package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.ReverseSourceKind
import dev.dmigrate.driver.ReverseSourceRef
import dev.dmigrate.driver.SchemaReadReportInput
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.server.application.connection.ConnectionMaterializer
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
 * Verkettet die drei LF-012 / LN-011 / LN-017 / LN-027-Schritte fuer einen Reverse-Job:
 *
 * 1. **Connection-Materialisierung** — `connectionRef` per
 *    [ConnectionMaterializer] in eine credential-tragende
 *    [ConnectionConfig] aufloesen. Discovery- und Resource-Pfade
 *    sahen die Ref vorher secret-frei.
 * 2. **Schema-Reader** — [readSchema]-Lambda liest das Quell-Schema
 *    mit Cancel-Token-Propagation. Konkrete Wiring-Adapter
 *    instanziieren das Lambda ueber den `JdbcMetadataSession`-Pfad
 *    (LF-012 / LN-011 / LN-017 / LN-027) und reichen den Token an alle Statement-Grenzen
 *    durch.
 * 3. **Artefakt-Publish** — [JobArtifactPublisher.publish] persistiert
 *    den serialisierten Schema-Output und liefert die wire-stabile
 *    `dmigrate://...artifacts/<id>`-URI fuer
 *    `JobWorkerOutcome.Succeeded.artifactRefs`. Danach veroeffentlicht
 *    [reportPublisher] den Reverse-Report (Notes und uebersprungene
 *    Objekte des Readers) — dieselbe Trennung wie `schema reverse` in der
 *    CLI: das Schema-Dokument traegt keine Notes, der Report schon. Ohne ihn
 *    blieben die Hinweise des Readers ueber MCP stumm, auch die
 *    Bestaetigung einer deklarierten Praeferenz
 *    (`spec/dialect-preference-mechanism.md`, „Nicht stumm"). Die
 *    Reihenfolge der Verweise ist Vertrag (`spec/mcp-server.md`): erst das
 *    Schema, dann der Report.
 *
 * Cancel-Verhalten (LF-012 / LN-011 / LN-017 / LN-027 + Cancel-Checkpoints):
 *
 * - VOR Materialisierung, VOR Schema-Read und VOR Publish wird der
 *   Token gepoll't ([CancellationToken.throwIfCancellationRequested]).
 * - Im Schema-Reader propagiert der Token weiter durch die Treiber-
 *   Schichten (E0.4-/LF-012 / LN-011 / LN-017 / LN-027-Adapter).
 * - Eine [dev.dmigrate.core.cancel.OperationCancelledException] mit
 *   `source = JOB_CANCEL` (Default) wandert hoch zum Dispatcher, der
 *   sie in `JobWorkerOutcome.Cancelled` umsetzt.
 *
 * Per LF-012 / LN-011 / LN-017 / LN-027 ist dieser Worker pro Job konstruiert ([connectionRef]
 * baked-in). Die Tool-Handler/Orchestrator bauen ihn unmittelbar
 * nach dem `JobStartTransaction.commit` aus den Tool-Args zusammen
 * und uebergeben ihn an den [JobDispatcher].
 */
class SchemaReverseJobWorker(
    private val connectionRef: String,
    private val materializer: ConnectionMaterializer,
    private val readSchema: (ConnectionConfig, CancellationToken) -> SchemaReadResult,
    private val publisher: JobArtifactPublisher<SchemaDefinition>,
    private val reportPublisher: JobArtifactPublisher<SchemaReadReportInput>,
) : JobWorker {

    override fun execute(job: JobRecord, token: CancellationToken): JobWorkerOutcome {
        token.throwIfCancellationRequested()
        val config = materializer.materialize(connectionRef, job.tenantId)

        token.throwIfCancellationRequested()
        val result = readSchema(config, token)

        token.throwIfCancellationRequested()
        val schemaRef = publisher.publish(job, result.schema)
        val reportRef = reportPublisher.publish(job, connectionReport(connectionRef, result))

        return JobWorkerOutcome.Succeeded(artifactRefs = listOf(schemaRef, reportRef))
    }
}

/**
 * Der Reverse-Report einer aus einer Server-Verbindung gelesenen Seite: die
 * Quelle ist der secret-freie Verbindungs-Verweis.
 */
internal fun connectionReport(connectionRef: String, result: SchemaReadResult): SchemaReadReportInput =
    SchemaReadReportInput(ReverseSourceRef(ReverseSourceKind.CONNECTION, connectionRef), result)
