# S8 — Checkpoint-Erweiterung (Bundle + Single-File)

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](parquet-productive-cut-a.md)
> §3 S8).
>
> Status: In-progress (2026-06-09). Sub-Slices durch:
> - `0df74427` ImpPlan v1 (Draft)
> - `8cdae234` ImpPlan v2 — Plan-Review-Befunde eingearbeitet
>   (drei parallele Multi-Angle-Reviews, zehn Drift-Korrekturen)
> - `df733244` S8-0 — `BundleCheckpointSpecifics` +
>   `SingleFileCheckpointSpecifics` Sealed-Subtypen in
>   `hexagon:ports-write/.../CheckpointManifest.kt`
>   (`BundleResumeFingerprint` wiederverwendet, kein neuer Typ)
> - `a0b07d35` S8a — `FileCheckpointStore.toMap/fromMap`
>   Persistenz from scratch + 14 Tests (Round-Trip, Pflicht-Felder,
>   Unknown-Kind, Pre-AP8-Toleranz); Detekt-LargeClass per
>   Klassen-Split (`FileCheckpointStoreOperationSpecificsTest`)
> - `3e3c1692` S8b — `InputContext` mit
>   `bundleExpectedSha256ByTable` + `singleFileContentSha256`,
>   befuellt im `ImportPreflightValidator.resolveInputContext`
>   per Sealed-when ueber `ImportInput`-Varianten
> - `d6be9cc9` S8c — `validateManifest`-Erweiterung
>   (Bundle/SingleFile/Pre-AP8-Branches), `operationSpecific`-
>   Through in `writeInitialManifest` + `saveManifest`; plus
>   `InputContext.bundleResumeFingerprint`-Nachschub fuer
>   Bundle-Specifics-Konstruktion. 12 Tests fuer alle Pfade.
>
> Offen: S8d (Hash-Through-Plumbing in `DataImportRunner`/
> `ParquetImportInputResolutionHook`), S8e (Verifikations-Slice
> fuer `--no-checkpoint` × Single-File + Bundle), S8f
> (CHANGELOG `### Breaking`-Sektion + Closure-Doc-Move).
>
> Initial-Status: Draft (2026-06-08). Verdrahtet `BundleCheckpointSpecifics`
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
   - `BundleCheckpointSpecifics(bundleKind: String, fingerprint: BundleResumeFingerprint)`
     gemaess AP9 §4.2 / §7.3. `bundleKind` literal `"parquet-bundle"`.
     **Wichtig**: `BundleResumeFingerprint` existiert bereits in
     `hexagon/ports-write/.../ImportInput.kt:145` (vom S5a-Resolver-
     Pfad gespeist); S8 verwendet diesen Typ wieder, legt **keinen**
     duplizierten `BundleFingerprint` an.
   - `SingleFileCheckpointSpecifics(bundleKind: String, contentSha256: String, table: String)`
     gemaess AP11 §6.4. `bundleKind` literal `"parquet-single-file"`.
   - Sealed-`when`-Sweep auf allen heutigen Konsumenten via
     `rg --type kotlin -n 'is CheckpointOperationSpecifics\.' .` und
     `rg --type kotlin -n 'when \(' . | grep -F 'operationSpecific'`
     ergibt **null Treffer ausserhalb der Definition** (Plan-Review
     verifiziert 2026-06-08): heute liest **kein** Code das Feld,
     auch `FileCheckpointStore.toMap`/`fromMap` schreibt/liest es
     gar nicht — kein `?.let`-Branch vorhanden. S8a baut die
     Persistenz also vollstaendig von Grund auf.

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
     im `ImportPreflightValidator.resolveInputContext` (Z. 80-132,
     letzte Konstruktion bei Z. 131) befuellt, sobald
     `preparedImport.input is ImportInput.ResolvedBundle` (AP9 §7.5).
   - Neues optionales Feld
     `singleFileContentSha256: String? = null` — analog im Validator
     befuellt aus `ImportInput.ResolvedSingleFile.contentSha256`
     (AP11 §6.4).
   - Bestehende Felder (`effectiveTables`, `inputFilesByTable`,
     `fingerprint`) bleiben unveraendert.
   - **Klarstellung**: Plan-Review hat aufgedeckt, dass die Wiring-
     Doc `parquet-cli-wiring.md` §7.3 historisch `ImportPreflightResolver`
     nennt; die echte Konstruktion findet aber im **Validator** statt
     (der Resolver liefert nur `ImportPreflightContext`, der den
     bereits-aufgeloesten `preparedImport.input` traegt, von dem der
     Validator die SHA-Werte abliest).

