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
  - `data profile` (nur mit aktivem Profiling-Modul, **Source-only**)
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

Optionale Erweiterungen:

- `adapters:driven:driver-trino-profiling` (Feature-Flag in Phase 1 optional,
  in Phase 2 standardmäßig aktiv)
- `test:integration-trino` (später)

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
  - `requestTimeoutMs` (positive Ganzzahl, ms; Bereich `1..2_147_483_647`)
    - Muss als Ganzzahl parsebar sein.
    - `0`, negative, nicht-numerische Werte oder Überläufe außerhalb des Bereichs
      werden hart mit `action_required` abgewehrt.
  - `session.<name>` (Session-Property-Forwarding; Name muss in der aktivierten
    Allowlist enthalten sein und dort nach Trino-Phase-1-Schema verarbeitet werden)
  - `accessToken`
  - `trustStorePath`
  - `trustStorePassword`
  - `keystorePath`
  - `keystorePassword`
- Sicherheitsklassifikation nach Parsing (Phase 1):
  - Nicht-sensitive Parser-Properties: `ssl`, `httpScheme`, `requestTimeoutMs`,
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
- Format ist absichtlich ohne `db:`-Prefix.

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
- Normalisierung:
  - Whitespace wird getrimmt, leere Tokens verworfen, Kleinbuchstaben erzwungen.
  - Reihenfolge wird deterministisch sortiert und Duplikate dedupliziert.
  - Jeder Eintrag muss das Muster `^&#91;a-z&#93;(?:&#91;a-z0-9_-&#93;*&#91;a-z0-9_&#93;)?(?:\\.&#91;a-z0-9&#93;(?:&#91;a-z0-9_-&#93;*&#91;a-z0-9_&#93;)?)*$`
    erfüllen. Die HTML-Entities vermeiden, dass der Docs-Link-Checker
    Regex-Gruppen irrtümlich als Markdown-Link interpretiert.
  - Das Muster verbietet leere Segmente (z. B. `token.`, `a..b`).
  - Der Regex wird bei Bedarf über eine neue `DM_TRINO_SESSION_ALLOWLIST_V`-Version erweitert;
    aktuelle Schreibweise bleibt deterministisch (kleinbuchstabig, Punkt-/Unterstrich-/Bindestrich-Zulässig).
  - Dotted Session-Keys sind erlaubt (z. B. `hive.s3_staging_directory`), sofern das Muster passt.
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

Zur Vermeidung von Inkonsistenzen gilt diese Reihenfolge explizit:

1. Runtime-Profil-Auflösung
- `--trino-runtime-profile` > `DM_TRINO_RUNTIME_PROFILE` > Default `production`.
- Jeder ungültige Profilwert (inkl. unbekannte Tokens) führt sofort zu `action_required`.
- Fehlt die Profilangabe vollständig, wird deterministisch `production` wirksam.

2. Transport-Guards
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

3. Secrets-Guards
- `production`: harte Blockade für `user:password`, `accessToken`, `trustStorePassword`,
  `keystorePassword` ohne Ausnahme.
- `non_production`: diese Werte nur mit aktiver Legacy-Geheimnis-Ausnahme erlaubt
  (`--allow-legacy-trino-secrets` oder `DM_TRINO_ALLOW_LEGACY_TRINO_SECRETS=true`).
- `session.<name>` bleibt in beiden Profilen ausschließlich allowlist-gesteuert.

4. Session-Forwarding-Quelle
- Präzedenz: explizit gesetztes CLI-Flag > Umgebungsvariable > leerer Default.
- Bei aktivem CLI-Override wird die Env-Quelle (inkl. `DM_TRINO_SESSION_ALLOWLIST_V`) vollständig
  ignoriert; ungültige Env-Versionen werden nur gewarnt, aber nicht blockiert.

5. Fehlerverhalten
- Alle obigen Verstoßfälle sind reproduzierbar als `action_required` (ohne Retry-/Transient-Pfade).

