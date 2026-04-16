# Connection- und Konfigurationsspezifikation: d-migrate

**Datenbankverbindungen, URL-Format und vollständiges Konfigurationsschema**

> Dokumenttyp: Spezifikation / Referenz

---

## 1. Connection-URL-Format

### 1.1 Allgemeine Syntax

d-migrate akzeptiert Datenbankverbindungen als URL:

```
<dialect>://[<user>[:<password>]@]<host>[:<port>]/<database>[?<params>]
```

Beispiele:
```
postgresql://admin:secret@localhost:5432/mydb
mysql://root@db.example.com/shop?ssl=true
sqlite:///path/to/database.db
sqlite::memory:
```

### 1.2 Dialekt-Aliase

| Eingabe | Kanonischer Dialekt | Beschreibung |
|---|---|---|
| `postgresql`, `postgres`, `pg` | `postgresql` | PostgreSQL |
| `mysql`, `maria`, `mariadb` | `mysql` | MySQL / MariaDB |
| `sqlite`, `sqlite3` | `sqlite` | SQLite |
| `mssql`, `sqlserver` | `mssql` | MS SQL Server (geplant) |
| `oracle`, `ora` | `oracle` | Oracle (geplant) |

Aliase werden beim Parsen sofort auf den kanonischen Wert normalisiert.

### 1.3 PostgreSQL

```
postgresql://[user[:password]@]host[:port]/database[?params]
```

| Parameter | Default | Beschreibung |
|---|---|---|
| Port | `5432` | TCP-Port |
| `sslmode` | `prefer` | SSL-Modus: `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full` |
| `sslrootcert` | | Pfad zum CA-Zertifikat |
| `currentSchema` | `public` | Standard-Schema |
| `connectTimeout` | `10` | Verbindungs-Timeout in Sekunden (JDBC-Konvention) |
| `socketTimeout` | `0` | Socket-Timeout in Sekunden (0 = unbegrenzt) |
| `applicationName` | `d-migrate` | Anwendungsname (sichtbar in `pg_stat_activity`) |

**JDBC-Mapping**: `postgresql://user:pass@host:5432/db?sslmode=require` → `jdbc:postgresql://host:5432/db?user=user&password=pass&sslmode=require`

### 1.4 MySQL

```
mysql://[user[:password]@]host[:port]/database[?params]
```

| Parameter | Default | Beschreibung |
|---|---|---|
| Port | `3306` | TCP-Port |
| `ssl` | `false` | SSL aktivieren |
| `sslMode` | `PREFERRED` | MySQL SSL-Modus (`DISABLED`, `PREFERRED`, `REQUIRED`, `VERIFY_CA`, `VERIFY_IDENTITY`) |
| `useUnicode` | `true` | Unicode-Unterstützung |
| `characterEncoding` | `utf8mb4` | Zeichensatz |
| `serverTimezone` | `UTC` | Server-Zeitzone |
| `connectTimeout` | `10000` | Verbindungs-Timeout in Millisekunden |
| `socketTimeout` | `0` | Socket-Timeout in Millisekunden |
| `allowPublicKeyRetrieval` | `false` | Public-Key-Retrieval erlauben |

**JDBC-Mapping**: `mysql://user:pass@host:3306/db` → `jdbc:mysql://host:3306/db?user=user&password=pass&useUnicode=true&characterEncoding=utf8mb4`

### 1.5 SQLite

```
sqlite:///<absolute-path>
sqlite://<relative-path>
sqlite::memory:
```

| Parameter | Default | Beschreibung |
|---|---|---|
| `mode` | `rwc` | Zugriffsmodus (`ro`, `rw`, `rwc`, `memory`) |
| `journal_mode` | `wal` | Journal-Modus (`wal`, `delete`, `truncate`, `off`) |
| `foreign_keys` | `true` | Foreign-Key-Enforcement (`true`, `false`) |
| `busy_timeout` | `5000` | Busy-Timeout in Millisekunden |
| `cache_size` | `-2000` | Cache-Größe in KiB (negativ) oder Pages (positiv) |

**JDBC-Mapping**: `sqlite:///path/to/db.sqlite` → `jdbc:sqlite:/path/to/db.sqlite?journal_mode=wal&foreign_keys=true`

SQLite-URLs mit `foreign_keys=true` als Default, weil d-migrate auf referenzielle Integrität angewiesen ist (in SQLite sind FKs standardmäßig deaktiviert).

