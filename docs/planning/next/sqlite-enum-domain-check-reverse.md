# SQLite: Wertevorrat von `ref_type`-Enums gegen eine echte Datenbank (Issue #48)

> **Status:** Draft mit Scope (2026-10-24), nach drei Review-Runden. Angelegt
> nach der Umsetzung von #48/#49 (Commit `6889535c8`), die eine Grenze bewusst
> offen lässt.
> **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim
> ersten Implementierungs-Commit.

## Ziel

`schema migrate` gegen eine **echte** SQLite-Datenbank erkennt eine Änderung des
Wertevorrats eines `ref_type`-Enums und setzt sie um (Teil A). Heute endet der
Lauf mit „ok", der CHECK in der Datenbank bleibt unverändert; der Planner warnt
nur (`SQLITE_CUSTOM_TYPE_VOCABULARY_UNVERIFIED`). Domain-CHECKs (Teil B) sind ein
eigener, späterer Schnitt.

## Problem

Ein `ref_type`-Enum und eine Domain liegen auf SQLite als **unbenannter** CHECK
an der Spalte ([`SqliteRefTypeColumns`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRefTypeColumns.kt)).
Der [`SqliteSchemaReader`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt)
liest nur benannte CHECKs als `ConstraintDefinition`; die unbenannten gehen als
INFO-Notiz `R203` verloren. Der CHECK bleibt unbenannt, weil ein benannter vom
Reader als Constraint zurückgelesen würde, den das Soll nicht kennt
(→ `POST_EXECUTE_DRIFT`, Exit 5).

Gegen eine Datei (`file:`-Ziel) tritt das Problem nicht auf: dort liegen beide
Schemas neutral vor, der Diff sieht `AlterCustomType` und baut die Tabelle neu.
Gegen eine echte Datenbank hat die Ist-Seite keine `custom_types`; es entsteht nur
`CreateCustomType` (bucht nichts, nur INFO + Warnung), nie `AlterCustomType`.

## Was schon da ist — und was nicht

- [`SqliteCheckConstraintScanner`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteCheckConstraintScanner.kt)
  liefert den Text unbenannter CHECKs bereits.
- [`EnumCheckProjection`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/EnumCheckProjection.kt)
  stellt einen Wertelisten-CHECK einem `enum` am Spaltentyp gleich —
  formbasiert, Werte als Menge, tolerant gegen Whitespace, Quoting und Casts.
  Gültig ist [ADR 0055](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md)
  (ersetzt [ADR 0048](../../adr/0048-enum-wertevorrat-im-fingerprint.md));
  die Faltung greift im Vergleich nur im **zielbewussten** Modus
  (`targetProjection != null`), `schema compare` bleibt streng.
- **Inline-Enums (`values`) sind bereits gelöst.** SQLite schreibt sie mit
  **benanntem** CHECK `ck_<tabelle>_<spalte>`
  ([`EnumValueCheck`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/metadata/EnumValueCheck.kt));
  der Reader liest ihn zurück, die Faltung greift. Die Aussage „SQLite rendert
  ein Enum als nacktes `TEXT`" in
  [`fingerprint-v8-enum-check-projection.md`](../done/fingerprint-v8-enum-check-projection.md)
  ist für SQLite veraltet.
- **`ref_type`-Enums sind der offene Fall.** Ihr Typ ist
  `Enum(values = null, refType = …)`; `declaredVocabulary` liefert dafür nichts,
  der Wertevorrat steht nur in `custom_types`.
- **Warum heute keine Drift auftritt** (belegt): Die Typ-Kanonisierung faltet
  `Enum(ref)` auf SQLite zu `TEXT` (`SqliteNeutralTypeCanonicalizer`), der Reader
  liest `TEXT`; beide Seiten hashen `type=text`, `enum_checks[0]`,
  `constraints[0]`. Es liegt **nicht** an `supportsCustomTypes = false` — das
  betrifft nur die `custom_types[n]`-Sektion.
