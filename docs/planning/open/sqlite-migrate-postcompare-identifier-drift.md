# SQLite `migrate --execute` Post-Compare-Drift bei **implizitem** `identifier`-PK

> Status: Vorabklärung (entdeckt 2026-06-22 beim 5d-SpatiaLite-Live-Apply)
> Trigger: SpatiaLite-`migrate --execute`-Round-Trip (VA4/5d,
>   [`spatial-harness-slice.md`](../done/spatial-harness-slice.md)).
> Bezug: (a) `spec/cli-spec.md` (Exit-Codes `migrate --execute`); (b) das neutrale
>   Modell — `identifier` ist als „Auto-Increment, **Primary Key**" definiert
>   ([`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md), Typ-Tabelle);
>   (c) der Fingerprint-Vertrag in
>   `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/MigrationFingerprint.kt`
>   (hasht `primary_key` direkt; Algorithmus-Bump bei Vertragsänderung Pflicht).
> **Nicht-spatial, pre-existing** — NICHT von der Spatial-Slice verursacht; hier nur
>   dokumentiert, weil der 5d-Smoke ihn umgehen muss.

## Befund (präziser Scope)

`schema migrate --execute` gegen ein **SQLite-Ziel** endet mit **Exit 5**
(„Post-execute compare detected drift") **genau dann, wenn das Soll-Schema kein
explizites `primary_key` trägt und den PK nur implizit über `type: identifier`
ausdrückt** — obwohl die Migration sauber durchläuft (Report `"status": "ok"`,
`execution.completed = true`, `executionError = null`).

**Abgrenzung (wichtig — sonst deckt ein späterer Test die falsche Variante ab):**

- **Explizites `primary_key` → bereits grün (Exit 0).** Belegt durch
  `test/integration-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteMigrateRoundTripIntegrationTest.kt`
  (`migrateExit shouldBe 0` + `MigrationFingerprint.compute(reverse) shouldBe
  MigrationFingerprint.compute(desired)`). Der Bug ist **NICHT** „migrate ist bei
  `identifier` generell kaputt".
- **Implizites `identifier` ohne `primary_key` → Exit 5.** Das ist der Defekt.

## Ursache

Der Post-Execute-Compare reverse-t das Ziel und vergleicht den **Fingerprint**
gegen das Soll. Der Reverse materialisiert den PK **explizit** als
`primary_key: [id]` (`adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt`,
`primaryKey = pkColumns`), während das Soll-Schema ihn nur **implizit** über
`type: identifier` trägt. `MigrationFingerprint` hasht `primary_key` als Feld direkt
(`  primary_key=...`), kanonisiert die `identifier`→`primary_key`-Äquivalenz aber
**nicht** → unterschiedlicher Fingerprint → gemeldete Drift, obwohl die Schemata
**semantisch identisch** sind (belegt: `schema generate` aus dem Reverse erzeugt
byte-identisches DDL wie aus dem Soll).

## Reproducer (vollständig, ladbar)

`name` und `version` sind Pflicht (`SchemaNodeParser`), daher minimal-vollständig:

```yaml
name: "postcompare-repro"
version: "1.0.0"
tables:
  widgets:
    columns:
      id:    { type: identifier, auto_increment: true }
      label: { type: text }
    # KEIN `primary_key:` — PK nur implizit über `identifier`. Genau das driftet.
```

```sh
d-migrate schema migrate --execute \
  --source /work/repro.yaml \
  --target "db:sqlite:///work/repro.db" \
  --report /work/repro.report.yaml
# → Prozess-Exit 5 (Post-Compare-Drift), Report aber "status": "ok".
# Gegenprobe: dieselbe Tabelle mit `primary_key: [id]` → Exit 0.
```

(Im Smoke: `examples/sample-db/scripts/smoke-spatial.sh` Abschnitt `[lite]`,
`va4-apply-schema.yaml` — ebenfalls ohne explizites `primary_key`.)

## Akzeptanz (für eine spätere, nicht-spatiale Slice)

- `migrate --execute` gegen ein SQLite-Ziel mit **implizitem** `identifier`-PK
  (ohne `primary_key`) endet mit Exit 0, wenn die Migration sauber durchläuft.
- Die `identifier`→PK-Äquivalenz wird **vor** dem Hashing kanonisiert — entweder als
  benannte Pre-Fingerprint-Normalisierung (Soll **und** Reverse leiten die implizite
  PK aus `identifier`-Spalten ab, bevor `MigrationFingerprint` greift) **oder** als
  explizite neue Fingerprint-Projektion. Im zweiten Fall ist der **Algorithmus-Bump
  Pflicht** (`MigrationFingerprint` v2 → v3; der Kopf-Kommentar „Bump on contract
  change" ist normativ) — sonst kollidieren alte und neue Fingerprints still.
- **Negativtests (müssen weiterhin als Drift / unterschiedlicher Fingerprint gelten):**
  - mehrere `identifier`-Spalten in einer Tabelle (nicht alle sind PK),
  - explizites `primary_key`, das **von** der `identifier`-Spalte **abweicht**,
  - **Composite-PK** (`primary_key: [a, b]`) vs. einzelne `identifier`-Spalte.
  Die Kanonisierung darf diese NICHT fälschlich gleichsetzen.
- Bestehender Grün-Fall bleibt grün: explizites `primary_key: [id]` (Regressions-
  Schutz gegen den bereits grünen Integrationstest).

## Open Question (gating — bestimmt, WO der Fix sitzt)

Soll die implizite „`identifier` ⇒ PK"-Konvention dauerhaft unterstützt werden, oder
sollen hand-/Smoke-Schemas künftig **immer** `primary_key: [id]` setzen?

**Empfehlung: implizite Konvention dauerhaft stützen → Fix in
Fingerprint/Normalizer.** Begründung: Das neutrale Modell **definiert** `identifier`
bereits als PK-tragend (`spec/neutral-model-spec.md`). Damit sind beide Schreibweisen
per Spec semantisch äquivalent; die Inkonsistenz liegt im Fingerprint, nicht in den
Beispielschemas. Eine Pflicht zu explizitem `primary_key` würde der Spec-Definition
widersprechen und die UX verschlechtern. → Fix in der Kanonisierung (Pre-Fingerprint
oder v3-Projektion), nicht in den Schemas. **Bei Slice-Start bestätigen.**

## Umgehung im 5d-Smoke (bis dahin)

`examples/sample-db/scripts/smoke-spatial.sh` Abschnitt `[lite]` toleriert den
Prozess-Exit (`|| true`) und prüft den **Report** (`status: ok`, kein
`executionError`) sowie den **Reverse**-Round-Trip (Geometrie + SRID + Spatial-Index,
Metatabellen gefiltert) — die für 5d relevanten Aussagen.
