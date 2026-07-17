---
status: proposed
date: 2026-07-17
decision-makers: pt9912
consulted: docs/adr/0022-ports-jdbc-entkopplung.md, docs/adr/0028-a-check-architecture-gate-scope.md, docs/adr/0015-fulltext-tsvector-neutral-type.md, spec/architecture.md, spec/neutral-model-spec.md, docs/planning/open/g2-neutrales-typmodell-jdbc-typcodes.md
informed: hexagon/ports-common, hexagon/ports-write, hexagon/ports, hexagon/application, adapters/driven/driver-common, adapters/driven/driver-postgresql, adapters/driven/driver-mysql, adapters/driven/driver-sqlite, adapters/driven/formats
---

# G2 — Import-Typkompatibilität als treiber-gelieferter Port

> **Status: proposed (2026-07-17).** Vorschlag zur Ratifizierung; nichts hiervon ist beschlossen.
> Er würde das in [ADR 0028](0028-a-check-architecture-gate-scope.md) als „G2" vertagte Problem —
> JDBC-Typcodes in der Ports-Schicht — **nicht** durch ein neutrales Typfeld im Port lösen, sondern
> durch **Wegfall des Typfelds**: die Kompatibilitätsentscheidung soll der Ziel-Treiber liefern,
> wie es der Transfer-Pfad mit `TransferTypeCompatibility` bereits vormacht.

## Kontext und Problemstellung

[ADR 0022](0022-ports-jdbc-entkopplung.md) Entscheidung 1 lautet: „Die Ports-Schicht
(`hexagon:ports-common`, `-read`, `-write`, `-execute`) exponiert kein `java.sql` mehr. JDBC lebt
ausschließlich in den Adaptern." [`spec/architecture.md`](../../spec/architecture.md) führt
dieselbe Regel als Zielbild.

Der Ist-Stand hält das auf Importebene ein und unterläuft es semantisch (verifiziert 2026-07-17):

1. **Zwei Port-Verträge tragen JDBC-Typcodes.**
   [`TargetColumn.jdbcType: Int`](../../hexagon/ports-write/src/main/kotlin/dev/dmigrate/driver/data/TargetColumn.kt)
   (`hexagon:ports-write`) und
   [`JdbcTypeHint.jdbcType: Int`](../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/format/data/JdbcTypeHint.kt)
   (`hexagon:ports-common`) transportieren `java.sql.Types`-Werte. ADR 0022 nennt beide Module
   ausdrücklich. Ein Import-/Schicht-Gate kann das prinzipiell nicht sehen — der Feldtyp ist `Int`.
2. **Die JDBC-Typcode-Tabelle steht in der Ports-Schicht.**
   [`JdbcTypeCodes`](../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/data/JdbcTypeCodes.kt)
   deklariert 29 Konstanten, deren Werte identisch mit denen von `java.sql.Types` sind
   (`BIT = -7`, `OTHER = 1111`, `LONGNVARCHAR = -16`, …) — Wert für Wert nachprüfbar, ohne
   Kommentar. Das Hexagon deklariert damit JDBCs Nummerierung als eigenen Port-Vertrag.
3. **Der Weg dahin war eine Gate-Reaktion.** Vor dem G1-Fix importierte `hexagon:application`
   `java.sql.Types`; derselbe Commit, der `a-check` scharf schaltete, entfernte diesen Import und
   legte `JdbcTypeCodes` an. `ports-jdbc-free-gate` und `a-check` prüfen **Importe** — Konstanten
   neu zu deklarieren statt sie zu importieren erfüllt beide Gates, ohne die Kopplung zu
   verringern.
4. **Das Zielbild wurde abgesenkt.** [`spec/architecture.md`](../../spec/architecture.md) trägt die
   Ausnahme inzwischen selbst: „`jdbcType: Int` bleibt **vorerst** eine eng begrenzte
   Interop-/Persistenz-Ausnahme … eine vollständige Typcode-Neutralisierung ist ein eigener
   **G2-Slice**."

[ADR 0028](0028-a-check-architecture-gate-scope.md) hat das nicht verschleiert: es verlangt
ausdrücklich, G1 nicht als G2 zu verkaufen, und kündigt G2 als eigenen Umbau an. Es war eine
**Reihenfolge**-Entscheidung. G1 ist erreicht; diese ADR schlägt den zweiten Schritt vor.

**Frage dieser ADR:** Wie verschwindet die JDBC-Semantik aus den Port-Verträgen, **ohne** die
Treffsicherheit der Importprüfung zu verlieren?

## Warum ein neutrales Typfeld im Port die falsche Antwort wäre

