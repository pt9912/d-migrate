# Implementierungsplan: 0.9.7 — F.4 View-/Trigger-/Routine-Renames

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: F.4 (vierter Slice — Renames jenseits von Tabellen/Spalten)
> **Status**: open (geplant, noch nicht gestartet)
> **Vorbedingung**: F.4 Rendering-Slice ✅, Workstream G ✅
>                  (`transactionScope`, strukturierte Statement-
>                  Serialisierung, Execution-Status), **E.1/E.2
>                  Routine-/Trigger-Renderbarkeit** ⚠️ HARTE Vorbedingung,
>                  zentraler Pre-Plan-Overlay-Gate aus dem
>                  `RENAME_MAPPING_INVALID`-Slice oder in diesem Slice
>                  mitgeliefert
> **Referenz**: `docs/planning/in-progress/diffresult-migration-plan-2.md`
>             §9 E.1/E.2/E.3 (Routine-/Trigger-Vorvertraege), §10 F.4
>             `docs/planning/done/ImpPlan-0.9.7-F.4-rendering.md`

---

## 1. Ziel

Der Rendering-Slice (F.4 zweite Scheibe) deckt nur Tabellen- und
Spalten-Renames ab. Renames anderer Objektklassen — Views,
Materialized Views, Trigger, Functions, Procedures, Sequences —
brauchen entweder das gleiche Rename-Overlay-Muster oder einen
Ersatzpfad ueber Drop+Create. Dieser Slice erweitert die
Rename-Faltung auf diese Objektklassen, sobald die Vorbedingungen aus
Workstream G und §9 erfuellt sind.

## 2. Warum nicht im ersten Slice?

Routine- und Trigger-Bodies enthalten Self-Referenzen (z.B.
`RAISE EXCEPTION 'orders.user_id missing'`, Trigger-Body mit
`NEW.user_id`), die nur sicher rename-faehig sind, wenn:

1. der Body-Speichervertrag aus Workstream G/F.2 steht (kein
   `\n\n`-Split-Heuristik, kein BEGIN-String-Sniff),
2. Body-Hash-Vergleich deterministisch und secret-scrubbed ist,
3. das Down-Artefakt den alten Body vollstaendig kennt — ohne den
   alten Body ist Rollback ein Drop+Create-Vertrag, kein Rename.

Bis dahin meldet d-migrate bei Routine-/Trigger-Diffs ohnehin
`DIALECT_UNSUPPORTED_OPERATION` (erste Matrix). Rename ist damit nur
sinnvoll, sobald diese Operationen renderbar werden.

Views sind teilweise frueher freischaltbar — `CREATE OR REPLACE VIEW`
ist im Rendering-Slice bereits Teil der Matrix —, sind aber an die
View-Dependency-Pruefung aus Workstream D.1/D.2 gebunden. Ein
View-Rename ist effektiv ein `DROP VIEW alt; CREATE VIEW neu` mit
demselben Body, was schon im Drop+Add-Pfad korrekt funktioniert.
Der Mehrwert eines expliziten `RenameView`-Operation-Subtyps ist
daher klein, solange `DependencyProjector` (dritter F.4-Slice) nicht
darauf aufbaut.

## 3. Scope

In Scope (nach Abschluss der Vorbedingungen):

- Neue `DiffOperation`-Subtypes:
  - `RenameView`
  - `RenameTrigger`
  - `RenameFunction`
  - `RenameProcedure`
  - `RenameSequence`
  Materialized Views bleiben in diesem Slice aus Scope. Der bestehende
  D.3a-Guard blockiert diff-basierte Operationen auf
  `ViewDefinition.materialized = true`, und Plan-2 D.3b sieht einen
  eigenen Materialized-View-Vertrag bzw. eine eigene Objektklasse vor.
  `RenameView` darf daher nur regular Views mit `materialized = false`
  falten.
- Overlay-Vertragserweiterung fuer eindeutige Identitaeten:
  `RenameMappingOverlayEntry.fromName`/`toName` bleiben die Felder, aber
  ihre Grammatik wird je Objektklasse gepinnt. `view` und `sequence`
  nutzen weiterhin den sichtbaren Namen. `trigger` nutzt den
  kanonischen `ObjectKeyCodec.triggerKey(table, name)`-Wert
  (`table::trigger`) und muss beim Rename dieselbe Tabelle auf beiden
  Seiten haben. `function` und `procedure` nutzen den kanonischen
  `ObjectKeyCodec.routineKey(name, parameters)`-Wert
  (`name(direction:type,...)`) und muessen dieselbe Signatur auf beiden
  Seiten haben. Damit sind ueberladene Routinen und gleichnamige
  Trigger auf verschiedenen Tabellen eindeutig selektierbar, ohne den
  `migration-overlay.v1`-Top-Level-Shape zu aendern.
- `MigrationOverlayValidator` erhaelt fuer `rename-mapping` eine
  explizite `objectType`-Whitelist bzw. erweitert die im
  `RENAME_MAPPING_INVALID`-Slice eingefuehrte zentrale Whitelist. Vor
  diesem Slice sind nur `{table, column}` gueltig; nach diesem Slice sind
  `{table, column, view, trigger, function, procedure, sequence}` gueltig.
  `materialized_view` bleibt ein BLOCKER, bis ein eigener
  `DiffObjectType.MATERIALIZED_VIEW` existiert. Fuer `trigger`,
  `function` und `procedure` validiert der Validator zusaetzlich die
  kanonische Key-Grammatik und die unveraenderte Tabellen-/Signatur-
  Identitaet. Ein `objectType = view`-Mapping ist damit syntaktisch
  gueltig; ob die konkrete `ViewDefinition.materialized = true` ist,
  kann der dokument-/fingerprint-basierte Pre-Plan-Gate ohne
  Schema-Kontext nicht entscheiden und wird erst in der schema-bewussten
  Mapper-/Planner-Phase blockiert.
- Erweiterung `OperationMapper`: konsumiert
  `RenameMappingOverlayEntry`-Eintraege mit `objectType` in
  `{view, trigger, function, procedure, sequence}` und faltet
  entsprechende Drop+Create-Paare zu Rename. Der Wert
  `objectType = materialized_view` bleibt bis zu einem echten
  `DiffObjectType.MATERIALIZED_VIEW` ungueltig und wird vom Validator
  blockiert; ein `objectType = view`-Mapping auf eine
  `ViewDefinition.materialized = true` blockiert in der Mapper-/Planner-
  Phase mit `OBJECT_RENAME_UNSUPPORTED`, bis D.3b explizit freigeschaltet
  ist. Diese Pruefung darf nicht im schemafreien Validator behauptet
  werden.
