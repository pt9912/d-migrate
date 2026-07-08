---
status: accepted
date: 2026-07-05
decision-makers: pt9912
consulted: docs/planning/done/postcompare-type-canonicalization-slice.md (Slice-Plan D1–D5, AP0–AP7), docs/planning/done/migrate-postcompare-identifier-pk-drift.md (v3-Präzedenz)
informed: hexagon/core (MigrationFingerprint, TableComparator), hexagon/ports-common (NeutralTypeCanonicalizer), docs/user/anwenderhandbuch.md
---

# Dialektbewusste Fingerprint-Kanonisierung im Post-Compare: Round-Trip-Projektion statt Kompatibilitäts-Prädikat, `schema-fingerprint-v6` → `v7`

> **Status: accepted (2026-07-05).** Der Post-Execute-Compare von
> `schema migrate --execute` kanonisiert Neutraltypen und Single-Column-
> UNIQUE-/FK-Äquivalenzen **dialektbewusst**, bevor er Fingerprints vergleicht.
> Die Kanonisierung ist eine **per-Dialekt-Round-Trip-Projektion** (Komposition
> der vorhandenen Vorwärts-/Rückwärts-Abbildung des Treibers), **kein**
> paarweises Kompatibilitäts-Prädikat. Sie lebt **uniform im
> Fingerprint-Vertrag** (`ALGORITHM` `schema-fingerprint-v6` → `v7`), nicht still
> nur im Post-Compare. `schema compare` und die Diff-Engine bleiben strukturell
> streng — die Toleranz ist bewusst auf den Fingerprint-/Post-Compare-Pfad
> begrenzt.

## Kontext und Problemstellung

