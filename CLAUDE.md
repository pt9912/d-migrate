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

## Konventionen

- **Detekt-Größenbefunde** (`LargeClass`, `TooManyFunctions`,
  `CyclomaticComplexMethod`) werden durch echte Aufteilung gelöst, nie durch
  `@Suppress`.
- **Strukturelle Umbauten** über viele Aufrufstellen (Signatur, Rename)
  laufen über `make ast-grep`, nicht über `grep`/`perl`.
- **ADRs** in `docs/adr/` sind durchgehend deutsch; nur das
  YAML-Frontmatter bleibt englisch.
