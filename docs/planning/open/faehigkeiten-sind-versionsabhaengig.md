# Fähigkeiten sind versionsabhängig — die Tabelle tut so, als wären sie es nicht

> Status: **offen** — Befund gemessen, Eigner-Entscheidung zur Default-Regel
> liegt vor, Umfang noch nicht geschnitten.
> Trigger: beim Bau von
> [`generated-column-expression-dropped.md`](generated-column-expression-dropped.md)
> ausgeliefert und sofort widerlegt.

## Der Auslöser

`supportsVirtualComputedColumns = false` wurde für PostgreSQL ausgeliefert, mit
der Begründung „`VIRTUAL` ist ein Syntaxfehler, live gemessen". Gemessen war das
gegen `postgres:16`. Auf **18.6** ist `VIRTUAL` gültig und sogar die **Vorgabe
ohne Angabe**. Die Fähigkeit war vom Tag ihrer Auslieferung an falsch — und
CI hätte es nie gemerkt, weil dort nur 16 läuft.

## Der Befund (gemessen)

`DialectCapabilities` führt **25 Fähigkeiten**, alle nur nach Dialekt
geschlüsselt (`forDialect(dialect)`, **59 produktive Aufrufstellen**). Die
Bauweise unterstellt, ein Dialekt habe feste Fähigkeiten.

Der Code weiß längst, dass das nicht stimmt — er umgeht die Tabelle an **drei**
Stellen mit **drei verschiedenen Mustern**:

| Muster | wo | Beispiel |
| --- | --- | --- |
| `ServerVersion` (sealed) | `hexagon/ports-read` | Oracles `supportsDropIfExists` (23+), MySQLs CHECK-Enforcement |
| `RoutineCapability` + `minServerVersion` | `hexagon/ports-read`, CLI-überschreibbar | Routinen je Kind und Serverversion |
| Ad-hoc-Schwelle im Renderer | je Adapter | `CREATE OR REPLACE TRIGGER` ab PG 14, `GREATEST` ab SQL Server 2022, `RENAME COLUMN` ab SQLite 3.25 |

Ein vierter Ad-hoc-Fall wäre der Auslöser oben. Genau das soll dieser Slice
verhindern.

## Entscheidung (Eigner, 2026-09-10): unbekannte Version = **aktuellste bekannte**

Nicht der konservativste Wert. Der Grund ist der Charakter des Fehlers:
„konservativ" heißt hier nicht „nichts tun", sondern **etwas anderes rendern,
als der Autor geschrieben hat** — wer `stored: false` schreibt und `STORED`
bekommt, verliert seine Angabe still. Die optimistische Wahl erzeugt dagegen
ein Skript, das entweder läuft oder auf einem älteren Server mit einem klaren
Syntaxfehler **laut scheitert**. Lautes Scheitern schlägt stille Degradation.

Dazu: der Default greift **nur bei Dateizielen**. Sobald eine Verbindung
besteht, ist die Version bekannt — und bei einem Dateiziel liest ein Mensch das
Skript, bevor er es anwendet.

**Bedingung, ohne die die Regel wieder zur Vermutung wird:** „aktuellste
bekannte" heißt *die neueste, die d-migrate gemessen hat*. Sie gehört je Dialekt
an **eine** Stelle gepinnt, und die Fähigkeits-Suite in CI muss gegen genau
diesen Pin laufen. So trägt der Default die Testmatrix, statt von ihr
abzuhängen.

## Was zu schneiden ist

- **Die Signatur.** `forDialect(dialect)` → `forTarget(dialect, serverVersion?)`.
  59 Aufrufstellen; der Umbau läuft über `make ast-grep`. Die meisten Stellen
  kennen ihr Ziel bereits, es geht also weniger um Änderungen als um
  Durchreichen.
- **Welche der 25 Fähigkeiten wirklich versionsabhängig sind.** Nicht alle sind
  es; die Liste ist zu messen, nicht zu schätzen. Bekannt ist bisher genau eine
  (`supportsVirtualComputedColumns`, PG ab 18).
- **Die drei bestehenden Muster einsammeln.** Ein `ServerVersion` neben einer
  version-blinden Fähigkeitstabelle ist die eigentliche Unstimmigkeit. Ob
  `RoutineCapability` mit hineingehört (es hat einen CLI-Override, den die
  Tabelle nicht kennt), ist eine eigene Frage.
- **Die Ausweichtür für Dateiziele:** eine ausdrückliche Angabe der
  Zielserverversion, damit wer bewusst für einen alten Server erzeugt, das sagen
  kann — statt auf einen Default zu treffen.

## Die Vorbedingung ist erfüllt (2026-09-11)

Die Spanne steht normativ im Lastenheft (Abschnitt 3.3): PostgreSQL 14,
MySQL 8.0.16, SQL Server 2017, Oracle 23ai, SQLite treibergebunden — geprüft
jeweils gegen die aktuelle Version.

Damit ist auch die Liste der Schwellen endlich, die `forTarget` kennen muss:
alles zwischen Unter- und Obergrenze. Zwei sind schon gemessen und benannt —
virtuelle berechnete Spalten ab PostgreSQL 18, `SET EXPRESSION` ab 17. Beim
Festlegen der Spanne kam eine dritte dazu: der Oracle-Adapter rendert `json`
und `array` als nativen `JSON`-Typ, den es erst ab 21c gibt. Das ist der
Grund, warum Oracle nicht auf 19c zugesagt werden konnte — und zugleich ein
Beispiel dafuer, dass eine Faehigkeit heute unsichtbar an einer Version haengt.

## Nicht-Scope

- Die **unterstützte Versionsspanne** selbst — sie steht jetzt fest (siehe oben).
- Der Ausbau der Testmatrix. Er folgt aus der Spanne, nicht aus dieser
  Umstellung.
