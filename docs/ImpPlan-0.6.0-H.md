# Implementierungsplan: Phase H - Testmatrix, Docs und Beispielpfade

> **Milestone**: 0.6.0 - Reverse-Engineering und Direkttransfer
> **Phase**: H (Testmatrix, Docs und Beispielpfade)
> **Status**: Done (2026-04-14)
> **Referenz**: `docs/implementation-plan-0.6.0.md` Abschnitt 5 Phase H,
> Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10, Abschnitt 11;
> `docs/ImpPlan-0.6.0-A.md`; `docs/ImpPlan-0.6.0-D.md`;
> `docs/ImpPlan-0.6.0-E.md`; `docs/ImpPlan-0.6.0-F.md`;
> `docs/ImpPlan-0.6.0-G.md`; `docs/cli-spec.md`;
> `docs/neutral-model-spec.md`; `docs/architecture.md`;
> `docs/design.md`; `docs/releasing.md`

---

## 1. Ziel

Phase H schliesst Milestone 0.6.0 nicht ueber neue Produktlogik ab, sondern
ueber belastbare Verifikation und konsistente Dokumentation.

Ziel der Phase ist:

- Reverse, Compare und Transfer sind nicht nur spezifiziert oder teilweise
  implementiert, sondern ueber die relevanten Schichten abgesichert
- die 0.6.0-Vertraege sind in Code, CLI-Tests, Dialekt-Tests und
  Dokumentation deckungsgleich
- fuer Reverse, Compare und Transfer existiert mindestens je ein
  reproduzierbarer Smoke-Pfad; DB-gestuetzte Smokes sind dabei an ein
  dokumentiertes lokales Docker-/Alias-Setup auf Basis eingecheckter
  Fixtures gebunden
- Beispielpfade bauen auf vorhandenem Repo-Material auf und verweisen nicht
  auf eine nicht existente `examples/`-Struktur

Nach Phase H soll fuer 0.6.0 klar und testbar gelten:

- Reverse ist pro Built-in-Dialekt durch echte driver-nahe Tests abgesichert
- Compare ist fuer `file/file`, `file/db` und `db/db` abgedeckt
- Transfer ist fuer Preflight, Reihenfolge, Flag-Semantik und Fehlerpfade
  abgesichert
- die kanonischen 0.6.0-Dokumente beschreiben denselben Vertrag:
  - `docs/cli-spec.md`
  - `docs/neutral-model-spec.md`
  - `docs/architecture.md`
  - `docs/design.md`
  - `docs/releasing.md`
- der kanonische Beispiel- und Fixture-Pfad ist im Repo konsistent benannt und
  fuer manuelle Smokes wiederverwendbar

---

## 2. Ausgangslage

Aktueller Stand im Repo:

- die Testbasis fuer 0.1.0 bis 0.5.x ist bereits breit:
  - `hexagon/core/src/test`
  - `hexagon/application/src/test`
  - `adapters/driven/driver-common/src/test`
  - `adapters/driven/driver-postgresql/src/test`
  - `adapters/driven/driver-mysql/src/test`
  - `adapters/driven/driver-sqlite/src/test`
  - `adapters/driven/formats/src/test`
  - `adapters/driven/streaming/src/test`
  - `adapters/driving/cli/src/test`
- es gibt bereits belastbare Fixture-Grundlagen unter:
  - `adapters/driven/formats/src/test/resources/fixtures/schemas/`
  - `adapters/driven/formats/src/test/resources/fixtures/ddl/`
  - `adapters/driven/formats/src/test/resources/fixtures/invalid/`
- `docs/releasing.md` nutzt diese Fixture-Schemas bereits fuer
  `schema generate`-Smokes
