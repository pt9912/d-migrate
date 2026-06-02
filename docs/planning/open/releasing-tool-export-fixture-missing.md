# releasing.md §3.3 — Tool-Export-Smoke verweist auf nicht-existierende Fixture

**Status**: Vorabklärung

**Trigger**: Beim 0.9.7-Pre-Release-Smoke 2026-06-02 fiel auf, dass
[`docs/user/releasing.md`](../../user/releasing.md) §3.3
„Tool-Export-Smoke" auf
`adapters/driven/integrations/src/test/resources/fixtures/export-test-schema.yaml`
verweist. Diese Datei existiert nicht im Repo — die
Integrations-Tests (`FlywayMigrationExporterTest`,
`LiquibaseMigrationExporterTest`, `DjangoMigrationExporterTest`,
`KnexMigrationExporterTest`) verwenden programmatische
`SchemaDefinition`-Objekte statt YAML-Fixtures.

Der 0.9.7-Pre-Release-Smoke wurde mit Substitut-Fixture
`adapters/driven/formats/src/test/resources/fixtures/schemas/minimal.yaml`
gefahren — alle vier Tool-Exporter (Flyway / Liquibase / Django /
Knex) lieferten erwartete Artefakte. Die `releasing.md`-Referenz auf
die nicht-existierende Datei bleibt aber ein Doku-Defekt, der bei
einem neuen Release-Operator zu Verwirrung führt.

**Aktivierungsbedingung**: Trigger feuert mit einer der beiden
Auflösungen:

1. **Fixture-Datei anlegen**: ein dediziertes
   `adapters/driven/integrations/src/test/resources/fixtures/export-test-schema.yaml`
   mit einem Schema, das alle vier Tool-Export-Pfade sinnvoll
   stresst (mindestens eine Tabelle mit FK, ein Index, eine View,
   ein Trigger, eine Sequence — siehe
   `FlywayMigrationExporterTest`-Programmatik für das Minimum).
   Vorteil: realistischer Smoke, deckt mehr Artefakt-Pfade ab als
   `minimal.yaml`.

2. **`releasing.md` auf existierende Fixture umbiegen**: §3.3 auf
   `adapters/driven/formats/src/test/resources/fixtures/schemas/minimal.yaml`
   (oder besser `full-featured.yaml` mit mehr Coverage) ändern.
   Vorteil: schnell, kein neues Test-Asset zu pflegen.

Empfehlung: Variante 2 reicht, wenn die programmatischen
Integrations-Tests die Tool-Export-Pfade weiterhin als
Source-of-Truth pinnen. Variante 1 erst, wenn der Smoke einen
breiteren Vertrag als „Datei wird erzeugt" pinnen soll
(Plan-Artefakt-Sidecar, OperationalReport, etc.).

---

## Verweise

- [`docs/user/releasing.md`](../../user/releasing.md) §3.3 zur
  Korrektur.
- [`adapters/driven/integrations/src/test/kotlin/dev/dmigrate/integration/`](../../../adapters/driven/integrations/src/test/kotlin/dev/dmigrate/integration/)
  — bestehende programmatische Exporter-Tests.
- [`adapters/driven/formats/src/test/resources/fixtures/schemas/`](../../../adapters/driven/formats/src/test/resources/fixtures/schemas/)
  — Substitut-Fixtures (`minimal.yaml`, `e-commerce.yaml`,
  `full-featured.yaml`).
