# Arbeitshinweise für Claude Code

Betriebswissen über dieses Repo, das sich weder aus dem Code noch aus der
Doku ergibt. Verbindliche Doku-, Spec-, ADR- und Planning-Regeln stehen
**nicht** hier, sondern extern im Regelwerk (`pt9912/ai-harness-course`).

## Bauen und Testen

Gebaut und getestet wird **im Container**, nicht lokal per `./gradlew`. Die
Einstiegspunkte stehen in `make help`; die wichtigsten:

```
make docker-check MODULES=":adapters:driven:driver-mssql"   # :check je Modul (Test + Detekt + Kover)
make docker-test  MODULES=":hexagon:core"                   # nur :test
make integration  INTEGRATION_TASKS=":test:integration-mssql:test"
```

Ohne `MODULES` läuft der Task über das ganze Repo — deutlich langsamer.

`MODULES=` lässt die `test/integration-*`-Module **aus**, auch die
Kompilierung. Wer eine geteilte Signatur im Hexagon ändert (Modell, Port,
Fingerprint), prüft deshalb einmal ohne `MODULES` — sonst bricht der Bau erst
in CI, an einer Aufrufstelle, die lokal nie gebaut wurde.

## Zwei Gates, die der Build nicht abdeckt

- **`make docs-check`** prüft Doku, Specs, ADRs und Planning (verlinkte
  Codepfade existieren, ADR-IDs stimmen). Läuft in CI, aber **nicht** im
  Docker-Build. Bei jeder Änderung an `docs/`, `spec/` oder `docs/adr/`
  vorher lokal fahren.
- **`-PintegrationTests`** schaltet die `onlyIf`-Gates der
  `test/integration-*`-Module frei. Ohne die Property überspringt Gradle die
  Test-Tasks und meldet **trotzdem** `BUILD SUCCESSFUL` — ein grüner Lauf, der
  nichts geprüft hat. `make integration` ergänzt sie automatisch.

## Grün heißt nicht geprüft

`BUILD SUCCESSFUL` belegt nur, dass nichts fehlgeschlagen ist — nicht, dass
die neuen Tests gelaufen sind. Gradle meldet Erfolg auch, wenn ein Task
`UP-TO-DATE` war oder ein `--tests`-Filter nichts traf. Wer eine neue Spec
hinzufügt, prüft einmal, dass sie wirklich läuft: eine absichtlich
fehlschlagende Zusicherung einbauen, den Fehlschlag sehen, sie entfernen.

Aus demselben Grund den Exit-Code von `docker build` **direkt** prüfen und
nicht über ein nachgelagertes `echo` — das maskiert ein `BUILD FAILED`. Bei
langen Läufen die Ausgabe in eine Datei lenken und greppen; `tail -n` schneidet
den eigentlichen Fehler ab.

## Images

`make docker-build` und `make docker-oci-build` bauen mit `--target runtime`.
Ein blankes `docker build .` baut dagegen die **letzte** Stage des
`Dockerfile` (`ast-grep`) und liefert kein lauffähiges Runtime-Image.

## Was wo steht

- **`spec/` ist das Zielbild** und normativ. `spec/ddl-generation-rules.md` ist
  die Autorität für Render-Regeln je Dialekt — wer eine Regel ändert, ändert sie
  dort mit, nicht erst hinterher. Dass die Spec etwas beschreibt, das der Code
  noch nicht kann, ist kein Befund; das ist ihre Aufgabe.
- **`docs/user/` beschreibt den Ist-Zustand.** Dort darf nur stehen, was heute
  wirkt — ein Handbuch, das eine geplante Faehigkeit beschreibt, ist falsch, kein
  Vorgriff. Das **Anwenderhandbuch** ist dabei aufgabenorientiert („Brauchen Sie
  X → tun Sie Y"); Feld- und Optionsreferenzen gehoeren in seine Anhaenge, das
  **Administrationshandbuch** oder die **API-Referenz**.
  Faustregel beim Bauen: aendert sich, was ein Anwender tun oder erwarten kann,
  aendert sich hier etwas mit.
- **`docs/planning/` ist deskriptiv**, nicht normativ. Eine Entscheidung dort
  festzuhalten ersetzt den Spec-Eintrag nicht.

Was das Gate prüft, steht in `.d-check.yml`. Sein `matrix`-Modul mechanisiert
die Referenz-**Richtung**: Spec-Straten verweisen nie abwärts, weder auf ADRs
noch auf Pläne, und die Rangordnung Vertrag › Technik › Sicht ist dort als
`order` hinterlegt. Ein Link aus `spec/` auf einen ADR fällt also auf.

Was es **nicht** prüfen kann, ist der Inhalt: eine Spec-Zeile, die das Gegenteil
des Codes behauptet, ist strukturell einwandfrei und fällt niemandem auf. Genau
das passierte, als der MSSQL-Generate-Pfad Partitionierung zu rendern begann und
`ddl-generation-rules.md` weiter „wird nicht gerendert" sagte.

## Goldens

Zwei verschiedene Sorten, und nur eine hat ein Make-Target:

- **JSON-Schema-Snapshots** (`src/test/resources/golden/**`, MCP-Tool-Schemata)
  regeneriert `make golden-update` über die gleichnamige Docker-Stage.
- **DDL-Goldens** (`adapters/driven/formats/src/test/resources/fixtures/ddl/`)
  regeneriert es **nicht**. Sie entstehen über die CLI (`schema generate` gegen
  die Fixture) und werden von `DdlGoldenMasterTest` verglichen. Ändert sich das
  Rendern eines Dialekts, schlagen sie fehl — das ist der Zweck.

## Konventionen

- **Detekt-Größenbefunde** werden durch echte Aufteilung gelöst, nie durch
  `@Suppress`. Welche Regeln dazuzählen, steht **nicht hier**, sondern in
  `scripts/solid-suppression-gate.sh` (Liste `solid_rules`) — das ist die
  Quelle, dieser Absatz nur der Hinweis darauf. Sie ist breiter, als man im
  Kopf hat: neben `LargeClass`, `TooManyFunctions` und
  `CyclomaticComplexMethod` fallen auch `LongParameterList`, `LongMethod`,
  `ComplexMethod` und `NestedBlockDepth` darunter.

  Diese Aufzählung stand hier einmal unvollständig, und das hatte Folgen: was
  sie nicht nannte, wurde reflexhaft unterdrückt — `LongParameterList` ist so
  von null auf ein Dutzend Stellen gewachsen, während die drei genannten
  Regeln zurückgingen.

  **`make solid-suppression-gate` läuft vor jedem Commit**, nicht erst vor dem
  nächsten `@Suppress`. Ein Gate, das man nur bei Verdacht fährt, ist genau das
  Gate, das den Zuwachs oben nicht verhindert hat.
- **Strukturelle Umbauten** über viele Aufrufstellen (Signatur, Rename)
  laufen über `make ast-grep`, nicht über `grep`/`perl`.
- **ADRs** in `docs/adr/` sind durchgehend deutsch; nur das
  YAML-Frontmatter bleibt englisch.
