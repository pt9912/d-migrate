package dev.dmigrate.server.application.connection

import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.server.core.principal.TenantId

/**
 * LF-012 / LN-011 / LN-017 / LN-027 Connection-Materialisierungs-Port.
 *
 * Aufgaben:
 *
 * - Discovery- und Resource-Pfade sehen ConnectionRefs SECRET-FREI
 *   (siehe LF-012 / LN-038 `ConnectionReferenceStore`).
 * - Erst der Job-Worker fordert eine [ConnectionConfig] mit
 *   aufgeloesten Credentials an — daher dieses Port-Interface, das
 *   produktiv von einer Adapter-Schicht (Secret-Resolver + URL-Builder)
 *   implementiert wird.
 *
 * Implementierungen MUESSEN:
 *
 * - Tenant-Scope durchsetzen ([connectionRef] muss zu [tenant] gehoeren;
 *   sonst werfen).
 * - Secret-Lookup auditierbar protokollieren (LF-012 / LN-011 / LN-017 / LN-027 — Secrets
 *   erscheinen nicht in Job-/Artefakt-/Audit-Projektionen).
 * - Bei fehlendem Eintrag oder Tenant-Mismatch eine fachliche
 *   Exception werfen (z.B. `ResourceNotFoundException`,
 *   `TenantScopeDeniedException`); der [SchemaReverseJobWorker]
 *   propagiert diese und der [dev.dmigrate.server.application.job.JobDispatcher]
 *   mappt sie auf [JobWorkerOutcome.Failed].
 */
fun interface ConnectionMaterializer {

    fun materialize(connectionRef: String, tenant: TenantId): ConnectionConfig
}
