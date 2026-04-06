# Lastenheft: d-migrate

**Framework für datenbankunabhängige Migrationen und Datenverwaltung**

---

## 1. Ausgangssituation

### 1.1 Ist-Zustand

Viele Unternehmen stehen vor der Herausforderung, dass ihre Datenbankmigrationen und Datenverwaltungsprozesse stark von spezifischen Datenbanksystemen und Tools abhängig sind. Dies führt zu:

- **Vendor Lock-in**: Abhängigkeit von proprietären Migrations-Tools einzelner Datenbankhersteller
- **Inkonsistente Prozesse**: Verschiedene Teams nutzen unterschiedliche Tools und Verfahren
- **Hoher manueller Aufwand**: Schema-Änderungen müssen für jede Datenbank separat entwickelt werden
- **Fehleranfälligkeit**: Manuelle Konvertierung zwischen verschiedenen Formaten
- **Eingeschränkte Portabilität**: Schwieriger Wechsel zwischen Datenbanksystemen
- **Komplexe Tool-Landschaft**: Vielzahl spezialisierter Tools für verschiedene Aufgaben

### 1.2 Problembeschreibung

Entwicklungsteams benötigen eine einheitliche Lösung, die:
- Unabhängigkeit von spezifischen Datenbanksystemen gewährleistet
- Migrationen zwischen verschiedenen Systemen vereinfacht
- Konsistente Prozesse über alle Teams hinweg ermöglicht
- Den Aufwand für Schema-Verwaltung und Datenmigrationen reduziert

---

## 2. Zielsetzung

### 2.1 Projektziel

Entwicklung eines modularen Frameworks, das eine herstellerunabhängige Verwaltung von Datenbankstrukturen und -inhalten ermöglicht und dabei bestehende Entwicklungsprozesse und Tools integriert.

### 2.2 Geschäftsziele

- **Kostensenkung** durch Reduzierung des Entwicklungsaufwands um mindestens 40%
- **Risikominimierung** durch Vermeidung von Vendor Lock-in
- **Qualitätssteigerung** durch standardisierte, validierte Prozesse
- **Flexibilität** für zukünftige Technologieentscheidungen
- **Beschleunigung** von Entwicklungs- und Migrationsprozessen
- **Werkzeugunabhängigkeit** für Reverse-Engineering und Codeerzeugung, insbesondere bei kommerziellen Datenbanksystemen

### 2.3 Nicht-Ziele

- Ersatz bestehender Datenbanksysteme
- Entwicklung einer eigenen Datenbank
- Automatische Performance-Optimierung
- Echtzeit-Replikation zwischen Systemen

---

## 3. Produkteinsatz

### 3.1 Anwendungsbereiche

- **Entwicklung**: Schema-Verwaltung während der Softwareentwicklung
- **Migration**: Überführung von Datenbanken zwischen verschiedenen Systemen
- **Backup/Recovery**: Export und Import von Datenbeständen
- **Testing**: Erstellung von Testdatenbanken aus Definitionen
- **Dokumentation**: Automatische Erzeugung von Datenbankdokumentation
- **Compliance**: Nachvollziehbare Änderungshistorie

### 3.2 Zielgruppen

#### Primäre Zielgruppen
- **Entwicklungsteams** (10-100 Personen)
- **DevOps-Engineers** 
- **Datenbankadministratoren**
- **Software-Architekten**

#### Sekundäre Zielgruppen
- **Projektmanager** (für Migrations-Planung)
- **QA-Teams** (für Testdatenverwaltung)
- **Compliance-Beauftragte** (für Audit-Trails)

### 3.3 Betriebsbedingungen

- **Betriebsumgebung**: On-Premise und Cloud-Umgebungen
- **Verfügbarkeit**: Keine besonderen Anforderungen (Batch-Betrieb)
- **Wartungsfenster**: Migrationen typischerweise in Wartungsfenstern
- **Plattformen**: Windows, Linux, macOS

---

## 4. Funktionale Anforderungen

### 4.1 Musskriterien

#### 4.1.1 Schema-Verwaltung

**LF-001** Das System muss Datenbankstrukturen in einem neutralen Format definieren können
- Tabellenstrukturen
- Datentypen
- Beziehungen zwischen Tabellen
- Constraints und Validierungen
- Indizes
- Andere datenbankeigene Objekte (z.B. Stored Procedures, Funktionen, Trigger, Views)

**LF-002** Das System muss definierte Strukturen validieren können
- Syntaktische Korrektheit
- Referenzielle Integrität
- Typkompatibilität

**LF-003** Das System muss aus neutralen Definitionen datenbankspezifische Strukturen und Objekte erzeugen können für:
- PostgreSQL (Version 12+)
- MySQL (Version 8.0+)
- SQLite (Version 3.30+)
- Räumliche Datentypen und Geometrie-Spalten müssen für unterstützte Zielsysteme abbildbar sein:
  - PostgreSQL über PostGIS (`GEOMETRY`, `GEOGRAPHY` und abgeleitete Typen)
  - MySQL über native Spatial Data Types (`GEOMETRY`, `POINT`, `LINESTRING`, `POLYGON`)
  - SQLite über SpatiaLite (`GEOMETRY`, `POINT`, `LINESTRING`, `POLYGON`), sofern die Erweiterung im Zielsystem verfügbar ist

**LF-004** Das System muss aus datenbankspezifischen Strukturen und Objekten (SQL-Skripten oder Datenbankschema-Abfragen) ein neutrales Format erzeugen können.
- Unterstützung für Reverse-Engineering von SQL-DDL-Statements (z. B. CREATE TABLE, ALTER TABLE, CREATE PROCEDURE, CREATE FUNCTION, CREATE TRIGGER, CREATE VIEW)
- Extraktion von Tabellen, Datentypen, Constraints, Indizes, Beziehungen und anderen datenbankeigenen Objekten
- Kompatibilität mit den in LF-003 genannten Datenbanksystemen
- Reverse-Engineering muss über eigene Adapter und Metadatenleser möglich sein und darf keine verpflichtende Abhängigkeit von externen proprietären Codegenerierungswerkzeugen voraussetzen

#### 4.1.2 Internationalisierung und Zeichensätze

