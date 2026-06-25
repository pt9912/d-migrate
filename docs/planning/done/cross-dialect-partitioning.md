# Cross-Dialect-Partitionierung (AP6 — MySQL-Reverse/-Generate + Cross-Dialect-Mapping)

> **Status:** in-progress/-Slice (AP6.2 angefangen). Folge-Slice der graduierten
> [Partitions-Hierarchie-Rekonstruktion](../done/partition-hierarchy-reconstruction.md)
> (PG-first), gemäß [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md),
> Punkt 3+4 (Cross-Dialect-Form-Divergenz + MySQL-Reverse als eigener Slice).
>
> **Stand & Wiedereinstieg (2026-06-24):** Gate-ADR
> [ADR 0020](../../adr/0020-cross-dialect-partitioning-mysql.md) **accepted** (Review-Runde
> eingearbeitet). **AP6.2 Teil 1 erledigt** (MySQL-Generate): RANGE/LIST → `… COLUMNS(key)`,
> Temporal-Literal-UTC-Normalisierung (W129) + Nicht-UTC-`action_required` (E061), W112-Text
> auf `from`-Verwurf umgestellt; Ledger W129/E061 (YAML) eingetragen; Unit-Tests grün.
> **AP6.1 erledigt** (MySQL-Reverse): `MysqlPartitionReader` + `listPartitions`-Query
> (`information_schema.PARTITIONS`) erfasst RANGE (`to`)/LIST (`values`)/HASH (benannt); Spaltenschlüssel
> aus PARTITION_EXPRESSION (Backticks gestrippt); Unit-Tests + Live-Integration (MySQL-Testcontainer) grün.
> **AP6.2 Teil 2 erledigt** (Generate-Carve-Outs): DECIMAL/FLOAT/Nicht-Integer-HASH-Schlüssel →
> skip+`action_required` (E062, §1/§3); HASH-Platzierungs-Note (W130, §3); LIST-`DEFAULT` →
> verworfen + `action_required` (E063 Transfer-Datenverlust, §4); Ledger E062/E063/W130; 4 Unit-Tests.
> Helper refactored (skipNote/partitionDiagnostics/effectivePartitions/renderPartition gegen LongMethod).
> **AP6.3 (Index-Heben) erledigt** (§5): kind-lokale Indizes werden nicht mehr still verworfen —
> nicht-unique auf die Tabelle **gehoben** (dedupliziert nach Spalten/Typ, N Partitionen → 1 Index;
> Namenskollision → eindeutiger Name + W131; INFO `PARTITION_INDEX_LIFTED` je gehobenem Index);
> **UNIQUE** kind-lokal → NICHT gehoben, skip + `action_required` (E064: invalides DDL ohne
> Partitionsschlüssel + partition-lokale≠globale Eindeutigkeit). Ledger E064/W131; 3 Unit-Tests.
> **AP6.3-FK (FK-Carve-Out) erledigt** (ADR §5 Teil 2): MySQL/InnoDB unterstützt keine FKs auf
> partitionierten Tabellen (beide Richtungen) — FK auf/zu einer **tatsächlich** partitionierten Tabelle
> übersprungen + `action_required` (E065) über **alle drei** Emissionspfade (Inline-Ref, explizite
> FOREIGN_KEY-Constraint, zirkulärer ALTER). „Tatsächlich" = `partitionedTables`-Set während `generateTable`
> befüllt (skip via E055/E062 lässt Tabelle unpartitioniert → FK bleibt gültig). Ledger E065; 5 Unit-Tests +
> **deckte invalides DDL im `full-featured`-Golden auf** (`orders` war partitioniert UND trug 2 FKs → Goldens
> regeneriert: FKs jetzt korrekt verworfen statt invalides DDL gepinnt).
> **AP6.4 (Cross-Smoke PG→MySQL) erledigt + GRÜN**: `make sample-db-cross-smoke-pg2my` live grün, der
> **End-to-End-Live-Apply-Beweis**. `payment` round-trippt als EINE partitionierte MySQL-Tabelle —
> **16049 Zeilen** (= Quelle, keine Duplikation; war 32098), 7 MySQL-Partitionen, 0 Kind-Tabellen, 0 FKs.
> Harness-Gates mitgezogen: tgt_tables 22→15 logisch (Kinder gefiltert), Parity-Loop schließt Kinder aus,
> alter P2-Duplikations-NOTE → hartes Partitions-Integritäts-Gate. Notes-Baseline neu gepinnt
> (`E055` weg; `W112`/`W129` neu; `W100` 24→17, `W118` 8→1 — 7 Kind-Duplikate entfallen); AP6.3 Index-Heben
> feuert live (`PARTITION_INDEX_LIFTED`). `expected/pagila-cross.md` aktualisiert (Finding P2-pg2my → gelöst).
> **AP6.5 (MySQL→PG-Richtung) erledigt** (ADR §6): `MysqlPartitionReader` hebt MySQLs knappere native
> Form ins **vollständige** neutrale Modell (`reconstructNeutralBounds`): RANGE-`from` aus der Kontiguität
> (`fromₙ = toₙ₋₁`, erstes `from = MINVALUE`, arität-treu); HASH `modulus = n`/`remainder = Ordinalindex`
> aus `PARTITIONS n`; LIST trägt `values` schon. So generiert der bestehende PG-Generator unverändert
> valides PG-DDL (`FOR VALUES FROM (MINVALUE) TO …`, `WITH (MODULUS n, REMAINDER i)`). Reader-Unit-Tests
> aktualisiert + Live-Integration (echtes MySQL: RANGE-`from` + HASH-`PARTITIONS 4`→modulus/remainder) +
> PG-Round-Trip-Generate-Tests (rekonstruierte Form → valides PG-DDL). tz-Verlust nicht invertierbar
> (UTC-Annahme dokumentiert, kein Raten).
> **Damit ist AP6 codeseitig komplett.** **Offene Folgen (klein, nicht AP6-blockierend):**
> **(a)** kind-lokale FK-Constraints erfassen+melden — pagila deklariert payments FKs auf den Kindern
> (PG erlaubt das); AP2a erfasst kind-lokale Indizes, aber keine kind-lokalen FKs → sie fallen still weg
> (MySQL-Ergebnis korrekt, da FKs dort ohnehin verboten; nur die E065-Transparenz fehlt für diesen Fall).
> Mini-Folgen: spec/ledger.md-Summary-Sync (W125–W131, E061–E065); LIST-`DEFAULT`-**Preflight** (§4, Transfer-Seite —
> die Generate-Note E063 ist da, die Preflight-Integration noch nicht).
> **Trigger:** Die PG-first-Scheibe hat das strukturierte `PartitionDefinition`-Modell,
> den PG-Reverse-Capture (Kinder + Grenzen + kind-lokale Indizes) und den
> partitions-bewussten Comparator/Fingerprint geliefert. Der MySQL-Pfad konsumiert das
> Modell heute **nur teilweise** und es gibt **keinen** MySQL-Reverse — ein voller
> Cross-Dialect-Round-Trip fehlt.
> **Aktivierung (2026-06-24): priorisiert + Prämisse belegt.** `make sample-db-cross-smoke-pg2my`
> bricht datenbelegt — generierte MySQL-DDL invalid (`PARTITION BY RANGE (datetime_col)` +
> tz-behaftetes Literal `'…+00'`), Notes-Baseline veraltet (`E055` weg, `W112` neu).
> **Gate-ADR geschrieben (proposed):** [ADR 0020](../../adr/0020-cross-dialect-partitioning-mysql.md) —
> fixiert die Mapping-Entscheide (RANGE→`RANGE COLUMNS`, Temporal-Literal-Normalisierung→W129,
> HASH, LIST-`DEFAULT`, Index-Heben, MySQL→PG). **Muss accepted sein, bevor AP6.2/AP6.3 codiert werden.**
> Der Slice wandert nach `in-progress/`, sobald der erste Implementierungs-Commit (AP6.2) landet.

