# Implementierungsplan: Phase E - CLI-Pfad `schema reverse`

> **Milestone**: 0.6.0 - Reverse-Engineering und Direkttransfer
> **Phase**: E (CLI-Pfad `schema reverse`)
> **Status**: Draft (2026-04-14)
> **Referenz**: `docs/implementation-plan-0.6.0.md` Abschnitt 4.1 bis 4.4,
> Abschnitt 5 Phase E, Abschnitt 6.1, Abschnitt 6.3, Abschnitt 7,
> Abschnitt 8, Abschnitt 9, Abschnitt 10; `docs/ImpPlan-0.6.0-B.md`;
> `docs/ImpPlan-0.6.0-C.md`; `docs/ImpPlan-0.6.0-D.md`;
> `docs/cli-spec.md`; `docs/connection-config-spec.md`

---

## 1. Ziel

Phase E haengt den in Phase B festgezogenen Reverse-Vertrag, die in Phase C
vorbereitete Schema-I/O-/Report-Infrastruktur und die in Phase D
bereitgestellten Dialekt-`SchemaReader` zu einem benutzbaren CLI-Kommando
zusammen.

Ergebnis der Phase ist ein produktiver, skriptfaehiger Pfad:

```bash
d-migrate schema reverse --source <url-or-alias> --output <path>
```

Die Phase fuehrt bewusst noch keinen DB-Operand-Compare und keinen
`data transfer`-Pfad ein. Sie macht ausschliesslich Reverse als
eigenstaendiges Kommando benutzbar.

Nach Phase E soll auf Application- und CLI-Ebene klar und testbar gelten:

- `schema reverse` existiert als regulaeres `schema`-Subcommand
- `--source` akzeptiert URL oder Named-Connection-Alias
- `--output` schreibt ein reines Schema-Artefakt in YAML oder JSON
- `--report` schreibt strukturierten Reverse-Report getrennt vom Schema
- Include-Flags werden in `SchemaReadOptions` verdrahtet
- Reverse-Notes und `skipped_objects` erscheinen konsistent auf `stderr`
  und im Report
- Fehlerpfade sind sauber auf Exit `2`, `4` und `7` getrennt

---

## 2. Ausgangslage

Aktueller Stand in `hexagon:application`, `adapters:driving:cli` und den
bereits bestehenden CLI-Pfaden:

- In `hexagon:application` existieren heute produktive Runner fuer:
  - `SchemaGenerateRunner`
  - `SchemaCompareRunner`
- Ein `SchemaReverseRunner` existiert noch nicht.
- Das bestehende Runner-Muster ist klar:
  - immutable Request-Datentyp
  - constructor-injected Kollaboratoren
  - keine direkte Clikt-Abhaengigkeit
  - unit-testbare Exit-Code-Entscheidungen
- `SchemaCommand` exponiert derzeit nur:
  - `validate`
  - `generate`
  - `compare`
- `SchemaCommands.kt` kennt noch kein `reverse`.
- `NamedConnectionResolver` kann heute direkte URLs und benannte
  Verbindungen aufloesen; fuer Reverse ist damit bereits ein passender
  Source-Aufloesungspfad vorhanden.
- `ConnectionUrlParser` und `HikariConnectionPoolFactory` existieren bereits
  und werden von `data export` bzw. `data import` produktiv genutzt.
- `OutputFormatter` kann heute nur:
  - Validierungsergebnisse rendern
  - generische Fehler rendern
- Ein Reverse-spezifischer stderr-Renderer fuer Notes und `skipped_objects`
  existiert noch nicht.
- `SchemaGenerateHelpers.sidecarPath(...)` existiert bereits, ist aber
  Generate-zentriert und noch kein allgemeiner Reverse-/Schema-Sidecar-Helper.
- `docs/cli-spec.md` beschreibt `schema reverse` bereits als geplantes
  Kommando, ist aber fuer Phase E noch nicht ganz auf dem finalen Vertrag:
  - es dokumentiert aktuell noch `--include-procedures` als Sammelbegriff
    fuer Procedures und Functions
  - es fuehrt fuer Reverse derzeit noch einen Exit-`5`-Pfad, waehrend der
    0.6.0-Masterplan fuer CLI-seitige Reverse-Fehler auf `2`, `4` und `7`
    schneidet
