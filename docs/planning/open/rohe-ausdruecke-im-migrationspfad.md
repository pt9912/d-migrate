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

## Nachtrag (2026-09-12): ein fuenftes Feld, und ein besserer Ort

Derselbe Konsument fand ein Feld, das die Aufzaehlung oben nicht kennt:
`DefaultValue.FunctionCall.name`. Die Spec fuehrt es als **uebersetzten
Namen**, der Reverse legt dort aber Servertext ab, wenn er die Funktion nicht
erkennt — `ARRAY['NEW'::order_status]` aus PostgreSQL, woertlich nach T-SQL
kopiert und dabei zu `…]()` verstuemmelt.

Behoben fuer den generate-Pfad, und zwar **nicht** je Dialekt: `DEFAULT` wird
an einem Dutzend Stellen gerendert (SQLite und PostgreSQL an je drei). Der
Filter sitzt stattdessen in `AbstractDdlGenerator.generate` — der einen Stelle,
durch die jeder generate-Lauf aller fuenf Dialekte geht. Das Schema wird dort
einmal gefiltert, danach kann kein Renderer den Text mehr sehen.

**Damit ist Weg 2 oben nicht mehr nur eine Idee, sondern erprobt.** Ein
Durchgang vor dem Rendern kostet weniger als vier weitere Aufrufstellen und
haelt die Aussage an einem Ort. Wer diesen Schnitt baut, sollte pruefen, ob die
heutigen per-Dialekt-Pruefungen fuer CHECK und Berechnung dorthin wandern —
dann faellt die Verdopplung zwischen generate- und migrate-Renderern mit weg.

Fuer den **migrate**-Pfad steht das alles weiterhin aus, jetzt fuer fuenf
Felder statt vier.

## Herkunft

Nebenbefund beim Schließen des Konsumentenbefunds zu berechneten Spalten
(2026-09-12): der generate-Pfad wurde vollständig gemacht, der migrate-Pfad
blieb, wie er war — und das soll nicht unbenannt bleiben.
