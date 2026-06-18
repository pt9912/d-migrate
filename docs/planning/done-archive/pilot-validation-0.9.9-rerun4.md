# Pilot-Validierung 0.9.9 (Beta) — Re-Validierungslauf 4 (nach K1-Fix)

> **Software-Version:** 0.9.9-SNAPSHOT (aus `develop` gebaut, HEAD `25ee2b78`, enthält K1-Fix `48d90adf`) · **Stand:** 17.06.2026
>
> **Art:** Automatisierte End-to-End-**Re-Validierung** (fünfter Lauf) als
> Breiten-Proxy für die Pilotanwender-Tests (Lastenheft 9.2), Vorbereitung
> 1.0.0-RC. Vorgänger (NICHT überschrieben):
> [Erstlauf](pilot-validation-0.9.9.md), [Re-Run 1](pilot-validation-0.9.9-rerun.md),
> [Re-Run 2](pilot-validation-0.9.9-rerun2.md), [Re-Run 3](pilot-validation-0.9.9-rerun3.md).
> Verbindlicher Rahmen: [Migrations-Leitfaden](../../user/migrations-leitfaden.md),
> [`guide.md`](../../user/guide.md), [API-Referenz](../../user/api-referenz.md),
> [`spec/cli-spec.md`](../../../spec/cli-spec.md). Ablage gemäß
> [ADR 0004](../../adr/0004-documentation-and-planning-structure.md).

---

## 1. Ehrlichkeits-Vorbehalt (zuerst lesen)

Lastenheft 9.2 verlangt **mindestens fünf menschliche Tester**. Dieser Bericht
ersetzt das **nicht**. Er ist eine **automatisierte Validierung durch einen
einzelnen Agenten** über repräsentative Szenarien als **Breiten-Proxy** —
reproduzierbare Befunde gegen einen definierten Build, aber keine Aussagen zu
Bedienbarkeit oder zur Vielfalt realer Betriebsumgebungen. Alle Behauptungen sind
durch echte Tool-Läufe belegt; keine erfundenen Zahlen; kein Performance-Benchmark.

**Build-Provenienz (transparent):** Der Auftrag nannte HEAD `48d90adf` (K1-Fix).
Der tatsächliche `develop`-HEAD zum Testzeitpunkt ist `25ee2b78`; `48d90adf` ist
dessen direkter **Vorfahr**, und das Delta `48d90adf..25ee2b78` ist
**ausschließlich Doku** (`docs/planning/open/README.md`,
`pilot-rerun-p3-residuals.md`) — der **Code ist identisch**. Gebaut und getestet
wurde aus `25ee2b78`; der K1-Fix ist damit enthalten.

---

## 2. Gesamt-Verdikt

**K1 ist behoben und datenbelegt abgenommen.** `PG text[] → MySQL JSON` wird jetzt
als **gültiges JSON-Array** gebunden (`["Trailers","Commentaries"]`, leeres Array
→ `[]`), Zeilenzahl Quelle = Ziel, und die **skalaren Nachbarspalten**
(Decimal/DateTime/Enum/Boolean) bleiben korrekt — keine Regression durch den
Array-Pfad. Alle früheren Fixes **M1, M2, N1–N6, I-01…I-10** sind regressionsfrei
(stichprobenartig **datenbelegt**), die M2-**Gegenprobe** (`text → integer`) bleibt
sauber **Exit 3**, und der Round-Trip PG → MySQL → SQLite läuft über beide Stufen.

