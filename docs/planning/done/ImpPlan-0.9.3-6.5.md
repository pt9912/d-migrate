# Implementierungsplan: 0.9.3 - Arbeitspaket 6.5 `Doku- und Fixture-Nachzug`

> **Milestone**: 0.9.3 - Beta: Filter-Haertung und
> MySQL-Sequence-Emulation (Generator)
> **Arbeitspaket**: 6.5 (`Doku- und Fixture-Nachzug`)
> **Status**: Done (2026-04-20)
> **Referenz**: `docs/planning/implementation-plan-0.9.3.md` Abschnitt 4.2a,
> Abschnitt 4.3, Abschnitt 5.2, Abschnitt 6.5, Abschnitt 6.6,
> Abschnitt 7 und Abschnitt 8;
> `spec/neutral-model-spec.md`;
> `spec/schema-reference.md`;
> `docs/user/guide.md`;
> `docs/planning/roadmap.md`;
> `docs/planning/mysql-sequence-emulation-plan.md`;
> `spec/ddl-generation-rules.md`;
> `spec/cli-spec.md`;
> `CHANGELOG.md`;
> `docs/planning/ImpPlan-0.9.3-6.2.md`;
> `docs/planning/ImpPlan-0.9.3-6.3.md`;
> `docs/planning/ImpPlan-0.9.3-6.4.md`.

---

## 1. Ziel

Arbeitspaket 6.5 schliesst nach den Vertrags- und Generatorarbeiten
die nutzersichtbare Produktflaeche:

- Konsolidierung: alle Docs sprechen denselben 0.9.3-Vertrag
- Erstarbeit: CHANGELOG-Eintrag und Breaking-Change-Migrationsdoku
- Fixture-Erweiterung: `SequenceNextVal`-Spalten in Schema-Fixtures
- Golden-Master-Erweiterung: MySQL `helper_table`-Modus als neue GMs
- Verifikation: Konsistenzpruefung ueber alle betroffenen Dokumente

6.5 liefert bewusst keine neue Fachlogik. Das Arbeitspaket schliesst die
oeffentliche und testbare Produktflaeche nach 6.2 bis 6.4.

Nach 6.5 soll klar gelten:

- Nutzer sehen nur noch den neuen kanonischen Vertrag
- Release- und Migrationshinweise machen den Bruchpunkt explizit
- Golden Masters und Fixtures decken die neuen Faelle sichtbar ab

---

## 2. Ausgangslage

### 2.1 Abhaengigkeit von 6.2 bis 6.4

6.5 setzt voraus, dass 6.2, 6.3 und 6.4 **vollstaendig abgeschlossen**
sind.

### 2.2 Doku-Arbeit in frueheren WPs vs. 6.5

Mehrere Dokumente und Artefakte wurden in 6.2/6.3/6.4 bereits als
Arbeitsschritte zugesagt:

| Artefakt                        | Zugesagt in              | Rolle von 6.5                 |
| ------------------------------- | ------------------------ | ----------------------------- |
| `spec/ddl-generation-rules.md`  | 6.2 §6.4                 | Verifikation + Konsolidierung |
| `spec/cli-spec.md`              | 6.2 §6.4                 | Verifikation + Konsolidierung |
| `spec/neutral-model-spec.md`    | 6.3 §6.6                 | Verifikation + Konsolidierung |
| `spec/schema-reference.md`      | 6.3 §6.6                 | Verifikation + Konsolidierung |
| Schema-Fixtures                 | 6.3 §6.6 ("vorbereiten") | Erweitern + Absichern         |
| GM-Versionsupdate 0.9.2 → 0.9.3 | 6.2 §6.3                 | Verifikation                  |
| MySQL `helper_table` GMs        | 6.4 §7.1                 | Erweitern + Absichern         |

6.5 hat drei Rollen:

- **Erstarbeit** fuer Artefakte, die in keinem frueheren WP zugesagt
  sind: CHANGELOG-Eintrag, Breaking-Change-Migrationsdoku,
  `docs/user/guide.md` Beispiel-Update, `docs/planning/roadmap.md` Scope-Verweis
