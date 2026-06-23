# Migration

If your players already have items in their **vanilla** ender chests, EnhancedEchest can import that data into its own storage so nothing is lost when you install the plugin.

## How it works

When a player is migrated, their 27 vanilla ender chest slots are copied into their EnhancedEchest **chest #1**, and the vanilla ender chest is cleared. The whole operation happens in a single server tick:

1. Chest #1 is created at full size if it doesn't exist yet
2. The vanilla contents are written into the head slots of chest #1
3. The data is saved to the database, the vanilla ender chest is cleared, and the player is flagged as migrated, all at once

::: info Single-location guarantee
There is never a moment where the items exist in both the vanilla ender chest and the plugin database. This makes migration safe against duplication.
:::

Each player is migrated **only once**. Players already flagged as migrated are skipped.

## Automatic migration on join

To migrate players automatically the first time they join after the plugin is installed, enable it in `config.yml`:

```yaml
migration:
  enabled: true
```

With this on, any un-migrated player has their vanilla ender chest imported the moment they join. Once everyone you care about has logged in, you can turn it back off.

## Manual migration

Admins can trigger migration on demand for players who are online:

| Command | Effect |
|---------|--------|
| `/ee migrate run <player>` | Migrate a single online player |
| `/ee migrate run all` | Migrate every player currently online |

Both require the `enhancedechest.admin.migrate.run` permission. Players who are already migrated are reported as skipped.

::: warning Online players only
Migration reads the player's live vanilla ender chest, so it only works for players who are **currently online**. Offline players are migrated automatically on their next join if `migration.enabled` is `true`.
:::
