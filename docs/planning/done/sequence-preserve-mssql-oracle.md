---
id: sequence-preserve-mssql-oracle
title: "Das Preserve-Fenster gibt es auf fünf Dialekten — belegt ist es auf dreien"
status: done
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

## Entscheidung (Eigner, 2026-09-12): Warnung jetzt, Opt-in-Frage später

Die offene Frage aus Punkt 2 — Warnung im Report oder Blocker mit Opt-in — ist
**gestaffelt** entschieden: **jetzt eine Warnung**, und die Frage, ob daraus
später ein Opt-in-Blocker wird, bekommt ein eigenes Ticket, das auf der
Messung des Fehlerfalls aus Slice A aufsetzt.

Der Grund für die Staffelung: das Oracle-Fenster **funktioniert**, es sichert
nur weniger zu. Es heute zu blocken bräche bestehende Läufe für eine
Eigenschaft, die sich nicht ändern lässt (Oracle committet DDL implizit). Und
ob ein Blocker das richtige Mittel ist, lässt sich erst beurteilen, wenn der
Fehlerfall gemessen ist statt beschrieben.

## Der Schnitt

Vier Scheiben, in dieser Reihenfolge. A ist die Voraussetzung für alles andere:
erst messen, dann behaupten.

### A — Live-Abnahme SQL Server (3 Specs)

Gleicher Zuschnitt wie bei den drei belegten Dialekten, damit die Reihe
vergleichbar bleibt:

| Spec | Vorbild | Was sie belegt |
| --- | --- | --- |
| `MssqlAtomicSequencePreserveExecutorIntegrationTest` | `SqliteAtomicSequencePreserveExecutorIntegrationTest` | `sp_getapplock` hält das Fenster; eine zweite Sitzung kommt nicht dazwischen |
| `MssqlSchemaMigrateAtomicPreserveIntegrationTest` | `SqliteSchemaMigrateAtomicPreserveIntegrationTest` | `schema migrate --execute` mit Preserve-Kandidat läuft durch, der Wert steht danach auf dem vorgefundenen Stand |
| `MssqlSequenceCurrentValueProbeIntegrationTest` | das PG-Pendant | die Probe liest den Stand, den der Server wirklich führt |

**Kosten:** SQL Server braucht ~2 min Containerstart je Spec-Klasse; die drei
gehören deshalb in **eine** Klasse mit gemeinsamem `beforeSpec`, nicht in drei.

### B — Live-Abnahme Oracle (3 Specs + der Fehlerfall)

Dieselben drei, plus die Scheibe, die SQL Server nicht braucht:

- **Der Abbruch mitten im Fenster.** Ein gezielt herbeigeführter Fehlschlag
  zwischen zwei geschützten Operationen, und der Nachweis, dass die erste
  **steht** — das ist der Unterschied zwischen `SERIALIZED` und `ATOMIC`, und
  er ist heute nur behauptet. Ohne diese Messung darf C nichts zusagen.

**Offene Frage in B, vor dem Bau zu klären:** wie der Fehlschlag herbeigeführt
wird, ohne den Test von Zufall abhängig zu machen. Kandidat: eine zweite
geschützte Operation, die am Server scheitert (etwa ein `ALTER SEQUENCE` auf
einen Wert, den Oracle ablehnt), statt einen Abbruch von außen zu simulieren.

### C — `preserveWindowIsolation` erreicht den Betreiber

Heute liest kein Zweig das Feld. Nach A/B:

- Ein Lauf mit Preserve-Kandidat gegen einen `SERIALIZED`-Dialekt setzt eine
  **Warnung** in den Report — mit der Folge im Klartext: „ein Abbruch im
  Fenster lässt angewandte Operationen stehen".
- `NONE` bleibt, was es ist: der Dialekt bietet das Fenster nicht an.
- Ein neuer Warncode gehört in den Ledger (`warn-code-ledger-*`), anders als
  bei der R-Serie ist der dort geführt und getestet.

