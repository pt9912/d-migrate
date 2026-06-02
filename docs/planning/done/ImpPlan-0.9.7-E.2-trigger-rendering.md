# Implementierungsplan: 0.9.7 — E.2 Trigger-Migration / Rendering

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.2 Trigger-Migration (PostgreSQL/MySQL/SQLite Trigger-Rendering)
> **Status**: done ✅ 2026-05-18. Vollscheibe gelandet:
>            Sub-Slice A.1 (Foundation: `TriggerNameCollisionDetector`,
>            `OperationRisk.hasGap`, neue MigrationBlockedReason-Codes,
>            YAML-Strict-Pin),
>            Sub-Slice A.2 (PostgreSQL-Renderer mit Body-as-Function-
>            Reference-Validator),
>            Sub-Slice A.3 (hasGap-Mapper-Wiring + `--strict-gap-operations`
>            CLI; Detail: `docs/planning/done/ImpPlan-0.9.7-E.2-A.3-hasgap-strict.md`),
>            Sub-Slice B (MySQL-Renderer mit Bare-Name-DROP +
>            inline-body),
>            Sub-Slice C (SQLite-Renderer mit
>            `SqliteRebuildPlanner.classify`-Absorption),
>            Review-Follow-up (12 Findings adressiert),
>            Sub-Slice D (Roadmap- und Spec-Update + Plan-Doc-Closing).
> **Vorbedingung**: Workstream G ✅, E.1 Routine-Migration ✅ (Body-Vertrag,
>                  Secret-Scrubbing, `RoutineBodyNormalizer`,
>                  `ROUTINE_DOWN_BODY_UNKNOWN`, Capability-Vertrag),
>                  D.3b Materialized-Views ✅ (Drop+Create-Fallback-Muster
>                  und Dependency-Graph-Anwendung).
> **Referenz**: `docs/planning/in-progress/diffresult-migration-plan-2.md`
>             §9 E.2 (Trigger-Migration),
>             `docs/planning/done/ImpPlan-0.9.7-E.1-routine-migration.md`
>             als Vorlage fuer Body-Vertrag, Capability-Pattern und
>             Renderer-Aufteilung,
>             `spec/cli-spec.md` §6.1 `schema migrate`

> **SQL-Syntaxbeispiele in diesem Dokument sind verbindliche
> Render-Templates** (nicht Pseudo-Code). Renderer und Goldenness-Tests
> halten sich an genau diese Form; Abweichungen muessen im Slice-PR
> explizit begruendet und in §4 mit-aktualisiert werden.

---

## 1. Ziel

Plan-2 §9 E.2 fordert eine vollstaendige Trigger-Migration fuer
PostgreSQL, MySQL und SQLite mit:

> "Trigger werden als eigene Objektklasse modelliert. … Replace ist nur
> ein logischer Operationstyp. Gerendert wird je Dialekt als sicheres
> Drop/Create oder natives Replace, wenn vorhanden und getestet. Down
> fuer Replace erfordert den vollstaendigen alten Triggerzustand; sonst
> wird kein vollstaendiges Rollback-Artefakt erzeugt. Drop/Create-Trigger
> werden in der Dependency-Sortierung um Tabellen, Spalten, Routinen und
> Views herum geplant."

Heutiger Stand:

- **Modell**: `TriggerDefinition` existiert mit
  `table`/`event`/`timing`/`forEach`/`condition`/`body`/`dependencies`/
  `sourceDialect`. `SchemaDefinition.triggers: Map<String, TriggerDefinition>`
  ist gesetzt. `SchemaComparator` produziert `triggersAdded/Removed/Changed`.
- **Mapper**: `OperationMapperRoutines.mapTriggers(...)` emittiert
  `DiffOperation.CreateTrigger`/`ReplaceTrigger`/`DropTrigger`.
- **Reader**: PostgreSQL liest Trigger ueber `pg_trigger`
  (`PostgresSchemaProgrammabilityReaders`). MySQL liest Trigger ueber
  `INFORMATION_SCHEMA.TRIGGERS` (`MysqlRoutineReader`).
  SQLite-Trigger-Reverse-Read fehlt (siehe §3 Carve-out).
- **Dependency**: `RoutineDependencyAnalyzer` kennt Trigger-Edges.
- **SqliteRebuildPlanner** kennt Trigger als Rebuild-relevante Objekte.
- **Renderer**: PostgreSQL/MySQL/SQLite-Diff-Generatoren kategorisieren
  `CreateTrigger`/`ReplaceTrigger`/`DropTrigger` als
  `OpCategory.UNSUPPORTED` und blocken sie als
  `DIALECT_UNSUPPORTED_OPERATION` (`PostgresDiffDdlGenerator.kt:145-147`,
  `MysqlDiffDdlGenerator.kt:129-131`,
  `SqliteDiffDdlGenerator.kt:257-259`).

Dieser Workstream baut **nur den fehlenden Render-Pfad** und uebernimmt
den E.1-Body-Vertrag fuer Trigger-Bodies. Eine breite
TriggerDefinition-Modellerweiterung (`events`-Liste, `enabledState`,
strukturierte Spaltenliste) ist nicht Teil von E.2 (siehe §3 Carve-out).

## 2. Scope-Carve-out fuer 0.9.7

**In Scope** (dieser Workstream, in mehreren Slices):

- Trigger-Body-Vertrag analog E.1: Body wird ueber denselben
  `RoutineBodyNormalizer` normalisiert und ueber SHA-256 gehasht;
  `RoutineBodyScrubber`/`RoutineBodyLogRedactor` greifen auch fuer
  Trigger-Bodies. `body: String?` aus `TriggerDefinition` ist die
  einzige Body-Quelle; ein fehlender Body blockt Replace-Down mit
  `ROUTINE_DOWN_BODY_UNKNOWN` (selber Code, derselbe Vertrag wie E.1).
- PostgreSQL-Renderer fuer `CREATE TRIGGER`/`DROP TRIGGER`/
  `CREATE OR REPLACE TRIGGER` (PG 14+) bzw. Drop+Create-Fallback fuer
  PG < 14 oder bei Capability `enabled=false`.
- MySQL-Renderer fuer `CREATE TRIGGER`/`DROP TRIGGER`. Replace ist immer
  Drop+Create (MySQL kennt kein `CREATE OR REPLACE TRIGGER`).
  Body wird ohne MySQL-Delimiter als einzelnes strukturiertes Statement
  gespeichert (analog E.1.C.2 Routinen).
- SQLite-Renderer fuer `CREATE TRIGGER`/`DROP TRIGGER`. Replace ist
  immer Drop+Create (SQLite kennt kein `CREATE OR REPLACE TRIGGER`).
  Body wird als plain SQL gerendert.
