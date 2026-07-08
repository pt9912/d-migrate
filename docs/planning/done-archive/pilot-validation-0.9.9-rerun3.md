# Pilot-Validierung 0.9.9 (Beta) — Re-Validierungslauf 3 (nach dritter Fix-Runde)

> **Software-Version:** 0.9.9-SNAPSHOT (aus `develop` gebaut, HEAD `8ed32d09`) · **Stand:** 17.06.2026
>
> **Art:** Automatisierte End-to-End-**Re-Validierung** (vierter Lauf) als
> Breiten-Proxy für die Pilotanwender-Tests (Lastenheft 9.2), Vorbereitung
> 1.0.0-RC. Vorgänger (NICHT überschrieben):
> [`pilot-validation-0.9.9.md`](pilot-validation-0.9.9.md) (Erstlauf),
> [`pilot-validation-0.9.9-rerun.md`](pilot-validation-0.9.9-rerun.md) (Re-Run 1),
> [`pilot-validation-0.9.9-rerun2.md`](pilot-validation-0.9.9-rerun2.md) (Re-Run 2).
> Verbindlicher Rahmen: [Migrations-Leitfaden](../../user/migrations-leitfaden.md),
> [`guide.md`](../../user/guide.md), [API-Referenz](../../user/api-referenz.md),
> [`spec/cli-spec.md`](../../../spec/cli-spec.md). Ablage gemäß
> [ADR 0004](../../adr/0004-documentation-and-planning-structure.md).

---

## 1. Ehrlichkeits-Vorbehalt (zuerst lesen)

Lastenheft 9.2 verlangt **mindestens fünf menschliche Tester**. Dieser Bericht
ersetzt das **nicht**. Er ist eine **automatisierte Validierung durch einen
einzelnen Agenten** über sechs repräsentative Szenarien als **Breiten-Proxy** —
reproduzierbare Befunde gegen einen definierten Build, aber keine Aussagen zu
Bedienbarkeit oder zur Vielfalt realer Betriebsumgebungen. Alle Behauptungen sind
durch echte Tool-Läufe belegt; keine erfundenen Zahlen; kein Performance-Benchmark.

---

## 2. Gesamt-Verdikt

**Die dritte Fix-Runde ist verifiziert; das Tool ist sehr nahe an RC-Reife.**
Beide in [Re-Run 2 Abschnitt 7](pilot-validation-0.9.9-rerun2.md) gemeldeten
Befunde sind behoben: **M2 (P1, Preflight strukturell) und M1 (P2, Routinennamen)**.
Die strukturelle Preflight-Regel ist **korrekt begrenzt** — die geforderte
**Gegenprobe** (ein echter `text → integer`-Konflikt) wird weiterhin sauber mit
**Exit 3** abgewiesen. Alle früheren Blocker **N1–N6** und **I-01…I-10** bleiben
behoben (**keine Regression**). Der Round-Trip PG → MySQL → SQLite (benignes
Schema) läuft jetzt über **beide** Stufen durch (je Exit 0).

