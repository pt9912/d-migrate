# Implementierungsplan: 0.9.7 — F.4 RENAME_MAPPING_INVALID Enum-Wert

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: F.4 (sechster Slice — neuer MigrationBlockedReason)
> **Status**: open (geplant, noch nicht gestartet)
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
  Mappings, Strukturmismatch)
- `ROLLBACK_NOT_POSSIBLE` (wenn Rename-Down strukturell nicht
  reversibel ist)

Dieser Slice fuehrt `RENAME_MAPPING_INVALID` als eigenen Enum-Wert
ein, damit Reports, Tooling-Clients und Plan-Artefakt-Konsumenten
Rename-spezifische Blocker maschinenlesbar von anderen
Manual-Action-Faellen unterscheiden koennen.

## 2. Motivation

Der heutige Sammel-Reason `MANUAL_ACTION_REQUIRED` macht es schwer,
Rename-Probleme automatisch von z.B. USING-Expression-Problemen oder
Spatial-Index-Problemen zu unterscheiden. Das ist insbesondere fuer
CLI-/Report-/Artefakt-Konsumenten relevant, die Rename-Overlay-Fehler dem
Operator gezielt als Overlay-Edit-Vorschlag praesentieren wollen.

`TRANSACTION_SCOPE_UNSUPPORTED` ist die Blaupause: erst spaeter
hinzugefuegt, einheitlich gerollt durch Enum, JSON-Codec, Renderer
und Tests.

## 3. Scope

In Scope:

- Neuer Enum-Wert `MigrationBlockedReason.RENAME_MAPPING_INVALID` in
  `hexagon:ports-read`.
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
- Backward-Compat gilt nur fuer serialisierte Artefakt-/Report-Reader,
  die Reasons als String lesen. Der aktuelle Runtime-Typ
  `MigrationBlockedReason` ist ein geschlossenes Kotlin-Enum; unbekannte
  Werte koennen dort nicht "pass-through" bleiben. Der Slice muss daher
  entweder:
  - die betroffenen Artefakt-/Report-Codecs auf ein tolerant gelesenes
    String-DTO vor dem Enum-Mapping fuehren, oder
  - die Compat-Aussage auf alte bekannte Werte (`MANUAL_ACTION_REQUIRED`)
    beschraenken und unbekannte neue Werte fuer alte Runtime-Binaries als
    nicht unterstuetzt dokumentieren.
- MCP-Layer bleibt aus diesem Slice heraus. `MigrationBlockedReason` ist
  der CLI-/Report-/Artefakt-Reason, waehrend MCP-`dmigrateCode` heute
  JSON-RPC-Fehlerfamilien wie `VALIDATION_ERROR`, `TENANT_SCOPE_DENIED`
  und `RESOURCE_NOT_FOUND` beschreibt. Ein spaeteres MCP-Migrationstool
  darf den neuen Reason als Reportfeld durchreichen, aber dieser Slice
  erweitert keine `dmigrateCode`-Enum-Liste.
- Tests:
  - Enum-Roundtrip-Test (Name, Ordinal-Stabilitaet)
  - Preflight-/Report-Tests: alle Rename-Overlay-Blocker-Pfade
    emittieren den neuen Reason
  - Plan-/Report-Artefakt-Compatibility-Test: Plaene mit alten
    `MANUAL_ACTION_REQUIRED`-Reasons fuer Rename werden weiterhin
    gelesen. Falls ein Reader unbekannte Reasons tolerieren soll, muss
    das ueber einen expliziten String-Zwischentyp getestet werden; das
    geschlossene Runtime-Enum allein reicht dafuer nicht.
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

Im `MigrationOverlayPreflight.validate` wird die Diagnostic-Severity
fuer `OVERLAY_RENAME_MAPPING_*`-Codes mit dem neuen Reason verknuepft.
Der Preflight-Layer emittiert heute `MANUAL_ACTION_REQUIRED` als
Sammel-Reason fuer alle Overlay-Probleme; ab jetzt unterscheidet er
zwischen USING-bezogenen und Rename-bezogenen Codes und vergibt den
passenden Reason.

Mapper-/Planner-Diagnostics werden nach derselben Reason-Tabelle
klassifiziert, sobald sie als `BLOCKER` in ein `MigrationDdlResult`
uebernommen werden. Warnings erzeugen weiterhin keinen
`MigrationBlocker` und damit keinen `primaryBlockedReason`.

Konkret wird `MigrationOverlayPreflight.buildFailureResult(...)` von
einem einzelnen Sammel-Blocker auf gruppierte Blocker umgestellt:

- Blocker-Diagnostics mit Code `OVERLAY_RENAME_MAPPING_*` oder
  `OBJECT_RENAME_UNSUPPORTED` landen in einem
  `MigrationBlocker(reason = RENAME_MAPPING_INVALID)`.
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

### 4.3 Code-Tabelle

