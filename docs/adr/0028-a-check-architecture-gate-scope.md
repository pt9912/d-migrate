---
status: accepted
date: 2026-07-07
decision-makers: pt9912
consulted: docs/planning/done/a-check-architecture-gate.md, docs/adr/0022-ports-jdbc-entkopplung.md, spec/architecture.md
informed: .a-check.yml, make/a-check.mk, hexagon/ports-common, hexagon/ports-write, adapters/driven/formats, adapters/driven/streaming, adapters/driving/cli
---

# a-check-Architektur-Gate: G1 zuerst, strikte Composition-Root-JDBC-Regel

> **Status: accepted (2026-07-07).** Der a-check-Slice verfolgt zuerst **G1**:
> das Hexagon-Gate wird gruen, ohne die bestehenden JDBC-Typcode-Integer
> vollstaendig durch ein neutrales Typmodell zu ersetzen. Composition Roots
> bleiben beim JDBC-Tech-Scope **streng**: CLI/MCP verdrahten Adapter, halten aber
> keine produktive JDBC-Ausfuehrung und kein JDBC-Unwrap.

## Kontext und Problemstellung

ADR 0022 hat fuer die Ports-Schicht entschieden: Port-Signaturen exponieren
keine `java.sql`-Typen; JDBC-Technologie lebt in Adaptern. Der naechste
Architektur-Slice verallgemeinert diese Regel mit `a-check` auf das ganze
Hexagon und findet zwei verschiedene Problemklassen:

1. **Textuell sichtbare Architekturverletzungen**: `java.sql`-/`javax.sql`-
   Importe in `hexagon:application`, `hexagon:profiling` oder
   `adapters:driving`, plus Adapter-zu-Adapter-Kanten im
   formats/streaming/parquet-Dreieck.
2. **Semantische JDBC-Typcode-Kopplung ohne Import**: `TargetColumn.jdbcType:
   Int`, `JdbcTypeHint.jdbcType: Int` und der Parquet-Manifest-/Bundle-Vertrag
   transportieren JDBC-`Types`-Codes, obwohl `a-check` das nicht als
   `java.sql`-Import sehen kann.

Diese zwei Klassen duerfen nicht vermischt werden. Ein gruenes Import- und
Layer-Gate beweist noch kein vollstaendig neutrales Typmodell.

Zusaetzlich ist die Rolle von `adapters:driving:*` zu klaeren. CLI und MCP sind
Composition Roots: sie duerfen konkrete Adapter zusammenstecken. Offen war, ob
sie dabei auch JDBC-Connections auspacken und produktive JDBC-Ausfuehrung halten
duerfen.

## Entscheidungstreiber

- **Kleiner, lieferbarer Gate-Slice:** Das Import-/Layer-Gate soll aktiviert
  werden koennen, ohne gleichzeitig die gesamte Transfer- und Parquet-
  Typcode-Semantik umzubauen.
- **Ehrlichkeit des Zielbilds:** G1 darf nicht als G2 verkauft werden. Der
  verbleibende `jdbcType: Int`-Vertrag muss als begrenzte Ausnahme sichtbar
  bleiben.
- **Hexagon-Klarheit:** Composition Roots verdrahten Abhaengigkeiten. JDBC-
  Verhalten und JDBC-Unwrap gehoeren in driven Adapter oder explizite Ports, nicht
  in CLI/MCP.
- **Gate-faehigkeit:** Die Entscheidung muss mit statischen Gates pruefbar sein:
  `a-check` fuer Kotlin-Importe/Kanten, zusaetzliche Grep-/Gradle-Gates fuer
  Falsch-Gruen-Luecken.

## Betrachtete Optionen

- **Option A — G1 zuerst, D2 streng.** Relokation/Interface-Extraktion und
  Port-DTOs machen `a-check` gruen. `jdbcType: Int` bleibt vorerst als eng
  begrenzte Interop-/Persistenz-Ausnahme dokumentiert. Composition Roots bleiben
  JDBC-frei.
- **Option B — G1 zuerst, D2 pragmatisch.** Wie Option A, aber CLI/MCP duerfen
  JDBC-Connections auspacken und ausfuehren.
