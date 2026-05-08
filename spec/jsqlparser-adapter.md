# JSqlParser-Adapter für SQL-Transformation

## Ziel und Leitplanken

- SQL-Parsing und -Transformation liegen vollständig im Adapter-Layer.
- Der Hexagon-Kern bleibt parser-unabhängig.
- Unbekannte, quellspezifische Dialekt-Funktionen werden deterministisch als `W111` berichtet.
- In `STRICT` sind unbehandelte Transformationsfehler harte Fehlerpfade (keine stillen Qualitätsdegradationen).
- `FALLBACK_ALLOWED` ist ein kontrollierter, vollständig beobachtbarer Ausweichpfad.
- Bestehendes `ViewQueryTransformer`-Verhalten bleibt nach außen kompatibel, während intern ein neuer Port verwendet wird.

## Aktueller Ist-Stand / Abgleich

- `W111` wird aktuell bereits in [ViewQueryTransformer](../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/ViewQueryTransformer.kt) sowie in
  [ViewQueryTransformerTest](../adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/ViewQueryTransformerTest.kt) und
  [PostgresDdlGeneratorTest](../adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGeneratorTest.kt) verwendet.
- Die aktuellen `warn-code-ledger-0.9.2.yaml` und `warn-code-ledger-0.9.3.yaml` enthalten `W111` aktuell nicht.
- Deshalb ist die Umsetzung in diesem Dokument so formuliert, dass `W111` zunächst als dokumentierte Voraussetzung betrachtet wird und vor Aktivierung des neuen Adapters im Zielversion-Ledger eindeutig hinterlegt wird.

## Architekturentscheidungen (verbindlich)

- Port-Definition liegt in `hexagon/ports` und nutzt das bestehende Dialektmodell aus `hexagon/ports-common`.
- Konkrete Dateien:
  - `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/sql/SqlQueryTransformPort.kt`
  - `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/sql/SqlTransformRequest.kt`
  - `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/sql/SqlTransformResult.kt`
  - `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/sql/SqlTransformWarning.kt`
  - `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/sql/SqlTransformError.kt`
- Adapter-Implementierung liegt in `adapters/driven/driver-common`:
  - `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/JSqlParserQueryTransformer.kt`
- `ViewQueryTransformer` bleibt Fassade und hängt über den Port an die Adapter-Transformation.
- Bestehende Aufrufer wie [PostgresRoutineDdlHelper](../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresRoutineDdlHelper.kt) behalten die Aufrufform. Für neue Fallback/Failure-Funktionalität werden gezielte, nicht-invasive Überladungen ergänzt.

## Port-Vertrag (verbindlich)

```kotlin
package dev.dmigrate.driver.sql

import dev.dmigrate.driver.DatabaseDialect

/** Steuerungshebel der Transformationspipeline */
enum class SqlTransformMode {
  STRICT,              // Kein Fallback, Fehler führen zum Failure
  FALLBACK_ALLOWED,    // Parser -> bei Fehler Legacy-Tokenizer-Fallback
}

enum class SqlTransformWarningCode(val externalCode: String) {
  W111_UNKNOWN_FUNCTION("W111"),
  W198_PARSER_FALLBACK_USED("W198"),
}

enum class SqlTransformErrorCode {
  INPUT_VALIDATION,
  PARSE_ERROR,
  LEGACY_FALLBACK_ERROR,
  UNSUPPORTED_EXPRESSION,
  TRANSFORM_ERROR,
  INTERNAL_ERROR,
}

enum class SqlTransformFailureContext {
  INPUT_VALIDATION,
  PARSING,
  LEGACY_FALLBACK,
  VISITATION,
  SERIALIZATION,
}

enum class SqlTransformVisibility {
  USER,
  TECHNICAL,
}

data class SqlTransformRequest(
  val query: String,
  val sourceDialect: DatabaseDialect?,
  val targetDialect: DatabaseDialect,
  val mode: SqlTransformMode = SqlTransformMode.STRICT,
)

data class SqlTransformError(
  val code: SqlTransformErrorCode,
  val context: SqlTransformFailureContext,
  val message: String,
  val sourceDialect: DatabaseDialect?,
  val targetDialect: DatabaseDialect,
  val causeClass: String? = null,
  val causeMessage: String? = null,
  val primaryError: String? = null,
  val legacyError: String? = null,
  val details: Map<String, String> = emptyMap(),
  val querySnippet: String? = null,
  val position: Int? = null,
)

data class SqlTransformWarning(
  val code: SqlTransformWarningCode,
  val message: String,
  val sourceFunction: String? = null,
  val position: Int? = null,
  val context: Map<String, String> = emptyMap(),
)

data class SqlTransformSuccess(
  val transformedQuery: String,
  val warnings: List<SqlTransformWarning>,
  val fallbackUsed: Boolean = false,
)

data class SqlTransformFailure(
  val error: SqlTransformError,
  val warnings: List<SqlTransformWarning> = emptyList(),
)

sealed interface SqlTransformResult {
  val visibility: SqlTransformVisibility
}

data class SqlTransformResultSuccess(
  val payload: SqlTransformSuccess,
  override val visibility: SqlTransformVisibility = SqlTransformVisibility.USER,
) : SqlTransformResult

data class SqlTransformResultFailure(
  val payload: SqlTransformFailure,
  override val visibility: SqlTransformVisibility = SqlTransformVisibility.USER,
) : SqlTransformResult

interface SqlQueryTransformPort {
  fun transform(request: SqlTransformRequest): SqlTransformResult
}
```

