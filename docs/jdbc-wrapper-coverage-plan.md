# Plan: JDBC-Wrapper und MockK-Tests fuer Kover 90%

> **Status**: Review (2026-04-18) — Paket 1 + 4-Zwischenstand umgesetzt
> **Kontext**: 0.9.1 Phase A Review-Finding — Kover-Ausschluesse muessen
> reduziert und die verbleibenden durch MockK-Unit-Tests ersetzt werden.

---

## 1. Ist-Zustand

### Was bereits umgesetzt ist (WIP-Commit `4a3913d`)

- `JdbcOperations`-Interface in `driver-common` mit `queryList`,
  `querySingle`, `execute`, `executeBatch`
- `JdbcMetadataSession` implementiert `JdbcOperations`
- 6 Profiling/Introspection-Adapter auf `JdbcOperations` umgestellt
- MockK als Test-Dependency
- MockK-Unit-Tests fuer PG/MySQL Profiling/Introspection (4 Testdateien)
- LogicalTypeResolver-Unit-Tests (PG + MySQL)
- Integration-Tests in eigene Module verschoben
  (`test/integration-postgresql`, `test/integration-mysql`)

### Klassen-Audit pro Dialekt

Jede Klasse faellt in eine von 3 Kategorien:

| Kategorie | Beschreibung | Aktion |
|-----------|-------------|--------|
| **A: Nutzt JdbcOperations** | `queryList`/`querySingle` via Session | MockK-Unit-Test schreiben → Ausschluss entfernen |
| **B: Raw JDBC** | `createStatement`/`prepareStatement` direkt | Auf JdbcOperations umstellen → MockK-Test → Ausschluss entfernen |
| **C: Kein JDBC** | Reine Logik (TypeMapper, DdlGenerator) | Bereits unit-getestet, kein Ausschluss |

**PostgreSQL (15 Quelldateien):**

| Klasse | LOC | Kategorie | JdbcOps | Raw JDBC | Status |
|--------|-----|-----------|---------|----------|--------|
| DdlGenerator | 551 | C | 0 | 0 | Unit-Test vorhanden |
| TypeMapper | 68 | C | 0 | 0 | Unit-Test vorhanden |
| TypeMapping | 196 | C | 0 | 0 | Unit-Test vorhanden |
| JdbcUrlBuilder | 34 | C | 0 | 0 | Unit-Test vorhanden |
| Identifiers | 40 | C | 0 | 0 | Indirekt getestet |
| LogicalTypeResolver | 23 | C | 0 | 0 | Unit-Test vorhanden (neu) |
| ProfilingDataAdapter | 118 | A | ✓ | 0 | MockK-Test vorhanden (neu) |
| SchemaIntrospectionAdapter | 47 | A | ✓ | 0 | MockK-Test vorhanden (neu) |
| MetadataQueries | 354 | A | 19 | 0 | **MockK-Test fehlt** |
| SchemaReader | 394 | A | 12 | 0 | **MockK-Test fehlt** |
| TableLister | 26 | A | 2 | 0 | **MockK-Test fehlt** |
| DataReader | 34 | B* | 0 | 0 | Erbt von AbstractJdbcDataReader |
| DataWriter | 547 | B | 0 | 4 | **Umstellung + MockK-Test noetig** |
| SchemaSync | 142 | B | 0 | 6 | **Umstellung + MockK-Test noetig** |
| TableImportSession | 195 | B | 0 | 8 | **Umstellung + MockK-Test noetig** |
| Driver | 26 | — | 0 | 0 | Registry-Facade; instantiiert JDBC-Klassen, daher nicht isoliert testbar. Ausschluss bleibt (26 LOC). |

*DataReader: die konkreten Dialekt-Klassen (34 LOC) setzen nur `dialect`,
`quoteIdentifier` und `fetchSize`. Die eigentliche JDBC-Logik steckt in
`AbstractJdbcDataReader` (driver-common, ~130 LOC), der eigene Unit-Tests
hat (`AbstractJdbcDataReaderTest.kt`). Separate Behandlung.

**MySQL**: identische Verteilung (15 Quelldateien, gleiche Kategorien).

**SQLite** (12 Quelldateien): aehnliche Verteilung, aber Tests laufen
in-memory (kein Testcontainer). Die bestehenden In-Memory-Tests in
`SqliteProfilingTest`, `SqliteSchemaReaderTest` etc. decken die
JdbcOperations-basierten Klassen bereits ab — fuer SQLite sind keine
zusaetzlichen MockK-Tests noetig. Kover hat nur `SqliteSchemaReader`
ausgeschlossen; dieser Ausschluss bleibt, da der SchemaReader-Test
bereits In-Memory-Coverage liefert und die restlichen Branches
exotische Schemas erfordern.

---

## 2. Arbeitspakete

### Paket 1: MockK-Tests fuer Kategorie-A-Klassen (JdbcOperations bereits vorhanden)

Betroffene Klassen pro Dialekt:
- `MetadataQueries` (354/197/112 LOC)
- `SchemaReader` (394/294/181 LOC)
- `TableLister` (26/26/25 LOC)

**MetadataQueries**: Ist ein `object` dessen Funktionen eine
`JdbcMetadataSession` bereits als Parameter annehmen
(`fun listTables(session: JdbcMetadataSession, ...)`). Das Testseam
ist schon vorhanden — kein Constructor-Umbau noetig. Fuer Unit-Tests
wird der Parametertyp von `JdbcMetadataSession` auf `JdbcOperations`
geweitet; MockK-Tests mocken dann das Interface direkt. In-Memory-
SQLite ist kein valider Ersatz, da die Queries dialektspezifisch
gegen `information_schema` (PG/MySQL) bzw. `pg_catalog` arbeiten.

