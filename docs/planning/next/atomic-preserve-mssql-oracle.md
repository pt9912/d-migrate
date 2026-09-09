# Atomic-Preserve für MSSQL und Oracle nachrüsten

> **Status:** Phase A (MSSQL) **geliefert** (2026-09-09). Phase B (Oracle)
> entschieden (`DBMS_LOCK.REQUEST`, siehe unten), noch nicht gebaut.
>
> **Was Phase A gebracht hat, und was sie gekostet hat:** `sp_getapplock` ist
> das direkte Gegenstueck zu PGs Advisory-Lock, aber es hat eine Bedingung,
> die keine Doku vorher genannt hat und die den ersten Lauf sofort rot machte
> — **gemessen gegen SQL Server 2022:**
>
> | Zustand | `@@TRANCOUNT` | `sp_getapplock(@LockOwner='Transaction')` |
> | --- | --- | --- |
> | `autoCommit = false` gesetzt | 0 | `-999` (Parameterfehler) |
> | `autoCommit = false`, danach ein `SELECT` | 0 | `-999` |
> | explizites `BEGIN TRANSACTION` | 1 | `0` (Sperre erteilt) |
> | `@LockOwner = 'Session'` ohne Transaktion | 0 | `0` |
>
> Der JDBC-Treiber eroeffnet mit `autoCommit = false` **keine** Transaktion —
> auch nicht nach einem Statement. Der Executor setzt deshalb ein explizites
> `BEGIN TRANSACTION`. Die letzte Zeile ist die Ausweiche, die man nimmt, wenn
> man das nicht misst: `Session`-Sperren funktionieren ohne Transaktion, fallen
> aber beim Commit **nicht** von selbst weg — ein abgebrochener Lauf liesse
> sie stehen.
>
> Und die zweite Eigenart, die den Unterschied zu den drei aelteren Dialekten
> ausmacht: **`sp_getapplock` wirft nicht.** Es liefert eine negative Zahl
> (`-1` Zeitueberschreitung, `-3` Deadlock-Opfer, `-999` Parameterfehler). Ein
> Executor, der nur auf `SQLException` hoert, haelt ein nicht erworbenes Lock
> fuer ein erworbenes und schreibt ungeschuetzt weiter.
>
> **Dabei ist ein Fehler in der gemeinsamen Restore-Naht aufgefallen:**
> `sys.sequences.current_value` ist der zuletzt **ausgegebene** Wert,
> `RESTART WITH` setzt den **naechsten**. Den probierten Wert unveraendert
> zurueckzuschreiben gaebe ihn ein zweites Mal aus. Der Fortsetzungspunkt
> braucht Schrittweite und Schranken — die Formel lag im Adapter, wo die
> Anwendungsschicht nicht hinkommt, und steht jetzt als
> `MssqlSequenceResume` in `ports-read`, von beiden Pfaden benutzt statt
> zweimal geschrieben.
>
> **Abnahme:** Cross-Plan-Deadlock-Test live gegen SQL Server 2022 — zwei
> parallele Laeufe mit ueberlappenden Sequenzmengen erreichen beide `Applied`,
> der Restore-Wert kommt an, und die von Hand gekreuzte Sperrreihenfolge
> liefert die negative Statuszahl, die die Sortierung verhindert.
> Sabotage-geprueft ueber das entfernte `BEGIN TRANSACTION`.

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

**Entschieden (2026-09-08, Eigner): `DBMS_LOCK.REQUEST`.** Es ist das
semantische Gegenstück zu PostgreSQLs Advisory-Lock — transaktionsgebunden,
ohne zusätzliches Objekt im Zielschema, und damit dieselbe Denkweise wie in
den drei bestehenden Dialekten. Das fehlende `EXECUTE`-Recht wird **benannt
gemeldet**, nicht durch einen stillen Rückfall verdeckt: wer den Pfad nutzen
will, braucht das Privileg, und wer es nicht hat, erfährt es als Blocker mit
Grund. Die Sentinel-Zeilen-Variante entfällt damit; sie hätte ein Schemaobjekt
eingeführt, das kein anderer Dialekt braucht.

Oracle Slice 5 (Diff/Migrate) ist geliefert — die Abhängigkeit der Phase B ist
damit aufgelöst.

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
