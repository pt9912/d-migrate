# Ports-JDBC-Entkopplung: `java.sql` aus `ports-common`/`ports-execute`/`ports-write` entfernen

> **Status:** Done — graduiert (2026-06-27). **Entscheidungsgrundlage:**
> [ADR 0022](../../adr/0022-ports-jdbc-entkopplung.md) (accepted, Option A).
> P1–P5 geliefert + Doku-Folgearbeit (Abschnitt 6) erledigt; siehe Closure am Ende.
>
> **Phasen-Fortschritt: P1–P5 KOMPLETT.** `hexagon:ports*` ist **java.sql-frei** (Gate P5
> grün). P1 neutrales `DatabaseConnection`; **P2 (`d5b20a40`)** `ConnectionPool.borrow()`
> + ~24 Konsumenten + ~50 Test-Fakes; **P3 (`6c9af92d`)** `AtomicSequencePreserveExecutor`
> (ports-execute) + 3 dialekt-Impls + Caller + Tests; **P4 (`d30672a8`)** `SchemaSync`/
> `TriggerManagement` (ports-write) + Impls + Caller; **P5 (`b91ec1b3`)** Fitness-Function
> `scripts/ports-jdbc-free-gate.sh` (in `gates`). Adapter unwrappen via `asJdbc()`;
> `MigrationExecutorTestSupport` (testFixtures ohne driver-common-Pfad) **reflektiv** (Präzedenz
> `JdbcForeignValueNormalizer`, kein build.gradle-Dep — offline-Build kann keine
> Dependency-Neuauflösung). Alle `docker-check`-Läufe grün (Compile/Test/Detekt/Kover ≥ 90 %).
> **Doku-Folgearbeit (Abschnitt 6) erledigt** — Zielbild-Regel in `architecture.md`; beide
> gelieferten Pläne (`hexagonal-port.md`, `phase-e2-persistence.md`) nach `done-archive/`; Live-Refs
> nachgezogen (Handbuch-Flyway-Inhalt **inline** statt Link).
> *Methodik-Nachtrag:* P2 lief via Regex/perl + Build-Netz und deckte iterativ String-/Kommentar-/
> mockk-DSL-Fehlalarme auf. Daraus entstand die hermetische **ast-grep-Stage** (`make ast-grep`,
> `feedback_syntax_aware_refactor`); P3/P4 entsprechend syntax-diszipliniert.
>
> **Scope-Korrektur (2026-06-27, P1-Review):** `ports-write` (`SchemaSync`,
> `TriggerManagement`) leakt `java.sql.Connection` ebenfalls und ist von ADR 0022 (Punkt 1)
> mitgemeint — als eigene Phase P4 nachgezogen; die Fitness-Function (jetzt P5) auf `hexagon/ports*`
> deckte die Lücke auf (hätte sonst dort failed).

## 1. Ziel

Die Hexagon-Ports-Schicht von JDBC befreien: `hexagon:ports-common`, `hexagon:ports-execute` **und
`hexagon:ports-write`** exponieren heute `java.sql.Connection`; nach diesem Slice tun sie es nicht
mehr. JDBC lebt nur noch in den Adaptern. Umgesetzt über das neutrale `DatabaseConnection`
(ADR 0022), das das ursprünglich unter D4 (Strukturplan, inzwischen archiviert) vorgesehene Soll
endlich realisiert.

## 2. Hintergrund (Ist-Stand im Code)

- **Leak 1 — Pool (nur Rückgabetyp):** `ConnectionPool.borrow(): java.sql.Connection` in
  [`hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionPool.kt`](../../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionPool.kt).
  Kein Port-Code nutzt Connection-Methoden — die **56** `borrow()`-Konsumenten liegen in den
  Adaptern.
