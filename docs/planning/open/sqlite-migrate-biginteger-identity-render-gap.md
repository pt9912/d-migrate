# SQLite-`migrate` von authored `biginteger`+`identity`: Render-Lücke + Präferenz-Threading im Re-Read

> Status: **Draft (Trigger Watch)**
> Trigger: Abspaltung beim Bau des Reverse-Präferenzen-Slices
> ([`../done/reverse-preferences.md`](../done/reverse-preferences.md),
> Nicht-Scope). Der Slice adressiert den **Transfer**-Fall (SQLite reverse →
> PG/MySQL generate); dieser Befund betrifft `migrate --execute` **gegen ein
> SQLite-Ziel** mit authored `biginteger`+`generation: identity`.
> Severity: **P3** (schmal — nur wer explizit `biginteger`+`identity` gegen SQLite
> migriert; final beim Schnitt einzustufen).
> **Reichweite (2026-09-17):** Der Eintrag trägt seit dem Nachtrag unten auch den
> **MySQL**-Fall (`schema migrate` plant ein wirkungsloses `MODIFY COLUMN`,
> sobald das Soll kein `legacy_serial_syntax` trägt); er ist bei der Graduation
> des Compare-Slices hier verankert. Der Dateiname nennt nur SQLite und bleibt,
> weil mehrere Pläne ihn verlinken. Wer den Eintrag schneidet, prüft, ob die
> SQLite-Render-Lücke und die MySQL-Naht in **einen** Plan gehören — sie teilen
> Ursache 2 (Präferenz im Ist-Stand), nicht Ursache 1.

## Befund — **zwei** Ursachen (Review B3)

Ein spec-valides Schema mit authored `biginteger` + `generation: identity` gegen
ein SQLite-Ziel migriert **driftet**, und zwar aus **zwei** unabhängigen Gründen.
Beide müssen behoben werden — der Render-Fix allein lässt die Drift bestehen:

1. **Render-Lücke im Diff/Rebuild-Pfad.** Der Voll-Generate-Pfad
   ([`SqliteColumnConstraintHelper`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteColumnConstraintHelper.kt),
   `generateRowidIdentityColumn`) rendert `biginteger`+`identity` korrekt als
   `INTEGER PRIMARY KEY AUTOINCREMENT`. Der von `migrate --execute` genutzte
   Diff/Rebuild-Renderpfad
   ([`SqliteDiffSqlBuilders`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSqlBuilders.kt))
   inspiziert `col.generation` **nicht** → rendert plain `INTEGER` + separates
   `PRIMARY KEY(id)` (valides DDL, **appliziert**, aber **AUTOINCREMENT still
   verloren**). `columnLine` **und** `primaryKeyClause` müssen auf Parität mit dem
   Voll-Generate-Pfad gehoben werden.

2. **Präferenz-Threading im Post-Compare-Re-Read.** Selbst nach dem Render-Fix
   driftet der Post-Compare, solange der Re-Read des SQLite-Ziels die
   Reverse-Präferenz **nicht mitführt**: der Default-Re-Read liefert `identifier`,
   das authored Soll ist `biginteger`+`identity` → Exit 5. Die aus dem
   Reverse-Präferenzen-Slice stammende `SchemaReadOptions.sqliteAutoincrement`-
   Präferenz muss bis in den Post-Compare-Re-Read (`SchemaMigrateExecutionStage`)
   gefädelt werden — oder der `migrate`-Kontext leitet sie deterministisch ab.

## Nachtrag 2026-09-17 — MySQL: zwei Ursachen, nicht eine

Seit dem vierten Bauabschnitt des Compare-Slices
([`../done/compare-projektion-und-normalisierung.md`](../done/compare-projektion-und-normalisierung.md))
gibt es die Reverse-Präferenz `serial`/`identity` für MySQLs
`BIGINT AUTO_INCREMENT` (und SQLite unter der 64-Bit-Breite). Gegen MySQL 9.7.2
gemessen, `schema migrate --plan-only` gegen dieselbe Datenbank, aus der das
Soll stammt (eine Tabelle mit `BIGINT AUTO_INCREMENT`):