- `scripts/test-integration-docker.sh` deckt reale Dialekt-Integration ueber
  Gradle/Testcontainers ab, ist aber kein fertiger manueller Smoke-Harness
  fuer rohe `postgresql://...`-/`mysql://...`-Beispiele
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/ReverseContractTest.kt`
  deckt heute nur den grundlegenden Reverse-Vertrag ab:
  - `SchemaReadOptions`
  - `SchemaReadResult`
  - `SchemaReadNote`
  - `ResolvedSchemaOperand`
- `SchemaCompareRunnerTest` und `CliSchemaCompareTest` existieren, sind aber
  aktuell file-based
- fuer `schema reverse` gibt es heute noch keine produktionsnahe Testmatrix
  ueber Runner, CLI und Dialekt-Reads
- fuer `data transfer` existiert heute noch keine Testbasis, weil der Pfad
  selbst noch nicht produktiv vorhanden ist
- es gibt im Repo derzeit kein top-level `examples/`-Verzeichnis

Aktueller Doku-Zustand:

- `docs/cli-spec.md` beschreibt Reverse, Compare-DB-Operanden und Transfer
  bereits als 0.6.0-Soll
- `docs/neutral-model-spec.md` ist fuer den Live-DB-first-Reverse-Pfad bereits
  in Richtung 0.6.0 bereinigt
- `docs/architecture.md` und `docs/design.md` beschreiben Fixtures noch mit
  einem generischen Pfad `src/test/resources/fixtures/`, waehrend der reale
  Repo-Pfad heute unter `adapters/driven/formats/src/test/resources/fixtures/`
  liegt
- `docs/releasing.md` hat bisher nur Schema-Generate-Smokes gegen die
  Fixture-Schemas, aber noch keinen 0.6.0-Satz fuer Reverse, Compare und
  Transfer

Konsequenz fuer Phase H:

- Die Phase ist kein Appendix nach der eigentlichen Arbeit, sondern der
  Schlussschritt, der 0.6.0 belastbar macht.
- Der wichtigste Restaufwand liegt in:
  - fehlender 0.6.0-Testmatrix fuer Reverse und Transfer
  - Ausweitung des Compare-Testsatzes von file-based auf gemischte Operanden
  - Doku-Abgleich ueber Specs, Architektur, Living Design und Release-Smokes
  - Fixierung eines real existierenden Beispielpfads inklusive lokal
    reproduzierbarem DB-Smoke-Setup statt diffuser Platzhalter

---

## 3. Scope fuer Phase H

### 3.1 In Scope

- systematische Testmatrix fuer Reverse, Compare und Transfer
- Unit-Tests fuer neue Port-, Runner-, Helper- und Diff-Vertraege
- dialect-nahe Reverse-Tests fuer PostgreSQL, MySQL und SQLite
- Compare-Tests fuer:
  - `file/file`
  - `file/db`
  - `db/db`
- Transfer-Tests fuer:
  - target-autoritatives Preflight
  - Tabellenreihenfolge
  - FK-Zyklen
  - Flag-Semantik
  - Scrubbing
  - Progress-/Summary-Vertrag
- Integrationstests gegen reale Dialekte, soweit fuer Reverse-Read,
  Compare-DB-Operand und Transfer notwendig
- Nachfuehrung der kanonischen 0.6.0-Dokumente
- Festlegung eines belastbaren Beispielpfads auf Basis vorhandener
  Repo-Fixtures und Release-Smokes

### 3.2 Bewusst nicht Teil von Phase H

- neue Produktfeatures jenseits der in Phase D bis G festgezogenen 0.6.0-
  Vertraege
- neue Dialekte
- neue top-level Produktdokumente ohne klaren 0.6.0-Nutzen
- Aufbau eines grossen Demo-Projekts oder einer neuen `examples/`-Struktur
  nur fuer 0.6.0
- Performance-Ziele jenseits der bereits vorhandenen gezielten Perf-Tests
- Profiling- oder 0.7.5-Arbeit

---

## 4. Leitentscheidungen fuer Phase H

### 4.1 Phase H verifiziert die Phasen D bis G, erfindet sie nicht neu

Phase H ist keine inhaltliche Neudefinition von Reverse, Compare oder
Transfer.

Verbindliche Folge:

- Phase H testet und dokumentiert die bereits festgezogenen Vertraege aus:
  - Reverse
  - Compare-DB-Operanden
  - Transfer
- wenn waehrend Phase H neue Widersprueche auftauchen, werden diese gegen die
  Teilplaene D bis G gespiegelt und nicht ad hoc neu erfunden

### 4.2 Die Testmatrix folgt den realen Schichten des Repos

Die Phase muss an die vorhandene Testlandschaft anschliessen, nicht an eine
abstrakte Wunschstruktur.

Verbindliche Folge:

- Core-Logik bleibt in `hexagon/core/src/test`
- pure, CLI-unabhaengige Application-Helfer bleiben in
  `hexagon/application/src/test`
- Resolver-, Runner- und CLI-Vertragslogik folgt dem bestehenden Zuschnitt in
  `adapters/driving/cli/src/test`
- driver-nahe Dialektpfade bleiben in den jeweiligen Driver-Modulen
- format- und fixture-nahe Tests bleiben in `adapters/driven/formats/src/test`
- Streaming-Verhalten bleibt in `adapters/driven/streaming/src/test`
- CLI- und E2E-nahe Verifikation bleibt in `adapters/driving/cli/src/test`

Nicht akzeptabel ist:

- dieselbe Fachregel in mehreren Modulen copy-paste erneut zu testen, wenn
  eine tiefere Schicht sie bereits stabil absichert
- Reverse-, Compare- oder Transfer-Vertraege nur ueber manuelle Smokes
  abzudecken

### 4.3 Reverse braucht echte Dialektpfade, nicht nur Vertrags- oder Stub-Tests

Reverse ist in 0.6.0 besonders stark von JDBC-Metadaten und Dialektbesonderen
abhaengig.

Verbindliche Folge:

- pro Built-in-Dialekt braucht es driver-nahe Reverse-Tests
- diese Tests muessen mindestens die 0.6.0-relevanten Objekttypen und
  Note-/Skipped-Objekt-Pfade abdecken
- reine Contract-Tests wie `ReverseContractTest` bleiben wichtig, ersetzen
  aber keine Dialektabsicherung

### 4.4 Compare muss operandseitig vollstaendig verifiziert werden

`schema compare` ist ab 0.6.0 nicht mehr file-only.

Verbindliche Folge:

- die Testmatrix muss explizit alle drei Operand-Kombinationen enthalten:
  - `file/file`
  - `file/db`
  - `db/db`
- die Symmetrie von Aufloesung, Scrubbing, Exit-Codes und Ausgabe darf nicht
  nur indirekt getestet werden

### 4.5 Transfer wird primär ueber Preflight-, Reihenfolge- und Fehlerpfade abgesichert

Der groesste Transfer-Risiko liegt nicht im simplen Happy Path, sondern in
Zielinkompatibilitaeten, Reihenfolge und Flag-Semantik.

Verbindliche Folge:

- Transfer-Tests muessen mindestens priorisieren:
  - Preflight statt spaetem Laufzeitfehler
  - target-autoritives Ordering
  - FK-Zykluspfad
  - `on-conflict`-/`trigger-mode`-/`since`-Semantik
  - Scrubbing
- reine "Rows kommen an" Happy-Path-Tests reichen nicht

### 4.6 Beispielpfade bauen auf den vorhandenen Fixture-Schemas auf

Fuer 0.6.0 existiert kein Repo-`examples/`-Verzeichnis. Die Phase soll deshalb
auf dem real vorhandenen Material aufsetzen.

Verbindliche Folge:

- kanonische Beispielbasis fuer 0.6.0 bleibt:
  - `adapters/driven/formats/src/test/resources/fixtures/schemas/`
- bestehende Release-Smokes in `docs/releasing.md` werden erweitert, statt
  fuer 0.6.0 einen neuen grossen Demo-Pfad zu erfinden
- generische Doku-Pfade wie `src/test/resources/fixtures/` muessen auf den
  realen Repo-Pfad oder auf eine bewusst abstrahierte Form mit klarem Hinweis
  gezogen werden

Nicht akzeptabel ist:

- Doku, die fuer Smokes oder Beispiele auf nicht existente `examples/`- oder
  generische Fixture-Pfade zeigt

### 4.7 Doku-Synchronisation umfasst Spezifikation, Architektur, Living Design und Release-Smokes

Phase H ist nicht mit einem Update nur von `cli-spec.md` abgeschlossen.

Verbindliche Folge:

- die kanonische Spezifikation bleibt in `docs/cli-spec.md`
- das Datenmodell bleibt in `docs/neutral-model-spec.md`
- Architekturrand und Modul-/Fixture-Einordnung bleiben in
  `docs/architecture.md`
- das Living Design in `docs/design.md` bleibt fuer das Zielbild konsistent
- reproduzierbare manuelle Smokes fuer Releases bleiben in `docs/releasing.md`

---

## 5. Arbeitspakete

### H.1 Testmatrix und Gap-Map aus den Phasen D bis G ableiten

Vor der eigentlichen Testarbeit ist eine explizite Abdeckungstabelle
herzustellen.

Mindestens noetig:

- Zuordnung der Reverse-Vertraege aus Phase D/E zu:
  - Contract-Tests
  - Driver-Tests
  - CLI-Tests
- Zuordnung der Compare-Vertraege aus Phase F zu:
  - Core-/Projection-Tests
  - Runner-Tests
  - CLI-Tests
- Zuordnung der Transfer-Vertraege aus Phase G zu:
  - Preflight-/Runner-Tests
  - Streaming-/Executor-Tests
  - CLI-Tests

Wichtig:

- H1 ist kein zusaetzliches Spreadsheet-Artefakt fuer den User, sondern die
  strukturierende Vorarbeit fuer die eigentlichen Test- und Doku-Aenderungen

### H.2 Reverse-Testmatrix pro Dialekt schliessen

Phase H muss den 0.6.0-Reverse-Pfad pro Built-in-Dialekt absichern.

Mindestens noetig:

- Runner-Tests fuer:
  - Exit-Codes
  - Output-/Report-Trennung
  - Scrubbing
  - Include-Flags
- Driver-Tests fuer PostgreSQL, MySQL und SQLite mit Fokus auf:
  - Tabellen
  - Spalten
  - Keys / Constraints / Indizes
  - Sequenzen / relevante Typen
  - Views
  - Procedures / Functions / Triggers gemaess Include-Flags
  - Notes / `skippedObjects`
- Round-Trip-nahe Checks:
  - reverse-erzeugte YAML-/JSON-Schemas bleiben wieder einlesbar

### H.3 Compare-Testmatrix von file-only auf Operand-Matrix anheben

Compare muss nach Phase H denselben 0.6.0-Vertrag tragen wie die Doku.

Mindestens noetig:

- Erweiterung von Runner-/CLI-Tests auf:
  - `file/file`
  - `file/db`
  - `db/db`
- Absicherung fuer:
  - Operand-Aufloesung
  - Reverse-Marker-Normalisierung
  - operandneutrale Fehlerausgaben
  - Scrubbing
  - Exit `0` / `1` / `3` / `4` / `7`
- Verifikation, dass neue 0.6.0-Objekttypen in der Ausgabe nicht verloren
  gehen

### H.4 Transfer-Testmatrix fuer Preflight, Reihenfolge und Flags aufbauen

Transfer ist in Phase H der staerkste neue Testblock.

Mindestens noetig:

- Runner-Tests fuer:
  - Source-/Target-Aufloesung ohne Defaults
  - Flag-Validierung
  - Scrubbing
  - Exit-Code-Mapping
- Preflight-Tests fuer:
  - Zielinkompatibilitaet
  - invalides neutrales Schema
  - `on-conflict update` ohne PK
  - `trigger-mode`
  - FK-Zyklen
  - target-autoritives Ordering
- Streaming-/Executor-Tests fuer:
  - Reader->Writer-Kopplung
  - Chunk-Verhalten
  - Summary-/Progress-Vertrag

### H.5 Integration und CLI-E2E fuer reale Dialekte absichern

Neben Unit- und Runner-Tests braucht 0.6.0 reale Dialektpfade.

Mindestens noetig:

- gezielte Integrationstests fuer Reverse-Reads pro Dialekt
- CLI-E2E-/docker-nahe Pfade fuer Compare-DB-Operanden und Transfer, soweit
  sinnvoll
- Einordnung in das bestehende Skript- und Docker-Setup statt eines
  separaten Teststapels
- CLI-Smokes laufen gegen ein finales Runtime-Image mit
  `ENTRYPOINT ["d-migrate"]`; `--target build` bleibt Test-/Asset-Pipeline
  vorbehalten

### H.6 Kanonische 0.6.0-Dokumente synchronisieren

Die Doku muss nach Phase H dieselbe Sprache sprechen wie die Tests.

Mindestens noetig:

- `docs/cli-spec.md` auf den finalen Reverse-/Compare-/Transfer-Vertrag
  ziehen
- `docs/neutral-model-spec.md` gegen den realen Reverse- und Round-Trip-
  Vertrag pruefen
- `docs/architecture.md` auf reale Fixture-/Testpfade und 0.6.0-Status
  abgleichen
- `docs/design.md` auf denselben Fixture-/Beispielpfad und denselben
  0.6.0-Stand ziehen
- `docs/releasing.md` um Reverse-/Compare-/Transfer-Smokes erweitern

### H.7 Beispielpfade und manuelle Smokes auf vorhandene Repo-Artefakte stutzen

Phase H soll einen kleinen, realen und wartbaren Beispielpfad etablieren.

Mindestens noetig:

- fixture-basierte file/file-Smokes fuer Compare
- DB-gestuetzte Reverse-/Compare-/Transfer-Smokes mit dokumentiertem lokalem
  Docker-Netz, temporaerer `--config`-Datei und Provisionierung aus
  `adapters/driven/formats/src/test/resources/fixtures/ddl/`
- explizite Compare-Smokes fuer:
  - `file/file`
  - `file/db`
  - `db/db`
- explizite Benennung des kanonischen Fixture-Pfads in der Doku
- keine Verweise auf ein nicht existentes `examples/`-Verzeichnis
- keine Platzhalter wie `postgresql://user:pw@host/...`, wenn derselbe Pfad
  als reproduzierbar bezeichnet wird

