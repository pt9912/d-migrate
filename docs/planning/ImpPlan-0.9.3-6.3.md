# Implementierungsplan: 0.9.3 - Arbeitspaket 6.3 `Sequence Phase A2: Neutralmodell und Audit aller DefaultValue-Verzweigungen`

> **Milestone**: 0.9.3 - Beta: Filter-Haertung und
> MySQL-Sequence-Emulation (Generator)
> **Arbeitspaket**: 6.3 (`Sequence Phase A2: Neutralmodell und Audit
> aller DefaultValue-Verzweigungen`)
> **Status**: Done (2026-04-20)
> **Referenz**: `docs/planning/implementation-plan-0.9.3.md` Abschnitt 4.2a,
> Abschnitt 4.3, Abschnitt 5.2, Abschnitt 5.5, Abschnitt 6.3,
> Abschnitt 6.5, Abschnitt 6.6, Abschnitt 7 und Abschnitt 8;
> `spec/neutral-model-spec.md`;
> `spec/schema-reference.md`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DefaultValue.kt`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaValidator.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeParser.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeBuilder.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpers.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`;
> `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt`;
> `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt`.

---

## 1. Ziel

Arbeitspaket 6.3 fuehrt den neutralen Modellanker fuer
sequence-basierte Defaults ein und zieht alle betroffenen
`DefaultValue`-Verzweigungen auf denselben Vertrag:

- `DefaultValue` bekommt `SequenceNextVal(sequenceName: String)`
- YAML/JSON-Codecs kennen die kanonische Objektform
  `default.sequence_nextval`
- Validator, Compare-Helfer und Dialektpfade behandeln den neuen Subtyp
  explizit statt implizit ueber freie Funktionsstrings
- historische `nextval(...)`-Notationen werden bewusst hart abgewiesen
  — sowohl als Map-Eingabe als auch als freie Textform

6.3 liefert bewusst noch nicht:

- die MySQL-Supportobjekt-Generierung
- Trigger-, Routine- oder Helper-Table-DDL
- Reverse/Compare gegen echte Datenbankobjekte

Nach 6.3 soll klar gelten:

- das Repo kennt `SequenceNextVal` als expliziten neutralen Vertrag
- Round-Trips ueber YAML/JSON sind fuer den neuen Typ verlustfrei
- alle relevanten `when (default)`-/`when (dv)`-Stellen sind
  exhaustiv nachgezogen
- alte `nextval(...)`-Formen scheitern frueh und konsistent — egal ob
  als `FunctionCall`, `StringLiteral` oder Map-Default eingegeben

---

## 2. Ausgangslage

Der aktuelle `DefaultValue`-Vertrag ist noch zu schmal:

- `DefaultValue` kennt nur:
  - `StringLiteral`
  - `NumberLiteral`
  - `BooleanLiteral`
  - `FunctionCall`
- `SchemaNodeParser.parseDefault(...)` verarbeitet heute im Wesentlichen
  skalare Default-Nodes
- `SchemaNodeBuilder.buildDefault(...)` schreibt die bekannten
  Default-Typen als bestehende Scalar-/Function-Formen
- `SchemaValidator.isDefaultCompatible(...)` kennt noch keinen
  expliziten Sequence-Subtyp
- Compare- und Dialektpfade behandeln `nextval(...)` heute hoechstens
  indirekt ueber `FunctionCall`

Das reicht fuer 0.9.3 nicht:

- `SequenceDefinition` kann zwar modelliert sein, ihre Nutzung als
  Spaltendefault aber nicht als neutraler Vertrag
- freie `FunctionCall("nextval(...)")`-Strings sind mehrdeutig und
  ungeeignet fuer spaetere Reverse-/Compare-Arbeit
- YAML/JSON koennen die Sequence-Semantik ohne explizite Objektform
  nicht verlustfrei ausdruecken

Zusaetzlich ist 6.3 bewusst ein Breaking-Change-Punkt:

- historische oder programmgesteuert erzeugte
  `nextval(...)`-Defaults werden in 0.9.3 nicht mehr still getragen
