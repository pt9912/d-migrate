# Spec: Approximate Profiling mit Apache DataSketches

Status: Draft  
Zielversion: 0.9.9 Spike, 1.0.0-RC produktiv  
Modulvorschlag: `d-migrate-profiling-datasketches`  
Kategorie: Optional Extension / Driven Adapter

---

## 1. Kontext

d-migrate unterstützt deterministisches Datenbank-Profiling über `data profile`.

Das bestehende Profiling erzeugt Tabellen- und Spaltenprofile mit Kennzahlen wie:

- Row Count
- Null Count
- Non-Null Count
- Distinct Count
- Top Values
- numerische Statistiken
- zeitliche Statistiken
- Zieltyp-Kompatibilitätschecks
- Qualitätswarnungen

Für große Tabellen können einige dieser Kennzahlen teuer werden, insbesondere:

- `COUNT(DISTINCT column)`
- Top-N-Werte
- Quantile wie Median, P95, P99
- Profiling über sehr große Streaming-Transfers
- Source-vs-Target-Plausibilitätschecks nach Migrationen

Apache DataSketches Java bietet probabilistische Streaming-Datenstrukturen für Kardinalitätsschätzung, Quantile, Frequent Items und Sampling.

Diese Spec beschreibt eine optionale Integration von Apache DataSketches Java als approximatives Profiling-Backend für d-migrate.

---

## 2. Ziel

Ziel ist ein optionales Profiling-Modul, das approximative, speicherschonende und streaming-fähige Spaltenstatistiken erzeugt.

Das Modul soll folgende Use Cases unterstützen:

1. Schnelles Profiling großer Tabellen.
2. Approximate Distinct Counts.
3. Approximate Quantiles.
4. Approximate Frequent Items.
5. Optionales Sampling.
6. Plausibilitätsvergleich zwischen Source- und Target-Daten nach Transfers.
7. Kleine, serialisierbare Profiling-Artefakte für CLI, REST, gRPC und MCP.
8. Verdichtete, KI-sichere Profil-Summaries ohne Rohdatenweitergabe.

---

## 3. Nicht-Ziele

DataSketches darf nicht verwendet werden für:

- DDL-Generierung.
- Schema-Reverse-Engineering.
- Schema-Diff-Wahrheit.
- Exakte Migrationserfolgsaussagen.
- Rechtlich oder fachlich verbindliche Datenintegritätsprüfung.
- Ersetzen von SHA-256-Artefaktprüfung.
- Ersetzen deterministischer Row Counts, Checksums oder Constraints.
- Heimliches Befüllen deterministischer Profilfelder mit Schätzwerten.

Approximative Werte müssen im Modell, in CLI-Ausgaben und in API-Antworten klar als approximativ gekennzeichnet sein.

---

## 4. Architektur

Die Integration folgt der hexagonalen Architektur von d-migrate.

### 4.1 Modulplatzierung

Vorgeschlagene Modulstruktur:

```text
hexagon:profiling
  Domain-Modell
  Profiling-Ports
  deterministische Profiling-Contracts
  Qualitätswarnungen
  Report-Modell
  Row-Streaming-Port

adapters:driven:profiling-datasketches
  Apache-DataSketches-basierte Approximationen
  Sketch-Konfiguration
  Sketch-Ausführung
  Sketch-Serialisierung
  Approximate-Stats-Mapping

adapters:driven:driver-postgresql-profiling
adapters:driven:driver-mysql-profiling
adapters:driven:driver-sqlite-profiling
  DB-spezifischer Zugriff
  exakte Profiling-Aggregate
  Row-Streaming / Cursor-Zugriff
  Dialekt-spezifische SQL-Optimierungen

adapters:driving:cli
adapters:driving:rest
adapters:driving:grpc
adapters:driving:mcp
  Aktivierung über Request-Parameter
  Job- und Artefaktverwaltung
```

### 4.2 Dependency-Regel

`profiling-datasketches` darf abhängig sein von:

```text
hexagon:profiling
org.apache.datasketches:datasketches-java
```

`hexagon:core` darf nicht abhängig sein von:

```text
org.apache.datasketches:datasketches-java
profiling-datasketches
```

`hexagon:profiling` darf nicht abhängig sein von:

```text
org.apache.datasketches:datasketches-java
```

Driver-Core-Module dürfen nicht abhängig sein von:

```text
org.apache.datasketches:datasketches-java
profiling-datasketches
```

Dialektspezifische Profiling-Adapter dürfen den neuen Row-Streaming-Port implementieren.

Die Sketch-Ausführung selbst liegt im optionalen DataSketches-Adapter.

---

## 5. Bestehendes Profiling-Modell und Kompatibilität

### 5.1 Problem

Das bestehende Profiling-Modell verwendet Pflichtwerte.

Insbesondere existiert im aktuellen Modell ein Pflichtfeld:

```kotlin
distinctCount: Long = 0
```

Ein approximativer Profiling-Modus darf deshalb nicht einfach `distinctCount = null` setzen.

Das wäre entweder:

1. ein Breaking Change, oder
2. semantisch falsch, weil `0` als echter exakter Wert missverstanden werden könnte.

### 5.2 Entscheidung

Das bestehende `ColumnProfile`-Modell bleibt für die erste DataSketches-Integration semantisch kompatibel:

- bestehende Felder werden nicht entfernt,
- bestehende Feldtypen werden nicht geändert,
- bestehende deterministische Felder behalten ihre deterministische Bedeutung,
- approximative Werte werden nur additiv ergänzt.

Wichtig: Das additive Erweitern einer Kotlin `data class` ist für bereits kompilierte Kotlin-/Java-Konsumenten nicht zwingend binär kompatibel, auch wenn neue Constructor-Parameter Defaults haben. Wenn d-migrate für diese Version harte Binärkompatibilität gegenüber extern kompilierten Artefakten garantieren muss, darf `ColumnProfile` selbst nicht erweitert werden. Dann muss ein separates Report-/DTO-Modell, z. B. `ColumnProfileReport`, die neuen Felder tragen.

Für die geplante erste Integration gilt deshalb:

```text
Source-/Report-Kompatibilität: ja
Keine semantische Überladung bestehender Felder: ja
Harte Binärkompatibilität für vorkompilierte Konsumenten: nur mit separatem DTO
```

Es gibt keine Änderung von:

```kotlin
distinctCount: Long
```

zu:

```kotlin
distinctCount: Long?
```

Approximative Statistiken werden additiv modelliert.

### 5.3 Erweiterung von `ColumnProfile`

Die Erweiterung muss auf dem aktuellen Modell aus `hexagon:profiling` basieren. Die folgende Skizze zeigt nur die relevanten Felder und lässt keine bestehenden Felder weg.

```kotlin
data class ColumnProfile(
    val name: String,
    val dbType: String,
    val logicalType: LogicalType,
    val nullable: Boolean,

    val rowCount: Long,
    val nonNullCount: Long,
    val nullCount: Long,
    val emptyStringCount: Long = 0,
    val blankStringCount: Long = 0,

    // Bleibt deterministisch.
    // Darf im Approximate/Hybrid Mode nicht mit Sketch-Schätzwerten befüllt werden.
    val distinctCount: Long = 0,
    val duplicateValueCount: Long = 0,

    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minValue: String? = null,
    val maxValue: String? = null,

    val topValues: List<ValueFrequency> = emptyList(),
    val numericStats: NumericStats? = null,
    val temporalStats: TemporalStats? = null,
    val targetCompatibility: List<TargetTypeCompatibility> = emptyList(),
    val warnings: List<ProfileWarning> = emptyList(),

    // Neu, additiv, optional.
    val approximateStats: ApproximateColumnStats? = null,

    // Neu, additiv.
    // Beschreibt, welche bestehenden Pflichtfelder tatsächlich berechnet wurden.
    // Default ist Exact-Legacy-kompatibel, damit bestehende Exact-Pfade nicht
    // versehentlich NOT_COMPUTED reporten.
    val computedMetrics: ComputedMetrics = ComputedMetrics.exactLegacy()
)
```

### 5.4 Erweiterung von `TableProfile`

