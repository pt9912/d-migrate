# Tracker: Parquet-Einzeldatei-Import bricht mit ClassCastException ab

> **Status:** Befund mit Repro (Draft) / Trigger Watch (2026-08-15)
> **Trigger:** Beim funktionalen Nachweis der Hadoop-Ausschlüsse
> ([dependency-cve-exposure-shipped-artifact.md](dependency-cve-exposure-shipped-artifact.md))
> als Nebenbefund aufgefallen. **Nicht** von jenem Eingriff verursacht — zwei
> Kontrollläufe gegen das unveränderte `pt9912/d-migrate:1.0.0` scheitern identisch.
> **Aktivierungsbedingung** (Move nach `../next/`): Entscheidung, ob der
> Einzeldatei-Import Typen aus dem Parquet-Schema ableiten soll oder ob er ohne
> Manifest sauber abgelehnt gehört.

## Symptom

```
data import --target sqlite:///w/dst.db --format parquet \
            --source /w/out/t.parquet --table t

Error: Import failed: class org.apache.parquet.example.data.simple.IntegerValue
       cannot be cast to class org.apache.parquet.example.data.simple.BinaryValue
```

Kein Datensatz wird geschrieben; die Zieltabelle bleibt leer.

## Repro (vollständig, ohne Projektkontext)

Quelle: SQLite mit einer Tabelle aus `INTEGER PRIMARY KEY`, `TEXT`, `REAL`,
gefüllt mit 500 Zeilen.

```bash
d-migrate data export --source "sqlite:///w/src.db" --format parquet \
          --tables t -o /w/out --split-files      # -> t.parquet + manifest.yaml
d-migrate data import --target "sqlite:///w/dst.db" --format parquet \
          --source /w/out/t.parquet --table t     # -> ClassCastException
```

## Abgrenzung — was funktioniert

Der **Verzeichnis-Import derselben Ausgabe** läuft in beiden Images fehlerfrei
durch und schreibt alle 500 Zeilen mit korrekten Prüfsummen:

```bash
d-migrate data import --target "sqlite:///w/dst.db" --source /w/out
```

Der Unterschied ist die `manifest.yaml`, die beim Verzeichnis-Import die
Spaltentypen mitliefert. Der Einzeldatei-Pfad hat sie nicht und leitet die Typen
offenbar falsch ab: Eine Spalte, die als `IntegerValue` im Parquet steht, wird als
`BinaryValue` gelesen. Die Vermutung ist eine Positions- statt Namenszuordnung oder
ein pauschaler Fallback auf „Text", belegt ist sie nicht.

## Warum das nicht als „vom CVE-Eingriff verursacht" durchgeht

Der Befund tauchte unmittelbar nach dem Ausschluss der Hadoop-Bäume auf, und der
erste Verdacht war eine fehlende Klasse. Er ist widerlegt:

1. Die Ausnahme ist ein **ClassCastException**, kein `NoClassDefFoundError` — es
   fehlt nichts, es wird falsch gelesen.
2. Derselbe Import mit dem **unveränderten** `pt9912/d-migrate:1.0.0` scheitert
   zeichengleich.
3. Auch ein reiner 1.0.0-Round-Trip (Export *und* Import mit 1.0.0, ohne jede
   Beteiligung des neuen Images) scheitert zeichengleich.

Der Defekt steckt also im veröffentlichten 1.0.0 und ist älter als dieser Tag.

## Wege

1. **Typen aus dem Parquet-Schema ableiten.** Die Datei trägt ihr Schema selbst; der
   Einzeldatei-Pfad müsste es lesen, statt sich auf das Manifest zu verlassen. Macht
   den dokumentierten Aufruf funktionsfähig.
2. **Ohne Manifest sauber ablehnen.** Wenn der Einzeldatei-Import für Parquet nie
   tragfähig gedacht war, gehört eine verständliche Fehlermeldung hin statt einer
   durchgereichten ClassCastException — und der Hinweis auf den Verzeichnis-Import.

Weg 1 ist die Auflösung, Weg 2 das Minimum. Beides ist besser als der heutige
Zustand, in dem ein in `--help` angebotener Aufruf mit einer internen Cast-Meldung
scheitert.

## Verwandt — dieselbe Bewegung könnte drei Dinge lösen

Ein Sprung auf **parquet-java 1.18.x** steht inzwischen aus drei unabhängigen Gründen
im Raum, und dieser Defekt ist einer davon:

1. Der Hadoop-Klotz unter `formats-parquet` (Weg 3 in
   [dependency-cve-exposure-shipped-artifact.md](dependency-cve-exposure-shipped-artifact.md)).
2. **Geshadetes Jackson 2.21.3 in `parquet-jackson`** — drei HIGH, die kein eigener
   Pin erreicht; als begründete Ausnahme in `.trivyignore.yaml` hinterlegt.
3. Dieser Lesepfad-Defekt, falls er in 1.18.x behoben oder anders geschnitten ist.

Ob 1.18.x hier wirklich hilft, ist **nicht geprüft** — die drei Gründe rechtfertigen
aber, es gemeinsam zu bewerten statt dreimal einzeln.

## Offen

- Ob andere Ziel-Dialekte (PostgreSQL, MySQL) denselben Pfad nehmen, ist **nicht
  geprüft** — der Repro lief gegen SQLite.
- Ob CSV/JSON/YAML im Einzeldatei-Import dieselbe Typableitung benutzen und dort
  nur zufällig durchkommen (alles Text), ist ebenfalls **nicht geprüft**.
