# Implementierungsplan: Milestone 0.6.0 - Reverse-Engineering und Direkttransfer

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer Milestone
> 0.6.0. Es dient als laufend gepflegte Spezifikation und Review-Grundlage
> waehrend der Umsetzung.
>
> Status: Draft
> Referenzen: `docs/planning/roadmap.md` Milestone 0.6.0,
> `spec/connection-config-spec.md`, `spec/cli-spec.md` Abschnitt
> `schema reverse` / `schema compare`, `spec/neutral-model-spec.md`,
> `spec/architecture.md`, `spec/design.md`.

---

## 1. Ziel

Milestone 0.6.0 macht den Read-Pfad gegen bestehende Datenbanken produktiv.
Nach 0.5.5 ist das neutrale Typsystem bewusst vorgezogen und generatorseitig
abgesichert; 0.6.0 soll dieses Modell nun aus Live-Datenbanken befuellen, statt
weiter nur dateibasierte Schemas zu verarbeiten.

0.6.0 liefert:

- einen echten `SchemaReader` fuer PostgreSQL, MySQL und SQLite
- das CLI-Kommando `d-migrate schema reverse --source <url>`
- einen getrennten Schema-/Report-Vertrag fuer Reverse-Ausgaben
- die Vervollstaendigung von `schema compare` fuer Datei- und DB-Operanden
- einen `data transfer`-Pfad fuer DB-zu-DB-Streaming ohne Zwischenformat
- eine gemeinsame JDBC-Metadatenbasis fuer spaetere Profiling-Arbeit ab 0.7.5
- eine klare Modellierungsstrategie fuer reverse-relevante
  Dialektbesonderheiten
- Tests und Smokes fuer Reverse, Compare und Transfer gegen reale Dialekte

Das Ergebnis von 0.6.0 ist kein weiterer Generator-Milestone, sondern der
Schritt von "wir koennen ein neutrales Schema beschreiben" zu
"wir koennen bestehende Systeme lesen, vergleichen und direkt uebertragen".

---

## 2. Ausgangslage

Stand nach 0.5.x im aktuellen Code:

- `schema validate`, `schema generate`, `schema compare`, `data export` und
  `data import` existieren bereits.
- Das Typfundament fuer `uuid`, `json`, `binary`, `array` und `geometry`
  ist vorhanden; 0.6.0 muss also kein neues Typsystem parallel zum
  Reverse-Pfad erfinden.
- `DatabaseDriver` ist als gemeinsame Treiber-Fassade bereits eingefuehrt und
  kapselt heute `ddlGenerator()`, `dataReader()`, `tableLister()`,
  `dataWriter()` und `urlBuilder()`.
- Ein `SchemaReader`-Port existiert im realen Code noch nicht.
- `TableLister` ist bewusst ein Zwischenport fuer Auto-Discovery im Export und
  verweist selbst bereits auf seine spaetere Ablosung durch `SchemaReader`.
- `ConnectionUrlParser`, Named Connections, Connection Pools, Driver-Registry,
  Progress-Reporting und die Streaming-Bausteine fuer Export/Import sind
  vorhanden und koennen wiederverwendet werden.
- `schema compare` ist aktuell ein file-based MVP-Slice. Der Runner liest zwei
  Schema-Dateien, validiert sie und diffed anschliessend das neutrale Modell.
- Der aktuelle `SchemaComparator` deckt nur einen Teil des Modells ab:
  Schema-Metadaten, Tabellen, Enum-Custom-Types und Views. Sequenzen,
  Routinen, Trigger, nicht-Enum-Custom-Types und zusaetzliche
  Dialektmetadaten sind noch nicht Teil des Diff-Vertrags.
- `schema reverse` ist in `spec/cli-spec.md` und
  `spec/neutral-model-spec.md` bereits als zukuenftiges Kommando beschrieben,
  aber noch nicht implementiert. Die Doku nennt dabei teils `url|path` bzw.
  DDL-von-stdin, obwohl es dafuer heute weder einen SQL-Parser noch einen
  konsolidierten Scope-Beschluss gibt.
- `data transfer` ist in der Roadmap fuer 0.6.0 vorgesehen, besitzt aber noch
  keinen CLI- oder Application-Pfad.

0.6.0 startet damit nicht auf der gruenen Wiese. Die groessten Luecken liegen
nicht bei Connection- oder Streaming-Grundlagen, sondern bei:

- Reverse-Port und Metadatenprojektion
- Modell-/Diff-Abdeckung ueber Tabellen hinaus
- CLI-Vertraegen fuer DB-Operanden
- direkter Orchestrierung von Reader zu Writer

---

## 3. Scope

### 3.1 In Scope fuer 0.6.0

