# Capability-Tabellen ans `DatabaseDriver`-Interface statt statischer Tabellen im Hexagon

> **Status:** In Arbeit seit 2026-09-13 (P0 gemessen, P1–P5 gebaut; alle fünf Tabellen verschoben).
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

## Designfrage vor Sub-Slice-Schnitt — **gemessen 2026-09-13**

Die Frage lautete: bleibt die Registry in jedem Aufrufkontext verfügbar, oder
braucht es zusätzlich eine leichte „Capability-only"-Auflösung ohne volle
Treiber-Registrierung? Gemessen über `^import` (Textvorkommen zählen KDoc mit
und überschätzten den Bestand um die Hälfte):

| | produktiv | Test |
|---|---|---|
| `DialectCapabilities` | 14 (9 `hexagon/application`) | 2 |
| `SequenceCapabilityDefaults` | 3 | 1 |
| `RoutineCapabilityDefaults` | 1 | 2 |
| `TriggerCapabilityDefaults` | 1 | 0 |
| `SpatialProfilePolicy` | 5 (4 `hexagon/application`) | 0 |

Die Zeilensumme (24) doppelt: zwei Dateien fragen mehr als eine Tabelle.
Eindeutig sind es **22 produktive Dateien**, davon **14 in
`hexagon/application`**, und **5 Testdateien**.

