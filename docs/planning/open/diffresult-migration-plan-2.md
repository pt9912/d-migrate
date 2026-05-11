# Implementierungsplan: DiffResult-Migrationen 0.9.7, Teil 2

> Status: Draft, verfeinert (2026-05-11)
>
> Zweck: Folgeplan fuer die offenen Punkte und Carve-outs aus dem ersten
> `DiffResult`-Slice. Dieses Dokument sammelt nur Themen, die fuer 0.9.7
> separat entschieden, vertraglich stabilisiert oder bewusst nachgelagert
> werden muessen.
>
> Ausdruecklich nicht Teil dieses Dokuments: Phase H aus
> `docs/planning/in-progress/diffresult-migration-plan.md`
> (SQLite-Rebuild-Vertrag formalisieren). Phase H bleibt im 0.9.7-Plan
> selbst gefuehrt, weil sie dort als strukturelle Akzeptanzluecke dokumentiert
> ist.
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

- Phase H: formaler SQLite-Rebuild-Plan, Temp-Namen-Kollision,
  Drop+Recreate abhaengiger Views/Trigger, FK-Pragma-Restore und
  vollstaendige SQLite-Rebuild-Preflights
- Ruecknahme der 0.9.7-Blocker-Strategie
- generisches SQL-Raten bei unbekannten Dependencies
- automatische Datenmigration ohne expliziten Nutzervertrag
- implizite Installation von Extensions
- oeffentliche Stabilitaetsgarantie fuer interne `DiffResult`-Details, solange
  kein versioniertes Plan-Artefakt beschlossen ist

Phase H wird absichtlich nicht dupliziert. Sobald Phase H abgeschlossen ist,
kann dieses Dokument darauf aufbauen, ohne die H-Tasks neu zu definieren.

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
  - JSON-Array im Metadatenblock mit SQL, Operation-IDs, Phase,
    `transactionScope`, Risiken und Hash pro Statement; oder
  - eindeutig spezifiziertes Length-Prefix-Format.
- `formatVersion` unterscheidet alte und neue Formate eindeutig.
- Parser lehnt unbekannte Versionen blockierend ab.
- Ein kontrollierter Migrationspfad fuer alte `rollback-sql v1`-Artefakte wird
  explizit entschieden: weiter unterstuetzen, upgraden oder ablehnen.
- Artifact-Hash deckt Header und strukturierten Statement-Body ab.

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

Zu klaeren:

- Statement-Gruppen-IDs fuer Multi-Statement-Operationen.
- `transactionBoundary` im Report: `before`, `inside`, `after`, `none`.
- `recoverability` nach Ausfuehrungsfehler: `FULL_ROLLBACK_CONFIRMED`,
  `ROLLBACK_ATTEMPTED`, `PARTIAL_STATE_POSSIBLE`, `UNKNOWN`.
- Zusammenhang zwischen `transactionScope`, Dialekt und Exit `5`.

Akzeptanz:

- Je Dialekt ein Fehlerpfad nach begonnenem Execute.
- Report bleibt fuer alte Felder rueckwaertskompatibel.
- Keine Down-SQL-Finalisierung, wenn Up-Ausfuehrung partiell oder unklar ist.

DoD:

- [ ] `MigrationDdlStatement` hat ein strukturiertes `transactionScope`-Feld
  mit dokumentierten Werten.
- [ ] Alle bestehenden Renderer setzen `transactionScope` explizit.
- [ ] Runner und Test-Support nutzen keine BEGIN-String-Heuristik mehr.
- [ ] Rollback-Artefakte serialisieren Statements strukturiert und ohne
  `\n\n`-Split-Vertrag.
- [ ] Parser prueft `formatVersion`, Artifact-Hash und Statement-Reihenfolge.
- [ ] Execution-Report deckt Statement-Gruppen, Transaktionsgrenzen und
  Recoverability ab.
- [ ] Regressionstests decken SQL-Bodies mit `BEGIN`, Leerzeilen und
  manipulierten Artefakten ab.