- Per-Dialekt-Renderer fuer jeden neuen Subtyp:
  - PostgreSQL: native `ALTER … RENAME TO …`-Syntax pro Objektklasse
    (`ALTER VIEW`, `ALTER TRIGGER … ON …
    RENAME TO …`, `ALTER FUNCTION … RENAME TO …`,
    `ALTER PROCEDURE … RENAME TO …`, `ALTER SEQUENCE … RENAME TO …`).
  - MySQL: View-Rename via `RENAME TABLE` (Views liegen im selben
    Namespace wie Tabellen); Trigger-Rename nur via Drop+Create
    (MySQL hat kein `ALTER TRIGGER … RENAME`); Routinen nur via
    Drop+Create. Sequence-Rename ist fuer MySQL/MariaDB nur freigeschaltet,
    wenn der jeweilige E.3-Sequence-Vertrag native bzw. emulierte Sequences
    als renderbar markiert und die Policy die Serverfamilie vor `plan()`
    kennt; bis dahin klassifiziert MySQL `RenameSequence` als `BLOCKED`.
  - SQLite: kein natives View-Rename. `ALTER TABLE ... RENAME` ist
    auf Tabellen beschraenkt und darf Views nicht alterieren; View-
    Rename laeuft daher nur ueber Drop+Create, sofern der alte und neue
    View-Body bekannt sind. Trigger-Rename nur via Drop+Create.
    Sequence-Rename ist `BLOCKED`, solange SQLite-Sequences nicht als
    eigene renderbare Objektklasse im E.3-Vertrag modelliert sind.
- Pro Dialekt eine `ObjectRenamePolicy`, die explizit pinnt, ob ein
  natives Rename verfuegbar ist oder ob der Mapper auf ein
  Drop+Create-aequivalent ausweicht. Letzteres ist semantisch
  identisch zur heutigen Drop+Add-Fallback-Logik, aber maschinenlesbar
  als Rename gekennzeichnet — fuer Report und spaetere Plan-Artefakte.
  Der Dialekt ist dabei ein expliziter Planungsinput: `DiffPlanner.plan(...)`
  erhaelt einen core-lokalen `ObjectRenamePlanningContext`, den der
  Application-/CLI-Layer aus `DatabaseDialect` und vorab bekannten Engine-
  Capabilities befuellt. Der Mapper darf keine Dialektentscheidung aus einem
  globalen Renderer oder aus spaeteren Render-Preflights ableiten.
- Plan-Artefakt-/Report-Vertrag fuer `RenameProvenance`: Weil
  `renameProvenance` keine dekorative Producer-Metadata ist, sondern
  Ausfuehrungs-, Rollback- und Provenance-Semantik traegt, muss dieser
  Slice den oeffentlichen Artefakt-Carrier eindeutig machen:
  `migration-plan.v1` nutzt als public Semantikfeld den gemeinsamen
  `renameProjections`/`RenameProjectionReport`-Carrier. Operation-level
  `renameProvenance` bleibt internes Planungs-/Mapping-Metadatum und darf
  nur dann in ein oeffentliches Artefakt serialisiert werden, wenn es dort
  als abgeleitetes, versioniertes Feld mit demselben
  `requiredFeatures`/`semanticExtensions`-Gate wie `renameProjections`
  abgesichert ist. Ein Consumer darf eine Drop+Create-Fallback-Operation
  niemals als normales Drop+Create ohne den zugehoerigen
  `renameProjections`-Eintrag interpretieren.
  Reportseitig ist `renameProvenance` kein zweiter F.4-Carrier neben
  `renameProjections`: Falls der Dependency-Projection-Slice
  `DiffResult.renameProjections` bereits eingefuehrt hat, erweitert dieser
  Slice denselben Carrier um Objekt-Renames und Drop+Create-Fallbacks oder
  referenziert ihn eindeutig aus `RenameProvenance`. Falls er frueher
  landet, fuehrt er den gemeinsamen Carrier so ein, dass der
  Dependency-Projection-Slice ihn weiterverwenden kann.
  Konkret gilt die DTO-Form aus dem Dependency-Projection-Plan:
  `RenameProjectionReport(candidateId, objectType, fromPath, toPath,
  overlaySource, overlayEntryId, overlayHash, renameOperationId,
  fallbackOperationIds, automatic, explicit, blockers, fallbackReason)`.
  `RenameProvenance` ist primaer internes Operation-Metadatum fuer Drop+Create-
  Fallbacks und wird fuer Report und das oeffentliche Artefakt in diesen
  Carrier projiziert; es
  darf keinen separaten `renameProvenance`-Reportabschnitt geben.
- Tests pro Dialekt fuer mindestens View-Rename und einen weiteren
  Subtyp (Trigger oder Sequence) — der Rest folgt dem gleichen
  Muster wie die Tabellen-Rename-Tests.
- Body-Drift ist ein eigener harter Vertrag fuer Views, Trigger,
  Functions und Procedures: Eine native `Rename*`-Operation darf nur
  entstehen, wenn Quelle und Ziel denselben Body-Vertrag erfuellen
  (gleicher kanonischer Body-Hash bzw. beide Bodies nach E.1/E.2
  verlustfrei bekannt und unveraendert). Weicht der Body ab, ist das
  kein reiner Rename. Dialekte mit sicherem Drop+Create-Vertrag fallen
  auf `DROP_CREATE_FALLBACK` mit `RenameProvenance` zurueck; fehlt alter
  oder neuer Body, blockiert der Kandidat mit `OBJECT_RENAME_UNSUPPORTED`
  statt ein natives `ALTER ... RENAME` zu planen. Der `bodyHash` in
  `RenameTrigger`/`RenameFunction`/`RenameProcedure` dient genau dieser
  Drift-Pruefung und darf nicht nur Report-Metadatum sein.
  Ein Fallback mit unbekanntem altem Body wird in diesem Slice nicht als
  nicht-rollbackbarer Up-Plan zugelassen: Der Plan blockiert vor Render mit
  `OBJECT_RENAME_UNSUPPORTED`, weil der Drop+Create-Vertrag den alten Body
  fuer Down und fuer den Provenance-Report braucht.