- Capability-Vertrag: `create_or_replace_trigger` als dialektfaehige
  Engine-Kennzahl mit `{ enabled: bool, minServerVersion?: string }`
  analog `create_or_replace_routine`. PG: `enabled=true,
  minServerVersion="14"` als Default; MySQL/SQLite immer `enabled=false`.
- Trigger-Identitaet im Renderer: bestehende `DiffObjectRef(TRIGGER,
  listOf(name))`-Arity-1-Konvention bleibt. Tabellenkontext fuer das
  SQL-Template wird aus `TriggerDefinition.table` gelesen — nicht aus
  `objectRef.path`. Eine kanonische `ObjectKeyCodec.triggerKey(table,
  name)`-Identitaet ist Sache von F.4 RenameTrigger und nicht E.2.
- **Trigger-Name-Kollisions-Detektor (TriggerNameCollisionDetector)**
  als Pre-Plan-Gate (Sub-Slice A.1). Strukturelle Lage: das heutige
  Modell `SchemaDefinition.triggers: Map<String, TriggerDefinition>`
  nutzt den Triggernamen als Map-Key und kann zwei Trigger mit
  gleichem Namen auf verschiedenen Tabellen nicht direkt halten;
  Mehrdeutigkeit ginge beim `toMap()`-Schritt still verloren. E.2
  schliesst diese Luecke an den beiden realen Eingangspfaden ab,
  **ohne** eine Map→List-Modellmigration vorzuziehen:
    - **Reader-Pfad** (Live-DB-Ist-Zustand): jeder Reader
      (`PostgresSchemaProgrammabilityReaders`, `MysqlRoutineReader`)
      liefert eine `List<NamedTrigger>`-Form an den Detektor **vor**
      dem `.toMap()`-Schritt. Findet der Detektor zwei Eintraege mit
      gleichem `name` und verschiedener `definition.table`, blockt
      er hart mit `TRIGGER_NAME_COLLISION` und nennt die
      `(name, tableA, tableB)`-Tripel.
    - **File-Pfad** (Datei-Soll-Zustand): der `YamlSchemaCodec` nutzt
      Jacksons `DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY`
      (bereits in `YamlSchemaCodec.kt:17` aktiv), wodurch doppelte
      Map-Keys im `triggers:`-Knoten einen
      `JsonMappingException`/`DuplicateKeyException` ausloesen, statt
      still zu ueberschreiben. Der Slice pinnt diese Konfiguration
      explizit in einem Test mit doppeltem Trigger-Key.
  Strukturelle `Map<TriggerKey, TriggerDefinition>`-Migration mit
  `ObjectKeyCodec.triggerKey(table, name)` bleibt F.4-Vorbedingung;
  E.2 macht sie nicht. Damit ist E.2 nicht von der Map-Key-Schwaeche
  abhaengig; falsch aufgeloeste Mappings koennen nicht zu Operationen
  an der falschen Triggerinstanz fuehren.
- **Replace-Fallback ist eine bewusste, nicht stille Entscheidung**
  (siehe §4.5): jeder `ReplaceTrigger`, der ueber Drop+Create gerendert
  wird, traegt eine explizite `REPLACE_TRIGGER_VIA_DROP_CREATE`-
  Capability-Decision, eine `OperationRisks(up = OperationRisk(
  hasGap = true), ...)`-Markierung und emittiert eine Display-/Report-
  Warnung (`W_TRIGGER_REPLACE_GAP`). Im `--strict`-Pfad blockt der
  Mapper-/Planner mit `MANUAL_ACTION_REQUIRED` statt einen
  Drop+Create-Fallback zu rendern.
- Dependency-Sortierung: Trigger-Operationen muessen nach
  Tabellen-/Spalten-/Routine-/View-Operationen ausgefuehrt werden;
  `RoutineDependencyAnalyzer`-Edges bleiben unveraendert, der Slice
  prueft nur, dass die Edges fuer das neue Rendering ausreichen
  (`DEPENDENCY_GUARD_TOPOLOGY` greift bereits).
- Tests pro Dialekt: Positiv- (Create/Drop/Replace), Negativ- (Down
  ohne Vorbody, Body-Drift), Smoke- und Goldenness-Tests.
- Roadmap- und Plan-2 §9.E.2-Status-Update.

**Aus Scope** (Carve-outs, dokumentiert in §3):

- TriggerDefinition-Modellerweiterung um `events: List<TriggerEvent>`
  mit optionaler Spaltenliste, `enabledState` und
  strukturierte `INSERT/UPDATE OF col`-Modellierung.
- SQLite-Trigger-Reverse-Read und Live-Diff aus `sqlite_master`.
- Kanonische `table::name`-Identitaet im Schema-Map und in
  `DiffObjectRef` (F.4-Vorbedingung, nicht E.2).
- Trigger-Migration innerhalb der SQLite-Rebuild-Pipeline (SqliteRebuild
  drop/recreate-Pfad fuer betroffene Tabellen) — der Plan dokumentiert
  diese Interaktion explizit, aber die Rebuild-Trigger-Reinjektion ist
  Phase-H-Scope.

## 3. Sub-Slice-Schnitt

Pro Sub-Slice ein eigener Commit; Plan-Header trackt den Slice-Status.

### Sub-Slice A — Foundation + PostgreSQL Trigger-Rendering

**A.1 Foundation (dialektneutral, vor PG-Renderer):**

- Neuer `TriggerNameCollisionDetector` in `hexagon:core` mit einer
  list-basierten Pre-Map-API:
    ```kotlin
    fun detect(triggers: List<NamedTrigger>): TriggerNameCollisionOutcome
    ```
  Findet Eintraege mit gleichem `name` und verschiedener
  `definition.table` und meldet die Konflikte. Bei Kollision blockt
  der Aufrufer mit dem neuen
  `MigrationBlockedReason.TRIGGER_NAME_COLLISION` und nennt die
  `(name, tableA, tableB)`-Tripel. Der Detektor wird **vor** der
  `.toMap()`-Materialisierung in `SchemaDefinition.triggers`
  konsultiert — der Map-Schritt darf erst danach passieren.
- Reader-Verkabelung in Slice A: PG-/MySQL-Reader liefern eine
  `List<NamedTrigger>`-Form an den Aufrufer (CLI-Layer oder
  `JdbcSchemaReader`-Adapter); der Adapter ruft den Detektor und gibt
  dann erst die `Map<String, TriggerDefinition>` an
  `SchemaDefinition` weiter. (MySQL-Reader-Wiring bleibt fuer
  Slice B; Slice A liefert die generische Adapter-Infrastruktur und
  schaltet PG ein.)
