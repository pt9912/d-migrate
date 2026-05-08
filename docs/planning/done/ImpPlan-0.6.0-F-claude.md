# Phase F: schema compare fuer DB-Operanden

## Context

Phase F erweitert den file-based `schema compare`-Pfad um DB-Operanden
(`file/file`, `file/db`, `db/db`), fuehrt den `ResolvedSchemaOperand`-
Envelope produktiv ein, normalisiert Reverse-Marker vor dem Diff und
zieht die CLI-Projektion auf den vollen 0.6.0-Diff-Vertrag
(Sequences, Functions, Procedures, Triggers).

Docker zum Bauen/Testen:
```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :hexagon:application:test :adapters:driving:cli:test" \
  -t d-migrate:phase-f .

docker build -t d-migrate:dev .
```

---

## Implementierungsschritte

### Schritt 1: Operand-Modell + Resolver + Normalizer (F.1, F.2, F.3)

**Neue Datei:** `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/CompareOperandResolver.kt`

```kotlin
sealed interface CompareOperand {
    data class File(val path: Path) : CompareOperand
    data class Database(val source: String) : CompareOperand
}

object CompareOperandParser {
    fun parse(raw: String): CompareOperand
    // "file:<path>" → File, "db:<url-or-alias>" → Database,
    // praefixlos → File (Rueckwaertskompatibilitaet)
}
```

**Datei editieren:** `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ResolvedSchemaOperand.kt`

Erweitern um `reference: String` (user-facing Operand-Referenz):
```kotlin
data class ResolvedSchemaOperand(
    val reference: String,
    val schema: SchemaDefinition,
    val validation: ValidationResult,
    val notes: List<SchemaReadNote> = emptyList(),
    val skippedObjects: List<SkippedObject> = emptyList(),
)
```

**Neue Datei:** `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/CompareOperandNormalizer.kt`

Reverse-Marker-Normalisierung vor Diff:
- Operiert auf **jedem einzelnen** aufgeloesten Operand unabhaengig
  (nicht nur wenn beide Seiten reverse-generiert sind) — damit
  `file/db`-Vergleiche mit nur einer reverse-generierten Seite keinen
  Schein-Diff auf `name`/`version` erzeugen
- Erkennt `__dmigrate_reverse__:` + `0.0.0-reverse` via
  `ReverseScopeCodec.isReverseGenerated()`
- Ersetzt synthetische `name`/`version` durch neutrale Platzhalter
- Ungueltige Marker (Prefix vorhanden, aber Set nicht syntaktisch
  gueltig) → harter Fehler mit Exit 7 (gemaess Phase-F-Vertrag
  docs/planning/ImpPlan-0.6.0-F.md:300)
- Ownership im Application-Layer, NICHT im Core-Comparator

**Tests:** `CompareOperandParserTest.kt`, `CompareOperandNormalizerTest.kt`

---

### Schritt 2: SchemaCompareRunner auf Operanden umstellen (F.4)

**Datei editieren:** `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`

Groesster Umbau: `SchemaCompareRequest` aendern — `source`/`target`
werden `String` statt `Path`:

```kotlin
data class SchemaCompareRequest(
    val source: String,
    val target: String,
    val output: Path?,
    val outputFormat: String,
    val quiet: Boolean,
    val verbose: Boolean = false,
    val cliConfigPath: Path? = null,
)
```

Runner bekommt neue injizierte Collaborators:
```kotlin
class SchemaCompareRunner(
    private val operandParser: (String) -> CompareOperand,
    private val fileLoader: (Path) -> ResolvedSchemaOperand,
    private val dbLoader: (String, Path?) -> ResolvedSchemaOperand,
    private val normalizer: (ResolvedSchemaOperand) -> ResolvedSchemaOperand,
    private val comparator: (SchemaDefinition, SchemaDefinition) -> SchemaDiff,
    private val projectDiff: (SchemaDiff) -> DiffView,
    private val urlScrubber: (String) -> String,
    // ... render/output callbacks wie bisher
)
```

