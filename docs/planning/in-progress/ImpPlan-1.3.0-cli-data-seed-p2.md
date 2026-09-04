# ImpPlan 1.3.0 — `data seed` P2: `--rules`-Regeldatei

> **Status:** Draft, bereit zur Umsetzung (2026-09-04). Setzt auf P1
> auf ([`ImpPlan-1.3.0-cli-data-seed-p1.md`](../done/ImpPlan-1.3.0-cli-data-seed-p1.md),
> geliefert). Aktiviert laut
> [`cli-data-seed.md`](cli-data-seed.md) Aktivierungs-Trigger durch
> expliziten Eigner-Wunsch vor dem v1.2.0-Release.
> **Vorbedingung:** Keine harte Blockade.

## Kontext / Ist-Stand (verifiziert)

- **`--rules` ist in `cli-spec.md` §"data seed" nur als Flag-Zeile
  genannt** (`spec/cli-spec.md:1466`, "Pfad, Regeldatei für
  Generierung"), kein Format spezifiziert — muss in diesem Slice neu
  entworfen werden, es gibt keine Spec-Autorität zum Abgleichen.
- **Kein Rules-Format-Vorbild im Repo.** Das einzige inhaltlich
  ähnliche Konzept ist `mcp-server.md`s Policy-YAML (unabhängiger
  Zweck: Zugriffsentscheidungen, nicht Wertegenerierung) und der
  `rulesSummary`-Freitext-Parameter im `testdata_planning`-MCP-Prompt
  (nur Kontext für einen KI-Prompt, keine strukturierte Regel).
- **Generierungs-Kern** (`hexagon/core/src/main/kotlin/dev/dmigrate/core/seed/`):
  `ColumnValueGenerator.generate(type: NeutralType): Any?` kennt nur
  den Neutraltyp, nicht Tabellen-/Spaltennamen.
  `TableRowSeeder.uniqueAwareValue()` (`TableRowSeeder.kt:183-196`)
  ist die Stelle, die pro Spalte tatsächlich generiert — sie kennt
  `columnName`, `column: ColumnDefinition` und `ctx.tableName`, hat
  also genug Kontext für ein Tabelle.Spalte-Rule-Matching.
  `columnValue()` (`TableRowSeeder.kt:121-139`) verzweigt VOR
  `uniqueAwareValue()` nach FK-Referenz (`column.references != null`)
  in `referencedValue()` — Regeln greifen dort **nicht** (AE-3).
- **Design-Delta zur ursprünglichen Aufwandsschätzung:** die
  vorherige grobe Schätzung ging von einer gemeinsamen
  `ColumnValueSource`-Abstraktion für P2 **und** P3 aus (P3 muss
  `hexagon:core` wegen `AiProviderPort` verlassen). P2 selbst braucht
  das nicht — eine Regeldatei ist reine Konfigurationsdaten, keine
  externe Abhängigkeit, bleibt also vollständig in `hexagon:core`
  lösbar. Dieser Plan baut **keine** vorgezogene Abstraktion für P3
  (CLAUDE.md: keine Abstraktion für hypothetische künftige
  Anforderungen) — wenn P3 kommt, wird der Schnitt dann entschieden.
- **Datei-Lade-Vorbild**: `PolicyRuleFileLoader.kt`
  (`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/PolicyRuleFileLoader.kt`)
  — Extension-basierter Jackson-Mapper (YAML/JSON), `error(...)`
  (nie `require(...)`) für alle Validierungsfehler, damit der
  Aufrufer einen einzigen Exception-Typ fängt. Für `data seed` liegt
  das Analogon in `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/`
  (P1s `DataSeedWiring.kt`/`DataSeedCommand.kt` liegen dort).
- **Exit-Code-Konvention** (`DataSeedRunner.kt` Klassen-KDoc): 7 =
  Konfigurationsfehler (unbekannte `--locale`, Schema nicht lesbar,
  `--target` nicht auflösbar) — eine ungültige `--rules`-Datei reiht
  sich dort ein, kein neuer Exit-Code nötig.

## Scope

`--rules <pfad>` auf `data seed`: eine YAML-Datei mit
Spalten-Overrides (feste Wertelisten mit optionalen Gewichtungen,
numerische Bereiche, einfache Text-Vorlagen), die den
Default-Generator aus P1 pro Tabelle.Spalte ersetzt. Additiv: ohne
`--rules` bleibt das Verhalten exakt wie in P1.

## Architektur-Entscheidungen

**AE-1 — Regelmodell bleibt in `hexagon:core`, keine neue
Abstraktionsebene.** `SeedRuleSet`/`ColumnRule` sind reine
Datenklassen + eine pure `resolve(tableName, columnName): ColumnRule?`-
Matching-Funktion, analog zu `SeedLocale`. `TableRowSeeder` bekommt
einen optionalen `rules: SeedRuleSet?`-Konstruktorparameter
(Default `null` = P1-Verhalten unverändert) statt eines
Plugin-Interfaces — es gibt in P2 nur eine Regelquelle (die Datei),
eine Abstraktion für mehrere wäre spekulativ.

**AE-2 — Erste passende Regel gewinnt, `table` optional (Wildcard).**
Gleiches Matching-Prinzip wie `PolicyRule` (`tenantId`/`toolName`
optional = Wildcard) und die Policy-Datei aus der `--policy-file`-
Slice: Regeln werden in Dateireihenfolge geprüft,
`table: null` matched jede Tabelle. Deckt sowohl "genau diese Spalte
in genau dieser Tabelle" als auch "jede Spalte namens `email`,
egal in welcher Tabelle" ab, ohne zwei getrennte Match-Modi zu
brauchen.

**AE-3 — Regeln greifen NICHT bei FK-referenzierenden Spalten.**
`columnValue()` verzweigt für `column.references != null` immer in
`referencedValue()` (Werte-Pool-Sampling gegen die Zieltabelle) —
eine Regel dort würde referenzielle Integrität ohne Vorwarnung
brechen. Eine `--rules`-Regel für eine FK-Spalte wird beim Laden
**nicht** abgelehnt (die Datei kennt das Schema nicht), aber beim
Anwenden schlicht ignoriert — **kein** stiller Fehler: ein
`W`-artiger Hinweis auf stdout, welche Regeln nie gegriffen haben
(AE-7).

**AE-4 — Drei Strategien, kein Regex-Generator.** `values` (feste
Liste + optionale `weights`, Default Gleichverteilung), `range`
(numerisch, `min`/`max`), `template` (geschlossenes Token-Vokabular:
`{word}` aus dem bestehenden `SeedLocale`-Wortschatz, `{digits:N}`
`N` Zufallsziffern, `{uuid}`). Bewusst **kein** "generiere String
passend zu Regex X" (Xeger-artige Regex-zu-String-Generierung) — das
wäre eine neue Runtime-Dependency (CVE-/Lizenz-Prüfung Pflicht,
Historie Dependency-CVE-Reduktion 90→0) für einen Bedarf, den niemand
konkret angefragt hat. `cli-spec.md` nennt nur "Muster", kein
Regex-Vertrag.

**AE-5 — Typ-Kompatibilität wird bei Anwendung geprüft, nicht beim
Laden.** Der Loader kennt das Schema nicht (Datei wird unabhängig vom
`--schema`-Argument geparst). `range` auf einer Nicht-numerischen
Spalte oder `values`-Einträge, die nicht zu `column.type` passen
(z. B. Text in einer `Integer`-Spalte), werfen beim tatsächlichen
Zeilenbau eine `SeedPreflightException` (Exit 3) — gleiche Kategorie
wie P1s bestehende Preflight-Fehler (FK-Zyklus, nicht generierbare
Typen), kein neuer Fehler-Exit.

**AE-6 — `unique`/Identifier-Spalten mit `values`-Strategie nutzen
denselben Wiederholungs-Mechanismus wie P1.** Kein neuer
Eindeutigkeits-Code: `uniqueAwareValue()`s bestehende
`usedValues`/`MAX_UNIQUE_ATTEMPTS`-Schleife wird unverändert
wiederverwendet, nur die innere `generate(...)`-Quelle wechselt (Regel
statt `ColumnValueGenerator`, wenn eine matcht). Eine `values`-Liste
kleiner als `--count` für eine `unique`-Spalte läuft also in die
bestehende `SeedUniquenessExhaustedException` (Exit 5) — erwartetes,
nicht neu zu bauendes Verhalten.

**AE-7 — Ungenutzte Regeln sind ein Hinweis, kein Fehler.** Eine
Regel, die nie auf eine tatsächlich vorhandene Tabelle.Spalte
gematcht hat (Tippfehler im Tabellennamen, FK-Spalte per AE-3, Spalte
existiert nicht im Schema), lässt `data seed` nicht scheitern — sie
wird nach dem Lauf auf stdout aufgelistet ("N Regel(n) nie
angewendet: ..."). Ein Abbruch wäre zu streng für ein additives
Werkzeug; stilles Schweigen wäre ein Footgun (Tippfehler bleibt
unbemerkt) — Mittelweg analog zu anderen "kein stiller Fehler"-
Konventionen im Projekt.

## Neue/geänderte Dateien

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/seed/SeedRules.kt` <!-- d-check:ignore (Zielbild: entsteht in P2; ADR 0011) -->
  (neu) — `ColumnRule` (sealed: `Values`/`Range`/`Template`),
  `SeedRuleSet` (Liste + `resolve(table, column): ColumnRule?` +
  `markUsed`/`unused()`-Tracking für AE-7).
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/seed/TableRowSeeder.kt`
  — neuer Konstruktorparameter `rules: SeedRuleSet? = null`;
  `uniqueAwareValue()` konsultiert `ctx.rules?.resolve(...)` vor dem
  Fallback auf `ctx.generator.generate(column.type)`.
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/seed/ColumnValueGenerator.kt`
  — `{word}`/`{digits:N}`/`{uuid}`-Template-Rendering als kleine,
  wiederverwendbare Funktion (nutzt bestehende `SeedLocale`/
  `randomLetters`-artige Bausteine, kein neuer Zufallsmechanismus).
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SeedRulesFileLoader.kt` <!-- d-check:ignore (Zielbild: entsteht in P2; ADR 0011) -->
  (neu) — YAML-Parser analog `PolicyRuleFileLoader.kt`, ausschließlich
  `error(...)` bei Validierungsfehlern.
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedCommand.kt`
  — neues `--rules <pfad>`-Flag.
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedWiring.kt`
  — lädt die Datei (falls gesetzt) vor dem Runner-Aufruf, mappt
  Ladefehler auf Exit 7 (analog `McpServeWiring.loadPolicyRulesOrExit`-
  Muster aus der `--policy-file`-Slice).
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedRequest.kt`,
  `DataSeedRunner.kt` — `rules: SeedRuleSet? = null`
  durchgereicht an `TableRowSeeder`; AE-7-Hinweis nach dem Lauf
  ausgegeben.
- `docs/user/anwenderhandbuch.md` §3.22 — `--rules`-Format +
  Sicherheitshinweis-freier Abschnitt (keine Secrets in Regeldateien
  zu erwarten, kein Hinweis nötig).
- `spec/cli-spec.md` — Format-Beispiel unter der `data seed`-Flag-Tabelle
  (normativ, wie `--policy-file`s YAML-Beispiel im Administrationshandbuch).
- Tests: `SeedRulesTest.kt` (Matching, Wildcard, erste-Regel-gewinnt),
  `SeedRulesFileLoaderTest.kt` (gültige/ungültige Dateien),
  `TableRowSeederTest.kt`-Ergänzungen (Regel überschreibt Default,
  FK-Spalte ignoriert Regel, `unique` + zu kleine `values`-Liste wirft
  weiterhin `SeedUniquenessExhaustedException`), `DataSeedRunnerTest.kt`-
  Ergänzung (AE-7-Hinweis-Ausgabe), `CliDataSeedSmokeTest.kt`-Ergänzung.

## Phasen

- **AP1 — Regelmodell + Matching (`hexagon:core`).** `SeedRules.kt`,
  Unit-Tests. Kein Dateizugriff, reine Datenstrukturen.
- **AP2 — `TableRowSeeder`-Integration.** Konstruktorparameter,
  AE-3/AE-6/AE-7-Verhalten, Regressionstests.
- **AP3 — Template-Rendering in `ColumnValueGenerator`.** Die drei
  Tokens, deterministisch über denselben `Random`-Zustand wie P1.
- **AP4 — Datei-Loader + CLI-Wiring.** `SeedRulesFileLoader.kt`,
  `--rules`-Flag, Exit-7-Mapping, Smoke-Test.
- **AP5 — Doku.** `anwenderhandbuch.md`, `cli-spec.md`.

## Akzeptanzkriterien

- [ ] `data seed --rules rules.yaml` überschreibt den Default-Generator
      für jede gematchte Tabelle.Spalte; ohne `--rules` ist das
      Verhalten byte-identisch zu P1 (Regressionstest mit `seedFor`-
      Fixture).
- [ ] `values` mit `weights` erzeugt eine nicht-gleichverteilte
      Stichprobe über viele Zeilen (statistischer Test mit fester
      Toleranz, deterministischer Seed).
- [ ] `range` erzeugt Werte innerhalb `[min, max]` für alle generierten
      Zeilen.
- [ ] `template` rendert `{word}`/`{digits:N}`/`{uuid}` korrekt und
      deterministisch (gleicher Seed ⇒ gleiche Werte).
- [ ] Eine Regel auf einer FK-referenzierenden Spalte greift nicht
      (Pool-Sampling bleibt maßgeblich) und erscheint im
      "nie angewendet"-Hinweis.
- [ ] `range` auf einer Nicht-numerischen Spalte → `SeedPreflightException`
      (Exit 3) mit einer Meldung, die Tabelle.Spalte und die
      inkompatible Strategie nennt.
- [ ] `values`-Liste kleiner als `--count` auf einer `unique`-Spalte →
      `SeedUniquenessExhaustedException` (Exit 5), wie in P1.
- [ ] Ungültige `--rules`-Datei (kaputtes YAML, unbekannte Strategie,
      fehlende strategie-spezifische Pflichtfelder,
      `values`/`weights`-Längen-Mismatch) → Exit 7 mit klarer Meldung,
      **vor** jeder Zeilengenerierung.
- [ ] `make docker-check` (targeted, dann einmal ohne `MODULES` wegen
      `hexagon:core`-Signaturänderung) grün.
- [ ] `make docs-check` grün.

## Nicht-Scope

- Regex-basierte Mustergenerierung (AE-4) — Folge-Ticket bei
  konkretem Bedarf.
- Verschachtelte/bedingte Regeln (z. B. "Wert von Spalte B hängt von
  Spalte A ab") — reine Spalten-für-sich-Overrides in P2.
- `--ai-backend` (P3) — separater, deutlich größerer Slice, nicht Teil
  dieses Plans.

## Verifikation

1. `make docker-test MODULES=":hexagon:core"` für AP1-AP3.
2. `make docker-check MODULES=":hexagon:core :hexagon:application :adapters:driving:cli"`.
3. Einmal `make docker-check` ohne `MODULES` (geteilte
   `TableRowSeeder`-Konstruktorsignatur).
4. `make docs-check` nach den Doku-Änderungen.
5. `make solid-suppression-gate` vor jedem Commit.
6. Manueller Smoke: `data seed --schema <fixture> --target sqlite:///tmp/x.db --rules <beispiel>`
   lokal, Stichprobe der erzeugten Zeilen gegen die Regel prüfen.

## Referenzen

- [`cli-data-seed.md`](cli-data-seed.md) — Umbrella-Plan, P2-Skizze.
- [`ImpPlan-1.3.0-cli-data-seed-p1.md`](../done/ImpPlan-1.3.0-cli-data-seed-p1.md)
  — P1, Closure-Sektion mit AE-1 bis AE-12.
- `spec/cli-spec.md` — `data seed`-Flag-Tabelle (`--rules`-Zeile ohne
  Format).
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/PolicyRuleFileLoader.kt`
  — Datei-Lade-Vorbild (Extension-Mapper, `error()`-Konvention).
