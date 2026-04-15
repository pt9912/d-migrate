# Implementierungsplan: Phase A - Spezifikationsbereinigung und Export-Vertrag

> **Milestone**: 0.7.0 - Tool-Integrationen
> **Phase**: A (Spezifikationsbereinigung und Export-Vertrag)
> **Status**: Done (2026-04-14)
> **Referenz**: `docs/implementation-plan-0.7.0.md` Abschnitt 1, Abschnitt 2,
> Abschnitt 3, Abschnitt 4, Abschnitt 5 Phase A, Abschnitt 6, Abschnitt 7,
> Abschnitt 8, Abschnitt 9, Abschnitt 10; `docs/roadmap.md` Milestone 0.7.0;
> `docs/lastenheft-d-migrate.md` LF-011 / LF-014; `docs/cli-spec.md`;
> `docs/design.md`; `docs/architecture.md`; `docs/ddl-generation-rules.md`

---

## 1. Ziel

Phase A zieht den 0.7.0-Vertrag aus dem Masterplan in eine belastbare
Spezifikationsbasis, bevor Code fuer Migrations-Bundles, Tool-Adapter oder
eine neue CLI-Gruppe geschrieben wird.

Der Teilplan liefert bewusst noch keine Implementierung. Ergebnis von Phase A
ist eine konsistente Dokumentations- und Vertragsbasis, auf der die spaeteren
Phasen B bis F ohne vermischte Migrationsbegriffe, implizite Dialektdefaults
oder tool-unscharfe Versionsregeln aufbauen koennen.

Konkret soll nach Phase A klar und widerspruchsfrei dokumentiert sein:

- dass 0.7.0 `d-migrate export flyway|liquibase|django|knex` als neue
  Top-Level-CLI einfuehrt
- dass die allgemeine CLI-Taxonomie in `docs/cli-spec.md` den realen
  Ist-Root (`schema`, `data`) und das 0.7.0-Zielbild `export` sauber
  trennt, statt nicht implementierte Root-Gruppen als bereits vorhandenen
  Ist-Zustand auszugeben
- dass 0.7.0 baseline-/full-state-Artefakte aus genau einem neutralen Schema
  exportiert und keinen diff-basierten `schema migrate`-Pfad vorzieht
- dass `--target` fuer alle vier Export-Subcommands verpflichtend ist
- dass die Versionsstrategie tool-spezifisch ist:
  - Flyway/Liquibase koennen `schema.version` als Fallback nutzen
  - Django/Knex verlangen ein explizites, tool-taugliches `--version`
- dass `--spatial-profile`, `--generate-rollback` und `--report` als
  relevante Generate-Optionen in den Export-Vertrag vererbt werden
- dass Exit `7` in der globalen CLI-Spec nicht weiter exklusiv als
  `CONFIG_ERROR` reserviert bleibt, wenn 0.7.0 denselben Code fuer lokale
  Parse-/Datei-/Render-/Kollisionsfehler nutzt
- dass Determinismus in 0.7.0 nicht nur Versions- und Dateinamen, sondern
  auch die byte-stabilen Artefaktinhalte umfasst
- dass Rollback in 0.7.0 ein tool-spezifisches Down-Artefakt auf Basis des
  bestehenden full-state-`generateRollback(...)` ist und nicht der spaetere
  `DiffResult`-Rollback-Pfad

---

## 2. Ausgangslage

Aktueller Stand in Dokumentation und Code:

- `docs/roadmap.md` definiert fuer 0.7.0:
  - Flyway-Adapter
  - Liquibase-Adapter
  - Django-Adapter
  - Knex.js-Adapter
  - CLI-Kommandos `d-migrate export ...`
  - Migrations-Rollback-Generierung
- `docs/lastenheft-d-migrate.md` fixiert in LF-011 diese Integrationen als
  optionale Output-Pfade und in LF-014 Rollback als Sollkriterium.
- `docs/cli-spec.md` beschreibt `export flyway` / `export liquibase` /
  `export django` / `export knex` derzeit nur als knappen Stub mit:
  - `--source`
  - `--output`
  - `--target`
  - `--version`
