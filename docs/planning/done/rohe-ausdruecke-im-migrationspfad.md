---
id: rohe-ausdruecke-im-migrationspfad
title: "Der Migrationspfad prüft rohe Ausdrücke nicht auf Portabilität"
status: done
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

## Die Gabelung — und der dritte Weg, der gebaut wurde

Der Plan stellte zwei Wege gegeneinander:

1. **Dieselbe Prüfung in die drei Diff-Renderer.** Drei weitere Stellen, bei
   SQLite zusätzlich der Tabellen-Neubau, dessen Spaltenzeilen aus
   `buildCreateTempSql` kommen — dort liegt kein Meldungskanal.
2. **Eine Vorprüfung im Planer.** Eine Stelle für alle Dialekte und beide
   Pfade.

Gebaut wurde **keiner von beiden**, sondern der Ort dazwischen: ein Wächter
**hinter** allen fünf Renderern, in `SchemaMigrateRenderPipeline`, nach dem
Muster von `MigrateDestructiveGuard`.

**Warum nicht Weg 2 (vor dem Planen).** Beim Bauen fiel auf, dass eine
Vorprüfung, die das Soll-Schema *filtert*, im Migrationspfad falsch wäre: der
Vergleich läuft gegen das Ist. Nähme man einer Spalte vorher ihre Berechnung,
vergliche man eine gewöhnliche Spalte gegen eine berechnete — und der Lauf
plante, die Berechnung zu **entfernen**. Im generate-Pfad ist Filtern richtig,
weil dort nichts verglichen wird; hier nicht.

**Warum nicht nach dem Planen, aber vor dem Rendern.** Die Operationen tragen
ihre Identität aus `CanonicalPayload`. Sie nach der ID-Vergabe umzuschreiben
entkoppelte ID und Inhalt.

Bleibt: nichts umschreiben, sondern **urteilen** — über die Operationen, die
wirklich gerendert wurden (`operationsRendered`), und blocken.

## Blocken statt weglassen

Im generate-Pfad entsteht eine Datei, die jemand liest, bevor er sie anwendet;
dort ist Weglassen plus `E053` das Richtige. `schema migrate --execute` schickt
die Anweisung sofort an den Server. Ihn ablehnen zu lassen heißt, **mitten** in
einer Folge bereits angewandter Anweisungen abzubrechen — live gemessen war der
Ausgang ohne Wächter Exit 5 (Post-Compare-Drift) statt eines sauberen Halts.
Mit Wächter: Exit 8, nichts angewandt, die Tabelle existiert nicht einmal halb.

## Was der Wächter mehr abdeckt als die Gabelung versprach

Er sitzt hinter **allen** Renderern, nicht nur den dreien aus der Tabelle —
also auch hinter SQL Server und Oracle, deren Migrationspfad die
generate-Prüfung nur zufällig mit abdeckte (sie teilen sich den Renderer). Und
er prüft alle Stellen, an denen roher Text in eine Anweisung gerät, nicht nur
die berechnete Spalte: CHECK-Ausdruck, Index-Prädikat, Ausdrucks-Schlüssel und
Funktions-Default, in `CreateTable` ebenso wie in `AddColumn`,
`AddConstraint`, `AddIndex`, `AlterColumnGeneration` und
`AlterColumnDefault`.

## Der Preis, benannt

Das Urteil kennt die Grammatik der Dialekte und liegt deshalb im Adapter
(`RawSqlExpressionPortability`). Die Anwendungsschicht darf es nicht sehen — es
ist ein **Port** (`RawSqlPortabilityFn`), den das treibende CLI bindet. Wie die
fünf Preflight-Sonden daneben ist er nullable: **bleibt er ungebunden, prüft
der Lauf nicht, und das fällt nirgends auf.** Dagegen hilft nur, dass die
Bindung selbst ein benanntes, geprüftes Objekt ist (`CliRawSqlPortability`)
statt eines Lambdas in der Verdrahtung.

## Nachtrag (2026-09-12): ein fuenftes Feld, und der generate-Pfad

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

Fuer den **migrate**-Pfad ist es jetzt ebenfalls geschlossen — siehe oben, mit
einem anderen Mittel als dort vermutet.

## Herkunft

Nebenbefund beim Schließen des Konsumentenbefunds zu berechneten Spalten
(2026-09-12): der generate-Pfad wurde vollständig gemacht, der migrate-Pfad
blieb, wie er war — und das soll nicht unbenannt bleiben.
