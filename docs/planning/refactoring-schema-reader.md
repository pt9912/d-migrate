# Refactoring: SchemaReader Shared Utilities

> **Status**: Umgesetzt (Shared Utilities)
> **Ursprung**: Technische Schuld aus Phase D
> **Vorbild**: `AbstractJdbcDataReader` in `driver-common`

---

## Problem

Die drei `SchemaReader`-Implementierungen (`PostgresSchemaReader`,
`MysqlSchemaReader`, `SqliteSchemaReader`) enthielten duplizierte
Orchestrierungslogik:

- `toReferentialActionOrNull()` — 3x identisch (7 Zeilen)
- Single-Column FK/UNIQUE Lifting — 3x strukturell identisch
- Multi-Column FK/UNIQUE/CHECK Constraint-Aufbau — 3x identisch

## Evaluierte Ansaetze

### AbstractJdbcSchemaReader (verworfen)

Ein gemeinsamer abstrakter Basistyp nach dem Vorbild von
`AbstractJdbcDataReader` wurde evaluiert, aber wegen fundamentaler
Scope-Unterschiede zwischen den Dialekten verworfen:

| Dialekt    | Scope-Modell                                    |
|------------|------------------------------------------------|
| PostgreSQL | 1 Variable (`schema: String`)                   |
| MySQL      | 2 Variablen (`database` + `lctn: Int`) mit Normalisierung |
| SQLite     | Kein Scope (hardcoded `"main"`)                 |

Weitere Blocker:
- `readTable()` hat inkompatible Signaturen (MySQL: Dual-Identifier,
  SQLite: CREATE-SQL-Parameter)
- Dialektspezifische Bloecke mitten in der "gemeinsamen" Orchestrierung
  (MySQL FK-Backing-Index-Filterung, SQLite Virtual-Table-Erkennung)
- Kover-Verbesserung ueberschaetzt: Die Basisklasse waere genauso nur
  mit echter DB testbar

### Shared Utilities (umgesetzt)

Statt Vererbung wurden gezielte Utility-Funktionen in
`driver-common/.../metadata/SchemaReaderUtils.kt` extrahiert:

```kotlin
object SchemaReaderUtils {
    fun toReferentialAction(action: String?): ReferentialAction?
    fun liftSingleColumnFks(fks: List<ForeignKeyProjection>): Map<String, ReferenceDefinition>
    fun buildMultiColumnFkConstraints(fks: List<ForeignKeyProjection>): List<ConstraintDefinition>
    fun buildMultiColumnUniqueFromConstraints(constraints: Map<String, List<String>>): List<ConstraintDefinition>
    fun buildMultiColumnUniqueFromIndices(indices: List<IndexProjection>): List<ConstraintDefinition>
    fun buildCheckConstraints(checks: List<ConstraintProjection>): List<ConstraintDefinition>
    fun singleColumnUniqueFromIndices(indices: List<IndexProjection>): Set<String>
    fun singleColumnUniqueFromConstraints(constraints: Map<String, List<String>>): Set<String>
}
```

---

## Ergebnis

| Aspekt | Vorher | Nachher |
|--------|--------|---------|
| `toReferentialActionOrNull()` | 3x dupliziert | 1x in SchemaReaderUtils |
| FK/UNIQUE Lifting | 3x ~5 Zeilen | 1x als reine Funktion, unit-getestet |
| Constraint-Aufbau | 3x ~20 Zeilen | 1x pro Variante, unit-getestet |
| Reader bleiben eigenstaendig | ja | ja (kein Vererbungs-Lock-in) |
| Unit-Testbarkeit der Utilities | nicht testbar | 100% (SchemaReaderUtilsTest) |

## Betroffene Dateien

| Datei | Aenderung |
|-------|-----------|
| `driver-common/.../metadata/SchemaReaderUtils.kt` | Neu |
| `driver-common/.../metadata/SchemaReaderUtilsTest.kt` | Neu |
| `driver-postgresql/.../PostgresSchemaReader.kt` | Nutzt SchemaReaderUtils |
| `driver-mysql/.../MysqlSchemaReader.kt` | Nutzt SchemaReaderUtils |
| `driver-sqlite/.../SqliteSchemaReader.kt` | Nutzt SchemaReaderUtils |