---

## 6. Technische Zielstruktur

Phase H braucht keine neue Produktionsarchitektur, aber eine saubere
Abdeckungsstruktur.

Bevorzugte Matrix:

- Reverse:
  - `hexagon/application/src/test`
    - reine Contract-Tests ohne Resolver- oder CLI-Adapter-Reichweite
  - `adapters/driven/driver-*/src/test`
    - echte Dialektreads, Notes, `skippedObjects`, Include-Flags
  - `adapters/driving/cli/src/test`
    - Runner-, Resolver-, Exit-, Scrubbing-, Report-, CLI-Help- und
      Output-Pfade
- Compare:
  - `hexagon/core/src/test`
    - Diff- und Marker-nahe Kernlogik
  - `adapters/driving/cli/src/test`
    - Operandauflosung, `file/file`, `file/db`, `db/db`, Exit-Codes,
      Scrubbing, Ausgabe
- Transfer:
  - `adapters/driven/streaming/src/test`
    - Transfer-Orchestrierung, Rows-/Chunk-Vertrag
  - `adapters/driving/cli/src/test`
    - Resolver, Preflight, Ordering, Exit-Codes, `data transfer`-CLI,
      Flag-Semantik, Summary/Progress
- Docs und Beispielpfade:
  - `docs/cli-spec.md`
  - `docs/neutral-model-spec.md`
  - `docs/architecture.md`
  - `docs/design.md`
  - `docs/releasing.md`
  - `adapters/driven/formats/src/test/resources/fixtures/...`