- das betrifft uebergreifend alle Dialekte, auch wenn praktisch
  bestehende PostgreSQL-Schemas am ehesten betroffen sein koennen

### 2.1 Parser-Verhalten: aktuelle Lücke

Der aktuelle Parser (`SchemaNodeParser.parseDefault()`, Zeile 130-144)
hat fuer den Legacy-Migrationspfad eine entscheidende Luecke:

- fuer textuelle Nodes kennt er nur `current_timestamp` und `gen_uuid`
  als reservierte `FunctionCall`-Strings
- ein freier Text wie `nextval('my_seq')` wird heute als
  `StringLiteral("nextval('my_seq')")` eingelesen, nicht als
  `FunctionCall`
- ein Map-Node, der weder boolean/number/text ist, faellt durch in den
  Catch-All `else -> DefaultValue.StringLiteral(node.toString())`

Konsequenz: Ohne Parser-Aenderung wuerde die im Validator geplante
harte Ablehnung von `FunctionCall("nextval(...)")` fuer die haeufigste
Eingabeform (freier Text) gar nicht greifen, weil der Wert als
`StringLiteral` ankommt. Der Plan muss daher alle drei moeglichen
Eingabeformen abfangen.

---

## 3. Scope fuer 6.3

### 3.1 In Scope

- `DefaultValue.SequenceNextVal(sequenceName: String)`
- YAML-/JSON-Codec-Vertrag fuer `default.sequence_nextval`
- strukturelle Erweiterung von `SchemaNodeParser.parseDefault(...)`
  um die neue Map-Form
- Entfernung des generischen Catch-All-Fallbacks in
  `SchemaNodeParser.parseDefault(...)` (Zeile 143); unbekannte
  Nicht-Scalar-Defaults werden zu einem strukturierten Parse-Fehler
  statt zu `StringLiteral(node.toString())`
- Erkennung von `nextval(...)`-Textmustern im Parser als
  `FunctionCall` (statt `StringLiteral`), damit der Validator sie
  abfangen kann
- `SchemaNodeBuilder.buildDefault(...)` fuer die neue Objektform
- `SchemaValidator`: Typ-Kompatibilitaet und Sequenz-Existenzpruefung
  fuer `SequenceNextVal`
- `SchemaValidator`: harter Migrationsfehler fuer Legacy-`nextval(...)`
  in allen drei Varianten (FunctionCall, StringLiteral-Textmuster,
  Map-Default)
- Audit aller relevanten `DefaultValue`-Verzweigungen:
  - Validator
  - Parser
  - Builder
  - Compare-Helfer
  - Dialektpfade/TypeMapper
- Breaking-Change-Migrationspfad fuer alte
  `FunctionCall("nextval(...)")`

### 3.2 Bewusst nicht Teil von 6.3

- die eigentliche MySQL-Generatorlogik fuer `helper_table`
- MySQL-Routinen/Trigger/Supportobjekte
- Reverse von PostgreSQL-`nextval('...')` oder MySQL-Supportobjekten
- Datenbankvergleich gegen echte DB-Metadaten

Praezisierung:

6.3 loest "wie sieht der neutrale Vertrag aus und wo muss er bekannt
sein?", nicht "wie wird daraus fertige MySQL-DDL erzeugt?".

---

## 4. Leitentscheidungen

### 4.1 `SequenceNextVal` ist ein expliziter Subtyp, kein Pattern-Match

Verbindliche Folge:

- `DefaultValue` wird um `SequenceNextVal(sequenceName: String)`
  erweitert
- ein String-Literal wie `"nextval(foo)"` bleibt ein String-Literal
  nur dann, wenn es nicht das Legacy-Muster `nextval(...)` trifft
  (siehe §4.4)
- `FunctionCall("nextval(...)")` wird nicht implizit als Sequence-Form
  umgedeutet

Begruendung:

- nur ein expliziter Subtyp ist fuer Generator, Validator und spaetere
  Reverse-/Compare-Arbeit eindeutig genug

### 4.2 Die kanonische Eingabeform ist `default.sequence_nextval`

Verbindliche Folge:

- YAML/JSON-Eingabe:

```yaml
default:
  sequence_nextval: invoice_number_seq
```

