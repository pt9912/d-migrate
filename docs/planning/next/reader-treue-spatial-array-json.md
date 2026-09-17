# Reader-Treue: stille Typverluste bei Spatial, Array und JSON

> **Status:** Entwurf mit Scope, **aktivierungsbereit** (2026-09-17:
> Aktivierungsschnitt — Pakete P0 und P7–P13, Reihenfolge, Anker nachgemessen;
> davor Review-Runden 1 und 2, 2026-09-16).
> Gemeldet gegen `1.7.1` (C1 stammt aus der Compare-Messung und ist am
> 2026-09-16 uebernommen; D1–D6 aus dem Compare-Bau, s. Abschnitt D). Zwei
> Angaben je Posten, getrennt gefuehrt:
> **Belegart** — *nachgemessen* (A1, A5, B3, B4, C1, D1–D6; bei D4 und D5 ist
> je ein Teil nur im Code geprüft, s. dort) oder *blosse Meldung*
> (A2, A3, A4, B1, B2); bei den Meldungen ist im Code nur die **Vorbedingung**
> geprueft, nicht die Zahl oder der Objektname (s. „Verifikation", Punkt 6).
> **Verbleib** — Paket (A1→P1, A2/A3→P2a/P2b, A4→P3, A5→P4, B1→P5, C1→P6,
> A6→P7, B3→P8, D2→P9, D4→P10, D5→P11, D6→P12 (Reader-Hälfte), D1→P13),
> eingeordnet ohne Paket (D3: vertragsgleich bzw. schon laut) oder widerlegt
> und entfallen (B4). **P0** ist kein Posten, sondern die Abnahme: die
> Compare-Matrix bekommt native Seeds und einen Silent-Loss-Check und trägt
> damit die Pakete P3, P4, P5, P6, P8–P13. Die Reihenfolge steht am Anfang von
> „Arbeitspakete".
> **Vorbedingung / Gate:** **eines, halb** — die Eigner-Frage aus A1 (s.
> Abgrenzung) ist mit P1 zur Haelfte beruehrt: die „Fund"-Seite (Note) nimmt P1
> vor, die „Block"-Seite bleibt offen. Die Aktivierung sperrt sie nicht. Beim
> Schneiden sind **zwei** Eigner-Fragen dazugekommen (s. „Offen"), keine
> sperrt den Slice: die Entscheidungsregel von P13 (D1) sperrt nur P13, die
> Modellfrage hinter D2 sperrt nichts — P9 macht den Verlust in jedem Ausgang
> laut. Der Spatial-Vertrag, den A1
> beruehrt, steht in
> [`spec/type-mapping.md`](../../../spec/type-mapping.md) (dort **zweimal**: beim
> Oracle-Reverse und in den Render-Regeln je Dialekt) und im Geometrie-/
> Spatial-Profil-Modell von
> [`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md) — **nicht**
> in einem ADR. [`ADR 0016`](../../adr/0016-spatialite-metadata-bootstrap.md)
> regelt den SpatiaLite-Bootstrap auf dem **Migrate**-Pfad und hat den
> **Generate**-Pfad ausdruecklich als bewusste Scope-Grenze aufgeschoben
> (Abschnitt „Verworfene/aufgeschobene Alternativen"); fuer A6, der auf dem
> Generate-Pfad sitzt, ist sie damit **keine** Quelle, und ihr Kern bleibt von
> P7 unberührt (P7 braucht keinen ADR). Ob ein fehlender SRID ein
> **Fund** oder ein **Block** sein soll, ist eine Eigner-Frage (s. Abgrenzung).
> **Nachtrag 2026-09-17:** A6 und B3 sind vom Eigner entschieden und werden bei
> der Aktivierung zu Paketen; dazu drei Reader-Posten aus dem Compare-Bau (D1–D3,
> s. Abschnitt D). Beides ist hier festgehalten, damit es nicht mit dem
> Compare-Slice nach `done/` wandert. Bei dessen Graduation (2026-09-17) kamen
> D4–D6 dazu (Generator-Warnung bei `ALWAYS`, `fk_0`-Namen, T-SQL-Quoting samt
> `E053`). Der Aktivierungsschnitt vom selben Tag hat jeden D-Posten
> eingeordnet (Abschnitt D, je „Einordnung").
> **Die beiden `open/`-Einträge** zu A6 und B3 verweisen auf P7 bzw. P8 und
> schliessen mit der Graduation dieses Slices, nicht vorher.
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

**Die D-Posten verteilen sich auf beide Muster** (Abschnitt D): D1 und D4 sind
Muster 1 auf der **Generator**-Seite (der Typ einer berechneten Spalte, der
Identity-Modus `ALWAYS` gehen still verloren), D2 und D5 Muster 1 auf der
**Reader**-Seite (`numeric` wird Gleitkomma, der Name eines Fremdschlüssels
wird erfunden), D6 ist Muster 2 (T-SQL-Quoting als Server-Text im Modell). D3
ist keines von beiden (s. dort). Damit sind es sechzehn lebende Posten.

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
`PostgresProgrammabilityMetadataQueries.listFunctions` filtert bloss
`routine_name NOT LIKE 'pg_%'` — und genau dort haengen die ~1000
PostGIS-Funktionen. (Anker 2026-09-17 nachgemessen: die Bedingung steht
inzwischen in Zeile 127; `:118` war der Funktionskopf. **Dazu** tragen
`listAggregates` und `listProcedures` im selben Objekt gar keinen
Extension-Filter — PostGIS bringt auch Aggregate mit, etwa `ST_Union`.) In der Praxis wandert jede extension-lastige Datenbank als
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

> **Überholt am 2026-09-17:** der Eigner hat einen **dritten** Weg entschieden
> — `NOT NULL` nativ über `AddGeometryColumn`, die Tabelle bleibt ganz —, und
> A6 wird Paket P7 (s. Abschnitt D). Keine der drei Zeilen oben kippt dabei:
> alle drei sagen, **was** mit einer blockierten Tabelle geschieht, keine sagt,
> **wann** sie blockiert wird. Dass `NOT NULL` ein Auslöser ist, steht
> nirgends in `spec/` — der Profilabschnitt 16.5 nennt die Auslöser gar nicht
> (nachgemessen beim Aktivierungsschnitt). P7 trägt sie dort erstmals ein.

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

> **Überholt am 2026-09-17:** der Eigner hat entschieden — **gleichsetzen,
> aber laut**. Das Modell behält einen JSON-Typ; der PostgreSQL-Reverse meldet
> die `json`-Spalte mit eigenem Code. B3 wird Paket P8 (s. Abschnitt D).

**B4 — entfaellt (Praemisse widerlegt, im Code nachgemessen).**

> **Korrektur nach Review.** Der erste Entwurf fuehrte „PG `interval` wird still
> zu `text`, kein Fund" als Posten. Es gibt einen Fund.

`interval` faellt durch alle Zweige von `PostgresTypeMapping.mapColumn` in den
gemeinsamen `else`-Fallback — mit **`R301`**, Severity `WARNING`, „Unknown
PostgreSQL type 'interval' (udt: interval) mapped to text"
(`PostgresTypeMapping.kt:79-86`). `spec/type-mapping.md`, Abschnitt
„Reverse-Mapping else-Fallback", schreibt den
Fallback fuer **alle fuenf** Reverse-Mapper als „erzeugt **immer** eine
diagnostische Warning-Note" fest (der fruehere Anker `:412` zeigte beim
Aktivierungsschnitt auf den Oracle-Abschnitt).

**Was bleibt, ist ein Nachtrag am Tracker, kein Slice-Posten:** die Familie hat
einen offenen Ort,
[`../open/pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md)
— und `interval` fehlte in dessen Kandidatenliste (Abschnitt „Kandidaten"). Der Nachtrag ist
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
Anwenderhandbuch (`docs/user/anwenderhandbuch.md:2198`) — dort zieht P6 mit.

### D — Nachträge vor der Aktivierung (2026-09-17)

**Entschiedene „Offen"-Posten.** Beide werden bei der Aktivierung eigene Pakete;
die Begründung und die Messung stehen in den `open/`-Einträgen.

- **A6 — NOT NULL nativ.** Eine Geometriespalte mit `NOT NULL` wird über das
  `not_null`-Argument von `AddGeometryColumn` angelegt statt die Tabelle mit
  `E052` zu verwerfen (gemessen an SpatiaLite 5.1.0); `E052` bleibt für PK,
  UNIQUE, Default und Fremdschlüssel. Die drei Spec-Stellen nennen `NOT NULL`
  nicht mehr als Auslöser. Quelle:
  [`../open/spatial-profile-e052-ganze-tabelle.md`](../open/spatial-profile-e052-ganze-tabelle.md).
  **Einordnung: P7.** Beim Schneiden nachgemessen, und drei Dinge sind anders
  als der Satz davor annimmt: (1) Die drei Spec-Stellen haben `NOT NULL` nie
  genannt; die Auslöser stehen nirgends in `spec/` (s. A6, „Überholt"). (2) Die
  Regel steht im Code **zweimal** — im Generate-Pfad
  (`SqliteTableDdlSupport.hasSpatialMetadataConflict`) und im Migrate-Pfad
  (`SqliteSpatialDiffOps.geometryColumnMetadataBlock`, aufgerufen für
  `CreateTable` und `AddColumn`, dort als `SPATIAL_METADATA_UNSUPPORTED` mit
  `MANUAL_ACTION_REQUIRED`). Zieht nur einer nach, sagen `schema generate` und
  `schema migrate` Verschiedenes. (3) Der Rückweg bricht sonst: SpatiaLite
  legt die Spalte als `NOT NULL DEFAULT ''` an, und der SQLite-Reverse liest
  jeden Default wörtlich (`SqliteTypeMapping.parseDefault` macht aus `''` ein
  `default: ""`) — ein `default` ist aber selbst ein `E052`-Auslöser. Ohne
  Gegenmaßnahme im Reader blockiert der zweite Generate genau die Tabelle, die
  der erste angelegt hat.
- **B3 — `json` laut.** Ein JSON-Typ im Modell; der PostgreSQL-Reverse meldet
  eine `json`-Spalte mit eigenem Code (sie wird als `jsonb` gerendert). Quelle:
  [`../open/json-jsonb-zweite-json-art.md`](../open/json-jsonb-zweite-json-art.md).
  **Einordnung: P8.** Nachgemessen: der Zweig `"json", "jsonb"` in
  `PostgresTypeMapping.mapSpecialTypes` steht unverändert; dieselbe
  Gleichsetzung trägt das Array-Element (`mapArrayElementType`, `json[]` →
  Element `json`).

**Neue Posten aus dem Compare-Bau** (gemessen dort, Belegart: *nachgemessen*;
Quelle: [`../done/compare-projektion-und-normalisierung.md`](../done/compare-projektion-und-normalisierung.md),
Abschnitt „Offen"). Das Paket je Posten wird bei der Aktivierung geschnitten.

- **D1 — Der Typ einer berechneten Spalte in SQL Server.** SQL Server führt für
  berechnete Spalten keinen deklarierten Typ; der Reverse liest den aus dem
  Ausdruck abgeleiteten (`decimal(23,2)` für `quantity * unit_price` bei
  `decimal(12,2)`), das Soll sagt `decimal(14,2)`. Ein Fund in PG↔MSSQL und
  MSSQL↔MySQL, schon in 1.7.1. Zu messen: ob der Generator den Ausdruck in
  `CAST(… AS <Solltyp>)` hüllen soll, damit der Reverse den Solltyp zurückliest —
  und was das für den Ausdrucksvergleich (`W137`) bedeutet. Eigner-Entscheidung
  vom 2026-09-16: der Posten gehört hierher.
  **Einordnung: P13, zuerst Messung.** Der Posten ist auch ein
  **Generator**-Posten: `spec/ddl-generation-rules.md` (3.2a) schreibt für SQL
  Server ausdrücklich „ohne Typ" vor, und der Generator meldet nicht, dass der
  deklarierte Typ damit wegfällt — spec-konform, aber still. Die CAST-Hülle hat
  einen Haken, den der Reader schon kennt: SQL Server legt `CAST` als `CONVERT`
  ab (Kommentar am Computed-Zweig von `MssqlSchemaReader`, gemessen auf 2025),
  und `CONVERT(<typ>, …)` ist auf PostgreSQL und MySQL kein gültiger Ausdruck —
  ohne eine Rückführung im Reader wäre D1 gelöst und ein neuer D6-Fall
  entstanden. Deshalb erst messen, dann nach einer Regel entscheiden (P13).
- **D2 — `numeric` ohne Präzision wird als `float` gelesen**
  (`PostgresTypeMapping.kt:115-119`). Das ist ein stiller Typverlust: eine exakte
  Zahl wird zur Gleitkommazahl. Im Compare-Slice war er Ursache einer
  Falsch-Gleichsetzung, die dort an der Cast-Regel abgefangen wurde; der Verlust
  selbst besteht weiter.
  **Einordnung: Modellposten — laut machen hier (P9), verlustfrei machen ist
  eine Eigner-Frage („Offen").** Ein Defekt mit schlichtem Fix ist es nicht:
  `spec/neutral-model-spec.md` verlangt `precision` und `scale` bei `decimal`
  (Abschnitt „Typkompatibilitäts-Regeln"), `NeutralType.Decimal(precision: Int, scale: Int)` hat
  keine Lücke dafür, und die Ziele haben verschiedene Vorgaben für ein
  `DECIMAL` ohne Angabe (MySQL `(10,0)`, SQL Server `(18,0)`, Oracle
  unbeschränkt, SQLite `REAL`). Ein neutraler `decimal` ohne Präzision ist
  deshalb eine Modellerweiterung nach dem Muster von ADR 0015, mit einer
  Render-Entscheidung je Dialekt. Auch die Nachbarn sind uneins:
  `spec/type-mapping.md` nennt für SQLite `NUMERIC` ohne Präzision → `Float()`
  „akzeptabel", für Oracle `NUMBER` ohne Präzision → `decimal(38,10)`. Und der
  Verlust trifft auch den Rückweg in **denselben** Dialekt: PostgreSQL
  `numeric` → `float` → `DOUBLE PRECISION`. Nachgemessen: die Zeilen
  `:115-119` stimmen; derselbe Zweig steht in `SqliteTypeMapping.mapNumericType`.
- **D3 — Was als `text` ankommt.** Ein `varchar` ohne Länge ist im Modell nicht
  von `text` zu unterscheiden (still); unbekannte PostgreSQL-Typen wie `inet` und
  `interval` landen als `text` mit `R301` (laut, s. B4). Zu klären: ob D3 ein
  Modellposten ist (Kandidatenfamilie
  [`../open/pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md))
  oder nur der `varchar`-Teil hierher gehört.
  **Einordnung: kein Paket.** Der `varchar`-Teil ist **vertragsgleich**:
  PostgreSQL behandelt `varchar` ohne Länge und `text` gleich, und `text`
  ohne `max_length` beschreibt im Modell genau das — es geht keine
  Information verloren, die ein Ziel tragen könnte. Was bleibt, ist eine
  Vergleichsfolge (PostgreSQL schreibt `(spalte)::text` nur an eine
  `varchar`-Spalte); sie ist im Compare-Slice als bewusste Grenze festgehalten
  (dort „Restflächen", „Bewusst ohne eigenen Ort") und wäre, wenn überhaupt,
  ein Kandidat des [Toleranzprofils](compare-toleranzprofil.md). `inet` und
  `interval` sind laut (`R301`), die Modellfrage liegt beim Kandidaten-Tracker.
  **Folge für P0:** die Silent-Loss-Klasse „Nicht-Text-Quelltyp kommt als Text
  an" zählt `varchar` zur Text-Familie; die frühere Fassung von
  „Verifikation", Punkt 5, führte ihn als Beispiel der Klasse und war darin
  falsch.

**Nebenbefund beim Schneiden (N1, im Code geprüft, nicht gemessen).**
`PostgresTypeMapping.mapArrayElementType` endet mit `else -> "text"`: ein
Array unbekannten Elementtyps (`date[]`, `timestamp[]`, `inet[]`) kommt als
`array` mit `element_type: text` zurück — **ohne** `R301`. Das widerspricht
`spec/type-mapping.md` („Reverse-Mapping else-Fallback": der Fallback erzeugt
**immer** eine Warning-Note) und ist genau die Klasse dieses Slices. Er geht
in P9 mit (dieselbe Datei, dieselbe Art Note).

**Nachtrag bei der Graduation des Compare-Slices (2026-09-17).** Drei weitere
Posten aus dessen Repro und 5x5-Compare-Matrix (Belegart: *nachgemessen*, dort
unter „Offen"; in der Matrix als Zustand `APPLY-FAIL` gepinnt,
[`examples/mcp-e2e/expected/compare-matrix.env`](../../../examples/mcp-e2e/expected/compare-matrix.env)).
Wie bei D1–D3 wird das Paket bei der Aktivierung geschnitten; ob ein Posten
dann hier bleibt oder als Generator-Thema einen eigenen Plan bekommt, ist Teil
des Schnitts.

- **D4 — Der MySQL-Generator rendert `GENERATED ALWAYS` ohne Warnung.**
  Muster 1 (Fidelity, wie B1): MySQL kennt nur `AUTO_INCREMENT`; der Generator
  rendert eine `always`-Spalte dorthin, **ohne** es zu melden, und der Reverse
  liest `by_default`. Gemessen im Konsumenten-Repro (Schema des Konsumenten,
  fünf Identity-Spalten mit `GENERATED ALWAYS`). Der Schwesterfall hat einen
  Code: SQL Server meldet den umgekehrten Verlust (`BY DEFAULT`) mit `W140`.
  Zu klären: ein eigener Code oder `W140`-analog; ob SQLite (`AUTOINCREMENT`)
  denselben stillen Verlust hat — **nicht geprüft**. Im Vergleich bleibt der
  Modus ein Fund (Fähigkeitsunterschied,
  [ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md));
  eine Toleranz dafür ist Kandidat K2 im
  [Toleranzprofil](compare-toleranzprofil.md), das den Generator-Befund nur als
  Beleg braucht.
  **Einordnung: P10, hier.** Es ist **derselbe Mechanismus** wie B1/P5, nicht
  nur dasselbe Muster: beide Verluste entstehen in
  `MysqlColumnConstraintHelper.generateColumnSql` (hier im Zweig
  `columnGeneratedIdentity`, der `NOT NULL AUTO_INCREMENT` ohne Blick auf den
  Modus schreibt), beide haben dort die Notizliste schon in der Hand — und
  beide fehlen im **zweiten** MySQL-Renderer, dem Migrate-Pfad
  (`MysqlDiffSqlBuilders.columnLine`), der gar keinen Notizkanal hat. Beim
  Schneiden im Code geprüft, **nicht gemessen**: SQLite hat denselben stillen
  Verlust — `SqliteColumnConstraintHelper.generateRowidIdentityColumn` schreibt
  `INTEGER PRIMARY KEY AUTOINCREMENT` für beide Modi ohne Note, und der
  SQLite-Reverse liest die Spalte ohne Modus (Vorgabe `by_default`). Ein
  eigener Code statt `W140`: `W140` ist die SQL-Server-Meldung mit zwei
  Bedeutungen (`BY DEFAULT` **und** Default an einer Identity-Spalte); ihn
  mitzubenutzen erzeugte die nächste Doppelbelegung nach `W137`.
- **D5 — Der SQLite-Reverse nennt die Fremdschlüssel jeder Tabelle `fk_0` ….**
  SQLite führt keine Namen für Fremdschlüssel; der Reverse vergibt sie je
  Tabelle ab `fk_0`, also mehrfach im Schema. SQL Server verlangt eindeutige
  Constraint-Namen und lehnt die erzeugte DDL ab (Matrix: SQLite → SQL Server,
  `Msg 2714`). MySQL verlangt Fremdschlüsselnamen ebenfalls datenbankweit
  eindeutig; die Zelle SQLite → MySQL scheitert heute schon vorher (`ERROR
  1170`, s. [`pk-constraint-prefix-length.md`](pk-constraint-prefix-length.md)).
  Zu klären: schemaweit eindeutige Namen im Reader (etwa mit Tabellenpräfix)
  oder eine Entschärfung im Generator je Zieldialekt. Verwandt, aber nicht
  dieselbe Familie: die server-vergebenen Namen, die `schema compare` ausnehmen
  könnte ([`../open/compare-serververgebene-namen-messauftrag.md`](../open/compare-serververgebene-namen-messauftrag.md)).
  **Einordnung: P11, im Reader.** Die Prämisse „SQLite führt keine Namen" ist
  zu grob: SQLite **bewahrt** den Namen im `CREATE TABLE`-Text, nur
  `PRAGMA foreign_key_list` gibt ihn nicht heraus — und d-migrates
  SQLite-Generator schreibt ihn dorthin (`CONSTRAINT "<name>" FOREIGN KEY …`).
  Den Namen erfindet erst der Reader: `SqliteMetadataQueries.listForeignKeys`
  vergibt `fk_<id>` aus der PRAGMA-Nummer. Der Präzedenzfall liegt im selben
  Reader: `SqliteUniqueConstraintScanner` liest die Namen mehrspaltiger
  UNIQUE-Klauseln aus demselben Text (AP4, `multiColumnUniqueConstraints`).
  **Und derselbe Reader hat den Schwesterfall:** für eine unbenannte
  UNIQUE-Klausel vergibt `syntheticUniqueName` je Tabelle ab `uq_0` — ebenfalls
  mehrfach im Schema, und PostgreSQL (der Index trägt den Namen, eindeutig je
  Schema) wie SQL Server (Einschränkungen sind Schemaobjekte) lehnen das
  ebenso ab. Belegart: `fk_0` gemessen (Matrix, `Msg 2714`); der Name im
  `CREATE TABLE`-Text und die `uq_N`-Kollision nur im Code geprüft. Eine
  Entschärfung im Generator wäre die schlechtere Wahl: sie reparierte die DDL
  und ließe das Modell falsch — der Name aus der Quelle bliebe verloren.
- **D6 — T-SQL-Quoting in berechneten Ausdrücken, und `E053` sieht es nicht.**
  Muster 2 (Modell-Reinheit, wie C1): der SQL-Server-Reverse liest den
  Ausdruck einer berechneten Spalte als Server-Text mit Klammer-Quoting
  (`[quantity]*[unit_price]`). Die Portabilitätsprüfung (`E053`,
  [`RawSqlExpressionPortability`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/RawSqlExpressionPortability.kt))
  meldet T-SQL-Klammern **bewusst nicht** („ohne Herkunft wäre die
  Unterscheidung geraten"); der Generator übernimmt den Text, und PostgreSQL
  (`syntax error at or near "["`) und MySQL (`ERROR 1064`) lehnen die DDL ab
  (Matrix: SQL Server → PostgreSQL/MySQL). Zwei Hälften: der Reader (Quoting
  entfernen, wo der Bezeichner es nicht braucht — der MSSQL-Präzedenzfall
  `N'…'` aus C1 ist dieselbe Klasse) und die Prüfung (die Herkunft ist inzwischen
  bekannt: ein Reverse trägt seine Markierung mit Dialekt). Ob die Prüfung hier
  oder in einem eigenen Plan nachzieht, entscheidet der Schnitt.
  **Einordnung: die Reader-Hälfte ist P12, die Prüfungs-Hälfte geht nach
  „Offen".** Die Reader-Hälfte ist kleiner als gedacht: die Normalisierung gibt
  es schon — `MssqlTypeMapping.normalizeCheckExpression` entfernt `N'…'` und
  Klammer-Quoting und wird für CHECK-Ausdrücke aufgerufen
  (`MssqlMetadataQueries`); der Computed-Zweig in `MssqlSchemaReader` übernimmt
  `computedDefinition` dagegen roh. Die Prüfungs-Hälfte ist eine
  **Regeländerung** der Spec: `spec/ddl-generation-rules.md` (8.3, „Roher
  Ausdruckstext") sagt ausdrücklich „Beurteilt wird das **Ziel**, nicht die
  Herkunft" und nimmt T-SQL-Klammern bewusst aus. Sie über die
  Reverse-Markierung zu kippen berührt die Portabilitätsprüfung aller fünf
  Generatoren; nach P12 erreicht der Fall nur noch ältere Reverse-Dateien und
  handgeschriebenen T-SQL-Text. Das rechtfertigt keinen Ausbau in diesem Slice.
  Nebenbei gesehen: 8.3 zählt drei Felder mit rohem Ausdruckstext auf, der
  Code prüft auch den Berechnungsausdruck (`computedRefusal`) — die Spec hinkt
  hier dem Code nach; P12 zieht den Satz mit.

## Ziel

Kein Verlust bleibt still. Wo Information nicht erhalten werden kann, wird sie
**benannt** — mit demselben Mechanismus, den die Schwesterdialekte schon
benutzen, statt mit einem je Dialekt neu gebauten.

Das hat fuenf Ausgaenge, und sie in einen Satz zu zwingen waere die erste
Ungenauigkeit (Muster: [`compare-projektion-und-normalisierung.md`](../done/compare-projektion-und-normalisierung.md),
das dieselbe Zerlegung braucht):

1. **Der Verlust ist unvermeidbar und wird benannt** — A1 (der SRID einer
   quotiert kleingeschriebenen Tabelle ist bei Oracle nicht deklarierbar, also
   wird der Ausgang gemeldet), B1 (MySQL verliert die Array-Eigenschaft beim
   Rendern). B2 benennt die Grenze: was im Ziel nicht mehr existiert, laesst sich
   rueckwirkend nicht melden; meldbar ist nur der Hinweg. Dazu seit dem
   Aktivierungsschnitt: B3 (`json` wird `jsonb`, Eigner-Entscheidung „laut"),
   D2 und N1 (`numeric` wird Gleitkomma, ein unbekanntes Array-Element `text` —
   verlustfrei ginge es nur mit einer Modellerweiterung), D4 (MySQL und SQLite
   kennen `ALWAYS` nicht) und, je nach Messung, D1.
2. **Die Meldung ist da, aber unbrauchbar, und wird handelbar** — A4: die Note
   `R401` entsteht, nennt aber weder Grund noch Ausweg.
3. **Der Verlust ist ein Defekt und wird behoben** — A5 (`geography` wird als
   Enum statt als Geometrie gelesen), A6 (die ganze Tabelle fällt weg, obwohl
   SpatiaLite `NOT NULL` tragen kann), D5 (der Reader erfindet
   Fremdschlüsselnamen, die er aus der Quelle lesen könnte).
4. **Fremdes gehoert nicht ins Modell** — A2, A3, C1, D6. Eigener Strang (s. „Der
   gemeinsame Nenner"): hier wird nichts bewahrt, sondern **ausgeschlossen**. Bei
   C1 ist der Ausschluss nicht optional — der Fremdteil macht das Schema
   **ungueltig** (`E012`); bei D6 macht er die DDL jedes anderen Ziels
   ungültig. Ob ein Ausschluss sonst stumm oder mit Hinweis
   geschieht, entscheidet das Paket.
5. **Der Ausgang ist bereits laut oder vertragsgleich und wird nicht angefasst** —
   B4 (widerlegt: `R301` meldet `interval`) und D3 (`varchar` ohne Länge ist
   `text`). A6 und B3 standen hier bis zur Eigner-Entscheidung.

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
  Modellerweiterung und ist **kein** Posten dieses Slices: der Eigner hat
  „gleichsetzen, aber laut" entschieden (B3). P8 baut nur die Meldung.
- **Ein neutraler `decimal` ohne Präzision** (D2) ist eine Modellerweiterung
  mit einer Render-Entscheidung je Dialekt; P9 macht den Verlust laut, baut
  die Erweiterung aber nicht (s. „Offen").
- **A6 wird gebaut (P7), ohne ADR:** ADR 0016 hat den Generate-Pfad
  aufgeschoben, nicht festgelegt, und sein Kern bleibt unberührt. Die Regel
  „keine partielle DDL" bleibt; es ändert sich nur, **wann** eine Tabelle
  blockiert.
- **Die Portabilitätsprüfung für T-SQL-Quoting** (D6, zweite Hälfte) ist eine
  Regeländerung der Spec und nicht Teil dieses Slices (s. „Offen"); P12 baut
  nur die Reader-Hälfte.
- **Der Identity-Modus bleibt im Vergleich ein Fund** (ADR 0057). P10 meldet
  den Verlust am Generator; eine Toleranz dafür ist Kandidat K2 im
  [Toleranzprofil](compare-toleranzprofil.md), nicht dieses Slices.
- **Die Oracle-Image-Frage ist geklaert** (s. o.) und braucht nichts.

## Arbeitspakete

### Reihenfolge und Abhängigkeiten (Aktivierungsschnitt 2026-09-17)

Die Matrix ist die Abnahme (P0), und die Matrix pinnt heute Zustände, die
einzelne Pakete auflösen. Daraus folgt die Reihenfolge: **jede Änderung an den
gepinnten Zellen soll einen Grund haben, der im Diff der Erwartungsdatei
allein steht.**

| Schritt | Paket | Warum an dieser Stelle | Hängt ab von |
| --- | --- | --- | --- |
| 1 | **P6** | Die Matrix pinnt die MySQL-Zeile als `INVALID`/`E012-introducer`; P6 öffnet diese drei Zellen. Der Neu-Pin soll **nur** den Introducer-Effekt zeigen, nicht zugleich die Seeds aus P0. Zeigt die geöffnete Zeile neue Befunde (MySQL als Quelle ist bisher nie gemessen), werden sie vor dem Pin benannt — als D-Posten hier oder als `open/`-Eintrag —, nicht mit einer Zahl weggepinnt. | — |
| 2 | **P0** | Macht die Matrix zur Abnahme. Jedes spätere Paket braucht seinen Fall darin, **bevor** es gebaut wird: rot ohne Fix ist nur prüfbar, wenn der Fall vorher läuft. Zweiter, eigener Neu-Pin: nur die Seeds. | P6 (nur wegen der getrennten Pins) |
| 3 | **P12**, **P11** | Öffnen die übrigen `APPLY-FAIL`-Zellen (SQL Server → PostgreSQL/MySQL, SQLite → SQL Server). Jede geöffnete Zelle misst danach auch die Seeds ihrer Quelle — die Pakete danach bekommen mehr Abnahmefläche. | P0 |
| 4 | **P5**, dann **P10** | Derselbe Mechanismus (D4). P5 baut den fehlenden Notizkanal im MySQL-Migrate-Pfad, P10 benutzt ihn und nimmt SQLite dazu. | P0; P10 auf P5 |
| 5 | **P8**, **P9** | Klein und unabhängig, PostgreSQL-Reverse (P9 auch SQLite). | P0 |
| 6 | **P4**, dann **P3** | Beide an den PostGIS-Metadaten. P4 klärt zuerst den Ort des SRID-Kriteriums und liest `geography_columns` neu; P3s Hinweis deckt danach beide Sichten. | P0 (P3-Bein) |
| 7 | **P2b** | Abnahme in `:test:integration-postgresql`, nicht in der Matrix (sie hält PostGIS bewusst aus `public`, s. P0). | — |
| 8 | **P1**, **P2a** | Ein gemeinsamer Lauf von `:test:integration-oracle` (`ORACLE_FULL`). | — |
| 9 | **P7** | Abnahme in `:test:integration-sqlite` und im `[lite]`-Bein von `make sample-db-spatial-smoke`; die Matrix fährt kein SpatiaLite. | Nulllinie von `:test:integration-sqlite` (s. „Verifikation", Punkt 2) |
| 10 | **P13** | Zuletzt: erst messen, dann nach der Regel entscheiden, die der Eigner bestätigt. Variante a setzt die Normalisierung aus P12 voraus. | P12; Eigner-Frage 1 |

Die Schritte 7 bis 9 hängen an keinem anderen Paket und dürfen früher kommen;
sie berühren die Matrix nicht.

**Codes, beim Schneiden reserviert** (frei nachgemessen: keine der Kennungen
kommt in Code, Spec, Ledger oder Plänen vor). Die R-Codes folgen den
Bereichen, die der Code tatsächlich benutzt — registriert ist der R-Bereich
nirgends (s. „Offen", Ledger):

| Paket | Code | Bereich im Code |
| --- | --- | --- |
| P1 | `R370` | Oracle- und SQL-Server-Reverse (`R340`–`R369`) |
| P8 | `R402` | PostgreSQL-Reverse (`R400`, `R401`) |
| P4 | `R403` | PostgreSQL-Reverse |
| P9 | `R404` (PostgreSQL), `R221` (SQLite, `R200`–`R220`) | je Dialekt; N1 benutzt `R301` |
| P3 | `R405` — nur, wenn P3 eine eigene Note braucht statt `R401` zu schärfen | PostgreSQL-Reverse |
| P5 | `W162` | Kompatibilitätswarnungen (bis `W161`) |
| P10 | `W163` | ein Code für MySQL und SQLite (Muster: `W132`) |
| P13, Variante b | `W164` | nur, wenn die Messung Variante a ausschließt |

### P0 — Die Compare-Matrix wird Abnahme: native Seeds und Silent-Loss-Check

**Befund:** „Verifikation", Punkt 5 (Auftrag aus dem Compare-Slice, dort
„Restflächen": „Native Typ-Seeds und Silent-Loss-Check der Compare-Matrix").
**Ort:** `examples/mcp-e2e` —
[`examples/mcp-e2e/scripts/smoke-compare-matrix.sh`](../../../examples/mcp-e2e/scripts/smoke-compare-matrix.sh),
eine neue Bibliothek neben
[`examples/mcp-e2e/scripts/lib/compare-guards.sh`](../../../examples/mcp-e2e/scripts/lib/compare-guards.sh),
die Seeds unter `fixtures/seeds/` (je Dialekt eine Datei),
[`examples/mcp-e2e/expected/compare-matrix.env`](../../../examples/mcp-e2e/expected/compare-matrix.env),
der README-Abschnitt „Compare-Matrix 5x5" und
[`examples/mcp-e2e/docker-compose.yml`](../../../examples/mcp-e2e/docker-compose.yml)
(PostgreSQL-Image). Kein Produktionscode.

**Was die Matrix heute kann und was fehlt.** Sie wendet
`fixtures/seeds/<dialekt>.sql` schon an, wenn es die Datei gibt (Kopf des
Skripts, „Anknuepfungspunkt fuer native Typ-Seeds"); es gibt keine. Sie pinnt
Zahl und Codes der **Vergleichsfunde** je Zelle — aber weder die Notes eines
Reverse noch die des Generate-Schritts, und sie prüft nicht, ob ein Reverse
eine Spalte still verändert oder verliert. Genau das braucht die Abnahme der
Pakete dieses Slices.

**Teil 1 — Seeds.** Je Dialekt eine Datei mit Tabellen unter eigenem Präfix
(`sl_`), jede Spalte mit einem maschinenlesbaren Kommentar: nativer Typ,
Posten, erwartete Klasse. **Kein Seed ohne Posten** — jede Spalte nennt das
Paket, dessen Fall sie trägt; ein Seed, der bloß „auch mal" einen Typ
ausprobiert, erzeugt Zellrauschen ohne Abnahmewert. Mindestumfang:

| Quelle | Seed-Spalten | trägt |
| --- | --- | --- |
| PostgreSQL | `geography(Point,4326)` | P4 |
| PostgreSQL | `integer[]`, `text[]`, `date[]` | P5, N1 (P9) |
| PostgreSQL | `json`, `jsonb` | P8 |
| PostgreSQL | `numeric` ohne Präzision | P9 |
| PostgreSQL | `integer GENERATED ALWAYS AS IDENTITY` | P10 |
| PostgreSQL | `varchar` ohne Länge, `interval` | D3 (Gegenprobe: Text-Familie bzw. `R301`, beides kein stiller Verlust) |
| SQL Server | **keiner vor P13** (s. unten) | — |
| SQLite | unbenannter Spalten-Fremdschlüssel und unbenannte mehrspaltige UNIQUE-Klausel in **zwei** Tabellen | P11 |
| SQLite | `NUMERIC` ohne Präzision | P9 |
| MySQL | — (C1/P6 trägt die Fixture schon: LIKE-CHECK mit String-Literal) | P6 |

Zwei Zuschnitte der Seed-Tabellen folgen aus den Zielen: die
**Spatial-Spalten** stehen in einer eigenen Tabelle — SQLite blockiert ohne
Profil `spatialite` (die Matrix setzt keines) die ganze Tabelle mit `E052`,
und die übrigen Seed-Spalten kämen dort sonst nie an. Und die
**`ALWAYS`-Spalte** ist alleiniger Primärschlüssel ihrer Tabelle — nur dann
rendert SQLite sie als `AUTOINCREMENT`, sonst greift `W135` und P10 hätte auf
SQLite keinen Fall.

**Warum SQL Server keinen D1-Seed bekommt.** Eine berechnete Spalte mit
`CAST(… AS decimal(14,2))` legt SQL Server als `CONVERT([decimal](14,2),…)` ab;
auch nach P12 bleibt davon `CONVERT(decimal(14,2), …)`, das PostgreSQL und
MySQL ablehnen und das `E053` nicht erkennt (D6, zweite Hälfte). Der Seed
schlösse die Zellen SQL Server → PostgreSQL/MySQL wieder, die P12 öffnen
soll. D1 ist in der Matrix ohnehin unsichtbar — beide Seiten sind Reverses
und tragen denselben abgeleiteten Typ; sichtbar ist er im Roundtrip-Harness
(Datei gegen Reverse, SQL Server: „abgeleiteter Typ der berechneten Spalte").
P13 misst deshalb in `:test:integration-mssql` und im Roundtrip.

**PostGIS im PostgreSQL-Dienst.** `geography` braucht PostGIS; der Dienst
fährt heute `postgres:18.6-trixie`. Er bekommt das PostGIS-Image, das
`examples/sample-db/docker-compose.yml` schon per Digest pinnt
(`postgis/postgis:18-3.6`), und die Extension liegt im **eigenen** Schema
`postgis` mit gesetztem `search_path` auf Datenbankebene — die Konfiguration,
die die PostGIS-Doku empfiehlt. So bleibt der A3-Fall (~1000 Funktionen in
`public`) aus der Matrix: der MCP-Reverse liest Routinen **immer** (Compare-Slice,
„Reverse-Umfang CLI gegen MCP"), und in `public` überschwemmte er jede
PostgreSQL-Zelle. P2b bleibt deshalb bei `:test:integration-postgresql`.

**Das P3-Bein.** A4 braucht den umgekehrten Zustand: PostGIS im eigenen
Schema, **ohne** `search_path`. Dafür eine zweite Datenbank im selben
PostgreSQL-Dienst und eine zweite Verbindung — **nur** in der
Server-Konfiguration, die der Lauf ohnehin selbst schreibt
(`out/compare-matrix/server.d-migrate.yaml`), nicht in der geteilten
`.d-migrate.yaml`, die auch der Scope-Smoke liest. Das Bein ist ein einzelner
Reverse je Lauf, ohne Zellen, mit eigenem kleinen Seed (eine
`postgis.geometry(Point,4326)`- und eine `postgis.geography(Point,4326)`-Spalte).
Die A4-Degradierung selbst fällt in keine der drei Klassen unten (die Spalte
bleibt `geometry`, nur ohne Subtyp und SRID); abgenommen wird P3 deshalb über
die Report-Codes dieses Beins (Teil 3).

**Teil 2 — Silent-Loss-Check.** Auf jedem Reverse, drei Klassen:

1. **Ein Nicht-Text-Quelltyp kommt als Text an** — im Reverse der Quelle, gegen
   die Seed-Kommentare. Die Text-Familie (`text`, `varchar`, `character
   varying`, `char`, `nvarchar`, `clob`, …) zählt nicht (D3). Zu den Klassen
   gehört auch ein Array, dessen Element als `text` ankommt (N1).
   Auf den Reverses der Ziele gilt die Klasse **nicht**: dort sind
   Degradierungen die Regel (SQLite-Typaffinität) und sind am Generate-Schritt
   zu melden, nicht am Reverse — das prüft Teil 3.
2. **Ein `ref_type` ohne Eintrag in `custom_types`** — auf jedem Reverse. Ein
   solcher Verweis ist nie richtig (A5).
3. **Eine Spalte fehlt im Reverse** — im Reverse der Quelle gegen die
   Seed-Kommentare; im Reverse des Ziels gegen den Reverse der Quelle, außer
   für Tabellen und Spalten, die der Generate-Schritt in `skipped_objects`
   nennt.

Ein Fall ist **laut**, wenn der zugehörige Report (Reverse-Report des Jobs bzw.
der Sidecar-Report des Generate-Schritts) eine Note mit genau diesem Objekt
trägt; dann ist er kein Fehlschlag. Ein stiller Fall ist ein Fehlschlag —
**außer** er steht in einer festen Liste bekannter Befunde im Harness-Code, mit
dem Paket, das ihn auflöst (Muster: `known_introducer_invalid` in
`lib/compare-guards.sh`). Die Liste ist Code, keine Erwartung: sie lässt sich
nicht mit `--update-expectations` erweitern, und ein Eintrag, der im Lauf
**nicht** auftritt, ist selbst ein Fehlschlag („bekannter Befund
verschwunden — Liste nachziehen"). Das Paket, das einen Befund auflöst,
streicht seinen Eintrag im selben Commit.

**Teil 3 — Codes je Reverse und je Generate.** Zwei neue Schlüsselfamilien in
`expected/compare-matrix.env`, versionsgebunden und pinnbar wie `CELL_`/`CODES_`:

- `REPORT_CODES_<DIALEKT>` — die Codes des Reverse-Reports der Quelle mit
  Anzahl (und `REPORT_CODES_POSTGRESQL_NOSEARCHPATH` für das P3-Bein);
- `GEN_CODES_<QUELLE>_<ZIEL>` — die Codes aus dem Sidecar-Report des
  Generate-Schritts (`generated.sql.report.yaml`; die CLI schreibt ihn neben
  `--output`), mit Anzahl.

Damit wird ein Paket, das eine Note einführt, zu einem **bewussten** Neu-Pin
(`W162` erscheint in `GEN_CODES_POSTGRESQL_MYSQL`), und die Sabotage — Note
zurückgenommen — zu einer Abweichung, die den Lauf rot macht.

**Teil 4 — Schutz und Selbstprüfung** (Muster: sechster Bauabschnitt des
Compare-Slices):
- Silent-Loss-Fehlschläge, ein nicht auftretender bekannter Befund, ein
  unlesbarer Report und ein Seed-Kommentar, der eine nach dem Anwenden nicht
  vorhandene Spalte nennt, laufen über `note_failure` — **nie pinnbar**;
  `--update-expectations` schreibt dann nichts.
- Die Auswertung prüft sich selbst: ein Seed ohne Kommentare, ein Report ohne
  die erwarteten Schlüssel oder ein jq-Fehler scheitern laut, statt „nichts
  gefunden" zu melden.
- Die Einführung der Seeds ist ein eigener, bewusster Neu-Pin; eine
  Seed-Tabelle, die eine bisher messende Zelle in `APPLY-FAIL` kippt, wird vor
  dem Pin geklärt (Seed anpassen oder Posten benennen).

**Oracle bleibt Opt-in.** Der Oracle-Dienst des Harness fährt
`23-slim-faststart` ohne Spatial; A1 und A2 bleiben deshalb bei
`:test:integration-oracle` (`ORACLE_FULL`). Ein `seeds/oracle.sql` ohne
Spatial ist erlaubt, wird aber nur mit `make mcp-e2e-compare-matrix-oracle`
gefahren — und das ist auf dem Messhost bis zur Klärung aus
[`../open/mcp-e2e-oracle-nicht-gefahren.md`](../open/mcp-e2e-oracle-nicht-gefahren.md)
nicht zulässig (fremder Container).

**DoD:**
1. Seeds für PostgreSQL (samt P3-Bein) und SQLite im Umfang der Tabelle
   oben; jede Spalte nennt ihren Posten. SQL Server und MySQL bekommen keinen
   (Begründung oben).
2. Der Silent-Loss-Check läuft mit drei Klassen, der Liste bekannter Befunde
   (beim Einbau mindestens P4 — `geography` als `ref_type` ohne
   `custom_types`, in beiden PostgreSQL-Reverses — und N1, das `date[]` mit
   `element_type: text`) und der Selbstprüfung.
3. `REPORT_CODES_*` und `GEN_CODES_*` sind gepinnt; zwei aufeinanderfolgende
   Läufe sind identisch.
4. Sabotage am Harness, je mit unveränderter Erwartungsdatei: (a) ein Image,
   dessen Reverse eine Note nicht mehr trägt → Klasse rot; (b) ein
   Seed-Kommentar für eine fehlende Spalte → Selbstprüfung rot; (c) ein
   bekannter Befund, der nicht auftritt → rot; (d) ein Lauf mit
   `--update-expectations` und einem stillen Verlust → Datei unverändert, rot;
   (e) ein kaputtes jq-Programm → rot.
5. Mit dem PostGIS-Image bleiben `make mcp-e2e-roundtrip` und
   `make mcp-e2e-smoke` unverändert grün (gemessen, nicht angenommen).
6. README-Abschnitt und Skriptkopf beschreiben Seeds, Klassen, Liste,
   Schlüsselfamilien und das P3-Bein; `bash -n` und shellcheck (Container)
   für alle geänderten Skripte.

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
neue Note ist ein **R-Code** wie `R365`, `R401` und `R345` — reserviert ist
`R370`; der R-Bereich ist
nicht im Ledger registriert (Nebenbefund, s. „Offen"). **Und sie gehoert in die
Anwendersicht:** die Handbuch-Tabelle, die `R365` fuehrt, bekommt sie daneben —
sie ist dessen Schwester. (Anker beim Aktivierungsschnitt nachgemessen: die
Tabelle steht nicht mehr bei `:3021`, sondern unter der Frage „Was liest
`schema reverse` von den Oracle-Routinen nicht?" — trotz der Überschrift die
Stelle, an der `R365` steht. Geht der Satz über Routinen hinaus, gehört die
Überschrift mitgezogen.)

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

**Umfang von P2b (beim Aktivierungsschnitt nachgemessen).** Im selben Objekt
`PostgresProgrammabilityMetadataQueries` filtern **drei** Abfragen nicht nach
Extension-Besitz: `listFunctions` (nur `NOT LIKE 'pg_%'`), `listAggregates`
und `listProcedures` (gar nicht). PostGIS bringt Aggregate mit; P2b nimmt alle
drei. Die Abfragen, die Abhängigkeiten nachschlagen (`deptype IN ('n', 'a')`),
sind etwas anderes und bleiben.

**DoD (je Paket getrennt, damit ein Teilstand entscheidbar bleibt):**
**P2a** — `MDRS_*` erscheint nicht in `sequences:`; **P2b** — ein
PostGIS-in-`public`-Reverse ist nicht mehr um Groessenordnungen groesser als
dasselbe Schema ohne, und er führt weder Funktionen noch Aggregate der
Extension. Je Paket ein Test, je Paket sabotage-verifiziert. Abnahme P2a in
`:test:integration-oracle`, P2b in `:test:integration-postgresql` (die Matrix
hält PostGIS aus `public`, s. P0).

### P3 — Die stille Degradierung benennen (A4)

Ein Posten: Geometrie ohne Subtyp/SRID bei fehlendem `search_path` (A4). Es gibt
dort eine Note (`R401`), aber sie nennt weder Ursache noch Ausweg.

**Wo der Reader den Grund kennt (beim Aktivierungsschnitt nachgemessen):**
`PostgresTableMetadataQueries.listGeometryColumns` fragt zuerst
`to_regclass('geometry_columns')` und gibt bei `null` still eine leere Liste
zurück. Genau dort ist die Ursache bekannt — die Sicht ist nicht erreichbar,
obwohl die Tabelle eine Geometriespalte hat. Nach P4 gilt dasselbe für
`geography_columns`. Ob P3 `R401` schärft oder eine eigene Note braucht
(reserviert: `R405`), entscheidet das Paket: `R401` entsteht für **jede**
PostGIS-Spalte, der neue Hinweis nur im Fehlfall.

**DoD:** Die Note nennt den Grund und den Ausweg („PostGIS-Schema in den
`search_path` aufnehmen"), wo der Reader ihn kennt. **Nicht** ueber
`schema_validate`: das Kommando nimmt nur eine Schemadatei ohne Datenbank
(`SchemaValidateCommand.kt:22`), und Read-Notes landen ausschliesslich im
Reverse-**Report** — das Artefakt-YAML kennt kein `notes`-Feld. Und der Ausweg
gehoert **gespiegelt** ins Anwenderhandbuch — in den Abschnitt „Geodaten
(Spatial) modellieren und übertragen" (3.16), nicht in die Oracle-Tabelle, die
der erste Schnitt hier nannte; eine Handlungsanweisung, die nur im
Reverse-Report steht, findet niemand, der den Report nicht liest. Abnahme:
`:test:integration-postgresql` (PostGIS im eigenen Schema, ohne
`search_path`) und das P3-Bein der Matrix (P0, `REPORT_CODES_POSTGRESQL_NOSEARCHPATH`).

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

**Subtyp und SRID liest der PG-Reverse heute nur aus `geometry_columns`**
(`PostgresTableMetadataQueries.listGeometryColumns`, beim Aktivierungsschnitt
nachgemessen). Für `geography` steht beides in `geography_columns`; P4 liest
diese Sicht mit demselben `to_regclass`-Wächter. Die Note ist ein eigener
R-Code (`R403`) — nicht `R345`, das schon doppelt belegt ist (s. „Offen").
Und die Note gehört in die Anwendersicht: Abschnitt 3.16 des Handbuchs.

**DoD:** `geography(Point,4326)` liest als Geometrie mit `srid: 4326` (nicht als
`enum`, kein Custom-Type); der Rueckweg nach PG ergibt wieder `geography`; beide
Spec-Stellen sind nachgezogen. Abnahme: `:test:integration-postgresql` (mit
`TestImages.POSTGIS`, das dieses Modul bisher nicht benutzt) und die Matrix
(P0: der bekannte Befund `geography` als `ref_type` ohne `custom_types`
verschwindet, sein Eintrag geht im selben Commit; PostgreSQL → SQL Server
ergibt `geography`).

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
W-Code aus dem Dialektbereich (der Ledger fuehrt bis `W161`; `W200`+ ist belegt)
— reserviert ist `W162`.
Der Ledger-Widerspruch wird dabei **nicht** mitbehoben: er geht nach „Offen".

**Zwei Renderer, nicht einer (beim Aktivierungsschnitt nachgemessen).** Der
Array-Verlust entsteht im Generate-Pfad in `MysqlColumnConstraintHelper`
(dort liegt die Notizliste bereits vor) **und** im Migrate-Pfad in
`MysqlDiffSqlBuilders.columnLine`, der `typeMapper.toSql` direkt ruft und
keinen Notizkanal hat. Eine Meldung nur im Generate-Pfad ließe
`schema migrate` still weiter verlieren. Das Vorbild steht im
SQL-Server-Treiber: `MssqlDiffRenderContext.carryOverNotes` übernimmt die
Hinweise des Generate-Spalten-Helfers in die Diagnosen des Migrate-Pfads.
P5 baut diesen Kanal für MySQL; P10 benutzt ihn.

**Der Nachzug gehoert zum Paket — vier Spec-Orte plus die Anwendersicht.** Ein
neuer nutzersichtbarer Code ist erst vollstaendig, wenn er steht in: der W-Tabelle
[`spec/cli-spec.md`](../../../spec/cli-spec.md) (dort zuletzt `W155`–`W161`), der
Lesefassung [`spec/ledger.md`](../../../spec/ledger.md) (Einzelzeile **und**
Bereichszeile — `spec/ledger.md` erklaert selbst, dass **jeder** nutzersichtbare
W/E-Code dort registriert ist), dem maschinenlesbaren `warn-code-ledger-*.yaml`
und der Render-Regel in `spec/ddl-generation-rules.md` beim MySQL-Ziel
(Abschnitt 3.4, „Besonderheiten") — das Muster steht dort fuer MSSQL
(Abschnitt 3.8, `json`, `array` → `NVARCHAR(MAX)` + W137) und Oracle
(Abschnitt 3.9, `array` → `JSON` + W149) bereits.

**Und in der Anwendersicht**, die dieser Slice sonst nirgends anfasst: der Code
gehoert ins Anwenderhandbuch, und zwar dorthin, wo ein Anwender SQL für ein
Ziel erzeugt (Abschnitt 3.2) bzw. in die Typtabelle (Anhang C, Zeile `array`)
— **nicht** in die Oracle-Tabelle, die der erste Schnitt hier nannte (sie
beschreibt Reverse-Notes, keine Generate-Codes) — und, wo er einen Ausweg hat,
in den Troubleshooting-Leitfaden.
`CLAUDE.md` ist an dieser Stelle eindeutig: aendert sich, was ein Anwender tun
oder erwarten kann, aendert sich `docs/user/` mit — ein Fund-Code auf dem
Renderweg ist genau das.

**DoD:** MySQL meldet den Array-Verlust mit eigenem Code (`W162`) auf dem
Generate- **und** dem Migrate-Pfad; die vier Registrierungsorte sind
nachgezogen; der Code trägt einen Test je Pfad, der mit zurueckgenommener
Meldung faellt. Abnahme in der Matrix: `W162` steht in
`GEN_CODES_POSTGRESQL_MYSQL` (bewusster Neu-Pin); zurückgenommen wird der Lauf
rot.

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

**Ort (beim Aktivierungsschnitt nachgemessen):** der Server-Text kommt aus
`MysqlMetadataQueries` (`cc.check_clause`) und geht heute unverändert ins
Modell — anders als beim SQL-Server-Reader, der dieselbe Stelle über
`MssqlTypeMapping.normalizeCheckExpression` führt.

**Spec-Nachzug (beim Schneiden ergänzt):** der SQL-Server-Präzedenzfall steht
in `spec/type-mapping.md` (6.2, „CHECK-Ausdrücke kommen in neutraler
Syntax"); der MySQL-Abschnitt derselben Datei bekommt den entsprechenden
Absatz (Introducer und Backslash-Escape entfallen, sonst nichts). Der erste
Schnitt hatte P6 ohne Spec-Bezug geführt.

**DoD:** PG↔MySQL meldet `ck_customer_email_shape` nicht mehr **und** ein
MySQL-Reverse mit einem solchen CHECK ist validierbar (`schema validate` ohne
`E012`). PG↔MSSQL war vorher sauber und bleibt es. Dazu der `docs/user/`-Nachtrag:
die Grenze von `E012` steht im Anwenderhandbuch (`:2198`) und zieht mit.
**Und die Matrix:** die drei Zellen der MySQL-Zeile verlassen
`INVALID`/`E012-introducer` und messen; der Neu-Pin ist der erste Schritt der
Reihenfolge und steht allein im Diff. `known_introducer_invalid` und der
Zustand `INVALID` bleiben im Harness (sie erkennen einen Rückfall), der
Kommentar in `expected/compare-matrix.env` und im README zieht nach. Auch der
Roundtrip-Harness weist MySQL heute als „ungültig" aus und misst danach
wieder. Sabotage: Introducer nicht entfernt → Integrationsfall rot **und**
Matrix zurück auf `INVALID`.

### P7 — SpatiaLite: `NOT NULL` nativ statt ganzer Tabelle (A6, entschieden)

**Befund:** A6 und Abschnitt D (Einordnung);
[`../open/spatial-profile-e052-ganze-tabelle.md`](../open/spatial-profile-e052-ganze-tabelle.md)
mit der Messung an SpatiaLite 5.1.0 (Tooling-Image):
`AddGeometryColumn('t','geom',4326,'POINT','XY',1)` legt `"geom" POINT NOT NULL
DEFAULT ''` an, eine Zeile ohne Geometrie wird abgewiesen.
**Ort:** `:adapters:driven:driver-sqlite`, an **drei** Stellen:

1. **Generate** — `SqliteTableDdlSupport`: `required` fällt aus
   `hasSpatialMetadataConflict`, `generateSpatiaLiteColumns` hängt das sechste
   Argument an. **Nur** bei `required: true`; eine nullable Spalte behält den
   Aufruf mit fünf Argumenten. So bleiben alle DDL-Goldens (`spatial.sqlite.sql`
   trägt nur nullable Spalten) unverändert, und die Fixture wird nicht
   angefasst.
2. **Migrate** — `SqliteSpatialDiffOps`: `geometryColumnMetadataBlock` verliert
   den `required`-Zweig, `addGeometryColumnSql` bekommt dasselbe Argument, und
   zwar für `CreateTable`. Für `AddColumn` auf eine **bestehende** Tabelle ist
   zuerst zu **messen**, was `AddGeometryColumn(…, 1)` mit vorhandenen Zeilen
   tut: legt SpatiaLite die Spalte an und füllt die Bestandszeilen mit `''`,
   bleibt `AddColumn` mit `required` blockiert (mit dem Grund „Bestandszeilen
   hätten keine gültige Geometrie") — dieselbe Regel, die SQLite für jede
   `NOT NULL`-Spalte ohne Default hat. Lehnt SpatiaLite ab, gilt das ebenso.
   Nur wenn es eine leere Tabelle von einer gefüllten unterscheidet und die
   gefüllte ablehnt, darf `AddColumn` rendern — der Fehler käme dann vom
   Server, benannt.
3. **Reverse** — `SqliteSchemaReader`: für eine in `geometry_columns`
   registrierte Spalte ist der Default `''` SpatiaLites eigener
   Füllwert, kein Anwender-Default; er kommt nicht als `default: ""` ins
   Modell. Zuerst messen, was `PRAGMA table_info` dort als `dflt_value`
   liefert (erwartet `''`). Ohne diesen Schritt blockiert der zweite Generate
   genau die Tabelle, die der erste angelegt hat.

`E052` bleibt für Primärschlüssel, `unique`, `default`, Fremdschlüssel und
eine tabellenweite Einschränkung auf der Geometriespalte. Die Regel „keine
partielle DDL" bleibt. Kein ADR: ADR 0016 hat den Generate-Pfad aufgeschoben,
nicht festgelegt; sein Kern bleibt unberührt (`make doc-immutable`).

**Spec-Nachzug** (korrigiert gegenüber dem Nachtrag in Abschnitt D, s. A6):
`spec/ddl-generation-rules.md` 16.5 bekommt das sechste Argument in der
Signatur-Beschreibung und **erstmals** die Liste der Auslöser, die weiter
`E052` ergeben. Die drei generischen Stellen (`spec/cli-spec.md` beim
`E052`-Ausgabeverhalten, `spec/neutral-model-spec.md` in der Tabelle der
blockierenden Codes, `spec/ddl-generation-rules.md` bei der `E052`-Regel)
bleiben wörtlich richtig und werden nur geprüft. Wo `schema migrate` eine
Geometriespalte gegen SpatiaLite beschreibt, zieht der Satz mit, falls die
Messung aus Punkt 2 eine eigene Regel für `AddColumn` ergibt.

**Anwendersicht:** Abschnitt 3.16 des Anwenderhandbuchs sagt heute nichts zu
Einschränkungen an SpatiaLite-Geometriespalten; er bekommt einen Hinweis
(`required` geht, PK/`unique`/`default`/Fremdschlüssel blockieren mit `E052`).
CHANGELOG `[Unreleased]`: eine Tabelle, die bisher mit `E052` fehlte, entsteht.

**Tests, die kippen:** `SqliteDdlGeneratorSpatialTest` pinnt `required` heute
**zweimal** als Blockade („spatialite metadata blocking adds SkippedObject",
„spatialite blocks table when geometry column has required metadata"). Beide
wechseln auf einen Auslöser, der bleibt (`unique`, `default`), und ein neuer
Fall pinnt `required` → sechstes Argument `1`.

**DoD:**
1. `schema generate --target sqlite --spatial-profile spatialite` mit einer
   `required`-Geometriespalte erzeugt die Tabelle und `AddGeometryColumn(…, 1)`,
   ohne `E052`; die übrigen Auslöser blockieren weiter.
2. Generate- und Migrate-Pfad sagen für `CreateTable` dasselbe; `AddColumn`
   folgt der Messung aus Punkt 2.
3. Live in `:test:integration-sqlite` (Tooling-Image mit SpatiaLite; die
   Standalone-DDL braucht den Bootstrap aus ADR 0016 vorab): anlegen, eine
   Zeile ohne Geometrie wird abgewiesen, eine mit angenommen; der Reverse
   liefert `required: true` **ohne** `default`; ein zweiter Generate daraus ist
   identisch.
4. Das `[lite]`-Bein von `make sample-db-spatial-smoke` fährt den
   Konsumentenfall (MySQL-Quelle mit `NOT NULL`-Geometrie → SpatiaLite) ohne
   `E052` und ohne Skip.
5. Spec 16.5, Handbuch 3.16 und CHANGELOG sind nachgezogen.
6. Sabotage je Stelle: sechstes Argument weg → Generate-Test rot; `required`
   zurück in die Konfliktprüfung (je Pfad) → rot; Default-Ausnahme im Reverse
   weg → Round-Trip-Fall rot.

### P8 — PostgreSQL: `json` wird laut (B3, entschieden)

**Befund:** B3 und Abschnitt D;
[`../open/json-jsonb-zweite-json-art.md`](../open/json-jsonb-zweite-json-art.md)
(Eigner: „gleichsetzen, aber laut").
**Ort:** `:adapters:driven:driver-postgresql`, `PostgresTypeMapping` — der
gemeinsame Zweig `"json", "jsonb"` in `mapSpecialTypes` teilt sich: `jsonb`
bleibt still, `json` bekommt die Note `R402`. Ob `json[]` (Element `json` in
`mapArrayElementType`) dieselbe Note trägt, entscheidet das Paket; es ist
derselbe Verlust.

**Die Note** nennt, was verloren geht, wenn die Spalte als `jsonb` neu
entsteht: Schlüsselreihenfolge, doppelte Schlüssel, Leerraum. Severity
`WARNING`, nicht `INFO`: beim Übertragen ändern sich die **Daten** (ein
doppelter Schlüssel fällt weg), nicht nur die Spaltendeklaration.

**Spec-Nachzug:** `spec/type-mapping.md` im PostgreSQL-Abschnitt (heute nur
„`jsonb` — gemappt als `Json`") bekommt die `json`-Zeile samt Begründung des
Rückwegs; `spec/neutral-model-spec.md` (Zeile `json` der Soll-Tabelle) bekommt
den Satz, dass die Tabelle die Renderrichtung beschreibt und der Rückweg in
`type-mapping.md` steht — beide sind Technik-Specs und dürfen sich
gegenseitig verweisen. **Anwendersicht:** Handbuch 3.3 („Eine bestehende
Datenbank übernehmen"), Hinweise.

**DoD:** Ein PG-Reverse einer `json`-Spalte trägt `R402` mit dem
Spaltennamen, eine `jsonb`-Spalte keine Note; Unit-Test und ein Fall in
`:test:integration-postgresql`; Spec und Handbuch nachgezogen; CHANGELOG.
Abnahme in der Matrix: `R402` steht in `REPORT_CODES_POSTGRESQL`. Sabotage:
Note zurückgenommen → Unit-Test, Integrationsfall und Matrix rot.

### P9 — Stille Typ-Fallbacks im Reverse werden benannt (D2, N1)

**Befund:** D2 (`numeric` ohne Präzision → `float`) und N1 (unbekanntes
Array-Element → `text`), Abschnitt D.
**Ort:** `:adapters:driven:driver-postgresql` (`PostgresTypeMapping`:
`mapNumericTypes` und `mapArrayElementType`) und
`:adapters:driven:driver-sqlite` (`SqliteTypeMapping.mapNumericType`, derselbe
Zweig).

**Was P9 baut und was nicht.** Das Mapping bleibt; der Verlust wird benannt:
`R404` (PostgreSQL) bzw. `R221` (SQLite) für `numeric`/`NUMERIC` ohne
Präzision, `WARNING` — eine exakte Zahl wird zur Gleitkommazahl, auch auf dem
Rückweg in denselben Dialekt. Für N1 gilt die Spec schon: der Fallback erzeugt
**immer** `R301`; das Array-Element-`else` bekommt ihn. Ob `numeric` ohne
Präzision verlustfrei werden soll, ist eine Modellfrage und bleibt offen
(Eigner-Frage 2, „Offen") — P9 ist in jedem Ausgang nützlich: bis eine
Erweiterung gebaut ist, ist der Verlust laut, und eine Variante nach dem
Oracle-Muster (`decimal(38,10)`) bliebe ebenfalls verlustbehaftet und
bräuchte die Note mit anderem Text.

**Spec-Nachzug:** `spec/type-mapping.md` — PostgreSQL-Abschnitt (neue Zeile
`numeric` ohne Präzision), SQLite 5.2 (die Zeile „`NUMERIC` ohne Precision —
`Float()` — Akzeptabel" nennt den Code), und der Abschnitt „Reverse-Mapping
else-Fallback" gilt ab P9 auch für Array-Elemente wörtlich.
**Anwendersicht:** Handbuch 3.3, Hinweise.

**DoD:** Beide Reverses melden `numeric` ohne Präzision mit ihrem Code; ein
PostgreSQL-Array unbekannten Elementtyps meldet `R301`; je Fall ein Test; Spec
und Handbuch nachgezogen; CHANGELOG. Abnahme in der Matrix:
`REPORT_CODES_POSTGRESQL` und `REPORT_CODES_SQLITE` tragen die Codes, der
bekannte Befund N1 verschwindet aus der Liste (P0). Sabotage je Note → rot.

### P10 — `ALWAYS` ohne Entsprechung wird gemeldet (D4)

**Befund:** D4 und seine Einordnung (derselbe Mechanismus wie P5; SQLite im
Code geprüft).
**Ort:** `:adapters:driven:driver-mysql` — `MysqlColumnConstraintHelper`
(`columnGeneratedIdentity`) und `MysqlDiffSqlBuilders.columnLine` über den
Notizkanal aus P5; `:adapters:driven:driver-sqlite` —
`SqliteColumnConstraintHelper.generateRowidIdentityColumn` und der
entsprechende Zweig im SQLite-Migrate-Pfad.

**Zuerst messen (SQLite):** dass `INTEGER PRIMARY KEY AUTOINCREMENT` einen
ausdrücklich eingefügten Wert annimmt (also `ALWAYS` nicht durchsetzt) und
was der Reverse zurückliest. Bestätigt die Messung den Code-Befund, bekommt
SQLite dieselbe Note; widerlegt sie ihn, wird SQLite hier gestrichen.

**Die Note** `W163`, ein Code für beide Dialekte (Muster `W132`): die
Identity-Spalte ist als Autowert gerendert, der ausdrücklich gesetzte Werte
annimmt; der Modus `always` ist nicht durchgesetzt, und ein Reverse liest
`by_default`. Nur bei `mode: always` — `by_default` entspricht dem, was beide
Dialekte tun.

**Spec-Nachzug:** `spec/ddl-generation-rules.md` 3.4 (MySQL) und 3.5 (SQLite),
„Besonderheiten"; die vier Registrierungsorte wie bei P5 (`spec/cli-spec.md`,
`spec/ledger.md` Einzel- und Bereichszeile, das aktuelle
`ledger/warn-code-ledger-*.yaml`). Die Tabelle der Migrate-Änderungen
(`spec/ddl-generation-rules.md`, Zeile „Identity-Modus": „MySQL kennt keinen
Modus") bleibt, wie sie ist; ob der Migrate-Pfad bei einem **Wechsel** des
Modus etwas meldet, prüft das Paket mit.
**Anwendersicht:** Handbuch 3.12 („Sequenzen/Autowerte korrekt mitnehmen");
der Compare-Abschnitt nennt den Modus schon als Fähigkeitsunterschied und
bekommt den Verweis auf den Code.
**Nachbar:** das [Toleranzprofil](compare-toleranzprofil.md) führt den Code als
Beleg für Kandidat K2 — dort nur ein Verweis, keine Arbeit hier.

**DoD:** MySQL (und nach Messung SQLite) melden `W163` auf Generate **und**
Migrate, nur bei `always`; Tests je Pfad; Spec, Ledger, Handbuch, CHANGELOG
nachgezogen. Abnahme in der Matrix: `W163` in `GEN_CODES_POSTGRESQL_MYSQL`
(und `_SQLITE`). Sabotage: Note weg → Tests und Matrix rot; Note bei
`by_default` → Test rot.

### P11 — SQLite-Reverse: Constraint-Namen aus der Quelle, schemaweit eindeutig (D5)

**Befund:** D5 und seine Einordnung.
**Ort:** `:adapters:driven:driver-sqlite` — `SqliteMetadataQueries.listForeignKeys`
(`fk_<id>`), `SqliteSchemaReader` (`multiColumnUniqueConstraints`,
`syntheticUniqueName`) und ein Scanner für Fremdschlüssel-Klauseln im
`CREATE TABLE`-Text nach dem Muster von `SqliteUniqueConstraintScanner`.

**Was P11 baut.**
1. **Der Name aus der Quelle.** Eine `CONSTRAINT <name> FOREIGN KEY …`-Klausel
   liefert ihren Namen; zugeordnet über Spaltenliste und Zieltabelle, wie der
   UNIQUE-Scanner es tut (verbrauchend, damit Doppelungen deterministisch
   bleiben).
2. **Ein erfundener Name ist schemaweit eindeutig** — für unbenannte
   Fremdschlüssel (Spaltenform `REFERENCES …` und unbenannte Tabellenklausel)
   **und** für unbenannte UNIQUE-Klauseln. Die Form legt das Paket fest und
   schreibt sie in die Spec; naheliegend ist der Tabellenname im Namen
   (Vorbild: `idx_<tabelle>_…` des SQLite-Generators), gekürzt auf die
   kleinste Bezeichnergrenze der Ziele und kollisionsfrei gegen echte Namen.

**Zuerst messen:** (a) dass der Reverse einer von d-migrate erzeugten
SQLite-Datenbank die Namen im Text findet; (b) wie der zielbewusste Vergleich
und der Post-Compare von `schema migrate` gegen SQLite Fremdschlüsselnamen
behandeln — `SqliteCapabilities` setzt `namesSingleColumnConstraints = false`,
und ein bisher erfundener Name darf nach P11 keinen neuen Plan erzeugen
(Konvergenz: `make sample-db-types-smoke`, Probe „Plan-Konvergenz"). Ändert
sich dabei eine Fähigkeit in `:hexagon:ports-common`, ist das eine geteilte
Signatur — einmal ohne `MODULES` bauen.

**Verträglichkeit:** ein Reverse derselben Datenbank trägt danach andere Namen
als vorher (`fk_0` → `fk_cm_order_customer`). Ein Vergleich gegen eine ältere
Reverse-Datei meldet das; CHANGELOG `[Unreleased]` unter „Changed".
**Spec-Nachzug:** `spec/type-mapping.md` im SQLite-Abschnitt (woher der Name
kommt, wie ein fehlender gebildet wird).

**DoD:** Benannte Fremdschlüssel kommen mit ihrem Namen zurück; kein
erfundener Name kommt im Schema zweimal vor; Tests für beide Fälle und für die
Kollision mit einem echten Namen; Konvergenz gemessen; Spec und CHANGELOG
nachgezogen. Abnahme in der Matrix: SQLite → SQL Server verlässt
`APPLY-FAIL`/`Msg 2714` und misst (bewusster Neu-Pin); SQLite → MySQL bleibt
`ERROR 1170` (andere Ursache, s.
[`pk-constraint-prefix-length.md`](pk-constraint-prefix-length.md)). Sabotage:
wieder `fk_<id>` → Unit-Test rot, Matrixzelle zurück auf `Msg 2714`.

### P12 — SQL Server: der Berechnungsausdruck kommt ohne T-SQL-Quoting (D6, Reader-Hälfte)

**Befund:** D6 und seine Einordnung.
**Ort:** `:adapters:driven:driver-mssql` — der Computed-Zweig in
`MssqlSchemaReader` führt `computedDefinition` durch dieselbe Normalisierung
wie ein CHECK (`MssqlTypeMapping.normalizeCheckExpression`: `N'…'` weg,
`[name]` → `name` bzw. `"Name"`). Der Name der Funktion passt dann nicht mehr
ganz; ob sie umbenannt wird, entscheidet das Paket.

**Nicht mitnormalisieren:** `MssqlHashPartitionRecognition` liest denselben
`computedDefinition`-Text für die Hash-Partitionierung und erkennt dort die
Serverform. Die Normalisierung gehört an die Stelle, an der der Ausdruck ins
Modell geht, nicht an die Abfrage.

**Zuerst messen:** (a) ob ein Vergleich eines **älteren** MSSQL-Reverse (mit
Klammern) gegen einen neuen einen Fund ergibt — die Dialekt-Faltung von
`schema compare` gilt für CHECK- und Sichten-Text, für den
Berechnungsausdruck gilt die eigene Regel (`W137`; ADR 0056 nimmt ihn
ausdrücklich aus); (b) dass der Post-Compare von `schema migrate` gegen SQL
Server nicht neu plant (beide Seiten laufen durch denselben Reader).

**Spec-Nachzug:** `spec/type-mapping.md` 6.2 (der Absatz über
CHECK-Ausdrücke gilt für Berechnungsausdrücke mit) und 6.3 („Der Ausdruck ist
die Serverform" — bis auf diese Normalisierung);
`spec/ddl-generation-rules.md` 8.3 nennt den Berechnungsausdruck unter den
Feldern mit rohem Ausdruckstext (der Code prüft ihn schon, D6).
CHANGELOG `[Unreleased]`.

**DoD:** Ein MSSQL-Reverse liefert `quantity*unit_price` statt
`[quantity]*[unit_price]`; die Hash-Partitionserkennung bleibt unverändert
grün; Messungen (a) und (b) festgehalten; Spec und CHANGELOG nachgezogen.
Abnahme in der Matrix: SQL Server → PostgreSQL und → MySQL verlassen
`APPLY-FAIL` und messen (bewusster Neu-Pin). Sabotage: Normalisierung weg →
Unit-Test rot, beide Zellen zurück auf `APPLY-FAIL`.

### P13 — Der Typ einer berechneten SQL-Server-Spalte: messen, dann nach Regel (D1)

**Befund:** D1 und seine Einordnung.
**Ort:** `:adapters:driven:driver-mssql` (Generator für berechnete Spalten,
`MssqlSchemaReader`); Messung in `:test:integration-mssql`.
**Vorbedingung:** P12 (die Rückführung arbeitet auf normalisiertem Text) und
die bestätigte Entscheidungsregel (Eigner-Frage 1, „Offen").

**Messung zuerst** (SQL Server 2025, derselbe Stand wie die Matrix):
1. Was `sys.computed_columns.definition` und der Spaltentyp für
   `AS (CAST(<ausdruck> AS decimal(14,2))) PERSISTED` liefern.
2. Ob der Reader die **äußerste** Hülle `CONVERT(<typ>, <ausdruck>)` genau dann
   eindeutig zurückführen kann, wenn `<typ>` der Spaltentyp ist — so dass
   Hin- und Rückweg Typ und Ausdruck unverändert liefern.
3. Was der Vergleich daraus macht (`W137`, Entscheidbarkeit) und was der
   Roundtrip-Harness für SQL Server meldet (seine Fixture trägt den Fall
   schon: `line_total` als `decimal(14,2)` über `quantity * unit_price`; in
   der Matrix ist D1 unsichtbar, s. P0).

**Regel (Empfehlung, vom Eigner zu bestätigen):**
- **Variante a** — der Generator hüllt den Ausdruck in `CAST(… AS <typ>)`, der
  Reader führt die äußerste `CONVERT`-Hülle zurück —, wenn Messung 1 und 2
  deterministisch sind.
- **Variante b** — sonst bleibt der Generator bei „ohne Typ" und meldet den
  Wegfall mit `W164`.

Beide Varianten ändern `spec/ddl-generation-rules.md` 3.2a (a: die Regel „ohne
Typ" wird „mit `CAST`"; b: die Regel bekommt den Code) und
`spec/type-mapping.md` 6.3.

**DoD:** Die drei Messungen stehen in diesem Plan; die Variante ist nach der
Regel gewählt und gebaut, mit Test und Sabotage; bei Variante a liefert ein
MSSQL-Reverse `decimal(14,2)` und den Ausdruck ohne Hülle, bei Variante b
trägt jede berechnete Spalte mit deklariertem Typ `W164`; Spec, gegebenenfalls
Ledger und CHANGELOG nachgezogen. Abnahme: im Roundtrip verschwindet der
Typfund `decimal(23,2)` gegen `decimal(14,2)` für SQL Server (a; die
README-Tabelle des Harness zieht nach); in der Matrix bleiben SQL Server →
PostgreSQL/MySQL messend — bei Variante a der Beleg, dass die Rückführung
greift, denn ohne sie stünde dort `CONVERT(…)` und die Zelle wäre wieder
`APPLY-FAIL`; bei Variante b steht `W164` in den `GEN_CODES_*` jeder Zelle mit
Ziel SQL Server, deren Quelle die Berechnung behält.

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
   ohne `E012`) und der CHECK meldet im Vergleich nicht mehr (C1/P6); die
   MySQL-Zeile der Matrix misst.
9. Die Compare-Matrix fährt native Seeds und den Silent-Loss-Check; jeder
   stille Verlust ist entweder benannt oder als bekannter Befund mit seinem
   Paket gelistet, und `--update-expectations` pinnt keinen (P0).
10. Eine `required`-Geometriespalte entsteht auf SpatiaLite nativ als
    `NOT NULL`, ohne `E052`; Generate und Migrate sagen dasselbe, und der
    Reverse liefert die Spalte ohne erfundenen Default zurück (A6/P7).
    Abnahme unter `-PintegrationTests` in `:test:integration-sqlite`.
11. Eine PostgreSQL-`json`-Spalte ist im Reverse-Report benannt, eine
    `jsonb`-Spalte nicht (B3/P8).
12. `numeric` ohne Präzision (PostgreSQL, SQLite) und ein PostgreSQL-Array
    unbekannten Elementtyps sind im Reverse-Report benannt (D2, N1/P9).
13. Eine Identity-Spalte mit `always` ist auf MySQL — und nach Messung auf
    SQLite — beim Generieren und Migrieren benannt (D4/P10).
14. Der SQLite-Reverse liefert Fremdschlüsselnamen aus der Quelle, und kein
    erfundener Constraint-Name kommt im Schema zweimal vor; SQLite → SQL
    Server misst in der Matrix (D5/P11).
15. Der SQL-Server-Reverse liefert Berechnungsausdrücke ohne T-SQL-Quoting;
    SQL Server → PostgreSQL und → MySQL messen in der Matrix (D6/P12).
16. Für D1 stehen die Messungen im Plan, und die gewählte Variante ist gebaut
    (P13).
17. Jede neue Kennung (`R370`, `R402`–`R404`, `R221`, `W162`, `W163`, bei
    Bedarf `R405`/`W164`) steht an ihren Registrierungsorten und dort, wo ein
    Anwender sie liest (`docs/user/`); CHANGELOG `[Unreleased]` nennt jede
    sichtbare Änderung.

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
   Testdeklarationen, davon die A1-relevante Pinnung `:340-389` (beim
   Aktivierungsschnitt nachgezählt: acht, und die Pinnung steht dort).

   **Noch nicht gemessen** ist die Nulllinie der Module, die erst mit dem
   Aktivierungsschnitt dazukommen: `:test:integration-sqlite` (P7; eine der
   Klassen dort lädt SpatiaLite schon, `SqliteSpatialiteDataPathIntegrationTest`)
   und `:test:integration-mssql` (P13). Beide werden **vor** ihrem Paket
   gemessen, genauso wie oben: `executed`, nicht `SKIPPED`/`UP-TO-DATE`, keine
   Selbstüberspringung. `:test:integration-postgresql` braucht für P2b, P3 und
   P4 zum ersten Mal einen PostGIS-Container (`TestImages.POSTGIS` gibt es,
   benutzt wird er bisher nur in `:test:e2e-cli`); die Nulllinie des Moduls
   gilt für den neuen Container nicht mit.
3. Je Paket der Ort:

   | Paket | Modul | Abnahme |
   | --- | --- | --- |
   | P0 | `examples/mcp-e2e` | `make mcp-e2e-compare-matrix` (zweimal identisch), `make mcp-e2e-roundtrip`, `make mcp-e2e-smoke`; `bash -n`, shellcheck (Container) |
   | P1, P2a | `:adapters:driven:driver-oracle` | `:test:integration-oracle` |
   | P2b | `:adapters:driven:driver-postgresql` | `:test:integration-postgresql` (PostGIS) |
   | P3, P4 | `:adapters:driven:driver-postgresql` — Typ-Mapping **und** dessen Kanonisierer-Projektion (in `hexagon/ports-common` liegt nur das Interface `NeutralTypeCanonicalizer.kt`); P4 zusätzlich der Ort des SRID-Kriteriums (heute `driver-mssql`) | `:test:integration-postgresql` (PostGIS), Matrix |
   | P5, P6 | `:adapters:driven:driver-mysql` | `:test:integration-mysql`, Matrix |
   | P7 | `:adapters:driven:driver-sqlite` | `:test:integration-sqlite`, `make sample-db-spatial-smoke` |
   | P8 | `:adapters:driven:driver-postgresql` | `:test:integration-postgresql`, Matrix |
   | P9 | `:adapters:driven:driver-postgresql`, `:adapters:driven:driver-sqlite` | Matrix |
   | P10 | `:adapters:driven:driver-mysql`, `:adapters:driven:driver-sqlite` | Matrix |
   | P11 | `:adapters:driven:driver-sqlite` (bei einer Fähigkeitsänderung auch `:hexagon:ports-common` → einmal ohne `MODULES`) | Matrix, `make sample-db-types-smoke` |
   | P12 | `:adapters:driven:driver-mssql` | Matrix |
   | P13 | `:adapters:driven:driver-mssql` | `:test:integration-mssql`, `make mcp-e2e-roundtrip` (SQL Server), Matrix |

   Integrationsläufe über
   `make integration INTEGRATION_TASKS=":test:integration-<modul>:test"` — ohne
   `-PintegrationTests` meldet Gradle `BUILD SUCCESSFUL`, ohne gefahren zu
   haben. Zieht P4 das SRID-Kriterium aus `driver-mssql` heraus, ändert sich
   eine Treiber-Signatur, gegen die `:test:integration-mssql` kompiliert: dann
   einmal ohne `MODULES`. Sabotage je Paket, auch je Teilpaket von P2 und je
   Stelle von P7.
4. `make docs-check` — P1, P4–P13 fassen Spec-Bezuege an (P1 beide
   „exakt passend"-Stellen, P4 beide Mapping-Seiten, P5 und P10 je vier
   Registrierungsorte, P6 den MySQL-Abschnitt von `spec/type-mapping.md`,
   P7 den Profilabschnitt 16.5, P8 zwei Technik-Specs,
   P9/P11/P12/P13 `spec/type-mapping.md`, P12 und P13 zusätzlich
   `spec/ddl-generation-rules.md`); P2 nicht, P3 nur mit eigenem Code
   (`R405`, dann im PostgreSQL-Abschnitt von `spec/type-mapping.md`). P3–P10 fassen
   zusaetzlich `docs/user/` an — das Gate prueft es mit, aber nur
   auf Verweise, nicht auf Inhalt. `make doc-immutable RANGE=origin/main..HEAD`
   vor dem Push: kein Paket fasst einen ADR an, ADR 0016 insbesondere nicht.
5. **Die 5x5-Compare-Matrix wird die Abnahme der Reader-Pakete** — gebaut als
   **P0**; der Text hier ist der Auftrag, P0 der Schnitt.
   `examples/mcp-e2e/scripts/smoke-compare-matrix.sh` (aus dem
   Compare-Slice) faehrt jeden Dialekt einmal als Quelle gegen die Reverses
   der anderen, ueber MCP. Dieser Slice erweitert sie um
   - **native Typ-Seeds je Dialekt** (unter `examples/mcp-e2e/fixtures/` je
     Dialekt eine Datei `seeds/<dialekt>.sql`; der Lauf wendet sie schon an,
     wenn es sie gibt — heute gibt es keine) mit den Typen der Posten dieses
     Slices, und
   - einen **Silent-Loss-Check** je Reverse mit drei Klassen: ein
     Nicht-Text-Quelltyp kommt als Text an (D3: `varchar` ohne Laenge; B4:
     `interval`, dort mit `R301` benannt); ein `ref_type` ohne Eintrag in
     `custom_types` (A5: `geography` als Enum gelesen); eine Spalte fehlt im
     Reverse. Jeder Fall ist ein Fehlschlag des Laufs, sofern der Reverse ihn
     nicht mit einer Note benennt.

   Vorbild ist die Typ-Matrix des Konsumenten, deren Klassen dieser Slice in
   A5, B4 und D3 bereits fuehrt. Die Erweiterung ist die Abnahme der Pakete:
   ein Paket gilt erst als geliefert, wenn die Matrix seinen Fall faehrt und
   ohne den Fix rot wird.

   **Korrekturen beim Schnitt:** `varchar` ohne Länge gehört nicht in die
   erste Klasse, er ist Text-Familie (s. D3, Einordnung). Die erste Klasse
   gilt nur für den Reverse der Quelle; auf den Reverses der Ziele sind
   Degradierungen die Regel und werden über die Codes des Generate-Schritts
   geprüft (P0, Teil 3). Und „sofern der Reverse ihn nicht benennt" reicht
   nicht, solange Pakete offen sind: bekannte stille Befunde stehen bis zu
   ihrem Paket in einer Liste im Harness-Code (P0, Teil 2).
6. **Was nachgemessen ist und was nicht.** Die Posten A2, A3, A4, B1, B2 stammen
   aus der Konsumentenmessung; geprueft ist im Repo jeweils die **Vorbedingung**
   (der Filter fehlt an genau dieser Stelle; der Zweig fehlt; die Note nennt
   Grund und Ausweg nicht), **nicht** die Zahl oder der Objektname. Wer die Pakete
   schneidet, prueft die Meldung selbst nach — die Zahlen aus A3 sind dafuer kein
   Beleg. Beim Aktivierungsschnitt nur im Code geprüft, nicht gemessen: D4 für
   SQLite, D5 der Name im `CREATE TABLE`-Text und die `uq_N`-Kollision, N1;
   P10 und P11 messen das zuerst.
7. **Gates je Commit:** `make solid-suppression-gate` vor jedem Commit;
   `make docker-check MODULES=…` für die berührten Module; eine geteilte
   Signatur im Hexagon (P11, falls eine Fähigkeit wandert) einmal ohne
   `MODULES`. Die Anker `Datei:Zeile` in diesem Plan sind Stand des
   Aktivierungsschnitts (2026-09-17, stichprobenartig nachgemessen; wo sie
   verrutscht waren, stehen jetzt Symbolnamen) — wer einen Beleg nachfährt,
   sucht über den Symbolnamen.

## Offen (nicht Teil dieses Slices)

**Entschieden und seit dem Aktivierungsschnitt Pakete** (bis dahin standen sie
hier): die Vertragsfrage aus A6 (**P7**, Eigner: „`NOT NULL` nativ") und die
zweite JSON-Art aus B3 (**P8**, Eigner: „gleichsetzen, aber laut"). Ihre Orte
[`../open/spatial-profile-e052-ganze-tabelle.md`](../open/spatial-profile-e052-ganze-tabelle.md)
und [`../open/json-jsonb-zweite-json-art.md`](../open/json-jsonb-zweite-json-art.md)
verweisen auf die Pakete und schliessen mit der Graduation dieses Slices; ihre
Zeilen in [`../open/README.md`](../open/README.md) sind nachgezogen.

**Eigner-Fragen aus dem Schnitt** (je mit Empfehlung; die erste sperrt P13,
die zweite nichts):

1. **D1/P13 — die Entscheidungsregel.** Soll P13 nach der Messung selbst
   zwischen „CAST-Hülle mit Rückführung im Reader" (Variante a) und „ohne Typ,
   aber gemeldet" (Variante b) wählen, nach der Regel in P13? **Empfehlung:**
   ja, so bestätigen. Beide Varianten ändern eine Render-Regel in
   `spec/ddl-generation-rules.md` (3.2a); die Regel macht die Wahl an einer
   Messung fest statt an einer Vorliebe, und Variante a ist nur dann
   zulässig, wenn der Rückweg nachweislich exakt ist.
2. **D2 — `numeric` ohne Präzision verlustfrei?** P9 macht den Verlust laut;
   verlustfrei geht er nur über einen neutralen `decimal` ohne Präzision —
   eine Modellerweiterung mit einer Render-Entscheidung je Dialekt (MySQL
   `DECIMAL` ohne Angabe ist `(10,0)`, SQL Server `(18,0)`, Oracle
   unbeschränkt, SQLite `REAL`) und mit der Frage, ob Oracles `NUMBER` →
   `decimal(38,10)` dann nachzieht. **Empfehlung:** nicht in diesem Slice;
   ein eigener `open/`-Eintrag bei der Graduation (dann ist `open/README.md`
   wieder frei), mit dem Muster von
   [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) als Weg.
   Solange niemand einen
   Fidelity-Bedarf meldet, reicht die Meldung aus P9 — dieselbe Schwelle, die
   der Kandidaten-Tracker für PostgreSQL-Typen ansetzt.

**Nicht in diesem Slice, aber aus ihm:**

- **D6, zweite Hälfte — die Portabilitätsprüfung mit Herkunft.**
  `spec/ddl-generation-rules.md` (8.3) beurteilt rohen Ausdruckstext
  ausdrücklich nach dem Ziel, nicht nach der Herkunft, und meldet
  T-SQL-Klammern deshalb nicht. Die Reverse-Markierung trägt inzwischen den
  Dialekt; eine Prüfung, die sie auswertet, ist eine Regeländerung über alle
  fünf Generatoren. Nach P12 erreicht der Fall nur noch ältere Reverse-Dateien
  und handgeschriebenen T-SQL-Text (etwa `CONVERT(…)`, das kein Marker
  erkennt). **Ort:** ein eigener `open/`-Eintrag bei der Graduation (aus
  demselben Grund wie oben nicht jetzt).
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
  nicht, was hier auffiel. Die Codes, die dieser Slice neu vergibt (Tabelle
  „Codes, beim Schneiden reserviert"), vergrößern den R-Bereich um vier bis
  fünf Kennungen; auch sie stehen danach nur im Code, in `spec/type-mapping.md`
  und im Handbuch.

## Was der Slice bewusst nicht tut

Er fuehrt **keinen** `interval`-Neutraltyp ein, **keine** zweite JSON-Art,
**keinen** `decimal` ohne Präzision und entscheidet **nicht**, ob ein
fehlender SRID blockiert. Alle vier sind Modell-/Vertragsfragen (s. „Offen");
bei JSON und `numeric` macht der Slice den Verlust laut, statt das Modell zu
erweitern. Er **lockert den SRID-Abgleich nicht** (s. A1) — obwohl genau das
der naheliegende Fix waere. Er ändert **nicht**, dass SpatiaLite eine Tabelle
ganz blockiert (P7 ändert nur, wann), und er ändert **nicht**, wonach die
Portabilitätsprüfung roher Ausdrücke urteilt (D6, zweite Hälfte).

Und er behebt **nicht** „alles": von den **sechzehn** lebenden Posten sind
**fünf** Defekte mit Fix (A5, A6, C1, D5 und — mit P12 — die Reader-Hälfte
von D6), **einer** eine Meldung, die handelbar wird (A4), **sechs** Verluste,
die benannt werden (A1, B1, B3, D2, D4 und das unvermeidbare B2), **zwei**
Fremdobjekte, die ausgeschlossen werden (A2, A3), **einer** offen bis zur
Messung (D1) und **einer** vertragsgleich (D3). Dazu kommt der Nebenbefund N1
(benannt, in P9). B4 ist widerlegt und war nie Teil der Zählung; C1 ist am
2026-09-16 aus der Compare-Messung uebernommen und bringt dort seinen
Praezedenzfall mit.
