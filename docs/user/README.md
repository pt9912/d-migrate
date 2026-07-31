# d-migrate — Dokumentation (Beta 0.9.9)

Dieser Ordner bündelt die nutzerorientierte Dokumentation. Im Milestone
**0.9.9 (Dokumentation und Pilot-Validierung)** entstehen daraus vier
zusammenhängende Handbücher. Dieses Dokument ist der Einstieg und der
Statusüberblick.

## Die vier Handbücher

| Handbuch | Datei | Zielgruppe | Status |
| -------- | ----- | ---------- | ------ |
| **Anwenderhandbuch** | [`anwenderhandbuch.md`](anwenderhandbuch.md) | Endanwender (CLI-Workflows) | ✅ Entwurf (Review offen) |
| **Administrationshandbuch** | [`administrationshandbuch.md`](administrationshandbuch.md) | Betrieb/Deployment | 🚧 Gerüst |
| **Migrations-Leitfaden** | [`migrations-leitfaden.md`](migrations-leitfaden.md) | Migrations-Durchführende | 🚧 Gerüst |
| **API-Referenz (CLI + MCP)** | [`api-referenz.md`](api-referenz.md) | Integratoren/Automatisierung | ✅ Entwurf (Review offen) |

## Weitere Dokumente

- [`best-practices-leitfaden.md`](best-practices-leitfaden.md) — verdichtete
  Empfehlungen, Faustregeln und Anti-Patterns quer über die Aufgaben (Performance-
  Tuning, Cross-Dialect-Typ-Fallstricke, Verifikation, Rollback, Credentials, CI).
- [`troubleshooting-leitfaden.md`](troubleshooting-leitfaden.md) — schnelle Triage
  nach Exit-Code und Symptom: Diagnose-Werkzeuge und Bereichs-Playbooks (Verbindung,
  Reverse/Generate/Migrate, Daten/Verify, Cross-Dialect-Überraschungen).
- [`guide.md`](guide.md) — bestehende „Schnellstart-Anleitung". Wird in das
  **Anwenderhandbuch** überführt; bleibt vorerst als Quelle erhalten.
- [`releasing.md`](releasing.md) — Release-Leitfaden (Maintainer).
- [`quality.md`](quality.md) / [`quality-report.md`](quality-report.md) —
  Qualität/Coverage (Contributor).

## Spezifikationen (Quellmaterial)

Die Handbücher leiten Inhalte aus den formalen Specs ab:
- [`../../spec/cli-spec.md`](../../spec/cli-spec.md)
- [`../../spec/mcp-server.md`](../../spec/mcp-server.md)
- [`../../spec/connection-config-spec.md`](../../spec/connection-config-spec.md)
- [`../../spec/schema-reference.md`](../../spec/schema-reference.md)
- [`../../spec/neutral-model-spec.md`](../../spec/neutral-model-spec.md)
- [`../../spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md)
- [`../../spec/type-mapping.md`](../../spec/type-mapping.md)

## QA-Deliverables 0.9.9 (kein Handbuch, aber Teil des Milestones)

- **Performance-Benchmarks dokumentiert** — Ergebnis-Doku aus der
  `PerfMeasure`/`PerfReport`-Infrastruktur. (🔲 zu erstellen)
- **Pilotanwender-Tests (≥ 5 Tester)** — Testplan + Kandidaten-Datenbanken in
  [`../planning/open/test-database-candidates.md`](../planning/open/test-database-candidates.md).
  (🔲 Programm/Plan zu erstellen)
