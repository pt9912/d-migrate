# Implementierungsplan: Milestone 0.9.1 - Library-Refactor und Integrationsschnitt

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.9.1. Es dient als laufend gepflegte Spezifikation und
> Review-Grundlage fuer die Entkopplung von `d-migrate` als wiederverwendbare
> Library-Basis.
>
> Status: Draft (2026-04-16)
> Referenzen: `docs/planning/roadmap.md` Milestone 0.9.1, 0.9.2 und 1.0.0,
> `spec/architecture.md` Moduluebersicht und Abhaengigkeiten,
> `spec/hexagonal-port.md` Abschnitt `3.2 hexagon:ports`,
> `docs/planning/d-browser-integration-coupling-assessment.md`,
> `docs/planning/ddl-output-split-plan.md`,
> `docs/user/releasing.md` als aktueller Ist-Stand fuer Release-Automation.

---

## 1. Ziel

Milestone 0.9.1 schiebt keinen neuen Endnutzer-Feature-Vertrag nach, sondern
macht die innere Modul- und Portstruktur von `d-migrate` sauberer fuer externe
Consumer wie `d-browser`. Ein zweites, gleichrangiges Ziel ist die
Sicherheits-Haertung, die aus dem Code-Quality-Review
(`docs/user/quality.md`) hervorgegangen ist — sie wird bewusst **vor** dem
1.0.0-Publish-Vertrag abgearbeitet.

Der Milestone liefert:

