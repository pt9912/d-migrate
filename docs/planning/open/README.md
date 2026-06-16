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
| [`profiling-query-and-normalization.md`](profiling-query-and-normalization.md) | Vorschlag (Draft) | Nachruesten der in 0.7.5 bewusst zurueckgestellten Profiling-Flags `--query` und `--analyze-normalization` (Strukturanalyse, FD-Discovery, Normalisierungsvorschlaege) — ohne LLM-Schicht (Spec §10). |
| [`rest-grpc-artifact-ref-inheritance.md`](rest-grpc-artifact-ref-inheritance.md) | Vorschlag (Trigger Watch) | Auflage, dass die kuenftigen REST- (1.2.0) und gRPC- (1.1.8) Jobvertraege das opake `ServerResourceUri`-Artifact-Ref-Modell erben (MCP nutzt es bereits); aktiviert beim Bau der Services. |
| [`test-database-candidates.md`](test-database-candidates.md) | Referenzsammlung | Externe Beispieldatenbanken fuer Smoke-, Regression-, Streaming-, Resume- und Integrationsverifikation. |