Runner-Flow:
1. Operanden parsen (Exit 2 bei ungueltigem Prefix)
2. Beide Operanden laden (File → codecForPath + validate;
   DB → resolve + urlParse + pool + schemaReader + validate)
3. Normalisierung (Reverse-Marker entfernen)
4. `SchemaComparator.compare(left.schema, right.schema)`
5. Projektion + Ausgabe (inkl. operandseitiger Notes/SkippedObjects)

Exit-Code-Mapping (konsistent mit docs/planning/ImpPlan-0.6.0-F.md:278-301):
- 0: identisch
- 1: Unterschiede
- 2: CLI-Validierung, ungueltiger Operand-Prefix, Output-Kollision
- 3: Schema-Validierungsfehler auf mindestens einem Operand
- 4: NEU — Connection-/DB-Fehler bei `db:`-Operanden
- 7: Datei-/Parse-Fehler, Config-/URL-Aufloesung, Write-Fehler,
     ungueltige Reverse-Marker auf aufgeloesten Operanden

Der bestehende `fileLoader` kapselt den alten Pfad
(codecForPath + read + validate) und liefert einen
`ResolvedSchemaOperand` mit leeren Notes/SkippedObjects.

**Datei editieren:** `SchemaCompareRunnerTest.kt` — bestehende Tests
auf neue Request-Signatur anpassen + neue Tests fuer:
- `file/db` und `db/db`
- Reverse-Marker-Normalisierung
- Exit 4 fuer DB-Fehler
- Operandseitige Notes/SkippedObjects im Ergebnis
- URL-Scrubbing in Fehlerausgaben

---

### Schritt 3: DiffView, Summary und Renderer erweitern (F.6, F.7)

**Datei editieren:** `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`

`SchemaCompareSummary` erweitern:
```kotlin
data class SchemaCompareSummary(
    // ... bestehende Felder ...
    val sequencesAdded: Int = 0,
    val sequencesRemoved: Int = 0,
    val sequencesChanged: Int = 0,
    val functionsAdded: Int = 0,
    val functionsRemoved: Int = 0,
    val functionsChanged: Int = 0,
    val proceduresAdded: Int = 0,
    val proceduresRemoved: Int = 0,
    val proceduresChanged: Int = 0,
    val triggersAdded: Int = 0,
    val triggersRemoved: Int = 0,
    val triggersChanged: Int = 0,
)
```

`SchemaCompareDocument` erweitern um vollstaendige operandseitige Felder:
```kotlin
data class OperandInfo(
    val reference: String,
    val validation: ValidationResult? = null,
    val notes: List<SchemaReadNote> = emptyList(),
    val skippedObjects: List<SkippedObject> = emptyList(),
)

data class SchemaCompareDocument(
    // ... bestehende Felder ...
    val sourceOperand: OperandInfo? = null,
    val targetOperand: OperandInfo? = null,
)
```

Damit sind Validation, Reverse-Notes und SkippedObjects pro Operand
getrennt transportierbar — kein Ad-hoc-Feld noetig.

**Datei editieren:** `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpers.kt`

`projectDiff()` erweitern fuer Sequences/Functions/Procedures/Triggers.
Plain-/JSON-/YAML-Renderer erweitern:
- Neue Sektionen fuer jede Objektart
- Operandseitige Notes/SkippedObjects im Ausgabedokument

**Datei editieren:** Runner `printError` Aufrufe im
`SchemaCompareRunner` verwenden die injizierte `printError`-Funktion
mit der user-facing Operand-Referenz als `source`-Parameter. Da
`OutputFormatter.printError(msg, source)` heute `source` nur als
String-Label nutzt, funktioniert das operandneutral — der Runner
uebergibt `operand.reference` (z.B. `"db:staging"` oder `"/tmp/a.yaml"`)
statt eines hardcodierten `"File:"`-Labels. Kein Umbau von
`OutputFormatter` noetig.
- Operandneutrale Fehler-Labels ("Operand:" statt "File:")
- Summary-Zaehler fuer neue Objektarten