| Soll | Plan |
| ---- | ---- |
| Reverse ohne Präferenz (`legacy_serial_syntax: true`) | keine Operation |
| Reverse mit `--mysql-autoincrement-syntax identity` (ohne das Flag) | eine `AlterColumnGeneration`, gerendert als ``ALTER TABLE `t` MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT`` — ändert die Spalte nicht |
| **handgeschrieben**, `biginteger` + `generation: {type: identity, mode: by_default}`, **ohne** Präferenz | dieselbe `AlterColumnGeneration` — mit 1.7.1 genauso |
| handgeschrieben wie oben, zusätzlich `legacy_serial_syntax: true` | keine Operation |

Die dritte Zeile hat mit der Präferenz nichts zu tun; sie plante schon vor dem
Compare-Slice so (der MySQL-Reverse setzte das Flag immer). Die erste Fassung
dieses Nachtrags nannte nur das fehlende Threading — das ist die **zweite**
Ursache, nicht die einzige (Review Runde 4, M-1):

1. **Die zielbewusste Naht wertet das Flag gegen MySQL.**
   `capabilityGenerationCanonicalizer`
   ([`TypeCanonicalizerWiring.kt`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/TypeCanonicalizerWiring.kt))
   projiziert Sequenzname und `stored`, nicht `legacy_serial_syntax`. Der
   MySQL-Generator rendert beide Formen identisch
   ([`spec/type-mapping.md`](../../../spec/type-mapping.md), Abschnitt 4.4:
   „MySQL selbst erzeugt aus beiden Formen dieselbe Spalte"), der Vergleich
   sieht trotzdem einen Unterschied. Das trifft **jedes** Soll ohne das Flag
   gegen ein MySQL-Ziel, mit und ohne Präferenz. Für SQLite unter der
   64-Bit-Breite gilt dieselbe Frage, sobald Ursache 1 des Abschnitts oben
   (Render-Lücke) behoben ist.
2. **Der Ist-Stand wird ohne Präferenz gelesen.** `schema migrate` (und
   `schema rollback`) lesen die Datenbank mit dem Default, also mit dem Flag.
   Ein mit `identity` gelesenes Soll unterscheidet sich darin vom Ist.

**Welche Behebung was schließt.** Ursache 2 allein (Threading) schließt die
zweite Zeile nur, wenn der Anwender die Präferenz auch für `schema migrate`
deklariert; die dritte Zeile bleibt, solange er es nicht tut. Ursache 1 allein
schließt beide Zeilen. **Ursache 1 zu beheben heißt aber, in der Naht zu
falten, die auch den Fingerabdruck und die Overlay-Bindung speist**
(`SchemaMigrateRunner`, `SchemaRollbackRunner`, `PartitionOverlayHint`) — genau
das schließt [ADR 0027](../../adr/0027-reverse-preferences-inhaerente-mehrdeutigkeit.md)
in Entscheidung 3 aus („Kein Fingerprint-Bump, kein Fold"). Das ist eine
Architektur-Frage, keine Bau-Entscheidung: entweder eine Fähigkeit „rendert
`SERIAL` und IDENTITY gleich" nur für den Plan (nicht für den Abdruck), oder ein
Abdruck-Bump mit Begründung, oder die Präferenz bleibt der einzige Weg und der
Plan mit der wirkungslosen Operation die dokumentierte Folge.

**Spannung zur Spezifikation.**
[`spec/dialect-preference-mechanism.md`](../../../spec/dialect-preference-mechanism.md)
sagt unter „Reichweite der Konfiguration", eine Lese-Präferenz gelte „für jeden
Reverse, dessen Ergebnis der Anwender liest oder vergleicht". Der Ist-Stand von
`schema migrate … db:` ist ein solcher Reverse; er liest heute ohne. Die Spec
beschreibt hier das Zielbild; Ursache 2 ist der Abstand dazu.

**Handbuch bis dahin.** Das Anwenderhandbuch (Reverse-Abschnitt) nennt beide
Wirkungen getrennt: wer eine mit `identity` gelesene Datei als Soll gegen
dieselbe MySQL-Datenbank nimmt, liest ohne `identity`; ein handgeschriebenes
Soll plant die wirkungslose Operation unabhängig von der Präferenz — wer es
nur gegen MySQL einsetzt, trägt das Flag ein (letzte Zeile der Tabelle), mit
der Folge `BIGSERIAL` auf PostgreSQL.

## Nachtrag 2026-09-18 — Ursache 1 ist geschlossen

Plan 2 des Reader-Umbrellas
([`../done/reader-treue-2-meldungen.md`](../done/reader-treue-2-meldungen.md),
Paket S2) hat die **Render-Lücke** behoben: `SqliteDiffSqlBuilders.columnLine`
rendert `generation: identity` auf `integer`/`biginteger` jetzt als
`INTEGER PRIMARY KEY AUTOINCREMENT`, wenn die Spalte allein den
Primärschlüssel bildet, und `primaryKeyClause` lässt die zweite
Primärschlüssel-Klausel dann weg. Beide `CREATE TABLE`-Emitter des
Migrate-Pfads gehen darüber — der Diff-Renderer und der Tabellen-Neubau. Am
Server gemessen (SQLite 3.45, `schema migrate --execute`): der gespeicherte
Tabellentext trägt `AUTOINCREMENT`, und `sqlite_sequence` entsteht — die
Tabelle, die es **nur** für eine solche Tabelle gibt.

Mitgezogen: `SqliteCompositePkIdentity.isDroppedAutoincrement` prüft jetzt die
Spalte statt nur ihren Typ. Sonst wäre mit dem Fix ein neuer stiller Verlust
entstanden — im **zusammengesetzten** Schlüssel gibt es weiterhin kein
AUTOINCREMENT, und `W135` hätte diese Schreibweise nicht gemeldet.

**Ursache 2 bleibt offen — und sie ist breiter als bisher beschrieben.**
Gemessen an SQLite 3.45, `schema migrate --execute` gegen ein leeres Ziel, mit
zwei Sollformen derselben Spalte:

| Soll | erzeugte DDL | Ausgang |
| --- | --- | --- |
| `identifier` mit `auto_increment` | `"id" INTEGER PRIMARY KEY AUTOINCREMENT` | Exit **0** |
| `biginteger` + `generation: identity` | **dieselbe** DDL, Zeichen für Zeichen | Exit **5**, `POST_EXECUTE_DRIFT` |

Es driftet also nicht die Datenbank, sondern die Schreibweise des Solls. Der
bisherige Text nennt dafür das fehlende **Präferenz-Threading** (der Re-Read
liest ohne `--sqlite-autoincrement-width 64` und `--sqlite-autoincrement-syntax
identity` und liefert `identifier`). Dazu kommt ein zweiter Grund:

**Der Fingerabdruck faltet die beiden Schreibweisen nur in `generation`, nicht
im Typ.** `MigrationFingerprint.impliedGeneration` macht aus `identifier` mit
`auto_increment` ein `identity(always)`, wo der Dialekt beide Formen gleich
rendert; der **Typ** geht dagegen unverändert durch `canonicalizeType` —
`identifier(auto)` bleibt `identifier(auto)`, `biginteger` wird `integer`. Der
Post-Compare sieht deshalb zwei verschiedene Typen, während
`TableComparator.identitySpelledDifferently` die Spalten längst
zusammenfaltet. Wer Ursache 2 schneidet, entscheidet deshalb zwei Dinge: ob die
Präferenz bis in den Re-Read gefädelt wird **und** ob der Abdruck die Faltung
auch im Typ nachvollzieht — Letzteres ist eine Projektionsänderung mit
Anhebung, also dieselbe Architektur-Frage, die der Nachtrag oben für MySQL
aufwirft (ADR 0027, Entscheidung 3).

**Gepinnt ist der Zustand**, nicht die Hoffnung: ein Integrationsfall
(`SqliteIdentityGenerationMigrateIntegrationTest`) hält beide Ausgänge
nebeneinander fest. Wird Ursache 2 geschlossen, wird er rot — er ist der
Wächter über diesem Eintrag.

## Nicht-Scope

- Der SQLite→PG/MySQL-**Transfer** (kein SQLite-Generate involviert) — der ist im
  Reverse-Präferenzen-Slice gelöst.
