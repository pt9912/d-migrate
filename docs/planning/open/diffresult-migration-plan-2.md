# Implementierungsplan: DiffResult-Migrationen 0.9.7, Teil 2

> Status: Draft, verfeinert (2026-05-11)
>
> Zweck: Folgeplan fuer die offenen Punkte und Carve-outs aus dem ersten
> `DiffResult`-Slice. Dieses Dokument sammelt nur Themen, die fuer 0.9.7
> separat entschieden, vertraglich stabilisiert oder bewusst nachgelagert
> werden muessen.
>
> Ausdruecklich nicht Teil dieses Dokuments: erneute Planung von Phase H aus
> `docs/planning/in-progress/diffresult-migration-plan.md`
> (SQLite-Rebuild-Vertrag formalisieren). Die dort abgeschlossenen
> SQLite-Rebuild-Vertraege gelten hier als Voraussetzung und werden nicht
> dupliziert.
>
> Referenzen:
> - `docs/planning/in-progress/diffresult-migration-plan.md`
> - `docs/planning/in-progress/roadmap.md`
> - `spec/cli-spec.md` Abschnitt `schema migrate` / `schema rollback`
> - `spec/ddl-generation-rules.md`
> - `docs/planning/open/sqlite-sequence-emulation-plan.md`

---

## 1. Ziel und Scope-Grenze

Der erste 0.9.7-Plan liefert den ausfuehrbaren `schema migrate`-/
`schema rollback`-Slice:

- Datei-zu-DB und Datei-zu-Datei-Planung
- Up-DDL-Erzeugung und optionale Up-DDL-Ausfuehrung
- Down-SQL-Artefakte
- `schema rollback` aus Down-SQL
- Driftpruefung
- Risiko-, Blocker- und Exit-Code-Semantik

Dieser zweite Plan ist kein zweiter Sammelkorb fuer "mehr SQL". Er definiert
die Folgeentscheidungen, ohne die der erste Slice sonst unkontrolliert
wachsen wuerde:

- Dialekt-Hardening jenseits der ersten sicheren Matrix
- Daten- und Dependency-Preflights, die eine Live-DB oder neue Modellfelder
  brauchen
- groessere Objektklassen wie Routinen, Trigger, Sequences und Materialized
  Views
- Produktvertraege fuer Plan-Artefakte, Partial-Rollbacks und Rename-Mappings
- technische Artefakt-/Runner-Carve-outs, die vor komplexeren SQL-Bodies
  aufgeloest werden muessen

Leitlinie: Neue Faelle werden erst renderbar, wenn fuer Planung, Rendering,
Report, Rollback und Tests ein expliziter Vertrag existiert. Bis dahin bleibt
das Verhalten blockierend oder diagnostisch.

---

## 2. Nicht-Ziele

Nicht Bestandteil dieses Plans:

- erneute Planung oder Duplizierung des abgeschlossenen Phase-H-Vertrags fuer
  SQLite-Rebuilds aus dem ersten 0.9.7-Plan
- Ruecknahme der 0.9.7-Blocker-Strategie
- generisches SQL-Raten bei unbekannten Dependencies
- automatische Datenmigration ohne expliziten Nutzervertrag
- implizite Installation von Extensions
- oeffentliche Stabilitaetsgarantie fuer interne `DiffResult`-Details, solange
  kein versioniertes Plan-Artefakt beschlossen ist

Die abgeschlossenen SQLite-Rebuild-Vertraege aus Phase H gelten als Grundlage
dieses Folgeplans. Dieses Dokument baut darauf auf, ohne die H-Tasks erneut zu
definieren.

---

## 3. Schnittstellen zum ersten Slice

### 3.1 Bestehende 0.9.7-Vertraege bleiben verbindlich

Dieser Folgeplan darf folgende Entscheidungen nicht nebenbei aufweichen:

- `DiffResult` bleibt im ersten Slice intern.
- `schema rollback` liest im ersten Slice Down-SQL, nicht ein serialisiertes
  `DiffResult`-Plan-Artefakt.
- `--generate-rollback` ist streng: `MANUAL_REQUIRED` und `NOT_REVERSIBLE`
  blockieren vollstaendige Rollback-Erzeugung mit Exit `8`.
- Rename-Hints bleiben im ersten Slice Diagnose, keine Operation.
- Datei-zu-Datei-Planung bleibt deterministisch und darf keine Live-DB-
  Erkenntnisse vortaeuschen.
- Non-TTY-Betrieb nutzt keine interaktiven Rueckfragen.

### 3.2 Gemeinsame Blocker- und Report-Regeln

Jeder Workstream muss vor Implementierung festlegen:

- betroffene Modi: Datei-zu-Datei, Datei-zu-DB, Execute, Rollback
- neue renderbare Operationen und weiterhin blockierende Operationen
- neue Diagnostics und `primaryBlockedReason`-Werte, falls noetig
- Up-/Down-Reversibilitaet getrennt nach Dialekt
- Report- und Metadatenfelder, die maschinenlesbar stabil sein muessen
- Positiv-, Blocker- und Round-Trip-Tests

Wenn ein Punkt Live-DB-Wissen benoetigt, muss der Datei-zu-Datei-Pfad explizit
melden, dass diese Pruefung nicht ausgefuehrt wurde. Er darf keinen
optimistischen Pass simulieren.

### 3.3 Entscheidungskriterien

Ein Punkt wird in diesen Plan aufgenommen, wenn mindestens eine der folgenden
Bedingungen gilt:

- Er erweitert den renderbaren SQL-Scope.
- Er veraendert Runner-, Artefakt- oder Rollback-Semantik.
- Er braucht neue neutrale Modellfelder oder neue Reverse-Daten.
- Er verschiebt ein Verhalten von Diagnose/Blocker zu automatischer Ausfuehrung.
- Er betrifft Side Effects, Locking, Transaktionsgrenzen oder Datenverlust.

### 3.4 DoD-Grundschema fuer jeden Slice

Jeder aus diesem Plan herausgeloeste Implementierungs-Slice braucht vor
Abschluss mindestens diese Definition of Done:

- [ ] Scope ist auf einen kleinen, separat testbaren Workstream-Ausschnitt
  begrenzt.
- [ ] Betroffene Modi sind dokumentiert: Datei-zu-Datei, Datei-zu-DB,
  Execute, Rollback.
- [ ] Renderbare und weiterhin blockierende Operationen sind explizit
  beschrieben.
- [ ] Neue Diagnostics, Blocker und Exit-Code-Folgen sind maschinenlesbar
  spezifiziert.
- [ ] Up- und Down-Verhalten sind getrennt bewertet.
- [ ] Report-/Metadatenfelder sind stabilisiert oder bewusst als intern
  markiert.
- [ ] Datei-zu-Datei-Verhalten ist definiert, falls Live-DB-Wissen fehlt.
- [ ] Positive Pfade, Blocker-Pfade und relevante Rollback-Pfade sind getestet.
- [ ] Bestehende 0.9.7-Vertraege aus Abschnitt 3.1 bleiben unveraendert oder
  werden bewusst per separater Planentscheidung geaendert.

---

## 4. Workstream G - Artefakt- und Runner-Vertrag

Dieser Workstream hat Prioritaet vor neuen SQL-Bodies. Er raeumt zwei
Carve-outs aus dem ersten Slice auf:

- BEGIN-Erkennung ueber SQL-Content statt strukturiertem Feld
- Rollback-Statement-Splitting ueber `\n\n`

### G.1 Strukturiertes `transactionScope`

> Status: implementiert (2026-05-12). `TransactionScope`-Enum mit
> `RUNNER_OWNED`/`STREAM_OWNED`/`NO_TRANSACTION`,
> `MigrationDdlStatement.transactionScope`, PG/MySQL/SQLite-Renderer
> setzen das Feld explizit, `MigrationStreamClassifier` dispatcht ueber
> das Feld statt ueber SQL-Content. Transitionaler Fallback in
> `SchemaRollbackRunner.splitArtefactBody`: das `rollback-sql v1`-
> Format traegt noch kein `transactionScope`, deshalb stempelt der
> Artefakt-Splitter den Scope bis G.2 anhand fuehrender BEGIN-Tokens.
> Executor und Classifier sind frei von SQL-Heuristik. G.2/G.3 bleiben
> offen.

0.9.7 erkennt stream-owned Transaktionen ueber SQL-Content, konkret ueber
fuehrende `BEGIN`-Statements. Das ist nur sicher, solange keine
Routinen-/Trigger-Bodies als normale Statements gerendert werden.

Entscheidungspunkte:

- `MigrationDdlStatement` bekommt ein strukturiertes `transactionScope`-Feld.
- Mindestwerte:
  - `RUNNER_OWNED`: Runner startet/committet/rollt zurueck.
  - `STREAM_OWNED`: SQL-Stream enthaelt eigene Transaktionsmarker.
  - `NO_TRANSACTION`: Statement darf oder kann nicht in Runner-Transaktion
    ausgefuehrt werden.
- Dialekt-Renderer muessen den Scope explizit setzen.
- Runner darf keine SQL-String-Heuristik mehr fuer Transaktionsfuehrung nutzen.
- Gemischte Streams sind nur erlaubt, wenn ein expliziter Gruppierungsvertrag
  existiert; sonst blockieren sie vor Ausfuehrung.

Akzeptanz:

- Regressionstest mit Routine-/Trigger-artigem SQL-Body, der `BEGIN` enthaelt
  und trotzdem nicht als stream-owned klassifiziert wird.