- File-Pfad: `YamlSchemaCodec` aktiviert bereits Jacksons
  `DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY`
  (`YamlSchemaCodec.kt:17`). Slice A pinnt das mit einem Test, der
  ein YAML-Dokument mit doppeltem Trigger-Map-Key faehrt und einen
  `JsonMappingException`/`DuplicateKeyException` aus dem Codec
  erwartet, statt einer stillen Ueberschreibung.
- `Map<TriggerKey, TriggerDefinition>`-Migration mit kanonischer
  `table::name`-Identitaet bleibt F.4-Vorbedingung; E.2 macht keine
  Modellaenderung an `SchemaDefinition.triggers`.
- Neue `MigrationBlockedReason`-Codes: `TRIGGER_NAME_COLLISION`,
  `TRIGGER_BODY_NOT_FUNCTION_REFERENCE` (siehe A.2),
  `MANUAL_ACTION_REQUIRED` (sofern nicht bereits aus E.1 vorhanden).
- Neuer Warn-Code `W_TRIGGER_REPLACE_GAP` fuer Drop+Create-Fallbacks
  bei `ReplaceTrigger` (Display-Plane-Warnung; Severity Warning).
- `OperationRisk` erhaelt — falls noch nicht aus einem frueheren Slice
  vorhanden — ein optionales `hasGap: Boolean = false`-Feld fuer
  Operationen, die zwischen zwei Statements eine kurze
  Wirkungsluecke erzeugen. `ReplaceTrigger` ueber Drop+Create setzt
  `up.hasGap = true`. Wenn `OperationRisk.hasGap` bereits existiert,
  wird das Feld wiederverwendet; sonst wird die Felderweiterung in
  A.1 mitgeliefert und durch die bestehenden E.1-Tests
  (`OperationRisk`-Konstruktor-Defaults) abgesichert.
- `--strict`-Vertrag: ein bestehender oder in A.1 eingefuehrter
  `MigrationStrictMode`-Flag schaltet das Mapper-Verhalten so, dass
  jeder `OperationRisk.hasGap = true`-Pfad nicht gerendert wird,
  sondern als `MANUAL_ACTION_REQUIRED` mit Operation-ID-Liste blockt.
  Wenn `--strict` schon aus E.1 existiert, wird er erweitert; sonst
  fuehrt A.1 ihn ein und dokumentiert die Flag-Semantik in
  `spec/cli-spec.md` §6.1.

**A.2 PostgreSQL-Rendering:**

- Neuer `PostgresTriggerDdlHelper.kt` analog
  `PostgresRoutineDdlHelper.kt`.
- **Body-Vertrag fuer PostgreSQL**: `TriggerDefinition.body` ist in
  PG **strikt eine Funktionsreferenz** in der Form `fn_name([args])`
  (z.B. `audit_orders()` oder `log_change('orders', 'INSERT')`).
  Der Helper validiert das vor dem Render ueber eine konservative
  Pruefung:
    - Erlaubt: `[schema.]identifier([arg_literal, ...])` — also ein
      qualifizierbarer Identifier gefolgt von einer einfachen
      Argumentliste mit Literal-/Identifier-Tokens.
    - Verboten: Mehrzeilige Bodies, `BEGIN`/`END`-Bloecke,
      Statement-Separatoren (`;` ausserhalb optionaler abschliessender
      Whitespace), Subquery-Klammerung.
  Bei Verstoss blockt der Helper mit
  `TRIGGER_BODY_NOT_FUNCTION_REFERENCE`. Inline-PL/pgSQL-Bodies sind
  damit explizit ausserhalb von E.2-Scope (sie waeren ein separater
  E.1-Routinen-Slice; ein Trigger-Body-Inline-Path ist in PG ohnehin
  nicht supportet).
- Render-Templates (verbindlich, nicht Pseudo-Code):

  ```
  CREATE TRIGGER <name>
      <timing> <event>
      ON <table>
      FOR EACH <forEach>
      [WHEN (<condition>)]
      EXECUTE FUNCTION <bodyFunctionRef>;
  ```

  ```
  DROP TRIGGER <name> ON <table>;
  ```

  ```
  CREATE OR REPLACE TRIGGER <name>
      <timing> <event>
      ON <table>
      FOR EACH <forEach>
      [WHEN (<condition>)]
      EXECUTE FUNCTION <bodyFunctionRef>;   -- PG 14+ Pfad
  ```

  `<bodyFunctionRef>` ist der validierte Funktionsreferenz-String aus
  `TriggerDefinition.body`. `EXECUTE PROCEDURE` wird **nicht**
  gerendert (PG-Syntax-Alias, seit PG 11 deprecated; der Helper rendert
  ausschliesslich `EXECUTE FUNCTION`).
- `CreateTrigger`/`DropTrigger`: gemaess Templates oben.
- `ReplaceTrigger`-Pfad:
  - Capability `create_or_replace_trigger.enabled = true` und
    Server-Version >= 14 → natives `CREATE OR REPLACE TRIGGER ...`
    (kein Drop+Create, kein Gap, `up.hasGap = false`).
  - Andernfalls Drop+Create-Fallback mit explizitem
    `REPLACE_TRIGGER_VIA_DROP_CREATE`-Decision-Vermerk im Operation-
    Metadata, `up.hasGap = true`, `W_TRIGGER_REPLACE_GAP` als
    Display-/Report-Warnung, und im `--strict`-Pfad blockierender
    `MANUAL_ACTION_REQUIRED`.
  - `before`-Body muss bekannt sein; sonst `ROUTINE_DOWN_BODY_UNKNOWN`.
- `PostgresDiffDdlGenerator`: Kategorisierung von `CreateTrigger`/
  `ReplaceTrigger`/`DropTrigger` von `UNSUPPORTED` nach neuer
  `OpCategory.TRIGGER` umstellen; `renderTrigger*` an
  `PostgresTriggerDdlHelper` delegieren.
- Body-Drift-Erkennung in Replace: `bodyHash(before) != bodyHash(after)`
  ist ein normaler Replace-Trigger-Fall (gewuenschte Migration); der
  Body-Vergleich beschraenkt sich auf den validierten
  Funktionsreferenz-String und wird ueber den gemeinsamen
  `RoutineBodyNormalizer` gefuehrt.
- Down-Pfad: `DropTrigger` → `CreateTrigger(before)`;
  `CreateTrigger` → `DropTrigger`; `ReplaceTrigger` →
  `ReplaceTrigger(before↔after)` bzw. Drop+Create-Fallback mit
  altem Body (gleiche Gap-/Strict-Regeln wie Up).
- Capability-Verdrahtung: PG `create_or_replace_trigger = { enabled =
  true, minServerVersion = "14" }`. Hardcoded-Default in
  `PostgresEngineCapabilities`/`RoutineCapabilities` (E.1-Muster
  wiederverwenden).
