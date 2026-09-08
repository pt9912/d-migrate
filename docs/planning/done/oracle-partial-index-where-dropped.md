---
id: oracle-partial-index-where-dropped
title: "Oracle verwirft die WHERE-Klausel eines partiellen Index stillschweigend"
status: resolved
---

# Partieller Index verliert seine WHERE-Klausel auf Oracle

> **Erledigt, ueber Richtung 1** — beide Haelften.
>
> - **Gemeldet.** Der Generate-Pfad schreibt `W155`, wenn ein partieller
>   Index als voller angelegt wird, und nennt bei `unique` ausdruecklich,
>   dass sich die Zusicherung inhaltlich aendert.
> - **Nicht mehr driftend.** `carriesPartialIndexPredicate` blendet das
>   Praedikat fuer Oracle aus dem Fingerabdruck und dem Vergleich aus —
>   dieselbe Mechanik wie bei der Text-Search-Konfiguration und den
>   Bitmap-/Partitions-Projektionen. `MigrationFingerprint.ALGORITHM` geht
>   damit auf `schema-fingerprint-v14`.
>
> **MySQL bleibt bewusst auf `true`.** Es traegt das Praedikat ebenso wenig,
> ueberspringt den Index aber **ganz** (`E057`) statt einen schwaecheren
> anzulegen. Es gibt dort nichts zu versoehnen, und die Projektion haette
> einen Schaden: ein von Hand angelegter voller Index saehe aus wie der
> verlangte partielle.
>
> Richtung 2 (ausdrucksbasierte Nachbildung ueber
> `CASE WHEN … THEN … END`) bleibt offen und haengt weiter an der
> Ausdrucksdarstellung aus Slice 6b. Sie ist jetzt aber eine Verbesserung,
> keine Reparatur: der Verlust ist gemeldet und driftet nicht mehr.
>
> Nebenbefund mitbehoben: die Oracle-Regeln in `ddl-generation-rules.md`
> behaupteten noch, jede Tabelle mit Geometriespalten werde vor der
> Generierung geblockt (`E052`) — seit Slice 12 falsch.

## Befund

`OracleIndexDdlBuilder.render` wertete `IndexDefinition.where` nicht aus. Ein
partieller Index — in PostgreSQL und SQLite ein gewoehnliches Mittel — wurde
auf Oracle deshalb als **voller** Index angelegt, ohne Meldung.

Das ist kein Fehler in der erzeugten DDL (sie laeuft), aber ein stiller
Bedeutungsunterschied: der Index deckt mehr Zeilen ab als verlangt, ist
groesser, und bei `unique` aendert sich die Zusicherung sogar inhaltlich —
aus „hoechstens eine aktive Zeile je Schluessel" wird „hoechstens eine Zeile
je Schluessel ueberhaupt".

Gefunden als Nebenbefund beim Review von Slice 6a (2026-09-06); nicht von
diesem Slice verursacht, sondern seit Slice 2 vorhanden.

## Zweite Haelfte: Drift

Der Verlust war nicht nur still, er **driftete** auch. `IndexDefinition.where`
geht in den Fingerabdruck ein (`MigrationFingerprint`), und
`capabilityIndexCanonicalizer` blendete es nicht aus. Ein Soll-Schema mit
einem partiellen Index gegen ein Oracle-Ziel meldete deshalb nach jedem
`migrate --execute` Drift — und weil `TableComparator` das Feld ebenfalls
fuehrt, plante der naechste Lauf denselben Index erneut.

Dieselbe Familie wie
[`fulltext-config-fingerprint-lossy-dialects.md`](fulltext-config-fingerprint-lossy-dialects.md)
und die Bitmap-/Partitions-Projektionen.

## Live verifiziert

`OracleIndexReverseIntegrationTest` gegen echtes Oracle: das erzeugte DDL
traegt `W155`, Oracle nimmt es an, der Reverse liest den Index **ohne**
Praedikat zurueck, und beide Seiten sind unter der Oracle-Projektion gleich.
Gegengeprueft, indem `carriesPartialIndexPredicate` fuer Oracle auf `true`
gesetzt wurde: genau diese Spezifikation faellt, die uebrigen drei bleiben
gruen.
