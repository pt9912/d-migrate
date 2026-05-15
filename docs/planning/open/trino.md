# Plan: Trino-Support in d-migrate (read-first Federation-Adapter)

> Dokumenttyp: Architektur- und Umsetzungsplan
>
> Status: Entwurf (2026-05-15)
>
> Referenzen: `spec/architecture.md`, `spec/cli-spec.md`,
> `spec/connection-config-spec.md`, `docs/planning/roadmap.md`

---

## 1) Zielbild

`d-migrate` soll Trino als **zusätzlichen, read-first Adapter** unterstützen.

Der Adapter dient primär für:

- Reverse Engineering großer Kataloge/Schema
- Schema-Vergleich gegen neutrale Zielartefakte
- Export und Profiling über Analyselayer
- Daten-Transfers mit Trino als Quelle

Der Adapter ist kein Ersatz für native OLTP-Dialekte.

## 2) Ausgangslage

`d-migrate` hat heute stabile native Treiber für PostgreSQL, MySQL und SQLite.
Trino wird als verteilte Query Engine eingesetzt und bringt bereits in vielen
Setups Sicht auf heterogene Quellen ohne direkte Treiber für alle Hintergründe.

Das spricht für Trino als Lese- und Analyseebene, aber gegenwärtig gegen:

- transaktionale DDL-/DML-Semantik
- vollständige Constraint-/Trigger-/Procedure-Abdeckung
- robuste Connector-übergreifende Write-Garantien

## 3) Ziel- und Nicht-Ziele

### 3.1 In Scope

- `DatabaseDialect.TRINO` + URL-Alias `trino`
- URL-Parsing `trino://user@host:port/catalog/schema` (Canonical-Form)
- neues Adapter-Modul `adapters:driven:driver-trino`
- read-only Pipeline-Unterstützung für:
  - `schema reverse`
  - `schema compare`
  - `data export`
  - `data profile`
  - `data transfer` **nur als Source**

### 3.2 Nicht in Scope (aktuell)

- Schreibpfade (`data import`, allgemeine DDL-Generierung) als Standardpfad
- Transaktionsverhalten/UPSERT-Äquivalente im Trino-Pfad
- vollständige Migration mit Constraint-, Trigger- und Procedure-Semantik
- `presto` als Alias auf `TRINO`
- `schema generate` in Phase 1 (erst in Phase 3 geplant)

## 4) Architektureinbettung

Der Adapter folgt dem vorhandenen Hexagon-Modell:

```text
adapters:driven:driver-trino
  ├─ ConnectionPool / JDBC Wiring
  ├─ SchemaReader
  ├─ TableLister
  └─ DataReader
```

Weitere optionale Erweiterungen:

- `adapters:driven:driver-trino-profiling`
- `test:integration-trino` (später)

## 5) Kontrakt: Dialekt und Connection-URL

### 5.1 Dialekt

- Erweiterung von `DatabaseDialect` auf:
  - `POSTGRESQL`, `MYSQL`, `SQLITE`, `TRINO`
- Alias-Mapping:
  - `trino -> TRINO`

### 5.2 URL-Modell

Primäres Muster (canonical):

```text
trino://user@localhost:8080/catalog/schema
```

Beispiele:

```text
trino://analyst@localhost:8080/hive/default
trino://analyst@localhost:8080/iceberg/default
trino://analyst@localhost:8080/postgresql/public
```

Interpretation:

- `catalog` ist der erste Pfadteil.
- `schema` ist der zweite Pfadteil.
- Bei fehlendem `schema` nutzt die Engine das Trino-session-Default des Connectors.
  - Ist kein Default vorhanden oder benötigt der Aufruf zwingend ein Schema (z. B. bei qualifizierten Tabellenzugriffen), wird vor dem Lauf mit `action_required` und klarer Anleitung abgebrochen.
- Query-Parameter werden als harte Fehlerklasse behandelt, solange sie nicht explizit in Phase 1 freigegeben sind.

Credential-Modell (Phase 1):

- Erwartete Basisform: `trino://user:password@host:port/catalog/schema`
- Optional/empfohlen: Trennung von Geheimnis über Umgebungsvariable (z. B. `TRINO_PASSWORD`) bis Credential-Provider formal eingeführt sind.
- Kein generischer Connector-Parameter-Flattendurchlass; nur explicit erlaubte Properties sind zulässig.

### 5.3 Fehler- und Signalisationsregeln

- fehlendes/unklares `catalog` → klare Fehlermeldung inkl. Beispiel-URL
- nicht unterstützte URL-Eigenschaften (Connector-spezifisch) werden früh und
  explicit als Capability- oder Feature-Lücke gemeldet
