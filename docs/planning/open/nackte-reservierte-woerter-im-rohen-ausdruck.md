# Ein nacktes reserviertes Wort im rohen Ausdruck — nur MySQL quotiert zurück

> **Status:** Befund (gemessen 2026-09-18), ohne Scope.
> **Trigger:** die Review von
> [`../done/reader-treue-1-matrix-abnahme.md`](../done/reader-treue-1-matrix-abnahme.md)
> (Befund M1). Der MySQL-Teil ist dort behoben, **bis auf eine Wortklasse**
> (unten, „Was auf MySQL bleibt"); dieser Eintrag hält beides fest: diesen Rest
> und das, was derselbe Mechanismus auf den übrigen Zielen anrichtet.
> **Aktivierungsbedingung:** Eine Entscheidung darüber, ob PostgreSQL, SQL
> Server, Oracle und SQLite dieselbe Rückquotierung bekommen, oder ob der Fall
> ausdrücklich beim Server bleibt (dann gehört die Grenze in
> `spec/ddl-generation-rules.md`, „Roher Ausdruckstext").

## Der Mechanismus

Ein Reader schreibt einen Bezeichner, den der Server quotiert zurückgibt
(`` `key` ``, `[key]`), über `NeutralExpressionIdentifier` in den neutralen
Ausdruck: rein kleingeschrieben bleibt er **nackt**, jeder andere wird
`"Name"`. Das neutrale Modell kennt den Zieldialekt nicht und kann deshalb
nicht wissen, dass `key` oder `order` dort ein Schlüsselwort ist.

Beim Rendern quotiert seit diesem Slice **nur der MySQL-Generator** solche
Wörter zurück (`MysqlReservedWords`). Für die übrigen Ziele geht der nackte
Name unverändert in die DDL.

## Gemessen (2026-09-18)

| Ziel | Anweisung | Ausgang |
| --- | --- | --- |
| MySQL 9.7.2 | `CREATE TABLE … (\`key\` INT NOT NULL, CHECK (key > 0))` | `ERROR 1064` — **behoben**, der Generator quotiert zurück |
| MySQL 9.7.2 und 8.0.46 | dasselbe mit `interval`, `case`, `null` … statt `key` | bleibt (s. „Was auf MySQL bleibt") |
| PostgreSQL 18 | `CREATE TABLE … ("order" int NOT NULL, CHECK (order > 0))` | `ERROR: syntax error at or near "order"` |
| PostgreSQL 18 | dasselbe mit `key` statt `order` | angenommen — `key` ist in PostgreSQL **nicht** reserviert |
| SQL Server 2025 | `CREATE TABLE … ([key] int NOT NULL, CHECK (key > 0))` | `Msg 156: Incorrect syntax near the keyword 'key'` |

Die Wortlisten sind je Dialekt verschieden; dasselbe Schema scheitert also auf
einem Ziel und läuft auf dem anderen.

## Warum das zählt

Vor P6 war der Weg **laut und harmlos**: der MySQL-Reverse lieferte den
Ausdruck mit Backticks, `RawSqlExpressionPortability` erkannte das
Backtick-Quoting, und das Ziel bekam ein übersprungenes Objekt mit `E053`,
einer Note und einem Eintrag in `skipped_objects`. Seit P6 die Backticks
entfernt, sieht der Ausdruck portabel aus — und ein einzelnes CHECK bringt die
**ganze** `CREATE TABLE` am Zielserver zu Fall. Aus einem benannten Verlust
wurde ein Abbruch.

Dass die Compare-Matrix das nicht zeigt, liegt allein an den Fixtures: keine
Seed-Spalte heißt wie ein reserviertes Wort.

## Was auf MySQL bleibt (gemessen 2026-09-18, 9.7.2 und 8.0.46)

Die Rückquotierung hängt seit der Abschluss-Verifikation von Plan 1 an der
**Stellung**, nicht am Wort allein (`MysqlReservedWords`): in
Operandenstellung wird quotiert, in Operatorstellung nicht. Damit ist die
dauerhafte Ausnahme von 48 auf **17** Wörter geschrumpft — die, die auch am
**Anfang eines Operanden** Syntax sind:

```
not  case  binary  interval  distinct  null  true  false
current_date  current_time  current_timestamp  current_user
localtime  localtimestamp  utc_date  utc_time  utc_timestamp
```

Je Wort gemessen (`CREATE TABLE … (\`w\` INT, CHECK (w > 0))`, beide
Serverversionen, gleiches Ergebnis):

| Wortklasse | nackt | quotiert |
| --- | --- | --- |
| `not`, `case`, `binary`, `interval`, `distinct` und die neun Werte-Funktionen | `ERROR 1064` | angenommen |
| `null`, `true`, `false` | **angenommen — und still**: MySQL liest das Literal. `CHECK (null > 0)` ist damit wirkungslos, die verletzende Zeile wurde eingefügt | angenommen |

Quotieren lässt sich keines davon: dieselbe Messung von der anderen Seite
zeigt, dass jedes in seiner Syntaxrolle am Anfang eines Operanden steht —
`` `binary` note = 'x' ``, `` a is `null` ``, `` d + `interval` 1 year_month ``,
`` count(`distinct` a) `` und `` `current_timestamp` > d `` scheitern alle
(`ERROR 1064` bzw. `1054`). Ohne Parser ist die Stellung dort nicht zu
entscheiden, und ein falsch gesetztes Quoting wäre schlimmer als ein fehlendes.

**Zweite, kleinere Restfläche:** ein Wort aus der Operatorklasse (`mod`,
`default`, `match`, `between` …) als Spaltenname direkt hinter einem
Präfixoperator — `NOT default`, `CASE mod WHEN …`, `BINARY match` — steht dort
zwar als Operand, wird aber nicht als solcher erkannt: hinter `NOT` kann
ebenso gut Syntax stehen (`a NOT LIKE 'x'`), und die Regel gibt im Zweifel
nicht zu quotieren.

**Und eine Beobachtung nebenan** (im Code geprüft, nicht gemessen): der
Typname des zweiten `CONVERT`-Arguments ist für die Validierung eine Spalte —
`CheckExpressionColumns` überspringt einen Typnamen nur hinter `::` und `AS`,
nicht hinter dem Komma eines `CONVERT(`. Ein CHECK
`convert(total, unsigned) > 0` in einer Schemadatei bekäme damit `E012` für
`unsigned`. Der MySQL-Generator schreibt diese Form korrekt (der Typname
bleibt nackt); die Validierung zieht nicht nach.

## Zu klären

1. **Bekommt jedes Ziel seine Rückquotierung?** Der MySQL-Weg ist der
   Präzedenzfall: eine gemessene Wortliste (`information_schema.KEYWORDS`
   mit `RESERVED = 1`; PostgreSQL hat `pg_get_keywords()`, SQL Server und
   Oracle ihre Katalogsichten), abzüglich der Wörter, die in einem **skalaren**
   Ausdruck Syntax sind, plus die drei Stellungsregeln (Operandenstellung,
   vor `(`, der Typname eines `CAST`/`CONVERT`).
   Anders als bei MySQL gibt es dafür in diesen Generatoren heute **keine**
   gemeinsame Stelle: der rohe Text geht an jeder Rendering-Stelle einzeln
   durch. Der Umbau wäre also zuerst eine Naht.
2. **Oder bleibt der Fall beim Server?** Dann ist die Grenze zu schreiben —
   und die Frage zu beantworten, warum ein `E053`-Pfad, den es für
   Backtick-Quoting gibt, für diesen Fall nicht existiert.
3. **Ein dritter Weg wäre die Quelle:** `NeutralExpressionIdentifier` quotiert,
   sobald ein Name in **irgendeinem** der fünf Ziele reserviert ist. Das ändert
   den neutralen Text für alle Reader (und damit Fingerabdruck, Goldens und
   Matrix-Pins) und trifft Oracle, wo `"order"` kleingeschrieben etwas anderes
   ist als `ORDER`. Zu prüfen, nicht zu setzen.
