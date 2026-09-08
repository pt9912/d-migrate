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

## Und bei SQLite fehlt die Spalte ganz (gemessen)

```sql
total REAL GENERATED ALWAYS AS (qty * price) STORED,
virt  REAL GENERATED ALWAYS AS (qty + 1) VIRTUAL
```

| Abfrage | Ergebnis |
| --- | --- |
| `PRAGMA table_info('t')` | `id`, `qty`, `price` — **die beiden generierten Spalten fehlen** |
| `PRAGMA table_xinfo('t')` | dieselben plus `total` (`hidden=3`, STORED) und `virt` (`hidden=2`, VIRTUAL) |
| Reverse | `columns=[id, qty, price]`, `notes=[]` |

SQLite blendet generierte Spalten in `table_info` aus; der Leser benutzt genau
diese Abfrage. Es geht dort also nicht nur der **Ausdruck** verloren, sondern
die **Spalte**. Folgen, die ueber den PostgreSQL-Fall hinausgehen:

- Ein `schema generate` aus dem Reverse erzeugt eine Tabelle, der Spalten
  fehlen.
- Ein `schema compare` gegen dieselbe Datenbank plant fuer jede generierte
  Spalte ein `AddColumn` — und weil der naechste Reverse sie wieder nicht
  sieht, plant es der uebernaechste Lauf erneut. Nicht konvergent, dieselbe
  Gestalt wie
  [`raw-sql-text-drift.md`](raw-sql-text-drift.md), aber aus anderer Ursache.

`PRAGMA table_xinfo` liefert die Angabe fertig mit; die Umstellung ist keine
Interpretationsfrage.

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
  [`check-expression-cross-dialect-portability.md`](../done/check-expression-cross-dialect-portability.md)
  (er ist nicht ohne Weiteres portabel). Wer diesen Slice schneidet, schneidet
  ihn hinter oder mit jenen — sonst entsteht eine dritte Baustelle derselben
  Art.
- **Drei Projektionen, wie immer.** Comparator, `MigrationFingerprint` und
  `CanonicalPayload` muessen zugleich bewegt werden; die dritte traegt die
  Operations-IDs.
- **Der Schreibpfad.** Eine berechnete Spalte darf nicht befuellt werden. SQL
  Server lehnt das heute benannt ab; ob die uebrigen vier das auch tun, ist
  ungeprueft — der Import kennt die Spalten dort ja gar nicht als berechnet.

## Teilweise erledigt: der Verlust ist nicht mehr stumm (2026-09-08)

Jeder Reverse meldet ihn jetzt — `R343` fuer die vier Dialekte, die die Spalte
zurueckgeben, `R367` fuer SQLite, wo sie ganz fehlt. Beides aus einer
gemeinsamen Stelle (`GeneratedColumnNotes`), damit aus einer Fassung nicht
fuenf leicht verschiedene werden.

Dabei kam heraus, dass **SQL Server es laengst meldete** (`R343`,
`MssqlSchemaReader`) — die Luecke war nicht „nirgends", sondern „an vier von
fuenf Stellen". Der Wortlaut von SQL Server ist woertlich uebernommen; sein
Verhalten aendert sich nicht.

Und ein zweiter Fund derselben Art: Oracle hatte die noetige Abfrage schon
(`OracleMetadataQueries.virtualColumns`) — benutzt aber nur vom **Schreib**pfad,
damit er nicht in virtuelle Spalten schreibt. Genau wie bei SQL Servers
`computedColumns` wusste die eine Haelfte des Werkzeugs Bescheid und die
andere nicht. Der Lesepfad benutzt jetzt dieselbe Abfrage.

Live abgenommen gegen PostgreSQL 16, MySQL 8.0, Oracle 23 und SQLite; alle
vier Katalog-Praedikate sabotage-geprueft.

**Offen bleibt die Modellierung** — alles unter „Was der Schnitt klaeren muss".

## Was die Meldung nicht loest

Der Verlust **stumm** zu lassen ist das Schlimmste daran. Eine Reverse-Notiz,
sobald ein Leser eine berechnete Spalte sieht, kostet keine Modelaenderung und
macht aus dem stillen Fehlschlag einen benannten. Sie ist kein Ersatz fuer die
Modellierung, aber sofort besser als nichts.

Bei SQLite muss die Notiz mehr sagen als bei den uebrigen vier: dort fehlt die
Spalte, nicht nur ihr Ausdruck. Sie deshalb als **gewoehnliche** Spalte
nachzutragen waere schlimmer als sie wegzulassen — sie waere dann
beschreibbar, und der naechste Import schriebe hinein, was der Server selbst
berechnen wollte.

## Herkunft

Externe Durchsicht der neutralen Form (2026-09-08), am PostgreSQL- und
SQLite-Reverse nachgemessen.
