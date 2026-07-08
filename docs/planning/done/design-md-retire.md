# Sub-Slice (DONE): `design.md` retired (superseded by `architecture.md`)

> **Status:** DONE (2026-06-28) — ausgeführt; `design.md` liegt unter `docs/archive/`.
> **Herkunft:** Ausgegliedert aus dem Milestone-Hygiene-Slice
> ([`spec-milestone-hygiene-slice.md`](spec-milestone-hygiene-slice.md)).
> Maßgeblich: [ADR 0024](../../adr/0024-ist-zustand-dokumentation.md) („Specs sind
> Zielbilder; kein Ist-Prosa-Doc").

## Befund

`docs/archive/design.md` ist **kein** zu entstempelnder Zielbild-Vertrag, sondern ein
**veralteter, redundanter Living-Ist/Soll-Overview**. Belege:

- `architecture.md §3.3` trägt bereits `class StreamingPipeline(sourceDriver,
  targetDriver, transformer: DataTransformer, checkpointStore)` mit Dependency-
  Graph/Topo-Sort — also **genau** das, was `design.md §3.1` als zukünftiges
  „**Soll**: Bidirektionale Pipeline mit Transformation (spätere Milestones)"
  beschreibt. `design.md`s Ist/Soll-Labels sind damit unzuverlässig.
- `architecture.md` ist die aktuelle, detaillierte Architektur-Zielbild-Quelle
  (Pipeline §3.3, Profiling §3.7, Config §4.1 + neue §3.9/§3.10); `cli-spec.md`
  besitzt die Command-Struktur; `neutral-model-spec.md`/`profiling.md` die
  Domänen. `design.md` überlagert das mit einem älteren Ist/Soll-Bild.

Konsequenz (Entscheidung A, 2026-06-28): `design.md` **retiren** statt in-place
de-Ist-en. Das ist ein Content-Migrations-Vorhaben, kein simpler `git mv`.

## Kernfrage (zuerst klären): Heimat des KI-Integrations-Designs

`design.md §4 KI-Integrations-Design` (Provider-Abstraktion, Provider-Hierarchie,
Datenschutz-Strategie, KI-Audit-Trail [`LN-030`](../../../spec/lastenheft-d-migrate.md#ln-030)/031, A/B-Testing [`LN-036`](../../../spec/lastenheft-d-migrate.md#ln-036)) ist
**einzigartig** — `architecture.md` hat **keine** KI-Integrations-Sektion (nur
§8.2 „Neuen KI-Provider hinzufügen" = Erweiterbarkeit). Abhängig davon:
`ki-mcp.md` (3 Verweise) und `ddl-generation-rules.md:890`
(`[design.md §4](#4-ki-integrations-design)`).

Optionen für die Heimat:
- **(a)** eine eigene **KI-Integrations-Zielbild-Spec** unter `spec/` (sauberste Trennung);
- **(b)** neue Sektion in `architecture.md` (eine Architektur-Quelle);
- **(c)** Fold in `ki-mcp.md` (falls der generische KI-Provider-Teil dort passt).

Empfehlung vorläufig **(a)** oder **(b)** — bei Slice-Start entscheiden.

## Section-Map (zu bestätigen in WP1)

| `design.md` | Status | Ziel-Heimat |
| --- | --- | --- |
| §1 Design-Philosophie / Kotlin-Entscheidung | tw. einzigartig | `architecture.md §1.2`; Kotlin-Wahl ~ ADR |
| §2 Domänenmodell | redundant | `neutral-model-spec.md` |
| §3 Datenverarbeitung (Pipeline/Checkpoint/Parallel/Partition/Inkrementell) | redundant | `architecture.md §3.3`, `cli-spec.md` |
| §3.6 Daten-Profiling | redundant | `architecture.md §3.7` + `profiling.md` |
| **§4 KI-Integrations-Design** | **einzigartig** | **Kernfrage oben** |
| §5 CLI-Design | redundant | `cli-spec.md` |
| §6 Format-Design (YAML/Export/Import) | redundant | `neutral-model-spec.md`, `cli-spec.md` |
| §7 Migrations-Rollback | redundant | `cli-spec.md §6`, ggf. `ledger.md` |
| §8 Fehlerbehandlung | redundant | `architecture.md §4.4`, `cli-spec.md` (Exit-Codes) |
| §9 i18n | redundant | `architecture.md §4.5`, `cli-spec.md` |
| §10 Testbarkeit | redundant | `architecture.md §6` |
| §11 Versionierung/Kompatibilität | prüfen | §11.2 Kompat-Matrix evtl. einzigartig → `architecture.md` |

## Arbeitspakete

### WP1 — Overlap-Audit
- [ ] Section-by-section bestätigen: redundant (Ziel-Heimat existiert) vs.
      einzigartig. Ergebnis: Liste der zu foldenden Inhalte.

### WP2 — Einzigartiges folden
- [ ] KI-Design-Heimat entscheiden (a/b/c) und `design.md §4` dorthin folden
      (zeitlos, ohne Ist/Soll-Stempel).
- [ ] Etwaige weitere einzigartige Reste (§1-Leitprinzipien, §11.2-Kompat-Matrix)
      in `architecture.md` aufnehmen.

### WP3 — Live-Inbound-Links umbiegen (~20, nur **gescannte**)
- [ ] „Verwandte Dokumentation [Design]": `architecture.md:1377`,
      `neutral-model-spec.md:1354`, `cli-spec.md:1898`,
      `ddl-generation-rules.md:1557`, `connection-config-spec.md:500` →
      auf `architecture.md` (bzw. KI-Heimat) umbiegen oder streichen.
- [ ] Inhaltliche Refs: `ki-mcp.md:7/290/608`, `ddl-generation-rules.md:890`
      (`#4-ki-integrations-design`), `cli-spec.md:644` („Begriffe vollständig in
      design.md") → auf die neue KI-/Begriffs-Heimat.
- [ ] `README.md:476`, `README.de.md:491`, `docs/user/guide.md:798` → `architecture.md`.
- [ ] Meine ADRs `0023` (`consulted` + Body) und `0024` (`consulted` + Body) auf
      den Archiv-Pfad bzw. `architecture.md` anpassen.
- [ ] `next/migrations-ef-core-10.md`, `next/validate-data-against-schema.md:9`,
      `in-progress/roadmap.md:426`, Hygiene-Tracker/-Slice-Erwähnungen nachziehen.
- [ ] **`done-archive/`-Refs (~70) NICHT anfassen** — frozen + scan-ausgeschlossen
      (ADR 0010).

### WP4 — Archivieren
- [ ] `git mv docs/archive/design.md docs/archive/design.md`.
- [ ] `.d-check.yml` `scan.ignore` um `docs/archive/**` ergänzen (analog
      `done-archive`, ADR 0010) — sonst bleibt die Datei gescannt. Ggf. ADR 0010
      „Abgrenzung zu docs/archive" nachziehen.

### WP5 — Verifikation
- [ ] `make docs-check` Exit 0.
- [ ] Grep: kein **gescannter** Live-Link auf `docs/archive/design.md` mehr.
- [ ] KI-Design-Zielbild hat ein eindeutiges Zuhause; `architecture.md` ist die
      einzige Architektur-Zielbild-Quelle.

## Akzeptanzkriterien

- `docs/archive/design.md` liegt unter `docs/archive/` und ist scan-ausgeschlossen.
- Kein gescanntes Dokument verweist mehr auf `docs/archive/design.md`.
- Das einzigartige KI-Integrations-Design ist als Zielbild erhalten (klare Heimat).
- `make docs-check` 0 Befunde.

## Abgrenzung

Kein Code-Touch. **Nicht** Teil des Milestone-Hygiene-Slice — der bereinigt nur
Stempel an den verbleibenden echten Zielbild-Verträgen.

## Closure (2026-06-28)

Ausgeführt. `design.md` ist nach `docs/archive/design.md` retired (scan-ausgeschlossen
via `.d-check.yml`). **Grounding-Korrektur** ggü. dem Ursprungsplan: §4 KI-Design ist
**part-built** — der reale `AiProviderPort` (Package `dev.dmigrate.server.application.ai`) + MCP-Tools
(`procedure_transform_*`) + Audit sind gebaut (0.9.6), echte Provider/CLI sind Zukunft
(roadmap 1.5.5). Daher **kein** neuer KI-Spec, sondern **Split**:

- §4.1 Port → `architecture.md` §8.2 nennt jetzt `AiProviderPort`; Vertrag lebt im Code-KDoc.
- §4.4 Audit-Trail → `ki-mcp.md` (Abwärtsref „wie im Design-Dokument" aufgelöst).
- §4.2/§4.3/§4.5 (Provider-Hierarchie, Datenschutz, A/B-CLI) → schon in roadmap 1.5.5.

~20 Live-Inbound-Refs umgebogen (Verwandte-Doku-Einträge entfernt, Pfad-Tokens →
`docs/archive/design.md`, inhaltliche KI-Refs → `ki-mcp.md`/§8). `make docs-check` grün.

**Review-Befund (Validierung) behoben:** `design.md §11.2` (Kompatibilitäts-Matrix:
Schema-Format 2 Major rückwärtskompatibel, Deprecated-Flags 2 Minor, stabile Formate
ab 1.0) und `§1.1` (Leitprinzipien) waren **nirgends sonst** abgedeckt — sie wären
beim Retire verloren gegangen. Nachgefoldet nach `architecture.md §4.6` bzw. `§1.3`.
