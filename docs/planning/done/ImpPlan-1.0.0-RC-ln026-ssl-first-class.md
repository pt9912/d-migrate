# ImpPlan 1.0.0-RC — LN-026: First-Class SSL/TLS (minimal: typisiert + validiert)

> Status: **DONE / graduiert** (2026-07-11, Review 1 eingearbeitet; siehe „## Closure"). Schließt den
> [`LN-026`](../../../spec/lastenheft-d-migrate.md#ln-026)-Gap (Roadmap 1.0.0-RC,
> Fußnote ³): SSL ist heute nur generischer JDBC-URL-Parameter-**Passthrough**;
> First-Class-Config (typisiert, validiert, per-Dialekt-korrekt) fehlt.

## Kontext / Ist-Stand

`ConnectionUrlParser.parse` legt **alle** Query-Params roh in
`ConnectionConfig.params: Map<String,String>` (`parseQuery(uri.rawQuery)`).
`JdbcUrlBuilder.buildJdbcUrl` (Default-Impl im Interface) merged `defaultParams()`
+ `config.params` und hängt sie URL-encoded an — die SSL-Params (`sslmode`/`ssl`/…)
fließen also **unverändert und ungeprüft** durch. Kein typisiertes Modell, keine
Validierung ungültiger Modi, keine per-Dialekt-Normalisierung.

**Wichtige Nuance (Review-1-präzisiert):** Verschlüsselung ist heute nur mit dem
**korrekten** Namen möglich — `sslmode=require` auf PG funktioniert (Passthrough →
pgjdbc). Die Spec-eigenen Beispiele mit `ssl=require`/`ssl=verify-full` auf **PG**
(Zeilen 206/475/476) sind **kaputt** (pgjdbcs `ssl` ist boolesch, kein Modus), und
MySQL `ssl=true` ist unter Connector/J 9.x ein **No-Op**. Dieser Slice hebt die
Qualität auf First-Class **und** korrigiert diese Spec-/Verhaltens-Lücken.

## Spec-Vertrag (Zielbild)

[`connection-config-spec.md`](../../../spec/connection-config-spec.md):
- **PG:** `sslmode` ∈ {`disable`,`allow`,`prefer`,`require`,`verify-ca`,`verify-full`}
  (Default `prefer`); `sslrootcert` = CA-Pfad.
- **MySQL:** `ssl` (bool, Default `false`); `sslMode` ∈ {`DISABLED`,`PREFERRED`,
  `REQUIRED`,`VERIFY_CA`,`VERIFY_IDENTITY`} (Default `PREFERRED`).

## Scope (user-abgestimmt 2026-07-11: **Minimal — typisiert + validiert**)

Neutrales `SslSettings` in `ConnectionConfig`; `ConnectionUrlParser` extrahiert +
validiert die SSL-Params (ungültiger Modus → klarer Fehler statt stillem
Garbage-Passthrough); jeder `JdbcUrlBuilder` mappt `SslSettings` zurück auf die
korrekten per-Dialekt-JDBC-Params.

## Nicht-Scope

- **Erzwingung** (require-SSL / fail-closed) — nächste Tiefenstufe.
- **Truststore/Keystore**-Config (Pfade + Passwörter) — nächste Tiefenstufe.
- MySQL-Client-Zertifikate (`trustCertificateKeyStoreUrl` etc.) — Truststore-Level.

## Architektur

**Neutrales Modell** ([[feedback_neutral_model_no_native_passthrough]]): ein
neutraler `SslMode`-Enum, per-Dialekt gemappt. **Modell im Hexagon, Parsing im
Adapter** ([[feedback_resource_loader_colocation]]).

1. **`SslMode`** (Enum, **`hexagon:ports-common`** `driver.connection`, ko-lokalisiert
   mit `ConnectionConfig`): `DISABLE`, `ALLOW`, `PREFER`, `REQUIRE`, `VERIFY_CA`,
   `VERIFY_FULL` (PG-Supermenge).
2. **`SslSettings`** (data class, ports-common): `mode: SslMode?` (null = nicht
   gesetzt → Treiber-Default, Parität) + `rootCert: String?` (CA-Pfad, **PG-only**).
3. **`SslSettingsParser`** (**`adapters/driven/driver-common`**, bei
   `ConnectionUrlParser`) — dialekt-bewusst; scrubbt via **`ConnectionSecretMasker`**
   (ports-common), nicht `LogScrubber`:
   - PG: liest `sslmode` (→ `SslMode` 1:1) + `sslrootcert`. PG kanonisiert **auf
     `sslmode`** (kein `ssl`-Alias — die Spec-`ssl=`-PG-Beispiele waren kaputt, s.
     AP4).
   - MySQL: liest `sslMode` (MySQL-Namen → neutral) + `ssl` (Legacy-Bool). `sslMode`
     gewinnt; `ssl` nur als Fallback.
   - SQLite: kein Netz-SSL → SSL-Keys bleiben unberührt in `params` (nicht extrahiert).
   - **Ungültiger Wert → `IllegalArgumentException`** (via `ConnectionSecretMasker`
     gescrubbte URL in der Message).
4. **`ConnectionConfig`** bekommt `ssl: SslSettings = SslSettings()`; der Parser
   **extrahiert** die dialekt-eigenen SSL-Keys aus `params` in `ssl` und **entfernt**
   sie aus `params` (Single Source of Truth). `toString` nimmt `ssl` auf (nicht
   sensibel: Modus + CA-Pfad).
5. **`JdbcUrlBuilder`-Naht (Review-1-Kern):** das Interface bekommt
   `fun sslParams(ssl: SslSettings): Map<String,String>` (**Default leer**; PG/MySQL
   überschreiben, SQLite erbt leer). Die gemeinsame `buildJdbcUrl`-Default-Impl merged
   **`defaultParams()` → `sslParams(config.ssl)` → `config.params`** (Präzedenz
   festgeschrieben; `config.params` trägt keine ssl-Keys mehr → kein Konflikt). Kein
   Builder überschreibt `buildJdbcUrl` (keine Merge-/Encode-Duplikation).
   - PG `sslParams`: `SslMode` → `sslmode=<pg-name>` (1:1) + `sslrootcert`.
   - MySQL `sslParams`: `SslMode` → `sslMode=<mysql-name>` (Mapping unten).
   - SQLite: erbt leer.

### Aufgelöste Impedanz-Entscheidungen (Review 1)

- **`ALLOW` ↔ MySQL:** `ALLOW` → MySQL `PREFERRED` (**opportunistisch**, MySQL kennt
  kein `allow`).
- **MySQL `ssl=true` (Legacy):** → **`PREFER`**, nicht `REQUIRE` (rückwärtskompatibel;
  `ssl=true` ist unter Connector/J 9.x heute ein No-Op — eine Eskalation zu `REQUIRED`
  bräche Nicht-TLS-Server, die „liefen"). `ssl=false` → `DISABLE`.
- **Neutral↔MySQL-Namen:** `DISABLE↔DISABLED`, `PREFER/ALLOW↔PREFERRED`,
  `REQUIRE↔REQUIRED`, `VERIFY_CA↔VERIFY_CA`, `VERIFY_FULL↔VERIFY_IDENTITY`.
- **`rootCert`:** nur aus PG `sslrootcert` (MySQL-URLs nutzen `sslrootcert` nicht →
  moot; MySQL-Client-CA ist Truststore-Level, Nicht-Scope).
- **Defaults:** `null` → Treiber-Default (Parität); **keine** expliziten
  d-migrate-Defaults (sonst Verhaltensänderung).

## Phasen (AP)

- **AP1 — Modell + Parser:** `SslMode`/`SslSettings` (ports-common) +
  `SslSettingsParser` (driver-common, per-Dialekt Parse/Validierung/Mapping) +
  Unit-Tests (alle Modi, ungültig→Fehler, MySQL-Namen+`ssl`-Bool→opportunistisch,
  SQLite-unberührt).
- **AP2 — ConnectionConfig + ConnectionUrlParser:** `ssl`-Feld + `toString`; Parser
  extrahiert SSL-Keys → `ssl` und entfernt sie aus `params`. **Migrationspunkt:**
  `ConnectionUrlParserTest:49-74` erwartet ssl heute **in `params`** → auf `config.ssl`
  umstellen. `HikariConnectionPoolFactory` (params nur für `SPATIALITE_PARAM`, :49/:126)
  verifizieren (ssl-Entfernung sicher).
- **AP3 — JdbcUrlBuilder-Naht:** `sslParams`-SPI-Methode + Merge in `buildJdbcUrl`
  (ports-common); PG/MySQL `sslParams`-Override; SQLite erbt leer. Builder-Tests
  (korrekte per-Dialekt-Params, Präzedenz, `defaultParams`-Merge unberührt,
  Parität ohne ssl).
- **AP4 — Doku/Spec/Roadmap:** **Spec-Bug fixen** — PG-Beispiele
  `connection-config-spec.md` :206/:475/:476 `?ssl=…` → `?sslmode=…` (PG kanonisiert
  auf `sslmode`); Admin-/User-Handbuch (SSL-Modi + **Hinweis: MySQL `VERIFY_*` voll
  erst mit Truststore-Tiefenstufe**); CHANGELOG; Roadmap [`LN-026`](../../../spec/lastenheft-d-migrate.md#ln-026) 🚧³ → ✅, Fußnote ³
  auf „Erzwingung/Truststore = nächste Tiefenstufe" präzisieren.

## Definition of Done

- Explizite SSL-Modi werden typisiert geparst + validiert (ungültig → klarer Fehler);
  PG/MySQL-JDBC-URLs tragen die korrekten per-Dialekt-Params über die `sslParams`-Naht.
- Kein doppeltes ssl-Emit; `params` trägt keine dialekt-eigenen ssl-Keys mehr.
- Verhalten ohne explizite SSL-Params **byte-identisch** (Passthrough-Parität).
- Spec-PG-`ssl=`-Beispiele auf `sslmode=` korrigiert.
- Alle berührten Module `:check` grün; `docs-check` grün; Roadmap [`LN-026`](../../../spec/lastenheft-d-migrate.md#ln-026) ✅.

## Closure (2026-07-11)

Alle AP erledigt:
- **AP1** — `SslMode`/`SslSettings` (ports-common) + `SslSettingsParser`
  (driver-common, per-Dialekt; PG `sslmode`/`sslrootcert`, MySQL `sslMode`/`ssl`→
  opportunistisch, SQLite unberührt, ungültig→Fehler via `ConnectionSecretMasker`)
  + `SslSettingsParserTest`.
- **AP2** — `ConnectionConfig.ssl` + `toString`; `ConnectionUrlParser` extrahiert
  ssl-Keys → `config.ssl`, entfernt aus `params`; `ConnectionUrlParserTest:49-74`
  migriert (ssl in `config.ssl` statt `params`).
- **AP3** — `JdbcUrlBuilder.sslParams`-Naht (Default leer; PG/MySQL override, SQLite
  erbt) + Merge `defaultParams → sslParams → config.params`; PG/MySQL-Builder-Tests.
- **AP4** — Spec-PG-Beispiele `?ssl=…`→`?sslmode=…` gefixt; Admin-Handbuch §9.3;
  CHANGELOG; Roadmap [`LN-026`](../../../spec/lastenheft-d-migrate.md#ln-026) ✅.

Review-1-Blocker gelöst: die fehlende SPI-Naht (`sslParams`) ist der Kern; alle 7
Punkte (E-Merge-Präzedenz, PG-Spec-Bug, opportunistisches `ssl=true`→PREFER,
Parser-in-driver-common, Test-Migration, MySQL-VERIFY-Doku, `toString`) adressiert.
