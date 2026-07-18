# JDBC: kein sicherer SSL-Default, CA-Pin als stiller No-Op (P3)

> **Status:** Befund 9 BEHOBEN 2026-07-18; Befund 8 = bewusste offene Tiefenstufe.
> **Trigger:** Security-Vollaudit
> ([`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Befunde 8 und
> 9, beide P3). Konkretisiert die bekannte offene Tiefenstufe von [`LN-026`](../../../spec/lastenheft-d-migrate.md)
> („Erzwingung + Truststore").
>
> **Umsetzung (Befund 9, CWE-178):** `SslSettingsParser` verbraucht jetzt **alle**
> Case-Varianten eines SSL-Keys (kanonische Lowercase-Keys + case-insensitiver
> `filterConsumed`); ein case-abweichendes Duplikat (`sslMode` neben `sslmode`)
> überlebt nicht mehr in `remainingParams` und kann den validierten Modus nicht
> mehr in der emittierten URL überschreiben. TDD (PG + MySQL);
> `:driver-common:check` grün.
>
> **Befund 8 — bewusst NICHT jetzt (dokumentierte Entscheidung):** (8a) Ein sicherer
> Default (`verify-full`) ist ein **Breaking Change** für Selfsigned-Setups und
> braucht ADR + CHANGELOG; der Audit hält `prefer` für spec-konform (libpq/pgjdbc-
> Default). (8b) `sslrootcert` ohne verifizierenden `sslmode` als **harter** Fehler
> wurde in der Audit-Gegenprüfung **widerlegt** (bräche das verbreitete
> `sslmode=require&sslrootcert`); die sanktionierte Alternative — eine W-Code-
> **Warnung** — bräuchte einen Note-Kanal durch `JdbcUrlBuilder.sslParams` (heute
> reine `Map`), was der abgeschwächte P3 nicht rechtfertigt. Beides bleibt bewusst
> als Tiefenstufe offen.

## Befunde

**8 (P3, CWE-319) — Kein sicherer SSL-Default.** Fehlt `sslmode`, greift der
Treiber-Default: pgjdbc `prefer`, Connector-J `PREFERRED`. Das ist
opportunistisches TLS **ohne Zertifikatsprüfung** und mit stillem
Klartext-Fallback — der Operator glaubt, verschlüsselt zu sein, und bekommt es
nicht gesagt, wenn er es nicht ist.

Verwandt und besonders tückisch: `sslrootcert` wird ohne `sslmode` emittiert.
Bei pgjdbc-Default `prefer` ist ein gesetzter CA-Pin damit ein **stiller
No-Op** — die Konfiguration sieht sicher aus und wirkt nicht.

**9 (P3, CWE-178) — `SslSettingsParser` konsumiert bei case-abweichenden
Duplikat-Keys nur einen.** Der zweite überlebt in `params` und kann den
validierten Modus überschreiben.

## Einordnung

[`LN-026`](../../../spec/lastenheft-d-migrate.md) wurde bewusst als „minimal: typisiert + validiert" geschnitten;
Erzwingung und Truststore sind als offene Tiefenstufen bekannt. Diese Befunde
sind damit **keine Überraschung, sondern die Konkretisierung** — sie sagen,
welche der offenen Stufen tatsächlich ausbeutbar ist und warum (der
`sslrootcert`-No-Op ist der schärfste Punkt, weil er aktiv in die Irre führt).

## Arbeitspakete (Skizze)

1. Entscheiden, ob ein sicherer Default (`verify-full`) gesetzt wird — das ist
   ein **Breaking Change** für bestehende Nutzer mit selbstsignierten Zertifikaten
   und braucht wahrscheinlich eine ADR plus CHANGELOG-Eintrag. Alternative:
   Default belassen, aber warnen, wenn ohne `sslmode` verbunden wird
   (W-Code, Ledger-Eintrag nötig).
2. `sslrootcert` ohne `sslmode` als Konfigurationsfehler behandeln — ein CA-Pin,
   der nicht wirkt, ist schlimmer als keiner.
3. Duplikat-Keys case-insensitiv vollständig konsumieren.
4. Encoding-Asymmetrie prüfen (Bericht, Befund im jdbc-url-Abschnitt):
   `config.params` werden URL-encoded, `config.database`/`config.host` roh
   interpoliert.

## Fundstellen

- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/SslSettings.kt:21`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresJdbcUrlBuilder.kt:48` (`sslrootcert`-No-Op)
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/connection/SslSettingsParser.kt:84`
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/JdbcUrlBuilder.kt:70` (Encoding-Asymmetrie)