- Konsolidierung mit dem Tabellen-/Spalten-Rename-Vertrag: Die
  Provenance-Regel aus diesem Slice gilt nicht nur fuer die neuen
  Objektklassen. Bestehende `RenameTable`/`RenameColumn`-Operationen und
  der Dependency-Projection-Slice muessen ebenfalls `overlayEntryId`
  transportieren, damit Reports und Plan-Artefakte jeden Rename auf den
  konkreten Overlay-Eintrag zurueckfuehren koennen. Dieser Slice darf keine
  zweite, schwaechere Provenance-Semantik fuer Views/Trigger/Routinen
  einfuehren.

Aus Scope:

- View-Body-Reschreiben bei Tabellen-Rename: wird durch den
  Dependency-Projection-Slice abgedeckt; dieser Slice rendert nur
  reine View-/Routine-Renames mit unveraendertem Body.
- Routine-Argument-Renames (innerhalb der Signatur): nicht in der
  ersten Matrix, weil die Signatur den Routinen-Identifier mitformt
  (`fn(int, text)` ist ein anderes Objekt als `fn(int)`).
- Trigger-Migration auf umbenannten Tabellen: das ist
  Dependency-Projection (Folge-Operation des Tabellen-Renames), nicht
  Trigger-Rename.

## 4. Vorbedingungen

| Vorbedingung | Status | Kommentar |
| ------------ | ------ | --------- |
| Workstream G abgeschlossen (`transactionScope`, strukturierte Statement-Serialisierung, Execution-Status) | ✅ | Plan-2 §4/G.1-G.3 implementiert; neue v2-Rollback-Artefakte nutzen strukturierte Statement-Ranges |
| Routine- und Trigger-Diff-Renderbarkeit (E.1/E.2) | OFFEN | Plan-2 §9 |
| Sequence-Renderbarkeit (E.3) | TEILWEISE | PG-Slice ✅, MySQL/SQLite offen |
| F.2 Plan-Artefakt-Vertrag | ✅ erste Scheibe | unveraendert nutzbar |

Dieser Slice startet erst, wenn die `OFFEN`-Zellen fuer E.1/E.2 gruen sind.
Workstream G ist keine offene Blockade mehr, bleibt aber eine harte
Voraussetzung, die bei Slice-Start im Code-/Planstand nachgewiesen werden
muss. Ohne Routine-/Trigger-Renderbarkeit sind Rename-Down-Pfade fuer
Routinen/Trigger nicht planbar: Der Slice darf dann nicht starten. Falls ein
Mapper-/Planner-Pfad trotzdem auf eine noch nicht renderbare Objektklasse
trifft, blockiert er mit `OBJECT_RENAME_UNSUPPORTED` bzw. der Renderer meldet
fuer einen bereits geplanten, aber vom Dialekt nicht renderbaren Subtyp
`DIALECT_UNSUPPORTED_OPERATION`; das ist kein Rollback-only-
`ROLLBACK_NOT_POSSIBLE`-Fall.

## 5. Architektur

### 5.1 Operation-Modellerweiterung

Pro Objektklasse je eine neue `data class Rename*` in `DiffOperation`
(`hexagon:core`). Felder-Schema folgt dem `RenameTable`-Pattern:

```kotlin
data class RenameView(
    override val id: String,
    override val objectRef: DiffObjectRef,
    val fromName: String,
    val toName: String,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String?,
    override val phase: DiffPhase = DiffPhase.VIEWS,
    override val dependencies: Set<String> = emptySet(),
    override val reversibility: Reversibility = Reversibility.AUTOMATIC,
    override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
) : DiffOperation
```

`RenameView` deckt nur regular Views ab. Wenn die zugrunde liegende
`ViewDefinition.materialized = true` ist, bleibt der bestehende
Materialized-View-Guard aktiv und blockiert den Pfad, bis D.3b einen
eigenen Materialized-View-Rename-Vertrag freigibt.

`RenameTrigger` muss zusaetzlich den Zieltisch tragen, weil PostgreSQL
`ALTER TRIGGER <old> ON <table> RENAME TO <new>` rendert und
	`DiffObjectRef.TRIGGER` heute schemaweite Arity 1 hat:

```kotlin
data class RenameTrigger(
    override val id: String,
    override val objectRef: DiffObjectRef, // [toCanonicalTriggerKey], e.g. ObjectKeyCodec.triggerKey(tableName, toName)
    val tableName: String,
    val fromName: String,
    val toName: String,
    val bodyHash: String?,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String?,
    override val phase: DiffPhase = DiffPhase.TRIGGERS,
    override val dependencies: Set<String> = emptySet(),
    override val reversibility: Reversibility = Reversibility.AUTOMATIC,
    override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
) : DiffOperation
```

Der Mapper gewinnt `tableName`, `fromName` und `toName` fuer
`RenameTrigger` aus den kanonischen Overlay-Keys. `objectRef.path[0]`
bleibt der kanonische Ziel-Key (`ObjectKeyCodec.triggerKey(tableName,
toName)`), weil der heutige `SchemaDefinition.triggers`-Map-Key und der
bestehende `OperationMapper` Trigger ueber `table::trigger`
identifizieren. Der sichtbare Triggername wird nur aus `toName` gerendert;
Renderer duerfen ihn nicht aus `objectRef.path[0]` ableiten. Fuer natives
Rename-SQL muss der Renderer aber die bestehende Identitaet aus `fromName`
und `tableName` verwenden und nur den Zielnamen aus `toName` nehmen:
`ALTER TRIGGER <fromName> ON <tableName> RENAME TO <toName>`. Ein Overlay
`objectType = "trigger", fromName = "orders::audit_old",
toName = "orders::audit_new"` ist gueltig; `orders::audit_old ->
users::audit_new` ist kein Rename, sondern eine Cross-Table-Bewegung und
blockiert.

