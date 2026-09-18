# Reader-Treue 3: Spatial-Treue (P4, P7, P2a, P2b)

> **Status:** **In Arbeit seit 2026-09-18.** Schnitt 2026-09-17 aus dem
> ungeschnittenen Reader-Slice; Befunde aus Plan-Review und
> Architektur-Prüfung eingearbeitet, Anker gegen `90c6c234f` nachgemessen.
> Teil des Umbrellas
> [`reader-treue.md`](reader-treue.md); dort stehen Nenner, Belegart, Regeln
> der Abnahme, Doku-Pflichten und die Code-Tabelle. Der Bauabschnitt am Ende
> hält Nulllinie, Messungen, Sabotagen und Neu-Pins fest.
> **Vorbedingung / Gate:** [Plan 1](../done/reader-treue-1-matrix-abnahme.md) ist geliefert und graduiert
> (2026-09-18; Matrix als Abnahme,
> PostGIS-Dienst im eigenen Schema), und
> [Plan 2](../done/reader-treue-2-meldungen.md) ebenso (2026-09-18) — **P3
> steht damit**, also auch die Grundlage, die P4 auf `geography_columns`
> erweitert; der PostGIS-Container in `:test:integration-postgresql` ist seit
> P3 gefahren. Die Eigner-Entscheidungen F1 (P4 nur Rückweg) und A6 (P7)
> stehen; keine offene Frage sperrt diesen Plan.
> **Aktivierung:** mit dem ersten Implementierungs-Commit (P4) nach
> `in-progress/` gewandert (2026-09-18); der Umbrella bleibt, wo er ist.
> **Abhängigkeit:** [Plan 1](../done/reader-treue-1-matrix-abnahme.md) und, für P4, P3 aus
> [Plan 2](../done/reader-treue-2-meldungen.md) — **beide geliefert**. P7, P2a und P2b
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

**Reihenfolge:** P4 (nach P3); P7; P2b; P2a; **S5** und **S6**. P7, P2a, P2b,
S5 und S6 sind frei reihbar; S5 und S6 stellen **dieselbe** Frage und werden
zusammen entschieden. P2a läuft im selben `:test:integration-oracle`-Lauf wie
P1 aus Plan 2, wenn beide zeitlich zusammenfallen.

### S5 — SQL Server: der `integer`-Identity-Primärschlüssel behält den Modus

**Nachgetragen am 2026-09-18** (Eigner-Entscheidung). Beim Bau von S1 in Plan 2
gemessen und in
[`../open/mssql-integer-identity-pk-verliert-den-modus.md`](../open/mssql-integer-identity-pk-verliert-den-modus.md)
festgehalten: der SQL-Server-Reverse faltet eine `int IDENTITY(1,1)`-Spalte, die
**allein** den Primärschlüssel bildet, auf `identifier` mit `auto_increment` —
und `identifier` trägt keinen Modus; `bigint IDENTITY` kommt dagegen als
`biginteger` + `generation: identity` zurück. Das ist derselbe stille Verlust,
den S1 für PostgreSQL behoben hat, und der letzte Eintrag aus Plan 2, der noch
in `SILENT_LOSS_KNOWN` steht.

**Gebaut wird** die Entsprechung zu S1: eine solche Spalte liest als `integer`
mit `generation: identity` (Modus erhalten); `by_default` und die
`identifier`-Zusage für Spalten ohne Modus bleiben, wie sie sind. Vor dem Bau
prüfen, ob die SQL-Server-Seite dieselben Nachbarfälle kennt wie S1 (Identity
ohne Primärschlüssel, mehrspaltiger Primärschlüssel, `smallint`), und ob der
Generator die Rückrichtung unverändert rendert. **Zusammen mit S6 zu
entscheiden** — beide fragen, welche Breiten der `identifier`-Vertrag trägt.

**DoD:** Der Seed `sl_pg_identity_int` reist PostgreSQL → SQL Server → Reverse
mit Modus; der Eintrag fällt aus `SILENT_LOSS_KNOWN`, die betroffene Zelle wird
**einzeln** neu gepinnt; Gegenproben (`by_default`, ohne Primärschlüssel,
mehrspaltig) bleiben unverändert; Sabotage je Zweig. Abnahme in
`:test:integration-mssql` und in der Matrix.

### S6 — PostgreSQL: der `smallint`-Identity-Primärschlüssel verliert den Modus

**Nachgetragen am 2026-09-18** aus der Eigner-Entscheidung H1 zu Plan 2.

