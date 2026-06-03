# Plan: Trino-Support in d-migrate (Read-first Federation-Adapter)

> Dokumenttyp: Architektur- und Umsetzungsplan  
> Status: Entwurf (2026-05-15, Review-Update 2026-06-03)  
> Roadmap-Slot: Kandidat für 1.x (nicht Teil 0.9.x-Milestones)  
> Referenzen: `spec/architecture.md`, `spec/cli-spec.md`, `spec/connection-config-spec.md`, `docs/planning/in-progress/roadmap.md`

## Kurzfassung

`d-migrate` erweitert seine Adapter-Landschaft um Trino als **read-first**
Federation-Layer. Fokus ist die sichere Nutzung als Analyse- und
Metadaten-Lesequelle – nicht als vollwertiger OLTP-Migrationspfad.

## 1) Zielbild

`d-migrate` soll Trino als **zusätzlichen, read-first Adapter** unterstützen.

Primäre Nutzung:

- Reverse Engineering großer Kataloge/Schema
- Schema-Vergleich gegen neutrale Zielartefakte
- Export und Profiling über den Analyse-Layer
- Daten-Transfers mit Trino als Quelle

Der Adapter ist **nicht** der primäre OLTP-Migrationspfad.

## 2) Ausgangslage

`d-migrate` besitzt heute native Treiber für PostgreSQL, MySQL und SQLite.
Trino bringt bereits heute in vielen Setups Zugriff auf heterogene Quellen ohne
direkten Treiberverbrauch aller Backends.

Das macht Trino für Read/Analyse attraktiv, gleichzeitig limitiert:

- transaktionale DDL/DML-Semantik,
- vollständige Constraint-/Trigger-/Procedure-Abdeckung,
- Connector-übergreifend konsistente Write-Garantien.

## 3) Ziel- und Nicht-Ziele

### 3.1 In Scope (Phase 1)

- Dialekt: `DatabaseDialect.TRINO` + Alias `trino`.
- URL-Parsing: `trino://user@host:port/catalog/schema` (kanonisch).
- Neues Adapter-Modul: `adapters:driven:driver-trino`.
- Eindeutiges Capability-Modell: **Phase 1 = write-frei**.
  - Reads sind als Source erlaubt; `schema compare` ist als read-only Ziel-Pfad explizit erlaubt.
- Read-only Pipelines:
  - `schema reverse`
  - `schema compare`
  - `data export`
  - `data profile` (ab Phase 1 Tranche 3; `driver-trino-profiling` ist
    feste Treiber-Standardabhängigkeit, **Source-only**)
  - `data transfer` **nur Source**

Default-Regel für Phase 1: Target-Nutzung ist standardmäßig gesperrt. Nur explizit als
Ausnahme in der Capability-Matrix markierte Zielpfade sind erlaubt (aktuell: `schema compare`
als read-only Diff-Pfad).

### 3.2 Nicht in Scope (aktuell)

- Schreibpfade (`data import`, allgemeine DDL-Generierung) als Standardpfad.
- Transaktions-/UPSERT-/MERGE-Semantik im Trino-Pfad.
- Vollständige Migration mit Constraint-, Trigger- und Procedure-Semantik.
- Alias `presto` → `TRINO`.
- `schema generate` in Phase 1 (erscheint frühestens in Phase 3).

## 4) Architektureinbettung

Der Adapter nutzt das bestehende Hexagon-Modell:

```text
adapters:driven:driver-trino
  ├─ ConnectionPool / JDBC Wiring
  ├─ TrinoSchemaReader
  ├─ TrinoTableLister
  └─ TrinoDataReader
```

Module-Erweiterungen:

- `adapters:driven:driver-trino-profiling` wird **in Phase 1 Tranche 3**
  ausgeliefert und ist ab dann **fest verkabelte Standardabhängigkeit**
  des Trino-Treibers (kein Opt-in, kein separater Feature-Flag).
  Konsequenz: `data profile --source trino://...` ist ab Tranche 3
  lauffähig. Eine separate „Modul-nicht-vorhanden"-Konstellation existiert
  nach Tranche-3-Auslieferung nicht; entsprechende Tests dokumentieren
  die feste Verkabelung statt eines Opt-in-Pfads.
- `test:integration-trino` (Tranche-2-Smoke gegen Testcontainers;
  Vollausbau ab Phase 2).

### 4.1 Integration in ports-common (`ConnectionConfig`, Dialect-Context)

Trinos URL-Modell mit `catalog`+`schema` lässt sich nicht sauber auf das
bestehende einfeldrige `database: String` von
`hexagon/ports-common/.../ConnectionConfig.kt` mappen. Wir folgen daher dem
etablierten Muster für dialektspezifische Felder (siehe ADR-Memory
`feedback_hexagon_dialect_context`): **keine nullable `trino*`-Felder am
generischen Port**, sondern ein sealed Dialect-Context.

Verbindlich für Tranche 1:

- `ConnectionConfig.database` bleibt der kanonische String-Slot; für Trino
  hält er ausschließlich den **Schema-Namen** (zweites Pfadsegment).
- Der **Katalog** und alle Trino-spezifischen Parser-Felder (z. B.
  `httpScheme`, Session-Allowlist-Snapshot) landen in
  einem neuen `TrinoConnectionContext` als Variante eines sealed
  `DialectConnectionContext` (parallel zum bestehenden
  `DdlDialectContext`).
- `ConnectionConfig` erhält ein optionales `dialectContext:
  DialectConnectionContext?`-Feld; für PG/MySQL/SQLite bleibt es `null`.
- Maskierungspflichtige Felder (Token, Passwörter, `session.<name>`-Werte)
  liegen ausschließlich im Trino-Context und werden dort
  per-`toString()` als `***` gerendert.

Diese Architekturentscheidung ist **DoD-Pflicht in Tranche 1a** (siehe §6).

### 4.2 Mapping d-migrate-URL-Properties → Trino-JDBC-Properties

Der generische `JdbcUrlBuilder` merged `ConnectionConfig.params` roh in
die JDBC-URL
(`hexagon/ports-common/.../JdbcUrlBuilder.kt`). Trinos JDBC-Treiber
verlangt jedoch **case-sensitive** Property-Namen (`SSL` statt `ssl`,
`SSLTrustStorePath` statt `trustStorePath`, `sessionProperties` als
gemeinsame Map etc.). Ohne explizites Mapping würden korrekt
dokumentierte d-migrate-URLs vom Trino-Treiber ignoriert.

**Pflicht-API-Erweiterung in ports-common / driver-common:**

Die heutige Signatur `JdbcUrlBuilder.buildJdbcUrl(config): String`
(`hexagon/ports-common/.../JdbcUrlBuilder.kt`) sowie der bestehende
Hikari-Init in `HikariConnectionPoolFactory` (setzt nur `jdbcUrl`,
`username`, `password`) reichen für Trino nicht aus, weil
`accessToken`, `SSLTrustStorePassword`, `SSLKeyStorePassword` und
`sessionProperties` über JDBC-Driver-Properties laufen müssen, nicht
über die URL. Phase 1 erweitert die API daher um eine kleine
Specs-Klasse:

```kotlin
data class JdbcConnectionSpec(
    val jdbcUrl: String,
    /** Wird an DriverManager-Properties bzw. addDataSourceProperty übergeben. */
    val driverProperties: Map<String, String> = emptyMap(),
)

interface JdbcUrlBuilder {
    val dialect: DatabaseDialect
    fun buildConnectionSpec(config: ConnectionConfig): JdbcConnectionSpec
    // Default-Bridge für bestehende Aufrufer; entfällt nach Tranche 1b
    fun buildJdbcUrl(config: ConnectionConfig): String = buildConnectionSpec(config).jdbcUrl
}
```

Konsequenzen für die Verkabelung:

- `HikariConnectionPoolFactory` ruft `buildConnectionSpec` auf und
  reicht jeden Eintrag aus `driverProperties` per
  `HikariConfig.addDataSourceProperty(key, value)` (bzw. via
  `HikariConfig.dataSourceProperties`) an den Treiber durch.
  `username`/`password` werden nicht mehr aus `ConnectionConfig`
  überschrieben, sobald `driverProperties` `user`/`password` enthält.
- Bestehende Driver (PostgreSQL, MySQL, SQLite) bleiben kompatibel,
  weil sie weiterhin nur `jdbcUrl` (und leere `driverProperties`)
  liefern.
- Der dedizierte **Trino-`JdbcUrlBuilder`** im `driver-trino`-Modul
  liefert die unten beschriebene Mapping-Tabelle (Quelle:
  <https://trino.io/docs/current/client/jdbc.html>; bei
  Treiber-Versionssprüngen ist die Tabelle vor dem Bump zu verifizieren).

| d-migrate-URL-Property | Trino-JDBC-Property | Anmerkung |
| --- | --- | --- |
| `ssl=true\|false` | `SSL=true\|false` | Pflicht in `production`. |
| `httpScheme=https\|http` | (kein Direct-Mapping) | Wird in `SSL=true/false` aufgelöst (§5.2.3). |
| `accessToken=<token>` | `accessToken=<token>` | camelCase im Trino-Treiber, deckt sich mit d-migrate. |
| `trustStorePath=<path>` | `SSLTrustStorePath=<path>` | |
| `trustStorePassword=<pw>` | `SSLTrustStorePassword=<pw>` | |
| `keystorePath=<path>` | `SSLKeyStorePath=<path>` | |
| `keystorePassword=<pw>` | `SSLKeyStorePassword=<pw>` | |
| `session.<name>=<value>` *(je Eintrag)* | über `Properties.setProperty("sessionProperties", "<n1>:<v1>;<n2>:<v2>")` | Werte delimiter-sicher (siehe unten). Reihenfolge deterministisch (lexikografisch). **Nicht** in JDBC-URL. |
| `user`, `password` (aus `userinfo`/`DM_TRINO_PASSWORD`) | über `java.util.Properties`, **nicht** in der JDBC-URL | siehe Secret-Transportregel unten. |

Regeln:

- Properties außerhalb dieser Tabelle und ohne Phase-1-Allowlist-Eintrag
  werden vor dem Bau der JDBC-URL abgelehnt (`LOCAL_ERROR`).
- Der Trino-Builder benutzt **nicht** den generischen Roh-Merge;
  `ConnectionConfig.params` bleibt für andere Dialekte unverändert.

**Delimiter-Schutz für `session.<name>`-Werte:**

Trino-`sessionProperties` ist ein flacher Key-Value-Stream mit
Property-Separator `;` und Key-Value-Separator `:`. Würden d-migrate
einen dekodierten Wert mit `;` oder `:` direkt einbetten, könnte ein
Eintrag wie `session.tag=foo;query_max_run_time=1ms` weitere
Pseudo-Properties einschleusen und die Allowlist (die nur Keys prüft)
umgehen.

Phase-1-Regel:

- Nach Percent-Dekodierung darf jeder `session.<name>`-Wert weder `;`
  noch `:` enthalten. Verstoß → `LOCAL_ERROR` (Exit 7), inkl. Hinweis
  „Trino session value must not contain `;` or `:` (use a different
  representation)".
