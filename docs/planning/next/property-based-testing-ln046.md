# Property-Based Testing (LN-046)

**Status:** in Arbeit — Phase A geliefert (2026-07-10).
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
- **Phase B — Kern-Invarianten:** wiederverwendbarer `Arb<NeutralType>` (~21
  sealed-Varianten) als Test-Baustein; `TypeMapper.toSql`-Totalität je Dialekt
  (fängt „neue `NeutralType`-Variante ohne Mapper-Update", spec-nah zu
  [`LN-045`](../../../spec/lastenheft-d-migrate.md#ln-045))
  + `NeutralTypeCanonicalizer`-Idempotenz je Dialekt. Ablageort des geteilten
  `Arb<NeutralType>` klären (Test-Fixtures vs. pro-Modul-Duplikat).
- **Phase C — Schema-Parsing (spec-wörtlich):** `Arb<SchemaDefinition>` /
  `Arb<TableDefinition>`; YAML `write→read`-Round-Trip; Parser-Nie-Crash auf
  beliebigem Text; `MigrationFingerprint`-Ordnungs-Unabhängigkeit +
  Metadaten-Ausschluss. **Erst hiernach ist
  [`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046) „erledigt"** (das
  Schema-Parsing-Property ist die namentliche Anforderung).

## Definition of Done

- Alle drei Phasen grün; Shrinking an mindestens einem bewussten
  Fehlversuch demonstriert (minimales Gegenbeispiel), dann bereinigt.
- Kover-Coverage pro Modul ≥ 90 % gehalten (Property-Tests erhöhen Ausführung,
  senken sie nicht).
- Roadmap [`LN-046`](../../../spec/lastenheft-d-migrate.md#ln-046) → ✅ (mit Beleg);
  CHANGELOG-Eintrag; ADR 0029 accepted.
- Slice graduiert nach `docs/planning/done/`.

## Offene Fragen

- Geteilter `Arb<NeutralType>`/`Arb<SchemaDefinition>`-Baukasten: eigenes
  Test-Fixtures-Artefakt (modulübergreifend nutzbar) oder pro Modul lokal?
- Iterations-Budget/Seed-Politik für die CI (Determinismus vs. Abdeckung).
