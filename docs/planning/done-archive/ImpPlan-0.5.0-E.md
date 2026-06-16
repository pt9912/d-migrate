# Implementierungsplan: Phase E - Dokumentation und Release Packaging

> **Milestone**: 0.5.0 - MVP-Release
> **Phase**: E (Dokumentation und Release Packaging)
> **Status**: Umgesetzt (Stand 2026-04-13)
> **Referenz**: `implementation-plan-0.5.0.md` Abschnitt 4.5, Abschnitt 4.6,
> Abschnitt 5 Phase E, Abschnitt 7, Abschnitt 8; `docs/user/releasing.md`;
> `spec/architecture.md` Abschnitt 5.3 Distribution;
> `.github/workflows/build.yml`; `.github/workflows/release-homebrew.yml`

> **Hinweis**: Dieses Dokument bleibt als Umsetzungs- und Abnahmebasis fuer
> Phase E erhalten. Der Planinhalt beschreibt daher stellenweise bewusst die
> Ausgangslage vor der Umsetzung; der Statusblock oben markiert den heute
> erreichten Stand.

---

## 1. Ziel

Phase E macht den 0.5.0-Milestone release-faehig fuer zwei Zielgruppen:

- Nutzer, die `d-migrate` installieren, ausprobieren und verstehen wollen
- Maintainer, die ein konsistentes GitHub-Release mit belastbaren Artefakten
  veroeffentlichen muessen

Der Teilplan liefert deshalb nicht nur "ein paar Doku-Updates", sondern einen
zusammenhaengenden MVP-Release-Slice:

- konsolidiertes Dokumentations-Set fuer Einstieg, Nutzung und Referenz
- ein klarer Maintainer-Flow fuer Release-Artefakte und GitHub Releases
- kanonische CLI-Release-Artefakte fuer Download und Packaging
- eine erste reale Homebrew-Basis statt eines Platzhalters

Nicht Ziel dieser Phase ist eine komplett neue Doku-Site oder ein Ausbau auf
weitere Paketmanager.

---

## 2. Ausgangslage

Aktueller Stand der Codebasis:

- `README.md` existiert als Einstieg und verweist bereits auf Docker, Build und
  Dokumentation.
- `docs/user/guide.md` deckt heute `schema validate`, `schema generate`,
  `data export` und `data import` ab.
- `spec/cli-spec.md` existiert als Referenz, enthaelt aber noch veraltete
  Statusmarker:
  - `schema compare` steht noch als "geplant: 0.5.0"
  - `data import` steht noch als "geplant: 0.4.0", obwohl der Command laengst
    umgesetzt ist
- `docs/user/releasing.md` beschreibt heute Branching, Tagging, GHCR-Image und
  `gh release create`, aber noch nicht:
  - ZIP/TAR/Fat-JAR-Assets
  - SHA256-Datei
  - Workflow-Artefakte fuer Release-Assets
  - Homebrew-Formula-Pflege
- `spec/architecture.md` Abschnitt 5.3 beschreibt Distribution aktuell zu breit:
  - Fat JAR -> Maven Central, SDKMAN
  - Native Image -> GitHub Releases, Homebrew
  - Package Manager -> brew / sdk / scoop
- Das passt nicht zum realen 0.5.0-Scope, der nur GitHub Releases, OCI und eine
  erste Homebrew-Basis vorsieht.
- Das CLI-Modul besitzt bereits:
  - `installDist`
  - `distZip`
  - `distTar`
  - Jib fuer das OCI-Image
- Es gibt aber noch keinen klar benannten Fat-JAR-Task und keinen kanonischen
  Release-Ordner mit stabilen Dateinamen.
- `.github/workflows/build.yml` baut, testet, verifiziert Coverage und pusht auf
  Tag-Builds ein OCI-Image nach GHCR.
- Der Workflow laedt heute nur Test- und Coverage-Artefakte hoch, aber keine
  Release-Assets.
- `packaging/homebrew/` existiert noch nicht.

