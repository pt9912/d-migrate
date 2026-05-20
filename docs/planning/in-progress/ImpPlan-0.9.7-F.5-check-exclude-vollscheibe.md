# Implementierungsplan: 0.9.7 — F.5 CHECK-/EXCLUDE-Vollscheibe

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: F.5 (vollständige Diffbarkeit von CHECK-/EXCLUDE-Constraints)
> **Status**: in-progress 2026-05-19. Sub-Slices A ✅ + B ✅ + C ✅ +
>            D ✅ + E (E.1+E.2 ✅, E.3 ✅, E.4 ✅ inkl. Review-
>            Follow-ups) — offen: **Sub-Slice F** (Reversibility +
>            Replace-Vertrag), **Sub-Slice G** (Closing/DoD).
>            Erstscheibe ✅ 2026-05-12 (konservativer
>            SQL-Textvergleich).
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
   Execute-Modus braucht es einen Live-DB-Scan; im
   Datei-zu-Datei-Modus ist der Live-Preflight nicht erreichbar und die
   Operation bleibt `MANUAL_ACTION_REQUIRED`.

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
  **konservativen-Kanonisierer**: weiterhin nur LF/Trim-Textnormalisierung,
  keine Klammer-Normalisierung oder Whitespace-Kompaktierung. Es bleibt bei
  keinem SQL-Parser; Parser/weitere Normalisierung sind in Folge-Slices
  vorgesehen (§9).
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
  Planner-Blocker-Code, bestehend auf existierendem
  `MigrationBlockedReason`).
  Nicht-standardisierte EXCLUDE-Operator-Klassen blockt PG ebenfalls mit
  `EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED` als `MANUAL_ACTION_REQUIRED`
  (Operator kann die Constraint mit Standard-Operator-Klasse umschreiben).
  Die Implementierung nutzt eine konservative Whitelist-Validierung
  (`with`-Operator und optionale `WHERE`-Klausel), und alles
  Nicht-Whitelisted wird als unsupported markiert.
- MySQL CHECK-Enforcement-Capability:
  - `mysqlServerVersion`-getriebenes Enforcement-Mode-Gate
    (`MysqlCheckEnforcementCapability` oder Re-Use von
    `RoutineCapability`-Pattern). Versionen < 8.0.16 → CHECK ist
    Syntax, aber semantisch `NOT ENFORCED`. Die Vollscheibe blockt diese
    Kombination mit `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16`; ein
    Operator-Override (`--allow-check-not-enforced`) ist bewusst
    Out-of-Scope.
    Für MariaDB gilt: 10.2.1+ erzwingt ebenfalls CHECK; MariaDB < 10.2.1
    wird als nicht erzwungen behandelt und ebenfalls mit
    `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` blockiert.
  - Wenn die Server-Detection technisch fehlschlaegt (z.B. Rechte/
    Treiberlimitierung), blockiert die Migration mit
    `MYSQL_CHECK_ENFORCEMENT_UNKNOWN`.
  - MariaDB-Pfad: Vendor-String erkennt MariaDB ≥ 10.2.1 →
    Enforcement an.
- Daten-Preflight fuer neue restriktive CHECK:
  - Im `--execute`-Modus mit DB-Target: `SELECT count(*) FROM tbl
    WHERE NOT (expression)` als Preflight; > 0 → Block mit
    `CHECK_PREFLIGHT_VIOLATIONS` (neuer
    `PlannerBlocker`-Code; Reuse von Cast-Preflight-Pattern).
    Ausführungsausfälle (z. B. SQL-/Typfehler, Rechtefehler,
    Verbindungsprobleme) liefern `CHECK_PREFLIGHT_RUNTIME_ERROR` als
    eindeutigen technischen Blocker (`MANUAL_ACTION_REQUIRED`) mit
    Ausnahmetext aus der DB.
  - Im Datei-zu-Datei-Modus: keine Live-Daten greifbar → Operator
    kann hier keinen Preflight erzwingen; die
    Operation bleibt `MANUAL_ACTION_REQUIRED`.
  - Kein neuer CLI-Carve-out (`--skip-check-preflight`) wird in dieser
    Vollscheibe eingeführt; die Sicherheit basiert auf echtem
    Daten-Scan im Execute-Modus.
- F.5-spezifische Blocker-Codes in
  `PlannerBlockerClassifier` aufnehmen:
  - `CHECK_PREFLIGHT_VIOLATIONS` →
    `MANUAL_ACTION_REQUIRED` (Operator muss entscheiden).
  - `CHECK_PREFLIGHT_RUNTIME_ERROR` → `MANUAL_ACTION_REQUIRED`.
  - `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` →
    `DIALECT_UNSUPPORTED_OPERATION`.
  - `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` →
    `MANUAL_ACTION_REQUIRED`.
  - `MYSQL_CHECK_ENFORCEMENT_UNKNOWN` →
    `MANUAL_ACTION_REQUIRED`.
  - `CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED` →
    `MANUAL_ACTION_REQUIRED`.
  - `EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED` →
    `MANUAL_ACTION_REQUIRED` (Operator kann die Constraint mit
    Standard-Operator-Klasse umschreiben).
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
  (`USING gist (col WITH &&)`). Erste Vollscheibe deckt nur
  bekannte Standard-Range-Operatoren; nicht-standardisierte
  Operator-Klassen blocken mit
  `EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED`/`MANUAL_ACTION_REQUIRED`.
- **MySQL `CHECK` mit Enforcement-Override via Operator** (z.B.
  `--allow-check-not-enforced`). Diese Vollscheibe blockt bewusst ohne
  8.0.16+ oder MariaDB; spaetere Tranche kann das Override
  als opt-in Carve-out einfuehren.
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
| F.1 DataTransformationContract | ✅ | Preflight-Pfade ordnen sich unter F.1 ein (`MANUAL_ACTION_REQUIRED`) |
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

