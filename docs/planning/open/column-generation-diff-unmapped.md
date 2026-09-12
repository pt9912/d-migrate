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

## Gebaut (2026-09-12): die Uebergaenge, die die Server wirklich koennen

Vor dem Bau wurde jeder Uebergang einzeln gemessen — und zwar **durch den
vollen Pfad**, nicht nur als Anweisung. Das war noetig: Oracles
`MODIFY (c <typ>)` auf einer virtuellen Spalte ist gueltiges SQL, wird
angenommen und **aendert nichts**. Eine Sonde, die nur den Spaltenwert liest,
sieht keinen Unterschied (der Wert ist ja der gerechnete); erst der
Post-Compare zeigte, dass die Spalte weiterhin virtuell war. Eine angenommene
Anweisung ist noch keine wirksame.

| Uebergang | PostgreSQL | MySQL | SQLite | Oracle | SQL Server |
| --- | --- | --- | --- | --- | --- |
| berechnet → gewoehnlich | **`DROP EXPRESSION`** (Wert bleibt) | blockt | **Neubau** (Wert bleibt) | blockt (`MODIFY` wirkt nicht) | blockt |
| gewoehnlich → berechnet | blockt | blockt | **Neubau** (wird gerechnet) | blockt (`ORA-54026`) | blockt |
| Identity-Modus | **`SET GENERATED …`** | — | — | **`MODIFY … AS IDENTITY`** | blockt |
| Identity entfernen | **`DROP IDENTITY`** | **`MODIFY COLUMN`** (Schluessel) | — | **`MODIFY … DROP IDENTITY`** + `NOT NULL` | blockt |
| Identity hinzufuegen | blockt (Sequenz kollidiert) | **`MODIFY … AUTO_INCREMENT`** (Schluessel) | — | blockt (`ORA-30673`) | blockt |

Drei Dinge, die die Messung erzwungen hat:

- **PostgreSQL blockt das Hinzufuegen einer Identity**, obwohl der Server die
  Anweisung annimmt: die neue Sequenz beginnt bei 1, und der naechste `INSERT`
  scheitert an `duplicate key value violates unique constraint`. Eine
  Migration, die die Tabelle still unbrauchbar zuruecklaesst, wird nicht
  gerendert. MySQL macht es von selbst richtig (der Zaehler setzt ueber dem
  Bestand auf).
- **Oracle nimmt der Spalte mit der Identity ihr implizites `NOT NULL`.** Ohne
  eine zweite Anweisung endete der Lauf in Drift; der Renderer zieht den Zwang
  nach, wenn das Soll die Spalte weiter als Pflichtfeld fuehrt.
- **Die Umkehrbarkeit haengt am Uebergang**, nicht am Operationstyp: ein
  Ausdruckswechsel ist umkehrbar, das Entfernen einer Identity oder einer
  Berechnung nicht (die Gegenrichtung kann kein Server sicher). Der Mapper
  setzt `reversibility` deshalb je Fall.

**Und wo die Identity ueberhaupt steht.** PostgreSQL, MySQL und SQLite falten
`identifier` + `auto_increment` und `generation: identity` auf dieselbe Spalte;
ihr Reverse liefert die Typ-Schreibweise. Oracle ist der einzige, dessen
Reverse die Identity mit ihrem Modus in `generation` zurueckgibt — dort sind
die Identity-Uebergaenge aus einem zurueckgelesenen Schema erreichbar, anderswo
nur aus einem handgeschriebenen Soll.

**Nebenbefund, mitbehoben:** eine PostgreSQL-Identity-Spalte, die **nicht** im
Primaerschluessel liegt und kein `bigint` ist, verlor ihre Identity im Reverse
vollstaendig (`type=Integer, generation=null, default=null`) — ein
`schema generate` daraus erzeugte eine Spalte ohne Generator. Zwei Zweige des
Typ-Mappers fingen sie nicht: der eine nur `bigint`, der andere nur den
Primaerschluessel.

## Was offen bleibt

- **PostgreSQLs `ADD GENERATED … AS IDENTITY` sauber fahren.** Es braeuchte ein
  Nachziehen der Sequenz auf `max(spalte)` — eine **Daten**anweisung im
  DDL-Plan, und damit eine eigene Entscheidung (sie beruehrt denselben
  Sequenzstand, um den sich das Preserve-Fenster kuemmert).
- **Der Kind-Wechsel auf MySQL, Oracle und SQL Server.** Alle drei lehnen ihn in
  place ab; der Weg waere ein Spaltentausch mit Datenkopie, also ein eigener
  Operationstyp mit eigenem Risikoprofil.
- **Das Verhaeltnis zu `AlterColumnType`.** Der Weg ueber den Typ
  (`integer` → `identifier`) funktioniert und ist dokumentiert; auf drei
  Dialekten ist er der einzige, der aus einem zurueckgelesenen Schema
  entsteht.

## Aktivierungsbedingung

Fuer die Restfläche: ein belegter Bedarf. Was gebaut ist, laeuft; was blockt,
sagt den gemessenen Grund und nennt den Weg, den der Server kann.