4. **`ImportCheckpointManager.validateManifest`-Erweiterung**
   (`hexagon:application/.../ImportCheckpointManager.kt:108`):
   - Nach den **vier** bestehenden Pruefungen (Reihenfolge laut
     Plan-Review: `operationType` Z. 109, `optionsFingerprint` Z. 113,
     `tableSlices` Z. 117, `inputFilesByTable` Z. 125-137) ein neues
     `when (manifest.operationSpecific)`-Schalt:
     - `is BundleCheckpointSpecifics` → `validateBundleResume(specifics, inputCtx)`
       (AP9 §7.5 Schritte 1-3: `bundleKind`-Gleichheit,
       `BundleResumeFingerprint`-Gleichheit, SHA-256-Pflicht pro Tabelle
       via `inputCtx.bundleExpectedSha256ByTable`).
     - `is SingleFileCheckpointSpecifics` → `validateSingleFileResume(specifics, inputCtx)`
       (AP11 §6.4: `contentSha256`-Gleichheit gegen
       `inputCtx.singleFileContentSha256`, `table`-Gleichheit).
     - `null` → Pre-AP8-Checkpoint. **OK, wenn** der aktuelle Lauf
       nicht Parquet-Bundle/SingleFile ist; sonst
       `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` (AP9). Die
       „Parquet-Lauf?"-Frage beantwortet `inputCtx.bundleExpectedSha256ByTable
       != null || inputCtx.singleFileContentSha256 != null`.
     - **Hinweis zur Pre-AP8-Erreichbarkeit**: Der Branch wird unter
       Real-Bedingungen NUR dann erreicht, wenn `optionsFingerprint`
       *gleich* bleibt — Pre-0.9.8-Checkpoints fuer
       JSON/YAML/CSV-Importe sind unproblematisch (operationSpecific
       war dort nie gesetzt, Fingerprint stimmt weiter). Pre-0.9.8-
       Parquet-Bundle-Checkpoints existieren in der Wildnis nicht
       (Parquet kam erst mit 0.9.8 live) — der Pre-AP8-Bundle-Branch
       ist defensiv, nicht produktiv-noetig.
   - `writeInitialManifest` (Z. 171) **und** `saveManifest()` (Z. 231,
     genested in `buildCallbacks` Z. 200) reichen das `operationSpecific`-
     Objekt durch jeden Schreibaufruf (AP9 §7.5-Befund-Rueckspiel:
     Fingerprint ist Lauf-Invariante, nicht Initial-Information).
     Wichtig: **beide** Schreibpfade, sonst wuerde ein Resume vor dem
     ersten Chunk-Commit faelschlich auf den Pre-AP8-Branch fallen.

5. **`ImportInputResolutionHook.finalizeBeforePrepare`-Hash-Through-
   Plumbing** (Loest den S6-TODO in `DataImportRunner.kt:194` ab):
   - `DataImportRunner.runImport` liest `inputCtx.singleFileContentSha256`
     und reicht den Wert als `resumeExpectedSha256` in den Hook —
     S6 hardcodiert hier `null`.
   - Der `ParquetImportInputResolutionHook` validiert dann tatsaechlich
     den Content-Hash (heute No-Op bei `null`-Hash). Re-Compute des
     Hash ueber `ParquetSingleFileResolver.phase1(...)` (existierender
     Aufruf, `ParquetSingleFileAdapter.kt:46`); Hook ruft den Resolver,
     nicht `ParquetSingleFilePreflight` direkt.
   - **Modulgrenze beachten**: `Sha256DigestCalculator` ist
     `internal object` in `:adapters:driven:formats-parquet`
     (`ParquetManifestWriter.kt:98`). Der Hash-Through darf
     `:hexagon:application` nicht direkt darauf zugreifen lassen — der
     vorhandene Resolver-Pfad ist die einzige zulaessige Brücke.

6. **`CheckpointMode.Disabled`-Pfad** (`--no-checkpoint`,
   `hexagon:application/.../CheckpointMode.kt:24`):
   - Plan-Review hat aufgedeckt, dass die Phase-1-Skip-Logik bereits
     SEIT S5b/S6 LIVE ist (`ImportPreflightResolver.kt:76-79`:
     `Disabled → false; Enabled.resume == null → false; Enabled.resume != null → true`;
     `ParquetSingleFilePreflight.kt:99`:
     `if (computeContentSha256) Sha256DigestCalculator.compute(path) else null`).
     Existierender Test `ImportPreflightResolverTest.kt:183` verifiziert
     bereits: „ohne --resume wird kein contentSha256 berechnet, auch
     wenn der Checkpoint-Store aktiv ist".
   - **S8 fuegt keinen neuen Skip-Code hinzu**, sondern verifiziert
     den Bundle-Spiegel-Pfad: `ParquetBundleResolver` hat ein
     `verifyContentSha256: Boolean = true`-Flag (`ParquetBundleAdapter.kt`/
     `ParquetBundlePreflight.kt`), das vom Hook via
     `verifyContentSha256 = computeContentSha256` durchgereicht wird.
     S8 stellt per Test sicher, dass `--no-checkpoint` × Bundle weder
     den Per-Tabelle-SHA-Vergleich noch einen Re-Compute triggert.

7. **Pre-AP8-Bruch-Release-Note** im `CHANGELOG.md`:
   - CHANGELOG hat heute `[Unreleased]` mit `### Changed` und
     `### Added`, aber **keine** `### Breaking`/`### Removed`/
     `### Deprecated`-Sektion. S8f legt eine neue `### Breaking`-
     Subsektion an (Keep-a-Changelog-Konvention) mit dem Eintrag:
     „Pre-0.9.8-Parquet-Checkpoints fuer Bundle-Importe sind nach
     0.9.8 nicht mehr wiederaufnehmbar (Code
     `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT`).
     JSON/YAML/CSV-Checkpoints und Single-File-Importe ohne
     vorherigen Checkpoint sind nicht betroffen. Pre-0.9.8-Parquet-
     Bundle-Checkpoints existierten in der Wildnis nicht (Parquet kam
     mit 0.9.8 live); der Bruch ist defensiv im Code, nicht praktisch
     spuerbar."

