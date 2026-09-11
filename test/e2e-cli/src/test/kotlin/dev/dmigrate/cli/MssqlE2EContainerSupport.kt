package dev.dmigrate.cli

import java.time.Duration

/**
 * Wie lange ein SQL-Server-Container zum Hochfahren bekommt.
 *
 * Eigene Konstante, weil dieses Modul die des Integrationsmoduls nicht sieht.
 * Der Grund ist derselbe: der Container startet, nimmt aber unter Last nicht
 * rechtzeitig Verbindungen an, und die Testcontainers-Vorgabe ist dafuer zu
 * knapp bemessen.
 */
internal val MSSQL_STARTUP_TIMEOUT: Duration = Duration.ofMinutes(5)
