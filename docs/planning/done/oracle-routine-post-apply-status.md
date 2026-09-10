# Oracle: eine `INVALID` gebliebene Routine wird nach dem Anwenden nicht bemerkt

## Befund

Ein `CREATE OR REPLACE FUNCTION`/`PROCEDURE`/`TRIGGER` mit einem
Übersetzungsfehler im Rumpf meldet über JDBC **Erfolg**. Das Objekt entsteht,
steht aber auf `ALL_OBJECTS.STATUS = 'INVALID'` und ist unbenutzbar. Gemessen
über echtes JDBC gegen `gvenzl/oracle-free`:

| gesendet | `execute()` | Zustand |
| --- | --- | --- |
| `… END;` | OK | VALID |
| `… END;;` | **OK** | **INVALID** |
| `… END;` + `/` | **OK** | **INVALID** |
| Rumpf mit unbekanntem Bezeichner | **OK** | **INVALID** |

d-migrate vermeidet die ersten beiden Fälle: das emittierte SQL trägt weder
ein zweites `;` noch ein `/` (`OracleRoutineDdl`, gepinnt durch
`OracleRoutineIntegrationTest`). Der dritte Fall bleibt: ein Rumpf, den ein
Anwender in seine Schemadatei schreibt und der auf dem Ziel nicht übersetzt,
lässt `schema migrate --execute` als gelungen gelten.

## Warum es keine bestehende Naht auffängt

Der Post-Compare liest das Schema nach dem Anwenden zurück — aber `ALL_SOURCE`
führt den Text auch einer `INVALID`-Routine. Der Vergleich ist also grün,
obwohl das Objekt nicht benutzbar ist.

## Was gebaut wurde

Eine Nachfrage nach dem Anwenden, entlang des Musters von
`CheckPreflightProbeRunner` — nur einen Schritt später im Ablauf:
`PostApplyStatusProbeFn` als Naht, `OraclePostApplyStatusProbe` als einzige
tragende Ausprägung, `SchemaMigrateExecutionStage.checkPostApplyStatus` als
Aufrufer. Sie läuft **vor** dem Post-Compare: der vergleicht Katalogtext und
fände eine nicht übersetzte Routine grün.

## Was die Messung an den Entscheidungen geändert hat

Beide Fragen, die dieses Ticket dem Eigner vorlegen wollte, ließen sich messen
statt entscheiden.

**Zur Schärfe (Frage 2).** Das Ticket nahm an, ein `INVALID`-Ergebnis sei
zwischen „echter Fehler" und „planmäßige Invalidierung" nicht unterscheidbar,
und ein hartes Scheitern deshalb womöglich falsch. Gegen
`gvenzl/oracle-free:23-slim-faststart` gemessen ist es unterscheidbar:

| Fall | `ALL_OBJECTS.STATUS` | `ALL_ERRORS` |
|---|---|---|
| Übersetzungsfehler im Rumpf (Funktion) | INVALID | **2 Zeilen**, `PLS-00201` mit Zeile/Spalte |
| Übersetzungsfehler im Rumpf (Trigger) | INVALID | **2 Zeilen** |
| Spalte **hinzugefügt** | **VALID** | leer |
| benutzte Spalte **gelöscht** | INVALID | **leer** |
| Tabelle gelöscht | INVALID | **leer** |

`ALL_ERRORS` ist also das Signal, nicht `STATUS`. Damit ist der Fund
eindeutig — er heißt, dass der Rumpf nicht übersetzbar ist — und ein
Fehlschlag des Laufs die richtige Antwort. **Die Spec verlangte an dieser
Stelle das falsche Signal** („Nach dem Anwenden ist `ALL_OBJECTS.status` zu
prüfen") und ist korrigiert.

Nebenbefund derselben Messung: Oracle 23 invalidiert beim **Hinzufügen** einer
Spalte gar nicht mehr (feingranulare Abhängigkeiten). Planmäßige Invalidierung
ist seltener, als das Ticket annahm.

**Zum Umfang (Frage 1).** Nur die Objekte, die der Lauf anfasst — wie das
Ticket es schon nahelegte. Weggeworfene Objekte brauchen dabei keine
Aussonderung: sie stehen nicht in `ALL_ERRORS`.

## Wo der Fund landet

In `ExecutionTrace.executionError`. Damit trägt er dieselbe Behandlung wie ein
mitten im Lauf abgebrochenes Statement: Exit `5`, kein Rücknahme-Artefakt, und
**kein Post-Compare**, der die Lage grün färbte. Ein eigener Exit-Code war
nicht nötig — `5` heißt bereits „DDL-Ausführungsfehler nach Beginn von
`--execute`", und genau das ist es.

Scheitert die Nachfrage selbst (fehlende Katalogrechte), bleibt der Lauf
gelungen — gescheitert ist die Nachfrage, nicht das DDL. Verschwiegen wird sie
trotzdem nicht: sie meldet, dass ungeprüft blieb, was geprüft werden sollte.

## Was bewusst nicht gemeldet wird

Ein angefasstes Objekt, das **ohne** Einträge in `ALL_ERRORS` auf `INVALID`
steht. Oracle übersetzt es bei der nächsten Benutzung selbst neu. Der eine
Fall, in dem das nicht gelingt — die Abhängigkeit ist weg —, entsteht nur aus
einem in sich widersprüchlichen Soll-Schema (eine Routine bleibt im Schema,
die Tabelle, die sie benutzt, nicht) und gehört damit vor den Lauf, nicht
danach.

## Was die anderen vier Dialekte tun

Nichts, und das ist kein Ausschnitt: PostgreSQL, MySQL, SQLite und SQL Server
führen keinen Zustand „angelegt, aber nicht übersetzt". Wo ein Rumpf erst beim
Aufruf scheitert (PostgreSQL mit `check_function_bodies`, SQL Server mit
aufgeschobener Namensauflösung), gibt es dafür auch keinen Katalogeintrag, den
man nachschlagen könnte.

## Berührte Stellen

- `adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleCheckPreflightProbe.kt`
  (Vorbild für die Sonden-Naht)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SegmentAwareMigrationExecutor.kt`
  (die Stelle, an der ausgeführt wird)
- `spec/ddl-generation-rules.md`, Abschnitt 11 (Zielbild sagt bereits: „Nach
  dem Anwenden ist `ALL_OBJECTS.status` zu prüfen")