Da Reports auch Tabellenmetriken als berechnet oder nicht berechnet kennzeichnen, erhält `TableProfile` analog ein additives Feld. Für harte Binärkompatibilität gilt dieselbe DTO-Regel wie bei `ColumnProfile`.

```kotlin
data class TableProfile(
    val name: String,
    val schema: String? = null,
    val rowCount: Long,
    val columns: List<ColumnProfile>,
    val warnings: List<ProfileWarning> = emptyList(),

    // Neu, additiv.
    val computedMetrics: TableComputedMetrics = TableComputedMetrics.exactLegacy()
)
```

```kotlin
data class TableComputedMetrics(
    val rowCount: MetricComputation = MetricComputation.EXACT
) {
    companion object {
        fun exactLegacy(): TableComputedMetrics = TableComputedMetrics()

        fun approximateDefaults(): TableComputedMetrics = TableComputedMetrics(
            rowCount = MetricComputation.NOT_COMPUTED
        )
    }
}
```

### 5.5 `ComputedMetrics`

```kotlin
data class ComputedMetrics(
    val rowCount: MetricComputation = MetricComputation.EXACT,
    val nullCount: MetricComputation = MetricComputation.EXACT,
    val nonNullCount: MetricComputation = MetricComputation.EXACT,
    val emptyStringCount: MetricComputation = MetricComputation.EXACT,
    val blankStringCount: MetricComputation = MetricComputation.EXACT,
    val distinctCount: MetricComputation = MetricComputation.EXACT,
    val duplicateValueCount: MetricComputation = MetricComputation.EXACT,
    val minLength: MetricComputation = MetricComputation.EXACT,
    val maxLength: MetricComputation = MetricComputation.EXACT,
    val minValue: MetricComputation = MetricComputation.EXACT,
    val maxValue: MetricComputation = MetricComputation.EXACT,
    val topValues: MetricComputation = MetricComputation.EXACT,
    val numericStats: MetricComputation = MetricComputation.NOT_COMPUTED,
    val temporalStats: MetricComputation = MetricComputation.NOT_COMPUTED,
    val targetCompatibility: MetricComputation = MetricComputation.NOT_COMPUTED
) {
    companion object {
        fun exactLegacy(): ComputedMetrics = ComputedMetrics()

        fun approximateDefaults(): ComputedMetrics = ComputedMetrics(
            rowCount = MetricComputation.NOT_COMPUTED,
            nullCount = MetricComputation.NOT_COMPUTED,
            nonNullCount = MetricComputation.NOT_COMPUTED,
            emptyStringCount = MetricComputation.NOT_COMPUTED,
            blankStringCount = MetricComputation.NOT_COMPUTED,
            distinctCount = MetricComputation.NOT_COMPUTED,
            duplicateValueCount = MetricComputation.NOT_COMPUTED,
            minLength = MetricComputation.NOT_COMPUTED,
            maxLength = MetricComputation.NOT_COMPUTED,
            minValue = MetricComputation.NOT_COMPUTED,
            maxValue = MetricComputation.NOT_COMPUTED,
            topValues = MetricComputation.NOT_COMPUTED,
            numericStats = MetricComputation.NOT_COMPUTED,
            temporalStats = MetricComputation.NOT_COMPUTED,
            targetCompatibility = MetricComputation.NOT_COMPUTED
        )
    }
}
```

```kotlin
enum class MetricComputation {
    NOT_COMPUTED,
    EXACT,
    APPROXIMATE,
    DERIVED
}
```

Pflichtzählwerte defaulten im Legacy-Pfad auf `EXACT`, damit bestehende Exact-Profile nicht nachträglich falsch als nicht berechnet erscheinen. Optionale Metriken wie `numericStats`, `temporalStats` und `targetCompatibility` müssen vom Service oder Report-Mapper explizit auf `EXACT` gesetzt werden, wenn sie tatsächlich berechnet und ausgegeben werden.

### 5.6 Semantik von `distinctCount`

Im Exact Mode:

```yaml
distinctCount: 12345
computedMetrics:
  distinctCount: EXACT
```

Im Approximate Mode:

```yaml
distinctCount: 0
computedMetrics:
  distinctCount: NOT_COMPUTED
approximateStats:
  distinctEstimate:
    estimate: 12345
    lowerBound: 12200
    upperBound: 12500
```

Im Hybrid Mode, wenn exakte Distinct Counts bewusst deaktiviert sind:

```yaml
distinctCount: 0
computedMetrics:
  distinctCount: NOT_COMPUTED
approximateStats:
  distinctEstimate:
    estimate: 12345
```

### 5.7 Kompatibilitätsregel

Consumer dürfen `distinctCount = 0` nur dann als echten Wert interpretieren, wenn gilt:

```text
computedMetrics.distinctCount == EXACT
```

Falls `computedMetrics` in alten Reports fehlt, gilt für Legacy-Reports:

```text
computedMetrics.distinctCount = EXACT
```

Diese Legacy-Regel stellt Rückwärtskompatibilität sicher.

### 5.8 Alternative für spätere Major-Version

Für eine spätere Major-Version darf das Modell stärker typisiert werden:

```kotlin
sealed interface ProfileMetric<out T> {
    data class Exact<T>(
        val value: T
    ) : ProfileMetric<T>

    data class Approximate<T>(
        val estimate: T,
        val lowerBound: T?,
        val upperBound: T?
    ) : ProfileMetric<T>

    data object NotComputed : ProfileMetric<Nothing>
}
```

Diese Änderung ist nicht Teil der ersten DataSketches-Integration.

---

## 6. Profiling-Modi

Das Profiling erhält einen expliziten Modus.

```text
exact
  deterministisches Profiling

approximate
  approximatives Profiling über Sketches

hybrid
  exakte günstige Kennzahlen + approximative teure Kennzahlen
```

### 6.1 Exact Mode

Der Exact Mode bleibt das bestehende Verhalten.

Beispiele:

- Exakter Row Count.
- Exakter Null Count.
- Exakter Distinct Count, sofern aktiviert.
- Exakte Min-/Max-Werte.
- Exakte Top-N-Werte, sofern berechnet.

### 6.2 Approximate Mode

Der Approximate Mode nutzt Sketches für unterstützte Statistiken.

Beispiele:

- Approximate Distinct Count.
- Approximate Frequent Items.
- Approximate Quantiles.
- Optionales Approximate Sampling.

Bestehende deterministische Felder dürfen in diesem Modus nur dann als `EXACT` markiert werden, wenn sie tatsächlich exakt berechnet wurden.

### 6.3 Hybrid Mode

Der Hybrid Mode ist der empfohlene Modus für große Datenbestände.

Beispiele:

- `rowCount`: exakt, falls kostengünstig verfügbar.
- `nullCount`: exakt, falls kostengünstig verfügbar.
- `min/max`: exakt, falls über DB-Aggregate billig.
- `distinctCount`: deterministisches Feld bleibt `NOT_COMPUTED`, wenn nur Sketch-Schätzung vorliegt.
- `topValues`: deterministisches Feld bleibt `NOT_COMPUTED`, wenn nur Sketch-Schätzung vorliegt.
- `quantiles`: approximativ unter `approximateStats`.

---

## 7. Streaming-Port und Adapter-Zuschnitt

### 7.1 Problem

Der bestehende `ProfilingDataPort` ist auf exakte SQL-Aggregate ausgelegt.

DataSketches benötigt dagegen Zugriff auf Zeilenwerte, Cursor oder einen Row-Stream.

Sketches können deshalb nicht sauber ausschließlich gegen den bestehenden `ProfilingDataPort` implementiert werden.

### 7.2 Entscheidung

Es wird ein neuer optionaler Streaming-Port eingeführt.

```text
hexagon:profiling
  ProfilingDataPort
    bestehend
    SQL-Aggregate
    deterministisches Profiling

  ProfilingRowStreamPort
    neu
    streaming-basiertes Profiling
    Grundlage für Sketch-Ausführung
```

### 7.3 Neuer Port

```kotlin
interface ProfilingRowStreamPort {
    fun streamRows(
        pool: dev.dmigrate.driver.connection.ConnectionPool,
        request: ProfilingRowStreamRequest
    ): ProfilingRowStream
}
```

