# CSV-Formel-Injection: untrusted Text-Zellen im Export (CWE-1236)

> **Status:** BEHOBEN 2026-07-18
> **Trigger:** Follow-up-Audit der beim Erst-Audit ungeprüften Ausgabe-Kodierung der
> Daten-Writer (aus der „Nicht geprüft / offene Lücken"-Sektion des
> [`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Punkt 6). Die
> deserialization-Fläche hatte ausdrücklich nur die **Lese**seite geprüft.
>
> **Umsetzung (zweistufig):** (1) `CsvChunkWriter` meldet formel-anfällige Spalten
> **immer** einmalig per Warnung **`W203`** — das Default-Verhalten bleibt ein
> **treuer Dump** (Wert unverändert, wie `pg_dump`). (2) Ein **opt-in** Guard
> `--csv-formula-guard` / `--no-csv-formula-guard` (CLI) bzw.
> `export.csv.formula_guard` (Config) präfixt formel-anfällige **Text**-Zellen mit
> `'`, sodass Tabellenkalkulationen sie nicht ausführen. Präzedenz CLI > Config >
> Default `false`, aufgelöst vom lenienten `CsvFormulaGuardResolver` (Spiegel von
> `ReverseAutoincrementResolver`). Der Guard-Zustand fließt in den
> `ExportOptionsFingerprint` ein, damit ein `--resume` nicht geschützte und
> ungeschützte Zeilen mischt. TDD; Docker `:hexagon:ports-write:check`,
> `:hexagon:application:check`, `:adapters:driven:formats:check`,
> `:adapters:driving:cli:check` grün.

## Befund

`CsvChunkWriter` schrieb Text-Zellwerte aus der Quell-DB roh. `ValueSerializer`
bildet `String → SerializedValue.Text(value)` **verbatim** ab (keine
Neutralisierung), und das uniVocity-Quoting ist RFC-4180-konform — es setzt
Anführungszeichen nur bei Delimiter/Quote/Newline und verhindert Formel-Auswertung
**nicht**. Beginnt ein Zellwert mit `=`, `+`, `-`, `@`, Tab oder Wagenrücklauf,
werten Excel und LibreOffice ihn beim Öffnen als **Formel** aus.

## Angriffsszenario

Ein Operator exportiert eine Tabelle aus einer nicht vertrauenswürdigen Quelle
(das Bedrohungsmodell in [`SECURITY.md`](../../../SECURITY.md) führt die Quell-DB
als untrusted) nach CSV und öffnet sie in Excel/LibreOffice. Der Angreifer hat in
einer Textspalte hinterlegt:

```
=cmd|'/c calc'!A1
```

bzw. eine `@`/`+`/`-`-präfixierte DDE-/Hyperlink-Nutzlast. Beim Öffnen führt die
Tabellenkalkulation die Formel im Kontext des Operators aus (klassische CSV-/Formel-
Injection). Dieselbe Vertrauensgrenze wie die beiden bereits bestätigten
Injection-Befunde dieses Audits, nur auf der **Ausgabe**seite.

## Warum kein stilles Neutralisieren (Default = treu)

Ein pauschales `'`-Präfixen würde den exportierten Wert **verändern** und jeden
byte-treuen Roundtrip / Re-Import brechen — d-migrate ist primär ein
Migrations-/Backup-Werkzeug, kein Spreadsheet-Generator. Deshalb ist der
sichtbare Default ein treuer Dump **mit** Warnung (`W203`), und die Entschärfung
ist eine bewusste, projekt-deklarierbare Opt-in-Entscheidung des Operators, der
weiß, dass die Datei in einer Tabellenkalkulation geöffnet wird. Nur **Text**-Zellen
tragen den Vektor; typisierte Zahlen/Booleans werden nie präfixt.

## Fundstellen

- `hexagon/ports-write/src/main/kotlin/dev/dmigrate/format/data/ExportOptions.kt` (`csvFormulaGuard`)
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/csv/CsvChunkWriter.kt` (Guard + `W203`)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/CsvFormulaGuardResolver.kt` (Präzedenz)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt` (`--csv-formula-guard`)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ExportOptionsFingerprint.kt` (Resume-Fingerprint)

## Weiterhin offen (nicht in diesem Durchlauf geprüft)

- `SchemaFileResolver` (im selben Audit-Punkt 6 genannt).
- Die JSON/YAML-Writer-Ausgabeseite (Formel-Injection ist CSV-spezifisch, aber die
  Ausgabe-Kodierungsprüfung der übrigen Writer steht noch aus).
- Ledger-Backfill für `W202`/`W203` — verfolgt in
  [`warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md).
