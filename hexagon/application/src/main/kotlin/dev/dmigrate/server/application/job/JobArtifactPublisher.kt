package dev.dmigrate.server.application.job

import dev.dmigrate.server.core.job.JobRecord

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
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
 * [P] ist der Nutzlasttyp des Workers: Schema-Reverse veroeffentlicht eine
 * `SchemaDefinition`, Data-Profile einen Report, Schema-Compare das
 * Vergleichsergebnis, das die Baustelle waehlt. Der Port bleibt damit
 * tool-neutral, und trotzdem prueft der Compiler, dass ein Worker nur
 * veroeffentlicht, was sein Publisher schreiben kann — ohne Typpruefung zur
 * Laufzeit. Kontravariant (`in`): ein Publisher fuer `Any` passt ueberall.
 */
fun interface JobArtifactPublisher<in P : Any> {

    fun publish(job: JobRecord, payload: P): String
}
