# `schema compare` sagt `DIFFERENT`, zeigt aber keinen Unterschied

> **Status:** Befund (2026-09-17), zwei Stellen.
> **Trigger:** Beim Bau des Compare-Slices
> [`compare-projektion-und-normalisierung.md`](../done/compare-projektion-und-normalisierung.md)
> gesehen (P2b bzw. Review Runde 3, M1) und dort unter „Offen" geführt; nicht
> Teil jenes Slices.
> **Aktivierungsbedingung:** Ein Abnehmer, der Ergebnisse maschinell auswertet
> (Status gegen Fundliste bzw. Zusammenfassung), oder der nächste Slice, der die
> Projektion von `schema compare` anfasst. Beide Stellen sind klein und ohne
> Eigner-Frage; ein `next/`-Plan kann sie zusammen tragen.

Beide Stellen haben dieselbe Form: der Status sagt „verschieden", die Stelle,
an der ein Abnehmer den Grund sucht, ist leer oder zählt null.

## 1 — Eine Partitionierungs-Änderung hat keinen MCP-Fund

`TableDiff` trägt `partitioning`; die Projektion von `schema_compare` (und
damit auch `schema_compare_start`) kennt dafür keinen Fund-Code. Eine Tabelle,
die sich nur in der Partitionierung unterscheidet, ergibt `status: different`
ohne Eintrag in `findings`.

Als bekannte Lücke gepinnt:
[`ObjectDiffFieldsCompletenessTest`](../../../adapters/driving/mcp/src/test/kotlin/dev/dmigrate/mcp/registry/ObjectDiffFieldsCompletenessTest.kt)
nimmt `partitioning` aus und prüft die Ausnahme zusätzlich — kommt ein Fund
dazu, fällt der Test auf und die Ausnahme dort weg.

**Zu tun:** ein Fund-Code (Pfad nach dem Pfad-Schema in `spec/cli-spec.md`,
`…partitioning`), `details` in der Kurzform, Eintrag in `spec/mcp-server.md`;
die CLI-Ausgabe daneben prüfen. Die Messung der server-vergebenen
Partitionsnamen
([`compare-serververgebene-namen-messauftrag.md`](compare-serververgebene-namen-messauftrag.md))
hängt über MCP an diesem Fund.

## 2 — „0 change(s)" bei `DIFFERENT`

Unterscheiden sich zwei **handgeschriebene** Schemata nur in `name` oder
`version`, meldet die CLI `DIFFERENT` (Exit 1) und in der Zusammenfassung
„0 change(s)"
([`CompareRendererPlain`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompareRendererPlain.kt)):
`totalChanges` zählt die Metadaten nicht mit. Bei zwei Reverses tritt der Fall
seit dem Compare-Slice nicht mehr auf (Name und Version sind dort kein
Vergleichsgegenstand, ADR 0057).

**Zu tun:** die Zählung um die Metadaten ergänzen oder die Zusammenfassung
getrennt ausweisen; JSON/YAML-Ausgabe (`summary`) mitprüfen, weil der
Roundtrip-Harness in `examples/mcp-e2e` seine Fundzahl aus `summary` bildet.
