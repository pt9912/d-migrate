---
status: accepted
date: 2026-07-17
decision-makers: pt9912
consulted: spec/architecture.md, spec/lastenheft-d-migrate.md, docs/adr/0022-ports-jdbc-entkopplung.md, docs/adr/0028-a-check-architecture-gate-scope.md, docs/adr/0015-fulltext-tsvector-neutral-type.md, docs/planning/open/jdbc-kopplung-der-ports-ist-aufnahme.md
informed: hexagon/ports, hexagon/ports-common, hexagon/ports-read, hexagon/ports-write, hexagon/profiling, adapters/driven/driver-common, adapters/driven/formats, adapters/driven/streaming
---

# „Database-Agnostic First": Zielbild bestätigt, Umsetzung nach 1.0.0

> **Status: accepted (2026-07-17).** Diese ADR entscheidet **nicht**, wie die Ports
> datenbank-agnostisch werden. Sie entscheidet zwei vorgelagerte Fragen: (1) Das Leitprinzip
> „Database-Agnostic First" **bleibt verbindliches Zielbild** — es wird *nicht* nachträglich auf
> „relationale Datenbanken über JDBC" eingegrenzt. (2) Eingelöst wird es **nach 1.0.0**, als eigenes
> Architekturvorhaben. Bis dahin ist die JDBC-Bindung des Treiber-Ports eine **benannte, ratifizierte
> Ausnahme** statt eines unbemerkten Widerspruchs.
>
> Der Konjunktiv in „Konsequenzen" ist beabsichtigt: die Entscheidung ist getroffen, ihre Umsetzung
> steht aus (s. „Entscheidung"), die dort beschriebenen Wirkungen treten erst mit ihr ein.

## Kontext und Problemstellung

[`spec/architecture.md`](../../spec/architecture.md) führt als Leitprinzip, **unqualifiziert**:

> **Database-Agnostic First**: Alle internen Datenstrukturen sind datenbankunabhängig;
> datenbankspezifisches Verhalten lebt ausschließlich in austauschbaren Adaptern.

Weder [`lastenheft-d-migrate.md`](../../spec/lastenheft-d-migrate.md) noch
[`architecture.md`](../../spec/architecture.md) enthalten das Wort „relational" — d-migrate grenzt
sich nirgends normativ auf relationale Datenbanken ein. Nicht-SQL-Datenbanken (MongoDB, Redis,
Cassandra, Neo4j, Elasticsearch) kommen in `spec/` und `docs/` nicht vor; die einzige Erwähnung
(Redis in [`port-atomicity.md`](../../spec/port-atomicity.md)) betrifft ein alternatives Backend
für den Server-State, kein Migrationsziel.

**Der Ist-Stand widerspricht dem Leitprinzip** (verifiziert 2026-07-17). `DatabaseDriver`
([`hexagon:ports`](../../hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt))
verlangt von **jedem** Treiber, ohne Default:

| Pflichtmitglied | Setzt voraus |
| --- | --- |
| `urlBuilder(): JdbcUrlBuilder` | eine JDBC-URL |
| `ddlGenerator(): DdlGenerator` | SQL-DDL |
| `tableLister(): TableLister` | Tabellen |
| `schemaReader(): SchemaReader` | ein relationales Schema |
| `dataReader(): DataReader` | einen SQL-Lesepfad |
| `dataWriter(): DataWriter` | einen SQL-Schreibpfad |

Formal ist `JdbcUrlBuilder` ein Interface, ein Nicht-JDBC-Treiber *könnte* es also implementieren.
Praktisch müsste er aus einer Methode namens `baseJdbcUrl` etwas zurückgeben, das keine JDBC-URL
ist, und einen `DdlGenerator` liefern, der wirft. **Der Port abstrahiert nicht, er diktiert** — er
ist nur mit Lügen erfüllbar. Ein Redis- oder Neo4j-Adapter ist damit nicht „noch nicht gebaut",
sondern vom Vertrag ausgeschlossen.

**Der Port kann Optionalität allerdings ausdrücken — er tut es nur selten.** `dataReader(fetchSize:
Int?)` trägt einen Default (`= dataReader()`), dessen KDoc ausdrücklich festhält, dass die
Default-Implementierung den Wert ignoriert und „nur die JDBC-Treiber" ihn auswerten. Dieselbe
Bauform haben `transferCompatibility()`, `typeCanonicalizer()` und `preGenerationValidator()`. Das
ist genau die Gestalt, auf die das Post-1.0.0-Vorhaben hinauswill: eine Fähigkeit, die ein Treiber
anbieten **kann**, statt einer, die er erfüllen **muss**. Die Bauform existiert also bereits — sie
ist nur an den sechs tragenden Mitgliedern nicht angewandt.

Dieselbe Krankheit in kleinerer Ausprägung tragen die Typcode-Felder:
`TargetColumn.jdbcType: Int` (`ports-write`), `JdbcTypeHint.jdbcType: Int` und `JdbcTypeCodes`
(29 Konstanten mit den Werten von `java.sql.Types`, `ports-common`). Sie sind **Symptome
derselben Ursache**, nicht die Ursache.

Bemerkenswert: [ADR 0022](0022-ports-jdbc-entkopplung.md) hat genau diese Bewegung begonnen — es
ersetzte `java.sql.Connection` durch ein neutrales `DatabaseConnection`, „das **genau** die von den
Ports benötigten Fähigkeiten trägt". Dieselbe Logik auf `urlBuilder()` angewandt hätte
`JdbcUrlBuilder` erfasst. Die Verbindung wurde neutralisiert, die URL blieb stehen.

Der Abstand blieb dabei nicht unbemerkt, sondern wurde ins Zielbild geschrieben: `spec/architecture.md`
trug bis 2026-07-17 die Aussage, `jdbcType: Int` bleibe „vorerst eine eng begrenzte
Interop-/Persistenz-Ausnahme" und eine vollständige Neutralisierung sei „ein eigener G2-Slice" — ein
Statusmarker und ein Abwärtsverweis auf einen Plan, beides in einem Zielbild konventionswidrig. Die
Passage ist entfernt (unabhängig vom Ausgang dieser ADR, weil sie in jedem Fall dort nicht hingehört).
Sie ist hier erwähnt, weil sie die Diagnose stützt: Das Zielbild wurde an die Umsetzung abgesenkt,
statt den Abstand als Abstand zu führen.

**Frage dieser ADR:** Bleibt „Database-Agnostic First" verbindliches Zielbild — und wann wird der
Abstand geschlossen?

## Entscheidungstreiber

- **Ehrlichkeit des Zielbilds.** Ein Leitprinzip, das der Code an seiner zentralsten Stelle nicht
  hält, ist entweder falsch formuliert oder uneingelöst. Beides ist erträglich; **unbemerkt** ist
  es nicht.
- **Stabilität der 1.0.0-Linie.** Die RC-Linie ist feature-komplett. Ein Umbau des Treiber-Ports
  kurz vor Stable ist ein Regressionsrisiko ohne Anwendernutzen.
- **Verhältnismäßigkeit.** Kein Anwender fordert heute MongoDB; die geplanten Erweiterungen
  (MS SQL Server, Oracle) sind JDBC-Datenbanken und passen in den Ist-Port. Gegenzurechnen ist
  aber, dass Vertagen nicht kostenneutral ist (s. „Konsequenzen/negativ": das Parquet-Manifest
  liefert mit 1.0.0 ein Schema aus, das `jdbcType` deklariert).
- **Keine Symptombehandlung.** An `jdbcType` zu operieren, während `urlBuilder(): JdbcUrlBuilder`
  Pflicht bleibt, ändert an der Austauschbarkeit nichts.

## Betrachtete Optionen

- **Z1 — Zielbild eingrenzen.** „d-migrate migriert relationale Datenbanken über JDBC" wird in die
  Spec geschrieben, das Leitprinzip entsprechend qualifiziert. Der Widerspruch verschwindet, indem
  der Anspruch sinkt.
- **Z2 — Zielbild halten, Umsetzung nach 1.0.0.** Der Abstand wird benannt und terminiert.
- **Z3 — Zielbild halten, jetzt umsetzen.** Der Treiber-Port wird vor 1.0.0 auf optionale
  Fähigkeiten umgestellt.

## Entscheidung

**Gewählt: Z2.**

Das Leitprinzip bleibt unverändert und unqualifiziert gültig; es wird **nicht** auf „relational über
JDBC" heruntergestuft. Die Einlösung erfolgt **nach 1.0.0** als eigenes Architekturvorhaben, dessen
Prüfstein die genannten Nicht-SQL-Datenbanken sind: Erst wenn ein Adapter für eine von ihnen
schreibbar ist, ohne den Vertrag anzulügen, ist das Prinzip eingelöst.

Bis dahin gilt die JDBC-Bindung des Treiber-Ports als **benannte, ratifizierte Ausnahme**. Der
Unterschied zum Status quo ist nicht kosmetisch: Heute ist der Widerspruch **latent** — die Spec
verspricht Agnostik, der Port diktiert JDBC, und nichts dokumentiert das. Nach dieser ADR ist er
**benannt**, mit Grund und Fälligkeit.

Z1 wurde verworfen, weil die Eingrenzung eine echte Fähigkeit aufgäbe, die das Projekt als
Leitprinzip führt — und zwar nur, um einen unbequemen Abstand zum Verschwinden zu bringen.

Z3 wurde verworfen, **soweit es den Treiber-Port betrifft**: Ihn vor 1.0.0 auf optionale Fähigkeiten
umzustellen, tauschte die feature-komplette RC-Linie gegen ein Vorhaben, dessen Nutzen erst mit dem
ersten Nicht-SQL-Adapter anfällt — den niemand angefordert hat.

Der Verwerfungsgrund trägt jedoch **nicht** für den `formats`-Zweig: dort ist der Schmerz heute
belegbar (s. u.), es geht also nicht um Nutzen ohne Nachfrage. Ein schmaler Vorzieh-Slice scheitert
dort an einer anderen Ursache — `formats` bezieht seinen Dispatch-Schlüssel über den Port aus
`driver-common` und hat dorthin keine Compile-Kante. Jede Ablösung muss deshalb dieselbe Frage
beantworten wie das Gesamtvorhaben (woher kommt der Schlüssel, wenn nicht aus einem Port-Feld) und
wäre kein Vorziehen, sondern dessen erster Akt — mitten in der RC-Linie. Deshalb auch hier: nach
1.0.0, aber als **erster** Angriffspunkt.

## Was daraus für „G2" folgt

Der Auslöser dieser ADR war der Einwand, im Hexagon solle kein JDBC stecken — konkret
`jdbcType: Int`. Aus der Ursachenlage folgt: **`jdbcType` ist ein Symptom und taugt nicht als
eigenständiges Vorhaben.** Es aus `TargetColumn` zu entfernen ändert an der Unmöglichkeit eines
MongoDB-Adapters nichts, solange `urlBuilder(): JdbcUrlBuilder` Pflicht ist.

Drei Heilmittel wurden ausformuliert und am Code widerlegt. Sie sind hier festgehalten, damit sie
nicht erneut vorgeschlagen werden:

- **`NeutralType` im Port statt `jdbcType: Int`** — `NeutralType` ist ein geschlossenes
  **Autoren**-Vokabular (was d-migrate erzeugen kann), `TargetColumn` beschreibt eine **fremde**
  Tabelle. `NCHAR`/`BLOB`/`SQLXML` fielen auf `Text`, obwohl die Prüfung sie heute akzeptiert;
  `BIT(8)` würde angenommen, obwohl Multi-Bit heute abgelehnt wird. Zudem kehrte es bei mehreren
  Typfamilien die Default-Richtung von ablehnen auf annehmen.
- **Treiber liefert das Kompatibilitätsurteil, Port trägt kein Typfeld** — `formats` bezieht seinen
  Dispatch-Schlüssel ausschließlich über `TargetColumn` → `JdbcTypeHint` und hat keine Compile-Kante
  auf `driver-common`. Ein Urteil (`Boolean`) ersetzt keinen Dispatch-Schlüssel.
- **`TargetColumn` vollständig nach `driver-common`** — `driver-common` exportiert
  `api(hexagon:ports)` und zöge die Umbrella samt `DatabaseDriverRegistry` in `formats` und
  `streaming`; zudem steckt `TargetColumn` in zwei weiteren Port-Verträgen
  (`TableImportSession.targetColumns`, `ValueDeserializerFactory.create`).

Der gemeinsame Nenner: Alle drei suchten ein Heilmittel, bevor die Ursache benannt war. Die
Typcode-Felder werden im Post-1.0.0-Vorhaben **mitbehandelt**, nicht davor einzeln.

Ein Befund verdient dabei besondere Beachtung, weil er den Anspruch schon heute konkret verletzt:
**`formats` ist ein Nicht-JDBC-Adapter.** Eine CSV-Datei hat keine JDBC-Typen; trotzdem zwingt
`JdbcTypeHint` den Format-Adapter, in `java.sql.Types` zu denken, um Werte zu casten. Das ist der
einzige heute belegbare Fall, in dem die fehlende Agnostik einen realen Adapter belastet — und
damit der natürliche erste Angriffspunkt des Vorhabens.

## Nicht-Ziele

- **Keine Umsetzung vor 1.0.0** — weder am Treiber-Port noch an den Typcode-Feldern.
- **Keine Eingrenzung des Leitprinzips** auf relationale Datenbanken (Z1 ausdrücklich verworfen).
- **Kein Gate.** Ein Grep nach `jdbcType` prüft einen Feldnamen, nicht die Kopplung; prüfbar wird
  das Prinzip erst an einem realen Nicht-SQL-Adapter.
- Das Neutralmodell selbst: `NeutralType.Enum.refType` und `Array.elementType` tragen rohe
  Dialekt-Strings — eine Spannung zu [ADR 0015](0015-fulltext-tsvector-neutral-type.md), die zum
  Modell gehört, nicht zur Portschicht.
- Vendor-benannte Ports (`SqliteCastPreflight`) und der Profiling-Pfad (`dbType: String`).

## Konsequenzen

**Positiv**

- Der Widerspruch zwischen Leitprinzip und Port-Design ist benannt statt latent — mit Grund und
  Fälligkeit.
- Kein Aktionismus an Symptomen; die 1.0.0-Linie bleibt unangetastet.
- Das Post-1.0.0-Vorhaben hat einen **prüfbaren** Prüfstein (ein Nicht-SQL-Adapter, ohne den
  Vertrag anzulügen) statt eines Feldnamen-Greps.

**Negativ — bewusst in Kauf genommen**

- **Die Einlösung ist terminiert, aber nicht durchsetzbar.** Diese ADR setzt bewusst kein Gate
  (s. „Confirmation"); „nach 1.0.0" ist eine Absicht, die niemand einklagt. Ein späterer Zyklus
  kann sie folgenlos weiterschieben. Das ist der eigentliche Preis von Z2 — dass das Zielbild noch
  aussteht, ist es **nicht**: `spec/` ist Zielbild ohne Status, ein noch nicht eingelöstes Ziel ist
  dort der Normalfall und kein Mangel. (Genau deshalb war der „vorerst"-Satz in der Spec
  konventionswidrig.)
- **Vertagen ist nicht kostenneutral: 1.0.0 liefert eine Festlegung mit aus.** Der
  Parquet-Manifest-Vertrag deklariert `jdbcType` als lesetolerantes Feld, und
  [ADR 0028](0028-a-check-architecture-gate-scope.md) führt ihn ausdrücklich als Typcode-Träger. Mit
  1.0.0 steht dieses Schema unter Kompatibilitätserwartung. Entschärft ist der Preis dadurch, dass
  **kein geschriebenes Manifest das Feld enthält** (`ChunkSchemaToManifest` setzt `null`, der Writer
  emittiert bedingt) und dass `CURRENT_FORMAT_VERSION` als Änderungsvehikel existiert: eine spätere
  Entfernung wäre eine versionierte **Schema-Verengung**, keine Migration bestehender Bundles. Klein
  genug, um die Entscheidung nicht zu kippen — aber nicht null, und vor 1.0.0 wäre er gar nicht
  angefallen.
- `jdbcType: Int` bleibt in `ports-write`/`ports-common` stehen; der ursprüngliche Einwand bleibt
  sachlich berechtigt und unerledigt.

**Neutral**

- Kein Code ändert sich durch diese ADR.
- [ADR 0028](0028-a-check-architecture-gate-scope.md) behält seine Gültigkeit; seine
  `jdbcType`-Interop-Erlaubnis hat nun einen benannten Grund statt einer offenen Frage.

## Confirmation

- Diese ADR ist eine **Richtungsentscheidung**; sie trägt kein Abnahmekriterium am Code.
- Prüfbar wird sie durch das Folgevorhaben: ein Adapter für eine Nicht-SQL-Datenbank, der
  `DatabaseDriver` erfüllt, ohne `baseJdbcUrl` zu missbrauchen oder `DdlGenerator` werfen zu lassen.
- Der Ist-Abstand ist in `docs/planning/` sichtbar statt in der Spec.

## Weitere Informationen

- [ADR 0022](0022-ports-jdbc-entkopplung.md) — neutralisierte die Connection; die URL blieb.
- [ADR 0028](0028-a-check-architecture-gate-scope.md) — Ursprung der `jdbcType`-Interop-Ausnahme.
- [ADR 0015](0015-fulltext-tsvector-neutral-type.md) — „first class statt Passthrough".
- [`jdbc-kopplung-der-ports-ist-aufnahme.md`](../planning/open/jdbc-kopplung-der-ports-ist-aufnahme.md)
  — verifizierte Faktenlage: Ursache, Symptome und Fläche.