- **Fixture-Erweiterung**: bestehende `full-featured.yaml` um
  `SequenceNextVal`-Spalten erweitern, neue Modus-spezifische GMs
  anlegen
- **Verifikation und Konsolidierung**: pruefen, dass alle Dokumente
  denselben Wortlaut fuer CLI-Option, Warning-Codes und
  Eingabeform verwenden; ggf. Nachkorrektur bei inkonsistentem
  Wortlaut

Falls fruehere WPs eine zugesagte Doku-Aenderung nicht vollstaendig
geliefert haben, ist 6.5 der explizite Fallback, der die Luecke
schliesst.

### 2.3 Bestehende Fixture- und Golden-Master-Struktur

Fixtures und GMs folgen einer festen Konvention:

- Schemas: `adapters/driven/formats/src/test/resources/fixtures/schemas/<name>.yaml`
- GMs: `adapters/driven/formats/src/test/resources/fixtures/ddl/<name>.<dialect>.sql`
- Split-GMs: `<name>.<dialect>.pre-data.sql` / `<name>.<dialect>.post-data.sql`

Bestehender Stand:

- `full-featured.yaml` enthaelt `sequences:` mit `invoice_seq` und
  `simple_seq`, aber **keine** Spalte mit
  `default: { sequence_nextval: ... }`
- `full-featured.mysql.sql` zeigt E056-Skip fuer beide Sequences
- `full-featured.postgresql.sql` zeigt `CREATE SEQUENCE`
- `DdlGoldenMasterTest.kt` ist die Testklasse, die GMs verifiziert

Fuer MySQL `helper_table` wird ein modusspezifisches GM-Suffix
benoetigt (§4.4).

### 2.4 Release-Notes-Ort

`CHANGELOG.md` existiert im Repo-Root mit Keep-a-Changelog-Format
und einer `[Unreleased]`-Sektion. Der Breaking Change und die neuen
Features werden dort eingetragen.

---

## 3. Scope fuer 6.5

### 3.1 Erstarbeit (in keinem frueheren WP zugesagt)

- CHANGELOG-Eintrag unter `[Unreleased]` fuer alle 0.9.3-Sequence-
  Features und den Breaking Change
- Breaking-Change-Migrationsdoku mit Vorher/Nachher-Beispiel
- `docs/user/guide.md` um `default: { sequence_nextval: ... }` Beispiel
  erweitern
- `docs/planning/roadmap.md` mit Verweis auf den erreichten 0.9.3-Scope
- `docs/planning/mysql-sequence-emulation-plan.md` auf den tatsaechlichen
  Generatorstand bringen

### 3.2 Fixture-Erweiterung

- `full-featured.yaml` um mindestens eine Spalte mit
  `default: { sequence_nextval: invoice_seq }` erweitern
- neue GMs fuer MySQL `helper_table`-Modus
- aktualisierte GMs fuer `action_required`-Modus (mit
  SequenceNextVal-Spalte → E056-Diagnose)

### 3.3 Verifikation und Konsolidierung

- Wortlaut-Check ueber alle Dokumente:
  - `--mysql-named-sequences action_required|helper_table`
  - `W114` / `W115` / `W116` / `W117`
  - `default.sequence_nextval` als einzige Eingabeform
- Nachkorrektur bei inkonsistentem Wortlaut
- Verifikation, dass GM-Versionsstrings `0.9.3` zeigen (6.2 §6.3)

### 3.4 Bewusst nicht Teil von 6.5

- neue CLI- oder Generatorlogik
- weitere Modellentscheidungen
- Reverse-/Compare-Implementierung fuer 0.9.4

Praezisierung:

6.5 loest "wie werden Vertrag und Artefakte sauber nachgezogen?",
nicht "welche neue Fachlogik wird eingefuehrt?".

---

## 4. Leitentscheidungen

### 4.1 `default.sequence_nextval` ist die einzige kanonische Eingabeform

Verbindliche Folge:

- `spec/neutral-model-spec.md` und `spec/schema-reference.md`
  dokumentieren nur die Objektform
