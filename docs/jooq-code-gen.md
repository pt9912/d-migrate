# jOOQ Codegen via CLI in d-migrate

**Konzept- und Entscheidungsdokument fuer die Generierung von jOOQ-Klassen ueber ein separates CLI-Tool**

> Dokumenttyp: Architektur-Notiz / Integrationskonzept

---

## 1. Ziel

Fuer `d-migrate` soll bewertet werden, ob und wie sich jOOQ-Klassen ueber ein eigenes CLI-Kommando generieren lassen, insbesondere fuer Oracle-basierte Quellschemata.

Ziel ist **nicht**, einen eigenen jOOQ-Ersatz zu bauen, sondern eine robuste und wartbare Integration in die bestehende CLI-Architektur zu definieren.

---

## 2. Kurzfassung

Ja, `d-migrate` kann jOOQ-Klassen per CLI-Tool generieren lassen.

Die empfohlene Umsetzung ist:

- eigenes CLI-Kommando in `d-migrate`
- programmgesteuerter Aufruf des offiziellen jOOQ-Codegenerators
- optional zwei Eingangswege:
  - Codegen aus einer Live-Datenbank per JDBC
  - Codegen aus DDL-Dateien statt aus einer Live-Datenbank

**Nicht empfohlen** ist ein komplett eigener Generator, der jOOQ-Klassen ohne Nutzung von `jooq-codegen` selbst erzeugt. Das waere technisch moeglich, aber deutlich fehleranfaelliger und langfristig teurer in der Wartung.

---

## 3. Warum kein eigener jOOQ-Klassengenerator

Ein eigener Generator muesste einen grossen Teil der Funktionalitaet nachbauen, die jOOQ bereits mitliefert:

- Tabellen, Spalten, Primary Keys, Foreign Keys, Indizes
- korrekte Datentyp-Abbildung
- Naming-Strategien und Paketstruktur
- Generierung von `Table`, `Record`, `Field` und zugehoerigen Artefakten
- Verhalten ueber mehrere jOOQ-Versionen hinweg

Das fuehrt zu folgenden Nachteilen:

- hohe Kopplung an jOOQ-Interne Strukturen
- dauerhafter Pflegeaufwand bei jOOQ-Upgrades
- erhoehte Gefahr von API-Inkompatibilitaeten
- doppelter Implementierungsaufwand fuer ein bereits geloestes Problem

Fazit: Wenn jOOQ-Zielartefakte benoetigt werden, sollte moeglichst auch der offizielle jOOQ-Generator verwendet werden.

---

## 4. Empfohlene Architektur

### 4.1 CLI-Ebene

Erweiterung der bestehenden CLI um ein neues Kommando, zum Beispiel:

```bash
d-migrate jooq generate --jdbc-url jdbc:oracle:thin:@//dbhost:1521/ORCLCDB --user app --password-env DB_PASSWORD
```

oder alternativ:

```bash
d-migrate jooq generate --ddl build/schema/oracle.sql --target-dir src/generated/jooq
```

Moegliche Kommandoformen:

- `d-migrate jooq generate`
- `d-migrate generate jooq`

Beides ist technisch moeglich. Aus Konsistenzgruenden mit anderen Themenbereichen wirkt `d-migrate jooq generate` als eigener Command-Baum klarer.

### 4.2 Integrationsschicht

Empfohlen wird ein dediziertes Integrationsmodul, zum Beispiel:

```text
d-migrate-integrations/
  src/main/kotlin/dev/dmigrate/integration/jooq/
    JooqCodegenAdapter.kt
    JooqCodegenConfig.kt
    JooqCodegenRunner.kt
```

Verantwortung dieser Schicht:

- Aufbereitung der CLI-Parameter
- Erzeugung der jOOQ-Konfiguration
- Aufruf von `org.jooq.codegen.GenerationTool`
- Fehlerbehandlung und Ausgabe fuer die CLI

### 4.3 Codegen-Backend

Intern sollte **nicht** eigener Java/Kotlin-Code fuer die eigentliche Artefakt-Erzeugung geschrieben werden. Stattdessen sollte `GenerationTool.generate(...)` genutzt werden.

Damit bleibt `d-migrate` Orchestrator, waehrend jOOQ die Generierung selbst uebernimmt.

---

## 5. Empfohlene Betriebsmodi

### 5.1 Modus A: Live-Datenbank per JDBC

Geeignet fuer:

- bestehende Oracle-, PostgreSQL- oder MySQL-Datenbanken
- Reverse Engineering aus dem realen Schemazustand

Vorteile:

- kein vorgeschalteter DDL-Export noetig
- echte Metadaten aus dem Zielsystem
- gut fuer produktionsnahe Schemaquellen

Nachteile:

- Datenbankzugriff und Treiberkonfiguration erforderlich
- in CI/CD haeufig schwerer reproduzierbar

### 5.2 Modus B: DDL-Dateien

Geeignet fuer:

- reproduzierbare Builds
- Generierung aus versionierten SQL-Dateien
- spaetere Kopplung mit `d-migrate schema generate`

Vorteile:

- kein Live-DB-Zugriff erforderlich
- gut fuer CI/CD und lokale Entwicklung
- leichter deterministisch testbar

Nachteile:

- nur so gut wie die zugrundeliegende DDL
- vendor-spezifische Feinheiten koennen verloren gehen

