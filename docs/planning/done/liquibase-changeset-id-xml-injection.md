# Liquibase: changeSetId roh im XML-Attribut → XML-Injection (P2, CWE-91)

> **Status:** BEHOBEN 2026-07-18
> **Trigger:** Follow-up-Audit der bis dahin ungeprüften integrations-Exporter-Fläche
> (aus der „Nicht geprüft / offene Lücken"-Sektion des
> [`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Punkt 1). Die
> Nicht-SQL-Escaping-Matrix (XML/Python/JS) war beim Erst-Audit nie geprüft.
>
> **Umsetzung:** `RenderHelpers.escapeXmlAttribute` (escapt zusätzlich zu `&<>` auch
> `"`/`'`) wird jetzt auf `changeSetId` angewandt, bevor es in `id="…"` interpoliert
> wird. **Nicht** die Liquibase-version-Validierung verengt — Liquibase erlaubt
> legitim beliebige Versionsstrings; Escaping am Einbettungspunkt ist die robuste,
> nicht-brechende Lösung (dieselbe Linie wie die SQL-seitigen dialekt-bewussten
> Escaper). TDD (`RenderHelpersTest` + `LiquibaseMigrationExporterTest`:
> Quote-in-version injiziert keinen Geschwister-`changeSet`). Docker
> `:integrations:check` grün.

## Befund

`LiquibaseMigrationExporter.render` baute die Changelog-Zeile

```kotlin
appendLine("""    <changeSet id="$changeSetId" author="d-migrate">""")
```

mit `changeSetId = "${version}-${slug}-${dialect}"`. Der **`version`**-Teil ist bei
Liquibase **unvalidiert**: `MigrationVersionValidator.validate` liefert für
`LIQUIBASE` `ValidationResult(true)` für jeden non-blank String, und
`normalizeFallback` reicht eine `schema.version` 1:1 durch. Diese `schema.version`
kann via `ToolExportRunner` aus einem **fremden Schema-File** stammen (das
Bedrohungsmodell in [`SECURITY.md`](../../../SECURITY.md) führt Eingabedateien als
untrusted).

## Angriffsszenario

Ein Operator exportiert ein fremd bezogenes Schema **ohne** `--version` nach
Liquibase (`schema.version` greift als Fallback). Der Schema-Autor hat gesetzt:

```
version: '1"/><changeSet id="evil" author="x"/><x q="'
```

Gerendert entstünde ohne Escaping ein zweiter, angreifer-kontrollierter
`<changeSet>` im Changelog. Wendet der Operator das Changelog per Liquibase an,
laufen die injizierten Änderungen gegen die Ziel-DB. Dieselbe Klasse wie die
SQL-seitigen Injection-Befunde (untrusted Quell-Metadaten → generiertes Artefakt →
vom Operator ausgeführt), nur über den XML-Pfad.

## Warum P2 und nicht höher

Kein direkter Zweitstatement-Durchgriff wie bei `mysql`-Client-DDL: es braucht die
Kette fremdes Schema-File → Export nach Liquibase ohne `--version` → Operator wendet
das Changelog an. `slug` (`MigrationSlugNormalizer` → `[a-z0-9_]`) und `dialect`
(Enum) sind harmlos; nur `version` trägt den Vektor.

## Nicht betroffen (im selben Follow-up geprüft)

- `escapeXml` im `<sql>`-Text-Knoten reicht (`&<>`, `>` bricht `]]>`).
- Knex-Kommentar-`*/`-Breakout nicht ausbeutbar (version/slug charset-beschränkt).
- `escapePython`/`escapeJavaScript` robust; Django/Flyway ohne Injection-Fläche.

## Fundstellen

- `adapters/driven/integrations/src/main/kotlin/dev/dmigrate/integration/LiquibaseMigrationExporter.kt` (Einbettung)
- `adapters/driven/integrations/src/main/kotlin/dev/dmigrate/integration/RenderHelpers.kt` (`escapeXmlAttribute`)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/migration/MigrationVersionValidator.kt` (Liquibase = jeder String)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ToolExportRunner.kt` (`schemaVersion = schema.version`)