- Encoded Varianten (`%3B`, `%3A`, `%3b`, `%3a`, Mixed-Case) sind nach
  Decodierung identisch zu blockieren — DoD 1b enthält explizite Tests
  für diese Sequenzen.
- Phase 2 evaluiert, ob ein Quoting-Schema (z. B. JDBC-Properties-API
  statt URL-Inlining) den Restriktionsschritt ersetzen kann; Phase 1
  wählt bewusst die strikte Variante.

**Secret-Transportregel:**

Sensitive Trino-Properties dürfen **nicht** als URL-Bestandteil in der
finalen `jdbcUrl` landen, sondern werden ausschließlich über die
`java.util.Properties`-Instanz an `DriverManager.getConnection(url,
props)` bzw. die Hikari-`addDataSourceProperty(...)`-API übergeben.

Betroffen:

- `user` (nicht-sensitiv, aber konsistent über Properties)
- `password` (aus `userinfo`/`DM_TRINO_PASSWORD`)
- `accessToken`
- `SSLTrustStorePassword`, `SSLKeyStorePassword`

Auch der `sessionProperties`-Stream gehört dazu — er kann je nach
Session-Wert sensitive oder diagnostisch heikle Daten enthalten und
ist gemäß §5.3 pauschal maskierungspflichtig. Er wird ebenfalls über
`Properties.setProperty("sessionProperties", ...)` weitergereicht und
**nicht** in die JDBC-URL inlined.

In der finalen JDBC-URL erscheinen ausschließlich nicht-sensitive
Konfigurationswerte (`SSL`, `SSLTrustStorePath`, `SSLKeyStorePath`).

DoD-Anforderungen:

- DoD-Tranche-1b: Test verifiziert, dass die zusammengesetzte
  `jdbcUrl` keine Secret-Werte enthält (`accessToken`, `password`,
  `*Password`).
- DoD-Tranche-2: Smoke-Test verifiziert, dass die Mapping-Tabelle
  tatsächlich greift (mind. ein TLS-Pfad + ein Session-Property + ein
  Secret über Properties statt URL).

## 5) Kontrakt: Dialekt und Connection-URL

### 5.1 Dialekt

- Erweiterung `DatabaseDialect` um `TRINO`.
- Alias-Mapping: `trino -> TRINO`.

### 5.2 URL-Modell

Diese Sektion ist die verbindliche Sequenz: Parse → Decode/Normalize → Guards → Forwarding → Entscheidung.

#### 5.2.1 URL-Form und Pflichtsegmentierung

Kanonische Form:

```text
trino://user@host:port/catalog/schema
```

Die **URL-Form selbst** trägt keinen `db:`-Prefix; sie folgt dem etablierten
d-migrate-URL-Schema (`<dialect>://...`) aus `spec/connection-config-spec.md`
und wird auch ohne Prefix von `--source`/`--target` der meisten Kommandos
(`schema reverse`, `data export`, `data transfer`, `data profile`) akzeptiert.

Hinweis zur Operand-Notation von `schema compare`:

`schema compare` parst prefixlose Operanden gemäß
`CompareOperandParser` als **Dateipfade**. Trino-Quellen/-Ziele müssen
deshalb dort mit dem CLI-Operand-Prefix `db:` notiert werden, also
`db:trino://...`. Siehe `spec/cli-spec.md`, Abschnitt „Operand-Notation".
Andere Kommandos brauchen den Prefix nicht.

Beispiele:

- `trino://analyst@localhost:8080/hive/default`
- `trino://analyst@localhost:8080/iceberg/default`
- `trino://analyst@localhost:8080/postgresql/public`
- `trino://analyst@[2001:db8::1]:8080/iceberg/default`

Interpretation:

- `catalog` ist das erste Pfadsegment.
- `schema` ist das zweite Pfadsegment.
- `host` ist ein RFC-konformer Hostname/IPv4 oder IPv6 in eckigen Klammern
  (`[2001:db8::1]`), niemals als ungebundener `:`-Wert (`::1` ohne Klammern).
- Kanonisch ist die URL inklusive `schema`; für Phase 1 gilt `schema` daher als verpflichtend.
- Parser verweigert Formate ohne `schema` in Phase 1 bereits auf Parsing-Ebene mit
  `action_required`.
- Query-Parameter sind bis auf explizit freigegebene Properties als harte
  Capability-Fehler zu behandeln. Die erlaubten Properties in Phase 1 sind:
  - `ssl` (`true|false`, default: `true`)
  - `httpScheme` (`http|https`, default: `https`)
  - `session.<name>` (Session-Property-Forwarding; Name muss in der aktivierten
    Allowlist enthalten sein und dort nach Trino-Phase-1-Schema verarbeitet werden)
  - `accessToken`
  - `trustStorePath`
  - `trustStorePassword`
  - `keystorePath`
  - `keystorePassword`

  Hinweis Timeouts: Phase 1 modelliert **keine** trino-spezifische
  `requestTimeoutMs`-Property. Der offizielle Trino-JDBC-Treiber kennt
  kein solches Property; HTTP-Timeouts werden vom internen Client gesteuert.
  Für Phase 1 gilt:
  - Connection-/Pool-Timeouts laufen über die bestehenden HikariCP-Settings
    (`PoolSettings.connectionTimeoutMs`).
  - Statement-/Query-Timeouts werden auf JDBC-Standard `Statement.setQueryTimeout`
    gelegt, gesteuert durch den vorhandenen `statementTimeoutMs`-Mechanismus.
  - Sollte ein späterer Trino-Treiber ein dediziertes Property anbieten,
    wird es in Phase 2 nach Versionspinning ergänzt; bis dahin landen
    Trino-URLs mit `requestTimeoutMs=...` als nicht erlaubte Property
    hart auf `LOCAL_ERROR` (siehe §5.4.0).
- Sicherheitsklassifikation nach Parsing (Phase 1):
  - Nicht-sensitive Parser-Properties: `ssl`, `httpScheme`,
    `trustStorePath`, `keystorePath`.
  - Sensitive, nicht allowlist-gesteuerte Parameter: `user:password` (Authority),
    `accessToken`, `trustStorePassword`, `keystorePassword`.
  - `session.<name>` gilt als sensitive, aber **separat behandelt**:
    Erlaubnis erfolgt ausschließlich über die `session`-Allowlist, unabhängig vom Runtime-Profil.
    Werte sind immer maskierungspflichtig.
  - Für alle sensiblen Parameter und `session.<name>`-Werte gilt zusätzlich:
    - Das Vorhandensein des Secret-Schlüssels ist explizit.
    - Der Secret-Wert darf nach Decodierung/Trim nicht leer sein; leere Werte führen zu
      hartem `action_required` (auch in `non_production`).
  - Syntaktisch erlaubt bedeutet nicht automatisch nutzbar:
    - `accessToken`
    - `trustStorePassword`
    - `keystorePassword`
    sind in `production` hart blockiert.
  - `trustStorePath` und `keystorePath` sind Konfigurationsfelder und können
    in `production` genutzt werden, sofern keine Secrets direkt übergeben werden.
    (Hinweis: `user:password`, `trustStorePassword`, `keystorePassword` bleiben
    dort blockiert.)
  - Legacy-Geheimniswerte (`user:password`, `accessToken`, `trustStorePassword`,
    `keystorePassword`) sind in `non_production` nur mit aktivierter
    Legacy-Geheimnis-Ausnahme erlaubt (siehe Sicherheitskontext).
#### 5.2.2 Decodierung, Normalisierung und Duplicate-Regeln

- URL-Kodierung und Decodierungs-Guards:
  - `user`, `password`, `catalog`, `schema` und Query-Parameter (`key`/`value`) sind
    URL-kodiert zu interpretieren.
  - Vor der Validierung wird eine RFC-konforme Percent-Decodierung (UTF-8) durchgeführt.
    Ungültige Encodings führen zu `action_required`.
  - Decodierungsreihenfolge:
    - Erst wird aus dem rohen URL-String strukturiert geparst (`scheme://`, Authority,
      Pfad, Query-Teil).
    - Anschließend werden `user`, `password`, `catalog`, `schema` sowie jedes erkannte
      Query-Property (`key`/`value`) nach der Initial-Pfadeinordnung percent-dekodiert.
  - Nach der Decodierung werden Query-Properties normalisiert:
    - Alle Property-Keys und -Values werden zunächst getrimmt.
    - `session.<name>` wird zusätzlich wie in Abschnitt *Session-Forwarding-Liste*
      strikt validiert (Musterprüfung gegen `v1`-Regex, Name-Auflösung). Es
      findet **keine** Case-Normalisierung statt; Großbuchstaben im `<name>`
      werden als `LOCAL_ERROR` abgelehnt.
    - Andere Property-Schlüssel (`ssl`, `httpScheme`, `accessToken`,
      `trustStorePath`, `trustStorePassword`, `keystorePath`, `keystorePassword`) werden
      ausschließlich nach Decodierung gegen die erlaubte Property-Liste geprüft (Case-sensitiv
      wie spezifiziert).
  - Duplikatserkennung für Query-Properties:
    - Der Vergleich erfolgt auf der vollständig normalisierten Schlüssel-Repräsentation.
    - Jede doppelte Property-Key-Instanz (inklusive `session.<name>`) führt hart zu
      `action_required`.
  - Die Deduplizierung in *Session-Forwarding-Liste* betrifft nur die Konfigurationsliste und
    ersetzt keine Duplicate-Guard im Query-Parsing.
  - `catalog` und `schema` dürfen nach Dekodierung keinen `/` mehr enthalten; ein
    solcher Befund führt zu `action_required` (Vermeidung segmentierter Ambiguitäten).
- Weitere Pfadsegmente sind in Phase 1 ungültig.
- Kodierungsvarianten derselben Bedeutung gelten als identischer Key (z. B. unterschiedliche
  Encodings desselben `session.<name>`-Schlüssels).
#### 5.2.3 Runtime- und Transport-Guards

- Ausführungskontext:
  - Profilelöse-Reihenfolge: `--trino-runtime-profile` > `DM_TRINO_RUNTIME_PROFILE` >
    `production`.
  - Implizit bedeutet das: Ohne gesetzte Quelle fällt das Profil in `production` zurück und
    Production-Guards greifen ohne zusätzliche Konfiguration automatisch.
  - Unterstützte Werte sind ausschließlich `production` und `non_production`.
  - Alle anderen Angaben führen zu `action_required`.
