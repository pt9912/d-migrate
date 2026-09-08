---
status: accepted
date: 2026-09-08
decision-makers: pt9912
consulted: docs/planning/done/routine-body-cross-dialect-portability.md
informed: adapters/driven/driver-common (RoutineBodyOrigin, ViewQueryTransformer), adapters/driven/driver-postgresql, adapters/driven/driver-mysql, adapters/driven/driver-sqlite, adapters/driven/driver-mssql, adapters/driven/driver-oracle, spec/ddl-generation-rules.md
---

# Routinen-Rümpfe werden nicht übersetzt — die Herkunft entscheidet, nicht der Inhalt

> **Status: accepted (2026-09-08).** Der Rumpf einer Funktion, Prozedur oder
> eines Triggers wird für einen **fremden** Quelldialekt grundsätzlich nicht
> gerendert, sondern mit `E053` als manuelle Nacharbeit ausgewiesen. Beurteilt
> wird die **Herkunft** (`source_dialect`), nicht der Inhalt — anders als beim
> Sichten-Rumpf. Ein Rumpf ohne Herkunft gilt als für das Ziel geschrieben.

## Kontext und Problemstellung

Für **Sichten** beurteilt `ViewQueryTransformer.assessPortability` den Rumpf
inhaltlich: `SELECT`-Dialekte überlappen weit, ein Teil der Unterschiede lässt
sich sogar umschreiben (`DATE_TRUNC` → `DATE_FORMAT`, `CONCAT` → `||`), und
was übrig bleibt, ist an harten Markern erkennbar (`::`, Backticks, `LIMIT`
gegen T-SQL, `[dbo]`-Quoting).

Für **Funktionen, Prozeduren und Trigger** gibt es diese Beurteilung nicht.
Alle fünf Dialekte prüfen stattdessen die Herkunft und lehnen jeden fremden
Rumpf ab. Damit fällt auch der Rumpf weg, der wörtlich gültig wäre
(`RETURN a + b`), und ein Rumpf **ohne** `source_dialect` geht ungeprüft
durch.

Die Frage, die dieser ADR beantwortet: **hat eine inhaltliche Beurteilung
ohne Übersetzung hier Wert — und wenn nicht, ist das eine dauerhafte Grenze
oder ein Zwischenstand?**

## Entscheidungstreiber

- **Prozedurale Sprachen teilen keine Grundstruktur.** PL/pgSQL, T-SQL,
  PL/SQL und MySQLs Prozedursprache unterscheiden sich in Blockstruktur
  (`DECLARE … BEGIN … END` gegen `BEGIN … END` gegen `AS $$ … $$`),
  Variablendeklaration, Zuweisung (`:=` gegen `SET` gegen `SELECT … INTO`),
  Fehlerbehandlung (`EXCEPTION WHEN` gegen `TRY/CATCH` gegen `DECLARE
  HANDLER`) und Ablaufsteuerung. Der Sichten-Fall ist nicht das kleine
  Vorbild dieses Falls, sondern ein anderer Fall.
- **Ein falsches „portabel" ist teurer als ein falsches „nicht portabel".**
  Wer einen Rumpf fälschlich durchlässt, erzeugt DDL, die das Ziel beim
  `CREATE` ablehnt — mitten in einem `migrate --execute`, nach implizit
  committeten Vorgänger-Anweisungen. Wer ihn fälschlich ablehnt, erzeugt eine
  benannte Nacharbeit mit Hinweis. Die Asymmetrie ist der Kern.
- **Es gibt keine Marker-Liste, die trägt.** Bei `SELECT` markiert `::` eine
  PostgreSQL-Eigenheit. In einem Rumpf ist die Abwesenheit jeder Eigenheit
  kein Beleg für Gültigkeit: `RETURN 1;` ist in PL/pgSQL syntaktisch
  vollständig, in T-SQL aber nur mit `RETURN` als Anweisung im richtigen
  Rahmen, und in MySQL nur innerhalb eines `BEGIN … END`. Eine Beurteilung
  müsste die Grammatik kennen — dann wäre sie schon fast der Übersetzer.
- **Die Herkunft ist eine belastbare Angabe.** Der Reverse setzt sie; ein
  handgeschriebenes Schema kann sie setzen oder weglassen. Sie ist die
  einzige Information, die ohne Grammatik verlässlich ist.

## Entscheidung

1. **Routinen-Rümpfe werden nicht übersetzt.** Ein Rumpf aus einem fremden
   Dialekt wird nicht gerendert, sondern mit `E053` als manuelle Nacharbeit
   ausgewiesen. Das ist eine dauerhafte Grenze, kein Zwischenstand: sie fällt
   erst mit einem echten Transpiler je Sprachpaar, und der ist ein eigenes
   Vorhaben mit eigener Begründung.
2. **Entschieden wird an der Herkunft**, in `RoutineBodyOrigin` — einer
   Stelle für alle fünf Dialekte, damit die Regel nicht je Dialekt
   auseinanderläuft.
3. **Die Herkunft wird aufgelöst, nicht verglichen.** `source_dialect` ist im
   Schema-Format eine freie Zeichenkette; `postgres`, `pg`, `maria`,
   `mariadb`, `sqlite3`, `sqlserver` und jede Groß-/Kleinschreibung meinen
   den Dialekt, den sie nennen — genau wie überall sonst
   (`DatabaseDialect.fromString`). Ein **unbekannter** Wert bleibt fremd.
4. **Kein `source_dialect` heißt „für das Ziel geschrieben".** Das ist die
   dokumentierte Art, eine Routine von Hand zu führen. Sie abzulehnen machte
   jedes handgeschriebene Schema unbrauchbar; sie zu prüfen setzte die
   Grammatik voraus, die es nach Punkt 1 nicht gibt. Der Preis ist benannt:
   ein von Hand geführter Rumpf, der zum Ziel nicht passt, scheitert erst am
   Server.
5. **Sichten bleiben davon unberührt.** `ViewQueryTransformer` beurteilt
   weiter inhaltlich und schreibt weiter um. Der Unterschied ist kein
   Reifegrad, sondern eine Eigenschaft der Sprachen.

## Konsequenzen

- Ein Rumpf, der wörtlich auch auf dem Ziel gültig wäre, fällt weiterhin weg,
  wenn seine Herkunft fremd ist. Wer ihn übernehmen will, entfernt
  `source_dialect` aus der Schema-Datei und übernimmt damit ausdrücklich die
  Verantwortung dafür — das ist der bewusste Weg, nicht die Umgehung.
- Die Aliasse werden jetzt aufgelöst; vorher fiel `source_dialect: postgres`
  gegen ein PostgreSQL-Ziel mit `E053` weg, `source_dialect: postgresql`
  nicht. Schemata, die eine Alias-Schreibweise tragen, rendern ihre Routinen
  ab sofort — eine Verhaltensänderung, die vorher fehlende Objekte
  hinzufügt, keine bestehenden entfernt.
- Ein Transpiler bleibt möglich. Er hebt diesen ADR auf, statt ihn zu
  umgehen: die Entscheidungsstelle ist eine, und sie ist benannt.
