# Implementierungsplan: 0.9.6 — AP E0.7 `Driver-Adapter-Timeout-Konfiguration`

> **Milestone**: 0.9.6 — Beta: MCP-Server
> **Phase**: E0.7 (Pre-Phase-E-Auflösung des E0-Gate-`Blocked`)
> **Status**: Draft (2026-05-05)
> **Referenzen**:
> - `docs/planning/in-progress/ImpPlan-0.9.6-E0.md` §4.1, §4.2, §6, §7.6, §9, §10
> - `docs/planning/in-progress/ImpPlan-0.9.6-E0-Side-Effect-Matrix.md` §6
> - `docs/planning/in-progress/ImpPlan-0.9.6-E0-Gate-Decision.md` §3
> - `spec/job-contract.md` (Exit-Code 130, Cancel-Reaktionsbudget)
> - `adapters/driven/driver-common/.../HikariConnectionPoolFactory.kt`
> - `adapters/driven/driver-postgresql/`, `driver-mysql/`, `driver-sqlite/`

---

## 1. Ziel

AP E0.7 löst das `Blocked`-Verdict aus E0.6 auf. Nach E0.7 ist für jeden
monolithischen Driver-Call ein **belegtes Timeout-/Laufzeitfenster
`<= 30s`** und ein gemessenes E0-Cancel-Reaktionsbudget vorhanden, ohne
eine Port-Vertrag-Änderung in `hexagon:ports-read` oder `ports-write`
einzuführen.

Konkret liefert E0.7:

- pro Driver-Adapter (postgresql, mysql, sqlite) eine zentrale
  Statement-/Connection-Timeout-Konfiguration mit Default `30000ms`,
  konfigurierbar pro `ConnectionConfig`/`PoolSettings`
- pro Driver einen Bench-Test, der zeigt: eine bewusst lange Operation
  (ServerSleep/LangerScan) wird nach `<= 30s` mit `SQLTimeoutException`
  oder `SQLException` (driver-spezifisch) abgebrochen, ohne Retry-Loop
  und ohne offene Connection
- ein Side-Effect-Matrix-Update, das alle `blockierend`-Zeilen aus
  Section 6 nach `atomic-nicht-cancelbar` mit `bound = 30000ms`,
  `cancel_budget_ms = 30000`, `measurement_evidence = <Bench-Test-Name>`
  und `gate = go` hebt
- ein neues E0.6-Gate-Verdict-Stempel: `Blocked → Go`

Bewusst nicht Teil von E0.7:

- in-flight-Statement-Cancel über `Statement.cancel()` aus separatem
  Thread (würde Worker-Handle-Registry vorausnehmen — Phase E)
- `abort()`-API auf `TableImportSession` (nicht nötig, siehe Gate-
  Decision §2.4)
- Token-Param am Reader-/Writer-Iterator-Rand (würde Port-Vertrag
  ändern; nicht E0.7-Scope)
- Statement-Timeout-Konfiguration für DDL-Tools wie
  `schema_generate`/`tools_export` außerhalb der vier E0-Runner

---

## 2. Ausgangslage

### 2.1 Heutiger Zustand

`adapters/driven/driver-common/.../HikariConnectionPoolFactory.kt`
konfiguriert HikariCP mit Pool-Acquire-Timeout, Idle-Timeout und
Max-Lifetime — aber keine Per-Statement- oder Per-Connection-Query-
Timeout. Die `PoolSettings`-Datenstruktur trägt diese Felder nicht.

Driver-Adapter (`driver-postgresql`, `driver-mysql`, `driver-sqlite`)
erzeugen Statements und PreparedStatements direkt aus
`pool.borrow().prepareStatement(...)` ohne `setQueryTimeout(...)`.

### 2.2 Driver-spezifische Fakten

#### PostgreSQL
- Server-side: `SET statement_timeout = '30000'` (in ms) gilt
  per-Connection für **alle** SQL-Statements (SELECT, INSERT, UPDATE,
  DDL).
- HikariCP-Hook: `connectionInitSql = "SET statement_timeout = 30000"`.
- Driver-Verhalten: nach Timeout wirft das Statement
  `org.postgresql.util.PSQLException` mit SQLState `57014`
  (`query_canceled`). Connection bleibt nutzbar, kein Connection-Leak.