Konsequenz fuer Phase E:

- Dokumentation und Packaging muessen gemeinsam betrachtet werden
- Release-Dateinamen duerfen nicht implizit aus zufaelligen Gradle-Outputs
  abgeleitet werden
- der Maintainer-Flow muss exakt dieselben Artefakte beschreiben, bauen und
  veroeffentlichen, die der Workflow validiert

---

## 3. Scope fuer Phase E

### 3.1 In Scope

- Doku-Konsolidierung fuer:
  - `README.md`
  - `docs/user/guide.md`
  - `spec/cli-spec.md`
  - `docs/user/releasing.md`
  - `spec/architecture.md` Abschnitt Distribution
- optionaler Konsistenzabgleich in `docs/planning/roadmap.md`, falls Statusmarker oder
  Formulierungen fuer 0.5.0 nachgezogen werden muessen
- Release-Asset-Build im CLI-Modul:
  - Fat JAR
  - ZIP-Distribution
  - TAR-Distribution
  - SHA256-Datei
- ein kanonischer Release-Ordner mit stabilen Dateinamen
- Tag-Workflow-Validierung und Upload dieser Release-Assets als
  Workflow-Artefakte
- neue Basis-Formula unter `packaging/homebrew/d-migrate.rb`
- Release-Guide fuer:
  - lokalen Asset-Build
  - Bezug aus Workflow-Artefakten
  - `gh release create` bzw. `gh release upload`
  - Formula-Update und `brew install --formula`-Smoke-Test

### 3.2 Bewusst nicht Teil von Phase E

- neue Doku-Site, MkDocs/Docusaurus oder aehnlicher Site-Unterbau
- Beispielprojekt `examples/e-commerce/`
- separate Homebrew-Tap-Repository-Verwaltung
- automatische Formula-Bumps per CI
- automatische GitHub-Release-Publikation direkt aus dem Workflow
- Native Image
- Maven Central, SDKMAN oder Scoop
- Signierung, Notarisierung oder Checks wie Cosign/SLSA

---

## 4. Architekturentscheidungen

### 4.1 Das Dokumentations-Set bekommt klare Verantwortungen

Phase E soll nicht "alle Dateien ein bisschen" aendern, sondern die Rollen
explizit schaerfen:

- `README.md`
  - Landing Page
  - Installationsueberblick
  - kurzer Quick Start
  - Link-Hub in tiefere Doku
- `docs/user/guide.md`
  - task-orientierte Nutzung
  - erste echte Workflows fuer Schema- und Datenpfade
- `spec/cli-spec.md`
  - Referenz des implementierten CLI-Vertrags
  - Statusmarker nur fuer wirklich noch nicht umgesetzte Commands
- `docs/user/releasing.md`
  - Maintainer-Runbook fuer Build, Tag, Release, Assets und Homebrew
- `spec/architecture.md`
  - grobe, ehrliche Verortung der realen Distributionskanaele
  - keine 0.5.0-falschen Zukunftsversprechen als Ist-Zustand

Damit wird vermieden, dass dieselbe Information in vier Dateien mit vier leicht
abweichenden Wahrheiten dupliziert wird.

### 4.2 Phase E dokumentiert den implementierten 0.5.0-Scope, nicht Wunschzustaende

Dokumentation fuer den MVP-Release darf nicht weiter in der Zukunft leben als
der Code.

Verbindliche Regel fuer 0.5.0:

- `schema compare` wird in Nutzerdoku als vorhandener file-based MVP-Slice
  beschrieben
- `data import` wird nicht mehr als geplanter Command gefuehrt
- Progress-Doku folgt dem in Phase D definierten MVP-Vertrag
- Native Image, SDKMAN, Scoop und aehnliche Kanaele bleiben klar als spaeter
  markierte Zukunftspfade

### 4.3 Release-Artefakte bleiben im CLI-Modul verankert

Die reale Distribution entsteht weiterhin aus `adapters/driving/cli`.