Vollscheibe-Entscheidung (keine Funktionserweiterung — bestehende Tests
bleiben gruen, weil identische Eingaben dieselbe Ausgabe produzieren):

```kotlin
private fun String.canonicalRawSqlExpression(): String =
    replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
```

Sub-Slice A trifft folgende harte Entscheidung: `canonicalRawSqlExpression`
bleibt bei `LF`-Normalisierung + `trim()`.
Whitespace-Kompaktierung und Klammer-Normalisierung sind explizit **nicht**
Teil dieses Slices.

### 5.2 Operation-Modell

Re-Use ueber `AddConstraint`/`DropConstraint`. `ConstraintType.CHECK`
und `ConstraintType.EXCLUDE` sind heute bereits im Enum und werden
heute von Mapper/Renderer GESKIPPT (`if (c.type == CHECK || EXCLUDE)
continue` an mehreren Stellen).

Sub-Slice A entfernt diese Skips konditional: wenn
`detectConstraintNotDiffableTables` die Tabelle NICHT blockt
(neue Vollscheibe-Logik), iteriert der Mapper auch ueber CHECK und
EXCLUDE.

Drop-Renderer müssen die Dialekt-spezifische Syntax beachten, d.h. `DROP
CONSTRAINT` (PG/SQLite-Rebuild) und `DROP CHECK` (MySQL/MariaDB), damit
`renderDropConstraint` nicht über ein gemeinsames Template unzulässig
belegt wird.

Alternative: neue Subtypes `AddCheckConstraint` / `DropCheckConstraint` /
`AddExcludeConstraint` / `DropExcludeConstraint`. Vorteil: explizit
typed. Nachteil: vier neue Datenklassen + Mapper-/Renderer-Pfade.
Entscheidung in Sub-Slice A.

### 5.3 Per-Dialekt-Render-Matrix

| Dialekt | CHECK Add | CHECK Drop | EXCLUDE Add | EXCLUDE Drop |
|---|---|---|---|---|
| PostgreSQL | `ALTER TABLE t ADD CONSTRAINT n CHECK (expr)` | `ALTER TABLE t DROP CONSTRAINT n` | `ALTER TABLE t ADD CONSTRAINT n EXCLUDE USING gist (…) WHERE (…)` | `ALTER TABLE t DROP CONSTRAINT n` |
| MariaDB ≥ 10.2.1 | `ALTER TABLE t ADD CONSTRAINT n CHECK (expr)` | `ALTER TABLE t DROP CHECK n` | BLOCKED `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` | dito |
| MySQL ≥ 8.0.16 | `ALTER TABLE t ADD CONSTRAINT n CHECK (expr)` | `ALTER TABLE t DROP CHECK n` | BLOCKED `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` | dito |
| MySQL (< 8.0.16, kein MariaDB) | BLOCKED `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` | `ALTER TABLE t DROP CHECK n` (falls Syntax/Support eindeutig vorhanden) | BLOCKED `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` | dito |
| MariaDB (< 10.2.1) | BLOCKED `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` | `ALTER TABLE t DROP CHECK n` (falls Syntax/Support eindeutig vorhanden) | BLOCKED `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` | dito |
| MySQL (Version unbekannt) | BLOCKED `MYSQL_CHECK_ENFORCEMENT_UNKNOWN` | `ALTER TABLE t DROP CHECK n` (falls Syntax/Support eindeutig vorhanden; sonst BLOCKED `MYSQL_CHECK_ENFORCEMENT_UNKNOWN`) | BLOCKED `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` | dito |
| MariaDB (Version unbekannt) | BLOCKED `MYSQL_CHECK_ENFORCEMENT_UNKNOWN` | `ALTER TABLE t DROP CHECK n` (falls Syntax/Support eindeutig vorhanden; sonst BLOCKED `MYSQL_CHECK_ENFORCEMENT_UNKNOWN`) | BLOCKED `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` | dito |
| SQLite | Rebuild-Pipeline absorbiert CHECK-Add | Rebuild-Pipeline absorbiert CHECK-Drop | BLOCKED `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` | dito |

### 5.4 Daten-Preflight (neue restriktive CHECK)

Pattern wiederverwendbar von `SqliteCastPreflightProbe`:

```
fun runCheckPreflight(
    table: String,
    expression: String,
    probe: CheckPreflightProbe,
    quoteIdentifier: (String) -> String,
): CheckPreflightOutcome {
    val sql = "SELECT count(*) FROM ${quoteIdentifier(table)} WHERE NOT (${expression})"
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
    val known: Boolean,
    val rationale: String,
)

object MysqlCheckEnforcementResolver {
    fun resolve(serverVersion: MysqlServerVersion): MysqlCheckEnforcementCapability {
        return when {
            serverVersion.isUnknown() -> MysqlCheckEnforcementCapability(false, false, "mysqlServerVersion konnte nicht gelesen werden")
            serverVersion.isMariaDb && serverVersion.atLeast(10, 2, 1) -> MysqlCheckEnforcementCapability(true, true, "MariaDB ≥ 10.2.1")
            serverVersion.isMariaDb && !serverVersion.atLeast(10, 2, 1) -> MysqlCheckEnforcementCapability(false, true, "MariaDB < 10.2.1 ignoriert CHECK semantisch")
            !serverVersion.isMariaDb && serverVersion.atLeast(8, 0, 16) -> MysqlCheckEnforcementCapability(true, true, "MySQL ≥ 8.0.16")
            else -> MysqlCheckEnforcementCapability(false, true, "MySQL < 8.0.16 ignores CHECK semantics")
        }
    }
}
```

`atLeast` ist semantisch auf `major.minor.patch` auszulegen; String-Vergleich ist ungültig.

Renderer-Verhalten nach Op-Typ:
- `ADD` / `REPLACE`: blockiert bei `!enforced && known` mit
  `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16`; bei `!known`
  mit `MYSQL_CHECK_ENFORCEMENT_UNKNOWN`.
  Für MariaDB gilt: `< 10.2.1` wird ebenfalls auf
  `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` abgebildet.
