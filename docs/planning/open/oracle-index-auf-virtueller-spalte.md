---
id: oracle-index-auf-virtueller-spalte
title: "Ein Index auf einer virtuellen Oracle-Spalte kommt als Ausdrucks-Index zurück"
status: open
---

# Ein Index auf einer virtuellen Oracle-Spalte kommt als Ausdrucks-Index zurück

## Der Befund (gemessen 2026-09-12, Oracle 23)

Angelegt wird ein gewöhnlicher Spaltenindex:

```sql
CREATE TABLE "ocl" (
  "quantity"   NUMBER(9),
  "unit_price" NUMBER(12,2),
  "line_total" NUMBER(14,2) GENERATED ALWAYS AS ("quantity" * "unit_price") VIRTUAL
);
CREATE INDEX "ix_total" ON "ocl" ("line_total");
```

Zurück kommt er als **Ausdrucks**-Index:

```
IndexDefinition(name=ix_total, columns=[expr:"quantity"*"unit_price"], …)
```

Oracle legt einen Index über einer virtuellen Spalte als funktionsbasierten
Index an. In `ALL_IND_COLUMNS` steht an der Stelle eine unsichtbare
Systemspalte (`SYS_NC0000n$`), den echten Schlüssel liefert
`ALL_IND_EXPRESSIONS` — und genau von dort liest ihn `OracleMetadataQueries
.scanIndexes`. Aus Sicht des Lesers ist das korrekt; aus Sicht des
Round-Trips ist es eine andere Form als die geschriebene.

## Die Folge

Ein Schema, das den Index als Spaltenindex führt (`columns: [line_total]`),
konvergiert gegen Oracle **nie**:

- `schema compare` meldet bei jedem Lauf denselben Unterschied,
- `schema migrate --execute` endet im Post-Compare mit Drift (Exit 5), obwohl
  der Lauf getan hat, was verlangt war,
- und weil Herkunft nur bei sauberem Post-Compare entsteht, plant auch der
  nächste Lauf dasselbe wieder.

Belegt beim Bau der berechneten Spalten: die Spec
`OracleComputedExpressionChangeIntegrationTest` musste den Index in der
**Serverform** (`expr:"quantity"*"unit_price"`) im Soll führen, damit der
Aufbau überhaupt grün wird.

## Was der Schnitt klären muss

- **Wo die Form angeglichen wird.** Der Leser könnte einen Ausdrucks-Index,
  dessen Ausdruck wortgleich der Berechnung einer Spalte derselben Tabelle
  entspricht, wieder als **Spaltenindex** melden — die Angabe dafür liegt
  vor, seit der Reverse die Berechnung trägt. Das ist eine Leseregel, keine
  Textanalyse: verglichen wird gegen einen bekannten Text, nicht geparst.
- **Ob dieselbe Frage andere Dialekte betrifft.** Bei MySQL und SQL Server ist
  ein Index über einer berechneten Spalte ein gewöhnlicher Spaltenindex
  (ungemessen — vor dem Bau zu prüfen). PostgreSQL kennt nur die gespeicherte
  Form; dort ist es ebenfalls ein Spaltenindex.
- **Die Gegenrichtung.** Ein Schema, das den Index absichtlich als Ausdruck
  führt (`UPPER("name")`), darf nicht plötzlich als Spaltenindex gelesen
  werden. Die Regel greift nur bei wortgleicher Übereinstimmung mit einer
  Spaltenberechnung.

## Was heute schon davon abhängt

`OracleDiffTableOps.renderAlterColumnGeneration` blockt eine Änderung des
Berechnungsausdrucks, wenn ein Index auf der Spalte liegt (`ORA-54022`). Weil
der Index zurückgelesen als Ausdruck erscheint, sucht der Renderer **beide**
Formen — Spaltenname und wortgleicher Ausdruck. Wird der Leser angeglichen,
kann die zweite Suche dort wieder entfallen.

## Herkunft

Nebenbefund beim Bau des Slice
[`generated-column-expression-dropped.md`](generated-column-expression-dropped.md),
gemessen an Oracle 23 über den Reverse.