### 1.6 Timeout-Einheiten

Die Timeout-Parameter in Connection-URLs verwenden die **native JDBC-Konvention** des jeweiligen Treibers. Diese ist nicht einheitlich:

| Dialekt | `connectTimeout`-Einheit | `socketTimeout`-Einheit |
|---|---|---|
| PostgreSQL | Sekunden | Sekunden |
| MySQL | Millisekunden | Millisekunden |
| SQLite | — | — (`busy_timeout` in ms) |

Im Gegensatz dazu verwenden alle Konfigurationswerte in `.d-migrate.yaml` (§3.2) konsistent **Millisekunden** (Suffix `_ms`). d-migrate konvertiert automatisch zwischen den Einheiten.

### 1.7 Sonderzeichen in Passwörtern

Passwörter mit Sonderzeichen müssen URL-encoded werden:

| Zeichen | Encoding |
|---|---|
| `@` | `%40` |
| `:` | `%3A` |
| `/` | `%2F` |
| `?` | `%3F` |
| `#` | `%23` |
| `%` | `%25` |

Beispiel: Passwort `p@ss:word` → `postgresql://admin:p%40ss%3Aword@localhost/mydb`

**Alternative**: Passwort weglassen und via Umgebungsvariable oder interaktive Eingabe bereitstellen.

---

## 2. Verbindungsaufbau

### 2.1 Ablauf

```
1. URL parsen → Dialekt, Host, Port, Credentials, Parameter
2. Dialekt-Alias normalisieren
3. JDBC-URL konstruieren
4. HikariCP Connection Pool konfigurieren
5. Verbindung testen (SELECT 1)
6. Bei Fehler: Aussagekräftige Fehlermeldung mit Hinweis
```

### 2.2 Connection-Pool-Defaults (HikariCP)

| Parameter | Default | Beschreibung |
|---|---|---|
| `maximumPoolSize` | `10` | Maximale Verbindungen |
| `minimumIdle` | `2` | Minimale Idle-Verbindungen |
| `connectionTimeout` | `10000` | Max. Wartezeit auf Verbindung (ms) |
| `idleTimeout` | `300000` | Max. Idle-Zeit (ms, 5 Min) |
| `maxLifetime` | `600000` | Max. Lebenszeit einer Verbindung (ms, 10 Min) |
| `keepaliveTime` | `60000` | Keepalive-Intervall (ms) |

Für SQLite: Pool-Size auf `1` (SQLite unterstützt keine parallelen Schreibzugriffe).

### 2.3 Fehlermeldungen

| Fehler | Meldung | Hinweis |
|---|---|---|
| Unbekannter Dialekt | `Unknown database dialect 'xyz'` | `Supported: postgresql, mysql, sqlite` |
| Verbindung verweigert | `Connection refused: host:port` | `Is the database running? Check host and port.` |
| Authentifizierung | `Authentication failed for user 'x'` | `Check credentials or use D_MIGRATE_DB_PASSWORD` |
| Datenbank existiert nicht | `Database 'x' does not exist` | `Create it first or check the database name.` |
| SSL-Fehler | `SSL connection failed` | `Check sslmode parameter or certificate path.` |
| Timeout | `Connection timed out after 10s` | `Increase connectTimeout or check network.` |

---

## 3. Konfigurationsdatei `.d-migrate.yaml`

### 3.1 Effektiver Konfigurationspfad

Die produktive CLI arbeitet heute mit genau einer effektiv aufgeloesten
Konfigurationsdatei, nicht mit einem Merge mehrerer Dateien.

Aufloesungsreihenfolge:

```
1. Datei aus --config
2. Pfad aus D_MIGRATE_CONFIG
3. Default ./.d-migrate.yaml
```

Diese Prioritaet gilt auch fuer spaetere `i18n.*`-Settings-Resolution.
Internationalisierung verwendet bewusst keinen eigenen Suchpfad neben der
bestehenden CLI-Konfiguration.

### 3.2 Vollständiges Schema