`RenameFunction`/`RenameProcedure` tragen zusaetzlich zur Body-Drift-
Erkennung die Routine-Signatur, weil mehrere gleichnamige Routinen mit
unterschiedlichen Parametern nebeneinander existieren koennen und
PostgreSQL `ALTER FUNCTION/PROCEDURE <name>(<argtypes>) RENAME TO ...`
rendert. Der bestehende neutrale Identitaetsvertrag nutzt dafuer
`ObjectKeyCodec.routineKey(name, parameters)`; dieser Slice darf die
Signatur nicht nur im Test ableiten, sondern muss sie im
`DiffOperation`-Payload oder in einer eindeutig referenzierbaren
Identity mitschleppen. Als Payload-Typ wird der bestehende
`ParameterDefinition`-Vertrag verwendet, weil
`ObjectKeyCodec.routineKey(...)` bereits diesen Typ konsumiert und daraus
den kanonischen `direction:type`-Key bildet. Der Slice fuehrt keinen
zweiten, fast gleichen Routine-Signaturtyp ein.

```kotlin
data class RenameFunction(
    override val id: String,
    override val objectRef: DiffObjectRef, // [toCanonicalRoutineKey], e.g. ObjectKeyCodec.routineKey(toName, params)
    val fromName: String,
    val toName: String,
    val signature: List<ParameterDefinition>,
    val bodyHash: String?,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String?,
    override val phase: DiffPhase = DiffPhase.ROUTINES,
    override val dependencies: Set<String> = emptySet(),
    override val reversibility: Reversibility = Reversibility.AUTOMATIC,
    override val risks: OperationRisks = OperationRisks(up = OperationRisk.SAFE, down = OperationRisk.SAFE),
) : DiffOperation
```

`RenameProcedure` folgt demselben Signaturvertrag. `bodyHash` dient nur
der Drift-/Strukturpruefung; er ersetzt die Signatur nicht.
`RenameSequence` hat einen einfachen Objekt-Payload (deklarative
Attribute), braucht aber eine explizite Default-Projektion: Ein
`old_seq -> new_seq`-Rename darf nicht dazu fuehren, dass
`DefaultValue.SequenceNextVal`-Spalten-Defaults im Plan weiter auf
`old_seq` zeigen.

Auch hier stammt die Signatur aus dem Overlay-Key, nicht aus einem
heuristischen Match auf sichtbare Namen. Gueltig ist z.B.
`objectType = "function", fromName = "old_fn(in:int)",
toName = "new_fn(in:int)"`. Ein Mapping auf `new_fn(in:text)` blockiert,
weil das eine Signaturänderung und damit ein anderes Routine-Objekt ist.
`objectRef.path[0]` ist der kanonische Ziel-Key
(`ObjectKeyCodec.routineKey(toName, parameters)`) und nicht bloss der
sichtbare Name. Fuer natives PostgreSQL-Rename-SQL ist der bestehende
Routine-Identifier `fromName + signature`; `toName` ist nur der neue
sichtbare Name:
`ALTER FUNCTION/PROCEDURE <fromName>(<signature>) RENAME TO <toName>`.
Renderer duerfen deshalb weder den kanonischen Ziel-Key noch `toName` fuer
die linke Seite des `ALTER ... RENAME` verwenden. Die Signatur speichert
die neutralen Parameterdefinitionen; der kanonische `direction:type`-Key
wird ausschliesslich ueber `ObjectKeyCodec.routineKey(...)` gebildet.
PostgreSQL-Rendering muss daraus die vom Dialekt benoetigte Identitaet ableiten und
dokumentieren, ob OUT-Parameter in der Argumentliste ignoriert oder als
Teil der uebergebenen Signatur behandelt werden. Diese Entscheidung wird
mit Overload-Tests fuer IN, OUT und INOUT gepinnt.

### 5.2 ObjectRenamePolicy

```kotlin
internal interface ObjectRenamePolicy {
    // Core-local discriminator, not dev.dmigrate.driver.DatabaseDialect:
    // hexagon:core must remain dependency-free.
    val dialect: ObjectRenameDialect

    fun classify(rename: ObjectRenameCandidate, context: ObjectRenamePlanningContext): RenameSupport
}

internal data class ObjectRenamePlanningContext(
    val dialect: ObjectRenameDialect,
    val capabilities: ObjectRenameCapabilities = ObjectRenameCapabilities(),
)

internal data class ObjectRenameCapabilities(
    val source: RenameCapabilitySource = RenameCapabilitySource.FILE_ONLY,
    val mysqlServerFamily: String? = null,
    val mysqlVersion: String? = null,
    val sqliteVersion: String? = null,
)

internal enum class RenameCapabilitySource {
    FILE_ONLY,
    LIVE_TARGET,
    TEST_PINNED,
}

internal enum class ObjectRenameDialect {
    POSTGRESQL,
    MYSQL,
    SQLITE,
}

internal sealed interface RenameSupport {
    /** Native ALTER … RENAME exists. */
    data object NATIVE : RenameSupport
    /** Engine has no rename for this kind; fall back to Drop+Create. */
    data class DROP_CREATE_FALLBACK(val rationale: String) : RenameSupport
    /** Rename is not safe for this dialect / kind combination at all. */
    data class BLOCKED(val code: String, val message: String) : RenameSupport
}

internal data class ObjectRenameCandidate(
    val objectType: DiffObjectType,
    val fromName: String,
    val toName: String,
    val materializedView: Boolean = false,
    val triggerTableName: String? = null,
    val routineSignature: List<ParameterDefinition> = emptyList(),
    val sourceBodyHash: String? = null,
    val targetBodyHash: String? = null,
)
```

Der Mapper konsultiert diese Policy bevor er einen Rename-Subtyp
emittiert. `DiffObjectType` allein reicht nicht: `VIEW` muss zwischen
regular/materialized unterscheiden, `TRIGGER` braucht den Tabellenkontext
fuer PostgreSQL, und Routinen brauchen eine Signatur, weil PostgreSQL
Funktionen/Prozeduren ueber Name plus Argumenttypen identifiziert.
`DROP_CREATE_FALLBACK` erzeugt zwar weiterhin Drop+Create-Operationen,
aber mit `renameProvenance = ...`-Metadatum, sodass Report und spaetere
Plan-Artefakte das nutzerseitig erwartete Rename als Vertrag erkennen
koennen.

