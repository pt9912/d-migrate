# Follow-up-Plan: MCP Testdata-Execute, AI-Approval-Details und Bundle-Import

> Status: Draft (2026-05-07)
>
> Kontext: 0.9.6 hat die MCP-Phasen B bis G produktiv gemacht, laesst aber
> drei echte Produkt-/Wire-Erweiterungen bewusst offen. Dieser Plan sammelt
> nur diese offenen Punkte. Bereits entschiedene Adapter- und
> Persistenz-Details werden nicht erneut geoeffnet.

---

## 1. Scope

Dieser Plan umfasst drei Follow-ups:

1. `testdata_execute`: produktiv als Testdaten-Artefaktgenerator
   implementieren; der eigentliche DB-Write bleibt bei `data_import_start`.
2. AI-Approval-Challenge-Details: KI-Tool-Handler geben bei
   `POLICY_REQUIRED` dieselben strukturierten Detailfelder aus wie die
   Job-/Upload-Pfade.
3. Bundle-/Mehrtabellen-Import: `data_import_start` bekommt einen definierten
   Mehrtabellenvertrag, statt `tables` dauerhaft pauschal abzulehnen.

Nicht-Ziele:

- `JdbcTransactionRunner` in einen Hexagon-Port ziehen oder wieder
  modul-`internal` machen.
- `jobs.managed_job` auf reines `ManagedJob`-JSONB zurueckbauen.
- `preAbortState` oder `preAbortBytes` in den Abort-Fingerprint aufnehmen.
- `AiToolOutcomeStore` ersetzen. Single-Writer-Lease, `InProgress` fuer
  parallele Pending-Reserves und Terminal-Replay sind die geloeste
  G.6-Anforderung.

---

## 2. Ausgangslage

### 2.1 `testdata_execute`

`testdata_plan` erzeugt heute einen Plan-Artefakt
(`wireArtifactKind=testdata-plan`, `aiIntent=testdata_plan`). Der Slot
`testdata_execute` ist registriert, bleibt aber `UnsupportedToolHandler`.
Das ist korrekt fuer 0.9.6, verhindert aber einen vollstaendigen
Testdaten-Workflow ueber MCP. Der Follow-up schliesst diese Luecke ohne
direkten Datenbank-Write: `testdata_execute` materialisiert ein importierbares
Testdaten-Artefakt, das anschliessend explizit ueber `data_import_start`
geschrieben wird.

### 2.2 AI-Approval-Challenge-Details

Job-Start-Handler projizieren `PolicyDecision.RequiresApproval` bereits als
`POLICY_REQUIRED` mit Details:

- `approvalRequestId`
- `correlationKind`
- `correlationKey`
- `requiredScopes`
- `reasons`

Die KI-Tool-Handler liefern heute nur generisches `POLICY_REQUIRED`. Dadurch
koennen Clients den gleichen Approval-Flow nicht transportneutral anzeigen
oder fortsetzen.

### 2.3 Bundle-/Mehrtabellen-Import

`data_import_start` erlaubt Single-File-Importe ueber `table`. `tables` wird
bewusst abgelehnt, weil bisher kein Bundle-Format definiert ist. Der
Runner-Pfad kann SchemaRef-Preflight und Tabellenreihenfolge bereits
einhaengen; es fehlt der Wire- und Artefaktvertrag fuer die Zuordnung von
mehreren Dateien/Streams zu Tabellen.

---

## 3. AP 1: AI-Approval-Challenge-Details

Prioritaet: hoch. Der Scope ist klein, und bestehende Error-Envelope-Patterns
koennen wiederverwendet werden.

### Ziel

`procedure_transform_plan`, `procedure_transform_execute` und `testdata_plan`
geben bei `PolicyDecision.RequiresApproval` vollstaendige Challenge-Details
aus, ohne Provider, Quota oder Artefakt-Publish anzustossen.

### Vertrag

Error-Code:

- `POLICY_REQUIRED`

Details:

- `approvalRequestId`: aus `PolicyDecision.RequiresApproval`
- `correlationKind`: `APPROVAL_KEY`
- `correlationKey`: der jeweilige `approvalKey`
- `requiredScopes`: deterministisch sortiert, kommagetrennt
- `reasons`: deterministisch serialisiert, analog zu
  `JobStartHandlerSupport`

