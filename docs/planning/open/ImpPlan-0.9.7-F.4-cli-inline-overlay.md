# Implementierungsplan: 0.9.7 — F.4 CLI-Inline-Overlay

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: F.4 (fuenfter Slice — Inline-Overlay-Eingabe via CLI)
> **Status**: open (geplant, noch nicht gestartet)
> **Vorbedingung**: F.4 Rendering-Slice ✅
> **Referenz**: `docs/planning/in-progress/diffresult-migration-plan-2.md`
>             §10 F.4 (Carve-out)
>             `docs/planning/done/ImpPlan-0.9.7-F.4-rendering.md`
>             `spec/cli-spec.md` §6.1 `schema migrate`
> **Contract-Gate**: Plan-2 §10 F.4 verbietet aktuell Inline-Rename-
>                    Mappings und erlaubt CLI-Flags nur fuer Overlay-Dateien.
>                    Dieser Slice darf erst starten, nachdem Plan-2 und
>                    `spec/cli-spec.md` diesen Operator-Shortcut explizit
>                    freigegeben haben.

---

## 1. Ziel

Heute werden Migration-Overlays ausschliesslich ueber
`--migration-overlay <pfad>` als Datei geladen (signiert,
fingerprint-gebunden, hash-validiert). Fuer kleine Rename-Mappings
ist das zaeh: Operator muss eine JSON-Datei mit korrektem Hash
generieren, nur um `users_old → users` zu sagen.

Dieser Slice ergaenzt nach Contract-Freigabe einen Inline-Overlay-Pfad:
das CLI akzeptiert Mapping-Eintraege direkt als Flag-Werte, generiert
daraus einen synthetischen `MigrationOverlay` mit aktuellen Fingerprints
und korrektem Hash, und reicht ihn unveraendert durch die existierende
Validator-/Mapper-Pipeline.

## 2. Scope

In Scope:

- Vorab-Contract-Aenderung: `diffresult-migration-plan-2.md` §10 F.4
  und `spec/cli-spec.md` §6.1 muessen Inline-Rename-Flags als
  bewusst nicht-artefaktstabilen Operator-Shortcut erlauben. Ohne diese
  Aenderung bleibt der Slice blockiert.
- Reihenfolge bleibt identisch zu den anderen F.4-Folgeslices: synthetische
  Inline-Overlays werden vor dem ersten `DiffPlanner.plan(...)` gebaut und
  anschliessend zusammen mit File-Overlays durch den zentralen Pre-Plan-
  Overlay-Gate validiert. Der CLI-Slice fuehrt keinen zweiten Rename-
  Validierungs- oder Reason-Klassifikationspfad ein.
- Neue CLI-Flags auf `schema migrate`:
  - `--rename-table <from>:<to>` (wiederholbar)
  - `--rename-column <table>.<from>:<table>.<to>` (wiederholbar)
- Optional: generische Variante fuer alle in
  ImpPlan-0.9.7-F.4-routine-trigger-view-renames.md genannten
  Objektklassen (`--rename-view`, `--rename-trigger`, ...). Dieser
  Slice setzt nur `--rename-table`/`--rename-column` um, weil das die
  Mehrheit der Operator-Faelle abdeckt; weitere Flags folgen mit dem
  jeweiligen Renderer-Slice.
- CLI-Layer baut aus den Flag-Werten eine
  `MigrationOverlay`-Instanz mit:
  - `formatVersion = MigrationOverlay.FORMAT_VERSION`
  - `overlayKind = MigrationOverlayKinds.RENAME_MAPPING`
  - `sourceFingerprint` / `targetFingerprint` aus vorab berechnetem
    `MigrationFingerprint.compute(current/desired)`
  - `dialect` aus der `resolveDialect`-Logik
  - `createdAt = INLINE_CREATED_AT_SENTINEL` mit stabilem Wert
    `"cli-inline"`; Inline-Overlays sind kein persistiertes
    Signaturartefakt, und ein wall-clock Timestamp im kanonischen Hash
    wuerde bei identischem CLI-Aufruf unterschiedliche Operation-IDs
    erzeugen, weil Rename-Operation-IDs den `overlayHash` tragen.
  - `createdByVersion = d-migrate (...)`
  - `overlayHash` via `withComputedHash()`
