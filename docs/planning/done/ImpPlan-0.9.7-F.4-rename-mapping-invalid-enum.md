# Implementierungsplan: 0.9.7 — F.4 RENAME_MAPPING_INVALID Enum-Wert

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: F.4 (sechster Slice — neuer MigrationBlockedReason)
> **Status**: done (Slice abgeschlossen 2026-05-15)
> **Vorbedingung**: F.4 Rendering-Slice ✅
> **Referenz**: `docs/planning/in-progress/diffresult-migration-plan-2.md`
>             §10 F.4 (Plan-Eintrag und Carve-out)
>             `docs/planning/done/ImpPlan-0.9.7-F.4-rendering.md`

---

## 1. Ziel

Plan-2 §10 F.4 spezifiziert:

> "`RENAME_MAPPING_INVALID` ist wie `TRANSACTION_SCOPE_UNSUPPORTED`
> erst ein stabiler Report-Wert, nachdem Enum, CLI-JSON,
> Report-Rendering und Tests angepasst sind. Bis dahin muss ein
> Implementierungsslice auf bestehende Blocker-Reasons mappen und
> die genauere Rename-Diagnostic separat ausweisen."

Heute meldet die Rename-Pipeline ueber bestehende Reasons:

- `MANUAL_ACTION_REQUIRED` (z.B. stale Fingerprint, mehrdeutige
  Mappings oder andere harte Rename-Overlay-Blocker)
- `ROLLBACK_NOT_POSSIBLE` (wenn Rename-Down strukturell nicht
  reversibel ist)

Strukturmismatch selbst ist im heutigen Rendering-Slice kein harter
Blocker: `RENAME_OVERLAY_STRUCTURAL_MISMATCH` bleibt eine Warning und
der Plan faellt auf Drop+Add zurueck.

Dieser Slice fuehrt `RENAME_MAPPING_INVALID` als eigenen Enum-Wert
ein, damit CLI-/Report-JSON und Tooling-Clients Rename-spezifische
Blocker maschinenlesbar von anderen Manual-Action-Faellen unterscheiden
koennen. `migration-plan.v1` traegt heute keine `MigrationBlockedReason`;
dieser Slice aendert daher den Plan-Artefakt-Vertrag nicht.

## 2. Motivation

Der heutige Sammel-Reason `MANUAL_ACTION_REQUIRED` macht es schwer,
Rename-Probleme automatisch von z.B. USING-Expression-Problemen oder
Spatial-Index-Problemen zu unterscheiden. Das ist insbesondere fuer
CLI-/Report-Konsumenten relevant, die Rename-Overlay-Fehler dem Operator
gezielt als Overlay-Edit-Vorschlag praesentieren wollen.

`TRANSACTION_SCOPE_UNSUPPORTED` ist die Blaupause: erst spaeter
hinzugefuegt, einheitlich gerollt durch Enum, JSON-Codec, Renderer
und Tests.

## 3. Scope

In Scope:

- Neuer Enum-Wert `MigrationBlockedReason.RENAME_MAPPING_INVALID` in
  `hexagon:ports-read`.
- Planungsreihenfolge fuer Rename-Overlay-Blocker: Rename-spezifische
  Overlay-Fehler duerfen den Planner nicht erst nachtraeglich blockieren,
  wenn das invalide Overlay bereits von `OperationMapper` konsumiert wurde.
  Der Slice zieht deshalb die planunabhaengige Overlay-Validierung
  (Hash/Fingerprint/Dialekt, dokumentlokale Rename-Mapping-Fehler und
  Cross-Document-Uniqueness) vor den ersten `DiffPlanner.plan(...)`-Aufruf
  oder fuehrt einen gleichwertigen Pre-Plan-Gate ein. Erst wenn dieser Gate
  keine BLOCKER liefert, darf der Planner die Rename-Overlays konsumieren.