Diese Feldnamen sind verbindlich. Die KI-Tool-Projektion darf nicht auf
mehrere Detail-Eintraege `requiredScope`/`reason` ausweichen, solange die
Job-/Upload-Pfade `requiredScopes` und `reasons` als aggregierte Details
liefern.

### Umsetzungshinweise

- Gemeinsamen Mapper fuer `PolicyDecision.RequiresApproval` in Betracht
  ziehen, statt die Detailprojektion in drei Handlern zu duplizieren.
- Bestandsabweichung vor Umsetzung explizit pruefen: Falls ein vorhandener
  KI-Approval-Mapper bereits `requiredScope`/`reason` als wiederholte
  Singular-Details emittiert, ist das kein kompatibler Endzustand dieses AP.
  Der Mapper muss auf die aggregierten Felder `requiredScopes` und `reasons`
  umgestellt werden.
- `approvalToken` darf nicht in Payload-Fingerprint, Audit-Refs oder
  Provider-Prompt gelangen.
- Reihenfolge bleibt: Form-Validation, Scope-Gate, Acquire, Source-Resolution,
  Policy.
- Entscheidung: `POLICY_REQUIRED` fuer KI-Tools ist ein nicht-committender
  Challenge-Pfad. Ein fehlendes Approval darf nicht als terminales
  `AiToolOutcomeStore`-Outcome replayt werden, weil ein Retry mit
  `approvalToken` sonst an einem alten `POLICY_REQUIRED` haengen bleiben
  koennte. Der Orchestrator muss die aktive Claim-Lease freigeben oder einen
  expliziten Challenge-Status modellieren, ohne Provider-Aufruf und ohne
  Artefakt-Publish.

### Tests

- Je ein Handler-Test fuer `procedure_transform_plan`,
  `procedure_transform_execute` und `testdata_plan`.
- Details enthalten alle Pflichtfelder und sortierte `requiredScopes`.
- Challenge-Details verwenden exakt `requiredScopes` und `reasons`, nicht
  wiederholte Singular-Felder.
- Negative Assertion: In keinem der drei KI-Handler duerfen Detail-Keys
  `requiredScope` oder `reason` im `POLICY_REQUIRED`-Envelope vorkommen.
- Retry-Pfad: erster Aufruf ohne `approvalToken` liefert `POLICY_REQUIRED`;
  zweiter Aufruf mit gleichem `approvalKey`, identischem Payload und gueltigem
  Grant laeuft weiter bis Provider-/Artefakt-Pfad, statt die alte Challenge zu
  replayen.
- `POLICY_DENIED`, Hygiene-Block, Provider-Fehler und Quota-Fehler bleiben
  unveraendert.
- Kein Provider-/Quota-/Artifact-Store-Aufruf bei `POLICY_REQUIRED`.

### Akzeptanz

- MCP-Clients erhalten fuer KI-Tools dieselbe Approval-Challenge-Form wie fuer
  Job-/Upload-Pfade.
- Bestehende Golden-/Tool-Schema-Snapshots bleiben kompatibel; nur Error-Details
  erweitern die Runtime-Antwort.

---

## 4. AP 2: Bundle-/Mehrtabellen-Import

Prioritaet: mittel. Das ist ein Wire- und Runner-Vertrag, nicht nur eine
Handler-Freischaltung.

### Ziel

`data_import_start` unterstuetzt `tables`, wenn ein explizites Bundle-Format
die Aufloesung von Artefaktinhalt zu Tabellen deterministisch macht.
Ohne Bundle-Format bleibt `tables` weiterhin `VALIDATION_ERROR`.

### Entscheidungsbedarf

Vor Implementierung festlegen:

- Bundle-Container: ZIP/TAR-Artefakt oder Verzeichnis-Spool aus einem
  archivierten Upload.
- Manifest-Format: verpflichtend fuer Version 1. JSON/YAML mit Tabellenname,
  relativer Datei, Format und optionalen per-table Optionen.
- Ob ein Bundle pro Tabelle ein eigenes Datenformat erlauben darf oder ein
  gemeinsames `format` erzwingt.
- Ob `schemaRef` Pflicht wird, sobald `tables` mehr als eine Tabelle enthaelt.
- Sicherheitsprofil des Containers: erlaubte Entry-Typen, Pfadnormalisierung,
  Groessenlimits und Verhalten bei doppelten Manifest-/Dateieintraegen.

### Vorgeschlagener Vertrag

Input-Erweiterung:

- `tables`: nicht-leere Liste von Tabellen.
- `bundleFormat`: Pflicht, wenn `tables` gesetzt ist.
- `table` und `tables` bleiben gegenseitig exklusiv.
- `bundleFormat` ist ein versionierter Wert, z. B. `seed-bundle.v1.zip` oder
  `seed-bundle.v1.tar`; freie Strings werden nicht akzeptiert.
- Manifest-Datei im Bundle ist Pflicht. Dateinamen plus `schemaRef` reichen
  fuer Version 1 nicht aus, weil sie Tabellenzuordnung, Format und
  per-table Optionen nicht stabil genug beschreiben.

Artifact-/Upload-Metadaten:

- `wireArtifactKind=seed-data` oder ein neuer expliziter Bundle-Marker.
- Persistente Upload-Metadaten enthalten Bundle-Format und bei Bedarf
  Manifest-Fingerprint.
- `targetTables` in `ArtifactUploadMetadata` muss mit Manifest und Tool-
  `tables` konsistent sein, falls der Upload bereits Tabellenbindung mitbringt.
- Das Datenmodell muss die Bundle-Information dauerhaft tragen. Falls der
  bestehende Upload-/Artefaktpfad nur `targetTable` und generische
  Upload-Metadaten persistiert, gehoeren mindestens folgende Erweiterungen zum
  AP: `UploadSession` fuer Bundle-Init-Hints, `ArtifactUploadMetadata` fuer
  `targetTables`, `bundleFormat`, `manifestPath` und `manifestFingerprint`,
  sowie der Finalizer, der diese Felder aus der Session in den
  `ArtifactRecord` uebernimmt.
- `artifact_upload_init` fuer `uploadIntent=job_input` muss dieselbe
  Bundle-Topologie wie `data_import_start` validieren. Andernfalls koennte ein
  Upload finalisiert werden, dessen Bundle-Zuordnung spaeter nicht mehr
  tenant-/idempotenzsicher rekonstruierbar ist.

Fingerprint:

- Artefakt-ID bzw. Source-Ref
- Artefakt-sha256
- `targetConnectionRef`
- `bundleFormat`
- normalisierte `tables`
- Manifest-Fingerprint oder deterministische Datei-zu-Tabelle-Zuordnung
- `schemaRef`, falls vorhanden
- normalisierte Importoptionen
- Tenant + Principal ueber bestehende `JobStartOrchestrator`-Bindung

Runner:

- Lokale Pfade bleiben Tool-seitig verboten.
- Worker spult Artefakt und optional Schema aus Stores.
- Bundle-Extraktion erfolgt in ein job-lokales Temp-Verzeichnis mit Cleanup im
  `finally`-Pfad. Der Extractor lehnt absolute Pfade, `..`-Segmente,
  Symlinks/Hardlinks, Device-/Special-Files, doppelte Manifest-Pfade,
  doppelte Zielpfade nach Normalisierung, leere Dateien fuer nicht-leere
  Tabellen und Entries ausserhalb des Temp-Verzeichnisses ab.
- ZIP/TAR-Bomb-Schutz: maximale Entry-Anzahl, maximale entpackte Gesamtbytes,
  maximale Einzeldateigroesse und optionales Kompressionsverhaeltnis werden
  vor Runner-Start erzwungen. Ueberschreitungen liefern stabile
  `VALIDATION_ERROR`-Details ohne lokale Pfade.
- `SchemaRefImportPreflightAdapter` validiert Schema und Tabellenreihenfolge,
  sobald ein `schemaRef` vorliegt.

### Tests

- `tables` ohne `bundleFormat` -> `VALIDATION_ERROR`.
- Leere oder syntaktisch ungueltige `tables` bleiben `VALIDATION_ERROR`.
- Gueltiges Bundle startet Job und replayt mit gleichem Payload.
- Andere Tabellenliste oder anderes Manifest mit gleichem `idempotencyKey`
  -> `IDEMPOTENCY_CONFLICT`.
- Cross-Tenant-Refs, fehlende Artefakte, fehlendes Manifest und
  Schema-/Tabellen-Mismatch liefern stabile Fehler.
- Manifest-/Archiv-Sicherheitsfaelle: absolute Pfade, Traversal, Symlink,
  doppelte Entries, zu viele Entries, zu grosse entpackte Daten und unbekannte
  Entry-Typen liefern `VALIDATION_ERROR`.
