# MCP-E2E-Harness

Reproduzierbare End-to-End-Prüfung des `d-migrate`-MCP-Servers gegen das
**echte, gebaute Runtime-Image** — docker-compose + Bash-Skripte, exakt
analog [`../sample-db/`](../sample-db/README.md) und
[`../bi-demo/`](../bi-demo/README.md): **kein** Testcontainers, **kein**
Gradle-Testmodul.

- Plan: [`../../docs/planning/done/mcp-real-e2e-scope-matrix.md`](../../docs/planning/done/mcp-real-e2e-scope-matrix.md) (Teil B)

## Die Datenbanken

Der Stack traegt **alle fuenf Dialekte**, die d-migrate unterstuetzt:

| Dienst | Dialekt | Start |
| ------ | ------- | ----- |
| `postgres` | PostgreSQL (PostGIS-Image) | schnell |
| `mysql` | MySQL | schnell |
| `mssql` | SQL Server | ~30 s |
| `oracle` | Oracle | 2-3 Minuten, nur unter `--profile oracle` |
| — | SQLite | kein Dienst: eine Datei unter `out/` |

`make mcp-e2e-up` startet die drei schnellen; Oracle kommt nur mit
`mcp-e2e-roundtrip-oracle` dazu, weil sein Kaltstart die uebrigen Pfade
aufhalten wuerde.

**PostgreSQL faehrt auf dem PostGIS-Image**, damit Geometrie-Faelle ueberhaupt
messbar sind — aber mit **eigenem Init-Verzeichnis** (`initdb-postgres/`): die
Extension liegt im Schema `postgis`, nicht in `public`, und der `search_path`
der Datenbank nennt beide. Zwei Gruende: in `public` kaemen die rund tausend
PostGIS-Routinen als Anwenderobjekte in jeden Reverse, und das `DROP SCHEMA
public CASCADE` des Leerens naehme die Extension mit (gemessen: mit eigenem
Schema ueberlebt sie es, `geometry` bleibt aufloesbar). Der Matrix-Lauf prueft
die Lage der Extension und **scheitert laut**, wenn sie woanders steht — ein
Volume von vor dem Image-Wechsel ist schon initialisiert, das Init-Verzeichnis
liefe dort nicht mehr (`make mcp-e2e-purge` legt es neu an).

## Warum es diesen Harness gibt

Zwei bestehende Testebenen decken das MCP-Protokoll bereits ab
(In-Process-Szenarien, JVM-Real-Subprozess in `test/e2e-cli`), aber **keine
läuft gegen das tatsächlich gebaute Docker-Image** — genau die Ebene, die
Packaging-spezifische Defekte findet (fehlende Dateien im Image,
Non-root-Berechtigungen, Native-Image-Reflection-Lücken). Ausgelöst durch
eine manuelle Live-Prüfung, bei der `connections/list` fälschlich als
"nicht erreichbar" gemeldet wurde — der Code war korrekt, aber es gab
keinen automatisierten Beleg dafür.

## Was der Smoke prüft

`scripts/smoke-scope-matrix.sh` fährt `mcp serve --transport stdio` als
echten Container-Prozess (`docker compose run -T`, NDJSON-Requests per
stdin, Stdin-EOF beendet den Server sauber):

- **`admin`-Token** (alle Scopes via `isAdmin`): **alle 31 Einträge** aus
  `McpServerConfig.DEFAULT_SCOPE_MAPPING` (dieselbe Matrix wie Teil As
  `McpScopeEnforcementMatrixTest.kt` — hier im Skript gespiegelt, da Bash
  die Kotlin-Map nicht zur Laufzeit introspektieren kann) dürfen nicht
  scope-verweigert werden. `policy-rules.yaml` (universelle Allow-Regel)
  hebt zusätzlich den fail-closed-Policy-Default auf — vier der fünf
  `*_start`-Tools (`schema_reverse_start`, `schema_compare_start`,
  `data_profile_start`, `data_transfer_start`) bekommen echte,
  tenant-scoped Argumente und laufen damit **wirklich durch**: kein
  `POLICY_DENIED`, keine `VALIDATION_ERROR`, sondern ein echter Job
  (`jobId` + `resourceUri`) gegen die echte `postgres`-Verbindung.
  `data_import_start` bleibt bewusst bei `VALIDATION_ERROR` stehen — sein
  Handler löst `artifactId`/`sourceArtifactRef` gegen den Artefakt-Store
  auf, bevor `PolicyService` je aufgerufen wird; das bräuchte eine vorab
  angelegte Upload-Session (mehrere zusätzliche Aufrufe), keinen
  Argument-Fix.
