# Spec-Hygiene: Spec→ADR-Abwärts-Verweise + Gate-Mechanisierung

> **Status:** In Progress (2026-06-28) — Bestands-Schuld behoben (commit `419762da`);
> jetzt aktiv: `.d-check.yml`-Gate-Härtung (`matrix`/`ids`).
> **Trigger:** Beim Milestone-Hygiene-Slice
> ([`../done/spec-milestone-hygiene-slice.md`](../done/spec-milestone-hygiene-slice.md))
> bekräftigte der Maintainer das Ziel **„Specs verweisen NIE auf ADRs"**. Dabei
> fielen drei Bestands-Verstöße auf — eine **andere** SDP-Kategorie als die dort
> bereinigten Milestone-Stempel.
> **Aktivierungsbedingung:** Bei Aufnahme nach `../in-progress/`; klein genug für
> einen direkten Commit. Verwandt:
> [`spec-milestone-reference-hygiene.md`](../done/spec-milestone-reference-hygiene.md),
> [`mcp-server-spec-hygiene-residuals.md`](../open/mcp-server-spec-hygiene-residuals.md).

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

## Gate-Mechanisierung (`.d-check.yml`) — ✅ umgesetzt 2026-06-28

d-check auf **v0.30.0** gehoben (Makefile-Digest + `d-check.mk`). Empirisch (Wegwerf-
Test-Spec) validiert, was gegatet ist:

| Regression-Vektor in einer Spec | Modul | gefangen? |
| --- | --- | --- |
| Markdown-Link `[ADR 0020](../adr/…)` | `matrix` (`spec→adr` allow:false) | ✅ `matrix-forbidden` |
| Text-Nennung „ADR 0020" | — | ❌ review-pflichtig |
| Inline-Code `` `ADR 0021` `` | — | ❌ review-pflichtig |
| bare Pfad `` `docs/adr/0003-…` `` | — | ❌ **Rest-Lücke** |

**Prinzipieller Schutz = `matrix` (Link-Vektor).** Ein zwischenzeitlich getestetes
`ids`-ADR-Broadening (`ADR[ -]\d{3,4}` + `link-policy: always`) fängt zwar Text-/
Inline-Nennungen, wurde aber **verworfen**: es ist genau das „zu breite Hand-Muster",
das die D2-Entscheidung bewusst ausschloss. Das `ids`-Modell bleibt **schmal**
(`ADR-\d{4}`, kein Broadening); `link-policy: always` gilt konsistent für alle
Patterns (UC/ADR/LF-LN — Inline-Code auch link-pflichtig).

**Rest-Lücke (Tool-Limit, nicht config-fixbar — Handbuch-bestätigt):** Die `matrix`
erkennt nur Markdown-Links, nicht bare Inline-Code-Pfade — auch in v0.30.0. Der
`docs/adr/…`-Pfad-Vektor (cli-spec:302-Stil) und der Spec→**Plan**-Inline-Vektor
(`docs/planning/…`, `Plan-N`) bleiben **review-pflichtig**; die Plan-**Link**-Form
fängt `matrix`. v0.30.0-Neuerung `direction: no-downward` ist klassen**intern** —
für unseren klassen**übergreifenden** Fall nicht einschlägig.

## Closure (2026-06-28)

Beide Teile erledigt: Bestands-Schuld behoben (commit `419762da`) + Gate-Stand
geklärt — `matrix` (Link-Vektor) ist der prinzipielle Schutz, das `ids`-ADR-Broadening
wurde als D2-Verstoß verworfen, d-check auf v0.30.0 gebumpt. Die Rest-Lücke (bare-Pfad / Spec→Plan-
Inline) ist dokumentiertes Tool-Limit, review-pflichtig — keine offene Config-Arbeit.

## Abgrenzung

Kein Code-Touch. Nicht Teil des Milestone-Hygiene-Slice (der bereinigt nur
Versions-/Phasen-/Milestone-Stempel). Reine SDP-Richtungs-Hygiene + optionale
Gate-Härtung.
