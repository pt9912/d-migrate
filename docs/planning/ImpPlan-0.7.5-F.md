# Implementierungsplan: Phase F - Tests, Smokes und Doku-Abgleich

> **Milestone**: 0.7.5 - Daten-Profiling
> **Phase**: F (Tests, Smokes und Doku-Abgleich)
> **Status**: Implemented (2026-04-15)
> **Referenz**: `docs/planning/implementation-plan-0.7.5.md` Abschnitt 6 Phase F,
> Abschnitt 7.1 bis 7.3, Abschnitt 8, Abschnitt 9; `docs/planning/ImpPlan-0.7.5-A.md`;
> `docs/planning/ImpPlan-0.7.5-B.md`; `docs/planning/ImpPlan-0.7.5-C.md`;
> `docs/planning/ImpPlan-0.7.5-D.md`; `docs/planning/ImpPlan-0.7.5-E.md`;
> `docs/planning/roadmap.md` Milestone 0.7.5; `spec/profiling.md`;
> `spec/design.md` Abschnitt 3.6; `spec/cli-spec.md`; `spec/architecture.md`;
> `docs/user/guide.md`

---

## 1. Ziel

Phase F schliesst den 0.7.5-Milestone ab. Ergebnis der Phase ist ein gegen
reale Laufzeitumgebungen abgesicherter Profiling-Pfad plus ein konsistenter
Satz regulaerer Dokumente, der exakt den tatsaechlich gelieferten 0.7.5-
Vertrag beschreibt.

Der Teilplan fuehrt bewusst keine neuen Profiling-Features mehr ein. Er haertet
das bereits Gebaute durch Unit-, Integrations- und CLI-Smokes, prueft den
Determinismusvertrag und zieht die regulaere Doku vom Planungs- in den finalen
Nutzervertrag ueber.

Nach Phase F soll klar und testbar gelten:

- der Profiling-Pfad ist auf Domain-, Service-, Adapter-, CLI- und Writer-Ebene
  belastbar abgesichert
- mindestens ein echter PostgreSQL- und ein echter MySQL-Lauf bestaetigen die
  dialektspezifischen Adapterpfade
- der SQLite-`:memory:`-Pfad ist als schnelle lokale Referenz stabil
- der Default-Report bleibt deterministisch:
  - stabile Tabellenreihenfolge
  - stabile Spaltenreihenfolge
  - stabile `topValues`
  - kein laufzeitvariables `generatedAt`
- `spec/cli-spec.md`, `spec/design.md`, `spec/architecture.md` und bei Bedarf
  `docs/user/guide.md` beschreiben nur den real gelieferten 0.7.5-Scope
- spaetere Zielbilder aus `spec/profiling.md` werden nicht rueckwirkend so
  dargestellt, als waeren sie bereits produktiv

---

## 2. Ausgangslage

Aktueller Stand der Codebasis und der vorigen Phasen:

- Phase B hat den Profiling-Kern in `hexagon:profiling` angelegt; dort gibt es
  bereits reine Modell-, Typ- und Regeltests.
- Phase C hat erste Profiling-Ports und Adapter fuer PostgreSQL, MySQL und
  SQLite etabliert; sichtbar sind bereits produktive Adapterklassen in den
  Driver-Modulen.
- Phase D und E definieren den Zielzustand fuer:
  - `DataProfileRunner`
  - `DataProfileCommand`
  - `ProfileReportWriter`
  - den finalen 0.7.5-CLI- und Report-Vertrag
- Im aktuellen Repo sind bereits relevante Testanker vorhanden:
  - `WarningEvaluatorTest`
  - `ProfilingModelTest`
  - `ProfilingTypesTest`
  - `SqliteProfilingTest`
  - `CliHelpAndBootstrapTest`
  - `CliDataExportTest`
- Fuer Profiling fehlen im sichtbaren Stand noch explizite Abschlussartefakte
  wie:
  - `DataProfileRunnerTest`
  - `ProfileDatabaseServiceTest`
  - `ProfileTableServiceTest`
  - `ProfileReportWriterTest`
  - PostgreSQL-/MySQL-Integrations-Smokes fuer Profiling
  - regulaerer Doku-Abgleich fuer den finalen 0.7.5-Vertrag
- Die regulaeren Doku-Dateien existieren bereits, enthalten aber fuer Profiling
  noch kein gesichertes finales 0.7.5-Nutzerbild.

Konsequenz fuer Phase F:

- Die Hauptaufgabe ist jetzt nicht mehr Implementierung, sondern belastbare
  Endabnahme.
- Wenn Phase F hier zu oberflaechlich bleibt, endet der Milestone mit Plan-
  Dokumenten statt mit einem verifizierten Nutzervertrag.
