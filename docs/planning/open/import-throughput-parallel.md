# Import-Durchsatz: paralleler Import (mehrere gleichzeitige Streams)

> Status: **Vorschlag** (Trigger Watch)
>
> Trigger: Abschluss des COPY-Fast-Path-Tickets
> ([`import-throughput-copy-path.md`](../done/import-throughput-copy-path.md), 2026-06-25).
> Beide dort gelieferten Hebel — Schritt 0 (`reWriteBatchedInserts`) und der
> COPY-Bulk-Fast-Path — beschleunigen einen **einzelnen** Import-Stream
> (Protokoll-Effizienz pro Strom). Der parallele Import ist die dazu
> **orthogonale** Achse und wurde im Ticket bewusst ausgeklammert (Abschnitt
> „Orthogonale Achse: Parallelität (bewusst außerhalb dieses Tickets)").
>
> Aktivierungsbedingung: sobald Volumen-Migrationen den Durchsatz eines
> einzelnen Streams als Ziel überschreiten **und** die unten genannten
> Korrektheitsfragen für ein konkretes Szenario geklärt werden können. Dann
> wandert der Eintrag mit ausgearbeitetem Scope nach `../next/`.

---

## Worum es geht

Statt eines Stroms mehrere **gleichzeitige** Verbindungen/Streams gegen die
Ziel-DB: eine je Tabelle, oder eine große Tabelle chunk-partitioniert über N
Streams. Diese Achse **multipliziert** sich mit der Protokoll-Effizienz aus dem
COPY-Ticket — beide zusammen ergeben den maximalen Import-Durchsatz.

## Offene Korrektheits-/Architektur-Fragen (vor jedem Scope zu klären)

- **FK-/Ladereihenfolge** über gleichzeitig geladene Tabellen (referenzielle
  Integrität). Der heutige Import respektiert eine topologische Tabellenreihenfolge;
  gleichzeitige Streams müssen entweder dieselbe Ordnung über Abhängigkeiten hinweg
  einhalten oder Constraints temporär aussetzen (`session_replication_role` /
  deferred FKs) und am Ende validieren.
- **Globaler `triggersDisabled`-Zustand + Pool-Dimensionierung.** Das Deaktivieren
  von Triggern (`ALTER TABLE … DISABLE TRIGGER USER`) ist heute pro Tabelle und
  läuft innerhalb der Session-Transaktion; bei N gleichzeitigen Sessions ist der
  Gültigkeitsbereich (Transaktion vs. Verbindung) und die Connection-Pool-Größe neu
  zu bewerten.
- **Decke ist die Ziel-Instanz.** Importer-seitige Parallelität ist durch die
  vertikale Kapazität der **einen** Ziel-PG-Instanz (CPU/IO/WAL) begrenzt — ab
  einem Punkt bringt ein weiterer Stream nichts mehr. Protokoll-Effizienz (das
  COPY-Ticket) senkt die Arbeit pro Zeile und bleibt darum auch unter dieser Decke
  wirksam; Parallelität skaliert nur bis zur Sättigung der Ziel-Instanz.

## Warum kein eigener Slice (jetzt)

Diese Fragen sind eigenständige Korrektheits-/Architektur-Themen und gehören
ausdrücklich **nicht** in den COPY-Fast-Path gemischt. Ein Scope ergibt erst
Sinn, wenn ein konkretes Volumen-Szenario die Mehrarbeit rechtfertigt und die
FK-/Trigger-/Sättigungs-Fragen für genau dieses Szenario beantwortet sind.

## Verwandte Tracker

- Quelle und Closure-Kontext:
  [`import-throughput-copy-path.md`](../done/import-throughput-copy-path.md)
  (Abschnitt „Orthogonale Achse: Parallelität").
- Komplementärer, von dieser Achse unabhängiger Speed-Hebel:
  [`import-throughput-binary-copy.md`](import-throughput-binary-copy.md)
  (mehr Typen über COPY, statt mehr Streams).
- Mess-Substrat (Vorher/Nachher unter Caps): die 4c-Harness
  (`make sample-db-tpch-perf`,
  [`tpc-4c-volume-acceptance-slice.md`](../done/tpc-4c-volume-acceptance-slice.md)).
