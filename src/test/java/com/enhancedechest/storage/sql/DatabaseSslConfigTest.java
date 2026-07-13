package com.enhancedechest.storage.sql;

import com.enhancedechest.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSslConfigTest {

    @Test
    void sslDefaultsToFalse() {
        PluginConfig config = config(null);

        assertFalse(config.isDbSsl());
        assertTrue(MysqlStorage.buildJdbcUrl(config).contains("sslMode=disable"));
        assertEquals("jdbc:postgresql://db.example.com:5432/echest",
                PostgresStorage.buildJdbcUrl(config));
    }

    @Test
    void sslTrueRequiresEncryptionForRemoteBackends() {
        PluginConfig config = config(true);

        assertTrue(config.isDbSsl());
        assertTrue(MysqlStorage.buildJdbcUrl(config).contains("sslMode=trust"));
        assertEquals("jdbc:postgresql://db.example.com:5432/echest?sslmode=require",
                PostgresStorage.buildJdbcUrl(config));
    }

    private static PluginConfig config(Boolean ssl) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("database.host", "db.example.com");
        yaml.set("database.port", 5432);
        yaml.set("database.database", "echest");
        if (ssl != null) {
            yaml.set("database.ssl", ssl);
        }
        return new PluginConfig(yaml);
    }
}
