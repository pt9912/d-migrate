# Follow-up-Plan: Index-Spalten mit ASC/DESC-Reihenfolge

> Status: Draft (2026-05-07)
>
> Kontext: `CREATE INDEX ... (col ASC|DESC)` ist in PostgreSQL, MySQL und
> SQLite relevant. Das aktuelle neutrale Indexmodell speichert aber nur eine
> Liste von Spaltennamen. Dadurch gehen Sortierrichtungen beim Reverse-Lesen
> verloren und Forward-Generate kann sie nicht erzeugen.

---

## 1. Befund

Aktueller Code:

- `IndexDefinition.columns` ist `List<String>`.
- `IndexProjection.columns` ist ebenfalls `List<String>`.
- YAML/JSON-Parser und Builder serialisieren nur `columns: [name, ...]`.
- PostgreSQL, MySQL und SQLite rendern Index-Spalten als reine Identifierliste:
  `CREATE INDEX ... (col1, col2)`.
- PostgreSQL-Reverse liest `pg_index.indkey` und Spaltennamen, aber nicht
  `indoption` bzw. Sortieroptionen.
- MySQL-Reverse liest `information_schema.statistics`, aber nicht die
  `collation`-Spalte (`A`/`D`/`NULL`) fuer Sortierrichtung.
- SQLite-Reverse nutzt `PRAGMA index_info`, nicht `PRAGMA index_xinfo`; damit
  fehlen Sortierrichtung und weitere Index-Term-Details.

Folge:

- Ein bestehender Index `CREATE INDEX idx ON t (created_at DESC, id ASC)`
  roundtript heute als `columns = ["created_at", "id"]`.
- Ein spaeteres Generate erzeugt `CREATE INDEX idx ON t (created_at, id)`.
- Die Semantik kann sich fuer Query-Plans, ORDER-BY-Abdeckung und
  Cursor-/Resume-Muster aendern.

---

## 2. Ziel

Index-Spalten sollen ihre Richtung verlustarm transportieren:

- `ASC`
- `DESC`
- nicht angegeben / Dialekt-Default

Reverse-Normalisierung:

- `DESC` wird explizit transportiert.
- Default/omitted/metadata-`ASC` wird in der ersten Iteration als `null`
  normalisiert, sofern der Dialekt nicht sicher unterscheiden kann, dass der
  Benutzer explizit `ASC` geschrieben hat. Damit bleiben bestehende Indizes
  ohne explizite Richtung diff-stabil.
- Explizites `ASC` im neutralen Input bleibt fuer Forward-Generate erlaubt.
  Reverse darf es aber nicht versprechen, wenn die Datenbank-Metadaten diese
  Information nicht hergeben.

Nicht-Ziele in der ersten Iteration:

- PostgreSQL `NULLS FIRST` / `NULLS LAST`
- funktionale/expression indexes
- operator classes / collations
- partial indexes (`WHERE ...`)
- INCLUDE columns

Diese Punkte sollten nicht durch das `ASC`/`DESC`-Follow-up blockiert werden,
muessen aber im Modell so offen bleiben, dass sie spaeter additiv ergaenzbar
sind.

---

## 3. Vorgeschlagener Vertrag

Neues Modell:

```kotlin
data class IndexColumn(
    val name: String,
    val direction: IndexSortDirection? = null,
)

enum class IndexSortDirection {
    ASC,
    DESC,
}

data class IndexDefinition(
    val name: String? = null,
    val columns: List<IndexColumn>,
    val type: IndexType = IndexType.BTREE,
    val unique: Boolean = false,
)
```

Kompatibilitaet:

- Bestehende YAML/JSON-Form `columns: ["created_at", "id"]` bleibt gueltig
  und wird als `direction=null` gelesen.
- Neue Form:

```yaml
indices:
  - name: idx_orders_created
    columns:
      - name: created_at
        direction: desc
      - name: id
        direction: asc
```

- Beim Schreiben kann fuer Spalten ohne Richtung weiter die Kurzform
  ausgegeben werden, oder ein Format-Versionsentscheid erzwingt die
  Objektform. Fuer kleine Diffs ist eine gemischte Lesbarkeit sinnvoll:
  String fuer Default, Objekt fuer explizite Richtung.

---

## 4. Dialektverhalten

### PostgreSQL

Forward:

- `direction=null` -> nur Identifier
- `ASC` -> `identifier ASC`
- `DESC` -> `identifier DESC`

Reverse:

- Sortierrichtung aus `pg_index.indoption` pro Index-Key lesen.
- `DESC` ist in PostgreSQL ueber `INDOPTION_DESC` markiert; fehlendes Flag ist
  `ASC`/Default.
- Reverse normalisiert fehlendes `DESC`-Flag auf `direction=null`, nicht auf
  explizites `ASC`. PostgreSQL-Metadaten unterscheiden nicht verlaesslich
  zwischen vom Benutzer geschriebenem `ASC` und Default.

### MySQL

Forward:

- MySQL 8.0 unterstuetzt descending indexes; `ASC`/`DESC` kann pro Spalte
  gerendert werden.
- Fuer aeltere Zielversionen braucht es entweder eine Warnung oder eine
  Dialekt-/Version-Capability.

Reverse:

- `information_schema.statistics.collation` nutzen:
  - `A` -> `null` im Reverse-Vertrag der ersten Iteration
  - `D` -> `DESC`
  - `NULL` -> `null`
- Begruendung: `A` beschreibt die effektive aufsteigende Ordnung, nicht
  zwingend, dass `ASC` explizit im DDL stand. Forward darf explizites `ASC`
  dennoch rendern, wenn es im neutralen Input gesetzt ist.

### SQLite

Forward:

- SQLite erlaubt `ASC`/`DESC` in indexed columns.

Reverse:

- `PRAGMA index_xinfo(index_name)` statt nur `PRAGMA index_info(index_name)`
  verwenden; die `desc`-Spalte transportiert die Richtung.
- `desc=1` -> `DESC`; `desc=0` -> `null` fuer Default/aufsteigend.

---

## 5. Arbeitsplan

### AP 1: Modell und Format

- `IndexColumn` und `IndexSortDirection` einfuehren.
- `IndexDefinition.columns` migrieren oder kompatible Convenience-Factorys
  bereitstellen, damit bestehende Tests/Callsites mit Stringlisten kontrolliert
  umgestellt werden koennen.
- Schema-Parser akzeptiert String- und Objektform.
- Schema-Builder schreibt explizite Richtungen.
- Validator prueft:
  - nicht-leerer Spaltenname
  - Richtung nur `asc`/`desc`
  - keine leeren `columns`

### AP 2: Diff/Comparator

- Index-Diff muss Richtungsunterschiede erkennen:
  `created_at ASC` vs. `created_at DESC` ist ein geaenderter Index, nicht
  derselbe.
- Der interne Diff-/Vergleichs-Key fuer namenlose Indizes muss Richtung
  einbeziehen, damit
  `idx(a ASC)` und `idx(a DESC)` nicht kollidieren.
- `direction=null` und `direction=ASC` sind fuer Forward-Input
  unterschiedlich, aber Reverse-normalisierte Default-Indizes sollen nicht
  kuenstlich zu `ASC` werden. Diff-Regeln muessen diese Normalisierung
  konsistent anwenden.

### AP 3: DDL-Generatoren

- PostgreSQL, MySQL und SQLite rendern Richtung pro Spalte.
- Auto-generierte SQL-Indexnamen sind getrennt vom internen Diff-Key zu
  behandeln:
  - Fuer einen einzelnen namenlosen Index bleibt der bisherige Name stabil.
  - Wenn mehrere namenlose Indizes dieselben Spalten, aber unterschiedliche
    Richtungen haben, muss die Namensgenerierung eine deterministische
    Kollisionsauflosung haben, z. B. Suffix aus Richtungen oder Hash.
  - Der interne Diff-Key darf Richtung enthalten, auch wenn der SQL-Name aus
    Kompatibilitaetsgruenden meist stabil bleibt.

### AP 4: Reverse-Reader

- PostgreSQL: `indoption` auslesen.
- MySQL: `statistics.collation` auslesen.
- SQLite: `index_xinfo` verwenden.
- `IndexProjection` auf strukturierte Spalten erweitern.

### AP 5: Specs

- `spec/neutral-model-spec.md`, `spec/design.md` und relevante
  DDL-Generation-Regeln um Index-Spaltenrichtung ergaenzen.
- Explizit dokumentieren, welche erweiterten Index-Features weiterhin offen
  bleiben: Nulls ordering, expressions, operator classes, partial indexes,
  INCLUDE columns.

---

## 6. Tests

- Modell-/Format-Roundtrip:
  - alte Stringform bleibt lesbar.
  - neue Objektform mit `direction: desc` roundtript.
- PostgreSQL Forward:
  - `IndexColumn("created_at", DESC)` -> `"created_at" DESC`.
  - gemischter Index `(created_at DESC, id ASC)`.
- PostgreSQL Reverse:
  - `CREATE INDEX ... (created_at DESC, id ASC)` wird als
    `created_at DESC`, `id null/default` gelesen.
  - Default-/ASC-only-Index erzeugt beim Reverse -> Forward keine
    unnoetigen `ASC`-Diffs.
- MySQL Forward/Reverse analog, soweit Zielversion es unterstuetzt.
- SQLite Forward/Reverse mit `PRAGMA index_xinfo`.
- Diff-Test: nur Richtung geaendert -> `indicesChanged`.
- Namens-Kollisionstest: zwei namenlose Indizes mit gleicher Spaltenliste und
  unterschiedlicher Richtung bekommen deterministische unterschiedliche
  SQL-Namen oder werden validiert abgewiesen.

---

## 7. Akzeptanz

- `CREATE INDEX` kann pro Spalte `ASC`/`DESC` erzeugen.
- Reverse -> Forward verliert die Sortierrichtung fuer einfache
  Spaltenindizes nicht, soweit sie als `DESC` oder expliziter neutraler Input
  modelliert ist.
- Bestehende Schemas mit `columns: ["a", "b"]` bleiben kompatibel.
- Normale Indizes ohne explizite Richtung erzeugen weiterhin die bisherige
  SQL-Form ohne unnoetige Diffs.
- Reverse normalisiert effektives `ASC`/Default so, dass bestehende Indizes
  nicht ploetzlich explizite `ASC`-Syntax erzeugen.
