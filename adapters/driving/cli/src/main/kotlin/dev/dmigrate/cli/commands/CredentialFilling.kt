package dev.dmigrate.cli.commands

import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.CredentialStoreException

/**
 * Baut den CLI-`urlParser` für **Single-Connection**-Ops: `parse → Stufe 2 (Env) → Stufe 4 (Store)`.
 * Stufe 4 greift nur, wenn [rawSource] ein Connection-**Name** ist (kein Inline-`://`) — dann ist der
 * Store-Lookup-Key genau dieser Name. Inline-URLs und der Default-Fall (`--source` weggelassen) machen in
 * diesem Schnitt **keinen** Store-Lookup (Namensdurchreichung für `default_*` = Folgeschnitt). Dual-
 * Connection-Ops (`transfer`, `compare`) sind bewusst ausgenommen (teilen einen `urlParser` → brauchen die
 * per-Connection-Namensdurchreichung). Der **MCP-Pfad** nutzt diesen Baustein **nie**.
 */
internal class CredentialFilling(
    rawSource: String?,
    stderr: (String) -> Unit = { System.err.println(it) },
    env: (String) -> String? = System::getenv,
    sessionFactory: () -> CredentialFillSession = ::defaultFillSession,
) {
    private val envFiller = EnvCredentialFiller(env)
    private val storeFiller: StoreCredentialFiller? =
        rawSource?.takeUnless { it.contains("://") }?.let { StoreCredentialFiller(it, sessionFactory(), stderr) }

    /** `parse → Env(2) → Store(4)`. */
    fun parser(parse: (String) -> ConnectionConfig = ConnectionUrlParser::parse): (String) -> ConnectionConfig =
        { url ->
            try {
                val afterEnv = envFiller.fill(parse(url))
                storeFiller?.fill(afterEnv) ?: afterEnv
            } catch (e: CredentialStoreException) {
                // Falsches Master-Secret / beschädigter Store = lokaler Fehler. Als IllegalArgumentException
                // signalisieren (wie ein URL-Parse-Fehler) — damit die Verbindungs-Runner es einheitlich auf
                // Exit 7 mit secret-freier Meldung mappen, statt als ungefangene Exception (Stacktrace, Exit 1)
                // zu entkommen. KEIN fail-open bei falschem Secret (der Nutzer soll die Passphrase korrigieren).
                throw IllegalArgumentException(e.message, e)
            }
        }

    /** Wie [parser], direkt auf eine bereits aufgelöste URL angewandt (für Wirings, die inline parsen). */
    fun fill(url: String, parse: (String) -> ConnectionConfig = ConnectionUrlParser::parse): ConnectionConfig =
        parser(parse)(url)

    companion object {
        /**
         * Legt den Store-Filler (Stufe 4) **über** einen bestehenden `urlParser` [base] (der bereits parst
         * und Stufe 2/Env füllt) — für Wirings mit Bundle-Seam, bei denen der Name erst am Runner-Bau
         * bekannt ist. Store nur bei einem Namen ([rawSource] ohne `://`); sonst wird [base] unverändert
         * zurückgegeben (der injizierte Seam bleibt also erhalten). Falsches Master-Secret / beschädigter
         * Store → IllegalArgumentException (→ Exit 7), kein fail-open.
         */
        fun storeOnTop(
            rawSource: String?,
            base: (String) -> ConnectionConfig,
            stderr: (String) -> Unit = { System.err.println(it) },
            sessionFactory: () -> CredentialFillSession = ::defaultFillSession,
        ): (String) -> ConnectionConfig {
            val name = rawSource?.takeUnless { it.contains("://") } ?: return base
            val storeFiller = StoreCredentialFiller(name, sessionFactory(), stderr)
            return { url ->
                try {
                    storeFiller.fill(base(url))
                } catch (e: CredentialStoreException) {
                    throw IllegalArgumentException(e.message, e)
                }
            }
        }

        /**
         * Per-Connection-Store-Filler (Stufe 4) für Dual-Connection-Ops (`data transfer`): füllt eine
         * ConnectionConfig, keyed nach dem **rohen** `--source`/`--target`-Ref (Store nur bei Name, kein
         * `://`). **Eine geteilte Session** über alle Connections → höchstens **ein** Master-Secret-Prompt
         * je Lauf (lazy: keine Session, wenn nie ein Name kommt). Falsches Master-Secret → IAE (→ Exit 7).
         */
        fun perConnectionStoreFiller(
            stderr: (String) -> Unit = { System.err.println(it) },
            sessionFactory: () -> CredentialFillSession = ::defaultFillSession,
        ): (ConnectionConfig, String) -> ConnectionConfig {
            val session by lazy(sessionFactory)
            return { config, rawRef ->
                val name = rawRef.takeUnless { it.contains("://") }
                if (name == null) {
                    config
                } else {
                    try {
                        StoreCredentialFiller(name, session, stderr).fill(config)
                    } catch (e: CredentialStoreException) {
                        throw IllegalArgumentException(e.message, e)
                    }
                }
            }
        }
    }
}

/**
 * **Prozess-weite** Fill-Session: das Master-Secret wird höchstens **einmal pro CLI-Prozess** beschafft und
 * von allen store-konsumierenden Verbindungen geteilt. Ops mit mehreren Ziel-Verbindungen — `transfer`,
 * `compare` (2 Operanden) und `migrate`/`rollback` (loadFromDb + Probes + Executor, die jeweils **eigen**
 * re-resolven) — bekommen so **einen** Master-Secret-Prompt statt n, ohne die Session durch jeden Layer
 * durchzureichen. Tests injizieren eigene Sessions; dieser Default wird dort nicht genutzt.
 */
private val processFillSession: CredentialFillSession by lazy {
    CredentialFillSession(
        masterSecretResolver = MasterSecretResolver(
            prompt = { System.console()?.readPassword("Master passphrase for credential store: ") },
        ),
        baseDir = defaultCredentialBaseDir(),
    )
}

private fun defaultFillSession(): CredentialFillSession = processFillSession