- Worker-Test fuer Spool, Manifest-Aufloesung, SchemaRef-Preflight und
  Cleanup.
- Datenmodell-/Finalizer-Test: Bundle-Init-Hints werden aus der
  Upload-Session in persistente `ArtifactUploadMetadata` uebernommen, inklusive
  `targetTables`, `bundleFormat` und Manifest-Fingerprint.
- Import-Handler-Test: `data_import_start.tables` wird gegen persistierte
  `targetTables` und Manifest-Tabellen abgeglichen; Mismatch liefert
  `VALIDATION_ERROR` oder bei gleichem `idempotencyKey` mit geaendertem
  Fingerprint `IDEMPOTENCY_CONFLICT`.

### Akzeptanz

- Mehrtabellenimporte sind nur ueber explizit versionierte Bundle-Vertraege
  moeglich.
- Keine rohen lokalen Pfade, JDBC-URLs oder Secrets erscheinen im Tool-Payload,
  Fingerprint oder Audit.

---

## 5. AP 3: `testdata_execute`

Prioritaet: mittel. Das AP erzeugt konkrete Testdaten als importierbares
Artefakt, fuehrt aber keinen Ziel-DB-Write aus. Der Write bleibt ein separater
`data_import_start`-Schritt mit eigener Policy, Idempotenz und Audit-Spur.

### Entscheidung

`testdata_execute` wird kein Daten-Schreibjob. Das Tool konsumiert einen
`testdata-plan`, ruft bei Bedarf den Provider auf und publiziert ein
importierbares Datenartefakt bzw. Bundle-Artefakt. Clients starten den
eigentlichen Datenbank-Import danach bewusst mit `data_import_start`.

### Minimaler Implementierungsvertrag

Input:

- `approvalKey`
- `planRef` oder `planArtifactId`
- `targetDialect`
- `outputFormat` oder `bundleFormat`
- genau eine Zielbindung:
  - `targetTable` fuer Single-Table-Output, oder
  - `targetTables` fuer Bundle-/Mehrtabellen-Output, oder
  - eine explizite, validierte Zielbindung aus dem `testdata-plan`
- optional `approvalToken`
- optionale Provider-Auswahl
- optionale kleine Generierungsoptionen, z. B. Row-Limits oder Seed

Validierung:

- Plan-Artefakt muss zum Tenant gehoeren.
- `AiArtifactMetadata` muss `wireArtifactKind=testdata-plan` und
  `aiIntent=testdata_plan` tragen.
- `targetDialect` muss zum Plan passen oder explizit kompatibel sein.
- Zieltabellen muessen entweder aus dem Payload oder aus dem Plan eindeutig
  bestimmbar sein. Mehrdeutige oder fehlende Zielbindung ist
  `VALIDATION_ERROR`; Payload-Zielbindung darf dem Plan nicht widersprechen.
- Kein `targetConnectionRef` im Tool-Payload. Testdaten-Erzeugung kennt keine
  Ziel-DB-Secrets.
- Output muss ein importierbares Artefakt mit eindeutigem
  `wireArtifactKind` bekommen, z. B. `generated-testdata` oder
  `seed-data-bundle`. `data_import_start` muss diesen Marker explizit als
  importfaehig akzeptieren.

Import-Bruecke:

- Der Output kann nicht nur als heutiges KI-Artefakt
  `ArtifactKind.OTHER + AiArtifactMetadata` publiziert werden, wenn er direkt
  von `data_import_start` konsumiert werden soll. Der aktuelle Importpfad
  akzeptiert `ArtifactKind.UPLOAD_INPUT` mit persistenter
  `ArtifactUploadMetadata` und importfaehigem `wireArtifactKind`.
- Die Implementierung muss daher einen von zwei Pfaden verbindlich waehlen:
  1. `testdata_execute` publiziert ein importierbares `UPLOAD_INPUT`-Artefakt
     mit synthetischer, serverseitig erzeugter `ArtifactUploadMetadata`
     (`uploadIntent=job_input`, passendes Format, Tabelle(n), sha256,
     sizeBytes) und zusaetzlicher `AiArtifactMetadata` fuer Provenance.
  2. `data_import_start` wird erweitert, KI-generierte Artefakte ueber
     `AiArtifactMetadata` explizit als Importquelle zu akzeptieren. Dann
     muessen `AiWireArtifactKind.ALL`, `AiIntent.ALL`, Provenance-Checks und
     die Eignungsmatrix des Import-Handlers gemeinsam erweitert werden.