**Befund.** `PostgresTypeMapping.mapColumn` gibt für eine generierte Spalte im
Primärschlüssel `identifier(auto_increment)` zurück — S1 (Plan 2) hat davon
genau den `integer`-Fall mit `ALWAYS` ausgenommen. Ein
`smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY` liest deshalb weiter als
`identifier`, und `identifier` trägt keinen Modus: der Verlust ist derselbe wie
bei `integer`, nur bleibt er hier **ohne Code**.

**Warum der Zweig eng blieb (gemessen, Plan 2).** Nimmt der Zweig auch
`smallint`, liest die Spalte als `smallint` + `identity(always)` — und genau
diese Form lehnt die Validierung mit
[`E130`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaColumnValidationRules.kt)
ab („identity generation is only valid for integer or biginteger columns"), und
[`PostgresColumnConstraintHelper.identityColumnSql`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresColumnConstraintHelper.kt)
rendert sie für keinen anderen Basistyp. `schema generate` aus dem **eigenen**
Reverse bräche damit ab: aus einem verlustbehafteten, aber lauffähigen Weg
würde ein abbrechender. Ein Integrationsfall pinnt heute beides (die
`identifier`-Lesart und die Validierbarkeit des gelesenen Schemas,
`PostgresIdentityShapeIntegrationTest`).

**Der Zweig ohne Schlüssel ist schon heute unvalidierbar (gemessen,
2026-09-18).** Eine `smallint`-Identity **ohne** Primärschlüssel geht durch den
Zweig für Nicht-Schlüsselspalten, und der nimmt jeden Typ, den
`mapIntegerTypes` kennt — `smallint` also mit. Der Reverse liefert
`smallint` + `identity`, und genau diese Form fällt bei `E130`: aus diesem
Reverse lässt sich nicht generieren. Der Befund ist **älter** als H1 (H1 hat
nur den Schlüssel-Zweig eng gezogen) und gehört zur selben Entscheidung; ein
Wächter-Test in `PostgresIdentityShapeIntegrationTest` hält den Zustand fest
und wird rot, sobald S6 ihn auflöst. Weg 1 müsste ihn mitnehmen (der Zweig
ohne Schlüssel zieht auf `integer`/`biginteger` zusammen), Weg 2 löst ihn
mit dem gelockerten `E130` von selbst.

**Dieselbe Frage wie S5.** Beide Posten fragen: **welche Breiten trägt der
`identifier`-Vertrag, und welche gehören ins Modell?** S5 stellt sie für SQL
Server (`int IDENTITY`), S6 für PostgreSQL (`smallint`). Eine Antwort, die nur
einen der beiden bewegt, spaltet die Regel über die Dialekte.

**Entschieden (Eigner, 2026-09-18): Weg 1** — melden, Modell unverändert. Der
Reverse liest `smallint` weiter als `identifier` und benennt den verlorenen
Modus mit `R406`; der Zweig **ohne** Schlüssel zieht auf `integer`/`biginteger`
zusammen, weil er heute eine Form liefert, aus der sich nicht generieren lässt
(der Wächter-Test wird dabei rot und zieht mit). Dieselbe Antwort gilt für S5 in
dem Sinn, dass der `identifier`-Vertrag seine Breiten behält; S5 baut die
Behebung für `int IDENTITY` wie beschrieben. Die Messung „was tun die fünf
Generatoren heute" bleibt Teil des Pakets — sie begründet den Meldetext.

**Die zwei Wege im Wortlaut des Schnitts:**

1. **Den Zweig eng lassen und den Verlust melden.** Der Reverse liest weiter
   `identifier`, benennt den verlorenen Modus aber mit einem PostgreSQL-Code
   (frei: `R406`). Billig, ändert kein Modell, und die Datei bleibt erzeugbar.
   Kostet: der Modus ist und bleibt weg — die Meldung ersetzt ihn nicht.
2. **Das Modell erweitern.** `E130` wird auf `smallint` gelockert, und jeder
   Generator bekommt eine Render-Regel für eine `smallint`-Identity
   (PostgreSQL: `smallint GENERATED … AS IDENTITY`; SQL Server hat
   `SMALLINT IDENTITY` schon, `spec/type-mapping.md` 6.1; Oracle `NUMBER(4)`
   ebenso; MySQL und SQLite haben keine Entsprechung und melden dann `W163`
   bzw. verlieren die Identity ganz). Teurer, aber verlustfrei auf dem Rückweg
   in denselben Dialekt.

**Zu messen vor der Entscheidung:** was die fünf Generatoren heute für eine
`smallint`-Identity tun (nicht nur PostgreSQL), und ob ein gelockertes `E130`
irgendwo einen Pfad öffnet, der vorher geschlossen war.

**DoD (nach der Entscheidung zu formulieren):** je nach Weg ein Code samt
Registrierungsorten oder eine Modelländerung samt Render-Regel je Dialekt;
`spec/type-mapping.md` 3.4 trägt heute die enge Regel und zieht mit; Gegenprobe
`BY DEFAULT` und `serial`; Sabotage je Zweig; die Matrix bewegt sich nur, wenn
ein Seed eine `smallint`-Identity bekommt (heute hat keiner eine).

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

## Bau

### Nulllinie der Integrationsmodule (2026-09-18, vor dem ersten Paket)

`make integration INTEGRATION_TASKS=":test:integration-postgresql:test
:test:integration-sqlite:test :test:integration-oracle:test
:test:integration-mssql:test :test:e2e-cli:test --continue"`:
**`BUILD SUCCESSFUL` in 23 min 1 s, 107 Tasks.** Alle **fünf** `:test`-Tasks
stehen ohne `SKIPPED` und ohne `UP-TO-DATE` im Lauf — sie sind `executed`.
Damit ist auch die letzte offene Zeile des Umbrellas gemessen:
`:test:e2e-cli` läuft.

**Eine Selbstüberspringung**, dieselbe wie in Plan 1:
`MssqlFullTextEnvironmentIntegrationTest` überspringt sich (`xtest`), wenn das
abgeleitete Volltext-Image fehlt. Auf dem Messhost liegt
`d-migrate-mssql-fts:local`, die Spec lief also mit. In den vier anderen
Testquellbäumen kommt weder `assumeTrue`/`Assumptions` noch `@Disabled` oder
`xtest` vor (über alle fünf Bäume gesucht).

**Eine Testzahl je Modul steht nicht im Lauf** — dieselbe Grenze wie in Plan 1
und 2: das Integrations-Image trägt das Repo als Kopie, die Reports bleiben im
Container, und Gradle zählt in der Konsolenausgabe nichts. Gemessen ist der
ausgeführte Task, nicht die Zahl.

### Messungen vor dem Bau

#### P4 — was `geography_columns` führt (PostGIS 3.6, gemessen 2026-09-18)

| Spalte | `type` | `srid` | `coord_dimension` |
| --- | --- | --- | --- |
| `geography(Point,4326)` | `Point` | 4326 | 2 |
| `geography(LineString,4326)` | `LineString` | 4326 | 2 |
| `geography(Point,4258)` | `Point` | 4258 | 2 |
| `geography` **ohne** Typmodifikator | `Geometry` | **0** | `NULL` |

**Die Schreibweise unterscheidet sich von `geometry_columns`**: dort steht der
Subtyp groß (`POINT`), hier gemischt (`Point`). `GeometryType.of` faltet
beides (es kleinschreibt), also braucht es keine eigene Regel — gemessen,
nicht angenommen.

**`srid = 0` heißt „kein Bezugssystem"** und wird im Modell `null`, wie bei
`geometry` schon.

**Der Anwendertyp ist unterscheidbar.** Ein `CREATE TYPE app.geography AS
ENUM (…)` erscheint in `information_schema.columns` mit
`udt_schema = app, udt_name = geography`, die PostGIS-Spalte mit
`udt_schema = public`. Über den Namen allein wären die beiden nicht zu
trennen; `udt_schema` gegen das Schema der Extension
(`pg_extension` ⋈ `pg_namespace`) trennt sie.

#### P4 — welcher Schreibkonstruktor eine WKB-Eingabe in `geography` trägt

| Form | Ergebnis |
| --- | --- |
| `ST_GeogFromWKB(bytea)` | existiert, **nur einstellig** (`pg_proc`); legt den SRID auf 4326 |
| `ST_GeogFromWKB(?)` in `geography(Point,4258)` | **abgelehnt**: „Geometry SRID (4326) does not match column SRID (4258)" |
| `ST_GeomFromWKB(?, 4326)` in `geography(Point,4326)` | angenommen, SRID 4326 |
| `ST_GeomFromWKB(?, 4258)` in `geography(Point,4258)` | angenommen, SRID 4258 |
| `ST_GeomFromWKB(?)` (SRID 0) in `geography` ohne Modifikator | angenommen, kommt als 4326 an |

**Gebaut wird deshalb mit dem vorhandenen `ST_GeomFromWKB`**: PostGIS erklärt
den Weg von `geometry` nach `geography` als Zuweisungs-Cast, und die
zweistellige Form trägt den SRID, den `ST_GeogFromWKB` nicht nehmen kann. Der
Datenpfad braucht damit **keinen** zweiten Konstruktor und keine neue Naht in
`driver-common` — nur `isGeometryTypeName` und die SRID-Anreicherung müssen
`geography` kennen. Das Lesen ist ohnehin gemeinsam: `ST_AsBinary` gilt für
beide Typen und liefert dasselbe kanonische WKB.

#### P2b — was PostGIS in `public` an Objekten mitbringt (PostGIS 3.6)

| Gegenstand | Zahl in `public` | davon extension-eigen (`pg_depend.deptype = 'e'`) |
| --- | --- | --- |
| Funktionen (`pg_proc`) | 787 | **787** |
| davon über die heutige Abfrage (`information_schema.routines`, `routine_name NOT LIKE 'pg_%'`) | 758 | — |
| Aggregate (`pg_aggregate`) | 22 | **22** |
| Sichten (`relkind IN ('v','m')`) | 2 (`geometry_columns`, `geography_columns`) | **2** |
| Prozeduren | 0 | — |

Die Messung lief gegen ein Image mit PostGIS **und** `fuzzystrmatch` in
`public`; kein einziges dieser Objekte gehört einem Anwender. Der
`deptype = 'e'`-Filter nimmt also genau die Flut und lässt nichts übrig — das
ist der Beleg, dass er nicht zu viel filtert, wenn kein Anwenderobjekt da ist.
Die Gegenprobe (eine Anwenderfunktion, die PostGIS benutzt, und eine
Anwendersicht über eine Geometriespalte) steht im Integrationsfall.

#### P7 — `AddGeometryColumn` mit `not_null` (SpatiaLite 5.1.0, gemessen 2026-09-18)

Gemessen im Integrations-Image über eine echte Datei mit geladener Extension
(`?spatialite=true`), als Wegwerf-Spec mit absichtlich rotem Abschluss, damit
die Werte in der Ausgabe stehen.

| Fall | Ergebnis |
| --- | --- |
| `AddGeometryColumn('t','geom',4326,'POINT','XY',1)` auf **leerer** Tabelle | Rückgabe `1`; die Spalte entsteht als `"geom" POINT NOT NULL DEFAULT ''` (`PRAGMA table_info`: `notnull=1`, `dflt_value=''`) |
| Zeile **ohne** Geometrie danach | abgewiesen — und zwar vom **Geometrie-Trigger**, nicht vom `NOT NULL`: `SQLITE_CONSTRAINT_TRIGGER`, „t.geom violates Geometry constraint [geom-type or SRID not allowed]" |
| Zeile **mit** Geometrie | angenommen |
| derselbe Aufruf auf einer **gefüllten** Tabelle | **gelingt** (Rückgabe `1`). Die Bestandszeile trägt danach `geom = ''` (`typeof` = `text`, nicht `NULL`) — also den Füllwert, keine gültige Geometrie |
| fünfstellige Form (nullable) | `"geom" POINT`, `notnull=0`, **kein** Default |

**Zwei Entscheidungen folgen daraus.** Erstens: `AddColumn` auf eine
bestehende Tabelle bleibt mit `required` **blockiert**. SpatiaLite
unterscheidet leer und gefüllt nicht und lehnt die gefüllte nicht ab; es füllt
still mit `''`, und dieser Wert hätte beim Einfügen gerade der
Geometrie-Trigger abgewiesen. Eine Anweisung, die Bestandszeilen mit einem
Wert zurücklässt, den dieselbe Tabelle nicht annähme, ist kein Fortschritt
gegenüber der Blockade. Zweitens: der **Reverse** muss den Default `''`
verwerfen — er ist SpatiaLites Füllwert, kein Anwender-Default, und ein
Default ist selbst ein `E052`-Auslöser.

#### P2a — was Oracle Spatial an Sequenzen anlegt (Oracle 23 `ORACLE_FULL`, gemessen 2026-09-18)

Gemessen an einem eigenen Container: Tabelle mit `SDO_GEOMETRY`, Zeile in
`USER_SDO_GEOM_METADATA`, eine Zeile Daten, dann
`CREATE INDEX … INDEXTYPE IS MDSYS.SPATIAL_INDEX_V2`.

**Ein Nebenbefund auf dem Weg:** der Index braucht **Daten**. Auf der leeren
Tabelle scheitert er mit `ORA-13199: Table is empty; cannot determine SRID`
— genau der Grund, aus dem das Anwenderhandbuch für Oracle
`--split pre-post` empfiehlt.

| Objekt | `ALL_OBJECTS.SECONDARY` | `GENERATED` | `ORACLE_MAINTAINED` |
| --- | --- | --- | --- |
| `MDRS_11E5A$` (Sequenz, vom Spatial-Index angelegt) | **`Y`** | `N` | `N` |
| `MDRT_11E5A$` (Tabelle, dieselbe Herkunft) | `Y` | — | — |
| `ISEQ$$_73303` (Identity-Sequenz) | `N` | `Y` | `N` |
| `SDO_ORDER_SEQ` (Anwendersequenz) | `N` | `N` | `N` |

**Das Kriterium kommt aus dem Katalog** — dasselbe, das der Tabellenpfad schon
benutzt (`o.secondary = 'Y'`). Die erste Regel des Pakets greift damit, und es
braucht weder einen Namensfilter noch einen neuen Code. Gegengeprüft mit der
fertigen Abfrage: `MDRS_11E5A$` fällt weg, `SDO_ORDER_SEQ` bleibt, und eine
eigens angelegte Anwendersequenz `MDRS_KUNDE$` bleibt ebenfalls — ein
Präfixfilter hätte beide versteckt.

### P7 — SpatiaLite: `NOT NULL` nativ (2026-09-18)

**Gebaut an vier Stellen**, mit dem `AddGeometryColumn`-Aufruf **einmal** in
`SqliteSpatialGeometryColumn`: Generate und Migrate schrieben ihn je selbst,
und seit die Signatur ein siebtes Stück trägt, wäre das die Art Abweichung,
die erst an einem echten Server auffiele.

1. **Generate** — `required` fällt aus `hasSpatialMetadataConflict`; der
   Aufruf bekommt `, 1`. Eine nullbare Spalte behält die fünfstellige Form,
   und die DDL-Goldens bewegen sich nicht (`:adapters:driven:formats:check`
   grün, kein Golden neu erzeugt).
2. **Migrate, `CreateTable`** — `geometryColumnMetadataBlock` verliert den
   `required`-Zweig; die übrigen Auslöser bleiben, und beide Pfade nennen
   jetzt dieselben.
3. **Migrate, `ADD COLUMN`** — eigener Block (`addColumnRequiredBlock`) nach
   der Messung: eine `required`-Geometriespalte an eine bestehende Tabelle
   bleibt blockiert, mit dem Grund in der Meldung.
4. **Migrate, Tabellen-Neubau** — **blockt**, wie empfohlen. Die Begründung
   steht im Code und in der Spec: der Neubau schreibt die Zieltabelle über
   `columnLine` neu, eine Geometriespalte entstünde dort inline und damit
   ohne Eintrag in `geometry_columns`, ohne Integritäts-Trigger und ohne
   R\*Tree. Die Anweisungsfolge liefe durch, die Tabelle sähe richtig aus, und
   die Geometrie wäre still keine mehr. Geprüft werden **beide** Seiten des
   Plans — eine Spalte, die der Neubau anlegt, wäre unregistriert, eine, die
   er kopiert, verlöre ihre Registrierung mit dem `DROP TABLE`. Die
   Registrierung mitzunehmen ist ein eigener Posten.
5. **Reverse** — `SqliteSpatialDefault` verwirft das leere Literal **nur** an
   einer in `geometry_columns` registrierten Spalte. An jeder anderen bleibt
   `DEFAULT ''` ein Anwender-Default; ein anderer Default an einer
   Geometriespalte bleibt ebenso.

**Die zwei Tests, die kippen mussten**, sind gekippt: `required` als Blockade
stand zweimal (`SqliteDdlGeneratorSpatialTest` und, wortgleich kopiert,
`SqliteDdlGeneratorTestPart3`). Sie stehen jetzt auf Auslösern, die bleiben
(`unique`, `default`, Primärschlüssel); dazu ein Fall, der `required` → `, 1`
pinnt, und eine Gegenprobe auf die fünfstellige Form.

**Die Migrate-Seite hatte keinen einzigen Test** (M11). Neu ist
`SqliteDiffSpatialMetadataTest` mit zehn Fällen: je Auslöser einer, `AddColumn`
in beiden Ausgängen und der Neubau mit Gegenprobe. Dass die Spec wirklich
läuft, ist mit einer absichtlich falschen Zusicherung geprüft (rot), danach
entfernt.

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
- **S5 und S6 gemeinsam:** welche Breiten der `identifier`-Vertrag trägt. Ohne
  diese Entscheidung bleibt der SQL-Server-Fall der letzte Eintrag aus Plan 2
  in `SILENT_LOSS_KNOWN`, und der PostgreSQL-`smallint`-Fall bleibt ganz ohne
  Code.