- Phase E haengt fachlich von Phase B bis D ab:
  - ohne `SchemaReadOptions`/`SchemaReadResult` aus Phase B gibt es keinen
    belastbaren Reverse-Vertrag
  - ohne Schema-I/O und Reverse-Report aus Phase C bleibt Reverse nur ein
    Read-only-Prototyp
  - ohne `SchemaReader` pro Dialekt aus Phase D gibt es keinen produktiven
    Datenquellenpfad

Konsequenz fuer Phase E:

- Die eigentliche CLI-Arbeit ist keine neue Fachlogik, sondern saubere
  Orchestrierung der bereits vorbereiteten Bausteine.
- Die Hauptfehlergefahr liegt nicht im JDBC-Lesen selbst, sondern in
  inkonsistenten CLI-Vertraegen fuer:
  - Source-Aufloesung
  - Dateiformat
  - Report-Sidecar
  - Exit-Codes
  - stderr-Verhalten

---

## 3. Scope fuer Phase E

### 3.1 In Scope

- Einfuehrung eines `SchemaReverseRequest` und `SchemaReverseRunner`
- Erweiterung von `SchemaCommands.kt` um `reverse`
- CLI-Flags fuer:
  - `--source`
  - `--output`
  - `--format`
  - `--report`
  - `--include-views`
  - `--include-procedures`
  - `--include-functions`
  - `--include-triggers`
  - `--include-all`
- Wiederverwendung von:
  - `NamedConnectionResolver`
  - `ConnectionUrlParser`
  - `HikariConnectionPoolFactory`
  - `DatabaseDriverRegistry`
- Verdrahtung der Include-Flags auf `SchemaReadOptions`
- Schreiben des Reverse-Schemas in YAML oder JSON ueber den Formatvertrag aus
  Phase C
- Schreiben des Reverse-Reports ueber den Reverse-Report-Writer aus Phase C
- Default-Sidecar-Pfad fuer den Report
- stderr-Ausgabe fuer Reverse-Notes und `skipped_objects`
- klare Exit-Code-Zuordnung fuer CLI-, Config-/I/O- und
  Connection-/Metadatenfehler
- Runner- und CLI-Tests fuer den neuen Pfad
- Nachfuehrung der regulaeren CLI-Dokumentation auf den finalen Phase-E-Vertrag

### 3.2 Bewusst nicht Teil von Phase E

- Implementierung der Dialekt-`SchemaReader` selbst
- Modell- oder Portarbeit aus Phase B
- Format-/Codec-Grundlagen aus Phase C
- `schema compare` fuer `file/db` und `db/db`
- `data transfer`
- SQL-Datei-Reverse, stdin-DDL oder `--source-dialect`
- neue globale CLI-Flags oder eine neue Root-Command-Struktur
- neue Dialekte jenseits PostgreSQL, MySQL und SQLite

---

## 4. Leitentscheidungen fuer Phase E

### 4.1 Reverse folgt dem bestehenden Runner-Muster

Phase E fuehrt keine Clikt-zentrierte Geschaeftslogik ein.

Verbindliche Folge:

- `SchemaReverseCommand` sammelt nur CLI-Argumente ein
- die gesamte Orchestrierung sitzt in `SchemaReverseRunner`
- externe Kollaboratoren werden constructor-injected
- Exit-Code- und Dateipfade bleiben ohne Clikt, ohne echte DB und ohne echtes
  Dateisystem unit-testbar

Damit bleibt Reverse konsistent mit `schema generate`, `schema compare`,
`data export` und `data import`.

### 4.2 `--source` ist verpflichtend und nutzt dieselben URL-/Alias-Aufloesungsbausteine wie die Datenpfade

Fuer Phase E gilt dieselbe URL-/Alias-Aufloesung wie bei den bestehenden
DB-Kommandos, aber ohne deren Export-Fallback-Semantik:

- enthaelt `--source` ein `://`, wird der Wert als direkte URL behandelt
- sonst wird ein Named-Connection-Alias aus `.d-migrate.yaml` ueber
  `NamedConnectionResolver.resolve(source)` aufgeloest

Verbindlich fuer 0.6.0:

- `--source` ist ein Pflichtflag
- Reverse nutzt **keinen** impliziten Fallback auf `database.default_source`
- Reverse wiederverwendet die URL-/Alias-Aufloesungsbausteine, aber nicht die
  Export-Semantik von `NamedConnectionResolver.resolveSource(source: String?)`
