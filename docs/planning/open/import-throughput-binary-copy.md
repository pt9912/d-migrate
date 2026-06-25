# Import-Durchsatz: COPY-Pfad auf weitere Typen ausweiten (binär/EWKB/COPY-Text)

> Status: **Draft** (Trigger Watch)
>
> Trigger: Der COPY-Bulk-Fast-Path
> ([`import-throughput-copy-path.md`](../done/import-throughput-copy-path.md), 2026-06-25)
> ist bewusst **konservativ** geschnitten: COPY-TEXT-Format + eine Allowlist
> eindeutig text-sicherer **Skalartypen** (int/decimal/float/bool/char/varchar/
> date/time/timestamp). Geometrie (SQL-Wrap), Enum, Array, json/jsonb, interval,
> xml und bytea fallen darum auf den (mit `reWriteBatchedInserts` ohnehin
> schnelleren) Batch-INSERT zurück — korrekt, aber nicht COPY-schnell.
>
> Aktivierungsbedingung: sobald ein Workload mit hohem Anteil an genau diesen
> Typen (insb. **geometrie-lastig** oder json/array-lastig) den COPY-Durchsatz
> als Ziel hat. Dann mit ausgearbeitetem Scope nach `../next/`.

---

## Worum es geht

COPY ist kein reines Skalar-Protokoll: die heute ausgeschlossenen Typen haben
sehr wohl COPY-darstellbare Repräsentationen.

- **Geometrie.** Der *einzige* echte COPY-Blocker im Import ist das
  **SQL-Funktions-Wrapping** `ST_GeomFromWKB(?, srid[, axis-order])` in
  `AbstractTableImportSession.valuePlaceholder` — COPY kann keine Per-Wert-
  SQL-Ausdrücke ausführen. Als **EWKB-Hex** (inkl. SRID) ginge die Geometrie
  aber direkt in COPY, ganz ohne Funktionsaufruf. Damit entfiele der Wrap-Zwang,
  der den INSERT-Rückfall für Spatial-Tabellen erzwingt.
- **json/jsonb, Array, Enum, interval, xml.** Diese werden heute nicht über
  `valuePlaceholder`, sondern in `bindValue` per `PGobject`/`setObject`/`setArray`
  gebunden — sie haben Text-/Binär-Repräsentationen und sind in COPY (Text- oder
  Binär-Format) darstellbar; sie sind also keine *prinzipielle* Sperre, nur
  fiddly im Encoder.

## Erweiterungs-Skizze (zu entscheiden)

1. Die Allowlist `COPY_TEXT_SAFE_JDBC_TYPES` und den Encoder `PostgresCopyText`
   um Typen mit eindeutiger COPY-Repräsentation erweitern (json/array als
   COPY-Text, Geometrie als EWKB-Hex inkl. SRID), das `isEligible`-Gate
   entsprechend lockern.
2. Pro Typ die Verlustfreiheit weiter hart über den kanonischen 4c-SHA-256
   absichern (`make sample-db-tpch-perf` + die Spatial-Harness für Geometrie).
3. Abwägen: Text- vs. Binär-COPY. Binär ist kompakter/schneller, aber
   fehleranfälliger im Encoder; der heutige Pfad nutzt bewusst Text wegen des
   eindeutigen `\N`-NULL-Markers und der kanonischen Text-Repräsentation.

## Warum kein eigener Slice (jetzt)

Die konservative Skalar-Allowlist deckt den **häufigen Fall** ab (TPC-H und
ähnliche reine Skalar-Workloads → COPY greift für alle Tabellen). Für den Rest
ist der INSERT-Rückfall korrekt und verlustfrei; der Mehrwert dieser Erweiterung
ist reiner Durchsatz für *spezielle* Typ-Profile und damit nicht LF-blockierend.

## Verwandte Tracker

- Quelle und Closure-Kontext:
  [`import-throughput-copy-path.md`](../done/import-throughput-copy-path.md)
  (COPY-Bulk-Pfad + Closure-Abschnitt zur konservativen Allowlist).
- Komplementäre, unabhängige Achse (mehr Streams statt mehr Typen):
  [`import-throughput-parallel.md`](import-throughput-parallel.md).
- Geometrie-Treue als eigene Anforderung: die Spatial-Harness
  (`make sample-db-spatial-smoke`).
