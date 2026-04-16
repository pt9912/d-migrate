# Implementierungsplan: Milestone 0.9.1 - Library-Refactor und Integrationsschnitt

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.9.1. Es dient als laufend gepflegte Spezifikation und
> Review-Grundlage fuer die Entkopplung von `d-migrate` als wiederverwendbare
> Library-Basis.
>
> Status: Draft (2026-04-16)
> Referenzen: `docs/roadmap.md` Milestone 0.9.1 und 1.0.0,
> `docs/architecture.md` Moduluebersicht und Abhaengigkeiten,
> `docs/hexagonal-port.md` Abschnitt `3.2 hexagon:ports`,
> `docs/d-browser-integration-coupling-assessment.md`,
> `docs/releasing.md` als aktueller Ist-Stand fuer Release-Automation.

---

## 1. Ziel

Milestone 0.9.1 schiebt keinen neuen Endnutzer-Feature-Vertrag nach, sondern
macht die innere Modul- und Portstruktur von `d-migrate` sauberer fuer externe
Consumer wie `d-browser`.

Der Milestone liefert:

- eine klarere Read-/Write-Schnitt im Portbereich
- schlankere JDBC-Treiberkerne ohne transitive Profiling-Pflicht
- eine wiederverwendbare FK-/Topo-Sort-Basis statt mehrfacher Duplikation
- einen stabileren Integrationsschnitt fuer einen spaeteren
  `source-d-migrate`-Adapter

Der Milestone liefert bewusst nicht:

- einen öffentlichen Maven-Central-Publish-Vertrag
- einen GitHub-Workflow fuer Maven Central Portal
- bereits stabile Library-Koordinaten fuer externe Verbraucher

0.9.1 ist damit ein Refactor-Milestone fuer konsumierbare Libraries, waehrend
die oeffentliche Distribution bewusst in 1.0.0 verbleibt.

---

## 2. Ausgangslage

Stand im aktuellen Repo:

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

### 6.1 Phase A - Port- und Optionsschnitt trennen

- aktuelle read-/write-gemischte Porttypen inventarisieren
- Reader-Pfade von writer-zentrierten Optionsmodellen entkoppeln
- `hexagon:ports` so scharfziehen, dass reine Lese-Consumer weniger Ballast
  transitiv sehen
- betroffene Import-/Streaming-Pfade an den neuen Schnitt anpassen

Ergebnis:

Ein fachlich klarerer Portschnitt fuer Schema/Data-Lesen versus Import/Write.

### 6.2 Phase B - Profiling aus Treiber-Kernmodulen extrahieren

- Profiling-Pakete und Build-Abhaengigkeiten pro JDBC-Treiber separieren
- neue optionale Profiling-Module schneiden
- Treiber-Kernmodule von `hexagon:profiling` befreien
- Tests und Build-Graph auf den neuen Zuschnitt anpassen

Ergebnis:

Schema/Data/DDL-Treiberkerne sind ohne Profiling transitiv konsumierbar.

### 6.3 Phase C - FK-/Topo-Sort-Utility extrahieren

- gemeinsame Semantik aus DDL-, Import- und Transfer-Pfad herausarbeiten
- einen kleinen Core-Helfer mit Zyklusdiagnostik definieren
- duplizierte lokale Sortierimplementierungen auf den neuen Helfer umstellen

Ergebnis:

Ein wiederverwendbarer FK-/Topo-Sort-Vertrag statt dreifacher Speziallogik.

### 6.4 Phase D - Integrationsschnitt fuer `source-d-migrate` absichern

- Doku fuer externe Consumer angleichen
- festhalten, welche Toolmodelle intern bleiben und welche als stabile
  Integrationsflaeche taugen
- `SchemaReadResult`-Projektion bewusst im Adapterraum verorten

Ergebnis:

Ein nachvollziehbarer Integrationsschnitt fuer `d-browser` ohne vorschnellen
Oeffentlichkeitsvertrag.

### 6.5 Phase E - Vorbereitung fuer 1.0.0-Publish dokumentieren

- publizierbare Modulgruppen identifizieren
- festhalten, welche 1.0.0-Artefakte spaeter nach Maven Central sollen
- `docs/releasing.md` und Roadmap so angleichen, dass `0.9.1` als
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

- `docs/roadmap.md`
- `docs/architecture.md`
- `docs/hexagonal-port.md`
- `docs/releasing.md`
- `docs/d-browser-integration-coupling-assessment.md`
- `docs/implementation-plan-0.9.1.md`
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

---

## 10. Entscheidungsempfehlung

Milestone 0.9.1 sollte bewusst als Refactor-Milestone zwischen dem
0.9.0-Beta-Code-Cut und dem 1.0.0-Stable-Release eingefuegt werden:

- `0.9.0` bleibt fokussiert auf Resume und finalen `--lang`-Vertrag
- `0.9.1` stabilisiert die interne Library-Grenze fuer externe Consumer
- `0.9.5` bleibt bei Docs und Pilot-QA
- `1.0.0` uebernimmt erst danach den oeffentlichen Publish-Vertrag inklusive
  Maven-Central-Portal-Workflow

So wird `d-migrate` erst strukturell publish-faehig gemacht und erst danach
oeffentlich als stabile Library distribuiert.
