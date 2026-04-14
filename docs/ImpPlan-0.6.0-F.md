# Implementierungsplan: Phase F - `schema compare` fuer DB-Operanden vervollstaendigen

> **Milestone**: 0.6.0 - Reverse-Engineering und Direkttransfer
> **Phase**: F (`schema compare` fuer DB-Operanden)
> **Status**: Review (2026-04-14)
> **Referenz**: `docs/implementation-plan-0.6.0.md` Abschnitt 4.4,
> Abschnitt 5 Phase F, Abschnitt 6.2, Abschnitt 6.3, Abschnitt 7,
> Abschnitt 8, Abschnitt 9, Abschnitt 10; `docs/ImpPlan-0.6.0-A.md`;
> `docs/ImpPlan-0.6.0-B.md`; `docs/ImpPlan-0.6.0-D.md`;
> `docs/ImpPlan-0.6.0-E.md`; `docs/cli-spec.md`;
> `docs/neutral-model-spec.md`

---

## 1. Ziel

Phase F erweitert den bestehenden file-based `schema compare`-Pfad zu einem
vollwertigen Operand-Resolver fuer `file/file`, `file/db` und `db/db`, ohne
das modellbasierte Diff-Prinzip aufzugeben.

Ergebnis der Phase ist ein produktiver, skriptfaehiger Pfad:

```bash
d-migrate schema compare --source <operand> --target <operand>
```

mit dem kanonischen 0.6.0-Operandvertrag:

- `file:<path>` fuer Schema-Dateien
- `db:<url-or-alias>` fuer Live-Datenbanken oder Named Connections
- Rueckwaertskompatibilitaet fuer praefixlose Dateipfade

Die Phase fuehrt bewusst keinen neuen Migrationspfad und kein SQL-Text-Diff
ein. Sie vervollstaendigt ausschliesslich den Compare-Eingabepfad, den
Operand-Envelope und die CLI-/Ausgabeprojektion fuer die in 0.6.0 lesbaren
Objekttypen.

Nach Phase F soll auf Core-, Application- und CLI-Ebene klar und testbar
gelten:

- `schema compare` akzeptiert `file/file`, `file/db` und `db/db`
- beide Operanden werden symmetrisch zu einem `ResolvedSchemaOperand`
  aufgeloest
- Reverse-Notes und `skippedObjects` gehen pro Operand nicht verloren
- reverse-synthetische `name`-/`version`-Marker werden vor dem Diff
  normalisiert
- der Diff-Ausgabevertrag deckt alle 0.6.0-relevanten Objektarten ab
- Exit-Codes bleiben fuer file-based Compare stabil und werden fuer
  DB-Fehlerpfade sauber erweitert

---

## 2. Ausgangslage

Aktueller Stand in `hexagon:core`, `hexagon:application` und
`adapters:driving:cli`:

- `SchemaCompareRunner` existiert produktiv, ist aktuell aber file-only:
  - `source` und `target` sind `Path`
  - beide Operanden werden ausschliesslich ueber `YamlSchemaCodec().read(...)`
    gelesen
  - Parse-Fehler laufen auf Exit `7`
- `SchemaCompareCommand` akzeptiert derzeit nur Dateipfade:
  - `--source` und `--target` sind Clikt-`path(...)`
  - DB-Operanden `db:...` sind damit noch nicht annehmbar
- die aktuelle CLI-Doku beschreibt fuer 0.6.0 bereits den Soll-Vertrag mit
  `file:`- und `db:`-Operanden, waehrend der Code noch auf 0.5.0 steht
- `SchemaCompareRunner` validiert beide gelesenen Schemas vor dem Diff und
  nutzt die bestehenden Exit-Codes:
  - `0` identisch
  - `1` Unterschiede
  - `2` CLI-/Pfadvalidierung
  - `3` Schema-Validierung
  - `7` Parse-/Write-Fehler
- ein DB-spezifischer Exit-`4`-Pfad existiert im Compare-Runner heute noch
  nicht
- `SchemaCompareHelpers` rendern aktuell nur:
  - Schema-Metadaten
  - Custom Types
  - Tabellen
  - Views
- `SchemaDiff` und `SchemaComparator` sind im Core bereits weiter und decken
  heute zusaetzlich ab:
  - Sequenzen
  - Functions
  - Procedures
  - Triggers
  - compare-relevante Tabellenmetadaten
- damit ist die eigentliche Luecke fuer Phase F nicht primaer der Core-Diff,
  sondern der Weg von CLI-Operanden zu normalisierten Operanden sowie die
  stabile Projection des erweiterten Diffs in CLI-Dokumente
