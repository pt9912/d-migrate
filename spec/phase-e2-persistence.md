# Phase E2 — Persistente Server-State-Adapter (JDBC/Postgres)

> **Status**: aktiv (2026-05-06)
> **Geltung**: Phase-E2 (`docs/planning/in-progress/ImpPlan-0.9.6-E2.md`)
> **Cross-Refs**:
> [`spec/phase-e-port-atomicity.md`](./phase-e-port-atomicity.md) — Atomicity-Verträge der Ports;
> [`spec/mcp-server.md`](./mcp-server.md) Phase-E-Sektion — Wire-Verträge;
> [`spec/hexagonal-port.md`](./hexagonal-port.md);
> `docs/planning/in-progress/ImpPlan-0.9.6-E2.md` §3–§9

## Warum dieses Dokument existiert

Phase E2 liefert die ersten persistenten Implementor-Adapter für die
fünf Phase-E-kritischen Server-State-Ports. Während
[`phase-e-port-atomicity.md`](./phase-e-port-atomicity.md) **WAS**
atomar passieren muss spezifiziert, beschreibt dieses Dokument **WIE**
der Postgres-Adapter es realisiert — und gibt Folge-Implementoren
(MySQL, SQLite, eventuell verteilte Backends) das Grundgerüst.

Inhalt:

1. Modul-Layout & Komponenten
2. Schema (V1) und Flyway-Workflow
3. Realisierung der Atomicity-Verträge in SQL
4. Skizze für andere Dialekte (MySQL, SQLite)
5. Operations: Backup, Connection-Limits, Sweeper, Tuning
6. Cross-Refs

---

## 1. Modul-Layout & Komponenten

Alles lebt im Adapter-Modul **`adapters/driven/persistence-jdbc`**
(Plan §3.4):

| Komponente | Datei | Implementiert |
|---|---|---|
| `JdbcTransactionRunner` | `internal/JdbcTransactionRunner.kt` | adapter-internes TX-Primitive |
| `JdbcSqlSupport` | `internal/JdbcSqlSupport.kt` | bind/getInstant/querySingle/executeUpdate-Helfer |
| `JdbcIdempotencyStore` | `idempotency/JdbcIdempotencyStore.kt` | `IdempotencyStore` (regulär + InitResume) |
| `ApprovalChallengeJson` | `idempotency/ApprovalChallengeJson.kt` | Wire-Codec für `idempotency_reservations.challenge` |
| `JdbcJobStore` | `job/JdbcJobStore.kt` | `JobStore` |
| `JdbcJobStartTransaction` | `job/JdbcJobStartTransaction.kt` | `JobStartTransaction` (cross-store TX) |
| `JobRecordJson` | `job/JobRecordJson.kt` | Wire-Codec für `jobs.managed_job` |
| `JdbcQuotaStore` | `quota/JdbcQuotaStore.kt` | `QuotaStore` (Counter mit Limit-CAS) |
| `JdbcQuotaReservationOwnerStore` | `quota/JdbcQuotaReservationOwnerStore.kt` | `QuotaReservationOwnerStore` |
| `JdbcOwnerAwareQuotaService` | `quota/JdbcOwnerAwareQuotaService.kt` | TX-aware Komposite |
| `QuotaJson` | `quota/QuotaJson.kt` | Wire-Codec für `QuotaKey` + `QuotaReservation` |
| `PhaseEMigrationRunner` | `migration/PhaseEMigrationRunner.kt` | Flyway-Wrapper |

### 1.1 Sichtbarkeits-Konventionen (Plan-§-3.5-Carve-out)

`JdbcTransactionRunner` ist `public class` (nicht `internal`, wie der
Plan-Erst-Entwurf vorsah). Begründung: AP E2.6
(`JdbcJobStartTransaction`) komponiert den Runner mit
`JdbcIdempotencyStore` und `JdbcJobStore` über Modulgrenzen — das
Bootstrap-Wiring lebt im MCP-Adapter. Modul-`internal` würde diese
Komposition blockieren.

„Nicht als Hexagon-Port exponiert" bleibt erfüllt: der Runner taucht
in **keinem** `hexagon:*`-Interface auf, führt also keinen
`java.sql.Connection` in die Hexagon-Schicht.

