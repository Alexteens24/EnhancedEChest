package com.enhancedechest.storage.sql;

import com.enhancedechest.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;

public final class PostgresStorage extends AbstractSqlStorage {

    // BYTEA maps directly to byte[] via JDBC — no size cap unlike MySQL's BLOB tiers.
    private static final String INIT_SQL = """
            CREATE TABLE IF NOT EXISTS enderchests (
                player_uuid    VARCHAR(36)  NOT NULL,
                container_data BYTEA        NOT NULL,
                migrated       SMALLINT     NOT NULL DEFAULT 0,
                last_updated   BIGINT       NOT NULL DEFAULT 0,
                PRIMARY KEY (player_uuid)
            )
            """;

    // PostgreSQL and SQLite share this standard ISO upsert syntax.
    private static final String UPSERT_SQL = """
            INSERT INTO enderchests (player_uuid, container_data, migrated, last_updated)
            VALUES (?, ?, 0, ?)
            ON CONFLICT (player_uuid) DO UPDATE SET
                container_data = EXCLUDED.container_data,
                last_updated   = EXCLUDED.last_updated
            """;

    public PostgresStorage(PluginConfig config) {
        super(buildConfig(config), INIT_SQL, UPSERT_SQL);
    }

    private static HikariConfig buildConfig(PluginConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:postgresql://" + config.getDbHost() + ":" + config.getDbPort()
                + "/" + config.getDbName());
        hc.setUsername(config.getDbUsername());
        hc.setPassword(config.getDbPassword());
        hc.setMaximumPoolSize(config.getDbPoolSize());
        hc.setMinimumIdle(2);
        hc.setConnectionTestQuery("SELECT 1");
        hc.setPoolName("EnhancedEChest-Postgres");
        hc.setConnectionTimeout(10_000);
        hc.setIdleTimeout(600_000);
        hc.setMaxLifetime(1_800_000);
        return hc;
    }
}
