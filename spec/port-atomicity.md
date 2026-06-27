# Port-Atomicity-Vertraege

> **Cross-Refs**: [`spec/mcp-server.md`](./mcp-server.md) Abschnitt „Async-Jobs, Idempotency, Policy"

## Warum dieses Dokument existiert

Mehrere Hexagon-Ports tragen Operationen, deren
Korrektheit unter Concurrency und Crash-Recovery direkt von
Atomarem-Sequenzieren abhaengt. Die in-process [InMemoryStores][1]
benutzen `synchronized`-Bloecke und `ConcurrentHashMap.compute`-CAS, um
diese Eigenschaften zu erreichen — eine **persistente** Implementation
(JDBC-Backend, Redis, etc.) muss aequivalente Atomicity-Primitive
liefern, sonst entstehen die unten je Vertrag beschriebenen
Race-Conditions.

Dieses Dokument listet die kritischen Atomicity-Vertraege dieser Ports und
verlinkt zu den `*ContractTests`-Suiten, die jeden Implementor
durchlaufen MUSS.

[1]: ../hexagon/ports-common/src/testFixtures/kotlin/dev/dmigrate/server/ports/memory

---

## 1. `IdempotencyStore.reserve` — atomare Reserve

| Aspekt | Vertrag |
|---|---|
| **Operation** | `reserve(scope, payloadFingerprint, now)` |
| **Garantie** | Konkurrierende identische Reserves liefern genau **eines** `Reserved`-Outcome. Der Rest sieht `ExistingPending` mit derselben Lease. |
| **Race-Folgen** | Ohne Atomicity koennte eine zweite Reserve einen Job parallel starten — sichtbar als doppeltes Idempotency-Outcome `COMMITTED` mit verschiedenen `resultRef`. |
| **InMemory** | `ConcurrentHashMap.compute` |
| **Production** | DB-`SELECT … FOR UPDATE` + `INSERT … ON CONFLICT DO NOTHING` oder gleichwertig |
| **Contract-Test** | `IdempotencyStoreContractTests` — Test "parallel identical reserves yield exactly one Reserved" |

## 2. `IdempotencyStore.markAwaitingApproval` mit durabler Challenge

| Aspekt | Vertrag |
|---|---|
| **Operation** | `markAwaitingApproval(scope, now, challenge?)` |
| **Garantie** | Die `ApprovalChallenge` (approvalRequestId, requiredScopes, …) wird zusammen mit dem Statuswechsel `PENDING -> AWAITING_APPROVAL` durabel gespeichert. Spaetere `reserve()`-Calls liefern dieselbe Challenge im `AwaitingApproval`-Outcome zurueck. |
| **Race-Folgen** | Ohne durable Challenge: Approved-Retry hat keine echte Anti-Replay-Bindung; ein Grant fuer eine alte/erneuerte `approvalRequestId` ist nicht unterscheidbar. |
| **InMemory** | `Entry.challenge: ApprovalChallenge?` im selben Eintrag wie der Status. |
| **Production** | DB-Spalte `awaiting_approval_challenge_json` o.Ae. ODER Foreign-Key auf eine separate Challenge-Tabelle. Atomar zum Statuswechsel. |
| **Contract-Test** | `IdempotencyStoreContractTests` — Test "challenge persists across markAwaitingApproval -> reserve roundtrip" (siehe (E) unten). |

## 3. `JobStartTransaction.commit` — gemeinsame Idempotency+JobStore-Transaktion

