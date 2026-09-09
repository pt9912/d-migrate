# Oracle kennt `DROP … IF EXISTS` — der Generator behauptet das Gegenteil

## Befund

`OracleDdlGenerator.invertStatement` trägt die Begründung, Oracle kenne kein
`DROP … IF EXISTS` „auch nicht 23ai", und lässt die Klausel deshalb bei allen
Rücknahme-Anweisungen weg.

Gegen den Container gemessen, den die Integrationstests ziehen
(`gvenzl/oracle-free:23-slim-faststart`, Banner „Oracle AI Database 26ai Free
Release 23.26.3.0.0"), funktionieren **alle sechs** Formen:

```
DROP TABLE IF EXISTS …      Table dropped.
DROP VIEW IF EXISTS …       View dropped.
DROP SEQUENCE IF EXISTS …   Sequence dropped.
DROP INDEX IF EXISTS …      Index dropped.
DROP FUNCTION IF EXISTS …   Function dropped.
DROP TRIGGER IF EXISTS …    Trigger dropped.
```

Die Klausel kam mit Oracle 23ai; für ältere Bestände (19c und davor) gilt die
Aussage weiterhin.

## Warum das zählt

Ein Rücknahme-Skript ohne `IF EXISTS` bricht ab, sobald ein Objekt bereits
fehlt — genau der Fall, in dem man ein Rücknahme-Skript fährt. Der generische
Inverter in `AbstractDdlGenerator` setzt die Klausel aus diesem Grund; Oracle
ist der einzige Dialekt, der sie herausnimmt.

## Was entschieden wurde

Keine der drei Optionen aus der Vorabklärung, sondern die vierte, die keine
Zusage braucht: **gefragt wird der Server, nicht die Roadmap.** d-migrate sagt
weiterhin keine Mindest-Oracle-Version zu; die Klausel wird gesetzt, wo die
Version des Ziels bekannt ist und sie trägt, und weggelassen, wo sie es nicht
ist. Damit entfällt die Frage, ab wann Unterstützung zugesagt wird — sie war
nie die Frage, die der Befund wirklich stellte.

Das Muster stand schon: `DdlDialectContext.MySql.serverVersion` führt seit den
Routinen-Gates dieselbe Information für MySQL, `null` für Datei-zu-Datei.

## Was gemessen wurde

Gegen `gvenzl/oracle-free:23-slim-faststart` (`product_component_version`:
`23.0.0.0.0`, `version_full` `23.26.3.0.0`, JDBC-`databaseMajorVersion` 23):

- **Neun** Objektformen nehmen `IF EXISTS` auf ein fehlendes Objekt an — die
  sechs aus dem Befund plus `MATERIALIZED VIEW`, `TYPE` und `PROCEDURE`.
- `DROP TABLE p_absent` ohne Klausel: `ORA-00942`. Das ist der Abbruch, um den
  es geht.
- **`ALTER TABLE … DROP CONSTRAINT IF EXISTS` gibt es auch auf 23 nicht**
  (`ORA-01735`). Wer die Klausel pauschal gesetzt hätte, hätte den Ruecknahme-
  Pfad für Constraints kaputtgemacht — der generische Inverter in
  `AbstractDdlGenerator` setzt sie dort.

## Was daneben herauskam

Der Oracle-Inverter hatte **keinen Zweig für `CREATE MATERIALIZED VIEW`**,
obwohl der Generierungspfad die Form erzeugt (`OracleMaterializedViewDdl`).
Die Rücknahme ließ die Materialized View also still stehen. Der Zweig ist
ergänzt.

## Was gebaut wurde

- `ServerVersion` (sealed) im Lesepfad; `MysqlServerVersion` und das neue
  `OracleServerVersion` sind seine Ausprägungen. `SchemaReadResult` und
  `ResolvedSchemaOperand` führen **ein** Feld `serverVersion` statt eines pro
  Dialekt — das zweite hätte die Naht verdoppelt, die schon dialektbenannt war.
- `OracleServerVersion.supportsDropIfExists` — die Regel an einer Stelle, samt
  der Ausnahme für Constraints.
- `OracleMetadataQueries.readServerVersion` liest `version` (nicht
  `version_full`: das gibt es erst ab 18c, also gerade auf den alten Beständen
  nicht, wegen derer überhaupt unterschieden wird), best-effort im
  `OracleSchemaReader`.
- `DdlDialectContext.Oracle`, gesetzt von `SchemaMigrateRenderPipeline`.
- `invertStatement` bekommt die Render-Optionen (`AbstractDdlGenerator` und
  die vier überschreibenden Treiber).

## Was nicht gebaut wurde

`schema generate --rollback` rendert ohne Verbindung und bekommt die Klausel
deshalb nicht. Ein Schalter, mit dem ein Anwender die Zielversion behauptet,
wäre möglich — bislang hat niemand danach gefragt, und beide Formen sind
gültiges DDL.

## Berührte Stellen

- `adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleDdlGenerator.kt`
  (`invertStatement` samt Begründung)
- `adapters/driven/driver-oracle/src/test/kotlin/dev/dmigrate/driver/oracle/OracleDdlGeneratorObjectsTest.kt`
  (der Test „rollback drops CREATE TABLE/INDEX/SEQUENCE/VIEW without IF EXISTS
  (Oracle has no such clause)" pinnt die heutige Form samt Begründung)