```yaml
# ════════════════════════════════════════════════
# d-migrate Konfiguration
# Alle Felder sind optional — Defaults sind angegeben
# ════════════════════════════════════════════════

# ── Datenbank-Verbindungen ─────────────────────
database:
  # Default-Verbindung (kann durch --source/--target überschrieben werden)
  default_source: null               # URL oder Name aus 'connections'
  default_target: null               # URL oder Name aus 'connections'

  # Benannte Verbindungen (Alias → URL)
  connections:
    local_pg: "postgresql://dev:dev@localhost:5432/myapp"
    local_mysql: "mysql://root@localhost:3306/myapp"
    staging: "postgresql://app:${DB_STAGING_PASSWORD}@staging.example.com/myapp?ssl=require"

  # Connection-Pool-Einstellungen
  pool:
    max_size: 10                     # Maximale Verbindungen
    min_idle: 2                      # Minimale Idle-Verbindungen
    connection_timeout_ms: 10000     # Verbindungs-Timeout
    idle_timeout_ms: 300000          # Idle-Timeout
    max_lifetime_ms: 600000          # Max. Lebenszeit

# ── Export-Einstellungen ───────────────────────
export:
  default_format: json               # json | yaml | csv
  encoding: utf-8                    # Ausgabe-Encoding
  csv:
    delimiter: ","                   # Spalten-Trennzeichen
    quote_char: "\""                 # Quoting-Zeichen
    escape_char: "\\"               # Escape-Zeichen
    write_bom: false                 # BOM am Dateianfang
    null_string: ""                  # Darstellung von NULL-Werten
    header: true                     # Spaltenüberschriften schreiben
    line_separator: "\n"             # Zeilentrenner

# ── Import-Einstellungen ──────────────────────
import:
  on_error: abort                    # abort | skip | log
  validate_before_import: true       # Daten gegen Schema validieren
  encoding_detection: true           # Automatische Encoding-Erkennung
  csv:
    auto_detect_delimiter: true      # Delimiter automatisch erkennen
    skip_empty_lines: true           # Leere Zeilen überspringen

# ── Pipeline-Einstellungen ─────────────────────
pipeline:
  chunk_size: 10000                  # Datensätze pro Chunk
  parallelism: auto                  # auto (= CPU-Kerne) oder Zahl
  checkpoint:
    enabled: true                    # Checkpoints erstellen
    # Row-basierter Trigger (`pipeline.checkpoint.interval`). 0.9.0 Phase B
    # (`docs/ImpPlan-0.9.0-B.md` §4.3) haelt den Key stabil und bildet ihn
    # intern auf `CheckpointConfig.rowInterval` ab. LN-012-Default: 10 000.
    interval: 10000
    # Zeit-basierter Trigger (`pipeline.checkpoint.max_interval`). Neuer
    # Key in 0.9.0 Phase B; intern `CheckpointConfig.maxInterval`. Wert ist
    # eine ISO-8601-Duration (z.B. `PT5M` = 5 Minuten). LN-012-Default: PT5M.
    max_interval: PT5M
    # Checkpoint-Verzeichnis. Wird vom CLI-Flag `--checkpoint-dir`
    # ueberschrieben, sobald es gesetzt ist (0.9.0 Phase A,
    # `docs/ImpPlan-0.9.0-A.md` §3.1/§4.3/§8.4). Die CLI-Spec
    # dokumentiert diese Prioritaet explizit.
    directory: ".d-migrate/checkpoints"
  retry:
    max_attempts: 3                  # Wiederholungsversuche
    initial_delay_ms: 1000           # Initialer Delay
    backoff_multiplier: 2.0          # Exponentieller Backoff-Faktor (Delay × Faktor^Versuch)

# ── Inkrementelle Migration ────────────────────
incremental:
  strategy: timestamp                # timestamp | id | hash | cdc
  timestamp_column: updated_at       # Spalte für timestamp-Strategie
  state_directory: ".d-migrate/sync" # Synchronisations-State

# ── KI-Integration ─────────────────────────────
ai:
  default_backend: ollama            # Default-Provider

  backends:
    ollama:
      base_url: "http://localhost:11434"
      model: "llama3.1:70b"
      timeout_s: 30
      temperature: 0.1
      max_tokens: 4096

    openai:
      api_key: "${OPENAI_API_KEY}"   # Umgebungsvariable
      model: "gpt-4o"
      timeout_s: 60
      temperature: 0.1
      max_tokens: 4096

    anthropic:
      api_key: "${ANTHROPIC_API_KEY}"
      model: "claude-sonnet-4-20250514"
      timeout_s: 60
      temperature: 0.1
      max_tokens: 4096

    xai:
      api_key: "${XAI_API_KEY}"
      model: "grok-2"
      timeout_s: 60

    google:
      api_key: "${GOOGLE_AI_API_KEY}"
      model: "gemini-pro"
      timeout_s: 60

    vllm:
      base_url: "http://localhost:8000"
      model: "codellama-34b"
      timeout_s: 30

    tgi:
      base_url: "http://localhost:8080"
      timeout_s: 30

  # Datenschutz
  privacy:
    prefer_local: true               # Lokale Modelle bevorzugen
    allow_external: false            # Externe APIs erlauben

  # Caching
  cache:
    enabled: true                    # Transformations-Cache
    directory: ".d-migrate/ai-cache"
    ttl_days: 30                     # Cache-Gültigkeit

  # Audit
  audit:
    enabled: true                    # KI-Audit-Trail
    directory: ".d-migrate/audit"

# ── Internationalisierung ──────────────────────
i18n:
  default_locale: en                 # Sprache für CLI-Ausgaben
  default_timezone: UTC              # Nur expliziter Zonierungs-Baustein (Phase E §4.4), kein Export-Default
  normalize_unicode: NFC             # Unicode-Normalisierung: NFC | NFD | NFKC | NFKD

# Vertragsregeln:
# - Root-/Fallback-Bundle bleibt Englisch (`messages.properties`)
# - dieselbe effektiv aufgeloeste Konfigurationsdatei gilt fuer `database.*` und `i18n.*`
# - `normalize_unicode` steuert Vergleichs-/Metadatenverhalten, nicht stille Nutzdatenmutation
# - JSON/YAML-Vertraege bleiben sprachstabil; lokalisiert werden nur Plain-Text-Ausgaben
# - `default_timezone` ist ein expliziter Konvertierungsbaustein (Phase E, §4.4):
#   sie greift nur dort, wo ein lokaler Wert bewusst in einen zonierten
#   Kontext ueberfuehrt werden soll, und loest keine blanket Umdeutung
#   vorhandener `LocalDateTime`-Werte aus. ISO-8601-Vertraege aus §4.1-§4.3
#   bleiben davon unberuehrt.

# ── DDL-Generierung ───────────────────────────
ddl:
  quote_identifiers: always          # always | reserved_only
  inline_foreign_keys: auto          # auto | always | never
  include_comments: true             # Header-Kommentar in DDL
  mysql:
    engine: InnoDB                   # Default Storage Engine
    charset: utf8mb4                 # Default Charset
    collation: utf8mb4_unicode_ci    # Default Collation
  sqlite:
    foreign_keys: true               # PRAGMA foreign_keys = ON
    journal_mode: wal                # WAL-Modus
  postgresql:
    default_schema: public           # Standard-Schema

# ── Dokumentationsgenerierung ──────────────────
documentation:
  enabled_formats:                   # Aktivierte Ausgabeformate
    - markdown
  include_er_diagrams: true          # ER-Diagramme generieren
  include_localized_labels: true     # Mehrsprachige Labels

# ── Logging ────────────────────────────────────
logging:
  level: info                        # error | warn | info | debug | trace
  format: plain                      # plain | json (für maschinelle Auswertung)
  file: null                         # Log-Datei (null = nur stderr)
  audit:
    enabled: true                    # Audit-Log für DB-Operationen
    file: ".d-migrate/audit.log"     # Audit-Log-Datei

# ── Sicherheit ─────────────────────────────────
security:
  credentials:
    store: env                       # env | encrypted_file | prompt
    encrypted_file: "~/.d-migrate/credentials.enc"
  roles:
    enabled: false                   # RBAC aktivieren
    current_role: admin              # Aktive Rolle: reader | writer | admin
```

