# `postgresql.default_schema` — Ziel-Schema für generiertes DDL

> **Status:** Draft mit Scope (2026-09-13). Teil von
> [`open/ddl-config-block-unimplemented.md`](../open/ddl-config-block-unimplemented.md).

## Ziel

`ddl.postgresql.default_schema: <name>` legt fest, in welchem Schema
`schema generate --target postgresql` die erzeugten Objekte platziert —
Vorrang **CLI-explizit > Config > Default** (Default `public`, heutiges
Verhalten unverändert).

## Befund: es gibt heute keinen Renderkontext für ein Ziel-Schema

`PostgresDdlGenerator` quotet jeden Bezeichner unqualifiziert:
`quoteIdentifier(name)` — Tabellen (`PostgresDdlGenerator.kt:176`),
Indizes (`:280`), Fremdschlüssel-Referenzen (`:378,399`) und alle übrigen
Stellen. Es gibt kein Feld, das ein Schema trüge, und keinen Aufrufpfad,
der eines hineinreicht — `default_schema` ist nicht "unvollständig
implementiert", sondern eine Fähigkeit, die noch nicht existiert.

**Wichtiger Unterschied zu MSSQL/Oracle:** Dort gibt es bereits
`defaultSchema`-Parameter (`MssqlQualifiedTableName.parse`,
`OracleQualifiedTableName.parse`) — aber die lösen ein anderes Problem: sie
qualifizieren einen **eingelesenen** unqualifizierten Tabellennamen beim
**Lesen/Schreiben von Daten** (`MssqlDataWriter`, `MssqlSchemaSync`), nicht
beim **Generieren von DDL**. Kein Vorbild ist 1:1 übertragbar; die
PostgreSQL-DDL-Generierung braucht einen eigenen Mechanismus.

## Die offene Design-Frage: zwei grundverschiedene Wege

**Weg A — jeden Bezeichner qualifizieren.** `quoteIdentifier(name)` würde zu
`quoteIdentifier(schema, name)` bzw. bekäme einen Schema-Kontext
mitgegeben, der `"schema"."table"` statt `"table"` rendert. Trifft **jede**
Stelle, die heute einen Tabellen-, Index- oder FK-Referenznamen rendert —
ein breiter, mechanischer, aber vielstelliger Umbau
(`PostgresDdlGenerator.kt` allein hat über ein Dutzend Aufrufstellen, dazu
`PostgresComputedStorage`, `PostgresPartitionClauses`,
`PostgresIndexClauses`, `NamedUniqueConstraints`). Wirkt **pro Objekt**,
unabhängig von der Verbindung, mit der das DDL später ausgeführt wird.

**Weg B — `SET search_path` am Skriptanfang emittieren.** Eine einzelne
Zeile `SET search_path TO <schema>;` vor dem ersten `CREATE`, analog zum
bestehenden Header-Kommentar-Mechanismus
([`generateHeader()`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt)).
Deutlich kleinerer Schnitt (eine Zeile, ein Aufrufort), aber andere
Semantik: **sitzungsgebunden**, nicht pro Anweisung — wirkt nur, wenn das
erzeugte Skript als Ganzes in derselben Session läuft (z. B. `psql -f`),
nicht wenn einzelne Anweisungen isoliert wiederverwendet werden (Split-Mode
`pre-post` mit separat ausgeführten Dateien, oder wenn eine einzelne
`CREATE TABLE`-Zeile aus dem generierten Skript herauskopiert wird).

Diese Bruchstelle betrifft konkret den bestehenden `--split-mode
pre-post`-Pfad: läuft `SET search_path` nur in der ersten der beiden
Dateien, gilt es in der zweiten nicht mehr, außer der Aufrufer verkettet
beide Dateien in derselben Verbindung — ein Verhalten, das dokumentiert,
aber nicht erzwungen werden könnte.

**Tendenz vor dem Bau nicht entschieden** — beide Wege sind mit dem
Eigner zu klären, bevor Code entsteht:

- Weg A ist robuster (funktioniert unabhängig vom Ausführungskontext),
  aber der größere Umbau und berührt Tests, die heute unqualifizierte
  Namen in Goldens erwarten (`DdlGoldenMasterTest`-Fixtures für
  PostgreSQL müssten neu gezogen werden, falls der Default weiterhin
  `public` unqualifiziert rendert — zu prüfen, ob Weg A bei
  `default_schema == "public"` identisch zu heute rendern kann, um die
  Goldens unberührt zu lassen).
- Weg B ist der kleinere Schnitt, trägt aber eine Einschränkung, die im
  Handbuch klar benannt werden müsste, sonst überrascht sie beim
  Split-Mode.

## Scope-Skizze (nach Entscheidung)

1. Mit dem Eigner Weg A vs. Weg B klären.
2. `DdlGenerationOptions.postgresqlDefaultSchema: String? = null` (oder
   `String = "public"`) ergänzen.
3. `DdlConfigResolver`: `ddl.postgresql.default_schema` lesen, Präzedenz
   wie bei `mssql.partition_storage`.
4. Je nach Weg: entweder `quoteIdentifier`/Renderstellen erweitern (A)
   oder `generateHeader()`-Nachbarschaft um eine `SET search_path`-Zeile
   ergänzen (B).
5. Golden-Master-Fixtures prüfen/anpassen, falls der Default-Fall
   (`public`) sich im Rendering ändert.
6. Handbuch: Abschnitt zu Schema-Qualifizierung, inkl. der
   Split-Mode-Einschränkung falls Weg B.

## Akzeptanzkriterien

- `ddl.postgresql.default_schema: analytics` lässt generiertes DDL im
  Schema `analytics` landen (Mechanismus abhängig vom gewählten Weg).
- Ohne den Schlüssel: unverändertes Verhalten (`public`, wie heute
  implizit über PostgreSQLs eigenen Server-Default).
- Golden-Master-Tests bleiben grün oder werden bewusst neu gezogen, nicht
  stillschweigend angepasst.

## Berührte Stellen (vorläufig, hängt vom gewählten Weg ab)

- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerationOptions.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/DdlConfigResolver.kt`
- `adapters/driven/formats/src/test/resources/fixtures/ddl/` (PostgreSQL-Goldens, falls Weg A)
