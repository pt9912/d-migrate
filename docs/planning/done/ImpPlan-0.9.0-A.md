# Implementierungsplan: Phase A - CLI-Vertrag fuer Sprache und Resume

> **Milestone**: 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI
> **Phase**: A (CLI-Vertrag fuer Sprache und Resume)
> **Status**: Implemented (2026-04-16) — aktives `--lang` mit strenger
> Produktsprachen-Validierung (Exit 2), sichtbare Resume-Flags plus
> stdin/stdout-Preflight (Exit 2), Runner-Warnung bei nicht-aktiver
> Resume-Runtime, Exit-Code-Zielvertrag in Runner-KDocs und CLI-Spec,
> Tests und normative Doku. **Bewusst in Phase A nicht enthalten**
> (Phasen B bis D): tatsaechliches Manifest-Lesen, semantische
> Preflight-Pruefung (Exit 3) und Checkpoint-Integritaet (Exit 7).
> **Referenz**: `docs/planning/implementation-plan-0.9.0.md` Abschnitt 1 bis 4,
> Abschnitt 5.1, Abschnitt 6.1, Abschnitt 8.4 und 8.5; `docs/planning/roadmap.md`
> Milestone 0.9.0 und 0.9.5; `spec/cli-spec.md` globale Flags sowie
> `data export`/`data import`; `docs/user/guide.md` globale Optionen;
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

### 2.1 Stand **vor** Phase A

- `Main.kt` deklarierte `--lang`, lehnte jede Nutzung in 0.8.0 aber bewusst
  mit Exit `7` ab und verwies auf den 0.9.0-Vertrag.
- `I18nSettingsResolver` loeste Locale-Werte ueber
  `D_MIGRATE_LANG`, `LC_ALL`, `LANG`, `i18n.default_locale` und
  System-Locale auf; keine strenge Validierung fuer einen CLI-Override.
- `MessageResolver` faellt fuer unsupported locales auf das englische
  Root-Bundle zurueck (unveraendert, dieser Vertrag bleibt erhalten).
- `spec/cli-spec.md` und `docs/user/guide.md` dokumentierten `--lang`
  konsistent als fuer 0.9.0 reserviert.
- `DataExportCommand` und `DataImportCommand` besassen keinerlei
  Resume-Flags.
- `DataExportRequest` und `DataImportRequest` transportierten entsprechend
  keine Resume- oder Checkpoint-Referenzen.
- `docs/planning/roadmap.md` beschrieb 0.9.0 allgemein als unterbrechbare und
  wiederaufsetzbare Export-/Import-Operationen, ohne den Scope auf
  dateibasierte Pfade oder Flag-Namen einzuengen.

Konsequenz vor Phase A:

- fuer `--lang` existierte die technische Basis, aber der sichtbare
  0.9.0-Nutzervertrag fehlte
- fuer Resume fehlte sowohl die sichtbare CLI-Oberflaeche als auch der
  technische Unterbau
- die Resume-Scope-Einschraenkung (dateibasiert, kein stdin/stdout) musste
  in Phase A explizit gemacht werden

### 2.2 Stand **nach** Phase A