- **`connections/list?checkLive=true`** gegen den echten `postgres`-Service
  — erwartet `REACHABLE` für die konfigurierte `mcp_e2e_pg`-Verbindung.
- **`noscope`-Token** (keine Scopes): **alle 31 Einträge** müssen
  scope-verweigert werden, in der jeweils passenden Form (7
  JSON-RPC-Protokollmethoden → `InvalidRequest`, 24 Tool-Namen via
  `tools/call` → `FORBIDDEN_PRINCIPAL` mit dem korrekten Scope-Namen).

Deckt sich vollständig mit Teil A
(`test/e2e-cli/.../McpScopeEnforcementMatrixTest.kt`) — dieser Harness
prüft dieselbe Matrix zusätzlich gegen das echte, gebaute Image.

## Cross-Dialekt-Hin-und-Her (`smoke-cross-dialect-roundtrip.sh`)

Der Scope-Smoke prueft MCP-Scopes gegen **eine** Postgres-Verbindung;
`examples/sample-db/` prueft Cross-Dialekt-Migrationen ohne diese
Dialekt-Matrix. Dieses Skript schliesst die Luecke: der ganze Weg

    Schema -> generate --target X -> anwenden -> reverse -> compare

fuer jeden Dialekt, gegen echte Server und das echte Image.

**Warum die CLI und nicht MCP.** Migrationen brauchen ein „DDL anwenden";
die MCP-Tools kennen das nicht (sie arbeiten ueber Artefakte und Jobs). Fuer
die Schema-*Qualitaet* ist die Ebene gleichgueltig — geprueft wird das Image
und der Server, nicht das Transportmittel. Der MCP-Weg bleibt beim
Scope-Smoke.

**Was es prueft.** Nicht nur „laeuft durch", sondern dass der Vergleich
nichts meldet, was keine Aenderung ist. Die Waechter
(`scripts/lib/compare-guards.sh`) arbeiten auf der JSON-Ausgabe von
`schema compare` und zielen auf **Klassen**, nicht auf eine Zeilenform:

| Waechter | schlaegt an bei |
| -------- | --------------- |
| `notation` | einem CHECK-, Index- oder Fremdschluessel-Fund, dessen beide Seiten ohne Leerraum, Anfuehrungszeichen, Klammern und eine ausdrueckliche `no_action` gleich sind — also nur Schreibweise (auch der unveraenderte Fremdschluessel, den ein Konsumentenprojekt gemeldet hatte) |
| `metadata` | einem Name- oder Versionsfund — die Quelle ist eine Datei, die andere Seite ein Reverse, und dessen Markierung ist keine Eigenschaft des Schemas |
| `sequence` | einem Erzeugungs-Fund zweier Identity-Spalten, die sich nur im Sequenznamen unterscheiden (den vergibt der Server) — geprueft an der Struktur `identity(schluessel=wert,…)`, nicht an einem einzelnen Token |
| `form` | einer Ausgabe, deren Form die Waechter nicht lesen koennen (ein unbekannter Schluessel eines Identity-Werts) |

