# PostgreSQL: der Rollback nach erweiterter Werteliste stellt den CHECK nicht her (Issue #61)

> **Status:** In Arbeit (2026-10-25); P0 und P1 abgeschlossen, P2 umgesetzt. **Vorbedingung:** keine.
> **Aktiviert** mit dem ersten Implementierungs-Commit (Move von `../next/`).
> GitHub-Issue: [#61](https://github.com/pt9912/d-migrate/issues/61). Bezug: ADR 0055 (Wertevorrat im zielbewussten
> Vergleich), ADR 0060 (`RefTypeVocabulary`, SQLite); Lastenheft: [LF-014](../../../spec/lastenheft-d-migrate.md#lf-014) („Migrations-Rollback"),
> gegengelesen.

## Ziel

`schema migrate --generate-rollback` auf PostgreSQL liefert nach einer **erweiterten Werteliste** einer Inline-Enum-Spalte
entweder ein Down-Skript, das den alten `CHECK` wiederherstellt, oder blockiert den Down ausdrücklich (`downBlocked: true`, Blocker `ROLLBACK_NOT_POSSIBLE`, Exit 8).
Ein stilles Exit 0 mit einem Down, der den weit offenen `CHECK` stehen lässt, entfällt.

**Zur Meldung `planFullyRollbackable`:** das Feld kommt aus `SchemaMigrateReportBuilder.kt:272-274` und prüft nur die Up-Statements
auf `FULLY_TRANSACTIONAL`; `true` heißt „transaktional“, **nicht** „rückführbar“. Das Issue deutet das Feld schief; ein Down-Blocker
schlägt sich dort nicht nieder. Das Down-Signal ist `downBlocked` (`SchemaMigrateReportBuilder.kt:268`, aus `renderedDown?.isBlocked`; definiert in `SchemaMigrateRunner.kt:1493`); ein Down-Blocker fließt über
`mergeDownIntoUp` (`SchemaMigrateRenderPipeline.kt:508-529`) nur in `blockers`/`primaryBlockedReason`. Ob das Feld selbst nachzuschärfen ist, entscheidet P1.

## Problem (Issue #61, gemeldet für 1.10.0 und 1.9.1; am Code gelesen, noch nicht gemessen)

Tabelle `t (id integer, e enum values [a, b] required default a)`, Datenbank aus `schema generate --target postgresql`
(`e TEXT … CHECK ("e" IN ('a','b'))`), Datei mit `values: [a, b, c]`. Der Up-Pfad erweitert den CHECK; `down.sql` enthält nur
`ALTER TABLE "t" ALTER COLUMN "e" TYPE TEXT;`, der Report meldet `planFullyRollbackable: true` (Transaktionalität des Up, kein Rückführbarkeitsbefund), `schema rollback --execute --allow-destructive` endet mit Exit 0, der CHECK lautet
weiter `= ANY (ARRAY['a','b','c'])`, `INSERT … 'c'` gelingt. Auf SQLite lehnt die Datenbank `c` nach dem Rollback ab.

**Wie der Up-Pfad den CHECK ersetzt:**
[`PostgresDiffTableOps.renderAlterColumnType`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffTableOps.kt)
wählt den Zieltyp je Richtung (`UP`: `op.after`, `DOWN`: `op.before`), löst den bisherigen Wertevorrat über
`dropExistingEnumValueCheck` (`DROP CONSTRAINT IF EXISTS <Katalogname>`) und fügt über `EnumValueCheck.clause` einen neuen
`ADD CHECK (… IN (…))` an, **wenn** `EnumValueCheck.inlineValues(targetType)` die Werte kennt. Es gibt keine eigene
`ModifyEnum`-Operation (gesucht, nicht vorhanden): eine Wertelistenänderung läuft als `AlterColumnType` über
[`OperationMapper.mapColumnChange`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/OperationMapper.kt).
Der Katalogname kommt aus
[`PostgresDiffRenderContext.enumValueCheckName`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffRenderContext.kt),
das für `UP` das **Ist-** (`currentSchema`), für `DOWN` das **Soll-Schema** (`desiredSchema`, die Datei) befragt.

**Ursache am Code (zwei Lücken, die nur zusammen den Befund ergeben; P0 bestätigt sie durch Messung):**

* **L1 — `op.before` trägt keine Werte.** Der Reader liefert `TEXT` plus CHECK-Constraint; den Wertevorrat faltet nur der Vergleich
  (`TableComparator.enumVocabularies`, `EnumCheckProjection`, ADR 0055). Im Down ist `targetType = op.before = Text`
  (`PostgresDiffTableOps.kt:201`), `typeUnchanged` ist `true`, `inlineValues(Text)` ist `null` → nur `TYPE TEXT`, kein `ADD CHECK`.
* **L2 — der erweiterte CHECK wird im Down nicht gelöst.** `enumValueCheckName` (`PostgresDiffRenderContext.kt:338-344`) befragt im
  Down das Soll-Schema, also die Datei; diese führt den Wertevorrat inline, nicht als `CHECK`-Constraint → `null` → kein `DROP`.
* **Folge für den Fix:** wer nur L1 behebt, erzeugt `ADD CHECK (… IN ('a','b'))` **ohne** `DROP` — alter (erweiterter) und neuer
  CHECK stehen nebeneinander; wer nur L2 behebt, löst den CHECK und lässt die Spalte ungeprüft. Option (a) muss **beide** Lücken
  schließen.
* **L3 — keine Meldung.** `AlterColumnType` ist `AUTOMATIC_WITH_DATA_RISK` (`DiffOperation.kt:172`); der Blocker
  `ROLLBACK_NOT_POSSIBLE` entsteht im Renderer nur für `NOT_REVERSIBLE` (`PostgresDiffDdlGenerator.kt:77-96`). Ein Down, das sein Ziel
  verfehlt, löst keinen Blocker aus; der Renderer prüft sein Ergebnis nicht gegen den Zustand vor dem Up.

**Gegenprobe SQLite:** dort ersetzt der Tabellenneubau (benannter CHECK `ck_<tabelle>_<spalte>`, `EnumValueCheck.name`) den
Wertevorrat in beiden Richtungen; der Reader sieht den benannten CHECK als Constraint der Ist-Seite. Der Unterschied zu
PostgreSQL ist die Rendering-Strategie (Neubau vs. `ALTER … DROP/ADD CONSTRAINT` mit unbenanntem CHECK), nicht ADR 0060:
`RefTypeVocabulary` betrifft nur `ref_type`-Enums, und das Dialekt-Gate (ADR 0060, Entscheidung 2) setzt `resolvesRefTypeVocabulary` nur für
SQLite; PostgreSQL führt den Typ selbst. Der Inline-Pfad hier hängt an ADR 0055.

## Richtung (nach P0/P1)

| Option | Inhalt | Für | Wider |
| ------ | ------ | --- | ----- |
| (a) Down stellt die alte Werteliste her | beide Lücken schließen: L1 (Werte der Ist-Seite kennen) **und** L2 (den erweiterten CHECK im Down lösen) | Rollback tut, was er verspricht; paritätisch zu SQLite | Namensfrage (siehe unten); die Werte liefert `op.before` (Modell/Mapper, Tag-Folge) oder der Renderer aus `currentSchema` (nur Adapter) |
| (b) nicht als rückführbar melden | der Renderer setzt `ctx.addBlocker(ROLLBACK_NOT_POSSIBLE)` für diesen Fall **unabhängig von `op.reversibility`** (Adapter-only); setzt man stattdessen die Reversibility der Operation herab, ist es eine Hexagon-Änderung | klein, ehrlich | Nutzer verliert einen Rollback, den SQLite kann |
| (c) gestaffelt | (b) zuerst als Schutz, dann (a); (b) fällt für den Fall weg | beseitigt die falsche Zusicherung schnell | zwei Auslieferungen; die (b)-Tests (Exit 8, Blocker) müssen mit (a) umgeschrieben werden |

**Begriffe:** „(a) mit (b) als Rückfall" (Blocker nur für Fälle, die der Renderer nicht herstellen kann) ist faktisch ein Dauer-(c)
und kein reines (a). Vorläufige Empfehlung: **(a) mit (b) als Rückfall**, weil der Grundsatz „der Rollback stellt her oder
verweigert, nie meldet ok und tut nichts" so dauerhaft gilt; (c) als zeitliche Staffelung nur, wenn (a) nicht im selben Slice trägt.
Endgültig in P1.

**Namensfrage (P1 entscheidet).** Der Down wird vor dem Up gerendert; der Servername des **neuen** Up-CHECK ist beim Rendern
unbekannt, und der Kommentar an `enumValueCheckName` (`PostgresDiffRenderContext.kt:328-337`) warnt vor erratenen Namen (Kollision,
Ziffernanhang, Kürzung). Kandidaten:
1. Name aus dem **Ist-Namen** ableiten — sicher nur, wenn der alte Name durch den `DROP` frei wird (der Up löst den alten CHECK vor
   dem Anlegen, der neue bekäme dann denselben Servernamen); in P0 messen, auch bei Kollision und langen Namen. **Greift nicht**
   bei einem vom Nutzer benannten Alt-CHECK (P0-Fall 7): der Up legt den neuen CHECK unbenannt an (`<tabelle>_<spalte>_check`), der
   abgeleitete Name trifft ihn nicht.
2. Der Up legt den CHECK **explizit benannt** an (`EnumValueCheck.namedClause`, `ck_<tabelle>_<spalte>`): der Down kennt den Namen.
   Ändert den Up-Text, DDL-Goldens und Renderer-Unit-Tests — berührt damit den Up-Pfad (Ausnahme vom Nicht-Scope, Eigner-Entscheidung)
   und kann Drift gegen bestehende Datenbanken mit Servernamen erzeugen.
3. `DROP CONSTRAINT IF EXISTS` mit abgeleitetem Namen, und wo der Name nicht sicher ist, Fallback auf (b). **Gefahr:** `IF EXISTS`
   läuft bei falschem Namen still durch → `ADD` ohne `DROP`.

Kandidat 1 und 3 sind für den benannten Alt-CHECK nur mit Fallback (b) oder mit Kandidat 2 abgesichert.

## Phasen

* **P0 Messung** (Integrationstest gegen PostgreSQL, Muster `PostgresMigrateRoundTripIntegrationTest` /
  `PostgresCustomTypeFileFormMigrateIntegrationTest`; PG-Version aus `TestImages.POSTGRESQL` angeben). Je Fall: Diff-Operationen
  (`before`/`after`), `down.sql`, `planFullyRollbackable`, Exit, Katalog-CHECK nach `schema rollback --execute --allow-destructive`,
  `INSERT` des abgelehnten Werts. Fälle:
  1. Werteliste **erweitert** (`[a,b]` → `[a,b,c]`) — der Befund des Issues;
  2. Werteliste **verkürzt** (`[a,b,c]` → `[a,b]`; Down muss `c` wieder erlauben);
  3. **Inline** (`values`) gegen **benannte Domain/`ref_type`** (nativer Enum-Typ, `AlterCustomType`-Pfad) — Gegenprobe, dass dieser
     Pfad nicht betroffen ist;
  4. **mehrere** Enum-Spalten in einer Tabelle, nur eine geändert, und zwei geändert;
  5. Rollback **nach `--execute`** (echter Hin-und-Rückweg) und Rollback gegen eine Datei (`file:`). Erwartung: im `file:`-Modus ist
     `currentSchema` eine Datei mit Inline-Werten (der Renderer liest sie aus `op.before`), im Live-Modus die Datenbank mit
     `= ANY (ARRAY[…])` (der Renderer braucht `EnumCheckProjection.valuesOf`); beide Wege messen;
  6. **Gegenprobe SQLite**, dasselbe Schema (Soll: `c` abgelehnt);
  7. benannter vom Nutzer geschriebener CHECK statt Generate-Form (Servername vs. `ck_…`);
  8. Werteliste **und** Typ gleichzeitig geändert;
  9. nur die **Reihenfolge** der Werte geändert (soll kein Rollback-Statement erzeugen);
  10. **mehrere** Wertelisten-CHECKs an einer Spalte;
  11. `NOT VALID`-CHECK;
  12. gelesene Form `= ANY (ARRAY[…])` (Katalog) gegen die Generate-Form `IN (…)`; Name des Up-CHECK nach Ausführung (Namensfrage).
  Für (b) zusätzlich: greift die Exit-8-Abbildung für einen **Down**-Blocker (`downBlocked`, `mergeDownIntoUp`) wirklich, oder endet
  der Lauf mit Exit 0?
  Ergebnis: eine **Messtabelle im Plan** (Fall, `op.before`/`after`, `down.sql`, `downBlocked`, Exit, Katalogzustand), L1/L2/L3
  bestätigt oder verworfen.
* **P1 Entscheidung:** Option (a)/(b)/(c); ob `op.before` Werte tragen muss; ADR-/Tag-Bedarf. Nummern **zum Aktivierungszeitpunkt
  nächster freier** ADR und nächster freier `schema-fingerprint-v…`-Tag (nicht vorab festlegen; andere Pläne konkurrieren darum).
  Greift die Lösung nur den Adapter-Renderer an, ändert sich Abdruck und Hexagon nicht → kein Tag; ob ein ADR nötig ist, bestätigt der
  Eigner in P1 (Bestätigung steht aus). Außerdem: wird die Rollback-Spec (`spec/` Rollback-/Migrationsregeln, `ddl-generation-rules.md`) berührt?
* **P2 Umsetzung mit Tests:**
  * Integrationstest gegen PostgreSQL: Up erweitern, `down.sql` anwenden, `INSERT … 'c'` wird von der Datenbank abgelehnt, `a`/`b`
    gelingen; CHECK-Definition im Katalog entspricht dem Zustand vor dem Up. Rot ohne die Änderung (der aktuelle Befund), absichtlich
    falsche Zusicherung einmal rot gesehen.
  * Fälle aus P0 (verkürzt, mehrere Spalten, benannter CHECK) je ein Test; bei Option (b) zusätzlich: Exit 8,
    `downBlocked: true`, `primaryBlockedReason`/Blocker `ROLLBACK_NOT_POSSIBLE` mit klarer Meldung.
  * Renderer-Unit-Test (`PostgresDiffDdlGenerator` DOWN): erwartete Statements in Reihenfolge `DROP CONSTRAINT` → `ADD CHECK`.
  * **Matrix** (`test/cross-dialect-matrix`): `Kind.ROLLBACK` setzt `generateRollback = true` und fängt die `.rollback.sql` in
    `rollbackBody`. Die Zelle läuft aber **file-zu-file im planOnly-Modus** (`MatrixSweepRunner.kt:163-170`): beide Seiten sind
    Inline-Enums mit Werten, `op.before` trägt sie, es gibt keinen Katalognamen. Sie bildet daher **nicht** den Live-Fehler ab; ohne
    die Änderung rendert der Down dort `ADD CHECK` ohne `DROP` (anderes Symptom). „Rot ohne die Änderung" belegt also nur dieses
    file-mode-Symptom, nicht das Issue. Ehrliche Varianten: eine Zelle mit **benanntem** Up-CHECK (Namensfrage Kandidat 2), die
    `DROP`+`ADD` pinnt, oder bei (b) eine Blocker-Zelle (Exit 8). Ein neuer Workstream in `MatrixWorkstreams` ist wahrscheinlich nötig.
    `carve-outs.yaml`: eine Zeile, die „Rollback je Dialekt" für diesen Fall abdeckt, gibt es nicht (G.2 `kind: blocker` `permanent`
    zielt auf `NOT_REVERSIBLE`; die ROLLBACK-Carve-outs sind `dialect: "*"` mit `ownerTests`). P0 benennt, welche Zeile die neue
    Zelle ablöst oder dass der neue Workstream keine ablöst. „Zweiter Lauf `operationsTotal` 0" gehört in den Integrationstest, nicht in
    die Matrix. Die Ausführung gegen die Datenbank belegt nur der Integrationstest.