### 3.3 Umgebungsvariablen in der Konfiguration

Werte mit `${VAR_NAME}` werden beim Laden durch Umgebungsvariablen ersetzt:

```yaml
database:
  connections:
    prod: "postgresql://app:${DB_PROD_PASSWORD}@prod.example.com/myapp"
ai:
  backends:
    openai:
      api_key: "${OPENAI_API_KEY}"
```

Regeln:
- Nicht gesetzte Variablen erzeugen `CONFIG_ERROR` (Exit-Code 7)
- Leere Variablen werden als leerer String behandelt
- Escaping: `$${VAR}` wird als literales `${VAR}` behandelt

### 3.4 Validierung

Die Konfiguration wird beim Start validiert:

| Regel | Fehler-Code |
|---|---|
| YAML-Syntax ungültig | E400 |
| Unbekannter Schlüssel (Tippfehler-Erkennung) | E401 (Warnung mit Suggestion) |
| Referenzierte Connection existiert nicht | E402 |
| Umgebungsvariable nicht gesetzt | E403 |
| Ungültiger Wert für Enum-Feld | E404 |
| Numerischer Wert außerhalb des erlaubten Bereichs | E405 |

---

## 4. Credential-Management

### 4.1 Priorität

Credentials werden in folgender Reihenfolge gesucht:

```
1. Connection-URL (inline): postgresql://user:password@host/db
2. Umgebungsvariable: D_MIGRATE_DB_PASSWORD
3. Konfigurationsdatei mit ${VAR}-Referenz
4. Encrypted Credentials File: ~/.d-migrate/credentials.enc
5. Interaktiver Prompt (nur wenn TTY)
```

### 4.2 Encrypted Credentials File

Credentials werden über das CLI-Kommando [`config credentials set`](./cli-spec.md#67-config) verwaltet:

```bash
# Credentials verschlüsseln
d-migrate config credentials set --name prod --user admin --password secret

# Gespeicherte Verbindungen anzeigen
d-migrate config credentials list

# Ergebnis: ~/.d-migrate/credentials.enc (AES-256 verschlüsselt)
# Master-Key: ~/.d-migrate/master.key (nur Benutzer-lesbar, chmod 600)
```

### 4.3 Sicherheitsregeln

- Passwörter werden **nie** in Logs geschrieben (maskiert als `***`)
- API-Keys werden **nie** in Logs geschrieben
- Connection-URLs in Logs maskieren das Passwort: `postgresql://admin:***@host/db`
- `.d-migrate/credentials.enc` muss in `.gitignore` stehen
- `.d-migrate/master.key` muss in `.gitignore` stehen
- Empfohlener `.gitignore`-Eintrag: `.d-migrate/` (schließt auch Checkpoints, Cache und Audit-Daten aus)

---

## 5. Profil-Unterstützung

Für verschiedene Umgebungen (dev, staging, prod) können Profile verwendet werden:

```yaml
# .d-migrate.yaml
database:
  connections:
    dev: "postgresql://dev:dev@localhost/myapp"
    staging: "postgresql://app:${STAGING_PW}@staging/myapp?ssl=require"
    prod: "postgresql://app:${PROD_PW}@prod/myapp?ssl=verify-full"

  default_source: dev    # Default für --source
  default_target: dev    # Default für --target
```

Verwendung:

```bash
# Default-Verbindung (dev)
d-migrate data export --format json

# Benannte Verbindung
d-migrate data export --source staging --format json

# Direkte URL (überschreibt alles)
d-migrate data export --source postgresql://other@host/db --format json
```

Wenn `--source` oder `--target` kein URL-Schema (`://`) enthält, wird der Wert als Verbindungsname in `database.connections` nachgeschlagen. Die Auflösung erfolgt **vor** der CLI-Validierung — für die nachgelagerte Verarbeitung sieht das System immer eine vollständige URL. Wird kein passender Verbindungsname gefunden, erzeugt das System Fehler E402.

**Wiederverwendung in 0.6.0-Kommandos**: Dieselbe Auflösung gilt für alle
Kommandos, die DB-Verbindungen akzeptieren — insbesondere auch für die in 0.6.0
neuen Pfade `schema reverse` (`--source`), `schema compare` (Operanden mit
`db:`-Präfix) und `data transfer` (`--source`, `--target`). Die
URL-/Alias-Semantik ist in dieser Spezifikation kanonisch; die
[CLI-Spezifikation](./cli-spec.md) beschreibt nur die kommandospezifische
Bedeutung der Flags.

---

## Verwandte Dokumentation

- [CLI-Spezifikation](./cli-spec.md) — Kommandos, Flags, Exit-Codes
- [Design](./design.md) — Konfigurationshierarchie §5.3, KI-Datenschutz §4.3
- [Architektur](./architecture.md) — HikariCP §5.2, DmigrateConfig §4.1, Sicherheit §4.3
- [Lastenheft](./lastenheft-d-migrate.md) — LN-025 (Credentials), LN-026 (Verschlüsselung), LN-032 (KI-Datenschutz)

---

**Version**: 1.1
**Stand**: 2026-04-13
**Status**: Entwurf — explizite Wiederverwendung für 0.6.0-Kommandos (reverse, compare, transfer) dokumentiert
