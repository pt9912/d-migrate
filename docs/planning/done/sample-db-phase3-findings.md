# Sample-DB-Scale-Findings (Phase 3, Employees)

> Status: **Abschlussreif**. Phase 3 des Sample-DB-Harness
> ([`sample-db-integration-harness.md`](../done/sample-db-integration-harness.md))
> fährt erstmals den **datei-basierten `data export`→`import`-Pfad** (statt des
> direkten `data transfer` aus Phase 1/2), weil **nur** dieser Pfad `--resume`
> unterstützt. Dataset: Employees (`datacharmer/test_db@e324b561`, ~4 Mio Zeilen,
> 6 Basis-Tabellen). Wie erwartet („jeder neue Pfad deckt eigene Defekte auf")
> brachte der Scale-Lauf einen echten Befund: **S1 BEHOBEN** (PK-Nullability-
> Preflight). Export-Resume + Chunking + Dual-Target-Parität sind datenbelegt grün.

---

## Datenbelegt KORREKT (kein Defekt)

| Aspekt | Beleg |
|---|---|
| Export-Resume nach Interruption | Pass 1 `data export json --split-files` wird mitten im Stream hart unterbrochen (`docker kill`, sobald ein atomarer Checkpoint persistiert ist); Pass 2 `--resume <operationId>` vollendet das Bundle — alle 6 Tabellen-Dateien vorhanden. |
| Chunking | `--chunk-size 5000` gegen `salaries` (2.844.047 Zeilen ≈ 569 Chunks); Checkpoint-Snapshot belegt `chunksProcessed`. |
| Dual-Target-Parität | Zeilen-Parität Quelle == Ziel == gepinnte Baseline für **beide** Ziele (MySQL-Round-Trip + PG-Cross-Dialect), alle 6 Tabellen. |
| Werte-Integrität bei Volumen | `SUM(salary)=181480757419` round-trippt exakt nach MySQL **und** PG. |

Scope-Grenze: importiert/verglichen werden die **6 Basis-Tabellen** (Daten). Das
Ziel wird nur aus **pre-data** (Tabellen + PK) aufgebaut; FKs/Views (post-data)
sind Schema-Fidelity (Phase-2-Domäne) und hier bewusst **nicht** im Scope.

## S1 — PK-Spalten-Nullability im Import-Preflight · ✅ BEHOBEN

Der erste Import-Versuch (MySQL-Ziel) brach im Preflight ab:

```
Error: Table 'employees' does not match the provided --schema
(column 'emp_no' nullability mismatch: schema requires NULLABLE but target is NOT NULL)
```

**Ursache (Selbst-Inkonsistenz):** Die DDL-Generatoren emittieren `NOT NULL`
ausschließlich aus `ColumnDefinition.required`
(`if (col.required) parts += "NOT NULL"`). Für eine **Primärschlüssel-Spalte**
lässt `reverse` `required` aber **weg** (das Modell trägt die NOT-NULL-Eigenschaft
nicht redundant zur PK ein). `generate` erzeugt also `emp_no INT` + `PRIMARY KEY
(emp_no)` — und MySQL/PostgreSQL erzwingen für PK-Spalten **implizit NOT NULL**
über die PK-Klausel. Beim Reverse-Lesen des Ziels kommt `emp_no` daher als
`NOT NULL` zurück, während das `--schema`-Modell `required=false` (= NULLABLE)
sagt → `ImportTableValidator` meldet einen Mismatch. Ein Schema, das d-migrate
**selbst** reversed + generiert hat, wird so vom **eigenen** Import abgelehnt.

**Behoben:** `ImportTableValidator` rechnet die effektive Constraint als
`required || columnName in primaryKey` — das universelle DB-Invariant „eine
PK-Spalte ist implizit NOT NULL". Minimaler, korrekter Fix im Preflight (kein
Churn an reverse-/DDL-Goldens). Regressionstests in `ImportTableValidatorTest`
(Einzel- **und** Composite-PK ohne explizites `required` gegen NOT-NULL-Ziel →
kein Mismatch; der bestehende Nicht-PK-NULLABLE-vs-NOT-NULL-Mismatch bleibt).
Harness-belegt: `smoke-scale.sh` importiert nach dem Fix beide Ziele ohne
Preflight-Fehler, Parität + Checksumme grün.

---

## Baseline (gepinnt)

`examples/sample-db/expected/employees-scale.counts.txt` — Quell-Zeilenzahlen
(Dataset-Integrität) + `SUM(salary)`:

```
departments 9
employees 300024
dept_manager 24
dept_emp 331603
titles 443308
salaries 2844047
salary_sum 181480757419
```

Der Smoke prüft Quelle == Baseline (gepinnter Dump wirklich geladen) **und**
Ziel == Quelle (Transfer-Integrität), je Ziel.