* **P3 Doku:** Handbuch §Rollback in `docs/user/anwenderhandbuch.md` (nur Ist-Zustand: was ein Rollback nach Wertelistenänderung auf
  PostgreSQL tut bzw. dass er verweigert; Grenzen), `CHANGELOG.md` unter `[Unreleased]` → „Fixed" mit Verweis auf #61,
  `spec/ddl-generation-rules.md` nur falls eine Render-/Rollback-Regel sich ändert (dort mitziehen, nicht nachträglich), ADR gemäß
  P1 falls nötig. `docs/planning/next/README.md`/`done/` nachführen.

## P0 Messergebnis (2026-10-25, `postgres:18-alpine`, `TestImages.POSTGRESQL`)

Gemessen mit einem Wegwerf-Integrationstest (Hin- und Rückweg über `SchemaMigrateRunner` mit `--execute --generate-rollback`
und `SchemaRollbackRunner --execute --allow-destructive` gegen eine echte Datenbank; Ist-Seite wie `schema generate`
sie anlegt: `e TEXT NOT NULL CHECK ("e" IN (…))`, Katalogname `t_e_check`). Spalte „nach Rollback“ ist der Katalog bzw. das
Ergebnis eines `INSERT`. `planFullyRollbackable` war in allen Läufen `true`, `downBlocked` `false`, wo nicht anders vermerkt.

