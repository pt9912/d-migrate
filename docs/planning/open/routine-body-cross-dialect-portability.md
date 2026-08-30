---
id: routine-body-cross-dialect-portability
title: "Routinen-Rümpfe werden bei Dialektwechsel übersprungen, nicht beurteilt"
status: open
---

# Routinen-Rümpfe werden bei Dialektwechsel übersprungen, nicht beurteilt

## Befund

Für **Sichten** beurteilt `ViewQueryTransformer.assessPortability` den Rumpf
inhaltlich: was portierbar ist, wird übertragen (teils umgeschrieben), was
nicht, meldet `E053`.

Für **Funktionen, Prozeduren und Trigger** gibt es diese Beurteilung nicht.
Alle vier Dialekte prüfen stattdessen nur die Herkunft:

```kotlin
sourceDialect != null && sourceDialect != "<ziel>" -> Unrenderable(...)   // E053
```

Damit gilt: jeder Rumpf aus einem fremden Dialekt fällt weg, auch der, der
wörtlich gültig wäre (`RETURN a + b`, ein `INSERT` ohne Dialekt-Eigenheiten).
Und umgekehrt: ein Rumpf **ohne** `sourceDialect` geht ungeprüft durch, egal
was darin steht.

## Warum das heute vertretbar ist

Prozedurale Sprachen unterscheiden sich stärker als `SELECT`-Dialekte —
PL/pgSQL, T-SQL und MySQLs Prozedursprache teilen weder Blockstruktur noch
Fehlerbehandlung noch Variablendeklaration. Ein Übersetzer ist ungleich mehr
Arbeit als der Sicht-Transformer, und die konservative Ablehnung erzeugt
wenigstens kein ungültiges DDL am Ziel.

## Was der Schnitt klären muss

- **Ob eine inhaltliche Beurteilung ohne Übersetzung Wert hat.** „Dieser Rumpf
  wäre auch auf dem Ziel gültig" ist eine kleinere Frage als „übersetze ihn",
  und sie würde den Fall retten, der heute unnötig wegfällt.
- **Was mit dem fehlenden `sourceDialect` geschieht.** Heute ist er die
  Freikarte; wenn eine Beurteilung kommt, sollte sie auch dann greifen.
- **Ob das ein ADR ist.** Die Entscheidung „Routinen-Rümpfe werden grundsätzlich
  nicht übersetzt" wäre eine dauerhafte Grenze und gehört dann festgehalten,
  nicht in einen Plan.

## Herkunft

Aus Slice 9 des MSSQL-Plans, der die Frage ausdrücklich als cross-dialektal
ausgeschnitten hat.
