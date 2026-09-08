---
id: mssql-hash-emulation-not-round-trippable
title: "SQL Servers HASH-Emulation kommt als RANGE zurueck — der Migrate-Plan konvergiert nicht"
status: resolved
---

# Die emulierte HASH-Partitionierung round-trippt nicht

> **Erledigt, ueber Loesungsrichtung 1.** Der Reverse erkennt die Emulation
> an Spaltenname und Ausdruck wieder und liefert `HASH` mit dem
> Fachschluessel; die Eimerspalte faellt aus Spaltenbestand, Primaerschluessel
> und Indizes heraus.
>
> Beim Messen kam ein **zweiter** Grund dazu, aus dem auch RANGE nicht
> konvergierte: SQL Server nummeriert Partitionen, und der Name ging trotzdem
> in den Vergleich ein. Beides zusammen in
> [`mssql-partitionierung-round-trip.md`](../done/mssql-partitionierung-round-trip.md).

## Befund

SQL Server kennt nur RANGE. Im Modus `--mssql-hash-partitions computed_column`
baut d-migrate eine HASH-Partitionierung nach: eine persistierte berechnete
Spalte (`dmg_hash_bucket`) haelt den Eimer, eine RANGE-Funktion schneidet an
den Eimergrenzen. Der Generate-Pfad sagt das mit `W145` an.

Der Reverse kann daraus nichts anderes lesen als das, was dasteht:
`MssqlSchemaReader` erzeugt **ausschliesslich** `PartitionType.RANGE`
(:280, :301). Nach `migrate --execute` unterscheiden sich Soll und Ist
deshalb an drei Stellen zugleich:

| | Soll (Datei) | Ist (Reverse) |
| --- | --- | --- |
| `type` | `HASH` | `RANGE` |
| `key` | die Fachspalte(n) | `dmg_hash_bucket` |
| Spalten | ohne | **mit** `dmg_hash_bucket` |

Die Eimerspalte wird beim Reverse nicht ausgeblendet — anders als etwa die
Sekundaertabellen eines Oracle-Text-Index, die `listTableRefs` gezielt
herausfiltert.

Folge: `schema migrate` plant bei **jedem** Lauf erneut eine
Partitionsaenderung und eine Spaltenoperation, gegen ein Ziel, das genau das
enthaelt, was der vorige Lauf angelegt hat.

## Warum keine Projektion das heilt

`capabilityPartitionCanonicalizer` faltet einzelne **Felder** einer
Partitionierung weg (untere Grenze, Modulus, Remainder). Hier weicht nicht
ein Feld ab, sondern die Art der Partitionierung, ihr Schluessel und der
Spaltenbestand der Tabelle. Eine Feld-Projektion hat dafuer keinen Griff.

Das ist der Grund, warum dieser Befund **nicht** Teil von
`partition-fingerprint-lossy-dialects` war: dort ging es um
`carriesPartitionHashModulus`, und das Flag ist fuer SQL Server richtig auf
`true` — es beschreibt eine Frage, die sich hier gar nicht stellt.

## Reichweite

Nur im ausdruecklich angeforderten Emulationsmodus. Der Default ist
`action_required`: eine HASH-Partitionierung bricht dann mit `E055` ab, und
es entsteht nichts, was driften koennte. Wer die Emulation einschaltet,
bekommt heute eine Migration, die nie fertig wird.

## Moegliche Loesungsrichtungen

1. **Die Emulation im Reverse wiedererkennen.** Die Eimerspalte traegt einen
   reservierten Namen und einen erkennbaren Ausdruck; aus beidem plus den
   Grenzen liesse sich `HASH(modulus)` zurueckgewinnen und die Spalte aus dem
   neutralen Modell heraushalten — dieselbe Bauart wie die
   `dmg_sequences`-Wiedererkennung des SQLite-Reverse
   (`SqliteSequenceReverseSupport`).
2. **Den Emulationsmodus als nicht round-trip-faehig ausweisen** und den
   wiederholten Plan mit benanntem Grund blocken, statt ihn zu emittieren.
   Ehrlich, aber es bleibt eine Migration, die der Anwender nicht abschliessen
   kann.

Richtung 1 ist die eigentliche Antwort; sie ist derselbe Gedanke wie bei
SQLite und hat dort bereits eine Form.

## Herkunft

Aufgefallen bei der Aufnahme fuer
[`fingerprint-und-comparator-projektion.md`](../done/fingerprint-und-comparator-projektion.md),
beim Nachmessen der Behauptung, SQL Server verliere den HASH-Modulus.