- `SchemaNodeParser.parseDefault(...)` erkennt dafuer einen Map-Node
  mit genau dem Schluessel `sequence_nextval`
- unbekannte Map-Schluessel fuehren zu einem strukturierten Parse-Fehler
- `SchemaNodeBuilder.buildDefault(...)` schreibt denselben Wert wieder
  als explizites Map-Objekt

### 4.3 Bestehende `nextval(...)`-Notationen sind ein bewusster Bruch

Verbindliche Folge:

- historische `nextval(...)`-Defaults bekommen keinen stillen
  Compat-Shim — egal in welcher Repraesentationsform sie ankommen
- der harte Migrationsabbruch ist in 0.9.3 ein expliziter Breaking
  Change
- verpflichtend sind:
  - Release-Note
  - Migrationshinweis
  - Vorher/Nachher-Beispiel
  - Such-/Ersetzungsheuristik fuer bestehende Schema-Dateien

### 4.4 Die harte Ablehnung deckt alle Eingabevarianten ab

Das Legacy-Muster `nextval(...)` kann auf drei Wegen ins Modell
gelangen. Der Plan muss alle drei abdecken:

**Variante A — freier Text (haeufigster Fall):**

Eingabe: `default: "nextval('invoice_seq')"`

Aktuelles Verhalten: `SchemaNodeParser` erzeugt
`StringLiteral("nextval('invoice_seq')")`. Der Validator sieht einen
generischen String und prueft nur String-Typ-Kompatibilitaet (E009).

Loesung: Der Parser erkennt im textuellen Zweig (Zeile 135-141)
Strings, die dem Muster `nextval(...)` oder `nextval('...')`
entsprechen, und erzeugt dafuer `FunctionCall("nextval(...)")`.
Dadurch kann der Validator die Legacy-Form einheitlich als
`FunctionCall` abfangen.

Konkret: Im `node.isTextual`-Zweig von `parseDefault` wird nach den
bestehenden reservierten Funktionsnamen (`current_timestamp`,
`gen_uuid`) eine Regex-Pruefung eingefuegt:

```kotlin
// Legacy nextval detection: keep as FunctionCall so the validator
// can emit a targeted migration error
text.matches(Regex("""nextval\(.*\)""", RegexOption.IGNORE_CASE))
    -> DefaultValue.FunctionCall(text)
```

**Variante B — Map-Node mit `nextval`-Schluessel:**

Eingabe: `default: { nextval: invoice_seq }`

Aktuelles Verhalten: Der `else`-Catch-All in Zeile 143 erzeugt
`StringLiteral(node.toString())` — weder Parse-Fehler noch
erkennbarer Migrationspfad.

Loesung: Der Map-Zweig erkennt bekannte Schluessel
(`sequence_nextval`) explizit und wirft fuer alle unbekannten
Map-Schluessel (einschliesslich `nextval`) einen strukturierten
Parse-Fehler. Falls der Schluessel `nextval` ist, enthaelt der
Fehler einen gezielten Migrationshinweis auf `sequence_nextval`.

**Variante C — programmatisch erzeugtes `FunctionCall("nextval(...)")`:**

Eingabe: Kein YAML-Pfad, sondern Code, der direkt
`FunctionCall("nextval(...)")` erzeugt.

Loesung: Der Validator faengt diese Form als E122 ab (siehe §4.5).

Verbindliche Reihenfolge:

1. `SchemaNodeParser` erkennt alle drei Formen:
   - `sequence_nextval`-Map → `SequenceNextVal`
   - Legacy-Text `nextval(...)` → `FunctionCall` (fuer Validator-E122)
   - Map mit Schluessel `nextval` → Parse-Fehler mit Migrationshinweis
   - Map mit anderem unbekanntem Schluessel → generischer Parse-Fehler
2. `SchemaValidator` ist der zentrale, nutzernahe harte Abbruchpunkt
   fuer `FunctionCall("nextval(...)")` mit konsistenter
   Migrationsfehlermeldung (E122)
3. Generator-/TypeMapper-Pfade behalten zusaetzlich defensive Guards,
   duerfen aber keinen stillen Fallback oder Rewrite enthalten