- API-Vertrag fuer diesen Pre-Plan-Gate: Die Validierung darf nicht mehr
  implizit `DiffResult.current/desired.fingerprint` lesen. Der Runner
  berechnet die erwarteten Fingerprints vorab aus den normalisierten
  Operanden und uebergibt sie zusammen mit Dialekt, geladenen Overlay-
  Dokumenten und Load-Failures an einen planunabhaengigen Entry-Point
  (z.B. `MigrationOverlayPreflight.validateBeforePlan(...)`). Der
  bestehende `validate(plan, ...)`-Pfad wird entweder zu einem duennen
  Adapter auf diese API oder bleibt nur fuer planabhaengige Post-Plan-
  Findings zustaendig.
- Aktuelle `rename-mapping.objectType`-Whitelist im zentralen Pre-Plan-
  Gate: Solange nur der F.4-Rendering-Slice umgesetzt ist, sind
  ausschliesslich `{table, column}` gueltig. Alle anderen Werte, inklusive
  zukuenftiger Objektklassen wie `view`, `trigger`, `function`,
  `procedure`, `sequence` und `materialized_view`, blockieren vor `plan()`
  mit dem bestehenden Code `OVERLAY_UNKNOWN_ENTRY_KIND` oder einem bereits
  versionierten Whitelist-Code und werden unter
  `RENAME_MAPPING_INVALID` klassifiziert. Der spaetere
  View-/Trigger-/Routine-Rename-Slice erweitert diese Whitelist explizit;
  bis dahin darf der Mapper unbekannte `objectType`s nicht still
  ignorieren.
- Cross-Document-Uniqueness gehoert ebenfalls zu diesem zentralen Pre-Plan-
  Gate. Der CLI-Inline-Slice darf dieselbe API aufrufen und synthetische
  Inline-Overlays in die Dokumentliste einspeisen, aber keine zweite
  Implementierung der Rename-Uniqueness- oder Reason-Klassifikation
  einfuehren.
- Der Dependency-Projection-Slice konsumiert ebenfalls nur Overlays, die
  diesen Pre-Plan-Gate bestanden haben. Runtime- oder Dependency-
  Capabilities duerfen nachgelagert geprueft werden, aber harte
  `OVERLAY_RENAME_MAPPING_*`-Fehler und Cross-Document-Provenance-Konflikte
  duerfen nicht erst entdeckt werden, nachdem `OperationMapper` ein Rename-
  Mapping konsumiert hat.
- Preflight-/Report-Aenderungen: alle Rename-Overlay-spezifischen
  Blocker melden ab jetzt diesen neuen Reason statt
  `MANUAL_ACTION_REQUIRED`. Rename-Warnings werden in derselben Tabelle
  explizit als nicht-blockierend festgehalten:
  - `OVERLAY_RENAME_MAPPING_STALE_FINGERPRINT`
  - `OVERLAY_RENAME_MAPPING_AMBIGUOUS`
  - `OVERLAY_RENAME_MAPPING_CASE_CONFLICT`
  - `OVERLAY_RENAME_MAPPING_CHAIN_UNSUPPORTED`
  - `OVERLAY_RENAME_MAPPING_DUPLICATE`
  - `RENAME_OVERLAY_STRUCTURAL_MISMATCH` (heute nur Warning — bleibt
    Warning, kein neuer Reason)
  - `OBJECT_RENAME_UNSUPPORTED` (spaeterer View-/Trigger-/Routine-
    Rename-Slice): wenn der Mapper ein explizites Rename-Mapping wegen
    Objektklasse/Dialekt/Materialized-View-Vertrag hart blockiert, landet
    der Blocker ebenfalls unter `RENAME_MAPPING_INVALID`, weil der
    Operator das Rename-Mapping entfernen oder in einen unterstuetzten
    File-/Drop+Create-Pfad ueberfuehren muss.
  - `RENAME_DEPENDENCY_UNPROJECTABLE` (spaeterer Dependency-Projection-
    Slice) bleibt **kein** `RENAME_MAPPING_INVALID`, solange der
    Drop+Add-Fallback vollstaendig geplant wird und die Diagnose nur eine
    `WARNING` ist. Falls ein Fallback-Lueckenfall spaeter als echter
    `BLOCKER` modelliert wird, ist der Reason
    `MANUAL_ACTION_REQUIRED`, weil nicht das Mapping selbst invalid ist,
    sondern eine Dependency-Projektion manuell entschieden werden muss.
