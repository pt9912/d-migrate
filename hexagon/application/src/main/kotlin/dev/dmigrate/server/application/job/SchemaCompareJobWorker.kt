package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
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
 * Token an seine internen Layer weiterreicht.
 *
 * [comparator] und [publisher]: pure Funktionen ueber [SchemaDefinition]
 * bzw. [SchemaDiff]. Compare ist CPU-bound aber im Regelfall schnell;
 * der Cancel-Checkpoint VOR Compare reicht.
 */
class SchemaCompareJobWorker(
    private val sourceRef: String,
    private val targetRef: String,
    private val schemaLoader: (
        ref: String,
        tenant: TenantId,
        token: CancellationToken,
    ) -> SchemaDefinition,
    private val comparator: (SchemaDefinition, SchemaDefinition) -> SchemaDiff,
    private val publisher: JobArtifactPublisher,
) : JobWorker {

    override fun execute(job: JobRecord, token: CancellationToken): JobWorkerOutcome {
        token.throwIfCancellationRequested()
        val source = schemaLoader(sourceRef, job.tenantId, token)

        token.throwIfCancellationRequested()
        val target = schemaLoader(targetRef, job.tenantId, token)

        token.throwIfCancellationRequested()
        val diff = comparator(source, target)

        token.throwIfCancellationRequested()
        val artifactRef = publisher.publish(job, diff)

        return JobWorkerOutcome.Succeeded(artifactRefs = listOf(artifactRef))
    }
}