## W111-Verhalten (festgelegt)

- `W111_UNKNOWN_FUNCTION` wird gesetzt, wenn eine Funktionsreferenz im Quell-Dialekt existiert,
  aber für den Ziel-Dialekt keine Transformationsregel vorhanden ist.
- Die betroffene Funktion bleibt in der SQL-Ausgabe unverändert.
- Ausgabe ist stabil sortiert (Funktionsname case-insensitive aufsteigend, dann Position).
- `sourceFunction` enthält den Funktionsnamen, `position` den besten verfügbaren Offset/Tokenindex.
- `W111` wird nur ausgelöst, wenn ein expliziter `sourceDialect` vorliegt und dieser vom `targetDialect` abweicht.

## Fallback-Policy

Es gibt genau einen Steuerungshebel: `SqlTransformMode`.

### STRICT

- `mode = STRICT`
- Kein Parser-Fallback.
- Jeder Fehler führt zu `SqlTransformResultFailure`:
  - `INPUT_VALIDATION` bei leerem/blank Query
  - `PARSE_ERROR` bei Parsing-Fehlern
  - `TRANSFORM_ERROR` bei AST-transformationsseitigen Fehlern
  - `UNSUPPORTED_EXPRESSION` bei bekannter, nicht unterstützter Konstruktion
  - `INTERNAL_ERROR` bei unerwarteten Laufzeitfehlern
- Der Aufrufer entscheidet, ob ein `Failure` als harte Abbruchbedingung oder als skipped Objekt im Host-Modul behandelt wird.

### FALLBACK_ALLOWED

- Ablauf:
  1. Parser-basierte Transformation wird versucht.
  2. Bei Parser-/Transformationsfehler wird der bestehende Legacy-Tokenizer-Fallback aufgerufen.
  3. Bei Erfolg des Legacy-Pfads wird `SqlTransformSuccess(fallbackUsed = true)` geliefert.
  4. Bei Fehler im Legacy-Pfad wird `SqlTransformFailure` mit `code = LEGACY_FALLBACK_ERROR` geliefert.
- Für Schritt 4 werden die dedizierten Fehlerfelder in `SqlTransformError` gesetzt:
  - `primaryError`: Fehlerbeschreibung des Parser-Pfads
  - `legacyError`: Fehlerbeschreibung des Legacy-Fallback-Pfads
- Ergebnis ist deterministisch: entweder Success (ggf. mit Fallback-Hinweis) oder eindeutiger Failure.

## W198-Risikokodierung

- `W198_PARSER_FALLBACK_USED` ist in dieser Planphase als technische Diagnose gekennzeichnet.
- `SqlTransformResultSuccess` bzw. `SqlTransformResultFailure` tragen über das Feld `visibility` die Sichtbarkeit.
- Bei `fallbackUsed = true` wird automatisch ein `W198_PARSER_FALLBACK_USED`-Eintrag in `SqlTransformSuccess.warnings` ergänzt.
- Standardausgabe im `ViewQueryTransformer` konvertiert `W198` nicht in Nutzer-`TransformationNote`.
- `W198` darf erst dann als Nutzerwarnung ausgegeben werden, wenn der Ledger-Status auf `active` wechselt.
- Für Monitoring/Runbook wird `W198` intern gezählt und an den Observability-Sink gemeldet.