- Trino ist in Phase 1 als Target-Only-disabled und Source-only modelliert.
  Der Command-Lauf bricht mit deterministischer `action_required`-Meldung früh ab,
  wenn ein nicht erlaubter Zielpfad genutzt wird.

## 6) Umsetzungsphasen

### Phase 1 — Read-only MVP

**Ziele:** stabiler Trino-Lesepfad ohne Write-Risiko.

1. `TRINO`-Dialekt + URL-Alias einführen
2. Trino-Adapter und JDBC-Anbindung bereitstellen
   - Pooling/Connection
   - `TrinoSchemaReader`
   - `TrinoTableLister`
   - `TrinoDataReader`
3. CLI-Pfade aktivieren
   - `schema reverse`
   - `schema compare`
   - `data export`
   - `data profile`
   - `data transfer` mit Trino als Source (Capability-Guard gegen Target-Pfade)

Keine Writes, keine `schema generate`-Freigabe in dieser Phase.

`data profile` ist in Phase 1 nur möglich, wenn `driver-trino-profiling` ebenfalls
in Phase 1 implementiert ist; andernfalls wird der Aufruf mit klarer Fehlermeldung
früh blockiert.

### Phase 2 — Profiling- und Diagnosehärtung

- Ergänzende Profiling-Härtung und Connector-spezifische Diagnoseabdeckung
- Trino-spezifische Diagnosehinweise und Warnings im Vergleich
- Metadatenkonsistenz-Tests pro Connector (z. B. Hive vs Iceberg)

### Phase 3 — Controlled `schema generate`

- `schema generate --target trino://...` nur explizit und mit Profil-Optionen
- harte Limitierungen durch `action_required`
- Ziel: bewusster, nicht stiller Degradationspfad statt impliziter Abdeckung

### Phase 4 — Writes per Capability-Matrix

- Schreib-Adapter nur, wenn Connector-Treiber die Fähigkeiten explizit liefern:
  - `supportsInsert`
  - `supportsCreateTable`
  - `supportsCreateTableAs`
  - `supportsMerge`
  - `supportsDelete`
  - `supportsUpdate`
  - `supportsTransactions`

## 7) Funktions-Matrix mit Risiko-Niveau

| Feature | Ziel-Fit | Begründung |
| ------- | -------- | ---------- |
| `schema reverse` | Hoch | starke Synergie mit Trino-Layer |
| `schema compare` | Hoch | nützlich für Lakehouse-Vergleiche |
| `data export` | Sehr hoch | skalierbare Leselast |
| `data profile` | Sehr hoch | analytische Datentiefe |
| `data transfer` (Source) | Hoch | zentrale Use-Case für zentrale SQL-Schicht |
| `data transfer` (Target) | Gering | stark Connector-abhängig |
| `schema generate` | Mittel | nur explizit + Warnungen |
| `data import` | Niedrig | nicht für Phase 1 |
| klassische OLTP-Migration | Sehr gering | Trino ist kein primärer Migrationsknoten |

## 8) CLI-Beispiele (operativer Scope)

```bash
d-migrate schema reverse \
  --source trino://analyst@localhost:8080/iceberg/default \
  --output lakehouse.yaml

d-migrate schema compare \
  --source file:lakehouse.yaml \
  --target trino://analyst@localhost:8080/postgresql/public

d-migrate data export \
  --source trino://analyst@localhost:8080/iceberg/default \
  --tables orders,customers \
  --format csv

d-migrate data profile \
  --source trino://analyst@localhost:8080/hive/default \
  --tables orders,customers

d-migrate data transfer \
  --source trino://analyst@localhost:8080/iceberg/default \
  --target postgresql://app@localhost:5432/app \
  --tables customers
```

Hinweise:

- `schema reverse`/`compare` liefern die Trino-Sicht des Zielsystems.
- `data transfer --target trino://...` ist in Phase 1 blockiert.
- Nicht garantierte Objekte (Constraints/Index/Trigger/Procedures) werden klar
  als fehlende Sichtbarkeit markiert.

## 9) Risiken und Gegenmaßnahmen

### 9.1 Metadaten-Lücken

- Trino-Connectoren können bestimmte Metadaten nicht vollständig liefern.
- Gegenmaßnahme: `action_required`/Warnungen, kein stilles Fallback.

### 9.2 Schreibsemantik

- Trino-Write-Verhalten ist Connector-abhängig und nicht durchgehend kompatibel.
- Gegenmaßnahme: keine generische Write-Freigabe; Writes erst per Capability-
  Matrix pro Connector. In Phase 1 ist `TRINO` explizit auf Source-only gesetzt.