- Source-Label im Report: `source = "cli-inline"` statt eines
  Dateipfads; einzelne Flag-Slots werden ueber stabile `entryId`s
  (`rename-table-<index>` / `rename-column-<index>`) identifiziert.
  Dafuer wird der bestehende Overlay-Report-Vertrag erweitert: valide
  Overlay-Eintraege erzeugen zusaetzlich zu Diagnostics eine
  `OVERLAY_ACCEPTED`/`INFO`-Reportzeile, weil `overlays[]` heute nur
  aus Validator-Diagnostics gespeist wird und erfolgreiche Overlays
  sonst unsichtbar bleiben. Diese Accepted-Zeilen sind reine Report-
  Provenance und duerfen nicht als `DiffDiagnostic` mit Failure-Text
  in den Preflight-Blockerpfad gelangen.
- Diagnostics: doppelte Flag-Eintraege innerhalb desselben CLI-Aufrufs fuer
  dasselbe `<from>` blocken als reine CLI-Parse-/Shortcut-Fehler vor
  Overlay-Construction mit Exit 2. Sobald die synthetischen Inline-Overlays
  gebaut sind, gelten sie als normale Overlay-Quelle: doppelte Rename-Quellen
  ueber File- und Inline-Overlays hinweg oder ueber mehrere Overlay-Dokumente
  blocken **vor dem ersten
  `DiffPlanner.plan(...)`** ueber eine neue Cross-Document-Uniqueness-
  Pruefung auf der zusammengefuehrten Overlay-Liste. Diese Pruefung ist
  keine CLI-Sonderlogik: Der CLI-Slice nutzt den zentralen Pre-Plan-
  Overlay-Gate aus
  `ImpPlan-0.9.7-F.4-rename-mapping-invalid-enum.md` bzw. fuehrt bei
  umgekehrter Implementierungsreihenfolge genau diese gemeinsame API ohne
  CLI-lokale Reason-Literale ein. Die heutige
  `MigrationOverlayValidator.validate(...)`-Pruefung pro Dokument reicht
  dafuer nicht. Cross-Document-Blocker duerfen keine gerenderte,
  ausfuehrbare oder bereits mit Rename-Provenance geplante Operationenliste
  zulassen: Der Runner beendet mit Exit 8 und einem synthetischen
  Pre-Plan-Blocker-Result; `operationsSkipped` bleibt leer, weil es noch
  keinen autorisierten Plan gibt. Die fachlich gueltige Provenance ist in
  diesem Fall ausschliesslich der Pre-Plan-Finding mit beiden
  `source`/`entryId`-Paaren.
- Eigener Pre-Plan-Report-Pfad: Weil bei Cross-Document-Blockern kein
  `DiffResult` existiert, darf der Report nicht ueber
  `SchemaMigrateReportBuilder.build(plan = ...)` laufen. Der Runner nutzt
  den gemeinsamen Pre-Plan-Report-Pfad aus dem Rename-Reason-Slice; falls
  dieser Slice frueher landet, gehoert der Builder als allgemeiner
  `SchemaMigratePrePlanReportBuilder` in denselben zentralen Application-
  Pfad und nicht in CLI-Inline-spezifische Logik. Der Builder schreibt
  Source/Target, Dialekt, `status = "blocked"`, `exitCode = 8`,
  `operations = []`, `statements = null`, leere `operationsSkipped` und
  die Cross-Document-Findings in `blockers`/`diagnostics`/`overlays`.
  So bleibt der Report-Vertrag vollstaendig, ohne einen
  Dummy-Plan zu erzeugen oder einen invaliden Rename bereits zu planen.
