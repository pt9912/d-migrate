# Implementierungsplan: 0.9.7 — F.5 CHECK-/EXCLUDE-Vollscheibe

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: F.5 (vollständige Diffbarkeit von CHECK-/EXCLUDE-Constraints)
> **Status**: open 2026-05-19. Erstscheibe ✅ 2026-05-12 (konservativer
>            SQL-Textvergleich); Vollscheibe folgt unter diesem Plan-Doc.
> **Vorbedingung**: F.5-Erstscheibe ✅ 2026-05-12
>                  (`ConstraintDiffContract.canonicalRawSqlExpression()` +
>                  `DiffPlanner.detectConstraintNotDiffableTables(...)`);
>                  Workstream G ✅; F.0 Overlay-Vertrag ✅;
>                  F.1 DataTransformationContract ✅;
>                  Datei-zu-DB Reader-Pfade ✅ pro Dialekt.
> **Referenz**: `diffresult-migration-plan-2.md` §F.5 (Erstscheibe);
>             `done/ImpPlan-0.9.7-F.4-renderer-blocker-bridge.md` (Reason-
>             Classifier-Pattern als Vorlage fuer F.5-spezifische
>             Blocker-Reasons).

---

## 1. Auslöser

Die F.5-Erstscheibe vom 2026-05-12 hat einen wichtigen Halbschritt
gemacht: CHECK- und EXCLUDE-Constraints werden nicht mehr pauschal
aus dem Schema-Vergleich entfernt, und unveraenderte Constraints
blockieren migrationsfremde Tabellenoperationen nicht mehr. Der
Vergleich ist heute ein konservativer SQL-Textvergleich
(`ConstraintDiffContract.canonicalRawSqlExpression()`:
LF-Normalisierung + Trim).

Was offen bleibt:

1. **Echte Diffbarkeit** von Constraint-Aenderungen — Heute blockt
   `DiffPlanner.detectConstraintNotDiffableTables(...)` jede
   Tabellen-Aenderung, deren CHECK-/EXCLUDE-Constraints
   hinzugefuegt, entfernt oder geaendert werden, mit dem
   diagnostischen Code `CONSTRAINT_NOT_DIFFABLE`. Das ist die
   sichere Default-Loesung — aber kein Operator kann heute eine
   `ALTER TABLE … ADD CHECK …`-Migration ueber d-migrate
   ausfuehren.

2. **Dialekt-Render-Matrix**:
   - PostgreSQL beherrscht CHECK + EXCLUDE nativ; EXCLUDE braucht
     GIST + Spatial-/Range-Types.
   - MySQL 8.0.16+ erzwingt CHECK syntaktisch und semantisch;
     aeltere MySQL-Versionen akzeptieren CHECK-Syntax, ignorieren
     aber semantisch (Enforcement aus). MariaDB 10.2.1+ erzwingt
     CHECK. EXCLUDE existiert in MySQL nicht.
   - SQLite erzwingt CHECK, kennt aber kein EXCLUDE.
   - Cross-Dialect-Transfer (z.B. PG → SQLite mit EXCLUDE) muss
     blocken bzw. graceful degradieren.

3. **Reversibilitaet** bei Constraint-Replace: aendert sich
   nur die Expression eines benannten CHECK, ist Drop+Add die
   einzige sichere Operation. Down ist reversibel, wenn beide
   Expressions bekannt sind.

4. **Daten-Preflight** fuer neue restriktive Constraints: ein neuer
   `CHECK (age >= 0)` kann bestehende Zeilen verletzen. Im
   Execute-Modus braucht es einen Live-DB-Scan oder einen
   expliziten `--skip-data-preflight`-Carve-out; im Datei-zu-Datei-
   Modus bleibt die Operation `MANUAL_ACTION_REQUIRED`.

5. **Enforcement-Mode-Unklarheit** auf MySQL: ohne erkannten Server-
   Version blockt die Migration heute mit
   `MYSQL_CHECK_ENFORCEMENT_UNKNOWN` (sollte Code-Konstante werden,
   heute Sammelname) statt stillschweigend `NOT ENFORCED` zu
   uebernehmen.

---

## 2. Warum jetzt?

