# ImpPlan — `--table-order`-CLI-Flag (Directory-/Bundle-Reihenfolge-Override)

> Anlass: S9a-Befund (2026-06-09) — `BUNDLE_ORDER_*` (S9a-0.c) ist
> produktiv **toter Code**, weil kein CLI-Pfad `ImportInput.Directory.tableOrder`
> setzt (verifiziert: `resolveImportInput` setzt nur `tableFilter`; die
> Schema-FK-Sortierung läuft separat über `DataImportSchemaPreflight`).
> User-Entscheid (pt9912): **Flag bauen** statt Code entfernen, damit die
> Codes erreichbar werden + Operatoren die Reihenfolge explizit steuern.
>
> Status: **Closed (2026-06-09)** — umgesetzt in `c687b47c` (Feature +
> Tests, `make docker-check` grün). Reconciliation §4 entschieden (A).
> CHANGELOG `### Added` ergänzt; Doc nach `done/` migriert.

---

## 1. Vertrag (User-Entscheid 2026-06-09)

`--schema` ist heute überladen (Validierung **und** FK-Topo-Sortierung).
Neuer Vertrag:

- `--schema` ohne `--table-order`: heutiges Verhalten (Validierung +
  FK-Topo-Sort).
- `--table-order` ohne `--schema`: manuelle Reihenfolge, kein
  Schema-Preflight.
- `--schema` **+** `--table-order`: Schema wird validiert, FK-Topo-Sort
  **übersprungen**; die explizite Reihenfolge ist authoritative.
- **Präzedenz:** `--table-order` > Schema-Topo-Sort > Discovery-/Default.
- **Validierung:** Duplikate → Fehler; unbekannte Tabellen → Fehler; mit
  `--schema` muss `--table-order` zur ausgewählten Import-Tabellenmenge
  passen (exakte Permutation). FK-Zyklen brechen in diesem Modus **nicht**
  (Topo-Sort ist ja übersprungen); echte Schema-/Datenfehler bleiben Fehler.

## 2. Komponenten

1. **`DataImportRequest`**: `+ tableOrder: List<String>? = null`.
2. **`DataImportCommand`**: `--table-order` (multiple, analog `--tables`).
   `validateCliFlags`: nur für Directory-Source zulässig (nicht stdin/
   single-file → Exit 2); Duplikat-Check (s. §4).
3. **`resolveImportInput`** (`DataImportHelpers.kt:251`):
   `ImportInput.Directory(tableFilter = request.tables, tableOrder = request.tableOrder)`.
4. **Präzedenz in `DataImportSchemaPreflight.prepare`** — braucht das
   „explizite Reihenfolge gesetzt"-Signal:
   - **Directory**: trägt es via `input.tableOrder` →
     `if (input.tableOrder != null) input else input.copy(tableOrder = resolveTableOrder(...))`.
     Kein Signatur-Change nötig.
   - **ResolvedBundle**: trägt es **nicht** (der Hook hat `tableOrder`
     konsumiert und in `input.tables` gebacken). → `schemaPreflight`-
     Funktionstyp wird 4-är (`+ explicitTableOrder: List<String>?`);
     `resolveSchemaPreflight` reicht `request.tableOrder` durch.
     `if (explicitTableOrder != null) input else <topo-sort>`.
     **Blast-Radius:** 14 Injektionsstellen (überwiegend Test-Lambdas
     `{ _, input, _ -> … }` → `{ _, input, _, _ -> … }`), mechanisch.
5. **Bundle-Pfad erreichbar**: Da der Hook (Schritt 7) *vor* dem
   Schema-Preflight (Schritt 8) läuft und `rawInput.tableOrder` liest,
   reicht S9a-0.c's `applyFilterAndOrder` die `--table-order` durch →
   `BUNDLE_ORDER_*` wird erreichbar (= Ziel).

## 3. Tests

- CLI: `--table-order` Happy-Path (Bundle + Directory) → Reihenfolge gilt.
- CLI: `BUNDLE_ORDER_DUPLICATE/UNKNOWN_TABLE/INCOMPLETE` jetzt **end-to-end**
  erreichbar (die S9a.1-„nicht CLI-erreichbar"-Notiz wird aufgelöst).
- Präzedenz: `--schema` + `--table-order` → Schema validiert, explizite
  Reihenfolge gewinnt, kein Topo-Sort, kein Zyklus-Fehler.
- Non-Parquet-Directory: `--table-order` greift analog (streaming-Resolver).
- `--table-order` auf stdin/single-file → Exit 2.

## 4. Exit-Code-Reconciliation — ENTSCHIEDEN: (A) (User pt9912, 2026-06-09)

**Vertrag festgezogen:**
- **Exit 2** = reine CLI-Form-/Usage-Fehler: `--table-order` auf stdin/
  single-file, leerer Wert, syntaktisch kaputte Liste. `validateCliFlags`
  prüft **nur** Flag-Usage/Syntax, **nicht** semantische Tabellenordnung.
- **Exit 5** = Bundle-/Resolver-Order-Fehler: `BUNDLE_ORDER_DUPLICATE`,
  `BUNDLE_ORDER_UNKNOWN_TABLE`, `BUNDLE_ORDER_INCOMPLETE` (in
  `applyFilterAndOrder`, AP12 §9). Alle drei betreffen dieselbe
  Nutzereingabe + denselben Order-Vertrag → eine Familie, ein Exit-Code.

Begründung/Alternativen (historisch):

Der Vertrag (§1) sagt „Duplikate/unbekannt → **Exit 2**". Aber S9a-0.c hat
für Bundles `BUNDLE_ORDER_DUPLICATE`/`BUNDLE_ORDER_UNKNOWN_TABLE`/
`BUNDLE_ORDER_INCOMPLETE` → **Exit 5** implementiert (AP12 §9,
Resolver/Iteration-Familie), und der `--table-order`-Pfad läuft für Bundles
**genau durch** `applyFilterAndOrder`. Das kollidiert. Optionen:

- **(A) Exit 5 (AP12-§9-konsistent):** `--table-order`-Fehler für Bundles
  bleiben `BUNDLE_ORDER_*` → Exit 5; der „Exit 2"-Satz war generisch
  gemeint. Konsistent mit dem gerade gebauten Vertrag; keine Sonderlogik.
- **(B) Duplikate früh → Exit 2, Rest → Exit 5:** reiner CLI-Input-Fehler
  (Duplikat in `--table-order`) wird in `validateCliFlags` als Exit 2
  gefangen (kein Tabellen-Wissen nötig); unbekannt/incomplete bleiben
  `BUNDLE_ORDER_*` → Exit 5 (brauchen die aufgelöste Tabellenmenge).
  Splittet die Familie über zwei Exit-Codes.
- **(C) Alles Exit 2:** `--table-order`-Validierung komplett früh (vor den
  Format-Resolvern) → widerspricht AP12 §9 / macht `BUNDLE_ORDER_*` wieder
  tot. Verworfen.

*Empfehlung:* **(A)** — konsistent mit AP12 §9 / S9a-0; „Exit 2" gilt nur
für die *Flag-Form*-Fehler (z.B. `--table-order` auf stdin/single-file).

## 5. Closure

Eigener Slice (nicht S9a — das ist Test-only). CHANGELOG `### Added`
(`--table-order`-Flag) + die S9a.1-Notiz/§-Befund im S9a-Anker auflösen
(`BUNDLE_ORDER_*` jetzt CLI-erreichbar). Doc nach `done/` + Umbrella.
