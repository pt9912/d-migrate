# Plan: Persistence-JDBC-Migrationen aus neutralem Server-State-DDL

> Dokumenttyp: Architektur- und Implementierungsplan
>
> Status: Entwurf (2026-05-08)
>
> Referenzen:
> - `adapters/driven/persistence-jdbc/src/main/resources/db/migration/`
> - `adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/`
> - `hexagon/ports-common/src/testFixtures/kotlin/dev/dmigrate/server/ports/contract/`
> - `spec/port-atomicity.md`
>
> **Status-Update 2026-09-05** ([ADR 0051](../../adr/0051-server-state-schema-generiert-statt-handgeschrieben.md)):
> §3.2s Prämisse „PostgreSQL braucht ein Dialekt-Overlay für
> JSONB/TIMESTAMPTZ/partielle Indizes" ist für PostgreSQL **widerlegt** —
> `NeutralType.Json` rendert bereits `JSONB`, `IndexDefinition.where`
> rendert bereits partielle Indizes, beides ohne jede Overlay-
> Erweiterung (belegt in ADR 0051). Für PostgreSQL ist daher **kein**
> Overlay nötig.
>
> **§3.2s Oracle-Teil ist spekulativ, nicht durch Arbeit gedeckt**: Es
> gibt im Repo noch keine Oracle-Unterstützung — keinen Treiber, keinen
> Dialekt-Code, keine `NeutralType`-Verifikation gegen eine echte
> Oracle-Instanz. Konkrete Aussagen wie „JSON oder CLOB plus
> JSON-Constraint" oder „function-based indexes oder alternative
> Indexstrategie" sind Vermutungen ohne verifizierte Grundlage, keine
> Analyseergebnisse. Die eigentliche Overlay-Frage für Oracle bleibt
> offen und ungeprüft — zu beantworten erst, wenn echte Oracle-
> Treiberarbeit beginnt (Milestone 1.8.0 laut `roadmap.md`), nicht vorher.
>
> Ad hoc angewendet (ohne den hier skizzierten Gradle-Generator/Drift-
> Check): `V2__schema_artifact_stores.sql`
> (ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md) wählt
> genau die in §3.3 vorgeschlagene **"Eingefrorene Legacy-V1"**-Variante
> — V1 bleibt unverändert Hand-SQL/Legacy-Baseline, V2 wurde einmalig
> per manuellem `schema migrate --source ... --target file:... --dry-run`-
> Diff generiert und der SQL-Text von Hand in die Migrationsdatei
> übernommen. Kein Gradle-Task, kein automatisierter Drift-Check, keine
> Dialekt-Overlay-Struktur — dieser Plan bleibt für all das die
> maßgebliche Quelle, sollte das automatisiert werden.

---

## 1. Ziel

Die Server-State-Persistenz soll langfristig nicht aus handgeschriebenen,
auseinanderlaufenden DDL-Dateien pro Datenbank bestehen.

Stattdessen soll ein neutrales internes Server-State-Schema als Source of
Truth dienen. Daraus werden datenbankspezifische Flyway-Migrationen erzeugt,
zunaechst fuer PostgreSQL und spaeter fuer weitere Datenbanken wie Oracle.

Wichtig: Das neutrale DDL beschreibt nur die Struktur. Die Laufzeitlogik der
Stores bleibt weiterhin explizite Adapterlogik und wird ueber Contract-Tests
abgesichert.

---

## 2. Ausgangslage

Aktuell existiert fuer den produktiven Server-State ein PostgreSQL-spezifischer
Flyway-Pfad:

- `V1__server_state_initial.sql`
- `JdbcMigrationRunner`
- dedizierte Flyway-History-Table
- JDBC-Stores fuer Idempotency, Jobs, Quota und Owner-Tracking

Diese SQL-Migration ist kein Anwender-DDL, sondern internes Betriebsmodell fuer
d-migrate selbst. Sie speichert Laufzeitlogik:

- idempotente Tool-Aufrufe
- Job-Statusuebergaenge
- Quota-Reservierungen
- Cancel-/Lease-/Expiry-Semantik
- atomare Compare-and-set-Uebergaenge

Die Tabellenstruktur ist trotzdem DDL und kann neutral modelliert werden. Der
kritische Teil ist jedoch die Semantik der Store-Operationen, nicht nur die
Tabellenanlage.

---

## 3. Produktentscheidung

### 3.1 Neutrales Server-State-Schema

Ein internes neutrales Schema beschreibt:

- Tabellen
- Spalten
- Primaerschluessel
- eindeutige Constraints
- Indizes
- optionale TTL-/Expiry-Indizes
- JSON-Spalten als abstrakten Typ
- Zeitstempel mit Zeitzonen-Semantik

Vorgeschlagener Ort:

```text
adapters/driven/persistence-jdbc/src/main/resources/server-state/schema.yaml
```

Dieses Schema ist kein Nutzervertrag und nicht Teil der normalen
`schema generate`-/`schema compare`-Workflows. Es ist ein Build-/Maintainer-
Artefakt fuer d-migrates eigene Control-Plane.

### 3.2 Dialekt-Overlays

Nicht alles laesst sich verlustfrei in einem neutralen Kern ausdruecken.
Deshalb braucht das Schema dialektspezifische Overlays.

Beispiele:

- PostgreSQL: `JSONB`, `TIMESTAMPTZ`, partielle Indizes
- Oracle: `JSON` oder `CLOB` plus JSON-Constraint, `TIMESTAMP WITH TIME ZONE`,
  function-based indexes oder alternative Indexstrategie
- MySQL: JSON-Typ, `DATETIME(6)`/Timezone-Strategie, andere Upsert-Semantik

Moegliche Struktur:

```text
server-state/
  schema.yaml
  overlays/
    postgresql.yaml
    oracle.yaml
```

### 3.3 Generierte Flyway-Migrationen

Flyway soll weiterhin fertige SQL-Dateien ausfuehren. Zur Laufzeit wird nicht
d-migrate gestartet, um sein eigenes Server-State-Schema zu erzeugen.

Stattdessen werden generierte SQL-Dateien als Golden Files eingecheckt:

```text
db/migration/postgresql/V1__server_state_initial.sql
db/migration/oracle/V1__server_state_initial.sql
```

Die Generierung ist ein Maintainer-Schritt, aehnlich Golden-File-Updates:

```bash
./gradlew :adapters:driven:persistence-jdbc:generateServerStateMigrations
```

Ein Drift-Check stellt sicher, dass `schema.yaml` und die eingecheckten SQLs
zusammenpassen.

Verbindlich: Die aktuell veroeffentlichte PostgreSQL-`V1` ist fuer Flyway
immutable. Eine bereits migrierte produktive Server-State-DB enthaelt die
Checksumme von `V1__server_state_initial.sql` in
`flyway_server_state_history`; jede nachtraegliche Aenderung an Inhalt,
Whitespace, Kommentaren oder Statement-Reihenfolge wuerde `validate()` brechen.

Der erste Generator-Slice muss deshalb eine der beiden Varianten explizit
waehlen:

- **Byte-identische V1**: Der PostgreSQL-Generator erzeugt exakt dieselben Bytes
  wie die vorhandene `V1__server_state_initial.sql`. Dann darf V1 als generiertes
  Golden File gelten.
- **Eingefrorene Legacy-V1**: Die bestehende V1 bleibt unveraendert und wird
  nicht durch Generator-Output ersetzt. Das neutrale Schema wird auf den
  beobachteten V1-Zielzustand validiert; automatisch generierte Aenderungen
  beginnen erst ab `V2__...`.

Ein Drift-Check darf V1 nur dann neu schreiben oder als veraltet melden, wenn
die Byte-identische-V1-Variante umgesetzt ist. Andernfalls prueft er V1 gegen
einen festgehaltenen Legacy-Hash und prueft nur V2+ gegen Generator-Output.

