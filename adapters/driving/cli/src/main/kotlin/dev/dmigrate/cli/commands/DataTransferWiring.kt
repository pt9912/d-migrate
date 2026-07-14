package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.cliAuditRecorder
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.config.ReverseAutoincrementResolver
import dev.dmigrate.cli.output.MessageResolver
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.LogScrubber
import dev.dmigrate.format.verify.CanonicalValueCodec
import java.nio.file.Path

internal data class DataTransferOptions(
    val source: String,
    val target: String,
    val tables: List<String>?,
    val filter: String?,
    val sinceColumn: String?,
    val since: String?,
    val onConflict: String,
    val triggerMode: String,
    val truncate: Boolean,
    val verify: Boolean,
    val atomic: Boolean,
    val chunkSize: Int,
    val parallel: Int,
    /** pipeline.parallelism-Slice: Origin (CLI-explizit?) + Label, s. DataTransferRequest. */
    val parallelFromCli: Boolean = false,
    val parallelSourceLabel: String = "--parallel",
    val readOnly: Boolean,
    /** LN-005: JDBC-Cursor-fetchSize für den Quell-Read (null = Dialekt-Default). */
    val fetchSize: Int? = null,
    val cliContext: CliContext,
    val configPath: Path?,
    val sqliteAutoincrementWidth: Int? = null,
)

/**
 * Wiring for `data transfer`: filter validation, request construction,
 * runner assembly. Mirrors [DataExportWiring]'s shape so the two
 * Filter-DSL-bearing commands share one validation contract.
 */
internal object DataTransferWiring {

    fun execute(
        options: DataTransferOptions,
        recorder: CliAuditRecorder = cliAuditRecorder(options.configPath),
    ): Int = recorder.record("data.transfer", listOf(options.source, options.target)) {
        executeInner(options)
    }

    private fun executeInner(options: DataTransferOptions): Int {
        if (options.filter != null && options.filter.isBlank()) {
            System.err.println(
                "Error: --filter must not be empty or whitespace-only. Omit the flag to transfer without a filter."
            )
            return 2
        }
        val parsedFilter = try {
            parseFilter(options.filter)
        } catch (e: FilterParseException) {
            val err = e.parseError
            val posHint = if (err.index != null) " (at position ${err.index})" else ""
            System.err.println("Error: Invalid --filter expression${posHint}: ${err.message}")
            return 2
        }
        val request = DataTransferRequest(
            source = options.source,
            target = options.target,
            tables = options.tables,
            filter = parsedFilter,
            sinceColumn = options.sinceColumn,
            since = options.since,
            onConflict = options.onConflict,
            triggerMode = options.triggerMode,
            truncate = options.truncate,
            verify = options.verify,
            atomic = options.atomic,
            chunkSize = options.chunkSize,
            parallel = options.parallel,
            parallelFromCli = options.parallelFromCli,
            parallelSourceLabel = options.parallelSourceLabel,
            readOnly = options.readOnly,
            fetchSize = options.fetchSize,
            cliConfigPath = options.configPath,
            quiet = options.cliContext.quiet,
            noProgress = options.cliContext.noProgress,
            sqliteAutoincrement = ReverseAutoincrementResolver(configPathFromCli = options.configPath)
                .resolve(options.sqliteAutoincrementWidth),
        )
        val runner = DataTransferRunner(
            sourceResolver = { src, cfgPath -> NamedConnectionResolver(configPathFromCli = cfgPath).resolve(src) },
            targetResolver = { tgt, cfgPath -> NamedConnectionResolver(configPathFromCli = cfgPath).resolve(tgt) },
            urlParser = EnvCredentialFiller().fillingParser(ConnectionUrlParser::parse),
            poolFactory = { config -> HikariConnectionPoolFactory.create(config) },
            driverLookup = { dialect -> DatabaseDriverRegistry.get(dialect) },
            urlScrubber = LogScrubber::maskUrl,
            // data transfer uses plain stderr for errors — no structured json/yaml
            // error envelope via OutputFormatter (see LF-012 / LN-016).
            printError = { msg, src ->
                val msgs = MessageResolver(options.cliContext.locale)
                System.err.println(msgs.text("cli.error.source_format", src, msg))
            },
            // LN-009: dialekt-neutrale Wert-Kanonik für --verify (formats-Adapter).
            valueCanonicalizer = CanonicalValueCodec(),
            // LN-049 Stufe 4: Store-Konsum je Verbindung (Quelle/Ziel), eine geteilte Master-Secret-Session.
            credentialFiller = CredentialFilling.perConnectionStoreFiller(),
        )
        return runner.execute(request)
    }
}
