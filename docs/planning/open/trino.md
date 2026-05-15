# Plan: Trino-Support in d-migrate (Read-first Federation-Adapter)

> Dokumenttyp: Architektur- und Umsetzungsplan  
> Status: Entwurf (2026-05-15)  
> Referenzen: `spec/architecture.md`, `spec/cli-spec.md`, `spec/connection-config-spec.md`, `docs/planning/roadmap.md`

## Kurzfassung

`d-migrate` erweitert seine Adapter-Landschaft um Trino als **read-first**
Federation-Layer. Fokus ist die sichere Nutzung als Analyse- und
Metadaten-Lesequelle – nicht als vollwertiger OLTP-Migrationspfad.

## 1) Zielbild

`d-migrate` soll Trino als **zusätzlichen, read-first Adapter** unterstützen.

Primäre Nutzung:

- Reverse Engineering großer Kataloge/Schema
- Schema-Vergleich gegen neutrale Zielartefakte
- Export und Profiling über den Analyse-Layer
- Daten-Transfers mit Trino als Quelle

Der Adapter ist **nicht** der primäre OLTP-Migrationspfad.

## 2) Ausgangslage

`d-migrate` besitzt heute native Treiber für PostgreSQL, MySQL und SQLite.
Trino bringt bereits heute in vielen Setups Zugriff auf heterogene Quellen ohne
direkten Treiberverbrauch aller Backends.

Das macht Trino für Read/Analyse attraktiv, gleichzeitig limitiert:

- transaktionale DDL/DML-Semantik,
- vollständige Constraint-/Trigger-/Procedure-Abdeckung,
- Connector-übergreifend konsistente Write-Garantien.

## 3) Ziel- und Nicht-Ziele

### 3.1 In Scope (Phase 1)

- Dialekt: `DatabaseDialect.TRINO` + Alias `trino`.
- URL-Parsing: `trino://user@host:port/catalog/schema` (kanonisch).
- Neues Adapter-Modul: `adapters:driven:driver-trino`.
- Eindeutiges Capability-Modell: **Phase 1 = Source-only**.
- Read-only Pipelines:
  - `schema reverse`
  - `schema compare`
  - `data export`
  - `data profile` (nur mit aktivem Profiling-Modul)
  - `data transfer` **nur Source**

### 3.2 Nicht in Scope (aktuell)

- Schreibpfade (`data import`, allgemeine DDL-Generierung) als Standardpfad.
- Transaktions-/UPSERT-/MERGE-Semantik im Trino-Pfad.
- Vollständige Migration mit Constraint-, Trigger- und Procedure-Semantik.
- Alias `presto` → `TRINO`.
- `schema generate` in Phase 1 (erscheint frühestens in Phase 3).

## 4) Architektureinbettung

Der Adapter nutzt das bestehende Hexagon-Modell:

```text
adapters:driven:driver-trino
  ├─ ConnectionPool / JDBC Wiring
  ├─ TrinoSchemaReader
  ├─ TrinoTableLister
  └─ TrinoDataReader
```

Optionale Erweiterungen:

- `adapters:driven:driver-trino-profiling` (Feature-Flag in Phase 1 optional,
  in Phase 2 standardmäßig aktiv)
- `test:integration-trino` (später)

## 5) Kontrakt: Dialekt und Connection-URL

### 5.1 Dialekt

- Erweiterung `DatabaseDialect` um `TRINO`.
- Alias-Mapping: `trino -> TRINO`.

### 5.2 URL-Modell

Kanonische Form:

```text
trino://user@host:port/catalog/schema
```

Beispiele:

- `trino://analyst@localhost:8080/hive/default`
- `trino://analyst@localhost:8080/iceberg/default`
- `trino://analyst@localhost:8080/postgresql/public`

Interpretation:

- `catalog` ist das erste Pfadsegment.
- `schema` ist das zweite Pfadsegment.
- Bei fehlendem `schema` verwendet die Engine das Trino-Session-Default des
  Connectors.
  - Ist kein Default vorhanden oder wird ein Schema zwingend benötigt, bricht der
    Lauf vorab mit `action_required` und klarer Anleitung ab.
- Query-Parameter sind bis auf explizit freigegebene Properties als harte
  Capability-Fehler zu behandeln. Die erlaubten Properties in Phase 1 sind:
  - `ssl` (`true|false`, default: `false`)
  - `httpScheme` (`http|https`, default: `http`)
  - `requestTimeoutMs` (positive Ganzzahl, ms)
  - `session.<name>` (Session-Property-Forwarding)
  - `accessToken`
  - `trustStorePath`
  - `trustStorePassword`
  - `keystorePath`
  - `keystorePassword`