### 3.4 Dialektabhaengige Flyway-Locations

Der aktuelle `JdbcMigrationRunner` verwendet `classpath:db/migration` als
Default-Location. Das ist fuer den heutigen PostgreSQL-only-Pfad korrekt, wird
aber falsch, sobald mehrere Dialekte im selben Artefakt liegen: Flyway duerfte
nicht versehentlich PostgreSQL- und Oracle-`V1` gleichzeitig scannen.

Vor dem Einchecken mehrerer Dialekt-Unterverzeichnisse braucht der Runner einen
expliziten Dialekt-/Location-Vertrag, zum Beispiel:

```text
postgresql -> classpath:db/migration/postgresql
oracle     -> classpath:db/migration/oracle
```

Die Umstellung darf bestehende PostgreSQL-Installationen nicht brechen:

- Entweder bleibt der PostgreSQL-Pfad bis zum Location-Switch bei
  `classpath:db/migration`, und Oracle wird in einem separaten Modul oder Runner
  mit eigener Location eingefuehrt.
- Oder der PostgreSQL-Pfad wird auf `classpath:db/migration/postgresql`
  umgestellt, aber nur zusammen mit einem Kompatibilitaetstest: mit der alten V1
  migrieren, danach den neuen Runner gegen dieselbe DB mit
  `flyway_server_state_history` validieren. `validate()` muss ohne Repair,
  Baseline oder History-Migration erfolgreich sein.

Der Runner muss fuer jeden Dialekt nur dessen Migrationen sehen. Tests muessen
belegen, dass PostgreSQL keine Oracle-Migrationen scannt und umgekehrt.

---

## 4. Nicht-Ziele

- keine automatische Portierung der Store-Logik durch neutrales DDL
- keine Laufzeit-Generierung der internen Server-State-DB
- kein Versuch, `ON CONFLICT`, `MERGE`, Locking und Isolation vollstaendig im
  DDL-Modell zu verstecken
- kein Ersatz fuer datenbankspezifische Integrationstests
- keine Vermischung mit Anwender-Zielschemata

---

## 5. Store-Logik bleibt dialektspezifisch

Die eigentliche Komplexitaet liegt in Operationen wie:

- `reserve` vs. `Existing` vs. `Conflict`
- atomarer Job-Start ueber Idempotency + JobStore
- Compare-and-set-Statusuebergaenge
- Quota-Reserve/Commit/Release/Refund
- Sweeper-Logik fuer abgelaufene Leases

Diese Semantik kann dieselben Ports verwenden, braucht aber pro Datenbank
eigene SQL-Statements und ggf. eigene Transaktionsstrategien.

Jdbi3 kann dabei helfen:

- weniger `PreparedStatement`-/`ResultSet`-Boilerplate
- klarere DAO-/Repository-Struktur
- einfacheres Row-Mapping
- explizite Transaktions-Handles

Jdbi3 ersetzt aber keine Dialektentscheidung. PostgreSQL und Oracle brauchen
weiterhin getrennte SQLs fuer Upsert, JSON, Indexe und Locking.

---

## 6. Vorgeschlagene Modulstruktur

Kurzfristig:

```text
adapters/driven/persistence-jdbc
```

enthaelt weiterhin PostgreSQL als erste Implementierung.

Mittelfristig pruefen:

```text
adapters/driven/persistence-jdbc-postgresql
adapters/driven/persistence-jdbc-oracle
adapters/driven/persistence-jdbc-common
```

`persistence-jdbc-common` enthaelt gemeinsame DTOs, JSON-Codecs, Contract-Test-
Hilfen und ggf. Jdbi-Basisklassen. Die dialektspezifischen Module enthalten:

- Flyway-Migrationen
- SQL-Statements
- Store-Implementierungen
- Integrationstests gegen echte Datenbankcontainer

---

## 7. Akzeptanzkriterien

