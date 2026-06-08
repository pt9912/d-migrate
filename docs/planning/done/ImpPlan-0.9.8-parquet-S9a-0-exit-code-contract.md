# S9a-0 — AP12-§9-Exit-Code-Vertrag (Produktiv-Vor-Slice zu S9a)

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](../in-progress/parquet-productive-cut-a.md)
> §3 S9a).
>
> Status: **Closed (2026-06-09)** — S9a-0.a/b/c/d/e durch, direkt auf
> `develop`. Commits: `7808968c` (a) + `9af34212` (b) + `c9c0e989` (c)
> + `c66ba012` (d) + S9a-0.e-Closeout. Doc nach `done/` migriert.
>
> **Anlass:** S9a-Scoping-Recherche (2026-06-09) hat aufgedeckt, dass
> der in [`parquet-cli-wiring.md`](parquet-cli-wiring.md) §9
> (AP12) **bindend** vorgeschlagene Exit-Code-Vertrag im Produktivcode
> **nicht** umgesetzt ist. S9a ist eine reine Test-Slice und darf
> keine Tests schreiben, die das nicht-konforme IST-Verhalten
> zementieren. Darum dieser vorgeschaltete **Produktiv**-Slice
> (User-Entscheid pt9912, 2026-06-09): erst Code auf AP12 §9 bringen,
> dann S9a-Tests gegen den korrigierten Vertrag.

---

## 1. Befund — AP12 §9 Soll vs. Code-IST (verifiziert 2026-06-09)

AP12-§9-Tabelle (`parquet-cli-wiring.md:668`):

| Fehlerfamilie | AP12 §9 Soll-Exit | Code-IST | Konform? |
| --- | --- | --- | --- |
| `MANIFEST_*` (11 Codes, AP7 §9.2) | **4** | `ParquetBundlePreflightException` → generischer `RuntimeException`-Catch in `ImportPreflightResolver.kt:91-93` → **Exit 3** | ❌ |
| Bundle-Resolver/Iteration `BUNDLE_FILTER_UNKNOWN_TABLE`, `BUNDLE_ORDER_DUPLICATE`, `BUNDLE_ORDER_UNKNOWN_TABLE`, `BUNDLE_ORDER_INCOMPLETE`, `BUNDLE_SCHEMA_UNRESOLVED`, `BUNDLE_TABLE_IMPORT_FAILED` (AP8 §5.2/§7.3, wie im bindenden `parquet-cli-wiring.md:669`; `BUNDLE_SCHEMA_UNRESOLVED` zusätzlich AP8 §6.2, s. §4.2) | **5** | Codes **existieren nicht**; `tableFilter`/`tableOrder`-Fehler werfen heute `IllegalArgumentException("MANIFEST_FILE_MISSING: …")` (`ParquetBundlePreflight.kt:188-200`) → generischer IAE-Catch → **Exit 2** | ❌ |
| Bundle-Resume `BUNDLE_RESUME_REQUIRES_FILE_HASHES`, `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`, `BUNDLE_FORMAT_VERSION_INCOMPATIBLE_WITH_CHECKPOINT`, `BUNDLE_TABLE_ORDER_CHANGED`, `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` (AP8 §8.4) | **3** | `validateBundleResume`/`validatePreAp8Compat` (`ImportCheckpointManager.kt:160-241`) → **Exit 3** | ✅ |
| Reader-Konsistenz `BUNDLE_SCHEMA_PARQUET_MISMATCH` (AP10 §3.3) | **4** | `ParquetSchemaMismatchException` (`ParquetChunkReader.kt:110`) → tritt im **Streaming** auf → **Exit 5** | ⚠️ (siehe §4.3) |

**Scope dieses Slices:** die ersten beiden Zeilen (MANIFEST_* → 4,
Bundle-Resolver-Familie → 5). Resume (Zeile 3) ist bereits konform.
Single-File-Codes (`PARQUET_SINGLE_FILE_*` → 4) haben dieselbe Lücke,
gehören aber zu **S9b-0** (Spiegel-Slice) — **nicht** hier, um den
Bundle-/Single-File-Schnitt sauber zu halten.

---

## 2. Architektur-Kern: Modulgrenze (Hexagon)