Kanonischer Beispielpfad fuer 0.6.0:

- Schema-Fixtures bleiben unter
  `adapters/driven/formats/src/test/resources/fixtures/schemas/`
- DDL-Fixtures fuer lokale DB-Smokes bleiben unter
  `adapters/driven/formats/src/test/resources/fixtures/ddl/`
- manuelle Release-/Smoke-Kommandos mounten genau diesen Pfad in Container
- DB-gestuetzte Release-/Smoke-Kommandos nutzen ein dokumentiertes lokales
  Docker-Netz und eine temporaere Config-Datei mit Aliasen statt freier
  `host`-Platzhalter
- temporaere Smoke-Artefakte werden ausserhalb des Repos oder unter einem
  expliziten Work-Verzeichnis erzeugt, aber nicht versioniert

---

## 7. Betroffene Artefakte

Direkt betroffen oder neu einzufuehren:

- neue oder erweiterte Tests unter:
  - `hexagon/core/src/test/kotlin/dev/dmigrate/core/`
  - `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/`
  - `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/`
  - `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/`
  - `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/`
  - `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/`
- ggf. reine, adapterfreie Contract-Tests unter:
  - `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/`
- bestehende Fixture-Basis unter:
  - `adapters/driven/formats/src/test/resources/fixtures/schemas/`
  - `adapters/driven/formats/src/test/resources/fixtures/ddl/`
  - `adapters/driven/formats/src/test/resources/fixtures/invalid/`
