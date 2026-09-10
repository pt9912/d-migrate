# CHECK-Ausdrücke werden geraten, nicht gelesen

## Status

Vorabklärung. Der akute Fehler ist behoben (siehe unten); was hier steht, ist
die Zielform.

## Befund

`E012` („Check expression references unknown column") muss entscheiden, welche
Wörter in einem CHECK-Ausdruck Spaltenbezüge sind. Ohne SQL-Parser ist das
nicht entscheidbar, sondern nur zu schätzen.

Die erste Schätzung — alle Wörter außer einer 28-Wörter-Schlüsselwortliste —
lehnte jeden Funktionsaufruf und jeden Cast ab. Aufgefallen ist es an einem
externen Anwender (`pg-change-feed`), der die Katalogform eines
`IN`-Ausdrucks wörtlich in seine Schemadatei schreiben wollte und vier
gemeldete Spalten bekam, die Typnamen waren. Er trägt den Constraint deshalb
als dokumentierten manuellen Schritt nach dem Rollout.

Die zweite Schätzung — Erkennung an der **Stellung** im Ausdruck statt an
Namen — ist gebaut und deckt die bekannten Formen. Sie bleibt eine Schätzung:
was ein Parser entscheidet, rät sie weiterhin.

## Zielform

`spec/jsqlparser-adapter.md` beschreibt sie bereits: SQL-Parsing liegt im
Adapter, der Hexagon-Kern bleibt parser-unabhängig. Für diesen Fall hieße das
ein Port „welche Spalten nennt dieser Ausdruck", implementiert von einem
Adapter mit einem echten Parser, und ein `SchemaValidator`, der ihn bekommt.

Ein Parser, der einen Ausdruck **nicht** lesen kann, darf dabei nicht zum
Fehler führen: dann gilt weiter „nicht entscheidbar, also nicht melden". Die
heutige zurückhaltende Erkennung bleibt damit als Rückfallweg nützlich, sie
wird nicht ersetzt, sondern vorgelagert.

## Was vor dem Bau zu messen war — gemessen

Gemessen gegen `com.github.jsqlparser:jsqlparser:5.3` (aktuelles Release,
Stand der Messung), außerhalb des Repos.

### 1. Ausdrucksfragmente — beantwortet, positiv

`CCJSqlParserUtil.parseCondExpression` liest ein blankes Prädikat, kein
Statement nötig. **Alle zwölf** bekannten Fälle parsen, und die gefundenen
Spalten sind **identisch** mit dem, was die heutige Erkennung liefert:

| Ausdruck | Parser liefert |
| --- | --- |
| `status IN ('active','deleted')` | `[status]` |
| `LENGTH(name) > 3` | `[name]` |
| `CAST(x AS integer) > 0` | `[x]` |
| `EXTRACT(YEAR FROM created_at) > 2000` | `[created_at]` |
| `amount > 0::numeric` | `[amount]` |
| `((operation)::text = ANY ((ARRAY['INSERT'::character varying, …])::text[]))` | `[operation]` |
| `created_at < CURRENT_DATE` | `[created_at]` |
| `name ~ '^[a-z]+$'` | `[name]` |
| `t.status <> ''` | `[status]` |
| `COALESCE(name,'') <> ''` | `[name]` |
| `statsu > 0` (echter Tippfehler) | `[statsu]` |

Also auch PostgreSQL-eigene Syntax: `::`-Cast, `ARRAY[…]`, der `~`-Operator.

### 2. Lizenz und CVE-Fläche — beantwortet, mit einer Auflage

**Lizenz: doppelt — LGPL-2.1 *oder* Apache-2.0.** Die Apache-Variante ist
wählbar und damit unproblematisch; zu bestätigen bleibt, dass der POM sie als
Wahl und nicht als Konjunktion meint.

**Transitive Laufzeit-Abhängigkeit: genau eine, und sie gehört nicht dorthin.**
`org.openjdk.jmh:jmh-core:1.37` steht im POM auf `compile` und nicht
`optional` — eine Benchmark-Bibliothek im Laufzeitpfad, die ihrerseits
`jopt-simple` und `commons-math3` nachzieht. Sie ist beim Einbinden
auszuschließen; ohne Ausschluss wüchse das Auslieferungsartefakt um drei
Bibliotheken, die niemand aufruft.

(PMD, Checkstyle und ein JavaCC-Versionsbereich stehen ebenfalls im POM, aber
als **Plugin**-Abhängigkeiten — reine Bauzeit, nicht transitiv. Ein erster
Blick auf die Datei legt das Gegenteil nahe; nachgesehen wurde die tatsächliche
Auflösung.)

### 3. Native Image — starker Hinweis, kein Beweis

Das Jar (1,3 MB, 527 Klassen) enthält **keinen** Reflection-Indikator
(`java.lang.reflect`, `ServiceLoader`, `Class.forName`), kein
`META-INF/services` und keine mitgelieferten Native-Image-Metadaten. Der Parser
ist JavaCC-erzeugter Java-Code, der Besucher ist zur Übersetzungszeit gebunden.

Das ist ein starker Hinweis, aber kein Beweis: GraalVM ist auf der
Entwicklungsmaschine nicht installiert (`native-image.yml` baut ausdrücklich
außerhalb des Docker-Bildes), der belastbare Nachweis ist ein Native-Bau in
CI. Nach der Hausregel aus
`adapters/driving/cli/src/main/resources/META-INF/native-image/dev.dmigrate/cli-manual/README.md`
("Alle Eintraege hier sind empirisch belegt … Keiner steht auf Verdacht") wäre
der Bau die Abnahme, nicht diese Analyse.

## Umfang, gemessen

`SchemaValidator()` wird an **10 produktiven Stellen** konstruiert (28
insgesamt mit Tests): drei in `hexagon/application`, drei im MCP-Adapter, vier
in der CLI-Verdrahtung. Ein injizierter Port geht durch alle; der Umbau läuft
über `make ast-grep`.

## Eigner-Entscheidung

Eine neue Laufzeit-Abhängigkeit im ausgelieferten Artefakt ist keine
Werkzeugwahl, sondern eine Zusage. Sie gehört entschieden, bevor gebaut wird.

Nach den Messungen steht die Frage schmal: der Parser leistet, was er soll
(Punkt 1), die Lizenz trägt (Punkt 2, mit dem `jmh-core`-Ausschluss als
Auflage), und der Native-Nachweis ist ein CI-Lauf (Punkt 3). Zu entscheiden
bleibt, ob das Werkzeug eine Bibliothek mehr im Auslieferungsartefakt tragen
soll, um eine Schätzung durch eine Entscheidung zu ersetzen.

## Berührte Stellen

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/CheckExpressionColumns.kt`
  (die heutige Erkennung)
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaStructureValidationRules.kt`
  (`validateCheckExpressionColumns`)
- [`spec/jsqlparser-adapter.md`](../../../spec/jsqlparser-adapter.md) (Zielform, bislang ungebaut)
