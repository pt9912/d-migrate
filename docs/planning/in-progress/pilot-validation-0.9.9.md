# Pilot-Validierung 0.9.9 (Beta) — End-to-End-Bericht

> **Software-Version:** 0.9.9-SNAPSHOT (aus `develop` gebaut) · **Stand:** 16.06.2026
>
> **Art:** Automatisierte End-to-End-Validierung als Breiten-Proxy für die
> Pilotanwender-Tests (Lastenheft 9.2), Vorbereitung 1.0.0-RC. Verbindlicher
> Workflow- und Abnahme-Rahmen: [Migrations-Leitfaden](../../user/migrations-leitfaden.md),
> [`guide.md`](../../user/guide.md), [API-Referenz](../../user/api-referenz.md),
> [`spec/cli-spec.md`](../../../spec/cli-spec.md). Kandidaten-DBs:
> [Test-Database-Candidates](../open/test-database-candidates.md). Ablage gemäß
> [ADR 0004](../../adr/0004-documentation-and-planning-structure.md).

---

## 1. Ehrlichkeits-Vorbehalt (zuerst lesen)

Lastenheft 9.2 verlangt **mindestens fünf menschliche Tester**. Dieser Bericht
ersetzt das **nicht**. Er ist eine **automatisierte Validierung durch einen
einzelnen Agenten** über sechs repräsentative Szenarien (Smoke → Compatibility
→ Scale-nah → Round-Trip → Feature-Stichproben) als **Breiten-Proxy**. Er liefert
reproduzierbare Befunde gegen einen definierten Build, aber **keine** Aussagen
über Bedienbarkeit, Dokumentationsverständlichkeit aus Anwendersicht oder die
Vielfalt realer Betriebsumgebungen, die eine menschliche Pilotgruppe abdeckt.

