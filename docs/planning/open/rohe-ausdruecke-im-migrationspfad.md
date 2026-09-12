---
id: rohe-ausdruecke-im-migrationspfad
title: "Der Migrationspfad prüft rohe Ausdrücke nicht auf Portabilität"
status: open
---

# Der Migrationspfad prüft rohe Ausdrücke nicht auf Portabilität

## Der Stand

`RawSqlExpressionPortability` beurteilt einen rohen Ausdruck gegen das Ziel und
verwirft ihn benannt (`E053`), statt ihn weiterzureichen. Im **generate**-Pfad
gilt das für alle fünf Dialekte und alle vier Textfelder — CHECK/EXCLUDE,
Index-Prädikat, Ausdrucks-Schlüssel und, seit dem Konsumentenbefund vom
2026-09-12, auch die Berechnung einer Spalte.

Im **migrate**-Pfad gilt es nicht durchgehend. Die Diff-Renderer bauen ihre
Spaltenzeilen selbst:

| Dialekt | generate | migrate |
| --- | --- | --- |
| SQL Server | ✅ | ✅ (derselbe `renderColumn`) |
| Oracle | ✅ | ✅ (derselbe `generateColumnSql`) |
| PostgreSQL | ✅ | ❌ `PostgresDiffSqlBuilders.columnLine` |
| MySQL | ✅ | ❌ `MysqlDiffSqlBuilders.columnLine` |
| SQLite | ✅ | ❌ `SqliteDiffSqlBuilders.columnLine` (auch im Tabellen-Neubau) |

## Warum das kein stiller Verlust ist

Der Unterschied zum generate-Pfad ist wesentlich: `schema generate` schreibt
eine Datei, die jemand später anwendet — dort fällt ungültiger Text erst weit
weg vom Werkzeug auf. `schema migrate --execute` schickt die Anweisung dagegen
sofort an den Server, und der lehnt sie mit einem Syntaxfehler ab. **Laut, nicht
still** — aber mitten im Lauf, nachdem vorherige Anweisungen schon angewandt
wurden.

## Die Gabelung

1. **Dieselbe Prüfung in die drei Diff-Renderer.** Nahe an dem, was schon
   dasteht, aber es sind drei weitere Stellen — und bei SQLite zusätzlich der
   Tabellen-Neubau, dessen Spaltenzeilen aus `buildCreateTempSql` kommen, wo
   heute kein Meldungskanal liegt.
2. **Eine Vorprüfung im Planer statt in den Renderern.** Ein Durchgang über das
   Soll-Schema vor dem Rendern: jeder rohe Ausdruck gegen den Zieldialekt, eine
   Stelle für alle Dialekte und beide Pfade. Damit verschwände auch die heutige
   Verdopplung zwischen generate- und migrate-Renderern.

Weg 2 ist der bessere Zuschnitt, aber ein eigener Entwurf: er muss beantworten,
wie sich eine Vorprüfung zu den bestehenden Preflights verhält
(`CheckPreflight`, `SqliteCastPreflight`) und ob sie blockt oder meldet.

## Herkunft

Nebenbefund beim Schließen des Konsumentenbefunds zu berechneten Spalten
(2026-09-12): der generate-Pfad wurde vollständig gemacht, der migrate-Pfad
blieb, wie er war — und das soll nicht unbenannt bleiben.