### 9.3 Erwartungsmanagement

- Gefahr: Trino als „voller DB-Ersatz“ verstanden.
- Gegenmaßnahme: README/Specs/CLI-Hilfe präzise als read-first kennzeichnen.

### 9.4 Spezifikationsklarheit

- Gefahr: divergierende URL-Konzepte zwischen Dokumenten, Doku und Implementierung.
- Gegenmaßnahme: eine kanonische URI-Form mit klarer Fehlerstrategie und
  verpflichtender Source/Target-Semantik (Capabilities).

## 10) Betroffene Artefakte

- `spec/architecture.md` (Adapterposition)
- `spec/cli-spec.md` (Source/Target-Dialekt-Doku, Capabilities/Guarding für `data transfer`)
- `spec/connection-config-spec.md` (URL-Form)
- `settings.gradle.kts` (Modulverkabelung)
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DatabaseDialect.kt` (Alias/Enum)
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionUrlParser.kt` (URL-Parsing)
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DialectCapabilities.kt` (Read-only/Target-Guard)
- `adapters:driven:driver-trino` (neu)
- `adapters:driven:driver-trino-profiling` (falls `data profile` in Phase 1 aktiviert)
- `hexagon`-Ports bei Bedarf (Capabilities bei Phase 4)
- `docs/planning/roadmap.md` + ggf. User-Doku

## 11) Akzeptanzkriterien (gesamt)

- `TRINO`-Dialekt und `trino://...` sind im CLI parsebar und dokumentiert.
- `schema reverse` gegen mindestens ein Trino-Katalog/Schema erfolgreich.
- `schema compare` läuft gegen `trino://...` und gibt klare Diff-/Limit-Warnungen.
- `data export` aus Trino ist stabil nutzbar.
- `data profile` liefert belastbare Ergebnisse für mindestens die Kernprofile.
- `data transfer` ist in Phase 1 Source-only, Target-Pfad ist klar deaktiviert.
- `data transfer --target trino://...` ist blockiert und liefert eine
  reproduzierbare Fehlermeldung mit `action_required`.
- `schema generate --target trino://...` bleibt bis Abschluss Phase 3 deaktiviert.

## 12) Empfehlung

Trino in dieser Ausprägung als **separaten Analytics/Federation-Adapter**.
Keine Vermischung mit klassischen OLTP-Migrationspfaden.

## Definition of Done

### DoD — Phase 1 (MVP)

- [ ] `DatabaseDialect.TRINO` vorhanden.
- [ ] Alias `trino` in Dialektauflösung und URL-Parsing verankert.
- [ ] `adapters:driven:driver-trino` in `settings.gradle.kts` aufgenommen.
- [ ] Trino-Connection-Factory/JDBC-Pool lauffähig.
- [ ] `TrinoSchemaReader`, `TrinoTableLister`, `TrinoDataReader` implementiert.
- [ ] `schema reverse` gegen Trino lauffähig.
- [ ] `schema compare --source file... --target trino://...` lauffähig.
- [ ] `data export --source trino...` lauffähig.
- [ ] `data profile --source trino...` lauffähig.
- [ ] `data transfer` ist Source-only dokumentiert und technisch durchgesetzt.
- [ ] Trino-Sicht-Lücken/Unbekannte werden als Warnungen ausgegeben.
- [ ] `data transfer --target trino...` liefert klare Fehlerklasse (`action_required`).
- [ ] `TRINO` als Target-Adapter für DDL/Import/Transfer nicht freigeschaltet.
- [ ] Mindest-Doku ergänzt: `spec/cli-spec.md`, `spec/connection-config-spec.md`.

### DoD — Phase 2

- [ ] Profiling-spezifische Trino-Warn- und Coverage-Klassen dokumentiert.
- [ ] Optionales Profiling-Modul `driver-trino-profiling` verfügbar oder klar begründet,
  warum verzögert.

### DoD — Phase 3

- [ ] `schema generate --target trino://...` ist nur als expliziter, begrenzter Pfad aktiv.
- [ ] `action_required`-Ausgabe für nicht abbildbare Objekte verlässlich getestet.

### DoD — Phase 4

- [ ] `supports*` Capability-Kontrakt pro Connector definiert.
- [ ] `adapters:driven:driver-trino` setzt write-/generate-/transfer-Guards nach Capability-Vertrag durch.
- [ ] Trino-Writepfade bleiben deaktiviert, solange der Capability-Test sie nicht
  erlaubt.
- [ ] Kein Produktivbetrieb mit stiller Schreibsemantik.