**LF-005** Das System muss Unicode und internationale Zeichensätze vollständig unterstützen
- UTF-8 als Standard-Encoding für alle Konfigurationsdateien und Schema-Definitionen
- Korrekte Verarbeitung von Unicode-Zeichen in Tabellen-, Spalten- und Constraint-Namen
- Unterstützung für nicht-lateinische Schriftzeichen (Kyrillisch, Arabisch, Chinesisch, Japanisch, etc.)
- Korrekte Sortierung und Kollation gemäß Unicode-Standards (ICU - International Components for Unicode)
- Erhalt von Encoding-Informationen bei Export/Import
- Validierung und Normalisierung von Unicode-Strings (NFC, NFD, NFKC, NFKD)

**LF-006** Das System muss mehrsprachige Benutzerinteraktion unterstützen
- Mehrsprachige Fehlermeldungen und Statusmeldungen (mindestens Deutsch, Englisch)
- Erweiterbare Lokalisierungsarchitektur für weitere Sprachen
- Sprachauswahl über Umgebungsvariable (LANG, LC_ALL) oder Konfiguration
- Lokalisierte Datumsformate, Zahlenformate und Währungen
- Mehrsprachige CLI-Hilfe und Dokumentation

**LF-007** Das System muss internationale Datenformate korrekt verarbeiten
- Zeitzonen-Awareness für TIMESTAMP-Felder (UTC als Standard, konfigurierbare Zeitzonen)
- Korrekte Konvertierung zwischen Zeitzonen bei Export/Import
- Unterstützung für verschiedene Datums- und Zeitformate (ISO 8601 als Standard)
- Währungsformate mit korrekter Dezimaltrennung (Komma vs. Punkt)
- Telefonnummern-Formate gemäß E.164 Standard

#### 4.1.3 Datenverwaltung

**LF-008** Das System muss Daten aus Datenbanken exportieren können
- Vollständiger Export von Tabellen
- Selektiver Export mit Filterkriterien
- Erhaltung von Datentypen und Beziehungen
- Korrekte Behandlung von Unicode-Daten (UTF-8, UTF-16, etc.)

**LF-009** Das System muss Daten in verschiedene Formate exportieren können:
- JSON (für Systemintegration, UTF-8 encoded)
- YAML (für Konfigurationsverwaltung, UTF-8 encoded)
- CSV (für Analyse-Tools, mit konfigurierbarem Encoding und BOM-Unterstützung)

**LF-010** Das System muss Daten aus Dateien in Datenbanken importieren können
- Validierung gegen Schema-Definition
- Fehlerbehandlung bei ungültigen Daten
- Transaktionale Verarbeitung
- Automatische Encoding-Erkennung (UTF-8, UTF-16, ISO-8859-1, etc.)
- Behandlung von BOM (Byte Order Mark) in Eingabedateien

#### 4.1.4 Integrationsfähigkeit

**LF-011** Das System muss Migrationsdateien für bestehende Tools erzeugen können:
- Flyway-kompatible SQL-Skripte
- Liquibase-Changesets
- Django-Migrations
- Knex.js-Migrationsdateien
- Diese Integrationen sind optionale Ausgabepfade und dürfen keine Voraussetzung für die Kernfunktionen des Systems sein

**LF-012** Das System muss über eine Kommandozeilen-Schnittstelle verfügen
- Alle Funktionen müssen per CLI erreichbar sein
- Batch-Verarbeitung muss möglich sein
- Integration in CI/CD-Pipelines

### 4.2 Sollkriterien
(Hinweis: Eine Priorisierung dieser Kriterien wird in der weiteren Projektplanung erfolgen, z.B. mittels MoSCoW-Methode.)

**LF-013** Das System sollte inkrementelle Exports/Imports unterstützen

**LF-014** Das System sollte Migrations-Rollback unterstützen

**LF-015** Das System sollte Schema-Vergleiche zwischen verschiedenen Umgebungen ermöglichen

**LF-016** Das System sollte automatische Dokumentationsgenerierung unterstützen
- Mehrsprachige Dokumentation (mindestens Deutsch, Englisch)
- Export in verschiedene Formate (HTML, PDF, Markdown)
- Generierung von ER-Diagrammen mit Unicode-Labels

**LF-017** Das System sollte die Evaluierung und den optionalen Einsatz von KI-Modellen zur Transformation von Stored Procedures und anderen datenbankeigenen Objekten unterstützen
- Transformation in ein abstraktes, textbasiertes Zwischenformat (Markdown mit strukturierten Code-Blöcken)
- Unterstützung für verschiedene LLM-Backends:
  - **Externe APIs**: OpenAI GPT-4/GPT-4o, Anthropic Claude 3.5, xAI Grok, Google Gemini
  - **Lokale Modelle**: Ollama (Llama 3.x, CodeLlama, Mixtral), LM Studio
  - **Self-Hosted**: vLLM, Text Generation Inference (TGI)
- Konfigurierbare Model-Provider mit Fallback-Optionen
- Das Format soll sowohl als lesbare Spezifikation dienen als auch die Generierung von zielsystemspezifischem Code ermöglichen
- Datenschutz-Option: Bevorzugung lokaler Modelle für sensible Code-Transformationen
- Performance-Anforderung: Transformation einer durchschnittlichen Stored Procedure (<100 Zeilen) in unter 10 Sekunden


### 4.3 Kannkriterien
(Hinweis: Eine Priorisierung dieser Kriterien wird in der weiteren Projektplanung erfolgen, z.B. mittels MoSCoW-Methode.)

**LF-018** Das System kann eine grafische Benutzeroberfläche für Schema-Design bereitstellen
- Mehrsprachige Benutzeroberfläche
- Bidirektionale Text-Unterstützung (RTL für Arabisch, Hebräisch)

**LF-019** Das System kann weitere Datenbanksysteme unterstützen (Oracle, MS SQL Server)
- Inklusive deren spezifischer Kollations- und Encoding-Einstellungen
- Die Unterstützung muss über eigene, austauschbare Reader-, Mapper- und Generator-Komponenten realisierbar sein

**LF-020** Das System kann Schema-Optimierungsvorschläge generieren

**LF-021** Das System kann Daten anonymisieren/pseudonymisieren beim Export
- Unicode-Aware-Anonymisierung (z.B. Ersetzung kyrillischer Namen durch andere kyrillische Namen)

**LF-022** Das System kann eine REST-API für Remote-Zugriff bereitstellen
- Content-Negotiation für verschiedene Sprachen (Accept-Language Header)

**LF-023** Das System kann die Erzeugung von Docker-Images unterstützen, die eine vordefinierte Datenbankstruktur und Testdaten enthalten, um Testumgebungen schnell bereitzustellen.

