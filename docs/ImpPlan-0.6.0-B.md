# Implementierungsplan: Phase B - Reverse-Vertrag und Modellanpassungen

> **Milestone**: 0.6.0 - Reverse-Engineering und Direkttransfer
> **Phase**: B (Reverse-Vertrag und Modellanpassungen)
> **Status**: Draft (2026-04-13)
> **Referenz**: `docs/implementation-plan-0.6.0.md` Abschnitt 2,
> Abschnitt 4.2 bis 4.7, Abschnitt 5 Phase B, Abschnitt 6.1 bis 6.3,
> Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10;
> `docs/ImpPlan-0.6.0-A.md`; `docs/architecture.md`;
> `docs/neutral-model-spec.md`

---

## 1. Ziel

Phase B produktiviert den fachlichen Kernvertrag fuer 0.6.0 im Hexagon:
Reverse-Ergebnisse muessen ueber einen stabilen Read-Port gelesen, als
belastbares neutrales Modell transportiert und spaeter von Compare, Generate
und Transfer konsistent weiterverarbeitet werden koennen.

Der Teilplan liefert bewusst noch keine JDBC-Abfragen, keine Codec-Schreibpfade
und kein CLI-Kommando. Er schafft die Port- und Modellgrundlage, auf der die
spaeteren Phasen C bis H aufsetzen.

Nach Phase B soll im Core- und Port-Vertrag klar und testbar gelten:

- `DatabaseDriver` exponiert additiv einen `schemaReader()`
- `SchemaReader` arbeitet gegen den bestehenden Pool-/Connection-Vertrag
- der Read-Pfad liefert ein Ergebnisobjekt statt nur eines nackten
  `SchemaDefinition`
- Reverse-Notes und bewusst ausgelassene Objekte sind strukturiert
  transportierbar
- Compare-relevante Reverse-Objekte sind im Modell nicht auf Tabellen, Enums
  und Views beschraenkt
- 0.6.0 fuehrt keinen freien Metadaten-Sack im Modell ein
- reverse-generierte Modelle scheitern nicht an vermeidbaren
  Self-Inflicted-Widerspruechen im Validator

---

## 2. Ausgangslage

Aktueller Stand der Codebasis in `hexagon:ports` und `hexagon:core`:

- `DatabaseDriver` exponiert heute `ddlGenerator()`, `dataReader()`,
  `tableLister()`, `dataWriter()` und `urlBuilder()`, aber noch keinen
  `schemaReader()`.
- `TableLister` existiert produktiv, wird bereits im Export genutzt und
  dokumentiert seine eigene spaetere Ablosung durch einen vollstaendigen
  `SchemaReader`.
- Ein `SchemaReader`-Port, `SchemaReadOptions` oder `SchemaReadResult`
  existieren im realen Code noch nicht.
- `SchemaDefinition` ist objektseitig schon breiter, als es der heutige
  Compare-Pfad ausnutzt:
  - `customTypes`
  - `tables`
  - `procedures`
  - `functions`
  - `views`
  - `triggers`
  - `sequences`
- `CustomTypeDefinition` kennt bereits `ENUM`, `COMPOSITE` und `DOMAIN`.
- `ViewDefinition`, `FunctionDefinition`, `ProcedureDefinition`,
  `TriggerDefinition` und `SequenceDefinition` besitzen bereits eigene
  Modelltypen und muessen daher nicht erst neu erfunden werden.
- `SchemaDefinition` haelt `functions`, `procedures` und `triggers` heute
  jeweils unter einem einfachen `Map<String, ...>`-Key. Damit ist fuer 0.6.0
  noch nicht geklaert, wie ueberladene Routinen oder gleichnamige Trigger auf
  verschiedenen Tabellen verlustfrei identifiziert werden.
- `TableDefinition` kennt heute nur portable Tabellenbasisdaten
  (`columns`, `primaryKey`, `indices`, `constraints`, `partitioning`), aber
  noch keine expliziten Felder fuer compare-relevante physische
  Dialektattribute wie MySQL-Engine oder SQLite-`WITHOUT ROWID`.
- `SchemaComparator` und `SchemaDiff` decken aktuell nur
  Schema-Metadaten, Tabellen, Enum-Custom-Types und Views ab.
- `schema compare` ist deshalb im Core faktisch noch auf einen Teil des
  vorhandenen Modells reduziert, obwohl das Modell bereits weitere Objektarten
  tragen kann.