**Zur Laufzeit kein Blocker.** Beide Einstiegspunkte rufen
`RuntimeBootstrap.initialize()`, bevor irgendein Kommando läuft — `Main.kt`
für die CLI, `McpServerBootstrap`/`DataRunnerWorkers` für MCP —, und
`RuntimeBootstrap` ist die **einzige** produktive Stelle, die
`DatabaseDriverRegistry.loadAll()` aufruft. Die Sorge im Trigger („oft, bevor
überhaupt eine Verbindung/ein Treiber existiert") vermengt zwei Dinge: die
Registry hängt nicht an einer Verbindung, sie wird beim Start gefüllt. Eine
Preflight-Frage aus dem eingetippten Dialektnamen bleibt beantwortbar.

**Im Test schon.** `hexagon/application` trägt **14 der 22** produktiven
Dateien, und sein Test-Klassenpfad führt `driver-common`, aber **keinen**
konkreten Treiber — das ist kein Versehen, sondern die Schichtregel. Nach dem
Umbau bräuchte jeder dieser Tests einen registrierten `DatabaseDriver` für den
geprüften Dialekt. Eine `testFixtures`-Attrappe dafür **gibt es heute nicht**;
die Suche über alle `testFixtures`-Quellen findet keine.

**Der schwerere Punkt ist nicht die Verfügbarkeit, sondern die Form der
Registry.** `DatabaseDriverRegistry` ist ein global veränderlicher Singleton
mit `register`/`loadAll`/`clear`, und `get()` **wirft**, wenn nichts
registriert ist. Heute ist `DialectCapabilities.forTarget(dialect)` eine reine
Funktion: sie kann nicht scheitern und hängt von nichts ab. Danach hinge jede
Fähigkeitsfrage an geteiltem, ordnungsabhängigem Zustand — 59 Testdateien
fassen die Registry bereits an, inklusive `clear()`, und Tests eines Moduls
teilen sich eine JVM.

**Antwort: ja, es braucht die leichte Auflösung.** Nicht als Bequemlichkeit,
sondern damit eine Frage, die heute nicht scheitern kann, es auch danach nicht
kann. Zwei Teile:

1. Eine Auflösung, die Capabilities liefert, ohne einen vollen Treiber zu
   verlangen — und die bei unbekanntem Dialekt eine Antwort gibt statt zu
   werfen.
2. Eine `testFixtures`-Attrappe (`DatabaseDriver` mit reinen
   Capability-Rückgaben), damit `hexagon/application`-Tests einen Stub
   registrieren, statt Adapter auf ihren Klassenpfad zu ziehen.

Ohne beides verschiebt der Umbau einen reinen Wert in einen Zustand — und
tauscht eine `when (dialect)`-Tabelle gegen eine Klasse von Testfehlern, die
es heute nicht gibt.

## Wie es gebaut wurde — und wo es vom Entwurf abweicht

Drei Abweichungen, jede mit einem Grund, der beim Bauen entstand.

**1. Die Naht liegt in `ports-common`, nicht an `DatabaseDriverRegistry`.**
Der Entwurf wollte über die Registry auflösen. Das geht für `ports-read`
nicht: `hexagon/ports` (wo die Registry wohnt) hängt **von** `ports-read` ab,
nicht umgekehrt — eine Auflösung über die Registry hätte den Modulgraphen
umgedreht. `ports-read` hat aber zwei Aufrufstellen (`DdlScript`:
`batchSeparator`, `scriptPreamble`).

Deshalb ein eigener Port im untersten Ports-Modul:
`DialectCapabilityProvider` + `DialectCapabilityLookup`. Der Lookup lädt
selbst über den `ServiceLoader` und **träge** — er braucht weder Registry noch
`RuntimeBootstrap`. Damit ist die in P0 gemessene Laufzeit-Abhängigkeit gar
nicht erst entstanden: wer die Treiber-JARs auf dem Klassenpfad hat, bekommt
eine Antwort.

`DatabaseDriver` **erweitert** den neuen Port, jeder der fünf Treiber
implementiert ihn — die vom Entwurf verlangte Interface-Erweiterung ist damit
da, nur eine Ebene tiefer verankert.

**2. `forDialect`/`forTarget` bleiben als dünne Weiterleitung stehen.** Der
Entwurf wollte 20 Aufrufstellen auf `Registry.get(dialect).xCapability()`
umschreiben. Das Akzeptanzkriterium ist aber, dass die `when (dialect)`-Tabelle
verschwindet — und das tut sie. Die Frage behält ihren Namen, der Diff bleibt
lesbar, und die Aufrufstellen ändern sich nicht. Wo ein **Treiber** seine
eigene Antwort braucht (PG-Computed-Storage, MSSQL-Präambel), ruft er jetzt
sein lokales Objekt, statt über den ServiceLoader zu gehen.

**3. Keine Capability-Attrappe, sondern die echten Anbieter im Test.** P0 sagte
voraus, dass `hexagon/application`-Tests keinen Treiber sehen. Sie bekommen die
fünf Module als `testRuntimeOnly`: der ServiceLoader findet die **echten**
Werte, Testcode kann trotzdem keinen Treibertyp importieren, und `a-check`
prüft Importe — die Schichtregel bleibt in den Quellen unberührt. Eine
Attrappe mit erfundenen Werten wäre die schlechtere Wahl gewesen: dann prüften
dialektabhängige Tests gegen Fantasie statt gegen den Dialekt.
`DialectCapabilityLookup.register(...)` gibt es trotzdem — für Tests, die
bewusst eine abweichende Antwort setzen wollen.

## Die zweite Scheibe: die vier übrigen Tabellen

`SequenceCapabilityDefaults`, `RoutineCapabilityDefaults`,
`TriggerCapabilityDefaults` und `SpatialProfilePolicy` sind denselben Weg
gegangen — mit **einer** Abweichung, die der Modulgraph erzwang.

Ihre Werttypen (`SequenceCapability`, `EffectiveRoutineCapability`,
`TriggerCapability`, `SpatialProfile`) wohnen in `ports-read`.
`DialectCapabilityProvider` liegt in `ports-common` und kann sie nicht sehen.
Ein Port gehört dahin, wo seine Sprache wohnt; die Alternative wäre gewesen,
vier Werttypen nach unten zu schieben, nur damit eine Schnittstelle sie nennen
kann. Deshalb ein **zweiter** Port, `DialectReadCapabilityProvider`, in
`ports-read` — der **erbt** vom ersten, sodass ein Anbieter alle Fragen seines
Dialekts beantwortet und `dialect` nur einmal deklariert ist. Dieselbe Klasse
je Treibermodul steht unter beiden `META-INF/services`-Namen.

Nicht auf `DatabaseDriver` gelegt, anders als der Entwurf es für alle fünf
vorsah: kein Aufrufer fragt heute eine Treiber**instanz** danach, und vier
weitere abstrakte Methoden hätten dieselben 18 Attrappen wieder gebrochen. Das
Treiber**modul** liefert sie, und das ist, was das Akzeptanzkriterium meint.

**`routineCapability` nimmt die Version**, weil `MYSQL` für zwei Server steht:
Oracle MySQL kennt `CREATE OR REPLACE FUNCTION` nicht, MariaDB schon. Das
ehemalige `forMysqlServerVersion` ist damit kein Sonderweg mehr, sondern
derselbe Aufruf mit einem Argument.

**Belegt.** Je Treibermodul eine Spec, die prüft, was still brechen kann: dass
der `ServiceLoader` den Anbieter unter **seinem** Dialekt findet (ein
vertauschter Service-Eintrag fällt sonst nirgends auf — die Antworten wären
plausibel, nur für den falschen Server) und dass er alle vier Fragen
beantwortet. Alle fünf sind über eine absichtlich falsche Zusicherung als
laufend belegt.

## Was übrig bleibt

Nach beiden Scheiben tragen `ports-common` und `ports-read` **keine**
Capability-Tabelle mehr. Drei `when (dialect)` stehen dort noch, und keines
ist eine:

- `SqlIdentifiers` — Quoting, vom Plan ausdrücklich als Syntax ausgenommen.
- `TargetServerVersionParser` — liest je Dialekt eine Versionsangabe. Eine
  Zuordnung, keine Fähigkeit.
- `MeasuredServerVersions.of` — hält fest, gegen welche Version gemessen
  wurde. Das ist ein Messprotokoll und gehört nicht in den Treiber, der davon
  handelt.

## Was dabei auffiel, aber nicht dazugehört

`batchSeparator` und `scriptPreamble` sind nach dem Maßstab dieses Plans gar
keine Fähigkeiten, sondern **Syntax** — dieselbe Klasse, die der Plan für
`SqlIdentifiers` ausdrücklich ausschließt („das ist Syntax, keine
Capability"). Sie beschreiben nicht, was ein Server kann, sondern wie ein
Skript für ihn geschrieben wird. Sie sind vorerst mitgewandert; ob sie an eine
eigene Naht gehören, ist eine Frage für danach, kein Blocker hier.

## Scope-Skizze

1. **P0 — Designfrage geklärt** (siehe oben; 2026-09-13). Bleibt: die
   Capability-only-Auflösung und die `testFixtures`-Attrappe entwerfen, dann
   die Interface-Erweiterung (fünf neue `DatabaseDriver`-Methoden, mit sinnvollen
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
