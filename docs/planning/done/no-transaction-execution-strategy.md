# Anweisungen, die außerhalb der Transaktion laufen müssen

> **Status:** Abgeschlossen — P0 bis P2 geliefert, live belegt gegen SQL Server.
> **Ziel:** Eine Migration darf Anweisungen enthalten, die eine Datenbank in
> einer offenen Transaktion ablehnt. Sie laufen in einem eigenen Abschnitt, und
> der Report sagt, was daran nicht zurückrollbar ist.
> **Trigger:** Sub-Slice 8d des MSSQL-Dialekts — `CREATE FULLTEXT INDEX` ist in
> einer offenen Transaktion verboten, der Lauf klammert aber alles in eine.

Absorbiert die Vorabklärung `open/no-transaction-execution-strategy.md`.

## 1. Ausgangslage

`TransactionScope.NO_TRANSACTION` steht im Modell, ist aber nicht ausführbar:
[`MigrationStreamClassifier`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/migration/MigrationStreamClassifier.kt)
weist den Wert ausdrücklich ab — *„NO_TRANSACTION statements require a
dedicated execution strategy"*. Die Strategie gibt es nicht.

Zwei Dialekte warten darauf. SQL Server kann Volltext-Indizes nicht anwenden
(gemessen: `CREATE FULLTEXT INDEX`, `DROP FULLTEXT INDEX` und
`DROP FULLTEXT CATALOG` sind in einer offenen Transaktion verboten, das
Anlegen des Katalogs nicht); heute bricht `schema migrate` deshalb mit `E072`
vor der Ausführung ab. PostgreSQL rendert `CREATE INDEX CONCURRENTLY` aus
demselben Grund gar nicht erst.

## 2. Was schon steht

Die Naht ist halb gebaut, und zwar an beiden Enden:

- **Das Ausführungsmodell kennt Abschnitte.** `ExecutableSegment` ist eine
  sealed Hierarchie, `segmentForExecute` ihr einziger Erzeuger, und
  `SegmentAwareMigrationExecutor` führt jeden Abschnitt mit der passenden
  Strategie aus. Der Atomic-Preserve-Weg nutzt das bereits.
- **Der Report kennt den Fall.** `MigrationExecutionStatusBuilder` bildet
  `NO_TRANSACTION` auf `TransactionBoundary.NONE` ab, und
  `ExecutionRecoverability.PARTIAL_STATE_POSSIBLE` beschreibt genau den
  Zustand, den ein fehlgeschlagener Lauf hinterlässt.

Es fehlen die drei Stücke dazwischen: die Segmentierung am Scope-Wechsel, die
Ausführung ohne Transaktion, und ein Dialekt, der den Scope setzt.

## 3. Was der Schnitt entscheidet

**Gemischt heißt nacheinander, nicht gleichzeitig.** Ein Lauf, der beides
enthält, zerfällt in Abschnitte in Planreihenfolge: transaktional, dann
außerhalb, dann wieder transaktional, so oft wie nötig. Jeder Abschnitt
committet für sich.

**Was committet ist, bleibt.** Scheitert ein späterer Abschnitt, rollt seine
eigene Transaktion zurück — die früheren nicht. Das ist keine Schwäche der
Strategie, sondern die Eigenschaft, wegen der die Anweisung überhaupt außerhalb
laufen muss. Der Lauf meldet dafür `PARTIAL_STATE_POSSIBLE`, und zwar auch
dann, wenn der fehlgeschlagene Abschnitt selbst sauber zurückgerollt ist —
bekannte Seiteneffekte schlagen die Rückbau-Aussage, sonst behauptete der
Bericht eine unveränderte Datenbank.

**`STREAM_OWNED` bleibt allein.** Ein Strom, der seine Transaktionsgrenzen
selbst mitbringt (SQLites Rebuild), verträgt keinen fremden Abschnitt daneben;
diese Mischung bleibt abgewiesen.

## 4. Arbeitspakete

| AP | Inhalt | Fertig, wenn |
| --- | --- | --- |
| **P0** ✅ | Klassifikator lässt `NO_TRANSACTION` neben `RUNNER_OWNED` zu, `segmentForExecute` trennt am Scope-Wechsel, `NoTransactionSegment`, Ausführung mit `autoCommit` und ohne Rückrollversuch | Ein gemischter Strom läuft; ein Fehlschlag nach einem committeten Abschnitt meldet `PARTIAL_STATE_POSSIBLE` |
| **P1** ✅ | SQL Server: Volltext-Anweisungen tragen den Scope, statt mit `E072` zu blocken | `schema migrate` legt einen Volltext-Index gegen echtes SQL Server an |
| **P2** ✅ | Spec, Anwenderhandbuch, CHANGELOG | `make docs-check` grün |