## Review-Härtung (Runde 1, 2026-06-25)

Multi-Winkel-Code-Review der AP6-Range (`3f5427dc..HEAD`) + ein hoch-Recall-Re-Review des Fixes.
Alle P1/P2 und die abbildbaren P3 sind behoben (Build grün: Tests + Detekt + koverVerify für
driver-common/driver-mysql/driver-postgresql/formats).

- **P1 #1 (Phantom-tz-Offset):** Die alte, unverankerte Offset-Regex fraß das `-TT` einer Datumsgrenze
  als Zeitzone (`'2022-02-01'` → `'2022-02'`) und verschob still Partitionsgrenzen. Ersetzt durch eine
  **strukturierte, verankerte** Zerlegung (`MysqlPartitionBoundRenderer`): ein Offset wird nur erkannt,
  wenn ihm eine Zeitkomponente vorausgeht.
- **P2 #2 (Non-UTC):** Grenze bleibt jetzt **unverändert** (kein stiller Shift) und wird **pro Partition**
  als E061 gemeldet (statt tabellenweit dedupliziert).
- **P2 #3 (unquotierte Temporal-Grenzen):** Wurzel war die Fixture `full-featured.yaml` (betraf **beide**
  Dialekt-Goldens — PG `FOR VALUES FROM (2024-01-01)` ist Integer-Arithmetik). Bounds in Fixture, JSON-
  Contract-Fixture, Spec-Beispielen ([schema-reference](../../../spec/schema-reference.md),
  [neutral-model-spec](../../../spec/neutral-model-spec.md)) und [Anwenderhandbuch](../../user/anwenderhandbuch.md)
  quotiert; 6 Goldens regeneriert. **Vertrag:** Bound-Literale tragen ihr SQL-Quoting im Modell
  (identisch für alle Dialekte) — der Generator quotet **nicht** defensiv (siehe Re-Review C).