- Phase F haengt fachlich an Phase B, D und E:
  - ohne `SchemaReadResult`/`SchemaReadNote` aus Phase B gibt es keinen
    belastbaren Operand-Envelope fuer DB-Operanden
  - ohne die Reverse-Provenienz- und Markerregeln aus Phase D vergleicht
    Compare reverse-synthetische Namen und Versionen als Scheinaenderungen
  - ohne den produktiven Reverse-CLI-/Reader-Pfad aus Phase E gibt es keinen
    belastbaren DB-Operand-Resolver

Konsequenz fuer Phase F:

- Die Kernarbeit liegt im Application-Layer und in der CLI-Projektion, nicht
  im erneuten Erfinden des Diff-Grundprinzips.
- Die groesste Fehlergefahr liegt in:
  - unklarer Operand-Disambiguierung
  - asymmetrischer Behandlung von Datei- und DB-Operanden
  - Verlust von Reverse-Notes pro Operand
  - Ausgabeprojektionen, die den erweiterten `SchemaDiff` nur teilweise
    sichtbar machen

---

## 3. Scope fuer Phase F

### 3.1 In Scope

- Operandmodell fuer `schema compare` ueber Datei oder Datenbankquelle
- Einfuehrung eines kanonischen `ResolvedSchemaOperand`-Envelope im
  Application-Layer
- Erweiterung von `SchemaCompareRequest` und `SchemaCompareRunner` auf
  symmetrische Operandaufloesung
- Rueckwaertskompatible Operand-Notation:
  - `file:<path>`
  - `db:<url-or-alias>`
  - praefixlose Dateipfade
- Wiederverwendung von:
  - formatbewusstem Schema-Datei-Read aus Phase C
  - `NamedConnectionResolver`
  - `ConnectionUrlParser`
  - `HikariConnectionPoolFactory`
  - `DatabaseDriverRegistry`
  - `SchemaReader`
- Compare-Operand-Normalisierung fuer reverse-generierte
  `__dmigrate_reverse__:`-Marker
- pro Operand Transport von:
  - `schema`
  - `validation`
  - `notes`
  - `skippedObjects`
- Vervollstaendigung von `SchemaCompareDocument`, `DiffView` und den
  Plain-/JSON-/YAML-Renderern fuer:
  - Sequenzen
  - Functions
  - Procedures
  - Triggers
  - neue 0.6.0-Metadatenfelder
- strukturierte Compare-Ausgabe, die operandseitige Reverse-Notes und
  `skippedObjects` sichtbar halten kann
- sauberer Exit-Code-Vertrag fuer Datei-, Config-, Connection- und
  DB-Metadatenfehler
- Runner-, Helper- und CLI-Tests fuer `file/file`, `file/db` und `db/db`
- Nachfuehrung von `docs/cli-spec.md` auf den finalen Phase-F-Vertrag

### 3.2 Bewusst nicht Teil von Phase F

- neue Core-Diff-Grundprinzipien jenseits des modellbasierten Vergleichs
- SQL-Text-, AST- oder DDL-Diff
- `schema migrate` oder `schema rollback`
- SQL-Datei-Reverse oder andere Parser-Pfade ausserhalb des bestehenden
  Schema-Datei-I/O
- neue Dialekte jenseits PostgreSQL, MySQL und SQLite
- `data transfer`
- neue globale CLI-Flags oder Root-Command-Umbauten

---

## 4. Leitentscheidungen fuer Phase F

### 4.1 Compare bleibt modellbasiert und symmetrisch

Auch mit DB-Operanden diffed `schema compare` keine SQL-Texte und fuehrt
keinen impliziten Migrationspfad ein.

Verbindliche Folge:

- beide Operanden werden vor dem Diff zu `ResolvedSchemaOperand`
  aufgeloest
- der eigentliche Diff arbeitet weiter auf normalisierten
  `SchemaDefinition`-Instanzen
- Datei- und DB-Operanden sind fachlich gleichwertige Quellen fuer denselben
  Compare-Kern

Nicht akzeptabel ist:

- ein Sonderpfad, der `db/db` anders diffed als `file/file`
- direkter Vergleich von Reverse-Reports, JDBC-Rohmetadaten oder SQL-Texten

### 4.2 Operand-Disambiguierung ist explizit, aber rueckwaertskompatibel

Phase A hat fuer 0.6.0 den kanonischen CLI-Vertrag festgezogen:

- `file:<path>` fuer Dateioperanden
- `db:<url-or-alias>` fuer Datenbankoperanden

Verbindlich fuer Phase F:

- praefixlose Operanden bleiben aus Rueckwaertskompatibilitaet Dateipfade
- Named Connections werden im Compare-Pfad nur als `db:<alias>` behandelt
  und nicht ueber Heuristiken von Dateipfaden unterschieden
