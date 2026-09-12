---
id: primaerschluessel-ohne-required
title: "Ein Primärschlüssel ohne `required` erzeugt einen Unterschied, der keiner ist"
status: done
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

## Nachgemessen (2026-09-12): `schema migrate` war nie betroffen

Die offene Frage ist beantwortet, und die Antwort macht den Schnitt kleiner:
**`schema migrate` plant nichts.** Sein Vergleich läuft ziel-bewusst, und der
zielbewusste Modus faltete PK-impliziertes `required` schon immer weg
(`effectiveRequiredEqual`); der Fingerabdruck tut es seit v7 sogar unbedingt.
Betroffen war allein der **strikte** Vergleich, also `schema compare` — und
damit der Bericht, nicht die Datenbank.

Damit standen zwei der drei Projektionen gegeneinander: Fingerabdruck und
zielbewusster Vergleich falteten, der strikte Vergleich nicht.

## Die eigentliche Ursache war doppelt

**PostgreSQL hielt die Reverse-Konvention nur zur Hälfte.** Alle fünf Leser
folgen der Regel „ein Primärschlüssel impliziert `required` und `unique`, also
behaupte keines von beiden noch einmal" (MySQL-Präzedenz). PostgreSQL faltete
`unique` — und `required` nicht:

```kotlin
val required = (row["is_nullable"] as String) == "NO"      // vorher
val unique = if (isPrimaryKeyColumn) false else …          // schon immer gefaltet
```

Das ist der Grund, warum der Befund ausgerechnet am PostgreSQL-Smoke auffiel
und nicht an den anderen vier.

**Und die Gegenrichtung gab es auch.** Hätte `minimal.yaml` `required: true`
ausgeschrieben, zeigte derselbe Scheindiff gegen MySQL, SQLite, SQL Server und
Oracle — mit vertauschten Seiten. Den PostgreSQL-Leser allein zu berichtigen
hätte den Befund also nur verschoben.

## Gebaut: die Projektion — und die Prüfung ausdrücklich nicht

**Projektion (Weg 1), beide Seiten, beide Modi.** `required` vergleicht jetzt
immer effektiv (`required || Spalte ∈ effektiver PK`), nicht mehr nur
ziel-bewusst. Damit sagen alle drei Projektionen dasselbe. Der Einwand „gilt
das für alle fünf gleich?" ist beantwortet: für SQLite gilt es im Server nicht,
im gerenderten DDL aber schon — `SqlitePrimaryKeyNullability.materialize`
schreibt das `NOT NULL` hin. Die Projektion hängt also an der Form, nicht am
Dialekt, und braucht kein Fähigkeitsflag.

**Dazu der PostgreSQL-Leser**, der jetzt dieselbe Konvention hält wie die
anderen vier.

**Prüfung (Weg 2) wird nicht gebaut** — und zwar nicht aus Aufwandsgründen. Die
Regel sollte eine PK-Spalte *ohne* `required: true` melden. Genau diese
Schreibweise ist aber die, die **jeder** der fünf Reverse-Leser erzeugt: sie
schreiben an einer PK-Spalte weder `required` noch `unique` aus. Eine Warnung
darüber träfe damit jedes zurückgelesene Schema und widerspräche der eigenen
Konvention. Was stattdessen nötig war, ist ein Satz in der Schema-Referenz:
beide Schreibweisen bedeuten dasselbe.

## Was bewusst nicht mitwandert

`CanonicalPayload` behält den rohen `required`-Wert. Die Identität einer
Operation ist keine Vergleichsentscheidung; sie zu projizieren änderte
Operations-IDs und entwertete bestehende Overlays. Dieselbe Linie hält der
Payload schon beim PK selbst — er schreibt `t.primaryKey`, nicht den
effektiven.

## Belegt

`PostgresPrimaryKeyRequiredIntegrationTest` (live, PostgreSQL 18): der Server
meldet für die PK-Spalte wirklich `is_nullable = 'NO'`, der Leser faltet es
weg, und ein strikter Vergleich der Fixture gegen die aus ihr erzeugte
Datenbank findet keinen Tabellenunterschied — mit und ohne ausgeschriebenes
`required`. Sabotage-geprüft: beide Hälften einzeln zurückgedreht, und der
Befund erscheint wortgleich wieder (`required=ValueChange(before=true,
after=false)` an `users.id`).

## Nicht-Umfang

- Andere implizite Server-Zusicherungen (etwa `UNIQUE` auf einer nullable
  Spalte). Sie mögen dieselbe Form haben, sind aber nicht gemessen.

## Herkunft

Gefunden beim Abarbeiten der Vorab-Checks für 1.3.0: der DB-Smoke aus
`releasing.md` 3.3 meldete `DIFFERENT`, wo eine aus derselben Fixture erzeugte
Datenbank verglichen wurde.