`ImportPreflightResolver` lebt in `:hexagon:application` (Core).
`ParquetBundlePreflightException` lebt in
`:adapters:driven:formats-parquet`. **Der Core darf den Adapter-Typ
nicht kennen** (Hexagon-Regel; vgl. Memo
[[hexagon-dialect-context]]). Heute propagiert die Parquet-Exception
durch den `ImportInputResolutionHook`-Port hoch und wird im Resolver
nur als generische `RuntimeException` → Exit 3 gefangen.

**Lösung (port-seitige Übersetzung, analog ADR-0006 Wiring-Drift-
Exception-Familie):**

1. Neue **Port-Exception** in `:hexagon:application` (oder
   `:hexagon:ports-*`), die einen **expliziten Exit-Code** trägt:
   ```kotlin
   class PreflightExitException(
       val exitCode: Int,          // 4 oder 5
       message: String,            // beginnt mit stabilem Code (MANIFEST_* / BUNDLE_*)
       cause: Throwable? = null,
   ) : RuntimeException(message, cause)
   ```
   (Name/Platzierung in Review final; ggf. sealed Familie statt
   Int-Feld, falls die Exit-Code-Menge klein und fix bleibt.)
2. Der **CLI-Hook** `ParquetImportInputResolutionHook`
   (`:adapters:driving:cli` — sieht beide Welten) fängt
   `ParquetBundlePreflightException` (→ `exitCode = 4`) und die neue
   Resolver-Exception (→ `exitCode = 5`) und übersetzt sie in
   `PreflightExitException`. Der Core bleibt parquet-frei.
3. `ImportPreflightResolver.resolve` bekommt einen **neuen Catch**
   für `PreflightExitException` **vor** dem generischen
   `RuntimeException`-Catch und gibt `Exit(e.exitCode)` zurück.
   Reihenfolge: `OperationCancelledException` → `PreflightExitException`
   → `IllegalArgumentException` → `RuntimeException`.

So bleibt das Exit-Code-Wissen im Adapter (wo die Codes definiert
sind), und der Core mappt nur ein generisches, exit-code-tragendes
Port-Signal.

---

## 3. Sub-Slice-Plan

| Sub-Slice | Inhalt | DoD |
| --- | --- | --- |
| **S9a-0.a** | Port-Exception `PreflightExitException` (exit-code-tragend) in `:hexagon:application`; `ImportPreflightResolver`-Catch davor verdrahtet (Reihenfolge s. §2.3). **Noch kein** Parquet-Mapping — nur das Gerüst + Unit-Test, dass `Exit(code)` durchgereicht wird (Fake-Hook wirft `PreflightExitException(4/5)`). | `make docker-test MODULES=":hexagon:application"` grün; Resolver mappt 4 und 5 korrekt. |
| **S9a-0.b** | `MANIFEST_* → Exit 4`: CLI-Hook übersetzt `ParquetBundlePreflightException` → `PreflightExitException(4, …)`. Message-Wortlaut/Code-Präfix erhalten. | `make docker-test MODULES=":adapters:driving:cli"` grün; ein `MANIFEST_NOT_FOUND` durch die CLI ergibt Exit 4 (nicht 3). |
| **S9a-0.c** | **Bundle-Resolver-Familie → Exit 5**: neue Exception in `:adapters:driven:formats-parquet`. Throw-Site ist **`ParquetBundlePreflight.applyFilterAndOrder`** (`ParquetBundlePreflight.kt:180-205`), nicht der dünne `ParquetBundleResolver`-Wrapper (`ParquetBundleAdapter.kt:50`, delegiert nur). Der Exception-Name folgt der Throw-Klasse (`ParquetBundlePreflightResolverException` o.ä.) — `…ResolverException` wäre irreführend, da der Resolver-Wrapper nicht wirft. Sie wirft die korrekten Codes (`BUNDLE_FILTER_UNKNOWN_TABLE`, `BUNDLE_ORDER_DUPLICATE`, `BUNDLE_ORDER_UNKNOWN_TABLE`, `BUNDLE_ORDER_INCOMPLETE`) statt `IllegalArgumentException("MANIFEST_FILE_MISSING…")`. CLI-Hook übersetzt → `PreflightExitException(5, …)`. | Adapter-Tests in `ParquetBundleResolverTest` auf die neuen Codes umgestellt/ergänzt; `make docker-check` grün. |
| **S9a-0.d** *(umgesetzt)* | **Entschieden (§4.2):** `BUNDLE_SCHEMA_UNRESOLVED` = ehrliches N/A (kein erreichbarer Pfad; fehlendes Schema = `MANIFEST_*`-Vertragsbruch; Folge-Scope falls echter SchemaReader-Pfad kommt). `BUNDLE_TABLE_IMPORT_FAILED` = umgesetzt: `assessCompletion(isParquetBundle)` gibt für Bundle-Läufe `BUNDLE_TABLE_IMPORT_FAILED: table='…' cause='…'` aus (`error` + `failedFinish`), Exit 5 unverändert, generischer Pfad unberührt. | Entscheidung je Code dokumentiert; 2 neue `assessCompletion`-Tests (Bundle per-table + failed-finish); `make docker-test MODULES=":hexagon:application"` grün; `make docker-check` grün. |
| **S9a-0.e** | KDoc-`DataImportRunner` Exit-Code-Doku auf die erweiterte Bedeutung bringen (4 = Connection **oder** Parquet-Format-Vertragsbruch; 5 = Streaming **oder** Bundle-Resolver/Iteration). CHANGELOG-Notiz (`### Changed`: Bundle-Preflight-Exit-Codes nach AP12 §9). Closure-Doc-Move + Umbrella-Update. | KDoc + CHANGELOG aktualisiert; Doc nach `done/`; Umbrella §3.4 um S9a-0-Zeile ergänzt. |

