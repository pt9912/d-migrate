# Oracle ist in den Compare-Harnesses von `examples/mcp-e2e` nie gefahren

> **Status:** Befund (Test-Infrastruktur), 2026-09-17.
> **Trigger:** Die E2E-Harnesses des Compare-Slices
> [`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md)
> (fünfter Bauabschnitt, „E2E-Harnesses"; „Offen"). Beide Harnesses kennen
> Oracle als Opt-in; gefahren wurde es weder dort noch im Konsumenten-Repro.
> **Aktivierungsbedingung:** Ein Messhost ohne fremden `mcp-e2e-oracle-1`, oder
> ein getrenntes Compose-Projekt für Oracle. Danach einmal beide Opt-ins fahren
> und die Oracle-Zellen der Matrix pinnen.

## Befund

- `make mcp-e2e-roundtrip-oracle` und `make mcp-e2e-compare-matrix-oracle`
  existieren (Kaltstart 2–3 Minuten, Profil `oracle` in
  [`examples/mcp-e2e/docker-compose.yml`](../../../examples/mcp-e2e/docker-compose.yml)).
- Auf dem Messhost läuft ein Container `mcp-e2e-oracle-1`, der zum
  Compose-Projekt des Harness gehört, aber **nicht** vom Harness angelegt ist
  (ein fremder Container). Der Opt-in würde ihn übernehmen und seine Objekte
  löschen; deshalb ist er in keinem Lauf gefahren.
- Folge: die Oracle-Zeile und -Spalte der 5x5-Matrix sind **nicht** gepinnt
  ([`examples/mcp-e2e/expected/compare-matrix.env`](../../../examples/mcp-e2e/expected/compare-matrix.env)
  sagt „Oracle nicht gemessen"); ohne den Opt-in werden sie weder gemessen noch
  geprüft. Der Workflow „MCP-E2E Compare-Matrix" fährt ohne Oracle.
- `make mcp-e2e-down` lässt den fremden Container stehen; das Netz des
  Compose-Projekts bleibt dadurch belegt.

## Was ein Lauf braucht

1. Den fremden Container klären (wem gehört er, darf er weg) — oder den
   Harness unter einem eigenen Projektnamen fahren, damit er ihn nicht trifft.
2. Beide Opt-ins fahren; die Matrix mit `--update-expectations` bewusst neu
   pinnen und den Diff der Erwartungsdatei lesen.
3. Erwartbar sind Oracle-eigene Zustände (Quotierung kleingeschriebener
   Bezeichner, Identity-Sequenznamen wie bei PostgreSQL, ADR 0057). Was davon
   ein Befund ist, entscheidet der Lauf, nicht diese Notiz.
4. Ob der Workflow Oracle mitnimmt, ist eine eigene Frage (Laufzeit,
   Image-Größe).
