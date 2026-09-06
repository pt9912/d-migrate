# Vorabklärung: Oracle als fünfter Dialekt (Milestone 1.8.0)

> **Status:** In Progress (2026-09-05). Scope skizziert, alle fünf
> Grundsatzentscheidungen getroffen (siehe ADR 0052), **Slice 0, Slice 1,
> Slice 1a, Slice 2 und Slice 3 geliefert**.
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
> [`open/oracle-uppercase-folding-unquoted-identifier-references.md`](../open/oracle-uppercase-folding-unquoted-identifier-references.md)
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
>   [`mssql-enum-reftype-unresolved-fallback-gap.md`](../open/mssql-enum-reftype-unresolved-fallback-gap.md)
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
> [`diff-comment-as-statement.md`](../open/diff-comment-as-statement.md)
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
> [`oracle-sequence-bounds-not-round-trippable.md`](../open/oracle-sequence-bounds-not-round-trippable.md).

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
> [`mssql-object-rename-policy-missing.md`](../open/mssql-object-rename-policy-missing.md).
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
> [`pg-identity-sequence-name-fingerprint.md`](../open/pg-identity-sequence-name-fingerprint.md).
> Der Vermerk stand im 4a-Ticket und wäre mit dessen Löschung
> verschwunden.
>
> Zwei weitere Befunde als Ticket, beide dialektübergreifend und nicht von
> diesem Slice verursacht:
> [`migrate-spatial-profile-not-validated.md`](../open/migrate-spatial-profile-not-validated.md)
> (ein Tippfehler in `--spatial-profile` fällt auf dem Migrate-Pfad still
> auf den Default zurück) und
> [`check-preflight-probe-duplication.md`](../open/check-preflight-probe-duplication.md)
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
>   [`identity-column-shape-mismatch.md`](../open/identity-column-shape-mismatch.md).
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
| Indizes | Function-based-Indizes, Bitmap-Indizes | Ausbau-Slice |
| Partitionierung | Range/List/Hash/Composite — strukturell reichhaltiger als PG | Ausbau-Slice |
| Materialized Views | **nativ vorhanden**, echtes Refresh-Modell (FAST/COMPLETE/FORCE, ON COMMIT/ON DEMAND) | Ausbau-Slice (10) — Anschluss ans bestehende Modell, keine Lücke |
| Volltext | Oracle Text, eigene Indextypen (`CONTEXT`/`CTXCAT`) | Ausbau-Slice — Muster aus dem Fulltext-Slice |
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
| **3b** ✅ | Sample-db-Oracle-Leg: Compose-Service, `.env`, Verbindung, `smoke-cross-pg2ora.sh`, `make sample-db-cross-smoke-pg2ora` — **gruen**. Pagila PG → Oracle mit `sqlplus`-angewandter DDL, `--verify OK` (18 Ausschluesse), Paritaet ueber alle 15 Tabellen, datenbelegten Typkonvertierungen und Schluesseltreue. Der Weg dorthin hat **sechs Defekte im ausgelieferten Pfad** aufgedeckt, alle behoben ([`oracle-sample-db-leg.md`](../next/oracle-sample-db-leg.md)) | Cross-Dialekt-Beleg im Harness |
| **4a** ✅ | `NeutralTypeCanonicalizer` + Postcompare-Fingerprint-Beleg (`transferCompatibility` bereits Slice 3) | Vergleichs-Substrat für Slice 5 |
| **4b** ✅ | Cross-Dialekt-sample-db-Smoke, Gegenrichtung Oracle → PG (`smoke-cross-ora2pg.sh`, `make sample-db-cross-smoke-ora2pg`): Hop 0 saet Oracle mit der pg2ora-Mechanik, Hop 1 faehrt reverse/generate/transfer zurueck. **Dreifache Zeilen-Paritaet** (Original == Oracle == Rueckziel), `--verify OK` (16 Ausschluesse), Rueckwaerts-Konvertierungen datenbelegt — `NUMBER(1)` kommt als `boolean` zurueck, `CLOB` als `text`, `TIMESTAMP WITH TIME ZONE` als `timestamptz`, Schluessel ueber beide Hops erhalten | Cross-Dialekt-Beleg in beide Richtungen |
| **5a** ✅ | Diff-Gerüst + Tabellen-/Spalten-Operationen | nur über Tests erreichbar (Registry-Verdrahtung erst 5e) |
| **5b** ✅ | Constraint- und Index-Operationen | dito |
| **5c** ✅ | Views und Custom Types | dito |
| **5d** ✅ | Sequenzen | dito |
| **5e-1** ✅ | Rename-Policies (Objekt + Abhängigkeit) und View-Abhängigkeiten aus `ALL_DEPENDENCIES` — Vorbedingung für den Gate-Fall | dito |
| **5e-2** ✅ | Verdrahtung: `MigrateRendererRegistry`, `DialectCommandGate`, CHECK-Preflight-Sonde, `ColumnGeneration`-Kanonisierung im Fingerprint, `SequenceCapabilityDefaults` | **`schema migrate` ist nutzbar** |
| **5e-3** ✅ | Cross-Dialekt-Matrix-Sweep-Beitritt, Live-Round-Trip, Handbücher (der CLI-E2E kam bereits mit 5e-2) | Matrix-Abdeckung + Live-Beleg + Doku |
| **6** | Function-based- + Bitmap-Indizes, Reverse + Generate + Diff | volle Index-Treue |
| **7** | Partitionierung: Range/List/Hash/Composite (Anschluss an `PartitionBoundScanner`/Cross-Dialekt-Muster) | Partitionstabellen im Round-Trip |
| **8** | Volltext: Oracle Text (`CONTEXT`/`CTXCAT`, Muster aus dem Fulltext-Slice) | Volltext-Indizes Generate + Reverse |
| **9** | Routinen/Trigger (standalone PL/SQL, `CREATE OR REPLACE`) | Routinen-Migration |
| **10** | Materialized Views: Anschluss ans bestehende 0.9.7-D.3b-Modell (Refresh-Modi FAST/COMPLETE/FORCE, ON COMMIT/ON DEMAND) | Materialized Views im Round-Trip |
| **11** | Profiling-Modul `driver-oracle-profiling` | Live belegt; `DialectCommandGate` verliert seinen letzten Oracle-Eintrag |
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
| `schema migrate` | Slice 5 | Gate + `MigrateRendererRegistry` → `null` |
| `data profile` (CLI + MCP-Job) | Slice 11 | Gate |

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
  [`oracle-add-identity-requires-rebuild.md`](../open/oracle-add-identity-requires-rebuild.md).
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
  Partitionierung (E055, Slice 7), Function-based-/Bitmap-Indizes (E057,
  Slice 6), Materialized Views (nicht gebaut, Slice 10).