- [ ] Routine-/Trigger-Renderer bleiben blockiert, bis diese Checkboxen erfuellt
  sind.

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

---

## 6. Workstream B - Erweiterte Typkonvertierungen

### B.1 PostgreSQL `USING`-Konvertierungen

0.9.7 rendert `ALTER COLUMN TYPE` nur fuer explizit getestete implizite Casts
ohne `USING`. Alles andere blockiert.

Zu klaeren:

- Nutzervertrag fuer explizite `USING`-Ausdruecke.
- Ablage solcher Ausdruecke:
  - Schema-Metadatum
  - Overlay-Datei
  - Migrations-Request
  - explizites Plan-Artefakt nach Workstream F
- Risiko- und Reversibilitaetsmodell pro Konvertierung.
- Down-Konvertierung: automatisch, manuell oder nicht reversibel.
- Validierung gegen Quell-/Zieltyp und betroffene Spalte.
- Ob Expressions auf andere Spalten zugreifen duerfen; falls ja, wie
  Dependency und Rename-Risiko modelliert werden.

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

Zu klaeren:

- wann der Planner eine Connection braucht
- ob der Preflight vor Render, vor Execute oder im Runner-Vertrag laeuft
- wie nicht konvertierbare Bestandsdaten als `MANUAL_REQUIRED` blockieren
- wie Datei-zu-Datei-Planung ohne Live-DB diesen Punkt berichtet
- ob Preflight-SQL im Report offengelegt wird oder nur Ergebnis und Zaehlung
- wie grosse Tabellen begrenzt oder sampled werden duerfen; Default muss
  konservativ vollstaendig pruefen oder blockieren

Akzeptanz:

- Positiv- und Negativtests mit realer SQLite-DB.
- Datei-zu-Datei-Pfad bleibt deterministisch und markiert fehlende
  Live-Pruefung.
- Keine Cast-Ausfuehrung gegen Live-Daten ohne dokumentierten Preflight-Status.
- Report unterscheidet `PASSED`, `FAILED`, `NOT_RUN_FILE_TARGET` und
  `NOT_RUN_POLICY`.

DoD:

- [ ] PostgreSQL-`USING`-Expression-Quelle ist festgelegt und validiert.
- [ ] Up- und Down-Expressions werden getrennt gespeichert oder Down wird
  blockierend als manuell/nicht reversibel markiert.
- [ ] Generische Cast-Heuristiken ohne Nutzerentscheidung bleiben verboten.
- [ ] SQLite-Live-Preflight-Status ist im Report maschinenlesbar.
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

Zu klaeren:

- separates `routineProjectionComplete`-Signal oder konsolidiertes
  Dependency-Projektionsmodell
- Tests fuer fehlende Tabelle, fehlende Privilegien und stille leere Projektion
- Blocker fuer Views mit versteckten Routine-Abhaengigkeiten
- Report-Felder fuer table-, column- und routine-level Projection-Completeness

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
- [ ] Materialized Views sind nicht als normale Views modelliert.
- [ ] Refresh-, Staleness-, Locking- und Rollback-Verhalten fuer Materialized
  Views sind im Report sichtbar.
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

Zu klaeren:

- Body-Vergleich: kanonischer SQL-Text, Hash oder strukturierter Parser.
- Security-/Definer-Attribute und Secrets im Report.
- Down-Erzeugung bei Routine-Replace: alter Body muss bekannt und im Artefakt
  sicher gespeichert sein.
- MySQL-Delimiter sind CLI-/Client-Syntax, nicht Server-SQL; Renderer darf
  keinen losen Delimiter-Vertrag in Artefakte schreiben.

### E.2 Trigger-Migration

Nicht in der ersten Matrix:

- vollstaendige Trigger-Migration fuer PostgreSQL, MySQL und SQLite
- Trigger-Bodies im Rollback-Artefakt
- Dependency- und Sortiervertrag gegen Tabellen, Spalten, Routinen und Views

Vorbedingung:

- keine `\n\n`-Split-Heuristik fuer Artefakt-Statements mehr
- keine BEGIN-String-Heuristik fuer Transaktionsfuehrung mehr

