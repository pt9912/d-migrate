# Oracle-Leg im Sample-DB-Harness (Slice 3b)

> **Status:** Draft mit Scope (2026-09-06).
> **Trigger:** Oracle Slice 3 (`data export`/`import`/`transfer`, ADR 0052)
> hat den Datenpfad geliefert, aber die im Slice-Schnitt vorgesehene
> **3b** (sample-db-Oracle-Leg im Harness) bewusst nicht mitgezogen —
> Eigner-Entscheidung, als eigener Folge-Schnitt statt in Slice 3
> eingebettet (anders als beim MSSQL-Vorbild, das 3+3b gemeinsam
> geliefert hat, `docs/planning/done/mssql-dialect-scoping.md` Zeile 218).

> **Status-Update 2026-09-06: erledigt.** P0–P5 gebaut, der Lauf ist
> **gruen**: Pagila PG → Oracle mit `sqlplus`-angewandter DDL,
> `--verify OK` (18 Ausschluesse gepinnt), Paritaet ueber alle 15 Tabellen,
> datenbelegte Typkonvertierungen, Schluesseltreue.
>
> **Der Weg dorthin hat sechs Defekte im ausgelieferten Pfad aufgedeckt.**
> Keiner war durch die DDL-Goldens abgedeckt — deren Fixtures enthalten die
> noetigen Kombinationen nicht:
> - `NOT NULL DEFAULT x` — Oracle verlangt die DEFAULT-Klausel VOR der
>   Constraint (`ORA-03076`). Im Golden ist keine DEFAULT-Spalte NOT NULL.
> - `CACHE 1` — Oracle verlangt >= 2 oder `NOCACHE` (`ORA-04010`).
>   PostgreSQLs Sequenz-Default IST 1, also traf es jede reverse-gelesene
>   PG-Sequenz.
> - **`TIMESTAMP WITH TIME ZONE` als Schluesselspalte** (`ORA-02329`) —
>   dieselbe Fehlernummer wie CLOB/BLOB, aber die weniger bekannte Haelfte:
>   PG, MySQL und SQL Server erlauben es. Pagilas `payment`-PK laeuft
>   darueber.
> - **Jede Anweisung lief doppelt.** Hinter jeder `;`-terminierten Anweisung
>   stand ein `/`. In SQL*Plus beendet `/` keinen Batch (wie T-SQLs `GO`),
>   sondern fuehrt den Puffer ERNEUT aus. Bei DDL fiel es als `ORA-00955`
>   auf; bei einem Datenskript waere es ein doppelter INSERT gewesen. Ueber
>   JDBC waere es nie sichtbar geworden.
> - **JSON-Spalten waren nicht lesbar.** ojdbc verlangt
>   `oracle.jdbc.jsonDefaultGetObjectType`; ohne sie scheitert JEDER
>   Lesezugriff mit `ORA-18722` — betraf Oracle als Transfer-Quelle,
>   `data export` und den `--verify`-Rueckleseweg, weil `Array` und `Json`
>   beide auf `JSON` abbilden.
> - **`--verify` meldete Abweichungen, die keine sind.** Oracle speichert
>   `''` als NULL; eine nullbare Textspalte mit leerem Quellwert wich damit
>   immer ab. Der Vergleich faltet beides jetzt zusammen.
>
> Der leere String in **NOT-NULL**-Spalten ist ueber den
> Praeferenz-Mechanismus geloest (`write.oracle.empty_string`,
> [`dialect-preference-mechanism.md`](../../../spec/dialect-preference-mechanism.md)),
> nicht durch eine stille Umformung. Der Harness deklariert seine Wahl in
> der eigenen `.d-migrate.yaml`. **4b ist damit entblockt.**