- Blocker-Reason: Dieser CLI-Slice definiert den Reason-Wert nicht selbst.
  Cross-Document-Rename-Blocker laufen durch denselben zentralen
  Overlay-Blocker-Reason-Classifier wie File-Overlays. Ist der separate
  `ImpPlan-0.9.7-F.4-rename-mapping-invalid-enum.md`-Slice noch offen,
  liefert dieser Classifier weiter `MANUAL_ACTION_REQUIRED`; ist er
  umgesetzt, liefert er `RENAME_MAPPING_INVALID`. Der CLI-Slice darf keinen
  eigenen Literal-Reason hart verdrahten und keine abweichende
  Uebergangslogik einfuehren.
- Tests: CLI-Parsing, Inline-Overlay-Konstruktion, End-to-End-Smoke
  fuer Tabellen-/Spalten-Rename via Flag.

Aus Scope:

- USING-Expressions inline: zu komplex (Multi-Line-Expressions,
  Quoting-Hoelle); bleibt File-Overlay.
- Plan-Artefakt-Einbettung inline gebauter Overlays: das Plan-
  Artefakt darf in diesem Slice keine Inline-Overlay-Referenz schreiben,
  die nur aus `source = "cli-inline"` plus `entryId`s besteht. Ein solcher
  Verweis waere ohne den Original-Flag-Aufruf nicht reproduzierbar und ist
  deshalb verboten. Der synthetische Overlay-Body bleibt auf Runner/Report
  beschraenkt und wird nicht in oeffentliche Plan-Artefakte serialisiert.
  Das gilt auch dann, wenn der interne `DiffResult` waehrend desselben
  CLI-Laufs ein `MigrationOverlayDocument(source = "cli-inline", ...)`
  als Planner-/Renderer-Input traegt: Ein oeffentlicher
  `migration-plan.v1`-Serializer muss Inline-Overlay-Dokumente entweder
  ausdruecklich auslassen oder den Artefakt-Export blockieren, bis ein
  versionierter Body-Einbettungs-Gate existiert.
  Eine spaetere Iteration kann den ganzen Overlay-Body mit
  `requiredFeatures`/`semanticExtensions`-Gate einbetten; bis dahin gilt fuer
  langlebige/reproduzierbare Plaene: File-Overlay nutzen.
- Interaktive Eingabe (`--prompt-for-renames`): bewusst kein TTY-
  Pfad, weil das Plan-Artefakt-Reproduzierbarkeit bricht (Plan-2 §10
  F.3 "keine TTY-Rueckfrage").
- `schema rollback`: der aktuelle Rollback-Vertrag liest ein fertiges
  Down-SQL-Artefakt und fuehrt keinen Diff-/Planner-/Overlay-Pfad aus.
  Inline-Rename-Flags fuer Rollback werden erst relevant, wenn ein
  spaeterer Slice `schema rollback` direkt aus einem serialisierten
  Plan-Artefakt plant.

## 3. Architektur

### 3.1 CLI-Layer

`SchemaMigrateCommand` bekommt zwei neue Multi-Value-Optionen:

```kotlin
private val renameTableFlags by option("--rename-table")
    .help("Inline rename mapping <from>:<to> (repeatable). " +
          "Equivalent to a single-entry migration-overlay rename-mapping.")
    .multiple()

private val renameColumnFlags by option("--rename-column")
    .help("Inline column rename mapping <table>.<from>:<table>.<to> (repeatable).")
    .multiple()
```

### 3.2 Builder

Ein neuer `InlineRenameOverlayBuilder` in `hexagon:application` (nahe
`MigrationOverlayPreflight`) sitzt zwischen CLI und Runner:

```kotlin
internal object InlineRenameOverlayBuilder {

    /**
     * Parses CLI-supplied `--rename-table` / `--rename-column` flags
     * and returns a single signed [MigrationOverlayDocument] with
     * `source = "cli-inline"` whose entries match the flag order.
     * Returns null when both lists are empty.
     */
    fun build(
        renameTableFlags: List<String>,
        renameColumnFlags: List<String>,
        sourceFingerprint: String,
        targetFingerprint: String,
        dialect: String,
        version: String,
    ): InlineRenameOverlayResult
}

internal sealed interface InlineRenameOverlayResult {
    data class Built(val document: MigrationOverlayDocument) : InlineRenameOverlayResult
    data class ParseFailed(val errors: List<String>) : InlineRenameOverlayResult
    data object Empty : InlineRenameOverlayResult
}
```

Parsing-Regeln:

- `<from>:<to>` mit genau einem ungeklammerten `:` als Separator.
- `<from>` und `<to>` sind rohe Overlay-Namen in derselben Konvention wie
  `RenameMappingOverlayEntry.fromName`/`toName`, aber der CLI-Shortcut
  akzeptiert bewusst keine SQL-Identifier-Quoting-Syntax wie
  `"identifier"` oder `` `identifier` ``. Der Builder validiert diese
  engere CLI-Grammatik selbst; der bestehende `MigrationOverlayValidator`
  prueft nur den Overlay-Vertrag und fuehrt kein Dialekt-Quoting aus. Das
  eigentliche SQL-Quoting passiert spaeter im Renderer.
- Doppelte `from`-Eintraege werden vor Overlay-Construction
  zurueckgewiesen — der Runner liefert Exit 2.
- Whitespace wird getrimmt.
- Spalten-Form: `<table>.<from>:<table>.<to>` mit beidseitig
  identischem Tabellen-Prefix; abweichende Tabellen blockieren mit
  einer expliziten Parse-Fehlermeldung.

### 3.3 Runner-Integration

`SchemaMigrateRunner.execute` ruft `InlineRenameOverlayBuilder.build`
nach `compare`, aber vor dem ersten `plan()`. Weil `DiffPlanner.plan(...)`
die `DiffEndpoint`-Fingerprints erst am Ende des Plans erzeugt, darf
der Builder NICHT aus einem bereits geplanten Endpoint lesen. Stattdessen
berechnet der Runner die beiden Hashes vorab mit demselben Contract wie
der Planner:

```kotlin
val currentFingerprint = MigrationFingerprint.compute(targetNormalized.schema)
val desiredFingerprint = MigrationFingerprint.compute(sourceNormalized.schema)
```

Diese Werte muessen bitgleich zu den spaeteren `plan.current.fingerprint`
und `plan.desired.fingerprint` sein; ein Test pinnt diese Gleichheit.
Die resultierende `MigrationOverlayDocument` wird mit den explizit
geladenen File-Overlays zu einer lokalen Overlay-Liste kombiniert und
an `DiffPlanner.plan(..., migrationOverlays = ...)` uebergeben. Die
nachgelagerten Schritte (Preflight, Mapper) sehen nur die
zusammengefuehrte Liste und bleiben fachlich unveraendert.
Diese lokale Liste ist ein Runtime-Carrier fuer den aktuellen CLI-Lauf.
Falls ein spaeterer Codepfad `DiffResult.migrationOverlays` in ein
oeffentliches `migration-plan.v1`-Artefakt projiziert, muss er
`source = "cli-inline"`-Dokumente herausfiltern oder den Export mit einem
klaren Reproduzierbarkeitsblocker abbrechen. Der Inline-Slice darf nicht
darauf vertrauen, dass `DiffResult`-Felder automatisch "intern" bleiben.

`ParseFailed` -> `userFacingPrintError` + Exit 2 (CLI-Validation-
Fehler, kein I/O).