- Tests:
  - Positiv: `CREATE TRIGGER` exakt nach Template.
  - `DROP TRIGGER ... ON <table>`.
  - `ReplaceTrigger` mit `OR REPLACE` (PG 14+) und ohne (PG 13)
    Drop+Create-Fallback inkl. Gap-Risk-Markierung und
    `W_TRIGGER_REPLACE_GAP`-Warnung.
  - `--strict` blockt Drop+Create-Fallback als
    `MANUAL_ACTION_REQUIRED`.
  - Down-Pfad fuer alle drei.
  - Body-Drift (Hash-Vergleich).
  - Fehlender Vorbody → `ROUTINE_DOWN_BODY_UNKNOWN`.
  - Body, der keine Funktionsreferenz ist (z.B.
    `"BEGIN INSERT ... END"`) → `TRIGGER_BODY_NOT_FUNCTION_REFERENCE`.
  - `TriggerNameCollisionDetector` blockt bei Namenskollision auf
    verschiedenen Tabellen (Schema-Map fixture mit zwei Triggern
    `audit_log` auf `orders` und `customers`, ueber separate Doku-
    Streams konstruiert).

**Abgrenzung A:** keine MySQL/SQLite-Aenderungen. Foundation A.1 ist
dialektneutral und wird in Slice B/C wiederverwendet.

**Carve-outs nach A.2-Implementierung (2026-05-18):** zwei Punkte aus
der A.1/A.2-Spec sind als Sub-Slice A.3 herausgezogen, weil sie ein
End-to-End-Wiring vom Mapper bis zum CLI-Layer brauchen und sonst den
PG-Renderer-Slice ueberlasten wuerden:

1. **`OperationRisk.hasGap`-Setzen auf der Operation.** A.1 hat das
   Feld eingefuehrt, A.2 nutzt es noch nicht — der Renderer surface
   die Luecke nur als `W_TRIGGER_REPLACE_GAP`-WARNING-Diagnostic. Der
   Mapper muss das Feld konsultieren der Capability und setzen, damit
   downstream-Consumer den Risk maschinenlesbar auf der Operation
   sehen.
2. **`--strict`-CLI-Mode.** A.1 hat den Vertrag dokumentiert
   (`hasGap → MANUAL_ACTION_REQUIRED`), aber kein Flag und keinen
   Lift-Pfad. Der Slice braucht ein CLI-Flag, einen
   `DdlGenerationOptions.strictMode`-Carrier und einen Lift-Pfad im
   Renderer / im Result-Builder.

Beide hangen zusammen (Source + Consumer fuer dasselbe Signal) und
landen in einem gemeinsamen Sub-Slice A.3. Detail-Plan:
`docs/planning/done/ImpPlan-0.9.7-E.2-A.3-hasgap-strict.md`.

### Sub-Slice A.3 — `hasGap`-Wiring + Strict-Mode

**Status:** ✅ 2026-05-18. Detail-Plan in
`docs/planning/done/ImpPlan-0.9.7-E.2-A.3-hasgap-strict.md`.

**Lieferumfang in Kuerze:**

- `OperationMapperRoutines.mapTriggers(...)` (oder ein dialekt-
  bewusster Post-Map-Schritt) konsumiert die `TriggerCapability` aus
  einem Planning-Context und setzt
  `ReplaceTrigger.risks.up.hasGap = true` (und analog `down.hasGap`),
  wenn die Capability `Disabled` resolved.
- Neuer `DdlGenerationOptions.strictMode: Boolean = false` plus CLI-
  Flag `--strict-gap-operations` (oder analog) an
  `schema migrate` / `schema rollback`.
- Renderer/Result-Builder konsultiert `strictMode`; wenn
  `op.risks.<direction>.hasGap` und `strictMode` zusammenfallen,
  blockt der Pfad mit `MANUAL_ACTION_REQUIRED` statt zu rendern.
  Der `W_TRIGGER_REPLACE_GAP`-Warning bleibt im Default-Pfad als
  Display-Diagnostic.
- Tests pinnen das End-to-End-Verhalten: Default-Pfad emittiert
  Drop+Create + Warning; `--strict`-Pfad blockt mit
  `MANUAL_ACTION_REQUIRED`; das `hasGap`-Feld ist im Plan-Artefakt
  serialisiert.

**Vorbedingung A.3 → B:** Sub-Slice B (MySQL) konsumiert dieselbe
Strict-Mode-Infrastruktur, weil MySQL `ReplaceTrigger` immer Drop+
Create ist. Reihenfolge:
1. A.3 (Wiring + CLI) landet vor B.
2. B baut auf A.3 auf — MySQL-Renderer setzt `hasGap = true`
   bedingungslos und nutzt denselben Strict-Lift.

**Abgrenzung A.3:** keine renderer-spezifischen SQL-Templates;
keine MySQL/SQLite-Renderer-Aenderungen. Reiner Risk-/CLI-/Result-
Layer.

### Sub-Slice B — MySQL Trigger-Rendering

**Vorbedingung:** Sub-Slice A.3 (`hasGap`-Wiring + Strict-Mode) ✅, weil
MySQL `ReplaceTrigger` immer Drop+Create rendert und dasselbe
Gap-/Strict-Vertragsmuster braucht.

**Lieferumfang:**

- Neuer `MysqlTriggerDdlHelper.kt`.
- **Body-Vertrag fuer MySQL**: `TriggerDefinition.body` ist hier ein
  inline SQL-Block, der entweder ein einzelnes Statement oder einen
  `BEGIN ... END`-Block enthalten kann. Der Helper rendert ihn ohne
  MySQL-Delimiter-Wechsel: `JdbcMigrationExecutor` schickt den Body
  via einzelnem `Statement.execute(...)`, ohne `DELIMITER //`-Schalter
  im Artefakt. Das ist analog E.1.C.2 (Routinen) und wird im Slice
  durch Goldenness-Tests gepinnt.
