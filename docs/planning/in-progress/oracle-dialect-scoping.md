# Vorabklärung: Oracle als fünfter Dialekt (Milestone 1.8.0)

> **Status:** In Progress. Alle fünf Grundsatzentscheidungen getroffen (siehe
> ADR 0052). **Geliefert: Slices 0 bis 12.** Oracle führt damit denselben
> Befehlsumfang wie die vier anderen Dialekte: Reverse, Generate, Compare,
> Migrate, der Datenpfad, die Werkzeug-Exporte und `data profile`. Der
> Sample-DB-Harness fährt Pagila in **beide** Richtungen, Bitmap-,
> Ausdrucks- und Volltext-Indizes gehen durch, partitionierte Tabellen
> entstehen und werden zurückgelesen, Routinen und Trigger laufen als
> PL/SQL, und Materialized Views round-trippen mit ihrer
> Refresh-Einstellung. `DialectCommandGate` verliert seinen letzten Eintrag
> und entfällt.
>
> Slice 12 bringt Oracle Spatial: `SDO_GEOMETRY` traegt im Modell wieder eine
> Geometrie statt Text, der raeumliche Index wird gerendert und gelesen, und
> der Datenpfad bewegt Geometrien als WKB.
>
> Offen: PL/SQL-Packages ohne Liefertermin.
>
> Die datierten Status-Blöcke unten sind **Momentaufnahmen** und werden nicht
> rückwirkend umgeschrieben — was dort „bis Slice 5 gesperrt" heißt, war zum
> Zeitpunkt des Eintrags richtig.
>
> **Status-Update 2026-09-05:** Slice 0 umgesetzt — Modul
> `adapters/driven/driver-oracle` (Skeleton, `ojdbc11` 23.26.3.0.0),
> Spike-Modul `test/integration-oracle` (Container-Start gegen
> `gvenzl/oracle-free:23-slim-faststart` + Treiber-Connect +
> `SELECT banner FROM v$version`, live grün gelaufen), Dependabot-Major-Ignore,
> FUTC-Lizenzdoku in [`THIRD-PARTY-NOTICES.md`](../../../THIRD-PARTY-NOTICES.md).
> Live-Fund: die gleitenden `slim-faststart`-Tags liefern inzwischen „26ai"
> statt „23ai" aus (Banner „Oracle AI Database", nicht mehr „Oracle
> Database") — Spike pinnt deshalb explizit auf `23-slim-faststart`.
>
> **Status-Update 2026-09-05 (Slice 1):** `ORACLE`-Enum-Querschnitt,
> `DialectCommandGate` wiedereingeführt (schema generate/migrate, data
> export/import/transfer/profile bis zum jeweiligen Slice gesperrt), reale
> `OracleSchemaReader`/`OracleTableLister`/`OracleJdbcUrlBuilder` — `schema
> reverse` und `schema compare` funktionieren gegen Oracle. Unabhängiges
> Review vor dem Push fand drei P1- und zwei P2-Befunde (fehlende Tests +
> Kover-Gate, `export`-Kommandos ungegattert, `NUMBER(1)`-Boolean-Faltung
> fehlte); alle behoben, CI (Build & Test, Integration Tests, Per-Module
> Coverage) grün.
>
> **Status-Update 2026-09-05 (Slice 1a):** CLI-E2E-Netz in `test/e2e-cli`
> (`OracleCommandGateE2ETest`, `OracleSchemaReverseE2ETest`) — fand drei
> reale Bugs, die keiner der bisherigen Unit-/Reviewschichten sah: (1)
> `--dialect` bei `schema migrate` hatte "oracle" nicht in seiner
> Clikt-`choice()`-Liste, (2) `TransferConnectionResolver` prüfte das
> Gate ERST NACH dem Pool-Aufbau — bei `data transfer` schlug die
> Verbindung schon fehl, bevor das Gate greifen konnte (Fix: Gate direkt
> nach der URL-Auflösung, vor jedem `poolFactory`-Aufruf), (3)
> `OracleMetadataQueries.scanIndexes` schloss faelschlich auch
> UNIQUE-Constraint-Indizes aus (nur PK-Indizes gehören ausgeschlossen —
> es gibt keine gesonderte Oracle-Abfrage für UNIQUE-Constraints, der
> Index-Scan ist ihr einziger Weg ins Modell). Alle drei behoben, CI grün.
>
> **Status-Update 2026-09-05 (Slice 2):** `OracleDdlGenerator` +
> `OracleTypeMapper` + `OracleColumnConstraintHelper` — `schema generate`
> und `export flyway/liquibase/django/knex` funktionieren gegen Oracle
> (Tabellen, benannte UNIQUE/CHECK/PK/FK-Constraints, einfache B-Tree-
> Indizes, native Sequenzen, Views). Routinen/Trigger/Aggregate → E053,
> Composite-Typen → E054, Partitionierung → E055 (Tabelle plain),
> Volltext-Indizes → E057 (Slice 6/7/8/9 noch nicht gebaut).
> Neue Codes W145-W153 (`spec/ledger.md`). DDL-Goldens für neun
> Fixture-Kombinationen per CLI erzeugt. `AtomicPreserveRestoreSql` +
> `AtomicSequencePreserveDispatcher` beim Review auf
> `SequenceCapabilityDefaults.supportsAtomicPreserve`-Check umgestellt
> statt hartcodierter MSSQL/ORACLE-Aufzählung (Eigner-Fund); zwei weitere
> Capability-Architektur-Fragen (ViewQueryTransformer-Dialektzweige,
> Capability-Tabellen ins `DatabaseDriver`-Interface) als eigene
> `next/`-Pläne dokumentiert, nicht Teil dieses Slices. CI grün.
>
> **Review-Nachtrag (unabhängiges Review, gleicher Tag):** fünf P1-Funde
> behoben — Sequenz-Default-Quoting (`invoice_seq.NEXTVAL` →
> `"invoice_seq".NEXTVAL`, sonst ORA-02289 gegen echtes Oracle), fehlendes
> `FORCE` bei `CREATE OR REPLACE VIEW` (eine Sicht, die eine übersprungene
> Abhängigkeit referenziert, scheitert sonst sofort mit ORA-00942/ORA-00904
> statt wie bei MSSQL erst bei Nutzung), Reverse-Erkennung für
> `gen_uuid`/`current_date`/`current_time` ergänzt (sonst Round-Trip-
> Bedeutungsverlust), `spec/`-Slice-Referenzen entfernt (Zielbild-
> Konvention). Ein architektonischer Fund (unquoted Bezeichner in
> CHECK-Ausdrücken/View-Bodies bricht nur bei Oracle, da es der einzige
> Dialekt mit Uppercase-Faltung ist — betrifft alle fünf Dialekte
> gleichermaßen, kein Oracle-Slice-2-Bug) als
> [`open/oracle-uppercase-folding-unquoted-identifier-references.md`](../done/oracle-uppercase-folding-unquoted-identifier-references.md)
> dokumentiert statt in diesem Slice gefixt. Drei P2-Funde behoben
> (`isLargeObject`-Enum-Fehlklassifikation, `||` aus MySQL-Quelle non-portable
> zu Oracle, fehlende Oracle/MSSQL-Testabdeckung für den
> Atomic-Preserve-Guard). CI grün.
>
> **Status-Update 2026-09-06 (Slice 3):** `OracleDataReader`/
> `OracleDataWriter`/`OracleTableImportSession`/`OracleInsertSql`/
> `OracleSchemaSync` — `data export`/`import`/`transfer` funktionieren gegen
> Oracle. Drei Kernannahmen aus der Recherche erwiesen sich am echten
> Treiber (Testcontainer, `test/integration-oracle`) als falsch und wurden
> jeweils live korrigiert:
> - **Kein `OVERRIDING SYSTEM VALUE`** (`ORA-00926`): anders als
>   PostgreSQL/DB2 kennt Oracles `INSERT`/`MERGE` diese SQL:2003-Klausel
>   nicht. Ersatz: die Session schaltet eine `GENERATED ALWAYS AS
>   IDENTITY`-Spalte vor dem ersten Insert per `ALTER TABLE ... MODIFY
>   <col> GENERATED BY DEFAULT AS IDENTITY` temporär um (BY DEFAULT
>   akzeptiert explizite Werte ohne Klausel) und zurück im Cleanup.
> - **`ALTER SEQUENCE` scheitert an der Identity-Sequenz** (`ORA-32793:
>   Cannot alter a system-generated sequence`): die Sequenz hinter einer
>   Identity-Spalte ist system-generiert. Ersatz fürs Reseed:
>   `ALTER TABLE ... MODIFY <col> GENERATED <Modus> AS IDENTITY
>   (START WITH n)` bzw. `(START WITH LIMIT VALUE)` für den
>   Truncate-und-leer-gebliebene-Tabelle-Fall — beides über die
>   Identity-Klausel der Tabelle, nicht direkt auf der Sequenz.
> - **`ENABLE CONSTRAINT` (Oracle-Default `VALIDATE`) scheitert am
>   eigenen Zweck** (`ORA-02298`): während `disableFkChecks` bewusst nicht
>   constraint-konform eingefügte Zeilen lässt das Default-`VALIDATE` beim
>   Re-Enable genau daran scheitern. Ersatz: `ENABLE NOVALIDATE CONSTRAINT`
>   schaltet für künftige DML scharf, ohne den Altbestand zu prüfen.
>
> Weitere Kernentscheidungen gegenüber dem MSSQL-Vorbild:
> - **`MERGE`-Zeilen-Buchführung zweigeteilt**: `skip` (nur `WHEN NOT
>   MATCHED THEN INSERT`) unterscheidet eingefügt/übersprungen exakt über
>   die Batch-Zeilenzahl (1/0); `update` (beide Zweige) kann das nicht und
>   bucht als `rowsUnknown` statt zu schätzen oder Zeile für Zeile
>   auszuführen.
> - **FK-Disable pro Constraint statt global**: Oracle kennt kein
>   `SET FOREIGN_KEY_CHECKS=0`; die Session deaktiviert die eigenen
>   FK-Constraints der Zieltabelle einzeln.
> - **CLOB/BLOB-Materialisierung im Reader**: Oracle-JDBC liefert sie als
>   live Locator statt als `String`/`ByteArray` — der Reader materialisiert
>   sofort, während der Cursor noch auf der Zeile steht.
> - **`TIMESTAMPTZ` statt `OffsetDateTime`**: `getObject()` liefert das
>   treibereigene `oracle.sql.TIMESTAMPTZ`; die Konvertierung braucht
>   zwingend eine Connection (`offsetDateTimeValue(conn)`) — die geteilte
>   `mapValue`-Naht in `driver-common` (`AbstractJdbcDataReader`,
>   `JdbcChunkSequence`) trägt seitdem einen Connection-Parameter (MSSQLs
>   `DateTimeOffset`-Override angepasst, unverändertes Verhalten).
>
> `DialectCommandGate` hält nur noch `schema migrate` und `data profile`.
>
> **Status-Update 2026-09-06 (Slice 4a):** `OracleNeutralTypeCanonicalizer`
> als lebende Komposition `reverse(toSql(t))` von `OracleTypeMapper` und
> `OracleTypeMapping` — kein zweiter, handgepflegter Falt-Tisch. Zwei echte
> Vorab-Bugs beim Bau entdeckt und behoben, keiner davon in der
> Canonicalizer-Substanz selbst:
> - **`JSON`/`XMLTYPE` fehlten im Reverse-Read** (seit Slice 1): beide
>   fielen auf `Text(maxLength=null)` samt spurioser R301-Warnung zurück,
>   statt auf `Json`/`Xml`. Ergänzt in `OracleTypeMapping.mapOpaque` +
>   `KNOWN_TYPES`; ohne den Fix hätte der Kanonisierer beide Typen künstlich
>   als Identity-Carve-out führen müssen.
> - **`resolveRefType` blieb bei einem unaufloesbaren `refType` untätig**
>   (Custom Type fehlt im Schema oder ist ein wertloser `COMPOSITE`):
>   anders als `OracleColumnConstraintHelper.enumColumn`, das in jedem
>   dieser Fälle auf `plainColumn` (ungebundenes `VARCHAR2(4000)`) fällt,
>   hätte der Kanonisierer den Typ unverändert stehen lassen — eine
>   Divergenz, die im unabhängigen Review auffiel. Behoben: `resolveRefType`
>   liefert nie mehr `null`, sondern bildet alle drei Zweige von
>   `enumColumn` nach. Dieselbe Struktur existiert unverändert (und
>   unbehoben) in `MssqlNeutralTypeCanonicalizer` —
>   [`mssql-enum-reftype-unresolved-fallback-gap.md`](../done/mssql-enum-reftype-unresolved-fallback-gap.md)
>   dokumentiert das für den eigenen, separat zu verifizierenden Fix.
>
> `enumWidth` aus `OracleColumnConstraintHelper.boundedEnumColumn`
> extrahiert nach `OracleTypeMapper` (geteilte Quelle mit dem Kanonisierer,
> analog MSSQL). `Identifier(autoIncrement=true)` ist bewusst KEIN
> Identity-Carve-out (anders als PostgreSQL) — Oracles Reverse-Read faltet
> jede Identity-Spalte ohnehin auf ihren Basistyp, die Komposition liefert
> das schon richtig.
>
> Zwei Live-Belege in `test/integration-oracle` (gegen den echten
> Testcontainer, nicht nur eine zweite Tabelle): eine Typ-für-Typ-Sonde
> (analog `MssqlNeutralTypeCanonicalizerIntegrationTest`) und ein
> Postcompare-Fingerprint-Beleg (analog
> `MssqlPostCompareFingerprintIntegrationTest`, inkl. der diskriminierenden
> Gegenprobe „ohne Projektion driftet derselbe Round-Trip"). Letzterer
> deckte einen weiteren, echten aber **außerhalb der Canonicalizer-Substanz
> liegenden** Befund auf:
> `FingerprintValueProjection.generation()` bettet
> `ColumnGeneration.Identity.sequenceName` roh ein; Oracles Identity-Sequenz
> ist system-generiert (`ISEQ$$_n`) und für ein user-authored `desired`-
> Schema nie im Voraus bekannt — jede frisch angelegte Oracle-IDENTITY-Spalte
> würde nach `--execute` als Drift gemeldet. Aktuell **dormant** (Gate
> blockt `schema migrate` für Oracle bis Slice 5) und kein Slice-4a-Fix (der
> `(NeutralType) -> NeutralType`-Hook sieht `ColumnGeneration` gar nicht) —
> als Ticket für Slice 5 festgehalten (dort in Sub-Slice 5e-2 über den
> neuen `canonicalizeGeneration`-Hook gelöst, Ticket entfällt).
>
> **Status-Update 2026-09-06 (Slice 5a):** `OracleDiffDdlGenerator` +
> `OracleDiffTableOps`/`OracleDiffRenderContext`/`OracleDiffSqlBuilders` —
> die elf Tabellen-/Spalten-/Primärschlüssel-Operationen rendern UP und
> DOWN. Der Renderer ist bewusst **noch nicht** in
> `MigrateRendererRegistry`/`DialectCommandGate` verdrahtet (das ist 5e,
> demselben Muster folgend, das MSSQL in einem einzigen Registry-Commit
> gefahren hat) — erreichbar ist er bis dahin nur über direkte
> Instanziierung im Test.
>
> Kernentscheidungen, alle gegen den echten Testcontainer gemessen statt
> aus dem MSSQL-Vorbild übernommen:
> - **`CreateTable` wiederverwendet den Generate-Pfad**
>   (`OracleColumnConstraintHelper`, `OracleIndexDdlBuilder`) statt eine
>   zweite Spaltenwiedergabe zu bauen. PostgreSQLs Diff-Pfad tut das nicht
>   und trägt dafür eine dokumentierte Enum-Fidelity-Lücke (W134 im
>   `CreateTable`-Pfad); Oracle hat sie deshalb gar nicht erst.
>   Dafür wurde `generateIndex` aus `OracleDdlGenerator` nach
>   `OracleIndexDdlBuilder` gezogen — eine Quelle für beide Pfade.
> - **Kein Default-Dreischritt, kein Katalog-Namenslookup**: DEFAULT ist in
>   Oracle Spalteneigenschaft (wie PostgreSQL), und `DROP PRIMARY KEY`
>   kommt ohne Constraint-Namen aus — MSSQLs teuerste zwei Mechaniken
>   entfallen strukturell.
> - **Identity-Übergänge asymmetrisch** (live geklärt, siehe oben):
>   Entfernen rendert `MODIFY <col> DROP IDENTITY`, Hinzufügen blockt
>   benannt (`ORA-30673` ist unumgehbar ohne Tabellen-Neubau). Die Blockade
>   ist richtungsabhängig — die Down-Seite derselben Operation ist der
>   Entfernen-Fall und rendert sauber.
> - **`IMPLICIT_COMMIT` statt `FULLY_TRANSACTIONAL`**: Oracle-DDL committet
>   implizit (wie MySQL, anders als PostgreSQL/SQL Server) — die
>   Ausführungs-Hints sagen das, statt einen Rollback zu versprechen, den es
>   nicht gibt.
>
> Ein unabhängiges Review fand drei Befunde, alle vor dem Commit behoben:
> die fehlende Identity-Übergangs-Behandlung (P1, oben), ein zu enger
> W134-Wächter (er sah nur werte-basierte Enums, während Oracle auch
> `refType`-Enums auf `VARCHAR2(4000)` abflacht) und eine unbelegte
> Behauptung zur Index-Namensstabilität unter `RENAME TO` — letztere ist
> jetzt gemessen und stimmt.
>
> **Status-Update 2026-09-06 (Slice 5b):** `OracleDiffObjectOps` —
> `AddConstraint`/`DropConstraint`/`AddIndex`/`DropIndex` in beiden
> Richtungen. Zwölf Oracle-Eigenheiten live gemessen, bevor irgendetwas
> verdrahtet wurde; die drei, die den Renderer geprägt haben:
> - **Kein `WITH CHECK`-Äquivalent nötig.** Oracle validiert einen
>   nachgezogenen CHECK/FK per Default gegen den Bestand und scheitert an
>   verletzenden Zeilen (`ORA-02293`/`ORA-02298`) — genau die strenge
>   Semantik, die MSSQL sich mit `WITH CHECK` erst erkaufen muss. Die
>   Gegenrichtung (`ENABLE NOVALIDATE`) existiert und funktioniert (künftige
>   DML wird geprüft, Altbestand nicht), wäre aber eine stille Abschwächung
>   und wird deshalb bewusst NICHT gerendert.
> - **Ein UNIQUE-Constraint trägt seinen Index selbst**: `ADD CONSTRAINT`
>   legt ihn unter dem Constraint-Namen an, `DROP CONSTRAINT` räumt ihn mit
>   weg; einzeln droppen lässt Oracle ihn nicht (`ORA-02429`). Der Fall kann
>   im Diff aber gar nicht entstehen, weil der Reverse Unique-Indizes nie
>   als Index führt — `SchemaReaderUtils` hebt sie auf `column.unique` bzw.
>   einen UNIQUE-Constraint, und zwar geteilt für vier Dialekte.
> - **`DROP INDEX` nennt keinen Tabellennamen** (anders als MySQL), und es
>   gibt **kein `IF EXISTS`** (`ORA-02443`) — die Down-Richtung darf nicht
>   auf Idempotenz bauen.
>
> Weitere gemessene Randbedingungen, die der Renderer nicht abfangen kann und
> die deshalb nur dokumentiert sind: Constraint- und Indexnamen sind
> **schema-global** (`ORA-02264`/`ORA-00955`, dieselbe Falle wie MSSQLs
> Msg 2714), und ein UNIQUE, auf das ein Fremdschlüssel zeigt, lässt sich
> nicht droppen (`ORA-02273`) — beides entscheidet sich erst beim Ausführen
> bzw. an der Operationsreihenfolge des Planners.
>
> Vorprüfungen vor dem Generate-Helfer: der rechnet mit wohlgeformten
> Schemata und würde einen fehlenden CHECK-Ausdruck als `CHECK (null)`
> interpolieren bzw. bei fehlendem `references` mit einer NPE abbrechen. 5b
> blockt beide Fälle benannt, ebenso `EXCLUDE` (E054 war bis dahin nur eine
> Notiz) und UNIQUE auf LOB-Spalten (E057).
>
> **Zur Rename-Warnung aus 5a:** sie war in der Begründung falsch, im Kern
> aber zu harmlos formuliert. `DropConstraint` trägt zwar einen Namen im
> Payload (`ConstraintDefinition.name`, nicht-nullbar) — nur ist der bei
> einspaltigen Constraints **erfunden**: `TableComparator.normalizeConstraints`
> zieht JEDES einspaltige UNIQUE und jeden einspaltigen FK auf
> `singleColumnUnique`/`singleColumnForeignKeys` zusammen, **auch benannte
> Tabellen-Constraints**, und `compareConstraints` materialisiert das Delta
> anschließend über `syntheticUniqueConstraint`/`syntheticFkConstraint` mit
> den Platzhaltern `_unique_<spalte>` bzw. `_fk_<spalte>` neu. Der Renderer
> gibt genau diesen Platzhalter aus (`DROP CONSTRAINT "_unique_email"`), und
> nichts bildet ihn auf den Katalognamen zurück — gegen eine echte Datenbank
> endet das in `ORA-02443`. Das ist **dialektübergreifend und älter als
> Oracle** (PostgreSQL und MSSQL rendern identisch, der Name geht schon beim
> Reverse verloren); 5b ist nur der Slice, in dem Oracle es erbt. Ticket:
> [`single-column-constraint-synthetic-name.md`](../open/single-column-constraint-synthetic-name.md).
>
> Der zweite schmale Fall bleibt bestehen: ein **anonymer Index**
> (`IndexDefinition.name == null`, entsteht nur bei handgeschriebenen
> Schemata, weil der Reverse den Namen immer setzt). Add- und Drop-Pfad
> teilen sich dafür jetzt eine Namensauflösung
> (`OracleIndexDdlBuilder.effectiveName`), damit ein `DROP INDEX` nicht einen
> anderen Namen sucht, als das `CREATE INDEX` vergeben hat.
>
> **Nicht verdrahtet, bewusst:** `CheckPreflightGate` (PostgreSQL/MySQL/
> SQLite nutzen ihn für `AddConstraint(CHECK)`, MSSQL nicht). Er ist eine
> Vorab-Diagnose gegen Live-Daten, kein Korrektheitsbaustein — Oracles
> Default-Validierung sorgt ohnehin dafür, dass eine verletzte Bedingung
> nicht durchrutscht.
>
> **Status-Update 2026-09-06 (Slice 5c):** `OracleDiffViewOps` +
> `OracleDiffCustomTypeOps` — Views und Custom Types in beiden Richtungen.
> Sieben Oracle-Eigenheiten live gemessen; die drei, die das Design
> bestimmt haben:
> - **Kein Signatur-Wächter.** Oracles `CREATE OR REPLACE VIEW` darf die
>   Spaltenliste frei ändern (Anzahl UND Namen, verifiziert) — PostgreSQL
>   blockt genau das, weil es dort nicht geht. Ein kopierter Wächter wäre
>   hier zudem wirkungslos: `ViewDefinition.columns` befüllen nur PGs
>   Reverse und der Datei-Parser, Oracles Reverse nie.
> - **`ALTER VIEW ... RENAME TO` existiert nicht** (`ORA-00922`) —
>   umbenannt wird mit der freistehenden Anweisung `RENAME alt TO neu`.
> - **ENUM und DOMAIN haben in Oracle kein Datenbankobjekt**, sie leben an
>   der Spalte. `CreateCustomType`/`DropCustomType` erzeugen deshalb keine
>   Anweisung, buchen die Operation aber als erledigt
>   (`OracleDiffRenderContext.markRendered`) und legen die Begründung als
>   INFO-Diagnose ab. Eine geänderte ENUM fächert auf die nutzenden Spalten
>   auf: CHECK lösen, Breite anpassen, CHECK neu — in dieser Reihenfolge,
>   und den ersten Schritt nur, wenn es vorher überhaupt einen CHECK gab.
>
> **Ein Muster aus dem MSSQL-Vorbild wurde dabei verworfen, nicht
> übernommen:** dort (und in MySQL/PostgreSQL) emittieren Diff-Renderer
> SQL-Kommentare als Anweisung, um eine Operation als erledigt zu buchen.
> Der Vertrag verlangt das nicht — die Invariante in `MigrationDdlResult`
> ist einseitig, eine gerenderte Operation braucht keine Anweisung — und
> für Oracle wäre es ein Ausführungsfehler: `JdbcMigrationStatementExecutor`
> führt jede Anweisung aus, und Oracle lehnt eine reine Kommentar-Anweisung
> mit `ORA-00900` ab (in Slice 4a am Header-Kommentar gemessen). Der
> Oracle-Treiber enthält deshalb keine Kommentar-Anweisung mehr, auch die
> unerreichbare `DropTable`-Down-Attrappe aus 5a nicht;
> [`diff-comment-as-statement.md`](../done/diff-comment-as-statement.md)
> hält das dialektübergreifende Muster fest.
>
> Ein unabhängiges Review fand einen echten Absturz: `AlterCustomType` ist
> `MANUAL_REQUIRED` mit `risks.down = null` und passierte den
> `NOT_REVERSIBLE`-Wächter des Dispatchers — der Renderer lief dann in
> `riskFor`s `error(...)` statt in einen Blocker. Der fehlende
> Down-Risiko-Wächter ist ergänzt und per Sabotage-Test belegt. Weitere
> Befunde behoben: der Materialized-View-Wächter griff nur auf der
> Zielseite (ein View-/MV-Wechsel entsteht als gewöhnliches `ReplaceView`),
> `blockComposite` prüfte nur `op.after`, und der Fan-out hätte in drei
> erreichbaren Fällen ein `DROP CONSTRAINT` auf einen nie angelegten CHECK
> abgesetzt.
>
> **Status-Update 2026-09-06 (Slice 5d):** `OracleDiffSequenceOps` + das
> geteilte `OracleSequenceDdl` (Generate- und Diff-Pfad rendern
> `CREATE SEQUENCE` aus einer Quelle, byte-identisch per Test gepinnt).
> Acht Eigenheiten live gemessen; zwei haben das Design bestimmt:
> - **`START WITH` ist unveränderlich** (`ORA-02283`). `ALTER SEQUENCE`
>   schreibt deshalb jede Klausel außer dem Startwert aus; eine
>   Start-Abweichung wird gemeldet. Für Oracle trifft das häufiger als
>   anderswo, weil der Reverse `LAST_NUMBER` als `start` liest (`R345`) —
>   jede je gezogene Sequenz erscheint im Diff mit abweichendem Startwert,
>   ohne dass sich am Modell etwas geändert hätte.
> - **`LAST_NUMBER` ist der NÄCHSTE Wert, nicht der zuletzt ausgegebene.**
>   T-SQLs `sys.sequences.current_value` meint das Gegenteil, weshalb MSSQL
>   dort die Schrittweite addiert. Hätte ich das übernommen, wäre es falsch
>   gewesen — und der Cache-Fall zeigt, wie falsch: nach *einer* Ziehung mit
>   `CACHE 20` steht `LAST_NUMBER` auf 21, Oracle hat 1–20 reserviert. Bei
>   21 fortzusetzen lässt sie aus (Lücke); von „zuletzt ausgegeben + 1" zu
>   rechnen ergäbe 2 und vergäbe 2–20 ein zweites Mal.
>
> Der Rest folgt daraus: `RESTART START WITH` (nicht `RESTART WITH`),
> Umbenennen über die freistehende Anweisung wie bei Views, kein
> `IF EXISTS` (`ORA-02289`).
>
> **`supportsCurrentValuePreserve` blieb in 5d auf `false`, und die
> Begründung dafür war falsch.** Sie stützte sich auf das KDoc des Feldes —
> ein Kommentar, also beschreibend und nicht normativ. Nachgemessen am Code
> gilt: **das Feld hat repo-weit keinen Leser.** Es wird nur in
> `SequenceCapabilityDefaults` gesetzt und in `SequenceCapabilityTest`
> gegen sich selbst geprüft; kein Produktivpfad verzweigt darauf. Beide
> Werte ändern also kein Verhalten, und die Frage ist allein, welcher
> Wert wahr ist. Normativ ist `neutral-model-spec.md` Abschnitt 9, und
> dessen `preserve_current_value`-Zeile trägt für Oracle den Renderer.
> Der Wert gehört damit auf `true`; er wird zusammen mit der Verdrahtung
> gesetzt (5e).
>
> **Korrektur der 5d-Warnung zu `PRESERVE_DIALECTS`:** dort stand, Oracle
> einzutragen mache aus einem „sauberen Skip" einen harten Blocker. Das ist
> nicht so. `SequencePreserveStage.blockUnsupportedDialect` liefert
> `Outcome.NotRun` nur, wenn es gar keine Preserve-Kandidaten gibt —
> andernfalls schon **heute** ein `Outcome.Failed` mit BLOCKER-Diagnose. Der
> Eintritt in `PRESERVE_DIALECTS` ändert nicht *ob*, sondern *womit*
> geblockt wird, und zwar zum Wahren hin: die heutige Meldung
> „preserveCurrentValue is not supported on ORACLE" ist seit 5d falsch (der
> Renderer kann es ausdrücken), während `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`
> zutrifft — was Oracle fehlt, ist der atomare Ausführungspfad, nicht der
> Renderer.
>
> **Kein `OracleSequenceCurrentValueProbe` gebaut.** MSSQL hat einen, aber
> er hat repo-weit keinen Aufrufer — ihn nachzubauen hieße, unreferenzierten
> Code allein mit dem Vorbild zu begründen. Die gemessene `LAST_NUMBER`-Semantik steht
> stattdessen dort, wo sie trägt: im Renderer und in
> [`neutral-model-spec.md`](../../../spec/neutral-model-spec.md) Abschnitt 9.
>
> Dort ist auch die seit Slice 1 fehlende Oracle-Spalte der
> Sequenz-Capability-Matrix ergänzt, samt Renderer-/Probe-Zeile in 9.1 und
> einem Oracle-Defaults-Absatz.
>
> Das Review förderte dabei einen **Fehler im Reverse** zutage, der älter
> ist als Slice 5: `NOMAXVALUE` liefert Oracle als 28-stelligen Wert, den
> `BigDecimal.toLong()` still auf `4477988020393345023` verkürzt — eine
> unbegrenzte Sequenz kommt als begrenzt zurück. Kein Test hat das je
> gesehen, weil der Stub `Long.MAX_VALUE` verwendet, einen Wert, den echtes
> Oracle dort nie liefert. Zusammen mit der Beobachtung, dass
> `NOMINVALUE`/`NOMAXVALUE` ohnehin als Zahlen materialisieren und als
> deklarierte Schranken zurückgelesen werden, steht das in
> [`oracle-sequence-bounds-not-round-trippable.md`](../done/oracle-sequence-bounds-not-round-trippable.md).

