# Pilot-Validierung 0.9.9 (Beta) — Re-Validierungslauf 2 (nach zweiter Fix-Runde)

> **Software-Version:** 0.9.9-SNAPSHOT (aus `develop` gebaut, HEAD `b7f515ac`) · **Stand:** 17.06.2026
>
> **Art:** Automatisierte End-to-End-**Re-Validierung** (dritter Lauf) als
> Breiten-Proxy für die Pilotanwender-Tests (Lastenheft 9.2), Vorbereitung
> 1.0.0-RC. Vorgänger (NICHT überschrieben):
> [`pilot-validation-0.9.9.md`](pilot-validation-0.9.9.md) (Erstlauf) und
> [`pilot-validation-0.9.9-rerun.md`](pilot-validation-0.9.9-rerun.md) (Re-Run 1).
> Verbindlicher Rahmen: [Migrations-Leitfaden](../../user/migrations-leitfaden.md),
> [`guide.md`](../../user/guide.md), [API-Referenz](../../user/api-referenz.md),
> [`spec/cli-spec.md`](../../../spec/cli-spec.md). Kandidaten-DBs:
> [Test-Database-Candidates](../open/test-database-candidates.md). Ablage gemäß
> [ADR 0004](../../adr/0004-documentation-and-planning-structure.md).

---

## 1. Ehrlichkeits-Vorbehalt (zuerst lesen)

Lastenheft 9.2 verlangt **mindestens fünf menschliche Tester**. Dieser Bericht
ersetzt das **nicht**. Er ist eine **automatisierte Validierung durch einen
einzelnen Agenten** über sechs repräsentative Szenarien als **Breiten-Proxy** und
liefert reproduzierbare Befunde gegen einen definierten Build — aber keine
Aussagen zu Bedienbarkeit oder zur Vielfalt realer Betriebsumgebungen.

Alle Behauptungen sind durch echte Tool-Läufe belegt. Es wurden **keine Zahlen
erfunden**. Kein formaler Performance-Benchmark.

---

## 2. Gesamt-Verdikt

**Die zweite Fix-Runde ist vollständig verifiziert — RC-Reife in greifbarer
Nähe.** Alle sechs in [Re-Run 1 Abschnitt 7](pilot-validation-0.9.9-rerun.md)
gemeldeten neuen Befunde **N1–N6 sind gegen ihr Original-Repro behoben**, und die
zehn Erst-Blocker **I-01…I-10 bleiben behoben (keine Regression)**. Zwei zuvor
blockierte Pfade laufen jetzt zusätzlich sauber: **Pagila PG → PG** legt Schema +
**alle Daten inkl. der ehemals partitionierten `payment`** an (N1/N2), und die
**SQLite-Named-Sequence-Emulation funktioniert jetzt auch aus einem
reverse-engineerten Schema** (N5).

**Noch offen vor RC — zwei Punkte:**
- **M2 (P1):** Der Transfer-Preflight ist weiterhin **nicht erschöpfend** — die
  N3-Repros (Enum↔Enum, Temporal→Text) sind behoben, aber zwei weitere
  Tool-eigene Abbildungen derselben Klasse blockieren noch:
  `Array(text)→Json` (PG→MySQL) und `Decimal→Float` (→SQLite). Das ist dieselbe
  „Whack-a-Mole"-Mechanik wie bei I-01/N3 und blockiert die PG→MySQL- und
  →SQLite-Datenpfade.
- **M1 (P2):** Durch den N6-Fix freigelegt — generierte Funktionsnamen tragen ihre
  Parameter-Signatur als Literal (`"last_updated()"`), sodass Trigger-/View-
  Referenzen nicht auflösen (`function … does not exist`). Betrifft nur den
  `--include-all`-Pfad (Functions/Trigger).

| Szenario | Richtung | Re-Run 1 | Re-Run 2 (Abnahme 10.4) |
| -------- | -------- | -------- | ----------------------- |
| 1 Smoke | Pagila PG → PG | ⚠️ FAIL | ✅ **PASS** (Tabellen+Daten) — N1/N2 behoben, 22 Tab. + alle Zeilen inkl. `payment`; Functions/Trigger (`--include-all`) durch M1 offen |
| 2 Compatibility | Pagila PG → MySQL | ⚠️ FAIL | ⚠️ **VERBESSERT** — DDL appliziert sauber (N1/N4, 23 Tab.); Transfer durch M2 (`Array→Json`) blockiert |
| 3 Compatibility | Sakila MySQL → PG | ✅ PASS | ✅ **PASS** — Transfer Exit 0, Daten verifiziert (regressionsfrei) |
| 4 → SQLite | helper_table / W103 / E056 | ✅ PASS | ✅ **PASS** — Feature + Runtime; **reverse→generate-Round-Trip jetzt ok** (N5) |
| 5 Round-Trip | PG → MySQL → SQLite | ⚠️ TEILWEISE | ⚠️ **VERBESSERT** — Stufe A ok; Stufe B durch M2 (`Decimal→Float`) blockiert |
| 6 Features | inkrementell / Parquet / profile | ✅ PASS | ✅ **PASS** — I-10 regressionsfrei |

