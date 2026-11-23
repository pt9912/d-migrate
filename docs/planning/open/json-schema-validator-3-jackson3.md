# Tracker: json-schema-validator 3.x zieht Jackson 3 nach

> **Status:** **Geschlossen (2026-11-23).** Mit Dependabot-PR #65 (3.0.9) und
> Issue #69 umgestellt; siehe [Abschluss](#abschluss-2026-11-23). Ursprünglich
> Tracker / Vorabklärung (2026-08-28).
> **Trigger:** Dependabot-PR #32 (`com.networknt:json-schema-validator`
> 1.5.4 → 3.0.7) bricht die Kompilierung in zwei Modulen. Der PR wurde
> geschlossen, der Befund hier festgehalten.
> **Aktivierungsbedingung:** Wird priorisiert → `next/`-Plan; sonst
> Trigger-Watch, bis das Projekt aus anderem Grund auf Jackson 3 geht.

## Befund (gemessen 2026-08-28)

Der Bump ist kein reiner Versionssprung: **3.x ist die Jackson-3-Linie.**
Das POM von 3.0.7 hängt an `tools.jackson.core:jackson-databind:3.2.1` —
Jackson 3 hat mit der Version auch die groupId und die Paketwurzel
gewechselt (`com.fasterxml.jackson.*` → `tools.jackson.*`).

Beide Konsumenten beziehen Jackson heute **transitiv über den Validator**
und importieren `com.fasterxml.jackson.databind.*`. Mit 3.0.7 verschwindet
diese Linie vom Test-Klassenpfad, und zusätzlich ist die eigene API
umbenannt:

| 1.5.4 | 3.0.7 |
| ----- | ----- |
| `JsonSchema` | `Schema` |
| `JsonSchemaFactory` | `SchemaRegistry` |
| `SpecVersion.VersionFlag` | `com.networknt.schema.dialect.Dialects` |
| `com.fasterxml.jackson.databind.JsonNode` | `tools.jackson.databind.JsonNode` |

Der CI-Fehlschlag zeigt deshalb beides zugleich:

```
:test:e2e-cli:compileTestKotlin FAILED
  McpOutputSchemaValidationScenarioTest.kt:3:30 Unresolved reference 'databind'
  McpOutputSchemaValidationScenarioTest.kt:8:29 Unresolved reference 'JsonSchema'
:adapters:driven:formats:compileTestKotlin FAILED
  SchemaJsonContractTest.kt:6:29 Unresolved reference 'JsonSchema'
```

## Betroffene Stellen

Die genutzte API-Fläche ist klein — vier Aufrufstellen in zwei Testdateien:

- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/SchemaJsonContractTest.kt`
- `test/e2e-cli/src/test/kotlin/dev/dmigrate/cli/integration/McpOutputSchemaValidationScenarioTest.kt`

Beide bauen ein Schema über `JsonSchemaFactory.getInstance(V202012)` und
prüfen mit `schema.validate(node)`. Produktivcode ist nicht betroffen; der
Validator steht ausschließlich als `testImplementation`.

## Warum nicht jetzt

Der Umbau der vier Aufrufstellen wäre überschaubar. Teuer ist die Folge
dahinter: Der Produktivcode fährt Jackson 2 (2.21.5). Ein Test-Klassenpfad
auf `tools.jackson.*` brächte eine **zweite Jackson-Linie** in den Build —
für zwei Testdateien, und gegen die Stoßrichtung der Abhängigkeitsarbeit,
die das Auslieferungsartefakt gerade erst von 240 auf 177 Jars gebracht hat
([ADR 0046](../../adr/0046-hadoop-bleibt-im-parquet-adapter.md)).

Die Umstellung gehört deshalb an den Tag, an dem das Projekt Jackson 3 als
Ganzes nimmt — nicht an einen Testabhängigkeits-Bump.

## Kleiner Schritt, falls der Stand veralten soll

Innerhalb der Jackson-2-Linie ist **1.5.6** die neueste Version (Stand
2026-08-28). Ein Bump 1.5.4 → 1.5.6 liefe ohne jede Codeänderung durch.
Dependabot schlägt ihn nicht vor, weil es immer die höchste Version nimmt.

## Abschluss (2026-11-23)

Umgestellt mit Dependabot-PR #65 (1.5.4 → 3.0.9) und Issue #69. Beide Testdateien
nutzen jetzt `Schema`, `SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)`
und `tools.jackson.databind.*`. Die Tabelle oben nannte `Dialects` als Ersatz für
`SpecVersion.VersionFlag`. Für die Wahl des Standard-Dialekts ist es in 3.0.9 das
Enum `SpecificationVersion`.

**Der Einwand aus „Warum nicht jetzt" galt nicht mehr.** Jackson 3 liegt längst im
Auslieferungsartefakt, nicht erst auf dem Test-Klassenpfad: Flyway 13 bringt es mit,
`adapters/driven/persistence-jdbc/build.gradle.kts` hebt es per Constraint auf 3.1.7
(CVE-2026-68497). Gemessen am `make release-assets`-ZIP: 183 Jars, dieselbe Liste wie
in v1.12.1, darunter je eine Jackson-2- und eine Jackson-3-Linie. Der Validator bleibt
`testImplementation` und ändert daran nichts. Jackson 2 und 3 liegen in getrennten
Paketen (`com.fasterxml.jackson.*` / `tools.jackson.*`) konfliktfrei nebeneinander.
Jackson 3 hängt weiter an `jackson-annotations` aus der 2.x-Linie.

**Falle beim Umbau:** In Jackson 3 hat `JsonNode` eine eigene Methode
`map(Function)`. Sie wendet die Funktion auf den Knoten selbst an und verdeckt Kotlins
`Iterable.map`. `arrayNode.map { it.asString() }` kompiliert deshalb weiterhin, wirft
aber zur Laufzeit (`ArrayNode` lässt sich nicht in einen String umwandeln).
Über die Elemente geht es mit `values()`.