- `docs/user/guide.md` zeigt die Objektform in mindestens einem Beispiel
- lesbare Kurzformen wie `sequence_nextval(<name>)` duerfen in Diff oder
  Report erklaert werden, aber nicht als offizielle Eingabeform

### 4.2 Der Breaking Change fuer `nextval(...)` muss sichtbar sein

Verbindliche Folge:

- `CHANGELOG.md` unter `[Unreleased]` → `### Changed` markiert den
  Wechsel als Breaking Change
- eine separate Migrationssektion (im CHANGELOG oder als eigener
  Abschnitt in `docs/user/guide.md`) enthaelt:
  - Vorher/Nachher-Beispiel
  - Hinweis, dass alle Dialekte betroffen sind
  - klare Such-/Ersetzungsheuristik fuer bestehende Schema-Dateien

### 4.3 MySQL-Opt-in und Warning-Semantik muessen ueberall denselben Wortlaut haben

Verbindliche Folge:

- `spec/ddl-generation-rules.md`
- `spec/cli-spec.md`
- `docs/user/guide.md`
- ggf. `docs/planning/mysql-sequence-emulation-plan.md`

muessen denselben Vertrag sprechen fuer:

- `--mysql-named-sequences action_required|helper_table`
- `W114` / `W115` / `W116` / `W117`
- `helper_table` als opt-in statt neuem Default

### 4.4 Golden-Master-Namenskonvention fuer MySQL-Modi

Die bestehende Konvention `<name>.<dialect>.sql` unterscheidet nicht
zwischen `action_required` und `helper_table`. Fuer 6.5 gilt:

- `<name>.mysql.sql` bleibt der `action_required`-GM (Default-Modus)
- `<name>.mysql.helper-table.sql` wird der neue `helper_table`-GM
- `<name>.mysql.helper-table.pre-data.sql` /
  `<name>.mysql.helper-table.post-data.sql` fuer Split-GMs

Begruendung:

- `action_required` ist der Default und behaelt den kuerzeren Namen
- `helper_table` ist der opt-in-Modus und bekommt ein explizites
  Suffix

### 4.5 Bestehende Fixture wird erweitert, keine neue Fixture

Die bestehende `full-featured.yaml` wird um `SequenceNextVal`-Spalten
erweitert, statt eine neue Fixture-Datei anzulegen.

Begruendung:

- `full-featured.yaml` enthaelt bereits `sequences:` mit
  `invoice_seq` und `simple_seq`
- die Erweiterung um eine Spalte mit
  `default: { sequence_nextval: invoice_seq }` ist minimal und
  haelt die Fixture-Zahl niedrig
- alle drei Dialekte (PostgreSQL, MySQL, SQLite) und alle Modi
  werden durch dieselbe Fixture abgedeckt

Auswirkung: Alle bestehenden GMs fuer `full-featured` muessen
aktualisiert werden (die neuen Spalten erscheinen in der DDL).

---

## 5. Zielarchitektur

### 5.1 Doku-Nachzug (Erstarbeit)

`CHANGELOG.md` unter `[Unreleased]`:

- `### Added`: `--mysql-named-sequences` CLI-Option,
  `default.sequence_nextval` Schema-Form, MySQL `helper_table`
  Sequence-Emulation
- `### Changed`: `nextval(...)` als Breaking Change markiert,
  Versionsstrings auf 0.9.3
- Vorher/Nachher-Migrationshinweis inline oder als Verweis

`docs/user/guide.md`:

- neues Beispiel im Default-Abschnitt fuer
  `default: { sequence_nextval: invoice_seq }`
- kurzer Hinweis auf den MySQL-Opt-in-Modus

`docs/planning/roadmap.md`:

- 0.9.3-Scope markiert als "Sequence-Emulation (Generator)"
- Reverse/Compare als 0.9.4-Scope abgegrenzt

`docs/planning/mysql-sequence-emulation-plan.md`:

- Umsetzungsstand auf den tatsaechlich in 6.4 erreichten Scope
  aktualisieren