- SQLite-Rebuild bleibt stream-owned.
- PostgreSQL/MySQL bleiben runner-owned, sofern der Dialektvertrag das erlaubt.
- Report weist `transactionScope` pro Statement oder Statement-Gruppe aus.
- Bei `NO_TRANSACTION` erklaert der Report, ob `sideEffectsPossible=true`
  realistisch ist.

### G.2 Strukturierte Statement-Serialisierung im Rollback-Artefakt

0.9.7 rekonstruiert Statements aus dem Rollback-Artefakt per `\n\n`-Split.
Das ist nur sicher, solange Statement-SQL selbst keine Leerzeilen enthaelt.

Entscheidungspunkte:

- Rollback-Artefakt speichert Statements strukturiert, zum Beispiel:
  - JSON-Array im Metadatenblock mit Operation-IDs, Phase,
    `transactionScope`, Risiken, Byte-Range/Statement-Index und Hash pro
    Statement; oder
  - eindeutig spezifiziertes Length-Prefix-/Range-Format.
- Falls Byte-Ranges genutzt werden, ist die Range-Kanonik Teil des Formats und
  nicht Implementierungsdetail: Ranges beziehen sich auf UTF-8-Bytes des
  LF-normalisierten SQL-Bodys nach dem Metadaten-Endbegrenzer, nutzen
  `startInclusive`/`endExclusive`, zaehlen Kommentare und Whitespace im Body mit
  und erwarten genau eine finale Newline im kanonischen Artefakt. Hashes werden
  ueber genau diese Byte-Slices gebildet. Rohdatei-Offsets, Kotlin-String-
  Indizes oder dialektspezifische Statement-Normalisierung duerfen nicht als
  interoperabler Vertrag verwendet werden.
- Der neue Vertrag bleibt ein Down-SQL-Artefakt, solange `schema rollback`
  laut erstem Slice Down-SQL liest: Ein SQL-Kommentarheader enthaelt die
  strukturierte Statement-Index-/Hash-Metadaten, der Body bleibt ein
  ausfuehrbares SQL-Skript fuer den Zieldialekt. Die Struktur ersetzt nur die
  Parser-Heuristik, nicht den ausfuehrbaren Body. Ein reines JSON- oder
  Length-Prefix-Artefakt ohne direkt ausfuehrbaren SQL-Body waere ein neues
  Artefaktformat und braucht vorher einen separaten CLI-/Kompatibilitaets-
  Vertrag.
- Kanonische SQL-Quelle ist in diesem Vertrag genau der ausfuehrbare
  SQL-Body. Strukturierte Metadaten duerfen keine zweite, abweichungsfaehige
  SQL-Kopie als Ausfuehrungsquelle einfuehren; sie beschreiben nur Grenzen,
  Zuordnung und Hashes der Body-Statements. Falls ein spaeteres Format
  zusaetzlich SQL-Text in Metadaten dupliziert, muss der Parser Byte-/Statement-
  Aequivalenz gegen den Body pruefen und bei Mismatch blockieren.
- `formatVersion` unterscheidet alte und neue Formate eindeutig.
- Parser lehnt unbekannte Versionen blockierend ab.
- Ein kontrollierter Migrationspfad fuer alte `rollback-sql v1`-Artefakte wird
  explizit entschieden: weiter unterstuetzen, upgraden oder ablehnen.
- Artifact-Hash deckt Header, strukturierte Statement-Metadaten und den
  ausfuehrbaren SQL-Body ab. Wie bei `rollback-sql v1` wird der Hash ueber den
  kanonischen Header ohne das `artifactHash`-Feld plus den kanonischen SQL-Body
  gebildet; das `artifactHash`-Feld selbst ist niemals Teil seines eigenen
  Hash-Inputs. `schema rollback --execute` rekonstruiert die Statement-Liste
  aus den validierten Body-Ranges und fuehrt diese Body-Statements aus.

Vorbedingung fuer:

- Routine-Bodies
- Trigger-Bodies
- komplexe View-Definitionen mit Leerzeilen
- Materialized-View-Refresh-Statements mit mehrteiliger Steuerlogik

Akzeptanz:

- Round-Trip-Test mit Statement-SQL, das Leerzeilen enthaelt.
- Parser-Test fuer manipulierte Statement-Reihenfolge und Hash-Mismatch.
- Parser-Test fuer unbekannte `formatVersion`.
- `schema rollback --execute` nutzt die strukturierte Form ohne
  Whitespace-Split.

### G.3 Execution-Status als stabiler Report-Vertrag

Der erste Slice berichtet `executionStarted`, `executionCompleted`,
`statementsAttempted`, `lastStatementOperationIds`, `transactionRolledBack`
und `sideEffectsPossible`. Fuer komplexere DDL muss dieser Status erweitert
werden, ohne bestehende Felder umzudeuten.

Entscheidung:

- Multi-Statement-Operationen bekommen eine stabile `statementGroupId`.
  Default ist die primaere Operation-ID; wenn eine Operation mehrere getrennte
  Gruppen erzeugt, wird deterministisch mit `#1`, `#2`, ... suffixiert. Jedes
  Statement referenziert genau eine Gruppe, und der Report aggregiert
  `operationIds`, Statement-Indexbereich, `transactionScope` und
  `transactionBoundary` pro Gruppe.
- `transactionBoundary` beschreibt die Ausfuehrungsposition der Gruppe relativ
  zur effektiven Transaktion:
  - `before`: Gruppe lief vor einer Runner-Transaktion oder vor dem
    stream-owned Transaktionsblock.
  - `inside`: Gruppe lief innerhalb einer bestaetigt aktiven Transaktion.
  - `after`: Gruppe lief nach Commit/Rollback eines stream-owned Blocks.
  - `none`: keine Transaktion gilt fuer diese Gruppe.
  Gemischte oder nicht belegbare Grenzen blockieren vor Execute mit
  `primaryBlockedReason = TRANSACTION_SCOPE_UNSUPPORTED`.
- `recoverability` wird nach einem Execute-Fehler aus Executor-Beobachtung,
  `transactionScope` und Dialekt-Hinweisen abgeleitet:
  - `FULL_ROLLBACK_CONFIRMED`: alle begonnenen Statements wurden nachweislich
    zurueckgerollt.
  - `ROLLBACK_ATTEMPTED`: Rollback wurde angestossen, das Ergebnis ist aber
    nicht voll bestaetigt.
  - `PARTIAL_STATE_POSSIBLE`: mindestens ein Statement kann trotz Fehler
    committed oder side-effectful sein.
  - `UNKNOWN`: der Runner kann den Zustand nicht belastbar einordnen.
- Exit `5` bleibt fuer jeden Fehler nach begonnenem Execute verbindlich.
  `transactionScope` veraendert nicht den Exit-Code, sondern nur
  `transactionRolledBack`, `sideEffectsPossible`, `recoverability` und die
  Diagnose. Ungueltige Scope-Kombinationen, die vor Execute erkannt werden,
  bleiben Migrations-Blocker mit Exit `8`.
- Neue `primaryBlockedReason`-Werte aus diesem Workstream sind erst stabil,
  wenn sie in `MigrationBlockedReason`, CLI-JSON, Report-Rendering und Tests
  ergaenzt sind. Fuer diesen Slice ist `TRANSACTION_SCOPE_UNSUPPORTED` der
  geplante neue Wert; bis zur Implementierung darf kein Report diesen String
  als stabilen Vertrag ausgeben.
- Mixed-Stream-Blocker: sobald ein Stream sowohl `STREAM_OWNED`- als auch
  `RUNNER_OWNED`-/`NO_TRANSACTION`-Statements enthaelt, blockiert die Ausfuehrung
  vor dem ersten Statement mit `TRANSACTION_SCOPE_UNSUPPORTED`. Bis dieser
  Slice umgesetzt ist, faellt der Classifier still auf "stream-owned" zurueck,
  sobald ein einziges Statement `STREAM_OWNED` ist. Das ist kein silent best-
  effort, sondern eine dokumentierte §G.3-Luecke; aktuelle Renderer
  produzieren keine Mixed Streams.

Akzeptanz:

- Je Dialekt ein Fehlerpfad nach begonnenem Execute.
- Report bleibt fuer alte Felder rueckwaertskompatibel.
- Keine Down-SQL-Finalisierung, wenn Up-Ausfuehrung partiell oder unklar ist.
- Mixed-Stream-Fall blockiert mit `TRANSACTION_SCOPE_UNSUPPORTED` und einem
  Regressionstest pro Dialekt; `MigrationStreamClassifier`-KDoc verweist auf
  diesen Vertrag.

DoD:

- [x] `MigrationDdlStatement` hat ein strukturiertes `transactionScope`-Feld
  mit dokumentierten Werten. (G.1)
- [x] Alle bestehenden Renderer setzen `transactionScope` explizit. (G.1)
- [~] Runner und Test-Support nutzen keine BEGIN-String-Heuristik mehr.
  `MigrationStreamClassifier` und `JdbcMigrationExecutor` lesen das Feld
  (G.1). Verbleibender transitionaler Sniff in
  `SchemaRollbackRunner.splitArtefactBody`, weil `rollback-sql v1` das
  Feld nicht traegt; faellt mit G.2 weg.
- [ ] Rollback-Artefakte serialisieren Statements strukturiert und ohne
  `\n\n`-Split-Vertrag. (G.2)
- [ ] Parser prueft `formatVersion`, Artifact-Hash und Statement-Reihenfolge.
  (formatVersion + Hash bereits in v1; Statement-Reihenfolge G.2)
- [ ] Execution-Report deckt Statement-Gruppen, Transaktionsgrenzen und
  Recoverability ab. (G.3)