## 5. Nicht-Scope

- **PostgreSQL `CREATE INDEX CONCURRENTLY`.** Der Renderer kennt die Klausel
  heute gar nicht, und sie bringt eigene Fragen mit (ein fehlgeschlagener Lauf
  hinterlässt einen `INVALID`-Index, der aufgeräumt werden will). Ausgeschnitten
  nach [`pg-create-index-concurrently.md`](../open/pg-create-index-concurrently.md).
- **Rückrollen über Abschnittsgrenzen hinweg.** Ein Abschnitt, der außerhalb
  einer Transaktion lief, ist nicht zurückrollbar; ein Rollback-Artefakt
  beschreibt weiterhin nur, was die Migration rückgängig machen *würde*.

## 6. Was der Bau ergeben hat

**Die Entscheidung stand zweimal im Code.** Der produktive Ausführer und die
Test-Fixture, die dieselben Ströme gegen echte Datenbanken fährt, hatten je
eine eigene Fallunterscheidung. Der erste Live-Lauf schlug deshalb fehl, obwohl
der Produktionspfad stimmte: die Fixture kannte `NO_TRANSACTION` nicht und fuhr
die Anweisung in genau der Transaktion, die sie ablehnt. Das Modell liegt jetzt
als `StreamExecutionModel` an einer Stelle, und beide Ausführer fragen sie.

**Ein Fehlschlag nach einem committeten Abschnitt war bisher als sauberer
Rückbau gemeldet.** Der Ausführer übernahm die Flags des gescheiterten
Abschnitts; dass ein früherer bereits festgeschrieben war, ging dabei
verloren. Das betraf auch den Atomic-Preserve-Weg, der Abschnitte schon vorher
kannte — es fiel nur nicht auf, weil dort selten mehr als einer vorkam.

---

## Closure

Die Naht steht: ein Plan darf Anweisungen enthalten, die eine offene
Transaktion nicht vertragen. Sie laufen in einem eigenen Abschnitt, in
Planreihenfolge, und der Report sagt, was daran nicht zurückrollbar ist.

Der erste Nutzer ist SQL Servers Volltext-DDL — `schema migrate` legt einen
Volltext-Index jetzt an, statt mit `E072` darauf zu verweisen, dass man das DDL
selbst anwenden möge. `E072` ist damit ohne Verwendung.

Offen bleibt als eigener Schnitt
[`pg-create-index-concurrently.md`](../open/pg-create-index-concurrently.md):
PostgreSQL rendert die Klausel nicht, und sie bringt mit dem `INVALID`-Index
nach einem Abbruch eine eigene Frage mit.

## 7. Review-Nacharbeit (30.08.2026)

Ein `/code-review high` fand drei schwere Befunde, alle an Stellen, die der
erste Schnitt nicht mitgedacht hatte:

- **Der Tabellen-Neubau** rendert seine Anweisungen über `emitRebuild`, das den
  Scope fest auf `RUNNER_OWNED` setzt. Ein Neubau an einer Volltext-Tabelle
  hätte `CREATE FULLTEXT INDEX` wieder in die Transaktion geschickt — Msg 574,
  mitten im Lauf. Der Volltext-Teil verlässt den Neubau-Batch jetzt.
- **`schema rollback` segmentiert nicht.** Es reicht die Anweisungen flach an
  den Ausführer, und ein Rollback-Artefakt einer Volltext-Migration trägt genau
  die Anweisungen, die eine offene Transaktion ablehnt. Die Trennung sitzt
  deshalb jetzt im Ausführer selbst, nicht nur im Migrationspfad — dort erreicht
  sie jeden Aufrufer.
- **Eine Typänderung an einer Volltext-Spalte fiel still weg.** Der frühere
  `E072`-Block hatte die Ablehnung nebenbei mitgeliefert; ohne ihn kehrte die
  Stelle mit `null` zurück, ohne Meldung und ohne Blocker.

Dazu zwei mittlere: der Report übernahm die Einstufung des gescheiterten
Abschnitts (`FULL_ROLLBACK_CONFIRMED`, obwohl ein früherer committet hatte),
und das Verwerfen einer Spalte hätte einen mehrspaltigen Volltext-Index samt
Suche auf den übrigen Spalten mitgenommen.

**Der erste Live-Test für den Rollback-Fall bewies nichts:** er ließ die
Migration die Tabelle anlegen, und deren `DROP TABLE` nimmt den Volltext-Index
ohnehin mit. Der Test läuft jetzt gegen eine bestehende Tabelle, sodass der
Rückbau wirklich gemischt ist — gegengeprüft, indem die Trennung kurzzeitig
entfernt wurde: dann scheitert er mit Exit 5.