§11 DoD Box (a/b/c/d/e) ist abgehakt — die Audit-Triplett-Schliessung
ist durch. CHECK/EXCLUDE-Aenderungen sind aktuell der groesste
funktional offene Punkt im Milestone 0.9.7 (Roadmap §F.5:
"teilerledigt"), und der einzige, der eine eigene
Produkt-Workstream-Groesse hat. Ohne F.5-Vollscheibe koennen
Operatoren Tabellen-Migrationen, die CHECK-Constraints hinzufuegen
oder aendern, nicht via d-migrate fahren — sie muessen die
betroffenen Tabellen vorher manuell anfassen.

---

## 3. Scope

### 3.1 In-Scope

- Vier neue `DiffOperation`-Subtypes:
  - `AddConstraintCheck` *(oder Re-Use des bestehenden
    `AddConstraint` + `ConstraintType.CHECK`-Branch)*
  - `DropConstraintCheck` *(analog)*
  - `AddConstraintExclude`
  - `DropConstraintExclude`
  *(Implementation-Entscheidung: vermutlich Re-Use ueber den
  bestehenden `AddConstraint`/`DropConstraint`-Datentyp, weil die
  Identitaet ueber `ConstraintType` und `name` schon eindeutig ist;
  die Wahl waehrend Sub-Slice A.)*
- `ConstraintDiffContract.comparable` erweitern: heute nur
  LF/Trim-Kanonisierung. Sub-Slice A erweitert auf einen
  **konservativen-Kanonisierer** (Whitespace-Kompaktierung,
  Klammer-Normalisierung optional), aber KEINEN SQL-Parser. Die
  Decision: heute textvergleich, morgen SQL-Parser bleibt
  out-of-scope dieses Slices — siehe §9.
- Per-Dialekt-Renderer fuer CHECK Add/Drop:
  - PostgreSQL: `ALTER TABLE … ADD CONSTRAINT name CHECK
    (expression)` / `ALTER TABLE … DROP CONSTRAINT name`.
  - MySQL: gleiches DDL; zusaetzlich Enforcement-Capability-Gate
    (siehe unten).
  - SQLite: CHECK ist nur via Rebuild-Pipeline aenderbar (SQLite
    kennt kein `ALTER TABLE ADD CONSTRAINT`). Sub-Slice D
    integriert in den SQLite-Rebuild-Planner.
- Per-Dialekt-Renderer fuer EXCLUDE (PG-only): `ALTER TABLE … ADD
  CONSTRAINT name EXCLUDE USING gist (…) WHERE (…)`. MySQL und
  SQLite blocken mit `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` (neuer
  `MigrationBlockedReason`).
- MySQL CHECK-Enforcement-Capability:
  - `mysqlServerVersion`-getriebenes Enforcement-Mode-Gate
    (`MysqlCheckEnforcementCapability` oder Re-Use von
    `RoutineCapability`-Pattern). Versionen < 8.0.16 → CHECK ist
    Syntax, aber semantisch `NOT ENFORCED`. Operator muss
    `--allow-check-not-enforced` setzen ODER die Migration blockt
    mit `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16`.
  - MariaDB-Pfad: Vendor-String erkennt MariaDB ≥ 10.2.1 →
    Enforcement an.
- Daten-Preflight fuer neue restriktive CHECK:
  - Im `--execute`-Modus mit DB-Target: `SELECT count(*) FROM tbl
    WHERE NOT (expression)` als Preflight; > 0 → Block mit
    `CHECK_PREFLIGHT_VIOLATIONS` (neuer
    `MigrationBlockedReason` oder Reuse von Cast-Preflight-Pattern).
  - Im Datei-zu-Datei-Modus: keine Live-Daten greifbar → Operator
    muss `--skip-data-preflight` setzen oder die Operation bleibt
    `MANUAL_ACTION_REQUIRED`.
- F.5-spezifische Blocker-Codes in
  `PlannerBlockerClassifier` aufnehmen:
  - `CHECK_PREFLIGHT_VIOLATIONS` →
    `MANUAL_ACTION_REQUIRED` (Operator muss entscheiden).
  - `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` →
    `DIALECT_UNSUPPORTED_OPERATION`.
  - `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` →
    `MANUAL_ACTION_REQUIRED`.
- Plan-Artefakt-Vertrag (`migration-plan.v1`) bleibt
  unveraendert; CHECK/EXCLUDE-Operationen tragen ihre
  `objectType`/`phase` per bestehender Konvention.
- Down-Rendering: `AddConstraint` Down = `DropConstraint`; bei
  Replace (alte Expression existiert) = `DropConstraint` +
  neuer `AddConstraint` mit der alten Expression.

