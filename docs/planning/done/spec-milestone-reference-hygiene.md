# Spec-Hygiene: Milestone-/Phasen-/Versions-Provenienz in `spec/`

> **Status:** In Progress (2026-06-28) — aus `../open/` aktiviert; Triage und
> Bereinigung laufen.
> **Umsetzung:** konkreter Ausführungsplan (entschiedene Weggabelungen,
> Arbeitspakete, Akzeptanz) im Sub-Slice
> [`spec-milestone-hygiene-slice.md`](spec-milestone-hygiene-slice.md).
> **Trigger:** Bei der Bereinigung stale Milestone-Anker im Anwenderhandbuch
> (`0.5.5`, `0.9.7`) fiel auf, dass dieselbe Provenienz **breit in `spec/`**
> steckt.
> **Scope:** Milestone-/Phasen-/Versions-Provenienz im Spec-*Text* gegen die
> SDP-Regel 5 triagieren (s. u.); Abschnittsnummern und Engine-Versions-Fakten
> (SQLite/MySQL) bleiben. **Projekt-Entscheidung 2026-06-28** (strenger als die
> Regel-5-Default): eingebettete `Einführungsreihenfolge`-/
> `Implementierungsstrategie`-Roadmap-Sektionen verlassen den Spec ganz (Details
> im Sub-Slice). Verwandt:
> [`../open/mcp-server-spec-hygiene-residuals.md`](../open/mcp-server-spec-hygiene-residuals.md)
> (gleiche Kategorie, §-Referenzen).

## Maßgebliche Regel (Regelwerk v1.2.0)

Verbindlich ist das **Stable Dependencies Principle / Referenz-Richtung (SDP)**
aus `lab-regelwerk` v1.2.0, `grundlagen-konventionen.md`,
„Referenz-Richtung (SDP): wer darf wen referenzieren" — dort **Regel 5
(Provenance: Body vs. Changelog)**:

> *„Ein Abwärts-Zeiger im Anforderungs-/Entscheidungs-Text ist verboten.
> Provenance in einer abgegrenzten Versions-/Historie-Tabelle am Dokument-Rand
> ist Kontext und für alle Artefakte erlaubt … Der Unterschied ist nicht der
> Stabilitätsrang, sondern ob die Referenz Teil der Spezifikations-Logik ist."*

