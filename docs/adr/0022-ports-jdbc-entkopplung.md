---
status: accepted
date: 2026-06-27
decision-makers: pt9912
consulted: spec/hexagonal-port.md (D4-Soll "DatabaseConnection"), spec/architecture.md (Schicht-/Abhängigkeitsregeln), spec/phase-e-port-atomicity.md (backend-neutrale Atomicity-Ports)
informed: hexagon/ports-common, hexagon/ports-execute, adapters/driven/driver-common
---

# Ports-Schicht ohne `java.sql`: neutrale `DatabaseConnection`-Abstraktion statt durchgereichter JDBC-Connection

> **Status: accepted (2026-06-27).** Mechanismus **Option A** (neutrales `DatabaseConnection`,
> JDBC-Impl im Adapter) ratifiziert. Die Umsetzung samt Phasen liegt im Slice
> [`../planning/in-progress/ports-jdbc-entkopplung.md`](../planning/in-progress/ports-jdbc-entkopplung.md).

## Kontext und Problemstellung

Die Hexagon-Ports-Schicht exponiert an zwei Stellen den JDBC-Typ `java.sql.Connection`:

- **`hexagon:ports-common`** — `ConnectionPool.borrow(): java.sql.Connection`
  (`hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionPool.kt`).
- **`hexagon:ports-execute`** — `AtomicSequencePreserveExecutor.execute(connection: java.sql.Connection, …)`
  inklusive `executeProtectedOperations: (java.sql.Connection, …) -> …` und
  `requireOwnedConnection(connection)`
  (`hexagon/ports-execute/src/main/kotlin/dev/dmigrate/driver/migration/preserve/AtomicSequencePreserveExecutor.kt`).

Das verletzt das hexagonale Grundprinzip — **Ports sind neutrale Verträge, Technologie lebt in
den Adaptern** — und weicht vom ursprünglichen Entwurf ab: `spec/hexagonal-port.md` nannte unter
D4 einen neutralen `DatabaseConnection` als **Soll**; der Code divergierte (bewusst, aber ohne
Zielbild-Deckung) zu `ConnectionPool`/`java.sql.Connection`.

Zwei Beobachtungen schärfen das Bild:

1. **Der Leak ist asymmetrisch.** Im Pool ist `java.sql.Connection` nur der **Rückgabetyp** von
   `borrow()` — kein Port-Code ruft Connection-Methoden auf, die 56 `borrow()`-Konsumenten liegen
   in den Adaptern. Im Executor dagegen wird die Connection **verhaltensmäßig genutzt**
   (BEGIN/COMMIT/ROLLBACK über `autoCommit`, Ausführung gerenderter Statements, Session-Reset im
   `finally`). Die Abstraktion muss also für den Pool nur ein opakes Handle, für den Executor eine
   minimale Fähigkeits-Schnittstelle sein.
2. **Es gibt keine Zielbild-Deckung für den Status quo.** Die einzige Spec-Aussage, die
   `java.sql` in den Ports *erlaubt*, steht in `spec/hexagonal-port.md` (einem gelieferten
   Überführungs**plan** mit Ist/Soll-Momentaufnahme, kein Zielbild) und in
   `spec/phase-e2-persistence.md`. Das Zielbild `spec/architecture.md` schweigt dazu. Eine
   Momentaufnahme kann kein Zielbild sein; die „Erlaubnis" trägt daher nicht.

## Entscheidung

1. **Die Ports-Schicht** (`hexagon:ports-common`, `-read`, `-write`, `-execute`) **exponiert kein
   `java.sql` mehr.** JDBC lebt ausschließlich in den Adaptern.
2. Einführung eines **neutralen `DatabaseConnection`** in `hexagon:ports-common` (erfüllt das
   D4-Soll). Es trägt **genau** die von den Ports benötigten Fähigkeiten, nicht mehr:
   `AutoCloseable` (Pool-Rückgabe), Transaktions-Lebenszyklus (Begin/Commit/Rollback bzw.
   Owned-Transaction-Prüfung statt `autoCommit`), Ausführung **bereits gerenderter** Statements,
   Session-Einstellungs-Reset (für das Lock-Timeout-`finally`).
