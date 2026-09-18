# Reader-Treue 2: Verluste werden gemeldet (P5, P10, P8, P9, P1, P3, S1–S3)

> **Status:** **In Arbeit seit 2026-09-18.** Schnitt 2026-09-17 aus dem
> ungeschnittenen Reader-Slice; Befunde aus Plan-Review und
> Architektur-Prüfung eingearbeitet, Anker gegen `90c6c234f` nachgemessen. Der
> Bauabschnitt unten hält Nulllinie, Messungen, Sabotagen und Neu-Pins fest.
> Teil des Umbrellas
> [`reader-treue.md`](reader-treue.md); dort stehen Nenner, Belegart, Regeln
> der Abnahme, Doku-Pflichten und die Code-Tabelle.
> **Vorbedingung / Gate:** [Plan 1](../done/reader-treue-1-matrix-abnahme.md) ist geliefert und graduiert
> (2026-09-18) — die Matrix ist Abnahme, und
> ihre Liste bekannter Befunde trägt die Einträge dieses Plans. F2 ist
> entschieden
> ([ADR 0058](../../adr/0058-verlorener-srid-beim-reverse-ist-warnung.md)); P1
> ist damit frei. Die Eigner-Fragen sind am 2026-09-17 entschieden, alle wie
> empfohlen: **E2** — S1 wird behoben; **E3** — S2 übernimmt Ursache 1, Ursache
> 2 wird nur gemessen; **S3** — mit Anhebung des Fingerabdrucks (v16 → v17).
> Keine Sperre mehr (Umbrella, „Offen").
> **Aktivierung:** mit dem ersten Implementierungs-Commit nach `in-progress/`
> gewandert (2026-09-18); der Umbrella bleibt, wo er ist.
> **Abhängigkeit:** [Plan 1](../done/reader-treue-1-matrix-abnahme.md). Innerhalb: P10 nach P5 (dieselben Stellen); der
> `json[]`-Teil von P8 nach S3; Plan 3 baut P4 auf P3 auf.

## Befund

### B1, B2 — Arrays verlieren auf MySQL und SQLite still ihre Array-Eigenschaft (gemeldet; SQLite im Code geprüft)

- **MySQL:** `MysqlTypeMapper` bildet `Array` auf `JSON` ab
  (`MysqlTypeMapper.kt:41`), ohne Note. Oracle meldet denselben Verlust mit
  `W149`, SQL Server seinen (`NVARCHAR(MAX)`) mit `W137`
  (`MssqlColumnConstraintHelper.kt:333`).
- **SQLite (Review M2):** `SqliteTypeMapper` bildet `Array` still auf `TEXT`
  ab (`SqliteTypeMapper.kt:30`); `SqliteColumnConstraintHelper` meldet an
  dieser Stelle nur `W200` und `W132`. Der ungeschnittene Plan hielt SQLite
  für „nicht betroffen" — das stimmt nicht.
- **Zwei Renderer je Dialekt.** Der Migrate-Pfad rendert selbst:
  `MysqlDiffSqlBuilders.columnLine` (`MysqlDiffSqlBuilders.kt:30`) und
  `SqliteDiffSqlBuilders.columnLine` rufen `typeMapper.toSql` direkt.
- **Der Notizkanal ist da (Review L2).** Der MySQL-Migrate-Pfad hat
  `MysqlDiffRenderContext.warning` (`MysqlDiffRenderContext.kt:219`); Vorbild
  ist `warnIfDegradingEnum`, aufgerufen in `MysqlDiffTableOps.kt:129` und
  `:236`. SQLite hat `SqliteDiffRenderContext.warning`
  (`SqliteDiffRenderContext.kt:184`) und das Vorbild `W135`
  (`SqliteCompositePkIdentity`). `columnLine` wird in `MysqlDiffTableOps` an
  sieben Stellen aufgerufen (Zeilen 91, 235, 312, 365, 430, 444, 452).
- **B2 — die Grenze:** der MySQL-Reverse liest `JSON` immer als `json`. Die
  Kette PostgreSQL `integer[]` → MySQL `JSON` → Reverse → `json` → PostgreSQL
  `JSONB` verliert die Array-Eigenschaft unwiderruflich; meldbar ist nur der
  Hinweg.

### B3 — PostgreSQL `json` wird `jsonb` (im Code bestätigt; Eigner: gleichsetzen, aber laut)

`PostgresTypeMapping.mapSpecialTypes` bildet beide auf `NeutralType.Json` ab
(`PostgresTypeMapping.kt:142`); der Generator rendert `JSONB`. Beim Übertragen
ändern sich die Daten: Schlüsselreihenfolge, doppelte Schlüssel und Leerraum
gehen verloren. Die Begründung muss in `spec/type-mapping.md` tragen. Ein ADR
ist es nicht: das Modell bekommt keinen neuen Typ, und nur ein solcher bräuchte
nach der Abgrenzung von
[ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) eine eigene
Entscheidung. **`json[]`** liest mit Element `json`
(`PostgresTypeMapping.kt:185`), rendert aber heute gar nicht als `jsonb[]`,
sondern als `TEXT[]` (S3).

### D2, N1, L3 — stille Typ-Fallbacks im Reverse (D2 nachgemessen, Rest im Code geprüft)

- **PostgreSQL:** `numeric` ohne Präzision wird `float`
  (`mapNumericTypes`, `PostgresTypeMapping.kt:115`).
- **N1:** ein Array unbekannten Elementtyps (`date[]`, `timestamp[]`,
  `inet[]`) liest mit `element_type: text` **ohne** `R301`
  (`mapArrayElementType`, `PostgresTypeMapping.kt:186`).
- **L3:** `mapCompositeFieldType` (Felder zusammengesetzter Typen) hat
  dieselben stillen Fallbacks: `numeric` ohne Präzision wird `float`
  (`:273`), ein unbekannter Typ `text` (`:281`), beides ohne Note.
- **SQLite:** `NUMERIC` ohne Präzision wird `float`
  (`SqliteTypeMapping.mapNumericType`).
- **Oracle (Architektur-Prüfung):** `NUMBER` ohne Präzision und Skala wird
  still `decimal(38,10)` (`OracleTypeMapping.kt:109`). Dieselbe Klasse.
- `spec/type-mapping.md`, Abschnitt 8, schreibt für den `else`-Fallback
  „immer eine diagnostische Warning-Note" fest; N1 und L3 widersprechen dem.
  Die Linie „verlustbehaftet, aber eindeutig → gewöhnliche Note" steht in
  [`spec/dialect-preference-mechanism.md`](../../../spec/dialect-preference-mechanism.md),
  Abschnitte 4 und 6.

### D4, H2 — `ALWAYS` ohne Entsprechung (MySQL nachgemessen, SQLite im Code geprüft)

- **MySQL:** `columnGeneratedIdentity` (`MysqlColumnConstraintHelper.kt:78`)
  schreibt `NOT NULL AUTO_INCREMENT`, ohne auf den Modus zu sehen; der Reverse
  liest `by_default`. Der Schwesterfall hat einen Code: SQL Server meldet
  `BY DEFAULT` mit `W140`.
- **SQLite:** `generateRowidIdentityColumn`
  (`SqliteColumnConstraintHelper.kt:98`) schreibt
  `INTEGER PRIMARY KEY AUTOINCREMENT` für beide Modi ohne Note.
- **Spec:** die „Grenze — der Identity-Modus" im Abschnitt `schema compare`
  von `spec/cli-spec.md` (Zeile 910) nennt nur `W140`. Der Modus bleibt im
  Vergleich ein Fund
  ([ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md),
  Abschnitt 2, Punkt 5).
- **Matrix (H2):** der P10-Seed ist `bigint GENERATED ALWAYS AS IDENTITY`
  (Plan 1). Die Fixture trägt eine `biginteger`-Identity, die SQL Server als
  `IDENTITY` rendert und als `ALWAYS` zurückliest; sie löst `W163` also in den
  Zellen SQL Server → MySQL und → SQLite aus.

### M4 — der SQLite-Migrate-Pfad kennt `generation: identity` nicht (im Code geprüft) → S2

`SqliteDiffSqlBuilders.columnLine` rendert den Typ über `typeMapper.toSql`
(`SqliteDiffSqlBuilders.kt:66`); die KDoc von `primaryKeyClause` sagt es
ausdrücklich: `columnLine` rendert `ColumnGeneration.Identity` nicht inline
(`:94`). Eine Identity-Änderung wird mit `IDENTITY_IS_PART_OF_THE_TYPE`
geblockt (`SqliteDiffDdlGenerator.kt:400`). Eine
`biginteger`-Spalte mit `generation: identity`, die `schema migrate` anlegt,
bekommt also gar kein `AUTOINCREMENT`. Das ist Ursache 1 von
[`../open/sqlite-migrate-biginteger-identity-render-gap.md`](../open/sqlite-migrate-biginteger-identity-render-gap.md).
Die DoD „`W163` auf Generate und Migrate" ist für SQLite so nicht umsetzbar:
auf dem Migrate-Pfad geht nicht der Modus verloren, sondern die ganze
Identity.

### A1, M6 — Oracle verliert den SRID quotiert kleingeschriebener Tabellen (nachgemessen; entschieden: Warnung)

- Oracle schreibt Tabellen- und Spaltennamen in `USER_SDO_GEOM_METADATA`
  bedingungslos groß (gemessen, `oracle-dialect-scoping.md` in `done/`); eine
  quotiert kleingeschriebene Tabelle — so legt d-migrate sie an — kann keine
  Zeile haben, die sie beschreibt. Der Reverse liest den SRID per exaktem
  Namensabgleich (`OracleSchemaReader.kt:124`) und verliert ihn **ohne jede
  Meldung**.
- Ein toleranter Abgleich ist geprüft und verworfen: er werte die Zeile einer
  fremden Tabelle aus, und dieselbe Abfrage speist den Datenpfad
  (`OracleDataWriter`).
- **`R365`** meldet nur den werfenden Weg (Sicht nicht lesbar), als `INFO`
  (`OracleSchemaReader.kt:216`), und zwar für **jede** Tabelle, auch ohne
  Geometriespalte: `geometryMetadata` läuft je Tabelle
  (`OracleSchemaReader.kt:101`) und liefert im Fehlerfall eine leere Map
  (`:222`). Eine Note „kein SRID gefunden" entstünde dort zusätzlich zu
  `R365`, wenn sie nicht ausgeschlossen wird (Review M6).
- **Entschieden in ADR 0058:** `R370` und `R365` sind `WARNING`, kein Block;
  der Ausweg ist, den SRID in der Schemadatei zu deklarieren oder die Tabelle
  unquotiert anzulegen, **nicht**, die Metadatenzeile von Hand einzufügen.
  `R365` an einer Tabelle ohne Geometriespalte ist keine Warnung. Offen lässt
  der ADR, ob auch eine unquotierte Tabelle ohne registrierte Zeile gemeldet
  wird.
- **`W120` widerspricht sich schon heute** (Review M6).
  `OracleColumnConstraintHelper.kt:311` sagt für **jede** Tabelle, Oracle
  könne eine quotiert kleingeschriebene Tabelle wie diese nicht beschreiben,
  auch wenn der Name großgeschrieben ist; der Hinweis (`:316`) empfiehlt
  genau den ausgeschlossenen Ausweg, die Zeile von Hand einzufügen.
- **Anwendersicht:** `R365` steht im Anwenderhandbuch unter der Frage „Was
  liest `schema reverse` von den Oracle-Routinen nicht?"
  (`docs/user/anwenderhandbuch.md:3088`, Zeile `:3101`).

### A4 — die Degradierung ohne `search_path` nennt keinen Grund (gemeldet; im Code geprüft)

PostGIS im eigenen Schema, aber nicht im `search_path`: die Spalte kommt als
`geometry` ohne Subtyp und SRID. Es gibt eine Note (`R401`, `INFO`, je
PostGIS-Spalte), aber sie nennt weder Ursache noch Ausweg.
`PostgresTableMetadataQueries.listGeometryColumns` fragt zuerst
`to_regclass('geometry_columns')` und gibt bei `null` still eine leere Liste
zurück (`PostgresTableMetadataQueries.kt:84`) — dort ist der Grund bekannt.
**Durch den Schnitt vertauscht:** im ungeschnittenen Plan kam P4 (liest
`geography_columns`) vor P3. Jetzt deckt P3 nur `geometry_columns`; Plan 3
erweitert den Hinweis mit P4.

### S1 — `integer`-Identity als alleiniger Primärschlüssel verliert den Modus (Review H2, im Code geprüft)

`PostgresTypeMapping.mapColumn` gibt für eine generierte Spalte im
Primärschlüssel mit `integer`/`smallint` `Identifier(autoIncrement = true)`
zurück, **ohne** `generation` (`PostgresTypeMapping.kt:54`; `isPkCol` aus
`PostgresSchemaStructureReaders.kt:72`). Der `bigint`-Zweig davor behält den
Modus (`:46`), der Zweig für Nicht-Schlüsselspalten ebenso. Der
PostgreSQL-Generator rendert `identifier` als `SERIAL`
(`PostgresTypeMapper.kt:15`). Damit wird
`integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY` schon PostgreSQL →
PostgreSQL zu `SERIAL`, und kein Vergleich zweier Reverses sieht es.

### S3 — der PostgreSQL-Generator rendert Array-Elemente als `TEXT[]` (Review M5, im Code geprüft)

`PostgresTypeMapper.toSql` rendert ein Array über `resolveElementType`
(`PostgresTypeMapper.kt:22`, `:73`), und das kennt nur `text`, `integer`,
`boolean` und `uuid`; alles andere wird `TEXT`. PostgreSQL `bigint[]`,
`double precision[]`, `numeric[]`, `json[]` und `date[]` werden also
PostgreSQL → PostgreSQL still `text[]`, obwohl der Reverse das Element
(`biginteger`, `float`, `decimal`, `json`) richtig liest.

**Der Fingerabdruck hängt daran (I4).** Der PostgreSQL-Kanonisierer rendert
einen Typ und liest ihn zurück; nur bei `R301` behält er den Eingang
(`PostgresNeutralTypeCanonicalizer.kt:39`). Sein `elementUdtName` spiegelt den
geschlossenen Satz von `resolveElementType`. Heute projiziert er also
`array(biginteger)` auf `array(text)`. Wer S3 behebt, ändert diese Projektion
und damit den Fingerabdruck (heute `schema-fingerprint-v16`,
`MigrationFingerprint.kt:177`). Nach etablierter Praxis heißt das eine
Anhebung, die bestehende Rollback-Artefakte und Overlay-Pins laut ungültig
macht.

## Ziel

Jeder Verlust dieses Plans wird benannt — mit dem Mechanismus, den der
Schwesterdialekt schon benutzt. Wo der Verlust auf dem Rückweg in **denselben**
Dialekt entsteht und der Generator oder Reader ihn vermeiden kann (S1 bis S3),
wird er behoben statt gemeldet; eine Note an seiner Stelle wäre ein
Platzhalter.

| Posten | Ausgang |
| --- | --- |
| B1 und M2 | `W162` auf MySQL und SQLite, Generate und Migrate |
| B2 | Grenze, als erwarteter Ausgang gepinnt |
| D4 | `W163` auf MySQL (Generate und Migrate) und SQLite (Generate; Migrate nach S2) |
| B3 | `R402` am PostgreSQL-Reverse |
| D2, N1, L3 | `R404` (PostgreSQL), `R221` (SQLite), `R371` (Oracle), `R301` für die Fallbacks |
| A1 | `R370`; `R365` wird `WARNING`; `W120` sagt je Fall das Richtige |
| A4 | `R405` mit Grund und Ausweg |
| S1, S2, S3 | nach Messung behoben (E2, E3, Bestätigung der Anhebung) |

Begründet gegen [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004)
(Reverse-Engineering, PostgreSQL mit JSON/JSONB- und Array-Spalten, Abschnitt
8.4) und [`LN-016`](../../../spec/lastenheft-d-migrate.md#ln-016)
(Fehlermeldungen aussagekräftig und handlungsorientiert).

## Abgrenzung

- **Keine Modellerweiterung** (Umbrella). P8 und P9 machen laut, erweitern
  nicht.
- **P1 lockert den Abgleich nicht und blockt nichts** (ADR 0058).
- **P3 nicht über `schema validate`:** das Kommando nimmt nur eine Datei
  (`SchemaValidateCommand.kt:22`), und Read-Notes stehen nur im Reverse-Report.
- **P3 deckt `geography_columns` nicht** — das kommt mit P4 in Plan 3.
- **P5 heilt B2 nicht.** Was im Ziel nicht mehr existiert, kann kein Reader
  zurückgewinnen.
- **S2 übernimmt nur Ursache 1** des `open/`-Eintrags (nach E3); das
  Präferenz-Threading im Post-Compare-Re-Read und der MySQL-Teil bleiben dort.
- **Der `W137`-Doppelbeleg** wird hier nicht aufgelöst (Ledger-Eintrag).
- **Nackte reservierte Wörter gegen PostgreSQL, SQL Server und Oracle** gehören
  nicht hierher, sind aber der nächste Posten derselben Klasse: ein rein
  kleingeschriebener Spaltenname steht im neutralen Ausdruck unquotiert, und
  ist er auf dem Ziel reserviert, lehnt der Server die **ganze** DDL ab
  (gemessen: PostgreSQL `syntax error at or near "order"`, SQL Server
  `Msg 156`). Nur der MySQL-Generator quotiert seit Plan 1 zurück. Der Befund
  samt Messung und den drei Wegen steht in
  [`../open/nackte-reservierte-woerter-im-rohen-ausdruck.md`](../open/nackte-reservierte-woerter-im-rohen-ausdruck.md);
  er braucht zuerst eine Naht in den übrigen Generatoren und deshalb einen
  eigenen Schnitt.

## Arbeitspakete

**Reihenfolge:** P5 → P10; P8 und P9; P1; P3; S1 bis S3 messen zuerst und
dürfen parallel laufen, S3 vor dem `json[]`-Teil von P8, S2 vor dem
SQLite-Migrate-Teil von P10.

### P5 — MySQL und SQLite melden den Array-Verlust (B1, B2, M2, L2)

**Modul:** `:adapters:driven:driver-mysql` (`MysqlColumnConstraintHelper`,
die Aufrufstellen von `MysqlDiffSqlBuilders.columnLine` in
`MysqlDiffTableOps`) und `:adapters:driven:driver-sqlite`
(`SqliteColumnConstraintHelper`, die Aufrufer von
`SqliteDiffSqlBuilders.columnLine`).

**Was P5 baut:** die Note `W162` — die Spalte ist ein Array und wird als
`JSON` (MySQL) bzw. `TEXT` (SQLite) gerendert; ein Reverse liest `json` bzw.
`text` zurück. Im Generate-Pfad in die vorhandene Notizliste, im Migrate-Pfad
über `warning` des Render-Kontexts, nach dem Muster von `warnIfDegradingEnum`
bzw. `W135`, an **jeder** Aufrufstelle, die eine Array-Spalte rendert.
Warum ein eigener Code und nicht `W149`: Umbrella, „Codes".

**DoD:**
1. `W162` auf MySQL und SQLite, je Generate und Migrate, mit einem Test je
   Pfad und Dialekt.
2. Ein Test pinnt die Kette PostgreSQL `integer[]` → MySQL `JSON` → Reverse →
   `json` als **erwarteten** Ausgang (B2).
3. **Doku:** fünf Registrierungsorte (Umbrella); die Render-Regeln in
   `spec/ddl-generation-rules.md` 3.4 (MySQL) und 3.5 (SQLite), nach dem
   Muster von 3.8 (`W137`) und 3.9 (`W149`); Anwenderhandbuch 3.2 („SQL für
   eine Zieldatenbank erzeugen") und Anhang C, Zeile `array`;
   Troubleshooting-Leitfaden, wo ein Ausweg besteht; CHANGELOG „Added".
4. **Matrix:** `W162` erscheint in `GEN_CODES_POSTGRESQL_MYSQL` und
   `GEN_CODES_POSTGRESQL_SQLITE`; die Einträge für P5 verlassen die Liste
   bekannter Befunde. Betroffen sind die Zellen mit Ziel MySQL oder SQLite,
   deren Quelle Arrays trägt (heute nur PostgreSQL).
5. **Sabotage:** Note zurückgenommen → Tests je Pfad rot, Matrix rot.

**Abnahme:** `:test:integration-mysql`, `:test:integration-sqlite`, Matrix.

### P10 — `ALWAYS` ohne Entsprechung wird gemeldet (D4, H2, M4)

**Modul:** `:adapters:driven:driver-mysql` (`columnGeneratedIdentity`, die
Migrate-Aufrufstellen wie in P5), `:adapters:driven:driver-sqlite`
(`generateRowidIdentityColumn`; der Migrate-Pfad erst nach S2).

**Zuerst messen (SQLite):** dass `INTEGER PRIMARY KEY AUTOINCREMENT` einen
ausdrücklich eingefügten Wert annimmt, also `ALWAYS` nicht durchsetzt, und
was der Reverse zurückliest. Widerlegt die Messung den Code-Befund, entfällt
SQLite.

**Was P10 baut:** `W163`, ein Code für beide Dialekte (Muster `W132`): die
Identity-Spalte ist als Autowert gerendert, der ausdrücklich gesetzte Werte
annimmt; der Modus `always` ist nicht durchgesetzt, ein Reverse liest
`by_default`. Nur bei `mode: always`.

**DoD:**
1. MySQL meldet `W163` auf Generate und Migrate; SQLite auf Generate, auf
   Migrate erst, wenn S2 den Pfad rendern lässt — sonst benennt der Plan die
   Lücke.
2. Tests je Pfad; ein Test, dass `by_default` keine Note trägt.
3. Die Tabelle der Migrate-Änderungen in `spec/ddl-generation-rules.md`
   („Identity-Modus": „MySQL kennt keinen Modus") bleibt; ob ein **Wechsel**
   des Modus im Migrate-Pfad etwas meldet, prüft das Paket mit.
4. **Doku:** fünf Registrierungsorte; `spec/ddl-generation-rules.md` 3.4 und
   3.5; `spec/cli-spec.md`, „Grenze — der Identity-Modus", nennt `W163` neben
   `W140`; Anwenderhandbuch 3.12 („Sequenzen/Autowerte korrekt mitnehmen")
   und der Compare-Abschnitt (Verweis auf den Code); CHANGELOG „Added". Im
   [Toleranzprofil](../next/compare-toleranzprofil.md) ist der Code Beleg für K2 —
   dort nur ein Verweis.
5. **Matrix:** `W163` erscheint in `GEN_CODES_POSTGRESQL_MYSQL` und
   `_SQLITE` (Seed) sowie in `GEN_CODES_MSSQL_MYSQL` und `_SQLITE` (Fixture,
   s. Befund). Die P10-Einträge verlassen die Liste.
6. **Sabotage:** Note weg → Tests und Matrix rot; Note auch bei `by_default`
   → Test rot.

**Abnahme:** `:test:integration-mysql`, `:test:integration-sqlite`, Matrix.

### P8 — PostgreSQL: `json` wird laut (B3)

**Modul:** `:adapters:driven:driver-postgresql` — der gemeinsame Zweig
`"json", "jsonb"` in `mapSpecialTypes` teilt sich: `jsonb` bleibt still,
`json` bekommt `R402`.

**Die Note** nennt, was verloren geht, wenn die Spalte als `jsonb` neu
entsteht: Schlüsselreihenfolge, doppelte Schlüssel, Leerraum. Severity
`WARNING`, weil sich beim Übertragen die Daten ändern.

**`json[]`:** das Element `json` trägt dieselbe Note erst, wenn S3 das Array
als `jsonb[]` rendert. Vorher wäre die Aussage „wird als `jsonb` gerendert"
falsch.

**DoD:**
1. Ein PostgreSQL-Reverse einer `json`-Spalte trägt `R402` mit dem
   Spaltennamen, eine `jsonb`-Spalte keine Note; nach S3 gilt dasselbe für
   `json[]`.
2. Unit-Test und ein Fall in `:test:integration-postgresql`.
3. **Doku:** `spec/type-mapping.md`, PostgreSQL-Abschnitt: die `json`-Zeile
   mit der Begründung des Rückwegs. Abschnitt 3.3 führt `jsonb` heute mit
   Statusspalte und die Zeile „`generated always as (...)` — ❌ Nicht
   erkannt", die 6.3 widerspricht (berechnete Spalten kommen in allen fünf
   Dialekten zurück); P8 schreibt die Zeilen, die es berührt, als Regel
   (Umbrella, Spec-Richtung). `spec/neutral-model-spec.md`, Zeile `json` der
   Soll-Tabelle: die Tabelle beschreibt die Renderrichtung, der Rückweg steht
   in `type-mapping.md` — ohne Verweis auf einen Plan oder `open/`-Eintrag.
   Anwenderhandbuch 3.3, Hinweise. CHANGELOG „Added".
4. **Matrix:** `R402` erscheint in `REPORT_CODES_POSTGRESQL`; der Eintrag
   verlässt die Liste. Betroffen ist nur der Reverse-Report (alle
   PostgreSQL-Zellen teilen ihn).
5. **Sabotage:** Note zurückgenommen → Unit-Test, Integrationsfall und Matrix
   rot.

Mit P8 schließt
[`../open/json-jsonb-zweite-json-art.md`](../open/json-jsonb-zweite-json-art.md).

### P9 — stille Typ-Fallbacks im Reverse werden benannt (D2, N1, L3, Oracle `NUMBER`)

**Modul:** `:adapters:driven:driver-postgresql` (`mapNumericTypes`,
`mapArrayElementType`, `mapCompositeFieldType`),
`:adapters:driven:driver-sqlite` (`SqliteTypeMapping.mapNumericType`),
`:adapters:driven:driver-oracle` (`mapNumberPrecision`).

**Was P9 baut.** Das Mapping bleibt; der Verlust wird benannt:
- `R404` (PostgreSQL), `R221` (SQLite): `numeric` ohne Präzision wird
  Gleitkomma, auch auf dem Rückweg in denselben Dialekt. Dasselbe für ein Feld
  eines zusammengesetzten Typs.
- `R371` (Oracle): `NUMBER` ohne Angabe wird `decimal(38,10)`; mehr als zehn
  Nachkommastellen und mehr als 28 Vorkommastellen gehen verloren.
- `R301` für den `else`-Fallback des Array-Elements und des Feldtyps, wie
  `spec/type-mapping.md` Abschnitt 8 es schon verlangt.

Alle `WARNING`. Warum Oracle hier und nicht nur im `open/`-Eintrag: dieselbe
Klasse, dieselbe Linie aus `spec/dialect-preference-mechanism.md`, und ohne
ihn bliebe genau ein Verlust dieser Art still. Die Modellfrage liegt in
[`../open/decimal-ohne-praezision-verlustfrei.md`](../open/decimal-ohne-praezision-verlustfrei.md).

**DoD:**
1. Die drei Reverses melden ihren Fall mit ihrem Code; ein
   PostgreSQL-Array unbekannten Elementtyps und ein unbekannter Feldtyp melden
   `R301`; je Fall ein Test.
2. **Doku:** `spec/type-mapping.md` — PostgreSQL-Abschnitt (neue Regel für
   `numeric` ohne Präzision), SQLite 5.2 (die Zeile „`NUMERIC` ohne Precision"
   nennt den Code und wird als Regel geschrieben), Oracle 7.2 (die Zeile
   `NUMBER` ohne Angabe nennt `R371`), Abschnitt 8 gilt ausdrücklich auch für
   Array-Elemente und Felder zusammengesetzter Typen. Anwenderhandbuch 3.3,
   Hinweise. CHANGELOG „Added".
3. **Matrix:** `REPORT_CODES_POSTGRESQL` trägt `R404` und `R301` (für
   `date[]`), `REPORT_CODES_SQLITE` trägt `R221`; die Einträge für P9 und N1
   verlassen die Liste.
4. **Sabotage** je Note → rot.

**Abnahme:** Unit-Tests, Matrix; Oracle in `:test:integration-oracle`
(`NUMBER` ohne Angabe braucht kein Spatial).

### P1 — Oracle: der SRID-Verlust wird gemeldet (A1, M6)

**Modul:** `:adapters:driven:driver-oracle` — `OracleSchemaReader`
(`geometryMetadata`, Spaltenaufbau), `OracleColumnConstraintHelper` (`W120`).

**Was P1 baut.**
1. **`R370` in zwei Fällen, mit zwei Texten.** Die Metadatensicht ist lesbar,
   und für eine Geometriespalte fehlt die Zeile:
   - **Die Zeile kann es nicht geben** — Tabellen- oder Spaltenname ist nicht
     gleich seiner Großschreibung (quotiert klein- oder gemischtgeschrieben).
     Grund: Oracle kann zu dieser Tabelle keine Metadatenzeile führen. Ausweg:
     den SRID in der Schemadatei deklarieren oder die Tabelle unquotiert
     (großgeschrieben) anlegen und ihre Zeile registrieren.
   - **Die Zeile fehlt** — beide Namen sind großgeschrieben, aber keine Zeile
     ist registriert. Grund: für diese Spalte ist kein Bezugssystem
     deklariert. Ausweg: die Zeile in `USER_SDO_GEOM_METADATA` registrieren
     oder den SRID in der Schemadatei deklarieren.

   **Warum auch der zweite Fall (im Paket entschieden, ADR 0058 lässt es
   offen):** die Folge am Ziel ist dieselbe (SQL Server wählt planares
   `geometry`, ein räumlicher Index entfällt mit `E057`), der Reverse ist die
   einzige Stelle, an der der Anwender das vor dem Bau des Ziels erfährt, und
   für diesen Fall ist die Zeile der richtige, mögliche Ausweg. Der Reader
   unterscheidet beide Fälle deterministisch am Namen; kein Text rät.
   Severity `WARNING` (ADR 0058, Entscheidung 1).
2. **`R365` ist `WARNING` und steht nur an Tabellen mit Geometriespalte.**
   An einer Tabelle ohne Geometriespalte geht kein SRID verloren; P1 fragt die
   Sicht dort nicht mehr ab (spart eine Abfrage je Tabelle) und meldet nichts.
   Im `R365`-Fall entsteht kein zusätzliches `R370`.
3. **`W120` sagt je Fall das Richtige.** Text und Hinweis hängen daran, ob
   der Tabellenname seiner Großschreibung gleicht: dann ist die Zeile von Hand
   der richtige Ausweg; sonst kann Oracle die Zeile nicht führen, und der
   Ausweg ist ein großgeschriebener Tabellenname in der Schemadatei. Der
   ausgeschlossene Ausweg erscheint nie für eine quotiert kleingeschriebene
   Tabelle.

**Nicht:** kein Subtyp (die Sicht führt keinen), kein toleranter Abgleich,
kein Block.

**DoD** (die Bestätigung aus ADR 0058, vollständig):
1. Ein Test liest eine quotiert kleingeschriebene Tabelle mit
   Geometriespalte bei lesbarer Sicht: genau ein `R370`, `WARNING`; die Spalte
   ist `geometry` ohne SRID und bleibt im Schema; die Note nennt Grund und
   Ausweg und empfiehlt nicht, die Zeile von Hand einzufügen.
2. Ein Test liest eine großgeschriebene Tabelle ohne Zeile: genau ein `R370`
   mit dem zweiten Text.
3. Die bestehende Zusicherung auf `R365` pinnt `WARNING`, an einer Tabelle mit
   Geometriespalte.
4. **Gegenproben:** großgeschriebene Tabelle mit registrierter Zeile → SRID
   gelesen, weder `R370` noch `R365` (die Pinnung in
   `OracleSpatialIntegrationTest`, Test ab Zeile 340, bleibt grün); der
   `R365`-Fall → kein `R370`; eine Tabelle ohne Geometriespalte → keine
   `WARNING` zum SRID; die Aufrufstelle im Datenpfad (`OracleDataWriter`)
   verhält sich unverändert.
5. `W120`: ein Test je Fall (großgeschrieben: Zeile von Hand; sonst: Name in
   der Schemadatei), und keiner empfiehlt für eine kleingeschriebene Tabelle
   die Zeile von Hand.
6. **Live:** gegen `TestImages.ORACLE_FULL` entsteht für eine quotiert
   kleingeschriebene Tabelle `R370`, und `schema reverse` endet mit Exit `0`.
7. **Kein Block:** kein Pfad in `schema generate`, `schema migrate` oder
   `data transfer` wertet `R370` oder `R365` aus; die Zusicherungen zu `E057`
   (`MssqlDdlGeneratorIndexTest`) bleiben.
8. **Doku:** beide Spec-Stellen tragen Regel und Begründung, ohne Verweis auf
   den ADR: `spec/type-mapping.md` 7.2, Zeile `SDO_GEOMETRY`, und
   `spec/ddl-generation-rules.md` 16.10, Reverse-Regel (Zeile 2616).
   Anwenderhandbuch: `R370` neben `R365` (Zeile 3101); geht die Tabelle über
   Routinen hinaus, zieht die Überschrift mit (vorher prüfen, wer auf ihren
   Anker verlinkt). CHANGELOG: „Added" `R370`, „Changed" `R365` von `INFO` auf
   `WARNING` (wer `INFO` filtert, sieht ihn jetzt).
9. **Sabotage:** `R370` oder `R365` zurück auf `INFO`; `R370` auch für die
   großgeschriebene Tabelle mit Zeile; `R370` zusätzlich im `R365`-Fall; der
   `W120`-Hinweis wieder bedingungslos — je ein Test rot.

**Abnahme:** `:test:integration-oracle` (`ORACLE_FULL`, Nulllinie gemessen;
eigenes Gate unter `-PintegrationTests`).

### P3 — die Degradierung ohne `search_path` nennt Grund und Ausweg (A4)

**Modul:** `:adapters:driven:driver-postgresql` —
`PostgresTableMetadataQueries.listGeometryColumns` und der Aufbau der Spalte.

**Was P3 baut:** `R405` genau dann, wenn eine Tabelle eine PostGIS-Spalte hat,
`geometry_columns` aber nicht erreichbar ist. Grund: die Sicht liegt nicht im
`search_path`, Subtyp und SRID bleiben ungelesen. Ausweg: das PostGIS-Schema
in den `search_path` aufnehmen. `R401` bleibt, wie es ist (`INFO`, jede
Spalte). Severity `WARNING`, denn ein SRID geht verloren (ADR 0058,
Entscheidung 1).

**Das Matrix-Bein.** Ein eigenes Bein im PostgreSQL-Dienst aus Plan 1: eine
zweite Datenbank mit PostGIS im Schema `postgis` **ohne** `search_path` und
eine zweite Verbindung — nur in der Server-Konfiguration, die der Lauf selbst
schreibt (`out/compare-matrix/server.d-migrate.yaml`), nicht in der geteilten
`.d-migrate.yaml`, die auch der Scope-Smoke liest. Ein Reverse je Lauf, ohne
Zellen, mit einem Seed aus einer `postgis.geometry(Point,4326)`-Spalte;
Schlüssel `REPORT_CODES_POSTGRESQL_NOSEARCHPATH`. Die `geography`-Spalte
ergänzt Plan 3.

**DoD:**
1. `R405` im Fehlfall, keine Note bei erreichbarer Sicht; Unit-Test und ein
   Fall in `:test:integration-postgresql` (PostGIS im eigenen Schema, ohne
   `search_path`; der PostGIS-Container ist dort neu, s. Umbrella).
2. Das Matrix-Bein läuft; `R405` ist in seinem Schlüssel gepinnt.
3. **Doku:** `spec/type-mapping.md`, PostgreSQL-Abschnitt (die Regel und der
   Code); Anwenderhandbuch 3.16 („Geodaten (Spatial) modellieren und
   übertragen") mit dem Ausweg — eine Handlungsanweisung, die nur im
   Reverse-Report steht, findet nur, wer den Report liest; CHANGELOG „Added".
4. **Sabotage:** Note weg → Unit-Test, Integrationsfall und Bein rot.

### S1 — `integer`-Identity-Primärschlüssel behält den Modus (Review H2; erst messen, E2)

**Modul:** `:adapters:driven:driver-postgresql` (`PostgresTypeMapping.mapColumn`).

**Zuerst messen:** Reverse von `integer GENERATED ALWAYS AS IDENTITY PRIMARY
KEY` und `… BY DEFAULT …` und `serial PRIMARY KEY`; `schema generate` nach
PostgreSQL daraus; Vergleich zweier Reverses.

**Regel (Empfehlung E2):** bestätigt die Messung den Code-Befund, liest eine
solche Spalte mit `ALWAYS` als `integer` + `generation: identity` (Modus
`always`), wie der Zweig für Nicht-Schlüsselspalten; `BY DEFAULT` und `serial`
behalten den `identifier`-Vertrag. Entscheidet der Eigner für eine Note,
bekommt sie einen freien PostgreSQL-Code (`R406`).

**DoD:** Messung im Plan; PostgreSQL → PostgreSQL erhält `ALWAYS`; der
Fingerabdruck-Kanonisierer ist geprüft (`identifier` mit `autoIncrement`
bleibt unberührt); ein Test je Modus; Sabotage: der Zweig wieder ohne Modus →
rot. **Doku:** `spec/type-mapping.md`, PostgreSQL (welche Primärschlüssel
`identifier` werden); CHANGELOG „Changed" (ein Reverse derselben Datenbank
liefert eine andere Datei). **Matrix:** der S1-Eintrag verlässt die Liste; der
Reverse-Report und alle PostgreSQL-Zellen werden geprüft (die Spalte hat nun
auf MySQL und SQLite einen Modus, also `W163`).

### S2 — der SQLite-Migrate-Pfad rendert `generation: identity` (Review M4; erst messen, E3)

**Modul:** `:adapters:driven:driver-sqlite` (`SqliteDiffSqlBuilders.columnLine`
und `primaryKeyClause`).

**Zuerst messen:** `schema migrate --execute` gegen SQLite mit einer
`biginteger`-Spalte mit `generation: identity` als alleinigem
Primärschlüssel: gerendertes DDL, Exit-Code, Notes, Post-Compare — mit und
ohne Reverse-Präferenz `64`.

**Regel (Empfehlung E3):** ist der Verlust bestätigt, bringt S2
`columnLine` und `primaryKeyClause` auf Parität mit dem Generate-Pfad
(`INTEGER PRIMARY KEY AUTOINCREMENT`, keine doppelte Primärschlüssel-Klausel)
— das ist Ursache 1 des `open/`-Eintrags. Ursache 2 (Präferenz im
Post-Compare-Re-Read) misst S2 nur; bleibt danach Drift, bleibt sie dort.
Keine Note als Platzhalter.

**DoD:** Messung im Plan; `schema migrate` legt die Spalte mit
`AUTOINCREMENT` an; ein Test für `CreateTable` und Rebuild; danach der
SQLite-Migrate-Teil von P10. **Doku:** `spec/ddl-generation-rules.md` 3.7
(SQLite ALTER TABLE) prüfen; CHANGELOG „Fixed"; der `open/`-Eintrag bekommt
einen Nachtrag, welche Ursache geschlossen ist. **Matrix:** keine Änderung
erwartet (die Matrix migriert nicht). **Abnahme:** `:test:integration-sqlite`,
`make sample-db-types-smoke`.

### S3 — der PostgreSQL-Generator rendert das Array-Element (Review M5; erst messen)

**Modul:** `:adapters:driven:driver-postgresql` — `PostgresTypeMapper`
(`resolveElementType`) und `PostgresNeutralTypeCanonicalizer`
(`elementUdtName`), gemeinsam; dazu der Fingerabdruck
(`MigrationFingerprint.ALGORITHM` in `:hexagon:core` → einmal ohne
`MODULES`).

**Zuerst messen:** PostgreSQL → PostgreSQL mit `bigint[]`,
`double precision[]`, `numeric[]`, `json[]`, `date[]`: generiertes DDL,
Reverse, Vergleich, Post-Compare von `schema migrate`.

**Was S3 baut:** der Generator rendert jedes Element, das der Reverse liest,
in seinem Typ (`BIGINT[]`, `DOUBLE PRECISION[]`, `NUMERIC[]`, `JSONB[]`, …);
der Kanonisierer spiegelt den erweiterten Satz; der Fingerabdruck wird
angehoben (Bestätigung im Umbrella). Ein Element, das der Reverse als `text`
liest (N1), bleibt `TEXT[]`.

**DoD:** Messung im Plan; PostgreSQL → PostgreSQL erhält die Element-Typen;
ein Test je Element-Typ; der Kanonisierer-Test zeigt die neue Projektion; der
Algo-Guard lehnt ein altes Rollback-Artefakt laut ab. **Doku:**
`spec/neutral-model-spec.md` (Zeile `array`: `type[]` mit dem Element-Typ) und
`spec/type-mapping.md` prüfen; Anwenderhandbuch, Fehlerbehebung (die Anhebung
invalidiert Rollback-Artefakte und Overlay-Pins); CHANGELOG „Fixed" und
„Changed". **Matrix:** keine Zelle PostgreSQL → PostgreSQL; die Abnahme ist
`:test:integration-postgresql`. **Sabotage:** Element wieder `TEXT` → Test rot.

## Bau

### Nulllinie der Integrationsmodule (2026-09-18, vor dem ersten Paket)

`make integration INTEGRATION_TASKS=":test:integration-mysql:test
:test:integration-sqlite:test :test:integration-postgresql:test
:test:integration-oracle:test --continue"`: **`BUILD SUCCESSFUL` in 23 min 6 s,
165 Tasks**. Die vier `:test`-Tasks stehen ohne `SKIPPED` und ohne
`UP-TO-DATE` im Lauf — sie sind `executed`. Damit sind alle vier Zeilen des
Umbrellas gemessen, und zwar in **einem** Lauf.

**Keine Selbstüberspringung** in den vier Modulen: weder `assumeTrue`/
`Assumptions` noch `@Disabled` oder `xtest` kommen dort vor (gesucht über alle
vier Testquellbäume). Oracle fährt dabei beide Images — die schlanke Variante
für die übrigen Specs, `TestImages.ORACLE_FULL` (`23-faststart`) für
`OracleSpatialIntegrationTest`; beide liefen.

**Eine Testzahl je Modul steht nicht im Lauf** (das Integrations-Image trägt
das Repo als Kopie, die Reports bleiben im Container, und Gradle zählt in der
Konsolenausgabe nichts). Gemessen ist der ausgeführte Task, nicht die Zahl —
dieselbe Grenze wie in Plan 1.

**Der PostGIS-Container in `:test:integration-postgresql` ist weiterhin neu**
und von dieser Nulllinie nicht gedeckt; er wird vor P3 einmal leer gefahren
(Umbrella).

### Was gebaut ist, je Paket

Die Reihenfolge des Plans (P5 → P10; P8 und P9; P1; P3; S1 bis S3) ist im
**Bau** an zwei Stellen gedreht worden, beide Male aus einer Abhängigkeit, die
der Plan selbst nennt: **S2 kommt vor P10** (erst mit ihm rendert der
SQLite-Migrate-Pfad die Identity überhaupt, und nur dort lässt sich `W163`
melden), und **S3 kommt vor P8** (erst mit ihm rendert ein `json[]` als
`jsonb[]`, und vorher wäre die Aussage der Note falsch). Gebaut und committet
ist deshalb: P5 → S2 → P10 → S3 → P8 und P9 → S1 → P1 → P3.

**P8 und P9 teilen sich einen Commit.** Der Plan führt sie als einen Schritt
(„P8 und P9"), und sie ändern dieselben Stellen in
`PostgresTypeMapping.mapSpecialTypes`: die `json`-Note und die Note der
Array-Elementart entstehen in derselben Funktion. Getrennt hätte der erste
Commit einen Zwischenstand hinterlassen, in dem `mapArrayColumn` die eine Note
kennt und die andere nicht.

#### P5 — `W162` auf MySQL und SQLite

Gebaut als je ein Objekt im Treiber (`MysqlArrayDegradation`,
`SqliteArrayDegradation`), nach dem Muster von `SqliteEnumDegradation`: eine
Meldung, zwei Aufrufformen — `noteFor` für die Notizliste des Generate-Pfads,
`warnIfArray` für den Render-Kontext des Migrate-Pfads. So können die beiden
Pfade nicht auseinanderlaufen.

**Die Note hängt am Typ, nicht am Zweig.** Im Generate-Pfad entsteht sie
**vor** der Auswahl des Render-Zweigs; sonst bliebe eine berechnete
Array-Spalte still, weil der Computed-Zweig vorher zurückkehrt. Im
Migrate-Pfad steht sie an **jeder** Stelle, die eine Spaltendeklaration
schreibt: `CREATE TABLE`, `ADD COLUMN`, `MODIFY COLUMN` (Typwechsel und
Ausdruckswechsel), der Spaltentausch (dort einmal je Operation, nicht je der
fünf Anweisungen) und der SQLite-Tabellen-Neubau.

**Die Elementart steht in der Meldung** (`element type 'text'`). Sie ist genau
das, was verlorengeht — eine Meldung ohne sie sagte nur die Hälfte.

**B2, die Grenze, ist gepinnt:** ein Test fährt `array(integer)` durch
`MysqlTypeMapper.toSql` (`JSON`) und wieder zurück durch
`MysqlTypeMapping.mapColumn` und hält fest, dass `json` herauskommt — kein
Array mit verlorener Elementart, sondern gar kein Array mehr. Das ist der
**erwartete** Ausgang, kein Defekt.
## Akzeptanzkriterien

1. Der Array-Verlust ist auf MySQL und SQLite benannt, auf Generate und
   Migrate; die Kette `integer[]` → `JSON` → `json` ist als erwarteter Ausgang
   gepinnt (P5).
2. Eine Identity-Spalte mit `always` ist auf MySQL beim Generieren und
   Migrieren und auf SQLite beim Generieren benannt, auf dem SQLite-Migrate-Pfad
   nach S2 (P10).
3. Eine PostgreSQL-`json`-Spalte ist im Reverse-Report benannt, `jsonb`
   nicht (P8).
4. `numeric`/`NUMERIC` ohne Präzision, Oracles `NUMBER` ohne Angabe, ein
   unbekanntes Array-Element und ein unbekannter Feldtyp sind im Reverse-Report
   benannt (P9).
5. Der Oracle-Reverse meldet einen verlorenen SRID mit `R370` (`WARNING`) und
   einem zum Fall passenden Ausweg; `R365` ist `WARNING` und steht nur an
   Tabellen mit Geometriespalte; `W120` empfiehlt nie den ausgeschlossenen
   Ausweg; `schema reverse` endet mit Exit `0` (P1).
6. Eine Geometriespalte ohne erreichbares `geometry_columns` ist mit Grund
   und Ausweg benannt, und der Ausweg steht im Anwenderhandbuch (P3).
7. S1, S2 und S3 sind gemessen und nach der entschiedenen Regel gebaut.
8. Jede neue Kennung steht an ihren Registrierungsorten und dort, wo ein
   Anwender sie liest; CHANGELOG nennt jede sichtbare Änderung.
9. Jeder Fix fällt nachweislich mit zurückgenommenem Fix.

## Verifikation

1. **Nulllinie:** `:test:integration-oracle`, `:test:integration-postgresql`
   und `:test:integration-mysql` sind gemessen (Umbrella);
   `:test:integration-sqlite` ist mit Plan 1 gemessen. Der PostGIS-Container in
   `:test:integration-postgresql` ist neu und wird vor P3 einmal leer
   gefahren.
2. **Je Paket:**

   | Paket | Modul | Abnahme |
   | --- | --- | --- |
   | P5 | `driver-mysql`, `driver-sqlite` | `:test:integration-mysql`, `:test:integration-sqlite`, Matrix |
   | P10 | `driver-mysql`, `driver-sqlite` | dieselben, Matrix |
   | P8 | `driver-postgresql` | `:test:integration-postgresql`, Matrix |
   | P9 | `driver-postgresql`, `driver-sqlite`, `driver-oracle` | Unit-Tests, Matrix, `:test:integration-oracle` |
   | P1 | `driver-oracle` | `:test:integration-oracle` (`ORACLE_FULL`) |
   | P3 | `driver-postgresql` | `:test:integration-postgresql` (PostGIS), Matrix-Bein |
   | S1 | `driver-postgresql` | `:test:integration-postgresql`, Matrix |
   | S2 | `driver-sqlite` | `:test:integration-sqlite`, `make sample-db-types-smoke` |
   | S3 | `driver-postgresql`, `:hexagon:core` | `:test:integration-postgresql`; einmal ohne `MODULES` |

3. **Neu-Pins** in der Reihenfolge der Pakete, je ein Commit mit den
   Schlüsseln in der Nachricht (Umbrella). P5, P10, P8, P9 und S1 ändern
   `GEN_CODES_*` bzw. `REPORT_CODES_*` und streichen ihre Einträge aus der
   Liste bekannter Befunde; am Ende dieses Plans trägt die Liste keinen
   Eintrag von Plan 2 mehr.
4. **Gates:** Umbrella, „Gates je Commit". `make docs-check` nach jedem
   Spec-, Ledger- oder Handbuch-Nachtrag.
5. **Was gemessen ist und was nicht:** B1, B3 (Code), D2, D4 (MySQL) und A1
   sind gemessen oder im Code bestätigt; M2, L2, L3, N1, D4 (SQLite), M4, H2,
   M5 und der `R365`-an-jeder-Tabelle-Befund sind nur im Code geprüft.

## Offen

- **E2** (S1) und **E3** (S2), Umbrella.
- **Bestätigung der Fingerabdruck-Anhebung** (S3), Umbrella.
- **P1, zweiter Fall** — im Paket entschieden; der Eigner kann ihn streichen,
  dann entfällt DoD 2 und der zweite Text.
