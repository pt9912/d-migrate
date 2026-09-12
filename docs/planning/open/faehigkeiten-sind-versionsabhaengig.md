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
| Ad-hoc-Schwelle im Renderer | je Adapter | **widerlegt** — siehe „Die bestehenden Muster einsammeln“: keines der drei Beispiele ist eine Schwelle im Dialekt-Code |

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

## Der Schaden ist heute lieferbar (gemessen 2026-09-12)

Nicht mehr nur „die Fähigkeit ist falsch". Gemessen gegen **PostgreSQL 18.6 —
die Version, die in CI läuft** (`TestImages.POSTGRESQL` ist `postgres:18-alpine`):

```
virtual:    CREATE TABLE pv (a int, b int GENERATED ALWAYS AS (a*2) VIRTUAL)  → OK
no keyword: CREATE TABLE pn (a int, b int GENERATED ALWAYS AS (a*2))          → OK
attgenerated:  pv.b = 'v'   pn.b = 'v'   ps.b = 's'
```

`VIRTUAL` ist gültig, und **ohne Angabe entsteht ebenfalls die virtuelle Form**.
Daraus wird eine geschlossene Kette stiller Degradation:

1. Der Autor schreibt `stored: false`.
2. `TypeCanonicalizerWiring` faltet `stored` auf `true`, weil
   `supportsVirtualComputedColumns = false` — der Vergleich sieht keinen
   Unterschied mehr.
3. `PostgresColumnConstraintHelper` rendert unbedingt `STORED`.
4. Der Server legt `attgenerated = 's'` an.
5. Der Leser liest korrekt `stored = true` zurück (er wertet `attgenerated`
   aus, er ist als einziger Teil der Kette schon version-fest).
6. Der Round-Trip ist sauber — und die Angabe des Autors ist weg.

Kein Schritt der Kette meldet etwas. Genau die stille Degradation also, gegen
die die Eignerentscheidung unten „laut scheitern" setzt.

**Die Kette hat genau eine Aufrufstelle je Glied**, und das ist die gute
Nachricht: `supportsVirtualComputedColumns` wird produktiv **einmal** gelesen
(`TypeCanonicalizerWiring.kt`, `foldsStored`). Der erste Schnitt braucht deshalb
nicht die Signatur aller 59 Stellen anzufassen.

## Der Schnitt

Vier Scheiben. Die Reihenfolge ist die Aussage: erst den Mechanismus an **einem**
gemessenen Fall bauen, dann erst verbreitern.

### A — Der Mechanismus, an einer Fähigkeit

- `forDialect(dialect)` bekommt ein Geschwister `forTarget(dialect,
  serverVersion: ServerVersion?)`. `forDialect` bleibt und ruft `forTarget(d,
  null)` — damit müssen die 59 Stellen **nicht** in dieser Scheibe wandern.
- Je Dialekt **eine** gepinnte „neueste gemessene Version" (die
  Eignerentscheidung: unbekannt = aktuellste bekannte). Der Pin gehört neben die
  Fähigkeitstabelle, und die Test-Images müssen gegen genau diesen Pin laufen —
  ein Test, der Pin und `TestImages` vergleicht, hält die beiden zusammen.
- `supportsVirtualComputedColumns` wird die erste versionsabhängige Fähigkeit:
  `false` unter PostgreSQL 18, `true` ab 18, `true` für die übrigen vier.
- Die eine Aufrufstelle (`TypeCanonicalizerWiring`) reicht die Version durch —
  sie kennt das Ziel bereits.

**Akzeptanz:** eine Live-Spec gegen PG 18 schreibt `stored: false` und findet
danach `attgenerated = 'v'`; dieselbe Spec gegen einen 17er-Pin bekommt
`STORED` **und eine Meldung**, nicht stillschweigend.

### A — gebaut (2026-09-12)

`DialectCapabilities.forTarget(dialect, serverVersion)` steht neben
`forDialect(dialect)`, das unverändert bleibt und `forTarget(d, null)` ruft —
die 59 Aufrufstellen mussten deshalb nicht wandern.
`supportsVirtualComputedColumns` ist die erste versionsabhängige Fähigkeit
(PostgreSQL ab 18), und die eine Stelle, die sie produktiv liest
(`TypeCanonicalizerWiring`), bekommt die Version durchgereicht.

**Der Pin steht an einer Stelle und wird gegen die Testmatrix gehalten.**
`MeasuredServerVersions` führt je Dialekt die neueste gemessene Version;
`MeasuredServerVersionsPinTest` (im Modul `test:test-images`, läuft im normalen
Build mit) hält sie gegen die Bilder, gegen die die Suite fährt. Wer ein Bild
anhebt, hebt den Pin mit an — oder fällt auf. Damit trägt der Default die
Testmatrix, statt von ihr abzuhängen.