## Fehler- und Dialekt-Parsing-Grenzen

- `ViewDefinition.sourceDialect` ist aktuell ein `String?`.
- Die Fassade ist für die Validierung verantwortlich.
- Boundary-Regel:
  - `null`/blank: kein Source-Dialect-Diff, daher keine `W111`-Probe.
  - ungültiger String: `INPUT_VALIDATION`.
- Die Normalisierung des Source-Dialekts erfolgt über `DatabaseDialect.fromString`.
- Die Fassade fängt `IllegalArgumentException` aus `fromString` und übersetzt sie in `SqlTransformResultFailure` mit `code = INPUT_VALIDATION` und `context = INPUT_VALIDATION`.

## Metriken und Observability (verbindlich)

- Metriken werden im Adapter-Fassaden-Layer (`ViewQueryTransformer`) erhoben.
- Fallback auf festen Adapterpunkt in `driver-common`:
  - Interface `SqlTransformMetricsSink`
  - Standard: `NoopSqlTransformMetricsSink`
  - Produktions-Adapter: z. B. Micrometer
- Erforderliche Zählwerte je Versuch:
  - `d_migrate_sql_transform_attempts_total{mode,target_dialect,source_dialect}`
  - `d_migrate_sql_transform_success_total{mode,target_dialect,source_dialect}`
  - `d_migrate_sql_transform_failure_total{mode,target_dialect,source_dialect,error_code}`
  - `d_migrate_sql_transform_fallback_total{mode,target_dialect,source_dialect,fallback_reason="parser_error|legacy_error|legacy_success"}`
  - `d_migrate_sql_transform_duration_ms_bucket{status="success|failure",mode,target_dialect,source_dialect}`
- `fallback_attempt_total = sum(d_migrate_sql_transform_fallback_total)` über alle `fallback_reason`.
- `fallback_rate = fallback_attempt_total / attempts_total` mit Filter `mode="FALLBACK_ALLOWED"`.
- `legacy_fallback_failure_rate = d_migrate_sql_transform_fallback_total{fallback_reason="legacy_error"} / fallback_attempt_total` mit Filter `mode="FALLBACK_ALLOWED"`.
- Initiale Schwellwerte (nach Pilotlauf anhand realer Baseline anpassen):
  - `fallback_rate > 0.5%` -> Lauf als instabil markieren
  - `legacy_fallback_failure_rate > 0.1%` -> Schwerewarnung
- Bei `attempts_total == 0` bzw. `fallback_attempt_total == 0` wird die jeweilige Division nicht ausgeführt.

## Warnungs-Katalog-Anbindung (Ledger)

- `W111` ist Produktivsignal und muss in einem aktiven Ledger-Eintrag enthalten sein.
  - In einem 0.9.3-Kontext: `warn-code-ledger-0.9.3.yaml` (`status: active`, `test_path`, `evidence_paths`).
  - Nach Umstellung auf `status: active` muss `CodeLedgerValidationTest` grün bleiben.
- `W198` wird in dieser Iteration als technische Diagnose gehalten:
  - `status: reserved` in Ledger.
  - kein Nutzer-Zwangspfad in der Standard-CLI.
  - nicht als harte Qualitätsanforderung in Golden-Master-Tests verankert.

## Implementierungsregeln

- `ViewQueryTransformer` bleibt Fassade ohne Transformationslogik.
- Parser-Transformation ausschließlich über JSqlParser-Visitor.
- Funktionsmapping als deterministische Tabelle im Adapter.
- Serialisierung ausschließlich im Adapter.
- Bei `STRICT` werden Fehler nicht maskiert.
- Legacy-Fallback nutzt die bestehende Token-basierte Logik weiter und bleibt als kontrollierter Ausweichweg gebunden.

## Dependency-Management (geplant)

- JSqlParser hängt nur am Modul `adapters/driven/driver-common`.
- In diesem Repository gibt es kein `gradle/libs.versions.toml`.
- Version wird im zentralen Katalog angelegt:
  - Neu in `gradle.properties`: `jsqlparserVersion=5.x.y` (konkrete Version bei Implementierung festlegen)
  - Neu in `adapters/driven/driver-common/build.gradle.kts`: `implementation("com.github.jsqlparser:jsqlparser:${rootProject.properties["jsqlparserVersion"]}")`
- Keine parserbezogenen Transitiven in `hexagon/ports`, `hexagon/core` oder anderen Modulen.

