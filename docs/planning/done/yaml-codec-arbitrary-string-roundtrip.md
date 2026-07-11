# YAML-Codec: Round-Trip beliebiger String-Werte unter `MINIMIZE_QUOTES`

> Status: **DONE / graduiert** — gebaut & abgenommen 2026-07-11 (Option: custom
> `StringQuotingChecker`). Siehe „## Closure" unten.
> Trigger: [`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046) Property-Based-Testing (`YamlSchemaCodecPropertySpec`) deckte
> auf, dass `write→read` den Fingerprint für bestimmte String-**Werte** nicht
> erhält. Ein Interim (Generator-Einschränkung auf YAML-sichere Enum-Werte,
> Commit `4a744dfd`) hielt v0.9.10 grün; dieser Slice schließt die **eigentliche
> Codec-Limitierung** und **nimmt die Generator-Einschränkung wieder zurück**, so
> dass das PBT den Fix beweist statt ihn zu umgehen.
> Severity/Charakter: **echter, aber seltener Datenverlust-Bug** — trifft nur
> Schemata mit YAML-mehrdeutigen String-Werten (Enum-Labels, String-Defaults).

## Kern

`YamlSchemaCodec.writeMapper` nutzt `YAMLGenerator.Feature.MINIMIZE_QUOTES`
(saubere, unquotierte Ausgabe). Die Quoting-Entscheidung der **Schreib**-Seite
(Jackson) deckt sich aber nicht mit dem **Lese**-Resolver (SnakeYAML, YAML 1.1
Implicit Types). Folge: ein String-Wert, den Jackson unquoted schreibt, aber
SnakeYAML beim Lesen als Nicht-String auflöst, verliert seine Identität:

| Wert (geschrieben) | gelesen als | Ursache |
|---|---|---|
| `4.` | `4.0` (Float→String) | Jackson sieht es nicht als Zahl, schreibt unquoted; SnakeYAML löst als Float |
| `9_` | `9` (Int→String) | YAML-1.1-Ziffern-Trenner `_` |
| `yes` / `no` / `on` / `off` | `true` / `false` (Bool→String) | YAML-1.1-Booleans |
| `~` / `null` | `null` | YAML-Null |

Der Schreib-Only-Halbfix `ALWAYS_QUOTE_NUMBERS_AS_STRINGS` deckt nur `4.` (nicht
`9_`/`yes`) und wurde daher bewusst **nicht** eingebaut (partiell + inkonsistent).

## Praktische Relevanz

Real vorkommend v. a. bei **Enum-Labels** aus dem Reverse: PostgreSQL-Enum-Labels
und CHECK-`IN`-Werte sind beliebige Strings; ein Label `yes`, `no` oder `2024`
ist legal. String-**Defaults** ebenso. Kein aktuell gemeldeter Consumer-Befund —
Priorität entsprechend niedrig, aber ein echter Korrektheits-Gap.

## Optionen (bei Aufnahme zu entscheiden)

1. **`MINIMIZE_QUOTES` abschalten** — jeder String-Scalar wird gequotet →
   garantiert round-trip-sicher. Preis: user-sichtbare Output-Regression (jedes
   `type: text` → `type: "text"`), bricht Format-Assertions (z. B.
   `YamlSchemaCodecTest` `full_text_access_method: gin`) → Test-Updates nötig.
2. **Lese-Resolver auf YAML 1.2 Core** angleichen (kein `yes/no/on/off`, keine
   Underscore-Zahlen) + `ALWAYS_QUOTE_NUMBERS_AS_STRINGS` für den Zahl-Rest.
   Behält saubere Ausgabe; hängt an der Jackson-/SnakeYAML-Version + Resolver-API.
3. **Gezieltes Quoting** nur für Frei-Text-Felder (Enum-Werte, String-Defaults)
   beim Baumaufbau — erfordert per-Node-Quote-Steuerung, die Jacksons
   Tree-Serialisierung nicht direkt bietet.

## Definition of Done (bei Aufnahme)

- `YamlSchemaCodecPropertySpec` läuft mit **unbeschränktem** String-Generator
  (Rücknahme der `yamlSafeToken`-Einschränkung in `NeutralTypeArb`) grün.
- Gewählte Option dokumentiert; Output-Format-Änderung (falls Option 1) in
  Golden-/Format-Tests nachgezogen.

## Closure (2026-07-11)

**Gewählt: die saubere Realisierung von Option 3** — ein custom
`StringQuotingChecker`, ermöglicht durch Jackson 2.21.2
(`YAMLFactory.builder().stringQuotingChecker(...)`; das Ticket hielt „gezieltes
Quoting" fälschlich für „nicht direkt möglich"). **Kein ADR** (Bugfix, der den
Output-Vertrag *erhält* — keine permanente Ausschluss-Entscheidung).

Geliefert:
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/yaml/YamlImplicitAwareQuotingChecker.kt`:
  Subklasse von `StringQuotingChecker.Default`, quotet einen Wert zusätzlich, wenn
  **SnakeYAMLs Resolver** (derselbe, den die Lese-Seite nutzt) ihn zu einem anderen
  Tag als `Tag.STR` auflöst. Keine Nachbildung der Implicit-Type-Grammatik →
  Schreib/Lese-Symmetrie garantiert; harmlose Strings bleiben unter
  `MINIMIZE_QUOTES` unquotiert (**kein Output-Regress**, die Format-Assertions
  `full_text_access_method: gin` / `type: fulltext` halten).
- Verdrahtet in `YamlSchemaCodec.writeMapper`.
- **Generator-Einschränkung `yamlSafeToken` in `NeutralTypeArb` zurückgenommen**
  → Enum-Werte/refType sind wieder unbeschränkte `Arb.string`.

Abnahme: unbeschränktes `YamlSchemaCodecPropertySpec` grün; temporärer 20000-Iter-
Repro grün (volle `Arb.string`-Domäne inkl. Control-Chars/Unicode — kein
Repräsentierbarkeits-Problem); Fokus-Test `YamlSchemaCodecImplicitStringTest`
(`4.`/`9_`/`yes`/`~`/`2024-01-01`/… round-trippen, harmlose bleiben unquotiert);
`formats:check` + `core:check` (detekt + koverVerify-90%) grün.