- Render-Templates (verbindlich):

  ```
  CREATE TRIGGER <name>
      <timing> <event>
      ON <table>
      FOR EACH ROW
      <body>;
  ```

  ```
  DROP TRIGGER <name>;                 -- ungeschemed; Triggername ist
                                        -- innerhalb der DB eindeutig
  ```

  Optionaler Schema-Qualifizierer, wenn `TriggerDefinition` einen
  Schemakontext kennt (heute nicht modelliert; Renderer bleibt beim
  bare-name-Pfad):
  ```
  DROP TRIGGER <schema>.<name>;
  ```

  **Wichtig**: MySQL `DROP TRIGGER` akzeptiert keinen Tabellen-
  Qualifizierer (`table.name`-Form ist Syntaxfehler). Konsistent zum
  bestehenden `MysqlDdlGenerator.kt` Initial-DDL-Pfad rendert E.2 den
  bare-name-Pfad; die `IF EXISTS`-Frage (heute im Initial-Generator
  als `DROP TRIGGER IF EXISTS <name>` gerendert) wird im Slice
  explizit entschieden — Default fuer Migrate-Diff-Pfad ist **ohne**
  `IF EXISTS`, weil Drift sichtbar bleiben soll; mit `IF EXISTS` nur,
  wenn ein expliziter `--idempotent-drop`-Modus gesetzt ist (analog
  E.1-Routinen).
  `WHEN`-Klauseln werden auf MySQL **nicht** gerendert; MySQL kennt
  keine WHEN-Triggers. Eine `condition != null`-Eingabe blockt mit
  `DIALECT_UNSUPPORTED_OPERATION` (bestehender Code) und nennt
  `condition` als Verursacher.
  `forEach = STATEMENT` blockt analog — MySQL kennt nur ROW-Trigger.
- `CreateTrigger`/`DropTrigger`: gemaess Templates oben.
- `ReplaceTrigger`-Pfad:
  - MySQL kennt kein natives `CREATE OR REPLACE TRIGGER` → Drop+Create
    ist die einzige Option.
  - Operation traegt `REPLACE_TRIGGER_VIA_DROP_CREATE`-Decision-
    Vermerk, `up.hasGap = true`, `W_TRIGGER_REPLACE_GAP`-Warnung
    aus der Foundation in Slice A.1.
  - `--strict`-Pfad: `MANUAL_ACTION_REQUIRED` (gleiche Foundation).
  - `before`-Body muss bekannt sein; sonst
    `ROUTINE_DOWN_BODY_UNKNOWN`.
- `MysqlDiffDdlGenerator`: Kategorisierung umstellen, Helper
  einhaengen.
- Capability: `create_or_replace_trigger = { enabled = false }`.
  Hardcoded.
- DEFINER-Behandlung (analog E.1 F.6 MySQL Routinen): heutiges
  Trigger-Modell hat kein Definer-Feld → bewusster Carve-out (§7.2).
  E.2 rendert keinen Definer.
- Tests:
  - Positiv: `CREATE TRIGGER` exakt nach Template (mit und ohne
    `BEGIN ... END`-Body).
  - `DROP TRIGGER <name>` (bare name; **kein** `<table>.<name>`-
    Qualifizierer, der ist MySQL-Syntaxfehler).
  - `condition != null` → `DIALECT_UNSUPPORTED_OPERATION`.
  - `forEach = STATEMENT` → `DIALECT_UNSUPPORTED_OPERATION`.
  - Replace via Drop+Create inkl. Gap-Risk + Warnung.
  - `--strict` blockt Replace als `MANUAL_ACTION_REQUIRED`.
  - Fehlender Vorbody-Blocker.
  - Goldenness fuer Create/Drop/Replace.

**Abgrenzung B:** keine PG/SQLite-Aenderungen; keine
Trigger-Reverse-Read-Erweiterung (Reader existiert bereits).

### Sub-Slice C — SQLite Trigger-Rendering

**Lieferumfang:**

- Neuer `SqliteTriggerDdlHelper.kt`.
- **Body-Vertrag fuer SQLite**: `TriggerDefinition.body` ist ein
  inline SQL-Block, der ueber `BEGIN ... END` gewrappt wird.
- Render-Templates (verbindlich):

  ```
  CREATE TRIGGER <name>
      <timing> <event>
      ON <table>
      [WHEN <condition>]
      BEGIN
          <body>
      END;
  ```

  ```
  DROP TRIGGER <name>;     -- SQLite-Trigger sind global, table-context
                           -- ergibt sich aus dem catalog
  ```

  `FOR EACH ROW` ist in SQLite implizit. `forEach = STATEMENT` blockt
  mit `DIALECT_UNSUPPORTED_OPERATION` (SQLite kennt nur ROW).
  `timing = INSTEAD_OF` ist nur fuer Views erlaubt — der Renderer
  rendert es 1:1, eine View-Validierung gehoert dem Planner.
- `CreateTrigger`/`DropTrigger`: gemaess Templates oben.
- `ReplaceTrigger`-Pfad: immer Drop+Create-Fallback (SQLite kennt
  kein `CREATE OR REPLACE TRIGGER`); selbe Foundation-Behandlung wie
  in Slice B (`REPLACE_TRIGGER_VIA_DROP_CREATE`, `up.hasGap = true`,
  `W_TRIGGER_REPLACE_GAP`, `--strict` → `MANUAL_ACTION_REQUIRED`).
- `SqliteDiffDdlGenerator`: Kategorisierung umstellen, Helper
  einhaengen.
- Capability: `create_or_replace_trigger = { enabled = false }`.
- **SQLite-Rebuild-Interaktion (klare Verantwortlichkeitsteilung)**:
  - `SqliteRebuildPlanner` ist **autoritativ** fuer alle
    Trigger-Operationen auf rebuild-betroffenen Tabellen. Es
    absorbiert die `CreateTrigger`/`ReplaceTrigger`/`DropTrigger`-
    Ops fuer diese Tabellen in den Rebuild-Block (drop alter Trigger
    vor `INSERT INTO new_table` und recreate nach Tabellenrename) und
    entfernt sie aus der Top-Level-`DiffResult.operations`-Liste.
  - `SqliteDiffDdlGenerator` bekommt **eine bereits gefilterte
    Operationsliste** und rendert Trigger-Operationen blind — er
    braucht keine eigene Rebuild-Awareness. Damit gibt es genau
    eine Stelle, an der die Suppression passiert, und kein
    Doppel-Filtering zwischen Planner und Renderer.
  - Tests pinnen das: ein Rebuild-Fixture mit drei
    `CreateTrigger`-Ops auf der rebuild-Tabelle erzeugt im finalen
    DDL **keinen separaten `CREATE TRIGGER`-Block ausserhalb des
    Rebuild-Pakets**.
- **SQLite-Reverse-Read**: SQLite-Trigger-Reverse-Read aus
  `sqlite_master` ist heute nicht implementiert. E.2 erfordert ihn fuer
  Live-DB-Live-DB-Migrations nicht zwingend, weil Datei-zu-Datei der
  primaere SQLite-Pfad bleibt. Reverse-Read landet als Folge-Slice (mit
  eigenem Plan-Doc, analog `mysql-routine-identity-reverse-read.md`)
  und ist NICHT E.2-Sub-Slice C.
