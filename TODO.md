# TODO — Cross-server support (shared DB + Redis)

This document is a **design + implementation plan**. It describes how to make
EnhancedEchest safe to run on **multiple servers sharing one database** (a BungeeCord / Velocity
network, or a Folia/Paper fleet). It is not implemented yet — pick up any phase below.

> **Status:** planned / not started.
> **Owner:** unassigned — open an issue and link this doc before starting a phase.

---

## Why this is needed

Today the entire **dupe-safety model is in-JVM**. Read
[`.claude/architecture/concurrency-and-dupe-safety.md`](.claude/architecture/concurrency-and-dupe-safety.md)
first — it is load-bearing context.

The short version: `ChestSessionManager`
([src/main/java/com/enhancedechest/service/ChestSessionManager.java](src/main/java/com/enhancedechest/service/ChestSessionManager.java))
guarantees "one shared `Inventory` per open chest, load-fresh on first open, save on last close" — but
only **within a single server process**. The `sessions` registry, `runExclusive`, `forceCloseAll`, and
`pendingSaves` all live in one JVM's heap. A second server has its own independent copy and there is **no
cross-server coordination and no DB-level locking**.

`saveChest` is a **blind `UPDATE`** with no version check
([AbstractSqlStorage.java:41](src/main/java/com/enhancedechest/storage/sql/AbstractSqlStorage.java#L41)),
so when two servers write the same chest it is **last-writer-wins** → silent item loss/duplication.

### Concrete bugs without this work (in priority order)

1. **Server-switch dupe (most likely).** Player edits a chest on server A, quits to switch to B.
   `PlayerQuitListener` only calls `detach`, which saves **async and does not block the disconnect**
   ([PlayerQuitListener.java:36](src/main/java/com/enhancedechest/listener/PlayerQuitListener.java#L36)).
   On a proxy the player can join B and `loadChest` **before A's async save lands** → stale read → dupe.
2. **Admin view + owner edit on different servers.** `/ee view` joins the live shared inventory
   ([ChestOpener#adminOpen](src/main/java/com/enhancedechest/service/ChestOpener.java#L211)). The shared
   inventory is per-JVM, so an admin on B and the owner on A edit two independent copies → overwrite.
3. **Permission-chest reconcile flapping.** `PermissionChestService.reconcile` runs on open using the
   player's permissions **on that server** ([ChestOpener.java:111](src/main/java/com/enhancedechest/service/ChestOpener.java#L111)).
   If permission state differs per server, PERM chests get granted on one and revoked on another, churning
   spill temp chests.
4. **Expiry double-spill.** Every server runs its own `ExpirySweeper` timer
   ([ExpirySweeper.java:50](src/main/java/com/enhancedechest/expiry/ExpirySweeper.java#L50)); multiple
   servers process the same expired chest → racing spill+delete.
5. **`createChest` index collision.** Index is `MAX(chest_index)+1` computed in Java with no
   `SELECT ... FOR UPDATE` ([AbstractSqlStorage.java:232](src/main/java/com/enhancedechest/storage/sql/AbstractSqlStorage.java#L232));
   two servers creating a chest for the same player can compute the same index → PK violation.
6. **Stale caches.** `PlayerSettingsCache` is per-server and never invalidated across servers.

---

## Chosen approach: single-owner-at-a-time (distributed lock)

We do **not** attempt real-time content sync between servers (per-item replication — too complex, dupe-
prone, not worth it for an ender chest plugin). Instead we **extend the existing "single owner" model
from one JVM to the whole cluster**:

- A chest may be **open on only one server at a time**.
- Open = acquire a cross-server lock → load fresh → serve viewers (with the existing in-JVM shared-
  inventory model still handling multiple local viewers on Paper).
- Close (last viewer) = save → **release the lock only after the save commits**.
- Admin / expiry mutations broadcast a **force-close** so the owning server (whichever it is) tears down
  its live session before the mutation runs.

This keeps **all dupe-safety logic inside `ChestSessionManager`** (per the CLAUDE.md constraint) — we are
only widening the scope of "exclusive" from JVM to cluster, behind two new interfaces.

### Source of truth

The **SQL database is always the source of truth for chest contents.** Redis stores **no item data** —
it only provides the lock and the pub/sub bus. Everything Redis does can also be done with the SQL DB
alone (see "Backends" below); **Redis is recommended, not mandatory**.

> ⚠️ **SQLite is not supported for cross-server.** File-level locking does not work across machines.
> Cross-server requires MySQL / MariaDB / PostgreSQL as the shared DB.

---

## New abstractions to introduce

Define two small interfaces in a new package `com.enhancedechest.cluster`, injected into
`ChestSessionManager` (and the expiry/admin paths). Provide three implementations of each so single-server
installs keep working unchanged.

### `ChestLock`

```java
package com.enhancedechest.cluster;

public interface ChestLock {
    /** Try to acquire the cluster lock for (owner, index). Returns a token if acquired, null if held. */
    LockToken tryAcquire(UUID owner, int index);
    /** Renew an owned lock (heartbeat) so it does not expire while a chest stays open. */
    boolean renew(LockToken token);
    /** Release a lock we own. Must verify ownership (never release another server's lock). */
    void release(LockToken token);
}
```

Implementations:

| Impl          | When               | How                                                                 |
|---------------|--------------------|---------------------------------------------------------------------|
| `NoOpLock`    | single server      | always acquires; no-op release. **Preserves current behavior.**     |
| `SqlLock`     | DB-only cluster    | `locked_by` / `locked_at` columns + atomic conditional `UPDATE` + manual TTL/heartbeat |
| `RedisLock`   | DB + Redis (rec.)  | `SET lock:ec:{owner}:{index} {serverId} NX PX {ttl}`; release via Lua compare-and-del; renew = `PEXPIRE` |

### `ClusterBus`

```java
package com.enhancedechest.cluster;

public interface ClusterBus {
    void publishForceClose(UUID owner, int index);          // admin resize/delete, expiry
    void publishInvalidateSettings(UUID player);            // PlayerSettingsCache
    void subscribe(ClusterListener listener);               // each server reacts locally
}
```

Implementations:

| Impl              | When             | How                                                  |
|-------------------|------------------|------------------------------------------------------|
| `NoOpBus`         | single server    | no-op                                                |
| `RedisBus`        | DB + Redis (rec.)| Redis pub/sub channel `enhancedechest:cluster`       |
| (`SqlBus` / proxy plugin-messaging are **not recommended** — see "Rejected alternatives") |

---

## Phased plan

### Phase 1 — Make writes safe even without any cluster infra (do this first)

These two changes drastically reduce data loss with **no new infrastructure** and are useful even before
locks exist. Low risk, high value.

- [ ] **Optimistic locking on save.** Add a `version BIGINT NOT NULL DEFAULT 0` column to `enderchests`.
  - `loadChest` reads `version` into `EnderChestData`
    ([model/EnderChestData.java](src/main/java/com/enhancedechest/model/EnderChestData.java)).
  - Change `SQL_SAVE` to `UPDATE ... SET container_data=?, version=version+1, last_updated=? WHERE
    player_uuid=? AND chest_index=? AND version=?`
    ([AbstractSqlStorage.java:41](src/main/java/com/enhancedechest/storage/sql/AbstractSqlStorage.java#L41)).
  - If `affectedRows == 0`: **do not overwrite.** Log loudly and surface the conflict; never swallow it
    (swallowing = silent item loss). Decide policy: reject + reload, or merge — start with reject + log.
  - Thread the expected version from the `Session` through `persist`
    ([ChestSessionManager.java:330](src/main/java/com/enhancedechest/service/ChestSessionManager.java#L330)).
  - Add a `ConfigMigrations` / schema-migration step (see
    [`.claude/architecture/storage-and-schema.md`](.claude/architecture/storage-and-schema.md) and
    [`.claude/architecture/migration-config-language.md`](.claude/architecture/migration-config-language.md)).
- [ ] **Atomic chest-index allocation.** Fix the `MAX(index)+1` race in `createChest`, `createPermChest`,
  `spillShrink`, `spillRemove` ([AbstractSqlStorage.java:226+](src/main/java/com/enhancedechest/storage/sql/AbstractSqlStorage.java#L226)).
  Use `SELECT ... FOR UPDATE` (MySQL/Postgres) when reading max, or catch the PK violation and retry, or a
  per-player counter row. Keep it portable across the three dialects or push it into the dialect subclasses.

### Phase 2 — Distributed lock + config (the core of cross-server safety)

- [ ] Add `server-id` and a `cross-server` config section. See
  [PluginConfig](src/main/java/com/enhancedechest/config/PluginConfig.java) and the config docs.
  ```yaml
  cross-server:
    enabled: false              # false = NoOp everything; behavior identical to today
    server-id: "survival-1"     # unique per server; used in lock tokens + bus
    backend: redis              # redis | database
    lock:
      ttl-millis: 30000         # auto-expire so a crashed server frees its locks
      heartbeat-millis: 10000
    redis:
      host: localhost
      port: 6379
      password: ""
  ```
- [ ] Implement `NoOpLock`, `SqlLock`, `RedisLock` (+ wire selection from config).
  - Bundle a Redis client (e.g. Jedis or Lettuce) **shaded and relocated under
    `com.enhancedechest.libs.*`** — see the relocation rules in `build.gradle.kts` and CLAUDE.md. Do not
    reference the original package without the relocation.
- [ ] Hook the lock into `ChestSessionManager`:
  - **Acquire** in `decideOpen` before the first `loadChest`
    ([ChestSessionManager.java:163-169](src/main/java/com/enhancedechest/service/ChestSessionManager.java#L163)).
    On failure, notify `chest.in-use` (the message key already exists for the Folia path) and abort.
  - **Renew** (heartbeat) while a session is live — schedule via `FoliaLib` async timer keyed by session.
  - **Release** as the **last step after `saveChest` commits** — in the `persist` future completion, in
    `removeViewer`, and in `forceCloseAll`. ⚠️ This release-after-save ordering is what fixes the
    server-switch dupe (bug #1): server B cannot acquire until A has finished saving.
- [ ] Make the lock **re-entrant per (owner,index) within the same server** so the existing multi-local-
  viewer model (Paper concurrent edit) still attaches to one session under one lock.

### Phase 3 — Cluster messaging, expiry leader, cache coherence

- [ ] Implement `NoOpBus` + `RedisBus`; wire from config.
- [ ] **Force-close broadcast.** All admin mutations and expiry currently call `forceCloseAll`, which only
  closes local viewers. Wrap them so they also `publishForceClose(owner, index)`; every server's
  `ClusterListener` runs its local `forceCloseAll`. Affected callers:
  - `ChestAdminCommand` (`/ee resize`, `/ee delete`)
    ([command/admin/ChestAdminCommand.java](src/main/java/com/enhancedechest/command/admin/ChestAdminCommand.java))
  - `ChestSpillService` ([service/ChestSpillService.java](src/main/java/com/enhancedechest/service/ChestSpillService.java))
  - `PermissionChestService.reconcile` revoke path
    ([service/PermissionChestService.java](src/main/java/com/enhancedechest/service/PermissionChestService.java))
- [ ] **Single-leader expiry.** Only one server should run the sweep
  ([ExpirySweeper.java:82](src/main/java/com/enhancedechest/expiry/ExpirySweeper.java#L82)). Either:
  - Redis leader election (`SET leader NX PX` + renew), or
  - a `expiry.run-on-this-server` config flag the admin sets on exactly one server.
  Each spill/delete inside the sweep must still go through the lock + force-close broadcast.
- [ ] **Settings cache invalidation.** Invalidate `PlayerSettingsCache`
  ([service/PlayerSettingsCache.java](src/main/java/com/enhancedechest/service/PlayerSettingsCache.java))
  on `publishInvalidateSettings`, or give it a short TTL, or read through to DB.
- [ ] **Permission reconcile policy.** Document that permissions **must be identical across servers**, or
  add a config to disable reconcile except on a designated server / make revoke require agreement. Decide
  and document — see
  [`.claude/architecture/commands-and-permissions.md`](.claude/architecture/commands-and-permissions.md#permission-granted-chests).

---

## Acceptance / test scenarios

There is no automated test suite (verification is by running on a Paper/Folia server). Before merging,
manually verify on a **2-server proxy network sharing one MySQL/Postgres DB**:

- [ ] Open the same chest on server A; opening on B reports "in use" (lock held), never loads stale data.
- [ ] Edit on A, immediately switch to B: contents on B reflect A's edit (no dupe, no loss). Repeat rapidly.
- [ ] `/ee resize` / `/ee delete` on A while the chest is **open on B**: B's GUI force-closes before the
  mutation, no dupe.
- [ ] Expiry fires for a chest open on another server: it force-closes there first; runs on exactly one
  server.
- [ ] Kill a server with a chest open (simulate crash): its lock expires after TTL and another server can
  open the chest. (DB-only backend: verify the manual TTL path.)
- [ ] `cross-server.enabled: false` → behavior byte-for-byte identical to today (NoOp impls).
- [ ] Confirm shaded Redis client is relocated under `com.enhancedechest.libs.*` in the final jar.

---

## Rejected alternatives (don't reach for these)

- **Real-time per-item content sync.** Too complex, dupe-prone; not worth it. Single-owner lock is enough.
- **Proxy plugin-messaging (BungeeCord/Velocity channels) for the bus.** It travels over a *player's*
  connection, so a server with no online players cannot send — force-close becomes unreliable exactly when
  an admin acts on a chest open elsewhere. Messages can be dropped (no ack/retry). If you truly cannot run
  Redis, prefer the **DB-only backend** (`SqlLock` + optimistic `version` + check the lock at open time),
  which never depends on delivering a message to another server, over plugin-messaging.

## Reference reading

- [`.claude/ARCHITECTURE.md`](.claude/ARCHITECTURE.md) — overall design
- [`.claude/architecture/concurrency-and-dupe-safety.md`](.claude/architecture/concurrency-and-dupe-safety.md) — **read first**
- [`.claude/architecture/storage-and-schema.md`](.claude/architecture/storage-and-schema.md) — schema + migrations
- [`.claude/architecture/commands-and-permissions.md`](.claude/architecture/commands-and-permissions.md) — admin ops + PERM chests
- [`.claude/architecture/expiry-and-temp-chests.md`](.claude/architecture/expiry-and-temp-chests.md) — expiry + spill