- **P2 #4 (LIST-Temporal):** Normalisierung nur für **einspaltige** LIST-Schlüssel (mehrspaltige Tupel
  bleiben intakt) statt `firstOrNull()`.
- **P2 #6 (funktions-basierte MySQL-Partitionierung):** `parseColumnKey` erkennt Nicht-Spalten-Ausdrücke
  (`YEAR(d)`) und erfasst die Partitionierung **nicht** (kein Müll-Key) statt `RANGE COLUMNS (\`year(d)\`)`.
- **P3 erledigt:** #8 (`indexSignature` schließt `where` ein), #9 (gemeinsamer `PartitionBoundScanner` in
  driver-common — PG- + MySQL-Parser teilen ihn), #10 (`ManualActionRequired`-Factory für E055/E061–E064),
  #11 (typisierte Temporal-Behandlung als eigene Klasse — zugleich Detekt-Split), #13 (Namens-/`objectName`-
  Dedup), #15 (`reconstructRangeFrom` defensiver Pfad explizit), #14 (Coverage für alle neuen Zweige).

**Re-Review-Befunde (zusätzlich, behoben):**
- **A — Spec/Doku-Drift:** Die Partitions-Beispiele in Spec/Handbuch zeigten unquotierte Bounds (für PG
  ohnehin invalides DDL). Quotiert + Listen-Form vereinheitlicht.
- **C — PG/MySQL-Quoting-Asymmetrie:** Defensives Quoting nur auf MySQL-Seite hätte dieselbe Definition
  für MySQL valide, für PG invalide gemacht (per-dialect drift). Entfernt → beide Dialekte verlassen sich
  auf den Modell-Vertrag (oben).
- **B — Regex-Härtung:** `Z` (Zulu = UTC) und Kleinbuchstabe-`t`-Trenner werden jetzt als gültige
  ISO-8601-Formen erkannt/normalisiert.

**Bewusst dokumentiert, kein Code-Change:**
- **#5** (`renderMysqlUpperBound` indiziert `keyTypes` per `to`-Ordinal): per Modell-Vertrag korrekt (das
  `to`-Tupel ist je Schlüsselspalte in Schlüssel-Reihenfolge); `getOrNull` bleibt defensiv.
