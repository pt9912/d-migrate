---
id: pg-diff-object-key-leak
title: "PostgreSQL-Diff-Pfad emittiert den kanonischen Key als Bezeichner"
status: done
---

# PostgreSQL-Diff-Pfad emittiert den kanonischen Key als Bezeichner

> **Behoben 2026-08-30.** Alle sieben Emissionsstellen dekodieren jetzt ueber
> `ObjectKeyCodec` — `PostgresTriggerDdlHelper` (Create, Drop, Drop+Create-
> Fallback), `PostgresDiffFunctionOps` und `PostgresDiffProcedureOps` (je Create
> und Drop). Unit-Specs bauen den `objectRef` mit dem kanonischen Key;
> `PostgresDiffCanonicalKeyIntegrationTest` belegt den Drop gegen echtes
> PostgreSQL. Ohne den Fix meldet der Server dort
> `trigger "users::last_updated" for table "users" does not exist`.
>
> MySQL und SQLite sind **nicht** betroffen: dort emittiert auch der
> Generate-Pfad den Key, weil ihr Trigger-Namensraum schemaweit ist. Der
> Widerspruch bestand nur in PostgreSQL, zwischen seinem eigenen Generate- und
> Diff-Pfad.

## Lage

Das neutrale Modell keyt Trigger als `tabelle::name` und Routinen als
`name(in:typ,…)`, damit gleichnamige Objekte nebeneinander stehen können. Der
**Generate**-Pfad dekodiert den Key vor der Emission
(`ObjectKeyCodec.triggerName` / `routineName`). Der **Diff-/Migrate**-Pfad tut
das nicht: er setzt `op.objectRef.rootName` — also den Key — direkt als
Bezeichner ein.

Gemessen gegen `PostgresDiffDdlGenerator`, Eingabe sind die Keys, die alle vier
Reverse-Reader erzeugen:

```
CREATE TRIGGER "users::last_updated" BEFORE UPDATE ON "users" …
DROP TRIGGER "users::last_updated" ON "users";
CREATE FUNCTION "calc(in:integer)"(p_id integer) …
CREATE PROCEDURE "touch(in:integer)"(p_id integer) …
DROP FUNCTION "calc(in:integer)"(integer);
DROP PROCEDURE "touch(in:integer)"(integer);
```

Kein Blocker, keine Warnung — der Lauf hält sich für erfolgreich.

Das `DROP` wiegt schwerer als das `CREATE`: es soll ein Objekt entfernen, das
in der Datenbank `last_updated` heißt. Es scheitert zur Laufzeit, nicht bloß
kosmetisch.

## Warum es niemandem auffiel

Erreichbar ist das über den dokumentierten Weg — `schema reverse` schreibt die
kanonischen Keys, `schema migrate` liest sie wieder. Ein handgeschriebenes YAML
mit blanken Namen trifft es nicht, und genau so bauen die vorhandenen Tests
ihre `objectRef`s: mit blankem Namen. Der Fall ist ungetestet, nicht falsch
getestet.

## Umfang

- `PostgresTriggerDdlHelper.emitCreate/emitDrop`, `buildCreateSql`.
- `PostgresDiffFunctionOps` (Create/Replace/Drop) und `PostgresDiffProcedureOps`
  — dieselbe Stelle, `ctx.sql.quote(ref.rootName)`.
- Tests, die den `objectRef` mit dem **kanonischen** Key bauen, nicht mit dem
  blanken Namen. Ohne die schlägt der Fix nirgends an.
- Prüfen, ob MySQL und SQLite dieselbe Stelle haben. Beide keyen Trigger
  ebenfalls über `ObjectKeyCodec.triggerKey`, haben aber einen **schemaweiten**
  Trigger-Namensraum — dort ist die richtige Antwort möglicherweise eine andere
  als das blanke Dekodieren.

## Herkunft

Als Restfläche des Trigger-Namens-Defekts benannt, dort aber nur für Trigger und
in einem abgeschlossenen Dokument abgelegt — siehe
[`../done/sample-db-roundtrip-findings.md`](../done/sample-db-roundtrip-findings.md).
Dass Funktionen und Prozeduren dieselbe Form haben, ist hier zum ersten Mal
gemessen.