#### 5.2.6 Credential-Modell (Phase 1)

- Basisform: `trino://user[:password]@host:port/catalog/schema`.
- Optional/empfohlen: Passwort via Umgebungsvariable (z. B. `DM_TRINO_PASSWORD`) oder
  späterer Credential-Provider.
- `userinfo`-Parsing (Phase 1):
  - Grammatik nach Decodierung: `userinfo = user [ ":" password ]`
- `user` ist Phase 1 verpflichtend und muss vorhanden und nicht leer sein.
  `accessToken` ergänzt in dieser Phase ein zusätzliches Auth-Zusatzfeld; es ersetzt
  **nicht** die Pflicht zur URL-`userinfo` und kann nur mit vorhandenem `user` genutzt werden.
- Wenn `:` vorhanden ist, muss `password` nach Decodierung/Trim existieren und darf nicht
  leer sein.
- Bei vorhandenem `:` gilt `password` als URL-embedded Secret.
  - Leeres Passwort (`user:` oder Äquivalent nach Decodierung) wird in allen Profilen
    deterministisch mit `action_required` abgelehnt.
  - URL-embedded Secret ist in `production` hart blockiert.
  - In `non_production` ist URL-embedded Secret nur mit aktivierter
    Legacy-Geheimnis-Ausnahme erlaubt.
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
  - `session.<name>` bleibt in beiden Profilen erlaubt, sofern die Allowlist-Regel
    greift; Werte werden als Secret behandelt und maskiert.
    Die Allowlist-Entscheidung hat Vorrang vor Laufzeit- oder Profil-Gates.
  - Schwere Secret-Parameter:
    - `user:password`
    - `accessToken`
    - `trustStorePassword`
    - `keystorePassword`
    - `session.<name>`-Werte (maskierungspflichtig)
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
  - Sensitive Parameter mit explizit gesetztem, aber leerem Wert führen ebenfalls zu
    `action_required` in allen Profilen.
  - In `production` ist dies immer hart blockiert.
  - In `non_production` gilt dieselbe Blockade, außer bei exakt aktivierter
    Entwickler-Ausnahme (Dreifach-Signatur, siehe oben).
- Ungültige Angaben zu `DM_TRINO_RUNTIME_PROFILE` oder `--trino-runtime-profile` -> `action_required`.
- `requestTimeoutMs` ist hart validiert:
  - ungültig / nicht parsebar / außerhalb `1..2_147_483_647` -> `action_required`.
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

Standard-Verhalten ist in allen Phasen: Sink- oder Write-Pfade sind ohne explizite
Connector-Freigabe deaktiviert; die Tabelle bildet die einzige Ausnahmeliste ab.

| Befehl | Source | Target | Phase |
| --- | --- | --- | --- |
| `schema reverse` | ✅ | ❌ | 1 |
| `schema compare` | ✅ | ✅ *(read-only Diff-Pfad)* | 1 |
| `data export` | ✅ | ❌ | 1 |
| `data profile` | ✅ *(nur mit Profiling-Modul)* | ❌ | 1 |
| `data transfer` | ✅ | ❌ | 1 |
| `schema generate` | ❌ | ⚠️ (explizit freigegeben) | 3 |
| `data import` | ❌ | ❌ | 4+ |

Regel:

- `Target` für Trino ist in Phase 1 standardmäßig gesperrt.
- `schema compare --target trino://...` bleibt erlaubt, weil semantisch read-only.
- Write-/Generate-Funktionen erfordern immer einen expliziten Capability-Review je
  Connector.

- `data profile` ist in Phase 1 Source-only; Zielseite mit Trino (`--target trino://...`) ist
  nicht erlaubt.

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
- `data profile --target trino://...` startet nicht (Source-only-Regel).
- `data profile --source trino://...` ist nur mit aktivem Profiling-Modul möglich.
- `data profile --source trino://...` ohne Modul endet mit `action_required` + Hinweis.
- Nicht erlaubte Query-Properties liefern reproduzierbar `action_required`.
- Doppelte Query-Properties liefern reproduzierbar `action_required` (auch bei unterschiedlich
  kodierten Doppelungen).
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

