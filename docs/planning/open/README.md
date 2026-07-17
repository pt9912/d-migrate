# Trigger Watches und offene Vorabklärungen

Dieser Ordner sammelt Trigger, offene Folgearbeiten und Vorabklärungen,
die noch **keinen konkreten Scope** tragen. Sobald ein Scope formuliert
ist (Ziel + grobe Arbeitspakete + Akzeptanzkriterien), wandert der
Eintrag nach `../next/`.

Lebenszyklus und Verzeichnisstruktur sind in
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
festgehalten.

## Konvention für Einträge

- Sprechender lowercase-kebab-Dateiname (z. B.
  `test-database-candidates.md`,
  `d-browser-integration-coupling-assessment.md`).
- Jeder Eintrag beschreibt im Kopf:
  - **Status**: `Draft` / `Vorschlag` / `Sammlung` / `Vorabklärung`
  - **Trigger**: was hat den Eintrag motiviert?
  - **Aktivierungsbedingung**: was muss passieren, damit er nach
    `../next/` wandert?
- Worked Examples und reine Referenz-/Sammlungs-Dokumente
  (z. B. `test-database-candidates.md`) dürfen hier liegen, auch
  wenn sie nie nach `next/` migrieren — sie sind dauerhaft
  "trigger-/referenz-haft" ohne Slice-Charakter.

## Wann **nicht** hierher

- Plan mit ausgearbeitetem Scope und Phasen → `../next/`.
- Aktiv bearbeiteter Slice mit Implementierungs-Commits →
  `../in-progress/`.
- Geliefert und geschlossen → `../done/`.
- Verworfen / vollständig überholt → `docs/archive/` (existiert
  bei Bedarf, siehe [ADR-0004](../../adr/0004-documentation-and-planning-structure.md)).

## Bestand