### 5.2 Doku-Konsolidierung (Verifikation)

Die folgenden Dokumente wurden in 6.2/6.3/6.4 aktualisiert. 6.5
prueft die Konsistenz:

- `spec/neutral-model-spec.md`: `default.sequence_nextval` als
  kanonische Form dokumentiert?
- `spec/schema-reference.md`: nutzerorientierte Syntax korrekt?
- `spec/cli-spec.md`: `--mysql-named-sequences` Vertrag konsistent?
- `spec/ddl-generation-rules.md`: Warning-Codes, MySQL-Modus,
  `helper_table`-Semantik konsistent?

Nachkorrektur: Falls Wortlaut-Abweichungen gefunden werden,
korrigiert 6.5 auf den in 6.2/6.3/6.4 festgelegten Vertrag.

### 5.3 Fixture- und Golden-Master-Nachzug

Fixture-Erweiterung:

- `adapters/driven/formats/src/test/resources/fixtures/schemas/full-featured.yaml`:
  mindestens eine bestehende oder neue Spalte erhaelt
  `default: { sequence_nextval: invoice_seq }`

Neue Golden Masters (alle unter
`adapters/driven/formats/src/test/resources/fixtures/ddl/`):

- `full-featured.mysql.helper-table.sql`
- `full-featured.mysql.helper-table.pre-data.sql`
- `full-featured.mysql.helper-table.post-data.sql`

Aktualisierte Golden Masters:

- `full-featured.mysql.sql` (SequenceNextVal-Spalte → E056-Diagnose)
- `full-featured.mysql.pre-data.sql`
- `full-featured.postgresql.sql` (SequenceNextVal-Spalte →
  `DEFAULT nextval('invoice_seq')`)
- `full-featured.postgresql.pre-data.sql`
- `full-featured.sqlite.sql` (SequenceNextVal-Spalte → suppressed)
- `full-featured.sqlite.pre-data.sql`

Testklasse:

- `DdlGoldenMasterTest.kt`
  (`adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`)
  muss fuer die neuen `helper_table`-GMs erweitert werden

---

## 6. Konkrete Arbeitsschritte

### 6.1 CHANGELOG und Migrationsdoku schreiben

- `CHANGELOG.md` `[Unreleased]`-Sektion um 0.9.3-Sequence-Features
  und Breaking Change erweitern
- Migrationshinweis fuer historische `nextval(...)`-Notation:
  Vorher/Nachher, Such-/Ersetzungsheuristik
- klaren Hinweis, dass alle Dialekte betroffen sind

### 6.2 `docs/user/guide.md` erweitern

- Default-Abschnitt um `sequence_nextval`-Beispiel ergaenzen
- kurzer Verweis auf `--mysql-named-sequences`

### 6.3 Roadmap-/Plan-Doku synchronisieren

- `docs/planning/roadmap.md` mit Verweis auf den erreichten 0.9.3-Scope
- `docs/planning/mysql-sequence-emulation-plan.md` auf den tatsaechlichen
  Generatorstand bringen

### 6.4 Fixture erweitern

- `full-featured.yaml`: mindestens eine Spalte mit
  `default: { sequence_nextval: invoice_seq }` hinzufuegen

### 6.5 Golden Masters anlegen und aktualisieren

- neue GMs fuer `full-featured.mysql.helper-table.*`
- bestehende GMs fuer `full-featured.*` aktualisieren
  (SequenceNextVal-Spalte in der DDL sichtbar)
- `DdlGoldenMasterTest.kt` um `helper_table`-Testcases erweitern

### 6.6 Doku-Konsistenz verifizieren

- Wortlaut-Check ueber `neutral-model-spec.md`,
  `schema-reference.md`, `cli-spec.md`, `ddl-generation-rules.md`,
  `guide.md`
- Nachkorrektur bei Abweichungen

---

## 7. Tests und Verifikation

### 7.1 Dokumentations-Checks

- `spec/neutral-model-spec.md` zeigt `default.sequence_nextval`
  als einzige Eingabeform
