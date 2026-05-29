# Implementierungsplan: SQLite Sequence Current-Value Preserve (follow-up nach 0.9.7)

> Status: In Progress (2026-05-29)
> Workstream: E.3 Folge-Slice für SQLite `supportsCurrentValuePreserve`
> Vorarbeit:
> - `docs/planning/done/sqlite-sequence-emulation-plan.md`
> - `docs/planning/done/ImpPlan-0.9.7-sequence-preserve-current-value.md`

## 1. Ausgangslage

SQLite emuliert Sequences im `helper_table`-Modus, aber der Preserve-Pfad für `preserveCurrentValue` ist nicht vollständig aktiv:

- Stage blockiert aktuell mit Dialekt-/Feature-Fehlmeldungen.
- Es gibt keinen dedizierten SQLite-Probe-Adapter.
- Down-Rendering für `AlterSequenceCurrentValue` ist nicht deterministisch.
- `supportsCurrentValuePreserve` bleibt für SQLite ausgeschaltet, obwohl die einzelnen Teile größtenteils vorhanden sind.

Ziel ist, das bisher implizite Gap kontrolliert zu schließen: Probe → Follow-up-Planung → deterministischer Up/Down-Render in einem konsistenten Opt-in-Modus.

## 2. Zielbild

1. SQLite ist im `preserveCurrentValue`-Flow vollständig an PG/MySQL anschlussfähig, jedoch nur im `helper_table`-Modus.
2. Probe liest für Kandidaten den laufenden Wert von `dmg_sequences.next_value` und liefert stabil typisierte Ergebnisse.
3. Up- und Down-Renderemissions für `AlterSequenceCurrentValue` sind vollständig definiert.
4. Die Capability wird erst nach vollständigem technischen Abschluss aktiviert.
5. Alle neuen Fehlerfälle landen als klare Diagnosen, nicht als stiller No-Op.

## 3. In-/Out-of-Scope

### In Scope

- SQLite `SequenceCurrentValueProbe` ergänzen.
- Runner-/Wiring auf Adapter inkl. Stage-Pfade anpassen.
- `SqliteDiffSequenceOps` Down-Restore implementieren.
- SQLite-Opt-in (`helper_table`) in den Preserve-Flow integrieren.
- Capability- und Dokumentations-Update.
- Tests für Probe, Stage, Runner, Down-Renderer.

### Out of Scope

- Transaktionsmäßige Atomgarantie zwischen Probe und Restore.
- Re-Architekturierung der bestehenden SQLite-Helfertabellen.
- Änderungen außerhalb der Preserve-/Sequence-Pipeline.

## 4. Referenzen

1. `docs/planning/done/sqlite-sequence-emulation-plan.md`
2. `docs/planning/done/ImpPlan-0.9.7-sequence-preserve-current-value.md`
3. `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SequenceCurrentValueProbeRunner.kt`
4. `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SequencePreserveStage.kt`
5. `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateRenderPipeline.kt`
6. `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SequenceCurrentValueProbe.kt`
7. `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SequenceCapabilityDefaults.kt`
8. `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSequenceOps.kt`
9. `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSequenceNaming.kt`
10. `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceCurrentValueProbe.kt`

## 5. Umsetzung in Phasen

### Phase A – Vertragsdefinition und Abbruchmodell

- Define Probe-Ergebnis-Contract für SQLite:
  - `Read`: genau eine passende Zeile, `managed_by = d-migrate`, `format_version = sqlite-sequence-v1`.
  - `NotFound`: Tabelle fehlt **oder** kein passender Datensatz.
  - `Failed(PROBE_PERMISSION_DENIED)` bei DB-Rechten-/Zugriffsfehlern.
  - `Failed(PROBE_UNMANAGED_ROW)` wenn `managed_by` nicht akzeptiert.
  - `Failed(PROBE_UNKNOWN_FORMAT_VERSION)` bei unbekannter `format_version`.
  - `Failed(PROBE_AMBIGUOUS_ROW)` bei mehr als einem Treffer.
  - `Failed(PROBE_QUERY_FAILED)` für generelle SQL-/Driverfehler.
  - `NotApplicable`: nur für Nicht-SQLite.
- Konkrete Preserve-Routing-Regel:
  - `preserveCurrentValue` aktiv **und** DB-Target **und** Modus `helper_table` ⇒ Probe erlaubt.
  - Sonst: explizite Diagnose vor DB-Zugriff.
- NotFound-Policy fixieren:
  - `CreateSequence`: Info/NotRun.
  - `AlterSequence`/`RenameSequence`: Blocker.
- Restore-Referenzverhalten festlegen:
  - UP → `applySequenceRef`
  - DOWN → `probeSequenceRef`

**DoD A**

- [ ] Probe-Matrix dokumentiert (Plan oder KDoc).
- [ ] `helper_table` als harte Vorbedingung im Preserve-Kontext dokumentiert.
- [ ] Nicht-`helper_table` blockiert vor Live-Probe deterministisch.
- [ ] Rename-Restore nutzt `probeSequenceRef`.

