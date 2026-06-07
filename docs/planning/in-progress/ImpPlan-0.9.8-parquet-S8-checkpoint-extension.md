# S8 — Checkpoint-Erweiterung (Bundle + Single-File)

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](parquet-productive-cut-a.md)
> §3 S8).
>
> Status: Draft (2026-06-08). Verdrahtet `BundleCheckpointSpecifics`
> (AP9 §4.2) und `SingleFileCheckpointSpecifics` (AP11 §6.4) in den
> Resume-Pfad — `FileCheckpointStore` persistiert sie, der
> `ImportCheckpointManager` validiert sie, und der `ImportInputResolver`
> reicht den Resume-Hash an den `ImportInputResolutionHook` durch (loest
> den seit S6 in `DataImportRunner.kt:194` markierten
> „S6 immer null; der non-null-Pfad kommt mit S8"-TODO ab).
>
> S8 ist die exklusive Heimat der `CheckpointOperationSpecifics`-Sealed-
> Subtypen, ihrer YAML-Round-Trip-Persistenz, des Resume-Hash-
> Through-Plumbings und der `validateBundleResume`/`validateSingleFileResume`-
> Manager-Branches. **Kein** Phase-2-Schema-Fix-up im
> `ImportInputResolutionHook` (das ist B3 aus dem S6-Review; S8 reicht
> nur den Hash durch, kein Schema-Replace). **Keine** Bundle-/Single-File-
> Test-Familien (S9a/S9b).

---

## 1. Scope

Per Umbrella §3 S8-Cell und [`parquet-cli-wiring.md`](../done/parquet-cli-wiring.md) §7:

1. **`CheckpointOperationSpecifics`-Sealed-Subtypen** in
   `hexagon:ports-write/.../CheckpointManifest.kt` (heute Z. 156 nur
   das Interface ohne Subtypen):
   - `BundleCheckpointSpecifics(bundleKind: String, fingerprint: BundleFingerprint)`
     gemaess AP9 §4.2 / §7.3. `bundleKind` literal `"parquet-bundle"`.
   - `SingleFileCheckpointSpecifics(bundleKind: String, contentSha256: String, table: String)`
     gemaess AP11 §6.4. `bundleKind` literal `"parquet-single-file"`.
   - Sealed-`when`-Sweep auf allen heutigen Konsumenten via
     `rg --type kotlin -n 'is CheckpointOperationSpecifics\.' .` und
     `rg --type kotlin -n 'when \(' . | grep -F 'operationSpecific'`
     (heute leer — `FileCheckpointStore.toMap`/`fromMap` ist die
     einzige Stelle, die das Feld liest/schreibt, und die ignoriert es
     mit `null`-Branch).

2. **`FileCheckpointStore.toMap`/`fromMap`-Erweiterung**
   (`adapters/driven/streaming/.../FileCheckpointStore.kt:150` /
   `:190`):
   - `toMap` schreibt unter Schluessel `"operationSpecific"` einen
     `Map<String, Any?>` mit `"kind"`-Diskriminator + Subtyp-Felder
     (siehe [`parquet-cli-wiring.md`](../done/parquet-cli-wiring.md) §7.1 Beispiel).
   - `fromMap` liest `kind` und instanziiert die passende Variante;
     unbekannter `kind`-Wert wirft eine `IllegalStateException` mit
     Error-Code `CHECKPOINT_OPERATION_SPECIFICS_UNKNOWN_KIND`
     (AP9 §4.2; konsistent mit ADR-0006 Wiring-Drift-Exception-Familie).
   - **Kein** Schema-Versionsbump: `CURRENT_SCHEMA_VERSION` bleibt `2`
     (AP9 §7.5). Pre-AP8-Checkpoints ohne `operationSpecific` bleiben
     lesbar (Feld optional).

3. **`InputContext`-Erweiterung** in
   `hexagon:application/.../ImportRunnerTypes.kt:127`:
   - Neues optionales Feld
     `bundleExpectedSha256ByTable: Map<String, String?>? = null` —
     vom `ImportPreflightResolver` befuellt, sobald der `Resolved`-
     Subtyp eine `ResolvedBundle`-Quelle ist (AP9 §7.5).
   - Neues optionales Feld
     `singleFileContentSha256: String? = null` — vom
     `ImportPreflightResolver` befuellt, sobald die Quelle
     `ResolvedSingleFile` ist (AP11 §6.4).
   - Bestehende Felder (`effectiveTables`, `inputFilesByTable`,
     `fingerprint`) bleiben unveraendert.

4. **`ImportCheckpointManager.validateManifest`-Erweiterung**
   (`hexagon:application/.../ImportCheckpointManager.kt:108`):
   - Nach den bestehenden Pruefungen (`operationType`,
     `optionsFingerprint`, `tableSlices`) ein neues `when (manifest.operationSpecific)`-
     Schalt:
     - `is BundleCheckpointSpecifics` → `validateBundleResume(specifics, inputCtx)`
       (AP9 §7.5 Schritte 1-3: `bundleKind`-Gleichheit,
       `BundleFingerprint`-Gleichheit, SHA-256-Pflicht pro Tabelle).
     - `is SingleFileCheckpointSpecifics` → `validateSingleFileResume(specifics, inputCtx)`
       (AP11 §6.4: `contentSha256`-Gleichheit, `table`-Gleichheit).
     - `null` → Pre-AP8-Checkpoint. **OK, wenn** der aktuelle Lauf
       nicht Parquet-Bundle/SingleFile ist; sonst
       `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` (AP9). Die
       „Parquet-Lauf?"-Frage beantwortet `inputCtx.bundleExpectedSha256ByTable
       != null || inputCtx.singleFileContentSha256 != null`.
   - `saveManifest()` (Z. 216 ff.) reicht das `operationSpecific`-
     Objekt durch jeden Update-Aufruf (AP9 §7.5-Befund-Rueckspiel:
     Fingerprint ist Lauf-Invariante, nicht Initial-Information).

5. **`ImportInputResolutionHook.finalizeBeforePrepare`-Hash-Through-
   Plumbing** (Loest den S6-TODO in `DataImportRunner.kt:194` ab):
   - `DataImportRunner.runImport` liest `inputCtx.singleFileContentSha256`
     und reicht den Wert als `resumeExpectedSha256` in den Hook —
     S6 hardcodiert hier `null`.
   - Der `ParquetImportInputResolutionHook` validiert dann tatsaechlich
     den Content-Hash (heute No-Op bei `null`-Hash). `ResolvedSingleFile`-
     Re-Compute des Hash ist trivial via
     `ParquetSingleFilePreflight().phase1(...).contentSha256`.

6. **`CheckpointMode.Disabled`-Pfad** (`--no-checkpoint`,
   `hexagon:application/.../CheckpointMode.kt:24`):
   - Bereits in S6-(v) verdrahtet, dass `Disabled` den Store gar nicht
     erst aufruft. **S8 stellt sicher**, dass:
     - Single-File-Phase-1 (`ParquetSingleFilePreflight.phase1`)
       die `contentSha256`-Berechnung skippt; `inputCtx.singleFileContentSha256`
       bleibt `null`. Erlaubt `--no-checkpoint`-Laeufe ohne Doppel-Lese-
       Kosten.
     - Bundle-Phase-1 (`ParquetBundlePreflight`) **keinen** zweiten Lese-
       Durchlauf fuer Bundle-SHAs durchfuehrt (heute SHA-Berechnung
       nicht im Preflight, sondern in `ImportInputResolver`; der bleibt
       unveraendert, aber wir verifizieren das Verhalten per Test).

7. **Pre-AP8-Bruch-Release-Note** im `CHANGELOG.md`:
   - Eintrag in der `[Unreleased]`-Sektion: „Pre-0.9.8-Parquet-
     Checkpoints fuer Bundle-Importe sind nach 0.9.8 nicht mehr
     wiederaufnehmbar (Code `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT`).
     JSON/YAML/CSV-Checkpoints und Single-File-Importe ohne
     vorherigen Checkpoint sind nicht betroffen."

---

## 2. Sub-Slice-Plan

Reihenfolge ist sequenziell — jede Sub-Slice schliesst mit `make docker-check`
gruen ab, weil der Sealed-Sweep aus S8-0 sonst halbtransparent in den
Folgeslices weiterlebt.

| Sub-Slice | Inhalt | DoD |
| --------- | ------ | --- |
| **S8-0** | `CheckpointOperationSpecifics`-Sealed-Subtypen in `hexagon:ports-write/.../CheckpointManifest.kt`. `BundleCheckpointSpecifics`, `SingleFileCheckpointSpecifics`, plus `BundleFingerprint`-Hilfsdatenklasse (sofern noch nicht existent). Sealed-`when`-Sweep dokumentiert. | Klassen existieren; `make docker-check` gruen; alle bestehenden Tests gruen (Sealed-Subtypen sind heute nirgendwo konsumiert — Sweep-Zaehl leer). |
| **S8a** | `FileCheckpointStore.toMap`/`fromMap` mit `kind`-Diskriminator + Unknown-Kind-Fail-fast. Round-Trip-Test fuer beide Subtypen + Negative-Test fuer unbekanntes `kind`. | `make docker-test MODULES=":adapters:driven:streaming"` gruen; Round-Trip-Test fuer `BundleCheckpointSpecifics` und `SingleFileCheckpointSpecifics`; YAML-Bytes deterministisch (Schluessel-Reihenfolge `kind` zuerst). |
| **S8b** | `InputContext`-Erweiterung um `bundleExpectedSha256ByTable` + `singleFileContentSha256`. `ImportPreflightResolver` befuellt die Felder in den `ResolvedBundle`/`ResolvedSingleFile`-Branches. | `ImportPreflightResolverTest` deckt beide neue Felder ab; `make docker-test MODULES=":hexagon:application"` gruen. |
| **S8c** | `ImportCheckpointManager.validateManifest` mit `validateBundleResume` + `validateSingleFileResume` + Pre-AP8-Branch. `saveManifest()` reicht `operationSpecific` durch. | Manager-Tests decken alle drei Pfade (Bundle-OK, Bundle-Pre-AP8-Bruch, SingleFile-OK, SingleFile-Hash-Mismatch); `make docker-test MODULES=":hexagon:application"` gruen. |
| **S8d** | `ImportInputResolutionHook.finalizeBeforePrepare`-Hash-Through. `DataImportRunner.runImport` liest `inputCtx.singleFileContentSha256` und reicht ihn an den Hook (heute `DataImportRunner.kt:194` hardcodiert `null`). `ParquetImportInputResolutionHook` validiert den Hash. | Runner-Tests: Hash-Match-Pfad gruen; Hash-Mismatch-Pfad wirft mit AP11-§6.4-Code; `make docker-check` gruen. |
| **S8e** | `--no-checkpoint` × Single-File: `ParquetSingleFilePreflight.phase1` skippt `contentSha256`-Berechnung, wenn der Caller `computeContentSha256 = false` setzt (das Flag existiert seit S5b im Hook — `ImportInputResolutionHook.resolveBeforeSchema` Parameter). `ImportPreflightResolver` setzt `computeContentSha256 = (mode != CheckpointMode.Disabled)`. | Test: `--no-checkpoint`-Pfad mit Single-File-Input ruft Phase-1 ohne SHA-256 auf (verifizierbar via Hook-Capture / Counting-Spy); `make docker-test MODULES=":hexagon:application :adapters:driving:cli"` gruen. |
| **S8f** | `CHANGELOG.md` Release-Note Pre-AP8-Bruch. Closure-Doc-Move nach `done/`; Umbrella §3.4-Update. | Closure-Doc liegt in `done/`; Status-Tabelle aktualisiert; Plan-Doc-Closeout-Commit auf `feature/parquet-0.9.8`. |

---

## 3. Verifikationsbefehle

Pro Sub-Slice:

```bash
make docker-check                                                  # gesamtes Repo
make docker-test MODULES=":hexagon:ports-write"                    # S8-0
make docker-test MODULES=":adapters:driven:streaming"              # S8a
make docker-test MODULES=":hexagon:application"                    # S8b, S8c, S8d
make docker-test MODULES=":hexagon:application :adapters:driving:cli"   # S8e
```

Vor S8f: `make integration INTEGRATION_TASKS="-PintegrationTests :test:e2e-cli:test"`
gegen die Bundle-/Single-File-Roundtrip-Familie aus S7e — verifiziert,
dass der Resume-Pfad real funktioniert (ein Lauf mit `--resume <ckpt>`
nach einer simulierten Abbrueche-Iteration).

---

## 4. Risiken / Carve-Outs

### 4.1 Pre-AP8-Kompatibilitaet

Pre-0.9.8-Checkpoints fuer JSON/YAML/CSV-Importe **muessen** weiter
funktionieren — der `operationSpecific = null`-Branch in
`validateManifest` darf nur dann `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT`
werfen, wenn der **aktuelle Lauf** Parquet-Bundle/Single-File ist.
Test (`ImportCheckpointManagerPreAp8Test`):

- Pre-AP8-Manifest + JSON-Lauf → OK (Resume-Familie wie bisher).
- Pre-AP8-Manifest + Parquet-Bundle-Lauf →
  `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT`.

### 4.2 Detekt LongMethod

`FileCheckpointStore.toMap`/`fromMap` waechst um ca. 20-30 Zeilen pro
Methode. Falls Detekt-Limit (100) reisst, Subtyp-Persistenz in
`private fun toOperationSpecificMap(...)` / `fromOperationSpecificMap(...)`
auslagern. Memory `feedback_no_suppress_for_size`: nicht via `@Suppress`
loesen.

### 4.3 Sealed-Sweep-Disziplin

Sobald `CheckpointOperationSpecifics` Subtypen hat, wird der
`when`-Sweep verbindlich — alle bisherigen `?.let { ... }`-Lambdas auf
`manifest.operationSpecific` werden compile-time-warnungsfrei sein
(weil `?.let` keinen `when` zwingt). Aber der `parquet-cli-wiring.md`
§8.4-Sweep ist trotzdem Pflicht. Sub-Slice S8-0 enthaelt den Sweep-
Commit + `parquet-sealed-rg.sh`-Lauf gegen die neuen Subtypen.

### 4.4 Hash-Recompute-Kosten

Der `ImportInputResolutionHook.finalizeBeforePrepare`-Hash-Through
sieht harmlos aus, kostet aber bei Single-File-Resume einen kompletten
Datei-Scan (`ParquetSingleFilePreflight.phase1` ruft
`Sha256DigestCalculator.compute`). Heute akzeptabel — die Datei wird
ohnehin von `ParquetChunkReader` vollstaendig gelesen. Bundle-Pfad ist
nicht betroffen (per-Tabelle-Hash in `BundleCheckpointSpecifics.fingerprint`
wird in S5a/S7 erzeugt, nicht in S8).

### 4.5 Carve-Outs (was S8 NICHT macht)

- **Kein Phase-2-Schema-Fix-up** im
  `ImportInputResolutionHook.finalizeBeforePrepare`. B3 aus dem
  S6-Review bleibt offen fuer S9a/S9b (Test-Familien) bzw. Cut B.
- **Keine** Bundle-/Single-File-Test-Familien (Preflight-Codes,
  KV-Toleranz, Resume-Familie) — das ist S9a/S9b.
- **Keine** Wiring-Aenderungen am `FileCheckpointStore`-Disk-Layout
  ausser dem optionalen `operationSpecific`-Block (kein
  `CURRENT_SCHEMA_VERSION`-Bump).
- **Kein** Single-Runner-Harness fuer Resume-E2E (siehe
  `feedback_ap624_carveouts`); die S7e-Bundle-Roundtrip-Fixture
  reicht.

---

## 5. Closure-Plan

Bei S8f-Closeout:

1. Diese Doc nach
   `docs/planning/done/ImpPlan-0.9.8-parquet-S8-checkpoint-extension.md`
   verschieben (`git mv`).
2. Status-Tabelle in
   [`parquet-productive-cut-a.md`](parquet-productive-cut-a.md) §3.4
   aktualisieren: S8 → closed mit Commit-Refs S8-0..S8f.
3. `CHANGELOG.md` `[Unreleased]` um den Pre-AP8-Bruch-Eintrag
   ergaenzen.
4. Hand-off-Anker in `ImpPlan-0.9.8-parquet-S9a-bundle-tests.md`
   (bisher nicht existent) anlegen, der den Test-Familien-Block aus
   AP12 §11 fuer S9a uebernimmt — analog zu S9b-Skeleton (`6629e842`).
