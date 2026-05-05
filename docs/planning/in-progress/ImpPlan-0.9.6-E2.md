# Implementierungsplan: 0.9.6 - Phase E2 `Persistente Phase-E-Port-Adapter (JDBC/Postgres)`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: E2 (Sub-Plan zu Phase E — `Persistente Phase-E-Port-Adapter`)
> **Status**: Entwurf v2 (2026-05-05) — Review-1-Befunde eingearbeitet
> (Recovery-CAS, InitResume-Schema, transitionStatus-Transformer,
> Quota-Counter-UPSERT, Spec-Section-Refs, Tx-Primitive-Lokalisierung).
> Wartet auf Architektur-Approval (§ 3 + § 10).
> **Positionierung**: nach Phase E, **vor** Phase F (`ImpPlan-0.9.6-F.md`,
> Datenoperationen) — siehe § 0 zur Begründung.
> **Referenz**: `spec/phase-e-port-atomicity.md` (§§ 1–6 + § E + Cross-Refs);
> `docs/planning/in-progress/ImpPlan-0.9.6-E.md`;
> `docs/planning/open/ImpPlan-0.9.6-F.md`;
> `hexagon/ports-common/src/main/kotlin/dev/dmigrate/server/ports/IdempotencyStore.kt`;
> `hexagon/ports-common/src/main/kotlin/dev/dmigrate/server/ports/JobStartTransaction.kt`;
> `hexagon/ports-common/src/main/kotlin/dev/dmigrate/server/ports/JobStore.kt`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/server/core/idempotency/IdempotencyKey.kt`
> (`InitResumeScope`);
> `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/quota/QuotaReservationOwnerStore.kt`;
> `adapters/driven/driver-postgresql/`; `adapters/driven/driver-common/`.

---

## 0. Positionierung — warum E2, nicht F+1

E2 ist ein **Sub-Plan zu Phase E**, kein neuer Hauptphasen-Slot.
Begründung:

- Der Atomicity-Vertrag (`spec/phase-e-port-atomicity.md`) ist in
  Phase E entstanden; das Re-Review hat aufgedeckt, dass die
  persistente Implementierung des Vertrags fehlt.
- Phase F (Datenoperationen) und Phase G bauen auf Phase-E-Ports auf
  und behandeln sie als Black-Box. Sie sind **inhaltlich unabhängig**
  von der Persistenz-Frage; auf InMemory-Ports lauffähig.
- Phase E kann erst dann produktions-deploybar als „done" gelten,
  wenn E2 abgeschlossen ist. Solange das nicht der Fall ist, lebt
  Phase E mit einem klaren Caveat (InMemory-only).

**Vorschlag zur Reihenfolge**: E2 SOLL vor F starten, MUSS aber nicht
vor F abgeschlossen sein. F kann parallel auf InMemory-Ports begonnen
werden, sofern E2 vor dem ersten Production-Deploy fertig ist.
Final-entscheidung beim Owner — siehe § 10 Q6.

## 1. Ziel

Aus dem Phase-E-Re-Review: `spec/phase-e-port-atomicity.md` dokumentiert
für jeden atomicity-relevanten Port, **was** persistent atomar passieren
muss, lässt aber **wie** unbeantwortet — der Satz „persistente Backings
müssen ein gemeinsames Transaktions-Primitive bereitstellen" ist eine
Anforderung, kein Lieferobjekt.

Phase E2 schließt die Lücke mit:

1. einem **Adapter-internen** `JdbcTransactionRunner` (kein hexagon-
   level Port — Begründung in § 3.5)
2. einem persistenten Adapter-Modul `adapters/driven/persistence-jdbc`
3. JDBC/Postgres-Implementierungen für die fünf atomicity-relevanten
   Phase-E-Ports — die vier aus `spec/phase-e-port-atomicity.md`
   §§ 1-6 PLUS `IdempotencyStore.reserveInitResume` (Phase-C-Pfad,
   gleiche Tabelle ist nicht ausreichend, siehe § 4.1.bis):
   - `IdempotencyStore` (regular + InitResume-Pfad)
   - `JobStore` (mit `transitionStatus`-Transformer + `markCancelRequested`)
   - `JobStartTransaction` (komponiert IdempotencyStore + JobStore in
     einer DB-TX)
   - `QuotaService` + `QuotaReservationOwnerStore`
4. Flyway-Initial-Migration (V1) für das Server-State-Schema
5. Contract-Test-Lauf gegen Testcontainers-Postgres → die Atomicity-
   Verträge aus `spec/phase-e-port-atomicity.md` §§ 1–6 sind
   **ausführbar verifiziert**, nicht nur dokumentiert.

Out: Cluster-/Multi-Instance-Mode, LISTEN/NOTIFY, Schema-Sharding,
Online-Migration aus existierenden InMemory-Deployments.

## 2. Motivation

`spec/phase-e-port-atomicity.md` § 3 beschreibt z.B.:

> `IdempotencyStore.commit` und `JobStore.save` MÜSSEN gemeinsam
> sichtbar werden.

Im InMemory ist das ein `synchronized`-Block. Persistent ist das eine
gemeinsame DB-Transaktion über zwei Tabellen. Heute existiert keine
Stelle im Code, die diese Transaktions-Klammer bereitstellt — der
`JobStartTransaction`-Port ist nur eine Anwendungs-Komposition,
keine DB-Transaktion. Phase E2 liefert:

- den **Mechanismus** (`JdbcTransactionRunner` + connection-scoped
  Adapter-interne Helfer)
- die **Implementierung** (JDBC/Postgres)
- die **Verifikation** (Contract-Tests laufen gegen den realen Adapter)

## 3. Architektur-Entscheidungen

> Jede Entscheidung ist **Vorschlag** — Begründung kompakt; offene
> Fragen sind in § 10 gesammelt. Vor E2.1-Start bestätigt der Owner
> die Liste.

### 3.1 DB-Backend: Postgres-only für Server-State

- **Wahl**: PostgreSQL ≥ 14 (für `INSERT … ON CONFLICT … RETURNING`
  inklusive des `WHERE`-Predicates auf `DO UPDATE`, `JSONB`,
  `GREATEST` mit `timestamptz`, `SELECT … FOR UPDATE`).
- **Nicht**: H2 (semantische Abweichungen bei `ON CONFLICT`, JSONB,
  `FOR UPDATE`); SQLite (kein gleichwertiges `RETURNING`-Verhalten,
  Single-Writer-Limit).
- **Server-State ≠ Migration-Target**: d-migrate ist treiberseitig
  multi-DB, der **Server selbst** läuft aber gegen genau eine DB.
  Postgres ist die natürliche Wahl (existierender `driver-postgresql`-
  Adapter, Testcontainers-Setup ist da).

### 3.2 Migration-Tool: Flyway

- **Wahl**: Flyway (existiert bereits als Test-Dep in
  `adapters/driven/integrations/build.gradle.kts`).
- **Nicht**: Liquibase (XML/YAML-DSL überflüssig); hand-rolled SQL-
  Versionierung.
- **Schema-Ownership**: Server-State-Schema liegt isoliert im neuen
  Modul, NICHT in `driver-postgresql` (das ist ein Migration-Target-
  Adapter, andere Verantwortung).

### 3.3 SQL-Framework: plain JDBC + Kotlin-Helpers

- **Wahl**: `java.sql.Connection`/`PreparedStatement` + interne
  Kotlin-Extensions (`Connection.exec(sql, vararg params)` etc.).
- **Nicht**: jOOQ (zu schwer für die ~15 Statements pro Adapter);
  Exposed (DSL + ORM-Mix passt nicht zur hexagonalen Trennung);
  Spring Data (kein Spring im Projekt).
- Konsistent mit `driver-postgresql`/`driver-mysql` (auch plain JDBC).

### 3.4 Modul-Layout: ein Modul `adapters/driven/persistence-jdbc`

- **Wahl**: ein Gradle-Modul für alle Phase-E-Backings — sie teilen
  Schema, Migrations und das `JdbcTransactionRunner`-Primitive.
- **Nicht**: ein Modul pro Port (Schema-Versionierung würde sich
  duplizieren; cross-port-Transaktionen wie `JobStartTransaction.commit`
  würden Modul-Grenzen kreuzen).
- Pfad: `adapters/driven/persistence-jdbc` (passt zur `driven`-
  Konvention).

### 3.5 Transaction-Primitive — adapter-intern, nicht als Hexagon-Port

> **Review-Fix Medium-6**: Der Erst-Entwurf hat `TransactionRunner` in
> `hexagon/ports-common/.../tx/` mit `TxHandle.connection: java.sql.Connection`
> vorgeschlagen. Das ist inkonsistent: ein hexagon-Port darf JDBC
> nicht im Typ tragen, eine InMemory-No-op-Impl müsste eine Fake-
> Connection liefern. Die Hexagon-Abstraktion für „mehrere Ports
> committen gemeinsam" existiert bereits — `JobStartTransaction`. Eine
> zweite Abstraktion ist Doppelung.

**Beschluss**: `JdbcTransactionRunner` lebt im JDBC-Adapter-Modul,
ist `internal`/package-private. Aufbau:

```kotlin
// adapters/driven/persistence-jdbc/.../internal/JdbcTransactionRunner.kt
internal class JdbcTransactionRunner(private val ds: javax.sql.DataSource) {
    fun <T> inTransaction(block: (java.sql.Connection) -> T): T {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                return result
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }
}
```

`JdbcJobStartTransaction` injiziert den Runner UND
`JdbcIdempotencyStore`/`JdbcJobStore`. Cross-store-Operationen rufen
`internal fun commitWithConnection(conn, ...)` auf den Stores auf
(neben den public Port-Methoden, die ihre eigene Connection borgen).

InMemory-Welt bleibt unverändert: `synchronized`-Blöcke in
`InMemoryJobStartTransaction` brauchen keinen Runner. Es entsteht
keine zweite Hierarchie im Hexagon.

**Isolation**: Default `READ_COMMITTED`. `SERIALIZABLE` nur dort, wo
der jeweilige Atomicity-Vertrag es nachweislich braucht — pro Port-
Operation in § 6 begründet.

### 3.6 Connection-Pooling: HikariCP

Existiert schon in `driver-common`, gleiche Wahl. Ein `HikariDataSource`
pro Server-Instanz, von außen via `application.yaml`/Env konfiguriert
(separate URL als die Migration-Targets).

### 3.7 Test-Strategie: Testcontainers-Postgres, geteilte Container-Instanz pro Test-Suite

- **Wahl**: `PostgreSQLContainer("postgres:16-alpine")` als Class-
  Scoped-Singleton pro Contract-Test, **Schema-pro-Test** für
  Isolation (`CREATE SCHEMA test_<random>; SET search_path=…`).
- **Nicht**: Container-pro-Test (zu langsam, ~3s Start).
- CI-Risk: Bekannter `kover-CI-Flake`-Pattern aus Memory — falls
  Coverage knapp unter 90% landet, lokale Verifikation +
  `gh run rerun --failed`.

## 4. Schema-Skizze (V1__phase_e_initial.sql)

> Endgültiges DDL kommt mit E2.2; folgendes ist die Architektur-
> Vorabstimmung für § 3.

### 4.1 IdempotencyStore — regulärer Pfad

```sql
CREATE TABLE idempotency_reservations (
  tenant_id          TEXT      NOT NULL,
  caller_id          TEXT      NOT NULL,
  tool_name          TEXT      NOT NULL,
  idempotency_key    TEXT      NOT NULL,
  state              TEXT      NOT NULL,    -- PENDING|AWAITING_APPROVAL|COMMITTED|DENIED|FAILED
  claimed            BOOLEAN   NOT NULL DEFAULT FALSE, -- internal marker: claimApproved winner
  payload_fingerprint TEXT     NOT NULL,
  result_ref         TEXT,
  challenge          JSONB,                  -- ApprovalChallenge serialized
  reason             TEXT,
  expires_at         TIMESTAMPTZ NOT NULL,   -- lease for non-terminal, outcome expiry for terminal
  retention_until    TIMESTAMPTZ NOT NULL,   -- terminal-state retention; equals expires_at once terminal
  created_at         TIMESTAMPTZ NOT NULL,
  updated_at         TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, caller_id, tool_name, idempotency_key)
);
CREATE INDEX idempotency_expiry ON idempotency_reservations (retention_until);
```

### 4.1.bis IdempotencyStore — InitResume-Pfad (Phase-C-Upload-Init)

> **Review-Fix High-2**: Der Erst-Entwurf hatte `reserveInitResume`
> nicht modelliert. `InitResumeScope` (`tenantId`, `callerId`,
> `toolName`, `clientRequestId`) ist eine andere Identitäts-Tupel
> als `IdempotencyScope` (`idempotencyKey`). Polymorph in einer
> Tabelle wäre fragil — separate Tabelle ist sauberer.

```sql
CREATE TABLE init_resume_reservations (
  tenant_id           TEXT NOT NULL,
  caller_id           TEXT NOT NULL,
  tool_name           TEXT NOT NULL,
  client_request_id   TEXT NOT NULL,
  session_id          TEXT NOT NULL,
  payload_fingerprint TEXT NOT NULL,
  expires_at          TIMESTAMPTZ NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL,
  updated_at          TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, caller_id, tool_name, client_request_id)
);
CREATE INDEX init_resume_expiry ON init_resume_reservations (expires_at);
```

### 4.2 JobStore

```sql
CREATE TABLE jobs (
  tenant_id        TEXT NOT NULL,
  job_id           TEXT NOT NULL,
  status           TEXT NOT NULL,      -- QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED
  managed_job      JSONB NOT NULL,     -- ManagedJob serialized
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  cancel_source    TEXT,
  created_at       TIMESTAMPTZ NOT NULL,
  updated_at       TIMESTAMPTZ NOT NULL,
  expires_at       TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, job_id)
);
CREATE INDEX jobs_expiry ON jobs (expires_at);
CREATE INDEX jobs_status ON jobs (tenant_id, status);
```

### 4.3 QuotaReservationOwner

```sql
CREATE TABLE quota_reservation_owners (
  owner_id          TEXT NOT NULL PRIMARY KEY,
  reservation       JSONB NOT NULL,    -- QuotaReservation serialized
  state             TEXT NOT NULL,     -- PENDING|COMMITTED|RELEASED|REFUNDED
  lease_expires_at  TIMESTAMPTZ NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX quota_owners_expiry ON quota_reservation_owners (lease_expires_at)
  WHERE state = 'PENDING';
```

### 4.4 QuotaCounter

> **Review-Fix High-4**: `limit_value` lebt NICHT in der Tabelle —
> Limits kommen aus `QuotaConfig` und werden zur Reserve-Zeit als
> Parameter übergeben. So bleibt die Tabelle eine reine Counter-
> Source-of-Truth; Limit-Änderungen brauchen kein DDL.

```sql
CREATE TABLE quota_counters (
  quota_key   TEXT NOT NULL PRIMARY KEY,    -- serialized QuotaKey
  used        BIGINT NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL
);
```

## 5. Work-Packages

> Jeder AP endet mit grünem
> `make docker-check MODULES=":adapters:driven:persistence-jdbc"` UND
> einem grünen Contract-Test-Lauf. Granular committen wie in Phase E.

| AP | Inhalt | Akzeptanz |
|---|---|---|
| **E2.1** | Modul-Setup `adapters/driven/persistence-jdbc` (build.gradle.kts, Hikari-/Flyway-Wiring), `JdbcTransactionRunner` als `internal`-Klasse + Unit-Tests (Begin/Commit/Rollback, Exception-Pfade) | Modul baut; Runner-Tests grün |
| **E2.2** | Flyway-Setup + V1__phase_e_initial.sql (alle 5 Tabellen aus § 4) | Flyway-Migrate gegen Testcontainers grün; Idempotenz der Migration-Anwendung in CI |
| **E2.3** | `JdbcIdempotencyStore` — regulärer Idempotency-Pfad ohne `reserveInitResume`: `reserve`, `markAwaitingApproval`, `claimApproved`, `commit`, `deny`, `markFailed`, `cleanupExpired` mit konkreten SQL-Patterns aus § 6 | `IdempotencyStoreContractTests` grün gegen Postgres (inkl. Recovery-Tests für expired PENDING/AWAITING_APPROVAL-Leases); `markAwaitingApproval`-Challenge-Roundtrip-Test grün; Approval-Lease wird beim Übergang nach AWAITING_APPROVAL auf `now + awaitingApprovalSeconds` verlängert |
| **E2.4** | `JdbcIdempotencyStore.reserveInitResume`-Pfad gegen `init_resume_reservations` (separate Methode/Helper, keine Misch-Logik mit § 6.1) | Init-Resume-Contract-Suite (`ReadOnlyInitResumeContractTests` o.ä. — Name verifizieren in E2.4-Start) grün gegen Postgres |
| **E2.5** | `JdbcJobStore` (CRUD, Pagination, `transitionStatus` mit `transformer` + `NotFound`/`IllegalTransition`-Diskriminierung, `markCancelRequested`-CAS) | `JobStoreContractTests` grün; insbesondere die Contract-Tests, die `IllegalTransition.currentStatus` lesen, müssen passen |
| **E2.6** | `JdbcJobStartTransaction` — komponiert E2.3+E2.5 in einer DB-TX über `JdbcTransactionRunner` | `JobStartTransactionContractTests` grün (inkl. parallel-commit-Test); Atomicity-Vertrag aus `spec/phase-e-port-atomicity.md` § 3 ausführbar verifiziert |
| **E2.7** | `JdbcQuotaService` (Counter-UPSERT mit Limit-Check § 6.8) + `JdbcQuotaReservationOwnerStore` (markX-CAS § 6.9) + JDBC-Variante des `OwnerAwareQuotaService`-Wirings. Owner-aware `reserve`, `releaseForOwner`, `refundForOwner` und Sweeper-Refund laufen als gemeinsame DB-TX über Owner-Status + Counter. | (a) `QuotaStoreContractTests` aus `hexagon/ports-common/src/testFixtures/.../contract/QuotaStoreContractTests.kt` grün. (b) `QuotaReservationOwnerStoreContractTests` grün gegen Postgres. (c) `OwnerAwareQuotaService`-Atomicity-Tests (Reserve+Register, Double-Release, Double-Refund) laufen gegen das JDBC-Wiring grün. (d) Crash-Window-Test/Failure-Injection: Exception zwischen Owner-markX und Counter-Decrement rollbackt beides |
| **E2.8** | End-to-End-Test: Phase-E §7.x-Akzeptanz-Pins gegen Postgres-Wiring (Job-Start → Dispatch → Cancel → Quota-Refund-Cycle); Sweeper findet orphane Owner-Einträge | E2E-Suite grün; `QuotaReservationSweeper` exactly-once-refunded gegen Postgres |
| **E2.9** | Doku: `spec/phase-e2-persistence.md` — Implementor-Guide für andere Backings (MySQL/SQLite-Skizze als Folge), Flyway-Workflow, Operations-Hinweise (Backup, Connection-Limits); Cross-Refs in `spec/phase-e-port-atomicity.md` § Cross-Refs ergänzt | Doku reviewed; Spec-Cross-Ref-Eintrag „Persistente Implementoren siehe phase-e2-persistence.md" |

Schätzung: E2.1–E2.7 je ~1 Sub-Commit-Zyklus wie Phase-E-APs;
E2.8+E2.9 zusammen ein größerer Zyklus. Gesamt ~9–11 Commits.

## 6. SQL-Patterns pro Port

> Pro Operation: SQL + warum atomar. Verbindlich für die Implementierung.

### 6.1 IdempotencyStore.reserve — mit Recovery-CAS (`spec § 1`)

> **Review-Fix High-1**: Der Erst-Entwurf hat
> `ON CONFLICT DO UPDATE SET updated_at = idempotency_reservations.updated_at`
> vorgeschlagen. Das ist effektiv ein No-op und scheitert am Contract-
> Test „expired PENDING lease wird recovered". Korrektes Pattern ist
> ein **explizit transaktionaler Zwei-Schritt** mit `SELECT … FOR
> UPDATE`. Single-Statement-Lösungen über
> `INSERT … ON CONFLICT DO UPDATE … WHERE` führen zu zweideutigem
> RETURNING (`xmax = 0` unterscheidet nur Insert/UPDATE, nicht Recovery
> vs. NoOp).

```sql
-- in derselben TX (READ_COMMITTED, weil PK-Lock reicht):
BEGIN;

-- (1) Versuch: insert if absent
INSERT INTO idempotency_reservations (
  tenant_id, caller_id, tool_name, idempotency_key,
  state, payload_fingerprint,
  expires_at, retention_until, created_at, updated_at
) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)
ON CONFLICT (tenant_id, caller_id, tool_name, idempotency_key) DO NOTHING
RETURNING *;
-- Wenn Zeile zurueck → Reserved. fertig.

