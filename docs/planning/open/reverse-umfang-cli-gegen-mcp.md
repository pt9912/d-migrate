# Reverse-Umfang: `schema reverse` und `schema_reverse_start` lesen Verschiedenes

> **Status:** Befund / Vorabklärung (2026-09-17).
> **Trigger:** Konsumenten-Repro des Compare-Slices
> [`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md)
> (dritter Bauabschnitt, „Konsumenten-Repro mit dem Schema des Konsumenten";
> „Offen"). Kein Compare-Thema: der Unterschied entsteht vor dem Vergleich.
> **Aktivierungsbedingung:** Eine Entscheidung, ob beide Oberflächen denselben
> Default-Umfang haben sollen (Eigner), oder ein Abnehmer, der Reverse-Dateien
> aus beiden Wegen gegeneinander vergleicht.

## Befund

- **CLI:** `schema reverse` liest Sichten, Funktionen, Prozeduren und Trigger
  nur mit `--include-views`, `--include-functions`, `--include-procedures`,
  `--include-triggers` bzw. `--include-all`
  ([`SchemaReverseRunner`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaReverseRunner.kt)).
  Ohne die Flags fehlen sie in der Datei.
- **MCP:** `schema_reverse_start` liest sie **immer** und hat kein Argument, um
  sie auszuschließen.

Gemessen im Repro: der CLI-Vergleich der eigenen Reverses kennt kein
`VIEW_REMOVED`, der MCP-Vergleich derselben Datenbanken schon. Wer eine
CLI-Datei gegen ein MCP-Artefakt vergleicht, bekommt Funde, die nur aus dem
Umfang stammen.

**Verschärft durch PostGIS in `public`:** der MCP-Reverse trägt dann rund 1000
PostGIS-Funktionen (im Repro gemessen, beim Konsumenten bekannt und durch ein
eigenes Schema `postgis` umgangen). Dieser Teil ist ein Reader-Thema und steht
im Reader-Slice
([`reader-treue-spatial-array-json.md`](../next/reader-treue-spatial-array-json.md),
Posten A3, Paket P2b); der Umfangs-Unterschied bleibt auch nach dessen Fix.

## Zu klären

1. Soll die CLI denselben Default haben wie MCP (Vertragswechsel für die CLI)
   oder MCP ein Argument zum Ausschließen bekommen (Tool-Schema, Idempotenz)?
2. Nennt das Ergebnis seinen Umfang? Der Reverse-Report trägt ihn heute nicht;
   ein Abnehmer sieht einer Datei nicht an, ob Sichten fehlen oder nicht
   gelesen wurden.
3. `data transfer` liest ohne Sichten und Routinen
   ([`DataTransferRunner`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt));
   das ist dort gewollt und nicht Teil der Frage.