Verbindliche Regel:

- kein neues Packaging-Modul nur fuer 0.5.0
- kein `buildSrc`- oder Convention-Plugin-Zwang fuer einen kleinen MVP-Slice
- Build-Logik bleibt im bestehenden CLI-Buildscript, solange sie ueberschaubar
  bleibt

Die Rollen der Artefakte:

- `installDist` bleibt Grundlage fuer das Runtime-Image aus dem Dockerfile
- `distZip` und `distTar` bleiben die launcherbasierten Download-Distributionen
- Fat JAR ist ein zusaetzliches Convenience-Artefakt fuer `java -jar`

### 4.4 Release-Assets bekommen einen kanonischen Release-Ordner

Die rohen Gradle-Outputs liegen heute in verschiedenen Verzeichnissen:

- `build/distributions`
- `build/libs`
- `build/install`

Das ist fuer Workflow, Release-Guide und Checksummen unnoetig fragil.

Verbindliche Entscheidung fuer 0.5.0:

- das CLI-Modul erzeugt einen kanonischen Ordner
  `adapters/driving/cli/build/release/`
- dieser Ordner enthaelt genau die fuer GitHub Releases relevanten Dateien
- Workflow, Doku und Homebrew verweisen nur auf diesen Ordner bzw. seine
  publizierten Inhalte

Bevorzugte Dateinamen:

- `d-migrate-<version>.zip`
- `d-migrate-<version>.tar`
- `d-migrate-<version>-all.jar`
- `d-migrate-<version>.sha256`

Falls die zugrundeliegenden Gradle-Tasks andere Rohdateinamen liefern, werden
die Dateien in diesem Release-Ordner explizit kopiert oder normalisiert.

### 4.5 Checksummen werden in Gradle erzeugt, nicht als ad-hoc Shell-Schritt

Die Masterplanung zeigt `sha256sum` als Verifikationsbeispiel. Fuer den realen
Release-Flow ist das als Produktionsschritt zu unscharf:

- Shell-Tools unterscheiden sich zwischen Linux und macOS
- Dateireihenfolgen ueber Globs sind fehleranfaellig
- Workflow und lokale Maintainer-Kommandos koennen auseinanderlaufen

Verbindliche Entscheidung:

- die SHA256-Datei wird durch einen Gradle-Task erzeugt
- der Task arbeitet auf dem kanonischen Release-Ordner
- die Ausgabe ist deterministisch sortiert
- die Checksum-Datei enthaelt genau die Assets, die spaeter publiziert werden

### 4.6 Homebrew konsumiert die launcherbasierte Distribution, nicht das Fat JAR

Fuer Homebrew ist das ZIP-Artefakt die robustere Basis als das Fat JAR:

- es enthaelt bereits das `bin/d-migrate`-Launcher-Skript
- es bildet den regulaeren CLI-Installationspfad ab
- es vermeidet Homebrew-spezifische `java -jar`-Wrapper-Logik als Pflicht

Verbindliche Entscheidung fuer 0.5.0:

- `packaging/homebrew/d-migrate.rb` referenziert das publizierte ZIP-Artefakt
- die Formula enthaelt die reale SHA256 dieses ZIP-Artefakts
- die Formula installiert die Distribution unter `libexec` und verlinkt
  `bin/d-migrate`
- die Formula deklariert Java 21 explizit

Das Fat JAR bleibt Download-Asset, ist aber nicht die Grundlage fuer Homebrew.

### 4.7 Tag-Builds validieren und materialisieren Assets, publizieren sie aber nicht automatisch

0.5.0 braucht einen belastbaren Release-Prozess, aber keinen vollautomatischen
"Tag erstellt GitHub Release inklusive Assets"-Workflow.

Verbindliche Regel:

- `build.yml` validiert auf Tag-Builds die Release-Assets
- `build.yml` laedt die Dateien als Workflow-Artefakte hoch
- das eigentliche `gh release create` bzw. `gh release upload` bleibt ein
  expliziter Maintainer-Schritt in `docs/user/releasing.md`

