# Das `raw-text-provenance`-Overlay ist nicht zurückführbar

> **Status:** Befund (gemessen 2026-09-17), ohne Scope.
> **Trigger:** die Messung F5 aus
> [`../in-progress/reader-treue-1-matrix-abnahme.md`](../in-progress/reader-treue-1-matrix-abnahme.md)
> (P6). Sie fragte, was ein `schema migrate` mit einer älteren Herkunft nach
> der Reader-Normalisierung plant — und kam gar nicht so weit: das Dokument,
> das `--provenance-output` schreibt, weist `--migration-overlay` **derselben
> Version** ab.
> **Aktivierungsbedingung:** Eine Entscheidung darüber, welchen Dialekt und
> welchen Fingerabdruck ein Herkunfts-Overlay trägt — es beschreibt ein
> Schemapaar aus Autorentext und Katalogform, nicht einen Übergang.

## Gemessen

Gegen MySQL 9.7.2, `d-migrate` 1.8.0-SNAPSHOT (Stand `a77fc9bb7`), eine Tabelle
mit einem CHECK und einer berechneten Spalte:

1. `schema migrate --source <reverse.yaml> --target db:… --execute
   --report … --provenance-output prov.json` läuft sauber durch (`status:
   no_op`, Exit 0) und schreibt `prov.json` mit zwei Einträgen
   (`constraint:…:expression`, `column:…:generation-expression`).
2. Derselbe Lauf mit `--plan-only --migration-overlay prov.json` — **gleiche
   Version, gleiche Quelldatei, gleiche Datenbank** — endet mit Exit 8,
   `status: blocked`, und drei Blockern:

   | Code | Meldung |
   | --- | --- |
   | `OVERLAY_REQUIRED_FIELD_MISSING` | `dialect is required` |
   | `OVERLAY_DIALECT_MISMATCH` | `Overlay dialect '' is not applicable` |
   | `OVERLAY_STALE_SCHEMA_FINGERPRINT` | `Overlay schemaFingerprint does not match the schema it describes` |

Das geschriebene Dokument trägt `"dialect": ""` und einen
`schemaFingerprint`, den die Prüfung beim Zurückgeben nicht wiederfindet.

## Warum das zählt

[ADR 0053](../../adr/0053-vergleich-rohen-sql-texts.md) und
[ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md)
(übernommene Entscheidung 2) beantworten „hat der Autor den Text geändert?"
über die **Herkunft**. [`spec/cli-spec.md`](../../../spec/cli-spec.md)
beschreibt den Weg: schreiben mit `--provenance-output`, zurückgeben mit
`--migration-overlay`. Solange das Dokument nicht zurückgeht, gibt es die
Herkunftsquelle in der Praxis nicht: `schema migrate` plant rohe Textfelder
immer konservativ, und der Berechnungsausdruck bleibt immer unentscheidbar
(`W137`).

Für den Reader-Slice hatte das eine gute Nebenwirkung: die Normalisierung des
MySQL- und SQL-Server-Readers kann über die Herkunft **keine** Autorenänderung
vortäuschen, weil die Herkunft nie ankommt. Gemessen wurde stattdessen der
Fall ohne Overlay (Plan 1, Bauabschnitt P6).

## Zu klären

1. **Welchen Dialekt trägt ein Herkunfts-Overlay?** Es beschreibt eine
   Darstellung, keinen Übergang; der Schreibpfad lässt das Feld leer, der
   Lesepfad verlangt es. Entweder schreibt der Schreibpfad den Dialekt des
   Laufs, oder die Prüfung nimmt diese Overlay-Art aus.
2. **Woran bindet der Fingerabdruck?** An das Soll, wie es geschrieben wurde,
   oder an das zurückgelesene Ist? Gemessen passt er zu keinem von beiden.
3. Danach ist F5 erneut zu messen: mit gültiger Herkunft sieht ein neuer
   Reverse gegen eine alte Herkunft nach einer Autorenänderung aus.
