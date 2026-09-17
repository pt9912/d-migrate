# `schema compare`: unbenannte Indizes über die kanonische Form zuordnen

> **Status:** Befund / Korrektur ohne Eigner-Frage (2026-09-17).
> **Trigger:** Grenze aus P5 des Compare-Slices
> [`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md)
> („Grenze, bewusst nicht gebaut"). Der Plan
> [`compare-toleranzprofil.md`](../next/compare-toleranzprofil.md) hat den Punkt
> geprüft und **nicht** als Toleranz aufgenommen („Geprüft und nicht
> aufgenommen"): die Schreibweise-Faltung ist schon Vertrag
> ([ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md)),
> dass der Zuordnungsschlüssel sie nicht nutzt, ist eine Lücke in der
> bestehenden Regel. Er verlangt dafür einen eigenen Eintrag nach der
> Graduation des Compare-Slices — das ist dieser.
> **Aktivierungsbedingung:** Ein Anwender mit handgeschriebenen unbenannten
> Indizes, die sich nur in der Schreibweise ihres Prädikats unterscheiden, oder
> der nächste Slice am `TableIndexComparator`.

## Befund

Ein **unbenannter** Index wird über einen Schlüssel zugeordnet, der das
**rohe** Prädikat enthält
([`TableIndexComparator`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/TableIndexComparator.kt),
`indexKey`). Seit P5 faltet `schema compare` die Schreibweise des Prädikats
(`RawTextFolding.index`) — aber erst **nach** der Zuordnung. Zwei unbenannte
Indizes, deren Prädikat sich nur in der Schreibweise unterscheidet, erscheinen
deshalb als entfernt + hinzugefügt statt als unverändert.

**Reichweite:** Reverses benennen jeden Index; betroffen sind nur
handgeschriebene Dateien. Das Ergebnis ist konservativ (ein Fund zu viel, kein
versteckter Unterschied).

## Was eine Korrektur beachten muss

- **Nur `schema compare`.** Der Zuordnungsschlüssel dient auch dem
  zielbewussten Vergleich; `schema migrate` und der Fingerabdruck vergleichen
  rohen Text wortgleich (ADR 0056, neu gefasste Entscheidung 1). Die Faltung
  darf dort nicht in den Schlüssel wandern.
- **Die Cast-Regel braucht den Spaltentyp beider Seiten** (P9). Ein Schlüssel,
  der das kanonische Prädikat trägt, muss mit den Spaltentypen der jeweiligen
  Seite gebildet werden — sonst setzt er gleich, was der Vergleich danach
  wieder unterscheidet.
- **Der Rückzug** (Kommentar, Dollar-Quoting, Backslash …) muss im Schlüssel
  genauso greifen wie im Vergleich: ein Prädikat, bei dem die Faltung sich
  zurückzieht, bleibt wortgleich.
- **Der Pfad bleibt:** der Fund-Pfad eines unbenannten Index ist
  `IndexDefinition.keyLabels` (Pfad-Grammatik in `spec/cli-spec.md`), nicht das
  Prädikat; daran ändert die Korrektur nichts.