> **Status-Update 2026-09-06 (Slice 4b):** die Gegenrichtung laeuft
> ebenfalls — `smoke-cross-ora2pg.sh`, dreifache Zeilen-Paritaet
> (Original == Oracle == Rueckziel), `--verify OK`,
> Rueckwaerts-Konvertierungen datenbelegt.
>
> **Ein siebter Defekt, den nur die Rueckrichtung zeigen konnte:** Oracle
> hat keinen BOOLEAN-Spaltentyp, der Hinweg legt `NUMBER(1)` an — und der
> Oracle-Reverse rekonstruiert daraus wieder `boolean`, das PG-Rueckziel
> entsteht also mit `boolean`. Der Datenpfad reichte die rohe Zahl aber
> unveraendert weiter, und PostgreSQL nimmt in eine `boolean`-Spalte keine
> Zahl an (*column is of type boolean but expression is of type numeric*).
> Schema und Daten sagten Verschiedenes. Die Umsetzung sitzt jetzt an der
> Schreibgrenze, wo der Zieltyp bekannt ist.
>
> Nebenbefund beim Beheben: die Bedingung am JDBC-Typcode griff nicht —
> pgjdbc meldet `boolean` je nach Version als `BOOLEAN` **oder** als `BIT`.
> Der Typname ist der belastbare Anker.
>
> **Was das Leg bewusst nicht prueft**, und das steht auch im Skript: Wert-
> Identitaet Original == Rueckziel. Oracle setzt `''` mit NULL gleich; ein
> leerer String kommt als NULL zurueck (nullbare Spalten) bzw. als der
> erklaerte Ersatztext (NOT NULL). Dieser Verlust ist Oracles Semantik,
> nicht ein Fehler des Werkzeugs.

## Ist-Zustand

`examples/sample-db/` ist der Cross-Dialekt-Round-Trip-Harness (ADR 0013
Sourcing, ADR 0014 Fetch-and-Compose). Jeder registrierte Dialekt außer
Oracle hat darin ein "Leg": ein `docker-compose.yml`-Service, `.env`-Variablen
(Port, Credentials), ein Paar `smoke-cross-<dialekt>2pg.sh`/
`smoke-cross-pg2<dialekt>.sh`-Skripte und eine gepinnte Notes-/Zeilenzahl-
Baseline unter `expected/`. Das MSSQL-Leg
(`examples/sample-db/scripts/smoke-cross-pg2ms.sh`,
`examples/sample-db/scripts/smoke-cross-ms2pg.sh`) ist das jüngste, direkt vergleichbare
Vorbild: Pagila PG laden → `schema reverse` → `schema generate --target mssql
--split pre-post` → Pre-Data per `sqlcmd` anwenden → `data transfer --verify`
→ Zeilen-Paritäts- und Typ-Stichproben gegen eine gepinnte Baseline.

Oracle hat weder einen `docker-compose.yml`-Service noch `.env`-Variablen
noch Smoke-Skripte in diesem Harness. Der reale Oracle-Testcontainer-Einsatz
in diesem Projekt beschränkt sich bisher auf `test/integration-oracle`
(isolierte, kleine JDBC-Fixtures) und die neuen `test/e2e-cli`-Tests
(`OracleSchemaGenerateE2ETest`, `OracleTransferE2ETest`) — beide bauen ihre
eigenen, kleinen Testcontainer direkt im Testcode auf, nicht über das
Sample-DB-Harness mit dem echten Pagila-Datensatz.

## Ziel

Ein Oracle-Leg im Sample-DB-Harness, analog zum MSSQL-Leg: Pagila-Cross-
Dialect-Smoke PostgreSQL → Oracle (und optional zurück, falls Slice 5
`schema reverse` gegen das erzeugte Oracle-Schema dafür reif genug ist —
siehe Nicht-Scope) mit gepinnter Notes-/Zeilenzahl-Baseline, als CI-fähiger
Smoke-Test (oder zumindest manuell wiederholbar analog Phase 2 der
bestehenden Harness-Phasen).

## Scope-Skizze

1. **P0 — `docker-compose.yml`-Service.** `oracle`-Service analog zum
   `mssql`-Block (Zeile ~110-134): Digest-gepinntes `gvenzl/oracle-free`-Image
   (wie in `test/integration-oracle`/`test/e2e-cli` bereits verwendet,
   `23-slim-faststart`), Port-Mapping über eine neue `SAMPLE_DB_ORACLE_PORT`-
   `.env`-Variable, Healthcheck (z. B. `sqlplus`- oder JDBC-basierter Connect-
   Test — prüfen, ob das Image ein CLI-Tool für einen einfachen Healthcheck
   mitbringt, sonst auf einen simplen TCP-Check ausweichen).
