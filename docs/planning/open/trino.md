# Plan: Trino-Support in d-migrate (Read-first Federation-Adapter)

> Dokumenttyp: Architektur- und Umsetzungsplan  
> Status: Entwurf (2026-05-15)  
> Referenzen: `spec/architecture.md`, `spec/cli-spec.md`, `spec/connection-config-spec.md`, `docs/planning/roadmap.md`

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
  - `data profile` (nur mit aktivem Profiling-Modul)
  - `data transfer` **nur Source**

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

Optionale Erweiterungen:

- `adapters:driven:driver-trino-profiling` (Feature-Flag in Phase 1 optional,
  in Phase 2 standardmäßig aktiv)
- `test:integration-trino` (später)

## 5) Kontrakt: Dialekt und Connection-URL

### 5.1 Dialekt

- Erweiterung `DatabaseDialect` um `TRINO`.
- Alias-Mapping: `trino -> TRINO`.

### 5.2 URL-Modell

Kanonische Form:

```text
trino://user@host:port/catalog/schema
```

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
  - `requestTimeoutMs` (positive Ganzzahl, ms)
  - `session.<name>` (Session-Property-Forwarding; Name muss in der aktivierten
    Allowlist enthalten sein und dort nach Trino-Phase-1-Schema verarbeitet werden)
  - `accessToken`
  - `trustStorePath`
  - `trustStorePassword`
  - `keystorePath`
  - `keystorePassword`
  - Sicherheitsklassifikation nach Parsing (Phase 1):
  - Nicht-sensitive Parser-Properties: `ssl`, `httpScheme`, `requestTimeoutMs`,
    `trustStorePath`, `keystorePath`, `session.<name>`.
  - Sensitive Secrets: `user:password` (Authority), `accessToken`,
    `trustStorePassword`, `keystorePassword`.
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
- URL-Kodierung und Dekodierungsreihenfolge:
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
    - `session.<name>` wird zusätzlich wie in Abschnitt *Session-Forwarding-Liste* normalisiert
      (Lowercase, Musterprüfung, Name-Auflösung).
    - Andere Property-Schlüssel (`ssl`, `httpScheme`, `requestTimeoutMs`, `accessToken`,
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
- Doppelte Query-Properties (inklusive doppelter `session.<name>`-Einträge) sind in
  Phase 1 unzulässig und führen zu `action_required`.
- Der Duplikatsvergleich erfolgt nach vollständiger Percent-Decodierung der Keys/Values.
  - Kodierungsvarianten derselben Bedeutung gelten als identischer Key (z. B. unterschiedliche
    Encodings desselben `session.<name>`-Schlüssels).
- Ausführungskontext (für Sicherheitsregeln):
  - `DM_TRINO_RUNTIME_PROFILE` (Umgebungsvariable) oder `--trino-runtime-profile=<production|non_production>`
    bestimmen, ob produktive Regeln gelten.
  - Präzedenz: CLI-Flag > Umgebungsvariable > Default `production`.
  - Unterstützte Werte sind ausschließlich `production` und `non_production`.
  - Alle anderen Angaben (`DM_TRINO_RUNTIME_PROFILE` oder CLI-Flag) führen zu
    `action_required`.
  - In `production` ist die sichere Transportpolicy hart: `httpScheme=http` und/oder `ssl=false`
    sind nicht zulässig.
  - `non_production` erlaubt unsichere Transportoptionen nur bei aktivierter
    Entwickler-Ausnahme (siehe Sicherheitsregel).
  - Transportauflösung (Phase 1):
    1. Gültige Werte nach Decodierung:
       - `ssl`: `true|false` (default: `true`)
       - `httpScheme`: `http|https` (default: `https`)
    2. Wenn `ssl` gesetzt ist:
       - `ssl=true` ist nur zulässig mit `httpScheme`-Wert leer oder `https`.
       - `ssl=false` ist nur zulässig mit `httpScheme`-Wert leer oder `http`.
    3. Wenn `ssl` nicht gesetzt ist, definiert `httpScheme` die Sicherheit:
       - `https` -> TLS
       - `http` -> non-TLS
    4. Widersprüchliche Kombinationen sind harte Fehler (`action_required`), unabhängig
       vom Runtime-Profil:
       - `ssl=true` + `httpScheme=http`
       - `ssl=false` + `httpScheme=https`
    5. Die resultierende Transportwahl (`https`/`http`) wird anschließend mit den
       Produktions-/Nicht-Produktions-Guards geprüft.
  - Legacy-Geheimnis-Ausnahme für `non_production`:
  - `--allow-legacy-trino-secrets` (CLI-Flag) oder
      `DM_TRINO_ALLOW_LEGACY_TRINO_SECRETS=true` (Env-Variable, exakt).
    - Präzedenz: CLI-Flag > Umgebungsvariable.
    - Werte ungleich `true` (inkl. leerer String) werden als inaktiv gewertet.
    - Nur gültig für Sensitive-Parameter und URL-Embedded Secrets in `non_production`.
- Sicherheitsregel:
  - Effektiver Runtime-Profilwert wird aufgelöst als:
    `--trino-runtime-profile` > `DM_TRINO_RUNTIME_PROFILE` > `production`.
  - `insecure_transport` wird nur aktiv, wenn **alle** Bedingungen erfüllt sind:
    - Effektiver Runtime-Profilwert ist `non_production`,
    - `--allow-insecure-trino-transport` ist gesetzt (CLI-Flag explizit),
    - `DM_TRINO_ALLOW_INSECURE_TRANSPORT=true`.
    - `DM_TRINO_ALLOW_INSECURE_TRANSPORT` ist ausschließlich auf `true` aktiv.
      Jede andere Wertangabe (`false`, leer, andere Schreibweise, andere Tokens) führt bei
      angeforderter unsicherer Transportkonfiguration zu hartem `action_required`.
  - `httpScheme=http` oder `ssl=false` ist nur bei aktivem `insecure_transport` erlaubt.
    Ohne diese Freigabe erfolgt `action_required`.
  - Bei teilweiser oder fehlender Freigabe erfolgt `action_required` inkl. Diagnose der
    fehlenden Signaturbestandteile (`--trino-runtime-profile`, `--allow-insecure-trino-transport`,
    `DM_TRINO_ALLOW_INSECURE_TRANSPORT`).
  - Die Ausführung wird bei aktivem `insecure_transport` als `insecure_transport=true` markiert.
- Format ist absichtlich ohne `db:`-Prefix.

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
- Normalisierung:
  - Whitespace wird getrimmt, leere Tokens verworfen, Kleinbuchstaben erzwungen.
  - Reihenfolge wird deterministisch sortiert und Duplikate dedupliziert.
  - Jeder Eintrag muss das Muster `^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*$` erfüllen.
  - Dotted Session-Keys sind erlaubt (z. B. `hive.s3_staging_directory`), sofern das Muster passt.
- Versionierung:
  - `DM_TRINO_SESSION_ALLOWLIST_V` ist optionaler Versionsmarker.
  - Unterstützte Werte: `v1` (Default bei fehlender Angabe).
  - Nicht parsebare / nicht unterstützte Versionen führen zu `action_required`, wenn
    `DM_TRINO_SESSION_ALLOWLIST` als aktive Quelle verwendet wird.
  - Bei aktivem CLI-Override gilt die Versionslogik nur für `DM_TRINO_SESSION_ALLOWLIST_V`;
    ungültige Werte sind dann nicht blockierend, da die CLI-Quelle die Env-Quelle übersteuert.
  - Bei aktivem CLI-Override wird ein ungültiger `DM_TRINO_SESSION_ALLOWLIST_V` dokumentiert
    (Warnung), und die gesamte Env-Quelle inkl. Versionsmarker gilt als nicht aktiv.
  - CLI-Allowlist wird immer mit der v1-Schema-Logik geparst; ein separater
    Versionsschalter ist für CLI nicht vorgesehen.
  - Für `v1` gilt ausschließlich das oben definierte normalisierte CSV-Format.

Credential-Modell (Phase 1):

- Basisform: `trino://user[:password]@host:port/catalog/schema`.
- Optional/empfohlen: Passwort via Umgebungsvariable (z. B. `DM_TRINO_PASSWORD`) oder
  späterer Credential-Provider.
- `userinfo`-Parsing (Phase 1):
  - Grammatik nach Decodierung: `userinfo = user [ ":" password ]`
  - `user` muss vorhanden und nicht leer sein.
  - Bei vorhandenem `:` gilt `password` als URL-embedded Secret.
    - Leeres Passwort (`user:` oder Äquivalent nach Decodierung) bleibt ein expliziter
      Secret-Wert und ist strikt zu behandeln.
    - URL-embedded Secret (inkl. leer) ist in `production` hart blockiert.
    - In `non_production` ist URL-embedded Secret nur mit aktivierter
      Legacy-Geheimnis-Ausnahme erlaubt.
    - Leeres URL-embedded Passwort (`user:`) ist auch in `non_production` nicht zulässig
      (`action_required`).
  - Nur wenn kein `:` vorhanden ist (`user` ohne Passwort), darf `DM_TRINO_PASSWORD` als
    Fallback für das Passwort genutzt werden. Ist `DM_TRINO_PASSWORD` nicht gesetzt oder leer,
    ist dies ein deterministischer Guard-Fehler (`action_required`) in allen Profilen.
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

#### 5.3 Security, Secrets und Maskierung

- Security-Matrix (Phase 1):
  - `production`: URL-Embedding und Sensitive-Parameter in URL/Query sind hart blockiert.
  - `non_production`: Sensitive-Parameter möglich als Übergangsfälle.
    - Erlaubt ist ausschließlich, wenn das effektive Runtime-Profil `non_production`
      aufgelöst wurde **und** die Legacy-Geheimnis-Ausnahme aktiv ist.
    - Legacy-Geheimnis-Ausnahme ist:
      - `--allow-legacy-trino-secrets` (CLI-Flag) oder
      - `DM_TRINO_ALLOW_LEGACY_TRINO_SECRETS=true` (Umgebungsvariable, exakt).
      - Alle anderen Werte (`false`, leer, andere Schreibweise, andere Tokens) aktivieren
        die Ausnahme nicht.
      - Präzedenz: CLI-Flag > Umgebungsvariable.
    - In allen Legacy-Secret-Fällen ist ein klarer, maskierter Warnhinweis erforderlich.
    - `session.<name>` bleibt in beiden Profilen erlaubt, sofern die Allowlist-Regel
      greift; Werte werden als Secret behandelt und maskiert.
  - Schwere Secret-Parameter:
    - `user:password`
    - `accessToken`
    - `trustStorePassword`
    - `keystorePassword`
  - Nicht-sensible Konfigurationsfelder:
    - `trustStorePath`
    - `keystorePath`
    bleiben als Pfad-/Dateiangaben nutzbar.

- In produktiven Setups sind URL-Embedding und sensible URL-/Query-Secret-Parameter
  (`user:password`, `accessToken`, `trustStorePassword`, `keystorePassword`)
  als `action_required` hart blockiert.
- In neuen Setups muss ein Umgebungs-Secret (`DM_TRINO_PASSWORD`) oder späterer
  Credential-Provider genutzt werden.
- In Phase 1 gilt ergänzend:
  - `accessToken`, `trustStorePassword`, `keystorePassword` sind als Übergangs-Optionen
    nur mit aktiver Legacy-Geheimnis-Ausnahme nutzbar; in `production` weiterhin hart
    blockiert.
  - `session.<name>` ist erlaubt, kann aber Secret-Werte tragen und ist wie Secret zu
    behandeln.
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
  - In `production` ist dies immer hart blockiert.
  - In `non_production` gilt dieselbe Blockade, außer bei exakt aktivierter
    Entwickler-Ausnahme (Dreifach-Signatur, siehe oben).
- Ungültige Angaben zu `DM_TRINO_RUNTIME_PROFILE` oder `--trino-runtime-profile` -> `action_required`.
- Nicht unterstützte URL-Properties -> sofortiger Abbruch via `action_required`.
- Doppelte Query-Properties -> sofortiger Abbruch via `action_required`.
- Trino ist in Phase 1 ein **write-freier** Adapter; alle write-Pfade sind für
  Target/Sink gesperrt. `schema compare --target trino://...` bleibt als
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
- `oid`
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

### 5.6 Capability-Governance für Trino

| Befehl | Source | Target | Phase |
| --- | --- | --- | --- |
| `schema reverse` | ✅ | ❌ | 1 |
| `schema compare` | ✅ | ✅ *(read-only Diff-Pfad)* | 1 |
| `data export` | ✅ | ❌ | 1 |
| `data profile` | ✅ | ❌ *(nur mit Profiling-Modul)* | 1 |
| `data transfer` | ✅ | ❌ | 1 |
| `schema generate` | ❌ | ⚠️ (explizit freigegeben) | 3 |
| `data import` | ❌ | ❌ | 4+ |

Regel:

- `Target` für Trino ist in Phase 1 standardmäßig gesperrt.
- `schema compare --target trino://...` bleibt erlaubt, weil semantisch read-only.
- Write-/Generate-Funktionen erfordern immer einen expliziten Capability-Review je
  Connector.

## 6) Umsetzungsphasen

### Phase 1 — Read-only MVP

**Ziel:** sicherer Trino-Lesepfad ohne Schreib-Risiko.

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
   - `data profile` (nur mit aktivem `driver-trino-profiling`)
   - `data transfer` mit Source-only-Guard

Validierungsregeln:

- `schema reverse --source trino://... --output ...` ist lauffähig.
- `data transfer --target trino://...` startet nicht.
- `data profile --source trino://...` ist nur mit aktivem Profiling-Modul möglich.
- `data profile --source trino://...` ohne Modul endet mit `action_required` + Hinweis.
- Nicht erlaubte Query-Properties liefern reproduzierbar `action_required`.
- `schema compare --target trino://...` dokumentiert `metadata_coverage` pro
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
  - `schema compare --target trino://...` liefert `metadata_coverage`.
- Transport-/URL-Randfälle:
  - `ssl=true` + `httpScheme=http` und `ssl=false` + `httpScheme=https` liefern
    `action_required`.
  - `--trino-session-allowlist` nutzt die Env-Quelle, wenn das Flag nicht explizit gesetzt ist.
  - `trino://user:@host:8080/cat/schema` ist ein explizit leeres URL-embedded Passwort und
    als `action_required` definiert.

### Phase 2 — Profiling- und Diagnosehärtung

- Stabile Profiling-Coverage und Diagnoseabdeckung.
- Trino-spezifische Hinweise/Warnungen im Compare-Pfad.
- Metadatenkonsistenz-Tests zwischen Connector-Typen (z. B. Hive vs. Iceberg).

### Phase 3 — Controlled `schema generate`

- `schema generate --target trino://...` nur explicit freigeschaltet.
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

```bash
d-migrate schema reverse \
  --source trino://analyst@localhost:8080/iceberg/default \
  --output lakehouse.yaml

d-migrate schema compare \
  --source file:lakehouse.yaml \
  --target trino://analyst@localhost:8080/postgresql/public

d-migrate data export \
  --source trino://analyst@localhost:8080/iceberg/default \
  --tables orders,customers \
  --format csv

# mit aktivem driver-trino-profiling
d-migrate data profile \
  --source trino://analyst@localhost:8080/hive/default \
  --tables orders,customers

# ohne Profiling-Modul in Phase 1 (blockiert)
d-migrate data profile \
  --source trino://analyst@localhost:8080/hive/default \
  --tables orders,customers

d-migrate data transfer \
  --source trino://analyst@localhost:8080/iceberg/default \
  --target postgresql://app@localhost:5432/app \
  --tables customers

# Wird in Phase 1 geblockt
d-migrate data transfer \
  --source postgresql://app@localhost:5432/app \
  --target trino://analyst@localhost:8080/iceberg/default \
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

## 10) Betroffene Artefakte

- `spec/architecture.md` (Adapterposition)
- `spec/cli-spec.md` (Source-/Target-Dialekt- und Capability-Doku)
- `spec/connection-config-spec.md` (URL-Form)
- `settings.gradle.kts` (Modulverkabelung)
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DatabaseDialect.kt`
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionUrlParser.kt`
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DialectCapabilities.kt`
- `adapters:driven:driver-trino` (neu)
- `adapters:driven:driver-trino-profiling` (falls `data profile` in Phase 1)
- `hexagon`-Ports bei späteren Phasen (Capability-Guards)
- `docs/planning/roadmap.md`
- ggf. User-Dokumentation

## 11) Akzeptanzkriterien (gesamt)

- `TRINO`-Dialekt und `trino://...` sind parsebar und dokumentiert.
- `schema reverse` gegen mindestens einen Trino-Katalog/Schema erfolgreich nutzbar.
- `schema compare` gegen `trino://...` mit klarer Diff-/Limit-/`metadata_coverage`-Dokumentation.
- `data export` aus Trino stabil nutzbar.
- `data profile` liefert belastbare Kernkennzahlen (mit Profiling-Modul in Phase 1).
- `data transfer` ist Phase 1 Source-only.
- `data transfer --target trino://...` blockiert reproduzierbar mit `action_required`.
- `schema generate --target trino://...` bleibt bis Phase 3 deaktiviert.
- `schema compare --source file... --target trino://...` listet
  Connector-Grenzen explizit (`oid`/Constraints/Indexes/Procedures).
- `schema compare --source trino://... --target file...` liefert dieselben
  `metadata_coverage`-/Grenzwarnungen und Dokumentationen konsistent zur
  Objektklasse.
- URL-Properties außerhalb der Allowlist liefern reproduzierbar `action_required`.
- `metadata_coverage=missing` oder unzulässig niedrige Qualität bricht den
  Vergleich standardmäßig mit `action_required`; mit `--allow-metadata-gaps`
  wird der Vergleich kontrolliert mit dokumentierter Risikoannahme fortgeführt.
- Secrets werden in allen Ausgaben maskiert und nicht persistiert/geloggt.

## 12) Empfehlung

Trino soll als **eigener Read/Analytics-/Federation-Adapter** behandelt werden.
Keine Vermischung mit klassischen OLTP-Migrationspfaden.

## Definition of Done

### DoD — Phase 1, Tranche 1 (Basiskontrakt, URL-Parsing, Security)

Ziel: `trino://...` ist vollständig verifiziert und sicher genug, um Trino-Infrastruktur zu bauen.
Voraussetzung für Phase 1, Tranche 2.

- [ ] `DatabaseDialect.TRINO` vorhanden.
- [ ] Alias `trino` in Dialektauflösung und URL-Parsing verankert.
- [ ] URL-Parsing ist deterministisch für `catalog`/`schema` (inkl. fehlendes/ungültiges Schema).
- [ ] Trino-URL erlaubt nur die Phase-1-Property-Liste; nicht erlaubte Properties
  (`foo=bar`) brechen reproduzierbar mit `action_required` ab.
- [ ] Transport-Konflikte werden hart blockiert:
  - `ssl=true` + `httpScheme=http`
  - `ssl=false` + `httpScheme=https`
- [ ] `--trino-session-allowlist` ist als Tri-State implementiert und folgt der Präzedenzregel
  (explizites CLI-Set > Env > leerer Default).
- [ ] Leeres URL-embedded Passwort (`trino://user:@host:...`) wird in allen Profilen
  reproduzierbar mit `action_required` abgelehnt.
- [ ] `data transfer --target trino://...` bricht in Tranche 1 mit klarer
  Guard-Fehlermeldung ab (rein Source-only).
- [ ] Keine Secret-Ausgaben in Logs/Fehlern/Debug-Meldungen.
- [ ] Ungültige Runtime-Profile (`DM_TRINO_RUNTIME_PROFILE` / `--trino-runtime-profile`) führen
  in allen Pfaden zu `action_required`.
- [ ] In `production` sind `user:password`-URLs sowie `accessToken`, `trustStorePassword`,
  `keystorePassword` als Secret-Properties hart blockiert (`action_required`), unabhängig vom
  aktiven Modul.
- [ ] Legacy-Geheimnis-Ausnahme (`--allow-legacy-trino-secrets` oder
  `DM_TRINO_ALLOW_LEGACY_TRINO_SECRETS=true`) ist in Phase 1 nur in `non_production`
  verfügbar und erforderlich, damit Sensitive URL-/Query-Parameter in diesen Modus gelangen.
- [ ] `user` ohne URL-Embedded-Password erfordert bei fehlendem `DM_TRINO_PASSWORD` in allen
  Profilen deterministisch `action_required`.
- [ ] In `non_production` werden `user:password`, `accessToken`, `trustStorePassword`,
  `keystorePassword` als Übergangsmodus akzeptiert, nur bei effektivem
  `non_production`-Profil, aktivierter `allow-legacy-trino-secrets`-Ausnahme
  (entweder CLI-Flag oder Env-Variante) und mit deterministisch maskierter Warnung.
- [ ] Geheimnisse werden in Hilfetexten/Fehlern/diagnostischen Cache-IDs und Telemetrie
  deterministisch maskiert.
- [ ] Interne Runtime-Caches (Connection-/Metadaten-Cache) sind deterministisch gehashed
  (z. B. HMAC/SHA-256 mit `DM_TRINO_CACHE_SALT`) und enthalten keine Geheimnisse im Klartext.
- [ ] Insecure-Transport-Ausnahme ist nur aktiv bei exakt:
  - `DM_TRINO_RUNTIME_PROFILE=non_production` oder `--trino-runtime-profile=non_production`
    (Vorrang: CLI-Flag > Env),
  - `--allow-insecure-trino-transport`,
  - `DM_TRINO_ALLOW_INSECURE_TRANSPORT=true`;
  in allen `production`-Fällen wird `httpScheme=http`/`ssl=false` mit `action_required` blockiert.
- [ ] `DM_TRINO_ALLOW_INSECURE_TRANSPORT` wird streng als String `true` validiert.
  Nicht-`true`-Werte sind bei Anfrage unsicherer Transportoptionen deterministisch mit
  `action_required` belegt.
- [ ] Mindest-Dokumentation ergänzt: `spec/cli-spec.md`,
  `spec/connection-config-spec.md`.

### DoD — Phase 1, Tranche 2 (Read-Infrastruktur)

Ziel: Kernread-Funktionalität ist produktiv startfähig.
Voraussetzung: Tranche 1 vollständig abgeschlossen.

- [ ] `adapters:driven:driver-trino` in `settings.gradle.kts` aufgenommen.
- [ ] Trino-Connection-Factory/JDBC-Pool lauffähig.
- [ ] `TrinoSchemaReader`, `TrinoTableLister`, `TrinoDataReader` implementiert.
- [ ] `schema reverse --source trino://...` ist lauffähig.
- [ ] `data export --source trino://...` ist lauffähig.
- [ ] `data transfer` erkennt Source-only in der Trino-Runtime (kein erfolgreicher
  Zielausführungsweg Richtung `trino://...`).

### DoD — Phase 1, Tranche 3 (Vergleich, Profiling, Guard-Robustheit)

Ziel: Qualitätsregeln und Fehlermeldungen sind für Produktivbetrieb stabil.
Voraussetzung: Tranche 2 vollständig abgeschlossen.

- [ ] `schema compare --source file... --target trino://...` ist lauffähig.
- [ ] `schema compare --source file... --target trino://...` veröffentlicht
  `metadata_coverage` nach Objektklasse.
- [ ] `schema compare` nutzt bei `metadata_coverage=missing` standardmäßig
  `action_required`; mit dokumentierter Risikoannahme optional via
  `--allow-metadata-gaps`.
- [ ] `schema compare --source trino://... --target file...` liefert dieselben
  Coverage-/Warnungs- und Dokumentationsregeln konsistent.
- [ ] `data profile --source trino://...` ist lauffähig (mit Profiling-Modul).
- [ ] `data profile --source trino://...` ohne Modul liefert `action_required` und
  klare Anleitung.
- [ ] Source-only-Regel für Trino ist technisch und dokumentiert durchgesetzt.
- [ ] `data transfer --target trino://...` liefert klare Fehlerklasse `action_required`.
- [ ] Keine generische Transferschreib-Route in Richtung Trino aktiv.
- [ ] `schema generate` und `data import` sind für TRINO in Phase 1 deaktiviert.
- [ ] Trino-Metadaten-Lücken/Unbekannte werden explizit als Warnungen (nicht als stiller Fallback) ausgegeben.

### DoD — Phase 2

- [ ] Trino-spezifische Profiling-Warn- und Coverage-Klassen dokumentiert.
- [ ] Optionales Profiling-Modul `driver-trino-profiling` verfügbar oder klare
  Begrenzung dokumentiert.

### DoD — Phase 3

- [ ] `schema generate --target trino://...` ist nur explizit und begrenzt aktiv.
- [ ] `action_required` für nicht abbildbare Objekte konsistent dokumentiert.

### DoD — Phase 4

- [ ] `supports*`-Capability-Vertrag pro Connector definiert.
- [ ] Trino setzt Write-/Generate-/Transfer-Guards nach Capability-Vertrag um.
- [ ] Trino-Writepfade bleiben deaktiviert, solange Capability-Freigaben fehlen.
- [ ] Kein Produktivbetrieb mit stiller oder impliziter Schreibsemantik.