- `MigrationBlocker`/`MigrationBlockedReason`-DTO-/Enum-Vertrag:
  Reihenfolge im Enum bleibt stabil — neuer Wert am Ende, nicht
  zwischen bestehende Werte eingefuegt.
- JSON-Codec / CLI-Report-Renderer: neuer Reason taucht im JSON-
  Output als `"RENAME_MAPPING_INVALID"` auf.
- `spec/cli-spec.md` §6.1 dokumentiert `RENAME_MAPPING_INVALID` als
  unterscheidbaren Exit-8-`primaryBlockedReason` fuer Rename-Overlay-
  Fehler. Die Spec bleibt die oeffentliche CLI-/Report-Quelle; der
  Plan allein reicht fuer den neuen Report-Wert nicht.
- Backward-Compat gilt nur fuer serialisierte Report-Reader, die Reasons
  als String lesen. Der aktuelle Runtime-Typ `MigrationBlockedReason` ist
  ein geschlossenes Kotlin-Enum; unbekannte Werte koennen dort nicht
  "pass-through" bleiben. Der Slice muss daher entweder:
  - die betroffenen Report-Codecs auf ein tolerant gelesenes String-DTO
    vor dem Enum-Mapping fuehren, oder
  - die Compat-Aussage auf alte bekannte Werte (`MANUAL_ACTION_REQUIRED`)
    beschraenken und unbekannte neue Werte fuer alte Runtime-Binaries als
    nicht unterstuetzt dokumentieren.
- MCP-Layer bleibt aus diesem Slice heraus. `MigrationBlockedReason` ist
  der CLI-/Report-Reason, waehrend MCP-`dmigrateCode` heute
  JSON-RPC-Fehlerfamilien wie `VALIDATION_ERROR`, `TENANT_SCOPE_DENIED`
  und `RESOURCE_NOT_FOUND` beschreibt. Ein spaeteres MCP-Migrationstool
  darf den neuen Reason als Reportfeld durchreichen, aber dieser Slice
  erweitert keine `dmigrateCode`-Enum-Liste.
- Tests:
  - Enum-Roundtrip-Test (Name, Ordinal-Stabilitaet)
  - Preflight-/Report-Tests: alle Rename-Overlay-Blocker-Pfade
    emittieren den neuen Reason
  - CLI-/Report-JSON-Compatibility-Test: bestehende Report-Goldens oder
    Fixtures mit alten `MANUAL_ACTION_REQUIRED`-Reasons fuer Rename-
    Blocker bleiben semantisch als blockiert dokumentiert. Der
    `migration-plan.v1`-Artefaktvertrag bleibt unveraendert, weil er
    aktuell keine `MigrationBlockedReason` serialisiert. Falls ein
    Reader unbekannte Reasons tolerieren soll, muss das ueber einen
    expliziten String-Zwischentyp getestet werden; das geschlossene
    Runtime-Enum allein reicht dafuer nicht.
  - Kein MCP-`dmigrateCode`-Snapshot in diesem Slice; falls spaetere
    Migrationstools `MigrationBlockedReason` in Tool-Schemas exponieren,
    bekommen sie einen eigenen Schema-Slice.

Aus Scope:

- Neue Diagnostic-Codes fuer Rename-Probleme: bestehender Code
  reicht. Diese Aenderung ist rein eine Reason-Klassifikations-
  Verfeinerung.