| Aspekt | Vertrag |
|---|---|
| **Operation** | `commit(record, scope, now)` |
| **Garantie** | `idempotencyStore.commit(scope, jobId)` UND `jobStore.save(record)` gehen **gemeinsam sichtbar** auf den jeweiligen Stores. Es darf kein Zustand entstehen, in dem die Idempotenz committed ist, der Job aber nicht (oder umgekehrt). |
| **Race-Folgen** | Halbzustand: ein Caller sieht `Idempotency=COMMITTED(jobId)` aber `jobStore.findById(jobId) == null`. Replays liefern `AlreadyStarted(jobId)` — Klient probiert Status-Get, bekommt `RESOURCE_NOT_FOUND`. |
| **InMemory** | `synchronized(lock)` im `InMemoryJobStartTransaction.commit`; Idempotency-Commit ZUERST, dann Job-Save. |
| **Production** | Eine **gemeinsame DB-Transaktion** ueber beide Tabellen. Der Vertrag verlangt explizit: "wenn ein Backend keine gemeinsame Datenbanktransaktion bietet, muss der Adapter eine recoverable Saga oder gleichwertige atomare Primitive bereitstellen". |
| **Contract-Test** | `JobStartTransactionContractTests` — siehe `hexagon/ports-common/src/testFixtures` (Test "parallel commits yield exactly one Committed"; "rollback on save failure"). |

## 4. `OwnerAwareQuotaService.reserve` — Quota+OwnerStore-Kombi

| Aspekt | Vertrag |
|---|---|
| **Operation** | `reserve(key, amount, ownerId, leaseExpiresAt, now)` |
| **Garantie** | Ein erfolgreicher `delegate.reserve` (Counter +1) MUSS zusammen mit `ownerStore.register(ownerId, …)` atomar sichtbar werden. Sonst kann ein JVM-Crash zwischen den beiden Schritten den Slot dauerhaft leaken — ohne Owner-Eintrag findet der Sweeper ihn nie. |
| **Race-Folgen** | Slot-Leak: Counter steht permanent bei +1 ohne korrespondierenden Owner-Eintrag. Sweeper kann nicht refunden. |
| **InMemory** | `synchronized(this)` im `OwnerAwareQuotaService.reserve` umfasst beide Schritte. Fuer JVM-Crash bietet InMemory keine Garantien (alles weg). |
| **Production** | Gemeinsame DB-Transaktion ueber `quota_counters`-Tabelle und `quota_reservation_owners`-Tabelle. ODER Reserve-then-Rollback-Pattern: bei `register`-Fehler `delegate.refund` aufrufen. Letzteres ist anfaellig fuer Crash-zwischen-`reserve`-und-`register`; Volltransaktion ist die robuste Loesung. |
| **Contract-Test** | `QuotaReservationOwnerStoreContractTests` (siehe (E) unten) plus die `OwnerAwareQuotaService`-Tests, die parallele Reserves gegen einen niedrigen Limit fahren und keine Slot-Leaks pruefen. |

## 5. `QuotaReservationOwnerStore.markCommitted/markReleased/markRefunded` — exactly-once-CAS

| Aspekt | Vertrag |
|---|---|
| **Operation** | `markX(ownerId, now): QuotaReservationOwner?` |
| **Garantie** | Review-Fix #5: Status-Transition ist atomar gegen den vorherigen erlaubten Zustand. Bei Doppel-Aufruf gewinnt **genau einer** den CAS und bekommt den aktualisierten `QuotaReservationOwner` zurueck; alle anderen sehen `null` (no-op). |
| **Race-Folgen** | Ohne exactly-once: zwei concurrent `releaseForOwner`-Calls (z.B. Dispatcher + JobCancelService bei einem queued-Cancel) dekrementieren den Counter doppelt — Slot wird negativ, spaetere Reserves geben unrechtmaessig `Granted` zurueck. |
| **InMemory** | `ConcurrentHashMap.computeIfPresent` mit Status-Pruefung im Closure. |
| **Production** | DB-Update mit `WHERE status = <expected>`-Klausel und Affected-Rows-Check; alternativ `UPDATE … SET … RETURNING …` mit Konflikt-Erkennung. |
| **Contract-Test** | `QuotaReservationOwnerStoreContractTests` (siehe (E)). |

## 6. `JobStore.transitionStatus` / `markCancelRequested` — CAS-Statustransition