---

## 4. Offene Review-Fragen (vor Implementierung zu klären)

### 4.1 Exit-Code-Kollision — bewusst akzeptiert?
Exit 4 bedeutet nach diesem Slice **Connection-Fehler ODER
Parquet-Format-Vertragsbruch**; Exit 5 **Streaming-Fehler ODER
Bundle-Resolver-Fehler**. AP12 §9 hat das so vorgeschlagen
(„Format-Vertragsbruch"-Familie = 4). Für Operator-Skripte ist der
Exit-Code damit nicht mehr eindeutig — die Unterscheidung läuft über
den **stderr-Code-Präfix** (`MANIFEST_*` vs. Connection-Text).
**Review-Entscheid nötig:** akzeptieren (AP12-treu) oder doch ein
eigener Exit-Code für Format-Preflight? *Default-Empfehlung:*
AP12 §9 folgen (Kollision akzeptieren), weil der Vertrag bindend war
und eine neue Exit-Code-Nummer den `job-contract` ändern würde.
**Achtung:** Ein neuer Code dürfte **nicht** `6` sein — der ist im
`job-contract` (`spec/job-contract.md:213`) bereits als
*AI provider error* (REST 503 / gRPC UNAVAILABLE) belegt. Aktuell
sind `0–7` + `130` alle vergeben; eine eigene Format-Preflight-Nummer
wäre frühestens `8` und müsste die §8.1/§8.2-Mapping-Tabellen
erweitern. Das spricht zusätzlich für die Kollisions-Akzeptanz.

### 4.2 Throw-Sites für `BUNDLE_SCHEMA_UNRESOLVED` / `BUNDLE_TABLE_IMPORT_FAILED`
**Entschieden (User pt9912, 2026-06-09):**

- **`BUNDLE_SCHEMA_UNRESOLVED` → ehrliches N/A** (kein Code). Der
  produktive Bundle-Pfad bindet `neutralType` aus dem Manifest;
  `schemaSource` ist immer ein gültiger Enum (`ManifestReader`
  validiert, `ParquetBundlePreflight.kt:95-99`). Fehlt das Schema, ist
  das heute ein `MANIFEST_*`-Vertragsbruch (Exit 4), **kein**
  gescheiterter Resolver — es gibt keinen dreistufigen
  SchemaReader/JDBC-Hint/Fallback im Laufzeitpfad. Einen defensiven
  Throw anzulegen wäre toter Code (gegen Memo
  [[no-carveouts]] Regel 3: nur *echtes* dauerhaftes N/A, kein
  „kommt später"-Platzhalter). **Folge-Scope:** falls je ein echter
  Ableitungspfad gebaut wird, taucht `BUNDLE_SCHEMA_UNRESOLVED` *dort*
  auf — ehrlich als neuer Scope, nicht jetzt verdeckt.
- **`BUNDLE_TABLE_IMPORT_FAILED` → umgesetzt** (S9a-0.d). Realer,
  erreichbarer Pfad (Tabelle scheitert in Reader/Writer/Commit/Finish).
  Exit 5 war bereits korrekt; gefehlt hat die **benannte Diagnose**.
  `ImportCompletionSupport.assessCompletion` bekommt ein
  `isParquetBundle`-Flag (aus `DataImportRunner`,
  `preparedImport.input is ImportInput.ResolvedBundle`) und gibt für
  Bundle-Läufe `BUNDLE_TABLE_IMPORT_FAILED: table='…' cause='…'` aus
  (Per-Tabelle-`error` **und** `failedFinish`). Generischer
  JSON/YAML/CSV/Single-File-Pfad unverändert. Kein Modul-Coupling
  (nur Boolean), keine Streaming-Reader-Änderung.

### 4.3 `BUNDLE_SCHEMA_PARQUET_MISMATCH` (AP10, Soll Exit 4)
Tritt im **Streaming** auf (`ParquetChunkReader`), nicht im Preflight
→ heute Exit 5. AP12 §9 will Exit 4. Das über die Modulgrenze (Reader
ist tief im Streaming) auf Exit 4 zu heben ist deutlich invasiver.
**Vorschlag:** außerhalb S9a-0 (eigener Mini-Slice oder bewusster
dokumentierter Carve-Out), da es nicht zur Resolver-Preflight-Familie
gehört.

### 4.4 Single-File-Spiegel (`PARQUET_SINGLE_FILE_* → Exit 4`)
Dieselbe Lücke existiert für Single-File (`ParquetSingleFile*Exception`
→ heute Exit 3). Gehört in **S9b-0** (Spiegel-Slice vor S9b), nutzt
die in S9a-0.a eingeführte `PreflightExitException`.
**Wichtig — zweite Catch-Site:** S9a-0.a verdrahtet den
`PreflightExitException`-Catch **nur** in `ImportPreflightResolver`
(Bundle-Pfad via `resolveBeforeSchema`). Der Single-File-Phase-2-Hook
`finalizeBeforePrepare` läuft aber in einer **zweiten**,
strukturgleichen Catch-Site (`DataImportRunner.kt:214-227`, heute
`IllegalArgumentException → 2` / `RuntimeException → 3`). S9b-0 muss
diese zweite Stelle analog erweitern (Reihenfolge:
`OperationCancelledException` → `PreflightExitException` →
`IllegalArgumentException` → `RuntimeException`). Die
„Infrastruktur aus S9a-0.a" deckt also nur den Port-Typ + den
Resolver-Catch ab, **nicht** die Runner-Catch-Site.

---

## 5. Carve-Outs (was S9a-0 NICHT macht)

- **Keine Tests der Familien** über die Mapping-Belege hinaus — die
  volle Test-Familie ist S9a.
- **Kein** Single-File-Exit-Code-Fix (S9b-0).
- **Kein** Streaming-Reader-Exit-Code-Reroute (`BUNDLE_SCHEMA_PARQUET_MISMATCH`,
  §4.3) ohne expliziten Review-Entscheid.
- **Keine** neue Exit-Code-Nummer (job-contract bleibt bei der
  bestehenden Code-Menge `0/1/2/3/4/5/6/7` + `130`, vgl.
  `spec/job-contract.md:208-218` — `6` = AI provider error),
  außer §4.1 entscheidet anders.

---

## 6. Verifikationsbefehle

```bash
make docker-test MODULES=":hexagon:application"        # S9a-0.a
make docker-test MODULES=":adapters:driving:cli"       # S9a-0.b
make docker-check                                      # S9a-0.c/d/e (cross-modular)
```

---

## 7. Closure-Plan

1. Diese Doc nach `docs/planning/done/` migrieren (`git mv`).
2. Umbrella §3.4: S9a-0-Zeile (closed) **vor** S9a einfügen.
3. CHANGELOG `### Changed`: Bundle-Preflight-Exit-Codes nach AP12 §9.
4. S9a-Skeleton entzerren: Exit-Code-Annahmen auf „S9a-0 hat den
   Vertrag hergestellt" umstellen; die IST-Kommentar-Warnungen
   entfernen.