- **Warum der Reader-Umbau allein nicht trägt:** Liefert der Reader den CHECK,
  hat die Ist-Seite `enum_checks[1]` und `{spalte: [werte]}`, die Soll-Seite `[0]`
  und `{}` → `ValueChange` im Comparator und `POST_EXECUTE_DRIFT` bei jedem Lauf
  (`foldable` faltet auch bei `declared == null`).

## Richtung

**Kern ist eine Vorverarbeitung der Soll-Seite, nicht der Reader.** Eine neue
reine Funktion in `hexagon/core` (Arbeitsname `RefTypeVocabulary.resolve`)
ersetzt für jede Spalte mit `Enum(values = null, refType = r)` und
`customTypes[r].kind == ENUM` den Typ durch `Enum(values = customTypes[r].values)`.
DOMAIN und COMPOSITE bleiben unberührt. `EnumCheckProjection`,
`foldConstraints` und die Canonicalizer bleiben unverändert — `toSql(Enum)` liefert
für beide Varianten `TEXT`, der Hash ändert sich also nur um `enum_checks[1]`.

Der Reader liefert (nur mit Option, E1) den enum-förmigen CHECK als
`ConstraintDefinition`; die vorhandene Projektion faltet beide Seiten auf dieselbe
Form.

### Einhängepunkte (geprüft)

| Stelle | Änderung |
| --- | --- |
| `MigrationFingerprint.project()` (`hexagon/core`, `:226-257`) | `schema` liegt vor; die Tabellen laufen vor `canonicalizedTables(...)` durch `resolve(schema)`, wenn der Dialekt es verlangt. Keine neuen Aufrufer-Parameter, wenn das Gate über die bereits durchgefädelte Capability läuft. |
| `TableComparator.enumVocabularies` (`:244-249`) | bekommt die beiden `customTypes`-Maps; löst nur im `targetProjection != null`-Zweig auf. `compareTables` hat beide Schemas, reicht sie bisher nicht weiter. Weiterer Aufrufer `RenameIntraObjectDeltaSynthesizer` (strikt, Defaults). |
| `TargetProjection` (`:30-36`) | neues Feld (Default `false`) als Dialekt-Gate für den Comparator. Die Projektion darf nicht in die gemeldeten Definitionen oder die DDL fließen. |

