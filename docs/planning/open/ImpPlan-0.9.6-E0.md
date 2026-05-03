# Implementierungsplan: 0.9.6 - Phase E0 `Cancel-Gate fuer bestehende Runner`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: E0 (`Cancel-Gate fuer bestehende Runner`)
> **Status**: Draft (2026-05-03)
> **Referenz**: `docs/planning/in-progress/implementation-plan-0.9.6.md`
> Abschnitt 4.7, Abschnitt 4.9, Abschnitt 6.9, Abschnitt 8 Phase E0,
> Abschnitt 8 Phase E, Abschnitt 11 und Abschnitt 12;
> `docs/planning/done/ImpPlan-0.9.6-A.md`;
> `docs/planning/done/ImpPlan-0.9.6-B.md`;
> `docs/planning/in-progress/ImpPlan-0.9.6-C.md`;
> `docs/planning/open/ImpPlan-0.9.6-D.md`; `spec/ki-mcp.md`;
> `spec/job-contract.md`; `spec/architecture.md`; `spec/design.md`;
> `spec/cli-spec.md`; `hexagon/application`; `hexagon/ports-write`;
> `hexagon/profiling`; `adapters/driven/streaming`;
> `adapters/driving/cli`; `adapters/driving/mcp`.

---

## 1. Ziel

Phase E0 ist ein Vorab-Gate fuer die Cancel-Faehigkeit der bestehenden
langlaufenden Runner. Sie implementiert noch nicht das vollstaendige
MCP-Tool `job_cancel`, sondern klaert verbindlich, ob und wie Cancel in die
aktuellen Runner propagiert werden kann, ohne nach angenommenem Cancel neue
Artefakte zu publizieren oder neue Daten-Schreibabschnitte zu starten.

Phase E0 liefert konkrete technische Ergebnisse:

- Cancel-Spike fuer:
  - `SchemaReverseRunner`
  - Profiling-Runner/-Services
  - `DataImportRunner`
  - `DataTransferRunner`
  - betroffene Streaming-/Reader-/Writer-Pfade
- adapterneutraler Cancel-Vertrag fuer bestehende Runner:
  - `CancellationToken` oder aequivalenter Worker-Handle
  - kooperative Checkpoints
  - definierte Cancel-Exception bzw. Cancel-Result-Projektion
- dokumentierte Checkpoint- und Side-Effect-Matrix pro Runner
- Test-Fixtures, die Cancel-Propagation und Side-Effect-Stopp nachweisen
- Gate-Entscheidung, ob Phase E mit `job_cancel` fortgesetzt werden darf

Nach Phase E0 soll gelten:

- fuer jeden bestehenden Langlaeufer ist bekannt, wo Cancel geprueft werden
  muss
- fuer jeden Side Effect ist dokumentiert, ob er vor oder nach dem letzten
  Cancel-Checkpoint liegt
- der Milestone ist blockiert, wenn ein relevanter Pfad keine kooperative
  Cancel-Propagation mit Side-Effect-Stopp nachweisen kann
- reduzierte Semantik wie nur `cancel_requested`, Store-Status ohne Worker-
  Propagation oder "best effort ohne Side-Effect-Stopp" ist ausgeschlossen

Wichtig: Phase E0 ist ein Gate und Spike. Sie fuehrt kein neues MCP-Tool,
keine Policy-Grants, keine neuen Start-Tools und keine vollstaendige Async-
Job-Orchestrierung ein. Diese Umsetzung bleibt Phase E.

---

## 2. Ausgangslage

### 2.1 Masterplan fordert harte Cancel-Semantik

Der Masterplan entscheidet in Abschnitt 4.7:

- `job_cancel` ist kontrollierte Job-Steuerung, kein freier Kill-Schalter
- laufende Worker muessen ein `CancellationToken` oder einen aequivalenten
  Job-Worker-Handle beobachten
- Reverse, Profiling, Import und Transfer muessen kooperative Cancel-
  Checkpoints setzen
- nach angenommenem Cancel duerfen keine neuen Artefakte publiziert und keine
  weiteren Daten-Schreiboperationen begonnen werden
- bereits terminale Jobs bleiben terminal

Phase E0 prueft, ob diese Semantik mit den bestehenden Runnern realistisch und
testbar umsetzbar ist.

### 2.2 Phase E haengt von E0 ab

Phase E soll `job_cancel`, Start-Tools, Idempotenz und Policy produktiv
anbinden. Der Masterplan legt aber fest: Wenn der E0-Spike fuer einen der
genannten Langlaeufer keine kooperative Cancel-Propagation mit Side-Effect-
Stopp nachweisen kann, ist der Milestone blockiert und Phase E darf nicht als
fertig gelten.

### 2.3 Bestehende Runner sind CLI-gepraegt

