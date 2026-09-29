# SQL Server: eine `smallint`/`tinyint`/`decimal`-Identity liest in eine Form, aus der sich nicht erzeugen lässt

> **Status:** Befund / Vorabklärung — gemessen beim Review von Plan 3
> (2026-09-29). Es ist der planmandierte S5-Nachbarfall: der Plan verlangte
> vor dem Bau, „ob die SQL-Server-Seite dieselben Nachbarfälle kennt wie S1
> (Identity ohne Primärschlüssel, mehrspaltiger Primärschlüssel, `smallint`)"
> — geprüft und gebaut wurde nur `int`.
> **Trigger:** Review-Finding M1 zu Plan 3 des Reader-Umbrellas
> ([`../in-progress/reader-treue.md`](../in-progress/reader-treue.md)), Paket
> S5.
> **Aktivierungsbedingung:** eine Eigner-Entscheidung nach dem Muster von S6
> (PostgreSQL, Weg 1: melden statt Modell erweitern) oder eine
> Modellerweiterung nach Weg 2 (`E130` lockern, Render-Regel je Dialekt);
> aktiviert beim nächsten Reader-Treue- oder Identity-Posten.
> **Severity:** P3 — schmal (schmale IDENTITY-Typen sind in T-SQL-Idiomen
> selten), aber derselbe Defekt, den S6 auf der PostgreSQL-Seite benannt hat,
> nur ohne den Code.

## Befund

`MssqlTypeMapping.mapIdentity` liest `smallint`/`tinyint` IDENTITY als
`SmallInt` + `generation: identity (ALWAYS)` und `decimal` IDENTITY als
`Decimal` + `generation: identity (ALWAYS)` (`MssqlTypeMapping.kt:87-96`).
Das neutrale Modell trägt eine Erzeugungsangabe nur auf `integer` und
`biginteger` — der Validator lehnt genau diese Form mit `E130` ab
(`SchemaColumnValidationRules.kt:275-283`, „identity generation is only
valid for integer or biginteger columns"). Aus dem SQL-Server-Reverse einer
`SMALLINT IDENTITY`-Spalte lässt sich nichts erzeugen.

Auf der PostgreSQL-Seite hat S6 denselben Zweig zugestutzt: eine
`smallint`-Identity liest dort `identifier` mit `R406` — verlustbehaftet
(`identifier` trägt keinen Modus), aber validierbar und erzeugbar. Die
SQL-Server-Seite steht auf dem alten Stand; ein Test
(`MssqlTypeMappingTest.kt:104-108`, „smallint identity keeps SmallInt with
identity generation") hält die Lesart als Soll fest.

`spec/type-mapping.md` 6.2 trägt seit dem Review den `E130`-Vorbehalt in der
Zeile; er macht den Zustand ehrlich, hebt ihn aber nicht auf.

## Die zwei Wege

1. **Melden (Weg 1 wie S6).** Die schmalen IDENTITY-Spalten lesen als
   `identifier` mit einem Code nach der Art von `R406` — der Modus geht
   verloren, die Datei bleibt erzeugbar. Billig, ändert kein Modell.
2. **Modell erweitern (Weg 2).** `E130` wird auf die schmalen Breiten
   gelockert, und jeder Generator bekommt eine Render-Regel. SQL Server
   rendert `SMALLINT`/`TINYINT`-Identitys nativ, Oracle (`NUMBER(4)`) ebenso;
   MySQL und SQLite kennen keine Entsprechung und melden (`W163`-Familie).

S6 hat die gleiche Frage für PostgreSQL mit Weg 1 beantwortet; dieselbe
Antwort liegt hier nahe, steht aber nicht fest — die Messung „was tun die
fünf Generatoren" wäre Teil des Pakets.

## Belegstellen

- `adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlTypeMapping.kt`
  (`mapIdentity`, Zeile 87-96).
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaColumnValidationRules.kt`
  (`E130`, Zeile 275-283).
- `adapters/driven/driver-mssql/src/test/kotlin/dev/dmigrate/driver/mssql/MssqlTypeMappingTest.kt`
  (Zeile 104-108).
- Plan 3, Paket S5
  ([`../done/reader-treue-3-spatial.md`](../done/reader-treue-3-spatial.md)), und
  [`mssql-integer-identity-pk-verliert-den-modus.md`](mssql-integer-identity-pk-verliert-den-modus.md)
  (der `int`-Fall, geschlossen).
