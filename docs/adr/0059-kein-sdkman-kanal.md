---
status: accepted
date: 2026-10-22
decision-makers: pt9912
consulted: docs/adr/0042-sdkman-kein-1.0.0-gate.md, docs/archive/sdkman-distribution.md
informed: docs/user/releasing.md, docs/planning/in-progress/roadmap.md, spec/architecture.md
---

# Kein SDKMAN-Kanal — Workflow und Plan entfallen

> **Status: accepted (2026-10-22).** Übersteuert
> [ADR 0042](0042-sdkman-kein-1.0.0-gate.md). d-migrate wird nicht über SDKMAN
> verteilt. `sdkman-release.yml` ist gelöscht, der Plan liegt unter
> `docs/archive/`, die Roadmap-Zeile entfällt.

## Kontext und Problemstellung

ADR 0042 hat SDKMAN zum Nicht-Gate erklärt und die Zeile `⛔` stehen lassen,
bis der Candidate-PR im fremden Repo gemergt ist. Das ist nie geschehen. Der
Workflow lief seitdem inert (Skip mit Notice, weil die Secrets fehlen) und
musste trotzdem gepflegt werden: Dependabot hob `sdkman-release-action`,
und v1.0.0 der Action ist ein Bruch (Login mit E-Mail/Passwort statt
Consumer-Key/-Token, andere Plattform-Kennungen, neue API), den niemand
nehmen kann, ohne neue Vendor-Zugangsdaten zu beschaffen.

## Entscheidung

SDKMAN wird nicht angeboten. Der Eigner hat entschieden, den Kanal nicht zu
betreiben (2026-10-22). Damit:

- `.github/workflows/sdkman-release.yml` entfällt.
- Der Plan `sdkman-distribution.md` wandert nach `docs/archive/` (verworfen).
- Abschnitt 4.3.4 und die Checklistenzeile in `docs/user/releasing.md` entfallen.
- Die Zeile „SDKMAN-Distribution" aus Milestone 1.0.0 der Roadmap entfällt;
  diese Entscheidung ist der Beleg, dass sie nicht still verschwunden ist
  (Regel aus ADR 0039).
- `SDKMAN_CONSUMER_KEY` und `SDKMAN_CONSUMER_TOKEN` werden nicht mehr gebraucht.

## Konsequenzen

- Der Kanal lässt sich später neu aufbauen, dann gegen die v1-API der Action;
  der archivierte Plan bleibt als Ausgangspunkt lesbar.
- Der offene Candidate-PR im Repo `sdkman/sdkman-db-migrations` wird nicht
  weiterverfolgt.
