# Implementierungsplan: Milestone 0.5.0 - MVP-Release

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer Milestone
> 0.5.0. Es dient als laufend gepflegte Spezifikation und Review-Grundlage
> waehrend der Umsetzung.
>
> Status: Draft
> Referenzen: `docs/roadmap.md` Milestone 0.5.0, `docs/cli-spec.md` Abschnitt
> `schema compare` und `Fortschrittsanzeige`, `docs/releasing.md`,
> `docs/architecture.md` Abschnitt 5.3 Distribution.

---

## 1. Ziel

Milestone 0.5.0 schliesst die MVP-Phase ab. Der Fokus liegt nicht auf einem
weiteren grossen Fachfeature wie in 0.4.0, sondern auf einem oeffentlich
veroeffentlichbaren, koharenten Gesamtpaket:

- Schema-Vergleich auf Basis des neutralen Modells
- ein benutzbares `d-migrate schema compare`-Kommando als file-based
  MVP-Slice von LF-015
- eine MVP-taugliche Fortschrittsanzeige fuer langlaufende CLI-Operationen
- konsolidierte Benutzer-Dokumentation
- Release-Artefakte fuer GitHub Releases und eine erste Homebrew-Basis
- stabile QA- und Release-Checks fuer den ersten oeffentlichen MVP-Release

Das Ergebnis von 0.5.0 ist kein "neuer technischer Unterbau", sondern ein
Release-Milestone: Early Adopters sollen das bereits gebaute Produkt vernuenftig
installieren, verstehen, ausprobieren und in Skripten nutzen koennen.

Wichtig: 0.5.0 schliesst LF-015 bewusst nur teilweise ab. Der Milestone liefert
einen strukturellen Datei-zu-Datei-Vergleich im neutralen Modell; der volle
Vergleich zwischen Umgebungen bzw. Datenbanken folgt erst mit `SchemaReader` in
0.6.0.

---

## 2. Ausgangslage

Stand nach 0.4.x:

- `schema validate`, `schema generate`, `data export` und `data import` sind
  bereits vorhanden.
- Die CLI besitzt schon globale Flags wie `--quiet`, `--no-progress` und
  `--output-format`, aber es gibt noch kein durchgaengiges Progress-Konzept fuer
  laufende Operationen.
- Die Release-Pipeline baut und testet das Projekt bereits, pusht auf Tag-Builds
  ein OCI-Image nach GHCR und nutzt `installDist` fuer die Runtime-Distribution.
- Ein Release-Guide ist vorhanden, GitHub-Release-Artefakte und Homebrew-Setup
  sind aber noch nicht produktisiert.
- In `docs/cli-spec.md` sind `schema compare`, `schema migrate` und
  `schema rollback` bereits als "geplant 0.5.0" eingetragen, obwohl der
  `SchemaReader` laut Roadmap erst in 0.6.0 kommt.

0.5.0 muss diese Spannungen explizit aufloesen, statt sie still mitzuschleppen.

---

## 3. Scope

### 3.1 In Scope fuer 0.5.0

- Diff-Modell und Comparator fuer zwei neutrale Schema-Definitionen
- CLI `schema compare` fuer Datei-zu-Datei-Vergleich als MVP-Teilabdeckung von
  LF-015
- menschenlesbare und maschinenlesbare Diff-Ausgabe
- Exit-Code-Vertrag fuer "identisch" vs. "unterschiedlich"
- Progress-Infrastruktur fuer bestehende langlaufende Commands
- Dokumentations-Set fuer den MVP-Release
- Release-Artefakte fuer GitHub Releases
- erste Homebrew-Formula-Basis
- QA/CI-Absicherung fuer den Release

### 3.2 Bewusst nicht Teil von 0.5.0

- `schema reverse` bzw. generischer `SchemaReader` gegen echte Datenbanken
  (bleibt 0.6.0)
