# Implementierungsplan: DiffResult-Migrationen 0.9.7, Teil 2

> Status: Draft (2026-05-11)
>
> Zweck: Sammlung der offenen Punkte und Carve-outs aus dem ersten
> `DiffResult`-Slice, die fuer 0.9.7 separat geplant werden sollen.
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

---

## 1. Ziel

Der erste 0.9.7-Plan liefert den ausfuehrbaren `schema migrate`-/
`schema rollback`-Slice: Datei-zu-DB, Datei-zu-Datei-Planung, Up-DDL,
Down-SQL-Artefakte, Rollback-Ausfuehrung, Driftpruefung und
Risiko-/Blocker-Semantik.

Dieses Dokument buendelt die Themen, die aus dem ersten Dokument ausgegliedert
und fuer 0.9.7 in einem zweiten Plan gefuehrt werden:

- Dialekt-Hardening jenseits der ersten sicheren Matrix
- Daten- und Dependency-Preflights, die eine Live-DB oder neue Modellfelder
  brauchen
- groessere Objektklassen wie Routinen, Trigger, Sequences und Materialized
  Views
- Produktvertraege fuer Plan-Artefakte, Partial-Rollbacks und Rename-Mappings
- technische Artefakt-/Runner-Carve-outs, die vor komplexeren SQL-Bodies
  aufgeloest werden muessen

Leitlinie: Diese Themen sollen nicht still in bestehende Renderer hineinwachsen.
Jeder Punkt braucht einen eigenen Vertrag fuer Planung, Rendering, Report,
Rollback und Tests.

---

## 2. Nicht-Ziele

Nicht Bestandteil dieses Plans:

- Phase H: formaler SQLite-Rebuild-Plan, Temp-Namen-Kollision,
  Drop+Recreate abhaengiger Views/Trigger, FK-Pragma-Restore und
  vollstaendige SQLite-Rebuild-Preflights
- Ruecknahme der 0.9.7-Blocker-Strategie
- generisches SQL-Raten bei unbekannten Dependencies
- automatische Datenmigration ohne expliziten Nutzervertrag

Phase H wird absichtlich nicht dupliziert. Sobald Phase H abgeschlossen ist,
kann dieses Dokument darauf aufbauen, ohne die H-Tasks neu zu definieren.

---

## 3. Workstream A - Dialekt- und Ausfuehrungshinweise

### A.1 Locking- und Transactional-DDL-Hinweise

Aus 0.9.7 offen:

- PostgreSQL-, MySQL- und SQLite-spezifische Hinweise zu DDL-Transaktionen,
  impliziten Commits, Locks und moeglichen Side Effects
- Darstellung im Migrate-Report und optional im SQL-Metadatenblock
- klare Trennung zwischen garantiert transaktionalem Verhalten,
  best-effort-Rollback und potenziell partiell angewendetem Zustand

Erwarteter Vertrag:

- pro Statement oder Statement-Gruppe eine strukturierte
  `transactionBehavior`-/`lockBehavior`-Einordnung
- Report zeigt, ob der Runner die Ausfuehrung vollstaendig zurueckrollen kann
- keine optimistischen Garantien fuer Dialekte mit impliziten DDL-Commits

Akzeptanz:

- Tests fuer mindestens je einen PostgreSQL-, MySQL- und SQLite-Pfad
- Report-Felder sind stabil und maschinenlesbar
- Dokumentation erklaert, wann `sideEffectsPossible=true` realistisch ist

---

## 4. Workstream B - Erweiterte Typkonvertierungen

### B.1 PostgreSQL `USING`-Konvertierungen

0.9.7 rendert `ALTER COLUMN TYPE` nur fuer explizit getestete implizite Casts
ohne `USING`. Alles andere blockiert.

In diesem Plan zu klaeren:

- Nutzervertrag fuer explizite `USING`-Ausdruecke
- Ablage solcher Ausdruecke im Schema, Overlay oder Migrations-Request
- Risiko- und Reversibilitaetsmodell pro Konvertierung
- Down-Konvertierung: automatisch, manuell oder nicht reversibel
- Validierung gegen Quell-/Zieltyp und betroffene Spalte

Nicht akzeptabel:

- generisches `USING column::target_type` ohne Nutzerentscheidung
- automatische Konvertierung mit moeglichem Datenverlust ohne Blocker

### B.2 SQLite Live-DB-Daten-Preflights fuer Casts

0.9.7 hat die SQLite-Cast-Matrix gehaertet. Offen bleibt der Live-DB-
Preflight vor whitelisted Casts.

In diesem Plan zu klaeren:

- wann der Planner eine Connection braucht
- ob der Preflight vor Render, vor Execute oder im Runner-Vertrag laeuft
- wie nicht konvertierbare Bestandsdaten als `MANUAL_REQUIRED` blockieren
- wie Datei-zu-Datei-Planung ohne Live-DB diesen Punkt berichtet

Akzeptanz:

- Positiv- und Negativtests mit realer SQLite-DB
- Datei-zu-Datei-Pfad bleibt deterministisch und markiert fehlende Live-Pruefung
- keine Cast-Ausfuehrung gegen Live-Daten ohne dokumentierten Preflight-Status