Vor dem ersten `plan()` fuehrt der Runner
`MigrationOverlayPreflight.validateBeforePlan(...)` auf der
zusammengefuehrten File+Inline-Liste aus. Dieser zentrale Pre-Plan-Gate
fuer alle Overlay-Quellen nutzt dieselben dokumentlokalen Key-Regeln wie der
Validator, erweitert sie aber ueber Dokumentgrenzen hinweg und liefert bei
Blockern ein synthetisches `PrePlanMigrationBlockerResult` ohne
`DiffResult`, `MigrationDdlResult` und ohne `plan.operations`. Der Planner
darf bei Cross-Document-Konflikten nicht laufen, weil
`OperationMapper` sonst bereits Rename-Operationen mit einer
moeglicherweise falschen oder deduplizierten Overlay-Provenance erzeugt.

Der Runner behandelt diesen Pre-Plan-Blocker als eigenen Outcome-Zweig:
Er schreibt, falls `--report` gesetzt ist oder der normale Report-stdout-
Fallback greift, einen Report ueber `SchemaMigratePrePlanReportBuilder`.
Dieser Builder liest ausschliesslich den Pre-Plan-Blocker und die bereits
geladenen Operand-Metadaten; er verlangt keinen `DiffResult` und setzt:

- `operations = []`
- `statements = null`
- `summary.operationsTotal = 0`
- `summary.operationsSkipped = 0`
- `summary.primaryBlockedReason` aus dem zentralen
  Overlay-Blocker-Reason-Classifier; der Pre-Plan-Report-Builder enthaelt
  keinen CLI-lokalen Sonderfall fuer Rename-Reasons
- `overlays[]` mit allen betroffenen `source`/`entryId`-Paaren

Damit gibt es fuer Cross-Document-Konflikte keine kuenstliche
Operationenliste und keine nachtraeglich interpretierte Rename-Provenance.

Der Builder schreibt `MigrationOverlay.createdAt` als stabilen Sentinel
`"cli-inline"`. Das Feld bleibt ein `String` und nutzt denselben DTO-/JSON-
Vertrag wie File-Overlays, ist fuer Inline-Overlays aber absichtlich nicht
wall-clock-abhaengig. Ein Test pinnt, dass zwei identische CLI-Aufrufe mit
unterschiedlichen Runner-Clocks denselben `overlayHash`, dieselben
`Rename*`-Operation-IDs und denselben Statement-Stream erzeugen. Operator-
Zeitstempel gehoeren in den umgebenden Run-Report, nicht in den semantischen
Inline-Overlay-Hash.

### 3.4 Reportausgabe

Der `overlays`-Block im Report nutzt `source = "cli-inline"` und
`entryId = "rename-table-<index>"` oder `"rename-column-<index>"`.
Damit ist im Report nachvollziehbar, welches Mapping aus welchem
Flag-Slot kommt.

Technische Anpassung: Der heutige `MigrationOverlayValidationResult`
enthaelt nur `source`, `overlayHash` und `diagnostics`; daraus kann
`MigrationOverlayReport.fromValidation(...)` erfolgreiche Entries nicht
rekonstruieren. Dieser Slice erweitert daher entweder
`MigrationOverlayValidationResult` um die validierten Entry-Identitaeten
oder aendert die Report-Factory-Signatur auf
`fromValidation(result, overlay.entries)`. Danach liefert
`MigrationOverlayReport.fromValidation(...)` weiterhin alle Validator-
Diagnostics, ergaenzt aber fuer jeden validierten Entry ohne Blocker eine
`MigrationOverlayReportItem`-Zeile mit
`diagnosticCode = "OVERLAY_ACCEPTED"` und `severity = INFO`. Dadurch sieht
der bestehende `SchemaMigrateReportBuilder` sowohl File- als auch Inline-
Overlays im Report, ohne dass `SchemaMigrateOperationView` Overlay-
Provenance-Felder bekommen muss.

Wichtig: `MigrationOverlayPreflight.validate(...)` darf nicht mehr jedes
Report-Item pauschal in ein `DiffDiagnostic` mit "failed F.0 contract
validation" umwandeln. Die Preflight-Ausgabe muss Report-Provenance und
Failure-Diagnostics trennen:

- `reportItems`: Validator-Diagnostics plus `OVERLAY_ACCEPTED`-
  Provenance fuer valide Entries.
