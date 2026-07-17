# Path-Traversal: Tabellenname aus untrusted Quell-DB wird zum Dateinamen (P1)

> **Status:** Vorabklärung (2026-07-17)
> **Trigger:** Security-Vollaudit
> ([`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Befund 2 = P1).
> **Aktivierungsbedingung:** P1 — sollte vor 1.0.0-final priorisiert werden
> → `next/`-Plan.

## Befund

Bei `data export --output <dir> --split-files` ohne `--tables` stammt die
Tabellenliste aus dem Katalog der **Quell-DB**. Die Lister liefern den
Roh-Identifier unquotiert durch. Er wird per String-Interpolation zum
Dateinamen:

```kotlin
fun fileNameFor(table: String, format: DataExportFormat): String =
    "$table.${format.cliName}"
```

und ohne `normalize()`/`startsWith()`-Prüfung gegen das Ausgabeverzeichnis
aufgelöst (`output.directory.resolve(...)`). Geschrieben wird mit
`CREATE, TRUNCATE_EXISTING, WRITE` — anlegen **oder** bestehende Datei leeren
und überschreiben. Sequentieller und paralleler Pfad sind beide betroffen.

PostgreSQL erlaubt in gequoteten Identifiern nahezu jedes Zeichen;
`CREATE TABLE "../../../etc/cron.d/x"` ist gültiges SQL. Der geschriebene
Inhalt ist dabei angreiferkontrolliert (es sind seine Tabellendaten im
gewählten Exportformat).

## Zwei strukturelle Beobachtungen

1. **Die Validierung sitzt auf der falschen Seite.** `--tables` (Operator-Eingabe,
   *vertrauenswürdig*) wird streng geprüft; die Auto-Discovery aus der Quell-DB
   (*untrusted*) nicht. `DataExportHelpers.kt:29-31` schreibt die falsche
   Annahme sogar als Kommentar aus.
2. **Der sichere Helfer ist im falschen Modul eingesperrt.** `PathSafety`
   (`requireSafeId`, Allowlist `[A-Za-z0-9_-]{1,128}` als Vollmatch) existiert
   und ist sauber gebaut — aber `internal` in `adapters/driven/storage-file`.
   Das Streaming-Modul kann ihn bauartbedingt nicht erreichen und hat keinen
   eigenen. Der Fix sollte diese Naht mitlösen, statt die Allowlist ein zweites
   Mal zu schreiben.

## Arbeitspakete (Skizze)

1. Entscheiden, wo die sichere Namens-Validierung strukturell hingehört —
   Kandidat: `hexagon/ports-common` (vgl. Memory-Regel „Resource-Loader-Kolokation":
   ein `internal`-Helfer in einem Nicht-Konsumenten-Modul ist nicht eigenständig).
   Beachten: eine reine Allowlist wie in `PathSafety` würde legitime Tabellennamen
   mit Umlauten/Punkten ablehnen — Sanitizing (Mapping auf sicheren Dateinamen)
   ist vermutlich richtiger als Ablehnen, sonst bricht der Export an gültigen
   Schemata. Kollisionsfreiheit beim Mapping mitdenken.
2. Beide Schreibpfade in `StreamingExporter` (sequentiell + parallel) sowie die
   Auto-Discovery absichern; zusätzlich Containment-Check (`normalize()` +
   `startsWith(outputDir.toRealPath())`) als Netz.
3. Prüfen, ob weitere Pfade denselben Fehler haben (Bundle-Closure, Resume-Marker,
   Import-Seite).
4. Regression: Test mit einem Tabellennamen, der Traversal-Segmente enthält, und
   einem, der auf einen absoluten Pfad zeigt (laut Bericht der schärfere Fall); Live-Test gegen PG mit gequotetem Traversal-Identifier.

## Fundstellen

- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt:262` (sequentiell)
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt:325` (parallel)
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt:115` (Auto-Discovery)
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt:372` (`CREATE|TRUNCATE_EXISTING`)
- `hexagon/ports-write/src/main/kotlin/dev/dmigrate/streaming/ExportOutput.kt:95` (`fileNameFor`)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt:29` (ausgeschriebene Fehlannahme)
- `adapters/driven/storage-file/src/main/kotlin/dev/dmigrate/server/adapter/storage/file/PathSafety.kt` (unerreichbares Gegenmuster)
