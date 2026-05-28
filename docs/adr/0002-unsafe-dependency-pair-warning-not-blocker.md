---
status: accepted
date: 2026-05-16
decision-makers: pt9912
consulted: code-review-Agents (E.1 Slices D.1 + D.4 Post-Commit-Reviews)
informed: E.1-Follow-up-Reviewer
---

# `UNSAFE_DEPENDENCY_PAIR` bleibt WARNING, kein BLOCKER

## Kontext und Problemstellung

E.1 Slice D.1 hat den `RoutineDependencyAnalyzer` eingeführt.
Sein `UNSAFE_DEPENDENCY_PAIR`-Befund markiert zwei im selben
Plan koexistierende Routinen, zwischen denen das Manifest in
keiner Richtung eine Dependency-Kante deklariert. Der
ursprüngliche Plan §3 wollte das als `MANUAL_ACTION_REQUIRED`
(BLOCKER), mit dokumentiertem Hochstufungs-Pfad:
"WARNING in D.1, BLOCKER sobald Engine-Verifikation in D.2/D.3
geliefert ist."

Am Ende von Slice D.4 waren alle drei Voraussetzungen erfüllt:
PG-`pg_depend`-Projektion, MySQL-Trigger-Reader-Wiring und ein
topologie-getriebener `DependencyGuardEvaluator`, der seine
Routing-Entscheidung am tatsächlichen Edge-Graph orientiert. Im
D.4-Follow-up-Review sind drei Punkte aufgekommen:

1. Der D.4-Topology-Evaluator entscheidet das Routing inzwischen
   selbst — ein Edge-freies Paar wertet topologie-mäßig als SAFE,
   und der `Disabled`-Capability-Pfad fällt auf `DROP + CREATE`
   zurück.
2. Eine Hochstufung von `UNSAFE_DEPENDENCY_PAIR` zu BLOCKER würde
   jeden File-only-Multi-Routine-Plan per Default sperren —
   Operatoren müssten jedes Routinen-Paar als
   `dependencies.functions` aufzählen, selbst wenn die Routinen
   offensichtlich nichts miteinander zu tun haben. Das Manifest
   kennt keinen "Independent-of"-Marker.