- JDBC-basiertes Reverse-Engineering fuer PostgreSQL, MySQL und SQLite
- `schema reverse` fuer Live-Datenbanken ueber `--source <url>`
- YAML- und JSON-Ausgabe fuer reverse-generierte Schemas
- separater strukturierter Reverse-Report neben der Schema-Ausgabe
- Reverse von:
  - Tabellen und Spalten
  - Primary Keys, Foreign Keys, Constraints und Indizes
  - Sequenzen
  - Custom Types
  - Views
  - optional Procedures / Functions / Triggers
  - 0.5.5-Typen inklusive `geometry`
- modellseitige Erweiterungen, falls sie fuer einen stabilen Reverse-,
  Compare- oder Round-Trip-Vertrag fachlich erforderlich sind
- `schema compare` fuer `file/file`, `file/db` und `db/db`
- Erweiterung des Diff-Modells auf die von 0.6.0 gelesenen Objekttypen
- `data transfer` als DB-zu-DB-Streaming-Orchestrator ohne Zwischenformat
- target-autoritatives Preflight fuer `data transfer` vor dem ersten Write
- automatische Tabellenreihenfolge auf Basis der gelesenen FK-Relationen
- Wiederverwendung bestehender Flags und Semantiken aus Export/Import, soweit
  sie fuer DB-zu-DB-Transfer passen
- Dokumentationsabgleich fuer CLI, Architektur und neutrales Modell

### 3.2 Bewusst nicht Teil von 0.6.0

- generischer SQL-Parser fuer `schema reverse --source <path>` oder stdin-DDL
- `schema migrate` und `schema rollback`
- Ausfuehrung generierter Migrationen gegen Live-Datenbanken
- neue Dialekte jenseits PostgreSQL, MySQL und SQLite
- Datenmaskierung und selektive Datensatzprojektionen aus 1.4.0
- Profiling-Reports und Rule-Engine aus 0.7.5
- automatische Installation von PostGIS, SpatiaLite oder anderen Extensions
- Vollabdeckung exotischer, dialektspezifischer Detailobjekte ohne klare
  Modell- oder Compare-Relevanz

Wichtig: 0.6.0 ist ein Live-DB-Milestone. File-basierte SQL-Rueckwaertsparser
waeren ein separater Problemraum und werden hier bewusst nicht heimlich
mitgeschleppt.

---

## 4. Leitentscheidungen

### 4.1 `schema reverse` ist in 0.6.0 Live-DB-first

Der aktuelle Doku-Stand nennt `--source <url|path>` und sogar stdin-DDL. Das
passt weder zum vorhandenen Code noch zur Roadmap-Intention dieses Milestones.

Verbindliche Entscheidung fuer 0.6.0:

- `schema reverse` arbeitet gegen echte Datenbankverbindungen
- `--source` ist in 0.6.0 eine DB-URL oder ein Named-Connection-Alias, der auf
  eine DB-URL aufgeloest wird
- SQL-Dateien, stdin-DDL und `--source-dialect` fuer Parser-Pfade gehoeren
  nicht in den 0.6.0-Mindestvertrag

Falls spaeter ein SQL-Parser kommt, wird das als eigener additiver
Funktionsschnitt behandelt und nicht rueckwirkend in 0.6.0 hineinerzaehlt.

### 4.2 `SchemaReader` wird in die bestehende Driver-Fassade integriert

0.6.0 fuehrt keinen zweiten parallelen Lookup-Unterbau ein. Der vorhandene
`DatabaseDriver` ist bereits der zentrale Treiber-Einstiegspunkt und wird um
den neuen Read-Port erweitert.

Verbindliche Konsequenz:

- `DatabaseDriver` bekommt `schemaReader()`
- jeder Built-in-Driver registriert genau eine `SchemaReader`-Implementierung
- `TableLister` bleibt fuer bestehende Export-Pfade zunaechst erhalten, kann
  intern aber auf dieselbe Metadatenbasis umgestellt werden

0.6.0 baut also auf dem realen Ist-Code auf, nicht auf einem veralteten
"eigentlich wollten wir mal ein anderes Driver-Interface haben"-Narrativ.

### 4.3 Reverse braucht einen Ergebnisvertrag, nicht nur `SchemaDefinition`

Live-Reverse wird auf Faelle treffen, die:

- nur teilweise in das neutrale Modell passen,
- bewusst ausgelassen werden,
- oder mit Hinweisen/Warnungen verbunden sind.

Deshalb soll `SchemaReader` kein nacktes `SchemaDefinition` zurueckgeben,
sondern ein Ergebnisobjekt mit mindestens:

- dem gelesenen `schema`
- strukturierten `notes`
- optional `skipped_objects`, falls ganze Objekte bewusst nicht transportiert
  werden

Fuer den CLI-Vertrag von `schema reverse` gilt dabei verbindlich:

- `--output` enthaelt immer ein reines Schema-Dokument, das als
  `SchemaDefinition` wieder eingelesen werden kann
- Reverse-Notes werden nicht in die Schema-Datei eingebettet
- Notes und bewusst uebersprungene Objekte erscheinen menschenlesbar auf
  `stderr` und strukturiert in einem separaten Report-Artefakt
