# `postgresql.default_schema` — Ziel-Schema für generiertes DDL

> **Status:** Umgesetzt (2026-09-13). Teil von
> [`done/ddl-config-block-unimplemented.md`](ddl-config-block-unimplemented.md).

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

## Closure (2026-09-13)

Weg A gebaut (Eigner-Entscheidung), nach einer Recherche- und einer
gezielten Audit-Runde vor dem Bau — beide zeigten einen groesseren
Umfang als im Scope skizziert.

**Recherche vor der Entscheidung:** die urspruengliche Auflistung
(`PostgresDdlGenerator.kt`, `PostgresComputedStorage`,
`PostgresPartitionClauses`, `PostgresIndexClauses`,
`NamedUniqueConstraints`) unterschaetzte die Flaeche erheblich. Tatsaechlich
schema-gebunden (und damit qualifizierungspflichtig) sind zusaetzlich
Views/Materialized Views, Funktionen, Prozeduren, Aggregate, Sequenzen und
alle drei Custom-Type-Arten (ENUM/COMPOSITE/DOMAIN) —
`PostgresRoutineDdlHelper.kt` und `PostgresTypeSequenceDdlSupport.kt` kamen
dazu. Der Migrate-Pfad (`PostgresDiffSqlBuilders` + 11 Dateien, ~74
Aufrufstellen) hat dieselbe Luecke, blieb aber wie gescoped aussen vor.

**Audit vor dem Bau deckte zwei Referenz-Faelle auf, die eine reine
CREATE-Statement-Betrachtung uebersehen haette:** ein ENUM-Spalten-Typ mit
`refType` verweist auf den `CREATE TYPE`-Namen
(`PostgresColumnConstraintHelper.kt`), und ein `DEFAULT nextval('seq')`
verweist auf den `CREATE SEQUENCE`-Namen (`resolveSequenceDefault`) — beide
haetten unqualifiziert ins Leere gezeigt, waere nur die Erzeugung selbst
qualifiziert worden, das Ziel aber nicht mit im Suchpfad. Beide sind jetzt
mitqualifiziert.

**Der wiederverwendbare Baustein:** `SqlIdentifiers.quoteQualifiedIdentifier`
existierte bereits (genutzt von `PostgresSequenceCurrentValueProbe`, einer
Live-DB-Stelle im Preserve-Pfad) — kein neuer Mechanismus noetig, nur eine
neue Zugriffsflaeche (`PostgresDdlGenerator.quoteQualified`, per
Funktionsreferenz an vier Helfer durchgereicht: `PostgresColumnConstraintHelper`,
`PostgresRoutineDdlHelper`, `PostgresTypeSequenceDdlSupport`, sowie direkt
in `PostgresDdlGenerator` selbst).

**Golden-Master-Risiko wie in der Recherche vorhergesagt aufgeloest:** der
Default (`defaultSchema == null`) rendert exakt wie zuvor — keines der
PostgreSQL-Goldens musste neu gezogen werden, `DdlGoldenMasterTest` blieb
unveraendert gruen.

**Bewusst nicht qualifiziert** (Audit-Befund, aus demselben Grund wie
Funktions-/Prozedur-Bodies nie uebersetzt werden): `SFUNC`/`FINALFUNC`/`STYPE`
eines Aggregats und Parameter-/Rueckgabetypen von Funktionen/Prozeduren sind
roher SQL-Text ohne Information, ob sie einen PostgreSQL-Builtin oder ein
selbst erzeugtes Objekt meinen — eine Qualifizierung koennte einen gueltigen
Verweis auf einen Builtin brechen. Der Trigger-eigene Name bleibt
unqualifiziert (PostgreSQL kennt das nicht); seine Zieltabelle und seine
Helfer-Funktion (samt `EXECUTE FUNCTION`-Referenz) sind qualifiziert.

**Kein CLI-Flag** — dieselbe Begruendung wie bei `ddl.include_comments`:
eine Ziel-Beschreibung, kein Aufruf-Parameter.

**Verifiziert:** `PostgresDefaultSchemaQualificationTest` (Ende-zu-Ende ueber
`generate()`: Tabelle+Index+FK, Partitions-Eltern/Kind, Sequenz+`nextval()`,
ENUM+`refType`, sowie der Default-Fall byte-identisch zum vorherigen
Rendering), `PostgresRoutineDdlHelperTest`-Ergaenzung (View/Funktion/
Prozedur/Aggregat/Trigger, inkl. Gegenprobe dass Parameter- und
Trigger-eigener-Name unqualifiziert bleiben), `DdlConfigResolverTest` und
`SchemaGenerateWiringTest`-Ergaenzungen (Config-Lesen, Praezedenz, keine
Uebertragung auf einen Nicht-PostgreSQL-Lauf). Vier Sabotagen durchgefuehrt
und zurueckgenommen (der zentrale `quoteQualified`-Mechanismus, eine
einzelne Aufrufstelle in `PostgresRoutineDdlHelper`, der Config-Resolver-Read
und die `nextval()`-Qualifizierung je einmal neutralisiert) — alle brachen
die erwarteten Tests. Einmal voller Repo-Build ohne `MODULES`
(`DdlGenerationOptions` ist ein geteilter Hexagon-Port).

**Nicht Teil dieses Schnitts** (wie im Scope abgegrenzt): `schema migrate`
(Diff-Pfad) — dieselbe Luecke besteht dort unveraendert, deutlich groesser
(~74 Aufrufstellen in 11 Dateien), aber nicht Gegenstand dieses Slices.