- Tests:
  - Datei-zu-Datei Positiv (CREATE/DROP).
  - Replace via Drop+Create inkl. Gap-Risk + Warnung.
  - `--strict` blockt Replace als `MANUAL_ACTION_REQUIRED`.
  - `forEach = STATEMENT` → `DIALECT_UNSUPPORTED_OPERATION`.
  - Rebuild-Pfad: drei `CreateTrigger`-Ops auf rebuild-Tabelle
    werden vom Rebuild-Planner absorbiert und tauchen im finalen
    DDL **nicht als separate Top-Level-Ops** auf.
  - Goldenness fuer Create/Drop/Replace und Rebuild-Trigger-
    Absorption.

**Abgrenzung C:** keine SQLite-Reverse-Read; keine Aenderungen an
PG/MySQL.

### Sub-Slice D — Roadmap- und Spec-Update

**Status:** ✅ 2026-05-18.

**Geliefert:**

- `docs/planning/in-progress/roadmap.md`: 0.9.7-Arbeitsstand-Block
  ergaenzt um "E.2 Trigger-Rendering" + "E Rest"-Punkt um
  Trigger-Komponente bereinigt + F.4-Hinweis "E.1/E.2-Vorbedingung
  erfuellt" + Footer-Status-Zeile erweitert.
- `docs/planning/in-progress/diffresult-migration-plan-2.md` §9.E.2:
  Status-Header auf `done ✅ 2026-05-18` + E.2-Implementierungs-
  Carve-outs-Block angefuegt (Modell-Mindestumfang, Identitaets-
  Kollisions-Detektor, SQLite-Rebuild-Absorption, Body-Sanitisation-
  Out-of-Scope etc.).
- `spec/cli-spec.md`:
    - Neue `schema migrate`-Option `--strict-gap-operations` in der
      Options-Tabelle.
    - Neuer Trigger-Rendering-Abschnitt nach Routine-Rendering, der
      Templates pro Dialekt, Pre-Flight-Blocker, Gap-Vertrag,
      Identitaets-Kollisions-Detektor und Body-Sanitisation-Boundary
      dokumentiert.
- `CHANGELOG.md`: "0.9.7 E.2 Trigger-Rendering Vollscheibe"-Eintrag
  unter `[Unreleased] / Added` ergaenzt; deckt PG-/MySQL-/SQLite-
  Render-Templates, Gap-Vertrag, `--strict-gap-operations`,
  `TriggerNameCollisionDetector`, neue Code-Carriers und Carve-outs
  ab.
- Plan-Doc wandert nach `docs/planning/done/`.

**Abgrenzung D:** kein Renderer-Code, kein neuer Test — reines
Doku-Closing.

## 4. Architektur

### 4.1 Body-Vertrag (wiederverwendet aus E.1)

- `RoutineBodyNormalizer.normalize(body)` und `bodyHash` greifen
  unveraendert. Der Trigger-Body wird mit demselben Normalizer
  behandelt; **die Body-Form ist aber dialektspezifisch**:
    - **PostgreSQL**: Body ist eine Funktionsreferenz
      `fn_name([args])` (siehe Sub-Slice A.2). Der Helper validiert
      die Form *vor* der Normalisierung. Inline-PL/pgSQL-Bodies sind
      ausserhalb von E.2-Scope und blocken mit
      `TRIGGER_BODY_NOT_FUNCTION_REFERENCE`.
    - **MySQL**: Body ist inline SQL (einzelnes Statement oder
      `BEGIN ... END`-Block), ohne MySQL-Delimiter.
    - **SQLite**: Body ist inline SQL, der Renderer wrappt ihn in
      `BEGIN ... END`.
- Display-Plane-Ausgabe nutzt `{hash, length, scrubbedPreview,
  scrubbingApplied}` (E.1-Display-Vertrag); unmaskierter Body nur mit
  `--debug-body`.
- Log-/Diagnostic-Plane: `RoutineBodyLogRedactor`-Wiring deckt
  Trigger-Bodies automatisch ab (gleicher Carrier-Typ).

### 4.2 Capability-Modellerweiterung

`RoutineCapabilities` (aus E.1) wird um `create_or_replace_trigger`
erweitert oder bekommt eine analoge Struktur in einer dedizierten
`TriggerCapabilities` — Entscheidung im Slice A.

```kotlin
data class TriggerCapabilities(
    val createOrReplace: CapabilitySupport =
        CapabilitySupport.disabled(),
)
```

Wenn die Foundation aus E.1 (`RoutineCapabilities` mit
`createOrReplaceRoutine`) bereits einen generischen
`CapabilitySupport`-Typ exportiert, wird dieser wiederverwendet.

### 4.3 Renderer-Verkabelung

Pro Dialekt-Generator wird die Kategorisierung umgestellt:

```kotlin
// Vorher (Beispiel PG):
is DiffOperation.CreateTrigger,
is DiffOperation.ReplaceTrigger,
is DiffOperation.DropTrigger -> OpCategory.UNSUPPORTED

// Nachher:
is DiffOperation.CreateTrigger,
is DiffOperation.ReplaceTrigger,
is DiffOperation.DropTrigger -> OpCategory.TRIGGER
```

Eine neue `OpCategory.TRIGGER` (oder Wiederverwendung von
`OpCategory.OTHER` — Entscheidung im Slice A) leitet auf den neuen
`*TriggerDdlHelper` um. `OpCategory` ist eine Renderer-interne
Sortierhilfe und wirkt nicht auf den oeffentlichen Plan-Vertrag.

### 4.4 Identitaet und Dependency

- `DiffObjectRef(TRIGGER, [name])` bleibt Arity-1 (siehe §3 Carve-out).
- Tabellenkontext fuer SQL-Templates kommt aus
  `TriggerDefinition.table` — nicht aus `objectRef`.
- `RoutineDependencyAnalyzer.triggerCreateEdges(trigger, opId)` und
  `DropTrigger.trigger.dependencies` werden vom Slice nicht
  angefasst; der Renderer prueft nur, dass der Dependency-Plan
  Trigger-Operationen nach Tabellen-/Routine-/View-Operationen
  einplant (Test mit Fixture).

### 4.5 Down-Vertrag

- `CreateTrigger.toDown()` → `DropTrigger` (sicher).
- `DropTrigger.toDown()` → `CreateTrigger(before)` (alter Body
  muss bekannt sein).
- `ReplaceTrigger.toDown()` → `ReplaceTrigger(after, before)` bzw.
  Drop+Create-Fallback; `before.body == null` blockt mit
  `ROUTINE_DOWN_BODY_UNKNOWN`.
- Drop+Create-Down erbt die Gap-Risk-/Strict-Behandlung aus §2:
  `up.hasGap = true` (im Down-Plan), `W_TRIGGER_REPLACE_GAP`-Warnung
  fuer Down, `--strict` blockt das Down-Artefakt mit
  `MANUAL_ACTION_REQUIRED` statt es zu erzeugen.

`bodyEmbedding` (aus E.1 F.3) wirkt analog: Persistenzfaehigkeit
fuer Artefakte, kein Ersatz fuer fehlenden Vorbody im laufenden
Pfad.