Damit bleiben die Verantwortungen klar:

- CI beweist, dass die Artefakte baubar sind
- Maintainer publizieren bewusst dieselben Dateien

### 4.8 Release-Doku und Formula arbeiten mit derselben URL-/Versionslogik

Die Formula braucht eine konkrete Release-URL und eine SHA256. Beides muss zur
real publizierten GitHub-Release-Datei passen.

Verbindliche Regel:

- der Release-Guide beschreibt, wie URL und SHA aus den kanonischen Assets
  abgeleitet werden
- die Formula wird mit genau diesen Werten gepflegt
- der echte Homebrew-Smoke-Test laeuft erst gegen ein **publiziertes**
  GitHub-Release-Asset, nicht nur gegen einen lokalen Build oder einen
  vorbereiteten Draft-Zwischenstand

### 4.9 Das gruene Tag-Workflow-Artefakt ist die Publish-Quelle

Fuer ZIP, TAR, Fat JAR, SHA256 und Formula darf es keine zwei konkurrierenden
"Quellen der Wahrheit" geben.

Verbindliche Entscheidung fuer 0.5.0:

- lokale `build/release/`-Artefakte dienen Preflight und Vorabpruefung
- fuer den eigentlichen GitHub Release ist das Workflow-Artefakt
  `release-assets` des **gruenen Tag-Builds** die kanonische Publish-Quelle
- `gh release create` bzw. `gh release upload` arbeitet mit genau diesen
  Dateien
- Formula-URL und Formula-SHA werden aus dem publizierten ZIP abgeleitet, das
  aus diesem Workflow-Artefakt stammt

Damit wird ausgeschlossen, dass lokal gebaute Dateien und CI-gebaute Dateien
unbemerkt auseinanderlaufen.

### 4.10 Asset-Namen leiten sich immer aus `project.version` ab

Fuer Phase E gelten keine frei erfundenen Dateinamen pro Stadium.

Verbindliche Regel:

- der Release-Task leitet seine Dateinamen ausschliesslich aus `project.version`
  ab
- die Doku verwendet deshalb im Plan bewusst `<version>` als Platzhalter statt
  gemischter Beispiele wie `0.5.0-SNAPSHOT` und `0.5.0-rc1`
- vor dem eigentlichen Release-Cut kann `<version>` lokal noch ein
  `-SNAPSHOT`-Suffix tragen
- fuer den finalen GitHub Release und die Formula gilt die bereits auf den
  Release-Wert gebumpte Version ohne `-SNAPSHOT`

---

## 5. Betroffene Dateien und Module

### 5.1 Neue Produktionsdateien

| Datei | Zweck |
|---|---|
| `packaging/homebrew/d-migrate.rb` | erste reale Homebrew-Formula fuer 0.5.0 |

### 5.2 Geaenderte Produktionsdateien

| Datei | Aenderung |
|---|---|
| `adapters/driving/cli/build.gradle.kts` | Fat-JAR-Plugin/Task, kanonischer Release-Ordner, Checksum-Task |
| `.github/workflows/build.yml` | Tag-spezifische Release-Asset-Validierung und Upload als Workflow-Artefakt |
| `README.md` | Installationspfade, Quick Start, aktuelle 0.5.0-Faehigkeiten |
| `docs/user/guide.md` | Compare-Quick-Start, Installations-/Nutzungsfluss fuer den MVP |
| `docs/user/releasing.md` | Release-Artefakte, Workflow-Artefakte, `gh release`-Schritte, Formula-Pflege |
| `spec/architecture.md` | Distribution fuer 0.5.0 ehrlich schaerfen |
| `spec/cli-spec.md` | Statusmarker und Referenzkonsistenz fuer implementierte Commands |

Optional, nur wenn fuer Konsistenz noetig:

| Datei | Aenderung |
|---|---|
| `docs/planning/roadmap.md` | sprachliche Konsistenz rund um 0.5.0-Doku-/Build-Deliverables |