- Der vorhandene Note-/Skip-Vertrag in `hexagon:ports` ist heute DDL-zentriert:
  `DdlResult`, `TransformationNote`, `NoteType` und `SkippedObject` sind auf
  Generatorergebnisse zugeschnitten und nicht als Reverse-Vertrag ausgewiesen.
- `SchemaValidator` validiert heute vor allem Tabellen-, Spalten- und
  Trigger-Referenzen; fuer Sequenzen, Routinen und viele reverse-relevante
  Zusatzmetadaten existiert noch kein bewusster 0.6.0-Vertrag.

Konsequenz fuer Phase B:

- Der groesste 0.6.0-Gap ist aktuell nicht ein fehlendes Objekttop-level im
  Schema, sondern der fehlende Read-Vertrag und die zu schmale Compare- und
  Validierungsoberflaeche rund um das bereits vorhandene Modell.
- Ohne Phase B droht 0.6.0 entweder bei einem nackten
  `SchemaDefinition`-Rueckgabewert haengenzubleiben oder in spaeteren Phasen
  eine Mischung aus Reverse-spezifischen Ad-hoc-Typen, DDL-Reuse und
  impliziten Modellluecken zu produzieren.

---

## 3. Scope fuer Phase B

### 3.1 In Scope

- Einfuehrung eines additiven Reverse-Ports in `hexagon:ports`
- Definition von `SchemaReader`, `SchemaReadOptions` und `SchemaReadResult`
- verbindlicher Vertrag fuer strukturierte Reverse-Notes und
  `skipped_objects`
- Festlegung eines minimalen Operand-Envelope-Vertrags fuer spaeteren
  DB-Operand-Compare
- bewusste Entscheidung, welche reverse-relevanten Metadaten als explizite
  Modellfelder und welche nur als Notes transportiert werden
- modellseitige Absicherung compare-relevanter Custom Types, Sequenzen und
  optionaler DB-Objekte
- Erweiterung von `SchemaDiff` und `SchemaComparator` fuer die Objektarten, die
  0.6.0 reverse-seitig liefern und spaeter compare-seitig sichtbar machen will
- Ueberpruefung und Nachschaerfung von `SchemaValidator`, damit
  reverse-generierte Modelle nicht an generator- oder parserfremden
  Scheinwiderspruechen scheitern
- Core-/Port-Tests fuer den neuen Vertrag

### 3.2 Bewusst nicht Teil von Phase B

- JDBC-Metadatenabfragen und Driver-spezifische `SchemaReader`-Implementierungen
- gemeinsame JDBC-Projektionsschicht in `driver-common`
- YAML-/JSON-Schreibpfad fuer Reverse-Schemas
- Reverse-Report-Writer oder Sidecar-Serialisierung
- `schema reverse`-Runner, CLI-Flags oder Dateiausgabe
- `schema compare`-Runner fuer `file/db` und `db/db`
- `SchemaCompareSummary`, `DiffView` und CLI-Rendering der neuen Objektarten
- `data transfer`-Runner oder Transfer-Preflight
- generischer SQL-Parser oder Datei-/stdin-Reverse

---

## 4. Leitentscheidungen fuer Phase B

### 4.1 `SchemaReader` bleibt Teil der bestehenden Driver-Fassade

0.6.0 baut auf der realen Driver-Struktur auf.

Verbindliche Folge:

- `DatabaseDriver` bekommt additiv `schemaReader()`
- `tableLister()` bleibt fuer bestehende Export-Pfade erhalten
- `SchemaReader` arbeitet gegen `ConnectionPool`, nicht gegen ein neues
  paralleles Connection-API
- Connection-Ownership folgt dem bestehenden Port-Muster: der Reader leiht sich
  benoetigte Connections selbst aus dem Pool und gibt sie nach dem Read wieder
  zurueck

### 4.2 Reverse bekommt ein eigenes Ergebnisobjekt

Ein nacktes `SchemaDefinition` reicht fuer Live-Reverse nicht aus, weil 0.6.0
bewusst mit best-effort-Mappings, ausgelassenen Objekten und Hinweisen rechnen
muss.

Phase B fuehrt deshalb einen Ergebnisvertrag mit mindestens folgenden
Bestandteilen ein:

- `schema`
- `notes`
- optional `skippedObjects`

Wichtig ist die Semantik:

- `SchemaReadResult` ist nicht einfach `DdlResult` mit anderem Namen
- der Reverse-Pfad darf `DdlStatement`- oder SQL-spezifische Felder nicht als
  Transportkruecke mitziehen
