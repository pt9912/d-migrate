# Schema-YAML: implizite YAML-1.1-Typen verändern Werte still (`values: [OFF, ON]` → `'false', 'true'`)

- **Status**: In Arbeit (2026-11-24). Richtung **A** (Eigner, 2026-11-24); P0–P4 umgesetzt, siehe „Stand" am Ende.
- **Ziel**: Ein Wert, den das Schema als Text meint, kommt als genau dieser Text an — oder `schema validate`
  bricht laut ab und nennt Feld und Abhilfe. Kein Wert wird still umgedeutet.
- **Herkunft**: Nebenbefund aus Issue #81 (Konsument). Gemessen 2026-11-24 am Arbeitsstand `1.13.0-SNAPSHOT`.
- **Vorbedingungen**: Eigner-Entscheidung zur Richtung (P1). Ändert sich, was eine bestehende Datei bedeutet
  (Richtung B), braucht es einen ADR und eine Notiz im CHANGELOG. Ändert sich der Fingerabdruck, auch einen
  neuen Algorithmus-Tag.

## Befund

Der Schema-Reader (`adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodec.kt`) liest mit Jackson über SnakeYAML und
dessen **YAML-1.1**-Resolver. Ein ungequoteter Skalar wird dabei typisiert, bevor `SchemaNodeParser` ihn als
Text liest. Gemessen mit `schema generate --target postgresql`:

| Datei | ankommender Wert | Meldung |
| --- | --- | --- |
| `values: [OFF, on, yes, No]` | `'false', 'true', 'true', 'false'` | keine, `schema validate`: 0 Warnungen |
| `values: [Y, n, maybe]` | `'Y', 'n', 'maybe'` | (unverändert) |
| `values: [010, 0x1F, 1_000, 1.50, 1e3]` | `'8', '31', '1000', '1.5', '1000.0'` | keine — `010` ist YAML-1.1-Oktal |
| `values: [~, "null"]` | `'null', 'null'` | keine — Dublette |
| `values: [2024-01-01]` | `'2024-01-01'` | (unverändert) |
| `version: 1.10` | `1.1` (Kopfzeile `v1.1`) | keine |
| `default: ON` / `default: 010` an `text` | — | E009 „Default value incompatible with column type" |
| `values: [.inf]` | — | Parse-Fehler, Exit 7 |

Besonders schwer wiegt die stille Gruppe. Die Werteliste ändert sich **inhaltlich**: `OFF`, `ON`, `yes`, `no`
werden zu zwei Werten. Eine Datenbank, die mit dem Schema angelegt wird, lehnt danach `'OFF'` ab und nimmt `'false'`
an. `schema validate` meldet nichts.

**Die Schreibseite ist schon geschützt.** `YamlImplicitAwareQuotingChecker` quotet beim Schreiben jeden String, den
der Resolver umdeuten würde ([`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046)). Ein `schema reverse` erzeugt also keine solche Datei; betroffen sind
**handgeschriebene** Dateien.

**Wahrscheinlich weitere Felder**, ungemessen: jeder Textwert, der kein Map-Schlüssel ist. Dazu gehören
Constraint- und Indexnamen (`name: on`), `ref_type`, `values` eines `custom_types`-Enums, Partitionsgrenzen,
`unique_constraint` und `schema_format`. Map-Schlüssel (Tabellen- und Spaltennamen) liest Jackson immer als Text.

## Scope-Skizze

- **P0 — Vollständig messen.** Alle Textfelder aus `spec/schema-reference.md` mit den Problemwerten oben, je für
  YAML und JSON (JSON kennt das Problem nicht, ist aber die Gegenprobe). Prüfen, ob der Fingerabdruck oder
  `schema compare` betroffen sind und ob der Daten-Import (`data import --format yaml`) denselben Resolver nutzt.
- **P1 — Richtung entscheiden (Eigner).**
  - **A — Laut statt still:** Bekommt ein Textfeld einen Nicht-Text-Skalar (Boolean, Zahl, Null), meldet
    `schema validate` einen Fehler mit Feld, gelesenem Wert und Abhilfe („in Anführungszeichen setzen").
    Optional nur, wenn die Rückwandlung nicht verlustfrei ist (`010` → `8`, `OFF` → `false`), während `1` → `'1'`
    erlaubt bleibt. Bestehende Dateien ändern ihre Bedeutung nicht, sie scheitern höchstens.
  - **B — Text wie geschrieben:** Textfelder bekommen den Skalar so, wie er in der Datei steht (Originaltext
    statt aufgelöstem Typ). Felder mit Typ (`required`, `precision`) prüfen ihre Werte selbst nach YAML 1.2
    (`true`/`false`, Dezimalzahlen). Das ändert die Bedeutung bestehender Dateien, wenn auch zur gemeinten.
  - **C — YAML 1.2 lesen** (snakeyaml-engine liegt schon im Bundle, für das Parquet-Manifest): `on`/`yes` bleiben
    Text, `010` wird aber `10`, Zahlen bleiben Zahlen. Löst das Problem nur teilweise.
  - Empfehlung vor der Messung: **A**, weil sie nichts still umdeutet und keine Datei ihre Bedeutung wechselt.
    B kann darauf folgen.
- **P2 — Umsetzung** nach P1, im Parser (`SchemaNodeParser`/`YamlSchemaCodec`). Neuer Validierungscode im
  Code-Ledger, falls A.
- **P3 — Tests.** Je Problemwert aus dem Befund ein Test am Reader bzw. Validator. Ein CLI-Test mit der Datei aus
  #81 (`values: [OFF, ON]`). Gegenprobe: gequotete Werte und JSON bleiben unverändert. Schreib-/Lese-Rundlauf
  ([`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046)) bleibt grün.
