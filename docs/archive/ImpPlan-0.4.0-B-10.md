# Implementierungsplan: Phase B – Schritt 10 – DefaultDataChunkReaderFactory

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade
> **Phase**: B (Format-Reader)
> **Schritt**: 10
> **Status**: Umgesetzt und per Docker verifiziert
> **Referenz**: `implementation-plan-0.4.0.md` §3.5.1, §4 Phase B Schritt 10, §6.1

---

## 1. Ziel

Default-Implementierung der `DataChunkReaderFactory`-Schnittstelle, die
alle drei Format-Reader (`JsonChunkReader`, `YamlChunkReader`,
`CsvChunkReader`) aus Phase B Schritte 7–9 über eine zentrale
Factory-Methode instanziiert.

Die Klasse ist das symmetrische Gegenstück zu `DefaultDataChunkWriterFactory`
(0.3.0, Phase D) und dient als einziger Konkretisierungspunkt, den der
`StreamingImporter` (Phase D) und das CLI (Phase E) konsumieren.

---

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `DataChunkReader` Interface | Phase A Schritt 1 | ✅ Umgesetzt |
| `DataChunkReaderFactory` Interface | Phase A Schritt 1 | ✅ Umgesetzt |
| `ImportOptions` | Phase A Schritt 2 | ✅ Umgesetzt |
| `JsonChunkReader` | Phase B Schritt 7 | ✅ Umgesetzt |
| `YamlChunkReader` | Phase B Schritt 8 | ✅ Umgesetzt |
| `CsvChunkReader` | Phase B Schritt 9 | ✅ Umgesetzt |

---

## 3. Betroffene Dateien

### 3.1 Neue Dateien

| Datei | Zweck |
|---|---|
| `d-migrate-formats/src/main/kotlin/dev/dmigrate/format/data/DefaultDataChunkReaderFactory.kt` | Implementierung |
| `d-migrate-formats/src/test/kotlin/dev/dmigrate/format/data/DefaultDataChunkReaderFactoryTest.kt` | Unit-Tests |

### 3.2 Bestehende Dateien (keine Änderungen)

Die Factory-Schnittstelle `DataChunkReaderFactory.kt` und alle drei
Reader-Implementierungen werden unverändert konsumiert.

---

## 4. Design

### 4.1 Symmetrie zu `DefaultDataChunkWriterFactory`

Die Writer-Factory (0.3.0) folgt einem einfachen `when`-Dispatch auf
`DataExportFormat`:

Die Reader-Factory übernimmt dieses Muster 1:1. Es gibt bewusst keine
zusätzliche Abstraktion (Registry, SPI o.Ä.) — die drei Formate sind
hart kodiert und über das `DataExportFormat`-Enum vollständig
abgedeckt.

### 4.2 Konstruktor-Parameter

Die Writer-Factory nimmt einen optionalen `warningSink`-Callback für
`ValueSerializer.Warning`s. Die Reader-Seite hat kein Pendant dazu:
`ValueDeserializer` wirft bei Fehlern direkt Exceptions, die über den
`--on-error`-Pfad im `StreamingImporter` laufen. Die Reader-Factory hat
deshalb **keinen** Konstruktor-Parameter — sie ist ein reiner
Dispatcher.

### 4.3 Parameter-Weiterreichung

Alle drei Reader haben identische Konstruktor-Signaturen:

```kotlin
class Json/Yaml/CsvChunkReader(
    rawInput: InputStream,
    table: String,
    chunkSize: Int,
    options: ImportOptions = ImportOptions(),
)
```

Die Factory reicht die `create(...)`-Parameter unverändert durch:

| Factory-Parameter | Reader-Parameter |
|---|---|
| `input: InputStream` | `rawInput` |
| `table: String` | `table` |
| `chunkSize: Int` | `chunkSize` |
| `options: ImportOptions` | `options` |

`format` wird nur für den Dispatch verwendet und nicht weitergegeben.

### 4.4 `chunkSize`-Validation in der Factory

Die `chunkSize > 0`-Invariante wird in der Factory geprüft, bevor ein
konkreter Reader instanziiert wird. Das macht den Vertrag aus
`DataChunkReaderFactory.create(...)` auch dann robust, wenn die Factory
direkt außerhalb des späteren `PipelineConfig`-Pfads verwendet wird.

Die Reader selbst speichern `chunkSize` nur für ihre `nextChunk()`-
Schleifen und validieren ihn aktuell nicht konsistent. Deshalb ist die
Factory der richtige zentrale Fail-Fast-Punkt für diese Implementierung.
Bei einem Validierungsfehler übernimmt die Factory keine Ownership des
`InputStream` — der Caller bleibt für Cleanup verantwortlich.