- **#7** (Reverse-Rekonstruktion bricht **Cross-Dialect**-Fingerprint-Gleichheit): Cross-Dialect-Fingerprint-
  Gleichheit ist **kein Ziel** (Typen/DEFAULT-Verwurf/tz-Normalisierung divergieren ohnehin). Same-Dialect-
  Round-Trips bleiben konsistent (beide Seiten durchlaufen dieselbe Rekonstruktion).
- **#12** (Partitions-Bewusstsein im gemeinsamen `AbstractDdlGenerator` statt MySQL-`partitionedTables`-
  Seitenkanal): größeres Altitude-Refactoring → eigenes Ticket
  [`../open/partition-generator-shared-awareness.md`](../open/partition-generator-shared-awareness.md).

## Ziel

Ein partitioniertes Schema verlustarm **in beide Richtungen** zwischen PostgreSQL und
MySQL round-trippen: MySQL-Partitionen **reverse-erfassen** (heute partitions-blind),
MySQL-**Generate** das strukturierte Modell für **alle drei Strategien** konsumieren
lassen, und die bewussten Cross-Dialect-Verlust-/Transformationspunkte **strukturiert
entscheiden** (eine ADR, analog fulltext/geometry).

## Eine ADR für die Cross-Dialect-Carve-Outs

Alle verlustbehafteten/transformierenden Mapping-Entscheide dieses Slices gehören in
**eine** AP6-ADR (Carve-Out = strukturierte Entscheidung mit ADR-Präzedenz, nicht
„entweder/oder im Code" — projekteigene „No-Carveouts"-Linie, vgl. das fulltext-/geometry-Muster).
Sie deckt: RANGE-Datums-Wrapper + `from`-Verwurf (AP6.2), HASH-Form-Divergenz (AP6.2/AP6.5),
LIST-`DEFAULT`-ohne-MySQL-Pendant (AP6.2), Partition-Index-Abbildung (AP6.3), und die
**Note-/Ledger-Codes** (unten). Diese ADR ist Voraussetzung, bevor AP6.2/AP6.3 codiert werden.

## Arbeitspakete

- **AP6.1 — MySQL-Reverse-Capture der Partitionen (alle Strategien; dieselbe AP1-Klasse Arbeit).**
  Kein MySQL-Reader befüllt heute `PartitionConfig.partitions` (nur `MysqlDdlGenerator`
  konsumiert es). MySQL exponiert Partitionen über `information_schema.PARTITIONS`
  (PARTITION_NAME, PARTITION_METHOD, PARTITION_EXPRESSION, PARTITION_DESCRIPTION,
  SUBPARTITION_*). Strategie/Schlüssel + Kind-Grenzen für **RANGE/LIST/HASH** ins
  strukturierte Modell heben — analog zum PG-`PostgresPartitionBoundParser`, aber für MySQLs
  `VALUES LESS THAN`/`VALUES IN`/`PARTITIONS n`-Form. Bound-Parser/Normalisierer = Hotspot.

- **AP6.2 — Cross-Dialect-Generate-Divergenz PG→MySQL (alle Strategien).** Der MySQL-Generate
  existiert (`MysqlIndexPartitionDdlHelper`), aber die Form-Divergenz ist je Strategie verschieden:
  - **RANGE:** (a) PG hat `from`+`to` (Lücken erlaubt), MySQL nur `VALUES LESS THAN` (Obergrenze,
    Kontiguität) → `from`-**Verwurf** (heute schon). (b) MySQL-RANGE auf Datums-/Zeitspalte braucht
    einen **Integer-Ausdruck** (`UNIX_TIMESTAMP(col)`/`TO_DAYS(col)`/`YEAR(col)`) **oder**
    `RANGE COLUMNS(col)` — eine rohe timestamptz-Grenze (`'2022-02-01 00:00:00+00'`) lehnt MySQL ab.
    Das ist eine **Transformation**, kein Verwurf (anderer Verlust-Typ → eigener Note-Code, s. u.).
  - **LIST:** nahe an PG (`VALUES IN`), **aber** PGs `DEFAULT`-Partition hat **kein** MySQL-LIST-Pendant
    (MySQL kennt keinen LIST-Catch-all) → Carve-Out/Note.
  - **HASH:** **echte Divergenz.** PG-HASH ist `modulus`/`remainder` **pro Kind**; MySQL-HASH ist
    ausdrucks-+anzahlbasiert (`PARTITION BY HASH(expr) PARTITIONS n`, MySQL verteilt selbst). N PG-Kinder
    (modulus=N) → MySQL `PARTITIONS N`; die per-Kind-`remainder` entfällt. → ADR-Carve-Out.

