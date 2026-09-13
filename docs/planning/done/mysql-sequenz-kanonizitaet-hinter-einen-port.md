# MySQL-Sequenz-Kanonizität hinter einen Port

> **Status:** Abgeschlossen (2026-09-13). Rest eines größeren Befunds
> ([`done/preserve-restore-in-die-treiber.md`](preserve-restore-in-die-treiber.md)
> war der erste Teil); zwei der drei ursprünglich benannten Objekte waren
> bereits vorher umgezogen, weil sie gar keine Naht brauchten (siehe unten).

## Ziel

`hexagon/application` baut heute an zwei Stellen dieselben MySQL-
Sequenz-Kanonizitäts-Deklarationen über denselben Plan-Durchlauf — inklusive
eines synthetisierten MySQL-Triggernamens — und unterscheidet sich nur in
Status und Ursache. Ein Port, den `driver-mysql` implementiert, soll die
Frage „welche Kanonizitäts-Deklarationen erzeugt dieser Plan, und unter
welchem Status?" beantworten, damit die Anwendungsschicht sie nur noch
**stellt**. Reine Struktur-Verschiebung, kein Verhaltens-Trigger.

## Befund, gemessen 2026-09-13

`hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/` führte 38 Dateien,
zehn mit Dialektnamen. Sie zerfielen in zwei Gruppen, nur eine ein Befund:

**Port-Vokabular — richtig dort.** `MysqlServerVersion`,
`OracleServerVersion`, `SqliteLiveCatalog`, `SqliteCastPreflight`,
`MysqlSequenceCanonicity` stehen **in** einer Port-Signatur
(`SchemaReadResult`, `DdlGenerationOptions`). Der Adapter legt den Wert
hinein, die Anwendungsschicht liest ihn heraus; `.a-check.yml` erlaubt
`application → ports`, aber nicht `application → adapters` — im Adapter
definiert, wäre die Abhängigkeit umgedreht.

**Dialektlogik — der Befund.** Drei Objekte kamen in keiner Port-Signatur
vor, weil die Anwendungsschicht selbst MySQL-spezifisch arbeitete.

## Zwei der drei waren nur am falschen Ort (erledigt)

Gemessen an den **produktiven** Nutzern:

| Objekt | Nutzer | Ergebnis |
| --- | --- | --- |
| `MysqlSequenceCanonicityGate` | nur `driver-mysql` | umgezogen |
| `MysqlCheckEnforcementResolver` (+ `MysqlCheckEnforcementCapability`) | nur `driver-mysql` | umgezogen |
| `MysqlSequenceSupportNaming` | `driver-mysql` **und** `hexagon/application` | bleibt — dieses Ticket |

Die ersten beiden kamen in keiner Port-Signatur vor und hatten je **einen**
Konsumenten, im MySQL-Treiber selbst. Da war nichts zu entkoppeln — sie lagen
schlicht im falschen Modul. Beide sind mitsamt ihren Tests nach
`driver-mysql` gewandert (Commit `b2b8b0ce9`).

Nebenbei aufgefallen, was ein Umzug kostet, wenn ein Modul gut getestete
Klassen verliert: `ports-read` rutschte unter die 90-%-Coverage-Schranke —
nicht wegen der Verschiebung, sondern weil `DialectReadCapabilityLookup` aus
einem vorigen Slice dort ungetestet stand und das niemandem auffiel. Die
Spec ist nachgezogen.

## Der dritte ist größer, als das ursprüngliche Ticket ihn beschrieb

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
Port-Vokabular.

**Die Naht ist benannt.** Zwei Stellen bauen dieselben Deklarationen über
denselben Plan-Durchlauf, samt Triggername, und unterscheiden sich nur in
Status und Ursache:

- `MigrationPreflightPlanner.planMysqlSequenceCanonicity` — Status
  `NOT_RUN_FILE_TARGET`/`NOT_RUN_POLICY`, aufgerufen, wenn die Live-Probe
  nicht läuft (Datei-Ziel oder Policy).
- `MysqlSequenceCanonicityStage.stampStageFailure` — Status
  `PROBE_RUNTIME_ERROR`, aufgerufen, wenn die Probe-Verkabelung vor jeder
  Probe scheitert.

„Welche Deklarationen erzeugt dieser Plan, und unter welchem Status?" ist
eine MySQL-Frage; die Anwendungsschicht sollte sie **stellen**, nicht
beantworten — genau die Form, die der Preserve-Restore-Fall vorgemacht hat.

## Vorabklärung: `SqliteCastPreflightStage` baut keinen MySQL-Triggernamen