**Beim Bauen kam dazu, was die Kette sonst nur verschoben hätte:**

- Der **Renderer** musste mit. Die Faltung allein wegzunehmen hätte aus stiller
  Degradierung eine nicht konvergierende Migration gemacht: der Vergleich sähe
  den Unterschied, der Renderer schriebe weiter `STORED`, und der Post-Compare
  meldete Drift bei jedem Lauf. Beide Pfade (generate und migrate) setzen die
  Speicherform jetzt nach der Zielversion.
- **Die Speicherform zu wechseln** ist kein `SET EXPRESSION` — der Befehl lässt
  die Spalte, wo sie ist. Virtuell ↔ gespeichert blockt deshalb mit eigener
  Meldung, statt eine Anweisung zu erzeugen, die nichts tut.
- **Vier Versionstypen sind nach `ports-common` gewandert** (`ServerVersion`
  und die drei Ausprägungen). Sie lagen in `ports-read`, die Fähigkeitstabelle
  liegt darunter — ohne den Umzug hätte `forTarget` sie nicht sehen können.
  Dass beide Module dasselbe Paket benutzen, machte den Umzug importfrei.
  Kosten: `ports-read` fiel damit unter die 90-Prozent-Schwelle, weil vier gut
  abgedeckte Klassen es verließen. Geschlossen wurde das durch Tests für zwei
  Typen, die **gar keine** hatten (`MysqlSequenceSupportNaming`,
  `MssqlHashPartitionMode`) — nicht durch Absenken der Schwelle.

Live belegt gegen PostgreSQL 18.6: `stored: false` landet als
`attgenerated = 'v'`, der Reverse liest es als virtuell zurück, und ein zweiter
Lauf plant nichts. Sabotage-geprüft, auch der Pin-Abgleich.

### B — Die Liste der versionsabhängigen Fähigkeiten, gemessen

Nicht alle 25 sind es, und geschätzt wird nicht. Die Erhebung läuft gegen die
Grenzen der Spanne aus dem Lastenheft 3.3 (PostgreSQL 14, MySQL 8.0.16, SQL
Server 2017, Oracle 23ai, SQLite treibergebunden) — je Fähigkeit **eine**
Messung an der Untergrenze und eine an der Obergrenze. Wo beide gleich
antworten, ist die Fähigkeit versionsfest und bleibt, wo sie ist.

Drei Kandidaten sind schon benannt und kosten keine Suche mehr:

| Fähigkeit / Verhalten | Schwelle | Beleg |
| --- | --- | --- |
| `supportsVirtualComputedColumns` | PostgreSQL 18 | oben gemessen |
| `SET EXPRESSION` für berechnete Spalten | PostgreSQL 17 | `PostgresServerVersion.SET_EXPRESSION_SINCE_MAJOR` |
| `json`/`array` → nativer `JSON`-Typ | Oracle 21c | Grund, warum Oracle nicht auf 19c zugesagt wurde |

**Nicht-Scope von B:** die Fähigkeiten umzustellen. B erhebt nur, was
umzustellen ist.

### C — Die bestehenden Muster einsammeln

**Nachgemessen (2026-09-12), und die Aufzählung oben war falsch.** Vor dem Bau
einmal durchgesehen, welche Ad-hoc-Schwellen es wirklich gibt — von den drei
genannten hält keine stand:

| Genannt | Befund |
| --- | --- |
| `CREATE OR REPLACE TRIGGER` ab PG 14 | **keine Ad-hoc-Schwelle.** Läuft längst über `TriggerCapabilityDefaults` + `TriggerPlanningContext`; der Renderer-KDoc sagt ausdrücklich, dass er die Fähigkeit *nicht* selbst nachschlägt. Und weil die zugesagte Spanne bei 14 beginnt, ist die Konstante durch die Spanne gedeckt. |
| `GREATEST` ab SQL Server 2022 | **existiert im Dialekt-Code nicht.** `GREATEST` kommt im Repo nur in `persistence-jdbc` vor — dem PostgreSQL-**only** Backing-Store des Servers. Es ist keine Zieldialekt-Frage. |
| `RENAME COLUMN` ab SQLite 3.25 | **kein Server, sondern eine Abhängigkeit.** SQLite ist treibergebunden; gemessen liefert der mitgelieferte Treiber 3.53.4. Es gibt keinen älteren SQLite-Server, für den man erzeugen könnte. |

Damit fällt auch die vermeintliche Vorbedingung weg: **SQL Server und SQLite
brauchen heute keinen `ServerVersion`-Typ**, weil keine einzige Fähigkeit an
einem hinge. Zwei Typen samt Lesepfad und Pin für null Verwender zu bauen wäre
Vorgriff.