---

## 5. Workstream C - Extensions und Spatial

### C.1 Extension-Abhaengigkeiten

Aus 0.9.7 offen:

- PostgreSQL-Extensions, insbesondere PostGIS
- SpatiaLite-Abhaengigkeiten fuer SQLite
- Privilegien und Side Effects bei `CREATE EXTENSION`
- Abhaengigkeit von Typen, Funktionen, Operatoren, Indizes und Views auf
  Extensions

Erwarteter Vertrag:

- Extensions werden als Dependency sichtbar, nicht implizit installiert
- `CREATE EXTENSION` ist nur mit expliziter Option/Policy renderbar
- fehlende oder nicht verifizierbare Extension-Abhaengigkeiten blockieren
  betroffene Operationen

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

---

## 6. Workstream D - Views und Materialized Views

### D.1 PostgreSQL Visible-Signature-Compatibility fuer Views

0.9.7 splittet `ReplaceView`, wenn referenzierte Tabellen-/Spaltenoperationen
konfligieren. Offen bleibt die sichtbare View-Spaltenform:

- Spaltenanzahl
- Spaltenreihenfolge
- sichtbare Spaltentypen
- ggf. Namen/Aliase, soweit PostgreSQL sie fuer `CREATE OR REPLACE VIEW`
  relevant macht

Moegliche Loesungen:

- `ViewColumn`-Modellebene im neutralen Modell / Reverse-Pfad
- Pre-Render-Probe gegen Live-DB
- konservativer Blocker bei fehlender Signaturinformation

### D.2 MySQL `VIEW_ROUTINE_USAGE`-Privilege-Preflight

0.9.7 behandelt `VIEW_TABLE_USAGE` als Vollstaendigkeitsproblem. Offen bleibt
die analoge Behandlung fuer `VIEW_ROUTINE_USAGE`.

In diesem Plan zu klaeren:

- separates `routineProjectionComplete`-Signal oder konsolidiertes
  Dependency-Projektionsmodell
- Tests fuer fehlende Tabelle, fehlende Privilegien und stille leere Projektion
- Blocker fuer Views mit versteckten Routine-Abhaengigkeiten

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

---

## 7. Workstream E - Weitere Objektklassen

### E.1 Routine-Migration

Nicht in der ersten Matrix:

- PostgreSQL `CREATE OR REPLACE FUNCTION`
- PostgreSQL `CREATE OR REPLACE PROCEDURE`
- MySQL-Routinen
- Dependency-Sortierung zwischen Routinen, Views, Triggern und Tabellen

Vorbedingung:

- strukturierte Statement-Serialisierung und Transaction-Scope-Feld aus
  Workstream G, bevor Routine-Bodies mit `BEGIN ... END` sicher gerendert
  und rollbackfaehig gespeichert werden koennen

### E.2 Trigger-Migration

Nicht in der ersten Matrix:

- vollstaendige Trigger-Migration fuer PostgreSQL, MySQL und SQLite
- Trigger-Bodies im Rollback-Artefakt
- Dependency- und Sortiervertrag gegen Tabellen, Spalten, Routinen und Views

Vorbedingung:

- keine `\n\n`-Split-Heuristik fuer Artefakt-Statements mehr
- keine BEGIN-String-Heuristik fuer Transaktionsfuehrung mehr

### E.3 Sequence-Migrationen

Nicht in der ersten Matrix:

- PostgreSQL `CREATE/ALTER/DROP SEQUENCE`
- MySQL-Sequence-Emulation-Migration
- SQLite-Sequence-Emulation-Migration
- Nutzung von Sequences in Defaults und deren Reverse-/Compare-Stabilisierung

Verweis:

- `docs/planning/open/sqlite-sequence-emulation-plan.md`

---

## 8. Workstream F - Datenmigration und DiffResult-Produktvertraege

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

### F.2 Versionierte Plan-Artefakte

Nicht im ersten 0.9.7-Plan abgedeckt:

- serialisierter `DiffResult` als oeffentlicher Input
- `schema rollback` direkt aus Plan-Artefakt
- versionierte Plan-Kompatibilitaet

In diesem Plan zu klaeren:

- stabiles JSON-/YAML-Format
- Secret-Scrubbing
- Hash-/Signaturmodell
- Kompatibilitaetsregeln zwischen d-migrate-Versionen

### F.3 Partial Rollbacks

Nicht im ersten 0.9.7-Plan abgedeckt:

- `--allow-partial-rollback`
- bewusst unvollstaendige Down-Artefakte
- Rollback trotz `MANUAL_REQUIRED`- oder `NOT_REVERSIBLE`-Teilmenge

Erwarteter Vertrag:

- explizite Nutzerfreigabe
- maschinenlesbare Liste ausgelassener Operationen
- keine Darstellung als vollstaendiges Rollback

### F.4 Rename-Mappings

Nicht im ersten 0.9.7-Plan abgedeckt:

- `RenameTable`
- `RenameColumn`
- Nutzer-Mapping fuer Rename-Kandidaten