Der naheliegende Reflex — `jdbcType: Int` durch `NeutralType` ersetzen — scheitert an einer
Eigenschaft, die leicht übersehen wird:

**`NeutralType` ist ein geschlossenes Autoren-Vokabular; `TargetColumn` beschreibt eine fremde
Tabelle.** Die sealed class sagt, was d-migrate *erzeugen* kann.
[`loadTargetColumns`](../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/data/DataWriterUtils.kt)
liest dagegen per `ResultSetMetaData` eine Zieltabelle, die d-migrate **nicht angelegt hat**. Eine
offene Domäne in ein geschlossenes Vokabular zu zwingen, ist konstruktionsbedingt verlustbehaftet.
Für eine fremde Spalte ist `jdbcType` die **treuere** Beschreibung, nicht die schlechtere.

Der Verlust ist konkret und schon bei simplen Codes messbar, nicht erst bei Exoten.
[`JdbcToNeutralTypeMapper`](../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/data/JdbcToNeutralTypeMapper.kt)
kennt `NCHAR`, `BLOB` und `SQLXML` gar nicht; sie fallen in den `else`-Zweig auf
`NeutralType.Text`, während
[`ImportTypeCompatibility`](../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ImportTypeCompatibility.kt)
sie heute korrekt akzeptiert. `BIT(8)` würde zu `BooleanType` und damit angenommen, obwohl die
Prüfung Multi-Bit heute ablehnt; `FLOAT` verlöre die `SINGLE`-Unterscheidung. Diese Fälle sind
durch bestehende Tests festgenagelt — sie ließen sich nur durch Löschen oder Invertieren dieser
Tests „erreichen". `Interval`, `inet`, `money`, `citext`, `bit(n)`, `TINYINT` und `NCLOB` fehlen
im Modell ganz.

**Schwerwiegender als der Verlust wäre die Umkehr der Default-Richtung.** Heute landet Unbekanntes
auf `Types.OTHER` — ehrliches Nichtwissen — und beide Konsumenten verweigern daraufhin: die Matrix
fällt auf `false` durch, und der COPY-Fast-Path ist eine Allowlist, die `OTHER` nicht enthält.
Als `NeutralType.Text` würde daraus selbstbewusst-falsches Wissen und damit **Erlauben** per
Default: eine PG-`inet`/`money`/`interval`-Spalte käme neu durch den Preflight und würde neu
COPY-fähig, was das `PGobject`-Wrapping umginge. Das wäre eine Korrektheitsregression in einem
Umbau, der als Architektur-Hygiene anträte.

**Und die Kopplung bliebe.** `sqlTypeName` ist nicht entfernbar: Die Geometrie-Erkennung im
Import-Pfad läuft ausschließlich über den Typnamen, und PG-Enums meldet pgjdbc als `VARCHAR` —
nicht als `OTHER` —, sie sind allein über den Namen gegen `pg_enum` erkennbar. Ein Port, der den
`Int` verliert und den rohen Dialekt-String behält, wäre umetikettiert, nicht entkoppelt. Ein Gate
„verbiete `jdbcType` in `hexagon/**`" wäre dann grün über einem Port, der weiter PG-Typnamen
durchreicht — derselbe Falsch-grün-Fehlermodus wie bei G1, eine Ebene höher.

## Entscheidungstreiber

- **ADR 0022 Entscheidung 1 soll gelten**, nicht nur import-technisch bestehen.
- **Keine Regression der Treffsicherheit** und **kein Kippen von deny-by-default**.
- **Keine Ersatz-Kopplung.** Auch ein Dialekt-String im Port ist Passthrough
  ([ADR 0015](0015-fulltext-tsvector-neutral-type.md) verwirft ihn als Prinzip).
- **Bestehende Muster vor neuen Erfindungen.**

## Betrachtete Optionen

- **A — Port-Verträge tragen `NeutralType`.** `TargetColumn`/`JdbcTypeHint` führen
  `neutralType: NeutralType` statt `jdbcType: Int`.
- **B — Eigenes neutrales Transport-Enum** neben `NeutralType`, nur für den Datenpfad.
- **C — Status quo.** Ausnahme dauerhaft ratifizieren.
- **D — Der Port trägt gar kein Typfeld.** Die Kompatibilitätsentscheidung liefert der
  Ziel-Treiber über einen `fun interface`, analog zum bestehenden `TransferTypeCompatibility`.

## Entscheidung

**Vorgeschlagen: Option D.**

Das Muster existiert im Repo bereits und löst dasselbe Problem auf dem Schwesterpfad:
[`TransferTypeCompatibility`](../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/TransferTypeCompatibility.kt)
ist ein `fun interface`, das
[`DatabaseDriver.transferCompatibility()`](../../hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt)
liefert; alle drei Treiber implementieren es, und `DataTransferRunner` holt sich dort die
Entscheidung. Der Port trägt **kein** Typfeld — der Dialekt besitzt das Urteil. Der Vertrag benennt
den Zweck: die Entscheidung bereitstellen, ohne den Mapper zu lecken.

