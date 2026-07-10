# Property-Based Testing (LN-046)

**Status:** ABGESCHLOSSEN (2026-07-10) — Phasen A + B + C geliefert; die
Anforderung erledigt.
**Lastenheft:** [`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046) ·
**Roadmap:** Milestone 1.0.0-RC · **ADR:** [0029](../../adr/0029-property-based-testing-framework.md)

## Ziel

[`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046) fordert Property-Based
Testing mit automatischer Testfall-Generierung **für Schema-Parsing** und
**Shrinking** zur Ursachenfindung. Umgesetzt mit **kotest-property** (nicht
Jqwik — Begründung in [ADR 0029](../../adr/0029-property-based-testing-framework.md)).

Die Strategie: reine, deterministische Kernfunktionen (Codecs, Kanonisierer,
Typ-Mapper, YAML-Parser, Fingerprint) über generierte Eingaben gegen ihre
Invarianten prüfen — genau der Cross-Dialect-Kern, den die 0.9.9-Piloten
gestresst haben. Property-Tests bauen dort ein Regressionsnetz, das die
schwereren RC-Punkte (Streaming, SHA-256-Integrität, Checkpoint-Rollback,
Credential-/TLS-/Audit-Security) mit absichert.

## Framework

`io.kotest:kotest-property` (an `kotestVersion` gebunden), verdrahtet im
`subprojects`-Dependency-Block von `build.gradle.kts`. Property-Tests leben in
bestehenden Kotest-Specs (`FunSpec`) über `checkAll(Arb.…) { … }`; Shrinking ist
eingebaut.

## Ziel-Invarianten (nach Setup-Aufwand)

| Ziel | Modul | Invariante | Generator |
| ---- | ----- | ---------- | --------- |
| `ObjectKeyCodec` | `hexagon:core` | Round-Trip `decode∘encode`, `parseRoutineKey∘routineKey`, `parseTriggerKey∘triggerKey` | `Arb.string` |
| `TypeMapper.toSql` (PG/MySQL/SQLite) | Driver-Module | Totalität / Nie-Crash über alle `NeutralType` | `Arb<NeutralType>` |
| `NeutralTypeCanonicalizer` (PG/MySQL/SQLite) | Driver-Module | Idempotenz `canon∘canon == canon` | `Arb<NeutralType>` |
| `MigrationFingerprint` (v7) | `hexagon:core` | Ordnungs-Unabhängigkeit (Map-/Set-Permutation → gleicher Hash); Metadaten-Ausschluss | `Arb<SchemaDefinition>` |
| YAML-Schema-Parser | `adapters:driven:formats` | `write→read`-Round-Trip; Nie-Crash auf beliebigem Text (nur wohlgeformte Fehler) | `Arb<SchemaDefinition>` / `Arb.string` |

## Phasen

- **Phase A — Harness + Beweis (geliefert 2026-07-10):** `kotest-property`
  verdrahtet; `ObjectKeyCodecPropertySpec` (5 Round-Trip-/Namens-Invarianten,
  reine Strings, kein DB-Setup). Belegt Generatoren + Shrinking end-to-end.