Der erste 0.9.7-Plan bleibt bei Diagnose-Hints. Dieser zweite Plan braucht
einen expliziten Rename-Input-Vertrag, sonst wird Drop+Add mit Datenverlust
verwechselt.

### F.5 CHECK-/EXCLUDE-Constraint-Diffbarkeit

0.9.7 blockiert Tabellen mit `CHECK`-/`EXCLUDE`-Constraints ueber einen
Pre-Normalization-Detector, statt sie still wegzunormalisieren.

In diesem Plan moeglich:

- echte Diffbarkeit solcher Constraints
- Ausdruckskanonisierung oder konservativer SQL-Text-Vergleich
- dialektspezifische Enforcement-Regeln, insbesondere fuer MySQL

---

## 9. Workstream G - Artefakt- und Runner-Vertrag

### G.1 Strukturiertes `transactionScope`

0.9.7 erkennt stream-owned Transaktionen ueber SQL-Content, konkret ueber
fuehrende `BEGIN`-Statements. Das ist nur sicher, solange keine
Routinen-/Trigger-Bodies als normale Statements gerendert werden.

In diesem Plan:

- `MigrationDdlStatement` bekommt ein strukturiertes `transactionScope`-Feld
- moegliche Werte: `RUNNER_OWNED`, `STREAM_OWNED`, ggf. `NO_TRANSACTION`
- Runner nutzt keine SQL-String-Heuristik mehr fuer Transaktionsfuehrung

Akzeptanz:

- Regressionstest mit Routine-/Trigger-artigem SQL-Body, der `BEGIN` enthaelt
- SQLite-Rebuild bleibt stream-owned
- PostgreSQL/MySQL bleiben runner-owned, sofern der Dialektvertrag das erlaubt

### G.2 Strukturierte Statement-Serialisierung im Rollback-Artefakt

0.9.7 rekonstruiert Statements aus dem Rollback-Artefakt per `\n\n`-Split.
Das ist nur sicher, solange Statement-SQL selbst keine Leerzeilen enthaelt.

In diesem Plan:

- Rollback-Artefakt speichert Statements strukturiert, z.B. als JSON-Array im
  Metadatenblock oder mit eindeutigem Escape-/Length-Prefix-Vertrag
- Per-Statement-Metadaten wie Operation-IDs, Phase und Transaction-Scope sind
  round-trip-fest
- Parser lehnt alte/neue Formate anhand `formatVersion` eindeutig ab oder
  migriert sie kontrolliert

Vorbedingung fuer:

- Routine-Bodies
- Trigger-Bodies
- komplexe View-Definitionen mit Leerzeilen

---

## 10. Test- und Coverage-Follow-ups

### 10.1 MySQL `AlterColumnNullability` im Round-Trip-Smoke

Der MySQL-Smoke fuer 0.9.7 laesst `AlterColumnNullability` ausdruecklich als
Coverage-Carve-out stehen. Dieser Plan sollte einen echten Round-Trip-Smoke
fuer diese Operation abdecken, sofern der Produktvertrag weiter renderbar
bleibt.

### 10.2 Cross-Dialekt-Regressionsmatrix

Fuer alle Workstreams gilt:

- mindestens ein Positivpfad
- mindestens ein blockierender Pfad
- Report-/Exit-Code-Abdeckung
- Rollback-Verhalten oder explizite Begruendung, warum kein Rollback erzeugt
  werden darf

---

## 11. Priorisierungsvorschlag

Empfohlene Reihenfolge nach Risiko:

1. Workstream G: `transactionScope` und strukturierte Statement-Serialisierung,
   weil diese Punkte Vorbedingung fuer Routinen/Trigger und komplexe Bodies
   sind.
2. Workstream A: Locking-/Transactional-DDL-Hinweise, weil sie den bestehenden
   Execute-Vertrag schaerfen, ohne neue Objektklassen freizuschalten.
3. Workstream D.1/D.2: View-Dependency-Hardening fuer PostgreSQL/MySQL.
4. Workstream B: erweiterte Typkonvertierungen und Live-Daten-Preflights.
5. Workstream C/D.3/E: Extensions, Spatial, Materialized Views, Routinen,
   Trigger und Sequences.
6. Workstream F: neue Produktvertraege fuer Plan-Artefakte, Partial Rollbacks,
   Rename-Mappings und Datenrekonstruktion.

Diese Reihenfolge ist bewusst konservativ: erst Artefakt- und Runner-
Sicherheiten, dann breitere SQL-Oberflaeche.

---

## 12. Akzeptanz fuer dieses Folgepaket

Ein Folgepaket gilt als sauber geplant, wenn fuer jeden aufgenommenen Punkt
vor Implementierung geklaert ist:

- welcher Modus betroffen ist: Datei-zu-Datei, Datei-zu-DB, Execute,
  Rollback oder alle
- welche Operationen renderbar werden und welche weiterhin blockieren
- welche neuen Diagnostics/Blocker noetig sind
- wie Up und Down getrennt bewertet werden
- welche Report- und Metadatenfelder stabilisiert werden
- welche Dialekte betroffen sind
- welche Tests den positiven und den blockierenden Pfad pinnen
