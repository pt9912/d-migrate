# `schema compare`: `sourceDialect` an Routinen und Triggern wird verglichen, an Sichten nicht

> **Status:** Vorabklärung / Eigner-Frage (2026-09-17). Am Code gelesen,
> nicht an einem Paar zweier Reverses gemessen.
> **Trigger:** AP2 des Umbrella
> [`compare-falsch-positive-cross-dialekt.md`](../in-progress/compare-falsch-positive-cross-dialekt.md)
> hat `sourceDialect` als Herkunfts-Marker eingeordnet und aus dem Vergleich
> genommen, aber nur an Sichten. Bei der Graduation des Umbrella fiel auf, dass
> die übrigen Objekte das Feld weiter vergleichen und dass zwei Dokumente etwas
> anderes sagen.
> **Aktivierungsbedingung:** Eine Messung an zwei Reverses mit Routinen, eine
> Konsumentenmeldung oder der ADR-Schritt des
> [Toleranzprofils](../next/compare-toleranzprofil.md), der die
> Herkunftsfelder ohnehin berührt.

## Befund

- **Code.** `SchemaComparator.compareView` setzt `sourceDialect = null` (AP2,
  `685f4ebc9`). `compareFunction`, `compareProcedure` und `compareTrigger`
  vergleichen das Feld mit `valueChangeOrNull`. `SchemaCompareSemantics` nimmt
  es vorher nicht heraus. Zwei Reverses verschiedener Dialekte mit derselben
  Routine melden deshalb vermutlich neben dem Rumpf auch
  `functions.<name>.source_dialect` (bzw. `procedures.…`, `triggers.…`).
  Die MCP-Projektion bildet das Feld ab (`ObjectDiffFields`).
- **Tests.** Gepinnt ist nur der Sichtfall („sourceDialect is not compared — it
  says where a view was read", `SchemaComparatorTestPart2`). Für Routinen und
  Trigger legt kein Test fest, ob das Feld ein Fund ist.
- **`spec/cli-spec.md`** (Pfad-Grammatik von `schema compare`) führt
  `source_dialect` als Fundfeld für `views`, `functions`, `procedures` und
  `triggers`. Für Sichten kann der Fund seit AP2 nicht mehr entstehen.
- **`next/compare-toleranzprofil.md`** („Vier Mechanismen") sagt,
  Herkunftsfelder einschließlich `sourceDialect` würden **nie** als
  Schemaeigenschaft verglichen. Das stimmt nur für Sichten.

## Die Frage

Ist `sourceDialect` an einer Routine Herkunft oder Eigenschaft?

- **Für Herkunft spricht** dieselbe Begründung wie bei AP2: Zwei Reverses
  tragen unweigerlich verschiedene Werte. Der eigentliche Unterschied steht
  schon im Rumpf, der als eigener Fund erscheint.
- **Für Eigenschaft spricht:** Bei einer Routine sagt das Feld, in welcher
  Sprache der Rumpf geschrieben ist, und `schema generate` entscheidet daran,
  ob er renderbar ist (`action_required`, `spec/cli-spec.md`). In einem
  handgeschriebenen Schema ist es eine Angabe des Autors. Bei einer Sicht gilt
  das genauso (`RawSqlTextProjection` lässt es mit dem Rumpf fallen). AP2 hat
  die Frage dort trotzdem als Herkunft beantwortet, ohne ADR.

Die Antwort gehört in einen ADR (Nachbar von ADR 0057, „die Herkunft einer
Seite ist kein Unterschied") und in die Pfad-Grammatik der Spec. Sie kann auch
lauten: Herkunft nur dann, wenn eine Seite eine Reverse-Markierung trägt. Das
entspräche dem Muster der fähigkeitsgebundenen Toleranzen (Toleranzprofil,
E3).

## Was ein Schnitt braucht

1. **Messen:** zwei Reverses mit derselben Funktion (PostgreSQL gegen SQL
   Server) über CLI und MCP vergleichen und die Funde zählen.
2. **Entscheidung und ADR**, danach die Spec (Fundfelder je Abschnitt) und die
   Zeile im Toleranzprofil nachziehen.
3. **Tests für beide Richtungen:** Der entschiedene Fall ist gepinnt, dazu die
   Gegenprobe (ein echter Rumpfunterschied bleibt ein Fund).