- Transport-Guards:
  1. Erlaubte Werte nach Decodierung:
     - `ssl`: `true|false`
     - `httpScheme`: `http|https`
  2. Presence-Tracking:
     - `sslProvided`: `ssl` explizit im Query gesetzt
     - `httpSchemeProvided`: `httpScheme` explizit im Query gesetzt
  3. Inkompatible Kombinationen sind harte Fehler (`action_required`):
     - `ssl=true` + `httpScheme=http`
     - `ssl=false` + `httpScheme=https`
  4. Effektive Transportauflösung:
     - Kein Parameter gesetzt: `ssl=true`, `httpScheme=https`.
     - Nur `ssl` gesetzt:
       - `ssl=true` → `https`
       - `ssl=false` → `http`
     - Nur `httpScheme` gesetzt:
       - `https` → `ssl=true`
       - `http` → `ssl=false`
     - Beide gesetzt und kompatibel: direkte Konsistenzprüfung aus Schritt 3.
  5. Transportpolicy:
     - `production`: `httpScheme=http` und `ssl=false` sind immer hart blockiert.
     - `non_production`: unsicherer Transport nur mit aktivem `insecure_transport`.
- `insecure_transport` ist aktiv nur wenn alle Bedingungen erfüllt sind:
  - effektives Profil ist `non_production`,
  - `--allow-insecure-trino-transport` ist gesetzt (CLI-Flag explizit),
  - `DM_TRINO_ALLOW_INSECURE_TRANSPORT=true`.
- `DM_TRINO_ALLOW_INSECURE_TRANSPORT` ist nur als exakter String `true` wirksam.
  - Alle anderen Werte bleiben inaktiv und führen bei sicherheitskritischer Transportanforderung
    zu `action_required` inkl. fehlender Signaturbestandteile
    (`--trino-runtime-profile`, `--allow-insecure-trino-transport`, `DM_TRINO_ALLOW_INSECURE_TRANSPORT`).
- Die Ausführung wird bei aktivem `insecure_transport` als `insecure_transport=true` markiert.

#### 5.2.4 Session-Forwarding-Liste und Versionierung

Konfiguration der Session-Forwarding-Liste:

- Quelle mit fester Präzedenz:
  1. CLI-Flag `--trino-session-allowlist=<comma-separated-list>` (nur wenn der
     Flag explizit gesetzt ist)
  2. `DM_TRINO_SESSION_ALLOWLIST` (Umgebungsvariable)
  3. Default leer (`""`)
- Nicht gesetztes CLI-Flag gilt als „nicht vorhanden“, auch wenn der
  CLI-Parser intern einen leeren Defaultwert liefert.
- CLI-Implementierungserfordernis:
  - Implementierung muss explizit tracken, ob `--trino-session-allowlist` gesetzt wurde
    (Tri-State: gesetzt/nicht gesetzt), nicht nur den String-Wert.
  - Eine implizite leere Default-Quelle gilt nicht als gesetzte Quelle.
- Eine explizit leere Liste ist erlaubt und kann per `--trino-session-allowlist=""`
  gesetzt werden.
- Format: komma-separierte Liste von Session-Keys in Kleinbuchstaben, z. B.
  `query_max_run_time,query_max_cpu_time`
- Normalisierung (Phase 1 = strikt):
  - Whitespace wird getrimmt, leere Tokens verworfen.
  - **Keine Case-Normalisierung.** Eingaben müssen bereits in
    Kleinbuchstaben vorliegen. Großbuchstaben oder Mixed-Case führen
    deterministisch zu `LOCAL_ERROR` (Exit 7); es findet keine implizite
    Konvertierung statt. Begründung: Ein expliziter Konfigurationsfehler
    ist sichtbarer als eine stille Umformung.
  - Reihenfolge wird deterministisch sortiert und Duplikate dedupliziert.
  - Jeder Eintrag muss dem Phase-1-Muster `v1` entsprechen:
    - Kleinbuchstaben, Ziffern, Unterstrich, Bindestrich; optionale
      Punkt-getrennte Segmente.
    - Erstes Zeichen pro Segment muss ein Kleinbuchstabe sein; in
      Dot-Segmenten zusätzlich Ziffern erlaubt.
    - Letztes Zeichen pro Segment darf kein Bindestrich sein.
    - Leere Segmente sind verboten.
  - Akzeptierte Beispiele:
    - `query_max_run_time`
    - `query-max-cpu-time`
    - `hive.s3_staging_directory`
  - Abgelehnte Beispiele (alle → `LOCAL_ERROR`):
    - `token.` (leeres Segment am Ende)
    - `a..b` (leeres Segment in der Mitte)
    - `Query_Max_Run_Time` (Großbuchstaben — werden **nicht** normalisiert,
      sondern deterministisch abgelehnt)
    - `_query` (führender Underscore), `-query` (führender Bindestrich)
  - Dieselbe Strikt-Regel gilt für `session.<name>` aus der URL-Query:
    Großbuchstaben im `<name>`-Teil werden abgelehnt, statt nach unten zu
    konvertieren.
  - Referenzimplementierung des `v1`-Regex (Kotlin-Raw-String):
    `^[a-z](?:[a-z0-9_-]*[a-z0-9_])?(?:\.[a-z0-9](?:[a-z0-9_-]*[a-z0-9_])?)*$`
  - Der Regex wird bei Bedarf über eine neue
    `DM_TRINO_SESSION_ALLOWLIST_V`-Version erweitert; aktuelle Schreibweise
    bleibt deterministisch.
- Versionierung:
  - `DM_TRINO_SESSION_ALLOWLIST_V` ist optionaler Versionsmarker.
  - Unterstützte Werte: `v1` (Default bei fehlender Angabe).
  - Nicht parsebare / nicht unterstützte Versionen führen zu `action_required`, wenn
    `DM_TRINO_SESSION_ALLOWLIST` als aktive Quelle verwendet wird.
  - Bei aktivem CLI-Override gilt die Versionslogik nur für `DM_TRINO_SESSION_ALLOWLIST_V`;
    ungültige Werte sind dann nicht blockierend, da die CLI-Quelle die Env-Quelle übersteuert.
  - Bei aktivem CLI-Override wird ein ungültiger `DM_TRINO_SESSION_ALLOWLIST_V` dokumentiert
    (Warnung), und die gesamte Env-Quelle inkl. Versionsmarker ist vollständig deaktiviert
    (d. h. Werte aus `DM_TRINO_SESSION_ALLOWLIST` werden unabhängig vom Inhalt ignoriert).
  - CLI-Allowlist wird immer mit der v1-Schema-Logik geparst; ein separater
    Versionsschalter ist für CLI nicht vorgesehen.
  - Für `v1` gilt ausschließlich das oben definierte normalisierte CSV-Format.

#### 5.2.5 Entscheidungs-Matrix für Guard-Flows (Phase 1)

Die Reihenfolge ist verbindlich für Implementierung und Tests, damit die
erste reproduzierbare Fehlerklasse stabil ist (keine implementation-defined
Race zwischen Auth und Capability). Sie unterscheidet bewusst zwischen
**Scheme-Sniffing** (zur Dialect-Identifikation) und **vollständigem
URL-Parsing** (Pfadsegmente, Properties, Decoding):

0. **Scheme-Sniffing**
- Liest ausschließlich das `scheme://`-Präfix der URL bzw. extrahiert
  bei `schema compare` den Dialect aus dem `db:`-prefixierten Operand.
  Reiner String-Match auf `^trino://` (case-sensitive), keine
  Pfadsegmente, keine Query-Teile, keine Decodierung.
- Schlägt das Scheme-Sniffing fehl (komplett unparsebarer Operand,
  kein erkennbares Scheme), wird der Flow direkt mit `LOCAL_ERROR`
  beendet.

1. **CLI-Capability-Gate** (Source/Target-Pfad)
- Auf Basis des gesnifften Schemes wird geprüft, ob das Kommando
  Trino als Source oder Target überhaupt erlaubt (siehe §5.6).
- Verbotene Zielpfade (`data transfer --target trino://...`,
  `schema generate --target trino` in Phase 1, `data import` …)
  brechen sofort mit `USAGE_ERROR` (Exit 2) ab — **vor** vollständigem
  URL-Parsing, Profil- oder Secret-Guards. Tests können sich darauf
  verlassen, dass weder Auth- noch URL-Syntax-Fehler in diesen
  Konstellationen beobachtet werden, auch wenn die URL syntaktisch
  kaputt ist oder kein Passwort/Token enthält.

2. **Vollständiges URL-Parsing und Percent-Decodierung**
   (Authority, Pfad, Query-Teil; siehe §5.2.2). Ungültige Encodings
   oder verbotene Pfadbestandteile (z. B. `/` im dekodierten
   `catalog`/`schema`) brechen mit `LOCAL_ERROR`.

3. Runtime-Profil-Auflösung
- `--trino-runtime-profile` > `DM_TRINO_RUNTIME_PROFILE` > Default `production`.
- Jeder ungültige Profilwert (inkl. unbekannte Tokens) führt sofort zu `action_required`.
- Fehlt die Profilangabe vollständig, wird deterministisch `production` wirksam.

4. Transport-Guards
- Inkompatible Kombinationen (`ssl=true` + `httpScheme=http`, `ssl=false` + `httpScheme=https`) werden
  sofort hart mit `action_required` abgelehnt.
- Transportauflösung erfolgt deterministisch aus den normalisierten Query-Properties.
- `production`: `httpScheme=http` und `ssl=false` sind immer hart blockiert.
- `non_production`: unsicherer Transport ist nur aktiv bei:
  - `--allow-insecure-trino-transport` gesetzt
  - `DM_TRINO_ALLOW_INSECURE_TRANSPORT=true`
  - effektivem Profil `non_production`
- `DM_TRINO_ALLOW_INSECURE_TRANSPORT` darf nur als String `true` wirksam werden; alle
  anderen Werte aktivieren die Ausnahme nicht und führen bei gefordertem unsicherem Transport
  zu `action_required`.

5. Secrets-Guards
- `production`: harte Blockade für `user:password`, `accessToken`, `trustStorePassword`,
  `keystorePassword` ohne Ausnahme.
- `non_production`: diese Werte nur mit aktiver Legacy-Geheimnis-Ausnahme erlaubt
  (`--allow-legacy-trino-secrets` oder `DM_TRINO_ALLOW_LEGACY_TRINO_SECRETS=true`).
- `session.<name>` bleibt in beiden Profilen ausschließlich allowlist-gesteuert.
- Auth-Pflicht (`DM_TRINO_PASSWORD` oder `accessToken`) wird hier geprüft —
  **erst nach** dem CLI-Capability-Gate (Schritt 1) und den Transport-Guards.

6. Session-Forwarding-Quelle
- Präzedenz: explizit gesetztes CLI-Flag > Umgebungsvariable > leerer Default.
- Bei aktivem CLI-Override wird die Env-Quelle (inkl. `DM_TRINO_SESSION_ALLOWLIST_V`) vollständig
  ignoriert; ungültige Env-Versionen werden nur gewarnt, aber nicht blockiert.

7. Fehlerverhalten
- Alle obigen Verstoßfälle sind reproduzierbar als `action_required` (ohne Retry-/Transient-Pfade).
- Die in §5.4.0 dokumentierten Exit-Codes folgen der Schritt-Nummer:
  Schritt 1 → `USAGE_ERROR`, Schritte 0/2–6 → `LOCAL_ERROR`. Test-Assertions
  prüfen Exit-Code und Konventions-Präfix.

#### 5.2.6 Credential-Modell (Phase 1)

