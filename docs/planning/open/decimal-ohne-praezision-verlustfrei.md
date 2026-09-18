# Modellfrage: `numeric` ohne Präzision verlustfrei

> **Status:** Vorabklärung / Modellfrage (2026-09-17)
> **Trigger:** Eigner-Entscheidung vom 2026-09-17 zu Posten D2 des
> Reader-Slices
> ([`../in-progress/reader-treue-2-meldungen.md`](../in-progress/reader-treue-2-meldungen.md)):
> `numeric` ohne Präzision wird im Reader-Slice **nur gemeldet** (Paket P9).
> Ihn verlustfrei zu machen ist eine Modellerweiterung und liegt hier.
> **P9 ist gebaut (2026-09-18):** der Verlust ist jetzt benannt — `R404`
> (PostgreSQL, Spalte und Feld eines zusammengesetzten Typs), `R221` (SQLite)
> und `R371` (Oracles `NUMBER` ohne Angabe), alle `WARNING`. Damit ist er
> sichtbar, aber nicht behoben; die Modellfrage bleibt offen.
> **Aktivierungsbedingung:** ein gemeldeter Fidelity-Bedarf, bei dem die
> Meldung nicht reicht — dieselbe Schwelle, die
> [`pg-only-types-first-class-candidates.md`](pg-only-types-first-class-candidates.md)
> für PostgreSQL-Typen ansetzt. Dann entsteht ein `next/`-Plan samt ADR nach
> dem Muster von [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md).

## Befund (im Code geprüft, 2026-09-17)

Drei Reverses lesen eine ungebundene exakte Zahl verlustbehaftet:

| Dialekt | Quelle | Neutral | Verlust | Stelle |
| --- | --- | --- | --- | --- |
| PostgreSQL | `numeric` / `decimal` ohne Präzision | `float` | exakt → Gleitkomma, auch auf dem Rückweg nach PostgreSQL (`DOUBLE PRECISION`) | `PostgresTypeMapping.mapNumericTypes`; dieselbe Regel in `mapCompositeFieldType` (Felder zusammengesetzter Typen) |
| SQLite | `NUMERIC` / `DECIMAL` ohne Präzision | `float` | wie oben | `SqliteTypeMapping.mapNumericType` |
| Oracle | `NUMBER` ohne Präzision und Skala | `decimal(38,10)` | mehr als zehn Nachkommastellen und mehr als 28 Vorkommastellen gehen verloren | `OracleTypeMapping.mapNumberPrecision` |

Dazu ein Randfall: ein PostgreSQL-Array `numeric[]` liest als
`element_type: decimal` — ohne Präzision, die das Array-Element gar nicht
tragen kann.

Die Spec führt die ersten beiden als „akzeptabel" bzw. als bekannte Lücke und
die Oracle-Zeile als „konservativ"
([`spec/type-mapping.md`](../../../spec/type-mapping.md), Abschnitte 5.2 und
7.2). Keiner der drei Wege meldet den Verlust heute; das macht der
Meldungs-Plan des Reader-Slices (P9).

## Warum das keine Korrektur, sondern eine Modellfrage ist

- `spec/neutral-model-spec.md` verlangt `precision` und `scale` bei `decimal`
  (Abschnitt „Typkompatibilitäts-Regeln"), und
  `NeutralType.Decimal(precision: Int, scale: Int)` hat keine Lücke dafür.
- Die Ziele haben verschiedene Vorgaben für ein `DECIMAL` ohne Angabe: MySQL
  `(10,0)`, SQL Server `(18,0)`, Oracle unbeschränkt, SQLite `REAL`. Ein
  neutraler `decimal` ohne Präzision braucht also eine Render-Entscheidung je
  Dialekt, und MySQL und SQL Server können die ungebundene Zahl gar nicht
  tragen.
- Die Oracle-Zeile hängt daran: gibt es einen ungebundenen `decimal`, ist
  `NUMBER` → `decimal(38,10)` nicht mehr „konservativ", sondern falsch.

## Die Wege

| Weg | Wirkung | Preis |
| --- | ------- | ----- |
| neutraler `decimal` ohne Präzision | PostgreSQL → PostgreSQL und Oracle → Oracle verlustfrei | Modellerweiterung; Render-Regel je Dialekt; Ziele ohne ungebundene Form brauchen eine eigene Note; Fingerabdruck |
| so lassen | die Meldung aus P9 benennt den Verlust | der Rückweg in denselben Dialekt bleibt verlustbehaftet |

## Berührt

- [`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md),
  [`spec/schema.json`](../../../spec/schema.json)
- [`spec/type-mapping.md`](../../../spec/type-mapping.md) — PostgreSQL,
  SQLite 5.2, Oracle 7.2
- [`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md) —
  die Render-Regel je Dialekt

## Referenzen

- Befund D2 und die Eigner-Entscheidung:
  [`../in-progress/reader-treue-2-meldungen.md`](../in-progress/reader-treue-2-meldungen.md).
- Muster: [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md).
