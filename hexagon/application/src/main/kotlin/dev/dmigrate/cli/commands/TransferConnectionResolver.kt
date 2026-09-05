package dev.dmigrate.cli.commands

import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import java.nio.file.Path

internal data class TransferEndpoint(
    val config: ConnectionConfig,
    val pool: ConnectionPool,
    val ref: String,
)

internal data class TransferConnections(
    val source: TransferEndpoint,
    val target: TransferEndpoint,
) {
    fun close() {
        try {
            target.pool.close()
        } catch (_: Exception) {
            // Keep transfer exit-code reporting stable; still attempt to close the source pool.
        }
        try {
            source.pool.close()
        } catch (_: Exception) {
            // Nothing useful can be reported here without hiding the original transfer outcome.
        }
    }
}

internal sealed interface TransferConnectionResult {
    data class Ok(val connections: TransferConnections) : TransferConnectionResult
    data class Exit(val code: Int) : TransferConnectionResult
}

internal class TransferConnectionResolver(
    private val sourceResolver: (String, Path?) -> String,
    private val targetResolver: (String, Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val urlScrubber: (String) -> String,
    private val userFacingErrors: UserFacingErrors,
    private val printError: (String, String) -> Unit,
    // LN-049 Stufe 4: Per-Connection-Filler, keyed nach dem rohen --source/--target. Identity-Default →
    // MCP füllt nicht; nur die CLI-Wiring injiziert den Store-Filler.
    private val credentialFiller: (ConnectionConfig, String) -> ConnectionConfig = { config, _ -> config },
) {

    fun resolve(request: DataTransferRequest): TransferConnectionResult {
        val safeSrc = userFacingErrors.scrubRef(request.source)
        val safeTgt = userFacingErrors.scrubRef(request.target)

        val srcUrl: String
        val srcRef: String
        try {
            srcUrl = sourceResolver(request.source, request.cliConfigPath)
            srcRef = resolvedRef(request.source, srcUrl)
        } catch (e: Exception) {
            printError("Source config: ${e.message}", safeSrc)
            return TransferConnectionResult.Exit(7)
        }

        val tgtUrl: String
        val tgtRef: String
        try {
            tgtUrl = targetResolver(request.target, request.cliConfigPath)
            tgtRef = resolvedRef(request.target, tgtUrl)
        } catch (e: Exception) {
            printError("Target config: ${e.message}", safeTgt)
            return TransferConnectionResult.Exit(7)
        }

        val srcCfg: ConnectionConfig
        val tgtCfg: ConnectionConfig
        try {
            // Nur die Quelle wird gelesen → read-only oeffnen; das Ziel bleibt read-write.
            srcCfg = credentialFiller(urlParser(srcUrl).copy(readOnly = request.readOnly), request.source)
            tgtCfg = credentialFiller(urlParser(tgtUrl), request.target)
        } catch (e: Exception) {
            printError("URL parse: ${e.message}", srcRef)
            return TransferConnectionResult.Exit(7)
        }

        // Kommando-Grenz-Gate VOR jedem Verbindungsversuch (nicht danach): ein
        // Pool-Bau schlaegt fuer eine nicht erreichbare Adresse ohnehin fehl,
        // aber undeterministisch je nach Treiber-/HikariCP-Fail-Fast-Timing --
        // das darf nicht mit der Gate-Ablehnung verwechselt werden (Exit 4 statt 2).
        DialectCommandGate.refusal(DialectCommandGate.GatedCommand.DATA_TRANSFER, srcCfg.dialect)?.let {
            printError(it, srcRef)
            return TransferConnectionResult.Exit(2)
        }
        DialectCommandGate.refusal(DialectCommandGate.GatedCommand.DATA_TRANSFER, tgtCfg.dialect)?.let {
            printError(it, tgtRef)
            return TransferConnectionResult.Exit(2)
        }

        val srcPool = try {
            poolFactory(srcCfg)
        } catch (e: Exception) {
            printError("Source connection: ${e.message}", srcRef)
            return TransferConnectionResult.Exit(4)
        }

        val tgtPool = try {
            poolFactory(tgtCfg)
        } catch (e: Exception) {
            try {
                srcPool.close()
            } catch (_: Exception) {
                // Keep reporting the target connection failure that prevented the transfer from starting.
            }
            printError("Target connection: ${e.message}", tgtRef)
            return TransferConnectionResult.Exit(4)
        }

        return TransferConnectionResult.Ok(
            TransferConnections(
                source = TransferEndpoint(srcCfg, srcPool, srcRef),
                target = TransferEndpoint(tgtCfg, tgtPool, tgtRef),
            )
        )
    }

    private fun resolvedRef(rawRef: String, resolvedUrl: String): String =
        if (rawRef.contains("://")) urlScrubber(resolvedUrl) else rawRef
}