- **AP6.3 — MySQL-Generate für `partition.indices` (Folgebefund aus dem AP2a-Review).** *(AP2a selbst —
  Fingerprint-/Comparator-Bewusstsein der kind-lokalen Indizes — ist geliefert; offen ist nur der MySQL-
  Generate.)* `MysqlIndexPartitionDdlHelper` iteriert nur `table.indices` und **verwirft die kind-lokalen
  Partition-Indizes still**. MySQL teilt Indizes über alle Partitionen (kein per-Partition-Index wie PG),
  also ist die Abbildung ein Carve-Out: kind-lokale Indizes **auf den Parent heben** *oder* per Note melden —
  **die AP6-ADR entscheidet welches** (nicht offen lassen). (SQLite verwirft via `E055` die ganze
  Partitionierung → dort moot.)

- **AP6.4 — PG→MySQL-Round-Trip + Harness.** `make sample-db-cross-smoke-pg2my` grün bekommen;
  Comparator/Fingerprint sind seit der PG-Scheibe bereits partitions-bewusst. Baselines neu pinnen + erklären.

- **AP6.5 — MySQL→PG-Richtung (Rück-Mapping-Semantik).** Spiegelbild zu AP6.2: MySQLs kontiguierliche
  `VALUES LESS THAN` müssen in PGs `from`/`to`-Paare **rekonstruiert** werden (`fromₙ = toₙ₋₁`, erstes
  `from = MINVALUE`); HASH `PARTITIONS n` → `modulus=n, remainder=0..n-1` synthetisieren; LIST nahe.
  Der Datums-Wrapper ist hier rückzugewinnen (oder per Note als nicht-invertierbar zu markieren).

## Note-/Ledger-Codes (Teil der AP6-ADR)

- `from`-Verwurf bei RANGE: heute **W112** (RANGE-Anpassung). **W112 ist ledger-rückständig** —
  vgl. [`../done/index-prefix-length-model.md`](../done/index-prefix-length-model.md) — also Ledger-Eintrag
  in [`spec/ledger.md`](../../../spec/ledger.md) (führt W100–W112) nachziehen.
- Datums-Wrapper-**Transformation** (UNIX_TIMESTAMP/TO_DAYS/RANGE COLUMNS): semantisch **anderer** Verlust
  als W112 (Transformation statt Verwurf) → wahrscheinlich **neuer W-Code** + Ledger-Eintrag. Die AP6-ADR
  entscheidet, ob ein neuer Code nötig ist.
- LIST-`DEFAULT`-Verlust + Partition-Index-Abbildung: Note-Code je nach ADR-Entscheid (AP6.2/AP6.3).

## Akzeptanzkriterien (Skizze — schärfen beim Move nach `in-progress/`)

- **AP6-ADR akzeptiert**, bevor AP6.2/AP6.3 codiert werden (RANGE-Wrapper, HASH-Divergenz, LIST-DEFAULT,
  Index-Abbildung, Note-/Ledger-Codes entschieden).
- **Reverse (AP6.1):** MySQL-Reverse befüllt `partitions` für **RANGE/LIST/HASH** (Unit-Test je Strategie + Live).
- **Generate PG→MySQL (AP6.2):** je Strategie gültiges MySQL-DDL, das sauber anwendet —
  RANGE (Integer-Wrapper bzw. `RANGE COLUMNS`), LIST (inkl. DEFAULT-Carve-Out gemeldet), HASH
  (`PARTITIONS n`); jeder Verlust/Transformation per Note gemeldet + in der ADR begründet.