**Selbstprobe.** Bevor die Waechter laufen, prueft die Vereinheitlichung, dass
sie die Ausgabe ganz versteht: in der CLI entspricht jede Zahl in `summary` der
Laenge der gleichnamigen Liste in `diff`, und eine geaenderte Tabelle oder
Spalte traegt nur bekannte Schluessel; ueber MCP hat `different` Funde,
`identical` keine, und jeder Aenderungsfund der Waechter-Arten traegt
`details`. Benennt ein Release einen Schluessel um, scheitert der Lauf **laut**
— ohne die Probe liefen die Waechter still leer. Ein jq-Fehler ist nie
„nichts gefunden". Die Fundzahl je Dialekt kommt aus dem JSON-Dokument, nicht
aus der Textausgabe.

Gross-/Kleinschreibung und Casts rechnet die Heuristik bewusst nicht zur
Schreibweise: die Schreibweise von Schluesselwoertern ist eine offene
Eigner-Frage, und ein Cast kann Bedeutung tragen. Gegen das Image `1.7.1`
gefahren, schlagen alle drei Waechter an (Platzhalter als Name und Version,
Constraint-Funde ohne Ausdruck, das Index-Praedikat, der Sequenzname).

Die Quellfixture traegt die Konstrukte, an denen Konsumentenbefunde hingen:
ein benanntes UNIQUE auf einer **ungebundenen** `text`-Spalte
(`uq_customer_external_ref`), die Sicht `order_summary`, einen CHECK mit `OR`
und `IS NULL`, einen CHECK mit Werteliste an einer `varchar`-Spalte, einen
`numeric`-CHECK `> 0`, einen LIKE-CHECK, einen Index mit Praedikat, eine
Identity-Spalte und eine berechnete Spalte. Der Reverse liest Sichten nur mit
`--include-views`, das der Lauf deshalb setzt.

**Ein Waechter gegen einen Rueckfall: der MySQL-Introducer.** MySQL legte die
String-Literale eines CHECK mit Zeichensatz-Introducer ab (`_latin1'…'`), der
Reverse uebernahm ihn, und die Validierung las ihn als Spalte (`E012`,
`schema compare` Exit 3) — MySQL war damit als „ungueltig" ausgewiesen. Seit
der Reader den Servertext in neutrale Schreibweise bringt (`spec/type-mapping.md`,
Abschnitt 4.5) tritt der Zustand nicht mehr auf. Die Erkennung bleibt im
Skript: sie ist eng auf genau diesen Grund gefasst — `E012` an einer „Spalte",
die ein MySQL-Zeichensatz mit Unterstrich ist (`_latin1`, `_utf8mb4`, …) —, und
jeder andere Grund fuer Exit 3, auch eine andere unbekannte Spalte mit
Unterstrich (`_tmp`), laesst den Lauf scheitern. Kommt der Zustand zurueck,
faellt er als Abweichung von der Zahl unten auf.

Gemessener Stand (2026-09-17, `d-migrate:dev` 1.8.0-SNAPSHOT mit dem
normalisierten MySQL-Reverse; Oracle nicht gefahren):

| Dialekt | Funde | Zusammensetzung |
| ------- | ----- | --------------- |
| PostgreSQL | 1 | 1 Tabelle: LIKE-CHECK (`~~`, Schluesselwort-Schreibweise) und Werteliste (`= ANY (ARRAY[…])`, bewusst ein Fund) |
| MySQL | 5 | 3 Tabellen (drei CHECKs in MySQLs Kleinschreibung, Enum inline statt als Typ, `legacy_serial_syntax` der Identity-Spalte — der Roundtrip erklaert keine Praeferenz), 1 Typ (Enum), 1 Sicht |
| SQL Server | 5 | 4 Tabellen (UNIQUE auf ungebundenem `text`, LIKE-Schreibweise, Werteliste als `OR`-Kette, abgeleiteter Typ der berechneten Spalte, Enum als Text mit CHECK, Identity-Modus `always`), 1 Typ |
| SQLite | 6 | 4 Tabellen (Typdynamik, Identity als `identifier(auto)`), 1 Typ, 1 Sicht |
| Oracle | — | nicht gefahren |

