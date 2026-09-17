# Ein übersprungener Schlüssel lässt den Fremdschlüssel darauf stehen

> **Status:** Befund (gemessen 2026-09-17), ohne Scope.
> **Trigger:** der SQLite-Seed von P0/P11 in der Compare-Matrix
> ([`../done/reader-treue-1-matrix-abnahme.md`](../done/reader-treue-1-matrix-abnahme.md)).
> Die Zelle SQLite → SQL Server wechselte dabei von `Msg 2714` auf `Msg 1776`
> — ein zweiter, eigener Grund.
> **Aktivierungsbedingung:** Eine Entscheidung darüber, was der Generator tut,
> wenn ein Fremdschlüssel auf einen Schlüssel verweist, den derselbe Lauf
> **übersprungen** hat: mitnehmen und scheitern lassen (heute), ebenfalls
> überspringen (mit eigener Meldung) oder den Lauf anhalten.

## Gemessen

SQLite → SQL Server, `schema generate --target mssql` aus einem SQLite-Reverse:

1. Die Elterntabelle trägt eine mehrspaltige `UNIQUE`-Klausel über zwei
   Textspalten. Der SQLite-Reverse kennt keine Länge, also sind sie
   unbegrenzt.
2. SQL Server nimmt eine unbegrenzte Textspalte (`NVARCHAR(MAX)`) nicht als
   Schlüsselspalte. Der Generator überspringt die `UNIQUE`-Klausel — laut, mit
   `E057` im Report.
3. Der **Fremdschlüssel**, der genau auf diese Spalten verweist, bleibt
   stehen. Der Server lehnt die DDL ab:
   `Msg 1776: There are no primary or candidate keys in the referenced table …
   that match the referencing column list in the foreign key`.

Damit scheitert der ganze Lauf an einer Anweisung, deren Voraussetzung derselbe
Lauf zwei Anweisungen vorher weggelassen hat.

## Warum das zählt

`E057` sagt „der Schlüssel entfällt", und der Report nennt ihn. Was er nicht
sagt: dass mit ihm jeder Fremdschlüssel darauf unbrauchbar wird. Der Anwender
liest einen Report mit einer Warnung und bekommt ein DDL, das der Server
komplett ablehnt — die Diagnose steht an der falschen Stelle.

Die Regel „keine partielle DDL" gilt hier nicht: die Tabelle entsteht, nur die
Anweisung, die den Fremdschlüssel anlegt, ist unerfüllbar.

## Zu klären

1. **Zieht ein übersprungener Schlüssel seine Fremdschlüssel mit?** Dann
   bräuchte der Fremdschlüssel eine eigene Meldung (welcher Schlüssel fehlt und
   warum), damit der Verlust nicht doppelt still ist.
2. **Oder hält der Lauf an?** Ein Fremdschlüssel ohne Schlüssel ist keine
   Degradierung, sondern eine Zusage, die das Ziel nicht halten kann —
   `action_required` wäre die andere vertretbare Antwort.
3. Betrifft nicht nur SQL Server: dieselbe Lage entsteht überall, wo eine
   `UNIQUE`-Klausel wegen einer Fähigkeitsgrenze übersprungen wird (MySQL
   verlangt für `TEXT` eine Präfixlänge, `W125`).
