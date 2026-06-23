# Database

EnhancedEchest stores every ender chest's contents in a database. You choose the backend with the `database.type` option in `config.yml`.

| Backend | `type` value | Best for |
|---------|--------------|----------|
| **SQLite** | `sqlite` | Single servers, zero setup, works out of the box |
| **MySQL** | `mysql` | Networks sharing one database |
| **MariaDB** | `mariadb` | Networks sharing one database |
| **PostgreSQL** | `postgres` | Setups already running Postgres |

::: tip No drivers needed
All database drivers and the HikariCP connection pool are bundled and relocated inside the plugin jar. You never need to install drivers on your server.
:::

## SQLite (default)

SQLite requires no configuration. On first start the plugin creates the database file in its data folder:

```yaml
database:
  type: sqlite
  sqlite-file: enderchests.db
```

The file is created at `plugins/EnhancedEchest/enderchests.db`. SQLite always uses a single connection, so `pool-size` is ignored in this mode.

## MySQL / MariaDB

Point the plugin at an existing MySQL or MariaDB database:

```yaml
database:
  type: mysql          # or: mariadb
  host: localhost
  port: 3306
  database: enhancedechest
  username: root
  password: "your-password"
  pool-size: 10
```

- Create the database (schema) beforehand, for example `CREATE DATABASE enhancedechest;`
- The plugin creates and manages its own tables automatically
- The bundled driver is compatible with MySQL 5.7+ and 8.x

## PostgreSQL

```yaml
database:
  type: postgres
  host: localhost
  port: 5432
  database: enhancedechest
  username: postgres
  password: "your-password"
  pool-size: 10
```

The default PostgreSQL port is **5432**, so remember to change `port` from the MySQL default.

## Sharing data across servers

Because all data lives in the database, pointing several servers at the **same** MySQL/MariaDB/PostgreSQL database lets them share ender chest storage. The plugin's dupe-safe load/save model (loading fresh on open and saving immediately on close) keeps contents consistent as long as a player is only ever on one server at a time.

## Switching backends

To move to a different backend, change `database.type` (and the connection fields) and restart the server. Note that EnhancedEchest does **not** automatically copy existing rows between backends; only the [vanilla migration](/docs/migration) import is automated.