> **Status-Update 2026-09-06 (Slice 5e-1):** die Rename-Vorbedingung —
> **beide** Teile des dafür geführten Tickets (Policy *und* Daten), nicht
> die dort als Zwischenschritt vorgeschlagene Policy ohne Daten; das Ticket
> ist mit diesem Commit gelöst und entfällt. Eine Policy, die den Reprojector ruft
> und mangels Abhängigkeiten nie etwas findet, hätte den `error(...)`-Stub
> durch etwas Schlimmeres ersetzt: eine umbenannte Tabelle ließe ihre
> Sichten still invalid zurück.
>
> Vorab live gemessen, weil MSSQLs Policy nichts über Oracle beweist:
> - **Tabellen-Rename:** FK bleibt `ENABLED` und zeigt auf den neuen Namen,
>   Index-/Constraint-Namen bleiben stehen — FK und Index brauchen keine
>   Projektion. **Alle** abhängigen Sichten gehen auf `INVALID`, ihr Rumpf in
>   `user_views.text` bleibt auf dem alten Namen, `SELECT` scheitert mit
>   `ORA-04063` — auch beim zweiten Versuch, die Sicht heilt sich beim
>   Zugriff also nicht.
> - **Spalten-Rename:** `user_cons_columns`/`user_ind_columns` folgen, und
>   den **CHECK-Ausdruck schreibt Oracle selbst um** (`"note" IS NOT NULL`
>   wurde zu `"remark" IS NOT NULL`). Von den Sichten bricht genau die, die
>   die Spalte nennt; eine andere Sicht auf derselben Tabelle bleibt `VALID`.
>
> Daraus die eine Stelle, an der Oracle von allen vier bestehenden Policies
> abweicht: **auch `classifyColumnRename` projiziert Sichten neu.** Der
> dialektunabhängige `VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS`-Wächter des
> Planers deckt diesen Fall nicht ab — er greift für `DropColumn` /
> `AlterColumnType` / `AlterColumnNullability`, nicht für `RenameColumn`.
> Neu dafür ist `RenameViewReprojector.reprojectViewsForColumnRename`.
>
> **Abhängigkeitsdaten** kommen aus `ALL_DEPENDENCIES`, gefiltert auf das
> eigene Schema. Zwei Messungen bestimmen die Übersetzung ins Modell:
> - Es gibt **keine spaltengenaue Quelle** — `ALL_DEPENDENCY_COLUMNS`
>   existiert nicht, und unter allen `SYS`-Sichten mit `DEPENDENC` im Namen
>   ist keine spaltenbezogene. `DependencyInfo.columns` bleibt deshalb leer,
>   womit der genannte Wächter greift: eine Spaltenänderung unter einer
>   Oracle-Sicht blockt. Das ist gewollt — dieselbe Lage wie bei MySQL.
> - **Jede Sicht trägt mindestens eine `ALL_DEPENDENCIES`-Zeile**, selbst
>   eine über `dual` (dort `PUBLIC.DUAL` als `SYNONYM`). Gar keine Zeile
>   heißt deshalb nicht „hängt von nichts ab", sondern fehlende Sichtbarkeit
>   → `INCOMPLETE_PRIVILEGE`, und der Planer blockt `ReplaceView` statt zu
>   raten. Leere In-Schema-Listen bei vorhandener Zeile sind dagegen
>   `EMPTY_VERIFIED`.
>
> Beim Bau fiel ein **ausgelieferter Defekt in einem anderen Dialekt** auf:
> `ObjectRenamePolicyRegistry` führt nur PostgreSQL/MySQL/SQLite und greift
> mit `getValue` zu — für **MSSQL** wirft das `NoSuchElementException` statt
> einen Blocker zu liefern, und `OperationMapper` ruft die Rename-Folds ohne
> Dialekt-Wächter auf. Ein Rename-Overlay, das für MSSQL eine Sicht, Sequenz,
> Routine oder einen Trigger mappt, bricht den Lauf ab. Nicht hier behoben
> (MSSQLs Policy-Inhalt bräuchte eigene Messungen gegen SQL Server):
> [`mssql-object-rename-policy-missing.md`](../done/mssql-object-rename-policy-missing.md).
> Der **Absturz** selbst ist es doch: `forDialect` liefert für einen
> Dialekt ohne Policy jetzt einen `OBJECT_RENAME_UNSUPPORTED`-Blocker
> statt `NoSuchElementException`. Das braucht keine Messung, und einen
> bekannten Abbruch als Ticket weiterleben zu lassen wäre die falsche
> Reihenfolge.
>
> **Das Review fand einen P1, den ich selbst gebaut hatte.** Die
> Spalten-Reprojektion gab ihre `absorbedViews` zurück, aber der Weg
> dorthin verlor sie: `RenameColumnProjection` hatte das Feld gar nicht,
> `projectColumns` hängte nur `explicit` an. Ergebnis wäre ein Plan mit
> `DropView` + `CreateView` **und** einem dritten, an nichts geketteten
> `ReplaceView` auf demselben Objekt gewesen. Der Vertrag steht wörtlich
> im selben Modul (`RenameProjection.absorbedViews`) und ist für den
> Tabellen-Pfad per Test festgenagelt — für den neuen Spalten-Pfad gab es
> kein Gegenstück. Der neue Test fährt deshalb durch `DiffPlanner.plan`
> statt an der Policy vorbei; eine Sabotage der Durchreichung bringt ihn
> zum Fallen.
>
> Zwei weitere Befunde wären stille Brüche gewesen: eine Sicht, die ihre
> Tabelle über ein **Synonym** erreicht, galt als „verifiziert leer" (der
> Reprojector hätte beim Rename nichts gefunden), und eine Sicht mit
> **unbrauchbarer** Projektion wurde still übersprungen statt geblockt.
> Umgekehrt eskalierte eine im selben Lauf **gelöschte, unbeteiligte**
> Sicht den Spalten-Rename auf den destruktiven Drop+Add-Pfad — das ist
> jetzt ausgenommen, weil es dort nichts neu zu projizieren gibt.