- `DROP`: kein Enforcement-Gate, da keine Schutzwirkung gegen
  Datenverletzung erforderlich ist; stattdessen nur klarer
  Syntax-/Capability-Nachweis. Ist dieser nicht vorhanden,
  blockt `MYSQL_CHECK_ENFORCEMENT_UNKNOWN`.
Operator-Override fuer `NOT_ENFORCED` ist Out-of-Scope §3.2.

### 5.6 `PlannerBlockerClassifier`-Erweiterung

Sub-Slice A fuegt sieben neue Codes hinzu:

```
"CHECK_PREFLIGHT_VIOLATIONS" -> MANUAL_ACTION_REQUIRED
"EXCLUDE_NOT_SUPPORTED_BY_DIALECT" -> DIALECT_UNSUPPORTED_OPERATION
"MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16" -> MANUAL_ACTION_REQUIRED
"MYSQL_CHECK_ENFORCEMENT_UNKNOWN" -> MANUAL_ACTION_REQUIRED
"CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED" -> MANUAL_ACTION_REQUIRED
"EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED" -> MANUAL_ACTION_REQUIRED
"CHECK_PREFLIGHT_RUNTIME_ERROR" -> MANUAL_ACTION_REQUIRED
```

Default-Fallback `DIALECT_UNSUPPORTED_OPERATION` bleibt.

### 5.7 Reversibilitaet

`AddConstraint(CHECK)`:
- Reversibility `AUTOMATIC`, wenn der Down-Pfad eindeutig
  `DropConstraint` ergibt.
- Optional: Eine striktere Daten-Risiko-Klassifikation kann in
  einem Folge-Slice ergänzt werden, bleibt für diese Vollscheibe
  jedoch nicht zwingend.

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
- Kompatibilitaet zum bestehenden `migration-plan.v1`: Die bisher
  vorhandene Constraint-Metadaten-Struktur muss die alte Expression
  bereits transportieren; ist sie nicht vorhanden, bleibt der
  kontrollierte Fallback `ROLLBACK_NOT_POSSIBLE`.

---

## 6. Sub-Slice-Schnitt (Vorschlag)

### Sub-Slice A — Foundation (dialekt-neutral) ✅ (2026-05-19)

- `ConstraintDiffContract.comparable`-Erweiterung
  (LF/Trim-Contract-Festigung).
- `DiffPlanner.detectConstraintNotDiffableTables` lockern:
  Tabellen mit CHECK-/EXCLUDE-Aenderungen werden nur noch blockiert,
  wenn die Aenderung nicht durch Sub-Slice B/C/D gerendert werden
  kann.
- Konkrete Cross-Table-Check-Abbruchregel ergänzen: Wenn eine
  CHECK-Expression relationale Subquerys enthält, gilt eine
  konservative Heuristik als „unsupported“. Blockiere vor
  Mapper/Renderer mit `CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED`, wenn:
  - der normalisierte Ausdruck `select`-Tokens enthält (z. B. in
    `EXISTS (SELECT ...)` oder `IN (SELECT ...)`),
  - `select` im Kontext von Klammern erscheint,
  - oder der allgemeine Fallback für potenziell relationale Muster
    triggert.
  Damit ist die Regel bewusst eher strikt (mehr false positives, aber
  keine false negatives bei Subquerys im reinen Text).
- `OperationMapper` iteriert auch ueber CHECK/EXCLUDE-Constraints
  (Skip entfernt).
- `RenameIntraObjectDeltaSynthesizer`-Skips lockern (analog).
- `PlannerBlockerClassifier` um alle F.5-Codes (inklusive
  `MYSQL_CHECK_ENFORCEMENT_UNKNOWN` und
  `EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED` sowie
  `CHECK_PREFLIGHT_RUNTIME_ERROR`) ergaenzen.
- Tests: Constraint-Comparison-Pin (insbesondere
  LF/Trim-Konstanz), Mapper emittiert `AddConstraint`/
  `DropConstraint` fuer CHECK/EXCLUDE-Diffs.

### Sub-Slice B — PostgreSQL Renderer ✅ (2026-05-19)

- `PostgresDiffSqlBuilders.constraintLine` erweitert um native
  Branches fuer `CHECK` (`CONSTRAINT n CHECK (expr)`) und
  `EXCLUDE` (`CONSTRAINT n EXCLUDE USING gist (expr)`); blanke
  Expression bleibt nicht-renderable und faellt auf den bestehenden
  `DIALECT_UNSUPPORTED_OPERATION`-Skip-Pfad in
  `PostgresDiffOtherOps.renderAddConstraint` /
  `renderDropConstraint` zurueck.
- `WHERE (…)`-Klausel + Custom-Operator-Klassen bleiben pro §3.2
  out-of-scope der Erstscheibe; ein Operator, der eine eigene
  Klasse braucht, kann sie in `expression` inline kodieren
  (Reversibility wird in Sub-Slice F gepinnt).
- Tests: `PostgresDiffDdlGeneratorCheckExcludeTest` (eigene
  Datei wegen Detekt-LargeClass) pinnt Add/Drop/Up/Down fuer
  CHECK + EXCLUDE, Inline-Variante in CreateTable, sowie den
  Blank-Expression-Fallback auf den `DIALECT_UNSUPPORTED_OPERATION`-
  Blocker. Carve-outs fuer Cross-Table-Referenzen sind bereits durch
  Sub-Slice A's Planner-Pfad abgedeckt (siehe
  `§F.5 Sub-Slice A: cross-table CHECK planner-blocker cascades`
  in `PostgresDiffDdlGeneratorTest`).

### Sub-Slice C — MySQL Renderer + Enforcement-Capability ✅ (2026-05-19)

- `MysqlCheckEnforcementCapability` + `MysqlCheckEnforcementResolver`
  liegen in `hexagon:ports-read`. Floors sind `MySQL 8.0.16` und
  `MariaDB 10.2.1`; `null`-Version (file-only oder unverfuegbar)
  faellt auf `known=false, enforced=false` mit
  Rationale-Begruendung. Auswertung via `Comparable<MysqlServerVersion>`,
  Vendor-Routing per `isMariaDb`.
