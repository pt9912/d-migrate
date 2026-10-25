---
status: accepted
date: 2026-10-25
decision-makers: pt9912
consulted: docs/adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md, docs/adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md, docs/adr/0060-sqlite-ref-type-enum-wertevorrat-gegen-datenbank.md, docs/planning/in-progress/postgres-domain-autorenform-gegen-katalog.md
informed: hexagon/core (SchemaComparator, CustomTypeBaseType, CanonicalPayload, MigrationFingerprint), adapters/driven/driver-postgresql (PostgresSchemaStructureReaders, PostgresDomainCheckForm), spec/ddl-generation-rules.md, docs/user/anwenderhandbuch.md, CHANGELOG.md
---

# Eine Domain in Autorenform gilt als dieselbe Domain — Reader-Normalform für den `CHECK`, Neutralform für den Basistyp, `schema-fingerprint-v19`

> **Status: accepted (2026-10-25).** Der PostgreSQL-Reader gibt den `CHECK` einer
> Domain in der Schreibweise eines Autors zurück und `precision`/`scale` nur für
> `decimal`. Vergleich und Abdruck setzen den Basistyp in Neutralform ein. Das ändert
> den Abdruck jedes Schemas mit einer Domain; der Algorithmus-Tag steigt auf `v19`.
> [ADR 0056](0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md) bleibt
> unberührt.

## Kontext und Problemstellung

