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

## Richtung (Eigner-Entscheidung 2026-09-06)

Das gehoert **in die Einstellungen**, nicht in eine Sonderbehandlung im
Oracle-Schreibpfad. Der Mechanismus dafuer existiert bereits:
[`dialect-preference-mechanism.md`](../../../spec/dialect-preference-mechanism.md)
loest „inhaerente Mehrdeutigkeiten" durch eine **deklarierte
Anwender-Praeferenz** statt durch eine Heuristik, mit
`reverse.sqlite.autoincrement_width` als lebendem Praezedenzfall. Seine
Prinzipien passen unveraendert:

- *Deklaration statt Heuristik* — nur der Anwender kennt die Absicht.
- *Konservativer Default* — ohne Deklaration bleibt das Verhalten, wie es
  ist (der Transfer scheitert), also keine stille Datenaenderung.
- *Nicht stumm* — weicht der Lauf per Praeferenz vom Default ab, haelt
  eine INFO-Note im Report das fest.
- Praezedenz: **CLI-Flag > `.d-migrate.yaml` > Default**.

Ein Unterschied bleibt und ist beim Umsetzen zu entscheiden: jener
Mechanismus ist auf **Reverse**-Mehrdeutigkeiten zugeschnitten (die
Datenbank traegt die Information nicht, die die Wahl entscheiden wuerde).
Der leere String ist die **Schreib**-Seite — die Quelle ist eindeutig, das
Ziel kann sie nicht darstellen. Entweder bekommt die Spec einen
Schreib-Abschnitt mit eigener Registry, oder ihr Geltungsbereich wird auf
beide Richtungen erweitert. Eine eigene, dritte Mechanik waere falsch.

Unabhaengig davon bleiben zwei Punkte, die **keine** Daten aendern und
deshalb ohnehin gelten sollten:

1. **Benannte Diagnose** statt roher `ORA-01400`: die Meldung muss sagen,
   dass die Quelle einen leeren String fuehrt und Oracle ihn mit NULL
   gleichsetzt.
2. **Preflight** vor dem ersten Schreibzugriff. Ohne ihn bricht der Lauf
   mitten drin ab (gemessen: nach `actor`) und laesst ein halb befuelltes
   Ziel zurueck; mit ihm nennt er alle betroffenen Spalten auf einmal.

## Auswirkung auf den Sample-DB-Harness

Das Oracle-Leg (`make sample-db-cross-smoke-pg2ora`, Slice 3b) laeuft bis
einschliesslich DDL-Anwendung und Tabellenzahl-Paritaet durch und bricht
dann an dieser Stelle ab. Es ist damit **rot**, solange die Praeferenz
nicht umgesetzt ist — bewusst, statt den Transferschritt zu ueberspringen
und einen gruenen Lauf zu melden, der die Haelfte nicht geprueft hat.

Sobald es sie gibt, **deklariert das Leg sie in seiner
`.d-migrate.yaml`** — sichtbar und begruendet, statt dass das Smoke-Skript
die fuenf Zeilen still in der Quelle umschreibt. Damit ist auch **4b**
entblockt, das einen durchlaufenden Transfer voraussetzt.

## Herkunft

Beim Bau des Oracle-Legs (Slice 3b) aufgefallen — dem ersten Lauf eines
realen Schemas samt Daten gegen Oracle. Derselbe Lauf hat fuenf
Generator-Defekte aufgedeckt, die alle vorher behoben wurden.
