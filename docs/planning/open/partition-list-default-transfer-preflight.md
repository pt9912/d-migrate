# Vorschlag: LIST-`DEFAULT`-Partition Transfer-Preflight (E063)

> **Status:** Draft (Vorschlag, 2026-06-27)
> **Trigger:** Carve-Out aus der graduierten Cross-Dialect-Partitionierung
> ([`../done/cross-dialect-partitioning.md`](../done/cross-dialect-partitioning.md),
> Sub-Slice „LIST-`DEFAULT`-Transfer-Preflight"). Getrackt in
> [`../in-progress/carveout.md`](../in-progress/carveout.md), Abschnitt 9, Zeile 2 —
> dort **Provisional** mit Trigger „dediziertes Transfer-Preflight-Slice".
> **Aktivierungsbedingung:** Sobald ein PG→MySQL-Transfer mit LIST-`DEFAULT`-Partitionen
> in einem realen Migrationsszenario priorisiert wird, wandert dieser Vorschlag nach
> [`../next/`](../next/) — **dort** mit Phasenschnitt und Akzeptanzkriterien
> ([ADR 0004](../../adr/0004-documentation-and-planning-structure.md) reserviert
> ausgearbeitete Phasen/Akzeptanz für `next/`). Dieses `open/`-Dokument bleibt auf
> Vorschlags-Altitude: Ziel, Scope und offene Designentscheidungen.

## 1. Ziel

Eine PG-LIST-Partition mit `DEFAULT`-Catch-all hat **kein MySQL-Pendant**. Beim
PG→MySQL-Generate wird sie verworfen, und die Zeilen, die in die `DEFAULT`-Partition
fielen, hätten in MySQL kein Ziel — sie würden beim Transfer zurückgewiesen
(**Datenverlust**). Die Generate-Note **E063** flaggt das schon laut (`action_required`),
aber **rein statisch**: sie weiß nicht, **ob überhaupt** Zeilen betroffen sind.

Ziel ist ein **Transfer-Zeit-Preflight**, der die tatsächlich betroffenen Zeilen **zählt**
(die in der `DEFAULT`-Partition liegen) und das Ergebnis vor dem Datentransfer meldet —
nach dem Vorbild der bestehenden Preflight-Architektur (planner→runner→renderer→report).
So wird aus „könnte Daten verlieren" ein belegtes „verliert N Zeilen" (oder „0 betroffen,
unkritisch").

## 2. Hintergrund (Ist-Stand im Code)

- **E063 (Generate, statisch) existiert.** Emittiert in
  [`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPartitionDdlHelper.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPartitionDdlHelper.kt),
  sobald eine LIST-Partitionierung eine `DEFAULT`-Partition trägt — verwirft sie und meldet
  `action_required`. Die `DEFAULT`-Partition ist im Modell über
  `PartitionDefinition.isDefault` markiert (
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/PartitionConfig.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/PartitionConfig.kt));
  die expliziten LIST-Werte der übrigen Partitionen liegen in `values`.
- **Preflight-Architektur als Vorbild (planner→runner→renderer→report).** Der bestehende
  CHECK-Constraint-Preflight zeigt das Muster vollständig:
  - Planner: [`hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/CheckPreflightPlanner.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/CheckPreflightPlanner.kt)
    baut eine `probeSql`.
  - Runner: [`adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CheckPreflightProbeRunner.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CheckPreflightProbeRunner.kt)
    führt die Probe gegen die DB aus.
  - Stage: [`hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/CheckPreflightStage.kt`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/CheckPreflightStage.kt).
  - Gate/Renderer: [`hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/CheckPreflightGate.kt`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/CheckPreflightGate.kt)
    entscheidet Proceed/Block.
  - Report-Typ: `CheckPreflightDeclaration` in
    [`hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/CheckPreflight.kt`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/CheckPreflight.kt)
    (trägt `totalRows`/`failingRows` — genau die Zähl-Semantik, die hier gebraucht wird).
- **Preflight-Registry.** Mehrere Preflight-Arten werden in `MigrationPreflightPlan`
  gesammelt (
  [`hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SqliteCastPreflightStage.kt`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SqliteCastPreflightStage.kt)) —
  je eine Liste pro Check-Typ. Ein neuer Check reiht sich dort ein.
- **Transfer-Pfad (anderer Integrationspunkt!).** `data transfer` läuft über
  [`hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt)
  → Tabellen-Validierung/Topo-Sort in
  [`hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/TransferPreflightPlanner.kt`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/TransferPreflightPlanner.kt)
  → Tabellen-Iteration in
  [`hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/TransferExecutor.kt`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/TransferExecutor.kt).
  **Wichtig:** Der bestehende CHECK-Preflight läuft im `migrate`-Renderpfad und probt das
  **Ziel**; dieser neue Check muss die **Quelle** zählen und gehört in den **Transfer**-Pfad.
  Diese Naht-Wahl ist die zentrale Designfrage (siehe Abschnitt 4).

## 3. Scope

### 3.1 In Scope

- Ein neuer Preflight-Check-Typ (z. B. `ListDefaultPartitionPreflightDeclaration`),
  eingereiht in die Preflight-Registry (`MigrationPreflightPlan`-Muster).
- Ein Planner, der für PG-LIST-Quelltabellen mit `DEFAULT`-Partition **und** MySQL-Ziel
  eine Zähl-Probe auf die Quelle baut.
- Ein Runner, der die Probe gegen die Quell-DB ausführt, und ein Gate, das je nach Policy
  **blockiert** oder **warnt**.
- Report mit der gezählten betroffenen Zeilenzahl (Wiederverwendung der
  `totalRows`/`failingRows`-Semantik).

### 3.2 Nicht in Scope

- Die verwaisten Zeilen **anderswohin migrieren** (separate Umpartitionierung) — der
  Preflight meldet nur, er repariert nicht.
- Änderung der bestehenden **E063-Generate**-Logik (bleibt der statische Frühwarner).
- Nicht-LIST-Partitions-Transfer-Belange und RANGE/HASH-Fälle.

## 4. Offene Designentscheidungen

1. **Integrationspunkt: `migrate`-Preflight vs. `data transfer`-Preflight.** Der Carve-Out
   sagt „spiegelt `CheckPreflight`" (Architektur), aber CHECK-Preflight probt das **Ziel**
   während `migrate`. Dieser Check zählt die **Quelle** und sitzt logisch im
   **Transfer**-Pfad (`TransferPreflightPlanner`). Zu entscheiden: das CHECK-Muster in den
   Transfer-Pfad spiegeln (sauber, aber neue Verdrahtung) oder als zusätzliche
   `migrate`-Preflight-Art führen, die quell-seitig probt (Wiederverwendung, aber probt
   ungewohnt die Quelle).
2. **Probe-SQL: Catch-all-Prädikat vs. direkte Kind-Zählung.** PG materialisiert die
   `DEFAULT`-Partition als reale Kind-Tabelle — ein direktes `SELECT count(*)` darauf ist
   exakt und billig. Alternative: ein `NOT IN (<alle expliziten LIST-Werte>)`-Prädikat auf
   die Parent-Tabelle. Tendenz: direkte Kind-Zählung (exakt, keine Wert-Rekonstruktion).
3. **Block- vs. Warn-Default.** Datenverlust spricht für **Block per Default** mit
   Override-Flag; gegen einen versehentlichen harten Stopp bei `0` betroffenen Zeilen spricht
   ein automatisches Proceed, wenn der Zähler `0` ergibt.
4. **Erkennung der Bedingung.** Zur Transfer-Zeit feststellen, dass Quelle = PG-LIST-mit-
   `DEFAULT` und Ziel = MySQL ist (sonst ist der Check ein No-op).

## 5. Bezug

- Quell-Slice (graduiert): [`../done/cross-dialect-partitioning.md`](../done/cross-dialect-partitioning.md).
- Carve-Out-Tracker: [`../in-progress/carveout.md`](../in-progress/carveout.md), Abschnitt 9.
- ADR: [0020](../../adr/0020-cross-dialect-partitioning-mysql.md) (LIST-`DEFAULT`-Verwurf
  als Cross-Dialect-Mapping-Entscheid).
- Schwester-Slice (gleicher Carve-Out-Abschnitt):
  [`partition-child-local-fk-transparency.md`](partition-child-local-fk-transparency.md).