- Doku-Dateien:
  - `docs/cli-spec.md`
  - `docs/neutral-model-spec.md`
  - `docs/architecture.md`
  - `docs/design.md`
  - `docs/releasing.md`

Indirekt vorausgesetzt:

- Reverse-Vertraege aus Phase D/E
- Compare-Vertraege aus Phase F
- Transfer-Vertraege aus Phase G
- bestehende Docker-/Integration-Skripte

---

## 8. Akzeptanzkriterien

- [ ] Fuer Reverse, Compare und Transfer existiert eine explizite und
      nachvollziehbare Testmatrix ueber Core/Application/Driver/Streaming/CLI.
- [ ] Reverse ist pro Built-in-Dialekt durch echte driver-nahe Tests
      abgesichert.
- [ ] Reverse-Runner- und CLI-Tests decken Exit-Codes, Report-/Output-
      Trennung, Include-Flags und Scrubbing ab.
- [ ] Compare ist fuer `file/file`, `file/db` und `db/db` abgesichert.
- [ ] Compare-Tests decken Operandauflosung, Exit-Codes, Scrubbing und
      Ausgabeprojektion der 0.6.0-Objekttypen ab.
- [ ] Transfer-Tests decken Preflight, Reihenfolge, FK-Zyklen, Flag-Semantik,
      Scrubbing und Summary/Progress ab.