**Tests:** `SchemaCompareHelpersTest.kt` erweitern fuer neue Objektarten

---

### Schritt 4: CLI-Command umstellen (F.5)

**Datei editieren:** `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`

`SchemaCompareCommand` aendern:
- `--source` und `--target` von `.path()` auf `.required()` (String)
- Help-Texte: "Schema file or db: operand (file:<path>, db:<url-or-alias>)"
- Verdrahtung auf erweiterten Runner mit Operand-Callbacks

**Neue Datei (optional):** `SchemaCompareCommand.kt` — wenn die
Komplexitaet es rechtfertigt, eigene Datei wie SchemaReverseCommand

---

### Schritt 5: cli-spec.md + Verifikation (F.8)

**Datei editieren:** `spec/cli-spec.md`

Section `schema compare` aktualisieren:
- `file:` und `db:` Operand-Notation dokumentieren
- Praefixlose Rueckwaertskompatibilitaet
- Exit 4 fuer DB-Fehler
- Erweiterte Objektarten in Summary/Diff
- Operandseitige Notes/SkippedObjects

**Docker-Build:**
```bash
docker build -t d-migrate:dev .
```

---

## Betroffene Dateien

### Neue Dateien (~4)

| Datei | Modul |
|-------|-------|
| `application/commands/CompareOperandResolver.kt` | application |
| `application/commands/CompareOperandNormalizer.kt` | application |
| `application/test/CompareOperandParserTest.kt` | application (test) |
| `application/test/CompareOperandNormalizerTest.kt` | application (test) |

### Zu aendernde Dateien (~8)

| Datei | Aenderung |
|-------|-----------|
| `application/commands/ResolvedSchemaOperand.kt` | +reference: String |
| `application/commands/SchemaCompareRunner.kt` | Operand-basierter Flow, Exit 4, Scrubbing |
| `application/test/SchemaCompareRunnerTest.kt` | Neue Signatur + file/db/db-Tests |
| `cli/commands/SchemaCommands.kt` | --source/--target von path auf String |
| `cli/commands/SchemaCompareHelpers.kt` | Sequences/Functions/Procedures/Triggers-Renderer |
| `cli/test/SchemaCompareHelpersTest.kt` | Tests fuer neue Objektarten |
| `application/test/ReverseContractTest.kt` | ResolvedSchemaOperand.reference |
| `spec/cli-spec.md` | Compare-Vertrag aktualisieren |

---

## Abhaengigkeiten zwischen Schritten

```
Schritt 1 (Operand-Modell)   ── Voraussetzung fuer alles
Schritt 2 (Runner-Umbau)     ── abhaengig von Schritt 1
Schritt 3 (Renderer)         ── parallel zu Schritt 2 moeglich
Schritt 4 (CLI-Command)      ── abhaengig von Schritt 2
Schritt 5 (Docs/Verify)      ── abhaengig von Schritt 2+3+4
```

Schritt 1 zuerst, dann 2+3 parallel, dann 4, dann 5.

---

## Verifikation

```bash
# Core + Application + CLI
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :hexagon:application:test :adapters:driving:cli:test" \
  -t d-migrate:phase-f .

# Vollstaendiger Build
docker build -t d-migrate:dev .
```

Gezielt zu pruefen:
- `file/file` verhaelt sich wie 0.5.0
- `file/db` und `db/db` funktionieren symmetrisch
- Reverse-Marker erzeugen keinen Schein-Diff
- Ungueltige Marker → harter Fehler
- DB-Fehler → Exit 4
- Sequences/Functions/Procedures/Triggers im Output sichtbar
- DB-Operand-Referenzen maskiert
- Fehlertexte gescrubbt
- Operandneutrale Labels in Fehlerausgaben
- `quiet` unterdrueckt keine `different`/`invalid`-Ergebnisse