Zwei Eigenschaften dieses Musters sind entscheidend:

- **Sein Default ist deny.** `transferCompatibility()` ist per Default die Identitätsregel, „so a
  driver without a structural mapping never silently over-accepts". Genau die Sicherheitsrichtung,
  die Option A umkehren würde.
- **Seine strukturelle Implementierung ist ausdrücklich nicht übertragbar.**
  `StructuralTransferTypeCompatibility` funktioniert laut eigenem Vertrag, „because the target
  column type **is itself the tool's generated mapping**". Beim Import ist das Ziel fremd.
  Übernommen würde also die **Form** (treiber-gelieferter Port), nicht die Implementierung. Wer das
  verwechselt, baut die Lossigkeit von Option A nach.

Der Zuschnitt, der daraus folgt:

- `TargetColumn` behielte im Port nur, was dialektneutral und außerhalb der Adapter gebraucht wird:
  **`name` und `nullable`**.
  [`ImportTableValidator`](../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ImportTableValidator.kt)
  nutzt strukturell exakt diese beiden; `jdbcType`/`sqlTypeName` erscheinen dort nur in einer
  Fehlermeldung. Für die Meldung lieferte der Treiber eine **Anzeigezeichenkette**, keinen
  Dispatch-Schlüssel.
- `jdbcType`, `sqlTypeName` und `srid` wanderten in einen **adapter-internen** Typ in
  `driver-common` (der geteilten, erlaubten JDBC-Basis) und verließen die Adapter nicht mehr.
- `hexagon:application` fragte den Ziel-Treiber nach dem Urteil, statt selbst über Typen zu
  entscheiden. `JdbcTypeCodes` und die Matrix in `application` entfielen.
- `JdbcTypeHint` (Formate) folgte derselben Bewegung; die Konverter-Registry dürfte intern weiter
  auf `java.sql.Types` schlüsseln — sie liegt in `adapters/driven/formats` und ist dort legitim.

Warum nicht die anderen: **A** verlöre Treffsicherheit, kehrte deny-by-default um und behielte die
String-Kopplung (s. o.). **B** hätte alle Nachteile von A plus ein zweites neutrales Modell mit
dauerhafter Abbildungspflicht. **C** ist die ehrlich benennbare Alternative — sie verlangte aber
dauerhaft ein abgesenktes Zielbild und hielte eine Regel offen, die das Repo anderswo streng
durchsetzt.

## `sqlTypeName` — hier entschieden, nicht in den Slice vertagt

Diese Frage entscheidet, ob der Umbau Entkopplung oder Umetikettierung ist; sie gehört deshalb in
diese ADR:

**`sqlTypeName` verließe die Ports-Schicht zusammen mit `jdbcType`.** Es bleibt fachlich
unverzichtbar (Geometrie-Erkennung, PG-Enum-Auflösung gegen `pg_enum`, Array-Elementtyp) — aber
ausschließlich **adapterintern**. Kein Port-Vertrag trüge danach einen rohen Dialekt-Typnamen.

Damit wäre [ADR 0015](0015-fulltext-tsvector-neutral-type.md) eingehalten, statt nur zitiert: kein
Passthrough über die Schichtgrenze. Ehrlich anzumerken bleibt, dass `NeutralType.Enum.refType` und
`NeutralType.Array.elementType` **bereits heute** rohe Dialekt-Strings tragen — diese ADR ändert
daran nichts und behauptet es nicht; sie verhindert nur, **neue** Passthrough-Fläche zu schaffen.

## Konsequenzen

**Positiv**

- ADR 0022 Entscheidung 1 würde auch semantisch gelten; `JdbcTypeCodes` verschwände aus dem Hexagon.
- Kein Verlust an Treffsicherheit: der Treiber urteilte auf seinen vollen Metadaten.
- Deny-by-default bliebe erhalten und folgte dem etablierten Default des Schwestermusters.
- **Kein Rückmapper nötig.** `setNull(idx, jdbcType)` in den drei Import-Sessions braucht weiterhin
  einen `Int` — der Treiber hat ihn intern ohnehin. Ein `NeutralType→jdbcType`-Rückmapper (den es
  nicht gibt und den Option A erzwungen hätte) entfiele.
- Die Produktivfläche ist klein: es gibt genau **eine** produktive `TargetColumn`-Konstruktion
  (`DataWriterUtils`), und sie liegt bereits im Adapter.