- URL-Operanden fuer `schema compare` ohne vorhandenen `SchemaReader`
- `schema migrate` und `schema rollback`
- Native Image, SDKMAN, Scoop oder weitere Package-Manager
- neue Datenbankdialekte
- neue neutrale Typen aus 0.5.5
- i18n-/Lokalisierungsarbeit aus 0.8.0
- Beispiel-Projekte wie `examples/e-commerce/` (verschoben nach 0.6.0)

---

## 4. Leitentscheidungen

### 4.1 `schema compare` ist in 0.5.0 Datei-zu-Datei

Die aktuelle CLI-Spec nennt `--source <path|url> --target <path|url>`. Das ist
mit der Roadmap nicht konsistent, weil DB-URLs einen `SchemaReader` brauchen,
der erst fuer 0.6.0 geplant ist.

Verbindliche Entscheidung fuer 0.5.0:

- `schema compare` vergleicht zwei Schema-Dateien im neutralen Format
- beide Operanden sind in 0.5.0 Pfade, keine DB-URLs
- die CLI-Spec wird entsprechend korrigiert oder explizit mit "DB-URLs ab 0.6.0"
  annotiert

Das ist eine bewusste MVP-Teilabdeckung von LF-015, nicht die volle
Anforderung "Vergleich zwischen verschiedenen Umgebungen". Die Roadmap und
CLI-Spec muessen diese Einschraenkung explizit sichtbar machen, statt 0.5.0 als
vollstaendige LF-015-Erfuellung zu labeln.

### 4.2 Diff arbeitet auf dem neutralen Modell, nicht auf DDL-Text

Der Comparator bekommt zwei `SchemaDefinition`-Objekte und vergleicht sie
strukturell. Es wird bewusst kein SQL-String-Vergleich und kein
"generate DDL on both sides and diff the text"-Workaround eingefuehrt.

Vorteile:

- dialektunabhaengig
- stabile, testbare Semantik
- wiederverwendbar fuer spaetere `schema migrate`- und `schema reverse`-Flows

### 4.3 `schema migrate` und `schema rollback` werden aus 0.5.0 entfernt

Beide Commands haengen fachlich am Diff-Modell, brauchen fuer echte DB-Ziele
aber mehr als ein MVP-Diff:

- Alter-Operationsmodell
- Dialekt-Migrationsgeneratoren
- Sicherheitsregeln fuer destruktive Aenderungen
- Rollback-Vertrag

Fuer 0.5.0 wird daher nur der Compare-Pfad produktisiert. Die Platzhalter in
`docs/cli-spec.md` werden auf spaeter verschoben.

### 4.4 Fortschritt im MVP ist ereignisbasiert, nicht TUI-basiert

0.5.0 fuehrt keine komplexe Terminal-UI ein. Die Fortschrittsanzeige bleibt
stderr-basiert und skriptfreundlich:

- line-orientierte Events
- klare Respektierung von `--quiet` und `--no-progress`
- keine Cursor-Magie als Voraussetzung

Falls eine prozentuale Anzeige ohne verlaessliche Totals nicht moeglich ist,
werden fuer 0.5.0 monotone Zaehler und Tabellenstatus bevorzugt. Die CLI-Spec
muss diesen MVP-Vertrag dann ehrlich abbilden.

### 4.5 Release-Artefakte bauen auf dem bestehenden CLI-Modul auf

Das CLI-Modul besitzt bereits `installDist`, `distTar`, `distZip` und Jib.
0.5.0 fuehrt daher keinen parallelen Packaging-Unterbau ein, sondern erweitert
das bestehende Setup um:

- ein zusaetzliches Fat-JAR-Artefakt fuer GitHub Releases
- Checksums fuer Release-Assets
- eine einfache Homebrew-Formula-Basis, die ein veroeffentlichtes Artefakt
  konsumiert

Verbindlicher Release-Pfad fuer 0.5.0:

- `adapters/driving/cli` bekommt einen klar benannten Fat-JAR-Task
  (bevorzugt `shadowJar`)
