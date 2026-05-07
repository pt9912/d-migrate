# Follow-up-Plan: BIGSERIAL, BigIdentifier und neutrale Identity-Breite

> Status: Done (2026-05-07)
>
> Kontext: Ausgangspunkt war, dass der PostgreSQL-Reverse-Pfad `bigserial`
> bewusst auf `NeutralType.BigInteger` mit Diagnose `R300` mappte. Aeltere
> Specs zeigen dagegen `NeutralType.BigIdentifier -> BIGSERIAL`. Dieser Plan
> klaert die Abweichung und beschreibt den Aenderungspfad.

---

## 1. Befund

### Ausgangsstand vor AP 3

Bisheriger Code:

- `NeutralType` enthaelt `Identifier(autoIncrement: Boolean)` und
  `BigInteger`, aber keinen `BigIdentifier`.
- `PostgresTypeMapper.toSql` erzeugt:
  - `Identifier(autoIncrement=true)` -> `SERIAL`
  - `BigInteger` -> `BIGINT`
- `PostgresTypeMapping.mapColumn` fuehrt `bigserial`/`bigint identity` auf
  `BigInteger` zurueck und ergaenzt eine `R300`-Note:
  `bigint auto-increment mapped to BigInteger (not Identifier) to preserve
  type width`.

Der umgesetzte 0.6.0-Plan begruendet das so:

- `serial` -> `Identifier(autoIncrement=true)` mit integer-Breite.
- `bigserial` -> `BigInteger` plus Auto-Increment-Erkennung via
  `pg_get_serial_sequence()`.
- `bigserial` bleibt `BigInteger`, damit Generate spaeter nicht auf
  `SERIAL` (= integer) kollabiert.
- Die Auto-Increment-Eigenschaft sollte ueber Sequence/default `nextval(...)`
  transportiert werden, nicht ueber `NeutralType.Identifier`.

Wichtig: Das ist die historische Planbegruendung. Vor AP 3 entschied der
PostgreSQL-Reverse-Pfad die Spaltenabbildung ueber
`information_schema.columns.is_identity` und `column_default`/`nextval(...)`.
Sequenzen wurden separat in `SchemaDefinition.sequences` gelesen, aber nicht
belastbar als Owned-Sequence-/Identity-Metadatum an die konkrete Spalte
gebunden. Genau diese fehlende Bindung war Teil der Luecke.

### Problem

Der Breitenverlust wurde verhindert, aber die Generator-Seite hat heute keine
strukturierte Information mehr, dass ein `BigInteger` aus einer
Auto-Increment-/Identity-Spalte stammt. Deshalb kann Forward-Generate aus
`BigInteger` allein nicht `BIGSERIAL` erzeugen, ohne normale `BIGINT`-Spalten
faelschlich zu auto-increment-Spalten zu machen.

Zusaetzlich besteht ein Sequence-Duplizierungsrisiko: Wenn ein spaeterer
Vertrag `BIGSERIAL` oder `GENERATED ... AS IDENTITY` aus Spaltenmetadaten
erzeugt, darf dieselbe implizite/owned Sequence nicht nochmal als eigenstaendige
`CREATE SEQUENCE` aus `SchemaDefinition.sequences` emittiert werden.

---

## 2. Veraltete oder widerspruechliche Dokumentstellen

### Mit AP 1 korrigierte Abweichungen zum Code

- `spec/architecture.md` §3.4 zeigte einen alten Beispiel-Mapper mit
  `NeutralType.BigIdentifier(autoIncrement=true)` und
  `BigIdentifier -> BIGSERIAL`.
- Dasselbe Beispiel war auch jenseits von `BigIdentifier` nicht mehr
  code-nah:
  - Interface heisst im Code `TypeMapper.toSql(...)` und
    `toDefaultSql(...)`; Reverse-Mapping liegt in den driver-spezifischen
    Mapping-Objekten, z. B. `PostgresTypeMapping`.
  - Das Beispiel verwendet alte Namen wie `NeutralType.Boolean`,
    `NeutralType.Float(precision=...)` und
    `DateTime(withTimezone=...)`; der Code verwendet `BooleanType`,
    `floatPrecision` und `timezone`.
- `spec/design.md` und `spec/neutral-model-spec.md` listeten
  `identifier` fuer PostgreSQL als `SERIAL / BIGSERIAL`. Das war zu grob,
  weil der aktuelle `identifier`-Typ nur `SERIAL` eindeutig erzeugt.