- Source-Aufloesung bleibt explizit, damit Reverse-Skripte eindeutig und
  reproduzierbar bleiben

### 4.3 `--format` steuert das Schema-Artefakt, nicht das CLI-Ausgabeformat

Der Reverse-Dateipfad muss dieselbe Formatpolitik wie Phase C verwenden.

Verbindliche Folge:

- `--format yaml|json` steuert die Serialisierung des Schema-Artefakts
- `yaml` bleibt Default
- `--output` muss eine dazu passende Dateiendung tragen
- das globale `--output-format` bleibt ein Darstellungsflag fuer CLI-Meldungen,
  Fehler und ein optionales Success-Dokument, nicht fuer das
  Schema-Artefakt selbst
- `--output-format json|yaml` serialisiert im Erfolgspfad ein strukturiertes
  CLI-Ergebnis nach `stdout`, das Artefaktpfade und Summary transportiert,
  aber nicht das Reverse-Schema in `--output` ersetzt

Phase E fuehrt keinen zweiten konkurrierenden Reverse-Dateiformatpfad ein.

### 4.4 Schema-Artefakt und Reverse-Report bleiben streng getrennt

Der Kernvertrag aus Phase B/C bleibt auch im CLI-Pfad unveraendert:

- `--output` enthaelt ausschliesslich das reverse-generierte Schema
- `--report` enthaelt `notes`, `skipped_objects` und Summary
- Reverse-Notes werden nicht ins Schema-Dokument eingebettet

Weil `--output` fuer `schema reverse` verpflichtend ist, gilt fuer Phase E:

- ohne explizites `--report` wird immer ein Default-Sidecar
  `<basename>.report.yaml` erzeugt
- `--output` und `--report` duerfen nicht auf denselben Pfad zeigen

### 4.5 Include-Flags spiegeln den Port-Vertrag, nicht alte CLI-Abkuerzungen

Phase B schneidet `SchemaReadOptions` explizit fuer:

- Views
- Procedures
- Functions
- Triggers

Verbindliche Folge fuer Phase E:

- die CLI spiegelt diese Granularitaet
- `--include-procedures` und `--include-functions` sind getrennte Flags
- `--include-all` ist nur ein Convenience-Flag im CLI-Layer
- der Runner ueberfuehrt die CLI-Flags deterministisch in
  `SchemaReadOptions`

Nicht akzeptabel ist ein unscharfer CLI-Vertrag, der Functions und Procedures
unter demselben Flag zusammenzieht, obwohl der Port sie getrennt fuehrt.

### 4.6 Reverse-Fehler werden fuer den CLI-Pfad auf `2`, `4` und `7` geschnitten

Phase E richtet den effektiven Reverse-CLI-Vertrag auf den 0.6.0-Masterplan
aus.

Verbindliche Zuordnung:

- Exit `2`:
  - ungueltige CLI-Argumente
  - widerspruechliche Include-Flags
  - unpassende `--format`-/Dateiendungs-Kombination
  - Kollision von `--output` und `--report`
- Exit `4`:
  - Connection-Fehler
  - Treiber-/Dialektfehler nach erfolgreicher URL-Aufloesung
  - DB-Metadatenfehler waehrend `SchemaReader.read(...)`
- Exit `7`:
  - Config-Aufloesungsfehler
  - ungueltige oder unbekannte URL-/Alias-Eingaben
  - Schreibfehler fuer Schema oder Report

Phase E fuehrt fuer `schema reverse` keinen zusaetzlichen Exit-`5`-Pfad ein.
Die bestehende CLI-Dokumentation ist an dieser Stelle nachzufuehren.

Zusaetzlich verbindlich:

- alle Fehlerpfade verwenden fuer user-facing Ausgaben nur eine maskierte bzw.
  alias-basierte Source-Referenz
- alle user-facing Fehlertexte laufen vor `printError(...)` bzw. vor der
  JSON-/YAML-Fehlerausgabe durch denselben zentralen Scrubbing-Pfad
- `OutputFormatter.printError(...)` oder ein gleichwertiger zentraler
  Fehlerpfad bekommt weder die unmaskierte, aufgeloeste Connection-URL noch
  einen unsanitized Exception-Text mit sensitiven Verbindungsdetails

### 4.7 Reverse-Notes erscheinen nur in `plain` auf `stderr`; Erfolgsausgabe folgt `--output-format`