**Eine echte Restlücke bleibt (P2):** Der strukturelle Preflight **öffnet** jetzt
das Tor für `PG text[] → MySQL JSON`, aber der **Wert-Konverter** dafür fehlt im
Transfer — der Lauf bricht zur Laufzeit mit Exit 5
(`NotSerializableException`, [K1](#7-priorisierte-issue-liste-neue-befunde)). Alle
**skalaren** Cross-Dialect-Datenpfade (inkl. Enum, Boolean, Decimal, DateTime,
timestamptz) sind dagegen **end-to-end sauber**.

| Szenario | Richtung | Re-Run 2 | Re-Run 3 (Abnahme 10.4) |
| -------- | -------- | -------- | ----------------------- |
| 1 Smoke | Pagila PG → PG | ✅ PASS (Tab.+Daten) | ✅ **PASS** — 22 Tab., alle Daten; Trigger lösen jetzt auf (M1/N6, 15 Trigger); `--include-all`-Routinen: K2 + N7 (Nicht-Ziel) |
| 2 Compatibility | Pagila PG → MySQL | ⚠️ VERBESSERT | ⚠️ **VERBESSERT** — Preflight passiert (M2); skalare Daten ok; nur `text[]→JSON`-Transfer (K1) Exit 5 |
| 3 Compatibility | Sakila MySQL → PG | ✅ PASS | ✅ **PASS** — Transfer Exit 0, Daten verifiziert |
| 4 → SQLite | helper_table / W103 / E056 | ✅ PASS | ✅ **PASS** — N5-Runtime + Decimal→REAL |
| 5 Round-Trip | PG → MySQL → SQLite | ⚠️ VERBESSERT | ✅ **PASS** — Stufe A + B je Exit 0 (M2 schließt Decimal→REAL/DateTime→Text) |
| 6 Features | inkrementell / Parquet / profile | ✅ PASS | ✅ **PASS** — I-10 regressionsfrei |

---

## 3. Umgebung

| Komponente | Wert |
| ---------- | ---- |
| Host | Linux 6.8.0, x86-64, Docker Engine 29.5.3, Compose v5.1.4 (Einzelhost) |
| d-migrate | Image `d-migrate:0.9.9-pilot-rerun3`, aus `develop` (HEAD `8ed32d09`) mit `--build-arg GRADLE_TASKS="assemble :adapters:driving:cli:installDist"`; `--version` → `0.9.9-SNAPSHOT`. GHCR `:latest` ist weiterhin 0.9.8 (ohne Fixes). |
| PostgreSQL / MySQL | `postgres:17.10-trixie` / `mysql:8.4.10` (Container `pilot-pg`/`pilot-my`, Netz `pilot-net`, aus Re-Run 2 weitergenutzt) |
| SQLite | Datei-Ziel über JDBC (xerial) bzw. Runtime-Gegenprobe via Python-`sqlite3` |
| Beispieldaten | Pagila (PG, 22 Tabellen), Sakila (MySQL, 16 Tab. + 7 Views), `features` (PG, benannte Sequenz + Matview), `conf_src`/`conf_tgt` (PG, Gegenprobe-Fixtures) |

Verbindungen secret-frei über `.d-migrate.yaml` mit `${VAR}`; CLI als Container im
selben Docker-Netz, `/work`-Mount.

---

## 4. Methodik

- **Exit-Codes** gegen [API-Referenz 2.2](../../user/api-referenz.md) geprüft, vor
  jeder Shell-Pipe erfasst.
- **Re-Run-Mandat:** M2 und M1 **explizit gegen ihr Original-Repro** aus
  [Re-Run 2 Abschnitt 7](pilot-validation-0.9.9-rerun2.md) gefahren, markiert mit
  **BEHOBEN / TEILWEISE / REGRESSION / NEUER BEFUND**; Regressions-Sweep N1–N6 +
  I-01…I-10. **M2-Gegenprobe** (echter `text→integer`-Konflikt) explizit gefahren,
  damit die strukturelle Regel nicht „akzeptiert alles" durchgewunken wird.
  **M1 + N6 kombiniert** am schärfsten Pfad (Pagila `last_updated`-Trigger +
  Funktion) geprüft.
- **Bewusste Nicht-Ziele — nicht als Bug gewertet:** N7/N8 (P3-Tracker
  [`pilot-rerun-p3-residuals.md`](../done/pilot-rerun-p3-residuals.md)),
  View-/Function-/Trigger-Body-Transpilation (`E053`-Skip ist korrekt),
  PK-/Constraint-Präfixlängen ([ADR 0012](../../adr/0012-index-prefix-length-scope.md)).

---

## 5. Verifikation der dritten Fix-Runde (M2, M1)

### 5.1 M2 (P1) — Preflight strukturell aus der Ziel-Typ-Abbildung — ✅ BEHOBEN (mit Restlücke K1)

| Teilprüfung | Erwartet | Ergebnis | Beleg |
| ----------- | -------- | -------- | ----- |
| **Gegenprobe** `text → integer` | bleibt **Exit 3** | ✅ | `Preflight: Column 't.val' type mismatch: Text(maxLength=null) vs Integer` — Regel **nicht zu locker**. |
| `Decimal → SQLite REAL` (PG→SQLite) | Exit 0 | ✅ | `price REAL`; Transfer Exit 0; Werte 9.99/19.95/5.00 datenbelegt. |
| `Decimal → SQLite REAL` (MySQL→SQLite) | Exit 0 | ✅ | Round-Trip Stufe B Transfer Exit 0. |
| `DateTime/timestamptz` Mappings | kein Exit 3 | ✅ | Stufe A PG→MySQL (timestamptz→DATETIME) + Stufe B (DateTime→Text) Exit 0. |
| `PG text[] → MySQL JSON` | Exit 0 | ⚠️ **TEILWEISE** | Preflight **passiert** (`special_features JSON`), aber Transfer **Exit 5** zur Laufzeit: Wert-Konverter PgArray→JSON fehlt → [K1](#7-priorisierte-issue-liste-neue-befunde). |

**Fazit M2:** Die strukturelle Preflight-Ableitung ist **behoben und korrekt
begrenzt**. Für skalare Typen ist der Cross-Dialect-Transfer end-to-end sauber.
Einzig die **Array→JSON-Wertkonvertierung** fehlt noch (K1, P2).

### 5.2 M1 (P2) — Routinennamen ohne Signatur-Suffix — ✅ BEHOBEN

| Prüfung | Erwartet | Ergebnis |
| ------- | -------- | -------- |
| Funktionsname | `CREATE OR REPLACE FUNCTION "last_updated"(…)` (bloßer Name) | ✅ generiert `"last_updated"()`, `"inventory_in_stock"(…)` (war `"last_updated()"`) |
| Trigger (N6 kombiniert) | direktes `EXECUTE FUNCTION last_updated()`, löst auf | ✅ alle **15 Trigger** in `pagila_tgt` angelegt; **0×** „function last_updated() does not exist" (Re-Run 2: 14×) |
| Funktions-Apply | keine Namens-Auflösungsfehler | ✅ `last_updated`/`inventory_in_stock`/`get_customer_balance` etc. angelegt (6 Routinen) |

**Restbefund (nicht M1):** Zwei verbleibende post-data-Fehler stammen aus der
**Routinen-Emissions-Reihenfolge** — die SQL-Funktion `film_in_stock` referenziert
`inventory_in_stock`, das erst später emittiert wird (Forward-Reference) → [K2](#7-priorisierte-issue-liste-neue-befunde).
Vorbestehend, `--include-all`-Pfad, **keine** M1-Regression.

---

## 6. Regressions-Sweep (N1 … N6, I-01 … I-10)

| ID | Re-Check | Beleg |
| -- | -------- | ----- |
| N1 | ✅ | PG `DEFAULT CURRENT_DATE`, MySQL `DEFAULT (CURRENT_DATE)`; PG-Apply 0 Fehler/22 Tab., MySQL 0 Fehler. |
| N2 | ✅ | `payment` plain Table + `E055`; Transfer Exit 0, **payment 16049/16049**. |
| N3 | ✅ | Preflight passiert `film.rating` (Enum) + `created_at` (Temporal). |
| N4 | ✅ | PG `::`/`||`-Views → `E053`-Skip; MySQL-Apply 0 Fehler. |
| N5 | ✅ | `ref` als `sequence_nextval`; 1× PK AUTOINCREMENT; Runtime `ref` = 1000/1001/1002. |
| N6 | ✅ | direkter `EXECUTE FUNCTION`; 15 Trigger angelegt (zusammen mit M1). |
| I-01 | ✅ | Sakila MySQL→PG Transfer Exit 0 (16 Tab.); boolean PG→MySQL `1,0,1,0,1`. |
| I-02 | ✅ | `validate` Pagila Exit 0. |
| I-03 | ✅ | Sakila `film.rating` Enum-Case erhalten (`G,PG,PG-13,…`). |
| I-04 | ✅ | Pagila→PG: `film` (Enum) 1000/1000 transferiert. |
| I-05/I-06 | ✅ | `CREATE DOMAIN "bıgınt" AS BIGINT;`, `"year" … CHECK ((( … )))` (ein Wrapper). |
| I-07 | ✅ | MySQL `payment` PK `(payment_id, payment_date)`; `E055`-Skip. |
| I-08 | ✅ | PG `W123` (1), MySQL `W125` (3). |
| I-09 | ✅ | Sakila MySQL→PG 14× `E053`. |
| I-10 | ✅ | Parquet Export+Import Exit 0, 5 inserted, Timestamp intakt. |

**0 Regressionen.**

---

## 7. Priorisierte Issue-Liste (neue Befunde)

| ID | P | Titel | Minimal-Repro (erwartet → tatsächlich) |
| -- | - | ----- | -------------------------------------- |
| **K1** | **P2** | Transfer-Wertkonverter PG-`text[]` → MySQL `JSON` fehlt | Nach dem M2-Preflight-Fix wird `Array(text) → Json` **zugelassen** (Spalte korrekt als `JSON` generiert), aber `data transfer` bricht zur Laufzeit mit **Exit 5** ab: `Cannot convert class org.postgresql.jdbc.PgArray to SQL type requested … NotSerializableException`. Erwartet: Array-Wert als JSON serialisieren (z. B. `["Trailers","Deleted Scenes"]`). Skalare Mappings (Decimal/DateTime/Enum/Boolean) konvertieren korrekt — nur Array→JSON fehlt. Letzte Lücke für vollständige PG→MySQL-Datentreue. |
| **K2** | **P3** | `--include-all`-Routinen werden nicht topologisch nach Abhängigkeiten geordnet | SQL-Funktion `film_in_stock` referenziert `inventory_in_stock`, das **später** im DDL emittiert wird → `ERROR: function inventory_in_stock(integer) does not exist` (2×). Zusätzlich eine Funktion mit `RETURN NEXT` ohne `RETURNS SETOF` (1×). Vorbestehend; nur optionaler Functions-Export (`--include-all`); angrenzend an das getrackte N7. |

> **Einordnung:** K1 ist die **einzige verbleibende P2** für volle
> Cross-Dialect-Datentreue und betrifft ausschließlich Array-Spalten (PG→MySQL).
> K2 betrifft nur den optionalen Routinen-Export. Beide blockieren **nicht** die
> skalaren Schema-+Daten-Kernpfade.

---

## 8. Szenario-Notizen

- **S1 (PG → PG):** pre-data 0 Fehler/22 Tab.; Transfer Exit 0, **alle** Tabellen
  inkl. `payment` (16049); post-data: **15 Trigger + 6 Funktionen** angelegt
  (M1/N6), Rest = K2 + N7 (Nicht-Ziel). Kern-Smoke **PASS**.
- **S2 (PG → MySQL):** generate sauber (`E055`/`W125`/`E053` inkl. `::`/`||`);
  pre-data 0 Fehler/23 Tab. (N1/N4). Transfer: **Preflight passiert** (M2,
  inkl. Enum + Array-Spalte), bricht zur Laufzeit nur an `special_features`
  (Array→JSON, K1). Skalar-Datenpfad sauber.
- **S3 (MySQL → PG):** Transfer Exit 0, 16 Tab.; Enum-Case + boolean datenbelegt.
  **PASS.**
- **S4 (→ SQLite):** `E056`/`W103`/`W200`; helper_table-Runtime `ref` =
  1000/1001/1002; reverse→generate-Round-Trip valide (N5); Decimal→REAL. **PASS.**
- **S5 (Round-Trip PG→MySQL→SQLite):** Stufe A Exit 0 (boolean `1,0,1,0,1`),
  Stufe B Exit 0 (Decimal→REAL + DateTime→Text). **PASS** (beide Stufen).
- **S6 (Features):** inkrementeller Export, Parquet-Export, **Parquet-Import
  (Timestamp)**, `data profile` — alle Exit 0. **PASS.**

---

## 9. Grenzen dieser Validierung

- **Kein Ersatz für menschliche Pilotgruppe** (Abschnitt 1).
- **Kein Performance-Benchmark**; Employees (Scale) nicht geladen.
- Reduzierte Dialekt-/Versions-Matrix (je eine PG-/MySQL-/SQLite-Version).
- Stored-Procedure-/MCP-/AI-Pfade nicht Teil des Auftrags.
- **Keine** Test-Daten-Workarounds nötig.

---

## 10. Reproduktion

0.9.9-Image aus `develop` (HEAD `8ed32d09`) bauen
(`GRADLE_TASKS="assemble :adapters:driving:cli:installDist"`), Pagila/Sakila in je
einen PG-/MySQL-Container im Netz `pilot-net` laden, `features`-Schema + die
Gegenprobe-Tabellen (`conf_src` text-Spalte / `conf_tgt` integer-Spalte) anlegen,
und die in [Abschnitt 5](#5-verifikation-der-dritten-fix-runde-m2-m1)/[6](#6-regressions-sweep-n1--n6-i-01--i-10)
gezeigten Befehle im `/work`-Docker-Stil aus [`guide.md`](../../user/guide.md)
ausführen. Verbindungen secret-frei über `.d-migrate.yaml` mit `${VAR}`.

---

## 11. Empfehlung für den RC

Die dritte Fix-Runde hat ihr Ziel erreicht: M2 (strukturell, korrekt begrenzt) und
M1 sind behoben, keine Regressionen, und der Round-Trip läuft jetzt durch. Für die
volle Cross-Dialect-Datentreue bleibt **ein** P2:

1. **K1 (P2)** — Wert-Konverter `PG text[] → MySQL JSON` ergänzen (Array als
   JSON-Array serialisieren). Danach ist auch der PG→MySQL-Datenpfad für
   Array-Spalten dicht; alle skalaren Pfade sind es bereits.
2. **K2 (P3)** — optionaler Functions-Export topologisch ordnen + `RETURNS SETOF`
   bei `RETURN NEXT` (nur `--include-all`).

N7/N8 bleiben bewusst als P3 offen
([`pilot-rerun-p3-residuals.md`](../done/pilot-rerun-p3-residuals.md)). Mit K1
geschlossen wäre kein P1/P2-Cross-Dialect-Befund aus den vier Pilot-Läufen mehr
offen.

---

## 12. Verwandte Dokumente

- [Erstlauf](pilot-validation-0.9.9.md) · [Re-Run 1](pilot-validation-0.9.9-rerun.md) · [Re-Run 2](pilot-validation-0.9.9-rerun2.md) · [P3-Restbefunde](../done/pilot-rerun-p3-residuals.md)
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
