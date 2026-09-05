---
status: accepted
date: 2026-09-05
decision-makers: pt9912
consulted: docs/planning/done/ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md
informed: adapters/driven/persistence-jdbc (Flyway-Migrationen), docs/user/administrationshandbuch.md (§10.1 Upgrade-Workflow)
---

# Server-State-Schema wird neutral gepflegt, Flyway-Migrationen werden generiert statt handgeschrieben

> **Status: accepted (2026-09-05).** Die Server-State-DB (`V1__server_state_initial.sql`,
> `V2__schema_artifact_stores.sql`, …) hat ab sofort eine neutrale
> Schema-Quelle (`adapters/driven/persistence-jdbc/src/main/resources/db/schema/server-state-schema.yaml`,
> Kumulativ-Soll-Zustand). Neue Migrationen entstehen per `schema migrate`-
> Diff gegen den vorherigen committeten Stand dieser Datei, nicht per Hand.
> Flyway bleibt unverändert der Anwendungs-/Tracking-Mechanismus in
> Produktion (`JdbcMigrationRunner`, `flyway_phase_e_history`).

## Kontext und Problemstellung

`V1__server_state_initial.sql` war reines, handgeschriebenes SQL — mit der
Begründung im Kommentar, das neutrale Schema-Modell könne JSONB/partielle
Indizes nicht ausdrücken und die Server-State-DB solle vom eigenen
Migrations-Werkzeug isoliert bleiben. Beim Anlegen von `V2` (ImpPlan-1.2.0-
mcp-server-state-schema-artifact-persistence.md, Schema-/Artefakt-
Persistenz) wurde diese Begründung hinterfragt und **beide Teile davon
erwiesen sich als falsch**, nicht nur ungenau:

- `NeutralType.Json` rendert für PostgreSQL bereits `JSONB`
  (`PostgresTypeMapper.kt:34`).
- `IndexDefinition.where` ist modelliert und wird gerendert
  (`PostgresDdlGenerator.kt:249`: `WHERE ${index.where}`).

Ein Praxistest bestätigte das: der komplette `V1`-Ist-Zustand (fünf
Tabellen: `idempotency_reservations`, `init_resume_reservations`, `jobs`,
`quota_reservation_owners`, `quota_counters`, inkl. des partiellen Index
`quota_owners_expiry ... WHERE state = 'PENDING'`) ließ sich verlustfrei als
neutrales Schema nachbilden; `schema generate --target postgresql` rendert
daraus DDL, das mit dem handgeschriebenen `V1` bis auf
Identifier-Quoting identisch ist. Ein anschließender `schema migrate
--source <desired> --target file:<current> --dialect postgresql --dry-run`
rendert exakt das Delta (nur die beiden neuen Tabellen), nicht die
komplette Datenbank neu — die für einen additiven Migrationsschritt
richtige Semantik.

