# Sub-Slice: Spec-Milestone-Hygiene — Umsetzung

> **Status:** In Progress (2026-06-28)
> **Umbrella:** [`spec-milestone-reference-hygiene.md`](spec-milestone-reference-hygiene.md)
> (Trigger, SDP-Regel-5-Triage-Kriterium, Roh-Bestandsaufnahme).
> **Gegenstand:** Konkreter Ausführungsplan, nachdem die zwei strukturellen
> Weggabelungen entschieden sind. Reine Doku-Hygiene am Zielbild — kein
> Verhaltens- oder Vertragswechsel am Code.

## Maßgebliche Regel

[SDP-Regel 5 (Provenance: Body vs. Changelog)](spec-milestone-reference-hygiene.md)
aus dem Umbrella: Milestone-/Phasen-/Versions-Provenienz **im
Spezifikationstext** (Teil der Soll-Aussage) ist ein Verstoß; Provenienz in
einer Rand-Versions-/Historie-Tabelle, versionierte Dateinamen,
Roadmap-/Welle-Einordnung **außerhalb** der normativen Klammer und die
Lastenheft-Milestone-Roadmap sind erlaubter Kontext. Zusätzlich: ein
**Abwärts-Zeiger** (Spec → ADR/Plan/Roadmap/Phase) im Anforderungstext ist
verboten — auch nicht als „siehe Roadmap"-Hinweis.

## Entschiedene Weggabelungen (2026-06-28)

1. **§12 „DDL-Parser" (neutral-model-spec.md) → Deferral als ADR.**
   Ein neuer **ADR 0023** „Reverse-Eingabe: Live-DB-first; DDL-Datei-Parser
   additiv-später" hält die permanente Architektur-Entscheidung fest. Der
   §12-Body bleibt zeitloses **Zielbild** (beschreibt das Parser-Modell), trägt
   keine `0.6.0`-/`(geplant)`-/`Milestone`-Stempel mehr und verweist
   **seitwärts** auf den ADR statt auf eine Version. Echo in `design.md`
   analog.

2. **Eingebettete Roadmap-Sektionen → ganz aus dem Spec.** Strenge Lesart
   „Spec = Zielbild ohne Status/Phasen": die `Einführungsreihenfolge`-/
   `Implementierungsstrategie`-Sektionen verlassen den Spec; ihr Inhalt wandert
   nach `docs/planning/in-progress/roadmap.md` (planning → spec ist ein
   erlaubter Aufwärts-Verweis; `roadmap.md` trägt für 1.1.8/1.2.0 bereits
   Milestone-Einträge mit Spec-Link).

3. **Ist-Zustand-Dokumentation → [ADR 0024](../../adr/0024-ist-zustand-dokumentation.md)
   (Option 1).** Kein dedizierter Ist-Prosa-Doc; Ist = `roadmap.md`-✅ +
   `CHANGELOG.md` + ADR + Code/Tests. Folge: `design.md` ist **kein** Living
   Ist/Soll-Doc mehr — die „Heutiger Ist-Zustand"-Annotationen **fallen weg**
   (ziehen nicht um), die „Soll"-Inhalte werden zeitloses Zielbild. „Andere Docs
   (Lastenheft) tragen auch Provenienz" ist **Ist-Stand, kein Argument** —
   `spec/` wird konsequent Zielbild.

## Behalten (kein Verstoß — explizit dokumentiert)

- **Engine-Versions-Fakten** in `ddl-generation-rules.md`: `SQLite ab
  3.2.0/3.25.0/3.35.0`, `MySQL ab 8.0.16`, `JSON ab 3.38` — Domänenfakten über
  Ziel-Engines, kein d-migrate-Provenienz.
- **Kommando-/Klassen-skopierte „nicht Teil von …"**: `schema compare`
  (neutral-model), `data transfer` (cli-spec), `TypeMapper` (architecture) —
  zeitlose Scope-Sätze ohne Version.