- **Leak 2 — Executor (echte Nutzung):**
  [`hexagon/ports-execute/src/main/kotlin/dev/dmigrate/driver/migration/preserve/AtomicSequencePreserveExecutor.kt`](../../../hexagon/ports-execute/src/main/kotlin/dev/dmigrate/driver/migration/preserve/AtomicSequencePreserveExecutor.kt)
  nimmt eine `Connection`, fährt BEGIN/COMMIT/ROLLBACK (über `autoCommit`), führt gerenderte
  Statements aus und resettet Session-Settings im `finally`. `requireOwnedConnection(connection)`
  prüft `autoCommit`. Der Contract-Test mockt heute `mockk<java.sql.Connection>`.
- **Leak 3 — Write-Ports (Signatur-Parameter):** in `hexagon:ports-write` nehmen
  [`SchemaSync.kt`](../../../hexagon/ports-write/src/main/kotlin/dev/dmigrate/driver/data/SchemaSync.kt)
  (`conn: Connection`) und
  [`TriggerManagement.kt`](../../../hexagon/ports-write/src/main/kotlin/dev/dmigrate/driver/data/TriggerManagement.kt)
  (`disableTriggers`/`assertNoUserTriggers`/`enableTriggers(conn: Connection, …)`) eine rohe
  `java.sql.Connection`. Vom P1-Review aufgedeckt; ADR 0022 (Punkt 1) listet `-write` mit.
  (`ports-read`/`ports` sind bereits JDBC-frei — geprüft.)
- **`ConnectionPool` ist Vertragswährung** der Datenports — `SchemaReader`/`DataReader`/
  `DataWriter`/`TableLister` nehmen alle `pool: ConnectionPool`. Darum wird der Pool **nicht**
  verlagert, sondern nur sein Rückgabetyp neutralisiert (ADR 0022, Option B verworfen).

## 3. Scope

### 3.1 In Scope

- Neues `DatabaseConnection`-Interface in `hexagon:ports-common` (minimale Fähigkeiten).
- `JdbcDatabaseConnection`-Adapter (Hikari-Wrapper) in `adapters/driven/driver-common`.
- Signatur-Umstellung `ConnectionPool.borrow()` + `AtomicSequencePreserveExecutor` (+ Callback +
  `requireOwnedConnection`) + `SchemaSync`/`TriggerManagement` (`ports-write`).
- Adapter-Konsumenten an das Unwrap im Adapter umstellen; Contract-Tests umstellen.
- Architektur-Fitness-Function, die `java.sql` in `hexagon/ports*` künftig verbietet.

### 3.2 Nicht in Scope

- Re-Design der Datenports (`ConnectionPool` bleibt Währung).
- Eine zweite (Nicht-JDBC-)`DatabaseConnection`-Implementierung.
- Die Doku-Folgearbeit (Abschnitt 6) — eigene, der ADR nachgelagerte Schritte.

## 4. Phasen (Reihenfolge: Fundament → Umstellung → Verriegelung)

- **P1 — Neutraler Typ + JDBC-Impl.** `DatabaseConnection` in `ports-common` definieren
  (`AutoCloseable` + Transaktions-Lebenszyklus + Ausführung gerenderter Statements + Session-Reset
  + Owned-Transaction-Prüfung). `JdbcDatabaseConnection` in `driver-common` über die Hikari-
  `java.sql.Connection`. **DoD:** Interface + Impl gebaut; Test belegt, dass `close()` die
  Connection in den Pool zurückgibt (nicht physisch schließt); Build grün.
- **P2 — Pool umstellen.** `ConnectionPool.borrow(): DatabaseConnection`. Die 56 Adapter-
  Konsumenten holen die reale Connection über die Adapter-Impl (Unwrap im Adapter, nicht im Port).
  **DoD:** `grep -r "java.sql" hexagon/ports-common/src` leer; Build + Adapter-Tests grün.