| Fall | Ops (`before`→`after`) | `down.sql` (vor der Änderung) | Exit Migrate / Rollback | Katalog nach Rollback | `INSERT` |
| ---- | ---------------------- | ----------------------------- | ----------------------- | --------------------- | -------- |
| 1 erweitert `[a,b]`→`[a,b,c]` | `AlterColumnType` Text→Enum(a,b,c) | `ALTER … TYPE TEXT;` | 0 / 0 | `t_e_check … ARRAY['a','b','c']` (unverändert weit offen) | `c` **angenommen** (Befund des Issues) |
| 2 verkürzt `[a,b,c]`→`[a,b]` | wie 1 | `ALTER … TYPE TEXT;` | 0 / 0 | `… ARRAY['a','b']` (kurz geblieben) | `c` **abgelehnt** (müsste wieder erlaubt sein) |
| 3 Inline gegen `ref_type`/nativen Enum | `AlterCustomType` | — (Up blockiert) | 8 (`DIALECT_UNSUPPORTED_OPERATION`), nichts ausgeführt | unverändert | — Gegenprobe: anderer Pfad, nicht betroffen |
| 4a zwei Spalten, eine geändert | ein `AlterColumnType` (e) | `TYPE TEXT` für `e` | 0 / 0 | `e` weit, `f` unverändert | `c` angenommen |
| 4b zwei Spalten, beide geändert | zwei `AlterColumnType` | zweimal `TYPE TEXT` (f, e) | 0 / 0 | beide weit | `z` angenommen |
| 5a Hin- und Rückweg nach `--execute` | = Fall 1 | = Fall 1 | 0 / 0 | = Fall 1 | = Fall 1 |
| 5b `file:` gegen `file:` (planOnly) | beide Seiten Inline-Werte | Up: `ADD CHECK (a,b,c)`, Down: `ADD CHECK (a,b)`, jeweils **ohne** `DROP` (aus dem Code gelesen; der Renderer-Test blockiert dort jetzt) | 0 | — | — (`--plan-only` verbietet `--rollback-output`, Exit 2) |
| 6 SQLite, dasselbe Schema | Tabellenneubau, benannter CHECK `ck_t_e` | Neubau | 0 / 0 | `ck_t_e` mit `[a,b]` | `c` **abgelehnt** (Soll); Spec `SqliteEnumRollbackIntegrationTest` bleibt grün |
| 7 vom Nutzer benannter CHECK `my_e_values` | `AlterColumnType` | `TYPE TEXT` | 0 / 0 | nur `t_e_check … (a,b,c)`: der Up löste `my_e_values` und legte **`t_e_check`** an | `c` angenommen |
| 8a Typ + Werteliste: `varchar(10)` → Enum | `AlterColumnType` | — | 8 (`MANUAL_ACTION_REQUIRED`, `USING`-Overlay fehlt) | unverändert | — Up blockiert, nicht Gegenstand |
| 8b Enum → `varchar(20)` | `AlterColumnType` | — | 8 (wie 8a) | unverändert | — |
| 9 nur Reihenfolge `[a,b]`→`[b,a]` | keine Operation | leer | 0 / 0 | unverändert | `c` abgelehnt (richtig, kein Rollback-Statement) |
| 10 zwei Wertelisten-CHECKs | `AlterColumnType` + 2× `DropConstraint` | — | **5** (Up bricht ab, nichts verändert) | unverändert | — **eigener Befund, nicht dieser Slice** |
| 11 `NOT VALID`-CHECK | `AlterColumnType` | `TYPE TEXT` | 0 / 0 | `t_e_check` (a,b,c), validiert; der Up löst `nv` und legt einen **validierten** CHECK an | `c` angenommen |
| 12 langer Name (63 Byte) | `AlterColumnType` | `TYPE TEXT` | 0 / 0 | weit, Name unverändert (`t_x…x_check`) | `c` angenommen |
| 13 Down-only-Blocker (`DropTable`, `--allow-destructive`) | `DropTable` (NOT_REVERSIBLE) | — | **8**, `primaryBlockedReason=ROLLBACK_NOT_POSSIBLE`, `downBlocked=true`, nichts ausgeführt | unverändert | — |
| B Text → Enum (neu) | `AlterColumnType` | `TYPE TEXT` | 0 / 0 | `t_e_check (a,b)` bleibt stehen | `c` abgelehnt, nach Rollback sollte `c` erlaubt sein |
| C Enum → Text | `AlterColumnType` | `TYPE TEXT` | 0 / 0 | CHECK bleibt **weg** | `c` angenommen, sollte abgelehnt werden |

