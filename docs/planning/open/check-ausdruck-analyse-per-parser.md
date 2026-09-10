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

## Was vor dem Bau zu messen ist

1. **Native Image.** d-migrate liefert ein GraalVM-Binary aus. Ob der Parser
   dort ohne Reflection-Konfiguration läuft, entscheidet über den Aufwand und
   ist vor der Abhängigkeitsentscheidung zu prüfen.
2. **Lizenz und CVE-Fläche** des Parsers im ausgelieferten Artefakt — dieselbe
   Sorgfaltspflicht wie bei `ojdbc11` (FUTC) und den Befunden aus
   [`open/dependency-cve-exposure-shipped-artifact.md`](dependency-cve-exposure-shipped-artifact.md).
3. **Ausdrucksfragmente.** Ein CHECK ist kein Statement. Ob der Parser ein
   blankes Prädikat liest und wie er sich bei dialektspezifischer Syntax
   verhält (`::`, `~`, `ARRAY[...]`), ist zu messen, nicht anzunehmen.

## Umfang, gemessen

`SchemaValidator()` wird an **10 produktiven Stellen** konstruiert (28
insgesamt mit Tests): drei in `hexagon/application`, drei im MCP-Adapter, vier
in der CLI-Verdrahtung. Ein injizierter Port geht durch alle; der Umbau läuft
über `make ast-grep`.

## Eigner-Entscheidung

Eine neue Laufzeit-Abhängigkeit im ausgelieferten Artefakt ist keine
Werkzeugwahl, sondern eine Zusage. Sie gehört entschieden, bevor gebaut wird.

## Berührte Stellen

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/CheckExpressionColumns.kt`
  (die heutige Erkennung)
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaStructureValidationRules.kt`
  (`validateCheckExpressionColumns`)
- [`spec/jsqlparser-adapter.md`](../../../spec/jsqlparser-adapter.md) (Zielform, bislang ungebaut)