Daraus das **Triage-Kriterium** (nicht „alle Versionsnummern raus"):

- **Verstoß** — Milestone-/Phasen-/Versions-Provenienz **im Spezifikationstext**
  (Teil der Soll-Aussage), z. B. inline `(ab 0.5.5)` in einer
  `schema.json`-`description`, „Geplanter Milestone: 0.7.5" im Fließtext,
  „Phase 1/2/3" als Implementierungs-Phasing im Spec-Body, ein Verweis auf
  `implementation-plan-0.9.6` als Begründung.
- **Erlaubt (Kontext)** — Provenienz in einer abgegrenzten **Versions-/Historie-
  Tabelle am Dokument-Rand**; **versionierte Dateinamen** als stabile Tokens
  (`warn-code-ledger-0.9.2.yaml`); **Roadmap/Welle**-Einordnung (steht außerhalb
  der normativen Klammer); das **Lastenheft mit seiner Milestone-Roadmap**
  (Vertrags-Stratum + Wellen-Plan, kein Abwärts-Zeiger im Anforderungstext).

Weitere Regel: Ein **dauerhaftes Nicht-Ziel**, das heute als „nicht Teil von
X.Y.Z" im Spec-Text steht, gehört als **ADR** (permanente Architekturregel),
nicht als versionsgestempelter Spec-Satz.

## Kandidaten (Bestandsaufnahme 2026-06-17, vor Triage)

Treffer pro Datei (Rohzählung inkl. Falsch-Positiver — vor Bewertung):

| Datei | ~Treffer | Erste Einschätzung |
| --- | --- | --- |
| `spec/neutral-model-spec.md` | 19 | viele `ab 0.5.5`/`Phase 1` — **Triage** |
| `spec/ddl-generation-rules.md` | 19 | `Milestone 0.5.5`/`Phase 1`/`nicht Teil von 0.5.5` — **Triage** |
| `docs/archive/design.md` | 18 | gemischt (Roadmap-Kontext vs. Provenienz) — **Triage** |
| `spec/ledger.md` | 16 | überw. **legitim** (Ledger sind per Minor-Version versioniert) + einige `ab 0.9.7`-Provenienz |
| `spec/ki-mcp.md` | 21 | **Triage** (ImpPlan-B/Phasen-Reste, vgl. mcp-Hygiene-Tracker) |
| `spec/rest-service.md` / `spec/grpc-service.md` | 5 / 3 | `Phase 1/2/3`-Implementierungs-Phasing — **Triage** |
| `spec/profiling.md` | 3 | `ab Phase 2 (0.7.5)`/`Milestone 0.6.0`/`Geplanter Milestone 0.7.5` — **Triage** |
| `spec/schema.json` | 2 | inline `(ab 0.6.0)`/`0.9.7:` Provenienz im Vertrag — **Triage** |
| `spec/connection-config-spec.md` | 1 | Verweis auf `implementation-plan-0.9.6 §4.1` (abwärts) — **Triage** |
| `spec/schema-reference.md` | 2 | `seit 0.9.3 … E122` — Verhaltensänderung, Wortlaut prüfen |
| `spec/lastenheft-d-migrate.md` | 5 | **legitim** — Anforderungsdoku MIT Milestone-Roadmap (deren Zweck); Treffer sind überw. Abschnittsnummern (`4.1.1`) |
| `spec/catalog-publisher-lakehouse-targets.md` | 1 | `Akzeptanzkriterien für 1.6.0` — Future-Milestone-Spec, vermutl. legitim |
| weitere (architecture, cli-spec, shadow-migration, datasketches, design-import-…) | je 1–4 | einzeln prüfen |

> **Teil-erledigt (2026-06-27, via ADR 0022):** drei Docs der Phase-E-Familie bereinigt —
> `hexagonal-port.md` und `phase-e2-persistence.md` (phasen-/plan-benannte Nicht-Zielbild-Docs) als
> gelieferte Pläne nach [`../done-archive/`](../done-archive/) verschoben; `phase-e-port-atomicity.md`
> (Zielbild-**Vertrag**, kein Plan) **de-phast** → umbenannt nach `spec/port-atomicity.md`,
> Status/Geltung-Header und „Phase E"-Erwähnungen im Body entfernt. Alle drei aus der Liste entfernt.
> **Nachklang für diesen Tracker:** verbleibende Phase-E-Provenienz in den Labels von `spec/ki-mcp.md`
> und `spec/job-contract.md` („Phase-E2"-/„Phase-E"-Cross-Ref-Überschriften) sowie in
> `docs/operations/job-executor.md` als Familie auflösen.

> Die Zählungen sind eine Roh-`grep`-Stichprobe (`X.Y.Z` / `Phase N` /
> `Milestone`) **inklusive** Falsch-Positiver (Abschnittsnummern wie `4.1.1`,
> versionierte Ledger-Dateinamen, SRID `4326`, Beispiel-Schema-Versionen
> `1.0.0`). Sie dient nur der Priorisierung, nicht als Befundliste.

## Vorgehen (bei Aktivierung)

1. SDP-Regel 5 aus `lab-regelwerk` v1.2.0
   (`grundlagen-konventionen.md`, §Referenz-Richtung) erneut gegenlesen — die
   aktuelle Release-Version ziehen, nicht den Default-Branch.
2. Pro Treffer entscheiden:
   - **Provenienz im Spec-Text** → entfernen oder in eine Rand-Versions-/
     Historie-Tabelle verschieben (Soll-Aussage ohne Milestone/Phase).
   - **Kontext-Provenienz** (Rand-Tabelle, versionierter Dateiname, Roadmap,
     Lastenheft-Roadmap, Abschnittsnummer) → **legitim, lassen**.
3. Dauerhafte Nicht-Ziele, die heute als „nicht Teil von X.Y.Z" im Spec-Text
   stehen, als **ADR** überführen (permanente Architekturregel), nicht als
   versionsgestempelten Spec-Satz.
4. `make docs-check` grün halten (Anker/Links).

## Abgrenzung

Reine **Doku-Hygiene am Zielbild** — kein Verhaltens- oder Vertragswechsel am
Code. Bereits erledigt: dieselbe Bereinigung im **Anwenderhandbuch**
(`0.5.5` ×3, `0.9.7` ×1).

## Closure (2026-06-28)

Die Milestone-/Phasen-/Versions-Provenienz-Triage ist abgeschlossen — ausgeführt
im Sub-Slice [`spec-milestone-hygiene-slice.md`](spec-milestone-hygiene-slice.md)
(WP1–WP3 + ADR 0023/0024). `spec/` ist phasenfrei (voller `0.x.y`-Sweep leer,
abzüglich Engine-Fakten/versionierter Dateinamen/Beispiele/Lastenheft/`design.md`);
`make docs-check` grün.

**In eigene Tracker abgegrenzt:** `design.md`-Retire
([`design-md-retire.md`](design-md-retire.md)); Spec→ADR/Plan-
Abwärts-Refs ([`../open/spec-adr-downref-hygiene.md`](../open/spec-adr-downref-hygiene.md));
Phase-E/F-Cross-Ref-Familie; Lastenheft-Roadmap.