- Reason-Migration fuer USING-Expressions, Spatial usw.: jeder
  Workstream entscheidet selbst, ob ein eigener Reason sinnvoll ist.
- MCP-`dmigrateCode`-Aenderungen: `RENAME_MAPPING_INVALID` ist kein
  JSON-RPC-Fehlercode, sondern ein Migration-Report-Reason.

## 4. Architektur

### 4.1 Enum-Erweiterung

`hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/migration/MigrationBlocker.kt`:

```kotlin
enum class MigrationBlockedReason {
    // bestehende Werte bleiben in unveraenderter Reihenfolge
    DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION,
    ROLLBACK_NOT_POSSIBLE,
    MANUAL_ACTION_REQUIRED,
    TARGET_STATE_MISMATCH,
    TARGET_DIALECT_MISMATCH,
    DIALECT_UNSUPPORTED_OPERATION,
    TRANSACTION_SCOPE_UNSUPPORTED,
    RENAME_MAPPING_INVALID,   // NEU — am Ende anhaengen
}
```

### 4.2 Preflight- und Mapper-Anpassung

Im `OperationMapper.mapRenameTables`/`mapRenameColumns` werden
Diagnostics weiterhin als `WARNING` mit Code
`RENAME_OVERLAY_STRUCTURAL_MISMATCH` ausgegeben — die strukturelle
Mismatch-Logik bleibt unveraendert, weil sie auf Drop+Add zurueckfaellt
und kein Hard-Blocker ist.

Die bisherige Runner-Reihenfolge `compare -> plan -> overlay preflight`
ist fuer harte Rename-Overlay-Fehler nicht ausreichend, weil der Planner
die Overlays bereits konsumiert. Dieser Slice trennt daher die
Overlay-Pruefung in zwei Gates:

1. **Pre-Plan-Gate** nach Load/Normalisierung/Dialekt-Aufloesung und vor
   `DiffPlanner.plan(...)`: prueft alle planunabhaengigen
   Overlay-Vertraege, insbesondere Hash, Fingerprints, Dialekt,
   dokumentlokale `OVERLAY_RENAME_MAPPING_*`-Fehler und
   Cross-Document-Uniqueness. BLOCKER aus diesem Gate erzeugen ein
   synthetisches Pre-Plan-Blocker-Result ohne Operationenliste.
2. **Post-Plan-Gate** fuer planabhaengige Findings, die tatsaechlich
   Operationen brauchen. Dieses Gate darf keine invaliden Rename-Mappings
   mehr entdecken, die schon im Pre-Plan-Gate haetten blockieren muessen.

Der neue Pre-Plan-Entry-Point hat einen expliziten Kontext, nicht ein
halb gebautes `DiffResult`:

```kotlin
internal data class MigrationOverlayPrePlanContext(
    val expectedSourceFingerprint: String,
    val expectedTargetFingerprint: String,
    val expectedDialect: String,
    val documents: List<MigrationOverlayDocument>,
    val loadFailures: List<MigrationOverlayLoadFailure> = emptyList(),
    val supportedRenameObjectTypes: Set<String> = setOf("table", "column"),
)

internal fun MigrationOverlayPreflight.validateBeforePlan(
    context: MigrationOverlayPrePlanContext,
): MigrationOverlayPreflightResult
```

Der Result-/Diagnostic-Carrier muss fuer die Reason-Klassifikation
strukturierte Overlay-Kontextdaten behalten. Mindestens erforderlich sind:
`overlayKind`, `entryKind`, `renameObjectType`, `source`, `entryId` und
`overlayHash` pro Finding bzw. Report-Item. Der Classifier darf nicht aus
freien Fehlermeldungen ableiten, ob ein `OVERLAY_UNKNOWN_ENTRY_KIND` aus einem
`rename-mapping.objectType` oder aus einem generischen unbekannten Entry stammt.
Falls der bestehende `MigrationOverlayReportItem` dafuer nicht reicht, fuehrt
dieser Slice einen internen `MigrationOverlayFinding`-Carrier ein und projiziert
ihn erst fuer die CLI-Ausgabe auf das bisherige Report-Item-Shape.

