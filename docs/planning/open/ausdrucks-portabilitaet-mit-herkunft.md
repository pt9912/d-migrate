# Vorabklärung: die Portabilitätsprüfung roher Ausdrücke kennt die Herkunft nicht

> **Status:** Vorabklärung / Spec-Regeländerung (2026-09-17)
> **Trigger:** Posten D6 des Reader-Slices
> ([`../next/reader-treue-1-matrix-abnahme.md`](../next/reader-treue-1-matrix-abnahme.md)),
> zweite Hälfte. Die erste Hälfte (der SQL-Server-Reverse liefert den
> Berechnungsausdruck ohne T-SQL-Quoting) wird dort gebaut; diese hier war im
> Slice unter „Offen" geführt und ist beim Schnitt in vier Pläne hierher
> gewandert.
> **Aktivierungsbedingung:** ein gemessener Fall nach dem Reader-Fix — eine
> ältere Reverse-Datei oder handgeschriebener T-SQL-Text, der ein anderes Ziel
> still oder mit einem Serverfehler erreicht — oder ein Eigner-Auftrag.

## Worum es geht

`E053` beurteilt rohen Ausdruckstext (CHECK, Index-Prädikat, Index-Ausdruck,
Berechnungsausdruck) nach dem **Ziel**, nicht nach der Herkunft
([`RawSqlExpressionPortability`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/RawSqlExpressionPortability.kt);
[`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md),
Abschnitt „Roher Ausdruckstext" unter 8.3). Zwei Fälle nimmt die Prüfung
deshalb bewusst aus:

- **T-SQL-Klammern** (`[q]*[p]`): `[` steht auch in JSON-Pfaden und
  Array-Ausdrücken; ohne Herkunft wäre die Unterscheidung geraten.
- **`||` gegen MySQL**: dort gültig, aber als logisches ODER.

Gemessen im Compare-Slice: SQL Server → PostgreSQL scheitert mit
`syntax error at or near "["`, SQL Server → MySQL mit `ERROR 1064`, beide an
Klammer-Quoting im Berechnungsausdruck. Die Matrix pinnt das heute als
`APPLY-FAIL`.

Die Herkunft ist inzwischen bekannt: ein Reverse trägt seine Markierung mit
Dialekt ([ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md),
Abschnitt 2, Punkt 2). Der Generate-Pfad bekommt sie aber nicht.

## Was nach dem Reader-Fix übrig bleibt

Normalisiert der SQL-Server-Reverse den Berechnungsausdruck (wie heute schon
den CHECK), erreicht der Fall nur noch:

- ältere Reverse-Dateien mit Klammer-Quoting;
- handgeschriebenen T-SQL-Text, etwa `CONVERT(decimal(14,2), …)`, den kein
  Marker erkennt.

## Die Wege

| Weg | Wirkung | Preis |
| --- | ------- | ----- |
| die Prüfung wertet die Reverse-Markierung aus | T-SQL-Text aus einem SQL-Server-Reverse wird gegen jedes andere Ziel laut abgelehnt | Regeländerung von 8.3; der Generate-Pfad muss den Herkunftsdialekt tragen; betrifft die gemeinsame Prüfung aller fünf Generatoren |
| so lassen | der Server des Ziels lehnt ab, laut, aber erst beim Anwenden | ältere Dateien scheitern spät |

## Nachbarschaft

Die Grenze „`"…"` gilt immer als Bezeichner, MySQL liest es ohne
`ANSI_QUOTES` als Zeichenkette"
([`spec/cli-spec.md`](../../../spec/cli-spec.md), Abschnitt
`schema compare`, Grenze der Schreibweise-Faltung) ist dieselbe Familie. Sie
wird akut, sobald die Reverses Bezeichner zu `"…"` normalisieren, und ist im
Umbrella des Reader-Slices als Eigner-Frage E1 geführt
([`../next/reader-treue.md`](../next/reader-treue.md)). Fällt sie für eine herkunftsbewusste Prüfung aus,
gehört sie hierher.

## Referenzen

- Befund D6: [`../next/reader-treue-1-matrix-abnahme.md`](../next/reader-treue-1-matrix-abnahme.md).
- Messung: [`../done/compare-projektion-und-normalisierung.md`](../done/compare-projektion-und-normalisierung.md),
  Abschnitt „Offen".
