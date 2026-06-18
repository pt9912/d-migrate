# Pilot-Validierung 0.9.9 (Beta) — Re-Validierungslauf nach Bugfixes

> **Software-Version:** 0.9.9-SNAPSHOT (aus `develop` gebaut, HEAD `0facc838`) · **Stand:** 17.06.2026
>
> **Art:** Automatisierte End-to-End-**Re-Validierung** als Breiten-Proxy für die
> Pilotanwender-Tests (Lastenheft 9.2), Vorbereitung 1.0.0-RC. Dies ist **nicht**
> der Erstlauf: der Erst-Report liegt unverändert in
> [`pilot-validation-0.9.9.md`](pilot-validation-0.9.9.md). Verbindlicher
> Workflow- und Abnahme-Rahmen: [Migrations-Leitfaden](../../user/migrations-leitfaden.md),
> [`guide.md`](../../user/guide.md), [API-Referenz](../../user/api-referenz.md),
> [`spec/cli-spec.md`](../../../spec/cli-spec.md). Kandidaten-DBs:
> [Test-Database-Candidates](../open/test-database-candidates.md). Ablage gemäß
> [ADR 0004](../../adr/0004-documentation-and-planning-structure.md).

---

## 1. Ehrlichkeits-Vorbehalt (zuerst lesen)

Lastenheft 9.2 verlangt **mindestens fünf menschliche Tester**. Dieser Bericht
ersetzt das **nicht**. Er ist eine **automatisierte Validierung durch einen
einzelnen Agenten** über sechs repräsentative Szenarien (Smoke → Compatibility →
Scale-nah → Round-Trip → Feature-Stichproben) als **Breiten-Proxy**. Er liefert
reproduzierbare Befunde gegen einen definierten Build, aber **keine** Aussagen
über Bedienbarkeit, Dokumentationsverständlichkeit aus Anwendersicht oder die
Vielfalt realer Betriebsumgebungen, die eine menschliche Pilotgruppe abdeckt.

Alle Behauptungen sind durch echte Tool-Läufe belegt. Es wurden **keine Zahlen
erfunden**. Es wurde **kein** formaler Performance-Benchmark gefahren; vereinzelt
genannte Laufzeiten sind nur indikativ auf der unten genannten Maschine.

---

## 2. Gesamt-Verdikt