- Basisform: `trino://user[:password]@host:port/catalog/schema`.
- Optional/empfohlen: Passwort via Umgebungsvariable (z. B. `DM_TRINO_PASSWORD`) oder
  späterer Credential-Provider.
- `userinfo`-Parsing (Phase 1):
  - Grammatik nach Decodierung: `userinfo = user [ ":" password ]`
- `user` ist Phase 1 verpflichtend und muss vorhanden und nicht leer sein.
- Authentifizierungs-Modi für Phase 1 (mindestens **einer** muss erfüllt sein):
  - **URL-embedded Passwort** (`user:password@...`) — nur in `non_production`
    mit aktivierter Legacy-Geheimnis-Ausnahme.
  - **`DM_TRINO_PASSWORD`** als Umgebungs-Secret — in beiden Profilen erlaubt,
    Standardweg für `production`.
  - **`accessToken`** als Query-Property — ergänzt `user`, ersetzt aber nicht
    die Pflicht zu vorhandenem `user` in der URL. In `production` hart
    blockiert; in `non_production` nur mit Legacy-Geheimnis-Ausnahme.
- Wenn `:` in `userinfo` vorhanden ist, muss `password` nach Decodierung/Trim
  existieren und darf nicht leer sein.
- Bei vorhandenem `:` gilt `password` als URL-embedded Secret.
  - Leeres Passwort (`user:` oder Äquivalent nach Decodierung) wird in allen Profilen
    deterministisch mit `action_required` abgelehnt.
  - URL-embedded Secret ist in `production` hart blockiert.
  - In `non_production` ist URL-embedded Secret nur mit aktivierter
    Legacy-Geheimnis-Ausnahme erlaubt.
- Fallback-Auflösung, wenn kein `:` in `userinfo`:
  - Vorhandenes `accessToken` deckt die Auth-Pflicht bereits ab; `DM_TRINO_PASSWORD`
    ist dann **nicht erforderlich**.
  - Ohne `accessToken` ist `DM_TRINO_PASSWORD` Pflicht. Fehlend oder leer →
    deterministischer Guard-Fehler (`action_required`) in allen Profilen.
- URL-Embedded `user:password` ist in `production` hart blockiert.
  In `non_production` ist es für den Legacy-URL-Secret-Modus nur erlaubt, wenn
  die Legacy-Geheimnis-Ausnahme aktiv ist:
  `--allow-legacy-trino-secrets` oder `DM_TRINO_ALLOW_LEGACY_TRINO_SECRETS=true`.
  - Bei gleichzeitiger Übergabe gilt `DM_TRINO_PASSWORD` als Vorrang nur für den
    Standard-Fall `user` ohne Passwort in URL.
  - Ist `user:password` URL-embedded vorhanden, wird dies vor jedem
    `DM_TRINO_PASSWORD`-Fallback geparst und bei blockierenden Regeln (z. B.
    `production` oder fehlender Legacy-Ausnahme) sofort mit
    `action_required` abgelehnt.
  - Kein generischer Connector-Parameter-Bypass; nur explizit erlaubte Properties.
- Trino-spezifische Bekannte Phase-1-Einschränkung: Es wird kein
  Kerberos-/OIDC-Auth-Flow unterstützt. Token-basierte Auth läuft
  ausschließlich über `accessToken` mit gesetztem `user`. Siehe Risiko 9.5.

### 5.3 Security, Secrets und Maskierung

- Security-Matrix (Phase 1):
  - `production`: URL-Embedding und folgende Sensitive-Parameter sind hart blockiert:
    `user:password`, `accessToken`, `trustStorePassword`, `keystorePassword`.
  - `production`: `session.<name>` bleibt nur via Allowlist erlaubt und wird maskiert.
  - `production`: ohne explizite, per 5.2.4 als Quelle bestimmte Allowlist gibt es keine
    Session-Weitergabe (Default = leer).
  - `non_production`: Sensitive Nicht-Session-Parameter möglich als Übergangsfälle.
    - Erlaubt ist ausschließlich, wenn das effektive Runtime-Profil `non_production`
      aufgelöst wurde **und** die Legacy-Geheimnis-Ausnahme aktiv ist.
    - Legacy-Geheimnis-Ausnahme ist:
      - `--allow-legacy-trino-secrets` (CLI-Flag) oder
      - `DM_TRINO_ALLOW_LEGACY_TRINO_SECRETS=true` (Umgebungsvariable, exakt).
      - Alle anderen Werte (`false`, leer, andere Schreibweise, andere Tokens) aktivieren
        die Ausnahme nicht.
      - Präzedenz: CLI-Flag > Umgebungsvariable.
    - In allen Legacy-Secret-Fällen ist ein klarer, maskierter Warnhinweis erforderlich.

- Klassifikation der Sensitive-Parameter:

  | Klasse | Parameter | Profil-Gate | Allowlist | Maskierung |
  | --- | --- | --- | --- | --- |
  | **Profil-blockiert** | `user:password`, `accessToken`, `trustStorePassword`, `keystorePassword` | `production` hart blockiert; `non_production` nur mit Legacy-Geheimnis-Ausnahme | – | immer `***` |
  | **Allowlist-gesteuert** | `session.<name>`-Werte | kein Profil-Gate | Pflicht (`v1`-Muster) | Phase 1: pauschal `***` |
  | **Nicht-sensibel** | `trustStorePath`, `keystorePath` | – | – | nicht maskiert |

  Hinweise:

  - `session.<name>` ist **nicht** profil-blockiert: Die Allowlist-Entscheidung
    hat Vorrang vor Laufzeit- oder Profil-Gates. In beiden Profilen erlaubt,
    sofern Name in der angewandten Allowlist liegt.
  - Phase-1-Entscheidung zur Session-Wert-Maskierung: **pauschal `***`**, weil
    Allowlist-Einträge keine Klassifikation (`public` vs. `secret`) tragen.
    Diagnostische Klartext-Werte (`query_max_run_time=10m`) sind dadurch in
    Phase 1 nicht sichtbar. Phase 2 verfeinert dies via
    Allowlist-Eintragsklassifikation (`name:public`/`name:secret`).

- In produktiven Setups sind URL-Embedding und sensible URL-/Query-Secret-Parameter
  (`user:password`, `accessToken`, `trustStorePassword`, `keystorePassword`)
  als `action_required` hart blockiert.
- In neuen Setups muss ein Umgebungs-Secret (`DM_TRINO_PASSWORD`) oder späterer
  Credential-Provider genutzt werden.
- In Phase 1 gilt ergänzend:
  - `accessToken`, `trustStorePassword`, `keystorePassword` sind als Übergangs-Optionen
    nur mit aktiver Legacy-Geheimnis-Ausnahme nutzbar; in `production` weiterhin hart
    blockiert.
  - `session.<name>` ist erlaubt, wenn es im Rahmen der aktuellen Allowlist-Konfiguration
    akzeptiert ist; die Werte können Secret-artig sein und sind immer maskierungspflichtig.
- Geheimnisse dürfen nicht in Logs, Debug-Ausgaben, Cache-Keys oder Telemetrie mit
  Klartext enthalten sein.
- Jede Ausgabe mit potenziellen Secret-Feldern (Passwort, Token, Secret-Properties) ist
  deterministisch zu maskieren (`***`).
- Security-Terminologie:
  - Interne Runtime-Caches (z. B. Connection-/Metadaten-Caches): technisch eindeutig und reproduzierbar,
    intern gehasht/fingerprinted; Geheimnisse dürfen nicht im Klartext im Key gespeichert werden.
  - Diagnostische/benutzerzentrierte IDs (Hilfetexte, Logs, Fehlermeldungen, Cache-IDs):
    maskierte Darstellung (`***`) erforderlich.
  - Implementierungshinweis für interne Cache-Schlüssel:
    - HMAC/SHA-256 auf normalisierten Verbindungsdaten mit einem stabilen
      `DM_TRINO_CACHE_SALT` (deploymentspezifisch) zur Reproduzierbarkeit.
    - Ergebnis-Digests dürfen keine Klartext-Geheimnisse enthalten.
    - Fehlt `DM_TRINO_CACHE_SALT`, gilt **fail-secure**: kein
      generierter Default-Salt, sondern `action_required` mit Hinweis auf die
      Pflicht-Variable. Die Begründung: Ein impliziter Salt verleitet zur
      Wieder-Verwendung über Deployments hinweg und unterläuft die
      Cache-Trennung.
  - Session-Property-Regel:
    - `session.<name>` darf nur dann weitergereicht werden, wenn `<name>` in der expliziten
      Allowlist für Phase 1 und der angewandten Konfiguration enthalten ist.
      `name` wird nach der gleichen Normalisierung wie die Allowlist aufgelöst.
    - Unbekannte Session-Property-Namen brechen direkt mit `action_required`.

Hinweis zu Phase-1-Allowlist:

- `DM_TRINO_SESSION_ALLOWLIST` ist eine explizit gepflegte, versionsierte Liste (leerer Standard in Phase 1).
- Beispiel-Initialkonfiguration: `""` (keine Weitergabe).
- Konkrete Minimal-Konfiguration Phase 1:
  - `DM_TRINO_SESSION_ALLOWLIST=""`
  - `DM_TRINO_SESSION_ALLOWLIST_V` nicht gesetzt (kein Versionsmarker).

### 5.4 Fehler- und Signalisationsregeln

#### 5.4.0 Begriffsabgrenzung „action_required" und Exit-Code-Mapping

> **Wichtig — Begriffsklärung:** In `spec/cli-spec.md` ist `action_required`
> ausschließlich ein **Sidecar-Report-Status** für übersprungene
> Schema-Objekte bei `schema generate` (Exit 0, kein Abbruch). Im Trino-Plan
> wird der Begriff aus historischen Gründen abkürzend für „Trino-Adapter
> weigert sich" verwendet. **Diese beiden Bedeutungen sind nicht identisch.**
> Implementierer und Tests müssen jedes Vorkommen von `action_required` in
> diesem Plan in eine der konkreten CLI-Spec-Klassen aus der untenstehenden
> Tabelle übersetzen. Trino-Fehlermeldungen, Logs und Exit-Codes verwenden
> ausschließlich diese konkreten Klassen, niemals den Token
> `action_required` als Output.