- **Option C — G2 sofort.** JDBC-Typcodes werden durch ein neutrales Typmodell
  in Ports, Formats, Streaming, Reverse Engineering und Parquet-Manifest ersetzt.

## Entscheidung

**Gewählt: Option A — G1 zuerst, D2 streng.**

Der aktuelle Slice liefert zuerst ein sprach- und modulweites Architektur-Gate:

- `a-check` muss fuer produktive Kotlin-Quellen gruen werden.
- Driven Adapter duerfen in produktiven Gradle-Konfigurationen nicht voneinander
  abhaengen; erlaubt bleiben nur dieselben Ausnahmen wie in `.a-check.yml`:
  Driving-Composition-Roots und `adapters:driven:driver-common` als
  Adapter-Sink.
- `adapters:driving:*` bleiben reine Composition Roots. Produktiver Code dort
  darf keine `java.sql`-/`javax.sql`-Imports, keine produktiven
  `java.sql.`-/`javax.sql.`-FQNs, kein `asJdbc` und keine direkte
  `JdbcDatabaseConnection`-Nutzung enthalten. JDBC-Ausfuehrung, Probe-Dispatch
  und Hook-Applier werden hinter driven Ports/Adapter-Schnittstellen gezogen.

G1 ratifiziert zugleich eine begrenzte, bewusst benannte Ausnahme: `jdbcType:
Int` darf in bestehenden Transfer-/Format-/Persistenzvertraegen bleiben, solange
es nicht in `hexagon:core` wandert und nicht als vollstaendig neutrales
Typmodell behauptet wird. Konkret betrifft das die heutigen
`TargetColumn`-/`JdbcTypeHint`-Vertraege und den Parquet-Manifest-/Bundle-
Vertrag. Diese Ausnahme ist keine Einladung fuer neue `jdbcType`-Felder; neue
neutrale Domain-Vertraege duerfen JDBC-Typcodes nicht weiter ausbreiten.

G2 bleibt ein eigener, spaeterer ADR-/Slice-wuerdiger Umbau: neutrales Typ-Enum
statt JDBC-`Types`-Codes, inklusive Reverse Engineering, TypeConverter,
Streaming-Import und Parquet-Manifest-Kompatibilitaet.

## Konsequenzen

- **Gut:** Der a-check-Slice bleibt lieferbar und beseitigt reale Layer-Leaks,
  ohne eine grosse Typmodell-Migration zu verstecken.
- **Gut:** Die strikte Composition-Root-Regel verhindert ein Falsch-Gruen, bei
  dem CLI/MCP zwar keine falschen Imports mehr zeigen, aber weiterhin JDBC-
  Ausfuehrung halten.
- **Gut:** Die verbleibende Typcode-Kopplung ist dokumentiert und begrenzt.
- **Preis:** G1 ist kein vollstaendig neutrales Daten-/Typmodell. Leser und
  Gates muessen diese Grenze sichtbar halten.
- **Preis:** D2 streng macht A1/A2/A4 groesser: Hook-Applier, Probe-Dispatch und
  alle `asJdbc`-Stellen in `adapters:driving:*` brauchen Ports oder Registry-
  Adapter statt lokaler JDBC-Ausfuehrung.

## Bestätigung

- `make a-check` wird Teil von `gates`/`docker-gates`, sobald der Slice alle
  aktuellen Befunde bereinigt.
- Ein separates Gradle-Dependency-Gate prueft produktive Adapter-zu-Adapter-
  `project(...)`-Kanten mit denselben Ausnahmen wie `.a-check.yml`.
- Ein separates Composition-Root-Gate prueft in `adapters/driving/**`
  produktive `java.sql`-/`javax.sql`-Imports und FQNs sowie `asJdbc`/
  `JdbcDatabaseConnection`; Kommentare/KDoc und Testquellen werden ausgeklammert.

## Weitere Informationen

- Planung: [`docs/planning/done/a-check-architecture-gate.md`](../planning/done/a-check-architecture-gate.md)
- Vorentscheidung: [`ADR 0022`](0022-ports-jdbc-entkopplung.md)