- Wenn sie zu viel neu entwirft, verwischt die Abschlussphase mit den
  eigentlichen Implementierungsphasen.

---

## 3. Scope fuer Phase F

### 3.1 In Scope

- Domain-Unit-Tests fuer Modell, Regeln und Kompatibilitaet finalisieren
- Service-Unit-Tests fuer Runner und Use-Cases finalisieren
- SQLite-Integrationspfad gegen `:memory:` absichern
- PostgreSQL-/MySQL-Integrationspfade via Testcontainers absichern
- CLI-Round-Trip-Tests fuer den finalen `data profile`-Pfad ergaenzen
- Determinismus explizit testen:
  - Tabellenreihenfolge
  - Spaltenreihenfolge
  - `topValues`-Sortierung
  - keine laufzeitvariablen Default-Report-Felder
- regulaere Dokumente auf den finalen 0.7.5-Vertrag ziehen:
  - `spec/cli-spec.md`
  - `spec/design.md`
  - `spec/architecture.md`
  - ggf. `docs/user/guide.md`
- Test- und Doku-Abnahme gegen den tatsaechlich gelieferten Scope durchfuehren

### 3.2 Bewusst nicht Teil von Phase F

- neue Ports, Adapter, Services oder Runner-Faehigkeiten
- Erweiterung des 0.7.5-CLI-Vertrags
- `--query`
- `--analyze-normalization`
- neue Analysemodelle wie `StructuralFinding` oder `NormalizationProposal`
- nachtraegliches Aufblasen des Report-Modells ueber den 0.7.5-Kern hinaus
- neue Produktfeatures, die nicht bereits in Phase A bis E geplant sind

Praezisierung:

Phase F ist Milestone-Hardening und Dokumentationsangleich. Sie fuehrt nichts
Neues ein, was den gelieferten Nutzervertrag erweitert.

---

## 4. Leitentscheidungen fuer Phase F

### 4.1 Abschlussphase testet den realen Vertrag, nicht das Wunschbild

Phase F verifiziert exakt, was 0.7.5 liefern soll, und nicht das spaetere
Vollbild aus `spec/profiling.md`.

Verbindliche Folge:

- Tests und Doku beziehen sich nur auf den 0.7.5-Funktionsumfang
- `--query` und Normalisierungsanalyse bleiben explizit ausserhalb der
  Abschlussabnahme
- fehlende spaetere Features werden nicht implizit als "kommt mit" dokumentiert

### 4.2 Testpyramide bleibt klar getrennt

Die Abschlussabnahme soll Fehler zielgerichtet auffindbar machen.

Verbindliche Folge:

- reine Modell- und Regelvertraege bleiben Unit-Tests
- Runner- und Service-Orchestrierung bleibt ohne echte DB unit-testbar
- Dialektlogik wird ueber gezielte Integrationspfade abgesichert
- CLI-Ende-zu-Ende-Tests pruefen nur den aussen sichtbaren Nutzerpfad

### 4.3 SQLite bleibt der schnelle lokale Referenzpfad

SQLite ist der guenstigste reale Datenbankpfad fuer schnelle Integrations- und
Smoke-Checks.

Verbindliche Folge:

- `:memory:` bleibt verpflichtender Abschluss-Testpfad
- Grundkennzahlen, Text-/Numerik-/Temporalfaelle und SQLite-Fallbacks werden
  dort verifiziert
- lokale Regressionen duerfen nicht allein auf Containerlaeufe angewiesen sein

### 4.4 PostgreSQL und MySQL muessen reale Adapterpfade bestaetigen

Der Milestone ist nicht belastbar abgeschlossen, wenn nur SQLite oder nur
Mocks gruen sind.

Verbindliche Folge:

- mindestens ein echter PostgreSQL-Lauf bestaetigt den Profiling-Pfad
- mindestens ein echter MySQL-Lauf bestaetigt den Profiling-Pfad
- Testcontainers sind der Standardpfad fuer diese Dialekte
- die Smokes sollen die dialektspezifischen Risiken treffen:
  - PostgreSQL `--schema`
  - Typ-Resolver
  - `topValues`
  - MySQL Text-/Numerikpfad

### 4.5 Determinismus ist ein eigener Abschlussvertrag

Determinismus ist kein Nebeneffekt, sondern expliziter Abnahmegegenstand.

Verbindliche Folge:

- Tests pruefen stabile Tabellenreihenfolge
- Tests pruefen stabile Spaltenreihenfolge
- Tests pruefen stabile `topValues`-Sortierung
- Tests pruefen, dass der Default-Report kein `generatedAt` enthaelt
- Doku beschreibt denselben Determinismusvertrag ohne Widersprueche