- `MysqlDiffSqlBuilders`:
  - `constraintLine` rendert CHECK als `CONSTRAINT n CHECK (expr)`
    (blanke Expression bleibt null → bestehender Skip-Pfad), EXCLUDE
    bleibt null.
  - `dropConstraintSql` rendert CHECK als
    `ALTER TABLE t DROP CHECK n;` (EXCLUDE bleibt null).
- `MysqlDiffOtherOps`:
  - `renderAddConstraint` + `renderDropConstraint` extrahieren die
    bisher duplizierte Up/Down-Emission in `emitAddConstraint` /
    `emitDropConstraint`-Helper. Davor laeuft fuer `EXCLUDE` der
    unconditional `blockExcludeOnMysql` (Code:
    `EXCLUDE_NOT_SUPPORTED_BY_DIALECT`, Reason:
    `DIALECT_UNSUPPORTED_OPERATION`); fuer `CHECK` greift
    `gateMysqlCheck(isLogicalAdd)`:
    - `!cap.known` → `MYSQL_CHECK_ENFORCEMENT_UNKNOWN`,
      `MANUAL_ACTION_REQUIRED` (gilt fuer ADD und DROP).
    - `isLogicalAdd && !cap.enforced` →
      `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16`,
      `MANUAL_ACTION_REQUIRED`. Drop ohne Enforcement laeuft durch
      (keine Schutzwirkung erforderlich).
- Tests:
  - `MysqlCheckEnforcementResolverTest` (ports-read) pinnt alle vier
    Quadranten (MySQL ≥/< 8.0.16, MariaDB ≥/< 10.2.1) plus null und
    den Vendor-Routing-Edge-Case (10.2.1 ohne MariaDB-Vendor laeuft
    als plain MySQL ≥ 8.0.16).
  - `MysqlDiffDdlGeneratorCheckExcludeTest` (driver-mysql, eigene
    Datei wegen Detekt-LargeClass) pinnt:
    CHECK-ADD positiv auf MySQL 8.0.16 + MariaDB 10.2.1;
    CHECK-ADD blockt pre-Version (beide Vendor) + null;
    CHECK-DROP rendert `DROP CHECK n` und blockt nur bei `!known`;
    CHECK-ADD-Up + Down-Pfade pinnen das Round-Trip;
    EXCLUDE-ADD blockt auf jeder Server-Version inkl. null.

### Sub-Slice D — SQLite Renderer (Rebuild-Pipeline) ✅ (2026-05-19)

- `SqliteRebuildPlanner.classify` braucht keine Erweiterung:
  `Add/DropConstraint` waren bereits sowohl Rebuild-Trigger als
  auch durch den Rebuild absorbiert. CHECK-Diffs fliessen damit
  automatisch durch die rebuild-Pipeline.
- `SqliteDiffSqlBuilders.constraintLine` rendert CHECK inline als
  `CONSTRAINT n CHECK (expr)` — der bestehende Rebuild-Renderer
  iteriert `target.constraints` und nimmt das CHECK-Line so ohne
  weitere Anpassung mit. EXCLUDE bleibt `null` (kein SQLite-
  Pendant).
- `SqliteDiffDdlGenerator.renderRebuildBucket` blockt eine
  Rebuild-Bucket konservativ, wenn (a) ein `Add/DropConstraint`-Op
  vom Typ `EXCLUDE` vorliegt ODER (b) die `current`- oder
  `desired`-Tabelle bereits ein `EXCLUDE` traegt (sonst wuerde der
  Rebuild es stillschweigend droppen). Code:
  `EXCLUDE_NOT_SUPPORTED_BY_DIALECT`, Reason:
  `DIALECT_UNSUPPORTED_OPERATION`; keine SQL emittiert.
- Tests (`SqliteDiffDdlGeneratorCheckExcludeTest`, eigene Datei wegen
  Detekt-LargeClass) pinnen:
  - CHECK-Add → CREATE temp enthaelt CHECK-Klausel inline.
  - CHECK-Drop → CREATE temp ohne CHECK-Klausel.
  - CHECK-Replace (gleicher Name, neue Expression) → nur die neue
    Expression im CREATE temp.
  - Column-Reshape auf Tabelle MIT CHECK → CHECK ueberlebt den
    Rebuild.
  - Down-Pfad (AddConstraint reversed) → CREATE temp ohne CHECK.
  - EXCLUDE-Add / -Drop → keine SQL, Blocker
    `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` + `DIALECT_UNSUPPORTED_OPERATION`.
  - Schema-Level-EXCLUDE plus unrelated Column-Reshape → ebenfalls
    geblockt, Diagnose nennt den EXCLUDE-Namen.

### Sub-Slice E — Daten-Preflight (Cross-Dialect)

Geschnitten in zwei Stufen:

#### E.1 + E.2 — Ports + dialektneutraler Planner ✅ (2026-05-19)

- `CheckPreflightDeclaration` (Datenklasse mit
  `operationId`/`dialect`/`table`/`constraintName`/`expression`/
  `status`/`sqlHash`/`totalRows`/`failingRows`/`sampleRowIds`/`problem`)
  plus `CheckPreflightStatus`-Enum
  (`PASSED`/`FAILED`/`NOT_RUN_FILE_TARGET`/`NOT_RUN_POLICY`/
  `PROBE_RUNTIME_ERROR`) in `hexagon:ports-read`. Binding-Key via
  `` als Separator (kollidiert nicht mit SQL-Identifier-
  Zeichen). Struktur spiegelt `SqliteCastPreflightDeclaration`.
- `DdlGenerationOptions.checkPreflights: List<CheckPreflightDeclaration>`
  als neues Feld (default `emptyList()`). Renderer der drei Dialekte
  konsumieren es ab Sub-Slice E.4.