```kotlin
data class ProfilingRowStreamRequest(
    val schema: String?,
    val table: String,
    val columns: List<String>,
    val fetchSize: Int = 10_000,
    val limit: Long? = null,
    val filter: ProfilingRowFilter? = null
)
```

Der Port verwendet wie die bestehenden Profiling-Ports einen `ConnectionPool`. Die Auflösung von CLI-`--source`, REST-/gRPC-/MCP-`connectionId` oder tenant-scoped Connection-Refs bleibt Aufgabe der Driving-Schicht beziehungsweise Application-Wiring-Schicht.

Raw-SQL-Fragmente sind im Port-Vertrag nicht erlaubt. Falls Filter benötigt werden, müssen sie strukturiert modelliert und vom jeweiligen Adapter mit bestehenden Identifier-/Literal-Quoting-Regeln serialisiert werden.

```kotlin
sealed interface ProfilingRowFilter {
    data class Equals(
        val column: String,
        val value: ProfileValue
    ) : ProfilingRowFilter

    data class And(
        val filters: List<ProfilingRowFilter>
    ) : ProfilingRowFilter
}
```

```kotlin
interface ProfilingRowStream : AutoCloseable {
    fun next(): ProfilingRow?
}
```

```kotlin
data class ProfilingRow(
    val values: Map<String, ProfileValue?>
)
```

### 7.4 Adapter-Verantwortung

Dialektspezifische Profiling-Adapter implementieren den Streaming-Port:

```text
driver-postgresql-profiling
  implements ProfilingDataPort
  implements ProfilingRowStreamPort

driver-mysql-profiling
  implements ProfilingDataPort
  implements ProfilingRowStreamPort

driver-sqlite-profiling
  implements ProfilingDataPort
  implements ProfilingRowStreamPort
```

`profiling-datasketches` konsumiert den neuen Port:

```text
profiling-datasketches -> hexagon:profiling
profiling-datasketches -> ProfilingRowStreamPort
profiling-datasketches -> org.apache.datasketches:datasketches-java
```

### 7.5 Fallback-Regel

Wenn kein `ProfilingRowStreamPort` verfügbar ist:

```text
profile-mode approximate:
  Fehler mit klarer Meldung

profile-mode hybrid:
  approximative Metriken werden übersprungen
  Warning SKETCH_STREAMING_PORT_UNAVAILABLE
```

Beispiel-Warning:

```yaml
warnings:
  - code: SKETCH_STREAMING_PORT_UNAVAILABLE
    severity: WARNING
    message: "Approximate profiling was requested, but no row streaming port is available for this connection."
```

---

## 8. Sketch-Zuordnung

| Profiling-Ziel             | DataSketches-Familie           | Zweck                   |
| -------------------------- | ------------------------------ | ----------------------- |
| Distinct Count             | HLL oder CPC                   | Approximate Cardinality |
| Top-N / Heavy Hitters      | Frequent Items                 | Häufige Werte           |
| Median / P90 / P95 / P99   | KLL, Quantiles oder t-digest   | Quantile                |
| Stichproben                | Reservoir oder VarOpt Sampling | Beispielwerte           |
| Source-vs-Target-Vergleich | HLL/CPC, KLL, Frequent Items   | Plausibilitätsprüfung   |

### 8.1 Default-Empfehlung

```text
Distinct Count:
  Default: HLL
  Optional: CPC

Quantiles:
  Default: KLL
  Optional: t-digest

Frequent Items:
  Default: Frequent Items

Sampling:
  Default: deaktiviert
```

### 8.2 Unterstützte Spaltentypen

| Sketch         | Unterstützte Typen                                                           |
| -------------- | ---------------------------------------------------------------------------- |
| HLL/CPC        | String, Number, Boolean, Date/Time, UUID, Binary mit stabiler Normalisierung |
| KLL            | numerische Typen, Date/Time nach expliziter numerischer Normalisierung       |
| Frequent Items | String, Number, Boolean, Date/Time, Enum-ähnliche Werte                      |
| Sampling       | alle typisierten `ProfileValue`-Werte, sofern Datenschutzregeln erfüllt sind |

Unsupported Types erzeugen Warnings statt harter Fehler, außer der Nutzer verlangt explizit `--fail-on-unsupported-sketch`.

### 8.3 Fehler- und Confidence-Semantik

Die Optionen `--relative-error` und `--confidence` sind User-Ziele, aber nicht jede Sketch-Familie kann beide Werte direkt oder mathematisch gleichwertig garantieren. Die Implementierung muss pro Sketch dokumentieren, wie Request-Optionen auf konkrete DataSketches-Parameter und Report-Felder gemappt werden.

| Sketch  | Parameter-Mapping | Report-Bounds | `confidence` im Report |
| ------- | ----------------- | ------------- | ---------------------- |
| HLL/CPC | `relativeError` wird auf `lgK` und Sketch-Familie gemappt; Implementierung wählt `numStdDev` passend zur angeforderten Confidence, soweit unterstützt | `lowerBound`/`upperBound` aus DataSketches-Bounds | erlaubt, wenn auf `numStdDev` 1..3 abbildbar; sonst weglassen und Warning erzeugen |
| KLL     | `relativeError` wird auf `k` beziehungsweise den normalisierten Rank Error gemappt | Quantile-Bounds sind Rank-Error-Bounds, keine Value-Error-Garantie | nur reporten, wenn die verwendete API eine dokumentierte Confidence für den Bound liefert; sonst weglassen |
| t-digest | `relativeError`/`confidence` sind Zielwerte, aber keine harte Garantie | keine mathematisch garantierten Bounds; empirische Kennzahlen nur als Benchmark-/Metadata-Werte | nicht reporten; stattdessen `confidence = null` und Warning/Metadata zur empirischen Natur |
| Frequent Items | Fehler hängt von Stream-Länge, Sketch-Konfiguration und Familie ab | `lowerBound`/`upperBound` nur reporten, wenn die Sketch-API sie liefert | nicht pauschal reporten |
| Sampling | Seed und Sample-Größe steuern Reproduzierbarkeit und Umfang | keine Fehler-Bounds | nicht reporten |

Wenn ein angefragtes `relativeError`-/`confidence`-Ziel nicht umgesetzt werden kann, muss die Implementierung entweder:

- eine klare Konfigurations-Warning erzeugen und die tatsächlich verwendeten Parameter in `sketchMetadata.parameters` schreiben, oder
- bei strikt validierender Konfiguration mit Vertragsfehler abbrechen.

Approximative Report-Felder dürfen keine Genauigkeit suggerieren, die die jeweilige Sketch-Familie nicht liefert.

---

## 9. Domain-Modell für approximative Werte

### 9.0 `ProfileValue`

Approximate Profiling benötigt eine typisierte Wertrepräsentation für Streaming, Hashing, Frequent Items, Sampling und Redaction. Dieser Typ existiert im aktuellen Profiling-Modell noch nicht und muss als Teil der DataSketches-Integration eingeführt werden.

```kotlin
sealed interface ProfileValue {
    val normalizedText: String

    data class StringValue(
        val value: String,
        override val normalizedText: String = value
    ) : ProfileValue

    data class LongValue(
        val value: Long
    ) : ProfileValue {
        override val normalizedText: String = value.toString()
    }

    data class DecimalValue(
        val value: String
    ) : ProfileValue {
        override val normalizedText: String = value
    }

    data class DoubleValue(
        val value: Double
    ) : ProfileValue {
        override val normalizedText: String = normalizeDouble(value)
    }

    data class BooleanValue(
        val value: Boolean
    ) : ProfileValue {
        override val normalizedText: String = value.toString()
    }

    data class TemporalValue(
        val iso8601: String
    ) : ProfileValue {
        override val normalizedText: String = iso8601
    }

    data class BinaryValue(
        val sha256: String,
        val sizeBytes: Long? = null
    ) : ProfileValue {
        override val normalizedText: String = sha256
    }

    data object Unsupported : ProfileValue {
        override val normalizedText: String = "<unsupported>"
    }
}
```

Regeln:

- `normalizedText` muss locale-unabhängig und deterministisch sein.
- Decimal-Werte werden nicht über `Double` normalisiert, sondern als kanonischer Dezimalstring.
- Double-Werte werden zentral normalisiert:
  - `NaN`, `Infinity` und `-Infinity` erhalten feste Textformen.
  - `-0.0` wird zu `0.0` normalisiert.
  - endliche Werte werden mit `Double.toString()` nach vorheriger `-0.0`-Normalisierung serialisiert; keine locale-spezifische Formatierung.
- Date/Time-Werte werden mit expliziter Zeitzonen-/Precision-Regel normalisiert.
- Binary-Werte werden nicht roh in Reports geschrieben; für HLL/CPC wird eine kontrollierte Hash-Strategie verwendet.
- YAML-/JSON-Serializer dürfen einfache skalare Kurzformen ausgeben, wenn Typ und Redaction eindeutig bleiben.

```kotlin
fun normalizeDouble(value: Double): String =
    when {
        value.isNaN() -> "NaN"
        value == Double.POSITIVE_INFINITY -> "Infinity"
        value == Double.NEGATIVE_INFINITY -> "-Infinity"
        value == 0.0 -> "0.0"
        else -> value.toString()
    }
```

### 9.1 `ApproximateColumnStats`

```kotlin
data class ApproximateColumnStats(
    val distinctEstimate: ApproximateLong? = null,
    val frequentItems: List<ApproximateValueFrequency> = emptyList(),
    val quantiles: ApproximateQuantiles? = null,
    val samples: List<SampleValue> = emptyList(),
    val sketchMetadata: List<SketchMetadata> = emptyList()
)
```

### 9.2 `ApproximateLong`

```kotlin
data class ApproximateLong(
    val estimate: Long,
    val lowerBound: Long? = null,
    val upperBound: Long? = null,
    val relativeErrorTarget: Double? = null,
    val confidence: Double? = null
)
```

DataSketches-HLL/CPC liefert Schätzwerte und Bounds als Fließkommazahlen. Für das ganzzahlige Profiling-Modell gelten deshalb feste Rundungsregeln:

```text
estimate: round(estimate)
lowerBound: floor(lowerBound)
upperBound: ceil(upperBound)
```

Damit bleibt der gerundete Report konservativ: Der untere Bound wird nicht zu hoch und der obere Bound nicht zu niedrig ausgewiesen. Wenn eine Familie keine sinnvollen Bounds liefert, bleiben `lowerBound` und `upperBound` `null`.

### 9.3 `ApproximateValueFrequency`

```kotlin
data class ApproximateValueFrequency(
    val value: ProfileValue,
    val estimate: Long,
    val lowerBound: Long? = null,
    val upperBound: Long? = null,
    val error: Long? = null
)
```

### 9.4 `ApproximateQuantiles`

```kotlin
data class ApproximateQuantiles(
    val min: Double? = null,
    val p01: Double? = null,
    val p05: Double? = null,
    val p10: Double? = null,
    val p25: Double? = null,
    val p50: Double? = null,
    val p75: Double? = null,
    val p90: Double? = null,
    val p95: Double? = null,
    val p99: Double? = null,
    val max: Double? = null,
    val normalizedRankError: Double? = null
)
```

### 9.5 `SampleValue`

```kotlin
data class SampleValue(
    val value: ProfileValue,
    val ordinal: Long? = null,
    val source: SampleSource = SampleSource.SKETCH,
    val redacted: Boolean = false
)
```

```kotlin
enum class SampleSource {
    SKETCH,
    DATABASE,
    DERIVED
}
```

### 9.6 `SketchMetadata`

```kotlin
data class SketchMetadata(
    val metric: String,
    val family: String,
    val implementation: String,
    val version: String?,
    val parameters: Map<String, String> = emptyMap(),
    val serializedArtifactId: String? = null,
    val privacyClass: SketchPrivacyClass? = null,
    val mode: ApproximationMode = ApproximationMode.APPROXIMATE
)
```

### 9.7 `ApproximationMode`

```kotlin
enum class ApproximationMode {
    EXACT,
    APPROXIMATE,
    HYBRID
}
```

---

## 10. YAML-/JSON-Ausgabe

### 10.1 Beispiel: Approximate Distinct Count

```yaml
table: orders
rowCount: 12893422
computedMetrics:
  rowCount: EXACT

columns:
  - name: customer_id
    dbType: bigint
    logicalType: INTEGER
    nullCount: 0
    nonNullCount: 12893422
    distinctCount: 0
    computedMetrics:
      nullCount: EXACT
      nonNullCount: EXACT
      distinctCount: NOT_COMPUTED
    approximateStats:
      distinctEstimate:
        estimate: 831204
        lowerBound: 822000
        upperBound: 840500
        relativeErrorTarget: 0.01
        confidence: 0.95
      sketchMetadata:
        - metric: distinct
          family: HLL
          implementation: apache-datasketches-java
          version: "7.0.1"
          parameters:
            lgK: "12"
            numStdDev: "2"
            boundConfidence: "0.95"
```

### 10.2 Beispiel: Approximate Quantiles

```yaml
table: orders
columns:
  - name: amount
    dbType: numeric
    logicalType: DECIMAL
    numericStats:
      min: 0.0
      max: 98123.5
    computedMetrics:
      numericStats: EXACT
    approximateStats:
      quantiles:
        min: 0.0
        p50: 44.9
        p90: 181.3
        p95: 318.2
        p99: 901.7
        max: 98123.5
        normalizedRankError: 0.0133
      sketchMetadata:
        - metric: quantiles
          family: KLL
          implementation: apache-datasketches-java
          version: "7.0.1"
          parameters:
            k: "200"
```

### 10.3 Beispiel: Approximate Frequent Items

```yaml
table: orders
columns:
  - name: status
    dbType: varchar
    logicalType: STRING
    computedMetrics:
      topValues: NOT_COMPUTED
    approximateStats:
      frequentItems:
        - value: PAID
          estimate: 7123000
          lowerBound: 7119000
          upperBound: 7129000
        - value: OPEN
          estimate: 2101000
          lowerBound: 2098000
          upperBound: 2107000
        - value: CANCELLED
          estimate: 901000
          lowerBound: 899000
          upperBound: 905000
      sketchMetadata:
        - metric: frequentItems
          family: FREQUENT_ITEMS
          implementation: apache-datasketches-java
          version: "7.0.1"
```

---

## 11. CLI-Erweiterung

### 11.1 Neue Optionen

```text
--profile-mode exact|approximate|hybrid
--sketches distinct,quantiles,frequent-items,sampling
--relative-error <double>
--confidence <double>
--sketch-artifacts true|false
--sketch-artifact-policy disabled|value-free-only|value-derived-only|allow-value-bearing
--max-sketch-bytes <bytes>
--sampling-seed <long>
```

### 11.2 Beispiele

Exact Mode:

```bash
d-migrate data profile \
  --source prod \
  --tables users,orders \
  --profile-mode exact \
  --output profile.yaml
```

Approximate Mode:

```bash
d-migrate data profile \
  --source prod \
  --tables users,orders \
  --profile-mode approximate \
  --sketches distinct,quantiles,frequent-items \
  --relative-error 0.01 \
  --confidence 0.95 \
  --output profile.yaml
```

Hybrid Mode:

```bash
d-migrate data profile \
  --source prod \
  --tables users,orders \
  --profile-mode hybrid \
  --sketches distinct,quantiles,frequent-items \
  --output profile.yaml
```

Mit Sketch-Artefakten:

```bash
d-migrate data profile \
  --source prod \
  --tables orders \
  --profile-mode approximate \
  --sketch-artifacts true \
  --sketch-artifact-policy value-derived-only \
  --output profile.yaml
```

### 11.3 CLI-Semantik von `--source`

CLI darf weiterhin einen nutzerfreundlichen Alias wie `--source` verwenden.

Intern muss dieser Alias auf eine Connection aufgelöst werden.

API-Verträge verwenden dagegen `connectionId`.

---

## 12. REST API

### 12.1 Request

Asynchrone Job-Starts müssen den bestehenden Idempotency-Vertrag einhalten.

```http
POST /api/v1/data/profile
Idempotency-Key: 8f7f6b0e-9c5d-4f8d-98a2-5a0e2f7d9e11
Content-Type: application/json
```