> **Status-Update 2026-09-06 (Slice 5e-2):** der Gate-Fall — `schema
> migrate` ist für Oracle nutzbar. `MigrateRendererRegistry` liefert den
> Renderer statt `null`, `DialectCommandGate` führt `SCHEMA_MIGRATE` nicht
> mehr, und die drei `error("unreachable")`-Stubs auf dem Migrate-Pfad sind
> weg (Rename-Policies in 5e-1, `CheckPreflightProbeRunner` hier).
>
> **Neu: ein `canonicalizeGeneration`-Hook im Fingerprint-Vertrag.** Damit
> ist der Slice-4a-Befund gelöst, und zwar dort, wo er hingehört: nicht
> Oracle-lokal, sondern als dritte Achse neben `canonicalizeType` und
> `canonicalizeIndex`. Die Faltung selbst hängt an einer neuen Fähigkeit
> `DialectCapabilities.namesIdentitySequences`, und die ist gemessen, nicht
> angenommen — vier Belege gegen `gvenzl/oracle-free:23-slim-faststart`:
> - `GENERATED ALWAYS AS IDENTITY (SEQUENCE NAME s)` → `ORA-02000`
>   (PostgreSQL kennt genau diese Form, Oracle nicht),
> - `… AS IDENTITY USING <eigene_sequenz>` → `ORA-03076`,
> - der Name ist **nicht einmal stabil**: dieselbe Tabelle gelöscht und
>   identisch neu angelegt ergab `ISEQ$$_73345`, dann `ISEQ$$_73349`,
> - nachträglich umbenennen → `ORA-32799: cannot rename a system-generated
>   sequence`.
>
> Der Name ist also weder vergebbar noch stabil noch korrigierbar. Ihn im
> Abdruck zu führen hieße, jede frisch angelegte Oracle-IDENTITY-Spalte
> nach `--execute` als Drift zu melden. `schema compare` bleibt streng und
> zeigt ihn weiterhin — dieselbe Grenze wie bei Typ- und Index-Projektion.
>
> **`supportsCurrentValuePreserve` steht jetzt auf `true`**, und Oracle ist
> in `PRESERVE_DIALECTS`. Beides folgt aus der Korrektur am 5d-Eintrag
> oben: das Feld hat keinen Leser, die normative Quelle ist Abschnitt 9 der
> `neutral-model-spec.md`, und der Eintritt in die Dialektliste ändert
> nicht *ob* ein Preserve-Kandidat blockt, sondern *womit* — jetzt mit
> `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`, was zutrifft, statt mit
> „not supported on ORACLE", was seit 5d falsch war. Der atomare
> Ausführungspfad fehlt weiterhin; das wäre ein eigener Schnitt nach dem
> Muster der Phase C.4 der anderen drei Dialekte.
>
> Der in Slice 1a gesetzte Gate-Ablehnungsfall für `schema migrate` ist
> nach der Vorgabe der Testdatei selbst in einen Funktions-E2E gekippt
> (`OracleSchemaMigrateE2ETest`); in `OracleCommandGateE2ETest` bleibt nur
> noch `data profile` (Slice 11). Die CHECK-Preflight-Sonde ist gegen ein
> echtes Oracle belegt (bestandene Prüfung, gezählte Verletzerzeilen,
> Sondenfehler) — eine gestubbte Verbindung könnte dafür nichts zeigen.
>
> Im Anwenderhandbuch stand „`schema migrate` … folgt"; das ist mit diesem
> Sub-Slice falsch geworden und nachgezogen, samt der Liste der Objektarten,
> die für Oracle noch benannt blocken.

> **Nachtrag zum 5e-2-Review:** der Gate-Fall hat drei Zusicherungen
> freigelegt, die bis dahin **das Gate** getragen hat, nicht die Renderer:
> - **Partitionierung.** Der Generate-Pfad legt eine partitionierte Tabelle
>   flach an und meldet `E055` — dort liest der Anwender ein Skript, bevor
>   er es ausführt. Auf dem Migrate-Pfad wäre dasselbe eine stille
>   Layout-Änderung an einer Tabelle, die partitioniert sein sollte, und
>   das Anwenderhandbuch sagt an dieser Stelle ausdrücklich Abbruch zu.
>   `CreateTable` blockt jetzt (`ORACLE_PARTITIONING_UNSUPPORTED`).
> - **Spatial.** `canGenerateSpatial()` wertet **nur** der Generate-Pfad
>   aus (`AbstractDdlGenerator`); der Diff-Pfad fragt es nie und hätte
>   `SDO_GEOMETRY` aus der Typtabelle gerendert — für eine Fähigkeit, die
>   das Projekt für Oracle als ungescoped führt. `CreateTable`, `AddColumn`
>   und `AlterColumnType` blocken jetzt (`ORACLE_SPATIAL_UNSUPPORTED`).
> - **Die Handbuch-Liste war zweimal falsch.** Bitmap-Indizes blocken
>   nicht (das neutrale Modell kennt den Typ gar nicht — jeder Nicht-BTREE
>   rendert als B-Tree mit `W102`), funktionsbasierte Indizes sind nicht
>   darstellbar (`IndexColumn` hat kein Ausdrucksfeld). Beides stand als
>   Zusicherung im Handbuch und ist ersetzt durch das, was wirklich blockt.
>
> **Der neue Hook war sabotierbar grün.** `canonicalizeGeneration` ist an
> vier Nähten ein Parameter mit Default `{ it }`; ein Aufrufer, der ihn
> weglässt, kompiliert. Kein Test hätte das bemerkt — der
> Kanonisierer-Test ruft die Funktion direkt auf. Der neue
> `SchemaMigrateGenerationCanonicalizationWiringTest` prüft deshalb, was
> der Runner dem Planer tatsächlich mitgibt; das Entfernen des Arguments
> bringt ihn zu Fall (verifiziert).
>
> **Meine Begründung für das Capability-Flag war falsch.** Sie sagte,
> PostgreSQL könne den Sequenznamen über `(SEQUENCE NAME …)` vergeben —
> `SEQUENCE NAME` kommt im PG-Renderer nirgends vor. PG schreibt den Namen
> ebenso wenig wie Oracle und liest ihn beim Reverse trotzdem, sodass die
> Drift dort genauso auftritt. Sie zu schließen ändert bestehende
> PG-Fingerabdrücke und damit die Gültigkeit erzeugter Rollback-Artefakte
> — das ist eine Entscheidung über Artefakt-Kompatibilität, kein Beifang
> des Oracle-Rollouts:
> [`pg-identity-sequence-name-fingerprint.md`](../done/pg-identity-sequence-name-fingerprint.md).
> Der Vermerk stand im 4a-Ticket und wäre mit dessen Löschung
> verschwunden.
>
> Zwei weitere Befunde als Ticket, beide dialektübergreifend und nicht von
> diesem Slice verursacht:
> [`migrate-spatial-profile-not-validated.md`](../done/migrate-spatial-profile-not-validated.md)
> (ein Tippfehler in `--spatial-profile` fällt auf dem Migrate-Pfad still
> auf den Default zurück) und
> [`check-preflight-probe-duplication.md`](../done/check-preflight-probe-duplication.md)
> (die fünfte zeichengleiche Kopie derselben Sonde).