---

## 2. Sub-Slice-Plan

Reihenfolge ist sequenziell — jede Sub-Slice schliesst mit `make docker-check`
gruen ab, weil der Sealed-Sweep aus S8-0 sonst halbtransparent in den
Folgeslices weiterlebt.

| Sub-Slice | Inhalt | DoD |
| --------- | ------ | --- |
| **S8-0** | `CheckpointOperationSpecifics`-Sealed-Subtypen in `hexagon:ports-write/.../CheckpointManifest.kt`. `BundleCheckpointSpecifics(bundleKind, fingerprint: BundleResumeFingerprint)` und `SingleFileCheckpointSpecifics(bundleKind, contentSha256, table)`. **Kein** neuer `BundleFingerprint`-Typ — `BundleResumeFingerprint` aus `ImportInput.kt:145` wird wiederverwendet. Sealed-`when`-Sweep mit `rg`-Aufruf dokumentiert (Sweep-Zaehl leer; siehe §1.1). | Klassen existieren; `make docker-check` gruen; bestehende Tests gruen ohne Aenderung (kein heutiger Konsument). |
| **S8a** | `FileCheckpointStore.toMap`/`fromMap` mit `kind`-Diskriminator + Unknown-Kind-Fail-fast. **Persistenz from scratch** — heute steht `operationSpecific` weder in toMap noch in fromMap. Round-Trip-Test fuer beide Subtypen + Negative-Test fuer unbekanntes `kind`. | `make docker-test MODULES=":adapters:driven:streaming"` gruen; Round-Trip-Test fuer `BundleCheckpointSpecifics` und `SingleFileCheckpointSpecifics`; YAML-Bytes deterministisch (Schluessel-Reihenfolge `kind` zuerst). |
| **S8b** | `InputContext`-Erweiterung um `bundleExpectedSha256ByTable` + `singleFileContentSha256` (beide mit `null`-Default). Befuellung in **`ImportPreflightValidator.resolveInputContext`** (Z. 80-132, nicht im Resolver — siehe §1.3 Klarstellung). Wert kommt aus `preparedImport.input` (`ResolvedBundle.tables[i].expectedSha256` bzw. `ResolvedSingleFile.contentSha256`). | `ImportPreflightValidatorTest` deckt beide neue Felder ab; bestehende `InputContext(...)`-Test-Konstruktoren bleiben kompatibel (default-Parameter); `make docker-test MODULES=":hexagon:application"` gruen. |
| **S8c** | `ImportCheckpointManager.validateManifest` mit `validateBundleResume` + `validateSingleFileResume` + Pre-AP8-Branch. Einfuegung **nach** `inputFilesByTable`-Check (Z. 137), nicht davor — Diagnostik-Reihenfolge: input-File-Mismatch hat Vorrang. `writeInitialManifest` (Z. 171) **und** `saveManifest()` (Z. 231) reichen `operationSpecific` durch (beide Schreibpfade, sonst Early-Resume-Bug). | Manager-Tests decken (a) Bundle-OK, (b) Bundle-Pre-AP8-Bruch mit aktuellem Parquet-Bundle-Lauf, (c) Pre-AP8 + JSON/YAML/CSV-Lauf → OK-Pfad, (d) SingleFile-OK, (e) SingleFile-Hash-Mismatch; `make docker-test MODULES=":hexagon:application"` gruen. |
| **S8d** | `ImportInputResolutionHook.finalizeBeforePrepare`-Hash-Through. `DataImportRunner.runImport` liest `inputCtx.singleFileContentSha256` und reicht ihn an den Hook (heute `DataImportRunner.kt:194` hardcodiert `null`). `ParquetImportInputResolutionHook` validiert den Hash ueber den **bestehenden** `ParquetSingleFileResolver`-Pfad (`ParquetSingleFileAdapter.kt:46`), **nicht** durch direkten `Sha256DigestCalculator`-Aufruf (cross-module `internal`-Sichtbarkeit). | Runner-Tests: Hash-Match-Pfad gruen; Hash-Mismatch-Pfad wirft mit AP11-§6.4-Code; `make docker-check` gruen. |
| **S8e** | **Verifikations-Slice** (kein neuer Production-Code): Die `--no-checkpoint`-Skip-Logik fuer Single-File-Phase-1 existiert seit S5b/S6 (`ImportPreflightResolver.kt:76-79` + `ParquetSingleFilePreflight.kt:99`). S8e fuegt **Tests** hinzu: (1) Single-File-`--no-checkpoint`-Pfad ruft Phase-1 ohne SHA-256 (Counting-Spy); (2) Bundle-`--no-checkpoint`-Pfad: `ParquetBundleResolver` mit `verifyContentSha256 = false` triggert weder Per-Tabelle-SHA-Vergleich noch Re-Compute. | `make docker-test MODULES=":hexagon:application :adapters:driven:formats-parquet :adapters:driving:cli"` gruen; beide neuen Tests deckt AP12 §7.1 vollstaendig ab. |
| **S8f** | `CHANGELOG.md`-Eintrag in **neuer** `### Breaking`-Subsektion unter `[Unreleased]` (Plan-Review-Befund: bisher nur `### Changed` + `### Added` vorhanden). Closure-Doc-Move nach `done/`; Umbrella §3.4-Update. | Closure-Doc liegt in `done/`; Status-Tabelle aktualisiert; Plan-Doc-Closeout-Commit auf `develop` (laut User-Entscheid 2026-06-08: S8 laeuft direkt auf develop). |

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