**Bestätigt:**
* **L1** (Down kennt die alten Werte nicht): `op.before` ist `Text` in allen Live-Läufen; der Down rendert nur `TYPE TEXT`.
* **L2** (der Up-CHECK wird im Down nicht gelöst): kein `DROP`, der weite CHECK bleibt (Fälle 1, 4, 7).
* **L3** (keine Meldung): Exit 0, `downBlocked=false`.
* **Down-Blocker greift (für (b))**: Fall 13 endet mit Exit 8, `downBlocked=true`, `primaryBlockedReason=ROLLBACK_NOT_POSSIBLE`;
  ein Down-Blocker verhindert, dass das Ausrollen überhaupt läuft.
* **Namensfrage**: Nach dem Up trägt der neue CHECK den Servernamen `<tabelle>_<spalte>_check`; war der alte CHECK so benannt
  (Fälle 1, 2, 12), ist der Name derselbe — er wird durch das `DROP` frei. Hat der alte CHECK einen anderen Namen (Fall 7, 11),
  entsteht trotzdem `<tabelle>_<spalte>_check`. Der Ist-Name allein reicht also nicht (Kandidat 1 greift nur für den
  Standardnamen), der Standardname ist sicher, wenn ihn kein anderer Constraint trägt.
* **Schemaweite Namensprüfung (gemessen):** Ein unbenannter `ADD CHECK` auf `t.e` neben einem `t_e_check` einer **anderen** Tabelle
  `u` im selben Schema erhält den Namen `t_e_check1` (`t.t_e_check1`, `u.t_e_check`). PostgreSQL prüft den Standardnamen also
  schemaweit, nicht je Tabelle; `taken` in `PostgresEnumCheckRollback.createdName` zählt deshalb die Constraints **aller** Tabellen der
  Ist-Seite. Ein zu kleines `taken` führte zu `DROP` des falschen Namens. Der Integrationstest pinnt die Messung und den Blocker.
