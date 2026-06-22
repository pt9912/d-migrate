# `migrate --execute` Post-Compare-Drift bei **implizitem** `identifier`-PK (dialektneutral)

> Status: Vorabklärung · Severity **P2** (Korrektheitsdefekt der CLI-Exit-Semantik:
>   `migrate --execute` meldet Exit 5 auf einem spec-VALIDEN Schema).
> Entdeckt 2026-06-22 beim 5d-SpatiaLite-Live-Apply (zuerst auf SQLite beobachtet),
>   der Defekt ist aber **dialektneutral** (siehe Befund).
> Trigger: SpatiaLite-`migrate --execute`-Round-Trip (VA4/5d,
>   [`spatial-harness-slice.md`](../done/spatial-harness-slice.md)).
> Bezug:
>   (a) `spec/cli-spec.md` — Exit-Codes `migrate --execute`;
>   (b) neutrales Modell: [`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md)
>       Abschnitt 13.1 — „Jede Tabelle sollte einen `primary_key` haben (**explizit oder
>       über `identifier`-Typ**); ein fehlender Primary Key erzeugt eine Warnung (E008),
>       blockiert aber die Validierung nicht" → `identifier` IST per Spec PK-tragend;
>   (c) Fingerprint-Vertrag
>       `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/MigrationFingerprint.kt`
>       (`ALGORITHM = "schema-fingerprint-v2"`; hasht `primary_key` direkt; Bump bei
>       Vertragsänderung Pflicht);
>   (d) Reverse-Reader materialisieren den PK explizit (alle drei Dialekte).
> **Nicht-spatial, pre-existing** — NICHT von der Spatial-Slice verursacht; hier nur
>   dokumentiert, weil der 5d-Smoke ihn umgehen muss.

## Befund (präziser Scope)

`schema migrate --execute` endet mit **Exit 5** („Post-execute compare detected
drift") **genau dann, wenn das Soll-Schema kein explizites `primary_key` trägt und
den PK nur implizit über `type: identifier` ausdrückt** — obwohl die Migration
sauber durchläuft (Report `"status": "ok"`, `execution.completed = true`,
`executionError = null`).

**Dialektneutral — zuerst auf SQLite beobachtet, aber nicht SQLite-spezifisch.**
Der Defekt sitzt im dialektneutralen `MigrationFingerprint` (core) und in der
Asymmetrie Soll-Parser ↔ Reverse-Reader, die **alle** Reader teilen:

- Soll-Parser leitet PK **nicht** aus `identifier` ab →
  `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeStructureParsers.kt`
  (`primaryKey = node["primary_key"]?.toStringList() ?: emptyList()`).
- Reverse materialisiert PK **explizit** — SQLite
  `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt`
  (`primaryKey = pkColumns`), **PostgreSQL**
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt`
  (`primaryKey = primaryKeyColumns`), **MySQL**
  `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`
  (`primaryKey = pkColumns`).

→ Derselbe Post-Compare-Drift trifft latent auch **PG- und MySQL-Ziele** bei
identifier-only-Soll. Er wurde nur zuerst auf SQLite beobachtet, weil dort
(SpatiaLite-5d) getestet wurde. Die Slice MUSS alle drei Dialekte abdecken — sonst
bleiben PG/MySQL latent kaputt.

**Abgrenzung (sonst deckt ein späterer Test die falsche, bereits grüne Variante ab):**

- **Explizites `primary_key` → bereits grün (Exit 0).** Belegt durch
  `test/integration-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteMigrateRoundTripIntegrationTest.kt`
  (`primaryKey = listOf("id")` → `migrateExit shouldBe 0` + `MigrationFingerprint.compute(reverse)
  shouldBe MigrationFingerprint.compute(desired)`). Der Bug ist **NICHT** „migrate ist
  bei `identifier` generell kaputt".
- **Implizites `identifier` ohne `primary_key` → Exit 5.** Das ist der Defekt.
- Distinkt vom bestehenden Exit-5-Integrationstest (NOT-NULL-Rebuild): jener ist ein
  **Runtime-`executionError`**, hier ist `executionError = null` (reiner Post-Compare).

## Ursache

Der Post-Execute-Compare reverse-t das Ziel und vergleicht den **Fingerprint** gegen
das Soll. `MigrationFingerprint` hasht `primary_key` als Feld direkt
(`  primary_key=` + `joinToString`), kanonisiert die `identifier`→`primary_key`-
Äquivalenz aber **nicht** im `project()`. Bei identifier-only-Soll ist also
`primaryKey = []`, beim Reverse `[id]` → unterschiedliche `primary_key=`-Zeile →
unterschiedlicher Fingerprint → gemeldete Drift, obwohl die Schemata **semantisch
identisch** sind.

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

## Akzeptanz (für eine spätere, **dialektneutrale** Slice)

- `migrate --execute` gegen ein Ziel mit **implizitem** `identifier`-PK (ohne
  `primary_key`) endet mit Exit 0, wenn die Migration sauber durchläuft — verifiziert
  für **SQLite, PostgreSQL und MySQL** (nicht nur SQLite).
- Die `identifier`→PK-Äquivalenz wird **vor** dem Hashing kanonisiert — entweder als
  benannte Pre-Fingerprint-Normalisierung (Soll **und** Reverse leiten die implizite
  PK aus `identifier` ab) **oder** als explizite neue Fingerprint-Projektion. Im
  zweiten Fall ist der **Algorithmus-Bump Pflicht** (`schema-fingerprint-v2` → `v3`;
  der Kopf-Kommentar „Bump on contract change" ist normativ) — sonst kollidieren alte
  und neue Fingerprints still.
- **Präzise Kanonisierungsregel:** implizite PK NUR ableiten, wenn **genau eine**
  `identifier`-Spalte existiert **und** `primaryKey` leer ist. Andernfalls bleibt der
  PK leer (Drift bzw. Warnung wie gehabt) — die Regel darf keine ambige PK erfinden.
- **Last-tragender Äquivalenz-Test (nicht nur Fingerprint):** `schema generate` aus
  dem Reverse erzeugt **byte-identisches DDL** wie aus dem Soll
  (`generate(reverse) == generate(soll)` auf DDL-Ebene) — das ist der Beweis, dass
  „semantisch identisch" stimmt; die Empfehlung steht und fällt damit.
- **Negativtests (müssen weiterhin als Drift / unterschiedlicher Fingerprint gelten):**
  - **mehrere `identifier`-Spalten ohne explizites `primary_key`** (implizite PK
    **ambig** → NICHT ableiten),
  - explizites `primary_key`, das **von** der `identifier`-Spalte **abweicht**,
  - **Composite-PK** (`primary_key: [a, b]`) vs. einzelne `identifier`-Spalte.
- Bestehender Grün-Fall bleibt grün: explizites `primary_key: [id]` (Regressions-
  Schutz gegen den bereits grünen Integrationstest).

## Entscheidung (durch Spec praktisch bestätigt — bei Slice-Start ratifizieren)

Soll die implizite „`identifier` ⇒ PK"-Konvention dauerhaft gestützt werden, oder
sollen hand-/Smoke-Schemas künftig **immer** `primary_key: [id]` setzen?

**Stützen → Fix in Fingerprint/Normalizer (dialektneutral, core).** Spec 13.1 sagt
wörtlich, ein PK gelte „explizit **oder über `identifier`-Typ**", fehlender PK sei nur
Warnung E008 — also definiert das neutrale Modell `identifier` bereits als PK-tragend
und einen fehlenden expliziten `primary_key` als zulässig. Damit ist die Frage
faktisch beantwortet: die Inkonsistenz liegt im Fingerprint, nicht in den Schemas;
eine Pflicht zu explizitem `primary_key` würde der Spec widersprechen. Bei Slice-Start
nur noch ratifizieren.

## Umgehung im 5d-Smoke (bis dahin)

`examples/sample-db/scripts/smoke-spatial.sh` Abschnitt `[lite]` toleriert den
Prozess-Exit (`|| true`) und prüft den **Report** (`status: ok`, kein
`executionError`) sowie den **Reverse**-Round-Trip (Geometrie + SRID + Spatial-Index,
Metatabellen gefiltert) — die für 5d relevanten Aussagen.