`schema migrate --execute` führt nach dem Apply einen **Post-Execute-Compare**
aus: Es re-introspiziert das Ziel, berechnet einen Fingerprint und vergleicht
ihn gegen den erwarteten. Der Post-Compare ist ein **reiner
Fingerprint-Vergleich** (zwei Hash-Strings), kein strukturierter Modellvergleich;
eine Abweichung endet mit **Exit 5** („Post-execute compare detected drift").

Für **spec-valide** Schemata endete ein **frisches** `migrate --execute` gegen
ein leeres Ziel jedoch mit Exit 5, obwohl der Apply sauber durchlief. Ursache:
Der Ziel-Dialekt **flacht Neutraltypen ab** und der Reverse liest den
Storage-Typ zurück, während der Fingerprint Neutraltypen **wörtlich** verglich:

- **SQLite** (nur vier Storage-Klassen): `smallint`/`biginteger`/`boolean` →
  `INTEGER` → Reverse `integer`; `datetime`/`date`/`uuid`/`json`/… → `TEXT` →
  Reverse `text`; `decimal(10,2)` → `REAL` → Reverse `float`. 16 belegte Kanten.
- **PostgreSQL/MySQL**: schmalere, aber reale Kanten (`email` → `VARCHAR(254)`,
  `enum` → `TEXT`, MySQL `datetime(tz)` → `datetime`, `array(text)` → `JSON`).

Derselben Familie gehören **Single-Column-Constraints**: ein benannter
Single-Column-`UNIQUE` bzw. eine Spalten-`references` reversen als
Spaltenattribut (`unique: true` bzw. benannter FK) zurück — der `TableComparator`
faltet diese Äquivalenz bereits, der Fingerprint zog nicht nach. Netto konnten
spec-valide Schemata auf SQLite nie drift-frei frisch migriert werden — ein
Korrektheitsdefekt der `migrate --execute`-Exit-Semantik (Severity P2).

## Entscheidungstreiber

- **Der einzige Hebel ist der Fingerprint-Vertrag.** Der Post-Compare hat kein
  strukturiertes Modell zur Verfügung, nur zwei Hash-Strings.
- **Der Fingerprint wird persistiert.** Das Rollback-Artefakt trägt
  `postUpFingerprint`, den `schema rollback` mit `compute` **re-berechnet**. Eine
  Projektion nur im Post-Compare wäre ein Vertragsbruch (falscher
  `TARGET_STATE_MISMATCH` beim Rollback — exakt das von `c4846667` beseitigte
  Fehlerbild).
- **Round-Trip-Treue ist dialekt-spezifisch und schon vorhanden.** Sie steckt in
  der Vorwärts- (`TypeMapper.toSql`) und Rückwärts-Abbildung jedes Treibers —
  eine zweite Wahrheit (Abbildungstabelle) wäre Drift-anfällig.
- **Der Comparator ist der Referenzstand.** Er faltet Single-Column-UNIQUE/FK
  bereits; der Fingerprint soll auf denselben Stand nachziehen, nicht divergieren.

## Betrachtete Optionen

### Typ-Äquivalenz: Kompatibilitäts-Prädikat vs. Round-Trip-Projektion

- **Option A — vorhandenes `StructuralTransferTypeCompatibility` wiederverwenden**
  (paarweises Prädikat). **Verworfen:** keine Äquivalenzrelation (reflexiv/
  symmetrisch, aber **nicht transitiv**: `identifier` ≡ `integer` und
  `integer` ≡ `boolean`, aber `identifier` ≢ `boolean`) — als
  Kanonisierungs-Substrat untauglich; zudem sind seine Integral-/DateTime-
  Sonderregeln Transfer-Semantik (Value-Widening), die im Post-Compare **echte**
  Drift verschleiern würde.
- **Option B — per-Dialekt-Projektion** `canonicalize(t) = reverse(render(t))`,
  also Komposition der vorhandenen Vorwärts-/Rückwärts-Abbildung. **Gewählt.**
  Eigenschaften: **idempotent** (Reverse-Output ist Fixpunkt), **Identity für
  treue Dialekte** (kein Sonderfall nötig).

### Ort der Kanonisierung: nur Post-Compare vs. uniform im Fingerprint-Vertrag

- **Option A — still nur im Post-Compare projizieren.** **Verworfen:** der
  persistierte `postUpFingerprint` würde beim Rollback mit unverändertem
  `compute` re-berechnet → falscher `TARGET_STATE_MISMATCH`, den der Algo-Guard
  nicht abfängt.
- **Option B — `MigrationFingerprint.compute(schema, canonicalizer)` an allen
  Call-Sites**, `ALGORITHM` → `v7`. **Gewählt.**

### Scope: nur Fingerprint vs. auch Diff-Engine / `schema compare`

- **Nur Fingerprint-/Post-Compare-Pfad.** `schema compare` und die Diff-Engine
  bleiben strukturell streng. Durch die Fingerprint-**Freiheit** des
  Compare-Pfads ist das per Konstruktion erfüllt (Gegenprobe in der Abnahme).

## Entscheidung (D1–D3)

- **D1 — Kanonisierung als Round-Trip-Projektion, nicht als Prädikat**
  (Option B oben). Ein Treiber-Port `NeutralTypeCanonicalizer` (Default
  **Identity** — ein Treiber ohne explizite Abflachungs-Deklaration kanonisiert
  nichts weg) liefert die Faltung als Live-Komposition. Einziger
  Identity-Carve-out: `geometry` — Subtyp/SRID reisen über **Dialekt-Metadaten**
  (`AddGeometryColumn`, SRID-Attribut, PostGIS-Katalog), nicht durch den
  deklarierten Typ-String, und der Reverse rekonstruiert sie; die Komposition
  könnte das nicht transportieren (siehe [ADR-0016](0016-spatialite-metadata-bootstrap.md)).
  `fulltext` geht dagegen **durch die Komposition**: auf PG ist `tsvector` ein
  Fixpunkt, auf SQLite/MySQL degradiert die Spalte real zu TEXT und der Reverse
  rekonstruiert nur den Volltext-**Index** — ein Identity-Carve-out hätte dort
  genau die False-Positive-Klasse dieses Defekts reproduziert (siehe
  [ADR-0015](0015-fulltext-tsvector-neutral-type.md) und
  [ADR-0025](0025-fulltext-source-columns-as-index.md)).

- **D2 — Uniform im Fingerprint-Vertrag, `schema-fingerprint-v6` → `v7`,
  dialekt-parametrisiert** (Option B oben). `MigrationFingerprint.compute` nimmt
  einen Kanonisierer (Default Identity); alle Migrate-/Rollback-Call-Sites inkl.
  `DiffPlanner.plan()` reichen den **Ziel-Dialekt**-Kanonisierer durch. Das Paar
  (Algorithmus, Dialekt) bestimmt die Fingerprint-Funktion eindeutig; das
  Rollback-Artefakt trägt den Dialekt bereits und der Rollback-Verify erzwingt
  `TARGET_DIALECT_MISMATCH` → **kein neues Artefakt-Feld nötig**.

- **D3 — Nur Post-Compare/Fingerprint; gewollte Divergenz zu `schema compare`.**
  Ein gewolltes `smallint → integer` bleibt im Compare **echter** Unterschied.
  Die bewusste Divergenz (Fingerprint dialektbewusst **tolerant** ↔ Comparator
  strukturell **streng**) ist im `MigrationFingerprint`-KDoc festgehalten.

Im **selben `v7`-Bump** mitgeführt (zwei aufeinanderfolgende Bumps wären unnötige
Artefakt-Invalidierung): der **Single-Column-UNIQUE-/FK-Fold** (spiegelt
`TableComparator.normalizeConstraints`, Grenze `columns.size == 1`) und die
**`effectiveRequired`-Kanonisierung** (PK-Spalten sind semantisch NOT NULL; der
PG-Reverse materialisiert das, der Soll-Parser nicht — Analogon zu
`effectivePrimaryKey` aus v3). Der Migrate-Diff bekam zudem einen **target-aware
Vergleichsmodus** im Comparator (Default strikt = `schema compare` unverändert),
damit ein zweiter Migrate-Lauf gegen das bereits migrierte Ziel keinen
No-Op-Rebuild mehr plant.

## Konsequenzen

- **Gut:** Ein frisches `migrate --execute` spec-valider Schemata endet mit
  **Exit 0**; der Post-Compare meldet keine False-Positives mehr auf
  verlustfreien Dialekt-Round-Trips. `text`-Kontrolle und ein zweiter Migrate-Lauf
  (0 Operationen) bleiben grün.
- **Kompatibilität — der `v6` → `v7`-Bump invalidiert bestehende
  Rollback-Artefakte und Overlay-Pins**, exakt wie jeder frühere Bump. Alte
  v6-Rollback-Artefakte lehnt der Algo-Guard **laut** ab
  (`ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`, Exit 8, „regenerate"); Overlays mit
  gepinnten v6-Fingerprints scheitern laut im Preflight
  (`OVERLAY_STALE_SOURCE_FINGERPRINT` / `OVERLAY_STALE_TARGET_FINGERPRINT`). Kein
  stiller Fehlvergleich. Anwenderseitig dokumentiert im
  [Anwenderhandbuch (Fehlerbehebung)](../user/anwenderhandbuch.md#5-fehlerbehebung).
- **`schema compare` und Generate unverändert** (D3); ein gewolltes
  `smallint → integer` bleibt Compare-Drift. Der oben beschriebene target-aware
  Modus des Comparators ist **opt-in** (Default strikt) und ändert nur den
  Migrate-Diff, nicht `schema compare`.
- **Neue Treiber** ohne explizite Abflachungs-Deklaration kanonisieren nichts
  (Default Identity — konservativ). Der Sensor je Dialekt ist der Typ-Smoke, kein
  „muss überschreiben"-Guard.
- Das Plan-Artefakt trägt jetzt ein `fingerprintAlgorithm`-Feld — die
  persistierten Fingerprint-Werte werden dadurch extern interpretierbar.

## Bestätigung

- `make docs-check` grün.
- `make sample-db-types-smoke` grün: permanenter Drift-Sensor über die Typ-/
  UNIQUE-/FK-Proben (Exit 0), `schema compare`-Gegenprobe (strikt), Plan-Konvergenz
  (zweiter Lauf 0 Operationen), Rollback-Round-Trip mit v7-Artefakt.
- Unit-/Integrationstests auf Fingerprint-/Comparator-Ebene; ein v6-Artefakt
  wird mit Exit 8 `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` abgelehnt
  (Regressionstest); Kover ≥ 90 % je berührtem Modul.

## Weitere Informationen

- Slice-Plan mit D1–D5, AP0–AP7 und den Live-Belegen (Kanten-Tabellen je
  Dialekt): [`postcompare-type-canonicalization-slice.md`](../planning/done/postcompare-type-canonicalization-slice.md).
- Präzedenz — Fingerprint-v3-Kanonisierung des impliziten `identifier`-PK:
  [`migrate-postcompare-identifier-pk-drift.md`](../planning/done/migrate-postcompare-identifier-pk-drift.md).
- Verwandte Carve-out-Rationale: [ADR-0015](0015-fulltext-tsvector-neutral-type.md),
  [ADR-0016](0016-spatialite-metadata-bootstrap.md),
  [ADR-0025](0025-fulltext-source-columns-as-index.md).