3. **Signatur-Umstellung:** `ConnectionPool.borrow(): DatabaseConnection`;
   `AtomicSequencePreserveExecutor` und sein `executeProtectedOperations`-Callback nehmen
   `DatabaseConnection`.
4. Die **JDBC-gebundene Implementierung** (`JdbcDatabaseConnection`, Wrapper um die Hikari-
   `java.sql.Connection`) lebt in `adapters/driven/driver-common`. Die Hikari-`close()`-zurück-in-
   den-Pool-Semantik bleibt erhalten; Adapter-Konsumenten kommen über diese Adapter-Klasse an die
   reale Connection.

`ConnectionPool` bleibt **Vertragswährung** der Datenports — nur sein Rückgabetyp wird
neutralisiert (siehe verworfene Option B).

## Betrachtete Optionen

- **A — Neutrales `DatabaseConnection`-Handle in den Ports, JDBC-Impl im Adapter (gewählt).**
  Minimaler, zielgerichteter Eingriff; richtet den Code am D4-Soll aus.
- **B — `ConnectionPool` aus den Ports nach `driver-common` verlagern.** Verworfen: `ConnectionPool`
  ist Parameter-Typ aller Datenports (`SchemaReader.read(pool)`, `DataReader(pool)`,
  `DataWriter(pool)`, `TableLister.listTables(pool)`); eine Verlagerung bräche sämtliche
  Port-Signaturen — ein viel größerer, nicht gerechtfertigter Umbau.
- **C — `java.sql` in den Ports formalisieren (Status quo legitimieren).** Verworfen: widerspricht
  dem Hexagon-Prinzip; die einzige „Erlaubnis" steht in einem Nicht-Zielbild-Plan, nicht im
  Zielbild.

## Konsequenzen

**Positiv:**
- Die Ports werden technologie-neutral und ohne JDBC testbar — ein echter Hexagon-Vertrag, kein
  durchgereichter Treiber-Typ. Der `AtomicSequencePreserveExecutor` wird so backend-neutral, wie es
  die Port-Idee vorsieht (vgl. `spec/phase-e-port-atomicity.md`, das persistente Nicht-JDBC-Backends
  als möglichen Implementor benennt).
- Der Code richtet sich am dokumentierten D4-Soll aus, statt davon abzuweichen.

**Negativ / Abwägung:**
- **Leaky-Abstraction-Risiko:** `DatabaseConnection` darf **nur** die von den Ports benötigten
  Operationen tragen, nicht die `java.sql.Connection`-Oberfläche unter neutralem Namen spiegeln.
  Bewusst minimal gehalten; das ist die zentrale Review-Leitplanke des Slice.
- **Spürbarer Refactor:** 56 `borrow()`-Konsumenten in den Adaptern müssen über die Adapter-Impl an
  die reale Connection (Unwrap im Adapter); Executor + `*ContractTest` (heute `mockk<java.sql.Connection>`)
  umstellen. Mechanik und Phasen im Slice.

## Abgrenzung (Nicht-Ziele)

- **Kein Re-Design der Datenports.** `ConnectionPool` bleibt die Währung; nur der Rückgabetyp wird
  neutralisiert.
- **Keine zweite (Nicht-JDBC-)Implementierung** in diesem Schritt — nur die JDBC-Impl, aber so
  geschnitten, dass eine Nicht-JDBC-Impl möglich wird.
- **Doku-Folgearbeit** (die Zielbild-Regel in `spec/architecture.md` verankern;
  `spec/hexagonal-port.md` + `spec/phase-e2-persistence.md` als gelieferte Pläne archivieren) ist im
  Slice bzw. Tracker geführt, **nicht** Teil dieser ADR.
