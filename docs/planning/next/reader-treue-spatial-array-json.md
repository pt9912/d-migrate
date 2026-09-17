# Reader-Treue: stille Typverluste bei Spatial, Array und JSON

> **Status:** Entwurf mit Scope (2026-09-16, Review-Runden 1 und 2 eingearbeitet).
> Gemeldet gegen `1.7.1` (C1 stammt aus der Compare-Messung und ist am
> 2026-09-16 uebernommen). Zwei Angaben je Posten, getrennt gefuehrt:
> **Belegart** — *nachgemessen* (A1, A5, B3, B4, C1) oder *blosse Meldung*
> (A2, A3, A4, B1, B2); bei den Meldungen ist im Code nur die **Vorbedingung**
> geprueft, nicht die Zahl oder der Objektname (s. „Verifikation", Punkt 5).
> **Verbleib** — Paket (A1→P1, A2/A3→P2a/P2b, A4→P3, A5→P4, B1→P5, C1→P6),
> „Offen" (A6, B3) oder widerlegt und entfallen (B4).
> **Vorbedingung / Gate:** **eines, halb** — die Eigner-Frage aus A1 (s.
> Abgrenzung) ist mit P1 zur Haelfte beruehrt: die „Fund"-Seite (Note) nimmt P1
> vor, die „Block"-Seite bleibt offen. Sonst keins. Der Spatial-Vertrag, den A1
> beruehrt, steht in
> [`spec/type-mapping.md`](../../../spec/type-mapping.md) (dort **zweimal**: beim
> Oracle-Reverse und in den Render-Regeln je Dialekt) und im Geometrie-/
> Spatial-Profil-Modell von
> [`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md) — **nicht**
> in einem ADR. [`ADR 0016`](../../adr/0016-spatialite-metadata-bootstrap.md)
> regelt den SpatiaLite-Bootstrap auf dem **Migrate**-Pfad und hat den
> **Generate**-Pfad ausdruecklich als bewusste Scope-Grenze aufgeschoben
> (`:73-80`); fuer A6, der auf dem Generate-Pfad sitzt, ist sie damit **keine**
> Quelle, und ihr Befund ist ein anderer als dieser. Ob ein fehlender SRID ein
> **Fund** oder ein **Block** sein soll, ist eine Eigner-Frage (s. Abgrenzung).
> **Nachtrag 2026-09-17:** A6 und B3 sind vom Eigner entschieden und werden bei
> der Aktivierung zu Paketen; dazu drei Reader-Posten aus dem Compare-Bau (D1–D3,
> s. Abschnitt D). Beides ist hier festgehalten, damit es nicht mit dem
> Compare-Slice nach `done/` wandert.
> **Aktivierung:** Move nach `../in-progress/` beim ersten Implementierungs-Commit.

## Der gemeinsame Nenner

Zehn lebende Posten — neun aus der Reader-Messung (ein zehnter, B4, ist in
Review-Runde 1 widerlegt und entfallen) und **C1**, am 2026-09-16 aus der
Compare-Messung uebernommen. **Zwei** Muster — die Einleitung des ersten Entwurfs
tat sie in eines, und das trug nicht:

1. **Fidelity: der Reader verliert Information oder der Generator verwirft ein
   Objekt — und sagt es nicht.** Mehrfach gibt es auf demselben Weg fuer einen
   **Schwesterdialekt** bereits einen Fund und fuer den betroffenen keinen:
   `W149` (Oracle) und `W137` (MSSQL) melden beide den Array-Verlust, den MySQL
   nicht meldet (B1) — Oracle rendert ihn als `JSON`, MSSQL als `NVARCHAR(MAX)`;
   MSSQL meldet seinen `geography`-Sonderfall mit `R345` (A5). Das ist kein
   Zufall: die Meldewege sind je Dialekt einzeln gebaut, nicht aus einer Naht.
2. **Modell-Reinheit: der Reader nimmt ZUVIEL auf** (A2, A3, C1). Hier geht
   nichts verloren — es kommt Fremdes hinzu: Oracles interne `MDRS_*`-Sequenz und
   rund 1000 PostGIS-Funktionen wandern als Anwenderobjekte ins Artefakt (A2,
   A3), und MySQLs Charset-Introducer wandert als **Server-Text** ins Modell und
   macht es ungültig (C1). Der Filter, den P2a/P2b bauen, **erzeugt** den Verlust
   erst, den er dann melden soll; das ist ein eigener Strang und in „Ziel"
   getrennt gefuehrt.

Die Gegenprobe, die der Konsument selbst gefuehrt hat, gehoert dazu: **kein
Befund** ist, dass Oracles `-slim`-Image Spatial und Locator nicht enthaelt
(`gvenzl` ImageDetails) — mit dem `regular`-Flavor (`23-faststart`) laeuft es,
und die Free-Edition lizenziert Spatial (`v$option`: Spatial = TRUE). Ebenso
sauber: SpatiaLites Metadatentabellen (`spatial_ref_sys`, `geometry_columns`)
lecken **nicht** ins Reverse, und der R-Tree-Index kommt als `type: spatial` mit.

## Befund

### A — Spatial

**A1 — Oracle verliert den SRID bei quotiert-kleingeschriebenen Tabellen
(Praemisse in Review-Runde 1 korrigiert, Deutung in Runde 2; im Code und im Repo
nachgemessen).**

> **Korrektur nach Review-Runde 1.** Der zweite Entwurf hatte die betroffene
> Klasse **invertiert**: er nannte „unquotiert/grossgeschrieben angelegte
> Tabellen" und setzte genau diesen Fall als P1-DoD. Der Code sagt das Gegenteil
> (s. u.) — der unquotierte Fall ist **heute schon** zugesichert und getestet.
> Die Aussage, der Fall „laeuft bereits als Fund `W120`", ist ebenfalls falsch:
> `W120` feuert auf diesem Pfad nicht.
>
> **Korrektur nach Review-Runde 2.** Runde 1 hatte als Ersatz die Deutung
> eingetragen, die grossgeschriebene Zeile sei „die Zeile **derselben** Tabelle".
> Das ist **widerlegt** — von der Messung, die derselbe Absatz anfuehrt
> (`oracle-dialect-scoping.md:1527`: eine solche Zeile „behauptete etwas über
> eine *andere* Tabelle"), und von drei Repo-Stellen, die es aussprechen
> (`OracleColumnConstraintHelper.kt:313-315`, `OracleNeutralTypeCanonicalizer.kt:40-42`,
> `smoke-spatial-ora.sh:8-10`; auch der Integrationstest sagt es in seinem eigenen
> Kommentar, `OracleSpatialIntegrationTest.kt:334-338`). Es geht hier **nicht** um
> einen zu strengen Vergleich, sondern um eine harte Grenze: eine Zeile, die eine
> quotiert kleingeschriebene Tabelle beschreibt, gibt es nicht. Der Fix ist
> deshalb die **Meldung**, nicht der Abgleich (s. P1).

Der Katalogzugriff ist vorhanden (das hatte schon der erste Entwurf bestritten):

```kotlin
OracleMetadataQueries.kt:180-185   SELECT m.column_name, m.srid FROM all_sdo_geom_metadata m
                                   WHERE m.owner = ? AND m.table_name = ?
OracleSchemaReader.kt:124          geometrySrid = geometry[row.name]?.srid
OracleTypeMapping.kt:225           NeutralType.Geometry(geometryType = …, srid = input.geometrySrid)
```

Der Abgleich ist eine **Gleichheit gegen den Dictionary-Namen** — und genau daran
bricht es, aber nicht bei den unquotierten Tabellen:

| Angelegt als | Dictionary-Name | Was in `ALL_SDO_GEOM_METADATA` stehen kann | Ergebnis |
| --- | --- | --- | --- |
| unquotiert (`CREATE TABLE SRC_PLACES …`) | `SRC_PLACES` | `SRC_PLACES`/`GEOM` | Treffer, `srid = 4326` — **heute gruen**, gepinnt in `OracleSpatialIntegrationTest.kt:340-389` (`.srid shouldBe 4326`) |
| **quotiert kleingeschrieben** (`CREATE TABLE "fp_places" …`) — so legt d-migrate seine Tabellen an | `fp_places` | **keine, die sie beschreibt** — jede geschriebene Zeile landet hochgefaltet als `FP_PLACES`/`GEOM` | **kein Treffer** → SRID verloren |

Der Unterschied ist keine Feinheit des Vergleichs, sondern eine Eigenschaft der
Quelle: eine per `"fp_places"` quotierte Tabelle laesst sich in
`USER_SDO_GEOM_METADATA` **nicht beschreiben**. Gemessen in
`docs/planning/done/oracle-dialect-scoping.md:1527`: die Zeile wird
**bedingungslos grossgeschrieben**, auch fuer Bezeichner, die sich gar nicht
hochstellen lassen (`'my-tbl'` → `MY-TBL`), Verursacher `MDSYS.SDO_GEOM_TRIG_INS1`;
und Oracle sucht sie unter dem echten Namen `fp_places`, findet nur `FP_PLACES`
und meldet **ORA-13252** — die Zeile nuetzt dort auch Oracle selbst nicht. Dasselbe
sagen `OracleColumnConstraintHelper.kt:313-315` („it cannot describe a quoted
lower-case table like '$tableName'") und
`OracleNeutralTypeCanonicalizer.kt:40-42`.

**Und d-migrate schreibt die Zeile nirgends.** Kein Main-Source-Pfad legt sie an
— `OracleMetadataQueries.kt:174-185` und `OracleSchemaReader.kt:206-223` **lesen**
nur, und `oracle-dialect-scoping.md:1570` haelt fest, dass der Slice „keine
Metadatenzeile" schreibt. Der Zustand, den ein toleranter Abgleich auswerten
wuerde, entsteht deshalb nur, wenn jemand die Zeile **von Hand** nachgezogen hat —
wofuer der Generator mit `W120` sogar wirbt („Insert the USER_SDO_GEOM_METADATA
row manually"). Fuer eine quotiert kleingeschriebene Tabelle ist das aber genau
die Zeile, die Oracle als die einer **anderen** Tabelle liest.

**Und der Verlust ist stumm.** Der zweite Entwurf berief sich darauf, der Fall sei
„bereits als `W120` gemeldet" — das trifft nicht zu. Auf dem Oracle→MSSQL-Weg
entsteht **keine** Note: beide einschlaegigen Bedingungen verlangen einen
**getragenen** SRID oder einen nicht-generischen Subtyp
(`OracleColumnConstraintHelper.kt:309-310`,
`MssqlColumnConstraintHelper.kt:293-295`: `sridUnenforced = type.srid != null && …`),
und bei verlorenem SRID und generischem Subtyp — genau dem A1-Zustand
(`OracleTypeMapping.kt:224-225` setzt immer `GeometryType.GEOMETRY`) — greift
keine von beiden. Auf dem Oracle→MSSQL-Weg ist das einzige Signal `E057` am
fallenden Spatial-Index.

**Nicht zu verwechseln mit dem Fall, den es schon gibt.** `R365` meldet den
**werfenden** Weg — Oracle Spatial fehlt oder das Recht darauf
(`OracleSchemaReader.kt:206-223`), im Handbuch gefuehrt als „`ALL_SDO_GEOM_METADATA`
ist nicht lesbar … Geometriespalten kommen ohne Koordinatensystem zurück". Der
A1-Fall ist der andere: der Katalog **ist** lesbar, die passende Zeile fehlt
trotzdem — und dafuer gibt es nichts.

**Die gemeldete Wirkung bleibt damit stehen:** der SRID-Verlust kostet auf dem
Ziel einen Index (Oracle→MSSQL: `geometry` statt `geography`, der Spatial-Index
faellt mit `E057` weg). **Der Subtyp gehoert nicht dazu:**
`ALL_SDO_GEOM_METADATA` fuehrt kein Subtyp-Feld, `SDO_GTYPE` steht am einzelnen
Wert (eine Spalte kann Punkt und Polygon fuehren) — `OracleTypeMapping.kt:218-225`
haelt fest, dass „eine engere Angabe erfunden" waere.

**Der naheliegende Fix wurde geprueft und verworfen.** Runde 1 hatte einen
toleranten (case-insensitiven) Abgleich vorgesehen. Er scheitert an der Sache und
an zwei Nebenwirkungen:

1. **Er wertet eine fremde Zeile aus.** Beschreiben laesst sich eine quotiert
   kleingeschriebene Tabelle nicht (s. o.) — gefunden wuerde also hoechstens die
   Zeile einer echt grossgeschriebenen Nachbartabelle, und in Oracle koexistieren
   `PLACES` und `"places"`. Eine Regel „zwei faltende Zeilen ⇒ keine" deckt
   gerade **nicht** diesen Fall ab, sondern nur den der Doppelfalte.
2. **Er trifft nicht nur das Artefakt.** Dieselbe Abfrage speist den
   **Datenpfad**: `OracleDataWriter.kt:115-125` fuellt daraus `TargetColumn.srid`
   (`col.copy(srid = it)`). Eine Fehlzuordnung schriebe die SRID der fremden
   Zeile in die Geometriewerte eines Transfers — nicht nur ins Modell.

Es bleibt deshalb beim wortgetreuen Abgleich; die Spec behaelt „exakt passend"
und bekommt die **Begruendung** dazu (s. P1).

**Und der Vergleichssatz des ersten Entwurfs war zu pauschal.** „PG, MySQL,
MSSQL und SpatiaLite tragen den SRID alle" gilt fuer MSSQL-`geometry` **nicht**:
`MssqlTypeMapping.kt:167` gibt `NeutralType.Geometry()` ohne `srid`; nur
`geography` erhaelt 4326 als **Annahme** (`:168`, gemeldet als `R345`).

**A2 — Oracles interne Spatial-Sequenz leckt ins Modell (gemeldet).** Mit
installiertem Spatial erscheint `sequences: MDRS_11E5F$` im Artefakt und wird als
`CREATE SEQUENCE [MDRS_11E5F$] …` in die Ziel-DDL geschrieben (belegt fuer MSSQL
und PG). Ein Fremdsystem-Objekt wandert damit in das migrierte Schema.

**Geprueft ist davon die Vorbedingung, nicht der Name:** `listSequences` filtert
ausschliesslich identity-tragende Sequenzen
(`OracleMetadataQueries.kt:432-445`, `NOT EXISTS all_tab_identity_cols`) — es gibt
dort **keinen** Systemobjekt-Filter, waehrend der Tabellenpfad deren vier hat
(`:142-155`: Papierkorb, Sekundaerobjekte, Materialized Views, MV-Logs). Der
gemeldete Sequenzname selbst ist nicht nachgemessen.

**A3 — PostGIS-Funktionen fluten das Reverse (gemeldet).** PostGIS in `public`
installiert ~1000 Funktionen; das Reverse sammelt sie als `functions:`-Block ein
— **320.271 Byte** Artefakt gegenueber **816 Byte** bei derselben Tabelle mit
PostGIS im eigenen Schema.

**Der Filter existiert im selben Modul — nur nicht auf diesem Pfad.**
`PostgresTableMetadataQueries.kt:20` schliesst extension-eigene **Tabellen** aus
(„5a: Extension-eigene Tabellen ausschliessen (z.B. PostGIS `spatial_ref_sys`)");
`PostgresTypeMetadataQueries.kt` ebenso fuer Enum- (`:15`), Domain- (`:40`) und
Composite-**Typen** (`:59`). Ungefiltert ist allein der **Programmability**-Pfad:
`PostgresProgrammabilityMetadataQueries.kt:118` filtert bloss
`routine_name NOT LIKE 'pg_%'` — und genau dort haengen die ~1000
PostGIS-Funktionen. In der Praxis wandert jede extension-lastige Datenbank als
Riesenartefakt in Store und Compare.

**Nicht nachgemessen sind die Zahlen** (320.271/816 Byte, ~1000 Funktionen): sie
stammen aus der Konsumentenmessung. Geprueft ist die **Vorbedingung** — der
Programmability-Pfad hat keinen Extension-Filter, waehrend die beiden
Nachbarpfade ihn haben.

**A4 — PG-Geometrie haengt am `search_path`, und die Degradierung ist still
(gemeldet).** PostGIS im eigenen Schema, aber nicht im `search_path` → `type:
geometry` **ohne** Subtyp/SRID, **ohne Finding**. Mit `search_path = …,
postgis` (die von der PostGIS-Doku empfohlene Konfiguration) ist alles korrekt.
Kein Reader-Fehler im engeren Sinn — es fehlt der **Hinweis** („Geometriespalte
ohne Subtyp/SRID — liegt das PostGIS-Schema im `search_path`?").

**Praezisierung:** „ohne Finding" ist zu absolut. Je PostGIS-Geometriespalte
entsteht eine INFO-Note `R401` (`PostgresTypeMapping.kt:166-172`) — sie nennt
Subtyp und SRID aber nicht und gibt keinen Ausweg. „Still" trifft also den Kern,
„ohne Finding" nicht.

**A5 — PG `geography` wird als Enum fehlgelesen (im Code bestaetigt).**
`geography(Point,4326)` → `type: enum, ref_type: geography`. Im PG-Zweig ist
`geography` **gar nicht** abgebildet; ein unbekannter Typ faellt in den
Custom-Type-Zweig (`PostgresTypeMapping.kt:174`, `NeutralType.Enum(refType = udtName)`).

**MSSQL macht es vor, und zwar vollstaendig:** `geography` liest dort als
`Geometry(srid = 4326)` mit der Note `R345` (`MssqlTypeMapping.kt:168`/`:55`),
und vorwaerts waehlt `isGeodeticSrid(type.srid)` zwischen `geography` und
`geometry` (`MssqlTypeMapper.kt:73`, spec'd in `spec/ddl-generation-rules.md:2538`).
PostGIS hat dieselbe Zwei-Teilung — PG erzeugt hier als einziger Dialekt einen
Custom-Type, obwohl das Muster im selben Repo liegt.

**A6 — SpatiaLite-Ziel verwirft die ganze Tabelle: spec-konform, damit kein
stiller Verlust (gemeldet; in Review-Runde 1 neu eingeordnet).**
`schema generate --target sqlite --spatial-profile spatialite` auf einer
MySQL-Quelle mit **NOT NULL**-Geometrie: `E052` („Geometry column 'g_point' has
unsupported metadata (required/unique/default/references/PK) for SpatiaLite"),
und die **komplette Tabelle** faellt aus der Ausgabe (4 Skips) — die uebrigen,
darstellbaren Spalten gehen mit.

Der zweite Entwurf fuehrte das als Defekt und wollte die Tabelle erhalten. Die
Spec schreibt den heutigen Effekt aber an **drei** Stellen fest, und zwar
begruendet:

- `spec/cli-spec.md:467` — „Die gesamte Tabelle wird uebersprungen; keine partielle DDL."
- `spec/neutral-model-spec.md:1503` — „E052 | Blockiert die gesamte betroffene Tabelle."
- `spec/ddl-generation-rules.md:2688` — die **generische** E052-Regel: „Wird erzeugt, wenn ein Spatial-Objekt mit dem gewaehlten Spatial-Profil nicht generiert werden kann. Die gesamte betroffene Tabelle wird blockiert — partielle DDL ohne die Spatial-Spalte wird nicht erzeugt." Sie traegt A6, weil sie **profilunabhaengig** formuliert ist — A6s Effekt stammt aus dem Profil `spatialite` (`SqliteTableDdlSupport.kt:315-333`), nicht aus `none`. Die Profilabschnitte 16.5 (`:2479`, `spatialite`) und 16.6 (`:2507`, `none`) sagen zu diesem Fall nichts; die in Runde 1 hier zitierte Stelle `:2508` war eine Leerzeile im Abschnitt `none`.

Beide Wege, die der zweite Entwurf erwog („Geometriespalte allein verwerfen",
„Spalte als Text"), kippen eine dieser Zeilen. **A6 ist damit kein stiller
Verlust und faellt als Paket aus diesem Slice heraus:** der Ausgang ist laut —
`E052` steht auf stderr und in `skipped_objects` des Reports, und die Meldung
nennt die Spalte. Die Vertragsfrage selbst bleibt gestellt; sie liegt mit den
drei Spec-Stellen in „Offen".

### B — Array und JSON

**B1 — MySQL rendert Arrays still als JSON (gemeldet).** `integer[]`/`text[]` →
`JSON`, ohne jeden Fund. Dieselbe Quelle meldet auf Oracle `W149` („Array column
… is rendered as JSON"); MSSQL meldet seinen eigenen Verlust ebenfalls, aber mit
**anderem** Ziel — dort wird das Array zu `NVARCHAR(MAX)`-Text
(`MssqlTypeMapper.kt:62`, `MssqlColumnConstraintHelper.kt:333-336`, Note `W137`).
MySQL tut sachlich dasselbe wie Oracle und meldet es nicht.

**B2 — der Array-Verlust ueber MySQL ist irreversibel (gemeldet).** Der
MySQL-Reader liest JSON **immer** als `type: json`, nie als `array`. Kette:
PG `integer[]` → MySQL `JSON` → Reverse → `json` → zurueck nach PG `JSONB`. Die
Array-Eigenschaft ist weg und rueckwirkend **nicht erkennbar** — auf keinem der
drei Schritte ein Signal.

**B3 — PG `json` und `jsonb` kollabieren: spec-konform, kein Posten
(im Code bestaetigt; in Review-Runde 1 neu eingeordnet).**
`PostgresTypeMapping.kt:142` bildet **beide** auf `NeutralType.Json` ab; der
PG-Generator rendert daraus `JSONB`.

Der zweite Entwurf fuehrte das als stillen Typverlust. Es ist aber die
**kanonische Form des Modells**: `spec/neutral-model-spec.md:148` fuehrt genau
eine Zeile `json` → PG `JSONB` (MySQL `JSON`, SQLite `TEXT`). Der Rueckweg
`json → json` mit erneuter Renderung nach `JSONB` ist damit vertragsgleich; „zu
beheben" hiesse, das Modell um eine **zweite** JSON-Art zu erweitern — eine
Modellfrage (s. „Offen"), kein Paketposten.

**B4 — entfaellt (Praemisse widerlegt, im Code nachgemessen).**

> **Korrektur nach Review.** Der erste Entwurf fuehrte „PG `interval` wird still
> zu `text`, kein Fund" als Posten. Es gibt einen Fund.

`interval` faellt durch alle Zweige von `PostgresTypeMapping.mapColumn` in den
gemeinsamen `else`-Fallback — mit **`R301`**, Severity `WARNING`, „Unknown
PostgreSQL type 'interval' (udt: interval) mapped to text"
(`PostgresTypeMapping.kt:79-86`). `spec/type-mapping.md:412` schreibt den
Fallback fuer **alle fuenf** Reverse-Mapper als „erzeugt **immer** eine
diagnostische Warning-Note" fest.

**Was bleibt, ist ein Nachtrag am Tracker, kein Slice-Posten:** die Familie hat
einen offenen Ort,
[`../open/pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md)
— und `interval` fehlte in dessen Kandidatenliste (`:31-39`). Der Nachtrag ist
beim Aufnehmen dieses Befunds **erfolgt** (2026-09-16) und beruehrte **zwei**
Dateien: die Liste dort (als **Mechanik**-Zeile, nicht als Bedarfsmeldung — der
Tracker aktiviert sonst „sobald ein konkreter Fidelity-Bedarf auftritt", und
genau den hat B4 widerlegt) **und** die Übersichtszeile in
[`../open/README.md`](../open/README.md), die die Kandidaten namentlich aufzaehlt.

### C — Modell-Reinheit (aus der Compare-Messung uebernommen)

**C1 — MySQLs Charset-Introducer macht das Schema ungueltig (gemessen; Quelle:
Compare-Messung, dort Posten 4 — am 2026-09-16 hierher gewandert, weil es der
einzige Reader-Posten jener Messung ist).** Der Konsument meldete
`ck_customer_email_shape` als Fehlalarm „nur wo MySQL beteiligt ist"; Ursache sei
`_utf8mb4'%@%'`. Nachgemessen ist es mehr als ein Fehlalarm — eine Datei mit
diesem Ausdruck ist **ungueltig**:

```
$ d-migrate schema validate --source cs_my.yaml
  ✗ Error [E012]: Check expression 'ck_mail' references unknown column '_utf8mb4'
    → tables.orders.constraints.ck_mail
```

Der Introducer steht vor dem Literal; die Ausdrucks-Analyse liest `_utf8mb4` als
**Spaltenbezug**. Das ist kein Kanonisierungs-, sondern ein **Reader**-Thema: was
MySQL in `CHECK_CLAUSE` liefert, ist nicht das neutrale Modell, sondern ein
Server-Text mit Dialekt-Anhang. (Der zweite Teil der Meldung — backslash-escapte
Anführungszeichen, `\'%@%\'` — war ohne MySQL-Server nicht entscheidbar; am
2026-09-16 gegen MySQL 9.7.2 nachgemessen: `CHECK_CLAUSE` liefert sie
tatsaechlich, s. P6.)

**Der Praezedenzfall steht im Repo und entscheidet die Frage vor:** der
MSSQL-Reader streicht den Unicode-Literal-Praefix `N'…'` **im Reader**, weil der
Validator das `N` sonst als Spaltenbezug liest und jedes reverse-gelesene
MSSQL-Schema mit `E012` abweist (`MssqlTypeMapping.kt:312-314`, gepinnt in
`MssqlTypeMappingTest.kt:203-222`). `_utf8mb4'…'` ist dieselbe Klasse Konstrukt —
die Alternative („die Analyse kennt den Introducer") hat damit einen Vorläufer,
der sich dagegen entschieden hat.

**Und der Posten bringt eine Anwenderstelle mit:** die Grenze von `E012` steht im
Anwenderhandbuch (`docs/user/anwenderhandbuch.md:2125`) — dort zieht P6 mit.

### D — Nachträge vor der Aktivierung (2026-09-17)

**Entschiedene „Offen"-Posten.** Beide werden bei der Aktivierung eigene Pakete;
die Begründung und die Messung stehen in den `open/`-Einträgen.

- **A6 — NOT NULL nativ.** Eine Geometriespalte mit `NOT NULL` wird über das
  `not_null`-Argument von `AddGeometryColumn` angelegt statt die Tabelle mit
  `E052` zu verwerfen (gemessen an SpatiaLite 5.1.0); `E052` bleibt für PK,
  UNIQUE, Default und Fremdschlüssel. Die drei Spec-Stellen nennen `NOT NULL`
  nicht mehr als Auslöser. Quelle:
  [`../open/spatial-profile-e052-ganze-tabelle.md`](../open/spatial-profile-e052-ganze-tabelle.md).
- **B3 — `json` laut.** Ein JSON-Typ im Modell; der PostgreSQL-Reverse meldet
  eine `json`-Spalte mit eigenem Code (sie wird als `jsonb` gerendert). Quelle:
  [`../open/json-jsonb-zweite-json-art.md`](../open/json-jsonb-zweite-json-art.md).

**Neue Posten aus dem Compare-Bau** (gemessen dort, Belegart: *nachgemessen*;
Quelle: [`../in-progress/compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md),
Abschnitt „Offen"). Das Paket je Posten wird bei der Aktivierung geschnitten.

- **D1 — Der Typ einer berechneten Spalte in SQL Server.** SQL Server führt für
  berechnete Spalten keinen deklarierten Typ; der Reverse liest den aus dem
  Ausdruck abgeleiteten (`decimal(23,2)` für `quantity * unit_price` bei
  `decimal(12,2)`), das Soll sagt `decimal(14,2)`. Ein Fund in PG↔MSSQL und
  MSSQL↔MySQL, schon in 1.7.1. Zu messen: ob der Generator den Ausdruck in
  `CAST(… AS <Solltyp>)` hüllen soll, damit der Reverse den Solltyp zurückliest —
  und was das für den Ausdrucksvergleich (`W137`) bedeutet. Eigner-Entscheidung
  vom 2026-09-16: der Posten gehört hierher.
- **D2 — `numeric` ohne Präzision wird als `float` gelesen**
  (`PostgresTypeMapping.kt:115-119`). Das ist ein stiller Typverlust: eine exakte
  Zahl wird zur Gleitkommazahl. Im Compare-Slice war er Ursache einer
  Falsch-Gleichsetzung, die dort an der Cast-Regel abgefangen wurde; der Verlust
  selbst besteht weiter.
- **D3 — Was als `text` ankommt.** Ein `varchar` ohne Länge ist im Modell nicht
  von `text` zu unterscheiden (still); unbekannte PostgreSQL-Typen wie `inet` und
  `interval` landen als `text` mit `R301` (laut, s. B4). Zu klären: ob D3 ein
  Modellposten ist (Kandidatenfamilie
  [`../open/pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md))
  oder nur der `varchar`-Teil hierher gehört.

## Ziel

Kein Verlust bleibt still. Wo Information nicht erhalten werden kann, wird sie
**benannt** — mit demselben Mechanismus, den die Schwesterdialekte schon
benutzen, statt mit einem je Dialekt neu gebauten.

Das hat fuenf Ausgaenge, und sie in einen Satz zu zwingen waere die erste
Ungenauigkeit (Muster: [`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md),
das dieselbe Zerlegung braucht):

1. **Der Verlust ist unvermeidbar und wird benannt** — A1 (der SRID einer
   quotiert kleingeschriebenen Tabelle ist bei Oracle nicht deklarierbar, also
   wird der Ausgang gemeldet), B1 (MySQL verliert die Array-Eigenschaft beim
   Rendern). B2 benennt die Grenze: was im Ziel nicht mehr existiert, laesst sich
   rueckwirkend nicht melden; meldbar ist nur der Hinweg.
2. **Die Meldung ist da, aber unbrauchbar, und wird handelbar** — A4: die Note
   `R401` entsteht, nennt aber weder Grund noch Ausweg.
3. **Der Verlust ist ein Defekt und wird behoben** — A5 (`geography` wird als
   Enum statt als Geometrie gelesen).
4. **Fremdes gehoert nicht ins Modell** — A2, A3, C1. Eigener Strang (s. „Der
   gemeinsame Nenner"): hier wird nichts bewahrt, sondern **ausgeschlossen**. Bei
   C1 ist der Ausschluss nicht optional — der Fremdteil macht das Schema
   **ungueltig** (`E012`). Ob ein Ausschluss sonst stumm oder mit Hinweis
   geschieht, entscheidet das Paket.
5. **Der Ausgang ist bereits laut oder vertragsgleich und wird nicht angefasst** —
   A6 (`E052` nennt Spalte und Wirkung), B3 (`json` → `JSONB` ist die kanonische
   Modellform), B4 (widerlegt: `R301` meldet `interval`).

## Abgrenzung

- **Ob ein fehlender SRID ein Fund oder ein Block ist, entscheidet der Eigner.**
  A1 kostet auf dem Ziel einen Index; das ist mehr als ein Fehlalarm und weniger
  als ein Datenverlust. Die Entscheidung gehoert in
  [`spec/type-mapping.md`](../../../spec/type-mapping.md), nicht in diesen Slice.
  **Was P1 dazu beitraegt und was offen bleibt:** P1 macht den Ausgang auf der
  **Leseseite** sichtbar — das ist die „Fund"-Seite der Frage, und P1 nimmt sie
  damit faktisch vor (ein Warning-Code *ist* die Antwort „Fund"; die Kopfzeile
  sagt deshalb nicht mehr „Gate: keins", sondern benennt genau diese Vorwegnahme).
  **Offen ist die „Block"-Seite:** ob ein Generator einen Transfer oder eine
  Migration mit verlorenem SRID verweigern soll — heute kostet er „nur" den Index
  (`E057`). Beide Seiten gehoeren derselben Entscheidung.
- **Ein `interval`-neutraltyp** waere eine Modellerweiterung (wie `geometry`) —
  die Familie hat einen offenen Ort,
  [`../open/pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md);
  `interval` fehlt in dessen Kandidatenliste und wird dort nachgetragen (beide
  Dateien, s. B4), nicht hier gebaut.
- **Eine zweite JSON-Art** (`json` gegen `jsonb`) waere ebenfalls eine
  Modellerweiterung und ist **kein** Posten dieses Slices (B3): die heutige
  Gleichsetzung ist die kanonische Modellform
  (`spec/neutral-model-spec.md:148`). Die Frage liegt mit den beiden anderen in
  „Offen".
- **A6 ist kein stiller Verlust** und faellt als Paket heraus: der Ausgang ist
  laut (`E052`), und die Spec schreibt ihn an drei Stellen fest (s. A6). Die
  Vertragsfrage liegt in „Offen".
- **Die Oracle-Image-Frage ist geklaert** (s. o.) und braucht nichts.

## Arbeitspakete

### P1 — Oracle: der SRID-Verlust wird gemeldet (A1)

**Der Abgleich bleibt, wie er ist.** Der wortgetreue Vergleich ist kein Fehler,
sondern die Grenze der Quelle: eine Zeile, die eine quotiert kleingeschriebene
Tabelle beschreibt, gibt es nicht (A1). Der naheliegende Fix — ein toleranter,
case-insensitiver Abgleich — wurde **geprueft und verworfen**; Begruendung und die
zwei Nebenwirkungen (fremde Zeile, Aufrufstelle im Datenpfad
`OracleDataWriter.kt:115-125`) stehen in A1. P1 baut ihn nicht.

**Was P1 baut: die Meldung.** Bleibt eine Geometriespalte im Reverse ohne SRID,
entsteht eine Note, die **Grund** und **Ausweg** nennt — heute laeuft der Fall
ohne jede Meldung durch (A1). Der Grund ist nicht „der Vergleich war zu streng",
sondern: *Oracle kann zu einer quotiert kleingeschriebenen Tabelle keine
Metadatenzeile fuehren; wird die SRID gebraucht, muss sie an der Quelle deklariert
werden.* Der Ausweg ist die Handlungsanweisung, die der Generator schon kennt
(`OracleColumnConstraintHelper.kt:316-318`: „Insert the USER_SDO_GEOM_METADATA row
manually").

**Abgrenzung zur vorhandenen Note.** `R365` deckt den **werfenden** Weg (Katalog
nicht lesbar, s. A1). P1 deckt den anderen: lesbar, aber ohne passende Zeile. Die
neue Note ist ein **R-Code** wie `R365`, `R401` und `R345`; der R-Bereich ist
nicht im Ledger registriert (Nebenbefund, s. „Offen"). **Und sie gehoert in die
Anwendersicht:** die Handbuch-Tabelle, die `R365` fuehrt
(`docs/user/anwenderhandbuch.md:3021`), bekommt sie daneben — sie ist dessen
Schwester.

**Kein Subtyp.** `ALL_SDO_GEOM_METADATA` fuehrt keinen, und `SDO_GTYPE` steht am
Wert, nicht an der Spalte (A1) — das Paket fuehrt ihn deshalb nicht ein.

**Der Spec-Nachzug gehoert zum Paket — beide Stellen.** Die Regel steht
**zweimal** — [`spec/type-mapping.md`](../../../spec/type-mapping.md) beim
Oracle-Reverse und `spec/ddl-generation-rules.md:2615` in den Render-Regeln. Beide
sagen heute nur „sofern eine Zeile mit exakt passendem Tabellen- und Spaltennamen
existiert"; beide bekommen die **Begruendung** dazu, damit die Grenze nicht fuer
einen Bug gehalten wird.

**DoD:**
1. Ein Oracle-Reverse einer **quotiert kleingeschriebenen** Tabelle mit
   Geometriespalte erzeugt eine Note, die Grund und Ausweg nennt — der Fall, der
   heute ohne jede Meldung durchlaeuft (Abnahme in `test/integration-oracle`, s.
   „Verifikation" 2).
2. Beide Spec-Stellen sind nachgezogen.
3. Der Abgleich ist **unveraendert**: die bestehende Pinnung des unquotierten
   Falls (`OracleSpatialIntegrationTest.kt:340-389`) bleibt gruen, und die
   Aufrufstelle im Datenpfad verhaelt sich unveraendert.

### P2 — Extension-eigene und interne Objekte filtern (A2, A3)

Zwei Quellen, ein Filtergedanke — aber **zwei** Pakete, weil sie in zwei Treibern
liegen und nichts miteinander teilen ausser der Absicht:

**P2a — Oracle: Systemobjekte des Spatial-/Locator-Systems (A2).** `MDRS_*`,
`SDO_*`, `user_sdo_*` sind keine Schemaobjekte des Anwenders. Betroffen ist der
Sequenzpfad, der heute nur identity-tragende Sequenzen ausschliesst (A2).

**P2b — PostgreSQL: extension-eigene Objekte im Programmability-Pfad (A3).**
`pg_depend.deptype = 'e'` markiert sie; die ~1000 PostGIS-Funktionen haengen dort.
**Der Praezedenzfall liegt daneben und ist zu kopieren:** PG filtert Tabellen
(`PostgresTableMetadataQueries.kt:20`) und Typen
(`PostgresTypeMetadataQueries.kt:15`/`:40`/`:59`) bereits — es fehlt nur dieser
Pfad. Die Naht ist je Dialekt verschieden, **der Vertrag nicht**: was nicht dem
Anwender gehoert, kommt nicht ins Modell.

**Zur Entscheidung in beiden:** ob ein solches Objekt **stumm** oder **mit
Hinweis** entfaellt. Fuer P2b rechtfertigt die Groessenordnung den Hinweis (320 kB
gegen 816 Byte, gemeldet — nicht nachgemessen, s. A3); fuer P2a ist der Hinweis
die ehrlichere Wahl, weil ein verschwundenes Objekt sonst nur durch Vergleich
auffaellt.

**DoD (je Paket getrennt, damit ein Teilstand entscheidbar bleibt):**
**P2a** — `MDRS_*` erscheint nicht in `sequences:`; **P2b** — ein
PostGIS-in-`public`-Reverse ist nicht mehr um Groessenordnungen groesser als
dasselbe Schema ohne. Je Paket ein Test, je Paket sabotage-verifiziert.

### P3 — Die stille Degradierung benennen (A4)

Ein Posten: Geometrie ohne Subtyp/SRID bei fehlendem `search_path` (A4). Es gibt
dort eine Note (`R401`), aber sie nennt weder Ursache noch Ausweg.

**DoD:** Die Note nennt den Grund und den Ausweg („PostGIS-Schema in den
`search_path` aufnehmen"), wo der Reader ihn kennt. **Nicht** ueber
`schema_validate`: das Kommando nimmt nur eine Schemadatei ohne Datenbank
(`SchemaValidateCommand.kt:22`), und Read-Notes landen ausschliesslich im
Reverse-**Report** — das Artefakt-YAML kennt kein `notes`-Feld. Und der Ausweg
gehoert **gespiegelt** ins Anwenderhandbuch: das fuehrt fuer denselben Pfad schon
eine Note-Tabelle (`docs/user/anwenderhandbuch.md:3021`, dort u. a. `R365`),
und eine Handlungsanweisung, die nur im Reverse-Report steht, findet niemand, der
den Report nicht liest.

### P4 — PG `geography` nach dem MSSQL-Muster (A5)

**Der Praezedenzfall ist vollstaendig vorhanden — auf beiden Seiten:**

```kotlin
// Rueckweg
MssqlTypeMapping.kt:168   "geography" -> NeutralType.Geometry(srid = GEOGRAPHY_DEFAULT_SRID)   // + R345
// Hinweg
MssqlTypeMapper.kt:73     if (isGeodeticSrid(type.srid)) "geography" else "geometry"
```

`spec/ddl-generation-rules.md:2538` schreibt die Vorwaerts-Regel fest
(„ein geodätischer SRID (EPSG-Geographic-Block 4000–4999, insbesondere
4326/WGS 84) ergibt `geography`, alles andere planares `geometry`").

**Der Ort fuer das Kriterium ist heute nicht im Treiber, sondern in
`driver-mssql`.** `isGeodeticSrid` samt `GEODETIC_SRID_RANGE = 4000..4999` liegt
dort (`MssqlTypeMapper.kt:73`/`:149`). P4 braucht es in PG; eine zweite Kopie im
PG-Treiber waere genau die je-Dialekt-neu-gebaute Naht, die dieses Vorhaben
abschaffen will. Das Paket klaert also **zuerst den Ort** (gemeinsame Stelle im
`driver-common` oder eine neutrale Absichtserklaerung) und baut das Kriterium nur
einmal.

**PostGIS hat dieselbe Zwei-Teilung** (`geometry` planar, `geography`
geodaetisch). Der Fix ist damit **kein neuer Vertrag, sondern die Uebertragung
des bestehenden**: der PG-Zweig liest `geography` als Geometrie mit SRID 4326
(+ Note, wie `R345`) und waehlt vorwaerts nach demselben SRID-Kriterium.

**Nicht** ein eigener neutraler „geography"-Typ: das Modell kennt die
Unterscheidung nicht (`spec/neutral-model-spec.md` fuehrt `geography`
ausdruecklich als „nicht Teil des neutralen Geometry-Modells"), und MSSQL zeigt,
dass es sie nicht braucht — der **SRID** traegt sie.

**Der Spec-Nachzug gehoert zum Paket — beide Seiten.** Vorwaerts in
`spec/ddl-generation-rules.md` beim **PG**-Abschnitt, wortgleich zur MSSQL-Regel.
Rueckwaerts in `spec/type-mapping.md` in die dortige PG-Allowlist
(`mapUserDefined`, heute kennt sie nur `geometry` und fuehrt alles andere als
`Enum(refType = udtName)`): `geography` kommt hinzu. Die Spec empfiehlt diese
Allowlist an derselben Stelle bereits selbst — A5 ist damit ihre Einloesung fuer
`geography`, nicht ihr Widerspruch.

**DoD:** `geography(Point,4326)` liest als Geometrie mit `srid: 4326` (nicht als
`enum`, kein Custom-Type); der Rueckweg nach PG ergibt wieder `geography`; beide
Spec-Stellen sind nachgezogen.

### P5 — MySQL meldet den Array-Verlust (B1)

**Nicht** „das Modell kann Array nicht": es **kann** es (`NeutralType.Array` mit
`element_type`, `spec/neutral-model-spec.md:153`, gelesen in
`PostgresTypeMapping.kt:149` und als `type[]` gerendert). Der Verlust entsteht am
**MySQL-Zielrenderer** (`MysqlTypeMapper.kt:41` bildet `Array` auf `"JSON"` ab) —
und niemand sagt es. PostgreSQL und SQLite sind hier nicht betroffen.

**Die Grenze gehoert zum Paket, nicht gegen es:** B2 laesst sich **nicht** durch
Melden heilen — ist die Array-Eigenschaft im Ziel erst `JSON`, existiert sie
nicht mehr, und kein Reader kann sie rueckgewinnen. Melden laesst sich nur der
**Hinweg**; das ist genau, was P5 tut. Ein Testfall pinnt die Kette PG `integer[]`
→ MySQL `JSON` → Reverse → `json` als **erwarteten** Ausgang, damit sie nicht
spaeter fuer einen Defekt gehalten wird.

**Ein eigener Code, nicht `W137`.** `W137` ist im Repo doppelt belegt — dieselbe
Kennung traegt den JSON→`NVARCHAR(MAX)`-Verlust
(`MssqlColumnConstraintHelper.kt:333`) **und** den unentscheidbaren
Berechnungsausdruck (`ComputedExpressionDecidability.kt:37`); `spec/ledger.md`
widerspricht sich dazu selbst (`:64` gegen `:77`). Ein neuer Code, der diesem
Muster folgt, erbte die Mehrdeutigkeit. P5 bekommt deshalb einen **eigenen**
W-Code aus dem Dialektbereich (der Ledger fuehrt bis `W161`; `W200`+ ist belegt).
Der Ledger-Widerspruch wird dabei **nicht** mitbehoben: er geht nach „Offen".

**Der Nachzug gehoert zum Paket — vier Spec-Orte plus die Anwendersicht.** Ein
neuer nutzersichtbarer Code ist erst vollstaendig, wenn er steht in: der W-Tabelle
[`spec/cli-spec.md`](../../../spec/cli-spec.md) (dort zuletzt `W155`–`W161`), der
Lesefassung [`spec/ledger.md`](../../../spec/ledger.md) (Einzelzeile **und**
Bereichszeile — `spec/ledger.md` erklaert selbst, dass **jeder** nutzersichtbare
W/E-Code dort registriert ist), dem maschinenlesbaren `warn-code-ledger-*.yaml`
und der Render-Regel in `spec/ddl-generation-rules.md` beim MySQL-Ziel — das
Muster steht dort fuer MSSQL (`:575`) und Oracle (`:637-638`) bereits.

**Und in der Anwendersicht**, die dieser Slice sonst nirgends anfasst: der Code
gehoert in die Meldungstabelle des Anwenderhandbuchs
(`docs/user/anwenderhandbuch.md:3021` fuehrt dieselbe Art von Notiz fuer
denselben Weg) und, wo er einen Ausweg hat, in den Troubleshooting-Leitfaden.
`CLAUDE.md` ist an dieser Stelle eindeutig: aendert sich, was ein Anwender tun
oder erwarten kann, aendert sich `docs/user/` mit — ein Fund-Code auf dem
Renderweg ist genau das.

**DoD:** MySQL meldet den Array-Verlust mit eigenem Code; die vier
Registrierungsorte sind nachgezogen; der Code trägt einen Test, der mit
zurueckgenommener Meldung faellt.

### P6 — MySQL: der Introducer gehoert nicht ins Modell (C1)

**Der Praezedenzfall entscheidet die Frage vor:** der MSSQL-Reader streicht den
Unicode-Praefix `N'…'` im Reader (s. C1) — P6 uebertraegt das auf MySQLs
Charset-Introducer. Die Alternative (die Ausdrucks-Analyse kennt den Introducer)
faellt damit **weg** — nicht weil sie teurer waere, sondern weil das Repo sie
fuer dieselbe Konstruktklasse schon einmal verworfen hat.

**Notiz (2026-09-16, aus dem Konsumenten-Repro des Compare-Slices gegen
MySQL 9.7.2):** `CHECK_CLAUSE` traegt neben dem Introducer auch das
Backslash-Escape — `` (`email` like _latin1\'%@%\') ``, im Hex `…5C27…`. Der
Introducer ist der Zeichensatz der **Sitzung**, die den CHECK anlegte
(`_latin1` bei einem `mysql`-Client mit `latin1`; der Konsument sah
`_utf8mb4`). **Folge fuer P6:** der Reader muss auch `\'` aufloesen. Sonst
zieht sich die Schreibweise-Faltung von `schema compare` (Rueckzug bei einem
Backslash) fuer **jeden** MySQL-CHECK mit String-Literal zurueck, und
`ck_customer_email_shape` bliebe ein Fund, obwohl `E012` behoben ist. Mit
beidem entfernt (simuliert, nicht gebaut) meldet PG↔MySQL den CHECK nicht
mehr; die Messung steht im Compare-Slice unter „Konsumenten-Repro".

**DoD:** PG↔MySQL meldet `ck_customer_email_shape` nicht mehr **und** ein
MySQL-Reverse mit einem solchen CHECK ist validierbar (`schema validate` ohne
`E012`). PG↔MSSQL war vorher sauber und bleibt es. Dazu der `docs/user/`-Nachtrag:
die Grenze von `E012` steht im Anwenderhandbuch (`:2125`) und zieht mit.

## Akzeptanzkriterien

1. Der Oracle-Reverse einer **quotiert kleingeschriebenen** Tabelle mit
   Geometriespalte erzeugt eine Note, die Grund und Ausweg nennt; der Abgleich
   bleibt wortgetreu (A1/P1). Abnahme unter `-PintegrationTests` (s.
   „Verifikation" 2).
2. Ein PostGIS-in-`public`-Reverse ist nicht um Groessenordnungen groesser als
   ohne (A3/P2b).
3. `MDRS_*` fehlt in den Sequenzen (A2/P2a).
4. Eine Geometriespalte ohne Subtyp/SRID erzeugt eine Note, die Grund und Ausweg
   nennt (A4/P3), und der Ausweg steht im Anwenderhandbuch.
5. `geography` wird als Geometrie mit SRID gelesen, nicht als Enum (A5/P4).
6. Der Array-Verlust auf MySQL ist mit eigenem Code benannt (B1/P5); die Kette
   `integer[]` → `JSON` → `json` ist als **erwarteter** Ausgang gepinnt (B2).
7. Jeder Test faellt nachweislich mit zurueckgenommenem Fix (Sabotage je Paket).
8. Ein MySQL-Reverse mit Charset-Introducer ist validierbar (`schema validate`
   ohne `E012`) und der CHECK meldet im Vergleich nicht mehr (C1/P6).

## Verifikation

1. **Spatial braucht den `regular`-Flavor** (`gvenzl/oracle-free:23-faststart`) —
   das `-slim`-Image hat Spatial und Locator nicht. Im Repo ist das bereits so
   modelliert: `TestImages.ORACLE_FULL` und der Dienst `oracle-spatial` in
   `examples/sample-db/docker-compose.yml`.
2. **Die Abnahme fuer P1/P2a entsteht in `test/integration-oracle` — der Fall ist
   neu, die Kante nicht.** `OracleSpatialIntegrationTest` fahrt mit
   `TestImages.ORACLE_FULL` bereits einen echten Oracle-**Reverse** und pinnt
   `.srid shouldBe 4326` (`:340-389`, unquotierter Fall). Was fehlt, ist der
   **Fall** „quotiert kleingeschrieben" — der heute ohne jede Meldung
   durchlaeuft — und fuer P2a die Zusicherung, dass `MDRS_*` nicht in
   `sequences:` steht. Beides gehoert in diese Klasse, nicht in einen neuen
   Harness.

   **Und es ist ein eigenes Gate.** `test/integration-oracle` ist vom
   Unit-Test-Lauf ausgenommen und laeuft nur unter `-PintegrationTests`
   (`test/integration-oracle/build.gradle.kts:3`). Ohne die Property meldet
   Gradle `BUILD SUCCESSFUL`, **ohne** die Faelle gefahren zu haben; die
   Verifikation nennt diesen Lauf deshalb ausdruecklich
   (`make integration INTEGRATION_TASKS=":test:integration-oracle:test"`).

   **Der MSSQL-Ziel-Leg gehoert nicht mehr zu P1.** P1 aendert nichts am
   Erzeugten, also gibt es keine neue Oracle→MSSQL-Zusicherung zu bauen. Die
   Kante dort (`E057` statt Spatial-Index) ist eine Aussage der **Eigner-Frage**
   (s. Abgrenzung) und im Generator bereits gepinnt
   (`MssqlDdlGeneratorIndexTest.kt:134-145`).

   Die vorhandenen Smokes tragen das weiterhin nicht: `smoke-spatial-ora.sh`
   fahrt PostGIS→Oracle, nie ein Oracle-Reverse; `make sample-db-spatial-smoke`
   fahrt PG↔MySQL; und `sample-db-spatial-ora-smoke` laeuft in **keiner**
   Workflow-Datei (die einzige Spatial-Workflow-Datei fahrt den anderen Smoke).

   **Nulllinie gemessen 2026-09-16** (alle drei Module, in denen die Pakete ihre
   Abnahme finden): `:test:integration-oracle`, `:test:integration-postgresql`
   und `:test:integration-mysql` laufen unter `-PintegrationTests` grün; kein
   Modul hat eine Selbstueberspringung (`assumeTrue`/`Assumptions`/`@Disabled`
   kommen in keinem vor), und die Test-Tasks standen als `executed` im Lauf —
   nicht als `SKIPPED`/`UP-TO-DATE`. Ein roter Lauf **nach** einem Paket bedeutet
   damit etwas; ohne diese Nulllinie waere „grün" von „nichts gefahren"
   ununterscheidbar. `OracleSpatialIntegrationTest` traegt acht
   Testdeklarationen, davon die A1-relevante Pinnung `:340-389`.
3. Je Paket der Ort: P1/P2a `:adapters:driven:driver-oracle`; P3 und P4
   `:adapters:driven:driver-postgresql` — Typ-Mapping **und** dessen
   Kanonisierer-Projektion, denn in `hexagon/ports-common` liegt nur das
   Interface (`NeutralTypeCanonicalizer.kt`), die PG-Projektion liegt im Treiber;
   P5 und P6 `:adapters:driven:driver-mysql`; P2b
   `:adapters:driven:driver-postgresql`.
   Die Abnahmefaelle fuer P1/P2a liegen in `:test:integration-oracle` (s.
   Punkt 2), die fuer P6 in `:test:integration-mysql`
   (`make integration INTEGRATION_TASKS=":test:integration-mysql:test"` — die
   Nulllinie dieses Moduls ist gemessen, s. Punkt 2). Sabotage je Paket, auch je
   Teilpaket von P2.
4. `make docs-check` — P1, P4 und P5 fassen Spec-Bezuege an (P1 beide
   „exakt passend"-Stellen, P4 beide Mapping-Seiten, P5 vier Registrierungsorte);
   die uebrigen Pakete nicht. P3, P5 und P6 fassen zusaetzlich `docs/user/` an —
   das Gate prueft es mit, aber nur auf Verweise, nicht auf Inhalt.
5. **Was nachgemessen ist und was nicht.** Die Posten A2, A3, A4, B1, B2 stammen
   aus der Konsumentenmessung; geprueft ist im Repo jeweils die **Vorbedingung**
   (der Filter fehlt an genau dieser Stelle; der Zweig fehlt; die Note nennt
   Grund und Ausweg nicht), **nicht** die Zahl oder der Objektname. Wer die Pakete
   schneidet, prueft die Meldung selbst nach — die Zahlen aus A3 sind dafuer kein
   Beleg.

## Offen (nicht Teil dieses Slices)

- **A6 — Vertragsfrage: SpatiaLite verwirft die ganze Tabelle.** Die Spec
  schreibt den heutigen Effekt an drei Stellen fest (`spec/cli-spec.md:467`,
  `spec/neutral-model-spec.md:1503`, `spec/ddl-generation-rules.md:2688`)
  und begruendet ihn („keine partielle DDL"). Zu entscheiden ist, ob die
  Spalte allein entfallen darf oder die Tabelle mit Hinweis angelegt wird. **Der
  Ort ist angelegt:** [`../open/spatial-profile-e052-ganze-tabelle.md`](../open/spatial-profile-e052-ganze-tabelle.md)
  (2026-09-16) traegt die drei Wege samt Preis. Zu beachten:
  [`ADR 0016`](../../adr/0016-spatialite-metadata-bootstrap.md) traegt
  `status: accepted` und ist damit im Kern eingefroren (`make doc-immutable`) —
  die dort aufgeschobene Generate-Grenze laesst sich nicht im Vorbeigehen
  nachziehen, sondern nur ueber einen neuen ADR oder eine Statusaenderung.
- **Eine zweite JSON-Art** (`json` gegen `jsonb`, aus B3) braucht eine eigene
  Entscheidung — **der Ort ist angelegt:**
  [`../open/json-jsonb-zweite-json-art.md`](../open/json-jsonb-zweite-json-art.md)
  (2026-09-16); sie braucht ihn, weil die Kandidatenfamilie
  [`../open/pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md)
  `json`/`jsonb` ausdruecklich ausschliesst. Zu beachten bei der Begruendung:
  `spec/neutral-model-spec.md:148` ist eine **Soll**-Tabelle fuer die
  **Render**richtung und sagt zum Rueckweg `jsonb` → `json` nichts — der Fall
  beruht also auf der Render-Haelfte, und die ist gedeckt.
- **Der `W137`-Ledger-Widerspruch** (aus P5): `spec/ledger.md:64` und `:77` geben
  derselben Kennung zwei Bedeutungen; im Code sind beide real
  (`ComputedExpressionDecidability.kt:37`, `MssqlColumnConstraintHelper.kt:333`),
  und das YAML-Ledger legt sich auf die MSSQL-Bedeutung fest. **Dazu gehoert die
  zweite Kollision, die den Fall ueberhaupt tragfaehig macht:** `R345` traegt im
  Code zwei verschiedene Notizen (MSSQL-`geography`, `MssqlTypeMapping.kt:55`,
  gegen Oracles Sequenz-`START WITH`, `OracleSchemaReader.kt:381-387`) und fehlt
  im Ledger ganz — wie `R401`. Ablageort ist
  [`../open/warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md),
  aber **nicht als blosser Verweis**: der Eintrag fuehrt bis heute nur W-Codes
  („Backfill" plus ein Gate auf `Wxxx`), er muss um die Doppelbelegung und den
  R-Bereich **erweitert** werden — sonst faengt sein Akzeptanzkriterium genau das
  nicht, was hier auffiel.

## Was der Slice bewusst nicht tut

Er fuehrt **keinen** `interval`-Neutraltyp ein, **keine** zweite JSON-Art und
entscheidet **nicht**, ob ein fehlender SRID blockiert. Alle drei sind Modell-/
Vertragsfragen (s. „Offen"). Er fasst **A6 nicht an**: der Ausgang ist laut
(`E052`) und die Spec schreibt ihn fest. Und er **lockert den SRID-Abgleich
nicht** (s. A1) — obwohl genau das der naheliegende Fix waere.

Und er behebt **nicht** „alles": von den **zehn** lebenden Posten sind **zwei**
Defekte mit Fix (A5, C1), **einer** eine Meldung, die handelbar wird (A4),
**drei** Verluste, die benannt werden (A1, B1 und das unvermeidbare B2), **zwei**
Fremdobjekte, die ausgeschlossen werden (A2, A3), und **zwei** spec-konform
(A6, B3). B4 ist widerlegt und war nie Teil der zehn; C1 ist am 2026-09-16 aus
der Compare-Messung uebernommen und bringt dort seinen Praezedenzfall mit.