- der Reverse-Report folgt bevorzugt dem bestehenden Sidecar-Muster
  (`--report`, Default `<output>.report.yaml`) statt einem implizit
  vermischten Payload

Ob dafuer bestehende Note-Typen aus dem Generator wiederverwendet oder ein
gleichwertiger Reverse-spezifischer Vertrag eingefuehrt wird, ist
Implementierungsdetail. Nicht akzeptabel ist stiller Metadatenverlust ohne
sichtbaren Report-Pfad.

### 4.4 `schema compare` bleibt modellbasiert

Auch mit DB-Operanden wird nicht SQL-Text gegeneinander diffed. Der Ablauf
bleibt:

1. Quelle und Ziel zu `SchemaDefinition` aufloesen
2. optional validieren bzw. Reverse-Notes auswerten
3. strukturellen Diff auf dem neutralen Modell bilden

Damit bleiben Compare-Semantik, Exit-Codes und Testbarkeit stabil. 0.6.0
erweitert den Eingabepfad, nicht das Grundprinzip.

### 4.5 `data transfer` komponiert bestehende Reader-/Writer-Bausteine

Fuer DB-zu-DB-Transfer wird kein Export-in-Datei-und-dann-Import-aus-Datei
durch die Hintertuer gebaut. Der neue Pfad soll:

- `DataReader` und `DataWriter` direkt verbinden
- Connection-Management und Driver-Registry wiederverwenden
- vorhandene Import-/Export-Optionen soweit moeglich uebernehmen
- Fortschritts- und Exit-Code-Vertraege an den heutigen Datenkommandos
  ausrichten
- vor dem ersten Write ein target-autoritatives Preflight fuer
  Tabellen-/Spaltenkompatibilitaet ausfuehren

Neue Logik entsteht nur dort, wo 0.6.0 wirklich Neues braucht:

- automatische Tabellenreihenfolge
- gleichzeitige Koordination von Source und Target
- DB-zu-DB-spezifische Validierungsregeln

Verbindliche Konsequenz fuer 0.6.0:

- `data transfer` liest vor dem Streaming sowohl Source- als auch Target-Schema,
  soweit das fuer Auswahl, Kompatibilitaet und Reihenfolge noetig ist
- das Zielschema ist fuer Schreibkompatibilitaet autoritativ
- FK-Zyklen schlagen im Preflight fehl, sofern kein expliziter und
  dialektsicherer Bypass aktiv ist

### 4.6 Kein freier Metadaten-Sack im Modell

0.6.0 wird dialektspezifische Metadaten sehen, die heute noch kein Zuhause im
neutralen Modell haben. Die Antwort darauf ist **kein** unstrukturierter
`Map<String, Any>`-Anhang.

Stattdessen gilt:

- Metadaten mit echter Compare-, Round-Trip- oder Transfer-Relevanz bekommen
  explizite Modellfelder
- reine Herkunfts- oder Ausfuehrungsdetails bleiben strukturierte Reverse-Notes

Explizit zu pruefen fuer 0.6.0:

- PostgreSQL-Extensions
- MySQL-Tabellen-Engine
- SQLite-spezifische Tabellenattribute wie `WITHOUT ROWID` oder Virtual Tables

Die Modellierungsentscheidung fuer diese Faelle muss in Phase A/B bewusst
getroffen werden; sie darf nicht implizit in den Drivern auseinanderlaufen.

### 4.7 Routinen, Views und Trigger bleiben dialektgebunden

0.6.0 soll diese Objekte lesen koennen, aber nicht so tun, als waeren ihre
Bodies ploetzlich portable. Deshalb gilt weiter:

- Body-/Query-Text wird gelesen und im neutralen Modell abgelegt
- `sourceDialect` bleibt erhalten bzw. wird gesetzt
- Compare arbeitet strukturell gegen diese Repraesentation
- `data transfer` kopiert keine Routine- oder View-Definitionen implizit mit

Der Milestone liest und vergleicht solche Objekte; er fuehrt keine automatische
Dialekttransformation fuer sie ein.

---

## 5. Geplante Arbeitspakete

### Phase A - Spezifikationsbereinigung und Scope-Fixierung

1. `spec/cli-spec.md` fuer `schema reverse` auf den 0.6.0-Live-DB-Vertrag
   ziehen:
   - `--source <url>` statt `url|path`
   - keine stdin-DDL im Mindestumfang
   - `--output` = reines Schema, `--report` = strukturierter Reverse-Report
   - klare Include-Flags fuer optionale Objekte
   - Exit-Code-Vertrag fuer Connection-, Reverse- und I/O-Fehler
2. `spec/cli-spec.md` um `data transfer` erweitern.
3. `spec/cli-spec.md` fuer `schema compare` von file-only auf
   `file/db`- und `db/db`-Vergleich erweitern.
4. `spec/neutral-model-spec.md` fuer die reverse-relevanten Metadatenluecken
   abgleichen.
