# PostgreSQL: der Reader verliert bei einer Domain die Länge und den Array-Basistyp (Folge von #52)

> **Status:** Draft mit Scope (2026-10-26). Abgetrennt aus dem früheren Sammelplan; ändert die Neutralform des Basistyps und den
> `schema reverse`-Vertrag (ADR 0061 Entscheidung 3 und 4). **Vorbedingung:** keine. **Aktivierung** (Move nach
> `../in-progress/`) beim ersten Implementierungs-Commit; dann GitHub-Issue anlegen und hier verlinken.

## Ziel

Der Reader gibt den Basistyp einer Domain vollständig zurück — mit Länge (`varchar(50)`, `character(5)`) und Array
(`integer[]`). Eine **echte** Änderung daran sieht `schema migrate` (und blockiert mit `AlterCustomType`, Exit 8, weil PostgreSQL
das nicht rendert); eine unveränderte Domain blockiert nicht.

## Probleme (gemessen in #52, P0)

1. **Die Länge geht verloren.** Der Reader liest `information_schema.domains.numeric_*`, nicht `character_maximum_length`;
   `varchar(50)` wird `text`. `varchar(50)` → `varchar(254)` ist gegen eine Datenbank unsichtbar. `char(n)` und `varchar(n)`
   sind über `text` nicht unterscheidbar (`bpchar` pad-Semantik).
2. **Array-Basistyp.** `typbasetype` ist z. B. `_int4`; `PostgresTypeMapping.mapParamType` kennt es nicht, der Reader liefert
   `_int4`, die Datei `integer[]`; die Domain gilt als geändert. Mehrdimensionale Arrays (`integer[][]`) sind im Katalog
   nicht von `integer[]` unterscheidbar (Grenze).
3. Die Quelle `information_schema.domains` ist rechteabhängig (NULL, wenn die Rolle die Domain nicht besitzt/keine Rechte hat);
   weitere Modifikatoren (`timestamp(n)`, `bit(n)`, `time(n)`) gehen ebenfalls verloren.

## Richtung (nach P0 zu entscheiden)

* **Quelle `format_type(t.typbasetype, t.typtypmod)`** aus `pg_catalog` statt `information_schema`: liefert
  `character varying(50)`, `character(5)`, `integer[]`, `numeric(10,2)`, `timestamp(3) without time zone`; der Composite-Zweig
  nutzt sie schon (`PostgresTypeMetadataQueries`, Zeile um 55). Keine Rechteabhängigkeit. **Bevorzugt.**
* **`mapParamType` nicht erweitern:** es wird auch von den Routinen-Readern benutzt (Parameter-/Rückgabetypen von Funktionen);
  eine Änderung dort verschöbe deren Fingerabdrücke. Die Domain-Abbildung bekommt eine eigene Funktion.
* **Neutralform** ([`CustomTypeBaseType`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/CustomTypeBaseType.kt)):
  `char`/`bpchar` und `varchar` trennen (heute beide `text`), `[]`-Suffix abtrennen, Kern abbilden, `[]` wieder anhängen
  (`int4[]` = `integer[]`). **`sameParameters` wird strenger:** heute gilt „fehlende Klammer = unbekannt, nicht verschieden"
  (ADR 0061 Entscheidung 4, Begründung „der Katalog nennt sie nicht"); liefert der Reader die Länge, ist `varchar(50)` gegen
  `text`/`varchar` ohne Länge ein Unterschied — alte Reverse-Dateien (`text`) und Autorendateien ohne Länge gälten als geändert.
  Das ist eine Vertragsänderung, kein Nachtrag.
* **Fingerabdruck:** `CanonicalPayload`/`MigrationFingerprint` tragen `base=` nur als Typname ohne Klammer; trägt der Abdruck
  die Länge, ändert sich das Layout → neuer Tag (nächster freier) und ADR.
* **ADR:** ein **neuer** ADR (nächste freie Nummer, nach 0063), der ADR 0061 Entscheidung 3/4 ergänzt; ein Nachtrag im
  akzeptierten ADR ist wegen `doc-immutable` nicht möglich.

## Phasen

* **P0 Messung** (Integrationstest gegen PostgreSQL): `varchar(n)`, `char(n)`, `text`; `integer[]`, `text[]`, `integer[][]`,
  `numeric(10,2)[]`, `varchar(n)[]`; Domain in einem anderen Schema als `public` (Reader filtert ein Schema); `timestamp(3)`,
  `bit(n)`; rechtearme Rolle (Quelle `information_schema` liefert NULL?). Je Fall Reader-Ausgabe, Diff gegen die Autorenform,
  Verhalten von `schema generate` für die gelesene Domain (Round-Trip). P0 endet, wenn die Tabelle voll ist.
* **P1 Entscheidung:** Quelle, `sameParameters`-Regel, Neutralform, Tag/ADR, Übergang für Bestandsdateien (`text` statt
  `varchar(n)` aus früheren Reverse-Läufen).
* **P2 Umsetzung:** Unit-Tests (Reader, `CustomTypeBaseType`, Abdruck), Integrationstest (zweiter Lauf `operationsTotal` 0,
  Rückweg, echte Änderung der Länge/des Array-Typs blockiert), Test „alte Reverse-Datei gegen neuen Reader".
* **P3 Doku:** Handbuch §3.21 (Grenzen „Länge" und „Array-Domain" nachführen, nur Ist-Zustand), `CHANGELOG.md`,
  `spec/schema-reference.md` (Domain: Länge/Array im Vertrag) und `spec/ddl-generation-rules.md` §6.3 **mit** dem Code.

## Nicht-Scope

Mehrfach-CHECK (`postgres-domain-mehrfach-check.md`); `DEFAULT`/`NOT NULL`/Kollation (`domain-default-not-null-modell.md`);
`AlterCustomType` rendern; andere Dialekte.

## Akzeptanz

* Jede gemessene Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt; absichtlich falsche
  Zusicherung einmal rot gesehen.
* Eine echte Änderung (Länge, Array-Typ, `char` ↔ `varchar`) blockiert weiter (Exit 8, `AlterCustomType`).
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne `MODULES=`,
  `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"` (ganzes Modul, Detekt eingeschlossen),
  `make solid-suppression-gate`, `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