Alle Behauptungen sind durch echte Tool-Läufe belegt. Es wurden **keine Zahlen
erfunden**. Es wurde **kein** formaler Performance-Benchmark gefahren (siehe
[Abschnitt 9](#9-grenzen-dieser-validierung)); vereinzelt genannte Laufzeiten
sind nur indikativ auf der unten genannten Maschine.

---

## 2. Gesamt-Verdikt

**Nicht RC-reif.** Die einzelnen Engine-Bausteine sind solide
(Reverse-Engineering mit Include-Flags, `schema compare`, SQLite-Generierung und
Sequenz-Emulation, gleichdialekt- und nicht-kritische Datentransfers,
inkrementeller Export, Parquet-Bundle-Export, `data profile`). Die **dokumentierten
Cross-Dialect-Happy-Paths scheitern jedoch reproduzierbar** an einer Kette
echter Bugs — bereits der Smoke-Test (Szenario 1, PostgreSQL → PostgreSQL) läuft
ohne manuelle Eingriffe nicht sauber durch.

| Szenario | Richtung | Abnahme (Checkliste 10.4) |
| -------- | -------- | ------------------------- |
| 1 Smoke | Pagila PG → PG | ❌ **FAIL** — Pipeline durch E009 → Domain-DDL → GIST → Enum-Transfer blockiert |
| 2 Compatibility | Pagila PG → MySQL | ❌ **FAIL** — Partition-DDL ungültig + Transfer-Preflight blockiert |
| 3 Compatibility | Sakila MySQL → PG | ❌ **FAIL** — Enum-Lowercasing + Transfer-Preflight + View-DDL |
| 4 → SQLite | helper_table / W103 / E056 | ✅ **PASS** — exakt wie dokumentiert, Runtime verifiziert |
| 5 Round-Trip | PG → MySQL → SQLite | ❌ **FAIL** (Daten) — Schema-Kette ok, Daten durch Preflight/E009 blockiert |
| 6 Features | inkrementell / Parquet / profile | ⚠️ **TEILWEISE** — Export/Profile ok, Parquet-**Import** auf Timestamp blockiert |

Kernursache der meisten Transfer-Blocker ist **ein einziger Defekt**: der
`data transfer`-Preflight verlangt strikte Gleichheit der neutralen Typen
zwischen Quelle und Ziel und ignoriert dabei die **vom Tool selbst erzeugten**
Dialekt-Abbildungen (boolean→INTEGER, timestamptz→DATETIME, enum→text). Siehe
[I-01](#6-priorisierte-issue-liste).

---

## 3. Umgebung

| Komponente | Wert |
| ---------- | ---- |
| Host | Linux 6.8.0, x86-64, Docker Engine 29.5.3, Compose v5.1.4 (lokaler Einzelhost, kein Benchmark-Rig) |
| d-migrate | Image `d-migrate:0.9.9-pilot`, aus `develop` gebaut (`--build-arg GRADLE_TASKS="assemble :adapters:driving:cli:installDist"`), `--version` → `0.9.9-SNAPSHOT`. **Hinweis:** GHCR `:latest` zeigt zum Testzeitpunkt auf 0.9.8 — für eine ehrliche 0.9.9-Validierung wurde aus Quellcode gebaut. |
| JVM (Container) | `user.language=en`, `user.country=US`, `file.encoding=UTF-8`, `LANG=en_US.UTF-8` |
| PostgreSQL | `postgres:17.10-trixie` |
| MySQL | `mysql:8.4` |
| SQLite | Datei-Ziel über JDBC (xerial) |
| Beispieldaten | Pagila (PG, 22 Tabellen), Sakila (MySQL, 16 Tabellen + 7 Views), Employees (MySQL, ~4 Mio. Datenzeilen geladen, für Scale-Reserve) |

Verbindungen liefen secret-frei über `.d-migrate.yaml` mit `${VAR}`-Substitution
aus der Prozess-Umgebung (keine echten Kundendaten, keine Secrets im Klartext).
Die d-migrate-CLI lief als Container im selben Docker-Netz wie die Datenbanken;
das Arbeitsverzeichnis war als `/work` gemountet (Stil wie
[`guide.md`](../../user/guide.md) Option B).

---

## 4. Methodik

- **Exit-Codes** wurden gegen [API-Referenz 2.2](../../user/api-referenz.md)
  geprüft und stets **vor** jeder Shell-Pipe erfasst (`cmd > out; echo $?`), da
  `$?` nach `| tail` den Code von `tail` liefern würde — eine Falle, die zu
  Beginn einmal zuschlug und korrigiert wurde.
- **Bug vs. erwartete Dialekt-Grenze:** Jeder Verdacht wurde gegen die
  autoritative Quelle (DB-Katalog, Quell-Dump, Spec) geprüft, bevor er als Bug
  klassifiziert wurde. Zwei Erst-Verdachte wurden dadurch **widerlegt** (siehe
  [Abschnitt 7](#7-was-korrekt-funktioniert-verifiziert)).
- **Minimal-Repros** für die wichtigsten Bugs wurden auf kleinste Schemata
  reduziert, damit sie unabhängig vom Beispieldatensatz nachvollziehbar sind.

---

## 5. Szenario-Ergebnisse

### 5.1 Szenario 1 — Smoke: Pagila PostgreSQL → PostgreSQL ❌ FAIL

**Befehle (Kern):**

```bash
d-migrate schema reverse  --source pagila --output /work/schemas/pagila.yaml
d-migrate schema validate --source /work/schemas/pagila.yaml
d-migrate schema generate --source /work/schemas/pagila.yaml --target postgresql \
    --split pre-post --deterministic --output /work/ddl/pagila.sql
d-migrate data transfer   --source pagila --target pagila_tgt --trigger-mode disable
d-migrate schema compare  --source db:pagila --target db:pagila_tgt
```

**Ergebnis & Exit-Codes:**

| Schritt | Exit | Beobachtung |
| ------- | :--: | ----------- |
| reverse | 0 | 22 Tabellen, 13 Sequenzen; eine Warnung R301 (`tsvector` → text). Korrekt. |
| validate | **3** | `E009` auf `customer.create_date` — der reverse-Output scheitert am **eigenen** Validator. |
| generate | **3** | Bricht mit demselben E009 ab, **erzeugt keinerlei DDL**. Kein Skip-Validation-Flag vorhanden. |
| (nach Workaround) generate | 0 | DDL erzeugt; pre-data-Apply scheitert dann an Domain-DDL und einem GIST-Index. |
| transfer | **5** | Bricht bei `film` ab: Enum-Spalte `rating` ohne Cast (`character varying` → `mpaa_rating`). |
| compare | 1 | Funktioniert korrekt; meldet exakt die durch die Workarounds entstandenen Differenzen. |

**Datenintegrität (Teil-Transfer vor dem Enum-Abbruch):** Zeilenzahlen Quelle =
Ziel exakt für die sechs übertragenen Tabellen (`actor` 200, `address` 603,
`category` 16, `city` 600, `country` 109, `customer` 599). Die Transfer-Engine
ist für Nicht-Enum-Daten korrekt; sie bricht bei B6 **komplett** ab (kein
Überspringen — `film`/`rental`/`payment` blieben bei 0).

**Abnahme-Checkliste 10.4:** reverse+validate ❌ · pre/post-data ❌ · compare ohne
unerwartete Differenzen ❌ · Zeilenzahlen ✅ (Teilmenge) · Sequenzen — n/a.

**Befunde:** I-02 (E009), I-05/I-06 (Domain-DDL), I-08 (GIST-Index), I-04
(Enum-Transfer). Siehe Issue-Liste.

### 5.2 Szenario 2 — Pagila PostgreSQL → MySQL (Sequenz-Emulation) ❌ FAIL

```bash
d-migrate schema generate --source /work/schemas/pagila.yaml --target mysql \
    --mysql-named-sequences helper_table --split pre-post --deterministic \
    --output /work/ddl/pagila_my.sql
d-migrate data transfer --source pagila --target pagila_my \
    --trigger-mode disable --on-conflict update
```

| Schritt | Exit | Beobachtung |
| ------- | :--: | ----------- |
| generate | 0 | 40 Warnungen (W100 TZ, W102 GIST-Skip — **korrekt**, W112 Partition, W114 cache, W117 Sequenz-Rollback), 0 `action_required`. `dmg_sequences`-Emulation **wohlgeformt** (Tabelle + Metadaten-INSERTs `format_version='mysql-sequence-v1'`); Enum → `ENUM(...)`, boolean → `TINYINT(1)`. |
| pre-data apply | Fehler | `ERROR 1064` an der **partitionierten** `payment`-Tabelle: `PARTITION BY RANGE (payment_date)` mit **leerer** Partitionsliste, vor `ENGINE`. Zusätzlich `ERROR 1075` (AUTO_INCREMENT nicht führend in Composite-PK) und `ERROR 1170` (Index auf unbounded `TEXT` ohne Präfixlänge). |
| transfer | **3** | **Preflight** bricht ab: `Column 'actor.last_update' type mismatch: DateTime(timezone=true) vs DateTime(timezone=false)` — genau die TZ-Differenz, die `generate` selbst als W100 erzeugte. |

**Abnahme 10.4:** Alle Punkte ❌ (pre-data nicht anlegbar, Transfer im Preflight
blockiert). Die `dmg_sequences`-Infrastruktur ist strukturell korrekt, war aber
zur Laufzeit nicht erreichbar. **Hinweis:** Pagila nutzt implizite SERIAL-Sequenzen
(→ AUTO_INCREMENT); die echte `dmg_sequences`-Runtime-Emulation wurde separat in
Szenario 4 (SQLite) end-to-end verifiziert.

**Befunde:** I-07 (Partition + AUTO_INCREMENT), I-08 (TEXT-Index), I-01 (Preflight TZ).

### 5.3 Szenario 3 — Sakila MySQL → PostgreSQL (TINYINT(1) ↔ BOOLEAN) ❌ FAIL

```bash
d-migrate schema reverse  --source sakila --include-views --output /work/schemas/sakila.yaml
d-migrate schema validate --source /work/schemas/sakila.yaml          # Exit 0 (PASS)
d-migrate schema generate --source /work/schemas/sakila.yaml --target postgresql --split pre-post
d-migrate data transfer   --source sakila --target sakila_pg
```

| Schritt | Exit | Beobachtung |
| ------- | :--: | ----------- |
| reverse | 0 | **TINYINT(1) → `boolean`** korrekt; `tinyint unsigned` korrekt **nicht** boolean. R301 (`year` → text) Warnung, R320 **ACTION_REQUIRED** (MySQL `SET` → text) korrekt gemeldet. |
| validate | 0 | PASS (kein `CURRENT_DATE` → kein E009). |
| generate | 0 | W111 für View-Funktionen (`GROUP_CONCAT`, `IF`) — Warnung korrekt, **aber** View-Bodies werden als **rohes MySQL** ins PG-DDL geschrieben. |
| pre-data apply | Fehler | `7× syntax error at or near "."` (Backtick-/`schema.tabelle`-MySQL-Syntax in PG). Tabellen entstehen, Views scheitern. |
| transfer | **3** | **Preflight**: `Column 'film.rating' type mismatch: Enum(values=[g, pg, pg-13, r, nc-17]) vs Text`. |

**Schwerer Datenbefund:** `schema reverse` **schreibt MySQL-ENUM-Werte klein**.
Quelle `enum('G','PG','PG-13','R','NC-17')` → reverse `values: [g, pg, pg-13, r, nc-17]`,
während `default: G` (groß) erhalten bleibt → **Default nicht in der Werteliste**.
Minimal-Repro: `ENUM('Yes','No','MAYBE')` → `["yes","no","maybe"]`. Echte Daten
(G/PG/…) würden nach Migration nicht zum korrumpierten Enum passen.

**Abnahme 10.4:** TINYINT/BOOLEAN ✅ und validate ✅, aber Transfer/Compare nicht
sauber erreichbar → Szenario ❌.

**Befunde:** I-03 (Enum-Lowercasing), I-09 (View-DDL), I-01 (Preflight Enum/Text).

### 5.4 Szenario 4 — Ziel SQLite (helper_table, W103, E056) ✅ PASS

Fokussiertes Schema mit benannter Sequenz + Materialized View:

```bash
d-migrate schema generate --source /work/schemas/sqlite_features.yaml --target sqlite   # default
d-migrate schema generate --source /work/schemas/sqlite_features.yaml --target sqlite \
    --sqlite-named-sequences helper_table
```

| Variante | Exit | Beobachtung |
| -------- | :--: | ----------- |
| default | 0 | `E056` action_required + 1 skipped object (Sequenz); **W103** Materialized View → reguläre VIEW; W200 Decimal → REAL. Exakt wie [Leitfaden 6.1/6.4](../../user/migrations-leitfaden.md). |
| helper_table | 0 | `dmg_sequences` (`format_version='sqlite-sequence-v1'`, seeded `next_value=1000`); kanonisches `_bi`/`_ai`-Trigger-Paar `dmg_seq_orders_ref_<hash>_{bi,ai}`; W103/W115/W117/W200. |

**Runtime-Verifikation** (DDL real auf eine SQLite-Datei angewandt, 3 Inserts):
`ref` = 1000, 1001, 1002 (start=1000, increment=1); `dmg_sequences.next_value`=1003,
`last_returned_value`=1002. Sequenz-Emulation, View und Trigger funktionieren
**live** wie dokumentiert.

**Abnahme 10.4:** vollständig erfüllt für den Generierungs- und Emulationspfad.
Starker Positivbefund; deutlicher Kontrast zu den Cross-Dialect-Transferpfaden.

### 5.5 Szenario 5 — Round-Trip PG → MySQL → SQLite ❌ FAIL (Daten)

Bewusst „landminenfreies" Schema (`product`: SERIAL, `VARCHAR(100)`, `DECIMAL`,
`INTEGER`, `BOOLEAN`, `TIMESTAMP` ohne TZ), um zu isolieren, ob die Pipeline ohne
die obigen Trigger durchläuft. Hinweis: [Leitfaden 7.4](../../user/migrations-leitfaden.md)
markiert den Cross-DB-Round-Trip selbst als **🔮 Zielbild für 1.0.0 (Abnahmeziel 8.6)**.

- **Stufe A (PG → MySQL):** reverse/validate/generate/apply alle Exit 0 — das
  benigne Schema erzeugt **gültiges** MySQL-DDL. `data transfer --trigger-mode disable`
  → **Exit 2** „not supported for dialect MYSQL". **Ohne** das Flag: Exit 0, 5/5
  Zeilen, boolean-Treue korrekt (true/false → 1/0), decimal/varchar/int exakt.
  Compare zeigt eine **unerwartete** Differenz: `product.id required: true → false`
  (PG SERIAL vs. MySQL AUTO_INCREMENT).
- **Stufe B (MySQL → SQLite):** reverse/generate Exit 0. `data transfer` →
  **Exit 3** Preflight: `Column 'product.active' type mismatch: BooleanType vs Integer`.
  Compare MySQL ↔ SQLite → **Exit 3 INVALID**, `E009` auf dem **selbst generierten**
  SQLite-Ziel (`created_at TEXT DEFAULT (datetime('now'))` → reverse `text` +
  `current_timestamp` → E009).

**Befunde:** I-12 (trigger-mode/MySQL), I-14 (Identity-`required`), I-01 (Preflight
boolean), I-02 (E009 systemisch — auch text+current_timestamp).

### 5.6 Szenario 6 — Feature-Stichproben ⚠️ TEILWEISE

| Feature | Befehl | Exit | Ergebnis |
| ------- | ------ | :--: | -------- |
| Inkrementeller Export | `data export … --since-column qty --since 50` | 0 | 3 Zeilen (qty ≥ 50, inklusive Grenze). ✅ |
| Parquet-Export (Single-File) | `data export … --format parquet -o x.parquet` | 0 | 5 Zeilen, Manifest im Footer. ✅ |
| Parquet-Bundle | `data export … --format parquet --split-files --manifest-sha256 -o dir/` | 0 | Bundle mit `manifest.yaml` (pro Tabelle `sha256` + `neutralType`, `producerVersion: 0.9.9-SNAPSHOT`). ✅ |
| Parquet-**Import** | `data import … --format parquet` | **3** | `column 'created_at' expects TIMESTAMP, got Instant` — blockiert Timestamp-Tabellen. ❌ |
| `data profile` | `data profile --source … --tables product` | 0 | Vollständiger JSON-Report (rowCount, nullable, distinctCount, min/max, topValues). ✅ (klein: `databaseProduct="unknown"`). |

**Befunde:** I-10 (Parquet-Import Timestamp), I-13 (Bundle braucht `--split-files`,
im `guide.md`-Beispiel nicht gezeigt).

---

## 6. Priorisierte Issue-Liste

Schwere: **P1** = blockiert dokumentierten Happy-Path / Datenkorrektheit · **P2** =
ungültige DDL-Generierung · **P3** = Doku/UX/kosmetisch.

| ID | P | Titel | Minimal-Repro (erwartet → tatsächlich) |
| -- | - | ----- | -------------------------------------- |
| **I-01** | **P1** | `data transfer`-Preflight verlangt strikte Neutraltyp-Gleichheit und ignoriert die eigenen Dialekt-Abbildungen | Quelle bool/enum/timestamptz → Ziel (vom Tool generiert) INTEGER/text/DATETIME. **Erwartet:** Transfer läuft. **Tatsächlich:** Exit 3 „type mismatch". Blockiert ~alle Cross-Dialect-Transfers. **Häufigste Einzelursache.** |
| **I-02** | **P1** | `schema validate`/`generate` lehnt Temporal-Funktions-Defaults ab (E009) | `date DEFAULT CURRENT_DATE` **und** `text DEFAULT current_timestamp`. **Erwartet:** valide. **Tatsächlich:** Exit 3 E009. `generate` erzeugt dann keine DDL (kein Skip-Flag). reverse/SQLite-generate-Output scheitert am eigenen Validator. |
| **I-03** | **P1** | `schema reverse` schreibt MySQL-ENUM-Werte klein (Datenkorruption) | `ENUM('Yes','No','MAYBE')` → `values: ["yes","no","maybe"]`, `default` bleibt groß → Default nicht in Werteliste. |
| **I-04** | **P1** | Enum-Datentransfer ohne Cast (PG-Ziel) | `data transfer` einer Enum-Spalte → Exit 5 „column is of type X but expression is of type character varying". |
| **I-05** | **P2** | Domain-`base_type` rendert neutralen Typnamen + falsche Präzision | `CREATE DOMAIN … AS BIGINTEGER(64,0)` statt `AS BIGINT`. Spaltenpfad mappt korrekt; nur der Domain-Pfad umgeht den Typ-Mapper. |
| **I-06** | **P2** | Domain-`check` doppelt gewrappt | `CHECK (CHECK (((VALUE …))))` — reverse speichert inkl. Wrapper, generate wrappt erneut → ungültig. |
| **I-07** | **P2** | Partitionierte Tabelle → ungültiges MySQL-DDL | `PARTITION BY RANGE (col)` mit leerer Partitionsliste, vor `ENGINE`; zusätzlich AUTO_INCREMENT nicht führend in der erzwungenen Composite-PK (ERROR 1075). Sollte E055/skip oder valides DDL sein. |
| **I-08** | **P2** | Index auf typ-inkompatibler Spalte → ungültiges DDL ohne Sekundärwarnung | MySQL: Index auf unbounded `TEXT` ohne Präfixlänge (ERROR 1170). PG: GIST-Index auf `tsvector`→text-degradierter Spalte (no operator class). Nur die Typ-Warnung (R301), kein Index-Hinweis. |
| **I-09** | **P2** | View-Bodies werden als rohes Quell-Dialekt-SQL ins Ziel-DDL geschrieben | MySQL→PG: Backticks + `schema.tabelle` + `group_concat` → `syntax error at or near "."`. W111 warnt, aber DDL ist nicht parsebar (sollte E053/skip sein). |
| **I-10** | **P2** | Parquet-Import scheitert an Timestamp-Spalten | `data import --format parquet` → Exit 3 „column expects TIMESTAMP, got Instant". |
| **I-11** | **P3** | Default-`reverse` lässt Views/Functions/Trigger still weg | Playbook-Befehl ohne `--include-*` → `skipped_objects: 0`, kein Hinweis, dass Objekte existieren. (Doku-Update bereits angestoßen.) |
| **I-12** | **P3** | `data transfer --trigger-mode disable` für MySQL abgelehnt, aber im Playbook gezeigt | Leitfaden 5.3/7.1 zeigt genau diesen Befehl → Exit 2. Doku oder Tool angleichen. |
| **I-13** | **P3** | Parquet-Bundle braucht `--split-files` (im `guide.md`-Beispiel weggelassen) | Ohne Flag: Single-File / Exit 2 / Exit 5; `--manifest-sha256` still wirkungslos. Mit Flag: korrekt. |
| **I-14** | **P3** | Identity-Spalte `required` asymmetrisch über Dialekte | PG SERIAL `required: true` vs. MySQL AUTO_INCREMENT `required: false` → unechte Round-Trip-Differenz. |
| **I-15** | **P3** | Kosmetik | `schema compare` braucht `db:`-Präfix (reverse/transfer nehmen blanken Alias); `data profile` meldet `databaseProduct: "unknown"`. |

---

## 7. Was korrekt funktioniert (verifiziert)

- **Reverse-Engineering mit Include-Flags:** `--include-views/-functions/-triggers`
  erfassen Pagilas 8 Views (inkl. Materialized-Flag), 9 Functions und 15 Trigger
  konsistent (Trigger→Function-Abhängigkeiten intakt).
- **TINYINT(1) → boolean** und Nicht-Abbildung von `tinyint unsigned` — korrekte
  Unterscheidung.
- **SQLite-Pfad (Szenario 4):** E056/W103/W200, helper_table-Emulation und
  `_bi`/`_ai`-Trigger end-to-end korrekt (Runtime verifiziert).
- **MySQL-`generate`-Diagnostik:** W100/W102/W114/W117 sachgerecht; GIST-Index wird
  hier — anders als im PG-Pfad — korrekt mit W102 **übersprungen**.
- **`schema compare`:** Exit-Codes (0/1/3/7) korrekt; reverse-engineert beide
  Operanden voll und meldet exakt die tatsächlichen Differenzen.
- **Nicht-kritische Datentransfers:** exakte Zeilenzahlen, korrekte
  boolean-Treue (true/false → 1/0).
- **Inkrementeller Export, Parquet-Bundle (mit `--split-files`, inkl. sha256-Manifest),
  `data profile`.**
- **Zwei widerlegte Erst-Verdachte** (Disziplin „Bug vs. Dialekt-Grenze"):
  (a) der Non-ASCII-Identifier `bıgınt` (U+0131) stammt aus dem Pagila-Dump selbst
  und wurde byte-genau erhalten — **kein** Locale-Bug; (b) fehlende `max_length` an
  `actor.last_name` ist korrekt, weil diese Pagila-Variante echtes `text` verwendet.

---

## 8. Reproduktion

Alle Läufe sind reproduzierbar: 0.9.9-Image aus `develop` bauen, je einen
PostgreSQL- und MySQL-Container im selben Docker-Netz starten, Pagila/Sakila aus
den in [Test-Database-Candidates](../open/test-database-candidates.md) genannten
Quellen laden, und die in [Abschnitt 5](#5-szenario-ergebnisse) gezeigten Befehle
im `/work`-Docker-Stil aus [`guide.md`](../../user/guide.md) ausführen.
Verbindungen secret-frei über `.d-migrate.yaml` mit `${VAR}`.

---

## 9. Grenzen dieser Validierung

- **Kein Ersatz für menschliche Pilotgruppe** (siehe Abschnitt 1).
- **Kein Performance-Benchmark.** Employees (~4 Mio. Datenzeilen) wurde geladen,
  aber kein Last-/Durchsatz-Lauf gefahren; die Cross-Dialect-Blocker hätten einen
  belastbaren Scale-Transfer ohnehin verhindert. Genannte Laufzeiten sind rein
  indikativ auf dem oben genannten Einzelhost.
- **Reduzierte Dialekt-/Versions-Matrix** (je eine PG-/MySQL-/SQLite-Version).
- **Stored-Procedure-/MCP-/AI-Pfade** waren nicht Teil des Auftrags und wurden
  nicht validiert.
- Mehrere Szenarien wurden mit **klar etikettierten Test-Daten-Workarounds**
  fortgeführt (nicht Tool-Fixes), um Downstream-Verhalten trotz Blocker zu messen;
  diese sind je Szenario benannt.

---

## 10. Verwandte Dokumente

- [Migrations-Leitfaden](../../user/migrations-leitfaden.md) · [`guide.md`](../../user/guide.md) · [API-Referenz](../../user/api-referenz.md)
- [`spec/cli-spec.md`](../../../spec/cli-spec.md) — normative CLI-/Exit-Code-Verträge
- [Test-Database-Candidates](../open/test-database-candidates.md) · [ADR 0004](../../adr/0004-documentation-and-planning-structure.md)