### 5.3 Neue oder geaenderte Testdateien

Phase E braucht voraussichtlich keine neuen Kotlin-Testdateien. Die Absicherung
liegt primar in:

- Gradle-Task-Ausfuehrung
- Workflow-Artefakten
- manuellen Smoke-Checks fuer Release-Artefakte und Homebrew

Wenn Build-Logik in echte Kotlin-Klassen ausgelagert werden muss, waeren
dedizierte Tests nachzuziehen. Das ist fuer 0.5.0 aber kein Pflichtziel.

---

## 6. Dokumentations-Set

### 6.1 `README.md`

Das README ist in 0.5.0 die erste Kontaktflaeche fuer Early Adopters und muss
deshalb drei Dinge sauber leisten:

- in einem Bildschirm erklaeren, was `d-migrate` ist
- reale Installationspfade zeigen
- in den Rest der Doku fuehren

Pflichtanpassungen:

- "Aktuelle Faehigkeiten" auf den 0.5.0-MVP-Stand bringen:
  - `schema compare` als vorhandener file-based MVP-Slice
  - `data export` / `data import` bleiben sichtbar
- "Geplant" nur fuer echte Zukunftsthemen wie DB-basiertes Reverse Engineering
- Installationsueberblick mindestens fuer:
  - GitHub Release Assets
  - OCI-Image aus GHCR
  - Build aus Quellcode
- kurzer Quick Start mit mindestens:
  - `schema validate`
  - `schema compare`
  - Verweis auf Guide fuer tieferen Datenpfad

Wichtig:

- README darf Homebrew nicht als vollautomatischen Standardpfad verkaufen, wenn
  0.5.0 nur eine Formula-Basis im Hauptrepo liefert
- keine Verweise auf nicht existente Native- oder SDKMAN-Pakete

### 6.2 `docs/user/guide.md`

Der Guide ist task-orientiert und darf ueber das README hinaus detaillierter
werden.

Pflichtanpassungen fuer 0.5.0:

- ein `schema compare`-Abschnitt mit minimalem Datei-zu-Datei-Beispiel
- Installations-/Startpfade fuer:
  - Release-Asset
  - Docker/GHCR
  - Quellcode-Build
- bestehende Export-/Import-Abschnitte auf aktuellen CLI-Stand pruefen
- Verweise auf `--quiet`, `--no-progress` und `--output-format` nur in der
  Form, wie sie durch Phase C und D tatsaechlich implementiert sind

Nicht noetig fuer 0.5.0:

- ein vollstaendiges Administrationshandbuch
- lange Szenario-Tutorials ueber mehrere Kapitel

### 6.3 `spec/cli-spec.md`

Die CLI-Spec ist in Phase E keine neue Feature-Spezifikation mehr, sondern eine
Konsistenzpruefung gegen den implementierten Stand.

Pflichtpunkte:

- `schema compare` Statusmarker von "geplant" auf implementierten
  0.5.0-MVP-Stand bringen
- `data import` nicht weiter als "geplant: 0.4.0" fuehren
- Compare- und Progress-Abschnitte nicht erneut von README/Guide abweichend
  formulieren
- Platzhalter-Commands wie `schema migrate` / `schema rollback` bleiben klar als
  spaetere Milestones markiert

### 6.4 `docs/user/releasing.md`

`docs/user/releasing.md` ist der zentrale Maintainer-Runbook-Pfad fuer 0.5.0 und
muss deshalb den kompletten Asset- und Formula-Fluss tragen.

Pflichtinhalte:

- lokaler Build der Release-Assets
- Bezug der Assets aus GitHub-Workflow-Artefakten
- `gh release create` / `gh release upload` mit genau den kanonischen Dateien
- Ableitung und Pflege von Formula-URL und SHA256
- klare Trennung zwischen:
  - lokalem Preflight mit `build/release/`
  - kanonischer Publish-Quelle `release-assets` aus dem Tag-Workflow
