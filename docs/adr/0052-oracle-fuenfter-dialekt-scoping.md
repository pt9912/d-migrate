---
status: accepted
date: 2026-09-05
decision-makers: pt9912
consulted: docs/planning/in-progress/oracle-dialect-scoping.md, docs/adr/0047-mssql-vierter-dialekt-scoping.md
informed: .github/dependabot.yml, docs/user/quality.md
---

# Oracle als fünfter Dialekt — 23ai Free als Testziel, voller Umfang als Slices, PL/SQL-Packages als Ausbau-Slice

> **Status: accepted (2026-09-05).** Oracle wird als fünfter Dialekt gebaut
> ([LF-019](../../spec/lastenheft-d-migrate.md#lf-019), analog zur
> MSSQL-Vorziehung per [ADR 0047](0047-mssql-vierter-dialekt-scoping.md)).
> Testziel **Oracle 23ai Free** (`gvenzl/oracle-free`); **kein
> Carve-Out-Schnitt** — voller Funktionsumfang als Slices, dem MSSQL-Muster
> folgend. **PL/SQL Packages** (Prozedur-Gruppierung ohne Äquivalent in den
> vier bestehenden Dialekten) sind ein vorgemerkter **Ausbau-Slice**, keine
> dauerhafte Lücke.

## Kontext und Problemstellung

Ein fünfter Dialekt ist — wie MSSQL zuvor — ein Milestone in der
Größenordnung mehrerer bisheriger Releases. Bestandsaufnahme, Oracle-Inventar
und Slice-Schnitt trägt das Plan-Dokument
[`oracle-dialect-scoping.md`](../planning/in-progress/oracle-dialect-scoping.md)
(deskriptiv). Vier Fragen waren vor Baubeginn zu entscheiden: Testziel-Version,
Feature-Schnitt, Umgang mit dem Oracle-JDBC-Lizenzmodell und die Einordnung von
PL/SQL Packages.

## Entscheidung

1. **Testziel: Oracle 23ai Free, Testcontainer `gvenzl/oracle-free`
   (`slim`/`faststart`-Varianten, ~700 MB–1,4 GB komprimiert — vergleichbar
   mit oder leichter als der MSSQL-Container).** 23ai ist die aktuelle
   kostenlose Edition mit vollem Feature-Umfang (IDENTITY-Spalten, JSON,
   `FETCH FIRST`); ältere LTS-Linien (19c) böten keinen Vorteil, den das
   Inventar bräuchte, und `gvenzl/oracle-xe` wird weniger aktiv gepflegt als
   `gvenzl/oracle-free`.
2. **Feature-Schnitt: keine Carve-Outs**, analog [ADR 0047](0047-mssql-vierter-dialekt-scoping.md)
   Punkt 2. Kern (Reverse-Read, DDL-Generate, Datentransfer, Matrix-Teilnahme,
   Diff/Migrate) zuerst, danach Partitionierung, Volltext (Oracle Text),
   Routinen/Trigger (PL/SQL, standalone), Index-Eigenheiten
   (Function-based/Bitmap) und Materialized Views (bei Oracle nativ vorhanden,
   anders als bei MSSQL — Anschluss an das bereits bestehende
   Materialized-View-Modell aus der 0.9.7-D.3b-Vollscheibe) als eigene Slices.
3. **JDBC-Treiber `com.oracle.database.jdbc:ojdbc11` unter den Oracle Free Use
   Terms and Conditions (FUTC) — kein Blocker, aber Compliance-Pflicht.**
   Anders als `mssql-jdbc` (MIT) ist FUTC keine OSI-Lizenz. Verifiziert: FUTC
   erlaubt Weiterverbreitung des unmodifizierten Treibers, verlangt aber (a)
   den Lizenztext mitzuliefern, (b) Oracle-Eigentumsvermerke nicht zu
   entfernen, (c) kein Reverse Engineering. Das Docker-Image und die
   Release-Assets (Fat-JAR/ZIP) müssen die FUTC-Lizenzkopie mitführen (Slice 0).
4. **PL/SQL Packages: vorgemerkter Ausbau-Slice, keine dauerhafte Lücke** —
   anders als MSSQLs Materialized-Views-Entscheidung in ADR 0047. Package-
   Gruppierung von Prozeduren/Funktionen hat in keinem der vier bestehenden
   Dialekte ein Äquivalent; eine Modellierung verlangt eine Erweiterung des
   Neutralmodells (Routine-Gruppierung) und ist deshalb ein eigener,
   terminlich offener Slice nach dem MSSQL-Muster „was ein Slice noch nicht
   kann, steht als späterer Slice, nicht als else-Zweig". Bis dahin erfasst
   der Reverse-Read einzelne Package-Prozeduren/Funktionen als entpackte,
   eigenständige Routinen (Package-Zugehörigkeit geht vorübergehend verloren,
   analog zum MSSQL-Umgang mit noch nicht gebauten Flächen).

## Konsequenzen

- Treiber ist `com.oracle.database.jdbc:ojdbc11` (FUTC, siehe oben); Major-Bumps
  stehen wie bei den anderen JDBC-Treibern in der Dependabot-Ignore-Liste, die
  Cross-Dialekt-Matrix ist das Gate für bewusste Sprünge.
- Es gilt weiterhin die `DdlDialectContext`-Regel: dialektspezifische Daten als
  sealed Varianten, keine nullable `oracle*`-Felder auf generischen Ports.
- **UPPERCASE-Bezeichner ohne Quoting sind Oracles Default** — das Gegenteil
  von PostgreSQL/MySQL/SQLite (lowercase-Default) und der Umkehrfall zu
  MSSQLs case-insensitiver Collation. Reverse-/Postcompare-Kanonisierung
  brauchen dafür eine eigene, dokumentierte Fallunterscheidung — voraussichtlich
  ähnlich aufwendig wie MSSQLs Collation-Behandlung, nur mit umgekehrtem
  Vorzeichen.
- `spec/oracle-code-gen.md` (bereits im Repo) ist **kein** Vorlauf für diesen
  Dialekt-Ausbau — das ist ein unabhängiges Zielbild für ein separates
  Introspection/Codegen-Tool (jOOQ-Lizenz-Alternative) und bleibt davon
  unberührt.
