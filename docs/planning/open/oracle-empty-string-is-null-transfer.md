---
id: oracle-empty-string-is-null-transfer
title: "Transfer nach Oracle scheitert an leeren Strings in NOT-NULL-Spalten — mit einer rohen ORA-Meldung"
status: open
---

# Leerer String trifft `NOT NULL` (Oracle)

## Befund

Oracle behandelt den leeren String `''` als **NULL**. Eine Quellzeile, die
in PostgreSQL einen leeren String in einer `NOT NULL`-Textspalte fuehrt,
laesst sich deshalb nicht nach Oracle uebertragen.

Der Anwender sieht heute:

```
Error [pagila_pg]: Transfer error: ORA-01400: cannot insert NULL into
("DMIGRATE"."address"."phone")
```

Das nennt weder die Ursache (der Wert ist nicht NULL, er ist leer) noch
die Handlungsmoeglichkeit.

## Umfang (gemessen an Pagila, 2026-09-06)

Von 15 `NOT NULL`-Textspalten sind **zwei** betroffen, mit zusammen
**fuenf** Zeilen:

```
address.district: 3 empty of 603 rows
address.phone:    2 empty of 603 rows
```

Alle uebrigen (`customer.first_name`, `film.title`, `city.city`, …) sind
leer-frei. Der Fall ist also selten, aber nicht konstruiert — er steckt in
einem der meistgenutzten Beispieldatensaetze.

## Warum das nicht "einfach ein Datenfehler" ist

Der Wert ist in der Quelle gueltig: PostgreSQL, MySQL, SQLite und SQL
Server unterscheiden `''` von `NULL`. Nur Oracle tut es nicht. Der
Konflikt entsteht **erst durch die Zielwahl**, und genau solche
Unterschiede sichtbar zu machen ist die Aufgabe des Werkzeugs.

Der Generate-Pfad kann es nicht vorhersagen: er sieht die Spalte
(`text NOT NULL`), nicht die Daten. Der Transfer-Pfad sieht die Daten.

## Moegliche Loesungsrichtungen (nicht vorentschieden)

1. **Benannte Diagnose statt roher ORA-Meldung.** Der Oracle-Schreibpfad
   faengt `ORA-01400` ab und meldet, dass die Quelle einen leeren String
   in einer NOT-NULL-Spalte fuehrt und Oracle beides gleichsetzt.
   Aendert nichts am Ausgang, aber der Anwender weiss, was zu tun ist.
2. **Preflight.** Vor dem Transfer je NOT-NULL-Textspalte auf leere
   Strings pruefen und den Lauf mit einer Liste der betroffenen
   Spalten/Zeilen abbrechen, bevor die Haelfte der Tabellen geschrieben
   ist.
3. **Opt-in-Ersetzung** (z. B. `--oracle-empty-string <wert>`), die den
   leeren String beim Schreiben durch einen definierten Wert ersetzt.
   Aendert Daten und braucht deshalb eine ausdrueckliche Ansage.
4. **Nichts im Code**, nur Doku — dann bleibt die rohe ORA-Meldung.

Richtung 1 und 2 aendern keine Daten und sind unabhaengig voneinander
umsetzbar; 3 ist eine Produktentscheidung.

## Auswirkung auf den Sample-DB-Harness

Das Oracle-Leg (`make sample-db-cross-smoke-pg2ora`, Slice 3b) laeuft bis
einschliesslich DDL-Anwendung und Tabellenzahl-Paritaet durch und bricht
dann an dieser Stelle ab. Es ist damit **rot**, solange die Entscheidung
aussteht — bewusst, statt den Transferschritt zu ueberspringen und einen
gruenen Lauf zu melden, der die Haelfte nicht geprueft hat.

## Herkunft

Beim Bau des Oracle-Legs (Slice 3b) aufgefallen — dem ersten Lauf eines
realen Schemas samt Daten gegen Oracle. Derselbe Lauf hat fuenf
Generator-Defekte aufgedeckt, die alle vorher behoben wurden.
