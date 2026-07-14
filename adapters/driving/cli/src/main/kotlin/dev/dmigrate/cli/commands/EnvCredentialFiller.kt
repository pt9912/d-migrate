package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig

/**
 * LN-049 **Stufe 2**: ergänzt ein **fehlendes** DB-Passwort aus dem globalen Fallback
 * `D_MIGRATE_DB_PASSWORD` (connection-config-spec 4.1). Rein **additiv**:
 * - ein explizit (inline / `${VAR}`) gesetztes Passwort bleibt unangetastet (Stufe 1/3 gewinnt);
 * - no-auth-Dialekte (SQLite) werden übersprungen — **kein** Fill (sonst bräche z. B. jeder
 *   `sqlite:///…`-Zugriff, der gar kein Passwort kennt);
 * - ist `D_MIGRATE_DB_PASSWORD` leer/ungesetzt, bleibt die Config **unverändert** — **kein**
 *   fail-closed, damit passwortlose Auth (Postgres `peer`/`trust`/`.pgpass`, MySQL socket) möglich bleibt.
 *
 * **CLI-only:** Der MCP-Pfad komponiert diesen Filler **nie** in seinen `urlParser` (er nutzt den nackten
 * [ConnectionUrlParser]), damit die globale Server-Env nicht cross-principal auf MCP-Verbindungen
 * durchschlägt (Trennungs-Invariante). Ein „fehlend" ist `password.isNullOrEmpty()`, deckt also auch
 * `user:@host` und ein leer-expandiertes `${VAR}` ab.
 */
class EnvCredentialFiller(
    private val env: (String) -> String? = System::getenv,
) {

    /** Ergänzt ein fehlendes Passwort aus der Env (additiv, dialekt-gegatet). */
    fun fill(config: ConnectionConfig): ConnectionConfig {
        if (!config.password.isNullOrEmpty()) return config
        if (config.dialect == DatabaseDialect.SQLITE) return config
        val fromEnv = env(ENV_VAR)
        return if (fromEnv.isNullOrEmpty()) config else config.copy(password = fromEnv)
    }

    /**
     * CLI-Naht: liefert einen `urlParser`, der **parst und dann füllt**. Der MCP-Pfad reicht statt dessen
     * den nackten [parse] ein und bekommt so kein Fill.
     */
    fun fillingParser(parse: (String) -> ConnectionConfig): (String) -> ConnectionConfig =
        { url -> fill(parse(url)) }

    companion object {
        const val ENV_VAR = "D_MIGRATE_DB_PASSWORD"
    }
}
