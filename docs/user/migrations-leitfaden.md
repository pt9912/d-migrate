# Migrations-Leitfaden

> **Software-Version:** 0.9.9 (Beta) · **Stand:** 16.06.2026
>
> **Zielgruppe:** Personen, die eine konkrete Datenbank-Migration mit d-migrate
> planen und durchführen. Grundkenntnisse aus dem
> [Anwenderhandbuch](anwenderhandbuch.md) werden vorausgesetzt; exakte
> Kommando-/Flag-Details stehen in der [API-Referenz](api-referenz.md) und im
> [`guide.md`](guide.md) (mit kopierbaren Beispielen). Mit **🔮** markierte
> Punkte sind für spätere Milestones geplant.

---

## 1. Einführung

### 1.1 Migrationsphilosophie: das neutrale Modell als Pivot

Jede Migration läuft über ein **datenbankunabhängiges neutrales Modell**:
`Quelle → neutrales Modell → Ziel`. d-migrate übersetzt nie direkt von Dialekt zu
Dialekt, sondern reverse-engineert die Quelle in das neutrale Modell und
generiert daraus die Zielstrukturen. Das macht jede Quell-Ziel-Kombination zu
einer Komposition zweier unabhängiger Schritte und hält die Übersetzungsregeln
testbar. Format und Typsystem: [`spec/neutral-model-spec.md`](../../spec/neutral-model-spec.md).

### 1.2 Was d-migrate migriert

- **Schema (Default beim Reverse):** Tabellen, Spalten/Typen, Primär-/
  Fremdschlüssel, Constraints, Indizes, Sequenzen.
