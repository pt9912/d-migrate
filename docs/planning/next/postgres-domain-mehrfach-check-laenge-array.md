# PostgreSQL: Domain-Reader verliert CHECKs, Länge und Array-Basistyp (Folge von #52)

> **Status:** Draft mit Scope (2026-10-25). Folge-Slice zu #52
> (`postgres-domain-autorenform-gegen-katalog.md`, [ADR 0061](../../adr/0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md)):
> dort als „offen“ abgetrennt. **Vorbedingung:** #52 ist auf `main`. **Aktivierung**
> (Move nach `../in-progress/`) beim ersten Implementierungs-Commit. Ein GitHub-Issue
> ist noch nicht angelegt; beim Aktivieren anlegen und hier verlinken.

## Ziel

Der PostgreSQL-Reader gibt eine Domain vollständig zurück: alle ihre `CHECK`-Constraints, die
Länge von `varchar(n)`/`char(n)` und einen Array-Basistyp. Eine **echte** Änderung daran sieht
`schema migrate` dann (und blockiert mit `AlterCustomType`, Exit 8, weil PostgreSQL das nicht
rendert); eine unveränderte Domain mit diesen Merkmalen blockiert nicht.

## Probleme (gemessen in #52, P0)

1. **Mehrere `CHECK`-Constraints gehen verloren.** `PostgresTypeMetadataQueries.listDomainTypes`
   verbindet `pg_constraint` ohne Aggregation (`LEFT JOIN … contype = 'c'`); zwei CHECKs ergeben
   zwei Zeilen, `readPostgresCustomTypes` schreibt `result[name] = …` und behält die letzte
   (gemessen: `CHECK (VALUE > 0)` + `CHECK (VALUE < 10)` → nur `VALUE < 10`). Ein Reader-Fehler,
   der im Vergleich unsichtbar ist: eine Datei mit beiden CHECKs gilt als geändert, eine mit dem
   ersten wird übersehen.
2. **Die Länge geht verloren.** Der Reader liest `character_maximum_length` nicht; `varchar(50)`
   wird `text`. Ändert jemand `varchar(50)` → `varchar(254)`, sieht der Vergleich gegen eine
   Datenbank nichts (Datei gegen Datei sieht es seit #52, wenn beide die Länge nennen).
3. **Array-Basistyp.** `typbasetype` ist z. B. `_int4`; `mapParamType` kennt das nicht, der Reader
   liefert `_int4`, die Datei schreibt `integer[]` — gemessen: `baseType` verschieden, Domain
   gilt als geändert.
4. **Modelllücke:** `CustomTypeDefinition` kennt weder `DEFAULT` noch `NOT NULL` noch Kollation
   einer Domain (`domain_default` wird selektiert, nirgends verwendet). Eine Änderung daran ist im
   Diff unsichtbar. Ob das Modell erweitert wird, ist Teil von P1 (Vertrag: `spec/schema-reference.md`).

## Richtung (nach P0 zu entscheiden)

* **Mehrfach-CHECK:** der Reader aggregiert (`string_agg`) und führt die Prädikate mit `AND`, je in
  Klammern (`(VALUE > 0) AND (VALUE < 10)`) — passend zu der Form, die der Server für ein
  zusammengesetztes Prädikat schreibt. Reihenfolge nach Constraint-Name (stabil). Der Renderer
  schreibt einen `CHECK` — ein Round-Trip fasst die Constraints zu einem zusammen (zu messen: ob
  das ein Drift gegen die Datenbank erzeugt, die zwei benannte CHECKs trägt).
* **Länge:** die Länge in den Basistyp (`varchar(50)`), wie es die Neutralform seit #52 zulässt
  (`CustomTypeBaseType` trägt die Klammer); der Reader liefert `varchar(50)` statt `text`. Ändert
  den `schema reverse`-Vertrag und die Neutralform der Typen mit Länge.
* **Array:** `_int4` → `integer[]` im Reader (und die Neutralform für `…[]`).
* **Modell:** `default`/`not_null` an der Domain nur, wenn der Vertrag (`spec/schema-reference.md`,
  alle Dialekte) das trägt — sonst Grenze im Handbuch.

Nicht-Ziel: `AlterCustomType` für PostgreSQL rendern (`ALTER DOMAIN`); Faltung von `BETWEEN`/`IN`
(ADR 0061 Entscheidung 2).

## Phasen

* **P0 Messung** (Integrationstest gegen PostgreSQL, Muster
  `PostgresCustomTypeFileFormMigrateIntegrationTest`): zwei CHECKs (benannt, unbenannt, gleiche
  Spalte), drei CHECKs; `varchar(n)`, `char(n)` mit Länge; `integer[]`, `text[]`, `integer[][]`;
  `DEFAULT` und `NOT NULL` an einer Domain. Je Fall: Reader-Ausgabe, Diff gegen Autorenform,
  Verhalten von `schema generate` für die gelesene Domain (Round-Trip). Endet, wenn jede Zeile
  gemessen ist.
* **P1 Entscheidung:** Richtung je Befund; Vertrag für `schema reverse`; Modell erweitern oder
  Grenze; ADR-Bedarf (eigener Nachtrag zu 0061, wenn die Neutralform sich ändert);
  Fingerabdruck-Tag (`v20`), wenn sich der Hash echter Eingaben ändert.
* **P2 Umsetzung** nach P1, mit Unit-Tests im Reader, Integrationstest (zweiter Lauf
  `operationsTotal=0`, Rückweg, echte Änderung blockiert weiter).
* **P3 Doku:** Handbuch §3.21 (die Grenzen „mehrere CHECK-Constraints“, „Länge“, „Array-Domain“
  aus #52 nachführen), `CHANGELOG.md`, `spec/schema-reference.md` und `spec/ddl-generation-rules.md`
  §6.3 prüfen.

## Nicht-Scope

* `AlterCustomType` auf PostgreSQL rendern.
* Andere Dialekte (nur PostgreSQL liest Typ-Objekte).
* Composite und Enum (erledigt mit #51).

## Akzeptanz

* Jede in P0 als Fehler gemessene Zeile hat einen Test, der ohne die Änderung an der erwarteten
  Stelle fehlschlägt; die neuen Tests werden einmal mit absichtlich falscher Zusicherung rot gesehen.
* Eine echte Änderung (anderer zweiter `CHECK`, andere Länge, anderer Array-Typ) blockiert weiter.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne
  `MODULES=`, `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"`,
  `make solid-suppression-gate`, `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