Cross-Store-Helfer (`commitOnConnection`, `saveOnConnection`,
`reserveOnConnection`, `markReleasedOnConnection`, …) sind dagegen
strikt `internal` — nur Adapter-interne Komposition (E2.6/E2.7) nutzt
sie.

---

## 2. Schema und Flyway-Workflow

### 2.1 Initial-Migration `V1__phase_e_initial.sql`

Fünf Tabellen, alle in einem Migration-Skript:

| Tabelle | Zweck | PK |
|---|---|---|
| `idempotency_reservations` | regulärer Idempotency-Pfad (Job-Start-Tools) | `(tenant_id, caller_id, tool_name, idempotency_key)` |
| `init_resume_reservations` | Phase-C Upload-Init-Resume | `(tenant_id, caller_id, tool_name, client_request_id)` |
| `jobs` | Job-Lifecycle (`QUEUED → RUNNING → terminal`) | `(tenant_id, job_id)` |
| `quota_reservation_owners` | Owner-Tracking pro Quota-Reservation | `owner_id` |
| `quota_counters` | Raw-Counter pro `QuotaKey` | `quota_key` (TEXT, JSON-serialisiert) |

Vollständiges DDL in `adapters/driven/persistence-jdbc/src/main/resources/db/migration/V1__phase_e_initial.sql`.
Postgres-spezifisch: `JSONB`, `TIMESTAMPTZ`, partielle Indizes
(`WHERE state = 'PENDING'`), `INSERT … ON CONFLICT … RETURNING`.

### 2.2 Plan-§-4.2-Carve-out: `jobs.managed_job` enthält den ganzen `JobRecord`

Plan-§-4.2-Wortlaut „managed_job: ManagedJob serialized" ist eine
Vereinfachung. Tatsächlich serialisiert der Adapter den vollständigen
`JobRecord` (inklusive `tenantId`, `ownerPrincipalId`, `visibility`,
`resourceUri`, `adminScope`, `quotaReservationOwnerId`) in das JSONB-
Feld — das Schema hat keine separaten Spalten für diese Felder. Spalten-
Name ist historisch.

Filter-relevante Felder (`status`, `cancel_requested`, `created_at`,
`updated_at`, `expires_at`) leben als extrahierte Spalten für
Index/SQL-Query-Performance. JSONB ist source-of-truth.

### 2.3 Flyway-Workflow

**Production (Plan-§-3.2 + §-10 Q3):** expliziter Ops-Step.

```bash
# nach Code-Deploy, vor MCP-Server-Start
make migrate
```

(Make-Target-Wiring kommt mit dem Bootstrap in einem Folge-AP; Heute
ruft der Operator `PhaseEMigrationRunner(dataSource).migrate()` aus
einem Script.)

Der Server selbst ruft Flyway **nicht** automatisch beim Start. Bei
Drift schlägt der Bootstrap fehl (`PhaseEMigrationRunner.validate()`
beim Start, oder gleichwertig).

**Dev/Test:** opt-in über `server.state.migrations.auto = true`. Der
Bootstrap ruft dann `migrate()` selbst.

**Schema-History-Tabelle:** dediziert `flyway_phase_e_history`
(Plan-§-3.2). So bleibt das Phase-E-Schema von eventuellen Co-Mietern
in derselben DB getrennt.

### 2.4 Schema-Versionierung in JSONB

Die Wire-DTOs (`JobRecordJson`, `ApprovalChallengeJson`, `QuotaJson`)
serialisieren über stabile Wire-Type-Records — Domain-Refactorings
brechen das Format **nicht**, sofern der Wire-Type unverändert bleibt.
Schema-Bumps benötigen einen neuen Migration-Step (V2/V3) und einen
zweiten Read-Pfad für alte Records.

---

## 3. Realisierung der Atomicity-Verträge

Querverweis auf [`phase-e-port-atomicity.md`](./phase-e-port-atomicity.md):
für jeden Vertrag gibt diese Sektion das konkrete SQL-Pattern.

### 3.1 IdempotencyStore.reserve — Recovery-CAS

