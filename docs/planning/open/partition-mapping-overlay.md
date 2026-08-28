# Vorabklärung: `partition-mapping`-Overlay

> **Status:** Vorabklärung / Trigger Watch (2026-08-28)
> **Trigger:** Beim Schneiden der SQL-Server-Partitionierung fielen zwei Fälle
> an, die kein Dialekt-Slice lösen kann, weil das Werkzeug die nötige Kenntnis
> nicht hat — sie liegt beim Anwender.
> **Aktivierungsbedingung:** Wird priorisiert → `next/`-Plan mit Format,
> Verifikation und CLI-Naht; sonst Trigger-Watch.

## Befund

SQL Server nummeriert Partitionen und kennt nur `RANGE`. Daraus folgen zwei
Verluste, die beide dieselbe Gestalt haben: **die Identität ist bekannt, aber
nicht ableitbar.**

- **Kindnamen.** PostgreSQL und MySQL benennen Partitionen, SQL Server nicht.
  Ein Reverse kann `p_2024` nicht zurückgeben; er vergibt `p1`, `p2`, … und
  meldet das (`R346`). Welche Nummer welchem Namen entsprach, weiß nur, wer die
  Migration fährt.
- **`LIST` → `RANGE`.** Eine LIST-Partitionierung ist genau dann als RANGE
  ausdrückbar, wenn die Wertemengen in Sortierreihenfolge zusammenhängend und
  überschneidungsfrei sind: `(1,2), (3,4)` wird zu Grenzen `3, 5`. Bei
  `('DE','FR'), ('US','CA')` geht es nicht — die Mengen verschränken sich in
  jeder Ordnung.

## Warum ein Overlay und kein Konfigurationsschalter

`MigrationOverlayKinds` führt bereits `using-expression` und `rename-mapping`.
Beide lösen dieselbe Lage: Wissen, das nur der Anwender hat. Ein
`partition-mapping` wäre die dritte Art derselben Sorte.

Der Unterschied zu einem Schalter ist der entscheidende: **ein Overlay stellt
Identität her, es lockert keine Gleichheit.** `schema compare` bliebe streng und
erführe nur, dass Partition 1 des Ziels dieselbe ist wie `p_2024` der Quelle.
Die Grenze aus [ADR 0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md)
bliebe unangetastet. Ein Konfigurationsschalter, der entscheidet, ob zwei
Partitionssätze gleich sind, wäre das Gegenteil davon — dieselbe Migration wäre
je nach Datei sauber oder driftend, und der Fingerabdruck im Rollback-Artefakt
hinge an einer Einstellung statt am Schema.

Das Format bringt zwei Eigenschaften mit, die hier genau passen:
`sourceFingerprint`/`targetFingerprint` binden das Overlay an das Schemapaar,
für das es gilt, und `overlayHash`/`createdByVersion` machen die Zuordnung
nachvollziehbar.

## Was den LIST-Fall besonders macht

Die Zuordnung ist **verifizierbar**. Der Anwender liefert die Abbildung von
Wertemengen auf Grenzen, und das Werkzeug prüft sie: sortieren, auf
Zusammenhang und Überschneidungsfreiheit prüfen, bei Verschränkung ablehnen.
Eine Zuordnung, die falsches Routing erzeugte, käme nicht durch. Das ist mehr,
als ein Namens-Mapping leisten kann — und der Grund, warum sich der Aufwand
über den reinen Namensfall hinaus lohnt.

## Arbeitspakete (Skizze)

1. Overlay-Art `partition-mapping` samt Eintragsform (Kind ↔ Nummer bzw.
   Wertemenge ↔ Grenze).
2. Verifikation für den LIST-Fall; Ablehnung mit benanntem Grund.
3. Naht im Reverse (Namen aus dem Overlay statt aus dem Muster) und im Diff.
4. CLI: Overlay laden, wie bei `rename-mapping`.

## Reichweite

Nicht MSSQL-spezifisch. Der Namensfall trifft jeden Dialekt, dessen Ziel
Partitionen anders identifiziert als die Quelle; der LIST-Fall jeden, der LIST
nicht kennt.
