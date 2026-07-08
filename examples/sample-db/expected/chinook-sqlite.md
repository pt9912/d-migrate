# Erwartete Baseline — Chinook SQLite-Round-Trip (Phase 2b)

Gepinnt durch `examples/sample-db/scripts/smoke-sqlite.sh`
(`make sample-db-sqlite-smoke`). Quelle + Ziel: SQLite. Sample: Chinook
(`lerocha/chinook-database@7f677725`, `Chinook_Sqlite.sqlite`, SHA256-gepinnt).

**Same-Dialect-Round-Trip** (SQLite→SQLite, wie Phase 1 Pagila/PG — *nicht*
cross-dialect). SQLite hat **keinen Server**: die CLI arbeitet via `docker run`
(als Host-User, non-root-Image) direkt gegen die bind-gemountete `.db`-Datei;
das Zielschema baut `sqlite3` aus der generierten DDL. Gepinnt:

1. **generate-Notes == `chinook-sqlite.notes.txt`** (3× W200).
2. **Zeilen-Parität** Quelle == Ziel (11 Tabellen).
3. **Decimal(10,2)→REAL** ohne Datenverlust (Track.UnitPrice-Summe).

## Note-Klassen (kein Defekt — SQLite-Typ-Affinität)

| Code | Wo | Anzahl | Erklärung |
|---|---|---|---|
| `W200` | generate | 3 | `Decimal(10,2)` → `REAL` (SQLite kennt kein DECIMAL). Konservative Präzisionswarnung — für Chinooks 2-Dezimal-Preise **kein** echter Verlust (siehe unten). Spalten: `Invoice.Total`, `InvoiceLine.UnitPrice`, `Track.UnitPrice`. |
| `R201` | reverse | 12 | `NVARCHAR(n)` → `text`. SQLite ist dynamisch typisiert; `NVARCHAR(n)` ist nur eine Affinitäts-Deklaration (Länge wird ignoriert), funktional == TEXT. Erscheint im **reverse**-Report, nicht im generate (daher nicht in der generate-Notes-Baseline). |

Beide sind **SQLite-Typ-Affinität**, kein Datenverlust. SQLite speichert Werte
typunabhängig; die Degradationen betreffen nur die deklarierte Typ-Zeichenkette.

## Datenbelegt: Decimal→REAL ohne Verlust

`Track.UnitPrice` (Chinook: `0.99`/`1.99`) round-trippt exakt; die Summe über alle
3503 Tracks ist **3680.97 == 3680.97** (Quelle == Ziel). REAL (IEEE-754 double)
stellt 2-Dezimal-Preise dieser Größenordnung verlustfrei dar. Die W200-Warnung ist
korrekt (Präzision *kann* bei mehr Nachkommastellen leiden), aber für diesen
Datenbestand greift sie nicht.

## Pflege

- Notes neu pinnen: `expected/chinook-sqlite.notes.txt` löschen,
  `make sample-db-sqlite-smoke` laufen lassen (Bootstrap), prüfen, committen,
  erneut laufen lassen (muss dann grün vergleichen).