Der Reverse-Pfad ist ein dateiorientiertes Kommando. Erfolgsartefakte liegen in
Dateien; `stdout` transportiert nur CLI-Erfolgsausgabe, nie das eigentliche
Schema-Artefakt.

Verbindliche Folge:

- das Schema wird ausschliesslich ueber `--output` geschrieben
- der Report wird ausschliesslich ueber `--report` bzw. Sidecar geschrieben
- menschenlesbare Notes und `skipped_objects` gehen nur bei
  `--output-format plain` auf `stderr`
- bei `--output-format plain` gehen Erfolgsmeldungen wie
  `Schema written to ...` / `Report written to ...` auf `stdout`
- bei `--output-format json|yaml` geht stattdessen ein strukturiertes
  Success-Dokument auf `stdout`, mindestens mit:
  - `command`
  - `status`
  - `exit_code`
  - maskierter bzw. alias-basierter `source`-Referenz
  - `output`-/`report`-Pfad
  - Summary-Zaehlern fuer Notes / `skipped_objects`
- bei `--output-format json|yaml` gibt es fuer erfolgreiche Runs keine
  zusaetzliche menschenlesbare Note-/`skipped_objects`-Ausgabe auf `stderr`;
  Details bleiben im Report, `stdout` traegt nur das strukturierte
  Success-Dokument
- `quiet` folgt dem bestehenden globalen CLI-Vertrag `Only show errors`:
  - Reverse-Warnungen, Info-Notes und `skipped_objects` werden unterdrueckt
  - menschenlesbare Plain-Erfolgsmeldungen werden unterdrueckt
  - strukturierte Success-Dokumente bei `--output-format json|yaml` werden
    ebenfalls unterdrueckt
  - Fehlerausgaben bleiben sichtbar

### 4.8 Quellreferenzen und Fehlertexte bleiben in Report-, Fehler- und Success-Ausgaben gescrubbt

Der CLI-Pfad darf keine Klartext-Credentials ueber Reverse-Reports oder
CLI-Fehler- bzw. Success-Ausgaben leaken.

Verbindliche Folge:

- der Runner bildet nach der Source-Aufloesung genau eine user-facing
  Source-Referenz fuer Report-, Fehler- und Success-Ausgaben
- der Runner bildet fuer Fehlerfaelle einen user-facing Fehlertext, der vor
  jeder Ausgabe zentral gescrubbt wird
- der Runner uebergibt dem Reverse-Report-Writer den Phase-C-Vertrag
  `SchemaReadReportInput` inklusive `ReverseSourceRef`
- Aliasquellen duerfen im Report unveraendert erscheinen
- URL-Quellen muessen fuer alle user-facing Ausgaben ueber den zentralen
  Scrubbing-Pfad maskiert werden
- die aufgeloeste Klartext-URL darf nur innerhalb von URL-Parser, Pooling und
  DB-Zugriffspfad weitergereicht werden, nicht in `printError(...)`,
  JSON-/YAML-Fehlerdokumente oder Success-Ausgaben
- Exception-Messages aus URL-Parser, Pool, Treiber-Registry oder
  `SchemaReader.read(...)` duerfen nicht roh in die CLI-Ausgabe durchgereicht
  werden, wenn sie sensitive Verbindungsdetails enthalten koennen

---

## 5. Arbeitspakete

### E.1 `SchemaReverseRequest` und `SchemaReverseRunner` einfuehren

Im Application-Modul ist ein neuer Runner analog zu den bestehenden
Schema-/Daten-Runnern einzufuehren.

Mindestens noetig:

- Request-Datentyp fuer:
  - `source`
  - `output`
  - `format`
  - `report`
  - Include-Flags
  - `cliConfigPath`
  - `quiet`
  - `verbose`
  - `outputFormat`
- constructor-injected Kollaboratoren fuer:
  - Source-Aufloesung
  - maskierte Source-Referenzbildung fuer Report/Fehler/Success-Ausgaben
  - Scrubbing von user-facing Fehlertexten
  - URL-Parsing
  - Pool-Erzeugung
  - Driver-/`SchemaReader`-Lookup
  - formatbewusste Schema-Datei-I/O-Aufloesung
  - Reverse-Report-Writing
  - Sidecar-Pfadbildung
  - stderr-Rendering
  - Fehlerausgabe

Der Runner koordiniert:

1. CLI-nahe Vorvalidierung
2. Source-Aufloesung und user-facing Source-Referenzbildung
3. URL-Parsing und Dialektbestimmung
4. Pool-Erzeugung
5. `SchemaReader.read(pool, options)`
6. Schreiben von Schema und Report
7. stdout-/stderr-Ausgabe gemaess `--output-format`, `quiet` und Reverse-Notes
8. finale Exit-Code-Mapping-Entscheidung

### E.2 `SchemaCommands.kt` um `reverse` erweitern

Im Driving-Adapter ist ein neues Clikt-Command einzufuehren.

Mindestens erforderlich:

- neues `SchemaReverseCommand`
- Registrierung unter `SchemaCommand`
- Help-Texte fuer alle Reverse-Flags
- Verdrahtung auf `SchemaReverseRunner`

Wichtig:

- Help-Texte muessen `Schema-Datei (YAML/JSON)` bzw.
  `Output schema format: yaml|json` korrekt benennen
- die CLI darf nicht weiter Reverse als YAML-only oder parserfaehigen
  SQL-Dateipfad andeuten

### E.3 Source-Aufloesung, Dialekt-Lookup und Pool-Lifecycle verdrahten

Der neue CLI-Pfad soll den bestehenden DB-Unterbau maximal wiederverwenden.

Mindestens noetig:

- `NamedConnectionResolver.resolve(source)` fuer Alias-/URL-Aufloesung
- `ConnectionUrlParser.parse(...)` fuer Dialektbestimmung
- `HikariConnectionPoolFactory.create(...)` fuer den Reverse-Read-Pool
- `DatabaseDriverRegistry.get(dialect).schemaReader()` fuer den eigentlichen
  Read-Port

Wichtig:

- Reverse nutzt denselben Resolver-Baustein wie `data export`, aber explizit
  **ohne** `resolveSource(source: String?)` und dessen
  `database.default_source`-Fallback
- die CLI fuehrt keinen eigenen Dialekt-Parser neben `ConnectionUrlParser`
  ein
- Pool-Ownership bleibt im Runner; `SchemaReader` bleibt Owner des
  Connection-Borrow-/Close-Zyklus innerhalb des Reads

### E.4 Schema-Datei-Writing ueber den Formatvertrag aus Phase C anschliessen

Reverse darf nicht mit ad-hoc-JSON oder ad-hoc-YAML aus dem CLI schreiben.

Verbindlich:

- der Runner loest ueber den formatbewussten Schema-Datei-I/O-Resolver aus
  Phase C einen konkreten Schema-Writer fuer `request.output` und
  `request.format`
- YAML und JSON sind gleichwertige Reverse-Ausgabeformate
- Endungs- und Formatmismatch fuehren vor dem eigentlichen DB-Read zu Exit `2`
- Schreibfehler fuehren zu Exit `7`

Wichtig:

- Serialisierungs- und Format-Dispatch bleiben in `adapters:driven:formats`
  und wandern nicht als rohes `(Path, format, SchemaDefinition)`-Protokoll in
  den Runner
- das geschriebene Artefakt bleibt eine regulaere `SchemaDefinition`
- die Datei muss spaeter von den file-based Pfaden wieder konsumierbar sein

### E.5 Reverse-Report und Sidecar-Pfad verdrahten

Der Reverse-Pfad braucht denselben Sidecar-Komfort wie `schema generate`, aber
mit Reverse-spezifischem Reportvertrag.

Mindestens erforderlich:

- Verdrahtung des Reverse-Report-Writers aus Phase C
- Default-Pfad `<basename>.report.yaml`
- Kollisionserkennung zwischen `--output` und `--report`
- Erfolgsmeldung fuer Schema und Report nur ausserhalb von `quiet`
- strukturierte Success-Ausgabe fuer `--output-format json|yaml`, die auf
  Datei-Artefakte verweist statt sie zu duplizieren, aber wie alle anderen
  Nicht-Fehler-Ausgaben durch `quiet` unterdrueckt wird

Zulaessig ist:

- den bisherigen `SchemaGenerateHelpers.sidecarPath(...)` in einen
  gemeinsamen Schema-Sidecar-Helper zu ueberfuehren

Nicht akzeptabel ist:

- Reverse-Report ueber `TransformationReportWriter` zu schreiben
- zwei konkurrierende Sidecar-Pfadregeln fuer Generate und Reverse zu pflegen

### E.6 Reverse-Notes und `skipped_objects` konsistent im `plain`-Stderr rendern