- Weitere Pfadsegmente sind in Phase 1 ungültig.
- Format ist absichtlich ohne `db:`-Prefix.

Credential-Modell (Phase 1):

- Basisform: `trino://user:password@host:port/catalog/schema`.
- Optional/empfohlen: Passwort via Umgebungsvariable (z. B. `TRINO_PASSWORD`),
  bis ein Credential-Provider formal eingeführt ist.
- Kein generischer Connector-Parameter-Bypass; nur explizit erlaubte Properties.

#### 5.3 Security, Secrets und Maskierung

- In produktiven Setups ist URL-Embedding (z. B. `user:password`) nur als
  Übergangslösung vorgesehen.
- In neuen Setups muss ein Umgebungs-Secret (`TRINO_PASSWORD`) oder späterer
  Credential-Provider genutzt werden.
- Geheimnisse dürfen nicht in Logs, Debug-Ausgaben, Cache-Keys oder Telemetrie
  mit Klartext enthalten sein.
- Jede Ausgabe mit potenziellen Secret-Feldern (Passwort, Token) ist
  deterministisch zu maskieren (`***`).

### 5.4 Fehler- und Signalisationsregeln

- fehlendes oder unklar formatiertes `catalog` -> deterministische Fehlermeldung mit
  Beispiel-URL.
- nicht unterstützte URL-Properties -> sofortiger Abbruch via `action_required`.
- Trino ist in Phase 1 ein **Source-only** Adapter.
  Nicht erlaubte Zielpfade brechen deterministisch mit `action_required` ab.
- Capability- oder Guard-Fehler sind dauerhaft reproduzierbar und damit als
  dauerhafte Signale zu behandeln (keine transienten Retry-Pfade).

### 5.5 Compare-Metadaten-Qualitätsmodell

`schema compare --target trino://...` verwendet ein dreistufiges
Metadaten-Abdeckungmodell:

- `full`: Objekt ist vollständig lesbar und vergleichbar
- `partial`: Objekt ist lesbar, aber unvollständig
- `missing`: Objektklasse ist nicht zuverlässig lesbar

Interpretation:

- `partial`: Vergleich erlaubt mit klarer Warnung (`metadata_coverage=partial`) pro
  Objektklasse.
- `missing`: Vergleich für die betroffene Klasse blockiert mit
  `action_required`, außer bei expliziter Freigabe über
  `--allow-metadata-gaps` (mit dokumentierter Risikoannahme).

### 5.6 Capability-Governance für Trino

| Befehl | Source | Target | Phase |
| --- | --- | --- | --- |
| `schema reverse` | ✅ | ❌ | 1 |
| `schema compare` | ✅ | ✅ *(read-only Diff-Pfad)* | 1 |
| `data export` | ✅ | ❌ | 1 |
| `data profile` | ✅ | ❌ *(nur mit Profiling-Modul)* | 1 |
| `data transfer` | ✅ | ❌ | 1 |
| `schema generate` | ❌ | ⚠️ (explizit freigegeben) | 3 |
| `data import` | ❌ | ❌ | 4+ |

Regel:

- `Target` für Trino ist in Phase 1 standardmäßig gesperrt.
- `schema compare --target trino://...` bleibt erlaubt, weil semantisch read-only.
- Write-/Generate-Funktionen erfordern immer einen expliziten Capability-Review je
  Connector.

## 6) Umsetzungsphasen

### Phase 1 — Read-only MVP

**Ziel:** sicherer Trino-Lesepfad ohne Schreib-Risiko.

1. Dialekt + URL-Alias implementieren.
2. Adapter/JDBC hinzufügen:
   - Connection/Pooling
   - `TrinoSchemaReader`
   - `TrinoTableLister`
   - `TrinoDataReader`
3. CLI-Coverage aktivieren:
   - `schema reverse`
   - `schema compare`
   - `data export`
   - `data profile` (nur mit aktivem `driver-trino-profiling`)
   - `data transfer` mit Source-only-Guard

Validierungsregeln:

- `schema reverse --source trino://... --output ...` ist lauffähig.
- `data transfer --target trino://...` startet nicht.
- `data profile --source trino://...` ist nur mit aktivem Profiling-Modul möglich.
- `data profile --source trino://...` ohne Modul endet mit `action_required` + Hinweis.
- Nicht erlaubte Query-Properties liefern reproduzierbar `action_required`.
- `schema compare --target trino://...` dokumentiert `metadata_coverage` pro
  Objektklasse.