3. Ein SAFE-getriebener `DROP + CREATE` würde drei Diagnostics
   gleichzeitig auslösen: `UNSAFE_DEPENDENCY_PAIR` WARNING +
   `DEPENDENCY_GUARD_TOPOLOGY` INFO +
   `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC` WARNING. Punkt (1) auf
   BLOCKER zu heben, würde ein widersprüchliches Paar erzeugen
   (BLOCKER: "Unabhängigkeit nicht beweisbar" vs. INFO: "Topologie
   beweist Unabhängigkeit").

Welche finale Severity hat `UNSAFE_DEPENDENCY_PAIR`, nachdem
der D.4-Topology-Evaluator existiert?

## Entscheidungstreiber

- Der Topology-Evaluator produziert die tragende Routing-
  Entscheidung; ein paralleler Diagnostic darf das nicht
  überschreiben.
- File-only-Multi-Routine-Pläne sind in der Praxis häufig; ein
  BLOCKER-by-Default würde sie kaputtmachen.
- Manifest-Drift (Operator hat eine real existierende Kante
  vergessen zu deklarieren) ist weiterhin ein echtes Risiko;
  die WARNING bleibt ein nützliches Sicherheitsnetz.
- ADR-0001 setzt bereits Präzedenz, operativ relevante Befunde
  auf WARNING-Severity zu belassen, wenn die Alternative einen
  freigegebenen Pfad blockieren würde.

## Betrachtete Optionen

- **WARNING-Severity (Endzustand)** — operator-sichtbares
  Sicherheitsnetz; Routing-Entscheidung gehört dem
  Topology-Evaluator.
- **BLOCKER-Hochstufung** wie ursprünglich im D.1-Plan
  vorgesehen.
- **Diagnostic ganz fallenlassen** — der Topology-Evaluator
  übernimmt das Routing ohnehin.
- **Auf INFO neu klassifizieren** — leiseres Signal.

## Entscheidung

Gewählt: **WARNING-Severity, keine weitere Hochstufung
geplant.** Sie funktioniert als hinweisendes Sicherheitsnetz:
wenn zwei Routinen ohne Manifest-Kanten koexistieren, stupst die
WARNING den Operator an, die Beziehung explizit zu deklarieren,
falls der Topology-Evaluator eine versteckte Referenz übersehen
hat — ohne dessen Routing-Entscheidung zu überschreiben. Der im
D.1-Follow-up dokumentierte Hochstufungs-Pfad ist damit
geschlossen; Diagnostic-Message und Plan-Text wurden in
Commit `5d19903e` entsprechend angepasst.

### Konsequenzen

- Gut, weil File-only-Multi-Routine-Pläne ohne Konfiguration
  weiter laufen.
- Gut, weil die Diagnostic Schema-Manifest-Drift weiterhin
  einfängt — der Topology-Evaluator sieht nur deklarierte oder
  projizierte Kanten, ein vergessener Eintrag wäre sonst stumm.
- Schlecht, weil Operatoren die WARNING neben einer TOPOLOGY
  INFO sehen, die Unabhängigkeit beweist; die Botschaften sind
  in zwei verschiedenen Registern hinweisend und können
  rauschig wirken.
- Schlecht, weil Operatoren mit strikten
  WARNING-Eskalations-Pipelines sie als harten Stopp
  interpretieren könnten, was sie nicht ist.
- Neutral, weil der WARNING-Wortlaut in Commit `5d19903e`
  geschärft wurde, um die Topology-Evaluator-Welt
  widerzuspiegeln.

### Bestätigung

- `DiffPlanner.kt` emittiert `UNSAFE_DEPENDENCY_PAIR` mit
  WARNING-Severity und der D.4-bewussten Message; der
  CHANGELOG-Eintrag zu D.1 wurde von der Pre-Follow-up-
  "BLOCKER"-Behauptung auf die WARNING-Realität korrigiert
  (Commit `5d19903e`).
- `RoutineDependencyAnalyzerTest` und die Renderer-Tests pinnen
  den WARNING-Pfad; kein Test postuliert BLOCKER-Verhalten für
  diesen Code.

## Pros und Cons der Optionen

### WARNING (gewählt)

- Gut, weil File-only-Multi-Routine-Pläne ohne manuelle
  Edge-Deklaration durchlaufen.
- Gut, weil die Routing-Entscheidung des Topology-Evaluators
  tragend bleibt.
- Neutral, weil der Operator jetzt eine WARNING sieht, die die
  TOPOLOGY INFO gleichzeitig als unkritisch ausweist — der
  Operator muss beide lesen.

### BLOCKER-Hochstufung

- Gut, weil kein Operator versehentlich einen Plan mit nicht
  beweisbarer Unabhängigkeit ausführen kann.
- Schlecht, weil File-only-Multi-Routine-Migrationen damit per
  Default gesperrt wären — kein "Independent-of"-Marker im
  Manifest.
- Schlecht, weil es dem D.4-Topology-Routing auf dem SAFE-Pfad
  widerspricht.

### Ganz fallenlassen

- Gut, weil die Diagnostic-Ausgabe sauberer bleibt.
- Schlecht, weil Manifest-Drift ihr einziges Sicherheitsnetz
  verliert — der Topology-Evaluator sieht nur, was deklariert
  oder projiziert wurde.

### INFO

- Gut, weil null Friction.
- Schlecht, weil Manifest-Drift operativ relevant ist;
  INFO-Rauschen wird routinemäßig überflogen und das
  Sicherheitsnetz wäre faktisch unsichtbar.

## Weitere Informationen

- Implementierung:
  `RoutineDependencyAnalyzer.detectUnsafeRoutinePairs` erzeugt
  die Kandidaten; `DiffPlanner.kt` emittiert die Diagnostic.
- Plan-Referenz:
  `docs/planning/done/ImpPlan-0.9.7-E.1-routine-migration.md`
  §D.1-Follow-up + §D.4-Follow-up.
- Verwandte ADR: ADR-0001 dokumentiert die parallele
  Entscheidung zu `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC`.
- Mögliche Supersession: ein späterer Slice könnte einen
  expliziten "Independent-of"-Marker auf das Routine-Manifest
  einziehen (z. B. `dependencies.independentOf: ["other_routine"]`);
  diese ADR wäre dann zu überarbeiten.