- `docs/user/guide.md` enthaelt ein `sequence_nextval`-Beispiel
- `CHANGELOG.md` markiert den `nextval(...)`-Bruch als Breaking
  Change mit Migrationshinweis
- CLI- und DDL-Regeldoku verwenden denselben Wortlaut fuer
  `--mysql-named-sequences` und `W114` bis `W117`

### 7.2 Golden-Master-Checks

Alle Checks ueber `DdlGoldenMasterTest.kt`:

- `full-featured.postgresql.sql` enthaelt
  `DEFAULT nextval('invoice_seq')` fuer die neue Spalte
- `full-featured.mysql.sql` enthaelt E056-Diagnose fuer die neue
  Spalte
- `full-featured.mysql.helper-table.sql` enthaelt
  `dmg_sequences`-Tabelle, Support-Routinen und Trigger
- Split-GMs: `dmg_sequences` in `pre-data`, Routinen/Trigger in
  `post-data`
- alle GMs zeigen `0.9.3` im Header

### 7.3 Akzeptanzkriterien

6.5 gilt als abgeschlossen, wenn gleichzeitig gilt:

- alle nutzerrelevanten Docs spiegeln den 0.9.3-Vertrag korrekt
- der Breaking Change fuer historische `nextval(...)` ist in
  `CHANGELOG.md` sichtbar dokumentiert
- `full-featured.yaml` enthaelt mindestens eine
  `SequenceNextVal`-Spalte
- Golden Masters decken alle drei Dialekte und beide MySQL-Modi ab
- `DdlGoldenMasterTest` verifiziert die neuen GMs
- die Doku widerspricht dem Code-/Planvertrag an keiner Stelle

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen (Erstarbeit):

- `CHANGELOG.md` — 0.9.3-Eintraege und Breaking-Change-Hinweis
- `docs/user/guide.md` — `sequence_nextval`-Beispiel
- `docs/planning/roadmap.md` — 0.9.3-Scope-Verweis
- `docs/planning/mysql-sequence-emulation-plan.md` — Umsetzungsstand

Voraussichtlich betroffen (Verifikation/Konsolidierung):

- `spec/neutral-model-spec.md`
- `spec/schema-reference.md`
- `spec/ddl-generation-rules.md`
- `spec/cli-spec.md`

Voraussichtlich betroffen (Fixtures und Golden Masters):

- `adapters/driven/formats/src/test/resources/fixtures/schemas/full-featured.yaml`
  — neue `SequenceNextVal`-Spalte
- `adapters/driven/formats/src/test/resources/fixtures/ddl/full-featured.mysql.helper-table.sql`
  (neu)
- `adapters/driven/formats/src/test/resources/fixtures/ddl/full-featured.mysql.helper-table.pre-data.sql`
  (neu)
- `adapters/driven/formats/src/test/resources/fixtures/ddl/full-featured.mysql.helper-table.post-data.sql`
  (neu)
- `adapters/driven/formats/src/test/resources/fixtures/ddl/full-featured.mysql.sql`
  (aktualisiert)
- `adapters/driven/formats/src/test/resources/fixtures/ddl/full-featured.mysql.pre-data.sql`
  (aktualisiert)
- `adapters/driven/formats/src/test/resources/fixtures/ddl/full-featured.postgresql.sql`
  (aktualisiert)
- `adapters/driven/formats/src/test/resources/fixtures/ddl/full-featured.postgresql.pre-data.sql`
  (aktualisiert)
- `adapters/driven/formats/src/test/resources/fixtures/ddl/full-featured.sqlite.sql`
  (aktualisiert)
- `adapters/driven/formats/src/test/resources/fixtures/ddl/full-featured.sqlite.pre-data.sql`
  (aktualisiert)
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`
  — neue `helper_table`-Testcases

---

## 9. Offene Punkte

### 9.1 6.5 ist Nachzug, kein neuer Vertragsraum

6.5 darf keine neue alternative Eingabeform oder neue Option erfinden.
Das Arbeitspaket spiegelt nur die in 6.2 bis 6.4 bereits
festgezogenen Regeln nach aussen.