5. `spec/architecture.md` auf die reale Port-Einfuehrung ueber
   `DatabaseDriver.schemaReader()` und die gemeinsame JDBC-Metadatenbasis
   ausrichten.

Ziel von Phase A: Vor der Code-Umsetzung ist klar, was 0.6.0 wirklich liefert
und welche Doku-Aussagen aus frueheren Entwurfsstaenden explizit verworfen
werden.

### Phase B - Reverse-Vertrag und Modellanpassungen

Ziel: Das neutrale Modell und der Port-Vertrag koennen Reverse-Ergebnisse
stabil transportieren.

Betroffene Module:

- `hexagon:core`
- `hexagon:ports`

Arbeitspunkte:

1. `SchemaReader`-Port im Ports-Modul einfuehren.
2. Ergebnisobjekt fuer Reverse einfuehren, mindestens mit `schema` und
   strukturierten Hinweisen.
3. Optionen fuer Reverse definieren, z. B. Include-Flags fuer
   Views/Procedures/Functions/Triggers.
4. Modellluecken fuer 0.6.0 bewusst schliessen, falls sie fuer Reverse-,
   Compare- oder Round-Trip-Faelle fachlich notwendig sind.
5. Reverse- und Compare-relevante Metadaten nicht nur fuer Enums, sondern fuer
   weitere Custom Types, Sequenzen und optionale DB-Objekte modellseitig
   absichern.
6. Validierungsregeln pruefen: Reverse-generierte Modelle duerfen nicht an
   vermeidbaren Self-Inflicted-Widerspruechen scheitern.

Wichtig:

- 0.6.0 soll keine generische "lose Metadata"-Escape-Hatch einfuehren.
- Modellanpassungen muessen mit `schema generate` und `schema compare`
  zusammengedacht werden, nicht nur mit dem Read-Pfad.

### Phase C - Schema-I/O, Reverse-Reports und gemeinsame JDBC-Metadatenbasis

Ziel: Reverse besitzt einen belastbaren Schema-I/O-Vertrag, und Reverse sowie
spaeteres Profiling bauen auf denselben dialektspezifischen
Metadatenbausteinen auf, ohne den kompletten Code doppelt zu schreiben.

Betroffene Module:

- `adapters:driven:formats`
- `adapters:driven:driver-common`
- `adapters:driven:driver-postgresql`
- `adapters:driven:driver-mysql`
- `adapters:driven:driver-sqlite`

Arbeitspunkte:

1. `YamlSchemaCodec.write(...)` produktiv machen.
2. JSON-Schema-Ausgabe ueber einen klaren Formats-Pfad einfuehren, entweder via
   eigenem JSON-Codec oder gleichwertig expliziter Serializer-Komponente.
3. Den Datei-I/O-Vertrag fuer Schema-Kommandos so schaerfen, dass
   reverse-erzeugte YAML-/JSON-Schemas von den file-based Pfaden wieder
   konsumiert werden koennen.
4. Reverse-Report-Writer bzw. Sidecar-Format fuer Notes und `skipped_objects`
   einfuehren.
5. Gemeinsame Basisklassen / Helper fuer JDBC-Metadaten-Abfragen einfuehren,
   wo das fachlich wirklich geteilt werden kann.
6. Projektionen fuer Tabellen, Spalten, Keys, Indizes und weitere
   Schemaobjekte so strukturieren, dass Driver-spezifische Details nicht in
   String-Geraetsel im Runner ausarten.
7. Connection-Ownership am bestehenden Pool-/Driver-Modell ausrichten.
8. `TableLister` nach Moeglichkeit auf dieselbe Query-Basis umstellen, ohne den
   0.3.0-/0.4.0-Vertrag zu brechen.
9. Die gemeinsame Basis so schneiden, dass sie spaeter fuer 0.7.5
   `SchemaIntrospectionPort` wiederverwendbar bleibt.

Wichtig:

- `schema reverse` braucht nicht nur Read-Mapping, sondern auch verlaessliche
  Schema-Serialisierung.
- JDBC-Metadaten allein reichen je nach Dialekt nicht fuer alles. Dialektmodule
  duerfen gezielte Zusatzqueries verwenden.
- Die geteilte Infrastruktur darf Driver-Besonderheiten nicht plattbuegeln.

### Phase D - Dialektimplementierung fuer `SchemaReader`

Ziel: Alle drei unterstuetzten Dialekte liefern belastbare Reverse-Ergebnisse.

#### D.1 PostgreSQL

Betroffene Module:

- `adapters:driven:driver-postgresql`

Arbeitspunkte:

1. Tabellen, Spalten, Constraints, Indizes und Sequenzen lesen.
2. `uuid`, `json/jsonb`, `array`, `binary/xml` und `geometry` sauber ins
   neutrale Modell ueberfuehren.
3. Enum-, Domain- und Composite-Types lesen.
4. Views, Functions, Procedures und Triggers optional lesen.
5. Partitionierungs- und Extension-Metadaten gemaess Phase-B-Entscheidung
   modellieren oder als strukturierte Notes ausgeben.

