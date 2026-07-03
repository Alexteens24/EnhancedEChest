package com.enhancedechest.migration;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Connection details for the <b>source</b> database of a {@code /ee import} DB→DB conversion, as filled
 * in by the import dialog. The destination is always the plugin's active backend; this describes the
 * old backend the admin is copying <i>from</i>.
 *
 * <p>Only the fields relevant to {@link #type} are used: {@code sqliteFile} for SQLite, and
 * {@code host}/{@code port}/{@code database}/{@code username}/{@code password} for the remote engines.
 * The other fields are ignored (the dialog is static and shows every field regardless of type).
 *
 * @param type       backend type: {@code sqlite}, {@code mysql}, {@code mariadb}, {@code postgres} or
 *                   {@code postgresql}
 * @param sqliteFile SQLite database file (absolute, or relative to the plugin data folder); SQLite only
 * @param host       remote host; MySQL/MariaDB/PostgreSQL only
 * @param port       remote port; MySQL/MariaDB/PostgreSQL only
 * @param database   remote database name; MySQL/MariaDB/PostgreSQL only
 * @param username   remote username; MySQL/MariaDB/PostgreSQL only
 * @param password   remote password (shown in cleartext in the dialog — documented); MySQL/MariaDB/PostgreSQL only
 */
public record SourceSpec(String type, String sqliteFile, String host, int port, String database,
                         String username, String password) {

    /** True when this spec describes a file-based SQLite source. */
    public boolean isSqlite() {
        return family(type).equals("sqlite");
    }

    /**
     * A stable identity string for equality checks against the active destination, so an import that
     * would read from the very database it is writing into can be refused. Two specs/backends pointing
     * at the same database produce the same identity: for SQLite the absolute file path, otherwise the
     * engine family plus {@code host:port/database} (host case-insensitive).
     *
     * @param dataFolder plugin data folder, used to resolve a relative SQLite file path
     */
    public String identity(Path dataFolder) {
        return identity(type, sqliteFile, host, port, database, dataFolder);
    }

    /** Builds the same identity string from raw parts (used for the active destination config too). */
    public static String identity(String type, String sqliteFile, String host, int port,
                                  String database, Path dataFolder) {
        String fam = family(type);
        if (fam.equals("sqlite")) {
            Path p = Path.of(sqliteFile == null ? "" : sqliteFile);
            Path resolved = (p.isAbsolute() ? p : dataFolder.resolve(p)).toAbsolutePath().normalize();
            return "sqlite:" + resolved;
        }
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String db = database == null ? "" : database;
        return fam + ":" + h + ":" + port + "/" + db;
    }

    /** Collapses driver aliases to a family: mariadb→mysql, postgresql→postgres. */
    public static String family(@Nullable String type) {
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "mariadb" -> "mysql";
            case "postgresql" -> "postgres";
            default -> t;
        };
    }
}