- **P3 — Executor umstellen.** `AtomicSequencePreserveExecutor.execute` + `executeProtectedOperations`
  + `requireOwnedConnection` auf `DatabaseConnection`; `AtomicSequencePreserveContractTest`-Mocks
  (`mockk<java.sql.Connection>` → `mockk<DatabaseConnection>`). **DoD:**
  `grep -r "java.sql" hexagon/ports-execute/src` leer; Contract-Test grün; Verhalten unverändert
  (BEGIN/COMMIT/ROLLBACK-Pfade, Lock-Timeout-`finally`, Owned-Connection-Check).
- **P4 — Write-Ports umstellen.** `SchemaSync` und `TriggerManagement` (`hexagon:ports-write`) von
  `java.sql.Connection` auf `DatabaseConnection` umstellen; die Implementierungen (driver-`*`)
  unwrappen via `asJdbc()`, die Aufrufer reichen das neutrale Handle. **DoD:**
  `grep -r "java.sql" hexagon/ports-write/src` leer; Build + betroffene Adapter-/Tests grün;
  Verhalten unverändert.
- **P5 — Verriegeln (Fitness-Function).** Eine Detekt-`ForbiddenImport`-Regel (oder ein
  `consumer-read-probe`-artiger Test), die `java.sql.*` in `hexagon/ports*` als Verstoß meldet —
  damit der Leak nicht zurückkehrt. Greift erst grün, wenn P2–P4 alle Ports gesäubert haben.
  **DoD:** Regel aktiv, Gate grün; ein bewusst eingefügter Test-Verstoß failt.

## 5. Akzeptanzkriterien

- `grep -rl "java.sql" hexagon/ports-common hexagon/ports-execute hexagon/ports-write` ist **leer**
  (main **und** test); allgemeiner: `hexagon/ports*` ist JDBC-frei.
- Build, Detekt, Kover (≥ 90 % pro Modul) grün; die Architektur-Fitness-Function (P5) grün und
  scharf.
- Hikari-`close()`-zurück-in-den-Pool-Semantik nachweislich erhalten (P1-Test).
- `AtomicSequencePreserveContractTest` grün; **kein** Verhaltenswechsel im Atomic-Preserve-Pfad.
- `DatabaseConnection` trägt nur die benötigten Operationen (Review-Leitplanke gegen Leaky
  Abstraction, ADR 0022).

## 6. Doku-Folgearbeit (nach dem Code-Fix, nicht Teil der Phasen) — ERLEDIGT (2026-06-27)

War Auslöser dieses Slice; alle drei Schritte umgesetzt:

1. ✅ Zielbild-Regel „Ports exponieren kein `java.sql`; JDBC nur in Adaptern" in
   [`../../../spec/architecture.md`](../../../spec/architecture.md) verankert (eigener Unterblock
   bei den Modul-Regeln; als **Architektur-Fitness-Function** ausgewiesen, da `java.sql` JDK-intern
   ist; **ohne** Abwärtsverweis auf die ADR — SDP).
2. ✅ Der gelieferte Überführungsplan (D1–D9 aufgelöst) nach
   [`../done-archive/hexagonal-port.md`](../done-archive/hexagonal-port.md) verschoben (Archiv-Banner;
   die überholte `java.sql`-in-Ports-Notiz **nicht** ins Zielbild übernommen).