Der Runner befuellt `expectedSourceFingerprint` mit dem Fingerprint des
aktuellen Zielzustands und `expectedTargetFingerprint` mit dem Fingerprint des
gewuenschten Quell-/Soll-Schemas, also mit denselben Werten, die der spaetere
`DiffPlanner` in `plan.current.fingerprint` und `plan.desired.fingerprint`
schreibt. Ein Test pinnt diese Gleichheit und zaehlt den Planner-Aufruf:
bei Pre-Plan-Blockern wird `plan()` nicht aufgerufen, bei validen Overlays
genau einmal.

Im `MigrationOverlayPreflight.validate` bzw. den daraus extrahierten Gate-
Funktionen wird die Diagnostic-Severity fuer
`OVERLAY_RENAME_MAPPING_*`-Codes mit dem neuen Reason verknuepft. Der
Preflight-Layer emittiert heute `MANUAL_ACTION_REQUIRED` als Sammel-
Reason fuer alle Overlay-Probleme; ab jetzt unterscheidet er zwischen
USING-bezogenen und Rename-bezogenen Codes und vergibt den passenden
Reason.

Mapper-/Planner-Diagnostics werden nach derselben Reason-Tabelle
klassifiziert, sobald sie als `BLOCKER` in ein `MigrationDdlResult`
uebernommen werden. Warnings erzeugen weiterhin keinen
`MigrationBlocker` und damit keinen `primaryBlockedReason`.

Konkret wird `MigrationOverlayPreflight.buildFailureResult(...)` von
einem einzelnen Sammel-Blocker auf gruppierte Blocker umgestellt:

- Blocker-Diagnostics mit Code `OVERLAY_RENAME_MAPPING_*` oder
  `OBJECT_RENAME_UNSUPPORTED` landen in einem
  `MigrationBlocker(reason = RENAME_MAPPING_INVALID)`.
- Blocker-Diagnostics mit Code `OVERLAY_UNKNOWN_ENTRY_KIND` landen ebenfalls
  in `RENAME_MAPPING_INVALID`, wenn sie aus einem `rename-mapping`-Eintrag
  mit nicht freigeschaltetem `objectType` stammen. Generische unbekannte
  Entry-Kinds in anderen Overlay-Arten bleiben bei
  `MANUAL_ACTION_REQUIRED`, solange kein eigener Reason existiert. Diese
  Unterscheidung muss ueber die strukturierten Finding-Felder aus dem
  Pre-Plan-Gate laufen; Message-Parsing oder Entry-ID-Konventionen sind nicht
  zulaessig. Dieser Slice fuehrt keinen neuen Diagnostic-Code fuer nicht
  freigeschaltete `rename-mapping.objectType`-Werte ein; er verfeinert nur die
  Reason-Klassifikation des bestehenden `OVERLAY_UNKNOWN_ENTRY_KIND` anhand
  strukturierter Overlay-Kontextfelder.
- Blocker-Diagnostics mit Code `RENAME_DEPENDENCY_UNPROJECTABLE` landen
  in `MigrationBlocker(reason = MANUAL_ACTION_REQUIRED)`. Der normale
  Drop+Add-Fallback-Fall ist aber nur eine Warning und erzeugt keinen
  Blocker.
- Alle anderen Overlay-Blocker bleiben in
  `MigrationBlocker(reason = MANUAL_ACTION_REQUIRED)`.
- Wenn beide Gruppen im selben Preflight auftreten, enthaelt der
  Result beide Blocker; `primaryBlockedReason` ist
  `RENAME_MAPPING_INVALID`, sobald mindestens eine Rename-Gruppe
  existiert, sonst `MANUAL_ACTION_REQUIRED`.