#### D.2 MySQL

Betroffene Module:

- `adapters:driven:driver-mysql`

Arbeitspunkte:

1. Tabellen, Spalten, Constraints und Indizes lesen.
2. `AUTO_INCREMENT`, `json`, `enum`, Spatial-Typen und weitere
   neutral-relevante Typen korrekt rueckfuehren.
3. Tabellen-Engine und weitere MySQL-spezifische Metadaten gemaess
   Modellentscheidung transportieren.
4. Views, Procedures, Functions und Triggers optional lesen.
5. SET-/weitere nicht exakt neutrale Faelle nicht still verschlucken.

#### D.3 SQLite

Betroffene Module:

- `adapters:driven:driver-sqlite`

Arbeitspunkte:

1. Tabellen, Spalten, Indizes und Constraints aus `sqlite_master` / PRAGMA-Pfad
   lesen.
2. `WITHOUT ROWID`, Virtual Tables und SpatiaLite-Faelle gemaess
   Modellentscheidung behandeln.
3. Views und Triggers optional lesen.
4. SQLite-Typaffinitaet nicht mit fester nativer Typsemantik verwechseln;
   Reverse-Regeln muessen ehrlich und testbar bleiben.

### Phase E - CLI-Pfad `schema reverse`

Ziel: Reverse ist als eigenstaendiges CLI-Kommando benutzbar und skriptfaehig.

Betroffene Module:

- `hexagon:application`
- `adapters:driving:cli`

Arbeitspunkte:

1. `SchemaReverseRunner` analog zu den bestehenden Runnern einfuehren.
2. `SchemaCommands.kt` um `reverse` erweitern.
3. Source-Aufloesung ueber Named Connections und `ConnectionUrlParser`
   wiederverwenden.
4. Ausgabe als YAML und JSON unterstuetzen.
5. `--report` plus Default-Sidecar-Pfad fuer Reverse-Notes einfuehren.
6. Include-Flags fuer optionale Objekte verdrahten.
7. Trennung sauber halten:
   - `--output` = reines Schema
   - `--report` = strukturierte Reverse-Notes / `skipped_objects`
8. Fehler- und Exit-Code-Vertrag festziehen:
   - `0` Erfolg
   - `2` CLI-Validierungsfehler
   - `4` Connection- bzw. DB-Metadatenfehler
   - `7` Config-/URL-/I/O-Fehler
9. Strukturierte Reverse-Notes auf stderr und im Report
   konsistent transportieren.

### Phase F - `schema compare` fuer DB-Operanden vervollstaendigen

Ziel: LF-015 wird von file-only auf Umgebungs- und DB-Vergleich erweitert.

Betroffene Module:

- `hexagon:core`
- `hexagon:application`
- `adapters:driving:cli`

Arbeitspunkte:

1. Operandenmodell fuer Compare abstrahieren: Datei oder Datenbankquelle.
2. `SchemaCompareRunner` so erweitern, dass beide Seiten symmetrisch ueber
   Datei oder `SchemaReader` aufgeloest werden koennen.
3. Diff-Modell und `SchemaComparator` auf die von 0.6.0 gelesenen Objekte
   erweitern:
   - weitere Custom Types
   - Sequenzen
   - Functions / Procedures / Triggers
   - neue 0.6.0-Metadatenfelder
4. Exit-Codes fuer Compare beibehalten und um DB-Fehlerpfade sauber ergaenzen.
5. Plain-/JSON-/YAML-Ausgabe fuer die erweiterten Diff-Faelle vervollstaendigen.

Wichtig:

- Der bisherige file-based Compare-Pfad darf nicht regressieren.
- DB-/Umgebungsvergleich ist in 0.6.0 eine Erweiterung, kein Rewrite.

### Phase G - `data transfer` ohne Zwischenformat

Ziel: Daten koennen direkt von DB zu DB gestreamt werden.

Betroffene Module:

- `hexagon:application`
- `adapters:driven:streaming`
- `adapters:driving:cli`

Arbeitspunkte:

1. `DataTransferRunner` als eigenen Application-Pfad einfuehren.
2. Source- und Target-URL-Aufloesung analog zu Export/Import wiederverwenden.
3. Source- und Target-Schema vor dem Streaming lesen, soweit das fuer
   Tabellenwahl, Reihenfolge und Zielvalidierung erforderlich ist.
4. `DataReader` der Quelle direkt mit `DataWriter` des Ziels verbinden.
5. Target-autoritatives Preflight einfuehren:
   - Tabellen-/Spaltenkompatibilitaet vor dem ersten Write pruefen
   - bestehende Import-Semantik fuer Preflight-Fehler soweit moeglich
     wiederverwenden
6. Tabellenliste automatisch aus dem gelesenen Source-Schema bzw. den
   FK-Abhaengigkeiten ableiten, falls `--tables` nicht gesetzt ist.
