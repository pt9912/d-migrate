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
| gewoehnlich → berechnet | „is not a generated column" | „'Changing the STORED status' is not supported"² | `ORA-54026`² | Syntaxfehler² | — |
| berechnet → gewoehnlich | `DROP EXPRESSION` **geht** | „'Changing the STORED status' is not supported"² | `MODIFY (c <typ>)` **geht**, aendert aber nichts² | „Cannot alter column … because it is 'COMPUTED'"² | — |
| Identity `ALWAYS` ↔ `BY DEFAULT` | `SET GENERATED …` **geht** | (Modus gibt es nicht) | `MODIFY (c GENERATED … AS IDENTITY)` **geht** | ungemessen | Identity steckt im **Typ** |
| Identity → gewoehnlich | `DROP IDENTITY` **geht** | `MODIFY c INT` **geht** (Schluesselspalte) | `MODIFY (c DROP IDENTITY)` **geht** | ungemessen | s. o. |
| gewoehnlich → Identity | braucht vorher `NOT NULL`, danach **geht** (`ADD GENERATED … AS IDENTITY`)¹ | nur als Schluesselspalte | `ORA-30673` | „Incorrect syntax near the keyword 'IDENTITY'" | s. o. |

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
| berechnet → gewoehnlich | **`DROP EXPRESSION`** (Wert bleibt) | **Spaltentausch**² (Wert bleibt) | **Neubau** (Wert bleibt) | **Spaltentausch**² (Wert bleibt) | **Spaltentausch**² (Wert bleibt) |
| gewoehnlich → berechnet | blockt | **Spaltentausch**² (wird gerechnet) | **Neubau** (wird gerechnet) | **Spaltentausch**² (wird gerechnet) | **Spaltentausch**² (wird gerechnet) |
| Identity-Modus | **`SET GENERATED …`** | — | — | **`MODIFY … AS IDENTITY`** | blockt |
| Identity entfernen | **`DROP IDENTITY`** | **`MODIFY COLUMN`** (Schluessel) | — | **`MODIFY … DROP IDENTITY`** + `NOT NULL` | blockt |
| Identity hinzufuegen | **`SET NOT NULL` + `ADD GENERATED … AS IDENTITY` + `setval`-Nachzug**¹ | **`MODIFY … AUTO_INCREMENT`** (Schluessel) | — | blockt (`ORA-30673`) | blockt |

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

- ~~**PostgreSQLs `ADD GENERATED … AS IDENTITY` sauber fahren.**~~ —
  **erledigt 2026-09-13**, siehe unten.
- ~~**Der Kind-Wechsel auf MySQL, Oracle und SQL Server.**~~ —
  **erledigt 2026-09-13**, siehe unten.
- **Das Verhaeltnis zu `AlterColumnType`.** Der Weg ueber den Typ
  (`integer` → `identifier`) funktioniert und ist dokumentiert; auf drei
  Dialekten ist er der einzige, der aus einem zurueckgelesenen Schema
  entsteht.

## Aktivierungsbedingung

Fuer die Restfläche: ein belegter Bedarf. Was gebaut ist, laeuft; was blockt,
sagt den gemessenen Grund und nennt den Weg, den der Server kann.

## Nachgemessen 2026-09-13

Zwischen dem Bau (2026-09-12) und heute lag der Capability-Tables-Umbau —
`DialectCapabilities` und ihre vier Geschwistertabellen verloren ihre
`when (dialect)`-Zweige, die Werte wanderten in die fuenf Treibermodule. Das
ist genau die Art Aenderung, die diese Tabelle still haette brechen koennen,
ohne dass ein Test es haette merken muessen — deshalb erneut gegen echte
Server gefahren, nicht nur gegen den Code gelesen.

Alle fuenf zustaendigen Integrationsspecs liefen frisch durch (kein
`UP-TO-DATE`, kein leerer `--tests`-Filter — der haette den Bau hart
abbrechen lassen) und gruen:

- `PostgresGenerationTransitionMigrateIntegrationTest`
- `PostgresIdentityShapeIntegrationTest`
- `MysqlModifyColumnDeclarationIntegrationTest`
- `OracleGenerationTransitionMigrateIntegrationTest`
- `SqliteGenerationTransitionIntegrationTest`

Die zweite Tabelle oben (was d-migrate tatsaechlich rendert oder blockt) gilt
damit unveraendert **fuer den Stand von 2026-09-13 vormittags** — der
Kind-Wechsel-Bau am selben Tag hat sie seitdem weiter veraendert, siehe
unten. Die erste Tabelle (rohe Server-Annahme) haengt nicht am Code, sondern
an den Server-Versionen selbst (PostgreSQL 18.6, MySQL 9.7.2, Oracle 23, SQL
Server 2025) — ohne Versionswechsel keine neue Messung noetig.

## Gebaut 2026-09-13: PostgreSQL `ADD GENERATED … AS IDENTITY`

¹ Der bis dahin geblockte Uebergang „gewoehnlich → Identity" rendert jetzt.
Live gegen 18.6 gemessen, drei Anweisungen fuer eine Operation
(`PostgresDiffComputedColumnOps.renderIdentityAdd`):

1. `ALTER TABLE … ALTER COLUMN … SET NOT NULL` — PostgreSQL verlangt das vor
   `ADD GENERATED` (`column "x" … must be declared NOT NULL before identity
   can be added`, live gemessen); auf einer bereits `NOT NULL`-Spalte ist es
   ein folgenloses No-op.
2. `ALTER TABLE … ALTER COLUMN … ADD GENERATED { ALWAYS | BY DEFAULT } AS
   IDENTITY` — legt die Sequenz an, die bei 1 beginnt.