**Deutlicher Fortschritt — noch nicht RC-sauber.** Die im Erstlauf gemeldeten
P1/P2-Blocker sind **alle gegen ihr Original-Repro verifiziert behoben** (Details
[Abschnitt 5](#5-blocker-re-verifikation-i-01--i-10)). Es gibt **keine
Regression**. Der Pfad **MySQL → PostgreSQL läuft jetzt sauber end-to-end durch**
(Transfer Exit 0, Daten datenbelegt korrekt), ebenso die Feature-Stichproben
(inkl. des zuvor blockierten Parquet-Imports). Die SQLite-Sequenz-Emulation und
`schema compare` arbeiten unverändert korrekt.

**Aber:** Sobald die Erst-Blocker die Cross-Dialect-Pipelines nicht mehr früh
abbrechen, wird eine **zweite Schicht** von Defekten sichtbar, die mehrere
dokumentierte Happy-Paths weiterhin nicht vollständig durchlaufen lässt — vor
allem ein generate-seitiger Fehler beim `CURRENT_DATE`-Funktions-Default
([N1](#7-priorisierte-issue-liste-neue--restbefunde)), unvollständige PostgreSQL-
Partition-Generierung ([N2](#7-priorisierte-issue-liste-neue--restbefunde)) und
zwei verbleibende Preflight-Lücken bei Tool-eigenen Typabbildungen
([N3](#7-priorisierte-issue-liste-neue--restbefunde)).

| Szenario | Richtung | Erstlauf | Re-Run (Abnahme 10.4) |
| -------- | -------- | -------- | --------------------- |
| 1 Smoke | Pagila PG → PG | ❌ FAIL | ⚠️ **VERBESSERT/FAIL** — Kern (Tabellen, Domains, Enum-Daten) ok; Voll-Pipeline durch N1/N2/N6 blockiert |
| 2 Compatibility | Pagila PG → MySQL | ❌ FAIL | ⚠️ **VERBESSERT/FAIL** — I-07/I-08/I-09 behoben; Pipeline durch N1/N3/N4 blockiert |
| 3 Compatibility | Sakila MySQL → PG | ❌ FAIL | ✅ **PASS** (Datenpfad) — Transfer Exit 0, Daten verifiziert; nur Index-Namens-Kollision (N8) |
| 4 → SQLite | helper_table / W103 / E056 | ✅ PASS | ✅ **PASS** (Feature+Runtime, regressionsfrei) — reverse→generate-Round-Trip durch N5 gebrochen |
| 5 Round-Trip | PG → MySQL → SQLite | ❌ FAIL | ⚠️ **TEILWEISE** — Stufe A (Daten) ok, I-02 behoben; Stufe B durch N3 blockiert |
| 6 Features | inkrementell / Parquet / profile | ⚠️ TEILWEISE | ✅ **PASS** — Parquet-Import (I-10) behoben, alle Stichproben Exit 0 |

---

## 3. Umgebung

| Komponente | Wert |
| ---------- | ---- |
| Host | Linux 6.8.0, x86-64, Docker Engine 29.5.3, Compose v5.1.4 (lokaler Einzelhost, kein Benchmark-Rig) |
| d-migrate | Image `d-migrate:0.9.9-pilot-rerun`, aus `develop` (HEAD `0facc838`) gebaut mit `--build-arg GRADLE_TASKS="assemble :adapters:driving:cli:installDist"`; `--version` → `0.9.9-SNAPSHOT`. **Hinweis:** GHCR `:latest` zeigt zum Testzeitpunkt weiterhin auf 0.9.8 (ohne die Fixes) — für eine ehrliche 0.9.9-Validierung wurde aus Quellcode gebaut. |
| JVM (Container) | `LANG=en_US.UTF-8`, `file.encoding=UTF-8` |
| PostgreSQL | `postgres:17.10-trixie` |
| MySQL | `mysql:8.4.10` |
| SQLite | Datei-Ziel über JDBC (xerial) bzw. Runtime-Gegenprobe via Python-`sqlite3` |
| Beispieldaten | Pagila (PG, 22 Tabellen), Sakila (MySQL, 16 Tabellen + 7 Views) |

Verbindungen liefen secret-frei über `.d-migrate.yaml` mit `${VAR}`-Substitution
aus der Prozess-Umgebung (keine echten Kundendaten, keine Secrets im Klartext).
Die d-migrate-CLI lief als Container im selben Docker-Netz (`pilot-net`) wie die
Datenbanken; das Arbeitsverzeichnis war als `/work` gemountet (Stil wie
[`guide.md`](../../user/guide.md) Container-CLI).

---

## 4. Methodik

- **Exit-Codes** gegen [API-Referenz 2.2](../../user/api-referenz.md) geprüft und
  stets **vor** jeder Shell-Pipe erfasst (`cmd > out; echo $?`).
- **Bug vs. erwartete Dialekt-Grenze / Nicht-Ziel:** Jeder Verdacht wurde gegen
  die autoritative Quelle (DB-Katalog, Quell-Dump, Spec, Modellklassen) geprüft.
  Bewusste Nicht-Ziele ([ADR 0012](../../adr/0012-index-prefix-length-scope.md):
  PK-/Constraint-Präfixlängen; **View-Bodies werden nicht transpiliert**, vgl.
  [P2-Tracker I-09](pilot-blocker-p2-tracker.md)) werden **nicht** als Bug
  gewertet — im Gegenteil, der `E053`-Skip für nicht-portierbare View-Bodies ist
  genau dieses Nicht-Ziel, korrekt umgesetzt.
- **Re-Run-Mandat:** Jeder behobene Blocker wurde **explizit gegen sein
  Original-Repro** aus [Erst-Report Abschnitt 6](pilot-validation-0.9.9.md)
  gefahren und mit **BEHOBEN / TEILWEISE / REGRESSION / NEUER BEFUND** markiert.
- **Klar etikettierte Test-Daten-Workarounds** (keine Tool-Fixes) wurden genutzt,
  um Downstream-Verhalten trotz eines Blockers zu messen; sie sind je Stelle
  benannt.

---

## 5. Blocker-Re-Verifikation (I-01 … I-10)

Schwere wie im Erstlauf: **P1** = Happy-Path/Datenkorrektheit · **P2** = ungültige
DDL-Generierung/Import-Abbruch · **P3** = Doku/UX.

| ID | Original-Symptom (Erstlauf) | Status Re-Run | Beleg |
| -- | --------------------------- | ------------- | ----- |
| **I-01** | Transfer-Preflight verlangt strikte Neutraltyp-Gleichheit; bool/enum/timestamptz↔Tool-Mapping → Exit 3 | ✅ **BEHOBEN** (alle drei Repro-Mappings) | Transfer passiert `actor.last_update` (timestamptz→DATETIME) in S2; boolean→INTEGER in S3/S5; Inline-Enum in S3. **Restlücke** → [N3](#7-priorisierte-issue-liste-neue--restbefunde) |
| **I-02** | `validate`/`generate` lehnt Temporal-Funktions-Defaults ab (E009); keine DDL | ✅ **BEHOBEN** | `validate` der Pagila-`customer.create_date` Exit 0; SQLite `text DEFAULT (datetime('now'))` reverse→validate Exit 0 (kein E009). Legt N1 separat frei. |
| **I-03** | `reverse` schreibt MySQL-ENUM-Werte klein (Datenkorruption) | ✅ **BEHOBEN** | Sakila `film.rating` → YAML `values: [G, PG, PG-13, R, NC-17]`, `default: G` (Case erhalten, Default in Liste). End-to-end Daten identisch (s. S3). |
| **I-04** | Enum-Datentransfer ohne Cast (PG-Ziel) → Exit 5 | ✅ **BEHOBEN** | Pagila `film` (Enum `rating`) transferiert; Werteverteilung Quelle = Ziel exakt (G 178/PG 194/PG-13 223/R 195/NC-17 210). |
| **I-05** | Domain-`base_type` rendert neutralen Typnamen + falsche Präzision | ✅ **BEHOBEN** | Generiert `CREATE DOMAIN "bıgınt" AS BIGINT;` (war `AS BIGINTEGER(64,0)`). |
| **I-06** | Domain-`check` doppelt gewrappt | ✅ **BEHOBEN** | `CREATE DOMAIN "year" AS INTEGER CHECK (((VALUE >= 1901) AND (VALUE <= 2155)));` — genau **ein** `CHECK`-Wrapper. |
| **I-07** | Partition → ungültiges MySQL-DDL (ERROR 1064/1075) | ✅ **BEHOBEN** | `E055`-Skip + Hint statt nacktem `PARTITION BY`; `payment`-PK als `(payment_id, payment_date)` → AUTO_INCREMENT **führt** (kein 1075). MySQL-Apply ohne 1064/1075. |
| **I-08** | Index auf typ-inkompatibler Spalte → ungültiges DDL (PG GIST / MySQL TEXT 1170) | ✅ **BEHOBEN** (PG + MySQL) | PG: `W123`-Skip von `film_fulltext_idx [gist]`. MySQL: `W125`-Skip von TEXT-Indizes (`idx_title`, `idx_actor_last_name`); kein 1170. |
| **I-09** | View-Bodies als rohes Quell-Dialekt-SQL ins Ziel | ✅ **BEHOBEN** (Original-Richtung MySQL→PG) | Alle 7 Sakila-Views → `E053`-Skip („MySQL-style backtick quoting; GROUP_CONCAT/IF"), kein invalides PG-DDL. **Restlücke** Gegenrichtung → [N4](#7-priorisierte-issue-liste-neue--restbefunde) |
| **I-10** | Parquet-Import scheitert an Timestamp-Spalten → Exit 3 | ✅ **BEHOBEN** | Parquet-Import in leere Tabelle: **5 inserted**, Exit 0; Timestamp-Werte identisch zur Quelle (`2026-06-17 12:40:54.14235`). |

**Zusammenfassung:** 10/10 Original-Repros **behoben**, **0 Regressionen**. I-01
und I-09 sind für ihren dokumentierten Repro-Fall vollständig behoben; in beiden
Bereichen wurde je eine **angrenzende Restlücke neu entdeckt** (N3 bzw. N4), die
erst durch das Wegfallen des Erst-Blockers sichtbar wird.

---

## 6. Szenario-Ergebnisse

### 6.1 Szenario 1 — Smoke: Pagila PostgreSQL → PostgreSQL ⚠️ VERBESSERT/FAIL

**Kette:** `reverse --include-all` → `validate` → `generate --split pre-post
--deterministic` → pre-data apply → `transfer --trigger-mode disable` → post-data
apply → `compare db:pagila db:pagila_tgt`.

| Schritt | Exit | Beobachtung |
| ------- | :--: | ----------- |
| reverse | 0 | 22 Tabellen; R301 (`tsvector`→text), korrekt. |
| validate | **0** | **War Exit 3 (E009).** Jetzt sauber → I-02 behoben. |
| generate | **0** | **War Exit 3.** Erzeugt pre/post-data; `W123`-Skip GIST → I-08-PG. Domain-DDL korrekt → I-05/I-06. |
| pre-data apply | Fehler | `ERROR: invalid input syntax for type date: "CURRENT_DATE"` — `create_date DATE DEFAULT 'CURRENT_DATE'` (Funktions-Default als String-Literal gequotet → **N1**). `customer` fehlt; 21/22 Tabellen. |
| (Workaround N1) apply | 0 | Mit entquotetem `DEFAULT CURRENT_DATE`: **22/22 Tabellen, 0 Fehler** — die Domain-/GIST-DDL ist valide. |
| transfer | **5** | Kommt jetzt **bis `film` (Enum) erfolgreich durch** (war Exit 5 genau dort → I-04 behoben); bricht an partitionierter `payment`: `no partition of relation "payment" found for row` (**N2**). 11 Tabellen übertragen. |
| post-data apply | Fehler | Trigger-Wrapper mit Body `EXECUTE FUNCTION last_updated()` → `syntax error at EXECUTE` (**N6**); fehlendes Aggregat `group_concat` (**N7**). |
| compare | 1 | Funktioniert; meldet die durch unvollständige post-data entstandenen Differenzen. |

**Datenintegrität (11 übertragene Tabellen):** Zeilenzahlen Quelle = Ziel exakt
(`actor` 200, `address` 603, `category` 16, `city` 600, `country` 109, `customer`
599, `film` 1000, `film_actor` 5462, `film_category` 1000, `inventory` 4581,
`language` 6). Enum-Treue `film.rating` Quelle = Ziel identisch.

**Befunde:** I-02/I-04/I-05/I-06/I-08-PG **behoben**; neu: N1, N2, N6, N7.

### 6.2 Szenario 2 — Pagila PostgreSQL → MySQL ⚠️ VERBESSERT/FAIL

```bash
schema generate --source pagila.yaml --target mysql \
    --mysql-named-sequences helper_table --split pre-post --deterministic
data transfer --source pagila --target pagila_my --on-conflict update
```

| Schritt | Exit | Beobachtung |
| ------- | :--: | ----------- |
| generate | 0 | **Saubere Diagnostik statt invalidem DDL:** `E055` (Partition leer → Skip+Hint → I-07), 3× `W125` (TEXT-Index-Skip → I-08-MySQL), `W102` (GIST-Skip), 54× `E053` (nicht-portierbare Views/Functions/Trigger → I-09). |
| pre-data apply | Fehler | `payment` jetzt als reguläre Tabelle, **PK `(payment_id, payment_date)` mit führendem AUTO_INCREMENT** — kein 1064/1075. Einziger DDL-Fehler: `ERROR 1067 Invalid default value for 'create_date'` (= **N1**, cross-dialect). |
| (Workaround N1) apply | — | Mit `DEFAULT (CURRENT_DATE)`: 23 Tabellen; verbleibend 3× `ERROR 1064` an PG-View-Bodies mit `::text`/`||` (**N4**). |
| transfer | **3** | **Passiert `actor.last_update` (timestamptz→DATETIME)** — die TZ-Inkompatibilität des Erstlaufs ist weg (I-01). Stoppt erst bei `film.rating`: `Enum(values=null, refType=mpaa_rating) vs Enum(values=[G,...], refType=null)` (**N3**). |

**Befunde:** I-07, I-08-MySQL, I-09(-fwd), I-01(-TZ) **behoben**; neu: N1, N3, N4.

### 6.3 Szenario 3 — Sakila MySQL → PostgreSQL ✅ PASS (Datenpfad)

```bash
schema reverse --source sakila --include-views ; schema validate   # Exit 0/0
schema generate --target postgresql --split pre-post ; data transfer --source sakila --target sakila_pg
```

| Schritt | Exit | Beobachtung |
| ------- | :--: | ----------- |
| reverse | 0 | TINYINT(1)→`boolean`; `film.rating`-Enum **case-erhalten** (I-03). R301/R320 korrekt. |
| validate | 0 | PASS. |
| generate | 0 | 14× `E053`: alle 7 Views als nicht-portierbar erkannt (Backtick-Heuristik) → I-09. Kein Backtick/`group_concat`-Leak ins PG-DDL. |
| pre-data apply | (6 Fehler) | 16 Tabellen angelegt; 6× `ERROR: relation "idx_fk_*" already exists` — MySQL-Index-Namen pro Tabelle kollidieren im PG-schema-globalen Namensraum (**N8**). |
| transfer | **0** | **Transfer complete: 16 table(s).** |

**Datenintegrität:** Zeilenzahlen exakt (`film` 1000, `actor` 200, `customer`
599, `rental` 16044, `payment` 16049, `inventory` 4581). `film.rating`-Verteilung
Quelle (MySQL) = Ziel (PG) identisch und **groß** geschrieben → I-03 end-to-end.
`customer.active` als PG-`boolean`.

**Befunde:** I-03, I-09, I-01 **behoben/verifiziert**; neu: N8 (Index-Namen).

### 6.4 Szenario 4 — Ziel SQLite (helper_table, W103, E056) ✅ PASS (Feature+Runtime)

**Generierung** (aus reverse-Schema mit benannter Sequenz + Materialized View +
Decimal):

| Variante | Exit | Beobachtung |
| -------- | :--: | ----------- |
| default | 0 | `E056` (Sequenz action_required + skip), `W200` (Decimal→REAL), `W103` (Materialized View → reguläre VIEW) — exakt wie [Leitfaden 6.1/6.4](../../user/migrations-leitfaden.md). |
| helper_table | 0 | `dmg_sequences` (`format_version='sqlite-sequence-v1'`, `next_value=1000`); kanonisches `dmg_seq_orders_ref_<hash>_{bi,ai}`-Trigger-Paar. |

**Runtime-Verifikation** (DDL real auf SQLite-Datei, 3 Inserts): `ref` =
**1000, 1001, 1002**; `dmg_sequences.next_value=1003`, `last_returned_value=1002`;
Materialized View als reguläre `view`. **Identisch zum Erstlauf — keine
Regression.**

**Aber (N5):** Wird das SQLite-Schema **aus einem reverse-engineerten
PostgreSQL-Schema** erzeugt, misst der reverse eine Nicht-PK-Spalte mit
`DEFAULT nextval('named_seq')` fälschlich als `generation.type: identity` (statt
`default: {sequence_nextval: …}`). Folge: generate emittiert eine **zweite**
`PRIMARY KEY AUTOINCREMENT`-Spalte → SQLite `table … has more than one primary
key`, und die Named-Sequence-Trigger entfallen. Der `--sqlite-named-sequences`-
Pfad ist damit für reverse-engineerte Schemas faktisch unbenutzbar.

### 6.5 Szenario 5 — Round-Trip PG → MySQL → SQLite ⚠️ TEILWEISE

Benignes `product`-Schema (`serial`, `varchar(100)`, `decimal(10,2)`, `integer`,
`boolean`, `timestamp`). [Leitfaden 7.4](../../user/migrations-leitfaden.md)
markiert den Cross-DB-Round-Trip selbst als Zielbild für 1.0.0 (Abnahmeziel 8.6).

- **Stufe A (PG → MySQL):** reverse/validate/generate/apply alle Exit 0
  (`now()`-Default valide → I-02). `data transfer` **Exit 0**, boolean-Treue
  exakt (`t/f` → `1/0`). I-01 (boolean) verifiziert.
- **Stufe B (MySQL → SQLite):** reverse/generate Exit 0 (`W200`). `data transfer`
  **Exit 3** Preflight: `Column 'product.created_at' type mismatch:
  DateTime(timezone=false) vs Text` — SQLite speichert Timestamps als TEXT; dieser
  Tool-eigene Mapping-Fall fehlt noch im Preflight (**N3**). Der zugehörige
  I-02-Systemfall ist jedoch behoben: reverse des generierten SQLite +
  `validate` → **Exit 0** (kein E009 mehr auf `created_at TEXT DEFAULT
  (datetime('now'))`).

**Befunde:** I-01 (boolean), I-02 (systemisch) **behoben**; Stufe-B-Daten durch
N3 blockiert.

### 6.6 Szenario 6 — Feature-Stichproben ✅ PASS

| Feature | Exit | Ergebnis |
| ------- | :--: | -------- |
| Inkrementeller Export (`--since-column qty --since 50`) | 0 | Korrekte Grenzfilterung (1 Zeile mit qty ≥ 50 im Testdatensatz). |
| Parquet-Export (Single-File) | 0 | 2148 B, gültiges Parquet. |
| **Parquet-Import (Timestamp)** | **0** | **War Exit 3** („expects TIMESTAMP, got Instant"). Jetzt: 5 inserted in leere Tabelle, Timestamp-Werte unverfälscht → **I-10 behoben**. |
| `data profile` | 0 | Vollständiger JSON-Report (rowCount, distinctCount, nullable, min/max, topValues). `databaseProduct: "unknown"` (klein, bekannte Kosmetik I-15). |

---

## 7. Priorisierte Issue-Liste (neue / Restbefunde)

Diese Befunde wurden **erst sichtbar**, nachdem die Erst-Blocker die Pipelines
nicht mehr früh abbrechen. Keiner ist ein bewusstes Nicht-Ziel.

| ID | P | Titel | Minimal-Repro (erwartet → tatsächlich) |
| -- | - | ----- | -------------------------------------- |
| **N1** | **P2** | `CURRENT_DATE`-Funktions-Default wird als String-Literal gerendert → ungültiges DDL | `date NOT NULL DEFAULT CURRENT_DATE` → generate emittiert `DEFAULT 'CURRENT_DATE'`. **PG:** `ERROR invalid input syntax for type date`. **MySQL:** `ERROR 1067`. **Cross-dialect.** Kontrast: `now()`/`current_timestamp` auf `datetime` rendert korrekt als `CURRENT_TIMESTAMP`. Lücke betrifft `CURRENT_DATE` (vermutl. auch `CURRENT_TIME`). |
| **N3** | **P1** | Transfer-Preflight deckt nicht alle Tool-eigenen Typabbildungen ab | (a) PG-**Named-Enum** (`Enum(values=null, refType=…)`) → MySQL-**Inline-Enum** (`Enum(values=[…])`) → Exit 3. (b) `DateTime(tz=false)` → SQLite-`Text` → Exit 3. Blockiert PG→MySQL-Enum- und *→SQLite-Datetime-Transfers. I-01-Restlücke. |
| **N2** | **P2** | PostgreSQL-Partition-Tabelle wird ohne Kind-Partitionen + ohne Warnung generiert | Partitionierte Quelle → generate emittiert `) PARTITION BY RANGE (col);` **ohne** Partitionsdefinitionen und **ohne** Warn-Code. Valides DDL, aber nimmt keine Zeile auf → `data transfer` Exit 5 „no partition found". (MySQL-Ziel macht hier korrekt `E055`-Skip.) |
| **N4** | **P2** | View-Portabilitäts-Heuristik (PG→MySQL) übersieht `::`-Cast und `||`-Concat | PG-View mit `(x)::text || y` → MySQL: **kein** `E053`-Skip, sondern verbatim emittiert → `ERROR 1064`. Gegenrichtung (MySQL-Backticks) wird korrekt erkannt. Heuristik unsymmetrisch. |
| **N5** | **P2** | reverse misst Nicht-PK-`nextval`-Spalte als Identity statt Named-Sequence | PG `col bigint DEFAULT nextval('s')` (kein PK) → YAML `generation.type: identity` statt `default.sequence_nextval`. SQLite-generate → doppelte `PRIMARY KEY AUTOINCREMENT` (ungültig); Named-Sequence-Trigger entfallen. |
| **N6** | **P2** | Trigger-Round-Trip (auch gleichdialektal PG→PG) erzeugt ungültige Funktions-Body | Pagila-Trigger → generate emittiert `CREATE FUNCTION "…::…"() … AS $$ EXECUTE FUNCTION last_updated() $$` → `syntax error at EXECUTE` (kein gültiger plpgsql-Body). |
| **N7** | **P3** | Benutzerdefiniertes Aggregat wird von reverse nicht erfasst | PG-`CREATE AGGREGATE group_concat(...)` → nicht im DDL; abhängige Views → `function group_concat does not exist`. |
| **N8** | **P3** | Index-Namens-Kollision MySQL→PG (pro-Tabelle vs. schema-global) | MySQL `idx_fk_address_id` auf mehreren Tabellen → PG `ERROR: relation "idx_fk_address_id" already exists`; einzelne Indizes fehlen im Ziel. |

> **Hinweis zur Priorisierung:** N3 ist als **P1** eingestuft, weil es einen
> dokumentierten Cross-Dialect-Datenpfad (Enum/Datetime) blockiert; es ist die
> direkte Fortsetzung der I-01-Klasse. N1 ist die häufigste Einzelursache für
> nicht-applizierbares DDL in S1/S2.

---

## 8. Was korrekt funktioniert (verifiziert)

- **Alle 10 Erst-Blocker behoben** (Abschnitt 5), **keine Regression**.
- **MySQL → PostgreSQL end-to-end** (S3): reverse/validate/generate/transfer,
  Zeilenzahlen + Enum-Daten + boolean-Mapping datenbelegt korrekt.
- **SQLite-Sequenz-Emulation** (S4): `dmg_sequences` + `_bi`/`_ai`-Trigger,
  Runtime `ref` = 1000/1001/1002 — unverändert zum Erstlauf.
- **`schema generate`-Diagnostik:** `E055`/`W123`/`W125`/`W102`/`E053`/`W103`/
  `W200` werden sachgerecht und an der richtigen Stelle ausgegeben (sauberer
  Skip statt invalidem DDL) — genau das im P2-Tracker geforderte Verhalten.
- **`E053`-Skip nicht-portierbarer View-/Function-/Trigger-Bodies** ist das
  bewusste Nicht-Ziel „kein SQL-Transpiler" korrekt umgesetzt.
- **Parquet** (S6): Export Single-File + **Import inkl. Timestamp** (I-10),
  inkrementeller Export, `data profile`.
- **`schema compare`:** Exit-Codes (0/1) korrekt, beide Operanden voll
  reverse-engineert.

---

## 9. Grenzen dieser Validierung

- **Kein Ersatz für menschliche Pilotgruppe** (Abschnitt 1).
- **Kein Performance-Benchmark**; genannte Laufzeiten rein indikativ. Employees
  (Scale) wurde in diesem Re-Run nicht geladen — der Fokus lag auf der
  Blocker-Re-Verifikation, nicht auf Durchsatz.
- **Reduzierte Dialekt-/Versions-Matrix** (je eine PG-/MySQL-/SQLite-Version).
- **Stored-Procedure-/MCP-/AI-Pfade** nicht Teil des Auftrags.
- Mehrere Szenarien wurden mit **klar etikettierten Test-Daten-Workarounds**
  (N1-Quoting) fortgeführt, um Downstream-Verhalten trotz Blocker zu messen.

---

## 10. Reproduktion

0.9.9-Image aus `develop` (HEAD `0facc838`) bauen
(`GRADLE_TASKS="assemble :adapters:driving:cli:installDist"`), je einen
PostgreSQL- und MySQL-Container im selben Docker-Netz starten, Pagila/Sakila aus
den in [Test-Database-Candidates](../open/test-database-candidates.md) genannten
Quellen laden, Verbindungen secret-frei über `.d-migrate.yaml` mit `${VAR}`, und
die in [Abschnitt 6](#6-szenario-ergebnisse) gezeigten Befehle im `/work`-
Docker-Stil aus [`guide.md`](../../user/guide.md) ausführen.

---

## 11. Empfehlung für den RC

Die Bugfix-Runde hat ihr Ziel erreicht: die gemeldeten Blocker sind weg, und
mindestens ein vollständiger Cross-Dialect-Pfad (MySQL→PG) ist jetzt sauber. Vor
dem 1.0.0-RC empfiehlt sich eine **zweite, eng geschnittene Fix-Runde** auf die
neu freigelegte Schicht — Priorität **N1** (häufigste Einzelursache),
**N3** (P1, Cross-Dialect-Datenpfad), **N2** (stiller Partition-Defekt), dann
N4–N8. Diese sind dem Muster der Erstlauf-Blocker sehr ähnlich (Tool-eigene
Abbildung vs. Preflight/Renderer) und vermutlich kompakt behebbar.

---

## 12. Verwandte Dokumente

- [Erst-Report](pilot-validation-0.9.9.md) · [P2-Blocker-Tracker](pilot-blocker-p2-tracker.md)
- [Migrations-Leitfaden](../../user/migrations-leitfaden.md) · [`guide.md`](../../user/guide.md) · [API-Referenz](../../user/api-referenz.md)
- [`spec/cli-spec.md`](../../../spec/cli-spec.md) · [ADR 0004](../../adr/0004-documentation-and-planning-structure.md) · [ADR 0012](../../adr/0012-index-prefix-length-scope.md)
- [Test-Database-Candidates](../open/test-database-candidates.md) · [Index-Prefix-Length-Model](../done/index-prefix-length-model.md)
