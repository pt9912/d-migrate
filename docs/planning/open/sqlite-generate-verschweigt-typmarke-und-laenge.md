# Der SQLite-Generate verschweigt, was er verwirft: Typmarke und Länge

> **Status:** Befund (gemessen 2026-09-17), ohne Scope.
> **Trigger:** der Silent-Loss-Check der Compare-Matrix (P0 aus
> [`../in-progress/reader-treue-1-matrix-abnahme.md`](../in-progress/reader-treue-1-matrix-abnahme.md)).
> Er verlangt für jede Degradierung einen Code im Generate-Report; für diese
> beiden gibt es keinen, und sie stehen deshalb in der Liste bekannter Befunde
> des Harness — mit diesem Eintrag als Ort.
> **Aktivierungsbedingung:** Eine Entscheidung darüber, ob diese beiden
> Verluste eine Meldung bekommen (und mit welchem Code), oder ob sie
> ausdrücklich als „keine Meldung" festgeschrieben werden.

## Gemessen

`schema generate --target sqlite`, Reverse des Ziels, Compare-Matrix
(`examples/mcp-e2e`):

| Neutral | SQLite-DDL | Reverse des Ziels | Code im Generate-Report |
| --- | --- | --- | --- |
| `text(40)` | `TEXT` | `text` | **keiner** |
| `json` | `TEXT` | `text` | **keiner** |
| `decimal(12,2)` | `REAL` | `float` | `W200` |
| `decimal(21,2)` **einer berechneten Spalte** | `REAL` | `float` | **keiner** |
| `array(text)` | `TEXT` | `text` | **keiner** (Posten P5, Plan 2 des Reader-Umbrellas) |

Die Zahl daneben: der Vergleich **zeigt** den Unterschied (die Zelle meldet
`TABLE_COLUMN_TYPE_CHANGED`), der Generate-Schritt **sagt** ihn nicht. Wer aus
dem Report liest, was der Weg gekostet hat, sieht nur `W200`.

## Warum das zählt

Der Report ist die Stelle, an der ein Anwender erfährt, was beim Erzeugen
verloren ging — `schema generate` schreibt ihn neben das DDL, und `W200` zeigt,
dass genau dafür Codes vergeben werden. Die deklarierte Länge ist keine
Kosmetik: sie ist die Zusage des Autors an die Daten, und auf jedem anderen
Ziel wird sie durchgesetzt. Die Typmarke `json` unterscheidet einen JSON-Wert
von einem beliebigen Text; SQLite prüft ihn nicht, und der Reverse führt ihn
danach als `text`.

**Die dritte Zeile ist eine eigene Lücke:** `W200` gibt es, es trifft nur die
berechnete Spalte nicht. Gemessen in der Compare-Matrix (SQL Server → SQLite):
`sl_ms_calc.Preis` bekommt `W200`, die daneben stehende berechnete Spalte
`sl_ms_calc.Summe` mit demselben Präzisionsverlust nicht.

## Zu klären

1. **Bekommt der Längenverlust einen Code?** SQLite kennt keine Länge (die
   Typaffinität ignoriert sie), der Verlust ist unvermeidlich — die Frage ist
   nur, ob er gemeldet wird. Vorbild wäre `W200` (Präzisionsverlust bei
   `decimal`).
2. **Bekommt der Verlust der JSON-Typmarke einen Code?** Dieselbe Frage; die
   Nachbardialekte sind hier verschieden (MySQL hat `JSON`, SQL Server meldet
   `W137`, Oracle `W149`).
3. **Warum trifft `W200` eine berechnete Spalte nicht?** Der Verlust ist
   derselbe; vermutlich läuft die Typabbildung dort an der Stelle vorbei, die
   die Note hängt. Das ist eher ein Defekt als eine offene Frage.
4. Danach ziehen die Anmerkungen der Seeds und die Liste bekannter Befunde im
   Harness nach (`examples/mcp-e2e/scripts/lib/silent-loss.sh`).
