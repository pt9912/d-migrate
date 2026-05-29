# Implementierungsplan: SQLite Sequence Current-Value Preserve (follow-up nach 0.9.7)

> Status: In Progress (2026-05-29)
> Workstream: E.3 Folge-Slice fuer SQLite `supportsCurrentValuePreserve`
> Vorarbeit: `sqlite-sequence-emulation-plan.md`, `ImpPlan-0.9.7-sequence-preserve-current-value.md`

## 1. Ausgang

`SequencePreserveStage` kann aktuell keine SQLite-Sequenzen verarbeiten, obwohl die emulierte Laufzeit-Emission bereits vorhanden ist. `SqliteDiffSequenceOps` emittiert heute bereits eine `UPDATE dmg_sequences SET next_value ...` fuer `AlterSequenceCurrentValue` in Richtung UP, aber der Preserve-Pfad ist durch die Dialekt-Blockade, den fehlenden SQLite-Probe-Adapter und `supportsCurrentValuePreserve = false` deaktiviert.

Der Folge-Workstream schliesst diese Luecke: SQLite bekommt einen `SequenceCurrentValueProbe`, der Runner routet auf den Adapter, die Stage laesst SQLite durch den Preserve-Flow, der Down-Renderer kann Restore-Werte schreiben, und die Capability wird erst danach aktiviert.

## 2. Ziel

1. SQLite nutzt denselben Preserve-Flow wie PG/MySQL unter `preserveCurrentValue`.
2. Nach dem Diff-Lauf und vor der Migrationserzeugung ist der aktuelle SQLite-Emulationsstatus (`dmg_sequences.next_value`) per Probe lesbar.
3. `sequence_nextval`-Folgeoperationen erhalten bei SQLite echte `AlterSequenceCurrentValue`-Follow-ups.
4. Down-Migrationen geben einen deterministischen Restore-`UPDATE` auf `dmg_sequences.next_value` aus.
5. Fehlender oder ungeeigneter SQLite-Sequenzzustand laeuft durch die bestehende Diagnose-Pipeline.

## 3. In Scope / Out of Scope

1. In Scope: `SequenceCurrentValueProbe`-Implementierung fuer SQLite.
2. In Scope: Runner-/Registry-Wiring in `SequenceCurrentValueProbeRunner`.
3. In Scope: Dialekt-Gate in `SequencePreserveStage` fuer SQLite oeffnen.
4. In Scope: `SequenceCapabilityDefaults.SQLite.supportsCurrentValuePreserve = true` als letzter Aktivierungsschritt.
5. In Scope: Down-Renderer-Pfad in `SqliteDiffSequenceOps` fuer `AlterSequenceCurrentValue` erweitern.
6. In Scope: Preserve-Probe fuer SQLite nur im `helper_table`-Modus aktivieren oder vor dem Probe-Lauf mit bestehender Opt-in-Diagnostik blockieren.
7. In Scope: Testabdeckung fuer Probe, Stage, Runner und Diff-Renderer auf SQLite.
8. Out of Scope: atomare Probe+Restore-Lock-Garantie zwischen Probezeitpunkt und Set/Update.
9. Out of Scope: Umstrukturierung der bestehenden SQLite-Emulationsform.

## 4. Referenzen

1. `docs/planning/done/sqlite-sequence-emulation-plan.md`
2. `docs/planning/done/ImpPlan-0.9.7-sequence-preserve-current-value.md`
3. `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SequencePreserveStage.kt`
4. `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SequenceCurrentValueProbeRunner.kt`
5. `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SequenceCurrentValueProbe.kt`
6. `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SequenceCapabilityDefaults.kt`
7. `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSequenceOps.kt`
8. `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteMetadataQueries.kt`
9. `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSequenceNaming.kt`
10. `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceCurrentValueProbe.kt`

## 5. Umsetzungsphasen

### Phase A - Vertragsabgleich