### 4.5 Eigene Error-Codes fuer Sequence-Default-Validierung

Die Sequence-Default-Validierung bekommt dedizierte Error-Codes
statt Wiederverwendung von E009:

- **E122**: Legacy-`nextval(...)`-Notation erkannt — Migrationsfehler
  mit Verweis auf die kanonische `default.sequence_nextval`-Form.
  Fehlermeldung enthaelt:
  - die erkannte Legacy-Notation
  - den Migrationspfad (`default.sequence_nextval: <name>`)
  - ein Vorher/Nachher-Beispiel
- **E123**: Sequence-Default referenziert nicht-existierende Sequence
  (z.B. `default.sequence_nextval: invoice_seq`, aber
  `schema.sequences` enthaelt keine `invoice_seq`).
  Fehlermeldung enthaelt:
  - den referenzierten Sequence-Namen
  - die verfuegbaren Sequences im Schema (falls < 10)

Begruendung:

- E009 ist "Default value incompatible with column type" — ein
  generischer Typ-Mismatch-Fehler. Legacy-nextval ist aber kein
  Typ-Mismatch, sondern ein Notation-Bruch; fehlende Sequence ist
  eine Referenz-Verletzung. Beide Faelle brauchen eigene, eindeutige
  Fehlermeldungen, damit Nutzer gezielt handeln koennen.
- E009 wird weiterhin fuer `SequenceNextVal` mit inkompatiblem
  Spaltentyp (z.B. sequence_nextval auf einer Text-Spalte) verwendet.

Ledger-Vertrag:

- E122 und E123 werden in `ledger/error-code-ledger-0.9.3.yaml`
  eingetragen — in 6.3 mit `status: active` und gueltigem `test_path`
  und `evidence_paths`, da emittierender Code und Tests in diesem
  Arbeitspaket entstehen.

### 4.6 Sequenz-Existenzpruefung erfordert Schema-Kontext im Validator

Die aktuelle Validator-Architektur hat fuer die geforderte
Existenzpruefung eine strukturelle Luecke:

- `validateDefaultTypeCompatibility(path, col, errors)` delegiert an
  `isDefaultCompatible(default, col.type)`, die nur `DefaultValue`
  und `NeutralType` sieht — kein Zugriff auf `schema.sequences`
- die Pruefung "existiert die referenzierte Sequence?" ist in dieser
  Signatur nicht moeglich

Loesung: Eine neue Validierungsmethode wird eingefuehrt, analog zu
`validateForeignKeyTableExists`:

```kotlin
// E123: Sequence default references non-existent sequence
private fun validateSequenceDefaultReference(
    path: String,
    col: ColumnDefinition,
    schema: SchemaDefinition,
    errors: MutableList<ValidationError>
)
```

Diese Methode wird im bestehenden Spalten-Validierungsloop
(Zeile 43-51 in `SchemaValidator.kt`) nach
`validateDefaultTypeCompatibility` aufgerufen und prueft:

- `col.default is SequenceNextVal` und
  `col.default.sequenceName !in schema.sequences` → E123

Zusaetzlich wird dieselbe Methode auch die Legacy-Erkennung
uebernehmen:

- `col.default is FunctionCall` und `default.name` matcht
  `nextval(...)` → E122

`isDefaultCompatible` bleibt unveraendert in ihrer Signatur und
behandelt `SequenceNextVal` ausschliesslich als Typ-Kompatibilitaets-
Pruefung (numerische/identifier-aehnliche Spalten).

Verhalten bei Mehrfachfehlern: Beide Methoden laufen unabhaengig.
Fuer eine Spalte mit `SequenceNextVal("nonexistent")` auf einer
`Text`-Spalte koennen gleichzeitig E009 (Typ-Inkompatibilitaet) und
E123 (fehlende Sequence) feuern. Das ist gewollt — der Validator
akkumuliert bewusst alle Fehler, damit der Nutzer alle Probleme in
einem Lauf sieht, statt sie einzeln nacheinander zu entdecken.
Dieses Verhalten ist konsistent mit der bestehenden Akkumulation
(z.B. E005 + E009 fuer dieselbe Spalte).