Die Policy erhaelt den `ObjectRenamePlanningContext` bei jeder
Klassifikation. Die `dialect`-Property ist nur ein stabiler Discriminator
fuer Registrierung und Tests; runtime-abhaengige Entscheidungen muessen
aus den uebergebenen Capabilities stammen.

`DiffPlanner.plan(...)` erhaelt dafuer zusaetzlich zu den Overlays einen
`ObjectRenamePlanningContext`. Der Application-/CLI-Layer mappt
`DatabaseDialect.POSTGRESQL/MYSQL/SQLITE` auf `ObjectRenameDialect` und
befuellt `ObjectRenameCapabilities` nur mit Informationen, die vor dem ersten
Plan-Aufruf verlustfrei bekannt sind. Datei-zu-Datei nutzt `FILE_ONLY` und
konservative Defaults. Execute-Pfade duerfen read-only Capability-Probes nur
vor dem ersten `plan()` ausfuehren; nachgelagerte Preflights bestaetigen oder
blockieren den Plan, planen aber keinen Rename-Fallback mehr um. So bleibt
die Native/Fallback/Blocked-Klassifikation reproduzierbar und testbar, ohne
`hexagon:core` von `dev.dmigrate.driver.DatabaseDialect` abhaengig zu machen.

`RenameCapabilitySource` ist hier als gemeinsamer core-lokaler Rename-
Capability-Carrier gemeint, nicht als zweiter konkurrierender Enum-Typ:
Wenn der Dependency-Projection-Slice bereits einen gleichwertigen
`RenameCapabilitySource` eingefuehrt hat, wird dieser Typ wiederverwendet.
Falls dieser Slice zuerst landet, fuehrt er den gemeinsamen Typ so ein,
dass der Dependency-Projection-Slice ihn ohne semantischen Drift nutzen
kann. Alternativ muessen die Typen eindeutig benannt werden; zwei
gleichnamige Enums im selben Core-Package sind nicht zulaessig.

Der Context ist absichtlich getrennt vom
`RenameProjectionCapabilities`-Input des Dependency-Projection-Slices. Falls
beide Slices im selben Implementierungsfenster landen, duerfen sie einen
gemeinsamen core-lokalen Carrier teilen; der Vertrag bleibt aber: alle
dialekt- und runtime-abhaengigen Rename-Entscheidungen muessen vor dem Mapper
im Planungsinput stehen.

Auch die Overlay-Freischaltung fuer neue `objectType`-Werte passiert vor dem
Mapper im zentralen Pre-Plan-Gate. Der Mapper darf unbekannte oder noch nicht
freigeschaltete Objektklassen nicht still ueberspringen; solche Eintraege
muessen vor `plan()` als `RENAME_MAPPING_INVALID` blockieren.

`BLOCKED` ist ein Mapper-/Planner-Ergebnis, kein Renderer-Ergebnis. Der
Mapper emittiert fuer diesen Kandidaten keinen `Rename*`-Subtyp und keinen
`renameProvenance`-Fallback, sondern eine BLOCKER-Diagnostic
`OBJECT_RENAME_UNSUPPORTED` mit Objektklasse, Dialekt und Rationale.
`DIALECT_UNSUPPORTED_OPERATION` bleibt Renderer-Faellen vorbehalten, in
denen ein tatsaechlich geplanter `DiffOperation`-Subtyp vom Dialekt nicht
gerendert werden kann.

Der aktuelle Core-Identitaetsvertrag modelliert `TRIGGER`, `FUNCTION`,
`PROCEDURE` und `VIEW` als schemaweite `DiffObjectRef` mit Arity 1.
Dieser Slice behaelt diese Arity bei, legt aber fuer Trigger/Routinen
fest, dass `objectRef.path[0]` der kanonische `ObjectKeyCodec`-Ziel-Key
ist, nicht der sichtbare Name. Damit bleibt der oeffentliche
`DiffObjectRef`-Shape klein, waehrend die gleiche Identitaet genutzt wird
wie in `SchemaDefinition.triggers/functions/procedures`. Sichtbare Namen,
Tabellenkontext und Routine-Signatur muessen in den `Rename*`-Subtypes
und im `ObjectRenameCandidate` separat getragen werden.

Tests muessen explizit pinnen, dass zwei gleichnamige Routinen mit
unterschiedlicher Signatur und gleichnamige Trigger auf verschiedenen
Tabellen nicht versehentlich als eindeutiges Rename-Paar gefaltet werden.

Wichtig: Die Eindeutigkeit darf nicht erst beim `DiffOperation`-Payload
entstehen. Der Overlay-Index muss fuer Trigger/Routinen bereits mit den
kanonischen ObjectKeys arbeiten; die Cross-Document-Uniqueness-Pruefung
aus F.4 muss `objectType + canonicalKey` verwenden, nicht nur
`objectType + sichtbarer Name`.

`RenameFunction` und `RenameProcedure` muessen die Signatur zwingend als
eigenes Feld tragen und die Renderer duerfen nicht aus
`objectRef.path[0]` allein rendern. Fuer PostgreSQL ist die Signatur
Bestandteil des SQL-Templates: linke Seite `fromName(signature)`, rechte
Seite `toName`. Fuer Dialekte mit Drop+Create-Fallback bleibt sie Teil der
Provenance, damit Report und Plan-Artefakte den konkreten Routine-Overload
benennen koennen.

Da die heutigen `Create*`/`Drop*`-Operationen kein solches Feld haben,
gehoert eine kleine Operation-Modellerweiterung zu diesem Slice:

```kotlin
data class RenameProvenance(
    val candidateId: String,
    val objectType: DiffObjectType,
    val fromPath: List<String>,
    val toPath: List<String>,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String?,
    val fallbackReason: String,
)
```

Alle `Create*`/`Drop*`-Subtypes, die als `DROP_CREATE_FALLBACK`
emittiert werden koennen, erhalten optional
`renameProvenance: RenameProvenance? = null`. Native `Rename*`-
Subtypes tragen die Provenance weiterhin direkt ueber `fromName`,
`toName`, `overlaySource`, `overlayEntryId` und `overlayHash`.
Der Report-Builder darf `RenameProvenance` nicht direkt serialisieren,
sondern erzeugt daraus einen `RenameProjectionReport` mit
`renameOperationId = null`, den tatsaechlichen Drop+Create-Operation-IDs in
`fallbackOperationIds` und leerem `automatic`/`explicit`, sofern keine
Dependency-Projection beteiligt ist.
`overlayEntryId` ist Pflicht, weil ein einzelnes Overlay mehrere
Rename-Mappings enthalten kann und `overlaySource + overlayHash` die
autorisierende Entry-Zeile nicht eindeutig identifiziert. Reports und
Plan-Artefakte duerfen Entry-Provenance daher nicht aus Operation-IDs
oder Mapping-Reihenfolge rekonstruieren.

