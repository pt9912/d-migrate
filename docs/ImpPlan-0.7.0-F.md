# Implementierungsplan: Phase F - Doku, Smokes und Release-Pfade nachziehen

> **Milestone**: 0.7.0 - Tool-Integrationen
> **Phase**: F (Doku, Smokes und Release-Pfade nachziehen)
> **Status**: Draft (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.7.0.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 4.2 bis 4.9, Abschnitt 5 Phase F, Abschnitt 6,
> Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10;
> `docs/ImpPlan-0.7.0-A.md`; `docs/ImpPlan-0.7.0-B.md`;
> `docs/ImpPlan-0.7.0-C.md`; `docs/ImpPlan-0.7.0-D.md`;
> `docs/ImpPlan-0.7.0-E.md`; `docs/cli-spec.md`; `docs/design.md`;
> `docs/architecture.md`; `docs/releasing.md`;
> `docs/lastenheft-d-migrate.md` LF-011 / LF-014

---

## 1. Ziel

Phase F schliesst 0.7.0 als produktionsreifen Milestone ab: Nach den
Vertragsarbeiten aus Phase A, den Kern- und Renderpfaden aus Phase B/C, dem
produktiven CLI-/Runner-Wiring aus Phase D und der Runtime-Validierung aus
Phase E werden nun Doku, Smoke-Pfade und Release-Anleitung auf denselben
finalen Exportvertrag nachgezogen.

Der Teilplan fuehrt bewusst keine neuen Port-, Renderer-, CLI- oder
Runtime-Vertraege ein. Er macht den bereits implementierten 0.7.0-Stand fuer
Nutzer, Maintainer und Release-Pfade konsistent sichtbar.

Nach Phase F soll klar und testbar gelten:

- `docs/cli-spec.md`, `docs/design.md`, `docs/architecture.md` und
  `docs/releasing.md` beschreiben fuer die jeweils relevanten Aspekte
  widerspruchsfrei denselben 0.7.0-Vertrag
- die Doku trennt 0.7.0-Tool-Export weiterhin sauber von spaeteren
  diff-basierten `schema migrate`-/`schema rollback`-Pfaeden
- es gibt mindestens einen reproduzierbaren Tool-Export-Smoke im Release-Pfad
- Release- und Smoke-Dokumentation passen zum tatsaechlichen CLI- und
  Runtime-Verhalten

---

## 2. Ausgangslage

Aktueller Stand der Codebasis und der Dokumentation:

- Phase A hat den 0.7.0-Exportvertrag fuer Flags, Versionen, Rollback und
  Exit-Codes in der Doku geschaerft.
- Phase B fuehrt den gemeinsamen Migrations-Bundle-Vertrag ein.
- Phase C liefert die vier Tool-Renderer.
- Phase D beschreibt den produktiven `d-migrate export ...`-Pfad.
- Phase E beschreibt die fokussierte Runtime-Matrix fuer echte Tool-
  Validierung.
- `docs/cli-spec.md` enthaelt bereits einen weitgehend ausformulierten
  0.7.0-Exportabschnitt.
- `docs/design.md` enthaelt bereits einen 0.7.0-spezifischen Abschnitt fuer
  Tool-Export-Rollback.
- `docs/architecture.md` enthaelt bislang eher Vorlaeufer und Analogien aus
  benachbarten Pfaden wie `schema generate`, muss aber den finalen
  Integrationsadapter- und CLI-Zuschnitt fuer 0.7.0 erst noch explizit
  abbilden.
- `docs/releasing.md` enthaelt bereits Release-/Smoke-Schritte fuer bestehende
  Features, aber noch keinen expliziten 0.7.0-Tool-Export-Smoke.

Konsequenz fuer Phase F:

- Der groesste offene 0.7.0-Gap liegt jetzt nicht mehr im Code, sondern in der
  Konsistenz zwischen Spezifikation, Architekturtext und Release-Anleitung.
- Ohne Phase F bleibt 0.7.0 technisch umgesetzt, aber fuer Release und
  Langzeitpflege dokumentarisch unsauber.

---

## 3. Scope fuer Phase F

### 3.1 In Scope

- finalen Exportvertrag in `docs/cli-spec.md` gegen den implementierten Stand
  pruefen und nachziehen
- `docs/design.md` fuer Integrations-Export und Rollback-Zielbild auf den
  finalen 0.7.0-Stand bringen
- `docs/architecture.md` um den finalen Integrationsadapter- und
  CLI-/Runner-Pfad ergaenzen
- `docs/releasing.md` um mindestens einen reproduzierbaren Tool-Export-Smoke
  erweitern
- Angleichen von:
  - Flag-Vertrag
  - Versionsstrategie
  - Rollback-Einordnung
  - Output-/Mutationsregeln
  - fokussierter Runtime-Matrix
- Smoke-Kommandos und Release-Anleitung auf echte 0.7.0-Pfade ausrichten

### 3.2 Bewusst nicht Teil von Phase F

- neue Produktionsfeatures fuer Tool-Export
- neue Runtime-Matrix oder weitere Tool-/Dialektkombinationen
- Aenderungen am Bundle- oder Renderer-Vertrag
- neue CLI-Flags
- Ausbau zu einer vollstaendigen Endnutzer-Anleitung jenseits des fuer 0.7.0
  noetigen Vertrags- und Release-Umfangs

---

## 4. Leitentscheidungen fuer Phase F

### 4.1 Phase F dokumentiert den bestehenden 0.7.0-Vertrag, sie erfindet keinen neuen

Diese Phase ist eine Synchronisations- und Abschlussphase, keine versteckte
Nachverhandlung des Milestones.

Verbindliche Folge:

- `docs/cli-spec.md`, `docs/design.md`, `docs/architecture.md` und
  `docs/releasing.md` werden am implementierten und getesteten Stand
  ausgerichtet.
- Neue fachliche Anforderungen werden in Phase F nicht mehr eingefuehrt.
- Falls ein Dokument noch vom Code abweicht, wird die Doku dem freigegebenen
  0.7.0-Vertrag nachgezogen, nicht umgekehrt.

### 4.2 Die Doku muss den 0.7.0-Export sauber von spaeteren Diff-Pfaden trennen

0.7.0 exportiert baseline-/full-state-Artefakte aus einem einzelnen Schema.
Das bleibt auch im Abschlussdokument klar.

Verbindliche Folge:

- Die Doku beschreibt `export flyway|liquibase|django|knex` nicht als Ersatz
  fuer spaetere inkrementelle `schema migrate`-/`schema rollback`-Pfade.
- Verweise auf `DiffResult`-basierte Migrationen bleiben als spaeterer
  Milestone eingeordnet.
- Rollback in 0.7.0 bleibt tool-spezifisch und basiert auf dem bestehenden
  full-state-`generateRollback()`-Pfad.

### 4.3 CLI-Spec und Release-Smokes muessen denselben Flag-Vertrag sprechen

Der Nutzervertrag lebt nicht nur in der CLI-Spec, sondern auch in der
Release-Anleitung.

Verbindliche Folge:

- `--source`, `--output`, `--target`, `--version`, `--generate-rollback` und
  `--report` werden in CLI-Spec und Release-Smokes konsistent dargestellt.
- Pflicht-/Optional-Regeln fuer `--version` bleiben tool-spezifisch:
  - Flyway/Liquibase mit `schema.version`-Fallback
  - Django/Knex mit explizitem `--version`
- Die Release-Doku suggeriert keine impliziten Defaults, die der CLI-Vertrag
  nicht hergibt.

### 4.4 Architekturtext beschreibt den echten Pfad durch Hexagon, Integrationsadapter und CLI

Nach Phase D/E existiert eine konkrete Exportachse, die auch in
`docs/architecture.md` sichtbar sein muss.

Verbindliche Folge:

- `docs/architecture.md` beschreibt:
  - den Bundle-/Portvertrag
  - den `ToolExportRunner`
  - die vier Integrations-Exporter
  - die `export`-CLI-Gruppe
  - die Trennung zwischen Produktionspfad und Runtime-Testpfad
- Dabei bleibt klar, welche Teile Produktionscode und welche Teile Test-/
  Validierungspfad sind.

### 4.5 Release-Smokes bleiben klein, reproduzierbar und kontraktnah

Phase F braucht keine neue Vollmatrix in der Release-Doku, sondern einen
belastbaren Mindest-Smoke.

Verbindliche Folge:

- `docs/releasing.md` enthaelt mindestens einen Tool-Export-Smoke, der den
  echten 0.7.0-Pfad nutzt.
- Der Smoke nutzt ein kleines, bestehendes oder klar benanntes Fixture-Schema.
- Der Smoke prueft nicht nur, dass ein Kommando laeuft, sondern dass die
  erwarteten Artefakte im Ausgabeverzeichnis entstehen.
- Release-Smokes duplizieren nicht die gesamte Runtime-Matrix aus Phase E.

### 4.6 Release-Doku darf den Runtime-Grad nicht ueber- oder unterverkaufen

Phase E validiert eine fokussierte Runtime-Matrix. Phase F muss diese
Einordnung korrekt transportieren.

Verbindliche Folge:

- `docs/releasing.md` und die uebrige Doku behaupten keine breitere
  Tool-/Dialektabdeckung als Phase E tatsaechlich liefert.
- Gleichzeitig verschweigen sie die vorhandene Runtime-Validierung nicht.
- Flyway-Undo bleibt auch in der Abschlussdoku als editions-/praxisabhaengig
  eingeordnet.

---

## 5. Arbeitspakete fuer Phase F

### F.1 `docs/cli-spec.md` auf den finalen 0.7.0-Stand bringen

Mindestens noetig:

- Export-Unterabschnitt gegen den implementierten CLI-Stand pruefen
- Pflicht-/Optional-Regeln der Flags absichern
- Exit-Code-Vertrag gegen den produktiven Exportpfad verifizieren
- Rollback- und Liquibase-Formatbeschreibung auf dem finalen Stand halten

### F.2 `docs/design.md` fuer Integrations-Export und Rollback-Zielbild nachziehen

Mindestens noetig:

- 0.7.0-Exportmodell gegen spaetere Diff-Pfade abgrenzen
- tool-spezifische Rollback-Formate konsistent beschreiben
- Mindesttiefe der 0.7.0-Integration ohne ueberschiessende Native-Semantik
  dokumentieren

### F.3 `docs/architecture.md` um den finalen Exportpfad ergaenzen

Mindestens noetig:

- Port-/Bundle-Vertrag verorten
- `ToolExportRunner` im Application-Layer verorten
- Integrationsadapter im Driven-Layer verorten
- `export`-CLI-Gruppe im Driving-Layer verorten

### F.4 `docs/releasing.md` um mindestens einen Tool-Export-Smoke erweitern

Mindestens noetig:

- Auswahl eines kleinen Fixture-Schemas
- dokumentierter Smoke fuer mindestens ein Tool-Export-Kommando
- klare Beschreibung von Voraussetzungen, Kommandos und erwarteten Artefakten
- Einordnung, wie sich dieser Smoke zu den umfassenderen Runtime-Tests aus
  Phase E verhaelt

### F.5 Dokumente gegeneinander auf Vertragsdrift pruefen

Mindestens noetig:

- Vergleich von:
  - `docs/cli-spec.md`
  - `docs/design.md`
  - `docs/architecture.md`
  - `docs/releasing.md`
- Abgleich auf:
  - Flag-Vertrag
  - Version-/Rollback-Regeln
  - Output-/Mutationsregeln
  - Runtime-Einordnung

Ziel von Phase F:

- Spezifikation, Architekturtext, Design-Einordnung und Release-Smokes
  sprechen nach der Umsetzung denselben 0.7.0-Vertrag.

---

## 6. Technische Zielstruktur fuer Phase F

Bevorzugte Struktur:

- `docs/cli-spec.md`
  - finaler Nutzervertrag fuer `export flyway|liquibase|django|knex`
- `docs/design.md`
  - Einordnung des 0.7.0-Exportmodells und Rollback-Zielbilds
- `docs/architecture.md`
  - finaler Produktionspfad fuer Bundle -> Runner -> Exporter -> CLI
- `docs/releasing.md`
  - reproduzierbare Smoke-Schritte fuer Tool-Export

Illustrativer Zielzuschnitt:

- CLI-Spec:
  - Flags
  - Exit-Codes
  - Rollback-Einordnung
  - Liquibase-Format
- Architecture:
  - Hexagon-/Adapter-Zuschnitt des Exportpfads
- Releasing:
  - Build-/Runtime-Voraussetzungen
  - mindestens ein Export-Smoke
  - erwartete Artefaktpfade bzw. Artefaktnamen

Nicht Teil der Zielstruktur von Phase F:

- neue Code-Module
- neue Testmodule
- neue Release-Infrastruktur jenseits des fuer 0.7.0 noetigen Smoke-Pfads

---

## 7. Betroffene Artefakte

Direkt betroffen:

- `docs/implementation-plan-0.7.0.md`
- `docs/ImpPlan-0.7.0-A.md`
- `docs/ImpPlan-0.7.0-B.md`
- `docs/ImpPlan-0.7.0-C.md`
- `docs/ImpPlan-0.7.0-D.md`
- `docs/ImpPlan-0.7.0-E.md`
- `docs/cli-spec.md`
- `docs/design.md`
- `docs/architecture.md`
- `docs/releasing.md`

Indirekt als Ist-Referenz relevant:

- produktiver Exportpfad in:
  - `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/...`
  - `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/...`
  - `adapters/driven/integrations/src/main/kotlin/dev/dmigrate/integration/...`
- Runtime-Testpfade aus Phase E
- Fixture-Schemas unter
  `adapters/driven/formats/src/test/resources/fixtures/schemas/`

---

## 8. Akzeptanzkriterien

- [ ] `docs/cli-spec.md`, `docs/design.md`, `docs/architecture.md` und
      `docs/releasing.md` beschreiben fuer ihre jeweiligen Schwerpunkte
      widerspruchsfrei denselben 0.7.0-Vertrag:
      Flag-Vertrag, Versionsstrategie, Rollback-Einordnung,
      Output-/Mutationsregeln, Runtime-Einordnung und Release-Smoke.
- [ ] Die Doku beschreibt `export flyway|liquibase|django|knex` explizit als
      baseline-/full-state-Export aus einem einzelnen neutralen Schema und
      nicht als Ersatz fuer spaetere diff-basierte Migrationen.
- [ ] `docs/cli-spec.md` beschreibt den finalen Flag-Vertrag fuer
      `--source`, `--output`, `--target`, `--version`,
      `--spatial-profile`, `--generate-rollback` und `--report`
      konsistent zum implementierten CLI-Pfad.
- [ ] `docs/design.md` beschreibt die tool-spezifischen Rollback-Formate
      konsistent zum 0.7.0-Vertrag.
- [ ] `docs/architecture.md` beschreibt den finalen Exportpfad ueber
      Bundle-/Portvertrag, `ToolExportRunner`, Integrationsadapter und
      `export`-CLI-Gruppe.
- [ ] `docs/releasing.md` enthaelt mindestens einen reproduzierbaren
      Tool-Export-Smoke.
- [ ] Der dokumentierte Smoke nutzt den echten 0.7.0-Exportpfad und nennt die
      erwarteten Artefakte oder Artefaktpfade explizit.
- [ ] Die Release-Doku ordnet die fokussierte Runtime-Matrix aus Phase E
      korrekt ein und suggeriert keine breitere Tool-/Dialektabdeckung.
- [ ] Flyway-Undo bleibt in den Abschlussdokumenten explizit als
      editions-/praxisabhaengig eingeordnet.

---

## 9. Verifikation

Mindestumfang fuer die Phase-F-Umsetzung:

1. Vertragsstellen in der Doku gegeneinander pruefen:

```bash
rg -n "export flyway|export liquibase|export django|export knex|generate-rollback|schema.version|changeSet|reverse_sql|exports.down|baseline|full-state|DiffResult" \
  docs/cli-spec.md docs/design.md docs/architecture.md docs/releasing.md
```

Danach dokumentenweise manuell gegen denselben Sollsatz abgleichen:

- `docs/cli-spec.md`:
  - `--source`, `--output`, `--target`, `--version`, `--spatial-profile`,
    `--generate-rollback`, `--report`
  - Flyway/Liquibase-`schema.version`-Fallback vs. Django/Knex-Pflichtversion
- `docs/design.md`:
  - 0.7.0 als baseline-/full-state-Export
  - tool-spezifische Rollback-Formate
  - keine Verwechslung mit spaeteren Diff-Pfaden
- `docs/architecture.md`:
  - Bundle-/Portvertrag
  - `ToolExportRunner`
  - vier Integrations-Exporter
  - `export`-CLI-Gruppe
  - Trennung Produktionspfad vs. Runtime-Testpfad
- `docs/releasing.md`:
  - mindestens ein realer Export-Smoke
  - reale CLI-Kommandos und Fixture-Schema
  - erwartete Artefaktpfade/-namen
  - keine breitere Tool-/Dialektbehauptung als Phase E

2. Release-Smoke-Dokumentation pruefen:

```bash
sed -n '1,260p' docs/releasing.md
```

3. Smoke-Pfad gegen die reale CLI nachvollziehen:

```bash
rg -n "ExportCommand|ExportFlywayCommand|ExportLiquibaseCommand|ExportDjangoCommand|ExportKnexCommand|ToolExportRunner" \
  adapters/driving/cli/src/main/kotlin \
  hexagon/application/src/main/kotlin
```

Dabei explizit pruefen:

- `docs/cli-spec.md` nennt `--target` weiterhin als Pflichtflag
- Flyway/Liquibase-Fallback fuer `schema.version` und Django-/Knex-
  Pflichtversion sind in CLI-Spec und Release-Doku konsistent
- `docs/design.md` beschreibt Rollback fuer Flyway, Liquibase, Django und
  Knex im 0.7.0-Sinne
- `docs/architecture.md` bildet den Integrationsadapter- und Runner-Pfad ab
- `docs/releasing.md` enthaelt mindestens einen konkreten Export-Smoke
- der dokumentierte Smoke verweist auf ein reales Fixture-Schema und auf
  erwartete Artefaktpfade bzw. Artefaktnamen
- die Abschlussdoku trennt 0.7.0 weiterhin sauber von spaeteren
  `schema migrate`-/`schema rollback`-Pfaden

---

## 10. Risiken und offene Punkte

### R1 - Dokumente koennen denselben Vertrag in leicht verschiedenen Varianten erzaehlen

Gerade bei Flag-Details, Rollback-Einordnung und Liquibase-Format reichen
kleine Formulierungsabweichungen aus, um spaeter widerspruechliche Aussagen zu
haben.

### R2 - Release-Smokes koennen vom echten Produktpfad entkoppeln

Ein zu kuenstlicher Smoke prueft dann nur die Release-Doku, aber nicht den
wirklichen 0.7.0-Exportpfad.

### R3 - Phase F kann versehentlich neue Anforderungen nachschieben

Wenn die Abschlussphase beginnt, fehlende Features ueber Doku zu
"spezifizieren", wird aus einer Synchronisationsphase wieder eine
Vertragsphase.

### R4 - Runtime-Einordnung kann in der Abschlussdoku ueberzeichnet werden

Phase E validiert eine fokussierte Matrix. Wenn Phase F daraus eine breitere
Unterstuetzung herausliest, wird die Release-Kommunikation unsauber.