- dieselbe CLI-Spec nennt in §1.1 weiterhin Root-Commands wie `transform`,
  `generate`, `export`, `validate` und `config`, obwohl der reale Root in
  `Main.kt` derzeit nur `schema` und `data` kennt.
- derselbe Stub nennt `--target` noch als optional mit Default `postgresql`,
  was nicht mehr zum aktuellen 0.7.0-Masterplan passt.
- die globale Exit-Code-Tabelle in `docs/cli-spec.md` reserviert `7`
  weiterhin allein fuer `CONFIG_ERROR`, obwohl bestehende und geplante
  CLI-Pfade denselben Code bereits auch fuer Datei-/Parse-/I/O-Fehler
  verwenden.
- `docs/design.md` beschreibt Rollback weiterhin im allgemeinen
  Migrationszielbild als diff-basierten Up-/Down-Pfad auf Basis von
  `DiffResult`.
- `docs/cli-spec.md` beschreibt `schema migrate` und `schema rollback`
  weiterhin bewusst als spaeteren Milestone.
- `docs/ddl-generation-rules.md` schreibt im DDL-Header bewusst einen
  Laufzeit-Timestamp (`Generated: <ISO-8601-timestamp>`), was fuer 0.7.0-
  Tool-Exporte ohne zusaetzliche Regel zu byteweise nicht deterministischen
  Artefakten fuehren wuerde.

Realer Code-Iststand:

- `schema generate` liest heute ein einzelnes neutrales Schema, validiert es
  und erzeugt full-state-DDL ueber `DdlGenerator.generate(...)`.
- optional erzeugt derselbe Pfad ein Down-Artefakt ueber
  `generateRollback(...)`.
- dieser Generate-Pfad arbeitet heute nicht mit `DiffResult`.
- die Root-CLI kennt aktuell nur:
  - `schema`
  - `data`
- ein Top-Level-Command `export` existiert noch nicht.
- `settings.gradle.kts` enthaelt noch kein Modul
  `adapters:driven:integrations`.
- ein tool-neutraler Migrations-Bundle-Vertrag existiert im Code noch nicht.

Konsequenz:

- Die 0.7.0-Dokumentation ist noch nicht falsch in mehreren konkurrierenden
  Varianten, aber deutlich unterdefiniert.
- Ohne Phase A drohen spaeter dieselben Begriffe zwei verschiedene Dinge zu
  meinen:
  - full-state-Tool-Export aus einem einzelnen Schema
  - echter spaeterer inkrementeller `schema migrate`-/Rollback-Pfad
- zusaetzlich ist der heutige CLI-Stub zu knapp, um daraus direkt Runner,
  Tests und Tool-Adapter konsistent abzuleiten.

---

## 3. Scope fuer Phase A

### 3.1 In Scope

- Schaerfung des 0.7.0-CLI-Vertrags fuer `export flyway`,
  `export liquibase`, `export django` und `export knex`
- klare Abgrenzung des 0.7.0-Exportmodells gegen spaetere
  `DiffResult`-basierte Migrationen
- Bereinigung der allgemeinen CLI-Taxonomie in `docs/cli-spec.md`, nicht nur
  des Export-Unterabschnitts
- Festlegung von:
  - Pflicht-`--target`
  - tool-spezifischer `--version`-Strategie
  - `--spatial-profile`
  - `--generate-rollback`
  - `--report`
- Dokumentation einer konsistenten Exit-Code-Matrix fuer 0.7.0
- explizite Einordnung von Exit `7` in der globalen Exit-Code-Tabelle der
  CLI-Spec
- Festlegung des Inhalts-Determinismus fuer Tool-Artefakte trotz
  nicht-deterministischer DDL-Header-Timestamps
- Abgleich von `docs/cli-spec.md`, `docs/design.md` und
  `docs/architecture.md` gegen den realen Generate-Iststand
- klare sprachliche Einordnung von Rollback in 0.7.0:
  tool-spezifische Down-Artefakte auf Basis des bestehenden
  full-state-Generate-Pfads

### 3.2 Bewusst nicht Teil von Phase A

