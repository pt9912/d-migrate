# Plan: Persistence-JDBC-Migrationen aus neutralem Server-State-DDL

> Dokumenttyp: Architektur- und Implementierungsplan
>
> Status: Entwurf (2026-05-08)
>
> Referenzen:
> - `adapters/driven/persistence-jdbc/src/main/resources/db/migration/`
> - `adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/`
> - `hexagon/ports-common/src/testFixtures/kotlin/dev/dmigrate/server/ports/contract/`
> - `spec/phase-e-port-atomicity.md`

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

Stattdessen werden generierte SQL-Dateien eingecheckt:

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
- Ein Drift-Check faellt fehl, wenn generierte SQL-Dateien veraltet sind.
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
4. Generator fuer PostgreSQL-Flyway-V1 implementieren.
5. Drift-Check in Gradle einhaengen.
6. Bestehende Migration gegen generierten Output abgleichen.
7. Jdbi3-Einsatz fuer Store-Implementierungen evaluieren.
8. Oracle-Overlay und Oracle-Store-Prototyp planen.
9. Contract-Test-Matrix fuer PostgreSQL und Oracle definieren.

---

## 9. Offene Fragen

- Soll das interne Server-State-Schema dasselbe neutrale Modell wie
  Anwender-Schemata nutzen oder ein kleineres internes Modell?
- Werden generierte SQLs committed oder nur im Build erzeugt?
- Wie wird eine bestehende produktive `flyway_server_state_history` bei
  Umstellung der Dateipfade behandelt?
- Welche Oracle-Version ist Mindestziel?
- Ist Jdbi3 ein eigener Refactoring-Schritt vor Oracle oder Teil der
  Oracle-Einfuehrung?