Diese Pflicht gilt F.4-weit: Falls `RenameTable`/`RenameColumn` aus dem
Rendering- oder Dependency-Projection-Pfad noch kein `overlayEntryId`
tragen, wird das in demselben Vertrag nachgezogen, bevor
`RenameProvenance` im Plan-Artefakt oeffentlich wird. Ein Artefakt darf
nicht fuer neue Objektklassen genaue Entry-Provenance besitzen, aber fuer
Tabellen-/Spalten-Renames nur auf den Dokument-Hash zeigen.

Der Report-Vertrag nutzt dabei den gemeinsamen F.4-Carrier fuer
Rename-Projection/-Provenance. Es darf nicht gleichzeitig
`renameProjections` fuer Tabellen-/Spalten-Mischfaelle und ein separater,
semantisch gleichwertiger `renameProvenance`-Reportabschnitt fuer
View-/Trigger-/Routine-Fallbacks existieren.

Artefakt-Gate: Der oeffentliche Plan-Artefakt-Vertrag behandelt
`renameProjections` als versionierte Semantik. Der Slice aktualisiert den
F.2-Artefaktvertrag, den JSON-Codec/Validator und Golden-Files so, dass
alte Consumer die neue Semantik entweder bewusst blockieren
(`requiredFeatures`/`semanticExtensions`) oder sie als bekanntes Feld
korrekt lesen. `RenameProvenance` darf operation-level nur als interne
Ableitung oder als separat gegatetes Zusatzfeld auftauchen; der fuer
Consumer verbindliche Rename-Fallback-Vertrag ist der
`RenameProjectionReport`-Eintrag. Ein unbekanntes optionales Feld reicht
hier nicht, weil sonst ein Drop+Create-Fallback ohne Rename-Vertrag
ausgefuehrt werden koennte.

Fuer `RenameSequence` fuegt der Slice eine Projektionsregel hinzu: Alle
`CreateTable`, `AddColumn` und `AlterColumnDefault`-Operationen, deren
`DefaultValue.SequenceNextVal` auf die umbenannte Sequenz zeigt, muessen
auf den Zielnamen zeigen und eine Dependency auf die finale
`RenameSequence`-ID tragen. Existiert eine aktuelle oder gewuenschte
Spalten-Default-Referenz auf die alte Sequenz, die nicht verlustfrei auf
den Zielnamen projiziert werden kann, blockiert der Rename mit
`OBJECT_RENAME_UNSUPPORTED`, statt inkonsistentes Default-SQL zu rendern.
`DependencyAnalyzer` muss deshalb neben `CreateSequence` auch
`RenameSequence` als Sequenz-Provider fuer `SequenceNextVal`-Defaults
kennen.

Diese Reprojection darf nicht von der heutigen Mapper-Reihenfolge abhaengen,
in der Tabellen-/Spaltenoperationen vor Sequenzoperationen entstehen. Der
Slice fuehrt deshalb entweder einen gemeinsamen Object-Rename-Projector ein,
der alle Rename-Candidates zuerst sammelt und danach die finale Operationenliste
inklusive Default-Rewrites erzeugt, oder einen expliziten Post-Map-Rewrite
zwischen Mapper und `DependencyAnalyzer`. In beiden Varianten gilt: keine
`CreateTable`-, `AddColumn`- oder `AlterColumnDefault`-Operation darf mit dem
alten Sequenznamen in die finale `DiffResult.operations`-Liste gelangen, wenn
ein autorisiertes `RenameSequence` fuer dieselbe Sequenz geplant wird.

### 5.3 Renderer-Integration

Jeder Dialekt-Renderer erweitert den exhaustiven `when (op)`-Block
um die neuen Subtypes. Die SQL-Templates sind in den jeweiligen
`*DiffOtherOps`-Klassen verankert (Views/Routines/Trigger liegen
schon dort).

Allgemeine Native-Rename-Regel: die linke Seite des SQL-Templates muss
immer die bestehende Objektidentitaet aus `fromName` plus ggf. Tabellen-
oder Signaturkontext verwenden; die rechte Seite ist der neue sichtbare
Name `toName`. `objectRef.path[0]` bleibt der kanonische Ziel-Key fuer
Plan/Report/ID-Stabilitaet und darf nicht als bestehender Objektname in
`ALTER ... RENAME` gerendert werden. Tests pinnen dies mindestens fuer
`RenameView`, `RenameSequence`, `RenameTrigger` und einen ueberladenen
`RenameFunction`-Fall.

## 6. Akzeptanzkriterien

- [ ] Die fuenf neuen `Rename*`-`DiffOperation`-Subtypes sind in
      `hexagon:core` definiert.
- [ ] `OperationMapper` konsumiert `RenameMappingOverlayEntry`-
      Eintraege mit den neuen `objectType`-Werten und faltet
      strukturkonsistente Paare zu Rename — analog zum
      Tabellen/Spalten-Pfad inkl. `RENAME_OVERLAY_STRUCTURAL_MISMATCH`-
      Warning fuer Mischfaelle.
- [ ] Body-Drift ist fuer View-/Trigger-/Routine-Renames gepinnt:
      unveraenderter kanonischer Body erlaubt natives Rename bzw. die
      dialektspezifische Rename-Policy; abweichender Body fuehrt nicht zu
      einem nativen `Rename*`, sondern zu Drop+Create-Fallback mit
      `RenameProvenance`, sofern beide Bodies renderbar bekannt sind;
      unbekannter alter oder neuer Body blockiert mit
      `OBJECT_RENAME_UNSUPPORTED`. Tests decken mindestens eine Function
      oder Procedure und einen Trigger/View-Fall ab.
- [ ] `ObjectRenamePolicy` ist je Dialekt implementiert und pinnt die
      Native/Fallback/Blocked-Klassifikation gegen die Dialekt-Doku,
      ohne `hexagon:core` von `DatabaseDialect` abhaengig zu machen.