- `diagnostics`: nur echte Validator-/Load-/Cross-Document-Findings,
  deren Message und Severity fachlich zum Code passen.

Tests pinnen, dass erfolgreiche Inline-Overlays im Report auftauchen,
keine Failure-Diagnostic erzeugen und Blocker-Zeilen weiterhin ihre
konkreten Diagnostic-Codes behalten.

### 3.5 Cross-Document-Uniqueness

Heute validiert `MigrationOverlayValidator.validate(...)` jedes
`MigrationOverlayDocument` einzeln; der dokumentlokale Validator sieht
daher keine Konflikte zwischen zwei Dateien oder zwischen Datei und
`cli-inline`. Dieser Slice fuegt im Runner vor `DiffPlanner.plan(...)`
eine zusammenfassende Rename-Pruefung ueber alle syntaktisch ladbaren
`RenameMappingOverlayEntry`-Eintraege hinzu:

- gleicher `objectType + fromName` mit unterschiedlichem `toName`
  blockiert als `OVERLAY_RENAME_MAPPING_AMBIGUOUS`;
- gleicher `objectType + toName` mit unterschiedlichem `fromName`
  blockiert als `OVERLAY_RENAME_MAPPING_AMBIGUOUS`;
- exakt gleicher `objectType + fromName + toName` ueber zwei Dokumente
  (z.B. File-Overlay plus `cli-inline`) blockiert als
  `OVERLAY_RENAME_MAPPING_DUPLICATE`, weil sonst unklar ist, welche
  Source-/Entry-Provenance den Rename autorisiert;
- Case-Folding-Konflikte und Kettenrenames werden mit denselben Codes
  wie im Dokument-lokalen Validator gemeldet;
- `source`/`entryId` zeigen auf den konkreten File- oder Inline-Eintrag.

Damit bleibt die Dokumentvalidierung lokal, aber der Runner blockt die
fachlich relevante Gesamt-Overlay-Menge, bevor ein Rename geplant oder
gerendert wird. Bei Cross-Document-Blockern existiert daher keine
Operationenliste, die als Nachweis gelten koennte, dass ein bestimmtes
Rename-Mapping akzeptiert oder ausgefuehrt wurde.

## 4. Akzeptanzkriterien

- [ ] `schema migrate --rename-table users_old:users` rendert einen
      Tabellen-Rename ohne externe Overlay-Datei — der erzeugte
      Statement-Stream entspricht 1:1 dem File-Overlay-Pfad.
- [ ] `diffresult-migration-plan-2.md` §10 F.4 ist vor Umsetzung so
      angepasst, dass Inline-Rename-Flags als expliziter CLI-Shortcut
      erlaubt sind; der alte Satz "CLI-Flags duerfen hoechstens auf eine
      Overlay-Datei zeigen" ist ersetzt oder eingeschraenkt.
- [ ] `schema migrate --rename-column users.old_name:users.new_name`
      rendert einen Spalten-Rename ohne externe Overlay-Datei.
- [ ] Beide Flags sind wiederholbar (mehrere Mappings pro Aufruf).
- [ ] Doppelte `from`-Eintraege innerhalb der Inline-Flags desselben
      CLI-Aufrufs blockieren mit Exit 2 vor Overlay-Construction und
      vor `plan()`. Cross-Document-Duplikate nach Overlay-Construction
      bleiben Exit-8-Pre-Plan-Blocker.
- [ ] Fehlerhafte Flag-Syntax (kein `:`, abweichendes Tabellen-Prefix,
      leere Bezeichner, SQL-Quoting-Syntax im Shortcut) blockiert mit
      Exit 2 und konkretem Operator-Hinweis. Die Tests zeigen, dass diese
      Pruefung im `InlineRenameOverlayBuilder` liegt und nicht faelschlich
      vom `MigrationOverlayValidator` erwartet wird.