Gemessen wurde dabei auch, wie die beiden ihre Version überhaupt ausweisen —
falls je ein Verwender auftaucht, steht es hier statt in einer Vermutung:

```
SQL Server   SERVERPROPERTY('ProductVersion') = 17.0.4085.5,
             ProductMajorVersion = 17, Banner = "SQL Server 2025"
             → Marketingjahr ≠ Hauptversion (2017=14, 2019=15, 2022=16, 2025=17)
SQLite       sqlite_version() = 3.53.4
```

### Was von C übrig bleibt

Die **wirklich** versionsabhängigen Stellen sind vier, auf drei Dialekten, und
jeder davon hat seinen Versionstyp schon:

| Stelle | Schwelle | Heutige Form |
| --- | --- | --- |
| PostgreSQL `SET EXPRESSION` | ≥ 17 | `PostgresServerVersion.supportsSetExpression` |
| PostgreSQL virtuelle berechnete Spalte | ≥ 18 | `DialectCapabilities.forTarget` (Scheibe A) |
| Oracle `DROP … IF EXISTS` | 23+ | `OracleServerVersion.supportsDropIfExists` |
| MySQL Routinen | `minServerVersion` | `RoutineCapability`, CLI-überschreibbar |

Die Zusammenlegung der ersten drei auf `forTarget` wäre **kein reiner Umbau**,
und das ist der Grund, warum C hier anhält statt weiterzulaufen:

**„Unbekannte Version" bedeutet nicht bei jeder Fähigkeit dasselbe.** Die
Eignerentscheidung (unbekannt = aktuellste bekannte) steht auf der Begründung,
dass „konservativ" hier hieße, *etwas anderes zu rendern, als der Autor
geschrieben hat*. Das trifft auf `supportsVirtualComputedColumns` zu — dort
steht `stored: false` in der Schemadatei. Auf `supportsDropIfExists` trifft es
**nicht**: kein Autor hat `IF EXISTS` verlangt, es ist eine Bequemlichkeit der
Rücknahme-Anweisungen. Würde dieses Flag durch `forTarget` laufen, kippte sein
Default für Dateiziele stillschweigend von „weglassen" auf „hinschreiben" — und
ein Oracle-19-Ziel bekäme einen Syntaxfehler für etwas, das niemand wollte.

**Zu entscheiden ist deshalb zuerst:** bekommt `forTarget` eine Auskunft je
Fähigkeit darüber, was „unbekannt" heißt (optimistisch vs. konservativ), oder
bleiben Flags dieser zweiten Art bewusst draußen? Vor dieser Antwort ist die
Zusammenlegung kein Fortschritt, sondern eine verschobene Entscheidung.

**Ausdrücklich weiter offen:** ob `RoutineCapability` hineingehört. Es hat einen
CLI-Override, den die Tabelle nicht kennt; das ist ein eigener Entwurf.

### D — Die Ausweichtür für Dateiziele — gebaut (2026-09-12)

`--target-version` steht auf `schema generate` **und** `schema migrate`. Ohne
die Angabe bleibt es beim Pin; mit ihr entscheidet sie die versionsabhängigen
Fähigkeiten.

**Gegen eine lebende Datenbank gewinnt die Angabe.** Sie sagt, wofür das
Skript gedacht ist, nicht, woraus gelesen wurde — ein Lauf gegen Staging, der
für die ältere Produktion erzeugt, ist ein wirklicher Fall. Übersteuert wird an
**einer** Stelle im Runner (`withTargetVersion`), nicht an den vier, die die
Version lesen; sonst wäre die nächste Lesestelle die, die es vergisst.

Ein Dialekt ohne strukturelle Version (SQL Server, SQLite) nimmt die Option
nicht entgegen, sondern sagt es und endet mit Exit 2 — eine stillschweigend
ignorierte Option wäre schlechter als keine.

## Aufwand, ehrlich geschätzt

A ist klein (eine Fähigkeit, eine Aufrufstelle, ein Pin, zwei Specs) und
liefert den ganzen Nutzen des ersten gemessenen Falls. **B ist die teuerste
Scheibe** — bis zu 25 Fähigkeiten × zwei Versionsgrenzen × fünf Dialekte, und
die Untergrenzen brauchen Container-Images, die es in `TestImages` heute nicht
gibt. Wer den Slice aufteilt, teilt ihn hier.

C hängt an keiner Vorarbeit mehr — die vermeintliche ist widerlegt —, sondern
an **einer Entscheidung**: was „unbekannte Version" je Fähigkeit bedeutet. D ist
gebaut.

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
