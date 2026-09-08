---
id: single-column-constraint-synthetic-name
title: "Einspaltige UNIQUE-/FK-Constraints werden mit erfundenen Namen gedroppt (dialektuebergreifend)"
status: done
---

# Einspaltige UNIQUE-/FK-Constraints werden mit erfundenen Namen gedroppt

> **Gebaut (2026-09-08).** Der Name steht im Modell (`unique_constraint` an
> der Spalte, `constraint` am `references`-Block), alle fuenf Leser fuellen
> ihn, der Vergleich meldet einen abweichenden Namen, und der Renderer
> erhaelt ihn. Was beim Bauen dazukam, steht unter „Beim Bauen gemessen".
>
> **Entschieden: der Name wird im Modell gefuehrt, und ob er zur Identitaet
> gehoert, sagt eine Dialekt-Faehigkeit.** Nicht der Katalog-Lookup zur
> Renderzeit — der machte den Renderer verbindungsabhaengig und braeuchte eine
> eigene Probe-Stage.
>
> Der Grund, warum die Modellaenderung allein nicht reicht, steht unten: die
> Faltung sitzt an **drei** Projektionen, und sie ist dort Absicht. Der
> Fingerabdruck sagt es im Klartext — „the constraint name is not observable
> state on every dialect (SQLite synthesises `fk_N`)". Ein im Modell
> gefuehrter Name wuerde also sofort wieder weggefaltet.
>
> Die eigentliche Frage ist deshalb nicht „welches Feld", sondern **ob der
> Constraint-Name beobachtbarer Zustand ist** — und die Antwort ist
> dialektabhaengig: PostgreSQL, MySQL und SQL Server geben den vergebenen
> Namen zurueck; SQLite synthetisiert ihn; Oracle liefert bei inline
> deklarierten Constraints ein `SYS_C…`, das ein Soll-Schema nie tragen kann.
>
> Genau diese Sorte Frage beantwortet `DialectCapabilities` bereits dreimal in
> derselben Familie — `namesFullTextIndexes`, `namesPartitions`,
> `namesIdentitySequences`. Ein viertes Flag derselben Art nimmt den Namen
> dort aus der Projektion, wo der Server ihn nicht fuehrt, und behaelt ihn,
> wo er ihn fuehrt. Kein ADR noetig: die drei Vorgaenger tragen ihre
> Begruendung ebenfalls in der Faehigkeit selbst.
>
> **Was dabei mit herauskommt:** `schema compare` meldet auf PostgreSQL,
> MySQL und SQL Server einen abweichenden UNIQUE-Namen, statt zu schweigen.
>
> ---
>
> **Teilweise erledigt — Richtung 1 gebaut:**
>
> `NormalizedConstraints` fuehrt den Ursprungsnamen jetzt mit, und
> `syntheticUniqueConstraint`/`syntheticFkConstraint` verwenden ihn, wenn es
> einen gab. Der Name kommt dabei von der **Seite, die den Constraint
> traegt**: beim Entfernen der Bestand, beim Hinzufuegen das Soll — sonst
> stuende im Drop ein Name, den die Datenbank nicht kennt. Die Faltung
> selbst bleibt unveraendert: ein benannter Tabellen-Constraint und ein
> `column.unique` beschreiben weiter dasselbe und planen keine Aenderung.
>
> Damit sind die Faelle geloest, in denen die Seite den Constraint **benannt**
> fuehrt — Datei-zu-Datei, `schema compare`, und jedes handgeschriebene
> Ist-Schema.
>
> ## Was offen bleibt, und warum es eine Entscheidung braucht
>
> Der **Live-Fall**: der Reverse faltet einen einspaltigen UNIQUE auf
> `column.unique` und verwirft den Katalognamen
> (`SchemaReaderUtils.singleColumnUniqueFromConstraints`, alle fuenf
> Dialekte). Dort gibt es im Modell keinen Namen, den Richtung 1 mitfuehren
> koennte.
>
> **Gemessen (Oracle 23c), und es korrigiert dieses Ticket:**
>
> | Beobachtung | Ergebnis |
> | --- | --- |
> | `DROP CONSTRAINT "_unique_email"` (erfunden) | `ORA-02443` |
> | `DROP CONSTRAINT "SYS_C009788"` (Katalogname) | funktioniert |
> | `DROP UNIQUE ("email")` | **funktioniert** |
>
> Die dritte Zeile widerlegt Richtung 3 fuer Oracle: die Form gibt es. Sie
> ist aber Oracle-eigen — PostgreSQL, MySQL und SQL Server haben keine
> Entsprechung, und der Katalogname einer inline deklarierten Spalte ist
> ausserdem ein `SYS_C…`, den ein Soll-Schema nie tragen kann.
>
> Die verbleibende Wahl ist deshalb zwischen zwei Wegen mit echten Kosten,
> und beide gehoeren dem Eigner:
>
> - **Katalog-Lookup zur Renderzeit** (Richtung 2, wie MSSQL ihn fuer
>   Default-Constraints schon faehrt). Loest alle Faelle, macht den Renderer
>   aber verbindungsabhaengig — architektonisch eine eigene Probe-Stage neben
>   `CheckPreflightStage`.
> - **Den Namen im neutralen Modell fuehren** (ein Feld an der Spalte, oder
>   der Reverse legt den Constraint benannt statt als Spalteneigenschaft ab).
>   Loest ebenfalls alle Faelle, aendert aber das Modell und damit
>   Reverse-Ausgabe, Fingerabdruck und Spec.
>
> `OracleSingleColumnConstraintIntegrationTest` haelt die drei gemessenen
> Aussagen fest, damit die Entscheidung nicht erneut auf Vermutungen
> aufsetzt.
>
> **Unabhaengig wiedergefunden (2026-09-08)**, an PostgreSQL 16: ein
> `CONSTRAINT uq_product_sku UNIQUE (sku)` kommt als `sku unique=true`
> zurueck, und `constraints` fuehrt ihn gar nicht mehr — der Katalogname ist
> vor dem Comparator weg, nicht erst in ihm. Dabei kam eine Folge heraus, die
> oben noch nicht stand: `schema compare` gegen eine Datenbank, deren
> UNIQUE-Constraints **anders heissen**, meldet den Unterschied nicht. Je nach
> Anwendungsfall ist das gewollt (der Constraint ist derselbe) oder ein
> blinder Fleck (der Name ist Teil des Vertrags, etwa fuer Anwendungen, die
> ihn in der Fehlerbehandlung lesen). Auch diese Frage haengt an der
> Entscheidung oben.