### 6.1 Phase-1-Abnahmekriterien

- URL-Parsing:
  - `catalog`- und `schema`-Auflösung sind deterministisch.
  - Fehlende Felder oder ungültige Pfadsegmente liefern `action_required`.
  - Nicht erlaubte Query-Properties liefern `action_required`.
- Capabilities:
  - Source-only-Verhalten für `data transfer` ist technisch erzwungen.
  - `data import` ist in Phase 1 deaktiviert.
- Security:
  - Secret-Maskierung in Logs, Fehlermeldungen und Hilfetexten ist verifiziert.
- Compare:
  - `schema compare --target trino://...` liefert `metadata_coverage`.

### Phase 2 — Profiling- und Diagnosehärtung

- Stabile Profiling-Coverage und Diagnoseabdeckung.
- Trino-spezifische Hinweise/Warnungen im Compare-Pfad.
- Metadatenkonsistenz-Tests zwischen Connector-Typen (z. B. Hive vs. Iceberg).

### Phase 3 — Controlled `schema generate`

- `schema generate --target trino://...` nur explicit freigeschaltet.
- harte Guard-Grenzen mit klarer `action_required`-Ausgabe.

### Phase 4 — Writes per Capability-Matrix

Write-Pfade nur bei expliziter Fähigkeit je Connector:

- `supportsInsert`
- `supportsCreateTable`
- `supportsCreateTableAs`
- `supportsMerge`
- `supportsDelete`
- `supportsUpdate`
- `supportsTransactions`

## 7) Funktions-Matrix mit Risiko-Niveau

| Feature | Ziel-Fit | Begründung |
| --- | --- | --- |
| `schema reverse` | Hoch | starke Synergie mit Trino-Layer |
| `schema compare` | Hoch | hilfreich für Lakehouse-/Connector-Vergleiche |
| `data export` | Sehr hoch | skalierbare Leseauslastung |
| `data profile` | Sehr hoch | analytische Abdeckung |
| `data transfer` *(Source)* | Hoch | zentrale SQL-/Analyse-Schicht |
| `data transfer` *(Target)* | Gering | stark Connector-abhängig |
| `schema generate` | Mittel | nur explizit + starke Guarding |
| `data import` | Niedrig | kein Primärfall für Phase 1 |
| klassische OLTP-Migration | Sehr gering | Trino kein Migrations-Primärknoten |

## 8) CLI-Beispiele

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

# Wird in Phase 1 geblockt
d-migrate data transfer \
  --source postgresql://app@localhost:5432/app \
  --target trino://analyst@localhost:8080/iceberg/default \
  --tables customers
```

Hinweise:

- `schema reverse`/`compare` liefern die Trino-Sicht des Zielsystems.
- `data transfer --target trino://...` ist in Phase 1 blockiert.
- Nicht unterstützte Objekte (Constraints/Indexes/Triggers/Procedures) werden
  als fehlende Sichtbarkeit explizit gekennzeichnet.

## 9) Risiken und Gegenmaßnahmen

### 9.1 Metadaten-Lücken

- Trino-Connectoren liefern teils unvollständige Metadaten.
- Gegenmaßnahme: sichtbare `action_required`-/Warnmeldungen statt stillen Fallbacks.

### 9.2 Schreibsemantik

- Connector-abhängige Schreibunterschiede sind nicht einheitlich.
- Gegenmaßnahme: keine generische Write-Freigabe, erst Capability-basierte Freigabe.

### 9.3 Erwartungsmanagement

- Risiko: Trino als „voller DB-Ersatz“ missverstanden.
- Gegenmaßnahme: klare, wiederholte Kommunikation in README/Specs/CLI-Hilfe.

### 9.4 Spezifikationsklarheit

- Risiko: unterschiedliche URL-/Capability-Definitionen in Specs und Implementierung.
- Gegenmaßnahme: ein verbindliches Modell (Canonical-URL, Source/Target-Regeln,
  Guard-Fehlerklassen).

## 10) Betroffene Artefakte