* **Zusätzlich gefunden:** dieselbe Lücke in zwei Nachbarfällen (B: eine Textspalte erhält eine Werteliste → der Down löst den CHECK nicht;
  C: eine Spalte verliert die Werteliste → der Down legt den CHECK nicht wieder an). Dieselbe Ursache, derselbe Mechanismus.
* **Verworfen/offen:** Fall 10 (zwei Wertelisten-CHECKs) scheitert schon im Up mit Exit 5; er gehört nicht in diesen Slice.
  Fall 11: der Up legt den ursprünglich `NOT VALID`en CHECK validiert neu an — Up-Verhalten, unverändert.

## P1 Entscheidung

* **Option (a) mit (b) als Rückfall** (Dauer-(c) im Sinn der Begriffsklärung oben). Die Werte der Ist-Seite liefert **nicht** `op.before`
  (Modell/Mapper, Tag-Folge), sondern der Renderer aus `currentSchema` (`EnumCheckProjection.valuesOf`): reiner Adapter-Eingriff.
  **Kein** Fingerabdruck-Tag, **kein** Hexagon-Eingriff, **kein** ADR (die Eigner-Bestätigung für „ADR nötig/nicht nötig“ steht aus;
  nach Plan braucht Option (a) nur im Adapter keinen).
* **Namensfrage: Kandidat 1 mit Rückfall (b); der Up-Text bleibt unberührt** (Kandidat 2 braucht die ausstehende Eigner-Bestätigung und
  ändert Goldens und Bestandsdatenbanken). Der Down löst den CHECK `<tabelle>_<spalte>_check`, wenn kein **anderer** Constraint der Ist-Seite
  (schemaweit) so heißt, der Name ohne Kürzung in 63 Byte passt und die Ist-Seite ein Katalog ist (Constraint-Namen bekannt). Das
  `DROP` ist **ohne** `IF EXISTS`: ein falscher Name soll laut scheitern, nicht still durchlaufen (Kandidat 3 verworfen).
  Sonst blockiert der Down mit `ROLLBACK_NOT_POSSIBLE` und der Diagnose `POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN`. Der alte CHECK
  wird mit seinem Ist-Namen und den Werten aus `currentSchema` wieder angelegt (`ADD CONSTRAINT "<alt>" CHECK (… IN (…))`), damit der
  Zustand vor dem Up auch bei benanntem CHECK (Fall 7) wiederkehrt.