- [ ] Transfer-Happy-Path allein gilt nicht als ausreichende Absicherung.
- [ ] Gezielte Integrationstests gegen reale Dialekte bleiben fuer 0.6.0 Teil
      der Verifikation.
- [ ] `docs/cli-spec.md`, `docs/neutral-model-spec.md`,
      `docs/architecture.md`, `docs/design.md` und `docs/releasing.md`
      beschreiben denselben 0.6.0-Vertrag.
- [ ] Doku-Verweise auf Fixtures oder Beispielmaterial zeigen auf reale
      Repo-Pfade und nicht auf ein nicht existentes `examples/`-Verzeichnis.
- [ ] Der kanonische Fixture-Pfad fuer 0.6.0-Beispielmaterial ist in der Doku
      konsistent festgezogen.
- [ ] `docs/releasing.md` enthaelt neben `schema generate` auch belastbare
      Reverse-/Compare-/Transfer-Smoke-Pfade.
- [ ] Mindestens ein reproduzierbarer Smoke-Pfad fuer Reverse, Compare und
      Transfer ist dokumentiert.
- [ ] DB-gestuetzte Smoke-Pfade sind an ein dokumentiertes lokales
      Docker-/Alias-Setup auf Basis der eingecheckten DDL-Fixtures gebunden
      und nicht an rohe `user:pw@host`-Platzhalter.
