# Atomic-Preserve für MSSQL und Oracle nachrüsten

> **Status:** erledigt (2026-09-09). Beide Phasen gebaut.
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

## Phase B: gemessen, und die Prämisse hält nicht (2026-09-09)

Vor dem Bau gegen Oracle 23 gemessen. Der Befund betrifft nicht die
Sperrstrategie, sondern die **Zusicherung**, die der Slice geben will.

| Messung | Ergebnis |
| --- | --- |
| `GRANT EXECUTE ON DBMS_LOCK` als `system` | `ORA-01031` — nur **SYSDBA** darf es vergeben |
| Ohne Grant: jeder `DBMS_LOCK`-Aufruf | `ORA-06550` (Paket nicht sichtbar) |
| Mit Grant: `request(release_on_commit => TRUE)` | `0` — Sperre erteilt |
| Zweite Session, während die erste hält, **ohne** DDL | `1` — Zeitüberschreitung, die Sperre greift |
| Zweite Session, **nachdem** die erste `ALTER SEQUENCE` ausführte | **`0` — Sperre frei** |
| Dasselbe mit `release_on_commit => FALSE` | `1` — Sperre überlebt das DDL |
| `DBMS_LOCK.RELEASE` danach | `0`, Sperre wieder frei |

**Oracle committet DDL implizit.** Der Restore ist dort
`ALTER SEQUENCE … RESTART START WITH n`, die geschützten Operationen
(`CreateSequence`/`AlterSequence`/`RenameSequence`) sind es ebenfalls. Eine
transaktionsgebundene Sperre fällt damit **mitten im Fenster** weg — Zeile 5
der Tabelle zeigt es: die zweite Session bekommt sie, während die erste noch
zu arbeiten glaubt.

Die session-gebundene Variante (Zeile 6/7) hält durch. Sie liefert aber nur
die eine Hälfte dessen, was die anderen drei Dialekte zusichern:

| Zusicherung | PostgreSQL / MySQL / SQLite | Oracle |
| --- | --- | --- |
| Niemand anderes verbraucht Sequenzwerte im Fenster | ja | **ja** (Session-Sperre) |
| Alles oder nichts — Fehlschlag rollt zurück | ja | **nein**, DDL ist committet |

Die zweite Zeile ist auf Oracle für **jedes** DDL unerreichbar, nicht nur
hier. `supportsAtomicPreserve = true` für Oracle zu setzen behauptete also
eine Rücknahmesicherheit, die es nicht gibt.

**Entschieden (2026-09-09, Eigner): eigene, ehrlich benannte Zusicherung.**
`PreserveWindowIsolation` unterscheidet jetzt `NONE` / `SERIALIZED` / `ATOMIC`;
Oracle steht auf `SERIALIZED`, die anderen vier auf `ATOMIC`. Wer `ATOMIC`
liest, darf nach einem Fehlschlag annehmen, dass nichts angewandt wurde — bei
`SERIALIZED` darf er das nicht.

**Und die Verfügbarkeit von `DBMS_LOCK` ist eine Eigenschaft der Verbindung,
nicht des Dialekts** (Eigner-Einwand, und er trifft): die Messung oben zeigt
nur, dass der *Standard-Testbenutzer* das Recht nicht hat. Wer als SYSDBA
fährt oder es von seinem DBA bekommen hat, kann den Pfad nutzen. Geprüft wird
deshalb zur Laufzeit an der konkreten Verbindung, und ein Fehlen wird
**benannt** gemeldet (samt dem nötigen `GRANT`), nicht durch einen stillen
Rückfall auf ein ungeschütztes Fenster verdeckt — das sähe aus wie ein
geschütztes.

**Zwei weitere Dinge, die der Bau zutage gefördert hat:**

- **Oracle hatte gar keine `SequenceCurrentValueProbe`.** Die drei älteren
  Dialekte und SQL Server haben je eine; für Oracle musste sie entstehen
  (`ALL_SEQUENCES.LAST_NUMBER`).
- **`LAST_NUMBER` ist der *nächste* Wert, nicht der zuletzt ausgegebene** —
  anders als SQL Servers `current_value`. Das war zuerst falsch angenommen und
  dann gemessen:

  | Zustand | `LAST_NUMBER` |
  | --- | --- |
  | `NOCACHE`, frisch (`START WITH 1`) | `1` |
  | nach `NEXTVAL` → 1 | `2` |
  | nach `NEXTVAL` → 2 | `3` |
  | `CACHE 20`, nach `NEXTVAL` → 1 | `21` |
  | `INCREMENT BY 5`, nach `NEXTVAL` → 100 | `105` |

  Der Restore setzt ihn deshalb **unverändert**; die Schrittweite zu addieren
  verschenkte bei jedem Preserve einen Wert. Aufgefallen ist das erst durch
  die Sabotage-Prüfung: die erste Fassung des Live-Tests prüfte nur
  „größer als vorher" und hätte jeden verschobenen Fortsetzungspunkt
  durchgelassen. Sie prüft jetzt **lückenlos**.

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

## Closure (2026-09-09)

Beide Phasen geliefert. Das Ergebnis weicht in einem Punkt vom Plan ab, und
zwar begründet: SQL Server bekam das atomare Fenster wie vorgesehen, Oracle
ein **serialisiertes** — dort ist Atomarität nicht erreichbar, weil jedes DDL
implizit committet und der Restore DDL ist. Statt das unter dem bestehenden
Flag zu behaupten, unterscheidet `PreserveWindowIsolation` jetzt
`NONE`/`SERIALIZED`/`ATOMIC`.

Was der Bau gekostet hat, stand nicht im Plan:

- **Zwei Sperr-Eigenarten, je Server eine.** `sp_getapplock` wirft nicht,
  sondern liefert eine negative Zahl — und der JDBC-Treiber eröffnet mit
  `autoCommit = false` keine Transaktion, ohne die es `-999` gibt.
  `DBMS_LOCK.REQUEST` braucht `release_on_commit => FALSE`, sonst fällt die
  Sperre beim ersten DDL weg.
- **Zwei Restore-Regeln, die beide falsch angenommen waren.** SQL Servers
  `current_value` ist der zuletzt *ausgegebene* Wert (Restore = +Schrittweite),
  Oracles `LAST_NUMBER` der *nächste* (Restore = unverändert). Die erste
  Annahme wäre ein Duplikat gewesen, die zweite eine Lücke bei jedem Lauf.
- **Oracle hatte keine Sequenz-Probe.** Sie musste erst entstehen.

Der Oracle-Fehler fiel nur durch die Sabotage-Prüfung auf: der Live-Test blieb
grün, obwohl die Regel entfernt war. Er prüfte „größer als vorher" statt
lückenlos — eine Zusicherung, die richtig und falsch nicht unterscheiden
konnte. Das ist der Grund, warum jede neue Zusicherung sabotage-geprüft wird,
und hier hat es sich unmittelbar ausgezahlt.

Offen bleibt nichts aus diesem Schnitt.