- [ ] Mixed-Stream-Fall blockiert mit `TRANSACTION_SCOPE_UNSUPPORTED` und
  ist als Regressionstest gepinnt. (G.3)
- [x] Regressionstest fuer SQL-Body mit `BEGIN`-Token ohne Stream-Owned-
  Klassifikation. (G.1)
- [ ] Regressionstests fuer Leerzeilen-Bodies und manipulierte Artefakte. (G.2)
- [ ] Routine-/Trigger-Renderer bleiben blockiert, bis diese Checkboxen erfuellt
  sind. (gilt bis G.2 und G.3)

---

## 5. Workstream A - Dialekt- und Ausfuehrungshinweise

### A.1 Locking- und Transactional-DDL-Hinweise

Aus 0.9.7 offen:

- PostgreSQL-, MySQL- und SQLite-spezifische Hinweise zu DDL-Transaktionen,
  impliziten Commits, Locks und moeglichen Side Effects
- Darstellung im Migrate-Report und optional im SQL-Metadatenblock
- klare Trennung zwischen garantiert transaktionalem Verhalten,
  best-effort-Rollback und potenziell partiell angewendetem Zustand

Erwarteter Vertrag:

- pro Statement oder Statement-Gruppe eine strukturierte Einordnung:
  - `transactionBehavior`
  - `lockBehavior`
  - `implicitCommitPossible`
  - `sideEffectsPossible`
  - `requiresExclusiveAccess`, falls belastbar ableitbar
- Report zeigt, ob der Runner die Ausfuehrung vollstaendig zurueckrollen kann.
- Keine optimistischen Garantien fuer Dialekte mit impliziten DDL-Commits.
- SQL-Kommentare duerfen Hinweise spiegeln, der maschinenlesbare Report bleibt
  aber die kanonische Quelle.

Dialekt-Mindestabdeckung:

- PostgreSQL: transaktionale DDL vs. Operationen mit Einschraenkungen.
- MySQL: implizite Commits und Online-/Copy-ALTER-Unterschiede nur berichten,
  wenn sie sicher bestimmt werden koennen.
- SQLite: stream-owned Rebuild-Transaktion und `PRAGMA foreign_keys`-Einfluss.

Akzeptanz:

- Tests fuer mindestens je einen PostgreSQL-, MySQL- und SQLite-Pfad.
- Report-Felder sind stabil und maschinenlesbar.
- Dokumentation erklaert, wann `sideEffectsPossible=true` realistisch ist.
- Execute-Fehler nach Start enden weiterhin mit Exit `5`, nicht mit
  Migrations-Blocker Exit `8`.

DoD:

- [ ] `transactionBehavior` und `lockBehavior` sind als stabile Report-Felder
  definiert.
- [ ] PostgreSQL-, MySQL- und SQLite-Renderer liefern Dialekt-Hinweise fuer die
  erste relevante Statement-Matrix.
- [ ] Implizite Commits und nicht voll rollbackfaehige DDL werden nicht als
  garantiert transaktional berichtet.
- [ ] `sideEffectsPossible` ist aus Dialekt- und Scope-Information ableitbar und
  getestet.
- [ ] SQL-Metadatenblock und Report widersprechen sich nicht.
- [ ] Dokumentation beschreibt die Grenzen der Aussagen pro Dialekt.

### A.2 SQLite-Rebuild Live-`sqlite_master`-Probe im Execute-Pfad

Phase H des ersten 0.9.7-Plans formalisiert den SQLite-Rebuild-Vertrag und
loest Temp-Namen-Kollisionen plan-time ueber `SqliteCatalogSnapshot` mit
deterministischem `__2`/`__3`-Fallback. Heute stammt dieser Snapshot im
Execute-Pfad noch aus dem Schema-Modell. Ad-hoc-Objekte in der Live-DB, die
nicht im neutralen `SchemaDefinition`-Snapshot stehen, koennen deshalb erst beim
`CREATE TABLE <temp>` fehlschlagen. Dieser Slice ist die explizite H.2.2-
Fortsetzung: keine Rueckplanung von Phase H, sondern Execute-Hardening fuer
die bereits beschlossene Temp-Namen-Policy.

Entscheidung:

- `SqliteCatalogSnapshot` bekommt einen Live-Loader
  `fromSqliteMaster(conn)` oder einen aequivalenten Adapter-Port, der
  Tabellen, Views, Indizes und Trigger aus `sqlite_master`/`sqlite_schema`
  liest. Systemobjekte wie `sqlite_%` werden nicht als Nutzer-Kollisionen
  behandelt, ausser SQLite wuerde denselben Namen fuer ein explizites
  User-Objekt reservieren.
- Der Execute-Pfad fuer `schema migrate --execute` fuehrt die Probe vor dem
  Rebuild-Plan-Build aus und uebergibt
  `SqliteCatalogSnapshot.fromSchema(current).union(liveSnapshot)` an
  `SqliteRebuildPlanner.planRebuild`.
- Der Renderer bleibt pure consumption: `newTableTempName` wird weiterhin
  ausschliesslich im Plan eingefroren und nicht waehrend der SQL-Emission
  nachtraeglich geaendert.
- Datei-zu-Datei- und `--plan-only`-Pfade duerfen keine Live-DB-Erkenntnis
  vortaeuschen. Sie nutzen weiter den Schema-Snapshot und markieren im Report
  bzw. Artefakt-Header, dass die Live-Catalog-Probe nicht ausgefuehrt wurde.
- Datei-zu-DB ohne Execute muss explizit entscheiden, ob eine Live-Probe fuer
  den Plan erlaubt ist. Wenn kein Connection-Kontext existiert, gilt dasselbe
  Verhalten wie Datei-zu-Datei: schema-only Snapshot plus Diagnose
  `NOT_RUN_FILE_TARGET` oder aequivalenter Status.
- Wenn die Live-Probe wegen fehlender Berechtigung oder Metadata-Fehler
  fehlschlaegt, blockiert Execute vor dem ersten mutierenden Statement mit
  Exit `8` und maschinenlesbarer Diagnostic, statt in einen spaeteren
  `CREATE TABLE`-Fehler zu laufen.
- Trigger-Namen muessen gegen die echten SQLite-Objektnamen geprueft werden,
  nicht gegen kanonische neutrale Map-Keys wie `table::trigger`. Der Loader
  liefert deshalb SQL-Namen; `fromSchema` muss kanonische Keys fuer die
  Kollisionsmenge entsprechend normalisieren.

Akzeptanz:

- Positivtest: Live-DB enthaelt ein ad-hoc-Objekt mit dem Basis-Temp-Namen;
  `schema migrate --execute` plant deterministisch `<base>__2` und fuehrt den
  Rebuild erfolgreich aus.
- Suffix-Test: Live-DB enthaelt `<base>` und `<base>__2`; der Plan nutzt
  `<base>__3`.
- Trigger-Test: eine Live-Trigger-Kollision wird anhand des SQL-Triggernamens
  erkannt, auch wenn der neutrale Schema-Snapshot kanonische Trigger-Keys nutzt.
- Blocker-Test: Metadata-Probe-Fehler vor Execute endet ohne ausgefuehrte
  Mutationsstatements mit Exit `8` und klarer Diagnostic.
- Datei-zu-Datei-Test: derselbe Diff bleibt deterministisch, fuehrt keine
  Live-Probe aus und berichtet den schema-only Status.
- Report/SQL-Artefakt zeigen den verwendeten Catalog-Probe-Modus
  (`SCHEMA_ONLY`, `LIVE_SQLITE_MASTER`, `NOT_RUN_FILE_TARGET`) und den finalen
  Temp-Namen.

DoD:

- [ ] Live-Loader fuer SQLite-Catalog-Snapshot existiert und ist getrennt vom
  schema-pure `DiffPlanner`.
- [ ] Execute-Wiring uebergibt den Union-Snapshot vor `planRebuild`.
- [ ] Renderer bleibt frei von Live-DB-Probes.
- [ ] Datei-zu-Datei-/Plan-only-Verhalten ist explizit diagnostisch, nicht
  optimistisch.
- [ ] Trigger-Key-vs-SQL-Name-Kollisionen sind getestet.
- [ ] Report- und Exit-Code-Erwartungen sind gepinnt.

---

## 6. Workstream B - Erweiterte Typkonvertierungen

### B.1 PostgreSQL `USING`-Konvertierungen

0.9.7 rendert `ALTER COLUMN TYPE` nur fuer explizit getestete implizite Casts
ohne `USING`. Alles andere blockiert.

Entscheidung:

- Explizite `USING`-Ausdruecke sind nur ueber einen versionierten
  Migrations-Overlay zulaessig, der an `sourceFingerprint`,
  `targetFingerprint`, Dialekt, Tabellenname, Spaltenname, Quelltyp und Zieltyp
  gebunden ist und den Overlay-Grundvertrag aus F.0 erfuellt. Schema-Metadaten
  bleiben dafuer tabu, weil die Konvertierung ein Migrationsvertrag und kein
  Zielschemateil ist. Nach Workstream F.2 darf
  derselbe Vertrag in ein Plan-Artefakt eingebettet werden.
- Der Overlay muss Up- und Down-Seite getrennt beschreiben:
  `upUsingExpression`, optional `downUsingExpression`, `dataRisk`,
  `reversibility`, `expressionSource` und `reviewedByUser=true`.
  Fehlt `downUsingExpression`, ist Down entweder `MANUAL_REQUIRED` oder
  `NOT_REVERSIBLE`; der Renderer leitet keine Down-Expression aus Up ab.
