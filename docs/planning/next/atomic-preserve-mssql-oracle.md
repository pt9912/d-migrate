# Atomic-Preserve für MSSQL und Oracle nachrüsten

> **Status:** Draft mit Scope (2026-09-05). Scope skizziert, **noch keine
> aktive Slice-Arbeit** im Code.
> **Trigger:** Beim Oracle-Slice-1-Bau musste `SequenceCapabilityDefaults`
> um Oracle ergänzt werden (`supportsAtomicPreserve = false`). Dabei fiel
> auf: **MSSQL** trägt dieselben `false`-Werte, obwohl MSSQL als vierter
> Dialekt längst abgeschlossen ist (alle eigenen Slices ✅). Der einzige
> „Beleg" dafür war ein Code-Kommentar — nicht normativ. Eigner-Entscheidung
> 2026-09-05: für beide Dialekte einen Nachrüst-Slice anlegen.

## Kontext

Atomic-Preserve (Probe + Restore + geschützte DDL in einer einzigen
Transaktion unter Per-Dialekt-Lock) wurde 2026-06-01 für PostgreSQL, MySQL
und SQLite gebaut
([`sequence-preserve-atomic-lock-plan.md`](../done-archive/sequence-preserve-atomic-lock-plan.md),
[`ImpPlan-0.9.8-atomic-preserve-AE.md`](../done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md)) —
**vor** MSSQL als viertem Dialekt (ADR 0047, 2026-08-21) und lange vor
Oracle (ADR 0052, 2026-09-05). Keins von beiden wurde je nachgezogen.

`AtomicSequencePreserveDispatcher.executorFor(...)` und
`AtomicPreserveRestoreSql.forDialect(...)` behandeln MSSQL/Oracle heute mit
`error("unreachable: …")`, weil `SequenceCapabilityDefaults` beiden
`supportsAtomicPreserve = false` zuweist — das Gate greift korrekt, es fehlt
nur die eigentliche Implementierung.

## Zwei unabhängige Phasen

**Phase A — MSSQL.** Sofort startbar: MSSQL hat bereits einen vollständigen
`schema migrate`-Pfad (Slice 5, `MssqlDiff*Ops`).

**Phase B — Oracle.** **Blockiert bis Oracle Slice 5** (Diff/Migrate,
`docs/planning/in-progress/oracle-dialect-scoping.md`) geliefert ist — ein
Atomic-Preserve-Pfad ohne einen bestehenden `schema migrate`-Pfad hat
nichts, worin er sich einreiht.

Beide Phasen brauchen dieselben Bausteine, je Dialekt:

1. **Lock-Strategie entscheiden** (Kandidaten unten).
2. `<Dialekt>AtomicSequencePreserveExecutor` (Probe + Restore + DDL in einer
   Transaktion unter dem gewählten Lock).
3. Wiring in `AtomicSequencePreserveDispatcher.executorFor(...)` und
   `AtomicPreserveRestoreSql.forDialect(...)` (beide `error("unreachable…")`
   → echte Implementierung).
4. `SequenceCapabilityDefaults`: `supportsAtomicPreserve` +
   `supportsAtomicPreserveAllInPlan` auf `true`, `transactionalProtected­SequenceOperations`
   auf die bestehende Allowlist (`CreateSequence`/`AlterSequence`/`RenameSequence`).
5. Cross-Plan-Deadlock-Test (analog `PostgresAtomicSequencePreserveCrossPlanDeadlockTest`
   u. a.): belegt, dass die namensortierte Lock-Reihenfolge das Diamant-Problem
   zwischen parallelen Läufen schließt.
6. `SequenceCapabilityTest`s Matrix-Ausnahme-Liste um den fertigen Dialekt
   verkleinern (er rutscht dann in den `else`-Zweig „jeder Dialekt
   unterstützt Atomic-Preserve").

### Lock-Strategie-Kandidaten

**MSSQL:** `sys.sp_getapplock` (Named-Application-Lock, seit SQL Server
2000) — das direkte T-SQL-Äquivalent zu PostgreSQLs
`pg_advisory_xact_lock(hashtext(...))`. Transaktionsgebunden
(`@LockOwner = 'Transaction'`), löst sich beim Commit/Rollback automatisch —
kein manuelles Release nötig, passt zur bestehenden Pattern-Familie.

**Oracle:** zwei Kandidaten, **Entscheidung offen**:

- `DBMS_LOCK.REQUEST`/`DBMS_LOCK.RELEASE` — Oracles natives User-Lock-Paket,
  semantisch am nächsten zu PGs Advisory-Locks. **Risiko:** `EXECUTE`-Recht
  auf `DBMS_LOCK` ist nicht in jeder Oracle-Installation frei vergeben
  (abhängig von DBA-Policy) — ein Operator könnte den Pfad nutzen wollen,
  ohne das Privileg zu haben.
- `SELECT … FOR UPDATE` auf eine Sentinel-Zeile in einer d-migrate-eigenen
  Kontrolltabelle (analog dem bereits etablierten `dmg_sequences`-Helper-
  Tabellen-Muster für MySQL/SQLite-Sequenz-Emulation) — portabler, keine
  Sonderrechte, aber ein zusätzliches Schema-Objekt im Zielschema.

Diese Entscheidung braucht den Eigner, bevor Phase B beginnt (die ohnehin
erst nach Oracle Slice 5 startet).

## Akzeptanzkriterien (je Phase)

- Migrate-Plan mit mehreren Sequenz-Operationen läuft unter Last mit einem
  zweiten, parallelen Lauf ohne Deadlock (Cross-Plan-Test grün).
- `SequenceCapabilityTest`s Matrix-Test erwartet den fertigen Dialekt im
  `else`-Zweig (volle Unterstützung), nicht mehr in der Ausnahme-Liste.
- `AtomicSequencePreserveDispatcher`/`AtomicPreserveRestoreSql` liefern eine
  echte Implementierung statt `error("unreachable…")`.

## Nicht-Ziel

- Kein neues Neutralmodell-Feld — Atomic-Preserve ist reine
  Ausführungs-Infrastruktur, keine Schema-Eigenschaft.
- Phase B beginnt nicht vor Oracle Slice 5 — keine Umgehung dieser
  Abhängigkeit durch einen Interims-Workaround.
