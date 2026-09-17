# Reader-Treue 3: Spatial-Treue (P4, P7, P2a, P2b)

> **Status:** Entwurf mit Scope (Schnitt 2026-09-17 aus dem ungeschnittenen
> Reader-Slice; Befunde aus Plan-Review und Architektur-Prüfung eingearbeitet,
> Anker gegen `90c6c234f` nachgemessen). Teil des Umbrellas
> [`reader-treue.md`](../in-progress/reader-treue.md); dort stehen Nenner, Belegart, Regeln
> der Abnahme, Doku-Pflichten und die Code-Tabelle.
> **Vorbedingung / Gate:** [Plan 1](../done/reader-treue-1-matrix-abnahme.md) ist geliefert und graduiert
> (2026-09-18; Matrix als Abnahme,
> PostGIS-Dienst im eigenen Schema). P4 braucht P3 aus Plan 2 (der Hinweis
> ohne `search_path`, den P4 auf `geography_columns` erweitert). Die
> Eigner-Entscheidungen F1 (P4 nur Rückweg) und A6 (P7) stehen; keine offene
> Frage sperrt diesen Plan.
> **Aktivierung:** Move nach `../in-progress/` beim ersten
> Implementierungs-Commit dieses Plans.
> **Abhängigkeit:** [Plan 1](../done/reader-treue-1-matrix-abnahme.md); für P4 zusätzlich P3 aus Plan 2. P7, P2a und P2b
> hängen an keinem anderen Paket und berühren die Matrix nicht.

## Befund

### A5, F1 — PostgreSQL `geography` wird als Enum gelesen (im Code bestätigt; Eigner: nur Rückweg)

`mapUserDefined` kennt nur `geometry` (`PostgresTypeMapping.kt:160`); jeder
andere benutzerdefinierte Typ wird `Enum(refType = udtName)` (`:174`). Aus
`geography(Point,4326)` wird `type: enum, ref_type: geography`, und das Schema
ist mit `E007` ungültig (`SchemaColumnValidationRules.kt:152`).

**SQL Server macht den Rückweg vor:** `geography` liest dort als
`Geometry(srid = 4326)` mit der Note `R345`
(`MssqlTypeMapping.kt:168`, Note in `geographyNote`), und vorwärts wählt der
SRID zwischen `geography` und `geometry` (`MssqlTypeMapper.spatialTypeSql`;
`spec/type-mapping.md` 6.4).

**Eigner-Entscheidung F1 (2026-09-17):** nur der Rückweg. `geography` liest als
`geometry` mit SRID, mit einer Note, dass PostgreSQL → PostgreSQL daraus
`geometry` wird. Vorwärts bleibt PostgreSQL bei `geometry`
(`spec/ddl-generation-rules.md` 16.2); das Golden `spatial.postgresql.sql`
bleibt unverändert (Zeile 9: `"location" geometry(Point, 4326)`). Ein
Modell-Attribut „geodätisch" ist
[`../open/geometrie-geodaetisch-modellattribut.md`](../open/geometrie-geodaetisch-modellattribut.md).
**Folge:** das SRID-Kriterium muss nicht aus `driver-mssql` heraus; der
ungeschnittene Plan hatte dafür eine gemeinsame Stelle gesucht.

**Subtyp und SRID** liest der PostgreSQL-Reverse heute nur aus
`geometry_columns` (`PostgresTableMetadataQueries.listGeometryColumns`,
`to_regclass`-Wächter ab Zeile 84). Für `geography` stehen sie in
`geography_columns`.

### M3 — der Datenpfad kennt `geography` nicht (im Code geprüft)

