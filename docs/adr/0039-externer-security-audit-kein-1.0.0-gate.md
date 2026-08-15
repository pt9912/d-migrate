---
status: accepted
date: 2026-07-19
decision-makers: pt9912
consulted: docs/planning/done/security-audit-2026-07-17.md, SECURITY.md, docs/planning/in-progress/roadmap.md
informed: docs/planning/open/audit-readiness-package.md
---

# Externer Security-Audit ist kein 1.0.0-Gate — Verschiebung auf Post-1.0.0

> **Status: accepted (2026-07-19).** Hält die Roadmap-Entscheidung normativ fest,
> dass die Zeile „Externer Security-Audit" im Milestone 1.0.0-Stable **kein
> Release-Gate** ist, sondern ein Post-1.0.0-Ziel — und benennt die 1.0.0-Interimslatte.

## Kontext und Problemstellung

Die Roadmap führt im [Milestone 1.0.0 — Stable](../planning/in-progress/roadmap.md)
die Zeile „Externer Security-Audit ⛔" **ohne** Akzeptanzkriterium (im Gegensatz zu
den QA-Zeilen mit Fußnoten). Ein **externer** Audit heißt per Definition: eine
**unabhängige dritte Partei** prüft den Code. Das ist eine Beschaffungs-/Business-
Handlung (Anbieterauswahl, Budget, Terminierung) — **kein Entwicklungsschritt** —
und wird durch das intern durchgeführte Audit **nicht** erfüllt.

d-migrate hat 2026-07-17..19 ein **internes** adversariales Vollaudit gefahren
([`security-audit-2026-07-17.md`](../planning/done/security-audit-2026-07-17.md);
Multi-Agent-Flächen, jeder Befund dreifach gegengeprüft): 18 bestätigte Befunde
(alle P1/P2 behoben), die 6 anfangs ungeprüften Restflächen nachgeholt, zwei der
methodischen Einschränkungen (P1-Live-Repro, Gson-Rekursionstiefe) verifiziert. So
gründlich das ist — es bleibt **intern** und sieht seine eigenen blinden Flecken
nicht. Zu entscheiden ist: Blockiert das Fehlen eines Dritt-Audits das 1.0.0-Stable-
Release, oder nicht?

## Entscheidungstreiber

- **Extern ≠ intern.** Der Befund-Ledger eines internen Audits ist **nicht**
  Audit-Vollständigkeit. Ein Dritt-Audit lässt sich nicht durch mehr eigenen Code
  oder mehr eigene Doku „erledigen".
- **Kein Dev-Task.** Der einzige Repo-Beitrag zu einem externen Audit ist ein
  **Audit-Readiness-Paket**; die Beauftragung selbst ist Budget/Anbieter/Termin.
- **1.0.0 hängt nicht an Fremdbeschaffung.** Dieselbe Einordnung gilt bereits für
  die anderen ⛔-Zeilen des Stable-Milestones (GraalVM, Docker Hub, SDKMAN):
  überwiegend externe Accounts/Entscheidungen, nicht Code.