- Generische Overlay-Diagnostics wie `OVERLAY_STALE_SOURCE_FINGERPRINT`
  bleiben `MANUAL_ACTION_REQUIRED`, auch wenn dasselbe Dokument
  zusaetzlich `OVERLAY_RENAME_MAPPING_STALE_FINGERPRINT` meldet. Die
  Rename-spezifische Diagnose ist der maschinenlesbare Trigger fuer
  den neuen Reason.
- Pre-Plan-Blocker nutzen denselben Gruppierungsvertrag, aber
  `operationIds = emptySet()`, weil noch kein autorisierter Plan existiert.
  Der Reportpfad muss diese Resultate ohne Dummy-`DiffResult` serialisieren.

### 4.3 Code-Tabelle

| Diagnostic-Code | Heutiger Reason | Neuer Reason |
| --------------- | --------------- | ------------ |
| `OVERLAY_RENAME_MAPPING_STALE_FINGERPRINT` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OVERLAY_RENAME_MAPPING_AMBIGUOUS` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OVERLAY_RENAME_MAPPING_CASE_CONFLICT` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OVERLAY_RENAME_MAPPING_CHAIN_UNSUPPORTED` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OVERLAY_RENAME_MAPPING_DUPLICATE` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OVERLAY_UNKNOWN_ENTRY_KIND` fuer nicht freigeschalteten `rename-mapping.objectType` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OBJECT_RENAME_UNSUPPORTED` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `RENAME_DEPENDENCY_UNPROJECTABLE` als WARNING mit Drop+Add-Fallback | (kein Blocker) | (kein Reason) |
| `RENAME_DEPENDENCY_UNPROJECTABLE` als BLOCKER ohne verlustfreien Fallback | MANUAL_ACTION_REQUIRED | MANUAL_ACTION_REQUIRED |
| `RENAME_OVERLAY_STRUCTURAL_MISMATCH` | (nur WARNING) | (bleibt WARNING — kein Reason) |

## 5. Akzeptanzkriterien

- [x] `MigrationBlockedReason.RENAME_MAPPING_INVALID` existiert als
      letzter Enum-Wert; bestehende Ordinals sind unveraendert.
      `MigrationDdlResultTest` pinnt die Enum-Liste in voller Reihenfolge.
- [x] `MigrationOverlayPreflight` emittiert den neuen Reason fuer alle
      bestehenden `OVERLAY_RENAME_MAPPING_*`-Blocker-Codes:
      `STALE_FINGERPRINT`, `AMBIGUOUS`, `CASE_CONFLICT`,
      `CHAIN_UNSUPPORTED` und `DUPLICATE` (je ein Test in
      `MigrationOverlayPreflightTest`).
- [x] Es gibt einen planunabhaengigen Pre-Plan-Validation-Entry-Point
      (`MigrationOverlayPreflight.validateBeforePlan(...)`), der
      Source-/Target-Fingerprints, Dialekt, Overlay-Dokumente, Load-
      Failures und die Rename-Whitelist explizit entgegennimmt. Der
      Test "planless gate validates rename overlays without a
      DiffResult" pinnt den DiffResult-freien Aufruf-Vertrag.
- [x] Der Pre-Plan-Gate blockiert `rename-mapping.objectType`-Werte ausserhalb
      der aktuell freigeschalteten Whitelist `{table, column}` vor `plan()`
      mit `RENAME_MAPPING_INVALID`. Tests pinnen `view`, `trigger`,
      `function`, `procedure`, `sequence` und `materialized_view`.
- [x] Reason-Klassifikation basiert auf strukturiertem Overlay-Kontext
      (`MigrationOverlayDiagnostic.entryKind` /
      `renameObjectType`). Test "generic UNKNOWN_ENTRY_KIND load
      failure stays MANUAL_ACTION_REQUIRED" zeigt, dass derselbe Code
      ohne Rename-Kontext bei `MANUAL_ACTION_REQUIRED` bleibt; der
      Classifier liest keine Message-Texte und keine Entry-ID-Muster.
