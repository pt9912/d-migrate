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
  - betroffene Streaming-/Writer-Pfade
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

### 5.2 Cancel-Result

Runner muessen Cancel unterscheidbar von fachlichen Fehlern signalisieren.
Erlaubte Formen:

- typisierte `OperationCancelledException`
- typisiertes Runner-Result wie `Cancelled`

Nicht erlaubt:

- Cancel als generischer `RuntimeException`
- Cancel als normaler fachlicher Fehler mit Exit-Code-Mapping ohne
  Erkennbarkeit fuer den spaeteren Jobkern
- stilles Schlucken des Cancel-Signals

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
- vor jeder neuen Tabelle
- vor jedem neuen Transfer-Chunk
- vor Ziel-Write
- vor Finish/Commit
- vor Fortschritts-Checkpoint, falls vorhanden

Spike-Abnahme:

- Cancel vor Ziel-Write startet keine Ziel-Schreiboperation
- Cancel zwischen Chunks verhindert weitere Ziel-Chunks
- Cancel zwischen Tabellen verhindert die naechste Tabelle
- Runner liefert erkennbares Cancel-Ergebnis statt Erfolg mit Teildaten

### 6.5 Streaming-/Writer-Pfade

Betroffene Ports und Adapter:

- `StreamingImporter`
- `StreamingExporter`, falls spaetere Start-Tools ihn als Langlaeufer nutzen
- `DataChunkWriter`
- `DataWriter`
- `TableImportSession`
- driver-spezifische Writer in `adapters/driven/driver-*`

E0 muss entscheiden, wo Cancel-Pruefung liegt:

- im Runner vor Port-Aufrufen
- im Streaming-Adapter zwischen Chunks
- optional in Writer-Ports, falls ein einzelner Port-Aufruf selbst lange
  laufen kann

Spike-Abnahme:

- dokumentierte Entscheidung pro Port-Grenze
- Fake-Writer-Test beweist: nach Cancel wird kein weiterer `write`-Aufruf
  gestartet
- keine stille Semantik, bei der ein Writer nach Cancel weiter neue Chunks
  annimmt

---

## 7. Umsetzungsschritte

### 7.1 AP E0.1: Cancel-Vertrag platzieren

- Paket fuer adapterneutralen `CancellationToken` festlegen.
- `CancellationToken.none()` oder aequivalent fuer bestehende CLI-Pfade
  bereitstellen.
- Test-Token mit deterministischen Hooks bereitstellen:
  - Cancel nach N Checkpoints
  - Cancel vor naechstem Side Effect
  - sofort gecancelter Token
- Cancel-Exception oder Cancel-Result definieren.

Tests:

- `none()` ist nie gecancelt
- gecancelter Token wirft typisierte Cancel-Signalisierung
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

Tests/Gate:

- Inventar deckt Reverse, Profiling, Import, Transfer und Streaming-/Writer-
  Pfade ab
- kein Side Effect ohne vorherigen Cancel-Checkpoint bleibt unbegruendet

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
- Checkpoints vor Tabelle, Chunk, Ziel-Write, Finish/Commit und Checkpoint-
  Persistenz setzen.
- Falls ein Port-Aufruf lange laufen kann, dokumentieren, ob Phase E
  zusaetzliche Port-Erweiterung braucht.

Tests:

- Cancel vor erstem Write startet keinen Write
- Cancel zwischen Chunks startet keinen weiteren Write
- Cancel vor Finish/Commit startet kein Finish/Commit
- Fake-Writer zaehlt Aufrufe und beweist Side-Effect-Stopp

### 7.6 AP E0.6: Gate-Entscheidung dokumentieren

- Ergebnisse pro Runner zusammenfassen:
  - Checkpoints nachgewiesen
  - Side Effects gestoppt
  - offene Risiken
  - benoetigte Phase-E-Nacharbeiten
- Gate-Status setzen:
  - `Go`
  - `Blocked`
  - `Go mit Nacharbeiten`
- Bei `Blocked` konkrete blockierende Pfade und fehlende Tests nennen.

---

## 8. Teststrategie

E0-Tests sind keine reinen Smoke-Tests. Sie muessen deterministisch beweisen,
dass Cancel vor Side Effects greift.

Pflichtmuster:

- Fake-Port mit Aufrufzaehlern
- Token, der an definierter Checkpoint-Nummer cancel ausloest
- Assertion, dass nach Cancel keine weiteren Side-Effect-Aufrufe passieren
- Assertion, dass Cancel typisiert propagiert wird
- Regressionstest, dass Default-Token bestehendes Verhalten nicht veraendert

Mindesttests:

- `SchemaReverseRunner`: Cancel vor Publish
- `DataProfileRunner` / Profiling-Service: Cancel zwischen Tabellen und vor
  Report-Persistenz
- `DataImportRunner`: Cancel vor erstem Chunk, zwischen Chunks und vor Finish
- `DataTransferRunner`: Cancel vor Ziel-Write und zwischen Chunks
- Streaming-/Writer-Fake: nach Cancel kein weiterer `write`

---

## 9. Abnahmekriterien

- Fuer `SchemaReverseRunner`, Profiling, `DataImportRunner`,
  `DataTransferRunner` und betroffene Streaming-/Writer-Pfade existiert eine
  dokumentierte Checkpoint- und Side-Effect-Matrix.
- Ein adapterneutraler Cancel-Vertrag existiert und wird nicht im MCP-Adapter
  definiert.
- Bestehende CLI-Pfade bleiben ohne expliziten Cancel-Token kompatibel.
- Cancel vor Artefakt-/Report-Publish verhindert neue Artefakte bzw. Reports.
- Cancel vor Import-/Transfer-Write verhindert neue Daten-Schreibabschnitte.
- Cancel zwischen Chunks oder Tabellen verhindert den naechsten Chunk bzw. die
  naechste Tabelle.
- Bereits begonnene atomare Operationen duerfen kontrolliert fertiglaufen,
  danach startet kein weiterer Side Effect.
- Cancel wird typisiert als Cancel signalisiert und nicht als generischer
  fachlicher Fehler verschleiert.
- Tests beweisen fuer jeden relevanten Runner mindestens einen Cancel-Punkt vor
  einem extern sichtbaren Side Effect.
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