* **Datei gegen Datei** (Fall 5b, B, C): die Namen des Ziels sind unbekannt, der Up selbst löst dort nichts. Der Down blockiert, statt ein
  `ADD` ohne `DROP` zu rendern. Erkennungsmerkmal „Ist-Seite ist ein Katalog“: ein alter Wertevorrat-CHECK **mit Namen** in der Ist-Seite
  (Beleg) oder `options.postgresContext.serverVersion != null` (der Leser reicht die Serverversion mit; die CLI-Verdrahtung
  `SchemaMigrateWiring` setzt sie). Eine Textspalte ohne CHECK sieht in Datei und Katalog gleich aus; ohne dieses Merkmal bliebe Fall B
  ein geratener Name. Ist die Version nicht lesbar, blockiert der Down (konservativ). Das ändert Exit 0 → Exit 8 für `--generate-rollback` mit Wertelistenänderung im Datei-Modus; im
  Bericht an den Eigner ausdrücklich genannt.
* **`planFullyRollbackable`** bleibt unverändert (Transaktionalität des Up); das Handbuch benennt `downBlocked` als Down-Signal.
* **Spec:** `ddl-generation-rules.md` §12 bekommt die Regel (Zielbild zuerst), dazu Handbuch §3.5 und `CHANGELOG`.
* **Matrix:** kein neuer Workstream. Der Sweep vergleicht nur Exit-Codes und teilt eine dialektneutrale Fixture; ein neuer Workstream
  brächte 20+ Carve-outs und eine Zelle, die den Fehler nicht unterscheiden kann. Die Zelle `G.2/*/rollback` ist bereits als
  `permanent` mit `ownerTests` geführt; die neuen Tests (Renderer-Unit-Test, PostgreSQL-Integrationstest) stehen dort als weitere
  `ownerTests`. Die Ausführung gegen die Datenbank belegt nur der Integrationstest.

