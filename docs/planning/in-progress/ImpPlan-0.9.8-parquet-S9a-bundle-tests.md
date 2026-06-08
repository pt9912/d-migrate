# S9a — Bundle-Test-Familien (SKELETON)

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](./parquet-productive-cut-a.md)
> §3 S9a).
>
> Status: **Skeleton — startbereit fuer Plan-Ausbau** (S7/S8 sind
> geschlossen, siehe Umbrella §3.4).
>
> Diese Datei ist ein Anker fuer die Folgeaufgaben, die in S5a, S6,
> S7 und S8 explizit an S9a uebergeben wurden. Der volle
> Implementierungsplan (Sub-Slice-Schnitt, Code-Stellen, DoD-Detail,
> Test-Strategie) wird geschrieben, wenn S9a in Angriff genommen wird
> — analog dem Vorgehen bei S6 (ImpPlan-First → Review →
> Implementation).
>
> Diese Skeleton-Datei existiert ausschliesslich, damit die feinen
> Hand-off-Anker aus den vorherigen Slices nicht in deren
> Folgeaufgaben-Sektionen verloren gehen. Spiegelbild zum
> S9b-Skeleton (`6629e842`,
> [`ImpPlan-0.9.8-parquet-S9b-single-file-tests.md`](./ImpPlan-0.9.8-parquet-S9b-single-file-tests.md)).

---

## 1. Scope (Umbrella-Referenz)

Per Umbrella §3 S9a-Cell
(`parquet-productive-cut-a.md:213`):

> Bundle-Tests: CLI-Preflight (Bundle-Codes), Format-Resolver
> (`manifest.yaml`-Hook), Bundle-Resume-Familie, DuckDB-/Arrow-
> Bundle-KV-Toleranz (AP12 §11).

Vier Test-Familien, die in den vier betroffenen Modulen
(`:hexagon:application`, `:adapters:driven:streaming`,
`:adapters:driven:formats-parquet`, `:adapters:driving:cli`) gruen
werden muessen.

## 2. Hand-off-Anker aus vorherigen Slices

### Aus S5a-ImpPlan (`ImpPlan-0.9.8-parquet-S5a-bundle-preflight.md`)

- **CLI-Preflight-Codes** fuer Bundle: die `MANIFEST_*`-Fehlerklassen
  aus `ParquetBundlePreflight` (`ParquetBundlePreflightException`)
  bekommen einen CLI-Test, der den AP12-Exit-Code **4** + die exakte
  stderr-Message verifiziert:
  `MANIFEST_NOT_FOUND`, `MANIFEST_PARSE_ERROR`,
  `MANIFEST_VERSION_INCOMPATIBLE`, `MANIFEST_FIELD_MISSING`,
  `MANIFEST_FIELD_INVALID`, `MANIFEST_TABLE_DUPLICATE`,
  `MANIFEST_FILE_DUPLICATE`, `MANIFEST_FILE_MISSING`,
  `MANIFEST_FILE_OUTSIDE_BUNDLE`, `MANIFEST_FILE_UNREFERENCED`,
  `MANIFEST_SHA256_MISMATCH`.
  Heute sind diese nur als Adapter-Tests in
  `ParquetBundleResolverTest`/`ParquetBundleClosureTest` gedeckt;
  S9a verbreitert das auf CLI-Ebene (Exit-Code + stderr). **Soll-Exit
  ist 4** — durch S9a-0.b hergestellt (`PreflightExitException`,
  `MANIFEST_*` → Exit 4); das frühere pauschale `RuntimeException` →
  Exit 3 ist abgelöst (keine offene Mini-Slice-Frage mehr).
- **Kollisionsschutz K1–K5** (AP7 §6.2): jeder Branch in
  `runCollisionChecks` braucht einen produktiven Negativ-Test.
- **Bundle-spezifische `BUNDLE_*`-Codes** aus AP8/AP12 duerfen nicht
  hinter `MANIFEST_*` verschwinden. **S9a-0 hat den Vertrag bereits
  hergestellt** (`parquet-directory-import.md` §5.2/§7.3/§8.4 +
  `parquet-cli-wiring.md` §9) — für S9a bleibt **nur der CLI-Test
  gegen die existierenden Codes**, keine Entscheidung mehr offen.
  Soll-Exits in Klammern:
  - Bundle-Resolver/Iteration → **Exit 5**:
    `BUNDLE_FILTER_UNKNOWN_TABLE`, `BUNDLE_ORDER_DUPLICATE`,
    `BUNDLE_ORDER_UNKNOWN_TABLE`, `BUNDLE_ORDER_INCOMPLETE`
    (S9a-0.c); `BUNDLE_TABLE_IMPORT_FAILED` (S9a-0.d).
  - Bundle-Resume → **Exit 3**:
    `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`,
    `BUNDLE_FORMAT_VERSION_INCOMPATIBLE_WITH_CHECKPOINT`,
    `BUNDLE_TABLE_ORDER_CHANGED` (S9a-0.f);
    `BUNDLE_RESUME_REQUIRES_FILE_HASHES`,
    `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` (S8c).
  - `BUNDLE_SCHEMA_UNRESOLVED` — **N/A** (kein erreichbarer Pfad,
    s. §5 / S9a-0 §4.2); **kein** Test, Folge-Scope.