---

## 6. Oracle-spezifische Einordnung

Oracle ist fuer diesen Anwendungsfall der wichtigste Sonderfall.

### 6.1 Technische Aspekte

Bei Oracle muessen unter anderem sauber behandelt werden:

- `NUMBER` und seine Varianten
- `VARCHAR2`, `CHAR`, `CLOB`, `BLOB`
- `TIMESTAMP WITH TIME ZONE`
- Sequences
- ggf. Synonyms und mehrere Schemas

Diese Details sprechen **gegen** einen selbstgebauten Generator und **fuer** die Wiederverwendung von jOOQ-Codegen.

### 6.2 Lizenzaspekt

Die Nutzung von jOOQ mit Oracle ist in der Praxis ein Lizenzthema und sollte frueh geprueft werden. Die Oracle-Unterstuetzung ist bei jOOQ nicht mit der Open-Source-Edition gleichzusetzen.

Fuer die Projektplanung bedeutet das:

- Oracle-Codegen nicht als "kostenlose Selbstverstaendlichkeit" annehmen
- Abhaengigkeiten und Distribution bewusst planen
- Lizenzpruefung vor produktiver Einfuehrung abschliessen

---

## 7. Vorschlag fuer CLI-API

### 7.1 Minimaler erster Zuschnitt

```bash
d-migrate jooq generate \
  --jdbc-url jdbc:oracle:thin:@//localhost:1521/ORCLCDB \
  --user app \
  --password-env DB_PASSWORD \
  --schema APP \
  --package-name com.example.jooq \
  --target-dir src/generated/jooq
```

### 7.2 Alternative ueber DDL

```bash
d-migrate jooq generate \
  --ddl build/schema/oracle.sql \
  --package-name com.example.jooq \
  --target-dir src/generated/jooq
```

### 7.3 Sinnvolle Optionen

| Option | Bedeutung |
|---|---|
| `--jdbc-url` | JDBC-Quelle fuer Live-Codegen |
| `--user` | Datenbankbenutzer |
| `--password` | Passwort direkt (nur falls wirklich noetig) |
| `--password-env` | Passwort aus Umgebungsvariable |
| `--schema` | Quellschema |
| `--ddl` | Eingabe ueber SQL-DDL-Datei(en) |
| `--package-name` | Zielpaket fuer generierten Code |
| `--target-dir` | Zielverzeichnis |
| `--includes` | Regex fuer einzuschliessende Objekte |
| `--excludes` | Regex fuer auszuschliessende Objekte |
| `--forced-type` | Optionale Type-Mappings / Overrides |
| `--dry-run` | Konfiguration pruefen, aber nichts schreiben |

---

## 8. Beziehung zu d-migrate

Diese Integration sollte als **optionale Zusatzfunktion** behandelt werden.

`d-migrate` bleibt primaer:

- Schema-Modellierer
- Validator
- DDL-Generator
- spaeter ggf. Reverse-Engineering- und Migrationswerkzeug

Die jOOQ-Integration ist dagegen:

- ein Downstream-Use-Case
- ein Adapter fuer Java-Anwendungen, die jOOQ verwenden

Das ist architektonisch sauber, weil die Kernlogik von `d-migrate` nicht von jOOQ abhaengen muss.

---

## 9. Empfohlene Ausbaustufen

### Stufe 1

Direkter CLI-Aufruf von jOOQ-Codegen gegen JDBC.

Ziel:

- schnellster Nutzwert
- geringste Eigenimplementierung

### Stufe 2

Codegen aus DDL-Dateien ueber jOOQ `DDLDatabase`.

Ziel:

- reproduzierbare Builds ohne Live-Datenbank
- gute CI/CD-Integration

### Stufe 3

Kopplung mit `d-migrate`:

```text
schema.yaml -> d-migrate DDL -> jOOQ Codegen
```

Ziel:

- neutral definiertes Schema
- daraus DB-DDL
- daraus jOOQ-Artefakte

Diese Stufe ist besonders interessant, sobald Oracle als Dialekt im Projekt selbst unterstuetzt wird.

---

## 10. Entscheidung

Empfohlen wird:

1. `docs/jooq-code-gen.md` als Referenz fuer die Architekturentscheidung
2. spaeter ein CLI-Kommando `d-migrate jooq generate`
3. intern Nutzung von `jooq-codegen` statt eigenem Artefaktgenerator
4. zuerst JDBC-Modus, danach optional DDL-Modus
5. Oracle frueh unter Lizenz- und Distributionsgesichtspunkten klaeren

Nicht empfohlen wird:

- ein vollstaendig eigener Generator fuer jOOQ-Klassen

---

## 11. Externe Referenzen

Offizielle jOOQ-Dokumentation:

- Programmatic code generation:
  - https://www.jooq.org/doc/latest/manual/code-generation/codegen-programmatic/
- Code generation from DDL:
  - https://www.jooq.org/doc/latest/manual/code-generation/codegen-meta-sources/codegen-ddl/
- Download / Edition overview:
  - https://www.jooq.org/download/

Hinweis: Diese Links sollten bei einer spaeteren Implementierung erneut geprueft werden, falls sich Editionsmodell, Oracle-Unterstuetzung oder Konfigurationsdetails in neueren jOOQ-Versionen aendern.