Gegenueber 2026-09-15 kamen die neuen Konstrukte hinzu; `OR`/`IS NULL`, der
`numeric`-CHECK, das Index-Praedikat und der Sequenzname der Identity-Spalte
melden in keinem Dialekt etwas.

**Jeder Dialekt wird angewandt.** Der Lauf faehrt die erzeugte DDL gegen den
echten Server, bevor er zurueckliest — PostgreSQL, MySQL und SQL Server ueber
ihren Client im Container, **SQLite** ueber `sqlite3` auf dem Host (die Datei
liegt im gemounteten `out/`) und **Oracle** ueber `sqlplus` im Oracle-Image
(`scripts/lib/dialects.sh`). Ohne diesen Schritt laese der Reverse eine leere
Datenbank zurueck.

## Compare-Matrix 5x5 (`smoke-compare-matrix.sh`)

Der Roundtrip vergleicht eine Datei mit je einem Reverse. Ein Konsument
vergleicht dagegen **Reverses untereinander**, und das ueber MCP. Die Matrix
faehrt genau das: jeder Dialekt ist einmal Quelle.

    fixtures/compare-matrix.yaml --generate--> Quelle Q --schema_reverse_start--> R(Q)
    je Ziel Z != Q:  R(Q) --generate--> Z --schema_reverse_start--> R(Z)
                     schema_compare(R(Q), R(Z))  und  schema_compare_start(R(Q), R(Z))