- [ ] Inline-Overlay laeuft durch denselben `MigrationOverlayValidator`
      wie File-Overlays (Fingerprint-, Hash-, Dialekt-Check). Der
      Validator sieht keinen Unterschied.
- [ ] Inline-Overlay-Dokumente werden nur als Runtime-Input in
      `DiffPlanner.plan(... migrationOverlays = ...)` genutzt. Tests oder
      Artefakt-Gates pinnen, dass ein oeffentlicher
      `migration-plan.v1`-Export sie nicht als
      `source = "cli-inline"`-Referenz ohne Body serialisiert.
- [ ] `createdAt` des synthetischen Overlays ist der stabile String-
      Sentinel `"cli-inline"`; kein Core-/JSON-Typwechsel auf `Instant`,
      und kein wall-clock-Wert im kanonischen Inline-Overlay-Hash.
- [ ] Zwei identische CLI-Aufrufe mit unterschiedlich gepinnter Runner-Clock
      erzeugen denselben `overlayHash`, dieselben Rename-Operation-IDs und
      denselben Statement-Stream.
- [ ] Ein Test pinnt, dass vorab berechnete Inline-Overlay-Fingerprints
      den spaeteren `DiffPlanner`-Endpoints entsprechen und dass der
      Runner nur einmal plant.
- [ ] Inline und File-Overlay duerfen kombiniert werden — beide
      Listen werden konkateniert; eine explizite Cross-Document-
      Uniqueness-Pruefung blockt Konflikte ueber alle Quellen hinweg vor
      dem ersten `DiffPlanner.plan(...)`. Diese Pruefung kommt aus dem
      zentralen Pre-Plan-Overlay-Gate und ist nicht im CLI-Slice dupliziert.
- [ ] Der CLI-Slice delegiert sowohl Rename-Reason-Klassifikation als auch
      Cross-Document-Provenance an denselben zentralen Pre-Plan-Gate, den
      RENAME_MAPPING_INVALID- und Dependency-Projection-Slices nutzen. Es gibt
      keinen CLI-lokalen Fallback, der invalide Inline-Renames nach `plan()`
      entdeckt oder umklassifiziert.
- [ ] Cross-Document-Uniqueness-Blocker beenden den Lauf vor Plan/Render/
      Execute mit Exit 8. Das umfasst File-vs-File-, File-vs-Inline- und
      andere echte Mehrdokument-Konflikte, nicht aber reine Inline-Flag-
      Parse-Duplikate, die bereits mit Exit 2 enden. Der Inline-Builder
      erzeugt pro CLI-Aufruf genau ein synthetisches Overlay-Dokument; Tests
      fuer Mehrdokument-Konflikte nutzen daher File-vs-File oder
      File-vs-Inline. Der Report zeigt die betroffenen
      `source`/`entryId`-Paare aus dem Pre-Plan-Finding; es gibt keine vorab
      geplanten Rename-Operationen und `operationsSkipped` bleibt leer.
- [ ] Fuer Pre-Plan-Blocker existiert ein eigener Report-Builder, der
      keinen `DiffResult` benoetigt. Ein Test pinnt, dass
      Cross-Document-Konflikte mit leerem `operations[]`, leeren
      `operationsSkipped`, `statements = null` und konkreten
      `overlays[]`/`diagnostics[]` reportet werden.
- [ ] Cross-Document-Rename-Blocker nutzen denselben zentralen
      Overlay-Blocker-Reason-Classifier wie File-Overlay-Blocker. CLI-Tests
      pruefen die Delegation und die konkrete Diagnostic-Provenance; der
      `RENAME_MAPPING_INVALID`-Enum-Slice besitzt die Tests, die den
      Literalwechsel von `MANUAL_ACTION_REQUIRED` auf
      `RENAME_MAPPING_INVALID` pinnen.
- [ ] Ein exakt doppeltes Rename-Mapping in zwei verschiedenen Quellen
      (`objectType + fromName + toName`) blockiert mit
      `OVERLAY_RENAME_MAPPING_DUPLICATE` und zeigt beide betroffenen
      `source`/`entryId`-Paare im Report.