`PostgresDataReader.isGeometryTypeName` (`PostgresDataReader.kt:57`) und
`PostgresTableImportSession.isGeometryTypeName`
(`PostgresTableImportSession.kt:66`) erkennen nur `geometry`; die KDoc sagt es
(„`geography` bleibt vorerst außen vor: eigener Konstruktor"). Der Import
konstruiert Geometrie mit `ST_GeomFromWKB`, das `geometry` liefert. Ein
Transfer aus einer `geography`-Spalte liest die Werte also nicht als WKB, einer
in eine `geography`-Spalte schreibt nicht über den Geometrie-Pfad.

### L4 — Gegenproben für P4 (im Code geprüft)

- `geography` ohne Typmodifikator: was `geography_columns` dann als SRID
  führt, ist zu messen.
- ein geodätischer SRID ungleich 4326 (etwa 4258).
- ein Anwendertyp namens `geography` in einem anderen Schema:
  `mapUserDefined` prüft `udt_schema` nicht (die Signatur ab
  `PostgresTypeMapping.kt:153` trägt ihn gar nicht) und würde ihn für
  PostGIS halten.
- `geometry(Point,4326)` bleibt vorwärts `geometry` (F1).

### I6 — `spec/type-mapping.md` 3.1 ist veraltet (im Code geprüft)

Die Tabelle führt `tsvector` als „`Enum(refType="tsvector")` ❌"; seit
[ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) ist er
`fulltext`. „Aktuelles Verhalten: `mapUserDefined()` erkennt nur `geometry`"
ist eine Ist-Formulierung. P4 fasst diese Tabelle an.

### A6 — SpatiaLite verwirft die ganze Tabelle bei `NOT NULL` (gemeldet; SpatiaLite gemessen; Eigner: nativ)

`schema generate --target sqlite --spatial-profile spatialite` auf einer
MySQL-Quelle mit `NOT NULL`-Geometrie: `E052`, die ganze Tabelle fällt weg.
Gemessen an SpatiaLite 5.1.0 (Tooling-Image):
`AddGeometryColumn('t','geom',4326,'POINT','XY',1)` legt
`"geom" POINT NOT NULL DEFAULT ''` an; eine Zeile ohne Geometrie wird
abgewiesen. **Eigner-Entscheidung (2026-09-16):** `NOT NULL` nativ über das
sechste Argument; `E052` bleibt für Primärschlüssel, `unique`, `default`,
Fremdschlüssel und eine tabellenweite Einschränkung.

**Die Regel steht zweimal im Code:** im Generate-Pfad
(`SqliteTableDdlSupport.checkSpatialMetadataBlocks` mit
`hasSpatialMetadataConflict`, `SqliteTableDdlSupport.kt:336`) und im
Migrate-Pfad (`SqliteSpatialDiffOps.spatialMetadataBlock` und
`geometryColumnMetadataBlock`, als `SPATIAL_METADATA_UNSUPPORTED` mit
`MANUAL_ACTION_REQUIRED`).

**N4 — Korrektur an der Architektur-Prüfung.** Sie hielt fest, der
Generate-Pfad prüfe keine tabellenweite Einschränkung. Er tut es:
`checkSpatialMetadataBlocks` prüft nach der Spaltenregel jede Einschränkung
der Tabelle auf eine Geometriespalte (`SqliteTableDdlSupport.kt:78`), wie der
Migrate-Pfad (`SqliteSpatialDiffOps.kt:50`). Beide Pfade haben dieselben
Auslöser; sie unterscheiden sich im Wortlaut (Generate bündelt
„required/unique/default/references/PK", Migrate nennt den einzelnen Grund).

**Der Rückweg bricht sonst:** SpatiaLite legt die Spalte mit `DEFAULT ''` an,
und der SQLite-Reverse liest jeden Default wörtlich
(`SqliteTypeMapping.parseDefault`) — ein Default ist selbst ein
`E052`-Auslöser. Ohne Gegenmaßnahme blockiert der zweite Generate genau die
Tabelle, die der erste angelegt hat.

### F3 — die Begründung „ADR 0016 hat den Generate-Pfad aufgeschoben" (Architektur-Prüfung)

Sie stimmt nicht. [ADR 0016](../../adr/0016-spatialite-metadata-bootstrap.md)
hat im Generate-Pfad nur den **Bootstrap** aufgeschoben. **Richtig ist:** Die
Auslöser von `E052` sind nicht ADR-gebunden; der Gegenstand von ADR 0016 (Ort
und Form des Bootstrap) bleibt unberührt. P7 braucht deshalb keinen ADR.

### M11 — der Rebuild kennt keine Geometrie (im Code geprüft)

`AlterColumnNullability` löst einen Rebuild aus
(`SqliteRebuildPlanner.kt:528`). Weder `SqliteRebuildRenderer` noch
`SqliteDiffDdlGenerator` kennen Geometrie; der Rebuild rendert die Spalte über
`columnLine` inline als `GEOMETRY` (`SqliteRebuildRenderer.kt:608`), ohne
`AddGeometryColumn`. Eine Änderung nullable → required an einer
Geometriespalte ist heute also weder geblockt noch richtig gerendert. Für den
Migrate-Block (`SPATIAL_METADATA_UNSUPPORTED`, „is NOT NULL") gibt es heute
**keinen** Test — die Sabotage „je Pfad" braucht neue Tests.

### P7-Spec-Befunde (Architektur-Prüfung)

- Die Reverse-Regel (SpatiaLites `DEFAULT ''` entfällt) gehört nach
  `spec/type-mapping.md` Abschnitt 5 (SQLite).
- Die Auslöserliste in `spec/ddl-generation-rules.md` 16.5 muss beiden Pfaden
  entsprechen; heute nennt 16.5 gar keine Auslöser.
- `spec/cli-spec.md`, `--spatial-profile` bei `schema migrate` (Zeile 1044),
  beschreibt nur das Profil `none`.
- `spec/neutral-model-spec.md` sagt „`E052`–`E056` … werden nur von
  `schema generate` gemeldet" (Zeile 1493) und widerspricht damit
  `spec/cli-spec.md` Zeile 1044, wo `schema migrate` mit `E052` blockt.
  Dieselbe Aussage steht in `spec/ddl-generation-rules.md` 16.8 (Zeile 2641).
- Der Bootstrap aus ADR 0016 steht nicht in `spec/` (ältere Lücke).

### A2, M7 — Oracles interne Spatial-Sequenz im Modell (gemeldet; im Code geprüft)

Mit Spatial erscheint `sequences: MDRS_11E5F$` im Artefakt und wird als
`CREATE SEQUENCE` in die Ziel-DDL geschrieben (gemeldet für SQL Server und
PostgreSQL). `listSequences` schließt nur Identity-Sequenzen aus
(`OracleMetadataQueries.kt:440`); der Tabellenpfad hat vier
Systemobjekt-Filter, darunter `o.secondary = 'Y'`
(`OracleMetadataQueries.kt:146`). **Review M7:** ein Namensfilter
`MDRS_*`/`SDO_*` versteckte auch eine Anwendersequenz wie `SDO_ORDER_SEQ`,
und `_` ist in `LIKE` ein Platzhalter. Das Kriterium muss aus dem Katalog
kommen; welches, ist zu messen.

### A3, M7 — PostGIS-Routinen und -Sichten fluten das Reverse (gemeldet; im Code geprüft)

PostGIS in `public` bringt rund 1000 Funktionen (gemeldet: 320.271 Byte gegen
816 Byte). `PostgresProgrammabilityMetadataQueries` filtert
`listFunctions` nur mit `routine_name NOT LIKE 'pg_%'` (Zeile 127),
`listAggregates` und `listProcedures` gar nicht. Die Nachbarpfade filtern
extension-eigene Objekte über `pg_depend.deptype = 'e'`: Tabellen
(`PostgresTableMetadataQueries.kt:20`) und Typen
(`PostgresTypeMetadataQueries.kt:15`, `:40`, `:59`). **Review M7:** auch
`listViews` (`PostgresProgrammabilityMetadataQueries.kt:8`) filtert nicht, und
PostGIS legt die Sichten `geometry_columns` und `geography_columns` an. Die
Abfragen mit `deptype IN ('n', 'a')` schlagen Abhängigkeiten nach und sind
etwas anderes.

**Architektur-Prüfung:** die drei Vorbilder filtern **stumm**. Wählt P2
einen Hinweis, fehlt dafür ein reservierter Code, und „P2 fasst keine Spec
an" stimmt nicht mehr. Auch ohne Hinweis ist die Spec zu prüfen: Filterregeln
des PostgreSQL-Reverse stehen heute nur für Sequenzen
(`spec/cli-spec.md:340`, `spec/neutral-model-spec.md:967`).

## Ziel

1. Eine PostgreSQL-`geography`-Spalte liest als `geometry` mit ihrem SRID,
   benannt als Verlust für PostgreSQL → PostgreSQL; ihre Werte laufen durch
   den Datenpfad.
2. Eine `required`-Geometriespalte entsteht auf SpatiaLite nativ; Generate,
   Migrate und Reverse sagen dasselbe.
3. Oracles Spatial-Sequenzen und PostGIS-Routinen und -Sichten kommen nicht
   ins Modell, und kein Anwenderobjekt verschwindet mit ihnen.

Begründet gegen [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003)
(Geometrie-Spalten für PostGIS und SpatiaLite abbildbar) und
[`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004) (Extraktion
datenbankeigener Objekte; PostGIS als Spezial-Feature, Abschnitt 8.4).

## Abgrenzung

- **P4 baut keinen Hinweg** (F1): PostgreSQL rendert weiter `geometry`, auch
  bei SRID 4326; kein Attribut „geodätisch".
- **P7 behält „keine partielle DDL"**; es ändert nur, wann eine Tabelle
  blockiert. Kein ADR (F3).
- **P7 schreibt den Bootstrap nicht in die Spec**, außer 16.5 wird ohnehin neu
  gefasst und er passt ohne Umbau dazu; sonst bleibt die Lücke unter „Offen".
- **P2 filtert nur, was nachweislich der Extension bzw. dem Spatial-System
  gehört.**

## Arbeitspakete

**Reihenfolge:** P4 (nach P3); P7; P2b; P2a. P7, P2a und P2b sind frei
reihbar. P2a läuft im selben `:test:integration-oracle`-Lauf wie P1 aus
Plan 2, wenn beide zeitlich zusammenfallen.

### P4 — PostgreSQL `geography` liest als Geometrie (A5, F1, M3, L4, I6)

**Modul:** `:adapters:driven:driver-postgresql` — `PostgresTypeMapping`
(`mapUserDefined`, samt `udt_schema`), `PostgresTableMetadataQueries`
(`geography_columns`), der Kanonisierer (Projektion),
`PostgresDataReader` und `PostgresTableImportSession` (Datenpfad).

**Was P4 baut.**
1. **Rückweg.** Ein Typ `geography` aus dem PostGIS-Schema liest als
   `Geometry` mit Subtyp und SRID aus `geography_columns` (derselbe
   `to_regclass`-Wächter). Dazu `R403`: die Spalte ist PostGIS-`geography`;
   ein PostgreSQL-Ziel rendert sie als `geometry` mit demselben SRID, und
   Abstände und Flächen rechnen dort planar statt auf dem Ellipsoid.
   **Severity `WARNING`:** die Bedeutung von Abfragen auf der Spalte ändert
   sich schon PostgreSQL → PostgreSQL; `INFO` wäre ohne `--verbose` nicht
   sichtbar. SQL Server als Ziel wählt nach dem SRID ohnehin `geography`.
2. **Der Hinweis aus P3 gilt auch für `geography_columns`:** ist die Sicht
   nicht erreichbar, trägt die Spalte `R405` wie eine `geometry`-Spalte.
3. **Datenpfad (M3).** `geography` gilt im Lesen als Geometrie (`ST_AsBinary`
   funktioniert für beide); im Schreiben in eine bestehende
   `geography`-Spalte wird der Wert als `geography` konstruiert. Wie, misst das
   Paket (etwa `ST_GeogFromWKB` oder ein Cast).
4. **Kanonisierer:** eine `Geometry` bleibt, was sie ist (heute schon); zu
   prüfen ist nur, dass `geography` keinen Umweg über `Enum` mehr nimmt.

**Zuerst messen:** was `geography_columns` für `geography` ohne
Typmodifikator und für einen SRID ungleich 4326 liefert; welcher
Schreibkonstruktor eine WKB-Eingabe in eine `geography`-Spalte trägt.

**DoD:**
1. `geography(Point,4326)` liest als `geometry` mit `srid: 4326` und
   `geometry_type: point`, kein `enum`, kein Custom-Type, mit `R403`.
2. **Gegenproben (L4):** `geography` ohne Typmodifikator liest mit dem
   gemessenen SRID; `geography(Point,4258)` liest `4258`; ein Anwendertyp
   `geography` in einem anderen Schema bleibt `enum` mit `ref_type` (und sein
   `custom_types`-Eintrag); `geometry(Point,4326)` wird vorwärts weiter
   `geometry(Point, 4326)`, und das Golden `spatial.postgresql.sql` ist
   unverändert (`DdlGoldenMasterTest` grün, kein Golden neu erzeugt).
3. `R405` für eine `geography`-Spalte ohne erreichbare Sicht.
4. **Datenpfad:** `data transfer` PostgreSQL (`geography`) → SQL Server in
   `:test:e2e-cli` (Vorbild `MssqlSpatialTransferE2ETest`): die Werte kommen
   an, der SRID stimmt; dazu ein Fall, der in eine bestehende
   `geography`-Spalte schreibt.
5. **Matrix:** P4 legt den `geography(Point,4326)`-Seed an (H1: erst jetzt)
   — in einer eigenen Seed-Tabelle, weil SQLite ohne Profil `spatialite` die
   ganze Tabelle mit `E052` blockt. Die Anmerkung erwartet `geometry` mit SRID
   und `R403` an der Quelle; SQL Server als Ziel ergibt `geography`
   (`srid: 4326`). Das `search_path`-Bein aus Plan 2 bekommt eine
   `postgis.geography(Point,4326)`-Spalte (`R405`). Betroffen sind alle
   PostgreSQL-Zellen und das Bein; `REPORT_CODES_POSTGRESQL` trägt `R403`.
   Weil der Seed erst mit dem Fix kommt, gibt es keinen Eintrag in der Liste
   bekannter Befunde; „rot ohne Fix" zeigt der Unit- und Integrationstest
   (ohne Fix: `E007`, `GEN-FAIL`).
6. **Doku:** `spec/type-mapping.md` — PostgreSQL 3.1: `geography` kommt in die
   Regel für benutzerdefinierte Typen (samt Schema-Prüfung); die Tabelle wird
   als Zielbild geschrieben und die `tsvector`-Zeile berichtigt (I6);
   Abschnitt 6.4 bekommt den PostgreSQL-Rückweg als Nachbarregel (die Zeile
   mit der Konstante `MssqlTypeMapper.GEODETIC_SRID_RANGE` bleibt, sie
   betrifft nur SQL Server); `R403` steht im PostgreSQL-Abschnitt.
   `spec/ddl-generation-rules.md` 16.2: `geometry` bleibt die einzige
   Renderform, auch für einen SRID im geodätischen Block — als Satz, damit die
   Grenze nicht für eine Lücke gehalten wird. Anwenderhandbuch 3.16 und
   Anhang C, Zeile `geometry` (`docs/user/anwenderhandbuch.md:3526`): der
   PostgreSQL-Rückweg. CHANGELOG „Added" `R403`, „Fixed" (Reverse gültig,
   Datenpfad).
7. **Sabotage:** `geography` wieder als Enum → Unit-Test rot; Schema-Prüfung
   weg → Gegenprobe rot; Datenpfad zurück → E2E rot; Note weg → Test und
   Matrix rot.

**Abnahme:** `:test:integration-postgresql` mit `TestImages.POSTGIS`,
`:test:e2e-cli` (Nulllinie vorher messen), Matrix.

### P7 — SpatiaLite: `NOT NULL` nativ statt ganzer Tabelle (A6, F3, M11, N4)

**Modul:** `:adapters:driven:driver-sqlite`, an vier Stellen.

1. **Generate** — `SqliteTableDdlSupport`: `required` fällt aus
   `hasSpatialMetadataConflict`; `generateSpatiaLiteColumns` hängt das
   sechste Argument an, **nur** bei `required: true`. Eine nullable Spalte
   behält den Aufruf mit fünf Argumenten; die DDL-Goldens
   (`spatial.sqlite.sql` trägt nur nullable Spalten) bleiben unverändert.
2. **Migrate, `CreateTable` und `AddColumn`** — `SqliteSpatialDiffOps`:
   `geometryColumnMetadataBlock` verliert den `required`-Zweig,
   `addGeometryColumnSql` bekommt dasselbe Argument für `CreateTable`. Für
   `AddColumn` auf eine **bestehende** Tabelle ist zuerst zu messen, was
   `AddGeometryColumn(…, 1)` mit vorhandenen Zeilen tut. Füllt SpatiaLite sie
   mit `''` oder lehnt ab, bleibt `AddColumn` mit `required` blockiert (Grund:
   Bestandszeilen hätten keine gültige Geometrie — dieselbe Regel wie für jede
   `NOT NULL`-Spalte ohne Default). Nur wenn SpatiaLite eine leere Tabelle von
   einer gefüllten unterscheidet und die gefüllte ablehnt, darf `AddColumn`
   rendern.
3. **Migrate, Rebuild (M11)** — eine Änderung, die an einer Geometriespalte
   einen Rebuild auslöst (`AlterColumnNullability` und jede andere
   Rebuild-Operation), rendert heute `GEOMETRY` inline. P7 entscheidet, ob der
   Rebuild die Spalte über `AddGeometryColumn` neu anlegt oder die Operation
   mit `SPATIAL_METADATA_UNSUPPORTED` blockt; **Empfehlung: blocken**, solange
   der Rebuild die SpatiaLite-Registrierung (Metadaten, Trigger, Index) nicht
   mitnimmt — eine inline angelegte `GEOMETRY`-Spalte ist in SpatiaLite nicht
   registriert. Die Wahl steht mit Begründung im Plan.
4. **Reverse** — `SqliteSchemaReader`: für eine in `geometry_columns`
   registrierte Spalte ist der Default `''` SpatiaLites eigener Füllwert; er
   kommt nicht als `default: ""` ins Modell. Zuerst messen, was
   `PRAGMA table_info` dort als `dflt_value` liefert.

`E052` bleibt für Primärschlüssel, `unique`, `default`, Fremdschlüssel und
eine tabellenweite Einschränkung (N4: in beiden Pfaden schon so). Kein ADR
(F3).

**Tests, die kippen:** `SqliteDdlGeneratorSpatialTest` pinnt `required`
heute zweimal als Blockade. Beide wechseln auf einen Auslöser, der bleibt
(`unique`, `default`); ein neuer Fall pinnt `required` → sechstes Argument
`1`. **Neue Tests (M11):** der Migrate-Block hat heute keinen; P7 legt je
Auslöser einen an und einen für den Rebuild.

**DoD:**
1. `schema generate --target sqlite --spatial-profile spatialite` mit einer
   `required`-Geometriespalte erzeugt die Tabelle und
   `AddGeometryColumn(…, 1)`, ohne `E052`; die übrigen Auslöser blockieren
   weiter, in beiden Pfaden.
2. Generate und Migrate sagen für `CreateTable` dasselbe; `AddColumn` folgt
   der Messung; der Rebuild folgt der Wahl aus Stelle 3, mit Test.
3. **Live** in `:test:integration-sqlite` (Tooling-Image mit SpatiaLite; die
   Standalone-DDL braucht den Bootstrap aus ADR 0016 vorab): anlegen; eine
   Zeile ohne Geometrie wird abgewiesen, eine mit angenommen; der Reverse
   liefert `required: true` ohne `default`; ein zweiter Generate daraus ist
   identisch.
4. Das `[lite]`-Bein von `make sample-db-spatial-smoke` fährt den
   Konsumentenfall (MySQL-Quelle mit `NOT NULL`-Geometrie → SpatiaLite) ohne
   `E052` und ohne Skip.
5. **Doku (Spec):** `spec/ddl-generation-rules.md` 16.5 bekommt das sechste
   Argument in der Signatur und **erstmals** die Auslöser, die weiter `E052`
   ergeben — für beide Pfade gleich formuliert —, samt der Regel für
   `AddColumn` und Rebuild. `spec/type-mapping.md` 5: SpatiaLites
   `DEFAULT ''` an einer registrierten Spalte ist kein Anwender-Default.
   `spec/cli-spec.md`, `--spatial-profile` bei `schema migrate`: auch das
   Profil `spatialite` und seine Auslöser. `spec/neutral-model-spec.md`
   (Zeile 1493) und `spec/ddl-generation-rules.md` 16.8 (Zeile 2641): `E052`
   kommt auch aus `schema migrate`. Die drei generischen Stellen
   (`spec/cli-spec.md:467`, `spec/neutral-model-spec.md:1503`,
   `spec/ddl-generation-rules.md:2688`) bleiben wörtlich richtig und werden
   nur geprüft.
6. **Doku (Anwender):** Anwenderhandbuch 3.16 — `required` geht,
   Primärschlüssel/`unique`/`default`/Fremdschlüssel blockieren mit `E052`;
   CHANGELOG „Fixed" (eine Tabelle, die bisher mit `E052` fehlte, entsteht).
   Kein neuer Code.
7. **Sabotage je Stelle:** sechstes Argument weg → Generate-Test rot;
   `required` zurück in die Konfliktprüfung → Test je Pfad rot; die Wahl für
   den Rebuild zurückgenommen → Rebuild-Test rot; Default-Ausnahme im Reverse
   weg → Round-Trip-Fall rot.

Mit P7 schließt
[`../open/spatial-profile-e052-ganze-tabelle.md`](../open/spatial-profile-e052-ganze-tabelle.md).

**Abnahme:** `:test:integration-sqlite` (Nulllinie mit Plan 1 gemessen),
`make sample-db-spatial-smoke`. Die Matrix fährt kein SpatiaLite.

### P2b — PostgreSQL: extension-eigene Routinen und Sichten filtern (A3, M7)

**Modul:** `:adapters:driven:driver-postgresql` —
`PostgresProgrammabilityMetadataQueries`: `listFunctions`, `listAggregates`,
`listProcedures`, `listViews`.

**Was P2b baut:** alle vier Abfragen schließen Objekte mit
`pg_depend.deptype = 'e'` aus, wie die Nachbarpfade für Tabellen und Typen.
**Stumm**, wie die drei Vorbilder: ein extension-eigenes Objekt ist kein
Anwenderobjekt, und ein Hinweis entstünde bei jedem Reverse einer
PostGIS-Datenbank. Die Abhängigkeitsabfragen (`deptype IN ('n', 'a')`)
bleiben.

**Zuerst messen:** die Zahl der Funktionen, Aggregate und Sichten von PostGIS
3.6 in `public` und die Größe des Reverse davor und danach (die gemeldeten
Zahlen sind kein Beleg).

**DoD:**
1. Ein PostGIS-in-`public`-Reverse führt keine Funktion, kein Aggregat und
   keine Sicht der Extension und ist nicht mehr um Größenordnungen größer als
   dasselbe Schema ohne PostGIS; die Messung steht im Plan.
2. **Gegenprobe:** eine Anwenderfunktion, die PostGIS benutzt
   (`deptype 'n'`), bleibt im Reverse; eine Anwendersicht über eine
   Geometriespalte bleibt.
3. **Doku:** die Regel „was einer Extension gehört, kommt nicht ins Modell"
   bekommt einen Ort in der Spec (Kandidat: der Abschnitt `schema reverse`
   in `spec/cli-spec.md`), für Tabellen, Typen, Routinen und Sichten zugleich;
   die zwei Stellen, die heute Filterregeln nennen (`spec/cli-spec.md:340`,
   `spec/neutral-model-spec.md:967`), werden geprüft. Anwenderhandbuch 3.3
   („Eine bestehende Datenbank übernehmen"). CHANGELOG „Fixed".
4. **Sabotage:** Filter weg → Test rot, je Abfrage.
5. Der Befund in
   [`../open/reverse-umfang-cli-gegen-mcp.md`](../open/reverse-umfang-cli-gegen-mcp.md)
   bekommt einen Nachtrag: der PostGIS-Teil ist behoben, der
   Umfangs-Unterschied bleibt.

**Abnahme:** `:test:integration-postgresql` (PostGIS in `public`). Die Matrix
hält PostGIS aus `public` (Plan 1).

### P2a — Oracle: Systemobjekte des Spatial-Systems filtern (A2, M7)

**Modul:** `:adapters:driven:driver-oracle` —
`OracleMetadataQueries.listSequences`.

**Zuerst messen** (gegen `TestImages.ORACLE_FULL`, mit einem räumlichen
Index): welche Sequenzen Spatial anlegt, wie sie heißen und was der Katalog
über sie sagt — `ALL_OBJECTS.SECONDARY`, `ORACLE_MAINTAINED`, ein Eintrag in
`ALL_SDO_INDEX_METADATA` oder eine andere Abhängigkeit.

**Regel:**
- Gibt der Katalog ein Kriterium her (Vorbild `o.secondary = 'Y'` im
  Tabellenpfad), filtert P2a danach, **stumm**.
- Gibt er keins her und bleibt nur der Name, filtert P2a nicht nach einem
  Präfix. Dann entsteht eine Note, die die vermutete Systemsequenz nennt und
  sie im Modell lässt; sie braucht einen freien Code im Oracle-Bereich, der
  vor dem Bau reserviert wird.

**DoD:**
1. Die Messung steht im Plan; `MDRS_*` erscheint nicht in `sequences:`
   (bzw. ist nach der zweiten Regel benannt).
2. **Gegenprobe:** eine Anwendersequenz `SDO_ORDER_SEQ` bleibt im Reverse
   (der Unterstrich ist in `LIKE` ein Platzhalter; ein Präfixfilter hätte sie
   versteckt).
3. **Doku:** dieselbe Spec-Regel wie P2b, für Oracle; bei der zweiten Regel
   der Code an seinen Orten. CHANGELOG „Fixed".
4. **Sabotage:** Kriterium weg → Test rot.

**Abnahme:** `:test:integration-oracle` (`ORACLE_FULL`, Nulllinie gemessen).

## Akzeptanzkriterien

1. `geography` liest als Geometrie mit SRID, benannt mit `R403`; die vier
   Gegenproben halten; PostgreSQL rendert vorwärts unverändert `geometry`;
   `data transfer` trägt `geography`-Werte (P4).
2. Eine `required`-Geometriespalte entsteht auf SpatiaLite nativ als
   `NOT NULL`, ohne `E052`; Generate und Migrate haben dieselben Auslöser; der
   Rebuild ist entschieden und getestet; der Reverse liefert die Spalte ohne
   erfundenen Default (P7).
3. Ein PostGIS-in-`public`-Reverse führt keine Extension-Objekte; eine
   Anwenderfunktion mit PostGIS-Nutzung bleibt (P2b).
4. Oracles Spatial-Sequenzen fehlen oder sind benannt; eine Anwendersequenz
   mit `SDO_`-Präfix bleibt (P2a).
5. Jeder Fix fällt nachweislich mit zurückgenommenem Fix, je Stelle.
6. Spec, Anwenderhandbuch und CHANGELOG sind nachgezogen; `make docs-check`
   ohne Befund.

## Verifikation

1. **Nulllinie:** `:test:integration-oracle` und
   `:test:integration-postgresql` sind gemessen, `:test:integration-sqlite`
   mit Plan 1; `:test:e2e-cli` wird vor P4 gemessen. Der PostGIS-Container in
   `:test:integration-postgresql` ist mit Plan 2 eingeführt.
2. **Je Paket:**

   | Paket | Modul | Abnahme |
   | --- | --- | --- |
   | P4 | `driver-postgresql` | `:test:integration-postgresql` (PostGIS), `:test:e2e-cli`, Matrix |
   | P7 | `driver-sqlite` | `:test:integration-sqlite`, `make sample-db-spatial-smoke` |
   | P2b | `driver-postgresql` | `:test:integration-postgresql` (PostGIS in `public`) |
   | P2a | `driver-oracle` | `:test:integration-oracle` (`ORACLE_FULL`) |

3. **Neu-Pin:** nur P4 (ein Commit: Seed, Bein-Spalte, Schlüssel).
4. **Goldens:** P4 und P7 ändern kein DDL-Golden; ändert sich eines, ist das
   ein Befund und kein Neu-Erzeugen.
5. **Gates:** Umbrella, „Gates je Commit"; `make doc-immutable` bestätigt,
   dass ADR 0016 unberührt bleibt.
6. **Was gemessen ist und was nicht:** A6 (SpatiaLite) gemessen; A2 und A3
   gemeldet (Vorbedingung geprüft); A5, M3, M7, M11, L4 und N4 im Code
   geprüft.

## Offen

- **Der Bootstrap steht nicht in `spec/`** (ältere Lücke, s. Abgrenzung).
- **P2a, zweite Regel:** braucht einen reservierten Code, falls die Messung
  kein Katalogkriterium findet.
- **P7, Rebuild:** die Empfehlung „blocken" ist im Paket zu begründen; ein
  Rebuild, der die Registrierung mitnimmt, wäre ein eigener Posten.