**Interaktion mit `optionsFingerprint`-Check** (Z. 113): In der Praxis
wird Pre-AP8 × Parquet-Bundle sehr wahrscheinlich VORHER schon am
`optionsFingerprint`-Mismatch scheitern — `ImportOptionsFingerprint.compute`
verarbeitet die neuen Felder von `InputContext` (Bundle-SHA-Map,
Single-File-Content-Hash) typischerweise als Teil des Fingerprints.
Der Pre-AP8-`null`-Branch ist deshalb defensiv (Code-Sicherheit), nicht
funktional erreichbar fuer reale Pre-0.9.8-Parquet-Checkpoints. S8c-
Test fuer den Bundle-Pre-AP8-Branch muss den `optionsFingerprint`
deshalb explizit gleichhalten (z.B. via Mock-Validator oder durch
Fingerprint-Hash-Fixture), sonst landet er im falschen Exit-Code-Pfad.

### 4.2 Detekt LongMethod

`FileCheckpointStore.toMap`/`fromMap` waechst um ca. 20-30 Zeilen pro
Methode. Falls Detekt-Limit (100) reisst, Subtyp-Persistenz in
`private fun toOperationSpecificMap(...)` / `fromOperationSpecificMap(...)`
auslagern. Memory `feedback_no_suppress_for_size`: nicht via `@Suppress`
loesen.

### 4.3 Sealed-Sweep-Disziplin

`rg`-Sweep auf `manifest.operationSpecific` und
`is CheckpointOperationSpecifics.*` ergibt heute **null Treffer**
ausserhalb der Definition (Plan-Review 2026-06-08): es gibt keine
existierenden `?.let`/`when`-Konsumenten, die durch die neuen Subtypen
inkomplett wuerden. Sub-Slice S8-0 dokumentiert den Sweep-Lauf
(`parquet-sealed-rg.sh` oder direkter `rg`-Befehl) als
Plan-Doc-Closeout-Anker. Sobald S8a/S8c die ersten Konsumenten
hinzufuegen, ist der `when`-Sweep verbindlich — bei jedem neuen
`operationSpecific`-Touch (z.B. spaeterer Export-Resume-Slice) muss
der Sweep wiederholt werden.

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