- Tag-Builds in `.github/workflows/build.yml` validieren `shadowJar`,
  `distZip` und `distTar` und laden die erzeugten Dateien plus SHA256-Datei als
  Workflow-Artefakte hoch
- `docs/releasing.md` wird erweitert: `gh release create` oder
  `gh release upload` haengt genau diese vier Asset-Gruppen an den GitHub
  Release:
  - `d-migrate-<version>.zip`
  - `d-migrate-<version>.tar`
  - `d-migrate-<version>-all.jar`
  - `d-migrate-<version>.sha256`

0.5.0 verspricht damit einen belastbaren Release-Prozess, aber keine voll
automatische "Tag erstellt Release inkl. Assets"-Magie. Der Tag-Workflow
validiert und materialisiert die Assets; der Release-Schritt publiziert sie
explizit.

### 4.6 Homebrew in 0.5.0 = reale installierbare Basis, kein Template

Homebrew wird in 0.5.0 nicht nur als Platzhalter gepflegt. Die Basis fuer den
MVP ist:

- eine echte Formula unter `packaging/homebrew/d-migrate.rb`
- die Formula zeigt auf ein publiziertes GitHub-Release-Artefakt
- die Formula enthaelt eine echte SHA256 fuer dieses Artefakt
- die Formula wird im Release-Prozess einmal tatsaechlich installiert und
  geprueft

Nicht Ziel von 0.5.0:

- ein separates Tap-Repository
- automatische Formula-Bumps per CI
- `brew install d-migrate` ohne zusaetzlichen Tap-/Pfad-Kontext

---

## 5. Geplante Arbeitspakete

### Phase A - Spezifikationsbereinigung und Scope-Fixierung

1. `docs/cli-spec.md` fuer `schema compare` auf den 0.5.0-MVP-Vertrag
   umstellen:
   - Operanden: Datei statt `path|url`
   - Exit-Codes: mindestens `0`, `1`, `2`, `3`, `7`
   - `--output`-Verhalten dokumentieren
   - klare Markierung als "file-based compare MVP"; DB-basierter Compare ab 0.6.0
2. `docs/cli-spec.md` fuer `schema migrate` und `schema rollback`
   auf spaeteren Milestone verschieben
3. Abschnitt "Fortschrittsanzeige" auf den tatsaechlichen MVP-Umfang
   abgleichen
4. `docs/roadmap.md` mit Verweis auf diesen Plan ergaenzen, sobald die
   Umsetzung startet, und den 0.5.0-Eintrag sprachlich auf
   "Schema compare (file-based MVP)" schaerfen

### Phase B - Core-Diff-Engine

Ziel: Ein stabiles, serialisierbares Diff-Modell im Core-Layer.

Betroffene Module:

- `hexagon:core`
- optional `hexagon:application` fuer Ausgabe-nahe DTOs

Geplante Bausteine:

- `SchemaDiff`
- `TableDiff`
- `ColumnDiff`
- `ChangeType` oder aehnliche Change-Kategorien
- Comparator fuer `SchemaDefinition -> SchemaDiff`

Vergleichs-Scope fuer 0.5.0:

- Schema-Name und Version
- Tabellen hinzugefuegt/entfernt
- Spalten hinzugefuegt/entfernt/geaendert
- Primary Keys
- Foreign Keys
- Unique-Constraints
- Indizes
- Views, soweit bereits im neutralen Modell enthalten und im YAML-Pfad genutzt

Nicht Ziel von 0.5.0:

- optimale "minimal change set"-Berechnung fuer spaetere Migrationen
- Destructive-Change-Safety-Bewertung
- automatische Ableitung von `ALTER TABLE`-Operationen

Wichtige Regeln:

- Ergebnisreihenfolge ist deterministisch
- Aenderungen werden in fachlicher, nicht parserbedingter Reihenfolge sortiert
- Collections werden vor dem Vergleich normalisiert, um Reihenfolgenrauschen zu
  vermeiden

