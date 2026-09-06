---
id: oracle-partial-index-where-dropped
title: "Oracle verwirft die WHERE-Klausel eines partiellen Index stillschweigend"
status: open
---

# Partieller Index verliert seine WHERE-Klausel auf Oracle

## Befund

`OracleIndexDdlBuilder.render` wertet `IndexDefinition.where` nicht aus. Ein
partieller Index — in PostgreSQL und SQLite ein gewoehnliches Mittel — wird
auf Oracle deshalb als **voller** Index angelegt, ohne Meldung.

Das ist kein Fehler in der erzeugten DDL (sie laeuft), aber ein stiller
Bedeutungsunterschied: der Index deckt mehr Zeilen ab als verlangt, ist
groesser, und bei `unique` aendert sich die Zusicherung sogar inhaltlich —
aus „hoechstens eine aktive Zeile je Schluessel" wird „hoechstens eine Zeile
je Schluessel ueberhaupt".

Gefunden als Nebenbefund beim Review von Slice 6a (2026-09-06); nicht von
diesem Slice verursacht, sondern seit Slice 2 vorhanden.

## Warum es zaehlt

Die uebrigen Dialekte melden solche Verluste. MySQL kennt partielle Indizes
ebenfalls nicht und meldet es (`W128`); SQL Server kann sie (`WHERE`) und
rendert sie. Oracle ist damit der einzige Dialekt, der hier **still**
verliert — und die Hausregel ist „nicht stumm".

## Moegliche Richtungen

1. **Melden statt schweigen** (klein): `W102`/eigener Code beim Rendern,
   Index bleibt voll. Loest den Stille-Befund, nicht den Bedeutungsverlust.
2. **Ausdrucksbasiert nachbilden** (gross): Oracle kann einen partiellen
   Index ueber einen function-based Index simulieren
   (`CASE WHEN <bedingung> THEN <spalte> END`), weil NULL-Schluessel nicht
   im B-Tree landen. Das setzt aber die Ausdrucksdarstellung aus **Slice 6b**
   voraus und ist ohne sie nicht baubar.

Empfehlung: Richtung 1 sofort, Richtung 2 als Frage an Slice 6b anhaengen.