---

## 5. Implementierung

### 5.1 `DefaultDataChunkReaderFactory.kt`

### 5.2 Hinweise

- Die Klasse hat keinen internen State und ist thread-safe.
- `DataExportFormat` ist ein Enum mit genau drei Einträgen. Der
  `when`-Block deckt alle Enum-Einträge ab und ist deshalb exhaustiv;
  kein `else`-Zweig nötig.
- Der Name `DataExportFormat` wird bewusst auch für den Import-Pfad
  wiederverwendet (§6.1: Format-Enum ist richtungsunabhängig).

---

## 6. Tests

### 6.1 Teststrategie

Symmetrisch zu `DefaultDataChunkWriterFactoryTest.kt`. Vier Achsen:

1. **Typ-Dispatch**: Jedes `DataExportFormat` erzeugt die korrekte
   Reader-Klasse.
2. **Parameter-Durchreichung**: `chunkSize` und `options` landen im
   Reader (L1-Vertrag).
3. **Validierung**: Nicht-positive `chunkSize` wird zentral in der
   Factory abgelehnt.
4. **Funktionaler Smoke-Test**: Der erzeugte Reader kann einen
   minimalen Input lesen und schließen, ohne zu werfen.

### 6.2 Testfälle

| # | Testname | Prüfung |
|---|---|---|
| 1 | `JSON format → JsonChunkReader` | `shouldBeInstanceOf<JsonChunkReader>()` |
| 2 | `YAML format → YamlChunkReader` | `shouldBeInstanceOf<YamlChunkReader>()` |
| 3 | `CSV format → CsvChunkReader` | `shouldBeInstanceOf<CsvChunkReader>()` |
| 4 | `JSON reader reads minimal input` | `create()` mit `[{"id":1}]` → `nextChunk()` liefert 1 Row, danach `null` |
| 5 | `YAML reader reads minimal input` | `create()` mit `- {id: 1}` → analog |
| 6 | `CSV reader reads minimal input` | `create()` mit `id\n1` → analog |
| 7 | `chunkSize is propagated to reader` | 3 JSON-Rows mit `chunkSize = 2` → Chunks 2, 1, `null` |
| 8 | `rejects non-positive chunkSize` | `chunkSize = 0` und `-1` werfen `IllegalArgumentException` |
| 9 | `options are propagated to CSV reader` | `csvNoHeader = true` → `headerColumns() == null` |
| 10 | `empty JSON array → nextChunk() returns null` | `create()` mit `[]` → `nextChunk() == null` |
| 11 | `close() without read does not throw` | `create()` → `close()` → kein Fehler |

### 6.3 Test-Skeleton

---

## 7. Coverage-Ziel

Die Klasse hat eine `require`-Validierung und einen `when`-Ausdruck mit
drei Zweigen. Die Tests decken den Validierungsfehler sowie alle drei
Format-Zweige ab → **100 % Branch/Line-Coverage**.

Gesamtziel pro Modul: ≥ 90 % (siehe
`d-migrate-formats/build.gradle.kts` und
`implementation-plan-0.4.0.md` §8.4).

---

## 8. Build & Verifizierung

```bash
# Nur d-migrate-formats testen (schneller Feedback-Loop)
docker build --target build \
  --build-arg GRADLE_TASKS=":d-migrate-formats:test" \
  -t d-migrate:phase-b10 .

# Vollständiger Build inkl. Coverage-Verification
docker build -t d-migrate:dev .
```

Verifiziert:

- `docker build --target build --build-arg GRADLE_TASKS=":d-migrate-formats:test" -t d-migrate:phase-b10 .`
- `docker build --target build --build-arg GRADLE_TASKS=":d-migrate-formats:test :d-migrate-formats:koverVerify --rerun-tasks" -t d-migrate:phase-b10 .`
- `docker build -t d-migrate:dev .`

---

## 9. Abnahmekriterien

- [x] `DefaultDataChunkReaderFactory` implementiert `DataChunkReaderFactory`
- [x] `when`-Dispatch ist exhaustiv (kein `else`-Zweig)
- [x] Alle 11 Tests grün
- [x] Coverage ≥ 90 % für `d-migrate-formats`
- [x] Docker-Build (`docker build -t d-migrate:dev .`) ist grün
- [x] Kein neuer State, kein Konstruktor-Parameter (reiner Dispatcher)

---

## 10. Offene Punkte

Keine. Die Implementierung ist umgesetzt und per Docker-Build verifiziert.