- Phase B fuehrt fuer den Read-Pfad einen eigenen Note-Typ
  `SchemaReadNote` oder gleichwertig explizit benannten Reverse-Note-Vertrag
  ein
- `TransformationNote` bleibt DDL-/Generator-Semantik und wird nicht als
  impliziter Mehrzwecktyp fuer Reverse recycelt

Fachlich verbindlich bleibt:

- strukturierte Notes tragen mindestens Severity/Typ, Code, Objektbezug,
  Nachricht und optionalen Hinweis
- `skippedObjects` erfassen bewusst nicht transportierte ganze Objekte
- best-effort-Mappings ohne Objektverlust laufen ueber Notes, nicht ueber
  `skippedObjects`

### 4.3 `SchemaReadOptions` bleibt klein, explizit und portnah

Die Read-Optionen gehoeren in ein klares Optionsobjekt und nicht in ein loses
Methoden-Parameter-Set.

Fuer 0.6.0 wird verbindlich:

- Include-Flags sitzen in `SchemaReadOptions`
- Optionen unterscheiden mindestens zwischen:
  - `includeViews`
  - `includeProcedures`
  - `includeFunctions`
  - `includeTriggers`
- CLI-/I/O-Themen wie `--output`, `--report`, `--format` oder Dateipfade
  gehoeren ausdruecklich **nicht** in `SchemaReadOptions`
- Dialektwahl gehoert ebenfalls nicht in die Read-Optionen, weil der Dialekt
  ueber den ausgewaehlten `DatabaseDriver` feststeht

Der Port-Vertrag bleibt damit expliziter als die heutige alte CLI-Skizze mit
`--include-procedures` als Sammelbegriff fuer zwei verschiedene Objektarten.

### 4.4 Kein freier Metadaten-Sack im Modell

Phase B fuehrt keinen `Map<String, Any>`-Anhang und keine lose
`extras`-Struktur ein.

Stattdessen gilt fuer 0.6.0:

- compare- oder round-trip-relevante Metadaten bekommen explizite Modellfelder
- reine Herkunfts-, Erkennungs- oder Betriebsdetails bleiben Reverse-Notes

Verbindliche Modellentscheidung fuer die heute bekannten 0.6.0-Faelle:

- MySQL-Tabellen-Engine ist compare-relevant und bekommt ein explizites
  Modellfeld unter `TableDefinition` oder einer gleichwertigen kleinen
  Tabellen-Metadatenstruktur
- SQLite-`WITHOUT ROWID` ist compare-relevant und bekommt ebenfalls ein
  explizites Modellfeld
- SQLite-Virtual-Tables werden in 0.6.0 nicht still als normale Tabellen
  maskiert; wenn sie nicht sauber ins neutrale Modell passen, erscheinen sie
  als `skippedObject` und/oder Reverse-Note
- PostgreSQL-Extensions bleiben in 0.6.0 strukturierte Reverse-Notes, solange
  kein klarer neutraler Objektvertrag mit Compare- und Generate-Nutzen
  festgelegt ist

### 4.5 Compare darf nicht auf Enums stehenbleiben

Das aktuelle Modell ist breiter als der heutige Diff-Vertrag. Phase B schliesst
diese Schieflage bewusst.

Verbindliche Folge:

- `SchemaDiff` und `SchemaComparator` bleiben I/O-frei in `hexagon:core`
- sie werden aber fuer 0.6.0 auf die Objektarten erweitert, die Phase D/E/F
  reverse- und compare-seitig sichtbar machen wollen
- dazu gehoeren mindestens:
  - Custom Types nicht nur als `ENUM`, sondern auch als `DOMAIN` und
    `COMPOSITE`
  - `sequences`
  - `views`
  - `functions`
  - `procedures`
  - `triggers`

Dabei ist kein SQL-Text-Diff gefordert; ausreichend ist eine klare
strukturbezogene Compare-Repraesentation auf Basis des vorhandenen Modells.

### 4.6 Objektidentitaet fuer Routinen und Trigger muss verlustfrei sein

0.6.0 will Routinen und Trigger nicht nur lesen, sondern spaeter auch compare-
faehig transportieren. Dafuer reicht ein einfacher Name als alleiniger
Objektschluessel nicht in allen Dialekten aus.

Verbindliche Folge fuer Phase B:

- Routinen duerfen im Reverse-Vertrag nicht an einem simplen Namen kollidieren,
  wenn der Dialekt Ueberladungen zulaesst
