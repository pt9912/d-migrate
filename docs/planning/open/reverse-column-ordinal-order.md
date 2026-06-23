# Reverse/Generate bewahrt die Spalten-Ordinalreihenfolge nicht (alphabetisiert)

> **Status:** Vorabklärung (Trigger, 2026-06-23)
> **Trigger:** Beim TPC-4c-Spike (Volumen-Abnahme) fiel auf, dass ein **roher
> Byte-Vergleich** des `data export` Quelle vs. Re-Import-Ziel abweicht, obwohl
> der Transfer **zellgenau verlustfrei** ist (per kanonischem Inhalts-Hash belegt).
> Ursache: `schema reverse` legt die Spalten **alphabetisch nach Namen** ab statt in
> der Quell-**Ordinalreihenfolge**; das generierte Ziel-DDL übernimmt das treu.
> **Bezug (Anforderung):** keine harte LF-Anforderung verlangt Ordinal-Erhalt
> explizit; relevant für Reverse-**Fidelity** (LF-004-Strukturerkennung) und für
> jede byte-basierte Verifikation.

## Beleg (TPC-H `lineitem`, 2026-06-23)

| Stufe | Spaltenreihenfolge |
|-------|--------------------|
| Quelle (DuckDB-`schema.sql`) | `l_orderkey, l_partkey, l_suppkey, l_linenumber, …` (TPC-H-Definitionsreihenfolge) |
| `out/tpch.reverse.yaml` | `l_comment, l_commitdate, l_discount, l_extendedprice, …` (**strikt alphabetisch**) |
| `out/tpch.sql` (generate) | identisch alphabetisch |

Schon die **reverse-YAML** ist alphabetisch → der Verlust liegt **vor** dem generate.
Präziser (codeverifiziert 2026-06-23): die **Reverse-Treiber selbst verlieren die
Reihenfolge nicht** — MySQL/PG fragen `ORDER BY ordinal_position` ab, SQLite liest `cid`,
und alle drei füllen einen insertion-ordered `LinkedHashMap`; das frisch reverse-te
`TableDefinition` trägt im Speicher also noch die Quell-Ordinalreihenfolge. Geplättet
wird sie erst beim **Serialisieren** (siehe „Ursache"). Die Ordinaldaten für einen Fix
liegen am Treiber-Rand somit bereits vor.

## Warum es (meist) nicht auffällt

- d-migrates eigener `schema compare` arbeitet im **alphabetisierten neutralen Modell**:
  beide Seiten sind gleich sortiert → 0 Diffs. Der Pagila-Round-Trip erreicht deshalb
  „IDENTICAL", obwohl die physische Ordinalreihenfolge gegenüber dem Original abweichen
  würde. Die Abweichung wird nur sichtbar, wenn man gegen die **Original-DB-Physik**
  vergleicht (wie der 4c-Export-Spike).

## Einordnung / Auswirkung

- **Relationale Korrektheit: kosmetisch.** Daten + Spalten + Typen sind vollständig
  (kanonischer SHA-256 Quelle == Ziel über alle 8 TPC-H-Tabellen). Zugriff per Spaltenname
  ist unberührt.
- **Aber echte Fidelity-Lücke:** überrascht bei `SELECT *`-Reihenfolge oder positionalem
  Zugriff; verhindert einen literalen Byte-für-Byte-Export-Vergleich (4c nutzt deshalb
  einen kanonischen, order-invarianten Inhalts-Hash).

## Ursache (codeverifiziert 2026-06-23)

Zwei zusammenwirkende Stellen, **nicht** der Reverse-Read:

1. **YAML-Serializer sortiert aktiv nach Name.** `SchemaNodeStructureBuilders.buildColumns`
   iteriert `columns.entries.sortedBy { it.key }` (Datei
   `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeStructureBuilders.kt`,
   ~Z. 77); begründet als „Maps are sorted by key for deterministic output"
   (`SchemaNodeBuilder.kt`). Das plättet die im `LinkedHashMap` noch vorhandene
   Ordinalreihenfolge auf Alphabet.
2. **Modell-Contract trägt keine Ordinalposition.** `TableDefinition.columns` ist ein
   `Map<String, ColumnDefinition>`, und `ColumnDefinition` hat kein `position`/`ordinal`-
   Feld. Selbst ohne (1) wäre die Reihenfolge nicht garantiert und ginge spätestens beim
   **Re-Parse** aus YAML wieder verloren.

## Entscheidung (offen)

Ist Ordinal-Erhalt im Scope? Falls ja: neutrales Modell um eine Ordinal-/Positions-
Information ergänzen, Reverse füllt sie aus der Quelle (`ordinal_position` aus
`information_schema.columns` / PG `attnum` / MySQL / SQLite `cid`), generate +
Serialisierung respektieren sie. Falls nein: als bewusste Normalisierung (alphabetisch)
dokumentieren.

**Verworfen:** das `sortedBy { it.key }` (Ursache 1) ersatzlos entfernen und auf die
`LinkedHashMap`-Einfügereihenfolge vertrauen. Das bräche den bewusst deterministischen
Serialisierungs-Output (gilt für alle Map-Felder, nicht nur Spalten) und überlebt keinen
YAML-Round-Trip, weil Ursache 2 (kein Ordinal-Feld) bestehen bliebe. Ein explizites
Ordinal-Feld entkoppelt „deterministische Serialisierung (nach Name)" sauber von
„physische Reihenfolge (nach Ordinal)" — deshalb der robuste Weg.

## Scope-Hinweis

**Nicht 4c-blockierend** — 4c verifiziert Verlustfreiheit per kanonischem (order-
invariantem) Inhalts-Hash. Dieses Ticket ist eine **separate** Reverse-Fidelity-Frage.