### 3.2 Out-of-Scope

- **Semantischer SQL-Parser** fuer CHECK-Expressions. Der
  konservative Textvergleich bleibt; ein echter Parser
  (`age >= 0` vs `0 <= age` sind semantisch gleich, textuell
  nicht) ist ein separater grosser Slice.
- **EXCLUDE-Vollvariante mit benutzerdefinierten Operator-Klassen**
  (`USING gist (col WITH &&)`). Erste Vollscheibe deckt
  Standard-Range-Operatoren; nicht-standardisierte
  Operator-Klassen blocken mit
  `EXCLUDE_OPERATOR_CLASS_UNSUPPORTED`.
- **MySQL `CHECK` mit Enforcement-Override via Operator** (z.B.
  `--mysql-allow-check-not-enforced`). Erste Vollscheibe blockt
  ohne 8.0.16+ oder MariaDB; spaetere Tranche kann das Override
  einfuehren.
- **Daten-Preflight ueber sehr grosse Tabellen** (>10M Zeilen)
  mit Limit/Sampling-Strategie. Erste Vollscheibe macht den
  ganzen Scan; Operatoren mit grossen Tabellen muessen den
  Preflight ueberspringen.
- **`NOT VALID` + `VALIDATE` Zwei-Stufen-Workflow** fuer
  PostgreSQL CHECK auf grossen Tabellen. Out of scope dieses
  Slices, weil der Two-Step ein neues Operation-Subtyp-Paar
  braucht.
- **CHECK-Constraints, die Cross-Table-Referenzen brauchen**
  (PG `CHECK (… (SELECT … FROM other_table))`). Konservativer
  Blocker mit `CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED`.

---

## 4. Vorbedingungen

| Vorbedingung | Status | Kommentar |
| ------------ | ------ | --------- |
| F.5-Erstscheibe (konservativer Textvergleich) | ✅ 2026-05-12 | `ConstraintDiffContract.comparable` aktiv; `CONSTRAINT_NOT_DIFFABLE`-Block fuer Aenderungen |
| Workstream G (transactionScope etc.) | ✅ | Pflicht fuer Constraint-DDL |
| F.0 Overlay-Vertrag | ✅ | Falls Operator-Overlays fuer Carve-outs gebraucht werden |
| F.1 DataTransformationContract | ✅ | Preflight-Pfade ordnen sich unter F.1 ein (`MANUAL_REQUIRED`) |
| F.4 Renderer-Blocker-Bridge | ✅ 2026-05-19 | `PlannerBlockerClassifier`-Pattern verwendbar fuer F.5-Reasons |
| Per-Dialekt Reader fuer CHECK | ✅ (PG, MySQL, SQLite) | Schema-Reader liest CHECK-Expression heute korrekt aus |
| Per-Dialekt Reader fuer EXCLUDE | ⚠️ | PG-Reader liest EXCLUDE; MySQL/SQLite haben keinen, brauchen sie auch nicht |
| MySQL Server-Version-Detection | ✅ E.1 Slice C.2 | Vendor-String + `mysqlServerVersion` verfuegbar |
| SQLite-Rebuild-Pipeline | ✅ | F.5 CHECK-Aenderungen reihen sich in Rebuild ein |

---

## 5. Architektur

### 5.1 Comparison-Modell

`ConstraintDiffContract.canonicalRawSqlExpression()` heute:

```kotlin
private fun String.canonicalRawSqlExpression(): String =
    replace("\r\n", "\n").replace('\r', '\n').trim()
```

Vollscheibe-Erweiterung (additive — bestehende Tests bleiben gruen,
weil identische Eingaben dieselbe Ausgabe produzieren):

```kotlin
private fun String.canonicalRawSqlExpression(): String =
    replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
        // Optional, falls als "verlustfrei" akzeptiert: kollabiere
        // mehrfach-Whitespace zu single space INNERHALB der Expression.
        // Bleibt ein Carve-out — der Textvergleich wird strikt
        // dokumentiert. Klammer-Normalisierung NICHT enthalten.
```

Decision-Punkt fuer Sub-Slice A: bleibt es bei LF/Trim, oder kommt
Whitespace-Kollaps? Letzteres verringert false-positives bei
formatierten Expressions, kann aber theoretisch zwei Ausdruecke
gleich-canonisieren, die semantisch unterschiedlich sind
(unrealistisch, aber dokumentenpflichtig). Empfehlung: konservativ
bleiben; A nimmt nur LF/Trim + Whitespace-Kollaps wenn beide
Expressions tatsaechlich gleich-LANG-nach-Kollaps sind. Sonst Block.