- eine Sicherheits-Haertung der Profiling-/Introspection-Adapter und der
  bewusst offenen Raw-SQL-Grenzen (`docs/user/quality.md` Findings „Hoch" und
  „Mittel")
- eine Zerlegung der grossen Orchestrierungs- und Dialekt-Klassen
  (`DataImportRunner`, `DataExportRunner`, `StreamingImporter`,
  `SchemaComparator`, die drei DDL-Generatoren) in kleinere,
  verantwortungsstaerker geschnittene Dienste
- eine vorbereitende interne DDL-Modelltrennung, die einen spaeteren
  optionalen DDL-Output-Split (`pre-data`/`post-data`) tragen kann, ohne
  den bestehenden `schema generate`-Default in 0.9.1 zu veraendern
- eine klarere Read-/Write-Schnitt im Portbereich
- schlankere JDBC-Treiberkerne ohne transitive Profiling-Pflicht
- eine wiederverwendbare FK-/Topo-Sort-Basis statt mehrfacher Duplikation
- einen stabileren Integrationsschnitt fuer einen spaeteren
  `source-d-migrate`-Adapter

Der Milestone liefert bewusst nicht:

- einen öffentlichen Maven-Central-Publish-Vertrag
- einen GitHub-Workflow fuer Maven Central Portal
- bereits stabile Library-Koordinaten fuer externe Verbraucher
- den sichtbaren Endnutzer-Vertrag fuer den optionalen DDL-Output-Split;
  dieser bleibt Milestone 0.9.2 vorbehalten

0.9.1 ist damit ein Refactor-Milestone fuer konsumierbare Libraries, waehrend
die oeffentliche Distribution bewusst in 1.0.0 verbleibt.

---

## 2. Ausgangslage

Stand im aktuellen Repo:

- ein Code-Quality-Review (`docs/user/quality.md`) hat konkrete Sicherheits-
  Findings dokumentiert:
  - **Hoch**: Profiling-/Introspection-Adapter der drei Treiber
    interpolieren `table`/`column`/teils `schema` direkt in
    SQL-Strings (PG `PostgresSchemaIntrospectionAdapter:16`, MySQL
    `MysqlSchemaIntrospectionAdapter:33` und
    `MysqlProfilingDataAdapter:18`, SQLite
    `SqliteSchemaIntrospectionAdapter:35`). Das ist eine echte
    Injection-Flaeche, sobald diese Werte nicht ausschliesslich aus
    vertrauenswuerdigen Metadaten kommen.
  - **Mittel**: bewusst offene Raw-SQL-Schnittstellen —
    `DataExportHelpers.resolveFilter` nimmt `--filter` ungeprueft als
    `WhereClause`, und die DDL-Generatoren setzen
    `constraint.expression` direkt in CHECK/EXCLUDE. Vertragslage ist
    „Trusted Input", aber weder im CLI-Flag noch im Vertragsmodell
    sichtbar kommuniziert.
  - **Mittel**: Orchestrator- und Dialekt-Klassen sind zu gross und
    tragen zu viele Verantwortlichkeiten:
    `DataImportRunner` ~887 LOC, `DataExportRunner` ~923 LOC,
    `StreamingImporter` ~792 LOC, `SchemaComparator` ~656 LOC; die
    drei DDL-Generatoren (`PostgresDdlGenerator`, `MysqlDdlGenerator`,
    `SqliteDdlGenerator`) bundeln Views, Functions, Procedures,
    Trigger und Rewrite-Fallbacks in jeweils einer monolithischen
    Datei mit `-- TODO: …`-Platzhaltern statt strukturierter
    `ManualActionRequired`-Eintraege.
  - **Mittel**: Profiling-Adapter zwischen PG/MySQL/SQLite enthalten
    aehnliche Logik mit leicht unterschiedlichen Escape-Strategien —
    Sicherheitsfixes landen leicht in nur einem Dialekt.
- `hexagon:ports` enthaelt heute sowohl lese- als auch write-orientierte
  Vertraege
- `ImportOptions` haengt nicht nur am Writer-/Importpfad, sondern auch am
  Format-Lesepfad
- die JDBC-Treibermodule haengen direkt an `hexagon:profiling`, obwohl die
  Profiling-Nutzung in separaten `profiling/`-Paketen liegt
- FK-/Topo-Sort-Logik ist heute mehrfach vorhanden:
  - `AbstractDdlGenerator.topologicalSort(...)`
  - `ImportDirectoryResolver.topologicalSort(...)`
  - `DataTransferRunner.topoSort(...)`
- `SchemaReadResult` ist ein bewusst reverse-orientierter Envelope mit
  `schema`, `notes` und `skippedObjects`
- Release-Automation ist aktuell auf GitHub Release/Homebrew ausgelegt; ein
  Maven-Central-Publish-Workflow existiert noch nicht

Die Hauptaufgabe von 0.9.1 ist daher kein neues Produktfeature, sondern die
Reduktion technischer Kopplung vor einem spaeteren Stable-Publish.

---

## 3. Scope

### 3.1 In Scope fuer 0.9.1

- **Sicherheits-Haertung** (`docs/user/quality.md`):
  - zentrale Identifier-Quoting-Utility pro Dialekt; alle Profiling-
    und Introspection-Adapter bauen nur noch darueber
  - Metadaten-Literals ueber `PreparedStatement` binden statt
    interpolieren; Namen weiter ueber konsequentes Identifier-Quoting
  - `--filter` und `constraint.expression` als „Trusted Input"
    kennzeichnen (CLI-Flag + Vertrags-/KDoc-Ebene); optional Filter-
    DSL als kleinerer, sicherer Ersatzweg
  - Security-Tests mit boesartigen Tabellen-/Spaltennamen, die
    Escape-/Quoting-Fehler direkt sichtbar machen
- **Zerlegung grosser Orchestrierungs- und Dialekt-Klassen**:
  - `DataImportRunner` / `DataExportRunner` in verantwortungs-
    schaerfer geschnittene Dienste (z.B. `ImportRequestValidator`,
    `ImportSourceResolver`, `ResumeCoordinator`,
    `ManifestCoordinator`, `ImportExecutionService` bzw. analog
    fuer Export)
  - `StreamingImporter` / `StreamingExporter` in separate
    Orchestrator- und Tabellen-Pipeline-Units
  - `SchemaComparator` in kleinere Vergleichseinheiten (pro
    Objekttyp) trennen
  - DDL-Generatoren pro Objektart schneiden
    (`ViewDdlGenerator`, `FunctionDdlGenerator`,
    `ProcedureDdlGenerator`, `TriggerDdlGenerator`); `-- TODO: …`-
    Platzhalter intern durch strukturierte `ManualActionRequired`-
    Eintraege ersetzen; dabei einen internen Phasen-/Objektschnitt
    vorbereiten, der spaeteren `pre-data`/`post-data`-Output tragen
    kann, ohne in 0.9.1 bereits den bestehenden `schema generate`-
    Output-Vertrag zu aendern
  - Plan-/Milestone-Kommentare aus Produktionscode zurueck in
    `docs/`; im Code bleiben nur „why" + Invarianten stehen
- Refactor der Portoberflaeche fuer externe Lese-Consumer
- Trennung von read-orientierten und write-orientierten Options-/Porttypen,
  soweit dies fuer echte Entkopplung noetig ist
- Extraktion von Profiling-Adaptern aus JDBC-Treiber-Kernmodulen
- Extraktion einer wiederverwendbaren FK-/Topo-Sort-Utility
- Doku-Angleich fuer Roadmap, Architektur und Integrationsschnitt
- lokale oder interne Verifikation, dass `d-browser` bzw. ein
  `source-d-migrate`-Adapter den schmaleren Schnitt konsumieren kann

### 3.2 Bewusst nicht Teil von 0.9.1

- oeffentliches Maven-Central-Publishing
- GitHub Actions Workflow fuer Maven Central Portal
- GPG-/Signing-Setup fuer zentrale Artefaktveroeffentlichung
- verbindliche langfristige Maven-Koordinaten fuer alle Module
- Umbau von `SchemaReader` auf einen zweiten, schlanken Produktvertrag ohne
  konkreten Mehrfachbedarf
- sichtbare Aenderung des `schema generate`-Output-Vertrags
  (`pre-data`/`post-data`, `ddl_parts`, phasenbezogene Artefakte)

Begruendung:

Der Refactor soll erst die tatsaechliche Library-Grenze stabilisieren. Ein
frueher oeffentlicher Publish wuerde sonst instabile Modulzuschnitte und
Relocation-Themen veroeffentlichen, die kurz darauf wieder gebrochen werden
koennten.

---

## 4. Leitentscheidungen

### 4.1 0.9.1 stabilisiert den Integrationsschnitt, nicht den Distributionsvertrag

Verbindliche Entscheidung:

- 0.9.1 darf Modul- und Portzuschnitte noch gezielt bewegen
- 1.0.0 ist der erste Milestone, der daraus einen oeffentlichen
  Publish-Vertrag machen soll
- Release-Automation fuer Maven Central folgt deshalb erst nach dem Refactor

### 4.2 Read-/Write-Trennung muss fachlich sein, nicht nur paketkosmetisch

Verbindliche Entscheidung:

- ein reiner Interface-Split reicht nicht, solange read-only Pfade weiter an
  write-lastigen Optionsmodellen haengen
- Reader-/Schema-Lese-Consumer sollen ohne `DataWriter`-,
  `TableImportSession`- oder writer-zentrierte Optionen auskommen
- bestehende Import-/Writer-Funktionalitaet darf dadurch nicht semantisch
  geschwaecht werden

### 4.3 Treiber-Kernmodule sollen Profiling nicht transitiv exportieren

Verbindliche Entscheidung:

- JDBC-Treiberkerne tragen nur Schema-/Data-/DDL-Vertraege
- Profiling-Adapter werden in optionale Zusatzmodule verschoben
- externe Consumer duerfen Treiberkerne ohne `hexagon:profiling` einbinden

### 4.4 FK-/Topo-Sort-Reuse ist Core-Utility, nicht Baumprojektion

Verbindliche Entscheidung:

- `d-migrate` extrahiert eine wiederverwendbare Utility fuer
  Tabellenabhaengigkeiten und Zyklusdiagnostik
- diese Utility adressiert Sortierung und FK-Zyklen auf Schemaebene
- sie ist kein fertiger Ersatz fuer die Baumprojektion oder Row-Graph-Walks
  externer Consumer

### 4.5 `SchemaReadResult` bleibt vorerst ein Envelope

Verbindliche Entscheidung:

- `SchemaReadResult` wird in 0.9.1 nicht vorschnell aufgespalten
- Integrationsadapter duerfen `schema`, `notes` und `skippedObjects` lokal
  projizieren oder filtern
- ein zweiter schlanker Core-Vertrag kommt nur bei nachgewiesenem Mehrfachbedarf

### 4.6 DDL-Refactor in 0.9.1 ist intern vorbereitend, nicht output-wirksam

Verbindliche Entscheidung:

- 0.9.1 darf interne DDL-Modell- und Kompositionsschnitte einfuehren
- 0.9.1 darf den bestehenden `schema generate`-Default-Output nicht
  sichtbar aendern
- der sichtbare Endnutzer-Vertrag fuer einen optionalen DDL-Output-Split
  (`pre-data`/`post-data`, `ddl_parts`, phasenbezogene Artefakte) bleibt
  Milestone 0.9.2 gemaess `docs/planning/ddl-output-split-plan.md` vorbehalten
- strukturierte `ManualActionRequired`-Modelle in 0.9.1 sind zulaessig,
  sofern die bestehende CLI-/Datei-Ausgabe dadurch nicht bricht

---

## 5. Zielarchitektur

### 5.1 Portschnitt mit klarer Leserichtung

Die Zielstruktur fuer 0.9.1 ist eine Portflaeche, in der lesende Consumer
moeglichst nur lesende Typen sehen:

- read-orientierte Ports fuer Schema/Data-Lesen
- write-orientierte Ports fuer Import-/Write-Sessions
- getrennte Optionsmodelle, sofern Reader- und Writer-Bedarf heute vermischt
  sind

Die konkrete Paketstruktur kann waehrend der Umsetzung pragmatisch gewaehlt
werden; entscheidend ist die reduzierte transitive Kopplung.

### 5.2 JDBC-Treiber mit optionalem Profiling-Anbau

Die Treiberkerne bleiben fuer:

- `DatabaseDriver`
- `SchemaReader`
- `DataReader`
- `DataWriter`
- `DdlGenerator`

Profiling-spezifische Adapter werden daneben als optionale Module geschnitten,
damit ein externer Consumer nicht unnoetig `hexagon:profiling` mitziehen muss.

### 5.3 Gemeinsame Utility fuer Tabellenabhaengigkeiten

Die mehrfach vorhandene FK-/Topo-Sort-Logik wird in einen kleinen,
wiederverwendbaren Helfer ueberfuehrt, voraussichtlich in `hexagon:core`.

Der Helper sollte mindestens liefern:

- sortierte Tabellenreihenfolge
- erkannte zyklische Kanten
- eine klare API fuer Import-/Transfer-/DDL-Pfade

---

## 6. Geplante Arbeitspakete

Die Arbeitspakete sind bewusst gereiht: **Phase A (Sicherheits-Haertung)**
steht vor den strukturellen Refaktorierungen, weil die Findings aus
`docs/user/quality.md` inhaltlich **vor** dem 1.0.0-Publish-Vertrag behoben
sein muessen. **Phase B (Zerlegung der grossen Klassen)** nutzt die nun
verfuegbaren Dialekt-Utilities aus Phase A als Basis und reduziert die
Regressionsflaeche der spaeteren Port-/Profiling-/Integrations-Refactors.

### 6.1 Phase A - Sicherheits-Haertung

Eingang: `docs/user/quality.md` Findings „Hoch" und „Mittel".

- zentrale Identifier-Quoting-Utility pro Dialekt (PG/MySQL/SQLite) in
  einem einheitlichen Paket; die drei Introspection-Adapter und die
  Profiling-Data-Adapter bauen alle Identifier nur noch ueber diese
  Utility (`PostgresSchemaIntrospectionAdapter`,
  `MysqlSchemaIntrospectionAdapter`, `MysqlProfilingDataAdapter`,
  `SqliteSchemaIntrospectionAdapter`)
- direkte `$name`-Interpolation von `table`/`column`/`schema` in
  Metadaten-SQL durch `PreparedStatement`-Binding fuer Literals und
  konsequentes Identifier-Quoting fuer Namen ersetzen
- `--filter` (`DataExportHelpers.resolveFilter`) und
  `constraint.expression` (DDL-Generatoren) als **Trusted Input**
  kennzeichnen: CLI-Hilfetext + KDoc + ggf. eigener Flag-Name
  (`--unsafe-filter`); optional kleine Filter-DSL als sicherer
  Alternativweg
- Security-Tests mit absichtlich boesartigen Tabellen-/Spaltennamen
  (`"; DROP TABLE …`, Unicode-Homoglyphs, reservierte Woerter) im
  Profiling- und DDL-Pfad — Escape-/Quoting-Fehler fallen dadurch
  sofort auf
- Kover-Coverage bleibt pro Modul ≥ 90 %

Ergebnis:

Die Injection-Flaeche aus `docs/user/quality.md` Findings ist geschlossen.
Raw-SQL-Oberflaechen sind entweder vertragstechnisch klar als
Trusted-Input ausgewiesen oder durch strukturierten Ersatz reduziert.

### 6.2 Phase B - Zerlegung der grossen Orchestrierungs- und Dialekt-Klassen

Eingang: `docs/user/quality.md` Findings zu Dateigroesse, dialekt-duplizierter
Logik und `-- TODO: …`-Platzhaltern in den DDL-Generatoren.

- **Runner-Zerlegung**: `DataImportRunner` (~887 LOC) und
  `DataExportRunner` (~923 LOC) in kleinere Dienste trennen, z.B.
  `ImportRequestValidator`, `ImportSourceResolver`,
  `ResumeCoordinator`, `ManifestCoordinator`, `ImportExecutionService`
  bzw. analog fuer Export. Der Runner wird zum Orchestrator ueber
  diese Dienste.
- **Streaming-Zerlegung**: `StreamingImporter` (~792 LOC) in einen
  Orchestrator + Tabellen-Pipeline + Chunk-Handler-Unit trennen; der
  Export-Seite folgt derselben Linie.
- **Comparator-Zerlegung**: `SchemaComparator` (~656 LOC) pro
  Objekttyp (Tables, Views, Functions, Procedures, Triggers,
  Sequences) in kleinere Diff-Einheiten.
- **DDL-Generatoren pro Objektart**: neuen Schnitt in
  `ViewDdlGenerator`, `FunctionDdlGenerator`,
  `ProcedureDdlGenerator`, `TriggerDdlGenerator`; die dialekt-
  spezifischen Generatoren werden zu Komposition aus diesen
  Objekt-Generatoren und ziehen Rewrite-/Capability-Regeln pro
  Objekt nach. Die bisherigen `-- TODO: …`-SQL-Kommentar-
  Platzhalter (PG ~7 Stellen, MySQL ~11 Stellen, SQLite ~3 Stellen)
  werden intern durch strukturierte `ManualActionRequired`-Eintraege
  ersetzt. Parallel entsteht ein interner Phasen-/Objektschnitt, der
  spaeteren `pre-data`/`post-data`-Output tragen kann
  (`docs/planning/ddl-output-split-plan.md`), ohne in 0.9.1 bereits neue
  sichtbare Artefakte oder JSON-Felder einzufuehren.
- **Dialekt-Capabilities**: explizite Capability-Typen (z.B. als
  Enum oder Data-Class pro `DatabaseDialect`), damit Generatoren
  konsistent entscheiden, ob ein Objekt generiert, konvertiert,
  uebersprungen oder als manuelle Nacharbeit gemeldet wird.
- **Plan-Kommentare raus**: Historie (`0.9.0 Phase A/B/C/D.n §…`)
  wandert aus dem Produktionscode zurueck nach `docs/`; im Code
  bleiben „why"-Kommentare und Invarianten. Pragmatischer Schnitt
  pro Datei, nicht als Zwangs-Sweep.
- Tests fuer den internen DDL-Refactor: Unsupported-/Rewrite-Faelle
  werden strukturiert modelliert; zugleich muss die bestehende
  `schema generate`-Ausgabe fuer den Default-Pfad stabil bleiben.

Ergebnis:

Die wartungskritischen Hotspots sind zerlegt, der DDL-Pfad ist intern so
geschnitten, dass manuelle Nacharbeit und spaetere Phasenobjekte sauber
modellierbar sind, und Dialekt-Fixes landen konsistent ueber alle drei
Treiber, ohne dass 0.9.1 bereits einen neuen DDL-Output-Vertrag ausrollt.

### 6.3 Phase C - Port- und Optionsschnitt trennen

- aktuelle read-/write-gemischte Porttypen inventarisieren
- Reader-Pfade von writer-zentrierten Optionsmodellen entkoppeln
- `hexagon:ports` so scharfziehen, dass reine Lese-Consumer weniger Ballast
  transitiv sehen
- betroffene Import-/Streaming-Pfade an den neuen Schnitt anpassen

Ergebnis:

Ein fachlich klarerer Portschnitt fuer Schema/Data-Lesen versus Import/Write.

### 6.4 Phase D - Profiling aus Treiber-Kernmodulen extrahieren

- Profiling-Pakete und Build-Abhaengigkeiten pro JDBC-Treiber separieren
- neue optionale Profiling-Module schneiden
- Treiber-Kernmodule von `hexagon:profiling` befreien
- Tests und Build-Graph auf den neuen Zuschnitt anpassen

Ergebnis:

Schema/Data/DDL-Treiberkerne sind ohne Profiling transitiv konsumierbar.

### 6.5 Phase E - FK-/Topo-Sort-Utility extrahieren

- gemeinsame Semantik aus DDL-, Import- und Transfer-Pfad herausarbeiten
- einen kleinen Core-Helfer mit Zyklusdiagnostik definieren
- duplizierte lokale Sortierimplementierungen auf den neuen Helfer umstellen

Ergebnis:

Ein wiederverwendbarer FK-/Topo-Sort-Vertrag statt dreifacher Speziallogik.

### 6.6 Phase F - Integrationsschnitt fuer `source-d-migrate` absichern

- Doku fuer externe Consumer angleichen
- festhalten, welche Toolmodelle intern bleiben und welche als stabile
  Integrationsflaeche taugen
- `SchemaReadResult`-Projektion bewusst im Adapterraum verorten

Ergebnis:

Ein nachvollziehbarer Integrationsschnitt fuer `d-browser` ohne vorschnellen
Oeffentlichkeitsvertrag.

### 6.7 Phase G - Vorbereitung fuer 1.0.0-Publish dokumentieren

- publizierbare Modulgruppen identifizieren
- festhalten, welche 1.0.0-Artefakte spaeter nach Maven Central sollen
- `docs/user/releasing.md` und Roadmap so angleichen, dass `0.9.1` als
  Refactor-Schritt und `1.0.0` als Publish-Schritt klar getrennt bleiben

Ergebnis:

Eine stabile inhaltliche Vorstufe fuer spaetere 1.0.0-Distribution, ohne den
Publish-Workflow bereits in 0.9.1 einzubauen.

---

## 7. Test- und Verifikationsstrategie

Pflichtfaelle:

- Unit-Tests fuer neue oder extrahierte FK-/Topo-Sort-Utilities
- Modul-/Build-Checks, dass Treiberkerne nicht mehr an `hexagon:profiling`
  haengen
- Reader-/Writer-Pfade bleiben nach Port-Refactor funktional gruen
- bestehende Import-/Streaming-Tests bleiben semantisch intakt
- Architektur- und Doku-Checks gegen neue Integrationsgrenzen

Zusatznutzen:

- mindestens eine lokale oder interne Konsumentenprobe fuer den
  `source-d-migrate`-Pfad
- kein erzwungener oeffentlicher Publish-Test in 0.9.1

---

## 8. Datei- und Codebasis-Betroffenheit

Sicher betroffen:

- `docs/planning/roadmap.md`
- `spec/architecture.md`
- `spec/hexagonal-port.md`
- `docs/user/releasing.md`
- `docs/planning/d-browser-integration-coupling-assessment.md`
- `docs/planning/implementation-plan-0.9.1.md`
- `hexagon/ports/...`
- `hexagon/core/...`
- `adapters/driven/driver-postgresql/...`
- `adapters/driven/driver-mysql/...`
- `adapters/driven/driver-sqlite/...`
- `adapters/driven/formats/...`
- `hexagon/application/...`

Wahrscheinlich neu:

- optionale Profiling-Zusatzmodule fuer JDBC-Treiber
- neue Core-Utility fuer Tabellenabhaengigkeiten
- neue oder angepasste Tests fuer Modulzuschnitt und Reuse-Vertraege

---

## 9. Risiken und offene Punkte

### 9.1 Zu frueher Publish-Vertrag wuerde den Refactor einfrieren

Wenn 0.9.1 bereits oeffentliche Maven-Artefakte mit stabilem Vertrag
veroeffentlicht, wird jede spaetere Modulkorrektur unnoetig teuer.

Mitigation:

- kein Maven-Central-Publish in 0.9.1
- Publish-Vertrag explizit auf 1.0.0 schieben

### 9.2 Reader-/Writer-Schnitt kann tiefer gekoppelt sein als erwartet

Gerade `ImportOptions` haengt heute auch an Reader-Pfaden. Ein oberflaechlicher
Split wuerde die Kopplung nur verdecken.

Mitigation:

- fachliche statt nur paketbezogene Entkopplung
- bestehende Reader-/Streaming-Tests als Sicherheitsnetz behalten

### 9.3 Profiling-Extraktion veraendert den Build-Graph

Neue Module und verschobene Tests koennen CI, Coverage und Registry-/Wiring-
Stellen unerwartet beeinflussen.

Mitigation:

- Modulzuschnitt inkrementell einfuehren
- Build- und Testgraph frueh mitziehen

### 9.4 `SchemaReadResult` darf nicht vorschnell ueberdesignt werden

Ein zweiter Portvertrag nur fuer einen einzelnen Consumer kann den Core
komplizierter machen als noetig.

Mitigation:

- erst lokal im Adapter projizieren
- Core-Split nur bei nachgewiesenem Mehrfachbedarf

### 9.5 Zerlegung der grossen Klassen kann Resume-Invarianten gefaehrden

`DataImportRunner` und `DataExportRunner` tragen nach 0.9.0 den
vollstaendigen Resume-Vertrag (Manifest-Lifecycle, Fingerprint-
Preflight, committed-chunk-basierte Fortschreibung, Directory-Bindung,
Composite-Marker). Ein zu grobschlaechtiger Split wuerde diese
Invarianten quer ueber die neuen Dienste verteilen und Regressions-
Fehler in Resume-Pfaden wahrscheinlicher machen.

Mitigation:

- Resume-Invarianten bleiben Eigentum genau eines Koordinators
  (`ResumeCoordinator` bzw. `ManifestCoordinator`); andere Dienste
  rufen nur auf
- Bestand der 0.9.0-Resume-Tests (C.1/C.2/D.1/D.2-4) als
  Regressions-Sicherheitsnetz waehrend der Zerlegung behalten
- keine Semantik-Aenderungen am Vertrag im Refactor — nur strukturelle
  Umverteilung

### 9.6 DDL-Ergebnis-Split darf die bestehende CLI-Ausgabe nicht brechen

Der DDL-Refactor in 0.9.1 fuehrt intern neue Modell- und
Kompositionsschnitte ein. Wenn daraus versehentlich bereits ein sichtbarer
CLI-/Datei-/JSON-Vertragswechsel wird, kollidiert das mit dem Roadmap-
Zuschnitt zwischen 0.9.1 und 0.9.2. Nutzer, die heute auf dem
`schema generate`-Output arbeiten, koennten dadurch unnoetig vorzeitig
gebrochen werden.

Mitigation:

- interne DDL-Modelle duerfen eingefuehrt werden, aber der bestehende
  `schema generate`-Default-Output bleibt in 0.9.1 stabil
- keine neuen sichtbaren Split-Artefakte (`pre-data`/`post-data`),
  keine neuen JSON-Felder wie `ddl_parts` und kein neuer CLI-Vertrag in
  0.9.1
- Golden-Master- und CLI-Tests stellen sicher, dass der Default-Pfad
  semantisch und textuell stabil bleibt
- sichtbare DDL-Output-Aenderungen werden bewusst erst in 0.9.2
  implementiert

---

## 10. Entscheidungsempfehlung

Milestone 0.9.1 sollte bewusst als Haerte- und Refactor-Milestone zwischen
dem 0.9.0-Beta-Code-Cut und dem 1.0.0-Stable-Release eingefuegt werden:

- `0.9.0` bleibt fokussiert auf Resume und finalen `--lang`-Vertrag
- `0.9.1` haertet zuerst die Sicherheits-Findings aus `docs/user/quality.md`
  (Phase A), zerlegt die wartungskritischen Orchestrierungs-/Dialekt-
  Hotspots (Phase B), bereitet dabei den internen DDL-Modellschnitt fuer
  spaetere `pre-data`/`post-data`-Artefakte vor und stabilisiert dann die
  interne Library-Grenze fuer externe Consumer (Phasen C–G)
- `0.9.2` uebernimmt erst danach den sichtbaren optionalen
  DDL-Output-Split gemaess `docs/planning/ddl-output-split-plan.md`
- `0.9.5` bleibt bei Docs und Pilot-QA
- `1.0.0` uebernimmt erst danach den oeffentlichen Publish-Vertrag inklusive
  Maven-Central-Portal-Workflow

So wird `d-migrate` erst sicherheits-gehaertet und strukturell publish-
faehig gemacht und erst danach oeffentlich als stabile Library distribuiert.
