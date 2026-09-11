---
id: sequence-preserve-mssql-oracle
title: "Das Preserve-Fenster gibt es auf fünf Dialekten — belegt ist es auf dreien"
status: open
---

# Das Preserve-Fenster gibt es auf fünf Dialekten — belegt ist es auf dreien

## Der Auslöser

Die Startseite sagte `preserveCurrentValue` für „PG / MySQL / SQLite" zu, und
die Frage war, warum SQL Server und Oracle fehlen. Sie fehlen nicht. Gebaut ist
beides; was fehlt, ist der Beleg und die Auskunft darüber, was das Fenster dort
leistet.

## Was gemessen vorhanden ist

| | `supportsCurrentValuePreserve` | Probe | Executor | `PRESERVE_DIALECTS` | Dispatcher |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL | ✅ | ✅ | ✅ | ✅ | ✅ |
| MySQL | ✅ | ✅ | ✅ | ✅ | ✅ |
| SQLite | ✅ | ✅ | ✅ | ✅ | ✅ |
| SQL Server | ✅ | `MssqlSequenceCurrentValueProbe` | `MssqlAtomicSequencePreserveExecutor` (`sys.sp_getapplock`) | ✅ | ✅ |
| Oracle | ✅ | `OracleSequenceCurrentValueProbe` | `OracleSequencePreserveExecutor` (`DBMS_LOCK`) | ✅ | ✅ |

Die beiden Executor sind keine Attrappen: der Oracle-Pfad trägt eine
Live-Messung im KDoc — mit `release_on_commit => TRUE` bekommt eine zweite
Sitzung die Sperre, sobald die erste ein DDL ausgeführt hat, weil das implizite
Commit sie mitten im Fenster freigibt.

## Was fehlt

### 1. Die Live-Abnahme

PostgreSQL, MySQL und SQLite tragen je drei Integrationsspecs: den Executor,
den Weg über `schema migrate --execute`, und die Probe. Für die beiden anderen
Dialekte gibt es je einen Ausschnitt:

| Dialekt | Executor-IT | `SchemaMigrate…AtomicPreserve`-IT | Probe-IT | sonstige |
| --- | --- | --- | --- | --- |
| PostgreSQL / MySQL / SQLite | ✅ | ✅ | ✅ | Cross-Plan-Deadlock |
| SQL Server | ❌ | ❌ | ❌ | nur Cross-Plan-Deadlock |
| Oracle | ❌ | ❌ | ❌ | nur Preserve-Window |

Ob ein `schema migrate --execute` mit einem Preserve-Kandidaten auf diesen
beiden Dialekten durchläuft und der Wert danach wirklich steht, ist nirgends
belegt. Der Code sagt ja; gemessen hat es niemand.

### 2. `preserveWindowIsolation` wird produktiv nie gelesen

Das Feld unterscheidet, **was** das Fenster zusichert:

- `ATOMIC` — alles oder nichts (PostgreSQL, MySQL, SQLite, SQL Server).
- `SERIALIZED` — niemand kommt dazwischen, ein Fehlschlag lässt aber stehen,
  was bis dahin lief (Oracle: jedes DDL committet implizit, und sowohl die
  geschützten Operationen als auch der Restore sind DDL).
- `NONE` — der Dialekt bietet das Fenster nicht an.

Produktiv steht der Wert nur in einem KDoc von `PlannerBlockerClassifier`. Kein
Zweig liest ihn. Ein Betreiber, der `preserveCurrentValue` auf Oracle fährt,
bekommt damit dasselbe Bild wie auf PostgreSQL — und erfährt nicht, dass ein
Abbruch in der Mitte Angewandtes stehen lässt. Das ist der schwerste Punkt
dieses Slices: die Zusage ist im Modell vorhanden und erreicht den Adressaten
nicht.

### 3. Der Oracle-Kommentar widerspricht seinem eigenen Code

`SequenceCapabilityDefaults` sagt über Oracle: „Der atomare Ausfuehrungspfad
fehlt weiterhin -- deshalb bleiben die Atomic-Faehigkeiten false und
protectedSequenceOperations leer." Drei Zeilen darunter stehen
`preserveWindowIsolation = SERIALIZED`, `preserveAllCandidatesInOneWindow =
true` und alle drei geschützten Operationen. Wer den Kommentar liest, hält
Oracle für gesperrt.

### 4. Die Dokumentation sagt drei

- Die Root-READMEs nennen `preserveCurrentValue` für „PG / MySQL / SQLite".
- `docs/user/guide.md`, „preserveCurrentValue (atomar unter Lock)", führt
  Lock-Strategien für MySQL/SQLite und PostgreSQL auf. `sp_getapplock` und
  `DBMS_LOCK` fehlen — und damit fehlt genau die Warnung, dass das
  Oracle-Fenster nicht atomar ist.
- Die Kurzmatrix im Anwenderhandbuch begründet ausdrücklich, warum SQL Server
  fehlt; Oracle kommt dort nicht vor.

## Der Schnitt

1. **Live-Abnahme SQL Server und Oracle.** Je ein Executor-IT, ein Weg über
   `schema migrate --execute`, und ein Probe-IT — gleicher Zuschnitt wie bei
   den drei belegten Dialekten, damit die Reihe vergleichbar bleibt. Der
   Oracle-Fall braucht zusätzlich den Nachweis des Fehlerfalls: ein Abbruch
   zwischen zwei geschützten Operationen lässt Angewandtes stehen.
2. **`preserveWindowIsolation` wirksam machen.** Wer `SERIALIZED` fährt, muss
   es erfahren. Zu entscheiden ist die Form: Warnung im Report oder Blocker mit
   Opt-in. Die Wahl gehört in diesen Slice, nicht in seine Umsetzung.
3. **Kommentar und Doku nachziehen** — erst nach Punkt 1, damit die Zusage auf
   einer Messung steht und nicht auf einer zweiten Behauptung.

## Akzeptanzkriterien

- Ein `schema migrate --execute` mit Preserve-Kandidat läuft auf SQL Server und
  Oracle live durch, und der Sequenzwert steht danach nachweislich auf dem
  vorgefundenen Stand.
- Ein Abbruch im Oracle-Fenster ist als das sichtbar, was er ist: die
  Auswertung von `preserveWindowIsolation` erreicht den Betreiber, und ein
  gezielt herbeigeführter Fehlschlag belegt das.
- `guide.md` führt alle fünf Lock-Strategien, mit dem Oracle-Vorbehalt am
  Fundort — nicht in einer Fußnote.
- Die Root-READMEs nennen `preserveCurrentValue` für alle fünf Dialekte, und
  zwar erst, wenn die Abnahme steht.

## Nicht-Umfang

- Der App-seitige `nextval`-Race auf PostgreSQL und Oracle (nativ lock-freie
  Sequenzen). Er ist in `sequence-preserve-atomic-lock-plan.md` als bewusster
  Carve-Out geführt und ändert sich hier nicht.
- Ein atomares Oracle-Fenster. Oracle committet DDL implizit; das ist keine
  Lücke, die man schließen kann, sondern eine Eigenschaft, die man benennen
  muss.

## Herkunft

Gefunden am 2026-09-11 beim Richtigstellen der Dialekt-Aufzählungen in den
Root-READMEs: die Dreierliste bei `preserveCurrentValue` wurde zunächst als
korrekt bestätigt, weil nur nach dem Bezeichner `preserveCurrentValue` in den
Treibern gesucht wurde — auf SQL Server und Oracle heißt derselbe Pfad
`renderAlterSequenceCurrentValue`, `…SequenceCurrentValueProbe` und
`…AtomicSequencePreserveExecutor`.