#### MySQL
- Server-side: `SET SESSION MAX_EXECUTION_TIME = 30000` gilt **nur
  für SELECTs**, nicht für INSERT/UPDATE/DDL (MySQL-Quirk).
- Für Write-Statements: JDBC `Statement.setQueryTimeout(30)` —
  MySQL-JDBC-Treiber implementiert das durch
  `Connection.setNetworkTimeout` plus Server-Cancel-Mechanismus.
- HikariCP-Hook für SELECTs: `connectionInitSql = "SET SESSION
  MAX_EXECUTION_TIME = 30000"`.
- Für Writes: zentrale `Connection.createStatement()`-Wrapper-Helper
  in `driver-mysql`, der `setQueryTimeout(30)` setzt.
- Driver-Verhalten: Timeout wirft `SQLTimeoutException` (oder
  `MySQLTransactionRollbackException` für Lock-Wait).

#### SQLite
- Kein `statement_timeout`-Pragma. Für Lock-Wait: `PRAGMA
  busy_timeout = 30000` (Default 0).
- Für lange Queries (Range-Scan ohne Index): `Statement.setQueryTimeout(30)`
  im SQLite-JDBC-Treiber (xerial) implementiert mittels Worker-Thread,
  der `sqlite3_interrupt(...)` aus C-Library aufruft.
- HikariCP-Hook: `connectionInitSql = "PRAGMA busy_timeout = 30000"`.
- Für Statement-Level: per-Statement `setQueryTimeout(30)`.

### 2.3 Konsequenz für die Implementierung

Eine reine HikariCP-`connectionInitSql`-Konfiguration reicht **nicht**
für alle drei Driver einheitlich. Wir brauchen:

1. HikariCP-`connectionInitSql` pro Driver (kümmert sich um SELECT-
   und Lock-Wait-Timeouts)
2. einen zentralen Hook in der gemeinsamen Statement-Erzeugung pro
   Driver, der `setQueryTimeout(...)` setzt — für Write/DDL-Pfade

---

## 3. Scope

### 3.1 In Scope

- Erweiterung von `PoolSettings` um `statementTimeoutMs: Int = 30000`
  (Default = 30s nach Plan §4.1).
- Erweiterung von `HikariConnectionPoolFactory` um driver-spezifischen
  `connectionInitSql`-Build aus `statementTimeoutMs`.
- Driver-Adapter-Erweiterung: pro Driver eine zentrale
  `prepareStatementWithTimeout`-Helper-Funktion oder ein Statement-
  Decorator-Layer, das `setQueryTimeout(statementTimeoutMs / 1000)`
  setzt (JDBC nimmt Sekunden).
- Bench-Test pro Driver in `test/integration-postgresql`,
  `test/integration-mysql` und einem neuen Test-Spec im
  `driver-sqlite`-Modul (SQLite braucht keinen Testcontainer, hat
  in-memory-File-DB).
- `connection-config-spec.md`-Update um `statementTimeoutMs`-Feld.
- Side-Effect-Matrix Section 6 + Section 7 + Changelog Section 8 update.
- Gate-Decision-Doc §1 + §3 re-stempeln.

### 3.2 Bewusst nicht in Scope

- Phase-E `job_cancel`-Tool (Hauptplan §8 Phase E).
- Worker-Handle-Registry und thread-basierte `Statement.cancel()`-
  Propagation (Phase E §4.1).
- Format-Reader (DataChunkReader) Timeouts — bleiben `atomic` mit
  lokalem-FS-bound (Gate-Decision §2.4).
- Connection-Init-SQL für Profiling-spezifische Settings (war nicht
  Teil des E0-Gates).
- Token-Param am Port-Vertrag — bleibt explizit ausgeschlossen
  (Plan §7.6).

---

## 4. Leitentscheidungen

### 4.1 Default `30000ms` aus Plan §4.1

`statementTimeoutMs` defaultet auf `30000`. Plan §4.1 verlangt
`<=30s`-Cancel-Reaktionsbudget; Default = obere Schranke. Tests verifi-
zieren mit kürzeren Werten (z.B. `5000ms`) für CI-Geschwindigkeit.

### 4.2 Konfigurierbar pro `ConnectionConfig`