### 5.2 Operation-Modell

Re-Use ueber `AddConstraint`/`DropConstraint`. `ConstraintType.CHECK`
und `ConstraintType.EXCLUDE` sind heute bereits im Enum und werden
heute von Mapper/Renderer GESKIPPT (`if (c.type == CHECK || EXCLUDE)
continue` an mehreren Stellen).

Sub-Slice A entfernt diese Skips konditional: wenn
`detectConstraintNotDiffableTables` die Tabelle NICHT blockt
(neue Vollscheibe-Logik), iteriert der Mapper auch ueber CHECK und
EXCLUDE.

Alternative: neue Subtypes `AddCheckConstraint` / `DropCheckConstraint` /
`AddExcludeConstraint` / `DropExcludeConstraint`. Vorteil: explizit
typed. Nachteil: vier neue Datenklassen + Mapper-/Renderer-Pfade.
Entscheidung in Sub-Slice A.

### 5.3 Per-Dialekt-Render-Matrix

| Dialekt | CHECK Add | CHECK Drop | EXCLUDE Add | EXCLUDE Drop |
|---|---|---|---|---|
| PostgreSQL | `ALTER TABLE t ADD CONSTRAINT n CHECK (expr)` | `ALTER TABLE t DROP CONSTRAINT n` | `ALTER TABLE t ADD CONSTRAINT n EXCLUDE USING gist (…)` | `ALTER TABLE t DROP CONSTRAINT n` |
| MySQL (≥ 8.0.16 / MariaDB ≥ 10.2.1) | `ALTER TABLE t ADD CONSTRAINT n CHECK (expr)` | `ALTER TABLE t DROP CHECK n` | BLOCKED `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` | dito |
| MySQL (< 8.0.16, kein MariaDB) | BLOCKED `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` | dito | BLOCKED | dito |
| SQLite | Rebuild-Pipeline absorbiert CHECK-Add | Rebuild-Pipeline absorbiert CHECK-Drop | BLOCKED `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` | dito |

### 5.4 Daten-Preflight (neue restriktive CHECK)

Pattern wiederverwendbar von `SqliteCastPreflightProbe`:

```
fun runCheckPreflight(
    op: AddConstraint,  // CHECK-Variante
    targetOp: SchemaMigrateTargetOperand,
): CheckPreflightOutcome {
    val sql = "SELECT count(*) FROM ${quote(table)} WHERE NOT (${expression})"
    return when (val count = probe.executeCount(sql)) {
        0L -> CheckPreflightOutcome.PASSED
        else -> CheckPreflightOutcome.FAILED(count)
    }
}
```

`CheckPreflightOutcome.FAILED` → Block mit
`CHECK_PREFLIGHT_VIOLATIONS` (Reason
`MANUAL_ACTION_REQUIRED`). Im Datei-zu-Datei-Modus ist der Probe
nicht erreichbar; Op bleibt `MANUAL_ACTION_REQUIRED` mit Hinweis
auf `--execute` gegen DB.

### 5.5 MySQL Enforcement-Gate

Re-Use des `routineCapability`-Patterns:

```
data class MysqlCheckEnforcementCapability(
    val enforced: Boolean,
    val rationale: String,
)

object MysqlCheckEnforcementResolver {
    fun resolve(serverVersion: MysqlServerVersion): MysqlCheckEnforcementCapability {
        return when {
            serverVersion.isMariaDb && serverVersion >= "10.2.1" -> ENFORCED("MariaDB ≥ 10.2.1")
            !serverVersion.isMariaDb && serverVersion >= "8.0.16" -> ENFORCED("MySQL ≥ 8.0.16")
            else -> NOT_ENFORCED("MySQL < 8.0.16 ignores CHECK semantics")
        }
    }
}
```

Renderer blockt wenn `!enforced`. Operator-Override fuer
`NOT_ENFORCED` ist Out-of-Scope §3.2.

### 5.6 `PlannerBlockerClassifier`-Erweiterung

Sub-Slice A fuegt drei neue Codes hinzu:

```
"CHECK_PREFLIGHT_VIOLATIONS" -> MANUAL_ACTION_REQUIRED
"EXCLUDE_NOT_SUPPORTED_BY_DIALECT" -> DIALECT_UNSUPPORTED_OPERATION
"MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16" -> MANUAL_ACTION_REQUIRED
"CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED" -> MANUAL_ACTION_REQUIRED
```