- **Phase B — Kern-Invarianten (geliefert 2026-07-10):** geteilter
  `Arb<NeutralType>` (alle 21 Varianten, uniform via `Arb.choice`) in den
  **core-Test-Fixtures** (`hexagon/core/src/testFixtures/kotlin/dev/dmigrate/core/model/NeutralTypeArb.kt`,
  via `testFixturesApi(kotest-property)`; Driver konsumieren per
  `testImplementation(testFixtures(project(":hexagon:core")))`). Drei
  Driver-Specs (`<Dialekt>NeutralTypePropertySpec`) prüfen `TypeMapper.toSql`-
  **Totalität** (fängt „neue `NeutralType`-Variante ohne Mapper-Update" — bei
  PG/MySQL zur Laufzeit via `simpleToSql`-`error`, bei SQLite compile-erschöpft;
  spec-nah zu [`LN-045`](../../../spec/lastenheft-d-migrate.md#ln-045)) +
  `NeutralTypeCanonicalizer`-**Idempotenz** je Dialekt. Verifiziert: `:check`
  (test + detekt + koverVerify-90%) für Core + alle drei Driver grün. Die offene
  Frage (Ablageort) ist zugunsten der Test-Fixtures entschieden — reusable für
  Phase C.
- **Phase C — Schema-Parsing (spec-wörtlich, geliefert 2026-07-10):** geteilter
  `Arb<SchemaDefinition>` (Tabellen/Spalten über die volle `NeutralType`-Matrix,
  YAML-sichere präfixierte Bezeichner) in den core-Test-Fixtures
  (`hexagon/core/src/testFixtures/kotlin/dev/dmigrate/core/model/SchemaArb.kt`).
  Drei Properties: **(C1)** `YamlSchemaCodec.read` wirft nie eine
  `NullPointerException` auf beliebigem Text (nur Domänen-/Parse-Fehler);
  **(C2)** `MigrationFingerprint` ist reihenfolge-unabhängig (Tabellen-/Spalten-
  Permutation) und schließt Reporting-Metadaten (name/version/description) aus;
  **(C3)** semantischer Round-Trip `compute(read(write(s))) == compute(s)` — der
  Fingerprint als Orakel (kanonisiert, ignoriert `ordinal`/Metadaten) fängt
  echten Datenverlust ohne an belangloser Normalisierung falsch-rot zu werden.
  Damit ist [`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046) erledigt (das
  Schema-Parsing-Property ist die namentliche Anforderung). Verifiziert: `:check`
  (test + detekt + koverVerify-90 %) für Core + formats grün.

### Befund (per PBT gefunden)

C3 shrinkte auf `Enum(values=[], refType=null)` (leeres Enum): der Fingerprint
projiziert `[]` als `enum()`, der YAML-Codec normalisiert die leere Liste auf
`null` (→ `enum`) — der einzige nicht round-trippende Typ. Ein Enum ohne Werte
ist ein **ungültiges Schema** (der Reverse-Reader erzeugt es nie), daher im
Generator ausgeschlossen (Werteliste = null oder nicht-leer) statt als
Codec-Bug behandelt. Alle übrigen 29 Typvarianten (inkl. Geometry+SRID,
`datetime(tz)`, Array, Enum-mit-Werten/-ref) round-trippen verlustfrei.

### Round-Trip-Abdeckung (transparenter Scope)

C3 deckt den **Tabellen-/Spalten-/Typ-Kern** ab (voller `NeutralType`-Satz +
optionaler String-Default + PK). Noch nicht generiert und daher nicht
round-trip-geprüft: Referenzen, Constraints, Indizes, Partitionierung,
Views/Routinen/Sequenzen und nicht-String-Defaults — optionale Breiten-
Erweiterung des `Arb<SchemaDefinition>` in einem Folge-Increment.

## Definition of Done — erfüllt

- ✅ Alle drei Phasen grün; Shrinking real demonstriert (C3 shrinkte auf das
  degenerierte leere Enum, siehe Befund), Generator bereinigt.
- ✅ Kover-Coverage pro Modul ≥ 90 % gehalten (`:check` für Core/Driver/formats grün).
- ✅ Roadmap [`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046) → ✅ (mit Beleg);
  CHANGELOG-Eintrag; ADR 0029 accepted.
- ✅ Slice graduiert nach `docs/planning/done/`.

## Offene Fragen

- ~~Geteilter `Arb<NeutralType>`-Baukasten: Test-Fixtures oder pro Modul lokal?~~
  **Entschieden (Phase B):** core-Test-Fixtures. `Arb<SchemaDefinition>` (Phase C)
  gesellt sich dorthin.
- Iterations-Budget/Seed-Politik für die CI (Determinismus vs. Abdeckung).