- `db:` ist ein harter Compare-Operand-Prefix und kein best-effort-Hinweis
- die Operandenauflosung sitzt im Runner bzw. einem dedizierten
  Operand-Resolver, nicht verstreut in Renderer oder Core-Diff

Damit bleibt der 0.5.0-File-Pfad stabil, waehrend 0.6.0 die
DB-Operand-Semantik additiv einfuehrt.

### 4.3 `ResolvedSchemaOperand` ist der kanonische Compare-Envelope

Phase B hat festgezogen, dass DB-Operanden nicht nur auf nacktes `schema`
reduziert werden duerfen.

Verbindliche Folge fuer Phase F:

- Compare arbeitet logisch mit einem Operand-Envelope mindestens aus:
  - `schema`
  - `validation`
  - `notes`
  - `skippedObjects`
  - user-facing Operand-Referenz
- Datei- und DB-Operanden werden vor dem Diff auf denselben Envelope-Typ
  gezogen
- Reverse-Notes und `skippedObjects` bleiben pro Operand bis zur Ausgabe
  transportierbar

Nicht akzeptabel ist:

- DB-Operanden im Runner still auf `SchemaDefinition` zu reduzieren und dabei
  Reverse-Notes zu verlieren
- einen separaten Note-Vertrag nur fuer Compare zu erfinden

### 4.4 Reverse-synthetische Marker werden vor dem Diff normalisiert

Phase D fuehrt fuer reverse-generierte Live-DB-Schemas ein reserviertes
Marker-Set ein:

- `version == 0.0.0-reverse`
- `name` beginnt mit `__dmigrate_reverse__:`

Verbindliche Folge fuer Phase F:

- der Compare-Operand-Resolver bzw. ein nachgelagerter Operand-Normalizer
  erkennt vollstaendige, syntaktisch gueltige Reverse-Marker auf jedem
  aufgeloesten Operand-Envelope, unabhaengig davon ob Datei- oder DB-Ursprung
- reverse-synthetische `name`-/`version`-Werte werden vor `SchemaComparator`
  normalisiert, damit sie nicht als inhaltlicher Diff auftauchen
- unvollstaendige oder syntaktisch ungueltige Marker sind ein expliziter
  Operand- bzw. Application-Fehler und kein stiller Fallback
- die Ownership dieser Logik liegt im Application-Layer, nicht im Core-Diff

Damit bleibt `SchemaComparator` rein strukturell und bekommt keine
Herkunfts- oder Provenienzlogik aufgezwungen.

### 4.5 Datei- und DB-Operandpfade verwenden denselben Validierungsvertrag

Compare darf DB-Operanden nicht anders behandeln als Dateioperanden, sobald
ein neutrales Schema vorliegt.

Verbindliche Folge:

- jeder aufgeloeste Operand wird validiert
- Validation-Fehler bleiben Exit `3`
- Validation-Warnings bleiben pro Operand sichtbar
- DB-Operand-Notes und Validation-Warnings werden nicht miteinander vermischt,
  sondern im Dokument getrennt gehalten

Wichtig:

- Reverse-Notes beschreiben Herkunfts- und Mapping-Themen des Operanden
- Validation-Warnings beschreiben den Zustand der resultierenden
  `SchemaDefinition`

### 4.6 Compare-Exit-Codes bleiben stabil und werden nur additiv erweitert

Der bisherige file-based Compare-Vertrag darf nicht regressieren.

Verbindliche Zuordnung fuer Phase F:

- Exit `0`:
  - Operanden erfolgreich aufgeloest
  - beide Schemas valide
  - keine Unterschiede
- Exit `1`:
  - Operanden erfolgreich aufgeloest
  - beide Schemas valide
  - Unterschiede gefunden
- Exit `2`:
  - ungueltige CLI-Argumente
  - ungueltige Operand-Praefixe
  - Kollision von `--output` mit Dateioperanden
- Exit `3`:
  - mindestens ein aufgeloester Operand ist als `SchemaDefinition` invalid
- Exit `4`:
  - Connection-Fehler fuer `db:`-Operanden
  - Treiber-/Dialektfehler nach erfolgreicher DB-Operand-Aufloesung
  - DB-Metadatenfehler waehrend `SchemaReader.read(...)`
- Exit `7`:
  - Datei-/Parse-Fehler fuer Dateioperanden
  - Config-Fehler oder ungueltige DB-URL-/Alias-Aufloesung
  - Write-Fehler fuer `--output`
  - ungueltige Reverse-Marker auf bereits aufgeloesten Operanden