| Anlass im Plan-Text | Konkrete CLI-Spec-Klasse | Exit-Code | Wo definiert |
| --- | --- | --- | --- |
| URL-Parse-Fehler (kanonische Form, Pfadsegmente, Percent-Decoding) | `LOCAL_ERROR` | 7 | `spec/cli-spec.md` §2 |
| Nicht erlaubte / doppelte / ungültige Query-Properties (`requestTimeoutMs`, `foo=bar`, Duplikate, ungültige Encodings) | `LOCAL_ERROR` | 7 | `spec/cli-spec.md` §2 |
| Verstoß gegen Runtime-Profil-/Transport-/Secret-Guards (inkl. fehlende Auth) | `LOCAL_ERROR` | 7 | dieser Plan §5.2.3–5.3 |
| Fehlende `DM_TRINO_CACHE_SALT`, fehlendes `DM_TRINO_PASSWORD`/`accessToken` | `LOCAL_ERROR` | 7 | dieser Plan §5.2.6, §5.3 |
| Capability-Verweigerung an CLI-Grenze (`data transfer --target trino://...`, `schema generate --target trino` in Phase 1) | `USAGE_ERROR` | 2 | `spec/cli-spec.md` §2 |
| `schema compare` mit `metadata_coverage=missing` ohne `--allow-metadata-gaps` | Compare-Sidecar-Reason `MANUAL_ACTION_REQUIRED`, Exit-Code je Compare-Spec (typisch `VALIDATION_ERROR` = 3) | 3 | `spec/cli-spec.md` §14.3 / Compare-Sektion |
| Trino-Verbindungsfehler nach erfolgreichem Parsing (Pool, Netzwerk, Auth-Ablehnung durch Server) | `CONNECTION_ERROR` | 4 | `spec/cli-spec.md` §2 |

Konsequenz für DoD-Items:

- Wo dieser Plan in DoD-Listen `action_required` schreibt, ist die jeweils
  passende Tabellenzeile gemeint. Test-Implementierungen prüfen den
  konkreten Exit-Code, nicht das Wort.
- Fehlermeldungs-Präfix-Konvention: `trino: <KLASSE>: <Hinweis>` (z. B.
  `trino: LOCAL_ERROR: httpScheme=http requires --allow-insecure-trino-transport ...`).



- fehlendes oder unklar formatiertes `catalog` -> deterministische Fehlermeldung mit
  Beispiel-URL.
- fehlendes oder leeres `schema` -> parse-time `action_required` mit Hinweis auf kanonische
  URL inklusive Schema.
- fehlender, nicht-numerischer oder außerhalb von `1..65535` liegender `port` -> parse-time
  `action_required` mit Beispiel-URL inklusive `host:port`.
- Nicht erlaubte oder unsichere Transport-Kombinationen:
  - `httpScheme=http` oder `ssl=false` ohne aktivem `insecure_transport` -> `action_required`
    inkl. klarer Sicherheitsaufforderung.
  - In `production` sind unsichere Kombinationen immer hart blockiert, unabhängig von der
    gewählten Signaturkombination.
  - In `non_production` sind unsichere Kombinationen nur bei aktivem `insecure_transport`
    erlaubt. Fehlende Signaturbestandteile führen deterministisch zu `action_required`
    inkl. Hinweis auf fehlende Bestandteile.
- Sensitive URL-/Query-Parameter (`user:password`, `accessToken`, `trustStorePassword`,
  `keystorePassword`) in `non_production` ohne aktive Legacy-Geheimnis-Ausnahme
  -> `action_required` mit klarer Diagnose der aktivierenden Flags.
  - Sensitive Parameter mit explizit gesetztem, aber leerem Wert führen ebenfalls zu
    `action_required` in allen Profilen.
  - In `production` ist dies immer hart blockiert.
  - In `non_production` gilt dieselbe Blockade, außer bei exakt aktivierter
    Entwickler-Ausnahme (Dreifach-Signatur, siehe oben).
- Ungültige Angaben zu `DM_TRINO_RUNTIME_PROFILE` oder `--trino-runtime-profile` -> `action_required`.
- Nicht unterstützte URL-Properties -> sofortiger Abbruch via `action_required`.
- Doppelte Query-Properties -> sofortiger Abbruch via `action_required`.
- Trino ist in Phase 1 ein **write-freier** Adapter; alle write-Pfade sind für
  Target/Sink gesperrt. `schema compare --target db:trino://...` bleibt als
  read-only Pfad explizit erlaubt.
  Nicht erlaubte Zielpfade brechen deterministisch mit `action_required` ab.
- Capability- oder Guard-Fehler sind dauerhaft reproduzierbar und damit als
  dauerhafte Signale zu behandeln (keine transienten Retry-Pfade).

### 5.5 Compare-Metadaten-Qualitätsmodell

`schema compare` mit Trino als `--source` oder `--target` verwendet ein
dreistufiges Metadaten-Abdeckungmodell:

- `full`: Objekt ist vollständig lesbar und vergleichbar
- `partial`: Objekt ist lesbar, aber unvollständig
- `missing`: Objektklasse ist nicht zuverlässig lesbar

Objektklassen (Phase-1-Vertrag):

- `table`
- `view`
- `column`
- `index`
- `constraint`
- `trigger`
- `procedure`
- `function`
- `udf`
- `sequence`
- `oid` *(PG-spezifische Identitäts-Spalte; behalten, weil im
  Compare-Pfad PG↔Trino die Identitätskonsistenz separat berichtet
  werden soll. In Trino immer `missing`.)*
- `other` (Fallback-Klasse für nicht explizit definierte Objektklassen)

Hinweis zur Coverage-Auswertung:

- Alle Objektklassen außerhalb der explizit aufgelisteten Werte werden als `other` behandelt.
- `other` ist mindestens `missing`, sofern keine explizite Trino-Unterstützung nachgewiesen ist.

Interpretation:

- `partial`: Vergleich erlaubt mit klarer Warnung (`metadata_coverage=partial`) pro
  Objektklasse.
- `missing`: Vergleich für die betroffene Klasse blockiert mit
  `action_required`. Nur bei explizit gesetztem `--allow-metadata-gaps`
  kann der Vergleich fortgesetzt werden; die betroffene Klasse wird weiterhin
  mit `metadata_coverage=missing` und dokumentierter Risikoannahme gemeldet.

#### 5.5.1 Per-Connector-Coverage-Vertrag (Phase-1-Minimum)

Damit `metadata_coverage` deterministisch ist, dokumentiert Phase 1
eine **statische Coverage-Map je Trino-Connector** (`iceberg`, `hive`,
`postgresql`, `mysql`). Diese Map ist im Adapter mitgeliefert und
versionsiert (`DM_TRINO_COVERAGE_MAP_V`, Default `v1`).

Verbindlich für Phase 1:

- Mindestens **ein Default-Connector mit vollständiger Coverage-Map**
  ist Voraussetzung für `schema compare` (Vorschlag: `iceberg` als
  Tranche-3-DoD-Baseline).
- Connectoren ohne Map liefern `metadata_coverage=missing` für alle
  Klassen außer `table`/`view`/`column` (diese sind via Trino-JDBC immer
  lesbar) und brechen ohne `--allow-metadata-gaps` ab.
- Map-Format (YAML, klasseweise):
  ```yaml
  connector: iceberg
  version: v1
  classes:
    table: full
    view: full
    column: full
    index: missing
    constraint: missing
    trigger: missing
    procedure: missing
    function: missing
    udf: missing
    sequence: missing
    oid: missing
    other: missing
  ```
- Phase 2 ergänzt eine Connector-Test-Matrix (`hive` vs. `iceberg`)
  und dynamische Coverage-Erkennung.

### 5.6 Capability-Governance für Trino

Standard-Verhalten ist in allen Phasen: Sink- oder Write-Pfade sind ohne explizite
Connector-Freigabe deaktiviert; die Tabelle bildet die einzige Ausnahmeliste ab.

| Befehl | Source | Target | Phase |
| --- | --- | --- | --- |
| `schema reverse` | ✅ | ❌ | 1 |
| `schema compare` | ✅ | ✅ *(read-only Diff-Pfad)* | 1 |
| `data export` | ✅ | ❌ | 1 |
| `data profile` | ✅ *(ab Tranche 3, Profiling-Modul fest verkabelt)* | ❌ | 1 |
| `data transfer` | ✅ | ❌ | 1 |
| `schema generate` | ❌ | ⚠️ (explizit freigegeben) | 3 |
| `data import` | ❌ | ❌ | 4+ |

Regel:

- `Target` für Trino ist in Phase 1 standardmäßig gesperrt.
- `schema compare --target db:trino://...` bleibt erlaubt, weil semantisch read-only.
- Write-/Generate-Funktionen erfordern immer einen expliziten Capability-Review je
  Connector.

- `data profile` ist in Phase 1 Source-only. Das ist strukturell durch
  die CLI-Definition durchgesetzt — `data profile` hat keine
  `--target`-Option. Eine spätere Ergänzung dieser Option würde gegen
  diesen Plan verstoßen; der CLI-Help-Snapshot-Test in Tranche 1a
  detektiert eine solche Änderung.

## 6) Umsetzungsphasen

### Phase 1 — Read-only MVP

**Ziel:** sicherer Trino-Lesepfad ohne Schreib-Risiko.

Phase 1 ist in vier Tranchen geschnitten (1a, 1b, 2, 3), damit das
umfangreiche Security-Modell nicht den ersten lauffähigen Read-Pfad
blockiert:

- **Tranche 1a** — Build-fähiges Minimum: `TRINO`-Enum,
  `DialectCapabilities`, kanonisches URL-Parsing, Source-only-Guards,
  Basis-Maskierung. Endet mit grünem Build und blockiertem `--target`.
- **Tranche 1b** — Vollständiges Security-Modell: Runtime-Profile,
  Insecure-Transport-Dreifach-Signatur, Legacy-Geheimnis-Ausnahme,
  Session-Allowlist + Versionierung, Cache-Salt-Pflicht.
- **Tranche 2** — Read-Infrastruktur inkl. Testcontainers-Smoke-Test.
- **Tranche 3** — `schema compare`, `data profile`, Coverage-Map.

Funktionaler Scope (über alle Tranchen):

1. Dialekt + URL-Alias implementieren.
2. Adapter/JDBC hinzufügen:
   - Connection/Pooling
   - `TrinoSchemaReader`
   - `TrinoTableLister`
   - `TrinoDataReader`
3. CLI-Coverage aktivieren:
   - `schema reverse`
   - `schema compare`
   - `data export`
   - `data profile` (ab Tranche 3 ausgeliefert; `driver-trino-profiling`
     ist feste Standardabhängigkeit)
   - `data transfer` mit Source-only-Guard

Validierungsregeln:

- `schema reverse --source trino://... --output ...` ist lauffähig.
- `data transfer --target trino://...` startet nicht.
- `data profile` kennt per CLI-Definition keine `--target`-Option;
  Source-only ist strukturell durchgesetzt und durch Snapshot-Test gegen
  die CLI-Help abgesichert.
- `data profile --source trino://...` ist ab Tranche 3 lauffähig
  (`driver-trino-profiling` ist verkabelt; kein separater Modul-Opt-in).
- Nicht erlaubte Query-Properties liefern reproduzierbar `action_required`.
- Doppelte Query-Properties liefern reproduzierbar `action_required` (auch bei unterschiedlich
  kodierten Doppelungen).
- `schema compare --target db:trino://...` dokumentiert `metadata_coverage` pro
  Objektklasse.

### 6.1 Phase-1-Abnahmekriterien

- URL-Parsing:
  - `catalog`- und `schema`-Auflösung sind deterministisch.
  - Fehlende Felder oder ungültige Pfadsegmente liefern `action_required`.
  - Nicht erlaubte Query-Properties liefern `action_required`.
- Capabilities:
  - Source-only-Verhalten für `data transfer` ist technisch erzwungen.
  - `data import` ist in Phase 1 deaktiviert.
