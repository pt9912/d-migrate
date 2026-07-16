# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **Ungültiges SQLite-DDL bei zusammengesetztem Primärschlüssel mit Identity-Spalte** (Warn-Code
  **W135**) — eine Identity-/`AUTO_INCREMENT`-Spalte, die Teil eines **zusammengesetzten**
  Primärschlüssels wird (z. B. weil eine partitionierte MySQL-Tabelle ihren Partitionsschlüssel in
  den PK faltet), erzeugte in SQLite **zwei** PRIMARY-KEY-Deklarationen — inline
  `INTEGER PRIMARY KEY AUTOINCREMENT` **und** table-level `PRIMARY KEY (…)` — und damit nicht
  ladbares DDL (»table has more than one primary key«). Die Spalte wird jetzt als schlichtes
  `INTEGER`-Mitglied des zusammengesetzten Schlüssels gerendert (AUTOINCREMENT entfällt, neuer
  Warn-Code W135). Behoben auf allen drei SQLite-`CREATE TABLE`-Pfaden (generate, diff/migrate,
  Table-Rebuild). Aufgedeckt vom neuen 3-Hop-Cross-Dialect-Smoke (Lastenheft 8.6).

### Added

- **3-Hop-Cross-Dialect-Smoke** (`make sample-db-3hop-smoke`, Lastenheft 8.6) — die wörtliche Kette
  **PostgreSQL → MySQL → SQLite** als *ein* verketteter Test (Pagila-Quelle): End-to-End-Zeilen-Parität
  über alle logischen Tabellen plus die drei 8.6-Typ-Transformationen
  Serial→`AUTO_INCREMENT`→`AUTOINCREMENT`, Array→JSON→JSON und ENUM→ENUM→CHECK. Ergänzt die
  bestehenden paarweisen Cross-Dialect-Smokes um die dritte Kaskadenstufe (MySQL→SQLite).
- **Best-Practices-Leitfaden** ([`docs/user/best-practices-leitfaden.md`](docs/user/best-practices-leitfaden.md)) —
  verdichtete Empfehlungen, Faustregeln und Anti-Patterns quer über die Aufgaben: Pre-Flight,
  Performance-Tuning (das einzige konsolidierte `parallelism`/`chunk_size`/`fetch_size`/`pool`-Kapitel),
  Cross-Dialect-Typ-Fallstricke, Verifikation/sauberer Load, Rollback-Strategie, Credential-Handling und
  CI-Integration. Additiv zum Migrations-Leitfaden (verlinkt statt nacherzählt).
- **Troubleshooting-Leitfaden** ([`docs/user/troubleshooting-leitfaden.md`](docs/user/troubleshooting-leitfaden.md)) —
  schnelle Triage von Exit-Code bzw. Symptom zu Diagnose und Behebung: Exit-Code-Triage-Index (0–8/130),
  Diagnose-Werkzeuge (`--report`/`--plan-only`/`--dry-run`/`--output-format json`) und Bereichs-Playbooks
  (Verbindung/Zugangsdaten, Reverse/Generate/Migrate, Daten-Transfer/Verify, Cross-Dialect-Überraschungen).
  Additiv zum Meldungskatalog in Anwenderhandbuch Abschnitt 5 (verlinkt statt nacherzählt).

## [1.0.0-RC1] - 2026-07-16

### Added