`PoolSettings.statementTimeoutMs` ist konfigurierbar — kein Hard-Code.
Begründung: Test-Scenarien können kürzere Timeouts setzen, produktive
Bulk-Imports können den Default-30s überschreiben (Plan §4.1 erlaubt
das, sofern dokumentiert und für die jeweilige Operation begründet).

Ein Wert von `0` deaktiviert das Timeout (für Tests, die genau das
prüfen). Ein negativer Wert ist ein Validierungsfehler in
`ConnectionConfig.validate()`.

### 4.3 HikariCP-Init-SQL für Server-Side, Statement-Level für Write/DDL

Pro Driver:

| Driver | HikariCP `connectionInitSql` | Statement-Level |
| --- | --- | --- |
| PostgreSQL | `SET statement_timeout = ${ms}` | nicht zusätzlich nötig (server enforced für alle Statements) |
| MySQL | `SET SESSION MAX_EXECUTION_TIME = ${ms}` (nur SELECTs) | `setQueryTimeout(s)` zentral in `MysqlPreparedStatementSupport`-Helper für Write/DDL-Pfade |
| SQLite | `PRAGMA busy_timeout = ${ms}` (Lock-Wait) | `setQueryTimeout(s)` zentral in `SqlitePreparedStatementSupport` für lange Queries |

### 4.4 Cleanup nach Timeout-Throw

Plan §4.1 fordert: "nach Timeout oder Fehler startet der Runner keinen
weiteren Side Effect, der Call hält keine offene Transaktion, Session,
Sperre oder temporäre Ressource".

Dies wird durch die bestehende `pool.use {}`/`session.use {}`-Cleanup-
Pattern und die `closeAndCollect(...)`-Suppressed-Exception-Logik in
`TableImporter.import` (E0.5) abgedeckt. E0.7 fügt keine neue Cleanup-
Semantik hinzu.

### 4.5 Bench-Tests sind Integration-Tests (Testcontainers)

PostgreSQL- und MySQL-Bench-Tests laufen unter
`test/integration-postgresql` und `test/integration-mysql` (bereits
existierende Module mit Testcontainers + Default-CI-Skip). SQLite-Bench-
Test läuft im `driver-sqlite`-Modul-Test (in-memory).

CI-Aktivierung: `make integration` oder
`./gradlew test -PintegrationTests` (siehe Hauptbuild §6.16).

---

## 5. Umsetzungsschritte

### 5.1 AP E0.7.1: `PoolSettings.statementTimeoutMs` ergänzen

- `dev.dmigrate.driver.connection.PoolSettings` um
  `statementTimeoutMs: Int = 30000`.
- `ConnectionConfig.validate()` rejected `statementTimeoutMs < 0`.
- `connection-config-spec.md` (`spec/`) dokumentiert Feld + Default.
- Bestehende Connection-Config-Tests bleiben grün (Default).

### 5.2 AP E0.7.2: HikariCP-`connectionInitSql` pro Driver

- `HikariConnectionPoolFactory.create(config)` ruft eine neue
  `connectionInitSqlFor(dialect, statementTimeoutMs)`-Helper-Funktion
  auf und setzt `hikariConfig.connectionInitSql`.
- Driver-spezifische Helper:
  - PostgreSQL: `"SET statement_timeout = $ms"`
  - MySQL: `"SET SESSION MAX_EXECUTION_TIME = $ms"`
  - SQLite: `"PRAGMA busy_timeout = $ms"`
- Für `statementTimeoutMs = 0`: kein `connectionInitSql` (Driver-
  Default).

### 5.3 AP E0.7.3: Statement-Level-Timeout für Writes/DDL

- `driver-postgresql`: keine zusätzliche Aktion (server enforced).
- `driver-mysql`: zentrale `MysqlStatementSupport.setTimeoutOn(stmt,
  config)`-Funktion in einem neuen oder bestehenden Helper-File;
  alle `prepareStatement(...)`-Stellen in `MysqlDataReader`,
  `MysqlDataWriter`, `MysqlSchemaReader` rufen sie auf.
- `driver-sqlite`: gleiche Pattern in `SqliteStatementSupport`.

### 5.4 AP E0.7.4: Bench-Test pro Driver