- **P4 — Doku.** `spec/schema-reference.md` (wie Textwerte zu schreiben sind), das Anwenderhandbuch (Hinweis bei
  `enum`-Werten) und der CHANGELOG.

## Akzeptanz (DoD)

**Fachlich:**
* Kein Wert aus der Befund-Tabelle wird still verändert. Je nach P1 kommt er als geschriebener Text an, oder
  `schema validate` und jeder Befehl, der die Datei liest, enden mit einem Fehler, der Feld und Abhilfe nennt.
* Die Datei aus #81 (`values: [OFF, ON]`) ergibt `('OFF', 'ON')` oder einen Fehler, nie `('false', 'true')`.
* `version: 1.10` kommt nicht mehr still als `1.1` an.
* Gequotete Werte, JSON-Schemata und die Ausgabe von `schema reverse` sind unverändert (Rundlauf-Tests aus [`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046)
  grün).

**Rot-Beleg:** Die neuen Tests scheitern ohne P2 und sind mit P2 grün. Den Fehlschlag einmal sehen und die
Rücknahme an der Ausgabe prüfen.

**Gates:**
* `make docker-check MODULES=":adapters:driven:formats :hexagon:core :adapters:driving:cli"`. Ändert sich eine
  geteilte Signatur, einmal `make docker-test` ohne `MODULES=`.
* `make solid-suppression-gate`, `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
* Auf dem Push sind „Build & Test“ und „Integration Tests“ grün.

**Doku:** `spec/schema-reference.md` beschreibt die Regel für Textwerte, das Handbuch zeigt sie am `enum`-Beispiel,
der CHANGELOG nennt die Änderung. Falls B: ADR accepted, Fingerabdruck-Tag geprüft.

## Stand (2026-11-24)

- **P1:** Richtung A, laut statt still (Eigner-Freigabe 2026-11-24). Keine Datei ändert ihre Bedeutung, kein ADR
  nötig, kein Fingerabdruck berührt (die Nutzlast entsteht erst nach dem Lesen).
- **P2:** `YamlSchemaCodec.read` liest den Baum wie bisher mit Jackson, samt der Prüfung auf doppelte Schlüssel.
  `YamlWrittenScalars.mark` vergleicht danach im Token-Strom je typisiertem Skalar den geschriebenen Text
  (`JsonParser.getText()`, gemessen: liefert ihn auch für `VALUE_FALSE`, `VALUE_NUMBER_INT` usw.) mit dem Text des
  Knotens. Bei Abweichung, und bei jedem `null` in einer Liste, ersetzt eine Unterklasse des Knotens ihn. Sie trägt
  geschriebenen Text und Pfad. Typisierte Felder sehen den Knoten unverändert. Textfelder lesen über
  `asSchemaText()` (alle `asText()`-Stellen der Schema-Parser) und brechen dort ab. Die Meldung erscheint als
  Parse-Fehler (Exit 7), etwa: `tables.t.columns.a_state.values[0]: YAML liest `OFF` als Boolean (`false`), nicht
  als Text. Setzen Sie den Wert in Anfuehrungszeichen: "OFF".`
- **P3:** `YamlSchemaCodecWrittenScalarTest`: 12 verlustbehaftete Werte (darunter Long und BigInteger) scheitern
  mit Pfad und Abhilfe. `version: 1.10` scheitert, `version: 1.0`, `[1, 2, 10, 99999999999]`, `[Y, n, maybe,
  2024-01-01]` und gequotete Werte bleiben, `required: yes` bleibt `true`. Rot-Beleg: Ohne die Markierung
  scheitern genau die 13 verlustbehafteten Specs. Der Property-Test (`YamlSchemaCodecPropertySpec`) fand
  dabei einen NPE für ein Dokument, das nur ein Skalar ist; behoben. Kein Repo-Fixture trägt einen betroffenen
  Wert (alle getrackten YAML-Dateien durchsucht).
- **P4:** `spec/schema-reference.md` („Textwerte in YAML"), Anwenderhandbuch (Hinweis am Schema-Beispiel),
  CHANGELOG.
- **Nicht im Umfang:** Andere YAML-Leser (`.d-migrate.yaml`, Overlays, `data import --format yaml`) nutzen eigene
  Mapper und sind nicht gemessen. Die Meldung E009 für `default: ON` an einer Textspalte bleibt, wie sie ist;
  sie ist laut, nennt aber nicht die Ursache.