- **Schema (opt-in):** Views, Trigger, Functions und Procedures erfasst
  `schema reverse` **nur** mit den Flags `--include-views` / `--include-triggers`
  / `--include-functions` / `--include-procedures` (oder `--include-all`).
  **Ohne** diese Flags werden sie ohne Fehler **ausgelassen** (siehe
  [§4.1](#4-schema-migration)).
- **Daten:** zeilenweise streaming-basiert (export/import oder direkter
  DB-zu-DB-Transfer), inkl. inkrementeller und resume-fähiger Pfade.

### 1.3 Grenzen und bewusste Nicht-Ziele

- **Keine Live-Replikation** und keine kontinuierliche Synchronisation — d-migrate
  ist ein Batch-Werkzeug.
- **Dialektspezifische prozedurale Logik** (PL/pgSQL, MySQL-Procedural-SQL …) wird
  **nicht** rein regelbasiert übersetzt: bei unterschiedlichen Dialekten erzeugt
  die DDL-Generierung `action_required` (**E053**); die Body-Transformation ist
  KI-gestützt oder manuell (siehe [§9](#9-stored-procedure-migration)).
- Verlustbehaftete Typ-Abbildungen werden gemeldet, nicht still durchgeführt
  (siehe [§6.5](#6-spezialfälle-und-stolpersteine)).

---

## 2. Der Migrations-Workflow im Überblick

### 2.1 Phasenmodell

```
1. reverse   Quelle → neutrales Schema           (schema reverse)
2. generate  neutrales Schema → Ziel-DDL          (schema generate --target …)
3. anlegen   Ziel-Schema erzeugen (pre-data)
4. daten     DB→DB-Transfer ODER Export+Import
5. anlegen   post-data (Trigger/Routinen/abhängige Views)
6. verify    Soll/Ist vergleichen                 (schema compare)
```

### 2.2 Entscheidungsbaum: direkter Transfer vs. Artefakt-basiert

| Kriterium | `data transfer` (direkt DB→DB) | Export + Import (Artefakt) |
| --------- | ------------------------------ | -------------------------- |
| Zwischenformat | keines (Stream) | Datei (JSON/YAML/CSV/Parquet) |
| Audit/Reproduzierbarkeit | gering | hoch (Artefakt prüf-/aufbewahrbar) |
| Resume nach Abbruch | nein | ja (`--resume` mit Checkpoint) |
| Netzwerk | beide DBs gleichzeitig erreichbar | entkoppelbar |
| Empfehlung | schnelle, einmalige Transfers | Pilot/Produktion, große Datenmengen, Offline-Schritt |

---

## 3. Vorbereitung

### 3.1 Quell- und Zielanalyse

`schema reverse` gegen die Quelle ausführen und das Ergebnis mit
`schema validate` prüfen. Warnungen (z. B. nicht abbildbare Typen, fehlende
Primärschlüssel **E008**) **vor** der Migration klären — sie sind die häufigste
Ursache für spätere Import-Fehler. Enthält die Quelle Views/Trigger/Functions/
Procedures, beim Reverse `--include-all` (oder die einzelnen `--include-*`-Flags)
setzen — der Default-Reverse lässt sie still aus.

### 3.2 Verbindungen einrichten

Quell- und Zielverbindung in der `.d-migrate.yaml` (`database.connections`) oder
als direkte URL angeben. URL-Format, Aliase, Sonderzeichen und Pool-Verhalten:
[`spec/connection-config-spec.md`](../../spec/connection-config-spec.md) §1–§3
bzw. [Administrationshandbuch §3–§4](administrationshandbuch.md#3-konfiguration).

### 3.3 Kompatibilität prüfen (Dialekt-Unterschiede)

Typ-Mapping-Lücken und Präzisionsrisiken zwischen den Dialekten vorab sichten:
[`spec/type-mapping.md`](../../spec/type-mapping.md) (String-Längenerhaltung,
PostgreSQL-Extension-Typen wie `citext`/`ltree`, versions- und
dialektspezifische Lücken).

---

## 4. Schema-Migration

| Schritt | Kommando (Details: [`guide.md`](guide.md), [API-Referenz §3](api-referenz.md#3-cli-referenz)) |
| ------- | --------------------------------------------------------------------------------------------- |
| **4.1 Reverse Engineering der Quelle** | `schema reverse` → neutrales Schema (YAML/JSON). **Default: nur Tabellen/Sequenzen/Constraints/Indizes** — Views/Trigger/Functions/Procedures per `--include-all` bzw. den einzelnen `--include-*`-Flags mitnehmen, sonst werden sie ohne Fehler ausgelassen. |
| **4.2 Ziel-DDL generieren** | `schema generate --target <postgresql\|mysql\|sqlite>` |
| **4.3 Split-DDL** | `schema generate --split pre-post` trennt **pre-data** (Tabellen, Sequenzen, Indizes, Constraints, einfache Views) von **post-data** (Trigger, Functions, Procedures, Views mit Routinen-Abhängigkeiten) |
| **4.4 Verifikation** | `schema compare` Quelle↔Ziel (Exit `1` = Unterschiede gefunden) |

Die Split-DDL ist der Schlüssel für eine sichere Import-Reihenfolge: pre-data vor
dem Datenimport anlegen, post-data **danach**, damit Trigger/Routinen nicht
während des Imports feuern. Phasenzuordnung normativ:
[`spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md).

---

## 5. Daten-Migration

### 5.1 Export/Import vs. direkter Transfer

- **Direkt:** `data transfer --source <src> --target <tgt>` streamt zeilenweise
  von DB zu DB.
- **Artefakt-basiert:** `data export … --output <datei>` dann `data import …` —
  mit Checkpoint/`--resume` und prüfbarem Zwischenartefakt.

### 5.2 Reihenfolge und Constraint-Handhabung

Tabellen werden topologisch nach Fremdschlüssel-Abhängigkeiten sortiert.
Steuerflags (Import und Transfer):

- `--table-order` / `--tables` — Reihenfolge bzw. Auswahl explizit setzen.
- `--disable-fk-checks` — Fremdschlüsselprüfung während des Imports aussetzen
  (für Bulk-Loads; danach Integrität verifizieren).
- `--truncate` — Zieltabellen vor dem Laden leeren.

### 5.3 Trigger während des Imports deaktivieren

`--trigger-mode disable` (Default `enable`) verhindert, dass Ziel-Trigger beim
Import feuern — kombiniert mit der pre-data/post-data-Reihenfolge aus
[§4.3](#4-schema-migration).

### 5.4 Idempotenter UPSERT-Import

`--on-conflict update` (Default `abort`) aktualisiert vorhandene Datensätze statt
abzubrechen — sicher für wiederholte Läufe.

### 5.5 Inkrementelle Migration (LF-013)

`--since-column <spalte> --since <wert>` exportiert/transferiert nur Zeilen
oberhalb eines Markers (z. B. `updated_at`). Für unterbrochene Läufe steht
zusätzlich `--resume` mit Checkpoint-Referenz bereit (Details und Grenzen:
[`guide.md`](guide.md), [API-Referenz](api-referenz.md)).

### 5.6 Parquet als Transportformat

`--format parquet` für `data export`/`data import` — kompaktes, typstabiles
Spaltenformat für große Datenmengen und Lakehouse-nahe Pipelines (siehe
[`guide.md`](guide.md) „Parquet-Export und -Import").

---

## 6. Spezialfälle und Stolpersteine

### 6.1 Sequenzen: PostgreSQL vs. MySQL/SQLite-Emulation

PostgreSQL hat native benannte Sequenzen. MySQL und SQLite emulieren sie über
eine Hilfstabelle `dmg_sequences` + ein kanonisches `BEFORE/AFTER INSERT`-
Trigger-Paar. Für SQLite muss die Emulation per `--sqlite-named-sequences
helper_table` aktiviert werden (Default `action_required`, E056-Skip). Reverse
und Compare erkennen die Hilfsobjekte automatisch (siehe
[`guide.md`](guide.md) „MySQL-/SQLite-Sequence-Emulation").

### 6.2 `preserveCurrentValue` korrekt nutzen

Der aktuelle Sequenzwert wird bei Bedarf atomar unter Lock übernommen
(`pg_advisory_xact_lock` / `SELECT FOR UPDATE` / `BEGIN IMMEDIATE` je Dialekt).
Voraussetzungen, Opt-in und Restrisiken (PG-`nextval`-Race) sind in
[`guide.md`](guide.md) „preserveCurrentValue" beschrieben.

### 6.3 Trigger, Functions, Procedures

Strukturelle Hüllen werden generiert; **Body-Logik** bei unterschiedlichen
Dialekten nicht regelbasiert übersetzt → `action_required` (**E053**), siehe
[§9](#9-stored-procedure-migration). **Wichtig:** `schema reverse` erfasst
Trigger/Functions/Procedures (und Views) nur mit den `--include-*`-Flags
(Default aus, siehe [§4.1](#4-schema-migration)) — sonst fehlen sie schon im
neutralen Modell. Beim Datenimport stellt `--reseed-sequences` (Default) den
korrekten Sequenz-Folgewert nach dem Laden ein; `--no-reseed-sequences`
schaltet das ab.

### 6.4 Materialized Views

Wo der Zieldialekt keine Materialized Views nativ unterstützt, wird auf eine
normale View zurückgefallen (Warnung **W103**) — die Materialisierung entfällt
und muss bei Bedarf zielseitig nachgebildet werden.

### 6.5 Typ-Mapping-Risiken und Präzisionsverlust

Nicht jeder Quelltyp hat eine verlustfreie Entsprechung (z. B. PostgreSQL
`citext`/`ltree`/`hstore`, dialektabhängige `DECIMAL`/Zeit-Präzision,
String-Längen). d-migrate meldet solche Fälle als Warnung statt sie still zu
verändern. Bekannte Lücken pro Dialekt:
[`spec/type-mapping.md`](../../spec/type-mapping.md) §2–§4.

### 6.6 Round-Trip-Risiko verstehen

Ein Reverse → Generate → Reverse muss nicht zeichengleich sein, wenn der
Zieldialekt eine Eigenschaft nur emuliert (z. B. SQLite-Sequenzen). Welche
Round-Trips stabil sind und welche bewusst degradieren, steht in
[`guide.md`](guide.md) „Round-Trip-Risiko".

---

## 7. End-to-End-Playbooks

Das Grundmuster ist für alle Richtungen gleich; nur die dialektspezifischen
Hinweise unterscheiden sich. Vollständige, kopierbare Beispiele:
[`guide.md`](guide.md).

```
schema reverse  --source <QUELLE> --include-all --output schema.yaml  # ohne --include-all: Views/Trigger/Functions/Procedures werden still ausgelassen
schema validate --source schema.yaml
schema generate --source schema.yaml --target <ZIEL> --split pre-post --output ddl/
# pre-data-DDL gegen das Ziel anwenden
data transfer   --source <QUELLE> --target <ZIEL> --trigger-mode disable
#   (alternativ: data export … | data import …)
# post-data-DDL anwenden
schema compare  --source <QUELLE> --target <ZIEL>   # Exit 1 = Unterschiede
```

| Playbook | Dialekt-Hinweise |
| -------- | ---------------- |
| **7.1 PostgreSQL → MySQL** | Sequenzen werden in MySQL über `dmg_sequences` emuliert; PG-`citext`/Extension-Typen prüfen ([§6.5](#6-spezialfälle-und-stolpersteine)) |
| **7.2 MySQL → PostgreSQL** | `TINYINT(1)`↔`BOOLEAN`-Semantik prüfen; MySQL-Sequence-Emulation wird auf native PG-Sequenzen abgebildet |
| **7.3 → SQLite** | `--sqlite-named-sequences helper_table` setzen, sonst E056-Skip; Materialized Views → View (W103) |
| **7.4 Cross-DB Round-Trip PG → MySQL → SQLite** | Abnahmeziel **8.6** für 1.0.0 — als verketteter Smoke geliefert (`make sample-db-3hop-smoke`): End-to-End-Parität + Serial/Array/ENUM-Transformationen über die ganze Kette; jede Stufe zusätzlich einzeln mit `schema compare` abnehmbar |

---

## 8. Export in Migrations-Frameworks

Statt direkt zu migrieren, kann d-migrate die Zielstruktur als
Framework-Migrationsdateien ausgeben — für Teams, die ihren bestehenden
Migrations-Workflow behalten:

| Kommando | Ziel-Framework |
| -------- | -------------- |
| `export flyway` | Flyway (versionierte SQL-Migrationen) |
| `export liquibase` | Liquibase (Changelog) |
| `export django` | Django-Migrations |
| `export knex` | Knex.js-Migrations |

Optionen und Ausgabeformate: [API-Referenz §3](api-referenz.md#3-cli-referenz),
normativ [`spec/cli-spec.md`](../../spec/cli-spec.md).

---

## 9. Stored-Procedure-Migration

Bei gleichem Quell-/Zieldialekt wird der Body 1:1 übernommen. Bei
**unterschiedlichen** Dialekten ist die prozedurale Logik nicht regelbasiert
übersetzbar; d-migrate erzeugt `action_required` (**E053**). Für die
Body-Transformation gibt es heute zwei Wege:

- **KI-gestützt über den MCP-Server:** die Tools `procedure_transform_plan` und
  `procedure_transform_execute` (siehe [API-Referenz §4.5](api-referenz.md#45-tool-katalog))
  transformieren den Body über ein abstraktes Zwischenformat in den Zieldialekt.
- **Manuell:** den als `action_required` markierten Body zielseitig nachbilden.

Ein durchgängiges Beispiel (PostgreSQL → MySQL) steht in
[`beispiel-stored-procedure-migration.md`](../planning/open/beispiel-stored-procedure-migration.md).

> 🔮 Ein CLI-Kommando `d-migrate transform procedure` ist als Zielbild in
> [`spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md) genannt,
> aber **noch nicht** implementiert — die KI-Transformation läuft heute über die
> MCP-Tools.

---

## 10. Validierung und Abnahme

### 10.1 Schema-Compare als Abnahmegate

`schema compare` zwischen Quelle und Ziel; Exit-Code `1` signalisiert
Unterschiede und eignet sich direkt als CI-Gate.

### 10.2 Datenintegrität

Zeilenzahlen pro Tabelle und Stichproben vergleichen; `data profile` liefert je
Quelle/Ziel einen Datenqualitäts-Report, dessen Kennzahlen sich gegenüberstellen
lassen.

### 10.3 SHA-256-Verifikation

Beim Direkt-Transfer prüfen Sie die Datenintegrität mit `data transfer --verify`
([`LN-009`](../../spec/lastenheft-d-migrate.md#ln-009)): d-migrate bildet nach dem
Transfer je Tabelle eine dialekt-neutrale, reihenfolge-unabhängige SHA-256-
Prüfsumme über Quelle und Ziel und meldet Divergenzen mit Exit-Code `3`.

```bash
d-migrate data transfer --source staging --target local_pg --truncate --verify
```

Voraussetzung ist ein sauberer Load (leeres oder mit `--truncate` geleertes Ziel).
Bei Cross-Dialekt-Transfers, die eine Spalte repräsentativ umformen (z. B.
`text[]`→`json`, `tsvector`→`text`, `timestamptz`→`datetime`), lässt sich die
Byte-Gleichheit nicht bestätigen — diese Spalten werden mit einer Warnung aus der
Prüfung ausgeschlossen und im Report ausgewiesen; alle übrigen Spalten werden
byte-genau verglichen. Ergänzend bleiben Schema-Compare und Zeilen-/Stichproben-
vergleich ([10.1](#10-validierung-und-abnahme)/[10.2](#10-validierung-und-abnahme)) nützlich.

### 10.4 Checkliste für Pilot-Migrationen

- [ ] Quelle reverse-engineert, `schema validate` ohne offene Errors.
- [ ] Quelle enthält Views/Trigger/Functions/Procedures? Dann beim Reverse `--include-all`/`--include-*` gesetzt (Default lässt sie aus).
- [ ] Typ-Mapping-Warnungen ([§6.5](#6-spezialfälle-und-stolpersteine)) gesichtet und akzeptiert/behoben.
- [ ] pre-data angelegt, Daten geladen, post-data angelegt.
- [ ] `schema compare` Quelle↔Ziel ohne unerwartete Unterschiede.
- [ ] Zeilenzahlen + Stichproben verifiziert ([§10.2](#10-validierung-und-abnahme)).
- [ ] Sequenz-Folgewerte korrekt ([§6.1](#6-spezialfälle-und-stolpersteine)/[§6.2](#6-spezialfälle-und-stolpersteine)).

Diese Checkliste ist Teil des 0.9.9-Pilot-Programms (Pilotanwender-Tests, LF 9.2).

---

## Verwandte Dokumentation

- [Anwenderhandbuch](anwenderhandbuch.md) · [Best-Practices-Leitfaden](best-practices-leitfaden.md) · [Troubleshooting-Leitfaden](troubleshooting-leitfaden.md) · [API-Referenz](api-referenz.md) · [Administrationshandbuch](administrationshandbuch.md) · [`guide.md`](guide.md)
- [`spec/neutral-model-spec.md`](../../spec/neutral-model-spec.md), [`spec/type-mapping.md`](../../spec/type-mapping.md), [`spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md), [`spec/cli-spec.md`](../../spec/cli-spec.md), [`spec/connection-config-spec.md`](../../spec/connection-config-spec.md)