- Verifikation:
  - GitHub Release
  - OCI-Image
  - Fat JAR
  - Distribution
  - Homebrew nach echtem Release-Publish

### 6.5 `spec/architecture.md`

Die Architektur-Doku soll fuer 0.5.0 nicht jede Zukunftsoption loeschen, aber
die reale Distribution klar markieren.

Pragmatische Regel:

- GitHub Releases, OCI-Image und Homebrew-Basis werden als reale 0.5.0-Kanaele
  beschrieben
- Native Image, SDKMAN, Scoop und aehnliche Wege bleiben als Zukunftspfade
  sichtbar, aber nicht als aktueller MVP-Auslieferungsstand

---

## 7. Release Packaging

### 7.1 CLI-Build bekommt einen kanonischen Release-Task

Das CLI-Buildscript soll einen klaren Einstieg fuer Release-Artefakte bieten,
z. B.:

- `shadowJar` oder aequivalenter Fat-JAR-Task
- `assembleReleaseAssets`
- optional ein separater `writeReleaseChecksums`-Task

Bevorzugter Vertrag:

- `assembleReleaseAssets` haengt ab von:
  - `distZip`
  - `distTar`
  - `shadowJar`
- der Task befuellt `build/release/`
- der Task erzeugt die Dateien in ihren kanonischen Namen

Wichtig:

- `installDist` bleibt fuer Docker Runtime relevant, muss aber nicht in den
  Release-Ordner kopiert werden
- die Release-Doku verweist nur auf `build/release/`, nicht auf rohe
  Unterverzeichnisse in `build/distributions` oder `build/libs`

### 7.2 Fat JAR

Der Fat-JAR-Task soll fuer 0.5.0 einen direkt nutzbaren Download liefern:

- Dateiname: `d-migrate-<version>-all.jar`
- ausfuehrbar per `java -jar`
- Manifest mit Main-Class auf `dev.dmigrate.cli.MainKt`

Der Fat JAR ist ein zusaetzlicher Release-Kanal, aber nicht:

- Quelle des Docker-Images
- Grundlage der Homebrew-Formula

### 7.3 Checksum-Datei

Die Checksum-Datei soll fuer GitHub Releases und Homebrew klar nachvollziehbar
sein.

Verbindlicher Mindestvertrag:

- Dateiname: `d-migrate-<version>.sha256`
- enthaelt SHA256-Zeilen fuer:
  - ZIP
  - TAR
  - Fat JAR
- Reihenfolge ist deterministisch
- Dateinamen in der Ausgabe stimmen exakt mit den publizierten Asset-Namen
  ueberein

### 7.4 GitHub-Workflow

`build.yml` wird in Phase E nicht komplett neu strukturiert, sondern gezielt
erweitert.

Empfohlene Richtung:

- bestehender Build-/Coverage-Pfad bleibt erhalten
- auf Tag-Builds kommt ein zusaetzlicher Release-Asset-Schritt hinzu
- der Workflow laedt `adapters/driving/cli/build/release/*` als Artefakt
  `release-assets` hoch
- der OCI-Push nach GHCR bleibt bestehen
- das gruene Tag-Artefakt `release-assets` wird in `docs/user/releasing.md` als
  kanonische Quelle fuer `gh release create` / `gh release upload` festgelegt

Bewusst nicht Teil des MVP:

- automatisches Anlegen oder Aktualisieren des GitHub Releases im Workflow
- Upload direkt als Release-Asset aus GitHub Actions

### 7.5 Homebrew-Formula

Die Basis-Formula unter `packaging/homebrew/d-migrate.rb` soll reale Werte
tragen, keine Schablone.

Pflichtpunkte:

- `url` zeigt auf das publizierte GitHub-Release-ZIP
- `sha256` entspricht exakt diesem ZIP
- Java 21 wird explizit deklariert
- Installation nutzt die entpackte Distribution unter `libexec`
- `bin/d-migrate` wird fuer den Nutzer verlinkt
- `test do` prueft mindestens einen einfachen CLI-Aufruf wie `--help`