Phase F fuehrt keinen neuen Compare-spezifischen Exit-Code ein.

Zusaetzlich verbindlich:

- `quiet` folgt fuer Compare bewusst dem bestehenden file-based
  Rueckwaertskompatibilitaetsvertrag und dokumentiert damit explizit eine
  Compare-spezifische Legacy-Ausnahme gegenueber der globalen
  Root-Beschreibung `Only show errors`:
  - das primaere Compare-Dokument bleibt bei nicht-`0`-Ergebnissen auch unter
    `quiet` sichtbar, solange kein `--output` verwendet wird
  - `--output` schreibt das Compare-Ergebnis unabhaengig von `quiet`
  - erfolgreiche Exit-`0`-stdout-Ausgaben duerfen unter `quiet`
    unterdrueckt werden
  - bestehende Plain-Validation-Warnings auf `stderr` bleiben auch unter
    `quiet` sichtbar
  - operandseitige Reverse-Notes / `skippedObjects` folgen in `plain`
    demselben Sichtbarkeitsvertrag wie die bestehenden Validation-Warnings
  - `quiet` darf keinen `different`- oder `invalid`-Compare still machen
  - diese Compare-Semantik ist eine explizite Rueckwaertskompatibilitaetsregel
    und kein stillschweigender neuer Globalvertrag fuer andere Kommandos

### 4.7 Die CLI-Projektion deckt den erweiterten Diff wirklich ab

Im Ist-Zustand kann der Core-Diff bereits mehr als die CLI darstellt.

Verbindliche Folge fuer Phase F:

- `SchemaCompareSummary` zaehlt nicht nur Tabellen, Custom Types und Views,
  sondern auch Sequences, Functions, Procedures und Triggers
- `DiffView` und Renderer bilden diese Objektarten explizit ab
- compare-relevante 0.6.0-Metadatenfelder bleiben im strukturierten Output
  sichtbar und werden nicht hinter generischen Stringlisten versteckt
- pro Operand koennen Validation-Warnings sowie Reverse-Notes/
  `skippedObjects` im strukturierten Output getrennt sichtbar gemacht werden

Nicht akzeptabel ist:

- dass `SchemaComparator` Unterschiede findet, die `schema compare` im
  Plain-/JSON-/YAML-Output nicht mehr sichtbar macht

### 4.8 User-facing Operand-Referenzen, Fehlertexte und Fehler-Envelope bleiben fuer DB-Operanden operandneutral

Der Compare-Pfad darf fuer `db:`-Operanden keine Klartext-Credentials in
Fehlertexten, Validation- oder Diff-Dokumenten ausgeben.

Verbindliche Folge:

- fuer jeden Operand wird genau eine user-facing Referenz gebildet
- Dateioperanden duerfen als Pfad erscheinen
- DB-URL-Operanden muessen ueber den zentralen Scrubbing-Pfad maskiert werden
- Alias-Operanden duerfen alias-basiert erscheinen
- alle user-facing Fehlertexte laufen vor `printError(...)` bzw. vor einer
  strukturierten Fehlerausgabe durch denselben zentralen Scrubbing-Pfad
- Compare darf den bestehenden file-zentrierten Fehler-Envelope nicht fuer
  `db:`-Operanden unveraendert weiterverwenden
- Fehlerausgaben muessen operandneutral gerendert werden:
  - Plain: `Operand: ...` oder gleichwertig neutral statt `File: ...`
  - JSON/YAML: `operand` oder gleichwertig neutrales Feld statt `file`
- Fehlertexte und operandbezogene Dokumentfelder duerfen keine unmaskierte
  Connection-URL oder ungescrubbte sensitive Verbindungsdetails enthalten
- Exception-Messages aus Operand-Resolver, URL-Parser, Pool, Treiber-Registry,
  `SchemaReader.read(...)` oder Write-Pfaden duerfen nicht roh in die
  CLI-Ausgabe durchgereicht werden, wenn sie Verbindungsdetails enthalten

---

## 5. Arbeitspakete

### F.1 Compare-Operandtypen und `ResolvedSchemaOperand` einfuehren

Im Application-Modul ist ein expliziter Compare-Operandvertrag einzufuehren.

Mindestens noetig:

- Request-Typ, der `source` und `target` nicht mehr als nackte `Path`, sondern
  als Operand-Referenzen fuehrt
- der Request transportiert zusaetzlich mindestens:
  - `cliConfigPath`
  - `quiet`
  - `verbose`
  - `outputFormat`
- kanonische Operandtypen fuer:
  - Datei
  - Datenbank
