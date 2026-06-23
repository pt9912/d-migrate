# d-migrate im Vergleich zur Tool-Landschaft (Capability-Matrix)

> **Status:** Referenz-/Positionierungs-Sammlung (2026-06-23). Wie
> [`test-database-candidates.md`](test-database-candidates.md) ein dauerhaftes
> Referenz-Dokument, kein Slice.
> **Ausdrücklich KEIN Ziel:** Wettbewerbs-/Veröffentlichungs-taugliche Audit-Benchmark-
> Zahlen (Nicht-Ziel des TPC-Slice). Dies ist eine **qualitative Capability-Einordnung**
> für interne Positionierung. Roh-Durchsatz-Zahlen sind cross-tool **nicht** vergleichbar
> (siehe „Performance-Ehrlichkeit"). Recherche-Stand 2026-06-23, Quellen unten.

## Kernaussage

d-migrate ist **nicht „noch ein Migrationstool"**, sondern eine **wiederholbare,
überprüfbare Cross-Dialect-Migrations-Pipeline**: Schema-Konversion, Datenmigration mit
Typ-Konversion, Diff-basierte DDL-Planung, resumierbare Ausführung und **kanonische**
Verlustfreiheits-Verifikation in **einem selbst-gehosteten OSS-CLI**. Einzeltools decken
Teilbereiche gut ab; AWS erreicht die Breite nur durch die **Kombination zweier
managed/proprietärer Produkte** (SCT + DMS).

## Legende

| Symbol | Bedeutung |
|---|---|
| ✅ | native Kernfähigkeit |
| ◐ | teilweise / eingeschränkt / anderer Fokus |
| ❌ | nicht Ziel des Tools |
| — | nicht anwendbar |

## Matrix

| Fähigkeit (d-migrate-Achse) | d-migrate | Ora2Pg | AWS SCT+DMS | Atlas | Liquibase/Flyway | pgloader | COPY/`\copy` | pg_bulkload |
|---|---|---|---|---|---|---|---|---|
| **Cross-Dialect Schema-Konversion** | ✅ Matrix | ◐ nur Ziel PG | ✅ (SCT, sehr breit) | ❌ single-dialect | ❌ | ◐ nur Ziel PG | ❌ | ❌ |
| **Cross-Dialect Daten + Typ-Konversion** | ✅ Kern | ◐ nur Ziel PG | ✅ (DMS) | ❌ | ❌ | ◐ nur Ziel PG | ❌ | ❌ |
| **Diff→DDL-Generierung** | ✅ DiffPlanner | ❌ one-shot | ❌ one-shot | ✅ declarative | ◐ imperativ; Diff/Generate Enterprise | ❌ | ❌ | ❌ |
| **Verlustfreiheits-Verifikation** | ✅ kanon. SHA-256 | ✅ TEST_COUNT/TEST_DATA | ✅ row/hash (DMS) | ❌ | ❌ | ❌ | ◐ nur count | ❌ |
| **Resume nach Abbruch** | ✅ chunk/checkpoint | ❌ | ◐ task-/table-level restart; kein determ. Chunk-Resume | — | — | ❌ repeatable/reload, kein echtes Resume | ❌ | ❌ Migration-Resume; nur Recovery |
| **Self-hosted OSS-CLI** | ✅ | ✅ GPL | ❌ managed/proprietär | ◐ Apache-2 CE + EULA/Pro | ◐ Flyway OSS; Liquibase 5 FSL | ✅ | ✅ PostgreSQL-native Primitive | ✅ BSD |

## Hinweise zur Matrix (Präzisierung für kritische Leser)

- **Verlustfreiheit ist nicht gleich Verlustfreiheit — drei Stufen.** `count` (grobe
  Vollständigkeit) < `row/hash` (Integritätsprüfung mit **tool-/dialekt-spezifischer**
  Semantik, braucht meist PK/Unique) < **kanonischer Hash** (migrationsstabil,
  **typ-normalisiert über Dialekte hinweg**). d-migrates Differenzierer ist die
  **kanonische Normalisierung**, nicht „ein Hash": die drei ✅ in der Verifikations-Zeile
  sind **nicht** gleichwertig. AWS DMS' `row/hash` (DBMS_CRYPTO/pgcrypto) ist eine echte
  Integritätsprüfung, aber ohne dialekt-übergreifenden Normalisierungs-Vertrag.
- **AWS-DMS-Resume — widersprüchliche Doku.** Die AWS-**CLI**-Doku sagt, `resume-processing`
  lade bei Full Load teilweise/noch-nicht-geladene Tabellen neu; die DMS-**API**-Referenz
  sagt, `resume-processing` sei für Full-Load **nicht** anwendbar (teilweise geladene
  Tabellen nicht fortsetzbar). **Beides bestätigt** den Positionierungs-Punkt: **kein
  deterministisches Chunk-Resume** wie bei d-migrate — nur task-/table-level restart.

## Pro-Achse-Einordnung (wo der Vergleich fair ist — und wo er bricht)

- **Diff→DDL (4d):** fairster Peer = **Atlas** (declarative Diff→DDL = das DiffPlanner-
  Muster); Bytebase teilweise (Schema-Sync; declarative nur PostgreSQL); Liquibase/Flyway
  imperativ-zuerst (Diff/Generate bei Flyway **Enterprise-paid**).
- **Cross-Dialect Schema+Daten:** nächster Einzeltool-Peer = **Ora2Pg** (OSS-CLI,
  Schema+Daten+Verifikation in einem Binary) — bricht an **Ziel nur PostgreSQL** + **one-shot
  statt Diff**. **AWS SCT+DMS** ist breiter, aber **zwei** Produkte, managed/proprietär.
- **Daten-Durchsatz (4c):** **COPY** = native Decke (gleicher-Dialekt, file-basiert);
  **pgloader** = OSS-Peer-Tool (schreibt intern via COPY). **Alleinstellung d-migrate:**
  Verlustfreiheits-**Hash** + **Resume** + **JSON-Round-Trip** hat **keiner** der Bulk-Loader.
- **Wo es generell bricht:** **Cross-Dialect-Typ-Konversion + Datentransfer in einem OSS-CLI**
  — dafür gibt es **keinen** fairen Peer. Das ist d-migrates echter Differenzierer.

## Performance-Ehrlichkeit

- **Für Diff/DDL-Tools existieren KEINE veröffentlichten Speed-Benchmarks** (Atlas/Liquibase/
  Flyway/Bytebase — über offizielle Docs + Repos bestätigt). Speed-Vergleiche müssen
  **selbst** erzeugt werden.
- **Daten-Lade-Zahlen sind nicht cross-tool vergleichbar:** pgloaders offizielle Zahl ist
  selbst-disclaimt („don't read too much into the numbers"); COPY-Zahlen stammen von viel
  größeren Maschinen oder sind abgeleitet; pg_bulkload nennt Sekunden, keine rows/s.
- **Unsere 4c-Zahlen (~216k/~78k rows/s) sind diagnostisch** (Off-Spec-Host, ohne
  designierten Runner). **Nicht** mit Fremdzahlen vergleichbar.

→ **#2 (offen, empfohlen):** der einzige saubere Speed-Vergleich = **COPY (Decke) + pgloader
(Peer)** im selben Harness, identische TPC-H-Workload + Caps 2 CPU/4 GB, **nur Durchsatz**
(nicht Feature-Parität), gerahmt als **interner Sanity-Check**. Ergebnis: d-migrates rows/s
als Anteil der COPY-Decke + Hinweis auf die Allein-Features.

## Lizenz-Landschaft (Positionierungs-Punkt)

Die OSS-Migrations-Tooling-Landschaft fragmentiert: **Liquibase Community 5.0+ → FSL-1.1**
(Apache-2.0 erst 2 Jahre nach Release), **Atlas** (CE ist Apache-2.0; das Standardbinary/
Open Edition ist EULA-gated, Pro-Features lizenzpflichtig), **Bytebase** spaltet MIT-Kern
von proprietärem Enterprise; **Flyway Community** bleibt sauber Apache-2.0. Ein permissiv
lizenziertes d-migrate ist gegenüber mehreren davon differenziert.

## Quellen (load-bearing, Auswahl)

- AWS: [SCT Welcome](https://docs.aws.amazon.com/SchemaConversionTool/latest/userguide/CHAP_Welcome.html) ·
  [DMS Welcome](https://docs.aws.amazon.com/dms/latest/userguide/Welcome.html) ·
  [DMS Data Validation](https://docs.aws.amazon.com/dms/latest/userguide/CHAP_Validating.html) ·
  [DMS Restart/Resume](https://repost.aws/knowledge-center/dms-restart-resume-failed-task)
- Ora2Pg: [README](https://github.com/darold/ora2pg/blob/master/README) ·
  [LICENSE GPLv3](https://raw.githubusercontent.com/darold/ora2pg/master/LICENSE)
- Atlas: [declarative-vs-versioned](https://atlasgo.io/concepts/declarative-vs-versioned) ·
  [community-edition](https://atlasgo.io/community-edition)
- Liquibase: [FSL-Ankündigung](https://www.liquibase.com/blog/liquibase-community-for-the-future-fsl) ·
  [diff-changelog](https://docs.liquibase.com/commands/inspection/diff-changelog.html)
- Flyway: [diff/generate (Enterprise)](https://documentation.red-gate.com/flyway/reference/commands/generate)
- pgloader: [about (self-disclaimed bench)](https://pgloader.io/about/) ·
  [repo](https://github.com/dimitri/pgloader)
- COPY: [sql-copy](https://www.postgresql.org/docs/current/sql-copy.html) ·
  [populate](https://www.postgresql.org/docs/current/populate.html)
- pg_bulkload: [docs](https://ossc-db.github.io/pg_bulkload/pg_bulkload.html) ·
  [benchmark](https://ossc-db.github.io/pg_bulkload/index.html)