- Implementierung eines Migrations-Bundle-Vertrags im Code
- Einfuehrung des neuen Moduls `adapters/driven/integrations`
- Renderer fuer Flyway, Liquibase, Django oder Knex
- neue CLI-Kommandos oder Runner im Code
- Tool-Runtime-Tests
- automatische Mutation bestehender Tool-Projektdateien
- diff-basierter `schema migrate`- oder `schema rollback`-Pfad

---

## 4. Leitentscheidungen fuer Phase A

### 4.1 0.7.0 ist Tool-Export, nicht `schema migrate` durch die Hintertuer

Phase A fixiert die wichtigste fachliche Grenze des Milestones:

- 0.7.0 exportiert baseline-/full-state-Artefakte aus einem einzelnen
  neutralen Schema
- 0.7.0 liefert keine allgemeine inkrementelle Migration zwischen zwei
  Schema-Versionen
- der spaetere diff-basierte Migrationspfad bleibt an `DiffResult` gebunden

Verbindliche Folge:

- Die 0.7.0-Doku darf die erzeugten Tool-Artefakte nicht als vollwertigen
  Ersatz fuer spaetere `schema migrate`-Schritte darstellen.

### 4.2 `--target` ist fuer alle Export-Subcommands Pflicht

Die bisherige Stub-Annahme in `docs/cli-spec.md`, dass `--target` optional mit
Default `postgresql` sei, wird fuer 0.7.0 verworfen.

Verbindliche Folge:

- `--target` ist fuer:
  - Flyway
  - Liquibase
  - Django
  - Knex
  jeweils Pflicht
- der Dialekt wird nicht aus Config, Profilen oder anderen Defaults
  implizit nachgeladen
- fehlendes `--target` ist ein CLI-Fehler mit Exit `2`

### 4.3 Versionsstrategie ist tool-spezifisch, nicht global

Phase A fixiert:

- Flyway/Liquibase:
  - `--version` optional
  - Fallback auf `schema.version`, wenn die Version tool-tauglich
    normalisierbar ist
- Django/Knex:
  - `--version` Pflicht
  - `schema.version` bleibt dort Metadatum bzw. Report-Kontext
  - kein stilles Recycling von `schema.version` als Dateiname oder
    Modulkennung

Verbindliche Folge:

- fehlendes `--version` bei Django/Knex ist Exit `2`, auch wenn im Schema
  bereits `version` gesetzt ist
- Dateinamen und Migrations-IDs bleiben deterministisch
- ein impliziter Timestamp-Fallback ist in 0.7.0 nicht akzeptabel

### 4.4 Relevante Generate-Optionen werden vererbt

Tool-Export ist fachlich ein spezieller Generate-Pfad.

Verbindliche Folge fuer Phase A:

- `--spatial-profile` wird genauso validiert wie im bestehenden
  `schema generate`-Pfad
- `--generate-rollback` steuert die Materialisierung tool-spezifischer
  Down-Artefakte
- `--report` bleibt der strukturierte Pfad fuer Notes / `skippedObjects`
- globales `--output-format` bleibt Darstellungsflag fuer CLI-Meldungen
  und Fehler, nicht fuer die generierten Tool-Artefakte

### 4.5 Rollback in 0.7.0 ist ein Down-Artefakt, kein diff-basierter Migrationsrueckbau

Phase A muss die Rollback-Begriffe sauber trennen:

- 0.7.0:
  - Flyway-Undo-Datei
  - Liquibase-Rollback-Block
  - Django `reverse_sql`
  - Knex `exports.down`
- spaeterer Migrationspfad:
  - `DiffResult`-basierte Up-/Down-Ableitung

Verbindliche Folge:

- Die 0.7.0-Doku darf Rollback nicht als identisch mit dem spaeteren
  diff-basierten Design beschreiben.

### 4.6 LF-011 bleibt zentral: Tool-Integrationen sind optionale Output-Pfade

Die Tool-Integrationen sind kein neuer Pflichtunterbau fuer d-migrate.

Verbindliche Folge:

- Die regulaeren Kernpfade bleiben ohne Flyway, Liquibase, Django oder Knex
  nutzbar.
- Externe Tool-Runtimes werden nur fuer Tests, Smokes und die Nutzung der
  exportierten Artefakte benoetigt.