1. Probe-Verhalten fuer SQLite verbindlich festlegen:
2. `Read`: exakt eine passende `dmg_sequences`-Zeile fuer den Sequenznamen, `managed_by = d-migrate`, `format_version = sqlite-sequence-v1`.
3. `NotFound`: fehlende `dmg_sequences`-Tabelle oder keine Zeile fuer den Sequenznamen.
4. `Failed(PROBE_PERMISSION_DENIED)`: fehlende Leserechte oder vergleichbare SQLite-Zugriffsfehler.
5. `Failed(PROBE_UNMANAGED_ROW)`: Zeile existiert, aber `managed_by` ist nicht erlaubt.
6. `Failed(PROBE_UNKNOWN_FORMAT_VERSION)`: Zeile ist d-migrate-managed, aber `format_version` ist unbekannt.
7. `Failed(PROBE_AMBIGUOUS_ROW)`: mehr als eine passende Zeile, defensiv trotz Primary-Key-Vertrag.
8. `Failed(PROBE_QUERY_FAILED)`: uebrige SQL-/Driver-Fehler.
9. `NotApplicable` darf kein regulaerer SQLite-Laufweg mehr sein; es bleibt nur fuer echte Nichtunterstuetzung ausserhalb dieses Dialekts.
10. NotFound-Policy fixieren: `CreateSequence` bleibt Info-Pfad, `AlterSequence`/`RenameSequence` bleiben Blocker-Pfade.
11. Down-Restore-Verhalten fixieren: UP schreibt auf `applySequenceRef`, DOWN schreibt auf `probeSequenceRef`.

DoD:

- [ ] Probe-Ergebnismatrix ist in Plan oder KDoc abgebildet.
- [ ] `NotFound` ist fuer Tabelle-fehlt und Zeile-fehlt bewusst gleich behandelt.
- [ ] `NotApplicable` ist fuer SQLite im Execute-Pfad nicht mehr vorgesehen.
- [ ] Restore-Namenswahl fuer Rename-Faelle ist eindeutig dokumentiert.

### Phase B - Probe Adapter

1. Neue Datei `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSequenceCurrentValueProbe.kt` erstellen.
2. Implementierung von `SequenceCurrentValueProbe.probe` mit SQL auf `dmg_sequences`.
3. SQL nutzt `PreparedStatement` fuer den Sequenznamen; statische Identifier bleiben ueber die SQLite-Adapterkonstanten bzw. vorhandene Quote-Helfer kontrolliert.
4. Ergebnismapping konsistent zu den vorhandenen Port-Typen (`Read`, `NotFound`, `Failed`).
5. Fehlercodes definieren und auf `SequenceCurrentValueProbeResult.Failed` mappen: `PROBE_PERMISSION_DENIED`, `PROBE_UNMANAGED_ROW`, `PROBE_UNKNOWN_FORMAT_VERSION`, `PROBE_QUERY_FAILED`, `PROBE_AMBIGUOUS_ROW`.
6. `managed_by` und `format_version` gegen `SqliteSequenceNaming.MANAGED_BY` und `SqliteSequenceNaming.FORMAT_VERSION` pruefen.
7. Optional gemeinsame Helper in `SqliteMetadataQueries` nutzen oder extrahieren, falls sonst duplizierte `dmg_sequences`-Abfragen entstehen.
8. Unit-Tests im SQLite-Adapterpaket erstellen.
9. Integrationsprobe gegen echte in-memory SQLite-Datenbank aufnehmen, wenn das vorhandene Testsetup dies ohne neue Infrastruktur erlaubt.

DoD:

- [ ] `SqliteSequenceCurrentValueProbe` existiert und implementiert `SequenceCurrentValueProbe`.
- [ ] Happy Path liest `next_value` als `Read(value = ...)`.
- [ ] Lookup-Key wird gebunden und nicht als String-Literal in das Probe-SQL konkatenisiert.
- [ ] Missing table und missing row liefern `NotFound`.
- [ ] Unmanaged row, unknown format, ambiguous row und SQL-Fehler liefern stabile `Failed`-Codes.
- [ ] Probe wirft im Normalbetrieb keine SQL-Exception nach oben.
- [ ] Adaptertests decken mindestens Happy Path, NotFound und zwei Failed-Varianten ab.

