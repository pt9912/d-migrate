---
status: accepted
date: 2026-05-16
decision-makers: pt9912
consulted: code-review-Agents (E.1 Slice C.3 Post-Commit-Review)
informed: E.1-Follow-up-Reviewer
---

# `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC` ist WARNING, kein BLOCKER

## Kontext und Problemstellung

E.1 Slice C.3 hat einen `DROP + CREATE`-Fallback für MySQL-
Routinen eingeführt, deren `RoutineCapability` sich zu
`Disabled` auflöst (kein `CREATE OR REPLACE` für die jeweilige
Routine-Art auf dem Zielserver). MySQL-DDL committet implizit
zwischen Statements: schlägt `CREATE` nach erfolgreichem `DROP`
fehl (Syntaxfehler, Privilegienwechsel, Recompile-OOM), ist die
Routine weg, ohne automatisches Rollback. Der Dependency-Guard
weiß nur, ob der Edge-Graph für den Fallback überhaupt `SAFE`
ist — er kann das Betriebsrisiko zwischen den beiden Statements
nicht modellieren. `ReplaceFunction` / `ReplaceProcedure` tragen
`risk.up = SAFE`, weil der Operator-Intent ein Body-Swap ist, also
flaggt der Destructive-Guard (`--allow-destructive`) das Paar auch
nicht. Welche Severity soll der Renderer nutzen, um das
Implicit-Commit-Risiko sichtbar zu machen?

## Entscheidungstreiber

- Der Operator muss das Risiko sehen, **bevor** er die Migration
  ausführt.
- Der SAFE-gegateder `DROP + CREATE`-Pfad ist die einzige
  praktikable Alternative zu `MANUAL_ACTION_REQUIRED`, wenn der
  Zielserver für die Routine-Art kein `CREATE OR REPLACE`
  anbietet.
- Die bestehende Destructive-Guard-Pipeline arbeitet auf
  *Operations*-, nicht auf *Statement*-Ebene.

## Betrachtete Optionen

- **WARNING-Severity-Diagnostic** neben den gerenderten
  Statements.
- **BLOCKER-Severity-Diagnostic** (würde den Fallback unterdrücken).
- **INFO-Severity-Diagnostic** (rein hinweisend).
- **Op-Level Destructive-Risk markieren**.

## Entscheidung

Gewählt: **WARNING-Severity-Diagnostic**, weil sie das
Implicit-Commit-Risiko im operator-zugewandten Report sichtbar
macht, ohne den Fallback-Pfad zu blockieren, den der
Dependency-Guard explizit erlaubt hat. Plan §3 Step 5 sagt, SAFE
erlaubt den Fallback; BLOCKER würde dem widersprechen, und INFO
ist zu leise — Operatoren überfliegen INFO-Einträge routinemäßig.

### Konsequenzen

- Gut, weil Operator-Reports für jede
  `Disabled`-Capability + `SAFE`-Guard-MySQL-Routine-`Replace`
  die WARNING tragen; Tooling, das auf WARNING-Severity
  eskaliert, fängt sie ein.
- Gut, weil der SAFE-gegateter Fallback-Pfad erreichbar bleibt —
  kein zusätzliches manuelles Gate.
- Schlecht, weil Operatoren, die `WARNING`-Triage automatisieren,
  blockierende Semantik erwarten könnten; die WARNING ist
  hinweisend, kein harter Stopp.
- Neutral, weil eine spätere transaktionale DDL in MySQL
  (MariaDB hat partielle Unterstützung) diese ADR ersetzen könnte.

### Bestätigung

`MysqlDiffRoutineOpsTest` pinnt die WARNING auf jedem
SAFE-Guard-Pfad und prüft ihre Abwesenheit auf UNSAFE-/UNKNOWN-
Block-Pfaden — die Testsuite bestätigt den Vertrag direkt.

## Pros und Cons der Optionen

### WARNING

- Gut, weil operator-sichtbar, ohne den Fallback zu blockieren.
- Gut, weil `DiffDiagnostic.Severity.WARNING` in Report und
  Tooling-Pipeline schon eine etablierte Bedeutung hat.
- Neutral, weil eine nachgelagerte automatisierte
  WARNING-Eskalation immer noch entscheiden muss, ob sie das
  als blockierend behandeln will.

### BLOCKER

- Gut, weil der Operator den riskanten Fallback nicht
  versehentlich ausführen kann.
- Schlecht, weil jeder Routinen-`Replace` auf einer
  `Disabled`-Capability damit in `MANUAL_ACTION_REQUIRED`
  gezwungen wird — der Fallback-Pfad wird unerreichbar,
  Slice C.3 wäre damit konterkariert.
- Schlecht, weil es Plan §3 Step 5 widerspricht, der
  `DROP + CREATE` unter SAFE-Guard explizit erlaubt.

### INFO

- Gut, weil es bestehende Tooling-Pipelines nicht stört.
- Schlecht, weil Operatoren INFO-Rauschen routinemäßig
  überfliegen; das Implicit-Commit-Risiko würde häufig
  übersehen.

### Op-Level Destructive-Risk

- Gut, weil es sich in die bestehende Destructive-Guard-Pipeline
  integrieren würde.
- Schlecht, weil `ReplaceFunction` / `ReplaceProcedure` semantisch
  ein Body-Swap ist; die Renderer-Entscheidung,
  `DROP + CREATE` zu emittieren, ist Fallback, nicht
  Operator-Intent. Die Op als destruktiv zu markieren, würde
  außerdem jeden regulären `CREATE OR REPLACE`-Pfad triggern —
  was falsch ist.

## Weitere Informationen

- Implementierung:
  `MysqlDiffRoutineOps.warnDropCreateNonAtomic` emittiert die
  Diagnostic; `MysqlDiffRenderContext.warning(...)` wurde dafür
  eingezogen und von Slice D.4 mitgenutzt.
- Plan-Referenz:
  `docs/planning/done-archive/ImpPlan-0.9.7-E.1-routine-migration.md`
  §3 Step 5.
- Verwandte ADR: ADR-0002 dokumentiert die parallele Entscheidung
  zu `UNSAFE_DEPENDENCY_PAIR`.
