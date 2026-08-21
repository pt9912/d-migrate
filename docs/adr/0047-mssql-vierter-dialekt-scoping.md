---
status: accepted
date: 2026-08-21
decision-makers: pt9912
consulted: docs/planning/in-progress/mssql-dialect-scoping.md, .github/workflows/integration.yml
informed: .github/dependabot.yml, docs/user/quality.md
---

# MS SQL Server als vierter Dialekt — 2017+, voller Umfang als Slices, CI in jedem Lauf

> **Status: accepted (2026-08-21).** MS SQL Server wird als vierter Dialekt
> gebaut ([LF-019](../../spec/lastenheft-d-migrate.md#lf-019), per
> Eigner-Entscheidung vor Trino/gRPC/REST vorgezogen). Versions-Untergrenze
> **SQL Server 2017**; **kein Carve-Out-Schnitt** — der volle Funktionsumfang
> ist als Slices geplant; die MSSQL-Integrationsschiene läuft **in jedem
> CI-Lauf** mit.

## Kontext und Problemstellung

Ein vierter Dialekt ist ein Milestone in der Größenordnung mehrerer bisheriger
Releases: die drei bestehenden Dialekte liegen bei 8–10k Zeilen Produktivcode
je Treibermodul, dazu je ein Profiling-Modul, ein Integrationstest-Modul,
Cross-Dialekt-Matrix-, Kanonisierer- und sample-db-Beteiligung; im Hexagon
brauchen ~70 `DatabaseDialect`-Verzweigungen einen MSSQL-Zweig. Vor Baubeginn
waren drei Fragen zu entscheiden: die Versions-Untergrenze, der
Feature-Schnitt und die CI-Einbettung des mit ~1,5 GB Image / 2 GB RAM
schwersten Containers im Haus. Bestandsaufnahme, T-SQL-Inventar und
Slice-Schnitt trägt das Plan-Dokument
[`mssql-dialect-scoping.md`](../planning/in-progress/mssql-dialect-scoping.md)
(deskriptiv).

## Entscheidung

1. **Versions-Untergrenze: SQL Server 2017.** 2017 ist die älteste Version mit
   Linux-Containern erster Klasse (Testcontainers) und `STRING_AGG`; alles aus
   dem T-SQL-Inventar des Plans ist bereits ab 2012 verfügbar, ältere
   Versionen sind EOL. Getestet wird gegen
   `mcr.microsoft.com/mssql/server:2022-latest`; die Untergrenze beschreibt,
   was der Dialekt an Server-Features voraussetzen darf.
2. **Feature-Schnitt: keine Carve-Outs.** Statt eines Kern-Schnitts mit
   ausgeschlossenen Carve-Out-Tickets deckt der Slice-Plan den vollen
   Funktionsumfang ab: Kern (Reverse-Read, DDL-Generate, Datentransfer,
   Matrix-Teilnahme, Diff/Migrate) zuerst, danach Partitionierung, Volltext,
   Routinen/Trigger, gefilterte/clustered-gesteuerte Indizes und das
   Profiling-Modul als eigene Slices. Was ein Slice noch nicht kann, steht im
   Plan als späterer Slice — nicht als else-Zweig oder UNSUPPORTED-Stopgap.
3. **CI: MSSQL läuft in jedem Lauf.** Das Modul `test/integration-mssql` nimmt
   automatisch am generischen `-PintegrationTests`-Mechanismus in
   `integration.yml` teil (jeder Push/PR auf `main`, nicht-blockierend neben
   dem Hauptbuild) — kein Sonderpfad, kein Drift-Risiko. Eine Staffelung wie
   `perf-acceptance` bleibt der benannte Ausweichpfad, falls die Laufzeit der
   Integrationsschiene unzumutbar wächst.

## Konsequenzen

- Treiber ist `com.microsoft.sqlserver:mssql-jdbc` (MIT-lizenziert, verträglich
  mit diesem Repo). Major-Bumps stehen wie bei den anderen JDBC-Treibern in
  der Dependabot-Ignore-Liste; die Cross-Dialekt-Matrix ist das Gate für
  bewusste Sprünge.
- Das Container-Image verlangt die Microsoft-EULA (`ACCEPT_EULA=Y`); die
  Integrationstests akzeptieren sie programmatisch
  (`MSSQLServerContainer.acceptLicense()`), dokumentiert in
  [`quality.md`](../user/quality.md).
- Es gilt die `DdlDialectContext`-Regel: dialektspezifische Daten als sealed
  Varianten, keine nullable `mssql*`-Felder auf generischen Ports.
- Die case-insensitive Default-Collation und die `sys.*`-Katalogsichten sind
  bekannte Reverse-/Postcompare-Risiken; Round-Trip-Tests laufen deshalb ab
  der Generate-Richtung, nicht erst mit der Matrix.