## Befund

`TableComparator.normalizeConstraints`
(`hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/TableComparator.kt`)
zieht **jedes** einspaltige UNIQUE und **jeden** einspaltigen Fremdschluessel
auf `singleColumnUnique`/`singleColumnForeignKeys` zusammen — nicht nur die,
die als `column.unique`/`column.references` modelliert sind, sondern auch
**benannte Tabellen-Constraints**. Der Name geht dabei verloren.

`compareConstraints` materialisiert das Delta anschliessend ueber
`syntheticUniqueConstraint`/`syntheticFkConstraint` neu und erfindet dafuer
die Namen `_unique_<spalte>` bzw. `_fk_<spalte>`. Nichts bildet sie auf den
Katalognamen zurueck (repo-weite Suche nach `_unique_` trifft nur diese eine
Datei).

Ein `DropConstraint` fuer ein einspaltiges UNIQUE rendert deshalb:

```sql
ALTER TABLE "users" DROP CONSTRAINT "_unique_email";
```

— gegen eine echte Datenbank `ORA-02443` (Oracle), analog in den anderen
Dialekten. Die Down-Richtung ist namenssymmetrisch, aber beide Seiten sind
von der Datenbank entkoppelt. Mehrspaltige Constraints sind nicht betroffen:
sie behalten ihren echten Namen.

## Reichweite: dialektuebergreifend, aelter als Oracle

