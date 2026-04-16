# Implementierungsplan: Phase A - CLI-Vertrag fuer Sprache und Resume

> **Milestone**: 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI
> **Phase**: A (CLI-Vertrag fuer Sprache und Resume)
> **Status**: Planned (2026-04-16)
> **Referenz**: `docs/implementation-plan-0.9.0.md` Abschnitt 1 bis 4,
> Abschnitt 5.1, Abschnitt 6.1, Abschnitt 8.4 und 8.5; `docs/roadmap.md`
> Milestone 0.9.0 und 0.9.5; `docs/cli-spec.md` globale Flags sowie
> `data export`/`data import`; `docs/guide.md` globale Optionen;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/I18nSettingsResolver.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/MessageResolver.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`

---

## 1. Ziel

Phase A fixiert fuer 0.9.0 den sichtbaren Nutzervertrag an der CLI-Kante,
bevor Checkpoint-Store, Resume-Manifest und Streaming-Resume technisch
umgesetzt werden.

Der Teilplan beantwortet bewusst zuerst die Vertragsfragen:

- wie `--lang` in 0.9.0 produktiv freigeschaltet wird
- welche Prioritaet `--lang` gegenueber Env/Config/System-Locale hat
- welche Werte fuer explizites `--lang` akzeptiert werden
- wie Resume fuer `data export` und `data import` an der CLI sichtbar wird
- welche Exit-Codes und Fehlermeldungsklassen fuer ungueltige
  Sprach-/Resume-Aufrufe gelten
- welche 0.9.0-Einschraenkungen in CLI-Spec, Guide und gegebenenfalls
  Roadmap explizit nachgezogen werden muessen
- wie sichtbare Resume-Flags zu der bereits dokumentierten
  `pipeline.checkpoint.directory`-Konfiguration stehen

Phase A liefert damit nicht den Checkpoint-Unterbau selbst, sondern den
verbindlichen CLI-Rahmen, auf den die spaeteren Phasen B bis E aufsetzen.

Nach Phase A soll klar und widerspruchsfrei dokumentiert sein:

- `--lang` ist in 0.9.0 ein aktiver CLI-Override
- explizites `--lang` hat einen strengeren Validierungsvertrag als die
  bestehenden Env-/Config-/System-Locale-Pfade
- Resume ist ein expliziter Nutzerpfad, kein stilles Auto-Resume
- Export und Import besitzen einen klar benannten Resume-Einstieg
- nicht resume-faehige Pfade und inkompatible Wiederaufnahmen werden mit
  definierten lokalen Fehlern abgewiesen

---

## 2. Ausgangslage

Aktueller Stand im Repo:

- `Main.kt` deklariert `--lang`, lehnt jede Nutzung in 0.8.0 aber bewusst
  mit Exit `7` ab und verweist auf den 0.9.0-Vertrag.
- `I18nSettingsResolver` loest Locale-Werte bereits ueber
  `D_MIGRATE_LANG`, `LC_ALL`, `LANG`, `i18n.default_locale` und
  System-Locale auf.
- `MessageResolver` faellt fuer unsupported locales auf das englische
  Root-Bundle zurueck.
- `docs/cli-spec.md` und `docs/guide.md` dokumentieren `--lang` derzeit
  konsistent als fuer 0.9.0 reserviert.
- `DataExportCommand` und `DataImportCommand` besitzen aktuell keinerlei
  Resume-Flags.
- `DataExportRequest` und `DataImportRequest` transportieren entsprechend
  noch keine Resume- oder Checkpoint-Referenzen.
- `docs/roadmap.md` beschreibt 0.9.0 allgemein als unterbrechbare und
  wiederaufsetzbare Export-/Import-Operationen, ohne den Scope bereits auf
  konkrete Ein-/Ausgabepfade oder Flag-Namen einzuengen.

Konsequenz:

- fuer `--lang` existiert bereits die technische Basis, aber der sichtbare
  0.9.0-Nutzervertrag fehlt noch
- fuer Resume fehlt sowohl die sichtbare CLI-Oberflaeche als auch der
  technische Unterbau
- wenn 0.9.0 den Resume-Scope enger als die allgemeine Roadmap formulieren
  will, muss diese Einschraenkung in Phase A explizit und nicht nur implizit
  ueber spaetere Codepfade festgezogen werden

---

## 3. Scope fuer Phase A

### 3.1 In Scope

- Finalisierung des Root-CLI-Vertrags fuer `--lang`
- Festlegung der Prioritaetskette fuer die effektive Locale in 0.9.0
- Festlegung des Validierungsvertrags fuer explizites `--lang`
- Abgrenzung zwischen:
  - explizitem `--lang`
  - Env-/Config-/System-Locale-Aufloesung
  - ResourceBundle-Fallback
- Festlegung des sichtbaren Resume-Einstiegs fuer:
  - `d-migrate data export`
  - `d-migrate data import`
- Festlegung der minimalen Resume-Flag-Oberflaeche:
  - Resume-Referenz
  - optionales Checkpoint-Verzeichnis
- Definition der lokalen Fehler- und Exit-Code-Klassen fuer:
  - ungueltige Sprachwerte
  - ungueltige Resume-Flag-Kombinationen
  - nicht unterstuetzte Resume-Pfade
  - unlesbare oder semantisch inkompatible Checkpoint-Referenzen
- Synchronisierung der normativen CLI-Dokumente auf diesen Vertrag
- Abgleich zwischen sichtbarem `--checkpoint-dir`-Vertrag und
  `pipeline.checkpoint.directory` in der Config-Spezifikation

### 3.2 Bewusst nicht Teil von Phase A

- Implementierung des Checkpoint-Stores
- Persistenzformat des Resume-Manifests im Detail
- Resume-Logik im `StreamingExporter` oder `StreamingImporter`
- Erweiterung von `PipelineConfig`
- Signalbehandlung und Checkpoint-Schreiblogik bei SIGINT/SIGTERM
- vollstaendige Anwenderdokumentation oder Pilot-QA
- Resume fuer `data transfer`

Praezisierung:

Phase A fixiert zuerst "wie sieht der Vertrag aus?", nicht
"wie wird der Resume-Unterbau technisch implementiert?".

---

## 4. Leitentscheidungen fuer Phase A

### 4.1 `--lang` wird in 0.9.0 zu einem echten Root-CLI-Override

Phase A fixiert:

- `--lang` ist ab 0.9.0 kein reservierter Platzhalter mehr
- das Flag wirkt im Root-CLI und bestimmt die menschenlesbare Sprache des
  gesamten Laufes
- die Prioritaetskette lautet:
  1. `--lang`
  2. `D_MIGRATE_LANG`
  3. `LC_ALL`
  4. `LANG`
  5. `i18n.default_locale`
  6. System-Locale
  7. Fallback `en`

Verbindliche Folge:

- Helptext in `Main.kt`, `docs/cli-spec.md` und `docs/guide.md` wird von
  "reserved for 0.9.0" auf einen aktiven Override-Vertrag umgestellt
- strukturierte JSON-/YAML-Ausgaben bleiben dabei weiterhin sprachstabil

### 4.2 Explizites `--lang` ist strenger als Env-/Config-/System-Locale

Phase A fixiert die bereits aus dem 0.8.0-Unterbau ableitbare, aber fuer
0.9.0 explizit zu formulierende Trennung:

- `--lang` akzeptiert nur offiziell unterstuetzte Produktspracheingaben
  fuer 0.9.0, mindestens:
  - `de`
  - `en`
  - kanonisierbare Varianten wie `de-DE`, `de_DE`, `en-US`, `en_US`
- ein explizit gesetztes, aber nicht unterstuetztes `--lang` ist ein lokaler
  CLI-Fehler
- Env-/Config-/System-Locale-Pfade behalten den 0.8.0-Vertrag:
  syntaktisch gueltige generische Locales duerfen weiter aufgeloest werden,
  und fehlende spezifische Bundles fallen ueber `MessageResolver` auf das
  Root-/Englisch-Bundle zurueck

Nicht zulaessig ist:

- den bestehenden allgemeinen Locale-/Bundle-Fallback still zu einem
  globalen Hard-Error umzudeuten
- `--lang` still auf `en` oder `de` zu mappen, wenn der Wert fachlich
  unbekannt ist

### 4.3 Resume ist subcommand-lokal und explizit

Phase A fixiert:

- Resume wird nicht als globales Root-Flag eingefuehrt
- Resume gehoert in die Oberflaeche von `data export` und `data import`
- die Arbeitsannahme aus dem Masterplan wird zum CLI-Zielvertrag:
  - `--resume <checkpoint-id|path>`
  - `--checkpoint-dir <path>`

Verbindliche Folge:

- Export- und Import-Command helfen spaeter denselben mentalen Resume-Pfad
  zu etablieren
- eine Wiederaufnahme ist immer ein bewusst erklaerter CLI-Aufruf und kein
  stilles "wenn da zufaellig ein Checkpoint liegt"

### 4.4 Dateibasierte Resume-Einschraenkung muss explizit dokumentiert werden

Falls 0.9.0 beim im Masterplan formulierten dateibasierten Beta-Zuschnitt
bleibt, fixiert Phase A:

- `--resume` wird fuer dateibasierte Export-/Import-Pfade dokumentiert
- `stdout`-Export und `stdin`-Import bleiben regulaere Basisfunktionen,
  aber nicht Teil des Resume-Vertrags

Verbindliche Folge:

- diese Einschraenkung darf nicht nur in einem Teilplan versteckt stehen
- sie muss in `docs/cli-spec.md` und `docs/guide.md` explizit auftauchen
- wenn die Roadmap weiterhin allgemeiner formuliert bleibt, muss zumindest
  ein klarer 0.9.0-Hinweis auf die eingeschraenkte Beta-Oberflaeche
  nachgezogen werden

### 4.5 Resume-Fehler bleiben in bestehenden lokalen Fehlerklassen

Phase A fuehrt keine neue Exit-Code-Familie ein.

Arbeitsvertrag fuer 0.9.0:

- Exit `2` fuer lokale CLI-Validierungsfehler:
  - ungueltiges oder unsupported `--lang`
  - unzulaessige Resume-Flag-Kombinationen
  - Resume auf nicht unterstuetzten Pfaden wie `stdout` oder `stdin`
- Exit `7` fuer lokale Checkpoint-/Konfigurationsfehler:
  - Checkpoint-Referenz nicht lesbar
  - Checkpoint-Datei/Manifest ungueltig

Praezisierung fuer semantische Resume-Mismatches:

- eine syntaktisch lesbare, aber fachlich inkompatible Resume-Referenz darf
  nicht pauschal auf Exit `7` gezogen werden
- fuer `data import` muss dieser Fall mit dem bestehenden Preflight-Modell
  abgestimmt werden und bleibt daher kandidat fuer Exit `3`
- fuer `data export` ist das Mapping in Phase A explizit festzuziehen und
  in `cli-spec`/Runner-Vertrag konsistent zu dokumentieren

Damit bleibt der neue Vertrag anschlussfaehig an die bestehende CLI-Matrix
statt neben ihr eine weitere Sonderklasse aufzubauen.

### 4.6 Phase A beschreibt bewusst nur den CLI-Vertrag, nicht schon das Manifest

Phase A fixiert nur die von aussen sichtbaren Resume-Garantien:

- Resume hat eine explizite Referenz
- Resume kann ein Checkpoint-Verzeichnis nutzen
- inkompatible Wiederaufnahme wird klar abgelehnt

Nicht Bestandteil von Phase A ist dagegen:

- ob eine Resume-Referenz intern UUID, Dateiname oder beides ist
- wie genau ein Checkpoint-Manifest serialisiert wird
- an welcher Chunk-Grenze oder in welchem Adapter ein Checkpoint geschrieben
  wird

Diese Details folgen erst in Phase B und den technischen Resume-Phasen C/D.

---

## 5. Geplante Arbeitspakete

### 5.1 A1 - Sprachvertrag im Root-CLI finalisieren

- Helptext und Nutzervertrag fuer `--lang` von 0.8.0-Placeholder auf
  aktiven Override umstellen
- akzeptierte 0.9.0-Sprachwerte und Kanonisierung festlegen
- Fehlerverhalten fuer explizit ungueltiges `--lang` definieren
- Trennung zwischen explizitem `--lang`-Fehler und allgemeinem
  Bundle-Fallback festschreiben

### 5.2 A2 - Resume-Oberflaeche fuer Export und Import definieren

- finale Resume-Flag-Namen fuer Export/Import festlegen
- Resume als subcommand-lokalen Einstieg definieren
- nicht unterstuetzte Resume-Pfade explizit benennen
- notwendige CLI-Deltas in Command-Optionen und Request-DTOs benennen

### 5.3 A3 - Exit-Code- und Fehlermeldungsvertrag festziehen

- Mapping auf bestehende lokale Fehlerklassen (`2` vs. `7`) festlegen
- klare Fehlermeldungen fuer:
  - unsupported `--lang`
  - fehlende/unlesbare Resume-Referenz
  - Resume auf nicht unterstuetzten Pfaden
  - semantisch inkompatiblen Checkpoint

### 5.4 A4 - Normative Doku synchronisieren

- `docs/cli-spec.md` auf aktive `--lang`-Semantik umstellen
- Export-/Import-Optionen um Resume-Vertrag erweitern
- `docs/guide.md` auf die neuen globalen und command-lokalen Optionen
  angleichen
- `docs/connection-config-spec.md` auf den sichtbaren Resume-Vertrag
  abstimmen, insbesondere auf das Verhaeltnis zwischen
  `pipeline.checkpoint.directory` und `--checkpoint-dir`
- falls der dateibasierte Resume-Zuschnitt beibehalten wird:
  die Einschraenkung in den normativen Dokumenten explizit nachziehen

---

## 6. Teststrategie fuer Phase A

Phase A braucht vor allem Vertrags- und Validierungstests, noch keine
vollstaendige Resume-Runtime.

Mindestens noetige Tests:

- Root-CLI:
  - `--lang de` und `--lang en` werden akzeptiert
  - kanonisierbare Varianten wie `de-DE` werden korrekt normalisiert
  - unsupported `--lang` fuehrt zu lokalem CLI-Fehler
- I18n-Abgrenzung:
  - Env-/Config-/System-Locale-Pfade behalten Fallback auf Root-Bundle
  - explizites `--lang` aendert diesen generellen Fallback nicht rueckwirkend
- Export-/Import-CLI:
  - neue Resume-Flags werden korrekt geparst
  - unzulaessige Resume-Kombinationen fuehren zu Exit `2`
  - unlesbare oder ungueltige Resume-Referenzen fuehren zu Exit `7`
- Doku-/Spec-Review:
  - `cli-spec`, `guide`, `connection-config-spec` und Masterplan verwenden
    dieselben Flag-Namen, Prioritaeten und Fehlermodellgrenzen

Explizit nicht Ziel von Phase A:

- Nachweis echter Wiederaufnahme ueber Streaming-Chunks
- Persistenztests fuer Checkpoint-Manifeste
- Signal-/Interrupt-Tests

---

## 7. Datei- und Codebasis-Betroffenheit

Sicher betroffen:

- `docs/implementation-plan-0.9.0.md`
- `docs/ImpPlan-0.9.0-A.md`
- `docs/cli-spec.md`
- `docs/connection-config-spec.md`
- `docs/guide.md`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`