Phase E muss die CLI-konsumierbare Kurzsicht auf Reverse-Ergebnisse definieren.

Mindestens noetig:

- menschenlesbare Darstellung fuer:
  - Warning-Notes
  - Action-Required-Notes
  - Info-Notes (mindestens `verbose`-gesteuert)
  - `skipped_objects`
- ein klarer Success-Output-Vertrag:
  - `plain` -> menschenlesbare Erfolgsmeldungen auf `stdout` und
    menschenlesbare Notes / `skipped_objects` auf `stderr`
  - `json|yaml` -> strukturiertes Success-Dokument auf `stdout`, keine
    zusaetzliche menschenlesbare Note-Ausgabe auf `stderr`
  - `quiet` -> keine Success-Ausgabe und keine Reverse-Notes; nur Fehler
- dieselbe inhaltliche Semantik wie im Report:
  - kein stiller Verlust
  - keine freierfundene Zusatzinterpretation
- Fehlerausgaben weiter ueber den bestehenden `OutputFormatter` oder einen
  gleichwertig zentralen Pfad
- keine user-facing Ausgabe arbeitet mit unmaskierter URL-Quelle oder
  ungescrubbtem Fehlertext

### E.7 Tests fuer Runner, Command und Fehlerpfade aufbauen

Fuer Phase E sind mindestens folgende Testklassen einzuplanen:

- `SchemaReverseRunnerTest`
- `CliSchemaReverseTest`
- ggf. Helper-Tests fuer:
  - Include-Flag-Mapping
  - Sidecar-Pfade
  - stderr-Rendering

Abzusichern sind mindestens:

- erfolgreicher Reverse mit URL
- erfolgreicher Reverse mit Named-Connection-Alias
- Default-Sidecar ohne explizites `--report`
- `--format json` mit passender `.json`-Datei
- `--output-format json|yaml` liefert ein strukturiertes Success-Dokument,
  waehrend Schema und Report Datei-Artefakte bleiben, inklusive maskierter bzw.
  alias-basierter `source`-Referenz
- `--output-format json|yaml` unterdrueckt die menschenlesbare Reverse-Note-
  Ausgabe auf `stderr` fuer erfolgreiche Runs
- Format-/Endungs-Mismatch
- Output-/Report-Kollision
- Config-Fehler
- Connection-/Metadatenfehler
- URL-Quelle bleibt in CLI-Fehlerausgaben maskiert
- user-facing Fehlertexte bleiben auch dann gescrubbt, wenn Exception-Messages
  URLs oder andere sensitive Verbindungsdetails enthalten
- `quiet` unterdrueckt Reverse-Notes und Success-Ausgaben, laesst aber Fehler
  sichtbar
- Include-Flags einzeln und ueber `--include-all`
- `quiet` versus `verbose`

---

## 6. Technische Zielstruktur

Eine moegliche Minimalform fuer den Runner ist:

```kotlin
data class SchemaReverseRequest(
    val source: String,
    val output: Path,
    val format: String = "yaml",
    val report: Path? = null,
    val includeViews: Boolean = false,
    val includeProcedures: Boolean = false,
    val includeFunctions: Boolean = false,
    val includeTriggers: Boolean = false,
    val includeAll: Boolean = false,
    val cliConfigPath: Path? = null,
    val outputFormat: String = "plain",
    val quiet: Boolean = false,
    val verbose: Boolean = false,
)

class SchemaReverseRunner(
    private val sourceResolver: (String, Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val schemaReaderLookup: (DatabaseDialect) -> SchemaReader,
    private val schemaFileWriterResolver: (Path, String) -> (SchemaDefinition) -> Unit,
    private val reportWriter: (Path, SchemaReadReportInput) -> Unit,
    ...
) {
    fun execute(request: SchemaReverseRequest): Int
}
```

Eine moegliche Minimalform fuer die Command-Seite ist:

```kotlin
class SchemaReverseCommand : CliktCommand(name = "reverse") {
    val source by option("--source").required()
    val output by option("--output").path().required()
    val format by option("--format").choice("yaml", "json").default("yaml")
    val report by option("--report").path()
    val includeViews by option("--include-views").flag()
    val includeProcedures by option("--include-procedures").flag()
    val includeFunctions by option("--include-functions").flag()
    val includeTriggers by option("--include-triggers").flag()
    val includeAll by option("--include-all").flag()
}
```

