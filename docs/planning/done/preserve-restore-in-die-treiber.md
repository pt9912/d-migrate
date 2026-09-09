# Das Zurückschreiben nach dem Preserve rendert der Treiber, nicht die Anwendungsschicht

## Befund

`AtomicPreserveRestoreSql` lag in `hexagon/application` und baute das
Restore-SQL für alle fünf Dialekte — 161 Zeilen mit
`when (dialect) → postgres/mysql/sqlite/mssql/oracle` und fünf privaten
Rendermethoden. Die Anwendungsschicht reichte das fertige SQL als
`renderRestore`-Closure an `AtomicSequencePreserveRequest` weiter.

Empfangen hat es der **treibereigene** Executor: `MssqlAtomicSequence
PreserveExecutor`, `OracleSequencePreserveExecutor` und die drei übrigen
liegen je in ihrem Treibermodul und riefen `request.renderRestore(probe)`.
Der Teil, der den Dialekt ohnehin kennt, ließ sich dessen SQL von außen
reichen.

Die eigene KDoc des Objekts nannte die Folge bereits:

> Drift between this object and the dialect renderers' restore SQL is a real
> regression risk … Until then both implementations carry identical SQL
> templates per dialect.

Zwei Kopien desselben SQL je Dialekt, per Kommentar als Risiko vermerkt.

Sichtbar wurde es an einer anderen Stelle: `MssqlSequenceResume` und
`OracleSequenceResume` — die Rechnung „wo läuft die Sequenz weiter" — lagen in
`hexagon/ports-read`, obwohl `OracleSequenceResume` von `driver-oracle` gar
nicht benutzt wurde. Sie lagen dort **nur**, weil die Anwendungsschicht sie
brauchte.

## Was gebaut wurde

`AtomicSequencePreserveRequest` trägt statt einer Render-Closure die
**Sequenz aus dem Soll-Schema**. Jeder Executor rendert sein eigenes SQL —
über denselben Bauer, den auch sein Diff-Renderer benutzt:

| Dialekt | geteilter Bauer |
|---|---|
| PostgreSQL | `PostgresDiffSequenceOps.setvalSql` |
| MySQL | `MysqlDiffSequenceOps.updateNextValueSql` |
| SQLite | `SqliteDiffSequenceOps.updateNextValueSql` |
| SQL Server | `MssqlSequenceDdl.restartSql` |
| Oracle | `OracleSequenceDdl.restartSql` |

Damit ist die im Kommentar vermerkte Drift nicht mehr möglich: es gibt je
Dialekt **eine** Quelle für die Form.

`MssqlSequenceResume` und `OracleSequenceResume` liegen jetzt in ihren
Treibermodulen; die Anwendungsschicht kennt keine Sequenz-Fortsetzungsregel
mehr. `SequencePreserveStage.buildBatch` braucht den Dialekt nicht mehr — der
Parameter fiel weg, und Detekt hat das gemeldet, bevor ein Mensch es sah.

## Was die Aufteilung an den Tests geändert hat

`AtomicPreserveRestoreSqlTest` (12 Fälle) prüfte SQL-Zeichenketten gegen ein
Objekt der Anwendungsschicht. An seine Stelle treten fünf
`<Dialekt>PreserveRestoreStatementsTest` im jeweiligen Treibermodul, und drei
Prüfungen wurden dabei **echter**:

- Der SQLite-Executor-Test fährt gegen eine **echte** SQLite-Datei; jetzt wird
  nachgesehen, dass der geprobte Wert nach dem Commit wirklich wieder in
  `dmg_sequences` steht — vorher lief dort eine Closure, die `emptyList()`
  zurückgab.
- Der Oracle- und der SQL-Server-Executor-Test prüften bislang gegen eine im
  Test erfundene Zeichenkette (`"-- seq_a"`, `"… RESTART START WITH 42"`).
  Jetzt steht dort das SQL, das der Treiber wirklich baut.
- `SequencePreserveStageTest` prüfte, dass die Stage korrektes SQL baut. Die
  Stage baut kein SQL mehr; geprüft wird jetzt, was ihr geblieben ist — dass
  sie die Sequenz aus dem **Soll**-Schema mitgibt und sie leer lässt, wenn das
  Schema sie nicht führt.

## Was offen bleibt

Drei MySQL-Regelobjekte (`MysqlSequenceSupportNaming`,
`MysqlCheckEnforcementResolver`, `MysqlSequenceCanonicityGate`) bleiben in
`hexagon/ports-read`, weil drei Stages der Anwendungsschicht sie benutzen.
Eigenes Ticket:
[`open/dialektlogik-in-den-ports.md`](../open/dialektlogik-in-den-ports.md).