- [ ] `docs/releasing.md` nutzt fuer CLI-Smokes ein finales Runtime-Image;
      `--target build` bleibt auf Build-/Test-/Asset-Schritte beschraenkt.
- [ ] Die Release-/Smoke-Matrix fuer Compare enthaelt explizit `file/file`,
      `file/db` und `db/db`.
- [ ] Resolver- und runner-nahe Vertrage rund um `NamedConnectionResolver`
      werden primaer im CLI-Adapter getestet oder als klar adapterfreie
      Helfer extrahiert, aber nicht doppelt ueber Module verteilt.
- [ ] 0.6.0 ist erst dann dokumentarisch abgeschlossen, wenn Tests und Doku
      gemeinsam denselben Stand zeigen.

---

## 9. Verifikation

Mindestumfang fuer die Umsetzung:

1. Gezielter Modul-Testlauf:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :hexagon:application:test :adapters:driven:driver-common:test :adapters:driven:driver-postgresql:test :adapters:driven:driver-mysql:test :adapters:driven:driver-sqlite:test :adapters:driven:streaming:test :adapters:driven:formats:test :adapters:driving:cli:test" \
  -t d-migrate:phase-h-build .
```

2. Reale Dialekt-Integration ueber das bestehende Skript:

```bash
./scripts/test-integration-docker.sh
```

3. Finale Runtime-Stage fuer CLI-Smokes bauen:

```bash
DOCKER_BUILDKIT=1 docker build \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist" \
  -t d-migrate:phase-h-runtime .
```

4. Manuelle fixture-basierte file/file-Smokes:

```bash
docker run --rm \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/work" \
  d-migrate:phase-h-runtime \
  schema compare --source file:/work/minimal.yaml --target file:/work/e-commerce.yaml
```

5. Reproduzierbares lokales DB-Smoke-Setup fuer Reverse, Compare und Transfer:

```bash
SMOKE_DIR="$(mktemp -d)"
mkdir -p "${SMOKE_DIR}/out"

cat > "${SMOKE_DIR}/d-migrate.yaml" <<'YAML'
database:
  connections:
    smoke_pg: "postgresql://dmigrate:dmigrate@d-migrate-smoke-pg:5432/dmigrate"
    smoke_mysql: "mysql://dmigrate:dmigrate@d-migrate-smoke-mysql:3306/dmigrate"
YAML

docker network inspect d-migrate-smoke >/dev/null 2>&1 || \
  docker network create d-migrate-smoke

docker run -d --rm --name d-migrate-smoke-pg --network d-migrate-smoke \
  -e POSTGRES_USER=dmigrate \
  -e POSTGRES_PASSWORD=dmigrate \
  -e POSTGRES_DB=dmigrate \
  postgres:16

docker run -d --rm --name d-migrate-smoke-mysql --network d-migrate-smoke \
  -e MYSQL_DATABASE=dmigrate \
  -e MYSQL_USER=dmigrate \
  -e MYSQL_PASSWORD=dmigrate \
  -e MYSQL_ROOT_PASSWORD=dmigrate \
  mysql:8

docker run --rm --network d-migrate-smoke \
  -e PGPASSWORD=dmigrate \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/ddl:/fixtures:ro" \
  postgres:16 sh -lc '
    until pg_isready -h d-migrate-smoke-pg -U dmigrate >/dev/null 2>&1; do sleep 1; done
    psql -h d-migrate-smoke-pg -U dmigrate -d dmigrate -f /fixtures/minimal.postgresql.sql
    psql -h d-migrate-smoke-pg -U dmigrate -d dmigrate -c "INSERT INTO users(name) VALUES (\$\$smoke user\$\$);"
  '

docker run --rm --network d-migrate-smoke \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/ddl:/fixtures:ro" \
  mysql:8 sh -lc '
    until mysqladmin ping -h d-migrate-smoke-mysql -u dmigrate -pdmigrate --silent; do sleep 1; done
    mysql -h d-migrate-smoke-mysql -u dmigrate -pdmigrate dmigrate < /fixtures/minimal.mysql.sql
  '
