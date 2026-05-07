# Follow-up-Plan: BIGSERIAL, BigIdentifier und neutrale Identity-Breite

> Status: Draft, Option C entschieden (2026-05-07)
>
> Kontext: Der produktive PostgreSQL-Reverse-Pfad mapped `bigserial` bewusst
> auf `NeutralType.BigInteger` mit Diagnose `R300`. Aeltere Specs zeigen
> dagegen `NeutralType.BigIdentifier -> BIGSERIAL`. Dieser Plan klaert die
> Abweichung und beschreibt einen belastbaren Aenderungspfad.

---

## 1. Befund

### Produktiver Stand

Aktueller Code:

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

Wichtig: Das ist die historische Planbegruendung, nicht vollstaendig die
heutige strukturierte Modellrealitaet. Der aktuelle PostgreSQL-Reverse-Pfad
entscheidet die Spaltenabbildung ueber `information_schema.columns.is_identity`
und `column_default`/`nextval(...)`. Sequenzen werden separat in
`SchemaDefinition.sequences` gelesen, aber nicht belastbar als
Owned-Sequence-/Identity-Metadatum an die konkrete Spalte gebunden. Genau diese
fehlende Bindung ist Teil der Luecke.

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

### AP 2: Modellumsetzung

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

### AP 3: PostgreSQL Reverse

- `PostgresTypeMapping` so erweitern, dass `bigserial`/`bigint identity`
  nicht nur als `BigInteger` mit `R300`, sondern gemaess neuem Vertrag als
  64-bit generated identifier modelliert wird.
- Owned-Sequence-Erkennung strukturiert implementieren. Der Reverse-Pfad darf
  sich nicht nur auf eine `R300`-Note verlassen; er muss Default/Identity,
  Sequence-Ownership und Spaltenbindung so weit abbilden, wie es fuer
  Forward-Generate noetig ist.
- `R300` entweder entfernen, abschwaechen oder auf Legacy-/Fallback-Faelle
  beschraenken.

### AP 4: PostgreSQL Forward

- `PostgresTypeMapper` bzw. DDL-Generator so erweitern, dass 64-bit generated
  identifier deterministisch `BIGSERIAL` oder `BIGINT GENERATED ... AS
  IDENTITY` erzeugen.
- Normales `BigInteger` bleibt `BIGINT`.
- Gebundene implizite/owned Sequences werden nicht zusaetzlich als
  eigenstaendige `CREATE SEQUENCE` erzeugt. Eigenstaendige Sequenzen bleiben
  unveraendert im Sequence-Generator.

### AP 5: Weitere Dialekte

- MySQL `BIGINT AUTO_INCREMENT` analog behandeln.
- SQLite-Vertrag explizit klaeren, weil SQLite nur `INTEGER PRIMARY KEY`
  als Rowid-Autoincrement-Spezialfall kennt.

---

## 6. Tests

- Modell-/Serialisierungstests fuer den neuen Vertrag.
- PostgreSQL TypeMapper-Test:
  - 32-bit generated identifier -> `SERIAL` oder gewaehlter Identity-Ausdruck.
  - 64-bit generated identifier -> `BIGSERIAL` oder gewaehlter
    Identity-Ausdruck.
  - `BigInteger` ohne Generation -> `BIGINT`.
- PostgreSQL Reverse-Test:
  - `serial` roundtript als 32-bit generated identifier.
  - `bigserial` roundtript als 64-bit generated identifier.
  - `bigint` ohne Default/Identity bleibt `BigInteger`.
- Integrationstest fuer Reverse -> Generate ohne Kollaps von `BIGSERIAL` zu
  `SERIAL`.
- Integrationstest fuer Reverse -> Generate ohne doppelte Sequence-DDL bei
  `SERIAL`/`BIGSERIAL`/Identity-Spalten.
- Test fuer eigenstaendige Sequence: nicht an eine Spalte gebundene Sequenzen
  bleiben `CREATE SEQUENCE`.
- MySQL-Test fuer `BIGINT AUTO_INCREMENT`.

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