### Phase C - Wiring in Runner und Stage

1. `SequenceCurrentValueProbeRunner` um SQLite-Routing auf `SqliteSequenceCurrentValueProbe.probe` erweitern.
2. Die bisherige SQLite-Verzweigung auf `SequenceCurrentValueProbeResult.NotApplicable` entfernen.
3. `SequencePreserveStage.run` so aendern, dass SQLite nicht mehr automatisch mit `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` blockiert.
4. Phase C oeffnet nur das interne Stage-/Runner-Wiring fuer SQLite; die oeffentliche Aktivierung ueber `supportsCurrentValuePreserve = true` passiert ausschliesslich in Phase E.
5. Kein Stage-Gate gegen `SequenceCapabilityDefaults.SQLite.supportsCurrentValuePreserve` einfuehren, solange die Capability in dieser Phase noch `false` ist.
6. SQLite-Preserve-Probe vor dem Live-DB-Zugriff an den `helper_table`-Modus koppeln. Wenn die Stage den Modus nicht bereits kennt, muss die Pipeline den SQLite-Named-Sequence-Modus in die Stage reichen oder vorher einen blockernden Opt-in-Diagnosepfad ausloesen.
7. `target !is CompareOperand.Database` bleibt hoeher priorisiert als Dialekt-/Capability-/Helper-Mode-Blocker.
8. `probe == null` bleibt ein kontrollierter NotRun-/Info-Pfad.
9. Stage- und Runner-Tests aktualisieren: bisherige Erwartungsausgaben fuer SQLite-BLOCKER entfernen und neuen SQLite-Happy-Path absichern.

DoD:

- [ ] Runner dispatcht SQLite auf den neuen Adapter.
- [ ] SQLite Preserve-Kandidaten erreichen bei DB-Execute den Probe-Flow.
- [ ] SQLite-Probe laeuft nur bei `helper_table`; `action_required` blockiert vor dem Live-Probe mit klarer Diagnose.
- [ ] Stage-Tests koennen SQLite-Wiring testen, obwohl `supportsCurrentValuePreserve` erst in Phase E oeffentlich aktiviert wird.
- [ ] File-target-Blocker bleibt vor Dialekt-/Capability-Blockern priorisiert.
- [ ] `probe == null`-Fallback bleibt unveraendert kontrolliert.
- [ ] Alte SQLite-Unsupported-Tests sind ersetzt oder gezielt umformuliert.
- [ ] PG/MySQL-Stage-Tests bleiben unveraendert in ihrer Semantik.

### Phase D - Down-Path im SQLite Diff Renderer

1. `SqliteDiffSequenceOps.renderAlterSequenceCurrentValue` in Richtung `DOWN` von permanentem Skip auf Restore-Update umstellen.
2. `UP` weiter auf `applySequenceRef` ausrichten.
3. `DOWN` auf `probeSequenceRef` ausrichten, damit Rename/Alter-Faelle korrekt aufgeloest werden.
4. Bestehende Tests in `SqliteDiffSequenceOpsTest` und `SqliteDiffDdlGeneratorTest` erweitern.
5. Bisherige Erwartung `SQLITE_SEQUENCE_CURRENT_VALUE_DOWN_NO_OP` entfernen oder nur fuer explizite fehlende Restore-Daten behalten, falls ein solcher Zustand weiterhin modellierbar ist.
6. Sicherstellen, dass fehlender Restore-Wert nicht still ignoriert wird.

DoD:

- [ ] DOWN emittiert `UPDATE "dmg_sequences" SET "next_value" = <value> WHERE "name" = <probeRef>;`.
- [ ] Rename-Fall nutzt in DOWN den alten/probe-seitigen Sequenznamen.
- [ ] UP-Verhalten bleibt unveraendert.
- [ ] Tests decken UP, DOWN und Rename-Ref-Aufloesung ab.
- [ ] Kein stiller Skip fuer `AlterSequenceCurrentValue` DOWN im normalen Preserve-Pfad.

### Phase E - Capabilities, Dokumentation und Aktivierung

1. `SequenceCapabilityDefaults.SQLite.supportsCurrentValuePreserve` erst nach Abschluss von Phase A-D auf `true` setzen.
2. Capability-KDoc aktualisieren: SQLite hat Probe + Stage-Wiring + Down-Restore, deshalb ist die bisherige Begruendung fuer `false` entfernt.
3. Tests fuer `SequenceCapabilityDefaults`/Capability-Matrix aktualisieren.
4. Falls zentrale Planer-/Pipeline-Gates auf `supportsCurrentValuePreserve` pruefen, ist Phase E der Punkt, an dem SQLite von "intern verdrahtet" auf "oeffentlich planbar" wechselt.
5. Dokumentation erweitern: `docs/ddl-generation-rules.md` und `docs/user/guide.md` um SQLite-Preserve-Status in Matrix, Helper-Table-Voraussetzung und Warnlogik.
6. `CHANGELOG.md` um kurzen follow-up Eintrag ergaenzen.
7. Status im Plan finalisieren und optional Roadmap-Referenz im in-progress-Aggregator aktualisieren.

DoD:

- [ ] SQLite-Capability wird erst nach gruenem Probe-/Stage-/Renderer-Pfad aktiviert.
- [ ] Capability-Test erwartet `supportsCurrentValuePreserve = true` fuer SQLite.
- [ ] User-Dokumentation nennt SQLite als unterstuetzt und beschreibt Helper-Table-Voraussetzung.
- [ ] Dokumentation nennt, dass `action_required` keinen Preserve-Live-Probe ausfuehrt.
- [ ] Changelog-Eintrag nennt Preserve-Current-Value fuer SQLite.
- [ ] Planstatus ist nach Merge auf Done/Completed aktualisiert.

## 6. Gesamt-DoD

- [ ] SQLite-Diff mit `preserveCurrentValue = true` erzeugt echte Probe-Flow-Diagnosen statt `DIALECT_UNSUPPORTED_OPERATION`.
- [ ] SQLite-Preserve ist an `--sqlite-named-sequences helper_table` gebunden; ohne Opt-in gibt es keine Live-Probe.
- [ ] `SchemaMigrate --execute` erzeugt bei SQLite Preserve-Follow-ups hinter den Parent-Sequence-Ops.
- [ ] `AlterSequenceCurrentValue` emittiert in `UP` und `DOWN` deterministische `dmg_sequences.next_value`-Updates.
- [ ] `NotFound` und `Failed` werden mit bestehenden Planercodes aufbereitet.
- [ ] Regressionen in PG/MySQL Preserve-Flow sind durch bestehende oder aktualisierte Tests ausgeschlossen.
- [ ] Mindestens ein Adaptertest, ein Stage-Test, ein Runner-Test und ein SQLite-Diff-Renderer-Test decken den neuen Pfad ab.

## 7. Risiken und Entkopplungen

1. Restore ist nicht atomar zu normalen Inserts zwischen Probe und Restore; dieses Verhalten bleibt bewusst dokumentiert.
2. Die Emulationsform kann sich per `format_version` weiterentwickeln; neue Werte brauchen ggf. eine Probe-Erweiterung.
3. `supportsCurrentValuePreserve` darf nicht vor Abschluss von Adapter, Runner, Stage und Down-Renderer aktiviert werden.
4. SQLite nutzt Helper-Table-Emulation; Datenbanken ohne kanonische `dmg_sequences`-Struktur muessen blockieren statt still ueberschrieben zu werden.