- `test/integration-postgresql/.../E07PostgresTimeoutBench.kt`:
  Testcontainer + `SELECT pg_sleep(60)` + `setQueryTimeout(5)` →
  erwartet `PSQLException` SQLState `57014` innerhalb `< 6s`.
- `test/integration-mysql/.../E07MysqlTimeoutBench.kt`:
  Testcontainer + `SELECT SLEEP(60)` + `MAX_EXECUTION_TIME = 5000` →
  erwartet `SQLTimeoutException` innerhalb `< 6s`.
- `adapters/driven/driver-sqlite/.../E07SqliteTimeoutBench.kt`:
  in-memory DB + langer Loop-Query oder Lock-Wait + `busy_timeout =
  5000` → erwartet `SQLException` mit SQLite-Lock-Code innerhalb `<
  6s`.
- Tests sind `@Tag("integration")` und laufen nur unter
  `make integration` / `./gradlew test -PintegrationTests`.

### 5.5 AP E0.7.5: Side-Effect-Matrix + Gate-Decision-Update

- `ImpPlan-0.9.6-E0-Side-Effect-Matrix.md` Section 6: alle
  `blockierend`-Zeilen werden zu `atomic-nicht-cancelbar` mit
  konkreten Bench-Test-Referenzen.
- Section 7 Schnellstatistik: `blocked` → 0, `go` wächst entsprechend.
- Section 8 Changelog: E0.7-Eintrag.
- `ImpPlan-0.9.6-E0-Gate-Decision.md`: §1 Verdict re-stempelt von
  `Blocked` auf `Go`. §3 wird zu "Pflicht-Pre-Phase-E-Arbeit
  abgeschlossen". Neuer §6.1 dokumentiert Phase-E-Start-Freigabe.

### 5.6 AP E0.7.6: Move E0 + E0.7 nach `done/`

- `ImpPlan-0.9.6-E0.md` → `done/`
- `ImpPlan-0.9.6-E0-Side-Effect-Matrix.md` → `done/`
- `ImpPlan-0.9.6-E0-Gate-Decision.md` → `done/`
- `ImpPlan-0.9.6-E0.7.md` → `done/`
- Phase-E-Plan in `in-progress/` öffnen (eigenes AP).

---

## 6. Teststrategie

### 6.1 Unit-Tests (default-CI)

- `HikariConnectionPoolFactoryTest`-Erweiterung: Driver-
  spezifische `connectionInitSql`-Strings werden korrekt gebaut für
  `statementTimeoutMs = 30000`, `5000`, `0` (kein Init-SQL).
- `PoolSettingsTest`-Erweiterung: Validation rejected negative
  Werte; Default = `30000`.
- `MysqlStatementSupportTest`/`SqliteStatementSupportTest`:
  `setTimeoutOn(stmt, config)` ruft `setQueryTimeout(seconds)` mit
  `statementTimeoutMs / 1000` auf; bei `0` keine Anpassung.

### 6.2 Integration-Bench-Tests (`integration`-Tag)

Pflichtmuster pro Driver:

```kotlin
// Pseudo-Code
test("statement_timeout enforces <= ${timeout}s on long-running query") {
    val cfg = ConnectionConfig(..., pool = PoolSettings(statementTimeoutMs = 5000))
    val pool = HikariConnectionPoolFactory.create(cfg)
    pool.use { p ->
        val start = System.nanoTime()
        val ex = shouldThrow<SQLException> {
            p.borrow().use { conn ->
                conn.prepareStatement("SELECT pg_sleep(60)").executeQuery()
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        elapsedMs shouldBeLessThan 6_000  // <= timeout + 1s slack
        // Driver-spezifischer SQLState/Klasse-Check
    }
}
```

### 6.3 Cleanup-Verifikation

Pro Bench-Test zusätzlich:

- `pool.activeConnections()` nach Cancel-Throw == 0 (Connection
  zurückgegeben, kein Leak).
- ein nachfolgender `pool.borrow().use { ... healthy SQL }` läuft
  durch (Connection ist nicht permanent korrupt).

### 6.4 Default-Token-Regressionsguard

Ein Test pro Driver belegt: `statementTimeoutMs = 30000` (Default)
beeinflusst eine schnelle Healthy-Query (`SELECT 1`) nicht — kein
False-Positive.

---

## 7. Abnahmekriterien