## Fassade und bestehender Call-Site-Flow

- Bestehender Aufruf bleibt kompatibel:
  - `transform(query, sourceDialect)` bleibt verfügbar.
  - Neu: `transform(query, sourceDialect, mode)` als überladene Form.
- Standard ist für aktuelle Nutzer `STRICT`.
- Für kontrollierte Rollouts kann `FALLBACK_ALLOWED` explizit pro Aufrufer gesetzt werden (z. B. CLI-Option `--sql-transform-mode`).
- Mapping auf bestehende DDL-Notizen/Objekte:
  - `SqlTransformResultSuccess` -> normalisierte `TransformationNote`-Liste
  - `SqlTransformResultFailure` im STRICT-Pfad -> harte Abbrüche oder gezielter Skip (je nach Host-Kontext)
  - `W198` im Standardpfad nicht in Nutzer-`TransformationNote` wandeln

## Testplan

- Bestehende Tests in `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/ViewQueryTransformerTest.kt` und
  `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGeneratorTest.kt` fortführen.
- Neue/angepasste Tests:
  - `sourceDialect = null` ist kein Fehler im User-Output-Pfad.
  - `sourceDialect` ungültig -> `INPUT_VALIDATION`.
  - `STRICT` + ungültiges SQL -> `SqlTransformFailure` mit passendem Error-Code.
  - `FALLBACK_ALLOWED` + JSqlParser-Fehler + Legacy-Erfolg -> `SqlTransformSuccess(fallbackUsed=true)`.
  - `FALLBACK_ALLOWED` + Fehler in Parser und Legacy -> `SqlTransformFailure(code = LEGACY_FALLBACK_ERROR)`.
  - deterministische `W111` inkl. `sourceFunction` und `position`.
  - `FALLBACK_ALLOWED` + Legacy-Erfolg -> `W198` automatisch in `warnings` enthalten.
  - Fassade: ungültiger `sourceDialect`-String -> `IllegalArgumentException` wird in `SqlTransformResultFailure(code = INPUT_VALIDATION)` übersetzt.
  - `fallback_rate`/`legacy_fallback_failure_rate` mit `attempts_total == 0`.
- Ledger-Tests:
  - `warn-code-ledger-0.9.3.yaml` enthält `W111` mit `status: active`.
  - `W198`-Reserveeintrag bleibt `status: reserved`.

## Abnahmekriterien

- Deterministische `W111`-Signale in definierten Fällen.
- `STRICT` darf keinen stillen Fallback durchführen.
- `STRICT`-Fehler liefern definiert `SqlTransformFailure`.
- `fallback_rate` und `legacy_fallback_failure_rate` liegen in kontrollierten Läufen unter den initial definierten Schwellwerten (nach Pilotlauf anpassen).
- JSqlParser-Imports nur in `adapters/driven/driver-common`.
- `W111`-Ledger-Eintrag ist in der Zielversion aktiv und konsistent validierbar.
- Fassade bleibt API-kompatibel zu bestehender Aufruferlandschaft, ergänzt aber neue Fallback/Mode-Wahl explizit.

## Risiken und Gegenmaßnahmen

- AST kann Dialektkonstrukte nicht vollständig modellieren.
  - Gegenmaßnahme: kontrollierter `FALLBACK_ALLOWED`-Rollout, tägliche `STRICT`-Läufe, harte Grenzwerte.
- JSqlParser-Serialisierung kann syntaktisch gültige, aber abweichende Strings erzeugen.
  - Gegenmaßnahme: semantische Golden-Master-Tests und Dialekt-normalisierte Vergleichslogik.
- Performance-Overhead auf großen Views.
  - Gegenmaßnahme: Dauer-Histogramm + Regressionstests nach Länge/Komplexität.
- `W198` bleibt technisch, bis Stabilität nachgewiesen ist.
  - Gegenmaßnahme: separater technischer Metrikpfad, keine aktive Nutzer-Ausgabe vor `active`.

## Nächster Schritt

1. Port-Klassen in `hexagon/ports` implementieren.
2. `ViewQueryTransformer` auf den Port umhängen und Overload für Rückwärtskompatibilität behalten.
3. JSqlParser-Adapter mit Default `STRICT` implementieren.
4. `FALLBACK_ALLOWED` in kontrollierten Migrationspfaden aktivieren.
5. Ledger-Konformität finalisieren (`W111` aktiv, `W198` reserviert). 