Plan §6.1. Hot-Path: `INSERT … ON CONFLICT DO NOTHING RETURNING`.
Recovery-Pfad: `SELECT … FOR UPDATE` + dispatch nach state +
fingerprint, dann `UPDATE … WHERE state IN (...) AND expires_at <= ?`
als CAS gegen abgelaufene Leases. Genau-eins-Sieger garantiert durch
PK-Lock + FOR-UPDATE-Row-Lock.

### 3.2 IdempotencyStore.commit + JobStore.save — gemeinsame TX

Plan §3 + §6.5 + §6.7. `JdbcJobStartTransaction` wickelt beide
Operationen in `JdbcTransactionRunner.inTransaction { conn -> … }` —
shared `Connection`, ein Postgres-Commit. Bei Fehler in einer der beiden
rollbackt Postgres beides; es entsteht **kein** sichtbarer Job ohne
matchenden COMMITTED-Idempotency-Eintrag.

### 3.3 JobStore.transitionStatus — Transformer + CAS

Plan §6.7. `SELECT managed_job::text FOR UPDATE` → Status-Check →
`transformer(currentManagedJob)` → `UPDATE … WHERE tenant_id = ? AND
job_id = ?`. NotFound/IllegalTransition/Applied unterscheidet der
Adapter aus dem SELECT-Ergebnis vor dem UPDATE.

### 3.4 JobStore.markCancelRequested — First-Reason-Wins

Plan §6.7 + §7.2. SELECT FOR UPDATE liest den vollen `cancelRequest`;
wenn `requested = true`, wird ohne UPDATE direkt `Applied(record)`
zurückgegeben (Idempotenz: erste Reason/Source bleiben).

### 3.5 OwnerAwareQuotaService.reserve / release — Cross-Tabellen-TX

Plan §6.8 + §6.9. `JdbcOwnerAwareQuotaService` überschreibt die 4
Methoden der `open class OwnerAwareQuotaService` und wrappt jeden
Aufruf in `inTransaction { conn -> … }`. Innerhalb der TX:

- **reserve**: `JdbcQuotaStore.reserveOnConnection` (UPSERT mit
  `WHERE used+amount <= limit`) + `JdbcQuotaReservationOwnerStore.registerOnConnection`
  (PK-INSERT). Bei `RateLimited` (0 affected rows aus Counter-UPSERT)
  wird **kein** Owner registriert — kein Side-Effect.
- **release/refund**: `markReleasedOnConnection`/`markRefundedOnConnection`
  (CAS via `UPDATE … WHERE state = ? RETURNING …`) + bei CAS-Gewinn
  `releaseOnConnection` (UPDATE mit `GREATEST(used - amount, 0)`).
  Crash zwischen markX und release rollbackt beides; es entsteht **kein**
  dauerhaft belegter Slot mit terminalem Owner.

### 3.6 QuotaCounter — Limit-CAS im SQL

Plan §6.8. Das Limit ist Argument, nicht Tabellen-Spalte — Limit-
Änderungen brauchen kein DDL. Der UPSERT prüft das Limit zweimal:

```sql
INSERT INTO quota_counters (quota_key, used, updated_at)
SELECT ?, ?, ?
WHERE ? <= ?              -- Insert-Branch: amount <= limit
ON CONFLICT (quota_key) DO UPDATE
  SET used = quota_counters.used + EXCLUDED.used,
      updated_at = EXCLUDED.updated_at
  WHERE quota_counters.used + EXCLUDED.used <= ?  -- Update-Branch: current+amount <= limit
RETURNING used;
```

0 affected rows ⇒ `RateLimited`; 1 ⇒ `Granted`. Postgres serialisiert
die `DO UPDATE`-Branches auf der PK-Zeile, also keine Doppel-Buchungen
unter Concurrency.

---

## 4. Skizze für andere Dialekte

### 4.1 MySQL (≥ 8.0)

