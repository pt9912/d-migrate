---
id: routine-identity-fields-not-in-schema-json
title: "Der Reverse schreibt Routinen-Felder, die spec/schema.json verbietet"
status: done
---

# Der Reverse schreibt Routinen-Felder, die `spec/schema.json` verbietet

> **Behoben 2026-08-30.** `spec/schema.json` fuehrt `security`, `definer`,
> `search_path` und `sql_mode` jetzt bei `function` und `procedure`; das
> Voll-Feature-Fixture des Contract-Tests setzt sie, damit die Luecke nicht
> wieder ungetestet bleibt. Ohne den Schema-Eintrag meldet der Test jetzt
> `property 'security' is not defined in the schema`.
>
> Der PostgreSQL-Generate-Pfad emittiert den gepinnten `search_path` — und
> Prozeduren tragen dieselben Attribute wie Funktionen, was vorher gar nicht
> galt. Der **Diff**-Pfad hatte beides laengst; die Abweichung lag umgekehrt zu
> der, die ich erwartet hatte.

## Lage

`SchemaNodeProgrammabilityBuilders.writeRoutineIdentityAttributes` schreibt für
Funktionen **und** Prozeduren vier Felder, sobald sie gesetzt sind:

```
security   definer   search_path   sql_mode
```

`spec/schema.json` führt bei `function` und `procedure` keines davon, und beide
Definitionen stehen auf `additionalProperties: false`. Ein Schema-File, das
`d-migrate schema reverse` aus einer PostgreSQL-Datenbank mit einer
`SECURITY DEFINER`-Funktion schreibt — oder aus MySQL mit `sql_mode` —, wird vom
veröffentlichten JSON-Schema **abgelehnt**.

Der Contract-Test bemerkt es nicht: sein Voll-Feature-Fixture setzt die vier
Felder nicht.

## Zweite Hälfte: `search_path` wird nie emittiert

`FunctionDefinition.searchPath` wird vom PG-Reverse erfasst und vom Modell
getragen, aber `PostgresRoutineDdlHelper.generateFunction` emittiert es nicht —
die Attributliste kennt nur Volatilität, Strictness und `SECURITY DEFINER`. Für
eine `SECURITY DEFINER`-Funktion ist ein gepinnter `search_path` die
Standard-Absicherung; sie fällt beim Round-Trip weg.

## Umfang

- `spec/schema.json`: die vier Felder bei `function` und `procedure` aufnehmen,
  mit denselben Wertebereichen, die das Modell kennt (`security`:
  `invoker | definer`).
- Contract-Test-Fixture um die Felder erweitern, sonst bleibt die Lücke
  ungetestet.
- `search_path` im PG-Generate emittieren (`SET search_path TO …` als
  Funktionsattribut).
- Prüfen, ob dieselbe Asymmetrie bei anderen Objektarten steht: das Muster ist
  „Modell und Writer kennen ein Feld, `schema.json` nicht".

## Herkunft

Als Restfläche des Funktions-Attribut-Verlusts benannt und dort als
„vorbestehender Spec-Drift" abgelegt — siehe
[`../done/sample-db-roundtrip-findings.md`](../done/sample-db-roundtrip-findings.md).
Dass daraus eine **Ablehnung** durch das eigene Schema folgt, steht dort nicht.