-- (2) Insert hat nichts geliefert → existing row sperren
SELECT * FROM idempotency_reservations
  WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
  FOR UPDATE;

-- (3) App-seitig dispatchen anhand state + expires_at + payload_fingerprint:
--     state = COMMITTED                  → Committed(resultRef)
--     state = DENIED                     → Denied(reason, expiresAt)
--     state = FAILED                     → Failed(reason)
--     fingerprint mismatch (any state)   → Conflict(existingFingerprint)
--     state = PENDING AND expires_at > now             → ExistingPending
--     state = AWAITING_APPROVAL AND expires_at > now   → AwaitingApproval(expiresAt, challenge)
--     state IN ('PENDING','AWAITING_APPROVAL') AND expires_at <= now → recover:

-- (4) Recovery-UPDATE (genau für Pfad mit expired Lease, gleicher Fingerprint):
UPDATE idempotency_reservations SET
  state = 'PENDING', claimed = FALSE, payload_fingerprint = ?, result_ref = NULL,
  challenge = NULL, reason = NULL,
  expires_at = ?, retention_until = ?, updated_at = ?
WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
  AND state IN ('PENDING','AWAITING_APPROVAL')
  AND expires_at <= ?;
-- affectedRows = 1 → Reserved (recovered). 0 → Race verloren, nochmal SELECT FOR UPDATE.

