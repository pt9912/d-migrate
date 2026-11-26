# Natives Image: MCP-E2E-Matrizen als Gate, `connections/list` und `server.state` (Issue #84)

- **Status**: In Arbeit (2026-11-26). Fix und Gate geschrieben; **am Native-Binary noch nicht belegt** (siehe DoD).
- **Ziel**: Das ausgelieferte native Binary besteht dieselben MCP-E2E-Läufe wie das JVM-Image, und zwar bevor es
  angehängt wird.
- **Herkunft**: Eigner-Frage, ob die E2E-Matrix je mit dem nativen Image lief (nein: CI und lokale Läufe nahmen das
  JVM-Image), und Issue #84.
- **Vorbedingungen**: keine. Kein ADR: Es ändert Bau und Prüfung, keinen Vertrag.
- **Verhältnis**: [`native-e2e-regression-gate.md`](../next/native-e2e-regression-gate.md) verdrahtet die
  Subprozess-E2Es (`make native-e2e`); dieser Plan verdrahtet die Harness-Matrizen und ersetzt jenen nicht.

## Befund (gemessen 2026-11-26 an `ghcr.io/pt9912/d-migrate:1.14.0-native`)

| Lauf | Ergebnis |
| ---- | -------- |
| Compare-Matrix (PostgreSQL, MySQL, SQL Server, SQLite; Erwartungen aus dem Tag `v1.14.0`) | bestanden |
| Dialekt-Roundtrip | bestanden |
| Scope-Matrix | **gescheitert**: `connections/list` (mit und ohne `checkLive`) „Unable to create instance of class …ConnectionsListParams" |
| `server.state` mit PostgreSQL (neues Skript) | **gescheitert**: Start gegen eine leere State-DB bricht ab (Issue #84) |

Gegenprobe: Das `server.state`-Skript besteht gegen das JVM-Image `1.14.0` vollständig.

## Umgesetzt

- Reflection-Registrierung (`cli-manual/reflect-config.json`): drei `connections`-DTOs und die Wire-Klassen der
  Persistenz samt Companion.
- Flyway: `IndexedMigrationResources` liefert den Standardort aus `db/migration/migrations.index` (Gradle-Task
  `generateMigrationIndex`), Java-Migrationen werden nicht gesucht, `resource-config.json` nimmt die Dateien ins Image.
  Der Weg gilt auf der JVM wie nativ; andere Orte bleiben beim Flyway-Scanner.
- `NativeReflectionRegistrationTest`: Jede Datenklasse des MCP-Protokolls und jede Wire-Klasse muss registriert sein.
- `examples/mcp-e2e/scripts/smoke-server-state.sh` (`make mcp-e2e-server-state`).
- `native-image.yml`, Job `mcp-e2e-native`: Scope-Matrix, Roundtrip, Compare-Matrix und `server.state` gegen das
  `linux-x64`-Binary des Laufs; `attach-release` hängt daran.

## Belegt (JVM)

- Wächter-Test: ohne die neuen Einträge scheitern beide Prüfungen.
- Integrationstests der Persistenz gegen PostgreSQL grün; liefert der Provider keine Migration, scheitern sie.

## Akzeptanz (DoD)

* **Offen**: `native-image.yml` per Dispatch grün, darin alle vier Schritte von `mcp-e2e-native`. Erst das belegt, dass
  die Registrierungen und die Flyway-Ressourcen im Binary wirken; es kann weitere Lücken zeigen.
* **Offen**: Das Reproduktionsskript aus Issue #84 gilt als erfüllt, wenn der `server.state`-Schritt grün ist; das
  Lesen von Datensätzen, die ein JVM-Server angelegt hat, deckt das Skript nicht ab (es schreibt und liest mit
  demselben Image).
* **Offen**: Die `INTERNAL_AGENT_ERROR`-Antworten der Store-Tools erscheinen in keinem Serverlog (Nebenbefund aus
  Issue #84). Das betrifft auch die JVM und ist hier nicht behoben.

## Nicht im Umfang

- Windows-Binary: die Matrizen brauchen Linux-Container.
- Oracle in den Matrizen (Kaltstart, wie im bestehenden JVM-Workflow).
- Ein lokaler Native-Bau als Pflicht: Er braucht rund 15 GiB und hat am 2026-11-26 die Maschine in den OOM gebracht.
