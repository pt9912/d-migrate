# Tracker: der `ddl:`-Konfigurationsblock wird nicht gelesen

> **Status:** Teilweise umgesetzt (2026-08-28) — Leser steht, **zwei von zehn**
> Schlüsseln sind verdrahtet. Der Rest bleibt offen.
> **Trigger:** Beim Verdrahten von `--partition-storage` (SQL-Server-Filegroup)
> fiel auf, dass es für Generierungsoptionen zwar eine Konfigurationsfläche in
> der Spec gibt, aber keinen Leser im Code.
> **Aktivierungsbedingung:** Wird priorisiert → `next/`-Plan; sonst
> Trigger-Watch.

## Befund

[`connection-config-spec.md`](../../../spec/connection-config-spec.md)
beschreibt einen `ddl:`-Block mit Dialekt-Unterblöcken:

```yaml
ddl:
  inline_foreign_keys: auto
  include_comments: true
  mysql:      { engine: InnoDB, charset: utf8mb4, collation: utf8mb4_unicode_ci }
  sqlite:     { foreign_keys: true, journal_mode: wal }
  postgresql: { default_schema: public }
  mssql:      { partition_storage: PRIMARY }
```

**Stand nach dem ersten Schritt:** `DdlConfigResolver` liest den Block, und
`ddl.mssql.partition_storage` wirkt auf `schema generate --target mssql`
(Vorrang **CLI > Datei > Default**, ungültige Werte brechen mit Exit 7 ab).

Seit 7d wird zusätzlich `ddl.mssql.hash_partitions` gelesen — er bekam mit der
HASH-Emulation seinen Konsumenten und wirkt auf `schema generate` **und**
`schema migrate`.

Die übrigen acht Schlüssel — `inline_foreign_keys`, `include_comments`, die
drei MySQL-Werte, die zwei SQLite-Werte und `postgresql.default_schema` —
werden weiterhin **nicht** gelesen. Sie sind heute nur über CLI-Flags erreichbar,
soweit sie überhaupt existieren; `include_comments` und
`postgresql.default_schema` haben nicht einmal das.

Als Zielbild ist das korrekt — die Spec beschreibt, wohin es geht. Der Punkt
dieses Tickets ist, dass die Umsetzung nirgends terminiert ist: weder Slice noch
Roadmap-Eintrag noch Ticket. Wer die Spec liest, erwartet die Einstellung; wer
den Code liest, findet sie nicht.

## Warum das mehr als Bequemlichkeit ist

Ein CLI-Flag wirkt je Aufruf. Einstellungen, die für ein Ziel dauerhaft gelten
— die Filegroup partitionierter Daten, das Standard-Schema, die Storage Engine
— gehören zur Beschreibung des Ziels, nicht zum einzelnen Befehl. Ohne
Konfigurationsweg muss jeder Aufruf sie wiederholen, und ein vergessenes Flag
erzeugt stillschweigend anderes DDL als der Lauf davor.

## Arbeitspakete

1. ~~Leser für `ddl:` samt Dialekt-Unterblöcken.~~ — `DdlConfigResolver`
   ([`DdlConfigResolver.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/DdlConfigResolver.kt)).
   Der Rahmen trägt weitere Schlüssel, ohne sich zu ändern.
2. ~~Vorrangregel gegenüber CLI-Flags festlegen.~~ — **CLI-explizit > Config >
   Default**, dieselbe wie bei `pipeline:`.
3. ~~`config show` muss die aufgelösten Werte zeigen.~~ — war nie eine Lücke:
   `ConfigShowRenderer` rendert den Dateibaum generisch und führt `ddl` bereits
   in seiner Sektions-Reihenfolge.
4. ~~Handbuch nachziehen.~~ — für den verdrahteten Schlüssel geschehen.
5. **Offen:** die restlichen acht Schlüssel. Jeder braucht einen Konsumenten,
   bevor er gelesen wird — ein Schlüssel, der gelesen wird und nichts bewirkt,
   ist schlimmer als keiner. Bei mehreren davon ist die Vorarbeit nicht das
   Lesen, sondern dass es die Einstellung im Generator noch gar nicht gibt.

## Gelernt

Der Aufwand lag nicht im Leser (eine Datei), sondern in der Frage, was ein
Schlüssel überhaupt bewirken soll. `partition_storage` ging schnell, weil
`DdlGenerationOptions.partitionStorage` und der Generator-Pfad aus Sub-Slice 7b
schon standen — der Block brauchte nur noch einen zweiten Weg dorthin.

## Angrenzend

[`config-show-full-source-merge.md`](config-show-full-source-merge.md) betrifft
die Herkunftsverfolgung über mehrere Quellen, nicht das Lesen dieses Blocks —
die beiden treffen sich bei Arbeitspaket 3.
