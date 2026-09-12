# Berechnete Zielspalten im Datenpfad: auslassen statt ablehnen

> **Status:** Next mit Scope (2026-09-12). Promotet aus `../open/`, nachdem die
> Ursache gemessen war — sie ist schaerfer als der erste Befund.
> **Herkunft:** Schreibpfad-Stueck von
> [`generated-column-expression-dropped.md`](../done/generated-column-expression-dropped.md).

## Der Befund, gemessen

Der Import lehnt auf allen fuenf Zielen benannt ab, wenn der Chunk eine
berechnete Spalte traegt, und nennt als Ausweg: „nimm die Spalte aus
Export/Transfer heraus". Fuer einen **direkten** `data transfer` gibt es diesen
Ausweg nicht — es existiert nirgends ein Spaltenfilter (geprueft: kein
`--columns`-Flag, kein `excludeColumns`/`columnFilter` im ganzen Baum).

**Und es ist nicht die Quelle, die die Spalte hineintraegt.**
`TransferExecutor.transferTable` baut **jeden** Chunk aus der **Ziel**-Spaltenliste
(`session.targetColumns`, aus den JDBC-Metadaten der Zieltabelle) und fuellt
Spalten, die die Quelle nicht hat, mit `null`:

```kotlin
val targetNames = session.targetColumns.map { it.name }
val sourceIndexes = targetNames.map { target -> sourceNames.indexOf(target) }
…
val targetDescriptors = session.targetColumns.map { ColumnDescriptor(it.name, …) }
```

Die berechnete Spalte steht also im Chunk, **unabhaengig von der Quelle**. Ein
Transfer in eine Tabelle mit berechneter Spalte kann damit nie durchlaufen —
und konnte es auch vorher nicht: die `INSERT`-Spaltenliste nannte sie, worauf
PostgreSQL/MySQL/SQLite mit einem Treiberfehler und SQL Server/Oracle mit der
benannten Ablehnung antworteten. Die neue Sperre hat das nicht kaputt gemacht,
sondern lesbar.

## Ziel

Ein `data transfer` in eine Tabelle mit berechneter Spalte laeuft durch: die
Spalte bleibt aus der Uebertragung heraus, das Ziel rechnet sie selbst, und der
Lauf **sagt**, welche Spalten er ausgelassen hat.

**Warum auslassen und nicht ablehnen.** Der Wert ist abgeleitet — er steht in
der Quelle, traegt aber keine Information, die das Ziel nicht selbst herstellt.
Genau das tut d-migrate an einer Stelle schon: der SQLite-Tabellen-Neubau laesst
die berechnete Spalte aus dem `INSERT INTO neu (…) SELECT … FROM alt` heraus
(`SqliteRebuildPlanner.computeColumnMapping`), sonst scheiterte der Neubau.

**Die Grenze, und warum sie dort liegt.** Fuer `data import` bleibt es bei der
**Ablehnung**. Dort hat ein Anwender eine Datei uebergeben, die die Spalte
*enthaelt*; sie stillschweigend zu verwerfen heisst, uebergebene Daten
wegzuwerfen. Beim Transfer dagegen entstand der Wert ohnehin aus einer
Berechnung — nur aus der der **Quelle**, die von der des Ziels abweichen darf.
Das Ziel neu rechnen zu lassen ist dort die vertragsgemaesse Antwort, nicht ein
Verlust. Diese Unterscheidung ist der Kern des Slices und gehoert vom Eigner
bestaetigt, bevor gebaut wird.

## Scope-Skizze (Phasen)

**P1 — Der Port sagt, was nicht beschreibbar ist.**
`TableImportSession` bekommt ein lesbares Glied neben `targetColumns` (Name
offen, z. B. `computedColumns: Set<String>`, Default leer).
`AbstractTableImportSession` haelt die Menge bereits (`computedTargetColumns`);
sie wird nur sichtbar gemacht, kein neues Dialektwissen. Damit kann **jeder**
Orchestrator sie auslassen, nicht nur der Transfer.

**P2 — Der Transfer laesst sie aus.** `TransferExecutor.transferTable` baut
`targetNames`/`targetDescriptors` ohne diese Spalten. Eine Zeile weniger je
Chunk, kein zweiter Pfad.

**P3 — Der Lauf sagt es.** `TransferPreflightPlanner.validate` hat **beide**
`SchemaDefinition`s und sieht `ColumnGeneration.Computed` am Ziel; die Meldung
entsteht dort — **je Tabelle, nicht je Zeile**, mit neuem `W`-Code (Eintrag in
`spec/ledger.md` Pflicht; die W-Code-Vollstaendigkeit ist ohnehin ein offener
Tracker: [`warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md)).

**P4 — Vertrag und Handbuch.** `spec/cli-spec.md` (Abschnitt „Berechnete
Spalten im Importpfad" trennt Import von Transfer), Anwenderhandbuch 3.7/3.8
(der Hinweis dort nennt heute den Weg ueber eine Exportdatei — der entfaellt),
CHANGELOG.

## Abgrenzung (NICHT Scope)

- **Ein allgemeiner Spaltenfilter** (`--columns`/`--exclude-columns`). Waere ein
  eigener Vertrag mit eigenen Fragen (Praezedenz, Pflichtspalten, FK-Folgen).
- **Identity-Spalten.** Die sind beschreibbar (`OVERRIDING SYSTEM VALUE`,
  `SET IDENTITY_INSERT`, Oracles Modus-Toggle) und laufen schon.
- **Die Ablehnung im Importpfad.** Bleibt, siehe „Die Grenze" oben.

## Blast Radius

| Stelle | Art |
| --- | --- |
| `hexagon/ports-write/src/main/kotlin/dev/dmigrate/driver/data/TableImportSession.kt` | +1 Glied mit Default (kein Bruch fuer Implementierer) |
| `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/data/AbstractTableImportSession.kt` | Sichtbarkeit, keine Logik |
| `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/TransferExecutor.kt` | Spaltenliste filtern |
| `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/TransferPreflightPlanner.kt` | Befund + `W`-Code |
| `spec/ledger.md`, `spec/cli-spec.md`, Anwenderhandbuch, CHANGELOG | Vertrag/Doku |

## Akzeptanzkriterien

- Ein PG→PG-Transfer einer Tabelle mit **gespeicherter und virtueller**
  berechneter Spalte laeuft durch; die Zielwerte sind vom Ziel gerechnet (live
  geprueft, nicht nur „kein Fehler").
- Derselbe Transfer meldet **je Tabelle einmal**, welche Spalten er ausgelassen
  hat, mit im Ledger eingetragenem Code.
- Mindestens ein **dialektfremdes** Paar (PG→MySQL) ebenso, damit die Regel
  nicht am gleichen Katalog haengt.
- `data import` lehnt weiter ab — die bestehenden Specs bleiben unveraendert
  gruen (Regressionsschutz fuer die Grenze oben).
- Kein neues Flag.

## Aktivierungs-Trigger

Bestaetigung der Grenze (Transfer laesst aus, Import lehnt ab) durch den
Eigner. Ohne sie ist P1/P2 gebaut und P3 sagt das Falsche.