- Trigger duerfen nicht still kollidieren, wenn identische Triggernamen auf
  verschiedenen Tabellen vorkommen
- Phase B legt deshalb eine kanonische verlustfreie Objektidentitaet fest:
  - Funktionen und Prozeduren werden im Schema ueber einen kanonischen
    Signatur-Key `name(direction:type,direction:type,...)` adressiert
  - Trigger werden im Schema ueber einen tabellenqualifizierten Key
    `table::name` adressiert
- nicht akzeptabel sind adapterseitige Ad-hoc-Umbenennungen, die nur lokal den
  letzten gefundenen Eintrag "retten"

Wichtig ist weniger die exakte Syntax als die Semantik:

- dieselbe DB-Struktur muss bei Reverse, Datei-Serialisierung und spaeterem
  Compare stabil denselben Objektschluessel behalten
- der Modellvertrag muss Ueberladungen und gleichnamige Trigger ausdruecklich
  tragen koennen, statt sie implizit zu verbieten
- der kanonische Key ist Teil des 0.6.0-Schema-Vertrags und nicht nur ein
  interner Helpername

### 4.7 Compare darf Reverse-Notes und `skippedObjects` nicht verlieren

Der Core-Diff bleibt bewusst schema-zentriert. Trotzdem darf der spaetere
Compare-Pfad DB-Operanden nicht auf `schema` reduzieren und dabei Hinweise oder
bewusst ausgelassene Objekte wegwerfen.

Verbindliche Folge fuer Phase B:

- `SchemaComparator` diffed weiterhin nur `SchemaDefinition`
- die Aufloesung von Compare-Operanden arbeitet aber logisch mit einem
  expliziten Operand-Envelope, nicht nur mit `schema`
- kanonische Mindestform fuer Phase F:

```kotlin
data class ResolvedSchemaOperand(
    val schema: SchemaDefinition,
    val validation: ValidationResult,
    val notes: List<SchemaReadNote> = emptyList(),
    val skippedObjects: List<SkippedObject> = emptyList(),
)
```

- Reverse-Notes und `skippedObjects` muessen pro Operand bis zur spaeteren
  Compare-Ausgabe oder einem gleichwertigen Ergebnisvertrag erhalten bleiben
- ein strukturell `identical`er Compare darf deshalb trotzdem begleitende
  Reverse-Warnungen oder ausgelassene Objekte sichtbar machen

Phase B definiert noch kein CLI-Rendering fuer diese Informationen. Verbindlich
ist aber, dass sie im Compare-Pfad nicht still verlorengehen und nicht erst in
`DiffView` oder `SchemaCompareSummary` wieder neu erfunden werden muessen.

### 4.8 Validierung bleibt modellzentriert und reverse-tauglich

Phase B darf den Validator weder mit Driver-Details ueberladen noch
reverse-generierte Modelle ohne Not blockieren.

Verbindlich fuer 0.6.0:

- `SchemaValidator` validiert weiterhin neutrale Modellkonsistenz, nicht
  Dialektverfuegbarkeit
- neue optionale Modellfelder fuer Reverse bleiben validatorseitig optional,
  solange kein echter neutraler Widerspruch vorliegt
- `E008` wird in 0.6.0 bewusst global von einem blockierenden
  ValidationError zu einem ValidationWarning herabgestuft
- diese Lockerung gilt nicht nur fuer Live-Reverse, sondern fuer das neutrale
  Modell insgesamt, damit reverse-generierte Schema-Dateien spaeter ueber die
  file-based Pfade wieder konsumierbar bleiben
- fehlende Primary Keys bleiben damit ein sichtbarer Qualitaetshinweis, aber
  kein invalidierender Hard-Fail mehr
- Routinen-, View- und Trigger-Bodies bleiben als rohe Texte zulaessig und
  werden nicht parserseitig "nachvalidiert"
- vorhandene Integritaetspruefungen wie Trigger-Tabellenreferenzen bleiben
  erhalten

---

## 5. Arbeitspakete

### B.1 Reverse-Port in `hexagon:ports` einfuehren

Im Ports-Modul sind die neuen Vertragstypen einzufuehren:

- `SchemaReader`
- `SchemaReadOptions`
- `SchemaReadResult`
- `DatabaseDriver.schemaReader()`

Dabei ist der Vertrag bewusst additiv zu schneiden:

- bestehende Driver-Fassade bleibt erhalten
- `TableLister` wird nicht in Phase B entfernt
- der neue Read-Port folgt dem vorhandenen Pool-/Ownership-Muster