- Security:
  - Secret-Maskierung in Logs, Fehlermeldungen und Hilfetexten ist verifiziert.
- Compare:
  - `schema compare --target db:trino://...` liefert `metadata_coverage`.
  - Transport-/URL-Randfälle:
    - `ssl=true` + `httpScheme=http` und `ssl=false` + `httpScheme=https` liefern
      `action_required`.
  - Kombinierte Security-Guards:
    - `--trino-runtime-profile=broken --allow-insecure-trino-transport DM_TRINO_ALLOW_INSECURE_TRANSPORT=true`
      führt zu `action_required`.
    - `--trino-runtime-profile=production --allow-insecure-trino-transport DM_TRINO_ALLOW_INSECURE_TRANSPORT=true` mit
      `trino://analyst@localhost:8080/iceberg/default?httpScheme=http`
      führt trotz Flags zu `action_required`.
  - Doppelte Query-Properties nach vollständiger Percent-Dekodierung führen reproduzierbar zu
    `action_required`.
  - Ungültige Percent-Dekodierung in `user`, `password`, `catalog`, `schema` oder
    Query-Keys/Values führt reproduzierbar zu `action_required`.
    - `--allow-insecure-trino-transport` ohne `DM_TRINO_ALLOW_INSECURE_TRANSPORT=true`
      bei `httpScheme=http` oder `ssl=false` führt zu `action_required`.
    - `DM_TRINO_ALLOW_INSECURE_TRANSPORT=true` ohne `--allow-insecure-trino-transport` bei unsicherer
      Transportausprägung führt zu `action_required`.
  - `--trino-session-allowlist` nutzt die Env-Quelle, wenn das Flag nicht explizit gesetzt ist.
  - `trino://user:@host:8080/cat/schema` ist ein explizit leeres URL-embedded Passwort und
    als `action_required` definiert.

### Phase 2 — Profiling- und Diagnosehärtung

- Stabile Profiling-Coverage und Diagnoseabdeckung.
- Trino-spezifische Hinweise/Warnungen im Compare-Pfad.
- Metadatenkonsistenz-Tests zwischen Connector-Typen (z. B. Hive vs. Iceberg).

### Phase 3 — Controlled `schema generate`

- `schema generate --target trino` nur explicit freigeschaltet.
- harte Guard-Grenzen mit klarer `action_required`-Ausgabe.

### Phase 4 — Writes per Capability-Matrix

Write-Pfade nur bei expliziter Fähigkeit je Connector:

- `supportsInsert`
- `supportsCreateTable`
- `supportsCreateTableAs`
- `supportsMerge`
- `supportsDelete`
- `supportsUpdate`
- `supportsTransactions`

## 7) Funktions-Matrix mit Risiko-Niveau

| Feature | Ziel-Fit | Begründung |
| --- | --- | --- |
| `schema reverse` | Hoch | starke Synergie mit Trino-Layer |
| `schema compare` | Hoch | hilfreich für Lakehouse-/Connector-Vergleiche |
| `data export` | Sehr hoch | skalierbare Leseauslastung |
| `data profile` | Sehr hoch | analytische Abdeckung |
| `data transfer` *(Source)* | Hoch | zentrale SQL-/Analyse-Schicht |
| `data transfer` *(Target)* | Gering | stark Connector-abhängig |
| `schema generate` | Mittel | nur explizit + starke Guarding |
| `data import` | Niedrig | kein Primärfall für Phase 1 |
| klassische OLTP-Migration | Sehr gering | Trino kein Migrations-Primärknoten |

## 8) CLI-Beispiele

Hinweise vorab:

- `schema compare` braucht den CLI-Operand-Prefix `db:` für Trino
  (siehe §5.2.1); andere Kommandos akzeptieren die rohe URL.
- Produktivbeispiele setzen einen HTTPS-Endpoint voraus (z. B. Port 8443
  hinter TLS-Termination). Der Default-Transport ist `ssl=true`/`https`.
- Lokale `localhost:8080`-Beispiele zeigen ausschließlich den
  `non_production`-Pfad mit aktiver Insecure-Transport-Signatur. Ohne diese
  Signatur lehnt der Adapter `httpScheme=http`/`ssl=false` ab.
- **Auth-Pflicht**: Trino-URLs ohne URL-embedded Passwort, ohne `accessToken`
  und ohne `DM_TRINO_PASSWORD` brechen mit `LOCAL_ERROR` (Exit 7) ab.
  Die folgenden Beispiele zeigen `DM_TRINO_PASSWORD` als Standard-Secret;
  in `non_production` ist `accessToken` als Query-Property mit
  Legacy-Geheimnis-Ausnahme erlaubt.

### 8.1 Produktive Setups (HTTPS-Default, DM_TRINO_PASSWORD)

```bash
export DM_TRINO_PASSWORD='…aus Vault/Pass-Manager…'

d-migrate schema reverse \
  --source trino://analyst@trino.internal:8443/iceberg/default \
  --output lakehouse.yaml

d-migrate schema compare \
  --source file:lakehouse.yaml \
  --target db:trino://analyst@trino.internal:8443/postgresql/public

d-migrate data export \
  --source trino://analyst@trino.internal:8443/iceberg/default \
  --tables orders,customers \
  --format csv

# ab Phase 1 Tranche 3 verfügbar (driver-trino-profiling fest verkabelt)
d-migrate data profile \
  --source trino://analyst@trino.internal:8443/hive/default \
  --tables orders,customers

d-migrate data transfer \
  --source trino://analyst@trino.internal:8443/iceberg/default \
  --target postgresql://app@db.internal:5432/app \
  --tables customers
```

### 8.2 Lokale Entwicklung (non_production, HTTP auf 8080)

```bash
# Komplette Insecure-Transport-Signatur + Auth-Secret erforderlich.
export DM_TRINO_PASSWORD='dev-secret'
export DM_TRINO_ALLOW_INSECURE_TRANSPORT=true

d-migrate schema reverse \
  --trino-runtime-profile=non_production \
  --allow-insecure-trino-transport \
  --source 'trino://analyst@localhost:8080/iceberg/default?httpScheme=http' \
  --output lakehouse.yaml

d-migrate schema compare \
  --trino-runtime-profile=non_production \
  --allow-insecure-trino-transport \
  --source file:lakehouse.yaml \
  --target 'db:trino://analyst@localhost:8080/postgresql/public?httpScheme=http'
```

Alternative in `non_production` mit `accessToken` statt `DM_TRINO_PASSWORD`
(setzt aktive Legacy-Geheimnis-Ausnahme voraus):

```bash
export DM_TRINO_ALLOW_INSECURE_TRANSPORT=true
export DM_TRINO_ALLOW_LEGACY_TRINO_SECRETS=true

d-migrate schema reverse \
  --trino-runtime-profile=non_production \
  --allow-insecure-trino-transport \
  --allow-legacy-trino-secrets \
  --source 'trino://analyst@localhost:8080/iceberg/default?httpScheme=http&accessToken=dev-jwt' \
  --output lakehouse.yaml
```

### 8.3 Bewusst blockierte Pfade (Phase 1)

```bash
# Auth-Pflicht verletzt → LOCAL_ERROR (Exit 7), unabhängig vom Profil
d-migrate schema reverse \
  --source trino://analyst@trino.internal:8443/iceberg/default \
  --output lakehouse.yaml
#   ↑ kein DM_TRINO_PASSWORD/accessToken gesetzt

# Vor Tranche-3-Auslieferung: data profile gegen Trino nicht verfügbar.
# Nach Tranche 3 ist das Profiling-Modul fest verkabelt — kein
# Modul-Opt-in/Off-Switch mehr, also entfällt dieser Blockierfall in der
# ausgelieferten Version.

# data transfer → trino (Sink) ist Source-only in Phase 1 → USAGE_ERROR (Exit 2)
d-migrate data transfer \
  --source postgresql://app@db.internal:5432/app \
  --target trino://analyst@trino.internal:8443/iceberg/default \
  --tables customers
```

Hinweise:

- `schema reverse`/`compare` liefern die Trino-Sicht des Zielsystems.
- `data transfer --target trino://...` ist in Phase 1 blockiert.
- Nicht unterstützte Objekte (Constraints/Indexes/Triggers/Procedures) werden
  als fehlende Sichtbarkeit explizit gekennzeichnet.

## 9) Risiken und Gegenmaßnahmen

### 9.1 Metadaten-Lücken

- Trino-Connectoren liefern teils unvollständige Metadaten.
- Gegenmaßnahme: sichtbare `action_required`-/Warnmeldungen statt stillen Fallbacks.

### 9.2 Schreibsemantik

- Connector-abhängige Schreibunterschiede sind nicht einheitlich.
- Gegenmaßnahme: keine generische Write-Freigabe, erst Capability-basierte Freigabe.

### 9.3 Erwartungsmanagement

- Risiko: Trino als „voller DB-Ersatz“ missverstanden.
- Gegenmaßnahme: klare, wiederholte Kommunikation in README/Specs/CLI-Hilfe.

### 9.4 Spezifikationsklarheit

- Risiko: unterschiedliche URL-/Capability-Definitionen in Specs und Implementierung.
- Gegenmaßnahme: ein verbindliches Modell (Canonical-URL, Source/Target-Regeln,
  Guard-Fehlerklassen).

### 9.5 Auth-Modell-Reichweite (Phase 1)

- Risiko: Phase 1 bietet ausschließlich URL-Passwort, `DM_TRINO_PASSWORD`
  und `accessToken` (JWT). Setups mit Kerberos, OAuth-Code-Flow oder
  externem Credential-Provider sind nicht unterstützt.
- Gegenmaßnahme: explizite, sichtbare Phase-1-Einschränkung im
  README/Adapter-Doc; Aufnahme erweiterter Auth-Modi in den Phase-2-Scope.

### 9.6 Session-Wert-Maskierung erschwert Diagnose

- Risiko: Pauschale `***`-Maskierung aller `session.<name>`-Werte (siehe
  §5.3) verbirgt unkritische Diagnose-Werte (z. B. `query_max_run_time`).
- Gegenmaßnahme: Phase 2 liefert klassifizierte Allowlist-Einträge
  (`name:public`/`name:secret`); in Phase 1 wird die Einschränkung in der
  CLI-Hilfe sichtbar gemacht.

## 10) Betroffene Artefakte