| Aspekt | Vertrag |
|---|---|
| **Operation** | `transitionStatus(tenant, jobId, allowedFrom, transformer)` |
| **Garantie** | Atomare CAS gegen `allowedFromStatuses`; nur erfolgreich wenn der heutige Status erlaubt ist. Ergebnis `IllegalTransition(currentStatus)` bei Race. |
| **Race-Folgen** | Ohne CAS: zwei gleichzeitige Updates koennen den Job-Status von QUEUED→CANCELLED **und** QUEUED→RUNNING wettlaufen lassen; einer dieser Pfade laeuft mit veralteten Annahmen weiter. |
| **InMemory** | `ConcurrentHashMap.compute` mit Status-Check im Closure. |
| **Production** | DB-`UPDATE … WHERE status IN (…)` mit Affected-Rows-Check. |
| **Contract-Test** | `JobStoreContractTests` — Test "transitionStatus is CAS-protected against parallel writes". |

---

## E. Contract-Test-Suiten (Pflicht fuer jeden Implementor)

Alle Contract-Tests sind als `abstract class … : FunSpec(…)` mit
einem `factory: () -> Port`-Parameter definiert. Implementoren leiten
ab und geben ihre Factory mit:

```kotlin
class MyDbBackedJobStartTransactionContractTest :
    JobStartTransactionContractTests(
        factory = { MyDbBackedJobStartTransaction(...) },
    )
```

Die Suiten muessen unveraendert durchlaufen, sonst ist die
Implementation **nicht** vertragskonform und fuehrt zu den im
jeweiligen Abschnitt oben dokumentierten Race-Folgen.

| Port | Suite | Pfad |
|---|---|---|
| `IdempotencyStore` | `IdempotencyStoreContractTests` | `hexagon/ports-common/src/testFixtures/…/contract/IdempotencyStoreContractTests.kt` |
| `JobStartTransaction` | `JobStartTransactionContractTests` | `hexagon/ports-common/src/testFixtures/…/contract/JobStartTransactionContractTests.kt` |
| `JobStore` | `JobStoreContractTests` | `hexagon/ports-common/src/testFixtures/…/contract/JobStoreContractTests.kt` |
| `QuotaStore` | `QuotaStoreContractTests` | `hexagon/ports-common/src/testFixtures/…/contract/QuotaStoreContractTests.kt` |
| `QuotaReservationOwnerStore` | `QuotaReservationOwnerStoreContractTests` | `hexagon/application/src/test/…/quota/QuotaReservationOwnerStoreContractTests.kt` |

---

## Anti-Pattern: was NICHT funktioniert

- **Reserve-then-Register ohne Rollback**: zwei separate Schritte ohne
  Tx-Klammer. Crash dazwischen leakt Slot. Wenn `register`-Fehler
  auftritt, ist `delegate.refund` der Cleanup-Pfad — aber ohne
  Tx-Garantie ist „Crash" auch ohne Exception moeglich.
- **find-then-update ohne CAS**: Klassischer Lost-Update-Bug. Zwei
  Caller lesen `status=COMMITTED`, beide schreiben `status=RELEASED`,
  beide rufen `delegate.release` (Counter -2 statt -1).
- **Optimistisches Locking ohne Affected-Rows-Check**: SQL-`UPDATE`
  ohne anschliessenden `rowCount == 1`-Check uebersieht Race-Verlierer.
  Implementoren MUESSEN den Affected-Rows-Wert pruefen.
- **JSON-Spalte fuer Idempotency-Challenge ohne Migration-Plan**:
  Das Feld wird neu eingefuehrt; existierende Eintraege haben es
  nicht. Migration: NULL-tolerant (Bestands-Eintraege liefern
  `challenge=null`, Approval-Retry faellt auf den E.6-(3a)-Workaround
  zurueck — dokumentiert).

---

## Cross-Refs

- [`spec/mcp-server.md` Abschnitt „Async-Jobs, Idempotency, Policy"](./mcp-server.md)
- Persistente Implementoren: Adapter-Modul `adapters/driven/persistence-jdbc`
