# Security-Audit d-migrate — Abschlussbericht

> **Status:** Sammlung / Referenz (2026-07-17)
> **Trigger:** Erstes systematisches Security-Vollaudit der Codebase, angesetzt
> vor 1.0.0-final. Bis dahin gab es weder ein Threat-Model-Dokument noch eine
> Disclosure-Policy; die Sicherheitslage war nie als Ganzes erhoben.
> **Aktivierungsbedingung:** Dieser Bericht ist ein Referenzdokument und wandert
> nicht als Ganzes nach `next/`. Die handlungsbedürftigen Befunde sind als
> eigene Einträge ausgeschnitten (siehe „Ausgeschnittene Tickets"); dieser
> Bericht bleibt als Beleg, warum ein Befund P1/P2/P3 ist und warum die
> verworfenen Verdachtsfälle keine sind.

**Datum:** 2026-07-17 · **Umfang:** Vollaudit über 12 Angriffsflächen (credential-store, mcp-auth, mcp-surface, sql-injection, jdbc-url, secret-leakage, path-traversal, s3-storage, deserialization, crypto, runtime-packaging, supply-chain) · **Verfahren:** Befund → 3-fache unabhängige Gegenprüfung (code-reality / exploitability / adr-context) → Severity-Konsens

## Wirksamkeit der Security-Infrastruktur (Stand 2026-07-17)

**GitHub liest `SECURITY.md` und `.github/dependabot.yml` ausschließlich vom
Default-Branch `main`** — auf `develop` sind sie wirkungslos. Das ist der Grund,
warum die beiden Dateien unterschiedlich weit sind:

| Artefakt | Stand |
| -------- | ----- |
| `.github/dependabot.yml` | live auf `main` (`64de223d`). Die Fassung auf `develop` ist seit der Toolchain-Härtung (siehe unten) **weiter** — der 1.0.0-Merge trägt die Härtung nach `main`. Bis dahin öffnet Dependabot auf `main` weiter Toolchain-PRs |
| `SECURITY.md` | nur auf `develop`; GitHub zeigt „Security policy" bis zum 1.0.0-Merge als *Disabled*. Bewusst so — kommt mit Schritt 4.3 in [`releasing.md`](../../user/releasing.md) regulär mit, **kein** Sonderschritt nötig |
| `.github/workflows/dependency-submission.yml` | nur auf `develop`; triggert auf `push: main` und läuft daher erst ab dem 1.0.0-Merge. Bis dahin sind **Gradle-Alerts blind** (siehe unten) |

**Repo-Einstellungen** (Settings → Security and quality). Ein Merge erledigt
davon nichts:

| Stufe | Stand | Bedeutung |
| ----- | ----- | --------- |
| Private vulnerability reporting | ✅ aktiv | Der in `SECURITY.md` beschriebene Meldekanal existiert. Funktioniert unabhängig von `main` |
| Dependency graph | ✅ aktiv | Unterbau der Alerts |
| Automatic dependency submission | ⛔ bewusst **aus** | Siehe „Warum nicht die Automatik" |
| Dependabot alerts | ✅ aktiv | …aber derzeit nur für Actions/Docker wirksam, nicht für Gradle |
| Dependabot version updates | ✅ live | Nutzt Dependabots **eigenen** Gradle-Parser, nicht den Dependency graph — funktioniert daher unabhängig vom Graph-Problem unten |

### Warum nicht die Automatik

GitHubs „Automatic dependency submission" wurde aktiviert und ist
**fehlgeschlagen** (Run `29582554903`): sie ignoriert den Wrapper-Pin des
Projekts (Gradle **8.12**) und fährt mit **Gradle 9.6.1**. Dagegen ist
`com.github.johnrengelman.shadow:8.1.1` (`adapters/driving/cli/build.gradle.kts:11`)
inkompatibel — `Could not set unknown property 'fileMode'`; das Property fiel in
Gradle 9 weg. Der Build stirbt in der Konfigurationsphase, bevor irgendeine
Dependency gemeldet wird.

Ersetzt durch `.github/workflows/dependency-submission.yml` mit
`gradle/actions/dependency-submission` <!-- d-check:ignore (GitHub-Action-Referenz, kein Repo-Pfad) --> (SHA-gepinnt), die den Wrapper des
Projekts benutzt. Die Automatik ist deshalb bewusst ausgeschaltet — sonst
scheitert sie bei jedem `main`-Push rot, ohne je etwas zu liefern.

### Messbarer Ist-Zustand des Graphen

Der SBOM (`gh api /repos/pt9912/d-migrate/dependency-graph/sbom`) enthält
**6 Pakete**: 5 GitHub-Actions plus das Root-Projekt. **Null Gradle-Dependencies.**
Grund: 94 der 105 Dependencies deklarieren ihre Version dynamisch als
`${rootProject.properties["…"]}` aus `gradle.properties` (keine
`libs.versions.toml`); statisches Parsen sieht davon nur die 11 literalen.
Actions und Docker sind statisch parsebar und daher bereits abgedeckt.

**Konsequenz bis 1.0.0:** Dependabot-Alerts sind aktiv, decken aber nur Actions
und Docker ab. Ein CVE in Jackson, snakeyaml oder einem JDBC-Treiber löst
**keine** Warnung aus. Das ist bewusst in Kauf genommen (Branch-Workflow bleibt
unangetastet); mit dem 1.0.0-Merge läuft der Workflow und der Graph füllt sich.
Verifikation danach: SBOM-Paketzahl muss deutlich über 6 liegen.

Anmerkung zu **Dependabot security updates** (auto-PRs für Alerts, derzeit aus):
Security-Updates ignorieren die `ignore`-Regeln der `dependabot.yml`. Bei einem
CVE in einem JDBC-Treiber käme der Major-Bump-PR also trotzdem — die bewusste
Ausnahme „JDBC-Majors nur über die Cross-Dialect-Matrix" greift dort nicht.

### Toolchain-Härtung (aus dem PR-Flut-Vorfall 2026-07-17)

Beim Aktivieren öffnete Dependabot sofort 13 PRs. Drei davon waren
Toolchain-Bumps, die kein Auto-PR sein dürfen — der Vorfall deckte eine Lücke in
der ersten `dependabot.yml` auf (JDBC-Majors waren ausgenommen, die
Kotlin/Gradle-Toolchain nicht):

- **Kotlin 2.1.20 → 2.4.10** (`jvm-minor-patch`-Gruppe): CI rot. Ein Minor-Bump
  ändert Compiler-/Kover-Verhalten — die Line-Coverage des winzigen
  3-Datei-Aggregators `:hexagon:ports` fiel auf 72,22% unter das 90%-Gate. Kein
  Flake, deterministisch an den Bump gekoppelt.
- **Gradle-Base-Image 8.12 → 9.6** (`docker`): CI rot. `shadow:8.1.1` ist mit
  Gradle 9 inkompatibel (`fileMode`). CI fängt es korrekt.
- **Gradle-Wrapper 8.12 → 9.6.1** (`gradle`): CI **grün** — aber ein False-Green.
  Der Docker-Build nutzt `gradle` aus dem Base-Image statt des Wrappers, CI ist <!-- d-check:ignore (Root-Datei außerhalb der codepaths.roots) -->
  für den Wrapper blind. Merged bräche es das lokale Wrapper-Skript und den <!-- d-check:ignore (Root-Datei außerhalb der codepaths.roots) -->
  Submission-Workflow (dasselbe shadow-Problem).

Reaktion: `dependabot.yml` auf `develop` um Ignore-Regeln für Kotlin/Kover
(Minor+Major), `gradle-wrapper` (alle) und das `gradle`-Base-Image (Major)
erweitert; Patch-Bumps bleiben erlaubt. Alle 13 PRs geschlossen. Beides greift
auf `main` erst mit dem 1.0.0-Merge.

Randbefund: `com.github.johnrengelman.shadow` ist die unmaintainte Koordinate
(Nachfolger `com.gradleup.shadow`) und ist die gemeinsame Ursache, dass Gradle 9
blockiert. Eigenes Migrations-Ticket wert, sobald ein Gradle-9-Umstieg ansteht.

**Ausgeschnittene Tickets** — gruppiert nach gemeinsamer Wurzel bzw. Fix-Ort,
nicht eins-je-Befund, damit ein Fix nicht über mehrere Einträge zersplittert:

| Ticket | Deckt Befunde |
| ------ | ------------- |
| [`mysql-string-literal-backslash-escaping.md`](mysql-string-literal-backslash-escaping.md) | 1 (P1), 3 (P2) |
| [`export-filename-untrusted-identifier.md`](export-filename-untrusted-identifier.md) | 2 (P1) |
| [`mcp-auth-url-scheme-validation.md`](mcp-auth-url-scheme-validation.md) | 5 (P2), 7 (P3), 12 (P3) |
| [`mcp-http-preauth-hardening.md`](mcp-http-preauth-hardening.md) | 4 (P2), 13 (P3), 14 (P3) |
| [`release-supply-chain-pinning.md`](release-supply-chain-pinning.md) | 6 (P2), 15 (P3), 16 (P3), 17 (P3) |
| [`jdbc-ssl-default-hardening.md`](jdbc-ssl-default-hardening.md) | 8 (P3), 9 (P3) |
| [`secret-leakage-residuals.md`](secret-leakage-residuals.md) | 10 (P3), 11 (P3) |

---

## Executive Summary

Das Sicherheitsniveau von d-migrate ist insgesamt solide — und an mehreren Stellen deutlich überdurchschnittlich. Der Credential-Store (AES-256-GCM, PBKDF2 600k, Header-als-AAD, 0600-Dateien, konsequentes Key-Wiping), die JWT-Validierungskette (alg-Allowlist ohne `none`/`HS*`, fail-closed Scope-Auflösung, Clock-Skew-Deckel) und die Path-Safety-Allowlist im Artefakt-Store (`[A-Za-z0-9_-]{1,128}` als Vollmatch) sind sauber gebaut und haben allen Widerlegungsversuchen standgehalten. Das Identifier-Quoting im SQL-Pfad ist über alle drei Dialekte hinweg korrekt und lückenlos zentralisiert.

Das drängendste Problem liegt genau dort, wo das Werkzeug seine Kernaufgabe erfüllt: **an der Grenze zur untrusted Quell-Datenbank.** Beide P1-Befunde teilen dieselbe Wurzel — Metadaten aus einem fremden Schema (Spalten-DEFAULTs, Tabellennamen) werden als vertrauenswürdig behandelt, obwohl das Bedrohungsmodell die Quell-DB ausdrücklich als untrusted führt. Beim MySQL-Ziel führt das zu einem SQL-Literal-Ausbruch per Backslash, beim `--split-files`-Export zu arbiträrem Datei-Schreibzugriff mit Operator-Rechten. Symptomatisch ist, dass die Validierung jeweils auf der *vertrauenswürdigen* Seite sitzt (`--tables` wird streng geprüft) und auf der *nicht* vertrauenswürdigen fehlt — mit einem Kommentar, der die falsche Annahme sogar ausschreibt.

Auf P2-Ebene dominieren zwei Cluster: die MCP-HTTP-Fläche (unauthentifizierter unbegrenzter Body-Read vor der Bearer-Validierung; fehlender https-Zwang auf der JWKS-URL, dem Vertrauensanker der gesamten Token-Prüfung) und die Release-Supply-Chain (Tap-Write-Token an eine Third-Party-Action auf mutablem Tag).

Von 27 gemeldeten Befunden haben 18 die Gegenprüfung überlebt, 9 wurden als False Positives verworfen — mehrere davon mit lehrreichen Widerlegungen, die im Bericht dokumentiert sind, damit sie nicht erneut auditiert werden.

**Bilanz:** 2× P1 · 5× P2 · 11× P3

---

## P1 — Sofort beheben

> **Beide P1 BEHOBEN 2026-07-17** (`447a9006` MySQL-Backslash, `d8b6d2de`
> Export-Traversal; Docker `build koverVerify` je grün). Details in den Tickets
> [`mysql-string-literal-backslash-escaping.md`](mysql-string-literal-backslash-escaping.md)
> und [`export-filename-untrusted-identifier.md`](export-filename-untrusted-identifier.md).
> Beim MySQL-Fix aufgetauchter, getrennt zu lösender Folgebefund (P2):
> [`partition-bound-literal-backslash.md`](partition-bound-literal-backslash.md).

### 1. MySQL-DEFAULT-String-Literale escapen keinen Backslash → SQL-Injection aus fremdem Quell-Schema

**Severity:** P1 · **CWE-89** · **Fläche:** sql-injection · **Status:** ✅ behoben (`447a9006`)

**Fundstellen:**
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt:49` (Defekt)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlColumnConstraintHelper.kt:39` (Generate-Pfad)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt:58` (Diff-Pfad)
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/SqlIdentifiers.kt:54` (backslash-unsichere Basis)
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapping.kt:198` (Quelle)

**Beschreibung:**
`MysqlTypeMapper.toDefaultSql` rendert einen `DefaultValue.StringLiteral` als einfach-gequotetes MySQL-Literal und escapet dabei ausschließlich `'` → `''`. MySQL behandelt bei Default-`sql_mode` (ohne `NO_BACKSLASH_ESCAPES`) den Backslash als Escape-Zeichen — die reine Quote-Verdopplung genügt dort nicht. Ein Wert, der auf `\` endet, wird zu `'…\'`: der Backslash escapet das schließende Quote weg, das Literal läuft weiter und verschluckt den nachfolgenden DDL-Text bis zum nächsten `'`; der Rest wird als SQL geparst.

Dass dies die bekannte Lücke ist, belegt der Schwester-Codec im selben Modul: `MysqlSequenceSqlCodec.quoteStringLiteral` macht korrekt `SqlIdentifiers.quoteStringLiteral(value.replace("\\", "\\\\"))`. Die Backslash-Verdopplung existiert als bewusstes Muster im MySQL-Treiber — der DDL-Renderpfad wendet sie nur nicht an. `d-migrate` setzt `NO_BACKSLASH_ESCAPES` auf seinen MySQL-Verbindungen nirgends; es gilt der unsichere Default.

Der Reverse-Pfad ist verifiziert: `PostgresTypeMapping.parseDefault` zerlegt `'a\'::text` per `substringAfter("'")`/`substringBefore("'::")` zu `StringLiteral("a\")` — der Backslash überlebt das Reverse-Engineering unverändert.

**Angriffsszenario:**
Angreifer ist der Betreiber der Quell-Datenbank (fremdes PG- oder SQLite-Schema, das ein Operator nach MySQL migriert — der Kern-Use-Case). Er legt zwei präparierte Spalten-DEFAULTs an:

```sql
col_a TEXT DEFAULT 'a\'                        -- Wert endet auf Backslash
col_b TEXT DEFAULT ', x INT); DROP TABLE kunden; -- '
```

In PG ist beides gültig (`standard_conforming_strings=on`). Beim `schema generate`/`migrate` auf MySQL entsteht:

```sql
`col_a` TEXT DEFAULT 'a\', `col_b` TEXT DEFAULT ', x INT); DROP TABLE kunden; -- '
```

MySQL parst `'a\', \`col_b\` TEXT DEFAULT '` als *ein* Literal; der Rest fällt heraus und wird ausgeführt.

**Wichtige Präzisierung aus der Gegenprüfung (alle drei Prüfer unabhängig):** `allowMultiQueries` kommt im Repo nirgends vor, `MysqlJdbcUrlBuilder` baut eine blanke URL, Connector/J-Default ist `false`. Auf dem JDBC-`migrate --execute`-Pfad läuft das angehängte `; DROP TABLE` daher **nicht** als Zweitstatement. Der Impact-Ceiling wird über zwei andere Routen erreicht: (a) `schema generate` emittiert ein DDL-Skript, das im dokumentierten Workflow dem `mysql`-Client gefüttert wird — dort ist Multi-Statement normal und die Payload läuft voll; (b) selbst über JDBC bleibt der Breakout *innerhalb* des einen `CREATE TABLE` (injizierte Zusatzspalten, entfernte NOT-NULL-Constraints, manipulierte Table-Options, geschluckte fremde Defaults).

**Empfehlung:**
Backslash-Verdopplung dialekt-bewusst in `SqlIdentifiers.quoteStringLiteral` ziehen (Signatur um `dialect` erweitern, analog zu `quoteIdentifier`), damit die Pflicht nicht pro Aufrufer wiederholt werden muss. Der Duplikat-Charakter der beiden Fundstellen (siehe P2-Befund 3) ist genau das Symptom der fehlenden Single-Source. Der KDoc von `SqlIdentifiers` („The result is always safe for interpolation") ist nachweislich falsch und muss mit korrigiert werden; sein Verweis auf `docs/quality.md` zeigt zudem auf eine **nicht existierende Datei** <!-- d-check:ignore (die Nichtexistenz IST der Befund) -->. Beim Fix prüfen, ob `PartitionLiteralGuard` (Denylist `;`/`--`/`/*`, kennt den Backslash ebenfalls nicht) konsistent nachzieht.

---

### 2. Path-Traversal beim FilePerTable-Export: Tabellenname aus der untrusted Quell-DB wird ungeprüft zum Dateinamen

**Severity:** P1 · **CWE-22** · **Fläche:** path-traversal · **Status:** ✅ behoben (`d8b6d2de`)

**Fundstellen:**
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt:262` (sequentieller Pfad)
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt:325` (paralleler Pfad)
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt:115` (Auto-Discovery)
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt:372` (CREATE|TRUNCATE_EXISTING)
- `hexagon/ports-write/src/main/kotlin/dev/dmigrate/streaming/ExportOutput.kt:95`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt:29` (die falsche Vertrauensannahme, ausgeschrieben)

**Beschreibung:**
Bei `data export --output <dir> --split-files` ohne `--tables` stammt die Tabellenliste aus dem Katalog der Quell-DB. Die Lister liefern den Roh-Identifier unquotiert durch. Dieser String wird per String-Interpolation zum Dateinamen (`"$table.${format.cliName}"`) und ohne `normalize()`/`startsWith()`-Prüfung gegen das Ausgabeverzeichnis aufgelöst. Geschrieben wird mit `CREATE, TRUNCATE_EXISTING, WRITE` — anlegen **oder** bestehende Datei leeren und überschreiben. Beide Pfade (sequentiell und parallel) sind betroffen.

Der Kern ist eine explizit im Code niedergeschriebene, falsche Vertrauensannahme: `ExportPreflightValidator.resolveTables` validiert ausgerechnet die vom **Operator** getippten `--tables`-Werte streng gegen `TABLE_IDENTIFIER_PATTERN`, lässt die aus der **untrusted Quell-DB** stammenden Namen aber ungeprüft durch — mit der Begründung, diese seien „keine User-Eingabe". Die Prüfung sitzt auf der vertrauenswürdigen und fehlt auf der nicht vertrauenswürdigen Seite.

Verschärfend: Das Projekt besitzt bereits einen zweckgebauten Guard für exakt diese Bug-Klasse — `hexagon/ports-write/src/main/kotlin/dev/dmigrate/migration/ArtifactRelativePath.kt` mit dokumentierter Garantie „No parent escapes (`..`) after normalization". Er ist in die Migrations-Artefakt-Exporter verdrahtet, **nicht** in den Daten-Export-Dateinamenpfad. Es ist also eine übersehene Anwendung der eigenen Invariante, keine verteidigbare Design-Position.

**Angriffsszenario:**
Akteur: wer die Quell-DB kontrolliert oder darin eine Tabelle anlegen darf — z. B. der Absender einer zu migrierenden `.sqlite`-Datei (das dokumentierte m-trace-Consumer-Szenario: eine fremde DB-Datei wird wie ein Dokument entgegengenommen).

```sql
CREATE TABLE "../../../../home/op/.config/app/config.yaml" (k TEXT);
```

Der Operator führt `d-migrate data export --source <fremde-db> --output ./out --split-files --format yaml` aus (ohne `--tables` = Auto-Discovery-Default). Empirisch verifiziert: SQLite akzeptiert den Namen, `sqlite_master` liefert ihn wörtlich zurück, ein `resolve` des Traversal-Namens gegen das Ausgabeverzeichnis normalisiert aus dem Zielbaum heraus. <!-- d-check:ignore (fiktiver Beispielpfad im Angriffsszenario, kein Maschinen-Layout) -->

**Zwei Verschärfungen aus der Gegenprüfung:**
- Der `../`-PoC bricht die nachfolgende SELECT (`quoteQualifiedIdentifier` splittet auf `.`), es bliebe nur Truncation. Ein **absoluter Tabellenname ohne Punkte** umgeht das vollständig: `CREATE TABLE "<absoluter-pfad>"` <!-- d-check:ignore (fiktiver Beispielpfad im Angriffsszenario, kein Maschinen-Layout) --> → `resolve` mit absolutem Argument verwirft die Basis (kein `..`, kein `normalize` nötig), und die SELECT läuft durch → arbiträrer Datei-Write **mit voller Inhaltskontrolle**, nicht nur Truncation.
- Der `Files.newOutputStream(..., TRUNCATE_EXISTING)` wird **vor** der Query geöffnet — eine fehlschlagende SELECT rettet nichts, die Zieldatei ist bereits geleert.

**Ehrliche Einschränkung:** Die Formatendung wird immer angehängt (`.csv`/`.json`/`.yaml`/`.parquet`), und Parent-Dirs werden für eskapte Pfade nicht angelegt. `~/.bashrc` ist damit nicht direkt überschreibbar — Konfigurationsdateien (`config.yaml`, `docker-compose.yaml`, CI-Configs) sehr wohl.

**Empfehlung:**
`ArtifactRelativePath`-Guard (oder äquivalentes `normalize()` + `startsWith()`) in `StreamingExporter` auf beiden Pfaden anwenden — **vor** dem Öffnen des Streams, sonst bleibt die Truncation. Der Guard muss zusätzlich **absolute** Namen ablehnen. Alternativ/ergänzend: `TABLE_IDENTIFIER_PATTERN` auch auf auto-discovered Namen anwenden bzw. den Dateinamen aus dem Identifier deterministisch sanitisieren. Der Kommentar in `DataExportHelpers.kt:29` muss ersatzlos entfernt werden — er dokumentiert eine Annahme, die dem Bedrohungsmodell direkt widerspricht.

---

## P2 — Zeitnah beheben

### 3. MySQL inline-ENUM-Werte escapen keinen Backslash

**Severity:** P2 · **CWE-89** · **Fläche:** sql-injection

**Fundstellen:**
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlEnumColumnRenderer.kt:35`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlColumnConstraintHelper.kt:53` (Generate-Pfad)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt:39` (Diff-Pfad)
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt:214` (Quelle: rohe `enumlabel`-Strings)

**Beschreibung:**
Gleiche Injection-Klasse wie P1-Befund 1, eigener Renderer mit eigener, duplizierter Escaping-Zeile: `MysqlEnumColumnRenderer.inline` baut die `ENUM('a','b')`-Werteliste per Konkatenation und escapet nur `'` → `''`. Ein Enum-Wert, der auf `\` endet, escapet sein schließendes Quote weg. **Ein Fix in `MysqlTypeMapper` allein schließt diese Stelle nicht** — der Renderer ruft `SqlIdentifiers.quoteStringLiteral` nicht einmal auf, ein zentraler Fix dort träfe ihn also ebenfalls nicht.

**Korrektur der ursprünglichen Mitigations-Annahme (alle drei Prüfer):** Der `DIALECT_UNSUPPORTED`-Block via `MysqlDiffOtherOps.renderCreateCustomType` greift **nur im Diff-Pfad**. Der Generate-Pfad ist offen: `MysqlColumnConstraintHelper.columnEnum` löst `type.refType` gegen `schema.customTypes` auf und ruft `columnEnumInline` ungeblockt. Der kanonische PG→MySQL-`schema generate`-Flow mit `CREATE TYPE … AS ENUM` erreicht Zeile 35 ungeschützt.

**Angriffsszenario:**
```sql
CREATE TYPE status AS ENUM ('ok\', ') , x INT); DROP TABLE audit; -- ');
```
PG-Enum-Labels sind beliebiger Text und dürfen auf `\` enden. Gerendert wird `ENUM('ok\', ') , x INT); DROP TABLE audit; -- ')`; MySQL schluckt `'ok\', '` als ein Literal. Wie bei Befund 1 gilt: über JDBC bleibt der Breakout innerhalb des `CREATE TABLE`; das volle Multi-Statement läuft über den `schema generate` → `mysql`-Client-Workflow.

**Empfehlung:** Gemeinsam mit Befund 1 über die dialekt-bewusste `quoteStringLiteral`-Naht lösen; den Renderer auf diese Naht umstellen statt eine dritte Escaping-Kopie zu pflegen.

---

### 4. MCP-HTTP: unauthentifizierter, unbegrenzter Request-Body wird vor der Bearer-Validierung vollständig in den Heap gelesen und geparst

**Severity:** P2 · **CWE-770** · **Fläche:** mcp-surface / deserialization *(Duplikat: von zwei Auditoren unabhängig gefunden, hier zusammengeführt)*

**Fundstellen:**
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/McpHttpRoute.kt:226` (`call.receiveText()` ohne Cap)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/McpHttpRoute.kt:142` (Reihenfolge: Origin → Accept → **parseBody** → Bearer)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerBootstrap.kt:124` (`embeddedServer(factory = CIO)` ohne Body-Limit-Plugin)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/ResponseLimitEnforcer.kt:48` (Cap misst den bereits geparsten Baum)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/protocol/McpServiceImpl.kt:272` (einziger Aufrufer, tief im Dispatch)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/OriginValidator.kt` (`if (origin == null) return true`)

**Beschreibung:**
`parseBody(...)` läuft vor `validateBearer(...)`. `call.receiveText()` materialisiert den kompletten Body als String (mit UTF-16-Verbreiterung), danach baut Gson daraus einen Objektbaum. Für keinen der beiden Schritte existiert eine Byte-Obergrenze: Es gibt im gesamten MCP-Modul **keinen einzigen `install(...)`-Aufruf** (grep verifiziert), also keine Ktor-Plugin-Pipeline; Ktor 3.0.3 kennt das `RequestBodyLimit`-Plugin noch gar nicht, und CIO setzt keinen Default.

Der scheinbar zuständige Cap `maxNonUploadToolRequestBytes = 262_144` greift nicht: `ResponseLimitEnforcer.enforceRequestSize` bekommt den bereits **geparsten** `JsonElement` und misst ihn durch **Re-Serialisierung** (`gson.toJson(it).toByteArray().size`). Er ist konstruktionsbedingt eine Nachkontrolle auf einem Baum, der schon im Speicher liegt — und wird dennoch von `CapabilitiesListHandler` nach außen als Request-Limit beworben (`spec/ki-mcp.md` macht das zur verbindlichen Auskunft).

Die vorgelagerten Gates sind credential-frei: `checkAccept` ist ein Header-String-Match, und `OriginValidator.isAllowed` gibt bei fehlendem Origin bewusst `true` zurück (korrekt als CSRF-Gate, aber für einen Nicht-Browser-Client ein No-op).

**Angriffsszenario:**
Angreifer: jeder mit TCP-Zugriff auf den Port. Nicht-Loopback-Bind ist first-class unterstützt und spec-dokumentiert (`--bind 0.0.0.0 --auth-mode jwt-jwks` steht wörtlich als Production-Setup in `spec/mcp-server.md`). Ein einzelner `POST /mcp` ohne `Authorization`, ohne `Origin`, mit `Accept: application/json, text/event-stream` und mehreren hundert MiB bis GiB chunked Body — der Inhalt muss **kein gültiges JSON** sein, der OOM tritt schon in `receiveText()` vor dem Parse ein. Ergebnis: `OutOfMemoryError`/GC-Thrashing; da der Server laufende Migrations-/Transfer-Jobs im selben Prozess hält, reißt der OOM in-flight-Jobs mit. 401 kommt erst, nachdem der Body längst gelesen wurde.

**Einordnung:** Reine Verfügbarkeitswirkung (kein C/I-Verlust), Default-Bind ist `127.0.0.1` — das begrenzt den Radius. P2 trägt, weil der spec-gesegnete Netzwerk-Modus anderswo bewusst gehärtet wird (Origin-Allowlist, https-Zwang, Exit 2 bei Konfigverstoß) und der Angriff weder Token noch Scope noch Session braucht.

**Empfehlung:**
Inbound-Byte-Cap auf Engine-/Pipeline-Ebene einziehen (Content-Length-Prüfung plus begrenztes Streaming-Read), **vor** `receiveText()`. Die Caps aus `McpLimitsConfig` dort verankern, damit die per `capabilities_list` beworbene Zusage auch transportseitig gilt. Die Reihenfolge Parse-vor-Auth ist zwar beabsichtigt (`params.name` wird für den Scope-Lookup gebraucht) und muss nicht zwingend gedreht werden — der Byte-Cap ist reihenfolge-unabhängig lösbar. Carve-Out „Cross-JVM-Service-Mode-Verträge" (Rate-Limit) adressiert das nicht: ein einziger Request genügt.

---

### 5. jwksUrl ohne https-Zwang: MITM kann JWKS ersetzen und Tokens fälschen

**Severity:** P2 · **CWE-319** · **Fläche:** crypto / mcp-auth

**Fundstellen:**
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt:98` (`jwksAuthErrors()` — nur Null-Check)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt:172` (Kontrast: `publicBaseUrl` erzwingt https)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/auth/JwksAuthValidator.kt:44` (`JWKSourceBuilder.create(URL(jwksUrl.toString()))`)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeRunner.kt:145` (`URI::create` ohne Prüfung)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpCommands.kt:99` (Clikt-Option ohne `check`)

**Beschreibung:**
`jwksAuthErrors()` prüft `jwksUrl` ausschließlich auf `null`, nie auf das Schema. Der Wert wird roh an Nimbus weitergereicht — ein `http://`-JWKS-URL wird ohne Warnung akzeptiert und der **Vertrauensanker der gesamten Bearer-Validierung** ([ADR 0009](../../adr/0009-mcp-resource-server-no-auth-server.md): „Signatur via JWKS") im Klartext geholt. Die Asymmetrie steht in derselben Datei: für `publicBaseUrl` erzwingt `sharedErrors()` explizit https, für den sicherheitskritischeren Schlüsselbezugspfad nicht. Ein repo-weiter grep nach `jwksUrl.scheme` liefert 0 Treffer; die einzige Scheme-Prüfung im gesamten MCP-Adapter ist Zeile 172.

Die Algorithmus-Allowlist hilft nicht: Der Angreifer liefert einen eigenen RSA-Public-Key und signiert RS256 korrekt.

`JWT_JWKS` ist zudem der **Default-authMode** und nicht loopback-beschränkt — also genau der Modus für Nicht-Loopback-Binds, wo der Netzwerk-Angreifer laut Trust-Boundary im Modell ist.

**Angriffsszenario:**
Operator konfiguriert `--auth-mode jwt-jwks --jwks-url http://keycloak.intern/realms/x/protocol/openid-connect/certs` (plausibel bei internem Keycloak ohne TLS-Terminierung; die CLI-Hilfe trägt für `--public-base-url` den Hinweis „MUST be https", für `--jwks-url` keinerlei Transporthinweis). Ein Angreifer mit Netzwerkposition zwischen d-migrate und dem IdP (ARP-Spoofing, kompromittierter Hop, DNS-Spoofing) beantwortet den Klartext-Fetch mit einem selbst erzeugten RSA-JWK. Nimbus cached ihn pro Prozess. Der Angreifer mintet danach beliebige RS256-JWTs mit korrektem `iss`/`aud`/`exp` und frei gewählten `scope`-/`tenant_id`-Claims. `JwksAuthValidator.validate` akzeptiert sie; `ClaimsMapper` setzt `isAdmin=true`; `ScopeChecker.isSatisfied` kurzschließt jedes method-level Gate. Ergebnis: vollständiger Auth-Bypass inkl. Tenant-Wahl und `data_transfer_start`, ohne je ein echtes Token gesehen zu haben.

**Empfehlung:**
Schema-Guard analog zu `publicBaseUrl` — aber **nicht unbedingt**: `introspectionAuthErrors()` waivet die Client-Credential-Pflicht bewusst für Loopback, und `http://localhost:8080/realms/x/protocol/openid-connect/certs` ist die gängige Dev-Keycloak-Form. Der richtige Guard ist „https außer Loopback-Host", gekoppelt an das bestehende `bindIsLoopback()`-Muster. **Der Fix muss `introspectionUrl` mit abdecken** (siehe P3-Befund 12) — es ist dieselbe systemische Scheme-Validierungs-Omission. Die entsprechende Regel gehört zusätzlich in `spec/mcp-server.md`, wo derzeit nur „MÜSSEN gesetzt sein" steht.

---

### 6. Third-Party-Action mit Tap-Write-Token auf mutablem Tag `@v3` statt Commit-SHA gepinnt

**Severity:** P2 · **CWE-1357** · **Fläche:** runtime-packaging

**Fundstellen:**
- `.github/workflows/release-homebrew.yml:64` (`uses: Justintime50/homebrew-releaser@v3` + `secrets.HOMEBREW_TAP_GITHUB_TOKEN`)
- `.github/workflows/release-homebrew.yml:100` (`verify-homebrew` — greift nicht)
- `.github/workflows/build.yml:149` (**gleiche Klasse, unmitigiert:** `secrets.DOCKERHUB_TOKEN` → `docker/login-action@v3`)

**Beschreibung:**
Der Release-Job übergibt ein Token mit Schreibrechten auf `pt9912/homebrew-d-migrate` an eine Third-Party-Action, die per **mutablem Git-Tag** referenziert ist. GitHub Actions löst `@v3` zur Dispatch-Zeit auf den Stand auf, auf den der Tag gerade zeigt. Die Integrität des Homebrew-Distributionskanals hängt damit an einem Account, den d-migrate nicht kontrolliert.

Der `verify-homebrew`-Job ist keine Detektionskontrolle: er greppt die Formula nur auf `d-migrate-${VERSION}` und smoke-testet `d-migrate --version`/`--help` — beides behält ein Angreifer trivial bei, während er den `install:`-Ruby-Block manipuliert.

**Zwei Korrekturen an der ursprünglichen Begründung (alle drei Prüfer unabhängig, für das Ticket relevant):**
1. Die Prämisse „widerspricht dem repo-weiten Pin-Kontrakt" ist **faktisch falsch** — **keine** Action im Repo ist SHA-gepinnt (`actions/checkout@v4`/`@v6`, `actions/upload-artifact@v6`, `docker/login-action@v3` über 12 Workflows). Die Digest-Pins (`SEMGREP_IMAGE`, `A_CHECK_IMAGE`, `DCHECK_DIGEST`) betreffen hermetische Offline-Scanner-Images in `make/gate.mk` — andere Domäne, Reproduzierbarkeits- statt Trust-Motiv. Es gibt keinen verletzten Kontrakt, nur einen durchgängig fehlenden.
2. Die Prämisse „einzige Stelle, an der ein Publishing-Secret an fremden Code geht" ist **falsch** — `build.yml:149` übergibt `DOCKERHUB_TOKEN` (Registry-Schreibrecht) an `docker/login-action@v3`, ebenfalls mutabler Tag. Zeile 64 ist die schlimmste, nicht die einzige Instanz.

Die tragfähige Unterscheidung ist enger, hält aber: `actions/*` und `docker/*` sind GitHub-eigene bzw. verifizierte Publisher, `Justintime50` ist ein Einzel-Drittanbieter — und es ist die einzige Action, die ein Secret mit Schreibrecht auf einen **Endnutzer-Distributionskanal** erhält.

**Angriffsszenario:**
Ein Angreifer kompromittiert den Upstream-Account `Justintime50` und schiebt `v3` auf einen Commit, der zusätzlich zum normalen Verhalten das `github_token` exfiltriert oder direkt eine manipulierte Formula in den Tap committet. Beim nächsten `v*`-Stable-Tag-Push läuft der Job. Der Angreifer schreibt eine Formula mit beliebigem `install:`-Ruby-Block; jeder Nutzer, der danach `brew install d-migrate` fährt, führt Angreifer-Code lokal aus (Homebrew evaluiert Formula-Ruby).

**Präzedenz — kein Hypothetikum:** Exakt das `tj-actions/changed-files`-Muster (CVE-2025-30066, März 2025), wo ein retro-gepushter Tag Secrets aus tausenden Repos exfiltrierte.

**Empfehlung:**
`Justintime50/homebrew-releaser` per vollem Commit-SHA pinnen (mit Versionskommentar). Anschließend **alle** credential-empfangenden Third-Party-Actions abdecken, mindestens `docker/login-action` in `build.yml:149`. Hinweis: `.github/dependabot.yml` trägt bereits den Kommentar „Third-party actions run with repository credentials, so their updates are security-relevant" — die Risikoklasse ist erkannt, aber Dependabot erkennt **kein Tag-Re-Pointing** (es vergleicht Versionen, nicht Auflösungsziele) und wirkt gegen diesen Vektor erst *nach* SHA-Pinning (dann PRt es SHA-Bumps korrekt).

---

## P3 — Härtung / Backlog

### 7. jwksUrl und introspectionUrl akzeptieren `http://` — Schema wird nicht validiert *(Sammelbefund)*

**Severity:** P3 · **CWE-319** · **Fläche:** mcp-auth

**Fundstellen:** `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt:172` · `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/auth/JwksAuthValidator.kt:45` · `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/auth/IntrospectionAuthValidator.kt:81`

Breiter formulierte Variante von Befund 5 und 12, die zusätzlich das Introspection-Leck adressiert: Im `JWT_INTROSPECTION`-Modus wandert der Bearer-Token bei `http://` im Klartext-Form-Body über die Leitung — passives Mitlesen genügt. Von zwei Prüfern auf P3 herabgestuft, weil (a) der Auslöser eine Operator-Fehlkonfiguration gegen die eigene Doku ist (alle Repo-Beispiele zeigen https), (b) zusätzlich eine On-Path-Position nötig ist, und (c) der Server ohnehin Klartext-HTTP hinter einem TLS-terminierenden Proxy spricht — wer den IdP-Hop kontrolliert, liest auf dem Proxy→d-migrate-Leg bereits Bearer-Token. Ein Prüfer hielt P2 für gerechtfertigt (Impact: untergeschobener Key → `isAdmin`; Fix trivial).

**Empfehlung:** Gemeinsam mit Befund 5 und 12 als *ein* Fix („non-loopback Auth-URL ⇒ https", für `jwksUrl` **und** `introspectionUrl`) erledigen. Der Guard gehört an dieselbe Bedingung wie die bestehende Client-Credentials-Regel.

---

### 8. Kein sicherer SSL-Default: fehlender `sslmode` fällt auf pgjdbc/Connector-J `prefer`/`PREFERRED` zurück

**Severity:** P3 · **CWE-319** · **Fläche:** jdbc-url

**Fundstellen:** `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/SslSettings.kt:21` · `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresJdbcUrlBuilder.kt:46` · `spec/connection-config-spec.md:48`

`SslSettings.mode` ist `null`-default; `sslParams()` emittiert nichts, die Treiber-Defaults greifen: TLS wird versucht, das Serverzertifikat aber **nicht validiert**, und bei Ablehnung fällt die Verbindung **still auf Klartext** zurück. Kein Warn-Code beim Verbinden ohne `sslmode` zu einem Nicht-Loopback-Host.

**Warum P3 und nicht P2 (zwei Prüfer, ein Refute):** `prefer`/`PREFERRED` ist der Standard-Default von libpq/psql/pgjdbc/Connector-J, den jedes JDBC-Werkzeug erbt — keine d-migrate-spezifische Schwächung. Der Code ist an dieser Zeile **spec-konform** (`connection-config-spec.md:48` dokumentiert `prefer` als Default). Die Operator-Mitigation ist real und first-class: `?sslmode=verify-full&sslrootcert=…` parst und wird korrekt emittiert (das ist die [`LN-026`](../../../spec/lastenheft-d-migrate.md)-Lieferung). Erzwingung ist als „nächste Tiefenstufe" im ausgelieferten Admin-Handbuch angekündigt.

**Empfehlung:** Kein Code-Fix ohne vorherige Entscheidung. Nach Projektregel („permanenter Ausschluss → ADR") gehört die Frage in ein **ADR**, das entweder die Treiber-Default-Parität sicherheitstechnisch begründet entscheidet oder die Erzwingungs-Tiefenstufe terminiert und die Spec-Zeile ändert. Ein Warn-Diagnostic bei Nicht-Loopback ohne `sslmode` wäre der billige Zwischenschritt. Ein pauschaler https-Zwang wäre falsch (bricht Loopback-Dev-Setups).

---

### 9. `SslSettingsParser` konsumiert bei case-abweichenden Duplikat-Keys nur EINEN — der zweite überschreibt den validierten Modus

**Severity:** P3 · **CWE-178** · **Fläche:** jdbc-url

**Fundstellen:** `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/connection/SslSettingsParser.kt:83` (`findKey` + `firstOrNull`) · `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionUrlParser.kt:155` (case-sensitive `.toMap()`) · `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/JdbcUrlBuilder.kt:50` (`putAll(config.params)` **nach** `sslParams`)

`parseQuery` baut eine case-**sensitive** Map, `findKey` matcht case-**insensitiv** und konsumiert nur den ersten Treffer. Der zweite, case-abweichende Key überlebt in `remainingParams` → `config.params` und wird **nach** `sslParams` angehängt. Die Single-Source-Zusicherung beider Klassen-KDocs ist damit verletzt, und ein Wert außerhalb des Allowlist-Vokabulars passiert ungeprüft in die JDBC-URL.

**Wichtige Korrektur der Kausalkette (empirisch gegen die gepinnten Treiber getestet):** Der behauptete **MySQL-Angriff funktioniert nicht** — Connector/J 9.6.0 ist case-**sensitiv** (`PropertyKey.fromValue("sslmode")` = `null`), `?sslMode=REQUIRED&sslmode=DISABLED` liefert reihenfolge-unabhängig `REQUIRED`. Der Defekt ist **PostgreSQL**-spezifisch: `PostgresJdbcUrlBuilder.sslParams` emittiert den kanonischen Kleinschreib-Key `sslmode`, `config.params` trägt bei `?sslMode=require&sslmode=disable` denselben Key → **stiller Overwrite**. Empirisch mit pgjdbc 42.7.10: `ConnectionConfig.ssl = REQUIRE`, effektive Wire-Einstellung `disable`.

**Angriffsszenario:** Review-Evasion in einer fremden `.d-migrate.yaml` (Vendor-Template, Zulieferer-Repo): `sslMode=require` springt ins Auge, das angehängte `sslmode=disable` wirkt wie ein harmloses Duplikat. Wirkung nur in Kombination mit einer MITM-Position; wer die Config schreibt, könnte `sslmode=disable` ohnehin direkt setzen — das Delta ist allein die falsche Zusicherung beim Lesen.

**Empfehlung:** `findKey` muss **alle** case-insensitiven Treffer konsumieren, oder `parseQuery` muss case-duplikate Keys ablehnen/normalisieren. Hinweis: Bei exakt gleichnamigen Duplikaten (`?sslMode=A&sslMode=B`) gewinnt bereits `.toMap()` das letzte Vorkommen — dieselbe Review-Evasion ohne Bug; eine vollständige Lösung lehnt Duplikate ab.

---

### 10. `ConnectionSecretMasker`-Allowlist verfehlt MySQL-Connector/J-Keystore-Passwort-Params

**Severity:** P3 · **CWE-532** · **Fläche:** secret-leakage

**Fundstellen:** `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionSecretMasker.kt:26` · `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/connection/SslSettingsParser.kt:88` (stderr-Pfad, ohne Audit) · `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportWiring.kt:83` + `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/audit/CliAuditRecorder.kt:62` (Audit-Pfad)

Die Query-Param-Erkennung ist eine **Exact-Match-Allowlist**, die direkt auf `?`/`&`/`;` folgen muss. `trustCertificateKeyStorePassword` und `clientCertificateKeyStorePassword` matchen keinen Eintrag (der Key *beginnt* nicht mit `password`) → unmaskiert. Empirisch: `?sslpassword=hunter2` → `***`, `?trustCertificateKeyStorePassword=hunter2` → unverändert. Verschärfend: `SslSettingsParser.extractMysql` konsumiert nur `sslMode`/`ssl`, die Keystore-Params bleiben per Design in `config.params` und legitim in der User-URL — der Passthrough ist der *einzige* unterstützte Weg für MySQL-Client-Cert-TLS.

Zwei Ausgabepfade: `SslSettingsParser` baut Fehlermeldungen mit `mask(url)` → stderr (CI-Logs, kein Audit nötig); `CliAuditRecorder` schreibt den rohen `--source`-String nach `SecretScrubber::scrub` in die persistente Audit-JSONL.

**Warum P3 und nicht P2:** Das hochwertige Secret (Authority-Passwort, `password=`/`pwd=`) ist bereits maskiert. Der Beispiel-Key `trustCertificateKeyStorePassword` schützt einen **Truststore** = Container öffentlicher CA-Zertifikate (konventionell `changeit`) — kein Authentifizierungs-Gewinn. Der einzig sensible Key (`clientCertificateKeyStorePassword`) schützt einen Private Key, dessen **Datei** an `clientCertificateKeyStoreUrl` hängt (lokaler Pfad) und nicht im Log steht. Kein angreifer-kontrollierter Trigger.

**Empfehlung:** Zwei-String-Fix — Allowlist erweitern bzw. auf Substring-Matching auf `password` umstellen. Verletzt die explizite Zusage in `LogScrubber` („Passwörter und vollständige Connection-URLs nie unmaskiert geloggt"). [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md) setzt die URL-Abdeckung voraus, statt sie zu begrenzen.

---

### 11. Audit-JSONL wird ohne 0600-Permissions angelegt

**Severity:** P3 · **CWE-276** · **Fläche:** secret-leakage

**Fundstellen:** `adapters/driven/audit-logging/src/main/kotlin/dev/dmigrate/server/adapter/audit/logging/JsonlFileAuditSink.kt:22` · Kontrast: `adapters/driven/connection-config/src/main/kotlin/dev/dmigrate/connection/AesGcmCredentialStore.kt:108`

`Files.writeString(..., CREATE, APPEND)` ohne `PosixFilePermissions`-Attribut → die Datei erbt die umask (0644 bei umask 022); auch `Files.createDirectories` für den Parent ist attributlos (0755). Inhalt sind maskierte Connect-URLs — der Masker ersetzt **nur** das Passwort: `postgresql://svc_migrate:***@db-prod.corp:5432/billing` behält User, Host, Port und DB-Namen. Der Kontrast im selben Repo ist bezeichnend: `AesGcmCredentialStore` setzt explizit `OWNER_RW` mit sauberem Nicht-POSIX-Fallback; im audit-logging-Modul kennt niemand dieses Muster (grep: 0 Treffer).

**Faktenkorrektur:** Default-Pfad ist `.d-migrate/audit.log` **relativ zum CWD** (nicht `~/…`) — ein Projektverzeichnis wird eher gesynct/committet als `$HOME`; das entlastet nicht.

[ADR 0035](../../adr/0035-credential-provider-scheme-registry.md) stellt File-Permissions bewusst zurück — das Deferral betrifft aber ausdrücklich das **Lesen** operator-bereitgestellter `file:`-Mounts („d-migrate *liest* nur"; k8s-tmpfs 0644). Hier erzeugt d-migrate die Datei **selbst**; der Windows-Einwand ist durch das vorhandene `isPosix()`-Muster gelöst. Mildernd: Audit ist opt-in (`enabled` Default `false`).

**Empfehlung:** `isPosix()`-Muster aus `AesGcmCredentialStore` übernehmen. Das Attribut wirkt nur beim Erzeugen — der Fix gehört auf den `CREATE`-Pfad (und auf `.d-migrate/`), nicht auf jeden Append.

---

### 12. `introspectionUrl` ohne https-Zwang: client_secret im Klartext + fälschbare Introspection-Antwort

**Severity:** P3 · **CWE-319** · **Fläche:** crypto

**Fundstellen:** `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt:104` · `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/auth/IntrospectionAuthValidator.kt:64`

`introspectionAuthErrors()` erzwingt für Nicht-Loopback-Binds korrekt die Client-Credentials (RFC 6749), prüft aber nie das Schema. Bei `http://` geht das Client-Secret base64-kodiert über die Leitung, und die Antwort, die über die Gültigkeit **jedes** Bearer-Tokens entscheidet, ist unauthentifiziert — der Validator liest `active`/`sub`/`iss`/`aud`/`exp`/`scope` ohne Signaturprüfung aus dem Response-Body. Ein On-Path-Angreifer antwortet mit `200 {"active":true,"scope":"dmigrate:admin",…}` und ist voll autorisierter Principal.

Auf P3 herabgestuft (zwei Prüfer): Operator-Fehlkonfiguration gegen die eigene Doku (`api-referenz.md`, `administrationshandbuch.md` fordern beide „nur mit https"), der Server terminiert ohnehin kein TLS selbst, und Introspection gegen einen Loopback-/Sidecar-Endpunkt (Istio/Linkerd-mTLS-Egress) ist legitim.

**Empfehlung:** Zusammen mit Befund 5/7 als *ein* Guard („non-loopback Auth-Host ⇒ https", an dieselbe Bedingung wie die Client-Credentials-Regel). Ein Prüfer merkt zu Recht an: Der Fix sollte bei `jwksUrl` beginnen — Klartext-JWKS ist gravierender, weil der Angreifer Tokens direkt fälscht, ohne pro Request MITM sein zu müssen.

---

### 13. `DELETE /mcp` terminiert Sessions ohne jede Authentifizierung

**Severity:** P3 · **CWE-306** · **Fläche:** mcp-auth

**Fundstellen:** `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/McpHttpRoute.kt:96`

Die Route bekommt nur den `SessionManager` übergeben — `config`/`authValidator` sind strukturell unerreichbar. Weder `validateBearer` noch `checkOrigin` noch `checkScopes` laufen, im Gegensatz zu `handleMcpPost`. Die Session-Id ist damit das einzige Merkmal für eine zustandsändernde Operation.

**Wirkung eng begrenzt (ein Refute, zwei Bestätigungen):** Die Session-Id ist **kein** Autorisierungsträger — `SessionState` dokumentiert `principalContext` explizit als „audit/last-validation snapshot", jeder Folge-Request re-validiert seinen eigenen Header. `SessionManager.remove` ist ein reines `sessions.remove(id)`: kein Cancel, kein Cleanup; laufende Jobs liegen in der route-weit geteilten Registry und laufen weiter. Verloren gehen nur `negotiatedProtocolVersion` + `lastSeen`; der Client re-initialisiert mit gültigem Bearer = ein Round-Trip, self-healing. Die UUID (122 Bit) ist nicht enumerierbar (unbekannte und fehlende Id liefern beide 405), und Browser-CSRF scheitert am CORS-Preflight für den `MCP-Session-Id`-Header (kein CORS-Plugin installiert). Bei `--auth-mode disabled` ist der Bind hart Loopback.

**Empfehlung:** `validateBearer` auf der DELETE-Route ergänzen (billige Defense-in-Depth, stellt die vom eigenen Route-KDoc behauptete Validierungsreihenfolge her). `spec/mcp-server.md` fordert „Principal wird pro Request validiert" ohne Verb-Ausnahme.

---

### 14. Session ist an keinen Principal gebunden; `currentPrincipal` ist geteilter mutabler Zustand

**Severity:** P3 · **CWE-488** · **Fläche:** mcp-auth

**Fundstellen:** `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/McpHttpRoute.kt:283` · `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/protocol/McpServiceImpl.kt:114` · `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/SessionState.kt:28`

`resolveContext` schlägt die Session allein über den Header nach und vergleicht den Request-Principal nie mit dem der Session. Der pro Session **geteilte** `McpServiceImpl` trägt den Principal in einer `AtomicReference`, die `dispatchAndRespond` bei jedem Request neu setzt; die Handler lesen ihn per `currentPrincipal.get()`. Route-Gate (`checkScopes`) benutzt die lokale Request-Variable, die Handler den geteilten Zustand — bei Nebenläufigkeit auf derselben Session können beide auseinanderlaufen.

**Faktenkorrektur (alle drei Prüfer):** Die Behauptung „SessionState hält kein Principal-Feld" ist **falsch** — `SessionState.kt:28` hat `val principalContext: PrincipalContext`, laut KDoc bewusst nur als Audit-Snapshot. Der Träger für eine Bindung ist also bereits da; nur der Vergleich fehlt. Das macht den Fix billig.

**Angriffsszenario:** Zwei Principals derselben Audience teilen eine Session-Id (B kennt die UUID des Admins A). B sendet `tools/call job_list` genau während A auf derselben Session arbeitet. Bs Gate prüft gegen Bs Scopes (read genügt); zwischen `checkScopes` und dem Handler-Zugriff setzt As paralleler Request `currentPrincipal` auf A. Bs Handler liest A und führt den Lookup mit As `effectiveTenantId` aus → Enumeration fremder Tenant-Jobs, obwohl `ClaimsMapper` `allowedTenantIds = setOf(tenantId)` bewusst dagegen absichert. Ohne Race ist der Session-Diebstahl folgenlos (B läuft als B).

**Empfehlung:** Principal per Dispatch durchreichen (`ToolCallContext`) statt per-Session-mutabler `AtomicReference`. Ergänzend: Gleichheitsprüfung Request-Principal ↔ `state.principalContext` an der `peek`-Stelle. Das Modell „Session = Protokollzustand, Bearer = Authz" ist im Kern richtig; nur der Träger ist falsch gewählt.

---

### 15. Unverifiziertes `curl | bash` von deb.nodesource.com in der integration-test-Stage

**Severity:** P3 · **CWE-494** · **Fläche:** runtime-packaging

**Fundstellen:** `Dockerfile:248` · `.github/workflows/integration.yml` · `scripts/test-integration-docker.sh` (mountet `/var/run/docker.sock`, `--network=host`)

`curl -fsSL https://deb.nodesource.com/setup_20.x | bash -` ohne Pin, SHA256 oder Signatur. `setup_20.x` ist ein serverseitig veränderbarer Endpunkt. Die Stage läuft bei jedem push/PR auf develop/main. Der Kontrast: `scripts/fetch-semgrep-rules.sh` holt zwei YAML-**Textdateien** commit-gepinnt und SHA256-verifiziert fail-closed, während hier **ausführbarer Code** völlig ungeprüft als root läuft. Das Gegenargument „das Skript trägt nur ein GPG-verifiziertes apt-Repo ein" scheitert an der Reihenfolge: Das Skript *installiert* den Key erst; die RCE läuft, bevor eine Signaturprüfung existiert.

**Auf P3 (ein Refute, zwei Bestätigungen):** Der CI-Schenkel ist gedeckelt (`permissions: contents: read`, keine Secrets). Der severity-treibende Pfad ist die Maintainer-Maschine (Socket + `--network=host` → Host-Root-Ausbruch inkl. Release-/Tap-Credentials). Zu Recht angemerkt: Es ist **kein isolierter Aussetzer** — dieselbe Stage hängt an `pip install django` (ohne Hash), `npm install -g pnpm node-gyp` (ohne Integrity), Basis-Image `gradle:8.12-jdk21` als mutabler Tag und Gradle ohne `verification-metadata.xml`. Ein Fix nur an Zeile 248 reduziert die Angriffsfläche der Stage kaum.

**Empfehlung:** Als *ein* Ticket „Build-Supply-Chain durchgängig pinnen" führen: nodesource-Repo + Key statisch eintragen (statt Pipe-to-Shell), pip/npm pinnen, Basis-Images per Digest, ggf. Gradle-Dependency-Verification. Nicht als Einzelzeilen-Fix.

---

### 16. yq/jq werden als ausführbare Binaries ohne SHA256-Verifikation per `ADD` geholt

**Severity:** P3 · **CWE-494** · **Fläche:** runtime-packaging

**Fundstellen:** `Dockerfile:271-275` · `.github/workflows/coverage-modules.yml`

Gepinnt ist nur ein Release-**Tag** (`v4.44.6`, `jq-1.8.1`) — GitHub-Release-Assets sind unter einem bestehenden Tag austauschbar, Tags verschiebbar. `chmod +x` ohne jede Integritätsprüfung; die Stage läuft bei jedem push/PR. Wirkung begrenzt: `contents: read`, keine Secrets, kein Fluss ins publizierte Runtime-Image (`runtime` ist `FROM eclipse-temurin:21-jre-noble` ohne `COPY --from=coverage-*`).

**Präzedenz im Repo:** [ADR 0017](../../adr/0017-tpc-benchmark-workload-sourcing.md) pinnt ein heruntergeladenes ausführbares Artefakt (`tpch.duckdb_extension`) per SHA256 — reiner Tag-Pin ist hier der Ausreißer.

**Empfehlung:** Ein Flag genügt — BuildKit kennt `ADD --checksum=sha256:… <url> <dest>`. Gemeinsam mit Befund 15 als Supply-Chain-Ticket.

---

### 17. Test-Framework landet über testFixtures im produktiven Distributions-Artefakt

**Severity:** P3 · **CWE-1104** · **Fläche:** supply-chain

**Fundstellen:** `adapters/driving/cli/build.gradle.kts:81` (`implementation(testFixtures(project(":hexagon:ports-common")))`) · `hexagon/ports-common/build.gradle.kts:10` (`testFixturesApi("io.kotest:…")`)

Die CLI zieht die testFixtures-Variante in eine **MAIN**-Konfiguration; da ports-common `testFixturesApi` (transitiv) nutzt, wandert der komplette Test-Stack auf den produktiven `runtimeClasspath`. **Empirisch am gebauten Artefakt verifiziert** (nicht nur inferiert): `d-migrate-…-all.jar` enthält 2300 `io/kotest/**`-Klassen + `META-INF/services/org.junit.platform.engine.TestEngine`; die distZip liefert `kotest-framework-engine`, `kotest-assertions-core`, `junit-platform-launcher-1.13.4`, `junit-jupiter-api-5.13.4`, `kotlinx-coroutines-debug-1.10.2`, `byte-buddy-1.10.9` und **`byte-buddy-agent-1.10.9`** (Stand 2020, von keinem Update-Pfad erfasst). Zusätzlich wird der eigene Contract-Test-Code (~19 Dateien) mit ausgeliefert. Es ist die einzige `implementation(testFixtures(...))`-Stelle im Repo. Kein Shadow-`exclude`/`minimize` filtert.

**Zwei Korrekturen:** (a) **JNA ist fehlattribuiert** — `jna-5.14.0` kommt über `mordant-jvm-jna` (clikt-Terminal-Lib, legitime Produktiv-Dependency) und würde ohnehin ausgeliefert; das JNI-Policy-Inkonsistenz-Argument gegen `formats-parquet` ist zu streichen. `byte-buddy-agent` ist korrekt attribuiert. (b) Kein ausnutzbarer Pfad: Die Klassen sind inert, kein `-javaagent`, kein `ByteBuddyAgent.install()`, kein `Native.load()` im Produktivcode.

**Empfehlung:** Die produktiv benötigten `InMemoryJobStore`/`InMemoryQuotaStore`/`InMemoryIdempotencyStore` (selbst kotest-frei) in ein **Produktivmodul** heben — nicht per Shadow-Exclusion kaschieren (das ließe den Classpath falsch). Wert liegt in SBOM-/CVE-Triage-Genauigkeit: eine RCE in kotest/junit/byte-buddy würde heute als „Test-only, nicht ausgeliefert" fehlklassifiziert.

---

## Geprüft und in Ordnung

Diese Bereiche wurden mit Code-Lektüre (nicht durch Vermutung) geprüft, ohne dass sich ein Befund bestätigte. Die Liste ist bewusst detailliert, damit Folge-Audits nicht dieselbe Arbeit wiederholen.

**Credential-Store / Krypto (vorbildlich)**
- `CredentialCrypto`: AES-256-GCM (128-bit Tag), PBKDF2-HMAC-SHA256 mit 600.000 Iterationen, Salt (16 B) **und** Nonce (12 B) pro `encrypt` frisch aus `SecureRandom` → kein (key, nonce)-Reuse möglich. Header (magic|version|iterations|salt|nonce) als GCM-**AAD** → Parameter-Tampering wird erkannt; ein Downgrade auf `iterations=1` ist wirkungslos, weil der abgeleitete Key das Tag nicht erfüllt. Kein ECB, kein statischer IV, kein hartkodiertes Salt/Key.
- Key-Wiping konsequent im `finally` (`deriveKey`, `readEntries`/`writeEntries`, `CredentialEntry.wipe()`, Shutdown-Hook auf `CredentialFillSession`). Passwörter als `ByteArray`/`CharArray`, nicht `String`.
- `writeAtomically`: Temp-Datei im **selben** Verzeichnis mit `OWNER_RW` **beim Anlegen** (kein chmod-Race), `ATOMIC_MOVE` → Ziel erbt 0600; Dir 0700; Fallback + Cleanup vorhanden.
- Exception-Messages durchgehend secret-frei; der [ADR 0035](../../adr/0035-credential-provider-scheme-registry.md)-Fix im `ConnectionUrlParser` (`e.reason`+`e.index` statt roher URL) ist im Code verifiziert.
- `StoredCredential` bewusst keine data class; `ConnectionConfig`/`S3StorageConfig` mit handgeschriebenem maskierendem `toString()`.
- Timing: einziger Secret-Vergleich ist `contentEquals` zweier **eigener** Operator-Eingaben — kein Orakel. GCM-Tag-Verifikation ist JCE-seitig constant-time.
- Repo-weit: kein MD5, kein SHA-1, kein ECB, kein CBC; kein `java.util.Random`/`Math.random` im Produktivcode. `McpCursorCodec` nutzt HMAC-SHA256 mit `MessageDigest.isEqual` (timing-safe) und leitet Bindings aus dem Request-Principal neu ab.
- `McpRuntimeWiring.DEV_DEFAULT` (hartkodierter Dev-HMAC-Key) ist per `rejectDevKeyringInProductionOrExit` fail-closed gesperrt (http + auth != disabled → Exit 2).

**MCP-Auth (JWT-Kette)**
- `alg=none` doppelt abgedeckt (Config verwirft case-insensitiv; Nimbus weist `PlainJWT` bei gesetztem `jwsKeySelector` bauartbedingt ab). RS256→HS256-Confusion ausgeschlossen (`HS*` verworfen **plus** `JWSVerificationKeySelector` selektiert nur passende Key-Familien).
- `exp`/`aud`/`iss`/`sub` als `requiredClaims`; `iat` explizit nachgeprüft (Nimbus trackt es nicht); `clockSkew` auf max. 5 min gedeckelt. Introspection-Pfad prüft alle fünf manuell.
- Leerer Scope ≠ alles erlaubt: `ScopeChecker.requiredScopes` fällt fail-closed auf `dmigrate:admin`. `isAdmin` stammt aus `ClaimsMapper` und ist per Konstruktion `dmigrate:admin in scopes` — kein zusätzliches Privileg; ein `is_admin`-Claim wird bewusst nicht gelesen.
- `authMode` Default = `JWT_JWKS`; `DISABLED` ist hart auf Loopback ohne `publicBaseUrl` eingesperrt, Start bricht sonst mit `ConfigError` ab. Kein Wildcard-Origin erlaubt. `BearerTokenReader` RFC-6750-strikt; Query-Parameter-Tokens werden aktiv mit 401 abgelehnt.
- JWKS-Fetch nutzt Nimbus-Cache + Rate-Limiter (kein Per-Request-Fetch); der einzige verbleibende Vektor ist der Klartext-Transport (Befund 5/7).

**SQL-Pfad**
- Identifier-Quoting in `SqlIdentifiers` ist für alle drei Dialekte **korrekt** (PG/SQLite `"`→`""`, MySQL `` ` ``→```` `` ````), alle Dialekt-Wrapper delegieren sauber, keine Drift-Neuimplementierung. Systematischer grep über alle SQL-Templates: **jeder** Tabellen-/Spalten-/Schema-Interpolationspunkt läuft über `quote()`/`quotedPath()`/`qt()`/`qi()`/`ProfilingSqlNames` — kein ungequoteter Identifier-Pfad gefunden.
- SQLite-PRAGMA-Pfade (nicht parametrisierbar) nutzen durchgängig `quoteStringLiteral`; SpatiaLite-`sqlString()` ist für SQLite korrekt (SQLite kennt keinen Backslash-Escape). Datenpfad (INSERT/Import) durchgängig PreparedStatement. `MysqlSchemaIntrospectionAdapter.schemaFilter` ist parametrisiert.

**JDBC-URL**
- Param-Injection über **Werte** unmöglich: `URLEncoder.encode` deckt `&`, `?`, `;`, `=`, `#` in Keys und Werten ab, ohne Pfad-Params zu brechen.
- Host-Injection unmöglich (`java.net.URI` validiert; `null` → `IllegalArgumentException`). SQLite-Pfad-Injection unmöglich (Split am **ersten** `?`).
- `allowLoadLocalInfile`/`allowUrlInLocalInfile`/`autoDeserialize` werden nicht gesetzt; Connector/J 9.6.0-Defaults sind alle `false` → ein Rogue-MySQL-Server kann LOCAL INFILE nicht erzwingen. `allowPublicKeyRetrieval` wird ausdrücklich nicht implizit aktiviert.
- `sslmode`-Downgrade über eine `?`-Injektion im `database`-Feld gezielt geprüft und **verworfen**: injizierter Inhalt landet zwangsläufig vor den angehängten Params; beide Treiber lassen das spätere Vorkommen gewinnen.
- Passwörter gehen als Hikari-Property, **nicht** in die jdbcUrl.

**Path-Traversal / Artefakt-Store**
- `PathSafety`: Allowlist `Regex("[A-Za-z0-9_-]{1,128}")` als **Vollmatch** (`matches()`, nicht `find()`) — Punkt/Slash/Backslash/NUL/Unicode ausgeschlossen, Traversal konstruktiv unmöglich. Alle 9 ID-annehmenden Einstiegspunkte rufen `requireSafeId` als erste Anweisung.
- `BundleExtractor` (untrusted ZIP via MCP): doppelte Verteidigung (String-Hygiene + `resolve().normalize()` + `startsWith` auf absoluten Pfaden), Symlink-Escape ausgeschlossen (ZipInputStream ignoriert Unix-Modus), Zip-Bomb-Caps (Entry-Count, Einzel-/Gesamt-Bytes). *Hinweis für Nachprüfer:* Zeile 226 `path.contains('␀')` sieht wie ein Leerzeichen aus, ist per `hexdump` aber ein echtes NUL-Literal (`27 00 27`) — korrekt.
- `RangeRead`: `RangeBounds.check` vor jedem Zugriff, Channel-Close im catch (kein FD-Leak). `Sidecar`/`StreamingHashWriter`: durchgängig `CREATE_NEW` (kein Symlink-Follow auf existierende Ziele), atomarer Move, strenge Hash-Regex.
- Temp-Dateien repo-weit: `createTempFile`/`createTempDirectory` → POSIX-Default 0600/0700, sichere Zufallsnamen, kein TOCTOU.

**MCP-Surface**
- `SchemaSourceResolver` lädt keine beliebigen Pfade/URLs (nur inline `schema` mit Cap oder `dmigrate://`-Store-URI) → kein SSRF. Tenant-Scope wird **vor** dem Store-Lookup geprüft (kein Oracle) + Defense-in-Depth `entryMatches`.
- `ArtifactUploadHandler`: Segment-Größe, -Offset, -Sequenz, -Hash und Session-Owner alle geprüft; overflow-sichere Long-Arithmetik. `JobInputFinalizer`: `artifactId` deterministisch abgeleitet, Replay-Drift → Fehler statt stiller Überschreibung.

**S3-Storage**
- Keine hardcoded AWS-Credentials, keine YAML-Credential-Felder; Keys nur aus `DefaultCredentialsProviderChain`. `S3StorageConfig.toString` redigiert accessKey/secretKey. **Keine presigned URLs im gesamten Repo** — die Angriffsklasse inkl. TTL existiert nicht. `requireSafeId` vor jedem `keyFor()`/`segmentKey()`; Bucket/keyPrefix nur aus lokaler YAML. `abortMultipartUpload` im `finally` auf jedem Fehlerpfad.

**Deserialisierung**
- **YAML:** alle Produktiv-Load-Sites (14 Stück) enumeriert — ausnahmslos `Load(LoadSettings...)` mit Default-StandardConstructor, nirgends ein Custom-/Tag-Constructor → snakeyaml-engine erzeugt nur Map/List/String/Number/Boolean. `setAllowRecursiveKeys` überall Default `false`. `ParquetManifestReader` härtet zusätzlich (`allowDuplicateKeys=false`, `maxAliasesForCollections=0`). Kein RCE-Pfad.
- **JSON:** kein einziges `activateDefaultTyping`/`enableDefaultTyping` im Repo (Produktiv **und** Test). Alle Mapper binden konkrete Zieltypen.
- **XML/XXE:** keine Surface (`DocumentBuilderFactory`/`SAXParser`/`XMLInputFactory` = 0 Treffer). **Archive/Zip-Slip** außerhalb `BundleExtractor`: keine Surface.
- `ParquetBundlePreflight` prüft `bundleRoot.resolve(table.file).normalize()` gegen `startsWith` über **alle** `manifest.tables` (vor Filter) → `MANIFEST_FILE_OUTSIDE_BUNDLE`.

**Supply-Chain (CVE-Abgleich)**
- Alle benannten Risiko-Dependencies verifiziert **außerhalb** der betroffenen Spanne: sqlite-jdbc 3.51.3.0 (CVE-2023-32697 ≤ 3.41.2.2), postgresql 42.7.10, logback 1.5.15, nimbus-jose-jwt 10.9, hadoop 3.4.1. jackson-databind löst auf 2.21.2 nach oben auf.
- **Avro/Parquet-RCE (CVE-2025-30065, CVE-2024-47561) nicht erreichbar** — `formats-parquet` nutzt `rejectAll()`-Constraints **plus** `exclude(group = "org.apache.avro")`; am aufgelösten `runtimeClasspath` verifiziert: 0 Treffer für avro/netty-nio/apache-client/snappy/zstd. Zweigleisige Absicherung, vorbildlich.
- Repositories: ausschließlich `mavenCentral()`, keine HTTP-Repos, kein Custom-Repo, kein `pluginManagement`-Override → Dependency-Confusion strukturell ausgeschlossen.

**Runtime-Packaging**
- Runtime non-root bestätigt (`useradd --uid 10001`, `USER` **vor** `ENTRYPOINT`, `/work` korrekt geownt). Keine Script-Injection über `github.event.*` (0 Treffer), kein `pull_request_target`, kein `workflow_run`; Fork-PRs können den Release-Pfad nicht auslösen.
- **Homebrew-Checksum-Hypothese falsifiziert:** `skip_checksum: true` unterdrückt nur den checksum.txt-Upload — die publizierte Formula trägt eine echte `sha256`-Zeile (live abgerufen).
- `fetch-semgrep-rules.sh`: Commit-Pin **in der URL**, `sha256sum -c --status`, Download nach `mktemp` und Verifikation **vor** dem `mv`, fail-closed. Sauber.
- Keine Secrets in Build-Args/Layern; `.dockerignore` schließt `.git`/`.env*`/`.d-migrate/` aus; `runtime` kopiert nur das Install-Verzeichnis.

---

## Verworfene Verdachtsfälle

Diese neun Meldungen wurden geprüft und als **False Positives** eingestuft. Sie sind hier mit Kurzbegründung dokumentiert, damit sie nicht erneut auditiert werden.

| Verdacht | Warum kein Problem |
|---|---|
| **`config credentials set` speichert bei Ctrl-D still ein leeres Passwort** | Kein Angreifer: nur der Operator selbst kann EOF auf seinem TTY auslösen. Nicht EOF-spezifisch — blankes Enter liefert ebenfalls `CharArray(0)`; der `?:`-Fix ändert am Endzustand nichts. Gegen `trust`/`peer`-Server ist der Zugriff identisch mit oder ohne Eintrag; gegen SCRAM/md5 scheitert es laut und sofort = selbstkorrigierend. Die Asymmetrie zu `promptMasterSecret()` ist eine Fehllesung (dort signalisiert `null` *Quelle nicht verfügbar*, nicht *Leerwert*). **Rest: UX-/Robustheits-Nit** (fail-closed Exit 2 wäre schöner), kein Security-Befund. |
| **`sslrootcert` ohne `sslmode` ist stiller No-Op** | Mechanik stimmt, aber es ist der dokumentierte 1:1-Passthrough-Vertrag (`SslSettings`-KDoc: „d-migrate setzt keine eigenen Defaults"). Identisches Verhalten wie psql/libpq — kein d-migrate-eigener Downgrade. Die geforderte Invariante würde `sslmode=require&sslrootcert=…` (verbreitet, funktionierend) hart brechen. Der MITM-Angriff gelingt ohnehin billiger gegen eine Config, die die Prüfung anstandslos durchließe. **Rest: W-Code-Warning wäre eine sinnvolle P3-Ergonomie-Ergänzung.** |
| **Encoding-Asymmetrie: `config.params` encoded, `database`/`host` roh** | `ConnectionConfig(...)` wird im Produktivcode an **genau zwei** Stellen konstruiert, beide im `ConnectionUrlParser`. Kein Aufrufer befüllt `database`/`host` aus getrennten Quellen; das Config-Format speichert ganze URL-Strings. Wer `database` kontrolliert, kontrolliert die ganze URL und kann Params direkt schreiben → kein Privilegien-Gewinn. Der Befund räumt selbst ein: „heute keine Grenzüberschreitung". **Rest: KDoc-Hinweis auf die Ein-Autor-Invariante.** |
| **`McpServerConfig` ohne maskierendes `toString()`** | Kein Pfad verwandelt die Instanz in Text: 0 Treffer für `$config`-Interpolation, kein `toString`-Aufruf, `McpServeRunner` hat **null** Logger-Aufrufe. `--introspection-client-secret` hat kein `envvar` — das Secret steht ohnehin in argv (`/proc/<pid>/cmdline`, `ps`) und der Shell-History; wer stderr liest, kann auch `ps`. Kein Privilegien-Gewinn. **Rest: 3-Zeilen-Hygiene-Commit, gern mitnehmen.** |
| **`artifacts.s3.endpoint` ohne https-Zwang** | Wert stammt ausschließlich aus der operator-eigenen Startup-YAML (Operator ≠ Angreifer). Die Analogie zu `publicBaseUrl` trägt nicht: das ist eine RFC-9728-**Advertisement**-Grenze für fremde Clients, hier ist d-migrate **Client**. `spec/ki-mcp.md` nennt MinIO/SeaweedFS/Ceph first-class — der geforderte Loopback-Carve-Out **bricht** das Zielbild (in-cluster `http://minio.ns.svc:9000`) **und** die eigene Harness (`SeaweedTestSupport` baut `http://$host:…` mit Testcontainers-Host). SigV4 überträgt kein Secret. **Rest: Doku-Satz im Admin-Handbuch.** |
| **Runtime-Base-Image nur Tag-gepinnt** | **Faktisch falsch verortet:** das publizierte GHCR-Image stammt von **Jib** (`cli/build.gradle.kts:152`), nicht aus `Dockerfile:393`. `--target runtime` baut nur `d-migrate:dev` für lokale Smokes und erreicht nie eine Registry — der `apt-get spatialite`-Vorwurf ist damit gegenstandslos (Jib-Images haben keinen apt-Layer). Zusätzlich: Digest-Pinning ist TOFU und würde die Tag-Re-Pushes verwerfen, die der **Auslieferungsweg für OS-CVE-Fixes** sind. **Rest: Jib-Base ist tatsächlich ein Tag — Reproduzierbarkeits-Ticket an `build.gradle.kts:154`.** |
| **Keine Gradle-Dependency-Verification** | Der Angriff („bösartige Version unter bereits gepinnter Koordinate") ist auf Maven Central **technisch unmöglich** — freigegebene GAVs sind unveränderlich, ein übernommener Account kann nur *neue* Versionen publizieren, die der Build mangels Ranges nie zieht. `verification-metadata.xml` ist TOFU (Hashes aus derselben Quelle) und adressiert das Restrisiko nicht. Die Semgrep-Präzedenz dreht sich um: dort wird gepinnt, weil der Kanal *mutabel* ist. **Übersehen:** `.github/dependabot.yml` (gradle+actions+docker) und SECURITY.md existieren — sind aber **untracked**; Fix ist ein Commit, keine Neukonzeption. |
| **Gradle-Build-Image Tag- statt Digest-gepinnt** | Die behauptete Policy existiert nicht (3. Tool-Image `DCHECK_IMAGE` ist ebenfalls Tag-gepinnt; alle GitHub-Actions ebenso). Die Digest-Pins begründen sich per Kommentar mit **Hermetik/Reproduzierbarkeit**, nicht Trust. Dependabot deckt Docker-Basis-Images bewusst ab. Ein Digest-Pin auf `eclipse-temurin:21-jre-noble` würde garantiert veraltende, real ausnutzbare OS-Pakete gegen ein hypothetisches Docker-Hub-Kompromiss-Szenario eintauschen — **netto negativ**. |
| **Gradle-Wrapper ohne `distributionSha256Sum`** | Der Pfad ist **vollständig tot**: der Dockerfile ruft an 12 Stellen `RUN gradle --no-daemon` gegen `FROM gradle:8.12-jdk21` — der Wrapper wird nie ausgeführt, `gradlew:22-26` sperrt ihn zusätzlich (Exit 2 ohne `DMIGRATE_ALLOW_LOCAL_GRADLE=1`, die keine Zeile im Repo setzt). Die Remediation ist scope-blind: sie härtet den ungegangenen Pfad und lässt den real gegangenen (Base-Image per Tag) unverifiziert. `wrapper-validation` schützt ein Binary, das kein CI-Job ausführt, gegen einen Angreifer mit Repo-Write, der es nicht bräuchte. *(Nebenbefund: `gradlew.bat` trägt keinen Guard — Asymmetrie, für ein Linux-Zielrepo heute irrelevant.)* |

---

## Nicht geprüft / offene Lücken

Diese Bereiche wurden von keiner der 12 Flächen abgedeckt und sind Kandidaten für ein Folge-Audit.

**Hohe Priorität**

1. **`adapters/driven/integrations/` — Nicht-SQL-Escaping-Matrix der Tool-Exporter (Flyway/Liquibase/Django/Knex).** Die einzige Stelle im Repo, die Schema-Inhalte in **XML, Python und JavaScript** rendert — eine völlig andere Escaping-Matrix als die geprüfte SQL-Seite. Konkrete Beobachtungen: `LiquibaseMigrationExporter` interpoliert `changeSetId` ungeescaped in ein XML-Attribut, und `RenderHelpers.escapeXml` escapet **nur** `&<>` — keine Quotes, kein `]]>`. `MigrationVersionValidator.validate` gibt für LIQUIBASE für **jeden** nicht-leeren String `true` zurück. `escapePython`/`escapeJavaScript` sind handgeschrieben (Backslash + Delimiter) — exakt die Klasse, in der dieses Audit bereits zwei bestätigte Backslash-Befunde (P1/P2) gefunden hat. Die Exploitierbarkeit hängt an der Provenienz von `version` (CLI-Flag vs. `schema.version` aus möglicherweise fremdbezogener Schema-YAML) — genau diese Herkunftsfrage muss ein Auditor entscheiden.

2. **Approval-Grant-Kette (Replay & Lebenszyklus).** Die **zweite Autorisierungsschranke** des Produkts — der Human-in-the-Loop-Gate vor destruktiven DB-Operationen durch einen KI-Agenten — ist komplett ungeprüft, während die erste (JWT) doppelt geprüft wurde. Beobachtung: `ApprovalGrantValidator.validate` bindet sauber (expiry/tenant/caller/tool/payloadFingerprint/scopes), aber der Port `ApprovalGrantStore` kennt nur `put`/`find`/`deleteExpired` — **keine Consumption-/Nonce-/markUsed-Semantik**. Ein erteilter Grant wäre innerhalb seiner TTL beliebig oft einlösbar. Ob der Idempotency-Store das abfängt oder es eine echte Replay-Lücke ist, klärt nur eine gezielte Prüfung.

3. **`adapters/driven/persistence-jdbc/` (~1445 LOC) — Mandantentrennung und Nebenläufigkeit.** Enthält den gesamten persistenten Zustand des Mehrmandanten-MCP-Servers: Quota-Store (die einzige DoS-Bremse gegen einen authentifizierten Mandanten), Idempotency-Store (464 LOC, Replay-Schutz) und Job-Store. Der Reserve-Pfad ist als `INSERT … ON CONFLICT DO UPDATE WHERE limit-check` atomar **gedacht** — niemand hat verifiziert, ob der Limit-Check race-frei ist, ob der Release-Pfad einem Mandanten erlaubt, fremde Reservierungen freizugeben, oder ob die Owner-Zuordnung die Tenant-Grenze trägt.

4. **MCP-Job-Ausführungspfad (`DataRunnerWorkers`, `McpCoreJobWorkerFactory`, `JobDispatcher`, `JobStartService`, `AiToolOrchestrator`).** Exakt die Naht, an der ein validierter MCP-Request in eine reale DB-Schreiboperation übersetzt wird. Geprüft wurden Auth und Request-Parsing — nicht, was der Worker danach damit macht. Ungeklärt: Läuft der Worker mit dem Principal des Aufrufers oder mit Server-Rechten? Werden Quota und Approval **vor** oder **nach** dem Dispatch ausgewertet?

**Mittlere Priorität**

5. **Nebenläufigkeit im parallelen Datenpfad (`--parallel N`) und Pool-Erschöpfung.** Alle 12 Flächen waren rein statisch-strukturell; Races/TOCTOU wurden nirgends adressiert. Das Projekt hat eine dokumentierte, bereits produktiv aufgetretene Deadlock-Klasse (verschachteltes Borrow bei SQLite mit `maximumPoolSize = 1`), und `--parallel N` erhöht genau diesen Druck. Zu prüfen: Ist N gegen `maximumPoolSize` gedeckelt (sonst Selbst-DoS bis `connectionTimeout`)? Hält die FK-Barriere unter Teilabbrüchen (halb geladene Zieltabellen bei provoziertem Chunk-Fehler)? Zählt der `chunkFailures`-Deckel race-frei? Für ein Werkzeug, dessen Kernversprechen die Integrität der Zieldatenbank ist, ist ein nebenläufigkeitsbedingter Teil-Load die schwerwiegendste Fehlerklasse.

6. **Ausgabe-Kodierung der Daten-Writer (`ValueSerializer`, CSV-/JSON-Writer) — CSV-Formel-Injection.** Die deserialization-Fläche prüfte ausdrücklich nur die **Lese**seite. d-migrate exportiert per Definition Inhalte aus einer fremden Quell-DB in Dateien, die danach in Excel/LibreOffice geöffnet werden: ein Zellwert `=cmd|'/c calc'!A1` bzw. `@`/`+`/`-`-Präfix ist Formel-Injection und wird von keiner CSV-Bibliothek per Default neutralisiert — korrektes RFC-4180-Quoting verhindert sie **nicht**. Dieselbe Vertrauensgrenze, die dieses Audit bereits zweimal als real bestätigt hat, nur auf der Ausgabeseite. Ebenfalls in diesem Modul ungeprüft: `SchemaFileResolver`.

**Methodische Einschränkungen dieses Audits**

- Der P1-Path-Traversal wurde **nicht** end-to-end gegen ein laufendes Binary demonstriert; verifiziert sind der Datenfluss (Code-Lektüre aller Zwischenstationen) und beide Schlüsselannahmen einzeln empirisch. Ein CLI-Repro gegen eine präparierte `.sqlite` ist der naheliegende nächste Schritt.
- Der Gradle-Wrapper-JAR-Hash konnte mangels Netzwerk nicht gegen die offizielle Referenz verifiziert werden.
- Gson-Rekursionstiefe im Pre-Auth-`parseMessage`: tief verschachteltes JSON könnte einen `StackOverflowError` auslösen, den `catch (e: Exception)` in `parseBody` **nicht** fängt (`Error`, keine `Exception`). Ohne Build nicht verifizierbar, daher bewusst nicht als Befund geführt — liegt auf derselben Naht wie Befund 4 und würde von einem Inbound-Cap teilweise mit-entschärft.
- Ktor-3.0.3-CVEs wurden nicht behauptet, weil die betroffenen Spannen nicht sicher belegbar waren.
- Repo-Security-Einstellungen (Dependabot-Alerts, Default-`GITHUB_TOKEN`-Scope) sind aus dem Repo nicht einsehbar. Acht Workflows ohne expliziten `permissions:`-Block erben den Repo-Default; sie triggern nur auf `push`/`schedule`/`dispatch` (keine Fork-Exposition) und nutzen keine Secrets — als Härtungsempfehlung vermerkt, nicht als Befund.