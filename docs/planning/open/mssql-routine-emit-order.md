---
id: mssql-routine-emit-order
title: "MSSQL: Funktionen werden in Map-Reihenfolge emittiert, nicht nach Abhängigkeit"
status: open
---

# MSSQL: Funktionen werden in Map-Reihenfolge emittiert

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