| Datei | Typ | Gegenstand |
| ----- | --- | ---------- |
| [`beispiel-stored-procedure-migration.md`](beispiel-stored-procedure-migration.md) | Worked Example | Beispiel fuer KI-gestuetzte Stored-Procedure-Migration von PostgreSQL nach MySQL. |
| [`cli-unimplemented-commands.md`](cli-unimplemented-commands.md) | Sammlung/Tracker | In `cli-spec.md` (Zielbild) beschriebene, aber noch nicht in der CLI registrierte Befehle (`transform procedure`, `generate procedure`, `data seed`, `validate data/procedure`, `config …`) + offene Trigger-Erweiterungen — Spec ↔ Requirement ↔ Milestone. |
| [`d-browser-integration-coupling-assessment.md`](d-browser-integration-coupling-assessment.md) | Vorabklaerung | Bewertung sichtbarer Kopplungen zwischen `d-browser` und `d-migrate` vor einem dedizierten `source-d-migrate`-Adapter. |
| [`mcp-server-spec-hygiene-residuals.md`](mcp-server-spec-hygiene-residuals.md) | Sammlung/Tracker | Rest-Hygiene in `spec/mcp-server.md` nach der Entphasung: 19 bare `§`-Referenzen (Stil + mehrdeutiges Ziel ImpPlan-B/ki-mcp) und eine möglicherweise veraltete `tools/call`-Verhaltensaussage (Vertrags-Genauigkeit). || [`import-throughput-binary-copy.md`](import-throughput-binary-copy.md) | Draft (Trigger Watch) | COPY-Fast-Path auf weitere Typen ausweiten (Geometrie als **EWKB-Hex** statt `ST_GeomFromWKB`-SQL-Wrap; json/array/enum/interval/xml via COPY-Text/binär), die heute konservativ auf den INSERT-Rückfall fallen. Aktiviert bei geometrie-/json-lastigen Workloads mit COPY-Durchsatz-Ziel; nicht LF-blockierend. |
| [`partition-child-local-fk-transparency.md`](partition-child-local-fk-transparency.md) | Vorschlag (Draft) | Kind-lokale FKs auf PG-Partitionen (z. B. Pagila-`payment`-Kinder) beim Reverse erfassen, damit sie PG→PG round-trippen und PG→MySQL sichtbar via E065 verworfen werden statt still wegzufallen. Aus Carve-Out (`carveout.md` Abschnitt 9); braucht FK-Feld auf `PartitionDefinition` + ADR 0019/0020-Ergänzung. |
| [`partition-list-default-transfer-preflight.md`](partition-list-default-transfer-preflight.md) | Vorschlag (Draft) | Transfer-Zeit-Preflight, der die in eine PG-LIST-`DEFAULT`-Partition fallenden Zeilen zählt (kein MySQL-Pendant → Datenverlust), nach dem `CheckPreflight`-Muster (planner→runner→renderer→report). Ergänzt die bestehende statische Generate-Note E063. Aus Carve-Out (`carveout.md` Abschnitt 9). |
| [`mutation-testing-pit.md`](mutation-testing-pit.md) | Vorschlag (Draft) | PIT-Mutation-Testing für die JVM-Module als Test-Wirksamkeits-Signal über die Kover-Zeilen-Coverage hinaus. Trigger (stabile Coverage-Baseline + konsolidierte Excludes) erfüllt. Kernfrage = Kotlin-Reibung von Standard-PIT vs. Arcmutate; Start opt-in/Nightly auf `hexagon:core`. Aus Carve-Out (`carveout.md` Abschnitt 7). |
| [`pg-only-types-first-class-candidates.md`](pg-only-types-first-class-candidates.md) | Sammlung/Trigger-Watch | PG-only-Typen (`inet`, `cidr`, `tsquery`, Range/Multirange, `ltree`, `hstore`, `money`, …), die heute zu `text` + R301 degradieren — Kandidaten für eine first-class-Modellierung (`geometry`-/`fulltext`-Muster), je Typ eine eigene Entscheidung bei belegtem Fidelity-Bedarf. Aus Carve-Out (`carveout.md` Abschnitt 8). |
| [`profiling-query-and-normalization.md`](profiling-query-and-normalization.md) | Vorschlag (Draft) | Nachruesten der in 0.7.5 bewusst zurueckgestellten Profiling-Flags `--query` und `--analyze-normalization` (Strukturanalyse, FD-Discovery, Normalisierungsvorschlaege) — ohne LLM-Schicht (Spec §10). |
| [`rest-grpc-artifact-ref-inheritance.md`](rest-grpc-artifact-ref-inheritance.md) | Vorschlag (Trigger Watch) | Auflage, dass die kuenftigen REST- (1.2.0) und gRPC- (1.1.8) Jobvertraege das opake `ServerResourceUri`-Artifact-Ref-Modell erben (MCP nutzt es bereits); aktiviert beim Bau der Services. |
| [`slice-adr-cross-class-direction-check.md`](slice-adr-cross-class-direction-check.md) | Tracker (wartet auf Tool-Feature) | Cross-Class-SDP-Richtung Vertrag › ADR › Slice mechanisieren, sobald d-check ein klassenübergreifendes Richtungs-Feature (slice↔adr) bietet; muss normativ-vs-Kontext unterscheiden, sonst flaggt es die 19 legitimen `adr→plan`-Provenienz-Refs. v0.30.0 `direction: no-downward` ist nur klassenintern (Spec-Straten Vertrag › Technik › Sicht). |
| [`sqlite-reverse-identifier-64bit-narrowing.md`](sqlite-reverse-identifier-64bit-narrowing.md) | Vorabklärung/Entscheidung | SQLite-Reverse mappt jede AUTOINCREMENT-PK (64-bit rowid) auf den 32-bit-`identifier`-Vertrag — Cross-Dialect-Transfer verengt still (m-trace-Befund 2026-07-02). Sofortmaßnahme geliefert (R202-Note + `generation` in schema.json); **Option 2 durch Reverse-Präferenzen-Slice abgelöst (2026-07-05)**; offen nur noch migrate-gegen-SQLite + Option 3. |
| [`sqlite-migrate-biginteger-identity-render-gap.md`](sqlite-migrate-biginteger-identity-render-gap.md) | Draft (Trigger Watch) | `migrate --execute` von authored `biginteger`+`identity` gegen ein SQLite-Ziel driftet aus **zwei** Ursachen: Diff/Rebuild-Renderpfad (`SqliteDiffSqlBuilders`) implementiert Identity nicht (AUTOINCREMENT still verloren) **und** die Reverse-Präferenz fädelt nicht in den Post-Compare-Re-Read. Abspaltung des Reverse-Präferenzen-Slices; P3. |
| [`enum-inline-check-fidelity.md`](enum-inline-check-fidelity.md) | Vorabklärung/Entscheidung | Enum-Fidelity „2b" aus dem Enum-Migrate-Slice: PG-inline + SQLite-Enum im Diff als `TEXT`+`CHECK` **plus** Reverse-Rekonstruktion `col IN (…)` → `Enum` (sonst neue Post-Compare-CHECK-Kante). Design-Gabelung offen (Reverse-Rekonstruktion via Reverse-Präferenz-Registry vs. Kanonisierer-Fold vs. Status quo); Fidelity-Upgrade, kein Bugfix. |
| [`g2-neutrales-typmodell-jdbc-typcodes.md`](g2-neutrales-typmodell-jdbc-typcodes.md) | Vorschlag (Draft) | G2 aus [ADR 0028](../../adr/0028-a-check-architecture-gate-scope.md): `TargetColumn.jdbcType: Int` / `JdbcTypeHint.jdbcType: Int` durch ein neutrales Typmodell ersetzen. **`make a-check` grün beweist nur G1** — die Typcodes tragen JDBC-Semantik ohne `java.sql`-Import („falsch-grün"), als Ausnahme ratifiziert. War nach der Slice-Graduierung nirgends getrackt. Scope kleiner als gedacht: der Parquet-Manifest-Schreibpfad ist bereits neutral. Offen = Gestalt des Ersatzmodells (ADR-würdig). |
| [`test-database-candidates.md`](test-database-candidates.md) | Referenzsammlung | Externe Beispieldatenbanken fuer Smoke-, Regression-, Streaming-, Resume- und Integrationsverifikation. |
| [`tool-comparison.md`](tool-comparison.md) | Referenzsammlung | Capability-Matrix d-migrate vs. Migrations-/Schema-/Bulk-Load-Tools (Ora2Pg, AWS SCT+DMS, Atlas, Liquibase/Flyway, pgloader, COPY, pg_bulkload), quellenbelegt + due-diligence-gehärtet. **#2 Head-to-Head erledigt + 2026-06-25 neu gemessen** (`make sample-db-tool-compare`: nach Schritt 0 + COPY ist d-migrate ~3,0× COPY (vorher ~4,6×) / ~1,7× pgloader; Import ~86k→~174k rows/s, Abstand zur Decke ~5,4×→~2,8×; diagnostisch). **Kein** Audit-Zahlen-Ziel. |
| [`warn-code-ledger-completeness.md`](warn-code-ledger-completeness.md) | Tracker | 8 emittierte W-Codes (W100/102/103/111/127/128/200/201) fehlen im YAML-Ledger; `CodeLedgerValidationTest` erzwingt keine W-Code-Vollständigkeit gegen den Source. AP1 Backfill + AP2 Gate-Härtung (symmetrisch zur E-Code-Vollständigkeit). Aufgedeckt beim W132-Fulltext-Code. |