```json
{
  "connectionId": "conn_prod",
  "tables": ["orders", "customers"],
  "format": "yaml",
  "profileMode": "hybrid",
  "approximate": {
    "enabled": true,
    "sketches": ["distinct", "quantiles", "frequent-items"],
    "relativeError": 0.01,
    "confidence": 0.95,
    "includeSketchArtifacts": false,
    "sketchArtifactPolicy": "VALUE_DERIVED_ONLY",
    "maxSketchBytes": 10485760
  }
}
```

### 12.2 Response

```json
{
  "jobId": "job_01HX...",
  "status": "accepted",
  "kind": "data.profile",
  "artifactHints": [
    "profile.yaml"
  ]
}
```

### 12.3 Fehlender Idempotency-Key

Fehlt der Header bei einem asynchronen Job-Start, muss die API mit einem Vertragsfehler antworten.

```json
{
  "error": {
    "code": "IDEMPOTENCY_KEY_REQUIRED",
    "message": "Idempotency-Key header is required for asynchronous job starts."
  }
}
```

### 12.4 `source` nicht neu einführen

Die REST API verwendet:

```text
connectionId
```

Nicht:

```text
source
```

---

## 13. gRPC API

### 13.1 Request-Erweiterung

```proto
message ProfileDataRequest {
  string connection_id = 1;
  repeated string tables = 2;
  string schema = 3;
  ProfileOutputFormat format = 4;
  ProfileMode profile_mode = 5;
  ApproximateProfilingOptions approximate = 6;
}
```

```proto
enum ProfileMode {
  PROFILE_MODE_UNSPECIFIED = 0;
  PROFILE_MODE_EXACT = 1;
  PROFILE_MODE_APPROXIMATE = 2;
  PROFILE_MODE_HYBRID = 3;
}
```

```proto
message ApproximateProfilingOptions {
  bool enabled = 1;
  repeated SketchMetric sketches = 2;
  double relative_error = 3;
  double confidence = 4;
  bool include_sketch_artifacts = 5;
  int64 max_sketch_bytes = 6;
  SketchArtifactPolicy sketch_artifact_policy = 7;
  optional int64 sampling_seed = 8;
}
```

```proto
enum SketchMetric {
  SKETCH_METRIC_UNSPECIFIED = 0;
  SKETCH_METRIC_DISTINCT = 1;
  SKETCH_METRIC_QUANTILES = 2;
  SKETCH_METRIC_FREQUENT_ITEMS = 3;
  SKETCH_METRIC_SAMPLING = 4;
}
```

```proto
enum SketchArtifactPolicy {
  SKETCH_ARTIFACT_POLICY_UNSPECIFIED = 0;
  SKETCH_ARTIFACT_POLICY_DISABLED = 1;
  SKETCH_ARTIFACT_POLICY_VALUE_FREE_ONLY = 2;
  SKETCH_ARTIFACT_POLICY_VALUE_DERIVED_ONLY = 3;
  SKETCH_ARTIFACT_POLICY_ALLOW_VALUE_BEARING = 4;
}
```

---

## 14. MCP Integration

### 14.1 Bestehender Tool-Vertrag

`data_profile_start` verwendet den bestehenden `connectionId`-basierten Job-Start.

Die Spec führt keinen künstlichen Tool-Envelope ein.

Falsch:

```json
{
  "tool": "data_profile_start",
  "arguments": {}
}
```

Richtig ist die Beschreibung der Tool-Argumente.

### 14.2 Tool-Argumente

```json
{
  "connectionId": "conn_prod",
  "tables": ["orders"],
  "profileMode": "hybrid",
  "approximate": {
    "enabled": true,
    "sketches": ["distinct", "quantiles", "frequent-items"],
    "relativeError": 0.01,
    "confidence": 0.95,
    "includeSketchArtifacts": false,
    "sketchArtifactPolicy": "VALUE_DERIVED_ONLY"
  }
}
```

### 14.3 Tool Response

```json
{
  "jobId": "job_01HX...",
  "status": "accepted",
  "resourceUri": "d-migrate://jobs/job_01HX"
}
```

Profiling-Ergebnisse werden anschließend über bestehende Job-/Artifact-Ressourcen gelesen.

### 14.4 LLM-sichere Summary

Für KI-Integrationen dürfen keine Rohdatenwerte weitergegeben werden, wenn dies nicht explizit erlaubt ist.

Erlaubt:

```json
{
  "table": "orders",
  "column": "amount",
  "summary": {
    "type": "DECIMAL",
    "approximateQuantiles": {
      "p50": 44.9,
      "p95": 318.2,
      "p99": 901.7
    },
    "warnings": [
      "high_outlier_range"
    ]
  }
}
```

Nicht erlaubt ohne explizite Freigabe:

```json
{
  "sampleRows": [
    {
      "customer_email": "alice@example.org"
    }
  ]
}
```

---

## 15. Determinismus und reproduzierbare Reports

### 15.1 Problem

Die CLI-Spec fordert vergleichbare beziehungsweise identische Reports.

Laufzeitvariable Felder wie `generatedAt` verletzen diesen Vertrag.

Auch Sampling und Sketch-Merge-Reihenfolge können nichtdeterministisch werden, wenn Seed, Sortierung und Merge-Reihenfolge nicht festgelegt sind.

### 15.2 Entscheidung

DataSketches-Profiling muss deterministische Report-Ausgaben unterstützen.

### 15.3 Kein `generatedAt` im stabilen Report

Dieses Feld ist im stabilen Report verboten:

```yaml
generatedAt: "2026-05-04T10:15:00Z"
```

Erlaubt ist es nur in Job-Metadaten oder Artefakt-Metadaten, nicht im vergleichbaren Profiling-Report.

Korrigierter Report:

```yaml
database: prod
profileMode: hybrid
tables:
  - name: orders
    rowCount: 12893422
```

Job-Metadaten dürfen Zeitstempel enthalten:

```yaml
job:
  id: job_01HX...
  createdAt: "2026-05-04T10:15:00Z"
```

Diese Metadaten dürfen nicht Teil des deterministischen `profile.yaml` sein.

### 15.4 Deterministische Reihenfolge

Reports müssen stabil sortiert werden:

```text
tables:
  sort by schema, tableName

columns:
  sort by ordinal position from database metadata
  fallback sort by columnName

frequentItems:
  sort by estimate desc
  then by normalized value asc

warnings:
  sort by severity desc
  then by code asc
  then by path asc

sketchMetadata:
  sort by metric asc
```

Damit diese Regel implementierbar ist, muss die Profiling-Introspection die Ordinalposition liefern:

```kotlin
data class ColumnSchema(
    val name: String,
    val dbType: String,
    val nullable: Boolean,
    val ordinalPosition: Int? = null,
    val isPrimaryKey: Boolean = false,
    val isForeignKey: Boolean = false,
    val isUnique: Boolean = false
)
```

Adapter müssen `ordinalPosition` setzen, wenn der Dialekt sie zuverlässig liefert. Wenn sie fehlt, sortiert der Report-Serializer deterministisch nach `columnName`.

### 15.5 Deterministische Sampling-Regel

Sampling ist nur dann in vergleichbaren Reports erlaubt, wenn ein stabiler Seed gesetzt ist.

```yaml
profiling:
  approximate:
    sampling:
      enabled: true
      seed: 123456789
```

Ohne Seed gilt:

```text
sampling darf nicht im stabilen Report erscheinen
```

Alternativ darf Sampling nur als nichtdeterministisches separates Artefakt erzeugt werden:

```text
samples.json
```

Dieses Artefakt ist dann nicht Teil der Report-Vergleichbarkeit.

### 15.6 Deterministische Sketch-Merge-Reihenfolge

Wenn paralleles Profiling Sketches pro Partition erzeugt, muss die Merge-Reihenfolge stabil sein.

Regel:

```text
sort partitions by:
  schema
  table
  partitionStart
  partitionEnd
  partitionOrdinal
```

Danach erst mergen.

### 15.7 Floating-Point-Ausgabe

Numerische Ausgabe muss normalisiert werden:

```text
decimal separator: "."
no locale-specific formatting
fixed max scale per metric
no scientific notation unless explicitly configured
```

Die konkrete Formatierungsregel muss global im Profiling-Serializer definiert werden.

---

## 16. Artefakte

### 16.1 Profil-Artefakte

Ein Profiling-Job kann folgende Artefakte erzeugen:

```text
profile.yaml
profile.json
summary.json
warnings.json
```

### 16.2 Sketch-Artefakte

Sketch-Artefakte sind optional.

Sie dürfen nur erzeugt werden, wenn sie explizit aktiviert sind:

```text
--sketch-artifacts true
```

oder die entsprechende API-Option aktiv ist.

Mögliche Artefakte:

```text
sketches/*.bin
```

### 16.3 Artefakt-Metadaten

Jedes Sketch-Artefakt muss enthalten:

```yaml
artifactId: artifact_01HX...
kind: sketch
metric: distinct
family: HLL
implementation: apache-datasketches-java
version: "7.0.1"
table: orders
column: customer_id
contentType: application/octet-stream
sha256: "..."
sizeBytes: 8192
createdAt: "2026-05-04T10:15:00Z"
```

`createdAt` ist in Artefakt-Metadaten erlaubt, aber nicht im stabilen Profiling-Report.

---

## 17. Datenschutz und Sketch-Artefakte

### 17.1 Problem

Serialisierte Sketches können werttragend sein.

Insbesondere können folgende Sketches Rohwerte oder rekonstruierbare Werte enthalten:

```text
KLL / Quantiles
Frequent Items
Sampling
```

Auch wenn der Report maskiert ist, kann ein binäres Sketch-Artefakt sensible Werte enthalten.

### 17.2 Sketch Privacy-Klassifikation

Jeder Sketch-Typ erhält eine Privacy-Klasse.

```kotlin
enum class SketchPrivacyClass {
    VALUE_FREE,
    VALUE_DERIVED,
    VALUE_BEARING
}
```

Vorgeschlagene Klassifikation:

| Sketch                               | Privacy-Klasse | Begründung                                                      |
| ------------------------------------ | -------------- | --------------------------------------------------------------- |
| HLL/CPC für gehashte Werte           | VALUE_DERIVED  | enthält keine direkt lesbaren Originalwerte, aber Ableitungen   |
| HLL/CPC für rohe serialisierte Werte | VALUE_BEARING  | abhängig von Encoding/Update-Strategie                          |
| KLL numerisch                        | VALUE_DERIVED  | enthält Verteilungsinformation, kann sensible Zahlen offenlegen |
| KLL für Strings                      | VALUE_BEARING  | falls unterstützt, enthält wertnahe Daten                       |
| Frequent Items                       | VALUE_BEARING  | enthält häufige Originalwerte                                   |
| Sampling                             | VALUE_BEARING  | enthält Rohwerte                                                |
| Theta mit Rohobjekten                | VALUE_BEARING  | kann werttragend sein                                           |

### 17.3 Default-Regel für Sketch-Artefakte

Sketch-Artefakte sind standardmäßig deaktiviert.

```yaml
profiling:
  approximate:
    artifacts:
      enabled: false
```

Selbst wenn sie aktiviert werden, gelten zusätzliche Privacy-Regeln.

### 17.4 Verbot für sensible Spalten

Für sensible Spalten dürfen werttragende Sketch-Artefakte nicht erzeugt werden.

Sensible Spalten können erkannt werden durch:

```text
explizite Konfiguration
Name Pattern
Profiling-Warnings
Type Hints
Policy Tags
```

Beispiele:

```yaml
profiling:
  privacy:
    sensitiveColumns:
      - email
      - phone
      - iban
      - ssn
      - birth_date
```

Für diese Spalten gilt:

```text
Frequent Items Artifact: verboten
Sampling Artifact: verboten
KLL Artifact: verboten, wenn Werte sensibel sind
Report-Werte: maskieren oder unterdrücken
```

### 17.5 Redaction-Policy

```kotlin
enum class SketchArtifactPolicy {
    DISABLED,
    VALUE_FREE_ONLY,
    VALUE_DERIVED_ONLY,
    ALLOW_VALUE_BEARING
}
```

Default:

```text
VALUE_DERIVED_ONLY
```

Bedeutung:

```text
DISABLED:
  keine Sketch-Artefakte

VALUE_FREE_ONLY:
  nur Artefakte ohne Wertinformationen

VALUE_DERIVED_ONLY:
  HLL/CPC mit kontrollierter Hash-/Update-Strategie erlaubt
  keine Frequent-Items- oder Sampling-Artefakte

ALLOW_VALUE_BEARING:
  werttragende Sketches erlaubt
  nur mit expliziter Konfiguration
```

### 17.6 Explizite Freigabe für werttragende Sketches

Werttragende Sketch-Artefakte dürfen nur erzeugt werden, wenn alle Bedingungen erfüllt sind:

```text
artifacts.enabled = true
artifactPolicy = ALLOW_VALUE_BEARING
column not classified as sensitive
user/request explicitly asks for value-bearing artifacts
```

Beispiel:

```yaml
profiling:
  approximate:
    artifacts:
      enabled: true
      policy: ALLOW_VALUE_BEARING
      allowValueBearingSketches:
        - orders.status
```

### 17.7 Report-Maskierung reicht nicht für Artefakte

Diese Regel ist verpflichtend:

```text
Wenn ein Report-Wert maskiert wird, darf kein unmaskiertes werttragendes Sketch-Artefakt für dieselbe Spalte erzeugt werden.
```

### 17.8 Artifact-Metadaten mit Privacy-Klassifikation

Jedes Sketch-Artefakt muss Privacy-Metadaten enthalten.

```yaml
artifactId: artifact_01HX...
kind: sketch
metric: frequentItems
family: FREQUENT_ITEMS
implementation: apache-datasketches-java
table: orders
column: status
privacyClass: VALUE_BEARING
artifactPolicy: ALLOW_VALUE_BEARING
containsRawValues: true
redacted: false
sha256: "..."
sizeBytes: 8192
```

Für blockierte Artefakte wird eine Warning erzeugt:

```yaml
warnings:
  - code: SKETCH_ARTIFACT_BLOCKED_BY_PRIVACY_POLICY
    severity: WARNING
    path: orders.email
    message: "Value-bearing sketch artifact was not created because the column is classified as sensitive."
```

---

## 18. Source-vs-Target-Plausibilitätscheck

Approximate Profile können nach Transfers verglichen werden.

### 18.1 CLI

```bash
d-migrate data transfer \
  --source legacy \
  --target modern \
  --tables orders \
  --profile-validation approximate
```

### 18.2 Vergleichsmodell

```kotlin
data class ApproximateProfileComparison(
    val table: String,
    val column: String,
    val metric: String,
    val sourceEstimate: Double,
    val targetEstimate: Double,
    val tolerance: Double,
    val status: ComparisonStatus,
    val message: String?
)
```

```kotlin
enum class ComparisonStatus {
    OK,
    OK_WITHIN_ERROR_BOUNDS,
    WARNING,
    FAILED,
    NOT_COMPARABLE
}
```

### 18.3 Beispielausgabe

```yaml
table: orders
comparisons:
  - column: customer_id
    metric: distinct
    sourceEstimate: 831204
    targetEstimate: 831190
    tolerance: 0.01
    status: OK_WITHIN_ERROR_BOUNDS

  - column: amount
    metric: p95
    sourceEstimate: 318.2
    targetEstimate: 317.9
    tolerance: 0.02
    status: OK

  - column: status
    metric: frequentItems
    status: OK
    message: "Top frequent items match within configured bounds."
```

### 18.4 Grenzen

Approximate Profile ersetzen keine exakte Integritätsprüfung.

Sie dürfen nicht als alleinige Abnahmebedingung für erfolgreiche Migrationen verwendet werden.

Für verbindliche Validierung bleiben erforderlich:

- exakte Row Counts
- Checksums
- Hashes
- Constraints
- optional Row-Level-Vergleiche

---

## 19. Fehler- und Warnmodell

### 19.1 Erweiterung des bestehenden Warning-Modells

