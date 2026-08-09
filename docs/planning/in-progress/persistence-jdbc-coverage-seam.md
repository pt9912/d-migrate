# `persistence-jdbc`: Entscheidung von Ausführung trennen (Coverage-Naht)

> **Status**: In Progress (2026-08-09) — **S1 geliefert**, siehe *Fortschritt*.
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

Reihenfolge nach Hebel und Musterwirkung.

**Korrektur am ursprünglichen Akzeptanzkriterium (2026-08-09, gemessen nach S2):**
„Je Sub-Slice ein Kover-Exclude weniger" geht nicht auf — und zwar grundsätzlicher,
als es beim Schreiben des Plans aussah.

Erstens sind die Excludes **klassenweit**, S1 und S2 teilen sich dieselbe Klasse.
Zweitens, und entscheidend: **auch nach vollständigem Schnitt fällt der Exclude
nicht.** Gemessen mit testweise entferntem Exclude steht `JdbcIdempotencyStore` bei
**0/116** und das Modul bei **57,7 %** gegen ein Gate von 90. Die verbliebenen 116
Zeilen sind `querySingle`/`executeUpdate` mit SQL-Literalen und Binding — sie laufen
ohne Postgres nicht, und daran ändert kein Schnitt etwas.

Der Grund liegt im Code-Stil: SQL-Literal, Binding und Ausführung stehen in **einem**
Ausdruck. Es gibt keinen separaten „SQL-Bau", den man herauslösen könnte — anders als
das Ursprungs-Ticket vermutete. Herauslösbar war ausschließlich die
**Entscheidungslogik**, und die ist mit S1+S2 vollständig heraus.

**Was Weg 3 also wirklich liefert**: nicht das Verschwinden der Excludes, sondern das
Schrumpfen dessen, was hinter ihnen liegt. Die Messgröße ist die **gemessene Fläche**,
nicht die Exclude-Anzahl.

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

## Fortschritt

**S1 geliefert (2026-08-09).** Der `reserve`-Pfad ist geschnitten: `ReserveDecision.kt`
trägt `ReservationRow` (Zeilenstand als Daten), den Entscheidungstyp und
`decideReserve`. Der Store liest, entscheidet, und schreibt nur noch bei
`RecoverExpired`. Entfernt: `dispatchReserve`, `recoverOrExisting`, `recoverOrAwaiting`
und die verschachtelte Row-Klasse (47 Zeilen).

Gemessen am Modul-Report: die neue Naht steht bei **32/32 Zeilen** (`ReserveDecisionKt`
22, `ReservationRow` 8, die beiden Entscheidungs-Varianten je 1) — Zeilen, die vorher
ausschließlich im Integrationslauf gedeckt waren. Modul insgesamt 128/128.

Die Entscheidungstabelle ist jetzt ohne Datenbank prüfbar, inklusive zweier Fälle, die
vorher praktisch nicht zu testen waren: die **exklusive Lease-Grenze** (`expiresAt ==
now` zählt als abgelaufen, deckungsgleich mit dem `expires_at <= ?` der Recovery-CAS —
liefen beide auseinander, entschiede der Code Übernahme und das UPDATE fände keine
Zeile) und die **Irrelevanz von `claimed`** für den reserve-Pfad.

Contract-Suiten gegen echtes Postgres unverändert grün.

**S2 geliefert (2026-08-09).** Die übrigen Pfade derselben Klasse. Herausgelöst:
`decideClaim` (Zustandsmaschine des Claim-Pfads, inkl. fehlender Zeile),
`decideInitResume` (Fingerprint-Vergleich der Init-Resume-Reservierung) und
`terminalExpiry` (Retention-Regel). Der Store hält nur noch SQL.

Gemessene Fläche jetzt **62 Zeilen** (`ReservationDecisionsKt` 46, `ReservationRow` 8,
`InitResumeRow` 4, vier Entscheidungs-Varianten je 1), Modul 158/158. Vor S1 waren es
96 gemessene Zeilen — die Fläche ist also um **65 %** gewachsen, ohne dass eine Zeile
Produktivverhalten sich geändert hat.

Zwei weitere Regeln sind dadurch erstmals gepinnt: die **Freigabe-Grenze** ist wie die
Lease-Grenze exklusiv (sonst liefe die Claim-CAS `expires_at > ?` ins Leere und
`check(updated == 1)` würde werfen), und **`retentionUntil` kann nur verlängern, nicht
verkürzen** — ein Aufrufer kann terminale Ergebnisse nicht früher verschwinden lassen,
als der Vertrag sie zusichert.

**S3 geliefert (2026-08-09).** `JdbcJobStore`. Herausgelöst: `paginate` (jetzt
generisch über `List<T>`), `decideTransition` und `decideCancelRequest`. Gemessene
Fläche **+34 Zeilen** (`JobDecisionsKt` 32, zwei Entscheidungs-Varianten je 1), Modul
192/192.

`paginate` war der Fund dieses Sub-Slices: ein reiner Algorithmus mit
Off-by-one-Fläche, der nur deshalb einen Postgres-Lauf brauchte, weil er als
`private` neben dem SQL lag. Neun Randfälle sind jetzt gepinnt, darunter drei, die
vorher praktisch unerreichbar waren — **unlesbares Token** (`"abc"`, `""`, `"1.5"`,
Überlauf) beginnt bei 0 statt zu werfen, **Token jenseits des Endes** liefert eine
leere Seite, und **Seitengröße < 1** wird auf 1 angehoben (sonst stünde die
Pagination still, ohne dass ein Aufrufer den Grund sähe).

Zwei Verhaltensregeln sind zusätzlich festgehalten: der **Transformer wird nicht
gerufen**, wenn der Ausgangszustand unerlaubt ist (er soll keine Zustände sehen, aus
denen er nie hätte rechnen sollen), und ein **wiederholter Abbruch** behält den ersten
Grund samt Quelle und schreibt nicht erneut.

## Akzeptanzkriterien

- Je Sub-Slice wächst die **gemessene Fläche** des Moduls um die herausgelöste
  Entscheidungslogik, bei unverändertem Produktivverhalten (Contract-Suiten grün).
- Der Kover-Exclude der jeweiligen Klasse **bleibt** — er deckt danach nur noch
  SQL-Vollzug, was die ehrlichere Begründung ist als vorher.
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