### Phase B – SQLite-Probe-Adapter implementieren

- Neue Datei ergänzen: `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSequenceCurrentValueProbe.kt`
- Implementierung per `dmg_sequences`-Abfrage mit gebundenem Sequenznamen.
- Mapping auf `SequenceCurrentValueProbeResult` inkl. Fehlercode-Verträge.
- Prüfung von `managed_by` und `format_version` gegen `SqliteSequenceNaming`.
- Keine ungefangene SQL-Exception in den Aufrufer gelangen lassen.

**DoD B**

- [ ] Adapter existiert und implementiert das Probe-Interface.
- [ ] Happy-Path liefert `Read(value)`.
- [ ] `NotFound` deckt fehlende Tabelle und fehlende Zeile ab.
- [ ] Mindestanforderung Fehlerszenarien ist getestet (unmanaged, format, permissions/ambiguous, query-fail).

### Phase C – Runner- und Stage-Wiring + Kontextfluss

- Runner-Wiring:
  - `SequenceCurrentValueProbeRunner` routet SQLite auf neuen Adapter.
  - Alte SQLite-`NotApplicable`-Default-Ableitung aufheben.
- Stage-Wiring:
  - Kein generischer „unsupported by dialect“-Stop mehr für SQLite im Preserve-Flow.
  - Reihenfolge strikt fixieren:
    1. Ziel ist DB?
    2. Modus/Opt-in geprüft?
    3. Probe vorhanden?
    4. Kandidat in Follow-up-Routing.
- `helper_table` in die Pipeline/Context tragen, damit Stage sauber bewertet.
- Bestehende Datei-Target-Blocker behalten Vorrang vor Preservergister/Capability-Checks.

**DoD C**

- [ ] SQLite-Kandidaten erreichen bei DB-Execute den Probe-Flow.
- [ ] `helper_table`-Opt-in ist Pflicht und wird vor Probe geprüft.
- [ ] `probe == null` bleibt kontrollierter NotRun-Pfad.
- [ ] Alte SQLite-unsupprted-Blocker-Tests ersetzt/angepasst.
- [ ] PG/MySQL-Verhalten unverändert.

### Phase D – Down-Rendering fertigstellen

- `SqliteDiffSequenceOps.renderAlterSequenceCurrentValue`:
  - `DOWN` statt permanentem No-Op: `UPDATE "dmg_sequences" SET "next_value" = <value> WHERE "name" = <probeRef>;`
  - Rename-Fälle nutzen den Probe-Refnamen.
- Sicherstellen, dass fehlender Restore-Referenzwert nicht still ignoriert wird.

**DoD D**

- [ ] Up-/Down-Restore sind in Tests explizit sichtbar.
- [ ] Kein impliziter Skip im normalen Preserve-Down-Case.
- [ ] Rename-Fall ist korrekt aufgelöst.

### Phase E – Capability, Docs, Aktivierung

- Erst nach Abschluss A–D:
  - `SequenceCapabilityDefaults.SQLite.supportsCurrentValuePreserve = true`.
  - KDoc/Comments aktualisieren.
  - Capability-Matrix-Tests ergänzen.
- Dokumentation/Guide:
  - `docs/ddl-generation-rules.md`, `docs/user/guide.md`: SQLite als unterstütztes Preserve im `helper_table`-Modus.
  - Klartext-Verhalten bei `action_required` (kein Live-Probe).
- `CHANGELOG.md`: kurze Follow-up-Notiz inkl. Hinweis zur nicht-atomaren Restore-Lücke.

**DoD E**

- [ ] Capability ist erst nach komplettem technischen Abschluss aktiv.
- [ ] Dokumentation enthält den Opt-in- und Blockierpfad.
- [ ] Changelog-Eintrag vorhanden.

### Phase F – Abschlussabnahme

- Testabdeckung:
  - Adapter-Test (Probe), Stage-Test, Runner-Test, Down-Renderer-Test.
- End-to-End-Sicht:
  - SQLite mit `preserveCurrentValue` + `helper_table` erzeugt Probe- und Restore-flows.
  - SQLite ohne helper_table erzeugt klare Opt-in-Diagnose (kein Live-Probe).

**DoD F**

- [ ] Kein Dialekt-Unsupprt-Block mehr im gültigen SQLite-Preserve-Flow.
- [ ] Up- und Down-Statements enthalten deterministische `dmg_sequences.next_value`-Updates.
- [ ] Fehler-/Block-Pfade sind deterministisch und dokumentiert.

## 6. Risiken

1. Zwischen Probe und Restore ist keine Transaktionsbarriere garantiert.
2. Neue `format_version`-Werte in `dmg_sequences` erfordern Adaptererweiterung.
3. Capability darf nicht vor Abschluss aller technischen Phasen eingeschaltet werden.
4. SQLite-Fallback außerhalb `helper_table` bleibt hart blockiert, um unbestimmtes Verhalten zu vermeiden.
