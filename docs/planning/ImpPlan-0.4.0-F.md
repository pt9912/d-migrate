# Implementierungsplan: Phase F — Schritte 29–33

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade  
> **Phase**: F (End-to-End-Tests mit Testcontainers)  
> **Schritte**: 29–33  
> **Status**: Abgeschlossen  
> **Referenz**: `implementation-plan-0.4.0.md` §4 Phase F, §8 Coverage, §9 Verifikation

---

## 1. Ziel

- Import-Pfad gegen echte PostgreSQL- und MySQL-Container verifizieren (analog zu
  den Export-E2E-Tests aus 0.3.0 Phase F).
- Sequence-/AUTO_INCREMENT-Reseeding, Trigger-Handling, `--truncate`,
  `--on-conflict update` und `--disable-fk-checks` unter realen Bedingungen testen.
- Inkrementellen Round-Trip E2E absichern: initial export → Quelldaten ändern →
  delta export (`--since`) → UPSERT-Import → Vergleich.

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| CLI-Import-Command + Runner | E-24/26/27 | ✅ Abgeschlossen |
| SQLite-E2E-Tests | E-28 | ✅ Abgeschlossen |
| LF-013 `--since-column`/`--since` | E-25 | ✅ Abgeschlossen |
| PostgresDataWriter + SchemaSync | C-16/18 | ✅ Abgeschlossen |
| MysqlDataWriter + SchemaSync | C-16/18 | ✅ Abgeschlossen |
| Export-E2E-Infrastruktur (0.3.0) | F-28/29 (0.3.0) | ✅ Vorhanden |
| `scripts/test-integration-docker.sh` | 0.3.0 F-39 | ✅ Vorhanden |
| `integration.yml` CI-Workflow | 0.3.0 F-39 | ✅ Vorhanden |

## 3. Architekturentscheidung

- Tests nutzen die bestehende Testcontainers-Infrastruktur aus 0.3.0
  (`DataExportE2EPostgresTest`/`DataExportE2EMysqlTest` als Vorlage).
- Tag `integration` — Tests laufen nur mit `-PintegrationTests` oder via
  `scripts/test-integration-docker.sh`.
- Jeder Test erstellt Tabellen in `beforeSpec`, bereinigt per
  `TRUNCATE ... RESTART IDENTITY` (PG) bzw. `DELETE FROM` + `ALTER TABLE AUTO_INCREMENT`
  (MySQL) in `beforeTest`.
- SQLite-E2E (Schritt 31) ist bereits durch `CliDataImportTest` abgedeckt.
- `integration.yml` erfordert keine Anpassungen — der Tag-Filter greift automatisch.

## 3b. Implementierungs-Vorbedingungen (vor den Tests zu erledigen)

### Truncate in PostgreSQL- und MySQL-Writer implementieren

Wie bei SQLite (in E-28 behoben) setzt `markTruncatePerformed()` in
`PostgresTableImportSession` und `MysqlTableImportSession` nur ein Flag
für Sequence-Reseeding, führt aber kein `TRUNCATE TABLE` (PG) bzw.
`DELETE FROM` (MySQL) aus. Ohne diese Implementierung schlagen die
`--truncate`-Tests fehl.

**PostgresDataWriter.openTable():**
```kotlin
if (options.truncate) {
    inOwnTransaction(conn) { stmt ->
        stmt.execute("TRUNCATE TABLE ${qualified.quotedPath()} RESTART IDENTITY")
    }
}
```

**MysqlDataWriter.openTable():**
```kotlin
if (options.truncate) {
    if (!conn.autoCommit) conn.autoCommit = true
    conn.createStatement().use { stmt ->
        stmt.execute("DELETE FROM ${qualified.quotedPath()}")
    }
}
```

Anschließend jeweils `session.markTruncatePerformed()` aufrufen (analog
zur SQLite-Lösung in `SqliteDataWriter`).

## 4. Abgrenzung

- Nicht Teil von Phase F: Performance-Gate, Schema-Reverse-Engineering, Checkpoint/Resume.
- Keine neuen Treiber-Features über die Truncate-Nachrüstung hinaus.

## 5. Betroffene Dateien

### 5.1 Neu

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataImportE2EPostgresTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataImportE2EMysqlTest.kt`

### 5.2 Referenz (bestehende Vorlagen)

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataExportE2EPostgresTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataExportE2EMysqlTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataImportTest.kt` (SQLite-E2E)