- [x] Cross-Document-Uniqueness laeuft weiterhin im zentralen Validator
      (`MigrationOverlayValidator.validateRenameUniqueness` /
      `validateRenameDuplicates`) und wird von `validateBeforePlan(...)`
      mit allen Dokumenten gefuettert; der CLI-Inline-Slice ruft genau
      diese API auf, ohne sie zu duplizieren.
- [x] Der Dependency-Projection-Slice ist bereits an
      `validateBeforePlan(...)` vor `DiffPlanner.plan(...)` gehaengt
      (`SchemaMigratePrePlanOverlayGateTest`); harte
      `OVERLAY_RENAME_MAPPING_*`-Findings erscheinen vor `plan()`.
- [x] `MigrationOverlayPreflight.validate(plan, ...)` ist nur noch ein
      Adapter, der Fingerprints aus dem `DiffResult` zieht und an
      `validateBeforePlan(...)` weiterreicht. Harte Rename-Blocker
      werden im Runner ueber den Pre-Plan-Gate erkannt.
- [x] Rename-Overlay-Blocker werden vor dem ersten
      `DiffPlanner.plan(...)` erkannt — `SchemaMigratePrePlanOverlayGateTest`
      zaehlt Planner-Aufrufe und pinnt keine emittierten Renames.
- [x] Mapper-/Planner-Blocker mit `OBJECT_RENAME_UNSUPPORTED`
      mappen ebenfalls auf `RENAME_MAPPING_INVALID` (Classifier-Eintrag
      in `MigrationOverlayPreflight.OBJECT_RENAME_UNSUPPORTED_CODE`).
      `RENAME_DEPENDENCY_UNPROJECTABLE` bleibt Warning beim Drop+Add-
      Fallback (heute) und ist nicht im Classifier — bleibt damit
      `MANUAL_ACTION_REQUIRED`, sobald spaeter ein verlustbehafteter
      Fallback als BLOCKER modelliert wird.
- [x] Andere Overlay-Codes (USING-Expression, allgemeine F.0-
      Verletzungen) emittieren weiterhin `MANUAL_ACTION_REQUIRED` —
      der "unsigned using-expression overlay blocks before render"-
      Test pinnt das.