---

## 3. Umgebung

| Komponente | Wert |
| ---------- | ---- |
| Host | Linux 6.8.0, x86-64, Docker Engine 29.5.3, Compose v5.1.4 (Einzelhost, kein Benchmark-Rig) |
| d-migrate | Image `d-migrate:0.9.9-pilot-rerun2`, aus `develop` (HEAD `b7f515ac`) mit `--build-arg GRADLE_TASKS="assemble :adapters:driving:cli:installDist"`; `--version` → `0.9.9-SNAPSHOT`. GHCR `:latest` ist weiterhin 0.9.8 (ohne Fixes) — daher aus Quellcode gebaut. |
| PostgreSQL / MySQL | `postgres:17.10-trixie` / `mysql:8.4.10` (Container `pilot-pg`/`pilot-my` aus Re-Run 1 weitergenutzt, Netz `pilot-net`) |
| SQLite | Datei-Ziel über JDBC (xerial) bzw. Runtime-Gegenprobe via Python-`sqlite3` |
| Beispieldaten | Pagila (PG, 22 Tabellen), Sakila (MySQL, 16 Tabellen + 7 Views), `features` (PG, benannte Sequenz + Materialized View) |

Verbindungen secret-frei über `.d-migrate.yaml` mit `${VAR}`-Substitution; CLI als
Container im selben Docker-Netz, `/work`-Mount.

---

## 4. Methodik

- **Exit-Codes** gegen [API-Referenz 2.2](../../user/api-referenz.md) geprüft, vor
  jeder Shell-Pipe erfasst.
- **Re-Run-Mandat:** Jeder Zweitrunden-Fix N1–N6 **explizit gegen sein
  Original-Repro** aus [Re-Run 1 Abschnitt 7](pilot-validation-0.9.9-rerun.md)
  gefahren, markiert mit **BEHOBEN / TEILWEISE / REGRESSION / NEUER BEFUND**.
  Zusätzlich Regressions-Sweep I-01…I-10.
- **Bewusste Nicht-Ziele — nicht als Bug gewertet:** N7 (Custom-Aggregat von
  reverse nicht erfasst) und N8 (MySQL→PG Index-Namens-Kollision) sind als P3 in
  [`pilot-rerun-p3-residuals.md`](../in-progress/pilot-rerun-p3-residuals.md) getrackt;
  View-/Function-/Trigger-Body-Transpilation ist ein Nicht-Ziel (`E053`-Skip ist
  korrekt); PK-/Constraint-Präfixlängen sind out of scope
  ([ADR 0012](../../adr/0012-index-prefix-length-scope.md)).

---

## 5. Verifikation der zweiten Fix-Runde (N1 … N6)