### 4.6 Die regulaere Doku ersetzt jetzt die Planungsdokumente als Nutzervertrag

Mit Abschluss von Phase F muessen Nutzer nicht mehr aus ImpPlans oder
Roadmap-Stellen rekonstruieren, was 0.7.5 wirklich kann.

Verbindliche Folge:

- `spec/cli-spec.md` enthaelt den finalen `data profile`-Vertrag
- `spec/design.md` beschreibt Profiling 3.6 im real gelieferten Stand
- `spec/architecture.md` spiegelt Modul- und Adapterstruktur korrekt
- `docs/user/guide.md` wird nur aktualisiert, wenn sie fuer Nutzer einen echten
  Mehrwert zum finalen Scope liefert

### 4.7 Abschlussdoku darf keine Scope-Inflation enthalten

Die regulaeren Dokumente sollen kein "Marketing fuer spaeter" sein.

Verbindliche Folge:

- nicht gelieferte spaetere Ziele werden nicht als aktive CLI- oder
  Report-Faehigkeiten formuliert
- Doku nennt keine Flags oder Analysepfade, die 0.7.5 bewusst nicht liefert
- groessere Zukunftsbilder bleiben in Plan- oder Designkontexten klar als
  spaeter markiert

---

## 5. Arbeitspakete

### F.1 Domain- und Kernvertraege final testen

Mindestens noetig:

- `WarningEvaluatorTest`
- Modelltests fuer Profiling-Kernobjekte
- Typ- und Kompatibilitaetsvertragstests

Ziel:

- der Kern ist ohne JDBC und ohne CLI abschliessend abgesichert

### F.2 Service- und Runner-Tests komplettieren

Mindestens noetig:

- `DataProfileRunnerTest`
- `ProfileDatabaseServiceTest`
- `ProfileTableServiceTest`
- Exit-Code-Mapping
- Tabellenfilter
- `topN`
- Fehlerpfade
- deterministische Sortierung

Ziel:

- die Application-Schicht ist ohne echte Datenbank voll unit-testbar

### F.3 SQLite-Integrationspfad haerten

Mindestens noetig:

- echte `:memory:`-DB
- Grundkennzahlen
- Text-/Numerik-/Temporalwerte
- SQLite-spezifische Fallbacks wie `stddev`

Ziel:

- ein schneller, lokal reproduzierbarer Referenzpfad bestaetigt die
  Profiling-Adapter auch ohne Container

### F.4 PostgreSQL- und MySQL-Integrationssmokes aufbauen

Mindestens noetig:

- PostgreSQL via Testcontainers
- MySQL via Testcontainers
- Profiling-Lauf mit repraesentativen Tabellen
- Verifikation dialektspezifischer Kernrisiken

Ziel:

- echte Dialektpfade sind vor Milestone-Abschluss bestaetigt

### F.5 CLI-Round-Trip-Tests fuer `data profile` vervollstaendigen

Mindestens noetig:

- JSON auf stdout
- YAML in Datei
- Tabellenfilter
- `--schema` auf PostgreSQL
- Fehlerpfade
- Named-Connection-Pfad ueber `.d-migrate.yaml`
- Trennung von Root-`--output-format` und kommandoeigenem `--format`
- Uebernahme des Root-`CliContext`, insbesondere `quiet`

Ziel:

- der sichtbare Nutzerpfad funktioniert end-to-end entsprechend dem 0.7.5-
  CLI-Vertrag

### F.6 Determinismus explizit abnehmen

Verpflichtender Scope:

- stabile Tabellenreihenfolge
- stabile Spaltenreihenfolge
- stabile `topValues`-Sortierung
- keine laufzeitvariablen Felder im Default-Report

Ziel:

- identische Eingabedaten fuehren zu diff-freundlichen Reports und reproduzier-
  barem CLI-Verhalten

### F.7 Regulaere Dokumente aktualisieren

Pflichtupdates:

- `spec/cli-spec.md`
  - neuer Abschnitt `data profile`
  - Flags
  - Exit-Codes
- `spec/design.md`
  - Abschnitt 3.6 Daten-Profiling auf finalen 0.7.5-Stand ziehen
- `spec/architecture.md`
  - Modul `hexagon:profiling`
  - Ports, Services, Runner, Writer und neue Adapterpfade abbilden

Optional:

- `docs/user/guide.md`, falls eine kurze Nutzerfuehrung fuer `data profile` echten
  Mehrwert bringt

Ziel:

- die regulaere Projektdoku ist nach dem Milestone die massgebliche
  Nutzerreferenz