Generiert wird mit der CLI aus genau dem Schema, das der MCP-Reverse abgelegt
hat; angewendet mit dem Client des Dialekts (MCP kennt kein „anwenden").
Die Fixture ist dialektneutral und traegt die Compare-relevanten Konstrukte
(CHECK mit `OR`/`IS NULL`, Werteliste an `varchar`, `numeric > 0`, LIKE,
Index mit Praedikat, Autowert und Identity, berechnete Spalte,
Fremdschluessel ohne Aktion).

**Server-Konfiguration.** Der MCP-Server der Matrix liest die Verbindungen aus
`.d-migrate.yaml` und zusaetzlich `reverse.mysql.autoincrement_syntax:
identity` (der Lauf schreibt beides nach
`out/compare-matrix/server.d-migrate.yaml`). Ohne diese Praeferenz
unterschiede sich die Identity-Spalte der Fixture (`BY DEFAULT`) zwischen
PostgreSQL und MySQL immer auch in `legacy_serial_syntax` — der Waechter
`sequence` saehe den Sequenznamen dort nie allein und waere blind. Mit ihr ist
PostgreSQL → MySQL die Zelle, in der er anschlaegt, sobald der Vergleich den
Sequenznamen wieder wertet. Zwischen PostgreSQL und SQL Server bzw. SQLite
unterscheidet sich die Spalte ohnehin im Modus bzw. in der Erzeugung; dort
kann er nicht anschlagen.

**Ausgabe:** je Zelle die Zahl der Funde und ihre Codes. **Gepinnt** in
`expected/compare-matrix.env`, versionsgebunden (`EXPECT_VERSION`): weicht
eine Zelle ab, scheitert der Lauf. Nach bewusster Pruefung pinnt

```sh
make mcp-e2e-compare-matrix MCP_E2E_MATRIX_ARGS=--update-expectations
```

neu — den Diff der Datei vor dem Commit lesen. Gepinnt wird nur ein
gemessener Zustand: scheitert eine Erzeugung (Exit weder `0` noch `8`), ist
eine Fehlerklasse unbekannt (`apply:unbekannt`) oder schlaegt ein Waechter
an, schreibt auch `--update-expectations` die Datei **nicht** und endet rot.

**Nie gepinnt**, und in jeder Version verbindlich:

- `schema_compare` und `schema_compare_start` liefern dieselben Funde; das
  Job-Artefakt hat die Art `COMPARE` und genau `status`, `summary`,
  `findings`, und der Job zweier gespeicherter Schemata nennt nur dieses
  Artefakt;
- die Waechter aus dem Roundtrip (`metadata`, `notation`, `sequence`, `form`)
  samt Selbstprobe, hier auf den MCP-Funden von Werkzeug **und** Job;
- jeder `schema_reverse_start`-Job nennt genau zwei Artefakte: das Schema (Art
  `SCHEMA`) und den Reverse-Report (Art `REVERSE_REPORT`, `kind:
  connection`); der Report eines MySQL-Reverse bestaetigt die Praeferenz mit
  `R205`.

Ein Waechter kann nur anschlagen, wo die Matrix den Fall erzeugt: zwei
Reverses tragen nach dem Entfernen der Reverse-Markierung denselben
Platzhalter-Namen, `metadata` bleibt hier also stumm — ein Namens- oder
Versionsfund zwischen Datei und Reverse faellt im Roundtrip auf.

**Zustaende statt Zahlen.** Zwei Arten von Zellen messen nichts und sind als
Zustand gepinnt — verschwindet der Zustand, ist die Erwartung neu zu pinnen:

- `INVALID` / `E012-introducer`: das Reverse der Quelle ist ungueltig; die CLI
  erzeugt daraus keine DDL. **Tritt seit dem normalisierten MySQL-Reverse nicht
  mehr auf**; die Erkennung bleibt als Waechter gegen einen Rueckfall (siehe
  oben).
- `APPLY-FAIL` / `apply:<Fehlerklasse>`: das Ziel lehnt die aus dem Reverse
  der Quelle erzeugte DDL ab — ein Befund ueber Reader oder Generator.

Gemessener Stand (2026-09-17, `d-migrate:dev` 1.8.0-SNAPSHOT mit der
Server-Praeferenz `identity` fuer MySQL und den vier Seeds; Oracle nicht
gefahren):

| Quelle \ Ziel | PostgreSQL | MySQL | SQL Server | SQLite |
| ------------- | ---------- | ----- | ---------- | ------ |
| PostgreSQL | — | 11 | 11 | 22 |
| MySQL | 4 | — | 8 | 11 |
| SQL Server | 2 | 6 | — | 11 |
| SQLite | `APPLY-FAIL` | `APPLY-FAIL` | `APPLY-FAIL` | — |

| Zelle | Funde bzw. Zustand | Grund |
| ----- | ------------------ | ----- |
| PostgreSQL → MySQL | 11: die 6 aus der Fixture (3 CHECKs und die Berechnung entfallen, Index-Praedikat entfaellt, CHECK mit `OR`/`IS NULL` in Kleinschreibung) und 5 aus dem Seed (vier Array-Spalten als `json`, der Identity-Modus `always`) | Generator rendert PostgreSQL-Casts nicht (`E053`), MySQL kennt kein Index-Praedikat (`E057`), kein Array und kein `ALWAYS`; Schluesselwort-Schreibweise |
| PostgreSQL → SQL Server | 11: die 5 aus der Fixture, dazu vier Arrays und zwei `json`-Spalten als `text` (je `W137`) | Casts wie oben, `W140`, `W137` |
| PostgreSQL → SQLite | 22: die 13 aus der Fixture, dazu vier Arrays, zwei `json`, `decimal` → `float` (`W200`) und die Identity | SQLite-Typaffinitaet, Casts wie oben |
| MySQL → SQL Server / SQLite / PostgreSQL | 8 / 11 / 4 | s. oben, Zeile „MySQL" |
| SQL Server → PostgreSQL | 2: zweimal `W137` | der Berechnungsausdruck ist ohne Herkunft nicht entscheidbar; sonst nichts — seit der Reverse ihn ohne T-SQL-Quoting liefert |
| SQL Server → MySQL | 6: zwei CHECKs in MySQLs Schreibweise, Identity-Modus, Index-Praedikat entfaellt, zweimal `W137` | `E057`, Schluesselwort-Schreibweise, Darstellung der Werteliste; die PascalCase-Berechnung des Seeds rechnet dort richtig (der Generator setzt `"Menge"` in Backticks) |
| MySQL → PostgreSQL | 4: Werteliste (`in (…)` gegen `= ANY (ARRAY[…])`), CHECK mit `OR`/`IS NULL` in Kleinschreibung, zweimal `W137` | bewusst ein Fund (Darstellung eines Enums), Schluesselwort-Schreibweise, zwei unentscheidbare Berechnungsausdruecke (Fixture und Seed) |
| MySQL → SQL Server | 8: dieselben zwei CHECKs, Identity-Modus `always`, abgeleiteter Typ **und** Nullbarkeit der beiden berechneten Spalten, zweimal `W137` | `W140`; SQL Server leitet Typ und `NOT NULL` einer berechneten Spalte aus dem Ausdruck ab |
| MySQL → SQLite | 11: 10 Typen, Identity | SQLite-Typaffinitaet (Laenge, `decimal`, `datetime`), Identity als `identifier(auto)` |
| SQL Server → SQLite | 11: 10 Typen, Identity | SQLite-Typaffinitaet (die zwei `decimal`-Spalten des Seeds kommen dazu) |
| SQLite → PostgreSQL | `APPLY-FAIL` (`relation "uq_0" already exists`) | der SQLite-Reverse nennt jede unbenannte mehrspaltige UNIQUE-Klausel `uq_0`; der Seed hat zwei davon in zwei Tabellen. Bis P11 |
| SQLite → MySQL | `APPLY-FAIL` (`ERROR 1170`) | der SQLite-Reverse kennt keine Laenge; MySQL indiziert `TEXT` nicht ohne Praefix |
| SQLite → SQL Server | `APPLY-FAIL` (`Msg 2714`) | der SQLite-Reverse nennt die Fremdschluessel jeder Tabelle `fk_0` …; SQL Server verlangt eindeutige Namen |

### Native Typ-Seeds und der Silent-Loss-Check

Die Zahlen oben zaehlen **Vergleichsfunde**. Ein **Verlust** faellt dabei nicht
auf: geht eine Eigenschaft beim Lesen oder beim Erzeugen verloren, sind
hinterher beide Seiten gleich verloren, und die Zelle meldet null Funde. Der
Silent-Loss-Check (`scripts/lib/silent-loss.sh`) fragt deshalb etwas anderes:
**kommt an, was ankommen soll — und wo nicht, wird es gesagt?**

**Die Seeds.** Liegt `fixtures/seeds/<dialekt>.sql`, wendet der Lauf die Datei
nach der Fixture an; sie geht in den Reverse der Quelle ein und traegt, was nur
dieser Dialekt so zurueckgibt. Heute: `postgresql.sql` (Arrays, `json`/`jsonb`,
`numeric` ohne Praezision, `varchar` ohne Laenge, `interval`, zwei
IDENTITY-Spalten mit `ALWAYS`), `mysql.sql` (CHECK und Berechnungsausdruck mit
Zeichenkette, eine nicht kleingeschriebene Spalte) und `sqlite.sql` (benannte
und unbenannte Fremdschluessel, zweimal dieselbe unbenannte UNIQUE-Klausel,
`NUMERIC` ohne Praezision).

**Die Anmerkungen.** Jede Seed-Spalte traegt eine, und zwar **ausserhalb** der
`CREATE`-Anweisung — SQLite speichert Kommentare im Tabellentext mit, und die
Scanner des Readers kennen keine:

```
-- seed: <tabelle>.<spalte> | paket: <Paket> | quelle: <form> [| code: <Code|keinen>]
--   [generation: <gen>]
--   [ausdruck: <text>]
--   ziel <dialekt>: <form> | code: <Code|keinen> [| generation: <gen>] [| ausdruck: <text>]
```

`quelle` ist die neutrale Form, die der Reverse **dieser** Quelle liefern muss,
`ziel <d>` die Form im Reverse des Ziels; `code` nennt den Code, den der
zugehoerige Report dafuer traegt — oder ausdruecklich `keinen`. Die Anmerkungen
beschreiben den **Zielzustand**, nicht den Ist-Zustand.

**Vier Klassen** (`quelle`, `reftype`, `verloren`, `ziel`) pruefen daraufhin
den Reverse der Quelle, jeden Reverse eines Ziels und die beiden Reports. Ein
Verstoss ist ein Fehlschlag — **ausser** er steht wortgleich in der Liste
bekannter Befunde in `scripts/lib/silent-loss.sh`, mit dem Paket, das ihn
aufloest. Die Liste ist **Code**, keine Erwartung: `--update-expectations`
erweitert sie nicht, und ein Eintrag, der im Lauf **nicht** auftritt, ist
selbst ein Fehlschlag.

**Zwei weitere Schluesselfamilien** in der Erwartungsdatei, gepinnt wie
`CELL_`/`CODES_`:

- `REPORT_CODES_<DIALEKT>` — die Codes des Reverse-Reports der Quelle, mit
  Anzahl;
- `GEN_CODES_<QUELLE>_<ZIEL>` — die Codes aus dem Sidecar-Report des
  Generate-Schritts. Sie entstehen **vor** dem Anwenden und gelten deshalb auch
  fuer eine Zelle, die danach `APPLY-FAIL` wird.

Ein Paket, das eine Note einfuehrt, wird damit zu einem bewussten Neu-Pin.

**Das Modell des Checks** liest eine zweite, gleichlautende Reverse-Ausgabe der
CLI (`schema reverse --format json`, mit derselben Konfiguration wie der
MCP-Server): die Formen stehen im Schema-Dokument, das MCP-Artefakt ist YAML,
und der Harness hat keinen YAML-Leser. Die **Zellen** der Matrix kommen
unveraendert aus dem MCP-Reverse.

**Oracle** faehrt nur mit `make mcp-e2e-compare-matrix-oracle`; ohne diesen
Aufruf werden seine Zellen weder gemessen noch geprueft. Der Workflow
`MCP-E2E Compare-Matrix` faehrt die Matrix ohne Oracle (manuell, woechentlich
und bei Aenderungen am Harness, am `Makefile`, unter `make/` oder am
`Dockerfile`). Er ist kein PR-Gate und kein Pflicht-Check, wird bei einer
Abweichung aber **rot**; die Artefakte unter `out/compare-matrix/` haengen in
jedem Fall am Lauf.

## Benutzung

```sh
make docker-build IMAGE_TAG=dev   # einmalig: d-migrate:dev-Runtime-Image
make mcp-e2e-smoke                # up + voller Scope-Matrix-Lauf
make mcp-e2e-roundtrip            # Hin-und-Her-Migrationen, alle schnellen Dialekte
make mcp-e2e-roundtrip-oracle     # dasselbe mit Oracle (2-3 Min Kaltstart extra)
make mcp-e2e-compare-matrix       # 5x5-Vergleich ueber MCP gegen die gepinnten Erwartungen
make mcp-e2e-compare-matrix-oracle  # dasselbe mit Oracle
make mcp-e2e-down                 # Container stoppen (Volume bleibt)
make mcp-e2e-purge                # Container + Volume entfernen
```

Voraussetzungen am Host: `docker`, `docker compose`, `jq`, `bash` ab 4 sowie
**`sqlite3`** (fuer die SQLite-Legs — die Datenbank ist eine Datei, es gibt
keinen Dienst). Der Stack bleibt
nach dem Lauf stehen (Cleanup über `mcp-e2e-down`/`-purge`).

## Sicherheit

`stdio-tokens.yaml` und die Rohtoken in `scripts/smoke-scope-matrix.sh` sind
**fest verdrahtete Dev-Only-Werte** für einen lokalen, isolierten
Compose-Stack — keine echten Secrets, nicht für Produktion.