7. Schreibreihenfolge aus dem Zielschema bzw. einer gleichwertig sicheren
   Abhaengigkeitsprojektion bestimmen.
8. FK-Zyklen explizit behandeln:
   - Default = Preflight-Fehler mit dediziertem Exit
   - nur explizite, dialektsichere Bypaesse duerfen davon abweichen
9. Bestehende Optionen sauber uebernehmen, mindestens:
   - `--tables`
   - `--filter`
   - `--truncate`
   - `--on-conflict update`
   - `--since-column` / `--since`
   - `--trigger-mode`
10. Transfer-spezifische Vorpruefungen fuer Source/Target-Dialekte und
   widerspruechliche Flags einfuehren.
11. Fehlervertrag an Export/Import angleichen, insbesondere mit dediziertem
    Preflight-Pfad fuer Zielinkompatibilitaeten und Zyklen.
12. Fortschritt an den heutigen Datenkommandos ausrichten.

Wichtig:

- `data transfer` ist kein versteckter Dateiexport.
- Routinen, Views und Trigger werden nicht implizit mitkopiert; der Milestone
  betrifft den Datenpfad.

### Phase H - Testmatrix, Docs und Beispielpfade

Ziel: Reverse, Compare und Transfer sind nicht nur implementiert, sondern in
realistischen Dialektpfaden abgesichert.

Betroffene Module:

- `hexagon:core:test`
- `hexagon:application:test`
- `adapters:driven:driver-common:test`
- `adapters:driven:driver-postgresql:test`
- `adapters:driven:driver-mysql:test`
- `adapters:driven:driver-sqlite:test`
- `adapters:driven:streaming:test`
- `adapters:driving:cli:test`
- `docs/...`

Arbeitspunkte:

1. Unit-Tests fuer Port-, Runner- und Diff-Erweiterungen.
2. Dialektnahe Reverse-Tests pro Driver.
3. Compare-Tests fuer `file/file`, `file/db` und `db/db`.
4. Transfer-Tests fuer Reihenfolge, Flag-Semantik und Fehlerfaelle.
5. Integrationstests gegen reale Dialekte.
6. CLI-Dokumentation, Architekturabgleich und mindestens ein belastbarer
   Reverse-/Compare-/Transfer-Smoke-Pfad fuer Beispielmaterial.

---

## 6. Technische Zielstruktur

### 6.1 Reverse-Port

Empfohlene Richtung:

```kotlin
interface SchemaReader {
    fun read(
        pool: ConnectionPool,
        options: SchemaReadOptions = SchemaReadOptions(),
    ): SchemaReadResult
}
```

Wichtig ist weniger die exakte Syntax als die Semantik:

- Driver lesen gegen den vorhandenen Connection-/Pool-Vertrag
- Include-Flags sitzen im Optionsobjekt statt in einem unklaren Parameter-Set
- der Rueckgabewert kann Hinweise und bewusst ausgelassene Objekte transportieren

### 6.2 Compare-Operanden

`schema compare` braucht in 0.6.0 ein explizites Operandenmodell, z. B.:

```kotlin
sealed interface SchemaInput {
    data class File(val path: Path) : SchemaInput
    data class Database(val source: String) : SchemaInput
}
```

Damit bleibt klar:

- Datei- und DB-Eingaben sind gleichwertige Quellen fuer `SchemaDefinition`
- Compare-Aufloesung sitzt im Runner
- der Core-Diff bleibt frei von I/O- und CLI-Details

### 6.3 Reverse-Notes und stille Verluste

0.6.0 soll keine faelschlich "sauberen" Reverse-Ergebnisse erzeugen, in denen
Details heimlich verschwinden. Deshalb braucht der Read-Pfad einen sichtbaren
Vertrag fuer:

- nicht modellierte Dialektattribute
- bewusst uebersprungene Objekte
- best-effort-Mappings

Verbindlicher CLI-Vertrag:

- `--output` enthaelt ausschliesslich das reverse-generierte Schema
- das Schema bleibt als regulaere `SchemaDefinition` wieder einlesbar
- strukturierte Reverse-Notes und `skipped_objects` liegen in einem separaten
  Report-Artefakt
- `stderr` zeigt die menschenlesbare Kurzfassung derselben Informationen

Die Darstellung muss ueber `stderr`, Report-Datei und Tests konsistent sein.

### 6.4 Gemeinsame Metadatenbasis

Zwischen JDBC-Rohdaten und `SchemaDefinition` soll eine interne,
dialektnahe Projektionsschicht liegen. Ziel:

- weniger Driver-spezifisches Parsing in Runnern
- gezielte Wiederverwendung fuer spaeteres Profiling
- klare Trennung zwischen "wie frage ich Metadaten ab" und
  "wie mappe ich sie ins neutrale Modell"

Diese Schicht ist interne Infrastruktur, kein neues oeffentliches API.

### 6.5 Transfer-Orchestrierung