### 4.7 Exit `7` muss in der globalen CLI-Spec als gemeinsamer Lokalfailure-Bucket beschrieben werden

Phase A darf Exit `7` nicht nur im Export-Unterabschnitt umdeuten, waehrend
die globale CLI-Tabelle denselben Code weiter exklusiv als `CONFIG_ERROR`
fuehrt.

Verbindliche Folge:

- `docs/cli-spec.md` Abschnitt 2 zieht die allgemeine Semantik von Exit `7`
  auf den realen Sammelpfad fuer lokale Konfigurations-, Parse-, Datei-,
  I/O-, Render- und Kollisionsfehler.
- `CONFIG_ERROR` bleibt ein wichtiger Unterfall dieses Buckets, aber nicht
  mehr die alleinige dokumentierte Bedeutung.
- 0.7.0 fuehrt damit keinen neuen Widerspruch in der globalen Exit-Code-
  Referenz ein.

### 4.8 Determinismus umfasst Artefaktinhalte, nicht nur IDs und Dateinamen

Phase A muss festziehen, ob Tool-Exporte trotz Wiederverwendung von
`DdlGenerator.generate(...)` byte-deterministisch sein sollen.

Verbindliche Folge:

- Gleiches Schema, gleicher Tool-Export, gleicher Dialekt, gleiche Version und
  gleiche Flags muessen dieselben Artefaktinhalte erzeugen.
- Der nicht-deterministische DDL-Header-Timestamp aus
  `docs/ddl-generation-rules.md` wird in 0.7.0-Tool-Artefakten nicht
  unveraendert uebernommen.
- Tool-Adapter entfernen diesen Laufzeit-Timestamp vor dem Wrapping; falls
  Provenienz benoetigt wird, bleibt sie im Report oder in stabilen, nicht
  zeitabhaengigen Metadaten sichtbar.

---

## 5. Arbeitspakete fuer Phase A

### A.1 CLI-Vertrag fuer `export` finalisieren

Mindestens noetig:

- neue Top-Level-Gruppe `export` im Spezifikationsbild fixieren
- allgemeine CLI-Taxonomie in `docs/cli-spec.md` auf den realen Ist-Root
  (`schema`, `data`) plus das explizit als neu markierte 0.7.0-Zielbild
  `export` ziehen
- Subcommands:
  - `flyway`
  - `liquibase`
  - `django`
  - `knex`
- finaler Flag-Vertrag fuer 0.7.0, mindestens:
  - `--source`
  - `--output`
  - `--target`
  - `--version`
  - `--spatial-profile`
  - `--generate-rollback`
  - `--report`

### A.2 Exit-Code-Matrix fuer Export-Pfade fixieren

Mindestens noetig:

- `0` Erfolg
- `2` ungueltige CLI-Argumente / unzulaessige Kombinationen /
  fehlendes Pflicht-`--target` / fehlendes tool-pflichtiges `--version`
- `3` Schema-Validierungsfehler
- `7` Parse-, I/O-, Render- oder Dateikollisionsfehler
- globale Exit-Code-Tabelle in `docs/cli-spec.md` Abschnitt 2 auf dieselbe
  Sammelsemantik fuer Exit `7` nachziehen; `CONFIG_ERROR` bleibt enthalten,
  aber nicht exklusiv

### A.3 Migrationsbegriff gegen spaetere Diff-Pfade abgrenzen

Mindestens noetig:

- baseline-/full-state-Export sprachlich klar markieren
- explizit festhalten, dass 0.7.0 kein Ersatz fuer spaeteres
  `schema migrate` ist
- Rollback-Begriff fuer 0.7.0 von diff-basiertem Rollback entkoppeln

### A.4 Tool-spezifische Versions- und Determinismusregeln fixieren

Mindestens noetig:

- Flyway-/Liquibase-Fallback ueber `schema.version`
- Django-/Knex-Pflichtversion
- keine impliziten Timestamp-Defaults
- deterministische Dateinamen aus Version plus `schema.name`
- byte-deterministische Artefaktinhalte fuer identische Eingaben
- DDL-Header-Timestamps werden fuer Tool-Exporte entfernt statt ungeprueft
  in Artefaktinhalte uebernommen