### Aus S6-ImpPlan §5 (`ImpPlan-0.9.8-parquet-S6-cli-wiring.md`)

- **Format-Resolver / `manifest.yaml`-Hook**: Directory-Sniff
  (Bundle vs. Non-Bundle vs. Mixed-Directory), `--format parquet`
  auf ein Verzeichnis mit/ohne `manifest.yaml`. Heute durch
  Helper-Tests abgedeckt; S9a hebt das auf CLI-Ebene.
- **tableFilter/tableOrder** auf Bundle-Ebene durch die CLI
  (heute nur Adapter-Test in `ParquetBundleResolverTest`).

### Aus S7-ImpPlan §7 (`ImpPlan-0.9.8-parquet-S7-end-to-end.md`)

- **Bundle-Import gegen ECHTE Parquet-Files** (via
  `ParquetChunkWriter` + `ParquetBundleClosure` provisioniert):
  `Directory → ResolvedBundle`-Pfad des Phase-1-Hooks gegen
  produktive Bytes, nicht nur gegen rekonstruierte DTOs.
- **End-to-End**: `data import --format parquet --source <dir>` gegen
  eine echte Tabelle (Bundle mit ≥2 Tabellen, tableOrder-Respekt) —
  der **kleine** Smoke landet schon im S7-E2E
  (`DataParquetRoundTripE2EPostgresTest`); S9a verbreitert das auf
  alle Edge-Cases.
- **DuckDB-/Arrow-Bundle-KV-Toleranz**: Bundle-Exports schreiben
  bewusst kein `d-migrate.manifest`-Footer-KV pro Parquet-Datei
  (Bundle-Manifest.yaml ist die Quelle). S9a braucht trotzdem
  produktive `:adapters:driven:formats-parquet`-Smokes, die ein
  echtes Bundle mit `manifest.yaml` und mehreren `.parquet`-Dateien
  erzeugen und belegen:
  - DuckDB `read_parquet` liest die Bundle-Dateien trotz daneben
    liegendem `manifest.yaml` normal.
  - Arrow-Inspektion/`SchemaConverter` akzeptiert die produktiven
    Bundle-Parquet-Dateien ohne d-migrate-Footer-KV.
  - Es gibt einen Kontrast zur Single-File-KV-Toleranz aus S9b:
    Bundle prueft Dateilesbarkeit + Manifest-Nebenlaeufigkeit,
    Single-File prueft zusaetzlich den tolerierten Custom-Footer-Key.

### Aus S8 (`ImpPlan-0.9.8-parquet-S8-checkpoint-extension.md`)

- **Volle Bundle-Resume-Familie** auf Basis des in S8 fertigen
  Specifics-Plumbings:
  - `BundleCheckpointSpecifics(fingerprint: BundleResumeFingerprint)`
    persistiert/round-trippt (S8a) und wird vom
    `ImportCheckpointManager.validateBundleResume` (S8c) geprueft.
  - Resume-Exit-Codes produktiv testen:
    `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` (Pre-AP8-Manifest
    × Parquet-Bundle-Lauf) und der Per-Tabelle-SHA-Pflicht-Pfad
    (`BUNDLE_RESUME_REQUIRES_FILE_HASHES`, fehlender Hash im
    Manifest → Exit 3).
  - `--no-checkpoint` × Bundle: kein Per-Tabelle-SHA-Vergleich
    (S8e hat den Adapter-Pfad `verifyContentSha256 = false` schon
    abgedeckt; S9a hebt das auf CLI-/Resume-Ebene).

## 3. Bewusst NICHT in S9a

- **Keine Single-File-Tests** — gehoert zu S9b.
- **Kein Production-Code** — S9a ist eine reine Test-Slice; sollte
  die Implementierung ein Defizit aufdecken, faellt der Fix in
  einen Review-Batch oder Mini-Slice und nicht in S9a selbst.
- **Keine Cross-Driver-E2E** ueber das hinaus, was S7 bereits via
  PG/Testcontainers liefert.

## 4. Definition of Done (skeleton)

Umbrella-DoD ist die Quelle:

> Vier Test-Familien gruen via `make docker-test MODULES=":hexagon:application :adapters:driven:streaming :adapters:driven:formats-parquet :adapters:driving:cli"`
> (Preflight/Resume in `hexagon:application`, Resolver in
> `streaming`, KV-Toleranz in `formats-parquet`, CLI-Codes in
> `cli`).

