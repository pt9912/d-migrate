package dev.dmigrate.driver.connection

/**
 * Neutraler SSL/TLS-Modus (LN-026). PG-Supermenge; die per-Dialekt-Projektion
 * (PG `sslmode=…`, MySQL `sslMode=…`) liegt in den jeweiligen `JdbcUrlBuilder`n
 * (Adapter), das Parsen der URL-Werte im `SslSettingsParser` (driver-common) —
 * Modell im Hexagon, Mapping im Adapter.
 */
enum class SslMode { DISABLE, ALLOW, PREFER, REQUIRE, VERIFY_CA, VERIFY_FULL }

/**
 * First-Class SSL/TLS-Einstellungen einer Verbindung (LN-026, Minimal-Scope:
 * typisiert + validiert).
 *
 * - [mode] `null` = nicht gesetzt → Treiber-Default (Verhaltens-Parität zum
 *   bisherigen Passthrough; d-migrate setzt keine eigenen Defaults).
 * - [rootCert] = CA-Zertifikat-Pfad (PG `sslrootcert`). MySQL-Client-CA /
 *   Truststore ist Nicht-Scope (nächste Tiefenstufe).
 */
data class SslSettings(
    val mode: SslMode? = null,
    val rootCert: String? = null,
)
