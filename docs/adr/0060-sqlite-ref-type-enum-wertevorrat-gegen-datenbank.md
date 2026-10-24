---
status: accepted
date: 2026-10-24
decision-makers: pt9912
consulted: docs/adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md, docs/adr/0026-fingerprint-kanonisierung-post-compare.md, docs/planning/in-progress/sqlite-enum-domain-check-reverse.md
informed: hexagon/core (TableComparator, MigrationFingerprint, CheckPreflightPlanner), adapters/driven/driver-sqlite (SqliteSchemaReader), spec/ddl-generation-rules.md, docs/user/anwenderhandbuch.md, CHANGELOG.md
---

# Der Wertevorrat eines `ref_type`-Enums auf SQLite wird gegen eine Datenbank aufgelöst, `schema-fingerprint-v18`

> **Status: accepted (2026-10-24).** Auf SQLite lösen Abdruck und
> zielbewusster Vergleich den Wertevorrat einer `ref_type`-Enum-Spalte aus den
> Custom Types des Soll-Schemas auf. Der Reader gibt dafür den unbenannten
> CHECK der Spalte nur im Migrate- und Rollback-Pfad zurück. Das ändert den
> Abdruck jedes SQLite-Soll-Schemas mit `ref_type`-Enum; der Algorithmus-Tag
> steigt auf `v18`.

## Kontext und Problemstellung

[ADR 0055](0055-enum-wertevorrat-im-zielbewussten-vergleich.md) stellt einen
Wertelisten-CHECK dem `enum` am Spaltentyp gleich. Das greift für ein
Inline-Enum (`values`), das SQLite mit **benanntem** CHECK schreibt. Ein
`ref_type`-Enum liegt dagegen als **unbenannter** CHECK an der Spalte (ein
benannter käme vom Reverse als Constraint zurück, den das Soll nicht kennt →
`POST_EXECUTE_DRIFT`). Der Reader verwirft unbenannte CHECKs (Notiz `R203`).

Folge: gegen eine **echte** Datenbank sah der Planer eine Änderung des
Wertevorrats nicht — `schema migrate` endete mit „ok", der CHECK blieb
unverändert. Gegen ein `file:`-Ziel tritt das nicht auf, dort sieht der Diff
`AlterCustomType`.

Den CHECK nur im Reader zurückzugeben genügt nicht: die Ist-Seite hätte dann
einen Wertevorrat, die Soll-Seite nicht (`Enum(values = null, refType = …)`
liefert in `EnumCheckProjection.declaredVocabulary` nichts) — `ValueChange` im
Vergleich und `POST_EXECUTE_DRIFT` bei jedem Lauf.

## Betrachtete Optionen

1. **Reader allein** — verworfen: Dauer-Drift (siehe oben).
2. **Soll-Seite auflösen, Reader mit Option** — gewählt.
3. **Rebuild bei jedem Lauf** — verworfen: ein ewiger No-Op-Rebuild.

## Entscheidung

1. **Soll-Auflösung.** `RefTypeVocabulary.resolve` ersetzt `Enum(refType = r)`
   durch `Enum(values = custom_types[r].values)`, wenn `custom_types[r]` ein ENUM
   ist. DOMAIN und COMPOSITE bleiben unberührt. Sie wirkt nur im Abdruck und im
   Wertevorrats-Vergleich, nie in geplanten Operationen oder erzeugter DDL.
2. **Dialekt-Gate.** Die Capability `resolvesRefTypeVocabulary` ist nur für
   SQLite gesetzt. PostgreSQL führt den Typ selbst, MySQL rendert einen nativen
   `ENUM`, SQL Server und Oracle lösen `ref_type` über ihren Kanonisierer auf;
   dort ändert sich nichts.
3. **`file:`-Ziele bleiben operationsgleich.** Der Vergleich löst eine Spalte nur
   auf, wenn die **Gegenseite** sie nicht selbst als `ref_type` führt; sonst deckt
   der Vergleich der Custom Types die Änderung ab, und ein zusätzliches
   `ColumnDiff` änderte Operations-IDs. Der Abdruck löst immer auf.
4. **Reader-Option.** `SchemaReadOptions.sqliteRefTypeChecks` (Default aus) gibt
   einen unbenannten, Wertelisten-förmigen CHECK als Constraint mit
   synthetischem Namen `__dm_enum_<tabelle>_<spalte>` zurück. `SchemaMigrateWiring`
   und `SchemaRollbackWiring` setzen sie fest — kein Nutzer-Flag, keine Änderung
   an `spec/cli-spec.md`. `schema reverse`, strikter `schema compare` und der
   Datentransfer lesen unverändert. Der Constraint entsteht nur bei genau einem
   solchen CHECK je Spalte und freiem Namen; sonst bleibt es bei `R203`.
5. **Rebuild.** Beim Neubau wird ein synthetischer Constraint unbenannt am
   Spaltentext zurückgeschrieben, so wie er war; sonst käme er als benannter
   CHECK zurück.
6. **Preflight.** `CheckPreflightPlanner` plant für eine `AlterColumnType` auf
   ein `ref_type`-Enum (nur mit dem Dialekt-Gate) eine Sonde
   `SELECT count(*) … WHERE NOT (<spalte> IN (…))`; `FAILED` blockiert den
   Neubau vor dem Apply.
7. **Warnung.** `SQLITE_CUSTOM_TYPE_VOCABULARY_UNVERIFIED` entfällt für ENUM
   und bleibt für DOMAIN.
8. **Fingerprint-Bump.** `ALGORITHM` steigt von `schema-fingerprint-v17` auf
   `v18`. Anders als bei `custom_types[0]` (dort blieb der Tag) ändert sich hier
   der Hash echter Eingaben (`enum_checks[0]` → `[1]`). Der Tag gilt für alle
   Dialekte; deren Abdrücke bleiben bis auf den Tag byte-gleich. Ein
   `v17`-Rollback-Artefakt liefert die bestehende Meldung
   `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`.

## Konsequenzen

- Gegen eine echte SQLite-Datenbank erkennt und setzt `schema migrate` eine
  Änderung des Wertevorrats eines `ref_type`-Enums um; Folgelauf ohne Drift.
- Weiter nicht erkannt: eine Änderung des **Domain**-CHECKs gegen eine Datenbank
  (kein Wertelisten-CHECK; eigener Schnitt).
- Ein `schema migrate`-Artefakt vor `v18` muss neu erzeugt werden.
- Ein Rollback eines Neubaus stellt den CHECK unbenannt am Spaltentext wieder
  her (getestet gegen `sqlite_master`).

## Weitere Informationen

Ergänzt [ADR 0055](0055-enum-wertevorrat-im-zielbewussten-vergleich.md) und
orientiert sich am Umgang mit Altartefakten aus
[ADR 0026](0026-fingerprint-kanonisierung-post-compare.md). Plan:
[`sqlite-enum-domain-check-reverse.md`](../planning/in-progress/sqlite-enum-domain-check-reverse.md).
