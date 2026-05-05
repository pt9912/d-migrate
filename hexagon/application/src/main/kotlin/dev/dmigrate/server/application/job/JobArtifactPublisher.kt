package dev.dmigrate.server.application.job

import dev.dmigrate.server.core.job.JobRecord

/**
 * Phase E §7.7 Job-Artefakt-Publishing-Port.
 *
 * Tool-spezifische Worker (Schema-Reverse, Data-Profile, Schema-Compare)
 * delegieren das tatsaechliche Persistieren ihres Output-Artefakts
 * (Schema-YAML, Profile-Report, Diff-JSON) an eine
 * [JobArtifactPublisher]-Implementierung. Die Implementierung
 * verkapselt:
 *
 * - Serialisierung in das wire-stabile Format (yaml/json).
 * - Bytes -> [dev.dmigrate.server.ports.ArtifactContentStore].
 * - Indexed-Eintrag -> [dev.dmigrate.server.ports.ArtifactStore] mit
 *   tenant-/owner-Bindung aus [JobRecord].
 * - Sha256-Berechnung gemaess `spec/job-contract.md`.
 *
 * @return die wire-stabile Artefakt-Resource-URI im Format
 *   `dmigrate://tenants/<tenantId>/artifacts/<artifactId>` —
 *   diese landet in [JobWorkerOutcome.Succeeded.artifactRefs] und
 *   ueber den Dispatcher in `ManagedJob.artifacts`.
 *
 * @param payload typabhaengig vom konkreten Tool. Schema-Reverse
 *   uebergibt eine `SchemaDefinition`, Data-Profile einen Report,
 *   Schema-Compare einen Diff. Der konkrete Publisher kennt den
 *   Payload-Typ und delegiert die Serialisierung an seine eigene
 *   Strategie. Hier `Any` belassen, damit die Port-Surface tool-
 *   neutral bleibt; konkrete Adapter-Klassen pruefen den Typ und
 *   werfen `IllegalArgumentException` bei Inkompatibilitaet.
 */
fun interface JobArtifactPublisher {

    fun publish(job: JobRecord, payload: Any): String
}
