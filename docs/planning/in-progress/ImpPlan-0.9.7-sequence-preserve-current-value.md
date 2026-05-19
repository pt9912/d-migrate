# Implementierungsplan: 0.9.7 — Sequence preserveCurrentValue-Policy

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.3 Cross-Dialect Folge-Slice
> **Status**: open 2026-05-19.
> **Vorbedingung**: E.3 Erstscheibe (PG-Sequence-Diff-Renderer) ✅;
>                  PG-`SequenceDefinition` ✅;
>                  Live-DB-Reader-Pfade pro Dialekt ✅;
>                  F.4 Renderer-Blocker-Bridge ✅ 2026-05-19;
>                  MySQL/SQLite-Sequence-Diff-Migration *(parallele Plans)*.
> **Referenz**: `diffresult-migration-plan-2.md` §E.3 (heutige
>             Sequence-Erstscheibe pinnt ausdruecklich: „aktueller
>             Wert wird NICHT migriert"); `ImpPlan-0.9.7-mysql-sequence-diff-migration.md`;
>             `open/sqlite-sequence-emulation-plan.md`.

---

## 1. Auslöser

Die heutige Sequence-Migration (PG E.3 Erstscheibe, MySQL Emulation
in `done/mysql-sequence-emulation-plan.md`, SQLite-Plan in
`open/`) deckt nur die **deklarativen Attribute** ab —
`start`, `increment`, `minValue`, `maxValue`, `cycle`, `cache`.
Den **aktuellen Wert** (`last_value` in PG, `next_value` in
MySQL-Emulation als *nächster von `nextval()`-Rückgabewert*) migriert
keine Pipeline. Effekt:

- Operator migriert eine bestehende Tabelle mit `id` aus einer
  PG-Sequenz nach DB-Target. Die Sequenz wird mit
  `CREATE SEQUENCE … START WITH 1` neu angelegt. Bei einem
  spaeteren `INSERT` springt `nextval('seq')` auf 1 statt auf den
  vorherigen Wert + 1 → **PK-Konflikt** mit existierenden Zeilen,
  Migration scheitert beim ersten Schreibzugriff.

Das ist der Hauptgrund, warum d-migrate fuer
sequence-tragende Schemata in Produktion heute nicht
ohne manuelle Nachbearbeitung benutzt werden kann. Operatoren
muessen nach jeder Migration manuell `ALTER SEQUENCE … RESTART WITH
<observed-max>` ausfuehren.

---

## 2. Warum jetzt?

`preserveCurrentValue` ist die kritische Live-DB-Bruecke, die
Sequence-Migrationen ueberhaupt erst produktionstauglich macht.
Der DDL-Generator-Pfad (`schema generate`) deckt Schema-Strukturen
ab, aber Migrationen brauchen **Daten-Bewusstsein** fuer Sequences
genauso wie Cast-Preflight fuer Spalten-Typaenderungen
(B.2-Pattern). Ohne diesen Slice ist der MySQL-Sequence-Diff-
Slice (parallel) und der SQLite-Sequence-Plan halbgar in
Production.

Roadmap §E Rest listet explizit:
> Aktueller Sequence-Wert / Preserve-Policy

---

## 3. Scope

### 3.1 In-Scope

- Neues optionales Feld `preserveCurrentValue: Boolean = false`
  auf `SequenceDefinition` als primäre Steuerung.
- Kein neuer globaler CLI-Flag in dieser Tranche; `preserveCurrentValue`
  wird vollständig über das Schema gesteuert.
- Neuer `SequenceCurrentValueProbe`-Port in `hexagon:ports-read`
  mit dialect-spezifischer Implementierung:
  - **PG**: `SELECT last_value, is_called FROM <sequence_name>`
  - **MySQL** (Emulation): `SELECT next_value FROM dmg_sequences
    WHERE name = <sequence_name>` (`next_value` ist der nächste von
    `nextval` gelieferte Wert).
  - **SQLite**: TBD pro `sqlite-sequence-emulation-plan.md`;
    blockt mit `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` bis
    der SQLite-Plan landet.
- Neue Operation-Subtype: `AlterSequenceCurrentValue(name, value, isCalled: Boolean?, restoreValue: Long?)`
  (kein Reuse von `AlterSequence`; Daten-statement-Renderer ist separat).
- Renderer-Pfade pro Dialekt:
  - **PG**: `SELECT setval('<sequence_name>', <value>, <is_called>)`
    als DDL-Equivalent, wobei `<is_called>` vom Probe-Ergebnis
    übernommen wird.
  - **MySQL**: `UPDATE dmg_sequences SET next_value = <value>
    WHERE name = <sequence_name>` (kein +1).
  - **SQLite**: erst sobald der SQLite-Plan landet.
- Pipeline-Integration in `SchemaMigrateRunner`:
  - Wenn `preserveCurrentValue = true` UND DB-Target verfuegbar:
    Probe vor Render; emittiere `AlterSequenceCurrentValue` mit
    dem geprobten Wert, inklusive `isCalled` (falls vom Dialekt
    geliefert) sowie optionalem `restoreValue`, sofern diese Planerzeit
    bestimmen kann.
    Für PG ist `isCalled` zwingend (kein Default), für andere
    Dialekte wird `isCalled` nicht verwendet.
  - Datei-zu-Datei-Modus: `preserveCurrentValue = true` blockt
    mit `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET` →
    `MANUAL_ACTION_REQUIRED`.
- F.5-Carve-out-Pattern wiederverwendet (Probe → Pipeline →
  Blocker-bei-Failure).
- Kein CLI-Override in dieser Tranche. Die Tranche arbeitet nur mit
  `SequenceDefinition.preserveCurrentValue` (deterministische Steuerung).
- Reversibility: `AlterSequenceCurrentValue` Down setzt den
  Wert auf den im Plan enthaltenen `restoreValue` zurück, sofern
  bekannt.
  Wenn kein stabiler Restore-Wert vorliegt (z. B. fehlender
  pre-existing Sequence-Snapshot oder konkurrierende `nextval`-Calls),
  resultiert Down in `ROLLBACK_NOT_POSSIBLE`.
  Diese `restoreValue` wird als `restoreValueHint` auf der Follow-up-Operation
  geführt. Der Planer setzt den Hint deterministisch, wenn der
  Ausgangswert aus einem pre-existing target-snapshot ableitbar ist.
  Bei neu angelegten Zielen bleibt der Hint `null` und Down wird als
  `ROLLBACK_NOT_POSSIBLE` markiert.

### 3.2 Out-of-Scope

- **Atomare Konsistenz** zwischen `nextval`-Calls in der App
  und der Migration: wenn die App parallel waehrend des
  Preflights Sequenzen-Calls macht, ist der geprobte Wert
  veraltet. Mitigation: Probe + `setval` in derselben
  Transaktion / unter Tabellen-Lock — separater Slice.
- **Sequence-Ownership-Inferenz** (wer „besitzt" eine Sequenz —
  PG `OWNED BY` table.column): bleibt im DDL-Generator-Pfad,
  diff-basiert ausgeklammert.
- **Multi-Sequence-Atomarity**: wenn ein Plan mehrere Sequenzen
  preserved, sind die Probe-Punkte zeitlich auseinander; ein
  Operator, der zwischen den Probes Inserts macht, kann
  Inkonsistenzen erzeugen. Carve-out documented.

---

## 4. Vorbedingungen

| Vorbedingung | Status |
| ------------ | ------ |
| `SequenceDefinition` neutrales Modell | ✅ |
| PG-Sequence-Diff-Renderer | ✅ E.3 Erstscheibe |
| MySQL-Sequence-Emulation (DDL-Generator) | ✅ 0.9.4 |
| MySQL-Sequence-Diff-Renderer | ⚠️ parallele Plan |
| SQLite-Sequence-Plan | ⚠️ `open/sqlite-sequence-emulation-plan.md` |
| Live-DB-Probe-Pattern (Cast-Preflight) | ✅ B.2 |

---

## 5. Architektur

### 5.1 Probe-Port

```kotlin
// hexagon:ports-read
interface SequenceCurrentValueProbe {
    fun probe(connection: JdbcOperations, sequenceName: String, dialect: Dialect): SequenceCurrentValueProbeResult
}

sealed class SequenceCurrentValueProbeResult {
    data class Read(val value: Long, val isCalled: Boolean? = null) : SequenceCurrentValueProbeResult()
    data class Failed(val code: String, val message: String) : SequenceCurrentValueProbeResult()
    data object NotApplicable : SequenceCurrentValueProbeResult() // SQLite without sequence support
}

// PG muss isCalled liefern; MySQL/SQLite dürfen null setzen.
```

### 5.2 Pipeline-Integration

Analog zu `SqliteCastPreflightProbe` in
`SchemaMigratePreflightPlanner`:

```
val sequencesNeedingPreservation = plan.operations
    .filterIsInstance<DiffOperation.CreateSequence>()
    .filter { it.sequence.preserveCurrentValue }

for (op in sequencesNeedingPreservation) {
    val probeSequenceName = when (op.objectRef.dialect) {
        Dialect.POSTGRES -> renderSequenceDbIdentifier(op.objectRef)
        else -> op.sequence.name
    }
    val result = probe.probe(connection, probeSequenceName, op.objectRef.dialect)
    when (result) {
        is Read -> {
            val isCalled = when (op.objectRef.dialect) {
                Dialect.POSTGRES -> {
                    if (result.isCalled == null) {
                        emitBlocker("SEQUENCE_PRESERVE_PROBE_FAILED", "PG-Probe muss is_called liefern")
                        continue
                    } else {
                        result.isCalled
                    }
                }
                else -> null
            }
            emitFollowupAlterSequenceCurrentValue(
                op,
                value = result.value,
                isCalled = isCalled,
                restoreValue = op.restoreValueHint,
            )
        }
        is Failed -> emitBlocker(result.code, result.message)
        NotApplicable -> emitBlocker("SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT")
    }
}
```

// renderSequenceDbIdentifier liefert nur für PG den schematisch-quotierten
// DB-Identifikator (z. B. "public.my_seq"). Für MySQL/SQLite-Emulation wird der
// unqualifizierte Sequenzname benötigt (z. B. "my_seq").

### 5.3 Operation-Modell

Festlegung: neuer Subtyp `AlterSequenceCurrentValue` mit
`currentValue`, optionalem `isCalled` und optionalem `restoreValue`,
weil der Render-Pfad fundamental anders ist (Daten-Statement statt DDL).

### 5.4 Dialekt-Render-Matrix

| Dialekt | Render |
|---|---|
| PG | `SELECT setval('<seq>', <value>, <isCalled>);` |
| MySQL | `UPDATE dmg_sequences SET next_value = <value> WHERE name = '<seq>';` |
| SQLite | Blocker bis SQLite-Sequence-Plan landet |

---

## 6. Sub-Slice-Schnitt

| Sub-Slice | Inhalt |
|---|---|
| A | `preserveCurrentValue`-Feld + `SequenceCurrentValueProbe`-Port + `AlterSequenceCurrentValue`-Subtyp |
| B | PG-Probe + PG-Renderer fuer `setval` |
| C | MySQL-Probe + MySQL-Renderer fuer `UPDATE dmg_sequences` |
| D | Pipeline-Integration in `SchemaMigrateRunner` (probe → emit) |
| E | Datei-zu-Datei-Blocker + Schema-Doku + Closing |

SQLite folgt aus `open/sqlite-sequence-emulation-plan.md`.

---

## 7. Akzeptanzkriterien

- [ ] `SequenceDefinition.preserveCurrentValue` ist im Schema-Modell
      definiert und ist die einzige Schaltstelle für diese Tranche.
- [ ] PG-Probe liest `last_value`; PG-Renderer emittiert
      `SELECT setval('<seq>', <value>, <isCalled>)` mit korrekt
      propagiertem `isCalled`.
- [ ] MySQL-Probe liest `dmg_sequences.next_value`; MySQL-Renderer
      emittiert `UPDATE dmg_sequences …`.
- [ ] Datei-zu-Datei-Modus mit `preserveCurrentValue = true`
      blockt mit `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`.
- [ ] Probe-Failure blockt mit
      `SEQUENCE_PRESERVE_PROBE_FAILED`.
- [ ] SQLite blockt mit
      `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`.
- [ ] Reversibility: `AlterSequenceCurrentValue` Down nutzt den im
      Plan gespeicherten `restoreValue` und setzt damit den
      vor-Up-Wert wieder zurueck; fehlt der Wert, wird
      `ROLLBACK_NOT_POSSIBLE` ausgewiesen.
- [ ] `restoreValueHint` ist genau in den Fällen gesetzt, in denen ein
      deterministischer Ausgangswert bekannt ist; für die übrigen Fälle
      ist Down explizit als `ROLLBACK_NOT_POSSIBLE` dokumentiert.
- [ ] Pro Dialekt mindestens je ein Positiv- und ein
      Blocker-Test.

---

## 8. Definition of Done (§13-Template)

- [ ] **Modus**: execute (Probe braucht Live-DB).
- [ ] **Renderbare Ops**: `AlterSequenceCurrentValue` auf PG/MySQL.
- [ ] **Neue Diagnostics**: `SEQUENCE_PRESERVE_PROBE_FAILED`,
      `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`,
      `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`. Alle drei
      mappen ueber `PlannerBlockerClassifier` auf
      `MANUAL_ACTION_REQUIRED` bzw.
      `DIALECT_UNSUPPORTED_OPERATION`.
- [ ] **Up / Down getrennt**: Up = `setval`/`UPDATE`; Down =
      `setval`/`UPDATE` auf den gespeicherten `restoreValue`, sonst
      expliziter `ROLLBACK_NOT_POSSIBLE`.
- [ ] **Report-Felder**: keine neuen.
- [ ] **Dialekte**: PG (positiv), MySQL (positiv), SQLite
      (blocker).
- [ ] **F.0-Erfuellung**: irrelevant.
- [ ] **Positive + Blocker-Tests**: siehe §7.
- [ ] **Rollback-Test**: explizit gepinnt fuer alle drei
      Dialekte; SQLite-Blocker ist auch Rollback-Blocker.
- [ ] **Datei-zu-Datei**: blockt, weil keine Live-DB.
- [ ] **Bestehende Vertraege unveraendert**: bestehende
      Sequence-Slices bleiben gruen.

---

## 9. Out-of-Scope / Folge-Themen

- Atomare Probe + setval unter Lock.
- Multi-Sequence-Atomarity.
- Sequence-Ownership-Inferenz.
- SQLite-Sequence-Vollvariante mit preserve-Pfad.

---

## 10. Risiken

- **Probe-Race-Conditions**: Wenn die App parallel zwischen
  Probe und Setval `nextval` aufruft, ist der gesetzte Wert
  veraltet. Mitigation: Pipeline-Doku schreibt klar, dass der
  Operator den Schreibverkehr stoppt; eine kuenftige Tranche
  kann `LOCK TABLES` / `BEGIN; SELECT FOR UPDATE`-Wrapper
  ergaenzen.
- **Sequence-Ownership-Sichtbarkeit**: ohne `OWNED BY` ist nicht
  klar, welche Sequenz zu welcher Spalte gehoert. Mitigation:
  diese Tranche behandelt benannte Sequences nur ueber den
  `SequenceDefinition`-Namen; Inferenz aus PG-Reverse-Read
  ist bereits in `MysqlSchemaReader` und `PostgresSchemaReader`
  gepinnt.