### F.8 Abschlussabnahme gegen den echten 0.7.5-Scope

Mindestens noetig:

- Teststatus und Restluecken sichten
- Doku auf Scope-Drift pruefen
- sicherstellen, dass keine spaeteren Features versehentlich als geliefert
  erscheinen

Ziel:

- Milestone 0.7.5 ist fachlich, technisch und dokumentarisch konsistent

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `hexagon/profiling/src/test/kotlin/dev/dmigrate/profiling/...`
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/...`
- `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/profiling/...`
- `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/profiling/...`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/profiling/...`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/...`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/report/...`
- `spec/cli-spec.md`
- `spec/design.md`
- `spec/architecture.md`
- ggf. `docs/user/guide.md`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/planning/implementation-plan-0.7.5.md`
- `docs/planning/ImpPlan-0.7.5-A.md`
- `docs/planning/ImpPlan-0.7.5-B.md`
- `docs/planning/ImpPlan-0.7.5-C.md`
- `docs/planning/ImpPlan-0.7.5-D.md`
- `docs/planning/ImpPlan-0.7.5-E.md`
- `spec/profiling.md`

---

## 7. Akzeptanzkriterien

- [x] `hexagon:profiling` erfuellt den konfigurierten Non-Integration-Kover-
      Gate von mindestens `90`.
- [x] Mindestens ein echter PostgreSQL- und ein echter MySQL-Lauf bestaetigen
      die Adapterpfade.
- [x] Der SQLite-`:memory:`-Pfad ist als schneller Integrations- und
      Smoke-Referenzpfad abgesichert.
- [x] CLI-Round-Trips decken JSON auf stdout, YAML in Datei, Tabellenfilter,
      Named Connections, relevante Fehlerpfade, Root-`CliContext`-
      Uebernahme und die Trennung von Root-`--output-format` gegen
      kommandoeigenes `--format` ab.
- [x] Der Determinismusvertrag ist explizit getestet:
      Tabellenreihenfolge, Spaltenreihenfolge, `topValues`, kein `generatedAt`.
- [x] Die Abschlussdoku beschreibt nur den tatsaechlich gelieferten 0.7.5-
      Scope.
- [x] `spec/cli-spec.md`, `spec/design.md` und `spec/architecture.md` sind auf
      den finalen Profiling-Vertrag aktualisiert.

---

## 8. Risiken

### R1 - Abschlussphase testet nur happy paths

Wenn Phase F nur ein paar grune Beispielpfade bestaetigt, bleiben Exit-Codes,
Determinismus und Dialektgrenzen zu wenig abgesichert.

### R2 - Container-Smokes werden zum Ersatz fuer gezielte Tests

Wenn PostgreSQL-/MySQL-Laeufe alles auffangen sollen, werden Regressionen
schwerer lokalisierbar und langsamer reproduzierbar.

### R3 - Abschlussdoku beschreibt Wunschbild statt Lieferstand

Sobald regulaere Dokumente wieder spaetere Flags, Analysepfade oder
Normalisierungsfeatures implizieren, verliert 0.7.5 einen klaren Nutzervertrag.

### R4 - Determinismus wird nur behauptet, nicht verifiziert

Ohne explizite Tests fuer Reihenfolge, `topValues` und das Fehlen von
`generatedAt` kann der Report-Vertrag bei kleinen Aenderungen unbemerkt kippen.

### R5 - Testabdeckung bleibt in einzelnen Schichten lueckenhaft

Wenn Domain-, Service-, Adapter-, CLI- und Writer-Ebene nicht gemeinsam
abgenommen werden, koennen saubere Unit-Tests eine kaputte End-to-End-Erfahrung
verdecken oder umgekehrt.

---

## 9. Abschluss-Checkliste

- [x] Domain-, Service-, Adapter-, CLI- und Writer-Tests sind fuer 0.7.5
      konsistent abgeschlossen.
- [x] SQLite, PostgreSQL und MySQL bestaetigen den Profiling-Pfad auf
      angemessener Testtiefe.
- [x] Der Determinismusvertrag ist explizit verifiziert.
- [x] Restluecken oder nicht gelieferte spaetere Features sind nicht als
      impliziter Lieferumfang dokumentiert.
- [x] `spec/cli-spec.md`, `spec/design.md` und `spec/architecture.md` sind auf
      finalem 0.7.5-Stand.
- [x] `docs/user/guide.md` ist nur dann angepasst, wenn sie fuer Nutzer echten
      Zusatznutzen bringt.
- [x] Milestone 0.7.5 ist technisch und dokumentarisch konsistent abgeschlossen.