Wichtiger als die exakte Kotlin-Form sind die Zielsemantiken:

- Reverse bleibt dateiorientiert statt stdout-orientiert
- der Runner ist testbar und Clikt-frei
- die CLI spiegelt den Port-Vertrag fuer optionale Objekte sauber
- Report und Schema bleiben getrennte Artefakte
- `urlParser`/`poolFactory` folgen dem bestehenden Connection-Layer direkt ueber
  `ConnectionConfig`; Phase E fuehrt keinen Zwischen-Typ neben
  `ConnectionUrlParser.parse(...)` und `HikariConnectionPoolFactory.create(...)`
  ein
- `schemaFileWriterResolver` steht fuer den formatbewussten Phase-C-Datei-I/O-
  Vertrag; der Runner loest darueber einen Writer auf und uebergibt diesem nur
  noch die `SchemaDefinition`
- user-facing Source-Referenzen werden einmal zentral gebildet und fuer
  Report-, Fehler- und Success-Ausgaben nur maskiert bzw. alias-basiert
  weitergereicht
- `--output-format json|yaml` liefert ein strukturiertes Success-Dokument auf
  `stdout`, ohne dass Reverse-Schema oder Reverse-Report aus ihren Dateien auf
  die Konsole gespiegelt werden; unter `quiet` entfaellt auch dieses
  Success-Dokument
- Source-Aufloesung, Pooling und Driver-Lookup werden wiederverwendet statt
  neu erfunden

---

## 7. Betroffene Artefakte

Direkt betroffen oder neu einzufuehren:

- neuer Runner unter
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaReverseRunner.kt`
- neue Runner-Tests unter
  `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/SchemaReverseRunnerTest.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`
- neuer CLI-Test unter
  `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaReverseTest.kt`
- ggf. neuer Reverse-Helper unter
  `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaReverseHelpers.kt`
- ggf. gemeinsamer Schema-Sidecar-Helper, falls aus `SchemaGenerateHelpers`
  extrahiert
- `docs/cli-spec.md`

Indirekt vorausgesetzt:

- `hexagon/ports/.../SchemaReader.kt`
- `adapters/driven/formats/...` fuer Schema-I/O und Reverse-Report
- Dialekt-`SchemaReader` in PostgreSQL, MySQL und SQLite

---

## 8. Akzeptanzkriterien

- [ ] `d-migrate schema reverse` ist als Subcommand unter `schema`
      registriert.
- [ ] `--source` ist Pflicht und akzeptiert URL oder Named-Connection-Alias.
- [ ] `--output` ist Pflicht und schreibt ein reines Schema-Artefakt.
- [ ] `--format yaml|json` wird unterstuetzt; Default ist `yaml`.
- [ ] Reverse schreibt das Schema-Artefakt ohne eingebettete Notes oder
      `skipped_objects`.
- [ ] Reverse schreibt einen strukturierten Report getrennt vom Schema.
- [ ] Ohne explizites `--report` wird ein Default-Sidecar
      `<basename>.report.yaml` erzeugt.
- [ ] `--include-views`, `--include-procedures`, `--include-functions` und
      `--include-triggers` werden korrekt in `SchemaReadOptions` abgebildet.
- [ ] `--include-all` aktiviert alle optionalen Objektarten.
- [ ] Reverse-Notes und `skipped_objects` erscheinen konsistent auf `stderr`
      und im Report; auf `stderr` jedoch nur im `plain`-Modus und ausserhalb
      von `quiet`.
- [ ] Reverse mit Alias-Quelle leakt keine Credentials.
- [ ] Reverse mit URL-Quelle maskiert Credentials im Report und in
      CLI-Fehler- und Success-Ausgaben.
- [ ] User-facing Fehlertexte bleiben auch dann gescrubbt, wenn
      Exception-Messages URLs oder andere sensitive Verbindungsdetails
      enthalten.
- [ ] `--output-format json|yaml` liefert im Erfolgspfad ein strukturiertes
      Success-Dokument auf `stdout`, ohne das Schema-Artefakt aus `--output`
      zu duplizieren, und verwendet dabei nur eine maskierte bzw.
      alias-basierte `source`-Referenz.
- [ ] `--output-format json|yaml` emittiert fuer erfolgreiche Runs keine
      zusaetzliche menschenlesbare Reverse-Note-Ausgabe auf `stderr`.
- [ ] `--quiet` unterdrueckt fuer `schema reverse` alle Nicht-Fehler-Ausgaben
      einschliesslich Reverse-Notes und strukturierter Success-Dokumente.
- [ ] CLI-/Pfadvalidierungsfehler enden mit Exit `2`.
- [ ] Connection- und DB-Metadatenfehler enden mit Exit `4`.
- [ ] Config-, URL- und Schreibfehler enden mit Exit `7`.
- [ ] `docs/cli-spec.md` beschreibt denselben Reverse-Vertrag wie der Code.

---

## 9. Verifikation

Mindestumfang fuer die Umsetzung:

1. Gezielter Testlauf fuer Application-, CLI- und Formatpfad:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:application:test :adapters:driving:cli:test :adapters:driven:formats:test" \
  -t d-migrate:phase-e .
```