- `CheckPreflightPlanner` (in `hexagon:core`,
  `dev.dmigrate.core.diff.migration`) konvertiert jede
  `AddConstraint(CHECK)`-Op eines `DiffResult` in eine
  `PlannedCheckPreflight`-Vorlage; baut die Probe-SQL
  (`SELECT count(*) FROM <table> WHERE NOT (<expression>)`) via
  injizierten `identifierQuoter`, hashed sie deterministisch via
  `sha256Hex`. Skips:
  - `DropConstraint(CHECK)` — Drop verletzt nie bestehende Daten.
  - `AddConstraint(UNIQUE/FOREIGN_KEY/EXCLUDE)` — andere Gates.
  - Blank-Expression — Renderer routet sowieso auf
    `DIALECT_UNSUPPORTED_OPERATION`.
  - Application-Layer-Status-Mapping liegt bewusst beim
    Pipeline-Wiring (Sub-Slice E.5), damit `hexagon:core` frei von
    `ports-read` bleibt — `InitialStatus`-Enum hier ist auf
    `NOT_RUN_FILE_TARGET` und `NOT_RUN_POLICY` reduziert.
- Tests: `CheckPreflightDeclarationTest` (ports-read) pinnt
  Binding-Key-Determinismus + Status-Range + Default-Wert auf
  `DdlGenerationOptions`. `CheckPreflightPlannerTest` (core) pinnt
  Op-Selektion (Skips fuer Drop/UNIQUE/FK/EXCLUDE/blank-Expression),
  Probe-SQL-Format, Hash-Determinismus und
  `initialStatus`-Durchreichung.

#### E.3 — Renderer-Gates pro Dialekt ✅ (2026-05-19)

- Geteilte Entscheidungslogik in `hexagon:ports-read`
  (`CheckPreflightGate`): `decide(operationId, declarations)` liefert
  `Decision.Proceed` oder `Decision.Block(code, reason, message)`.
  Operator-lesbare Nachrichten enthalten Tabelle, Constraint-Name,
  Expression sowie optional `Failing rows:`, `Total rows:`,
  `Sample row ids:` (FAILED) bzw. Probe-Problem-Text
  (PROBE_RUNTIME_ERROR). Status-Routing:
  - `PASSED` / `NOT_RUN_FILE_TARGET` / `NOT_RUN_POLICY` /
    keine Declaration → `Proceed`.
  - `FAILED` → Block (`CHECK_PREFLIGHT_VIOLATIONS`,
    `MANUAL_ACTION_REQUIRED`).
  - `PROBE_RUNTIME_ERROR` → Block (`CHECK_PREFLIGHT_RUNTIME_ERROR`,
    `MANUAL_ACTION_REQUIRED`).
- `PostgresDiffOtherOps.renderAddConstraint` konsultiert das Gate
  ausschliesslich auf der UP-Direction (Down = Drop, kein
  Preflight). Match per `operationId`.
- `MysqlDiffOtherOps.renderAddConstraint` konsultiert das Gate
  nachdem `gateMysqlCheck` Erfolg hatte und nur fuer `isLogicalAdd`.
  Reihenfolge ist bewusst: Capability-Gate zuerst, damit Operator
  nie Preflight-Block sieht, den er ohne Server-Upgrade nicht
  aufheben koennte.
- `SqliteDiffDdlGenerator.renderRebuildBucket` blockt die gesamte
  Bucket, sobald irgendein `AddConstraint(CHECK)`-Op in der Bucket
  eine `Block`-Decision liefert. Pro Op wird der spezifischste
  Diagnose-Text emittiert (Fallback: erster Bucket-Block-Hit fuer
  nicht-CHECK Ops), Blocker traegt alle Bucket-Op-IDs. Mirror zum
  EXCLUDE-Block-Pfad.
- Tests:
  - `CheckPreflightGateTest` (ports-read) pinnt alle Status-Branches
    inklusive Multi-Match-Determinismus.
  - `PostgresDiffCheckPreflightGateTest`,
    `MysqlDiffCheckPreflightGateTest`,
    `SqliteDiffCheckPreflightGateTest` pinnen pro Dialekt: PASSED,
    NOT_RUN_FILE_TARGET, NOT_RUN_POLICY, fehlende Declaration,
    FAILED, PROBE_RUNTIME_ERROR, Down-Direction ignoriert Preflight.
    MySQL pinnt zusaetzlich: Capability-Block gewinnt gegen
    Preflight-Block (Pre-Version + Unknown-Version).

#### E.4 — Per-Dialekt-Probes + Pipeline-Wiring + Report + CLI ✅ (2026-05-19)

- Drei Driver-Probes:
  `PostgresCheckPreflightProbe` (driver-postgresql),
  `MysqlCheckPreflightProbe` (driver-mysql),
  `SqliteCheckPreflightProbe` (driver-sqlite). Alle teilen das
  Muster aus `SqliteCastPreflightProbe`: `probe(conn, diff)` plant
  via `CheckPreflightPlanner` (hexagon:core), iteriert pro
  geplanter Op, fuehrt die count-Query
  (`SELECT count(*) FROM <t> WHERE NOT (<expr>)`) aus und liefert
  eine `CheckPreflightDeclaration` (`PASSED`/`FAILED`). `SQLException`
  fangen die Probes lokal ab und liefern
  `PROBE_RUNTIME_ERROR` mit `problem` = Fehlertext.
  Tests: pro Dialekt eigene Test-Datei (Postgres + MySQL mit
  mockk-`Connection`/`Statement`-Stubs; SQLite mit in-memory
  DriverManager-Connection); decken `empty / PASSED / FAILED /
  PROBE_RUNTIME_ERROR / quoting-shape / sqlHash-Format` ab.
- `MigrationDdlResult.checkPreflights: List<CheckPreflightDeclaration>`
  neu in `hexagon:ports-read`, sodass Renderer-Output und Report
  die Live-Probe-Outcomes mitfuehren koennen.