Mit hoher Wahrscheinlichkeit betroffen:

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliI18nContextTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/config/I18nSettingsResolverTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt`

Optional betroffen:

- `docs/roadmap.md`, falls der dateibasierte Resume-Zuschnitt dort explizit
  nachgezogen werden soll

---

## 8. Risiken und offene Punkte

### 8.1 `--lang` darf den 0.8.0-Fallback nicht unbeabsichtigt zerbrechen

Der aktuelle Unterbau erlaubt generische Locale-Aufloesung und Root-Bundle-
Fallback fuer unsupported locales ausserhalb des expliziten `--lang`-Pfads.
Phase A muss sauber trennen, wo 0.9.0 bewusst strenger wird und wo der
bestehende Fallback-Vertrag bestehen bleibt.

### 8.2 Dateibasierter Resume-Scope braucht klare Produktkommunikation

Wenn 0.9.0 Resume nur fuer dateibasierte Ein-/Ausgabepfade garantiert, darf
das nicht implizit bleiben. Sonst entsteht eine Luecke zwischen allgemeiner
Roadmap-Formulierung und tatsaechlicher CLI-Oberflaeche.

### 8.3 Exit-Code-Grenze zwischen `2` und `7`

Die vorgeschlagene Trennung ist plausibel, muss aber in Phase A wirklich
hartgezogen werden. Besonders heikel sind semantisch inkompatible, aber
syntaktisch lesbare Checkpoints, weil sie im Import-Pfad eher an den
bestehenden Preflight-Kanal anschliessen koennen als an reine Config-Fehler.

### 8.4 Sichtbarer CLI-Override vs. Config-Default

Sobald `--checkpoint-dir` als sichtbarer Vertrag dokumentiert wird, muss auch
klar sein, wie es sich zu `pipeline.checkpoint.directory` verhaelt.
Ohne diese Klarstellung entsteht zwischen CLI-Spec und Config-Spec eine neue
Prioritaetsluecke.

### 8.5 "Vollstaendige i18n-CLI" meint nicht automatisch vollstaendig lokalisierte Clikt-Interna

Phase A sollte in CLI-Spec und Guide sauber formulieren, dass 0.9.0 den
produktiven Sprach-Override fuer die 0.8.0-I18n-Runtime liefert. Das ist
nicht automatisch gleichbedeutend mit einer kompletten Uebersetzung aller
Clikt-internen Hilfe- und Parsertexte.

---

## 9. Entscheidungsempfehlung

Phase A sollte zuerst den sichtbaren CLI-Vertrag stabilisieren und erst
danach den technischen Resume-Unterbau aufziehen.

Das reduziert Milestone-Drift in drei Richtungen:

- `--lang` bekommt einen klaren 0.9.0-Produktvertrag statt eines unscharfen
  Mischzustands aus Placeholder und Fallback
- Resume wird mit expliziten Flag-Namen und Fehlergrenzen eingefuehrt statt
  spaeter aus der Implementierung rueckwaerts dokumentiert
- eine moegliche dateibasierte Beta-Einschraenkung wird frueh sichtbar und
  nicht erst als nachtraegliche Ueberraschung in C/D

Damit schafft Phase A die noetige Vertragsbasis fuer die technischen Phasen
B bis E, ohne den Milestone bereits mit Manifest- oder Streaming-Details zu
ueberfrachten.
