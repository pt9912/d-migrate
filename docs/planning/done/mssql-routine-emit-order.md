---
id: mssql-routine-emit-order
title: "MSSQL: Funktionen werden in Map-Reihenfolge emittiert, nicht nach Abhängigkeit"
status: done
---

# MSSQL: Funktionen werden in Map-Reihenfolge emittiert

> **Behoben 2026-08-30 — die Diagnose unten nannte den falschen Pfad.** Gemessen:
> der **Generate**-Pfad ordnet längst richtig. `AbstractDdlGenerator` reicht
> `generateFunctions` eine bereits sortierte Map
> (`DdlGenerationSupport.sortFunctionsByDependencies`, Kanten aus dem Rumpftext).
> Ich hatte `MssqlDdlGenerator.generateFunctions` isoliert gelesen.
>
> Der Defekt lag im **Migrate**-Pfad: dort ordnet `RoutineDependencyAnalyzer`
> nach `DependencyInfo`, und die füllte der MSSQL-Reverse nicht. Der Analyzer
> meldete das Paar sogar als `UNSAFE_DEPENDENCY_PAIR` — als Warnung, während die
> Reihenfolge falsch blieb.
>
> Der Reverse liest die Kanten jetzt aus `sys.sql_expression_dependencies`:
> Tabellen, Sichten und Routinen, für Funktionen, Prozeduren **und** Trigger.
> Der Katalog löst den Verweis auf ein Objekt auf, wo ein regulärer Ausdruck
> ihn raten müsste.

## Lage

`MssqlDdlGenerator.generateFunctions` gibt die Funktionen in
Iterationsreihenfolge der Map aus. Für die anderen drei Dialekte ist das
folgenlos; für SQL Server nicht: die verzögerte Namensauflösung deckt
**Tabellen** ab, nicht benutzerdefinierte Funktionen. Ruft der Rumpf von `fn_a`
die Funktion `fn_b` auf und sortiert `fn_a` zuerst, scheitert das Anwenden mit

```
Msg 4121: Cannot find either column "dbo" or the user-defined function or
aggregate "dbo.fn_b", or the name is ambiguous.
```

Aufgefallen im Review zu Slice 9b. Vor 9b war der Punkt unerreichbar, weil
MSSQL Routinen gar nicht gerendert hat.

## Warum es nicht nebenbei mitläuft

`FunctionDefinition.dependencies.functions` gäbe die Ordnung her, aber der
MSSQL-Reverse füllt das Feld nicht: Slice 9a liest Rumpf und Signatur, keine
Abhängigkeiten. Für einen MSSQL-Round-Trip ist die Information also gar nicht
da. Sie stünde in `sys.sql_expression_dependencies` — das zu lesen ist der
eigentliche Arbeitsanteil, nicht das Sortieren.

## Umfang

- Reverse: `sys.sql_expression_dependencies` je Routine lesen und
  `dependencies.functions` füllen.
- Generate: topologisch sortieren; ein Zyklus (in T-SQL nur über
  `CREATE`-`ALTER`-Umwege baubar) wird gemeldet, nicht geraten.
- Live: zwei voneinander abhängige Funktionen erzeugen, umgekehrt sortiert
  einlesen, anwenden.

## Nicht-Ziel

Sichten sind nicht betroffen — für sie gibt es die Abhängigkeitsordnung schon.