Mit Abschluss der Phase (Status „Implemented", 2026-04-16) gilt:

- `--lang` ist ein aktiver Root-CLI-Override
  (`adapters/driving/cli/.../Main.kt`). Unsupported Werte werden ueber
  [`UnsupportedLanguageException`](../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/I18nSettingsResolver.kt)
  mit Exit 2 abgewiesen.
- `I18nSettingsResolver` akzeptiert `langFromCli` als hoechste Prioritaet
  und validiert streng gegen `SUPPORTED_PRODUCT_LANGUAGES = {"de","en"}`;
  generische Env-/Config-/System-Pfade behalten den toleranteren
  0.8.0-Vertrag inkl. Root-Bundle-Fallback.
- `DataExportCommand`/`DataImportCommand` exponieren `--resume` und
  `--checkpoint-dir`; die Request-DTOs tragen `resume` und `checkpointDir`.
- `DataExportRunner`/`DataImportRunner` haben einen minimalen CLI-Preflight
  fuer Resume: stdout-Export bzw. stdin-Import in Kombination mit
  `--resume` enden mit Exit 2. Ansonsten wird der Flag sichtbar als noch
  nicht aktive Runtime markiert (Warning „resume runtime is not yet
  active"). **Echtes Manifest-Lesen, semantische Preflight-Pruefung und
  Streaming-Wiederaufnahme sind bewusst noch nicht Teil von Phase A**
  (§3.2 / §4.6) und folgen in Phase B bis D.
- Normative Doku: `spec/cli-spec.md`, `docs/user/guide.md` und
  `spec/connection-config-spec.md` sind auf den Phase-A-Vertrag
  angeglichen (inkl. Env-Var-Tabelle §9).
- `docs/planning/roadmap.md` ist nur **teilweise** angeglichen: die
  Milestone-Beschreibung bleibt das Zielbild nach vollstaendigem 0.9.0
  ("unterbrechbar und wieder aufsetzbar"); ergaenzt ist ein expliziter
  Ist-Stand-Kasten, der Phase A als aktuellen Schritt markiert und die
  Resume-Runtime explizit als noch ausstehend (Phase B bis D) ausweist.
  Das verhindert, dass die Roadmap-Formulierung als bereits
  implementierte Wiederaufnahme gelesen wird.
- Roadmap-Hinweis zur dateibasierten Resume-Einschraenkung in 0.9.0 ist
  jetzt explizit in `docs/planning/roadmap.md` vermerkt.

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

- Helptext in `Main.kt`, `spec/cli-spec.md` und `docs/user/guide.md` wird von
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
- sie muss in `spec/cli-spec.md` und `docs/user/guide.md` explizit auftauchen
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
- Exit `3` fuer semantisch inkompatible Resume-Referenzen:
  - Import: als Preflight-Fehler analog zum bestehenden Schema-/Header-
    Preflight
  - Export: symmetrisch auf Exit `3` als Resume-Preflight-Kategorie
    (neues Mapping fuer 0.9.0)

**In Phase A umgesetzt (Status „In Review"):**

- Exit `2` fuer `--resume` + stdout-Export bzw. stdin-Import
  (CLI-Preflight in den Runnern).
- Exit `2` fuer unsupported `--lang` an der Root-CLI
  ([`UnsupportedLanguageException`](../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/I18nSettingsResolver.kt)
  → [`Main.kt`](../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt)).
- Exit-Code-Mapping fuer Exit `3` und Exit `7` ist in den Runner-KDocs und
  in `spec/cli-spec.md` **als Zielvertrag** dokumentiert; die tatsaechliche
  Pruefung (Manifest-Lesen, Format-Validierung, Target-Vergleich) liegt
  bei der Resume-Runtime und wird in Phase B/C implementiert.
- Sichtbares Signal aus dem Runner solange die Runtime fehlt: eine
  stderr-Warnung „resume runtime is not yet active in this build;
  <...> will run from scratch. Phase B/C will activate it" — nicht stille
  Annahme, nicht harter Fehler.

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

- `spec/cli-spec.md` auf aktive `--lang`-Semantik umstellen
- Export-/Import-Optionen um Resume-Vertrag erweitern
- `docs/user/guide.md` auf die neuen globalen und command-lokalen Optionen
  angleichen
- `spec/connection-config-spec.md` auf den sichtbaren Resume-Vertrag
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

- `docs/planning/implementation-plan-0.9.0.md`
- `docs/planning/ImpPlan-0.9.0-A.md`
- `spec/cli-spec.md`
- `spec/connection-config-spec.md`
- `docs/user/guide.md`
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

- `docs/planning/roadmap.md`, falls der dateibasierte Resume-Zuschnitt dort explizit
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
