package dev.dmigrate.server.application.quota

/**
 * LF-012 / LN-011 / LN-017 / LN-027 InMemoryQuotaReservationOwnerStore Contract-Test.
 *
 * Die gesamte Atomicity-/Lifecycle-Suite lebt in
 * [QuotaReservationOwnerStoreContractTests] und wird hier mit der
 * In-Memory-Factory instanziiert. Persistente Implementoren (z.B.
 * JDBC) muessen denselben Contract durchlaufen — siehe
 * `spec/phase-e-port-atomicity.md` Abschnitt (5).
 *
 * Migration-Hinweis: bis zur LF-012 / LN-011 / LN-017 / LN-027 Review-Bereinigung lebten die
 * Tests als FunSpec direkt in dieser Datei. Sie wurden nach
 * QuotaReservationOwnerStoreContractTests verschoben + um
 * Atomicity-Tests (parallele markX-/register-CAS) erweitert. Diese
 * Datei haelt nur noch die InMemory-Bindung.
 */
class InMemoryQuotaReservationOwnerStoreContractTest :
    QuotaReservationOwnerStoreContractTests({ InMemoryQuotaReservationOwnerStore() })