### A.5 Doku-Basis fuer die Folgephasen bereinigen

Mindestens noetig:

- `docs/cli-spec.md` auf den finalen Export-Vertrag ziehen
- `docs/cli-spec.md` §1.1 und §2 ebenso bereinigen wie den Export-Unterabschnitt
- `docs/design.md` fuer baseline-/full-state-Export versus spaeteres
  diff-basiertes Migrationszielbild sauber abgrenzen
- `docs/ddl-generation-rules.md` oder die unmittelbar referenzierte Export-
  Doku auf die inhaltliche Determinismus-Regel fuer Tool-Exporte ziehen
- `docs/architecture.md` nur soweit nachziehen, dass die spaeteren Phasen
  keinen Widerspruch zwischen Ist-Code und Zielarchitektur erben

Ziel von Phase A:

- Vor der Code-Umsetzung ist klar, dass 0.7.0 einen baseline-/full-state-
  Export fuer externe Tools liefert, welche Flags gelten und wie Rollback
  fuer diesen Milestone zu lesen ist.

---

## 6. Technische Zielstruktur fuer Phase A

Phase A fuehrt noch keine neue Produktionsarchitektur ein, legt aber den
Spezifikationsrahmen fuer die spaeteren Codephasen fest.

Nach Phase A muss konsistent beschrieben sein:

- Root-CLI-Zielbild:
  - `d-migrate → {schema, data, export}`
- Export-Unterbau als spaetere Folgephasen:
  - tool-neutraler Migrations-Bundle-Vertrag
  - neues Modul `adapters/driven/integrations`
  - dedizierter Runner fuer `export`
- Reuse statt neuer Parallelpfade:
  - `SchemaFileResolver`
  - `SchemaValidator`
  - `DdlGenerator.generate(...)`
  - `DdlGenerator.generateRollback(...)`
  - `SpatialProfilePolicy`

Nicht Teil der Zielstruktur von Phase A:

- ein eigener zweiter SQL-Generator
- impliziter Diff-Migrationspfad unter dem Label Tool-Export
- automatische Bearbeitung bestehender Tool-Projektdateien

---

## 7. Betroffene Artefakte

Direkt betroffen:

- `docs/implementation-plan-0.7.0.md`
- `docs/cli-spec.md`
- `docs/design.md`
- `docs/architecture.md`
- `docs/ddl-generation-rules.md`

Indirekt als Ist-Referenz relevant:

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `settings.gradle.kts`

Noch nicht Teil von Phase A, aber als Folgeartefakte vorzubereiten:

- `adapters/driven/integrations`
- neue CLI-Commands unter `adapters/driving/cli`
- tool-neutrale Export-Typen in `hexagon/ports`
- dedizierter Export-Runner in `hexagon/application`

---

## 8. Akzeptanzkriterien

- [ ] Die 0.7.0-Dokumentation beschreibt Tool-Export explizit als
      baseline-/full-state-Export aus genau einem neutralen Schema.
- [ ] `docs/cli-spec.md` §1.1 behauptet nicht mehr, dass heute bereits nicht
      implementierte Root-Commands neben `schema` und `data` existieren;
      `export` ist dort als neues 0.7.0-Zielbild kenntlich.
- [ ] 0.7.0 wird dokumentarisch klar gegen spaeteres
      `DiffResult`-basiertes `schema migrate` abgegrenzt.
- [ ] `--target` ist fuer alle vier Export-Subcommands als Pflichtflag
      festgezogen.
- [ ] Die bisherige Stub-Annahme eines impliziten Default-Dialekts ist fuer
      0.7.0 entfernt.
- [ ] Die tool-spezifische Versionsstrategie ist dokumentiert:
      Flyway/Liquibase optionaler Fallback, Django/Knex Pflicht-`--version`.
- [ ] `--spatial-profile`, `--generate-rollback` und `--report` sind als
      relevante Export-Flags spezifiziert.
- [ ] Die Export-Exit-Codes `0`, `2`, `3`, `7` sind dokumentiert und
      voneinander abgegrenzt.
