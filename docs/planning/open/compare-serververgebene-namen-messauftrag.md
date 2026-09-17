# Messauftrag: die übrigen server-vergebenen Namen in `schema compare`

> **Status:** Vorabklärung (Messauftrag), 2026-09-17.
> **Trigger:** Eigner-Entscheidung F4 im Compare-Slice
> [`compare-projektion-und-normalisierung.md`](../done/compare-projektion-und-normalisierung.md)
> (Abschnitt „Offen"), festgeschrieben in
> [ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md),
> Entscheidung 2, Punkt 3 („Reichweite"). `schema compare` nimmt von der Familie
> der Namen, die ein Server vergibt oder nicht führt, **nur** den
> Identity-Sequenznamen aus.
> **Aktivierungsbedingung:** Ein gemessenes Paar zweier Reverses verschiedener
> Dialekte, das an einem der drei übrigen Namen einen Fehlalarm zeigt. Dann ein
> `next/`-Plan, der die Regel aus ADR 0057 auf diesen Namen ausweitet und
> `spec/cli-spec.md` mitzieht. Einen neuen ADR braucht das nicht; ADR 0057 deckt
> die Ausweitung ausdrücklich, setzt aber die Messung voraus.

## Was offen ist

[`DialectCapabilities`](../../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DialectCapabilities.kt)
führt vier Namens-Fähigkeiten. `schema compare` wertet seit dem Compare-Slice
nur `namesIdentitySequences` aus (P6). Die übrigen drei wertet weiterhin nur der
zielbewusste Vergleich (`schema migrate`, Fingerabdruck) aus:

| Fähigkeit | `false` bei | Was der Server tut |
| --- | --- | --- |
| `namesFullTextIndexes` | SQL Server | führt keinen Namen für den Volltext-Index |
| `namesPartitions` | SQL Server | nummeriert Partitionen; der Reverse vergibt `p1` … `pn` (`R346`) |
| `namesSingleColumnConstraints` | SQLite | legt ein einspaltiges `UNIQUE` als Auto-Index `sqlite_autoindex_<tabelle>_<n>` ab; der Name ist eine Erfindung des Servers |

## Was zu messen ist

Je Fähigkeit ein Paar zweier Reverses, in dem genau dieser Name auf einer Seite
vom Server vergeben oder vom Reader erfunden ist, auf der anderen deklariert:

1. Volltext-Index PostgreSQL ↔ SQL Server.
2. Partitionierte Tabelle PostgreSQL ↔ SQL Server. Vorsicht: eine Änderung der
   Partitionierung hat über MCP heute gar keinen Fund (s.
   [`compare-different-ohne-sichtbaren-fund.md`](compare-different-ohne-sichtbaren-fund.md)).
   Die Messung braucht deshalb die CLI oder den Fund zuerst.
3. Einspaltiges `UNIQUE` PostgreSQL ↔ SQLite.

Verwandt, aber nicht Teil der Familie: der SQLite-Reverse nennt die
Fremdschlüssel jeder Tabelle `fk_0` … — ein vom Reader erfundener Name, für den
es keine Fähigkeit gibt (Reader-Slice
[`reader-treue-1-matrix-abnahme.md`](../in-progress/reader-treue-1-matrix-abnahme.md),
Posten D5). Zeigt die Messung dort einen Fehlalarm, ist zuerst zu klären, ob
der Name in die Familie gehört.

Erst ein gemessener Fehlalarm entscheidet, ob die Kategorie „vom Server
vergeben" aus ADR 0057 dort gilt. Ohne Messung bleibt der Name
Vergleichsgegenstand; ein Fehlalarm dort ist bis dahin in Kauf genommen
(ADR 0057, „Dagegen").

## Nicht Teil

- Der Identity-Sequenzname ist gebaut (P6 des Compare-Slices).
- Der zielbewusste Vergleich bleibt, wie er ist.
