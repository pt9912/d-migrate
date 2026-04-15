# Implementierungsplan: Phase D - Application-Services und Runner

> **Milestone**: 0.7.5 - Daten-Profiling
> **Phase**: D (Application-Services und Runner)
> **Status**: Implemented (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.7.5.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 5.3, Abschnitt 6 Phase D, Abschnitt 7.1,
> Abschnitt 7.3, Abschnitt 8.2, Abschnitt 9; `docs/ImpPlan-0.7.5-A.md`;
> `docs/ImpPlan-0.7.5-B.md`; `docs/ImpPlan-0.7.5-C.md`;
> `docs/roadmap.md` Milestone 0.7.5; `docs/profiling.md`;
> `docs/design.md` Abschnitt 3.6

---

## 1. Ziel

Phase D setzt den bis Phase C vorbereiteten Profiling-Pfad zu einem
belastbaren Use-Case zusammen. Ergebnis der Phase ist ein Application-Layer,
der reale Verbindungen aufloesen, den passenden Profiling-Adapter-Satz
auswaehlen, Datenbank- und Tabellen-Profiling orchestrieren und Fehler sauber
auf bestehende Exit-Codes mappen kann.

Der Teilplan liefert bewusst noch kein neues CLI-Kommando, keine JSON-/YAML-
Ausgabe und keine Datei- oder stdout-Strategie fuer den finalen Nutzervertrag.
Er schafft die service- und runnerseitige Orchestrierung, auf der Phase E den
produktiven `data profile`-CLI-Pfad aufsetzen kann.

Nach Phase D soll klar und testbar gelten:

- `ProfileTableService` orchestriert Schema-, Aggregat-, Typ- und Warning-Logik
  fuer genau eine Tabelle
- `ProfileDatabaseService` orchestriert Tabellenfilter, stabile Reihenfolge und
  den Mehrtabellenlauf
- `DataProfileRunner` folgt dem bestehenden Runner-Muster aus
  `hexagon:application`
- `NamedConnectionResolver`, `ConnectionUrlParser` und
  `HikariConnectionPoolFactory` werden wiederverwendet statt neu erfunden
- die Profiling-Adapter werden ueber einen injizierten separaten Lookup
  angesprochen und nicht ueber eine Erweiterung von `DatabaseDriver`
- Profiling-Fehler werden ueber eine kleine lokale Fehlerhierarchie sauber auf
  Exit `0`, `2`, `4`, `5` und `7` gemappt
- Phase D fuehrt noch keine CLI-Parsing-, Writer- oder Render-Logik ein

---

## 2. Ausgangslage

Aktueller Stand der Codebasis und der vorigen Phasen:

- Phase A hat fuer 0.7.5 festgezogen:
  - `hexagon:profiling` als eigenes Modul
  - unveraenderten `DatabaseDriver`
  - zentrale spaetere Profiling-Verdrahtung statt verteilter
    `when (dialect)`-Logik
- Phase B definiert den fachlichen Kern fuer:
  - `DatabaseProfile`
  - `TableProfile`
  - `ColumnProfile`
  - `TargetTypeCompatibility`
  - `WarningEvaluator`
- Phase C definiert die Port-Vertraege und den Adapter-Satz pro Dialekt:
  - `SchemaIntrospectionPort`
  - `ProfilingDataPort`
  - `LogicalTypeResolverPort`
- `hexagon:application` enthaelt bereits Runner im Stil:
  - `SchemaReverseRunner`
  - `DataExportRunner`
  - `DataImportRunner`
  Diese Runner sind heute stark konstruktorinjiziert und ohne echte DB
  unit-testbar.
- `hexagon:application` haengt bereits von `hexagon:profiling` ab und kann
  damit Profiling-Services nutzen, ohne neue Modulzyklen einzufuehren.
- `docs/profiling.md` beschreibt fuer den Use-Case bereits:
  - `ProfileTableService`
  - `ProfileDatabaseService`
  - lokale Profiling-Exception-Hierarchie
- `docs/implementation-plan-0.7.5.md` fixiert fuer Phase D:
  - Reuse von `NamedConnectionResolver`, `ConnectionUrlParser`,
    `HikariConnectionPoolFactory`
  - `--schema` nur fuer PostgreSQL
  - Exit-Codes `0`, `2`, `4`, `5`, `7`

Konsequenz fuer Phase D:

- Die Hauptaufgabe ist jetzt nicht mehr Fachmodell oder SQL, sondern saubere
  Orchestrierung ueber Modul- und Fehlergrenzen hinweg.
- Wenn Phase D zu viel in den Runner zieht, wird die spaetere CLI-Schale
  unnoetig schwer testbar.
- Wenn Phase D zu wenig festlegt, muss Phase E die eigentliche Use-Case-Logik
  zwischen Command, Runner und Writer nachholen.

---

## 3. Scope fuer Phase D

### 3.1 In Scope

- Implementierung von `ProfileTableService`
- Implementierung von `ProfileDatabaseService`
- Implementierung von `DataProfileRunner`
- Einfuehrung der noetigen Request-/Options-DTOs fuer den Profiling-Use-Case,
  z. B.:
  - `DatabaseProfilingRequest`
  - `TableProfilingOptions`
  - `DataProfileRequest` in einer bereits Phase-E-kompatiblen Grundform
- Einfuehrung einer kleinen lokalen Fehlerhierarchie fuer Profiling-Laufzeit-
  fehler, z. B.:
  - `ProfilingException`
  - `SchemaIntrospectionError`
  - `ProfilingQueryError`
  - `TypeResolutionError`
- Definition und Nutzung eines injizierten Profiling-Adapter-Lookups, passend
  zum in Phase A vorgesehenen separaten Bootstrap-Vertrag
- Exit-Code-Mapping fuer:
  - `0` Erfolg
  - `2` CLI-/Request-Fehler
  - `4` Verbindungsfehler
  - `5` Profiling-Ausfuehrungsfehler
  - `7` Konfigurations-/URL-/Registry-Fehler
- Unit-Tests fuer Services, Runner und Exit-Code-Pfade

### 3.2 Bewusst nicht Teil von Phase D

- `DataProfileCommand`
- Root-CLI-Verdrahtung in `DataCommands.kt`
- Help-Text, Argument-Parsing und Endnutzer-Flagoberflaeche
- JSON-/YAML-Rendering
- stdout- oder Dateiausgabe des finalen Reports
- Report-Writer wie `ProfileReportWriter`
- Query-Profiling ueber `--query`
- FD-Discovery, `StructuralFinding`, `NormalizationProposal`
- spaetere semantische oder LLM-basierte Analyse

Praezisierung:

Phase D produktiviert den Use-Case und die Fehlersemantik. Die endgueltige
Benutzerschnitt und jede Report-Ausgabe bleiben Aufgabe von Phase E.

Dabei gilt fuer den Request-Vertrag:

- Phase D darf `DataProfileRequest` bereits in einer Form einfuehren, die
  den in Masterplan 0.7.5 vorgeschlagenen spaeteren CLI-Feldern nicht
  widerspricht, z. B. `format`, `output` und `quiet`
- semantisch ausgewertet werden in Phase D aber nur die Use-Case-relevanten
  Eingaben:
  - `source`
  - `tables`
  - `schema`
  - `topN`
- Felder fuer Ausgabe- oder CLI-Darstellung bleiben bis Phase E reine
  pass-through-/Reservefelder oder werden noch gar nicht eingefuehrt; beides
  ist zulaessig, solange Phase D keinen Writer-/Render-Vertrag vorwegnimmt

---

## 4. Leitentscheidungen fuer Phase D

### 4.1 Fachnahe Orchestrierung bleibt in Services, nicht im Runner

Phase D fuehrt zwei klar getrennte Services ein:

- `ProfileTableService` fuer die Orchestrierung einer einzelnen Tabelle
- `ProfileDatabaseService` fuer Tabellenliste, Filter und Gesamtprofil

Verbindliche Folge:

- der Runner orchestriert Verbindung, Lookup und Exit-Codes
- fachliche Zusammensetzung von `TableProfile` und `DatabaseProfile` bleibt in
  Services
- spaetere CLI-Schichten muessen keine Profiling-Fachlogik nachbauen
- falls `DatabaseProfile.generatedAt` im Phase-B-Modell enthalten ist, wird es
  in den Services und nicht im Runner befuellt

### 4.2 `ProfileTableService` ist der zentrale Einzeltabellen-Use-Case

`ProfileTableService` ist die kleinste produktive Profiling-Einheit.

Verbindliche Folge:

- die Service-Logik laedt zuerst die noetige Tabellen-/Spalten-Metadatenbasis
- sie profiliert dann Spalte fuer Spalte ueber `ProfilingDataPort`
- sie loest `LogicalType` ueber `LogicalTypeResolverPort` auf
- sie erzeugt Warnungen ueber `WarningEvaluator`
- sie baut daraus ein vollstaendiges `TableProfile`

### 4.3 `ProfileDatabaseService` delegiert strikt an `ProfileTableService`

Die Datenbankprofilierung soll kein zweiter unabhaengiger Fachpfad werden.

Verbindliche Folge:

- `ProfileDatabaseService` bestimmt die zu profilierenden Tabellen
- Tabellenfilter und Reihenfolge werden dort einmalig entschieden
- die eigentliche Tabellenprofilierung erfolgt fuer jede Tabelle ueber
  `ProfileTableService`
- Mehrtabellen- und Einzeltabellenpfad bleiben fachlich konsistent
- `DatabaseProfile.generatedAt` wird dort ueber eine injizierbare `Clock`
  gesetzt, damit der Use-Case auch mit Zeitmetadatum voll testbar und
  reproduzierbar bleibt

### 4.4 `DataProfileRunner` bleibt ein duennes Application-Seam

Der Runner soll dem Stil von `SchemaReverseRunner` und `DataExportRunner`
folgen: konstruktorinjiziert, unit-testbar und ohne harte Abhaengigkeit auf die
CLI-Schale.

Verbindliche Folge:

- der Runner loest die Source-Referenz auf
- er parst die Connection-URL
- er erstellt den Connection-Pool
- er zieht ueber den Dialekt den passenden Profiling-Adapter-Satz
- er ruft den Datenbank-Service auf
- er mappt Fehler auf Exit-Codes
- er uebernimmt keine Argument-Parsing- oder Report-Serialisierung

### 4.5 Profiling-Adapter werden ueber separaten Lookup injiziert

Phase A hat die Zyklusfalle bereits ausgeschlossen: kein neuer Zugriff ueber
`DatabaseDriver`.

Verbindliche Folge:

- `DataProfileRunner` bekommt einen injizierten
  `(DatabaseDialect) -> ProfilingAdapterSet`-Lookup
- fehlende Registry-/Lookup-Eintraege fuehren zu Exit `7`
- es gibt in Phase D keine ad-hoc `when (dialect)`-Verdrahtung im Runner
- die spaetere konkrete Bootstrap-Stelle bleibt zentral und ausserhalb der
  fachlichen Orchestrierung

### 4.6 `--schema` bleibt fachlich ein Request-Vertrag, noch kein CLI-Thema

Phase E wird das Flag einfuehren; Phase D muss seine Semantik bereits korrekt
durchsetzen.

Verbindliche Folge:

- der Profiling-Request kann ein optionales `schema` tragen
- fuer PostgreSQL wird `schema` produktiv unterstuetzt
- fuer MySQL und SQLite ist ein explizit gesetztes `schema` ein Request-Fehler
- dieser Fall liefert Exit `2`, nicht stilles Ignorieren

### 4.7 Fehler werden lokal uebersetzt und erst im Runner auf Exit-Codes gemappt

Die Services sollen keine Exit-Codes kennen, aber technische Fehler auch nicht
roh bis in spaetere CLI-Schichten durchreichen.

Verbindliche Folge:

- Adapter- oder JDBC-nahe Fehler werden in lokale `ProfilingException`-
  Untertypen uebersetzt
- `ProfileTableService` und `ProfileDatabaseService` arbeiten gegen diese
  Profiling-Fehler
- `DataProfileRunner` mappt sie auf die bestehenden Prozess-Exit-Codes
- URL-, Config- und Registry-Fehler bleiben von fachlichen Profiling-Fehlern
  getrennt

### 4.8 Der Use-Case bleibt deterministisch in Reihenfolge und Filterung

Phase E soll spaeter diff-freundliche Reports schreiben koennen. Phase D darf
hier keine instabile Vorarbeit liefern.

Verbindliche Folge:

- Tabellenfilter werden einmalig und deterministisch aufgeloest
- die Reihenfolge profilierter Tabellen bleibt stabil
- Services fuehren keine nicht deterministische Parallelisierung oder
  HashMap-basierte Zufallsreihenfolge ein
- falls `DatabaseProfile.generatedAt` in den Phase-D-Outputs gefuellt wird,
  erfolgt dies ausschliesslich ueber eine injizierte `Clock`; Tests nutzen
  `Clock.fixed(...)`

---

## 5. Arbeitspakete

### D.1 Use-Case-Requests und Options-DTOs festziehen

Mindestens noetig:

- ein fachnaher Request fuer Datenbankprofilierung
- Optionen fuer Einzeltabellenprofilierung
- ein Runner-Request fuer den Application-Layer
- klare Trennung zwischen bereits vorbereiteten Phase-E-Feldern und den in
  Phase D tatsaechlich ausgewerteten Request-Feldern

Ziel:

- Services und Runner arbeiten gegen explizite, testbare Eingabevertraege statt
  losem Parameterdurchreichen

### D.2 `ProfileTableService` implementieren

Mindestens noetig:

- Laden der Tabellen-/Spalten-Metadaten
- Profilierung der enthaltenen Spalten
- Aufloesung des `LogicalType`
- Anwendung des `WarningEvaluator`
- Aufbau eines `TableProfile`

Ziel:

- eine Tabelle kann vollstaendig ueber den Phase-B- und Phase-C-Vertrag
  profiliert werden

### D.3 `ProfileDatabaseService` implementieren

Mindestens noetig:

- Tabellenliste aus der Introspection bestimmen
- optionalen Tabellenfilter anwenden
- optionales `schema` beruecksichtigen
- pro Tabelle `ProfileTableService` delegieren
- Aufbau eines stabil sortierten `DatabaseProfile`
- `generatedAt` bei Bedarf ueber eine injizierbare `Clock` setzen

Ziel:

- der Mehrtabellenpfad ist nur eine definierte Erweiterung des
  Einzeltabellenpfads

### D.4 Profiling-Fehlerhierarchie einfuehren

Mindestens noetig:

- `ProfilingException`
- `SchemaIntrospectionError`
- `ProfilingQueryError`
- `TypeResolutionError`

Ziel:

- technische Fehler sind fuer den Runner unterscheidbar, ohne rohe
  Adapterdetails nach oben zu leaken

### D.5 `DataProfileRunner` im bestehenden Runner-Stil implementieren

Mindestens noetig:

- Source ueber `NamedConnectionResolver`-Vertrag aufloesen
- URL ueber `ConnectionUrlParser` parsen
- Pool ueber `HikariConnectionPoolFactory`-Vertrag erzeugen
- Profiling-Adapter-Satz ueber Dialekt-Lookup holen
- `ProfileDatabaseService` ausfuehren
- Fehler und Validierungen auf Exit-Codes mappen

Ziel:

- ein voll unit-testbarer Application-Entry-Point fuer den Profiling-Use-Case

### D.6 Request-Validierung fuer Tabellenfilter, `topN` und `schema` umsetzen

Verpflichtender Scope:

- `topN` muss positiv und sinnvoll begrenzt sein
- Tabellenfilter muessen deterministisch weitergereicht werden
- `schema` ist nur fuer PostgreSQL erlaubt

Ziel:

- Phase E kann spaeter CLI-Flags auf einen bereits stabilen Request-Vertrag
  abbilden

### D.7 Service- und Runner-Tests aufbauen

Mindestens erforderlich:

- `ProfileTableServiceTest`
- `ProfileDatabaseServiceTest`
- `DataProfileRunnerTest`
- Tests fuer jeden Exit-Code-Zweig
- Tests fuer `schema`-Verhalten pro Dialekt
- Tests fuer deterministische Tabellenreihenfolge
- Tests fuer deterministisches `generatedAt` via `Clock.fixed(...)`, falls das
  Feld in Phase D bereits befuellt wird

Ziel:

- Phase D ist ohne echte Datenbank belastbar abgesichert, bevor Phase E Output
  und CLI darueberlegt

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/service/...`
- `hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/...`
- `hexagon/profiling/src/test/kotlin/dev/dmigrate/profiling/...`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileRunner.kt`
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/DataProfileRunnerTest.kt`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/implementation-plan-0.7.5.md`
- `docs/ImpPlan-0.7.5-A.md`
- `docs/ImpPlan-0.7.5-B.md`
- `docs/ImpPlan-0.7.5-C.md`
- `docs/profiling.md`

---

## 7. Akzeptanzkriterien

- [x] `ProfileTableService` und `ProfileDatabaseService` bilden einen stabilen
      produktiven Profiling-Use-Case ueber den Phase-B- und Phase-C-Vertrag.
- [x] `DataProfileRunner` ist ohne echte Datenbank voll unit-testbar.
- [x] `NamedConnectionResolver`, `ConnectionUrlParser` und
      `HikariConnectionPoolFactory` werden wiederverwendet statt dupliziert.
- [x] Die Lookup-Strategie fuer Profiling-Adapter bleibt getrennt von
      `DatabaseDriver` und auf einen injizierten separaten Vertrag begrenzt.
- [x] `ProfileDatabaseService` bleibt deterministisch und unit-testbar
      (`DatabaseProfile.generatedAt` wurde in Phase B entfernt).
- [x] Saemtliche Exit-Code-Zweige `0`, `2`, `4`, `5`, `7` sind ueber Tests
      abgedeckt.
- [x] Ein explizites `schema` auf MySQL oder SQLite fuehrt zu Exit `2`.
- [x] Der Request-Vertrag ist gegen Phase E klar abgegrenzt:
      Ausgabe-/CLI-Felder wie `format`, `output` oder `quiet` werden in
      Phase D entweder noch nicht eingefuehrt oder noch nicht semantisch
      ausgewertet.
- [x] Phase D fuehrt keine CLI-Parsing-, Help-, Writer- oder Report-
      Serialisierungslogik ein.

---

## 8. Risiken

### R1 - Der Runner zieht zu viel Fachlogik an sich

Wenn `DataProfileRunner` Tabellenprofilierung, Warnungslogik oder
Profilzusammenbau direkt selbst macht, wird der Use-Case spaeter schwer testbar
und unnoetig an die CLI-Naehe gekoppelt.

### R2 - Services und Runner teilen die Verantwortung unsauber

Wenn Tabellenfilter, Reihenfolge oder `schema`-Semantik teils im Service und
teils im Runner leben, entstehen doppelte Regeln und abweichende Fehlerpfade.

### R3 - Der Adapter-Lookup rutscht doch wieder in `DatabaseDriver`

Sobald Phase D den Profiling-Zugriff "bequem" an den bestehenden Driver-Facade
haengt, ist die in Phase A gezogene Modulgrenze faktisch wieder aufgeweicht.

### R4 - Fehlerklassen bleiben zu technisch oder zu grob

Wenn rohe JDBC-/Adapter-Exceptions direkt bis zum Runner laufen oder alles nur
in einem generischen `RuntimeException` endet, wird Exit-Code-Mapping spaeter
instabil und schwer testbar.

### R5 - Phase D zieht bereits Phase-E-Verantwortung vor

Sobald Runner oder Services schon JSON/YAML, stdout oder Dateipfade kennen,
verwischen Use-Case und Ausgabeschicht vorzeitig.

---

## 9. Abschluss-Checkliste

- [x] Die Profiling-Services sind implementiert und klar vom Runner getrennt.
- [x] `DataProfileRunner` folgt dem bestehenden `hexagon:application`-Muster.
- [x] Source-Aufloesung, URL-Parsing und Pool-Erzeugung werden ueber die
      bestehenden Infrastrukturbausteine wiederverwendet.
- [x] Der Profiling-Adapter-Zugriff erfolgt ueber einen separaten Lookup und
      nicht ueber `DatabaseDriver`.
- [x] `schema` ist fachlich nur fuer PostgreSQL erlaubt und fuer MySQL/SQLite
      ein sauberer Exit-2-Fall.
- [x] Alle Exit-Code-Zweige sind unit-testbar und getestet.
- [x] Phase D bleibt frei von Help-, CLI-Parsing-, Writer- und
      Report-Serialisierungsimplementierung.