**Nicht in C:** ein Blocker, eine Option, ein Opt-in. Das ist das Folgeticket.

### D — Kommentar und Doku nachziehen

Erst jetzt, damit jede Zusage auf einer Messung steht:

- Der Kommentar in `SequenceCapabilityDefaults`, der Oracle für gesperrt
  erklärt und drei Zeilen später das Gegenteil konfiguriert.
- `docs/user/guide.md`: alle fünf Lock-Strategien, mit dem Oracle-Vorbehalt am
  Fundort statt in einer Fußnote.
- Die Kurzmatrix im Anwenderhandbuch: Oracle kommt dort gar nicht vor.
- Die Root-READMEs nennen `preserveCurrentValue` für alle fünf.

## Aufwand und Reihenfolge

A und B sind der Löwenanteil und laufen fast nur in Containerzeit (SQL Server
~2 min, Oracle ~1,5 min je Klasse). C ist klein, hängt aber inhaltlich an B. D
ist reine Textarbeit, darf aber nicht vorgezogen werden — genau das hat den
Befund erzeugt.

## Gebaut (2026-09-12)

**A und B — die Live-Abnahme.** Je eine Klasse für SQL Server (sieben Specs) und
Oracle (fünf), beide mit Probe, Executor und — bei SQL Server — dem Weg über
`schema migrate --execute`. Eine Klasse statt drei, weil der Containerstart der
teuerste Teil ist.

Was die Abnahme zutage förderte, stand vorher nirgends:

- **SQL Server braucht die Sequenz-Definition im Batch.** `RESTART WITH` setzt
  den *nächsten* Wert, also entscheiden Schrittweite und Schranken über den
  Fortsetzungspunkt. Ohne `AtomicSequencePreserveRequest.sequence` blockt der
  Executor — richtig so, aber es war nirgends belegt.
- **Oracles `DBMS_LOCK` fehlt dem Containernutzer.** Gemessen: ohne
  `GRANT EXECUTE ON SYS.DBMS_LOCK` meldet der Executor benannt `Failed` samt
  der nötigen Anweisung — kein stiller Rückfall auf ein ungeschütztes Fenster.
  Und: `system` darf diesen Grant **nicht** vergeben (ORA-01031), nur SYS als
  SYSDBA. Das ist die Voraussetzung, die ein Betreiber kennen muss.
- **Der Unterschied ist jetzt gemessen, nicht behauptet.** Die letzte
  Oracle-Spec führt im Fenster zwei DDLs aus, von denen das zweite scheitert,
  und prüft danach, dass das erste **steht**. Genau das trennt `SERIALIZED` von
  `ATOMIC`.
- **Nebenbefund:** `OracleSequenceCurrentValueProbe` war als einziger der fünf
  `internal`. Jetzt nicht mehr — vier von fünf waren es nie.

**C — die Warnung.** `preserveWindowIsolation` wird gelesen: ein Lauf mit
Preserve-Kandidat gegen ein `SERIALIZED`-Ziel setzt `W159` in den Report, mit
der Folge im Klartext. Warnung und nicht Blocker, wie entschieden; die Frage
nach einem Opt-in steht in
[`preserve-window-serialized-opt-in.md`](../open/preserve-window-serialized-opt-in.md)
und hat jetzt die Messung, auf die sie wartete.

**D — Kommentar und Doku.** Der Absatz in `SequenceCapabilityDefaults`, der
Oracle für gesperrt erklärte und drei Zeilen später das Gegenteil
konfigurierte, sagt jetzt, was gebaut ist und was es nicht ist. Beide READMEs
nennen `preserveCurrentValue` für alle fünf Dialekte samt Oracle-Vorbehalt.

**Was von Punkt 4 nicht mehr galt:** `guide.md` und die Kurzmatrix im
Anwenderhandbuch führten die fünf Lock-Strategien und den Oracle-Vorbehalt
bereits — die Beobachtung im Plan war überholt. Nur die READMEs trugen noch die
Dreierliste.

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
