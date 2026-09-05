# AGENTS.md

d-migrate ist ein datenbank-agnostisches CLI/MCP-Werkzeug für
Schema-Reverse-Engineering, DDL-Generierung, Schema-Vergleich/-Migration,
Datenexport/-import/-transfer und Profiling über PostgreSQL, MySQL, SQLite
und MS SQL Server hinweg.

Diese Datei ist der Einstiegspunkt für einen Code-Agenten, der mit diesem
Repository arbeitet — je nachdem, **ob er d-migrate als Werkzeug benutzt**
oder **den Code dieses Repos selbst ändert**.

## Ich will d-migrate benutzen (nicht dieses Repo ändern)

| Frage | Dokument |
| ----- | -------- |
| Wie installiere/starte ich d-migrate, wie sieht ein erster Workflow aus? | [`docs/user/guide.md`](docs/user/guide.md) |
| Wie löse ich eine konkrete Aufgabe (Schema anlegen, vergleichen, migrieren, Daten transferieren, Testdaten erzeugen, MCP-Tools aufrufen)? | [`docs/user/anwenderhandbuch.md`](docs/user/anwenderhandbuch.md) — aufgabenorientiert, ~3000 Zeilen; nutze das Inhaltsverzeichnis am Anfang, nicht linear lesen |
| Wie betreibe/deploye ich d-migrate (Docker, Distributionswege, `--server-state`, Credential-Provider)? | [`docs/user/administrationshandbuch.md`](docs/user/administrationshandbuch.md) |
| Was ist der exakte CLI-Vertrag (Flags, Exit-Codes, Optionen je Kommando)? | [`spec/cli-spec.md`](spec/cli-spec.md) |
| Was ist der exakte MCP-Tool-/JSON-RPC-Vertrag (Schemas, Scopes, Fehlercodes)? | [`spec/mcp-server.md`](spec/mcp-server.md) |
| Wie sieht das neutrale Schema-YAML-Format aus (Felder, Typen)? | [`spec/schema-reference.md`](spec/schema-reference.md) + [`spec/schema.json`](spec/schema.json) (JSON-Schema für Editor-/Tooling-Validierung) |
| Migrations-Workflows (Flyway/Liquibase/Django/Knex-Export, Round-Trips)? | [`docs/user/migrations-leitfaden.md`](docs/user/migrations-leitfaden.md) |
| Ein Fehler/Exit-Code tritt auf, was jetzt? | [`docs/user/troubleshooting-leitfaden.md`](docs/user/troubleshooting-leitfaden.md) — nach Exit-Code/Symptom |
| Was ist Stand heute, was ist geplant? | [`CHANGELOG.md`](CHANGELOG.md) (Release-Historie), [`docs/planning/in-progress/roadmap.md`](docs/planning/in-progress/roadmap.md) (Milestones) |

Direkter Einstieg für ein häufig gesuchtes Beispiel — KI-gestützte
Testdaten-Generierung per MCP (`testdata_plan`/`testdata_execute`):
[`anwenderhandbuch.md`, Abschnitt „Beispiel: Testdaten über MCP erzeugen"](docs/user/anwenderhandbuch.md#beispiel-testdaten-über-mcp-erzeugen)
unter §3.15.

Vollständiger Doku-Index mit alle Handbüchern: [`docs/user/README.md`](docs/user/README.md).

## Ich will Code/Doku in diesem Repo ändern

Dieses Repo trägt bereits ein ausführliches Claude-Code-Briefing in
[`CLAUDE.md`](CLAUDE.md) (Build-/Test-Workflow über Docker, welche Gates
existieren, Konventionen für Detekt-Suppressions, ADR-Sprache, `docs/`-
Schichten). Es gilt unabhängig vom verwendeten Werkzeug — die dort
beschriebenen Regeln sind Repo-Eigenschaften, keine Claude-Code-Spezifika.
Kurzfassung der wichtigsten Punkte:

- **Bauen/Testen läuft im Container** (`make docker-check`,
  `make docker-test`, `make integration`), nicht per lokalem `./gradlew`.
- **`make docs-check`** prüft Doku/Spec/ADR/Planning-Verweise; läuft in CI,
  aber nicht im Docker-Build — separat fahren bei Änderungen an `docs/`,
  `spec/` oder `docs/adr/`.
- **`make solid-suppression-gate`** vor jedem Commit — Detekt-Größenbefunde
  (`LargeClass`, `TooManyFunctions`, `LongParameterList` u. a.) werden durch
  echte Aufteilung gelöst, nie durch `@Suppress`.
- **Doku-Schichten**: `spec/` ist das normative Zielbild (darf Ungebautes
  beschreiben), `docs/user/` beschreibt ausschließlich den Ist-Zustand,
  `docs/planning/` ist deskriptiv, nicht normativ.

Details, Begründungen und Beispiele stehen in [`CLAUDE.md`](CLAUDE.md) —
diese Datei dupliziert sie nicht, sondern verweist darauf.

## Einordnung dieser Datei

Diese `AGENTS.md` deckt aktuell **Doku-Auffindbarkeit** ab (siehe oben) und
verweist auf `CLAUDE.md` für Build-/Entwicklungskonventionen. Eine
weitergehende, maschinenlesbare Formalisierung von Agenten-Konventionen
(Codestil-Regeln, Layering-Verbote, Source-Precedence-Rang nach dem
externen Regelwerk) ist als eigenes Arbeitspaket in
[`docs/planning/next/harness-bootstrap-v1.4.0.md`](docs/planning/next/harness-bootstrap-v1.4.0.md)
(AP4) skizziert und noch nicht umgesetzt.