3. Die Sequenz auf den Bestand nachziehen:
   `setval(pg_get_serial_sequence(…), GREATEST(COALESCE(max(spalte), 1), 1),
   max(spalte) IS NOT NULL AND max(spalte) >= 1)`. Zwei live gemessene
   Randfaelle, die ein blosses `setval(…, max(spalte))` nicht abdeckt:
   - **leere Tabelle** — `max` ist `NULL`, `COALESCE` faengt das ab
     (Sequenz startet bei 1, `is_called = false`).
   - **ausschliesslich negative Bestandswerte** — `setval` mit einem Wert
     unter `MINVALUE` (1) scheitert mit `value … is out of bounds`;
     `GREATEST` haelt den Wert bei 1, `is_called = false` laesst den
     naechsten `INSERT` dort beginnen. Kollisionsfrei, weil negative
     Bestandswerte nie mit aufsteigenden IDs ab 1 ueberlappen.

Die urspruengliche Ablehnung (`IDENTITY_ADD_WOULD_COLLIDE`) entfaellt
ersatzlos — der Uebergang braucht keinen manuellen Eingriff mehr.

**Nicht angefasst:** die beiden verbleibenden Punkte in „Was offen bleibt"
(Kind-Wechsel auf MySQL/Oracle/SQL Server, Verhaeltnis zu `AlterColumnType`)
— ausserhalb des hier gepruften Bedarfs.

## Gebaut 2026-09-13: der Kind-Wechsel per Spaltentausch

² Der bis dahin auf MySQL/Oracle/SQL Server geblockte Uebergang (gewoehnlich
↔ berechnet) rendert jetzt — auf allen drei Dialekten ueber denselben
Spaltentausch, live gemessen:

- **gewoehnlich → berechnet**: kein Kopieren noetig — der Wert entsteht
  sofort aus der Formel, wenn die Spalte neu angelegt wird (live bestaetigt
  auf allen drei Servern: `ADD COLUMN`/`ADD (…)` mit `GENERATED ALWAYS AS
  (…)`/`AS (…)` rechnet ueber dem Bestand). `DROP COLUMN` + `ADD` unter
  demselben Namen.
- **berechnet → gewoehnlich**: der eingefrorene Wert muss erhalten bleiben —
  echter Spaltentausch mit Datenkopie ueber eine Zwischenspalte (`ADD`
  nullbar + `UPDATE`-Kopie + `DROP` der berechneten Spalte + Umbenennen),
  danach eine Nachdeklaration auf die volle Zieldeklaration (NOT
  NULL/DEFAULT nachziehen — dieselbe Formel, auf einer bereits passenden
  Spalte ein folgenloses No-op).

**Sicherheits-Waechter statt Index-/FK-Wiederherstellung.** Der Tausch
laeuft nur, wenn die Spalte **keine** Encumbrance traegt: Primaerschluessel-
Mitgliedschaft, UNIQUE, Index, oder Fremdschluessel-Bezug (ausgehend
**oder** eingehend). Traegt sie eine, bleibt es beim Blocker, mit dem
gemessenen Grund und dem konkreten Objekt, das im Weg steht — statt den
Index/die Constraint abzubauen und danach neu anzulegen, was ein eigener,
groesserer Entwurf waere. Views, die die Spalte lesen, kann das neutrale
Modell nicht sehen; SQL Servers eigene Blockmeldung nennt dieses Risiko
explizit ("leaves a view over it silently broken") — dieselbe Grenze gilt
jetzt fuer den Tausch. Dialektunabhaengiger Waechter:
`ColumnSwapGuard` (`driver-common`, `dev.dmigrate.driver.metadata`).

**Automatisch gerendert, `--allow-destructive` gegatet — kein neues
CLI-Flag.** Beide Richtungen tragen `destructive = true` an jeder
Anweisung; das bindet die Operation an das **bereits bestehende**
`--allow-destructive`-Flag (`MigrateDestructiveGuard`), denselben Weg, den
jede andere destruktive Operation schon nimmt. Kein neues
`DdlDialectContext`-Feld, kein neuer Modus-Enum wie bei der
Hash-Partitions-/Named-Sequence-Emulation — der Tausch erreicht den
exakten Zielzustand (echte berechnete/gewoehnliche Spalte), anders als
eine Emulation, die dauerhaft eine Krücke bleibt.

**MSSQL hat jetzt eine eigene Integrationsspec**
(`MssqlGenerationTransitionMigrateIntegrationTest`) — vorher gab es keine,
weil die Zeile „blockt" die Abwesenheit eines Renderer-Zweigs war, kein
Live-Verhalten. Seit `AlterColumnGeneration` erstmals gerendert wird, gibt
es etwas Live-Nachzuweisendes.

Berührte Stellen:

- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/metadata/ColumnSwapGuard.kt`
  (neu, geteilt — der Waechter fragt nur das neutrale Modell, nicht den Server)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffTableOps.kt`
- `adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleDiffTableOps.kt`
  (Dispatch) + `OracleDiffGenerationSwapOps.kt` (neu, wegen Detekts
  `TooManyFunctions`)
- `adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlDiffGenerationOps.kt`
  (neu, aus demselben Grund — `AlterColumnGeneration` war dort zuvor gar
  nicht im Dispatch verdrahtet) +
  `adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlDiffDdlGenerator.kt`

Alle drei Render-Kontexte (`emit()`) haben jetzt einen optionalen
`riskOverride`-Parameter statt einer eigenen `emitColumnSwap`-Methode —
dieselbe Buchfuehrung wie bei `emitRebuild`, ohne eine zusaetzliche Methode
je Kontext.