- [ ] Die globale Exit-Code-Tabelle in `docs/cli-spec.md` kollidiert dabei
      nicht mehr mit dem Export-Vertrag fuer Exit `7`.
- [ ] Determinismus fuer 0.7.0 umfasst auch Artefaktinhalte; der
      DDL-Header-Timestamp wird fuer Tool-Exporte nicht unveraendert
      uebernommen.
- [ ] LF-011 ist im Dokument sauber umgesetzt: Tool-Integrationen bleiben
      optionale Output-Pfade und keine Voraussetzung fuer Kernfunktionen.
- [ ] `docs/cli-spec.md`, `docs/design.md`, `docs/architecture.md` und der
      0.7.0-Masterplan beschreiben denselben Phase-A-Vertrag.

---

## 9. Verifikation

Mindestumfang fuer die Phase-A-Umsetzung:

1. Dokumentabgleich gegen den Masterplan:

```bash
rg -n "export flyway|export liquibase|export django|export knex|schema migrate|schema rollback|Commands|Exit-Codes" \
  docs/implementation-plan-0.7.0.md docs/cli-spec.md docs/design.md docs/architecture.md
```

2. Pruefung der finalen Pflicht-/Optional-Semantik:

```bash
rg -n -- "--target|--version|--spatial-profile|--generate-rollback|--report" \
  docs/implementation-plan-0.7.0.md docs/cli-spec.md
```

3. Pruefung der globalen CLI-Taxonomie und Exit-Code-Tabelle gegen den Ist-Code:

```bash
sed -n '1,30p' docs/cli-spec.md
sed -n '64,82p' docs/cli-spec.md

rg -n "buildRootCommand|subcommands\\(|DataCommand|SchemaCommand" \
  adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt \
  adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands
```

4. Determinismus-Pruefung gegen die DDL-Regeln:

```bash
sed -n '24,40p' docs/ddl-generation-rules.md

sed -n '1,40p' settings.gradle.kts
```

Dabei explizit pruefen:

- `export` ist im Dokument als neues Zielbild markiert und nicht als bereits
  implementierter Ist-Zustand
- `docs/cli-spec.md` §1.1 beschreibt keine Root-Taxonomie mehr, die dem
  realen `buildRootCommand()`-Iststand widerspricht
- der 0.7.0-Exportvertrag kollidiert nicht mehr mit einem impliziten
  `--target`-Default
- Django/Knex sind dokumentarisch nicht mehr an `schema.version` als
  stillen Fallback gekoppelt
- die globale Exit-Code-Tabelle beschreibt Exit `7` nicht mehr enger als die
  Export-Spezifikation
- der Determinismus-Vertrag erfasst auch Artefaktinhalte; der DDL-Header-
  Timestamp wird fuer Tool-Exporte nicht still mitgeschleppt
- `docs/design.md` unterscheidet Rollback fuer 0.7.0 von spaeterem
  diff-basiertem Rollback

---

## 10. Risiken und offene Punkte

### R1 - Vollschema-Export wird spaeter als allgemeine Migration missverstanden

Wenn Phase A die baseline-/full-state-Natur von 0.7.0 nicht sauber markiert,
werden spaetere Nutzer oder Teilplaene den Milestone leicht als vorgezogenen
`schema migrate` lesen.

### R2 - Ein impliziter Dialektdefault wuerde spaeter tief in Tests und CLI diffundieren

Wenn `--target` nicht jetzt klar verpflichtend gemacht wird, entstehen
spaeter unnötige Sonderfaelle bei Spatial-Profilen, Dateinamen und
Runtime-Tests.

### R3 - Globale Versionsregeln passen nicht zu allen Tools

Ein einziger universeller Fallback fuer `--version` klingt bequem, fuehrt aber
bei Django und Knex voraussichtlich zu ungueltigen oder irrefuehrenden
Dateinamen.

### R4 - Rollback-Begriffe koennen zwischen 0.7.0 und spaeterem Migrationsdesign kollidieren

Wenn Phase A das nicht sauber trennt, entstehen spaeter zwei verschiedene
Rollback-Bedeutungen unter fast demselben Wording.