- `spec/ddl-generation-rules.md` enthielt ein neutrales PostGIS-Generate-
  Beispiel mit `BIGSERIAL`; dieses Beispiel wurde auf `SERIAL` korrigiert.

### Keine direkte Abweichung

- `spec/design-import-sequences-triggers.md` beschreibt Import in bestehende
  `SERIAL`/`BIGSERIAL`-Zielspalten und Sequence-Resync. Das ist ein
  Import-/Ziel-DB-Thema, nicht der neutrale Forward-Typvertrag.
- `docs/planning/done/implementation-plan-0.4.0.md` beschreibt
  Identity-/Sequence-Support fuer Import/Sync. Das widerspricht nicht
  zwingend dem fehlenden `BIGSERIAL`-Forward-Mapping.
- `docs/planning/done/change-request-spatial-types.md` enthaelt SQL-Beispiele
  mit `BIGSERIAL`. Diese Beispiele koennen fachlich korrekt sein, belegen aber
  nicht, dass der neutrale Generator `BIGSERIAL` bereits modellieren kann.
- `docs/planning/done/ImpPlan-0.6.0-D-claude.md` ist historisch konsistent mit
  dem aktuellen Code und sollte nicht rueckwirkend umgeschrieben werden.

---

## 3. Ziel

d-migrate soll 64-bit Auto-Increment-/Identity-Spalten verlustfrei roundtrips
koennen:

1. Reverse PostgreSQL `bigserial` / `bigint identity` erkennt Breite und
   Generation.
2. Das neutrale Modell transportiert diese Information explizit.
3. Forward PostgreSQL kann daraus deterministisch `BIGSERIAL` oder einen
   expliziten `BIGINT GENERATED ... AS IDENTITY`-Vertrag erzeugen.
4. Normale `BIGINT`-Spalten bleiben normale `BIGINT`-Spalten.

Nicht-Ziel:

- `NeutralType.BigInteger` pauschal auf `BIGSERIAL` mappen.

---

## 4. Entscheidung: separates Identity-/Generation-Metadatum

Option C ist verbindlich umzusetzen. Der neutrale Typ bleibt fuer die
Datenbreite verantwortlich; die Generierungsstrategie wird als eigenes
Spaltenmetadatum modelliert. Damit kann `BigInteger` weiter eindeutig `BIGINT`
bedeuten, waehrend `BigInteger + ColumnGeneration.Identity(...)` eine
64-bit-Identity-/Serial-Spalte beschreibt.

```kotlin
data class ColumnDefinition(
    val type: NeutralType,
    val required: Boolean = false,
    val unique: Boolean = false,
    val default: DefaultValue? = null,
    val references: ReferenceDefinition? = null,
    val generation: ColumnGeneration? = null,
)

sealed interface ColumnGeneration {
    data class Identity(
        val mode: IdentityMode = IdentityMode.BY_DEFAULT,
        val sequenceName: String? = null,
        val legacySerialSyntax: Boolean = false,
    ) : ColumnGeneration
}

enum class IdentityMode {
    ALWAYS,
    BY_DEFAULT,
}
```

PostgreSQL-Mapping:

- PostgreSQL `Identifier(autoIncrement=true)` bleibt fuer
  Rueckwaertskompatibilitaet der bestehende 32-bit-Serial-Vertrag.
- PostgreSQL `Integer + Identity(legacySerialSyntax=true)` -> `SERIAL`.
- PostgreSQL `Integer + Identity(legacySerialSyntax=false)` ->
  `INTEGER GENERATED {ALWAYS|BY DEFAULT} AS IDENTITY`.
- PostgreSQL `BigInteger + Identity(legacySerialSyntax=true)` -> `BIGSERIAL`.
- PostgreSQL `BigInteger + Identity(legacySerialSyntax=false)` ->
  `BIGINT GENERATED {ALWAYS|BY DEFAULT} AS IDENTITY`.
- PostgreSQL `BigInteger` ohne Generation -> `BIGINT`
- Reverse PostgreSQL `bigserial` -> `BigInteger +
  Identity(legacySerialSyntax=true, sequenceName=...)`.
- Reverse PostgreSQL `bigint GENERATED ... AS IDENTITY` -> `BigInteger +
  Identity(legacySerialSyntax=false, mode=...)`.

Owned-/Generated-Sequence-Policy:

- Eine implizite oder owned Serial-/Identity-Sequence gehoert zum
  `ColumnGeneration.Identity`-Metadatum der Spalte.
