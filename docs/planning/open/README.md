# Trigger Watches und offene Vorabklärungen

Dieser Ordner sammelt Trigger, offene Folgearbeiten und Vorabklärungen,
die noch **keinen konkreten Scope** tragen. Sobald ein Scope formuliert
ist (Ziel + grobe Arbeitspakete + Akzeptanzkriterien), wandert der
Eintrag nach `../next/`.

Lebenszyklus und Verzeichnisstruktur sind in
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
festgehalten.

## Konvention für Einträge

- Sprechender lowercase-kebab-Dateiname (z. B.
  `test-database-candidates.md`,
  `d-browser-integration-coupling-assessment.md`).
- Jeder Eintrag beschreibt im Kopf:
  - **Status**: `Draft` / `Vorschlag` / `Sammlung` / `Vorabklärung`
  - **Trigger**: was hat den Eintrag motiviert?
  - **Aktivierungsbedingung**: was muss passieren, damit er nach
    `../next/` wandert?
- Worked Examples und reine Referenz-/Sammlungs-Dokumente
  (z. B. `test-database-candidates.md`) dürfen hier liegen, auch
  wenn sie nie nach `next/` migrieren — sie sind dauerhaft
  "trigger-/referenz-haft" ohne Slice-Charakter.

## Wann **nicht** hierher

- Plan mit ausgearbeitetem Scope und Phasen → `../next/`.
- Aktiv bearbeiteter Slice mit Implementierungs-Commits →
  `../in-progress/`.
- Geliefert und geschlossen → `../done/`.
- Verworfen / vollständig überholt → `docs/archive/` (existiert
  bei Bedarf, siehe [ADR-0004](../../adr/0004-documentation-and-planning-structure.md)).

## Bestand

| Datei | Typ | Gegenstand |
| ----- | --- | ---------- |
| [`adapter-coverage-uplift.md`](adapter-coverage-uplift.md) | Draft | Folge-Plan zu Quality-Coverage Phase E.2: Scope-Schnitt fuer heutige Kover-Excludes auf Live-JDBC- und Streaming-Adapter-Glue fehlt noch. |
| [`beispiel-stored-procedure-migration.md`](beispiel-stored-procedure-migration.md) | Worked Example | Beispiel fuer KI-gestuetzte Stored-Procedure-Migration von PostgreSQL nach MySQL. |
| [`cli-unimplemented-commands.md`](cli-unimplemented-commands.md) | Sammlung/Tracker | In `cli-spec.md` (Zielbild) beschriebene, aber noch nicht in der CLI registrierte Befehle (`transform procedure`, `generate procedure`, `data seed`, `validate data/procedure`, `config …`) + offene Trigger-Erweiterungen — Spec ↔ Requirement ↔ Milestone. |
| [`d-browser-integration-coupling-assessment.md`](d-browser-integration-coupling-assessment.md) | Vorabklaerung | Bewertung sichtbarer Kopplungen zwischen `d-browser` und `d-migrate` vor einem dedizierten `source-d-migrate`-Adapter. |
| [`mcp-server-spec-hygiene-residuals.md`](mcp-server-spec-hygiene-residuals.md) | Sammlung/Tracker | Rest-Hygiene in `spec/mcp-server.md` nach der Entphasung: 19 bare `§`-Referenzen (Stil + mehrdeutiges Ziel ImpPlan-B/ki-mcp) und eine möglicherweise veraltete `tools/call`-Verhaltensaussage (Vertrags-Genauigkeit). |
| [`pk-constraint-prefix-length.md`](pk-constraint-prefix-length.md) | Vorabklaerung (Trigger) | Praefixlaengen fuer PRIMARY-KEY-/Constraint-Spalten (`List<String>` traegt heute keine Laenge); bewusst out of scope der Index-Praefix-Scheibe (ADR 0012), aktiviert bei Bedarf (insb. Praefix-PK auf TEXT/BLOB). |
| [`reverse-column-ordinal-order.md`](reverse-column-ordinal-order.md) | Vorabklärung (Trigger) | `schema reverse` bewahrt die Spalten-**Ordinalreihenfolge** nicht (alphabetisiert); kosmetisch für relationale Korrektheit (Daten zellgenau verlustfrei), aber Reverse-Fidelity-Lücke + bricht byte-basierte Export-Vergleiche. Beim TPC-4c-Spike entdeckt; nicht 4c-blockierend. |
| [`large-schema-superlinear-scaling.md`](large-schema-superlinear-scaling.md) | Vorabklärung (Trigger) | Diff-/Render-Pipeline skaliert **super-linear** für große GEMISCHTE Schemas (N=100 ~0,4 s vs. N=1000 ~52 s; reine Tabellen ~linear). Quelle = Dependency-Topologie der Views/Trigger (Hypothese O(n²)). Beim TPC-4d-Bau entdeckt; nicht LF-blockierend (LN-004 erfüllt). |
| [`import-throughput-copy-path.md`](import-throughput-copy-path.md) | Vorabklärung (Trigger) | PG-**Import** ~5,4× langsamer als die COPY-Decke (#2-Vergleich): Ursache = gebatchtes Prepared-`INSERT` statt COPY-Protokoll. „Bulk-import" = COPY-Fast-Path (pgjdbc `CopyManager`), ABER COPY kann die `valuePlaceholder`-Bind-Ausdrücke (Geometrie/JSON/SRID) nicht → nur für transformations-freie Spalten, sonst INSERT/Staging. Nicht LF-blockierend. |
| [`profiling-query-and-normalization.md`](profiling-query-and-normalization.md) | Vorschlag (Draft) | Nachruesten der in 0.7.5 bewusst zurueckgestellten Profiling-Flags `--query` und `--analyze-normalization` (Strukturanalyse, FD-Discovery, Normalisierungsvorschlaege) — ohne LLM-Schicht (Spec §10). |
| [`spec-milestone-reference-hygiene.md`](spec-milestone-reference-hygiene.md) | Sammlung/Tracker | Milestone-/Phasen-/Versions-Provenienz (`ab 0.5.5`, `Phase 1/2/3`, `0.9.7:`) breit in `spec/` gegen die SDP-Regel „Spec = Zielbild" triagieren; Ziel 0.9.9. Anwenderhandbuch ist bereits bereinigt. |
| [`rest-grpc-artifact-ref-inheritance.md`](rest-grpc-artifact-ref-inheritance.md) | Vorschlag (Trigger Watch) | Auflage, dass die kuenftigen REST- (1.2.0) und gRPC- (1.1.8) Jobvertraege das opake `ServerResourceUri`-Artifact-Ref-Modell erben (MCP nutzt es bereits); aktiviert beim Bau der Services. |
| [`test-database-candidates.md`](test-database-candidates.md) | Referenzsammlung | Externe Beispieldatenbanken fuer Smoke-, Regression-, Streaming-, Resume- und Integrationsverifikation. |
| [`tool-comparison.md`](tool-comparison.md) | Referenzsammlung | Capability-Matrix d-migrate vs. Migrations-/Schema-/Bulk-Load-Tools (Ora2Pg, AWS SCT+DMS, Atlas, Liquibase/Flyway, pgloader, COPY, pg_bulkload), quellenbelegt + due-diligence-gehärtet. **#2 Head-to-Head erledigt** (`make sample-db-tool-compare`: PG→PG-Durchsatz COPY vs. d-migrate ~4,6× vs. pgloader ~1,7×, diagnostisch). **Kein** Audit-Zahlen-Ziel. |
