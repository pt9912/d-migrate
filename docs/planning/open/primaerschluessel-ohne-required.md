---
id: primaerschluessel-ohne-required
title: "Ein Primärschlüssel ohne `required` erzeugt einen Unterschied, der keiner ist"
status: open
---

# Ein Primärschlüssel ohne `required` erzeugt einen Unterschied, der keiner ist

## Der Befund (gemessen 2026-09-11)

Der Release-Smoke aus [`releasing.md`](../../user/releasing.md) Abschnitt 3.3
legt eine PostgreSQL-Datenbank aus `minimal.postgresql.sql` an — erzeugt aus
`minimal.yaml` — und vergleicht die Fixture danach gegen die Datenbank. Das
Ergebnis ist nicht `IDENTICAL`:

```
Status: DIFFERENT
Summary: 1 change(s)
Tables:
  ~ users:
      ~ column id:
          required: false -> true
```

Die Fixture führt `id` im `primary_key`, lässt `required` aber unbesetzt:

```yaml
    columns:
      id:
        type: identifier
        auto_increment: true
    primary_key: [id]
```

PostgreSQL macht aus `PRIMARY KEY` zwingend `NOT NULL`. Der Rückleser meldet
also wahrheitsgemäß `required: true` — und der Vergleich sieht einen
Unterschied, den niemand herstellen oder auflösen kann. Der Server *kann* die
Spalte nicht nullable machen, solange sie Primärschlüssel ist.

## Warum das mehr ist als ein Schönheitsfehler

Es ist dieselbe Klasse wie die Nicht-Konvergenz bei rohem SQL-Text, die
[`raw-sql-text-drift.md`](../done/raw-sql-text-drift.md) ausgeräumt hat: der
Vergleich meldet bei jedem Lauf dasselbe, weil die beiden Seiten
unterschiedliche Dinge über dieselbe Sache sagen.

**Der Renderer weiß es bereits.** Für SQLite schreibt er `NOT NULL` an
PK-Spalten ausdrücklich hin — weil SQLites `PRIMARY KEY` es als einziger
Dialekt *nicht* impliziert (0.9.10-Fix, m-trace-Consumer-Befund). Das Wissen
„ein Primärschlüssel ist nie nullable" steckt also im Rendern, aber nicht im
Modell und nicht im Vergleich.

## Was nicht gemessen ist

Nur `schema compare` ist belegt. **Ob `schema migrate` daraus eine Operation
plant** — und was die auf einem Server anrichtet, der die Spalte gar nicht
nullable machen kann — ist offen und gehört als Erstes gemessen. Denkbar sind
beide Ausgänge: eine geplante `ALTER COLUMN … DROP NOT NULL`, die der Server
ablehnt, oder ein Diff, der folgenlos im Bericht endet.

## Die Gabelung

1. **Projektion.** Die Zugehörigkeit zum Primärschlüssel impliziert
   `required: true`, und zwar auf **beiden** Vergleichsseiten. Dann
   verschwindet der Unterschied, ohne dass jemand seine Schemadatei ändert.
   Die Frage dabei: gilt das für alle fünf Dialekte gleich? Für SQLite gilt es
   im Server nicht, im gerenderten DDL aber schon — die Projektion gehört also
   an die Form, nicht an den Dialekt.
2. **Prüfung.** Eine Validierungsregel, die eine PK-Spalte ohne
   `required: true` ablehnt oder meldet. Ehrlicher gegenüber dem Autor, aber
   sie bricht bestehende Schemadateien — und `minimal.yaml` ist eine davon.

Beides schließt sich nicht aus: die Projektion räumt den Scheindiff aus, die
Meldung sagt dem Autor, dass seine Datei etwas behauptet, das nirgends gilt.

## Nicht-Umfang

- Andere implizite Server-Zusicherungen (etwa `UNIQUE` auf einer nullable
  Spalte). Sie mögen dieselbe Form haben, sind aber nicht gemessen.

## Herkunft

Gefunden beim Abarbeiten der Vorab-Checks für 1.3.0: der DB-Smoke aus
`releasing.md` 3.3 meldete `DIFFERENT`, wo eine aus derselben Fixture erzeugte
Datenbank verglichen wurde.