# Trino verwendet implizit den Production-Modus, wenn kein Runtime-Profil gesetzt ist.
d-migrate schema reverse \
  --source trino://analyst@localhost:8080/iceberg/default \
  --output lakehouse.yaml

d-migrate data export \
  --source trino://analyst@localhost:8080/iceberg/default \
  --tables orders,customers \
  --format csv

# Unsicherer Transport nur explizit in non_production mit zusätzlicher Signatur aktiv.
d-migrate schema reverse \
  --trino-runtime-profile=non_production \
  --allow-insecure-trino-transport \
  DM_TRINO_ALLOW_INSECURE_TRANSPORT=true \
  --source trino://analyst@localhost:8080/iceberg/default?httpScheme=http \
  --output lakehouse.yaml

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
- `schema reverse` ohne gesetztes Profil nutzt implizit `production` als effektives Runtime-Profil.
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
- Query-Property-Duplikate (nach vollständiger Percent-Dekodierung) liefern reproduzierbar
  `action_required`.
- Ungültige Percent-Dekodierung in URL-Teilen oder Query-Werten führt reproduzierbar
  `action_required`.
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
- [ ] `requestTimeoutMs` wird als positive Ganzzahl strikt validiert; nicht parsebare,
  nicht-positive oder leere Werte führen zu `action_required`.
- [ ] Transport-Konflikte werden hart blockiert:
  - `ssl=true` + `httpScheme=http`
  - `ssl=false` + `httpScheme=https`
- [ ] `--trino-session-allowlist` ist als Tri-State implementiert und folgt der Präzedenzregel
  (explizites CLI-Set > Env > leerer Default).
- [ ] `DM_TRINO_SESSION_ALLOWLIST_V` ist bei aktivierter Env-Quelle versioniert:
  - Bei invaliden Env-Versionen wird `action_required` erzeugt.
  - Bei aktivem CLI-Override wird eine ungültige Env-Version nicht blockierend als Warnung
    dokumentiert und vollständig vom Env-Source-Parsing entkoppelt.
- [ ] `--trino-session-allowlist=""` wird als explizit gesetzter CLI-Override mit leerer
  Allowlist ausgewertet (ohne Env-Fallback).
- [ ] Leeres URL-embedded Passwort (`trino://user:@host:...`) wird in allen Profilen
  reproduzierbar mit `action_required` abgelehnt.
- [ ] `data transfer --target trino://...` bricht in Tranche 1 mit klarer
  Guard-Fehlermeldung ab (rein Source-only).
- [ ] `data profile --target trino://...` ist in Tranche 1 als Source-only klar abgelehnt.
- [ ] Keine Secret-Ausgaben in Logs/Fehlern/Debug-Meldungen.
- [ ] Ungültige Runtime-Profile (`DM_TRINO_RUNTIME_PROFILE` / `--trino-runtime-profile`) führen
  in allen Pfaden zu `action_required`.
- [ ] In `production` sind `user:password`-URLs sowie `accessToken`, `trustStorePassword`,
  `keystorePassword` als Secret-Properties hart blockiert (`action_required`), unabhängig vom
  aktiven Modul.
- [ ] Leere Werte bei explizit gesetzten Secret-Parametern (`accessToken`, `trustStorePassword`,
  `keystorePassword`, `user:password`, `session.<name>-Werte`) führen in allen Profilen deterministisch zu
  `action_required`.
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
- [ ] Doppelte Query-Properties werden nach vollständiger Percent-Dekodierung erkannt
  und mit `action_required` abgelehnt.
- [ ] Fehlerhafte Percent-Dekodierung in URL-Komponenten und Query-Werten liefert deterministisch
  `action_required`.
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
