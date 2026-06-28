# Spec-Hygiene: Spec→ADR-Abwärts-Verweise + Gate-Mechanisierung

> **Status:** Sammlung/Tracker (2026-06-28)
> **Trigger:** Beim Milestone-Hygiene-Slice
> ([`../done/spec-milestone-hygiene-slice.md`](../done/spec-milestone-hygiene-slice.md))
> bekräftigte der Maintainer das Ziel **„Specs verweisen NIE auf ADRs"**. Dabei
> fielen drei Bestands-Verstöße auf — eine **andere** SDP-Kategorie als die dort
> bereinigten Milestone-Stempel.
> **Aktivierungsbedingung:** Bei Aufnahme nach `../in-progress/`; klein genug für
> einen direkten Commit. Verwandt:
> [`spec-milestone-reference-hygiene.md`](../done/spec-milestone-reference-hygiene.md),
> [`mcp-server-spec-hygiene-residuals.md`](mcp-server-spec-hygiene-residuals.md).

## Maßgebliche Regel

`spec/` ist die stabilste Schicht und verweist **nie abwärts** — auch nicht auf
ADRs. Die *eine* erlaubte Richtung ist **ADR → Spec** (s. ADR 0023/0024). „Andere
Docs machen es auch" ist **kein** Argument (Ist-Stand, kein Prinzip).

## Befunde (Bestands-Schuld)

| Stelle | Form | Empfehlung |
| --- | --- | --- |
| `spec/cli-spec.md:302` | bare Inline-Code-Pfad `docs/adr/0003-cross-dialect-sequencing.md` als „die zugehörige ADR" | Verweis streichen — die normative Code-Mapping-Aussage steht ohnehin da; Begründung lebt im ADR (ADR→Spec). |
| `spec/ledger.md` | Textnennungen „ADR 0020"/„ADR 0011" als Code-Provenienz (Warn-/Fehlercodes) | **Heikel** — der Ledger ist ein Code-Registry; die ADR-Nennung ist Provenienz, kein Anforderungs-Zeiger. Entscheiden, ob Registry-Provenienz als Ausnahme gilt (SDP-Regel 5) oder die ADR-Begründung in den Ledger inline wandert. |
| `spec/architecture.md` (Z. 1286–1295) | informelle „ADR-001..010"-Tabelle (abweichend von `docs/adr/` nummeriert) | Eigener Fall: entweder als selbsttragende Architektur-Entscheidungs-*Zusammenfassung* belassen (keine Links auf `docs/adr/`) oder ganz entfernen (Entscheidungen leben in `docs/adr/`). |

## Gate-Mechanisierung (`.d-check.yml`) — **bewusst zurückgestellt**

> „d-check machen wir später." (Maintainer, 2026-06-28)

Lücke der heutigen `matrix`: sie verbietet `spec→adr` und `spec→plan`, aber
**link-/token-basiert** — bare Pfade (cli-spec:302) und Textnennungen
(`ledger`, `architecture`) rutschen durch. Kandidaten bei Aktivierung:

- `ids`-Pattern realistisch + spec-sicher: `ADR-\d{4}` → `ADR[ -]\d{3,4}`,
  `spec/**` aus dem `ids`-Scope nehmen (in Specs gilt: **keine** ADR-Refs, das
  ist `matrix`-Sache, keine Link-Pflicht).
- Prüfen, ob d-check Richtungs-Erkennung über bare Pfade/Text unterstützt
  (Config gibt es nicht her — ggf. Tool-Feature oder Review-Sache).
- Reihenfolge: **erst** die drei Befunde fixen, **dann** die Regel schärfen
  (sonst bricht das Gate an der Bestands-Schuld).

## Abgrenzung

Kein Code-Touch. Nicht Teil des Milestone-Hygiene-Slice (der bereinigt nur
Versions-/Phasen-/Milestone-Stempel). Reine SDP-Richtungs-Hygiene + optionale
Gate-Härtung.
