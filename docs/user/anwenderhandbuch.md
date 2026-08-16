# Benutzerhandbuch: d-migrate

**Software-Version:** 1.0.1  ·  **Handbuch-Version:** 0.5  ·  **Stand:** 16.08.2026
**Gültigkeitsbereich:** PostgreSQL, MySQL/MariaDB, SQLite

Dieses Handbuch zeigt, wie Sie mit d-migrate Ihre Aufgaben erledigen — Schemata
beschreiben, Datenbanken aufbauen, Daten übertragen und Migrationen ausrollen.
Es ist nach Aufgaben gegliedert, nicht nach Funktionen. Die vollständige
Befehls- und Optionsreferenz finden Sie im [Anhang](#8-anhang).

> **Aufrufkonvention:** Die Beispiele verwenden das installierte Kommando
> `d-migrate`. Aus einem Quellcode-Build entspricht das
> `./gradlew :adapters:driving:cli:run --args="…"`.

## Inhalt

1. [Einleitung](#1-einleitung)
2. [Erste Schritte](#2-erste-schritte)
3. [Aufgaben](#3-aufgaben)
4. [Konfiguration](#4-konfiguration)
5. [Fehlerbehebung](#5-fehlerbehebung)
6. [Häufige Fragen (FAQ)](#6-häufige-fragen-faq)
7. [Glossar](#7-glossar)
8. [Anhang](#8-anhang)
9. [Änderungshistorie](#9-änderungshistorie)

---

## 1. Einleitung

### Zweck der Software

d-migrate überträgt Datenbankschemata und -inhalte zwischen verschiedenen
Datenbanksystemen. Sie beschreiben Ihr Schema einmal in einem
**neutralen, herstellerunabhängigen Format** und erzeugen daraus das passende
SQL für PostgreSQL, MySQL/MariaDB oder SQLite. Daten lassen sich exportieren,
importieren und direkt von einer Datenbank in eine andere übertragen.

### Zielgruppe dieses Handbuchs

Anwenderinnen und Anwender, die d-migrate über die Kommandozeile bedienen —
typischerweise Entwicklerinnen, Datenbank-Verantwortliche und Personen, die
eine Datenmigration durchführen. Vorausgesetzt wird Grundwissen über
Datenbanken und den Umgang mit einem Terminal; Kenntnisse über den internen
Aufbau von d-migrate sind **nicht** nötig.

Betrieb und Deployment werden im
[Administrationshandbuch](administrationshandbuch.md) behandelt, vollständige
Migrationsszenarien im [Migrations-Leitfaden](migrations-leitfaden.md). Wie Sie
d-migrate als MCP-Server bereitstellen, steht in
[Abschnitt 3.15](#315-d-migrate-als-mcp-server-für-ki-agenten-bereitstellen); das
MCP-Protokoll und der Tool-Katalog stehen in der
[API-Referenz](api-referenz.md).

### Voraussetzungen

- **Mit Docker (empfohlen):** eine funktionierende Docker-Installation. Kein
  JDK nötig.
- **Ohne Docker:** Java 21 oder neuer.
- Zugangsdaten (URL, Benutzer, Passwort) zu den beteiligten Datenbanken.

---

## 2. Erste Schritte

### 2.1 Installation

Der schnellste Weg führt über das fertige Container-Image. Stellen Sie sicher,
dass Docker läuft, und prüfen Sie die Installation:

```bash
docker run --rm ghcr.io/pt9912/d-migrate:latest --version
```

Damit Sie kürzere Befehle schreiben können, richten Sie einen Alias ein, der
das aktuelle Verzeichnis in den Container einhängt:

```bash
alias d-migrate='docker run --rm --user "$(id -u):$(id -g)" -v "$(pwd)":/work -w /work ghcr.io/pt9912/d-migrate:latest'
```

Alle weiteren Beispiele in diesem Handbuch verwenden den Befehl `d-migrate`.
Weitere Installationswege (Release-Download, Homebrew, Bauen aus dem Quellcode)
stehen im [Administrationshandbuch](administrationshandbuch.md#2-deployment).

> **Hinweis (Docker und Schreibrechte):** Das Image läuft als **non-root**
> Benutzer. Damit Befehle, die Dateien in Ihr Verzeichnis schreiben
> (`schema generate --output …`, `schema reverse --output …`, `data transfer` in
> Datei-Ziele), die Ausgabe mit Ihrer Benutzer-Kennung ablegen können, enthält
> der Alias oben `--user "$(id -u):$(id -g)"`. Ohne diesen Zusatz schlägt das
> Schreiben in ein eingehängtes Host-Verzeichnis mit „permission denied" fehl.
> Reine Lesebefehle (`schema validate`, `schema compare`) funktionieren auch ohne.

> **Hinweis (Docker und `localhost`):** Innerhalb des Containers verweist
> `localhost` auf den Container selbst, nicht auf Ihren Rechner. Läuft die
> Datenbank lokal auf dem Host, verwenden Sie in der URL
> `host.docker.internal` statt `localhost` (oder starten Sie d-migrate ohne
> Docker). Datenbanken im selben Docker-Netz sprechen Sie über ihren
> Service-Namen an.

### 2.2 Grundkonzept: das neutrale Modell

d-migrate arbeitet nie direkt „von Datenbank A nach Datenbank B". Stattdessen
gibt es eine **Zwischenform** — das neutrale Modell:

```
Quelle  ──►  Neutrales Modell (YAML)  ──►  Ziel
```

Sie beschreiben (oder lesen per Reverse Engineering) Ihr Schema in dieser
neutralen YAML-Form. Daraus erzeugt d-migrate das passende SQL für jedes
Zielsystem. Dadurch müssen Sie die Unterschiede der einzelnen Datenbanken nicht
selbst kennen — d-migrate übersetzt sie für Sie.

### 2.3 Schnelldurchlauf in fünf Minuten

Dieser Durchlauf erzeugt aus einer Schemabeschreibung lauffähiges SQL.

1. Legen Sie eine Datei `mein-schema.yaml` an:

   ```yaml
   schema_format: "1.0"
   name: "Webshop"
   version: "1.0.0"

   tables:
     customers:
       columns:
         id:
           type: identifier
           auto_increment: true
         email:
           type: text
           max_length: 254
           required: true
           unique: true
       primary_key: [id]
   ```

2. Prüfen Sie das Schema:

   ```bash
   d-migrate schema validate --source mein-schema.yaml
   ```

3. Erzeugen Sie PostgreSQL-SQL:

   ```bash
   d-migrate schema generate --source mein-schema.yaml --target postgresql
   ```

**Ergebnis:** Auf dem Bildschirm erscheint das `CREATE TABLE`-Statement für
PostgreSQL. Sie haben aus einer neutralen Beschreibung zielspezifisches SQL
erzeugt — ohne PostgreSQL-Syntax selbst schreiben zu müssen.

> Dies ist bewusst ein Minimal-Einstieg. Ein **vollständiges, realistisches
> Beispiel** — eine komplette Migration einer PostgreSQL-Datenbank nach MySQL —
> finden Sie in [3.13](#313-komplettbeispiel-eine-datenbank-von-postgresql-nach-mysql-migrieren).

### 2.4 So ist die Bedienung aufgebaut

Jeder Aufruf folgt demselben Muster:

```
d-migrate [globale Optionen] <Gruppe> <Befehl> [Befehlsoptionen]
```

- **Gruppen:** `schema` (Struktur), `data` (Inhalte), `export`
  (Migrationsdateien für Fremdwerkzeuge) und `mcp` (Server-Betrieb).
- **Hilfe:** `--help` funktioniert an jeder Stelle, z. B.
  `d-migrate schema generate --help`.
- **Quelle/Ziel angeben:** `--source` und `--target` akzeptieren je nach Befehl
  einen Dateipfad, eine Datenbank-URL oder eine benannte Verbindung (siehe
  [4.1](#41-verbindungen-benennen)); einige Befehle (z. B. `schema validate`)
  akzeptieren `-` für stdin, sodass Sie ein Schema hineinpipen können.
- **Ein- und Ausgabe:** Ergebnisse gehen nach „stdout", Meldungen und Fortschritt
  nach „stderr". So können Sie Ergebnisse weiterleiten (`… > datei`), ohne dass
  Statusmeldungen stören.
- **Erfolg erkennen:** Jeder Befehl liefert einen Rückgabewert (Exit-Code); `0`
  bedeutet Erfolg. Die vollständige Liste steht in
  [Anhang B](#anhang-b--exit-codes).

Wie es weitergeht, zeigt [Abschnitt 3](#3-aufgaben).

---

## 3. Aufgaben

Jede Aufgabe folgt demselben Aufbau: **Ziel**, **Voraussetzungen**,
**Vorgehen** (nummeriert), **Ergebnis** und **Hinweise**. Die vollständigen
Optionen jedes Befehls stehen in [Anhang A](#anhang-a--befehls--und-optionsreferenz).

### 3.1 Ein Schema beschreiben und prüfen

**Ziel:** Ein neues Datenbankschema in neutraler Form erstellen und auf Fehler
prüfen.

**Voraussetzungen:** Ein Texteditor.

**Vorgehen:**

1. Erstellen Sie eine YAML-Datei (z. B. `mein-schema.yaml`) mit Tabellen,
   Spalten und Schlüsseln. Ein vollständiges Beispiel:

   ```yaml
   schema_format: "1.0"
   name: "Webshop"
   version: "1.0.0"

   custom_types:
     order_status:
       kind: enum
       values: [pending, processing, shipped, delivered, cancelled]

   tables:
     customers:
       description: "Kundenstammdaten"
       columns:
         id:           { type: identifier, auto_increment: true }
         email:        { type: text, max_length: 254, required: true, unique: true }
         name:         { type: text, max_length: 100, required: true }
         created_at:   { type: datetime, timezone: true, default: current_timestamp }
       primary_key: [id]
       indices:
         - { name: idx_customers_email, columns: [email], type: btree }

     orders:
       description: "Bestellungen"
       columns:
         id:           { type: identifier, auto_increment: true }
         customer_id:  { type: integer, required: true, references: { table: customers, column: id, on_delete: restrict } }
         total:        { type: decimal, precision: 10, scale: 2 }
         status:       { type: enum, ref_type: order_status, default: "pending" }
         ordered_at:   { type: datetime, required: true }
       primary_key: [id]
   ```

2. Prüfen Sie das Schema:

   ```bash
   d-migrate schema validate --source mein-schema.yaml
   ```

**Ergebnis:** Bei einem gültigen Schema sehen Sie:

```
Validating schema 'Webshop' v1.0.0...

  Tables:      2 found
  Columns:     9 found
  Indices:     1 found
  Constraints: 0 found

Results:
  ✓ Validation passed

Validation passed: 0 warning(s)
```

Bei einem Fehler — etwa wenn ein Fremdschlüssel auf eine nicht existierende
Tabelle zeigt — nennt d-migrate Code und Stelle und beendet mit Exit 3:

```
Results:
  ✗ Error [E002]: Foreign key references non-existent table 'payments'
    → tables.orders.columns.customer_id.references.table

Validation failed: 1 error(s), 0 warning(s)
```

**Hinweise:**

- Verfügbare Spaltentypen stehen in [Anhang C](#anhang-c--neutrales-typsystem).
- Die **vollständige** Element-Referenz — Tabellen, Spalten-Attribute, Indizes,
  Constraints, Partitionierung, Custom Types, Views, Trigger, Routinen,
  Sequenzen und Spatial-Typen — steht in
  [Anhang F](#anhang-f--schema-referenz).
- Findet d-migrate einen Fehler, nennt es einen Code (z. B. `E002`) und den Pfad
  zur fehlerhaften Stelle. Die Codes sind in
  [Anhang D](#anhang-d--fehler--und-warnungscodes) erklärt.
- Für maschinenlesbare Ausgabe stellen Sie `--output-format json` voran.

### 3.2 SQL für eine Zieldatenbank erzeugen

**Ziel:** Aus dem neutralen Schema ein `CREATE`-Skript für eine bestimmte
Datenbank erzeugen.

**Voraussetzungen:** Ein geprüftes Schema (siehe [3.1](#31-ein-schema-beschreiben-und-prüfen)).

**Vorgehen:**

1. Erzeugen Sie das SQL und schreiben Sie es in eine Datei:

   ```bash
   d-migrate schema generate --source mein-schema.yaml --target postgresql \
       --output schema.sql
   ```

   Für `--target` sind `postgresql`, `mysql` oder `sqlite` möglich.

2. Optional: Erzeugen Sie zusätzlich ein Rücknahme-Skript:

   ```bash
   d-migrate schema generate --source mein-schema.yaml --target postgresql \
       --output schema.sql --generate-rollback
   ```

3. Optional: **reproduzierbare** Ausgabe ohne Laufzeit-Timestamps in DDL und
   Report (z. B. für Diffs/Reviews):

   ```bash
   d-migrate schema generate --source mein-schema.yaml --target postgresql \
       --output schema.sql --deterministic
   ```

**Ergebnis:** Es entstehen `schema.sql` (das `CREATE`-Skript) und
`schema.report.yaml` (ein Bericht über vorgenommene Übersetzungen). Mit
`--generate-rollback` zusätzlich `schema.rollback.sql` mit den passenden
`DROP`-Anweisungen. Ein Auszug aus `schema.sql` (PostgreSQL):

```sql
CREATE TABLE "customers" (
    "id" SERIAL,
    "email" VARCHAR(254) NOT NULL UNIQUE,
    "name" VARCHAR(100) NOT NULL,
    "created_at" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("id")
);
```

**Hinweise:**

- Lassen Sie `--output` weg, erscheint das SQL direkt auf dem Bildschirm.
- Enthält Ihr Schema Trigger oder Funktionen und Sie möchten erst Daten laden,
  bevor diese aktiv werden, teilen Sie die Ausgabe mit `--split pre-post` (siehe
  [3.7, Hinweise](#37-daten-in-eine-datenbank-laden-import)).
- Wenn der Bericht Codes wie `E056` (Sequenzen) oder Warnungen enthält, sehen
  Sie in [3.12](#312-sequenzenautowerte-korrekt-mitnehmen) bzw.
  [Anhang D](#anhang-d--fehler--und-warnungscodes) nach.

### 3.3 Eine bestehende Datenbank übernehmen (Reverse Engineering)

**Ziel:** Aus einer vorhandenen Datenbank ein neutrales Schema erzeugen, um es
weiterzuverwenden oder auf eine andere Datenbank zu übertragen.

**Voraussetzungen:** Zugang zur Quelldatenbank (URL, Benutzer, Passwort) mit
Leserechten auf die Tabellen und den Systemkatalog der Datenbank.

**Vorgehen:**

1. Lesen Sie die Datenbank aus und schreiben Sie das Schema in eine Datei:

   ```bash
   d-migrate schema reverse --source postgresql://user@localhost/mydb \
       --output reversed-schema.yaml --include-all
   ```

   Statt `--include-all` lassen sich Objekttypen gezielt wählen, und der
   Schemaname/-version überschreiben:

   ```bash
   d-migrate schema reverse --source postgresql://user@localhost/mydb \
       --output reversed-schema.yaml \
       --include-views --include-triggers --include-functions --include-procedures \
       --name Webshop --version 2.0.0
   ```

**Ergebnis:** `reversed-schema.yaml` enthält das neutrale Schema. Reverse-Schemas
tragen einen technischen Namen (Präfix `__dmigrate_reverse__:`) und die Version
`0.0.0-reverse` — so bleiben sie auch ohne Sidecar als reverse-generiert
erkennbar. Auszug aus einem PostgreSQL-Reverse mit Tabelle, Function, View und
Trigger (`--include-all`):

```yaml
schema_format: 1.0
name: __dmigrate_reverse__:postgresql:database=demo;schema=public
version: 0.0.0-reverse
tables:
  customers:
    columns:
      email:
        type: text
        max_length: 254
        required: true
        unique: true
      id:
        type: identifier
        auto_increment: true
        required: true
      name:
        type: text
        max_length: 100
    primary_key:
    - id
functions:
  touch_name():
    returns:
      type: trigger
    language: PLPGSQL
    deterministic: false
    body: ' BEGIN NEW.name = trim(NEW.name); RETURN NEW; END; '
    source_dialect: postgresql
    security: invoker
views:
  active_customers:
    query: |2-
       SELECT id, email FROM customers WHERE name IS NOT NULL;
    # columns / dependencies gekürzt
    source_dialect: postgresql
triggers:
  customers::trg_touch_name:           # kanonischer Key: table::name
    table: customers
    event: update
    timing: before
    body: EXECUTE FUNCTION touch_name()
    dependencies:
      functions:
      - touch_name
    source_dialect: postgresql
```

**Hinweise:**

- **Wichtig:** Ohne `--include-all` werden **nur Tabellen** erfasst. Wollen Sie
  Views, Trigger oder Routinen einzeln, verwenden Sie `--include-views`,
  `--include-procedures`, `--include-functions` bzw. `--include-triggers`.
- Mit `--name` und `--version` können Sie Name und Version im erzeugten Schema
  überschreiben.
- Sequenz-Hilfsobjekte, die d-migrate selbst angelegt hat, werden automatisch
  erkannt und zurückübersetzt (siehe [3.12](#312-sequenzenautowerte-korrekt-mitnehmen)).
- **SQLite-64-bit-Autowerte:** SQLites `AUTOINCREMENT`-Primärschlüssel ist 64-bit,
  wird aber standardmäßig als 32-bit-`identifier` zurückübersetzt (bei einem Transfer
  nach PostgreSQL/MySQL sonst `SERIAL`/`INT`). Brauchen Sie den vollen 64-bit-Bereich,
  ergänzen Sie `--sqlite-autoincrement-width 64` (auch bei `data transfer`) — dann
  entsteht `biginteger` mit Identity (Ziel `BIGSERIAL`/`BIGINT`). Alternativ dauerhaft
  über die Konfiguration (`reverse.sqlite.autoincrement_width: 64` in `.d-migrate.yaml`).

### 3.4 Zwei Schemastände vergleichen

**Ziel:** Herausfinden, worin sich zwei Schemata unterscheiden — zwei Dateien,
oder eine Datei gegen eine laufende Datenbank.

**Voraussetzungen:** Zwei Vergleichsoperanden (Dateien und/oder Datenbanken).

**Vorgehen:**

1. Vergleichen Sie die beiden Stände:

   ```bash
   d-migrate schema compare --source mein-schema.yaml --target mein-schema-v2.yaml
   ```

   Statt eines Dateipfads können Sie auch eine Datenbank angeben, z. B.
   `--target db:postgresql://user@localhost/mydb`.

**Ergebnis:** Eine Auflistung der Unterschiede. Der Rückgabewert (Exit-Code) ist
`0`, wenn es keine Unterschiede gibt, und `1`, wenn welche gefunden wurden —
nützlich in Skripten.

**Hinweise:**

- Für eine maschinenlesbare Differenz: `d-migrate --output-format json schema
  compare … --output diff.json`.
- Alle Exit-Codes stehen in [Anhang B](#anhang-b--exit-codes).

### 3.5 Eine Schemaänderung ausrollen und zurücknehmen

**Ziel:** Eine bestehende Datenbank an einen geänderten Schemastand angleichen —
und die Änderung bei Bedarf rückgängig machen. Anders als
[3.2](#32-sql-für-eine-zieldatenbank-erzeugen) (komplettes Neu-Erzeugen)
überträgt dieser Weg nur die **Unterschiede**.

**Voraussetzungen:** Ein Soll-Schema als Datei und Zugang zur Zieldatenbank. Für
das Ausführen brauchen Sie Schreibrechte auf der Datenbank.

**Vorgehen:**

1. Sehen Sie sich zuerst gefahrlos an, was passieren würde (Trockenlauf):

   ```bash
   d-migrate schema migrate --source desired.yaml --target db:staging \
       --output up.sql --report plan.yaml --dry-run
   ```

2. Prüfen Sie `plan.yaml` (Risiken) und `up.sql` (die geplanten Anweisungen).
   Beispiel — das Soll-Schema fügt der Tabelle `customers` die Spalte `name`
   hinzu:

   ```sql
   -- up.sql
   ALTER TABLE "customers" ADD COLUMN "name" VARCHAR(100) NOT NULL;
   ```

   ```yaml
   # plan.yaml (Auszug)
   status: ok
   exitCode: 0
   dialect: POSTGRESQL
   summary:
     operationsTotal: 1
     statementsTotal: 1
     destructiveCount: 0
     manualActionCount: 0
     planFullyRollbackable: true
     planRequiresExclusiveAccess: true
   blockers: []
   diagnostics: []
   ```

3. Führen Sie die Migration aus. Ein Bericht ist dabei **Pflicht** (Nachweis):

   ```bash
   d-migrate schema migrate --source desired.yaml --target db:staging \
       --execute --report plan.yaml --generate-rollback --rollback-output down.sql
   ```

4. Müssen Sie die Änderung zurücknehmen, führen Sie das erzeugte Rücknahme-Skript
   aus:

   ```bash
   d-migrate schema rollback --source down.sql --target db:staging --execute
   ```

   Ist das Down-Artefakt bewusst nur **teilweise** (z. B. weil einzelne
   Operationen nicht reversibel sind), verlangt der Lauf zusätzlich
   `--allow-partial-rollback`:

   ```bash
   d-migrate schema rollback --source down.sql --target db:staging \
       --execute --allow-partial-rollback
   ```

**Ergebnis:** Die Datenbank entspricht nach Schritt 3 dem Soll-Schema. Das
Rücknahme-Skript `down.sql` bringt sie bei Bedarf wieder in den vorherigen
Zustand.

**Hinweise:**

- **Schutz vor Datenverlust:** Operationen, die Daten löschen könnten (z. B.
  Spalte entfernen), werden ohne `--allow-destructive` **blockiert** (Exit 8).
  Prüfen Sie solche Fälle bewusst, bevor Sie das Flag setzen.
- Vor dem Rollback prüft d-migrate, ob die Datenbank noch im erwarteten Zustand
  ist; bei Abweichung bricht es ab (Exit 8), statt blind etwas zu zerstören.
- Bei `file:`-Ziel (Datei-gegen-Datei) müssen Sie `--dialect` angeben, und
  `--execute` ist nicht möglich.

**Weitere Optionen — jeweils mit Beispiel:**

```bash
# Nur Plan/Report ansehen (kein SQL), als YAML, plus signiertes Plan-Artefakt
d-migrate schema migrate --source desired.yaml --target db:staging \
    --plan-only --report-format yaml --report plan.yaml --plan-artefact plan.v1.json

# Datei-gegen-Datei (Ziel ist eine Schema-Datei) — --dialect Pflicht, kein --execute
d-migrate schema migrate --source desired.yaml --target file:current.yaml \
    --dialect postgresql --output up.sql --report plan.yaml

# Umbenennen statt Drop+Create (erhält Daten) — inline …
d-migrate schema migrate --source desired.yaml --target db:staging --report plan.yaml \
    --rename-table kunden:customers --rename-column customers.mail:customers.email
# … oder artefaktstabil per Overlay-Datei
d-migrate schema migrate --source desired.yaml --target db:staging --report plan.yaml \
    --migration-overlay rename-overlay.json

# Destruktive Operationen bewusst zulassen (z. B. Spalte entfernen)
d-migrate schema migrate --source desired.yaml --target db:staging \
    --execute --report plan.yaml --allow-destructive

# PostgreSQL-Extension-Prerequisites rendern lassen (z. B. PostGIS)
d-migrate schema migrate --source desired.yaml --target db:staging \
    --execute --report plan.yaml --allow-extension-install

# Sequenz-Laufwert erhalten: SQLite-Opt-in + Lock-Budget (Atomic-Preserve)
d-migrate schema migrate --source desired.yaml --target db:staging \
    --execute --report plan.yaml \
    --sqlite-named-sequences helper_table --lock-timeout-ms 15000

# Trigger-Replace mit Sichtbarkeitslücke hart blocken statt nur warnen
d-migrate schema migrate --source desired.yaml --target db:staging \
    --report plan.yaml --strict-gap-operations

# Routine-Capability übersteuern und Routine-Bodies im Report sichtbar machen (UNSAFE)
d-migrate schema migrate --source desired.yaml --target db:staging --report plan.yaml \
    --routine-capability "function:enabled=true" --debug-body
```

### 3.6 Daten sichern (Export)

**Ziel:** Tabelleninhalte aus einer Datenbank in eine Datei schreiben (JSON,
YAML, CSV oder Parquet).

**Voraussetzungen:** Lesezugang zur Datenbank.

**Vorgehen:**

1. Exportieren Sie die gewünschten Tabellen:

   ```bash
   d-migrate data export --source postgresql://user@localhost/mydb --format csv \
       --tables customers,orders --output ./export --split-files
   ```

**Ergebnis:** Im Ordner `./export` liegt pro Tabelle eine CSV-Datei. <!-- d-check:ignore (Nutzer-CWD-Pfad, kein Repo-Artefakt; ADR 0011) -->

**Hinweise:**

- Ohne `--output` geht die Ausgabe auf den Bildschirm; ohne `--split-files`
  landen mehrere Tabellen in einer Datei.
- Nur bestimmte Zeilen exportieren Sie mit `--filter`, z. B.
  `--filter "status = 'shipped' AND total > 100"`.
- Nur Änderungen seit einem Zeitpunkt: `--since-column updated_at --since
  "2026-04-01T00:00:00"`.
- Für sehr große Tabellen siehe [3.9](#39-sehr-große-datenmengen-übertragen-mit-wiederaufnahme).

**Weitere Optionen — jeweils mit Beispiel:**

```bash
# Nur bestimmte Zeilen (Filter-DSL)
d-migrate data export --source staging --format json --tables orders \
    --filter "status = 'shipped' AND total > 100" --output orders.json

# Nur Änderungen seit einem Zeitpunkt (inkrementell, LF-013)
d-migrate data export --source staging --format json --tables orders \
    --since-column updated_at --since "2026-04-01T00:00:00" --output delta.json

# CSV fein steuern: Trennzeichen, BOM (Excel), ohne Kopfzeile, NULL-Text, Encoding
d-migrate data export --source staging --format csv --tables orders --output orders.csv \
    --csv-delimiter ";" --csv-bom --csv-no-header --null-string "NULL" --encoding utf-8

# Untrusted-Daten, die jemand in Excel/LibreOffice öffnet, gegen Formel-Injection sichern
d-migrate data export --source staging --format csv --tables comments --output comments.csv \
    --csv-formula-guard

# Chunk-Größe für sehr große Tabellen
d-migrate data export --source staging --format json --tables orders \
    --output orders.json --chunk-size 50000

# Parquet-Bundle mit SHA-256 je Tabelle im Manifest
d-migrate data export --source staging --format parquet --tables customers,orders \
    --output ./export-parquet --manifest-sha256
```

> **Formel-Injection (CSV + Tabellenkalkulation):** Ein Textwert aus der Quelle,
> der mit `=`, `+`, `-`, `@`, Tab oder Wagenrücklauf beginnt, wird von Excel und
> LibreOffice beim Öffnen als **Formel** ausgeführt. Standardmäßig exportiert
> d-migrate den Wert **unverändert** (treuer Dump) und meldet betroffene Spalten
> per Warnung `W203`. Wenn Sie **untrusted** Daten exportieren, die jemand in einer
> Tabellenkalkulation öffnet, setzen Sie `--csv-formula-guard`: solche Zellen werden
> dann mit einem `'` vorangestellt (die Formel wird nicht mehr ausgeführt). Das
> **verändert** den exportierten Wert — für einen byte-treuen Roundtrip lassen Sie
> den Guard aus.

### 3.7 Daten in eine Datenbank laden (Import)

**Ziel:** Daten aus einer Datei in eine Datenbank schreiben.

**Voraussetzungen:** Die Zieltabellen existieren bereits (siehe
[3.2](#32-sql-für-eine-zieldatenbank-erzeugen)). Schreibzugang zur Datenbank.

**Vorgehen:**

1. Importieren Sie die Datei:

   ```bash
   d-migrate data import --source customers.json --target postgresql://user@localhost/mydb \
       --schema mein-schema.yaml
   ```

2. Sollen vorhandene Datensätze aktualisiert statt abgelehnt werden, ergänzen Sie
   `--on-conflict update`.

**Ergebnis:** Die Daten stehen in der Zieldatenbank. Bei einem Fehler wird die
betroffene Verarbeitung abgebrochen, sodass keine halben Stände entstehen.

**Hinweise:**

- Das Format wird aus der Dateiendung erkannt; mit `--format` erzwingen Sie es.
- Eine Tabelle vorher leeren: `--truncate`.
- Kein Teil-Import bei einem Fehler (alles-oder-nichts): `--atomic` (zusammen
  mit `--truncate`).
- Trigger stören den Import? Auf PostgreSQL hilft `--trigger-mode disable`, auf
  MySQL/SQLite `--disable-fk-checks`.
- **Tipp (Trigger/Funktionen):** Erzeugen Sie das Schema mit
  `--split pre-post` ([3.2](#32-sql-für-eine-zieldatenbank-erzeugen)), spielen
  Sie zuerst `schema.pre-data.sql` ein, importieren dann die Daten und aktivieren
  zuletzt mit `schema.post-data.sql` die Trigger und Funktionen.

**Weitere Optionen — jeweils mit Beispiel:**

```bash
# Aus stdin in eine bestimmte Tabelle (Format explizit)
cat orders.json | d-migrate data import --source - --target staging \
    --format json --table orders

# Verzeichnis-Import: nur bestimmte Tabellen, feste Reihenfolge
d-migrate data import --source ./export --target staging --schema mein-schema.yaml \
    --tables customers,orders --table-order customers,orders

# UPSERT + tolerant: Chunk-Fehler protokollieren statt abbrechen
d-migrate data import --source orders.json --target staging --schema mein-schema.yaml \
    --on-conflict update --on-error log

# Zieltabelle leeren, Trigger feuern lassen, größere Chunks
d-migrate data import --source orders.json --target staging --schema mein-schema.yaml \
    --truncate --trigger-mode fire --chunk-size 50000

# MySQL/SQLite: FK-Prüfung aussetzen; Identity/Sequenzen NICHT neu setzen
# (Standard ist --reseed-sequences = an)
d-migrate data import --source orders.csv --target staging --format csv \
    --disable-fk-checks --no-reseed-sequences

# CSV ohne Kopfzeile, eigene NULL-Darstellung, festes Encoding
d-migrate data import --source orders.csv --target staging --format csv --table orders \
    --csv-no-header --csv-null-string "NULL" --encoding iso-8859-1

# Checkpoints für diesen Lauf abschalten
d-migrate data import --source ./export --target staging --schema mein-schema.yaml \
    --no-checkpoint
```

### 3.8 Daten direkt von Datenbank zu Datenbank übertragen

**Ziel:** Daten ohne Umweg über Dateien direkt von einer Datenbank in eine
andere kopieren.

**Voraussetzungen:** Lesezugang zur Quelle, Schreibzugang zum Ziel, und die
**Zieltabellen müssen bereits existieren**.

**Vorgehen:**

1. Übertragen Sie die Daten:

   ```bash
   d-migrate data transfer --source postgresql://localhost/source \
       --target mysql://localhost/target --tables customers,orders
   ```

**Ergebnis:** Die Daten werden direkt von der Quelle ins Ziel gestreamt. Vor dem
ersten Schreiben prüft d-migrate, ob die Zieltabellen passen, und bestimmt die
richtige Reihenfolge anhand der Fremdschlüssel im Ziel.

**Hinweise:**

- `data transfer` braucht **kein** `--format` und **kein** `--schema`.
- Existieren die Zieltabellen noch nicht, erzeugen Sie sie zuerst mit
  [3.2](#32-sql-für-eine-zieldatenbank-erzeugen).
- Optionen wie `--filter`, `--on-conflict` und `--truncate` funktionieren wie
  beim Export/Import.
- **Soll bei einem Fehler kein halber Stand zurückbleiben?** Fügen Sie `--atomic`
  hinzu (zusammen mit `--truncate`): schlägt eine Tabelle fehl, gehen **alle**
  Zieltabellen wieder auf leer.
- **Wollen Sie die Übertragung beweisen?** Hängen Sie `--verify` an — d-migrate
  gleicht danach Quelle und Ziel ab und meldet eine Abweichung mit Exit 3.
- **Die Quelle darf nicht verändert werden?** Das ist der Standard: d-migrate
  öffnet die Quelle schreibgeschützt (bei SQLite ohne `-wal`/`-shm`-Nebendateien);
  das Ziel bleibt schreibend. Brauchen Sie ausnahmsweise ein schreibendes Öffnen
  der Quelle, setzen Sie `--no-read-only`.

### 3.9 Sehr große Datenmengen übertragen (mit Wiederaufnahme)

**Ziel:** Einen großen Export oder Import nach einem Abbruch fortsetzen, ohne von
vorn zu beginnen.

**Voraussetzungen:** Ein Export/Import in eine Datei oder einen Ordner (nicht auf
den Bildschirm).

**Vorgehen:**

1. Starten Sie den Lauf mit einem Checkpoint-Verzeichnis:

   ```bash
   d-migrate data export --source staging --format json --tables orders \
       --output ./export --checkpoint-dir ./.d-migrate/checkpoints
   ```

2. Wurde der Lauf unterbrochen, setzen Sie ihn fort:

   ```bash
   d-migrate data export --source staging --format json --tables orders \
       --output ./export --resume <checkpoint-id> \
       --checkpoint-dir ./.d-migrate/checkpoints
   ```

**Ergebnis:** Bereits fertige Tabellen werden übersprungen, teilweise übertragene
Tabellen setzen an der letzten gesicherten Stelle fort.

**Hinweise:**

- Wiederaufnahme funktioniert nicht mit Bildschirmausgabe (`stdout`) oder
  `stdin`.
- Ändern Sie zwischen den Läufen wichtige Optionen, die Tabellenliste oder Pfade,
  bricht d-migrate mit Exit 3 ab, um Inkonsistenzen zu vermeiden.
- **Schneller statt wiederaufnehmbar:** Braucht ein breites Schema oder eine große
  partitionierte Tabelle vor allem Tempo (und keine Wiederaufnahme), übertragen/
  exportieren/importieren Sie mit `--parallel N` — unabhängige Tabellen und die
  Kind-Partitionen einer Tabelle laufen dann nebenläufig (FK-Reihenfolge bleibt
  gewahrt). Halten Sie `N` ≤ der Verbindungspool-Größe (Standard 10). `--parallel`
  schließt `--resume` (und `--atomic`) aus; für SQLite bleibt der Lauf sequenziell.
- **Durchsatz feinjustieren:** `--chunk-size` steuert die Zeilen pro Chunk/
  Transaktion, `--fetch-size` den JDBC-Cursor-Prefetch beim Lesen der Quelle
  (nur `data export`/`data transfer`; Standard dialektspezifisch 1000, SQLite nur
  Hinweis). Größere Werte erhöhen den Durchsatz, aber auch den Speicherbedarf.

### 3.10 Eine Datenbank auf Datenqualität prüfen (Profiling)

**Ziel:** Einen Bericht über Spaltenstatistiken, Datenqualität und Zieltyp-
Eignung einer Datenbank erstellen.

**Voraussetzungen:** Lesezugang zur Datenbank.

**Vorgehen:**

1. Erzeugen Sie den Bericht:

   ```bash
   d-migrate data profile --source meine-db --tables kunden --format yaml \
       --top-n 20 --output kunden-profil.yaml
   ```

**Ergebnis:** `kunden-profil.yaml` enthält pro Tabelle die Zeilenzahl und pro
Spalte Kennzahlen, Top-Werte, Zieltyp-Kompatibilität und Qualitätswarnungen.
Auszug (gekürzt um `targetCompatibility` und weitere Spalten):

```yaml
tables:
  - name: kunden
    rowCount: 10
    columns:
      - name: name
        dbType: text
        logicalType: STRING
        nullable: true
        nonNullCount: 8
        nullCount: 2
        distinctCount: 8
        emptyStringCount: 1
        blankStringCount: 1
        minLength: 0
        maxLength: 4
        topValues:
          - value: ""
            count: 1
            ratio: 0.1
          - value: "   "
            count: 1
            ratio: 0.1
        warnings:
          - code: CONTAINS_EMPTY_STRINGS
            severity: WARN
            message: "Column 'name' contains 1 empty strings"
          - code: CONTAINS_BLANK_STRINGS
            severity: WARN
            message: "Column 'name' contains 1 blank (whitespace-only) strings"

      - name: status
        dbType: text
        logicalType: STRING
        distinctCount: 2
        duplicateValueCount: 8
        topValues:
          - value: aktiv
            count: 7
            ratio: 0.7
          - value: inaktiv
            count: 3
            ratio: 0.3
        warnings:
          - code: LOW_CARDINALITY
            severity: INFO
            message: "Column 'status' has only 2 distinct values — candidate for lookup table or enum"

      - name: umsatz
        dbType: numeric
        logicalType: DECIMAL
        nonNullCount: 9
        nullCount: 1
        numericStats:
          min: -50.0
          max: 1500.0
          avg: 518.5711111111111
          sum: 4667.14
          stddev: 551.794746168143
          zeroCount: 2
          negativeCount: 1
        targetCompatibility:
          - targetType: INTEGER
            checkedValueCount: 9
            compatibleCount: 0
            incompatibleCount: 9
            determinationStatus: FULL_SCAN
        # … weitere targetCompatibility-Einträge (DECIMAL, BOOLEAN, DATE, …)
      # … weitere Spalten: id, email, erstellt
```

**Hinweise:**

- Pro Spalte liefert der Report u. a. `nullCount`/`distinctCount`,
  `topValues` (Häufigkeiten), typabhängige Statistiken (`numericStats`,
  `temporalStats`, `patternStats`, `spatialStats`) sowie `targetCompatibility`:
  Für jeden neutralen Zieltyp (INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, STRING)
  wird gezählt, wie viele Werte konvertierbar sind.
- **Qualitätswarnungen** erscheinen unter `warnings`, z. B.
  `HIGH_NULL_RATIO`, `CONTAINS_EMPTY_STRINGS`/`CONTAINS_BLANK_STRINGS`,
  `LOW_CARDINALITY`/`HIGH_CARDINALITY`, `DUPLICATE_VALUES`,
  `INVALID_TARGET_TYPE_VALUES` (Severity `INFO`/`WARN`/`ERROR`).
- Der Bericht ist **deterministisch**: gleiche Daten ergeben denselben Bericht
  (stabile Reihenfolge, kein laufzeitvariables `generatedAt`).
- `--top-n` steuert die Zahl der häufigsten Werte je Spalte (Standard 10,
  höchstens 1000); `--schema` gilt nur für PostgreSQL. Alle Optionen:
  [Anhang A.12](#a12-data-profile).
- Profiling **verändert die Quelle nicht**: d-migrate öffnet sie schreibgeschützt
  (bei SQLite ohne `-wal`/`-shm`-Nebendateien), so lassen sich auch
  nicht-schreibbare Datenbanken profilieren. Mit `--no-read-only` erzwingen Sie
  bei Bedarf ein schreibendes Öffnen.

### 3.11 Migrationsdateien für Flyway, Liquibase, Django oder Knex erzeugen

**Ziel:** Aus einem neutralen Schema fertige Migrationsdateien für ein
bestehendes Migrationswerkzeug erzeugen — als vollständigen Anfangsstand
(Baseline), nicht als Diff.

**Voraussetzungen:** Ein geprüftes Schema. Für `django` und `knex` zusätzlich
eine Versionsangabe über `--version`.

**Vorgehen:**

1. Wählen Sie das Werkzeug und erzeugen Sie die Dateien in ein
   Ausgabeverzeichnis:

   ```bash
   d-migrate export flyway    --source schema.yaml --target postgresql --output migrations/
   d-migrate export liquibase --source schema.yaml --target mysql      --output migrations/
   d-migrate export django    --source schema.yaml --target mysql --version 0001 --output app/migrations/
   d-migrate export knex      --source schema.yaml --target sqlite --version 20260615120000 --output migrations/
   ```

2. Übernehmen Sie die erzeugten Dateien in Ihr Projekt und führen Sie sie mit
   dem jeweiligen Werkzeug aus (`flyway migrate`, `liquibase update`,
   `manage.py migrate`, `knex migrate:latest`).

**Ergebnis:** Pro Werkzeug entsteht (mindestens) eine Datei. Der **Dateiname**
leitet sich aus **Version** und **Schemaname** ab: Der Schemaname wird zu einem
„Slug" normalisiert (Kleinschreibung, alles außer `a–z`/`0–9` wird zu `_`), z. B.
`Webshop` → `webshop`. Die folgenden Beispiele gehen vom Webshop-Schema aus
([3.1](#31-ein-schema-beschreiben-und-prüfen): Tabellen `customers` und `orders`,
Enum-Typ `order_status`); die Auszüge zeigen die Tabelle `customers`.

#### Flyway

```
migrations/
└── V1.0.0__webshop.sql          # Up-Migration (mit --generate-rollback zusätzlich U1.0.0__webshop.sql)
```

Inhalt von `V1.0.0__webshop.sql` (`--target postgresql`, Auszug):

```sql
-- Generated by d-migrate
-- Source: neutral schema v1.0.0 "Webshop"
-- Target: postgresql

CREATE TYPE "order_status" AS ENUM ('pending', 'processing', 'shipped', 'delivered', 'cancelled');

CREATE TABLE "customers" (
    "id" SERIAL,
    "email" VARCHAR(254) NOT NULL UNIQUE,
    "name" VARCHAR(100) NOT NULL,
    "created_at" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("id")
);

-- … CREATE TABLE "orders" (…) und CREATE INDEX "idx_customers_email" folgen
```

> Flyways Undo-Migrationen (`U`-Präfix) erfordern Flyway Teams/Enterprise; die
> Community Edition unterstützt sie nicht. d-migrate weist mit dem Hinweis
> `TE-FW-001` darauf hin.

#### Liquibase

```
migrations/
└── changelog-1.0.0-webshop.xml
```

Eine einzelne `<changeSet>` (es wird **kein** Master-Changelog erzeugt oder
verändert). Auszug (`--target mysql`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="...">

    <changeSet id="1.0.0-webshop-mysql" author="d-migrate">
        <sql>
            -- [W100] DATETIME with timezone on column 'created_at' mapped to DATETIME in MySQL which does not support time zones.
            CREATE TABLE `customers` (
                `id` INT NOT NULL AUTO_INCREMENT,
                `email` VARCHAR(254) NOT NULL UNIQUE,
                `name` VARCHAR(100) NOT NULL,
                `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`)
            )
            ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            -- … `orders` und der Index folgen im selben <sql>-Block
        </sql>
    </changeSet>

</databaseChangeLog>
```

Mit `--generate-rollback` enthält die `<changeSet>` zusätzlich einen
`<rollback><sql>…</sql></rollback>`-Block.

#### Django

```
app/migrations/
└── 0001.py
```

Inhalt (`--target mysql`):

```python
from django.db import migrations


class Migration(migrations.Migration):

    dependencies = []

    operations = [
        migrations.RunSQL(
            sql="""
CREATE TABLE `customers` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `email` VARCHAR(254) NOT NULL UNIQUE,
    `name` VARCHAR(100) NOT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
)
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- … `orders` und der Index folgen
""",
        ),
    ]
```

Mit `--generate-rollback` erhält `RunSQL` zusätzlich ein `reverse_sql="""…"""`.

#### Knex

```
migrations/
└── 20260615120000.js
```

Inhalt (`--target sqlite`):

```javascript
/**
 * Migration: 20260615120000 webshop
 * Target: sqlite
 * Generated by d-migrate
 */

exports.up = async function(knex) {
    await knex.raw(`CREATE TABLE "customers" (
    "id" INTEGER PRIMARY KEY AUTOINCREMENT,
    "email" TEXT NOT NULL UNIQUE,
    "name" TEXT NOT NULL,
    "created_at" TEXT DEFAULT (datetime('now'))
);`);
    // … weitere knex.raw(...)-Aufrufe für "orders" und den Index
};
```

Mit `--generate-rollback` zusätzlich eine `exports.down`-Funktion mit den
Rücknahme-Anweisungen.

**Hinweise:**

- Bei `django` und `knex` ist `--version` Pflicht; bei `flyway` und `liquibase`
  optional (sonst wird `schema.version` verwendet, sofern sie zum Werkzeug passt).
- Der Slug ist `migration`, falls der Schemaname nach der Normalisierung leer wäre.
- d-migrate erzeugt **nur die Migrationsdatei(en)** — keine Projekt-/
  Konfigurationsdateien, keinen Abhängigkeitsbaum und kein Master-Changelog.
- Das eingebettete SQL entspricht dem `--target`-Dialekt (PostgreSQL mit
  `"…"`/`SERIAL`, MySQL mit `` `…` ``, SQLite mit `"…"`).
- Dies erzeugt einen vollständigen Anfangsstand, keinen Diff. Für
  diff-basiertes Ausrollen siehe [3.5](#35-eine-schemaänderung-ausrollen-und-zurücknehmen).

### 3.12 Sequenzen/Autowerte korrekt mitnehmen

**Ziel:** Fortlaufende Nummern (Sequenzen) zwischen Datenbanken korrekt
übertragen — auch nach MySQL und SQLite, die keine nativen Sequenzen kennen.

**Voraussetzungen:** Ein Schema mit `sequences` bzw. `sequence_nextval`-Defaults.

**Vorgehen:**

1. Beschreiben Sie die Sequenz im Schema:

   ```yaml
   sequences:
     invoice_seq: { start: 10000, increment: 1 }
   tables:
     orders:
       columns:
         invoice_number: { type: biginteger, default: { sequence_nextval: invoice_seq } }
       primary_key: [id]
   ```

2. Für MySQL oder SQLite aktivieren Sie die Emulation beim Erzeugen:

   ```bash
   d-migrate schema generate --source mein-schema.yaml --target mysql \
       --mysql-named-sequences helper_table
   ```

   (Für SQLite: `--sqlite-named-sequences helper_table`.)

**Ergebnis:** PostgreSQL erhält native Sequenzen; MySQL/SQLite erhalten
Hilfsobjekte, die dasselbe Verhalten nachbilden.

**Hinweise:**

- **Ohne** die `helper_table`-Option werden Sequenzen auf MySQL/SQLite mit dem
  Hinweis `E056` übersprungen.
- Ändern Sie Sequenz-Parameter immer nur im neutralen Schema und erzeugen Sie
  neu — Eingriffe direkt in die Hilfsobjekte führen zu Warnungen (W116/W120/W124)
  und können den nächsten Reverse-Lauf stören.
- Soll der aktuelle Zählerstand beim Migrieren erhalten bleiben
  (`preserve_current_value`), läuft das beim Ausrollen
  ([3.5](#35-eine-schemaänderung-ausrollen-und-zurücknehmen)) automatisch unter
  einer Sperre; auf SQLite müssen Sie zusätzlich
  `--sqlite-named-sequences helper_table` angeben.

**Kurzmatrix:**

| Thema | PostgreSQL | MySQL | SQLite |
| ----- | ---------- | ----- | ------ |
| `identifier` / PK-Autowert | native Identity/Serial-Spalte | `AUTO_INCREMENT` | `INTEGER PRIMARY KEY AUTOINCREMENT` |
| benannte `sequences` | native `CREATE SEQUENCE` | `dmg_sequences` + `dmg_nextval`/`dmg_setval` + Trigger, nur mit `--mysql-named-sequences helper_table` | `dmg_sequences` + Trigger-Paar, nur mit `--sqlite-named-sequences helper_table` |
| `sequence_nextval`-Default | `DEFAULT nextval(...)` | `BEFORE INSERT`-Trigger ruft `dmg_nextval(...)` auf | Trigger reserviert und schreibt den nächsten Wert |
| `preserve_current_value` | `setval(...)` beim Ausrollen | `UPDATE dmg_sequences SET next_value = ...` unter Lock | `UPDATE dmg_sequences SET next_value = ...` unter Lock |
| `cache` | native Cache-Semantik | Metadatum, keine Runtime-Preallocation | Metadatum, keine Runtime-Preallocation |

Die vollständige Attributmatrix steht in der
[Neutralmodell-Spezifikation](../../spec/neutral-model-spec.md#92-cross-dialect-capability-matrix).

### 3.13 Komplettbeispiel: eine Datenbank von PostgreSQL nach MySQL migrieren

Dieses durchgängige Beispiel verbindet die Einzelaufgaben zu einem kompletten
Ablauf: Eine bestehende PostgreSQL-Datenbank „Webshop" (Tabellen, eine Sequenz
und Daten) wird vollständig nach MySQL übertragen.

**Ziel:** Struktur **und** Daten einer PostgreSQL-Datenbank verlustfrei in eine
neue MySQL-Datenbank bringen.

**Voraussetzungen:** Lesezugang zur Quell-PostgreSQL-Datenbank, eine **leere**
Ziel-MySQL-Datenbank mit Schreib- und Struktur-(DDL-)Rechten.

**Vorgehen:**

1. **Verbindungen benennen** (`.d-migrate.yaml`), damit Sie URLs nicht
   wiederholen (siehe [4.1](#41-verbindungen-benennen)):

   ```yaml
   database:
     connections:
       quelle_pg:  "postgresql://app:${PG_PW}@db-alt:5432/webshop"
       ziel_mysql: "mysql://app:${MY_PW}@db-neu:3306/webshop"
   ```

2. **Quell-Schema erfassen** (siehe [3.3](#33-eine-bestehende-datenbank-übernehmen-reverse-engineering)):

   ```bash
   d-migrate schema reverse --source quelle_pg --output webshop.yaml --include-all
   ```

3. **MySQL-DDL erzeugen** — mit Sequenz-Emulation, weil MySQL keine nativen
   Sequenzen kennt (siehe [3.12](#312-sequenzenautowerte-korrekt-mitnehmen)):

   ```bash
   d-migrate schema generate --source webshop.yaml --target mysql \
       --mysql-named-sequences helper_table --output webshop.mysql.sql
   ```

4. **Ziel-Tabellen anlegen**, indem Sie das erzeugte SQL mit Ihrem
   Datenbank-Client einspielen:

   ```bash
   mysql -h db-neu -u app -p webshop < webshop.mysql.sql
   ```

5. **Daten übertragen** — direkt von Quelle zu Ziel, ohne Zwischendateien
   (siehe [3.8](#38-daten-direkt-von-datenbank-zu-datenbank-übertragen)):

   ```bash
   d-migrate data transfer --source quelle_pg --target ziel_mysql
   ```

6. **Ergebnis prüfen** — vergleichen Sie das Soll-Schema gegen die neue
   Datenbank (siehe [3.4](#34-zwei-schemastände-vergleichen)):

   ```bash
   d-migrate schema compare --source file:webshop.yaml --target db:ziel_mysql
   ```

**Ergebnis:** Die MySQL-Datenbank „Webshop" enthält dieselbe Struktur
(einschließlich der emulierten Sequenzen) und dieselben Daten wie die
PostgreSQL-Quelle. `schema compare` meldet keine strukturellen Unterschiede
(Exit 0).

**Hinweise:**

- **Trigger/Funktionen im Schema?** Erzeugen Sie die DDL in Schritt 3 mit
  `--split pre-post` und spielen Sie zuerst `webshop.mysql.pre-data.sql` ein,
  übertragen dann die Daten und aktivieren zuletzt mit
  `webshop.mysql.post-data.sql` die Trigger (siehe
  [3.7, Hinweise](#37-daten-in-eine-datenbank-laden-import)).
- **Sehr große Datenmengen?** Übertragen Sie mit Wiederaufnahme über einen
  Export/Import-Umweg und Checkpoints (siehe
  [3.9](#39-sehr-große-datenmengen-übertragen-mit-wiederaufnahme)).
- `schema compare` zeigt operandseitige Sequenz-Hinweise (z. B. `W116`) als
  Diagnose an; sie beeinflussen den Exit-Code nicht.

### 3.14 Komplettbeispiel: eine Schemaänderung ausrollen und zurücknehmen

Dieses Beispiel zeigt den sicheren Weg, eine Änderung an einer **laufenden**
Datenbank vorzunehmen und sie bei Problemen wieder rückgängig zu machen.

**Ziel:** Ein geändertes Schema kontrolliert auf eine bestehende Datenbank
ausrollen und ein geprüftes Rücknahme-Skript in der Hand haben.

**Voraussetzungen:** Das geänderte Soll-Schema als Datei (`desired.yaml`) und
Zugang zur Zieldatenbank mit Struktur-(DDL-)Rechten. Testen Sie idealerweise
zuerst gegen eine Kopie (Staging).

**Vorgehen:**

1. **Trockenlauf** — ansehen, was passieren würde, ohne etwas zu ändern (siehe
   [3.5](#35-eine-schemaänderung-ausrollen-und-zurücknehmen)):

   ```bash
   d-migrate schema migrate --source desired.yaml --target db:staging \
       --output up.sql --report plan.yaml --dry-run
   ```

   Prüfen Sie `plan.yaml` (Risiken) und `up.sql` (die geplanten Anweisungen).

2. **Ausrollen mit Rücknahme-Skript** — der Report ist Pflicht (Nachweis), das
   Down-Skript ist Ihre Absicherung:

   ```bash
   d-migrate schema migrate --source desired.yaml --target db:staging \
       --execute --report plan.yaml \
       --generate-rollback --rollback-output down.sql
   ```

   Enthält der Plan datenlöschende Schritte, ergänzen Sie nach bewusster Prüfung
   `--allow-destructive`.

3. **Beobachten** — testen Sie die Anwendung gegen das neue Schema.

4. **Zurücknehmen**, falls etwas nicht stimmt. d-migrate prüft vorher, ob die
   Datenbank noch im erwarteten Zustand ist:

   ```bash
   # Erst gefahrlos prüfen
   d-migrate schema rollback --source down.sql --target db:staging --dry-run
   # Dann ausführen
   d-migrate schema rollback --source down.sql --target db:staging --execute
   ```

**Ergebnis:** Nach Schritt 2 entspricht die Datenbank dem neuen Schema. Schlägt
der Test fehl, bringt Schritt 4 sie exakt in den Zustand vor der Migration
zurück.

**Hinweise:**

- **Schutz vor Überraschungen:** Wurde die Datenbank seit dem Ausrollen
  anderweitig verändert, bricht der Rollback mit **Exit 8**
  (`TARGET_STATE_MISMATCH`) ab, statt einen inkonsistenten Zustand zu erzeugen.
  Passt der Dialekt des Artefakts nicht zur Datenbank, ebenfalls Exit 8.
- Nach einem **d-migrate-Update** kann ein älteres Down-Artefakt abgelehnt werden
  (Exit 8, `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`) — erzeugen Sie es dann mit
  der neuen Version neu. Siehe [Fehlerbehebung](#5-fehlerbehebung).
- Ist das Rücknahme-Skript bewusst unvollständig, benötigen Sie zusätzlich
  `--allow-partial-rollback`.
- Nicht jede Operation ist umkehrbar (z. B. das Ersetzen einer Routine, deren
  alter Inhalt unbekannt ist). In solchen Fällen meldet `--generate-rollback`
  das bereits beim Ausrollen, statt ein trügerisches Down-Skript zu erzeugen.

### 3.15 d-migrate als MCP-Server für KI-Agenten bereitstellen

**Ziel:** d-migrate so starten, dass ein KI-Agent (z. B. ein Desktop-Assistent)
die Schema- und Daten-Operationen über das **MCP-Protokoll** (Model Context
Protocol) nutzen kann — der Agent ruft d-migrate-Werkzeuge auf, statt dass Sie
die CLI selbst bedienen.

**Voraussetzungen:** Ein MCP-fähiger Client. Für HTTP-Betrieb über das eigene
Gerät hinaus zusätzlich ein OIDC-Identity-Provider (JWT). Die Datenbank-
Verbindungen, die der Agent nutzen darf, kommen aus einer Server-YAML
(`--connection-config`, secret-frei über benannte Verbindungen).

**Was der Agent dann nutzen kann.** Der Server bietet dieselben Operationen wie
die CLI als MCP-Tools an:

- **Schema:** `schema_validate`, `schema_compare`, `schema_generate`,
  `schema_reverse`, `schema_format`, `schema_list`, `schema_metadata`,
  `schema_staging_readonly`
- **Daten:** `data_profile`, `data_type`, `data_import`, `data_transfer`
- **Lang laufend als Job** (asynchron, Fortschritt per Job-Status):
  `schema_reverse_start`, `schema_compare_start`, `data_export_start`,
  `data_import_start`, `data_transfer_start`, `data_profile_start`
- **Discovery:** `capabilities_list` sowie `resources/list` und `resources/read`

Welche Tools ein Aufrufer tatsächlich sieht, hängt von seinen Scopes ab
(read-only vs. schreibend). Den vollständigen Katalog mit Ein-/Ausgabe-Verträgen
beschreibt die [API-Referenz §4 (MCP)](api-referenz.md#4-mcp-server-referenz).

**Vorgehen:** Wählen Sie einen der drei Betriebsmodi (A–C) und starten Sie den
Server entsprechend:

#### Variante A — lokaler Desktop-Client über stdio

stdio ist der primäre lokale Pfad: ein Server-Prozess pro Client, der vom Client
selbst gestartet wird.

1. Hinterlegen Sie ein Zugangstoken (Token-Registry) und testen Sie den Start:

   ```bash
   export DMIGRATE_MCP_STDIO_TOKEN="tok_local_dev"
   d-migrate mcp serve --transport stdio --stdio-token-file ./stdio-tokens.yaml
   ```

2. Tragen Sie **denselben** Startbefehl in die MCP-Konfiguration Ihres Clients
   ein (Format clientabhängig; typisch ein `mcpServers`-Eintrag):

   ```json
   {
     "mcpServers": {
       "d-migrate": {
         "command": "d-migrate",
         "args": ["mcp", "serve", "--transport", "stdio",
                  "--stdio-token-file", "/pfad/stdio-tokens.yaml",
                  "--connection-config", "/pfad/.d-migrate.yaml"],
         "env": { "DMIGRATE_MCP_STDIO_TOKEN": "tok_local_dev" }
       }
     }
   }
   ```

**Ergebnis:** Der Client startet d-migrate als Unterprozess; der Agent sieht die
oben genannten Tools. Der Server blockiert, bis stdin schließt (Client beendet).

#### Variante B — lokaler HTTP-Server zum Ausprobieren

1. Starten Sie den Server auf der Loopback-Adresse, Auth zum Testen aus:

   ```bash
   d-migrate mcp serve --transport http --bind 127.0.0.1 --port 8080 \
       --auth-mode disabled
   ```

**Ergebnis:** Auf stderr erscheint `MCP HTTP server listening on 127.0.0.1:8080`;
der Server läuft bis `Strg+C`. `--auth-mode disabled` ist **strikt** auf
`127.0.0.1`/`::1` beschränkt — ein Nicht-Loopback-`--bind` wird abgelehnt.

#### Variante C — Produktivbetrieb über das Netzwerk (HTTP + JWT)

1. Mit Authentifizierung und öffentlicher HTTPS-Basis-URL starten:

   ```bash
   d-migrate mcp serve --transport http --bind 0.0.0.0 --port 8080 \
       --auth-mode jwt-jwks \
       --issuer https://idp.example.com/ \
       --jwks-url https://idp.example.com/.well-known/jwks.json \
       --audience d-migrate-mcp \
       --public-base-url https://migrate.example.com \
       --connection-config /etc/d-migrate/server.yaml \
       --cursor-keyring-file /etc/d-migrate/cursor-keyring.yaml \
       --approval-grants-file /etc/d-migrate/approval-grants.yaml
   ```

**Ergebnis:** Jeder Request wird per `Authorization: Bearer …` gegen den Issuer
geprüft. Ein Nicht-Loopback-`--bind` **verlangt** eine aktive Auth (sonst
Abweisung). Härtung (Quotas, Rate-Limiting, Audit) im
[Administrationshandbuch](administrationshandbuch.md#6-mcp-server-betrieb).

#### Genehmigungspflichtige Jobs freigeben

Schreibende Operationen können policy-bedingt eine **Freigabe** verlangen: Ein
`*_start`-Tool antwortet dann mit `POLICY_REQUIRED` und nennt
`approvalRequestId`, `payloadFingerprint` und die benötigten Scopes. Ein
Operator stellt daraufhin einen Grant in den Freigabe-Store aus, den `mcp serve`
über `--approval-grants-file` liest:

```bash
d-migrate mcp approval-grant issue \
    --file /etc/d-migrate/approval-grants.yaml \
    --tenant <tenant> --caller <principal-id> --tool data_import_start \
    --approval-request-id <aus POLICY_REQUIRED> \
    --payload-fingerprint <aus POLICY_REQUIRED> \
    --idempotency-key <des wartenden Aufrufs> \
    --scope <geforderter scope>
# Ausgabe: approvalToken=appr_…  /  expiresAt=…
```

Der Client wiederholt den Aufruf mit dem ausgegebenen `approvalToken`. Volle
Flag-Liste: [Anhang A.14](#a14-mcp-approval-grant-issue).

Variante für einen **synchronen** `POLICY_REQUIRED`-Fall — Korrelation über
`--approval-key`, eigenes Token mit fester Ablaufzeit und Audit-Provenienz:

```bash
d-migrate mcp approval-grant issue --file /etc/d-migrate/approval-grants.yaml \
    --tenant <tenant> --caller <principal-id> --tool data_import_start \
    --approval-request-id <id> --payload-fingerprint <fp> \
    --approval-key <des wartenden Aufrufs> --scope <scope> \
    --token appr_eigenes_token --expires-at 2026-07-01T00:00:00Z \
    --issuer-fingerprint ops-team --grant-source manual-review
# statt --expires-at alternativ: --ttl-seconds 600
```

#### Weitere Server-Optionen — mit Beispiel

```bash
# Auth per Token-Introspection (RFC 7662) statt JWKS; Origin-Allowlist;
# persistentes State-Verzeichnis + Aufräum-Retention + Operations-Timeout
d-migrate mcp serve --transport http --bind 0.0.0.0 --port 8080 \
    --auth-mode jwt-introspection \
    --issuer https://idp.example.com/ \
    --introspection-url https://idp.example.com/oauth2/introspect \
    --introspection-client-id d-migrate \
    --introspection-client-secret "$IDP_SECRET" \
    --audience d-migrate-mcp --public-base-url https://migrate.example.com \
    --allow-origin https://app.example.com \
    --mcp-state-dir /var/lib/d-migrate/mcp \
    --mcp-state-orphan-retention 48h \
    --operation-timeout-seconds 120
```

**Hinweise:**

- **Mehrinstanz-HTTP** braucht ein stabiles Cursor-Keyring (für HMAC-versiegelte
  Cursor):

  ```bash
  d-migrate mcp cursor-key generate --kid key-2026 > cursor-keyring.yaml
  d-migrate mcp cursor-key validate --cursor-keyring-file cursor-keyring.yaml
  ```

- **Zustand und Dateien:** `--mcp-state-dir` bestimmt, wo hochgeladene Inhalte
  und Artefakte abgelegt werden; ohne Angabe ein temporäres, beim Stoppen
  gelöschtes Verzeichnis. `--mcp-state-orphan-retention` steuert das Aufräumen
  verwaister Dateien beim Start (Standard 24h).
- **Sicherheit:** `--auth-mode disabled` nur lokal; im Netzbetrieb JWT + HTTPS.
  Verbindungen secret-frei über `--connection-config` referenzieren.
- Alle Server-/Admin-Optionen: [Anhang A.13–A.16](#a13-mcp-serve). MCP-Protokoll,
  Tool-Katalog und Resource-/Auth-Verträge: [API-Referenz §4 (MCP)](api-referenz.md#4-mcp-server-referenz);
  Betrieb und Härtung: [Administrationshandbuch](administrationshandbuch.md#6-mcp-server-betrieb).

### 3.16 Geodaten (Spatial) modellieren und übertragen

**Ziel:** Eine Tabelle mit Geometrie-Spalte beschreiben und passendes
Spatial-DDL für das Zielsystem erzeugen.

**Voraussetzungen:** Ein Zielsystem mit Spatial-Unterstützung (PostGIS,
MySQL Spatial, SpatiaLite).

**Vorgehen:**

1. Beschreiben Sie die `geometry`-Spalten — `geometry_type` legt die Geometrie
   fest (hier `point`, `linestring` und `polygon`):

   ```yaml
   tables:
     places:
       columns:
         id:       { type: identifier, auto_increment: true }
         name:     { type: text, max_length: 100, required: true }
         location: { type: geometry, geometry_type: point, srid: 4326 }
         route:    { type: geometry, geometry_type: linestring, srid: 4326 }
         area:     { type: geometry, geometry_type: polygon, srid: 4326 }
       primary_key: [id]
   ```

2. Erzeugen Sie DDL mit dem passenden Spatial-Profil:

   ```bash
   # PostgreSQL (postgis ist hier Default)
   d-migrate schema generate --source places.yaml --target postgresql

   # SQLite (spatialite muss explizit gewählt werden — Default ist none)
   d-migrate schema generate --source places.yaml --target sqlite \
       --spatial-profile spatialite
   ```

**Ergebnis:** Die Geometrie wird ins zieltypische Format überführt.

PostgreSQL/PostGIS — die Geometrie-Spalten stehen **inline** in der Tabelle:

```sql
-- Generated by d-migrate
-- Source: neutral schema v1.0.0 "GeoDemo"
-- Target: postgresql

-- [I001] Table 'places' uses PostGIS geometry types. Ensure PostGIS extension is installed on the target database.
CREATE TABLE "places" (
    "id" SERIAL,
    "name" VARCHAR(100) NOT NULL,
    "location" geometry(Point, 4326),
    "route" geometry(LineString, 4326),
    "area" geometry(Polygon, 4326),
    PRIMARY KEY ("id")
);
```

SQLite/SpatiaLite — die Tabelle wird **ohne** Geometrie-Spalten angelegt; diese
kommen anschließend per `AddGeometryColumn()` dazu:

```sql
-- Generated by d-migrate
-- Source: neutral schema v1.0.0 "GeoDemo"
-- Target: sqlite

CREATE TABLE "places" (
    "id" INTEGER PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL
);

SELECT AddGeometryColumn('places', 'location', 4326, 'POINT', 'XY');

SELECT AddGeometryColumn('places', 'route', 4326, 'LINESTRING', 'XY');

SELECT AddGeometryColumn('places', 'area', 4326, 'POLYGON', 'XY');
```

**Hinweise:**

- Das Profil muss zum Ziel passen: `postgresql` → `postgis` (Default),
  `mysql` → `native` (Default), `sqlite` → `spatialite`; `none` lässt Geometrie
  aus. Unzulässige Kombination (z. B. `mysql` + `postgis`) → **Exit 2**.
- PostGIS benötigt die PostGIS-Erweiterung in der Zieldatenbank (Hinweis
  `[I001]`); die SpatiaLite-`AddGeometryColumn()`-Aufrufe setzen die geladene
  SpatiaLite-Erweiterung voraus.
- Erlaubte `geometry_type`-Werte und die Grenzen stehen in
  [Anhang F.4](#f4-spatial-typen); Profil-Details in
  [Anhang A.4](#a4-schema-generate).

### 3.17 Views und Materialized Views mitnehmen

**Ziel:** (Materialized) Views in die Zieldatenbank übernehmen.

**Voraussetzungen:** Ein Schema mit `views` (aus einer DB per
`schema reverse --include-views` erfassbar).

**Vorgehen:**

1. Beschreiben Sie die View:

   ```yaml
   views:
     active_orders:
       query: "SELECT id FROM orders WHERE status = 'pending'"
     monthly_revenue:
       materialized: true
       refresh: on_demand          # nur PostgreSQL
       query: "SELECT date_trunc('month', ordered_at) AS m, sum(total) FROM orders GROUP BY 1"
   ```

2. Erzeugen Sie das DDL — mit `--split pre-post` landen Views in der
   post-data-Phase:

   ```bash
   d-migrate schema generate --source schema.yaml --target postgresql --split pre-post
   ```

**Ergebnis:** Normale Views werden auf allen Dialekten erzeugt. Materialized
Views sind nativ nur auf PostgreSQL; auf MySQL/SQLite werden sie als normale
View erzeugt (Warnung **W103**).

**Hinweise:**

- `refresh: on_demand|on_commit` gilt nur für PostgreSQL.
- In **diff-basierten Migrationen** (`schema migrate`) werden Materialized Views
  blockiert, bis ein eigener Refresh-/Staleness-Vertrag existiert — sie werden
  dort nicht als normale View gerendert.
- Vollständige View-Felder (inkl. `columns`, `dependencies`): [Anhang F.11](#f11-views).

### 3.18 Trigger, Procedures und Functions mitnehmen

**Ziel:** Programmierbare Objekte (Trigger, Stored Procedures, Functions)
übernehmen.

**Voraussetzungen:** Ein Schema mit `triggers`/`procedures`/`functions` (aus
einer DB per `schema reverse --include-triggers`/`--include-procedures`/
`--include-functions` bzw. `--include-all` erfassbar).

**Vorgehen:**

1. Beschreiben Sie die Objekte — der Rumpf (`body`) steht im Quell-Dialekt,
   den `source_dialect` benennt:

   ```yaml
   functions:
     order_count:
       returns: { type: integer }
       language: sql
       body: "SELECT count(*) FROM orders;"
       source_dialect: postgresql
   procedures:
     touch_order:
       parameters: [ { name: p_id, type: integer, direction: in } ]
       language: sql
       body: "UPDATE orders SET updated_at = CURRENT_TIMESTAMP WHERE id = p_id;"
       source_dialect: postgresql
   triggers:
     trg_orders_touch:
       table: orders
       event: update
       timing: before
       for_each: row
       body: "SET NEW.updated_at = CURRENT_TIMESTAMP;"
       source_dialect: postgresql
   ```

2. Erzeugen Sie das DDL mit `--split pre-post`, damit Trigger/Routinen in die
   post-data-Phase kommen und einen Datenimport nicht stören:

   ```bash
   d-migrate schema generate --source schema.yaml --target postgresql --split pre-post
   ```

**Ergebnis:** `pre-data` (Tabellen) und `post-data` (Trigger/Routinen) sind
getrennt (Import-Reihenfolge: pre-data → Daten → post-data, siehe
[3.7, Hinweise](#37-daten-in-eine-datenbank-laden-import)). Der `source_dialect`
entscheidet, ob ein Objekt sauber rendert oder blockiert.

**PostgreSQL** (`source_dialect: postgresql` → sauber) — `schema.post-data.sql`:

```sql
CREATE OR REPLACE FUNCTION "order_count"() RETURNS INTEGER AS $$
SELECT count(*) FROM orders;
$$ LANGUAGE sql;

CREATE OR REPLACE PROCEDURE "touch_order"("p_id" INTEGER) AS $$
UPDATE orders SET updated_at = CURRENT_TIMESTAMP WHERE id = p_id;
$$ LANGUAGE sql;

-- Trigger = eigene Trigger-Funktion + CREATE TRIGGER
CREATE OR REPLACE FUNCTION "trg_fn_trg_orders_touch"() RETURNS TRIGGER AS $$
SET NEW.updated_at = CURRENT_TIMESTAMP;
$$ LANGUAGE plpgsql;

CREATE TRIGGER "trg_orders_touch"
    BEFORE UPDATE ON "orders"
    FOR EACH ROW
    EXECUTE FUNCTION "trg_fn_trg_orders_touch"();
```

**MySQL** (Objekte mit `source_dialect: mysql`, Body in MySQL-Syntax) — MySQL
kapselt jedes Objekt in `DELIMITER`-Blöcke:

```sql
DELIMITER //
CREATE FUNCTION `order_count`()
RETURNS INTEGER
BEGIN
RETURN (SELECT count(*) FROM orders);
END //
DELIMITER ;

DELIMITER //
CREATE TRIGGER `trg_orders_touch`
    BEFORE UPDATE ON `orders`
    FOR EACH ROW
BEGIN
SET NEW.updated_at = NOW();
END //
DELIMITER ;
```

**SQLite** (`source_dialect: sqlite`) — Trigger werden unterstützt:

```sql
CREATE TRIGGER "trg_orders_touch"
    AFTER UPDATE ON "orders"
    FOR EACH ROW
BEGIN
UPDATE orders SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;
```

Functions und Procedures kennt SQLite **nicht** — sie werden übersprungen und im
Report als **E054** vermerkt:

```sql
-- [E054] Function 'order_count' cannot be created via DDL in SQLite.
-- Hint: Register custom functions programmatically via the SQLite C API or your application's SQLite driver.
-- [E054] Procedure 'touch_order' cannot be created in SQLite.
-- Hint: Implement procedure logic at the application level.
```

**Cross-Dialect** (Body in falschem `source_dialect`, z. B. PostgreSQL-Body →
`--target mysql`): d-migrate übersetzt Routinen-Bodies **nicht** automatisch,
überspringt das Objekt und schreibt einen **E053**-Hinweis statt DDL:

```sql
-- [E053] Trigger 'trg_orders_touch' was written for 'postgresql' and must be manually rewritten for MySQL.
-- Hint: Rewrite the trigger body using MySQL-compatible syntax.
```

**Hinweise:**

- **Body nicht auto-übersetzt:** Nur bei **passendem** `source_dialect` rendert
  d-migrate sauber; sonst **E053** (manuell umschreiben). Einfache Transformationen
  greifen, komplexe Bodies nicht. Das KI-gestützte `transform procedure` ist
  geplant ([`LF-017`](../../spec/lastenheft-d-migrate.md#lf-017)).
- **SQLite:** keine Stored Functions/Procedures (**E054**) — Logik gehört in die
  Anwendung; Trigger sind unterstützt.
- Ein Routine-Replace ohne bekannten alten Rumpf kann beim Rollback blockieren
  (siehe [3.5](#35-eine-schemaänderung-ausrollen-und-zurücknehmen)).
- Reverse erfasst diese Objekte nur mit `--include-triggers`/`--include-procedures`/
  `--include-functions` (oder `--include-all`). Vollständige Felder:
  [Anhang F.12](#f12-trigger) / [Anhang F.13](#f13-procedures-und-functions).

### 3.19 Datenintegrität mit Constraints absichern

**Ziel:** CHECK-, UNIQUE-, EXCLUDE- und zusammengesetzte Fremdschlüssel-
Constraints definieren.

**Voraussetzungen:** Ein Schema.

**Vorgehen:**

1. Beschreiben Sie die Constraints auf Tabellen-Ebene:

   ```yaml
   tables:
     orders:
       columns: { id: { type: identifier, auto_increment: true } }
       primary_key: [id]
       constraints:
         - { name: chk_total, type: check, expression: "total >= 0" }
         - { name: uq_cust_date, type: unique, columns: [customer_id, ordered_at] }
   ```

**Ergebnis:** Die Constraints werden im DDL erzeugt; die Validierung prüft die
Spaltenbezüge (z. B. **E012**, wenn eine CHECK-Expression eine unbekannte Spalte
nennt).

**Hinweise:**

- **EXCLUDE**-Constraints sind PostgreSQL-spezifisch und auf MySQL/SQLite nicht
  abbildbar (Blocker).
- Einfache Fremdschlüssel formuliert man direkt an der Spalte über `references`
  (siehe [3.1](#31-ein-schema-beschreiben-und-prüfen) /
  [Anhang F.5](#f5-referenzen)); ein Constraint vom Typ `foreign_key` dient
  **zusammengesetzten** Fremdschlüsseln.
- `expression` ist Raw-SQL (Trusted Input) und wird nicht umgeschrieben. Volle
  Felder: [Anhang F.7](#f7-constraints).

### 3.20 Große Tabellen partitionieren

**Ziel:** Eine Tabelle nach `range`, `hash` oder `list` partitionieren.

**Voraussetzungen:** Ein Zielsystem mit Partitionierungsunterstützung.

**Vorgehen:**

1. Beschreiben Sie die Partitionierung an der Tabelle:

   ```yaml
   tables:
     orders:
       columns: { id: { type: identifier, auto_increment: true } }
       primary_key: [id]
       partitioning:
         type: range
         key: [ordered_at]
         partitions:
           - { name: orders_2025, from: ["'2025-01-01'"], to: ["'2026-01-01'"] }
   ```

**Ergebnis:** Partitionierungs-DDL für Ziele, die das Konzept unterstützen.

**Hinweise:**

- Zielsysteme ohne Partitionierungsunterstützung blockieren mit **E055**
  („Partitioning is not supported in the target dialect").
- Vollständige Felder: [Anhang F.8](#f8-partitionierung).

### 3.21 Eigene Datentypen definieren (Enum, Composite, Domain)

**Ziel:** Wiederverwendbare `custom_types` nutzen.

**Voraussetzungen:** Ein Schema.

**Vorgehen:**

1. Definieren Sie die Typen und verweisen Sie aus Spalten darauf
   (Enum über `ref_type`):

   ```yaml
   custom_types:
     order_status: { kind: enum, values: [pending, shipped] }
   tables:
     orders:
       columns:
         id:     { type: identifier, auto_increment: true }
         status: { type: enum, ref_type: order_status }
       primary_key: [id]
   ```

**Ergebnis:**

- **Enum** → PostgreSQL `CREATE TYPE … ENUM`, MySQL inline `ENUM(…)`,
  SQLite `TEXT` + `CHECK`.
- **Composite** → PostgreSQL nativ (`CREATE TYPE … AS (…)`); MySQL/SQLite
  benötigen eine **konfigurierte Fallback-Strategie** (`json`, `flatten`,
  `action_required`) — ohne Konfiguration erfolgt kein stiller Fallback,
  sondern `action_required`.
- **Domain** → PostgreSQL nativ; bei anderen Dialekten nennt der
  Transformations-Report die gewählte Abbildung.

**Hinweise:**

- Vollständige Custom-Type-Felder (inkl. `composite`/`domain`-Beispiele):
  [Anhang F.9](#f9-custom-types).

---

## 4. Konfiguration

### 4.1 Verbindungen benennen

Statt jeder Stelle die volle Datenbank-URL zu übergeben, können Sie Verbindungen
in einer Datei `.d-migrate.yaml` benennen:

```yaml
database:
  connections:
    local_pg: "postgresql://dev:dev@localhost:5432/myapp"
    staging:  "postgresql://app:${DB_STAGING_PASSWORD}@staging.example.com/myapp?ssl=require"
```

Danach genügt `--source staging`. Passwörter geben Sie sicher über
Umgebungsvariablen (`${…}`) an.

**Unterstützte URL-Formen** — allgemein
`<dialekt>://[benutzer[:passwort]@]host[:port]/datenbank[?parameter]`:

```yaml
database:
  connections:
    pg:     "postgresql://user:${PG_PW}@host:5432/db"   # Aliase: postgres, pg
    my:     "mysql://user:${MY_PW}@host:3306/db"         # Aliase: maria, mariadb
    lokal:  "sqlite:///pfad/zur/datei.db"                # oder sqlite::memory:
```

Die vollständige Liste der Parameter (SSL, Timeouts, Zeichensatz, Port-Defaults)
und der Dialekt-Aliase steht im
[Administrationshandbuch](administrationshandbuch.md#41-connection-url-format-und-aliase).

### 4.2 Welche Konfigurationsdatei gilt?

d-migrate verwendet genau **eine** Datei, in dieser Reihenfolge:

1. die Datei aus `--config`
2. der Pfad aus der Umgebungsvariable `D_MIGRATE_CONFIG`
3. die Datei `./.d-migrate.yaml` im aktuellen Verzeichnis <!-- d-check:ignore (Nutzer-CWD-Pfad, kein Repo-Artefakt; ADR 0011) -->

Welche Datei tatsächlich gegriffen hat und was darin steht, zeigt Ihnen:

```bash
d-migrate config show
```

Die Ausgabe ist der Inhalt der **effektiv gewählten** Datei als eingerückter
Abschnittsbaum; mit `--section database` schränken Sie auf einen Abschnitt ein.
Passwörter, Token und `credentialRef`-Werte erscheinen als `***`, Sie können die
Ausgabe also gefahrlos in ein Ticket kopieren. Beachten Sie zwei Grenzen:
`${VAR}`-Platzhalter werden **nicht** aufgelöst (sie erscheinen wörtlich), und
Werte, die Sie gar nicht in die Datei geschrieben haben, tauchen nicht auf —
angezeigt wird die Datei, nicht die Summe aus Datei und Standardwerten. Aktive
`D_MIGRATE_*`-Umgebungsvariablen listet der Befehl namentlich am Ende, ohne ihre
Werte einzurechnen.

Die vollständige Liste aller Konfigurationsfelder steht im
[Administrationshandbuch](administrationshandbuch.md#3-konfiguration).

### 4.3 Globale Optionen

Diese Optionen gelten für alle Befehle und stehen **vor** dem Befehl (z. B.
`d-migrate --output-format json schema validate …`). Die vollständige Tabelle
finden Sie in [Anhang A](#a1-globale-optionen). Die wichtigsten:

- `--lang de|en` — Sprache der Ausgabe.
- `--output-format plain|json|yaml` — Ausgabeformat (Standard: `plain`).
- `-v`/`--verbose`, `-q`/`--quiet` — mehr bzw. nur Fehlerausgabe.
- `-y`/`--yes` — Rückfragen automatisch bestätigen.

### 4.4 Sicherheit und Datenschutz

- **Zugangsdaten** gehören nicht im Klartext in Skripte oder die
  Versionsverwaltung. Sie haben mehrere Möglichkeiten:
  - Platzhalter `${VAR}` in der `.d-migrate.yaml`, den d-migrate aus der
    gleichnamigen Umgebungsvariable ersetzt (`$${VAR}` bleibt literal);
  - die globale Umgebungsvariable `D_MIGRATE_DB_PASSWORD` als Fallback für ein
    fehlendes Passwort;
  - das Passwort **verschlüsselt ablegen**: `d-migrate config credentials set
    --name <verbindung> --user <benutzer>` — es wird beim Verbinden herangezogen;
  - eine Verbindung per `credentialRef` auf eine externe Secret-Quelle zeigen
    lassen: `"file:/pfad"` (z. B. ein k8s-Secret-Mount), `"env:VAR"` oder
    `"keychain:<service>"` (Eintrag im Schlüsselbund Ihres Betriebssystems —
    bequem am Arbeitsplatz, aber ungeeignet für CI und Server, weil dort kein
    Schlüsselbund verfügbar ist).

  Details und die Prioritätsreihenfolge stehen im
  [Administrationshandbuch](administrationshandbuch.md#46-credential-handling).
- **Exportierte Dateien** (JSON/CSV/Parquet) enthalten echte, möglicherweise
  personenbezogene Daten. Behandeln Sie sie wie die Datenbank selbst:
  Zugriffsschutz, sichere Ablage, Löschung nach Gebrauch.
- **CSV-Export für Tabellenkalkulationen:** Öffnet jemand einen CSV-Export mit
  Daten aus einer nicht vertrauenswürdigen Quelle in Excel/LibreOffice, können
  formel-anfällige Textwerte (führendes `=`/`+`/`-`/`@`/Tab/CR) beim Öffnen
  ausgeführt werden. Setzen Sie in diesem Fall `--csv-formula-guard` (siehe
  [3.6](#36-daten-sichern-export)).
- **Geringste Rechte:** Verwenden Sie pro Aufgabe ein Datenbankkonto mit nur den
  nötigen Rechten — Leserechte zum Auslesen/Exportieren, Schreibrechte zum
  Importieren, Struktur-(DDL-)Rechte zum Migrieren.
- Authentifizierung und Netzwerkabsicherung des MCP-Servers behandelt das
  [Administrationshandbuch](administrationshandbuch.md#9-sicherheit).

---

## 5. Fehlerbehebung

Die Einträge folgen dem Muster **Symptom → Ursache → Lösung**. Den Rückgabewert
(Exit-Code) eines fehlgeschlagenen Laufs schlagen Sie in
[Anhang B](#anhang-b--exit-codes) nach.

### „Connection refused: host:port"

**Ursache:** Die Datenbank ist nicht erreichbar (läuft nicht, falscher Host/Port,
Firewall).

**Lösung:** Prüfen Sie, ob die Datenbank läuft, und kontrollieren Sie Host und
Port in der URL.

### „Authentication failed for user 'x'"

**Ursache:** Benutzername oder Passwort sind falsch.

**Lösung:** Prüfen Sie die Zugangsdaten. Enthält das Passwort Sonderzeichen,
müssen diese in der URL kodiert werden (z. B. `@` → `%40`) — oder hinterlegen Sie
das Passwort als `${VAR}` in einer benannten Verbindung der `.d-migrate.yaml`
(wird aus der gleichnamigen Umgebungsvariable ersetzt).

### „Failed to resolve credentialRef for connection '…' (fail-closed)"

**Ursache:** Eine Verbindung verweist per `credentialRef` (`file:`/`env:`/`keychain:`)
auf eine Secret-Quelle, die nicht aufgelöst werden konnte — die Datei fehlt, die
Umgebungsvariable ist nicht gesetzt, der Schlüsselbund-Eintrag existiert nicht oder
das Schema ist unbekannt. d-migrate verbindet dann **bewusst nicht** ohne Secret
(Exit 7), statt still weiterzumachen.

**Lösung:** Prüfen Sie, dass die referenzierte Datei existiert und lesbar ist bzw.
die Umgebungsvariable gesetzt ist, und dass der Datei-Inhalt/Variablenwert die
vollständige Connect-URL enthält. Die Fehlermeldung nennt Pfad bzw. Grund
(`FILE_NOT_FOUND`, `ENV_NOT_SET`, `PROVIDER_MISSING`), nie das Secret selbst.

Tritt der Fehler **nur in CI, im Container oder auf einem Server auf**, während er
am Arbeitsplatz nicht auftrat, prüfen Sie, ob die Verbindung `keychain:` verwendet:
dort gibt es keinen Schlüsselbund, und die Auflösung scheitert bewusst, statt ohne
Secret weiterzumachen. Nutzen Sie in solchen Umgebungen `env:` oder `file:`.

### „Unknown database dialect 'xyz'"

**Ursache:** In der URL steht ein nicht unterstützter Datenbanktyp.

**Lösung:** Verwenden Sie `postgresql`, `mysql` oder `sqlite` (bzw. deren
Aliase wie `pg` oder `mariadb`).

### „Database 'x' does not exist"

**Ursache:** Die angegebene Datenbank gibt es (noch) nicht.

**Lösung:** Legen Sie die Datenbank an oder korrigieren Sie den Namen in der URL.

### Reverse Engineering liefert keine Views/Trigger

**Ursache:** Standardmäßig werden nur Tabellen ausgelesen.

**Lösung:** Ergänzen Sie `--include-all` (oder gezielt `--include-views`,
`--include-triggers` usw.). Siehe [3.3](#33-eine-bestehende-datenbank-übernehmen-reverse-engineering).

### Hinweis „E056" — Sequenzen werden übersprungen

**Ursache:** MySQL/SQLite kennen keine nativen Sequenzen; ohne Emulation werden
sie nicht erzeugt.

**Lösung:** Erzeugen Sie mit `--mysql-named-sequences helper_table` bzw.
`--sqlite-named-sequences helper_table`. Siehe
[3.12](#312-sequenzenautowerte-korrekt-mitnehmen).

### Migration wird blockiert (Exit 8)

**Ursache:** Die Migration enthält eine riskante (z. B. datenlöschende)
Operation, oder eine Anweisung lässt sich für das Ziel nicht erzeugen.

**Lösung:** Sehen Sie sich den Bericht (`--report`) an. Ist die Operation
beabsichtigt, erlauben Sie sie gezielt mit `--allow-destructive`. Siehe
[3.5](#35-eine-schemaänderung-ausrollen-und-zurücknehmen).

### Rollback oder Overlay bricht nach einem d-migrate-Update ab (Exit 8)

**Ursache:** d-migrate erkennt den Zustand einer Datenbank über einen internen
**Fingerabdruck**. Rollback-Artefakte und Overlay-Dateien pinnen den
Fingerabdruck-Stand, mit dem sie erzeugt wurden. Aktualisiert sich d-migrate und
ändert dabei das Fingerabdruck-Verfahren, passen ältere Artefakte nicht mehr —
der Lauf bricht dann **bewusst laut** ab, statt einen falschen Vergleich zu
ziehen:

- Rollback: **Exit 8**, `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`.
- Overlay: **Exit 8**, `OVERLAY_STALE_SOURCE_FINGERPRINT` bzw.
  `OVERLAY_STALE_TARGET_FINGERPRINT`.

**Lösung:** Erzeugen Sie das betroffene Artefakt mit der aktuellen Version neu:
für ein Rücknahme-Skript den `migrate`-Lauf mit `--generate-rollback` erneut
ausführen (das erzeugte Down-Artefakt trägt dann den aktuellen Fingerabdruck);
für ein Overlay die darin gepinnten Soll-/Ist-Fingerabdrücke aus einem frischen
`--plan-artefact`-Lauf übernehmen. Bereits ausgerollte Migrationen sind davon
nicht betroffen — nur die *Artefakte* müssen zum aktuellen Stand passen.

### Wiederaufnahme schlägt mit Exit 3 fehl

**Ursache:** Zwischen dem ursprünglichen und dem fortgesetzten Lauf haben sich
relevante Optionen, die Tabellenliste oder Pfade geändert.

**Lösung:** Starten Sie den Lauf mit unveränderten Parametern erneut, oder
beginnen Sie ohne `--resume` neu.

---

## 6. Häufige Fragen (FAQ)

**Welche Datenbanken unterstützt d-migrate?**
PostgreSQL, MySQL/MariaDB und SQLite. MS SQL Server und Oracle sind geplant.

**Brauche ich ein JDK?**
Nein, wenn Sie das Docker-Image verwenden. Für die Installation ohne Docker
benötigen Sie Java 21 oder neuer.

**Wie gebe ich Passwörter sicher an?**
Am einfachsten als `${VAR}`-Platzhalter in einer benannten Verbindung der
`.d-migrate.yaml` — d-migrate ersetzt ihn aus der gleichnamigen Umgebungsvariable
(z. B. `postgresql://app:${DB_PASSWORD}@…`, dann `export DB_PASSWORD=…`).
Alternativ können Sie das Passwort verschlüsselt ablegen
(`d-migrate config credentials set`) oder eine Verbindung per
`credentialRef: "file:/pfad"`/`"env:VAR"`/`"keychain:<service>"` auf eine externe
Secret-Quelle zeigen lassen. Vermeiden Sie Passwörter im Klartext in Skripten; die Übersicht steht im
[Administrationshandbuch](administrationshandbuch.md#46-credential-handling).

**Was ist der Unterschied zwischen `schema generate` und `schema migrate`?**
`generate` erzeugt ein komplettes Schema von Grund auf. `migrate` überträgt nur
die Unterschiede zwischen Soll und Ist auf eine bestehende Datenbank.

**Wie übertrage ich Daten ohne Zwischendateien?**
Mit `data transfer` ([3.8](#38-daten-direkt-von-datenbank-zu-datenbank-übertragen)).
Die Zieltabellen müssen bereits existieren.

**Warum fehlen meine MySQL-/SQLite-Sequenzen?**
Diese Datenbanken haben keine nativen Sequenzen. Aktivieren Sie die Emulation
mit `--mysql-named-sequences helper_table` bzw.
`--sqlite-named-sequences helper_table` ([3.12](#312-sequenzenautowerte-korrekt-mitnehmen)).

**Wie stelle ich die Ausgabesprache um?**
Mit der globalen Option `--lang de` oder `--lang en`.

**Kann ich d-migrate aus einem KI-Agenten heraus nutzen?**
Ja, über den MCP-Server. Wie Sie ihn starten, zeigt
[3.15](#315-d-migrate-als-mcp-server-für-ki-agenten-bereitstellen); das Protokoll
und der Tool-Katalog stehen in der [API-Referenz](api-referenz.md).

---

## 7. Glossar

| Begriff | Bedeutung |
| ------- | --------- |
| **Neutrales Modell / Schema** | Herstellerunabhängige YAML-Beschreibung einer Datenbankstruktur; die Zwischenform zwischen Quelle und Ziel. |
| **Dialekt** | Konkrete Datenbankvariante: PostgreSQL, MySQL oder SQLite. |
| **DDL** | „Data Definition Language" — SQL-Anweisungen, die Struktur anlegen (`CREATE TABLE` usw.). |
| **Reverse Engineering** | Das Auslesen einer bestehenden Datenbank in ein neutrales Schema. |
| **Round-Trip** | Hin- und Rückübertragung (z. B. Datenbank → Schema → Datenbank) zur Kontrolle der Verlustfreiheit. |
| **Sequenz** | Quelle fortlaufender Zahlen (z. B. für IDs). |
| **Emulation / `helper_table`** | Nachbildung von Sequenzen in MySQL/SQLite über Hilfsobjekte. |
| **Split-DDL (pre-data/post-data)** | Aufteilung des SQL in „erst Tabellen, dann Trigger/Funktionen", damit der Datenimport dazwischen passt. |
| **Bundle / Manifest** | Beim Parquet-Export ein Ordner mit einer Datei pro Tabelle plus Inhaltsverzeichnis. |
| **Checkpoint / Resume** | Zwischenstand eines langen Laufs, um ihn nach Abbruch fortzusetzen. |
| **Benannte Verbindung** (Named Connection) | Ein in `.d-migrate.yaml` hinterlegter Name für eine Datenbank-URL. |
| **Up-/Down-SQL** | Anweisungen, die eine Migration ausführen (Up) bzw. zurücknehmen (Down). |
| **Exit-Code** | Rückgabewert eines Befehls; `0` bedeutet Erfolg. |

---

## 8. Anhang

### Anhang A — Befehls- und Optionsreferenz

#### A.1 Globale Optionen

| Option | Beschreibung |
| ------ | ------------ |
| `-c`, `--config` | Pfad zu einer Konfigurationsdatei |
| `--lang` | Sprache der Ausgabe (`de`, `en`); Vorrang vor `D_MIGRATE_LANG`, `LC_ALL`/`LANG`, `i18n.default_locale`; ungültige Werte → Exit 2 |
| `--output-format` | `plain` (Standard), `json`, `yaml` |
| `-v`, `--verbose` | Erweiterte Ausgabe (DEBUG) |
| `-q`, `--quiet` | Nur Fehler |
| `--no-color` | Farbausgabe deaktivieren |
| `--no-progress` | Fortschrittsanzeige deaktivieren |
| `-y`, `--yes` | Rückfragen automatisch bestätigen |
| `--version` | Version anzeigen |
| `-h`, `--help` | Hilfe anzeigen |

`--verbose` und `--quiet` schließen sich aus. Ergebnisse gehen nach stdout,
Fortschritt/Warnungen nach stderr.

#### A.2 `schema validate`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` | Schema-Datei (YAML/JSON), Pflicht |

#### A.3 `schema compare`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` | Operand: Pfad, `file:<path>` oder `db:<url-or-alias>` (Pflicht) |
| `--target` | Operand: Pfad, `file:<path>` oder `db:<url-or-alias>` (Pflicht) |
| `--output` | Ausgabedatei (Standard: stdout) |

#### A.4 `schema generate`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` | Schema-Datei (Pflicht) |
| `--target` | `postgresql`, `mysql`, `sqlite` (Pflicht) |
| `--output` | Ausgabedatei (Standard: stdout) |
| `--report` | Report-Datei (Standard: `<output>.report.yaml`) |
| `--generate-rollback` | Rollback-DDL erzeugen |
| `--deterministic` | Laufzeit-Timestamps weglassen |
| `--split` | `single` (Standard) oder `pre-post` |
| `--mysql-named-sequences` | `action_required` (Standard) oder `helper_table` (nur `--target mysql`) |
| `--sqlite-named-sequences` | `action_required` (Standard) oder `helper_table` (nur `--target sqlite`) |
| `--spatial-profile` | `postgis`, `native`, `spatialite`, `none` |

#### A.5 `schema reverse`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` | Datenbank-URL oder benannte Verbindung (Pflicht) |
| `--output` | Ausgabe-Schemadatei (YAML/JSON) |
| `--format` | `yaml` (Standard) oder `json` |
| `--report` | Report-Datei |
| `--include-views` / `--include-procedures` / `--include-functions` / `--include-triggers` | jeweiligen Objekttyp einschließen |
| `--include-all` | alle optionalen Objekttypen |
| `--name` / `--version` | Name bzw. Version im erzeugten Schema überschreiben |

> Beispiel einer erzeugten Schema-Datei (Tabelle + Function + View + Trigger):
> siehe [3.3](#33-eine-bestehende-datenbank-übernehmen-reverse-engineering).

#### A.6 `schema migrate`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` | Soll-Schema (Datei, Pflicht) |
| `--target` | `db:<url-or-alias>` oder `file:<current.yaml>` (Pflicht) |
| `--dialect` | `postgresql`/`mysql`/`sqlite` (Pflicht bei Datei-Ziel) |
| `--output` | Up-SQL-Ausgabedatei |
| `--rollback-output` | Down-SQL-Ausgabedatei (Pflicht bei `--generate-rollback` ohne `--plan-only`) |
| `--report` | Plan-/Risiko-Report (**Pflicht bei `--execute`**) |
| `--report-format` | `json` (Standard) oder `yaml` |
| `--plan-artefact` | Signierter `migration-plan.v1`-JSON |
| `--plan-only` | Nur Plan/Report, kein SQL |
| `--generate-rollback` | Down-SQL miterzeugen |
| `--execute` | Up-SQL gegen DB-Ziel ausführen (nur DB-Ziel) |
| `--dry-run` | Erzeugen, nichts ausführen (exklusiv zu `--execute`) |
| `--allow-destructive` | Destruktive Up-Operationen erlauben |
| `--allow-extension-install` | PostgreSQL `CREATE EXTENSION` erlauben |
| `--migration-overlay` | Overlay-JSON (wiederholbar) |
| `--rename-table` / `--rename-column` | Inline-Renames (wiederholbar) |
| `--lock-timeout-ms` | Lock-Budget in ms (10–60000, Default 5000) |
| `--sqlite-named-sequences` | `action_required` (Standard) oder `helper_table` |
| `--strict-gap-operations` | Operationen mit Sichtbarkeitslücke blocken |
| `--routine-capability` | Per-Routine-Capability-Override (wiederholbar) |
| `--debug-body` | UNSAFE: unmaskierte Routine-Bodies im Report |

> Beispiel-Ausgaben (`up.sql` + Plan-Report) eines Trockenlaufs: siehe
> [3.5](#35-eine-schemaänderung-ausrollen-und-zurücknehmen).

#### A.7 `schema rollback`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` | Down-SQL-Artefakt (Pflicht) |
| `--target` | Ziel-DB `db:<url-or-alias>` (Pflicht) |
| `--execute` | Down-SQL ausführen (exklusiv zu `--dry-run`) |
| `--allow-destructive` | Destruktive Down-Operationen erlauben |
| `--allow-partial-rollback` | Bewusst partielle Rollback-Artefakte ausführen |
| `--dry-run` | Nur Validierung/Preview |

#### A.8 `export flyway` / `liquibase` / `django` / `knex`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` | Schema-Datei (Pflicht) |
| `--output` | Ausgabeverzeichnis (Pflicht) |
| `--target` | `postgresql`, `mysql`, `sqlite` (Pflicht) |
| `--version` | Pflicht bei `django`/`knex`, optional bei `flyway`/`liquibase` |
| `--generate-rollback` | Down-Artefakt erzeugen |
| `--spatial-profile` | Spatial-Profil (wie `schema generate`) |
| `--report` | Transformationsbericht |

#### A.9 `data export`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` | URL oder benannte Verbindung (Pflicht) |
| `--format` | `json`, `yaml`, `csv`, `parquet` (Pflicht) |
| `-o`, `--output` | Datei oder Verzeichnis (Standard: stdout) |
| `--tables` | Tabellen (kommasepariert; Standard: alle) |
| `--filter` | Filter-DSL-Ausdruck |
| `--since-column` / `--since` | inkrementeller Export |
| `--split-files` | eine Datei pro Tabelle |
| `--chunk-size` | Rows pro Chunk (Standard 10000) |
| `--fetch-size` | JDBC-Cursor-Prefetch beim Lesen der Quelle (Standard: dialektspezifisch 1000); SQLite nur Hinweis |
| `--parallel` | Tabellen/Partitionen nebenläufig (Standard 1); pro-Kind-Datei bei `--split-files`, PostgreSQL |
| `--read-only` / `--no-read-only` | Quelle schreibgeschützt öffnen (Standard an); SQLite ohne `-wal`/`-shm` |
| `--encoding` | Output-Encoding (Standard `utf-8`) |
| `--csv-delimiter` / `--csv-bom` / `--csv-no-header` / `--null-string` | CSV-Optionen |
| `--csv-formula-guard` / `--no-csv-formula-guard` | CSV: formel-anfällige Text-Zellen für Tabellenkalkulationen entschärfen (`'`-Präfix); überschreibt `export.csv.formula_guard`; Standard aus (treuer Dump, meldet `W203`) |
| `--resume` / `--checkpoint-dir` | Wiederaufnahme |
| `--manifest-sha256` | Parquet: SHA-256 je Tabelle ins Manifest |

#### A.10 `data import`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` | Datei, Verzeichnis oder `-` (stdin) (Pflicht) |
| `--target` | URL oder benannte Verbindung (Standard: `database.default_target`) |
| `--format` | `json`/`yaml`/`csv`/`parquet` (sonst auto-erkannt) |
| `--schema` | Schema-Datei für Validierung/Reihenfolge |
| `--table` | Zieltabelle (Pflicht bei stdin/Single-File) |
| `--tables` / `--table-order` | Tabellenliste bzw. -reihenfolge (Verzeichnis) |
| `--on-error` | `abort` (Standard), `skip`, `log` |
| `--on-conflict` | `abort` (Standard), `skip`, `update` |
| `--trigger-mode` | `fire` (Standard), `disable` (nur PostgreSQL), `strict` |
| `--truncate` | Zieltabelle vorher leeren |
| `--atomic` | alles-oder-nichts: bei Fehler alle Tabellen auf leer zurück (setzt `--truncate` voraus) |
| `--disable-fk-checks` | FK-Prüfung aussetzen (nur MySQL/SQLite) |
| `--reseed-sequences` / `--no-reseed-sequences` | Identity/Sequenz neu setzen (Standard an) |
| `--encoding` | Eingabe-Encoding (Standard: Auto via BOM) |
| `--csv-no-header` / `--csv-null-string` | CSV-Optionen |
| `--chunk-size` | Datensätze pro Transaktion (Standard 10000) |
| `--parallel` | Tabellen nebenläufig, FK-sicher (Standard 1); ⊥ `--resume`/`--atomic` |
| `--resume` / `--checkpoint-dir` / `--no-checkpoint` | Wiederaufnahme |

#### A.11 `data transfer`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` / `--target` | Quell- bzw. Ziel-DB (Pflicht) |
| `--tables` | Tabellen (Standard: alle) |
| `--filter` | Filter-DSL für die Quelle |
| `--since-column` / `--since` | inkrementeller Transfer |
| `--on-conflict` | `abort` (Standard), `skip`, `update` |
| `--trigger-mode` | `fire` (Standard), `disable`, `strict` |
| `--truncate` | Zieltabellen vorher leeren |
| `--atomic` | alles-oder-nichts: bei Fehler alle Zieltabellen auf leer zurück (setzt `--truncate` voraus) |
| `--verify` | nach dem Transfer Quelle↔Ziel per SHA-256 abgleichen (Divergenz → Exit 3) |
| `--chunk-size` | Rows pro Chunk (Standard 10000) |
| `--fetch-size` | JDBC-Cursor-Prefetch beim Lesen der Quelle (Standard: dialektspezifisch 1000); SQLite nur Hinweis; auch der `--verify`-Read-Back nutzt ihn |
| `--parallel` | Tabellen/Partitionen nebenläufig, FK-sicher (Standard 1); ⊥ `--atomic`, SQLite→1 |
| `--read-only` / `--no-read-only` | **Quelle** schreibgeschützt öffnen (Standard an); Ziel bleibt schreibend; SQLite-Quelle ohne `-wal`/`-shm` |

#### A.12 `data profile`

| Option | Beschreibung |
| ------ | ------------ |
| `--source` | URL oder benannte Verbindung (Pflicht) |
| `--tables` | Tabellen (Standard: alle) |
| `--schema` | Datenbankschema (nur PostgreSQL, Standard `public`) |
| `--top-n` | häufigste Werte je Spalte (Standard 10, Max 1000) |
| `--format` | `json` (Standard) oder `yaml` |
| `--output` | Ausgabedatei (Standard: stdout) |
| `--read-only` / `--no-read-only` | Quelle schreibgeschützt öffnen (Standard an); SQLite ohne `-wal`/`-shm` |

#### A.13 `mcp serve`

| Option | Beschreibung |
| ------ | ------------ |
| `--transport` | `stdio` (Standard, ein Prozess pro Client) oder `http` (Streamable HTTP) |
| `--bind` | HTTP-Bind-Adresse (Standard `127.0.0.1`); Nicht-Loopback verlangt `--auth-mode` ≠ `disabled` |
| `--port` | HTTP-Port (`0` = freier Port) |
| `--public-base-url` | Öffentliche Basis-URL (muss `https` sein); für Nicht-Loopback-Produktivbetrieb erforderlich |
| `--auth-mode` | `jwt-jwks` (Standard), `jwt-introspection` oder `disabled` (nur Loopback) |
| `--issuer` | OIDC-Issuer-URI (für `jwt-*` erforderlich) |
| `--jwks-url` | JWKS-URL (für `jwt-jwks` erforderlich) |
| `--introspection-url` | RFC-7662-Introspection-Endpoint (für `jwt-introspection` erforderlich) |
| `--introspection-client-id` | OAuth-`client_id` für die Introspection (nur zusammen mit `--introspection-client-secret`) |
| `--introspection-client-secret` | OAuth-`client_secret` für die Introspection (nur zusammen mit `--introspection-client-id`) |
| `--audience` | erwarteter `aud`-Claim / Resource Indicator (für `jwt-*` erforderlich) |
| `--stdio-token-file` | Token-Registry (JSON/YAML) für den stdio-Transport |
| `--allow-origin` | Origin-Allowlist-Eintrag (wiederholbar) |
| `--mcp-state-dir` | Verzeichnis für dateibasierte Uploads/Artefakte (Vorrang vor `$DMIGRATE_MCP_STATE_DIR`; sonst temporär) |
| `--mcp-state-orphan-retention` | Aufbewahrung verwaister Dateien beim Start (`never`, `0`, `<n><ms\|s\|m\|h\|d>` oder ISO-8601; Standard 24h) |
| `--connection-config` | Server-YAML für secret-freie Verbindungsreferenzen (Standard: Root-`--config`) |
| `--cursor-keyring-file` | YAML-Keyring für HMAC-versiegelte Cursor (Mehrinstanz-Betrieb) |
| `--approval-grants-file` | JSON/YAML-Store für Freigaben (mit `mcp approval-grant issue`) |
| `--operation-timeout-seconds` | Timeout (s) für Upload-Finalisierung und Stale-Sweeper |

#### A.14 `mcp approval-grant issue`

| Option | Beschreibung |
| ------ | ------------ |
| `--file` | Freigabe-Store (JSON/YAML), Pflicht — derselbe wie `mcp serve --approval-grants-file` |
| `--tenant` | Tenant-ID aus der offenen Challenge (Pflicht) |
| `--caller` | Principal-ID, die den Job gestartet hat (Pflicht) |
| `--tool` | Tool-Name, z. B. `schema_reverse_start` (Pflicht) |
| `--approval-request-id` | aus `POLICY_REQUIRED` (Pflicht) |
| `--payload-fingerprint` | aus `POLICY_REQUIRED` (Pflicht) |
| `--scope` | freigegebener Scope (wiederholbar, mindestens einmal) |
| `--idempotency-key` *oder* `--approval-key` | Korrelation der offenen Anfrage (genau eines von beiden) |
| `--issuer-fingerprint` | Issuer-Identität im Grant (Standard `cli-approval-grant`) |
| `--grant-source` | Audit-/Quell-Label im Grant (Standard `cli-admin`) |
| `--expires-at` | RFC-3339-Ablaufzeitpunkt (Vorrang vor `--ttl-seconds`) |
| `--ttl-seconds` | Gültigkeitsdauer, wenn `--expires-at` fehlt (Standard 300) |
| `--token` | auszustellendes Token (Standard: generiert; gespeichert wird nur der Fingerprint) |

#### A.15 `mcp cursor-key generate`

| Option | Beschreibung |
| ------ | ------------ |
| `--kid` | Stabile Key-ID für künftige Cursor-Envelopes (Pflicht) |

Gibt das Keyring-YAML auf stdout aus (z. B. `… > keyring.yaml` umleiten).

#### A.16 `mcp cursor-key validate`

| Option | Beschreibung |
| ------ | ------------ |
| `--cursor-keyring-file` | zu prüfende Keyring-YAML-Datei (Pflicht) |

#### A.17 `config credentials`

Verwaltet den verschlüsselten Zugangsdaten-Speicher (`~/.d-migrate/credentials.enc`).
Das Master-Secret kommt aus `D_MIGRATE_MASTER_PASSWORD` oder einer interaktiven
Abfrage. Details: [Administrationshandbuch](administrationshandbuch.md#46-credential-handling).

| Befehl / Option | Beschreibung |
| ------ | ------------ |
| `config credentials set` | Zugangsdaten unter einem Verbindungsnamen ablegen |
| `--name` | Verbindungsname (Pflicht) |
| `--user` | Benutzername (Pflicht) |
| `--password` | Passwort; ohne Angabe interaktiv abgefragt |
| `config credentials list` | hinterlegte Namen anzeigen (nie Werte/Passwörter) |

#### A.18 `config show`

Zeigt die **effektiv gewählte** Konfigurationsdatei (Auflösung nach
`--config` > `D_MIGRATE_CONFIG` > `./.d-migrate.yaml`) als Abschnittsbaum, plus <!-- d-check:ignore (Nutzer-CWD-Pfad, kein Repo-Artefakt; ADR 0011) -->
die Namen aktiver `D_MIGRATE_*`-Variablen. Siehe [4.2](#42-welche-konfigurationsdatei-gilt).

| Befehl / Option | Beschreibung |
| ------ | ------------ |
| `config show` | effektiv gewählte Konfigurationsdatei anzeigen |
| `--section` | nur einen Abschnitt zeigen (`database`, `pipeline`, `ai`, …); unbekannter Name → Exit 2 |

Sensible Feldnamen (`password`, `secret`, `token`, `credentialRef`) und Passwörter
in URLs erscheinen als `***`. `${VAR}` wird nicht aufgelöst; Standardwerte, die
nicht in der Datei stehen, werden nicht ergänzt.

### Anhang B — Exit-Codes

| Code | Name | Bedeutung |
| ---- | ---- | --------- |
| `0` | SUCCESS | Erfolgreich |
| `1` | ERROR | Allgemeiner Fehler |
| `2` | USAGE_ERROR | Ungültige Argumente/Flags |
| `3` | VALIDATION_ERROR | Validierung fehlgeschlagen / Resume-Inkompatibilität |
| `4` | CONNECTION_ERROR | Datenbankverbindung fehlgeschlagen |
| `5` | MIGRATION_ERROR | Fehler während der Ausführung |
| `6` | AI_ERROR | KI-Provider nicht erreichbar |
| `7` | LOCAL_ERROR | Konfigurations-/Datei-/I/O-Fehler |
| `8` | MIGRATION_BLOCKED | Migration blockiert (Risiko/Dialekt/Drift) |
| `130` | INTERRUPTED | Durch Ctrl+C abgebrochen |

`schema compare` nutzt zusätzlich Exit `1` für „Unterschiede gefunden".

### Anhang C — Neutrales Typsystem

| Neutraler Typ | PostgreSQL | MySQL | SQLite |
| ------------- | ---------- | ----- | ------ |
| `identifier` | SERIAL | INT AUTO_INCREMENT | INTEGER PRIMARY KEY AUTOINCREMENT |
| `text` | VARCHAR(n) / TEXT | VARCHAR(n) / TEXT | TEXT |
| `char` | CHAR(n) | CHAR(n) | TEXT |
| `integer` | INTEGER | INT | INTEGER |
| `smallint` | SMALLINT | SMALLINT | INTEGER |
| `biginteger` | BIGINT | BIGINT | INTEGER |
| `float` | REAL / DOUBLE PRECISION | FLOAT / DOUBLE | REAL |
| `decimal` | DECIMAL(p,s) | DECIMAL(p,s) | REAL |
| `boolean` | BOOLEAN | TINYINT(1) | INTEGER |
| `datetime` | TIMESTAMP | DATETIME | TEXT (ISO 8601) |
| `date` | DATE | DATE | TEXT (ISO 8601) |
| `time` | TIME | TIME | TEXT (ISO 8601) |
| `uuid` | UUID | CHAR(36) | TEXT |
| `json` | JSONB | JSON | TEXT |
| `xml` | XML | TEXT (Fallback) | TEXT |
| `binary` | BYTEA | BLOB | BLOB |
| `email` | VARCHAR(254) | VARCHAR(254) | TEXT |
| `enum` | CREATE TYPE … ENUM | ENUM(…) | TEXT + CHECK |
| `array` | type[] | JSON | TEXT (JSON) |
| `geometry` | geometry(type, srid) | POINT / POLYGON / … | AddGeometryColumn() |

`identifier` ist der 32-bit-Auto-Increment-Vertrag; SQLites
`INTEGER PRIMARY KEY AUTOINCREMENT` ist dagegen 64-bit — ein Cross-Dialect-
Transfer verengt den Wertebereich (der Reverse merkt das als Note `R202` an).
Für 64-bit-Autowerte: `biginteger` + `generation` ([F.3](#f3-spalte)).

Die autoritative Liste inkl. Attributen steht in der
[Neutrales-Modell-Spezifikation](../../spec/neutral-model-spec.md#3-neutrales-typsystem).

### Anhang D — Fehler- und Warnungscodes

**Validierungsfehler (Auswahl):** `E001` Tabelle ohne Spalten · `E002` FK auf
nicht existierende Tabelle · `E003` FK auf nicht existierende Spalte · `E008`
Tabelle ohne Primärschlüssel · `E056` Sequenz braucht Emulation/manuelle
Behandlung.

**Verbindungsfehler:** `E100` unbekannter Dialekt · `E101` Connection refused ·
`E102` Authentifizierung fehlgeschlagen · `E103` Datenbank existiert nicht ·
`E104` SSL fehlgeschlagen · `E105` Timeout.

**Häufige Warnungen:** `W001` FLOAT statt DECIMAL für Geldbeträge · `W100`
Zeitzone geht bei TIMESTAMP→DATETIME verloren · `W116`/`W120`/`W124`
Sequenz-Hilfsobjekte degradiert/verändert (siehe
[3.12](#312-sequenzenautowerte-korrekt-mitnehmen)).

Die vollständige Liste steht in der
[CLI-Spezifikation](../../spec/cli-spec.md).

### Anhang E — Grenzwerte und Standardwerte

| Wert | Standard / Grenze |
| ---- | ----------------- |
| Chunk-Größe (Export/Import/Transfer) | 10000 Zeilen |
| `--top-n` (Profiling) | Standard 10, Maximum 1000 |
| `--lock-timeout-ms` (Migrate) | Standard 5000 ms, Bereich 10–60000 ms |
| Connection-Pool (außer SQLite) | 10 Verbindungen |
| Connection-Pool (SQLite) | 1 Verbindung |
| Standard-Encoding | UTF-8 |

### Anhang F — Schema-Referenz

Vollständige Übersicht der Elemente einer Schema-YAML-Datei. Maßgeblich ist
[`neutral-model-spec.md`](../../spec/neutral-model-spec.md); die maschinelle
Validierung erfolgt gegen [`schema.json`](../../spec/schema.json).

#### F.1 Top-Level

| Schlüssel | Pflicht | Beschreibung |
| --------- | ------- | ------------ |
| `schema_format` | ja | Formatversion, immer `"1.0"` |
| `name` | ja | Schema-Name |
| `version` | ja | Anwendungs-Schema-Version (z. B. `"1.0.0"`) |
| `description` | nein | Freitext |
| `encoding` | nein | Standard-Encoding (Default `utf-8`) |
| `locale` | nein | Standard-Locale (z. B. `de_DE`) |
| `custom_types` | nein | benutzerdefinierte Typen → [F.9](#f9-custom-types) |
| `tables` | nein | Tabellen → [F.2](#f2-tabelle) |
| `procedures` / `functions` | nein | Routinen → [F.13](#f13-procedures-und-functions) |
| `views` | nein | Views → [F.11](#f11-views) |
| `triggers` | nein | Trigger → [F.12](#f12-trigger) |
| `sequences` | nein | Sequenzen → [F.10](#f10-sequenzen) |

#### F.2 Tabelle

| Feld | Pflicht | Beschreibung |
| ---- | ------- | ------------ |
| `description` | nein | Freitext |
| `columns` | ja | Map Spaltenname → Spalte ([F.3](#f3-spalte)) |
| `primary_key` | ja | Liste der Primärschlüssel-Spalten |
| `indices` | nein | Liste von Indizes ([F.6](#f6-indizes)) |
| `constraints` | nein | Liste von Constraints ([F.7](#f7-constraints)) |
| `partitioning` | nein | Partitionierung ([F.8](#f8-partitionierung)) |
| `metadata` | nein | physische Metadaten: `engine` (MySQL), `without_rowid` (SQLite) — meist reverse-generiert |

#### F.3 Spalte

Gemeinsame Felder: `type` (Pflicht; einer der neutralen Typen, siehe
[Anhang C](#anhang-c--neutrales-typsystem)), `required` (NOT NULL),
`unique`, `default` (String/Zahl/Boolean/Funktionsname), `references`
([F.5](#f5-referenzen)).

Typ-spezifische Attribute:

| Attribut | Nur bei Typ | Beschreibung |
| -------- | ----------- | ------------ |
| `auto_increment` | `identifier` | Auto-Increment |
| `max_length` | `text` | Maximallänge |
| `length` | `char` | feste Länge (Pflicht) |
| `precision`, `scale` | `decimal` | Präzision/Nachkommastellen (Pflicht) |
| `float_precision` | `float` | `single` oder `double` (Default `double`) |
| `timezone` | `datetime` | `true` = WITH TIME ZONE |
| `values` | `enum` | Inline-Werteliste |
| `ref_type` | `enum` | Verweis auf `custom_types` |
| `element_type` | `array` | Elementtyp (Pflicht) |
| `geometry_type`, `srid` | `geometry` | Spatial → [F.4](#f4-spatial-typen) |
| `generation` | `integer`, `biginteger` | Identity-Spalte: `type: identity`, `mode: by_default`/`always`, optional `sequence_name`, `legacy_serial_syntax` |

`identifier` ist bewusst der **32-bit**-Auto-Increment-Vertrag (PostgreSQL
`SERIAL`, MySQL `INT AUTO_INCREMENT`). 64-bit-Autowerte modelliert man als
`biginteger` plus `generation: {type: identity}` — PostgreSQL rendert daraus
`BIGINT GENERATED … AS IDENTITY`. `generation` schließt `default` auf
derselben Spalte aus; Details in der
[Schema-Referenz](../../spec/schema-reference.md).

#### F.4 Spatial-Typen

Der Typ `geometry` trägt zwei optionale Attribute:

```yaml
columns:
  location:
    type: geometry
    geometry_type: point     # Default: geometry
    srid: 4326               # positive Ganzzahl (z. B. 4326 für WGS 84)
```

Erlaubte `geometry_type`-Werte: `geometry` (Default, beliebig), `point`,
`linestring`, `polygon`, `multipoint`, `multilinestring`, `multipolygon`,
`geometrycollection`.

Wie `geometry`-Spalten in DDL überführt werden, steuert `--spatial-profile`
(`postgis`, `native`, `spatialite`, `none`) bei `schema generate`/`export`
(siehe [Anhang A.4](#a4-schema-generate)).

Nicht unterstützt: `geography`, 3D-(`z`)/Mess-(`m`)-Koordinaten, Spatial-Index
als eigener Typ; `geometry` ist außerdem **nicht** als `array.element_type`
zulässig.

#### F.5 Referenzen

Spalten-Fremdschlüssel über `references`:

| Feld | Pflicht | Werte |
| ---- | ------- | ----- |
| `table`, `column` | ja | Zieltabelle/-spalte |
| `on_delete`, `on_update` | nein | `restrict`, `cascade`, `set_null`, `set_default`, `no_action` |

#### F.6 Indizes

| Feld | Beschreibung |
| ---- | ------------ |
| `name` | Indexname |
| `columns` | Liste aus Spaltennamen **oder** `{ name, direction: asc\|desc }` |
| `type` | `btree` (Default), `hash`, `gin`, `gist`, `brin` |
| `unique` | `true` für Unique-Index |
| `where` | Prädikat für Partial-Index (Raw-SQL) |

#### F.7 Constraints

| Feld | Beschreibung |
| ---- | ------------ |
| `name` | Constraint-Name |
| `type` | `check`, `unique`, `exclude`, `foreign_key` |
| `columns` | beteiligte Spalten |
| `expression` | nur bei `check` (Raw-SQL) |
| `references` | nur bei `foreign_key`: `table`, `columns`, `on_delete`, `on_update` |

#### F.8 Partitionierung

| Feld | Beschreibung |
| ---- | ------------ |
| `type` | `range`, `hash`, `list` |
| `key` | Liste der Partitionierungs-Spalten |
| `partitions` | Liste aus `{ name, from, to, values }` |

#### F.9 Custom Types

```yaml
custom_types:
  order_status:               # enum
    kind: enum
    values: [pending, shipped]
  address:                    # composite (PostgreSQL nativ; MySQL/SQLite Fallback)
    kind: composite
    fields:
      street: { type: text, max_length: 200 }
      zip:    { type: char, length: 10 }
  positive_amount:            # domain
    kind: domain
    base_type: decimal
    precision: 10
    scale: 2
    check: "VALUE >= 0"
```

#### F.10 Sequenzen

| Feld | Default | Beschreibung |
| ---- | ------- | ------------ |
| `start` | 1 | Startwert |
| `increment` | 1 | Schrittweite |
| `min_value`, `max_value` | — | Grenzen |
| `cycle` | false | Neustart nach `max_value` |
| `cache` | — | vorausberechnete Werte |
| `preserve_current_value` | false | Laufzeitwert über Migration retten — siehe [3.12](#312-sequenzenautowerte-korrekt-mitnehmen) |

#### F.11 Views

| Feld | Beschreibung |
| ---- | ------------ |
| `materialized` | `true` für Materialized View |
| `refresh` | `on_demand` oder `on_commit` (nur PostgreSQL) |
| `query` | SELECT-Anweisung |
| `columns` | optionale sichtbare Signatur: Liste aus Name **oder** `{ name, type }` |
| `dependencies` | `tables`, `views`, `columns` + Projektionsstatus (`*_projection_status`) |
| `source_dialect` | Quell-Dialekt des Query-Texts |

#### F.12 Trigger

| Feld | Pflicht | Werte |
| ---- | ------- | ----- |
| `table` | ja | Zieltabelle |
| `event` | ja | `insert`, `update`, `delete` |
| `timing` | ja | `before`, `after`, `instead_of` |
| `for_each` | nein | `row` (Default) oder `statement` |
| `condition` | nein | WHEN-Bedingung (oder `null`) |
| `body` | nein | Trigger-Rumpf |
| `dependencies`, `source_dialect` | nein | wie bei Routinen |

#### F.13 Procedures und Functions

| Feld | Procedure | Function | Beschreibung |
| ---- | :-------: | :------: | ------------ |
| `parameters` | ✓ | ✓ | Liste aus `{ name, type, direction: in\|out\|inout }` |
| `returns` | — | ✓ | `{ type, precision, scale }` |
| `deterministic` | — | ✓ | für MySQL DETERMINISTIC |
| `language` | ✓ | ✓ | Quell-Sprache (z. B. `plpgsql`) |
| `body` | ✓ | ✓ | Routinen-Rumpf |
| `dependencies` | ✓ | ✓ | `tables`, `views`, `columns` |
| `source_dialect` | ✓ | ✓ | Quell-DB des Rumpfs |

---

## 9. Änderungshistorie

| Handbuch-Version | Datum | Änderung |
| ---------------- | ----- | -------- |
| 0.1 | 15.06.2026 | Erster aufgabenorientierter Entwurf für Software-Version 0.9.9 (Beta). |
| 0.2 | 05.07.2026 | Fehlerbehebung: Hinweis zur Fingerabdruck-Versionsbindung von Rollback-Artefakten und Overlays (Abbruch mit Exit 8 nach einem Update) ergänzt. |
| 0.3 | 16.07.2026 | Auf Software-Version 1.0.0-RC-SNAPSHOT aktualisiert. Zugangsdaten-Optionen erweitert (`D_MIGRATE_DB_PASSWORD`, verschlüsselter Store `config credentials`, `credentialRef: file:/env:`); neuer Fehlerfall „credentialRef fail-closed"; `config credentials` in die Befehlsreferenz (A.17) aufgenommen. |
| 0.4 | 31.07.2026 | Auf Software-Version 1.0.0-RC2 aktualisiert. `credentialRef`-Aufzählungen um das dritte Schema `keychain:` ergänzt (inkl. Hinweis, dass es in CI/Container/Server fail-closed scheitert — neuer Absatz in der Fehlerbehebung). `config show` in 4.2 als Antwort auf „welche Konfiguration gilt gerade?" aufgenommen und als A.18 in die Befehlsreferenz. |
| 0.5 | 15.08.2026 | Auf Software-Version 1.0.0 (erstes Stable) aktualisiert. **Keine inhaltliche Änderung** — zwischen 1.0.0-RC2 und 1.0.0 kam kein neues Kommando und keine geänderte Option hinzu; die Releases dazwischen waren bauseitig. Nachgezogen wurde die Kopfzeile, die beim 0.4-Eintrag stehengeblieben war (sie nannte weiterhin Handbuch-Version 0.3 und den 16.07.). Sachlich relevant für Leser: der `--user`-Zusatz beim Docker-Aufruf (Abschnitt 1) ist ab 1.0.0 **nötig** und nicht mehr nur empfohlen — bis 0.9.12 lief das Image als root und schrieb auch ohne ihn. |

---

**Weiterführend:** [Migrations-Leitfaden](migrations-leitfaden.md) ·
[Best-Practices-Leitfaden](best-practices-leitfaden.md) ·
[Troubleshooting-Leitfaden](troubleshooting-leitfaden.md) ·
[Administrationshandbuch](administrationshandbuch.md) ·
[API-Referenz](api-referenz.md) ·
[BI-Demo-Stack](../../examples/bi-demo/README.md) ·
[Changelog](../../CHANGELOG.md)