Default-Fallback `DIALECT_UNSUPPORTED_OPERATION` bleibt.

### 5.7 Reversibilitaet

`AddConstraint(CHECK)`:
- Reversibility `AUTOMATIC` wenn alte Expression bekannt und der
  Down-Pfad `DropConstraint` ergibt.
- Reversibility `AUTOMATIC_WITH_DATA_RISK` wenn die Constraint
  semantisch restriktiv ist und der Drop daten-erhaltend ist
  (es gibt keinen Daten-Schaden beim Drop).

`DropConstraint(CHECK)` mit `AddConstraint(CHECK)`-Inverse:
- Reversibility `AUTOMATIC_WITH_DATA_RISK` wenn die alte Expression
  bekannt UND ein Daten-Preflight in Down-Richtung NICHT
  garantiert (Down-Rendering laeuft typischerweise wenn das Up
  schon angewendet war, dann sind die Daten konform).
- Wenn alte Expression nicht bekannt → `NOT_REVERSIBLE` /
  `ROLLBACK_NOT_POSSIBLE`.

`ReplaceConstraint` (gleicher Name, andere Expression):
- Implementierung: `DropConstraint(old)` + `AddConstraint(new)`,
  beide mit eigenem Reversibility-Vertrag.

---

## 6. Sub-Slice-Schnitt (Vorschlag)

### Sub-Slice A — Foundation (dialekt-neutral)

- `ConstraintDiffContract.comparable`-Erweiterung
  (Whitespace-Kollaps-Entscheidung).
- `DiffPlanner.detectConstraintNotDiffableTables` lockern:
  Tabellen mit CHECK-/EXCLUDE-Aenderungen werden nur noch blockiert,
  wenn die Aenderung nicht durch Sub-Slice B/C/D gerendert werden
  kann.
- `OperationMapper` iteriert auch ueber CHECK/EXCLUDE-Constraints
  (Skip entfernt).
- `RenameIntraObjectDeltaSynthesizer`-Skips lockern (analog).
- `PlannerBlockerClassifier` um die vier F.5-Codes ergaenzen.
- Tests: Constraint-Comparison-Pin (insbesondere
  Whitespace-Kollaps), Mapper emittiert `AddConstraint`/
  `DropConstraint` fuer CHECK/EXCLUDE-Diffs.

### Sub-Slice B — PostgreSQL Renderer

- `PostgresDiffOtherOps.renderAddConstraint` /
  `renderDropConstraint` Branch fuer CHECK + EXCLUDE entwirft die
  SQL-Templates.
- EXCLUDE-Rendering inkl. `USING gist (…)` und optionalem
  `WHERE (…)`.
- Tests: per-Subtyp Up/Down-Render-Pins; Carve-out-Tests fuer
  Cross-Table-Referenzen und unbekannte Operator-Klassen.

### Sub-Slice C — MySQL Renderer + Enforcement-Capability

- `MysqlCheckEnforcementCapability` einfuehren.
- `MysqlDiffOtherOps.renderAddConstraint` /
  `renderDropConstraint` Branch fuer CHECK (Add: `CHECK (…)` mit
  Capability-Gate; Drop: `DROP CHECK n`).
- EXCLUDE blockt unconditional mit
  `EXCLUDE_NOT_SUPPORTED_BY_DIALECT`.
- Tests: MySQL ≥ 8.0.16 positive; MariaDB ≥ 10.2.1 positive;
  < 8.0.16 blockt; EXCLUDE blockt.

### Sub-Slice D — SQLite Renderer (Rebuild-Pipeline)

- `SqliteRebuildPlanner.classify` erweitert: CHECK-Aenderungen
  triggern Tabellen-Rebuild (CHECK gehoert zur Tabellen-Schema-
  Definition, ist nicht via ALTER TABLE aenderbar).
- `SqliteRebuildRenderer` emittiert die rebuilt-Table mit neuer
  CHECK-Liste.
- EXCLUDE blockt mit `EXCLUDE_NOT_SUPPORTED_BY_DIALECT`.
- Tests: SQLite-CHECK-Add ueber Rebuild; CHECK-Drop ueber Rebuild;
  CHECK-Replace ueber Rebuild.

### Sub-Slice E — Daten-Preflight (Cross-Dialect)