| ID | Original-Repro (Re-Run 1) | Status | Beleg |
| -- | ------------------------- | ------ | ----- |
| **N1** | `CURRENT_DATE`-Default als String-Literal `'CURRENT_DATE'` → ungültiges DDL (PG invalid date, MySQL 1067) | ✅ **BEHOBEN** | Generiert **PG** `DEFAULT CURRENT_DATE`, **MySQL** `DEFAULT (CURRENT_DATE)`, **SQLite** `DEFAULT CURRENT_DATE`. Pagila pre-data Apply PG **0 Fehler / 22 Tab.** und MySQL **0 Fehler / 23 Tab.** (war 1067 + Cascade). |
| **N2** | PG-Partition-Tabelle ohne Kind-Partitionen + ohne Warnung → Transfer Exit 5 „no partition found" | ✅ **BEHOBEN** | generate: `E055`-Note „… has no child partitions … Created as a plain (non-partitioned) table"; `payment` als plain Table. **Transfer Exit 0, `payment` 16049 Zeilen übertragen** (exakt). |
| **N3** | Preflight blockt PG-Named-Enum↔MySQL-Inline-Enum und DateTime→SQLite-Text → Exit 3 | ✅ **BEHOBEN** (beide Repros) | Preflight passiert jetzt `film.rating` (PG→MySQL) und `product.created_at` (→SQLite). **Restklasse offen** → [M2](#7-priorisierte-issue-liste-neue-befunde). |
| **N4** | PG-View mit `::`-Cast / `||`-Concat → MySQL: kein Skip, ERROR 1064 | ✅ **BEHOBEN** | `E053`: „… not portable to MySQL (PostgreSQL-style cast (::); PostgreSQL/SQLite-style concatenation (\|\|))" für customer_list/staff_list/sales_by_store. MySQL pre-data Apply **0 Fehler** (kein 1064). |
| **N5** | PG Nicht-PK-`nextval`-Spalte → SQLite: doppelte `PRIMARY KEY AUTOINCREMENT` | ✅ **BEHOBEN** | reverse erfasst `ref` als `default: {sequence_nextval: orders_ref_seq}` (nicht `generation.type: identity`). SQLite-DDL: `ref INTEGER`, genau **1** `PRIMARY KEY AUTOINCREMENT`. Runtime: `ref` = **1000, 1001, 1002**. |
| **N6** | Trigger-Body `AS $$ EXECUTE FUNCTION … $$` → syntax error | ✅ **BEHOBEN** | Trigger emittiert direkt `EXECUTE FUNCTION last_updated();` ohne `$$`-Wrapper. **Restbefund** Funktionsname → [M1](#7-priorisierte-issue-liste-neue-befunde). |

**Zusammenfassung:** 6/6 Zweitrunden-Repros **behoben**. Bei N3 und N6 wurde — wie
bei den Erst-Blockern — je eine **angrenzende Restlücke** sichtbar (M2 bzw. M1).

---

## 6. Regressions-Sweep der Erst-Blocker (I-01 … I-10)

| ID | Re-Check | Beleg |
| -- | -------- | ----- |
| I-01 | ✅ kein Regress | Transfer passiert timestamptz→DATETIME (S2) + boolean (S5-A) + MySQL→PG (S3 Exit 0). |
| I-02 | ✅ kein Regress | `validate` Pagila Exit 0 (kein E009). |
| I-03 | ✅ kein Regress | Sakila `film.rating` → `[G,PG,PG-13,R,NC-17]`, `default: G`; end-to-end Verteilung Quelle = Ziel. |
| I-04 | ✅ kein Regress | Pagila `film`-Enum transferiert (1000 Zeilen). |
| I-05/I-06 | ✅ kein Regress | `CREATE DOMAIN "bıgınt" AS BIGINT;`, `"year" … CHECK ((( … )))` (ein Wrapper). |
| I-07 | ✅ kein Regress | MySQL `payment` PK `(payment_id, payment_date)` — AUTO_INCREMENT führt; `E055`-Skip. |
| I-08 | ✅ kein Regress | PG `W123` (GIST-Skip); MySQL `W125` (TEXT-Index-Skip). |
| I-09 | ✅ kein Regress | MySQL→PG: 14× `E053` (alle Sakila-Views); PG→MySQL: `E053` inkl. `::`/`||`. |
| I-10 | ✅ kein Regress | Parquet-Import in leere Tabelle: 5 inserted, Timestamp `2026-06-17 12:40:54.14235` identisch. |

**0 Regressionen.**

---

## 7. Priorisierte Issue-Liste (neue Befunde)

| ID | P | Titel | Minimal-Repro (erwartet → tatsächlich) |
| -- | - | ----- | -------------------------------------- |
| **M2** | **P1** | Transfer-Preflight-Kompatibilitätsmatrix weiterhin nicht erschöpfend | Weitere Tool-eigene Abbildungen fehlen: **(a)** PG `text[]` → MySQL `JSON`: `Preflight: Column 'film.special_features' type mismatch: Array(elementType=text) vs Json` → Exit 3. **(b)** `Decimal(10,2)` → SQLite-`Float`/REAL: `Preflight: Column 'product.price' type mismatch: Decimal(...) vs Float(DOUBLE)` → Exit 3. Gleiche Klasse wie I-01/N3. **Empfehlung:** Preflight aus der **eigenen Typ-Mapping-Tabelle des Generators** ableiten statt Fall-für-Fall pflegen (strukturell statt Whack-a-Mole). |
| **M1** | **P2** | Generierte Funktionsnamen enthalten die Parameter-Signatur als Literal-Identifier | reverse keyt Funktionen als `last_updated()` / `inventory_in_stock(in:integer)`; generate emittiert `CREATE FUNCTION "last_updated()"()`. Trigger/Views referenzieren `last_updated()` (ohne Signatur) → `ERROR: function last_updated() does not exist` (14×) beim PG→PG-Apply. Durch den N6-Fix freigelegt; nur `--include-all`-Pfad (Functions/Trigger). |

> **Hinweis:** M2 ist die direkte Fortsetzung der I-01/N3-Klasse und derzeit der
> einzige **P1**-Rest, der dokumentierte Cross-Dialect-Datenpfade (PG→MySQL,
> →SQLite) blockiert. M1 betrifft nur den optionalen Functions/Trigger-Export.

---

## 8. Szenario-Notizen

- **S1 (PG → PG):** reverse/validate/generate Exit 0; pre-data Apply **0 Fehler /
  22 Tabellen** (N1); `transfer` Exit 0 mit **allen** Tabellen inkl. `payment`
  (16049) und Partition-Children — Zeilenzahlen Quelle = Ziel exakt (N2).
  post-data (Functions/Trigger/Views, `--include-all`): M1 (Funktionsnamen) +
  N7 (Aggregat, getracktes Nicht-Ziel). Kern-Smoke (Tabellen+Daten) = **PASS**.
- **S2 (PG → MySQL):** generate sauber (`E055`/`W125`/`W102`/`E053` inkl. `::`/`||`);
  pre-data Apply **0 Fehler / 23 Tabellen** (N1/N4). `transfer` Exit 3 durch M2
  (`special_features` Array→Json) — Preflight passiert dabei `film.rating` (N3).
- **S3 (MySQL → PG):** `transfer` Exit 0, 16 Tabellen, Daten + Enum-Case +
  boolean datenbelegt korrekt. **PASS.**
- **S4 (→ SQLite):** default-Mode `E056`/`W103`/`W200`; helper_table-Runtime
  `ref` = 1000/1001/1002; **reverse→generate-Round-Trip jetzt valide** (N5).
  **PASS.**
- **S5 (Round-Trip):** Stufe A (PG→MySQL) Transfer Exit 0, boolean-Treue exakt;
  Stufe B (MySQL→SQLite) Exit 3 durch M2 (`price` Decimal→Float) — Preflight
  passiert dabei `created_at` (N3). I-02 (SQLite-Temporal-Default) regressionsfrei.
- **S6 (Features):** inkrementeller Export, Parquet-Export, **Parquet-Import
  (Timestamp)** und `data profile` alle Exit 0. **PASS.**

---

## 9. Grenzen dieser Validierung

- **Kein Ersatz für menschliche Pilotgruppe** (Abschnitt 1).
- **Kein Performance-Benchmark**; Employees (Scale) nicht geladen — Fokus auf
  Fix-Verifikation.
- Reduzierte Dialekt-/Versions-Matrix (je eine PG-/MySQL-/SQLite-Version).
- Stored-Procedure-/MCP-/AI-Pfade nicht Teil des Auftrags.
- **Keine** Test-Daten-Workarounds nötig — die N1-Quoting-Workaround-Stellen aus
  Re-Run 1 entfallen, da N1 behoben ist.

---

## 10. Reproduktion

0.9.9-Image aus `develop` (HEAD `b7f515ac`) bauen
(`GRADLE_TASKS="assemble :adapters:driving:cli:installDist"`), Pagila/Sakila in je
einen PG-/MySQL-Container im selben Docker-Netz laden, `features`-Schema (benannte
Sequenz + Materialized View) in PG anlegen, und die in
[Abschnitt 5](#5-verifikation-der-zweiten-fix-runde-n1--n6)/[8](#8-szenario-notizen)
gezeigten Befehle im `/work`-Docker-Stil aus [`guide.md`](../../user/guide.md)
ausführen. Verbindungen secret-frei über `.d-migrate.yaml` mit `${VAR}`.

---

## 11. Empfehlung für den RC

Die zweite Fix-Runde hat ihr Ziel erreicht: N1–N6 sind weg, keine Regressionen,
und zwei weitere Pfade (PG→PG-Daten, SQLite-Named-Sequence-Round-Trip) sind jetzt
sauber. Vor dem 1.0.0-RC bleibt im Kern **ein** strukturell wichtiger Punkt:

1. **M2 (P1)** — den Transfer-Preflight aus der generatoreigenen Typ-Abbildung
   ableiten, damit nicht jede Cross-Dialect-Typabbildung (Array→Json,
   Decimal→Float, …) einzeln nachgepflegt werden muss. Das schließt die
   PG→MySQL- und →SQLite-Datenpfade.
2. **M1 (P2)** — Funktionsnamen ohne Signatur-Suffix generieren (nur
   `--include-all`-relevant).

N7/N8 bleiben bewusst als P3 offen
([`pilot-rerun-p3-residuals.md`](../in-progress/pilot-rerun-p3-residuals.md)).

---

## 12. Verwandte Dokumente

- [Erstlauf](pilot-validation-0.9.9.md) · [Re-Run 1](pilot-validation-0.9.9-rerun.md) · [P2-Blocker-Tracker](pilot-blocker-p2-tracker.md) · [P3-Restbefunde](../in-progress/pilot-rerun-p3-residuals.md)
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