### 4.6 SQLite-Rebuild-Verantwortlichkeit (zentralisiert)

Die Suppression von Trigger-Operationen auf rebuild-betroffenen
Tabellen passiert **ausschliesslich im** `SqliteRebuildPlanner`,
nicht im Renderer. Das schafft eine klare Verantwortlichkeitsteilung:

1. `OperationMapperRoutines.mapTriggers(...)` emittiert
   `CreateTrigger`/`ReplaceTrigger`/`DropTrigger` wie heute, ohne
   Rebuild-Wissen.
2. `SqliteRebuildPlanner` sieht die volle Operationsliste, identifiziert
   rebuild-betroffene Tabellen, **absorbiert** alle Trigger-Operationen
   auf diesen Tabellen in den Rebuild-Block (drop/recreate Trigger als
   Teil des Tabellen-Rebuild) und gibt eine **gefilterte
   Operationsliste** an `SqliteDiffDdlGenerator` weiter.
3. `SqliteDiffDdlGenerator` rendert die uebergebenen Trigger-Ops
   1:1 ueber `SqliteTriggerDdlHelper`, ohne erneute Rebuild-Pruefung.

Damit gibt es keine doppelte Filter-Logik zwischen Planner und
Renderer. Tests pinnen die Verantwortlichkeit: ein Unit-Test prueft
den `SqliteRebuildPlanner`-Output (gefilterte Liste), ein
Integration-Test prueft das DDL-Endprodukt (kein separater
`CREATE TRIGGER` ausserhalb des Rebuild-Pakets).

## 5. Akzeptanzkriterien

- [ ] `CreateTrigger`/`ReplaceTrigger`/`DropTrigger` werden in
      PostgreSQL/MySQL/SQLite-Renderern nicht mehr als
      `DIALECT_UNSUPPORTED_OPERATION` blockiert.
- [ ] `TriggerNameCollisionDetector` arbeitet auf einer
      `List<NamedTrigger>`-Form **vor** der `.toMap()`-Materialisierung
      und blockt mit `TRIGGER_NAME_COLLISION` bei gleichem Namen auf
      verschiedenen Tabellen. Reader-Adapter rufen den Detektor vor
      dem Map-Schritt; CLI-Layer pinnt das in einem Adapter-Test.
- [ ] `YamlSchemaCodec` ist explizit getestet: ein YAML-Dokument
      mit doppeltem Trigger-Map-Key fuehrt zu einer
      `JsonMappingException`/`DuplicateKeyException` aus dem Codec
      (Jacksons `FAIL_ON_READING_DUP_TREE_KEY` greift), statt
      stillem Ueberschreiben.
- [ ] Detector-Tests decken positiv (zwei verschiedene Tabellen mit
      gleichem Triggernamen → Block) und negativ (gleicher Name auf
      gleicher Tabelle → keine Kollision; einzelner Trigger → kein
      Block) ab.
- [ ] PostgreSQL rendert `CREATE TRIGGER`/`DROP TRIGGER` exakt nach
      den Templates in Sub-Slice A.2 mit `timing`, `event`, `forEach`,
      optionalem `WHEN`-Block und `EXECUTE FUNCTION`-Aufruf aus dem
      validierten Funktionsreferenz-Body. `EXECUTE PROCEDURE` wird
      nicht gerendert (deprecated Alias).
- [ ] PostgreSQL-Renderer blockt mit
      `TRIGGER_BODY_NOT_FUNCTION_REFERENCE`, wenn
      `TriggerDefinition.body` nicht der konservativen
      `[schema.]identifier([arg, ...])`-Form entspricht.
- [ ] PostgreSQL `ReplaceTrigger` nutzt natives
      `CREATE OR REPLACE TRIGGER`, wenn Capability `enabled=true` und
      Server-Version >= 14; andernfalls Drop+Create-Fallback mit
      `REPLACE_TRIGGER_VIA_DROP_CREATE`-Markierung,
      `up.hasGap = true`, `W_TRIGGER_REPLACE_GAP`-Warnung.
- [ ] `--strict`-Pfad blockt jeden Drop+Create-Fallback von
      `ReplaceTrigger` als `MANUAL_ACTION_REQUIRED` (PG ohne PG-14+,
      MySQL und SQLite immer).
- [ ] MySQL rendert `CREATE TRIGGER`/`DROP TRIGGER` ohne MySQL-
      Delimiter in Artefakten; `condition != null` und
      `forEach = STATEMENT` blocken mit
      `DIALECT_UNSUPPORTED_OPERATION`; Replace ist immer Drop+Create
      mit Gap-Risk + Warnung.
- [ ] SQLite rendert `CREATE TRIGGER ... BEGIN <body> END;` mit
      impliziter ROW-Orientierung; `forEach = STATEMENT` blockt mit
      `DIALECT_UNSUPPORTED_OPERATION`; Replace ist immer Drop+Create
      mit Gap-Risk + Warnung.
- [ ] Body-Vergleich nutzt `RoutineBodyNormalizer`; Body-Hash ist
      Teil der Replace-Drift-Erkennung.
- [ ] Display-Plane gibt nur Hash/Length/scrubbed Preview aus;
      `--debug-body`-Pfad zeigt unmaskierten Body.
- [ ] Log-Plane wendet `RoutineBodyLogRedactor` auf Trigger-Bodies
      an (keine separate Verdrahtung noetig, weil der Redactor
      bereits in den E.1-Boundaries greift).
- [ ] Down-Pfad-Tests pro Dialekt: Drop ↔ Create-Inverse, Replace mit
      bekanntem Vorbody, Blocker mit unbekanntem Vorbody.
- [ ] Capability `create_or_replace_trigger` ist in
      `PostgresEngineCapabilities` bzw. dem zentralen Capability-Modul
      verdrahtet; PG: `enabled=true, minServerVersion="14"`;
      MySQL/SQLite: `enabled=false`.
- [ ] SQLite-Rebuild-Verantwortlichkeit ist im
      `SqliteRebuildPlanner` zentralisiert (siehe §4.6): Der Planner
      absorbiert Trigger-Ops fuer rebuild-Tabellen und liefert eine
      gefilterte Operationsliste an `SqliteDiffDdlGenerator`. Tests
      pinnen sowohl die Planner-Output-Filterung (Unit-Test) als auch
      das DDL-Endprodukt (Integration-Test, kein separater
      `CREATE TRIGGER` ausserhalb des Rebuild-Pakets). Der Renderer
      enthaelt **keine** eigene Rebuild-Suppression-Logik.
- [ ] Dependency-Sortierung deckt mindestens eine Kette mit Tabelle,
      Routine und Trigger ab; Trigger-Operationen werden nach
      Tabellen-/Routine-Operationen geplant.