- Zulaessige Risiko-Werte sind mindestens `NO_DATA_LOSS_EXPECTED`,
  `POSSIBLE_PRECISION_LOSS`, `POSSIBLE_TRUNCATION`,
  `POSSIBLE_PARSE_FAILURE` und `USER_ASSERTED_SAFE`. Alles ausser
  `NO_DATA_LOSS_EXPECTED` muss im Report sichtbar sein und kann je nach
  Rollback-Anforderung blockieren.
- Validierung erfolgt vor Render: Dialekt muss PostgreSQL sein, Operation muss
  exakt zur gebundenen Spalte passen, Quell-/Zieltyp muessen mit dem geplanten
  Diff uebereinstimmen, und die Expression muss eine einzelne PostgreSQL-
  Expression ohne Statement-Separator sein. Der Report nennt die Overlay-Quelle
  und die gebundenen Fingerprints.
- Standardregel: Die Expression darf nur die betroffene Spalte referenzieren.
  Referenzen auf andere Spalten sind erst in einem spaeteren Slice erlaubt,
  wenn der Overlay diese Dependencies explizit auflistet und der Planner sie
  gegen Rename-/Drop-Risiken validiert. Bis dahin blockieren solche
  Expressions mit `primaryBlockedReason = MANUAL_ACTION_REQUIRED`. Falls ein
  Down-Ausdruck fehlt, bleibt `MANUAL_REQUIRED` nur der Reversibility-/Down-
  Status, nicht der maschinenlesbare primaere Blocker-Reason.

Nicht akzeptabel:

- generisches `USING column::target_type` ohne Nutzerentscheidung
- automatische Konvertierung mit moeglichem Datenverlust ohne Blocker
- Down-Konvertierung aus Up-Expression ableiten, wenn sie nicht explizit
  reversibel beschrieben ist

Akzeptanz:

- Positivtest fuer explizit erlaubte `USING`-Expression.
- Blocker-Test ohne Expression.
- Blocker-Test fuer nicht reversible Down-Konvertierung bei
  `--generate-rollback`.
- Report weist Expression-Quelle, Risiko und Down-Status aus.

### B.2 SQLite Live-DB-Daten-Preflights fuer Casts

0.9.7 hat die SQLite-Cast-Matrix gehaertet. Offen bleibt der Live-DB-
Preflight vor whitelisted Casts.

Entscheidung:

- Der Live-DB-Preflight ist eine Pre-Render-Planungsbedingung fuer Datei-zu-DB-
  Targets mit whitelisted SQLite-Casts, sobald Bestandsdaten betroffen sein
  koennen. Er laeuft nach Diff-Planung und vor Render, damit fehlgeschlagene
  Datenchecks als Migrations-Blocker mit Exit `8` enden und nicht erst als
  Execute-Fehler.
- Der schema-pure `DiffPlanner` bleibt frei von Live-DB-Wissen. Die Preflight-
  Deklarationen erzeugt ein eigener `MigrationPreflightPlanner` zwischen
  `DiffPlanner` und Dialekt-Render: Input sind `DiffResult`, Zieldialekt,
  Target-Modus und, bei Datei-zu-DB, ein Preflight-Capability-/Connection-
  Kontext. Output ist ein erweiterter, weiterhin deterministischer Plananhang
  mit `preflightDeclarations`; Datei-zu-Datei erzeugt dieselben Operation-
  Bindungen mit Status `NOT_RUN_FILE_TARGET`, aber keine Live-SQL-Ausfuehrung.
- Der Runner erfindet keinen verdeckten Cast-Preflight. Er darf nur einen im
  Plan deklarierten Preflight (`preflightSqlHash`, Dialekt, Tabelle, Spalte,
  erwartete Statuswerte und Validierungszeitpunkt) ausfuehren oder
  revalidieren. Fehlt diese Deklaration, blockiert Execute vor dem ersten
  Statement; ein bloss gespeichertes `dataPreflightStatus = PASSED` reicht dann
  nicht.
- `PASSED` ist kein dauerhaft gueltiger Freifahrtschein. Fuer Execute gilt es
  nur innerhalb desselben geschuetzten Plan+Execute-Laufs, in dem der Preflight
  auf derselben Connection und unter der fuer den Cast geltenden
  Transaktions-/Lock-Strategie unmittelbar vor der Mutation validiert wurde.
  Wird ein Plan spaeter oder aus einem gespeicherten Artefakt ausgefuehrt, muss
  der Runner den im Plan deklarierten Preflight vor dem ersten mutierenden
  Statement erneut ausfuehren oder vor Execute blockieren. Der gespeicherte
  Status im Report ist dann nur Audit-Information.
- Nicht konvertierbare Bestandsdaten erzeugen eine Blocker-Diagnostic mit
  `primaryBlockedReason = MANUAL_ACTION_REQUIRED`, betroffener Tabelle/Spalte,
  Gesamtzahl der problematischen Zeilen und optional einer begrenzten
  Beispielmenge von Row-Identifiers, falls datenschutzarm verfuegbar.
- Datei-zu-Datei-Planung rendert keinen optimistischen Pass. Sie setzt
  `dataPreflightStatus = NOT_RUN_FILE_TARGET` und markiert die Operation als
  nur planbar, nicht execute-freigegeben. Wenn der Cast ohne Live-Preflight
  nicht verantwortbar ist, bleibt er fuer Execute blockierend.
- Der Report enthaelt standardmaessig Ergebnis, Zaehlungen, Dialekt, Tabellen-/
  Spaltenbindung und einen Hash des Preflight-SQL. Vollstaendiges Preflight-SQL
  wird nur mit Debug-/Verbose-Option ausgegeben, damit Reports keine
  schema- oder datenbezogenen Details unnoetig leaken.
- Grosse Tabellen werden nicht automatisch sampled. Default ist vollstaendige
  Pruefung. Eine spaetere Policy darf Limits erlauben, muss dann aber
  `dataPreflightStatus = NOT_RUN_POLICY` oder einen nicht-execute-faehigen
  Status setzen; ein Sample darf niemals als `PASSED` gelten.

Akzeptanz:

- Positiv- und Negativtests mit realer SQLite-DB.
- Datei-zu-Datei-Pfad bleibt deterministisch und markiert fehlende
  Live-Pruefung.
- Keine Cast-Ausfuehrung gegen Live-Daten ohne dokumentierten und fuer diesen
  Execute-Lauf frischen Preflight-Status.
- Report unterscheidet `PASSED`, `FAILED`, `NOT_RUN_FILE_TARGET` und
  `NOT_RUN_POLICY`.

DoD:

- [ ] PostgreSQL-`USING`-Expression-Quelle ist festgelegt und validiert.
- [ ] Up- und Down-Expressions werden getrennt gespeichert oder Down wird
  blockierend als manuell/nicht reversibel markiert.
- [ ] Generische Cast-Heuristiken ohne Nutzerentscheidung bleiben verboten.
- [ ] SQLite-Live-Preflight-Status ist im Report maschinenlesbar.
- [ ] `MigrationPreflightPlanner` oder ein aequivalenter Pre-Render-Baustein
  erzeugt die Preflight-Deklarationen; der Runner erfindet keine Preflight-SQL.
- [ ] Datei-zu-Datei-Planung berichtet fehlende Live-Pruefung ohne
  optimistischen Pass.
- [ ] Positive und blockierende Tests existieren fuer PostgreSQL `USING` und
  SQLite Live-Casts.

---

## 7. Workstream C - Extensions und Spatial

### C.1 Extension-Abhaengigkeiten

Aus 0.9.7 offen:

- PostgreSQL-Extensions, insbesondere PostGIS
- SpatiaLite-Abhaengigkeiten fuer SQLite
- Privilegien und Side Effects bei `CREATE EXTENSION`
- Abhaengigkeit von Typen, Funktionen, Operatoren, Indizes und Views auf
  Extensions

Erwarteter Vertrag:

- Extensions werden als Dependency sichtbar, nicht implizit installiert.
- `CREATE EXTENSION` ist nur mit expliziter Option/Policy renderbar.
- Fehlende oder nicht verifizierbare Extension-Abhaengigkeiten blockieren
  betroffene Operationen.
- Reverse muss zwischen "Extension vorhanden" und "Objekt nutzt Extension"
  unterscheiden.
- Datei-zu-Datei-Pfad darf Extension-Verfuegbarkeit nur aus Schema/Overlay
  ableiten, nicht annehmen.

Zu entscheiden:

- Flag/Policy fuer Installation, zum Beispiel `--allow-extension-install`.
- Report-Felder fuer `requiredExtensions`, `verifiedExtensions`,
  `missingExtensions` und `extensionInstallStatements`.
- Privilegienfehler: Blocker vor Render oder Execute-Fehler nach Start.

### C.2 Spatial-Migrationen

Offen:

- Spatial-Spalten bei diff-basierten Migrationen
- SRID-/Geometrie-Metadaten und Spatial-Indizes
- PostGIS-, MySQL-native- und SpatiaLite-Unterschiede
- Down-/Rollback-Semantik fuer Spatial-Zusatzobjekte

Akzeptanz:

- je Dialekt mindestens ein positiver und ein blockierender Spatial-Migrate-Test
- Report zeigt Profil, benoetigte Extension und manuelle Schritte
- keine partielle Tabellenmigration, bei der Spatial-Spalten still fehlen
- Spatial-Indizes und Metadaten werden entweder vollstaendig migriert oder
  blockieren die betroffene Operation

DoD:

- [ ] Extension-Dependencies werden im Modell oder Report sichtbar, nicht
  implizit installiert.
