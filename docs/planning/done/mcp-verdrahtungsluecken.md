# MCP-Verdrahtungslücken: Indizes `diffs`/`profiles`, `job_input`-Upload (Issue #87)

- **Status**: Abgeschlossen (2026-11-27, vom Eigner abgenommen). Ausgeliefert mit 1.16.0, siehe „Closure".
- **Ziel**: `diff_list` und `profile_list` nennen die Ergebnisse der Jobs, mit `server.state` auch nach einem Neustart.
  Ein Client, der sich an das Tool-Schema hält, kann Eingabedaten per `job_input` hochladen und mit
  `data_import_start` importieren.
- **Herkunft**: Befund vom 2026-09-17 (Review Runde 4 und 5 von
  [`compare-projektion-und-normalisierung.md`](../done/compare-projektion-und-normalisierung.md), L-1 und LOW-4).
  Aktiviert durch Issue #87.
- **Entscheidung (Eigner, 2026-11-27)**: Mit `server.state` kommen die beiden Indizes in die State-DB (JDBC-Stores
  samt Migration), nicht in den Arbeitsspeicher. Sonst wären sie nach einem Neustart leer, während Jobs und Artefakte
  noch da sind.

## Ausgangslage (am Code gelesen 2026-11-27)

- **Indizes:** `McpRuntimeWiring` hat die Defaults `EmptyDiffStore`/`EmptyProfileStore`, und `mcp serve` übernimmt sie
  in beiden Zweigen. Die Jobs tragen ihre Ergebnisse schon ein (`McpJobArtifacts`), die Einträge gehen verloren.
  `InMemoryDiffStore`/`InMemoryProfileStore` und die Vertragstests (`DiffStoreContractTests`,
  `ProfileStoreContractTests`) gibt es. JDBC-Stores gibt es nicht.
- **`job_input`:** Das Eingabeschema von `artifact_upload_init` lässt als `uploadIntent` nur
  `schema_staging_readonly` zu und führt `expectedSizeBytes`, `checksumSha256`, `filename`. Der Handler liest für
  `job_input` außerdem `approvalKey`, `approvalToken`, `mimeType`, `artifactKind`, `targetTable`, `bundleFormat`,
  `tables` und `sizeBytes`. Die Spec (`spec/mcp-server.md`, `artifact_upload_init` — Felder) beschreibt diese Felder.
  Der Weg ist über die Leitung noch nie bis zum Import gegangen.
- **Vorbedingung erledigt:** Der `job_input`-Finaliser schreibt seit der Korrektur zu Issue #85 in die getauschten
  Stores.

## Schritte

- **P1 — Indizes ohne `server.state`.** `mcp serve` verdrahtet `InMemoryDiffStore`/`InMemoryProfileStore`.
- **P2 — Indizes mit `server.state`.** `JdbcDiffStore`/`JdbcProfileStore` gegen die Vertragstests in
  `:test:integration-persistence-jdbc`. Migration `V3` aus `server-state-schema.yaml` erzeugt
  ([ADR 0051](../../adr/0051-server-state-schema-generiert-statt-handgeschrieben.md)). Wire-Klassen für das native
  Image registriert. `DefaultServerStateFactory` setzt die Stores ein.
- **P3 — `job_input` erreichbar.** Tool-Schema um den Intent und die Felder ergänzen, Goldens neu erzeugen, vom Upload
  bis zum Import fahren und beheben, was dabei auffällt.
- **P4 — Über die Leitung belegt.** `smoke-server-state.sh`: `schema_compare_start` dann `diff_list`,
  `data_profile_start` dann `profile_list`, beides auch nach Neustart; `job_input`-Upload dann `data_import_start` mit
  Kontrolle der Zeilen in der Zieltabelle.
- **P5 — Doku.** `spec/mcp-server.md` und `spec/cli-spec.md` (was `server.state` hält), Handbücher, CHANGELOG.

## Akzeptanz (DoD)

* Nach `schema_compare_start` nennt `diff_list` den Vergleich mit Quell- und Zielverweis, nach `data_profile_start`
  nennt `profile_list` das Profil; ohne und mit `server.state`, dort auch nach einem Neustart.
* Eine bestehende State-DB aus 1.15.x wird beim Start auf den neuen Stand migriert; die vorhandenen Datensätze bleiben
  lesbar.
* Ein Client, der nur das Tool-Schema kennt, lädt eine CSV-Datei per `job_input` hoch und importiert sie.
* Die neuen Prüfungen sind am Stand vor der Änderung rot.
* Der Native-Lauf besteht `smoke-server-state.sh`.

## Nicht im Umfang

- Der Ablauf von Index-Einträgen: Nichts ruft heute `deleteExpired` des `schemas`-Index auf (nur Artefakte werden
  abgeräumt). Die neuen Indizes verhalten sich wie `schemas`.
- Die Nachsicht von `artifact_upload_init` bei den Arten und der Filter von `schema_list`
  ([`mcp-server-spec-hygiene-residuals.md`](../open/mcp-server-spec-hygiene-residuals.md)).

## Stand (2026-11-27)

- **Rot am Stand 1.15.1:** Das erweiterte `smoke-server-state.sh` findet `diff_list` und `profile_list` leer (vor und
  nach dem Neustart) und den `job_input`-Init abgewiesen. Mit dem neuen Stand läuft es durch, einschließlich der
  importierten Zeilen.
- **Upgrade belegt:** State-DB von 1.15.1 geschrieben, mit dem neuen Stand gestartet: Migrationen `1,2` werden zu
  `1,2,3`; Jobs, Schema und Artefakt-Bytes bleiben lesbar. Mit `migrations.auto: false` endet der neue Server mit
  Exit 2 und nennt die fehlende Migration 3. Beides einmalig von Hand, nicht Teil des Skripts.
- **Beim Bauen entschieden:** `artifact_upload` nennt die Artefakt-URI eines `job_input`-Uploads weiterhin im Feld
  `schemaRef` (bestehendes Verhalten); Tool-Schema, Spec und Handbuch beschreiben es.
- **Nicht gebaut:** `clientRequestId` am Init, den die Spec nennt und der Handler nicht liest.
- **Native belegt:** Der Dispatch-Lauf von `native-image.yml` auf `905dbb4fc` besteht `smoke-server-state.sh` mit
  allen neuen Prüfungen.

## Closure (2026-11-27)

- **Geliefert:** P1 bis P5 mit 1.16.0. `smoke-server-state.sh` läuft gegen die veröffentlichten Images `1.16.0` und
  `1.16.0-native` durch; Issue #87 ist geschlossen. Abnahme durch den Eigner am 2026-11-27.
- **Schwächer belegt als die DoD verlangt:** Ohne `server.state` deckt die Listen nur ein Unit-Test der Verdrahtung,
  kein Lauf über die Leitung. Bundle-Uploads (`tables`/`bundleFormat`) liefen nicht über die Leitung.
- **Offen, geführt in** [`mcp-server-spec-hygiene-residuals.md`](../open/mcp-server-spec-hygiene-residuals.md)
  (Befund 5 und 6): `clientRequestId` am Upload-Init und der Ablauf der Index-Einträge.