Ein zweiter Verdacht aus dem Ursprungsticket, geprüft und entkräftet: der Name
`SqliteCastPreflightStage.kt` legt nahe, dort würde ein fremder Dialekt
angefasst. Die Datei führt **zwei** Objekte auf oberster Ebene — das
gleichnamige `SqliteCastPreflightStage` prüft `dialect != SQLITE` und steigt
sonst aus. Den MySQL-Triggernamen baut `MigrationPreflightPlanner`, der nur
zufällig im selben File wohnt und mit dem SQLite-Stage nichts zu tun hat: er
plant **drei** Vorprüfungen (SQLite-Cast, dialektübergreifendes CHECK,
MySQL-Sequenz-Kanonizität), jede hinter ihrem eigenen Dialekt-Gate. Übrig
bleibt eine Benennung, kein Defekt — sein MySQL-Zweig ist derselbe Fall wie
oben und gehört in dieses Ticket, nicht davor.

Mitgeprüft, weil genau dort ein Fehler wehgetan hätte: Planer und Renderer
bilden den Triggernamen über **dieselbe** Quelle —
`MysqlSequenceNaming.triggerName` im Treiber delegiert an
`MysqlSequenceSupportNaming.triggerName`. Die beiden können nicht
auseinanderlaufen. Übrig bleibt ein toter Parameter (`sequenceName` in
`stageFailureTrigger`, per `@Suppress("UNUSED_PARAMETER")` stillgestellt),
den der Umbau mitnehmen kann.

## Offene Designfrage