Empfohlene Grundrichtung:

```kotlin
interface SchemaReader {
    fun read(
        pool: ConnectionPool,
        options: SchemaReadOptions = SchemaReadOptions(),
    ): SchemaReadResult
}
```

### B.2 Reverse-Note- und Skip-Vertrag festziehen

Fuer Reverse muessen strukturierte Hinweise so modelliert werden, dass Phase C
sie serialisieren und Phase E sie auf `stderr` spiegeln kann.

Mindestens noetig:

- eindeutiger Severity-/Typvertrag
- stabiler Objektbezug fuer Notes
- `skippedObjects` fuer bewusst ausgelassene ganze Objekte
- klare Trennung zwischen:
  - "Objekt gelesen, aber mit Einschraenkung gemappt"
  - "Objekt bewusst nicht in `schema` transportiert"

Wichtig:

- `SchemaReadResult` darf keine SQL-Statement-Container transportieren
- `SchemaReadResult.notes` verwenden einen expliziten `SchemaReadNote`-
  Vertrag statt `TransformationNote`
- `SkippedObject` kann wiederverwendet werden, sofern seine Semantik fuer
  Reverse ausdruecklich mitgetragen wird
- Compare darf spaeter dieselben `SchemaReadNote`-/`SkippedObject`-Typen pro
  Operand weiterreichen, statt einen zweiten parallelen Note-Vertrag zu bauen

### B.3 Tabellen-Metadaten fuer 0.6.0 explizit modellieren

Der Reverse-Pfad wird physische Tabellenattribute sehen, die der heutige
portable Tabellenkern noch nicht ausdruecklich traegt.

Phase B definiert hier einen kleinen expliziten Modellzuschnitt statt einer
losen Metadatenablage.

Mindestens zu entscheiden und umzusetzen:

- MySQL-Engine als explizites Tabellenattribut
- SQLite-`WITHOUT ROWID` als explizites Tabellenattribut
- klare Nicht-Modellierung von Virtual Tables als normale `TableDefinition`,
  solange kein belastbarer neutraler Vertrag existiert

Empfohlen ist eine kleine benannte Tabellen-Metadatenstruktur unter
`TableDefinition`, nicht eine Streuung mehrerer dialektspezifischer
Top-Level-Strings ohne Zusammenhang.

### B.4 Custom Types ueber `ENUM` hinaus compare-faehig machen

Das Modell kennt `DOMAIN` und `COMPOSITE` bereits. Der Core-Diff jedoch noch
nicht.

Phase B erweitert deshalb Compare und Diff fuer:

- `ENUM` ueber Werte
- `DOMAIN` ueber `baseType`, `precision`, `scale`, `check`, `description`
- `COMPOSITE` ueber die enthaltenen Felder

Wichtig:

- Phase B fuehrt keinen neuen generischen Typbaum ein
- Reverse-Metadaten fuer Custom Types muessen spaeter von Compare sichtbar
  unterschieden werden koennen

### B.5 Verlustfreie Objektidentitaet fuer Routinen und Trigger festziehen

Bevor Routinen und Trigger compare-faehig werden koennen, muss ihre Identitaet
im Modell stabil festgelegt sein.

Mindestens noetig:

- Funktionen und Prozeduren werden kanonisch ueber
  `name(direction:type,direction:type,...)` adressiert
- Trigger werden kanonisch ueber `table::name` adressiert
- Diff- und Serialisierungspfad duerfen diese Identitaet nicht spaeter wieder
  implizit auf den nackten Namen reduzieren

Wichtig:

- die Identitaet muss im neutralen Modell und nicht nur in einem Driver-Helper
  leben
- keine verlustbehafteten Umbenennungsregeln im Adapter als Ersatz fuer einen
  sauberen Modellvertrag

### B.6 Sequenzen und optionale DB-Objekte in den Diff-Vertrag ziehen

Damit `schema compare` spaeter nicht wieder nur Tabellen plus Sonderfaelle
vergleicht, wird der Core-Diff fuer die bereits vorhandenen Modelltypen
erweitert.

Mindestens noetig:

- `sequences`
- `views`
- `functions`
- `procedures`
- `triggers`

Die Compare-Granularitaet bleibt modellbasiert:

- Sequenzen vergleichen strukturelle Zahlenwerte und `cycle/cache`
- Views vergleichen bestehende Felder wie `materialized`, `refresh`, `query`,
  `sourceDialect`
