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

## Gemessen: was die fuenf Server wirklich annehmen (2026-09-08)

Vor dem Schnitt einmal alle fuenf gefragt, statt der Doku zu glauben. Zwei
Zeilen dieser Tabelle haette ich aus dem Gedaechtnis falsch geschrieben.

| Server | `VIRTUAL` | `STORED` | Vorgabe ohne Angabe | Katalog |
| --- | --- | --- | --- | --- |
| PostgreSQL 16 | **Syntaxfehler** | ja | keine — `STORED` ist **Pflicht** | `attgenerated='s'`, `pg_get_expr` |
| MySQL 8.0 | ja | ja | `VIRTUAL` | `extra` = `VIRTUAL GENERATED` / `STORED GENERATED` |
| SQLite | ja | ja | `VIRTUAL` | `table_xinfo.hidden` = 2 / 3 |
| SQL Server 2022 | ja (ohne `PERSISTED`) | ja (`PERSISTED`) | virtuell | `sys.computed_columns.is_persisted` |
| Oracle 23 | ja | ja (**„MATERIALIZED"**) | `VIRTUAL` | `user_tab_cols.virtual_column` — **nur fuer die virtuelle Form** |

Zwei Formfragen, die den Renderer betreffen:

- **SQL Server duldet keinen Typ.** `b AS (a*2)` ist gueltig, `b INT AS (a*2)`
  ist ein Syntaxfehler. Die berechnete Spalte bekommt ihren Typ aus dem
  Ausdruck; eine Typangabe zu rendern bricht das Statement.
- **PostgreSQL verlangt `STORED`.** Weder `VIRTUAL` noch das Weglassen ist
  gueltig. Das Feld `stored` ist dort also keine Wahl, sondern eine Konstante —
  ein Fall fuer die Faehigkeits-Projektion, sonst driftete jeder Round-Trip.

## Schwerer als der gemeldete Verlust: Oracle liest gespeicherte generierte Spalten als DEFAULT

Oracle 23 nimmt `GENERATED ALWAYS AS (a*2) STORED` an, und die Spalte
**rechnet** (`a=21` eingefuegt → `b=42`). `dbms_metadata` schreibt sie als
`GENERATED ALWAYS AS ("A"*2) MATERIALIZED` zurueck. Im Katalog steht sie aber
mit `virtual_column = 'NO'`, und ihr Ausdruck liegt in `data_default` — genau
dort, wo auch ein gewoehnlicher DEFAULT steht.

`OracleSchemaReader` haengt seine Erkennung an `virtual_column`. Eine
gespeicherte generierte Spalte kommt deshalb als **gewoehnliche Spalte mit
DEFAULT** zurueck. Das ist kein Darstellungsverlust wie bei den uebrigen vier,
sondern ein **falsches Modell**: ein `schema generate` daraus erzeugt eine
beschreibbare Spalte mit Default-Ausdruck, und der Import schreibt hinein,
was der Server selbst berechnen wollte. Auch die Schreibsperre des
Oracle-Importpfads greift nicht — sie fragt dieselbe Abfrage.

**Und es gibt keinen sauberen Weg, das zu erkennen** (gemessen):

| Versuch | Ergebnis |
| --- | --- |
| Alle Spalten von `user_tab_cols` zwischen gespeicherter generierter Spalte und `DEFAULT 42` vergleichen | Unterschied **nur** in `DATA_DEFAULT`/`DEFAULT_LENGTH` — kein Merkmal |
| `dictionary` nach einer View mit `GENERAT` im Namen durchsuchen | keine Zeile |
| `sys.col$.property` (dort steht das Bit) | `ORA-00942` — mit den Rechten des Migrationsnutzers nicht lesbar |
| `dbms_metadata.get_ddl` | nennt `MATERIALIZED` — aber als DDL-Text, den zu lesen einen Parser braeuchte |

Damit ist die gespeicherte Form auf Oracle **nicht round-trip-faehig**, und
zwar prinzipiell, nicht aus Nachlaessigkeit. Wer den Slice schneidet, muss das
benennen statt es zu uebergehen — und sollte pruefen, ob wenigstens der
Schreibpfad sich schuetzen laesst (eine `INSERT`-Ablehnung des Servers benannt
weiterreichen, statt sie als gewoehnlichen Fehler durchzulassen).

## Warum der Slice hinter `raw-sql-text-drift` gehoert (2026-09-08)

Der Generierungsausdruck ist roher SQL-Text und waere das **fuenfte** Feld
dieser Art. [ADR 0053](../../adr/0053-vergleich-rohen-sql-texts.md) verwirft
die naheliegende Abkuerzung ausdruecklich (Option C, „Textfelder aus dem
Vergleich nehmen"): der Text **ist** die Aussage. Ohne den dort entschiedenen
Mechanismus — Post-Compare gegen die zurueckgelesene Form, Planung aus der
Herkunft — plante jeder `schema migrate`-Lauf gegen eine Datei dieselbe
Spaltenaenderung erneut.

Der heutige Zustand ist ein **stiller Verlust**; ohne jenen Mechanismus wuerde
daraus eine **nicht konvergierende Migration**. Das ist kein Fortschritt,
sondern ein Tausch nach unten. Der Slice wartet deshalb auf
[`raw-sql-text-drift.md`](raw-sql-text-drift.md).

## Herkunft

Externe Durchsicht der neutralen Form (2026-09-08), am PostgreSQL- und
SQLite-Reverse nachgemessen.
