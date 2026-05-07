# Follow-up-Plan: BIGSERIAL, BigIdentifier und neutrale Identity-Breite

> Status: Draft (2026-05-07)
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

### Echte Abweichungen zum Code

- `spec/architecture.md` §3.4 zeigt einen alten Beispiel-Mapper mit
  `NeutralType.BigIdentifier(autoIncrement=true)` und
  `BigIdentifier -> BIGSERIAL`.
- Dasselbe Beispiel ist auch jenseits von `BigIdentifier` nicht mehr
  code-nah:
  - Interface heisst im Code `TypeMapper.toSql(...)` und
    `toDefaultSql(...)`; Reverse-Mapping liegt in den driver-spezifischen
    Mapping-Objekten, z. B. `PostgresTypeMapping`.
  - Das Beispiel verwendet alte Namen wie `NeutralType.Boolean`,
    `NeutralType.Float(precision=...)` und
    `DateTime(withTimezone=...)`; der Code verwendet `BooleanType`,
    `floatPrecision` und `timezone`.
- `spec/design.md` und `spec/neutral-model-spec.md` listen
  `identifier` fuer PostgreSQL als `SERIAL / BIGSERIAL`. Das ist zu grob,
  weil der aktuelle `identifier`-Typ nur `SERIAL` eindeutig erzeugt.

### Keine direkte Abweichung

- `spec/design-import-sequences-triggers.md` beschreibt Import in bestehende
  `SERIAL`/`BIGSERIAL`-Zielspalten und Sequence-Resync. Das ist ein
  Import-/Ziel-DB-Thema, nicht der neutrale Forward-Typvertrag.
- `docs/planning/done/implementation-plan-0.4.0.md` beschreibt
  Identity-/Sequence-Support fuer Import/Sync. Das widerspricht nicht
  zwingend dem fehlenden `BIGSERIAL`-Forward-Mapping.
- `docs/planning/done/change-request-spatial-types.md` und
  `spec/ddl-generation-rules.md` enthalten SQL-Beispiele mit `BIGSERIAL`.
  Diese Beispiele koennen fachlich korrekt sein, belegen aber nicht, dass der
  neutrale Generator `BIGSERIAL` bereits modellieren kann.
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

## 4. Entscheidungsoptionen

### Option A: `NeutralType.BigIdentifier`

Neuen Typ einfuehren:

```kotlin
data class BigIdentifier(val autoIncrement: Boolean = false) : NeutralType()
```

Mapping:

- PostgreSQL `BigIdentifier(autoIncrement=true)` -> `BIGSERIAL`
- PostgreSQL `BigIdentifier(autoIncrement=false)` -> `BIGINT`
- Reverse `bigserial`/`bigint identity` -> `BigIdentifier(autoIncrement=true)`

Vorteile:

- Nahe an den alten Specs.
- Einfacher Forward-Mapper.

Nachteile:

- Wiederholt das Breitenkonzept in separaten Typen.
- Identity-Details wie `GENERATED ALWAYS` vs. `BY DEFAULT`, Sequence-Name oder
  Default-Ausdruck passen nur begrenzt in einen Typ.

### Option B: Breite am `Identifier`

`Identifier` bekommt eine Breite:

```kotlin
data class Identifier(
    val autoIncrement: Boolean = false,
    val width: IdentifierWidth = IdentifierWidth.INT32,
) : NeutralType()
```

Mapping:

- PostgreSQL `Identifier(true, INT32)` -> `SERIAL`
- PostgreSQL `Identifier(true, INT64)` -> `BIGSERIAL`
- PostgreSQL `Identifier(false, INT64)` -> `BIGINT`

Vorteile:

- Ein Identifier-Konzept, explizite Breite.
- Passt besser als `BigInteger`, wenn die Spalte semantisch eine ID ist.

Nachteile:

- Breaking Change fuer Serialisierung, Parser, Builder, Tests und Specs.
- Identity-Details bleiben weiterhin nur teilweise modelliert.

### Option C: Separates Identity-/Generation-Metadatum

Typ bleibt Breite, Generation wird an der Spalte modelliert:

```kotlin
ColumnDefinition(
    type = NeutralType.BigInteger,
    generated = ColumnGeneration.Identity(...)
)
```

Mapping:

- PostgreSQL `BigInteger + Identity/SerialGeneration` -> `BIGSERIAL` oder
  `BIGINT GENERATED ... AS IDENTITY`
- PostgreSQL `BigInteger` ohne Generation -> `BIGINT`
- Owned serial/identity sequences muessen entweder in `ColumnGeneration`
  gebunden oder aus der eigenstaendigen Sequence-Liste herausgefiltert werden,
  damit Generate keine doppelte Sequence-DDL erzeugt.

Vorteile:

- Trennung von Datentyp und Generierungsstrategie.
- Kann `SERIAL`, `BIGSERIAL`, `GENERATED ALWAYS`, `BY DEFAULT`, Sequence-Namen
  und Defaults sauberer ausdruecken.
- Verhindert das urspruengliche Problem, `BigInteger` ohne Kontext auf
  `BIGSERIAL` zu heben.
- Erlaubt eine explizite Owned-Sequence-Policy: gebundene implizite Sequences
  gehoeren zur Spalte, eigenstaendige Business-Sequences bleiben
  `SchemaDefinition.sequences`.

Nachteile:

- Groesserer Modell- und Persistenzumbau.
- Mehr Migration in YAML/JSON-Formaten, Diff, Validator und DDL-Generator.

### Empfehlung

Option C ist fachlich am saubersten. Falls der Scope klein bleiben soll, ist
Option B ein vertretbarer Zwischenschritt. Option A ist nur dann sinnvoll, wenn
bewusst die alten Specs mit minimalem Modellumbau nachgezogen werden sollen.

---

## 5. Arbeitsplan

### AP 1: Spec-Korrektur

- `spec/architecture.md` §3.4 auf aktuelle API oder bewusst neues Zielmodell
  umstellen.
- `spec/design.md` und `spec/neutral-model-spec.md` praezisieren:
  `identifier` erzeugt heute `SERIAL`; 64-bit Identity braucht den neuen
  Vertrag aus diesem Follow-up.
- SQL-Beispiele mit `BIGSERIAL` nur dort stehen lassen, wo sie echte
  Ziel-DB-Beispiele sind; bei neutralem Generate-Kontext klar markieren.

### AP 2: Modellentscheidung

- Eine der Optionen A/B/C verbindlich festlegen.
- Falls Option C gewaehlt wird, muss der Vertrag fuer owned/generated
  Sequences Bestandteil der Modellentscheidung sein:
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