- Es gibt ein neutrales internes Server-State-Schema als Source of Truth.
- PostgreSQL-Flyway-SQL kann daraus reproduzierbar generiert werden.
- Ein Drift-Check faellt fehl, wenn generierte SQL-Dateien veraltet sind. Fuer
  eine eingefrorene Legacy-V1 prueft er den bekannten V1-Hash statt die Datei
  aus Generator-Output neu zu schreiben.
- Die bestehende PostgreSQL-V1 bleibt Flyway-kompatibel: Eine DB, die mit der
  alten V1 migriert wurde, besteht `JdbcMigrationRunner.validate()` auch nach der
  Generator-/Location-Umstellung.
- Der MigrationRunner scannt pro Dialekt genau eine Flyway-Location und fuehrt
  nicht mehrere gleichnamige `V1`-Migrationen aus verschiedenen Dialekten
  zusammen.
- Die bestehenden Store-Contract-Tests laufen unveraendert gegen PostgreSQL.
- Eine Oracle-Erweiterung kann dieselben Ports und Contract-Tests verwenden.
- Dialekt-Overlays sind explizit, nicht implizit in String-Ersetzungen versteckt.
- Das Dokument trennt DDL-Struktur klar von Store-Laufzeitlogik.

---

## 8. Arbeitspakete

1. Bestehendes PostgreSQL-Server-State-DDL in ein neutrales Schema ueberfuehren.
2. Fehlende Modellfaehigkeiten inventarisieren: JSON, Timestamp, partielle
   Indizes, TTL-/Expiry-Indexe.
3. PostgreSQL-Overlay definieren.
4. V1-Strategie festlegen:
   - PostgreSQL-V1 byte-identisch generieren, oder
   - bestehende PostgreSQL-V1 als Legacy-Baseline einfrieren und Generator erst
     fuer V2+ verwenden.
5. Generator fuer PostgreSQL-Flyway-Artefakte implementieren.
6. Drift-Check in Gradle einhaengen, inklusive Legacy-V1-Hash oder
   byte-identischem V1-Vergleich.
7. Bestehende Migration gegen generierten Output bzw. Legacy-Hash abgleichen.
8. Dialekt-/Location-Vertrag im `JdbcMigrationRunner` einfuehren oder die
   Modultrennung so festlegen, dass pro Runner nur eine Dialekt-Location
   sichtbar ist.
9. Kompatibilitaetstest fuer bestehende PostgreSQL-History ergaenzen: alte V1
   anwenden, neuen Runner/Location-Vertrag validieren.
10. Jdbi3-Einsatz fuer Store-Implementierungen evaluieren.
11. Oracle-Overlay und Oracle-Store-Prototyp planen.
12. Contract-Test-Matrix fuer PostgreSQL und Oracle definieren.

---

## 9. Offene Fragen

- ~~Soll das interne Server-State-Schema dasselbe neutrale Modell wie
  Anwender-Schemata nutzen oder ein kleineres internes Modell?~~
  **Beantwortet (2026-09-05, ADR 0051):** dasselbe Modell. Der komplette
  V1-Ist-Zustand (fuenf Tabellen, inkl. JSONB-Spalte und partiellem Index)
  liess sich verlustfrei mit dem regulaeren `SchemaDefinition`/
  `TableDefinition`/`NeutralType`-Modell nachbilden und per
  `schema generate`/`schema migrate` (denselben Befehlen wie fuer
  Anwenderschemata) verarbeiten — kein kleineres internes Modell noetig.
- ~~Wird die bestehende PostgreSQL-V1 byte-identisch generiert oder als
  eingefrorene Legacy-Baseline behandelt?~~
  **Beantwortet (2026-09-05, ADR 0051):** eingefrorene Legacy-Baseline.
  V1 bleibt unveraendertes Hand-SQL; `V2__schema_artifact_stores.sql`
  ist die erste per Diff generierte Migration.
- Welche Oracle-Version ist Mindestziel?
- Ist Jdbi3 ein eigener Refactoring-Schritt vor Oracle oder Teil der
  Oracle-Einfuehrung?