- [ ] Policy/Flag fuer Extension-Installation ist entschieden und getestet.
- [ ] Fehlende Extension, fehlendes Privileg und unbekannte Verfuegbarkeit
  erzeugen unterschiedliche Diagnostics.
- [ ] Spatial-Spalten, Spatial-Metadaten und Spatial-Indizes werden je Dialekt
  vollstaendig geplant oder blockieren.
- [ ] Datei-zu-Datei-Pfad nimmt Extension-Verfuegbarkeit nicht ohne Schema- oder
  Overlay-Signal an.
- [ ] PostgreSQL/PostGIS, MySQL Spatial und SQLite/SpatiaLite haben je einen
  positiven und einen blockierenden Testpfad.

---

## 8. Workstream D - Views und Materialized Views

### D.1 PostgreSQL Visible-Signature-Compatibility fuer Views

0.9.7 splittet `ReplaceView`, wenn referenzierte Tabellen-/Spaltenoperationen
konfligieren. Offen bleibt die sichtbare View-Spaltenform:

- Spaltenanzahl
- Spaltenreihenfolge
- sichtbare Spaltentypen
- Namen/Aliase, soweit PostgreSQL sie fuer `CREATE OR REPLACE VIEW`
  relevant macht

Moegliche Loesungen:

- `ViewColumn`-Modellebene im neutralen Modell / Reverse-Pfad
- Pre-Render-Probe gegen Live-DB
- konservativer Blocker bei fehlender Signaturinformation

Entscheidung:

- Ohne belastbare Signaturinformation darf `CREATE OR REPLACE VIEW` fuer
  PostgreSQL nicht optimistisch genutzt werden.
- Drop/Recreate ist nur erlaubt, wenn Dependencies, Locking und Rollback fuer
  die View und abhaengige Objekte bekannt sind.

Akzeptanz:

- Positivtest fuer kompatible Signatur.
- Blocker- oder Drop/Recreate-Test fuer inkompatible Signatur.
- Datei-zu-Datei-Test ohne Signaturdaten blockiert konservativ oder nutzt
  explizites Modellfeld.

### D.2 MySQL `VIEW_ROUTINE_USAGE`-Privilege-Preflight

0.9.7 behandelt `VIEW_TABLE_USAGE` als Vollstaendigkeitsproblem. Offen bleibt
die analoge Behandlung fuer `VIEW_ROUTINE_USAGE`.

Entscheidung:

- Es wird kein separates Sonderfeld `routineProjectionComplete` eingefuehrt.
  Stattdessen bekommt der MySQL-Dependency-Snapshot ein konsolidiertes
  Projection-Completeness-Modell pro Objekt:
  `tableProjectionStatus`, `columnProjectionStatus` und
  `routineProjectionStatus`.
- Jeder Status nutzt dieselbe Wertemenge: `COMPLETE`, `INCOMPLETE_PRIVILEGE`,
  `INCOMPLETE_OBJECT_MISSING`, `EMPTY_VERIFIED`, `UNKNOWN`. Eine leere
  Projektion ist nur dann verwertbar, wenn sie als `EMPTY_VERIFIED` aus einer
  erfolgreichen Probe ohne Privilegienfehler stammt.
- View-Replacement ist nur erlaubt, wenn alle drei Projection-Status fuer die
  betroffene View `COMPLETE` oder `EMPTY_VERIFIED` sind. Jeder andere Status
  blockiert vor Render mit einer Dependency-Diagnostic; versteckte Routine-
  Abhaengigkeiten werden nicht als Warnung behandelt.
- Der Report weist pro View `dependencyProjection` mit getrennten Table-,
  Column- und Routine-Status, getesteten Information-Schema-Views und
  Fehlerklasse aus. Tests muessen fehlende referenzierte Objekte, fehlende
  Privilegien und stille leere Projektionen getrennt abdecken.

Akzeptanz:

- MySQL-Test mit vollstaendiger Routine-Projection.
- MySQL-Test mit fehlenden Privilegien, der vor Render blockiert.
- Kein View-Replace unter potenziell versteckter Routine-Dependency.

### D.3 Materialized Views

Offen:

- Dependency-Graph fuer Materialized Views
- Drop/Recreate-Strategie
- Refresh-Strategie nach Migration
- PostgreSQL `REFRESH MATERIALIZED VIEW CONCURRENTLY` und dessen
  Voraussetzungen
- Locking, Staleness und Rollback-Verhalten

Nicht akzeptabel:

- Materialized Views wie normale Views behandeln
- Refresh still auslassen, wenn der Zielzustand danach fachlich stale ist
- `CONCURRENTLY` rendern, ohne die PostgreSQL-Voraussetzungen zu belegen

#### D.3a Sofortiger Materialized-View-Guard

Dieser Guard ist ein eigener kleiner Safety-Slice und darf nicht auf den
vollstaendigen Materialized-View-Vertrag warten:

- Jede diff-basierte Operation auf `ViewDefinition.materialized = true` muss als
  Materialized-View-Operation erkannt und blockiert werden, solange kein eigener
  Materialized-View-Vertrag implementiert ist. Das gilt auch fuer bestehende
  `CreateView`-/`ReplaceView`-/`DropView`-Operationen, damit Renderer
  Materialized Views nicht versehentlich ueber normale View-SQL-Pfade
  behandeln.
- PostgreSQL darf fuer `ReplaceView` mit `materialized = true` niemals
  `CREATE OR REPLACE VIEW` rendern. MySQL und SQLite duerfen Materialized Views
  im diff-basierten Migrationspfad nicht als Regular-View-Fallback rendern,
  solange D.3 keinen expliziten Emulations-/Blocker-Vertrag beschlossen hat.

Akzeptanz fuer D.3a:

- Positiv kein SQL-Render fuer `CreateView`/`ReplaceView`/`DropView` mit
  `materialized = true`.
- Blocker-Diagnostic nennt Objektname, Dialekt und Materialized-View-Status.
- Bestehende Regular-View-Pfade bleiben unveraendert.

#### D.3b Vollstaendiger Materialized-View-Vertrag

Erwarteter Vertrag:

- Materialized Views bekommen eigene Objektklasse und Diagnostics.
- Report weist `stalenessAfterUp` und geplante Refresh-Schritte aus.
- Rollback beschreibt, ob die alte Materialized View rekonstruiert und
  refreshed werden kann.

DoD:

- [ ] PostgreSQL-View-Replacement nutzt `CREATE OR REPLACE VIEW` nur bei
  belegter sichtbarer Signaturkompatibilitaet.
- [ ] Datei-zu-Datei-Pfad ohne Signaturdaten blockiert konservativ oder nutzt
  ein explizites Modellfeld.
- [ ] MySQL-Dependency-Projektion deckt Table-, Column- und Routine-Usage ab
  oder blockiert bei unvollstaendiger Projektion.
- [ ] Bis zum eigenen Materialized-View-Vertrag blockieren alle diff-basierten
  Operationen auf `ViewDefinition.materialized = true` vor Render.
- [ ] Der D.3a-Guard ist separat testbar und nicht vom vollstaendigen
  Refresh-/Staleness-Vertrag abhaengig.
- [ ] Materialized Views sind vor D.3b nicht als normale Views renderbar; im
  vollstaendigen D.3b-Vertrag bekommen sie eine eigene Objektklasse.
- [ ] Refresh-, Staleness-, Locking- und Rollback-Verhalten fuer Materialized
  Views sind fuer D.3b im Report sichtbar.
- [ ] Tests decken kompatible View-Replaces, blockierende View-Dependencies und
  mindestens einen Materialized-View-Blocker ab.

---

## 9. Workstream E - Weitere Objektklassen

### E.1 Routine-Migration

Nicht in der ersten Matrix:

- PostgreSQL `CREATE OR REPLACE FUNCTION`
- PostgreSQL `CREATE OR REPLACE PROCEDURE`
- MySQL-Routinen
- Dependency-Sortierung zwischen Routinen, Views, Triggern und Tabellen

Vorbedingung:

- Workstream G ist abgeschlossen, bevor Routine-Bodies mit `BEGIN ... END`
  sicher gerendert und rollbackfaehig gespeichert werden koennen.

Entscheidung:

- Der erste Routine-Slice nutzt keinen strukturierenden SQL-Parser. Vergleich
  und Identitaet basieren auf normalisiertem Routine-Text plus SHA-256-Hash:
  line endings normalisieren, fuehrende/trailing Whitespace entfernen,
  optionale abschliessende Semikolons kanonisieren, Body-Inhalt sonst
  unveraendert lassen. Unterschiedliche Hashes bedeuten Replace oder Blocker,
  kein semantisches Raten.
- Security-, Definer- und Search-Path-/SQL-Mode-Attribute sind Teil der
  Routine-Signatur. Reports zeigen Attribute maschinenlesbar, scrubben aber
  potenziell sensitive Literale im Body und geben standardmaessig nur Hash,
  Laenge und Scrubbed-Preview aus. Vollstaendige Bodies duerfen nur im
  Artefakt landen, wenn Secret-Scrubbing und Speichervertrag aus Workstream G/F
  greifen.
- Solange F.2 bzw. ein expliziter Secret-/Body-Speichervertrag nicht
  abgeschlossen ist, ist der erste Routine-Slice fuer Body-Artefakte Up-only:
  Replace/Create darf nur gerendert werden, wenn die Up-Seite sicher ist;
  `--generate-rollback` fuer Routine-Replace blockiert, statt alte Bodies
  unsicher im Rollback-Artefakt zu speichern.
