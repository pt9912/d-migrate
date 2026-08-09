# `persistence-jdbc`: Entscheidung von Ausführung trennen (Coverage-Naht)

> **Status**: Draft mit Scope (2026-08-09) — aktiviert aus
> [`open/`](../open/README.md) durch die Wegentscheidung des Eigners.
> **Ziel**: Die 421 Produktivzeilen des Moduls, die heute nur im Integrationslauf
> gedeckt sind, im **Standard-Lauf** echt prüfbar machen — ohne das Gate um einen
> Testcontainers-Lauf zu verlangsamen und ohne Kover-Excludes zu verbreitern.
> **Vorbedingung**: erfüllt. Weg 3 ist gewählt (siehe *Entscheidung*).

## Ausgangslage

Sieben Produktivklassen stehen im Standard-Lauf bei **0 %** Line-Coverage — nicht
weil sie ungetestet wären, sondern weil ihre Tests Testcontainers brauchen und der
Gate-Lauf keinen Docker-Socket bekommt. Messung vom 2026-07-31:

| Klasse | ungedeckte Zeilen |
| --- | --- |
| `idempotency/JdbcIdempotencyStore` | 145 |
| `job/JdbcJobStore` | 109 |
| `quota/JdbcQuotaReservationOwnerStore` | 76 |
| `quota/JdbcOwnerAwareQuotaService` | 35 |
| `quota/JdbcQuotaStore` | 30 |
| `job/JdbcJobStartTransaction` | 15 |
| `migration/JdbcMigrationRunner` | 11 |
| **Summe** | **421** |

Dass das Gate trotzdem grün ist, liegt allein an der Aggregation über alle Module:
421 Zeilen mitteln sich aus. Die Klassen sind zusätzlich einzeln in den
Kover-Excludes des Moduls eingetragen.

## Entscheidung

**Weg 3 aus dem Ursprungs-Ticket** (Eigner, 2026-08-09): die Naht ziehen, statt die
Messung zu verschieben. Verworfen wurden:

- **Weg 1** (Coverage im Integrationslauf mitmessen) — misst die Wahrheit, ändert
  aber nichts an der Testbarkeit und koppelt das Gate an einen Docker-Socket.
- **Weg 2** (Modul aus der Standard-Aggregation nehmen) — verschiebt das Problem
  dorthin, wo es weniger auffällt.

Beleg, dass die Naht trägt: `job/JobRecordJson` liegt im selben Paket bei **100 %**.
Der reine Teil ist bereits ohne Datenbank prüfbar; der Rest ist es nur deshalb nicht,
weil er mit der Ausführung in derselben Klasse steckt.

## Das Muster: entscheiden, dann ausführen

Am `reserve`-Pfad von `JdbcIdempotencyStore` sichtbar gemacht. Heute nimmt
`dispatchReserve(conn, scope, fingerprint, now, existing)` eine `Connection`
entgegen — **nur**, um sie an `recoverExpired` durchzureichen. Die Entscheidung
selbst ist reine Datenlogik über `EntryRow`:

- Fingerprint weicht ab → `Conflict`
- `COMMITTED` / `DENIED` / `FAILED` → terminales Ergebnis aus Zeilendaten
- `PENDING` / `AWAITING_APPROVAL`, noch nicht abgelaufen → `ExistingPending` /
  `AwaitingApproval`
- abgelaufen → Recovery nötig (der einzige Schreibvorgang)

Der Schnitt führt einen Entscheidungstyp ein, der beschreibt **was zu tun ist**,
ohne es zu tun:

```kotlin
sealed interface ReserveDecision {
    data class Complete(val outcome: IdempotencyReserveOutcome) : ReserveDecision
    data class RecoverExpired(val fingerprint: String, val now: Instant) : ReserveDecision
}
```

Der Store ruft dann: Zeile lesen (unrein) → entscheiden (rein) → bei
`RecoverExpired` schreiben (unrein). Die Zustandsmaschine wird damit als
Entscheidungstabelle prüfbar — ohne `Connection`, ohne Postgres.

**Integrationsgebunden bleibt**, was das auch sein muss: SQL-Strings, JDBC-Binding,
`ResultSet` → Row-Mapping, die CAS-Prüfung (`updated == 1`) und das
Transaktionsverhalten.

## Offene Folgefrage aus dem Ticket — beantwortet

*Laufen die Contract-Suiten danach doppelt (einmal gegen einen Fake, einmal gegen die
echte DB), oder wird die Integrationsschicht auf Ausführungspfade eingedampft?*

**Weder noch.** Die neuen Unit-Tests prüfen die **Entscheidungstabelle**, die
Contract-Suiten prüfen das **beobachtbare Verhalten des Stores** gegen echtes
Postgres. Das sind verschiedene Gegenstände, keine Verdopplung. Die 22
Contract-Suiten in `hexagon/ports-common` bleiben unverändert und bleiben der
einzige Nachweis, dass die SQL-Pfade real funktionieren. Eingedampft wird nichts —
sonst verlöre man genau die Prüfung, die Postgres-Semantik (JSONB, `FOR UPDATE`,
`ON CONFLICT`) abdeckt.

## Sub-Slices

Reihenfolge nach Hebel und Musterwirkung. Jeder Sub-Slice endet mit einem
Kover-Exclude **weniger**, nicht mit einem breiteren.

| # | Gegenstand | Warum hier |
| --- | --- | --- |
| S1 | `JdbcIdempotencyStore` `reserve`-Pfad | Größter Brocken (145 Zeilen) und reichste Zustandsmaschine — setzt das Muster |
| S2 | `JdbcIdempotencyStore` Restpfade (`claimApproved`, `commit`, `deny`, `markFailed`, `cleanupExpired`) | Gleiches Muster, gleiche Klasse |
| S3 | `JdbcJobStore` | 109 Zeilen, zweitgrößter Hebel |
| S4 | `quota`-Paket (`JdbcQuotaStore`, `JdbcQuotaReservationOwnerStore`, `JdbcOwnerAwareQuotaService`) | 141 Zeilen; als Paket exkludiert, muss also als Paket zurückgeholt werden |
| S5 | `JdbcJobStartTransaction` | 15 Zeilen, Cross-Store-Komposition — braucht S1–S4 als Fundament |

`JdbcMigrationRunner` (11 Zeilen) bleibt bewusst außen vor: er ist ein dünner
Flyway-Wrapper ohne Entscheidungslogik. Dort gibt es keine Naht zu ziehen, nur
Ausführung.

## Akzeptanzkriterien

- Je Sub-Slice: der zugehörige Eintrag verschwindet aus den Kover-Excludes des
  Moduls, und der Excludes-Ledger wird entsprechend gepflegt.
- `koverVerify` des Moduls bleibt durchgehend grün, ohne dass die `minBound(90)`
  gesenkt wird.
- Die bestehenden Contract-Suiten laufen unverändert weiter und bleiben grün
  (`make integration`).
- Kein neuer Exclude auf eine Klassenfamilie.

## Bezug

- Ursprungs-Ticket mit der Messung und der Mechanik-Tabelle (Gate-Lauf ohne
  Docker-Socket vs. `test-integration-docker.sh`): aufgegangen in diesem Plan.
- [`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md) — Lebenszyklus
  dieses Dokuments.
- Nebenbefund aus dem Ticket, **nicht** Teil dieses Plans:
  `MigrationExecutorTestSupportKt` (152 Zeilen reiner Test-Support ohne
  Produktiv-Gegenstück) ist ein vertretbarer Exclude mit Ledger-Eintrag — getrennt
  zu entscheiden, kein Hebel.