> **Status-Update 2026-09-06 (Slice 5e-3):** Oracle ist im
> Cross-Dialekt-Matrix-Sweep. `MatrixCell.ALL_DIALECTS` führt es, und der
> Sweep fährt damit jede gepinnte Workstream-Fixture auch gegen den
> Oracle-Renderer.
>
> **Der erste Lauf legte eine zweite Registry frei.** Acht Zellen fielen
> mit „No renderer registered for dialect ORACLE" — `MatrixSweepRunner`
> hält seine eigene Dialekt→Renderer-Zuordnung, unabhängig von
> `MigrateRendererRegistry`. Ein Dialekt, der in der CLI verdrahtet ist,
> ist im Sweep noch nicht verdrahtet; das steht jetzt als Kommentar an der
> Stelle, damit der sechste Dialekt es nicht wieder herausfindet.
>
> Nach der Verdrahtung blieben **zwei** Zellen: `D.3/oracle/positive`
> (Materialized Views) und `E.2/oracle/positive` (Trigger) — beide mit
> benanntem `DIALECT_UNSUPPORTED_OPERATION` statt unvollständiger DDL. Die
> übrigen sechs (G.1, G.2, G.3, A.1, F.5 und `D.3/blocker`) laufen ohne
> Zutun durch.
>
> Beide Carve-outs sind **`permanent: false`**, und der Unterschied zu
> MSSQL ist der Punkt: SQL Servers D.3-Carve-out ist strukturell („has no
> MATERIALIZED VIEW and never will"). **Oracle hat Materialized Views** —
> die Zelle wird pinnbar, sobald Slice 10 sie baut, Trigger entsprechend
> mit Slice 9.
>
> Der Trigger-Carve-out verwies als Deckung auf
> `OracleDiffDdlGeneratorTest` — dort stand für `CreateTrigger` aber gar
> nichts, nur für Materialized Views. Die Zusage ist jetzt belegt statt
> behauptet.
>
> **Eine Lücke im Sweep selbst geschlossen:** ein Dialekt, der nicht in
> `ALL_DIALECTS` steht, erzeugt keine Kandidaten und damit auch keine
> `MATRIX_GAP`-Meldung — die Abdeckung schrumpft still, und alles bleibt
> grün. Genau so kam Oracle bis hierher gar nicht im Sweep vor. Ein Test
> pinnt jetzt `ALL_DIALECTS` gegen `DatabaseDialect.entries` (Sabotage
> verifiziert).
>
> **Doku:** `connection-config-spec.md` führte Oracle als „Oracle
> (geplant)" — Statussprache, die in `spec/` ohnehin nichts zu suchen hat
> und seit 5e-2 auch falsch ist. Das Administrationshandbuch kannte Oracle
> gar nicht; es beschreibt jetzt die URL-Form (Pfadteil ist der
> **Service-Name**, nicht die SID, Port-Default 1521), und die
> Treibermodul-Liste im Entwickler-Guide führt `driver-oracle`.

> **Nachtrag zum 5e-3-Review:** der Sweep prüfte für Oracle weniger, als
> es aussah. Gemessen: **fünf der sechs grünen Zellen bleiben grün, wenn
> der Renderer gar nichts emittiert**, und alle sechs blieben grün, wenn
> dort der MSSQL-Renderer stünde — der Sweep vergleicht nur Exit-Codes.
> Die Zuordnung Dialekt→Renderer ist deshalb jetzt selbst gepinnt
> (Sabotage mit dem MSSQL-Renderer verifiziert).
>
> **Meine Carve-out-Begründung war empirisch widerlegt, bevor ich sie
> schrieb.** Sie sagte, die E.2-Zelle werde pinnbar, sobald Slice 9 die
> Trigger-Rümpfe liest. MSSQL **hat** Slice 9 — und die Zelle ist trotzdem
> gecarvt: die geteilte Fixture trägt `source_dialect: postgresql`, und
> jeder Dialekt, der Rumpf-Portabilität prüft, blockt daran. Oracle liefe
> in dieselbe Wand. Der Carve-out steht jetzt auf `permanent: true` mit dem
> tatsächlichen Grund, und MSSQLs Eintrag — der seit Slice 9 sachlich
> falsch ist — gleich mit, bevor die Begründung ein drittes Mal kopiert
> wird.
>
> **Slice 5 galt als fertig ohne einen einzigen Live-Beleg für
> `schema migrate`.** Der Diff-Pfad war ausschließlich gegen Unit-Tests und
> einen Datei-Modus-E2E belegt; MSSQL hatte den Round-Trip über die echten
> Runner in seiner Abnahme. Der nachgezogene
> `OracleMigrateRoundTripIntegrationTest` fand sofort zwei Dinge, die kein
> Unit-Test zeigen konnte:
> - **Die system-generierte Identity-Sequenz erschien im Reverse als
>   eigenständige Sequenz.** `ALL_SEQUENCES` führt `ISEQ$$_n` wie jede
>   andere; ungefiltert trägt jedes zurückgelesene Schema mit
>   IDENTITY-Spalte eine Sequenz, die im Soll nie steht — `schema migrate`
>   plante ein `DROP SEQUENCE`, das Oracle ohnehin ablehnt (`ORA-32793`).
>   Jetzt über `ALL_TAB_IDENTITY_COLS` ausgeschlossen, wie PostgreSQL es
>   über `pg_depend` tut. Das wirkte schon auf `schema reverse`, seit
>   Slice 1.
> - **Zwei Schreibweisen für dieselbe Identity-Spalte.** Der Reverse liefert
>   `integer + generation`; die naheliegende Form `identifier +
>   auto_increment` plant gegen eine **unveränderte** Tabelle ein
>   `AlterColumnType`, das mit `ORACLE_ADD_IDENTITY_UNSUPPORTED` blockt
>   (gemessen: Exit 8 gegen Exit 0). Im Fingerabdruck sind beide Formen
>   äquivalent, im Vergleich nicht:
>   [`identity-column-shape-mismatch.md`](../done/identity-column-shape-mismatch.md).
>
> Der Round-Trip hängt nachweislich am `canonicalizeGeneration`-Hook aus
> 5e-2: setzt man `namesIdentitySequences` für Oracle auf `true`, fällt er.
>
> Beim Doku-Durchgang fielen weitere Stellen auf, die Oracle als
> nicht-verfügbar führten und seit 5e-2 falsch sind: beide READMEs (Oracle
> ohne „schema migration", während das FAQ es nennt), die nutzersichtbare
> Dialektliste in `ConnectionUrlParser` (seit Slice 1 ohne Oracle — ein
> Tippfehler in einer Oracle-URL las sich als „nicht unterstützt"), die
> Treiber-Runtime-Liste in `releasing.md`, und `neutral-model-spec.md`, das
> **auch MSSQL** noch als „geplant" führte. Ein Testmodul schloss Oracle
> mit einer doppelt falschen Begründung aus (`DataImportWiringTest`:
> `data import` sei gegated — ist es seit Slice 3 nicht — und
> `dataWriter()` sei ein Stub — ist er nicht).
>
> Mein eigener Handbuch-Absatz behauptete außerdem den Alias `ora://`. Den
> führt `spec/connection-config-spec.md` als **Zielbild**;
> `DatabaseDialect.fromString` kennt ihn nicht. In `docs/user/` ist das eine
> falsche Ist-Aussage und deshalb gestrichen.

> **Trigger:** Eigner-Entscheidung, Oracle nach MSSQL (siehe
> [`mssql-dialect-scoping.md`](../done/mssql-dialect-scoping.md)) als nächsten
> Dialekt zu bauen — dem dort etablierten Muster folgend.
> **Lastenheft:** [LF-019](../../../spec/lastenheft-d-migrate.md#lf-019)
> (Kann-Anforderung: „weitere Datenbanksysteme … Oracle, MS SQL Server").
> **ADR:** [0052](../../adr/0052-oracle-fuenfter-dialekt-scoping.md).

## Bestandsaufnahme — was ein fünfter Dialekt kostet (gemessen 2026-09-05)

Umfang der vier bestehenden Dialekte, nur Produktivcode:

| Modul | Zeilen (main) |
| --- | ---: |
| `driver-sqlite` | 10 606 |
| `driver-mssql` | 9 565 |
| `driver-mysql` | 9 538 |
| `driver-postgresql` | 8 598 |
| `driver-common` (geteilt) | 4 656 |

Dazu je Dialekt: ein Profiling-Modul (`driver-*-profiling`), ein
Integrationstest-Modul (`test/integration-*`), Teilnahme an der
Cross-Dialekt-Matrix, Kanonisierer-/Fingerprint-Beteiligung (Postcompare v7
ist dialekt-parametrisiert) und sample-db-Smokes.

**Der Port verlangt** ([`DatabaseDriver`](../../../hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt),
unverändert seit MSSQL): `dialect`, `ddlGenerator()`, `dataReader()`,
`tableLister()`, `dataWriter()`, `urlBuilder()`, `schemaReader()` als
Pflicht. Drei Fähigkeiten haben No-op-Defaults (`transferCompatibility`,
`typeCanonicalizer`, `preGenerationValidator`) — ein Dialekt ist ab Slice 1
registrierbar, ohne alles zu können.

**Querschnittskosten im Hexagon:** `DatabaseDialect`-Vorkommen (grob gezählt,
`hexagon/` + `adapters/`, Produktivquellen): POSTGRESQL 47, MYSQL 62, SQLITE
70, MSSQL 61 — zusammen 240 Vorkommen über die vier bestehenden Dialekte.
`hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DatabaseDialect.kt`
trägt bislang **keinen** `ORACLE`-Wert, auch nicht vorbereitend.

**Keine strukturellen Blocker:**

- [ADR 0037](../../adr/0037-database-agnostic-first-staffelung.md)
  („Database-Agnostic First", Umbau auf 2.0.0 vertagt) nennt Oracle explizit
  als JDBC-Fall, der in den heutigen Port passt (Zeile 91) — dieselbe
  Einordnung wie bei MSSQL.
- Treiber `com.oracle.database.jdbc:ojdbc11` — siehe ADR 0052 Punkt 3
  (Oracle Free Use Terms and Conditions, kein Blocker, aber
  Compliance-Pflicht: Lizenztext mitliefern).
- Materialized Views haben im Neutralmodell bereits eine Heimat (0.9.7
  D.3b-Vollscheibe, in `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/`)
  — Oracle muss hier nur reverse-/generate-seitig andocken, keine
  Modellerweiterung.

## Oracle-Inventar — was anders ist und wohin es fällt

| Fläche | Oracle-Realität | Einordnung |
| --- | --- | --- |
| Auto-Increment | Sequences (klassisch) + `IDENTITY`-Spalten (12c+) | Kern |
| Schemata | Schema = User, kein separates `dbo`-Konzept | Kern |
| Text/Unicode | `VARCHAR2`/`NVARCHAR2`, Byte- vs. Zeichen-Semantik | **Entscheidung im Typmapping** |
| Boolean | kein nativer Typ; Konvention `NUMBER(1)` (0/1) oder `CHAR(1)` ('Y'/'N' u. ä.) | **Entscheidung im Typmapping (Slice 1):** `NUMBER(1)` faltet auf `BooleanType`, analog MySQLs `tinyint(1)`. `CHAR(1)` NICHT — kein ebenso enges Signal wie bei `NUMBER(1)`, ein Einzelzeichen trägt oft einen echten Status-/Kategorie-Code |
| Temporal | `DATE` (**trägt Uhrzeit!**), `TIMESTAMP [WITH [LOCAL] TIME ZONE]` | Kern — `DATE`-Eigenheit dokumentieren |
| UUID | kein nativer Typ; `RAW(16)` oder `VARCHAR2(36)` | **Entscheidung** |
| Binary | `BLOB`, `RAW` | Kern |
| Paginierung | `ROWNUM` (klassisch), `FETCH FIRST n ROWS ONLY` (12c+) | Kern — betrifft DataReader-Chunking |
| Quoting | `"Anführungszeichen"`; **UPPERCASE-Default ohne Quoting** (Gegenteil von PG/MySQL/SQLite) | **Entscheidung** — Case-Fallstrick, siehe ADR 0052 |
| Indizes | Bitmap-Indizes (Slice 6a, gebaut), Function-based-Indizes | Ausbau-Slice (6b) |
| Partitionierung | Range/List/Hash (Slice 7, gebaut); Composite und INTERVAL gemeldet, nicht dargestellt | ✅ |
| Materialized Views | **nativ vorhanden**, echtes Refresh-Modell (FAST/COMPLETE/FORCE, ON COMMIT/ON DEMAND) | Ausbau-Slice (10) — Anschluss ans bestehende Modell, keine Lücke |
| Volltext | Oracle Text, `CTXSYS.CONTEXT` (Slice 8, gebaut); `CTXCAT` nicht gescoped | ✅ |
| Routinen/Trigger (standalone) | PL/SQL, `CREATE OR REPLACE` | Ausbau-Slice |
| **PL/SQL Packages** | Prozedur-/Funktions-Gruppierung, kein Äquivalent in PG/MySQL/SQLite/MSSQL | **Zeitlich unbestimmte Einschränkung** (ADR 0052 Punkt 4/Konsequenzen) — braucht Neutralmodell-Erweiterung, kein Slice mit Liefertermin |

## Die fünf Entscheidungen (getroffen 2026-09-05, siehe [ADR 0052](../../adr/0052-oracle-fuenfter-dialekt-scoping.md))

1. **Testziel: Oracle 23ai Free**, Testcontainer `gvenzl/oracle-free`
   (`slim`/`faststart`, ~700 MB–1,4 GB komprimiert — vergleichbar mit/leichter
   als der MSSQL-Container). Lizenz-/EULA-Mechanik vor Slice 0 zu verifizieren
   (analog `ACCEPT_EULA=Y` bei MSSQL).
2. **Feature-Schnitt: keine Carve-Outs.** Voller Funktionsumfang als Slices
   0–11 (inkl. Profiling-Modul als eigener Ausbau-Slice), analog MSSQL.
3. **JDBC-Lizenz (FUTC): kein Blocker, aber Compliance-Pflicht** — Lizenztext
   im Docker-Image/Release-Assets mitführen (Teil von Slice 0).
4. **PL/SQL Packages: zeitlich unbestimmte Einschränkung, kein numerierter
   Slice mit Liefertermin.** Anders als die anderen Ausbau-Flächen (die alle
   Slices 6–11 mit Lieferzusage sind) bekommt die Package-Gruppierung
   **bewusst keine Slice-Nummer** — ein Slice ohne Termin wäre ein
   Carve-Out mit anderem Etikett und widerspräche Punkt 2. Package-Inhalte
   werden bis auf Weiteres als entpackte Einzelroutinen erfasst (siehe
   [ADR 0052](../../adr/0052-oracle-fuenfter-dialekt-scoping.md), Konsequenzen).
5. **Test-Infrastruktur: Oracle läuft in jedem CI-Lauf mit**, analog MSSQL
   Entscheidung 3. Das neue Integrationstest-Modul (`test/integration-oracle`,
   dem `test/integration-*`-Muster folgend) nimmt automatisch am generischen
   `-PintegrationTests`-Mechanismus in `integration.yml` teil (jeder Push/PR
   auf main, nicht-blockierend neben dem Hauptbuild) — kein Sonderpfad. Der
   `gvenzl/oracle-free`-Container ist laut Recherche vergleichbar mit oder
   leichter als der MSSQL-Container, eine Staffelung ist deshalb nicht
   vorgesehen; RAM-Bedarf ist trotzdem vor Slice 0 real zu messen (siehe
   „Offene Punkte" unten).

## Slice-Schnitt (Entwurf, analog zum MSSQL-Muster)

Dem gewachsenen Muster folgend (Kern zuerst, Ausbau als eigene Slices):

| Slice | Inhalt | Registrierbar ab / liefert |
| --- | --- | --- |
| **0** ✅ | Scoping-ADR (0052), Gradle-Modul `driver-oracle`, Testcontainers-Spike (Connect + `SELECT banner FROM v$version`), FUTC-Lizenztext-Doku, Dependabot-Ignore | — |
| **1** ✅ | `JdbcUrlBuilder` + `SchemaReader`/`TableLister` (Reverse-Read) + `ORACLE`-Enum-Querschnitt + `DialectCommandGate` **wiedereinführen** (die Klasse wurde in Commit `ec3f2d06` beim MSSQL-Slice-10-Abschluss gelöscht, weil ihr letzter Eintrag wegfiel — Oracle braucht sie neu, nicht nur einen weiteren Eintrag) | `schema reverse` funktioniert |
| **1a** ✅ | CLI-E2E-Absicherung in `test/e2e-cli` (Gate-Ablehnungen + `schema reverse`-Subprozess-E2E), analog MSSQL Slice 1a | E2E-Netz vor Slice 2 |
| **2** ✅ | `DdlGenerator` + Typtabelle NeutralType→Oracle-Typen (Kern-Typen; Materialized Views bewusst **nicht** hier, siehe Slice 10) | `schema generate --target oracle` |
| **3** ✅ | `DataReader`/`DataWriter` (Transfer). **3b** (sample-db-Oracle-Leg im Harness, analog [ADR 0013](../../adr/0013-sample-db-sourcing.md)/[ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)) war bewusst **nicht** Teil dieses Slices — separater Folge-Schnitt | `data export/import/transfer` funktioniert |
| **3b** ✅ | Sample-db-Oracle-Leg: Compose-Service, `.env`, Verbindung, `smoke-cross-pg2ora.sh`, `make sample-db-cross-smoke-pg2ora` — **gruen**. Pagila PG → Oracle mit `sqlplus`-angewandter DDL, `--verify OK` (18 Ausschluesse), Paritaet ueber alle 15 Tabellen, datenbelegten Typkonvertierungen und Schluesseltreue. Der Weg dorthin hat **sechs Defekte im ausgelieferten Pfad** aufgedeckt, alle behoben ([`oracle-sample-db-leg.md`](../done/oracle-sample-db-leg.md)) | Cross-Dialekt-Beleg im Harness |
| **4a** ✅ | `NeutralTypeCanonicalizer` + Postcompare-Fingerprint-Beleg (`transferCompatibility` bereits Slice 3) | Vergleichs-Substrat für Slice 5 |
| **4b** ✅ | Cross-Dialekt-sample-db-Smoke, Gegenrichtung Oracle → PG (`smoke-cross-ora2pg.sh`, `make sample-db-cross-smoke-ora2pg`): Hop 0 saet Oracle mit der pg2ora-Mechanik, Hop 1 faehrt reverse/generate/transfer zurueck. **Dreifache Zeilen-Paritaet** (Original == Oracle == Rueckziel), `--verify OK` (16 Ausschluesse), Rueckwaerts-Konvertierungen datenbelegt — `NUMBER(1)` kommt als `boolean` zurueck, `CLOB` als `text`, `TIMESTAMP WITH TIME ZONE` als `timestamptz`, Schluessel ueber beide Hops erhalten | Cross-Dialekt-Beleg in beide Richtungen |
| **5a** ✅ | Diff-Gerüst + Tabellen-/Spalten-Operationen | nur über Tests erreichbar (Registry-Verdrahtung erst 5e) |
| **5b** ✅ | Constraint- und Index-Operationen | dito |
| **5c** ✅ | Views und Custom Types | dito |
| **5d** ✅ | Sequenzen | dito |
| **5e-1** ✅ | Rename-Policies (Objekt + Abhängigkeit) und View-Abhängigkeiten aus `ALL_DEPENDENCIES` — Vorbedingung für den Gate-Fall | dito |
| **5e-2** ✅ | Verdrahtung: `MigrateRendererRegistry`, `DialectCommandGate`, CHECK-Preflight-Sonde, `ColumnGeneration`-Kanonisierung im Fingerprint, `SequenceCapabilityDefaults` | **`schema migrate` ist nutzbar** |
| **5e-3** ✅ | Cross-Dialekt-Matrix-Sweep-Beitritt, Live-Round-Trip, Handbücher (der CLI-E2E kam bereits mit 5e-2) | Matrix-Abdeckung + Live-Beleg + Doku |
| **6a** ✅ | Bitmap-Indizes als eigener neutraler Typ: Reverse (`INDEX_TYPE`), Generate + Diff (`CREATE BITMAP INDEX`), Rückfall + `W102` auf den vier anderen Dialekten, Wire-Format (`schema.json`, Parser). Dazu die Rückfaltung des DESC-Index, der in Oracle intern function-based ist | Bitmap-Treue über alle Dialekte |
| **6b** ✅ | Indizes über echten Ausdrücken: `IndexColumn.expression` im neutralen Modell samt Wire-Format; Generate in allen fünf Dialekten (vier nativ, SQL Server `E057`); Reverse in Oracle **und** PostgreSQL | volle Index-Treue |
| **7** ✅ | Partitionierung Range/List/Hash: Generate, Reverse und Diff über einen geteilten Builder; Grenzwert-Umsetzung (`TO_DATE`) und -Rückfaltung; Fingerabdruck-Projektion für die Felder, die Oracle nicht führt. Composite und INTERVAL werden gemeldet (`R355`/`R356`), nicht dargestellt | Partitionstabellen im Round-Trip |
| **8** ✅ | Volltext: Oracle Text (`CTXSYS.CONTEXT`) für Generate, Reverse und Diff; `SYNC (ON COMMIT)` verpflichtend; mehrspaltig abgelehnt (`E057`); fremde Domain-Indizes gemeldet (`R357`) | Volltext-Indizes Generate + Reverse |
| **9** ✅ | Routinen/Trigger: lesen, erzeugen und migrieren als ein Stück. `ALL_SOURCE` für alle drei Objektarten, Signatur aus `ALL_ARGUMENTS`; Generate und Diff über ein geteiltes Urteil (`OracleRoutineShape`); Trenner je Anweisung statt je Dialekt (`DdlStatement.scriptTerminator`). Was das Modell nicht trägt, wird gemeldet (`R358`–`R363`) | Routinen-Migration |
| **10** ✅ | Materialized Views: Reverse aus `ALL_MVIEWS`, Generate und Diff nativ; `refresh` bekommt ein Vokabular (Methode und/oder Auslöser); `fast` wird gemeldet, weil es ein MV-Log verlangt, das das Modell nicht führt. Dazu ein Defekt im ausgelieferten Stand behoben: MV und MV-Log wurden als Tabellen gelesen | Materialized Views im Round-Trip |
| **11** ✅ | Profiling-Modul `driver-oracle-profiling` (drei Adapter analog den vier anderen Dialekten), live gegen einen Container belegt. `DialectCommandGate` verliert seinen letzten Eintrag und **entfällt** — zum zweiten Mal, siehe Detailabschnitt | `data profile` ist für Oracle nutzbar |
| **12** ✅ | Oracle Spatial: `SDO_GEOMETRY` als echter `NeutralType.Geometry` in Reverse, Generate und Diff, Spatial-Index in POST_DATA, WKB-Datenpfad — siehe Detailabschnitt | Geometriespalten im Round-Trip statt als Text |
| **ohne Nummer** | PL/SQL Packages (Neutralmodell-Erweiterung um Routine-Gruppierung) — **zeitlich unbestimmt, bewusst kein Slice mit Liefertermin** (Entscheidung 4) | Package-Struktur im Round-Trip, sobald angegangen |

Jeder nummerierte Slice endet CI-grün und einzeln nutzbar; die No-op-Defaults
des Ports machen das möglich, ohne UNSUPPORTED-Stopgaps (No-Carveouts-Regel).
PL/SQL Packages sind davon bewusst ausgenommen (Entscheidung 4) — kein
verstecktes else, aber auch keine falsche Terminzusage.

### Kommando-Verfügbarkeit je Slice (analog MSSQL)

| Kommando | Oracle verfügbar ab | bis dahin |
| --- | --- | --- |
| Verbindungsschicht (`oracle://`-URLs, Pool, SSL/TLS) | **Slice 1** | — |
| `schema reverse` (CLI + MCP-Job) | **Slice 1** | — |
| `schema compare` (MCP-Job, via Reverse) | **Slice 1** | — |
| `schema generate` | **Slice 2** | — |
| `export flyway/liquibase/django/knex` | **Slice 2** | — |
| `data export` / `data import` / `data transfer` | **Slice 3** | — |
| `schema migrate` | **Slice 5** | — |
| `data profile` (CLI + MCP-Job) | **Slice 11** | — |

## Slice 5 im Detail — Diff/Migrate für Oracle

### Warum dieser Slice einen Schnitt braucht

`DiffOperation` hat 42 Arten (identisch zur MSSQL-Zählung). Slice 5 ist damit
auch für Oracle größer als Slices 1–4 zusammen. Der Schnitt unten folgt der
Familien-Gliederung, die MSSQLs `renderOp`-Dispatch bereits etabliert hat
(`MssqlDiffTableOps`/`MssqlDiffObjectOps`/`MssqlDiffSequenceOps`/
`MssqlDiffViewOps`/`MssqlDiffCustomTypeOps`) — nicht einer erfundenen
Reihenfolge. Renderer implementieren `DiffDdlGenerator`
(`DiffDdlGenerator.kt`, `generateUp`/`generateDown`, dialektunabhängig).

### Was Slice 5 ausser dem Renderer anfasst

| Naht | Heute | Nach Slice 5 |
| --- | --- | --- |
| `MigrateRendererRegistry.forDialect` | `ORACLE -> null` | liefert `OracleDiffDdlGenerator()` |
| `DialectCommandGate` | `SCHEMA_MIGRATE` gated | Eintrag entfällt (nur `DATA_PROFILE` bleibt, Slice 11) |
| `SequenceCapabilityDefaults` (Oracle-Block) | `supportsCurrentValuePreserve = false` | `true` (Sub-Slice 5d) |
| `MatrixCell.ALL_DIALECTS` | Oracle fehlt in der Liste | beitreten, mit Carve-outs für die Zellen, die Oracle heute schon blockt (Materialized View, Trigger) |
| `RenameProjectionCapabilitiesFactory` | **bereits verdrahtet** (`ORACLE -> RenameProjectionDialect.ORACLE`) | keine Änderung nötig — nur Verifikation unter echtem Sequence-/Tabellen-Rename |
| `spec/neutral-model-spec.md` §9 (Sequence-Capability-Matrix) | Oracle-Spalte fehlt (seit Slice 1 als Beifang vertagt) | Spalte ergänzt, spiegelt `supportsCurrentValuePreserve = true` |

### Oracle-Eigenheiten, die den Schnitt gegenüber MSSQL verschieben

- **DEFAULT ist Spalteneigenschaft, kein benanntes Objekt** (wie PostgreSQL,
  siehe Kommentar in `OracleColumnConstraintHelper`) — MSSQLs teuerster Fund
  (Default-Constraint-Dreischritt mit Katalog-Namenssuche) entfällt
  strukturell. `ALTER TABLE ... MODIFY <col> DEFAULT <x>` direkt möglich.
- **Named-Constraint-Auflösung im Fremdschema entfällt.** Oracle rendert
  PK/UNIQUE/CHECK/FK bereits durchgehend konventionsbasiert benannt
  (`pk_`/`uq_`/`ck_`, seit Slice 2) — kein MSSQL-artiges Auto-Namensproblem.
- **Identity-Änderung geht ohne Tabellen-Neubau — live bestätigt
  (2026-09-06, Wegwerf-Sonde gegen `gvenzl/oracle-free:23-slim-faststart`).**
  `ALTER TABLE t MODIFY id NUMBER(18)` auf einer bestehenden
  `GENERATED ALWAYS AS IDENTITY`-Spalte widened die Präzision in-place,
  Zähler und Bestand bleiben erhalten (Zeilen 1/2 blieben 1/2, die nächste
  bekam korrekt 3 unter der erweiterten Präzision). Ebenso funktioniert eine
  kombinierte Präzisions- **und** Modus-Änderung in einem Schritt
  (`MODIFY id NUMBER(18) GENERATED BY DEFAULT AS IDENTITY`). Anders als
  MSSQLs harte Immutabilität (Msg 156, Rebuild-Zwang) — **Oracles Äquivalent
  zu MSSQLs Sub-Slice 5a-2 (IDENTITY-Rebuild) entfällt** für alle Änderungen
  **an** einer bereits identity-tragenden Spalte.
  Nebenbefund: ein Identity-Retype auf einen Nicht-Numerik-Typ scheitert
  erwartungsgemäß (`ORA-30675`).
  **Nachtrag aus der 5a-Umsetzung (2026-09-06):** die beiden
  Identity-*Übergänge* verhalten sich gegensätzlich und wurden live geklärt.
  `MODIFY <col> DROP IDENTITY` **entfernt** Identity zuverlässig (danach
  nimmt die Spalte explizite Werte an, verifiziert). Identity **hinzufügen**
  geht dagegen gar nicht: `MODIFY <col> GENERATED ALWAYS AS IDENTITY`
  scheitert auf jeder nicht bereits identity-tragenden Spalte mit
  `ORA-30673` — bei leerer, gefüllter und NULL-behafteter Spalte
  gleichermaßen. Dieser eine Fall bräuchte einen Tabellen-Neubau und blockt
  in 5a benannt (`ORACLE_ADD_IDENTITY_UNSUPPORTED`), siehe
  [`oracle-add-identity-requires-rebuild.md`](../done/oracle-add-identity-requires-rebuild.md).
- **Sequenzen: zwei getrennte Welten, nicht verwechseln.** Sub-Slice 5ds
  `CreateSequence`/`AlterSequence`/`AlterSequenceCurrentValue` betreffen
  eigenständige, benannte `SequenceDefinition`-Objekte (Slice 2 rendert sie
  bereits) — nicht die system-generierten Identity-Sequenzen (`ISEQ$$_n`),
  die in Slice 3 das `ORA-32793`-Problem verursachten. Eine normale benannte
  Sequenz sollte `ALTER SEQUENCE seq RESTART START WITH n` klaglos
  akzeptieren — live zu verifizieren, aber strukturell ein anderer Fall.
- **Enum/Domain haben kein Objekt** (wie MSSQL) — `AlterCustomType` fächert
  analog auf jede nutzende Spalte auf. Oracle-DOMAIN faltet laut
  Slice-4a-Fund aber IMMER auf CLOB (kein Basistyp-Versuch, anders als
  MSSQL) — vereinfacht `AlterCustomType` für DOMAIN-Fälle.
- **`CREATE OR REPLACE VIEW FORCE` existiert nativ** (Slice 2 nutzt es
  bereits) — `ReplaceView` ist billig wie bei MSSQLs `CREATE OR ALTER VIEW`.
- **Geblockt bis zum jeweiligen Ausbau-Slice**, identisch zum Generate-Pfad:
  Routinen/Trigger/Aggregate/Composite-Typen (E053/E054, Slice 9),
  Materialized Views (nicht gebaut, Slice 10). **Nicht** dazu gehören
  Bitmap-Indizes und Partitionierung: beide rendert der
  jeweils geteilte Builder seit Slice 6a bzw. 7 in beiden Pfaden. `E057`
  trägt im Index-Pfad nur noch der Volltext-Fall; `E055` nur noch
  Partitionierungs-Formen, die Oracle gar nicht ausdrücken kann.

### Sub-Slice-Schnitt

| Sub-Slice | Operationen | Kern der Arbeit | Abnahme |
| --- | --- | --- | --- |
| **5a** ✅ | `CreateTable`, `DropTable`, `RenameTable`, `AddColumn`, `DropColumn`, `RenameColumn`, `AlterColumnType`, `AlterColumnNullability`, `AlterColumnDefault`, `AddPrimaryKey`, `DropPrimaryKey` | Gerüst (Dispatch UP/DOWN, RenderContext, SqlBuilders). Kein Default-Dreischritt nötig. `RENAME TO`/`RENAME COLUMN` sind native Syntax. Identity-Typ-/Modus-Änderungen live bestätigt in-place — kein Rebuild-Zweig; das *Hinzufügen* von Identity blockt benannt (siehe oben) | Unit-Tests je Operation und Richtung |
| ~~5a-2~~ | — | **Entfällt** — die Live-Sonde bestätigte, dass Oracle Identity-Typänderungen per `ALTER TABLE ... MODIFY` in-place erlaubt (siehe oben); MSSQLs Rebuild-Sub-Slice hat kein Oracle-Äquivalent. Der einzige Rest-Fall (Identity *hinzufügen*) ist in [`oracle-add-identity-requires-rebuild.md`](../done/oracle-add-identity-requires-rebuild.md) ausgelagert | — |
| **5b** ✅ | `AddConstraint`, `DropConstraint`, `AddIndex`, `DropIndex` | Nur B-Tree-Indizes — ein nicht-BTREE-Indextyp rendert als B-Tree mit `W102` (so wie im Generate-Pfad, `spec/ddl-generation-rules.md`); Constraint-Namen kommen aus dem Operations-Payload, kein Katalog-Lookup nötig. Kein `WITH CHECK`-Äquivalent: Oracle validiert per Default gegen den Bestand (siehe oben) | Unit-Tests je Operation und Richtung |
| **5c** ✅ | `CreateView`, `ReplaceView`, `DropView`, `RenameView`, `CreateCustomType`, `AlterCustomType`, `DropCustomType` | `CREATE OR REPLACE VIEW FORCE`; `AlterCustomType` fächert auf nutzende Spalten auf, DOMAIN aber immer → CLOB | Unit-Tests je Operation und Richtung |
| **5d** ✅ | `CreateSequence`, `AlterSequence`, `DropSequence`, `RenameSequence`, `AlterSequenceCurrentValue` | `ALTER SEQUENCE ... RESTART START WITH n` (live verifizieren); `supportsCurrentValuePreserve` → `true`; explizit NICHT identity-backed Sequenzen (bleibt Slice 3s Domäne) | Live-Test pinnt die gemessene Sequenz-Semantik; `neutral-model-spec.md` §9 bekommt die Oracle-Spalte |
| **5e-1** ✅ | — | Rename-Vorbedingung: `OracleRenameDependencyPolicy` + `OracleObjectRenamePolicy` (beide Registries) und View-Abhängigkeiten aus `ALL_DEPENDENCIES`. Oracle-Besonderheit: auch `classifyColumnRename` projiziert Sichten neu | Unit-Tests + Planer-Durchlauf, der die View-Absorption pinnt |
| **5e-2** ✅ | — | Verdrahtung: `MigrateRendererRegistry` liefert den Oracle-Renderer, `DialectCommandGate` verliert `SCHEMA_MIGRATE`, Oracle-CHECK-Preflight-Sonde, `canonicalizeGeneration`-Hook im Fingerprint, `supportsCurrentValuePreserve` → `true` und Beitritt zu `PRESERVE_DIALECTS`. Alle drei Vorbedingungen damit erledigt (Rename-Policies in 5e-1, Fingerprint-Drift und `CheckPreflightProbeRunner`-Stub hier) | **`schema migrate` ist für oracle nutzbar** |
| **5e-3** ✅ | — | Beitritt zum Cross-Dialekt-Matrix-Sweep (Carve-outs auf D.3/E.2), Live-Round-Trip über die echten Runner, Handbücher und `connection-config-spec` | Matrix-Abdeckung + Doku |

## Slice 6 im Detail — Indizes

### Warum der Schnitt bei 6a/6b liegt

Eine Messung gegen `gvenzl/oracle-free:23-slim-faststart` (2026-09-06) zeigt,
dass „function-based" und „bitmap" in `ALL_INDEXES.INDEX_TYPE` **zwei
unabhängige Achsen** sind, nicht eine Liste:

| Angelegt als | `INDEX_TYPE` | Spalte laut `ALL_IND_COLUMNS` |
| --- | --- | --- |
| `CREATE BITMAP INDEX (status)` | `BITMAP` | `STATUS` |
| `CREATE INDEX (UPPER(nm))` | `FUNCTION-BASED NORMAL` | `SYS_NC00006$` |
| `CREATE BITMAP INDEX (LOWER(status))` | `FUNCTION-BASED BITMAP` | `SYS_NC00007$` |
| `CREATE INDEX (amt DESC)` | `FUNCTION-BASED NORMAL` | `SYS_NC00005$`, `DESCEND=DESC` |
| `CREATE UNIQUE BITMAP INDEX (amt)` | — | **ORA-00968** |

Daraus folgt der Schnitt: die Bitmap-Achse ist eine reine
Zugriffsmethoden-Frage und über alle fünf Dialekte darstellbar (6a). Die
Ausdrucks-Achse verlangt dagegen ein Feld im neutralen Modell, das es nicht
gibt — `IndexColumn` trägt Name, Richtung und Prefix-Länge, keinen Ausdruck
(6b).

Die vierte Zeile ist der Grund, warum 6a mehr umfasst als „Bitmap": ein
absteigender Index ist in Oracle **intern** function-based, und sein
Ausdruck ist nur der Spaltenname. Ohne Rückfaltung liest der Reverse dort
`SYS_NC00005$` — einen Spaltennamen, den kein Ziel kennt. Das war der Befund
aus `oracle-desc-index-becomes-function-based.md` (mit 6a erledigt und
entfernt).

### Was 6a anfasst

- **Reverse:** `OracleMetadataQueries.scanIndexes` liest `INDEX_TYPE` mit und
  löst `ALL_IND_EXPRESSIONS` auf. Ein Ausdruck, der nur aus einem zitierten
  Bezeichner besteht, ist die Spalte selbst und wird zurückgefaltet; jeder
  andere ist ein Ausdruck; seit 6b traegt ihn das Modell.
- **Neutrales Modell:** `IndexType.BITMAP`, dazu `spec/schema.json` und
  `toIndexType()`. Beide Listen waren handgepflegt — ein neuer Enum-Wert war
  schreibbar, aber nicht lesbar. Zwei erschöpfende Tests über
  `IndexType.entries` schließen das für künftige Zuwächse.
- **Generate + Diff (Oracle):** `CREATE BITMAP INDEX` über den geteilten
  `OracleIndexDdlBuilder`; `unique` + `bitmap` fällt auf einen eindeutigen
  B-Tree zurück (ORA-00968) mit `W102`.
- **Die vier anderen Dialekte:** gewöhnlicher Index + `W102` über den
  geteilten `BitmapIndexFallbackNote`. Für SQLite ist das eine bewusste
  **Ausnahme** von seiner pauschalen Nicht-BTREE-Regel: `gin`/`gist`/`brin`
  lässt es weg, weil sie ohne ihre Zugriffsmethode nichts mehr leisten — ein
  Bitmap-Index dagegen liegt über gewöhnlichen Spalten.

### Was 6b gebracht hat

`IndexColumn` traegt jetzt einen Ausdruck (`expression`), wie
`IndexDefinition.where` rohen SQL-Text. Entscheidend war die Trennung von
`columnNames` und `keyLabels`: ein Ausdruck darf **nicht** als Spaltenname
gelesen werden — genau die Fehlerklasse, die 6a im Oracle-Reverse gefunden
hat. Typ-Nachschläge sehen ihn deshalb nicht; für die Namensbildung eines
anonymen Index wird er verkürzt.

Zwei Annahmen hat der Live-Test widerlegt bzw. bestätigt:

- **MySQL** braucht wirklich zwei Klammernpaare; einfach geklammert lehnt
  der Server ab (beide Richtungen gemessen).
- **PostgreSQL verlor einen Ausdrucks-Index bisher vollständig.** Die
  Abfrage verband `pg_attribute` über `attnum`, und eine Ausdrucks-Position
  trägt dort `0` — der INNER JOIN liess den ganzen Index wegfallen, ohne
  Fehler (`null` statt eines Index, live gemessen). Da PostgreSQL der
  Herkunftsdialekt ist, aus dem ein solcher Index am ehesten stammt, ist der
  Lesepfad hier mitgeändert worden statt vertagt.

SQL Server bleibt außen vor: T-SQL indiziert nur eine persistierte
berechnete Spalte, und die anzulegen wäre eine Änderung an der Tabelle statt
am Index — abgelehnt mit `E057`, nicht geraten.

### Was die unabhängige Prüfung gefunden hat

Ein Blocker und vier ernste Befunde — der Blocker ist der lehrreichste:

**Das Feature war über die CLI gar nicht erreichbar.** Die
Schema-Validierung prüft jeden Indexschlüssel gegen die Spaltenliste, und
ein Ausdruck steht dort nie — `E005`, Exit 3, **vor** jedem Generator. Grün
war der Bau, weil sämtliche neuen Tests die Generatoren direkt aufriefen und
keiner den Runner. Das Beispiel aus dem Handbuch wäre nicht ausführbar
gewesen.

Die übrigen vier haben eine gemeinsame Ursache: die Naht wurde im **Modell**
gezogen, aber nicht überall dort nachgezogen, wo das Modell gelesen wird.

- **Jeder Dialekt hat zwei Index-Renderer** — einen für `schema generate`,
  einen für den Diff-Pfad. Umgestellt war nur der erste; der zweite quotete
  den Ausdruck als Bezeichner (PG, SQLite, MySQL) und legte damit einen
  Index auf eine Spalte, die es nicht gibt. Es gibt jetzt **eine** geteilte
  Quelle (`renderKey`).
- **Vier weitere Stellen bilden anonyme Indexnamen** im Diff-Pfad und trugen
  den rohen Ausdruck in den Bezeichner.
- **Ein eindeutiger Ausdrucks-Index ging verloren.** Die Hebe-Helfer lesen
  `IndexProjection.columns` roh: einspaltig wurde er herausgefiltert (als
  „schon gehoben"), mehrspaltig zu einer UNIQUE-Constraint über dem
  Ausdruckstext. `CREATE UNIQUE INDEX … (UPPER(nm))` ist ein verbreitetes
  Oracle-Idiom.
- **MySQLs Reverse stürzte ab** (`COLUMN_NAME` ist bei einem funktionalen
  Schlüssel `NULL`, blind gecastet) — ein bestehender Fehler, den das
  Handbuch aber als funktionierend beschrieben hätte.

Zwei Messungen kamen aus der Prüfung und haben das Rendern verändert:
PostgreSQL **verlangt** Klammern für jeden Ausdruck, der kein bloßer
Funktionsaufruf ist (`(nm || 'x')` ist ein Syntaxfehler), und Oracle nimmt
die geklammerte Form ebenfalls an und gibt sie ohne Klammern zurück. Damit
gilt eine Regel für alle vier statt einer MySQL-Sonderbehandlung.

### Was offen bleibt (6b)

- **Der Ausdruckstext driftet.** PostgreSQL gibt `upper(nm)` als
  `upper(nm::text)` zurück, Oracle normiert Klammern und Leerzeichen weg.
  Der Fingerabdruck hasht den Text, also konvergiert ein wiederholter
  `migrate --execute` nicht. Ausblenden hilft hier **nicht** — der Ausdruck
  ist die Aussage des Index; es braucht eine Kanonisierung, und das ist ein
  eigener Entwurf:
  [`raw-sql-text-drift.md`](../open/raw-sql-text-drift.md).
- **SQLite liest sie unvollständig zurück.** Der Katalog führt den
  Ausdruckstext nicht; er steht nur im ursprünglichen `CREATE`-Text:
  [`sqlite-expression-index-reverse.md`](../done/sqlite-expression-index-reverse.md).

## Slice 7 im Detail — Partitionierung

### Was die Messung entschieden hat

Gemessen gegen `gvenzl/oracle-free:23-slim-faststart` (2026-09-06):

| angelegt als | `HIGH_VALUE` |
| --- | --- |
| `RANGE` auf `DATE` | `TO_DATE(' 2024-01-01 00:00:00', 'SYYYY-MM-DD HH24:MI:SS', 'NLS_CALENDAR=GREGORIAN')` |
| `RANGE` mehrspaltig | `10, 100` bzw. `MAXVALUE, MAXVALUE` |
| `LIST` | `'A', 'B'` bzw. `DEFAULT` |
| `HASH` | `null`, Partitionsnamen `SYS_P679…` |
| `INTERVAL` | `PARTITION_COUNT` = 1048575 |

Vier Folgerungen, die den Schnitt bestimmt haben:

1. Die `TO_DATE`-Form trägt **selbst Kommata**. Ein mehrspaltiger Wert darf
   deshalb nur auf oberster Ebene getrennt werden — dafür gibt es bereits
   `PartitionBoundScanner`.
2. Oracle-**HASH** führt weder Modulus noch Remainder. Die Felder des
   neutralen Modells bleiben leer, und der Fingerabdruck muss sie ausblenden,
   sonst driftet jeder Round-Trip.
3. **LIST kann `DEFAULT`** — anders als MySQL, das die Partition verwerfen
   muss (`E063`). Oracle steht dem Modell hier näher als MySQL.
4. **INTERVAL und Composite** haben keine neutrale Entsprechung. Sie werden
   gemeldet (`R355`/`R356`), nicht geraten.

### Was der Live-Test gefunden hat

Der erste Entwurf rendete den Grenzwert wörtlich. Das lief gegen die eigene
Fixture, weil die Oracle-Syntax enthielt — mit der **kanonischen** Form, die
PostgreSQL und MySQL liefern (`'2024-01-01'`), antwortet Oracle dagegen mit
`ORA-01861`: ein blanker String wird gegen `NLS_DATE_FORMAT` gelesen, per
Default `DD-MON-RR`. Der Renderer setzt Temporalgrenzen deshalb in eine
explizite `TO_DATE`/`TO_TIMESTAMP`-Form; die Sabotage-Gegenprobe zeigt den
Fehler unmittelbar wieder.

Die Zerlegung des Literals teilt sich Oracle jetzt mit MySQL
(`PartitionTemporalLiteral` in `driver-common`) statt sie zu kopieren —
dieselbe Zusammenlegung, die `PartitionBoundScanner` schon einmal erfahren
hat.

### Was die unabhängige Prüfung gefunden hat

Drei Blocker, alle im ersten Entwurf enthalten:

1. **Die Fingerabdruck-Projektion erreichte drei von vier Stellen.**
   `DiffPlanner.endpoint()` hat eine eigene Signatur und blieb übrig — der
   Typalias-Zwang, der die anderen drei erwischt hatte, greift dort nicht.
   Folge wäre gewesen: Artefakt und Post-Compare vergleichen zwei
   **verschieden** projizierte Abdrücke, und `schema rollback` scheiterte an
   einem korrekten Ziel. Schlimmer als gar keine Projektion.
2. **RANGE mit `DEFAULT`-Partition rendete `VALUES LESS THAN ()`**
   (`ORA-14019`). Erreichbar, nicht theoretisch: PostgreSQL setzt `isDefault`
   vor der Strategie-Fallunterscheidung, ein RANGE-Schema mit Catch-all ist
   also eine gewöhnliche neutrale Form. MySQL löst sie identisch als
   `MAXVALUE`.
3. **Der `keyType`-Zweig im Leser war tot.** Oracles Reverse liefert nie
   `NeutralType.Date` (Oracles `DATE` trägt eine Uhrzeit, die Faltung ist
   `date` → `datetime`), also lief die Mitternacht-Abschneidung nie. Eine als
   `'2024-01-01'` geschriebene Grenze driftete damit gegen die zurückgelesene
   `'2024-01-01 00:00:00'` — genau der Drift, den die Projektion verhindern
   soll. Die Faltung sitzt jetzt in der Projektion, wo sie eine
   Dialekt-Eigenschaft ist (`separatesDateFromDateTime`), statt im Leser, wo
   sie geraten wäre.

Dazu die Fingerabdruck-Version: `ALGORITHM` ist auf `v10` angehoben. Der
Oracle-Reverse meldet Partitionierung, die er vorher verschwieg — ein vor
dem Slice erzeugtes Artefakt passte sonst still nicht mehr, und der Betreiber
sähe ein blankes `TARGET_STATE_MISMATCH` statt des Hinweises, es neu zu
erzeugen.

Ein vierter Punkt kam aus einer Messung, die die Prüfung angestoßen hat:
Oracle **nimmt** eine Grenze mit Bruchteilsekunden an und schneidet sie ab —
die Grenze verschiebt sich still. Sie bleibt deshalb unumgesetzt stehen, die
Anweisung scheitert laut (`E061`), derselbe Mechanismus wie beim
Nicht-UTC-Offset.

### Was offen bleibt

- Ausdrucksbasierte Partitionierung und `REFERENCE`/`SYSTEM`-Strategien
  werden nicht gelesen (die Tabelle erscheint unpartitioniert).
- **Der Partitionsbestand einer bestehenden Tabelle lässt sich nicht ändern**:
  `AlterTablePartitions` blockt für Oracle benannt. Neu ist, dass der Fall
  überhaupt erreichbar ist — vorher meldete der Reverse keine Partitionierung.
- MySQL und SQL Server behalten diese Felder im Fingerabdruck — anders als
  hier zunächst vermutet, und zu Recht: ihre Reverse-Leser rekonstruieren
  untere Grenze und Modulus aus der Kontiguität bzw. aus `PARTITIONS n`.
  Nachgemessen und geschlossen in
  [`partition-fingerprint-lossy-dialects.md`](../done/partition-fingerprint-lossy-dialects.md).

## Slice 8 im Detail — Oracle Text

### Was die Messung entschieden hat

Gemessen gegen `gvenzl/oracle-free:23-faststart` (2026-09-07):

| Versuch | Ergebnis |
| --- | --- |
| `INDEXTYPE IS CTXSYS.CONTEXT` auf einer Spalte | OK |
| … auf zwei Spalten | `ORA-29851: cannot build a domain index on more than one column` |
| zweiter CONTEXT-Index auf derselben Spalte | `ORA-29879` |
| `INSERT` + `COMMIT`, dann `CONTAINS` **ohne** `SYNC (ON COMMIT)` | **0 Treffer** |
| dasselbe **mit** `SYNC (ON COMMIT)` | 1 Treffer |
| Katalog | `INDEX_TYPE=DOMAIN`, `ITYP_OWNER=CTXSYS`, `ITYP_NAME=CONTEXT`, `PARAMETERS` trägt die Klausel wörtlich |

Die vierte Zeile ist die wichtigste: ein Volltext-Index ohne
`SYNC (ON COMMIT)` ist nach einer Migration **stumm funktionslos**. Die
Klausel steht deshalb immer, nicht auf Wunsch — die Sabotage-Gegenprobe
lässt den Live-Test mit „0 statt 1 Treffer" fallen.

Die erste Grenze bestimmt den Umfang: das neutrale Modell lässt mehrere
Quellspalten zu (MySQL und SQL Server tragen sie nativ), Oracle nicht. Das
in mehrere Einzelindizes zu zerlegen wäre kein Rückfall, sondern eine andere
Bedeutung — abgelehnt mit `E057`.

### Warum ein zweites Container-Abbild

Die `slim`-Variante hat **kein** Oracle Text: im Oracle-Home fehlt das
`ctx`-Verzeichnis, `CTXSYS` existiert nicht, und die Anweisung scheitert mit
`ORA-29833`. Die Vollvariante bringt es mit, ist aber deutlich größer und
startet langsamer. Nur die Volltext-Spezifikation fährt sie deshalb; alle
übrigen Oracle-Tests bleiben auf `slim`, damit deren Laufzeit unverändert
bleibt.

Dazu kommt eine Betreiber-Voraussetzung, die kein anderer Dialekt hat: der
ausführende Nutzer braucht die Rolle **`CTXAPP`**. Der von Testcontainers
angelegte Nutzer hat sie nicht — der Test vergibt sie als `system`, so wie
ein Betreiber es täte, und das Handbuch nennt sie.

### Was die unabhängige Prüfung gefunden hat

Ein Blocker, und es ist der, den die Prüfung selbst **nicht** verifizieren
konnte, sondern nur vermutete — die Messung gab ihr recht und übertraf sie:

**Ein Oracle-Text-Index legt im selben Schema sieben eigene Tabellen an**
(`DR$<index>$B/C/I/K/N/Q/U`), gewöhnliche Zeilen in `ALL_TABLES`. Der Reverse
hätte für eine Tabelle mit einem Volltext-Index **acht** zurückgegeben. Das
wäre nicht nur kosmetisch gewesen: der Post-Compare hätte nach jedem
`migrate --execute` Drift gemeldet, und `OracleTableLister` — dieselbe
Abfrage — hätte dem Datenpfad Oracle-interne Token-Tabellen zum Kopieren
gegeben. `ALL_TABLES` führt kein Kennzeichen dafür; `ALL_OBJECTS.SECONDARY`
schon (gemessen: `Y` für alle sieben, `N` für die echte Tabelle). Über den
Namen zu filtern wäre die schlechtere Lösung — `DR$` ist keine reservierte
Zeichenfolge.

Zwei weitere Befunde derselben Art wie in Slice 7:

- **`textSearchConfig` fiel beim Rendern weg, ging aber in den Fingerabdruck
  ein.** Das hätte nicht nur Drift gemeldet, sondern den Index bei jedem Lauf
  erneut eingeplant — `TableComparator` führt das Feld ebenfalls. Neue
  Fähigkeit `carriesFullTextConfiguration`.
- **Eine Umbenennung emittierte `CREATE` vor `DROP`.** Für einen
  Volltext-Index ist das auf Oracle fatal (`ORA-29879`: nur ein Domain-Index
  je Spaltenliste), für gewöhnliche Indizes nicht — deshalb fehlte die Kante.
  Sie hängt jetzt an den Spalten, nicht am Namen.

Dazu ein Fehler in meiner eigenen Korrektur: der Blocker für einen nicht
renderbaren Index stand **hinter** dem Emittieren der Tabelle, und dieselbe
Operation kann nicht zugleich gerendert und übersprungen sein
(`operationsRendered and operationsSkipped must be disjoint`). Die Indizes
werden jetzt vorher gerendert und geprüft.

### Was offen bleibt

- `CTXCAT`- und `CTXRULE`-Indextypen sind nicht gescoped; nur `CONTEXT`.
- Mehrspaltiger Volltext über `MULTI_COLUMN_DATASTORE` — verlangt eine
  benannte `CTX_DDL`-Preference, also ein Objekt, das das neutrale Modell
  nicht kennt (dieselbe Lage wie SQL Servers Volltext-Katalog).
- Die Text-Search-Konfiguration wird verworfen (`W154`), nicht auf einen
  Lexer abgebildet — aus demselben Grund; im Fingerabdruck ist sie für
  Oracle ausgeblendet, für MySQL/SQLite/SQL Server noch nicht
  ([Ticket](../done/fulltext-config-fingerprint-lossy-dialects.md)).
- Das `fulltext`-Golden deckt nur den mehrspaltigen **Ablehnungs**-Fall ab,
  weil die geteilte Fixture zwei Quellspalten führt. Die erzeugte Anweisung
  selbst prüft ein Unit-Test auf denselben exakten Text; ein zusätzlicher
  einspaltiger Index in der Fixture änderte die Goldens aller fünf Dialekte.

## Slice 9 im Detail — Routinen und Trigger

### Was die Messung ergeben hat

Gemessen gegen `gvenzl/oracle-free:23`:

| Versuch | JDBC meldet | Objektstatus |
| --- | --- | --- |
| `CREATE ... END;` | OK | **VALID** |
| `CREATE ... END;` + `/` | OK | **INVALID** |
| Routine mit echtem Kompilierfehler | OK | **INVALID** |

Das ist der wichtigste Befund für 9b/9c: **`execute()` meldet Erfolg, obwohl
die Routine nicht übersetzt hat.** Ein angehängter `/` — im Skript nötig,
weil ein PL/SQL-Rumpf selbst `;` enthält — macht sie über JDBC kaputt, und
ein echter Kompilierfehler kommt gar nicht erst an. Ohne Statusprüfung nach
dem Anlegen wäre das eine Migration, die scheinbar gelingt und eine defekte
Routine hinterlässt.

Daraus folgt für 9b eine Entwurfsfrage, die den Slice bestimmt: der
Anweisungstrenner ist **keine Dialekt-Eigenschaft**, sondern eine
Eigenschaft der einzelnen Anweisung. Gewöhnliches SQL endet mit `;` und
verträgt kein `/`; ein PL/SQL-Block braucht im Skript `/` und verträgt es
über JDBC nicht. `DialectCapabilities.batchSeparator` ist global und kann
das nicht ausdrücken.

Der Katalog liefert weiter:

- `ALL_SOURCE` **zeilenweise**, jede Zeile mit ihrem Zeilenumbruch, erste
  Zeile beginnt mit `PROCEDURE`/`FUNCTION` — **ohne** `CREATE OR REPLACE`.
- `ALL_ARGUMENTS` führt den Rückgabewert einer Funktion als Position `0`
  mit `IN_OUT = 'OUT'`.
- `ALL_TRIGGERS.TRIGGER_TYPE` trägt Zeitpunkt und Granularität zusammen
  (`BEFORE EACH ROW`), `WHEN_CLAUSE` kommt **ohne** umschließende Klammern,
  `TRIGGER_BODY` ist eine LONG-Spalte.

### Warum der Slice nicht in Lese- und Schreibteil zerfällt

Der Versuch, zuerst nur den **Rückweg** zu bauen, wurde gebaut und wieder
zurückgenommen. Er bricht `schema migrate`:

Der leere Reverse trug bisher eine **Zusicherung**, auf die sich der
Diff-Pfad verließ — er lieferte keine Routinen, also entstanden nie
Routine-Operationen. Liefert er sie, stuft `OracleDiffDdlGenerator` jede
davon als `UNSUPPORTED` ein, und das ist ein harter Blocker. Der Alltagsfall
genügt: eine Schemadatei ohne Routinen gegen eine Oracle-Datenbank, die eine
hat — der Diff will `DropFunction`, und der Lauf bricht ab.

**Slice 9 muss deshalb in einem Stück landen**: lesen, erzeugen, migrieren.

### Was der Modellvertrag verlangt (vor dem nächsten Anlauf zu lesen)

Der zurückgenommene Versuch hat drei normative Vorgaben verfehlt, weil er
vom Katalog statt vom Modell aus gebaut war. Alle vier anderen Leser halten
sie ein:

- **Kanonische Map-Schlüssel.** `name(richtung:typ,…)` für Routinen,
  `tabelle::name` für Trigger (`spec/neutral-model-spec.md`). Ein Schema mit
  blanken Namen passt zu keinem aus einem anderen Dialekt — jede Routine
  liest sich als Löschen + Anlegen.
- **`body` trägt nur den Rumpf**, nicht die Signatur. Oracle liefert in
  `ALL_SOURCE` beides zusammen; MSSQL hat dafür einen eigenen Scanner
  (`MssqlRoutineBody`).
- **Neutrale Parametertypen**, nicht `NUMBER`/`VARCHAR2` — sie gehen in den
  Schlüssel ein.

Dazu drei Katalog-Fallen, die beim nächsten Anlauf zu berücksichtigen sind:

- `ALL_ARGUMENTS` braucht `data_level = 0`. Ohne das werden die Felder eines
  RECORD- oder `%ROWTYPE`-Arguments zu eigenen Parametern, weil sie eigene
  Positionen ab 1 tragen.
- `base_object_type = 'TABLE'` schließt **INSTEAD-OF-Trigger auf Sichten**
  aus. Das Modell kennt `INSTEAD_OF`, PostgreSQL und SQL Server liefern es —
  Oracle wäre der einzige Dialekt, der sie stumm verliert.
- `UPDATE OF a, b` steht **nicht** in `ALL_TRIGGERS.COLUMN_NAME` — die Spalte
  ist dort leer, und `TRIGGERING_EVENT` sagt nur `INSERT OR UPDATE`. Die
  Spaltenliste steht in `ALL_TRIGGER_COLS` mit `COLUMN_LIST = 'YES'`; ohne
  diese Sicht feuert der wiedererzeugte Trigger auf jede Änderung.

### Nachgemessen — was den Entwurf ändert

| Frage | Messung | Folge |
| --- | --- | --- |
| Trägt `RETURN`/Parameter eine Länge? | `RETURN NUMBER(10)` und `IN VARCHAR2(10)` erzeugen die Routine **INVALID** | Generate rendert Parameter- und Rückgabetypen ausnahmslos unbeschränkt; `ReturnType.precision`/`scale` fallen für Oracle weg |
| Lässt sich eine Routine umbenennen? | `RENAME f TO g` → ORA-03001, `ALTER FUNCTION f RENAME TO g` → ORA-00922 | `RenameFunction`/`RenameProcedure` **blocken**; nur `ALTER TRIGGER … RENAME TO` ist nativ |
| Wo steht der Trigger-Rumpf? | `ALL_TRIGGERS.TRIGGER_BODY` ist `LONG` und in SQL nicht verkettbar; Trigger stehen aber ebenso in `ALL_SOURCE` (`TYPE = 'TRIGGER'`) | Ein Lesepfad für alle drei Objektarten, kein LONG-Sonderweg |
| Was steht in `ALL_SOURCE` Zeile 1? | `FUNCTION p9_calc(…)` — ohne `CREATE OR REPLACE`, in der Schreibweise des Autors | Der Schnitt sucht das erste `IS`/`AS` auf oberster Klammerebene, nicht ein `CREATE` |
| Bleibt der Rumpf wortgleich? | Ja, samt Einrückung und `--`-Kommentar | Anders als bei Sichten und CHECK-Ausdrücken konvergiert der Round-Trip; [ADR 0053](../../adr/0053-vergleich-rohen-sql-texts.md) betrifft Routinen nicht |
| Meldet `execute()` einen Kompilierfehler? | Nein — das DDL gilt als erfolgreich, das Objekt steht auf `INVALID` | Nach dem Anwenden ist `ALL_OBJECTS.status` zu prüfen |
| Sind Triggernamen schemaweit? | Ja: ein zweiter Trigger gleichen Namens auf anderer Tabelle → ORA-04095 | Dieselbe Kollisionsprüfung wie bei SQL Server (`collidingNames`) |

### Was der Rumpf bleibt

PL/SQL im Original. Eine Übersetzung nach PL/pgSQL oder T-SQL wäre ein
Transpiler, den es hier nicht gibt; ein Ziel, das den Dialekt nicht versteht,
lehnt beim Erzeugen ab, statt eine Übersetzung zu erfinden.

### Gebaut

Reverse, Generate und Diff in einem Stück — getrennt gelieferte Teile hätten
sich gegenseitig blockiert: ein Reverse, der Routinen liefert, während der
Diff-Pfad sie `UNSUPPORTED` nennt, bricht `schema migrate` auf **jeder**
Oracle-Datenbank mit einer Routine ab.

| Teil | Wo |
| --- | --- |
| Kopf/Rumpf-Schnitt (PL/SQL-Scanner mit `q'…'`, Kommentaren, quotierten Bezeichnern) | `OracleRoutineBody` |
| Katalogabfragen (`ALL_SOURCE`, `ALL_ARGUMENTS`, `ALL_PROCEDURES`, `ALL_TRIGGERS`, `ALL_TRIGGER_COLS`) | `OracleRoutineQueries` |
| Zusammenbau ins neutrale Modell samt `R358`–`R363` | `OracleRoutineReader` |
| Urteil „darstellbar?", geteilt von Generate und Diff | `OracleRoutineShape` |
| PL/SQL-Hüllen | `OracleRoutineDdl` |
| Diff-Operationen und ihre Sperren | `OracleDiffRoutineOps`, `OracleDiffRoutineGuards` |

Zwei Entscheidungen darin gehen über Oracle hinaus:

- **Der Trenner sitzt an der Anweisung, nicht am Dialekt.**
  `DdlStatement.scriptTerminator` trägt das `/`, das SQL\*Plus hinter einem
  PL/SQL-Block braucht; `DdlScript` hängt es an und lässt es dem
  Batch-Trenner des Dialekts vorgehen. Im `sql`-Feld hat es nichts zu suchen:
  über JDBC gesendet meldet `execute()` Erfolg und lässt die Routine `INVALID`
  zurück. `DialectCapabilities` hatte genau diese Aufteilung vorgezeichnet.
- **`ALL_PROCEDURES.SQL_MACRO`/`POLYMORPHIC` tragen die Zeichenkette `'NULL'`**,
  kein SQL-NULL. Ein Test auf „nicht leer" hätte jede gewöhnliche Routine für
  ein SQL-Makro gehalten und übergangen — die Funktion wäre unerreichbar
  gewesen, ohne dass ein Test es gezeigt hätte.

Nebenbefund, eigenes Ticket:
[`oracle-drop-if-exists-verfuegbar.md`](../open/oracle-drop-if-exists-verfuegbar.md).

### Was der Review gefunden hat

Sieben Befunde, alle behoben — vier davon hätten ungültiges DDL oder eine
nicht konvergierende Migration ergeben:

| Befund | Warum es schiefging | Behoben durch |
| --- | --- | --- |
| `removeSurrounding("(", ")")` an der `WHEN`-Bedingung | Der Katalog liefert `(a) AND (b)`; das fängt mit `(` an und hört mit `)` auf, ohne von einem Paar umschlossen zu sein → `WHEN (a) AND (b)` | Klammern gar nicht mehr abziehen |
| `ALL_ARGUMENTS.DATA_TYPE` bei benutzerdefinierten Typen | Dort steht die **Kategorie** (`OBJECT`, `VARRAY`, `REF CURSOR`), nicht der Name → `IN OBJECT`, kein gültiges PL/SQL | `TYPE_NAME` mitlesen; Kategorien ohne nennbaren Namen als `R360` melden |
| `deterministic = false` aus `DETERMINISTIC = 'NO'` | `NO` ist Oracles Voreinstellung; als `false` abgelegt plante **jeder** Lauf erneut ein `ReplaceFunction` gegen eine Schemadatei, die die Angabe nicht führt | `NO` → `null` |
| `OracleObjectRenamePolicy` blockte alle drei Objektarten | Damit war `ALTER TRIGGER … RENAME TO` — der einzige native Rename, den Oracle hat — unerreichbar, und die Meldung behauptete, d-migrate lese keine Trigger | Trigger auf den nativen Zweig; Funktion/Prozedur blocken jetzt mit dem Oracle-Grund (ORA-03001/ORA-00922) |
| `MigrationFingerprint.ALGORITHM` blieb auf `v10` | Derselbe Fall, den der v10-Eintrag selbst beschreibt: der Reverse liest jetzt Objekte, die er vorher gar nicht meldete → dieselbe Datenbank hasht anders, und ein altes Artefakt meldet ein blankes `TARGET_STATE_MISMATCH` | Anhebung auf `v11` |
| `FOLLOWS`/`PRECEDES` fiel stumm weg | Steht im Kopf, `ALL_TRIGGERS` führt es nicht — der wiedererzeugte Trigger feuerte in anderer Reihenfolge | `ALL_TRIGGER_ORDERING` abfragen, `R361` |
| Trigger auf einer Tabelle eines fremden Schemas | Das Modell trägt nur den blanken Namen; `schema generate` meldete ihn danach als `E018 references non-existent table` — ein Reverse-Ergebnis, das an der eigenen Validierung scheitert | `tableOwner != schema` → `R361` |

Dazu zwei Ausgabewege, die erst durch diesen Slice PL/SQL sehen konnten und es
zerschnitten hätten:

- **Liquibase** trennt ein `<sql>`-Element an `;`. Für T-SQL setzte der
  Exporter `endDelimiter="GO"`, für Oracle nichts — ein PL/SQL-Rumpf wäre an
  jedem inneren Semikolon zerlegt worden. Jetzt bekommt jede Anweisung ein
  eigenes `<sql splitStatements="false">`, sobald ein Block seinen eigenen
  Trenner trägt.
- **Django** verkettete alle Anweisungen zu einem `RunSQL`-Text. Die
  Listenform existierte bereits für SQL Server und greift jetzt auch hier.

Flyway war korrekt: sein Oracle-Parser kennt `/`.

Der Reverse hängt nicht mehr allein an handgeschriebenen Katalogzeilen —
`OracleRoutineIntegrationTest` legt die Objekte in einem echten Oracle an,
liest sie zurück und prüft nach dem Anwenden `ALL_OBJECTS.status`.

Zwei Befunde blieben offen und haben eigene Tickets:
[`oracle-routine-post-apply-status.md`](../open/oracle-routine-post-apply-status.md)
(die Statusprüfung braucht einen neuen Port und zwei Entscheidungen) und
[`oracle-routine-signature-type-narrowing.md`](../open/oracle-routine-signature-type-narrowing.md)
(`CLOB`→`VARCHAR2` und Verwandte, ohne Meldung).

## Slice 10 im Detail — Materialized Views

### Was die Messung ergeben hat

| Frage | Messung | Folge |
| --- | --- | --- |
| Braucht jeder Refresh-Modus ein MV-Log? | Nur `FAST` (ORA-23413 ohne Log). `FORCE` nicht — auch nicht mit `ON COMMIT`; es fällt auf einen vollständigen Refresh zurück | `fast` wird gemeldet (`E053`), alles andere gerendert |
| Was gilt ohne `REFRESH`-Klausel? | `FORCE ON DEMAND` | Die Voreinstellung wird beim Lesen **nicht** abgelegt und beim Erzeugen nicht ausgeschrieben |
| Gibt es `CREATE OR REPLACE MATERIALIZED VIEW`? | Nein (ORA-00922) | Ersetzen ist ein `DROP` plus ein `CREATE`, beide als eigene Anweisung im Plan |
| Wo steht die Abfrage? | `ALL_MVIEWS.QUERY`, eine `LONG`-Spalte — und unverändert, anders als `ALL_VIEWS.TEXT` | Die Spalte steht **zuletzt** in der Auswahl; der Round-Trip hat keine Deparse-Drift |
| Steht eine MV in `ALL_TABLES`? | Ja, unter ihrem eigenen Namen; ihr Log als `MLOG$_<tabelle>`; `SECONDARY` ist bei beiden `N` | Siehe unten — ein Defekt im ausgelieferten Stand |
| Steht eine MV in `ALL_VIEWS`? | Nein | Der Sichten-Reverse braucht keinen Ausschluss, der MV-Reverse eine eigene Abfrage |

### Der Defekt, den die Messung nebenbei aufdeckte

Der Sekundaerobjekt-Filter aus Slice 8 (`ALL_OBJECTS.SECONDARY = 'Y'`) greift
bei Materialized Views und ihren Logs **nicht** — beide stehen mit `N` in
`ALL_TABLES`. Im ausgelieferten Stand las `schema reverse` sie deshalb als
Tabellen, und weil `OracleTableLister` dieselbe Abfrage nutzt, kopierte
`data transfer` den Inhalt einer MV, als wäre er eine Tabelle. Gemessen:
`[MLOG$_sales, mv_rows, mv_totals, sales]` statt `[sales]`.

Behoben über `ALL_MVIEWS`/`ALL_MVIEW_LOGS` — die Katalogsichten, die den
Begriff führen — statt über ein Namensmuster; `MLOG$` ist keine reservierte
Zeichenfolge.

### Der Refresh-Vertrag

`ViewDefinition.refresh` war bis hierher ein Freitextfeld, das kein Dialekt
auswertete. Es bekommt jetzt ein Vokabular, ohne dass das Modell sich ändert:
eine Methode (`complete`, `force`, `fast`, `never`), ein Auslöser
(`on demand`, `on commit`) oder beides. Wer nur den Auslöser nennt, sagt
*wann*, nicht *wie* — die Methode bleibt die Voreinstellung des Dialekts. Die
Bestandsfixture schreibt `on_demand`; Unterstrich und Leerzeichen sind
dieselbe Angabe.

Was nicht in dieses Vokabular fällt, wird gemeldet statt ignoriert — sonst
verschwände eine Absicht des Autors zwischen Lesen und Schreiben.

### Der Zellentausch in der Cross-Dialekt-Matrix

`D.3/oracle/positive` war provisorisch gecarvt, mit der Begründung, dass die
Zelle mit Slice 10 pinnbar wird **und** `D.3/oracle/blocker` dann permanent zu
carven ist. Genau das ist passiert: Oracle rendert MVs nativ, also gibt es
keinen Dialekt-Blocker-Pfad mehr — dieselbe Lage, die PostgreSQL schon führt.

### Was der Review gefunden hat

Sechs Befunde, alle behoben — der erste hätte bei **jedem** Lauf Daten
verworfen:

| Befund | Warum es schiefging | Behoben durch |
| --- | --- | --- |
| Der Round-Trip konvergierte nicht | Der Reverse unterdrückte die Voreinstellung (`null`), das Rendern schrieb sie aus. Eine Schemadatei mit `refresh: on_demand` verglich sich damit dauerhaft als verschieden — und jeder Lauf plante ein `DROP` + `CREATE`, das den materialisierten Bestand verwarf | `ViewRefreshSetting` im Modell: der Parser legt **eine** Schreibweise ab, gleichbedeutende Angaben fallen zusammen |
| `REFRESH NEVER` ist kein gültiges Oracle | ORA-00905; die Klausel heißt `NEVER REFRESH` und steht vor dem Wort | Eigener Renderzweig, gemessen |
| MVs kamen ohne Abhängigkeiten zurück | `ALL_DEPENDENCIES` wurde nur nach `type = 'VIEW'` gefragt; eine MV trägt dort `MATERIALIZED VIEW`. Damit liefen die Wächter leer, die eine verwaiste Sicht verhindern | Beide Objektarten abfragen |
| Der Report nannte ausgeführte Operationen „blockiert" | `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED` feuert dialektblind, sobald `refresh` gesetzt ist — für einen Dialekt, der sie rendert, eine falsche Auskunft über einen erfolgreichen Lauf | Neue Fähigkeit `rendersViewRefreshSetting`; die Regel greift nur noch, wo sie zutrifft |
| Ein Ersetzen wies keinen Datenverlust aus | `ReplaceMaterializedView`/`DropMaterializedView` trugen nur `destructive`; der Rücknahme-Bericht meldete `dataLossPossible = false` für einen Plan, der den ganzen Bestand wegwirft | `dataLossPossible = true` an beiden |
| MV-DDL trug Metadaten-Hinweise | `CREATE MATERIALIZED VIEW … AS` liest beim Erstaufbau alle Basiszeilen, `DROP` vernichtet Daten — „fasst keine Nutzdaten an" stimmt dort nicht | Die gewöhnlichen Oracle-DDL-Hinweise |

Dazu der Zellentausch in der Matrix, den der Review korrigiert hat: die
Blocker-Zelle ist **nicht** unanwendbar geworden, denn `refresh: fast` ist
weiterhin ein echter Oracle-Blocker-Pfad. Die Blocker-Fixture nennt ihn jetzt,
womit **beide** D.3-Oracle-Zellen gepinnt sind statt einer gepinnten und einer
gecarvten.

Offen geblieben und ticketiert:
[`materialized-view-refresh-contract.md`](../open/materialized-view-refresh-contract.md).

### Was offen bleibt

- **Materialized View Logs** haben im neutralen Modell kein Zuhause. Ihre Form
  (`WITH PRIMARY KEY`, `ROWID`, Spaltenliste, `INCLUDING NEW VALUES`)
  entscheidet mit, ob ein schneller Refresh für eine konkrete Abfrage
  überhaupt möglich ist; sie zu erraten hieße, eine Zusicherung zu erfinden.
  Solange sie fehlen, ist `refresh: fast` nicht erzeugbar.
- **`schema refresh materialized-view`** gibt es nicht; der repo-weite
  D.3b-Vertrag führt es als eigenen OOS-Blocker.

## Slice 11 im Detail — Profiling

### Was Oracle anders macht

| Frage | Messung | Folge |
| --- | --- | --- |
| Zählt ein Leerstring? | `''` **ist** NULL in Oracle | `emptyStringCount` kann nur 0 sein; die Werte stehen unter `nullCount`. Das ist die einzige wahrheitsgemäße Antwort, keine Lücke |
| `COUNT(DISTINCT clob)`? | ORA-22849 | LOBs werden über die ersten 4000 Zeichen verglichen — jenseits davon fallen Werte zusammen. Ohne die Projektion gäbe es für LOB-Spalten gar keine Kennzahlen |
| Wie erkennt man reinen Leerraum? | `TRIM('  ')` ergibt den Leerstring und damit NULL | Leerraum ist genau das, was selbst nicht NULL ist, dessen getrimmter Wert aber schon |
| Typverträglichkeit? | `VALIDATE_CONVERSION` liefert 1/0 | Vollständig geprüft ohne Cast-und-Fangen — dieselbe Zusicherung wie `TRY_CONVERT` bei SQL Server |
| Top-N? | Kein `LIMIT`; `FETCH FIRST n ROWS ONLY` | Serverseitig begrenzt, nicht im Speicher |
| Zeitformate? | `TO_CHAR` ohne Maske folgt `NLS_DATE_FORMAT` | Alle Masken stehen ausgeschrieben, sonst fiele dasselbe Profil je nach Anmeldung anders aus |

### Was der Review gefunden hat

Neun Befunde, alle behoben. Drei davon hätten einen Profillauf **ganz**
abgebrochen — es gibt keine Toleranz je Spalte, ein Fehler beendet den Bericht
für die gesamte Datenbank:

| Befund | Warum es schiefging | Behoben durch |
| --- | --- | --- |
| XMLTYPE-Projektion lieferte einen CLOB | `SUBSTR` auf einem CLOB gibt wieder einen CLOB — genau der ORA-22849, den die Projektion vermeiden soll | `XMLSERIALIZE(… AS VARCHAR2(4000))` |
| `LONG`, `LONG RAW`, `BFILE`, Objekttypen | Der Resolver sagte sie zu, das SQL konnte sie nicht anfassen (ORA-00997) | Benannt abgewiesen, statt den Serverfehler durchzureichen |
| `VALIDATE_CONVERSION` auf LOB und TIMESTAMP | Nimmt beide nicht (ORA-43909) — vom Integrationstest gefunden, nicht vom Review | Zeitspalten brauchen keine Prüfung, LOB/RAW gehen über ihre Textform |
| Zeitmaske ohne Bruchteilsekunden | Zwei verschiedene `TIMESTAMP`-Werte fanden denselben Text: `topValues` zeigte ihn zweimal, die Sortierung war bei Gleichstand unbestimmt. `.FF` an einem `DATE` ist dagegen ORA-01821 | Maske je Typ |
| `TO_CHAR` auf Zahlen ohne Maske | Folgt `NLS_NUMERIC_CHARACTERS`; in einer Sitzung mit Dezimalkomma galt `10,5` als ganzzahlig, weil die Prüfung auf `'.'` sah | Zahlenspalten ohne Text-Umweg (`MOD(x,1)=0`); Textspalten schließen **beide** Trennzeichen aus |
| `--schema hr` lieferte einen leeren Bericht | `ALL_TABLES.OWNER` ist gefaltet; der exakte Vergleich fand nichts, ohne Meldung | Groß geschrieben wie Oracle selbst; ein quotierter Name bleibt stehen |
| Eigenes Quoting | Vierte Kopie derselben Regel, und die einzige ohne gemeinsamen Test | `ProfilingSqlNames` aus `driver-common` |
| Der Oracle-Zweig der Verdrahtung hatte keinen Test | Beide Wiring-Tests schlossen Oracle aus; die neuen E2E können den Unterschied nicht sehen, weil der Pool **vor** der Adapter-Auswahl entsteht | `DataProfileWiringTest` prüft alle fünf Dialekte und Oracle namentlich |
| Modul nur in Gradle registriert | Fehlte im `Dockerfile` (deps-Stage, beide Kover-Listen) und in `.a-check.yml` | ergänzt |

Der Integrationstest deckt jetzt dieselben Typen ab, für die der
MSSQL-Vorgänger eine eigene Tabelle anlegt — XML, JSON, LOB, binär und beide
zeitlichen Formen. Genau dort saßen zwei der drei Abbrüche.

### Das Gate entfällt — zum zweiten Mal

`DialectCommandGate` trug mit `DATA_PROFILE` seinen letzten Eintrag. Beim
MSSQL-Rollout wurde die Klasse aus demselben Grund gelöscht und musste für
Oracle neu gebaut werden (Slice 1). Sie erneut zu löschen ist trotzdem
richtig: eine leere Aufzählung macht `refusal()` unaufrufbar, und eine
Kommando-Grenze, die kein Kommando mehr führt, ist toter Code. Der nächste
Dialekt-Rollout baut sie wieder — das ist billiger als eine Attrappe zu
pflegen.

**Was nur das Gate trug**, und wo es jetzt steht:

| Zusicherung | vorher | jetzt |
| --- | --- | --- |
| `data profile` weist Oracle an der Kommando-Grenze ab, **bevor** eine Verbindung entsteht | `OracleCommandGateE2ETest` | `OracleDataProfileE2ETest` prüft das Gegenteil: der Lauf muss den Verbindungsaufbau erreichen, also **nicht** mit Exit 2 enden |
| Derselbe Riegel im MCP-Worker | `McpCoreJobWorkerFactoryTest` | derselbe Test, umgedreht: der Fehler darf kein Kommando-Grenz-Fehler mehr sein |
| „`data profile` weist Oracle mit einer Meldung ab" | Anwenderhandbuch | ersetzt durch den Hinweis auf die Leerstring-Semantik — die einzige Oracle-Besonderheit, die ein Anwender im Profil sieht |
| „Einzig `data profile` bleibt unerreichbar" | `OracleDriver`-KDoc | entfernt |

## Slice 12 im Detail — Oracle Spatial

### Ausgangslage

Oracle ist der einzige der fünf Dialekte ohne Spatial-Unterstützung. Der
Zustand ist in allen vier Pfaden verschieden, und der Reverse-Pfad ist der
schlechteste:

| Pfad | heute |
| --- | --- |
| `schema generate` | `OracleDdlGenerator.canGenerateSpatial()` liefert `false` → Geometriespalte blockt mit `E052` |
| `schema migrate` | `OracleDiffTableOps.blockSpatial` bei `CreateTable`, `AddColumn`, `AlterColumnType` |
| Spatial-Index | `OracleIndexDdlBuilder` überspringt ihn mit eigener Begründung |
| `schema reverse` | `SDO_GEOMETRY` trifft keinen Zweig in `OracleTypeMapping` und fällt über `mapOpaque` auf `NeutralType.Text(maxLength = null)`, mit `R301`. Die Geometrie ist danach Text — der Blocker der anderen drei Pfade greift nicht mehr, weil das Modell nichts Räumliches mehr trägt |

Der Typ**name** ist in `OracleTypeMapper` bereits abgebildet
(`NeutralType.Geometry → "SDO_GEOMETRY"`), damit die Blocker-Meldungen den
richtigen Namen nennen. Mehr steckt nicht dahinter.

### Was Oracle anders macht als die vier anderen

Gemessen gegen `gvenzl/oracle-free:23-faststart`:

| Befund | Messung | Warum es zählt |
| --- | --- | --- |
| **Spatial fehlt im slim-Image** | `23-slim-faststart`: `ALL_TYPES` kennt `SDO_GEOMETRY` nicht, `MDSYS.CS_SRS` und `USER_SDO_GEOM_METADATA` existieren nicht. `23-faststart`: alles vorhanden | Der sample-db-Harness **und** die meisten Integrationstests fahren das slim-Image. Slice 12 braucht dort einen Image-Wechsel oder einen eigenen Container — und `23-faststart` ist ~0,9 GB größer |
| **Der Index verlangt die SRID, nicht die Metadatenzeile** | `MDSYS.SPATIAL_INDEX_V2` gelingt **entweder** mit einer Zeile in `USER_SDO_GEOM_METADATA` (auch auf leerer Tabelle) **oder** mit mindestens einer Geometriezeile, aus der er die SRID ableitet. Nur wenn beides fehlt, kommt ORA-13199 „cannot determine SRID" + ORA-13252 | Korrigiert eine frühere Messung, die nur den leeren Fall geprüft und daraus die Metadatenzeile als Pflicht gelesen hatte. Der Index ist damit auch ohne sie renderbar — sofern er **nach** den Daten läuft |
| **Die Metadatenzeile wird großgeschrieben, bedingungslos** | `INSERT INTO user_sdo_geom_metadata … VALUES ('places','geom',…)` landet als `PLACES`/`GEOM`; auch `'my-tbl'`/`'geo-col'` — kein hochstellbarer Bezeichner — wird zu `MY-TBL`/`GEO-COL`. Verursacher ist `MDSYS.SDO_GEOM_TRIG_INS1` | d-migrate quotiert Bezeichner wortgetreu und erzeugt damit kleingeschriebene Tabellen (`CREATE TABLE "type_test"`, siehe DDL-Goldens). Für sie lässt sich **keine** Metadatenzeile ablegen: Oracle sucht sie unter dem echten Namen `places`, findet nur `PLACES` und meldet ORA-13252. Eine trotzdem geschriebene Zeile behauptete etwas über eine *andere* Tabelle |
| **Ein fehlgeschlagener Index lässt sich einfangen** | Ein PL/SQL-Block, der `CREATE INDEX` per `EXECUTE IMMEDIATE` fährt und im `EXCEPTION`-Zweig `DROP INDEX … FORCE` nachschiebt, bevor er `RAISE`t, hinterlässt auf der leeren Tabelle **null** Index-Reste; die Tabelle bleibt beschreibbar, der Fehler wird weiterhin gemeldet | Nimmt der Reihenfolge ihre Schärfe: der Index ist danach entweder da oder ganz weg, nie halb. Der Block braucht `scriptTerminator = "/"` — dieselbe Naht, die Slice 9 für PL/SQL eingeführt hat |
| **Die Bounding-Box ist Pflicht, aber ohne Zwangswirkung** | `SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -180, 180, 0.005), …)` — je Dimension Unter-, Obergrenze und Toleranz. Ein Punkt **außerhalb** der deklarierten Grenzen wird eingefügt *und* von einer indizierten `SDO_FILTER`-Abfrage gefunden; der Index bleibt `VALID` | Sie muss dastehen, schneidet aber nichts weg. Eine zu enge Angabe verliert also keine Zeilen — das nimmt der Herleitungsfrage ihre Schärfe (siehe Entscheidung 1) |
| **Auslesbar in beide Richtungen** | `SELECT d.sdo_dimname, d.sdo_lb, d.sdo_ub, d.sdo_tolerance FROM user_sdo_geom_metadata m, TABLE(m.diminfo) d` faltet die Nested Table auf; `SDO_TUNE.EXTENT_OF(tab, col)` misst die tatsächliche Datenausdehnung | Der Reverse-Pfad kann die deklarierte Box verlustfrei lesen, der Generate-Pfad sie notfalls messen lassen |
| **Ohne Index braucht die Spalte keine Metadatenzeile** | Tabelle mit `SDO_GEOMETRY`, `INSERT` und `SELECT` funktionieren ohne Eintrag in `USER_SDO_GEOM_METADATA` | Slice 12a (Reverse) und ein reiner Spaltentransfer kommen ohne sie aus; erst der Index verlangt sie |
| **Die SRID steht in der Metadatenzeile, nicht am Typ** | `USER_SDO_GEOM_METADATA.SRID = 4326`; die Spalte selbst ist typlos-generisch (`DATA_TYPE = 'SDO_GEOMETRY'`, `DATA_TYPE_OWNER = 'PUBLIC'`) | PostGIS trägt die SRID im Spaltentyp (`geometry(Point,4326)`), MySQL/SQL Server im Wert. Oracle ist die vierte Variante und braucht eine eigene Naht |
| **Ein fehlgeschlagener Index sperrt die Datenspur** | `DOMIDX_OPSTATUS = FAILED` → jedes `INSERT` scheitert mit ORA-29861 | Reihenfolge im Migrate-Plan ist sicherheitsrelevant: ein halb gebauter Spatial-Index macht die Tabelle unbeschreibbar, statt nur die Abfrage zu verlangsamen |
| **SRID-Katalog vorhanden** | `MDSYS.CS_SRS` führt 6194 Einträge, darunter 4326 und 3857 | Eine SRID lässt sich vor dem Schreiben prüfen, statt beim Anlegen des Index zu scheitern |
| **Der Spatial-Index ist fallrichtig auslesbar** | `USER_SDO_INDEX_INFO` führt Index-, Tabellen- und Spaltennamen in der Schreibweise des Katalogs (`places`/`geom`), `ALL_INDEXES.ITYP_NAME` die Indexart | Der Reverse braucht keine eigene Namensnormalisierung. `OracleMetadataQueries.domainKind` klassifiziert Domain-Indizes bereits über `ITYP_OWNER`/`ITYP_NAME`; Spatial ist dort ein dritter Zweig neben `CTXSYS/CONTEXT` |
| **WKB trägt die SRID nicht, der Konstruktor schon** | `SDO_UTIL.TO_WKBGEOMETRY` → BLOB; zurück über `SDO_UTIL.FROM_WKBGEOMETRY(wkb)` fällt die SRID auf `NULL`. Die **zweiargumentige** Überladung `FROM_WKBGEOMETRY(wkb, srid)` trägt sie, und beide Formen sind NULL-streng (`FROM_WKBGEOMETRY(NULL, 4326)` → `NULL`) | Trifft d-migrates vorhandene Naht exakt: `valuePlaceholder` rendert bereits `<ctor>(?, srid)`. Der Typkonstruktor `SDO_GEOMETRY(wkb, srid)` wäre die falsche Wahl — er ist **nicht** NULL-streng und macht aus einer NULL-Geometrie eine nicht-NULL Geistergeometrie mit leerem GTYPE |

Die Toleranz der Bounding-Box ist gegen `MDSYS.SPATIAL_INDEX_V2` auf 23ai
gemessen. Ältere Bestände tragen den Vorgänger `MDSYS.SPATIAL_INDEX`, für den
diese Duldsamkeit nicht mitgemessen ist — der Reverse-Pfad muss die Indexart
deshalb aus `ALL_INDEXES.ITYP_NAME` lesen und darf sie nicht annehmen.

### Schnitt

Der Slice ist damit größer als „noch ein Typ". Vorschlag in drei Teilen, in
dieser Reihenfolge, weil jeder den nächsten trägt:

- **12a — Reverse ohne Verlust.** `SDO_GEOMETRY` in `OracleTypeMapping` als
  `NeutralType.Geometry` erkennen, SRID und Dimensionszahl aus
  `USER_SDO_GEOM_METADATA` beziehen. Damit hört die stille Umdeutung zu Text
  auf; Generate und Diff blocken weiterhin, aber jetzt sichtbar und auf einem
  Modell, das die Geometrie noch trägt.
- **12b — Generate und Diff.** `canGenerateSpatial` für das native Profil,
  Spaltentyp `SDO_GEOMETRY`, Wegfall der Blocker in `OracleDiffTableOps`. Ohne
  Metadatenzeile (siehe Entscheidung 1) ist das der kleinste der vier Teile.
- **12c — Index.** `MDSYS.SPATIAL_INDEX_V2` als selbstaufräumender
  PL/SQL-Block in POST_DATA, und der Reverse-Zweig über `ITYP_NAME`, damit ein
  gelesener Spatial-Index nicht mehr als „fremder Domain-Index" durchfällt.
- **12d — Datenpfad.** `SDO_UTIL.TO_WKBGEOMETRY` beim Lesen,
  `SDO_UTIL.FROM_WKBGEOMETRY(?, srid)` beim Schreiben — zwei Überschreibungen
  auf der vorhandenen WKB-Naht.

### Vor dem Bau entschieden

1. **Woher die Bounding-Box kommt — die Frage entfällt.** Sie stand nur, weil
   die Metadatenzeile als Pflicht galt. Nach der korrigierten Messung braucht
   `SPATIAL_INDEX_V2` sie nicht, wenn er nach den Daten läuft, und für
   d-migrates kleingeschriebene Bezeichner lässt sie sich ohnehin nicht
   ablegen. Damit ist keine Box herzuleiten und kein Modellfeld zu erfinden:
   **12b schreibt keine Metadatenzeile.**

   Der Reverse liest die deklarierte Box weiterhin nicht mit — nur die SRID
   (12a). Ein Oracle→Oracle-Round-Trip über großgeschriebene Bezeichner
   verlöre sie also; das bleibt die getrennte Entscheidung, die erst ansteht,
   wenn dieser Fall gebaut wird.

2. **Welches Testbild: `23-faststart` nur für die Spatial-Tests.** Es gibt
   dafür bereits ein Vorbild im Repo — `OracleFullTextIntegrationTest` fährt
   dasselbe größere Bild nur für sich, weil Oracle Text im slim-Bild fehlt.
   Spatial hat dieselbe Lage und bekommt dieselbe Behandlung, statt allen
   übrigen Oracle-Tests ~0,9 GB und die längere Startzeit aufzuladen.

3. **Der Datenpfad geht mit.** Er kostet fast nichts — die Messung oben zeigt,
   dass `SDO_UTIL.TO_WKBGEOMETRY` / `FROM_WKBGEOMETRY(?, srid)` genau auf die
   vorhandene Naht passt (`geometryReadExpression`,
   `geometryBindConstructor`), ohne eine Zeile an der geteilten Naht zu
   ändern. Vor allem aber wäre es **ohne ihn eine Verschlechterung**: erst 12a
   macht aus der Geometrie einen echten `NeutralType.Geometry`, und ein
   Transfer, der ihn nicht kennt, schriebe den Oracle-Locator als Text
   fort.

### Die Reihenfolge ist die tragende Entscheidung

Aus den drei Messungen — Metadatenzeile unerreichbar, Index braucht die SRID
aus den Daten, fehlgeschlagener Index sperrt die Tabelle — folgt eine Regel,
die den Slice zusammenhält:

> Der Spatial-Index steht in **POST_DATA**, nie zwischen Tabelle und Daten,
> und wird als selbstaufräumender PL/SQL-Block gerendert.

POST_DATA ist dafür der richtige Ort und kein Notbehelf: das
Anwenderhandbuch sichert die Reihenfolge `pre-data → Daten → post-data`
bereits zu, und `generateIndices` bekommt im geteilten Generator keine
Phasen-Überschreibung — ein Statement behält seine eigene Phase. Oracle legt
den Index also selbst nach hinten, ohne die vier anderen Dialekte zu
berühren.

### Was der Review gefunden hat

Zehn Befunde, dazu zwei, die erst der Integrationstest gegen ein echtes
Oracle zeigte. Die beiden schwersten hätten je einen dauerhaft
nicht konvergierenden Zustand hinterlassen:

| Befund | Warum es schiefging | Behoben durch |
| --- | --- | --- |
| `schema migrate` legte den Spatial-Index auf eine gerade erst angelegte Tabelle | Der Migrate-Pfad hat keine Datenphase; `DiffPhase` verwirft die `POST_DATA`-Angabe des Statements. Die neue Tabelle ist leer → ORA-13199, und der Folgelauf plant dieselbe Operation erneut | Benannter Blocker `ORACLE_SPATIAL_INDEX_NEEDS_ROWS` beim `CreateTable`; auf einer **bestehenden** Tabelle wird der Index weiter gerendert |
| `setNull` band den Spaltentyp statt des WKB | An der Bindeposition steht das BLOB-Argument des Konstruktors, nicht die Geometrie. Oracle-JDBC lehnt das mit ORA-17068 ab — **vom Integrationstest gefunden, nicht vom Review** | `Types.BLOB` für Geometriespalten, WKB ausdrücklich über `setBytes` (dasselbe Muster wie SQL Server) |
| Der Rollback ließ den Spatial-Index stehen | `invertStatement` prüft `CREATE`-Präfixe; der Block beginnt mit `BEGIN` und fiel durch — während die Spec ein `DROP INDEX` zusagte | `OracleSpatialIndexDdl.invertedDrop` hebt das `DROP` aus dem Aufräumzweig des Blocks |
| Der JDBC-Typname ist eignerqualifiziert | `getColumnTypeName` meldet `MDSYS.SDO_GEOMETRY`, der Katalog dagegen `SDO_GEOMETRY`. Ein exakter Vergleich hätte den **gesamten** Datenpfad still übergangen — **vom Integrationstest gefunden** | Vergleich über das letzte Namenssegment, und der gemeldete Name ist im Test festgenagelt |
| `--spatial-profile none` wirkte auf `schema migrate` nicht mehr | Diese Zusicherung trug allein der entfernte `blockSpatial`; der Migrate-Renderer liest das Profil nicht (wie bei SQL Server) | Nicht im Slice behoben — das bestehende Ticket [`migrate-spatial-profile-not-validated.md`](../done/migrate-spatial-profile-not-validated.md) trägt jetzt die richtige Aktivierungsbedingung |
| Der Datenpfad verliert die SRID stumm | Sie käme aus `ALL_SDO_GEOM_METADATA` des Ziels, die es für eine von d-migrate angelegte Tabelle nie gibt. `W120` entsteht nur im Generate-Pfad | Nicht im Slice behoben: der Import-Port führt keinen Meldekanal. Eigenes Ticket [`data-path-loses-geometry-srid.md`](../done/data-path-loses-geometry-srid.md), im Handbuch benannt |
| Drei Handbuch-Stellen beschrieben den abgelösten Stand | `schema migrate` blocke „Geometrie-Spalten"; ein räumlicher Domain-Index werde ausgelassen; `R365` fehlte in der Meldungstabelle | Alle drei nachgezogen |
| Tote Abfrage im Reverse | `GeometryMetadataRow.dimensions` las niemand, die korrelierte `TABLE(m.diminfo)`-Unterabfrage lief bei jedem Reverse umsonst | Feld und Unterabfrage entfernt |
| Verwaister KDoc | Der Einschub von `geometryMetadata` trennte den Partitions-KDoc von seiner Funktion | Wieder zusammengeführt |
| Chronik in Kommentaren | Datum und Slice-Nummern in `OracleSpatialIndexDdl`, im Integrationstest und in zwei Unit-Tests | Entfernt; die Kommentare beschreiben jetzt die Funktion |

Ungetestet waren außerdem `SpatialProfilePolicy` für Oracle,
`listGeometryMetadata`, der SRID-Durchstich im Reader samt `R365`, der
Diff-Blocker und die Rollback-Umkehrung — alle fünf haben jetzt Tests.

## Offene Punkte

- ~~`gvenzl/oracle-free`-EULA-/Zustimmungsmechanik verifizieren~~ — **erledigt
  (Slice 0):** keine EULA-Zustimmung nötig, anders als beim MSSQL-Image.
- ~~FUTC-Lizenztext dokumentieren~~ — **erledigt (Slice 0):**
  [`THIRD-PARTY-NOTICES.md`](../../../THIRD-PARTY-NOTICES.md) im Repo-Root.
  Die Bündelung dieser Datei in Release-Artefakten ist ein separates Thema,
  siehe [`third-party-notices-release-bundling.md`](../done/third-party-notices-release-bundling.md).
- ~~Testcontainers-Ressourcenbedarf (RAM) real messen~~ — **erledigt (Slice
  1, live entdeckt):** kein RAM-Problem, sondern ein zu knapper Default:
  `org.testcontainers.oracle.OracleContainer` setzt `withStartupTimeout` auf
  nur 60s, ausreichend für ein bereits gezogenes Image auf einer warmen
  lokalen Maschine, zu knapp für einen kalten Pull + Kaltstart auf dem
  GitHub-Actions-Runner (real gemessen: Timeout nach 60s in CI, ~2-3 min bis
  „DATABASE IS READY TO USE!" lokal). Fix: `.withStartupTimeout(Duration
  .ofMinutes(5))` in `OracleContainerConnectIntegrationTest.kt`.
- **Neu (Slice 0, live entdeckt):** `gvenzl/oracle-free`s gleitende
  `slim-faststart`-Tags liefern inzwischen „26ai" statt „23ai" aus, und der
  Versions-Banner heißt jetzt „Oracle AI Database" statt „Oracle Database" —
  der Spike pinnt deshalb explizit auf `23-slim-faststart`
  (siehe `OracleContainerConnectIntegrationTest.kt`).

## Risiken

- UPPERCASE-Default-Bezeichner ohne Quoting sind ein Cross-Cutting-Risiko für
  Reverse-/Postcompare-Kanonisierung, ähnlich MSSQLs Collation-Fallstrick.
- PL/SQL Packages bleiben zeitlich unbestimmt unvollständig abgebildet (siehe
  Entscheidung 4). **Für den Anwender ist das seit Slice 5e-2 gedeckt**, ohne
  dass die Packages eigens genannt werden: das Anwenderhandbuch sagt, dass
  `schema migrate` bei Routinen und Triggern benannt blockt, und der Reverse
  meldet vorhandene Packages zur Laufzeit als `R342`-Notiz. Die
  darüberhinausgehende Aussage — dass Packages auch nach Slice 9 unabgebildet
  blieben — gehört **nicht** ins Handbuch: dort steht der Ist-Zustand, keine
  Planung.
