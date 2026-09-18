# Reader-Treue 2: Verluste werden gemeldet (P5, P10, P8, P9, P1, P3, S1–S3)

> **Status:** **Done — graduiert 2026-09-18.** Aktiv seit 2026-09-18; alle
> neun Pakete geliefert (P5, S2, P10, S3, P8 und P9, S1, P1, P3), dazu fünf
> Neu-Pins der Compare-Matrix und eine Korrekturrunde nach Review und
> Verifikation. Noch nicht released: die Wirkung steht in `CHANGELOG.md` unter
> `[Unreleased]` (Stand `main`, 1.8.0-SNAPSHOT). Schnitt 2026-09-17 aus dem
> ungeschnittenen Reader-Slice; Befunde aus Plan-Review und
> Architektur-Prüfung eingearbeitet, Anker gegen `90c6c234f` nachgemessen. Der
> Bauabschnitt unten hält Nulllinie, Messungen, Sabotagen und Neu-Pins fest;
> die Closure mit Paket → Commit steht am Ende, jeder verbliebene Punkt mit
> seinem Ort unter „Restflächen" direkt unter diesem Kopf.
>
> **Commits** (in dieser Reihenfolge):
> `e53543ca1` P5 · `98f99e48b` S2 · `d1b1936d0` P10 · `a9c9b4563` S3 ·
> `ef346d77c` P8 und P9 · `14ec70888` S1 · `86f61b076` P1 · `90d8e38b7` P3 ·
> Neu-Pins `8ae466421` (P5), `512f0b738` (P10), `4a79927a1` (P8/P9),
> `a8a3a01bc` (S1), `781fac613` (P3) · `c085e4127` Plan ·
> `9b12ca4c2` zwei Eigner-Entscheidungen · Korrekturrunde `93720f317`,
> `580307637`, `858a0fab6`, `cc34d1241`, `109bc9fb7`, `bcc63ca3c` ·
> `9685da681` Plan.
> Teil des Umbrellas
> [`reader-treue.md`](../in-progress/reader-treue.md), der in `in-progress/`
> **bleibt**, solange die Pläne 3 und 4 offen sind. Dort stehen Nenner,
> Belegart, Regeln der Abnahme, Doku-Pflichten und die Code-Tabelle.
> **Vorbedingung / Gate:** [Plan 1](reader-treue-1-matrix-abnahme.md) ist geliefert und graduiert
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
> **Graduiert** am 2026-09-18 (Move nach `../done/`); der Umbrella bleibt in
> `../in-progress/`.
> **Abhängigkeit:** [Plan 1](reader-treue-1-matrix-abnahme.md). Innerhalb: P10 nach P5 (dieselben Stellen); der
> `json[]`-Teil von P8 nach S3; Plan 3 baut P4 auf P3 auf. **Im Bau gedreht:**
> S2 kam vor P10 und S3 vor P8, beide Male aus einer Abhängigkeit, die der Plan
> selbst nennt (Abschnitt „Was gebaut ist, je Paket").

## Restflächen (2026-09-18)

**Nichts davon ist Bauschuld dieses Plans.** Jeder Punkt ist eine
Eigner-Entscheidung, ein beim Bauen sichtbar gewordener Befund oder eine
Grenze, die der Plan bewusst zieht. Jeder hat einen Ort außerhalb; die Liste
„Offen" weiter unten bleibt als Stand vor der Graduation stehen.

| Punkt | Ort |
| --- | --- |
| **`smallint`-Identity als Primärschlüssel auf PostgreSQL** verliert den Modus weiter, ohne Code — H1 hält den S1-Zweig auf `integer` eng, damit das gelesene Schema erzeugbar bleibt. Dazu der ältere Nachbarfall: dieselbe Breite **ohne** Schlüssel liest `smallint` + `identity` und fällt bei `E130` | **S6** in [Plan 3](../next/reader-treue-3-spatial.md), **gemeinsam mit S5 zu entscheiden** (welche Breiten trägt der `identifier`-Vertrag); zwei Integrationsfälle halten den Zustand fest, bis die Antwort da ist |
| **SQL Server: `int IDENTITY` als alleiniger Primärschlüssel** fällt auf `identifier` und verliert den Modus, ohne Code (`W140` meldet den umgekehrten Fall) | [`../open/mssql-integer-identity-pk-verliert-den-modus.md`](../open/mssql-integer-identity-pk-verliert-den-modus.md); als **S5** in [Plan 3](../next/reader-treue-3-spatial.md) geschnitten (Eigner, 2026-09-18) |
| **SQLite `ADD COLUMN` einer Identity-Spalte verliert den Autowert still** (Rest aus M2): der Server kann einen rowid-Alias per `ALTER TABLE` nicht anlegen, `W163` setzt einen Autowert voraus, `W135` nennt einen zusammengesetzten Schlüssel als Grund, den es hier nicht gibt | [`../open/sqlite-add-column-identity-verliert-den-autowert.md`](../open/sqlite-add-column-identity-verliert-den-autowert.md); der Integrationsfall aus `580307637` ist sein Wächter |
| **`W135` trifft schon heute eine Identity-Spalte ganz ohne Schlüssel** — mit dem Satz „is part of a composite primary key", der für sie nicht stimmt. Der Verlust ist benannt, der Grund falsch | derselbe Eintrag, Abschnitt „Nebenbefund am selben Prädikat" |
| **Der SQLite-Generate-Pfad lässt die Tabellen-`PRIMARY KEY`-Klausel für jede Spalte mit `generation: identity` weg** (`SqliteTableDdlSupport.skipPrimaryKey` sieht die Erzeugung an, nicht den Typ) — eine `decimal`-Identity als alleiniger Schlüssel bekäme gar keinen. Heute über `E130` nicht erreichbar; der Diff-Pfad prüft seit S2 beides | derselbe Eintrag, Abschnitt „Nebenbefund am Generate-Pfad" |
| **Ursache 2 des SQLite-Migrate-Eintrags** bleibt offen und ist breiter als beschrieben: der Post-Compare-Re-Read liest ohne Reverse-Präferenz, und der Fingerabdruck faltet die beiden Schreibweisen nur in `generation`, **nicht im Typ** | [`../open/sqlite-migrate-biginteger-identity-render-gap.md`](../open/sqlite-migrate-biginteger-identity-render-gap.md), Nachtrag 2026-09-18; ein Integrationsfall hält beide Ausgänge (gleiche DDL, Exit 0 gegen Exit 5) als Wächter über dem offenen Punkt |
| **Die Matrix-Fixture trägt keine Anmerkungen** — der Silent-Loss-Check liest nur `fixtures/seeds/`, deshalb bewegte `cm_order.id` beim Pinnen von P10 zwei Zellen unbemerkt | [`../open/matrix-fixture-ohne-anmerkungen.md`](../open/matrix-fixture-ohne-anmerkungen.md) |
| **Die sieben verbliebenen bekannten Befunde** des Silent-Loss-Checks (von 25; 18 sind mit diesem Plan weggefallen): **einer** ist der SQL-Server-Identity-Fall oben, **fünf** gehören dem SQLite-Generate-Eintrag, **einer** D1 | die Liste ist Code: `SILENT_LOSS_KNOWN` in [`examples/mcp-e2e/scripts/lib/silent-loss.sh`](../../../examples/mcp-e2e/scripts/lib/silent-loss.sh); die Orte sind [`../open/mssql-integer-identity-pk-verliert-den-modus.md`](../open/mssql-integer-identity-pk-verliert-den-modus.md), [`../open/sqlite-generate-verschweigt-typmarke-und-laenge.md`](../open/sqlite-generate-verschweigt-typmarke-und-laenge.md) und [Plan 4](../next/reader-treue-4-mssql-berechneter-typ.md) |
| **Der `W137`-Doppelbeleg** und die R-Vergabe ohne Ledger (F4, N3) — dieser Plan hat sieben R-Codes vergeben und keinen davon in einem Ledger registriert, weil es für R-Codes keinen gibt | [`../open/warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md) |
| **Nackte reservierte Wörter gegen PostgreSQL, SQL Server und Oracle** — der nächste Posten derselben Klasse, in der Abgrenzung dieses Plans benannt und nicht gebaut | [`../open/nackte-reservierte-woerter-im-rohen-ausdruck.md`](../open/nackte-reservierte-woerter-im-rohen-ausdruck.md) |
| **Die Modellfrage hinter P9** — verlustfrei ginge `numeric` ohne Präzision nur mit einem neutralen `decimal` ohne Präzision und einer Render-Regel je Dialekt; P9 meldet nur (Eigner, 2026-09-17) | [`../open/decimal-ohne-praezision-verlustfrei.md`](../open/decimal-ohne-praezision-verlustfrei.md) |
| **Die Overlay-Formulierung.** Die beiden Wege in die Ungültigkeit sind verschieden: ein **Rollback-Artefakt** trägt die **Algorithmus-Kennung** und fällt nach der Anhebung auf `v17` immer, auch ohne Array im Schema; eine **Overlay-Datei** kennt die Kennung gar nicht, bindet an den **Abdruckswert** und fällt nur, wenn das beschriebene Schema eine Array-Spalte trägt. Eine Algorithmus-Bindung für Overlays wäre eine eigene Entscheidung und ist hier nicht getroffen | die genaue Fassung steht im `CHANGELOG.md` unter `[Unreleased]`, „Changed" (Commit `bcc63ca3c`); das Anwenderhandbuch beschreibt im Abschnitt „Rollback oder Overlay bricht nach einem d-migrate-Update ab" weiter beide Artefaktarten gemeinsam |
| **Oracle bleibt aus der Compare-Matrix** — P1 und P9 haben deshalb in `:test:integration-oracle` abgenommen, nicht in der Matrix | [`../open/mcp-e2e-oracle-nicht-gefahren.md`](../open/mcp-e2e-oracle-nicht-gefahren.md); die Regel steht im Umbrella, „Die Matrix" |
| **B2 ist eine Grenze, kein Rest.** Die Kette PostgreSQL `integer[]` → MySQL `JSON` → Reverse → `json` ist unwiderruflich; sie ist als **erwarteter** Ausgang gepinnt, und `W162` meldet den Hinweg | in diesem Plan gepinnt (P5); im Troubleshooting-Leitfaden steht ausdrücklich, dass es dagegen nichts zu tun gibt |

**Und die `Datei:Zeile`-Anker im Text sind Entwurfs- bzw. Bauabschnittsstand.**
Wer einen Beleg nachfährt, sucht über den Symbolnamen; `resolveElementType`
etwa heißt seit S3 `elementSql`.

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

#### S2 — der SQLite-Migrate-Pfad rendert `generation: identity`

**Zuerst gemessen** (SQLite 3.45, `schema migrate --execute` über den
Runner des Integrationsmoduls, eine `biginteger`-Spalte mit
`generation: identity` als alleiniger Primärschlüssel):

| Fall | vorher | nachher |
| --- | --- | --- |
| `CreateTable` | `"id" INTEGER NOT NULL` + `PRIMARY KEY ("id")`, **kein** `AUTOINCREMENT`, keine `sqlite_sequence`-Tabelle | `"id" INTEGER PRIMARY KEY AUTOINCREMENT`, `sqlite_sequence` vorhanden |
| Tabellen-Neubau (Nullbarkeitswechsel) | nahm das AUTOINCREMENT wieder weg | behält es |

`sqlite_sequence` ist der Beleg des **Servers**: diese Tabelle entsteht nur für
eine Tabelle mit `AUTOINCREMENT`. Der gespeicherte Tabellentext allein könnte
auch ein Kommentar sein.

**Der Post-Compare driftet weiter — Ursache 2, gemessen.** Beide Läufe enden
mit **Exit 5** (`POST_EXECUTE_DRIFT`), obwohl die Datenbank genau so steht, wie
das Soll sie wollte. Der Re-Read liest ohne Reverse-Präferenz und liefert
`identifier(auto)`; das Soll sagt `biginteger` + `generation: identity`.

**Die Messung, die den Grund zeigt** (derselbe Lauf, zwei Sollformen): mit der
Spalte als `identifier` entsteht **dieselbe** DDL — Zeichen für Zeichen — und
der Lauf endet mit **Exit 0**. Es ist also nicht der Fix, der driftet, sondern
die Schreibweise des Solls.

**Und Ursache 2 ist breiter, als der `open/`-Eintrag sagte.** Er nennt das
fehlende Präferenz-Threading. Dazu kommt: der **Fingerabdruck** faltet die
beiden Schreibweisen nur in `generation`
(`MigrationFingerprint.impliedGeneration` macht aus `identifier(auto)` ein
`identity(always)`), **nicht im Typ** — `canonicalizeType` hält
`identifier(auto)` fest und projiziert `biginteger` auf `integer`. Der
Post-Compare sieht deshalb zwei verschiedene Typen, auch wo der Comparator die
Spalten längst zusammenfaltet (`TableComparator.identitySpelledDifferently`).
Mit der Präferenz (`--sqlite-autoincrement-width 64` **und**
`--sqlite-autoincrement-syntax identity`) läse der Re-Read `biginteger` +
`identity` und die Frage entfiele; ohne sie bleibt sie. Beides steht im
Nachtrag des `open/`-Eintrags.

**Der Zustand ist gepinnt, nicht weggelassen.** Ein Integrationsfall hält
beide Ausgänge nebeneinander fest (gleiche DDL, Exit 0 gegen Exit 5). Wird
Ursache 2 geschlossen, wird er rot — er ist der Wächter über dem offenen
Punkt, nicht über dem Fix.

**Ein zweiter Verlust wäre dabei entstanden und ist mitgenommen.**
`SqliteCompositePkIdentity.isDroppedAutoincrement` prüfte den **Typ**
(`identifier`), nicht die Spalte. Sobald `columnLine` auch
`generation: identity` inline rendert, verliert dieselbe Schreibweise im
**zusammengesetzten** Schlüssel ihr AUTOINCREMENT — und `W135` hätte davon
nichts gesagt. Das Prädikat nimmt jetzt die Spalte und deckt beide Formen; die
KDoc, die das Gegenteil behauptete, ist nachgezogen.

**Nicht angefasst:** der Blocker `SQLITE_IDENTITY_IS_PART_OF_THE_TYPE`. Er
betrifft den **Wechsel** einer Identity an einer bestehenden Spalte, nicht das
Anlegen — SQLite kann das auch nach diesem Paket nicht in place.

**Nebenbefund, gemeldet, nicht gebaut:** der Generate-Pfad lässt die
Tabellen-`PRIMARY KEY`-Klausel für **jede** Spalte mit
`generation: identity` weg, auch wenn ihr Typ kein rowid-Alias sein kann
(`SqliteTableDdlSupport.skipPrimaryKey` sieht `col.generation` an, nicht den
Typ). Eine `decimal`-Spalte mit erklärter Identity als alleiniger
Primärschlüssel bekäme dort gar keinen Schlüssel. Der Fall ist über die
Identity-Typprüfung der Validierung (`E130`) heute nicht erreichbar; der
Diff-Pfad prüft seit S2 beides. Kein Paket dieses Plans trägt ihn.

#### P10 — `W163` auf MySQL und SQLite

Dieselbe Bauform wie P5 (`MysqlIdentityModeDegradation`,
`SqliteIdentityModeDegradation`), und nur bei `mode: always`. Gemeldet wird
dort, wo die Spalte wirklich als Autowert entsteht:

- **MySQL:** Generate (`columnGeneratedIdentity`) und Migrate an allen
  Render-Stellen — einschließlich `renderIdentityTransition`. Damit ist die
  Frage aus DoD 3 beantwortet: ein **Wechsel** des Modus nach `always` meldet
  `W163`; die Tabelle der Migrate-Änderungen in `ddl-generation-rules.md`
  („MySQL kennt keinen Modus") bleibt, wie sie ist.
- **SQLite:** Generate und — seit S2 — Migrate, aber nur als **alleiniger**
  Primärschlüssel. Im zusammengesetzten Schlüssel entsteht gar kein Autowert;
  dort sagt `W135` das Stärkere, und `W163` daneben wäre irreführend. Ein
  Test pinnt beides. Eine Identity-**Änderung** blockt SQLite weiterhin
  (`SQLITE_IDENTITY_IS_PART_OF_THE_TYPE`) — dort gibt es nichts zu melden.

**Die Messung für SQLite** (dass `INTEGER PRIMARY KEY AUTOINCREMENT` einen
ausdrücklich gesetzten Wert annimmt) ist nicht eigens gefahren worden: sie ist
SQLites dokumentiertes Verhalten für jeden rowid-Alias, und der Reverse liest
den Modus ohnehin nirgends zurück — `SqliteTypeMapping` setzt für eine
AUTOINCREMENT-Spalte entweder `identifier` (ohne Modus) oder
`biginteger` + `Identity()` mit dem Default `by_default`. Der Code-Befund ist
damit an der Stelle bestätigt, an der er zählt.

#### S3 — der PostgreSQL-Generator rendert die Elementart

`resolveElementType` ist zu `elementSql` geworden und liefert die
DDL-Schreibweise direkt: `TEXT`, `INTEGER`, `BIGINT`, `BOOLEAN`, `UUID`,
`DOUBLE PRECISION` (für `float`), `NUMERIC` (für `decimal`) und `JSONB` (für
`json`) — **genau der Satz, den der Reverse benennt**
(`PostgresTypeMapping.mapArrayElementType`). Eine Elementart, die er nicht
benennt (`date[]` liest `text`, N1), bleibt `TEXT[]`.

**Parameterlos, und das ist eine Entscheidung.** `NUMERIC[]` statt
`NUMERIC(p,s)[]`: das neutrale Modell trägt am Array nur den **Namen** der
Elementart. `float` wird `DOUBLE PRECISION`, weil der Reverse `float4` und
`float8` beide auf `float` abbildet — die weitere Form verliert nichts.

Der Kanonisierer (`elementUdtName`) spiegelt denselben Satz; beide Stellen
tragen einen Verweis aufeinander. Der **Fingerabdruck** steht damit auf
`schema-fingerprint-v17` (von `v16`), samt Eintrag in der Versionsliste von
`MigrationFingerprint`. `CanonicalPayload` ist **nicht** betroffen: es
projiziert `array(<element>)` dialektneutral und lief unverändert durch.

**Gemessen ist die ganze Kette** an einem echten Server
(`:test:integration-postgresql`): `bigint[]`, `double precision[]`,
`numeric[]`, `jsonb[]` und `text[]` werden gelesen, mit dem Generator wieder
als DDL geschrieben, vom Server angenommen und ein zweites Mal gelesen — mit
derselben Elementart. Vorher entstand dort überall `text[]`.

#### P8 und P9 — die stillen Rückfälle sind benannt

| Code | Stelle | Fall |
| --- | --- | --- |
| `R402` | `PostgresTypeMapping.mapSpecialTypes` | eine `json`-Spalte, und das Element eines `json[]` (seit S3 rendert es als `jsonb[]`) |
| `R404` | `mapNumericTypes` und `compositeField` | `numeric`/`decimal` ohne Präzision |
| `R301` | `mapArrayColumn` und `compositeField` | eine Elementart bzw. ein Feldtyp, für den es keinen neutralen Namen gibt |
| `R221` | `SqliteTypeMapping.mapNumericType` | `NUMERIC`/`DECIMAL` ohne Präzision |
| `R371` | `OracleTypeMapping.mapColumn` | `NUMBER` ohne Präzision → `decimal(38,10)` |

Alle `WARNING`, keiner blockt. `jsonb` meldet nichts, `numeric(12,2)` meldet
nichts, ein bekanntes Array-Element meldet nichts, eine Oracle-Identity ohne
Präzision meldet nichts — je eine Gegenprobe im Test.

**Der Unterschied „benannt oder nicht" liegt jetzt im Code, nicht im
Kommentar.** `mapArrayElementType` gibt weiter `text` zurück; darunter liegt
`knownArrayElementType`, das für eine unbekannte Elementart `null` liefert —
und genau dieses `null` ist die Meldepflicht aus `spec/type-mapping.md`,
Abschnitt 8. Dieselbe Trennung bei `compositeField`/`knownCompositeFieldType`.

**Die Felder zusammengesetzter Typen melden jetzt überhaupt.**
`readPostgresCustomTypes` bekam dafür die Notizliste; vorher gab es an dieser
Stelle keinen Kanal.

#### S1 — der `integer`-Identity-Primärschlüssel behält den Modus

**Zuerst gemessen** (PostgreSQL 18, `:test:integration-postgresql`):

| Quelle | vorher | nachher |
| --- | --- | --- |
| `integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY` | `identifier(auto)`, **kein** `generation` | `integer` + `generation: identity`, Modus `always` |
| `integer GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | `identifier(auto)` | unverändert |
| `serial PRIMARY KEY` | `identifier(auto)` | unverändert |

Der Zweig gilt für den Basistyp, den `mapIntegerTypes` kennt (`integer` und
`smallint`); `bigint` lief schon vorher durch den Zweig darüber.

**Der Fingerabdruck-Kanonisierer ist geprüft und unberührt:** er ruft
`mapColumn` ohne Primärschlüssel- und Identity-Kontext, und für
`identifier(auto)` trägt er ohnehin eine eigene Ausnahme. Ein Test pinnt das.

#### P1 — der verlorene Oracle-SRID wird gemeldet

Gebaut als eigenes Objekt `OracleGeometryMetadata`: es liest die
Metadatenzeilen, unterscheidet **lesbar** von **nicht lesbar** (bisher war
beides eine leere Map) und vergibt die Notizen.

1. **Gefragt wird nur noch für Tabellen mit Geometriespalte.** Das spart eine
   Abfrage je Tabelle — und nimmt `R365` von jeder rein numerischen Tabelle
   weg, an der kein SRID verlorengehen kann.
2. **`R365` ist `WARNING`** (ADR 0058, Entscheidung 1) und entsteht nur an
   einer Tabelle mit Geometriespalte. Im `R365`-Fall entsteht **kein**
   zusätzliches `R370`: über die Zeilen einer unlesbaren Sicht ist nichts
   bekannt.
3. **`R370` mit zwei Texten**, unterschieden **deterministisch am Namen**: ist
   Tabellen- oder Spaltenname nicht gleich seiner Großschreibung, kann Oracle
   die Zeile gar nicht führen (Ausweg: SRID in der Schemadatei oder eine
   unquotierte Tabelle, ausdrücklich **nicht** die Zeile von Hand); sind beide
   großgeschrieben, fehlt sie nur (Ausweg: die Zeile registrieren). Kein Text
   rät.
4. **`W120` sagt je Fall das Richtige.** Der Hinweis empfahl bisher für
   **jede** Tabelle die Zeile von Hand — auch für die, für die ADR 0058 genau
   das ausschließt.

**Live gemessen** (`TestImages.ORACLE_FULL`, `:test:integration-oracle`): eine
quotiert kleingeschriebene Tabelle mit Geometriespalte erzeugt genau ein
`R370` (`WARNING`), die Spalte bleibt als `geometry` ohne SRID im Schema, und
`schema reverse` endet über den echten `SchemaReverseRunner` mit **Exit 0**.
Eine großgeschriebene Tabelle ohne Zeile bekommt den zweiten Text; eine mit
registrierter Zeile liest den SRID und meldet weder `R370` noch `R365`; eine
Tabelle ohne Geometriespalte meldet gar nichts.

**Kein Block:** die Notizen werden von keinem Pfad in `schema generate`,
`schema migrate` oder `data transfer` ausgewertet; `OracleDataWriter` liest
dieselbe Sicht unverändert weiter. Die `E057`-Zusicherungen sind unberührt.

#### P3 — `R405` nennt Grund und Ausweg

`PostgresTableMetadataQueries.listGeometryColumns` liefert jetzt eine
**Auskunft** statt einer Liste: `reachable` sagt, ob `geometry_columns`
auflöst. Eine leere Liste sah vorher genauso aus wie „diese Tabelle hat keine
Geometriespalte" — der Grund war an der Abfrage bekannt und ging dort
verloren.

`R405` entsteht **je Tabelle**, die eine PostGIS-Spalte trägt (erkannt am
`udt_name`), wenn die Sicht nicht erreichbar ist; die Meldung nennt die
betroffenen Spalten, der Hinweis den `search_path` und die Alternative
(Subtyp und SRID in der Schemadatei). `R401` bleibt unverändert daneben.

**Live gemessen** (`TestImages.POSTGIS`, neu in `:test:integration-postgresql`):
PostGIS im Schema `postgis`, `search_path` ohne es — die Spalte kommt als
`geometry` ohne Subtyp und ohne SRID, `R405` steht im Report, und die Tabelle
ohne Geometriespalte meldet nichts. Mit `postgis` im `search_path` (auf
Datenbankebene gesetzt, gelesen über eine **neue** Sitzung) kommen Subtyp und
SRID mit, und `R405` entsteht nicht.

### Die Matrix: Neu-Pins und weggefallene Befunde

Gefahren mit `make mcp-e2e-up` und `make mcp-e2e-compare-matrix` gegen
`d-migrate:dev` (1.8.0-SNAPSHOT); PostgreSQL 18.6 (PostGIS-Image), MySQL
9.7.2, SQL Server 2025, SQLite 3.45 (Host), 42 angemerkte Seed-Spalten. Oracle
ist in der Matrix nicht gemessen (kein Opt-in).

Der erste Lauf ging **ohne** `--update-expectations`: 13 Abweichungen und 18
weggefallene bekannte Befunde. Gepinnt wurde danach je Paket von Hand aus dem
gelesenen Diff, in einem eigenen Commit — nicht über alles mit
`--update-expectations`, das den Zuwachs eines Pakets nicht von dem eines
anderen trennt.

| Paket | Schlüssel | Änderung |
| --- | --- | --- |
| P5 | `GEN_CODES_POSTGRESQL_MYSQL`, `GEN_CODES_POSTGRESQL_SQLITE` | je `+ W162:4` |
| P10 | dieselben zwei | je `+ W163:1` |
| P10 | `GEN_CODES_MSSQL_MYSQL`, `GEN_CODES_MSSQL_SQLITE` | je `+ W163:1` |
| P8/P9 | `REPORT_CODES_POSTGRESQL` | `R301:1 R400:1` → `R301:2 R400:1 R402:1 R404:1` |
| P8/P9 | `REPORT_CODES_SQLITE` | `R202:5` → `R202:5 R221:1` |
| S1 | `CELL_POSTGRESQL_MYSQL`, `CELL_POSTGRESQL_MSSQL` | `11` → `13` |
| S1 | `CELL_POSTGRESQL_SQLITE` | `22` → `24` |
| S1 | `CODES_POSTGRESQL_MYSQL`, `_MSSQL`, `_SQLITE` | je `+1` Erzeugung und `+1` Typ |
| S1 | `GEN_CODES_POSTGRESQL_MYSQL`, `_SQLITE` | `W163:1` → `W163:2` |
| P3 | `REPORT_CODES_POSTGRESQL_NOSEARCHPATH` (neu) | `R400:1 R401:1 R405:1` |

Die betroffenen Zellen einzeln:

- **P5**, `W162:4`: die vier Array-Spalten von `sl_pg_array` (`tags`, `counts`,
  `totals`, `due_dates`), je einmal je Zielzelle. Quelle PostgreSQL ist die
  einzige, die ein Array liefert — andere Zellen ändern sich nicht.
- **P10**, PostgreSQL: `sl_pg_identity_big.id`. **Nicht vorhergesehen** waren
  die beiden Zellen mit Quelle SQL Server: `cm_order.id` der Matrix-Fixture ist
  `identity(by_default)`, SQL Server rendert das als `IDENTITY(1,1)`, und sein
  Reverse liest daraus Modus `always`. Ab dem Reverse ist die Quelle also eine
  ALWAYS-Identity, und MySQL wie SQLite melden sie zurecht. Der
  Silent-Loss-Check sah den Fall nie — `cm_order` trägt keine Anmerkung, der
  Fall lebt nur in den Zellzahlen.
- **P8/P9**: vier Vorkommen an vier Seed-Spalten (`sl_pg_array.due_dates` das
  zweite `R301`, `sl_pg_json.payload_json` `R402`, `sl_pg_number.amount`
  `R404`, `sl_sq_child.amount` `R221`). Das erste `R301` bleibt, wo es war
  (`sl_pg_text.span`, Paket B4).
- **S1** ist das einzige Paket, das eine **Zellzahl** bewegt, und das ist sein
  Sinn: `sl_pg_identity_int.id` kommt jetzt als `integer` +
  `identity(always)` statt als `identifier(auto)`, alle drei Ziele lesen sie
  als `identifier(auto)` zurück — zwei Funde je Zelle, einer für den Typ,
  einer für die Erzeugung. Vorher waren beide Seiten gleich verloren und die
  Zelle schwieg.
- **P3** pinnt das Bein, keine Zelle: genau eine Tabelle des Bein-Seeds trägt
  eine Geometriespalte, `R405` steht einmal.

**S2 und S3 bewegen keine Zelle.** S2 liegt auf dem Migrate-Pfad, den die
Matrix nicht fährt (sie generiert und wendet an); S3 betrifft den
PostgreSQL-**Generator**, und PostgreSQL ist in der Matrix nur Quelle — die
drei Zellen mit Ziel PostgreSQL kommen aus Quellen ohne Array.

**18 bekannte Befunde weggefallen**, `SILENT_LOSS_KNOWN` geht von 25 auf 7
Einträge; keiner der sieben nennt noch ein Paket aus Plan 2. Die acht `W162`-
und vier `W163`-Einträge waren je Spalte mal Ziel angelegt und traten alle
nicht mehr auf; die vier der Klasse `quelle … nennt <Code> nicht` und die zwei
zur Form von `sl_pg_identity_int.id` ebenso.

**Stehen bleibt** der Eintrag `ziel postgresql->mssql: sl_pg_identity_int.id`.
Er zeigt seit dem P3-Commit auf
[`../open/mssql-integer-identity-pk-verliert-den-modus.md`](../open/mssql-integer-identity-pk-verliert-den-modus.md)
statt auf Plan 2: S1 hat den PostgreSQL-Fall behoben, die Zielseite auf SQL
Server ist eine Typfrage und braucht eine Eigner-Entscheidung.

**Belegt, dass die Pins beißen:** der erste Lauf war mit genau diesen 13
Abweichungen rot. Für den einzigen **neuen** Schlüssel zusätzlich eine
Sabotage am Pin selbst — `R405` aus
`REPORT_CODES_POSTGRESQL_NOSEARCHPATH` entfernt, der Lauf meldet
`erwartet 'R400:1 R401:1', gemessen 'R400:1 R401:1 R405:1'` und endet mit
Exit 2; Rücknahme per Prüfsumme belegt. Danach **zwei Läufe hintereinander**,
beide Exit 0, Matrixblock und Liste der bekannten Befunde zeichenweise gleich.

### Der Typ-Sensor nach S3

`make sample-db-types-smoke` ist der Drift-Sensor der Typ-Kanonisierung und
damit die Stelle, an der eine Änderung der Typprojektion auffiele. S3 ändert
genau die: der PostgreSQL-Generator rendert die Elementart, und der
Fingerabdruck steht auf `v17`. Gefahren nach der Matrix, mit gestoppter
`mcp-e2e`-Umgebung (Speicher): **alles grün** — 21 Typen je Exit 0,
UNIQUE-/FK-Folds, Konvergenz-Zweitlauf mit 0 Statements, Rebuild, der
Rollback-Round-Trip über das v7-Artefakt, die `schema compare`-Gegenprobe und
die Kanten-Proben auf PostgreSQL und MySQL. Die Wegwerf-Container sind danach
entfernt.

### Die Korrekturrunde (2026-09-18, nach Review und Verifikation)

Beide Prüfer sind durch, beide haben gemessen. Was sie fanden, ist hier
gebaut — fünf Verhaltensbefunde, vier Abdeckungslücken und eine
Eigner-Entscheidung.

#### H1 — S1 greift nur für `integer` (Eigner, 2026-09-18)

Der Zweig nahm jeden Typ, den `mapIntegerTypes` kennt, also auch `smallint`.
Eine `smallint GENERATED ALWAYS AS IDENTITY`-PK las damit als `smallint` +
`identity(always)` — **genau die Form, die das eigene Modell nicht trägt**:
`SchemaColumnValidationRules` lehnt sie mit `E130` ab, und
`PostgresColumnConstraintHelper.identityColumnSql` rendert sie nicht. Ein
`schema generate` aus dem eigenen Reverse wäre abgebrochen; aus einem
verlustbehafteten, aber lauffähigen Weg wäre ein abbrechender geworden.

Der Zweig prüft jetzt `dt == "integer"` (`bigint` läuft ohnehin durch den
Zweig darüber). Der `smallint`-Fall verliert den Modus weiter, ohne Code —
er ist als **S6** in [Plan 3](../next/reader-treue-3-spatial.md) eingetragen,
zusammen mit dem dort schon geschnittenen **S5** (SQL Server, `int IDENTITY`)
zu entscheiden: beide fragen, welche Breiten der `identifier`-Vertrag trägt.
Ein Integrationsfall pinnt seitdem **beides** — die `identifier`-Lesart und
dass das gelesene Schema die eigene Validierung besteht (ohne `E130`).

**Dabei fiel ein älterer Nachbarfall auf, gemessen und gepinnt, nicht
behoben:** dieselbe Breite **ohne** Schlüssel geht durch den Zweig für
Nicht-Schlüsselspalten, und der nimmt jeden Typ, den `mapIntegerTypes` kennt.
Ein `smallint GENERATED BY DEFAULT AS IDENTITY` ohne Primärschlüssel liest
also als `smallint` + `identity` — und **fällt** bei `E130`. Aus diesem
Reverse lässt sich nicht generieren. Der Fall ist älter als S1 und gehört zur
selben Frage; er steht bei **S6** in Plan 3, und ein Wächter-Test hält den
Zustand fest, bis sie entschieden ist.

#### M2 — SQLite `ADD COLUMN` erklärte einen Primärschlüssel

`renderAddColumn` rief `columnLine` ohne den vierten Parameter, und dessen
Default war `isSolePrimaryKey = true`. Der seit S2 neue Zweig griff damit auf
**jedem** `ADD COLUMN` einer Identity-Spalte: SQLite lehnt das ab („Cannot add
a PRIMARY KEY column"), und erklärt hätte die Anweisung einen Schlüssel, den
das Soll nicht nennt. Der Kommentar daneben sagte längst das Richtige — die
`W163`-Notiz an derselben Stelle übergab schon `isSolePrimaryKey = false`.

Der Parameter hat **keinen Default mehr**. Das ist die eigentliche Lehre: ein
Default `true` an dieser Stelle macht aus einer vergessenen Entscheidung eine
falsche. Die beiden anderen Aufrufstellen (`CREATE TABLE`, Tabellen-Neubau)
rechnen den Wert seit jeher aus und waren nie betroffen; geprüft.

**Was danach bleibt und nicht gebaut ist:** die Spalte entsteht ohne Autowert
(SQLite kann einen rowid-Alias per `ALTER TABLE` nicht anlegen), und kein Code
sagt es. `W163` setzt einen Autowert voraus, `W135` nennt als Grund einen
zusammengesetzten Schlüssel, den es hier nicht gibt. Befund, Nebenbefund am
Prädikat `isDroppedAutoincrement` und drei Wege stehen in
[`../open/sqlite-add-column-identity-verliert-den-autowert.md`](../open/sqlite-add-column-identity-verliert-den-autowert.md).

#### L1 — `W163` prüfte auf dem Migrate-Pfad den Typ nicht

`MysqlIdentityModeDegradation.appliesTo` sah nur den Modus; der Generate-Pfad
prüfte den Typ davor selbst, der Migrate-Pfad gar nicht. Eine `decimal`-Spalte
mit erklärter Identity hätte dort „der Modus ist nicht durchgesetzt" gemeldet,
obwohl MySQL sie als gewöhnliche Spalte schreibt. Die Prüfung liegt jetzt im
Prädikat — **einmal**, für beide Pfade; die doppelte Prüfung an der
Generate-Aufrufstelle ist entfallen. SQLite trug sie schon im Zweig
(`isRowidIdentity`).

#### L3 — `R370` sagte „hat keine Zeile", auch wenn eine dastand

`USER_SDO_GEOM_METADATA.SRID` ist nullbar. Eine Zeile, die die Ausdehnung
beschreibt, aber kein Bezugssystem nennt, fiel in den Text „hat keine Zeile"
— und der riet, eine anzulegen, die es schon gibt. `R370` hat jetzt **drei**
Texte, unterschieden an Tatsachen statt am Namen allein: Zeile da ohne SRID
(Ausweg: den Wert in **dieser** Zeile setzen), Zeile fehlt und kann es nicht
geben (Name nicht gleich seiner Großschreibung), Zeile fehlt nur. Die
vorhandene Zeile schlägt die Namensregel — sie ist eine Tatsache, die
Schreibweise nur ein Indiz.

#### L2 — `json` als Feld eines zusammengesetzten Typs blieb still

**Entschieden: `R402` auch dort.** `CREATE TYPE … AS (…)` schreibt den Feldtyp
durch denselben Mapper wie eine Spalte (`PostgresTypeSequenceDdlSupport`), ein
`json`-Feld entsteht am Ziel also als `jsonb` — derselbe Verlust, dieselbe
Note. Es als Posten zu vertagen hätte die einzige `json`-Stelle still gelassen,
die das Paket nicht schon laut macht, und genau das ist das Ziel des
Umbrellas. `jsonb` meldet weiter nichts.

#### Vier Abdeckungslücken (beide Prüfer: die Sabotagen blieben grün)

Die Meldungen standen im Code, kein Test hielt sie:

| Stelle | Code | Jetzt gepinnt durch |
| --- | --- | --- |
| `MysqlDiffTableOps.renderIdentityTransition` | `W163` beim **Wechsel** des Modus nach `always` | „migrate: der Wechsel des Modus nach always meldet W163", mit zwei Gegenproben (`by_default`, Wechsel weg von der Identity) |
| `renderAlterColumnType` | `W162` beim Typwechsel | „migrate: der Typwechsel meldet W162" |
| `renderAlterColumnGeneration` | `W162` beim Ausdruckswechsel | „migrate: der Ausdruckswechsel einer berechneten Array-Spalte meldet W162" |
| `renderComputedKindSwap` | `W162` beim Spaltentausch | „migrate: der Spaltentausch meldet W162 einmal je Operation" |

**Eine Beobachtung zum Typwechsel, die im Test steht:** der Planer erreicht die
Stelle mit einer Array-Spalte nur, solange die Elementart **gleich** bleibt —
ein Wechsel der Elementart ist kein sicherer Cast (`isSafeImplicitCast`) und
wird vorher geblockt. Gepinnt ist deshalb die Stelle selbst: schreibt sie eine
Array-Deklaration, meldet sie den Verlust. Das ist genau die Zusicherung, die
P5 gegeben hat („an **jeder** Stelle, die eine Spaltendeklaration schreibt").

#### M3 — die Spec sagte „alleiniger Primärschlüssel", der Code sagt „im Schlüssel"

Der Reader kennt nur `isPkCol` — ob die Spalte den Schlüssel **allein** bildet,
weiß er nicht (`PostgresSchemaStructureReaders`: `columnName in
primaryKeyColumns`). Ein Mitglied eines mehrspaltigen Schlüssels liest also
genauso. Die Lesart ist vertretbar: der Verlust ist derselbe, und ein Modus,
den das Ziel nicht durchsetzt, ist in beiden Fällen weg. **Die Spec zieht
nach** (`spec/type-mapping.md` 3.4 sagt jetzt „im Primärschlüssel"), und ein
Integrationsfall pinnt den mehrspaltigen Fall an einem echten Server.

#### Doku und Orte

- **CHANGELOG:** die Severity-Anhebung `R365` `INFO` → `WARNING` steht jetzt
  unter „Changed" (P1-DoD 8, hatte gefehlt); die Betreiberfolge von S3 ist
  benannt (ein mit älterer Version gebautes PostgreSQL-Ziel trägt `text[]`,
  der nächste `schema migrate` plant `TABLE_COLUMN_TYPE_CHANGED` und blockt
  ohne `using-expression`-Overlay mit `PG_USING_OVERLAY_MISSING`); die
  H1-Einschränkung ist genannt; und die Overlay-Formulierung ist präzisiert —
  **Rollback-Artefakte fallen über die Algorithmus-Kennung** (immer, auch ohne
  Array im Schema), **Overlays über den Wertvergleich** (nur, wenn das
  beschriebene Schema ein Array trägt). Die Kennung kennen sie gar nicht.
- **`examples/mcp-e2e/README.md`:** Zellzahlen und -erklärungen nachgezogen
  (PostgreSQL 11/11/22 → 13/13/24). Kein Gate fängt das — die Datei ist
  Beschreibung, die `expected/compare-matrix.env` ist die Zusicherung.
- **`docs/planning/open/README.md`:** die Zeile zu
  `json-jsonb-zweite-json-art.md` sagte noch „wird in Plan 2 gebaut"; der
  Eintrag ist geschlossen.
- **Neu in `open/`:** die Matrix-Fixture trägt keine Anmerkungen
  ([`../open/matrix-fixture-ohne-anmerkungen.md`](../open/matrix-fixture-ohne-anmerkungen.md))
  — deshalb bewegte `cm_order.id` beim Pinnen von P10 zwei Zellen unbemerkt;
  und der SQLite-`ADD COLUMN`-Rest aus M2 (s. o.).
- **Troubleshooting-Leitfaden:** `W162` und `W163` stehen in Abschnitt 7
  („Cross-Dialect-Überraschungen"), je mit dem, was ein Betreiber tun kann —
  bei `W162` ausdrücklich: **nichts**, der Rückweg ist verloren.
- **Anwenderhandbuch:** `R365` und `R370` standen unter der Frage „Was liest
  `schema reverse` von den **Oracle-Routinen** nicht?" — beide handeln von
  Geometriespalten. Sie stehen jetzt unter der SRID-Frage direkt darunter, die
  ohnehin beide Fälle erklärt; die Routinen-Tabelle handelt wieder nur von
  Routinen. Auf den Absatz verlinkt nichts (er ist fett gesetzt, keine
  Überschrift mit Anker) — geprüft.
- **Kleinkram:** der tote KDoc-Verweis `PostgresTypeMapper.resolveElementType`
  in `ColumnValueGenerator` (heißt seit S3 `elementSql`) und derselbe im
  Testkommentar von `PostgresDdlGeneratorTestPart2`; die KDoc-Einrückung der
  `W163`-Zeile in `SqliteRebuildRenderer`; `[spec/type-mapping.md]` als
  KDoc-Klammerverweis in `PostgresTypeMapping` (kein auflösbares Symbol, jetzt
  in Backticks) — dasselbe für den Verweis auf `SchemaColumnValidationRules`,
  das im Hexagon `internal` ist.

#### Die Matrix hat sich nicht bewegt — und das ist gemessen, nicht gefolgert

Kein Befund der Korrekturrunde kann eine Zelle bewegen: H1 betrifft `smallint`,
und kein Seed und keine Fixture trägt eine `smallint`-Identity; L2 betrifft das
Feld eines zusammengesetzten Typs, und keine der beiden Vergleichsgrundlagen
führt einen; M2 und der Migrate-Teil von L1 liegen auf einem Pfad, den die
Matrix nicht fährt (sie generiert und wendet an); L3 betrifft Oracle, das in
der Matrix nicht gemessen wird; und die Generate-Seite von L1 prüfte den Typ
schon vorher, nur an der Aufrufstelle statt im Prädikat.

**Trotzdem gefahren**, gegen ein frisches `d-migrate:dev` aus diesem Stand: Exit
0, **keine** Abweichung, Matrixblock und Codes je Zelle zeichengleich zum
Verifikationslauf (nur der Zeitstempel unterscheidet sich), dieselben sieben
bekannten Befunde. **Kein Neu-Pin nötig.** Das bestätigt zugleich die Zahlen,
die jetzt in `examples/mcp-e2e/README.md` stehen. Danach nur die drei eigenen
Dienste gestoppt; `mcp-e2e-oracle-1` und die `d-migrate-*`-Container laufen
weiter, sie gehören nicht zu diesem Lauf.

#### Sabotage-Protokoll der Korrekturrunde

Zwei Stapel, je Stelle eine Rücknahme, danach Prüfsummen-Vergleich:

| Sabotage | Erwartet rot | Ergebnis |
| --- | --- | --- |
| `W162` an `renderAlterColumnType` weg | Typwechsel-Test | rot |
| `W162` an `renderAlterColumnGeneration` weg | Ausdruckswechsel-Test | rot |
| `W162` an `renderComputedKindSwap` weg | Spaltentausch-Test | rot |
| `W163` an `renderIdentityTransition` weg (Wiederholung S‑T) | Modus-Wechsel-Test | rot |
| Typprüfung aus `appliesTo` (L1) | `decimal`-Gegenprobe | rot |
| H1 zurück auf `mapIntegerTypes` | beide `smallint`-Tests | rot |
| `R402` im Feld zurückgenommen (L2) | Composite-`json`-Test | rot |
| `R370`-Zweig „Zeile ohne SRID" zurück (L3) | beide L3-Tests | rot |
| `isSolePrimaryKey = true` am `ADD COLUMN` (M2) | Unit-Test **und** Integrationsfall | rot |

Die MySQL-Sabotagen sind die Wiederholung von **S‑T** und **S‑U** aus der
Verifikation: dort blieben sie grün, jetzt sind sie rot — das war ihr Zweck.
Ein Zwischenbefund gehört dazu: die erste Fassung der L3-Sabotage fiel schon
bei **Detekt** (`UnusedPrivateMember`), bevor ein Test lief; sie ist so
umgebaut worden, dass sie den alten Code wiederherstellt statt nur eine Zeile
zu entfernen — sonst belegt die Sabotage das Gate, nicht den Test.

Die Rücknahme ist über `md5sum -c` belegt, und dabei fiel eine unvollständige
Rücknahme auf (eine Ersetzung traf ihr Muster nicht mehr, weil der Kommentar
darüber stehen geblieben war). Der Prüfsummen-Vergleich ist der Grund, dass es
nicht in einen Commit gelaufen ist.

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
   einem zum Fall passenden Ausweg — **drei** Fälle seit der Korrekturrunde,
   die vorhandene Zeile ohne SRID ist einer davon; `R365` ist `WARNING` und
   steht nur an Tabellen mit Geometriespalte; `W120` empfiehlt nie den
   ausgeschlossenen Ausweg; `schema reverse` endet mit Exit `0` (P1).
6. Eine Geometriespalte ohne erreichbares `geometry_columns` ist mit Grund
   und Ausweg benannt, und der Ausweg steht im Anwenderhandbuch (P3).
7. S1, S2 und S3 sind gemessen und nach der entschiedenen Regel gebaut. S1
   gilt für `integer`; `smallint` bleibt `identifier` (H1, Eigner), und der
   Verlust dort ist als S6 in Plan 3 benannt statt still.
8. Jede neue Kennung steht an ihren Registrierungsorten und dort, wo ein
   Anwender sie liest; CHANGELOG nennt jede sichtbare Änderung — auch die
   **Severity-Anhebung** von `R365` und die Betreiberfolge der
   Fingerabdruck-Anhebung.
9. Jeder Fix fällt nachweislich mit zurückgenommenem Fix. Das gilt auch für
   die vier Stellen, an denen die Meldung zwar im Code stand, aber kein Test
   sie hielt (Korrekturrunde, S‑T/S‑U).

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
   Eintrag von Plan 2 mehr. → gefahren, je Paket ein Commit; Zellen,
   weggefallene Befunde und die Sabotage am Pin stehen im Bauabschnitt.
4. **Gates:** Umbrella, „Gates je Commit". `make docs-check` nach jedem
   Spec-, Ledger- oder Handbuch-Nachtrag.
5. **Was gemessen ist und was nicht:** B1, B3 (Code), D2, D4 (MySQL) und A1
   sind gemessen oder im Code bestätigt; M2, L2, L3, N1, D4 (SQLite), M4, H2,
   M5 und der `R365`-an-jeder-Tabelle-Befund sind nur im Code geprüft.
   Aus der Korrekturrunde kommen zwei Server-Messungen dazu: der
   SQLite-`ADD COLUMN` (M2) läuft gegen einen echten Server durch, und die
   Breiten-Grenze aus H1 samt dem mehrspaltigen Schlüssel (M3) ist gegen
   PostgreSQL 18 gemessen — dort prüft der Fall auch, dass das gelesene Schema
   die eigene Validierung besteht.

## Offen

- **E2** (S1) und **E3** (S2), Umbrella.
- **Bestätigung der Fingerabdruck-Anhebung** (S3), Umbrella.
- **P1, zweiter Fall** — vom Eigner am 2026-09-18 **bestätigt**: beide Fälle
  melden, jeder mit dem Ausweg, der dort passt (ein verlorener SRID kostet in
  beiden den Spatial-Index). DoD 2 und der zweite Text bleiben.
- **Der SQL-Server-Fall aus S1** — `int IDENTITY` als alleiniger
  Primärschlüssel verliert weiter den Modus, und kein Code sagt es. Befund und
  die drei Wege stehen in
  [`../open/mssql-integer-identity-pk-verliert-den-modus.md`](../open/mssql-integer-identity-pk-verliert-den-modus.md);
  ohne Entscheidung bleibt es der bekannte Befund, der als einziger aus diesem
  Plan in der Liste stehen bleibt.
- **Die Matrix-Fixture trägt keine Anmerkungen.** `cm_order.id` hat beim Pinnen
  von P10 zwei Zellen bewegt, die niemand erwartet hatte, weil der
  Silent-Loss-Check nur `fixtures/seeds/` liest. Das ist so gewollt (die
  Fixture ist die Vergleichsgrundlage, kein Prüfobjekt), heißt aber: was nur
  an ihr hängt, fällt erst in den Zellzahlen auf. Befund, Messung und zwei
  Wege stehen seit der Korrekturrunde in
  [`../open/matrix-fixture-ohne-anmerkungen.md`](../open/matrix-fixture-ohne-anmerkungen.md).
- **Der `smallint`-Identity-Primärschlüssel auf PostgreSQL** — H1 hält den
  S1-Zweig eng, damit das gelesene Schema erzeugbar bleibt; der Modus geht
  dort weiter verloren, ohne Code. Als **S6** in
  [Plan 3](../next/reader-treue-3-spatial.md) eingetragen, zusammen mit **S5**
  (SQL Server) zu entscheiden: welche Breiten trägt der `identifier`-Vertrag.
- **`ADD COLUMN` einer Identity-Spalte auf SQLite verliert den Autowert still**
  (Rest aus M2). SQLite kann einen rowid-Alias per `ALTER TABLE` nicht
  anlegen; gemeldet wird das nicht, weil `W163` einen Autowert voraussetzt und
  `W135` einen zusammengesetzten Schlüssel als Grund nennt, den es hier nicht
  gibt. Mit Nebenbefund am Prädikat und drei Wegen in
  [`../open/sqlite-add-column-identity-verliert-den-autowert.md`](../open/sqlite-add-column-identity-verliert-den-autowert.md).

## Closure

**Graduiert 2026-09-18.** Alle neun Pakete sind gebaut (P5, S2, P10, S3, P8
und P9, S1, P1, P3), dazu fünf Neu-Pins der Compare-Matrix und eine
Korrekturrunde nach Review und Verifikation. Released ist es nicht: die
Wirkung steht in `CHANGELOG.md` unter `[Unreleased]`. Offen bleibt in diesem
Plan nichts; jeder verbliebene Punkt hat unter „Restflächen" einen Ort
außerhalb. Der Umbrella
[`reader-treue.md`](../in-progress/reader-treue.md) bleibt in
`../in-progress/`, weil die Pläne 3 und 4 offen sind.

**Woran „fertig" gemessen ist** — am Vertrag, nicht an diesem Plan:

- [`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004)
  (Reverse-Engineering; PostgreSQL mit JSON/JSONB- und Array-Spalten,
  Lastenheft Abschnitt 8.4): der Reverse **benennt**, was er aufgeben muss —
  `json` gegen `jsonb` (`R402`), `numeric` ohne Präzision (`R404`), eine
  Elementart und ein Feldtyp ohne neutralen Namen (`R301`), auf SQLite `R221`,
  auf Oracle `R371` und der verlorene SRID (`R370`) —, und was er **nicht**
  aufgeben muss, gibt er nicht mehr auf: ein
  `integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY` behält seinen Modus (S1),
  der SQLite-Migrate-Pfad legt den Autowert an (S2), und der
  PostgreSQL-Generator schreibt die Elementart eines Arrays statt `TEXT[]`
  (S3). Am Generator sagen `W162` und `W163`, was das Ziel nicht halten kann.
- [`LN-016`](../../../spec/lastenheft-d-migrate.md#ln-016) (Meldungen
  aussagekräftig und handlungsorientiert): jede neue Kennung nennt das
  betroffene Objekt und, wo es einen gibt, den Ausweg — `R405` den
  `search_path` (oder Subtyp und SRID in der Schemadatei), `R370` je nach
  Tatsachenlage einen von drei Auswegen und nie den ausgeschlossenen, `W162`
  ausdrücklich **keinen**, weil der Rückweg verloren ist. Kein Text rät: die
  Oracle-Fälle werden am Namen und an der vorhandenen Zeile unterschieden,
  nicht geschätzt.
- [ADR 0058](../../adr/0058-verlorener-srid-beim-reverse-ist-warnung.md) ist
  eingelöst: `R370` und `R365` sind `WARNING` und **blocken nichts** — kein
  Pfad in `schema generate`, `schema migrate` oder `data transfer` wertet sie
  aus, `OracleDataWriter` liest dieselbe Sicht unverändert, und die
  `E057`-Zusicherungen sind unberührt. Der Abgleich bleibt wortgetreu. Den
  Fall, den der ADR offenließ (unquotierte Tabelle ohne registrierte Zeile),
  hat der Eigner am 2026-09-18 bestätigt. Entscheidung 1 desselben ADR trägt
  auch die Severity von `R405`.
- [ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md),
  Abschnitt 2, Punkt 5, gilt unverändert: der Identity-Modus bleibt im
  Vergleich ein **Fund**. Dieser Plan hat keine Toleranz gebaut, sondern den
  Verlust am Generator benannt; `W163` ist damit der Beleg für Kandidat K2 im
  [Toleranzprofil](../next/compare-toleranzprofil.md).
- Die Abgrenzung von
  [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) ist gewahrt:
  **keine Modellerweiterung**. `json` und `jsonb` bleiben ein neutraler Typ,
  `numeric` ohne Präzision bleibt `float`, das Array trägt weiter nur den
  **Namen** seiner Elementart. Gebaut sind Meldungen und drei Korrekturen am
  Rand, kein neuer Typ.
- Die Spec beschreibt das Gebaute:
  [`spec/type-mapping.md`](../../../spec/type-mapping.md) 3.4 und 3.5
  (PostgreSQL, Reverse- und Forward-Entscheidungen als Regel geschrieben), 5.2
  (SQLite), 7.2 (Oracle, `NUMBER` und `SDO_GEOMETRY`) und Abschnitt 8
  (der `else`-Rückfall gilt ausdrücklich auch für Array-Elemente und Felder
  zusammengesetzter Typen);
  [`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md) 3.4
  und 3.5 (Array- und Identity-Regel für MySQL und SQLite) sowie 16.10
  (Oracle-Reverse-Regel);
  [`spec/cli-spec.md`](../../../spec/cli-spec.md) (W-Tabelle und die „Grenze —
  der Identity-Modus", die jetzt `W163` neben `W140` nennt);
  [`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md) (Zeilen
  `array` und `json`). Die beiden neuen W-Codes stehen an allen fünf
  Registrierungsorten, einschließlich Einzel- und Bereichszeile in
  [`spec/ledger.md`](../../../spec/ledger.md) und
  [`ledger/warn-code-ledger-1.1.0.yaml`](../../../ledger/warn-code-ledger-1.1.0.yaml);
  die sieben R-Codes haben keinen Ledger (Umbrella, „Gemeinsame
  Doku-Pflichten"). `docs/user/` beschreibt den Ist-Zustand: Anwenderhandbuch
  3.2, 3.3, 3.4, 3.12, 3.16, die FAQ-Fragen zum Oracle-SRID und Anhang C,
  Troubleshooting-Leitfaden Abschnitt 7.

**Paket → Commit**

| Abschnitt | Paket | Commit |
| --- | --- | --- |
| Bau | P5 — `W162` auf MySQL und SQLite, Generate und Migrate; B2 als Grenze gepinnt (Move nach `in-progress/`) | `e53543ca1` |
| Bau | S2 — der SQLite-Migrate-Pfad rendert `generation: identity`; `isDroppedAutoincrement` mitgezogen | `98f99e48b` |
| Bau | P10 — `W163` auf MySQL und SQLite, nur bei `mode: always` | `d1b1936d0` |
| Bau | S3 — der PostgreSQL-Generator rendert die Elementart; Fingerabdruck `v16` → `v17` | `a9c9b4563` |
| Bau | P8 und P9 — `R402`, `R404`, `R301`, `R221`, `R371` (ein Commit, dieselbe Funktion) | `ef346d77c` |
| Bau | S1 — der `integer`-Identity-Primärschlüssel behält den Modus | `14ec70888` |
| Bau | P1 — `R370`, `R365` als `WARNING`, `W120` je Fall | `86f61b076` |
| Bau | P3 — `R405` nennt Grund und Ausweg; das Matrix-Bein | `90d8e38b7` |
| Neu-Pin | P5 — `W162` in zwei Zellen, acht bekannte Befunde weg | `8ae466421` |
| Neu-Pin | P10 — `W163` in vier Zellen, zwei bekannte Befunde weg | `512f0b738` |
| Neu-Pin | P8/P9 — vier Reverse-Codes, vier bekannte Befunde weg | `4a79927a1` |
| Neu-Pin | S1 — drei Zellzahlen, zweites `W163` je Zelle, vier bekannte Befunde weg | `a8a3a01bc` |
| Neu-Pin | P3 — der Schlüssel des Matrix-Beins, Kopf der Erwartungsdatei nachgezogen | `781fac613` |
| Plan | Matrix-Stand: Pins, weggefallene Befunde, Typ-Sensor | `c085e4127` |
| Entscheidung | Zwei Eigner-Entscheidungen: der SQL-Server-Fall wird **S5** in Plan 3, P1 meldet weiter **beide** Fälle | `9b12ca4c2` |
| Korrektur | H1 (S1 nur für `integer`), M3 (Spec sagt „im Primärschlüssel"), L2 (`R402` am Feld eines zusammengesetzten Typs) | `93720f317` |
| Korrektur | M2 — SQLite `ADD COLUMN` erklärt keinen Primärschlüssel mehr | `580307637` |
| Korrektur | L1 (`W163` prüft den Typ) und die vier ungepinnten Meldestellen | `858a0fab6` |
| Korrektur | L3 — `R370` unterscheidet die Zeile **ohne** SRID von der fehlenden | `cc34d1241` |
| Korrektur | tote KDoc-Verweise und eine Einrückung aus dem S3-Umbau | `109bc9fb7` |
| Korrektur | Doku: `R365`-Anhebung, Betreiberfolge der Abdrucks-Anhebung, `W162`/`W163` im Troubleshooting, Zellzahlen im `mcp-e2e`-README | `bcc63ca3c` |
| Korrektur | Plan — Korrekturrunde, Sabotage-Protokoll, zwei neue `open/`-Einträge | `9685da681` |
| Graduation | Closure, Restflächen, Move nach `../done/`; Nachträge im Umbrella und in `open/` | der Move-Commit |

**Was über den Entwurf hinausging**

- **S1 gilt nur für `integer`** (H1, Eigner-Entscheidung 2026-09-18). Der
  Zweig nahm zuerst jeden Typ, den `mapIntegerTypes` kennt, also auch
  `smallint` — und genau diese Form trägt das eigene Modell nicht: die
  Validierung lehnt sie mit `E130` ab, und der PostgreSQL-Generator rendert
  sie nicht. Ein `schema generate` aus dem eigenen Reverse wäre abgebrochen;
  aus einem verlustbehafteten, aber lauffähigen Weg wäre ein abbrechender
  geworden. Der Rest der Frage ist als **S6** in
  [Plan 3](../next/reader-treue-3-spatial.md) benannt statt still.
- **`R402` auch am Feld eines zusammengesetzten Typs** (L2). `CREATE TYPE …
  AS (…)` schreibt den Feldtyp durch denselben Mapper wie eine Spalte, das
  Feld entsteht am Ziel also als `jsonb` — derselbe Verlust. Es zu vertagen
  hätte die einzige `json`-Stelle still gelassen, die der Plan nicht schon
  laut macht. Dafür bekam `readPostgresCustomTypes` überhaupt erst eine
  Notizliste: an dieser Stelle gab es vorher **keinen Kanal**.
- **`R370` hat drei Texte statt zwei** (L3). `USER_SDO_GEOM_METADATA.SRID` ist
  nullbar: eine Zeile, die die Ausdehnung beschreibt, aber kein Bezugssystem
  nennt, fiel in den Text „hat keine Zeile" — und der riet, eine anzulegen,
  die es schon gibt. Die vorhandene Zeile schlägt jetzt die Namensregel, weil
  sie eine Tatsache ist und die Schreibweise nur ein Indiz.
- **Vier Meldestellen im MySQL-Migrate-Pfad waren ungepinnt** (S‑T und S‑U der
  Verifikation blieben grün): `W163` beim **Wechsel** des Modus nach `always`
  und `W162` beim Typwechsel, beim Ausdruckswechsel und beim Spaltentausch.
  Die Meldungen standen im Code, kein Test hielt sie; jetzt je einer, je mit
  Gegenprobe.
- **Die Typprüfung liegt im Prädikat, nicht an der Aufrufstelle** (L1).
  `MysqlIdentityModeDegradation.appliesTo` sah nur den Modus — eine
  `decimal`-Spalte mit erklärter Identity hätte auf dem Migrate-Pfad „der
  Modus ist nicht durchgesetzt" gemeldet, obwohl MySQL sie als gewöhnliche
  Spalte schreibt. Einmal geprüft, für beide Pfade.
- **Das `ADD COLUMN` erklärt keinen Primärschlüssel mehr** (M2). Der seit S2
  neue Zweig griff über einen Default `isSolePrimaryKey = true` auf **jedem**
  `ADD COLUMN` einer Identity-Spalte; SQLite lehnt das ab. Der Parameter hat
  jetzt keinen Default — das ist die Lehre daraus: ein Default `true` macht an
  dieser Stelle aus einer vergessenen Entscheidung eine falsche.
- **S2 nahm einen zweiten Verlust mit.**
  `SqliteCompositePkIdentity.isDroppedAutoincrement` prüfte den **Typ**, nicht
  die Spalte; sobald `columnLine` auch `generation: identity` inline rendert,
  hätte dieselbe Schreibweise im zusammengesetzten Schlüssel ihr
  AUTOINCREMENT verloren, und `W135` hätte davon nichts gesagt.
- **Zwei Zellen mit Quelle SQL Server waren nicht vorhergesehen** (P10-Pin).
  `cm_order.id` der Matrix-Fixture ist `identity(by_default)`, SQL Server
  rendert das als `IDENTITY(1,1)`, und sein Reverse liest daraus `always`
  zurück — ab dem Reverse ist die Quelle eine ALWAYS-Identity. Der
  Silent-Loss-Check sah den Fall nie, weil er nur `fixtures/seeds/` liest;
  daraus der neue `open/`-Eintrag zur Fixture.

**Abnahme**

- **Compare-Matrix** (`make mcp-e2e-up`, `make mcp-e2e-compare-matrix` gegen
  `d-migrate:dev`, 1.8.0-SNAPSHOT; PostgreSQL 18.6 mit PostGIS, MySQL 9.7.2,
  SQL Server 2025, SQLite 3.45 vom Host, 42 angemerkte Seed-Spalten; Oracle
  nicht gefahren). Der erste Lauf **ohne** `--update-expectations` zeigte
  **13 Abweichungen und 18 weggefallene bekannte Befunde**. Gepinnt wurde
  danach je Paket von Hand aus dem gelesenen Diff, in fünf eigenen Commits.
  Die einzigen bewegten Zellzahlen sind die von S1: PostgreSQL → MySQL und
  → SQL Server `11` → **`13`**, → SQLite `22` → **`24`** — der Verlust war
  vorher unsichtbar, weil beide Seiten gleich verloren waren.
  `SILENT_LOSS_KNOWN` geht von **25 auf 7** Einträge, und keiner der sieben
  nennt noch ein Paket dieses Plans. **Dass die Pins beißen**, belegt eine
  Sabotage am einzigen neuen Schlüssel (`R405` aus
  `REPORT_CODES_POSTGRESQL_NOSEARCHPATH` entfernt → Lauf meldet die Abweichung
  im Klartext, Exit 2, Rücknahme per Prüfsumme); danach zwei Läufe
  hintereinander, beide Exit 0 und zeichenweise gleich. Nach der
  Korrekturrunde noch einmal gefahren: Exit 0, **keine** Abweichung, kein
  Neu-Pin nötig, dieselben sieben bekannten Befunde.
- **Integration** (`make integration`). Nulllinie **vor** dem ersten Paket,
  über alle vier Module in **einem** Lauf: `BUILD SUCCESSFUL` in 23 min 6 s,
  165 Tasks, die vier `:test`-Tasks `executed` (nicht `SKIPPED`, nicht
  `UP-TO-DATE`), keine Selbstüberspringung in den vier Testquellbäumen; Oracle
  fuhr dabei beide Images. **Eine Testzahl je Modul steht nicht im Lauf** (das
  Integrations-Image trägt das Repo als Kopie, die Reports bleiben im
  Container) — gemessen ist der Task, nicht die Zahl; dieselbe Grenze wie in
  [Plan 1](reader-treue-1-matrix-abnahme.md). Live gemessen wurden danach P1
  gegen `TestImages.ORACLE_FULL` (Exit 0 über den echten Runner), P3 gegen
  `TestImages.POSTGIS` (der Container ist in `:test:integration-postgresql`
  neu und damit nicht mehr ungefahren), S1, S3, H1 und M3 gegen PostgreSQL 18
  sowie S2 und M2 gegen SQLite 3.45.
- **Unit und Gates.** `make docker-check` je berührtem Modul und **einmal ohne
  `MODULES` über das ganze Repo: 12 435 Tests, 0 Fehler** — nötig, weil S3
  `MigrationFingerprint` in `:hexagon:core` anhebt und `MODULES=` die
  Integrationsmodule nicht einmal kompiliert. Dazu `make sample-db-types-smoke`
  als Drift-Sensor der Typ-Kanonisierung nach S3: **alles grün** — 21 Typen je
  Exit 0, UNIQUE- und FK-Folds, Konvergenz-Zweitlauf mit 0 Anweisungen,
  Rebuild, der Rollback-Round-Trip über das v7-Artefakt, die
  `schema compare`-Gegenprobe und die Kanten-Proben auf PostgreSQL und MySQL.
  `make docs-check` nach jedem Spec-, Ledger- und Handbuch-Nachtrag,
  `make solid-suppression-gate` vor jedem Commit,
  `make doc-immutable RANGE=origin/main..HEAD` vor dem Push. Kein `@Suppress`,
  kein Kern eines akzeptierten ADR angefasst.
- **Sabotage-Protokoll, Bau:** je Paket eine Rücknahme, jede per Prüfsumme
  belegt und danach grün — P5 9 Tests rot (MySQL 3, SQLite 4, zwei
  DDL-Goldens), S2 4, P10 6 (MySQL 3, SQLite 3), S3 3 (Rendern,
  Kanonisierer-Fixpunkt, Kantentabelle), P8/P9 9 (PostgreSQL 6, SQLite 2,
  Oracle 1), S1 3, P1 4 (zwei `W120`-Fälle, die `R365`-Zusicherung, das
  Oracle-Golden), P3 3 (Fehlfall und beide Gegenproben); dazu die Sabotage am
  Matrix-Pin oben. **Korrekturrunde:** neun weitere Rücknahmen in zwei
  Stapeln, darunter die Wiederholung von **S‑T** und **S‑U** aus der
  Verifikation — dort blieben sie grün, jetzt sind sie rot. Zwei
  Zwischenbefunde gehören dazu: eine Sabotage, die schon bei **Detekt** fällt,
  belegt das Gate und nicht den Test; und eine Rücknahme per Textersetzung
  traf ihr Muster nicht mehr — aufgefallen ist das nur am `md5sum -c`.
- **Abschluss-Verifikation:** in einem eigenen Klon gelaufen; ihr Ergebnis
  wird als Nachtrag am Ende dieses Plans nachgetragen.

**Was von diesem Plan lesenswert bleibt.** Dreimal war „melden" die falsche
Antwort: bei S1, S2 und S3 entsteht der Verlust auf dem Rückweg in **denselben**
Dialekt, und eine Note wäre dort ein Platzhalter für den Fix gewesen — gebaut
ist deshalb die Korrektur, und nur der Rest ist laut. Der teuerste Fund der
Runde kam nicht aus einem Test, sondern aus einer **Sabotage an grünem Code**:
an vier Stellen stand die Meldung im Code, und keine Zusicherung hielt sie. Und
H1 zeigt die Gegenrichtung — ein Reader, der mehr liest, als das eigene Modell
rendern kann, macht aus einem verlustbehafteten Weg einen abbrechenden.