**LF-024** Das System kann die algorithmische Generierung von Testdaten unterstützen, basierend auf Schemadefinitionen und optionalen Regeln
- **Regelbasierte Generierung**: Faker-Libraries (Python Faker, JavaScript Faker) für deterministische Testdaten
- **KI-gestützte Generierung (optional)**:
  - Verwendung von LLMs zur Generierung kontextrelevanter, realistischer Testdaten
  - Unterstützung derselben LLM-Backends wie LF-014 (OpenAI, Anthropic, xAI, Ollama, etc.)
  - Intelligente Berücksichtigung von Datenbeziehungen und Business-Logik
  - Generierung von Edge-Cases und Grenzwerten
- Konfigurierbare Datenmenge und Varianz
- Seed-basierte Reproduzierbarkeit für deterministische Tests
- Unterstützung für mehrsprachige Testdaten (Namen, Adressen, etc. in verschiedenen Sprachen)

**LF-025** Das System kann die Unterstützung für Teil-Replikationen von Daten bieten, um nur spezifische Datensätze oder Tabellen zu migrieren oder zu synchronisieren.

**LF-026** Das System kann Funktionen zur Datenmaskierung oder Pseudonymisierung für sensible Daten bereitstellen, um Datenschutzanforderungen zu erfüllen, insbesondere in Test- oder Entwicklungsumgebungen.


---

## 5. Nichtfunktionale Anforderungen

### 5.1 Qualitätsanforderungen

#### 5.1.1 Performance

**LN-001** DDL-Generierung muss innerhalb von 5 Sekunden erfolgen (für Schemas bis 100 Tabellen)

**LN-002** Datenexport muss mindestens 10.000 Datensätze pro Sekunde verarbeiten können

**LN-003** Import-Performance muss mindestens 5.000 Datensätze pro Sekunde betragen

#### 5.1.2 Skalierbarkeit

**LN-004** Das System muss Schemas mit mehr als 1.000 Tabellen verarbeiten können
- DDL-Generierung für 1.000 Tabellen in unter 30 Sekunden
- Parallele Verarbeitung von mindestens 10 Tabellen gleichzeitig
- Speicher-effiziente Verarbeitung (max. 2 GB RAM für Schema-Operationen)

**LN-005** Das System muss sehr große Datenmengen (>10 TB) verarbeiten können
- Streaming-basierter Export ohne vollständiges Laden in den Speicher
- Chunk-basierte Verarbeitung mit konfigurierbarer Batch-Größe (Standard: 10.000 Datensätze)
- Progress-Tracking und Resume-Capability bei Unterbrechungen

**LN-006** Das System muss inkrementelle Migrationen unterstützen
- Identifikation geänderter Datensätze seit letzter Migration
- Inkrementelle Migration für Datenbanken mit >1.000 Tabellen in unter 1 Stunde
- Delta-basierte Updates statt vollständiger Re-Migration

**LN-007** Das System muss parallele Verarbeitung unterstützen
- Paralleler Export/Import mehrerer Tabellen (konfigurierbar, Standard: CPU-Kerne)
- Thread-sichere Operationen bei konkurrierenden Zugriffen
- Automatische Lastverteilung basierend auf Tabellengröße

**LN-008** Das System muss Partitionierung für große Tabellen unterstützen
- Automatische Erkennung und Verarbeitung partitionierter Tabellen
- Export/Import pro Partition für bessere Performance
- Parallele Verarbeitung verschiedener Partitionen

#### 5.1.3 Zuverlässigkeit

**LN-009** Datenverlust bei Import/Export-Vorgängen muss ausgeschlossen sein

**LN-010** Transaktionale Konsistenz muss gewährleistet sein

**LN-011** Fehlerhafte Operationen dürfen keine inkonsistenten Zustände hinterlassen

**LN-012** Automatische Checkpoint-Erstellung bei langen Operationen (alle 10.000 Datensätze oder alle 5 Minuten)

**LN-013** Atomare Rollback-Fähigkeit auf Checkpoint-Ebene bei Fehlern

#### 5.1.4 Benutzbarkeit

**LN-014** Installation und Erstkonfiguration darf maximal 30 Minuten dauern

**LN-015** Kommandos müssen selbsterklärend und dokumentiert sein

**LN-016** Fehlermeldungen müssen aussagekräftig und handlungsorientiert sein

**LN-017** Progress-Anzeige mit Zeitschätzung für alle lang laufenden Operationen (>10 Sekunden)

#### 5.1.5 Kompatibilität

**LN-018** Das System muss auf allen gängigen Betriebssystemen lauffähig sein

**LN-019** Integration in bestehende CI/CD-Pipelines muss möglich sein

**LN-020** Bestehende Schema-Definitionen (SQL) müssen importierbar sein

#### 5.1.6 Internationalisierung und Lokalisierung

**LN-021** Das System muss vollständige Unicode-Unterstützung gewährleisten
- Keine Datenverluste bei Verarbeitung von Unicode-Zeichen
- Korrekte String-Längenberechnung (grapheme-aware, nicht byte-basiert)
- Unterstützung für Emoji und Sonderzeichen (Unicode 15.0+)

**LN-022** Das System muss Lokalisierungsstandards einhalten
- ICU (International Components for Unicode) für Sortierung und Vergleiche
- CLDR (Common Locale Data Repository) für Datums-, Zahlen- und Währungsformate
- gettext oder ähnliche I18n-Frameworks für Übersetzungen

**LN-023** Fehlermeldungen und Logging müssen internationalisiert sein
- Strukturierte Log-Ausgabe mit lokalisierbaren Message-Keys
- Trennung von technischen Details (immer Englisch) und Benutzermeldungen (lokalisiert)
- Fallback auf Englisch bei fehlenden Übersetzungen

**LN-024** Die Performance darf durch Unicode-Verarbeitung nicht signifikant beeinträchtigt werden
- String-Operationen maximal 20% langsamer als ASCII-only
- Effiziente UTF-8 Encoding/Decoding

### 5.2 Sicherheitsanforderungen

**LN-025** Datenbankzugangsdaten müssen verschlüsselt gespeichert werden

**LN-026** Verbindungen zu Datenbanken müssen verschlüsselt erfolgen können

**LN-027** Audit-Logging aller durchgeführten Operationen

**LN-028** Rollenbasierte Zugriffskontrolle für administrative Funktionen

