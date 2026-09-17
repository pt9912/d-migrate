package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.SchemaReadReportInput
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
 * LF-012 / LN-011 / LN-017 / LN-027-Pipeline (Compare-Materialisierung → Diff → Artefakt-
 * Publish) mit eigenen Cancel-Checkpoints zwischen jedem Schritt.
 * LF-012 / LN-011 / LN-017 / LN-027 Compare-Cancel-Gate-Followup: dieser Worker ist die produktive
 * Antwort auf den LF-012 / LN-011 / LN-017 / LN-027-Block "SchemaCompareRunner has no token wiring
 * yet" — der CLI-`SchemaCompareRunner.execute(...)` bekommt einen
 * separaten Token-Parameter (siehe gleichnamiger Commit), der Worker
 * geht hier einen anderen Weg ueber [schemaLoader] statt ueber den
 * file-/db-Loader-Pfad des CLI-Pfads.
 *
 * [schemaLoader] kapselt die Quell-Aufloesung:
 *
 * - Connection-Refs (`dmigrate://tenants/<t>/connections/<id>`) gehen
 *   ueber [dev.dmigrate.server.application.connection.ConnectionMaterializer]
 *   + Reader; das Token wandert weiter durch die Treiber-Schichten
 *   (Cancel-Checkpoints).
 * - Schema-Refs (`dmigrate://tenants/<t>/schemas/<id>`) gehen ueber
 *   den `SchemaContentLoader`/-`Store` (LF-012 / LN-038) und sind bereits
 *   secret-frei.
 *
 * Fuer den Worker ist das transparent — er kennt nur das Lambda und
 * verlaesst sich darauf, dass es Tenant-Scope durchsetzt und den
 * Token an seine internen Layer weiterreicht. Eine aus einer Verbindung
 * gelesene Seite bringt ihr [SchemaReadResult] mit ([LoadedCompareSide]);
 * deren Reverse-Report veroeffentlicht [reportPublisher] nach dem
 * Vergleichsergebnis, Quelle vor Ziel — sonst blieben die Hinweise des
 * Readers ueber MCP stumm (`spec/mcp-server.md`).
 *
 * [comparator] und [publisher]: pure Funktionen ueber [SchemaDefinition]
 * bzw. das Vergleichsergebnis [R]. Was das Ergebnis ist, entscheidet die
 * Baustelle: der MCP-Job veroeffentlicht dieselben Funde wie das Werkzeug
 * `schema_compare`, nicht den rohen `SchemaDiff`. [R] bindet beide
 * aneinander — der Publisher nimmt genau, was der Comparator liefert.
 * Compare ist CPU-bound aber im Regelfall schnell; der Cancel-Checkpoint
 * VOR Compare reicht.
 */
class SchemaCompareJobWorker<R : Any>(
    private val sourceRef: String,
    private val targetRef: String,
    private val schemaLoader: (
        ref: String,
        tenant: TenantId,
        token: CancellationToken,
    ) -> LoadedCompareSide,
    private val comparator: (SchemaDefinition, SchemaDefinition) -> R,
    private val publisher: JobArtifactPublisher<R>,
    private val reportPublisher: JobArtifactPublisher<SchemaReadReportInput>,
) : JobWorker {

    override fun execute(job: JobRecord, token: CancellationToken): JobWorkerOutcome {
        token.throwIfCancellationRequested()
        val source = schemaLoader(sourceRef, job.tenantId, token)

        token.throwIfCancellationRequested()
        val target = schemaLoader(targetRef, job.tenantId, token)

        token.throwIfCancellationRequested()
        val result = comparator(source.schema, target.schema)

        token.throwIfCancellationRequested()
        val artifactRef = publisher.publish(job, result)
        val reportRefs = listOf(sourceRef to source, targetRef to target).mapNotNull { (ref, side) ->
            side.readResult?.let { reportPublisher.publish(job, connectionReport(ref, it)) }
        }

        return JobWorkerOutcome.Succeeded(artifactRefs = listOf(artifactRef) + reportRefs)
    }
}

/**
 * Eine geladene Vergleichsseite. [readResult] traegt nur eine aus einer
 * Verbindung gelesene Seite — mit den Notes und uebersprungenen Objekten
 * des Readers; ein gespeichertes Schema hat keinen Reverse-Report.
 */
data class LoadedCompareSide(
    val schema: SchemaDefinition,
    val readResult: SchemaReadResult? = null,
) {
    companion object {
        /** Eine aus einer Verbindung gelesene Seite. */
        fun read(result: SchemaReadResult): LoadedCompareSide = LoadedCompareSide(result.schema, result)
    }
}