- `MigrationPreflightPlanner.plan` ergaenzt um den CHECK-Teil:
  fuer JEDEN Dialekt erzeugt der Planner per
  `CheckPreflightPlanner` (core) eine
  `NOT_RUN_FILE_TARGET` / `NOT_RUN_POLICY`-Vorlage pro Add-CHECK-Op,
  damit der Report auch ohne live-Probe immer sichtbar macht, was
  geprueft worden waere. Cast-Teil bleibt SQLite-only.
- Neues `CheckPreflightStage`-Object (parallel zu
  `SqliteCastPreflightStage`) mit `Outcome.Succeeded /
  Failed / NotRun` plus `buildFailureResult`. Stage laeuft nur,
  wenn `request.execute && target is Database && probe != null &&
  preflightPlan.checkPreflights.isNotEmpty()`. Probe-Exception
  wandelt die `NOT_RUN_*`-Vorlagen in
  `PROBE_RUNTIME_ERROR`-Eintraege mit `problem`-Text um.
- `SchemaMigrateRenderPipeline`:
  - `runPreflightPlan` ersetzt `runCastPreflightPlan` (deckt jetzt
    Cast + Check ab).
  - `runCheckPreflight` ruft die neue Stage auf.
  - `buildRenderOptions` schreibt das resolvierte
    `checkDeclarations`-Set in
    `DdlGenerationOptions.checkPreflights`. Quelle:
    `Outcome.Succeeded.declarations` oder
    `preflightPlan.checkPreflights` (`NotRun`/`Failed`).
  - `renderUp` short-circuit fuer
    `CheckPreflightStage.Outcome.Failed` mit
    `CheckPreflightStage.buildFailureResult`. Im Erfolgspfad faellt
    `rendered.checkPreflights` ggf. auf `renderOptions.checkPreflights`
    zurueck, damit das File-only-Rendering die NOT_RUN_*-Eintraege
    im Report behaelt.
  - `mergeDownIntoUp` dedupliziert `checkPreflights` per
    `bindingKey` (Up gewinnt, Down ergaenzt fehlende Eintraege).
  - Die drei Preflight-Outcomes (`probe`, `cast`, `check`) werden
    in einem privaten `PreflightOutcomes`-Datenklasse-Carrier
    gebuendelt, damit `buildRenderOptions` / `renderUp` unter
    Detekt's 8-Parameter-Limit bleiben.
- `SchemaMigrateRunner` akzeptiert
  `checkPreflightProbe: CheckPreflightProbeFn? = null` als optionalen
  Constructor-Param und reicht ihn an die Pipeline durch.
- Report:
  - Neuer `SchemaMigrateCheckPreflightView` (`operationId`,
    `dialect`, `table`, `constraintName`, `expression`, `status`,
    `sqlHash`, `totalRows`, `failingRows`, `sampleRowIds`,
    `problem`).
  - `SchemaMigrateReport.checkPreflights: List<…>` ergaenzt; null-
    sicher mit `emptyList()` als Default.
  - `SchemaMigrateReportBuilder.buildCheckPreflightViews` projiziert
    `MigrationDdlResult.checkPreflights` 1:1.
- CLI-Wiring (`adapters/driving/cli`):
  - `CheckPreflightProbeRunner.probe` resolved per
    `NamedConnectionResolver` + `ConnectionUrlParser` +
    `HikariConnectionPoolFactory` die Connection, dispatched auf
    den Dialekt-Probe via `when (dialect)` und gibt
    `emptyList()` fuer nicht-unterstuetzte Dialekte zurueck.
  - `SchemaMigrateCommand` reicht
    `CheckPreflightProbeRunner::probe` an den `SchemaMigrateRunner`
    durch.
- Tests: `CheckPreflightStageTest` (hexagon:application) pinnt
  alle drei `MigrationPreflightPlanner`-CHECK-Pfade (DB/exec,
  Datei, no-op) plus die Stage-Outcomes (NotRun-Varianten,
  Succeeded, Failed mit Problem-Text) und
  `buildFailureResult`-Form. Renderer-Gates haben ihre eigene
  Coverage aus E.3.

### Sub-Slice F — Reversibility + Replace-Vertrag *(offen, naechster Schritt)*

- `ConstraintReplaceContract` modelliert
  `DropConstraint(old) + AddConstraint(new)` mit synchroner
  Reversibility.
- Tests: Replace-Up emittiert beide Ops; Replace-Down inverse;
  Replace mit unbekannter alter Expression blockt mit
  `ROLLBACK_NOT_POSSIBLE`.

### Sub-Slice G — Closing *(offen, nach Sub-Slice F)*

- §F.5-DoD-Box im master plan abhaken.
- §11 DoD Box (a/b/c) Eintraege fuer die neuen Workstream-Slices
  (CHECK / EXCLUDE) erweitern.
