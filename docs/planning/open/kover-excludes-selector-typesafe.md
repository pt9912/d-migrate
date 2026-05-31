# Kover-Excludes-Selector-Typesafe-Scanner (Folge-Slice zu Phase E)

- **Status**: Draft (Trigger registriert, kein Scope-Schnitt)
- **Trigger**: Post-Closure-Review des
  [`quality-coverage-expansion-plan`](../done/quality-coverage-expansion-plan.md)
  am 2026-05-31 hat festgestellt, dass `scripts/verify-kover-excludes-ledger.py`
  Gradle-Dateien per Regex `\b(classes|packages)\s*\(` scannt — ein
  kuenftiger Kover-Selector-Typ wie `annotatedBy(...)`,
  `inheritedFrom(...)` o. ae. wuerde vom Scanner stillschweigend
  ignoriert, obwohl der Plan-DoD §7 Z. 721 fordert „bisher unbekannte
  Selector-Typen blocken den Hook, statt still ignoriert zu werden".
  Der fail-closed-Pfad existiert bereits **innerhalb** des Ledgers
  (verifier rejectet unbekannte Selector-Werte in Ledger-Zeilen),
  aber **nicht** im Gradle-Side-Scanner.
- **Aktivierungsbedingung**: Wenn Kover einen neuen Selector-Typ
  einfuehrt **oder** wenn jemand in einem `build.gradle.kts` einen
  neuen Selector verwendet, der bisher in keinem `kover { excludes }`-
  Block vorkommt. Der Trigger ist passiv-niedrigschwellig — der
  Drift bleibt unentdeckt, bis ein Reviewer ihn manuell auffaengt.

## Befund-Snapshot (2026-05-31)

- `scripts/verify-kover-excludes-ledger.py:208` matched
  `\b(classes|packages)\s*\(`. Andere Selector-Bezeichner in
  `kover { reports { filters { excludes { ... } } } }`-Bloecken
  werden vom Regex nicht erfasst und tauchen daher weder als
  „missing entry" noch als „unknown selector" auf.
- Die Ledger-seitige Validierung in `disposition_error` kennt nur
  `classes`/`packages`/`module` als Selektor-Tokens. Eine
  Gradle-Datei mit `annotatedBy("Generated")` wuerde im Ledger
  fail-closed quittiert werden, **falls** sie dort eingetragen
  ist — aber der Scanner sieht sie ohnehin nicht und schlaegt
  daher nicht als „missing" auf.

## Skizzierte Arbeit

- Scanner-Pfad anpassen: statt der Regex `(classes|packages)` einen
  generischen Bezeichner-Match `\b([A-Za-z_][A-Za-z0-9_]*)\s*\(`
  unter einem `excludes { ... }`-Block ziehen und gegen eine
  allowlist `{"classes", "packages"}` pruefen. Treffer ausserhalb
  der allowlist sind Fail-Closed-Cases mit klarem Fehlertext
  („unknown Kover exclude selector `annotatedBy` in module X —
  extend allowlist + ledger format together").
- Test pro Selector-Form-Mutation (Negativ-Probe): kuenstliches
  `annotatedBy("Foo")` in eine Build-Datei einfuegen, Skript-
  Lauf erwartet Exit 1 mit der Fail-Closed-Begruendung.
- Plan-Doc-Closure (in `done/quality-coverage-expansion-plan.md`)
  spiegelt rueckwirkend die Korrektur und referenziert diesen
  Folge-Plan.

## Nicht-Ziel

- Keine Erweiterung der Ledger-Disposition-Vokabular ueber das
  heute gepflegte Set hinaus. Das ist nicht das Problem hier —
  das Problem ist, dass der Scanner ueberhaupt erst Auge auf
  unbekannte Selectoren bekommt.
- Keine Migration auf einen echten Gradle-/Kover-AST-Parser; die
  Regex-Loesung reicht, solange wir nur fail-closed-Detektion
  brauchen.
