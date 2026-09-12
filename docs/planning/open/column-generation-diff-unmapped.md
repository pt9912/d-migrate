---
id: column-generation-diff-unmapped
title: "Die Erzeugungsart einer Spalte aendern: gemeldet, aber nicht ausgefuehrt"
status: open
---

# Die Erzeugungsart einer Spalte aendern

> **Der urspruengliche Befund ist ueberholt** (2026-09-12): `ColumnDiff
> .generation` wird sehr wohl zu einer Operation — `OperationMapper` bildet sie
> auf `DiffOperation.AlterColumnGeneration` ab, seit dem Slice
> [`generated-column-expression-dropped.md`](../done/generated-column-expression-dropped.md).
> Was davon **ausgefuehrt** wird, ist der Berechnungs**ausdruck**; die uebrigen
> Uebergaenge werden benannt abgelehnt. Dieser Eintrag fuehrt ab hier nur noch
> die Restfläche — mit der Messung, die vorher fehlte.

## Gemessen: welchen Uebergang welcher Server in place kann

Alle fuenf Server einzeln gefragt (PostgreSQL 18.6, MySQL 9.7.2, Oracle 23,
SQL Server 2025, SQLite ueber den echten Migrationspfad):

| Uebergang | PostgreSQL | MySQL | Oracle | SQL Server | SQLite |
| --- | --- | --- | --- | --- | --- |
| Ausdruck aendern (berechnet → berechnet) | `SET EXPRESSION` ab 17 ✅ | `MODIFY COLUMN` ✅ | `MODIFY` (virtuell, ohne Index) ✅ | Syntaxfehler | Neubau ✅ |
| gewoehnlich → berechnet | „is not a generated column" | „'Changing the STORED status' is not supported" | `ORA-54026` | Syntaxfehler | — |
| berechnet → gewoehnlich | `DROP EXPRESSION` **geht** | „'Changing the STORED status' is not supported" | `MODIFY (c <typ>)` **geht** | „Cannot alter column … because it is 'COMPUTED'" | — |
| Identity `ALWAYS` ↔ `BY DEFAULT` | `SET GENERATED …` **geht** | (Modus gibt es nicht) | `MODIFY (c GENERATED … AS IDENTITY)` **geht** | ungemessen | Identity steckt im **Typ** |
| Identity → gewoehnlich | `DROP IDENTITY` **geht** | `MODIFY c INT` **geht** (Schluesselspalte) | `MODIFY (c DROP IDENTITY)` **geht** | ungemessen | s. o. |
| gewoehnlich → Identity | braucht vorher `NOT NULL` | nur als Schluesselspalte | `ORA-30673` | „Incorrect syntax near the keyword 'IDENTITY'" | s. o. |

Die vierte und fuenfte Zeile sind der Kern der Restfläche: **drei Server koennen
mehrere dieser Uebergaenge**, d-migrate rendert keinen davon.

**SQLite ist der Sonderfall.** Dort steckt die Identity im Spalten**typ**
(`Identifier(autoIncrement = true)` → `INTEGER PRIMARY KEY AUTOINCREMENT`), nie
in `generation`; der Reverse liefert sie dort nicht. Eine Identity ueber
`generation` zu setzen beschreibt auf SQLite also etwas, das kein Neubau
herstellen kann.

## Behoben (2026-09-12)

- **Drei falsche Meldungen.** Ein Identity-Uebergang fiel in denselben Zweig wie
  ein Ausdruckswechsel und bekam eine Meldung ueber den *Berechnungsausdruck* —
  auf PostgreSQL, MySQL und Oracle. Es ging weder um einen Ausdruck, noch konnten
  die Server so wenig, wie die Meldung behauptete. Die Einteilung trifft jetzt
  `ColumnGenerationTransition` an einer Stelle, die Antwort bleibt beim Dialekt.
- **Zwei Behauptungen, die die Messung widerlegt hat.** „PostgreSQL cannot turn a
  generated column into an ordinary one in place" und „Oracle has no `MODIFY`
  that turns a generated column back into an ordinary one" — beide falsch;
  `DROP EXPRESSION` bzw. `MODIFY (c <typ>)` laufen durch. Geblockt wird weiter
  (der Uebergang ist ungebaut), aber die Meldung nennt jetzt den Befehl, den der
  Server annimmt, damit ein Operator ihn selbst fahren kann.
- **Ungueltige DDL.** „gewoehnlich → berechnet" rendert nicht mehr
  `SET EXPRESSION`/`MODIFY … GENERATED`, was jeder der drei Server ablehnt,
  sondern wird vorab benannt.
- **Ein Tabellen-Neubau fuer nichts (SQLite).** Eine Identity-Aenderung lief als
  Neubau durch — `CREATE TABLE …__dmg_rebuild_…`, `INSERT … SELECT`,
  `DROP TABLE`, `RENAME` — und die neue Tabelle sah aus wie die alte; danach
  meldete der Post-Compare Drift (Exit 5). Gemessen und abgestellt: der Fall
  wird vor dem ersten Statement benannt abgelehnt, die Tabelle bleibt
  unberuehrt.

## Was offen bleibt

**Die Uebergaenge ausfuehren, die die Server annehmen** — je Dialekt und je
Richtung, nach der Tabelle oben. Zu klaeren wie beim Ausdrucksfall:

- **Umkehrung und Risikoprofil je Richtung.** Identity zu entfernen ist
  ueberall leichter als sie hinzuzufuegen; `DROP EXPRESSION` behaelt die
  gespeicherten Werte als gewoehnliche Daten (PostgreSQL, gemessen) — ob das die
  gewuenschte Semantik ist, ist eine Zusage, keine Mechanik.
- **Das Verhaeltnis zu `AlterColumnType`.** Der Weg ueber den Typ
  (`integer` → `identifier`) funktioniert heute und ist der dokumentierte;
  beide Schreibweisen wuerden denselben Uebergang ausdruecken.
- **Die Vorbedingung auf PostgreSQL.** `ADD GENERATED … AS IDENTITY` verlangt
  `NOT NULL` vorher — also eine zweite Operation in derselben Anweisungsfolge,
  mit allem, was Reihenfolge und Umkehrung daran kostet.
- **SQL Server bleibt ungemessen** fuer die beiden Identity-Richtungen; dort ist
  ohnehin ein Neubau der einzige Weg.

## Aktivierungsbedingung

Unveraendert: ein belegter Bedarf, die Erzeugungsart **ohne** Typwechsel
umzustellen. Neu ist, dass der Fall nicht mehr still ist — er endet mit einer
benannten Meldung, die den Weg nennt, den der Server kann.
