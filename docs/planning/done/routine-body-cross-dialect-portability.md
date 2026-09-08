---
id: routine-body-cross-dialect-portability
title: "Routinen-Rümpfe werden bei Dialektwechsel übersprungen, nicht beurteilt"
status: resolved
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

## Geloest

Die drei Fragen unten sind beantwortet, die Entscheidung steht in
[`ADR 0054`](../../adr/0054-routinen-ruempfe-werden-nicht-uebersetzt.md):

- **Hat eine inhaltliche Beurteilung ohne Uebersetzung Wert? Nein.** Bei
  `SELECT` markiert `::` eine Eigenheit; in einem Rumpf ist die Abwesenheit
  jeder Eigenheit kein Beleg fuer Gueltigkeit. `RETURN 1;` ist in PL/pgSQL
  vollstaendig, in T-SQL nur im richtigen Rahmen, in MySQL nur innerhalb
  `BEGIN … END`. Eine tragfaehige Beurteilung muesste die Grammatik kennen —
  dann waere sie fast der Uebersetzer. Dazu die Asymmetrie: ein falsches
  „portabel" scheitert erst beim `CREATE` am Ziel, mitten in einem
  `migrate --execute` nach implizit committeten Vorgaengern; ein falsches
  „nicht portabel" erzeugt eine benannte Nacharbeit.
- **Was geschieht ohne `source_dialect`?** Er bleibt die Freikarte — aber als
  benannte Entscheidung, nicht als Nebenwirkung: das ist die dokumentierte Art,
  eine Routine von Hand zu fuehren, und ihn zu pruefen setzte die Grammatik
  voraus, die es nach Punkt 1 nicht gibt. Der Preis steht im ADR.
- **Ist das ein ADR? Ja** — die Grenze ist dauerhaft, nicht ein Zwischenstand.

Beim Bauen fiel ein Fehler auf, den die Frage selbst nicht enthielt: die
Herkunftspruefung verglich **Zeichen** statt aufzuloesen. `source_dialect:
postgres` gegen ein PostgreSQL-Ziel fiel mit `E053` weg, `postgresql` nicht —
gemessen ueber `PostgresDdlGenerator`, an allen fuenf Aliassen. Der Wert ist im
Schema-Format eine freie Zeichenkette, und der Sichten-Pfad loeste sie schon
immer auf. Die zehn Stellen liegen jetzt auf einer gemeinsamen
`RoutineBodyOrigin`.

## Was der Schnitt klaeren musste

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