### 4.7 Entfernung des Catch-All-Fallbacks im Parser

Der aktuelle Catch-All in `SchemaNodeParser.parseDefault()`
(Zeile 143):

```kotlin
else -> DefaultValue.StringLiteral(node.toString())
```

wird entfernt und durch einen strukturierten Parse-Fehler ersetzt.

Verbindliche Folge:

- unerkannte Nicht-Scalar-Defaults (Map-Nodes, Array-Nodes) erzeugen
  einen `IllegalArgumentException` mit klarer Meldung, welche
  Default-Formen unterstuetzt werden
- der Catch-All kann bisher unerkannte Struktur-Defaults still
  durchrutschen lassen und spaeter zu unscharfen Fehlern fuehren
  statt zu gezielten Migrationsfehlern
- das ist ein bewusster Breaking Change: Schema-Dateien, die heute
  Nicht-Scalar-Defaults als `StringLiteral(node.toString())` stillen,
  muessen explizit bereinigt werden

### 4.8 Compare-/Report-Kurzform bleibt Lesedarstellung

Verbindliche Folge:

- `SchemaCompareHelpers.defaultValueToString(...)` darf eine lesbare
  Kurzform wie `sequence_nextval(<name>)` rendern
- diese Form ist ausdruecklich keine kanonische YAML/JSON-Eingabe
- Eingabe bleibt `default.sequence_nextval`

### 4.9 Dialektverhalten wird in 6.3 nur vertraglich vorgezogen

Verbindliche Folge:

- PostgreSQL kennt fuer `SequenceNextVal` einen nativen Pfad:
  - `PostgresTypeMapper.toDefaultSql(SequenceNextVal("foo"), ...)` →
    `"nextval('foo')"`
  - das ist nativer PostgreSQL-SQL und braucht keine Emulation
- MySQL und SQLite haben in 6.3 noch keinen fachlichen Pfad fuer
  `SequenceNextVal` (der folgt in 6.4 fuer MySQL). Die TypeMapper
  muessen den neuen Zweig trotzdem exhaustiv behandeln:
  - `MysqlTypeMapper.toDefaultSql(SequenceNextVal(...), ...)` →
    `error("SequenceNextVal requires helper_table mode (not yet implemented)")`
  - `SqliteTypeMapper.toDefaultSql(SequenceNextVal(...), ...)` →
    `error("SequenceNextVal is not supported for SQLite")`
  - diese Zweige sind defensive Guards, die in der Praxis nicht
    erreicht werden sollen — der Validator und/oder Generator sollen
    vorher abfangen (E056 / action_required)
- der Mock-TypeMapper in `AbstractDdlGeneratorTest` muss ebenfalls
  einen Zweig fuer `SequenceNextVal` erhalten

---

## 5. Zielarchitektur

### 5.1 Modell- und Codec-Vertrag

`DefaultValue` wird erweitert zu:

```kotlin
sealed class DefaultValue {
    data class StringLiteral(val value: String) : DefaultValue()
    data class NumberLiteral(val value: Number) : DefaultValue()
    data class BooleanLiteral(val value: Boolean) : DefaultValue()
    data class FunctionCall(val name: String) : DefaultValue()
    data class SequenceNextVal(val sequenceName: String) : DefaultValue()
}
```

Codec-Folgen:

- `SchemaNodeParser.parseDefault(...)` wird um drei Faelle erweitert:
  1. Map-Node mit Schluessel `sequence_nextval` →
     `SequenceNextVal("<name>")`
  2. Map-Node mit unbekanntem Schluessel → strukturierter
     Parse-Fehler (bei Schluessel `nextval` mit gezieltem
     Migrationshinweis)
  3. Text-Node mit `nextval(...)`-Muster → `FunctionCall("<text>")`
     (fuer den Validator-Abfangpfad)
- der bisherige `else`-Catch-All (Zeile 143) wird durch einen
  strukturierten Parse-Fehler fuer alle nicht erkannten Node-Typen
  ersetzt
