---
id: oracle-desc-index-becomes-function-based
title: "Eine DESC-Indexspalte macht in Oracle einen function-based Index, den der Reverse falsch zurueckliest"
status: open
---

# DESC-Indexspalte wird in Oracle ein function-based Index

## Befund

Live gemessen (2026-09-06, `gvenzl/oracle-free:23-slim-faststart`):

```sql
CREATE INDEX idx_d1_a ON d1 (a DESC);
-- user_indexes.index_type = 'FUNCTION-BASED NORMAL'
```

Oracle setzt eine absteigende Indexspalte intern als Ausdruck um. Damit
zeigt `all_ind_columns.column_name` fuer diesen Index nicht mehr die
Spalte `A`, sondern eine systemgenerierte Ausdrucksspalte (`SYS_NC0000n$`);
der Ausdruck selbst steht in `all_ind_expressions`.

Beide Oracle-Pfade rendern die Richtung bereits:
`OracleIndexDdlBuilder.renderIndexColumn` haengt `DESC` an — der
Generate-Pfad seit Slice 2, der Diff-Pfad seit Sub-Slice 5b (dieselbe
geteilte Quelle).

Der Reverse haelt dagegen nicht mit:
`OracleMetadataQueries.scanIndexes` liest `column_name` woertlich (die
Sortierrichtung ueber `descend` liest es korrekt, den Spaltennamen nicht),
und `OracleSchemaReader` setzt `IndexType.BTREE` hart.

Folgen:

- Ein nativer Oracle-DESC-Index kommt als Index auf eine Spalte zurueck,
  die es in der Tabelle nicht gibt — die daraus erzeugte DDL ist nicht
  ausfuehrbar.
- Nach einem `schema migrate --execute`, das einen DESC-Index anlegt,
  meldet der Postcompare-Vergleich Drift, obwohl die Migration korrekt
  lief (Falschmeldung).

## Einordnung

Nicht durch Sub-Slice 5b verursacht: der Diff-Pfad nutzt dieselbe
Render-Quelle wie der Generate-Pfad und aendert an der erzeugten DDL
nichts. 5b macht die Folge nur erstmals im Migrate-/Postcompare-Pfad
sichtbar.

Die Wurzel liegt im Reverse-Read (Slice 1); die Aufloesung gehoert
sachlich zu **Slice 6** (Function-based- und Bitmap-Indizes), der die
Unterscheidung ohnehin einfuehren muss.

Heute dokumentiert `spec/ddl-generation-rules.md` nur, dass
Function-based- und Bitmap-Indizes nicht unterschieden werden und jeder
Index als `BTREE` gilt (W102). Dass eine **DESC-Spalte** einen solchen
Index ueberhaupt erst erzeugt — und dabei den Spaltennamen verliert —
steht nirgends.

## Moegliche Loesungsrichtungen

1. **Reverse haerten** (Minimum, unabhaengig von Slice 6):
   `scanIndexes` um `all_ind_expressions` erweitern und eine
   Ausdrucksspalte auf die zugrundeliegende Spalte zurueckfuehren, wenn
   der Ausdruck genau ein Spaltenverweis ist — dann round-trippt der
   DESC-Index korrekt als BTREE mit Richtung.
2. **Vorwaerts warnen**: im `OracleIndexDdlBuilder` eine Notiz setzen,
   sobald eine Richtung gerendert wird, solange (1) nicht steht. Wirkt
   auf Generate- und Diff-Pfad gleichzeitig (geteilte Quelle).
3. **Im Rahmen von Slice 6** vollstaendig aufloesen: `IndexType` um
   function-based unterscheiden und beide Richtungen sauber abbilden.

Aktivierungsbedingung: ein Schema mit absteigender Indexspalte gegen
Oracle — oder Slice 6, was frueher kommt. **Vor 5e pruefen**, weil der
Gate-Fall den Postcompare scharf schaltet und dieser Fall dort als
Falschmeldung auftraete.
