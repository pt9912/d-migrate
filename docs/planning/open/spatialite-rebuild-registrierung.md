# SpatiaLite: ein Tabellen-Neubau, der die Geometrie-Registrierung mitnimmt

> **Status:** Draft / Trigger Watch — aus Plan 3 (P7, Stelle 4) abgetrennt.
> **Trigger:** Der Bau von P7 (`../done/reader-treue-3-spatial.md`,
> Umbrella `../in-progress/reader-treue.md`) hat entschieden: der
> Tabellen-Neubau an einer Tabelle mit Geometriespalte **blockt** mit
> `SPATIAL_METADATA_UNSUPPORTED`, weil er die SpatiaLite-Registrierung nicht
> mitnimmt. Einen Neubau zu bauen, der sie mitnimmt, ist der eigene Posten.
> **Aktivierungsbedingung:** ein realer Migrate-Lauf, der an einer
> Geometriespalte einen Rebuild braucht (`AlterColumnNullability` und
> jede andere Rebuild-Operation) und an der Blockade hängt — oder ein
> Identity-/Nullability-Slice, der den Rebuild-Pfad ohnehin öffnet.

## Befund

Der SQLite-Neubau schreibt die Zieltabelle über `columnLine` neu: eine
Geometriespalte entstände dort inline als `GEOMETRY` — ohne Eintrag in
`geometry_columns`, ohne Integritäts-Trigger und ohne R\*Tree. Die
Anweisungsfolge liefe durch, die Tabelle sähe richtig aus, und die Geometrie
wäre still keine mehr. Beide Seiten sind im Blockfall geprüft: eine Spalte,
die der Neubau anlegt, wäre unregistriert, eine, die er kopiert, verlöre
ihre Registrierung mit dem `DROP TABLE`.

Heute ist der Zustand laut: der Neubau blockt mit
`SPATIAL_METADATA_UNSUPPORTED` (`MANUAL_ACTION_REQUIRED`), Generate und
Migrate nennen dieselben Auslöser (`spec/ddl-generation-rules.md` 16.5).

## Was der Posten bauen würde

Ein Rebuild, der die Registrierung mitnimmt: Tabelle ohne die
Geometriespalte neu bauen, Spalten über `AddGeometryColumn` wieder
registrieren, Daten zurückkopieren, Trigger und R\*Tree wiederherstellen —
und der Diff-Planer muss die Operation in Schritte zerlegen können, die
SpatiaLites Bootstrap (ADR 0016) vorab erfüllt.

## Belegstellen

- `SqliteRebuildRenderer.kt` (Blockade beider Seiten, `emitGeometryBlockerIfAny`).
- `SqliteSpatialDiffOps.kt` (die Auslöser des Blocks).
- [`../done/reader-treue-3-spatial.md`](../done/reader-treue-3-spatial.md),
  Paket P7, Stelle 4, und die „Offen"-Liste des Plans.