`data transfer` braucht keinen vollstaendig neuen Streaming-Unterbau. Die
Zielstruktur ist:

- Reader und Writer bleiben dialektspezifische Ports
- ein neuer Orchestrator koordiniert Source, Target, Reihenfolge und Optionen
- vorhandene Fortschrittsereignisse werden wiederverwendet oder additiv
  erweitert
- ein Preflight validiert Zielkompatibilitaet, bevor der erste Chunk
  geschrieben wird

Wichtig:

- Tabellenreihenfolge darf nicht nur "wie vom Lister geliefert" sein
- FK-Abhaengigkeiten muessen gegen ein target-autoritatives Bild geordnet
  werden
- Zyklen duerfen nicht erst waehrend des Schreibens auffallen; sie muessen im
  Preflight explizit scheitern oder ueber einen bewusst sicheren Dialektpfad
  abgefangen werden

---

## 7. Betroffene Artefakte

Voraussichtlich anzupassen oder neu einzufuehren:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt`
- neuer `SchemaReader`-Port unter `hexagon/ports/...`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/...`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/...`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`
- neuer `SchemaReverseRunner` unter `hexagon/application/...`
- neuer `DataTransferRunner` unter `hexagon/application/...`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt`
- neue CLI-Helfer fuer Reverse/Transfer
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodec.kt`
- neuer JSON-Schema-Writer/-Codec unter `adapters/driven/formats/...`
- neuer Reverse-Report-Writer unter `adapters/driven/formats/...`
- `adapters/driven/driver-common/...`
- `adapters/driven/driver-postgresql/...`
- `adapters/driven/driver-mysql/...`
- `adapters/driven/driver-sqlite/...`
- `adapters/driven/streaming/...`
- `spec/cli-spec.md`
- `spec/neutral-model-spec.md`
- `spec/architecture.md`

Neue oder erweiterte Testartefakte:

- Reverse-Fixtures und Golden-Schemas
- Compare-Fixtures fuer gemischte Operandentypen
- Transfer-Tests fuer Reihenfolge und Conflict-Pfade

---

## 8. Akzeptanzkriterien

- [ ] `DatabaseDriver` exponiert einen `SchemaReader`, und alle drei
      Built-in-Dialekte liefern eine Implementierung.
- [ ] `schema reverse --source <url> --output <path>` funktioniert fuer
      PostgreSQL, MySQL und SQLite.
- [ ] Reverse erzeugt gueltige YAML- und JSON-Schemas im neutralen Format.
- [ ] `--output` enthaelt ein reines, wieder einlesbares Schema-Dokument; Notes
      werden nicht in die Schema-Datei eingebettet.
- [ ] Reverse-erzeugte YAML-/JSON-Schemas koennen von den file-based
      Schema-Pfaden wieder konsumiert werden.
- [ ] Reverse-Notes und bewusst uebersprungene Objekte erscheinen auf `stderr`
      und in einem separaten strukturierten Report.
- [ ] Tabellen, Spalten, Keys, Constraints und Indizes werden gelesen.
- [ ] Sequenzen und relevante Custom Types werden gelesen.
- [ ] Views werden lesbar und compare-faehig.
- [ ] Procedures / Functions / Triggers sind ueber Include-Flags kontrollierbar.
- [ ] 0.5.5-Typen (`uuid`, `json`, `binary`, `array`, `geometry`) werden im
      Reverse-Pfad korrekt transportiert.
- [ ] Nicht exakt neutrale oder bewusst ausgelassene Faelle erscheinen als
      sichtbare strukturierte Reverse-Notes; es gibt keinen stillen Verlust.
- [ ] `schema compare` akzeptiert `file/file`, `file/db` und `db/db`.
- [ ] Die Compare-Ausgabe deckt die von 0.6.0 gelesenen Objekttypen und
      Metadatenfelder ab.
- [ ] Die bestehenden Compare-Exit-Codes `0` und `1` bleiben erhalten; DB-,
      Config- und Validierungsfehler sind sauber getrennt.
- [ ] `data transfer --source <url> --target <url>` streamt ohne
      Zwischenformat von DB zu DB.
- [ ] `data transfer` uebernimmt die vereinbarten Datenpfad-Flags konsistent.
- [ ] `data transfer` fuehrt vor dem ersten Write ein target-autoritatives
      Preflight fuer Tabellen-/Spaltenkompatibilitaet aus.
- [ ] Die Tabellenreihenfolge fuer Transfer wird automatisch aus FK-Beziehungen
      oder einer dokumentierten gleichwertigen Strategie abgeleitet.
- [ ] FK-Zyklen im `data transfer` schlagen im Preflight fehl, sofern kein
      expliziter und dokumentierter sicherer Bypass aktiv ist.
- [ ] Preflight-Fehler im `data transfer` sind exit-code-seitig vom eigentlichen
      Streaming-Fehlerpfad getrennt.
- [ ] `spec/cli-spec.md`, `spec/neutral-model-spec.md` und
      `spec/architecture.md` beschreiben denselben 0.6.0-Vertrag.

