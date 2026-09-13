# Dialektwissen im Lesepfad-Port, weil die Anwendungsschicht es braucht

## Status

Vorabklärung. Der erste Teil ist **erledigt** (siehe
[`done/preserve-restore-in-die-treiber.md`](../done/preserve-restore-in-die-treiber.md)).

**2026-09-13:** zwei der drei Objekte sind umgezogen — sie brauchten gar keine
Naht (siehe „Zwei davon waren nur am falschen Ort"). Offen ist der dritte, und
der ist größer, als dieses Ticket ihn beschrieb.

## Befund

`hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/` führt 38 Dateien,
zehn davon tragen einen Dialektnamen. Sie zerfallen in zwei Gruppen, und nur
eine davon ist ein Befund:

**Port-Vokabular — richtig dort.** `MysqlServerVersion`,
`OracleServerVersion`, `SqliteLiveCatalog`, `SqliteCastPreflight`,
`MysqlSequenceCanonicity` stehen **in** einer Port-Signatur (`SchemaReadResult`,
`DdlGenerationOptions`). Der Adapter legt den Wert hinein, die
Anwendungsschicht liest ihn heraus. `.a-check.yml` erlaubt
`application → ports`, aber nicht `application → adapters`; im Adapter
definiert, wäre die Abhängigkeit umgedreht.

**Dialektlogik — der Befund.** `MysqlSequenceSupportNaming`,
`MysqlCheckEnforcementResolver`, `MysqlSequenceCanonicityGate` kommen in
**keiner** Port-Signatur vor. Sie liegen dort, weil die Anwendungsschicht
selbst MySQL-spezifisch arbeitet:

| Stelle | Was sie tut |
|---|---|
| `MysqlSequenceCanonicityStage` | baut MySQL-Triggernamen, prüft das MySQL-Hilfstabellen-Format |
| `SequencePreserveStage` | prüft die MySQL-Namenslisten auf Nichtleere |
| `MigrationPreflightPlanner` | plant die MySQL-Sequenz-Kanonizität mit, samt Triggername |

## Zwei davon waren nur am falschen Ort (gemessen 2026-09-13)

Die Frage „gehören sie hinter einen Port?" stellte sich für zwei der drei gar
nicht. Gemessen an den **produktiven** Nutzern:

| Objekt | Nutzer |
| --- | --- |
| `MysqlSequenceCanonicityGate` | nur `driver-mysql` |
| `MysqlCheckEnforcementResolver` (+ `MysqlCheckEnforcementCapability`) | nur `driver-mysql` |
| `MysqlSequenceSupportNaming` | `driver-mysql` **und** `hexagon/application` |

Die ersten beiden kommen in keiner Port-Signatur vor und haben je **einen**
Konsumenten, der im MySQL-Treiber sitzt. Da ist nichts zu entkoppeln — sie
lagen schlicht im falschen Modul. Beide sind mitsamt ihren Tests nach
`driver-mysql` gewandert.

Nebenbei fiel dabei auf, was ein Umzug kostet, wenn ein Modul gut getestete
Klassen verliert: `ports-read` rutschte unter die 90-%-Coverage-Schranke.
Nicht wegen der Verschiebung an sich — sondern weil `DialectReadCapability
Lookup` aus dem Capability-Slice dort ungetestet stand und das bis dahin
niemandem auffiel. Die Spec ist nachgezogen.

## Was vom dritten übrig bleibt — und warum es größer ist

`MysqlSequenceSupportNaming` bleibt, weil `hexagon/application` es wirklich
braucht. Die Stellen sind aber nicht drei verstreute Zeilen, sondern **ein
Feature**: der MySQL-Sequenz-Kanonizitäts-Drift-Check. Gemessen an den
MySQL-Bezügen der Anwendungsschicht (122 Stellen in 12 Dateien) entfallen rund
zwei Drittel auf sein Vokabular — `MysqlSequenceCanonicityDeclaration` (27),
`…Stage` (10), `…Status` (8), `…SupportNaming` (7), `…Kind` (6), `…View` (6),
`…ProbeFn` (4).

Der Rest ist **kein** Befund: `MysqlNamedSequenceMode` (12) ist ein getippter
Wert, den der Anwender setzt und der durchgereicht wird (das
`sealed DdlDialectContext`-Muster), und `MysqlServerVersion` (11) ist
Port-Vokabular — beides ordnet dieses Ticket oben schon richtig ein.

**Die Naht ist damit benannt.** Zwei Stellen in der Anwendungsschicht bauen
dieselben Deklarationen über denselben Plan-Durchlauf, samt synthetisiertem
Triggernamen, und unterscheiden sich nur in Status und Ursache:
`MigrationPreflightPlanner.planMysqlSequenceCanonicity` (Status `NOT_RUN…`) und
`MysqlSequenceCanonicityStage.stampStageFailure` (Status
`PROBE_RUNTIME_ERROR`). „Welche Deklarationen erzeugt dieser Plan, und unter
welchem Status?" ist eine MySQL-Frage; die Anwendungsschicht sollte sie
**stellen**, nicht beantworten — genau die Form, die der Preserve-Restore-Fall
vorgemacht hat.

Das ist ein eigener Slice mit Scope, kein Aufräumen nebenbei. Zu klären
bleibt dabei die Benennung: ein Port `Mysql…Planner` auf einem generischen
Weg wäre die nullable-`mysql*`-Falle, vor der der Hexagon-Stil warnt — die
neutrale Form („welche Kanonizitäts-Deklarationen erzeugt dieser Plan?") hat
für vier Dialekte die ehrliche Antwort *keine*.

## Warum das zählt

Ein Modul, das Dialektwissen führt, ohne einen Dialekt zu besitzen, wächst mit
jedem neuen Dialekt: der sechste erweitert dieselben geteilten Klassen, statt
sein Wissen mitzubringen. Derselbe Mechanismus, der schon bei
`driver-common` und der View-Portabilität auffiel
([`done/view-query-transformer-per-dialect-rules.md`](../done/view-query-transformer-per-dialect-rules.md)).

Der Preserve-Restore-Fall zeigt auch die Behebung: nicht die Dateien
verschieben, sondern die **Naht** finden, an der die Anwendungsschicht das
Dialektwissen überhaupt braucht — und sie schließen. Danach wandern die
Dateien von selbst.

## Was zu klären ist

1. Ob die drei MySQL-Stellen (`MysqlSequenceCanonicityStage`,
   der MySQL-Teil von `SequencePreserveStage`, der MySQL-Zweig von
   `MigrationPreflightPlanner`) hinter einen Port gehören, den `driver-mysql`
   implementiert — analog zum Preserve-Restore.
2. ~~Ob `SqliteCastPreflightStage` einen MySQL-Triggernamen bauen darf.~~
   **Geprüft 2026-09-13 — kein eigener Befund; die Beobachtung war falsch
   notiert.** Der Verdacht kam vom Dateinamen. `SqliteCastPreflightStage.kt`
   führt **zwei** Objekte auf oberster Ebene, und das gleichnamige rührt
   keinen fremden Dialekt an: es prüft `dialect != SQLITE` und steigt sonst
   aus. Den MySQL-Triggernamen baut `MigrationPreflightPlanner`, der im selben
   File wohnt und mit dem SQLite-Stage nichts zu tun hat — er plant **drei**
   Vorprüfungen (SQLite-Cast, dialektübergreifendes CHECK,
   MySQL-Sequenz-Kanonizität), jede hinter ihrem eigenen Dialekt-Gate.

   Übrig bleibt eine Benennung, kein Defekt: ein allgemeiner Planer wohnt in
   einer Datei, die nach einer seiner drei Aufgaben heißt. Sein MySQL-Zweig
   ist derselbe Fall wie die Zeilen der Tabelle oben und gehört damit **in**
   Punkt 1, nicht davor.

   Mitgeprüft, weil genau dort ein Fehler wehgetan hätte: Planer und Renderer
   bilden den Triggernamen über **dieselbe** Quelle — `MysqlSequenceNaming
   .triggerName` im Treiber delegiert an `MysqlSequenceSupportNaming
   .triggerName`. Die beiden können nicht auseinanderlaufen, das Gate
   vergleicht also nie gegen einen Namen, den es nicht gibt. Was bleibt, ist
   ein toter Parameter (`sequenceName`, per `@Suppress("UNUSED_PARAMETER")`
   stillgestellt), den der Umbau mitnehmen kann.

## Berührte Stellen

- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/MysqlSequenceSupportNaming.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/MysqlSequenceCanonicityStage.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SequencePreserveStage.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SqliteCastPreflightStage.kt`
  — wegen `MigrationPreflightPlanner` im selben File, nicht wegen des SQLite-Stage
