# Ein Refresh-Befehl für Materialized Views fehlt

## Status

Vorabklärung. Die Frage ist nicht, wie man ihn baut, sondern ob er zur
Befehlsoberfläche gehört.

## Befund

Der Migrationsbericht führt je Materialized View einen Vertrag mit
`refreshSteps`. Einen **ausführbaren** Refresh-Schritt gibt es nicht: der
Bericht kennt `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED` als eigenen Blocker und
nennt dabei einen Befehl, den es nicht gibt.

Die Folge: eine Migration kann eine Sicht neu anlegen — dabei ist sie gefüllt —,
aber nach einem späteren Wechsel an den Basistabellen nicht auffrischen. Bei
`refresh: on demand` bleibt sie dann veraltet, und d-migrate kann nichts
dagegen tun.

## Warum das kein gewöhnlicher Rückstand ist

`spec/cli-spec.md` erwähnt `schema refresh materialized-view` **nur** als
geblockte Absicht in einer Tabellenzeile. Als Befehl ist er nirgends
beschrieben: keine Sektion in §6, kein Requirement, kein Eintrag im Tracker
[`cli-unimplemented-commands.md`](cli-unimplemented-commands.md) — der führt
ausdrücklich nur, was die Spec beschreibt und der Code nicht hat.

Ihn zu bauen hieße also zuerst, ihn zu **spezifizieren**. Das ist eine
Erweiterung der Befehlsoberfläche und gehört dem Eigner, nicht dem Bau.

## Was vor einer Spec zu klären wäre

1. **Gehört Auffrischen überhaupt zu einem Schema-Werkzeug?** Es ändert keine
   Schema-Form, sondern Daten. Die Grenze verläuft heute zwischen `schema` und
   `data`; ein Refresh liegt quer dazu.
2. **Welche Dialekte?** PostgreSQL (`REFRESH MATERIALIZED VIEW`, optional
   `CONCURRENTLY`) und Oracle (`DBMS_MVIEW.REFRESH`) — die Formen sind
   verschieden genug, dass die neutrale Fassung eine eigene Entscheidung ist.
3. **Verhältnis zum Migrationslauf.** Soll `schema migrate --execute` selbst
   auffrischen dürfen, oder bleibt es ein getrennter Befehl? Das erste macht
   aus einer Schemamigration einen Datenlauf mit entsprechender Laufzeit.

## Berührte Stellen

- [`spec/cli-spec.md`](../../../spec/cli-spec.md) (Vertragstabelle der
  MV-Blocker; dort steht der Befehlsname heute)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateMaterializedViewContractBuilder.kt`
  (`BLOCKED_SCHEMA_REFRESH_UNSUPPORTED`)