**Achtung „Resolver" ist überladen** (vor dem Ausbau klären, sonst
landet ein Test im falschen Modul): die Umbrella-DoD-Zeile „Resolver
in `streaming`" meint den **Format-Resolver / `manifest.yaml`-Directory-
Sniff** (`DataImportHelpers`/`ImportInputResolver`, §2 S6-Anker). Die
**Bundle-Resolver-Familie** (`BUNDLE_FILTER_*`/`BUNDLE_ORDER_*`) wirft
dagegen in `:adapters:driven:formats-parquet`
(`ParquetBundlePreflight.applyFilterAndOrder`, verifiziert) und wird
dort getestet (`ParquetBundleResolverTest`), **nicht** in `streaming`.
Der volle Plan macht die DoD-Modul-Zuordnung **pro Code + Test-Datei**
explizit (Tabelle), damit kein Resolver-Familien-Test fälschlich in
`streaming` wandert:

| Familie | Modul | Test-Datei (Richtung) |
| --- | --- | --- |
| CLI-Preflight `MANIFEST_*` → 4 | `:adapters:driving:cli` | Runner-/CLI-Exit-Code-Test |
| Bundle-Resolver `BUNDLE_FILTER_*`/`BUNDLE_ORDER_*` → 5 | `:adapters:driven:formats-parquet` (+ CLI-Hook → `:cli`) | `ParquetBundleResolverTest` / Hook-Test |
| Format-Sniff (`manifest.yaml`) | `:hexagon:application` (`DataImportHelpers`) / `:adapters:driven:streaming` (Directory-Resolver) | Helper-/Resolver-Test |
| Bundle-Resume → 3 | `:hexagon:application` (Manager) + `:cli` (E2E-Flow) | `ImportCheckpointManagerOperationSpecificsTest` + CLI |
| KV-Toleranz | `:adapters:driven:formats-parquet` | DuckDB-/Arrow-Bundle-Smoke |

Konkrete Belegbefehle, DoD-Cases und die finale Test-Datei-Liste
werden beim vollen Ausbau ergaenzt.

## 5. Vorbedingungen

- ✅ **S7 abgeschlossen** (2026-06-08, siehe Umbrella §3.4):
  Seekable-Dispatch produktiv, Bundle-Manifest wird produktiv
  geschrieben.
- ✅ **S8 abgeschlossen** (S8f-Closeout, siehe Umbrella §3.4):
  `BundleCheckpointSpecifics` persistiert + round-trippt (S8a),
  `validateBundleResume` aktiv (S8c), `--no-checkpoint`-Adapter-Pfad
  verifiziert (S8e). Die Bundle-Resume-Familie ist damit nicht mehr
  rein synthetisch.
- ✅ **S9a-0 abgeschlossen** (2026-06-09, eigener Produktiv-Vor-Slice,
  [`ImpPlan-0.9.8-parquet-S9a-0-exit-code-contract.md`](../done/ImpPlan-0.9.8-parquet-S9a-0-exit-code-contract.md)):
  der AP12-§9-Exit-Code-Vertrag ist hergestellt — `MANIFEST_*` → Exit 4,
  Bundle-Resolver-Familie (`BUNDLE_FILTER_UNKNOWN_TABLE`,
  `BUNDLE_ORDER_DUPLICATE/UNKNOWN_TABLE/INCOMPLETE`) → Exit 5,
  `BUNDLE_TABLE_IMPORT_FAILED` benannt, Resume-Codes → Exit 3.
  **Damit testet S9a gegen den korrigierten Vertrag** — die früheren
  IST-Warnungen in §2 („falls der Runner pauschal Exit 3 mappt …") sind
  durch S9a-0 aufgelöst. Auch die drei Resume-Bruch-Codes
  `BUNDLE_FORMAT_VERSION_INCOMPATIBLE_WITH_CHECKPOINT`,
  `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`, `BUNDLE_TABLE_ORDER_CHANGED`
  sind jetzt benannt (S9a-0.f-Addendum, feldweiser Split in
  `validateBundleResume`, Exit 3) → S9a Familie 3 testet gegen diese
  Codes. `BUNDLE_SCHEMA_UNRESOLVED` bleibt bewusst N/A (S9a-0 §4.2).

## 6. Naechste Schritte (bei Slice-Start)

1. Diese Skeleton-Datei zum vollen ImpPlan ausbauen (Sub-Slice-
   Schnitt, Test-Datei-Liste, DoD-Detail-Tabelle, Folgeaufgaben).
2. Plan-Review-Zyklus analog S6/S7 (mehrere Runden gegen
   Code-Realitaet).
3. Implementierungs-Sub-Slices unter `fix(review)`/`feat(parquet)`-
   Commit-Konvention.
4. Plan-Doc nach `docs/planning/done/` migrieren + Umbrella §3.4-
   Status-Tabelle aktualisieren.