- `spec/architecture.md` (Adapterposition)
- `spec/cli-spec.md` (Source-/Target-Dialekt- und Capability-Doku)
- `spec/connection-config-spec.md` (URL-Form)
- `settings.gradle.kts` (Modulverkabelung)
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DatabaseDialect.kt`
  → Enum-Wert `TRINO` + `fromString`-Alias `trino`.
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionUrlParser.kt`
  → Trino-URL-Form (`trino://user@host:port/catalog/schema`); Query-Property-Allowlist.
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionConfig.kt`
  → optionales `dialectContext: DialectConnectionContext?`-Feld (sealed; siehe §4.1).
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionSecretMasker.kt`
  → `sensitiveQueryKeys` um Trino-Sensitive-Keys erweitern:
  `accessToken`, `trustStorePassword`, `keystorePassword`,
  `session.*`-Wertmaskierung (case-sensitive, Phase 1 pauschal).
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/JdbcUrlBuilder.kt`
  → API-Erweiterung: neue `JdbcConnectionSpec(jdbcUrl,
  driverProperties)`-Datenklasse + `buildConnectionSpec`-Methode auf
  `JdbcUrlBuilder` (Default-Bridge auf `buildJdbcUrl` für PG/MySQL/SQLite).
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/connection/HikariConnectionPoolFactory.kt`
  → ruft `buildConnectionSpec` statt `buildJdbcUrl`; reicht
  `driverProperties` per `HikariConfig.addDataSourceProperty(...)` an
  den Treiber durch. `username`/`password` werden nicht überschrieben,
  wenn die Properties sie bereits enthalten.

**Build-Fallen (exhaustive `when (dialect)` ohne `else`)** — beim
Hinzufügen von `DatabaseDialect.TRINO` brechen folgende Dateien, bis
der Trino-Zweig ergänzt ist. Tranche 1a muss **alle** abdecken:

- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DialectCapabilities.kt`
  → Read-only-Voreinstellung:
  - `supportsViews = true`, `supportsFunctions = false`,
    `supportsProcedures = false`, `supportsTriggers = false`,
    `supportsSequences = false`, `supportsCustomTypes = false`,
    `supportsPartitioning = false`, `supportsRoutineRewrite = false`,
    `supportsDisableFkChecks = false`, `supportsTriggerDisable = false`,
    `supportsTriggerStrict = false`, `supportsSchemaParameter = true`.
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/SqlIdentifiers.kt`
  (`when (dialect)` für Quoting-Regeln) → Trino verwendet
  doppelte Anführungszeichen wie ANSI (analog zu PostgreSQL).
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/connection/HikariConnectionPoolFactory.kt`
  → drei betroffene `when`-Stellen:
  1. `connectionInitSqlFor` (Statement-Timeout-Init-SQL): Trino → `null`
     (kein Init-SQL; Timeouts via `setQueryTimeout`).
  2. `FallbackJdbcUrlBuilder.defaultParams` → leerer Default für TRINO;
     der echte Trino-`JdbcUrlBuilder` aus `driver-trino` registriert sich
     selbst und übernimmt das Mapping (siehe §4.2). Der Fallback ist nur
     Sicherheitsnetz für Konfigurationen ohne registrierten Builder.
  3. `FallbackJdbcUrlBuilder.baseJdbcUrl` → `jdbc:trino://${host}:${port}/${catalog}/${schema}`.
     **Kein Default-Port**: Phase 1 macht `port` in der Trino-URL zur
     Pflicht (§5.2.1 / §5.4), darum darf weder Parser noch Fallback einen
     Port erfinden. Eine `ConnectionConfig` ohne `port` ist im
     Trino-Zweig technisch unerreichbar (URL-Parser bricht vorher mit
     `LOCAL_ERROR`); zur Defense-in-Depth wirft der Fallback-Zweig eine
     `IllegalArgumentException`, wenn `port == null` ist.
- `adapters:driven:driver-trino` (neu)
- `adapters:driven:driver-trino-profiling` (Phase-1-Pflicht; siehe §4)
- `hexagon`-Ports bei späteren Phasen (Capability-Guards)
- `docs/planning/in-progress/roadmap.md` (Trino als 1.x-Kandidat eintragen)
- ggf. User-Dokumentation

## 11) Akzeptanzkriterien (gesamt)

- `TRINO`-Dialekt und `trino://...` sind parsebar und dokumentiert.
- `schema reverse` gegen mindestens einen Trino-Katalog/Schema erfolgreich nutzbar.
- `schema compare` gegen `trino://...` mit klarer Diff-/Limit-/`metadata_coverage`-Dokumentation.
- `schema reverse` ohne gesetztes Profil nutzt implizit `production` als effektives Runtime-Profil.
- `data export` aus Trino stabil nutzbar.
- `data profile` liefert belastbare Kernkennzahlen ab Phase 1 Tranche 3
  (Profiling-Modul ist feste Treiber-Standardabhängigkeit).
- `data transfer` ist Phase 1 Source-only.
- `data transfer --target trino://...` blockiert reproduzierbar mit `USAGE_ERROR` (Exit 2).
- `schema generate --target trino` bleibt bis Phase 3 deaktiviert.
- `schema compare --source file:... --target db:trino://...` listet
  Connector-Grenzen explizit (`oid`/Constraints/Indexes/Procedures).
- `schema compare --source db:trino://... --target file:...` liefert dieselben
  `metadata_coverage`-/Grenzwarnungen und Dokumentationen konsistent zur
  Objektklasse.
- URL-Properties außerhalb der Allowlist liefern reproduzierbar `action_required`.
- Query-Property-Duplikate (nach vollständiger Percent-Dekodierung) liefern reproduzierbar
  `action_required`.
- Ungültige Percent-Dekodierung in URL-Teilen oder Query-Werten führt reproduzierbar
  `action_required`.
- `metadata_coverage=missing` oder unzulässig niedrige Qualität bricht den
  Vergleich standardmäßig mit `action_required`; mit `--allow-metadata-gaps`
  wird der Vergleich kontrolliert mit dokumentierter Risikoannahme fortgeführt.
- Secrets werden in allen Ausgaben maskiert und nicht persistiert/geloggt.
- Mindest-Coverage je Trino-Modul ≥ 90 % (`hexagon/ports-common`,
  `adapters:driven:driver-trino`, `adapters:driven:driver-trino-profiling`).
- Tranche-1-Split (1a/1b) ist in DoD verankert; Tranche 2 enthält einen
  Testcontainers-Smoke gegen `trinodb/trino` mit Default-Connector.

## 12) Empfehlung

Trino soll als **eigener Read/Analytics-/Federation-Adapter** behandelt werden.
Keine Vermischung mit klassischen OLTP-Migrationspfaden.

## Definition of Done

### DoD — Phase 1, Tranche 1a (Build-fähiges Minimum)

Ziel: `TRINO`-Dialekt + kanonisches URL-Parsing existieren, der Build bleibt
grün, und nicht-erlaubte Zielpfade brechen reproduzierbar ab. Diese Tranche
ist deutlich kleiner als das vollständige Security-Modell und entkoppelt
URL-Parsing/Capabilities von der Härtung in 1b.

- [ ] `DatabaseDialect.TRINO` vorhanden; `fromString("trino") → TRINO`.
- [ ] Alle exhaustive `when (dialect)`-Stellen in §10 sind um den
  TRINO-Zweig ergänzt; voller `./gradlew assemble` bleibt grün:
  - `DialectCapabilities.forDialect` (Read-only-Werte)
  - `SqlIdentifiers` (ANSI-Quoting)
  - `HikariConnectionPoolFactory.connectionInitSqlFor` (→ `null`)
  - `FallbackJdbcUrlBuilder.defaultParams` (→ leerer Default)
  - `FallbackJdbcUrlBuilder.baseJdbcUrl` (→ `jdbc:trino://...`; ohne
    Default-Port, wirft bei `port == null` `IllegalArgumentException`)
- [ ] `ConnectionConfig` um optionales `dialectContext: DialectConnectionContext?`
  erweitert; sealed `TrinoConnectionContext` mit `catalog`, normalisierter
  Property-Map, Session-Allowlist-Snapshot. `toString()` maskiert
  Trino-Secret-Felder mit `***` (siehe §4.1).
- [ ] `ConnectionUrlParser` akzeptiert `trino://user@host:port/catalog/schema`
  inkl. IPv6 in eckigen Klammern; `database` wird mit `schema` befüllt,
  `catalog` landet im Trino-Context.
- [ ] URL-Parsing ist deterministisch für `catalog`/`schema` (inkl.
  fehlendes/ungültiges Schema; `/` nach Dekodierung verboten).
- [ ] Trino-URL erlaubt nur die Phase-1-Property-Liste; nicht erlaubte
  Properties (`foo=bar`, einschließlich `requestTimeoutMs`) brechen
  reproduzierbar mit `LOCAL_ERROR` (Exit 7) ab.
- [ ] Test sichert ab, dass `requestTimeoutMs` als nicht erlaubte Property
  abgelehnt wird (Schutz gegen versehentliche Wieder-Einführung ohne
  Treiberversion-Pin).
- [ ] Transport-Konflikte werden hart blockiert:
  - `ssl=true` + `httpScheme=http`
  - `ssl=false` + `httpScheme=https`
- [ ] Doppelte Query-Properties werden nach vollständiger
  Percent-Dekodierung erkannt und mit `action_required` abgelehnt.
- [ ] Fehlerhafte Percent-Dekodierung in URL-Komponenten und Query-Werten
  liefert deterministisch `action_required`.
- [ ] `data transfer --target trino://...` bricht mit `USAGE_ERROR`
  (Exit 2) ab; Fehlermeldung folgt der Konvention
  `trino: USAGE_ERROR: ...` (rein Source-only).
- [ ] Source-only von `data profile` ist strukturell durchgesetzt: das
  Kommando hat per CLI-Spec keine `--target`-Option
  (`adapters/driving/cli/.../DataProfileCommand.kt`). Tranche-1a-DoD
  prüft, dass diese Eigenschaft per Test gegen die CLI-Definition
  reproduzierbar verifiziert wird (Snapshot-/CLI-Help-Test), damit eine
  spätere `--target`-Ergänzung nicht unbemerkt einen Trino-Schreibpfad
  öffnen kann.
- [ ] `ConnectionSecretMasker.sensitiveQueryKeys` ist um `accessToken`,
  `trustStorePassword`, `keystorePassword` (case-sensitive camelCase)
  erweitert. `session.*`-Werte werden im selben Pass maskiert.
- [ ] API-Erweiterung `JdbcConnectionSpec(jdbcUrl, driverProperties)`
  und `JdbcUrlBuilder.buildConnectionSpec` ist gemerged; bestehende
  PG-/MySQL-/SQLite-Builder bleiben über die Default-Bridge kompatibel.
- [ ] `HikariConnectionPoolFactory` reicht `driverProperties` per
  `addDataSourceProperty` durch; `username`/`password` werden nur
  gesetzt, wenn die Properties sie nicht bereits führen. Bestehende
  Tests gegen PG/MySQL/SQLite bleiben grün.
- [ ] Basis-Maskierung in Logs/Fehlern/Debug-Meldungen verifiziert (keine
  Klartext-Secrets in `toString()` von `ConnectionConfig`/Trino-Context).
- [ ] Mindest-Dokumentation ergänzt: `spec/cli-spec.md`,
  `spec/connection-config-spec.md` (URL-Form, Property-Allowlist,
  Source-only-Regel).

**Test-Coverage (1a):**

- [ ] `hexagon/ports-common` bleibt ≥ 90 % Coverage (Memory-Vorgabe).
- [ ] Eigene Testklasse pro Trino-Parser-Regel: kanonische URL, fehlendes
  Schema, IPv6, leere Pfadsegmente, nicht erlaubte Properties, doppelte
  Properties, ungültige Percent-Decodierung.

