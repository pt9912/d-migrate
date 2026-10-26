# PostgreSQL: zwei Wertelisten-CHECKs an einer Spalte lassen `schema migrate` mit Exit 5 scheitern

> **Status:** In Arbeit (2026-10-26); P0 bis P3 umgesetzt. **Vorbedingung:** keine.
> Befund aus dem Plan [`postgres-rollback-inline-enum-werteliste.md`](postgres-rollback-inline-enum-werteliste.md)
> (Abschnitt „Befund außerhalb des Slices“). Ohne GitHub-Issue, auf Entscheidung des Eigners direkt behoben.

## Ziel

`schema migrate --execute` gegen eine PostgreSQL-Tabelle, deren Spalte zwei Wertelisten-`CHECK`s trägt und die die Datei als
Enum-Spalte führt, endet nicht mit Exit 5, sondern gleicht die Spalte an. Wo der Down nicht tragen kann, sagt der Lauf es vor dem Apply.

## P0 Messung (PostgreSQL 18, `PostgresEnumRollbackIntegrationTest`)

Tabelle `t (id, e text NOT NULL CHECK (e IN ('a','b','c')), CONSTRAINT second CHECK (e IN ('a','b')))`, Datei mit `e: enum`.

| Fall | Ergebnis |
| ---- | -------- |
| zwei Wertelisten-CHECKs, Liste erweitert (`a,b,d`), nur Up | Exit 5, Datenbank unverändert |
| dasselbe, Liste gleich einem der beiden (`a,b`), nur Up | Exit 5, Datenbank unverändert (auch ohne Änderung der Liste) |
| Liste erweitert oder verkürzt, Up + `--generate-rollback` | Exit 8, `ROLLBACK_NOT_POSSIBLE`, `POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN`, nichts ausgeführt |
| ein Wertelisten-CHECK plus fachlicher CHECK (`length(e) > 0`), Up allein und Up + Down | Exit 0; der Down stellt beide wieder her (grüne Gegenprobe) |

**Ursache.** `EnumCheckProjection.foldable` faltet einen Wertelisten-CHECK nur, wenn er der **einzige** der Spalte ist
(`perColumn.size == 1`). Bei zweien bleibt jeder ein eigener Constraint der Ist-Seite, und der Vergleich meldet
`AlterColumnType` plus je ein `DropConstraint` (`second`, `t_e_check`). `renderAlterColumnType` löst vor dem neuen CHECK den ersten
Treffer von `enumValueCheckName` (`DROP … IF EXISTS "second"`); das folgende `DropConstraint "second"` (ohne `IF EXISTS`) trifft
einen fehlenden Constraint, die Anweisung scheitert, die Transaktion rollt zurück. Das ist ein Statement-Fehler im Apply, kein
Drift nach dem Apply. Die Datenbank bleibt unverändert, weil der Up transaktional ist.

## P1 Entscheidung

* **Up: das `AlterColumnType` löst bei mehreren Wertelisten-CHECKs keinen** (`enumValueCheckNames().singleOrNull()`); die
  `DropConstraint`-Operationen, die der Vergleich ohnehin meldet, lösen beide, jeden Namen einmal. Der Up-Text für die Einzel-CHECK-Spalte
  ändert sich nicht. Verworfen: beide im `AlterColumnType` lösen — dann scheiterten die `DropConstraint`s, oder man müsste sie
  planabhängig in `IF EXISTS` verwandeln und so das laute Scheitern eines falschen Namens aufweichen.
* **Down: unverändert blockiert.** Der neue CHECK entsteht, solange `t_e_check` noch steht, und heißt deshalb `t_e_check1`; den Namen
  rät der Renderer nicht (Regel aus dem Plan zu #61). Der Down blockiert vor dem Apply (Exit 8), statt mit Exit 5 zu scheitern. Fail-loud ist hier die
  tragfähige Lösung; ein vollständiger Down bräuchte das Namens-Wissen des Servers und ist ein eigenes Thema.
* **Kein Fingerabdruck-Tag, kein ADR**: Adapter-Eingriff im Renderer, Modell und Fingerabdruck unberührt.

## P2 Umsetzung

* `PostgresDiffRenderContext.enumValueCheckNames` (statt `enumValueCheckName`), `PostgresDiffTableOps.dropExistingEnumValueCheck`.
* Tests: `PostgresEnumCheckRollbackTest` (Renderer: jeder Name genau einmal gelöst) und
  `PostgresEnumRollbackIntegrationTest` (Erweitern, Verkürzen, Liste eines der beiden, Block mit Rollback, fachlicher CHECK daneben).
  Ohne die Änderung rot: der Renderer-Test und die drei Up-Specs; die Block- und die fachliche-CHECK-Spec sind grüne Gegenproben.

## P3 Doku

Spec `ddl-generation-rules.md` §12, Anwenderhandbuch §3.5, `CHANGELOG.md`.

## Nicht-Scope

Ein ausführbarer Down für zwei Wertelisten-CHECKs; andere Dialekte (nicht gemessen).