COMMIT;
```

Atomar weil: PK-Insert blockiert konkurrente Inserts; `FOR UPDATE`
sperrt die Zeile bis Commit; Recovery-UPDATE ist CAS via `WHERE state
IN (…) AND expires_at <= ?`. **Genau-eins-Sieger** unter parallelen
identischen Reserves — der `IdempotencyStoreContractTests`-Atomicity-
Test ist der Lackmus.

### 6.2 IdempotencyStore.reserveInitResume (Phase C, eigene Tabelle)

```sql
INSERT INTO init_resume_reservations
  (tenant_id, caller_id, tool_name, client_request_id, session_id,
   payload_fingerprint, expires_at, created_at, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (tenant_id, caller_id, tool_name, client_request_id) DO NOTHING
RETURNING session_id, expires_at;
-- Zeile zurueck → Reserved. Sonst: existing row lesen.

SELECT session_id, payload_fingerprint, expires_at FROM init_resume_reservations
WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND client_request_id = ?;
-- payload_fingerprint match → Existing(sessionId, expiresAt).
-- mismatch → Conflict(existingFingerprint).
```

Kein TX nötig, weil zwei Statements unabhängig sind und
`payload_fingerprint` für die Conflict-Diskriminierung reicht.
Expired-Recovery analog § 6.1 falls vom Contract gefordert (in E2.4
gegen die Contract-Tests verifizieren).

### 6.3 IdempotencyStore.markAwaitingApproval (`spec § 2`)

```sql
UPDATE idempotency_reservations
SET state = 'AWAITING_APPROVAL',
    claimed = FALSE,
    challenge = ?::jsonb,
    expires_at = ?,
    updated_at = ?
WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
  AND state = 'PENDING' AND expires_at > ?;  -- not expired
```

`affectedRows = 1` ⇒ true; `0` ⇒ false. Challenge ist JSONB, wird
beim folgenden `reserve` aus der Tabelle gelesen ⇒ durable.
`expires_at` MUSS auf die Approval-Lease (`now + awaitingApprovalSeconds`)
gesetzt werden; andernfalls würde `claimApproved` die ursprüngliche
kurze PENDING-Lease verwenden und legitime Approval-Retries zu früh als
abgelaufen behandeln.

### 6.4 IdempotencyStore.claimApproved

```sql
BEGIN;
SELECT state, claimed, expires_at, result_ref, reason
FROM idempotency_reservations
WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
FOR UPDATE;
-- keine Zeile                         → NotAwaitingApproval
-- state = COMMITTED                   → Committed(resultRef)
-- state = DENIED                      → Denied(reason, expiresAt)
-- state = PENDING AND claimed = TRUE  → AlreadyClaimed(expiresAt)
-- state != AWAITING_APPROVAL          → NotAwaitingApproval
-- state = AWAITING_APPROVAL AND expires_at <= now → NotAwaitingApproval

UPDATE idempotency_reservations
SET state = 'PENDING',
    claimed = TRUE,
    expires_at = ?,
    updated_at = ?
WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
  AND state = 'AWAITING_APPROVAL'
  AND expires_at > ?;
COMMIT;
-- affectedRows = 1 → Claimed(newLease)
```

Genau-eins-Sieger über Row-Lock + `claimed = TRUE`. Parallele Verlierer
sehen nach dem ersten Commit `state=PENDING AND claimed=TRUE` und
liefern `AlreadyClaimed`, wie `IdempotencyStoreContractTests`
(`1 Claimed / 15 AlreadyClaimed`) es pinnen.

### 6.5 IdempotencyStore.commit (`spec § 3`, KRITISCH)

```sql
UPDATE idempotency_reservations
SET state = 'COMMITTED', result_ref = ?,
    claimed = FALSE,
    expires_at = GREATEST(retention_until, ?::timestamptz),
    retention_until = GREATEST(retention_until, ?::timestamptz),
    updated_at = ?
WHERE … AND state IN ('PENDING','AWAITING_APPROVAL')
RETURNING state, expires_at;
```

Standalone-Aufruf (z.B. von synchronen Tools ohne Job) → eigene
Connection, READ_COMMITTED. Inside `JobStartTransaction.commit` →
über die geteilte `Connection` aus dem `JdbcTransactionRunner`
ausgeführt, die auch das `JobStore`-INSERT/UPDATE benutzt → eine TX,
gemeinsame Visibility, beide Statements committen oder rollbacken
zusammen. Das ist genau das in `spec § 3` geforderte Verhalten.

Terminal rows use one observable expiry: `expires_at == retention_until`.
`IdempotencyReserveOutcome.Committed` itself only exposes `resultRef`,
but `Denied`/`Failed` expose `expiresAt`; those outcomes MUST read the
terminal value from `expires_at`.

### 6.6 IdempotencyStore.deny / markFailed / cleanupExpired

- `deny`: `UPDATE … SET state='DENIED', claimed=FALSE, reason=?, expires_at=?, retention_until=? WHERE state IN ('PENDING','AWAITING_APPROVAL') RETURNING expires_at`.
- `markFailed`: analog auf `state='FAILED'` und `claimed=FALSE`;
  `expires_at` und `retention_until` werden auf denselben terminalen
  Ablauf gesetzt. Bei `retentionUntil != null` gilt
  `max(defaultFailedRetention, retentionUntil)`.
- `cleanupExpired`: regulärer Pfad löscht NUR terminale Einträge
  (`COMMITTED`/`DENIED`/`FAILED`) mit `retention_until < ?`; abgelaufene
  `PENDING`/`AWAITING_APPROVAL` bleiben erhalten, damit `reserve` sie
  recovern bzw. korrekt als Conflict/AwaitingApproval beantworten kann.
  InitResume löscht separat `init_resume_reservations WHERE expires_at < ?`.
  Count via `affectedRows`.

### 6.7 JobStore.transitionStatus mit Transformer (`spec § 6`)

> **Review-Fix High-3**: Der Port nimmt `allowedFromStatuses:
> Set<JobStatus>` und einen `transformer: (ManagedJob) -> ManagedJob`
> und unterscheidet `Applied` / `IllegalTransition(currentStatus)` /
> `NotFound`. Ein einfaches `UPDATE … WHERE status = ? RETURNING …`
> kann das nicht abbilden. Korrektes Pattern:

```sql
BEGIN;
SELECT managed_job, status FROM jobs
WHERE tenant_id = ? AND job_id = ? FOR UPDATE;
-- (1) keine Zeile          → ROLLBACK; return NotFound
-- (2) status NOT IN allowed → ROLLBACK; return IllegalTransition(currentStatus)
-- (3) sonst: app-seitig transformer(currentManagedJob) anwenden, neuer Status aus dem
--     transformierten ManagedJob.status:
UPDATE jobs SET managed_job = ?::jsonb, status = ?, updated_at = ?
  WHERE tenant_id = ? AND job_id = ?;
COMMIT;
-- return Applied(transformedRecord)
```

`FOR UPDATE` blockiert konkurrente Transition-Versuche bis Commit ⇒
genau-eins-Sieger. Die Diskriminierung NotFound vs IllegalTransition
vs Applied liegt in App-Code, der den geSELECTeten Status liest.

`markCancelRequested` analog: `SELECT … FOR UPDATE`,
`cancel_requested = true; cancel_source = ?` setzen, dabei
Idempotenz wahren (wenn `cancel_requested` schon `true`, ersten
Reason/Source NICHT überschreiben — siehe JobStore.kt KDoc Phase E
§ 7.2).

### 6.8 OwnerAwareQuotaService.reserve (`spec § 4`)

> **Review-Fix High-4**: Der Erst-Entwurf hat ohne UPSERT-Pattern
> nie reservieren können — `UPDATE quota_counters … RETURNING`
> ergibt für unbekannten Key 0 Rows und wurde fälschlich als
> `RateLimited` behandelt. Korrektes Pattern unten.

Atomar über zwei Tabellen ⇒ `JdbcTransactionRunner.inTransaction { … }`:

```sql
-- (a) Counter-Reserve via INSERT…ON CONFLICT DO UPDATE WHERE.
--     Limit ist QuotaConfig-Eigentum, kommt als Parameter rein.
INSERT INTO quota_counters (quota_key, used, updated_at)
SELECT ?, ?, ?
WHERE ? <= ? -- amount <= limit
ON CONFLICT (quota_key) DO UPDATE
  SET used = quota_counters.used + EXCLUDED.used,
      updated_at = EXCLUDED.updated_at
  WHERE quota_counters.used + EXCLUDED.used <= ?  -- limit value
RETURNING used;
-- 0 rows → RateLimited (TX ROLLBACK; kein Owner-INSERT)
-- Insert-Branch mit amount > limit wird durch SELECT ... WHERE ? <= ? blockiert.
-- Für RateLimited.current: SELECT COALESCE(used, 0) FROM quota_counters WHERE quota_key = ?
-- bzw. current=0 bei fehlender Zeile.

-- (b) Owner registrieren
INSERT INTO quota_reservation_owners
  (owner_id, reservation, state, lease_expires_at, created_at, updated_at)
VALUES (?, ?::jsonb, 'PENDING', ?, ?, ?);
```

`release`/`refund` für den nackten `QuotaStore` müssen auf 0 flooren
(`QuotaStoreContractTests`: `reserve(1); release(5) == 0`):

```sql
UPDATE quota_counters
SET used = GREATEST(used - ?, 0), updated_at = ?
WHERE quota_key = ?
RETURNING used;
-- keine Zeile → 0
```

Double-Release/Double-Refund-Schutz liegt beim owner-aware Pfad im
`QuotaReservationOwnerStore.markX`-CAS (§ 6.9), nicht im nackten
`QuotaStore.release`.

### 6.9 QuotaReservationOwnerStore.markX (`spec § 5`)

```sql
UPDATE quota_reservation_owners
SET state = ?, updated_at = ?
WHERE owner_id = ? AND state = ?
RETURNING reservation, state;
```

Genau-eins-Sieger über `affectedRows`. CAS-Verlierer in
`releaseForOwner`/`refundForOwner` überspringen den Counter-Decrement
⇒ Double-Release-Schutz wie InMemory.

Für das JDBC-`OwnerAwareQuotaService`-Wiring reicht diese Einzeloperation
nicht: `markReleased`/`markRefunded` und der folgende Counter-Decrement
MÜSSEN in derselben DB-Transaktion laufen. Sonst entsteht bei Crash nach
Owner-Statuswechsel, aber vor `QuotaStore.release/refund`, ein
dauerhaft belegter Slot mit terminalem Owner.

```kotlin
tx.inTransaction { conn ->
    val transitioned = ownerStore.markReleasedWithConnection(conn, ownerId, now) ?: return
    quotaService.releaseWithConnection(conn, transitioned.reservation)
}
```

`refundForOwner` und `QuotaReservationSweeper` nutzen denselben
connection-scoped Pfad. Failure-Injection-Tests in E2.7 muessen
erzwingen, dass eine Exception zwischen `markX` und Counter-Decrement
beide Änderungen rollbackt.

## 7. Risiken

| Risiko | Wahrscheinlichkeit | Mitigation |
|---|---|---|
| `FOR UPDATE`-Pattern im reserve-Recovery (§ 6.1) hat höhere Latenz als Single-Statement | gering — N=1 Lock | Akzeptabel; Hot-Path ist `INSERT … ON CONFLICT DO NOTHING RETURNING`, der schon im 1. Statement das Ergebnis liefert. Der `FOR UPDATE`-Pfad greift nur bei existierender Zeile. |
| Postgres-Isolation nicht ausreichend (anomale Reads) | gering — § 6 nutzt entweder PK-Locks oder `FOR UPDATE` | Contract-Tests sind Lackmus; bei Flake auf SERIALIZABLE für betroffene OP eskalieren |
| Testcontainers in CI langsam/flakey | mittel | Class-Scoped-Singleton + Schema-pro-Test; Bekannter `kover-CI-Flake` als Backup `gh run rerun --failed` |
| `JSONB`-Serialisierung (ApprovalChallenge, ManagedJob) braucht Schema-Versionierung | mittel | Plain-Jackson + `schema_version`-Feld im JSON; Migrations-Bumps via Flyway |
| Connection-Pool-Erschöpfung unter Job-Storm | gering | Hikari-`maximumPoolSize` konfigurierbar; Sweeper benutzt Read-Only-Pfad ohne TX |
| `quota_counters` ohne `limit_value` ⇒ Limits aus QuotaConfig müssen runtime-stabil sein | mittel | Existing `QuotaConfig` ist immutable per construction; bei Hot-Reload Owner muss bewusst entscheiden, was mit Counter > new-Limit passiert. In E2.7 dokumentieren. |
| Owner-aware Release/Refund kann bei Crash zwischen Owner-markX und Counter-Decrement Slots leaken | mittel | JDBC-`OwnerAwareQuotaService` nutzt connection-scoped `markXWithConnection` + `releaseWithConnection` in einer DB-TX; Failure-Injection-Test in E2.7 |
| `markCancelRequested`-Idempotenz (Reason nicht überschreiben) muss als CAS umgesetzt sein | mittel | Pattern: `UPDATE … SET cancel_requested = TRUE, cancel_source = COALESCE(cancel_source, ?) WHERE …` — explicit unit-test in E2.5 |
| `JobStore.list`-Pagination auf SQL umsetzen | mittel | Contract-kompatible Offset-Tokens (`"2"`, `"4"`, …) beibehalten; optional spaeteres Keyset-Format nur nach bewusster Contract-Aenderung |
| Flyway-Baseline bei Bestands-Datenbanken | n/a | Phase E2 = Greenfield; Migrations-Plan startet bei V1 |

## 8. Akzeptanz

Phase E2 gilt als done, wenn:

1. ✅ alle Contract-Test-Suiten aus
   `hexagon/ports-common/src/testFixtures` UND
   `hexagon/application/src/test/kotlin/.../quota` laufen gegen den
   JDBC-Adapter grün durch (`IdempotencyStoreContractTests` inkl.
   expired-Lease-Recovery; `ReadOnlyInitResumeContractTests` für den
   Init-Resume-Pfad; `JobStoreContractTests`
   inkl. `IllegalTransition.currentStatus`-Diskriminierung;
   `JobStartTransactionContractTests` inkl. parallel-commit;
   `QuotaReservationOwnerStoreContractTests` inkl. parallel-markX;
   `OwnerAwareQuotaService`-Atomicity inkl. Rollback zwischen Owner-
   markX und Counter-Decrement);
2. ✅ die Atomicity-Tests aus `spec/phase-e-port-atomicity.md` § E
   sind ausführbar gegen das JDBC-Wiring;
3. ✅ Phase-E §7.x-Akzeptanz-Pins (E.9 (3/3), E.10) laufen end-to-end
   gegen das JDBC-Wiring;
4. ✅ Coverage-Schwelle 90% pro Modul (mit `kover-CI-Flake`-Toleranz);
5. ✅ `spec/phase-e2-persistence.md` reviewed,
   `spec/phase-e-port-atomicity.md § Cross-Refs` erweitert um
   „Persistente Implementoren siehe phase-e2-persistence.md";
6. ✅ Plan-Move
   `docs/planning/in-progress/ImpPlan-0.9.6-E2.md → done/`;
   `roadmap.md` aktualisiert.

## 9. Out-of-Scope

- Multi-Instance-Mode (Cluster-Sweeper-Koordination, Leader-Election)
- LISTEN/NOTIFY für Sweeper (heute: Polling, ausreichend bis ~10k
  Jobs/h)
- MySQL-/SQLite-Varianten des Server-State-Schemas (Folge-Phase,
  Skizze in E2.9)
- Online-Migration aus existierenden InMemory-Deployments
- Performance-Tuning über Indizes hinaus (Partitionierung,
  Connection-Multiplexing)

## 10. Offene Fragen (vor E2.1-Start zu klären)

- **Q1**: Postgres-Mindestversion 14 ok? (Plan setzt sie voraus
  wegen `INSERT … ON CONFLICT … RETURNING`-Verhalten und JSONB.)
- **Q2**: Server-State-DB-URL — separate Config-Sektion in
  `application.yaml` (z.B. `server.state.jdbcUrl`) oder Env-Var-only?
  Konsistent mit `connection-config-spec.md`?
- **Q3**: Flyway-Migrationen automatisch beim Server-Start anwenden
  oder separater `make migrate`-Step?
- **Q4**: `JdbcTransactionRunner` adapter-intern (§ 3.5 Vorschlag)
  bestätigen — oder wollen wir eine Hexagon-Port-Variante mit
  abstraktem `TxHandle` (ohne `Connection`-Typ) für mögliche zweite
  Backings (z.B. ein NATS- oder Redis-Adapter)?
- **Q5**: Brauchen wir für E2.8 echte E2E-Tests in einem neuen Test-
  Modul (`test/integration-server-state`?) oder reicht es, die
  existierenden Phase-E-Akzeptanz-Tests mit einem JDBC-Wiring-Profil
  parametrisierbar zu machen?
- **Q6**: E2 vor F starten (siehe § 0) — muss F warten bis E2
  fertig, oder darf F parallel auf InMemory laufen?

---

**Nach Approval § 3 + § 10**: Start mit **E2.1** (Modul-Setup +
`JdbcTransactionRunner` als isolierte adapter-interne Klasse) als
kleinster, isolierter Schritt.