- die bestehende fruehe Sonderbehandlung fuer reservierte
  Funktionsnamen wie `current_timestamp` und `gen_uuid` bleibt Vorbild
  fuer die Struktur, aber `nextval(...)` wird gerade nicht als
  reservierter Funktionsstring aufgenommen, sondern als Legacy-Erkennung
- `SchemaNodeBuilder.buildDefault(...)` schreibt den neuen Typ als
  explizites Objekt statt als skalaren Wert

### 5.2 Validator- und Migrationsvertrag

Die Validierung fuer Sequence-Defaults wird auf zwei Methoden
aufgeteilt:

**1. Typ-Kompatibilitaet (bestehende Methode):**

`SchemaValidator.isDefaultCompatible(default, type)` bekommt einen
neuen Zweig fuer `SequenceNextVal`:

```kotlin
is DefaultValue.SequenceNextVal ->
    type is NeutralType.Integer || type is NeutralType.SmallInt
        || type is NeutralType.BigInteger || type is NeutralType.Identifier
```

Signatur bleibt unveraendert; Nicht-Existenz der Sequence wird hier
bewusst nicht geprueft (kein Schema-Kontext).

**2. Referenz- und Migrationspruefung (neue Methode):**

```kotlin
// E122 + E123
private fun validateSequenceDefaultReference(
    path: String,
    col: ColumnDefinition,
    schema: SchemaDefinition,
    errors: MutableList<ValidationError>
)
```

Wird im Spalten-Validierungsloop nach
`validateDefaultTypeCompatibility` aufgerufen:

```kotlin
for ((colName, col) in table.columns) {
    val path = "tables.$tableName.columns.$colName"
    // ... bestehende Checks ...
    validateDefaultTypeCompatibility(path, col, errors)
    validateSequenceDefaultReference(path, col, schema, errors)  // NEU
}
```

Pruefungen:

- `col.default is FunctionCall` und `default.name` matcht
  `nextval(...)` (case-insensitive) → E122 mit Migrationsmeldung
- `col.default is SequenceNextVal` und
  `default.sequenceName !in schema.sequences` → E123

### 5.3 Compare- und Dialektvertrag

Compare:

- `SchemaCompareHelpers.defaultValueToString(...)` kennt
  `SequenceNextVal`
- die Darstellung trennt Sequence-Bezug klar von freien String-Literalen
- die Ausgabe bleibt eine Lesedarstellung, kein Reparse-Format

Dialektpfade:

- `PostgresTypeMapper.toDefaultSql()` liefert fuer `SequenceNextVal`
  den nativen SQL-Ausdruck `nextval('<sequenceName>')`
- `MysqlTypeMapper.toDefaultSql()` und
  `SqliteTypeMapper.toDefaultSql()` enthalten in 6.3 einen defensiven
  `error("...")`-Guard, der nicht erreicht werden soll (der Generator
  fuer MySQL/SQLite faengt `SequenceNextVal` ueber den bestehenden
  E056/action_required-Pfad vorher ab)
- der Mock-TypeMapper in `AbstractDdlGeneratorTest` (Zeile 721-726)
  bekommt ebenfalls einen Zweig
- falls der steady-state Pfad den Fall spaeter in
  `AbstractDdlGenerator.columnSql()` abfaengt, bleiben die TypeMapper-
  Stellen trotzdem Teil des Audits und muessen defensiv konsistent sein

---

## 6. Konkrete Arbeitsschritte

### 6.1 `DefaultValue` erweitern

- `SequenceNextVal(sequenceName: String)` einfuehren
- alle exhaustiven `when (default)`-/`when (dv)`-Stellen kompilierbar
  nachziehen — vollstaendige Checkliste:

  | Datei | Zeile | Funktion |
  |-------|-------|----------|
  | `SchemaValidator.kt` | 308 | `isDefaultCompatible()` |
  | `SchemaNodeBuilder.kt` | 179 | `buildDefault()` |
  | `SchemaCompareHelpers.kt` | 41 | `defaultValueToString()` |
  | `PostgresTypeMapper.kt` | 41 | `toDefaultSql()` |
  | `MysqlTypeMapper.kt` | 36 | `toDefaultSql()` |
  | `SqliteTypeMapper.kt` | 33 | `toDefaultSql()` |
  | `AbstractDdlGeneratorTest.kt` | 721 | Mock `toDefaultSql()` |