- Down fuer Routine-Replace wird nur erzeugt, wenn der alte Body und alle
  relevanten Attribute aus Current-Snapshot oder Plan-Artefakt vollstaendig
  bekannt sind. Fehlt der Altzustand, ist Up optional renderbar, aber
  `--generate-rollback` blockiert mit `ROLLBACK_NOT_POSSIBLE` oder
  `MANUAL_ACTION_REQUIRED` je nach Operation.
- MySQL-Delimiter werden nie in Artefakte geschrieben. Renderer speichern
  Server-SQL als einzelnes strukturiertes Statement; CLI-Ausgaben fuer Menschen
  duerfen Delimiter nur als Anzeigeformat ausserhalb des kanonischen Artefakts
  ergaenzen.

### E.2 Trigger-Migration

Nicht in der ersten Matrix:

- vollstaendige Trigger-Migration fuer PostgreSQL, MySQL und SQLite
- Trigger-Bodies im Rollback-Artefakt
- Dependency- und Sortiervertrag gegen Tabellen, Spalten, Routinen und Views

Vorbedingung:

- keine `\n\n`-Split-Heuristik fuer Artefakt-Statements mehr
- keine BEGIN-String-Heuristik fuer Transaktionsfuehrung mehr

Entscheidung:

- Trigger werden als eigene Objektklasse modelliert. Das neutrale Mindestmodell
  enthaelt `tableRef`, `timing` (`BEFORE`, `AFTER`, `INSTEAD_OF`),
  `events` (`INSERT`, `UPDATE`, `DELETE` plus optionale Spaltenliste),
  `orientation` (`ROW`, `STATEMENT`, falls Dialekt unterstuetzt),
  `condition`, `bodyHash`, `bodyTextAvailability` und `enabledState`.
  Dialektfeatures ausserhalb dieses Modells blockieren, statt still
  normalisiert zu werden.
- Replace ist nur ein logischer Operationstyp. Gerendert wird je Dialekt als
  sicheres Drop/Create oder natives Replace, wenn vorhanden und getestet. Down
  fuer Replace erfordert den vollstaendigen alten Triggerzustand; sonst wird
  kein vollstaendiges Rollback-Artefakt erzeugt.
- Drop/Create-Trigger werden in der Dependency-Sortierung um Tabellen,
  Spalten, Routinen und Views herum geplant. Trigger, die auf geaenderte oder
  gedroppte Objekte zeigen, blockieren bis die Dependencies eindeutig sind.
- SQLite-Trigger innerhalb eines Rebuilds bleiben Phase-H-gebunden: Der
  Rebuild-Plan muss Drop/Recreate, Temp-Namen und FK-Pragma-Vertrag kennen,
  bevor Trigger-Migration fuer SQLite freigeschaltet wird.

### E.3 Sequence-Migrationen

Nicht in der ersten Matrix:

- PostgreSQL `CREATE/ALTER/DROP SEQUENCE`
- MySQL-Sequence-Emulation-Migration
- SQLite-Sequence-Emulation-Migration
- Nutzung von Sequences in Defaults und deren Reverse-/Compare-Stabilisierung

Verweis:

- `docs/planning/open/sqlite-sequence-emulation-plan.md`

Entscheidung:

- Sequence und Spalten-Default werden als getrennte Objekte mit expliziter
  Dependency modelliert. PostgreSQL-Ownership (`OWNED BY`) ist eine eigene
  Kante; MySQL-/SQLite-Emulation referenziert die jeweilige Helper-Struktur.
  Ein Default darf erst gerendert werden, wenn die referenzierte Sequence
  existiert oder im selben Plan vorher erstellt wird.
- Diffbar sind zunaechst nur deklarative Attribute:
  `start`, `increment`, `minValue`, `maxValue`, `cycle`, `cache`,
  `ownedBy` und Emulationsformat-Version. Unbekannte oder dialektspezifisch
  nicht verlustfrei snapshotbare Attribute blockieren die Sequence-Operation.
- Der aktuelle Sequence-Wert ist datenabhaengiger Laufzeitstatus. Automatische
  Migration dieses Werts ist nur erlaubt, wenn eine Live-DB-Pruefung den Wert
  liest, Zielkonflikte ausschliesst und die Policy `preserveCurrentValue`
  explizit gesetzt ist. Datei-zu-Datei-Pfade markieren den Wert als
  `NOT_RUN_FILE_TARGET` und rendern keine Wertuebernahme.
- Down nach bereits verbrauchten Werten ist nicht voll reversibel. Rollback darf
  deklarative Attribute zuruecksetzen, aber keinen frueheren Current-Value
  versprechen, ausser ein expliziter Snapshot wurde im Artefakt gespeichert und
  die Policy erlaubt das Zuruecksetzen. Andernfalls ist die Wertkomponente
  `NOT_REVERSIBLE`.

Akzeptanz fuer E:

- Jede neue Objektklasse hat eigene Operationstypen oder eine explizite
  Begruendung, warum vorhandene Operationen reichen.
- Sortier- und Dependency-Tests decken mindestens eine Kette mit Tabelle,
  View/Routine und der neuen Objektklasse ab.
- Down-SQL wird nur erzeugt, wenn der alte Body bzw. alte Zustand vollstaendig
  bekannt ist.

DoD:

- [ ] Workstream G ist abgeschlossen, bevor Routine- oder Trigger-Bodies
  gerendert werden.
- [ ] Routine-, Trigger- und Sequence-Zustaende sind im neutralen Modell oder
  in einem dialektspezifisch begrenzten Vertrag beschrieben.
- [ ] Body-Vergleich und Body-Speicherung sind deterministisch und
  secret-scrubbed.
- [ ] Down-Erzeugung fuer Replace-Operationen erfordert vollstaendig bekannten
  Altzustand.
- [ ] Dependency-Sortierung deckt Tabellen, Views, Routinen, Trigger und
  Sequences ab.
- [ ] SQLite-Rebuild-Interaktion fuer Trigger bleibt mit Phase H abgestimmt.
- [ ] Jede freigeschaltete Objektklasse hat Positiv-, Blocker- und
  Rollback-Tests.

---

## 10. Workstream F - Datenmigration und DiffResult-Produktvertraege

### F.0 Versionierter Migrations-Overlay-Grundvertrag

Mehrere Workstreams brauchen Nutzerentscheidungen, die weder ins Zielschema
gehoeren noch als ad-hoc CLI-Flag stabil genug sind:

- PostgreSQL-`USING`-Expressions aus B.1
- Rename-Mappings aus F.4
- Extension-/Spatial-Verfuegbarkeitshinweise aus C, falls sie nicht aus dem
  Schema selbst ableitbar sind
- spaetere Daten-Transformationsvertraege aus F.1

Dieser Grundvertrag ist ein kleiner eigener Vorbereitungs-Slice vor allen
Features, die ein Overlay als Input akzeptieren.

Entscheidung:

- Overlay-Dateien sind versionierte, kanonisch lesbare JSON-Dokumente. YAML
  oder andere Formate duerfen spaeter als Komfortschicht folgen, sind aber
  nicht der erste stabile Austauschvertrag.
- Jedes Overlay enthaelt mindestens `formatVersion`, `overlayKind`,
  `sourceFingerprint`, `targetFingerprint`, `dialect`, `entries`,
  `createdAt`, `createdByVersion` und `overlayHash`.
- `overlayHash` ist ein SHA-256 ueber die kanonische JSON-Form ohne das
  `overlayHash`-Feld. Aenderungen an Bindings, Entry-Reihenfolge,
  Nutzerfreigaben, Risiken oder Expressions muessen den Hash aendern.
- Entries sind strikt typisiert. Ein `USING`-Entry kann nicht als Rename-Entry
  interpretiert werden und umgekehrt.
- Stale Fingerprints, falscher Dialekt, unbekannte `formatVersion`, unbekannte
  `overlayKind`, unbekannte Pflichtfelder und Hash-Mismatch blockieren vor
  Render.
- Unbekannte optionale Felder sind nur dekorative Producer-Metadaten. Jede
  Semantik fuer Ausfuehrung, Risiko, Rollback, Dependencies, Preflights oder
  Secrets braucht ein versioniertes Feld, ein `requiredFeature` oder eine neue
  `formatVersion`.
- Secret-Scrubbing gilt auch fuer Overlays. Als secret markierte Werte duerfen
  nicht in Reports oder Plan-Artefakte kopiert werden; Reports duerfen nur
  Quelle, Entry-ID, Hash und maschinenlesbare Diagnose ausweisen.
- CLI-Flags duerfen nur auf Overlay-Dateien zeigen oder konkrete Overlay-
  Policies aktivieren. Sie duerfen keine inline Renames, Expressions oder
  Daten-Transformationslogik als unversionierten Vertrag einfuehren.

Akzeptanz:

- Golden-File-Test fuer kanonische Overlay-Serialisierung.
- Ablehnung bei stale Fingerprint, Dialekt-Mismatch, unbekannter Version und
  Hash-Mismatch.
- Report nennt Overlay-Quelle, Entry-ID, Hash und Blocker-Diagnostic ohne
  Secrets.
- B.1 und F.4 haben je einen Test, der ohne gueltigen F.0-Overlay-Vertrag
  blockiert.

### F.1 Automatische Daten-Transformationen

Nicht im ersten 0.9.7-Plan abgedeckt:

- automatische Transformation von Bestandsdaten ueber Typkonvertierung hinaus
- Backfills mit fachlicher Logik
- Datenrekonstruktion nach destruktiven Operationen

Erwarteter Vertrag:

- manuelle Schritte bleiben `MANUAL_REQUIRED`, bis ein explizites
  Transformationsmodell existiert