Tests:

- identische Schemas -> leeres Diff
- Tabellen/Spalten hinzugefuegt, entfernt, geaendert
- rein kosmetische Reihenfolgeaenderung -> kein Diff
- Fixture-basierter Vergleich mit `minimal.yaml` und `e-commerce.yaml`

### Phase C - CLI `schema compare`

Ziel: Compare als normales CLI-Command im vorhandenen Runner-Muster.

Betroffene Dateien/Module:

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`
- neues Runner-Paar in `hexagon/application`
- Tests in `adapters/driving/cli/src/test/...`

Geplantes Design:

- `SchemaCompareCommand` als duenne Clikt-Schale
- `SchemaCompareRunner` in `hexagon:application`
- Dateilesen ueber injizierte Reader-Lambdas analog zu `SchemaGenerateRunner`
- Ausgabe ueber Plain-Text-Default und `--output-format json|yaml`

CLI-Vertrag fuer 0.5.0:

```bash
d-migrate schema compare --source schema-a.yaml --target schema-b.yaml
```

Semantik:

- Exit `0`: keine Unterschiede
- Exit `1`: Unterschiede gefunden
- Exit `2`: ungueltige CLI-Kombination
- Exit `3`: Schema ungueltig
- Exit `7`: Datei-/Parse-/I/O-Fehler

Ausgabe:

- Plain-Text auf stdout fuer Menschen
- JSON/YAML ueber globales `--output-format`
- `--output <path>` schreibt dieselbe Darstellung in eine Datei
- stderr bleibt fuer Fehler und ggf. Progress reserviert

Tests:

- Runner-Unit-Tests fuer Exit-Code-Mapping
- CLI-Tests fuer `0` und `1`
- Snapshot- oder Golden-Tests fuer menschenlesbare Diff-Ausgabe
- JSON/YAML-Ausgabe mit stabilem Feldschema

### Phase D - MVP-Fortschrittsanzeige

Ziel: Bestehende langlaufende Commands bekommen ein einheitliches
Progress-Konzept, ohne Skriptfaehigkeit oder Testbarkeit zu verlieren.

Primaere Kandidaten in 0.5.0:

- `data export`
- `data import`

Sekundaer:

- spaetere Wiederverwendung fuer `schema reverse`, `schema migrate`, `data seed`

Geplante Architektur:

- application-naher `ProgressEvent`-Typ
- injizierbarer `ProgressReporter`/Sink statt direkter CLI-Ausgabe aus dem
  Fachpfad
- CLI-seitiger Renderer, der:
  - unter `--quiet` nichts ausgibt
  - unter `--no-progress` nur Summaries/Errors laesst
  - sonst Events auf stderr rendert

MVP-Scope:

- Tabellenstart/-ende
- Zeilenzaehler pro Tabelle oder pro Chunk
- zusammenfassende Zwischenstaende fuer Multi-Table-Laeufe

Nicht Ziel von 0.5.0:

- ANSI-Progress-Bar mit Cursor-Rewrite als Pflicht
- exakte ETA fuer Import ohne billige Totals
- JSON-Event-Streaming fuer alle Commands

Tests:

- Runner-Tests fuer Event-Emission
- CLI-Tests fuer `--quiet` und `--no-progress`
- regressionssichere Tests, dass bestehende End-Summaries erhalten bleiben

### Phase E - Dokumentation und Release Packaging

#### E.1 Dokumentations-Set

0.5.0 baut keine komplett neue Doku-Site, sondern schliesst MVP-Luecken im
bestehenden Set:

- `README.md` als Einstieg fuer Installation und Quick Start
- `docs/guide.md` als Basis-Anwenderhandbuch
- `docs/cli-spec.md` als Referenz der implementierten Commands
- `docs/releasing.md` fuer Maintainer

Zusatz fuer 0.5.0:

- Fokus auf Installations-, Quick-Start- und Referenzdokumentation
- keine Beispielprojekt-Auslieferung in 0.5.0; `examples/e-commerce/` ist nach
  0.6.0 verschoben

#### E.2 GitHub Releases

Geplante Release-Artefakte:

- OCI-Image (bestehend)
- CLI-Distribution als `.zip` und `.tar`
- Fat JAR als einzelnes Download-Artefakt
- SHA256-Datei fuer alle Release-Assets

Technische Schritte:

- CLI-Modul um `shadowJar` (oder aequivalenten Fat-JAR-Task) erweitern
- Tag-Workflow erweitert Artefakt-Build:
  - `:adapters:driving:cli:shadowJar`
  - `:adapters:driving:cli:distZip`
  - `:adapters:driving:cli:distTar`
- SHA256-Datei wird aus genau diesen Artefakten erzeugt
- Tag-Workflow laedt ZIP/TAR/Fat-JAR/SHA256 als Workflow-Artefakte hoch
- `docs/releasing.md` wird erweitert:
  - wie diese Artefakte vor Release-Erstellung lokal oder aus dem Workflow
    bezogen werden
  - wie `gh release create` bzw. `gh release upload` sie anhaengt
  - wie nach dem Publish die Asset-URLs fuer Homebrew verwendet werden

#### E.3 Homebrew-Basis

0.5.0 liefert keine vollautomatische Tap-Verwaltung, aber eine belastbare
Grundlage:

- neuer Pfad `packaging/homebrew/`
- erste reale Formula `d-migrate.rb`, kein bloßes Template
- Installation konsumiert ein publiziertes GitHub-Release-Artefakt
- SHA256 wird aus dem veroeffentlichten Asset uebernommen
- `docs/releasing.md` beschreibt den manuellen Versions-/SHA-Bump
- Release-Verifikation enthaelt einen echten `brew install --formula`-Smoke-Test

---

## 6. Betroffene Dateien und Module

Voraussichtlich betroffen:

- `hexagon/core`
  - neues Diff-Modell und Comparator
- `hexagon/application`
  - `SchemaCompareRunner`
  - Progress-Event/Reporter-Vertrag
- `adapters/driving/cli`
  - `SchemaCompareCommand`
  - CLI-Progress-Rendering
  - Build-Setup fuer Fat JAR
- `docs/cli-spec.md`
- `docs/guide.md`
- `docs/releasing.md`
- `docs/roadmap.md`
- `README.md`
- `.github/workflows/build.yml`
- `packaging/homebrew/`

---

## 7. Akzeptanzkriterien

- `schema compare` ist fuer zwei Schema-Dateien produktiv nutzbar.
- `docs/roadmap.md` und `docs/cli-spec.md` markieren 0.5.0 explizit als
  file-based MVP-Teilabdeckung von LF-015; vollstaendiger Umgebungsvergleich
  bleibt 0.6.0.
- Exit `0` und Exit `1` sind fuer "identisch" vs. "unterschiedlich" stabil und
  in Tests abgesichert.
- Die Diff-Ausgabe ist deterministisch und fuer Scripting als JSON/YAML
  nutzbar.
- `--quiet` und `--no-progress` behalten ihre bisherige Bedeutung; bestehende
  Commands regressieren nicht.
- Tag-Builds validieren ZIP/TAR/Fat-JAR und erzeugen daraus Workflow-Artefakte
  plus SHA256-Datei; der Release-Prozess publiziert dieselben Dateien als
  GitHub-Release-Assets.
- Eine reale Homebrew-Formula liegt im Repo, zeigt auf ein publiziertes
  Release-Asset und ist mindestens einmal per `brew install --formula`
  verifiziert.
- Die Coverage bleibt mindestens auf dem Roadmap-Ziel von 80 Prozent; bereits
  vorhandene strengere Gates werden nicht abgesenkt.

---

## 8. Verifikation

Die Verifikation orientiert sich weiter am Docker-basierten Build-/Test-Flow
aus `README.md`.

1. Gezielter Diff-/CLI-Testlauf:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :hexagon:application:test :adapters:driving:cli:test --rerun-tasks" \
  -t d-migrate:mvp-tests .
```