## P2/P3 Umsetzung

* Code: `PostgresEnumCheckRollback.kt` (neu, Adapter), Aufruf aus `PostgresDiffTableOps.renderAlterColumnType` (nur Richtung `DOWN`);
  `PostgresDiffRenderContext.currentSchema` ist nicht mehr `private`. Der Up-Pfad bleibt unverändert.
* Tests: `PostgresEnumCheckRollbackTest` (Renderer, Reihenfolge `DROP` → `ADD`, Blocker-Fälle), `PostgresEnumRollbackIntegrationTest`
  (PostgreSQL: Fälle 1, 2, 4, 7, B, C, 9, Blocker, Zeilen mit neuem Wert), `SqliteEnumRollbackIntegrationTest` (Gegenprobe). Rot ohne die
  Änderung (gemessen vor Review-Runde 1: acht der neun PostgreSQL-Integrationsspecs und acht der neun Renderer-Tests); die Specs der Review-Runde (Katalog-Merkmal, Namenskollision) kamen danach hinzu; Fall 9 und die SQLite-Gegenprobe sind grün-Gegenproben.
* Größenregeln: `make docker-check` (Detekt inkl. `ComplexCondition`, ein Treffer wurde durch eine Zwischenvariable behoben) und
  `make solid-suppression-gate` sind grün, kein `@Suppress`.
