# Perf-Large-Schema HeapDumpOnOutOfMemoryError (Folge-Tweak zu Phase D)

- **Status**: Draft (Trigger registriert, kein Scope-Schnitt)
- **Trigger**: Post-Closure-Review des
  [`quality-coverage-expansion-plan`](../done/quality-coverage-expansion-plan.md)
  am 2026-05-31 hat festgestellt, dass Plan §5.4 in
  [`docs/planning/done/quality-coverage-expansion-plan.md:503`](../done/quality-coverage-expansion-plan.md)
  fuer den N=10000-Nightly-Run `-XX:+HeapDumpOnOutOfMemoryError`
  als Mitigation gegen OOM-CI-Verluste benennt, der Wert ist aber
  weder in `test/perf-large-schema/build.gradle.kts` noch in der
  root-level Test-`jvmArgs` gesetzt. Heute laufen N=100/1000 ohne
  Heap-Dump-Trigger — bei OOM verliert der Operator das forensische
  Artefakt.
- **Aktivierungsbedingung**: Wenn D-N10k aktiv wird (Nightly-Runner
  bereitsteht) **oder** wenn N=1000 in CI mit OOM faellt und ein
  Heap-Dump zur Diagnose gebraucht wird.

## Befund-Snapshot (2026-05-31)

- `test/perf-large-schema/build.gradle.kts:19` enthaelt keinen
  `jvmArgs`-Block und vererbt nur die root-level Test-Konfiguration
  in `build.gradle.kts:84-154`.
- Die root-level Test-`tasks.withType<Test>`-Konfiguration setzt
  weder `-XX:+HeapDumpOnOutOfMemoryError` noch
  `-XX:HeapDumpPath=...`. Plan §5.4 erwaehnt das nur als „Soll",
  ohne expliziten DoD-Punkt — daher in der Closure nicht
  als Bruch sichtbar.

## Skizzierte Arbeit

- `test/perf-large-schema/build.gradle.kts`: `tasks.named<Test>("test") { jvmArgs(...) }`
  ergaenzen um:
  - `-XX:+HeapDumpOnOutOfMemoryError`
  - `-XX:HeapDumpPath=build/test-heap-dumps/large-schema-${'$'}taskName.hprof`
  - optional `-XX:MaxMetaspaceSize=...` zur Eingrenzung
- Sicherstellen, dass `build/test-heap-dumps/` im `.gitignore`
  ist (vermutlich schon durch `build/` abgedeckt).
- Pruefen, ob das Pattern auch fuer `test/integration-concurrency`
  und `test/cross-dialect-matrix` Sinn ergibt (Concurrency-Tests
  arbeiten mit Testcontainers, OOM-Heap-Dumps koennten dort
  ebenfalls Diagnose-Wert haben).

## Nicht-Ziel

- Keine globale Aktivierung in der root-level Test-Konfiguration —
  Heap-Dumps von Unit-Specs sind 99% lokaler Code-Bug, kein
  produktiver Diagnose-Wert.
- Kein Hochladen der Heap-Dumps als CI-Artefakt; das Pattern ist
  vorerst nur lokal-forensisch.
