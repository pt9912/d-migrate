# Implementierungsplan: Phase F - Integrationsschnitt fuer `source-d-migrate` absichern

> **Milestone**: 0.9.1 - Library-Refactor und Integrationsschnitt
> **Phase**: F (Integrationsschnitt fuer `source-d-migrate` absichern)
> **Status**: Done (2026-04-18) — ohne Phase-C-Modulschnitt (formats
> exponiert Writer-API; Modulschnitt erfordert formats-Split, auf
> spaeteres Milestone verschoben)
> **Referenz**: `docs/implementation-plan-0.9.1.md` Abschnitt 1 bis 5,
> Abschnitt 6.6, Abschnitt 7, Abschnitt 8 und Abschnitt 9;
> `docs/d-browser-integration-coupling-assessment.md`;
> `docs/architecture.md`;
> `docs/hexagonal-port.md`;
> `docs/roadmap.md`;
> `docs/test-database-candidates.md`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/SchemaReader.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/SchemaReadReportInput.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaReverseRunner.kt`.

---

## 1. Ziel

Phase F zieht den fachlichen Integrationsschnitt fuer einen spaeteren
`source-d-migrate`-Adapter klar fest, nachdem die technischen
Entkopplungen aus Phase C bis E den Read-Pfad bereits verschlankt haben.

Der Teilplan beantwortet bewusst zuerst die Integrations- und
Vertragsfragen:

- welche `d-migrate`-Typen und Module fuer einen read-only Consumer
  als stabile Integrationsflaeche taugen
- welche Toolmodelle bewusst intern bleiben sollen
- wie `SchemaReadResult` in einem Adapterraum projiziert werden soll,
  ohne den Core-Vertrag vorschnell umzubauen
- wie Datenlesen, optionale Diagnostics und FK-/Topo-Sort-Reuse fuer
  einen `source-d-migrate`-Pfad zusammenspielen
- wie dieser Schnitt lokal oder intern verifiziert werden kann, obwohl
  im Repo noch kein produktiver `source-d-migrate`-Adapter existiert

Phase F liefert damit keinen neuen Endnutzervertrag und keinen im Repo
ausgerollten `d-browser`-Adapter, sondern einen nachvollziehbaren,
verifizierten Konsumschnitt fuer externe Read-Consumer.

Nach Phase F soll klar gelten:

- externe Read-Consumer wissen, gegen welche `d-migrate`-Flachen sie
  bauen duerfen
- write-, CLI- und Report-spezifische Toolmodelle werden nicht als
  Integrations-API missverstanden
- `SchemaReadResult` bleibt Core-seitig unveraendert, wird aber im
  Adapterraum bewusst projiziert
- mindestens eine lokale oder interne Konsumentenprobe zeigt, dass der
  schmalere Schnitt fuer einen `source-d-migrate`-Pfad tatsaechlich
  konsumierbar ist

---

## 2. Ausgangslage

Die aktuelle Integrationslage ist gemischt:

- das Ziel eines spaeteren `source-d-migrate`-Adapters ist im
  0.9.1-Masterplan und im `d-browser`-Assessment klar benannt
- im Repo existiert aber **noch kein** produktiver
  `source-d-migrate`-Adapter
- das bestehende Modul `adapters:driven:integrations` enthaelt heute
  Migrations-Exporter fuer Flyway, Liquibase, Django und Knex, nicht
  einen Read-Consumer fuer `d-browser`
- `DatabaseDriver` ist weiterhin eine gemischte Top-Level-Fassade mit
  Read-, Write- und DDL-Pfaden
- `SchemaReader` liefert mit `SchemaReadResult` bewusst einen Envelope
  aus:
  - `schema`
  - `notes`
  - `skippedObjects`
- `SchemaReadResult` ist damit fuer Reverse- und Diagnosepfade sinnvoll,
  fuer `d-browser` als reinen Source-Consumer aber oft breiter als
  noetig
- ein erstes Muster fuer adapterseitige Projektion existiert bereits:
  `SchemaReadReportInput` und `ReverseSourceRef` tragen source- bzw.
  report-spezifischen Kontext **neben** `SchemaReadResult`, statt den
  Core-Typ selbst zu erweitern
- nach Phase C ist der Read-Pfad fachlich schlanker:
  - der Optionsschnitt (Phase-C-Kern) trennt Read- von Write-Optionen
  - der Modulschnitt (Phase-C-Stretch-Goal) wuerde zusaetzlich ein
    eigenes `hexagon:ports-read`-Modul liefern; ob dieses Modul bei
    Phase-F-Start existiert, ist offen
- nach Phase D sind Profiling-Module fuer Read-Consumer optional
  gedacht und gehoeren nicht zum Default-Schnitt eines
  `source-d-migrate`-Adapters
- nach Phase E steht eine kleine FK-/Topo-Sort-Utility fuer
  Tabellenabhaengigkeiten zur Wiederverwendung bereit oder ist als
  unmittelbar vorgelagerte Basis geplant

Wichtige Konsequenzen:

- der eigentliche offene Punkt ist nicht mehr primaer technische
  Kopplung, sondern **Integrationsklarheit**
- ohne explizite Phase-F-Doku droht, dass externe Consumer:
  - gegen `DatabaseDriver` statt gegen den schmaleren Read-Schnitt bauen
  - `SchemaReadResult` ungefiltert in ihre Fachmodelle leaken
  - CLI-/Runner-/Checkpoint-Typen als vermeintliche Library-API
    missverstehen

---

## 3. Scope fuer Phase F

### 3.1 In Scope

- explizit dokumentieren, welche Flachen fuer einen spaeteren
  `source-d-migrate`-Adapter als stabile Integrationsflaeche gelten
- explizit dokumentieren, welche Toolmodelle bewusst intern bleiben
- Projektion von `SchemaReadResult` im Adapterraum als verbindliches
  Muster festhalten
- Integrationsverhaeltnis zwischen:
  - Read-Port(s)
  - neutralem Schema-Modell
  - optionaler Diagnostics-Uebernahme
  - optionaler FK-/Topo-Sort-Reuse
  beschreiben
- lokale oder interne Konsumentenprobe fuer den schmaleren
  Integrationsschnitt festlegen
- Doku-Angleich fuer Architektur-, Roadmap- und Integrationsdokumente

### 3.2 Bewusst nicht Teil von Phase F

- produktive Implementierung eines `source-d-migrate`-Adapters im Repo
- `d-browser`-seitige Fachmodelle oder UI-Vertraege
- Aufspaltung oder Redesign von `SchemaReadResult`
- neuer oeffentlicher Publish-Vertrag
- neue CLI-Kommandos fuer externe Consumer
- Umbau der bestehenden Exporter in `adapters:driven:integrations`
  zu einem `source-d-migrate`-Modul

Praezisierung:

Phase F loest zuerst "wie soll ein externer Read-Consumer sauber gegen
`d-migrate` integrieren?", nicht "wie sieht die komplette
Produktionsimplementierung in `d-browser` aus?".

---

## 4. Leitentscheidungen fuer Phase F

### 4.1 Der Integrationsschnitt ist read-only und bewusst schmal

Verbindliche Entscheidung:

- ein `source-d-migrate`-Adapter baut gegen den read-orientierten
  Schnitt, nicht gegen die voll gemischte `DatabaseDriver`-Fassade
- write-, import- und CLI-zentrierte Flachen sind nicht Teil des
  Zielvertrags fuer diesen Adapter

Folge:

- `DataWriter`, `TableImportSession`, `ImportOptions`,
  Checkpoint-/Resume-Typen und analoge Write-Welten gehoeren nicht in
  einen `source-d-migrate`-Defaultpfad

### 4.2 `SchemaReadResult` bleibt Core-Vertrag, Projektion bleibt Adapterarbeit

Verbindliche Entscheidung:

- `SchemaReader` liefert weiterhin `SchemaReadResult`
- ein `source-d-migrate`-Adapter projiziert daraus lokal sein
  konsumierbares Fachmodell
- mindestens `schema` wird fachlich uebernommen
- `notes` und `skippedObjects` werden:
  - entweder bewusst ignoriert
  - oder als optionaler Diagnostics-Kanal des Adapters modelliert

Nicht zulaessig ist:

- `SchemaReadResult` ungefiltert als externes Fachmodell auszugeben
- fuer einen einzelnen Consumer vorschnell einen zweiten Core-
  `SchemaReader`-Vertrag einzufuehren

### 4.3 Toolmodelle bleiben intern, auch wenn sie technisch sichtbar sind

Verbindliche Entscheidung:

- folgende Klassen- und Modellfamilien gelten fuer Phase F als intern
  bzw. nicht Teil des `source-d-migrate`-Integrationsvertrags:
  - Runner (`SchemaReverseRunner`, `DataExportRunner`, `DataImportRunner`,
    `DataTransferRunner`, `DataProfileRunner`)
  - CLI-DTOs und Clikt-Kommandos
  - Report-spezifische Wrapper und Renderer
  - Checkpoint-/Resume-/Manifest-Typen
  - DDL-Generator-spezifische Ergebniswelten
  - Profiling-Adapter und Profiling-Services im Default-Readpfad

Praezisierung:

- `SchemaReverseRunner` nutzt intern
  `driverLookup(dialect).schemaReader()` und baut damit auf der
  gemischten `DatabaseDriver`-Fassade auf; dieses Wiring ist ein
  internes CLI-Tool-Muster und dient **nicht** als Referenz fuer den
  kuenftigen `source-d-migrate`-Integrationspfad
- ein externer Read-Consumer soll `SchemaReader` direkt ueber den
  Read-Port-Schnitt beziehen, nicht ueber `DatabaseDriver`

Folge:

- Phase F dokumentiert **nicht** jede sichtbare Klasse als
  Konsumschnitt
- "technisch importierbar" ist nicht gleich "stabile Integrations-API"

### 4.4 Der bestehende Wrapper-Stil ist das richtige Muster fuer Adapterkontext

Verbindliche Entscheidung:

- source- oder consumer-spezifischer Kontext wird im Adapterraum ueber
  kleine Wrapper/Projektionen getragen
- `SchemaReadReportInput` ist dafuer ein bestehendes repo-internes
  Beispielmuster: Kontext wird neben `SchemaReadResult` getragen, nicht
  in den Core-Typ hineingezogen

Damit bleibt der Core-Vertrag stabil, waehrend der Adapter seinen
eigenen Kontext anreichern darf.

### 4.5 Profiling ist fuer `source-d-migrate` optional und nicht Teil des Basisschnitts

Verbindliche Entscheidung:

- nach Phase D gehoeren Profiling-Module nicht zum Defaultvertrag eines
  read-only Source-Adapters
- wenn ein externer Consumer spaeter Profiling-Daten verwenden will,
  geschieht das bewusst als separater optionaler Erweiterungspfad

### 4.6 FK-/Topo-Sort-Reuse bleibt ein Hilfswerkzeug, nicht Kern der Adapter-API

Verbindliche Entscheidung:

- nach Phase E darf ein `source-d-migrate`-Adapter die extrahierte
  Tabellenabhaengigkeits-Utility lokal nutzen
- diese Utility ist aber Hilfswerkzeug fuer Reihenfolge und
  Zyklusdiagnostik, nicht der primaere Integrationsvertrag des Adapters

### 4.7 Verifikation erfolgt ueber Konsumentenprobe, nicht ueber Publish

Verbindliche Entscheidung:

- Phase F braucht mindestens eine lokale oder interne
  Konsumentenprobe
- dafuer ist **kein** oeffentlicher Maven-Publish in 0.9.1 noetig
- die Probe darf als interner Fixture, Sample oder compile-nahe
  Teststruktur ausgefuehrt werden

### 4.8 Konsumentenprobe passt sich an das Phase-C-Ergebnis an

Verbindliche Entscheidung:

- Phase F setzt den Phase-C-Kern (Optionsschnitt) voraus, nicht
  zwingend den Phase-C-Stretch-Goal (Modulschnitt)
- der konkrete Build-Zuschnitt der Konsumentenprobe haengt davon ab,
  ob `hexagon:ports-read` bei Phase-F-Start existiert:
  - **mit Modulschnitt**: die Probe baut gegen `hexagon:ports-read`
    plus `adapters:driven:formats` und prueft, dass
    `hexagon:ports-write` nicht transitiv im Compile-Graph landet;
    sie erweitert oder ersetzt den Phase-C-Consumer-Fixture um
    fachliche Aspekte (Schema-Lesen, Projektion)
  - **ohne Modulschnitt**: die Probe baut gegen `hexagon:ports`
    (Aggregator) und dokumentiert den Integrationsschnitt rein
    ueber Import-Konventionen und Negativliste; der Build erzwingt
    die Trennung dann nicht, aber die Doku macht sie explizit
- in beiden Varianten muss die Probe zeigen, dass ein read-only
  Consumer ohne Write-, CLI- oder Profiling-Defaultpfade auskommt

### 4.9 Die Konsumentenprobe lebt in einem eigenen Testmodul

Verbindliche Entscheidung:

- die Probe wird als eigenes Gradle-Modul angelegt (Arbeitsname:
  `test:consumer-read-probe`)
- sie lebt bewusst nicht in `adapters:driven:integrations`, weil
  dieses Modul fuer Migrations-Exporter reserviert ist
- `settings.gradle.kts` wird entsprechend erweitert
- das Modul hat keine eigene modul-lokale `koverVerify`-Schwelle
- das Modul wird in die Root-`build.gradle.kts`-Kover-Aggregation
  aufgenommen; da die Root-Aggregation einen globalen 90%-Gate hat
  (`kover { reports { verify { rule { minBound(90) } } } }`),
  beeinflusst das Modul diesen Gate mit — dieser Seiteneffekt wird
  bewusst akzeptiert, weil die Probe nur wenig Code und wenig
  Coverage-Denominator beisteuert

---

## 5. Konkrete Arbeitspakete

Abhaengigkeiten und Reihenfolge:

1. **5.1** zieht stabile vs. interne Flachen fest
2. **5.2** beschreibt die Adapterprojektion fuer `SchemaReadResult`
3. **5.3** definiert und baut die Konsumentenprobe
4. **5.4** zieht Architektur- und Roadmap-Doku nach

### 5.1 Integrationsflaeche fuer `source-d-migrate` festziehen

- die fuer einen spaeteren Adapter vorgesehenen stabilen Flachen
  explizit benennen, voraussichtlich:
  - read-orientierte Portvertraege aus Phase C
  - neutrales Schema-Modell aus `hexagon:core`
  - `SchemaReader` / `SchemaReadOptions` / `SchemaReadResult`
  - `DataReader` und zugehoerige read-orientierte Datentypen, soweit
    der Adapter auch Datenstrom-Faelle tragen soll
  - optionale FK-/Topo-Sort-Utility aus Phase E als Hilfswerkzeug
- bewusst interne Flachen explizit ausschliessen:
  - `DatabaseDriver` als gemischte Vollflaeche
  - Write-/Import-Typen
  - Runner, CLI, Report-Writer, Resume-/Checkpoint-Typen
  - Profiling-Module im Default-Readpfad

Ergebnis:

Es gibt eine klare Liste von "dafuer ja" und "dafuer nein" statt nur
impliziter Architekturabsicht.

### 5.2 Projektion von `SchemaReadResult` im Adapterraum beschreiben

- ein kleines Referenzmuster fuer Adapterprojektion dokumentieren:
  - `schema` als fachliche Pflichtnutzlast
  - `notes` und `skippedObjects` als optionaler Diagnostics-Kanal
- festhalten, dass der Adapter seine eigenen Projektionstypen fuehren
  darf, z. B.:
  - `SourceSchemaSnapshot`
  - `SourceReadDiagnostics`
  - oder ein gleichwertiger consumerseitiger Typ
- dokumentieren, dass `SchemaReadResult` nicht aufgespalten und nicht
  direkt als `d-browser`-Domainmodell verwendet wird

Ergebnis:

Der Integrationsschnitt ist nicht nur benannt, sondern fachlich
benutzbar beschrieben.

### 5.3 Konsumentenprobe als eigenes Testmodul aufsetzen

- ein neues Gradle-Modul `test:consumer-read-probe` anlegen
  (Leitentscheidung 4.9)
- `settings.gradle.kts` und Root-`build.gradle.kts`
  (Kover-Aggregation) entsprechend erweitern
- Abhaengigkeitsschnitt je nach Phase-C-Ergebnis
  (Leitentscheidung 4.8):
  - **mit Modulschnitt**: `testImplementation(":hexagon:ports-read")`
    plus `testImplementation(":adapters:driven:formats")`; Build
    prueft, dass `hexagon:ports-write` nicht transitiv sichtbar ist
  - **ohne Modulschnitt**: `testImplementation(":hexagon:ports")`
    plus `testImplementation(":adapters:driven:formats")`;
    Integrationsschnitt wird ueber Import-Konventionen und einen
    expliziten Compile-Check-Kommentar im Test abgesichert
- in beiden Varianten muss die Probe mindestens zeigen:
  - Schema-Lesen ueber den vorgesehenen Integrationsschnitt
  - lokale Projektion von `SchemaReadResult`
  - keine Abhaengigkeit auf Write-, CLI- oder Profiling-Defaultpfade
- falls der Phase-C-Stretch-Goal-Fixture bereits existiert, erweitert
  die Phase-F-Probe diesen um fachliche Aspekte (Schema-Lesen,
  Projektion), statt ein separates Artefakt zu duplizieren
- als Datenbasis kann ein kleiner realistischer Smoke-Fall verwendet
  werden, z. B. ein lokales Pagila-/Sakila-nahes Schema oder ein
  gleichwertiger interner Testfall

Ergebnis:

Phase F bleibt nicht bei Dokuabsicht stehen, sondern belegt den
Integrationsschnitt praktisch.

### 5.4 Architektur- und Integrationsdoku angleichen

- `docs/architecture.md` so nachziehen, dass der externe
  Integrationsschnitt nicht mit Runner-/CLI-Wiring vermischt wird
- `docs/roadmap.md` so angleichen, dass 0.9.1 als Integrations- und
  Refactor-Meilenstein, nicht als Publish-Vertrag, klar bleibt
- `docs/d-browser-integration-coupling-assessment.md` ggf. auf den
  konkretisierten Phase-F-Zuschnitt verweisen
- falls hilfreich, einen kurzen Integrationsabschnitt in `docs/guide.md`
  oder einem gleichwertigen Architekturtext ergaenzen

Ergebnis:

Die Integrationsgrenze ist fuer spaetere Consumer nicht nur in
Teilplaenen, sondern in den zentralen Architekturdokumenten sichtbar.

---

## 6. Verifikation

Pflichtfaelle:

- Doku benennt explizit:
  - stabile Integrationsflaechen
  - bewusst interne Toolmodelle
  - Projektion von `SchemaReadResult` im Adapterraum
- mindestens eine Konsumentenprobe (`test:consumer-read-probe`), die
  gegen den dokumentierten Read-Only-Integrationsschnitt baut:
  - **mit Phase-C-Modulschnitt**: Probe baut gegen
    `hexagon:ports-read`; der Build prueft, dass
    `hexagon:ports-write` nicht transitiv sichtbar ist
  - **ohne Phase-C-Modulschnitt**: Probe baut gegen `hexagon:ports`
    und sichert den Integrationsschnitt ueber Import-Konventionen
    ab; die Build-Erzwingung der Read-/Write-Trennung entfaellt
    dann bewusst (Leitentscheidung 4.8)
- in beiden Varianten zeigt die Probe, dass:
  - `SchemaReadResult` lokal projiziert wird
  - kein Defaultbedarf fuer Write-Typen besteht
  - Profiling nicht transitiv zum Basispfad gehoert

Erwuenschte Zusatzfaelle:

- eine kleine Smoke-Verifikation gegen eine realistische Beispieldatenbank
  wie Pagila oder Sakila

---

## 7. Betroffene Codebasis

Direkt betroffen:

- `docs/implementation-plan-0.9.1.md`
- `docs/d-browser-integration-coupling-assessment.md`
- `docs/architecture.md`
- `docs/roadmap.md`
- `settings.gradle.kts` (neues Modul `test:consumer-read-probe`)
- `build.gradle.kts` (Root — Kover-Aggregationsliste)
- neues Modul `test/consumer-read-probe/` mit `build.gradle.kts`
  und Testquellen

Wahrscheinlich mit betroffen:

- `docs/guide.md` oder ein gleichwertiger Nutzer-/Architekturtext
- ggf. kleinere Referenzstellen rund um `SchemaReadResult`- und
  Reverse-Dokumentation
- falls Phase-C-Stretch-Goal-Fixture bereits existiert: dessen
  Erweiterung statt neuem Modul

Nicht primaer betroffen:

- produktive Treiberimplementierungen
- `SchemaReadResult` selbst
- Profiling- oder Write-Pfade, ausser als bewusst ausgeschlossene
  Integrationsflaechen

---

## 8. Risiken und offene Punkte

### 8.1 Ohne explizite Negativliste wird die falsche API konsumiert

Wenn Phase F nur sagt "diese Flachen sind gut", aber nicht klar
ausgrenzt, welche Typen **nicht** zum Integrationsvertrag gehoeren,
drohen externe Consumer weiter gegen Runner-, CLI- oder
Write-Welten zu bauen.

Mitigation:

- stabile und interne Flachen explizit getrennt dokumentieren

### 8.2 `SchemaReadResult` kann als "eigentlich schon Consumer-DTO" missverstanden werden

Weil `SchemaReadResult` im Portmodul lebt und fachlich nuetzlich ist,
liegt die Versuchung nahe, ihn ungefiltert bis in ein externes
Fachmodell durchzureichen.

Mitigation:

- Adapterprojektion verbindlich dokumentieren
- Diagnostics als optionale Adapterantwort statt als Core-Umbau
  behandeln

### 8.3 Ein Repo-interner Fixture kann wie ein offizieller Adapter wirken

Wenn eine Konsumentenprobe zu produktionsartig benannt oder platziert
wird, koennte sie als bereits supportete Integrationsloesung
missverstanden werden.

Mitigation:

- Probe klar als intern, lokal oder exemplarisch kennzeichnen
- keinen oeffentlichen Publish- oder Produktanspruch daraus ableiten

### 8.4 Integrationsschnitt kann nach C/D/E wieder verwischt werden

Ohne klare Phase-F-Doku koennte spaetere Arbeit erneut gemischte
Flachen wie `DatabaseDriver` oder optionale Profiling-Welten in den
Read-Defaultpfad ziehen.

Mitigation:

- Phase F explizit auf den Ergebnissen von C/D/E aufbauen
- Read-Defaultpfad von Write-/Profiling-Erweiterungen klar trennen

### 8.5 Verifikation ohne realistische Daten kann zu theoretisch bleiben

Eine rein abstrakte compile-nahe Probe zeigt nur begrenzt, ob der
Schnitt fuer einen echten Source-Consumer brauchbar ist.

Mitigation:

- wenn moeglich einen kleinen realistischen Schema-Smoke-Fall nutzen
- Testdatenkandidaten aus `docs/test-database-candidates.md`
  beruecksichtigen

### 8.6 Ohne Phase-C-Modulschnitt ist die Build-Absicherung schwaecher

Falls Phase C nur den Kern (Optionsschnitt) liefert, gibt es kein
`hexagon:ports-read`-Modul. Die Konsumentenprobe kann dann die
Read-/Write-Trennung nicht im Build erzwingen, sondern nur per
Dokumentation und Import-Konvention absichern.

Mitigation:

- Leitentscheidung 4.8 definiert beide Varianten explizit
- auch ohne Modulschnitt liefert Phase F einen dokumentierten und
  getesteten Integrationsschnitt, der fuer einen spaeteren
  `source-d-migrate`-Adapter als Vorlage dient
- der Modulschnitt (Phase-C Stretch Goals 5.4-5.5) wird als Teil von
  Phase F umgesetzt, nicht als separates Phase C2: er betrifft den
  Integrationsschnitt direkt und gehoert fachlich hierher
- Voraussetzung: `adapters:driven:formats` muss entweder in read-/
  write-Teilmodule gesplittet oder mit `api(ports-write)` gebaut
  werden, weil es oeffentliche Writer-Klassen exponiert
  (Finding aus Phase-C-Review)

---

## 9. Entscheidungsempfehlung

Phase F sollte in 0.9.1 umgesetzt werden, weil der technische Refactor
aus C bis E erst dann fuer externe Consumer wirklich nutzbar wird, wenn
sein Integrationsschnitt klar dokumentiert und praktisch belegt ist.

Empfohlener Zuschnitt:

1. stabile vs. interne Flachen fuer `source-d-migrate` explizit
   festziehen
2. `SchemaReadResult`-Projektion verbindlich im Adapterraum verorten
3. Konsumentenprobe als eigenes Testmodul
   (`test:consumer-read-probe`) aufsetzen, mit Build-Zuschnitt je
   nach Phase-C-Ergebnis (Leitentscheidung 4.8)
4. Architektur-, Roadmap- und Integrationsdoku konsistent nachziehen

Damit liefert Phase F einen nachvollziehbaren Integrationsschnitt fuer
`d-browser`, ohne bereits einen vorschnellen Produktionsadapter oder
oeffentlichen Publish-Vertrag auszurollen.