- Ein solcher Sequence-Name darf sichtbar bleiben, damit Roundtrip, Diagnose
  und Ziel-DDL deterministisch bleiben.
- Eine an `ColumnGeneration.Identity` gebundene Sequence wird nicht als
  eigenstaendige `CREATE SEQUENCE` aus `SchemaDefinition.sequences` emittiert.
- Eigenstaendige Business-Sequences bleiben `SchemaDefinition.sequences` und
  werden weiter separat erzeugt.

Begruendung:

- Datentyp und Generierungsstrategie bleiben getrennt.
- `SERIAL`, `BIGSERIAL`, `GENERATED ALWAYS`, `BY DEFAULT`, Sequence-Namen und
  Defaults koennen ohne neue Typvarianten ausgedrueckt werden.
- Das urspruengliche Problem bleibt geloest: `BigInteger` ohne
  Generation-Kontext wird nicht zu `BIGSERIAL`.
- Der Vertrag legt zugleich fest, wie doppelte Sequence-DDL vermieden wird.

Verworfene Alternativen:

- Option A, `NeutralType.BigIdentifier`, wird nicht umgesetzt. Sie waere nah an
  den alten Specs, wiederholt aber das Breitenkonzept in separaten Typen und
  kann Identity-Details wie `GENERATED ALWAYS`, `BY DEFAULT`, Sequence-Name
  oder Default-Ausdruck nur unzureichend transportieren.
- Option B, Breite am `Identifier`, wird nicht umgesetzt. Sie waere ein
  einheitlicheres Identifier-Konzept, mischt aber weiterhin Datentyp und
  Generierung und waere ein groesserer Breaking Change fuer Serialisierung,
  Parser, Builder, Tests und Specs.

---

## 5. Arbeitsplan

### AP 1: Spec-Korrektur (erledigt 2026-05-07)

- `spec/architecture.md` §3.4 auf die aktuelle `TypeMapper`-API umgestellt
  und den geplanten `ColumnGeneration.Identity`-Vertrag statt
  `NeutralType.BigIdentifier` beschrieben.
- `spec/design.md` und `spec/neutral-model-spec.md` praezisieren:
  `identifier` erzeugt heute `SERIAL`; 64-bit Identity braucht den neuen
  Vertrag aus diesem Follow-up.
- Neutrale Generate-Beispiele verwenden kein `BIGSERIAL` mehr ohne
  expliziten Generation-Kontext.

### AP 2: Modellumsetzung (erledigt 2026-05-07)

- Option C als neuen neutralen Vertrag implementieren:
  `ColumnDefinition.generation: ColumnGeneration?`.
- Der Vertrag fuer owned/generated Sequences ist Bestandteil der Umsetzung:
  - Wie wird die Sequence der Spalte zugeordnet?
  - Bleibt der Sequence-Name sichtbar?
  - Wird `SERIAL` als Legacy-Syntax oder als Identity-Form gerendert?
  - Welche Sequences bleiben eigenstaendige `SchemaDefinition.sequences`?
- Wire-/Format-Kompatibilitaet klaeren:
  - JSON/YAML Schema-Parser
  - SchemaNodeBuilder
  - Diff/Comparator
  - Validator
  - Transfer-Type-Kompatibilitaet

### AP 3: PostgreSQL Reverse (erledigt 2026-05-07)

- `PostgresTypeMapping` modelliert `bigserial`/`bigint identity` als
  `NeutralType.BigInteger` plus `ColumnGeneration.Identity`.
- Der PostgreSQL-Column-Reader liest `identity_generation` und
  `pg_get_serial_sequence(...)` als Generation-Kontext.
- Owned Serial-/Identity-Sequences werden aus `SchemaDefinition.sequences`
  herausgefiltert; eigenstaendige Business-Sequences bleiben sichtbar.
- `R300` wird fuer strukturiert erkannte `bigserial`/`bigint identity` nicht
  mehr erzeugt.

### AP 4: PostgreSQL Forward (erledigt 2026-05-07)

- Der PostgreSQL-DDL-Generator rendert `ColumnGeneration.Identity` explizit:
  - `Integer + Identity(legacySerialSyntax=true)` -> `SERIAL`
  - `Integer + Identity(legacySerialSyntax=false)` ->
    `INTEGER GENERATED {ALWAYS|BY DEFAULT} AS IDENTITY`
  - `BigInteger + Identity(legacySerialSyntax=true)` -> `BIGSERIAL`
  - `BigInteger + Identity(legacySerialSyntax=false)` ->
    `BIGINT GENERATED {ALWAYS|BY DEFAULT} AS IDENTITY`