- [x] Ein gemischter Preflight-Fall mit mindestens einem
      `OVERLAY_RENAME_MAPPING_*`-Blocker und mindestens einem anderen
      Overlay-Blocker erzeugt zwei `MigrationBlocker`;
      `primaryBlockedReason` ist `RENAME_MAPPING_INVALID`, und die
      jeweiligen Diagnostics bleiben in der passenden Reason-Gruppe
      (Test "stale fingerprint emits RENAME_MAPPING_INVALID, not
      MANUAL_ACTION_REQUIRED").
- [x] Report-JSON-Tests pinnen den neuen Reason-String:
      `SchemaMigrateBlockerView.reason` traegt
      `MigrationBlockedReason.name` (`MigrationDdlResultTest`
      bestaetigt den Enum-Namen-Vertrag).
- [x] `spec/cli-spec.md` §6.1 nennt Rename-Overlay-Blocker als Exit-8-
      Fall und dokumentiert `primaryBlockedReason = RENAME_MAPPING_INVALID`.
- [x] CLI-/Report-JSON-Compatibility ist additiv: bestehende Reports
      mit `MANUAL_ACTION_REQUIRED` fuer Rename-Codes bleiben
      semantisch blockiert. `migration-plan.v1` wurde nicht geaendert
      (kein Reason serialisiert).
- [N/A] Toleranter String-Reader-Test: keine Pass-Through-Compat fuer
      unbekannte Enum-Werte behauptet — bleibt fuer einen spaeteren
      Slice offen, falls je gebraucht.
- [x] Kein MCP-`dmigrateCode`-Schema wurde geaendert.
- [x] `roadmap.md` und `diffresult-migration-plan-2.md §10 F.4`
      haben Status-Updates mit Datum 2026-05-15.

## 6. Definition of Done

- [x] Alle Akzeptanzkriterien aus §5 erfuellt (oder als N/A
      dokumentiert).
- [x] `make docker-test` gruen (Output in `/tmp/build.log`).
- [x] `make docker-gates` gruen — Detekt + Kover ≥ 90% bestaetigt.
- [x] CHANGELOG.md enthaelt einen Unreleased-Eintrag zur Reason-
      Klassifikations-Verfeinerung mit Backward-Compat-Hinweis.
- [x] Plan-Datei wird nach `docs/planning/done/` verschoben (im
      Commit, der diesen Slice abschliesst).

## 7. Risiken

### 7.1 Backward-Compatibility fuer Reports

Bestehende CLI-/Report-JSON-Ausgaben koennen Rename-Blocker mit
`MANUAL_ACTION_REQUIRED` enthalten. Der neue Slice darf diese Reports
semantisch nicht umdeuten — alte bekannte Reasons bleiben als historische
Klassifikation dokumentiert und werden als Rename-Blocker verstanden, wenn
der Diagnostic-Code ein `OVERLAY_RENAME_MAPPING_*`-Code ist.

`migration-plan.v1` ist davon nicht betroffen: Das aktuelle Plan-Artefakt
serialisiert Operations, Diagnostics und Reversibility, aber keine
`MigrationBlockedReason`. Ein Plan-Artefakt-Format mit Blocker-Reasons
braucht einen eigenen F.2-/Artefakt-Slice.

Unbekannte future Reasons sind ein separater Vertrag. Da
`MigrationBlockedReason` heute ein geschlossenes Enum ist, kann ein
alter Runtime-Reader sie nicht automatisch pass-through erhalten.

**Mitigation**: Report-Goldens und Report-Consumer-Dokumentation akzeptieren
beide bekannten Werte (`MANUAL_ACTION_REQUIRED` UND
`RENAME_MAPPING_INVALID`) fuer denselben Diagnostic-Code. Future-Reason-
Toleranz wird nur versprochen, wenn der Reader explizit ueber einen
String-Zwischentyp implementiert und getestet ist. Ein Folge-Slice koennte
historische Reports beim Einlesen auf den neuen Wert umnormalisieren.

### 7.2 MCP-Begriffsverwechslung

MCP-Clients lesen heute `error.data.dmigrateCode` fuer JSON-RPC-
Fehlerfamilien. Dieser Slice darf `RENAME_MAPPING_INVALID` dort nicht
einschleusen, weil der neue Wert ein Migration-Report-Reason ist und kein
Transport-/Validierungsfehler.

**Mitigation**: Scope und Tests halten MCP-`dmigrateCode` unveraendert.
Falls ein spaeteres MCP-Migrationstool Migration-Reports strukturiert
ausgibt, exponiert es `MigrationBlockedReason` als eigenes Reportfeld mit
eigenem Schema-Test.

### 7.3 Reihenfolge im Enum

Wenn der neue Wert zwischen bestehende Werte eingefuegt wird, bricht
das Ordinals-basierte Serialisierungs-Logik (z.B. wenn irgendwo per
`ordinal()` serialisiert wird). Defensive Anforderung: am Ende
anhaengen, nichts zwischenfuegen.

**Mitigation**: Test pinnt die Reihenfolge der bestehenden Werte.

## 8. Out-of-Scope-Verweis

Dieser Slice ist rein eine Reason-Klassifikations-Verfeinerung. Er
fuehrt keine neuen Diagnostic-Codes ein und aendert keine
Mapper-/Renderer-Semantik. Andere F.4-Folgeslices (Dependency-
Projection, weitere Objektklassen, CLI-Inline-Overlay) sind unabhaengig
und koennen vor oder nach diesem Slice landen.