**Negativ — bewusst in Kauf genommen**

- **Ein Port mehr.** Jeder Treiber müsste eine Import-Kompatibilität liefern; die heute geteilte
  Logik in `application` verteilte sich auf drei Dialekte. Gemeinsame Anteile gehörten nach
  `driver-common`, sonst entstünde Dreifach-Pflege.
- **Die Prüfung würde nicht einfacher, nur richtig verortet.** Das Schwestermuster zeigt, wohin es
  führt: `StructuralTransferTypeCompatibility` braucht Ausnahmen für Integral-Widening und
  DateTime, eine gepflegte Textliste und einen Vergleich über gerenderte Dialekt-Strings. Wer sich
  von D eine schlankere Matrix verspricht, wird enttäuscht.
- **Testfläche.** 26 Testdateien konstruieren `TargetColumn`; die Signaturänderung schlüge dort
  durch. Kein Golden-File und keine `testFixtures` sind betroffen.
- **Die Fehlermeldung verlöre Detail**, wenn der Treiber keine gute Anzeigezeichenkette liefert.

**Neutral**

- Das Parquet-Dateiformat änderte sich nicht: der Schreibpfad setzt `jdbcType` bereits auf `null`
  und emittiert das Feld bedingt, also nie — kein geschriebenes Manifest enthält es. Es existiert
  nur read-tolerant.
- Keine neue Modulkante nötig.
- **Die Passage in [`spec/architecture.md`](../../spec/architecture.md) muss unabhängig vom Ausgang
  bereinigt werden** — sie verletzt die Zielbild-Konvention („vorerst" ist ein Statusmarker,
  „G2-Slice" ein Abwärtsverweis auf einen Plan) in jedem Fall. D und A entfernten sie ersatzlos; C
  ersetzte sie durch eine ADR-referenzierte Dauer-Ausnahme. Das ist **kein Vorteil einer Option**,
  sondern eine Folgepflicht.

## Confirmation

Die Bestätigung darf sich **nicht** auf ein Grep nach `jdbcType` in `hexagon/**` stützen: ein Gate,
das nach dem Namen des entfernten Feldes sucht, ist durch die Entfernung selbst garantiert grün und
verifiziert nichts über die Kopplung. Genau dieser Kurzschluss hat G1 falsch-grün gemacht.

Prüfbar wäre stattdessen:

- **Verhaltensgleichheit statt Feldabwesenheit**: die bestehenden `ImportTypeCompatibility`-Tests
  (`BIT(8)` → abgelehnt, `NCHAR`/`SQLXML`/`BLOB` → akzeptiert) gälten unverändert weiter, nur gegen
  den neuen Treiber-Port. Fällt einer, ist Treffsicherheit verloren.
- **Deny-by-default**: ein Test mit einem dem Treiber unbekannten Zieltyp muss **ablehnen**, nicht
  akzeptieren.
- **Strukturell**: `JdbcTypeCodes` ist gelöscht (nicht nur ungenutzt), und kein Port-Vertrag trägt
  ein dialekt-tragendes Feld — weder `Int`-Typcode noch `String`-Typname.
- `make a-check` bliebe grün — bewiese für diese ADR aber nichts.
- Cross-Dialect- und Spatial-Round-Trips blieben grün (sie decken jsonb/uuid/enum/array/Geometrie ab).
- Die Ausnahme-Passage in `spec/architecture.md` **würde** mit dem Slice entfernt; Nachweis: keine
  Treffer mehr für „vorerst"/„G2-Slice" an dieser Stelle.

Realistischer Zuschnitt: **mehr als ein Slice** — Treiber-Port und Zuschnitt von `TargetColumn`,
Ablösung der Matrix in `application`, danach `JdbcTypeHint`/Formate.

## Weitere Informationen

- [ADR 0022](0022-ports-jdbc-entkopplung.md) — die Regel, die hier eingelöst würde.
- [ADR 0028](0028-a-check-architecture-gate-scope.md) — G1-vor-G2; kündigt diesen Umbau an und
  würde durch ihn **erfüllt, nicht ersetzt**. Bei Ratifizierung braucht 0028 einen Verweis-Zusatz:
  seine dort erteilte `jdbcType`-Interop-Erlaubnis endete damit, sonst gilt sie für einen isoliert
  lesenden Nachfolger weiter.
- [ADR 0015](0015-fulltext-tsvector-neutral-type.md) — Präzedenz „first class statt Passthrough".
- [`neutral-model-spec.md`](../../spec/neutral-model-spec.md) — Vertrag des Neutralmodells.
- [Ticket](../planning/open/g2-neutrales-typmodell-jdbc-typcodes.md) — Ist-Aufnahme und Fläche.
