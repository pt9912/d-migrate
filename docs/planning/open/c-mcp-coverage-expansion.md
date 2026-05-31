# C-MCP-Coverage-Erweiterung (Folge-Slice zu Phase C-MCP)

- **Status**: Draft (Trigger registriert, kein Scope-Schnitt)
- **Trigger**: Post-Closure-Review des
  [`quality-coverage-expansion-plan`](../done/quality-coverage-expansion-plan.md)
  am 2026-05-31 hat festgestellt, dass der operational MCP-Szenario-
  Test nur **einen** der zwei vom Plan benannten Tool-Pfade abdeckt
  und das Artefakt-Lesen nicht ueber die MCP-Client-Oberflaeche
  laeuft.
- **Aktivierungsbedingung**: Wenn der Coverage-Vertrag aus
  Akzeptanzkriterium §7 des Closing-Plans (MCP-E2E-Szenario in
  `:test:e2e-cli` deckt **beide** Tools `schema_reverse_start` **und**
  `schema_compare_start` ueber den MCP-Client + prueft Artefaktinhalt
  ueber `resources/read` bzw. `artifact_chunk_get`) wieder als
  DoD-Gate benoetigt wird — z. B. fuer 1.0.0-RC-Gate oder ein
  Compliance-Audit.

## Befund-Snapshot (2026-05-31)

- `test/e2e-cli/src/test/kotlin/dev/dmigrate/cli/integration/McpOperationalScenarioTest.kt:47`
  startet ausschliesslich `schema_reverse_start`. `schema_compare_start`
  wird im selben Szenario nicht aufgerufen, obwohl der Plan beide als
  Pflicht-Pfad benennt
  ([`docs/planning/done/quality-coverage-expansion-plan.md:678`](../done/quality-coverage-expansion-plan.md)).
- Die erzeugte Schema-Resource wird in
  `McpOperationalScenarioTest.kt:139` direkt ueber
  `harness.runtimeWiring().schemaStore.list(...)` geprueft, nicht
  ueber den MCP-Pfad `resources/read`. Der Plan-Text fordert
  explizit „prueft … Artefaktinhalt ueber `job_status_get`,
  `resources/read` und bei Bedarf `artifact_chunk_get`".

## Skizzierte Arbeit

- Zweiter operational MCP-Szenariotest fuer `schema_compare_start`
  oder Erweiterung des bestehenden Tests um eine zweite
  Tool-Invocation-Phase. Eingangs-Schema gegen ein leeres Snapshot
  vergleichen, terminalen Job-Status abwarten, Diff-Artefakt-URI
  ueber `job_status_get` einsammeln.
- Direkten `schemaStore.list(...)`-Call durch eine echte
  `resources/read`-Roundtrip-Pruefung ersetzen (oder ergaenzen):
  der MCP-Client liefert den Resource-Inhalt zurueck, der gegen die
  erwartete Schema-Definition validiert wird.
- Operational-Harness-Variante bleibt unveraendert
  (`AiMcpRegistries.defaultComponents(AiMcpWiring(OperationalMcpWiring(...)))`-
  Override per Plan §5.3).

## Nicht-Ziel

- Kein neuer Subprocess-Smoke; der vorhandene `mcp serve`-Lifecycle-
  Smoke bleibt aktiv.
- Kein neuer MCP-Server-Last-Test (separater Folge-Slice, siehe
  Plan §9 Out-of-Scope).
