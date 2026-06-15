# Migrations-Leitfaden

> **Status:** 🚧 Gerüst (Milestone 0.9.9). Abschnitte sind gegliedert und mit
> Quellverweisen versehen, aber noch nicht ausformuliert.
>
> **Marker:** ✅ vorhanden (übernehmen) · ♻️ aus Spec konsolidieren · 🔲 neu ·
> 🔮 geplant (späterer Milestone)
>
> **Zielgruppe:** Personen, die eine konkrete Datenbank-Migration mit d-migrate
> planen und durchführen. Setzt Grundkenntnisse aus dem
> [Anwenderhandbuch](anwenderhandbuch.md) voraus; Kommandodetails in der
> [API-Referenz](api-referenz.md).

---

## 1. Einführung

- 1.1 Migrationsphilosophie: neutrales Modell als Pivot — ♻️
  [`../../spec/neutral-model-spec.md`](../../spec/neutral-model-spec.md)
- 1.2 Was d-migrate migriert (Schema, Daten, Sequenzen, Trigger/Routinen) — 🔲
- 1.3 Grenzen und bewusste Nicht-Ziele — 🔲

## 2. Der Migrations-Workflow im Überblick

- 2.1 Phasenmodell: reverse → compare → generate → transfer/verify — 🔲
- 2.2 Entscheidungsbaum: direkter Transfer vs. Artefakt-basiert — 🔲

## 3. Vorbereitung

- 3.1 Quell- und Zielanalyse — 🔲
- 3.2 Verbindungen einrichten (`.d-migrate.yaml`) — ♻️ connection-config-spec §3
- 3.3 Kompatibilität prüfen (Dialekt-Unterschiede) — ♻️
  [`../../spec/type-mapping.md`](../../spec/type-mapping.md)

## 4. Schema-Migration

- 4.1 Reverse Engineering der Quelle — ✅ [`guide.md`](guide.md) / cli-spec §6
- 4.2 Ziel-DDL generieren — ✅ `guide.md` „DDL generieren"
- 4.3 Split-DDL für sichere Import-Reihenfolge (pre-data/post-data) — ✅ `guide.md`
- 4.4 Compare/Verifikation des Zielschemas — ✅ `guide.md` „Schemas vergleichen"

## 5. Daten-Migration

- 5.1 Export/Import vs. direkter DB-zu-DB-Transfer — ♻️ cli-spec §6
- 5.2 Reihenfolge und Constraint-Handhabung — 🔲
- 5.3 Trigger während des Imports deaktivieren — ✅ `guide.md`
- 5.4 Idempotenter UPSERT-Import — ✅ `guide.md`
- 5.5 Inkrementelle Migration (LF-013) — ✅ `guide.md`
- 5.6 Parquet als Transportformat — ✅ `guide.md` „Parquet"

## 6. Spezialfälle und Stolpersteine

- 6.1 Sequenzen: PostgreSQL vs. MySQL/SQLite-Emulation — ✅ `guide.md`
- 6.2 `preserveCurrentValue` korrekt nutzen — ✅ `guide.md`
- 6.3 Trigger, Functions, Procedures — ✅ `guide.md` / ♻️ Specs
- 6.4 Materialized Views — 🔲
- 6.5 Typ-Mapping-Risiken und Präzisionsverlust — ♻️ type-mapping
- 6.6 Round-Trip-Risiko verstehen — ✅ `guide.md` „Round-Trip-Risiko"

## 7. End-to-End-Playbooks

- 7.1 PostgreSQL → MySQL — 🔲 (vollständiges Beispiel: Schema + Daten + Sequenzen)
- 7.2 MySQL → PostgreSQL — 🔲
- 7.3 → SQLite — 🔲
- 7.4 Cross-DB Round-Trip PG → MySQL → SQLite — 🔲 (Abnahmeziel 8.6 für 1.0.0)

## 8. Export in Migrations-Frameworks

- 8.1 Flyway — ♻️ [`releasing.md`](releasing.md) §3.3 / cli-spec
- 8.2 Liquibase — ♻️ releasing.md §3.3
- 8.3 Django — ♻️ releasing.md §3.3
- 8.4 Knex — ♻️ releasing.md §3.3

## 9. Stored-Procedure-Migration

- 9.1 Beispiel und Vorgehen — ♻️
  [`../planning/open/beispiel-stored-procedure-migration.md`](../planning/open/beispiel-stored-procedure-migration.md)

## 10. Validierung und Abnahme

- 10.1 Schema-Compare als Abnahmegate — ✅ `guide.md`
- 10.2 Datenintegrität (Zeilen-/Stichprobenvergleich) — 🔲
- 10.3 SHA-256-Verifikation — 🔮 (1.0.0-RC, LN-009)
- 10.4 Checkliste für Pilot-Migrationen — 🔲 (verknüpft mit Pilot-Programm 0.9.9)