Wichtig:

- die Formula wird erst mit finaler Release-URL und finaler SHA256 fertiggestellt
- ein lokaler Preflight darf hoechstens Syntax und Struktur der Formula pruefen
- der echte `brew install --formula`-Smoke-Test laeuft erst nach dem Publish des
  Releases gegen die reale Asset-URL

Nicht Ziel von 0.5.0:

- separates Tap-Repo
- automatische Formula-Aktualisierung
- `brew install d-migrate` ohne zusaetzlichen Repo-/Pfad-Kontext

---

## 8. Implementierung

### 8.1 Build-Logik im CLI-Modul

Umsetzungsschritte:

1. Shadow-Plugin oder aequivalente Fat-JAR-Unterstuetzung in
   `adapters/driving/cli/build.gradle.kts` aktivieren
2. Release-Ordner-Task einfuehren
3. Checksum-Task einfuehren
4. kanonische Dateinamen sicherstellen

Pragmatische Empfehlung:

- den Release-Task klein halten
- keine generische Packaging-Abstraktion fuer spaetere Package-Manager bauen
- nur ZIP, TAR, Fat JAR und SHA256 sauber zusammenfuehren

### 8.2 Workflow-Erweiterung

Umsetzungsschritte in `.github/workflows/build.yml`:

1. Tag-Gating fuer Release-Asset-Schritt
2. Gradle-Aufruf fuer den kanonischen Release-Task
3. Upload von `release-assets`
4. OCI-Image-Push unveraendert lassen

Wichtig:

- kein zweiter widerspruechlicher Asset-Build neben dem Gradle-Task
- kein Shell-Glue mit separat zusammengeklaubten Dateien aus mehreren Ordnern

### 8.3 Doku-Aktualisierung

Reihenfolge fuer den Doku-Slice:

1. README auf reale 0.5.0-Faehigkeiten heben
2. Guide fuer Compare- und Installationspfad ergaenzen
3. CLI-Spec-Konsistenz bereinigen
4. Architektur-Distribution schaerfen
5. Release-Guide mit exakt denselben Dateinamen und Task-Namen erweitern

### 8.4 Formula und Release-Guide aufeinander abstimmen

Die Formula darf kein isolierter Nebenpfad sein.

Pflichten:

- `docs/user/releasing.md` nennt den exakten Formula-Pfad
- `docs/user/releasing.md` beschreibt den URL-/SHA-Bump
- die Formula basiert auf demselben Release-Dateinamen, den der Workflow und
  der Release-Ordner nutzen
- `docs/user/releasing.md` trennt klar zwischen lokalem Asset-Preflight und finalem
  Homebrew-Smoke-Test nach Publish

---

## 9. Tests und QA

### 9.1 Automatisierte Absicherung

Phase E braucht voraussichtlich keine neuen Kotlin-Unit-Tests, wohl aber
technische Verifikation der Packaging-Schritte.

Pflichtchecks:

- Release-Gradle-Task laeuft erfolgreich
- Release-Ordner enthaelt exakt die erwarteten Dateien
- Tag-Workflow laedt `release-assets` hoch
- bestehender Build-/Coverage-/OCI-Pfad regressiert nicht

### 9.2 Dokumentations-QA

Die Doku-Aenderungen werden inhaltlich gegen den implementierten Stand geprueft:

- README- und Guide-Beispiele verwenden existierende Commands und Flags
- CLI-Spec-Statusmarker stimmen mit dem Code ueberein
- Release-Guide nennt reale Task- und Dateinamen
- Architektur-Doku verspricht keine 0.5.0-nicht vorhandenen Distributionskanaele

### 9.3 Manuelle Smoke-Checks

Fuer 0.5.0 sind folgende manuellen Verifikationen Teil der Release-Qualitaet:

- Fat JAR startet mit `--help`
- launcherbasierte Distribution startet mit `--help`
- OCI-Image startet mit `--help`
- Homebrew-Formula installiert und startet `d-migrate --help`

---

## 10. Build und Verifikation

Gezielter Build fuer Release-Assets:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:assembleReleaseAssets --rerun-tasks" \
  -t d-migrate:release-assets .
```

Artefakte aus der Build-Stage extrahieren:

```bash
docker create --name d-migrate-release-assets d-migrate:release-assets
docker cp d-migrate-release-assets:/src/adapters/driving/cli/build/release ./release
docker rm d-migrate-release-assets
```

Release-Dateien lokal pruefen:

```bash
ls -1 ./release
cat ./release/d-migrate-<version>.sha256
java -jar ./release/d-migrate-<version>-all.jar --help
```

Vollstaendiger Runtime-Build:

```bash
docker build -t d-migrate:mvp .
docker run --rm d-migrate:mvp --help
```

Kanonische Publish-Quelle nach gruenem Tag-Build:

```text
GitHub Actions -> Workflow-Artefakt `release-assets`
```

Workflow-Artefakt nach gruenem Tag-Build lokal beziehen:

```bash
gh run download <tag-run-id> \
  -n release-assets \
  -D ./release-assets
ls -1 ./release-assets
cat ./release-assets/d-migrate-<version>.sha256
```

Draft-Upload genau dieser Workflow-Dateien als vorbereiteter Release-Schritt:

```bash
gh release create v<version> \
  --draft \
  --title "v<version>" \
  ./release-assets/*
```

Wichtig:

- der lokale Ordner `./release` dient nur Preflight und Dateipruefung vor dem
  Tag
- fuer den echten Release-Publish werden die Dateien aus dem
  gruenerklaerten Tag-Workflow-Artefakt `release-assets` bezogen
- bei bereits angelegtem Release arbeitet `gh release upload` mit demselben
  heruntergeladenen Ordner `./release-assets/*`

Homebrew-Verifikation auf einem Host mit `brew` nach echtem Publish des
Releases:

```bash
brew install --formula ./packaging/homebrew/d-migrate.rb
d-migrate --help
```

---

## 11. Abnahmekriterien

- `README.md`, `docs/user/guide.md`, `docs/user/releasing.md` und `spec/cli-spec.md`
  beschreiben den implementierten 0.5.0-Stand ohne veraltete
  "geplant"-Markierungen fuer bereits vorhandene Commands.
- `spec/architecture.md` Abschnitt Distribution beschreibt fuer 0.5.0 ehrlich
  GitHub Releases, OCI und eine Homebrew-Basis, ohne Native Image oder weitere
  Package-Manager als aktuellen MVP-Auslieferungsstand auszugeben.
- Das CLI-Modul erzeugt ueber einen kanonischen Release-Task einen Ordner
  `build/release/` mit:
  - ZIP
  - TAR
  - Fat JAR
  - SHA256-Datei
- Die Dateinamen dieser Artefakte sind stabil und release-tauglich.
- Tag-Builds in `.github/workflows/build.yml` validieren diese Artefakte und
  laden sie als Workflow-Artefakt hoch.
- `docs/user/releasing.md` beschreibt, wie genau diese Artefakte lokal oder aus dem
  Workflow bezogen und per `gh release create` bzw. `gh release upload`
  publiziert werden.
- Das Workflow-Artefakt `release-assets` des gruenerklaerten Tag-Builds ist die
  kanonische Quelle fuer den eigentlichen GitHub Release.
- `packaging/homebrew/d-migrate.rb` existiert als reale Formula, zeigt auf ein
  publiziertes GitHub-Release-ZIP und traegt die korrekte SHA256 dieses Assets.
- Die Formula wurde mindestens einmal mit `brew install --formula` und
  `d-migrate --help` gegen das publizierte Release-Asset verifiziert.
- Der bestehende OCI-Release-Pfad nach GHCR regressiert nicht.
