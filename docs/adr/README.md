# Architecture Decision Records

Dieses Verzeichnis enthält Architecture Decision Records (ADRs) im
**MADR**-Format (Markdown Any Decision Records). ADRs halten
relevante Entscheidungen fest, deren Begründung sich nicht aus
dem Code oder der Commit-Message ergibt — typischerweise
Severity-Entscheidungen, Layering-Trade-offs oder Carve-outs, die
spätere Leser sonst neu durchkauen würden.

Das Format folgt dem
[offiziellen MADR-Template](https://adr.github.io/madr/) auf
[adr.github.io](https://adr.github.io/), das mehrere legitime
ADR-Formate katalogisiert. Wir haben MADR gegenüber der
klassischen Michael-Nygard-Form gewählt, weil das YAML-
Frontmatter abfragbare Metadaten (`status`, `date`,
`decision-makers`) trägt und die expliziten Sektionen
`Decision Drivers` / `Considered Options` / `Pros and Cons` zu der
Art passen, wie in diesem Repo Entscheidungen in Review-Zyklen
argumentiert werden.

## Konventionen

- **Sprache**: ADR-Prosa ist auf Deutsch zu verfassen
  (siehe [ADR-0004](0004-documentation-and-planning-structure.md)
  „Sprachhinweis"). Das YAML-Frontmatter bleibt englisch
  (`status: accepted`, `date: …`) — MADR-Spezifikation, nicht
  durch lokale Konvention überschreibbar. MADR-Sektionsnamen
  (`Decision Drivers`, `Considered Options`, `Pros and Cons of the Options`,
  `More Information`) können englisch bleiben oder sinngemäß
  übersetzt werden (`Entscheidungstreiber`, `Betrachtete Optionen`,
  `Pros und Cons der Optionen`, `Weitere Informationen`); die
  ADRs 0001-0004 nutzen die deutschen Übersetzungen.
- **Dateinamen**: eine Datei pro Entscheidung,
  `NNNN-kurz-titel.md` (vierstellige Nummer mit führenden Nullen,
  Bindestrich-getrennter lowercase-Slug).
- **Template**: vom
  [offiziellen MADR-Template](https://github.com/adr/madr/blob/main/template/adr-template.md)
  kopieren und nicht zutreffende Sektionen entfernen.
  Pflicht-Sektionen: `Context and Problem Statement` /
  `Kontext und Problemstellung`, `Considered Options` /
  `Betrachtete Optionen`, `Decision Outcome` / `Entscheidung`.
  Optional, aber empfohlen: `Decision Drivers`, `Consequences`,
  `Confirmation`, `Pros and Cons of the Options`,
  `More Information`.
- **Status-Werte** folgen der MADR-Konvention:
  `proposed`, `accepted`, `rejected`, `deprecated`,
  `superseded by ADR-XXXX`.
- **Supersession**: Die ursprüngliche Datei bleibt liegen, ihr
  `status` wird auf `superseded by ADR-XXXX` aktualisiert, die
  nachfolgende ADR wird im Abschnitt `More Information` /
  `Weitere Informationen` referenziert.

## Index

| # | Titel | Status |
|---|---|---|
| 0001 | [`MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC` ist WARNING, kein BLOCKER](0001-mysql-routine-drop-create-non-atomic-warning.md) | accepted |
| 0002 | [`UNSAFE_DEPENDENCY_PAIR` bleibt WARNING, kein BLOCKER](0002-unsafe-dependency-pair-warning-not-blocker.md) | accepted |
| 0003 | [Cross-Dialect-Sequencing — Capability-Vertrag](0003-cross-dialect-sequencing.md) | accepted |
| 0004 | [Lebenszyklus des Planungsverzeichnisses (`open/` → `next/` → `in-progress/` → `done/`)](0004-documentation-and-planning-structure.md) | accepted |
| 0005 | [`writerFactoryBuilder`-Invariante: Output-Mode statt Output-Pfad](0005-writerfactorybuilder-output-mode-invariant.md) | accepted |
| 0006 | [Wiring-Drift-Exception-Familie: `IllegalStateException` als gemeinsamer Typ](0006-wiring-drift-exception-family.md) | accepted |
| 0007 | [MCP-Parquet-Isolation: vier Verteidigungslinien](0007-mcp-parquet-isolation-defense-in-depth.md) | accepted |
| 0008 | [MCP-Transport ohne SSE-/Server→Client-Push](0008-mcp-no-sse-push.md) | accepted |
| 0009 | [MCP-Server als OAuth-Resource-Server (kein eigener Authorization Server, keine DCR)](0009-mcp-resource-server-no-auth-server.md) | accepted |
| 0010 | [Eingefrorenes Done-Archiv (`done-archive/`) und d-check-Scan-Ausschluss](0010-done-archive-und-gate-scan-ausschluss.md) | accepted |
| 0011 | [d-check-`codepaths`-Scope und dauerhafte Pfad-Ausnahmen](0011-d-check-codepaths-scope-und-dauerhafte-ausnahmen.md) | accepted |
| 0012 | [Index-Präfixlänge nur auf Index-Spalten — PK-/Constraint-Spalten tragen keine Länge](0012-index-prefix-length-scope.md) | accepted |
