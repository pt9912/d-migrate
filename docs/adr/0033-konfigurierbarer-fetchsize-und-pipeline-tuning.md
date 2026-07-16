---
status: accepted
date: 2026-07-13
decision-makers: pt9912
consulted: spec/lastenheft-d-migrate.md, docs/planning/done/ln005-streaming-oom-hardening.md
informed: hexagon/ports, adapters/driven/driver-postgresql, adapters/driven/driver-mysql, adapters/driven/driver-sqlite, adapters/driving/cli, hexagon/application, spec/cli-spec.md, spec/connection-config-spec.md
---

# Konfigurierbarer JDBC-`fetchSize` + Verdrahtung von `pipeline.chunk_size`/`fetch_size` (LN-005)

> **Status: accepted (2026-07-13).** Der JDBC-Cursor-`fetchSize` (Prefetch) wird von einer
> treiberinternen Konstante zu einem **user-konfigurierbaren** Wert (`--fetch-size`, `pipeline.fetch_size`),
> angewandt **am Reader-Bau** (nicht an der `streamTable`-Signatur, nicht in `PipelineConfig`). Zugleich
> wird das zuvor spec-dokumentierte, aber **unverdrahtete** `pipeline.chunk_size` echt ans Runtime
> gehängt. Teil der [`LN-005`](../../spec/lastenheft-d-migrate.md#ln-005)-Streaming-OOM-Härtung.

## Kontext und Problemstellung

Der Read-Pfad ist chunk-basiert (`chunkSize`, Default 10 000) und setzt einen JDBC-`fetchSize` als
Cursor-Prefetch. Bisher war `fetchSize` eine **pro Dialekt hart verdrahtete Konstante** (`1000`), und
[`PipelineConfig`](../../hexagon/ports-write/src/main/kotlin/dev/dmigrate/streaming/PipelineConfig.kt)
dokumentierte explizit „`fetchSize` ist treiberintern und gehört nicht hierher". Für die
[`LN-005`](../../spec/lastenheft-d-migrate.md#ln-005)-Anforderung („sehr große Datenmengen ohne OutOfMemory") ist das zu starr: bei **sehr breiten
Zeilen** oder großen Zellwerten möchte ein Operator `fetchSize` (und `chunkSize`) gezielt kleiner
wählen, um die Peak-Speicherlast pro Chunk zu senken.

Zwei verifizierte Randbedingungen prägen die Entscheidung:

1. **`streamTable` hat 81 Call-Sites** und zwei Overloads (4-Param + 5-Param `resumeMarker`). Ein
   per-Call-`fetchSize`-Parameter dort erzeugt Overload-Ambiguität (nullbarer fünfter Parameter) und
   einen breiten Call-Site-Umbau.
2. **`pipeline.chunk_size` war ein stiller No-op**: `PipelineCheckpointResolver` liest nur
   `pipeline.checkpoint.*`; ein `pipeline.chunk_size` im Config wurde ignoriert (`chunkSize` kam real
   nur aus `--chunk-size`). Die Spec (`connection-config-spec.md`) dokumentierte den Key trotzdem.

## Entscheidung

### D1 — `fetchSize` am Reader-Bau, nicht an `streamTable`, nicht in `PipelineConfig`

`fetchSize` wird über einen **optionalen Konstruktor-/Factory-Parameter** konfiguriert:
`DatabaseDriver.dataReader(fetchSize: Int?)` (Default-Methode, delegiert ohne Wert an `dataReader()`),
die JDBC-Treiber reichen ihn in den Reader-Konstruktor (`override val fetchSize = fetchSizeOverride ?: <Default>`).
Der Wert ist damit **immutable pro Reader-Instanz** → parallel-sicher (jeder `dataReader(...)`-Aufruf
liefert eine frische Instanz). `streamTable` bleibt unangetastet (D1 vermeidet den 81-Site-Blast-Radius
und die Overload-Ambiguität). `PipelineConfig` bleibt frei von `fetchSize` — es speist die
Streaming-**Schleife**, die den bereits gebauten Reader nutzt; der Kommentar dort bleibt korrekt.

**Semantik**: `fetchSize` ist Connection-/Cursor-Tuning (einmal beim Reader-Bau), `chunkSize` ist
per-Operation (variiert je Lauf/Resume). Die beiden Ebenen bleiben getrennt.

### D2 — `pipeline.chunk_size` + `pipeline.fetch_size` echt verdrahten

Ein neuer `PipelineTuningResolver` (Muster wie `PipelineCheckpointResolver`) liest `pipeline.chunk_size`
und `pipeline.fetch_size` (positive Ganzzahlen; sonst klarer Fehler statt stiller Ignoranz). Präzedenz:
**CLI-explizit > Config > eingebauter Default**. Dafür werden `--chunk-size`/`--fetch-size` **nullbar**
(der Default `10 000` bzw. der Dialekt-`fetchSize` wandert in den Merge `resolveEffectivePipelineTuning`).

### D3 — Reichweite: export/transfer (+ verify), nicht import

`--fetch-size` sitzt an `data export`/`transfer`. `data import` liest aus **Format-Dateien** über
`DataChunkReader` (kein JDBC-`DataReader`) — ein `fetchSize` dort wäre wirkungslos, daher ausgeschlossen;
nur `--chunk-size`/`pipeline.chunk_size` gelten für den Import. Der `data transfer --verify`-Read-Back
(Quelle + Ziel) baut seine Reader ebenfalls über `dataReader(...)` und nutzt denselben `fetchSize`.

## Konsequenzen

- **Positiv**: [`LN-005`](../../spec/lastenheft-d-migrate.md#ln-005)-Tuning-Knopf für breite Zeilen/große Zellen; die Spec (`pipeline.chunk_size`)
  wird wahr statt irreführend; `fetch_size` bekommt einen konsistenten Config-Key.
- **Vertrag**: `--fetch-size`/`--chunk-size` `≤ 0` (oder ungültiger Config-Wert) → Exit 2 bzw. Exit 7.
  Für SQLite ist `fetchSize` nur ein Hint (kein serverseitiger Cursor).
- **Kompatibilität**: alle bestehenden Aufrufe ohne die Flags verhalten sich unverändert
  (Default-Methode + Default-Werte); die ~15 `DatabaseDriver`-Test-Fakes bleiben unangetastet.

## Verworfene Alternativen

- **`ReadTuning`-Parameterobjekt an `streamTable`** (chunkSize+fetchSize bündeln): sauber, aber 81
  Call-Sites Umbau; verworfen zugunsten des churn-armen Reader-Bau-Ansatzes (D1).
- **clikt `ValueSource`** für die Config-Präzedenz: würde alle Optionen namensbasiert aus dem Config
  speisen (breiter Nebeneffekt); ein zielgerichteter `PipelineTuningResolver` + expliziter Merge ist
  konsistent mit dem bestehenden `PipelineCheckpointResolver` und lokal begrenzt.