**LN-029** Bei Nutzung externer KI-APIs müssen API-Keys sicher gespeichert werden (verschlüsselt, nie in Logs)

**LN-030** KI-generierter Code muss als solcher gekennzeichnet werden (Metadaten, Kommentare)

**LN-031** Bei KI-gestützten Transformationen müssen Quell- und Zielcode für Auditing verfügbar bleiben

### 5.3 KI-Integration und Datenschutz

**LN-032** Das System muss eine klare Konfiguration für KI-Backends ermöglichen
- Auswahl zwischen externen APIs und lokalen Modellen
- Transparente Angabe, wohin Daten gesendet werden
- Opt-in für externe APIs (kein automatisches Senden an Cloud-Services)

**LN-033** Für datenschutzkritische Umgebungen muss der Betrieb ohne externe KI-APIs möglich sein
- Vollständige Funktionalität mit lokalen Modellen (Ollama, LM Studio)
- Keine Telemetrie an externe Services
- Offline-Betrieb muss möglich sein

**LN-034** KI-Transformationen müssen validierbar sein
- Automatische Syntax-Prüfung des generierten Codes
- Optional: Semantische Äquivalenzprüfung durch Testausführung
- Diff-Ansicht zwischen Original und transformiertem Code

**LN-035** Performance-Anforderungen für KI-Operationen
- Timeout-Konfiguration für API-Calls (Standard: 30 Sekunden)
- Retry-Mechanismus bei temporären Fehlern (max. 3 Versuche)
- Fallback auf regelbasierte Generierung bei KI-Ausfall (für LF-024)
- Caching von häufigen Transformationen zur Kostenreduktion

**LN-036** Modell-Auswahl und Versionierung
- Dokumentation der verwendeten Modellversion in Metadaten
- Konfigurierbare Modell-Parameter (Temperatur, Max-Tokens)
- A/B-Testing-Fähigkeit für verschiedene Modelle

### 5.4 Wartbarkeit und Erweiterbarkeit

**LN-037** Modularer Aufbau für unabhängige Weiterentwicklung

**LN-038** Dokumentierte Schnittstellen für Erweiterungen

**LN-039** Versionierung aller Schema-Definitionen

**LN-040** Rückwärtskompatibilität für mindestens 2 Hauptversionen

**LN-041** Plugin-Architektur für KI-Backends
- Einfache Integration neuer LLM-Provider ohne Core-Änderungen
- Standardisierte Schnittstelle für alle KI-Provider
- Community-Beiträge für neue Modell-Integrationen

**LN-042** Lokalisierungserweiterbarkeit
- Einfache Hinzufügung neuer Sprachen durch Community
- Standardisierte Translation-Dateien (z.B. gettext .po/.pot)
- Dokumentierter Übersetzungsprozess

### 5.5 Testbarkeit

**LN-043** Das System muss umfassend testbar sein
- Dependency Injection für einfaches Mocking von Datenbankverbindungen
- Klare Trennung von Business-Logik und Infrastruktur-Code
- Deterministisches Verhalten bei gleichen Eingaben (keine versteckten Seiteneffekte)
- Test-Hooks für Timing-kritische Operationen

**LN-044** Automatisierte Tests müssen effizient ausführbar sein
- Unit-Tests: Vollständige Testsuite in unter 2 Minuten
- Integration-Tests: Vollständige Testsuite in unter 15 Minuten
- Parallele Testausführung ohne Race-Conditions
- Isolierte Tests ohne gegenseitige Abhängigkeiten

**LN-045** Test-Coverage muss messbar und überprüfbar sein
- Code-Coverage-Reports in CI/CD-Pipeline
- Mindestens 80% Line-Coverage für kritische Module
- 100% Coverage für Datentyp-Konvertierungen
- Branch-Coverage für Error-Handling-Pfade

**LN-046** Property-Based Testing für Robustheit
- Unterstützung für Hypothesis (Python) oder fast-check (JavaScript)
- Automatische Testfall-Generierung für Schema-Parsing
- Shrinking bei Testfehlern zur Ursachenfindung

**LN-047** Test-Daten und Fixtures müssen wartbar sein
- Versionierung von Test-Fixtures in Git
- Automatische Generierung repräsentativer Test-Schemas
- Reproduzierbare Test-Datenbanken via Docker Compose
- Anonymisierte Produktions-Dumps als Test-Daten (optional)

**LN-048** Integrationstests mit echten Datenbanken
- Docker-basierte Test-Datenbanken für alle unterstützten Systeme
- Automatisches Cleanup nach Testausführung
- Unterstützung für lokale und CI-Umgebungen
- Konfigurierbare Timeouts für langsame CI-Systeme

---

## 6. Lieferumfang

### 6.1 Hauptkomponenten

1. **Schema-Management-Modul**
   - Definition von Datenbankstrukturen
   - Validierung von Schemas
   - Generierung datenbankspezifischer DDL

2. **Daten-Management-Modul**
   - Export-Funktionalität
   - Import-Funktionalität
   - Format-Konvertierung

3. **Integrations-Adapter**
   - Flyway-Integration
   - Liquibase-Integration
   - Django-Integration
   - Knex.js-Integration

4. **Kommandozeilen-Interface**
   - Unified CLI für alle Funktionen
   - Scripting-Unterstützung

### 6.2 Dokumentation

- Anwenderhandbuch
- Administrationshandbuch
- API-Dokumentation
- Migrations-Leitfaden
- Best Practices Guide
- Beispiel-Projekte

### 6.3 Support-Materialien

- Installations-Skripte
- Beispiel-Schemas
- Test-Datensätze
- Troubleshooting-Guide

### 6.4 Community und Open-Source-Infrastruktur