**Ein neuer, angrenzender Befund (P2):** Sobald K1 den Transfer an
`film.special_features` (Array) vorbeilässt, wird an `film.fulltext` ein bisher
nie erreichter Defekt sichtbar — PostgreSQL-`tsvector` (pgjdbc-`PGobject`) wird
beim Transfer nach MySQL **Java-serialisiert** statt als String gebunden → Exit 5
([L1](#7-priorisierte-issue-liste-neue-befunde)). Es ist **dieselbe Klasse** wie
K1 (Wert-Binding eines PG-Spezialtyps), nur für `PGobject` statt `java.sql.Array`,
und betrifft Tabellen mit `tsvector`-Spalten.

| Szenario | Richtung | Re-Run 3 | Re-Run 4 (Abnahme 10.4) |
| -------- | -------- | -------- | ----------------------- |
| 1 Smoke | Pagila PG → PG | ✅ PASS | ✅ **PASS** — 22 Tab., payment 16049/16049, 15 Trigger (M1/N6) |
| 2 Compatibility | Pagila PG → MySQL | ⚠️ (K1) | ⚠️ **VERBESSERT** — Array-Pfad **dicht** (K1, datenbelegt); pagila.film nur noch an `tsvector` (L1) |
| 3 Compatibility | Sakila MySQL → PG | ✅ PASS | ✅ **PASS** — Transfer Exit 0, Daten verifiziert |
| 4 → SQLite | helper_table / W103 / E056 | ✅ PASS | ✅ **PASS** — N5-Runtime + Decimal→REAL |
| 5 Round-Trip | PG → MySQL → SQLite | ✅ PASS | ✅ **PASS** — Stufe A + B je Exit 0 |
| 6 Features | inkrementell / Parquet / profile | ✅ PASS | ✅ **PASS** — I-10 regressionsfrei |

---

## 3. Umgebung

| Komponente | Wert |
| ---------- | ---- |
| Host | Linux 6.8.0, x86-64, Docker Engine 29.5.3, Compose v5.1.4 (Einzelhost) |
| d-migrate | Image `d-migrate:0.9.9-pilot-rerun4`, aus `develop` (HEAD `25ee2b78`, enthält K1 `48d90adf`) mit `--build-arg GRADLE_TASKS="assemble :adapters:driving:cli:installDist"`; `--version` → `0.9.9-SNAPSHOT`. GHCR `:latest` ist weiterhin 0.9.8. |
| PostgreSQL / MySQL | `postgres:17.10-trixie` / `mysql:8.4.10` (Container `pilot-pg`/`pilot-my`, Netz `pilot-net`, aus Re-Run 3 weitergenutzt) |
| SQLite | Datei-Ziel über JDBC (xerial) bzw. Runtime-Gegenprobe via Python-`sqlite3` |
| Beispieldaten | Pagila (PG, 22 Tab.), Sakila (MySQL, 16 Tab. + 7 Views), `features` (PG), `arr_test` (PG, fokussierte K1-Fixture), `conf_src`/`conf_tgt` (M2-Gegenprobe) |

Verbindungen secret-frei über `.d-migrate.yaml` mit `${VAR}`; CLI als Container im
selben Docker-Netz, `/work`-Mount.

---

## 4. Methodik

- **Exit-Codes** gegen [API-Referenz 2.2](../../user/api-referenz.md) geprüft, vor
  jeder Shell-Pipe erfasst.
- **K1-Abnahme nicht nur „läuft durch":** Datentreue per `SELECT` in MySQL
  (`JSON_VALID`/`JSON_LENGTH`/`JSON_EXTRACT`) **und** Gegenprobe der skalaren
  Nachbarspalten. Da `pagila.film` zusätzlich eine `tsvector`-Spalte trägt (L1,
  s. u.), wurde der Array-Pfad sauber an einer **fokussierten Fixture** `arr_test`
  (text[] + Decimal + DateTime + Enum + Boolean, ohne tsvector) abgenommen.
- **Regression datenbelegt:** Zeilenzahlen + Stichproben je Pfad; M2-Gegenprobe
  explizit; M1 + N6 am Pagila-`last_updated`-Pfad.
- **Bewusste Nicht-Ziele — nicht als Bug gewertet:** K2 (Routinen-Ordering), N7,
  N8 (P3-Tracker [`pilot-rerun-p3-residuals.md`](../open/pilot-rerun-p3-residuals.md));
  View-/Function-/Trigger-Body-Transpilation (`E053`-Skip korrekt);
  PK-/Constraint-Präfixlängen ([ADR 0012](../../adr/0012-index-prefix-length-scope.md)).

---

## 5. Verifikation des K1-Fix (PG `text[]` → MySQL `JSON`)

**Fixture `arr_test` (PG):** `tags text[]`, `price decimal(10,2)`, `created_at
timestamp`, `active boolean`, `feeling` Enum. Generiertes MySQL-Ziel:
`tags JSON`, `price DECIMAL(10,2)`, `created_at DATETIME`, `active TINYINT(1)`,
`feeling ENUM('happy','sad','neutral')`. Transfer `rt_pg → arr_my`: **Exit 0**.

| Prüfung | Erwartet | Ergebnis |
| ------- | -------- | -------- |
| Transfer | Exit 0 (kein Exit 5) | ✅ **Exit 0** |
| Zeilenzahl | == Quelle (3) | ✅ 3 / 3 |
| `tags` Array → JSON | gültiges JSON-Array | ✅ `{Trailers,Commentaries}` → `["Trailers", "Commentaries"]`; `{"Deleted Scenes","Behind the Scenes"}` → `["Deleted Scenes", "Behind the Scenes"]`; `{}` → `[]`. `JSON_VALID=1`, `JSON_LENGTH` = 2/2/0, `JSON_EXTRACT($[0])` korrekt |
| Gegenprobe Decimal | 9.99 / 19.95 / 0.50 | ✅ |
| Gegenprobe Boolean | t/f/t → 1/0/1 | ✅ |
| Gegenprobe Enum | happy/sad/neutral | ✅ |
| Gegenprobe DateTime | `2026-06-17 10:00:00` | ✅ |

**K1: ✅ BEHOBEN** (datenbelegt; Array-Pfad sauber, skalare Nachbarspalten ohne
Regression).

---

## 6. Regressions-Sweep (datenbelegt)

| ID | Re-Check | Beleg |
| -- | -------- | ----- |
| M2 | ✅ | **Gegenprobe** `conf_src(text) → conf_tgt(integer)` bleibt **Exit 3** (`Text vs Integer`). Decimal→REAL / DateTime→Text / Array→JSON laufen. |
| M1 | ✅ | `CREATE OR REPLACE FUNCTION "last_updated"(…)` (bloßer Name); **0×** „function last_updated does not exist"; **15 Trigger** in `pagila_tgt`. |
| N1 | ✅ | PG `DEFAULT CURRENT_DATE`, MySQL `DEFAULT (CURRENT_DATE)`; PG-Apply 0 Fehler/22 Tab. |
| N2 | ✅ | `payment` plain + `E055`; pagila→PG Transfer Exit 0, **payment 16049/16049**. |
| N3 | ✅ | Preflight passiert Enum + Temporal (über M2-Struktur). |
| N4 | ✅ | PG `::`/`||`-Views → `E053`-Skip. |
| N5 | ✅ | Runtime `ref` = 1000/1001/1002 aus reverse-Schema; 1× PK AUTOINCREMENT. |
| N6 | ✅ | direkter `EXECUTE FUNCTION`; 15 Trigger (mit M1). |
| I-01 | ✅ | Sakila MySQL→PG Transfer Exit 0; boolean PG→MySQL `1,0,1`. |
| I-02 | ✅ | `validate` Pagila Exit 0. |
| I-03 | ✅ | Sakila→PG `film` 1000 Zeilen, rating `G/PG/PG-13/R/NC-17` (Case erhalten); `customer.active` = `boolean`. |
| I-04 | ✅ | Pagila→PG `film` 1000/1000. |
| I-05/I-06 | ✅ | `CREATE DOMAIN "bıgınt" AS BIGINT;`, `"year" … CHECK ((( … )))`. |
| I-07 | ✅ | MySQL `payment` PK `(payment_id, payment_date)`; `E055`. |
| I-08 | ✅ | PG `W123` (1), MySQL `W125` (3). |
| I-09 | ✅ | Sakila MySQL→PG 14× `E053`. |
| I-10 | ✅ | Parquet-Import 5 inserted, Exit 0. |
| Round-Trip | ✅ | Stufe A (PG→MySQL) + Stufe B (MySQL→SQLite) je Exit 0. |

**0 Regressionen.**

---

## 7. Priorisierte Issue-Liste (neue Befunde)

| ID | P | Titel | Minimal-Repro (erwartet → tatsächlich) |
| -- | - | ----- | -------------------------------------- |
| **L1** | **P2** | PG-`PGobject`-Spezialtyp (`tsvector`) wird beim Transfer nach MySQL Java-serialisiert statt als String gebunden | `pagila.film.fulltext` ist `tsvector` (udt `tsvector`), reverse → `text` (R301), MySQL-Ziel `TEXT`. `data transfer` PG → MySQL: **Exit 5** `Incorrect string value: '\xAC\xED\x00\x05sr…' for column 'fulltext'` — die Bytes `AC ED 00 05` sind der Java-`ObjectOutputStream`-Header. Erwartet: pgjdbc-`PGobject` über seine String-Form (`getValue()`/`toString()`) binden. Gleiche Klasse wie K1 (Wert-Binding eines PG-Spezialtyps), aber `PGobject` statt `java.sql.Array`. Erst durch den K1-Fix erreichbar (Transfer kommt jetzt bis `fulltext`). Betrifft PG→MySQL-Transfers von Tabellen mit `tsvector`-Spalten; **PG→PG** derselben Tabelle ist unberührt (Pagila→PG Exit 0). |

> **Einordnung:** L1 ist die direkte Fortsetzung der K1-Klasse (Wert-Binding für
> PG-Spezialtypen). Mit einer generischen `PGobject`→String-Behandlung im
> MySQL-Bind-Pfad wäre auch dieser letzte bekannte PG→MySQL-Datenpfad dicht. K2
> (Routinen-Ordering), N7, N8 bleiben bewusst P3.

---

## 8. Szenario-Notizen

- **S1 (PG → PG):** pre-data 0 Fehler/22 Tab.; Transfer Exit 0, alle Tabellen inkl.
  `payment` (16049) und `film` (mit `tsvector`/Array — PG→PG unkritisch); 15 Trigger
  + 6 Funktionen (M1/N6), Rest = K2 + N7 (Nicht-Ziele). **PASS.**
- **S2 (PG → MySQL):** generate sauber; pre-data 0 Fehler. **K1 datenbelegt** an
  `arr_test` (Array→JSON + skalare Spalten). `pagila.film` bricht nur noch an
  `tsvector` (L1) ab — alle anderen Spaltentypen (Array, Enum, Decimal, DateTime,
  Boolean, timestamptz) transferieren.
- **S3 (MySQL → PG):** Transfer Exit 0, 16 Tab.; Enum-Case + boolean datenbelegt.
  **PASS.**
- **S4 (→ SQLite):** `E056`/`W103`/`W200`; helper_table-Runtime `ref` =
  1000/1001/1002; Decimal→REAL. **PASS.**
- **S5 (Round-Trip):** Stufe A + B je Exit 0. **PASS.**
- **S6 (Features):** inkrementeller Export, Parquet-Export/-Import, `data profile`
  — alle Exit 0. **PASS.**

---

## 9. Grenzen dieser Validierung

- **Kein Ersatz für menschliche Pilotgruppe** (Abschnitt 1).
- **Kein Performance-Benchmark**; Employees (Scale) nicht geladen.
- Reduzierte Dialekt-/Versions-Matrix (je eine PG-/MySQL-/SQLite-Version).
- Stored-Procedure-/MCP-/AI-Pfade nicht Teil des Auftrags.
- **Keine** Test-Daten-Workarounds nötig.

---

## 10. Reproduktion

0.9.9-Image aus `develop` (HEAD `25ee2b78`, enthält K1 `48d90adf`) bauen
(`GRADLE_TASKS="assemble :adapters:driving:cli:installDist"`), Pagila/Sakila in je
einen PG-/MySQL-Container im Netz `pilot-net` laden, die fokussierte
`arr_test`-Fixture (text[] + Decimal/DateTime/Enum/Boolean) und die Gegenprobe-
Tabellen (`conf_src`/`conf_tgt`) anlegen, und die in
[Abschnitt 5](#5-verifikation-des-k1-fix-pg-text--mysql-json)/[6](#6-regressions-sweep-datenbelegt)
gezeigten Befehle im `/work`-Docker-Stil aus [`guide.md`](../../user/guide.md)
ausführen. Verbindungen secret-frei über `.d-migrate.yaml` mit `${VAR}`.

---

## 11. Empfehlung für den RC

K1 ist sauber abgenommen; alle in fünf Pilot-Läufen gemeldeten P1/P2 (I-01…I-10,
N1–N6, M1, M2, K1) sind behoben. **Ein** neuer P2 bleibt:

1. **L1 (P2)** — pgjdbc-`PGobject`-Werte (`tsvector` u. ä.) im MySQL-Bind-Pfad
   generisch über ihre String-Form binden statt Java-zu-serialisieren. Danach ist
   auch der PG→MySQL-Transfer von `tsvector`-tragenden Tabellen dicht. (Analog ist
   zu prüfen, ob andere PG-Extension-Typen denselben Pfad nehmen.)

P3 bleibt bewusst offen (K2/N7/N8,
[`pilot-rerun-p3-residuals.md`](../open/pilot-rerun-p3-residuals.md)). Mit L1
geschlossen wäre kein P1/P2-Cross-Dialect-Befund aus allen fünf Pilot-Läufen mehr
offen.

---

## 11a. Nachtrag — Closure (nach dem Lauf ergänzt, Stand 2026-06-18)

**L1 ist behoben.** Commit `c8115fc7` („fix(driver-mysql): L1 — pgjdbc-PGobject
(tsvector u. a.) PG→MySQL als String binden") verallgemeinert den K1-Wertkonverter
zu `JdbcForeignValueNormalizer` (driver-common): `java.sql.Array` → JSON **und**
pgjdbc-`PGobject` → `getValue()`-String (reflektiv über Paket `org.postgresql.*`,
kein pgjdbc-Compile-Dep), gebunden in `MysqlTableImportSession.bindRow`; mit
Regressionstest abgesichert. Damit ist — wie in §11 prognostiziert — **kein
P1/P2-Cross-Dialect-Befund aus allen fünf Pilot-Läufen mehr offen**.

L1 und K1 sind durch **Unit-Regressionstests** abgedeckt. Eine vollständige
**pilot-live E2E-Re-Verifikation** (eine `tsvector`-tragende Tabelle real
PG→MySQL transferieren) bleibt eine **optionale, nicht RC-blockierende**
Folgeaktivität. P3 (K2/N7/N8) bleibt bewusst offen. Mit diesem Nachtrag wandert
der Report nach `done-archive/` (Pilot-Validierungszyklus 0.9.9 abgeschlossen).

---

## 12. Verwandte Dokumente

- [Erstlauf](pilot-validation-0.9.9.md) · [Re-Run 1](pilot-validation-0.9.9-rerun.md) · [Re-Run 2](pilot-validation-0.9.9-rerun2.md) · [Re-Run 3](pilot-validation-0.9.9-rerun3.md) · [P3-Restbefunde](../open/pilot-rerun-p3-residuals.md)
- [Migrations-Leitfaden](../../user/migrations-leitfaden.md) · [`guide.md`](../../user/guide.md) · [API-Referenz](../../user/api-referenz.md) · [`spec/cli-spec.md`](../../../spec/cli-spec.md)
- [ADR 0004](../../adr/0004-documentation-and-planning-structure.md) · [ADR 0012](../../adr/0012-index-prefix-length-scope.md) · [Pilot-Validierungs-Playbook](../../operations/pilot-validation-playbook.md)
- [Test-Database-Candidates](../open/test-database-candidates.md)

---

## Anhang A — Agent-Brief (kopierbar, aus dem Pilot-Validierungs-Playbook §4)

> Übernommen aus [`docs/operations/pilot-validation-playbook.md`](../../operations/pilot-validation-playbook.md) §4 (Zeilen 48–117), `<version>` durch `0.9.9` ersetzt.

```text
ROLLE & ZIEL
Du bist Pilot-Tester für d-migrate (aktive Version 0.9.9). Validiere das Tool
end-to-end gegen reale Beispiel-Datenbanken und berichte schonungslos, was
funktioniert und was bricht — als Beta-Pilot-Validierung (Lastenheft 9.2),
Vorbereitung des 1.0.0-RC. Du behebst KEINE Bugs in diesem Lauf — du validierst
und berichtest; gefundene Fixes schlägst du als priorisierte Issues vor.

EHRLICHKEITS-VORBEHALT
Lastenheft 9.2 verlangt „mindestens 5 Tester" (Menschen). Ein Agent ersetzt das
nicht. Du lieferst eine automatisierte Validierung über >=5 repräsentative
Szenarien als Breiten-Proxy und weist genau das im Report aus — kein Vortäuschen
einer menschlichen Pilotgruppe. Keine erfundenen Zahlen; bei Perf-Messung die
Maschine/Container-Umgebung nennen.

ZUERST LESEN (Kontext, nicht überspringen)
- docs/user/migrations-leitfaden.md  — Workflow, Playbooks, Abnahme-Checkliste
- docs/user/guide.md                 — exakte Befehle/Flags
- docs/user/api-referenz.md, spec/cli-spec.md — CLI-Vertrag, Exit-Codes
- docs/user/administrationshandbuch.md — Deployment, Verbindungen
- docs/planning/open/test-database-candidates.md — Kandidaten + Teststaffelung
- examples/bi-demo/                   — Referenz docker-compose + d-migrate-Aufruf

UMGEBUNG (Voraussetzungen: Docker + Netzzugang für Sample-Dumps)
- d-migrate via GHCR-Image (ghcr.io/pt9912/d-migrate:latest) oder make-Build.
- Kandidaten-DBs in Containern, Beispiel-Dumps laden: Pagila (PostgreSQL),
  Sakila (MySQL), Employees (MySQL); SQLite-Ziel als Datei.
- Keine echten Kundendaten/Secrets; Verbindungen über .d-migrate.yaml / ${VAR}.

SZENARIEN (>=5, Teststaffelung Smoke -> Compatibility -> Scale folgen)
1. Smoke: Pagila PG -> reverse -> validate -> generate --split pre-post ->
   neues PG-Schema anlegen -> data transfer -> schema compare (muss clean sein).
2. PG -> MySQL (Pagila): Sequenz-Emulation (dmg_sequences), --trigger-mode
   disable, --on-conflict update.
3. MySQL -> PG (Sakila oder Employees): TINYINT(1)<->BOOLEAN, MySQL-Sequence-
   Emulation -> native PG-Sequenzen.
4. -> SQLite (--sqlite-named-sequences helper_table): Materialized Views (W103),
   E056-Verhalten.
5. Round-Trip PG -> MySQL -> SQLite (Abnahmeziel 8.6): jede Stufe mit
   schema compare einzeln abnehmen.
6. Feature-Stichproben: inkrementeller Export (--since-column/--since),
   Parquet-Transport (--format parquet), data profile.

PRO SZENARIO ERFASSEN
- exakte Befehle + Exit-Codes (gegen api-referenz 2.2 prüfen),
- schema-compare-Ergebnis (clean / erklärte Differenzen),
- Datenintegrität: Zeilenzahlen je Tabelle Quelle<->Ziel, Stichproben,
  Sequenz-Folgewerte,
- Befunde: Bug/Drift mit minimalem Repro (erwartet vs. tatsächlich). UNBEDINGT
  echten Tool-Bug von erwarteter Dialekt-Grenze (W103/E053/E056) unterscheiden.

ABNAHME je Szenario (Leitfaden-Checkliste 10.4)
[ ] reverse + validate ohne offene Errors
[ ] pre-data/post-data korrekt, Daten geladen
[ ] schema compare ohne unerwartete Differenzen
[ ] Zeilenzahlen + Stichproben verifiziert
[ ] Sequenzen korrekt

DELIVERABLE
- Strukturierter Markdown-Report (Ablage gemäß ADR 0004, Vorschlag:
  docs/planning/in-progress/pilot-validation-0.9.9.md): pro Szenario
  Setup/Befehle/Ergebnis/Exit-Codes/Befunde + Gesamt-Verdikt + priorisierte
  Issue-Liste (Titel, Schwere, Repro).
- make docs-check muss für den Report grün bleiben (Links/Anker/Pfade).
- Alle Behauptungen mit echten Läufen belegen; nichts erfinden.

GRENZEN
- Keine Tool-Fixes in diesem Lauf — nur validieren + berichten.
- Ersetzt keine menschliche Pilotgruppe (siehe Ehrlichkeits-Vorbehalt).
```
