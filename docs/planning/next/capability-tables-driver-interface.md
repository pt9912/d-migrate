# Capability-Tabellen ans `DatabaseDriver`-Interface statt statischer Tabellen im Hexagon

> **Status:** Draft mit Scope (2026-09-05).
> **Trigger:** Beim Oracle-Slice-2-Bau fiel auf, dass fünf statische
> Objekte in `hexagon/ports-common`/`hexagon/ports-read`
> (`DialectCapabilities`, `SequenceCapabilityDefaults`,
> `RoutineCapabilityDefaults`, `TriggerCapabilityDefaults`,
> `SpatialProfilePolicy`) jeweils einen `when (dialect)`-Zweig pro
> Dialekt tragen, statt dass jeder Dialekt seine eigenen Capabilities
> über sein `DatabaseDriver` selbst exponiert. Eigner-Einschätzung: die
> Registry-Variante wäre "die saubere Sache" — als eigener Slice, nicht
> Teil von Oracle Slice 2.

## Warum die Tabellen heute zentral liegen (nicht versehentlich)

`hexagon/application`-Code (`DataTransferRunner`, `SchemaMigrateRunner`,
`ImportPreflightResolver`, `TransferPreflightPlanner`,
`TriggerPlanningContextFactory`, `SequencePreserveStage` u. v. a.) fragt
diese Tabellen **direkt und statisch** ab — oft, bevor überhaupt eine
Verbindung/ein Treiber existiert (z. B. Preflight-Validierung: „unterstützt
`--target mssql` atomic preserve?" rein aus dem eingetippten Dialektnamen).
Die Hexagon-Regel (von `a-check` durchgesetzt) ist strikt einseitig:
Adapter dürfen von Hexagon abhängen, nie umgekehrt. Läge z. B.
`MssqlCapabilities` im Adaptermodul `driver-mssql`, könnte
`hexagon/application`-Code sie nicht importieren, ohne die
Abhängigkeitsrichtung zu invertieren.

Der einzige Weg, der die Schichtgrenze respektiert **und** die
Dialekt-Daten aus dem Hexagon herausnimmt: Capabilities werden zur
Laufzeit über den bereits vorhandenen `DatabaseDriverRegistry`
(ServiceLoader-basiert, wie `ddlGenerator()`/`schemaReader()` heute schon)
aufgelöst, nicht mehr über eine statische Tabelle mit Dialekt-Switch.

## Ziel

`DatabaseDriver`-Interface um Capability-Methoden erweitern
(z. B. `capabilities(): DialectCapabilities`,
`sequenceCapability(): SequenceCapability`,
`routineCapability(): EffectiveRoutineCapability.Valid`,
`triggerCapability(): TriggerCapability`,
`spatialProfilePolicy(): SpatialProfilePolicyFacts`); jeder
`*Driver` liefert seine eigenen Werte. Aufrufer wechseln von
`XCapabilityDefaults.forDialect(dialect)` auf
`DatabaseDriverRegistry.get(dialect).xCapability()`.

## Offene Designfrage vor Sub-Slice-Schnitt

**Registry-Verfügbarkeit in jedem Aufrufkontext.** Heute funktionieren die
statischen Tabellen unabhängig davon, welche Adapter-JARs auf dem
Klassenpfad liegen (reine Compile-Time-Daten). Die Registry ist
ServiceLoader-befüllt und hängt vom Klassenpfad zur Laufzeit ab — CLI/MCP
binden alle fünf Adapter ein, aber Unit-Tests, die eine Capability-Tabelle
heute direkt mocken/abfragen (ohne einen Treiber zu registrieren), müssten
auf eine Registry-Registrierung umgestellt werden. Vor Beginn zu klären:
bleibt das für alle betroffenen Testsuiten praktikabel, oder braucht es
zusätzlich eine leichte "Capability-only"-Auflösung ohne volle
Treiber-Registrierung?

## Scope-Skizze

1. **P0 — Designfrage klären** (siehe oben) + Interface-Erweiterung
   entwerfen (fünf neue `DatabaseDriver`-Methoden, mit sinnvollen
   Default-Implementierungen wo möglich, um nicht jeden `*Driver` sofort
   vollständig anfassen zu müssen).
2. **P1 — Je Dialekt verdrahten.** Fünf `*Driver`-Klassen um die neuen
   Methoden ergänzen, Rückgabewerte aus den bestehenden statischen
   Tabellen übernehmen (mechanische Verschiebung, keine
   Verhaltensänderung).
3. **P2 — Aufrufstellen umstellen.** Jede Konsumstelle in
   `hexagon/application` von `XCapabilityDefaults.forDialect(dialect)`
   auf `DatabaseDriverRegistry.get(dialect).xCapability()` umstellen.
   `AtomicPreserveRestoreSql`/`AtomicSequencePreserveDispatcher` (2026-09-05
   bereits auf `check(capability.supportsAtomicPreserve)` umgestellt)
   profitieren automatisch mit.
4. **P3 — Statische Tabellen entfernen.** `DialectCapabilities`,
   `SequenceCapabilityDefaults`, `RoutineCapabilityDefaults`,
   `TriggerCapabilityDefaults`, `SpatialProfilePolicy` als eigenständige
   Objekte auflösen (Inhalt liegt jetzt in den `*Driver`-Klassen).
5. **P4 — Tests umstellen.** Jede Testsuite, die heute eine
   Capability-Tabelle direkt abfragt/mockt, auf Treiber-Registrierung
   oder eine passende Test-Fixture umstellen.
6. **P5 — Vollregression.** `make docker-check` (Vollbau, kein
   `MODULES=`) und `make a-check` grün — Risiko liegt in stiller
   Verhaltensänderung für bestehende Dialekte, nicht in Oracle.

## Akzeptanzkriterien

- `hexagon/ports-common`/`hexagon/ports-read` enthalten keine
  `when (dialect)`-Switch-Tabellen für Capabilities mehr — nur noch die
  Modell-/Datenklassen (`DialectCapabilities`, `SequenceCapability` etc.)
  als reine Werttypen.
- Jeder `*Driver` liefert seine Capabilities selbst.
- Kein Verhalten ändert sich für bestehende Dialekte (reine
  Struktur-Verschiebung).

## Nicht-Scope

- `SqlIdentifiers` (Quoting) bleibt unverändert — das ist Syntax, keine
  Capability, und braucht keine Live-Treiber-Auflösung.
- Reine `DatabaseDialect → <anderes dialektspezifisches Enum>`-Mappings
  (`RenameProjectionDialect`, `DdlDialectContext`-Auswahl) sind strukturell
  unvermeidbare Zuordnungen, kein Capability-Fall — bleiben unverändert.
- Kein Auslöser-Zwang: aktiv erst bei explizitem Bedarf (z. B. wenn ein
  sechster Dialekt oder eine neue Capability-Dimension den Aufwand
  rechtfertigt).