**Code-Repository:**
- GitHub-Repository mit MIT-Lizenz
- Branch-Strategie: main, develop, feature/*, release/*
- Automated CI/CD mit GitHub Actions

**Öffentliche Roadmap:**
- GitHub Projects Board für Transparenz
- Quartalsweise Roadmap-Updates
- Milestone-Planung mit konkreten Zielen

**Issue-Tracking:**
- Issue-Templates für Bug-Reports, Feature-Requests, Fragen
- Labeling-System: bug, enhancement, documentation, good-first-issue, help-wanted
- Issue-Triage innerhalb von 48 Stunden
- Response-Zeit-Ziele: 72 Stunden für Bugs, 1 Woche für Features

**Pull-Request-Prozess:**
- PR-Template mit Checkliste (Tests, Dokumentation, Breaking Changes)
- Automatische Code-Quality-Checks (Linting, Tests, Coverage)
- Erforderliche Reviews: mindestens 1 Maintainer
- Code of Conduct für konstruktive Zusammenarbeit

**Community-Kommunikation:**
- Discord oder Slack für Echtzeit-Diskussionen
- GitHub Discussions für längerfristige Diskussionen
- Monatliche Community-Calls (optional)
- Newsletter für wichtige Updates

**Dokumentation:**
- Contributor Guide mit Setup-Anleitung
- Architecture Decision Records (ADRs) für Transparenz
- Coding Guidelines und Style Guide
- Release-Prozess-Dokumentation


---

## 7. Rahmenbedingungen

### 7.1 Technische Rahmenbedingungen

- Unterstützung aktueller LTS-Versionen der Zieldatenbanken
- Kompatibilität mit Standard-SQL soweit möglich
- Verwendung etablierter Dateiformate (JSON, YAML, CSV)

### 7.2 Organisatorische Rahmenbedingungen

**Open-Source-Lizenzierung:**
- Apache 2.0 oder MIT-Lizenz (Community-Abstimmung vor 1.0-Release)
- Contributor License Agreement (CLA) für größere Beiträge
- Klare Lizenz-Kompatibilität für Dependencies

**Community-Governance:**
- Öffentliches Repository auf GitHub
- Benevolent Dictator For Life (BDFL) oder Steering Committee-Modell
- Transparente Entscheidungsprozesse
- Dokumentierte Governance in GOVERNANCE.md

**Versionsmanagement:**
- Semantic Versioning (SemVer 2.0)
- Changelog gemäß Keep a Changelog
- Deprecation-Policy: Mindestens 2 Minor-Versionen Vorlaufzeit
- Long-Term Support (LTS) für Major-Versionen (24 Monate)

**Release-Prozess:**
- Regelmäßige Releases (vierteljährlich für Minor, monatlich für Patch)
- Beta/RC-Phasen für Major-Releases
- Release Notes mit Migration Guide
- Backwards-Kompatibilität wo möglich

**Qualitätssicherung:**
- Mandatory Code Reviews vor Merge
- CI/CD-Pipeline mit automatisierten Tests
- Security-Scanning (Dependabot, Snyk)
- Performance-Benchmarks bei jedem Release

**Community-Rollen:**
- **Users**: Nutzer des Frameworks
- **Contributors**: Einmalige Beiträge (Code, Docs, Issues)
- **Committers**: Regelmäßige Beitragende mit Merge-Rechten (nach 5+ PRs)
- **Maintainers**: Core-Team mit Release-Verantwortung
- **Steering Committee**: Strategische Entscheidungen (ab 1.0-Release)

**Kommunikationswege:**
- GitHub Issues für Bugs und Features
- GitHub Discussions für Fragen und Diskussionen
- Discord/Slack für Echtzeit-Support
- Twitter/Mastodon für News

**Dokumentations-Standards:**
- Deutsch als Hauptsprache
- Docs-as-Code (Markdown, Versionskontrolle)
- Automatische API-Dokumentation
- Interaktive Tutorials und Beispiele

### 7.3 Rechtliche Rahmenbedingungen

- Keine Verwendung proprietärer Datenbank-Features
- Compliance mit Datenschutzbestimmungen (DSGVO)
- Keine Exportkontrollbeschränkungen

---

## 8. Abnahmekriterien

### 8.1 Funktionale Tests

- Erfolgreiche Migration eines Beispielschemas zwischen allen unterstützten Datenbanken
- Export und Re-Import von mindestens 1 Million Datensätzen ohne Datenverlust
- Generierung lauffähiger Migrations-Skripte für alle unterstützten Tools
- KI-gestützte Transformation einer Stored Procedure (PL/SQL → T-SQL → PostgreSQL PL/pgSQL) mit funktionaler Äquivalenz
- Testdatengenerierung: 10.000 Datensätze für ein E-Commerce-Schema mit realistischen Beziehungen

**Internationalisierungstests:**
- Verarbeitung eines Schemas mit Unicode-Tabellennamen (Kyrillisch, Chinesisch, Arabisch)
- Export/Import von 100.000 Datensätzen mit gemischten Zeichensätzen (Emoji, Sonderzeichen)
- Korrekte Sortierung von mehrsprachigen Daten gemäß Locale
- CLI-Ausgabe in mindestens 2 Sprachen (Deutsch, Englisch) mit korrekter Formatierung
- Zeitzonen-Konvertierung: Export in UTC, Import mit korrekter Zeitzone

### 8.2 Performance-Tests

**Basis-Performance:**
- DDL-Generierung für 100 Tabellen in unter 5 Sekunden
- Export von 1 Million Datensätzen in unter 100 Sekunden
- Import von 1 Million Datensätzen in unter 200 Sekunden

**Skalierbarkeits-Tests:**
- DDL-Generierung für 1.000 Tabellen in unter 30 Sekunden
- Export von 10 Millionen Datensätzen ohne Out-of-Memory-Fehler
- Inkrementelle Migration eines Schemas mit 1.000 Tabellen in unter 1 Stunde
- Parallele Verarbeitung: Mindestens 5x Speedup bei 8 CPU-Kernen (gegenüber Single-Thread)
- Checkpoint/Resume: Erfolgreicher Wiederanlauf nach simuliertem Abbruch bei 50% Fortschritt
- Partitionierte Tabelle: Export einer 100-Partitionen-Tabelle mit paralleler Verarbeitung

### 8.3 Integrationstests

- Erfolgreiche Integration in mindestens 3 verschiedene CI/CD-Pipelines
- Nachweis der Kompatibilität mit aktuellen Versionen der Zieldatenbanken
- Funktionierende Beispiel-Projekte für alle unterstützten Migrations-Tools

### 8.4 Reverse-Engineering-Tests (LF-004)

**Strukturerkennung:**
- Vollständige Extraktion von 50 Tabellen mit komplexen Beziehungen aus PostgreSQL/MySQL/SQLite
- Korrekte Erkennung aller Constraint-Typen (PRIMARY KEY, FOREIGN KEY, UNIQUE, CHECK, NOT NULL)
- Extraktion von Indizes (B-Tree, Hash, GiST, GIN, BRIN) mit korrekten Optionen
- Erkennung von partitionierten Tabellen und deren Partitionsschlüssel

**Datenbankeigene Objekte:**
- Stored Procedures: Extraktion von mindestens 10 komplexen Prozeduren mit Parametern, Variablen und Control-Flow
- Functions: User-Defined Functions (UDF) mit verschiedenen Return-Typen
- Triggers: BEFORE/AFTER, INSERT/UPDATE/DELETE mit korrekter Timing-Information
- Views: Einfache und materialisierte Views mit Join-Logik
- Sequences: Auto-Increment-Mechanismen und Sequenz-Definitionen
- Custom Types: ENUMs, Composite Types, Domain Types

**Spezial-Features:**
- PostgreSQL: Extensions (uuid-ossp, hstore, PostGIS), JSON/JSONB-Spalten, Array-Typen
- MySQL: Engine-Spezifikation (InnoDB, MyISAM), AUTO_INCREMENT, SET-Typen
- SQLite: WITHOUT ROWID Tables, Virtual Tables, FTS5 Full-Text-Search

**Komplexitätstests:**
- Schema mit 100+ Tabellen und 500+ Spalten
- Zirkuläre Referenzen mit DEFERRABLE Constraints
- Multi-Column Foreign Keys
- Composite Primary Keys

### 8.5 Datenintegritätstests (LN-009, LN-010, LN-011)

**Vollständigkeitstests:**
- Export/Import von 1 Million Datensätzen: Byte-für-Byte-Vergleich (SHA-256 Hash)
- Nullwert-Behandlung: Unterscheidung zwischen NULL, leerer String und Whitespace
- Unicode-Integrität: Emoji, Sonderzeichen, verschiedene Schriftsysteme ohne Datenverlust
- BLOB/Binary-Daten: Export/Import von Binärdaten (Bilder, PDFs) ohne Korruption

**Datentyp-Präservierung:**
- Numerische Typen: INTEGER, BIGINT, DECIMAL, FLOAT ohne Präzisionsverlust
- Temporal-Typen: DATE, TIME, TIMESTAMP mit korrekter Zeitzonen-Behandlung
- String-Typen: VARCHAR, TEXT, CHAR mit korrektem Encoding
- Spezialtypen: JSON, XML, Arrays, UUIDs bleiben valide

**Referentielle Integrität:**
- Foreign-Key-Constraints bleiben nach Import gültig
- Cascade-Operationen (ON DELETE CASCADE, ON UPDATE CASCADE) funktionieren
- Check-Constraints werden validiert
- Unique-Constraints verhindern Duplikate

**Transaktionale Konsistenz:**
- Rollback bei Fehlern: Keine Teil-Importe bei Constraint-Verletzungen
- Atomare Operationen: Alle Tabellen oder keine bei Multi-Table-Import
- ACID-Compliance: Isolation-Level bleibt erhalten
- Deadlock-Vermeidung bei parallelen Operationen

**Edge-Cases:**
- Maximale String-Längen (VARCHAR(65535))
- Grenzwerte numerischer Typen (MIN_INT, MAX_INT, Infinity, NaN)
- Leere Tabellen
- Einzelne Zeile mit 1000+ Spalten
- NULL in allen Spalten

### 8.6 Automatisierte Teststrategie

**Unit-Tests (Ziel: 80%+ Coverage):**
- Schema-Parser: Parsing von DDL-Statements aller unterstützten Datenbanken
- Type-Mapper: Korrekte Konvertierung zwischen neutralem Format und DB-spezifischen Typen
- Validator: Schema-Validierung gegen definierte Regeln
- Generator: DDL-Generierung aus neutralem Format
- Export/Import-Module: Isolierte Tests für jeden Datentyp

**Integration-Tests:**
- Round-Trip-Tests: DB → Neutral → DB' mit semantischer Äquivalenz
- Cross-DB-Migration: PostgreSQL → Neutral → MySQL → Neutral → SQLite
- Tool-Integration: Generierte Flyway/Liquibase-Scripts ausführen und validieren
- CLI-Tests: End-to-End-Tests aller CLI-Kommandos mit verschiedenen Parametern

**Property-Based Testing:**
- Generierung zufälliger, aber valider Schemas
- Fuzzing von DDL-Parser mit invaliden/unerwarteten Eingaben
- Automatische Testdaten-Generierung für verschiedene Datentyp-Kombinationen

**Regression-Tests:**
- Golden-Master-Tests für bekannte Schema-Definitionen
- Snapshot-Testing für DDL-Generierung
- Performance-Regression-Tests (max. 10% Abweichung zwischen Versionen)

**Test-Datenbanken:**
- Docker-Container für PostgreSQL, MySQL, SQLite
- Automatisches Setup/Teardown für Isolation
- Test-Fixtures mit repräsentativen Daten
- Separate Testsuites für jede Datenbankversion (PostgreSQL 12-16, MySQL 8.0-8.3)

### 8.7 Spezifische Testfälle für proprietäre Objekte

**PostgreSQL-spezifisch:**
- PL/pgSQL Stored Procedures mit Exception-Handling
- Materialized Views mit Refresh-Strategien
- Partial Indexes mit WHERE-Klauseln
- Inheritance-Hierarchien zwischen Tabellen
- JSONB-Operatoren und GIN-Indizes

**MySQL-spezifisch:**
- MySQL Events (Scheduled Jobs)
- Stored Procedures mit OUT/INOUT-Parametern
- FULLTEXT-Indizes
- Spatial Data Types (GEOMETRY, POINT, LINESTRING)
- Partition by RANGE/HASH/LIST

**SQLite-spezifisch:**
- Virtual Tables (FTS5, R-Tree)
- Strict Tables (STRICT Keyword)
- Generated Columns (STORED/VIRTUAL)
- WITHOUT ROWID-Optimierung

**Cross-Database-Kompatibilität:**
- PostgreSQL Serial → MySQL AUTO_INCREMENT → SQLite AUTOINCREMENT
- PostgreSQL Arrays → MySQL JSON → SQLite JSON (mit Fallback-Strategien)
- PostgreSQL ENUM → MySQL ENUM → SQLite CHECK Constraint
- Timestamp with/without timezone Konvertierung

### 8.8 Dokumentation

- Vollständige Abdeckung aller Features in der Dokumentation
- Mindestens 10 durchgängige Beispiel-Szenarien
- Positive Bewertung der Dokumentation durch mindestens 5 Pilotanwender
- Dokumentierte Testfälle mit erwarteten Ergebnissen

---

## 9. Erfolgskriterien

### 9.1 Quantitative Kriterien

**Technische Metriken:**
- Reduzierung des Migrations-Aufwands um mindestens 40%
- Weniger als 5% kritische Bugs in den ersten 3 Monaten nach Release
- Mindestens 80% Code-Coverage durch automatisierte Tests
- Performance-Benchmarks: DDL-Generierung 1.000 Tabellen < 30 Sekunden

### 9.2 Qualitative Kriterien

- Positive Rückmeldungen von mindestens 80% der Pilotanwender bezüglich Funktionalität und Benutzbarkeit
- Erfolgreiche Migration von mindestens 3 Produktivsystemen
- Aufnahme in mindestens 2 Enterprise-Entwicklungsstacks
- Code-Qualitätsmetriken: Maintainability Index > 70, Cyclomatic Complexity < 15 (Durchschnitt)
- Dokumentationsqualität: Vollständig, aktuell und durch Anwender-Tests als verständlich validiert
- Architektur-Nachvollziehbarkeit: Dokumentierte Architekturentscheidungen (ADRs) für alle wesentlichen Designentscheidungen
- Wartbarkeit: Code muss von menschlichen Entwicklern verstanden und gewartet werden können (validiert durch Code Reviews)

---

## 10. Glossar

| Begriff                | Erläuterung                                                                   |
| ---------------------- | ----------------------------------------------------------------------------- |
| DDL                    | Data Definition Language - SQL-Befehle zur Definition von Datenbankstrukturen |
| Migration              | Kontrollierte Änderung von Datenbankstrukturen                                |
| Schema                 | Strukturdefinition einer Datenbank (Tabellen, Spalten, Beziehungen)           |
| Vendor Lock-in         | Abhängigkeit von einem spezifischen Hersteller oder Produkt                   |
| CLI                    | Command Line Interface - Kommandozeilen-Schnittstelle                         |
| CI/CD                  | Continuous Integration/Continuous Deployment                                  |
| LTS                    | Long Term Support - Langzeitunterstützung                                     |
| Constraint             | Einschränkung/Bedingung für Datenbankfelder                                   |
| Rollback               | Rückgängigmachen einer Migration                                              |
| Adapter                | Anpassungsschicht für spezifische Tools oder Systeme                          |
| LLM                    | Large Language Model - Großes Sprachmodell für KI-gestützte Transformationen  |
| Ollama                 | Open-Source-Plattform zum lokalen Betrieb von LLMs                            |
| Prompt                 | Eingabetext für ein LLM zur Steuerung der KI-Ausgabe                          |
| Token                  | Kleinste Verarbeitungseinheit in LLMs (ca. 0,75 Wörter)                       |
| Streaming              | Kontinuierliche Datenverarbeitung ohne vollständiges Laden in Speicher        |
| Checkpoint             | Zwischenspeicherpunkt zur Wiederaufnahme unterbrochener Operationen           |
| Partitionierung        | Aufteilung großer Tabellen in kleinere, verwaltbare Segmente                  |
| ADR                    | Architecture Decision Record - Dokumentation von Architekturentscheidungen    |
| I18n                   | Internationalization - Internationalisierung von Software                     |
| L10n                   | Localization - Lokalisierung für spezifische Sprachen/Regionen                |
| UTF-8                  | Unicode Transformation Format 8-bit - Standard-Encoding für Unicode           |
| ICU                    | International Components for Unicode - Bibliothek für Unicode-Operationen     |
| CLDR                   | Common Locale Data Repository - Repository für lokalisierte Daten             |
| BOM                    | Byte Order Mark - Markierung am Dateianfang zur Encoding-Erkennung            |
| Locale                 | Spracheinstellung mit regionalen Anpassungen (z.B. de_DE, en_US)              |
| Collation              | Sortierregel für Zeichenketten in verschiedenen Sprachen                      |
| Grapheme               | Kleinste visuelle Einheit eines Schriftsystems (kann mehrere Bytes sein)      |
| RTL                    | Right-to-Left - Rechts-nach-Links-Schreibrichtung (Arabisch, Hebräisch)       |
| Unit-Test              | Test einzelner Komponenten in Isolation                                       |
| Integration-Test       | Test des Zusammenspiels mehrerer Komponenten                                  |
| Round-Trip             | Konvertierung A → B → A zur Überprüfung von Datenerhalt                       |
| Property-Based Testing | Testmethode mit automatischer Generierung vieler Testfälle                    |
| Fuzzing                | Testen mit zufälligen/invaliden Eingaben zur Fehlersuche                      |
| Mock                   | Simulierte Komponente für isolierte Tests                                     |
| Fixture                | Vordefinierte Test-Daten für reproduzierbare Tests                            |
| Coverage               | Maß für getesteten Code (Line-, Branch-, Path-Coverage)                       |
| Golden Master          | Referenz-Ausgabe für Regression-Tests                                         |
| Snapshot-Test          | Test durch Vergleich mit gespeicherter Referenz-Ausgabe                       |
| Shrinking              | Automatische Vereinfachung fehlgeschlagener Testfälle                         |
| SemVer                 | Semantic Versioning - Versionierungsschema MAJOR.MINOR.PATCH                  |
| Contributor            | Person, die zum Projekt beiträgt (Code, Docs, Issues)                         |
| Maintainer             | Kernteam-Mitglied mit Release-Verantwortung                                   |
| Committer              | Contributor mit Merge-Rechten                                                 |
| BDFL                   | Benevolent Dictator For Life - Führungsstil bei Open-Source-Projekten         |
| CLA                    | Contributor License Agreement - Rechte-Übertragung für Beiträge               |
| CoC                    | Code of Conduct - Verhaltenskodex für Community                               |
| Bus Factor             | Anzahl Personen, die ausfallen können ohne Projekt zu gefährden               |
| Good First Issue       | Anfängerfreundliches Issue für neue Contributors                              |

---

## Anhang A: Use Cases

### UC-01: Neue Anwendung entwickeln
**Akteur**: Entwickler  
**Vorbedingung**: Projekt-Setup abgeschlossen  
**Ablauf**:
1. Entwickler definiert Datenbankschema in YAML
2. System validiert Schema-Definition
3. System generiert DDL für Zieldatenbank
4. Entwickler führt DDL in Entwicklungsumgebung aus
5. System generiert Migrations-Dateien für Produktivumgebung

**Nachbedingung**: Datenbankstruktur in allen Umgebungen verfügbar

### UC-02: Datenbank-System wechseln
**Akteur**: Datenbankadministrator  
**Vorbedingung**: Bestehende Datenbank in Betrieb  
**Ablauf**:
1. System exportiert aktuelle Struktur und Daten (ggf. initiiert durch Administrator).
2. System konvertiert in neutrales Format.
3. Optional: System erzeugt aus dem neutralen Format eine textbasierte Spezifikation der datenbankeigenen Objekte (z.B. Stored Procedures).
4. Administrator wählt neue Zieldatenbank.
5. System generiert DDL für neue Datenbank.
6. System importiert Daten in neue Datenbank.
7. Administrator validiert Migration.

**Nachbedingung**: Vollständige Datenbank auf neuem System

### UC-03: Test-Umgebung aufsetzen
**Akteur**: QA-Engineer  
**Vorbedingung**: Schema-Definition vorhanden  
**Ablauf**:
1. QA-Engineer lädt Schema-Definition
2. System generiert DDL für Test-Datenbank
3. QA-Engineer importiert Test-Datensätze
4. System validiert Datenintegrität

**Nachbedingung**: Funktionsfähige Test-Umgebung

### UC-04: KI-gestützte Stored Procedure Migration
**Akteur**: Datenbankadministrator
**Vorbedingung**: Migration von Oracle zu PostgreSQL geplant, Stored Procedures vorhanden
**Ablauf**:
1. Administrator exportiert Oracle-Schema mit Stored Procedures
2. System erkennt PL/SQL-Stored Procedures
3. Administrator wählt KI-Backend (z.B. lokales Ollama-Modell wegen Datenschutz)
4. System transformiert PL/SQL in abstraktes Markdown-Format mit Logikbeschreibung
5. Administrator prüft und validiert die abstrakte Beschreibung
6. System generiert PostgreSQL PL/pgSQL-Code aus der Beschreibung
7. System führt Syntax-Validierung durch
8. Administrator führt optionale Test-Suite zur funktionalen Äquivalenz aus
9. System erstellt Diff-Report: Original vs. Transformiert
10. Administrator genehmigt und übernimmt die Migration

**Nachbedingung**: Funktional äquivalente Stored Procedures in PostgreSQL

### UC-05: Schema Änderung
**Akteur**: Entwickler
**Vorbedingung**: Bestehendes Datenbankschema in Entwicklungsumgebung
**Ablauf**:
1. Entwickler ändert Datenbankschema in neutralem Format (z.B. YAML).
2. System validiert die Schema-Änderung.
3. System generiert ein Migrationsskript (DDL) für die Zieldatenbank, das die Änderungen anwendet.
4. Entwickler wendet das Migrationsskript in der Entwicklungsumgebung an.
5. System generiert Migrationsdateien für die Produktivumgebung (z.B. Flyway-kompatibel).
6. Entwickler oder DevOps-Engineer führt die Migration in der Produktivumgebung durch.
**Nachbedingung**: Datenbankschema in allen Umgebungen aktualisiert und konsistent.

### UC-06: Testdaten zurücksetzen
**Akteur**: QA-Engineer oder Entwickler
**Vorbedingung**: Testdatenbank mit Testdaten in einem definierten Zustand
**Ablauf**:
1.  QA-Engineer/Entwickler initiiert den Reset der Testdaten.
2.  System löscht oder überschreibt die vorhandenen Testdaten in der Testdatenbank.
3.  System importiert einen vordefinierten Satz von Testdaten in die Testdatenbank.
4.  System validiert die Integrität der neu importierten Testdaten.
**Nachbedingung**: Testdatenbank ist in einem sauberen, definierten Ausgangszustand für weitere Tests.

---

## Anhang B: Beispiel Schema-Definition

```yaml
# Beispiel einer neutralen Schema-Definition
name: "E-Commerce System"
version: "1.0"

tables:
  customers:
    description: "Kundenstammdaten"
    columns:
      id:
        type: identifier
        auto_increment: true
      email:
        type: email
        required: true
        unique: true
      name:
        type: text
        max_length: 100
        required: true
      created_at:
        type: datetime
        default: current_timestamp
    
  orders:
    description: "Bestellungen"
    columns:
      id:
        type: identifier
        auto_increment: true
      customer_id:
        type: reference
        references: customers.id
        on_delete: restrict
      order_date:
        type: datetime
        required: true
      total_amount:
        type: decimal
        precision: 10
        scale: 2
      status:
        type: enum
        values: [pending, processing, shipped, delivered, cancelled]
        default: pending
    indices:
      - columns: [customer_id, order_date]
        type: btree
      - columns: [status]
        type: hash
```

---

## Anhang C: Community-Roadmap

### C.1 Roadmap (Öffentlich auf GitHub Projects)

**Phase 1: MVP (0.1.0 - 0.5.0) - Monate 1-6**
- ✅ Core-Funktionalität: Schema-Definition in YAML
- ✅ DDL-Generierung für PostgreSQL, MySQL, SQLite
- ✅ Basis Export/Import für Standard-Datentypen
- ✅ CLI mit grundlegenden Kommandos
- 🎯 Ziel: Funktionaler Prototyp für Early Adopters

**Phase 2: Beta (0.6.0 - 0.9.0) - Monate 7-12**
- 🚧 Reverse-Engineering (LF-004)
- 🚧 Flyway/Liquibase-Integration
- 🚧 Erweiterte Datentypen (JSON, Arrays, Binary)
- 🚧 Internationalisierung (Deutsch, Englisch)
- 🚧 Umfassende Dokumentation
- 🎯 Ziel: Production-ready für erste Projekte

**Phase 3: Stable (1.0.0) - Monat 13-15**
- ✨ Vollständige Datenintegrität (SHA-256-Verifikation)
- ✨ Performance-Optimierungen (Streaming, Parallelisierung)
- ✨ Skalierbarkeit (1.000+ Tabellen, 10+ TB)
- ✨ Security-Audit
- 🎯 Ziel: Enterprise-ready Release

**Phase 4: Growth (1.1.0 - 2.0.0) - Monate 16-24**
- 🔮 KI-Integration (LF-017: Stored Procedure Transformation)
- 🔮 Weitere Datenbanken (Oracle, MS SQL Server)
- 🔮 GUI für Schema-Design
- 🔮 REST-API
- 🔮 Testdaten-Generierung mit KI
- 🎯 Ziel: Feature-Completeness und Ökosystem-Wachstum

---
