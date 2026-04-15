# Implementierungsplan: Phase E - Echte Tool-Runtime-Validierung aufbauen

> **Milestone**: 0.7.0 - Tool-Integrationen
> **Phase**: E (Echte Tool-Runtime-Validierung aufbauen)
> **Status**: Draft (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.7.0.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 4.2 bis 4.9, Abschnitt 5 Phase E, Abschnitt 6,
> Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10;
> `docs/ImpPlan-0.7.0-A.md`; `docs/ImpPlan-0.7.0-B.md`;
> `docs/ImpPlan-0.7.0-C.md`; `docs/ImpPlan-0.7.0-D.md`;
> `docs/cli-spec.md`; `docs/design.md`; `docs/architecture.md`;
> `docs/lastenheft-d-migrate.md` LF-011 / LF-014

---

## 1. Ziel

Phase E schliesst die groesste verbleibende 0.7.0-Risikoluecke: Die in
Phase C gerenderten und in Phase D produktiv exportierten Artefakte sollen
nicht nur formal korrekt aussehen, sondern in einer fokussierten Matrix von
echten Tool-Runtimes akzeptiert und ausgefuehrt werden.

Der Teilplan fuehrt bewusst keine neuen CLI-Vertraege, keine neuen
Renderformate und keinen allgemeinen diff-basierten Migrationspfad ein.
Er validiert den bereits definierten 0.7.0-Vertrag gegen reale Flyway-,
Liquibase-, Django- und Knex-Umgebungen.

Nach Phase E soll klar und testbar gelten:

- es gibt echte Runtime-Tests fuer:
  - Flyway -> PostgreSQL
  - Liquibase -> PostgreSQL
  - Django -> SQLite
  - Knex -> SQLite
- die von `d-migrate export ...` erzeugten Artefakte werden von den
  jeweiligen Tools geladen und ausgefuehrt
- Rollback-/Reverse-Pfade werden dort validiert, wo 0.7.0 sie explizit
  materialisiert
- Phase E prueft keine blossen String- oder Snapshot-Eigenschaften erneut,
  sondern Runtime-Akzeptanz und tatsaechliche Schemawirkung
- die Testmatrix bleibt bewusst fokussiert und wird nicht kartesisch ueber
  alle Dialekte und Tool-Kombinationen ausgedehnt

---

## 2. Ausgangslage

Aktueller Stand der Codebasis und der Dokumentation:

- Phase B definiert den gemeinsamen Exportvertrag in `hexagon:ports` und die
  noetigen Helper in `hexagon:application`.
- Phase C liefert produktive Renderer fuer:
  - Flyway
  - Liquibase
  - Django
  - Knex
- Phase D beschreibt den produktiven CLI-/Runner-Pfad fuer:
  - `ToolExportRequest`
  - `ToolExportRunner`
  - `d-migrate export flyway|liquibase|django|knex`
- Das Repo enthaelt bereits umfangreiche Unit- und Integrations-Tests fuer:
  - Versionsregeln
  - Bundle-Aufbau
  - Renderer-Inhalte
  - bestehende Daten- und Schema-Pfade
- Unter `adapters/driven/integrations/src/test/kotlin/dev/dmigrate/integration/`
  existieren derzeit nur renderer-nahe Tests; echte Tool-Runtimes werden dort
  noch nicht ausgefuehrt.
- Das Repo nutzt bereits fuer andere Bereiche Integrations-Testmuster mit
  fokussierten, getaggten Runtime-Tests und Testcontainers.
- Es gibt mit `./scripts/test-integration-docker.sh` bereits ein vorhandenes
  Skript fuer containerisierte Integrations-Testlaeufe.
- Der Masterplan fixiert fuer 0.7.0 explizit die Runtime-Matrix:
  - Flyway -> PostgreSQL
  - Liquibase -> PostgreSQL
  - Django -> SQLite
  - Knex -> SQLite

Konsequenz fuer Phase E:

- Die groessten offenen Risiken liegen nicht mehr in Port-, Renderer- oder
  CLI-Vertragsfragen, sondern in der Frage, ob die erzeugten Artefakte von
  echten Tool-Runtimes akzeptiert werden.
- Ohne Phase E bliebe 0.7.0 auf der Ebene von String-/Datei-Korrektheit
  stehen, obwohl Roadmap und Masterplan explizit echte Tool-Ausfuehrung
  verlangen.

---

## 3. Scope fuer Phase E

### 3.1 In Scope

- Runtime-Tests fuer die fokussierte Matrix:
  - Flyway gegen PostgreSQL
  - Liquibase gegen PostgreSQL
  - Django gegen SQLite
  - Knex gegen SQLite
- minimal noetige Testprojekte bzw. Fixture-Umgebungen fuer:
  - Liquibase-Changelog-Ausfuehrung
  - minimales Django-Projekt
  - minimales Knex-Projekt
- Generierung der Export-Artefakte ueber denselben produktiven 0.7.0-
  Exportpfad, den auch die Produktion nutzt
- Verifikation der tatsaechlichen Schemawirkung nach Tool-Ausfuehrung
- Verifikation von Rollback-/Reverse-Pfaden, soweit sie fuer 0.7.0 im
  jeweiligen Tool-Vertrag vorgesehen sind
- Isolation und Markierung der Runtime-Tests als Integrations-/Container-Tests
- CI-taugliche Ausfuehrung der fokussierten Matrix

### 3.2 Bewusst nicht Teil von Phase E

- neue Renderformate oder inhaltliche Aenderungen am Exportvertrag
- neue CLI-Flags oder Aenderungen am Exit-Code-Vertrag
- breite Vollmatrix ueber alle Dialekte pro Tool
- automatisches Patchen echter Nutzerprojekte
- Performance-Benchmarks oder Lasttests fuer Tool-Export
- Release-Smokes und finale Doku-Synchronisation aus Phase F

---

## 4. Leitentscheidungen fuer Phase E

### 4.1 Phase E validiert echte Tools, nicht nur gerenderte Texte

Renderer-Tests aus Phase C bleiben wichtig, sind fuer 0.7.0 aber nicht
hinreichend.

Verbindliche Folge:

- Ein Phase-E-Test ist erst dann aussagekraeftig, wenn ein reales Tool die
  generierten Artefakte laedt und ausfuehrt.
- Snapshot-, String- oder Regex-Pruefungen alleine genuegen in Phase E nicht.
- Der Fokus liegt auf Runtime-Akzeptanz und beobachtbarer Schemawirkung.

### 4.2 Die Runtime-Matrix bleibt bewusst fokussiert

Der Masterplan nennt fuer 0.7.0 keine kartesische Vollmatrix, sondern eine
gezielte Risikoreduktion.

Verbindliche Folge:

- Flyway wird gegen PostgreSQL validiert.
- Liquibase wird gegen PostgreSQL validiert.
- Django wird gegen SQLite validiert.
- Knex wird gegen SQLite validiert.
- Weitere Dialekt-/Tool-Kombinationen sind fuer 0.7.0 kein Phase-E-
  Abnahmekriterium.

### 4.3 Die Tests laufen ueber reale 0.7.0-Artefakte, nicht ueber handgepflegte Tool-Dateien

Phase E soll den eigentlichen Exportpfad absichern und nicht bloss separat
kuratierten Fixture-Code testen.

Verbindliche Folge:

- Die zu validierenden Migrationsdateien werden im Test aus demselben
  produktiven Exportpfad erzeugt, den auch die Produktion nutzt.
- Zulaessige Testeintrittspunkte sind nur produktionsnahe Aufrufe desselben
  Pfads, z. B. ueber `d-migrate export ...` oder denselben
  `ToolExportRunner`-Pfad aus Phase D.
- Handgepflegte Tool-Files duerfen nur als minimale Projektumgebung dienen,
  nicht als Ersatz fuer die exportierten Artefakte.
- Ein Test darf nicht still auf manuell vorab erstellte Flyway-, Liquibase-,
  Django- oder Knex-Migrationsdateien ausweichen.
- Nicht zulaessig ist ein separater, nur fuer Tests gebauter
  "aequivalenter" Exportpfad mit eigener Bundle-, Render- oder Write-Logik.

### 4.4 Fixture-Projekte bleiben minimal und rein testorientiert

Fuer Django und Knex braucht Phase E mehr als nur eine einzelne Datei: das
Tool erwartet ein kleines Projektgeruest.

Verbindliche Folge:

- Django bekommt ein minimales Projekt, das genau das Laden und Anwenden der
  generierten Migration prueft.
- Knex bekommt ein minimales Projekt mit genau der noetigen Konfiguration fuer
  `migrate:latest` und `migrate:rollback`.
- Liquibase bekommt einen minimalen Ausfuehrungskontext fuer den generierten
  versionierten XML-Changelog, ohne Master-Changelog-Mutation.
- Diese Testumgebungen bleiben klein, reproduzierbar und eng an den
  0.7.0-Vertrag gebunden.

### 4.5 Rollback wird tool-spezifisch und entlang des 0.7.0-Vertrags validiert

Phase E prueft keine allgemeine Undo-Semantik, sondern exakt den fuer 0.7.0
dokumentierten Rueckweg.

Verbindliche Folge:

- Flyway validiert die generierte Up-Datei und optional die Nutzbarkeit des
  Undo-Artefakts, soweit die gewaehlte Testumgebung dies reproduzierbar
  abbildet.
- Liquibase validiert den `<rollback>`-Block im selben Changeset.
- Django validiert `reverse_sql`.
- Knex validiert `exports.down` via `migrate:rollback`.
- Falls Flyway-Undo editions- oder praxisabhaengig bleibt, muss der Test die
  gewaehlte Einordnung klar machen, statt still eine staerkere Garantie zu
  suggerieren.

### 4.6 Phase E zieht Tool-Runtimes nur in den Testpfad, nicht in den Produktionspfad

LF-011 beschreibt die Integrationen als optionale Output-Pfade. Das bleibt
auch unter Runtime-Validierung wahr.

Verbindliche Folge:

- Produktionsmodule bleiben frei von Flyway-, Liquibase-, Django- oder Knex-
  Laufzeitabhaengigkeiten.
- Tool-Runtimes erscheinen nur in Testumgebungen, Testcontainern,
  Dockerfiles oder testnahen Projektgeruesten.
- Ein erfolgreiches Phase-E-Ergebnis darf keine neue Pflichtlaufzeit fuer den
  normalen Build- oder CLI-Betrieb einziehen.

### 4.7 Runtime-Tests muessen in CI steuerbar und vom Default-Testlauf getrennt sein

Die Toolmatrix ist schwerer als normale Unit- oder Renderer-Tests.

Verbindliche Folge:

- Phase-E-Tests werden klar als Integrations-/Runtime-Tests markiert.
- Default-Testlaeufe koennen schlank bleiben.
- Es gibt einen dokumentierten CI-/Docker-Pfad, ueber den die Runtime-Matrix
  reproduzierbar ausgefuehrt werden kann.

---

## 5. Arbeitspakete fuer Phase E

### E.1 Gemeinsame Runtime-Teststrategie fuer `adapters:driven:integrations` festziehen

Mindestens noetig:

- Festlegung, wo die Phase-E-Tests liegen
- Festlegung, wie sie markiert oder gefiltert werden
- Festlegung, wie Testartefakte erzeugt und an Tool-Runtimes uebergeben werden

### E.2 Flyway-Validierung gegen PostgreSQL aufbauen

Mindestens noetig:

- Export eines Flyway-Artefakts ueber den produktiven 0.7.0-Pfad
- Ausfuehrung gegen PostgreSQL
- Verifikation, dass die erwarteten Tabellen / Objekte existieren
- sinnvolle Einordnung des Undo-Pfads fuer die gewaehlte Testumgebung

### E.3 Liquibase-Validierung gegen PostgreSQL aufbauen

Mindestens noetig:

- Export des versionierten XML-Changelogs
- Ausfuehrung des Changesets gegen PostgreSQL
- Verifikation der erwarteten Schemawirkung
- Ausfuehrung und Verifikation des optionalen `<rollback>`-Blocks

### E.4 Django-Validierung gegen SQLite aufbauen

Mindestens noetig:

- minimales Django-Testprojekt
- Einhaengen der generierten Migration in die Projektstruktur
- Ausfuehrung von `migrate`
- Verifikation der erwarteten Schemawirkung
- Ausfuehrung des Reverse-Pfads fuer `reverse_sql`

### E.5 Knex-Validierung gegen SQLite aufbauen

Mindestens noetig:

- minimales Knex-Testprojekt
- Platzierung der generierten Migrationsdatei
- Ausfuehrung von `migrate:latest`
- Verifikation der erwarteten Schemawirkung
- Ausfuehrung von `migrate:rollback`

### E.6 Docker-/CI-Pfade fuer die Runtime-Matrix absichern

Mindestens noetig:

- Integration in vorhandene Docker-/Integrations-Testpfade
- reproduzierbare Ausfuehrung in CI
- klare Trennung zwischen Default-Testlauf und Runtime-Matrix

### E.7 Runtime-Fixtures und Assertions klein halten

Mindestens noetig:

- ein minimales, aber aussagekraeftiges Schema-Fixture
- einfache, robuste Assertions auf die tatsaechliche Schemawirkung
- keine ueberladenen Testprojekte mit unnoetiger eigener Fachlogik

Ziel von Phase E:

- Die generierten 0.7.0-Artefakte werden in der fokussierten Toolmatrix von
  echten Runtimes akzeptiert und ausgefuehrt.

---

## 6. Technische Zielstruktur fuer Phase E

Bevorzugte Struktur:

- Runtime-Tests unter oder nahe
  `adapters/driven/integrations/src/test/kotlin/dev/dmigrate/integration/`
- optionale testnahe Fixture-Projekte / Ressourcen unter
  `adapters/driven/integrations/src/test/resources/...`
- vorhandene Integrations-Testskripte weiterverwenden, statt neue parallele
  Test-Entrypoints aufzubauen

Illustrativer Zielzuschnitt:

- Flyway-/Liquibase-Tests:
  - PostgreSQL-Testcontainer oder aequivalente Docker-Testumgebung
  - exportierte Artefakte werden in ein temporaeres Laufverzeichnis geschrieben
  - Tool wird gegen dieses Verzeichnis ausgefuehrt
- Django-/Knex-Tests:
  - minimales Python-/Node-Projekt in Testressourcen
  - exportierte Artefakte werden in die erwarteten Migrationsordner gelegt
  - Tool-Kommandos werden in dieser Umgebung ausgefuehrt

Nicht Teil der Zielstruktur von Phase E:

- Tool-Runtime-Libraries im Produktionscode
- neue Produktionsmodule nur fuer Tests
- generische Multi-Tool-Abstraktionen, die nur Testkomplexitaet verschieben

---

## 7. Betroffene Artefakte

Direkt betroffen:

- `docs/implementation-plan-0.7.0.md`
- `docs/ImpPlan-0.7.0-A.md`
- `docs/ImpPlan-0.7.0-B.md`
- `docs/ImpPlan-0.7.0-C.md`
- `docs/ImpPlan-0.7.0-D.md`
- `adapters/driven/integrations/src/test/kotlin/dev/dmigrate/integration/...`
- `adapters/driven/integrations/src/test/resources/...`
- `scripts/test-integration-docker.sh`
- CI-/Docker-Testpfade, soweit sie die Runtime-Matrix ausfuehren

Indirekt als Ist-Referenz relevant:

- `docs/cli-spec.md`
- `docs/design.md`
- `docs/architecture.md`
- bestehende Integrations-/E2E-Tests unter `adapters/driving/cli/src/test/kotlin/...`
- vorhandene Fixture-Schemas unter
  `adapters/driven/formats/src/test/resources/fixtures/schemas/`

Noch nicht Teil von Phase E, aber Folgeartefakte vorzubereiten:

- `docs/releasing.md`
- finale Export-Smokes in Phase F

---

## 8. Akzeptanzkriterien

- [ ] Echte Tool-Runtime-Tests laufen mindestens fuer:
      Flyway -> PostgreSQL, Liquibase -> PostgreSQL, Django -> SQLite,
      Knex -> SQLite.
- [ ] Die Runtime-Tests erzeugen ihre Migrationsartefakte aus dem
      produktiven 0.7.0-Exportvertrag und nicht aus handgepflegten
      Tool-Migrationsdateien.
- [ ] Flyway wendet die generierte versionierte SQL-Datei gegen PostgreSQL an
      und die erwarteten Tabellen / Objekte existieren danach.
- [ ] Liquibase wendet den generierten versionierten XML-Changelog gegen
      PostgreSQL an und die erwarteten Tabellen / Objekte existieren danach.
- [ ] Django laedt die generierte Migration in einem minimalen Django-Projekt
      und `migrate` funktioniert gegen SQLite; die erwarteten Tabellen /
      Objekte existieren danach.
- [ ] Knex laedt die generierte Migration in einem minimalen Knex-Projekt und
      `migrate:latest` funktioniert gegen SQLite; die erwarteten Tabellen /
      Objekte existieren danach.
- [ ] Liquibase validiert den dokumentierten `<rollback>`-Pfad.
- [ ] Django validiert den dokumentierten `reverse_sql`-Pfad und dessen
      beobachtbare Schemawirkung.
- [ ] Knex validiert den dokumentierten `exports.down`-Pfad und dessen
      beobachtbare Schemawirkung.
- [ ] Flyway-Undo ist in der Testumgebung entweder reproduzierbar validiert oder
      im Testaufbau explizit als editions-/praxisabhaengig eingeordnet; der
      Test suggeriert keine staerkere Garantie als der Produktvertrag.
- [ ] Die Runtime-Tests sind als Integrations-/Container-Tests getrennt vom
      Default-Testlauf steuerbar.
- [ ] Die Runtime-Matrix ist ueber vorhandene Docker-/CI-Pfade
      reproduzierbar ausfuehrbar.

---

## 9. Verifikation

Mindestumfang fuer die Phase-E-Umsetzung:

1. Runtime-Testfaelle und Fixtures pruefen:

```bash
rg -n "Flyway|Liquibase|Django|Knex|integration|rollback|reverse_sql|migrate:latest|migrate:rollback" \
  adapters/driven/integrations/src/test/kotlin \
  adapters/driven/integrations/src/test/resources
```

2. Fokussierte Runtime-Tests ueber den vorhandenen Docker-Pfad ausfuehren:

```bash
./scripts/test-integration-docker.sh -PintegrationTests :adapters:driven:integrations:test
```

3. Bei Bedarf CI-nahe Runtime-Umgebung lokal nachvollziehen:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:integrations:test" \
  -t d-migrate:phase-070-runtime-tests .
```

Dabei explizit pruefen:

- Flyway wendet die generierten Artefakte gegen PostgreSQL an
- Liquibase wendet den generierten Changelog gegen PostgreSQL an
- Django laedt die generierte Migration in einem minimalen Projekt und
  `migrate` funktioniert; die erwartete Schemawirkung ist anschliessend
  beobachtbar
- Knex laedt die generierte Migration in einem minimalen Projekt und
  `migrate:latest` / `migrate:rollback` funktionieren; Up- und Down-Pfad
  zeigen die erwartete Schemawirkung
- Rollback-/Reverse-Pfade werden fuer Liquibase, Django und Knex tatsaechlich
  ausgefuehrt
- die Testartefakte stammen aus dem produktiven Exportpfad
- Runtime-Tests sind vom Default-Testlauf getrennt steuerbar

---

## 10. Risiken und offene Punkte

### R1 - Tool-spezifische Testumgebungen koennen mehr Komplexitaet einziehen als der Produktvertrag braucht

Wenn Phase E fuer Django oder Knex zu grosse Testprojekte aufbaut, validiert
man schnell mehr Projektgeruest als eigentlichen Exportvertrag.

### R2 - Flyway-Undo bleibt editions- bzw. praxisabhaengig

Undo-Skripte sind in Flyway nicht ueberall gleich nutzbar. Phase E muss hier
explizit machen, was die konkrete Testumgebung wirklich absichert und was
nicht.

### R3 - Runtime-Tests koennen CI spuerbar aufblaehen

Python-, Node-, Flyway- und Liquibase-Runtimes bringen eigene Laufzeit- und
Image-Kosten mit.

### R4 - Runtime-Tests koennen versehentlich vom eigentlichen Exportpfad entkoppeln

Wenn Testartefakte nicht mehr ueber denselben Exportvertrag erzeugt werden wie
in Produktion, entsteht eine gefaehrliche Scheinsicherheit.