- **Index (AP6.3):** kind-lokale Indizes gehen **nicht still** verloren — abgebildet **oder** per Note.
- **Round-Trip PG→MySQL (AP6.4):** Pagila `payment`, Zeilen-Parität + Nicht-Duplikation;
  `make sample-db-cross-smoke-pg2my` grün, Baselines erklärt.
- **MySQL→PG (AP6.5):** eine MySQL-RANGE-Tabelle reverst, generiert valides PG-DDL mit rekonstruierten
  `from`/`to`-Paaren; HASH `PARTITIONS n` → modulus/remainder; Round-Trip belegt.

## Abgrenzung / Nicht-Ziel

- **Sub-Partitionierung** bleibt OUT (wie in der PG-Scheibe, ADR 0019).
- **Performance** (paralleler per-Partition-Transfer) bleibt Performance-Phase.
- SQLite: keine Partitionierung → `E055` unverändert.

## Bezug

- Gate-ADR der Modellform: [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md).
- Vorläufer-Scheibe (PG-first, geliefert): [`../done/partition-hierarchy-reconstruction.md`](../done/partition-hierarchy-reconstruction.md).
- Note-/Fehler-Ledger: [`spec/ledger.md`](../../../spec/ledger.md).
- Anforderung **LN-008** „Partitionierung für große Tabellen"
  ([`../../../spec/lastenheft-d-migrate.md`](../../../spec/lastenheft-d-migrate.md)) — Cross-Dialect-Teil.

## Closure (2026-06-25)

**AP6 codeseitig komplett + review-gehärtet (Runde 1) → graduiert nach `done/`.** Geliefert:
AP6.1 (MySQL-Reverse RANGE/LIST/HASH), AP6.2 (Generate inkl. Temporal-UTC-Normalisierung,
Carve-Outs E062/E063/W130), AP6.3 (kind-lokale Index-Heben + FK-Carve-Out E064/E065),
AP6.4 (Cross-Smoke PG→MySQL live grün, 16049 Zeilen, Partitions-Integritäts-Gate),
AP6.5 (MySQL→PG-Rückrichtung, `reconstructNeutralBounds`). Gate-ADR
[ADR 0020](../../adr/0020-cross-dialect-partitioning-mysql.md) accepted; Ledger E061–E065 /
W129–W131 (YAML + `spec/ledger.md`-Summary).

**Review-Härtung Runde 1** (Commit `7b3cc641`): siehe gleichnamige Sektion oben — alle 15
Erstbefunde + 3 Re-Review-Befunde (Spec-Drift, PG/MySQL-Quoting-Asymmetrie, Regex-Härtung)
behoben; strukturierte Temporal-Behandlung (`MysqlPartitionBoundRenderer`), gemeinsamer
`PartitionBoundScanner` (driver-common), Quoting-Vertrag „Modell trägt Quotes" (dialekt-identisch).
Full-Repo-Build grün (Tests + Detekt + Kover), `docs-check` grün.

**Bewusst als eigene Sub-Slices geschnitten** (nicht AP6-blockierend, im
[`carveout.md`](../in-progress/carveout.md)-Tracker, Sektion „Cross-Dialect-Partitionierung",
mit Trigger verfolgt):
- **Kind-lokale FK-Transparenz (E065)** — braucht FK-Feld auf `PartitionDefinition` +
  Ergänzung von [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md) +
  [ADR 0020](../../adr/0020-cross-dialect-partitioning-mysql.md) + Reverse-Erfassung.
- **LIST-`DEFAULT`-Transfer-Preflight** — Generate-Note E063 flaggt bereits laut; ein
  Transfer-Zeit-Preflight (spiegelt `CheckPreflight`) ist ein eigenes Slice.
- **Gemeinsames Partitions-Bewusstsein im `AbstractDdlGenerator`** (statt MySQL-
  `partitionedTables`-Seitenkanal) → [`../open/partition-generator-shared-awareness.md`](../open/partition-generator-shared-awareness.md).

**Verbleibende Mini-Hygiene** (kein eigener Slice): kind-lokale FKs sind in pagila auf den
Kindern deklariert — der Cross-Smoke ist davon nicht betroffen (MySQL verbietet FKs auf
partitionierten Tabellen ohnehin).