### 6.2 Schema-Codecs nachziehen

Parser-Erweiterung (`SchemaNodeParser.parseDefault()`):

- Map-Node-Zweig einfuegen (vor dem bisherigen `else`):
  - Schluessel `sequence_nextval` → `SequenceNextVal("<name>")`
  - Schluessel `nextval` → Parse-Fehler mit gezieltem
    Migrationshinweis auf `default.sequence_nextval`
  - anderer unbekannter Schluessel → strukturierter Parse-Fehler
- im textuellen Zweig (`node.isTextual`): nach `current_timestamp`
  und `gen_uuid` eine Regex-Erkennung fuer `nextval(...)` einfuegen,
  die `FunctionCall(text)` erzeugt (Legacy-Erkennung, kein
  Compat-Shim)
- den bisherigen `else`-Catch-All (Zeile 143) durch einen
  strukturierten Parse-Fehler ersetzen
  (`IllegalArgumentException` mit klarer Meldung)

Builder-Erweiterung (`SchemaNodeBuilder.buildDefault()`):

- `SequenceNextVal` als explizites Map-Objekt schreiben:
  `{ sequence_nextval: "<name>" }`

### 6.3 Validator und Migrationsfehler festziehen

Bestehende Methode erweitern:

- `isDefaultCompatible(default, type)` um `SequenceNextVal`-Zweig
  (numerische/identifier-aehnliche Spalten)

Neue Methode einfuehren:

- `validateSequenceDefaultReference(path, col, schema, errors)`
  im Spalten-Validierungsloop nach `validateDefaultTypeCompatibility`
  aufrufen
- E122: Legacy-`nextval(...)`-Erkennung bei `FunctionCall`-Defaults
  (Fehlermeldung mit alter Form, neuer Form, Vorher/Nachher-Beispiel)
- E123: Nicht-existierende Sequence-Referenz bei `SequenceNextVal`
  (Fehlermeldung mit Name und verfuegbaren Sequences)

### 6.4 Compare- und Dialektaudit durchziehen

- `SchemaCompareHelpers.defaultValueToString(...)` erweitern
- `MysqlTypeMapper.toDefaultSql(...)` auditieren
- `PostgresTypeMapper.toDefaultSql(...)` auditieren
- `SqliteTypeMapper.toDefaultSql(...)` auditieren
- sicherstellen, dass kein Pfad `SequenceNextVal` still als freie
  `FunctionCall` oder String-Literal weitertraegt

### 6.5 Ledger-Nachzug

- E122 und E123 in `ledger/error-code-ledger-0.9.3.yaml` eintragen
  mit `status: active`, `test_path` und `evidence_paths`
- falls die 0.9.3-Ledgerdatei noch nicht existiert (aus 6.2),
  hier anlegen

### 6.6 Doku und Fixture-Nachzug vorbereiten

- `spec/neutral-model-spec.md` um `default.sequence_nextval` erweitern
- `spec/schema-reference.md` um die nutzerorientierte
  `default:`-Schreibweise erweitern
- Fixtures fuer sequence-basierte Defaults vorbereiten

---

## 7. Tests und Verifikation

### 7.1 Unit- und Codec-Tests

- `SchemaValidatorTest`:
  - `SequenceNextVal` mit existierender Sequence und kompatiblem
    Spaltentyp → ok
  - `SequenceNextVal` mit nicht-existierender Sequence → E123
  - `SequenceNextVal` auf Text-Spalte → E009
  - altes `FunctionCall("nextval('my_seq')")` → E122 mit
    Verweis auf `default.sequence_nextval`
- `YamlSchemaCodecTest`:
  - Round-Trip fuer `default.sequence_nextval`
  - Text-Input `default: "nextval('my_seq')"` wird als
    `FunctionCall` geparst (Legacy-Erkennung greift)
  - Map-Input `default: { nextval: my_seq }` → Parse-Fehler mit
    Migrationshinweis
  - Map-Input `default: { unknown_key: value }` → Parse-Fehler
  - Nicht-Scalar-Node ohne bekannten Schluessel → Parse-Fehler
    (kein StringLiteral-Fallback)
