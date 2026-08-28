# Tracker: der `ddl:`-Konfigurationsblock wird nicht gelesen

> **Status:** Tracker / Vorabklärung (2026-08-28)
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

Keiner dieser Schlüssel taucht als Konfigurationsschlüssel im Code auf. Die
entsprechenden Einstellungen sind heute ausschließlich über CLI-Flags
erreichbar, soweit sie überhaupt existieren.

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

## Arbeitspakete (Skizze)

1. Leser für `ddl:` samt Dialekt-Unterblöcken.
2. Vorrangregel gegenüber CLI-Flags festlegen (die übrigen Blöcke haben eine:
   Flag schlägt Datei).
3. `config show` muss die aufgelösten Werte zeigen.
4. Handbuch nachziehen — dort darf der Weg erst stehen, wenn er wirkt.

## Angrenzend

[`config-show-full-source-merge.md`](config-show-full-source-merge.md) betrifft
die Herkunftsverfolgung über mehrere Quellen, nicht das Lesen dieses Blocks —
die beiden treffen sich bei Arbeitspaket 3.
