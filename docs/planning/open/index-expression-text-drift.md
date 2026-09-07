---
id: index-expression-text-drift
title: "Der Text eines Ausdrucks-Index wird nicht kanonisiert und driftet deshalb nach jedem migrate"
status: open
---

# Ausdrucks-Index: der Text driftet

## Befund

Slice 6b hat Indizes ueber einem Ausdruck eingefuehrt
(`IndexColumn.expression`, roher SQL-Text wie `IndexDefinition.where`). Der
Fingerabdruck (`MigrationFingerprint.appendIndex`) und der Comparator
(`TableComparator.indexKey`) hashen diesen Text **wortgleich**.

Die Server geben ihn aber nicht wortgleich zurueck. Live gemessen
(2026-09-07):

| geschrieben | zurueckgelesen |
| --- | --- |
| PostgreSQL `upper(nm)` | `upper(nm::text)` |
| PostgreSQL `nm \|\| 'x'` | `(nm::text \|\| 'x'::text)` |
| Oracle `UPPER("nm")` | `UPPER("nm")` |
| Oracle `("amt" + 2)` | `"amt"+2` (Klammern und Leerzeichen weg) |

Folge: nach `migrate --execute` gegen PostgreSQL oder Oracle weicht der
zurueckgelesene Ausdruck vom gewuenschten ab. Der Post-Compare meldet Drift,
und weil der Comparator dasselbe Feld fuehrt, plant der **naechste** Lauf
denselben Index erneut — eine Migration, die nie konvergiert.

## Warum es nicht im Slice geloest wurde

Anders als bei den bisherigen Faellen dieser Familie (Bitmap-Zugriffsmethode,
untere Partitionsgrenze, Volltext-Konfiguration) hilft **kein** Ausblenden:
der Ausdruck IST die Aussage des Index. Ihn aus dem Fingerabdruck zu nehmen
hiesse, eine echte Aenderung nicht mehr zu sehen.

Was bliebe, ist eine **Kanonisierung** des SQL-Texts — Casts strippen,
Klammern und Leerzeichen normieren, Bezeichner-Quoting angleichen. Das ist
ein eigener Entwurf mit eigenem Risiko: zu aggressiv normiert verschluckt er
echte Unterschiede, zu zaghaft loest er das Problem nicht. Und er ist je
Dialekt verschieden, weil die Deparse-Form es ist.

## Zu klaeren

1. Ob eine Kanonisierung ueberhaupt der richtige Weg ist, oder ob ein
   Ausdrucks-Index im Fingerabdruck besser ueber eine **Server-Sicht**
   verglichen wuerde (beide Seiten durch denselben Deparser).
2. Ob `IndexDefinition.where` und der Rumpf einer Sicht dasselbe Problem
   haben — sehr wahrscheinlich ja, und dann ist es ein gemeinsamer Entwurf.
3. Bis dahin: `schema generate` und `schema reverse` sind unberuehrt; nur der
   wiederholte `migrate --execute` gegen ein Ziel mit Ausdrucks-Index
   konvergiert nicht.