### DoD — Phase 1, Tranche 1b (Vollständiges Security-Modell)

Ziel: alle Runtime-/Profil-/Transport-/Secret-Guards sind aktiv und durch
Permutations-Tests abgesichert.

- [ ] `--trino-runtime-profile` und `DM_TRINO_RUNTIME_PROFILE` sind
  implementiert; Default = `production`. Ungültige Werte → `action_required`.
- [ ] In `production` sind `user:password`-URLs sowie `accessToken`,
  `trustStorePassword`, `keystorePassword` als Secret-Properties hart
  blockiert (`action_required`), unabhängig vom aktiven Modul.
- [ ] In `non_production` werden `user:password`, `accessToken`,
  `trustStorePassword`, `keystorePassword` als Übergangsmodus akzeptiert —
  nur bei effektivem `non_production`-Profil, aktivierter
  `allow-legacy-trino-secrets`-Ausnahme (CLI-Flag oder Env-Variante) und
  mit deterministisch maskierter Warnung.
- [ ] Legacy-Geheimnis-Ausnahme (`--allow-legacy-trino-secrets` oder
  `DM_TRINO_ALLOW_LEGACY_TRINO_SECRETS=true`) ist nur in `non_production`
  verfügbar; Präzedenz CLI > Env.
- [ ] Leeres URL-embedded Passwort (`trino://user:@host:...`) wird in allen
  Profilen reproduzierbar mit `action_required` abgelehnt.
- [ ] Leere Werte bei explizit gesetzten Secret-Parametern (`accessToken`,
  `trustStorePassword`, `keystorePassword`, `user:password`,
  `session.<name>`-Werte) führen in allen Profilen deterministisch zu
  `action_required`.
- [ ] `user` ohne URL-Embedded-Password und ohne `accessToken` erfordert
  `DM_TRINO_PASSWORD`; fehlend/leer → `action_required` in allen Profilen.
- [ ] Mit gesetztem `accessToken` (und nicht-blockierendem Profil) ist
  `DM_TRINO_PASSWORD` nicht erforderlich.
- [ ] Insecure-Transport-Ausnahme ist nur aktiv bei exakt:
  - effektives Profil `non_production`,
  - `--allow-insecure-trino-transport`,
  - `DM_TRINO_ALLOW_INSECURE_TRANSPORT=true`;
  in allen `production`-Fällen wird `httpScheme=http`/`ssl=false` mit
  `action_required` blockiert.
- [ ] `DM_TRINO_ALLOW_INSECURE_TRANSPORT` wird streng als String `true`
  validiert. Nicht-`true`-Werte sind bei Anfrage unsicherer
  Transportoptionen deterministisch mit `action_required` belegt.
- [ ] `--trino-session-allowlist` ist als Tri-State implementiert und folgt
  der Präzedenzregel (explizites CLI-Set > Env > leerer Default).
- [ ] `--trino-session-allowlist=""` wird als explizit gesetzter
  CLI-Override mit leerer Allowlist ausgewertet (ohne Env-Fallback).
- [ ] `DM_TRINO_SESSION_ALLOWLIST_V` ist bei aktivierter Env-Quelle
  versioniert:
  - Bei invaliden Env-Versionen wird `action_required` erzeugt.
  - Bei aktivem CLI-Override wird eine ungültige Env-Version nicht
    blockierend als Warnung dokumentiert und vollständig vom
    Env-Source-Parsing entkoppelt.
- [ ] `session.<name>` wird gegen den `v1`-Regex aus §5.2.4 geprüft; Werte
  außerhalb der Allowlist brechen mit `action_required`.
- [ ] Geheimnisse werden in Hilfetexten/Fehlern/diagnostischen Cache-IDs
  und Telemetrie deterministisch maskiert.
- [ ] Interne Runtime-Caches (Connection-/Metadaten-Cache) sind
  deterministisch gehashed (HMAC/SHA-256 mit `DM_TRINO_CACHE_SALT`) und
  enthalten keine Geheimnisse im Klartext.
- [ ] Fehlt `DM_TRINO_CACHE_SALT`, ist das ein deterministischer Guard-Fehler
  (`action_required`); kein implizit generierter Default-Salt.

**Test-Coverage (1b) — Guard-Permutationen:**

- [ ] Test-Matrix abgedeckt: `{production, non_production}` × `{ssl=true, ssl=false,
  unset}` × `{httpScheme=http, httpScheme=https, unset}` × `{insecure-Signatur
  vollständig, teilweise, fehlend}`. Jede Kombination liefert deterministisch
  `permit` oder `action_required`.
- [ ] Test-Matrix abgedeckt: `{production, non_production}` × `{URL-PW,
  DM_TRINO_PASSWORD, accessToken, keiner}` × `{Legacy-Ausnahme aktiv, inaktiv}`.
- [ ] Test-Matrix abgedeckt: `--trino-session-allowlist` Tri-State ×
  `DM_TRINO_SESSION_ALLOWLIST` × `DM_TRINO_SESSION_ALLOWLIST_V` × `session.<name>`
  Validität.
- [ ] `session.<name>`-Werte mit `;` oder `:` (auch percent-encoded:
  `%3B`/`%3A`/`%3b`/`%3a`) werden nach Decodierung reproduzierbar mit
  `LOCAL_ERROR` (Exit 7) abgelehnt. Test deckt sowohl Direkt- als auch
  Encoded-Varianten ab.
- [ ] Test verifiziert, dass die zusammengesetzte `jdbcUrl` keine
  Secret-Werte und keine Session-Werte enthält (`password`,
  `accessToken`, `SSLTrustStorePassword`, `SSLKeyStorePassword`,
  `sessionProperties=…`-Stream). Secrets und Session-Stream fließen
  ausschließlich über `java.util.Properties` an den Treiber.
- [ ] Modul-Coverage `hexagon/ports-common` bleibt ≥ 90 %.

### DoD — Phase 1, Tranche 2 (Read-Infrastruktur)

Ziel: Kernread-Funktionalität ist produktiv startfähig **und** gegen ein
echtes Trino verifiziert.
Voraussetzung: Tranche 1a und 1b vollständig abgeschlossen.

- [ ] `adapters:driven:driver-trino` in `settings.gradle.kts` aufgenommen.
- [ ] Trino-Connection-Factory/JDBC-Pool lauffähig.
- [ ] `TrinoSchemaReader`, `TrinoTableLister`, `TrinoDataReader` implementiert.
- [ ] `schema reverse --source trino://...` ist lauffähig.
- [ ] `data export --source trino://...` ist lauffähig.
- [ ] `data transfer` erkennt Source-only in der Trino-Runtime (kein
  erfolgreicher Zielausführungsweg Richtung `trino://...`).

**Test-Coverage (2):**

- [ ] `test:integration-trino` als Gradle-Sub-Projekt vorhanden.
- [ ] Testcontainers-basierter Smoke-Test gegen `trinodb/trino` mit
  mindestens einem Default-Connector (Vorschlag: in-Memory-Connector für
  Bootstrap, `iceberg` für die Compare-Baseline).
- [ ] Smoke-Test verifiziert:
  - kanonische URL parst, Connection-Pool öffnet sich,
  - `TrinoSchemaReader` listet Tabellen aus `system.runtime`/Test-Schema,
  - `TrinoDataReader` liefert deterministische Zeilen,
  - `data transfer --target trino://...` bricht weiterhin mit
    `action_required` ab.
- [ ] `adapters:driven:driver-trino` ≥ 90 % Coverage (Unit + Integration).

### DoD — Phase 1, Tranche 3 (Vergleich, Profiling, Guard-Robustheit)

Ziel: Qualitätsregeln und Fehlermeldungen sind für Produktivbetrieb stabil.
Voraussetzung: Tranche 2 vollständig abgeschlossen.

- [ ] `schema compare --source file:... --target db:trino://...` ist lauffähig.
- [ ] `schema compare --source file:... --target db:trino://...` veröffentlicht
  `metadata_coverage` nach Objektklasse.
- [ ] **Default-Connector-Coverage-Map** (`iceberg`, `v1`) ist im
  `driver-trino` mitgeliefert und greift, wenn `--target db:trino://.../iceberg/...`
  verwendet wird. Andere Connectoren liefern für nicht-Basis-Klassen
  `missing`.
- [ ] `schema compare` nutzt bei `metadata_coverage=missing` standardmäßig
  `action_required`; mit dokumentierter Risikoannahme optional via
  `--allow-metadata-gaps`.
- [ ] `schema compare --source db:trino://... --target file:...` liefert dieselben
  Coverage-/Warnungs- und Dokumentationsregeln konsistent.
- [ ] `data profile --source trino://...` ist lauffähig;
  `driver-trino-profiling` ist als feste Treiber-Standardabhängigkeit
  verkabelt (kein Modul-Opt-in, kein „Modul fehlt"-Zustand in der
  ausgelieferten Konfiguration).
- [ ] Verkabelungs-Test: bei aktivem `driver-trino` ist das
  Profiling-Modul automatisch resolved (Gradle-Dependency-Test gegen
  `settings.gradle.kts` und Modul-`build.gradle.kts`).
- [ ] Source-only-Regel für Trino ist technisch und dokumentiert durchgesetzt.
- [ ] `data transfer --target trino://...` liefert `USAGE_ERROR` (Exit 2)
  mit Konventions-Präfix.
- [ ] Keine generische Transferschreib-Route in Richtung Trino aktiv.
- [ ] `schema generate` und `data import` sind für TRINO in Phase 1 deaktiviert.
- [ ] Trino-Metadaten-Lücken/Unbekannte werden explizit als Warnungen (nicht
  als stiller Fallback) ausgegeben.

**Test-Coverage (3):**

- [ ] Compare-Tests gegen Testcontainers-Trino mit Iceberg-Connector:
  `full`-/`partial`-/`missing`-Pfade werden je Objektklasse durchlaufen.
- [ ] `adapters:driven:driver-trino-profiling` ≥ 90 % Coverage.

### DoD — Phase 2

- [ ] Trino-spezifische Profiling-Warn- und Coverage-Klassen dokumentiert.
- [ ] `driver-trino-profiling` ist seit Tranche 3 ausgeliefert; Phase 2
  ergänzt erweiterte Coverage-Klassen und Connector-Konsistenz-Tests
  (Hive vs. Iceberg).

### DoD — Phase 3

- [ ] `schema generate --target trino` ist nur explizit und begrenzt aktiv.
- [ ] `action_required` für nicht abbildbare Objekte konsistent dokumentiert.

### DoD — Phase 4

- [ ] `supports*`-Capability-Vertrag pro Connector definiert.
- [ ] Trino setzt Write-/Generate-/Transfer-Guards nach Capability-Vertrag um.
- [ ] Trino-Writepfade bleiben deaktiviert, solange Capability-Freigaben fehlen.
- [ ] Kein Produktivbetrieb mit stiller oder impliziter Schreibsemantik.