* Doku: Handbuch §3.5, `spec/ddl-generation-rules.md` §12, `CHANGELOG.md`.

## Befund außerhalb des Slices: zwei Wertelisten-CHECKs an einer Spalte (Fall 10)

Reproduktion (PostgreSQL 18): `CREATE TABLE t (id integer NOT NULL, e text NOT NULL CHECK ("e" IN ('a','b','c')),
CONSTRAINT second CHECK ("e" IN ('a','b')), PRIMARY KEY (id))`; Datei mit `e: enum values [a, b, d]` (required).
`schema migrate --execute --generate-rollback` plant `AlterColumnType` + 2× `DropConstraint` und endet mit **Exit 5**; die Datenbank bleibt
unverändert (beide CHECKs stehen weiter), kein Rollback-Skript. Ursache nicht untersucht. Nicht Teil von #61; ob ein Issue entsteht,
klärt der Eigner.

## Nicht-Scope

Andere Dialekte (MySQL nativer `ENUM`, SQL Server, Oracle) — in P0 nur als Lesebefund, falls dort dieselbe Lücke auftritt: dann
eigener `open/`-Eintrag. `AlterCustomType` (nativer PG-Enum) und `ref_type`-Rollback. Der Up-Pfad, **soweit** die Namensfrage (Kandidat 2) nicht anders entscheidet (Eigner).

## Akzeptanz

* Nach `schema migrate --execute --generate-rollback` und `schema rollback --execute --allow-destructive` auf PostgreSQL lehnt die
  Datenbank `INSERT … 'c'` ab (Option a) **oder** der Migrate endet mit Exit 8, `downBlocked: true` und Blocker `ROLLBACK_NOT_POSSIBLE` (Option b). Der Fall
  „Exit 0 und CHECK unverändert weit offen“ tritt nicht mehr auf — belegt durch den Integrationstest.
* Verkürzte Werteliste: nach dem Rollback ist der zuvor abgelehnte Wert wieder erlaubt.
* Jede Fehlerzeile der P0-Messtabelle hat einen Test, der ohne die Änderung fehlschlägt; ausgenommen sind die grünen
  Gegenproben (Fall 3 `ref_type`/native Enum, Fall 6 SQLite), die grün bleiben müssen. Absichtlich falsche Zusicherung einmal rot gesehen.
* Die Matrix-Zelle (benannter Up-CHECK bzw. Blocker-Zelle) oder die dokumentierte Begründung, warum keine anlegbar ist, steht; im
  Integrationstest liefert nach dem Rollback ein zweiter Migrate `operationsTotal` 0.
* Handbuch beschreibt nur das gelieferte Verhalten; `CHANGELOG.md` nennt #61.
* Gates: `make docker-check` der betroffenen Module (einmal ohne `MODULES=`, also über das ganze Repo, falls eine geteilte
  Signatur wechselt; umfasst die Tests), `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"`, `make solid-suppression-gate`,
  `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