- CHANGELOG-Eintrag `### Added`.
- `spec/cli-spec.md` §6.1 dokumentiert
  die F.5-spezifischen Blocker-Codes (`Planergebnisse von Daten-Preflight,
  MySQL-Enforcement, EXCLUDE-Operator-Klassen).
- Roadmap §F.5-Rest auf erledigt.
- Plan-Doc nach `done/`.

---

## 7. Akzeptanzkriterien

Stand 2026-05-19: A–E fertig, F + G offen.

- [x] PostgreSQL kann CHECK Add/Drop/Replace ueber `ALTER TABLE`
      rendern (Up + Down). *(Sub-Slice B,
      `PostgresDiffDdlGeneratorCheckExcludeTest`.)*
- [x] PostgreSQL kann EXCLUDE Add/Drop ueber `ALTER TABLE … ADD
      CONSTRAINT … EXCLUDE USING gist (…)` rendern. *(Sub-Slice B.)*
- [x] MySQL ≥ 8.0.16 und MariaDB ≥ 10.2.1 koennen CHECK Add/Drop/
      Replace rendern (Enforcement aktiv per Capability).
      *(Sub-Slice C, `MysqlDiffDdlGeneratorCheckExcludeTest`.)*
- [ ] MySQL ≥ 8.0.16 und MariaDB ≥ 10.2.1 testen explizit alle drei Wege
      (Add/Drop/Replace) im Up- und Down-Pfad als positiv.
      *(Add + Drop pinned; Replace haengt an Sub-Slice F's
      ConstraintReplaceContract.)*
- [x] MySQL < 8.0.16 blockt CHECK-Aenderungen mit
      `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` →
      `MANUAL_ACTION_REQUIRED`. *(Sub-Slice C.)*
- [x] MySQL/MariaDB mit unbekannter Versionsdetektion blockt CHECK-ADD/REPLACE-Operationen
      mit `MYSQL_CHECK_ENFORCEMENT_UNKNOWN` → `MANUAL_ACTION_REQUIRED`.
      *(Sub-Slice C.)*
- [x] MySQL und SQLite blocken EXCLUDE mit
      `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` →
      `DIALECT_UNSUPPORTED_OPERATION`. *(Sub-Slices C + D.)*
- [x] SQLite kann CHECK Add/Drop/Replace ueber den
      Rebuild-Pipeline-Pfad ausfuehren (kein eigenes
      `ALTER TABLE ADD CONSTRAINT` noetig). *(Sub-Slice D,
      `SqliteDiffDdlGeneratorCheckExcludeTest`.)*
- [x] Daten-Preflight im `--execute`-Modus blockt eine neue
      restriktive CHECK, die existierende Zeilen verletzt, mit
      `CHECK_PREFLIGHT_VIOLATIONS` → `MANUAL_ACTION_REQUIRED`.
      *(Sub-Slice E.3 + E.4,
      `*DiffCheckPreflightGateTest` pro Dialekt +
      adapter-`*CheckPreflightProbeTest`.)*
- [x] Daten-Preflight im `--execute`-Modus blockt technische Probe-Fehler
      sauber mit `CHECK_PREFLIGHT_RUNTIME_ERROR` → `MANUAL_ACTION_REQUIRED`
      inkl. Fehlertext im Report. *(Sub-Slice E.3 + E.4.)*
- [x] EXCLUDE mit nicht unterstützten/benutzerdefinierten
      Operator-Klassen blockt mit
      `EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED` → `MANUAL_ACTION_REQUIRED`.
      *(Sub-Slice F: konservative Whitelist in `ExcludeOperatorClassGate`
      — bare/quoted identifier oder parenthesisierter Ausdruck vor `WITH`;
      Reject-Pfad in AddConstraint UP, DropConstraint DOWN und inline
      CreateTable.)*
- [x] Datei-zu-Datei-Modus: neue restriktive CHECK bleibt
      `MANUAL_ACTION_REQUIRED` mit Verweis auf `--execute`.
      *(Sub-Slice E.4: `NOT_RUN_FILE_TARGET`-Declarations werden vom
      Planner emittiert; Renderer-Gate laeuft durch ohne Block, Report
      surfaces den Status.)*
- [x] `ConstraintDiffContract.canonicalRawSqlExpression()` ist
      dokumentiert und gepinnt — nur durch `\r\n`/`\r` auf `\n`
      normalisiert und `trim()`-entfernte Expressions gelten als
      gleich; semantisch unterschiedliche Expressions bleiben
      unterschiedlich. *(Erstscheibe + Sub-Slice A.)*
- [x] `canonicalRawSqlExpression()` ist exakt auf LF-Normalisierung + `trim()`
      begrenzt; Klammer-/Whitespace-Kompaktnormalisierung ist als
      Open-Item für spätere Folge-Slices dokumentiert.
      *(Erstscheibe + Sub-Slice A.)*
- [ ] Reversibility: CHECK-Add Down = CHECK-Drop;
      CHECK-Replace Down = inverse Replace mit alter Expression;
      bei unbekannter alter Expression blockt
      `ROLLBACK_NOT_POSSIBLE`. *(Sub-Slice F-Scope.)*
- [x] `PlannerBlockerClassifier` enthaelt die sieben neuen Codes mit
      passenden `MigrationBlockedReason`-Werten.
      *(Sub-Slice A, `PlannerBlockerClassifierTest`.)*
- [ ] Pro Dialekt: Positiv-, Blocker- und (wo anwendbar)
      Rollback-Test fuer CHECK und EXCLUDE.
      *(Positiv + Blocker fertig; Rollback haengt an Sub-Slice F.)*
- [x] `make docker-check` gruen ueber alle betroffenen Module.
      *(Stand 2026-05-19 nach E.4 + Review-Cleanups.)*
- [ ] §F.5-DoD-Box im master plan abgehakt, Roadmap §F.5-Rest auf
      erledigt. *(Sub-Slice G-Scope.)*

---

## 8. Definition of Done (§13-Template)

Stand 2026-05-19: alle Boxes ausser Replace-Vertrag (Sub-Slice F) und
Roadmap-Closing (Sub-Slice G) abgehakt.

- [x] **Betroffener Modus**: alle Modi
  (file-to-file, file-to-DB, execute, rollback). Daten-Preflight
  ist execute-only; file-to-file emittiert `NOT_RUN_FILE_TARGET`-
  Declarations und laesst das Rendern durchlaufen mit Report-
  Hinweis. *(Sub-Slices A–E.)*
- [x] **Renderbare Operationen + Blocker**: neu renderbar
  sind CHECK Add/Drop (PG/MySQL/SQLite) und EXCLUDE
  Add/Drop (PG only). Blocker bleibt MySQL <
  8.0.16, EXCLUDE auf MySQL/SQLite, FAILED-Preflight (execute mode),
  Cross-Table-CHECK-Referenzen. *Replace bleibt Sub-Slice F-Scope.*
- [x] **Neue Diagnostics / Blocker / primaryBlockedReason**: sieben
  neue Codes (`CHECK_PREFLIGHT_VIOLATIONS`,
  `EXCLUDE_NOT_SUPPORTED_BY_DIALECT`,
  `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16`,
  `MYSQL_CHECK_ENFORCEMENT_UNKNOWN`,
  `CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED`,
  `EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED`, `CHECK_PREFLIGHT_RUNTIME_ERROR`) mit Mapping in
  `PlannerBlockerClassifier`. Keine neuen `MigrationBlockedReason`-
  Enum-Werte noetig — die existierenden Reasons
  (`MANUAL_ACTION_REQUIRED`, `DIALECT_UNSUPPORTED_OPERATION`,
  `ROLLBACK_NOT_POSSIBLE`) decken den Vertrag ab. *(Sub-Slice A.)*
- [ ] **Up- und Down-Verhalten**: getrennte Akzeptanzkriterien,
  Replace ist Drop+Add mit gemeinsamer Op-ID.
  *(Up + Drop-Down fertig; Replace haengt an Sub-Slice F.)*
- [x] **Report-/Metadatenfelder**: neue Operationen erscheinen mit
  `objectType = "CONSTRAINT"` und kind
  `AddConstraint`/`DropConstraint`; `migration-plan.v1`-Artefakt
  fuehrt sie ohne Vertragsaenderung. Zusaetzlich neuer
  `SchemaMigrateCheckPreflightView`/-Feld am Report sowie
  `MigrationDdlResult.checkPreflights` (Sub-Slice E.4).
- [x] **Betroffene Dialekte**: PostgreSQL, MySQL, SQLite — alle
  drei mit eigenem Render-Pfad bzw. blockierendem Carve-out.
  *(Sub-Slices B/C/D + E.3.)*
- [x] **F.0-Erfuellung**: irrelevant — kein neuer Overlay-Input.
- [ ] **Positive und blockierende Testpfade**: siehe §7. *(Alle
  Punkte ausser Replace + Operator-Klassen-Whitelist + Rollback
  abgehakt.)*
- [ ] **Rollback-Test oder Begruendung**: CHECK Add Down = Drop
  (positiv); CHECK Replace mit unbekanntem alten Body =
  `ROLLBACK_NOT_POSSIBLE` (Blocker); EXCLUDE Down analog.
  *(Sub-Slice F-Scope.)*
- [x] **Datei-zu-Datei-Verhalten**: Probe nicht erreichbar →
      Renderer erhaelt `NOT_RUN_FILE_TARGET`-Declarations; Render
      laeuft, Report dokumentiert Status. *(Sub-Slice E.4.)*
- [x] **MySQL-Detection**: Eine fehlende/unklare
      `mysqlServerVersion` wird nur über
      `MYSQL_CHECK_ENFORCEMENT_UNKNOWN` abgebildet und nicht still
      in einen impliziten Fallback-Block gematched. *(Sub-Slice C,
      `MysqlCheckEnforcementResolverTest`.)*
- [x] **Bestehende 0.9.7-Vertraege unveraendert**: alle bestehenden
  `AddConstraint`/`DropConstraint`-Tests fuer
  UNIQUE/FOREIGN_KEY bleiben gruen. Der konservative Textvergleich
  aus der Erstscheibe bleibt fuer Tabellen, die nicht ueber den
  neuen Pfad renderbar sind. *(Whole-tree `make docker-check` gruen
  2026-05-19.)*
- [x] **Slice kann unabhaengig implementiert und verifiziert
  werden**: Sub-Slices A–G sequenziell; A ist Voraussetzung fuer
  alle anderen. *(A–E sequentiell abgeschlossen; F + G stehen aus.)*

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
  `USING gist (col WITH custom_op)` braucht, wird mit
  `EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED` blockiert; spaetere Tranche kann
  Operator-Klassen-Whitelist mit detaillierter Prüfung einfuehren.

- **MySQL `CHECK` `NOT ENFORCED`-Override**: heute strikter
  Block ohne 8.0.16+. Operator-Override via
  `--allow-check-not-enforced` ist in dieser Vollscheibe nicht enthalten;
  spaetere Tranche kann den Override als opt-in Carve-out mit
  klaren Bedingungen einfuehren.

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
kein default `skip`-Ausweg vorgesehen; Risiko wird im Report klar
und inhaltlich mit Verweis auf den auszuführenden Execute-Scan
kommuniziert.

### 10.3 MySQL Enforcement-Detection

Wenn `mysqlServerVersion` nicht gelesen werden kann
(z.B. Privilege-Issue beim Reader), blockt die Migration jetzt
explizit mit `MYSQL_CHECK_ENFORCEMENT_UNKNOWN` (statt implizitem
Default). Mitigation: bestehender E.1-Slice-C.2-Vertrag fuer
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

| Sub-Slice | Status | Commit(s) |
|---|---|---|
| A | ✅ | `2c784d8b feat(check): F.5 Sub-Slice A — foundation …` |
| B | ✅ | `d60939c5 feat(check): F.5 Sub-Slice B — PostgreSQL …` |
| C | ✅ | `99c9528c feat(check): F.5 Sub-Slice C — MySQL CHECK + EXCLUDE …` |
| D | ✅ | `fe0cc35e feat(check): F.5 Sub-Slice D — SQLite CHECK via rebuild + EXCLUDE block` |
| E.1+E.2 | ✅ | `cde9d39f feat(check): F.5 Sub-Slice E.1 + E.2 — preflight foundation + dialect-neutral planner` |
| E.3 | ✅ | `8a47a640 feat(check): F.5 Sub-Slice E.3 — per-dialect renderer gates for checkPreflights` |
| E.4 | ✅ | `fc02d621 feat(check): F.5 Sub-Slice E.4 — probes + stage + pipeline + report + CLI` + `a2afe0c9 chore(check): F.5 E.4 review follow-ups` |
| F | offen | `feat(check): reversibility + replace contract` |
| G | offen | `docs(plan): F.5 CHECK/EXCLUDE Vollscheibe closing` |

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