- [ ] Goldenness pro Dialekt: Positiv-Goldens fuer Create/Drop/Replace
      (mindestens je 1 pro Dialekt) eingecheckt.
- [ ] Coverage je betroffenem Modul >= 90%.
- [ ] CHANGELOG-Eintrag pro Sub-Slice.
- [ ] `roadmap.md` und Plan-2 §9.E.2 sind nach Sub-Slice D auf
      `done` aktualisiert.

## 6. Definition of Done

- [ ] Alle Akzeptanzkriterien aus §5 erfuellt.
- [ ] Vorbedingungen aus dem Header (Workstream G, E.1, D.3b) sind
      nachweisbar gruen.
- [ ] `make docker-test` gruen, Output in `/tmp/build.log`.
- [ ] Coverage je betroffenem Modul >= 90%.
- [ ] Sub-Slice D abgeschlossen: Plan-Datei nach
      `docs/planning/done/` verschoben.

## 7. Risiken

### 7.1 PostgreSQL `CREATE OR REPLACE TRIGGER` ist PG-14+

PG < 14 unterstuetzt das Statement nicht. Capability mit
`minServerVersion="14"` schaltet den Pfad nur frei, wenn die
Server-Version bekannt ist und >= 14. Datei-zu-Datei ohne
Server-Kontext nutzt konservativ Drop+Create.

### 7.2 MySQL DEFINER ohne Modellfeld

Trigger-DEFINER ist in `TriggerDefinition` nicht modelliert.
MySQL rendert keinen Definer, auch wenn die Live-DB einen liefert.
Plan-Doc dokumentiert das als bewussten Carve-out; ein spaeterer
Slice kann ein `definer`-Feld nachreichen (analog E.1 F.6).

### 7.3 SQLite-Trigger-Reverse-Read fehlt

SQLite kann Trigger heute nicht aus einer Live-DB lesen.
Datei-zu-DB-Migrationen mit SQLite koennen Trigger nicht
diff-basiert berechnen. E.2 macht diese Lage explizit und blockt
Live-DB-Live-DB-Trigger-Pfade fuer SQLite, statt zu raten. Ein
separater Plan-Slice fuer SQLite-Trigger-Reverse-Read folgt.

### 7.4 Identitaet `name` vs. `table::name`

Heutige `SchemaDefinition.triggers: Map<String, TriggerDefinition>`
nutzt `name` als Key und kann Mehrdeutigkeit `(name, tableA)` /
`(name, tableB)` strukturell nicht halten — die Map-Materialisierung
wuerde die Information still verlieren. E.2 schliesst diese Luecke
an den beiden realen Eingangspfaden, **ohne** eine Map→List-Migration
vorzuziehen:

1. **Reader-Pfad**: `TriggerNameCollisionDetector` (Sub-Slice A.1)
   arbeitet auf einer `List<NamedTrigger>`-Form **vor** der
   `.toMap()`-Materialisierung. Findet er gleichen Namen auf
   verschiedenen Tabellen, blockt der Aufrufer mit
   `TRIGGER_NAME_COLLISION`. Der Map-Schritt darf erst danach
   passieren.
2. **File-Pfad**: YAML-Schema-Parser nutzt
   `LoaderOptions.setAllowDuplicateKeys(false)`, sodass doppelte
   Map-Keys einen `SchemaParseError` ausloesen statt still zu
   ueberschreiben.

Damit kann E.2 keine Operationen an einer falsch aufgeloesten
Triggerinstanz erzeugen. Die strukturelle Map-Key-Migration auf
`ObjectKeyCodec.triggerKey(table, name)` (echte Mehrdeutigkeit im
Modell halten zu koennen) bleibt F.4-Vorbedingung und wird mit F.4
RenameTrigger nachgereicht.

### 7.5 SQLite-Rebuild-Verantwortlichkeit

Die Suppression von Trigger-Operationen auf rebuild-betroffenen
Tabellen ist **zentral im `SqliteRebuildPlanner`** verankert
(siehe §4.6). Der Renderer rendert blind eine bereits gefilterte
Operationsliste — keine doppelte Filter-Logik zwischen Planner und
Renderer, kein Verhaltensunterschied zwischen verschiedenen
Testkonstellationen.

### 7.6 Trigger-Effekt-Luecke bei Drop+Create-Replace

Replace via Drop+Create erzeugt zwischen `DROP TRIGGER` und
`CREATE TRIGGER` eine kurze Zeitspanne, in der der Trigger nicht
greift. Auf produktionsnahen Schemas mit laufender Schreiblast
kann das zu unbemerkten Datenaenderungen ohne Trigger-Effekt
fuehren. E.2 macht das **explizit sichtbar** ueber
`OperationRisk.hasGap = true`, eine Display-/Report-Warnung
(`W_TRIGGER_REPLACE_GAP`) und einen `--strict`-Pfad, der den
Fallback blockiert. PostgreSQL 14+ vermeidet das ueber natives
`CREATE OR REPLACE TRIGGER`; MySQL/SQLite haben diese Option nicht
und sind damit auf den Gap-Pfad angewiesen.

### 7.7 Trigger-Body-Form ist dialektspezifisch

`TriggerDefinition.body` traegt heute einen `String?`, der je
Dialekt eine andere Form hat: in PostgreSQL eine Funktionsreferenz,
in MySQL/SQLite ein inline SQL-Block. Reader und Schema-Datei
muessen die jeweils richtige Form liefern — der Renderer validiert
in PG mit `TRIGGER_BODY_NOT_FUNCTION_REFERENCE`; MySQL und SQLite
akzeptieren beliebigen SQL-Inhalt und verlassen sich auf die
DB-Engine fuer Syntax-Validierung. Eine strukturierte
Body-Modellierung (z.B. `bodyKind: FUNCTION_REF | INLINE_SQL`) ist
nicht in E.2-Scope, weil sie das neutrale Modell erweitern wuerde.

## 8. Out-of-Scope-Verweis

- TriggerDefinition-Modellerweiterung um `events: List`,
  `enabledState`, `INSERT/UPDATE OF col`-Spaltenliste: nicht in E.2.
- SQLite-Trigger-Reverse-Read aus `sqlite_master`: eigener
  Folge-Slice; nicht in E.2.
- Kanonische `table::name`-Identitaet im Schema-Map und
  `DiffObjectRef.path`: Vorbedingung fuer F.4 RenameTrigger,
  nicht E.2.
- Trigger-Reinjektion innerhalb der SQLite-Rebuild-Pipeline:
  Phase-H-Scope; E.2 dokumentiert nur die Abgrenzung.
- Trigger-Definer/Owner-Migration: nicht in E.2 (siehe §7.2).