- **`credentialRef`-Provider auf dem CLI-Pfad** ([`LN-025`](spec/lastenheft-d-migrate.md#ln-025) O4,
  [ADR 0035](docs/adr/0035-credential-provider-scheme-registry.md)) — eine Verbindung kann statt einer
  URL einen secret-freien **Zeiger** tragen: `database.connections.<name>: { credentialRef: "file:/pfad" }`
  bzw. `"env:VAR"`. Die Auflösung liefert die **vollständige** Connect-URL (Datei-Inhalt bzw.
  Umgebungsvariable). Neuer erster Nicht-`env:`-Provider `file:` (cross-platform, headless-tauglich,
  k8s-Secret-Mounts). Umgesetzt als geteilte, principal-freie `CredentialProviderRegistry`, die der
  CLI-`--source`/`--target`-Pfad **und** der MCP-Serve-Pfad nutzen (der bisherige
  `EnvConnectionSecretResolver` wurde verhaltenserhaltend darauf refaktoriert). **Fail-closed**: ein
  gesetzter, aber unauflösbarer `credentialRef` bricht mit Exit 7 ab (kein stiller Rückfall). `file:`
  meldet nie den Datei-Inhalt, hat einen 1-MiB-Size-Cap und strippt ein UTF-8-BOM. `providerRef` bleibt
  ein reservierter Backend-Selektor (Vault/Keychain-Zukunft; `keychain:` als Folge-Slice). 3-Agenten-
  Security-Review: kein fail-open, Discovery-Trennung intakt.
- **Gespeicherte Zugangsdaten werden beim Verbinden herangezogen** ([`LN-049`](spec/lastenheft-d-migrate.md#ln-049)
  Stufe 4) — ein mit `config credentials set --name <n>` hinterlegtes Passwort wird jetzt von
  `data export --source <n>` genutzt (Master-Passphrase via `D_MIGRATE_MASTER_PASSWORD` oder Prompt, je Lauf
  einmal). Additiv nach Stufe 2 (Env), dialekt-gegatet, **kein** fail-closed (fehlt Eintrag/Master-Secret →
  weiter ohne, mit secret-freier Notiz). Falsches Master-Secret / beschädigter Store → Exit 7 (secret-frei).
  Verdrahtet für **alle 8 Ops** (`data export`/`import`/`transfer`/`profile`, `schema compare`/`reverse`/
  `migrate`/`rollback`) bei explizitem `--source`/`--target`-Connection-Namen. Eine **prozess-weite**
  Session beschafft das Master-Secret höchstens **einmal pro Lauf** — Ops mit mehreren Ziel-Verbindungen
  bekommen so **einen** Prompt statt n. Auch bei **weggelassenem `--target`** (`data import`) greift der
  Store über den `database.default_target`-Namen (`NamedConnectionResolver.connectionName`). Der
  connect-Zeit-Prompt (Stufe 5 der Kette) ist bewusst nicht implementiert — die interaktive Eingabe eines
  DB-Passworts erfolgt über `config credentials set` (→ verschlüsselt abgelegt → konsumiert).
- **Konfigurierbarer JDBC-`fetchSize`** (`data export`/`transfer --fetch-size`, [`LN-005`](spec/lastenheft-d-migrate.md#ln-005)) —
  der Cursor-Prefetch für den Quell-Read ist nicht mehr eine pro Dialekt hart verdrahtete Konstante,
  sondern per CLI-Flag bzw. `pipeline.fetch_size` einstellbar (für sehr breite Zeilen kleiner wählbar,
  um die Peak-Speicherlast pro Chunk zu senken). Wird am Reader-Bau fixiert (immutable pro Instanz →
  parallel-sicher); SQLite = nur Hint; gilt nicht für den Import (liest aus Format-Dateien); der
  `data transfer --verify`-Read-Back nutzt denselben Wert. Präzedenz: CLI-explizit > Config >
  Dialekt-Default; `≤ 0` → Exit 2. [ADR 0033](docs/adr/0033-konfigurierbarer-fetchsize-und-pipeline-tuning.md).

### Fixed

- **`database.pool:` war auf dem Datenpfad ein stiller No-op** — der Pool-Block
  (`max_size`/`min_idle`/`connection_timeout_ms`/`idle_timeout_ms`/`max_lifetime_ms`) war in der
  Connection-Spec dokumentiert, wurde vom Datenpfad aber nie gelesen (`ConnectionConfig.pool` blieb
  immer der HikariCP-Default). Diese fünf Keys werden jetzt über einen `PoolSettingsResolver` aus
  `.d-migrate.yaml` aufgelöst und für `data export`/`import`/`transfer`/`profile` in die Verbindung
  injiziert (Präzedenz Config > Default; kein CLI-Flag). Zugleich deckelt `pipeline.parallelism: auto`
  nun gegen den konfigurierten `max_size` statt gegen den Hardcode-Default. Werte müssen positive
  Ganzzahlen sein; ein **explizit** gesetztes `min_idle > max_size` ist ein Fehler (Exit 7), während
  ein nur gesetztes `max_size` das Default-`min_idle` bei Bedarf herunterklemmt (`max_size: 1` allein
  ist gültig). Unbekannte/vertippte Keys unter `pool:` werden **laut abgelehnt** (statt still auf
  Default zurückzufallen). SQLite bleibt auf Pool-Größe 1 geklemmt. Die sicherheitskritischen
  Cancel-Reaktions-Schranken (keepalive-/statement-/network-Timeout) bleiben bewusst nicht über diese
  Sektion tunbar.
- **`D_MIGRATE_DB_PASSWORD` war ein stiller No-op** ([`LN-049`](spec/lastenheft-d-migrate.md#ln-049)) —
  die globale Fallback-Variable war in der Connection-Spec als Auflösungs-Stufe 2 dokumentiert, wurde vom
  Runtime aber nie gelesen. Alle CLI-DB-Operationen (`data export`/`import`/`transfer`/`profile`,
  `schema reverse`/`migrate`/`compare`/`rollback` inkl. der Preflight-/Sequenz-Probes) ergänzen jetzt ein
  **fehlendes** DB-Passwort aus `D_MIGRATE_DB_PASSWORD` (rein additiv: explizit inline/`${VAR}` gesetztes
  gewinnt; SQLite/no-auth wird übersprungen; ist die Variable leer/ungesetzt, bleibt es beim bisherigen
  Verhalten — **kein** fail-closed, passwortlose Auth wie Postgres `peer`/`trust`/`.pgpass` bleibt möglich).
  CLI-only; der MCP-Pfad ist bewusst ausgenommen. Store-Konsum (Stufe 4) + interaktiver Prompt (Stufe 5) folgen.
- **`pipeline.chunk_size` war ein stiller No-op** ([`LN-005`](spec/lastenheft-d-migrate.md#ln-005)) —
  der Config-Key war in der Connection-Spec dokumentiert, wurde vom Runtime aber ignoriert (nur
  `--chunk-size` wirkte). `pipeline.chunk_size` (export/import/transfer) und der neue
  `pipeline.fetch_size` (export/transfer) werden jetzt über einen `PipelineTuningResolver` echt
  verdrahtet (Präzedenz CLI-explizit > Config > Default; ungültige Werte → Exit 2 bzw. 7).

- **Parquet-Export: explizite Row-Group-Größe** ([`LN-005`](spec/lastenheft-d-migrate.md#ln-005)) —
  der Parquet-Writer setzt jetzt eine explizite Row-Group-Größe (Default 32 MiB) statt auf den
  parquet-java-~128-MB-Default zu fallen. Ohne das puffert jeder Writer eine ganze Row-Group im RAM;
  im parallelen File-per-Table-Export (`--parallel N`) wäre der Peak ≈ `N × 128 MB`. Über
  `export.parquet.row_group_bytes` in der Config überschreibbar.

- **Import: `chunkFailures`-Detailliste gedeckelt** ([`LN-005`](spec/lastenheft-d-migrate.md#ln-005)) —
  bei `--on-error log` wuchs die Liste der Chunk-Fehler-Details unbounded (ein Eintrag je
  fehlgeschlagenem Chunk → Memory-Leck bei sehr großen Tabellen). Sie ist jetzt auf ein Sample (1000)
  gedeckelt; darüber hinausgehende Einträge werden verworfen. Die wahre Fehlerzahl trägt ohnehin
  `rowsFailed`.

- **`pipeline.parallelism` war ein stiller No-op** ([`LN-005`](spec/lastenheft-d-migrate.md#ln-005)-Folge) —
  wie zuvor `chunk_size` war der Config-Key dokumentiert, aber unverdrahtet (nur `--parallel` wirkte).
  `pipeline.parallelism` (export/import/transfer) wird jetzt echt verdrahtet: Ganzzahl oder `auto`
  (= `min(CPU-Kerne, Pool-Größe)`), Präzedenz CLI-explizit > Config > Default 1. Kommt der Wert aus der
  Config und ist mit `--resume`/`--atomic` inkompatibel, fällt der Lauf mit Hinweis auf 1 zurück statt
  hart zu scheitern (harter Fehler nur bei explizitem `--parallel > 1`). Meldungstexte sind
  herkunftsbewusst (`pipeline.parallelism: auto (= N)` statt `--parallel N`).

## [0.9.12] - 2026-07-13

### Added

- **Paralleler Datenpfad** (LN-007/LN-008) — `data export`/`import`/`transfer`
  akzeptieren `--parallel N` und verarbeiten unabhängige Tabellen nebenläufig
  (`1` = sequenziell, Default). Unabhängige Tabellen laufen FK-sicher in
  topologischen Ebenen (Barriere zwischen den Ebenen); ein beidseitig
  namensgleich partitionierter Parent wird pro Kind-Partition transferiert bzw.
  als eine Datei pro Kind exportiert (PostgreSQL — MySQL-Partitionen sind keine
  adressierbaren Tabellen und bleiben transparent). Für SQLite auf `1` geklemmt
  (Pool-Size 1); `N` sollte die Verbindungspool-Größe (Default 10) nicht
  überschreiten; inkompatibel mit `--resume` und `--atomic` (Exit 2).
  [ADR 0032](docs/adr/0032-paralleler-datenpfad-tabellen-partitionen.md).

- **Atomarer Clean-Load-Rollback** (LN-013) — `data import`/`transfer --atomic`
  setzt bei einem Fehler **alle** Zieltabellen der Operation auf den leeren
  Vor-Load-Zustand zurück (Kompensations-Truncate) → „alle Tabellen oder keine".
  Erfordert explizit `--truncate` (sonst Exit 2) und ist inkompatibel mit
  `--resume`/`--parallel` (Exit 2). Die Kompensation ist eine
  O(1)-Metadaten-Operation und damit streaming-verträglich für große
  Datenmengen. Nicht-Scope: Append in ein nicht-leeres Ziel.
  [ADR 0031](docs/adr/0031-atomic-clean-load-rollback.md).

- **Read-only-Öffnung der Quelle** (`--read-only`, Default an) — reine
  Lese-Operationen (`data profile`/`export` und die Quelle von `data transfer`)
  öffnen die Quelldatenbank schreibgeschützt. Für SQLite bedeutet das
  `SQLITE_OPEN_READONLY` über `file:<db>?mode=ro` **ohne** `journal_mode=wal`:
  auch nicht-schreibbare Quellen sind les-/profilierbar, ohne
  `-wal`/`-shm`-Nebendateien anzulegen. `--no-read-only` erzwingt ein
  schreibendes Öffnen; das Transfer-Ziel bleibt immer schreibend. `:memory:`
  und andere Dialekte ignorieren das Flag. (Enthält einen Fix im
  SQLite-JDBC-URL-Builder: führende `//` der `file:`-URI-Form wurden als
  URI-Authority fehlinterpretiert.)

## [0.9.11] - 2026-07-12

### Added

- **First-Class SSL/TLS-Konfiguration** (LN-026, Minimal-Scope: typisiert +
  validiert) — SSL-Verbindungsparameter werden nicht mehr nur roh durchgereicht,
  sondern in ein neutrales `SslMode`/`SslSettings` (`ConnectionConfig.ssl`) geparst
  und validiert: PostgreSQL `sslmode`/`sslrootcert`, MySQL `sslMode`/`ssl`
  (Legacy-Bool opportunistisch → `PREFERRED`). Ungültige Modi ergeben einen klaren
  Fehler statt stillem Passthrough; die per-Dialekt-korrekten JDBC-Parameter werden
  über eine neue `JdbcUrlBuilder.sslParams`-Naht emittiert. Ohne explizite
  SSL-Parameter unverändertes Verhalten. Erzwingung (require-SSL) und
  Truststore/Keystore-Konfiguration folgen als spätere Tiefenstufen.

- **Audit-Logging der CLI-DB-Operationen** (LN-027) — neben dem MCP-Server
  emittieren jetzt auch die DB-berührenden CLI-Operationen (`schema
  reverse`/`migrate`/`compare` (mit DB-Operand)/`rollback --execute`, `data
  export`/`import`/`transfer`/`profile`) Audit-Events. Opt-in über
  `logging.audit.enabled: true`; jedes Event wird als JSONL-Zeile (ein
  `AuditEvent`-JSON pro Zeile) an `logging.audit.file` (Default
  `.d-migrate/audit.log`) angehängt. Der `CliAuditRecorder` ist
  exit-code-getrieben (`outcome`/`exitCode` aus dem Prozess-Exit-Code),
  scrubbt Verbindungs-Referenzen (`SecretScrubber`) und ist best-effort — ein
  Audit-Schreibfehler lässt die Operation nie abstürzen.

- **SHA-256-Datenintegritäts-Verifikation** (LN-009) — `data transfer --verify`
  gleicht nach dem Transfer Quelle und Ziel je Tabelle über eine dialekt-neutrale,
  reihenfolge-unabhängige SHA-256-Prüfsumme ab (additive 256-bit-Kombination;
  `CanonicalValueCodec` je NeutralType inkl. JSON semantisch, Array rekursiv,
  Geometry über WKB). Divergenz → Exit 3. Repräsentations-transformierende
  Cross-Dialekt-Spalten (`text[]`→`json`, `tsvector`→`text`, tz→lokal) werden
  familien-basiert mit Warnung ausgeschlossen (kein False-Positive), der Rest
  byte-genau verglichen. Setzt einen sauberen Load (leeres/getrunctes Ziel)
  voraus. ADR 0030.

### Fixed

- **`data profile` crashte bei Integer-Spalten und leeren Tabellen** (gemeldet vom
  m-trace-Consumer) — zwei `ClassCastException`-Klassen im Profiling-Pfad,
  systemisch in allen drei Adaptern (SQLite/PostgreSQL/MySQL):
  `targetTypeCompatibility` castete inkompatible Beispielwerte ungeprüft auf
  `String` (crashte bei jeder Integer-Spalte), und `sum(case …)` über 0 Zeilen
  liefert `NULL` statt `0` (crashte bei leerer/all-NULL-Spalte in `columnMetrics`,
  `numericStats` und `targetTypeCompatibility`). `data profile` läuft jetzt als
  Pre-Flight über reale Schemata mit Integer-Spalten und leeren Tabellen sauber
  durch (Exit 0 mit Profil-JSON) statt hart abzubrechen (Exit 5).

- **YAML-Schema-Codec: verlustfreier Round-Trip YAML-mehrdeutiger String-Werte** —
  Enum-Labels und String-Defaults wie `4.`, `9_`, `yes`, `on`, `~` oder
  `2024-01-01` wurden unter `MINIMIZE_QUOTES` unquotiert geschrieben und beim
  Lesen als Zahl/Bool/Null/Timestamp umgedeutet (`yes` → `true`, `4.` → `4.0`),
  d. h. die String-Identität ging verloren. Ein neuer `StringQuotingChecker`
  delegiert die Quoting-Entscheidung an denselben SnakeYAML-Resolver, den die
  Lese-Seite nutzt, und quotet genau die mehrdeutigen Scalars — harmlose Strings
  bleiben unquotiert (kein Output-Wechsel). Von LN-046 Property-Based-Testing
  aufgedeckt.

## [0.9.10] - 2026-07-11

### Added

- **Property-Based Testing** (LN-046, erledigt — Phasen A–C): `kotest-property`
  als Test-Dependency verdrahtet (stack-nativ statt Jqwik, [ADR 0029](docs/adr/0029-property-based-testing-framework.md)).
  - Phase A — `ObjectKeyCodecPropertySpec` verifiziert die „lossless"-Round-Trip-
    Garantie der Routine-/Trigger-Identitätskodierung über generierte Eingaben
    inklusive Shrinking.
  - Phase B — geteilter `Arb<NeutralType>` (alle 21 Varianten) in den
    core-Test-Fixtures; drei Driver-Specs prüfen `TypeMapper.toSql`-Totalität und
    `NeutralTypeCanonicalizer`-Idempotenz für PostgreSQL/MySQL/SQLite.
  - Phase C — geteilter `Arb<SchemaDefinition>`; das spec-genannte Schema-Parsing:
    `YamlSchemaCodec.read`-Robustheit (nie NPE), `MigrationFingerprint`-
    Ordnungsunabhängigkeit + Metadaten-Ausschluss und der semantische
    `write→read`-Round-Trip (Fingerprint als Orakel). PBT deckte dabei einen
    degenerierten Enum-Round-Trip-Fall auf (leeres Enum, im Generator
    ausgeschlossen). Slice `docs/planning/done/property-based-testing-ln046.md`.

### Fixed

- **SQLite: `NOT NULL` auf PRIMARY-KEY-Spalten** — SQLites `PRIMARY KEY`
  erzwingt (anders als PostgreSQL/MySQL) kein `NOT NULL`; nur `INTEGER PRIMARY
  KEY` und `WITHOUT ROWID` sind ausgenommen. Das neutrale Modell lässt
  `required` auf PK-Spalten weg (Invariante „PK ⇒ required implizit"), weshalb
  ein von d-migrate reversierter und wieder nach SQLite exportierter
  Schema-Stand `NOT NULL` auf allen PK-Spalten still verlor (stille
  Constraint-Schwächung, gemeldet vom m-trace-Consumer). Der SQLite-Renderer
  materialisiert die Invariante jetzt explizit (`SqlitePrimaryKeyNullability`)
  im `CREATE TABLE`- und im 12-Schritt-Rebuild-Pfad; sequence-emulierte
  PK-Spalten bleiben (transientes `NULL` während der Trigger-Befüllung)
  unangetastet, und die Rowid-Alias-Pfade (`INTEGER PRIMARY KEY`) ändern sich
  nicht. PostgreSQL- und MySQL-Ausgabe unverändert.

## [0.9.9] - 2026-07-08

**Cross-Dialect-Datentreue-Härtung.** Eine mehrrundige End-to-End-Pilot-Validierung
(PostgreSQL / MySQL / SQLite gegen
Pagila/Sakila, fünf Läufe) hat Cross-Dialect-Happy-Path-Defekte aufgedeckt, die
erst sichtbar wurden, nachdem die jeweils vorgelagerten Blocker behoben waren.
Alle gemeldeten P1/P2-Cross-Dialect-Befunde sind behoben; die verbleibenden
Restpunkte sind P3 und in `docs/planning/open/pilot-rerun-p3-residuals.md`
getrackt.

### Added

- **Strukturelle Transfer-Preflight** — Typ-Kompatibilität wird aus der
  Ziel-Dialekt-Typ-Abbildung abgeleitet (`normalize(toSql(X)) == normalize(toSql(Y))`)
  statt aus einer hand-gepflegten Fall-Liste; deckt jede tool-eigene
  Cross-Dialect-Abbildung per Konstruktion ab. Neuer Treiber-Port
  `DatabaseDriver.transferCompatibility()`.
- **`CURRENT_DATE` / `CURRENT_TIME`** als Funktions-Defaults über alle Dialekte
  (PG/SQLite `CURRENT_DATE`, MySQL `(CURRENT_DATE)`).
- **Index-Präfixlängen** (`IndexColumn.prefixLength`) round-trip-fähig: MySQL
  `SUB_PART`-Reverse → `col(n)`-Generate; PG/SQLite verwerfen mit Hinweis.
- **Reverse-Präferenzen** (ADR 0027) — inhärente Reverse-Mehrdeutigkeiten werden
  per deklarierter Anwender-Präferenz aufgelöst (Config `.d-migrate.yaml` `reverse:`
  + CLI-Flag, konservativer Default), nicht per Heuristik/Fold. Erster Fall: die
  SQLite-AUTOINCREMENT-Breite — `schema reverse` / `data transfer` mit
  `--sqlite-autoincrement-width 64` (oder `reverse.sqlite.autoincrement_width: 64`)
  rekonstruiert einen SQLite-AUTOINCREMENT-PK als 64-bit `biginteger` +
  `generation: identity` (statt 32-bit `identifier`), sodass ein SQLite→PostgreSQL-
  Transfer den Wertebereich nicht mehr auf `SERIAL` verengt (Ziel `BIGSERIAL`).
  Default unverändert (kein Fingerprint-Bump); die R202-Note nennt jetzt den Flag.

### Fixed

- **`enum` im migrate-Pfad**: der Diff-/`migrate`-Pfad rendert Enums jetzt konsistent
  zum `schema generate` — MySQL nativ als `ENUM('…')`, PostgreSQL-`refType`-Enum als
  Typreferenz auf den per `CREATE TYPE` erzeugten Typ (statt still zu bloßem `TEXT`).
  Die verbleibenden Inline-Fälle (PostgreSQL inline-`values`, alle SQLite-Enums)
  degradieren weiter zu `TEXT`, machen das aber jetzt **laut** über `W134` (statt
  stillem Wertverlust) — an allen Renderpfaden (CREATE TABLE, ADD COLUMN, SQLite-Rebuild).
- **Transfer-Datentreue PG → MySQL**: PG-`text[]` → MySQL-`JSON` (Wert als
  JSON-Array gebunden) und pgjdbc-`PGobject` (z. B. `tsvector`) → String-Bind
  statt Java-Serialisierung.
- **Transfer-Preflight** akzeptiert tool-eigene Mappings (bool→INTEGER,
  Enum↔Enum, Temporal→Text, timestamptz→DATETIME, Array→JSON, Decimal→REAL)
  statt strikter Neutraltyp-Gleichheit.
- **DDL-Generierung Cross-Dialect**: PG-Partition ohne Kind-Partitionen → plain
  Table + `E055`; nicht-portierbare View-Bodies (Backticks, `::`/`||`,
  dialektspezifische Funktionen) → `E053`-Skip statt invalidem DDL; MySQL
  AUTO_INCREMENT-PK-Reihenfolge; GIN/GIST-Index ohne Default-Opclass → `W123`-Skip.
- **Reverse-Engineering**: Nicht-PK-`nextval`-Spalte als Named-Sequence statt
  Identity; MySQL-ENUM-Werte case-erhaltend; Routinen-Namen ohne Signatur-Suffix;
  PG-Trigger-Action-Statement (`EXECUTE FUNCTION …`) nicht als plpgsql-Body
  gewrappt; PG-Domain-Render über die Ziel-Typ-Abbildung.
- **Parquet-Import**: Timestamp (`INT64`-µs / `Instant`) → `LocalDateTime` /
  `OffsetDateTime`.
- **Validierung**: Temporal-/Funktions-Defaults auf Date/DateTime/Time/Text
  korrekt akzeptiert.
- **Post-Execute-Compare von `schema migrate --execute`** meldet keine
  Drift-False-Positives mehr, wenn der Ziel-Dialekt Neutraltypen verlustfrei
  abflacht (z. B. SQLite `smallint`/`boolean` → `INTEGER`, `datetime` → `TEXT`,
  `decimal` → `REAL`) oder einen Single-Column-`UNIQUE`/-Fremdschlüssel als
  Spaltenattribut zurückliest: Ein frisches `migrate --execute` spec-valider
  Schemata endet jetzt mit **Exit 0** statt Exit 5, ein zweiter Lauf plant 0
  Operationen. Die Fingerprint-Berechnung ist dafür dialektbewusst
  (`schema-fingerprint-v6` → `v7`, invalidiert ältere Rollback-Artefakte/Overlays
  laut per `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` / `OVERLAY_STALE_*_FINGERPRINT`);
  `schema compare` bleibt strukturell streng (ADR-0026).
- **Generate materialisiert den impliziten `identifier`-Primärschlüssel** — ein
  spec-valides Schema mit `identifier`-getragenem PK (ohne ausgeschriebenes
  `primary_key`) ist jetzt auf allen Dialekten anlegbar: MySQL `identifier`-only
  bekommt die nötige KEY-Klausel für die AUTO_INCREMENT-Spalte (vorher Error 1075),
  SQLite `identifier` + explizites `primary_key` erzeugt keinen doppelten PK mehr
  (vorher SQLITE_ERROR), und PG `SERIAL` trägt nun `PRIMARY KEY` im Ziel. Der
  effektive PK wird zentral im `OperationMapper` über dieselbe
  `EffectivePrimaryKey`-Regel materialisiert, die Fingerprint und Comparator nutzen.

## [0.9.8] - 2026-06-14

### Breaking

- **Parquet — Bundle-Preflight-Exit-Codes folgen jetzt AP12 §9**
  *(2026-06-09, Parquet Cut A S9a-0)* — die CLI-Exit-Codes fuer
  Parquet-Bundle-Fehler entsprechen jetzt dem AP12-§9-Vertrag. Fuer
  Skripte, die auf konkrete Exit-Codes pruefen, aendert sich:
  - `MANIFEST_*`-Fehler (fehlendes/ungueltiges `manifest.yaml`,
    SHA-256-Mismatch, Kollisionen) → **Exit 4** (vorher Exit 3).
  - `tableFilter`/`tableOrder`-Fehler → **Exit 5** mit den stabilen
    Codes `BUNDLE_FILTER_UNKNOWN_TABLE`, `BUNDLE_ORDER_DUPLICATE`,
    `BUNDLE_ORDER_UNKNOWN_TABLE`, `BUNDLE_ORDER_INCOMPLETE` (vorher
    Exit 2 mit irrefuehrendem Code `MANIFEST_FILE_MISSING`).
  - Ein **partieller** `tableOrder` ist jetzt ein harter Fehler
    (`BUNDLE_ORDER_INCOMPLETE`) statt stillschweigend nicht-gelistete
    Tabellen zu droppen (Bugfix).
  - Per-Tabelle-Importfehler eines Bundle-Laufs geben jetzt den
    stabilen Code `BUNDLE_TABLE_IMPORT_FAILED: table='…' cause='…'`
    aus (Exit 5 unveraendert). Die Exit-Codes 4/5 sind mit
    Connection-/Streaming-Fehlern geteilt; der stderr-Code-Praefix
    unterscheidet. JSON/YAML/CSV/Single-File-Importe sind **nicht**
    betroffen.
  - `--resume`-Abbrueche eines Bundle-Imports geben jetzt feldweise
    benannte Codes statt einer generischen „fingerprint mismatch"-
    Meldung (Exit 3 unveraendert): `BUNDLE_FORMAT_VERSION_INCOMPATIBLE_WITH_CHECKPOINT`,
    `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`, `BUNDLE_TABLE_ORDER_CHANGED`
    (AP8 §8.4). Reine Diagnose-Verfeinerung — Skripte, die auf den
    alten stderr-Text geprueft haben, muessen die neuen Codes lesen.
- **Parquet — Pre-0.9.8-Bundle-Checkpoints sind nach 0.9.8 nicht
  mehr wiederaufnehmbar** *(2026-06-09, Parquet Cut A S8)* —
  Pre-0.9.8-Parquet-Checkpoints fuer **Bundle**-Importe sind nach
  0.9.8 nicht mehr wiederaufnehmbar (Fehlercode
  `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT`). Der
  `ImportCheckpointManager` verlangt fuer einen Parquet-Bundle-Resume
  jetzt den in 0.9.8 eingefuehrten `BundleCheckpointSpecifics`-
  Resume-Fingerprint im Manifest; ein Manifest ohne diesen Block kann
  einen laufenden Parquet-Bundle-Import nicht fortsetzen.
  JSON/YAML/CSV-Checkpoints und Single-File-Importe ohne vorherigen
  Checkpoint sind **nicht** betroffen. Pre-0.9.8-Parquet-Bundle-
  Checkpoints existierten in der Wildnis nicht (Parquet kam mit 0.9.8
  live); der Bruch ist defensiv im Code, nicht praktisch spuerbar.

### Fixed

- **Parquet — Single-File-Resume funktioniert jetzt** *(2026-06-09)* —
  ein `--resume` eines Single-File-Parquet-Imports scheiterte bisher
  immer mit `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` (Exit 3), weil
  der Erstlauf den Content-SHA-256 nicht persistierte (er wurde nur bei
  `--resume` berechnet). Der Hash wird jetzt auch beim Erstlauf berechnet,
  wenn ein Checkpoint-Verzeichnis aktiv ist (`--checkpoint-dir`), sodass
  ein späterer `--resume` ihn validieren kann (`PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT`
  bei geänderter Datei). Hinweis: derzeit nur mit explizitem
  `--checkpoint-dir` voll wirksam (config-only `pipeline.checkpoint.directory`
  ist Folge-Scope).

### Changed

- **Parquet — Scope-/Versions-Korrektur: Cut A statt Cut B,
  Ziel 0.9.8 statt 1.0.0** *(2026-06-06)* — AP13 §5.4 / §7
  hatten am 2026-06-05 „Cut B (Bundle-only Pilot) als 1.0.0"
  empfohlen; Stakeholder-Entscheid e7f3f714 folgte dieser
  Empfehlung. Am 2026-06-06 wurde diese Empfehlung in
  [`parquet-decision-template.md`](docs/planning/done-archive/parquet-decision-template.md)
  §8 superseded auf **Cut A (Voller Vertrag) als 0.9.8**.
  Begruendung: Operator-Mehrwert (Spark-/Hive-/DuckDB-
  Dateien direkt importieren) macht Cut B zu eng, sobald
  der Bundle-Pfad steht; die Versionsabsenkung auf 0.9.8
  macht den 1.0-Stamp explizit zum Engineering-Reife-Punkt
  (Native-Image, Distributions-Cut, Hadoop-Footprint-
  Minimierung) statt zum Feature-Vollstaendigkeits-Stamp.
  Folge-Releases (AP13 §8.3): **1.0.0** = Native-Image-Cut
  + Distributions-Cut (Default-JAR vs. `--parquet`-Variante)
  + optionaler Hadoop-API-Shim + Hadoop-Footprint-
  Minimierung (vormals 1.2.0-Scope plus §6.2-Distributions-
  Frage plus der aus dem Umbrella herausgehaltene
  Footprint-Cut); **1.0.0+ / spaeter** = MCP-Server-
  Spiegelung; vormaliger 1.1.0-Single-File-Scope entfaellt
  (Bestandteil von 0.9.8).
- **Parquet-Plan-Doc nach `done/` migriert — nur
  Evaluierungsphase abgeschlossen** *(2026-06-05)* —
  per `in-progress/`-Konvention (ADR-0004) wandern
  [`parquet-export-import-evaluation.md`](docs/planning/done-archive/parquet-export-import-evaluation.md)
  und alle zehn Sub-Docs (AP1-AP13) nach
  `docs/planning/done/`, weil die **Evaluierungsphase**
  abgeschlossen ist. Hauptplan
  bekommt eine `## Closure (2026-06-05)`-Sektion mit
  Commit-Tabelle aller 16 Parquet-Commits in
  chronologischer Reihenfolge (a9bf941e Plan-Refresh, 4
  Code-Spike-Commits AP3-AP6, 10 Sub-Doc-Commits AP7-AP13
  inkl. AP10-Befund-Rueckspiel, 1 Stakeholder-Entscheid
  e7f3f714), fuenf verbleibenden Pre-Implementation-
  Aufgaben (Engineering-Goal, Native-Image-Smoketest,
  Sealed-`rg`-Sweep, Branch-Anlage, erster Implementierungs-
  Commit) und Folge-Threads. Cross-Verweise auf den alten
  `in-progress/`-Pfad sind in CHANGELOG, Spec, Sub-Docs,
  AP3-Spike-Tests und `gradle.properties` aktualisiert.
  **Wichtig:** die Closure deckt die Plan-Doc-/Spike-Phase
  ab, nicht den produktiven Code; der Spike unter
  `adapters/driven/formats-parquet/src/main/kotlin/.../spike/`
  bleibt, der produktive Pfad
  (`SeekableDataChunkReaderFactory`, `SeekableChunkSource`,
  `ParquetChunkReader`/`Writer`, `DataExportFormat.PARQUET`,
  CLI-`--format parquet`, Dependency-Hygiene, Hadoop-
  Footprint-Inventar fuer 1.0.0-Input) laeuft als
  Per-Feature-Umbrella
  [`parquet-productive-cut-a.md`](docs/planning/done-archive/parquet-productive-cut-a.md)
  unter `in-progress/`. Trigger sind vier Befunde aus
  Code-Sichtung 2026-06-06 (`DataExportFormat.kt:10` ohne
  `PARQUET`, `DataImportCommand.kt:41` `.choice("json", "yaml",
  "csv")`, transitives `org.apache.avro:avro:1.9.2` ueber
  Hadoop, restliche Hadoop-Transitive HDFS/YARN/Jersey/
  reload4j/Zookeeper/Netty). Der Umbrella deckt nach AP13 §8
  Cut A (Voll-Scope) ab.

### Added

- **S3-kompatibler Object-Storage fuer die MCP-Byte-Stores (S3.0..S3.6)**
  *(2026-06-09–2026-06-12)* — `mcp serve` kann Upload-Segmente und
  Artefakt-Bytes statt im lokalen State-Dir in einem S3-kompatiblen
  Object-Storage ablegen (AWS S3, SeaweedFS u. a.):
  - **Adapter**: neues Modul `adapters:driven:storage-s3` mit
    `S3ArtifactContentStore` + `S3UploadSegmentStore` (AWS SDK v2 mit
    `url-connection-client`; SHA-256-Idempotenz via `x-amz-meta-sha256`,
    Multipart-Pfad > 8 MiB, paginierte Listen-/Batch-Delete-Operationen,
    Checksums `WHEN_REQUIRED` fuer SeaweedFS-Kompatibilitaet).
  - **Konfiguration**: neue `artifacts`-Sektion der `.d-migrate.yaml`
    (`store: file|s3`; bei `s3`: `endpoint`/`bucket`/`region`/`prefix`/
    `pathStyle`). Credentials stehen NIE im YAML — sie kommen aus der
    Env (`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` via
    `DefaultCredentialsProviderChain`); Logs/stderr nennen endpoint und
    bucket, nie Credentials. Fehlende Sektion → file-backed (Default,
    Bestandsverhalten unveraendert).
  - **Wiring/Retention**: `artifacts.store: s3` selektiert die S3-Stores
    im `mcp serve`-Pfad; der file-basierte Orphan-Sweep fuer
    segments/artifacts wird uebersprungen (der lokale Assembly-Spool und
    sein Cleanup bleiben). Der geteilte S3-Client wird beim Shutdown
    ueber den neuen `McpRuntimeWiring.ownedResources`-Mechanismus
    geschlossen.
  - **Tests**: SeaweedFS-Vertragssuiten + Multipart-IT, Wiring-IT
    (YAML → Composition-Root → Round-Trip) und MCP-Protokoll-E2E mit der
    echten CLI als Subprozess (`McpS3SubprocessE2ETest`).
  - **Footprint**: Release-JAR +8,02 MiB (AWS SDK, nur
    `url-connection`-Transport — kein Netty/Apache aus dem SDK).

  ImpPlan mit Slice-Tabelle und Bewusst-nicht-Scope:
  [`ImpPlan-0.9.8-object-storage-s3.md`](docs/planning/done-archive/ImpPlan-0.9.8-object-storage-s3.md).

- **Parquet als Export-/Import-Format (Cut A, S0..S9b)**
  *(2026-06-06–2026-06-08)* — `d-migrate data export --format parquet`
  und `data import --format parquet` sind produktiv. Voller Vertrag
  (AP13 §8 „Cut A"):
  - **Reader/Writer**: `ParquetChunkReader`/`ParquetChunkWriter` +
    `ParquetSeekableDataChunkReaderFactory` im Modul
    `adapters:driven:formats-parquet`; neues `DataExportFormat.PARQUET`.
    Die `Default…Factory` bleibt Parquet-/Hadoop-frei (Contract-Branch).
  - **Bundle** (Multi-Tabelle): `manifest.yaml` + stabile Dateinamen
    (`ParquetManifestWriter`); **Single-File**: Footer-KV
    `d-migrate.manifest` mit Phase-1/2-Preflight.
  - **CLI**: `--format parquet` fuer Im- und Export,
    `CompositeDataChunkWriterFactory`-Wiring, Pfad-only/Stdin-Ablehnung;
    **Checkpoint/Resume** fuer Bundle und Single-File; **DuckDB-/Arrow-
    Interop** durch KV-Toleranz-Tests verifiziert.
  - Abgrenzung: **keine** Lakehouse-Formate (Iceberg/Delta/Hudi);
    Hadoop-Footprint-Minimierung ist 1.0.0-Folgeinput (S10a-Snapshot).

  Per-Feature-Umbrella mit vollstaendiger Sub-Slice-Tabelle:
  [`parquet-productive-cut-a.md`](docs/planning/done-archive/parquet-productive-cut-a.md)
  §3.4. Slice-Lead-Commits in chronologischer Reihenfolge (Headline-Commit
  pro Slice; vollstaendige Sub-Commit-Listen im Umbrella):

| Datum | Commit | Slice |
| ----- | ------ | ----- |
| 2026-06-06 | `9c840986` | S0 — ChunkSchema/ChunkColumnSchema/SchemaOrigin |
| 2026-06-06 | `7670a393` | S0b — JDBC→NeutralType-Mapping |
| 2026-06-06 | `40d7c551` | S2 — SeekableDataChunkReaderFactory-Port |
| 2026-06-06 | `2b5826d8` | S10a — Avro-Hygiene + Footprint-Inventar |
| 2026-06-06 | `0a992c0c` | S3 — ParquetChunkReader/Writer (produktiv) |
| 2026-06-06 | `9ba956ff` | S10b — Native-Image-Befund |
| 2026-06-06 | `97c74757` | S3b — ParquetManifestWriter + Bundle-Closure |
| 2026-06-06 | `28048ef2` | S4 — Single-File-Footer-KV (Phase 1/2) |
| 2026-06-06 | `24cbf4c5` | S5a — ParquetBundlePreflight + Resolver |
| 2026-06-06 | `4279c326` | S5b — ImportInput.ResolvedSingleFile |
| 2026-06-07 | `7759294d` | S6 — CLI-Wiring Im-/Export (+ 4 Folge-Commits) |
| 2026-06-07 | `34eea7ce` | S7 — End-to-End (+ Sub-Slices a–e) |
| 2026-06-07 | `df733244` | S8 — Checkpoint-Erweiterung Bundle/Single-File |
| 2026-06-08 | `7808968c` | S9a-0 — Exit-Code-Vertrag (AP12 §9) |
| 2026-06-08 | `31f1f6ef` | S9a — Bundle-Tests (+ 3 Folge-Commits) |
| 2026-06-08 | `c687b47c` | `--table-order`-Flag (S9a-Folge-Feature) |
| 2026-06-08 | `3306808e` | S9b-0 — Single-File-Format-Codes → Exit 4 |
| 2026-06-08 | `591493f3` | S9b — Single-File-Tests (+ Resume-Fix) |

- **`--table-order`-Flag fuer `data import`** *(2026-06-09)* —
  explizite Import-Reihenfolge fuer Directory-/Bundle-Quellen
  (kommagetrennt, analog `--tables`). Beim Ordering **authoritative**
  gegenueber dem `--schema`-FK-Topo-Sort: `--schema` validiert dann nur
  noch (Praezedenz `--table-order` > Schema-Topo-Sort > Discovery).
  Macht die Parquet-Bundle-Order-Codes (`BUNDLE_ORDER_DUPLICATE`/
  `BUNDLE_ORDER_UNKNOWN_TABLE`/`BUNDLE_ORDER_INCOMPLETE`, Exit 5)
  CLI-erreichbar. Usage-Fehler (`--table-order` auf stdin/single-file,
  Konflikt mit `--table`, kaputte Syntax) → Exit 2.
- **Parquet-Evaluierung — Stakeholder-Entscheid: Go fuer
  Cut B als 1.0.0** *(2026-06-05)* — alle fuenf offenen
  Punkte aus
  [`parquet-decision-template.md`](docs/planning/done-archive/parquet-decision-template.md)
  §6 sind beantwortet (durchgaengig nach AP13-Empfehlung):
  - **Release-Branch:** `feature/parquet-1.0` mit
    Schritt-fuer-Schritt-Commits, Merge in `develop` nach
    Schritt 9. Begruendung: Schritt 1 (Enum) ohne
    Schritt 6 (CLI-Wiring) hat keinen Funktionsnutzen.
  - **Distributions-Cut:** Parquet immer im Default-JAR
    (1.0/1.1). Eine separate Variante wird im 1.2-Cut
    zusammen mit Native-Image reevaluiert.
  - **DuckDB-/Arrow-Tests:** bleiben `testImplementation`
    plus CI-Smoke-Lauf (AP4/AP5/§11.4); kein Heraufstufen
    zu Pflicht-Tests.
  - **MCP-Server-Spiegelung:** nicht in 1.0.0; eine
    MCP-Exposition von Parquet-Bundle-Export/-Import wird
    fruehestens beim 1.1-Planning entschieden.
  - **Hadoop-API-Shim:** Entscheid „eigener Shim vs.
    `hadoop-common`-Subset pinnen" wird zusammen mit dem
    1.2-Cut anhand der Native-Image-Smoketest-Daten
    getroffen — kein harter Vorab-Termin, Datengrundlage
    zuerst.

  Damit ist die Bedingung „offene Punkte beantwortet" aus
  AP13 §7 erfuellt; verbleibende Pre-Implementation-Schritte
  (Engineering-Zeitbudget-Commit, Native-Image-Smoketest in
  AP12-Schritt 3 verankern, Sealed-`rg`-Sweep in
  PR-Checkliste) sind Engineering-Aufgaben beim
  Cut-B-Start, keine offenen Entscheidungsfragen. Plan-Doc
  und Sub-Docs wandern bei Cut-B-Start nach
  `docs/planning/done/`.

- **Parquet-Evaluierung — AP13 Entscheidungsvorlage**
  *(2026-06-05)* — neuer Sub-Doc
  [`parquet-decision-template.md`](docs/planning/done-archive/parquet-decision-template.md)
  synthetisiert die AP1-AP12-Evaluierung in eine Go/No-Go-
  Vorlage. **Damit ist die Plan-Doc-Phase abgeschlossen.**
  Inhalt:
  - Aufwandschaetzung pro AP12-Implementierungsschritt mit
    Bundle-/Single-File-Split (elf Tabellen-Eintraege —
    1, 2, 3, 3b neu fuer `ParquetManifestWriter` +
    `StreamingExporter`-Bundle-Closure, 4, 5a Bundle, 5b
    SingleFile, 6, 7, 8, 9a Bundle-Tests, 9b SingleFile-
    Tests). Voll-Scope: 29.5-47 PT netto, 38-65 PT brutto
    inkl. Review-Zyklen. **Cut B (Bundle-only): 20.5-32.5
    PT netto, 27-45 PT brutto**. Plus Folgekosten:
    5-15 PT Native-Image, 5-10 PT optionaler
    Hadoop-API-Shim, 2-3 PT Doku.
    Kalenderaufwand bei Vollzeit: Voll-Scope 8-15 Wochen,
    Cut B 5-9 Wochen.
  - Risiko-Gesamtbild in vier Gruppen:
    - Wahrscheinlich-und-aufwaendig: Native-Image-Reachability,
      Hadoop-Footprint im JAR, Sealed-Sweep-Vollstaendigkeit
      (mit konkreten Lueckenkategorien — `else`-Zweige,
      nicht-exhaustive `when` ohne Ausdruckszwang,
      Reflection-/Service-Loader, Test-Code; `rg`-Sweep ist
      Go-Bedingung, nicht durch `gradle assemble` ersetzbar).
    - Wahrscheinlich-und-billig: CSV-Flag-Skript-Bruch,
      Auto-Detection-Falle, pre-AP8-Checkpoint-Bruch.
    - Unwahrscheinlich-aber-teuer: parquet-java 1.18-Wechsel
      waehrend Umsetzung, CVE in parquet-hadoop.
    - Akzeptierte Restrisiken: semantischer Schema-Drift,
      Sealed-Modul-Lokalitaet, Single-File-Bundle-Manifest-
      Asymmetrie.
  - Drei gestaffelte Scope-Cuts mit klarer Empfehlung:
    - **Cut A (1.0.0 voller Vertrag)** — alle neun Schritte;
      maximaler Operator-Mehrwert, traegt die volle
      Single-File-Phase-1/2-Komplexitaet im 1.0-Risiko.
    - **Cut B (1.0.0 Bundle-Pilot, empfohlen)** —
      Bundle-Export inklusive `ParquetManifestWriter` +
      `StreamingExporter`-Bundle-Closure (Schritt 3b) +
      Bundle-Import + Bundle-Resume, Single-File scheitert
      mit `PARQUET_SINGLE_FILE_NOT_YET_SUPPORTED`;
      `--no-checkpoint` nicht eingefuehrt. Folge-Release
      1.1.0 (11-20 PT brutto) ergaenzt Single-File +
      `--no-checkpoint`; 1.2.0 (10-25 PT) den
      Native-Image-Cut + optionalen Hadoop-API-Shim.
    - **Cut C (1.0.0 Bundle ohne Resume)** — schnellster
      Pilot, aber Wertversprechen gegenueber
      `pg_dump | psql` zu duenn; verworfen.
  - Fuenf offene Punkte, die vor der Implementierung
    geklaert sein muessen (Release-Branch-Strategie,
    Gradle-Distributions-Cut, DuckDB-/Arrow-Test-Status in
    CI, MCP-Server-Spiegelung, Hadoop-API-Shim-Folge-
    Entscheidungsdatum).
  - Empfehlung: **Go mit Cut B als 1.0.0**, unter den
    Bedingungen: offene Punkte beantwortet,
    Engineering-Goal committed, Native-Image-Smoketest in
    Schritt 3 statt spaeter. Wenn No-Go: Plan-Doc und
    Sub-Docs unveraendert nach
    `docs/planning/done/`, Spike-Modul bleibt im Repo.

- **Parquet-Evaluierung — AP12 CLI- und Factory-Wiring-Skizze**
  *(2026-06-05)* — neuer Sub-Doc
  [`parquet-cli-wiring.md`](docs/planning/done-archive/parquet-cli-wiring.md)
  zieht alle AP1-AP11-Vorentscheidungen ins konkrete CLI- und
  Wiring-Bild. Implementierungsfertiges Skelett, das nach AP13
  (Entscheidungsvorlage) umgesetzt werden kann; trifft selbst
  **keine neuen Architekturentscheidungen**, verteilt die
  bestehenden auf die richtigen Code-Stellen.

  Inhalt (Sektionen 3-12):
  - `DataExportFormat.PARQUET` als additive Enum-Erweiterung;
    Clikt-`--format`-Choice automatisch ueber `entries.map
    { cliName }`.
  - Neue Parquet-Flags: `--manifest-sha256` (Export, opt-in,
    aktiviert AP7-Per-Datei-SHA-256), `--no-checkpoint`
    (Import, schaltet die Single-File-Content-Hash-
    Berechnung in `phase1` aus und unterdrueckt alle
    `FileCheckpointStore`-Schreiboperationen). Konflikt
    `--no-checkpoint` + `--resume <ref>` -> Exit 2
    (`CHECKPOINT_OPTIONS_CONFLICT`).
  - CSV-Flag-Ablehnung bei `--format parquet`
    (`CSV_FLAG_INVALID_FOR_PARQUET`): nur bei explizit
    gesetzten Flags, nicht bei Default-Werten. Dafuer wird
    `--csv-null-string` auf nullable `String?` umgestellt
    (kein `.default("")`-Maskerade-Problem); ein
    `null -> ""`-Mapping passiert an genau einer Stelle im
    `CsvChunkReader`/`Writer`-Konstruktor.
    `--encoding` silent-ignored (Skript-Kompatibilitaet,
    Schluesselargument: Shell-Skripte iterieren oft mehrere
    Formate mit globalem `--encoding`).
  - Format-Auto-Detection-Reihenfolge:
    `--format` > Verzeichnis-`manifest.yaml` >
    Endungs-Inferenz (`.parquet` neu in
    `EXTENSION_FORMAT_MAP`).
  - `--table`-Precedence fuer Single-File-Parquet aus
    AP11 §5.5 (Mismatch- und Required-Fehler).
  - Factory-Wiring: `DataImportWiring` instanziiert
    `DefaultDataChunkReaderFactory()` und
    `ParquetSeekableDataChunkReaderFactory()`,
    `StreamingImporter`-Constructor bekommt
    `seekableReaderFactory` als Pflichtparameter
    (AP10-Befund-Rueckspiel).
    `DefaultDataChunkWriterFactory` bekommt
    `PARQUET -> ParquetChunkWriter(...)`-Zweig.
  - Bundle-/Single-File-Adapter-CLI-Skelette:
    `ParquetBundlePreflight.run(bundleRoot,
    requireSha256Verify = !request.resume.isNullOrBlank())`
    + `ParquetBundleResolver` + `ParquetBundleAdapter.
    toResolvedBundle(resolver)`. Single-File bekommt einen
    **zwei-phasigen** Preflight: Phase 1 in `resolveRequest`
    (vor DB-Connect) liest Footer-KV und macht
    Tabellen-Resolution; Phase 2 in `runImport`
    (nach `connect()`) baut die `ChunkSchema` mit Live-
    Target-Schema-Zugriff. Neuer port-eigener Sealed-Subtyp
    `ImportInput.ResolvedSingleFile(table, path, schema,
    expectedSha256, resumeFingerprint)` analog zu
    AP9-`ResolvedBundle`; macht `ResolvedTableInput.Seekable`
    aus dem `ImportInputResolver` ableitbar ohne neuen
    Schema-Slot in `SchemaPreflightResult` /
    `ImportExecutionContext`.
  - Writer-Wiring: separate `ParquetChunkWriterFactory` im
    Parquet-Adapter-Modul plus `CompositeDataChunkWriterFactory`
    im CLI-Modul. `DefaultDataChunkWriterFactory` in
    `adapters:driven:formats` bleibt Hadoop-frei; der
    generische formats-Stack zieht den Parquet-Adapter nicht
    transitiv.
  - Checkpoint-Persistenz:
    `FileCheckpointStore.toMap`/`fromMap` serialisiert
    `operationSpecific` mit `kind`-Diskriminator
    (`parquet-bundle` -> `BundleCheckpointSpecifics`,
    `parquet-single-file` -> `SingleFileCheckpointSpecifics`).
    Kein Schema-Versionsbump (Feld optional,
    pre-AP8-Checkpoints fuer JSON/YAML/CSV bleiben
    funktional). `ImportCheckpointManager.validateManifest`
    bekommt zwei neue Validierungsmethoden
    (`validateBundleResume`, `validateSingleFileResume`),
    der heutige Pfad bleibt fuer `operationSpecific == null`
    erhalten — bricht aber bei Parquet-Bundle- bzw.
    Single-File-Resume strukturell mit
    `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT`.
    `buildCallbacks`/`saveManifest` reicht den
    Fingerprint bei jedem Update durch (AP9-Befund-
    Rueckspiel).
  - `InputContext` um `bundleExpectedSha256ByTable:
    Map<String, String?>?` und `singleFileContentSha256:
    String?` erweitert.
  - Vollstaendige Sealed-Sweep-Liste fuer fuenf
    Hierarchien (`ImportInput`, `SchemaOrigin`,
    `SeekableChunkSource`, `CheckpointOperationSpecifics`,
    `DataExportFormat`) mit konkretem `rg`-Suchmuster aus
    AP9 §7.8 plus bekannten Stellen
    (`ImportInputResolver`, `ImportPreflightValidator`
    Z. 105/113/118).
  - Exit-Code-Mapping fuer alle in AP7-AP11 definierten
    Fehlerklassen, gruppiert in Familien
    (Manifest/Bundle/Resume/SingleFile-Parquet/Reader/
    Checkpoint/CLI-Flag) mit Begruendung pro Exit-Code-Wahl.
  - Native-Image-Strategie: GraalVM-Reachability-Metadaten
    sind Pflicht; eigener Hadoop-API-Shim (parquet-libraries.md
    §8) ist erst nach GraalVM-Smoketest noetig — bis dahin
    `hadoop-common`+`hadoop-mapreduce-client-core` belassen.
    AP6-Befund `fs.file.impl.disable.cache=true` an genau
    einer Stelle (`ParquetHadoopConfigBuilder`).
  - Test-Strategie: sechs Pflicht-Familien (CLI-Preflight,
    Format-Resolver mit `--format json`+`manifest.yaml`-
    Vorrang, Resume-Akzeptanz, DuckDB-/Arrow-KV-Toleranz
    erweitert die AP4/AP5-Linien, Sealed-Sweep als
    Build-Zeit-Dokumentation).
  - Bindender Implementierungsplan in neun entkoppelten
    Schritten (Enum-Erweiterung > Port > Reader-/Writer-
    Implementation > Single-File-Adapter > Bundle-Adapter
    > CLI-Wiring > Resolver-Integration > Checkpoint >
    Tests). Big-Bang-Commit explizit abgelehnt.

  AP13 (Entscheidungsvorlage) entscheidet, welche der neun
  Implementierungs-Schritte im 1.x-Cut zwingend sind.

- **Parquet-Evaluierung — AP11 Single-File-Metadatenvertrag**
  *(2026-06-05)* — neuer Sub-Doc
  [`parquet-single-file-metadata.md`](docs/planning/done-archive/parquet-single-file-metadata.md)
  fixiert den letzten Vertragspunkt vor AP12/AP13. Inhalt:
  - Drei Optionen aus Hauptplan §6 verglichen
    (Footer-KV vs. Sidecar vs. Footer-only); bindende Wahl
    Option A — **Footer-Key-Value-Metadaten** mit Key
    `d-migrate.manifest`. parquet-java 1.17.1 unterstuetzt das
    verlaesslich via `withExtraMetaData`/`getKeyValueMetaData`
    (`parquet-libraries.md` §3.1).
  - Value-Format: UTF-8-YAML-Bytestrom als **strikte
    Teilmenge** des AP7-Bundle-Manifests
    (`parquet-manifest-format.md` §5). Genau ein
    `tables[]`-Eintrag, ohne `file` (Datei kennt sich
    selbst) und ohne `sha256` (Hash ueber den eigenen
    Bytestrom inkl. Hash-Feld waere zirkulaer).
  - Fremde Parquet-Dateien ohne `d-migrate.manifest`-Key
    bleiben **lesbar als best-effort**: Reader baut die
    `ChunkSchema` aus Footer-`MessageType` + Ziel-JDBC-
    Schema; CLI-Warnung weist auf moeglichen Decimal-/
    Temporal-Verlust hin. Damit kein Bruch mit Spark-/Hive-
    erzeugten Parquet-Dateien.
  - Sidecar (Option B) abgelehnt: bricht das Single-
    Artefakt-Versprechen und fuehrt zwei parallele
    Manifest-Vertraege ein. Footer-only (Option C) abgelehnt:
    verletzt AP2 §4.4 (Schema-vor-Chunk).
  - Stdin-Single-File-Import bleibt unzulaessig
    (`parquet-libraries.md` §7); CLI-Preflight wirft
    `PARQUET_STDIN_NOT_SUPPORTED` (AP12-Fehlercode).
  - Code-Konsequenzen: neue Klassen
    `ParquetSingleFileManifestWriter`/`Reader` plus
    `ParquetSingleFilePreflight` im Parquet-Adapter. Der
    Preflight ist die einzige Stelle mit Footer-Parsing —
    der Streaming-Layer (`adapters:driven:streaming`)
    bleibt parquet-frei und sieht nur die port-neutrale
    `ResolvedTableInput.Seekable(table, source, schema,
    expectedSha256)`-Variante (AP10 §5.4). Dadurch teilen
    Bundle- und Single-File-Pfad denselben
    `TableImporter`-Zweig.
  - Tabellennamens-Aufloesung mit klarer Precedence:
    `--table` gewinnt; bei vorhandenem Footer-KV +
    Mismatch -> `PARQUET_SINGLE_FILE_TABLE_MISMATCH`; bei
    `--table` fehlt + Footer-`tables[0].table` vorhanden
    -> Footer-Wert wird mit `stderr`-Note uebernommen;
    bei beidem fehlend -> `PARQUET_SINGLE_FILE_TABLE_REQUIRED`.
    Die heutige `--table`-Pflicht fuer JSON/YAML/CSV-
    Single-File bleibt unveraendert.
  - Resume fuer Single-File: AP8 §8.1 verbietet Resume
    ohne Datei-Hash. Da der Footer-KV keinen Hash tragen
    kann (zirkulaer ueber den eigenen Bytestrom), wird der
    SHA-256 ueber den **gesamten Dateibytestrom** im
    Preflight berechnet und im Checkpoint via neuer
    `SingleFileCheckpointSpecifics(bundleKind=
    "parquet-single-file", contentSha256, table) :
    CheckpointOperationSpecifics`-Variante persistiert.
    Mismatch beim Resume ->
    `PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT`.
  - `parquet-manifest-format.md` §5.2 Korrektur:
    `tables[].file` ist jetzt **bedingt** Pflicht — Pflicht
    im Bundle-Manifest, optional im Single-File-Footer-KV.
    Das macht das AP11-YAML zu einer konditionell strikten
    Teilmenge des Bundle-YAML (ein Parser-Pfad,
    Kontext-Validierungsregel statt zweites Schema).

  Implementierungscode folgt nach AP12.

- **Parquet-Evaluierung — AP10 Stream-vs-Datei-Portentscheidung**
  *(2026-06-05)* — neuer Sub-Doc
  [`parquet-port-shape.md`](docs/planning/done-archive/parquet-port-shape.md)
  hebt die Vorentscheidung aus `parquet-libraries.md` §7 in eine
  bindende Reader-Port-Skizze. Inhalt:
  - Neuer Port `SeekableDataChunkReaderFactory` in
    `hexagon:ports-read` parallel zur bestehenden
    `DataChunkReaderFactory` — bewusst Format-agnostisch
    (`PARQUET` heute, kuenftige seekable Formate ohne
    Vertragsbruch). Kein vereinheitlichter Port, weil das
    `InputStream`/`Path`-Variantenproblem dann den
    Temp-Spool-Pfad wiedereroeffnen oder das schwaechere Schema-
    Inferenz-Modell durchsetzen wuerde.
  - `SeekableChunkSource` als sealed interface mit konkretem
    `Local(path: Path)`-Subtyp; reine `InputStream`-Quellen sind
    bewusst nicht Teil der Hierarchie (kein impliziter
    Temp-Spool, `parquet-libraries.md` §7 Bullet 2).
  - `ChunkSchema` ist Pflichtparameter der `create(...)`-Signatur;
    der Reader vertraut dem Schema aus dem Bundle-Preflight und
    rekonstruiert nicht aus dem Datei-Footer.
  - Writer-Seite bleibt **unveraendert** stream-basiert; der
    Parquet-Writer wraps den `OutputStream` intern in einen
    `PositionOutputStream`/`OutputFile`-Adapter (stdout-tauglich).
    Kein neuer Writer-Port.
  - Default-Implementation `ParquetSeekableDataChunkReaderFactory`
    lebt im `adapters:driven:formats-parquet`-Modul; nutzt
    parquet-java 1.17.1 plus die Hadoop-API-via-LocalFileSystem-
    Befunde aus §5.1/§7/§8. Bewusst `public class` (kein
    `internal`), parallel zur Konvention von
    `DefaultDataChunkReaderFactory`, damit CLI/MCP die Factory
    direkt instanziieren koennen.
  - Minimaler Footer-vs-ChunkSchema-Konsistenzcheck im Reader
    (Spaltenanzahl + -namen) mit Fehler
    `BUNDLE_SCHEMA_PARQUET_MISMATCH`; schliesst die Luecke, die
    der opt-in AP7-Live-Hash nicht abdeckt. Vollstaendige
    Typgleichheits-Pruefung bewusst nicht — akzeptiertes
    Restrisiko (semantischer Drift).
  - Sealed-Erweiterungsregel explizit: neue
    `SeekableChunkSource`-Varianten kommen additiv ins
    Port-Modul `hexagon:ports-read` und brechen
    exhaustive `when`-Konsumenten bewusst. Externe Module
    koennen die Sealed-Hierarchie nicht selbst erweitern;
    das ist gewollt, weil ein offenes Interface den
    InputStream-/Temp-Spool-Pfad wiedereroeffnen wuerde.
  - Migrations-Analyse fuer sechs Module mit konkretem Sweep:
    `StreamingImporter`-Constructor (Z. 21-28) bekommt die
    zweite Factory-Referenz und reicht sie an den internen
    `TableImporter` durch — Pflichtparameter (kein `null`-
    Default), weil ein vergessenes Wiring bei `format=PARQUET`
    zur Laufzeit eine schlecht diagnostizierbare NPE waere.
    AP12 macht das Wiring.

  Implementierungscode folgt nach AP12.

- **Parquet-Evaluierung — AP9 Importpfad-Vertrag (bindende DTO-Wahl)**
  *(2026-06-05)* — neuer Sub-Doc
  [`parquet-import-input-dto.md`](docs/planning/done-archive/parquet-import-input-dto.md)
  hebt die AP7-/AP8-Vorentscheidungen in die Implementierungs-
  Entscheidung. Inhalt:
  - Bindung auf neuen Sealed-Subtyp `ImportInput.ResolvedBundle`
    statt Magic-Field-Erweiterung von `ImportInput.Directory`;
    Kotlin-Skelett mit `ResolvedBundleTableBinding(table, path,
    schema, expectedSha256)` und
    `BundleResumeFingerprint(manifestSha256, formatVersion,
    producerVersion, tableOrder)` in `hexagon:ports-write`.
    Bewusst Parquet-frei im Vertrag — der Port spricht nur
    „Bundle", Adapter befuellen format-spezifisch.
  - Konkrete `BundleCheckpointSpecifics :
    CheckpointOperationSpecifics`-Variante mit Pflichtfeld
    `bundleKind: String` (z.B. `KIND_PARQUET = "parquet-bundle"`)
    plus `fingerprint`. `bundleKind` ist der serialisierungs-
    stabile YAML-Diskriminator; unbekannte Werte fuehren in
    `fromMap` zu `CHECKPOINT_OPERATION_SPECIFICS_UNKNOWN_KIND`.
  - Fingerprint-Konsistenz: `BundleResumeFingerprint.tableOrder`
    wird im Translator aus `bindings.map { it.table }`
    abgeleitet (nicht als separater Parameter angenommen);
    damit koennen DTO-Order und Fingerprint-Order strukturell
    nicht divergieren.
  - Resume-Enforcement sitzt im `ImportCheckpointManager`, nicht
    im DTO. `expectedSha256: String?` bleibt nullable
    (Initial-Lauf ohne `--manifest-sha256` zulaessig); im
    `--resume` prueft der Manager pro Tabelle und faellt sonst
    auf `BUNDLE_RESUME_REQUIRES_FILE_HASHES`.
  - `ImportCheckpointManager.buildCallbacks` / `saveManifest()`
    muss `BundleCheckpointSpecifics` bei **jedem** Update
    fortschreiben (heute baut `saveManifest()` ein neues
    Manifest ohne `operationSpecific`-Feld, was den im
    Initial-Lauf geschriebenen Fingerprint sofort
    ueberschriebe). Fingerprint ist eine Bundle-Lauf-
    Invariante.
  - Sealed-Bruch ehrlich modelliert: `ImportPreflightValidator`
    hat drei exhaustive `when (input)` (Z. 105-122,
    `effectiveTables`/`inputTopology`/`inputPath`); AP12 muss
    sie alle ergaenzen. DTO traegt deshalb `bundleRoot: Path`,
    damit `inputPath` ohne externe Lookups ableitbar bleibt.
    Sweep-Befehl als robustes `rg --type kotlin -n 'is
    ImportInput\.' .` plus `rg --type kotlin -n 'when \(' . |
    grep -F 'ImportInput'`; einfaches
    `git grep "when.*ImportInput"` verfehlt Stellen wie
    `when (val input = ctx.input) { is ImportInput.Stdin -> ... }`.
  - Resume-Enforcement konkret verdrahtet:
    `InputContext` (ImportRunnerTypes.kt:127) bekommt
    `bundleExpectedSha256ByTable: Map<String, String?>?` — null
    fuer JSON/YAML/CSV, Map mit Per-Tabellen-Hash-Status fuer
    Bundles. `ImportCheckpointManager.validateManifest`
    (Z. 93-124) erweitert um drei Bundle-Pruefungen:
    `BundleCheckpointSpecifics`-Vorhandensein, Fingerprint-
    Gleichheit, Per-Tabelle-Hash-Vorhandensein im Resume-Scope
    (= IN_PROGRESS-Tabellen aus `tableSlices`). CLI-Resolver
    aktiviert den AP7-Preflight-SHA256-Check beim `--resume`
    zwangsweise (`requireSha256Verify = true`).
  - AP7 `ResolvedParquetBundle`-DTO im Sub-Doc §4.4 mit
    konkretem Mindestumfang skizziert
    (`bundleRoot`, `manifestSha256`, `formatVersion`,
    `producerVersion`, `schemaSource`, `tables`); diese
    Adapter-internen Felder waren vorher implizit und werden
    jetzt explizit referenzierbar gemacht (Translator-
    Skelett §4.3 nutzt sie direkt).
  - Adapter-Translator `ParquetBundleAdapter` im
    `adapters:driven:formats-parquet`-Modul: einzige Stelle,
    an der adapter-interne Manifest-Begriffe
    (`ResolvedParquetBundle`, `schemaSource`, ...) auf
    Port-Begriffe abgebildet werden.
  - **Begleitentscheidung 1** (AP2-Erweiterung): `SchemaOrigin`-
    Enum in
    [`parquet-schema-source.md`](docs/planning/done-archive/parquet-schema-source.md)
    §4.4 um `MANIFEST_FALLBACK` erweitert (additiv,
    `hexagon:ports-common`). Semantisch verschieden von `MERGED`
    („aus mehreren Quellen kombiniert"); `MANIFEST_FALLBACK`
    markiert best-effort-Manifest-Typen ohne SchemaReader-/
    JDBC-Provenance.
  - **Begleitentscheidung 2** (AP1-Aufraeumung):
    [`parquet-libraries.md`](docs/planning/done-archive/parquet-libraries.md)
    §7.1 Bullet 4 finalisiert. Die urspruengliche AP1-Aussage
    „`ImportInput.Directory` wird nicht ersetzt" ist praezisiert,
    nicht verworfen: `Directory` bleibt fuer JSON/YAML/CSV und
    fuer Single-File-Bundles (AP11) erhalten, Multi-Table-
    Bundles mit verpflichtendem `manifest.yaml` laufen ueber
    `ImportInput.ResolvedBundle`. Die Korrektur-Notiz aus dem
    AP8-Commit ist damit obsolet und entfernt.
  - Migrations-/Impact-Analyse fuer sieben Module
    (`hexagon:ports-write`, `hexagon:ports-common`,
    `adapters:driven:streaming`,
    `adapters:driven:streaming/checkpoint`,
    `hexagon:application`, `adapters:driven:formats-parquet`,
    `adapters:driving:cli`). JSON/YAML/CSV-Pfade bleiben
    funktional unveraendert (additives `is ResolvedBundle`).

  Implementierungscode folgt nach AP12.

- **Parquet-Evaluierung — AP8 manifestgebundene Directory-Import-
  Aufloesung** *(2026-06-05)* — neuer Sub-Doc
  [`parquet-directory-import.md`](docs/planning/done-archive/parquet-directory-import.md)
  als Resolver-Skizze fuer Bundle-Importe. Inhalt:
  - Aufloesungsmodell mit `ParquetBundleResolver`: Wrapper um
    `ResolvedParquetBundle`, `resolve()` liefert eine
    `List<ParquetTableBinding>` (kein one-shot `Iterable`, weil der
    `StreamingImporter` `discoveredInputs.size` vor der
    Per-Tabellen-Iteration braucht).
  - Tabellenordnung primaer aus Manifest-`tables[]`-Reihenfolge,
    optional von `tableOrder` aus `ImportInput` explizit
    ueberschrieben (User > Producer). `tableFilter` ist Teilmenge
    gegen Manifest-Bestand.
  - `ChunkSchema`-Konstruktion pro Tabelle in drei Stufen
    (Manifest-`neutralType` -> JDBC-Hint-Tupel -> `sqlTypeName`-
    Heuristik -> `BUNDLE_SCHEMA_UNRESOLVED`); JDBC-Hints fliessen
    NUR als Eingaben in die NeutralType-Ableitung ein und werden
    nicht im neutralen `ChunkColumnSchema` persistiert (konsistent
    mit `parquet-schema-source.md` §4.4).
  - Strikte Mid-stream-Fehlerbehandlung
    (`BUNDLE_TABLE_IMPORT_FAILED`): kein Skip-Modus, kein
    Bundle-weiter Rollback (Atomic-Preserve ist Ziel-DB-Sache).
  - Resume-Bedingung: erfordert manifestseitige Datei-Hashes
    (`tables[].sha256`) fuer jede Tabelle im Resume-Scope; sonst
    `BUNDLE_RESUME_REQUIRES_FILE_HASHES`. Begruendung: AP7 §7.1
    laesst SHA-256 optional, ohne Hash kann eine Dateiaenderung
    nicht erkannt werden. Resume macht zwei klar getrennte
    Pruefungen: (P1) AP7-Preflight-SHA256 zwangsweise aktiv —
    live-berechneter Datei-Digest gegen Manifest-`tables[].sha256`
    erkennt Datei-Aenderungen als `MANIFEST_SHA256_MISMATCH`;
    (P2) Checkpoint-Fingerprint (`manifestSha256`, `formatVersion`,
    `producerVersion`, effektive `tableOrder`) erkennt Manifest-
    Edits seit Checkpoint. `fileSha256ByTable` ist bewusst nicht
    Teil des Checkpoints (redundant zu `manifestSha256`).
  - Format-Autodetection: `data import --source <dir>` ohne
    `--format` routet bei vorhandenem `manifest.yaml` automatisch
    auf Parquet (`resolveFormat`-Hook vor
    `inferFormatFromExtension`). Vorbedingung:
    `DataExportFormat.PARQUET` muss eingefuehrt werden — heute
    kennt der Enum nur JSON/YAML/CSV (AP12-Vorgriff, im Sub-Doc
    explizit gemacht).
  - Code-Konsequenzen: neuer port-eigener Subtyp
    `ImportInput.ResolvedBundle` mit
    `ResolvedBundleTableBinding(table, path, schema, expectedSha256)`
    und `BundleResumeFingerprint` (`manifestSha256`,
    `formatVersion`, `producerVersion`, `tableOrder`) — bewusst
    Parquet-frei, damit `hexagon:ports-write` nicht von
    `adapters:driven:formats-parquet` abhaengt. Adapter haelt sein
    reichhaltigeres `ResolvedParquetBundle` intern und uebersetzt
    am Port-Eintritt.
  - Checkpoint-Persistenz (§10.5): neue konkrete Implementierung
    `BundleCheckpointSpecifics : CheckpointOperationSpecifics`
    wird im `hexagon:ports-write`-Modul gebraucht — bewusst
    Parquet-frei im Klassennamen, konsistent zur §10.1-
    Port-Sauberkeit; der YAML-`kind`-Diskriminator
    `"parquet-bundle"` traegt den Adapter-Bezug.
    `FileCheckpointStore` muss `operationSpecific` mit-serialisieren
    (heute ignoriert), `ImportCheckpointManager.writeInitialManifest`
    nimmt einen optionalen `BundleResumeFingerprint`-Parameter
    durch — beides AP12-Wiring. Schema-Bump auf
    `CURRENT_SCHEMA_VERSION=3` ist nicht noetig, weil das Feld
    optional bleibt und pre-AP8-Checkpoints sich beim
    Bundle-Resume strukturell mit dem neuen Code
    `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` abmelden
    (eigener Code, nicht `BUNDLE_RESUME_REQUIRES_FILE_HASHES` —
    das beschreibt das fehlende `tables[].sha256`, eine andere
    Konstellation).
  - `SchemaOrigin`-Mapping: AP2 §4.4 traegt heute nur
    `JDBC_METADATA`/`SCHEMA_READER`/`MERGED`. Fuer
    `schemaSource = manifest-fallback` ist `MERGED` semantisch
    falsch (es bedeutet „aus mehreren Quellen kombiniert", nicht
    „best-effort"). AP8 schlaegt eine vierte Variante
    `MANIFEST_FALLBACK` vor; AP2 wird beim AP9-Abschluss
    nachgezogen.

  Bindende DTO-Wahl ist AP9; Implementierungscode folgt nach AP12.

- **Parquet-Evaluierung — AP1 §7.1 ImportInput.Directory-Aussage
  als ueberstimmt markiert** *(2026-06-05)* —
  [`parquet-libraries.md`](docs/planning/done-archive/parquet-libraries.md)
  §7.1 Bullet 4 traegt jetzt einen Korrektur-Hinweis: AP7 §10.2
  und AP8 §10.1 empfehlen inzwischen einen neuen
  `ImportInput.ResolvedBundle`-Subtyp statt der urspruenglich
  geplanten Beibehaltung von `ImportInput.Directory`. Endgueltiger
  Wortlaut wird beim AP9-Abschluss aufgeraeumt.

- **Parquet-Evaluierung — AP7 Manifest-Format und Import-Preflight**
  *(2026-06-05)* — neuer Sub-Doc
  [`parquet-manifest-format.md`](docs/planning/done-archive/parquet-manifest-format.md)
  als architektonische Skizze fuer Multi-Table-/Directory-Bundle-
  Exporte. Inhalt:
  - YAML-Schema von `manifest.yaml`: Pflichtfelder `formatVersion`,
    `producer`, `producerVersion`, `exportedAt`, `schemaSource`,
    `tables[].{table,file,columns}`; optionale Felder `rowCount`,
    `sha256`, plus `NeutralType`-YAML-Repraesentation mit
    `kind`-Diskriminator.
  - Tabelle-zu-Datei-Aufloesung mit Default-Konvention
    `<schema>.<table>.parquet` und Kollisionsschutz K1-K5 (doppelte
    Tabelle/Datei, Pfadausbruch, fehlende und unreferenzierte
    Dateien — letztere verhindern stillen Import laut Hauptplan §6).
  - SHA-256-Verfahren als Opt-in: gehasht wird der fertige Parquet-
    Bytestrom nach Writer-`close()`, Lowercase-Hex, kein Praefix.
    Verifikation einmal im Preflight, nicht waehrend des Streamings.
  - Formatversionierung `MAJOR.MINOR` mit Start bei `1.0`:
    Major-Bump = inkompatibel und vom Reader abgelehnt; Minor-Bump
    = additiv und mit Warnung tolerierbar.
  - Preflight-Vertrag mit elf stabilen Fehlerklassen
    (`MANIFEST_NOT_FOUND`, `MANIFEST_PARSE_ERROR`,
    `MANIFEST_VERSION_INCOMPATIBLE`, `MANIFEST_FIELD_MISSING/INVALID`,
    `MANIFEST_TABLE_DUPLICATE`, `MANIFEST_FILE_DUPLICATE/OUTSIDE_BUNDLE/MISSING/UNREFERENCED`,
    `MANIFEST_SHA256_MISMATCH`).
  - Konsequenzen: neue Klassen `ParquetBundleManifest`,
    `ParquetManifestReader/Writer`, `ParquetBundlePreflight` im
    `adapters:driven:formats-parquet`-Modul; Vorzugsoption fuer AP9
    ist neuer `ResolvedParquetBundleInput`-Subtyp statt
    Magic-Field-Erweiterung an `ImportInput.Directory`.

  AP7-Vorentscheidungen werden in AP8 (Directory-Aufloesung) und AP9
  (Importpfad-DTO) bestaetigt; Implementierungscode folgt nach AP12.

- **Parquet-Evaluierung — AP6 Importpfad-Spike** *(2026-06-05)* —
  neuer Test
  [`ParquetSpikeImportPathTest`](adapters/driven/formats-parquet/src/test/kotlin/dev/dmigrate/format/parquet/spike/ParquetSpikeImportPathTest.kt)
  und drei neue `ParquetSpike`-Funktionen demonstrieren am Spike-
  Output, dass der Footer als Schema-Quelle reicht und dass sich
  Spike-Rows ueber das neutrale `DataChunk`-Modell leiten lassen:
  - `readSchemaFromFooter` mappt `MessageType`-Felder aus dem
    `ParquetFileReader`-Footer zu
    `dev.dmigrate.core.data.ColumnDescriptor`-Tupeln
    (name/nullable/`sqlTypeName` als opaker Parquet-Originaltyp).
  - `readAsChunk` kombiniert das mit `ParquetReader`-Rows zu einem
    `dev.dmigrate.core.data.DataChunk` (chunkIndex=0; Multi-Chunk-
    Akkumulation ist Sache des produktiven Adapters).
  - `writeWithoutCrc` demonstriert die `.crc`-Sidecar-Mitigation
    aus `parquet-libraries.md` §7 Variante (a) via
    `fs.file.impl=RawLocalFileSystem`. AP6-Befund: in Hadoop 3.4.1
    ist `fs.file.impl.disable.cache=true` als zweite Direktive
    noetig, sonst haelt der `FileSystem`-Service-Loader-Cache die
    `LocalFileSystem`-Default-Instanz vor und der Sidecar bleibt.

- **Parquet-Evaluierung — AP5 Arrow-Metadateninspektion**
  *(2026-06-05)* — neuer Test
  [`ParquetSpikeArrowInspectTest`](adapters/driven/formats-parquet/src/test/kotlin/dev/dmigrate/format/parquet/spike/ParquetSpikeArrowInspectTest.kt)
  im Spike-Modul. `parquet-arrow` 1.17.1 (ausschliesslich
  `testImplementation`, an `parquetVersion` gekoppelt; bewusst nicht
  `arrow-dataset` — vgl. `parquet-libraries.md` §3.4: JNI-frei,
  kein produktiver Arrow-Pfad) liest den Datei-Footer ueber
  `ParquetFileReader`, konvertiert das `MessageType` via
  `SchemaConverter#fromParquet` zu einem Arrow `Schema` und der Test
  verifiziert fuer die drei Spike-Spalten `Int(32, signed)`,
  `Utf8`, `Bool` plus `isNullable=false`. Damit ist
  Akzeptanzkriterium §7 Bullet 2 ("Beispiel-Export kann mit
  Arrow-Werkzeugen oder Arrow-Java-Metadaten inspiziert werden")
  abgehakt.

- **Parquet-Evaluierung — AP4 DuckDB-Akzeptanzlauf** *(2026-06-05)* —
  neuer Test
  [`ParquetSpikeDuckDbReadTest`](adapters/driven/formats-parquet/src/test/kotlin/dev/dmigrate/format/parquet/spike/ParquetSpikeDuckDbReadTest.kt)
  im Spike-Modul. DuckDB JDBC 1.5.3.0 (ausschliesslich
  `testImplementation`, kein produktiver Pfad — vgl.
  `parquet-libraries.md` §3.5) liest den GZIP-komprimierten
  Spike-Output via `SELECT * FROM read_parquet(?)`, verifiziert den
  Round-Trip aller drei Zeilen und meldet die Spalten als
  `INTEGER`/`VARCHAR`/`BOOLEAN`. Damit ist Akzeptanzkriterium §7
  Bullet 1 ("Beispiel-Export kann mit DuckDB gelesen werden") und
  fuer das Spike-Schemafragment implizit Bullet 3
  (Round-Trip-Typen) abgehakt. Hadoop bleibt bewusst auf 3.4.1:
  parquet-hadoop 1.17.1 ist gegen Hadoop 3.3.0 (provided)
  kompiliert, 3.5.0 ist ungetestete Kombination und lohnt sich
  erst mit dem 1.18-Wechsel.

### Changed

- **Parquet-Evaluierung — `.crc`-Sidecar-Mitigation in §7
  praezisiert** *(2026-06-05)* — AP6-Befund:
  [`parquet-libraries.md`](docs/planning/done-archive/parquet-libraries.md)
  §7 Bullet zum `.crc`-Sidecar ergaenzt. In Hadoop 3.4.1 reicht
  `fs.file.impl=RawLocalFileSystem` allein nicht; ohne
  `fs.file.impl.disable.cache=true` bedient der
  `FileSystem`-Service-Loader-Cache den Writer aus einer
  vorinstanziierten `LocalFileSystem` und der `.crc`-Sidecar bleibt.

- **Parquet-Evaluierung — `iceberg-parquet` als bewusst
  ausgeschlossener Kandidat dokumentiert** *(2026-06-05)* —
  [`parquet-libraries.md`](docs/planning/done-archive/parquet-libraries.md)
  §3.6 neu: `org.apache.iceberg:iceberg-parquet` ist ein Adapter
  zwischen Iceberg-Tabellen und Parquet-Dateien (nutzt intern
  `parquet-java`), nicht ein eigener Writer/Reader. Strukturell
  ausgeschlossen, weil Iceberg/Delta/Hudi laut Hauptplan §3.2
  Nicht-Scope und ein Lakehouse-Adapter laut §4 explizit in einen
  spaeteren Folge-Schritt verschoben ist; nicht in die
  Bewertungsmatrix §4 aufgenommen, weil der Ausschluss strukturell
  und nicht kriteriumsgetrieben ist. Natuerlicher Hauptkandidat
  fuer den spaeteren Lakehouse-Folgeplan.

- **Parquet-Evaluierung — AP3-Spike-Befunde zurueckgespielt**
  *(2026-06-05)* — die drei Befunde aus dem AP3-Round-Trip-Spike
  (Commit `3b051ec`) sind aus dem Status-Header der Plan-Doc in die
  AP1-Bibliothekssichtung verschoben und damit final dokumentiert:
  - [`parquet-libraries.md`](docs/planning/done-archive/parquet-libraries.md)
    §5.1 (neu) — Hadoop-API-Kanal in 1.17.1 praezisiert
    (Hadoop-`Path`+`Configuration`+`LocalFileSystem` rein NIO; die
    `PlainParquetConfiguration`-/`LocalOutputFile`-Pfade kommen erst
    mit 1.18+).
  - §7 — `.crc`-Sidecar von Hadoop-`LocalFileSystem` als
    Writer-Folgeentscheidung dokumentiert
    (`RawLocalFileSystem` vs. aktives Aufraeumen; Stdout-Weg ueber
    `PositionOutputStream` davon nicht betroffen).
  - §8 — `hadoop-mapreduce-client-core:3.4.1` (mit denselben
    Exclusions wie `hadoop-common`) als Reader-Compile-Dependency
    nachgezogen, weil `ParquetReader.builder` ueber
    `ParquetInputFormat extends FileInputFormat` MapReduce-Klassen
    laedt.
  - [`parquet-export-import-evaluation.md`](docs/planning/done-archive/parquet-export-import-evaluation.md)
    §8 Arbeitspaket 3 markiert AP3 als erledigt; Status-Block auf
    Pointer nach `parquet-libraries.md` reduziert. Naechste
    Arbeitspakete: AP4 (DuckDB-Akzeptanzlauf), AP5 (Arrow-Inspektion).

### Added

- **BI-Demo Compose-Stack** *(2026-06-04)* — vollstaendige
  reproduzierbare Demo-Umgebung unter `examples/bi-demo/`, die
  `d-migrate` in einen komponierbaren Analytics-Stack einbettet.
  Sub-Slices BD.1-BD.5 nach Plan
  [`docs/planning/done-archive/bi-demo-compose.md`](docs/planning/done-archive/bi-demo-compose.md):

  - **BD.1** — Compose-Skeleton mit fuenf Services: Postgres
    17.10, SeaweedFS 4.31 (S3-API-Server), `seaweed-config` +
    `seaweed-init`-One-Shots (bash-Parameter-Expansion fuer
    JSON-Safety der gerenderten `s3.json`), AWS-CLI 2.34.61 als
    `aws-tools`-Service (Entrypoint-Wrapper mit
    `--endpoint-url`-Default). Object-Storage von MinIO auf
    SeaweedFS umgestellt (MinIO Community Edition 2025-Q3
    archiviert), S3-Client von `minio/mc` auf `amazon/aws-cli`.
    Healthcheck-Vertraege via `jq -s`-State-Pinnung (Compose
    v2.24+ JSONL-Output-Konvention).
  - **BD.2** — Demo-Schema mit fuenf Tabellen
    (customers/products/orders/order_items/events;
    50/30/500/1500/10000 Zeilen). Deterministischer Seed via
    `setseed(0.42)` + `\set demo_start_date` +
    `SET timezone='UTC'` +
    `SET max_parallel_workers_per_gather=0`. Idempotenz
    empirisch verifiziert via
    `pg_dump | grep -v restrict | sha256sum`-Hash-Vergleich.
  - **BD.3** — Metabase v0.55.24.1 als BI-Frontend mit
    persistenter H2-Konfiguration im Named Volume; README
    dokumentiert Erstkonfiguration + drei Beispiel-Fragen
    (Umsatz/Tag, Bestellungen/Status, Top-Kunden).
  - **BD.4** — `dmigrate`-Compose-Service (Image `d-migrate:dev`,
    Profile=tools) +
    [`examples/bi-demo/.d-migrate.yaml`](examples/bi-demo/.d-migrate.yaml)
    mit zwei Named-Connections (Host-CLI + Container-CLI).
    End-to-End-Workflow reverse/profile/generate gegen
    Demo-Postgres + `aws s3 cp` zum S3-Bucket.
  - **BD.5** —
    [`examples/bi-demo/scripts/smoke.sh`](examples/bi-demo/scripts/smoke.sh)
    fuer End-to-End-Smoke; Repo-Root-Makefile-Targets
    `bi-demo-{env,pull,up,down,purge,smoke}` kapseln den
    Compose-Pfad; optionaler
    [`.github/workflows/bi-demo-smoke.yml`](.github/workflows/bi-demo-smoke.yml)
    (Best-Effort, `continue-on-error: true`).

  Verifiziert gegen Compose v5.1.4: postgres healthy,
  seaweed-config/init exited(0), Metabase `/api/health` =
  `{"status":"ok"}`, alle fuenf Tabellen reversed + profiled +
  als DDL gerendert, fuenf BD.4-Warning-Codes im Profil-Report
  (`CONTAINS_EMPTY_STRINGS`, `CONTAINS_BLANK_STRINGS`,
  `POSSIBLE_PLACEHOLDER_VALUES`, `LOW_CARDINALITY`,
  `DUPLICATE_VALUES`) + `numericStats.max=99999.99`-Outlier.

### Fixed

- **`ProfileReportWriter` JSON+YAML-Serializer-Bugs** *(2026-06-04)*
  — zwei Befunde aus dem BD.4-Smoke (Stand 0.9.8-SNAPSHOT, in
  BD.5-Review behoben):
  - `renderTargetCompatibilityJson` haengte pro Feld einen
    Extra-Quote an (`"INTEGER""`, `"50""`) durch fehlerhafte
    Raw-String-Verkettung (5-fach-`"""`-Pattern, das Kotlin als
    `"""`-open + content + `"""`-close + trail-`"` parst).
    Rewrite mit klassischen Strings und `jsonStr()`-Helfer
    eliminiert die Mehrdeutigkeit.
  - `yamlStr` / `needsYamlQuoting` quotierte leere und
    whitespace-only Strings nicht (`exampleInvalidValues:
    [, customer10@..., ...]`), YAML-Parser brachen.
    `isBlank()` und Edge-Whitespace-Check erzwingen jetzt das
    Quoting.

  Zwei neue Tests in `ProfileReportWriterTest` parsen die
  gerenderten Outputs mit
  `com.fasterxml.jackson.databind.ObjectMapper` bzw.
  `com.fasterxml.jackson.dataformat.yaml.YAMLMapper` — vorher
  liefen nur `shouldContain`-Substring-Checks, die den Bug nicht
  fingen.

### Changed

- **`scripts/verify-doc-refs.sh`** *(2026-06-04)* — der awk-Filter
  strippt jetzt Single-Backtick-Inline-Code-Spans bevor das
  Markdown-Link-Pattern matched. Damit faengt der Doc-Link-Check
  keine Regex-/Code-Beispiele mehr ein, die `](`-Patterns
  innerhalb von Backticks enthalten (z. B. Kotlin-Raw-String-
  Regex in `docs/planning/next/trino.md:461`).

## [0.9.7] - 2026-06-02

### Added

- **0.9.7 Atomic-Sequence-Preserve Phasen A + B + C + D + E** *(2026-06-01)* —
  Schließt die Race zwischen `SequencePreserveStage`-Probe und dem
  späteren `setval`/`UPDATE` während des Live-`schema migrate`-Laufs.
  Bisher las die Stage `current_value` in einer eigenen Connection,
  ließ den Renderer einen Restore-Statement-Block bauen und führte
  diesen außerhalb jeder Sperre aus — eine gleichzeitig laufende App-
  `nextval`-Sequenz konnte zwischen Probe und Restore eine Lücke
  reißen. Der neue Pfad faltet Probe + Restore + die geschützten
  DDL-Statements in eine einzige Transaktion unter Per-Dialect-Lock.

  **Phase A — Datenmodell + Ports** *(Commit `174c3891`)*: Neue
  Operation `AlterSequenceCurrentValue` in `core:diff`, neue Sealed-
  Hierarchie `AtomicSequencePreserveBatch` /
  `AtomicSequencePreserveRequest` /
  `AtomicSequencePreserveResult` in
  `hexagon:ports-execute`; Sentinel-Konstante
  `ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE = 0L` markiert den noch
  unbekannten Probe-Wert in plan-only-Artefakten.
  `SequenceCapability` lernt die Felder `supportsAtomicPreserve`,
  `supportsAtomicPreserveAllInPlan` und
  `transactionalProtectedSequenceOperations` (in Phase A nur
  Datenmodell, Defaults bleiben `false` bzw. leer).

  **Phase B — Per-Dialect-Executor** *(Commits `dc6d2ad6`,
  `24eb6e17`, `e882bcb1`)*: Drei neue
  `AtomicSequencePreserveExecutor`-Implementierungen, deterministisch
  nach `SequenceObjectRef.name` sortiert:
  - PostgreSQL: `pg_advisory_xact_lock(hashtext(seq_name))` pro
    Sequenz; Probe + protected statements + `setval` in einer
    `BEGIN`/`COMMIT`-Klammer.
  - MySQL: `SELECT … FOR UPDATE` auf der `dmg_sequences`-Helper-Row
    mit `MAX_EXECUTION_TIME`-Exempt-Pfad für SLEEP/BENCHMARK
    (Driver-Quirk).
  - SQLite: `BEGIN IMMEDIATE` + xerial-spezifischer
    `setQueryTimeout`-Lock-Wait; Hikari-Pool umgangen, weil die
    xerial-Connection-Pool-Interaktion den Lock auf eine andere
    Connection wandern lässt.

  **Phase C — Stage- + Executor-Integration** *(Commits `833d1796`,
  `1c09147d`, `8c2e0a07`, `11d04e57`, `b4f548b0`, `39bcaa29`,
  `d72e572f`)*:
  - Neues `hexagon:ports-execute`-Modul + Sealed-Interface
    `ExecutableSegment` mit `PlainSqlSegment` und
    `AtomicPreserveSegment` (C.2).
  - `SegmentAwareMigrationExecutor` ersetzt den bisherigen direkten
    `JdbcMigrationExecutor`-Aufruf in `SchemaMigrateWiring` und
    routet pro Segment zum passenden Executor (C.3).
  - `SequenceCapabilityDefaults.supportsAtomicPreserve = true` für
    PG/MySQL/SQLite; `transactionalProtectedSequenceOperations`
    befüllt (C.4).
  - `SequencePreserveStage` baut den `AtomicSequencePreserveBatch`
    direkt im Stage-Pfad — kein Vorab-Probe-Call mehr — und reicht
    ihn über die Render-Pipeline an den Segment-Executor (C.1).
  - Neue Atomic-Preserve-Integration-Tests pro Dialekt in
    `:test:integration-postgresql/-mysql/-sqlite` (C.5); die
    bestehenden `SequencePreserveRaceTest`-Suites in
    `:test:integration-concurrency` wurden auf den atomaren Pfad
    migriert und beweisen jetzt „keine Race möglich" statt
    „Race-Toleranz".

  **Phase D — Cross-Plan-Deadlock-Beweis + AllInPlan-Flag-Flip**
  *(Commit folgt)*: drei Cross-Plan-Deadlock-Integration-Tests
  (`PostgresAtomicPreserveCrossPlanDeadlockTest`,
  `MysqlAtomicPreserveCrossPlanDeadlockTest`,
  `SqliteAtomicPreserveCrossPlanDeadlockTest`). PG + MySQL haben
  positiven (zwei parallele Runs committen) und negativen Smoke
  (manuell invertierte Lock-Order → SQLSTATE 40P01/55P03 bzw.
  ER_LOCK_DEADLOCK/WAIT_TIMEOUT). SQLite hat nur den positiven
  Beweis — die DB-weite RESERVED-Lock macht das Deadlock-Diamant
  konstruktiv unmöglich.
  `SequenceCapabilityDefaults.supportsAtomicPreserveAllInPlan = true`
  pro Dialekt; `SequencePreserveStage.allInPlanBlocker` emittiert
  `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`, wenn ein Plan ≥ 2 Preserve-
  Kandidaten enthält und das Flag pro Dialekt auf `false` steht
  (greift heute nur für synthetische Capability-Overrides — alle
  produktiven Dialekte sind nach D auf `true`). Finding #1 aus dem
  Code-Review (Contiguity-Crash in `SchemaMigrateExecutionStage`)
  ist mit demselben Slice gefixt: `segmentForExecute(...)` läuft
  jetzt innerhalb des try-Blocks, eine `IllegalStateException`
  mapped zu einem strukturierten `ExecutionTrace` mit
  `transactionRolledBack = true`, `sideEffectsPossible = false` und
  einem „Atomic-preserve plan shape invalid"-Hinweis statt
  unhandled durchzureichen. Finding #3 (Diagnostic-Überzählung in
  `SegmentAwareMigrationExecutor.mapAtomicResultToTrace`) ebenfalls
  gefixt: `statementsAttempted` zählt nach `Applied` nur noch die
  protected statements (Audit-Follow-ups bleiben im Plan-Artefakt,
  inflationieren aber den Trace-Counter nicht mehr).

  **Phase E — Docs-Sync** *(Commit folgt)*: dieser CHANGELOG-
  Eintrag selbst, User-Guide-Update („preserveCurrentValue atomar
  seit 0.9.7" inkl. dialekt-spezifischer App-`nextval`-Race-
  Aufklärung), KDoc-Sync auf `SequencePreserveStage` (Restrictions-
  Block), `SequenceCapability` (C.4-Verweis korrigiert + §3.2-Out-
  of-Scope-Block + AllInPlan-Update) und `SequenceCurrentValueProbe`
  (dead-code-Status-Header — Cleanup eigener Slice).

  **Carve-Outs:**
  - Cross-Database-Locks, App-side Retry/Backpressure und globaler
    Schema-Lock bleiben permanent out-of-scope (Plan-Doc §3.2).
  - Auf PostgreSQL bleibt `pg_advisory_xact_lock` per Design app-
    nextval-blind: der Lock schliesst die Race zwischen zwei
    Migrationen, nicht zwischen Migration und App. Plan-Doc §6
    Risiko Nr. 8 / User-Guide-Restrisiko dokumentieren dies.
  - 5 verbleibende Code-Review-Findings (Mittel/Niedrig: Sentinel-
    Render, --mysql/sqlite-named-sequences-Fallback, Race-Test-
    Assertion, LockTimeout-Decorator) in
    `docs/planning/in-progress/atomic-preserve-followups.md` als Post-
    Release-Slices erfasst.
  - Dead-Code-Cleanup der ungenutzten Probe-Adapter-Klassen ist
    eigener Folge-Slice.

  Plan-Doc:
  `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`.

- **0.9.7 SQLite-Sequence preserveCurrentValue Folge-Slice** *(2026-05-29)* —
  schliesst die `SequencePreserveStage`-Lucke fuer SQLite-Targets aus
  der 0.9.7-E.3-Erstscheibe. Neuer `SqliteSequenceCurrentValueProbe`-Adapter liest
  `dmg_sequences.next_value` live; `SequenceCurrentValueProbeRunner`
  dispatcht SQLite jetzt auf den neuen Adapter statt `NotApplicable`.
  `SequencePreserveStage` listet SQLite in der Allowlist und blockt
  ohne `--sqlite-named-sequences helper_table` mit
  `SEQUENCE_PRESERVE_OPT_IN_REQUIRED` (Classifier:
  `MANUAL_ACTION_REQUIRED`) BEVOR die Probe-Connection geoeffnet
  wird. `SqliteDiffSequenceOps.renderAlterSequenceCurrentValue`
  rendert Down jetzt deterministisch als `UPDATE dmg_sequences SET
  next_value = <restoreValue> WHERE name = <probeSequenceRef.name>`;
  ein fehlender `restoreValue` (CreateSequence ohne deterministischen
  Vorzustand) surfaced als
  `SQLITE_SEQUENCE_CURRENT_VALUE_DOWN_ROLLBACK_IMPOSSIBLE`-Skip statt
  als stillem No-Op. `SequenceCapabilityDefaults.SQLite.supportsCurrentValuePreserve`
  von `false` auf `true` geflippt. Neue `--sqlite-named-sequences`-
  Option auf `schema migrate` (parallel zu `schema generate`); wird
  durch `SchemaMigrateRenderPipeline` als
  `DdlDialectContext.Sqlite.namedSequenceMode` an die Renderer
  durchgereicht.

  Carve-out: Atomare Probe + Setval unter Lock bleibt out-of-scope
  (siehe Plan-Doc §6 Risiken); langlaufende Apps muessen den
  Schreibverkehr vor `--execute` weiterhin manuell anhalten.

  Plan-Doc:
  `docs/planning/done-archive/ImpPlan-0.9.7-sqlite-sequence-preserve-current-value.md`.

- **0.9.7 SQLite-Sequence-Emulation Phase B.2 — Validator-Regeln**
  *(2026-05-28)* — zwei zusammengehörige Validator-Schichten, die
  laut Plan-Doc §3.4 / §3.6 / §7 Phase B vor dem `helper_table`-
  Generator (B.3) feststehen müssen.

  **Step 1 — SequenceDefinition-internal-Regeln (`E125`,
  `25f59f73`):** Neuer dialektagnostischer
  `SchemaSequenceValidationRules` in `hexagon:core/validation`,
  verdrahtet in `SchemaValidator.validate(...)`. Blockt
  `increment = 0`, `increment = Long.MIN_VALUE`,
  `min_value > max_value`, `start ∉ [min, max]` und
  `|increment| > max − min`. Der overflow-sichere
  `isIncrementInRange(inc, min, max)` folgt der Plan-Doc-§3.6-
  Herleitung und vermeidet die `max − min`-Subtraktion komplett —
  Grenzfälle bei `Long.MIN_VALUE`/`Long.MAX_VALUE` sind im Test
  pinned.

  **Step 2 — SQLite-helper_table-Cross-Check (`E059`, `09068f79`):**
  Neuer `PreGenerationValidator`-Port in `hexagon:ports`
  (Default-Methode `DatabaseDriver.preGenerationValidator(): NoOp`,
  PG/MySQL bleiben unberührt). SQLite-Driver liefert
  `SqliteHelperTableSequenceValidator` über die
  `SqlitePreGenerationValidator`-Bridge: blockt im
  `helper_table`-Modus jede Spalte, die `DefaultValue.SequenceNextVal`
  trägt **und** Teil des `PRIMARY KEY` ist. Symmetrisch verdrahtet
  in `SchemaGenerateRunner` und `ToolExportRunner` (jeweils nach dem
  dialektagnostischen `SchemaValidator`, vor `DdlGenerator.generate`).
  PG/MySQL erlauben PK + `SequenceNextVal` weiterhin — die Regel ist
  bewusst SQLite-Helper-Table-spezifisch, statt sie in den
  generischen Validator zu pressen.

  **Out of scope für B.2:** "CHECK `IS NOT NULL` auf
  `SequenceNextVal`-Spalte" — Plan-Doc §3.4 (Z. 728-735) sagt
  explizit Generator-Zeit-Auto-Suppression mit Warn-Code, kein
  Validator-Error → wandert nach B.3. "SequenceNextVal +
  expliziter DEFAULT" ist bereits durch bestehendes `E131`
  ("identity generation and default are mutually exclusive")
  abgedeckt.

  Ledger: neuer `error-code-ledger-0.9.7.yaml` registriert `E125`
  und `E059` als `active`; `CodeLedgerValidationTest` pinnt beide.
  `spec/ledger.md` Code-Number-Ranges um beide Codes erweitert
  (`E059` in der `E052-E060`-Gruppe, `E125` an
  `E122-E124`-Sequence-Default-Cluster anschließend).

  Plan-Doc: `docs/planning/done-archive/sqlite-sequence-emulation-plan.md`
  Phase B.2. Bleiben offen: B.3 (`helper_table`-DDL +
  `_bi`/`_ai`-Trigger-Paar inkl. CHECK-Auto-Suppression), B.4
  (`SequenceCapability`-Defaults flippen), C/D/E.

- **0.9.7 SQLite-Sequence-Emulation Phase B.1 — CLI-Plumbing**
  *(2026-05-27)* — neues `SqliteNamedSequenceMode`-Enum
  (`action_required` / `helper_table`) im `hexagon:ports-read`,
  Slot in `DdlDialectContext.Sqlite.namedSequenceMode`. CLI-Flag
  `--sqlite-named-sequences <action_required|helper_table>` auf
  `schema generate`; Default für SQLite-Targets bleibt
  `action_required`. Runner-seitige Validierung lehnt das Flag mit
  Exit 2 + Hinweistext ab, wenn `--target` nicht `sqlite` ist.
  JSON-Output und YAML-Sidecar-Report zeigen das Feld
  (`sqlite_named_sequences`) wenn gesetzt.

  Der Generator-Pfad (`helper_table`-Mode emittiert noch keine DDL)
  folgt in Phase B.3. Phase A § 11 Pre-Code-Klärungen sind alle
  geschlossen: Min-Version `3.35.0`, `DefaultValue.SequenceNextVal`
  vorhanden, Trigger-Body-Layout gegen SQLite 3.53.1 prototyp-
  validiert (`/tmp/sqlite-two-trigger-prototype.sql`, 8/8 Szenarien),
  W114-Vertrag via ADR-0003 fixiert.

- **0.9.7 E.3 Schirm — Cross-Dialect Sequencing (Sub-Slices A–E)**
  *(2026-05-27)* — retroaktive Harmonisierung der drei bereits
  abgeschlossenen Sequence-Slices (PG-DDL, MySQL helper_table,
  preserveCurrentValue) hinter einem einzigen Capability-Vertrag.

  Neu im Code:
  - `hexagon:ports-read:SequenceCapability` + `SequenceCapabilityDefaults`
    analog zu `RoutineCapability` / `TriggerCapability`. Acht
    Felder pro Dialekt: `supportsNamedSequences`, `supportsStart`,
    `supportsMinMaxValue`, `supportsCycle`, `supportsCache`,
    `emitsCachePreallocationWarning`, `supportsCurrentValuePreserve`,
    `supportsOwnedBy`. SQLite-Defaults sind reality-first
    (`supportsNamedSequences=false`); sie flippen, wenn der offene
    SQLite-Sequence-Emulation-Plan landet.
  - `PlannerBlockerClassifier`: zwei forward-kompatible Codes
    `SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT` und
    `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT`, beide auf
    `MANUAL_ACTION_REQUIRED`. Kein Renderer emittiert sie heute —
    OP-level SQLite-Block bleibt `DIALECT_UNSUPPORTED_OPERATION`.
  - `MysqlDiffSequenceOps`: emittiert `W114` im Diff-Pfad bei
    `CreateSequence` UP, `AlterSequence` (beidseitig wenn `cache`
    differiert), `DropSequence` DOWN — gegated über
    `SequenceCapability.emitsCachePreallocationWarning`. Bisher
    war `W114` nur im Full-Schema-Pfad (`MysqlSequenceDdlSupport`)
    aktiv; ein Operator mit `schema migrate --execute` sah die
    Warnung für sequence-altering Diffs nicht.

  Spec-/ADR-Updates:
  - `spec/neutral-model-spec.md` §9.2 (neu): Cross-Dialect-
    Capability-Matrix pro `SequenceDefinition`-Attribut.
  - `spec/cli-spec.md` §4.5: `W114` in der kanonischen
    Warning-Tabelle (war zuvor nur in §6.1-Prosa).
  - `spec/cli-spec.md` §4.7 (neu): String-codiertes Sequence-
    Blocker-Katalog mit Routing pro Code.
  - `docs/adr/0003-cross-dialect-sequencing.md` (neu): Decisions
    D1–D5 in binder Form für künftige Sequence-Tranchen
    (SQLite-helper-table, MariaDB-native, Ownership-Feld).

  Plan-Doc:
  `docs/planning/done-archive/ImpPlan-0.9.7-cross-dialect-sequencing.md`.

- **0.9.7 E.1 Folge-Slice — MySQL Routine-Identity Reverse-Read**
  *(2026-05-22)* — `MysqlRoutineReader.readFunctions` / `readProcedures`
  populieren jetzt `security` (`SQL SECURITY DEFINER`/`INVOKER`),
  `definer` (roher `'user'@'host'`-String) und `sqlMode`
  (komma-getrennter Snapshot zur Routine-Erzeugungszeit) aus
  `information_schema.routines`. PG-Slice E lieferte die analogen
  Felder schon seit E.1; MySQL blieb auf den Data-Class-Defaults
  `null` stehen, sodass file-zu-DB-Diffs gegen ein MySQL-Schema mit
  expliziten Identity-Attributen spurious-Replace-Diagnosen
  emittierten.

  Leere `sql_mode`-Strings werden zu `null` normalisiert; ein
  unbekanntes `security_type` (älteres MySQL oder eingeschränkte
  `information_schema`-Sicht) fällt ebenfalls auf `null` zurück.
  `RoutineIdentityNormalizer.normalizeMysqlSqlMode` (bereits seit
  E.1 im Comparator/Fingerprint) sortiert und dedupliziert die
  Modus-Liste, sodass Reihenfolge-Drift kein spurious-Replace mehr
  ist.

  Plan-Doc:
  `docs/planning/done-archive/ImpPlan-0.9.7-mysql-routine-identity-reverse-read.md`.

- **0.9.7 E.3 Folge-Slice — Sequence preserveCurrentValue
  (Sub-Slices A–E)** *(2026-05-21)* — neue, opt-in pro Sequenz
  steuerbare Policy `SequenceDefinition.preserveCurrentValue`. Wenn
  `true` und das Migrate-Target ist eine Live-DB, probt die
  Pipeline vor dem Render den runtime-Wert der Sequenz
  (PG `last_value`/`is_called`, MySQL `dmg_sequences.next_value`)
  und emittiert einen `AlterSequenceCurrentValue`-Follow-up direkt
  hinter der parent-Op (Create/Alter/Rename). Der Renderer
  übersetzt das per Dialekt in `SELECT setval(...)` (PG) oder
  `UPDATE dmg_sequences SET next_value = ...` (MySQL). Damit landen
  Migrationen gegen Bestandsdatenbanken mit befüllten
  sequence-tragenden Tabellen produktiv, ohne dass der erste
  Schreibzugriff in einen PK-Konflikt läuft.

  Probe & Port (Sub-Slices B `c93e44ef` / C `4eb0f525`):
    - Neuer Port `SequenceCurrentValueProbe` (`hexagon:ports-read`)
      mit sealed `SequenceCurrentValueProbeResult`
      (`Read{value, matchedRows, isCalled?, managedBy?,
      formatVersion?}`, `Failed{code, message}`, `NotFound`,
      `NotApplicable`).
    - JDBC-Adapter `PostgresSequenceCurrentValueProbe`
      (`adapters/driven/driver-postgresql`): `SELECT last_value,
      is_called FROM "<schema>"."<seq>"` mit SQLSTATE-Mapping
      `42P01 → NotFound`, `42501 → Failed(PROBE_PERMISSION_DENIED)`.
    - JDBC-Adapter `MysqlSequenceCurrentValueProbe`
      (`adapters/driven/driver-mysql`): `SELECT next_value,
      managed_by, format_version FROM dmg_sequences WHERE name = …`
      mit Error-Code-Mapping (1146/1142) und
      `SUPPORTED_MANAGED_BY` / `SUPPORTED_FORMAT_VERSIONS`-
      Validation gegen operator-eingefügte oder fremdformat
      Helper-Table-Zeilen
      (`PROBE_UNMANAGED_ROW` / `PROBE_UNKNOWN_FORMAT_VERSION`).
    - `MysqlSequenceSupportNaming.SUPPORTED_MANAGED_BY` wird als
      `Set<String>` geführt (single value `"d-migrate"` heute);
      Renderer + Probe beziehen die Filter-Liste aus derselben
      Konstante, damit sich Marker-Erweiterungen
      (`d-migrate-legacy` etc.) in einem einzigen Edit ergeben
      (Refactor-Commit `12f4b812`).

  Renderer-Pfade (Sub-Slice A `29ade761` + `feature(core)…
  no carve-outs` Folge-Commit):
    - PG: `PostgresDiffSequenceOps.renderAlterSequenceCurrentValue`
      emittiert `SELECT setval('<seq>', <value>, <isCalled>);` als
      Up-Statement; Down setzt auf `restoreValue`/`restoreIsCalled`
      zurück. `isCalled` ist auf PG zwingend (Renderer-Assertion
      verweigert einen half-built `setval(seq, value, null)`).
    - MySQL: `MysqlDiffSequenceOps.renderAlterSequenceCurrentValue`
      emittiert `UPDATE dmg_sequences SET next_value = …
      WHERE name = … AND managed_by IN (...) AND format_version
      IN (...);` — die `IN`-Listen iterieren
      `SUPPORTED_MANAGED_BY`/`SUPPORTED_FORMAT_VERSIONS`
      deterministisch.
    - SQLite: bleibt `OpCategory.UNSUPPORTED` →
      `DIALECT_UNSUPPORTED_OPERATION`. Endzustand, kein
      „kommt später"-Carve-out, bis ein SQLite-Sequence-
      Emulationsplan landet.
    - Plan-Augmentation (Sub-Slice D): jede
      `AlterSequenceCurrentValue`-Follow-up trägt
      `dependencies = setOf(parent.id)`; der Renderer-Switch
      routet sie über die SEQUENCE-Category zu denselben
      Per-Op-Renderern.

  Pipeline-Integration + Planner-Emit (Sub-Slice D `257908fd`):
    - Neue `SequencePreserveStage` (`hexagon:application`,
      `cli/commands/SequencePreserveStage.kt`) mit drei-Outcome-
      Shape (`Succeeded(augmentedPlan, infos)` / `Failed(diags)` /
      `NotRun`). Skip-Pfade: `!--execute`, file-Target, nicht-PG/
      MySQL-Dialect (per-Op `NOT_SUPPORTED_BY_DIALECT`-Blocker),
      kein Probe wired (per-Op `NOT_RUN_POLICY`-INFO), keine
      preserve-Kandidaten im Plan.
    - Kandidatenfilter: `AlterSequence` (immer, wenn
      preserve=true), `CreateSequence` nur mit
      `renameProvenance != null` (konservative Pre-Probe-Gate;
      Widening in Folge-Slice), `RenameSequence` wenn die
      Quell-Sequenz aus `currentSchema` preserve=true trägt.
      `DropSequence` ist nicht Kandidat.
    - Routing (`SequencePreserveStage.routeProbeResult`):
      `Read(matchedRows=1)` → Follow-up; `Read(matchedRows≠1)` →
      `PROBE_FAILED`-Blocker; `NotFound` → INFO für Create, Block
      für Alter+Rename; `Failed`/`NotApplicable` → entsprechende
      Blocker-Codes. Probe-Exceptions werden gefangen und als
      `PROBE_FAILED`-Diagnose pro betroffener parent-Op gestempelt.
    - Plan-Augmentation: jede `AlterSequenceCurrentValue`-Op
      landet direkt hinter ihrer parent-Op im
      `DiffResult.operations`-Stream. Der augmentierte Plan
      ersetzt den Original-Plan für `generateUp` / `generateDown`,
      `maybeWritePlanArtefact` (`migration-plan.v1`-Artefakt
      reflektiert den tatsächlich ausgeführten Op-Stream),
      Report-Builder und Rollback-Composer.
    - CLI-Wiring: `SequenceCurrentValueProbeRunner` als
      dialect-dispatchender Lambda an
      `SchemaMigrateRunner.sequenceCurrentValueProbe`. Routet pro
      `SequenceObjectRef.dialect` an `PostgresSequenceCurrentValueProbe`
      oder `MysqlSequenceCurrentValueProbe`; SQLite bleibt
      `NotApplicable`.

  Diagnose-Codes + `PlannerBlockerClassifier`-Mapping:
    - `SEQUENCE_PRESERVE_PROBE_FAILED` → `MANUAL_ACTION_REQUIRED`
    - `SEQUENCE_PRESERVE_CONFIG_INVALID` → `MANUAL_ACTION_REQUIRED`
    - `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET` → `MANUAL_ACTION_REQUIRED`
      (für zukünftige E-Slice-Erweiterung; D emittiert ihn heute
      nicht, weil `SchemaMigratePreparation` bereits Exit 2 für
      `--execute` mit File-Target hat).
    - `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` →
      `DIALECT_UNSUPPORTED_OPERATION` (SQLite).
    - INFO-Codes (`SEQUENCE_PRESERVE_NOT_FOUND` für CreateSequence
      ohne Vorzustand, `SEQUENCE_PRESERVE_NOT_RUN_POLICY` für
      fehlende Probe) bleiben absichtlich aus dem
      Classifier-Mapping — die Stage surfaced sie über
      `MigrationDdlResult.diagnostics` ohne Blocker-Klassifizierung.

  Out-of-Scope (eigene Folge-Slices):
    - **Atomare Probe + setval/UPDATE unter Lock**: wenn die App
      parallel `nextval` aufruft während der Pipeline-Probe läuft,
      ist der gesetzte Wert veraltet. Operator-Pflicht ist heute
      ein Freeze-Window. Eine künftige Tranche kann
      `LOCK TABLES` / `BEGIN; SELECT FOR UPDATE`-Wrappers
      ergänzen.
    - **CreateSequence-Pre-Probe-Gate Widening**: die heutige
      konservative Definition (`renameProvenance != null`) deckt
      nur Drop+Create-Rename-Fallbacks ab; der idempotente
      Re-Run-gegen-bestehendes-Target-Use-Case bleibt
      `SEQUENCE_PRESERVE_NOT_FOUND` (INFO) bis ein deterministischer
      pre-probe-Pfad spezifiziert ist.
    - **Multi-Sequence-Atomicity**: pro-Op-Probes laufen seriell;
      Operator-Inserts zwischen den Probes können Inkonsistenzen
      erzeugen. Carve-out dokumentiert.
    - **SQLite-Sequence-Vollvariante**: separater Plan unter
      `docs/planning/done-archive/sqlite-sequence-emulation-plan.md`.

  Tests:
    - Unit: `SequencePreserveStageTest`,
      `PlannerBlockerClassifierTest` (extended),
      `PostgresSequenceCurrentValueProbeTest`,
      `MysqlSequenceCurrentValueProbeTest`,
      `PostgresDiffSequenceOpsPreserveCurrentValueTest`,
      `MysqlDiffSequenceOpsPreserveCurrentValueTest`.
    - Runner-E2E: `SchemaMigrateRunnerSequencePreserveTest` —
      probe → augmented plan → MigrationDdlResult → Report (Read,
      NotFound auf Alter/Create, Probe-throws, SQLite-Blocker,
      no-probe-NOT_RUN_POLICY).
    - Integration (`make integration`):
      `PostgresSequenceCurrentValueProbeIntegrationTest` (live PG),
      `MysqlSequenceCurrentValueProbeIntegrationTest` (live MySQL),
      pinnen Probe-Outcomes gegen echte DB-Bytes.

  Sub-Slice-Commits:
    - A — Foundations + Renderers: `29ade761`.
    - B — PG Probe: `c93e44ef`.
    - C — MySQL Probe: `4eb0f525`.
    - C-Fix — Permission-Test entfernt (testcontainers MySQL-User
      hat kein CREATE USER): `86f188a6`.
    - `managed_by`-Refactor auf `SUPPORTED_MANAGED_BY: Set`:
      `12f4b812`.
    - D — Pipeline + Planner-Emit: `257908fd`.

  Plan-Doc:
  `docs/planning/done-archive/ImpPlan-0.9.7-sequence-preserve-current-value.md`.

- **0.9.7 E.3 Folge-Slice — MySQL Sequence Live-DB-Drift-Check
  (Sub-Slices A–F)** *(2026-05-20)* — `schema migrate --execute`
  gegen MySQL-Targets verifiziert jetzt vor dem Render, dass die
  Helper-Table-Emulation der Sequence-Migration (`dmg_sequences`-
  Tabelle, `dmg_nextval` / `dmg_setval`-Routinen, betroffene
  `dmg_sequences`-Zeile, gebundene `dmg_seq_…_bi`-Trigger) der
  kanonischen Form entspricht. Drift in einem dieser Objekte
  blockiert die Migration vor jeder DDL-Emission statt mit
  unklarem JDBC-Constraint-Fehler abzubrechen.

  Probe & Port:
    - Neuer Port `MysqlSequenceCanonicityProbe`
      (`hexagon:ports-read`) mit Methoden für Support-Table-
      Spaltensignatur, Routine-Body-Marker, Sequence-Row
      Managed-Fields (`increment_by` / `min_value` /
      `max_value` / `cycle_enabled` / `cache_size`) und
      Support-Trigger-Body. Per-Probe-Aufruf liefert eine
      `MysqlSequenceCanonicityDeclaration` mit Status
      CANONICAL / DRIFT / MISSING / NOT_RUN_FILE_TARGET /
      NOT_RUN_POLICY / PROBE_RUNTIME_ERROR und — bei DRIFT —
      strukturiertem Feld-Diff (`driftField` / `expected` /
      `actual`).
    - JDBC-Adapter `MysqlSequenceCanonicityProbeAdapter`
      (`adapters/driven/driver-mysql`) implementiert die Probes
      über `INFORMATION_SCHEMA.COLUMNS`, `SHOW CREATE FUNCTION`
      und `SHOW CREATE TRIGGER`. MySQL-Fehlercodes 1305
      (SP_DOES_NOT_EXIST) und 1360 (TRG_DOES_NOT_EXIST) werden
      zu Status `MISSING` mapped (nicht
      `PROBE_RUNTIME_ERROR`), weil der canonical Bootstrap
      diese Objekte erst noch erzeugen wird.

  Gate-Entscheidung:
    - `MysqlSequenceCanonicityGate.decide(declaration, intent)`
      routet pro Status × Op-Intent (`CREATE` / `ALTER` /
      `DROP`):
        * `CANONICAL` → Proceed in jedem Intent.
        * `DRIFT` → Block mit kind-spezifischem Code
          (`E124_MYSQL_SEQUENCE_DRIFT_TABLE` /
          `…_ROUTINE` / `…_ROW` / `…_TRIGGER`) und
          MANUAL_ACTION_REQUIRED.
        * `MISSING` + `CREATE` / `DROP` → Proceed (Bootstrap
          oder idempotenter Drop). `MISSING` + `ALTER` → Block
          mit `E124_MYSQL_SEQUENCE_MISSING_FOR_ALTER`.
        * `PROBE_RUNTIME_ERROR` → Block mit
          `E124_MYSQL_SEQUENCE_DRIFT_PROBE_FAILED`.
        * `NOT_RUN_*` → Info-Decision (kein Blocker), damit der
          Report den "drift-check skipped"-Grund tracked.
    - `PlannerBlockerClassifier` mapped alle sechs neuen Codes
      auf `MigrationBlockedReason.MANUAL_ACTION_REQUIRED`.

  CLI-Stage + Pipeline:
    - Neue `MysqlSequenceCanonicityStage` (`hexagon:application`)
      mit drei Outcome-Zuständen (Succeeded / Failed / NotRun).
      Skip-Pfade: `!execute`, file-target, non-MySQL Dialekt,
      keine `MysqlSequenceCanonicityProbeFn` gewired, keine
      Sequence-Op im Plan. Bei Exception aus der Probe-Funktion
      werden alle Sequence-Ops als `PROBE_RUNTIME_ERROR`
      gestempelt und ein Top-Level `MYSQL_SEQUENCE_DRIFT_RUN_FAILED`-
      Diagnostic emittiert.
    - `SchemaMigrateRenderPipeline` bekommt einen optionalen
      `mysqlSequenceCanonicityProbe`-Parameter (rückwärtskompatibel
      über `null` default); `runMysqlSequenceCanonicity` läuft
      vor `buildRenderOptions`, deren `mysqlSequenceCanonicity`-
      Feld bei Succeeded die Probe-Ergebnisse trägt, sonst die
      Pre-Plan-Declarations.
    - Pre-Planning: `MigrationPreflightPlanner.plan(…)` emittiert
      pro Sequence-Op eine SEQUENCE_ROW-Declaration mit
      `NOT_RUN_FILE_TARGET` (file-target) oder `NOT_RUN_POLICY`
      (DB-execute ohne wired Probe). Non-MySQL Dialekte und
      Pläne ohne Sequence-Op liefern nichts.

  Renderer-Gate:
    - `MysqlDiffSequenceOps.canonicityBlocks(op, intent, ctx)`
      konsultiert `ctx.options.mysqlSequenceCanonicity`,
      gefiltert auf `op.id`. Erste Block-Decision gewinnt;
      Info-Decisions emittieren INFO-Diagnostics und der Renderer
      fährt fort. Per-Op-Intent: `CreateSequence` → `CREATE`,
      `AlterSequence` → `ALTER`, `DropSequence` → `DROP`,
      `RenameSequence` → `ALTER` (UPDATE auf existierender
      Zeile). Eine leere Declaration-Liste für die op-id heißt
      "kein Probe gelaufen" → Proceed.

  Report:
    - `SchemaMigrateReport.mysqlSequenceCanonicity[]` carriert
      ein neues `SchemaMigrateMysqlSequenceCanonicityView`-DTO
      pro (Sequence-Op, kanonisches Objekt). JSON / human-Reports
      sehen Status, Kind, Object-Name, sqlHash und bei Drift /
      Probe-Fehler die zusätzlichen Felder.
    - `MigrationDdlResult.mysqlSequenceCanonicity` lebt parallel
      zu `checkPreflights` / `sqliteCastPreflights`; Up- und
      Down-Render-Results werden über `bindingKey` (op-id +
      kind + object + hash) dedupliziert gemerged.

  Live-DB-Coverage:
    - `MysqlSequenceCanonicityProbeIntegrationTest` läuft via
      testcontainers gegen einen echten MySQL-8-Container und
      pinnt jede Probe-Methode gegen kanonischen und
      drifted State. Aktivierung über `make integration` (oder
      `-PintegrationTests`); aus der reinen `make docker-check`-
      Suite weiterhin ausgeklammert, weil docker-in-docker
      benötigt wird.
    - PK-Drift wird vom Probe-Adapter zusätzlich zur Spalten-
      Signatur geprüft (`INFORMATION_SCHEMA.STATISTICS` auf den
      `PRIMARY`-Index): eine `dmg_sequences` ohne `PRIMARY KEY
      (name)` ergibt `driftField = "primary_key"`. Damit ist eine
      Tabelle ohne PK nicht mehr CANONICAL.
    - Trigger-Drift-Gate in `emitSupportTriggerForColumn`:
      `AddColumn` / `AlterColumnDefault` mit `SequenceNextVal`-
      Default holen vor dem `DROP + CREATE TRIGGER` die
      passende SUPPORT_TRIGGER-Declaration und blocken bei DRIFT.
      Vorher wurde Operator-modifizierter Trigger-Body
      stillschweigend überschrieben.
    - CLI-Wiring komplettiert: `SchemaMigrateCommand` übergibt
      `MysqlSequenceCanonicityProbeRunner::probe` an
      `SchemaMigrateRunner`, sodass `schema migrate --execute`
      gegen MySQL den Live-Drift-Check tatsächlich ausführt
      statt auf `NOT_RUN_POLICY` zu fallen.

  Out-of-scope für diesen Slice (eigene Folge-Slices):
    - **Auto-fix / Drift-Repair**: Der Drift-Check meldet
      ausschließlich. Repair-DDLs zu emittieren (z.B. die
      `dmg_sequences`-Spalte nachträglich auf NULLABLE schalten)
      ist eine eigene Operatoren-Aufgabe; die
      `MANUAL_ACTION_REQUIRED`-Diagnose nennt den betroffenen
      Drift-Field genau, damit der Operator das gezielt
      adressieren kann.
    - **`--skip-sequence-drift-check`-Flag**: Status
      `NOT_RUN_POLICY` ist bereits vorgesehen, ein
      User-facing-Flag dafür gibt's noch nicht. Operatoren, die
      den Check temporär ausstellen wollen, müssten heute den
      Probe-Wire weglassen.

  Plan-Doc:
  `docs/planning/done-archive/ImpPlan-0.9.7-mysql-sequence-drift-check.md`.

- **0.9.7 E.3 — MySQL Sequence Diff-Migration (Sub-Slices A–I)**
  *(2026-05-20)* — `schema migrate` against a MySQL target now
  renders the four sequence `DiffOperation` subtypes
  (`CreateSequence`, `AlterSequence`, `DropSequence`,
  `RenameSequence`) into the helper-table emulation that landed in
  0.9.4 (`docs/planning/done-archive/mysql-sequence-emulation-plan.md`).
  Previously the diff generator routed sequence ops to
  `OpCategory.UNSUPPORTED`, so operators with sequence-tracking
  MySQL schemas had to bypass `schema migrate` and apply the full
  `schema generate` script manually.

  Template surface:
    - New internal `MysqlSequenceEmulationTemplates`
      (`adapters/driven/driver-mysql`) holds the pure helper-table
      SQL templates extracted from `MysqlSequenceDdlSupport` so the
      DDL-Generator pipeline (full-schema emission) and the
      diff renderer share one source of truth. `MysqlSequenceDdlSupport`
      delegates to it; the production SQL output stays
      byte-identical (verified via existing
      `MysqlDdlGenerator*Test` fixtures).

  Diff renderer:
    - New `MysqlDiffSequenceOps` (`internal object`) implements
      Up/Down for all four subtypes. `CreateSequence` emits the
      helper-table bootstrap (`CREATE TABLE dmg_sequences` +
      `CREATE FUNCTION dmg_nextval` + `CREATE FUNCTION dmg_setval`)
      at most once per migration direction via
      `MysqlSequenceMigrationContext.needsBootstrap()`, followed by
      a row INSERT; subsequent `CreateSequence` ops reuse the
      bootstrap. `AlterSequence` emits an `UPDATE dmg_sequences`
      that touches only the managed declarative fields that
      actually differ between `op.before` and `op.after`
      (`increment_by`, `min_value`, `max_value`, `cycle_enabled`,
      `cache_size`); the runtime `next_value` / `start` state is
      left to the `preserveCurrentValue`-Cross-Dialect-Slice.
      `DropSequence` UP drops every sequence-bound trigger
      (discovered via the new
      `MysqlDiffRenderContext.triggersForSequence(name, SchemaSide)`
      helper that walks column-level `SequenceNextVal` defaults)
      before deleting the `dmg_sequences` row, so the catalog
      cannot leak `dmg_nextval('<deleted>')` references. The DOWN
      inverse re-creates the same triggers. Trigger discovery for
      `DropSequence` always consults `SchemaSide.CURRENT` (the
      pre-Up state); for `RenameSequence` the direction-based
      heuristic applies.
    - `MysqlDiffDdlGenerator.categorize()` routes the four
      subtypes to a new `OpCategory.SEQUENCE`. `RenameSequence`
      lands here as a defensive regression guard only — see the
      policy section below.
    - Mode gate: when `MysqlNamedSequenceMode != HELPER_TABLE`
      every sequence op blocks with diagnostic code `E056` →
      `MANUAL_ACTION_REQUIRED`, including the actionable hint
      "Add --mysql-named-sequences helper_table to enable
      sequence emulation."; no SQL is emitted.

  Rename policy + decomposition:
    - `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)` lifted
      from `RenameSupport.Blocked` to
      `RenameSupport.DropCreateFallback`. The Mapper now
      decomposes sequence renames into
      `DropSequence(from)` + `CreateSequence(to)` with shared
      `renameProvenance`, mirroring the trigger / function /
      procedure fallback that F.4 Sub-Slice B established. The
      `RenameSequence` op type still has a defensive `UPDATE
      dmg_sequences SET name = …` + trigger rebuild path in the
      renderer for planner regressions / custom plans / artefact
      replays.

  Column-default reprojection:
    - `SequenceDefaultReprojector` (F.4 Sub-Slice D) extended to
      recognise the Drop-Create fallback path. Without this, a
      MySQL plan that combines a rename overlay
      (`old_seq → new_seq`) with a `CreateTable` / `AddColumn` /
      `AlterColumnDefault` referencing `nextval('old_seq')` would
      silently emit DDL that points at the dropped sequence name.
      The reprojector picks up the `CreateSequence` half of every
      fallback pair (those whose `renameProvenance.objectType ==
      SEQUENCE`) and reads its `fromPath[0]` / `toPath[0]` into
      the same rewrite map the PostgreSQL-native `RenameSequence`
      path uses. The `DependencyAnalyzer` requires no change — its
      `sequenceSourceIdByName` map already keys
      `CreateSequence.objectRef.rootName → CreateSequence.id`, so
      column-bearing ops with rewritten defaults get the correct
      dependency edge for free.

  Carve-outs documented as out-of-scope (deferred to follow-up
  slices, named per plan §9): live-DB drift / canonical-form
  checks against existing `dmg_sequences` rows (analogous to F.5
  E.3's `CheckPreflightProbe`); `preserveCurrentValue` policy
  across dialects; SQLite sequence diff; cross-dialect sequence
  transfer; MariaDB-native `CREATE SEQUENCE`.

  Tests landed across the affected modules:
  `MysqlSequenceEmulationTemplatesTest`,
  `MysqlDiffSequenceOpsTest`,
  `ObjectRenamePolicyTest` (MySQL SEQUENCE case),
  `RenameObjectMapperTest` (MySQL fallback case),
  `MysqlDiffObjectRenameTest` (Drop+Create+Provenance pin),
  `SequenceDefaultReprojectorTest` (three new MySQL fallback
  cases).

  Bootstrap idempotency + column-default rendering (Sub-Slice F):
    - The helper-table bootstrap is idempotent across migration
      re-runs: `MysqlSequenceEmulationTemplates.supportTableSql`
      emits `CREATE TABLE IF NOT EXISTS dmg_sequences`, and the
      `nextval` / `setval` routines are preceded by separate
      `DROP FUNCTION IF EXISTS …;` statements (kept outside the
      `DELIMITER //`-wrapped CREATE so the DDL-Generator's
      rollback parser still recognises the routine name). A
      migration against a DB that already has the helper objects
      no longer fails on "table already exists" / "function
      already exists".
    - `SequenceNextVal` column defaults now render correctly in
      the diff path. `MysqlDiffSqlBuilders.columnLine` drops the
      `DEFAULT` clause for sequence defaults (mirrors the DDL-
      Generator's `resolveSequenceDefault` bypass), and the
      table-level renderers emit a per-column BEFORE INSERT
      trigger via `MysqlDiffSequenceOps.emitSupportTriggerForColumn`.
      `renderCreateTable` / `renderAddColumn` /
      `renderAlterColumnDefault` are all wired through the new
      mode-gated path; before Sub-Slice F a plan that combined a
      `CreateTable` / `AddColumn` with a `SequenceNextVal` default
      crashed the renderer.
    - `AlterSequence` whose `before → after` delta only touches
      the runtime-state field `start` no longer disappears
      silently. `renderAlterSequence` emits an INFO-severity
      diagnostic `MYSQL_SEQUENCE_RUNTIME_STATE_NO_OP` and marks
      the op as skipped — no blocker, but the report tracks the
      op. The actual runtime-state migration is the
      `preserveCurrentValue` cross-dialect follow-up.

  DELIMITER fix + scope clarification (Sub-Slice H):
    - The Sub-Slice B/F templates wrapped the routine + trigger
      bodies in `DELIMITER //…DELIMITER ;`, which is a mysql-CLI
      client directive — JDBC throws `SQLException`. Sub-Slice H
      moves the template output to a delimiterfreien BEGIN…END
      body; the DDL-Generator pipeline wraps the result with
      `wrapWithDelimiter` for `--output file.sql` artefacts, and
      the diff path passes the body directly through JDBC. Without
      this fix `schema migrate --execute` would have failed on
      every helper-table bootstrap or column-bound trigger
      emission.
    - The drift-/canonicity-check scope (live-DB validation of
      existing `dmg_sequences` rows and `dmg_*` support objects)
      is now explicitly out of this slice with a dedicated
      follow-up plan-doc stub
      (`docs/planning/done-archive/ImpPlan-0.9.7-mysql-sequence-drift-check.md`,
      6 sub-slices) so "done" no longer hides the reduced scope.

  Commit timeline:
  Sub-Slice A `edc1fb9d` + review `4336284d`,
  B `28598cde` + review `7c2b8bec`,
  C `d3724a33` + review `93aa3e40`,
  D `0bda4f15`,
  E (closing iter 1) `c7e92bf4` + `1431fb24`,
  F `3bea97e7`,
  G (closing iter 2) `aba3392f`,
  H `d248cd11`,
  I (closing iter 3) ⟵ this commit.
  Plan-Doc: `docs/planning/done-archive/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`.

- **0.9.7 F.5 — CHECK / EXCLUDE Constraint Vollscheibe (Sub-Slices A–G)**
  *(2026-05-20)* — the conservative F.5 carve-out from the
  first-matrix slice (every table carrying a CHECK or EXCLUDE
  constraint was tagged `CONSTRAINT_NOT_DIFFABLE`) is removed.
  CHECK and EXCLUDE constraints now flow through the standard
  Add/Drop/Replace pipeline with per-dialect rendering, live-data
  preflight in execute mode, MySQL enforcement gating, and a
  reversibility + replace contract.

  Comparison and planning:
    - `ConstraintDiffContract.canonicalRawSqlExpression()` pins
      raw-SQL constraint comparison to LF normalisation +
      surrounding `trim()` — semantically different expressions
      remain different.
    - `OperationMapper` no longer skips CHECK / EXCLUDE; they emit
      `AddConstraint` / `DropConstraint` (and a `DropConstraint +
      AddConstraint` pair for `constraintsChanged`) like UNIQUE /
      FOREIGN_KEY.
    - Both ops gain `replacePairId: String?`, a deterministic
      Replace-group identity (`SHA-256(table | name |
      canonical(before) | canonical(after))`) that downstream
      reporters use to recognise the pair. Op ids stay unique;
      dependency-sort, artefact binding and
      `renderedStatements.operationIds` continue to work unchanged.
    - New `ConstraintReplaceContract` post-pass classifies
      reversibility:
      `AddConstraint(CHECK/EXCLUDE)` → `AUTOMATIC`;
      `DropConstraint(CHECK/EXCLUDE)` with known expression →
      `AUTOMATIC_WITH_DATA_RISK`;
      `DropConstraint(CHECK/EXCLUDE)` without expression →
      `NOT_REVERSIBLE`. UNIQUE / FOREIGN_KEY ops pass through
      unchanged.
    - `PlannerBlockerClassifier` adds seven new diagnostic codes:
      `CHECK_PREFLIGHT_VIOLATIONS`,
      `CHECK_PREFLIGHT_RUNTIME_ERROR`,
      `EXCLUDE_NOT_SUPPORTED_BY_DIALECT`,
      `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16`,
      `MYSQL_CHECK_ENFORCEMENT_UNKNOWN`,
      `CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED`,
      `EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED`. All but the
      dialect-incapability case map to `MANUAL_ACTION_REQUIRED`
      (operator can rewrite the expression, clean up data, or
      upgrade the server); EXCLUDE on MySQL/SQLite maps to
      `DIALECT_UNSUPPORTED_OPERATION`. No new
      `MigrationBlockedReason` enum values were required.

  Per-dialect rendering matrix:
    - **PostgreSQL** renders CHECK and EXCLUDE via `ALTER TABLE
      … ADD CONSTRAINT … CHECK (…)` / `… EXCLUDE USING gist (…)`
      and `… DROP CONSTRAINT …` on the inverse side; inline
      `CREATE TABLE` keeps the constraint clause in the body.
      New `ExcludeOperatorClassGate` whitelists element heads
      (bare or quoted identifier, balanced parenthesised
      expression) before `WITH`; custom operator classes,
      `COLLATE`, `ASC`/`DESC` and `NULLS …` tokens block with
      `EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED` →
      `MANUAL_ACTION_REQUIRED` on Add (UP), Drop (DOWN) and inline
      `CREATE TABLE`.
    - **MySQL ≥ 8.0.16 / MariaDB ≥ 10.2.1** render CHECK via
      `ADD CONSTRAINT … CHECK (…)` / `DROP CHECK …`.
      `MysqlCheckEnforcementResolver` (`hexagon:ports-read`) maps
      `mysqlServerVersion` to a `(known, enforced)` capability:
      pre-8.0.16 (or MariaDB < 10.2.1) blocks
      `MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16` on logical-add
      paths (silent enforcement-skip is treated as a contract
      violation); missing `mysqlServerVersion` blocks
      `MYSQL_CHECK_ENFORCEMENT_UNKNOWN` on both add and drop.
      EXCLUDE is short-circuited with
      `EXCLUDE_NOT_SUPPORTED_BY_DIALECT`.
    - **SQLite** absorbs CHECK Add/Drop/Replace into the rebuild
      pipeline (the temporary `CREATE TABLE` embeds the new
      constraint set inline). EXCLUDE blocks the bucket with
      `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` — including the
      schema-level safety net that catches a pre-existing EXCLUDE
      on a column-reshape rebuild that no op would otherwise
      mention.

  Live-data preflight (execute mode):
    - New `CheckPreflightProbe` port + per-dialect adapter
      (`Postgres`/`Mysql`/`SqliteCheckPreflightProbe`) runs
      `SELECT count(*) FROM <t> WHERE NOT (<expr>)` against the
      target. A non-zero count surfaces
      `CHECK_PREFLIGHT_VIOLATIONS`; a thrown SQL error surfaces
      `CHECK_PREFLIGHT_RUNTIME_ERROR` with the message embedded
      in the report. The execute pipeline declares one preflight
      per `AddConstraint(CHECK)` via `CheckPreflightPlanner` +
      `CheckPreflightStage`, and the renderer gate
      (`CheckPreflightGate`, consumed identically by all three
      dialects) decides Proceed vs. Block. File-to-file mode
      emits `NOT_RUN_FILE_TARGET` declarations so the report can
      tell the operator the gate did not run; the renderer keeps
      emitting.
    - Reports surface a new `MigrationDdlResult.checkPreflights`
      list and `SchemaMigrateCheckPreflightView` field
      (Sub-Slice E.4); the CLI status command renders the per-op
      verdict alongside existing per-op diagnostics.

  Reversibility + Replace contract (Sub-Slice F):
    - PostgreSQL and MySQL renderers route a `DropConstraint`
      DOWN-pass for CHECK/EXCLUDE without expression to
      `ROLLBACK_NOT_POSSIBLE` (the inverse `ADD CONSTRAINT …
      <expr>` cannot be reconstructed) instead of the generic
      `DIALECT_UNSUPPORTED_OPERATION`. SQLite is already covered
      via the existing NOT_REVERSIBLE rebuild-bucket gate.
    - The Replace pair (Drop+Add for `constraintsChanged`)
      round-trips through Up and Down — Up emits Drop-then-Add of
      the new expression; Down emits Add-of-old followed by Drop
      of new. Op ids stay unique; the shared `replacePairId` ties
      them together for reporters.

  Carve-outs documented as out-of-scope (deferred to follow-up
  slices, named per plan §9): semantic SQL parser for CHECK
  expressions; PostgreSQL `NOT VALID` + `VALIDATE` two-step
  workflow; EXCLUDE with custom operator classes beyond the
  Whitelist; MySQL `CHECK` `NOT ENFORCED` override; preflight
  sampling for very large tables; cross-table CHECK references
  via trigger emulation.

  Tests landed across the affected modules:
  `ConstraintReplaceContractTest`,
  `OperationMapperReplacePairTest`,
  `CheckPreflightPlannerTest`,
  `MysqlCheckEnforcementResolverTest`,
  `PlannerBlockerClassifierTest`,
  `PostgresDiffDdlGeneratorCheckExcludeTest` /
  `MysqlDiffDdlGeneratorCheckExcludeTest` /
  `SqliteDiffDdlGeneratorCheckExcludeTest`,
  `PostgresDiffCheckPreflightGateTest` /
  `MysqlDiffCheckPreflightGateTest`,
  `Postgres`/`Mysql`/`SqliteCheckPreflightProbeTest`,
  `ExcludeOperatorClassGateTest`,
  `CheckPreflightStageTest`,
  `MergeCheckPreflightsTest`.

  Commit timeline:
  Sub-Slice A `2c784d8b`, B `d60939c5`, C `99c9528c`,
  D `fe0cc35e`, E.1+E.2 `cde9d39f`, E.3 `8a47a640`,
  E.4 `fc02d621` + `a2afe0c9`, F `172c9b82`.
  Plan-Doc: `docs/planning/done-archive/ImpPlan-0.9.7-F.5-check-exclude-vollscheibe.md`.

- **0.9.7 F.4 Follow-up — `migration-plan.v1` Artefact Producer Wiring**
  *(2026-05-19, Sub-Slice G)* — closes the audit gap raised after
  F.4's closing slice: the `MigrationPlanArtifact` contract that
  landed under F.4 Sub-Slice E was modelled, encoded and validated
  in `hexagon:core`, but never constructed in the production CLI
  flow. `schema migrate` now emits a signed `migration-plan.v1`
  JSON to disk when the new `--plan-artefact <path>` flag is set.

  Producer surface:
    - New `MigrationPlanArtifactBuilder` (`hexagon:application`) —
      pure projection
      `(DiffResult, MigrationDdlResult, dialect, clock, dMigrateVersion)
      → MigrationPlanArtifact`. Maps every `DiffOperation` to a
      public DTO (kind = subtype simpleName, objectType / phase /
      reversibility as enum names, risk projection including
      `dataTransformationMode` + optional model-version / model-id);
      `DiffDiagnostic` → public DTO with enum-name severity;
      reversibility summary aggregated from per-op `Reversibility`
      (`MANUAL_REQUIRED` / `NOT_REVERSIBLE` op-id lists,
      `fullyReversible` true iff all ops AUTOMATIC or
      AUTOMATIC_WITH_DATA_RISK); rendered statements with stable
      `stmt-N` ids + canonical `RoutineBodyScrubber.preview` hashes
      + `TransactionScope.name` strings;
      `DiffResult.renameProjections` round-tripped into the public
      DTO. Builder tail-calls `withRenameProjectionExtension()`
      (auto-adds `rename-projections.v1` to `semanticExtensions`
      when the list is non-empty) + `withComputedHash()`.
    - New `SchemaMigrateArtefactSink.writePlanArtefact(path, artifact)`
      — atomic write of the canonical JSON via the existing
      `atomicWriter`. Returns `null` on success, `7` on local I/O
      failure (matches `--report` write semantics).
    - New `SchemaMigrateRequest.planArtefact: Path? = null` field.
      `SchemaMigrateRunner` invokes `maybeWritePlanArtefact(...)`
      between the report build and the rollback compose, so the
      artefact lands even on Exit 8 (blocker) and `--plan-only`
      paths.
    - New `--plan-artefact <path>` Clikt option on `schema migrate`.

  Contract documentation: `spec/cli-spec.md` §6.1 gains a new
  **`migration-plan.v1`-Artefakt** section listing every top-level
  field, the semantic-extension gates (`rename-projections.v1`),
  and the eleven validator codes (`PLAN_ARTIFACT_*`) consumers
  may see. Plan-doc:
  `docs/planning/done-archive/ImpPlan-0.9.7-F.4-G-artefact-producer-wiring.md`.

  Tests: `MigrationPlanArtifactBuilderTest` (7 cases) pins per-field
  projection, reversibility-summary aggregation, stable `stmt-N`
  ids, diagnostic severity round-trip, `renameProjections` plus
  semantic-extension auto-gate, fingerprint-missing fail-fast.
  `SchemaMigrateRunnerTest` gets a `--plan-artefact` end-to-end
  case mirroring the `--report` integration test.

- **0.9.7 F.4 Follow-up — `transactionScope` enum drift fix in
  plan-artefact contract test** *(2026-05-19, Sub-Slice G.1)* —
  `MigrationPlanArtifactContractTest` pinned
  `"transactionScope": "SINGLE_STATEMENT"` since the artefact was
  introduced in 2026-05-13. That string never matched any value in
  the runtime `TransactionScope` enum
  (`RUNNER_OWNED` / `STREAM_OWNED` / `NO_TRANSACTION`). Both
  occurrences are updated to `"RUNNER_OWNED"` — the enum's default
  and the scope every PostgreSQL diff stream emits for ordinary
  single-statement DDL. `MigrationPlanRenderedStatement.transactionScope`
  gains a KDoc block documenting the canonical contract: the field
  carries `TransactionScope.name` as a `String` (not the enum) so
  a future scope value surfaces as an unknown string for
  validators / consumers rather than a deserialisation failure.

- **0.9.7 F.4 routine-trigger-view-renames Vollscheibe** — overlay-bound
  renames for views, triggers, functions, procedures and sequences land
  alongside the existing table/column rename pipeline. The mapper
  consults a per-dialect `ObjectRenamePolicy` for each rename
  candidate and emits one of three outcomes: a native `Rename*`
  operation (`RenameView` / `RenameTrigger` / `RenameFunction` /
  `RenameProcedure` / `RenameSequence`); a `Drop*` + `Create*` pair
  tagged with a `RenameProvenance` marker; or an
  `OBJECT_RENAME_UNSUPPORTED` BLOCKER diagnostic. Mapper / renderer
  / dependency-analyzer / plan-artifact contracts close together in
  this slice; the carve-out from earlier slices (rename-mapping
  overlay whitelisted only `{table, column}`) is gone.

  Per-dialect contract:
    - **PostgreSQL** renders native rename SQL for every kind —
      `ALTER VIEW name RENAME TO new`, `ALTER TRIGGER name ON table
      RENAME TO new`, `ALTER FUNCTION name(types) RENAME TO new`,
      `ALTER PROCEDURE name(types) RENAME TO new`, `ALTER SEQUENCE
      name RENAME TO new`. Function / procedure signatures follow
      PG's drop / alter convention: OUT parameters excluded, INOUT
      keeps the keyword, names omitted. Materialized-view rename
      remains blocked (D.3b carve-out). Body-drift on view /
      trigger / routine rename blocks with
      `OBJECT_RENAME_UNSUPPORTED`.
    - **MySQL** renders `RENAME TABLE old TO new` for view-rename
      (views share the table namespace). Trigger / function /
      procedure renames lower to `Drop*` + `Create*` with a
      `RenameProvenance` marker (no native `ALTER … RENAME`
      grammar); missing bodies or body drift block. Sequence rename
      stays blocked until the E.3 MySQL-sequence rendering
      contract lands.
    - **SQLite** lowers view and trigger renames to Drop+Create
      with a `RenameProvenance` marker (no native rename grammar);
      function / procedure rename blocks (SQLite has no routine
      model); sequence rename stays blocked until an E.3
      SQLite-sequence contract lands.

  Pre-plan blockers for malformed overlay payloads:
  `RENAME_OVERLAY_TRIGGER_KEY_INVALID` (trigger entry without the
  `table::name` canonical form),
  `RENAME_OVERLAY_TRIGGER_CROSS_TABLE_REJECTED` (cross-table moves
  are not renames), `RENAME_OVERLAY_ROUTINE_KEY_INVALID`
  (function / procedure entry without `name(direction:type,...)`),
  `RENAME_OVERLAY_ROUTINE_SIGNATURE_MISMATCH` (from / to signatures
  differ). The `MigrationOverlayValidator` `objectType` whitelist
  is widened to `{table, column, view, trigger, function,
  procedure, sequence}`; `materialized_view` stays blocked.

  Sequence-default reprojection: when a plan combines
  `RenameSequence(old → new)` with `CreateTable` / `AddColumn` /
  `AlterColumnDefault` ops that carry `DefaultValue.SequenceNextVal("old")`,
  the new `SequenceDefaultReprojector` rewrites the column-default
  references to `"new"` and the `DependencyAnalyzer` recognises
  `RenameSequence` as a sequence-provider so the topological sort
  places the rename strictly before the column-bearing ops. The
  reprojection is order-independent — the mapper may emit
  `CreateTable` before `RenameSequence` and the reprojector still
  walks the full list once.

  Plan-artifact contract: `migration-plan.v1` gains an optional
  versioned `renameProjections[]` field carrying `candidateId`,
  `objectType`, `fromPath` / `toPath`, overlay provenance,
  `renameOperationId` (null → fallback) and
  `fallbackOperationIds` + `fallbackReason`. The payload is gated
  behind a new semantic extension
  `MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1 =
  "rename-projections.v1"`. Producers MUST call
  `MigrationPlanArtifact.withRenameProjectionExtension()` before
  signing (the validator surfaces
  `PLAN_ARTIFACT_RENAME_PROJECTIONS_REQUIRE_EXTENSION` otherwise);
  consumers that do not list the extension in their supported set
  reject the artifact via the existing
  `PLAN_ARTIFACT_UNKNOWN_SEMANTIC_EXTENSION` blocker. Old
  consumers are therefore forced to reject artifacts they cannot
  read correctly rather than running the Drop+Create fallback as
  an ordinary destructive change.

  New code carriers (hexagon:core):
  `DiffOperation.RenameView`/`RenameTrigger`/`RenameFunction`/
  `RenameProcedure`/`RenameSequence` (with `bodyHash` /
  `signature` payloads where required); `RenameProvenance` (now
  public — it is exposed via the public `DiffOperation` data
  classes); `RenameSupport` sealed interface; `ObjectRenamePolicy`
  + `ObjectRenamePolicyRegistry`; `ObjectRenameCandidate`;
  `RenameObjectMapper`; `SequenceDefaultReprojector`;
  `MigrationPlanArtifactRenameProjection`;
  `MigrationPlanArtifactFeatures`. All ten `Create*`/`Drop*` ops
  for view / trigger / function / procedure / sequence gain an
  optional `renameProvenance: RenameProvenance? = null` field
  (internal metadata; the public artifact projection is via
  `renameProjections[]`). New `MigrationBlockedReason` enum value
  `OBJECT_RENAME_UNSUPPORTED` (ordinal 10, appended at the tail —
  earlier ordinals stay pinned).

  CLI surface: no new `--rename-{view,trigger,…}` inline shortcuts
  in this slice. The canonical keys (trigger `table::name`, routine
  `name(direction:type,...)`) are handled more cleanly in
  `--migration-overlay <file>` payloads than on the command line;
  inline shortcuts may follow if operator demand justifies the
  argument-parser complexity.

  Plan-Doc: `docs/planning/done-archive/ImpPlan-0.9.7-F.4-routine-trigger-view-renames.md`.

- **0.9.7 E.2 Trigger-Rendering Vollscheibe** — `CreateTrigger`,
  `ReplaceTrigger` and `DropTrigger` leave `OpCategory.UNSUPPORTED` in
  all three dialects. PostgreSQL renders strict `CREATE TRIGGER ...
  EXECUTE FUNCTION <ref>` with a `[schema.]identifier(args)` body
  validator (inline PL/pgSQL blocks with
  `TRIGGER_BODY_NOT_FUNCTION_REFERENCE`); Replace uses native
  `CREATE OR REPLACE TRIGGER` on PG-14+, Drop+Create otherwise. MySQL
  renders inline-body triggers without a `DELIMITER` wrapper and a
  bare-name DROP (the `<table>.<name>` form is a MySQL syntax error
  that the renderer never produces); WHEN, statement-level and
  INSTEAD-OF triggers block with dialect-specific
  `MYSQL_TRIGGER_*_UNSUPPORTED` diagnostics. SQLite reuses the
  existing rebuild-pipeline `createTriggerSql` builder for a
  bit-identical `BEGIN ... END` output across standalone and
  rebuild-absorbed paths; the rebuild planner is the authoritative
  filter for trigger ops whose table is being rebuilt. Replace is
  always Drop+Create on MySQL and SQLite.

  Gap contract: every Drop+Create-Replace carries
  `OperationRisk.hasGap = true` (set by the Mapper through a new
  core-local `TriggerPlanningContext`) and emits a
  `W_TRIGGER_REPLACE_GAP` warning on the report. The new
  `--strict-gap-operations` flag on `schema migrate` lifts gap-bearing
  operations to `MANUAL_ACTION_REQUIRED` (Exit 8); the strict path
  emits zero statements and an `OPERATION_HAS_GAP_STRICT_BLOCKED`
  blocker diagnostic.

  Identity safety: a new `TriggerNameCollisionDetector` runs on the
  raw `List<NamedTrigger>` before the trigger map is materialised, so
  two triggers sharing a name across different tables surface as
  `TRIGGER_NAME_COLLISION` instead of silently collapsing. The file
  path relies on Jackson's existing `FAIL_ON_READING_DUP_TREE_KEY`
  (already enabled in `YamlSchemaCodec`) for duplicate-map-key
  detection.

  New code carriers: `TriggerPlanningContext` + `TriggerReplaceMode`
  (hexagon:core, dependency-free); `TriggerCapability` +
  `TriggerCapabilityDefaults` + `resolve(postgresMajorVersion)`
  (hexagon:ports-read); `TriggerPlanningContextFactory`
  (application). `OperationRisk` gains a `hasGap: Boolean = false`
  field consumed by the dialect-neutral strict-gap lift in each
  render context. New `MigrationBlockedReason` codes appended at the
  enum tail: `TRIGGER_NAME_COLLISION`,
  `TRIGGER_BODY_NOT_FUNCTION_REFERENCE`.

  Carve-outs deferred to follow-up slices: DEFINER rendering for
  MySQL, schema-qualified DROP, SQLite trigger reverse-read from
  `sqlite_master`, and the `TriggerDefinition` model widening
  (`events` plural with column lists, `enabledState`). The structural
  `Map<TriggerKey, TriggerDefinition>` migration that would let the
  model hold genuine `(name, table)` ambiguity remains an F.4
  RenameTrigger precondition.

- **0.9.7 Materialized-View-Migrationsvertrag Sub-Slice C** — closes
  the D.3b plan: a new `MaterializedViewDependencyDetector` walks
  both schemas to find MVs whose `dependencies.tables/views/functions`
  point at an object being dropped or replaced in the same plan
  without a matching `Drop`/`Replace` for the MV itself. Each
  `(materialized-view, dropping-op)` orphan-pair becomes a
  `BLOCKED_DEPENDENCY_UNRESOLVED` planner blocker (BLOCKER severity)
  on the dropping op AND a structured
  `MaterializedViewDependencyBlocker` on `DiffResult`. Cross-MV
  dependency (MV-A references MV-B) follows the same rule because
  MVs share the view name-space. `RoutineDependencyAnalyzer`
  recognises MV ops in its drop-side index and create-side edges, so
  the topological sorter places `DropMaterializedView` before the
  matching `DropTable` (PG-without-CASCADE order).

  Report contract: `SchemaMigrateMaterializedViewContractView` gains
  a `dependencyBlockers` list with `(droppingOperationId,
  droppingPath, droppingKind)` per orphan. When the MV has no
  in-plan operation (purely orphaned), the report builder synthesises
  a contract row with `action=ORPHAN` and `operationId` set to the
  first dropping op so the operator sees the orphan in
  `materializedViews[]`. `primaryBlockedReason` becomes
  `MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED`. The precedence table
  appends the new code at position 10 (lowest priority — only fires
  on otherwise-renderable PG paths). JSON and YAML report renderers
  emit the new `dependencyBlockers` subfield.

  No new `DiffOperation` subclasses in this slice; the wiring is
  pure analyzer-and-planner work.

- **0.9.7 Materialized-View-Migrationsvertrag Sub-Slice B** — dedicated
  `DiffOperation.ReplaceMaterializedView` class closes out the
  `OperationMapper.mapViews` routing: a body / columns change on a
  view where both sides stay materialized now routes to the new op
  instead of the legacy `ReplaceView` placeholder. PostgreSQL emits
  the replace as two statements — `DROP MATERIALIZED VIEW <name>;`
  followed by `CREATE MATERIALIZED VIEW <name> AS <after.query>;` —
  sharing the same `operationId` so Workstream-G's
  `executionStatementGroups` treats them as one atomic unit under
  PG's transactional DDL. Down emits the symmetric inverse using
  `before.query`; absent `before.query` blocks with the new
  `MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN` code (Up still
  renders fine — only the rollback contract is affected). MySQL and
  SQLite block the replace via the existing
  `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT` path. The
  `materializedViews[]` report surfaces a `READY` row with
  `stalenessAfterUp=FRESH_AFTER_REPLACE_REFRESH`,
  `refreshSteps=[DROP_CREATE_INITIAL_REFRESH]` and
  `locking=ACCESS_EXCLUSIVE` for renderable PG replaces; the legacy
  `BLOCKED_UNTIL_REFRESH_STALENESS_CONTRACT` placeholder is now
  fully retired.

  **Internal API change**: `DiffOperation` gains one more sealed
  subclass (`ReplaceMaterializedView`). Embedders / extension code
  that pattern-matches against `DiffOperation` must triage it or the
  Kotlin compiler rejects the `when` as non-exhaustive.

- **0.9.7 Materialized-View-Migrationsvertrag Sub-Slice A** — dedicated
  `DiffOperation.CreateMaterializedView` / `DropMaterializedView`
  classes plus a new `DiffObjectType.MATERIALIZED_VIEW` enum value.
  PostgreSQL renders `CREATE MATERIALIZED VIEW <name> AS <query>` and
  `DROP MATERIALIZED VIEW <name>` (Down inverses each other; Drop-Down
  requires the original `query` body, otherwise it blocks with
  `MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN`). MySQL and SQLite emit a
  dialect-specific `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT` blocker
  instead of the previous generic D.3a diagnostic. The
  `materializedViews[]` report carrier now lists concrete `status` /
  `stalenessAfterUp` / `refreshSteps` / `locking` / `rollback` values
  (`READY` + `FRESH_AFTER_INITIAL_REFRESH` for Create, `READY` +
  `NOT_APPLICABLE_DROP` for Drop on PostgreSQL; `BLOCKED_*` codes plus
  the new `primaryBlockedReason` field elsewhere). Operator-visible
  `View↔MaterializedView` conversions are now deterministically blocked
  with `BLOCKED_CONVERSION_UNSUPPORTED`.

  **Internal API change**: `DiffOperation` is a sealed interface;
  embedders / extension code that pattern-matches against it must
  triage the two new subclasses or the Kotlin compiler will reject the
  `when` as non-exhaustive. The non-MV renderer default is a block with
  `MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT`. `Replace`-style
  materialized-view changes still fall through to the legacy
  `ReplaceView` path with the existing D.3a guard
  (`MATERIALIZED_VIEW_DIFF_UNSUPPORTED`); the dedicated
  `ReplaceMaterializedView` op lands in Sub-Slice B.

- **0.9.7 Routine-Capability-Configurable-Source** — operator override
  for the per-routine-kind capability of stored Functions/Procedures.
  Adds the repeatable `--routine-capability=<kind>:<key>=<value>[,...]`
  CLI flag and a `routineCapability:` section in `.d-migrate.yaml`.
  Precedence per routine kind: CLI > YAML > dialect/server-version
  defaults. Allowed kinds: `function`, `procedure`. Allowed keys:
  `enabled` (`true`/`false`), `minServerVersion`
  (`major.minor.patch`, MySQL/MariaDB only — must remain a quoted
  string in YAML to avoid SnakeYAML's float coercion of `8.0`).
  Structurally broken YAML raises `ConfigResolveException` before the
  pipeline runs; semantically invalid input (unknown kind/key,
  unparsable bool or version, duplicate per-kind CLI flag, float
  `minServerVersion`) flows through as
  `EffectiveRoutineCapability.Invalid(reason)`, which the MySQL
  renderer surfaces as `ROUTINE_CAPABILITY_CONFIG_INVALID` +
  `MANUAL_ACTION_REQUIRED` (Exit `8`) with the operator's reason
  string appended to the diagnostic body. PostgreSQL ignores the
  capability today; its `CREATE OR REPLACE` support is unconditional.

  **Internal API change (no user-facing migration)**:
  `DdlGenerationOptions.routineCapability` is now of type
  `EffectiveRoutineCapability` (a sealed interface with `Valid` /
  `Invalid` variants) instead of the previous `RoutineCapability`
  data class. The previous data class is available as
  `EffectiveRoutineCapability.Valid` with identical fields; affects
  only embedders / extension code that constructed
  `DdlGenerationOptions` directly (the CLI surface stays unchanged).

- **E.1 Routine-Migration Slice E** — closes the Slice-A
  reverse-read carve-out: the PostgreSQL schema reader now
  populates `security` / `definer` / `searchPath` for routines
  directly from `pg_proc`. New
  `PostgresProgrammabilityMetadataQueries.listRoutineIdentityAttributes`
  projects `prosecdef`, `pg_roles.rolname` (joined via
  `proowner`, using `pg_roles` instead of `pg_authid` so the
  query works without superuser), and `proconfig` — the latter
  is parsed to extract the `search_path` segment. The projection
  is keyed by `RoutineKey(name, oid)` so same-name overloads
  stay distinct. `readPostgresFunctions` / `readPostgresProcedures`
  consume the projection and assemble the final identity. With
  the carve-out closed, `--generate-rollback` Down-render for
  PG routines now works against a live DB whose routines declare
  `SECURITY DEFINER` + `SET search_path`: the prior body comes
  from `routine_definition`, identity attrs from `pg_proc`, and
  the diff no longer emits a spurious `ReplaceFunction` purely
  because the reverse side was missing the identity attrs.
  `sqlMode` stays null on the PG side — it's a MySQL-only
  concept. The MySQL routine reader is unchanged (routine
  bodies / identity are an MySQL-flavoured carve-out that a
  later slice can revisit).

  This is the final slice of E.1: workstreams A → B → C → D → E
  are complete. MySQL routine rendering, dependency sorting
  across all five object classes, and the engine-verified
  down-body recovery now ship together.

- **E.1 Routine-Migration Slice D.3** — MySQL trigger reader now
  surfaces the owning-table edge into `DependencyInfo` so the
  Slice-D.1 `RoutineDependencyAnalyzer` can build the
  `DropTrigger → DropTable` reverse-topology edge for MySQL
  schemas. `MysqlRoutineReader.readTriggers` sets
  `dependencies = DependencyInfo(tables = listOf(table))` from
  `information_schema.triggers.event_object_table` — the same
  value already used for `TriggerDefinition.table`. No new queries
  were added: the trigger ↔ table edge is read from the existing
  `listTriggers` projection. View ↔ table / view ↔ routine edges
  were already projected via `VIEW_TABLE_USAGE` /
  `VIEW_ROUTINE_USAGE`. Routine ↔ table / view / sequence edges
  stay manifest-based by design — routine bodies are opaque to
  MySQL's information schema, so the operator must declare them
  in `dependencies.functions` / `dependencies.tables` /
  `dependencies.sequences` on the routine's schema entry.

- **E.1 Routine-Migration Slice D.2** — PostgreSQL reverse-read now
  projects routine and trigger dependency edges from
  `pg_depend` / `pg_trigger` directly into the neutral
  `DependencyInfo` carrier consumed by
  `RoutineDependencyAnalyzer` (Slice D.1). New queries in
  `PostgresProgrammabilityMetadataQueries`:
   * `listRoutineRelationDependencies` joins `pg_proc` → `pg_depend`
     → `pg_class` and discriminates by `pg_class.relkind` to
     populate `DependencyInfo.tables` / `views` / `sequences` for
     functions and procedures. Materialized views land in
     `views`; sequences (`relkind = 'S'`) populate the
     Slice-D.1-introduced `sequences` list.
   * `listTriggerFunctionDependencies` joins `pg_trigger.tgfoid`
     → `pg_proc.oid` and populates `DependencyInfo.functions` on
     the trigger, so a `DropTrigger` correctly precedes the
     matching `DropFunction` in the reverse-topology sort.
  `readPostgresFunctions` / `readPostgresProcedures` /
  `readPostgresTriggers` consume the new queries and attach
  `DependencyInfo` when projection rows exist. The existing
  reverse-read carve-out for identity attributes
  (security/definer/searchPath/sqlMode) is unaffected; D.2
  ships only the dependency-edge data. MySQL adapter behaviour
  is unchanged.

- **E.1 Routine-Migration Slice D.1** — manifest-driven cross-
  object dependency edges for the file-to-file path. New
  `RoutineDependencyAnalyzer` (`hexagon:core/.../diff/migration/`,
  `internal object`) runs as a second pass after the existing
  `DependencyAnalyzer`. It adds Create/Replace-side edges from
  routines / views / triggers onto their referenced tables /
  views / functions / procedures / sequences (via each
  definition's `DependencyInfo`), plus reverse-topology Drop
  edges so a `DropView` runs before the matching `DropTable`,
  a `DropTrigger` before the `DropFunction` it referenced, etc.
  Triggers also pick up an unconditional edge onto their owning
  table via `trigger.table`. `DependencyInfo` gains a
  `sequences: List<String>` field; the codec only emits the
  YAML key when non-empty so older schema-file goldens stay
  byte-identical. `DiffPlanner` integrates the analyzer between
  the FK pass and the topological sorter. Two routines that
  co-exist in a plan without a manifest edge in either direction
  surface as `UNSAFE_DEPENDENCY_PAIR` WARNING diagnostics (the
  D.1 follow-up downgraded from BLOCKER so file-only multi-
  routine plans aren't locked out by default; the D.4 topology
  evaluator handles the actual routing decision — see the
  Slice D.4 entry). The existing
  generic `DEPENDENCY_CYCLE` code stays as the cycle marker; the
  plan's earlier `ROUTINE_DEPENDENCY_CYCLE` proposal collapses
  into it because the cycle detector is class-agnostic anyway.
  `DependencyAnalyzer` file KDoc updated: the Phase-D carve-outs
  for Drop-side ordering, Replace-body deps, and Trigger
  ordering are now closed; materialized-view refresh remains
  out of scope. PostgreSQL and MySQL renderers stay
  byte-identical (no Slice A/B/C test churn).

- **E.1 Routine-Migration Slice C.3** — Dependency-Guard stub +
  `DROP + CREATE` fallback for the MySQL renderer. New
  `DependencyGuard` enum (`SAFE`/`UNSAFE`/`UNKNOWN`) and
  `DependencyGuardEvaluator` live in `hexagon:ports-read`. The
  evaluator uses an explicitly conservative stub heuristic: a
  routine operation is `SAFE` iff it is the only op in the plan;
  any co-resident op flips the guard to `UNSAFE`. Slice D will
  replace the body with a real topology evaluator; the public
  contract stays stable. The MySQL routine renderer's
  `Disabled`-capability path now consults the guard: `SAFE` emits
  `DROP` + `CREATE` (two statements, no `OR REPLACE`); `UNSAFE`
  or `UNKNOWN` keep the previous `ROUTINE_CAPABILITY_DISABLED` +
  `MANUAL_ACTION_REQUIRED` blocker. Every guard consultation
  emits a `DEPENDENCY_GUARD_HEURISTIC` INFO diagnostic so reports
  show that the routing came from the stub, not a topology proof.
  `MysqlDiffRenderContext` gains a `plan: DiffResult?` field so
  the routine renderer can ask the evaluator without changing the
  public renderer API.

- **E.1 Routine-Migration Slice C.2** — MySQL-family function and
  procedure renderer joins PostgreSQL in the diff/render pipeline.
  New `MysqlDiffRoutineOps` covers `Create`/`Replace`/`Drop` of
  `FUNCTION` and `PROCEDURE` in both Up and Down direction. The
  body is emitted verbatim — no `DELIMITER` wrapper — so the
  canonical plan artefact remains a single structured statement;
  display variants may add a `DELIMITER` wrapper in a later slice.
  `MysqlDiffDdlGenerator` gains an `OpCategory.ROUTINE` dispatch.
  Capability-gated `CREATE OR REPLACE`: the renderer consults
  `DdlGenerationOptions.routineCapability` and the live
  `mysqlServerVersion` from `MysqlSchemaReader`. Post-F.11,
  Oracle MySQL defaults to `Disabled`, while live MariaDB targets
  resolve to `Active`. `Active` ⇒ `CREATE OR REPLACE`; `Disabled`
  (Oracle MySQL, capability off, or `minServerVersion` floor unmet)
  routes through the dependency guard and either emits `DROP` +
  `CREATE` or blocks; the reserved defensive `InvalidConfig` branch
  yields `ROUTINE_CAPABILITY_CONFIG_INVALID` +
  `MANUAL_ACTION_REQUIRED` once a configurable capability source can
  make that state reachable.
  Down-render blocks with
  `ROUTINE_DOWN_BODY_UNKNOWN` when the prior body is missing.
  `SchemaReadResult` carries a new `mysqlServerVersion` field
  populated by `MysqlSchemaReader` via
  `MysqlMetadataQueries.readServerVersion()`; the probe is best-
  effort (a privilege error on `VERSION()` falls back to null
  instead of failing the read). `ResolvedSchemaOperand` and the
  render pipeline thread the version through to
  `DdlGenerationOptions`. PostgreSQL renderers and tests stay
  byte-identical.

- **E.1 Routine-Migration Slice C.1.a** — Capability + Debug-Body
  infrastructure for the upcoming MySQL routine renderer (Slice C.2).
  New types `RoutineCapability`, `RoutineKindCapability`,
  `RoutineCapabilityResolution` (`Active`/`Disabled`/`InvalidConfig`),
  `RoutineCapabilityDefaults`, `RoutineBodyDisplay` and
  `MysqlServerVersion` (with parser for `8.0.36-log` / `5.7.44` /
  `10.11.6-MariaDB` / `8.4.0`) live in `hexagon:ports-read`; the
  layering choice keeps `hexagon:core`'s zero-dep contract intact.
  `MysqlMetadataQueries.readServerVersion(JdbcOperations)` exposes
  the live MySQL version. `SchemaMigrateRequest` gains a
  `debugBody: Boolean = false` field; `SchemaMigrateReport` gains
  `bodyDisplay: RoutineBodyDisplay = SCRUBBED_ONLY`. `schema migrate
  --debug-body` flips the report's display plane to `RAW_DEBUG`.
  `schema rollback` does NOT carry a `--debug-body` flag — the
  rollback runner always redacts its `executionError` output via
  `RoutineBodyLogRedactor` (see Slice F.1). Execution-Plane (the
  in-memory statements that the `--execute` path runs against the DB)
  is unaffected and always carries raw bodies. No renderer consumes
  the capability in C.1.a; PostgreSQL Slice A/B output stays
  byte-identical. Slice C.2 wires the MySQL renderer; Slice C.3 adds
  the Dependency-Guard.
- **E.1 Routine-Migration Slice B** — PostgreSQL procedures join
  functions in the diff/render pipeline. `ProcedureDiff` gains the
  same `security` / `definer` / `searchPath` / `sqlMode` value-change
  fields that Slice A added to `FunctionDiff`; the comparator now
  hash-compares procedure bodies through `RoutineBodyNormalizer` so
  cosmetic CRLF / trailing-semicolon / line-ending changes no longer
  trigger a spurious `ReplaceProcedure`. A new
  `PostgresDiffProcedureOps` renderer emits `CREATE PROCEDURE` /
  `CREATE OR REPLACE PROCEDURE` / `DROP PROCEDURE` with dollar-quoted
  bodies, parameter lists, optional `LANGUAGE` / `SECURITY` /
  `SET search_path` clauses, and PostgreSQL's signature contract for
  DROP (OUT params dropped, INOUT prefixed). `--generate-rollback`
  blocks `ReplaceProcedure` Down with
  `ROUTINE_REPLACE_DOWN_BODY_UNKNOWN` + `ROLLBACK_NOT_POSSIBLE` when
  the prior body is unknown; with a known prior body it renders
  `CREATE OR REPLACE PROCEDURE` reverting to the before-body.
  `ROUTINE_BODY_DOLLAR_TAG_COLLISION` guards against bodies that
  contain the renderer's `$body$` tag. Trigger ops remain blocked
  with `DIALECT_UNSUPPORTED_OPERATION` until Slice E.2; MySQL routine
  rendering and routine dependency sorting stay scheduled for Slice
  C/D in the same ImpPlan.
- **E.1 Routine-Migration Slice A** — PostgreSQL functions are now
  diffable and renderable.
  `FunctionDefinition` / `ProcedureDefinition` gain optional
  `security` / `definer` / `searchPath` / `sqlMode` fields; the new
  `RoutineSecurity` enum (`INVOKER`/`DEFINER`) is part of routine
  identity. Body equality is decided through
  `RoutineBodyNormalizer` (LF collapse, line-trailing whitespace
  strip, single trailing semicolon removal) plus a SHA-256 hash —
  cross-platform schema files no longer trip spurious
  `ReplaceFunction` ops on CRLF / trailing-newline differences. The
  PostgreSQL renderer (`PostgresDiffFunctionOps`) emits
  `CREATE FUNCTION` / `CREATE OR REPLACE FUNCTION` / `DROP FUNCTION`
  with dollar-quoted bodies (`$body$`), parameter lists, return
  types, language, and optional `SECURITY` / `SET search_path`
  clauses. `--generate-rollback` blocks `ReplaceFunction` Down with
  `ROUTINE_REPLACE_DOWN_BODY_UNKNOWN` + `ROLLBACK_NOT_POSSIBLE` when
  the prior body is unknown (file-to-file with body omitted); when
  the prior body is known (file-to-DB reverse read or schema file
  with full before-body), Down renders `CREATE OR REPLACE FUNCTION`
  reverting to the prior body. Procedure / trigger ops remain
  blocked with `DIALECT_UNSUPPORTED_OPERATION` until Slice B / E.2.
  Reports surface bodies through
  `RoutineBodyScrubber.preview(...)` — a
  `{hash, length, scrubbedPreview, scrubbingApplied}` shape that
  masks password / token / api-key / JDBC-URL credentials before any
  preview lands in a diagnostic or artefact. MySQL routine
  rendering, dependency sorting between routines / views / triggers
  / tables, and Slice E down-render improvements stay scheduled for
  later slices in the same ImpPlan.
- **F.4 cli-inline-overlay** — `schema migrate` accepts two new
  repeatable flags for inline rename mappings:
  - `--rename-table <from>:<to>`
  - `--rename-column <table>.<from>:<table>.<to>`
  The CLI builds a synthetic `migration-overlay.v1` document with
  `source = "cli-inline"` and a stable sentinel
  `createdAt = "cli-inline"` so two identical invocations produce a
  bit-identical `overlayHash` (and therefore identical
  `Rename*` operation ids and statement stream) regardless of
  wall-clock. The synthetic document runs through the exact same
  validator/preflight/mapper pipeline as a file-loaded overlay.
  Cross-document conflicts (file vs inline or file vs file) block
  before `DiffPlanner.plan(...)` via a new Pre-Plan cross-document
  uniqueness gate in `MigrationOverlayPreflight.validateBeforePlan`,
  emitting `OVERLAY_RENAME_MAPPING_DUPLICATE` /
  `OVERLAY_RENAME_MAPPING_AMBIGUOUS` with every conflicting
  `source`/`entryId` pair; the reason-classifier folds them into
  `RENAME_MAPPING_INVALID`. Parse-time CLI errors (bad syntax,
  duplicate `from` within the same invocation,
  mismatched table prefix, forbidden SQL-quoting chars) exit 2
  before any DB / I/O happens. Inline overlays are deliberately NOT
  artefact-stable — they're a runtime-only operator shortcut and
  must NOT be serialised into a public `migration-plan.v1` artefact;
  use `--migration-overlay` with a file for long-lived plans.
  `MigrationOverlayDiagnostic` carries the validator's own
  fact-bearing message text downstream (no more synthesised "failed
  F.0 contract validation"), and `OVERLAY_ACCEPTED` INFO provenance
  rows surface in `overlays[]` for entries that pass without
  blockers so report consumers can attribute each rename back to its
  flag slot or file entry. `spec/cli-spec.md` §6.1 lists the new
  flags; Plan-2 §10 F.4 carve-out updated.
- **F.4 rename-mapping-invalid-enum** — new
  `MigrationBlockedReason.RENAME_MAPPING_INVALID` (last enum value;
  existing ordinals unchanged). The application-layer
  reason-classifier in
  `MigrationOverlayPreflight.buildFailureResult(...)` now groups
  blocker findings by reason using **structured** overlay context
  (`MigrationOverlayDiagnostic.entryKind` /
  `renameObjectType`) — no free-form message or entry-ID parsing.
  All five `OVERLAY_RENAME_MAPPING_*` blocker codes plus
  `OVERLAY_UNKNOWN_ENTRY_KIND` tagged with a rename-mapping
  `objectType` outside the current `{table, column}` whitelist
  surface as `RENAME_MAPPING_INVALID`; mixed preflights with a
  rename-bound and a generic overlay blocker emit two
  `MigrationBlocker`s with `primaryBlockedReason =
  RENAME_MAPPING_INVALID`. `MigrationOverlayValidationContext`
  gains `supportedRenameObjectTypes` (default `{"table","column"}`);
  `MigrationOverlayPreflight.validateBeforePlan(...)` exposes the
  same set as a parameter so the later View-/Trigger-/Routine-Rename
  slice can widen the whitelist additively.
  **Backward-Compat**: bestehende Reports mit
  `MANUAL_ACTION_REQUIRED` fuer Rename-Codes bleiben semantisch
  blockiert; nur die Klassifikation hat sich verfeinert.
  `migration-plan.v1` traegt keine `MigrationBlockedReason` und ist
  daher unveraendert. `spec/cli-spec.md` §6.1 dokumentiert den
  neuen Exit-8-Fall.
- **F.4 dependency-projection (T1–T6)** — overlay-bound rename
  candidates now fold to native `RenameTable` / `RenameColumn`
  operations plus synthesised intra-object delta operations
  (`AlterColumn*`, `AddColumn`, `AddIndex`, …) AND explicit
  view-reprojection (`DropView` + `CreateView` from desired body).
  Per-dialect `RenameDependencyPolicy` (Postgres / MySQL / SQLite)
  gates engine-automatic vs explicit vs blocked dependencies.
  Migrate report gains a `renameProjections[]` section (one entry
  per candidate, success or drop+add fallback) — see
  `spec/cli-spec.md` §6.1. Plan-2 §10 F.4 has the status update.
  **Artefakt-Gate decision:** `renameProjections` is report-only;
  it is NOT serialised into `migration-plan.v1`. Today's rollback
  artefact is Down-SQL — the renderers produce natural inverses for
  every synthesised intra-object delta (`AlterColumn*`, `AddColumn`,
  `AddIndex`, …) and for the explicit view reprojection
  (`DropView` + `CreateView` reverse to `DropView` + `CreateView` of
  the previous body), so `--generate-rollback` works without a
  versioned plan artefact. Adding a `ROLLBACK_NOT_POSSIBLE` gate for
  persisted Mischfall-Plaene stays a future tranche — once
  `migration-plan.v1` gains the `renameProjections` field, the gate
  fires for plans that lack it.
- **`adapters:driven:text-icu` module** with `IcuUnicodeTextService`
  — production ICU4J implementation behind the new
  `dev.dmigrate.text.UnicodeTextService` port in
  `hexagon:ports-common`. CLI and MCP composition roots inject this
  service; `hexagon:application` no longer depends on
  `com.ibm.icu:icu4j` directly.
- **`FakeUnicodeTextService` test fixture** in
  `hexagon:ports-common` (JDK-only, backed by `java.text.Normalizer`
  and `BreakIterator`) so application-side tests stay free of the
  ICU4J runtime.

### Changed

- **0.9.7 Port-Refactor — `DdlDialectContext`** *(2026-05-27)* —
  `DdlGenerationOptions` trug bislang nullable `mysql*` /
  `sqlite*`-Felder parallel am Top-Level (`mysqlNamedSequenceMode`,
  `mysqlServerVersion`, `mysqlSequenceCanonicity`, `routineCapability`
  mit MySQL-flavored Default, `liveSqliteCatalog`,
  `sqliteCastPreflights`, `catalogProbeMode`). Diese sind jetzt in
  einen sealed `DdlDialectContext` mit Varianten `None`, `MySql(...)`,
  `Sqlite(...)` gewandert. Renderer/Tests greifen über die neuen
  Extension-Properties `options.mysqlContext` / `options.sqliteContext`
  zu. Dialekt-neutrale Felder (`spatialProfile`, `executionMode`,
  `checkPreflights`, `extension*`, `strictGapOperations`,
  `generatedAt`, `deterministic`, `deferForeignKeys`) bleiben am
  Top-Level. 30 Files migriert, semantisch identisch.

  Plan-Doc: `docs/planning/done-archive/sqlite-sequence-emulation-plan.md`
  Phase B.0; Memory-Pin: `feedback_hexagon_dialect_context`.

- **0.9.7 E.2 Folge-Slice — SQLite Trigger Reverse-Read
  (Sub-Slices A–E)** *(2026-05-22)* — neuer token-basierter
  `SqliteTriggerSqlParser` ersetzt den regex-/substring-basierten
  `SqliteTypeMapping.parseTriggerSql`. Reverse-Read aus
  `sqlite_master.sql` populiert jetzt `forEach`, `condition`
  (WHEN-Klausel) und einen Round-Trip-symmetrischen `body` (genau
  ein trailing `;` vor `END` wird gestrippt, weil der Renderer
  unconditional `;\nEND;` anhängt — sonst driften Bodies nach
  ein paar Cycles zu `...;;`).

  Diagnostics:
    - `R210` / `R211` werden zu `ACTION_REQUIRED` (vorher `WARNING`),
      wenn die `CREATE TRIGGER`-Grammatik nicht parsebar ist.
    - Neu: `R212` (`ACTION_REQUIRED`) für schema-qualifizierte
      Trigger-Namen oder Target-Tabellen (`main.trg`, `aux.t`).
      Betroffene Trigger werden aus dem Reverse-Read-Ergebnis
      ausgeschlossen, kein `TriggerDefinition` wird gebaut.
    - Neu: `R213` (`WARNING`) für `UPDATE OF <cols>`-Spaltenliste —
      das neutrale Modell behandelt UPDATE als whole-row.

  Auswirkung: `schema compare` zwischen zwei SQLite-Live-DBs mit
  identischen WHEN-getragenen Triggern emittiert keine spurious
  False-Positive-Diffs mehr; `schema migrate` (file-to-DB) gegen
  SQLite triggert keinen `Drop+Create`-Replace mehr, wenn das
  Source-File und das Live-DB-Trigger semantisch übereinstimmen.

  **Breaking-Change-Note**: alte Live-DBs mit unparseable
  Trigger-DDL surfen jetzt als `ACTION_REQUIRED` statt `WARNING`.
  Tooling, das `notes.severity` filtert, sollte beide Severities
  akzeptieren, bevor 0.9.7 ausgerollt wird.

  Plan-Doc:
  `docs/planning/done-archive/ImpPlan-0.9.7-sqlite-trigger-reverse-read.md`.

- **0.9.7 E.2 Trigger-Rendering — data-class extensions on
  published types**: `OperationRisk` (hexagon:core) gains a new
  `hasGap: Boolean = false` field; `MigrationBlockedReason`
  (hexagon:ports-read) appends two enum values at the tail
  (`TRIGGER_NAME_COLLISION`, `TRIGGER_BODY_NOT_FUNCTION_REFERENCE`).
  Source-compatible for callers that use named arguments; **callers
  that use positional `OperationRisk(...)` constructors or
  positional `.copy(...)` must migrate to named arguments** because
  the `hasGap` field sits before the trailing
  `notes: List<DiffDiagnostic>` field. Existing enum ordinals stay
  stable per the documented append-at-end convention; the
  `MigrationDdlResultTest` enum-order pin (`RENAME_MAPPING_INVALID`
  ordinal = 7) was extended to assert the new entries at indices 8
  and 9. The
  `SqliteDiffSqlBuilders.createTriggerSql(...)` builder — shared
  between the new standalone trigger renderer and the existing
  Phase H.3a rebuild-pipeline trigger-recreation path — now
  normalises a trailing `;` out of the body before wrapping in
  `BEGIN..END;`. Rebuild-pipeline output stays bit-identical to the
  standalone renderer; goldenness fixtures that asserted via
  `startsWith("CREATE TRIGGER ...")` are not affected, but any
  fixture that pinned an embedded trailing-`;` body will see one
  less terminator.

- **E.1 Routine-Migration Slice F.11** — MySQL-family routine
  capability is now vendor-aware. The neutral `MYSQL` dialect
  defaults to Oracle MySQL semantics, where stored routines do not
  support `CREATE OR REPLACE`; file-to-file MySQL routine replaces
  therefore use the existing dependency-guarded `DROP` + `CREATE`
  fallback instead of emitting invalid Oracle MySQL SQL. Live
  MariaDB targets are detected through `MysqlServerVersion.vendor`
  (`SELECT VERSION()` values such as `10.11.6-MariaDB`) and keep
  `CREATE OR REPLACE` enabled. `DdlGenerationOptions` defaults to
  the conservative Oracle MySQL capability so direct renderer calls
  are safe unless tests or future config explicitly opt into the
  MariaDB capability.

- **E.1 Routine-Migration Slice D.4** — `DependencyGuardEvaluator`
  body now drives the SAFE / UNSAFE decision from the real
  dependency-edge graph populated by Slices D.1 / D.2 / D.3
  instead of the Slice-C.3 "any co-resident op == UNSAFE"
  stub heuristic. An op is `SAFE` when no other op in the plan
  has a declared incoming or outgoing edge to it; any declared
  edge in either direction flips it to `UNSAFE`. Cross-plan
  edge ids (already dropped by the topological sorter as
  unresolvable) are ignored. The public signature
  `evaluate(plan, op)` is unchanged, so the MySQL renderer's
  consult sites compile without edits.

  The MySQL renderer's INFO-severity guard annotation switches
  from `DEPENDENCY_GUARD_HEURISTIC` to
  `DEPENDENCY_GUARD_TOPOLOGY` to reflect that the bewertung is
  no longer a stub. `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC`
  stays active — implicit-commit atomicity is orthogonal to
  the evaluator change.

  Practical consequence: the Slice-C.3 test that pinned
  "ReplaceFunction blocks when ANY co-resident op is in the
  plan" no longer makes sense — the stub was the only reason
  that path blocked. It is replaced by two D.4 tests:
   * one constructs a true `dependencies.tables` edge from the
     routine to a co-resident `CreateTable` and pins that the
     evaluator finds the edge, returns UNSAFE, and the renderer
     blocks with `ROUTINE_CAPABILITY_DISABLED`;
   * the other keeps the same plan shape but removes the
     declared edge, pins that the evaluator returns SAFE, and
     the renderer falls back to `DROP + CREATE` with the
     non-atomicity warning.

  `DependencyGuardEvaluatorTest` is rewritten end-to-end:
  isolated → SAFE, co-resident-without-edges → SAFE,
  outgoing-edge → UNSAFE, incoming-edge → UNSAFE,
  cross-plan-edge-id → ignored, mixed-routine-kind without
  edges → SAFE. Plan-2 §9 E.1 status updated to mark D.4
  landed.

- **E.1 Routine-Migration Slice C.1.b** — PostgreSQL function and
  procedure renderers emit the canonical `ROUTINE_DOWN_BODY_UNKNOWN`
  diagnostic code instead of the older Replace-specific
  `ROUTINE_REPLACE_DOWN_BODY_UNKNOWN` when `--generate-rollback`
  blocks because the prior routine body is unknown. The change is
  diagnostic-only: blocker reason (`ROLLBACK_NOT_POSSIBLE`),
  message text, and SQL output stay identical. Test pins in
  `PostgresDiffFunctionOpsTest` / `PostgresDiffProcedureOpsTest`
  and the `--generate-rollback` row in `spec/cli-spec.md` §6.1
  follow. The Replace-Up variant `ROUTINE_REPLACE_UP_BODY_UNKNOWN`
  stays as-is — a missing after-body on Replace is structurally
  different from a missing rollback target. Plan §1 sees the
  generic code as authoritative; MySQL slices (C.2+) will emit
  only the generic code from the start.

- **`JsonCanonicalizer`**: converted from `internal object` to
  `internal class JsonCanonicalizer(unicodeText: UnicodeTextService)`
  so JCS NFC normalization runs through the port instead of a
  static ICU4J facade.
- **`DefaultPayloadFingerprintService`** and **`OutputFormatter`**
  take `UnicodeTextService` as a required constructor argument.
- **`spec/architecture.md`**: ICU4J now belongs to
  `adapters/driven/text-icu`, not `application/cli`.

### Fixed

- **0.9.7 F.4 Renderer-Blocker-Bridge — preserve `OBJECT_RENAME_UNSUPPORTED`
  as `primaryBlockedReason`** *(2026-05-19)* — `PostgresDiffRenderContext`,
  `MysqlDiffRenderContext` and `SqliteDiffRenderContext` used to wrap
  every planner-emitted BLOCKER diagnostic into a single
  `MigrationBlocker(reason = DIALECT_UNSUPPORTED_OPERATION)`,
  collapsing the F.4-specific
  `MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED` reason that the
  Mapper / Planner had set per F.4 plan-doc §5.2.

  The fix introduces a `PlannerBlockerClassifier` in
  `hexagon:ports-read` that maps `DiffDiagnostic.code →
  MigrationBlockedReason` with the initial entry
  `"OBJECT_RENAME_UNSUPPORTED" →
  MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED` and a
  conservative default to `DIALECT_UNSUPPORTED_OPERATION` so legacy
  codes (`CONSTRAINT_NOT_DIFFABLE`,
  `MATERIALIZED_VIEW_DIFF_UNSUPPORTED`, etc.) keep their pre-F.4
  contract. The three render contexts now group planner-blockers
  by classified reason and emit one `MigrationBlocker` per reason,
  so a Materialized-View-Rename / body-drift / missing-body rename
  candidate surfaces `primaryBlockedReason = OBJECT_RENAME_UNSUPPORTED`
  end-to-end while a co-existing `CONSTRAINT_NOT_DIFFABLE` keeps its
  legacy `DIALECT_UNSUPPORTED_OPERATION` reason.

  **Breaking-change-note for downstream consumers**: the report
  field `summary.primaryBlockedReason` for F.4
  materialized-view-rename and body-drift routine/trigger
  scenarios now reads `OBJECT_RENAME_UNSUPPORTED` instead of
  `DIALECT_UNSUPPORTED_OPERATION`. Existing tooling that pinned
  the old reason as primary for these scenarios needs to widen its
  expectation. The H.3 audit found no in-tree consumer that did so,
  and the enum value
  `MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED` has existed
  since F.4 A.1 (2026-05-18) — only the wiring through the
  renderer wrap was missing.

  New tests:
    - `PlannerBlockerClassifierTest` (`hexagon:ports-read`) pins the
      mapping + the public `OBJECT_RENAME_UNSUPPORTED_CODE`
      constant.
    - `PostgresDiffObjectRenameTest` / `MysqlDiffObjectRenameTest` /
      `SqliteDiffObjectRenameTest` each get a sibling case that
      drives the full `planner.plan(...) → gen.generateUp(...)`
      pipeline through a mapper-only-block scenario
      (materialized-view rename on PG, trigger-body-drift on
      MySQL / SQLite — both surface
      `primaryBlockedReason = OBJECT_RENAME_UNSUPPORTED`).
    - `SchemaMigrateRunnerTest` gets an end-to-end pin that the
      report-emit pipeline carries the new primary reason through
      to stdout.

  §11 DoD Box (b) Carve-out (committed in `ffc6970e`) is resolved.
  Plan-Doc: `docs/planning/done-archive/ImpPlan-0.9.7-F.4-renderer-blocker-bridge.md`.

- **0.9.7 E.3 Folge-Slice — MySQL Sequence Drift-Check
  Plan-Compliance-Fixes** *(2026-05-20)* — zwei Verhaltensabwei-
  chungen zwischen Plan-Doc und Implementierung des Drift-Checks
  korrigiert:
  - `MysqlSequenceCanonicityGate` routet jetzt
    `MISSING + DROP` für `SEQUENCE_ROW` und `SUPPORT_TABLE` als
    Block (`E124_MYSQL_SEQUENCE_MISSING_FOR_DROP` →
    `MANUAL_ACTION_REQUIRED`). Plan-Doc §3.1 hatte das schon
    immer als "Missing → UPDATE/DELETE-Path: Block"
    deklariert; Renderer war auf `OpIntent.DROP` schon korrekt
    verdrahtet, das Gate hat es aber durchgewunken. Routinen
    und Column-Trigger fallen weiterhin auf Proceed durch, weil
    das `DELETE FROM dmg_sequences` sie nicht braucht und das
    column-trigger-Drop ohnehin idempotent ist.
  - `MysqlSequenceCanonicityProbeAdapter` verifiziert
    Routine- und Trigger-Bodies jetzt jenseits des
    Marker-Substrings: der Body zwischen `BEGIN` und letztem
    `END` wird normalisiert (backticks raus, lowercase,
    whitespace kollabiert) und gegen die kanonische Form aus
    `MysqlSequenceEmulationTemplates` verglichen. Driftet ein
    operator-bearbeiteter Routine-/Trigger-Body bei intaktem
    Marker → `body_signature`-Drift mit Preview im Report.
    Für Trigger zusätzlich `sequence_reference`-Check: der
    tatsächliche `dmg_nextval('…')`-Call muss den Sequence-
    Namen referenzieren, den der Plan erwartet — ein
    operator-verschobener Trigger (Marker stehen lassen, Call
    auf andere Sequence umbiegen) bricht jetzt sichtbar.

- **0.9.7 E.3 Folge-Slice — Drift-Check Trigger-Gate für reine
  Column-Default-Migrationen + Naming-Lift** *(2026-05-20)* —
  Lücke geschlossen, in der `AddColumn` /
  `AlterColumnDefault` mit `SequenceNextVal`-Default ihren
  `DROP + CREATE TRIGGER`-Block ohne Probe-Konsultation
  ausführten, weil `MysqlSequenceCanonicityStage` auf
  `!hasSequenceOps(plan)` short-circuitete: ein
  operator-modifizierter Trigger wurde stillschweigend
  überschrieben. `hasSequenceOps` → `hasSequenceRelatedOps`
  zählt jetzt auch Column-Ops mit `SequenceNextVal`-Default;
  `MigrationPreflightPlanner` emittiert pro solcher Column-Op
  eine SUPPORT_TRIGGER `NOT_RUN_*`-Declaration; der
  Stage-Failure-Stamping pflegt SUPPORT_TRIGGER-Declarations mit
  kanonischem Trigger-Namen. `MysqlSequenceSupportNaming` wurde
  nach `hexagon/ports-read` gehoben, sodass Stage (application),
  Renderer (driver-mysql) und Probe-Runner (driving/cli)
  dieselbe Naming-Quelle verwenden; das driver-side
  `MysqlSequenceNaming` ist nun Facade darüber.

- **0.9.7 E.3 Folge-Slice — Report-Renderer + BLOCKER-
  Diagnostic-Symmetrie** *(2026-05-20)* —
  `SchemaMigrateReportRenderer` emittierte
  `mysqlSequenceCanonicity` weder in JSON noch in YAML, obwohl
  das DTO befüllt wurde — Operatoren sahen den
  Plan-dokumentierten Feldnamen, aber nie die tatsächlichen
  Declarations. Beide Renderer geben das Feld jetzt aus
  (JSON-Projection in eigenem
  `SchemaMigratePreflightRenderers`-Object, damit der
  Main-Renderer unter Detekt's `TooManyFunctions`-Budget bleibt).
  Zusätzlich trägt der Trigger-Drift-Block im
  `AddColumn`/`AlterColumnDefault`-Pfad jetzt einen
  BLOCKER-severity `DiffDiagnostic` (vorher WARNING ohne
  Blocker-Attach), der direkt am `MigrationBlocker.diagnostics`
  hängt — gleiche Report-Semantik wie die Sequence-Op-Pfade.
  `MysqlDiffRenderContext.addBlocker(reason, opIds,
  diagnostics)` und `recordDiagnostic(...)` neu (letzteres
  schreibt in den Diagnostic-Stream ohne `rendered`/`skipped` zu
  berühren, sodass die `rendered ∩ skipped = ∅`-Invariante des
  `MigrationDdlResult` bei vorab emittierter Column-DDL nicht
  verletzt wird).

## [0.9.6] - 2026-05-08

### Added

- **MCP server** (`mcp serve`): production-ready Model Context Protocol
  v1 server over both `stdio` and Streamable HTTP, with
  initialize/capability negotiation, principal context and per-run
  tenant scoping. The full MCP-Server milestone (Phases A–G) lands in
  this release; the surface follows `spec/ki-mcp.md` and
  `spec/mcp-server.md`.
- **MCP read-only schema tools**: `schema_validate`, `schema_generate`
  and `schema_compare` route through the existing validator, DDL
  generators and comparator. Schema sources can be inline payloads,
  file paths or tenant-scoped `schemaRef` artifacts; `schema_compare`
  emits column-level findings with structured `details.before/after`
  and falls back to a scrubbed `diffArtifactRef` when the inline budget
  is exceeded.
- **MCP capability discovery**: `capabilities_list` exposes dialects,
  formats, limits and `executionMeta`. `tools/list` is a stable
  registry; HMAC-sealed cursors paginate the list-tool surface
  (`job_list`, `artifact_list`, `schema_list`, `profile_list`,
  `diff_list`).
- **MCP resource discovery**: standard `resources/list`,
  `resources/templates/list` and `resources/read` are wired against
  typed resource URIs (sealed ADT) with tenant-scope enforcement and a
  no-oracle error policy. `artifact_chunk_get` reads tenant-scoped
  artifacts via 4-segment URIs with HMAC `nextChunkCursor` paging.
- **MCP write tools** (Phase F): `data_import_start` and
  `data_transfer_start` accept connection- and schema-refs, run
  structural pre-idempotency validation, perform schema-ref import
  preflight and join the Phase-E job pipeline. The policy-pflichtige
  `artifact_upload_init` variant carries session metadata, drives an
  approval flow, terminally fails sessions on oversize segments and
  releases init quotas on expiry / finalisation failure.
- **MCP AI tools** (Phase G): `procedure_transform_plan`,
  `procedure_transform_execute` and `testdata_plan` produce signed AI
  artifacts via the new `AiProviderPort`/`NoOpAiProvider`,
  `AiToolOrchestrator`, `AiToolOutcomeStore` and
  `AiArtifactMetadataStore`. `testdata_execute` materialises an
  importable testdata artifact (path A) that is then written via
  `data_import_start`.
- **MCP prompts**: `prompts/list` and `prompts/get` expose a static
  prompt registry with prompt-hygiene secret scrubbing.
- **MCP authorization and audit**: HTTP and stdio authorization
  contracts (capability-based scope tables, principal binding),
  centralised audit wiring with secret scrubbing on every
  `tools/call`, structured error envelope with runtime scrubbing for
  read-only handlers, and `executionMeta.requestId` plumbed through
  every handler.
- **MCP async jobs and idempotency** (Phase E): `schema_reverse_start`,
  `data_profile_start`, `schema_compare_start`, `data_import_start`,
  `data_transfer_start` and `job_cancel`. Includes a typed
  `IdempotencyState` automaton with `FAILED`/`markFailed`,
  `JobStartOrchestrator`, `JobStartTransaction`, durable approval
  challenges, `ApprovedRetryService`, per-worker `JobWorker` ports
  and dispatcher with `OperationCancelSource` mapping
  (cancel reasons including `EXECUTOR_SATURATED` and the standard
  Exit-130 mapping).
- **MCP policy and approval grants**: `ConfiguredPolicyService`,
  `GrantIssuer` with fail-closed and demo modes, file-backed approval
  grants for `mcp serve` and a `mcp approval-grant issue` CLI
  subcommand that mints either `--idempotency-key` or `--approval-key`
  grants. `POLICY_REQUIRED` envelopes carry `approvalRequestId`,
  `correlationKind`, `correlationKey`, `requiredScopes`, `reasons` and
  `payloadFingerprint` (uniformly across job-, upload- and AI-tool
  paths).
- **MCP quotas**: typed quota dimensions including
  `STORED_ARTIFACT_BYTES`, operation-key dimension,
  `parallelSegmentWrites` default, retry-after and owner-tracking,
  COMPLETED-quota swap on artifact materialisation, AI provider
  quota with audit metadata.
- **MCP persistent state** (Phase E2): JDBC/Postgres adapters land in a
  new `persistence-jdbc` module — `JdbcTransactionRunner`,
  `JdbcIdempotencyStore` (regular and `reserveInitResume` paths),
  `JdbcJobStore`, `JdbcJobStartTransaction`, JDBC quota stack
  (`JdbcQuotaService`/`JdbcQuotaReservationOwnerStore`), Flyway V1
  initial migration, contract test suites against Testcontainers
  Postgres. The MCP bootstrap wires server-state persistence end-to-end.
- **MCP async executor** (Phase E3): `BoundedAsyncJobExecutor` with
  `JobExecutorLifecycle`, `JobDispatchAdmission` gate, backpressure
  (`RateLimited` with reason `EXECUTOR_SATURATED`), cancel-while-queued
  in `JobDispatcher` and graceful shutdown. The default
  `SyncExecutor` is preserved; async is opt-in via
  `server.jobs.executor.mode`.
- **MCP file-backed byte stores**: `mcp serve` uses a file-backed
  artifact byte store with stateDir locks, per-key locks, atomic
  `Files.move`/`Files.createLink` visibility, sidecar publish before
  data, content verification and a startup sweep over
  `<stateDir>/assembly/...`. Streaming finalisation via
  `AssembledUploadPayload` + `SchemaStagingFinalizer` keeps memory
  bounded.
- **MCP upload pipeline**: `UploadInitOrchestrator`, single-writer
  `UploadInitClaimStore` with claim-CAS, intent-based scope check,
  dispatch-by-uploadIntent (`job_input` vs schema), `JobInputFinalizer`
  for policy-pflichtige uploads, idempotent final-segment replay,
  finalisation timeout sweeper mapping to `OPERATION_TIMEOUT`,
  `AbortOutcomeStore` and administrative abort path with policy.
- **MCP bundle import** (data_import_start AP-2): multi-table bundle
  import contract — wire schema accepts `tables`, the worker extracts
  the bundle and runs per-table import.
- **DDL determinism**: new `--deterministic` flag on `schema generate`
  and `DdlGenerationOptions.generatedAt`. `AbstractDdlGenerator`
  emits a fixed timestamp or omits the `Generated:` header line.
  `SchemaGenerateRunner` honours `SOURCE_DATE_EPOCH` and exits 2 on an
  invalid value. `TransformationReportWriter` (sidecar reports) shares
  the same policy.
- **PostgreSQL bigint identity**: forward generation and reverse
  engineering of `GENERATED { ALWAYS | BY DEFAULT } AS IDENTITY`
  bigint columns, including an explicit decision to retire the
  `bigserial` shorthand in favour of identity columns.
- **MySQL bigint identity**: reverse engineering preserves
  `BIGINT AUTO_INCREMENT` identity semantics in the neutral schema.
- **Neutral column generation model**: shared core type for identity /
  default-generation metadata across PostgreSQL, MySQL and SQLite
  generators and readers.
- **Index features**: `IndexDefinition` now supports partial-index
  predicates and per-column sort directions; the corresponding DDL
  generators and reverse readers round-trip both.
- **`schema reverse` flags**: `--name` and `--version` set the neutral
  schema name/version directly (e.g.
  `--name bess-ems --version 1.0.0`).
- **Foreign-key reverse output**: `schema reverse` always emits
  named-constraint entries in `constraints[]`, including for
  single-column FKs, so identifiers like
  `optimization_objective_breakdowns_run_id_fkey` survive a
  reverse/generate round-trip. Column-level `references` remains
  supported for hand-written schemas.
- **PostgreSQL composite PK/UNIQUE**: `schema reverse` reads composite
  primary keys and unique constraints via `pg_constraint.conkey`;
  primary-key columns are restored to `required: true`.

### Changed

- **`AbstractDdlGenerator.getVersion()`** returns `0.9.6`.
- **`schema reverse` (PostgreSQL)** no longer emits column-level
  `references` from the reverse path — FKs are always named entries
  in `constraints[]`.
- **`--split=pre-post` FK handling** is now dialect-aware:
  `deferForeignKeys` is enabled only for PostgreSQL. MySQL and SQLite
  no longer lose circular FKs to the deferred-FK fence; circular
  explicit/composite FKs carry full constraint metadata and are
  rendered as complete `ALTER TABLE ... ADD CONSTRAINT`. Deferred FKs
  are emitted only for tables that actually exist in the output, and
  FKs to blocked schema tables are no longer written into post-data.
- **PostgreSQL `CREATE TABLE` ordering** considers composite-FK
  constraints when computing dependency order.
- **`--split=pre-post` PostgreSQL FKs** are stripped from pre-data and
  added back as `ALTER TABLE ... ADD CONSTRAINT` in post-data; output
  parent directories are created on demand.
- **MySQL view dependency contract**: column-level view dependencies
  no longer assume `INFORMATION_SCHEMA.VIEW_COLUMN_USAGE`; without
  introspection privileges or explicit dependencies the affected
  column-level view cases are blocked instead of being silently
  approximated.
- **MCP Schema-Source resolver**: tenant policy aligned with the rest
  of Phase B/C; `mcp serve` consistently routes inline payloads, file
  paths and tenant-scoped `schemaRef` artifacts through one resolver.
- **Driver timeout layer** (Phase E0.7): `PoolSettings` gains
  `statementTimeoutMs`/`networkTimeoutMs`, drivers carry a
  driver-specific `connectionInitSql`, and a common
  `TimeoutDecoratedConnection` layer plus `setNetworkTimeout` is
  applied uniformly across PostgreSQL, MySQL and SQLite Hikari
  factories.
- **MCP plan drift cleanup**: `data_export_start` removed from the
  registry, the HTTP `Accept` header is enforced more strictly, and
  artifact-upload scope is now checked by intent.
- **`schema generate --output --generate-rollback`** emits a
  rollback-DDL header that uses the same deterministic timestamp
  policy as the forward DDL.
- **Cancel-Reason scrubbing** runs end-to-end across reverse, profile,
  import, transfer and compare workers; cancel reasons reaching
  audit/error envelopes are scrubbed before persistence.

### Fixed

- **`schema generate --split=pre-post --generate-rollback`** now exits
  2 with a clear stderr message instead of producing inconsistent
  rollback files.
- **PostgreSQL primary-key columns from `schema reverse`** are emitted
  with `required: true` again (regression introduced when the column
  reader was refactored).
- **`mcp serve` shutdown hook** is unregistered on a normal return so
  multiple lifecycle entries no longer accumulate.
- **MCP upload session races**: transition-mapping race outcomes map
  to typed exceptions; finalize gates `COMPLETED` on artifact
  materialisation; `validateFinalize` is enforced on
  `UploadSessionService.finalize`; segment offsets persist in the
  sidecar (rehash-recovery dropped); divergent artifact-write hashes
  emit a `Conflict`.
- **MCP idempotency**: `IdempotencyStore.claimApproved` is now atomic
  before side effects; in-memory `JobStartTransaction` commits
  idempotency before saving the job; quota releases are race-free
  (no double release); `ApprovedRetryService` integrates with quotas.
- **MCP `job_status_get`** wire payload aligned with the JSON-Schema,
  pinned to a no-oracle store property; `artifact_chunk_get` aligned
  with spec §5.5 including the empty-artefact branch.
- **MCP audit pipeline**: `AuditFields.resourceRefs` populated in
  upload handlers; `AuditFields` plumbed through Phase-E tools; HTTP
  readiness and per-run principal asserted in the integration
  harness.
- **`isAdmin`** correctly bypasses `ScopeChecker.isSatisfied` for
  administrative tools.
- **`E07PostgresTimeoutBench`** decouples `networkTimeout` from
  `statement_timeout` to avoid driver-quirk-driven flakes.
- **`release-homebrew.yml`** passes `--no-build-cache` to the
  release-asset Gradle build (mirroring `build.yml`), so the
  workflow does not hit stale Gradle task-output state on a fresh
  runner. Without the flag, `:hexagon:core:koverVerify` flaked at
  ~75.5% (vs ≥90% required) on the tag run while the parallel
  `build.yml` was green.
- **`MysqlSchemaReaderIntegrationTest`** is aligned with the new
  reverse contract: single-column FKs are preserved as named
  entries in `TableDefinition.constraints` (not
  `ColumnDefinition.references`) — matching the
  PostgreSQL-equivalent test and the `schema reverse` output.

### Security

- **MCP secret scrubbing** is applied at every layer that can serialise
  user input: tool-error envelopes, schema sinks (`SchemaSecretGuard`
  with normalised forbidden-key detection), prompt registry
  (`PromptHygieneService`), AI-provider audit metadata, response
  byte-limit fallback artefacts and cancel-reason text.
- **MCP cursors** (`resources/list`, list tools, artifact chunks) are
  HMAC-sealed and rejected on tenant or chunk-key mismatch.
- **MCP request/response byte limits** are enforced centrally with a
  generic envelope fallback for unbounded payloads, and an
  inline-vs-`artifactRef` byte cap on `resources/read`.
- **Approval-grant CLI** (`mcp approval-grant issue`) requires exactly
  one of `--idempotency-key` or `--approval-key`, so a grant cannot
  ambiguously match both correlation kinds.

### Removed

- **`data_export_start`** removed from the MCP tool registry — exports
  remain CLI-only for 0.9.6 and were never wired in the server path.

## [0.9.5] - 2026-04-24

### Added

- **MySQL profiling schema support**: `data profile` now accepts `--schema`
  for MySQL end-to-end. Schema introspection and profiling queries use the
  requested database name instead of silently falling back to the current
  database.
- **Profiling integration coverage**: the MySQL profiling integration suite now
  exercises cross-database schema selection against a real Testcontainers
  MySQL instance, including a second database created at runtime.

### Changed

- **Shared profiling SQL helpers** now centralize identifier and
  schema-qualified table rendering for PostgreSQL, MySQL, and SQLite
  profiling adapters.
- **`ProfileDatabaseService`** preserves schema information discovered by
  introspection when profiling tables without an explicit `--schema` override.
- **Core quality refactors** split `FilterDslTokenizer` from filter parsing,
  switched driver discovery to `ServiceLoader`, and separated
  `ValueDeserializer`, `DialectCapabilities`, and `SchemaSync` concerns for
  maintainability.
- **Build hygiene**: detekt is enforced before test tasks, compiler warnings
  were cleaned up, and the release/roadmap docs now reflect the 0.9.5 quality
  milestone.
- **`AbstractDdlGenerator.getVersion()`** returns `0.9.5`.
- **Metadata and profiling precedence fixes** ensure explicit connection/schema
  context is preferred consistently when discovering and profiling tables.

### Fixed

- **MySQL sequence DDL escaping** now quotes string literals correctly for
  sequence names, avoiding broken SQL and identifier-injection regressions.
- **Import/transfer robustness** tightened DDL injection handling, row-by-row
  fallback behavior, and cleanup paths in the transfer/import stack.
- **Detekt enforcement** now runs before test tasks so style issues are caught
  locally before the GitHub Actions workflows.
- **ProfileTableService** classifies optional profiling failures and reports
  unexpected errors with operation context instead of hiding them silently.

## [0.9.4] - 2026-04-21

### Added

- **MySQL sequence reverse-engineering** (`schema reverse`): the MySQL
  `SchemaReader` now reconstructs `SequenceDefinition` objects from
  `dmg_sequences` helper tables, `dmg_nextval`/`dmg_setval` support
  routines, and canonical `BEFORE INSERT` triggers generated by the
  `--mysql-named-sequences helper_table` mode introduced in 0.9.3.
  The reverse pipeline runs in three stages: D1 (support-table scan and
  shape verification), D2 (sequence row materialization with numeric
  overflow and ambiguity detection), D3 (trigger-based
  `SequenceNextVal` default assignment with marker cross-validation and
  guard verification)
- **`MysqlMetadataQueries`**: seven new metadata query methods for
  support-table existence, shape verification, sequence row listing,
  routine lookup (with `SHOW CREATE FUNCTION` fallback when
  `information_schema.routines.routine_definition` is NULL), and
  trigger scanning with backtick-safe scope resolution
- **`MysqlSequenceReverseSupport`**: internal D1 types —
  `ReverseScope`, `SupportTableState`, `SupportRoutineState`,
  `SupportTriggerState`, and sealed `DiagnosticKey` interface
  (`SequenceDiagnosticKey` / `ColumnDiagnosticKey`) for structured
  reverse diagnostics
- **Sequence support in `schema compare`**: JSON and YAML renderers
  now include `sequences_added`, `sequences_removed`,
  `sequences_changed` in summary and diff output; operand-level
  diagnostics (notes, skipped objects) are surfaced per source/target
- **Warning code W116**: emitted when reverse-engineering encounters
  degraded sequence support — missing or inaccessible `dmg_sequences`
  table, invalid table shape, invalid sequence rows, non-canonical
  support routines or triggers. W116 is deduplicated per diagnostic
  key and bound to the owning sequence or column
- **Warn-code ledger `ledger/warn-code-ledger-0.9.4.yaml`**: documents
  W116 variants with test paths and evidence sources

### Changed

- **`MysqlSchemaReader.read()`** integrates the three-stage reverse
  pipeline (D1→D2→D3) alongside existing table/view/function/procedure/
  trigger reading; support objects (`dmg_sequences`, `dmg_nextval`,
  `dmg_setval`, canonical triggers) are auto-filtered from user schema
  output
- **Sealed `DiagnosticKey` interface** replaces the previous `Any`-typed
  diagnostic key, providing type-safe sequence-bound and column-bound
  diagnostic scoping
- **Routine and trigger detection** uses `SHOW CREATE FUNCTION` /
  `SHOW CREATE TRIGGER` as fallback when `information_schema` strips
  d-migrate marker comments
- **Backtick-safe identifier parsing**: scope resolution handles
  schema-qualified sequence names (`` `db`.`seq` `` vs. unqualified
  `seq`) correctly
- `AbstractDdlGenerator.getVersion()` returns `0.9.4`

### Fixed

- Safe numeric conversions (Long/Int) reject lossy fallbacks
  (Double/Float) and detect overflow in `dmg_sequences` row validation
- `cycle_enabled` column accepts both Integer and Boolean values from
  MySQL JDBC driver (`BIT(1)` shape)
- Marker strictness enforced: `d-migrate:mysql-sequence-v1` version
  tag, `object=nextval`/`setval` in routines, `object=sequence-trigger`
  in triggers
- Trigger guard validation requires `NEW.<column> IS NULL` matching
  the assignment column before accepting a trigger as canonical
- Cross-schema sequence references rejected as `NON_CANONICAL`
- `SequenceNextVal` conflict detection prevents duplicate default
  assignments to the same column
- W116 deduplication prevents repeated diagnostics for the same
  sequence or column across reverse stages
- MySQL trigger scope parsing handles backtick-quoted identifiers
  robustly

## [0.9.3] - 2026-04-21

### Added

- **Safe `--filter` DSL** for `data export`: the `--filter` flag now
  accepts a closed, parameterized filter language instead of raw SQL.
  Supports comparisons, `IN (...)`, `IS NULL`/`IS NOT NULL`, `AND`,
  `OR`, `NOT`, parentheses, function calls (allowlist), and arithmetic.
  All literals are bound as JDBC parameters. Canonicalized fingerprints
  ensure stable checkpoint resume across whitespace and case variations
- **`default.sequence_nextval` schema form**: columns can now declare
  `default: { sequence_nextval: <sequence-name> }` to express that
  the column's default value comes from a named sequence. This replaces
  the legacy `nextval(...)` function-call notation
- **`--mysql-named-sequences` CLI option** for `schema generate`:
  `action_required` (default — sequences skipped with E056) or
  `helper_table` (opt-in — emulates named sequences via `dmg_sequences`
  support table, `dmg_nextval`/`dmg_setval` routines, and canonical
  `BEFORE INSERT` triggers)
- **MySQL `helper_table` sequence emulation**: generates `dmg_sequences`
  table, `dmg_nextval`/`dmg_setval` support routines, and per-column
  `BEFORE INSERT` triggers for sequence-based defaults
- **Warning codes W114, W115, W117**: W114 — cache stored but not
  emulated in MySQL helper-table mode; W115 — lossy trigger semantics
  (explicit NULL treated like omitted value); W117 — sequence values
  are transaction-bound in MySQL (rollback retracts increments)
- **Error codes E122, E123, E124**: E122 — legacy `nextval(...)` notation
  rejected; E123 — `sequence_nextval` references non-existent sequence;
  E124 — support object name collision with user schema objects

### Changed

- **Breaking: `--filter` is now a closed DSL** — raw SQL fragments,
  dialect-specific operators (`LIKE`, `BETWEEN`, `ILIKE`), and
  subqueries are no longer accepted. Existing checkpoints with
  pre-0.9.3 raw-SQL fingerprints are rejected with Exit 2 and a
  migration hint
- **Breaking: `nextval(...)` function-call notation removed** —
  columns that previously used `default: "nextval(invoice_seq)"` must
  migrate to the object form `default: { sequence_nextval: invoice_seq }`.
  This affects all dialects. Search-and-replace heuristic:
  replace `default: "nextval(<name>)"` with
  `default: { sequence_nextval: <name> }` in all schema YAML files.
  The parser now rejects the legacy form with error E122
- `AbstractDdlGenerator.getVersion()` returns `0.9.3`

## [0.9.2] - 2026-04-20

### Added

- **Phase-aware DDL model** (`DdlPhase.PRE_DATA` / `POST_DATA`): every `DdlStatement` carries a phase tag; `DdlResult` provides `statementsForPhase()`, `renderPhase()`, `notesForPhase()` and `skippedObjectsForPhase()` filter methods
- **`--split single|pre-post` for `schema generate`**: optional DDL output split into `*.pre-data.sql` (tables, constraints, sequences, simple views) and `*.post-data.sql` (triggers, functions, procedures, views with routine dependencies); JSON output uses `ddl_parts.pre_data` / `ddl_parts.post_data` with `split_mode: "pre-post"`
- **`ViewPhaseClassifier`**: classifies views into `PRE_DATA` or `POST_DATA` by declared `dependencies.functions`, inferred function calls in query text, and transitive propagation through view dependencies
- **`DependencyInfo.functions`** and codec support: schema YAML views can declare function dependencies for reliable phase assignment
- **SchemaReader catalog queries** for function/procedure metadata (AP 6.3 Steps F+G)
- **Split Golden Master tests**, JSON/Report contract tests, CLI split tests and ledger validation (AP 6.7)
- **E2E round-trip test**: DB→Export→Format→Import→DB→Schema Compare with strict DDL and NULL verification
- **Error and warning code ledgers** (`ledger/error-codes.yaml`, `ledger/warn-codes.yaml`) with systematic validation against E006–E121
- **REST, gRPC, and MCP service specifications** for future server interface

### Changed

- **Runner decomposition**: `DataExportRunner.executeWithPool` (446→26 LOC) and `DataImportRunner.executeWithPool` (477→24 LOC) decomposed into named step functions (AP 6.6)
- **Executor parameter grouping**: `ExportExecutor` (16→4 params) and `ImportExecutor` (14→4 params) use context DTOs instead of flat parameter lists (AP 6.6)
- **DDL interpolation hardened**: CHECK constraints, partition expressions, trigger conditions, and SpatiaLite function calls systematically reviewed; remaining 4 MySQL `-- TODO` placeholders replaced with `ManualActionRequired` entries (AP 6.5)
- **Split SQL, JSON and report output** implemented for `schema generate` (AP 6.4): phase attribution in notes and `skipped_objects` (kebab-case `"pre-data"` / `"post-data"`)
- **Quality refactoring**: `ViewPhaseClassifier` and `StatementInverter` extracted; `ColumnConstraintHelper` extracted per dialect; `SchemaCompare` projection DTOs extracted; Runner DTOs and MySQL column helpers refactored; `Preflight`/`Checkpoint` helpers extracted for both Runners
- **Type mappings stabilized**: tabular geometry and document type fallbacks consolidated
- `AbstractDdlGenerator.getVersion()` returns `0.9.2`
- Documentation updated: `guide.md` (split examples and round-trip workflow), `architecture.md` (§3.8 phase-aware DDL model), `ddl-generation-rules.md` (§17 phase-based ordering), `quality.md` review findings refreshed

### Fixed

- `--since` / `--since-column` validation in `data transfer` aligned with `data export` (`isNullOrBlank` instead of `isNull`)
- E060 diagnostic fires only when `dependencies` is null, not when empty
- E2E test allowlist replaced with verifiable assertions (no comment-based suppression)
- W120 `test_path` and evidence source corrected in warn-code ledger
- Sequence diff assertions completed with shared JSON/Report anchor

## [0.9.1] - 2026-04-19

### Added

- **Central SQL identifier quoting** (`SqlIdentifiers`): dialect-aware `quoteIdentifier()`, `quoteQualifiedIdentifier()` and `quoteStringLiteral()` utility; all profiling and introspection adapters route through this single quoting layer, closing the injection surface documented in `docs/user/quality.md`
- **Security tests**: 17 injection tests with malicious table/column names (`"; DROP TABLE …`, Unicode homoglyphs, reserved words) across profiling and DDL paths
- **`ManualActionRequired` model**: structured replacement for `-- TODO: …` SQL comment placeholders in DDL output; renders as `-- TODO: …` for backward compatibility in 0.9.1
- **`DialectCapabilities` model**: declares which schema objects each dialect supports natively (views, functions, procedures, triggers, sequences, custom types, partitioning); used by DDL generators for consistent generate/skip/rewrite decisions
- **`TableDependencySort`** (`hexagon:core`): single Kahn's-algorithm-based FK topo-sort utility replacing three prior duplicates (`AbstractDdlGenerator`, `ImportDirectoryResolver`, `DataTransferRunner`); returns sorted tables plus circular edges
- **`consumer-read-probe` test module** (`test/consumer-read-probe`): compile-time verification that read-only consumers can use d-migrate's ports without importing write, CLI, or profiling modules
- CI: Docker coverage report stages in `build.yml`

### Changed

- **`hexagon:ports` split into three modules**: `ports-common` (shared driver abstractions: `DatabaseDialect`, `SqlIdentifiers`, `DialectCapabilities`, `ConnectionPool`), `ports-read` (read-only contracts: `SchemaReader`, `DdlGenerator`, `DataReader`, `DataChunkReader`), `ports-write` (write-oriented contracts: `DataWriter`, `TableImportSession`, `ImportOptions`, `ExportOptions`, `DataChunkWriter`, checkpoint storage)
- **Profiling adapters extracted** into optional modules: `driver-postgresql-profiling`, `driver-mysql-profiling`, `driver-sqlite-profiling` — JDBC driver cores no longer depend on `hexagon:profiling`
- **`FormatReadOptions` extracted from `ImportOptions`**: carries read-oriented fields only (encoding, CSV no-header, CSV null-string); lives in `ports-read`
- **Runner decomposition**: `DataImportRunner` → `ImportResumeCoordinator` + `DirectoryImportScanner`; `DataExportRunner` → `ExportResumeCoordinator`; `StreamingImporter` → `TableImporter` + `InputResolver`; `StreamingExporter` → `TableExporter`
- **`SchemaComparator` decomposition**: `TableComparator` extracted (~320 LOC) for isolated table-level diff logic (columns, constraints, indexes, primary keys)
- **DDL generator decomposition**: routine DDL helpers extracted per object type; dialect-specific generators compose through these helpers with per-object rewrite/capability rules
- **Plan/milestone comments removed** from production code; only `why` comments and invariants remain
- `AbstractDdlGenerator.getVersion()` returns `0.9.1`
- Documentation updated: `docs/user/releasing.md` hardened (accuracy, consistency, Umlaut normalization, SHA source fix, Homebrew rollback step)

## [0.9.0] - 2026-04-17

### Added

- **Checkpoint/Resume for `data export`**: file-based exports can be interrupted and resumed via `--resume <checkpoint-id>` and `--checkpoint-dir <path>`. Table-granular resume (Phase C.1) skips already-completed tables; mid-table resume (Phase C.2) continues from the last confirmed composite marker when `--since-column` is set and the table has a primary key. Single-file targets use staging with atomic rename
- **Checkpoint/Resume for `data import`**: file- and directory-based imports can be interrupted and resumed at committed-chunk boundaries. Already-completed tables are skipped; partially committed tables restart at the next open chunk. `--truncate` is automatically suppressed for resumed tables to protect committed rows. Directory imports validate stable `table → inputFile` bindings
- **`--lang` as productive CLI override** (de/en): strict validation against bundled product languages with Exit 2 for unsupported values; priority: `--lang` > `D_MIGRATE_LANG` > `LC_ALL`/`LANG` > config `i18n.default_locale` > system locale > English fallback
- **CheckpointStore port and file-based adapter**: versioned `CheckpointManifest` with schema version, operation type, table slices, options fingerprint and resume positions; atomic temp-to-rename write strategy
- **`PipelineConfig` extended with `CheckpointConfig`**: `pipeline.checkpoint.enabled`, `interval` (row-based), `max_interval` (time-based, ISO-8601 duration), `directory`; merge priority CLI > Config > Runtime-Default
- **`operationId` in progress and result types**: stable UUID per run, emitted in `ProgressEvent.RunStarted` (with Starting/Resuming label) and in `ExportResult`/`ImportResult`
- **Exit code 3 for resume incompatibility**: fingerprint mismatch (format, encoding, CSV options, filter, `--since-*`, tables, output mode/path, PK signature, directory topology), operation type mismatch, or table list divergence
- CI: `verify-homebrew` Job in `.github/workflows/release-homebrew.yml` — macOS-Runner installiert nach jedem Tag-Push den veröffentlichten Tap (`pt9912/homebrew-d-migrate`) und verifiziert `d-migrate --version` + `--help` end-to-end
- CI: neuer Workflow `.github/workflows/verify-homebrew-formula.yml` — macOS-Verifikation der repo-lokalen Formula (`packaging/homebrew/d-migrate.rb`) via Ephemeral-Tap bei jeder Änderung der Formula-Datei, PRs gegen `develop`/`main` sowie manuellem `workflow_dispatch`

### Changed

- `AbstractDdlGenerator.getVersion()` returns `0.9.0`
- `--lang` CLI flag is now productive (was rejected with exit 7 in 0.8.0)
- Normative documentation (`cli-spec.md`, `connection-config-spec.md`, `roadmap.md`, `guide.md`, `design.md`, `architecture.md`) synchronized to the implemented 0.9.0 contract; stale phase placeholders removed
- Homebrew install block (`packaging/homebrew/d-migrate.rb` und `release-homebrew.yml` `install:`-Key) verwendet `Dir["*"]` statt `Dir["d-migrate-*"].fetch(0)`: Homebrew strippt das einzelne Top-Level-Verzeichnis beim Entpacken, das alte Pattern lieferte daher immer einen leeren Treffer und einen `IndexError` bei der Installation
- `docs/user/releasing.md` §4.7: `brew install --formula <path.rb>` durch den modern-homebrew-konformen Ephemeral-Tap-Weg ersetzt; Alternativpfad über den veröffentlichten Tap dokumentiert
- `docs/user/releasing.md` §4.7 / §7: ZIP-SHA wird aus dem publizierten Release-Asset (curl/`gh release download`) gelesen, nicht aus `release-assets/*.sha256` — die beiden Workflows bauen den ZIP unabhängig und produzieren unterschiedliche Hashes
- `docs/user/releasing.md` §3.5 / §7 / §8: neue Workflows in der Vorbereitungs-, Veröffentlichungs- und Referenzsektion eingetragen

## [0.8.0] - 2026-04-16

### Added

- **I18n-Runtime and config resolution** (Phase B): `ResolvedI18nSettings` with `locale`, `timezone`, `normalization` plus `I18nSettingsResolver` that resolves via `D_MIGRATE_LANG` > `LC_ALL` > `LANG` > `i18n.default_locale` > system locale > English fallback; timezone chain `i18n.default_timezone` > `ZoneId.systemDefault()` > UTC (error fallback only)
- **ResourceBundles and localized CLI output** (Phase C): English root bundle (`messages.properties`) and German bundle (`messages_de.properties`); `MessageResolver` with parent-chain fallback; `OutputFormatter` and `ProgressRenderer` emit localized plain-text while JSON/YAML payloads stay language-stable English
- **ICU4J-based Unicode utilities** (Phase D): `UnicodeNormalizer` (NFC/NFD/NFKC/NFKD) as explicit utility, not silent payload mutation; `GraphemeCounter` with ICU `BreakIterator` for grapheme-aware length on combining marks, emoji, ZWJ sequences and CJK
- **TemporalFormatPolicy** (Phase E): stateless policy object in `hexagon:application` centralizing ISO-8601 formatters, parse helpers (`parseSinceLiteral`, `parseOffsetDateTime`, `parseLocalDateTime`, `parseLocalDate`), `hasOffsetOrZone` heuristic and `toZoned(local, zone)` as the single explicit-zoning API
- **`--csv-bom` contract consolidated** (Phase F / D1): writes a BOM matching `--encoding` — `EF BB BF` for UTF-8, `FE FF` for UTF-16 BE, `FF FE` for UTF-16 LE; no-op for non-UTF encodings (ISO-8859-1, Windows-1252, …)
- **Unicode-aware BOM/encoding tests** across `EncodingDetectorTest`, `CsvChunkReaderTest`, `JsonChunkReaderTest`, `YamlChunkReaderTest`, `CsvChunkWriterTest` with Cyrillic, CJK and emoji payloads
- **TemporalFormatPolicyTest** covering §4.1–§4.6 of the Phase-E contract including minute-precision read tolerance lock-in
- **DE-bundle fallback test** in `MessageResolverTest` using a dedicated test-resource pair (`test-messages-phase-g/phasegmsg[_de].properties`) to prove ResourceBundle parent-chain fallback
- **Phase plans** `docs/planning/ImpPlan-0.8.0-A.md` through `docs/planning/ImpPlan-0.8.0-G.md` documenting the milestone in full

### Changed

- `AbstractDdlGenerator.getVersion()` returns `0.8.0`
- `ValueSerializer` and `ValueDeserializer` temporal branches annotated with Phase-E contract references; behavior unchanged but ISO-8601 handling documented inline (`TIMESTAMP` stays local, `TIMESTAMP WITH TIME ZONE` stays offset-bearing, `ZonedDateTime` serialized offset-based — `ZoneId` region intentionally not part of the 0.8.0 contract)
- `DataExportHelpers.parseSinceLiteral` delegates to `TemporalFormatPolicy.parseSinceLiteral`, removing the duplicate parser logic and ensuring CLI and format layer share the same contract
- `--lang` CLI flag is declared but actively rejected in 0.8.0 with exit 7 — the resolution chain runs through `D_MIGRATE_LANG`, `LC_ALL`/`LANG` and `i18n.default_locale`; the final CLI override contract lands in 0.9.0
- Documentation aligned across `spec/cli-spec.md`, `docs/user/guide.md`, `spec/design.md`, `spec/architecture.md`, `spec/connection-config-spec.md` and `spec/lastenheft-d-migrate.md` on the Phase-E and Phase-F contracts
- `CsvChunkWriter.writeBomBytes()` now carries the D1 rationale inline; behavior (UTF-8/UTF-16 BE/LE BOM + non-UTF no-op) unchanged

### Fixed

- Master plan `docs/planning/implementation-plan-0.8.0.md` cleaned of stale "UTF-8-only" and "CLI > ENV > Config > System > Fallback" claims that contradicted the Phase-E/F/G contracts

## [0.7.5] - 2026-04-15

### Added

- `d-migrate data profile` CLI command — profiles a database with column statistics, quality warnings, and target type compatibility as JSON/YAML report
- New Gradle module `hexagon:profiling` with domain model, type system, rule engine, and outbound ports
- `DatabaseProfile`, `TableProfile`, `ColumnProfile` domain model with `LogicalType` (data-oriented, separate from `NeutralType`), `TargetLogicalType`, `ProfileWarning`, `TargetTypeCompatibility`
- `WarningEvaluator` with 8 migration-relevant rules: high null ratio, empty/blank strings, high/low cardinality, duplicate values, invalid target type values, placeholder values
- `SchemaIntrospectionPort`, `ProfilingDataPort`, `LogicalTypeResolverPort` — profiling-specific outbound ports (not extending `DatabaseDriver`)
- Dialect-specific profiling adapters for PostgreSQL, MySQL, and SQLite with schema-qualified queries, full-scan type compatibility, and deterministic example values
- `ProfileTableService` and `ProfileDatabaseService` for profiling orchestration
- `DataProfileRunner` with exit codes 0/2/4/5/7 and injected `ProfilingAdapterSet` lookup
- `ProfileReportWriter` with JSON (default) and YAML output, identical information content
- `--source`, `--tables`, `--schema` (PostgreSQL only), `--top-n`, `--format`, `--output` flags
- PostgreSQL `--schema` support end-to-end (introspection + data queries)
- Determinism contract: stable table/column order, stable topValues, no runtime-variable `generatedAt`
- Architecture section 3.7, CLI-spec `data profile` section, design section 3.6 updated

### Changed

- `AbstractDdlGenerator.getVersion()` returns `0.7.5`
- Kotest upgraded from 5.9.1 to 6.1.11
- Kover upgraded from 0.9.1 to 0.9.8
- Gradle JVM heap raised from 512 MB to 4 GB
- Dockerfile: `gradle:8.12-jdk21` base image (no more wrapper download)

## [0.7.0] - 2026-04-15

### Added

- `d-migrate export flyway` CLI command — generates Flyway SQL migration files (`V<version>__<slug>.sql`, optional `U<version>__<slug>.sql` undo)
- `d-migrate export liquibase` CLI command — generates a versioned XML changelog with deterministic `changeSet` (id from version+slug+dialect, author `d-migrate`, optional `<rollback>` block)
- `d-migrate export django` CLI command — generates a minimal Django `RunSQL` migration with optional `reverse_sql`
- `d-migrate export knex` CLI command — generates a Knex.js CommonJS migration with sequential `knex.raw()` calls and optional `exports.down`
- Tool-neutral migration export contract in `hexagon:ports` (`MigrationBundle`, `MigrationIdentity`, `MigrationDdlPayload`, `MigrationRollback`, `ArtifactRelativePath`, `ToolMigrationExporter`, `ToolExportResult`)
- Adapter-free application helpers: `MigrationIdentityResolver`, `MigrationVersionValidator`, `MigrationSlugNormalizer`, `DdlNormalizer`, `ArtifactCollisionChecker`
- `ToolExportRunner` orchestrator in application layer with 12-step pipeline (schema read, validate, identity resolve, DDL generate, bundle build, exporter delegation, collision check, file write, report sidecar)
- New Gradle module `adapters:driven:integrations` with four `ToolMigrationExporter` implementations (side-effect-free, no tool-runtime dependencies)
- Collision detection: in-run artifact duplicates, existing file collisions (recursive), and report/artifact path overlap — all checked before first write
- YAML report sidecar via `--report` with notes, skippedObjects, rollbackNotes, rollbackSkippedObjects, exportNotes, and artifact paths
- Dockerfile `integration-test` stage with JDK + Python + Django + Node.js for runtime validation
- Runtime validation tests: Flyway→PostgreSQL, Liquibase→PostgreSQL (with rollback), Django→SQLite (with reverse), Knex→SQLite (with rollback)
- Tool export smoke tests in `docs/user/releasing.md`
- Architecture section 3.6 documenting the full hexagonal export pipeline
- Extensibility section 8.4 for adding new tool exporters

### Changed

- `AbstractDdlGenerator.getVersion()` returns `0.7.0`
- `test-integration-docker.sh` builds from Dockerfile `integration-test` stage (JDK + Python + Django + Node.js) instead of plain JDK image
- `.github/workflows/integration.yml` provisions Python, Django, Node.js, and pnpm for CI
- Django/Knex exporters filter comment-only statements to prevent tool runtime errors

## [0.6.0] - 2026-04-14

### Added

- `d-migrate schema reverse` CLI command — extracts the schema of a live database (PostgreSQL, MySQL, SQLite) into the neutral YAML format with structured notes and optional YAML sidecar report
- `SchemaReader` port interface with `SchemaReadResult` envelope (schema + notes + skipped objects) and `SchemaReadOptions` for object-type filtering (views, functions, procedures, triggers)
- PostgreSQL `SchemaReader`: tables, columns, PKs, FKs, indices, CHECK constraints, sequences, ENUM/DOMAIN/COMPOSITE custom types, views, functions, procedures, triggers, partitioning, extension notes
- MySQL `SchemaReader`: tables with engine metadata, columns, PKs, FKs, indices, CHECK constraints, ENUM columns, views, functions, procedures, triggers, `lower_case_table_names`-aware lookups
- SQLite `SchemaReader`: PRAGMA-based metadata extraction, `WITHOUT ROWID` detection, CHECK constraint regex parser, views, triggers
- `ObjectKeyCodec` for canonical routine keys `name(direction:type,...)` and trigger keys `table::name` with percent-encoding
- `ReverseScopeCodec` for `__dmigrate_reverse__:` prefixed schema names with dialect/database/schema components
- DB-based `schema compare` operands: `file:<path>` and `db:<url-or-alias>` prefix disambiguation with `CompareOperandParser` and `CompareOperandNormalizer` for reverse-marker normalization
- `d-migrate data transfer` CLI command — direct DB-to-DB streaming without intermediate format, with target-authoritative preflight, FK topological sort, and per-chunk commit
- `CustomTypeDiff`, `SequenceDiff`, `FunctionDiff`, `ProcedureDiff`, `TriggerDiff` in core diff engine
- `SchemaComparator` extended for DOMAIN, COMPOSITE, sequences, functions, procedures, triggers
- `TableMetadata` data class with `engine` (MySQL) and `withoutRowid` (SQLite) properties
- `SchemaReadNote` and `SchemaReadSeverity` for reverse-specific diagnostic notes (R300, R310, R320, R330, R400)
- `ReverseReportWriter` for structured YAML sidecar reports
- `SchemaNodeParser` and `SchemaNodeBuilder` for format-agnostic JSON/YAML codec extraction
- `JdbcMetadataSession` and typed metadata projections (TableRef, Column, PK, FK, Index, Constraint) in driver-common
- PostgresTypeMapping, MysqlTypeMapping, SqliteTypeMapping as pure testable type mapping objects
- Release smoke paths for Reverse, Compare (file/db, db/db), and Transfer in `docs/user/releasing.md`
- `CliDataTransferTest` with flag parsing, validation, and error path coverage

### Changed

- `SchemaDiff`: `enumTypes*` fields renamed to `customTypes*` to support ENUM, DOMAIN, and COMPOSITE types uniformly
- `SchemaValidator`: E008 (missing primary key) downgraded from error to warning
- `DatabaseDriver` interface: `schemaReader()` method added (implemented for all three dialects)
- `MysqlJdbcUrlBuilder`: removed deprecated `useUnicode`/`characterEncoding` properties (Connector/J 9.x), added `allowPublicKeyRetrieval=true`
- `AbstractDdlGenerator.getVersion()` returns `0.6.0`
- Dependency upgrades: PostgreSQL JDBC 42.7.10, MySQL Connector/J 9.6.0, SQLite JDBC 3.51.3.0, Jackson 2.21.2

### Fixed

- PostgreSQL `listSequences`: `cache_size` column referenced from `information_schema.sequences` (does not exist); fixed via LEFT JOIN to `pg_sequences`
- PostgreSQL `readSequences`: `information_schema.sequences` returns varchar values, not numbers; fixed with `toLongOrNull()` helper for mixed Number/String parsing
- PostgreSQL `listForeignKeys`: cartesian product on composite FKs from `constraint_column_usage`; fixed with `pg_constraint` + `unnest(conkey, confkey) WITH ORDINALITY`

## [0.5.5] - 2026-04-13

### Added

- Spatial geometry type (`type: geometry`) in the neutral schema model with `geometry_type` (8 canonical values: geometry, point, linestring, polygon, multipoint, multilinestring, multipolygon, geometrycollection) and `srid` (spatial reference system identifier)
- DDL generation for spatial columns across all three dialects: PostgreSQL/PostGIS (`geometry(Point, 4326)`), MySQL (native spatial types with SRID constraint), SQLite/SpatiaLite (`AddGeometryColumn()`/`DiscardGeometryColumn()` strategy)
- `--spatial-profile` CLI flag for `schema generate` with profiles `postgis`, `native`, `spatialite`, `none` and dialect-appropriate defaults
- Generator options architecture: `DdlGenerationOptions` data class with typed `SpatialProfile` enum, `SpatialProfilePolicy` for central defaults and validation
- Schema validation rules E120 (unknown geometry type) and E121 (invalid SRID); generation-time codes E052 (spatial column cannot be generated with chosen profile) and W120 (SRID transfer limitation)
- YAML codec support for `type: geometry` with lossless parsing of spatial attributes (`geometry_type`, `srid`)
- Spatial Golden Master tests for all three dialects (`spatial.postgresql.sql`, `spatial.mysql.sql`, `spatial.sqlite.sql`)

### Changed

- `DdlGenerator.generate()` and `generateRollback()` extended with `options: DdlGenerationOptions` parameter (backward-compatible with default arguments)
- TypeMapper implementations for all three dialects extended with geometry type handling
- `AbstractDdlGenerator.getVersion()` returns `0.5.5`

## [0.5.0] - 2026-04-13

### Added

- `d-migrate schema compare` CLI command — file-based comparison of two neutral schema definitions with deterministic Plain/JSON/YAML output
- Core-Diff-Engine (`SchemaComparator`) with hierarchical before/after diff model (`SchemaDiff`, `TableDiff`, `ColumnDiff`, `EnumTypeDiff`, `ViewDiff`)
- Single-column UNIQUE/FK normalization to avoid false positives between column-level and constraint-level representations
- Stable DiffView projection for CLI output — primitive-only types, no raw core model leakage
- Exit code contract for `schema compare`: 0 (identical), 1 (different), 2 (CLI error), 3 (invalid schema), 7 (parse/IO error)
- Validation of both schemas before comparison; warnings visible on stderr (plain) or in validation block (JSON/YAML) without changing exit code
- `--output` support for `schema compare` with automatic parent directory creation and input collision detection
- Line-oriented stderr progress display for `data export` and `data import` with event-based reporting (RunStarted, TableStarted, ChunkProcessed, TableFinished)
- `ProgressEvent` sealed interface and `ProgressReporter` fun interface in hexagon:ports for cross-module progress contract
- `ProgressRenderer` in CLI adapter with Locale.US number formatting
- Kover XML-to-JSON coverage report conversion in Docker build stage (via yq)
- GitHub Actions workflow for automated Homebrew tap releases (`release-homebrew.yml`)
- Release packaging with Fat JAR, ZIP, TAR distributions and SHA256 checksums

### Changed

- `cli-spec.md` section 7 updated to reflect actual MVP progress display: line-oriented, sequential single-table, no >2s threshold
- `ExportExecutor` and `ImportExecutor` interfaces extended with `ProgressReporter` parameter
- `--quiet` suppresses progress events and final summary; `--no-progress` suppresses progress events and summary but keeps non-progress stderr output (e.g., export warnings)

## [0.4.0] - 2026-04-12

### Added

- `d-migrate data import` CLI command — transactional import of JSON, YAML, or CSV data into PostgreSQL, MySQL, and SQLite databases
- `DataWriter` / `TableImportSession` port interfaces for hexagonal import pipeline
- PostgreSQL, MySQL, and SQLite data writers with batch INSERT, UPSERT (`--on-conflict update`), and TRUNCATE support
- Streaming import pipeline (chunk-based, transactional, no full-table buffering)
- Schema preflight validation on import (target schema is authoritative)
- Sequence / Identity / AUTO_INCREMENT reseeding after import for all three dialects
- Dialect-specific trigger handling during import (`--trigger-mode disable`)
- `--truncate` flag for clearing target tables before import
- `--on-conflict update` flag for idempotent UPSERT imports
- `--trigger-mode` flag for controlling trigger behavior during import
- Format read path: `DataChunkReader`, `DataChunkReaderFactory`, `ImportOptions` with `JsonChunkReader`, `YamlChunkReader`, `CsvChunkReader` for streaming deserialization
- `EncodingDetector` with BOM sniffing for UTF-8 / UTF-16 BE / UTF-16 LE
- `ValueDeserializer` for JDBC-type-hint based conversion on import
- `DefaultDataChunkReaderFactory` implementation
- `SchemaSync` contract for pre-import schema matching and validation
- `NamedConnectionResolver` extended with `resolveSource` / `resolveTarget`
- Incremental export flags `--since-column` and `--since` on `d-migrate data export` (LF-013), including typed parameter binding and composition with `--filter`
- `DataFilter.ParameterizedClause` and `SelectQuery(sql, params)` for safely bound WHERE fragments
- Testcontainers E2E import tests for PostgreSQL and MySQL
- Driver-level truncate integration tests
- E2E CLI data import tests: JSON/YAML/CSV round-trips, `--truncate`, `--on-conflict update`, `--trigger-mode disable`
- Round-trip tests (export → import → comparison) and incremental round-trip tests (initial export → delta export → UPSERT import → comparison)
- Golden-Master round-trip and null-row property tests

### Changed

- **Hexagonal architecture restructuring**: project reorganized from flat modules into `hexagon/core`, `hexagon/ports`, `hexagon/application`, `adapters/driven/*`, `adapters/driving/cli` — all module paths, imports, and CI workflows updated accordingly
- `DatabaseDriverRegistry` replaces legacy per-dialect registries
- `SchemaGenerateRunner` and `DataExportRunner` extracted from CLI command classes into `hexagon:application`
- `DataExportCommand` extracted into its own file
- `Main.kt` bootstrap split for testability; Help and edge-gap tests added
- `AbstractDdlGenerator.getVersion()` returns `0.4.0`
- CLI Kover coverage gate raised from 60% to 90%
- `d-migrate data export` now rejects literal `?` inside `--filter` when combined with `--since`, returning exit code 2 in the CLI preflight

### Fixed

- `ImportOptions.encoding` defaults to auto-detect (`null`) instead of hard-coded UTF-8
- `EncodingDetector.UnsupportedEncodingException` renamed to `UnsupportedFileEncodingException` to avoid JDK collision
- `ValueDeserializer` distinguishes `NUMERIC` / `DECIMAL` via precision, scale, and token shape instead of forcing every value onto `BigDecimal`
- Large-fixture cache invalidation uses generator source hash instead of manual fingerprint
- Enum type check no longer rejects custom PG enums without `refType`
- `Types.OTHER` cross-contamination in schema type compatibility check
- Fragile `message.startsWith` check replaced with typed exception
- Schema-qualified table names no longer break `--schema` validation
- Directory import now includes `.yml` files for YAML format
- Schema target validation decoupled from application layer for cleaner error handling
- `autoCommit` guard before PostgreSQL `TRUNCATE` in `openTable`
- Writer wiring and cleanup-failure reporting hardened

## [0.3.0] - 2026-04-06

### Added

- `d-migrate data export` CLI command — streaming export of database tables to JSON, YAML, or CSV
- 2 new Gradle modules: `d-migrate-driver-api` (connection layer + data ports), `d-migrate-streaming` (pull-based StreamingExporter)
- Connection layer: `ConnectionConfig`, `ConnectionUrlParser`, `JdbcUrlBuilder` per dialect, HikariCP-backed `HikariConnectionPoolFactory`, `LogScrubber` (password-safe URL masking)
- Hexagonal data ports in `d-migrate-driver-api`: `DataReader`, `TableLister`, `ChunkSequence` (single-use, AutoCloseable, transaction lifecycle in `close()`)
- JDBC `DataReader` + `TableLister` adapters for PostgreSQL (cursor streaming via `setFetchSize` + `autoCommit=false`), MySQL (`useCursorFetch=true`), and SQLite (lazy file read)
- Driver bootstrap objects (`PostgresDriver`, `MysqlDriver`, `SqliteDriver`) for explicit registry registration in `Main.kt`
- Pull-based `StreamingExporter` (chunk-based, no full-table buffering) with `ExportOutput` resolution (`Stdout` / `SingleFile` / `FilePerTable`)
- Format writers in `d-migrate-formats/data/`: `JsonChunkWriter` (DSL-JSON), `YamlChunkWriter` (SnakeYAML Engine), `CsvChunkWriter` (uniVocity-parsers) — chosen over Jackson for streaming throughput
- `ValueSerializer` mapping table (Plan §6.4.1) covering 18+ JDBC types (Numeric, BigInteger/BigDecimal, Date/Time/UUID, BLOB/CLOB, `java.sql.Array` as recursive `SerializedValue.Sequence`)
- `NamedConnectionResolver` (Plan §6.14): minimal `.d-migrate.yaml` loader with CLI > ENV > default-path priority, `${ENV_VAR}` substitution, `$${VAR}` escape, literal substitution (no auto URL-encoding)
- CLI flags for `data export`: `--source` (URL or named connection), `--format` (required), `--output`, `-o`, `--tables`, `--filter`, `--encoding`, `--chunk-size`, `--split-files`, `--csv-delimiter`, `--csv-bom`, `--csv-no-header`, `--null-string`
- `--tables` strict identifier validation (`[A-Za-z_][A-Za-z0-9_]*` optionally schema-qualified) — rejects whitespace, hyphens, SQL injection attempts (Plan §6.7)
- §6.17 empty-table contract: every reader emits at least one chunk with `columns` even when `rows.isEmpty()`; pre-format outputs are `[]` (JSON), `[]` (YAML), header line (CSV), or empty file (`--csv-no-header`)
- Exit-code matrix for `data export`: `0` success, `2` CLI/usage error, `4` connection error, `5` export error, `7` config error
- Testcontainers-based integration tests for PostgreSQL 16 and MySQL 8.0 — both at the driver level and end-to-end via the CLI
- New `.github/workflows/integration.yml` running `./gradlew test koverVerify -PintegrationTests`; default `build.yml` excludes the `integration` Kotest tag and stays under the 5-min CI budget
- `scripts/test-integration-docker.sh` for running integration tests locally in a disposable Docker container
- `docs/planning/implementation-plan-0.3.0.md` (1400+ lines), `docs/user/releasing.md`
- `data export` section in `spec/cli-spec.md` §6.2 covering all flags, output resolution, exit codes, and 6 example invocations
- 600+ tests across all modules (was 374 in 0.2.0)
- Kover coverage gates: ≥ 90% for all production modules, ≥ 60% for `d-migrate-cli` (per Plan §11)

### Changed

- `AbstractDdlGenerator.getVersion()` now returns `0.3.0`
- Bumped Testcontainers from 1.20.4 to 2.0.4 — module artifacts renamed (`org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`) and classes relocated (`org.testcontainers.containers.PostgreSQLContainer` → `org.testcontainers.postgresql.PostgreSQLContainer`); resolves Docker Engine ≥ 25.x API negotiation failures
- `d-migrate-cli` Kover gate raised from 50% to 60% (Plan §11) now that CLI integration tests are in place
- LF-013 (incremental export/import) moved from milestone 0.9.0 forward to 0.4.0 in `docs/planning/roadmap.md` — paired with `data import`, no longer requires `SchemaReader`
- Roadmap milestone 0.9.0 split into 0.9.0 (Beta core: Checkpoint/Resume + `--lang`) and 0.9.5 (Beta docs and pilot QA) — different cadences for code vs. documentation work
- `cli-spec.md` §6.2 `data export` updated to reflect the actual 0.3.0 surface (named connections, all CSV flags, exit codes 2/7, 6 example invocations)
- `--quiet` now also suppresses `ValueSerializer` warnings on stderr (per `cli-spec.md` §1.3 — "Nur Fehler"), not just the `ProgressSummary`
- `--no-progress` is now honored by `data export` (previously only `--quiet` suppressed the `ProgressSummary`)

### Fixed

- SQLite `:memory:` URLs are now preserved as in-memory databases instead of being interpreted as filesystem paths
- MySQL `characterEncoding` parameter: use Java charset name `UTF-8` instead of MySQL identifier `utf8mb4`, which Connector/J rejects with `UnsupportedEncodingException`
- `java.sql.Array` is now serialized recursively as a JSON array / YAML sequence per Plan §6.4.1; CSV produces `null` plus a single deduplicated `W201` warning per `(table, column)` tuple
- `BigInteger` and `BigDecimal` are now mapped to separate `SerializedValue` variants — `BigInteger` renders as a YAML number (no precision loss), `BigDecimal` as a quoted string for both JSON and YAML
- `--csv-delimiter` validation now produces a clean exit code 2 with a stderr message instead of a raw `IllegalArgumentException` stack trace
- `StreamingExporter.end()` is no longer called when `begin()` failed, preventing format writers from emitting unmatched closing tokens (e.g. JSON `]` without `[`)
- `JsonChunkWriter` honors `ExportOptions.encoding` via a `CharsetReencodingOutputStream` wrapper when the target encoding is not UTF-8
- `CsvChunkWriter` warnings now carry the real column name instead of `col0`/`col1`
- `Stdout` exports no longer close `System.out` — a `NonClosingOutputStream` wrapper guards against the writer's `close()` propagating to the process-wide stream

## [0.2.0] - 2026-04-06

### Added

- DDL generation for PostgreSQL, MySQL, and SQLite via `d-migrate schema generate --target <dialect>`
- 4 new Gradle modules: `d-migrate-driver-api`, `d-migrate-driver-postgresql`, `d-migrate-driver-mysql`, `d-migrate-driver-sqlite`
- `TypeMapper` interface with full 18-type mapping per dialect (SERIAL, AUTO_INCREMENT, AUTOINCREMENT, JSONB, TINYINT(1), etc.)
- `AbstractDdlGenerator` base class with topological sort (Kahn's algorithm) and rollback generation
- `DdlStatement` model pairing SQL with inline `TransformationNote`s
- `ViewQueryTransformer` with 17 regex-based SQL function transformations between dialects
- `TransformationReportWriter` generating YAML sidecar reports (`<output>.report.yaml`)
- `--output`, `--target`, `--generate-rollback`, `--report` flags on `schema generate`
- `--output-format json` support for `schema generate` with ddl, notes, skipped_objects
- Circular FK handling: PostgreSQL/MySQL via ALTER TABLE ADD CONSTRAINT, SQLite via E019 error
- MySQL DELIMITER wrapping for triggers, functions, procedures
- 12 golden master test files (4 schemas x 3 dialects)
- 374 tests across all modules (was 83 in 0.1.0)
- Kover coverage enforcement >= 90% for all driver modules

### Fixed

- SQL injection prevention: identifier quoting escapes embedded quote characters
- Enum values with single quotes are properly escaped (`''`)

## [0.1.0] - 2026-04-05

### Added

- Gradle multi-module project structure with three modules: `d-migrate-core`, `d-migrate-formats`, `d-migrate-cli`
- `NeutralType` sealed class with 18 database-agnostic types (identifier, text, char, integer, smallint, biginteger, float, decimal, boolean, datetime, date, time, uuid, json, xml, binary, email, enum, array)
- `SchemaDefinition` model for representing database schemas in a neutral format
- `SchemaValidator` with 18 validation error codes (E001 -- E018) covering structural and semantic checks
- `YamlSchemaCodec` for parsing and writing schema definitions in YAML format
- CLI command `schema validate` for validating schema files from the command line
- CLI output format support: plain text, JSON, and YAML (`--output-format`)
- 83 unit and integration tests across all modules
- Kover code coverage enforcement: >= 90% for core and formats modules, >= 50% for CLI module
- Kotlin 2.1.20 with JVM 21 target
- Gradle 8.12 build configuration
- MIT License