- In beiden Varianten bleibt `targetConnectionRef` ausschliesslich im spaeteren
  `data_import_start`-Payload. `testdata_execute` kennt nur Dialekt, Format,
  Tabellen-/Bundle-Zielstruktur und Artefakt-Provenance.
- Single-Table-Outputs muessen so erzeugt werden, dass `data_import_start`
  ohne nachtraegliche Raterei eine Tabelle sieht: entweder persistiertes
  `ArtifactUploadMetadata.targetTable` oder ein im Import-Payload explizit
  verlangtes `table`, das gegen die Testdaten-Provenance validiert werden kann.
- Bundle-Outputs muessen denselben Manifest-v1- und `targetTables`-Vertrag wie
  AP 2 erfuellen; `testdata_execute` darf keinen zweiten, nur fuer KI-Artefakte
  gueltigen Bundle-Vertrag einfuehren.

Policy/Audit:

- Policy-Intent fuer KI-/Testdaten-Erzeugung, nicht `dmigrate:data:write`.
- Der spaetere Import braucht weiterhin `dmigrate:data:write`.
- Audit-Refs: Plan-Artefakt, erzeugtes Datenartefakt, Provider-/Modell-Meta
  und Payload-/Prompt-Fingerprints.

Idempotenz:

- AI-Tool-Semantik ueber `(tenant, caller, tool, approvalKey,
  payloadFingerprint)`.
- Abweichender Plan, Dialekt, Format oder Generierungsoption mit gleichem Key
  liefern `IDEMPOTENCY_CONFLICT`.

Runner:

- Kein DB-Runner und keine Connection-Secret-Materialisierung.
- Provider-Aufruf folgt denselben Hygiene-, Quota- und Outcome-Store-Regeln
  wie `testdata_plan`.
- Grosse Outputs werden ausschliesslich als Artefakt geschrieben; Inline-
  Antwort bleibt Preview.

### Tests

- Handler-, Policy-, Provider-, Hygiene-, Idempotenz- und Audit-Tests analog
  zu den bestehenden KI-Tools.
- Falscher `wireArtifactKind`, falscher `aiIntent`, fremder Tenant,
  fehlendes Plan-Artefakt und inkompatibler Dialekt liefern stabile Fehler.
- Import-Bruecken-Test fuer beide Seiten des Vertrags: Das erzeugte Artefakt
  traegt entweder vollstaendige `ArtifactUploadMetadata` oder wird ueber die
  explizit erweiterte KI-Artefakt-Eignungsmatrix akzeptiert; ein blosses
  `ArtifactKind.OTHER` ohne Import-Metadaten bleibt nicht importfaehig.
- Zielbindungs-Test: fehlendes `targetTable`/`targetTables` bzw. fehlende
  eindeutige Plan-Ableitung liefert `VALIDATION_ERROR`; widerspruechliche
  Payload- und Plan-Zielbindung ebenfalls.
- Nachgelagerter Import-Test: Das erzeugte Artefakt kann ueber
  `data_import_start` importiert werden, sofern der Caller dafuer
  `dmigrate:data:write` besitzt.

### Akzeptanz

- `tools/list` und Scope-Mapping stimmen mit der Entscheidung ueberein.
- `testdata_execute` erzeugt nie direkt Datenbank-Writes.
- Jede produktive Daten-Schreiboperation bleibt im `data_import_start`-
  Vertrag sichtbar.

---

## 6. Reihenfolge und Abhaengigkeiten

Empfohlene Reihenfolge:

1. AP 1, weil es die kleinste, klarste Wire-Luecke schliesst.
2. AP 2, weil der Bundle-Vertrag auch fuer spaetere Testdaten-Execute-Pfade
   nuetzlich sein kann.
3. AP 3, weil es auf AP 2 aufsetzen kann, wenn Testdaten als Bundle-Artefakt
   erzeugt werden.

Abhaengigkeiten:

- AP 1 kann unabhaengig umgesetzt werden.
- AP 2 haengt von der Manifest-v1-Formatentscheidung ab.
- AP 3 haengt von AP 2 ab, wenn Testdaten als Mehrtabellen-Bundle erzeugt
  werden; Single-Table-Outputs koennen unabhaengig starten.

---