- **Versions-Stabilitäts-Policy** (`design.md` §11.2: „Stabile Formate ab 1.0",
  „Deprecated Flags 2 Minor-Versionen") — SemVer-Kompatibilitätsvertrag, kein
  Feature-Milestone.
- **Versionierte Dateinamen** (stabile Tokens) und **Abschnittsnummern**
  (`4.1.1`) — keine Provenienz.

> **Kein Freibrief:** Dass das **Lastenheft** (und andere Docs) heute Milestone-
> Provenienz tragen, ist **Ist-Stand, kein Prinzip** — es rechtfertigt nicht,
> Provenienz in Specs zu belassen ([ADR 0024](../../adr/0024-ist-zustand-dokumentation.md)).
> Das Lastenheft trägt dieselbe Schuld; seine Bereinigung wird separat geschnitten.

## Arbeitspakete

### WP1 — ADR 0023 + §12-/design-Entkopplung
- [x] `docs/adr/0023-reverse-eingabe-live-db-first.md` angelegt (accepted);
      `docs/adr/README.md`-Index ergänzt. **2026-06-28.**
- [x] `spec/neutral-model-spec.md` §12 entstempelt (Heading, Hinweis-Block,
      drei `(geplant)`-Subheadings, §13.4 `(ab 0.5.5)`, §14.2 `(ab 0.6.0)`/
      `(späterer Milestone)`, Status-Footer). Anker jetzt `#12-ddl-parser`.
      **Kein** Link auf ADR 0023 (SDP-konform). **2026-06-28.**
- [x] `docs/archive/design.md` DDL-Parser-Echo (§2.3) entstempelt; Peer-Link auf neuen
      §12-Anker nachgezogen (Spec→Spec). **2026-06-28.**

### WP2 — De-Stamp gebauter Features → zeitlos
- [x] `neutral-model-spec.md`: §2.2/§6.3/§13.4-Headings, Spatial-Kommentar/-Attribut,
      Geometry-Scope zeitlos, Tabellen-Metadaten, Trigger-Keys, Status-Footer. **2026-06-28.**
- [x] `ddl-generation-rules.md`: §16 Spatial (Heading + In-Body-`0.5.5`), §17 `0.9.2`,
      `reserved_only`-Option, Status-Footer; Engine-Fakten (`3.x`/`8.x`) unangetastet. **2026-06-28.**
- [x] `connection-config-spec.md`: Abwärts-Zeiger auf `implementation-plan-0.9.6`
      gekappt, Wert `≤30s` behalten. **2026-06-28.**
- [x] `design-import-sequences-triggers.md`: `Milestone 0.4.0` raus, [`LF-010`](../../../spec/lastenheft-d-migrate.md#lf-010) behalten. **2026-06-28.**
- [x] `ki-mcp.md` (`660`): Upload-Body-Vertrag zeitlos (`nicht Teil des MCP-Vertrags`). **2026-06-28.**
- [x] `profiling.md`: Header zu Zielbild, `481`/`546`/`770` Milestone-Refs entstempelt
      (Zielbild-kompatibel, bleibt in `spec/`). **2026-06-28.**

**WP2 — Nachtrag: vollständiger Sweep (2026-06-28).** Die Erstliste stammte aus
einem zu engen Grep (`ab X.Y.Z`/`Milestone`/`Phase`) und unterzählte. Ein voller
`0.x.y`-Sweep deckte ~60 weitere bare Stempel auf — alle entstempelt:
- [x] `connection-config`, `schema-reference`, `architecture`, `cli-spec`,
      `design-import` (5× `0.4.0`), `jsqlparser` (versionierte Dateinamen bleiben),
      `schema.json` (4× in `description`, JSON valide) — Hand.
- [x] `ddl-generation` (13), `neutral-model` (13, inkl. §9.1/§9.2-Heading-Anker —
      keine Inbounds), `ki-mcp` (11), `ledger` (20, keine Historie-Sektion → Body
      bereinigt) — parallele Agents, Reports geprüft.
- [x] **`ledger.md` nachträglich in Scope** (Korrektur: Code-Registry-Versionen
      sind Ist-Provenienz, kein Zielbild — [ADR 0024](../../adr/0024-ist-zustand-dokumentation.md)).
- [x] **Verifikation:** `0.x.y`-Sweep über `spec/` leer (abzüglich Engine-Fakten
      `3.x`/`8.x`, versionierte Dateinamen, Beispiel `1.0.0`, `0.0.0-reverse`,
      Lastenheft, `design.md`).

**Gemeldete Rest-Residuals (andere Kategorie, NICHT dieser Slice):**
- `ki-mcp:718` „seit Phase F" + §12 `Phase-E`/`Phase-E2`-Cross-Refs → Phase-Familie
  (Nachklang im Umbrella-Tracker).
- `ddl-generation` `Plan-2 §F.5`/`§E.3` → Spec→Plan-Abwärts-Refs →
  [`spec-adr-downref-hygiene.md`](spec-adr-downref-hygiene.md).

### WP2b — `design.md` → ausgegliedert in eigenen Retire-Sub-Slice
Bei der Analyse stellte sich `design.md` als **veralteter, redundanter** Living-
Ist/Soll-Overview heraus (superseded by `architecture.md §3.3`, das die als
„Soll" markierte Pipeline bereits real trägt). Statt In-Place-De-Ist wird
`design.md` **retired** — ein eigenes Content-Migrations-Vorhaben (einzigartiges
§4 KI-Design folden, ~20 Live-Links umbiegen, `git mv` nach `docs/archive/`):
→ [`design-md-retire.md`](design-md-retire.md). **Nicht** Teil
dieses Slice. Die in WP1 an `design.md §2.3` gemachten Edits wandern mit ins
Archiv (DDL-Parser-Zielbild lebt ohnehin in `neutral-model-spec.md §12`).

### WP3 — Roadmap-Sektionen aus dem Spec ziehen (+ Umnummerierung) — **✅ 2026-06-28**
- [x] `rest-service.md` §10 entfernt, §11→§10; Inline-`Phase 2` (`129`) entstempelt.
      Reihenfolge → `roadmap.md` 1.2.0.
- [x] `grpc-service.md` §10 entfernt, §11→§10. Reihenfolge → `roadmap.md` 1.1.8.
- [x] `ki-mcp.md` §11 entfernt, §12→§11, §13→§12. (0.9.6 done/historisch → kein Fold.)
- [x] `shadow-migration.md` §27 entfernt, §28–32 → §27–31 (Skript-Renumber, 20 Headings);
      Header-`Zielversion`-Zwilling raus. Strategie → `roadmap.md` 2.0.0-Vision.
- [x] `profiling-datasketches.md` §22 entfernt, §23–27 → §22–26 (17 Headings);
      Header-`Zielversion`-Zwilling raus. Strategie → `roadmap.md` 1.0.0-RC.
- [x] Verifikations-Grep: keine `Einführungsreihenfolge`/`Implementierungsstrategie`
      und keine nummerierten Phasen-/Milestone-/Zielversion-Stempel mehr in `spec/`.

### WP4 — Abschluss & Graduierung
- [ ] Definition of Done (unten) vollständig erfüllt.
- [ ] Umbrella-Tracker + dieses Doc nach `done/` graduieren (mit `## Closure`,
      die den finalen Stand samt Belegen zusammenfasst).

## Definition of Done

Zweistufig getrennt: **Verifizierung** (haben wir es *richtig* gebaut —
objektiv, reproduzierbar) und **Validierung** (haben wir das *Richtige* gebaut —
Intent/Ziel erfüllt, menschlich gegen das Triage-Kriterium beurteilt).

### Verifizierung (richtig gebaut — reproduzierbar)
- [ ] `make docs-check` Exit 0 (Anker, Links, ADR-IDs verlinkt, keine `.../`).
- [ ] Grep-Sweep ohne Treffer im **normativen** Spec-Text:
      `grep -rnoE '0\.(4|5|6|7|9)\.[0-9]+' spec/` und
      `grep -rnE 'Phase[ -]?[0-9]|Milestone|geplant' spec/`, abzüglich der
      dokumentierten Allowlist (Engine-Fakten `3.x`/`8.x`, Lastenheft-Roadmap,
      versionierte Dateinamen, Abschnittsnummern, MSSQL/Oracle-Dialekt-Marker).
- [ ] Spec-Section-Nummern lückenlos nach WP3: pro betroffener Datei zeigt
      `grep -nE '^## [0-9]+\.'` eine fortlaufende Folge ohne Lücke.
- [ ] Keine toten Anker: kein Doc referenziert die entfernten/umbenannten
      §-Anker mehr (Grep der Alt-Anker liefert leer).
- [ ] Keine **neuen** Spec→ADR-Abwärts-Refs eingeführt; ADR 0023 trägt
      `status: accepted` und steht im `docs/adr/README.md`-Index.

### Validierung (das Richtige gebaut — Review gegen Intent)
- [ ] SDP-Durchsicht (Stichprobe gegen das Triage-Kriterium): jede **entfernte**
      Provenienz war Teil der Soll-Aussage (echter Verstoß); jede **behaltene**
      war Kontext (kein Over-Cleaning).
- [ ] Die entstempelten Stellen lesen sich als kohärentes **Zielbild** — keine
      sinnentstellten Sätze, keine verwaisten „in 0.6.0"-Reste, die zuvor
      Bedeutung trugen.
- [ ] Die nach `roadmap.md` verschobenen Inhalte sind dort **vollständig** und
      am passenden Milestone verortet (kein Informationsverlust; Aufwärts-Link
      zur Spec vorhanden).
- [ ] Ziel erreicht: `spec/` ist Zielbild **ohne Status/Phasen**; ein Leser
      versteht den Soll-Zustand ohne Milestone-Kontext.
- [ ] Maintainer-Abnahme (pt9912) der finalen Diff.

## Abgrenzung

Kein Code-Touch, keine Vertragsänderung am Verhalten. Die `mcp-server.md`-
§-Referenz-Restschuld bleibt im separaten Tracker
[`../open/mcp-server-spec-hygiene-residuals.md`](../open/mcp-server-spec-hygiene-residuals.md).

## Closure (2026-06-28)

Alle In-Scope-WPs geliefert. `make docs-check` grün (133/0); voller `0.x.y`-Sweep
über `spec/` leer (abzüglich Engine-Fakten `3.x`/`8.x`, versionierte Dateinamen,
Beispiel `1.0.0`, `0.0.0-reverse`, Lastenheft, `design.md`).

- **WP1** ADR 0023 (DDL-Parser-Deferral) + §12/§2.3 entstempelt.
- **WP2** ~80 Versionsstempel über 12 Specs entstempelt (Hand + 4 parallele
  Agents); Engine-Fakten/versionierte Dateinamen/Beispiel-Versionen/ADR-Refs
  behalten. `ledger.md` nachträglich in Scope (Korrektur, ADR 0024).
- **ADR 0024** „Ist-Doku-Policy" (kein Prosa-Ist-Doc; Ist = roadmap/CHANGELOG/
  ADR/Code).
- **WP3** fünf Roadmap-Sektionen (`rest §10`, `grpc §10`, `ki-mcp §11`,
  `shadow §27`, `datasketches §22`) aus den Specs entfernt + umnummeriert
  (Skript-Renumber) + Reihenfolge/Strategie nach `roadmap.md` gefoldet.

**Verifizierung-DoD** erfüllt; **Validierung** (Maintainer-Diff-Durchsicht durch
pt9912) bestätigt → Graduierung.

**Ausgegliederte Folgearbeit (eigene Tracker):** `design.md`-Retire
([`design-md-retire.md`](design-md-retire.md)); Spec→ADR/Plan-
Abwärts-Refs + d-check-Härtung
([`spec-adr-downref-hygiene.md`](spec-adr-downref-hygiene.md));
Phase-E/F-Familie (Umbrella-Nachklang); Lastenheft-Roadmap (separater Scope).