```

6. Manuelle Reverse-/Compare-/Transfer-Smokes fuer 0.6.0:

```bash
docker run --rm --network d-migrate-smoke \
  -v "${SMOKE_DIR}:/smoke" \
  d-migrate:phase-h-runtime \
  --config /smoke/d-migrate.yaml \
  schema reverse --source smoke_pg --output /smoke/out/reverse.yaml --report /smoke/out/reverse.report.yaml

docker run --rm --network d-migrate-smoke \
  -v "${SMOKE_DIR}:/smoke" \
  -v "$(pwd):/repo:ro" \
  d-migrate:phase-h-runtime \
  --config /smoke/d-migrate.yaml \
  schema compare --source file:/repo/adapters/driven/formats/src/test/resources/fixtures/schemas/minimal.yaml --target db:smoke_pg

docker run --rm --network d-migrate-smoke \
  -v "${SMOKE_DIR}:/smoke" \
  d-migrate:phase-h-runtime \
  --config /smoke/d-migrate.yaml \
  schema compare --source db:smoke_pg --target db:smoke_mysql

docker run --rm --network d-migrate-smoke \
  -v "${SMOKE_DIR}:/smoke" \
  d-migrate:phase-h-runtime \
  --config /smoke/d-migrate.yaml \
  data transfer --source smoke_pg --target smoke_mysql --tables users
```

Dabei explizit pruefen:

- Reverse ist auf `stderr`, Report-Datei und Schema-Artefakt konsistent
- Compare deckt `file/file`, `file/db` und `db/db` ohne Widerspruch ab
- Transfer scheitert bei Zielinkompatibilitaeten oder FK-Zyklen bereits im
  Preflight
- Doku-Beispielpfade verweisen auf reale Fixture- oder Work-Pfade
- DB-Smokes benutzen das dokumentierte lokale Docker-/Alias-Setup aus
  `fixtures/ddl/` statt freier `host`-Platzhalter
- CLI-Smokes laufen gegen das finale Runtime-Image statt gegen ein reines
  Build-Stage-Image
- `docs/releasing.md` ist mit den tatsaechlich lauffaehigen Smoke-Kommandos
  nachgefuehrt

---

## 10. Risiken und offene Punkte

### R1 - Testarbeit kann an den realen 0.6.0-Luecken vorbeilaufen

Wenn Phase H nur vorhandene 0.5.x-Testmuster erweitert, aber die neuen
0.6.0-Risiken nicht priorisiert, bleibt die Testbasis breit, aber an den
entscheidenden Stellen zu flach.

### R2 - Dialekt- und Versionsunterschiede machen Reverse-Tests fragil

Gerade Reverse-Reads koennen zwischen Datenbankversionen leicht abweichen.
Driver-nahe Tests muessen deshalb fachlich stabil formuliert sein und sich auf
relevante 0.6.0-Vertraege konzentrieren.

### R3 - Doku driftet leicht zwischen Spezifikation, Architektur und Living Design

`cli-spec.md`, `architecture.md` und `design.md` haben unterschiedliche Rollen.
Wenn Phase H diese Rollen nicht sauber zusammenzieht, entstehen erneut mehrere
leicht abweichende 0.6.0-Erzaehlungen.

### R4 - Generische Fixture-Pfade sind bequem, aber im Repo falsch

Der reale Repo-Pfad liegt heute unter
`adapters/driven/formats/src/test/resources/fixtures/`. Bleiben generische
Angaben wie `src/test/resources/fixtures/` unkommentiert stehen, werden
Beispielpfade und Smokes schnell falsch oder unklar.

### R5 - Ein neues `examples/`-Verzeichnis waere fuer 0.6.0 leicht ueberzogen

Da es aktuell kein `examples/`-Verzeichnis gibt, sollte Phase H nicht reflexhaft
ein neues Demo-Artefakt aufbauen, wenn die vorhandenen Fixture-Schemas und
Release-Smokes denselben Zweck mit deutlich weniger Wartungskosten erfuellen.