Zu klaeren:

- Trigger-Aktivierungszustand und Timing/Event-Modell im neutralen Schema.
- Down-Vertrag fuer Replace vs. Drop/Create.
- SQLite-Rebuild-Interaktion bleibt mit Phase H abgestimmt.

### E.3 Sequence-Migrationen

Nicht in der ersten Matrix:

- PostgreSQL `CREATE/ALTER/DROP SEQUENCE`
- MySQL-Sequence-Emulation-Migration
- SQLite-Sequence-Emulation-Migration
- Nutzung von Sequences in Defaults und deren Reverse-/Compare-Stabilisierung

Verweis:

- `docs/planning/open/sqlite-sequence-emulation-plan.md`

Zu klaeren:

- Ownership/Dependency zwischen Sequence und Spalten-Default.
- Start-/Increment-/Min-/Max-/Cycle-/Cache-Diffbarkeit.
- Datenabhaengiger aktueller Sequence-Wert: migrieren, pruefen oder blockieren.
- Down-Verhalten bei bereits verbrauchten Werten.

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

Zu klaeren:

- stabiles JSON-/YAML-Format
- Secret-Scrubbing
- Hash-/Signaturmodell
- Kompatibilitaetsregeln zwischen d-migrate-Versionen
- ob das Artefakt dialektneutral, dialektspezifisch oder beides enthaelt
- ob gerenderte SQL-Statements Teil des Plan-Artefakts sind oder separat
  materialisiert werden

Mindestfelder:

- `formatVersion`
- `dMigrateVersion`
- `sourceFingerprint`
- `targetFingerprint`
- `dialect`
- `operations`
- `diagnostics`
- `reversibilitySummary`
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
- `schema rollback` bestaetigt vor Execute erneut, dass das Artefakt partial
  ist

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

Zu klaeren:

- Mapping-Quelle: CLI-Flag, Overlay-Datei, Plan-Artefakt oder Schema-Metadatum.
- Eindeutigkeit und Konfliktregeln bei mehreren Kandidaten.
- Fingerprint-Bindung: Mapping muss zu konkretem Current-/Desired-Zustand
  passen.
- Down-Vertrag fuer Rename.
- Interaktion mit FKs, Indizes, Constraints, Views, Triggers und Defaults.

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
- [ ] Fuer F.2 bis F.5 existieren Golden-File-, Kompatibilitaets- und
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

### 11.3 Artifact-Compatibility-Tests

Sobald Workstream G oder F.2 Artefaktformate aendert:

- alte gueltige Artefakte werden entweder bewusst weiter akzeptiert oder mit
  klarer Diagnose abgelehnt
- manipulierte Hashes blockieren
- unbekannte Versionen blockieren
- Secret-Scrubbing wird als Testfall gepinnt

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

---

## 12. Priorisierungsvorschlag

Empfohlene Reihenfolge nach Risiko und Abhaengigkeiten:

1. Workstream G: `transactionScope`, strukturierte Statement-Serialisierung und
   Execution-Status. Diese Punkte sind Vorbedingung fuer Routinen, Trigger und
   komplexe Bodies.
2. Workstream A: Locking-/Transactional-DDL-Hinweise, weil sie den bestehenden
   Execute-Vertrag schaerfen, ohne neue Objektklassen freizuschalten.
3. Workstream D.1/D.2: View-Dependency-Hardening fuer PostgreSQL/MySQL, weil
   Views bereits im ersten Slice enthalten sind.
4. Workstream B: erweiterte Typkonvertierungen und Live-Daten-Preflights.
5. Workstream F.5: CHECK-/EXCLUDE-Diffbarkeit, weil der erste Slice hier
   bewusst blockiert.
6. Workstream C/D.3/E: Extensions, Spatial, Materialized Views, Routinen,
   Trigger und Sequences.
7. Workstream F.1-F.4: neue Produktvertraege fuer Daten-Transformationen,
   Plan-Artefakte, Partial Rollbacks und Rename-Mappings.

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
