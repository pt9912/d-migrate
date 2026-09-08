---
id: generated-column-expression-dropped
title: "Eine berechnete Spalte verliert ihre Berechnung — stumm"
status: open
---

# Eine berechnete Spalte verliert ihre Berechnung

## Befund (gemessen, PostgreSQL 16)

```sql
line_total numeric(14,2) GENERATED ALWAYS AS (quantity * unit_price) STORED
```

kommt aus dem Reverse zurueck als

```
col line_total type=Decimal(precision=14, scale=2) generation=null
notes=[]
```

Die Berechnung ist weg, und **es gibt keine Meldung**. Ein `schema generate`
aus diesem Modell erzeugt eine gewoehnliche Spalte: die Zusicherung
„`line_total` ist immer `quantity * unit_price`" faellt still weg, und die
Zielspalte bleibt danach leer oder haelt Werte, die niemand mehr nachrechnet.

Das ist kein Darstellungsverlust wie eine fehlende Zugriffsmethode, sondern
der Verlust einer **Anwendungsinvariante**.

## Warum es passiert

`ColumnGeneration` ist sealed und kennt genau einen Fall:

```kotlin
sealed interface ColumnGeneration {
    data class Identity(...) : ColumnGeneration
}
```

Es gibt im neutralen Modell keine Form fuer eine berechnete Spalte, also
liest sie auch kein Reverse. Der einzige Ort, an dem das Werkzeug ueberhaupt
von ihnen weiss, ist der **Schreibpfad** von SQL Server: `MssqlDataWriter`
holt sich ueber `MssqlMetadataQueries.computedColumns` eine Menge von
**Namen**, um Schreibversuche darauf abzulehnen. Der Ausdruck wird dabei nie
gelesen.

Alle fuenf Server fuehren die Angabe im Katalog: PostgreSQL
(`pg_attribute.attgenerated` + `pg_get_expr`), MySQL
(`INFORMATION_SCHEMA.COLUMNS.GENERATION_EXPRESSION`), SQL Server
(`sys.computed_columns.definition`), Oracle (`USER_TAB_COLS.DATA_DEFAULT`
mit `VIRTUAL_COLUMN = 'YES'`), SQLite (aus `sqlite_master`).

## Was der Schnitt klaeren muss

- **Die Modellform.** Ein zweiter Fall an `ColumnGeneration`
  (`Computed(expression, stored)`) liegt nahe — die sealed Hierarchie ist
  genau dafuer da, und der Fingerabdruck projiziert `generation` bereits.
- **Der Ausdruck ist roher SQL-Text.** Damit erbt die berechnete Spalte
  jedes Problem, das
  [`raw-sql-text-drift.md`](raw-sql-text-drift.md) beschreibt (der Server
  gibt ihn anders zurueck, als er hineinging) und
  [`check-expression-cross-dialect-portability.md`](check-expression-cross-dialect-portability.md)
  (er ist nicht ohne Weiteres portabel). Wer diesen Slice schneidet, schneidet
  ihn hinter oder mit jenen — sonst entsteht eine dritte Baustelle derselben
  Art.
- **Drei Projektionen, wie immer.** Comparator, `MigrationFingerprint` und
  `CanonicalPayload` muessen zugleich bewegt werden; die dritte traegt die
  Operations-IDs.
- **Der Schreibpfad.** Eine berechnete Spalte darf nicht befuellt werden. SQL
  Server lehnt das heute benannt ab; ob die uebrigen vier das auch tun, ist
  ungeprueft — der Import kennt die Spalten dort ja gar nicht als berechnet.

## Sofort moeglich, unabhaengig vom Rest

Der Verlust **stumm** zu lassen ist das Schlimmste daran. Eine Reverse-Notiz
(`R…`), sobald ein Leser eine berechnete Spalte sieht, kostet keine
Modelaenderung und macht aus dem stillen Fehlschlag einen benannten. Sie ist
kein Ersatz fuer die Modellierung, aber sie ist sofort besser als nichts.

## Herkunft

Externe Durchsicht der neutralen Form (2026-09-08), am PostgreSQL-Reverse
nachgemessen.
