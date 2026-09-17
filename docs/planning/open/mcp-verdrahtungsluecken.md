# MCP-Verdrahtungslücken: leere Indizes `diffs`/`profiles`, unerreichbarer `job_input`-Upload

> **Status:** Befund (2026-09-17), vorbestehend.
> **Trigger:** Review Runde 4 und 5 des Compare-Slices
> [`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md)
> (L-1, LOW-4; „Offen"). Der Slice hat den Vertrag des Index `diffs` in
> `spec/mcp-server.md` beschrieben; `mcp serve` erfüllt ihn nicht. Beide
> Punkte sind älter als der Slice.
> **Aktivierungsbedingung:** Ein Abnehmer, der Compare- oder Profil-Ergebnisse
> über die Listen-Werkzeuge sucht, oder ein Import über MCP mit hochgeladenen
> Eingabedaten. Beide Punkte sind Verdrahtung, keine Vertragsfrage; ein
> `next/`-Plan kann sie zusammen tragen.

## 1 — Die Indizes `diffs` und `profiles` sind in `mcp serve` leer

[`McpRuntimeWiring`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/McpRuntimeWiring.kt)
hat als Default `diffStore = EmptyDiffStore` und
`profileStore = EmptyProfileStore`. `mcp serve` baut seine Verdrahtung in
[`McpCliRuntimeWiring`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpCliRuntimeWiring.kt)
ohne diese beiden und übernimmt damit den Default; der `--server-state`-Zweig
ersetzt Job-, Schema- und Artefakt-Store, diese beiden nicht.

- **`diffs`:** `diff_list` findet das Artefakt von `schema_compare_start`
  nicht (Review Runde 4, gemessen). `spec/mcp-server.md` beschreibt seit dem
  sechsten Bauabschnitt des Compare-Slices den **Vertrag**: der Index führt das
  Compare-Artefakt mit beiden Verweisen. Gefunden wird das Ergebnis heute nur
  über `job_status_get` und `artifact_list`.
- **`profiles`:** dieselbe Verdrahtung (`EmptyProfileStore`); nicht gemessen.

**Zu tun:** echte Stores in `mcp serve` verdrahten (In-Memory- und
`--server-state`-Zweig), die Eintragung beim Ablegen des Ergebnisses (die
Lese-Jobs tragen ihr Ergebnis zuletzt in den Index ein, s. `spec/mcp-server.md`),
ein Szenario durch den MCP-Client in `:test:e2e-cli`. Für `profiles` zuerst
messen.

## 2 — Der `job_input`-Upload ist über die Leitung nicht erreichbar

[`ArtifactUploadInitHandler`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/ArtifactUploadInitHandler.kt)
kennt `uploadIntent=job_input` und delegiert an den `job_input`-Finalizer. Das
Eingabeschema von `artifact_upload_init` führt aber `approvalKey` und
`artifactKind` nicht, die dieser Weg braucht (Review Runde 4). Ein Client, der
sich an das Tool-Schema hält, erreicht den Weg nicht.

**Zu tun:** Tool-Schema und Golden (`make golden-update`) ergänzen oder den Weg
anders erreichbar machen; ein Szenario durch den MCP-Client.

## Verwandt

- Die Nachsicht von `artifact_upload_init` bei den Arten und der Filter von
  `schema_list` stehen in
  [`mcp-server-spec-hygiene-residuals.md`](mcp-server-spec-hygiene-residuals.md).