- [ ] `ObjectRenamePolicy.classify(...)` konsumiert den
      `ObjectRenamePlanningContext`; runtime-abhaengige Entscheidungen
      duerfen nicht allein aus der registrierten Policy-`dialect`-Property
      abgeleitet werden.
- [ ] `DiffPlanner.plan(...)` bzw. die Mapper-Grenze konsumiert einen
      core-lokalen `ObjectRenamePlanningContext`; der Application-/CLI-Layer
      mappt `DatabaseDialect` auf `ObjectRenameDialect` und befuellt
      Capabilities vor dem ersten Plan-Aufruf. Tests pinnen, dass Renderer-
      oder Post-Plan-Preflight-Zustand die Policy-Entscheidung nicht
      nachtraeglich veraendert.
- [ ] `MigrationOverlayValidator` blockiert unbekannte
      `rename-mapping.objectType`-Werte vor `plan()` ueber die zentrale
      Whitelist. Dieser Slice erweitert die zuvor gueltige Whitelist
      `{table, column}` bewusst um `view`, `trigger`, `function`,
      `procedure` und `sequence`; `materialized_view` bleibt blockiert.
- [ ] `RenameView` blockiert `ViewDefinition.materialized = true` in der
      schema-bewussten Mapper-/Planner-Phase mit
      `OBJECT_RENAME_UNSUPPORTED`; der schemafreie Pre-Plan-Gate blockiert
      nur `objectType = materialized_view`, nicht ein syntaktisch gueltiges
      `objectType = view` ohne Schema-Kontext. Kein Renderer darf
      `ALTER MATERIALIZED VIEW` oder einen Drop+Create-Fallback fuer
      Materialized Views aus diesem Slice emittieren.
- [ ] Trigger-/Routine-Rename-Overlays nutzen kanonische
      `ObjectKeyCodec`-Keys. Tests decken gleichnamige Trigger auf
      verschiedenen Tabellen und ueberladene Routinen ab; ein Mapping
      mit anderer Trigger-Tabelle oder anderer Routine-Signatur blockiert
      vor der Mapper-Faltung.
- [ ] Die Policy klassifiziert nicht nur nach `DiffObjectType`, sondern
      nach `ObjectRenameCandidate` inklusive materialized-Flag,
      Trigger-Table und Routine-Signatur.
- [ ] `RenameTrigger` enthaelt `tableName`; PostgreSQL-Renderer nutzt
      `fromName` und `tableName` fuer die bestehende Identitaet in
      `ALTER TRIGGER <fromName> ON <tableName> RENAME TO <toName>`.
- [ ] `RenameTrigger.objectRef.path[0]` ist der kanonische Ziel-Key
      `ObjectKeyCodec.triggerKey(tableName, toName)`; Report/Renderer
      nutzen `tableName`/`toName` fuer sichtbare Ausgabe und rendern
      keinen Triggernamen direkt aus dem kanonischen Key.
- [ ] Tests pinnen ueberladene Routinen: identische Namen mit
      unterschiedlicher Signatur duerfen nicht ohne eindeutige Signatur
      gefaltet werden.
- [ ] `RenameFunction`/`RenameProcedure` tragen die Routine-Signatur im
      Operation-Payload als `List<ParameterDefinition>` und
      `objectRef.path[0]` ist der kanonische Ziel-Key
      `ObjectKeyCodec.routineKey(toName, parameters)`; der
      PostgreSQL-Renderer rendert die linke Seite aus `fromName` plus der
      dialektspezifisch aus `signature` abgeleiteten Argumentliste und
      die rechte Seite aus `toName`; er rendert nicht aus dem blossen
      Namen oder dem kanonischen Ziel-Key.
- [ ] `RenameProvenance` ist als optionales internes Metadatum auf allen
      Drop+Create-Fallback-Operationen modelliert und wird im Report sowie im
      oeffentlichen Plan-Artefakt nur ueber den gemeinsamen
      F.4-Provenance-Carrier `renameProjections` ausgegeben. Eine zusaetzliche
      operation-level-Ausgabe in `migration-plan.v1` ist nur als separat
      gegatetes, abgeleitetes Zusatzfeld erlaubt; ohne dieses Gate bleibt
      `RenameProvenance` aus oeffentlichen Plan-Artefakten heraus.
      Es enthaelt `candidateId`, `fromPath`, `toPath` und `overlayEntryId`,
      damit mehrere Rename-Mappings im selben Overlay eindeutig auf den
      autorisierenden Entry zurueckgefuehrt werden koennen.
- [ ] `migration-plan.v1` behandelt `renameProjections` als versionierte
      Semantik: JSON-Codec, Validator, Golden-Files und Compat-Tests
      pinnen entweder ein bekanntes Feld oder ein
      `requiredFeatures`/`semanticExtensions`-Gate. Alte Consumer duerfen
      die Projection-/Provenance-Semantik nicht ignorieren und den Fallback
      als normales Drop+Create ausfuehren.
- [ ] `RenameProvenance` und `renameProjections` sind als ein gemeinsamer
      F.4-Report-/Artefaktvertrag modelliert: Operationen duerfen optional
      `RenameProvenance` tragen, aber Report und Artefakt nutzen genau den
      `RenameProjectionReport`-Carrier. Tests pinnen, dass ein Tabellen-/
      Spalten-Rename, ein nativer Objekt-Rename und ein Drop+Create-Fallback
      nicht in zwei voneinander unabhaengigen Provenance-Abschnitten landen.
- [ ] `spec/cli-spec.md` §6.1 dokumentiert den gemeinsamen F.4-
      Reportabschnitt fuer Rename-Projection/-Provenance, inklusive
      Drop+Create-Fallbacks mit Rename-Provenance. Plan- und Report-
      Goldens allein reichen nicht als oeffentlicher CLI-Vertrag.
- [ ] Alle nativen `Rename*`-Subtypes tragen `overlayEntryId` neben
      `overlaySource` und `overlayHash`; Report/Plan-Artefakt nutzt diese
      Felder direkt und rekonstruiert Entry-Provenance nicht aus
      Operation-ID-Konventionen.