Ein Port `Mysql…Planner` auf einem generischen Weg wäre die
nullable-`mysql*`-Falle, vor der der Hexagon-Stil warnt: `sealed
DdlDialectContext` statt nullable Dialektfelder auf generischen Ports. Die
neutrale Form („welche Kanonizitäts-Deklarationen erzeugt dieser
Plan?") hat für vier der fünf Dialekte die ehrliche Antwort *keine* — die
Frage ist strukturell MySQL-spezifisch, nicht neutral mit einem
MySQL-Sonderfall.

Zwei Kandidaten, zu entscheiden vor dem Bau:

1. **Ein dialektspezifischer Port** (kein `DatabaseDriver`-Zusatz, sondern ein
   eigenes Interface `MysqlSequenceCanonicityPlanner`, das nur `driver-mysql`
   implementiert und nur an den beiden MySQL-Aufrufstellen benutzt wird —
   analog zur Rolle, die `DialectReadCapabilityProvider` für die
   Capability-Frage spielt, nur ohne den Anspruch, für alle fünf Dialekte zu
   gelten).
2. **Eine der beiden bestehenden Capability-Provider erweitern**
   (`DialectReadCapabilityProvider`) um eine optionale, dialektspezifische
   Methode — nur von MySQL sinnvoll beantwortet, die anderen vier liefern
   einen Leerwert. Widerspräche aber der gerade erst gebauten Prämisse dieses
   Ports: er bündelt Fragen, die **jeder** Dialekt beantwortet.

Tendenz: Kandidat 1. Er hält den Vertrag ehrlich (nur MySQL implementiert
ihn, kein Dialekt liefert einen fingierten Leerwert) und wiederholt das
Preserve-Restore-Muster — ein Port pro tatsächlich geteilter Frage, nicht ein
Anbau an einen bestehenden.

**Gebaut wurde keiner der beiden — ein dritter, der beim Schreiben
eindeutig war.** Die Vorabklärung oben hatte es schon benannt: der
SQLite-Zwilling (`SqliteCastPreflightStage`/`MigrationPreflightPlanner`)
löst genau diese Frage bereits über eine Typalias-Funktionsreferenz
(`SqliteCastPreflightPlannerFn`), die das treibende CLI auf ein reines
`object` in `driver-sqlite` bindet (`SqliteCastPreflightPlanner.plan`,
keine Schnittstelle in `ports-read`) — derselbe Mechanismus, mit dem der
Live-Probe-Zweig (`MysqlSequenceCanonicityProbeFn`) hier ohnehin schon
funktioniert. Kein neuer Vertrag in `ports-read`, kein
Kandidat-2-Widerspruch zu `DialectReadCapabilityProvider`: siehe
„Gebaut" unten.

## Scope-Skizze

1. **Designfrage entscheiden** (siehe oben) — Eigner oder im Bau selbst,
   falls Kandidat 1 sich beim Schreiben als eindeutig richtig bestätigt.
2. **Port + MySQL-Implementierung.** Eine Methode, die Plan + Status nimmt
   und die Liste der `MysqlSequenceCanonicityDeclaration` liefert — beide
   heutigen Formen (`NOT_RUN…`, `PROBE_RUNTIME_ERROR`) über denselben
   Status-Parameter, den die Aufrufer schon berechnen.
3. **Beide Konsumstellen umstellen**:
   `MigrationPreflightPlanner.planMysqlSequenceCanonicity` und
   `MysqlSequenceCanonicityStage.stampStageFailure` rufen den Port statt die
   Deklarationen selbst zu bauen.
4. **`SqliteCastPreflightStage.kt`s MySQL-Zweig** (in
   `MigrationPreflightPlanner`) im selben Zug mitnehmen — es ist derselbe
   Fall.
5. **Toten Parameter entfernen** (`sequenceName` in `stageFailureTrigger`).
6. **`MysqlSequenceSupportNaming` wandert** nach `driver-mysql`, sobald
   `hexagon/application` es nicht mehr importiert — analog zum
   Preserve-Restore-Fall: „danach wandern die Dateien von selbst."
7. **Vollregression**: `make docker-check` ohne `MODULES=` (geteilte
   Signatur), `make a-check`, `make solid-suppression-gate`.

## Akzeptanzkriterien

- `hexagon/application` importiert `MysqlSequenceSupportNaming` nicht mehr.
- Beide Konsumstellen liefern für denselben Plan dieselben Deklarationen wie
  vor dem Umbau (Regressionstest, keine Verhaltensänderung).
- `hexagon/ports-read` führt keine MySQL-Dialektlogik mehr, nur noch
  Port-Vokabular.
- `a-check`: 0 Befunde (die Kante `application → adapters` bleibt
  geschlossen — der neue Port liegt in `ports`, nicht `driver-mysql` wird
  direkt importiert).

## Berührte Stellen

- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceSupportNaming.kt`
  (Herkunft: `hexagon/ports-read`)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceCanonicityPlanner.kt`
  (neu)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/MysqlSequenceCanonicityStage.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SequencePreserveStage.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SqliteCastPreflightStage.kt`
  (wegen `MigrationPreflightPlanner` im selben File, nicht wegen des
  SQLite-Stage)

## Warum das zählt

Ein Modul, das Dialektwissen führt, ohne einen Dialekt zu besitzen, wächst mit
jedem neuen Dialekt: der sechste erweitert dieselben geteilten Klassen, statt
sein Wissen mitzubringen — derselbe Mechanismus, der schon bei `driver-common`
und der View-Portabilität auffiel
([`view-query-transformer-per-dialect-rules.md`](view-query-transformer-per-dialect-rules.md)).
Der Preserve-Restore-Fall zeigt die Behebung: nicht die Dateien verschieben,
sondern die Naht finden, an der die Anwendungsschicht das Dialektwissen
überhaupt braucht — und sie schließen.

## Gebaut (2026-09-13)

**Die Naht:** `MysqlSequenceCanonicityPlanner.plan(diff, status, sqlHash,
problem)`, neu in `driver-mysql`. Eine Funktion, die den Plan-Durchlauf aus
Arbeitspaket 2 einmal macht — Sequenz-Ops (`Create`/`Alter`/`Drop`/
`RenameSequence`) tragen eine `SEQUENCE_ROW`-Deklaration, Spalten-Ops
(`AddColumn`/`AlterColumnDefault`) mit `SequenceNextVal`-Default eine
`SUPPORT_TRIGGER`-Deklaration mit dem kanonischen Triggernamen. Gebunden
über dieselbe Typalias-Funktionsreferenz wie der Live-Probe-Zweig
(`MysqlSequenceCanonicityPlannerFn`, `hexagon/application`), vom treibenden
CLI auf `MysqlSequenceCanonicityProbeRunner::planNotRun` verdrahtet — kein
neues Interface in `ports-read`.

**Beide Konsumstellen umgestellt:**

- `MigrationPreflightPlanner.planMysqlSequenceCanonicity` ruft den Port statt
  selbst zu bauen; die verbleibende Eigenlogik ist nur noch die
  Status-Wahl (`NOT_RUN_POLICY`/`NOT_RUN_FILE_TARGET`) und das
  Dialekt-Gate.
- `MysqlSequenceCanonicityStage.run` baute die `PROBE_RUNTIME_ERROR`-
  Deklarationen bislang über eine **eigene zweite** Kopie desselben
  Plan-Durchlaufs (`stampStageFailure`). Gemessen an
  [`CheckPreflightStage`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/CheckPreflightStage.kt),
  das für dieselbe Situation längst `preflightPlan.checkPreflights.map
  { it.copy(status = PROBE_RUNTIME_ERROR, …) }` schreibt: die Stage bekommt
  jetzt den bereits geplanten `MigrationPreflightPlan` als Parameter und
  kopiert dessen Deklarationen um, statt ein drittes Mal über den Plan zu
  laufen. Der `hasSequenceRelatedOps`-Vorablauf entfällt ebenso — er prüfte
  dieselbe Bedingung, die `preflightPlan.mysqlSequenceCanonicity.isEmpty()`
  jetzt beantwortet.

**`SqliteCastPreflightStage.kt`s MySQL-Zweig** (Arbeitspaket 4) ist damit
miterledigt — er lag im selben `MigrationPreflightPlanner`.

**Toter Parameter entfernt** (Arbeitspaket 5): `sequenceName` in
`stageFailureTrigger`/`triggerNotRun` war nie gelesen (der Triggername kommt
aus Tabellen-/Spaltenname, nicht aus dem Sequenznamen) — mit dem Umbau
verschwunden statt weiter durchgereicht.

**`MysqlSequenceSupportNaming` ist nach `driver-mysql` gewandert**
(Arbeitspaket 6), reine Paketverschiebung (`dev.dmigrate.driver` →
`dev.dmigrate.driver.mysql`). `MysqlSequenceNaming` (die alte
Cross-Modul-Fassade) bleibt als dünner Delegat stehen — ihr Zweck hat sich
geändert (kein Modul mehr dazwischen, nur noch API-Stabilität für ihre
bestehenden Aufrufer), ihre Form nicht; das ist eine reine
Struktur-Verschiebung, kein Anlass, sie mit dem Original zu verschmelzen.

**Ein vierter Aufrufer, den das Ticket nicht benannt hatte:**
`SequencePreserveStage.configInvalidIfMysqlNeedsIt` prüfte vor jedem
Preserve-Lauf, ob `MysqlSequenceSupportNaming.SUPPORTED_MANAGED_BY` /
`.SUPPORTED_FORMAT_VERSIONS` leer sind — beide sind hartkodierte,
nicht-leere Literale, die Prüfung konnte nie auslösen. Selbst wenn doch: der
identische Fall fängt sich bereits zur Laufzeit in
`MysqlSequenceCurrentValueProbe.probeCurrentValue`, die pro Zeile gegen
dieselben Mengen prüft und einen eigenen Diagnose-Pfad hat. Eine tote,
redundante Prüfung, die nur deshalb bestand, weil `hexagon/application` den
Treiber-Konstanten direkt importierte — mit dem Umzug entfernt statt hinter
eine weitere Funktionsreferenz gehängt.

**Nebenbefund: `ports-read` unterschritt kurzzeitig 89,78 % Zeilenabdeckung**
nach dem Entfernen von `MysqlSequenceSupportNaming` (gut getestet, 100 %) —
derselbe Mechanismus wie beim Preserve-Restore-Fall, nur diesmal an
`DdlDialectContext.Postgres`/`.MsSql`/`.Oracle`: drei sealed Varianten ohne
eigene Konstruktionsstelle in `ports-read`s eigener Testsuite (nur `.MySql`
war dort schon getestet). Drei reale Tests in `PortsReadTest.kt`
(`DdlGenerationOptions carries … via DdlDialectContext.…`), demselben Muster
wie die bestehende MySql-Variante, schließen die Lücke.

## Akzeptanzkriterien — erfüllt

- `hexagon/application` importiert `MysqlSequenceSupportNaming` nicht mehr. ✅
- Beide Konsumstellen liefern für denselben Plan dieselben Deklarationen wie
  vor dem Umbau — Regressionstests in `MysqlSequenceCanonicityPlannerTest.kt`
  (`driver-mysql`, neu) pinnen den Plan-Durchlauf selbst;
  `MigrationPreflightPlannerTest.kt`/`MysqlSequenceCanonicityStageTest.kt`
  (`hexagon/application`) pinnen die Kleberlogik mit Fake-Planern. ✅
- `hexagon/ports-read` führt keine MySQL-Dialektlogik mehr, nur noch
  Port-Vokabular (`MysqlSequenceCanonicityDeclaration`/`Kind`/`Status`). ✅
- Kein neues Interface in `ports-read`; die Kante `application → adapters`
  bleibt geschlossen (Funktionsreferenz, kein direkter Treiber-Import). ✅

Grün: `driver-mysql`, `hexagon/application`, `adapters/driving/cli`,
`hexagon/ports-read` je einzeln über `make docker-check`, danach einmal ohne
`MODULES=` (Modulgrenze verschoben), `make solid-suppression-gate`,
`make docs-check`.
