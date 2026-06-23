---
status: proposed
date: 2026-06-23
decision-makers: pt9912
consulted: docs/planning/next/partition-hierarchy-reconstruction.md, docs/adr/0015-fulltext-tsvector-neutral-type.md (neutrales-Modell-Präzedenz), spec/neutral-model-spec.md, spec/lastenheft-d-migrate.md (LN-008)
informed: hexagon/core (PartitionConfig, TableDiff), adapters/driven/driver-postgresql, adapters/driven/driver-mysql, examples/sample-db (Pagila-Round-Trip)
---

# Partitions-Hierarchie: strukturierte `PartitionDefinition` statt opaker Dialekt-Strings

> **Status: proposed.** Gate-Entscheidung (AP1a) für den Slice
> [`../planning/next/partition-hierarchy-reconstruction.md`](../planning/next/partition-hierarchy-reconstruction.md).
> **Empfehlung: strukturiert.** Konkrete Modellform unter „Entscheidung".

## Kontext und Problemstellung

Der PG-Reverse erfasst **Strategie + Schlüssel** einer partitionierten Tabelle, aber
**nie die Kind-Partitionen** (`readPostgresPartitioning` gibt `PartitionConfig(type, key)`
mit leerer `partitions`-Liste zurück; code-verifiziert). Folge: eine partitionierte
Eltern-Tabelle (z. B. Pagila `payment`, RANGE) round-trippt als „partitionsloser Parent +
7 lose Basistabellen", der Generate-Pfad fällt auf **E055** zurück (plain Tabelle), und
der Daten-Transfer **dupliziert** sogar Zeilen (Parent-SELECT + Kind-SELECTs; datenbelegt
32098 statt 16049). Das ist ein **systematischer Reverse-Fidelity-Defekt** und deckt
**LN-008** („Partitionierung für große Tabellen") nur teilweise ab.

Generate (PG **und** MySQL) konsumiert bereits eine `partitions`-Liste; der Comparator ist
**partitions-blind** (`TableDiff` hat kein Partitionsfeld; ein Test fixiert „partitioning
changes do not produce diff"). Die **fehlende Schicht ist der Reverse-Capture** — aber
*bevor* er gebaut wird, muss die **Repräsentation** der Partitionsgrenzen feststehen, denn
sie bestimmt, was Reverse erzeugt, Generate emittiert und Comparator vergleicht.

**Heutige Form (das Problem):** `PartitionDefinition.from/to/values` tragen **rohe
PG-SQL-Fragmente** (`'2022-01-01'`, `MINVALUE`, `MODULUS 4, REMAINDER 0`). Generate
konkateniert sie ungeparst. Das kollidiert frontal mit der Hausregel **„kein
Native-Passthrough im neutralen Modell"** ([ADR 0015](0015-fulltext-tsvector-neutral-type.md),
geometry-Muster): PG-only-Strukturen werden first-class modelliert, nicht als
Dialekt-String durchgereicht.

## Entscheidung

### 1. Strukturierte `PartitionDefinition` (statt opaker Strings)

Typisierte Grenzen statt roher Fragmente:

- **RANGE:** `from`/`to` als **Tupel von Bound-Werten** (`List<PartitionBoundValue>`),
  wobei `PartitionBoundValue` = kanonisierter Literal-String **oder** Sentinel
  (`MINVALUE`/`MAXVALUE`).
- **HASH:** `modulus`/`remainder` als **Zahlen** (`Int`).
- **LIST:** `values` als Wertliste (kanonisierte Literale).
- **DEFAULT:** `isDefault: Boolean` (siehe Scope-Entscheidung unten).

**Begründung (drei Punkte, alle vom Ticket belegt):**
1. **Regelkonform:** die einzige Variante, die zur Hausregel „kein Native-Passthrough"
   passt (Präzedenz fulltext/geometry).
2. **Löst den „Linchpin" weitgehend auf:** Zahlen vergleichen sich als Zahlen, Sentinels
   als Enum-Werte, die Tupel-Struktur ist explizit — die Cast-/Casing-/Whitespace-Strip-
   Fragilität kollabiert zu *einem* einmaligen Parse. Beim opaken Weg wäre byte-identisches
   Re-Encoding auf beiden Seiten das ganze Spiel (inhärentes False-Positive-Risiko im
   strukturellen `data class`-Comparator-Vergleich).
3. **Voraussetzung für Cross-Dialect (AP6):** PG-`FROM/TO` lässt sich nur **strukturiert**
   nach MySQL-`VALUES LESS THAN` abbilden — aus einem rohen PG-SQL-Fragment nicht.

**Restkanonisierung (kleiner, aber real):** Literal-Werte bleiben Strings; der Reverse-
Parser muss sie kanonisieren (Typ-Casts strippen — `pg_get_expr` rendert
`'2022-02-01 00:00:00+00'::timestamp with time zone` —, Quoting/Whitespace normieren).
Das ist die verbleibende „ein kanonisches Encoding"-Pflicht (AP1a), aber ohne die
HASH-/Sentinel-/Tupel-Fragilität des opaken Wegs.

### 2. Comparator wird partitions-bewusst

`TableDiff` bekommt ein Partitionierungs-Diff-Feld; der Comparator vergleicht
Strategie/Schlüssel/`partitions` (**Set-Gleichheit, reihenfolge-unabhängig**). Der
bestehende Test „partitioning changes do not produce diff" wird **umgedreht**
(Partitionsunterschied ⇒ Diff). `MigrationFingerprint` + `spec/schema-reference.md`
ziehen nach. Bewusste, getestete Bestandsänderung — hier sanktioniert.

### 3. Cross-Dialect-Form-Divergenz + verlustbehaftete RANGE-Abbildung

- **Form:** PG **deklarativ** (`CREATE TABLE … PARTITION OF … FOR VALUES …`), MySQL
  **inline** (`PARTITION BY … (PARTITION p VALUES LESS THAN …)`). Beide konsumieren das
  **strukturierte** Modell.
- **Verlust (semantischer Carve-Out, nicht nur Syntax):** PG-RANGE hat `from`+`to`
  (Lücken erlaubt); MySQL-RANGE kennt nur `VALUES LESS THAN` (Obergrenze, Kontiguität) →
  MySQL-Generate **verwirft `from`** (heute schon, Z. 62). Dieser Informationsverlust ist
  eine bewusste Carve-Out-Entscheidung (analog fulltext), per W112 gemeldet.

### 4. Scope: PG zuerst

- **In dieser Scheibe:** PG-Reverse-Capture (Kinder + Grenzen + Index/FK-Vererbung),
  strukturiertes Modell, Generate-Verifikation (PG **und** MySQL **konsumieren** das neue
  Modell — der MySQL-Generate-Umbau ist erzwungen durch die Modelländerung, nicht optional),
  partitions-bewusster Comparator, Transfer-Nicht-Duplikations-Test.
- **Folge-Slice:** MySQL-**Reverse**-Capture (dieselbe AP1-Klasse Arbeit, eigener Reader)
  + voller Cross-Dialect-Round-Trip.
- **SQLite:** keine Partitionierung → E055 unverändert.

### 5. Aufgelöste Einzelentscheidungen

- **DEFAULT-Partition: IN-Scope** (PG `… DEFAULT`). Im strukturierten Modell ein `isDefault`-
  Flag — geringe Mehrkosten, vermeidet genau den bedingten else-/Stopgap-Zweig, den das
  Ticket warnt. (Pagila nutzt es nicht; trotzdem first-class, kein Carve-Out.)
- **Sub-Partitionierung: OUT** (Partitionen von Partitionen) — eigener Slice.
- **Per-Partition-Performance-Transfer (paralleler Export/Import): OUT** — LN-008-
  Performance-Teil, Performance-Phase. Die *Korrektheit* (Parent-Routing,
  Nicht-Duplikation) ist hier drin.

## Konsequenzen

- **Contract-Fixture-/Golden-Churn:** String-Grenzen → typisierte Felder bricht eine
  bereits ausgelieferte Serialisierungs-Form (`spec/schema.json` `partitions`,
  `schema-reference.md`). Weil `spec/` Zielbild ist, vertretbar — hier bewusst geflaggt.
- **Mehr Aufwand als der opake Weg** (Modell + Reverse-Parser + Generate **PG & MySQL** +
  Serialisierung + Comparator), aber die einzige regelkonforme + cross-dialect-fähige
  Variante; der Linchpin-Preis des opaken Wegs entfällt.
- **Reihenfolge-Kopplung** (im Slice): diese ADR (AP1a) ist Upstream von AP1/AP4/AP6;
  AP1 (Capture) und AP2 (Doppel-Emit-Vermeidung) sind untrennbar (sonst
  `relation already exists`); AP4 (Comparator) erst **nach** AP1 (sonst bricht die
  `IDENTICAL`-Baseline).

## Verworfene Alternative

- **Opake Dialekt-Strings festschreiben:** am schnellsten (Generate baut darauf), aber
  zementiert den Native-Passthrough (Hausregel-Verstoß), trägt das volle Encoding-
  Identitäts-Risiko (byte-identisches Re-Encoding beidseitig), und **blockiert AP6**
  (Cross-Dialect aus rohem PG-SQL nicht abbildbar). Nur expedient — verworfen.