- `spec/architecture.md` (Adapterposition)
- `spec/cli-spec.md` (Source-/Target-Dialekt- und Capability-Doku)
- `spec/connection-config-spec.md` (URL-Form)
- `settings.gradle.kts` (Modulverkabelung)
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DatabaseDialect.kt`
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionUrlParser.kt`
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DialectCapabilities.kt`
- `adapters:driven:driver-trino` (neu)
- `adapters:driven:driver-trino-profiling` (falls `data profile` in Phase 1)
- `hexagon`-Ports bei späteren Phasen (Capability-Guards)
- `docs/planning/roadmap.md`
- ggf. User-Dokumentation

## 11) Akzeptanzkriterien (gesamt)

- `TRINO`-Dialekt und `trino://...` sind parsebar und dokumentiert.
- `schema reverse` gegen mindestens einen Trino-Katalog/Schema erfolgreich nutzbar.
- `schema compare` gegen `trino://...` mit klarer Diff-/Limit-/`metadata_coverage`-Dokumentation.
- `data export` aus Trino stabil nutzbar.
- `data profile` liefert belastbare Kernkennzahlen (mit Profiling-Modul in Phase 1).
- `data transfer` ist Phase 1 Source-only.
- `data transfer --target trino://...` blockiert reproduzierbar mit `action_required`.
- `schema generate --target trino://...` bleibt bis Phase 3 deaktiviert.
- `schema compare --source file... --target trino://...` listet
  Connector-Grenzen explizit (OID/Constraints/Indexes/Procedures).
- URL-Properties außerhalb der Allowlist liefern reproduzierbar `action_required`.
- `metadata_coverage=missing` oder unzulässig niedrige Qualität bricht den
  Vergleich (ohne stilles Ignorieren).
- Secrets werden in allen Ausgaben maskiert und nicht persistiert/geloggt.

## 12) Empfehlung

Trino soll als **eigener Read/Analytics-/Federation-Adapter** behandelt werden.
Keine Vermischung mit klassischen OLTP-Migrationspfaden.

## Definition of Done

### DoD — Phase 1 (MVP)

- [ ] `DatabaseDialect.TRINO` vorhanden.
- [ ] Alias `trino` in Dialektauflösung und URL-Parsing verankert.
- [ ] `adapters:driven:driver-trino` in `settings.gradle.kts` aufgenommen.
- [ ] Trino-Connection-Factory/JDBC-Pool lauffähig.
- [ ] `TrinoSchemaReader`, `TrinoTableLister`, `TrinoDataReader` implementiert.
- [ ] `schema reverse --source trino://...` lauffähig.
- [ ] `schema compare --source file... --target trino://...` lauffähig.
- [ ] `schema compare --source file... --target trino://...` veröffentlicht
  `metadata_coverage` nach Objektklasse.
- [ ] `data export --source trino...` lauffähig.
- [ ] `data profile --source trino...` lauffähig (mit Profiling-Modul).
- [ ] `data profile --source trino://...` ohne Modul liefert `action_required` und
  Anleitung.
- [ ] `data transfer --source trino... --target trino://...` blockiert mit
  `action_required`.
- [ ] Source-only-Regel für Trino technisch und dokumentiert durchgesetzt.
- [ ] Trino-Metadaten-Lücken/Unbekannte als Warnungen ausgegeben.
- [ ] `data transfer --target trino...` liefert klare Fehlerklasse `action_required`.
- [ ] Nicht erlaubte Query-Properties (`foo=bar`) führen reproduzierbar zu
  `action_required`.
- [ ] Keine Secret-Ausgaben in Logs/Fehlern/Debug-Meldungen.
- [ ] Kein DDL-/Import-/Transfer-Write-Pfad für TRINO aktiv.
- [ ] Mindest-Doku ergänzt: `spec/cli-spec.md`, `spec/connection-config-spec.md`.

### DoD — Phase 2

- [ ] Trino-spezifische Profiling-Warn- und Coverage-Klassen dokumentiert.
- [ ] Optionales Profiling-Modul `driver-trino-profiling` verfügbar oder klare
  Begrenzung dokumentiert.

### DoD — Phase 3

- [ ] `schema generate --target trino://...` ist nur explizit und begrenzt aktiv.
- [ ] `action_required` für nicht abbildbare Objekte konsistent dokumentiert.

### DoD — Phase 4

- [ ] `supports*`-Capability-Vertrag pro Connector definiert.
- [ ] Trino setzt Write-/Generate-/Transfer-Guards nach Capability-Vertrag um.
- [ ] Trino-Writepfade bleiben deaktiviert, solange Capability-Freigaben fehlen.
- [ ] Kein Produktivbetrieb mit stiller oder impliziter Schreibsemantik.