- [ ] Report kennzeichnet Inline-Overlay-Eintraege mit
      `source = "cli-inline"` und `diagnosticCode = "OVERLAY_ACCEPTED"`
      fuer valide Eintraege.
- [ ] `MigrationOverlayValidationResult` oder die
      `MigrationOverlayReport.fromValidation(...)`-Signatur transportiert
      validierte Entry-IDs, sodass `OVERLAY_ACCEPTED` nicht aus
      Diagnostics oder Operationen rekonstruiert werden muss.
- [ ] `OVERLAY_ACCEPTED`-Reportzeilen erzeugen keine Failure-
      `DiffDiagnostic`; `MigrationOverlayPreflightResult.diagnostics`
      enthaelt nur echte Findings.
- [ ] `spec/cli-spec.md` §6.1 ist um die Flag-Doku erweitert.
- [ ] `roadmap.md` und `diffresult-migration-plan-2.md §10 F.4`
      bekommen einen Status-Update.

## 5. Definition of Done

- [ ] Alle Akzeptanzkriterien aus §4 erfuellt.
- [ ] `make docker-test` gruen, Output in `/tmp/build.log`.
- [ ] Coverage `hexagon:application` und `adapters:driving:cli`
      jeweils ≥ 90%.
- [ ] Plan-Datei nach `docs/planning/done/` verschoben.

## 6. Risiken

### 6.1 Identifier-Quoting im Flag-Wert

Flag-Werte koennen Sonderzeichen enthalten (`"name with space"`),
die der CLI-Shell-Parser eh quoten muss. Dieser Slice akzeptiert
**keine** SQL-Identifier-Quoting-Syntax im Flag — der Operator
gibt Klartext-Bezeichner an. Der Builder blockiert SQL-Quoting-Zeichen
im Shortcut, baut daraus rohe `RenameMappingOverlayEntry`-Namen und
ueberlaesst das Dialekt-Quoting wie beim File-Overlay ausschliesslich
dem Renderer. Das entspricht der File-Overlay-Konvention
(`RenameMappingOverlayEntry` speichert ebenfalls Klartext), macht aber
die engere CLI-Shortcut-Grammatik explizit testbar.

### 6.2 Verwechslung mit `--rename-column <from>:<to>` ohne Tabelle

Operator-Fehler: `--rename-column old:new` ohne Tabellen-Prefix wuerde
ohne Validierung als unqualifizierte Spalten-Rename gefolded (siehe
F.4-Rendering-Slice §3.3, unqualifiziertes Mapping). Das ist
gewuenschtes Verhalten fuer das File-Overlay; im CLI-Slice ist es
zu fehleranfaellig. Diese Variante blockiert mit klarer Fehlermeldung
und verlangt explizit `<table>.<from>:<table>.<to>`. Wer
unqualifiziert mappen will, nimmt das File-Overlay.

### 6.3 Plan-Artefakt-Reproduzierbarkeit

Inline-Overlay landet in diesem Slice gar nicht im oeffentlichen
Plan-Artefakt: weder als blosses `source = "cli-inline"`-Referenzfeld noch
als synthetischer Overlay-Body. Eine Referenz ohne Body waere ohne den
Original-Flag-Aufruf nicht reproduzierbar; ein Body im Artefakt braucht einen
eigenen versionierten Compat-Gate. Operator-Hinweis: fuer langlebige Plaene
File-Overlay nutzen. Der Slice dokumentiert das in der Flag-Hilfe und im
CLI-Report; Plan-Artefakt-Einbettung braucht einen spaeteren Body-
Einbettungs- und Compat-Gate-Slice.

## 7. Out-of-Scope-Verweis

USING-Expressions, Sequence-Owner-Mappings und andere komplexere
Overlay-Eintraege bleiben File-only. Dieser Slice deckt nur den
einfachsten, haeufigsten Operator-Fall ab.