- Normales `BigInteger` ohne Generation bleibt `BIGINT`.
- Gebundene implizite/owned Sequences werden vor der Sequence-DDL-Erzeugung
  herausgefiltert. Eigenstaendige Sequenzen bleiben unveraendert im
  Sequence-Generator.

### AP 5: Weitere Dialekte (erledigt 2026-05-07)

- MySQL `BIGINT AUTO_INCREMENT` wird im Reverse-Pfad als
  `BigInteger + ColumnGeneration.Identity(legacySerialSyntax=true)` gelesen
  und im Forward-Pfad als `BIGINT NOT NULL AUTO_INCREMENT` erzeugt.
- MySQL `BigInteger` ohne Generation bleibt `BIGINT`.
- MySQL `INT AUTO_INCREMENT` bleibt aus Kompatibilitaetsgruenden weiter
  `Identifier(autoIncrement=true)`.
- SQLite hat keinen separaten `BIGINT AUTO_INCREMENT`-Vertrag. Der einzige
  native Auto-Increment-/Rowid-Pfad ist `INTEGER PRIMARY KEY AUTOINCREMENT`.
  `Integer`/`BigInteger + ColumnGeneration.Identity` wird deshalb fuer SQLite
  genau in diese Rowid-Form gerendert; `BigInteger` ohne Generation bleibt
  der normale SQLite-Integer-Affinity-Pfad.

---

## 6. Tests (erledigt 2026-05-07)

- Modell-/Serialisierungstests fuer den neuen Vertrag.
  - `SchemaNodeParserTest` und `SchemaNodeBuilderTest` decken
    `generation.type`, `mode`, `sequence_name` und
    `legacy_serial_syntax` ab.
  - Core-Tests decken Diff und Validierung fuer
    `ColumnGeneration.Identity` ab.
- PostgreSQL TypeMapper-Test:
  - 32-bit generated identifier -> `SERIAL` oder gewaehlter Identity-Ausdruck.
  - 64-bit generated identifier -> `BIGSERIAL` oder gewaehlter
    Identity-Ausdruck.
  - `BigInteger` ohne Generation -> `BIGINT`.
  - Abgedeckt in `PostgresTypeMappingTest` und
    `PostgresDdlGeneratorTableTest`.
- PostgreSQL Reverse-Test:
  - `serial` roundtript als 32-bit generated identifier.
  - `bigserial` roundtript als 64-bit generated identifier.
  - `bigint` ohne Default/Identity bleibt `BigInteger`.
  - Abgedeckt in `PostgresSchemaReaderTest` und
    `PostgresSchemaReaderIntegrationTest`.
- Integrationstest fuer Reverse -> Generate ohne Kollaps von `BIGSERIAL` zu
  `SERIAL`.
- Integrationstest fuer Reverse -> Generate ohne doppelte Sequence-DDL bei
  `SERIAL`/`BIGSERIAL`/Identity-Spalten.
- Test fuer eigenstaendige Sequence: nicht an eine Spalte gebundene Sequenzen
  bleiben `CREATE SEQUENCE`.
  - Abgedeckt in `PostgresSchemaReaderIntegrationTest` und
    `PostgresDdlGeneratorTableTest`.
- MySQL-Test fuer `BIGINT AUTO_INCREMENT`.
  - Abgedeckt in `MysqlTypeMappingTest`, `MysqlSchemaReaderTest`,
    `MysqlDdlGeneratorTableTest` und `MysqlSchemaReaderIntegrationTest`.
- SQLite-Test fuer den expliziten Rowid-Vertrag:
  `BigInteger + Identity` erzeugt `INTEGER PRIMARY KEY AUTOINCREMENT`.
  - Abgedeckt in `SqliteDdlGeneratorTableTest`.

---

## 7. Akzeptanz

- Keine Spec behauptet mehr, `NeutralType.BigIdentifier` sei implementiert,
  solange der Typ nicht existiert.
- Forward-Generate kann `BIGSERIAL` nur erzeugen, wenn die neutrale Struktur
  explizit eine 64-bit generated identifier Semantik traegt.
- Normale `BIGINT`-Spalten werden nicht versehentlich zu Auto-Increment-
  Spalten.
- Reverse -> Forward fuer PostgreSQL `bigserial` verliert weder Breite noch
  Generierungssemantik.
- Reverse -> Forward erzeugt fuer implizite/owned Serial-/Identity-Sequenzen
  keine doppelte Sequence-DDL.