**Dialekt-Gate.** Eine eigene Capability („löst `ref_type`-Wertevorräte auf"),
nur für SQLite gesetzt — nicht `!projectsCustomTypes`: das gilt auch für MySQL,
SQL Server und Oracle, die `ref_type` bereits über
`canonicalize(type, customTypes)` auflösen; dort darf sich nichts ändern.

**`file:`-Ziele bleiben operationsgleich.** Tragen beide Seiten denselben
`ref_type`, deckt `AlterCustomType` die Wertänderung ab; ein zusätzliches
`ColumnDiff` würde Operations-IDs und Rebuild-Buckets ändern. Daher löst der
Comparator eine Spalte nur auf, wenn die **Gegenseite** nicht selbst `ref_type`
führt. Der Fingerprint löst dagegen immer auf (er vergleicht keine Seiten).

**Plan-Weg gegen die echte Datenbank.** Ist: `TEXT` + synthetischer Constraint
(gefaltet), Soll: `Enum(ref)` aufgelöst → Vokabular-Unterschied → `ColumnDiff`
(`ValueChange`) → Rebuild der Tabelle. Daneben bleibt `CreateCustomType` (INFO).
Zu belegen ist, dass `SqliteRebuildPlanner` (`onlyVocabularyDiffers`, Spaltenabgleich)
den Fall Enum gegen Text bereits trägt.

Teil B (Domain) braucht dieselbe Auflösung **und** eine Projektion für
Nicht-Wertelisten-CHECKs (`n >= 0`; `VALUE` → Spalte, Textnormalisierung), die
`EnumCheckProjection` nicht erkennt. Eigener Schnitt nach Teil A.

## Phasen

### P0 — Messung (Test im selben Commit wie P2)

Der Prototyp der Soll-Auflösung ist klein (eine Funktion plus Reader-Aufruf);
P0 und P2 werden daher zusammen umgesetzt, die Messung bleibt als Test erhalten.
Fingerprint Soll gegen Reverse für zwei Spalten — Inline-Enum und
`ref_type`-Enum — vor der Änderung (Basis: heute identisch, keine Drift), nur mit
Reader-Option (Dauer-Drift), mit Reader-Option **und** Auflösung (identisch).
Getrennt gemessen für die **Migrate-Lesung** (Option gesetzt) und die
**Reverse-/Compare-Lesung** (Option aus, muss byte-gleich zu `6889535c8` bleiben).

### P1 — Entscheidung (ADR)

Eine neue Faltung ist eine Entscheidungsänderung. ADR 0048 und 0055 sind
eingefroren (`doc-immutable`); nötig ist ein **neuer** ADR (Nummer bei
Aktivierung): Soll-Auflösung (Dialekt-Gate, Comparator-Regel für `file:`),
Reader-Option (Wiring-Schalter statt Nutzer-Flag), synthetischer Name,
Fingerprint-Bump.

### P2 — Soll-Seite in `hexagon/core`

Wie unter „Einhängepunkte". **Der Fingerprint-Bump ist gesetzt**, nicht
bedingt: die Auflösung ändert den Hash jedes SQLite-Soll-Schemas mit
`ref_type`-Enum (`enum_checks[0]` → `[1]`) auch ohne Reader-Umbau. `ALGORITHM`
`schema-fingerprint-v17` → `v18` und gilt für alle Dialekte; Akzeptanz: die
Fingerprints der anderen Dialekte bleiben **byte-gleich bis auf den
Algorithmus-Tag**. (Der Kommentar bei `project()` begründet, warum
`custom_types[0]` bewusst keinen Bump bekam; hier ist er nötig, weil sich der
Hash echter Eingaben ändert.) Umgang mit v17-Rollback-Artefakten: die bestehende
Meldung `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` (`SchemaRollbackRunner`) bleibt,
nur der Wert ändert sich. `MigrationFingerprint` liegt nahe an den
Detekt-Größenregeln; Aufteilung statt `@Suppress`.

### P3 — Reader (SQLite)

Der Reader gibt einen unbenannten, enum-förmigen CHECK
(`EnumCheckProjection.valuesOf(expr, spalte) ≠ null`) als `ConstraintDefinition`
zurück — **nur** mit der Option.

- **Option.** Ein Wiring-Schalter in `SchemaReadOptions` (Arbeitsname
  `sqliteRefTypeChecks: Boolean = false`), den `SchemaMigrateWiring` (`:223`) und
  `SchemaRollbackWiring` (`:73`) fest setzen — kein Nutzer-Flag, keine
  `spec/cli-spec.md`-Änderung. Pre-Read und Post-Compare des Migrate-Laufs gehen
  durch denselben Loader (`SchemaMigratePreparation`, `SchemaMigrateExecutionStage`),
  eine Stelle genügt; Rollback liest separat und muss die Option setzen, sonst
  hat es einen anderen Hash als der Migrate-Lauf. Ein MCP-Migrate-Pfad existiert
  nicht (`spec/mcp-server.md` kennt kein `schema_migrate`); die MCP-Lesepfade
  bleiben mit Default aus unverändert.
- **Eindeutigkeit.** Ein synthetischer Constraint entsteht nur, wenn die Spalte
  **genau einen** unbenannten, enum-förmigen CHECK trägt. Bei mehreren bleibt es
  bei `R203`; d-migrate schreibt selbst genau einen.
- **Name.** `ck_<tabelle>_<spalte>` scheidet aus (Name des Inline-Enum-CHECK). Fester
  Präfix `__dm_enum_<tabelle>_<spalte>`. Existiert schon ein Constraint dieses
  Namens, entsteht kein synthetischer (`R203`). Gefaltet wandert der Constraint in
  `enum_checks` und der Name verschwindet aus dem Hash; ungefaltet (z. B.
  Widerspruch zum Typ) bliebe er sichtbar.
- **Aufteilung.** `SqliteSchemaReader` (565 Zeilen) wächst nicht; die Logik kommt in
  ein eigenes Objekt neben dem Scanner.

### P4 — Rebuild, Down-Richtung, Warnung

**Up** schreibt die Soll-Tabelle: `Enum(ref)`-Spalten bekommen ihren unbenannten
CHECK über `SqliteRefTypeColumns`; die Soll-Tabelle trägt keinen synthetischen
Constraint, es gibt kein Duplikat.

**Down / Rollback** schreibt die Ist-Tabelle des Readers: Spalte `TEXT` +
synthetischer Constraint. `SqliteRebuildRenderer.buildCreateTempSql` schreibt
`target.constraints` benannt; ein synthetischer Constraint würde also als
`CONSTRAINT __dm_enum_… CHECK (…)` zurückgeschrieben, der Folge-Reverse läse einen
**benannten** CHECK. Festlegung: Constraints mit dem synthetischen Präfix werden
beim Rebuild **unbenannt** geschrieben (`CHECK (…)`), womit der Ausgangszustand
exakt wiederhergestellt ist. Test: Rollback nach Rebuild vergleicht das DDL in
`sqlite_master` mit dem Ausgangszustand; der Folgelauf endet ohne
`TARGET_STATE_MISMATCH`.

**Warnung.** `warnIfVocabularyUnverifiable` (`SqliteDiffCustomTypeOps`) feuert
heute bei jedem `CreateCustomType` mit bestehender Spalte und prüft die `kind`
nicht. Sie bleibt nur für `kind == DOMAIN`; für ENUM entfällt sie (Test beider
Fälle).

**Verletzende Daten (E3).** Verbindlich sind Atomarität (getestet) **und** ein
Preflight vor dem Apply (siehe E3).

### P5 — Doku

Anwenderhandbuch §3.21: Grenze für Enums entfällt, für Domains bleibt sie.
`spec/ddl-generation-rules.md` §6.3 **umschreiben**, nicht nur ergänzen: die
dortige Begründung („der Reverse liest einen benannten CHECK als Constraint
zurück", unbenannter CHECK) ändert sich. `CHANGELOG.md`.

## Entscheidungen

**E1 — Reader-Reichweite: Option, Default aus.**
`SchemaReadOptions` trägt schon dialektspezifische Optionen (`sqliteAutoincrement`),
und jeder Aufrufer baut seine eigenen: `SchemaReverseRunner`, `SchemaCompareWiring`
und `DataTransferRunner` haben je eigene. Eine Option, die nur Migrate **und**
Rollback setzen, lässt `schema reverse`, strikten `schema compare` und
`data transfer` unverändert (kein Golden, keine CLI-Spec-Änderung, `R203` bleibt).

**E2 — Mehrere Wertelisten-CHECKs auf einer Spalte: kein synthetischer Constraint.**
Siehe P3. Handgeschrieben, nicht von d-migrate erzeugt; bleibt sichtbar (`R203`).

**E3 — Verletzende Daten: Atomarität garantiert, Preflight Pflicht.**
Gemessen (sqlite3, Rebuild-Sequenz mit `c` in den Daten und engerem CHECK): der
`INSERT … SELECT` bricht mit `CHECK constraint failed: e` ab. Beim Stream-eigenen
Rebuild-Bracket sendet der Executor `ROLLBACK`
(`JdbcMigrationStatementExecutor`, `runStreamOwnedTransaction`); die Tabelle bleibt
mit allen Zeilen, `tryRestoreFkStateAfterRollback` setzt `foreign_keys` zurück
(best-effort). Läuft jemand die erzeugte SQL-Datei per Shell, die nach Fehlern
weiterläuft, ist `DROP TABLE` + `RENAME` durchgelaufen und die Tabelle **leer**
(gemessen).
- Pflicht: ein Test, dass das Apply mit verletzenden Daten fehlschlägt, die
  Tabelle unverändert lässt und den Fehler der Operation zuordnet.
- Pflicht: Preflight vor dem Apply. `CheckPreflightPlanner` kennt nur
  `AddConstraint(CHECK)`; der Rebuild wegen der Vokabular-Änderung erzeugt keine
  solche Operation. P4 erweitert den Planner um diese Quelle.
- Das Plan-Only-Artefakt stellt `foreign_keys` nach einem Abbruch nicht wieder her
  (`SchemaMigrateArtefactSink`, Caveat 2); dessen Caveat-Block nennt den
  Datenverlust bei Shell-Ausführung nicht. P4 ergänzt ihn.

## Nicht-Scope

- Composite-Typen auf SQLite (bleiben im Diff blockiert).
- Änderungen am Verhalten von `file:`-Zielen (Operationen bleiben gleich, siehe
  Akzeptanz).
- Andere Dialekte.
- Domain-CHECK-Projektion (Teil B, eigener Schnitt).
- MCP, Server-State und Native-Binary: kein Migrate-Pfad.

## Akzeptanz (Teil A)

- E2E gegen echte SQLite-Datei, **beide** Enum-Arten: `ref_type`-Enum, Wert
  hinzufügen und entfernen → `schema migrate --execute` ändert den CHECK, Daten
  bleiben, Folgelauf Exit 0; Inline-Enum verhält sich unverändert.
- Der neue Test schlägt auf dem Stand `6889535c8` fehl: Assertion „`sqlite_master`
  enthält nach dem Lauf den neuen Wert" scheitert dort (Lauf endet mit Exit 0
  ohne Wirkung).
- Plan gegen ein `file:`-Ziel ist vor und nach der Änderung **operationsgleich**
  (inkl. Operations-IDs).
- Handgeschriebener, **unbenannter** CHECK in anderer Schreibweise mit
  **gleichem** Wertevorrat auf einer `ref_type`-Spalte: weder Rebuild noch Drift;
  mit **anderem** Vorrat: Rebuild. Ein handgeschriebener CHECK ohne `ref_type` im
  Soll wird zu `enum_checks` gefaltet und vom Rebuild abgelöst (gewollt, dokumentiert).
- Strikter `schema compare` bleibt unverändert (Test); Reverse-/Compare-Lesung
  (Option aus) ist byte-gleich zum Stand `6889535c8`.
- Die Warnung `SQLITE_CUSTOM_TYPE_VOCABULARY_UNVERIFIED` entfällt für ENUM und
  bleibt für DOMAIN (Test beider Fälle).
- Wertentfernung mit verletzenden Daten (E3): Apply schlägt fehl, Tabelle
  unverändert, Preflight meldet es vorher; Caveat im Plan-Only-Artefakt ergänzt.
- `schema rollback` nach einem Rebuild: DDL in `sqlite_master` entspricht dem
  Ausgangszustand, Folgelauf ohne `TARGET_STATE_MISMATCH`.
- Fingerprints der anderen Dialekte bleiben byte-gleich bis auf den
  Algorithmus-Tag (Test); v17-Rollback-Artefakt liefert die bestehende
  Algorithmus-Fehlermeldung.
- `make docker-test`, `make docker-check` (betroffene Module, einmal ohne
  `MODULES=` wegen der Signaturänderung im Hexagon), `make integration`,
  `make solid-suppression-gate`, `make docs-check` grün; vor dem Push
  `make doc-immutable RANGE=origin/main..HEAD`.
