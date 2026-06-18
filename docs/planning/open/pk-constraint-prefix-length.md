# PRIMARY-KEY-/Constraint-Präfixlängen

> **Status:** Vorabklärung (Trigger, 2026-06-17)
> **Trigger:** Die Präfixlängen-Modellscheibe
> ([`../done/index-prefix-length-model.md`](../done/index-prefix-length-model.md))
> hat `IndexColumn.prefixLength` eingeführt, PRIMARY-KEY- und
> Constraint-Spalten aber bewusst ausgeklammert (Entscheidung **D-4**, fixiert in
> [`ADR 0012`](../../adr/0012-index-prefix-length-scope.md)). MySQL erlaubt
> Präfixlängen auch in `PRIMARY KEY (col(n))` und Constraint-modellierten
> `UNIQUE KEY (col(n))`; das neutrale Modell führt beide als `List<String>` und
> kann keine Per-Spalten-Länge tragen.
> **Aktivierungsbedingung:** Sobald ein Pilot- oder Anwenderfall eine Präfix-
> Länge in `PRIMARY KEY`/Constraints benötigt (insb. Präfix-PK auf `TEXT`/`BLOB`,
> der heute beim Round-Trip verloren geht und beim Regenerieren `ERROR 1170`
> auslösen kann), wandert dieser Eintrag nach `../next/` — dort mit Phasenschnitt
> und Akzeptanzkriterien.
> **Disposition 2026-06-18:** für 1.0.x vorgesehen (nicht in 0.9.9; ADR 0012 bleibt in Kraft).

## Gegenstand

Präfixlängen für die heute ausgeklammerten Spaltenlisten nachrüsten:

- `TableDefinition.primaryKey: List<String>`
- `ConstraintDefinition.columns: List<String>`

Vorgezeichnete Richtung (aus ADR 0012): strukturierte Spalten-Einträge
(Name + optionale Länge) statt nackter Strings, durchgezogen über Serialisierung
(`spec/schema.json`), Vergleich (`SchemaComparator`/`TableComparator`),
Migration-Fingerprint und alle drei Dialekt-Generatoren — analog zur bereits
gelieferten `IndexColumn.prefixLength`-Mechanik.

## Abgrenzung (bereits abgedeckt)

- **Eigenständige Präfix-Indizes** auf `TEXT`/`BLOB` (`CREATE INDEX … (col(n))`)
  — geliefert (I-08-MySQL).
- **UNIQUE als Index** (`IndexDefinition(unique = true)`) — erbt `prefixLength`
  über `IndexColumn`, ebenfalls abgedeckt.

Offen ist also nur das **literale `PRIMARY KEY`** und die **PG-artige
Constraint-Modellierung** von UNIQUE.