- **Ehrlichkeit.** Die Zeile darf weder fälschlich abgehakt („✅, wir haben ja intern
  geprüft") noch still gelöscht werden — ein permanenter Ausschluss/eine Verschiebung
  gehört in einen ADR, nicht in eine gelöschte Tabellenzeile (Präzedenz
  [ADR 0037](0037-database-agnostic-first-staffelung.md) / Library-Publishing).

## Betrachtete Optionen

1. **Externen Audit VOR 1.0.0-Stable als hartes Gate** — Release blockiert bis ein
   Dritter geprüft hat.
2. **Zeile still aus der Roadmap streichen** — kein Nachweis der Entscheidung.
3. **Externen Audit als Post-1.0.0-Ziel deklarieren; 1.0.0-Interimslatte =
   internes Audit + Audit-Readiness-Paket; per ADR + Roadmap-Fußnote festhalten**
   (gewählt).

## Entscheidung

Gewählt: **Option 3.** Der externe Security-Audit ist **kein 1.0.0-Stable-Gate** und
wird auf **Post-1.0.0** verschoben. Die Roadmap-Zeile bleibt sichtbar `⛔` (der Audit
selbst ist unerledigt), trägt aber eine Fußnote, die diese ADR referenziert und klar
macht, dass die Zeile das Stable-Release **nicht blockiert**.

Die **1.0.0-Sicherheits-Interimslatte** ist:

1. das interne adversariale Vollaudit
   ([`security-audit-2026-07-17.md`](../planning/done/security-audit-2026-07-17.md))
   mit remediierten P1/P2-Befunden, nachgeholten Restflächen und geschlossenen
   Methoden-Lücken;
2. [`SECURITY.md`](../../SECURITY.md) mit Bedrohungsmodell (Operator ≠ Angreifer),
   Meldeprozess und Sicherheitsmaßnahmen;
3. die Security-Gates (semgrep offline, a-check-Architektur, `dependency-submission`).

> **Nachtrag 2026-08-15 — Punkt 3 traf so nicht zu.** Beim 1.0.0-Cut wurde erhoben,
> was von diesen drei Gates tatsaechlich lief: **keines verlaesslich.** `semgrep` und
> `a-check` stehen in `make gates`/`make docker-gates`, werden aber von **keinem**
> Workflow aufgerufen — die CI faehrt `ci-build`, `docs-check`, `release-assets`,
> `docker-oci-build` und `native-runtime-build`; `gates` ist kein CI-Ziel. Beide
> haengen also daran, dass jemand sie lokal tippt. `dependency-submission` lief zwar
> in der CI, scheiterte aber seit mindestens 2026-07-31 bei **jedem** `main`-Push am
> Local-Gradle-Guard (fehlendes `DMIGRATE_ALLOW_LOCAL_GRADLE=1`) — behoben am
> 2026-08-15 mit `66a27d99`, seither wird der Dependency-Graph wieder eingereicht.
>
> **Die Entscheidung dieser ADR bleibt unberuehrt** — sie betrifft die Einordnung des
> *externen* Audits, nicht den Zustand der Gates. Korrigiert wird nur die
> Tatsachenbehauptung in Punkt 3: 1.0.0 ist mit einer Interimslatte herausgegangen,
> deren dritter Pfeiler faktisch nicht trug. Punkt 1 (internes Vollaudit) und Punkt 2
> ([`SECURITY.md`](../../SECURITY.md)) sind davon nicht betroffen.
>
> Die Behebung — Gates in die CI, zeitgesteuerte Pruefung, Fruehwarnung fuer
> schweigende Gates — wird in
> [`security-gates-not-in-ci.md`](../planning/open/security-gates-not-in-ci.md)
> geführt. Nichts prueft heute zeitgesteuert; ein push-getriggertes Gate ist gegen
> CVEs prinzipiell blind, weil CVEs auftauchen, ohne dass sich das Repo aendert.

Als **benannte Folgearbeit** (macht einen späteren externen Audit billig und
glaubwürdig) wird ein **Audit-Readiness-Paket** geführt
([`audit-readiness-package.md`](../planning/open/audit-readiness-package.md)):
Auditor-Scope-/Onboarding-Doc (Attack-Surface-Map, Trust-Boundaries, Entry-Points,
Build-/Gate-Anleitung) und ein vollständiges Dependency-Inventar/SBOM. Es ist
**keine** Voraussetzung für 1.0.0.

**Schließen** lässt sich die Roadmap-Zeile ausschließlich durch einen tatsächlich
beauftragten und gelieferten Dritt-Audit-Bericht (dann `✅` mit Referenz auf Bericht/
Attestierung) — diese ADR verzichtet **nicht** auf den externen Audit, sie entkoppelt
ihn nur vom 1.0.0-Release.

## Konsequenzen

- **Positiv:** 1.0.0-Stable hängt nicht an einer Fremdbeschaffung; die Sicherheitslage
  ist dokumentiert und intern hart geprüft; die Roadmap bleibt ehrlich (kein falsches
  ✅, kein stilles Löschen).
- **Negativ:** Zum 1.0.0 gibt es **keine unabhängige Dritt-Bestätigung**; das
  Restrisiko eigener blinder Flecken bleibt. Bewusst getragen und in SECURITY.md
  transparent.
- **Abgrenzung:** Sobald ein externer Audit beauftragt und geliefert ist, wird die
  Zeile `✅`; diese ADR beschreibt nur die Nicht-Gate-Einordnung. Das Audit-Readiness-
  Paket ist Folgearbeit, kein Gate.

## Weitere Informationen

- [`security-audit-2026-07-17.md`](../planning/done/security-audit-2026-07-17.md) —
  interner Audit-Bericht inkl. „Nicht geprüft"- und „Methodische Einschränkungen"-Sektion.
- [`SECURITY.md`](../../SECURITY.md) — Bedrohungsmodell + Meldeprozess.
- [`audit-readiness-package.md`](../planning/open/audit-readiness-package.md) — die
  benannte Folgearbeit.
- [ADR 0037](0037-database-agnostic-first-staffelung.md) — Präzedenz für eine
  Post-1.0.0-Verschiebung per ADR + Roadmap-Fußnote (Library-Publishing).