- `ResolvedSchemaOperand` oder gleichwertiger Envelope mit mindestens:
  - `schema`
  - `validation`
  - `notes`
  - `skippedObjects`
  - user-facing Operand-Referenz
  - gescrubbtem user-facing Fehlertext fuer Fehlerpfade

Der Envelope ist bewusst Application-Layer-Verantwortung und kein Core-Typ.

### F.2 Operand-Resolver fuer Datei und DB symmetrisch verdrahten

Der Compare-Runner soll beide Operanden ueber denselben Resolver-Flow
aufloesen koennen.

Mindestens noetig:

- Parsing von `file:`- und `db:`-Operanden
- Rueckfall praefixloser Operanden auf Datei
- Datei-Read ueber den formatbewussten Schema-Datei-I/O-Pfad aus Phase C
- DB-Read ueber:
  - `NamedConnectionResolver`
  - `ConnectionUrlParser`
  - `HikariConnectionPoolFactory`
  - `DatabaseDriverRegistry.get(...).schemaReader()`
- Validierung des aufgeloesten Schemas pro Operand

Wichtig:

- DB-Operanden nutzen denselben Resolver-Baustein wie Reverse, aber ohne
  Export-Fallback auf `database.default_source`
- der Compare-Runner fuehrt keinen eigenen Dialektparser neben
  `ConnectionUrlParser` ein
- Datei- und DB-Operandpfade muessen auf denselben Envelope-Vertrag enden

### F.3 Reverse-Provenienz und Marker-Normalisierung anschliessen

Phase D hat die Reverse-Marker bewusst fuer spaeteren Compare eingefuehrt.

Mindestens erforderlich:

- Erkennung gueltiger Reverse-Marker auf allen aufgeloesten Operanden
- Normalisierung synthetischer `name`-/`version`-Werte vor dem Diff
- harter Fehlerpfad fuer unvollstaendige oder syntaktisch ungueltige Marker
- klare Trennung zwischen:
  - Operand-Provenienz
  - eigentlichem Schema-Diff

Zulaessig ist:

- einen dedizierten `CompareOperandNormalizer` oder gleichwertigen
  Application-Helper einzufuehren

Nicht akzeptabel ist:

- Marker-Erkennung in den Core-Comparator zu schieben
- reverse-synthetische Metadaten als echte Schemaaenderungen auszugeben

### F.4 `SchemaCompareRunner` auf symmetrische Operandenauflosung umstellen

Der bestehende Runner bleibt das Orchestrierungszentrum, wird aber auf den
neuen Operandvertrag erweitert.

Mindestens noetig:

- CLI-nahe Vorvalidierung fuer Operanden und `--output`
- symmetrische Aufloesung von `source` und `target`
- pro Operand Transport von:
  - user-facing Referenz
  - Validation-Ergebnis
  - Reverse-Notes / `skippedObjects`
- zentrales Scrubbing fuer user-facing Fehlertexte, bevor sie an
  `printError(...)` oder strukturierte Fehlerdokumente gehen
- operandneutraler Fehler-Renderer oder entsprechend verallgemeinerter
  `OutputFormatter`, damit `db:`-Fehler nicht unter `file`/`File:` erscheinen
- Weitergabe der normalisierten `SchemaDefinition` an `SchemaComparator`
- stdout-/stderr-Ausgabe gemaess `outputFormat`, `quiet` und mindestens
  `verbose`-gesteuerten Info-Notes
- sauberes Exit-Code-Mapping fuer Datei- vs. DB-Fehlerpfade

Wichtig:

- der bisherige file-based Compare-Pfad darf im Verhalten nicht regressieren
- der Runner bleibt Clikt-frei und ohne echte DB-/Dateisystemzugriffe
  unit-testbar

### F.5 CLI-Command und Help-Texte auf Operandnotation umstellen

Im Driving-Adapter ist `SchemaCompareCommand` von file-only auf den 0.6.0-
Operandvertrag anzuheben.

Mindestens erforderlich:

- `--source` und `--target` nicht mehr als reine Clikt-`path(...)`
- Help-Texte fuer:
  - `file:<path>`
  - `db:<url-or-alias>`
  - praefixlose Dateipfade als Rueckwaertskompatibilitaet
- Verdrahtung auf den erweiterten `SchemaCompareRunner`

Wichtig:

- die CLI darf `db:`-Operanden nicht durch lokale Dateipfadvalidierung
  vorzeitig abweisen
- Named Connections muessen fuer Compare explizit als `db:<alias>` beschrieben
  bleiben

### F.6 `SchemaCompareDocument`, Summary und Diff-View vervollstaendigen

Die bestehende CLI-Projektion muss auf den realen 0.6.0-Diff-Vertrag gezogen
werden.

Mindestens noetig:

- Summary-Zaehler fuer:
  - Sequences
  - Functions
  - Procedures
  - Triggers
- `DiffView`-Felder und Primitive-Projection fuer diese Objektarten
- Projektionspfade fuer compare-relevante neue 0.6.0-Metadaten
- strukturierte operandseitige Validation-/Reverse-Ergebnisfelder

Nicht akzeptabel ist:

- dass neue Core-Diffs nur intern vorhanden sind, aber im CLI-Dokument
  unsichtbar bleiben

### F.7 Plain-/JSON-/YAML-Renderer fuer erweiterte Diff- und Operanddaten ausbauen

Phase F muss sicherstellen, dass alle Ausgabeformate denselben
Informationsvertrag tragen.

Mindestens noetig:

- Plain-Renderer fuer:
  - Sequences
  - Functions
  - Procedures
  - Triggers
  - operandseitige Reverse-Notes / `skippedObjects`
- JSON-/YAML-Renderer mit denselben inhaltlichen Bereichen
- klare Trennung in der Ausgabe zwischen:
  - Operand-Referenz
  - Validation
  - Reverse-Notes / `skippedObjects`
  - Diff

Wichtig:

- warnings auf `stderr` bleiben wie heute auf `plain` beschraenkt
- operandseitige Reverse-Notes / `skippedObjects` folgen demselben Plain-only-
  `stderr`-Vertrag wie die bestehenden Validation-Warnings
- bestehende Plain-Validation-Warnings bleiben auch unter `quiet` sichtbar;
  dieselbe Regel gilt fuer operandseitige Reverse-Notes / `skippedObjects`
- operandseitige Info-Notes bleiben mindestens `verbose`-gesteuert
- strukturierte JSON-/YAML-Ausgabe darf keine parallele menschenlesbare
  Diff-Zusammenfassung auf `stderr` erzeugen
- `quiet` darf die primaere Compare-Ausgabe bei Exit `1` oder `3` nicht
  unterdruecken

### F.8 Tests fuer `file/file`, `file/db` und `db/db` aufbauen

Fuer Phase F sind mindestens folgende Testklassen einzuplanen:

- `SchemaCompareRunnerTest`
- `SchemaCompareHelpersTest`
- `CliSchemaCompareTest`
- ggf. Helper-Tests fuer:
  - Operand-Parsing
  - Marker-Normalisierung
  - operandseitiges Scrubbing

Abzusichern sind mindestens:

- erfolgreicher `file/file`-Compare bleibt unveraendert nutzbar
- erfolgreicher `file/db`-Compare
- erfolgreicher `db/db`-Compare
- praefixloser Dateioperand bleibt rueckwaertskompatibel
- `db:<alias>` und `db:<url>` werden korrekt aufgeloest
- Validation-Fehler auf einem oder beiden Operanden fuehren weiter zu Exit `3`
- Connection-/Metadatenfehler auf `db:`-Operanden fuehren zu Exit `4`
- Config-/URL-/Datei-/Write-Fehler fuehren zu Exit `7`
- Reverse-Notes und `skippedObjects` bleiben pro Operand im Ergebnis sichtbar
- reverse-synthetische Marker werden vor dem Diff fuer `file/file`,
  `file/db` und `db/db` normalisiert
- ungueltige Reverse-Marker fuehren zu hartem Fehler statt best effort
- JSON-/YAML-/Plain-Ausgabe deckt Sequences, Functions, Procedures und
  Triggers mit ab
- DB-Operand-Referenzen bleiben in Fehler- und Output-Dokumenten maskiert bzw.
  alias-basiert
- DB-Operand-Fehler erscheinen in Plain-/JSON-/YAML-Ausgaben unter einem
  operandneutralen Label statt unter `File:`/`file`
- user-facing Fehlertexte bleiben auch bei sensitiven Exception-Messages
  gescrubbt
- `quiet` und `verbose` steuern operandseitige Note-Ausgabe konsistent, ohne
  `different`- oder `invalid`-Resultate auf `stdout` zu unterdruecken; Plain-
  Warnings bleiben dabei wie im bestehenden Compare sichtbar

---

## 6. Technische Zielstruktur

Eine moegliche Minimalform fuer den Operandvertrag ist:

```kotlin
sealed interface SchemaCompareOperand {
    data class File(val path: Path) : SchemaCompareOperand
    data class Database(val source: String) : SchemaCompareOperand
}

data class ResolvedSchemaOperand(
    val reference: String,
    val schema: SchemaDefinition,
    val validation: ValidationResult,
    val notes: List<SchemaReadNote> = emptyList(),
    val skippedObjects: List<SkippedObject> = emptyList(),
)
```

Eine moegliche Minimalform fuer den Runner ist:

```kotlin
data class SchemaCompareRequest(
    val source: String,
    val target: String,
    val output: Path?,
    val cliConfigPath: Path? = null,
    val outputFormat: String,
    val quiet: Boolean,
    val verbose: Boolean = false,
)

class SchemaCompareRunner(
    private val operandResolver: (String) -> SchemaCompareOperand,
    private val operandLoader: (SchemaCompareOperand, Path?) -> ResolvedSchemaOperand,
    private val comparator: (SchemaDefinition, SchemaDefinition) -> SchemaDiff,
    private val projectDiff: (SchemaDiff) -> DiffView,
    ...
) {
    fun execute(request: SchemaCompareRequest): Int
}
```

Wichtiger als die exakte Kotlin-Form sind die Zielsemantiken:

- Compare bleibt modellbasiert statt SQL-textbasiert
- Datei- und DB-Operanden werden symmetrisch auf denselben Envelope-Typ
  gezogen
- Marker-Normalisierung passiert vor `SchemaComparator` fuer alle
  aufgeloesten Operanden, nicht nur fuer Dateiinputs
- Reverse-Notes und `skippedObjects` bleiben pro Operand transportierbar
- der erweiterte Core-Diff wird ohne Informationsverlust in CLI-Dokumente
  projiziert
- user-facing Operand-Referenzen und Fehlertexte bleiben fuer DB-Operanden
  gescrubbt; Fehlerausgaben bleiben operandneutral statt file-zentriert
- `quiet` unterdrueckt keine primaeren Compare-Dokumente mit Exit `1` oder `3`
  und laesst bestehende Plain-Warnings bewusst sichtbar
- Compare nutzt fuer Fehlerfaelle keinen DB-Operand-Pfad, der JSON/YAML weiter
  unter `file` oder Plain unter `File:` etikettiert

---

## 7. Betroffene Artefakte

Direkt betroffen oder neu einzufuehren:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`
- ggf. neuer Operand-Resolver oder Normalizer unter
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/`
- neue bzw. erweiterte Runner-Tests unter
  `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunnerTest.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpers.kt`
- neue bzw. erweiterte CLI-/Helper-Tests unter
  `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/`
- `docs/cli-spec.md`

Indirekt vorausgesetzt:

- `hexagon/ports/.../SchemaReader.kt`
- `adapters:driven:formats/...` fuer Schema-Datei-I/O
- Reverse-Reader in PostgreSQL, MySQL und SQLite
- Reverse-Provenienz- und Markerregeln aus Phase D

---

## 8. Akzeptanzkriterien

- [ ] `schema compare` akzeptiert `file/file`, `file/db` und `db/db`.
- [ ] Praefixlose Operanden bleiben rueckwaertskompatibel Dateipfade.
- [ ] `file:` und `db:` sind die kanonischen 0.6.0-Operand-Praefixe.
- [ ] `db:<alias>` wird im Compare-Pfad korrekt ueber Named Connections
      aufgeloest.
- [ ] Beide Operanden werden symmetrisch auf einen
      `ResolvedSchemaOperand(schema, validation, notes, skippedObjects)` oder
      gleichwertigen Envelope gezogen.
- [ ] Reverse-Notes und `skippedObjects` bleiben pro Operand transportierbar
      und gehen im Compare-Pfad nicht still verloren.
- [ ] Reverse-synthetische `__dmigrate_reverse__:`-Marker werden vor dem Diff
      fuer `file/file`, `file/db` und `db/db` normalisiert und nicht roh als
      Schema-Metadaten-Diff ausgegeben.
- [ ] Ungueltige oder unvollstaendige Reverse-Marker auf aufgeloesten
      Operanden fuehren zu explizitem Fehler statt best effort.
- [ ] Validation-Fehler bleiben fuer Datei- und DB-Operanden Exit `3`.
- [ ] Connection- und DB-Metadatenfehler auf `db:`-Operanden enden mit
      Exit `4`.
- [ ] Datei-, Parse-, Config-, URL- und Write-Fehler enden mit Exit `7`.
- [ ] `SchemaCompareSummary` deckt Tabellen, Custom Types, Views, Sequences,
      Functions, Procedures und Triggers ab.
- [ ] `DiffView` sowie Plain-/JSON-/YAML-Renderer machen dieselben
      0.6.0-Objektarten sichtbar.
- [ ] Compare-Ausgabe kann operandseitige Validation sowie Reverse-Notes /
      `skippedObjects` getrennt vom Diff transportieren.
- [ ] DB-Operand-Referenzen bleiben in Fehler- und Ergebnisdokumenten
      maskiert bzw. alias-basiert.
