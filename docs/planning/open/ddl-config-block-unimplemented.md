# Tracker: der `ddl:`-Konfigurationsblock wird nicht gelesen

> **Status:** Teilweise umgesetzt (2026-09-13) — Leser steht, **sechs von acht**
> Schlüsseln sind verdrahtet, zwei weitere als Spec-Dopplung entfernt statt
> gebaut. Zwei bleiben offen, je als eigener [`next/`](../next/)-Plan.
> **Trigger:** Beim Verdrahten von `--partition-storage` (SQL-Server-Filegroup)
> fiel auf, dass es für Generierungsoptionen zwar eine Konfigurationsfläche in
> der Spec gibt, aber keinen Leser im Code.
> **Aktivierungsbedingung:** erfüllt für die zwei restlichen Schlüssel — siehe
> die verlinkten `next/`-Pläne.

## Befund

[`connection-config-spec.md`](../../../spec/connection-config-spec.md)
beschrieb einen `ddl:`-Block mit Dialekt-Unterblöcken:

```yaml
ddl:
  inline_foreign_keys: auto
  include_comments: true
  mysql:      { engine: InnoDB, charset: utf8mb4, collation: utf8mb4_unicode_ci }
  postgresql: { default_schema: public }
  mssql:      { partition_storage: PRIMARY }
```

(Ursprünglich mit einem `sqlite: { foreign_keys, journal_mode }`-Unterblock —
entfernt, siehe Arbeitspaket 6 unten.)

**Stand nach dem ersten Schritt:** `DdlConfigResolver` liest den Block, und
`ddl.mssql.partition_storage` wirkt auf `schema generate --target mssql`
(Vorrang **CLI > Datei > Default**, ungültige Werte brechen mit Exit 7 ab).

Seit 7d wird zusätzlich `ddl.mssql.hash_partitions` gelesen — er bekam mit der
HASH-Emulation seinen Konsumenten und wirkt auf `schema generate` **und**
`schema migrate`.

Die übrigen sechs Schlüssel — `inline_foreign_keys`, `include_comments`, die
drei MySQL-Werte und `postgresql.default_schema` — wurden zum Zeitpunkt dieses
Befunds **nicht** gelesen (die drei MySQL-Werte sind seither verdrahtet, siehe
Arbeitspaket 5). Sie waren nur über CLI-Flags erreichbar, soweit sie
überhaupt existierten; `include_comments` und `postgresql.default_schema`
hatten nicht einmal das.

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
5. ~~Die drei MySQL-Schlüssel (`engine`, `charset`, `collation`).~~ —
   **erledigt 2026-08-30.** Sie waren der einfache Fall aus demselben Grund wie
   `partition_storage`: die Wirkung stand schon, nur fest verdrahtet. Der
   Generator schrieb `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
   COLLATE=utf8mb4_unicode_ci` als Literal; jetzt kommen die drei aus
   `DdlDialectContext.MySql.tableOptions`, mit denselben Vorgaben. Ein CLI-Flag
   gibt es nicht — sie beschreiben das Ziel.

6. ~~`sqlite.foreign_keys` und `sqlite.journal_mode` einordnen.~~ —
   **erledigt 2026-09-13, durch Entfernen statt Bauen.** Gemessen: beide
   Werte sind bereits Verbindungsparameter, spezifiziert **und verdrahtet** —
   [`connection-config-spec.md`](../../../spec/connection-config-spec.md) §1.5
   (`sqlite://…?journal_mode=…&foreign_keys=…`), `SqliteJdbcUrlBuilder
   .defaultParams()`, überschreibbar über `config.params`
   (`JdbcUrlBuilder.buildJdbcUrl` mergt `defaultParams()` → `sslParams()` →
   `config.params`, spätere gewinnen). Der `ddl.sqlite.*`-Unterblock war keine
   fehlende Fähigkeit, sondern eine zweite, nie konsumierte Spec-Stelle für
   dieselben zwei Werte — aus `connection-config-spec.md` entfernt, mit
   Verweis auf §1.5.

7. **Die restlichen drei:**

   - ~~**`inline_foreign_keys`**~~ — **erledigt 2026-09-13.** War kleiner als
     gescoped: `DdlGenerationOptions.deferForeignKeys` existierte bereits und
     war in PostgreSQL/MSSQL/Oracle bereits implementiert, nur nie
     unabhängig von `--split pre-post` erreichbar. `--inline-foreign-keys`
     bzw. `ddl.inline_foreign_keys` (`auto`/`always`/`never`) legt ihn jetzt
     direkt offen; `never` auf MySQL/SQLite (kein Deferred-Constraint-Konzept)
     bricht mit Exit 2. Details:
     [`inline-foreign-keys-mode.md`](../done/inline-foreign-keys-mode.md).
   - **`include_comments`** — nirgends wird ein `COMMENT` gerendert.
     Scope: [`ddl-comment-rendering.md`](../next/ddl-comment-rendering.md).
   - **`postgresql.default_schema`** — es gibt keinen PostgreSQL-Renderkontext
     für ein Ziel-Schema. Scope:
     [`postgresql-default-schema-context.md`](../next/postgresql-default-schema-context.md).

## Gelernt

Der Aufwand lag nicht im Leser (eine Datei), sondern in der Frage, was ein
Schlüssel überhaupt bewirken soll. `partition_storage` ging schnell, weil
`DdlGenerationOptions.partitionStorage` und der Generator-Pfad aus Sub-Slice 7b
schon standen — der Block brauchte nur noch einen zweiten Weg dorthin.

## Angrenzend

[`config-show-full-source-merge.md`](config-show-full-source-merge.md) betrifft
die Herkunftsverfolgung über mehrere Quellen, nicht das Lesen dieses Blocks —
die beiden treffen sich bei Arbeitspaket 3.
