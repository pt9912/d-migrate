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

## Was es brauchte

Eine Nachfrage gegen `ALL_OBJECTS` nach dem Anwenden, entlang des Musters von
`OracleCheckPreflightProbe` — nur in der Gegenrichtung (nach statt vor der
Ausführung). Das ist ein neuer Port: der Diff-Renderer hat keine Verbindung,
und der Executor kennt keine dialektspezifische Nachprüfung.

Zu entscheiden:

1. Ob die Prüfung das ganze Schema oder nur die angefassten Objekte abdeckt.
   Ein Schema kann schon vorher `INVALID`-Objekte enthalten, die niemand in
   diesem Lauf angefasst hat — die als Fehler dieses Laufs zu melden wäre
   falsch.
2. Ob ein `INVALID`-Ergebnis den Lauf fehlschlagen lässt oder als Warnung im
   Report steht. Oracle invalidiert Objekte auch **planmäßig** (eine
   geänderte Tabelle invalidiert die Sichten darauf, bis sie neu übersetzt
   werden) — ein hartes Scheitern wäre dort falsch.

Die Abfrage selbst war im Slice-9-Bau bereits geschrieben und wieder entfernt,
weil sie ohne diese Entscheidungen keinen Aufrufer haben konnte.

## Berührte Stellen

- `adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleCheckPreflightProbe.kt`
  (Vorbild für die Sonden-Naht)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SegmentAwareMigrationExecutor.kt`
  (die Stelle, an der ausgeführt wird)
- `spec/ddl-generation-rules.md`, Abschnitt 11 (Zielbild sagt bereits: „Nach
  dem Anwenden ist `ALL_OBJECTS.status` zu prüfen")
