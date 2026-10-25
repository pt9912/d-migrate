# PostgreSQL: der Reader verliert bei einer Domain alle CHECKs bis auf den letzten (Folge von #52)

> **Status:** In Progress (2026-10-25). Abgetrennt aus dem früheren Sammelplan zu Domain-Länge/-Array (dort in drei Pläne
> geteilt). Kleiner Reader-Fehler ohne Vertragsänderung. **Vorbedingung:** keine. Aktiviert (Move nach `in-progress/`) mit dem ersten Implementierungs-Commit. GitHub-Issue: [#57](https://github.com/pt9912/d-migrate/issues/57).
> Bezug: ADR 0061 („Weiter nicht erkannt": mehrere CHECKs).

## Ziel

Der PostgreSQL-Reader gibt **alle** `CHECK`-Constraints einer Domain zurück. Eine Datei und eine Datenbank, die dieselben
Prüfungen führen, erzeugen keine `AlterCustomType`-Operation.

## Problem (gemessen in #52, P0)

`CREATE DOMAIN d AS integer CONSTRAINT c1 CHECK (VALUE > 0) CONSTRAINT c2 CHECK (VALUE < 10)` → Reader liefert nur
`VALUE < 10`. Ursache:
[`PostgresTypeMetadataQueries.listDomainTypes`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMetadataQueries.kt)
verbindet `pg_constraint` ohne Aggregation (`LEFT JOIN … contype = 'c'`), zwei CHECKs ergeben zwei Zeilen;
[`readPostgresCustomTypes`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt)
schreibt `result[name] = …`, die letzte Zeile gewinnt (Reihenfolge nicht festgelegt). Ein CHECK geht still verloren; eine Datei
mit beiden gilt als geändert, eine mit dem ersten wird übersehen.

Weitere Reader-Verluste an Domain-`CHECK`s, in P0 zu messen: `NOT VALID` (`pg_get_constraintdef` hängt ` NOT VALID` an;
`normalizeDomainCheck` behält es, die äußere Klammer wird dann nicht mehr entfernt → ungültiges DDL `CHECK (… NOT VALID)`),
**Domain über Domain** mit eigenem CHECK (offene P0-Frage: geht der innere CHECK verloren, oder trägt der Reader ihn über den
Basistyp-Namen `bt.typname`? Nicht annehmen, messen).

## Richtung (nach P0 zu entscheiden)

* Der Reader liest die CHECKs **je Constraint** (eine Zeile pro `pg_constraint`, nicht `string_agg` im SQL), normalisiert **jedes**
  Prädikat in Kotlin mit `normalizeDomainCheck` (Cast-/Klammerentfernung arbeitet auf einem einzelnen Prädikat; ein verketteter
  String bräche sie) und fügt sie danach mit `AND`, **jedes in Klammern**, zusammen; ein einzelnes CHECK bleibt unverändert
  (`n = 1` nicht klammern). Reihenfolge: deterministisch `ORDER BY conname COLLATE "C"`. **Bekannte Grenze:** das Modell kennt die Namen der Datei
  nicht; eine Datei mit `a AND b` gegen eine Datenbank mit umgekehrter Namensfolge bleibt ein `AlterCustomType`. Unbenannte CHECKs
  erhalten Servernamen (`d_check`, `d_check1`) — P0 misst das.
* **Reader-Aufbau:** mit einer Zeile je Constraint wiederholen sich `typname`, `base_type`, Präzision und `domain_default`; `result`
  wird nach `typname` gruppiert. Fall „Domain ohne CHECK" (`LEFT JOIN` liefert `check_clause = null`). Reader-Unit-Test mit 0, 1 und
  n CHECKs (`PostgresMetadataQueriesTest` erwartet bisher rohe Maps). Semantik: Domain-CHECKs
  verwerfen nur bei `false`, `a AND b` ist äquivalent; Constraint-Namen gehen verloren (das Modell kennt eine `check`-Zeichenkette).
* **Vergleich mit der Autorenform:** der zusammengesetzte Ausdruck `(VALUE > 0) AND (VALUE < 10)` gegen `VALUE > 0 AND
  VALUE < 10` ist die Grenze „`AND` ohne Serverklammern" aus ADR 0061 Entscheidung 2 — dieser Plan beseitigt den Reader-Verlust,
  nicht diese Grenze. Der Akzeptanztest läuft deshalb gegen die Autorenform `(VALUE > 0) AND (VALUE < 10)`; für die Form ohne Klammern bleibt die Grenze.
* **Round-Trip:** `schema generate` schreibt **einen** `CHECK`; zwei benannte Constraints werden beim Zurückschreiben zu einem
  (zu messen: Drift gegen eine Datenbank, die zwei benannte trägt).
* `NOT VALID` → Suffix abtrennen, das Prädikat **behalten** (strip-and-keep; ein `NOT VALID`-Constraint prüft Neues weiter) und eine
  `SchemaReadNote` melden; Domain über Domain → die inneren CHECKs auflösen oder als Grenze benennen (P1).
* **Kopplung `RefTypeVocabulary`:** der Wertelisten-Check (`CHECK (VALUE IN (…))`) wird dort aus dem `check`-String als Wertevorrat
  erkannt; ein `AND`-Verbund aus mehreren CHECKs darf diese Erkennung nicht verändern. **Sollverhalten (P1 legt es fest):**
  `EnumCheckProjection.valuesOf` erkennt `IN`-Liste, `= ANY (ARRAY…)` und Gleichheits-OR-Kette; für `(VALUE IN (…)) AND (…)`
  liefert es voraussichtlich `null`. Entweder zählt der Verbund bewusst als „keine Werteliste", oder die Erkennung zerlegt `AND`
  (Hexagon-Änderung mit Tag-Folge); auch die Autorenseite mit `AND`-Check gehört dazu. Kopplungstest erst nach dieser Festlegung
  formulieren. Verweis: `sqlite-enum-domain-check-reverse.md` (in-progress) betrifft denselben Wertelisten-Check.
* Filter `contype = 'c'` erhalten (NOT NULL, `contype = 'n'`, nicht als CHECK einmischen).

Fingerabdruck: die Projektion der Datei ändert sich nicht, der **Reader-Ausgang** schon — für Live-Datenbanken mit mehreren
Domain-CHECKs und für Domains mit einem `NOT VALID`-Constraint (vorher stand das Suffix im Ausdruck). Damit ändert sich der Hash
des gelesenen Schemas. Die Entscheidung: dieser Plan vergibt **keinen eigenen Tag**; die Änderung wird beim Zusammenführen in den Tag
von #58 (`schema-fingerprint-v23`, ADR 0065) eingefaltet. **Planungshinweis:** sobald der Tag mit v23 wechselt, lehnt der Rollback Artefakte
mit einer solchen Domain ab (Exit 8, `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`); sie sind dann neu zu erzeugen. Dieser Branch bleibt auf v21
und verhält sich so noch nicht; die Aussage gehört in Changelog und Handbuch der v23-Änderung.
Bezug: [Plan #52](postgres-domain-autorenform-gegen-katalog.md). ADR 0061 bleibt unverändert; die dort genannte Grenze „Reader behält
den letzten CHECK" ist mit #57 aufgehoben.

## Phasen

* **P0 Messung** (Integrationstest gegen PostgreSQL, Muster `PostgresCustomTypeFileFormMigrateIntegrationTest`): zwei CHECKs
  (benannt/unbenannt, unsortierte Namen, `c10` vor `c2`), drei CHECKs, `NOT VALID`, Domain über Domain mit eigenem CHECK,
  Domain mit `NOT NULL` + CHECK (PG-Version aus `TestImages.POSTGRESQL` angeben; PG 18 legt Domain-NOT-NULL als `contype = 'n'` an, ältere nur als `typnotnull` — bei einer Image-Spanne beide messen). Je Fall Reader-Ausgabe und Diff gegen die Autorenform.
* **P1 Entscheidung:** Aggregationsform, `NOT VALID`, Domain über Domain, Tag/ADR.
* **P2 Umsetzung:** Reader-Unit-Test (Zeilen-Stub mit mehreren CHECKs), Integrationstest (zweiter Lauf `operationsTotal` 0,
  Rückweg, echte Änderung eines der CHECKs bleibt `AlterCustomType`).
* **P2 Matrix:** entfällt, siehe Ergebnisse (Datei-Modus ohne Reader, Begründung unter „Cross-Dialect-Matrix").
* **P3 Doku:** Handbuch §3.21 (die Grenze „mehrere `CHECK`-Constraints" streichen/nachführen, nur Ist-Zustand), `CHANGELOG.md`;
  `spec/ddl-generation-rules.md` §6.3 (Reader-Regel).

## Ergebnisse

### P0 Messung (2026-10-25, PostgreSQL 18.6, `TestImages.POSTGRESQL`)

Katalog (`pg_get_constraintdef`, `contype = 'c'`) und Reader-Folge, gemessen mit `psql` im Image und im Integrationstest
(vor der Änderung 11 von 47 Fällen rot):

| Fall | Katalog | Reader vorher |
| ---- | ------- | ------------- |
| zwei benannte (`c1`, `c2`) | zwei Zeilen, je `CHECK ((…))` | nur eine, Reihenfolge ungeordnet |
| unbenannt | Servernamen `d_check`, `d_check1` (Schreibfolge) | nur einer |
| benannt + unbenannt | `d_mix_check`, `zz` | nur einer |
| `c10`, `c2`, `c1` | in `COLLATE "C"`: `c1`, `c10`, `c2` | nur einer |
| `NOT VALID` | `CHECK ((VALUE > 0)) NOT VALID` | `((VALUE > 0)) NOT VALID` — Suffix im Ausdruck, ungültiges DDL |
| `NOT NULL` + CHECK | PG 18: `…_not_null` mit `contype = 'n'`, vom Filter `'c'` ausgenommen | unverändert, nur der CHECK |
| Domain über Domain mit eigenem CHECK | `d` hat `base_type = b` und nur den **eigenen** CHECK (`CHECK (((VALUE)::integer < 10))`); der innere steht an `b` | nicht verloren: der Reader liest `b` als eigene Domain, `d.baseType = b` |

Gemessen nur auf PG 18 (das einzige Image in `TestImages.POSTGRESQL`); ältere Versionen sind nicht gemessen.

### P1 Entscheidung

* **Aggregation:** je Constraint eine Zeile (`ORDER BY t.typname, c.conname COLLATE "C"`), Normalisierung je Prädikat in Kotlin,
  `n = 1` unverändert, ab `n = 2` `(a) AND (b)`. Der Reader gruppiert nach `typname`; eine Domain ohne CHECK liefert eine Zeile ohne
  `check_clause`.
* **`NOT VALID`:** strip-and-keep; Warnung `R407` (Domain und Constraint-Name), je ungültigem Constraint eine.
* **Domain über Domain:** kein Reader-Verlust, keine Änderung. Der innere CHECK steht an der inneren Domain, der Basistyp-Name
  verweist darauf. Der Cast an `VALUE` im äußeren CHECK bleibt die bekannte Grenze.
* **Wertelisten-Kopplung (`RefTypeVocabulary`):** der `AND`-Verbund zählt bewusst als **keine Werteliste**. `EnumCheckProjection.valuesOf`
  bleibt unverändert (es lehnt `AND` schon heute ab); die Gegenseite einer Datei mit `AND`-CHECK liest ebenfalls `null`, beide Seiten
  stimmen überein. Eine Hexagon-Änderung entfällt. Festgenagelt in `RefTypeVocabularyTest`.
* **Tag/ADR:** kein eigener Tag, kein ADR 0066. Weder Projektion noch Hash-Funktion ändern sich, wohl aber der Reader-Ausgang einer Domain
  mit mehreren CHECKs und einer `NOT VALID`-Domain; beide sind fingerabdruck-betroffen. Der Eigner faltet die Änderung beim Merge in
  `schema-fingerprint-v23` (ADR 0065, #58) ein; Konsequenz siehe „Fingerabdruck" oben (Artefakte neu erzeugen). Die Tests dieses
  Branches stehen bis dahin auf `schema-fingerprint-v21`.
* **Grenze der Reihenfolge:** ab dem elften unbenannten CHECK (`d_check10`; bis `d_check9` bleibt die Schreibfolge) ordnet die C-Kollation `d_check1, d_check10, d_check2, …`; die Reihenfolge
  weicht dann von der Schreibfolge ab. Im Integrationstest mit elf unbenannten CHECKs festgehalten.
* **Cross-Dialect-Matrix:** der Punkt im Plan entfällt. Die Matrix läuft im Datei-Modus (Datei gegen Datei, keine Reader) und kann einen
  Reader-Verlust nicht sehen; ein Fixture mit zwei CHECKs auf beiden Seiten wäre ohne die Änderung nicht rot. Die Abdeckung liegt im
  Integrationstest gegen PostgreSQL.

## Nicht-Scope

`AlterCustomType` auf PostgreSQL rendern; Länge/Array (`postgres-domain-laenge-array.md`); `DEFAULT`/`NOT NULL`/Kollation
(`domain-default-not-null-modell.md`).

## Akzeptanz

* Jede gemessene Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt; absichtlich falsche
  Zusicherung einmal rot gesehen.
* Die P1-Entscheidung zur Wertelisten-Kopplung (`RefTypeVocabulary`) ist umgesetzt und getestet.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module (einmal ohne `MODULES=`, falls eine geteilte Signatur wechselt),
  `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"` (das ganze Modul, Detekt eingeschlossen),
  `make solid-suppression-gate`, `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