- `CheckPreflightProbe`-Interface in `hexagon:ports-read`
  (analog zu `SqliteCastPreflightProbe`).
- Per-Dialekt-Implementierung (`PostgresCheckPreflightProbe`,
  `MysqlCheckPreflightProbe`, `SqliteCheckPreflightProbe`).
- `SchemaMigrateRunner` integriert den Preflight zwischen Render
  und Execute (analog zu Cast-Preflight in der
  `MigrationPreflightPlanner`-Pipeline).
- Datei-zu-Datei: Preflight nicht erreichbar → Operation bleibt
  `MANUAL_ACTION_REQUIRED`.
- Tests: PASSED-Pfad pro Dialekt; FAILED-Pfad pro Dialekt mit
  `CHECK_PREFLIGHT_VIOLATIONS` Blocker; `--execute`-Required-Pfad
  pin.

### Sub-Slice F — Reversibility + Replace-Vertrag

- `ConstraintReplaceContract` modelliert
  `DropConstraint(old) + AddConstraint(new)` mit synchroner
  Reversibility.
- Tests: Replace-Up emittiert beide Ops; Replace-Down inverse;
  Replace mit unbekannter alter Expression blockt mit
  `ROLLBACK_NOT_POSSIBLE`.

### Sub-Slice G — Closing

- §F.5-DoD-Box im master plan abhaken.
- §11 DoD Box (a/b/c) Eintraege fuer die neuen Workstream-Slices
  (CHECK / EXCLUDE) erweitern.
- CHANGELOG-Eintrag `### Added`.
- `spec/cli-spec.md` §6.1 dokumentiert
  `--skip-check-preflight` (falls Sub-Slice E so ausschaltet) und
  die F.5-spezifischen Blocker-Codes.
- Roadmap §F.5-Rest auf erledigt.
- Plan-Doc nach `done/`.

---

## 7. Akzeptanzkriterien

- [ ] PostgreSQL kann CHECK Add/Drop/Replace ueber `ALTER TABLE`
      rendern (Up + Down).
- [ ] PostgreSQL kann EXCLUDE Add/Drop ueber `ALTER TABLE … ADD
      CONSTRAINT … EXCLUDE USING gist (…)` rendern.
- [ ] MySQL ≥ 8.0.16 und MariaDB ≥ 10.2.1 koennen CHECK Add/Drop/
      Replace rendern (Enforcement aktiv per Capability).
- [ ] MySQL < 8.0.16 blockt CHECK-Aenderungen mit
      `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` →
      `MANUAL_ACTION_REQUIRED`.
- [ ] MySQL und SQLite blocken EXCLUDE mit
      `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` →
      `DIALECT_UNSUPPORTED_OPERATION`.
- [ ] SQLite kann CHECK Add/Drop/Replace ueber den
      Rebuild-Pipeline-Pfad ausfuehren (kein eigenes
      `ALTER TABLE ADD CONSTRAINT` noetig).
- [ ] Daten-Preflight im `--execute`-Modus blockt eine neue
      restriktive CHECK, die existierende Zeilen verletzt, mit
      `CHECK_PREFLIGHT_VIOLATIONS` → `MANUAL_ACTION_REQUIRED`.
- [ ] Datei-zu-Datei-Modus: neue restriktive CHECK bleibt
      `MANUAL_ACTION_REQUIRED` mit Verweis auf `--execute`.
- [ ] `ConstraintDiffContract.canonicalRawSqlExpression()` ist
      dokumentiert und gepinnt — zwei semantisch gleiche
      Expressions mit unterschiedlichem Whitespace sind nach
      Kanonisierung gleich; zwei semantisch unterschiedliche
      bleiben unterschiedlich.
- [ ] Reversibility: CHECK-Add Down = CHECK-Drop;
      CHECK-Replace Down = inverse Replace mit alter Expression;
      bei unbekannter alter Expression blockt
      `ROLLBACK_NOT_POSSIBLE`.
- [ ] `PlannerBlockerClassifier` enthaelt die vier neuen Codes mit
      passenden `MigrationBlockedReason`-Werten.
- [ ] Pro Dialekt: Positiv-, Blocker- und (wo anwendbar)
      Rollback-Test fuer CHECK und EXCLUDE.
- [ ] `make docker-check` gruen ueber alle betroffenen Module.
- [ ] §F.5-DoD-Box im master plan abgehakt, Roadmap §F.5-Rest auf
      erledigt.