- [ ] Der gemeinsame F.4-Provenance-Vertrag ist einheitlich: bestehende
      `RenameTable`/`RenameColumn`, neue Objekt-Renames und
      Drop+Create-Fallbacks mit `renameProvenance` tragen alle
      `overlayEntryId`. Ein Test nutzt ein Overlay mit mehreren Rename-
      Mappings und prueft fuer mindestens einen Tabellen-Rename und einen
      neuen Objekt-Rename die konkrete Entry-Zuordnung.
- [ ] Native Rename-Renderer verwenden `fromName` fuer die bestehende
      Objektidentitaet und `toName` fuer den Zielnamen; Tests pinnen, dass
      kein Renderer versehentlich den kanonischen Ziel-Key aus
      `objectRef.path[0]` als linke Seite des `ALTER ... RENAME` nutzt.
- [ ] Renderer rendern alle geplanten Subtypes pro Dialekt; `BLOCKED`-
      Faelle entstehen bereits im Mapper/Planner als
      `OBJECT_RENAME_UNSUPPORTED` und erreichen keinen Renderer.
      `DROP_CREATE_FALLBACK` emittiert Drop+Create mit
      `renameProvenance`-Markierung im Report.
- [ ] `RenameSequence` reprojiziert `DefaultValue.SequenceNextVal`-
      Referenzen in `CreateTable`, `AddColumn` und `AlterColumnDefault`
      auf den Zielnamen und setzt eine Dependency auf die finale
      `RenameSequence`-ID; unverlustig nicht reprojizierbare Defaults
      blockieren mit `OBJECT_RENAME_UNSUPPORTED`.
- [ ] Die Sequence-Default-Reprojection ist unabhaengig von der heutigen
      Mapper-Reihenfolge implementiert: Tests zeigen, dass Tabellen-/
      Spaltenoperationen, die vor dem Sequenz-Candidate gemappt werden,
      nach dem Projector/Post-Map-Rewrite trotzdem den Ziel-Sequenznamen
      und die Dependency auf die finale `RenameSequence`-ID tragen.
- [ ] Die Sequence-Rename-Policy ist pro Dialekt gepinnt: PostgreSQL nutzt
      natives `ALTER SEQUENCE ... RENAME`, MySQL/MariaDB wird nur bei
      nachgewiesenem E.3-Sequence-Rendervertrag freigeschaltet und
      SQLite bleibt bis zu einem eigenen Sequence-Objektvertrag `BLOCKED`.
- [ ] Tests decken `RenameSequence old_seq -> new_seq` mit einer
      betroffenen Spalten-Default-Referenz ab und pruefen, dass weder
      Up- noch Down-Plan SQL mit dem alten Sequenznamen in Defaults
      erzeugt.
- [ ] Pro Dialekt mindestens je ein Positivtest fuer View-Rename und
      einen weiteren Subtyp (Trigger oder Sequence).
- [ ] Down-Pfad ist getestet: inverser Rename fuer alle nativ
      unterstuetzten Faelle; Drop+Create-Fallback ist nur zulaessig,
      wenn der alte Body bekannt und renderbar ist. Fehlt der alte oder
      neue Body, blockiert bereits die Planung mit `OBJECT_RENAME_UNSUPPORTED`
      statt einen Up-Plan mit spaeterem `ROLLBACK_NOT_POSSIBLE` zu erzeugen.
- [ ] `roadmap.md` und `diffresult-migration-plan-2.md §10 F.4`
      bekommen einen Status-Update mit Datum des Slice-Abschlusses.

## 7. Definition of Done

- [ ] Alle Akzeptanzkriterien aus §6 erfuellt.
- [ ] Vorbedingungen aus §4 sind nachweisbar gruen: Workstream G bleibt
      implementiert, E.1/E.2 sind abgeschlossen.
- [ ] `make docker-test` gruen, Output in `/tmp/build.log`.
- [ ] Coverage je betroffenem Modul ≥ 90%.
- [ ] Plan-Datei nach `docs/planning/done/` verschoben.

## 8. Risiken

### 8.1 Trigger-Rename in MySQL/SQLite

Beide Dialekte unterstuetzen kein natives `ALTER TRIGGER … RENAME`.
Drop+Create-Fallback braucht den alten Trigger-Body — der ist
Workstream-G-pflichtig. Konsequenz: Trigger-Rename ist in MySQL/SQLite
effektiv erst nach Workstream G + Body-Speichervertrag freischaltbar.

### 8.1a View-Rename in SQLite

SQLite kann Tabellen und Spalten nativ umbenennen, aber keine Views per
`ALTER TABLE ... RENAME` alterieren. SQLite-View-Renames sind deshalb
immer `DROP_CREATE_FALLBACK`; wenn der alte oder neue View-Body fehlt,
klassifiziert die Policy den Fall als `BLOCKED` statt einen nativen
Rename anzunehmen.

### 8.2 Routine-Signatur-Renames

PostgreSQL erlaubt `ALTER FUNCTION fn(int) RENAME TO gn`, aber nur
fuer dieselbe Signatur. Eine Aenderung der Signatur ist effektiv ein
neues Objekt. Ein explizites Rename-Mapping mit abweichender Signatur
ist deshalb invalid und blockiert vor der Faltung mit
`OBJECT_RENAME_UNSUPPORTED` bzw. im Pre-Plan-Key-Check, wenn die
Abweichung bereits aus den Overlay-Keys sichtbar ist. Der normale
Drop+Create-Pfad bleibt nur fuer Diff-Paare ohne autorisierendes
Rename-Mapping zustaendig.

### 8.3 Materialized-View-Rename bleibt blockiert

Materialized Views bleiben in diesem Slice blockiert, auch wenn
PostgreSQL ein natives `ALTER MATERIALIZED VIEW ... RENAME` kennt.
Refresh-, Staleness-, Locking- und Rollback-Verhalten gehoeren zum
D.3b-Vertrag; ohne diesen Vertrag darf der Renderer keinen
Materialized-View-Rename ausgeben.

## 9. Out-of-Scope-Verweis

CLI-/Overlay-Eingabe-Pfade bleiben unveraendert; dieser Slice fuegt
nur neue `objectType`-Werte zum bestehenden Rename-Overlay-Vertrag
hinzu. View-Body-Reschreiben bei Tabellen-Rename ist in
ImpPlan-0.9.7-F.4-dependency-projection.md verortet.