## 6. Umsetzungsschritte

### Schritt 29: `DataImportE2EPostgresTest`

Testklasse mit `tags(IntegrationTag)` und `PostgreSQLContainer("postgres:16-alpine")`.

**Schema-Setup (beforeSpec):**
```sql
CREATE TABLE users (
    id         SERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    email      TEXT,
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    score      NUMERIC(10, 2),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE orders (
    id        SERIAL PRIMARY KEY,
    user_id   INTEGER NOT NULL REFERENCES users(id),
    amount    NUMERIC(12, 2) NOT NULL,
    placed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE audit_log (
    id      SERIAL PRIMARY KEY,
    message TEXT NOT NULL
);
-- Trigger für Test 7
CREATE OR REPLACE FUNCTION log_user_insert() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO audit_log (message) VALUES ('inserted ' || NEW.name);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_user_insert AFTER INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION log_user_insert();
```

Die `updated_at`-Spalte wird für Schritt 32 (inkrementeller Round-Trip) benötigt.
Die `audit_log`-Tabelle + Trigger für Test 7 (`--trigger-mode disable`).

**Tests:**

1. **JSON Round-Trip**: Export aus PG → JSON-Datei → Import in geleerte Tabelle →
   SELECT-Vergleich (inkl. NULL, NUMERIC-Precision, BOOLEAN)
2. **YAML Round-Trip**: Analog mit YAML-Format
3. **CSV Round-Trip**: Analog mit CSV-Format
4. **Sequence-Reseeding**: Nach Import prüfen, dass `nextval('users_id_seq')`
   den korrekten nächsten Wert liefert (MAX(id) + 1)
5. **`--truncate`**: Tabelle mit Seed-Daten befüllen, Import mit `--truncate`,
   prüfen dass alte Daten weg und Sequence korrekt ist
6. **`--on-conflict update`**: Tabelle mit abweichenden Werten befüllen,
   Import mit `--on-conflict update`, prüfen dass bestehende Rows aktualisiert
   und neue eingefügt wurden
7. **`--trigger-mode disable`**: Import mit `--trigger-mode disable`,
   prüfen dass `audit_log` leer bleibt (Trigger nicht gefeuert); Import ohne
   `--trigger-mode disable` als Gegenprobe (Trigger feuert, `audit_log` befüllt)
8. **FK-Reihenfolge**: Import von `orders` + `users` aus Directory mit `--schema`
   (topologische Sortierung), prüfen dass FK-Constraints erfüllt sind
9. **H4/M-R7 `failedFinish`-Pflichtfall** (Ref: `implementation-plan-0.4.0.md` §4 Schritt 29):
   `enableTriggers(...)` gezielt scheitern lassen und asserten, dass
   `TableImportSummary.failedFinish` die strukturierten Cause-Felder
   (`adjustments`, `causeMessage`, `causeClass`) trägt. CLI-stderr MUSS den
   `PARTIAL FAILURE`-Block enthalten. Umsetzung: Entweder über einen
   manipulierten PG-State (z.B. Tabelle zwischen Trigger-Disable und -Enable
   droppen) oder über einen Test-Subclass-Writer. Wenn der Aufwand für die
   gezielte Exception-Provokation im E2E-Setup unverhältnismäßig ist, wird
   dieser Fall über einen bestehenden Unit-Test im Driver-Modul abgedeckt und
   hier als bewusst ausgeklammert dokumentiert.

### Schritt 30: `DataImportE2EMysqlTest`

Testklasse mit `tags(IntegrationTag)` und `MySQLContainer("mysql:8.0")`.

**Schema-Setup:**
```sql
CREATE TABLE users (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255),
    active     TINYINT(1) NOT NULL DEFAULT 1,
    score      DECIMAL(10, 2),
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE orders (
    id        INT AUTO_INCREMENT PRIMARY KEY,
    user_id   INT NOT NULL,
    amount    DECIMAL(12, 2) NOT NULL,
    placed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
```

**Tests:**

1. **JSON Round-Trip**: Export → Import → SELECT-Vergleich
2. **AUTO_INCREMENT-Reseeding**: Nach Import prüfen, dass nächster
   AUTO_INCREMENT-Wert = MAX(id) + 1 (via `SHOW TABLE STATUS`)
3. **`--truncate`**: Analog zu PG
4. **`--on-conflict update`**: Analog zu PG
5. **`--trigger-mode disable` → Exit 2**: MySQL unterstützt kein Trigger-Disable,
   CLI muss mit Exit 2 abbrechen