**SchemaReader und TableLister**: Erzeugen intern
`JdbcMetadataSession(conn)`. Fuer MockK-Tests brauchen diese einen
`jdbcFactory`-Constructor-Parameter (gleicher Ansatz wie bei den
Profiling-Adaptern).

**Dateien**: PG und MySQL: je 2 Quelldateien (SchemaReader,
TableLister Constructor) + 3 neue Testdateien. MetadataQueries:
ggf. Parametertyp weiten oder In-Memory-Test nutzen. SQLite:
bestehende In-Memory-Tests decken die Klassen bereits ab.
Gesamt: ~12 PG/MySQL-Dateien + ggf. 3 SQLite-Quelldateien.

### Paket 2: Kategorie-B-Klassen — bestehende Tests erweitern oder JdbcOperations einfuehren

Betroffene Klassen pro Dialekt:
- `DataWriter` (547/525/544 LOC) — `execute` + `executeBatch`
- `SchemaSync` (142/127/149 LOC) — `execute` + `queryList`
- `TableImportSession` (195 LOC) — `execute` + `executeBatch`

**Bereits vorhandene Unit-Tests**: Fuer Teile dieser Klassen existieren
bereits direkte Unit-Tests ohne JDBC-Abstraktion:
- `PostgresTableImportSessionTest` (PG)
- `MysqlTableImportSessionTest` (MySQL)
- `SqliteDataWriterTest`, `SqliteSchemaSyncTest`,
  `SqliteTableImportSessionTest` (SQLite, in-memory)

Vor einer breiten JdbcOperations-Umstellung muss geprueft werden, welche
Coverage-Luecken durch gezielte Erweiterung dieser bestehenden Tests
geschlossen werden koennen — ohne Produktions-Code-Umbau. Nur wenn
einzelne Pfade nicht ohne JDBC-Abstraktion testbar sind, wird die
Umstellung auf `JdbcOperations` fuer die betroffene Klasse durchgefuehrt.

**Vorgehen**: Coverage-Report pro Klasse auswerten → fehlende Branches
identifizieren → bestehende Tests erweitern → nur bei Bedarf auf
JdbcOperations umstellen.

### Paket 3: AbstractJdbcDataReader

Der DataReader steckt in `driver-common/AbstractJdbcDataReader` und nutzt
`fetchSize`-basierten Cursor + ResultSet-Streaming. Optionen:

a) `JdbcOperations` um `queryStream(sql, params, fetchSize): Sequence<Map>`
   erweitern
b) DataReader bleibt ausgeschlossen (34 LOC pro Dialekt, Logik in
   Abstract-Klasse)

Empfehlung: **(b)** — die Dialekt-Klassen haben nur 34 LOC
(setzen `dialect`, `quoteIdentifier`, `fetchSize`). Der Ausschluss ist
minimal und ehrlich. `AbstractJdbcDataReader` hat eigene Tests.

### Paket 4: Kover-Ausschluesse entfernen

Nach Paket 1+2 bleiben nur noch ausgeschlossen:
- `*DataReader` (34 LOC, thin wrapper)
- `*Driver` (26 LOC, thin facade)

Das sind ~60 LOC pro Dialekt — weniger als 5% des Moduls.

---

## 3. Priorisierung

| Paket | Aufwand | Effekt auf Ausschluesse |
|-------|---------|------------------------|
| 1 (MockK fuer A-Klassen) | M | -3 Klassen/Dialekt (MetadataQueries, SchemaReader, TableLister) |
| 2 (B-Klassen umstellen) | L | -3 Klassen/Dialekt (DataWriter, SchemaSync, TableImportSession) |
| 3 (DataReader) | S | Empfehlung: ausgeschlossen lassen (34 LOC) |
| 4 (Kover bereinigen) | S | Finale Konfiguration |

**Empfohlene Reihenfolge**: 1 → 4-Zwischenstand → 2 → 4-Final

---

## 4. Ziel-Kover-Konfiguration (nach Abschluss)

```kotlin
// driver-postgresql/build.gradle.kts
kover {
    reports {
        filters {
            excludes {
                classes(
                    // Thin wrappers (< 35 LOC each, no logic):
                    "dev.dmigrate.driver.postgresql.PostgresDataReader",
                    "dev.dmigrate.driver.postgresql.PostgresDriver",
                )
            }
        }
        verify { rule { minBound(90) } }
    }
}
```

Nur noch 2 Klassen mit zusammen ~60 LOC ausgeschlossen (~5% des Moduls).

---

## 5. Verifikation

- `docker build -t d-migrate:dev .` — alle Unit-Tests gruen, Kover 90%
  in PG- und MySQL-Driver-Modulen
- `./scripts/test-integration-docker.sh` — Integrationstests gruen,
  Root-`:koverVerify` 90% (alle Klassen inkl. JDBC)
- Kein bedingter `minBound` (`if integrationTests 90 else 40/45`) mehr
  in den Driver-Modulen — `minBound(90)` ist fest
- `hasProperty("integrationTests")` in Root-`build.gradle.kts` bleibt
  unveraendert (steuert Tag-Filtering und Heap-Konfiguration, nicht
  Coverage-Grenzen)
- Maximal 2 Ausschluesse pro Driver-Modul (DataReader + Driver)