3. ✅ Der Implementor-Plan zur Server-State-Persistenz — Triage: phasen-benanntes
   Implementor-Dokument, **kein Zielbild**; gemäß der ADR-Abgrenzung (Nicht-Ziele) als gelieferter
   Plan archiviert nach
   [`../done-archive/phase-e2-persistence.md`](../done-archive/phase-e2-persistence.md) (Archiv-Banner).
   Die java.sql-Zeile dort war bereits konform („kein `java.sql` in `hexagon:*`").

**Eingehende Verweise nachgezogen** — präzisiert durch die d-check-Referenz-Matrix:
- `spec/`-Docs dürfen **nicht** nach planning zeigen (matrix-forbidden) → in `spec/port-atomicity.md`
  (Header-Cross-Ref entfernt), `spec/ki-mcp.md`, `spec/job-contract.md` die Archiv-Links **gedroppt**,
  Implementor-Verweis stattdessen auf das Code-Modul `adapters/driven/persistence-jdbc`.
- `docs/`-Docs dürfen nach `done-archive/` zeigen → `docs/operations/job-executor.md` (×2) umgebogen.
- `docs/user/administrationshandbuch.md` **self-contained** gemacht: Flyway-Workflow und
  Backup-Hinweise (vormals `§5.3`-Verweis) **inline** statt Link ([[Referenz-Stil]]).
- Verschobene Dateien: interne spec-Links auf Aufwärts-Pfade (`../../../spec/…`).

**Zusätzlich de-phast** (Folge-Erkenntnis im Review): der Port-Atomicity-Vertrag ist Zielbild
(kein Plan), trug aber Phasen-Naming → umbenannt nach
[`../../../spec/port-atomicity.md`](../../../spec/port-atomicity.md), Status/Geltung-Header und
„Phase E"-Body-Erwähnungen entfernt. Restschuld (Phase-E-Provenienz in `ki-mcp`/`job-contract`-Labels)
beim Hygiene-Tracker ([`spec-milestone-reference-hygiene.md`](spec-milestone-reference-hygiene.md)).

## 7. Vorbedingungen

- [ADR 0022](../../adr/0022-ports-jdbc-entkopplung.md) **accepted** (Option A, 2026-06-27) — die
  Abstraktionsform (`DatabaseConnection`) steht; P1 kann starten.

## 8. Bezug

- ADR: [0022](../../adr/0022-ports-jdbc-entkopplung.md).
- D4-Soll (Historie, archiviert): [`../done-archive/hexagonal-port.md`](../done-archive/hexagonal-port.md);
  Steady-State-Zielbild: [`../../../spec/architecture.md`](../../../spec/architecture.md).
- Backend-Neutralitäts-Intention: [`../../../spec/port-atomicity.md`](../../../spec/port-atomicity.md).

## Closure (2026-06-27)

**Code (P1–P5):** `hexagon:ports-common`/`-read`/`-write`/`-execute` sind java.sql-frei; neutrales
`DatabaseConnection` (ports-common), `JdbcDatabaseConnection` + `asJdbc()` (driver-common),
Fitness-Function-Gate `scripts/ports-jdbc-free-gate.sh` (in `gates`). E2E live-validiert: Integration
PG/SQLite voll grün, MySQL 140/140; Sample-DB-Smokes PG/SQLite/Cross MySQL→PG/Cross PG→MySQL grün.
Zwei dabei aufgedeckte **Harness**-Bugs (kein Refactor-Regress) mitgefixt: E07-Timeout-Bench
(3-Wege-Cross-Join, `753bfca9`) und stdin-Drain in 3 Parity-Skripten (`</dev/null`).

**Doku-Folgearbeit (Abschnitt 6):** erledigt — Zielbild-Regel in
[`spec/architecture.md`](../../../spec/architecture.md); beide gelieferten Pläne nach `done-archive/`
([`hexagonal-port.md`](../done-archive/hexagonal-port.md),
[`phase-e2-persistence.md`](../done-archive/phase-e2-persistence.md)); Live-Refs nachgezogen, Handbuch
self-contained (Flyway-/Backup-Inhalt inline). Zusätzlich der Port-Atomicity-Vertrag **de-phast**
(umbenannt nach `spec/port-atomicity.md`, Phasen-Naming raus). **Restschuld am Hygiene-Tracker
[`spec-milestone-reference-hygiene.md`](spec-milestone-reference-hygiene.md):** die
verbleibende Phase-E-Provenienz in den Labels von `ki-mcp`/`job-contract` (und
`docs/operations/job-executor.md`) als Familie auflösen.