`export flyway` (der naheliegende Kandidat für "Flyway-Format aus
d-migrate") ist dafür **nicht** geeignet: `ToolExportRunner.kt:157` ruft
immer `generator.generate()` auf den vollen Schema-Snapshot — ein
Full-State-Baseline-Export, kein Diff. Für ein additives `V2` würde das
versuchen, `V1`s Tabellen erneut anzulegen.

## Entscheidungstreiber

- d-migrate ist ein Schema-Migrations-Werkzeug; die eigene Server-
  State-DB per Hand-SQL zu pflegen, während das Werkzeug genau das für
  jede fremde Datenbank automatisiert, ist ein Integritäts-/Vertrauens-
  Widerspruch, sobald keine technische Notwendigkeit mehr dafür besteht.
- Die ursprüngliche technische Begründung (Modell-Lücke) ist widerlegt.
- Der Produktions-Ops-Workflow (`administrationshandbuch.md` §10.1:
  `JdbcMigrationRunner(dataSource).migrate()` als expliziter Deploy-
  Schritt, `flyway_phase_e_history`-Tracking-Tabelle) ist etabliert,
  getestet und dokumentiert — ihn zu ersetzen wäre ein eigenes, größeres
  Vorhaben mit Live-Risiko für eine Datenbank, von der der MCP-Server
  selbst abhängt.

## Betrachtete Optionen

- **Alles bleibt Hand-SQL** — Status quo, keine Widerlegung der falschen
  Begründung nachgezogen.
- **Server-State-DB komplett auf `schema migrate --target db:<url>
  --execute` als Ops-Schritt umstellen** — ersetzt Flyway vollständig.
  Größeres, riskanteres Vorhaben (kein History-Tracking-Äquivalent zu
  `flyway_schema_history`, Live-Rollback-Semantik ungeklärt); nicht Teil
  dieser Entscheidung.
- **Neutrales Schema als Quelle, Flyway-Datei-Inhalt per Diff generiert,
  Flyway bleibt Anwendungsmechanismus.** **Gewählt.**

## Entscheidungsergebnis

Gewählt: **neutrales Schema als Quelle, generierter Flyway-Dateiinhalt,
unveränderter Ops-Workflow.**

`adapters/driven/persistence-jdbc/src/main/resources/db/schema/server-state-schema.yaml`
trägt den kumulativen Soll-Zustand (Stand: nach `V2`, alle sieben
Tabellen). Verfahren für eine künftige `V<N>`:

1. Den zuletzt committeten Stand der Datei sichern (`git show
   HEAD:adapters/driven/persistence-jdbc/src/main/resources/db/schema/server-state-schema.yaml
   > /tmp/current.yaml`).
2. Die Arbeitskopie der Datei um die neue(n) Tabelle(n)/Spalte(n)
   erweitern (Soll-Zustand).
3. `schema migrate --source server-state-schema.yaml --target
   file:/tmp/current.yaml --dialect postgresql --dry-run --output
   /tmp/vN-up.sql` — das Delta rendern.
4. Den Inhalt von `/tmp/vN-up.sql` in eine neue
   `V<N>__<slug>.sql`-Datei übernehmen (Identifier-Quoting nach
   Repo-Konvention entfernen, Spaltentyp `TIMESTAMP WITH TIME ZONE` →
   `TIMESTAMPTZ` für Kürze — beides ist in PostgreSQL ein reines
   Synonym, keine Verhaltensänderung).
5. Beides zusammen committen: die neue Migrationsdatei **und** die
   aktualisierte `server-state-schema.yaml`.

Flyway bleibt unverändert der Anwendungs-/Tracking-Mechanismus
(`JdbcMigrationRunner`, `flyway_phase_e_history`, der Produktions-Ops-
Workflow aus `administrationshandbuch.md` §10.1) — dieser ADR ändert nur,
**womit** der SQL-Inhalt einer neuen Migrationsdatei erzeugt wird, nicht
**wie** sie angewendet/getrackt wird.

## Konsequenzen

- **Pflegecommitment:** jede künftige Änderung an der Server-State-DB
  zieht `server-state-schema.yaml` mit — eine Migration, die das nicht
  tut, lässt die Datei hinter der echten DB zurückdriften und macht das
  Verfahren für den nächsten Schritt unbrauchbar (der Diff würde dann
  gegen einen falschen Ist-Zustand rechnen). Reviewer prüfen das bei
  jeder neuen `V<N>`-Migration.
- **Kein automatisierter Gate-Check**, dass `server-state-schema.yaml`
  tatsächlich mit der Summe aller angewendeten Migrationen
  übereinstimmt — das bleibt manuelle Sorgfalt, analog zu anderen
  Konventionen in diesem Repo, die nicht mechanisch erzwungen werden
  (z. B. Referenz-Richtung in `spec/`).
- **`export flyway` bleibt unverändert** ein Full-State-Baseline-Export
  für externe Flyway-Projekte, die ein d-migrate-Schema adoptieren wollen
  — eine andere Zielgruppe als dieser interne Server-State-Anwendungsfall.
- Der Vergleich "generiertes DDL vs. Hand-SQL" für `V1` bleibt als
  Nachweis in
  [`ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md`](../planning/done/ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md)
  dokumentiert.