- `PoolSettings.statementTimeoutMs` mit Default `30000`, Validation
  rejected `< 0`.
- `HikariConnectionPoolFactory` setzt driver-spezifischen
  `connectionInitSql` aus `statementTimeoutMs`.
- `MysqlStatementSupport.setTimeoutOn(stmt, config)` und
  `SqliteStatementSupport.setTimeoutOn(stmt, config)` exportieren
  einheitliche Helper-API.
- Alle Adapter-Stellen, die `prepareStatement(...)` für
  Reader/Writer/SchemaReader nutzen, rufen die Helper auf —
  oder die zugrundeliegende Hikari-Init-SQL deckt den Pfad bereits ab.
- Integration-Bench-Tests pro Driver (PostgreSQL, MySQL, SQLite)
  beweisen: `<= statementTimeoutMs + 1s`-Reaktion auf langsame
  Operation, mit Cleanup-Verifikation.
- Default-Token-Regressionsguard pro Driver beweist: Healthy-Query
  läuft normal.
- Side-Effect-Matrix Section 6: alle `blockierend`-Zeilen wechseln
  zu `atomic-nicht-cancelbar`/`go` mit konkreten Bench-Test-Referenzen
  in `measurement_evidence`.
- Gate-Decision-Doc §1 Verdict: `Go`. §3 dokumentiert E0.7-Abschluss.
- `make docker-check MODULES=":adapters:driven:driver-common
  :adapters:driven:driver-postgresql :adapters:driven:driver-mysql
  :adapters:driven:driver-sqlite"` grün, alle koverVerify-Gates
  ≥90 % gehalten.
- `make integration` (mit Docker für Testcontainers) grün für die
  drei neuen Bench-Tests.

---

## 8. Risiken und Gegenmassnahmen

### 8.1 MySQL-`MAX_EXECUTION_TIME` deckt nur SELECTs ab

**Risiko**: Lange Bulk-INSERTs könnten weiterhin unbounded laufen.
**Gegenmassnahme**: Statement-Level `setQueryTimeout(s)` in
`MysqlStatementSupport` für alle Write-Pfade. Bench-Test deckt sowohl
SELECT-`SLEEP(60)` als auch Bulk-INSERT-Timeout ab.

### 8.2 Driver-Quirks bei Timeout-Verhalten

**Risiko**: Einzelne JDBC-Driver werfen unterschiedliche Exception-
Klassen oder lassen Connections im inkonsistenten Zustand zurück.
**Gegenmassnahme**: Bench-Test verifiziert `pool.activeConnections() ==
0` nach Cancel und nachfolgende Healthy-Query — also empirisch
abgedeckt. Falls Driver-spezifische Quirks auftauchen, werden sie pro
Driver in einer eigenen Adapter-Helper-Funktion behandelt.

### 8.3 Connection-Init-SQL und Multi-Tenant-Future

**Risiko**: HikariCP `connectionInitSql` läuft pro Connection-Create.
Wenn multi-tenant-Connection-Sharing eingeführt wird, könnte die
Statement-Timeout-Konfiguration zwischen Tenants leaken.
**Gegenmassnahme**: 0.9.6 hat keine Multi-Tenant-Connection-Sharing.
Phase F oder spätere Iteration prüft. Out-of-scope für E0.7.

### 8.4 Bench-Tests sind langsam (Testcontainers)

**Risiko**: Jeder Bench-Test startet einen Docker-Container, ggf. 10–
30s. Drei Tests + CI-Last.
**Gegenmassnahme**: Tests sind `@Tag("integration")` und laufen nur
unter `make integration`. Default-5min-CI bleibt unbelastet.

---

## 9. Folgearbeiten nach E0.7

- Phase E (Hauptplan §8) startet sobald E0.7 abgeschlossen.
- Worker-Handle-Registry und thread-basierte `Statement.cancel()`-
  Propagation kann optional in Phase E ergänzt werden, falls
  Statement-Timeout in der Praxis zu grob ist.
- 0.9.7+: Token-Param am Reader-Iterator-Rand (Port-Vertrag-
  Erweiterung) für inter-Chunk-Cancel ohne Timeout-Abhängigkeit.
- 0.9.7+: Statement-Timeout für `schema_generate`/`tools_export`/
  Profiling-Adapter (waren nicht Teil von E0).