| Diagnostic-Code | Heutiger Reason | Neuer Reason |
| --------------- | --------------- | ------------ |
| `OVERLAY_RENAME_MAPPING_STALE_FINGERPRINT` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OVERLAY_RENAME_MAPPING_AMBIGUOUS` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OVERLAY_RENAME_MAPPING_CASE_CONFLICT` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OVERLAY_RENAME_MAPPING_CHAIN_UNSUPPORTED` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OVERLAY_RENAME_MAPPING_DUPLICATE` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `OBJECT_RENAME_UNSUPPORTED` | MANUAL_ACTION_REQUIRED | RENAME_MAPPING_INVALID |
| `RENAME_DEPENDENCY_UNPROJECTABLE` als WARNING mit Drop+Add-Fallback | (kein Blocker) | (kein Reason) |
| `RENAME_DEPENDENCY_UNPROJECTABLE` als BLOCKER ohne verlustfreien Fallback | MANUAL_ACTION_REQUIRED | MANUAL_ACTION_REQUIRED |
| `RENAME_OVERLAY_STRUCTURAL_MISMATCH` | (nur WARNING) | (bleibt WARNING — kein Reason) |

## 5. Akzeptanzkriterien

- [ ] `MigrationBlockedReason.RENAME_MAPPING_INVALID` existiert als
      letzter Enum-Wert; bestehende Ordinals sind unveraendert.
- [ ] `MigrationOverlayPreflight` emittiert den neuen Reason fuer alle
      bestehenden `OVERLAY_RENAME_MAPPING_*`-Blocker-Codes:
      `STALE_FINGERPRINT`, `AMBIGUOUS`, `CASE_CONFLICT`,
      `CHAIN_UNSUPPORTED` und `DUPLICATE`.
- [ ] Mapper-/Planner-Blocker mit `OBJECT_RENAME_UNSUPPORTED` emittieren
      ebenfalls `RENAME_MAPPING_INVALID`; `RENAME_DEPENDENCY_UNPROJECTABLE`
      bleibt bei Drop+Add-Fallback eine Warning ohne Reason und wird nur
      bei fehlendem Fallback als `MANUAL_ACTION_REQUIRED` blockierend.
- [ ] Andere Overlay-Codes (USING-Expression, allgemeine F.0-
      Verletzungen) emittieren weiterhin `MANUAL_ACTION_REQUIRED`.
- [ ] Ein gemischter Preflight-Fall mit mindestens einem
      `OVERLAY_RENAME_MAPPING_*`-Blocker und mindestens einem anderen
      Overlay-Blocker erzeugt zwei `MigrationBlocker`; `primaryBlockedReason`
      ist `RENAME_MAPPING_INVALID`, und die jeweiligen Diagnostics bleiben
      in der passenden Reason-Gruppe.
- [ ] Renderer- und Report-JSON-Tests pinnen den neuen Reason-String.
- [ ] `spec/cli-spec.md` §6.1 nennt Rename-Overlay-Blocker als
      Exit-8-Fall und dokumentiert den neuen
      `primaryBlockedReason = RENAME_MAPPING_INVALID`.
- [ ] Plan-/Report-Artefakt-Compatibility-Test: ein Artefakt v1 mit
      altem `MANUAL_ACTION_REQUIRED`-Reason fuer einen Rename-Blocker
      bleibt lesbar und ausfuehrbar (nur die Klassifikation hat sich
      geaendert, nicht die Semantik).
- [ ] Falls unbekannte future Reasons toleriert werden sollen, existiert
      ein eigener toleranter String-Reader-Test. Ohne diesen Test wird
      keine Pass-Through-Compat fuer unbekannte Enum-Werte behauptet.
- [ ] Kein MCP-`dmigrateCode`-Schema wird geaendert; bestehende MCP-
      Fehlerfamilien bleiben unveraendert.
- [ ] `roadmap.md` und `diffresult-migration-plan-2.md §10 F.4`
      bekommen einen Status-Update mit Datum des Slice-Abschlusses.

## 6. Definition of Done

- [ ] Alle Akzeptanzkriterien aus §5 erfuellt.
- [ ] `make docker-test` gruen, Output in `/tmp/build.log`.
- [ ] Coverage `hexagon:ports-read`, `hexagon:core`,
      `hexagon:application` ≥ 90%.
- [ ] CHANGELOG.md erhaelt einen Eintrag zur Reason-Klassifikations-
      Verfeinerung mit Hinweis auf Backward-Compat.
- [ ] Plan-Datei nach `docs/planning/done/` verschoben.

## 7. Risiken

### 7.1 Backward-Compatibility fuer Plan-Artefakte

Bestehende Plan-/Report-Artefakte koennen Rename-Blocker mit
`MANUAL_ACTION_REQUIRED` enthalten. Der neue Slice darf diese Artefakte
NICHT invalidieren — alte bekannte Reasons bleiben gueltig und werden
semantisch als Rename-Blocker verstanden, wenn der Diagnostic-Code ein
`OVERLAY_RENAME_MAPPING_*`-Code ist.

Unbekannte future Reasons sind ein separater Vertrag. Da
`MigrationBlockedReason` heute ein geschlossenes Enum ist, kann ein
alter Runtime-Reader sie nicht automatisch pass-through erhalten.

**Mitigation**: Artefakt-/Report-Validator akzeptiert beide bekannten
Werte (`MANUAL_ACTION_REQUIRED` UND `RENAME_MAPPING_INVALID`) fuer
denselben Diagnostic-Code, mit einer Info-Diagnose dass die
Klassifikation verfeinert wurde. Future-Reason-Toleranz wird nur
versprochen, wenn der Reader explizit ueber einen String-Zwischentyp
implementiert und getestet ist. Ein Folge-Slice koennte Plaene auf den
neuen Wert umnormalisieren.

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