- [ ] DB-Operand-Fehler werden in Plain-/JSON-/YAML-Ausgaben operandneutral
      und nicht unter `File:`/`file` gerendert.
- [ ] User-facing Fehlertexte bleiben auch dann gescrubbt, wenn
      Exception-Messages URLs oder andere sensitive Verbindungsdetails
      enthalten.
- [ ] `quiet` und `verbose` steuern operandseitige Note-Ausgabe konsistent,
      ohne das primaere Compare-Dokument bei Exit `1` oder `3` zu
      unterdruecken; bestehende Plain-Warnings bleiben dabei sichtbar.
- [ ] Der bisherige file-based Compare-Pfad regressiert nicht.
- [ ] `docs/cli-spec.md` beschreibt denselben Compare-Vertrag wie der Code.

---

## 9. Verifikation

Mindestumfang fuer die Umsetzung:

1. Gezielter Testlauf fuer Core-, Application- und CLI-Pfad:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :hexagon:application:test :adapters:driving:cli:test" \
  -t d-migrate:phase-f .
```

2. Falls Phase D/E bereits produktiv verdrahtet sind, zusaetzlicher
   Driver-/CLI-Integrationslauf:

```bash
./scripts/test-integration-docker.sh
```

3. Manuelle CLI-Smokes:

```bash
d-migrate schema compare \
  --source schema-v1.yaml \
  --target schema-v2.yaml

d-migrate schema compare \
  --source file:/tmp/schema.yaml \
  --target db:staging \
  --output-format json

d-migrate schema compare \
  --source db:postgresql://user:pw@host/db1 \
  --target db:postgresql://user:pw@host/db2 \
  --output /tmp/compare.yaml \
  --output-format yaml
```

Dabei explizit pruefen:

- `file/file` verhaelt sich weiter wie im 0.5.0-Pfad
- `file/db` und `db/db` werden symmetrisch ueber denselben Operand-Resolver
  abgewickelt
- Validation-Fehler bleiben operandbezogen sichtbar
- `db:`-Operand-Fehler werden auf Exit `4` statt `7` geschnitten
- reverse-synthetische Marker fuehren in `file/file`, `file/db` und `db/db`
  nicht zu Scheinaenderungen in `schema.name` / `schema.version`
- JSON-/YAML-/Plain-Output zeigen Sequences, Functions, Procedures und
  Triggers mit an
- DB-Operand-Referenzen bleiben in Fehler- und Ergebnisdokumenten maskiert
- DB-Operand-Fehler erscheinen operandneutral statt unter `File:`/`file`
- Fehlertexte in CLI-Fehlerausgaben bleiben auch dann gescrubbt, wenn die
  zugrundeliegende Exception Verbindungsdetails enthaelt
- `quiet` unterdrueckt Hilfsausgaben, macht aber `different`- oder
  `invalid`-Compare ohne `--output` nicht still; bestehende Plain-Warnings
  bleiben sichtbar

---

## 10. Risiken und offene Punkte

### R1 - CLI-Help und Clikt-Typen koennen den 0.6.0-Operandvertrag weiter blockieren

Solange `SchemaCompareCommand` `--source` und `--target` als reine
`path(...)`-Optionen fuehrt, bleibt `db:` auf CLI-Ebene unbenutzbar, auch wenn
der Runner spaeter schon DB-Operanden koennte.

### R2 - Reverse-Notes gehen leicht verloren, wenn Compare nur auf `schema` reduziert

Ohne expliziten Operand-Envelope droht der DB-Operandpfad Reverse-Hinweise und
`skippedObjects` beim Uebergang von Reader zu Compare still abzuschneiden.

### R3 - Marker-Normalisierung darf nicht in den Core-Diff leaken

Wenn `SchemaComparator` Reverse-Provenienzlogik uebernimmt, vermischt der
Core Herkunftsregeln mit strukturellem Diff und verliert seine saubere
Wiederverwendbarkeit.

### R4 - CLI-Projektion kann hinter dem Core-Diff zurueckbleiben

Der Core deckt Sequenzen, Functions, Procedures und Triggers bereits ab. Wenn
Phase F nur die Operandauflosung liefert, aber die Renderer nicht nachzieht,
entsteht ein still abgeschnittener Compare-Vertrag.

### R5 - DB-Operand-Scrubbing muss ueber Fehler und Erfolgsdokumente konsistent sein

Ohne zentrale user-facing Operand-Referenz und gescrubbte Fehlertexte drohen
URL-basierte Operanden oder rohe Exception-Messages in Fehlern,
Validation-Hinweisen oder Ergebnisdokumenten unterschiedlich und im
schlechtesten Fall unmaskiert aufzutauchen.