---

## 8. Definition of Done (§13-Template)

- [ ] **Betroffener Modus**: alle Modi
  (file-to-file, file-to-DB, execute, rollback). Daten-Preflight
  ist execute-only; file-to-file haelt CHECK-Constraint-Adds
  immer als `MANUAL_ACTION_REQUIRED`.
- [ ] **Renderbare Operationen + Blocker**: neu renderbar
  sind CHECK Add/Drop/Replace (PG/MySQL/SQLite) und EXCLUDE
  Add/Drop (PG only). Blocker bleibt MySQL <
  8.0.16, EXCLUDE auf MySQL/SQLite, restriktive CHECK ohne
  Preflight im Datei-zu-Datei-Modus, Cross-Table-CHECK-Referenzen.
- [ ] **Neue Diagnostics / Blocker / primaryBlockedReason**: vier
  neue Codes (`CHECK_PREFLIGHT_VIOLATIONS`,
  `EXCLUDE_NOT_SUPPORTED_BY_DIALECT`,
  `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16`,
  `CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED`) mit Mapping in
  `PlannerBlockerClassifier`. Keine neuen `MigrationBlockedReason`-
  Enum-Werte noetig — die existierenden Reasons
  (`MANUAL_ACTION_REQUIRED`, `DIALECT_UNSUPPORTED_OPERATION`,
  `ROLLBACK_NOT_POSSIBLE`) decken den Vertrag ab.
- [ ] **Up- und Down-Verhalten**: getrennte Akzeptanzkriterien,
  Replace ist Drop+Add mit gemeinsamer Op-ID.
- [ ] **Report-/Metadatenfelder**: neue Operationen erscheinen mit
  `objectType = "CONSTRAINT"` und kind
  `AddConstraint`/`DropConstraint`; `migration-plan.v1`-Artefakt
  fuehrt sie ohne Vertragsaenderung.
- [ ] **Betroffene Dialekte**: PostgreSQL, MySQL, SQLite — alle
  drei mit eigenem Render-Pfad bzw. blockierendem Carve-out.
- [ ] **F.0-Erfuellung**: irrelevant — kein neuer Overlay-Input.
- [ ] **Positive und blockierende Testpfade**: siehe §7.
- [ ] **Rollback-Test oder Begruendung**: CHECK Add Down = Drop
  (positiv); CHECK Replace mit unbekanntem alten Body =
  `ROLLBACK_NOT_POSSIBLE` (Blocker); EXCLUDE Down analog.
- [ ] **Datei-zu-Datei-Verhalten**: Preflight nicht erreichbar →
  `MANUAL_ACTION_REQUIRED` mit Verweis auf `--execute`.
- [ ] **Bestehende 0.9.7-Vertraege unveraendert**: alle bestehenden
  `AddConstraint`/`DropConstraint`-Tests fuer
  UNIQUE/FOREIGN_KEY bleiben gruen. Der konservative Textvergleich
  aus der Erstscheibe bleibt fuer Tabellen, die nicht ueber den
  neuen Pfad renderbar sind.
- [ ] **Slice kann unabhaengig implementiert und verifiziert
  werden**: Sub-Slices A–G sequenziell; A ist Voraussetzung fuer
  alle anderen.

---

## 9. Out-of-Scope / Folge-Themen

- **Semantischer SQL-Parser**: ein echter Parser fuer
  CHECK-Expressions (z.B. via JSqlParser, JaQu oder eigenen
  Parser) macht semantisch-gleiche-aber-textuell-unterschiedliche
  Expressions vergleichbar. Eigener Workstream, mindestens
  PG/MySQL-Spec-Grammar-Parser plus Test-Korpora.

- **PostgreSQL `NOT VALID` + `VALIDATE`-Zwei-Stufen-Workflow**:
  fuer grosse Tabellen blockt heute der Preflight-Scan zu lange.
  Ein neuer `AddConstraintNotValid` + spaeterer
  `ValidateConstraint`-Op-Subtyp koennte das loesen — eigener
  Slice.

- **EXCLUDE mit benutzerdefinierten Operator-Klassen**: erste
  Vollscheibe macht nur Standard-Range-Operatoren. Wer
  `USING gist (col WITH custom_op)` braucht, blockt mit
  `EXCLUDE_OPERATOR_CLASS_UNSUPPORTED`; spaetere Tranche kann
  Operator-Klassen-Whitelist einfuehren.

