# Reverse-Präferenzen: Restflächen je Oberfläche (MCP-Aufruf, Herkunft der Note, `data transfer`)

> **Status:** Sammlung (2026-09-17).
> **Trigger:** Drei Punkte aus dem Compare-Slice
> [`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md)
> (P10, fünfter und sechster Bauabschnitt, „Offen"). Der Slice hat die
> Lese-Präferenz `serial`/`identity` gebaut, die Präferenzen streng gemacht
> und ihnen eine Herkunft gegeben (`PreferenceSource`). Drei Oberflächen tragen
> das nur teilweise.
> **Aktivierungsbedingung:** je Punkt eigen, s. dort. Wird einer priorisiert,
> entsteht ein `next/`-Plan; die übrigen bleiben hier.

Normativer Rahmen:
[`spec/dialect-preference-mechanism.md`](../../../spec/dialect-preference-mechanism.md)
(„Wurzel, nicht Symptom", „Nicht stumm", „Reichweite der Konfiguration") und
[ADR 0027](../../adr/0027-reverse-preferences-inhaerente-mehrdeutigkeit.md).

## 1 — Die Präferenz pro MCP-Aufruf

Über MCP trägt eine Lese-Präferenz nur die Konfigurationsdatei des Servers
(`--connection-config` bzw. `--config`); `mcp serve` löst sie einmal beim Start
auf. Ein Tool-Argument an `schema_reverse_start` (und an `schema_compare_start`
mit Verbindungen) als Pendant zum CLI-Flag gibt es nicht.

Ein Abnehmer, der je Aufruf verschieden lesen will (einmal `serial`, einmal
`identity`), braucht dafür einen eigenen Schnitt:

- **Tool-Schema:** ein Argument je Präferenz oder ein Block `reversePreferences`;
  Golden der Tool-Schemata (`make golden-update`).
- **Idempotenz:** die Präferenz ändert das Ergebnis und gehört damit in den
  Fingerabdruck des Start-Aufrufs — sonst liefert ein zweiter Aufruf mit anderer
  Präferenz das alte Ergebnis.
- **Herkunft:** die Note (`R204`/`R205`) müsste das Argument als Stelle nennen;
  `PreferenceSource` kennt es nicht (Punkt 2).

**Aktivierung:** ein Abnehmer mit diesem Bedarf.

## 2 — `PreferenceSource` kennt keine Oberfläche

[`PreferenceSource`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadOptions.kt)
unterscheidet nur `FLAG` und `CONFIG`. Die Note, die eine Präferenz bestätigt
oder zu einem anderen Wert rät
([`AutoIncrementSyntaxNote`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/metadata/AutoIncrementSyntaxNote.kt),
`DeclaredPreference.advise`), nennt deshalb ohne gesetztes Flag den
Konfigurationsschlüssel — **auch** in `schema reverse`, das ein Flag hätte.
Über MCP nennt `R202` seit dem sechsten Bauabschnitt kein CLI-Flag mehr (im
Harness gemessen; das war der Befund). Die Kehrseite: in der CLI nennt sie ohne
gesetztes Flag ebenso nur `reverse.sqlite.autoincrement_width`.

Das ist spec-konform (die Spec verlangt ohne gesetztes Flag den
Konfigurationsschlüssel, „den jede Oberfläche liest") und im Slice bewusst so
gelassen. Wer in der CLI wieder das Flag nennen will, braucht eine
Oberflächenangabe im Port — dieselbe Erweiterung, die Punkt 1 für das
Tool-Argument braucht.

**Aktivierung:** zusammen mit Punkt 1, oder wenn ein Anwender den Hinweis in
der CLI als irreführend meldet.

## 3 — `data transfer` gibt keine Reader-Notes aus

`data transfer` liest beide Schemata und verwirft deren Notes. Ein
`--sqlite-autoincrement-width 64` bleibt dort ohne `R204`; „Nicht stumm" gilt
für diesen Weg nicht. `reverse.mysql.autoincrement_syntax` liest der Transfer
gar nicht (er wertet `legacy_serial_syntax` nicht aus); ein ungültiger Wert der
Breite endet dort mit Exit 7, wie in den übrigen Befehlen.

Beim Bau der strengen Präferenzen gesehen (Review Runde 4, L-3). Die Herkunft
der Breite bekam der Transfer bewusst nicht, weil sie ohne ausgegebene Notes
keine sichtbare Wirkung hätte.

**Zu klären:** wohin der Transfer Notes schreibt (er hat heute keinen
Reverse-Report), und ob die Notes beider Seiten dort hingehören oder nur die
Präferenz-Bestätigungen.

**Aktivierung:** ein Anwender, der die Breite im Transfer erklärt und sie nicht
bestätigt sieht, oder ein Report-Pfad für `data transfer`.

## Nicht hier

- `schema migrate` und `schema rollback` lesen den Ist-Stand ohne Präferenz —
  das ist Ursache 2 in
  [`sqlite-migrate-biginteger-identity-render-gap.md`](sqlite-migrate-biginteger-identity-render-gap.md).
