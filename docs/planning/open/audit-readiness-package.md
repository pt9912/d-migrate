# Audit-Readiness-Paket (Folgearbeit zu ADR 0039)

> **Status:** Kern-Deliverables **GELIEFERT 2026-07-19**, **kein 1.0.0-Gate**
> ([ADR 0039](../../adr/0039-externer-security-audit-kein-1.0.0-gate.md)). Die
> Roadmap-Zeile „Externer Security-Audit" schließt dieses Ticket **nicht** — nur ein
> beauftragter + gelieferter Dritt-Audit-Bericht tut das.
> **Zweck:** Einen späteren **externen** Security-Audit billig und glaubwürdig machen,
> indem der Repo-Beitrag (das Einzige, was Dev liefern kann) vorbereitet ist.
>
> **Geliefert:** [`docs/security/audit-scope.md`](../../security/audit-scope.md)
> (Auditor-Onboarding/Scope + Readiness-Index) · [`docs/security/dependency-inventory.md`](../../security/dependency-inventory.md)
> (aufgelöstes Runtime-SBOM, 239 Artefakte). Pointer aus [`SECURITY.md`](../../../SECURITY.md).

## Kontext

[ADR 0039](../../adr/0039-externer-security-audit-kein-1.0.0-gate.md) entkoppelt den
externen Security-Audit vom 1.0.0-Release. Der einzige Repo-Beitrag zu einem
Dritt-Audit ist ein Readiness-Paket. Vieles existiert schon; hier stehen nur die
Lücken.

## Schon vorhanden (kein Handlungsbedarf)

- [`SECURITY.md`](../../../SECURITY.md): Bedrohungsmodell (Operator ≠ Angreifer,
  in/out of scope), Meldeprozess, Sicherheitsmaßnahmen.
- Interner Befund-Ledger [`security-audit-2026-07-17.md`](../done/security-audit-2026-07-17.md)
  mit Remediation-Status, „Nicht geprüft"- und „Methodische Einschränkungen"-Sektion.
- Security-Gates: semgrep (offline), a-check-Architektur, `dependency-submission`.

## Geliefert (2026-07-19)

1. ✅ **Auditor-Onboarding-/Scope-Doc** → [`docs/security/audit-scope.md`](../../security/audit-scope.md):
   Angriffsfläche konsolidiert — Trust-Boundaries, Entry-Points (MCP-HTTP-Pipeline/stdio,
   CLI-Datenpfad, Credential-Store, JWT/JWKS, Dialekt-SQL-Generierung), Datenflüsse
   untrusted → privilegiert (mit den bestätigten Befundklassen), Krypto-/Auth-Orte,
   Build-/Reproduktions-Anleitung, Gate-Tabelle.
2. ✅ **SBOM/Dependency-Inventar** → [`docs/security/dependency-inventory.md`](../../security/dependency-inventory.md):
   239 aufgelöste Runtime-Artefakte des CLI-Shadow-Jars (via `docker build … cli:dependencies`),
   Regenerier-Anleitung, Kern-Dep-Schnellzugriff. Schließt die „~6-Pakete"-Lücke des
   Dependency-Graphen (`dependency-submission.yml` liefert es zusätzlich ab main-Push).
3. ✅ **Readiness-Index** — als §9 + Querverweise in `audit-scope.md` realisiert (Bericht,
   Tickets, Gates, SBOM an einem Einstiegspunkt); Pointer aus `SECURITY.md`.

## Nicht-Ziel

Die Beauftragung/Durchführung des externen Audits selbst (Budget/Anbieter/Termin) —
das ist eine Business-Entscheidung, kein Dev-Task, und schließt die Roadmap-Zeile
(nicht dieses Ticket).