2. Falls Phase D bereits produktiv verdrahtet ist, zusaetzlicher Driver-/CLI-
   Integrationslauf:

```bash
./scripts/test-integration-docker.sh
```

3. Manuelle CLI-Smokes:

```bash
d-migrate schema reverse \
  --source staging \
  --output /tmp/reverse.yaml

d-migrate schema reverse \
  --source postgresql://user:pw@host/db \
  --format json \
  --output /tmp/reverse.json \
  --report /tmp/reverse.report.yaml \
  --output-format json \
  --include-views \
  --include-functions \
  --include-triggers
```

Dabei explizit pruefen:

- Schema-Datei ist gueltige YAML-/JSON-`SchemaDefinition`
- Report und `stderr` zeigen dieselben Notes / `skipped_objects`
  im `plain`-Modus und ausserhalb von `quiet`
- `--output-format json|yaml` liefert ein strukturiertes Success-Dokument auf
  `stdout`, waehrend Schema und Report nur in Dateien liegen
- die `source`-Referenz im strukturierten Success-Dokument ist bei Aliasquellen
  alias-basiert und bei URL-Quellen maskiert
- `--output-format json|yaml` erzeugt fuer erfolgreiche Runs keine
  zusaetzliche menschenlesbare Note-/`skipped_objects`-Ausgabe auf `stderr`
- `quiet` unterdrueckt Reverse-Warnungen, `skipped_objects` und
  Success-Ausgaben, aber nicht Fehler
- Format-/Endungsfehler werden ohne DB-Verbindungsversuch abgefangen
- Fehlertexte in CLI-Fehlerausgaben bleiben auch dann gescrubbt, wenn die
  zugrundeliegende Exception eine URL oder sensitive Verbindungsdetails
  enthaelt
- Alias- und URL-Quelle werden in Report, Fehler- und Success-Ausgaben korrekt
  und ohne Credential-Leak referenziert

---

## 10. Risiken und offene Punkte

### R1 - Exit-Code-Konflikt zwischen Masterplan und aktueller CLI-Spezifikation

`docs/cli-spec.md` dokumentiert aktuell fuer `schema reverse` noch einen
separaten Exit-`5`-Pfad. Phase E muss den finalen CLI-Vertrag auf einen
konsistenten Satz ziehen und diese Altspur schliessen.

### R2 - Include-Flag-Namen koennen zwischen Port und CLI auseinanderlaufen

Sobald `SchemaReadOptions` Functions und Procedures getrennt fuehrt, ist ein
Sammelflag `--include-procedures` fachlich unscharf. Phase E muss diese
Granularitaet im CLI sichtbar machen.

### R3 - Phase E haengt stark an Phase C

Wenn formatbewusster Schema-I/O oder der Reverse-Report-Writer aus Phase C
nicht belastbar stehen, droht Phase E in provisorische CLI-Serialisierung
abzugleiten. Das waere eine bewusste Fehlrichtung.

### R4 - Report-/Output-Pfadkollisionen sind leicht zu uebersehen

Ohne fruehe Pfadvalidierung kann der Nutzer versehentlich Schema und Report auf
dasselbe Ziel schreiben. Das muss im Runner vor dem eigentlichen Read
abgefangen werden.

### R5 - stderr-Semantik darf nicht vom Report abweichen

Wenn `stderr` nur einen Teil der Notes zeigt oder Inhalte anders gruppiert als
der Report, entstehen zwei konkurrierende Wahrheiten. Phase E muss deshalb die
menschenlesbare Kurzsicht bewusst aus demselben Ergebnisvertrag ableiten.
