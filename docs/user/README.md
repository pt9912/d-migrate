# d-migrate — Dokumentation

Dieser Ordner bündelt die nutzerorientierte Dokumentation in vier
zusammenhängenden Handbüchern. Dieses Dokument ist der Einstieg.

## Die vier Handbücher

| Handbuch | Datei | Zielgruppe |
| -------- | ----- | ---------- |
| **Anwenderhandbuch** | [`anwenderhandbuch.md`](anwenderhandbuch.md) | Endanwender (CLI-Workflows) |
| **Administrationshandbuch** | [`administrationshandbuch.md`](administrationshandbuch.md) | Betrieb/Deployment |
| **Migrations-Leitfaden** | [`migrations-leitfaden.md`](migrations-leitfaden.md) | Migrations-Durchführende |
| **API-Referenz (CLI + MCP)** | [`api-referenz.md`](api-referenz.md) | Integratoren/Automatisierung |

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
- [`quality.md`](quality.md) — Qualität/Coverage: wie gemessen wird und welche
  Gates gelten (Contributor).

## Spezifikationen (Quellmaterial)

Die Handbücher leiten Inhalte aus den formalen Specs ab:
- [`../../spec/cli-spec.md`](../../spec/cli-spec.md)
- [`../../spec/mcp-server.md`](../../spec/mcp-server.md)
- [`../../spec/connection-config-spec.md`](../../spec/connection-config-spec.md)
- [`../../spec/schema-reference.md`](../../spec/schema-reference.md)
- [`../../spec/neutral-model-spec.md`](../../spec/neutral-model-spec.md)
- [`../../spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md)
- [`../../spec/type-mapping.md`](../../spec/type-mapping.md)

