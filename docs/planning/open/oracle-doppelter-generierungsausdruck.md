---
id: oracle-doppelter-generierungsausdruck
title: "Oracle verweigert zwei berechnete Spalten mit demselben Ausdruck"
status: open
---

# Oracle verweigert zwei berechnete Spalten mit demselben Ausdruck

## Befund (gemessen, Oracle 23)

```sql
CREATE TABLE "generated" (
  "id"    NUMBER(9) PRIMARY KEY,
  "qty"   NUMBER(9) NOT NULL,
  "price" NUMBER(9) NOT NULL,
  "total_virtual"      NUMBER GENERATED ALWAYS AS ("qty" * "price") VIRTUAL,
  "total_materialized" NUMBER GENERATED ALWAYS AS ("qty" * "price") MATERIALIZED
)
```

→ **`ORA-54015: Duplicate column expression was specified.`**

Die Tabelle entsteht nicht. Mit zwei **verschiedenen** Ausdruecken
(`"qty" * "price"` und `"qty" + "price"`) laeuft dasselbe Statement durch.

Die uebrigen vier Ziele nehmen zwei berechnete Spalten mit demselben Ausdruck
an (PostgreSQL, MySQL, SQLite, SQL Server — ungemessen fuer diesen Einzelfall,
aber keiner von ihnen kennt eine solche Einschraenkung).

## Folge

Ein Schema, das zwei berechnete Spalten mit gleichem Ausdruck fuehrt — etwa
eine gespeicherte und eine virtuelle Variante derselben Rechnung, oder zwei
gleich berechnete Spalten unter verschiedenen Namen — laesst sich nach Oracle
**nicht** anlegen. `schema generate` rendert es, und `schema migrate --execute`
scheitert erst am Server, mitten in der Ausfuehrung.

## Was der Schnitt klaeren muss

- **Vorab erkennen statt scheitern lassen.** Der Fall ist statisch im Soll
  sichtbar (zwei `ColumnGeneration.Computed` einer Tabelle mit gleichem
  Ausdruckstext) — dieselbe Gestalt wie die bestehenden Generate-Notizen
  (`E05x`), also ein Preflight-Befund fuer ein Oracle-Ziel, kein Laufzeitfehler.
- **Wie streng der Vergleich ist.** Oracle vergleicht vermutlich die
  normalisierte Ausdrucksform, nicht den Text; ob `"qty"*"price"` und
  `"qty" * "price"` fuer Oracle derselbe Ausdruck sind, ist ungemessen. Eine
  Textgleichheits-Pruefung faende die offensichtlichen Faelle und uebersaehe
  die normalisierten.
- **Ob es auch zwei virtuelle trifft** (gemessen wurde virtuell + materialisiert)
  und ob ein Ausdrucks-**Index** mit demselben Ausdruck denselben Fehler
  ausloest.

## Herkunft

Fiel beim Bau des Schreibpfad-Stuecks von
[`generated-column-expression-dropped.md`](../done/generated-column-expression-dropped.md)
auf (2026-09-12) — die Testtabelle fuer beide Speicherformen liess sich mit
demselben Ausdruck nicht anlegen.