### Sub-Slice-Schnitt

| Sub-Slice | Operationen | Kern der Arbeit | Abnahme |
| --- | --- | --- | --- |
| **5a** ✅ | `CreateTable`, `DropTable`, `RenameTable`, `AddColumn`, `DropColumn`, `RenameColumn`, `AlterColumnType`, `AlterColumnNullability`, `AlterColumnDefault`, `AddPrimaryKey`, `DropPrimaryKey` | Gerüst (Dispatch UP/DOWN, RenderContext, SqlBuilders). Kein Default-Dreischritt nötig. `RENAME TO`/`RENAME COLUMN` sind native Syntax. Identity-Typ-/Modus-Änderungen live bestätigt in-place — kein Rebuild-Zweig; das *Hinzufügen* von Identity blockt benannt (siehe oben) | Unit-Tests je Operation und Richtung |
| ~~5a-2~~ | — | **Entfällt** — die Live-Sonde bestätigte, dass Oracle Identity-Typänderungen per `ALTER TABLE ... MODIFY` in-place erlaubt (siehe oben); MSSQLs Rebuild-Sub-Slice hat kein Oracle-Äquivalent. Der einzige Rest-Fall (Identity *hinzufügen*) ist in [`oracle-add-identity-requires-rebuild.md`](../open/oracle-add-identity-requires-rebuild.md) ausgelagert | — |
| **5b** ✅ | `AddConstraint`, `DropConstraint`, `AddIndex`, `DropIndex` | Nur B-Tree-Indizes — ein nicht-BTREE-Indextyp rendert als B-Tree mit `W102` (so wie im Generate-Pfad, `spec/ddl-generation-rules.md`); Constraint-Namen kommen aus dem Operations-Payload, kein Katalog-Lookup nötig. Kein `WITH CHECK`-Äquivalent: Oracle validiert per Default gegen den Bestand (siehe oben) | Unit-Tests je Operation und Richtung |
| **5c** ✅ | `CreateView`, `ReplaceView`, `DropView`, `RenameView`, `CreateCustomType`, `AlterCustomType`, `DropCustomType` | `CREATE OR REPLACE VIEW FORCE`; `AlterCustomType` fächert auf nutzende Spalten auf, DOMAIN aber immer → CLOB | Unit-Tests je Operation und Richtung |
| **5d** ✅ | `CreateSequence`, `AlterSequence`, `DropSequence`, `RenameSequence`, `AlterSequenceCurrentValue` | `ALTER SEQUENCE ... RESTART START WITH n` (live verifizieren); `supportsCurrentValuePreserve` → `true`; explizit NICHT identity-backed Sequenzen (bleibt Slice 3s Domäne) | Live-Test pinnt die gemessene Sequenz-Semantik; `neutral-model-spec.md` §9 bekommt die Oracle-Spalte |
| **5e-1** ✅ | — | Rename-Vorbedingung: `OracleRenameDependencyPolicy` + `OracleObjectRenamePolicy` (beide Registries) und View-Abhängigkeiten aus `ALL_DEPENDENCIES`. Oracle-Besonderheit: auch `classifyColumnRename` projiziert Sichten neu | Unit-Tests + Planer-Durchlauf, der die View-Absorption pinnt |
| **5e-2** ✅ | — | Verdrahtung: `MigrateRendererRegistry` liefert den Oracle-Renderer, `DialectCommandGate` verliert `SCHEMA_MIGRATE`, Oracle-CHECK-Preflight-Sonde, `canonicalizeGeneration`-Hook im Fingerprint, `supportsCurrentValuePreserve` → `true` und Beitritt zu `PRESERVE_DIALECTS`. Alle drei Vorbedingungen damit erledigt (Rename-Policies in 5e-1, Fingerprint-Drift und `CheckPreflightProbeRunner`-Stub hier) | **`schema migrate` ist für oracle nutzbar** |
| **5e-3** ✅ | — | Beitritt zum Cross-Dialekt-Matrix-Sweep (Carve-outs auf D.3/E.2), Live-Round-Trip über die echten Runner, Handbücher und `connection-config-spec` | Matrix-Abdeckung + Doku |

## Offene Punkte

- ~~`gvenzl/oracle-free`-EULA-/Zustimmungsmechanik verifizieren~~ — **erledigt
  (Slice 0):** keine EULA-Zustimmung nötig, anders als beim MSSQL-Image.
- ~~FUTC-Lizenztext dokumentieren~~ — **erledigt (Slice 0):**
  [`THIRD-PARTY-NOTICES.md`](../../../THIRD-PARTY-NOTICES.md) im Repo-Root.
  Die Bündelung dieser Datei in Release-Artefakten ist ein separates Thema,
  siehe [`third-party-notices-release-bundling.md`](../open/third-party-notices-release-bundling.md).
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
  Entscheidung 4) — muss in Anwenderhandbuch/Administrationshandbuch als
  bekannte Grenze stehen, sobald Oracle nutzersichtbar wird (ab Slice 1).