- Routinen und Trigger vergleichen ihre deklarativen Felder sowie
  `body/sourceDialect`, ohne in SQL-AST-Parsing abzurutschen

Zusaetzlich verbindlich:

- der spaetere Compare-Pfad muss pro Operand Reverse-Notes und
  `skippedObjects` neben dem Schema-Diff weitertransportieren koennen
- Phase B legt dafuer den kanonischen Operand-Envelope
  `ResolvedSchemaOperand` oder einen gleichwertigen Typ mit denselben Feldern
  fest, auch wenn die konkrete Projektion erst in spaeteren CLI-Phasen gebaut
  wird

### B.7 Validator auf 0.6.0-Reverse-Tauglichkeit pruefen

`SchemaValidator` ist gegen den erweiterten Reverse-Vertrag zu pruefen und nur
dort zu schaerfen, wo echte Modellwidersprueche abgesichert werden muessen.

Mindestens erforderlich:

- neue Tabellen-Metadatenfelder duerfen keine erzwungenen Dialektregeln in den
  Validator ziehen
- optional gelesene Objektarten muessen als regulaerer Teil von
  `SchemaDefinition` akzeptiert werden
- `E008` wird fuer 0.6.0 von Error auf Warning umgestellt
- Reverse-Schemas mit Routinen, Views, Triggern, Sequences und erweiterten
  Custom Types duerfen nicht an generatorfremden Pflichtannahmen scheitern

Explizit nicht Ziel:

- SQL-Bodies zu parsen
- Portabilitaet von Routinen- oder Trigger-Bodies zu bewerten
- Driver-spezifische Feature-Sperren in `SchemaValidator` zu ziehen

### B.8 Core- und Port-Tests erweitern

Die Phase ist nur belastbar, wenn der neue Vertrag durch Tests sichtbar wird.

Mindestens erforderlich:

- Compile-/Vertragstest fuer `DatabaseDriver.schemaReader()`
- Unit-Tests fuer `SchemaReadResult`- und `SchemaReadNote`-Semantik
- Comparator-Tests fuer:
  - verlustfreie Routinen-Identitaet bei Ueberladungen
  - verlustfreie Trigger-Identitaet bei gleichen Namen auf verschiedenen
    Tabellen
  - `DOMAIN`
  - `COMPOSITE`
  - `sequences`
  - `functions`
  - `procedures`
  - `triggers`
  - erweiterte Tabellenmetadaten
- Validator-Tests fuer reverse-generierte Modelle mit den neuen Objektarten und
  Tabellenmetadaten