2. Vollstaendiger Build inkl. CLI-Distribution und Release-Artefakten:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist :adapters:driving:cli:distZip :adapters:driving:cli:distTar :adapters:driving:cli:shadowJar --rerun-tasks" \
  -t d-migrate:mvp-build .
```

3. Artefakt-Pruefung in der Build-Stage:

```bash
docker create --name d-migrate-mvp-assets d-migrate:mvp-build
docker cp d-migrate-mvp-assets:/src/adapters/driving/cli/build/distributions ./dist
docker cp d-migrate-mvp-assets:/src/adapters/driving/cli/build/libs ./libs
docker rm d-migrate-mvp-assets
sha256sum dist/* libs/*-all.jar > d-migrate-0.5.0-local.sha256
```

4. Vollstaendiger Runtime-Build:

```bash
docker build -t d-migrate:mvp .
```

5. Smoke-Test fuer Compare:

```bash
docker run --rm \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/work" \
  d-migrate:mvp \
  schema compare --source /work/minimal.yaml --target /work/minimal.yaml
```

6. Release-Asset-Publish-Probe:

```bash
gh release create v0.5.0-rc1 \
  --draft \
  --title "v0.5.0-rc1" \
  dist/* libs/*-all.jar d-migrate-0.5.0-local.sha256
```

7. Homebrew-Verifikation auf einem Host mit `brew`:

```bash
brew install --formula ./packaging/homebrew/d-migrate.rb
d-migrate --help
```

---

## 9. Risiken und offene Fragen

### 9.1 Diff-Tiefe vs. MVP-Termin

Die groesste Gefahr ist ein zu ehrgeiziges Diff-Modell. Fuer 0.5.0 ist ein
stabiles, brauchbares Compare wichtiger als ein perfekter Vorlaeufer fuer
vollautomatische Migrationen.

### 9.2 Fortschrittsanzeige ohne Totals

Import und Export arbeiten streamingbasiert. Exakte Prozentwerte und ETA sind
nicht fuer jeden Pfad billig verfuegbar. Der MVP muss notfalls ehrlichere,
einfachere Progress-Events bevorzugen.

### 9.3 Release-Artefakt-Doppelung

`installDist`, `distZip`, Fat JAR und OCI-Image duerfen nicht in vier
widerspruechliche Packaging-Wege auseinanderlaufen. 0.5.0 braucht einen
klaren "one source of truth"-Build fuer die CLI.

### 9.4 Homebrew-Tap-Ownership

Zu klaeren ist, ob die Formula im Hauptrepo nur als Template liegt oder ob
fuer echte `brew install`-Nutzung ein separates Tap-Repo gepflegt wird. Fuer
0.5.0 reicht eine Basis im Hauptrepo; volle Automatisierung ist optional.

---

## 10. Aufgeloeste Scope-Entscheidungen

- `schema compare` in 0.5.0 ist Datei-zu-Datei, nicht DB-zu-DB.
- Die volle LF-015-Abdeckung "Vergleich zwischen Umgebungen" bleibt 0.6.0 mit
  `SchemaReader`; 0.5.0 liefert den file-based MVP-Slice.
- `schema migrate` und `schema rollback` sind nicht Teil dieses Milestones.
- Fortschrittsanzeige in 0.5.0 ist MVP-pragmatisch und nicht an Prozent/ETA
  um jeden Preis gekoppelt.
- Homebrew in 0.5.0 ist eine reale installierbare Formula-Basis, aber noch kein
  automatisierter Tap-Workflow.
- Das Roadmap-Ziel "Coverage >= 80%" ist eine Untergrenze; bestehende staerkere
  Module-Gates bleiben bestehen.
