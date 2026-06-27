# Ports-JDBC-Entkopplung: `java.sql` aus `ports-common`/`ports-execute` entfernen

> **Status:** Draft mit Scope (Entwurf, 2026-06-27). Aktiv (→ `../in-progress/`) beim ersten
> Implementierungs-Commit. **Entscheidungsgrundlage:**
> [ADR 0022](../../adr/0022-ports-jdbc-entkopplung.md) (accepted, Option A).

## 1. Ziel

Die Hexagon-Ports-Schicht von JDBC befreien: `hexagon:ports-common` und `hexagon:ports-execute`
exponieren heute `java.sql.Connection`; nach diesem Slice tun sie es nicht mehr. JDBC lebt nur
noch in den Adaptern. Umgesetzt über das neutrale `DatabaseConnection` (ADR 0022), das das in
`spec/hexagonal-port.md` (D4) ursprünglich vorgesehene Soll endlich realisiert.

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
- **`ConnectionPool` ist Vertragswährung** der Datenports — `SchemaReader`/`DataReader`/
  `DataWriter`/`TableLister` nehmen alle `pool: ConnectionPool`. Darum wird der Pool **nicht**
  verlagert, sondern nur sein Rückgabetyp neutralisiert (ADR 0022, Option B verworfen).

## 3. Scope

### 3.1 In Scope

- Neues `DatabaseConnection`-Interface in `hexagon:ports-common` (minimale Fähigkeiten).
- `JdbcDatabaseConnection`-Adapter (Hikari-Wrapper) in `adapters/driven/driver-common`.
- Signatur-Umstellung `ConnectionPool.borrow()` + `AtomicSequencePreserveExecutor` (+ Callback +
  `requireOwnedConnection`).
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
- **P4 — Verriegeln (Fitness-Function).** Eine Detekt-`ForbiddenImport`-Regel (oder ein
  `consumer-read-probe`-artiger Test), die `java.sql.*` in `hexagon/ports*` als Verstoß meldet —
  damit der Leak nicht zurückkehrt. **DoD:** Regel aktiv, Gate grün; ein bewusst eingefügter
  Test-Verstoß failt.

## 5. Akzeptanzkriterien

- `grep -rl "java.sql" hexagon/ports-common hexagon/ports-execute` ist **leer** (main **und**
  test).
- Build, Detekt, Kover (≥ 90 % pro Modul) grün; die Architektur-Fitness-Function (P4) grün und
  scharf.
- Hikari-`close()`-zurück-in-den-Pool-Semantik nachweislich erhalten (P1-Test).
- `AtomicSequencePreserveContractTest` grün; **kein** Verhaltenswechsel im Atomic-Preserve-Pfad.
- `DatabaseConnection` trägt nur die benötigten Operationen (Review-Leitplanke gegen Leaky
  Abstraction, ADR 0022).

## 6. Doku-Folgearbeit (nach dem Code-Fix, nicht Teil der Phasen)

Sobald der Code JDBC-frei ist, die Doku-Lage bereinigen (war Auslöser dieses Slice):

1. Die Zielbild-Regel „Ports exponieren kein `java.sql`; JDBC nur in Adaptern" in
   [`../../../spec/architecture.md`](../../../spec/architecture.md) verankern (an die Modul-
   Regel; **ohne** Abwärtsverweis auf die ADR — SDP).
2. [`../../../spec/hexagonal-port.md`](../../../spec/hexagonal-port.md) (gelieferter Überführungs-
   plan; D1/D2/D5/D7-Lücken sind gebaut) nach `../done-archive/` verschieben — die überholte
   `java.sql`-in-Ports-Erlaubnis (Z. 100, 252) **nicht** ins Zielbild übernehmen. Eingehende
   Verweise nachziehen (u. a. [`../open/spec-milestone-reference-hygiene.md`](../open/spec-milestone-reference-hygiene.md),
   `spec/phase-e-port-atomicity.md`).
3. [`../../../spec/phase-e2-persistence.md`](../../../spec/phase-e2-persistence.md) (zweite
   `java.sql`-Fundstelle, phasen-benannt) gleich mit-triagieren.

## 7. Vorbedingungen

- [ADR 0022](../../adr/0022-ports-jdbc-entkopplung.md) **accepted** (Option A, 2026-06-27) — die
  Abstraktionsform (`DatabaseConnection`) steht; P1 kann starten.

## 8. Bezug

- ADR: [0022](../../adr/0022-ports-jdbc-entkopplung.md).
- D4-Soll + Steady-State: [`../../../spec/hexagonal-port.md`](../../../spec/hexagonal-port.md),
  [`../../../spec/architecture.md`](../../../spec/architecture.md).
- Backend-Neutralitäts-Intention: [`../../../spec/phase-e-port-atomicity.md`](../../../spec/phase-e-port-atomicity.md).
