---
id: oracle-index-auf-virtueller-spalte
title: "Ein Index auf einer virtuellen Oracle-Spalte kommt als Ausdrucks-Index zurück"
status: done
---

# Ein Index auf einer virtuellen Oracle-Spalte kommt als Ausdrucks-Index zurück

## Behoben (2026-09-12) — und die erste Erklärung war falsch

Der Befund unten stimmt in seiner Wirkung und **nicht in seiner Ursache**. Er
sagte, Oracle ersetze die Spalte durch eine unsichtbare Systemspalte
(`SYS_NC0000n$`) und der echte Schlüssel stehe nur in `ALL_IND_EXPRESSIONS`.
Nachgemessen, beide Sichten nebeneinander:

```
all_ind_columns:       ix_total -> line_total          ix_nm -> SYS_NC00005$
all_ind_expressions:   ix_total -> "quantity"*"unit_price"
                       ix_nm    -> UPPER("nm")
all_indexes:           beide    -> FUNCTION-BASED NORMAL
```

`ALL_IND_COLUMNS` nennt für den Index über der virtuellen Spalte die **echte
Spalte**. Eine `SYS_NC…` steht dort nur beim *echten* Ausdrucks-Index. Oracle
führt beide Bücher — und sagt damit selbst, welcher Fall vorliegt.

**Der Fehler lag also im Leser, nicht im Server:** `resolveIndexColumns` hörte
allein darauf, *ob* eine Zeile in `ALL_IND_EXPRESSIONS` existiert. Für einen
Index über einer virtuellen Spalte existiert sie — und damit wurde ein
brauchbarer Spaltenname durch einen Ausdruck ersetzt. Die Prüfung fragt jetzt
zuerst, ob der Schlüssel eine Systemspalte ist; nur dann steht der echte in der
Ausdruckszeile.

**Was das für den vorgeschlagenen Schnitt bedeutet.** „Der Leser könnte einen
Ausdrucks-Index, dessen Ausdruck wortgleich der Berechnung einer Spalte
entspricht, wieder als Spaltenindex melden" — dieser Weg war gebaut und wurde
**wieder entfernt**, als die Messung den einfacheren Ort zeigte. Er hätte
funktioniert, aber auf einem Textvergleich beruht, wo Oracle eine eindeutige
Auskunft gibt. Die Gegenrichtung (`UPPER("nm")` bleibt ein Ausdruck) ist damit
nicht mehr eine Bedingung, die man einhalten muss, sondern fällt von selbst
heraus.

**Die zweite offene Frage ist gemessen:** Oracle ist allein. Auf PostgreSQL,
MySQL, SQL Server und SQLite kommt derselbe Index als Spaltenindex zurück —
alle vier nachgemessen, nicht geschlossen.

Live belegt (`OracleVirtualColumnIndexIntegrationTest`): die Doppelbuchführung
selbst, die zurückgegebene Spalte, der echte Ausdrucks-Index daneben, und ein
handgeschriebenes Soll mit `columns: [line_total]`, das jetzt konvergiert.

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