Der Renderer ist nicht die Ursache — alle drei bereits ausgelieferten
Dialekte geben denselben Payload-Namen aus
(`PostgresDiffOtherOps`, `MssqlDiffObjectOps`), und der echte Name geht
schon beim Reverse verloren (`PostgresSchemaStructureReaders`,
`MssqlSchemaReader`, `MysqlSchemaReader`, `OracleSchemaReader`). Oracle
Sub-Slice 5b ist nur der Slice, in dem der Dialekt das Verhalten erbt; die
Entscheidung, einspaltige Constraints auf Spalteneigenschaften zu
normalisieren, ist aelter und bewusst (sie macht `column.unique` und einen
gleichwertigen Tabellen-Constraint vergleichbar).

## Moegliche Loesungsrichtungen (nicht vorentschieden)

1. **Namen mitfuehren statt erfinden**: `NormalizedConstraints` um den
   Ursprungsnamen erweitern, damit `syntheticUniqueConstraint` ihn
   wiederverwenden kann, wenn es einen gab. Loest den Fall „benannter
   Tabellen-Constraint", nicht den Fall `column.unique` (dort gibt es
   keinen Namen im Modell).
2. **Katalog-Lookup im Renderer**, wie MSSQL ihn fuer Default-Constraints
   schon faehrt (`sys.default_constraints`): den tatsaechlichen Namen zur
   Renderzeit nachschlagen. Loest beide Faelle, macht den Renderer aber
   verbindungsabhaengig.
3. **Constraint spaltenweise droppen**, wo der Dialekt das kann. *(Korrigiert
   2026-09-08: Oracle hat `ALTER TABLE … DROP UNIQUE (spalte)` sehr wohl —
   live gemessen. Der Weg scheidet nicht an Oracle aus, sondern daran, dass
   PostgreSQL, MySQL und SQL Server keine Entsprechung haben.)*

Aktivierungsbedingung: ein `schema migrate --execute`, das einen
einspaltigen UNIQUE-/FK-Constraint entfernt. Bis dahin blockt nichts —
der Fehler entsteht erst beim Ausfuehren.

## Beim Bauen gemessen (2026-09-08)

Zwei Dinge, die der Entwurf so nicht vorhergesehen hatte:

- **Der Name muss der eines Constraints sein, nicht der eines Index.** SQL
  Server und Oracle heben auch gewoehnliche Unique-*Indizes* auf
  `column.unique`; deren Namen mit `DROP CONSTRAINT` abzubauen scheitert. Die
  Leser fuellen das Feld deshalb aus der **Constraint**-Abfrage ihres Dialekts
  (`pg_constraint`, `information_schema.table_constraints`,
  `sys.key_constraints` mit `type = 'UQ'`, `all_constraints` mit
  `constraint_type = 'U'`) — drei davon gab es noch nicht. Wo der Unique aus
  einem blossen Index stammt, bleibt das Feld leer, und es bleibt beim alten
  Verhalten.
- **Die inline benannte Form gibt es in MySQL nicht.** `email VARCHAR(50)
  CONSTRAINT uq UNIQUE` ist dort ein Syntaxfehler (live gemessen), die
  Tabellenform geht. Ein benannter einspaltiger UNIQUE wird deshalb in **allen**
  Dialekten als Tabellen-Constraint gerendert statt in zwei Formen gepflegt.

**Der Fingerabdruck bleibt, wie er war** — und das ist Absicht, kein
Versaeumnis: er projiziert **ein** Schema fuer sich und kann die Regel „nur
vergleichen, wenn beide Seiten einen Namen nennen" gar nicht ausdruecken.
Naehme er den Namen auf, driftete jedes handgeschriebene `unique: true` gegen
jede echte Datenbank. Die Faltung des Namens bleibt dort also stehen; der
Vergleich, der beide Seiten sieht, entscheidet ueber den Namen.

Live abgenommen gegen PostgreSQL 16: der gelesene Katalogname landet im
Modell, der erfundene Name scheitert am Server, der gelesene trifft, und ein
erzeugtes `CONSTRAINT … UNIQUE (…)` kommt unter demselben Namen zurueck.