6. **`--disable-fk-checks`**: Import von `orders` vor `users` (Child-vor-Parent)
   mit `--disable-fk-checks`, prüfen dass Import erfolgreich und FK-Checks
   danach wieder aktiv sind (via `SELECT @@foreign_key_checks`)

### Schritt 31: SQLite-E2E (bereits abgedeckt)

Durch `CliDataImportTest` in E-28 vollständig abgedeckt. Kein weiterer Handlungsbedarf.

### Schritt 32: Inkrementeller Round-Trip-E2E

In `DataImportE2EPostgresTest` als eigener Test (nutzt die `updated_at`-Spalte
aus dem Schema-Setup und die PG-Timestamp-Präzision):

1. Seed: 3 Rows in `users` mit `updated_at = '2026-01-01 00:00:00'`
2. Initial-Export aller Rows → JSON-Datei
3. Import in zweite Tabelle `users_target` (gleiche DB, gleiches Schema)
4. Timestamp merken: `checkpoint = NOW()`
5. Quell-Tabelle ändern: Row id=1 aktualisieren (`name`, `updated_at = NOW()`),
   neue Row id=4 einfügen (ebenfalls `updated_at = NOW()`)
6. Delta-Export: `--since-column updated_at --since "<checkpoint>"`
7. Delta-Import in `users_target` mit `--on-conflict update`
8. SELECT-Vergleich `users_target`:
   - id=1: aktualisierter Name
   - id=2, id=3: unverändert (aus Initial-Import)
   - id=4: neu eingefügt

### Schritt 33: CI-Workflow (bereits abgedeckt)

`integration.yml` läuft generisch über Tag-Filter. Import-Tests werden automatisch
erfasst, sobald sie `tags(IntegrationTag)` tragen. Kein Handlungsbedarf.

## 7. Akzeptanzkriterien

- [x] `scripts/test-integration-docker.sh` läuft erfolgreich mit den neuen Import-Tests.
- [x] PostgreSQL-Import mit JSON Round-Trip verifiziert.
- [x] Sequence-Reseeding nach Import auf PostgreSQL getestet (`nextval` liefert MAX+1).
- [x] AUTO_INCREMENT-Reseeding nach Import auf MySQL getestet.
- [x] `--truncate` auf PostgreSQL und MySQL funktional getestet.
- [x] `--on-conflict update` (UPSERT) auf PostgreSQL und MySQL getestet.
- [x] `--trigger-mode disable` auf PostgreSQL getestet (Trigger wird nicht gefeuert).
- [x] `--trigger-mode disable` auf MySQL liefert Exit 2.
- [x] Inkrementeller Round-Trip (initial → delta → UPSERT) mindestens einmal E2E verifiziert.
- [x] H4/M-R7 `failedFinish`-Pfad: bewusst als Unit-Test-only dokumentiert (E2E-Provokation unverhältnismäßig, Driver-Tests decken den Pfad ab).
- [x] Truncate-Implementierung in PostgreSQL- und MySQL-Writer nachgerüstet.
- [x] `docker build -t d-migrate:dev .` baut weiterhin erfolgreich (Nicht-Integration-Tests).

## 8. Verifikation

1. Gezielter Integrationstestlauf:
```bash
./scripts/test-integration-docker.sh \
  -PintegrationTests :adapters:driving:cli:test
```
Ergebnis: 311 Tests, 0 Fehler, BUILD SUCCESSFUL (inkl. bestehender Export-E2E-Tests).

2. Vollständiger Build (ohne Integrationstests):
```bash
docker build -t d-migrate:dev .
```
Ergebnis: BUILD SUCCESSFUL, alle Coverage-Gates bestanden.

3. Bestehende Export-E2E-Tests: nicht gebrochen (in den 311 Tests enthalten).

## 9. Review-Findings (2026-04-12)

- **Fix**: PG TRUNCATE fehlte `autoCommit=true`-Guard (behoben in `e75a0bc`).
- **Dokumentiert**: CASCADE truncatiert FK-referenzierende Tabellen stillschweigend.
- **Follow-up**: `--on-conflict skip` E2E-Test fehlt für beide DBs.
- **Follow-up**: Inkrementeller Round-Trip-Test für MySQL fehlt (nur PG).
- **Known**: `--truncate --no-reseed-sequences` divergiert zwischen PG und MySQL.