2. **P1 — `.env.example`-Ergänzung.** `SAMPLE_DB_ORACLE_PORT` + ggf.
   Credential-Variablen (Oracle-Testcontainer-Defaults `system`/generiertes
   Passwort — prüfen, ob `.env`-Overrides überhaupt nötig sind oder das
   Test-Image feste Test-Credentials mitbringt wie bei PG/MySQL).
3. **P2 — Smoke-Skripte.** `smoke-cross-pg2ora.sh` (Pagila PG → Oracle:
   reverse → `generate --target oracle` → Anwenden — **kein `sqlcmd`-Äquivalent
   nötig**, Slice-3-Erkenntnis aus `OracleTransferE2ETest` nutzen (JDBC-
   `Statement.execute()` pro Anweisung nach Entfernen der `/`-Batch-Trenner)
   → `data transfer --verify` → Zeilen-Parität + Typ-Stichproben). Optional
   `smoke-cross-ora2pg.sh` (umgekehrte Richtung) — nur wenn Oracle
   `schema reverse` (bereits aus Slice 1 vorhanden) für das komplexere
   Pagila-Schema ausreicht; sonst als eigener Nicht-Scope-Punkt vermerken.
4. **P3 — Gepinnte Baseline.** `pagila-cross-oracle.notes.txt` (unter
   `expected/`, analog `pagila-cross-ms.notes.txt`) + eine `EXPECTED_VERIFY_EXCLUSIONS`-
   Konstante fürs `--verify`-Skript, aus einem echten Erstlauf abgeleitet,
   nicht geraten.
5. **P4 — README/Doku.** `examples/sample-db/README.md`-Phasentabelle um die
   Oracle-Zeile ergänzen (Phase 2, analog MSSQL); `docs/planning/in-progress/
   oracle-dialect-scoping.md` Slice-3b-Zeile auf ✅ setzen, wenn geliefert.
6. **P5 — CI-Einbindung.** Prüfen, ob/wie der bestehende Sample-DB-Smoke-
   Workflow (welcher Workflow das MSSQL-Leg fährt) den Oracle-Container-
   Ressourcenbedarf (RAM/Startzeit, laut `OracleContainerConnectIntegrationTest`
   ~2-3 Min Kaltstart) verkraftet, oder ob ein separates, opt-in/nightly
   Gate wie bei Phase 3 (Employees-Scale) sinnvoller ist.

## Akzeptanzkriterien

- `make sample-db-smoke-cross-oracle` (oder analog benanntes Target) baut
  Pagila in PostgreSQL, transferiert per `data transfer --verify` nach
  Oracle, und die Zeilen-/Typ-Stichproben stimmen gegen eine gepinnte
  Baseline überein.
- Notes-Baseline ist aus einem echten Lauf abgeleitet (keine geratenen
  E-/W-Codes), analog `pagila-cross-ms.notes.txt`.
- `docker-compose.yml`/`.env.example` bleiben für alle bestehenden Legs
  unverändert nutzbar (rein additiv).

## Nicht-Scope

- Oracle→PostgreSQL-Rückrichtung (`smoke-cross-ora2pg.sh`), falls die
  Komplexität des reversed Oracle-Schemas (Partitionierung E055,
  Volltext/Spatial nicht gescoped) die Baseline unverhältnismäßig
  aufwendig macht — dann als eigener Trigger-Eintrag in `open/` vermerken.
- Employees-Scale- oder Spatial-Phasen für Oracle (Phase 3/Spatial-Analogon)
  — Oracle Spatial ist laut ADR 0052 gar nicht gescoped.
- CI-Standard-Gate-Aufnahme, falls der Ressourcenbedarf das PR-Gate zu sehr
  verlangsamt — dann opt-in/nightly wie Phase 3.
