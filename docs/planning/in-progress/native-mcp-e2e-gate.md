# Natives Image: MCP-E2E-Matrizen als Gate, `connections/list` und `server.state` (Issue #84)

- **Status**: In Arbeit (2026-11-27). Fix, Gate und Fehlerprotokoll **am Native-Binary belegt**, ausgeliefert mit
  1.14.1; offen ist die Abnahme durch den Eigner.
- **Ziel**: Das ausgelieferte native Binary besteht dieselben MCP-E2E-Läufe wie das JVM-Image, und zwar bevor es
  angehängt wird.
- **Herkunft**: Eigner-Frage, ob die E2E-Matrix je mit dem nativen Image lief (nein: CI und lokale Läufe nahmen das
  JVM-Image), und Issue #84.
- **Vorbedingungen**: keine. Kein ADR: Es ändert Bau und Prüfung, keinen Vertrag.
- **Vorläufer**: [`native-server-state-defekte.md`](../next/native-server-state-defekte.md) beschrieb denselben Defekt
  schon am 2026-09-16 (an `1.7.1-native`); er wurde beim Anlegen dieses Plans übersehen.
- **Verhältnis**: [`native-e2e-regression-gate.md`](../next/native-e2e-regression-gate.md) verdrahtet die
  Subprozess-E2Es (`make native-e2e`); dieser Plan verdrahtet die Harness-Matrizen und ersetzt jenen nicht.

## Befund (gemessen 2026-11-26 am veröffentlichten nativen Image `1.14.0-native`)

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

## CI-Runde 1 (Dispatch auf `d9552e396`, 2026-11-26)

- Auf dem Binary bestanden: Scope-Matrix (`connections/list` behoben), Roundtrip, Compare-Matrix und bei `server.state`
  Migration, Listen und das Anlegen des Jobs.
- Gescheitert: Nach dem Neustart antwortet `job_list` mit `INTERNAL_AGENT_ERROR`. Jackson liest über die Wire-Klassen
  hinaus deren Feldtypen (`ManagedJob`, `JobError`, `ManagedArtifact` …); 13 Typen waren nicht registriert. Der
  Wächter-Test folgt jetzt den Feldtypen und nannte genau diese 13.
- Schema- und Artefakt-Datensätze hatte das Skript nicht gelesen (Neustart vor Job-Ende). Es wartet jetzt auf
  `SUCCEEDED` und verlangt in allen drei Listen mindestens einen Eintrag, vor und nach dem Neustart.
- Erster Versuch zuvor rot ohne Code-Ursache: `apt-get` bekam für `libtiff6` einen 404 vom Ubuntu-Archiv (Index vor
  der Datei). Die Matrix-Schritte liefen trotz fehlendem Image weiter; sie hängen jetzt am erfolgreichen Image-Bau.

## CI-Runde 2 (Dispatch auf `fc35e7afe`)

- Scope-Matrix, Roundtrip und Compare-Matrix bestanden; `server.state` scheiterte früher: `schema_reverse_start`
  antwortete mit `INTERNAL_AGENT_ERROR`.
- Ursache (Binary des Laufs lokal verpackt, derselbe Ablauf mit dem Tracing-Agenten auf der JVM): `kotlin-reflect`
  fehlten die `kotlin/*.kotlin_builtins`, Service-Dateien und Domänentypen aus den Signaturen der Wire-Companions.
- Behoben durch die unveränderte Agent-Ausgabe in `META-INF/native-image/dev.dmigrate/server-state/`. Ein Make-Ziel
  zum Neuerzeugen gibt es nicht; das Verfahren steht im README unter `cli-manual/`.
- Die Ausnahme selbst war nativ nicht sichtbar: Logback wird beim Bau initialisiert, DEBUG lässt sich im Binary nicht
  einschalten.

## CI-Runde 3 (Dispatch auf `a7e085f17`)

- `mcp-e2e-native` grün in allen vier Schritten; Build & Test, Integration Tests, Per-Module Coverage, Scope-Matrix und
  Compare-Matrix auf demselben Commit grün.
- Reproduktionsablauf aus Issue #84 mit dem Binary dieses Laufs (lokal verpackt mit
  `make native-runtime-from-binary`), `1.14.0` (JVM) als Schreiber: Start gegen die leere State-DB, Listen und
  `schema_reverse_start` auf der migrierten DB, danach Lesen der vom JVM-Server angelegten Datensätze — alle Aufrufe
  `ok`.

## Akzeptanz (DoD)

* **Erfüllt** (Runde 3): `native-image.yml` per Dispatch grün, darin alle vier Schritte von `mcp-e2e-native`.
* **Erfüllt** (Runde 3): Der Reproduktionsablauf aus Issue #84 läuft durch, einschließlich der vom JVM-Server
  angelegten Datensätze. Dieser letzte Teil ist von Hand belegt; `smoke-server-state.sh` schreibt und liest mit
  demselben Image.
* **Erfüllt**: Die Ursache eines `INTERNAL_AGENT_ERROR` steht auf ERROR im Serverlog
  (Eigner-Entscheidung 2026-11-26, Nebenbefund aus Issue #84; `ToolFailureLog`, Zugangsdaten maskiert).
  `smoke-server-state.sh` erzwingt dafür in Phase 5 einen Serverfehler: grün mit dem neuen JVM-Image, rot mit `1.14.0`.
  Nativ belegt durch den Dispatch-Lauf von `native-image.yml` auf dem Release-Commit von 1.14.1 und von Hand am
  veröffentlichten Image `1.14.1-native`.

## Nicht im Umfang

- Windows-Binary: die Matrizen brauchen Linux-Container.
- Oracle in den Matrizen (Kaltstart, wie im bestehenden JVM-Workflow).
- Ein lokaler Native-Bau als Pflicht: Er braucht rund 15 GiB und hat am 2026-11-26 die Maschine in den OOM gebracht.