Die relevanten Runner liegen heute vor allem in `hexagon/application` und
werden vom CLI genutzt:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaReverseRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ImportStreamingInvoker.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/TransferExecutor.kt`
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingImporter.kt`
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt`
- `hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/service/ProfileTableService.kt`
- `hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/service/ProfileDatabaseService.kt`
- driver-nahe Writer-Pfade unter `hexagon/ports-write` und `adapters/driven/driver-*`

Phase E0 darf diese Pfade analysieren und minimal instrumentieren, aber nicht
einen zweiten Async-Job-Runner neben dem d-migrate-Jobmodell einfuehren.

---

## 3. Scope fuer Phase E0

### 3.1 In Scope

- adapterneutraler Cancel-Grundvertrag:
  - `CancellationToken`
  - `CancellationTokenSource` oder aequivalenter Worker-Handle fuer Tests
  - `CancelledException` oder typisiertes Cancel-Result
- Spike-Integration in bestehende Runner-Signaturen, soweit fuer Tests noetig
- Checkpoint-Matrix pro Runner:
  - Start-/Preflight-Grenzen
  - Schleifen ueber Tabellen, Dateien, Chunks oder Queries
  - vor Artefakt-Publish
  - vor Import-/Transfer-Schreibabschnitten
  - vor Commit-/Finish-/Checkpoint-Persistenz
- Side-Effect-Matrix pro Runner:
  - Artefakte
  - Checkpoints
  - Datenbank-Schreiboperationen
  - Profiling-Reports
  - temporare Dateien
- Tests mit kontrollierbaren Fake-Ports/Fake-Writern, die Cancel an
  definierten Stellen ausloesen
- Gate-Dokumentation fuer Phase E:
  - Go
  - Blocked
  - Go mit klar benannten Nacharbeiten, sofern die harte Semantik trotzdem
    nachgewiesen ist

### 3.2 Nicht in Scope

- produktive MCP-Implementierung von `job_cancel`
- neue Start-Tools wie `schema_reverse_start`, `data_profile_start`,
  `schema_compare_start`, `data_import_start` oder `data_transfer_start`
- Policy-/Approval-Grant-Aussteller
- Idempotency-Zustandsautomat fuer Start-Tools
- REST/gRPC-Cancel-API
- parallele MCP-Tasks-Abstraktion
- hartes Thread-/Coroutine-Killing
- best-effort-Cancel ohne Side-Effect-Stopp

---

## 4. Leitentscheidungen

### 4.1 Cancel ist kooperativ

Runner werden nicht gewaltsam beendet. Cancel wird ueber einen Token oder
Worker-Handle beobachtet. Langlaufende Loops pruefen den Token an stabilen
Checkpoint-Stellen und beenden den Pfad kontrolliert.

Das gilt auch fuer lange, nicht-iterative Operationen. Jeder monolithische
I/O-, Driver- oder DB-Aufruf, der in E0 als potentiell langlaufend bewertet
wird, muss explizit klassifiziert werden:

- cancelbar ueber vorhandene Port-/Driver-API
- nicht cancelbar, aber atomar und ohne neue nachgelagerte Side Effects
- nicht cancelbar und blockierend fuer das E0-Gate

Bei `open`, Preflight, einzelner Import-/Transfer-API, langer Query oder
Writer-Initialisierung reicht ein Checkpoint nur vor und nach dem Aufruf nicht
aus, wenn der Aufruf selbst neue extern sichtbare Side Effects startet oder
unkontrolliert lange blockieren kann. In diesem Fall muss E0 entweder eine
kooperative Cancel-Grenze am Port/Adapter nachweisen oder den Pfad als
blockierend markieren.

Ein nicht-cancelbarer, aber atomarer Call ist nur gate-faehig, wenn alle
folgenden Schwellen eingehalten und dokumentiert sind:

- kein interner Retry-/Reconnect-Loop ohne hartes Timeout
- maximale erwartete Laufzeit und Timeout liegen innerhalb des E0-Cancel-
  Reaktionsbudgets
- nach Timeout oder Fehler startet der Runner keinen weiteren Side Effect
- der Call haelt keine offene Transaktion, Session, Sperre oder temporaere
  Ressource ueber das Timeout hinaus

Messbares E0-Cancel-Reaktionsbudget:

- kooperative Checkpoint-Pfade muessen nach gesetztem Cancel-Signal vor dem
  naechsten Side Effect reagieren; der Testnachweis muss deterministisch sein
  und darf nicht von Wall-Clock-Sleeping abhaengen.
- atomar-nicht-cancelbare monolithische Calls duerfen nach gesetztem Cancel-
  Signal hoechstens ein gebundenes Operation-Timeout von `<= 30s` verbrauchen.
- Retry-/Reconnect-Logik muss in dieses `<= 30s`-Gesamtbudget fallen; ein
  einzelnes Timeout pro Versuch reicht nicht, wenn mehrere Versuche moeglich
  sind.
- Wenn ein produktiver Driver heute kein solches Gesamtbudget konfigurieren
  oder messen kann, ist der Pfad `Blocked`.

Ohne belegtes Timeout-/Laufzeitfenster und Messnachweis gilt `atomar aber
nicht cancelbar` als `Blocked`, nicht als `Go mit Nacharbeiten`.

Verbindliche Folge:

- kein `Thread.stop`
- kein blindes Interrupt ohne fachlichen Cleanup-Vertrag
- kein Abbruch mitten in einem nicht atomaren Write ohne definierte
  Wiederaufnahme- oder Cleanup-Regel

### 4.2 Cancel-Checkpoints liegen vor neuen Side Effects

Der wichtigste Checkpoint ist nicht "irgendwann im Loop", sondern direkt vor
neuen extern sichtbaren Side Effects:

- vor Artefakt-Publish
- vor neuem Tabellen-/Chunk-Write
- vor Import-Commit oder Finish
- vor Transfer-Schreibabschnitt
- vor Profiling-Report-Persistenz
- vor Checkpoint-Persistenz, falls der Checkpoint einen Fortschritt nach dem
  Cancel vortaeuschen wuerde

Bereits begonnene atomare Operationen duerfen beendet werden, wenn ein
halbfertiger Abbruch inkonsistenter waere. Danach darf kein weiterer Side
Effect gestartet werden.

### 4.3 Bestehende CLI-Pfade bleiben lauffaehig

Phase E0 darf Runner-Signaturen um optionale Cancel-Parameter erweitern, muss
aber bestehende CLI-Aufrufe ohne Cancel-Token kompatibel halten.

Empfohlene Default-Semantik:

- CLI-Pfade verwenden `CancellationToken.none()` oder aequivalent
- Tests und spaetere Job-Worker uebergeben echte Tokens
- kein globaler Singleton-Token

### 4.4 Cancel ist kein neuer Jobstatus in E0

Phase E0 prueft Worker-Faehigkeit. Der produktive Jobstatus `cancelled` wird
erst in Phase E an `job_cancel`, Job-Store und Berechtigungspruefung
angebunden.

E0 darf in Tests typisierte Cancel-Ergebnisse oder Exceptions nutzen, aber
keine abweichenden Jobstatus einfuehren.

Trotzdem muss E0 einen minimalen Anschlussnachweis fuer Phase E liefern: Das
Cancel-Signal muss an jeder Runner-Grenze als eindeutig typisiertes Outcome
beobachtbar sein, so dass Phase E es ohne weitere Interpretation in
Status-Transition, Audit-Event und Worker-Handle-Cleanup ueberfuehren kann.
Eine reine Reduktion auf `cancel_requested` oder ein mehrdeutiger Fehlerpfad
gilt nicht als anschlussfaehig.

Der Anschlussnachweis bleibt in E0 dokumentativ und adapterneutral. E0 darf
keine produktive Cancel-Status-Verarbeitung, keine Store-Transition zu
`cancelled` und keine produktiven Audit-Events fuer `job_cancel` einfuehren.

---

## 5. Cancel-Vertrag

### 5.1 `CancellationToken`

Der adapterneutrale Vertrag soll klein bleiben:

```kotlin
interface CancellationToken {
    val isCancellationRequested: Boolean
    fun throwIfCancellationRequested()
}
```

Ergaenzend fuer Tests und Worker-Orchestrierung:

```kotlin
interface CancellationTokenSource {
    val token: CancellationToken
    fun cancel(reason: String? = null)
}
```

Die konkrete Paketlage ist im Spike zu entscheiden. Der Vertrag gehoert in
einen gemeinsamen, adapterneutralen Bereich, nicht in `adapters:driving:mcp`.

Der Token-Vertrag muss thread-/task-sicher sein. Ein Cancel, das aus einem
anderen Worker-Thread, Coroutine-Kontext oder MCP-Job-Controller ausgeloest
wird, muss fuer alle beobachtenden Runner mit atomic-/volatile-aequivalenter
Sichtbarkeit lesbar sein. `cancel(...)` muss idempotent sein; mehrere
Cancel-Aufrufe duerfen weder den ersten Grund verlieren noch neue Side Effects
ausloesen.

### 5.2 Cancel-Result

Runner muessen Cancel unterscheidbar von fachlichen Fehlern signalisieren.
Kanonischer Carrier fuer E0 ist eine zentrale, typisierte
`OperationCancelledException` aus dem adapterneutralen Cancel-Paket.

Erlaubte Form:

- `OperationCancelledException`

Abweichungen sind nur zulaessig, wenn eine bestehende Runner-Signatur bereits
ein geschlossenes Result-Modell erzwingt. Dann muss die Result-Variante in E0
am Runner-Rand unmittelbar und verlustfrei auf die kanonische
`OperationCancelledException` oder ein daraus eindeutig ableitbares
`Cancelled`-Outcome gemappt werden. Gemischte freie Carrier pro Runner oder
Port sind nicht erlaubt.

Nicht erlaubt:

- Cancel als generischer `RuntimeException`
- Cancel als normaler fachlicher Fehler mit Exit-Code-Mapping ohne
  Erkennbarkeit fuer den spaeteren Jobkern
- stilles Schlucken des Cancel-Signals
- mehrere gleichrangige Cancel-Signalformen ohne zentrale Mapping-Regel

### 5.3 Cleanup-Vertrag

Nach Cancel gilt:

- keine neuen Artefakte publizieren
- keine neuen Daten-Schreibabschnitte starten
- geoeffnete Writer/Sessions kontrolliert schliessen oder aborten, wenn der
  Port das unterstuetzt
- temporaere Dateien und teilweise Upload-/Artifact-Spools nicht als
  erfolgreiche Artefakte registrieren
- Audit-/Job-Ebene in Phase E darf Cancel als kontrolliertes Outcome
  erfassen

E0 muss fuer jeden betroffenen Writer, jede Session und jede temporaere
Datei-/Spool-Ressource einen harten Cleanup-Nachweis liefern. Tests duerfen
hier nicht nur auf fehlenden Erfolgs-Publish pruefen, sondern muessen
deterministisch assertieren, dass `close`, `abort`, Rollback oder
Temporaerdatei-Loeschung entsprechend dem jeweiligen Port-Vertrag aufgerufen
wurde. Wenn ein Port keine beobachtbare Cleanup-/Abort-Grenze anbietet, muss
dies als Risiko in der Gate-Entscheidung erscheinen und darf nur dann `Go mit
Nacharbeiten` sein, wenn keine offene Ressource, Transaktion oder sperrende
Session zurueckbleiben kann.

---

## 6. Runner-Matrix

### 6.1 `SchemaReverseRunner`

Zu pruefen:

- Connection-/Reader-Initialisierung
- Schema-Introspection-Loops
- DDL-/Report-Erzeugung
- Artefakt-/Dateipublish

Pflicht-Checkpoints:

- nach Preflight und vor Start der Introspection
- zwischen groben Introspection-Schritten, falls der Driver-Port das erlaubt
- vor jedem Artefakt- oder Dateipublish
- vor finaler Erfolgsprojektion

Spike-Abnahme:

- Cancel vor Publish erzeugt kein neues Reverse-Artefakt
- Cancel nach Preflight aber vor Introspection beendet den Runner als Cancel
- CLI-Default ohne Token bleibt unveraendert

### 6.2 Profiling-Runner/-Services

Betroffene Pfade:

- `DataProfileRunner`
- `ProfileTableService`
- `ProfileDatabaseService`
- dialect-spezifische Profiling-Adapter

Pflicht-Checkpoints:

- vor Datenbank-/Tabellen-Loop
- zwischen Tabellen
- vor teuren Profiling-Queries, soweit Port-Grenzen das zulassen
- vor Report-/Profile-Artefakt-Persistenz

Spike-Abnahme:

- Cancel zwischen Tabellen startet keine weitere Tabellenprofilierung
- Cancel vor Report-Persistenz publiziert keinen neuen Profiling-Report
- bereits abgeschlossene Query muss nicht hart unterbrochen werden, sofern vor
  dem naechsten Side Effect erneut geprueft wird

### 6.3 `DataImportRunner`

Betroffene Pfade:

- Import-Preflight
- `ImportStreamingInvoker`
- `StreamingImporter`
- `DataWriter` / `TableImportSession`
- Checkpoint-Persistenz

Pflicht-Checkpoints:

- nach Preflight und vor Writer-Open
- vor jeder neuen Tabelle
- vor jedem neuen Chunk-Write
- vor `finish`/Commit einer Tabelle, soweit der Writer-Port diese Grenze
  sichtbar macht
- vor Checkpoint-Persistenz, wenn der Checkpoint Fortschritt bestaetigt

Spike-Abnahme:

- Cancel vor erstem Chunk startet keinen Datenwrite
- Cancel zwischen Chunks startet keinen weiteren Chunk-Write
- Cancel vor finalem Finish startet kein weiteres Finish/Commit
- bereits begonnener atomarer Chunk-Write darf beendet werden; danach endet
  der Runner als Cancel

### 6.4 `DataTransferRunner`

Betroffene Pfade:

- Transfer-Preflight
- `TransferExecutor`
- Quell-Reader
- Ziel-Writer / `TableImportSession`
- typisierte Fortschritts- und Fehlerprojektionen

Pflicht-Checkpoints:

- nach Preflight und vor Ziel-Writer-Open
- vor Quell-Reader-Open bzw. Reader-Initialisierung
- vor jedem potentiell langen Source-Read-Call
- vor jeder neuen Tabelle
- vor jedem neuen Transfer-Chunk
- vor Ziel-Write
- vor Finish/Commit
- vor Fortschritts-Checkpoint, falls vorhanden

Spike-Abnahme:

- Quell-Reader-Open und Source-Read-Calls sind als cancelbar,
  atomar-nicht-cancelbar oder blockierend klassifiziert, inklusive
  Timeout-/Retry-Verhalten
- Cancel vor Ziel-Write startet keine Ziel-Schreiboperation
- Cancel zwischen Chunks verhindert weitere Ziel-Chunks
- Cancel zwischen Tabellen verhindert die naechste Tabelle
- Runner liefert erkennbares Cancel-Ergebnis statt Erfolg mit Teildaten

### 6.5 Streaming-/Reader-/Writer-Pfade

Betroffene Ports und Adapter:

- `StreamingImporter`
- `StreamingExporter`, falls spaetere Start-Tools ihn als Langlaeufer nutzen
- Quell-Reader-/Export-Ports im Transfer-Pfad
- `DataChunkWriter`
- `DataWriter`
- `TableImportSession`
- driver-spezifische Reader und Writer in `adapters/driven/driver-*`

E0 muss entscheiden, wo Cancel-Pruefung liegt:

- im Runner vor Port-Aufrufen
- im Streaming-Adapter zwischen Chunks
- verpflichtend in Reader-/Writer-/Driver-Ports, wenn ein einzelner
  Port-Aufruf selbst lange laufen kann, Ressourcen blockiert oder mehrere neue
  Side Effects startet

Spike-Abnahme:

- dokumentierte Entscheidung pro Port-Grenze
- Fake-Reader-Test beweist: nach Cancel wird kein weiterer Source-Read-Call
  gestartet
- Fake-Writer-Test beweist: nach Cancel wird kein weiterer `write`-Aufruf
  gestartet
- keine stille Semantik, bei der ein Writer nach Cancel weiter neue Chunks
  annimmt
- gleiche Cancel-Erwartung fuer alle Adapter, die denselben Port-Vertrag
  implementieren; adapterspezifische Abweichungen muessen begruendet und im
  Gate als Risiko bewertet werden
- monolithische Port-Aufrufe sind als cancelbar, atomar-nicht-cancelbar oder
  blockierend klassifiziert

---

## 7. Umsetzungsschritte

### 7.1 AP E0.1: Cancel-Vertrag platzieren

- Paket fuer adapterneutralen `CancellationToken` festlegen.
- `CancellationToken.none()` oder aequivalent fuer bestehende CLI-Pfade
  bereitstellen.
- Token-Implementierung mit atomic-/volatile-aequivalenter Sichtbarkeit und
  idempotentem `cancel(...)` bereitstellen.
- Test-Token mit deterministischen Hooks bereitstellen:
  - Cancel nach N Checkpoints
  - Cancel vor naechstem Side Effect
  - sofort gecancelter Token
- Kanonische `OperationCancelledException` definieren; Result-basierte Runner
  duerfen nur ueber eine zentrale Mapping-Regel angebunden werden.

Tests:

- `none()` ist nie gecancelt
- gecancelter Token wirft typisierte Cancel-Signalisierung
- Cancel aus anderem Thread/Task wird deterministisch sichtbar
- mehrfaches `cancel(...)` ist idempotent
- Hook-Token kann Checkpoints deterministisch ausloesen

### 7.2 AP E0.2: Side-Effect-Inventar erstellen

- Pro Runner alle extern sichtbaren Side Effects dokumentieren.
- Pro Side Effect festhalten:
  - Startpunkt
  - atomar oder mehrstufig
  - Cleanup-/Abort-Moeglichkeit
  - benoetigter Cancel-Checkpoint davor
- Ergebnis in diesem Plan oder einer separaten Tabelle unter
  `docs/planning/open/` nachziehen.

Pflichtschema fuer die Checkpoint-/Side-Effect-Matrix:

| Feld | Pflichtinhalt |
| --- | --- |
| `runner` | betroffener Runner/Service/Adapter |
| `pipeline` | fachlicher Pfad, z. B. Reverse-Publish oder Import-Chunk |
| `operation` | konkreter Call oder Schritt |
| `operation_type` | `loop`, `side_effect`, `monolithic_call`, `cleanup` |
| `side_effect` | Artefakt, Source-Read, DB-Write, Checkpoint, Spool, Session oder `none` |
| `checkpoint_before` | konkrete Code-/Port-Grenze vor dem Side Effect |
| `cancel_reaction` | `OperationCancelledException`/zentrales Mapping, kein weiterer Read/Write, abort/close usw. |
| `atomicity` | `atomic`, `multi_step`, `unknown` |
| `timeout_or_bound` | Timeout, Laufzeitgrenze oder begruendetes `not_applicable` |
| `cancel_budget_ms` | `<=30000` fuer atomar-nicht-cancelbare Calls oder `not_applicable` |
| `measurement_evidence` | Testname, Fake-Clock-/Timeout-Nachweis oder `missing` |
| `cleanup_contract` | `close`, `abort`, Rollback, Delete oder `not_needed` |
| `test_coverage` | Testname oder `missing` |
| `gate_result` | `go`, `go_followup`, `blocked` |
| `followup` | nur fuer nicht-blockierende Nacharbeit, sonst leer |

Tests/Gate:

- Inventar deckt Reverse, Profiling, Import, Transfer und Streaming-/Reader-/
  Writer-Pfade ab
- kein Side Effect ohne vorherigen Cancel-Checkpoint bleibt unbegruendet
- jede Matrix-Zeile mit `test_coverage = missing`, `atomicity = unknown` oder
  fehlendem `timeout_or_bound` ist automatisch `blocked`, sofern ein externer
  Side Effect oder ein potentiell langer monolithischer Call betroffen ist
- jede atomar-nicht-cancelbare Matrix-Zeile ohne `cancel_budget_ms <= 30000`
  oder ohne `measurement_evidence` ist automatisch `blocked`

### 7.3 AP E0.3: Runner-Signaturen minimal erweitern

- `SchemaReverseRunner`, `DataProfileRunner`, `DataImportRunner` und
  `DataTransferRunner` um optionalen Cancel-Token erweitern.
- Bestehende CLI-Kommandos uebergeben Default-Token.
- Tests/Fakes koennen echte Tokens uebergeben.
- Keine MCP-Abhaengigkeit in Runnern einfuehren.

Tests:

- bestehende Runner-Tests bleiben ohne expliziten Token lauffaehig
- neuer Cancel-Token wird in den jeweiligen Pfad propagiert

### 7.4 AP E0.4: Checkpoints in read-/artefaktnahe Pfade setzen

- `SchemaReverseRunner` und Profiling-Pfade instrumentieren.
- Checkpoints vor Artefakt-/Report-Publish setzen.
- Profiling-Loops zwischen Tabellen/Scopes pruefen.

Tests:

- Cancel vor Publish erzeugt kein Artefakt
- Cancel zwischen Profiling-Tabellen startet keine weitere Tabelle
- Cancel wird als Cancel und nicht als fachlicher Fehler gemeldet

### 7.5 AP E0.5: Checkpoints in Import- und Transfer-Pfade setzen

- `DataImportRunner`, `ImportStreamingInvoker`, `StreamingImporter`,
  `DataTransferRunner` und `TransferExecutor` instrumentieren.
- Checkpoints vor Tabelle, Quell-Reader-Open, Source-Read-Call, Chunk,
  Ziel-Write, Finish/Commit und Checkpoint-Persistenz setzen.
- Falls ein Port-Aufruf lange laufen kann oder selbst mehrere Side Effects
  startet, muss E0 den Port-/Adapter-Vertrag schaerfen oder den Pfad fuer das
  Gate blockieren; eine reine Phase-E-Notiz reicht nicht.
- Cancel-Pruefung an Runner-Grenzen ist Mindeststandard. Port-/Adapterebene
  muss zusaetzlich pruefen, sobald sie eigenstaendig Source-Reads, Chunks,
  Writes, Commits, Spools oder Sessions sequenziert.

Tests:

- Cancel vor Quell-Reader-Open startet keine Reader-Initialisierung
- Cancel vor Source-Read-Call startet keinen weiteren Source-Read
- Cancel vor erstem Write startet keinen Write
- Cancel zwischen Chunks startet keinen weiteren Write
- Cancel vor Finish/Commit startet kein Finish/Commit
- Fake-Writer zaehlt Aufrufe und beweist Side-Effect-Stopp

### 7.6 AP E0.6: Gate-Entscheidung dokumentieren

- Ergebnisse pro Runner zusammenfassen:
  - Checkpoints nachgewiesen
  - Side Effects gestoppt
  - monolithische Calls klassifiziert und ggf. cancelbar gemacht
  - Cleanup-/Abort-Assertions nachgewiesen
  - typisiertes Cancel-Outcome fuer Phase E anschlussfaehig
  - offene Risiken
  - benoetigte Phase-E-Nacharbeiten
- Gate-Status setzen:
  - `Go`
  - `Blocked`
  - `Go mit Nacharbeiten`
- Bei `Blocked` konkrete blockierende Pfade und fehlende Tests nennen.

Gate-Regel:

- `Go` ist nur erlaubt, wenn alle relevanten Runner, Streaming-Pfade,
  Writer-/Session-Grenzen, Artefakt-/Spool-Pfade und bekannten monolithischen
  Calls die harte Semantik aus Abschnitt 9 erfuellen.
- `Go mit Nacharbeiten` ist nur erlaubt, wenn die harte Side-Effect- und
  Cleanup-Semantik bereits nachgewiesen ist und die Nacharbeiten keine
  zusaetzliche Cancel-Interpretation, keinen neuen Port-Vertrag und keine
  ungetestete Side-Effect-Pipeline betreffen.
- `Blocked` ist Pflicht, sobald ein relevanter Pfad keinen deterministischen
  Cancel-Stopp, keine Cleanup-Assertion, kein typisiertes Cancel-Outcome oder
  keine belastbare Klassifikation eines langen monolithischen Calls nachweist.

Zulaessige `Go mit Nacharbeiten`-Nacharbeiten sind ausschliesslich:

- Dokumentationsverdichtung ohne neue technische Semantik
- zusaetzliche positive Tests fuer bereits abgedeckte Pfade
- Refactoring bereits nachgewiesener Checkpoint-Platzierung ohne
  Vertragsaenderung
- dokumentativer Phase-E-Anschluss von bereits typisierten Cancel-Outcomes an
  Status und Audit

Der dokumentative Phase-E-Anschluss ist ausschliesslich eine Mapping-Tabelle
oder Gate-Notiz. Er darf in E0 keine Job-Store-Schreibpfade, keine
Status-Transitionen, keine Audit-Emissionen und keine produktive
`job_cancel`-Wiring-Logik enthalten.

Nicht zulaessig fuer `Go mit Nacharbeiten` und daher `Blocked`:

- fehlende Tests fuer eine Side-Effect-Pipeline
- unbekannte Atomaritaet oder fehlende Timeout-/Laufzeitgrenze
- noch zu definierende Port-/Adapter-Cancel-Vertraege
- unbewiesener Cleanup fuer Writer, Sessions, Transaktionen oder Spools
- mehrdeutige Cancel-Signalisierung, die Phase E interpretieren muesste

---

## 8. Teststrategie

E0-Tests sind keine reinen Smoke-Tests. Sie muessen deterministisch beweisen,
dass Cancel vor Side Effects greift.

Pflichtmuster:

- Fake-Port mit Aufrufzaehlern
- Token, der an definierter Checkpoint-Nummer cancel ausloest
- Assertion, dass nach Cancel keine weiteren Side-Effect-Aufrufe passieren
- Assertion, dass Cancel typisiert propagiert wird
- Assertion, dass Writer/Sessions/Spools deterministisch geschlossen,
  abgebrochen, zurueckgerollt oder geloescht wurden
- Regressionstest, dass Default-Token bestehendes Verhalten nicht veraendert

Mindesttests:

- `SchemaReverseRunner`: Cancel vor Publish
- `DataProfileRunner` / Profiling-Service: Cancel zwischen Tabellen und vor
  Report-Persistenz
- `DataImportRunner`: Cancel vor erstem Chunk, zwischen Chunks und vor Finish
- `DataTransferRunner`: Cancel vor Quell-Reader-Open, vor Source-Read-Call,
  vor Ziel-Write und zwischen Chunks
- Streaming-/Reader-Fake: nach Cancel kein weiterer Source-Read
- Streaming-/Writer-Fake: nach Cancel kein weiterer `write`
- Cleanup-Fake: nach Cancel sind offene Writer/Sessions beendet und temporaere
  Spools nicht als Erfolg registriert
- monolithischer Call-Fake: langer Port-Aufruf ist cancelbar, nachweislich
  atomar ohne nachgelagerte Side Effects oder blockiert das Gate

---

## 9. Abnahmekriterien

- Fuer `SchemaReverseRunner`, Profiling, `DataImportRunner`,
  `DataTransferRunner` und betroffene Streaming-/Reader-/Writer-Pfade
  existiert eine dokumentierte Checkpoint- und Side-Effect-Matrix.
- Ein adapterneutraler Cancel-Vertrag existiert und wird nicht im MCP-Adapter
  definiert.
- Bestehende CLI-Pfade bleiben ohne expliziten Cancel-Token kompatibel.
- `CancellationToken` und `CancellationTokenSource` garantieren thread-/
  task-sichere Sichtbarkeit und idempotentes Cancel.
- Cancel vor Artefakt-/Report-Publish verhindert neue Artefakte bzw. Reports.
- Cancel vor Quell-Reader-Open oder Source-Read-Call verhindert neue
  Transfer-Reads bzw. klassifiziert den Call nach Abschnitt 4.1.
- Cancel vor Import-/Transfer-Write verhindert neue Daten-Schreibabschnitte.
- Cancel zwischen Chunks oder Tabellen verhindert den naechsten Chunk bzw. die
  naechste Tabelle.
- Bereits begonnene atomare Operationen duerfen kontrolliert fertiglaufen,
  danach startet kein weiterer Side Effect.
- Cancel wird typisiert als Cancel signalisiert und nicht als generischer
  fachlicher Fehler verschleiert.
- `OperationCancelledException` ist der kanonische E0-Carrier; jede
  Result-basierte Abweichung ist zentral gemappt und begruendet.
- Das typisierte Cancel-Outcome ist so eindeutig, dass Phase E daraus ohne
  weitere fachliche Interpretation `cancelled`-Status, Audit-Event und
  Worker-Handle-Cleanup ableiten kann.
- Writer, Sessions, Transaktionen, temporaere Dateien und Spools haben
  deterministische Cleanup-/Abort-Assertions oder sind begruendet nicht
  betroffen.
- Lange monolithische Calls wie `open`, Preflight, einzelne Import-/Transfer-
  APIs, lange Queries und Writer-Initialisierung sind pro Pfad als cancelbar,
  atomar-nicht-cancelbar oder blockierend klassifiziert.
- Atomar-nicht-cancelbare Calls haben ein belegtes Timeout-/Laufzeitfenster,
  ein gemessenes E0-Cancel-Reaktionsbudget von `<= 30s`, keine ungebundenen
  Retry-/Reconnect-Loops und hinterlassen nach Timeout keine offenen
  Ressourcen.
- Die Checkpoint-/Side-Effect-Matrix nutzt das Pflichtschema aus Abschnitt 7.2
  und enthaelt keine `missing`-/`unknown`-Felder fuer gate-relevante Pfade.
- Tests beweisen fuer jeden relevanten Runner mindestens einen Cancel-Punkt vor
  einem extern sichtbaren Side Effect.
- Tests oder Gate-Matrix beweisen fuer jede relevante Side-Effect-Pipeline
  vollstaendige Abdeckung; teilweise Abdeckung reicht nicht fuer `Go`.
- Reduzierte Semantik wie nur `cancel_requested`, Store-Status ohne Worker-
  Propagation oder best effort ohne Side-Effect-Stopp erfuellt E0 nicht.
- Wenn ein relevanter Langlaeufer keine kooperative Cancel-Propagation mit
  Side-Effect-Stopp nachweisen kann, ist der 0.9.6-Milestone blockiert und
  Phase E darf nicht als fertig gelten.

---

## 10. Anschluss an Phase E

Phase E darf erst auf E0 aufbauen, wenn das Gate mindestens `Go mit
Nacharbeiten` erreicht und die harte Semantik aus Abschnitt 9 nicht verletzt
ist.

Phase E implementiert danach:

- `job_cancel` als MCP-Tool
- Berechtigungspruefung fuer eigene und erlaubte Jobs
- Jobstatus-Transition zu `cancelled`
- Worker-Handle-Registry oder aequivalente Job-Orchestrierung
- Cancel-Annahme im Jobkern
- Audit-Events fuer Cancel-Requests und Cancel-Outcomes
- Einbindung der in E0 nachgewiesenen Runner-Checkpoints in produktive
  Langlaeufer

Phase E darf keine reduzierte `cancel_requested`-Semantik als Ersatz fuer
Worker-Propagation verwenden.

Der E0-Abschluss muss Phase E ausserdem eine eindeutige Abbildung liefern:

- welches Runner-Cancel-Outcome in `cancelled` ueberfuehrt wird
- an welcher Grenze Audit fuer Cancel-Annahme und Cancel-Abschluss entsteht
- welche Worker-/Token-Handles nach Cancel freigegeben werden
- welche offenen Phase-E-Nacharbeiten keine neue Side-Effect-Semantik
  einfuehren

Diese Abbildung ist in E0 nur Vertrag und Gate-Artefakt. Produktive
Status-Transitionen, Job-Store-Mutationen und Audit-Emissionen bleiben
vollstaendig Phase E.

Als E0-Artefakt ist nur eine nicht-ausfuehrende Mapping-Tabelle erlaubt. Jede
Implementierung, die bereits `cancelled` persistiert, Audit-Events emittiert
oder `job_cancel` produktiv verdrahtet, ist Scope-Drift und gehoert nicht in
E0.