- **MySQL `CHECK` `NOT ENFORCED`-Override**: heute strikter
  Block ohne 8.0.16+. Operator-Override via
  `--mysql-allow-check-not-enforced` ist denkbar, braucht aber
  einen Carve-out-Vertrag (Operator akzeptiert, dass der CHECK
  nicht enforced wird).

- **Daten-Preflight-Sampling / Limit-Strategien** fuer sehr grosse
  Tabellen — heute Vollscan. Sampling waere Statistik-basierter
  Carve-out (`--check-preflight-sample-rate 0.01`).

- **Cross-Table-CHECK-Referenzen**: PostgreSQL erlaubt
  `CHECK ((SELECT max(x) FROM other_table) > 0)` (mit
  Trigger-Workaround). Erste Vollscheibe blockt; bewusste Carve-
  out, eigener Slice fuer Trigger-basierte-CHECK-Modellierung.

---

## 10. Risiken

### 10.1 Konservativer Textvergleich vs. echte SQL-Aequivalenz

Zwei Expressions koennen semantisch gleich sein, aber textuell
verschieden (`x >= 0` vs `0 <= x`). Erste Vollscheibe sieht das
als Aenderung und triggert Drop+Add. Das ist konservativ richtig
(rendert eine SQL-Aenderung, die der Operator wollte) aber
verlangsamt Migrationen unnoetig auf grossen Tabellen. Mitigation:
dokumentiert in §9 Out-of-Scope; Operatoren koennen die
Expressions exakt kanonisieren.

### 10.2 Daten-Preflight-Performance

Vollscan auf einer 50M-Zeilen-Tabelle dauert. Mitigation:
`--skip-check-preflight`-Flag mit klarem Warnhinweis im Report.

### 10.3 MySQL Enforcement-Detection

Wenn `mysqlServerVersion` nicht gelesen werden kann
(z.B. Privilege-Issue beim Reader), blockt der Renderer per
Default. Mitigation: bestehender E.1-Slice-C.2-Vertrag fuer
`mysqlServerVersion` ist bereits zuverlaessig.

### 10.4 SQLite Rebuild-Trigger fuer CHECK-Aenderung

SQLite-Rebuild auf grossen Tabellen ist teuer (Full-Copy).
Operator sieht einen Tabellen-Rebuild fuer eine simple
CHECK-Aenderung. Mitigation: Report dokumentiert den Rebuild-
Trigger ausdruecklich; `--allow-table-rebuild`-Flag bleibt
existierende Sicherheits-Bedingung.

### 10.5 EXCLUDE-Constraint-Reader auf MySQL/SQLite

Wenn der Operator ein PG-Schema mit EXCLUDE nach MySQL
transferiert, blockt der Cross-Dialect-Renderer mit
`EXCLUDE_NOT_SUPPORTED_BY_DIALECT`. Das ist korrekt; Mitigation
ist nur dokumentarisch.

---

## 11. Erwartete Commit-Reihenfolge

| Sub-Slice | Commit-Subjekt-Skizze |
|---|---|
| A | `feat(check): foundation — comparison contract, mapper passes through, classifier codes` |
| B | `feat(check): PostgreSQL renderer for CHECK + EXCLUDE add/drop` |
| C | `feat(check): MySQL renderer for CHECK with enforcement-capability gate` |
| D | `feat(check): SQLite renderer via rebuild-pipeline absorption` |
| E | `feat(check): live data preflight for restrictive CHECK constraints` |
| F | `feat(check): reversibility + replace contract` |
| G | `docs(plan): F.5 CHECK/EXCLUDE Vollscheibe closing` |

---

## 12. Hinweise fuer Reviewer

- Dieser Slice ist **gross** und kann sich ueber mehrere Wochen
  ziehen. Sub-Slice A ist Vorbedingung; B/C/D koennen parallel
  laufen, sobald A steht.
- **Daten-Preflight (E)** ist der riskanteste Teil — Live-DB-Scan
  mit Operator-Sicherheit. Empfehlung: erst nach B/C/D in
  Production-Tests probieren.
- **Reversibility (F)** ist konservativ: `ROLLBACK_NOT_POSSIBLE`
  fuer unbekannte alte Expressions ist Default. Operatoren mit
  vollstaendigen Schema-Files sehen reine reversibility-Pfade.
- Reviewer kann **B oder C streichen**, falls Dialekt-Prioritaet
  anders ist; A bleibt Pflicht.