- Validator-Test, dass `E008` in 0.6.0 nur noch Warning und kein Error mehr ist
- Application-nahe Vorbereitungstest oder gleichwertiger Vertragstest fuer
  `ResolvedSchemaOperand` mit `schema`, `validation`, `notes` und
  `skippedObjects`

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/TableLister.kt`
- neuer Reverse-Port-Schnitt unter `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SchemaDefinition.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/TableDefinition.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/CustomTypeDefinition.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ViewDefinition.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/FunctionDefinition.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ProcedureDefinition.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/TriggerDefinition.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SequenceDefinition.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaDiff.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaValidator.kt`

Tests:

- `hexagon/core/src/test/...`
- optional neue Vertragstests unter `hexagon/ports/src/test/...`, falls das
  Modul fuer kleine API-Tests geoeffnet wird

Indirekt betroffen in Folgephasen:

- `adapters:driven:driver-common`
- `adapters:driven:driver-postgresql`
- `adapters:driven:driver-mysql`
- `adapters:driven:driver-sqlite`
- `adapters:driven:formats`
- `hexagon:application`
- `adapters:driving:cli`

---

## 7. Akzeptanzkriterien

- [ ] `DatabaseDriver` exponiert additiv einen `schemaReader()`, ohne
      `tableLister()` fuer bestehende Export-Pfade zu entfernen.
- [ ] Ein `SchemaReader`-Port existiert in `hexagon:ports` mit
      `ConnectionPool`-basiertem Read-Vertrag und einem klaren Optionsobjekt.
- [ ] `SchemaReadOptions` enthaelt nur portnahe Reader-Optionen und keine
      CLI-/Datei-/Formatparameter.
- [ ] `SchemaReadOptions` unterscheidet Funktionen und Prozeduren als getrennte
      Objektarten statt sie nur implizit in einem Sammelflag zu vermischen.
- [ ] Der Reverse-Rueckgabewert ist ein Ergebnisobjekt mit `schema`, `notes`
      und optional `skippedObjects`, nicht nur ein nacktes `SchemaDefinition`.
- [ ] Der Reverse-Vertrag transportiert Hinweise und ausgelassene Objekte ohne
      SQL-Statement-Container oder andere generatorseitige Kruecken.
- [ ] `SchemaReadResult.notes` verwenden einen expliziten Reverse-Note-Typ
      (`SchemaReadNote` oder gleichwertig), nicht `TransformationNote`.
- [ ] Routinen- und Trigger-Identitaet ist fuer 0.6.0 verlustfrei definiert;
      Ueberladungen oder gleichnamige Trigger auf verschiedenen Tabellen
      fuehren nicht zu stillen Ueberschreibungen im Modellvertrag.
- [ ] Der kanonische Routinen-Key ist `name(direction:type,direction:type,...)`.
- [ ] Der kanonische Trigger-Key ist `table::name`.
- [ ] Es existiert fuer 0.6.0 keine lose `Map<String, Any>`- oder
      `extras`-Escape-Hatch im neutralen Modell.
- [ ] MySQL-Engine und SQLite-`WITHOUT ROWID` sind als explizite
      compare-relevante Modellattribute entschieden und modelliert.
- [ ] SQLite-Virtual-Tables werden nicht still als normale Tabellen in das
      Modell gedrueckt, wenn deren Semantik fuer 0.6.0 nicht sauber getragen
      werden kann.
- [ ] PostgreSQL-Extensions sind fuer 0.6.0 bewusst als Reverse-Notes und nicht
      als halbmodellierte Objektklasse eingeordnet.
- [ ] `SchemaDiff` und `SchemaComparator` sind nicht mehr auf Tabellen,
      Enum-Typen und Views beschraenkt, sondern decken auch `DOMAIN`,
      `COMPOSITE`, `sequences`, `functions`, `procedures` und `triggers` ab.
- [ ] Der Compare-Vertrag fuer DB-Operanden verliert Reverse-Notes und
      `skippedObjects` nicht still, sondern haelt sie pro Operand neben dem
      Schema-Diff transportierbar.
- [ ] Der kanonische Compare-Operand-Envelope fuer spaetere DB-Operanden ist
      als `ResolvedSchemaOperand(schema, validation, notes, skippedObjects)`
      oder gleichwertig festgelegt.
- [ ] Die neuen Compare-Surfaces bleiben modellbasiert und fuehren kein
      SQL-Text- oder AST-Diff als Pflichtmechanik ein.
- [ ] `SchemaValidator` akzeptiert reverse-generierte Modelle mit erweiterten
      Custom Types, Sequences, Routinen, Views, Triggern und Tabellenmetadaten,
      solange kein echter neutraler Modellwiderspruch vorliegt.
- [ ] `E008` ist in 0.6.0 kein ValidationError mehr, sondern ein
      ValidationWarning und blockiert daher weder Reverse noch file-based
      Wiederverwendung reverse-generierter Schemas.
- [ ] Die Phase ist durch Core-Tests und mindestens compile-seitige
      Port-Verifikation gegen Regressionen abgesichert.

---

## 8. Verifikation

Phase B wird primaer ueber Compile- und Unit-Test-Verifikation abgesichert.

Mindestumfang:

1. API-Review des neuen Reverse-Port-Schnitts gegen:
   - `DatabaseDriver`
   - `TableLister`
   - den Pool-/Ownership-Vertrag aus `hexagon:ports`
2. Core-Review des erweiterten Modells gegen:
   - Custom Types
   - Sequenzen
   - Views
   - Routinen
   - Trigger
   - verlustfreie Objektidentitaet fuer Routinen und Trigger
   - explizite Tabellenmetadaten
3. Gegenpruefung, dass Compare-relevante Objektarten im Core-Diff nicht mehr
   stumm aus dem Modell herausfallen.
4. Gegenpruefung, dass Reverse-Hinweise und ausgelassene Objekte im
   Rueckgabevertrag strukturiert sichtbar sind und nicht in einer losen
   Hilfsstruktur verschwinden.
5. Explizite Gegenpruefung, dass der Compare-Pfad kuenftig einen
   `ResolvedSchemaOperand` oder gleichwertigen Typ statt eines nackten
   `SchemaDefinition` transportiert.
6. Explizite Gegenpruefung, dass `E008` in 0.6.0 nur noch als Warning
   auftaucht und nicht mehr als Hard-Fail.

Empfohlene technische Mindestlaeufe nach Umsetzung:

```bash
./gradlew :hexagon:ports:compileKotlin :hexagon:core:test
```

Falls Port-Vertragstests eingefuehrt werden:

```bash
./gradlew :hexagon:ports:test :hexagon:core:test
```

Praktische Review-Hilfe:

- gezielte Volltextsuche nach `schemaReader`, `SchemaReadResult`,
  `SchemaReadOptions`, `SchemaReadNote`, `ResolvedSchemaOperand`,
  `Map<String, Any>`, `extras`, `DOMAIN`, `COMPOSITE`, `sequences`,
  `functions`, `procedures`, `triggers`, `WITHOUT ROWID`, `engine`, `E008`,
  `table::`, `direction:type`
- Diff-Review, ob neue Modellfelder in `SchemaComparator` und
  `SchemaValidator` konsistent beruecksichtigt oder bewusst ignoriert werden
- Gegenlesen, ob Reverse-Vertrag und Compare-Vertrag dieselben Objektarten als
  0.6.0-relevant behandeln
- Gegenlesen, ob Compare-Operanden den Reverse-Vertrag nicht schon vor der
  Ausgabe auf ein nacktes `SchemaDefinition` einkuerzen

---

## 9. Risiken und offene Punkte

### R1 - DDL-Typen werden halbherzig in den Reverse-Pfad wiederverwendet

Wenn `DdlResult` oder `TransformationNote` ohne semantische Bereinigung
einfach umetikettiert werden, droht ein schiefer Vertrag zwischen Generate- und
Reverse-Richtung.

### R2 - Modellierung physischer Tabellenattribute bleibt zu spaet unentschieden

Wenn MySQL-Engine und SQLite-`WITHOUT ROWID` erst in den Driver-Phasen
entschieden werden, laufen Reverse, Compare und spaetere Format-Arbeit
auseinander.

### R3 - Compare bleibt trotz breiterem Modell zu schmal

Ohne explizite Diff-Erweiterung fuer `DOMAIN`, `COMPOSITE`, `sequences`,
`functions`, `procedures` und `triggers` bleibt `schema compare` bei
DB-Operanden fachlich hinter dem Reverse-Modell zurueck.

### R4 - Objektidentitaet von Routinen und Triggern bleibt implizit

Wenn Phase B Ueberladungen und Trigger-Namenskollisionen nicht explizit loest,
entsteht spaeter entweder stiller Verlust oder ein schwer rueckbaubares
Adapter-Namensworkaround.

### R5 - Validator wird entweder zu streng oder zu blind

Zu strenge Regeln wuerden reverse-generierte Modelle ohne Not blockieren; zu
weiche Regeln wuerden echte Modellwidersprueche unbemerkt lassen. Die bewusste
Herabstufung von `E008` auf Warning muss deshalb als gezielte 0.6.0-
Entscheidung dokumentiert und testseitig abgesichert werden.

### R6 - Phase B rutscht in Driver- oder CLI-Details ab

Sobald JDBC-Query-Logik, Report-Serialisierung oder CLI-Flag-Parsing in diese
Phase hineingezogen werden, wird der Port- und Modellkern unnoetig vermischt.

---

## 10. Abschlussdefinition

Phase B ist abgeschlossen, wenn der 0.6.0-Reverse-Pfad im Port- und
Modellvertrag sauber vorbereitet ist:

- `SchemaReader` und sein Ergebnisobjekt sind im Hexagon klar definiert
- compare-relevante Reverse-Objekte fallen nicht mehr aus dem Core-Diff heraus
- Routinen und Trigger besitzen eine verlustfreie stabile Objektidentitaet
- der Reverse-Pfad nutzt einen eigenen `SchemaReadNote`-Vertrag statt
  generatorseitiger `TransformationNote`
- physische Tabellenattribute mit echtem 0.6.0-Nutzen sind explizit modelliert
- stille Metadatenverluste sind ueber Notes/`skippedObjects` statt ueber
  implizites Weglassen geregelt
- Reverse-Notes und `skippedObjects` bleiben ueber einen kanonischen
  `ResolvedSchemaOperand` auch fuer spaeteren DB-Operand-Compare transportierbar
- und `SchemaValidator` bleibt fuer reverse-generierte neutrale Modelle
  benutzbar, weil `E008` nur noch Warning statt blockierendem Error ist

Danach koennen Phase C bis F auf einem stabilen Kernvertrag aufbauen, statt
Read-, Diff- und Report-Semantik erst in den Adaptern zu improvisieren.
