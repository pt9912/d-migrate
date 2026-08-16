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
| 0013 | [Sample-DB-Sourcing: Pagila/Sakila vendoren, Employees on-demand — Quellen gepinnt](0013-sample-db-sourcing.md) | superseded by ADR-0014 |
| 0014 | [Sample-DB-Harness: On-Demand-Fetch + docker-compose/Scripts](0014-sample-db-harness-fetch-and-compose.md) | accepted |
| 0015 | [Volltext-Spalten (PostgreSQL `tsvector`) als first-class neutraler Typ](0015-fulltext-tsvector-neutral-type.md) | accepted |
| 0016 | [SpatiaLite-Metadaten-Bootstrap im Migrate-Diff-Pfad (`InitSpatialMetaData`)](0016-spatialite-metadata-bootstrap.md) | accepted |
| 0017 | [Phase-4-Benchmark-Workload: Generator-Tool pinnen statt statischem Dump](0017-tpc-benchmark-workload-sourcing.md) | accepted |
| 0018 | [Normierte Mess-Umgebung für die LF-8.2-Performance-Abnahme](0018-normalized-perf-measurement-environment.md) | accepted |
| 0019 | [Partitions-Hierarchie: strukturierte `PartitionDefinition` statt opaker Dialekt-Strings](0019-partition-hierarchy-structured-representation.md) | accepted |
| 0020 | [Cross-Dialect-Partitionierung PG↔MySQL: Mapping-Entscheide (AP6)](0020-cross-dialect-partitioning-mysql.md) | accepted |
| 0021 | [Spalten-Ordinalreihenfolge erhalten (Hybrid: geordnete Serialisierung + explizites `ordinal`)](0021-column-ordinal-fidelity.md) | accepted |
| 0022 | [Ports-Schicht ohne `java.sql`: neutrale `DatabaseConnection`-Abstraktion](0022-ports-jdbc-entkopplung.md) | accepted |
| 0023 | [Reverse-Eingabe: Live-DB-first; DDL-Datei-Parser additiv-später](0023-reverse-eingabe-live-db-first.md) | accepted |
| 0024 | [Ist-Zustand-Dokumentation: keine Prosa-Ist-Doku, `spec/` ist reines Zielbild](0024-ist-zustand-dokumentation.md) | accepted |
| 0025 | [Volltext-Quellspalten als `FULLTEXT`-Index modellieren, nicht am `fulltext`-Typ](0025-fulltext-source-columns-as-index.md) | accepted |
| 0026 | [Dialektbewusste Fingerprint-Kanonisierung im Post-Compare (Projektion statt Prädikat, `v6`→`v7`)](0026-fingerprint-kanonisierung-post-compare.md) | accepted |
| 0027 | [Reverse-Präferenzen: inhärente Mehrdeutigkeiten per deklarierter Präferenz auflösen (nicht Heuristik/Fold)](0027-reverse-preferences-inhaerente-mehrdeutigkeit.md) | accepted |
| 0028 | [a-check-Architektur-Gate: G1 zuerst, strikte Composition-Root-JDBC-Regel](0028-a-check-architecture-gate-scope.md) | accepted |
| 0029 | [Property-Based-Testing-Framework: kotest-property statt Jqwik](0029-property-based-testing-framework.md) | accepted |
| 0030 | [Datenwert-Kanonisierung für `data transfer --verify` (LN-009)](0030-datenwert-kanonisierung-verify.md) | accepted |
| 0031 | [Atomarer Rollback auf Checkpoint-Ebene: Clean-Load-Kompensation (`--atomic`, LN-013)](0031-atomic-clean-load-rollback.md) | accepted |
| 0032 | [Paralleler Datenpfad: Tabellen- und Partitions-Parallelität (`--parallel`, LN-007 + LN-008)](0032-paralleler-datenpfad-tabellen-partitionen.md) | accepted |
| 0033 | [Konfigurierbarer JDBC-`fetchSize` + Verdrahtung von `pipeline.chunk_size`/`fetch_size` (LN-005)](0033-konfigurierbarer-fetchsize-und-pipeline-tuning.md) | accepted |
| 0034 | [Master-Key-Architektur für den verschlüsselten Credential-Store (LN-025)](0034-master-key-architektur-credential-store.md) | accepted |
| 0035 | [Credential-Provider-Scheme-Registry (O4-Naht, LN-025 Slice 2)](0035-credential-provider-scheme-registry.md) | accepted |
| 0036 | [Library-Artefakte über GitHub Packages statt Maven Central](0036-library-artefakte-github-packages.md) | accepted |
| 0037 | [„Database-Agnostic First": Zielbild bestätigt, Umsetzung nach 1.0.0](0037-database-agnostic-first-staffelung.md) | accepted |
| 0038 | [PostgreSQL-SSL-Default bleibt `prefer` — `verify-full` ist opt-in](0038-ssl-default-prefer-verify-full-opt-in.md) | accepted |
| 0039 | [Externer Security-Audit ist kein 1.0.0-Gate — Verschiebung auf Post-1.0.0](0039-externer-security-audit-kein-1.0.0-gate.md) | accepted |
| 0040 | [Keychain-Credential-Provider: Backend-Port mit Default-Shell-out und opt-in Native-Modul](0040-keychain-credential-provider-backend-port.md) | accepted |
| 0041 | [Das publizierte JVM-OCI-Image kommt aus der Dockerfile-`runtime`-Stage, nicht aus Jib](0041-oci-image-aus-dockerfile-runtime-statt-jib.md) | accepted |
| 0042 | [SDKMAN-Distribution ist kein 1.0.0-Gate — 1.0.0 wird ohne SDKMAN geschnitten](0042-sdkman-kein-1.0.0-gate.md) | accepted |
| 0043 | [Interface-Default-Methoden ohne `$DefaultImpls`-Brücke (`-jvm-default=no-compatibility`)](0043-interface-default-ohne-defaultimpls.md) | accepted |
| 0044 | [Kein natives `macos-arm64`-Binary — GraalVM-Auslieferung auf Linux und Windows begrenzt](0044-kein-macos-native-binary.md) | accepted |
| 0045 | [Ein Branch statt `develop` → `main` — Entwicklung nach v1.0.1 auf `main`](0045-ein-branch-main-statt-develop-main.md) | accepted |