Das bestehende Profiling-Modell verwendet `WarningCode` und `ProfileWarning`. Die DataSketches-Integration führt keinen parallelen Enum-Namen wie `ProfileWarningCode` ein.

Für DataSketches müssen neue Werte zu `WarningCode` ergänzt werden. Zusätzlich braucht `ProfileWarning` optional einen Pfad, weil globale Report-Warnings, Tabellen-Warnings und Spalten-Warnings deterministisch gemeinsam sortiert und auf konkrete Artefakte oder Spalten bezogen werden müssen.

```kotlin
data class ProfileWarning(
    val code: WarningCode,
    val message: String,
    val severity: Severity = Severity.INFO,
    val path: String? = null
)
```

Wenn harte Binärkompatibilität für vorkompilierte Konsumenten erforderlich ist, gilt dieselbe Regel wie bei `ColumnProfile`: `path` muss dann in ein separates Report-/DTO-Modell ausgelagert werden.

### 19.2 Neue `WarningCode`-Werte

```kotlin
enum class WarningCode {
    // bestehende Codes bleiben erhalten

    APPROXIMATE_VALUE,
    APPROXIMATION_ERROR_HIGH,
    SKETCH_MEMORY_LIMIT_REACHED,
    SKETCH_UNSUPPORTED_TYPE,
    SKETCH_SERIALIZATION_FAILED,
    SKETCH_COMPARISON_NOT_COMPARABLE,
    SKETCH_STREAMING_PORT_UNAVAILABLE,
    SKETCH_ARTIFACT_BLOCKED_BY_PRIVACY_POLICY,
    SKETCH_SAMPLING_REQUIRES_SEED_FOR_STABLE_REPORT,
    SKETCH_MERGE_ORDER_NORMALIZED
}
```

### 19.3 Beispiele

```yaml
warnings:
  - code: APPROXIMATE_VALUE
    severity: INFO
    message: "distinctEstimate was computed using HLL and is approximate."

  - code: SKETCH_UNSUPPORTED_TYPE
    severity: WARNING
    message: "Quantile sketch skipped for non-numeric column status."

  - code: APPROXIMATION_ERROR_HIGH
    severity: WARNING
    message: "Configured relative error target could not be met within memory limit."

  - code: SKETCH_STREAMING_PORT_UNAVAILABLE
    severity: WARNING
    message: "Approximate profiling was requested, but no row streaming port is available."

  - code: SKETCH_ARTIFACT_BLOCKED_BY_PRIVACY_POLICY
    severity: WARNING
    path: orders.email
    message: "Value-bearing sketch artifact was not created because the column is classified as sensitive."
```

---

## 20. Datenschutz und Sicherheit für Reports

Profiling darf keine sensiblen Rohdaten unnötig exponieren.

### 20.1 Default-Verhalten

Standardmäßig sollen approximative Reports enthalten:

- Distinct Estimates.
- Quantile.
- Häufigkeiten nur für nicht sensible Spalten.
- Keine vollständigen Rohdatensätze.
- Keine serialisierten Sketches, außer explizit aktiviert.

### 20.2 Maskierung

Für `frequentItems` und `samples` muss Maskierung unterstützt werden.

Beispiel:

```yaml
columns:
  - name: email
    approximateStats:
      frequentItems:
        - value: "<masked>"
          estimate: 120
```

### 20.3 Konfigurationsoption

```yaml
profiling:
  approximate:
    maskValuesForColumns:
      - email
      - phone
      - iban
    disableSamples: true
```

---

## 21. Konfiguration

### 21.1 YAML-Konfiguration

```yaml
profiling:
  mode: hybrid

  approximate:
    enabled: true
    backend: datasketches

    sketches:
      distinct:
        enabled: true
        family: HLL
        lgK: 12
        relativeError: 0.01

      quantiles:
        enabled: true
        family: KLL
        k: 200

      frequentItems:
        enabled: true
        maxItems: 50

      sampling:
        enabled: false
        maxSamples: 100
        seed: null

    artifacts:
      enabled: false
      maxSketchBytes: 10485760
      policy: VALUE_DERIVED_ONLY

    privacy:
      disableSamples: true
      sensitiveColumns:
        - email
        - phone
        - iban
        - ssn
        - birth_date
      maskValuesForColumns:
        - email
        - phone
        - iban
```

---

## 22. Implementierungsstrategie

### 22.1 Phase 1: Spike

Zielversion: 0.9.9

Umfang:

- Prototyp für HLL/CPC Distinct Count.
- Prototyp für KLL Quantiles.
- Prototyp für Frequent Items.
- Neuer `ProfilingRowStreamPort`.
- Eine erste Adapter-Implementierung, bevorzugt PostgreSQL.
- Benchmark gegen große lokale Testdaten.
- Vergleich exakter vs. approximativer Werte.
- Memory- und Laufzeitmessung.
- Prüfung deterministischer Report-Ausgabe.

Erfolgskriterien:

- Profiling läuft streaming-basiert.
- Keine vollständige Spalte muss im RAM gehalten werden.
- Approx-Werte werden korrekt als approximativ markiert.
- `distinctCount` wird nicht mit Approx-Werten befüllt.
- Modul bleibt optional.
- Datenschutzregeln blockieren werttragende Sketch-Artefakte standardmäßig.

### 22.2 Phase 2: Produktives Modul

Zielversion: 1.0.0-RC

Umfang:

- Stabiles Modul `profiling-datasketches`.
- CLI-Unterstützung.
- REST-/gRPC-Request-Optionen.
- MCP-Ressourcenintegration.
- Artefaktunterstützung.
- Source-vs-Target-Vergleich.
- Datenschutzoptionen.
- Deterministische Merge- und Sortierregeln.
- Tests und Dokumentation.

---

## 23. Tests

### 23.1 Unit Tests

- Mapping Sketch-Ergebnis zu Domain-Modell.
- Serialisierung von `ApproximateColumnStats`.
- `distinctCount` bleibt unverändert bei Approx-Schätzung.
- `computedMetrics.distinctCount = NOT_COMPUTED` bei Approx-Schätzung.
- Fehlerfälle bei unsupported column types.
- Maskierung sensibler Werte.
- Blockieren werttragender Sketch-Artefakte.
- Konfigurationsvalidierung.

### 23.2 Integration Tests

- Profiling PostgreSQL Testdaten.
- Profiling MySQL Testdaten.
- Profiling SQLite Testdaten.
- CLI `--profile-mode approximate`.
- CLI `--profile-mode hybrid`.
- REST Job Request mit `Idempotency-Key`.
- REST Job Request ohne `Idempotency-Key` erzeugt Fehler.
- gRPC Job Request.
- MCP `data_profile_start` mit `connectionId`.

### 23.3 Determinismus-Tests

- Zwei identische Läufe erzeugen identische stabile Reports.
- Kein `generatedAt` im stabilen Report.
- Tabellen sind stabil sortiert.
- Spalten sind stabil sortiert.
- Frequent Items sind stabil sortiert.
- Warnings sind stabil sortiert.
- Sketch-Metadaten sind stabil sortiert.
- Sampling erscheint nur mit stabilem Seed im stabilen Report.
- Parallele Sketch-Merges verwenden deterministische Merge-Reihenfolge.

### 23.4 Benchmark Tests

Benchmark-Dimensionen:

- 1 Mio. Rows.
- 10 Mio. Rows.
- 100 Mio. Rows, falls Testumgebung verfügbar.
- Hohe Kardinalität.
- Niedrige Kardinalität.
- Schiefe Verteilungen.
- Numerische Ausreißer.

Metriken:

- Laufzeit.
- Peak Memory.
- Fehlerabweichung gegenüber exakten SQL-Aggregaten.
- Artefaktgröße.
- CPU-Zeit.

---

## 24. Akzeptanzkriterien

Die Integration gilt als akzeptiert, wenn:

1. DataSketches nur als optionale Dependency eingebunden ist.
2. `hexagon:core` keine DataSketches-Dependency besitzt.
3. `hexagon:profiling` keine DataSketches-Dependency besitzt.
4. Driver-Core-Module keine DataSketches-Dependency besitzen.
5. `ColumnProfile` behält bestehende Feldtypen und Semantik; harte Binärkompatibilität wird entweder explizit ausgeschlossen oder über ein separates Report-/DTO-Modell erreicht.
6. `distinctCount: Long = 0` wird nicht als Approx-Schätzwert missbraucht.
7. `computedMetrics` oder ein gleichwertiger Mechanismus kennzeichnet berechnete und nicht berechnete Pflichtfelder; bestehende Exact-Pfade reporten nicht versehentlich `NOT_COMPUTED`.
8. Approx-Werte werden nie in deterministische Felder geschrieben.
9. Es gibt einen expliziten Row-Streaming-Port oder eine gleichwertige dialektspezifische Streaming-Implementierung.
10. `profiling-datasketches` hängt nicht von Driver-Core-Modulen ab.
11. CLI unterstützt `--profile-mode approximate`.
12. CLI unterstützt `--profile-mode hybrid`.
13. JSON-/YAML-Reports kennzeichnen Approx-Werte eindeutig.
14. MCP verwendet den bestehenden `connectionId`-Vertrag.
15. MCP-Beispiele enthalten keinen künstlichen `tool`-Envelope.
16. REST dokumentiert und erzwingt die bestehende `Idempotency-Key`-Pflicht für asynchrone Job-Starts.
17. REST verwendet `connectionId` und führt nicht parallel `source` ein.
18. REST/gRPC/MCP können Approx-Profiling-Jobs starten.
19. Große Ergebnisse werden als Artefakte referenziert.
20. Stabile Profiling-Reports enthalten kein laufzeitvariables `generatedAt`.
21. Tabellen, Spalten, Warnings, Frequent Items und Sketch-Metadaten werden deterministisch sortiert.
22. Sampling im stabilen Report erfordert einen expliziten stabilen Seed.
23. Parallele Sketch-Merges verwenden eine deterministische Merge-Reihenfolge.
24. Sketch-Artefakte haben eine Privacy-Klassifikation.
25. Werttragende Sketch-Artefakte sind standardmäßig verboten.
26. Report-Maskierung verhindert unmaskierte werttragende Sketch-Artefakte derselben Spalte.
27. Blockierte Sketch-Artefakte erzeugen nachvollziehbare Warnings.
28. Unsupported Types erzeugen Warnings statt harter Fehler.
29. Der Row-Streaming-Port verwendet den bestehenden Connection-/Pool-Kontext und akzeptiert keine rohen SQL-Filterfragmente.
30. Typisierte `ProfileValue`-Normalisierung ist für Hashing, Sortierung, Redaction und Serialisierung definiert.
31. Das bestehende `WarningCode`-/`ProfileWarning`-Modell wird konsistent erweitert oder über ein separates Report-/DTO-Modell abgebildet.
32. Dokumentation erklärt klar, dass Approx-Profiling keine exakte Integritätsprüfung ersetzt.
33. Benchmarks zeigen niedrigeren Speicherverbrauch als exakte In-Memory-Auswertung.

---

## 25. Offene Entscheidungen

### 25.1 HLL oder CPC als Default für Distinct Count?

Optionen:

```text
HLL:
  verbreitet
  bekanntes Verhalten
  gute Default-Wahl

CPC:
  sehr effizient
  ggf. bessere Genauigkeit/Speicher-Balance
```

Vorschlag:

```text
Default: HLL
Optional: CPC
```

### 25.2 KLL oder t-digest als Default für Quantiles?

Optionen:

```text
KLL:
  gute allgemeine Quantile
  DataSketches-nativ

t-digest:
  stark für Tail-Quantiles
  besonders interessant für p99/p999
```

Vorschlag:

```text
Default: KLL
Optional: t-digest
```

### 25.3 Sollen Sketch-Artefakte standardmäßig gespeichert werden?

Vorschlag:

```text
Default: false
```

Begründung:

- Kleinere Artefaktmenge.
- Weniger Datenschutzrisiko.
- Reports reichen für die meisten Nutzer.

Aktivierung explizit über:

```bash
--sketch-artifacts true
```

### 25.4 Soll `hybrid` der Default für große Tabellen werden?

Vorschlag:

```text
Kurzfristig: nein
Langfristig: optional über Auto-Heuristik
```

Mögliche spätere Heuristik:

```text
if estimatedRowCount > configuredThreshold:
  recommend hybrid
else:
  use exact
```

### 25.5 Soll `ComputedMetrics` dauerhaft bleiben?

Vorschlag:

```text
Ja, kurzfristig.
```

Begründung:

Das bestehende Modell nutzt Pflichtwerte. Ohne `ComputedMetrics` lassen sich echte `0`-Werte nicht sauber von nicht berechneten Werten unterscheiden.

Langfristig kann eine Major-Version ein stärker typisiertes Metrikmodell einführen.

---

## 26. Beispiel: End-to-End

### 26.1 Command

```bash
d-migrate data profile \
  --source prod \
  --tables orders \
  --profile-mode hybrid \
  --sketches distinct,quantiles,frequent-items \
  --relative-error 0.01 \
  --confidence 0.95 \
  --output orders-profile.yaml
```

### 26.2 Stabiler Report ohne `generatedAt`

```yaml
database: prod
profileMode: hybrid

tables:
  - name: orders
    rowCount: 12893422
    computedMetrics:
      rowCount: EXACT

    columns:
      - name: customer_id
        dbType: bigint
        logicalType: INTEGER
        nullCount: 0
        nonNullCount: 12893422
        distinctCount: 0
        computedMetrics:
          nullCount: EXACT
          nonNullCount: EXACT
          distinctCount: NOT_COMPUTED
        approximateStats:
          distinctEstimate:
            estimate: 831204
            lowerBound: 822000
            upperBound: 840500
            relativeErrorTarget: 0.01
            confidence: 0.95
          sketchMetadata:
            - metric: distinct
              family: HLL
              implementation: apache-datasketches-java
              version: "7.0.1"
              parameters:
                lgK: "12"
                numStdDev: "2"
                boundConfidence: "0.95"

      - name: amount
        dbType: numeric
        logicalType: DECIMAL
        numericStats:
          min: 0.0
          max: 98123.5
        computedMetrics:
          numericStats: EXACT
        approximateStats:
          quantiles:
            p50: 44.9
            p95: 318.2
            p99: 901.7
            normalizedRankError: 0.0133
          sketchMetadata:
            - metric: quantiles
              family: KLL
              implementation: apache-datasketches-java
              version: "7.0.1"

warnings:
  - code: APPROXIMATE_VALUE
    severity: INFO
    message: "Some statistics were computed using approximate sketches."
```

### 26.3 Job-Metadaten separat

```yaml
job:
  id: job_01HX...
  kind: data.profile
  createdAt: "2026-05-04T10:15:00Z"
  artifacts:
    - artifactId: artifact_profile_yaml
      path: profile.yaml
      sha256: "..."
```

---

## 27. Zusammenfassung

Apache DataSketches passt sehr gut als optionales Backend für approximatives Profiling in d-migrate.

Die Integration soll:

- optional bleiben
- die hexagonale Architektur respektieren
- das bestehende `ColumnProfile`-Modell nicht brechen
- `distinctCount: Long = 0` nicht semantisch überladen
- berechnete und nicht berechnete Pflichtmetriken explizit kennzeichnen
- einen Row-Streaming-Port oder gleichwertige Streaming-Adapter nutzen
- approximative Werte explizit modellieren
- deterministische Felder nicht verfälschen
- CLI, REST, gRPC und MCP sauber unterstützen
- REST-Idempotency-Verträge respektieren
- MCP mit `connectionId` statt neuem `source`-Vertrag verwenden
- stabile Reports ohne laufzeitvariable Felder erzeugen
- Sampling und Merge-Reihenfolge deterministisch regeln
- Sketch-Artefakte mit Privacy-Klassifikation absichern
- werttragende Sketch-Artefakte standardmäßig verbieten
- große Datenmengen streaming-fähig profilieren
- Source-vs-Target-Plausibilitätschecks ermöglichen

DataSketches ist damit kein Bestandteil des Migrationskerns, sondern eine leistungsfähige Erweiterung für Profiling, Summaries, Plausibilitätschecks und große Datenbestände.