- `SchemaCompareHelpers`-nahe Tests:
  - `SequenceNextVal` wird als Lesekurzform dargestellt
  - die Kurzform wird nicht als YAML-Eingabevertrag missverstanden

### 7.2 Audit- und Defensivtests

- Generator-/TypeMapper-nahe Tests bestaetigen zusaetzlich, dass
  `SequenceNextVal` bzw. historische `nextval(...)`-Faelle keinen
  stillen Fallback bekommen
- Kompilier-/Exhaustiveness-Nachweis fuer alle bekannten
  `DefaultValue`-Verzweigungen

### 7.3 Akzeptanzkriterien

6.3 gilt als abgeschlossen, wenn gleichzeitig gilt:

- `DefaultValue.SequenceNextVal` existiert als expliziter Modelltyp
- YAML/JSON lesen und schreiben `default.sequence_nextval`
- `SchemaNodeParser.parseDefault()` hat keinen generischen
  Catch-All-Fallback mehr fuer unbekannte Node-Typen
- `SchemaNodeParser.parseDefault()` erkennt `nextval(...)`-Textmuster
  und erzeugt `FunctionCall` (nicht `StringLiteral`)
- `SchemaValidator` prueft Sequenz-Existenz ueber
  `validateSequenceDefaultReference` mit Schema-Kontext
- Legacy-`nextval(...)`-Formen scheitern als E122 mit
  Migrationshinweis — egal ob sie als Text, Map oder FunctionCall
  ankommen
- Fehlende Sequence-Referenzen scheitern als E123 mit klarem
  Sequenz-Namen
- Compare- und Dialektpfade kennen den neuen Subtyp explizit
- das Repo ist wieder exhaustiv-kompilierbar
- E122 und E123 sind im 0.9.3-Error-Ledger mit `status: active`
  eingetragen

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DefaultValue.kt`
  — neuer Subtyp `SequenceNextVal`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaValidator.kt`
  — `isDefaultCompatible` Erweiterung, neue Methode
  `validateSequenceDefaultReference`, Aufruf im Spalten-Loop
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeParser.kt`
  — Map-Node-Zweig, Legacy-Text-Erkennung, Catch-All-Entfernung
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeBuilder.kt`
  — Map-Objektform fuer `SequenceNextVal`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpers.kt`
  — Lesekurzform
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`
  — defensiver Audit
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt`
  — nativer Sequence-Pfad
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt`
  — defensiver Audit
- `spec/neutral-model-spec.md`
- `spec/schema-reference.md`
- `ledger/error-code-ledger-0.9.3.yaml` — E122, E123

Voraussichtlich testseitig betroffen:

- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/SchemaValidatorTest.kt`
  — E122, E123, SequenceNextVal-Kompatibilitaet
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodecTest.kt`
  — Round-Trip, Legacy-Erkennung, Catch-All-Entfernung
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpersTest.kt`
  — `defaultValueToString` fuer `SequenceNextVal`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapperTest.kt`
  — defensiver Guard-Test
- `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapperTest.kt`
  — nativer `nextval('...')`-Test
- `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapperTest.kt`
  — defensiver Guard-Test
- `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/AbstractDdlGeneratorTest.kt`
  — Mock-TypeMapper `toDefaultSql` Exhaustivitaet
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`
  — E122/E123 muessen im 0.9.3-Ledger validiert werden

---

## 9. Offene Punkte

### 9.1 Der Compare-String bleibt bewusst keine Eingabeform

Die lesbare Kurzform `sequence_nextval(<name>)` ist nuetzlich fuer Diff
und Report, darf aber nicht spaeter als zweiter offizieller
Eingabevertrag "durchrutschen".

### 9.2 6.3 friert den Modellvertrag ein, nicht die MySQL-Umsetzung

Die eigentliche MySQL-Sequence-Emulation folgt erst in 6.4. 6.3 sorgt
nur dafuer, dass der Modell- und Codec-Vertrag davor bereits sauber und
eindeutig ist.
