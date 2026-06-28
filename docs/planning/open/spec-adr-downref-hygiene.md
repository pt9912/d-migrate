# Spec-Hygiene: Spec→ADR-Abwärts-Verweise + Gate-Mechanisierung

> **Status:** Bestands-Schuld **behoben** (2026-06-28); offen nur noch die
> bewusst zurückgestellte `.d-check.yml`-Härtung.
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

## Befunde (Bestands-Schuld) — ✅ behoben 2026-06-28

| Stelle | Form | Fix |
| --- | --- | --- |
| `spec/cli-spec.md:302` | bare Inline-Code-Pfad zu ADR 0003 als „die zugehörige ADR" | ✅ Verweis gestrichen — die normative Mapping-Aussage steht ohnehin da. |
| `spec/ledger.md` (E061-E065, W129-W131) | „(ADR 0020)" inline an Code-Zeilen | ✅ Citation gestrichen; Code-Semantik bleibt (ADR 0020 referenziert den Ledger, nicht umgekehrt — ADR→Spec). |
| `spec/architecture.md` §7 | informelle „ADR-001..010"-Tabelle (abweichend von `docs/adr/`) | ✅ **Reframe statt Löschen** (§11.2-Lektion): Heading „ADR-Übersicht" → „Architektur-Grundsatzentscheidungen", Zellen `ADR-00N` → `N`. Die 10 Grundsatzentscheidungen bleiben erhalten, die ADR-Pretense ist raus. |
| `spec/ddl-generation-rules.md:394, 610` | „Diff-Migrationen (Plan-2 §F.5/§E.3)" Spec→**Plan**-Refs | ✅ Plan-Section-Citation gestrichen (Re-Scan-Fund, war nicht in der Erstliste). |

Re-Scan-Beleg: `grep -rnE 'docs/adr|ADR[ -]?[0-9]{3,4}|docs/planning|Plan-[0-9]' spec/` liefert leer; `make docs-check` grün.

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
- Reihenfolge: die vier Befunde sind **behoben** (2026-06-28); die Regel kann nun
  geschärft werden, ohne dass das Gate an Bestands-Schuld bricht.

## Abgrenzung

Kein Code-Touch. Nicht Teil des Milestone-Hygiene-Slice (der bereinigt nur
Versions-/Phasen-/Milestone-Stempel). Reine SDP-Richtungs-Hygiene + optionale
Gate-Härtung.