Eine Domain, die Datei und PostgreSQL-Datenbank gleich ausdrücken, plante
`schema migrate` als `AlterCustomType` und endete mit Exit 8 (Issue #52), sobald die
Datei sie anders schrieb als der Katalog. Gemessen (20 Fälle, Datenbank gegen Soll in
Autorenform) wichen vier Felder ab:

- `precision`/`scale`: der Katalog füllt sie auch für `integer` (32/0), `bigint`, `smallint`
  und die Gleitkommatypen; eine Datei schreibt sie nur für `decimal`.
- `check`: `pg_get_constraintdef` liefert eine Klammerebene mehr (`CHECK ((VALUE > 0))`) und
  Literale mit Cast (`(VALUE >= (0)::numeric)`).
- `baseType`: der Reader liefert die Neutralform (`biginteger`, `text`, `decimal`), die Datei
  darf die Synonyme schreiben, die der Renderer annimmt (`bigint`, `varchar(50)`, `numeric`).
- Der Fingerabdruck trug den Basistyp roh; der Post-Compare eines Laufs endete deshalb in
  `POST_EXECUTE_DRIFT`.

ADR 0056 hält fest, dass `schema migrate` die Frage „hat sich etwas geändert?" nicht über
eine lokale Normalisierung roher SQL-Texte beantwortet (Entscheidung 1), weil dafür ein Parser
nötig wäre. Der `CHECK` einer Domain ist so ein Text.

## Betrachtete Optionen

1. **Den `CHECK` im Vergleich falten** (`RawTextFolding`, Herkunft oder Serverform) — verworfen:
   im Standardfall fehlen Herkunft (nur nach einem früheren Lauf) und Serverform (nur mit der
   Sandbox), der erste Lauf bliebe ein `AlterCustomType`; die Sandbox gilt nur für Live-Ziele und
   widerspricht ADR 0057. Ein neuer Objekttyp im Herkunftsformat ändert ein persistiertes Overlay.
2. **Lokale Kanonisierung im Migrate-Vergleich** — verworfen: das schließt ADR 0056 aus.
3. **Reader-Normalform und Neutralform des Basistyps** — gewählt.
4. **Grenze dokumentieren, nichts ändern** — verworfen: der einfache Fall (`integer`, `CHECK
   (VALUE > 0)`) blockiert sonst jede Migration einer Datenbank mit einer Domain.

## Entscheidung

1. **Reader-Normalform, kein Vergleichsschritt.** `normalizeDomainCheck` entfernt wie zuvor das
   `CHECK`-Token und bringt das Prädikat auf Autorenform (`PostgresDomainCheckForm`): überflüssige
   äußere Klammern und der Cast an einfachen Zahl- und Textliteralen entfallen. Das ist die
   Normalform dessen, was der Reader dem Modell übergibt — dieselbe Stelle, an der schon bisher
   `CHECK` und eine Klammerebene wegfielen. Der Vergleich in `schema migrate` normalisiert
   weiterhin nichts (ADR 0056 Entscheidung 1 gilt unverändert).
2. **Zurückhaltend.** Ein Cast fällt nur an einem Standardliteral, nur ohne Typmodifikator
   (`::character varying(10)`) und Array-Klammer, nur ohne Rechenoperator (`/ % ^`) im Ausdruck,
   und negative Zahlen bleiben unverändert. Was der Server darüber hinaus umschreibt (`BETWEEN`,
   `IN (…)` → `= ANY (ARRAY[…])`, ein Cast an `VALUE`, `AND`/`OR` ohne Serverklammern), bleibt
   Katalogform und gilt als Änderung — dokumentierte Grenze.
3. **`precision`/`scale` nur bei `decimal`.** Der Reader setzt sie nur für `decimal`; der Vergleich
   und der Abdruck werten sie nur bei `decimal` und lesen sie dort auch aus der Klammer des Typs
   (`numeric(10,2)`). Eine alte Reverse-Datei mit `precision: 32` an einer `integer`-Domain stört
   damit nicht.
4. **Neutralform des Basistyps** (`CustomTypeBaseType`): Typname klein, ohne Klammer, Synonyme auf
   die Form des Readers (`bigint`/`int8` → `biginteger`, `varchar`/`char`/`bpchar` → `text`,
   `numeric` → `decimal`, `real`/`double precision` → `float`, `jsonb` → `json`, `bytea` → `binary`).
   Die Länge einer Klammer (`varchar(50)`) zählt nur, wenn **beide** Seiten sie nennen; der Katalog
   nennt sie nicht.
5. **Fingerabdruck-Bump.** `ALGORITHM` steigt von `schema-fingerprint-v18` auf `v19`. Eine Domain
   hasht auf jedem Dialekt anders (Neutralform, `precision`/`scale` nur bei `decimal`); Schemas ohne
   Domain bleiben bis auf den Tag byte-gleich. Ein `v18`-Rollback-Artefakt liefert die bestehende
   Meldung `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`.

## Konsequenzen

- `schema migrate` gegen PostgreSQL führt eine rein additive Änderung auch dann aus, wenn die Datei
  eine Domain in Autorenform schreibt; Folgelauf ohne Drift.
- Sichtbar ändert sich `schema reverse`: der Domain-`CHECK` steht in Autorenform, `precision`/`scale`
  entfallen bei einer Domain über `integer`, `bigint`, `smallint` oder einen Gleitkommatyp. Die
  erzeugte DDL bleibt gleich (der Renderer ignoriert beides für diese Typen).
- Ein Rollback- oder Plan-Artefakt vor `v19` ist neu zu erzeugen.
- Weiter nicht erkannt: die `varchar`-Länge aus dem Katalog, mehrere `CHECK`-Constraints an einer
  Domain (der Reader behält den letzten), `DEFAULT`/`NOT NULL` einer Domain (das Modell kennt sie
  nicht). Eine echte Änderung einer Domain blockiert auf PostgreSQL weiter (`AlterCustomType` ist
  nicht gerendert).

## Weitere Informationen

Grenzt sich von [ADR 0056](0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md) ab (Reader
statt Vergleich) und ergänzt [ADR 0060](0060-sqlite-ref-type-enum-wertevorrat-gegen-datenbank.md)
(Fingerabdruck-Bump). Plan:
[`postgres-domain-autorenform-gegen-katalog.md`](../planning/in-progress/postgres-domain-autorenform-gegen-katalog.md).