| Postgres-Feature | MySQL-Äquivalent |
|---|---|
| `INSERT … ON CONFLICT (key) DO NOTHING RETURNING` | `INSERT IGNORE` + Follow-up `SELECT` (kein RETURNING vor MySQL 8.0.21) |
| `INSERT … ON CONFLICT DO UPDATE WHERE` | `INSERT … ON DUPLICATE KEY UPDATE` (kein WHERE-Predicate; Limit-Check muss Application-side) |
| `JSONB` | `JSON` (kein binärer Typ; Vergleichs-Performance schlechter) |
| `TIMESTAMPTZ` | `DATETIME(6)` mit UTC-Konvention im Adapter |
| `SELECT … FOR UPDATE` | identisch |
| Partial Index `WHERE state = 'PENDING'` | nicht nativ — Workaround: Functional Index oder generated column |

**Praktische Folge**: der Limit-Check beim Counter-UPSERT muss in
einer SELECT-FOR-UPDATE + UPDATE-Sequenz innerhalb einer TX laufen
(zwei Statements statt einem). Performance-Hit minimal, da der PK-
Lock kurzzeitig ist.

### 4.2 SQLite

SQLite ist in der Praxis nur für Single-Instance-Test-Szenarien
geeignet (Single-Writer-Lock, keine echte Multi-Process-Atomicity
über `SELECT … FOR UPDATE`):

| Postgres-Feature | SQLite-Äquivalent |
|---|---|
| `INSERT … ON CONFLICT DO NOTHING/UPDATE` | identisch (seit 3.24) |
| `JSONB` | nicht vorhanden — `TEXT` mit JSON1-Extension |
| `TIMESTAMPTZ` | `TEXT` (ISO-8601) oder `INTEGER` (unix epoch) |
| `SELECT … FOR UPDATE` | nicht vorhanden — Single-Writer-Lock reicht |
| Partial Index | identisch (seit 3.8) |

**Empfohlener Use-Case**: Embedded-Tests, Single-User-Dev. Production-
Multi-Tenant ist NICHT empfohlen — erste echte Konkurrenz wird durch
den Single-Writer-Lock serialisiert, was den Quota-Counter zur
Bottleneck macht.

### 4.3 Implementor-Checkliste

Für ein neues Backing:

1. Implementiere die vier Cross-Store-Helper-APIs:
   `commitOnConnection`, `saveOnConnection`, `reserveOnConnection`,
   `markReleasedOnConnection` (+ `markRefundedOnConnection`,
   `markCommittedOnConnection`, `registerOnConnection`).
2. Verkable einen dialekt-spezifischen `TransactionRunner` (z.B.
   `MySqlTransactionRunner`); das Adapter-Modul gehört diesem Backing.
3. Lass die fünf Contract-Test-Suiten (s.u.) gegen Testcontainers
   laufen.
4. Schreibe ein `V1__phase_e_initial.sql` für deinen Dialekt; die
   semantischen Vorgaben aus Plan §4 sind Teil des Vertrags.

**Pflicht-Contract-Tests**:

- `IdempotencyStoreContractTests` (`hexagon/ports-common` testFixtures)
- `ReadOnlyInitResumeContractTests`
- `JobStoreContractTests`
- `JobStartTransactionContractTests` (Cross-Store-Atomicity)
- `QuotaStoreContractTests`
- `QuotaReservationOwnerStoreContractTests` (`hexagon/application` testFixtures)

Plus dialekt-spezifische Failure-Injection-Tests für den Crash-Window
zwischen Owner-markX und Counter-Decrement (Plan §6.9 — Postgres-
Variante in `JdbcOwnerAwareQuotaServiceTest`).

---

## 5. Operations

### 5.1 Connection-Pool

`JdbcTransactionRunner` borgt Connections aus einer
`javax.sql.DataSource`. Production-Wiring nutzt HikariCP (siehe
Plan §3.6). Dimensionierungs-Daumenregeln:

- `maximumPoolSize` ≥ erwartete tools/call-Concurrency × 2 (Dispatch-
  Pool + Sweeper + ad-hoc).
- `connectionTimeoutMs` konservativ (z.B. 30 s) — bei Pool-Saturation
  schlägt der Aufruf eher fehl als zu hängen.
- `idleTimeoutMs`/`maxLifetimeMs` < Postgres `idle_in_transaction_session_timeout`,
  sonst wird der Server die Connection killen.

**Multi-Instance-Mode (Cluster)**: Out-of-scope für Phase E2 (Plan §9).
Mehrere MCP-Server-Instanzen, die denselben Pool teilen, sind nicht
verifiziert; insbesondere der `QuotaReservationSweeper` läuft in
jeder Instanz und konkurriert auf den Owner-CAS — das ist sicher,
aber redundant.