- automatische Transformationen muessen Up und Down getrennt beschreiben
- Rollback darf keine Daten rekonstruieren, die nicht im Plan gesichert wurden
- Daten-Snapshots oder Copy-Back-Strategien brauchen explizite Storage-,
  Privacy- und Retention-Regeln

Nicht akzeptabel:

- DML-Backfill aus Spaltennamen oder Defaults erraten.
- Destruktive Operationen als reversibel markieren, nur weil ein Down-DDL
  strukturell moeglich ist.

### F.2 Versionierte Plan-Artefakte

Nicht im ersten 0.9.7-Plan abgedeckt:

- serialisierter `DiffResult` als oeffentlicher Input
- `schema rollback` direkt aus Plan-Artefakt
- versionierte Plan-Kompatibilitaet

Entscheidung:

- Das oeffentliche Plan-Artefakt wird als kanonisches JSON definiert. YAML darf
  spaeter als reine Anzeige-/Import-Komfortschicht folgen, ist aber nicht das
  signierte oder gehashte Austauschformat. Feldreihenfolge, Null-Behandlung,
  Zahlenformat und String-Escaping werden fuer Golden-Files festgelegt.
- Secret-Scrubbing ist Pflicht vor Serialisierung. Connection-Strings,
  Passwoerter, Tokens, Rollen-Passwoerter, `CREATE USER`-/`ALTER USER`-
  Secrets und als secret markierte Overlay-Werte duerfen nicht im Artefakt
  stehen. Bodies werden nur gespeichert, wenn der jeweilige Workstream einen
  Scrubbing-Vertrag hat; sonst werden Hash und Blocker gespeichert.
- `artifactHash` ist ein SHA-256 ueber die kanonische JSON-Form ohne das
  `artifactHash`-Feld. Eine optionale Signatur kann spaeter denselben Hash
  signieren; sie ist nicht Voraussetzung fuer v1. Jede Aenderung an
  Operationsreihenfolge, Diagnostics oder gerenderten SQL-Referenzen muss den
  Hash aendern.
- Kompatibilitaet: gleiche `formatVersion` muss innerhalb einer
  d-migrate-Minor-Linie lesbar bleiben. Das Artefakt enthaelt
  `requiredFeatures` und optional `semanticExtensions`; unbekannte Eintraege in
  diesen Listen blockieren vor Render oder Execute. Unbekannte optionale Felder
  duerfen nur dekorative oder producer-spezifische Metadaten tragen, niemals
  Ausfuehrungs-, Risiko-, Rollback-, Locking-, Preflight- oder
  Secret-Scrubbing-Semantik. Solche Semantik muss entweder Pflichtfeld,
  `requiredFeatures` oder neue `formatVersion` sein. Unbekannte Pflichtfelder
  oder inkompatible `formatVersion` blockieren. Neue Pflichtfelder erfordern
  eine neue `formatVersion`.
- Das Artefakt enthaelt beide Ebenen: einen dialektneutralen Operationskern und
  einen dialektspezifischen Render-Abschnitt fuer genau einen Zieldialekt.
  Datei-zu-Datei kann den Render-Abschnitt auslassen, dann ist das Artefakt
  nicht execute-faehig.
- Unbekannte Felder sind per Definition ignorierbare dekorative Producer-
  Metadaten. Ein unbekanntes Feld kann nicht nachtraeglich als Pflichtfeld
  interpretiert werden. Jede neue Semantik, die Ausfuehrung, Risiko, Rollback,
  Locking, Preflight, Secret-Scrubbing oder SQL-Bindung beeinflusst, muss
  entweder ueber `requiredFeatures`, eine neue `formatVersion` oder ein
  explizit versioniertes Schema-Feld signalisiert werden; fehlt dieses Signal,
  muss der Consumer das Feld ignorieren und darf daraus keine Semantik ableiten.
- Gerenderte SQL-Statements sind nicht frei im Plan-Artefakt vermischt. Sie
  werden entweder als strukturierter `renderedStatements`-Abschnitt mit
  Statement-IDs, Gruppen, Hashes und `transactionScope` gespeichert oder als
  separates SQL-Artefakt referenziert. In beiden Faellen prueft der Hash die
  Bindung zwischen Plan und SQL.

Mindestfelder:

- `formatVersion`
- `dMigrateVersion`
- `sourceFingerprint`
- `targetFingerprint`
- `dialect`
- `operations`
- `diagnostics`
- `reversibilitySummary`
- `requiredFeatures`
- `createdAt`
- `artifactHash`

Akzeptanz:

- Golden-File-Test fuer kanonische Serialisierung.
- Forward-/Backward-Kompatibilitaetstest fuer unbekannte optionale Felder.
- Ablehnung unbekannter Pflichtfelder oder inkompatibler `formatVersion`.
- Nachweis, dass keine Connection-Strings, Passwoerter oder Secrets landen.

### F.3 Partial Rollbacks

Nicht im ersten 0.9.7-Plan abgedeckt:

- `--allow-partial-rollback`
- bewusst unvollstaendige Down-Artefakte
- Rollback trotz `MANUAL_REQUIRED`- oder `NOT_REVERSIBLE`-Teilmenge

Erwarteter Vertrag:

- explizite Nutzerfreigabe
- maschinenlesbare Liste ausgelassener Operationen
- keine Darstellung als vollstaendiges Rollback
- Exit-/Report-Semantik unterscheidet "vollstaendig erzeugt" und
  "partial bewusst erzeugt"
- `schema rollback --execute` akzeptiert partial Artefakte nur mit expliziter
  nicht-interaktiver Freigabe, zum Beispiel `--allow-partial-rollback` oder
  einer gleichwertigen Policy im Artefakt/Request. Es gibt keine TTY-Rueckfrage;
  ohne diese Freigabe blockiert Execute vor dem ersten Statement.

Nicht akzeptabel:

- Partial-Down-SQL mit normalem `rollbackComplete=true`.
- ausgelassene Operationen nur als Freitext-Warnung.

### F.4 Rename-Mappings

Nicht im ersten 0.9.7-Plan abgedeckt:

- `RenameTable`
- `RenameColumn`
- Nutzer-Mapping fuer Rename-Kandidaten

Der erste 0.9.7-Plan bleibt bei Diagnose-Hints. Dieser zweite Plan braucht
einen expliziten Rename-Input-Vertrag, sonst wird Drop+Add mit Datenverlust
verwechselt.

Entscheidung:

- Rename-Mappings kommen aus einem versionierten Migrations-Overlay, der den
  Grundvertrag aus F.0 erfuellt; nach Workstream F.2 koennen sie in ein
  Plan-Artefakt eingebettet werden. CLI-Flags duerfen hoechstens auf eine
  Overlay-Datei zeigen. Schema-Metadaten sind keine Quelle, weil Renames eine
  Beziehung zwischen zwei Zustaenden beschreiben.
- Jedes Mapping bindet `sourceFingerprint`, `targetFingerprint`, Dialekt,
  Objektart, alte Referenz, neue Referenz und optional erwartete Struktur-
  Fingerprints. Stale Fingerprints blockieren mit dem neuen geplanten
  `primaryBlockedReason = RENAME_MAPPING_INVALID` und einer maschinenlesbaren
  Rename-Mapping-Diagnostic `STALE_FINGERPRINT`. `TARGET_STATE_MISMATCH` bleibt
  fuer Rollback-Zielzustandsdrift reserviert und wird fuer stale
  Rename-Overlays nicht wiederverwendet.
- Eindeutigkeit ist strikt: Ein altes Objekt darf genau ein neues Ziel haben
  und ein neues Objekt genau eine alte Quelle. Ueberlappungen, Kettenrenames im
  selben Slice, Case-Folding-Konflikte und mehrere plausible Kandidaten ohne
  explizite Auswahl blockieren.
- Down fuer Rename ist automatisch nur der inverse Rename, wenn die Up-
  Operation allein ein Rename ist und keine zwischenzeitliche Drop/Add-
  Semantik oder Objekt-Recreation erzeugt. Sobald abhaengige Objekte
  mitgeaendert werden, muss der Down-Plan diese Dependencies explizit
  enthalten oder `--generate-rollback` blockiert.
- Abhaengigkeiten werden vor Render validiert. FKs, Indizes, Constraints,
  Views, Trigger, Defaults, Routinen und Sequence-Defaults duerfen nicht auf
  alte Namen zeigen bleiben. Kann der Planner eine Dependency nicht vollstaendig
  projizieren, wird der Rename nicht gerendert. Datei-zu-Datei darf nur die im
  Modell/Overlay bekannten Dependencies bewerten und muss fehlende Live-
  Projektion im Report ausweisen.
- `RENAME_MAPPING_INVALID` ist wie `TRANSACTION_SCOPE_UNSUPPORTED` erst ein
  stabiler Report-Wert, nachdem Enum, CLI-JSON, Report-Rendering und Tests
  angepasst sind. Bis dahin muss ein Implementierungsslice auf bestehende
  Blocker-Reasons mappen und die genauere Rename-Diagnostic separat ausweisen.

Akzeptanz:

- Positivtest fuer Tabellen-Rename ohne Datenverlust.
- Positivtest fuer Spalten-Rename mit abhaengigem Index/Constraint.
- Blocker-Test fuer mehrdeutiges Mapping.
- Blocker-Test fuer stale Mapping gegen veraenderten Fingerprint.

### F.5 CHECK-/EXCLUDE-Constraint-Diffbarkeit

0.9.7 blockiert Tabellen mit `CHECK`-/`EXCLUDE`-Constraints ueber einen
Pre-Normalization-Detector, statt sie still wegzunormalisieren.

In diesem Plan moeglich:

- echte Diffbarkeit solcher Constraints
- Ausdruckskanonisierung oder konservativer SQL-Text-Vergleich
- dialektspezifische Enforcement-Regeln, insbesondere fuer MySQL

Entscheidungspunkte:

- Vergleichsmodell: strukturierte Expression, kanonischer SQL-Text oder
  bewusst konservativer Textvergleich.
- Dialekt-Feature-Matrix: PostgreSQL `CHECK`/`EXCLUDE`, MySQL `CHECK`
  Enforcement nach Version, SQLite `CHECK`.
- Reversibilitaet bei Constraint-Replace.
- Daten-Preflight fuer neue restriktive Constraints.

Akzeptanz:

- Tabellen mit unveraendertem `CHECK` blockieren nicht mehr, sobald das Modell
  sie verlustfrei vergleichen kann.
- Aenderung eines `CHECK` erzeugt planbare Operation oder konservativen
  Blocker mit konkreter Begruendung.
- MySQL-Version/Enforcement-Unklarheit blockiert statt stiller Annahme.

DoD:

- [ ] Der F.0-Overlay-Grundvertrag ist fuer alle Overlay-basierten Workstreams
  definiert, gehasht, fingerprint-gebunden und secret-scrubbed.
- [ ] Automatische Daten-Transformationen haben ein explizites Up-/Down-Modell
  oder bleiben `MANUAL_REQUIRED`.
- [ ] Versioniertes Plan-Artefakt hat kanonische Serialisierung,
  `formatVersion`, Hash und Secret-Scrubbing.
- [ ] Partial-Rollback-Artefakte sind maschinenlesbar als partial markiert und
  listen ausgelassene Operationen.
- [ ] Rename-Mappings sind fingerprint-gebunden und gegen Mehrdeutigkeit
  validiert.
- [ ] CHECK-/EXCLUDE-Diffbarkeit nutzt ein entschiedenes Vergleichsmodell und
  blockiert bei Dialekt- oder Enforcement-Unklarheit.
- [ ] Fuer F.0 und F.2 bis F.5 existieren Golden-File-, Kompatibilitaets- und
  Blocker-Tests.
- [ ] Kein Produktvertrag stellt ein unvollstaendiges Rollback als vollstaendig
  dar.

---

## 11. Test- und Coverage-Follow-ups

### 11.1 MySQL `AlterColumnNullability` im Round-Trip-Smoke

Der MySQL-Smoke fuer 0.9.7 laesst `AlterColumnNullability` ausdruecklich als
Coverage-Carve-out stehen. Dieser Plan sollte einen echten Round-Trip-Smoke
fuer diese Operation abdecken, sofern der Produktvertrag weiter renderbar
bleibt.

Akzeptanz:

- Up-Smoke mit `NULL -> NOT NULL` nur nach Daten-Preflight oder konservativem
  Blocker.
- Up+Down-Smoke fuer `NOT NULL -> NULL -> NOT NULL`, soweit reversibel.
- Report weist Datenrisiko und Locking-Hinweis aus.

### 11.2 Cross-Dialekt-Regressionsmatrix

Fuer alle Workstreams gilt:

- mindestens ein Positivpfad
- mindestens ein blockierender Pfad
- Report-/Exit-Code-Abdeckung
- Rollback-Verhalten oder explizite Begruendung, warum kein Rollback erzeugt
  werden darf
- Datei-zu-Datei-Verhalten, wenn der Workstream Live-DB-Wissen braucht

### 11.3 Artifact- und Overlay-Compatibility-Tests

Sobald Workstream G oder F.2 Artefaktformate aendert:

- alte gueltige Artefakte werden entweder bewusst weiter akzeptiert oder mit
  klarer Diagnose abgelehnt
- manipulierte Hashes blockieren
- unbekannte Versionen blockieren
- Secret-Scrubbing wird als Testfall gepinnt

Sobald Workstream F.0 Overlay-Formate einfuehrt:

- unbekannte Overlay-Versionen und unbekannte Overlay-Kinds blockieren
- stale Fingerprints, Dialekt-Mismatch und manipulierte Overlay-Hashes
  blockieren
- Secret-Scrubbing und dekorative unbekannte Felder werden als Testfaelle
  gepinnt

DoD:

- [ ] MySQL-`AlterColumnNullability` ist im Round-Trip-Smoke abgedeckt oder als
  bewusst blockierender Fall dokumentiert.
- [ ] Jeder umgesetzte Workstream hat mindestens einen Positivpfad und einen
  blockierenden Pfad.
- [ ] Report- und Exit-Code-Erwartungen sind in Tests gepinnt.
- [ ] Rollback-Verhalten ist getestet oder mit konkreter Blocker-Begruendung
  ausgeschlossen.
- [ ] Artifact-Compatibility-Tests decken alte Versionen, unbekannte Versionen,
  manipulierte Hashes und Secret-Scrubbing ab.
- [ ] Overlay-Compatibility-Tests decken unbekannte Versionen, unbekannte Kinds,
  stale Fingerprints, Dialekt-Mismatch, manipulierte Hashes und Secret-
  Scrubbing ab.

---

## 12. Priorisierungsvorschlag

Empfohlene Reihenfolge nach Risiko und Abhaengigkeiten:

1. Workstream G: `transactionScope`, strukturierte Statement-Serialisierung und
   Execution-Status. Diese Punkte sind Vorbedingung fuer Routinen, Trigger und
   komplexe Bodies.
2. Workstream A: Locking-/Transactional-DDL-Hinweise, weil sie den bestehenden
   Execute-Vertrag schaerfen, ohne neue Objektklassen freizuschalten.
3. Workstream A.2: SQLite-Rebuild Live-`sqlite_master`-Probe im Execute-Pfad,
   weil sie den bestehenden Phase-H-Vertrag gegen Live-Catalog-Drift haertet,
   ohne neuen SQL-Scope freizuschalten.
4. Workstream D.1/D.2: View-Dependency-Hardening fuer PostgreSQL/MySQL, weil
   Views bereits im ersten Slice enthalten sind.
   Vor D.1/D.2 oder als erster Teil davon muss der D.3a-Guard fuer
   `materialized = true` aktiv sein, damit Materialized Views nicht laenger ueber
   normale View-Pfade gerendert werden.
5. Workstream F.0: versionierter Migrations-Overlay-Grundvertrag. Dieser kleine
   Produktslice ist Vorbedingung fuer alle Features, die Nutzerentscheidungen
   als Overlay akzeptieren, insbesondere B.1 und F.4.
6. Workstream B: erweiterte Typkonvertierungen und Live-Daten-Preflights. B.1
   darf erst Overlay-Input akzeptieren, wenn F.0 umgesetzt ist; B.2 kann separat
   geplant werden.
7. Workstream F.5: CHECK-/EXCLUDE-Diffbarkeit, weil der erste Slice hier
   bewusst blockiert.
8. Workstream C/D.3b/E: Extensions, Spatial, Materialized Views sowie
   Up-only/blockierende Routine-, Trigger- und Sequence-Slices, die keine
   unsichere Body-Speicherung und kein vollstaendiges Routine-/Trigger-
   Rollback-Artefakt brauchen.
9. Workstream F.1-F.4: neue Produktvertraege fuer Daten-Transformationen,
   Plan-Artefakte, Partial Rollbacks und Rename-Mappings. F.4 darf den bereits
   umgesetzten F.0-Overlay-Vertrag nutzen; F.2 bzw. ein expliziter
   Secret-/Body-Speichervertrag muss vor Routine-/Trigger-Slices liegen, die
   alte Bodies im Artefakt speichern oder vollstaendige Replace-Rollbacks
   versprechen.

Diese Reihenfolge ist konservativ: erst Artefakt- und Runner-Sicherheiten,
dann bestehende SQL-Oberflaeche haerten, danach neue Objektklassen und
Produktvarianten freischalten.

---

## 13. Definition of Done fuer dieses Folgepaket

Ein Folgepaket gilt als sauber geplant, wenn fuer jeden aufgenommenen Punkt
vor Implementierung diese Checkboxen abgehakt sind:

- [ ] Betroffener Modus ist geklaert: Datei-zu-Datei, Datei-zu-DB, Execute,
  Rollback oder alle.
- [ ] Renderbare Operationen und weiterhin blockierende Operationen sind
  explizit benannt.
- [ ] Neue Diagnostics, Blocker und `primaryBlockedReason`-Folgen sind
  spezifiziert.
- [ ] Up- und Down-Verhalten sind getrennt bewertet.
- [ ] Report- und Metadatenfelder sind stabilisiert.
- [ ] Betroffene Dialekte und Dialektgrenzen sind dokumentiert.
- [ ] Falls ein Slice Overlay-Input akzeptiert, ist F.0 erfuellt oder der Slice
  bleibt bis dahin blockierend/diagnostisch.
- [ ] Positive und blockierende Testpfade sind definiert.
- [ ] Rollback-Test oder begruendeter Rollback-Blocker ist definiert.
- [ ] Datei-zu-Datei-Verhalten ist fuer Live-DB-abhaengige Features definiert.
- [ ] Bestehende 0.9.7-Vertraege bleiben unveraendert oder die Abweichung ist
  als eigene Entscheidung dokumentiert.
- [ ] Der Slice kann unabhaengig implementiert und verifiziert werden.

Ein Punkt darf erst aus diesem Dokument in `in-progress` wandern, wenn er einen
kleinen, separat testbaren Slice bildet. Grosse Sammel-Slices fuer mehrere
Objektklassen sind zu vermeiden, weil sie die Rollback- und Report-Vertraege
sonst gleichzeitig veraendern.
