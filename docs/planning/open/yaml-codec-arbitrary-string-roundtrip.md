# YAML-Codec: Round-Trip beliebiger String-Werte unter `MINIMIZE_QUOTES`

> Status: **Bekannte Limitierung / Folgearbeit** — noch nicht gebaut.
> Trigger: [`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046) Property-Based-Testing (`YamlSchemaCodecPropertySpec`) deckte
> auf, dass `write→read` den Fingerprint für bestimmte String-**Werte** nicht
> erhält. Der PBT-Generator (`NeutralTypeArb`) wurde auf YAML-sichere Enum-Werte
> eingeschränkt (konsistent mit der bereits YAML-sicheren Bezeichner-Erzeugung in
> `SchemaArb`), damit das PBT den **strukturellen** Round-Trip misst statt an
> YAML-Quoting-Artefakten falsch-rot zu werden. Diese Datei trackt die
> **eigentliche Codec-Limitierung**, die dabei sichtbar wurde.
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