### 5.2 `QuotaReservationSweeper`-Häufigkeit

Sweeper-Polling-Intervall sollte ≥ Lease-Default (typisch 60 s) sein.
Häufigere Polls verbrauchen DB-CPU für eine fast-immer-leere Query
(`WHERE state = 'PENDING' AND lease_expires_at <= ?`). Empfehlung:
1× pro Minute.

Der Sweeper ist exactly-once durch `markRefundedOnConnection`-CAS;
zwei Instanzen, die gleichzeitig sweepen, refunden jede Lease genau
einmal (Plan §7.9 line 1310).

### 5.3 Backup

Die Phase-E-Tabellen sind klein (Owner-Counts skalieren mit aktiven
Jobs, Idempotency-Eintrag-Zahl mit Retry-Volumen). Standard-PG-Backup
(`pg_dump --schema=public` oder kontinuierliche WAL-Archivierung)
reicht.

**Wichtig**: Idempotency-COMMITTED-Einträge dienen als Wire-Replay-
Cache. Ein Restore muss die Retention-Tabelle (`retention_until`) mit
einbeziehen — sonst kann ein Caller, der einen frischen Idempotency-
Key wiederholt, einen alten Job-Ref erhalten.

### 5.4 PostgreSQL-Versionen

Plan §3.1 + §10 Q1: technische Mindestversion **PostgreSQL 14**;
empfohlen für neue Production-Deployments **PostgreSQL 16+**.
PG 14 erreicht laut [PostgreSQL Versioning Policy](https://www.postgresql.org/support/versioning/)
am 2026-11-12 EOL. Die Adapter-SQL nutzt nur Features, die in
14+ vorhanden sind:

- `INSERT … ON CONFLICT … RETURNING` mit `WHERE`-Predicate auf `DO
  UPDATE` (PG 9.5+, `WHERE`-Predicate seit PG 9.5)
- `JSONB` (PG 9.4+)
- `TIMESTAMPTZ` (immer)
- partielle Indizes (immer)
- `GREATEST` mit `bigint` (immer)

Höhere Versionen bringen Performance-Verbesserungen für JSONB-
Indizierung und parallele Index-Builds — nichts adapter-relevantes.

### 5.5 Connection-Limits

Pro Phase-E-Operation hält der Adapter genau **eine** Connection für
die TX-Dauer. Innerhalb der TX werden nur 1–3 Statements ausgeführt
(SELECT FOR UPDATE + UPDATE/DELETE/INSERT). Lock-Dauer < 10 ms
typisch.

Postgres `max_connections` × `connection_factor` = Hikari-Pool-Größe
× MCP-Instanzen. Für Single-Instance + 16 Worker reichen 32–64
Connections.

### 5.6 Cleanup-Job

`IdempotencyStore.cleanupExpired(now)` löscht terminale Einträge
(COMMITTED/DENIED/FAILED) deren `retention_until` abgelaufen ist,
**plus** abgelaufene `init_resume_reservations`-Einträge. Empfehlung:
1× pro Stunde. Der Sweeper kümmert sich nur um Quota-Owner, nicht um
Idempotency-Cleanup.

---

## 6. Cross-Refs

- [Plan §3 Architektur-Entscheidungen (E2)](../docs/planning/in-progress/ImpPlan-0.9.6-E2.md#3-architektur-entscheidungen)
- [Plan §4 Schema-Skizze](../docs/planning/in-progress/ImpPlan-0.9.6-E2.md#4-schema-skizze-v1__phase_e_initialsql)
- [Plan §6 SQL-Patterns pro Port](../docs/planning/in-progress/ImpPlan-0.9.6-E2.md#6-sql-patterns-pro-port)
- [`spec/phase-e-port-atomicity.md`](./phase-e-port-atomicity.md) — Port-Verträge (was muss atomar sein)
- [`spec/mcp-server.md`](./mcp-server.md) Phase-E-Sektion — Wire-Verträge
- [`spec/connection-config-spec.md`](./connection-config-spec.md) — Connection-Pool-Konfiguration