---

## 9. Verifikation

Die Umsetzung wird mit gezielten Modul-Tests und Dialekt-Smokes verifiziert.
Mindestumfang:

1. Gezielter Testlauf fuer Core, Application, Driver, Streaming und CLI:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :hexagon:application:test :adapters:driven:driver-common:test :adapters:driven:driver-postgresql:test :adapters:driven:driver-mysql:test :adapters:driven:driver-sqlite:test :adapters:driven:streaming:test :adapters:driven:formats:test :adapters:driving:cli:test" \
  -t d-migrate:0.6.0-tests .
```

2. Integration ueber das bestehende Docker-Skript, falls Driver-Tests echte
   Datenbanken brauchen:

```bash
./scripts/test-integration-docker.sh
```

3. Manuelle Reverse-Smokes:

```bash
docker run --rm -v $(pwd):/work d-migrate:0.6.0 \
  schema reverse --source postgresql://user:pw@host/db --output /work/out/schema.yaml --report /work/out/schema.report.yaml

docker run --rm -v $(pwd):/work d-migrate:0.6.0 \
  schema reverse --source mysql://user:pw@host/db --format json --output /work/out/schema.json --report /work/out/schema.report.yaml
```

4. Manuelle Compare-Smokes:

```bash
docker run --rm -v $(pwd):/work d-migrate:0.6.0 \
  schema compare --source /work/schema.yaml --target postgresql://user:pw@host/db

docker run --rm d-migrate:0.6.0 \
  schema compare --source postgresql://user:pw@host/db1 --target postgresql://user:pw@host/db2
```

5. Manuelle Transfer-Smokes:

```bash
docker run --rm d-migrate:0.6.0 \
  data transfer --source postgresql://user:pw@host/src --target mysql://user:pw@host/dst

docker run --rm d-migrate:0.6.0 \
  data transfer --source sqlite:///work/src.db --target sqlite:///work/dst.db --tables users,orders
```

Dabei explizit pruefen:

- Reverse-Notes sind auf stderr und im Sidecar-Report konsistent
- die erzeugten Reverse-Schemas lassen sich von den file-based Schema-Pfaden
  wieder einlesen
- Compare meldet echte Unterschiede weiterhin mit Exit `1`
- Transfer erzeugt keine Zwischenartefakte und respektiert die vereinbarten
  Import-/Export-Semantiken
- Transfer scheitert bei Zielinkompatibilitaeten oder FK-Zyklen bereits im
  Preflight mit dem dafuer vorgesehenen Fehlerpfad

---

## 10. Risiken und offene Punkte

### R1 - Metadaten-Scope kann ausufern

Reverse trifft schnell auf Dialektdetails, die nicht sauber ins aktuelle Modell
passen. Ohne klare Modellierungsgrenzen droht 0.6.0 zu einem offenen
Metadaten-Sammelbecken zu werden.

### R2 - JDBC-Metadaten sind dialekt- und versionsabhaengig

Gerade SQLite und PostgreSQL brauchen voraussichtlich Zusatzqueries jenseits
der reinen JDBC-Standardsicht. Tests muessen deshalb gegen reale Dialekte
laufen, nicht nur gegen Stubs.

### R3 - Compare-Erweiterung ist groesser als nur "DB lesen koennen"

Sobald Reverse mehr Objekttypen liefert, muss auch der Diff-Vertrag wachsen.
0.6.0 sollte das als eigenen Arbeitspfad behandeln und nicht als beilaufige
Runner-Aenderung.

### R4 - Transfer beruehrt zwei Seiten gleichzeitig

`data transfer` kombiniert Source-, Target-, Streaming- und
Fehlerbehandlungslogik. Reihenfolge, FK-Zyklen und Konfliktverhalten muessen
explizit gemacht werden, sonst entstehen schwer testbare Grauzonen.

### R5 - Doku-Schulden aus frueheren Platzhaltern

`schema reverse` ist heute bereits in mehreren Docs angerissen, aber nicht
konsistent. Wenn Phase A nicht ernst genommen wird, droht dieselbe Funktion in
drei leicht unterschiedlichen Varianten beschrieben zu werden.

---

## 11. Abschlussdefinition

Milestone 0.6.0 ist abgeschlossen, wenn:

- bestehende Datenbanken in ein belastbares neutrales Schema rueckgefuehrt
  werden koennen,
- `schema compare` nicht mehr auf Datei-zu-Datei beschraenkt ist,
- `data transfer` den bestehenden Streaming-Unterbau direkt fuer DB-zu-DB
  wiederverwendet,
- und der 0.6.0-Vertrag in Code, CLI und Dokumentation konsistent beschrieben
  ist.

Danach ist der Kernpfad fuer Beta-Projekte komplett: d-migrate kann nicht nur
Zielschemata erzeugen, sondern Ist-Systeme lesen, vergleichen und Daten direkt
zwischen ihnen bewegen.
