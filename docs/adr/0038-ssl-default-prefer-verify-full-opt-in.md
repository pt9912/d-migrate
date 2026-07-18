---
status: accepted
date: 2026-07-18
decision-makers: pt9912
consulted: docs/planning/open/jdbc-ssl-default-hardening.md, docs/planning/open/security-audit-2026-07-17.md, spec/connection-config-spec.md, spec/lastenheft-d-migrate.md
informed: hexagon/ports-common, adapters/driven/driver-common, adapters/driven/driver-postgresql
---

# PostgreSQL-SSL-Default bleibt `prefer` — `verify-full` ist opt-in

> **Status: accepted (2026-07-18).** Löst die Deferral-Notiz zu
> Security-Audit-Befund 8 in
> [`jdbc-ssl-default-hardening.md`](../planning/open/jdbc-ssl-default-hardening.md)
> ab und hält die bislang nur als Ticket-Zuschnitt getroffene Entscheidung
> normativ fest.

## Kontext und Problemstellung

Fehlt in einer Verbindungs-URL der `sslmode`-Parameter, greift der Treiber-Default:
pgjdbc `prefer`, Connector/J `PREFERRED`. Das ist opportunistisches TLS **ohne
Zertifikatsprüfung** mit stillem Klartext-Fallback. Das Security-Vollaudit
(Befund 8, CWE-319) fragt, ob d-migrate stattdessen einen sicheren Default
(`verify-full`) setzen soll, damit ein Operator nicht unbemerkt ungeprüft
verbunden ist.

d-migrate führt SSL seit [LN-026](../../spec/lastenheft-d-migrate.md) als
typisiertes `SslSettings`-Modell, setzt aber bewusst **keine eigenen Defaults**
(1:1-Passthrough zum Treiber). Zu entscheiden ist: Bleibt das so, oder erzwingt
d-migrate einen sicheren Default?

## Entscheidungstreiber

- **Prinzip der geringsten Überraschung:** `prefer`/`PREFERRED` ist der
  dokumentierte Default von libpq, psql, pgjdbc und Connector/J. Ein Werkzeug,
  das denselben Default erbt, verhält sich wie jedes andere JDBC-Werkzeug — es ist
  keine d-migrate-eigene Schwächung.
- **Kompatibilität:** Ein `verify-full`-Default bricht jedes bestehende Setup mit
  selbstsigniertem/internem Zertifikat ohne konfigurierten Truststore — ein hartes,
  stillschweigendes Breaking gerade für die migrations-typische Interne-DB-Umgebung.
- **Operator-Mitigation ist first-class:** `?sslmode=verify-full&sslrootcert=…`
  parst und wird korrekt emittiert (die typisierte SSL-Lieferung). Wer Verifikation
  will, bekommt sie explizit.
- **Ehrlichkeit statt stiller Fehlfunktion:** Der schärfste Teil des Befunds ist
  nicht der Default selbst, sondern dass ein **wirkungsloser** CA-Pin
  (`sslrootcert` bei nicht-verifizierendem Modus) nichts sagt.

## Betrachtete Optionen

1. **`prefer`-Default beibehalten, `verify-full` opt-in** (gewählt).
2. **`verify-full` als Default erzwingen.**
3. **`prefer` beibehalten, aber bei wirkungsloser Konfiguration warnen.**

## Entscheidung

Gewählt: **Option 1 plus der Warnungsteil von Option 3.** d-migrate setzt **keinen**
eigenen SSL-Default; der Treiber-Default (`prefer`) bleibt. `verify-full` (inkl.
`sslrootcert`) ist eine explizite Opt-in-Konfiguration des Operators.

**Ergänzend** (nicht als Default, sondern als Ehrlichkeit): Ein gesetzter
`sslrootcert` bei einem Modus, der ihn nicht nutzt (kein `verify-ca`/`verify-full`,
inkl. fehlendem `sslmode`), ist ein **stiller No-Op**. Dieser Fall erzeugt jetzt
eine `WARN`-Logzeile am `ConnectionUrlParser`-Chokepoint (maskierte URL) — der
Operator wird informiert, ohne dass eine gängige, funktionierende Konfiguration
(`sslmode=require&sslrootcert=…`) hart bricht. Ein **harter** Konfigurationsfehler
für diesen Fall wurde in der Audit-Gegenprüfung verworfen, weil er genau diese
verbreitete Form bräche.

## Konsequenzen

- **Positiv:** kein Breaking; spec-konformes, erwartbares Verhalten; wirkungslose
  CA-Pins werden nicht mehr stillschweigend verschluckt.
- **Negativ:** Der unsichere Default bleibt der Default — wer `sslmode` gar nicht
  setzt, verbindet weiter opportunistisch. Das ist bewusst der Treiber-Standard;
  die Verantwortung liegt (wie bei psql) beim Operator.
- **Abgrenzung:** Truststore-/Client-Cert-Erzwingung und ein evtl. späterer sicherer
  Default bleiben offene Tiefenstufen; ein künftiger sicherer Default würde diese
  ADR ablösen und bräuchte einen CHANGELOG-Breaking-Hinweis.

## Weitere Informationen

- [`jdbc-ssl-default-hardening.md`](../planning/open/jdbc-ssl-default-hardening.md)
  — Befunde 8/9 mit Umsetzung.
- [`security-audit-2026-07-17.md`](../planning/open/security-audit-2026-07-17.md)
  — Audit-Bericht, Befund 8 mit dreifacher Gegenprüfung.
