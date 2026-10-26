# PostgreSQL: eine Spalte vom Domain-Typ (`type: enum` + `ref_type`) plant bei jedem Lauf `AlterColumnType` und blockiert mit Exit 8 (Issue #68)

> **Status: Alle Eigner-Fragen entschieden (2026-10-26): Option B′ und E3, E7–E12 nach Empfehlung; E3 (a2) ist nach P1b bestätigt (Datenbank-Hashes bitgleich, siehe „P2a, P2a-R und P1b Ergebnis“). Erledigt: Schritt 0a und 0b (Spec), P1a (Messung auf `main`), P2a (Reader, einseitiger Vergleich, Abdruck), P2a-R (Rollback-Verdrahtung), P1b (Hash nachher). Nächster Schritt: P2b (Validierung, E7), dann P2c-0 bis P2c-4, P3b, P5.** Der Plan liegt auf Anweisung des Eigners
> schon in `in-progress/` (ADR 0004 sieht den Move erst mit dem ersten Implementierungs-Commit vor); P2a ist der erste Implementierungs-Commit.
> **Vorbedingung:** E1, E2, E4–E6 entschieden (siehe „Entscheidung“). **Offen:** nur die Bestätigung von E3 (a2) durch P1a/P1b (Rückfall (a1)); sonst keine Eigner-Frage. **Reihenfolge:** Schritt 0a Spec (Reverse/Verweis, erledigt) → P1a (Messung auf `main`, erledigt) → **vorläufige** Entscheidung E3/E7/E9 → Schritt 0b Spec → P2a → P1b (Hash nachher, E3 bestätigt, ADR) → P2b → P2c → P3a/P3b → P5.
> **Plan-Review Runde 3 eingearbeitet (2026-10-26):** `postUpFingerprint`-Befund korrigiert, E3 in a1/a2/b/c, Akzeptanz nach Weg, E12, Schneidung von P2c/P3.
> **Plan-Review Runde 2 eingearbeitet (2026-10-26):** Tag-Wechsel ist global (E3 neu), Rollback-Verdrahtung schemagebunden (M2), P1 geteilt (M3), zweite Blindheit unter E3(a) (M4), Plan-Artefakt prüft keinen Tag (M5).
> **Plan-Review Runde 1 eingearbeitet (2026-10-26):** Validierung (H1), Fingerabdruck je Seite (H2), einseitige Projektion (M1), Datenpfade (M2), Schema/Domain über Domain (M3); neue offene Fragen **E7–E9**, E3 konkretisiert. Der Umfang von B′ ist gewachsen (siehe „Umfang von B′“).
> GitHub-Issue: [#68](https://github.com/pt9912/d-migrate/issues/68) (als Frage gestellt: „Ist die Form vorgesehen?"). Folge der Domain-Reihe #52, #58, #59.
> **Vorgabe für Spec und Handbuch:** nur der gültige Zustand, zeitlos; keine Chronik, keine Herleitung. Version, Betroffene und Herleitung stehen nur im `CHANGELOG.md` und hier in `docs/planning/`.

## Ziel

Eine Spalte vom Domain-Typ lässt sich in der Datei so schreiben, dass `schema generate` die Domain-Spalte erzeugt **und** `schema migrate` gegen die erzeugte Datenbank nichts plant — und,
je nach Option, dass auch `schema reverse` → `schema generate` die Domain-Spalte erhält.

## Antwort auf die Frage im Issue (nach Code und Spec)

1. **Ist `type: enum` + `ref_type: <Domain>` auf PostgreSQL vorgesehen?** Ja, soweit Generator, Validator und Spec es zeigen: `spec/neutral-model-spec.md` §13.2 verlangt nur „`ref_type` muss in `custom_types` existieren" (keine Einschränkung auf
   Enums); der PostgreSQL-Generator rendert jedes `Enum(refType)` als Spalte des benannten Typs (`PostgresColumnConstraintHelper`, `quoteQualified(refType)`), die Messung bestätigt `"d" "dd"`. **Dokumentiert** ist es nur für die Enum
   (Handbuch §3.21, `spec/schema-reference.md` „Verwendung in Spalten" steht nur beim Enum-Abschnitt); für die Domain fehlt die Schreibweise in Spec und Handbuch. Das ist eine Lücke, kein Entscheid dagegen.
2. **Der Fehler liegt im Leseweg und im Vergleich**, nicht im Generator (siehe Ursache). Die Frage „wie sonst ausdrücken" stellt sich nicht, sobald der Rundlauf funktioniert.

## Entscheidung (Eigner, 2026-10-26): Option B′

* **E1 = B′:** der Reader liefert für eine Domain-Spalte `Enum(refType = <Domain>)`; der Vergleich löst die **Datenbank-Seite** über den Basistyp auf, wo die Datei-Seite die Spalte **nicht** als `ref_type` führt.
* **E2 = Toleranz im Vergleich:** eine ältere `reverse`-Datei (Basistyp an der Domain-Spalte) bleibt bei Exit 0; kein harter Bruch.
* **E3 = (a2), in P1b bestätigt:** beide Seiten falten die Domain-Spalte auf den Basistyp, ohne `v25`-Bump; die Datenbank-Hashes bleiben bitgleich, neu hasht nur die Datei-Seite mit `ref_type`-Domain. Trägt P1a den Beleg nicht (z. B. ein persistierter Datei-Hash in Artefakten oder ein Datenbank-Hash, der sich doch ändert), gilt **(a1)** (Bump `v25` + ADR 0067). Mit (a) folgen die schemagebundene Rollback-Verdrahtung (P2a-R) und zwei dokumentierte, getestete Blindheiten im Post-Compare.
* **E4:** Domain außerhalb des gelesenen Schemas (nicht in `custom_types` des Ergebnisses) → Rückfall auf den Basistyp wie bisher.
* **E5:** strikter `schema compare` (Bundle `null`) zeigt den Unterschied Domain-Spalte gegen Basistyp-Spalte weiter; die Toleranz gilt nur im ziel-bewussten Vergleich (`schema migrate`/Fingerabdruck).
* **E7 = Regeln lösen auf:** die Validierungsregeln (E009/E017/E130) lösen `Enum(refType)` einer Domain über `custom_types` auf den Basistyp auf (gemeinsamer Helfer, E8); Generator-Identity in P1a messen.
* **E8 = je Lesestelle auflösen**, mit gemeinsamem Helfer in `hexagon/core` (P2c-0 bis P2c-4); **Seed = auflösen** (Zahlen erzeugen).
* **E9 = Grenze für Domain über Domain:** Rückfall auf den Basistyp der äußeren Domain, dokumentiert und getestet; `domain_name` hat Vorrang vor `udt_name`; `domain_schema` wird gegen das gelesene Schema geprüft.
* **E10 = ein Slice** (P2a → P2b → P2c in Commit-Reihenfolge; keine Auslieferung von P2a ohne P2c).
* **E11 = kein Guard hier:** nur der Rollback-Artefakt-Test; ein Guard für Plan-Artefakte wird bei einem Bump (a1) als eigener Slice angelegt.
* **E12 = Minor-Release 1.12.0** (neues `reverse`-Format; Eintrag unter `[Unreleased]`); Abwärtskompatibilität: bei (a2) bleiben Rollback-Artefakte gültig, bei (a1) scheitern alle alten an `…ALGORITHM_MISMATCH` und sind neu zu erzeugen.
* **E6:** kein neuer `R`-/`W`-Code; P4 (Ledger) entfällt, solange der Rückfall keine Meldung trägt.

Verworfen: A (Reader bleibt falsch, Rundlauf der `reverse`-Datei bleibt offen), C (dokumentiert einen Fehler als Eigenschaft). Begründung siehe „Optionen“.

## Ist / Soll

| | Ist (gemessen) | Soll |
|---|---|---|
| `generate` aus `d: { type: enum, ref_type: dd }` | `"d" "dd"` (Domain-Spalte) | unverändert |
| `migrate --plan-only` Datei gegen die erzeugte Datenbank | Exit 8, `AlterColumnType`, `PG_USING_OVERLAY_MISSING`, „X to TEXT" | Exit 0, leer |
| `reverse` einer Domain-Spalte | `type: <Basistyp>`, die Domain steht nur unter `custom_types` | `type: enum` + `ref_type: <Domain>` (B′) |
| `generate` aus der `reverse`-Datei | `"d" INTEGER` (Domain-Spalte weg) | `"d" "dd"` (B′) |
| `schema compare` Datei gegen Datenbank | `DIFFERENT`: `column d: type: enum(ref:dd) -> integer` (Exit 1) | `IDENTICAL` |
| Datei `type: integer` gegen Datenbank mit Domain-Spalte | Exit 0 (Domain-Spalte und Basistyp-Spalte gelten als gleich) | `schema migrate`: Exit 0 (Toleranz E2, nur diese Richtung); strikter `schema compare`: Unterschied sichtbar (E5) |
| Datei `type: enum` + `ref_type: dd` gegen Datenbank-Spalte vom **Basistyp** (`integer`, ohne Domain) | Exit 8 (derselbe Fehlerfall wie oben) | **bleibt** `AlterColumnType`, Exit 8: die Datei verlangt eine Domain-Spalte, die Datenbank hat keine (Toleranz nicht beidseitig) |

## Ursache (am Code belegt; P0 misst die Ergebnisse)

Die Hypothese aus dem Issue-Umfeld bestätigt sich in der **Datenbank-Seite**, nicht in der Datei-Seite.

1. **Der Reader verliert die Domain an der Spalte.** `PostgresTableMetadataQueries.listColumns` liest `information_schema.columns` mit `data_type` und `udt_name`; bei einer Domain-Spalte liefern beide den **Basistyp**
   (gemessen: `data_type = integer`, `udt_name = int4`; die Domain steht nur in `domain_schema`/`domain_name`, die die Abfrage nicht auswählt; im Code kommt `domain_name` nirgends vor).
   `PostgresTypeMapping.mapColumn` bildet das auf `Integer` ab. Bei einer Enum liefert `udt_name` den Typnamen, daher `mapUserDefined` → `Enum(refType)` — deshalb ist die Enum unbetroffen.
2. **Die Datei-Seite bleibt `Enum(refType = dd)`.** `PostgresNeutralTypeCanonicalizer.canonicalize` lässt `Enum` mit `refType` unverändert (Identitäts-Ausnahme „custom-type path"). Im Vergleich
   (`TableComparator.compareColumn`) steht also `Enum(refType)` gegen `Integer` → `ColumnDiff.type` → `AlterColumnType`.
3. **„TEXT" ist nur die Schreibweise der Meldung.** `PostgresUsingOverlayResolver` ruft `ctx.sql.toSql(op.after)`; `PostgresTypeMapper.toSql` rendert jedes `Enum` ohne Unterscheidung als `TEXT`
   (`is NeutralType.Enum -> "TEXT"`). Deshalb „TEXT to TEXT" bei einer `text`-Domain: es steht `Text` gegen `Enum(refType)`, beide als `TEXT` gedruckt. Der Zieltyp der Operation ist nicht TEXT, sondern die Domain.
4. **Dieselbe Lage hat SQLite schon gelöst, PostgreSQL ausdrücklich ausgenommen.** `TargetProjection.typeWithCustomTypes` / `DomainColumnTypes` projizieren eine Domain-Spalte über ihren Basistyp, aber nur wo
   `resolvesRefTypeVocabulary` gilt (`registryDomainAwareCanonicalizer`, KDoc: „Fuer PostgreSQL waere ein Aufloesen falsch -- dort ist die Domain ein Typ-Objekt, das der Reverse zurueckgibt"). Das stimmt für die
   **Domain als Typ-Objekt** (`custom_types`, vom Reader zurückgegeben), nicht für die **Spalte**, deren Domain-Zugehörigkeit der Reader verliert. Dieser KDoc-Satz ist zu korrigieren, was auch die Option ist.
5. **Warum `schema reverse` → `migrate` Exit 0 gibt:** die Datei trägt `type: integer`, die Datenbank liest `Integer`; beide Seiten verlieren die Domain gleichermaßen. Das ist kein Beleg für Korrektheit, sondern
   die **Gegenseite des Fehlers**: die Datenbank-Spalte vom Domain-Typ und eine Spalte `INTEGER` sind für den Vergleich ununterscheidbar.
6. **Der Fingerabdruck sieht dasselbe.** `MigrationFingerprint` hasht `enum(ref:dd)` (Datei) gegen `integer` (Datenbank); der Post-Compare nach `--execute` (Drift-Prüfung, `POST_EXECUTE_DRIFT`) steht damit vor demselben
   Unterschied, sobald ein Lauf mit anderer Änderung die Spalte berührt. **Nicht gemessen** (P0 ergänzt, siehe offene Messungen).

## P0 Messung (Ist, 2026-10-26)

Image aus dem Stand `main` (`954cc0002`) per `make docker-build` gebaut (`d-migrate-p68:dev`), PostgreSQL `postgres:18.6-alpine`, internes Docker-Netz, Skript wie im Issue (`generate` → `psql` → `migrate --plan-only`
→ `reverse` → `migrate --plan-only` mit der `reverse`-Datei → `schema compare`). Datei: `d: { type: enum, ref_type: dd }`, nur die Domain-Zeile wechselt. `generate` und Anwenden je Exit 0.

| Fall | Domain-Zeile | Plan Datei | Meldung | Plan `reverse`-Datei | `compare` Datei ↔ DB | `generate` aus `reverse`-Datei |
|---|---|---|---|---|---|---|
| 1 | `integer`, `check: "VALUE >= 0"` | Exit 8, `PG_USING_OVERLAY_MISSING` | `t.d from INTEGER to TEXT` | Exit 0 | Exit 1, `enum(ref:dd) -> integer` | `"d" INTEGER` |
| 2 | `text`, `check: "VALUE <> ''"` | Exit 8, `PG_USING_OVERLAY_MISSING` | `t.d from TEXT to TEXT` | Exit 0 | Exit 1 | `"d" TEXT` |
| 2v | `varchar(50)`, `check` | Exit 8, `PG_USING_OVERLAY_MISSING` **und** `POSTGRES_RENDER_SKIP` (`AlterCustomType`) | `t.d from VARCHAR(50) to TEXT` | Exit 0 | Exit 1 | `"d" VARCHAR(50)` |
| 3b | `decimal(10,2)`, `check` | Exit 8, `PG_USING_OVERLAY_MISSING` | `t.d from DECIMAL(10,2) to TEXT` | Exit 0 | Exit 1 | `"d" DECIMAL(10,2)` |
| 3d | `uuid` | Exit 8, `PG_USING_OVERLAY_MISSING` | `t.d from UUID to TEXT` | Exit 0 | Exit 1 | `"d" UUID` |
| K-Enum | Enum `dd: { kind: enum, values: [a, b] }`, Spalte `ref_type` | Exit 0, leer | — | Exit 0 | Exit 0 | `"d" "dd"` |
| K-ohne | Domain wie Fall 1, **ohne Spalte** `d` | Exit 0, leer | — | Exit 0 | Exit 0 | — |

Befunde:

* **Reproduziert** auf dem Stand von `main`: alle Fälle wie im Issue gemessen (1, 1b `integer` ohne `check`, 2, 2v, 3a `boolean` → „BOOLEAN to TEXT“, 3b, 3c `biginteger` → „BIGINT to TEXT“, 3d); jeweils Plan Datei Exit 8, `reverse`-Datei Exit 0, `compare` Exit 1. Die Kontrollen verhalten sich wie im Issue.
* **Messaufbau:** Grundlage ist das Skript aus Issue #68 (generate → `psql` → `migrate --plan-only` → `reverse` → `migrate`), ergänzt um `schema compare` und `generate` aus der `reverse`-Datei; die Datenbank der Zusatzmessungen wurde per `psql`-DDL angelegt. Die Fälle sind mit dem Issue-Skript und den obigen Domain-Zeilen reproduzierbar; das P1a-Messskript (`test/measure-domain-spalte-68/measure.sh`) deckt Hashes, Rollback und Lesestellen ab.
* **Katalog:** `information_schema.columns` meldet für die Domain-Spalte `data_type = integer`, `udt_name = int4`, `domain_schema = public`, `domain_name = dd` — der Reader wählt nur die ersten beiden.
* **Die `reverse`-Datei verliert die Domain-Spalte im Rundlauf:** jede `generate`-Ausgabe aus ihr trägt den Basistyp statt `"dd"`. Frage 2 des Issues („wie ausdrücken, damit `generate` die Domain-Spalte erzeugt?") hat
  für die `reverse`-Datei heute keine Antwort; für die handgeschriebene Datei antwortet `ref_type`.
* Die Domain **als Typ** ist nicht betroffen (K-ohne); ihre `varchar`-Grenze (`AlterCustomType` blockiert) ist bekannt (Handbuch §3.21) und bleibt außerhalb.

### Weitere P0-Messungen (2026-10-26, Stand `main`, gleiches Image)

Aufbau wie oben; Datenbank per `psql` angelegt, `schema reverse`, `schema migrate --plan-only` mit der `reverse`-Datei, wo genannt `--execute` mit zusätzlicher Spalte.

| Messung | Befund |
|---|---|
| Post-Compare: Domain-Spalte, `reverse`-Datei plus neue Spalte `n`, `migrate --execute --report` | Exit 0, keine Diagnose im Report (Ist-Stand, beide Seiten Basistyp). Der Post-Compare mit `ref_type` auf der Datei-Seite ist heute nicht messbar (der Plan blockiert vorher); er kommt als Test in P3 (Akzeptanz 5). |
| Domain in anderem Schema (`s2.dd2`), Spalte `a`; Domain `dd` in `public`, Spalte `b` | `reverse`: `custom_types` enthält nur `dd` (die Domain aus `s2` fehlt), beide Spalten `type: integer`; Plan Exit 0. Bestätigt E4: der Reader führt die fremde Domain nicht in `custom_types`, ein `ref_type` liefe ins Leere. |
| **Domain über Domain** (`d2` über `d1`), Spalten `a d1`, `b d2` | `reverse`: Spalte `a` = `type: integer` (Domain verloren), Spalte `b` = **`type: enum`, `ref_type: d1`** — falsche Domain (`d2` steht in der Datenbank). Der Reader liefert hier **schon heute** `Enum(refType)`, weil `udt_name` den **inneren** Domain-Namen trägt (`mapUserDefined`). Befund neben der Frage des Issues; B′ muss `domain_name` **vor** `udt_name` auswerten. Plan Exit 0 (beide Seiten gleich falsch). |
| Array-Domain (`ia` über `integer[]`), Spalte `a` | `reverse`: `type: array`, `element_type: integer`; die Domain geht verloren (wie bei Skalaren). `generate` aus der Datei: `"a" INTEGER[]`. B′ muss die Array-Domain-Spalte ebenfalls als `ref_type` führen. |
| `NOT NULL`-Domain (`nn`, `default 1`): Spalte `b nn`, Spalte `c nn NOT NULL` | `reverse`: `b` = `type: integer` ohne `required`, `c` = `type: integer`, `required: true` (stimmt mit #59 überein); die Domain trägt `not_null: true`, `default: 1`. `generate` aus der Datei: `"b" INTEGER` (Domain und ihr `NOT NULL`/`DEFAULT` an der Spalte weg), `"c" INTEGER NOT NULL`. B′ ändert `required` nicht. |
| Bestehende Tests, die die Basistyp-Form einer Domain-Spalte festschreiben | **Keiner gefunden.** `PostgresSchemaReaderIntegrationTest` legt `positive_int` nur als Typ an (keine Spalte); die Unit-Tests (`PostgresSchemaReaderTest`, `…PartitionTest`) füttern `listColumns`-Zeilen **ohne** `domain_name` — der Reader muss das Feld wie `column_not_null` tolerieren (fehlt es, gilt es als keine Domain). `DomainTraitsMatrixTest` nutzt `Enum(refType)` nur als Datei-Form für `generate`. |
| Weitere Leser des Spaltentyps | siehe unten (Code gelesen, nicht alle gemessen). |

**Leser des Spaltentyps (Code, bei B′ zu prüfen und in P3 zu messen):**

* `data seed` (`ColumnValueGenerator.randomEnumValue`, Zeile 134): eine `Enum` **ohne `values`** wirft `UnsupportedSeedTypeException`. Eine `reverse`-Datei mit `type: enum` + `ref_type` an einer Domain-Spalte erzeugte heute `integer` und seedet; nach B′ würde sie am Seed scheitern oder die Spalte nullen (`TableRowSeeder`). **Regressrisiko, gleiche Lücke wie bei einer handgeschriebenen Datei heute.** Abhilfe: der Seed löst `ref_type` auf eine Domain über deren Basistyp auf (eigenes Arbeitspaket P2c) — oder die Zeile wird bewusst als Grenze dokumentiert (Entscheidung für den Eigner, siehe Rückmeldung).
* `data import` (`ImportTypeCompatibility.isEnumCompatible`): wertet `refType` gegen den JDBC-`sqlTypeName` aus; für eine Domain-Spalte meldet der PostgreSQL-Treiber voraussichtlich den Basistyp (INTEGER) — dann „inkompatibel". Nicht gemessen; P3 misst Import einer Datei mit Domain-`ref_type` in die Domain-Spalte.
* `data transfer` liest beide Schemas **zur Laufzeit** über den Reader (`DataTransferRunner`, `schemaReader().read`): die Typen der Domain-Spalte werden nach B′ `Enum(refType)`. `TransferVerifier.canonicalFamily` stellt `Enum` (text) gegen `Integer` (numeric) und nimmt die Spalte bei Dialektwechsel aus der Byte-Verifikation (Zeilen 184–188, belegt am Code, nicht gemessen); Parquet schreibt eine `integer`-Domain-Spalte als String (`ParquetGroupValueWriter`, Zeile 56). Siehe E8; P3 misst.
* `ChunkSchemaToManifest` schreibt `refType` in den Parquet-Manifest (neue Information, kein Bruch erwartet).
* Cross-Dialekt-`generate` aus einer PostgreSQL-`reverse`-Datei: die Domain-`CHECK` landet an der Spalte (gewollt); `test/cross-dialect-matrix` prüfen.

**Einfluss auf die Entscheidung:** keine Messung kippt E1–E6. Sie erweitern den Umfang: (a) `domain_name` hat Vorrang vor `udt_name` (Domain über Domain heute schon falsch); (b) Array-Domain gehört dazu; (c) `data seed`/`import`/`transfer` brauchen eine Prüfung (P3), der Seed vermutlich eine Auflösung.

## P1a Messung (Ist, 2026-10-26, Stand `main`)

Skript: `test/measure-domain-spalte-68/measure.sh` (reproduzierbar, kein Gradle-Modul, nicht Teil der Build-Gates; Aufruf im Kopf des Skripts). Image `d-migrate-p68:dev` aus `main` (`954cc0002`); seither ändert sich außerhalb von `docs/` und `spec/` kein Pfad (`git diff --name-only 954cc0002 HEAD`), das Image ist also der Stand des Branchs. PostgreSQL `postgres:18.6-alpine`, handgeschriebene Dateien. Hashes gekürzt auf 12 Zeichen; `schema-fingerprint-v24`. „Ist“ ist die Datenbank-Seite (`sourceFingerprint` im Plan-Artefakt), „Soll“ die Datei (`targetFingerprint`).

Dateien: **ref** `d: { type: enum, ref_type: dd }` mit Domain `dd`; **alt** `d: { type: integer }` mit derselben Domain unter `custom_types` (die Form einer `reverse`-Datei); **plain** `d: integer`, keine Domain. Datenbanken: **dom** Domain-Spalte; **pdom** Domain angelegt, Spalte `integer`; **plain** keine Domain.

| Messung | Befund |
|---|---|
| Hash Datei (Soll) | ref `989a3ec8f587`; alt `942a38ce9dc4`; plain `ad80ae19ee6e` |
| Hash Datenbank (Ist) | dom `942a38ce9dc4`; pdom `942a38ce9dc4`; plain `ad80ae19ee6e` |
| Folgerung Hashes | Die Datenbank-Seite einer Domain-Spalte hasht **wie eine `integer`-Spalte neben derselben Domain** (dom = pdom). Die Datei `alt` hasht **gleich** der Datenbank dom (`942a38ce9dc4`): daher Exit 0 und kein Drift. Nur die Datei **ref** hasht anders (`enum(ref:dd)`). Bei (a2) bleiben alle Datenbank-Hashes und der Hash von alt/plain bitgleich; neu hasht nur ref. |
| Pläne | ref gegen dom/pdom: Exit 8; ref gegen plain: Exit 8; alt gegen dom/pdom: **Exit 0**; plain/alt gegen die jeweils falsche Domain-Lage: Exit 8 (Domain als Typ unterschiedlich, außerhalb der Frage). |
| Plan-Artefakt bei Exit 8 | **Ja:** `--plan-only --plan-artefact` schreibt es auch bei Exit 8, mit `sourceFingerprint`, `targetFingerprint` (= Datei-Hash ref `989a3ec8f587`) und `fingerprintAlgorithm`. Ein persistierter Datei-Hash existiert also. Verbraucher: `fingerprintAlgorithm` wird nicht gegen `ALGORITHM` geprüft (E11); die Hashes werden von keinem Lauf neu berechnet und verglichen (Suche im Code). Anders der **Overlay**: `MigrationOverlayValidator` (Zeilen 343–353, 583–584) bindet ein Transition-Overlay an `sourceFingerprint` (Datenbank) und `targetFingerprint` (Datei) und lehnt eine Abweichung ab (`STALE_SOURCE_FINGERPRINT`/`STALE_TARGET_FINGERPRINT`; am Code belegt, nicht gemessen). |
| Altdatei-Rollback (Weg b) | Auf `main`: alt plus neue Spalte `n` gegen dom, `--execute --generate-rollback`: Exit 0, Rollback-Artefakt **entsteht** (`currentFingerprint 942a38ce9dc4`, `desiredFingerprint = postUpFingerprint = 107b29f8983f`, `postUpVerified: true`, `recovery: false`); `schema rollback --execute`: Exit 0 (auf `main` sind beide Seiten Basistyp). Ein PostgreSQL-Drift ließ sich nicht erzeugen (u. a. `BETWEEN`-CHECK konvergiert). Der Composer ist dialektunabhängig; der dokumentierte SQLite-Drift (`biginteger` + `identity`) endet mit Exit 5, `POST_EXECUTE_DRIFT` und **ohne** Rollback-Artefakt: bestätigt `executeOk` am Lauf. Unter (b) gilt damit: Altdatei driftet, kein Artefakt (aus dem Code und dem Drift-Lauf abgeleitet, nicht an PostgreSQL gemessen). |
| Kurzschluss „gleicher Hash ⇒ kein Plan“ | **Keiner.** `DiffPlanner` und `SchemaMigrateRunner` vergleichen keine Fingerabdrücke (0 Treffer); der Plan entsteht aus dem Strukturvergleich. Fingerabdruck-Gleichheit steht nur im Overlay-Validator, im Rollback-Runner und im Post-Compare. |
| Identity an Domain-Spalte | `generation: identity` an `type: enum` + `ref_type`: `schema validate` **Exit 3, E130** („identity generation is only valid for integer or biginteger columns“); `schema generate` bricht an derselben Validierung ab (Exit 3), der Generator wird gar nicht erreicht. Auch eine `integer`-Domain mit Identity ist heute nicht schreibbar. |
| `data seed` | Datei ref gegen dom: **Exit 0, 3 Zeilen erzeugt, aber `d` in allen NULL** (0 nicht-NULL): der Seed nullt die Spalte **still**, es gibt keine `UnsupportedSeedTypeException` am CLI. Datei alt: Zahlen, aber `-668182` verletzt `dd_check` (Exit 5): der Seed kennt den `CHECK` der Domain nicht, auch ohne `ref_type`. |
| `data import` | Mit `--schema ref.yaml` in die Domain-Spalte: **Exit 3**, „column 'd' type mismatch: schema expects enum/text-compatible type but target is int4“ (Preflight lehnt ab; der JDBC-Treiber meldet den Basistyp). Ohne Schemadatei (Reader): Exit 0, 2 Zeilen eingefügt. |
| `data transfer` | PG → PG `--verify` und PG → SQLite `--verify`: Exit 0, „Verify OK“. Auf `main` liefert der Reader den Basistyp; die Datei-Pfade (`--schema`) des Transfers gibt es nicht, der Ist nach B′ ist erst nach dem Reader-Umbau messbar (P3b). |
| Parquet | `data export --format parquet --split-files` einer Domain-Spalte: Exit 0, Manifest `neutralType: kind: Integer` (numerisch). Nach B′ erst mit dem Reader-Umbau messbar (P3b). |
| SQLite-Altbefund | Kein Befund: Datei ref gegen SQLite (Basistyp + CHECK): Migrate Exit 0, Rollback-Artefakt entsteht, `schema rollback --execute` Exit 0. Der SQLite-Reader baut keine `Enum(refType)`-Spalte (am Code), beide Seiten sind der Basistyp. |

**E3 bestätigt: (a2) bleibt, mit einer ausdrücklichen Einschränkung für den Eigner (nicht gekippt).** Beleg: die Datenbank-Hashes und der Hash der Altdatei sind heute identisch (dom = pdom = alt) und bleiben es bei (a2) bitgleich; kein bestehendes **Rollback**-Artefakt ändert sich (es trägt Datenbank-Hashes und den Hash einer Datei ohne `ref_type`; aus einer ref-Datei kann heute keines entstehen, der Plan blockiert mit Exit 8). Einschränkung: der Datei-Hash von **ref** ist heute persistiert, nämlich im Plan-Artefakt (auch bei Exit 8, ungeprüft) und in einem Overlay, das jemand zu dieser Datei geschrieben hat (geprüft, `STALE_TARGET_FINGERPRINT`). Das Beispielkriterium aus E3 „ein persistierter Datei-Hash in Artefakten“ ist damit **wörtlich** erfüllt. Es kippt (a2) trotzdem nicht, weil (a1) dieselben Artefakte ebenso bricht und dazu **alle** anderen (globaler Tag-Wechsel); betroffen ist bei (a2) nur, wer auf `main` eine ref-Datei per using-Overlay am Exit 8 vorbei plante. Bleibt eine Tag-Ausnahme: das Plan-Artefakt einer ref-Datei wäre danach nicht reproduzierbar, ohne dass der Tag es verriete. Falls der Eigner diese Gruppe nicht in Kauf nehmen will: Rückfall (a1). P1b prüft den Hash der ref-Datei nach P2a (erwartet gleich alt `942a38ce9dc4`).

E7 (Identity: E130 vor dem Generator), E8 (Seed nullt still; Import-Preflight lehnt ab) und E9 sind damit entscheidbar; nichts davon ändert eine Empfehlung.

## P2a, P2a-R und P1b Ergebnis (2026-10-26)

Umgesetzt: `PostgresTableMetadataQueries.listColumns` wählt `domain_schema`/`domain_name`; der Reader liest die Typen **vor** den Tabellen und setzt `Enum(refType = <Domain>)`, wenn `domain_schema` das gelesene Schema ist und der Name in `custom_types` steht (`domain_name` vor `udt_name`; eine Zeile ohne `domain_name` ist keine Domain-Spalte; eine Spalte mit Erzeugungsart — Identity, Serial, berechnet — bleibt beim Basistyp, das Modell trägt sie nur dort). Vergleich: `TargetProjection.domainProjectionCurrentOnly` (neue Eigenschaft, nur Datenbank-Seite) mit der neuen Capability `DialectCapabilities.readsDomainColumnsAsRefType` (PostgreSQL `true`, sonst `false`; `resolvesRefTypeVocabulary` bleibt für PostgreSQL `false`); `TableComparator.enumVocabularies` projiziert dann nur die linke Seite; Verdrahtung an der einen Stelle `SchemaMigrateRunner` (`typeWithCustomTypes` über `registryDomainAwareCanonicalizer`, `domainProjectionCurrentOnly`). Abdruck (E3 a2): `PostgresNeutralTypeCanonicalizer.canonicalize(type, customTypes)` faltet eine Domain-Spalte auf den Basistyp ihrer Domain; kein Tag-Bump (`schema-fingerprint-v24`), kein ADR. `DiffPlanner.plan` nimmt zusätzlich `canonicalizeTypeFor` (schemagebunden je Endpunkt), damit auch die Abdrücke im Plan-Artefakt die Faltung tragen. P2a-R: `SchemaRollbackRunner` bindet den Kanonisierer an das gelesene Ziel-Schema (`registrySchemaAwareCanonicalizer`). KDoc-Sätze in `TypeCanonicalizerWiring`, `TargetProjection`, `NeutralTypeCanonicalizer` berichtigt.

**Abweichung vom ersten Entwurf, vom Integrationstest erzwungen:** der Basistyp wird vor dem Falten über `CustomTypeBaseType` in Neutralform gebracht (`real` = `float`, `int4[]` = `integer[]`, `timestamp(3) with time zone` = `timestamptz(3)`); ohne das hashte die Datei-Seite einer in Autorenform geschriebenen Domain anders als die Datenbank-Seite, und der Post-Compare meldete `POST_EXECUTE_DRIFT` (`PostgresCustomTypeFileFormMigrateIntegrationTest`, drei Fälle). Dafür ist `CustomTypeBaseType` public (Treiber nutzen sie).

**P1b — Hash je Seite nachher** (`test/measure-domain-spalte-68/measure.sh`, Abschnitte M1/M8/M9; Image `d-migrate-p68:before` = `main`, `d-migrate-p68:dev` = Branch; PostgreSQL `postgres:18.6-alpine`; 12 Zeichen, `schema-fingerprint-v24` auf beiden):

| Messung | vorher (`main`) | nachher |
|---|---|---|
| Datenbank-Hash (Ist) dom / pdom / plain | `942a38ce9dc4` / `942a38ce9dc4` / `ad80ae19ee6e` | **bitgleich** |
| Datei-Hash alt / plain | `942a38ce9dc4` / `ad80ae19ee6e` | **bitgleich** |
| Datei-Hash ref | `989a3ec8f587` | `942a38ce9dc4` (gleich alt und Datenbank dom, wie erwartet) |
| Datenbank-Hash je Basistyp integer, bigint, text, varchar(50), numeric(10,2), uuid, boolean | `d7e31078c9ec`, `12713b242c1f`, `304c9eae84d2`, `edcfe8e0a30b`, `3101d0e1d892`, `107b57bd4ba3`, `fa212973c7e3` | **bitgleich** (alle sieben) |
| Datei-Hash alt je Basistyp | gleich dem Datenbank-Hash | **bitgleich** |
| Datei-Hash ref je Basistyp | sieben eigene Werte (`7e014e60230f`, `f3af3bb9d650`, `615aad679763`, `bbcdc2acb1a1`, `0fde69d28ee0`, `8172c0cd97d1`, `dcad42ec0ba0`) | gleich dem Datenbank-Hash des Basistyps |
| Plan ref gegen dom | Exit 8 | **Exit 0** |
| Plan ref gegen pdom und plain (Datenbank-Spalte vom Basistyp) | Exit 8 | Exit 8 (bleibt, M1) |
| Plan alt gegen dom / pdom | Exit 0 | Exit 0 |
| Altdatei + `n`: `--execute --generate-rollback`, `schema rollback --execute` | Exit 0, Artefakt (`postUp 107b29f8983f`), Rollback Exit 0 | unverändert, `postUp 107b29f8983f` |
| ref-Datei + `n`: `--execute --generate-rollback` | Exit 8, kein Artefakt | Exit 0, Artefakt (`current 942a38ce9dc4`, `desired = postUp 107b29f8983f`, `postUpVerified True`), `schema rollback --execute` Exit 0, Folgeplan Exit 0 |
| `schema reverse` einer Domain-Spalte | `type: integer` | `type: enum`, `ref_type: dd` |
| SQLite (Migrate + Rollback mit ref-Datei; Drift-Lauf) | Exit 0 / Exit 5 ohne Artefakt | unverändert |

**E3 (a2) bestätigt:** Datenbank-Hashes und Altdatei-Hashes sind für alle gemessenen Eingänge bitgleich, kein Rollback-Artefakt ändert sich, der Tag bleibt `v24`. Die Einschränkung aus P1a gilt weiter (Plan-Artefakt einer ref-Datei von `main` trägt `989a3ec8f587`, nicht reproduzierbar ohne Tag-Signal); der Eigner hat sie in Kauf genommen. **Nicht bitgleich** (am Code abgeleitet, nicht gemessen): eine Datenbank, deren Domain-Basistyp nicht verlustfrei in `custom_types` steht — `real` (der Reader schreibt `float`, die Spalte war vorher `Float(SINGLE)`) und Basistypen ohne neutrales Gegenstück (`bit(n)`, `numeric` ohne `precision`, Array über `varchar(n)` oder eine Domain): dort ändert sich der Datenbank-Hash der Domain-Spalte, und ein vorher erzeugtes Rollback-Artefakt gegen so eine Datenbank scheitert an `TARGET_STATE_MISMATCH`. Das ist die Tag-Ausnahme von (a2), hier auf eine seltene Teilmenge begrenzt.

**Die zwei Blindheiten des Post-Compare** (E3 a): zwei Domains gleichen Basistyps an derselben Spalte und Basistyp-Spalte gegen Domain-Spalte hashen gleich; beide fing der Plan schon vorher (`AlterColumnType`, Exit 8), je ein Test in `PostgresDomainFingerprintWiringTest`; kein Hash-Kurzschluss (Plan und Hash bleiben unabhängig: ref gegen pdom hasht gleich und plant dennoch Exit 8).

**Tests:** `PostgresSchemaReaderDomainColumnTest` (Reader), `DomainColumnTypesCurrentOnlyTest` (einseitiger Vergleich je Basistyp, strikt, nur Datenbank-Seite), `PostgresNeutralTypeCanonicalizerTest` (Faltung = frühere Datenbank-Form je Basistyp, Synonyme), `PostgresDomainFingerprintWiringTest` (Abdruck, Blindheiten), `SchemaMigrateRollbackDomainColumnRoundTripTest` (Rollback-Rundlauf, ref und Alt), `PostgresDomainColumnIntegrationTest` (PostgreSQL: Reader, Plan je Basistyp, Altdatei, Exit 8 gegen Basistyp-Spalte, strikt, Rollback-Rundlauf mit ref und Altdatei), Capability-Tests PostgreSQL/SQLite. Rot-Belege: Reader, Vergleich und Kanonisierer je absichtlich gebrochen → je die zugehörigen Tests rot; Rollback-Runner auf den kontextfreien Kanonisierer zurückgesetzt → beide Rundlauf-Tests rot (`TARGET_STATE_MISMATCH`); `PostgresDomainColumnIntegrationTest` gegen den `main`-Stand (Produktionscode gestasht) → Reader-, strikter und beide Rollback-Tests rot.

## Optionen

* **A — Vergleich löst die Domain-Spalte auf (Korrektur im Vergleich).** PostgreSQL bekommt `typeWithCustomTypes` (Muster SQLite): eine Datei-Spalte `Enum(refType)` auf eine `kind: domain` projiziert der Vergleich über den Basistyp des
  Datei-`custom_types`; die Gegenseite (`Integer`) bleibt. Nur dort, wo die Gegenseite die Spalte nicht auch als `ref_type` führt (Basistypwechsel zweier Dateien bleibt ein `AlterCustomType`). Der Fingerabdruck
  löst die Datei-Seite ebenso auf (`registrySchemaAwareCanonicalizer` mit Domain-Überladung für PostgreSQL). Der Reader und das `reverse`-Format bleiben unberührt.
  * **Für A:** klein; kein Eingriff in `schema reverse`; keine Altdatei bricht; Muster und Test-Gerüst von #56 vorhanden.
  * **Gegen A:** **die Domain-Zugehörigkeit der Spalte bleibt für den Vergleich unsichtbar** — Wechsel einer Spalte von `integer` auf `dd` (oder zurück) plant nie etwas (kein `ALTER COLUMN TYPE`), obwohl PostgreSQL den Unterschied
    kennt; `reverse` → `generate` verliert die Domain-Spalte weiter. Frage 2 des Issues bliebe für die `reverse`-Datei offen. Eine Auflösung nur in der Datei trifft eine fachliche Aussage („Domain = Basistyp"), die auf PostgreSQL nicht gilt.
* **B — Reader schreibt `ref_type` statt Basistyp (Rundlauf der `reverse`-Datei ändert sich).** `listColumns` wählt `domain_schema`/`domain_name` zusätzlich; `PostgresTypeMapping.mapColumn` liefert für eine Domain-Spalte
  `Enum(refType = <Domain>)` (qualifiziert, wenn nicht im gelesenen Schema; der Domain-Basistyp und seine Länge stehen schon unter `custom_types`, ADR 0061/0065). Datei und Datenbank führen dann dieselbe Form,
  der Vergleich bleibt streng, `generate` aus der `reverse`-Datei erhält die Domain-Spalte.
  * **Für B:** behebt die Wurzel (der Reader sagt, was in der Datenbank steht); strenger Vergleich bleibt erhalten; Rundlauf vollständig; keine Sonderregel im Vergleich. Entspricht der Enum-Behandlung.
  * **Gegen B — Auswirkungen auf bestehende Dateien:**
    1. **Bestehende `reverse`-Dateien** (`type: integer` an einer Domain-Spalte) stünden gegen eine Datenbank, die nun `Enum(refType)` liest, und planten `AlterColumnType`/Exit 8 — bei **jedem** Nutzer mit Domain-Spalten,
       dessen Datei aus `schema reverse` stammt (das war bisher der einzige funktionierende Weg, siehe Issue). Das ist ein Bruch ohne Änderung der Datenbank. Abhilfe in B′ (nächster Punkt) oder „ziehen Sie `schema reverse` neu"
       (wie schon bei Domains mit `varchar(n)`, Handbuch §3.21).
    2. **B′ — Toleranz für Altdateien, nur einseitig:** der Vergleich löst die **Datenbank-Seite** über den Basistyp auf, wo die **Datei-Seite** die Spalte **nicht** als `ref_type` führt (gleicher Mechanismus
       `DomainColumnTypes`, aber auf die Datenbank-Seite beschränkt). Folge: Datei `integer` gegen Datenbank-Spalte vom Typ `dd` → kein Plan (Altdatei bleibt bei Exit 0; Preis: der Planer plant kein `ALTER` von Domain auf Basistyp,
       die Datei „verlangt“ den Basistyp). Datei `ref_type: dd` gegen Datenbank-Spalte `integer` → **weiter** `AlterColumnType`, Exit 8. **Achtung Verdrahtung:** `TableComparator.enumVocabularies` (Zeilen 272–273) ruft `DomainColumnTypes.projected`
       heute **symmetrisch** für beide Seiten auf; würde man `typeWithCustomTypes` für PostgreSQL nur verdrahten, projizierte der Vergleich auch die Datei-Seite — die Toleranz wäre beidseitig, und genau das ist der Preis von **A**,
       nicht von B′. P2 muss die Projektion einseitig machen (neue Eigenschaft oder eigene Richtung an `TargetProjection`).
    3. **Fingerabdruck:** die Datenbank-Seite hasht jetzt `enum(ref:dd)` statt `integer` — für **jede** Datenbank mit Domain-Spalten ändert sich der Abdruck des Ist-Zustands. Rollback- und Plan-Artefakte, die früher
       gegen so eine Datenbank erzeugt wurden, trügen den alten Hash und scheiterten an `TARGET_STATE_MISMATCH`/`SOURCE_FINGERPRINT_MISMATCH` (siehe E3), auch bei unverändertem Algorithmus-Tag, solange die Faltung fehlt.
    4. **Weitere Leser des Spaltentyps** (Export/Transfer/Import/Profiling/Manifest, Cross-Dialekt-`generate`; siehe offene Messungen): sie sähen `Enum(refType)` statt `Integer`. Ein Cross-Dialekt-`generate` aus einer PostgreSQL-`reverse`-Datei
       trüge die Domain-`CHECK` neu an der Spalte (gewollt, aber eine sichtbare Änderung). Die Matrix `test/cross-dialect-matrix` ist zu prüfen.
    5. **Domains außerhalb des gelesenen Schemas oder nicht lesbar:** die `custom_types` kennen sie nicht; die Spalte darf dann keinen Verweis ins Leere tragen (Validator: `ref_type` muss existieren). Regel nötig: Verweis nur, wenn die
       Domain im `custom_types` des Ergebnisses steht, sonst Basistyp wie bisher (mit Hinweis?).
* **C — nur dokumentieren.** Handbuch und Spec sagen: Eine Spalte vom Domain-Typ ist in der Datei `type: enum` + `ref_type`; `schema migrate` gegen die erzeugte Datenbank blockiert (Exit 8); die `reverse`-Datei
  führt den Basistyp. Kein Code.
  * **Gegen C:** dokumentiert einen Fehler als Eigenschaft: das Handbuch müsste schreiben, dass die einzige Schreibweise, mit der `generate` die Domain-Spalte erzeugt, den Folgeplan blockiert. Eine Ausweichform
    (`type: integer`) erzeugt keine Domain-Spalte. Es gäbe keinen Weg, der beide Anforderungen erfüllt. Nur als Interimslösung bis A/B vertretbar (Troubleshooting-Eintrag).

### Empfehlung (vom Eigner übernommen: B′)

**B′ (Reader schreibt `ref_type`, Toleranz für Altdateien im Vergleich)**, sonst **A** als schnelle Korrektur. Begründung: A behebt das Symptom im Vergleich, lässt aber den Reader eine fachlich falsche Aussage treffen
(Domain-Spalte = Basistyp-Spalte) und beantwortet Frage 2 des Issues für die `reverse`-Datei nicht; B behebt die Ursache, die Enum-Behandlung wird gleich, und B′ fängt den einzigen echten Bruch (Altdateien) ab.
Die Entscheidung berührt den `reverse`-Vertrag und ggf. den Fingerabdruck; sie gehört dem Eigner (E1–E3). Wenn der Eigner Risiko und Aufwand scheut, ist A ohne Reader-Änderung der kleinere, rückwärtskompatible Schritt;
die blinde Stelle ist dann ausdrücklich zu dokumentieren und kann später durch B ergänzt werden (A und B′ teilen den Mechanismus, in B′ nur umgekehrt).

## Ausgangsfragen E1–E6 (entschieden, siehe „Entscheidung“)

* **E1 — Option:** A (Vergleich), B (Reader, mit B′ oder ohne Toleranz) oder C (nur dokumentieren)? Empfehlung: B′.
* **E2 — Altdateien (nur B):** Toleranz im Vergleich (B′: `reverse`-Dateien mit Basistyp bleiben bei Exit 0) oder harter Bruch mit Hinweis „`schema reverse` neu ziehen"? Empfehlung: Toleranz.
* **E3 — Fingerabdruck, Bump und ADR (entschieden: (a2) vorläufig, in P1b bestätigt, Rückfall (a1)):**
  *Befunde am Code.* (1) `MigrationFingerprint.project` beginnt mit `algorithm=<ALGORITHM>`: **ein Tag-Wechsel ändert jeden Hash**, auch den von Eingängen ohne Domain-Spalte. Ein Test kann nur zeigen: nach Abzug des Tags bleiben Hashes von Eingängen ohne Domain-Spalte gleich; der Wechsel selbst ist global.
  (2) `SchemaMigrateRunner` hasht **beide Seiten unabhängig**, schemagebunden (`registrySchemaAwareCanonicalizer`); eine Toleranz des Vergleichs bildet sich im Hash nicht ab. (3) `SchemaRollbackRunner` nutzt die **kontextfreie** `registryTypeCanonicalizer` (`TypeCanonicalizerWiring` ≈44–51; Runner 55–56, 209).
  (4) Der Post-Compare (`SchemaMigrateExecutionStage` ≈294–310) vergleicht `observed == desiredFp`. (5) Plan-Artefakte führen `fingerprintAlgorithm` nur als nicht-leeres Feld (`MigrationPlanArtifactValidator`, Builder); **kein Vergleich mit `ALGORITHM`**; nur der Rollback-Parser prüft (`ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`, `SchemaRollbackRunner` ≈188).
  (6) **`postUpFingerprint` des Rollback-Artefakts** (`SchemaMigrateRollbackComposer` ≈62–72): bei `PostCompareOutcome.Clean` der **beobachtete Ist-Abdruck der Datenbank** (schemagebunden gehasht) mit `postUpVerified = true`, sonst der geplante. Das Artefakt entsteht **nur**, wenn der Lauf ohne Fehler **und ohne Drift** endete (`executeOk`). Folgen: unter (b) driftet eine Altdatei im Post-Compare
  (`POST_EXECUTE_DRIFT`), und es entsteht **gar kein** Rollback-Artefakt; unter (a) ist die schemagebundene Rollback-Verdrahtung nötig, weil der gespeicherte Ist-Abdruck schemagebunden (gefaltet), der Abdruck des Ziels im Rollback aber kontextfrei gebildet wird (`TARGET_STATE_MISMATCH` für jede Datenbank mit Domain-Spalte).
  *Globale Folgekosten jedes Bumps:* alle bestehenden Rollback-Artefakte scheitern mit `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`; Partition-Overlays (`PartitionOverlayHint.representationFingerprint`, Zeile 45; `RepresentationOverlayGate`) werden ungültig; Plan-Artefakte werden nicht erkennbar entwertet (E11).
  *Wege:*
  (a) **beide Seiten falten** die Domain-Spalte auf den Basistyp (Muster SQLite). Die Datenbank-Seite hasht dann **wie heute** (`integer`); neu hasht nur die Datei-Seite mit `ref_type`-Domain (heute Exit 8 → kaum Artefakte; P1a prüft, ob `--plan-only` bei Exit 8 ein Plan-Artefakt schreibt, `maybeWritePlanArtefact`).
  Kosten: schemagebundene Rollback-Verdrahtung (P2a-R, eigener Test); zwei Blindheiten im Post-Compare (`dd1` → `dd2` gleichen Basistyps; „Datenbank Basistyp, Datei verlangt Domain"), vom Plan vorher gefangen (einseitiger Vergleich), im Post-Compare nur nach erfolgreichem Overlay-Lauf blind, je ein Test. Ein Kurzschluss „gleicher Hash ⇒ kein Plan“ wurde nicht gefunden (P1a prüft gezielt).
  **(a1)** mit Bump `v25` + ADR 0067: sauberes Tag-Signal und präzise Meldung für Altartefakte; globale Folgekosten (siehe oben).
  **(a2)** ohne Bump: ändert sich nur der Hash der Datei-Seite mit `ref_type`-Domain; Datenbank-Hashes und alle anderen Eingänge bleiben **bitgleich**, kein globaler Verlust. Passt zum Eigner-Beschluss „ADR/Bump nur bei geändertem Hash für bestehende Eingänge“, soweit P1a belegt, dass bestehende Artefakte (Rollback-Artefakte tragen Datenbank-Hashes) unberührt bleiben.
  Gegenargument: eine Hash-Änderung ohne Tag-Signal ist eine **Ausnahme von der Praxis** der ADRs 0065/0066 (jeder Wechsel trug `vN`); ein nach (a2) vorhandenes Plan-Artefakt mit Datei-Hash `enum(ref:dd)` wäre nicht mehr reproduzierbar, ohne dass der Tag es verriete.
  (b) **nicht falten**, Bump `v25` + ADR 0067: streng, keine Blindheit, **keine** Rollback-Umverdrahtung. Kosten: Datei mit Altform (`integer`) gegen Datenbank (`enum(ref:dd)`) driftet im Post-Compare, es entsteht kein Rollback-Artefakt (siehe (6)); Toleranz E2 hielte nur im Plan; zusätzlich der globale Bump.
  (c) **ohne Bump**, nicht falten: Hash ändert sich nur für Datenbanken mit Domain-Spalte (`enum(ref:dd)` statt `integer`); Altartefakte genau dieser Datenbanken scheitern mit der irreführenden `TARGET_STATE_MISMATCH`; kein globaler Verlust. Gleiche Tag-Ausnahme wie (a2), dazu Drift der Altdatei wie (b).
  *Empfehlung (neu geprüft):* **(a), und zwar (a2), wenn P1a stützt** (Datenbank-Hashes bleiben bitgleich, kein bestehendes Artefakt bricht), sonst **(a1)**. Begründung: (a) hält Plan, Post-Compare, Rollback und Altdateien als einzige zusammen; (a2) vermeidet gegenüber (a1) den globalen Verlust (Rollback-Artefakte, Partition-Overlays) bei kleinerem Eingriff; der Preis ist die Tag-Ausnahme.
  Ehrlich: kippt P1a den Beleg (z. B. ein persistierter Datei-Hash in Artefakten), wird (a1) die Empfehlung. (b) bleibt die Alternative bei Scheu vor der Rollback-Umverdrahtung; (c) ist nur sinnvoll, wenn die Altdatei im Post-Compare irrelevant ist.
  Mitzuziehende Fingerabdruck-Stellen (Datei:Zeile, Stand `main`): `SchemaMigrateRunner.kt` 152–153 (Default kontextfrei, `typeCanonicalizerFor`), 259/269/279 (schemagebunden, Endpunkte), 444 (kontextfrei), 596 (schemagebunden, Post-Compare); `SchemaMigrateExecutionStage.kt` ≈294–310; `SchemaRollbackRunner.kt` 55–56, 209 (kontextfrei), ≈225–231; `PartitionOverlayHint.kt` 45. ADR 0067 (bei a1/b) braucht eine Zeile in `docs/adr/README.md`; ADR 0066 bleibt unberührt (`make doc-immutable`).
* **E4 — Domain außerhalb des gelesenen Schemas (B):** Verweis mit Schemaqualifizierung in `ref_type` oder Basistyp-Rückfall mit Hinweis? Empfehlung: Rückfall auf Basistyp, solange der Reader die Domain nicht in `custom_types` führt.
* **E5 — Sichtbarkeit der blinden Stelle (A, B′):** Soll `schema compare` (strikt) den Unterschied „Domain-Spalte gegen Basistyp-Spalte" zeigen, auch wenn `schema migrate` ihn nicht plant? Empfehlung: ja (strikt bleibt streng).
* **E6 — Code oder Hinweis:** braucht der Rundlauf eine eigene Meldung (neuer `R`-/`W`-Code), etwa wenn der Reader eine Domain-Spalte auf den Basistyp zurückfallen lässt? Empfehlung: nein, solange der Rückfall selten ist; sonst Ledger-Pflicht. **Entschieden = kein Code:** auch der Rückfall nach E4 (fremdes Schema, Erweiterungs-Domain) bleibt **ohne Meldung**; das ist eine bewusste Entscheidung des Eigners, kein Versehen.

## Fragen aus dem Plan-Review (entschieden 2026-10-26, siehe „Entscheidung“)

* **E7 — Validierung der Domain-Spalte (H1):** `SchemaColumnValidationRules` kennt `Enum(refType)` nur als Textfamilie: ein Spalten-`DEFAULT` als Zahl/Boolean → **E009** (`isNumericDefaultCompatible`, `BooleanLiteral`), eine Fremdschlüssel-Spalte vom Domain-Typ gegen eine
  `integer`-Spalte → **E017** (`isTypeCompatible`), Identity → **E130** (nur `Integer`/`BigInteger`). **Gemessen mit handgeschriebenen Dateien** (gleiche Form, die `reverse` nach B′ schriebe): `default: 5` → E009; FK gegen `integer` → E017;
  E130 nach Code, nicht gemessen. Eine `reverse`-Datei mit Domain-Spalte und Spalten-`DEFAULT` oder FK fiele also durch `schema validate` — B′ würde das für jede Domain-Spalte mit diesen Merkmalen **neu erzeugen**. Der PostgreSQL-Generator
  (`PostgresColumnConstraintHelper`) behandelt Enum+`refType` ohne Rücksicht auf `generation`; die Wirkung bei Identity ist in P1 zu messen. **Frage:** lösen die Regeln eine Domain über `custom_types` auf den Basistyp auf (Empfehlung; gemeinsamer Helfer, siehe E8),
  oder wird es eine bewusste, dokumentierte Grenze?
* **E8 — Umfang der Leser (M2):** die Auflösung „Domain-Spalte → Basistyp“ gehört in **einen** Helfer in `hexagon/core`, nicht in P2c nur für `data seed`. Lesestellen: `ColumnValueGenerator` (Seed), `TransferVerifier.canonicalFamily`
  (`Enum` = text gegen `Integer` = numeric: bei Quelle PostgreSQL, Ziel anderer Dialekt fällt die Spalte mit „cross-dialect representation transform“ **still aus der Byte-Verifikation**), `ImportTypeCompatibility.isEnumCompatible`
  (PostgreSQL-Treiber meldet voraussichtlich den Basistyp → „inkompatibel“; Pflichtmessung P3), `CanonicalValueCodec` (hasht `Enum` als UTF-8-Text), Parquet (`ChunkSchemaToParquetMessageType`: `Enum` → `BINARY`; `ParquetGroupValueWriter` schreibt
  `toString`: eine `integer`-Domain-Spalte würde eine **String-Spalte**), `CompareValueText`. **Frage:** je Stelle auflösen (Empfehlung) oder bewusst beim Basistyp-Verhalten bleiben; **die Seed-Frage bleibt offen** (Empfehlung: auflösen).
* **E9 — Domain über Domain und Domain-Herkunft (M3):** (i) `listDomainTypes` filtert `n.nspname = ?` und schließt Erweiterungs-Domains aus; B′ vergleicht daher `domain_schema` mit dem gelesenen Schema **und** den Namen mit `custom_types` (Rückfall E4 sonst).
  (ii) Domain über Domain: `domainBaseType` liefert `mapParamType("d1")`, der Kanonisierer fällt auf **R301**; heute schreibt der Reader dafür schon (falsch) `ref_type: d1`. **Frage:** unterstützen (Basistyp rekursiv auflösen) oder als Grenze festschreiben (Rückfall auf Basistyp der äußeren
  Domain, dokumentiert, Test)? Empfehlung: **Grenze**, mit Test und Zeile im Handbuch; `domain_name` hat Vorrang vor `udt_name`, damit wenigstens die richtige (äußere) Domain genannt wird.

* **E10 — ein oder zwei Slices:** (i) ein Slice, Phasen P2a → P2b → P2c in Commit-Reihenfolge; (ii) zwei Slices (Reader/Vergleich/Fingerabdruck/Validierung, dann Leser). Folge von (ii): nach Slice 1 brechen `reverse`-Dateien `data seed` und die Transfer-Verifikation spart die Spalte still aus → Release-Sperre auf Slice 2. Empfehlung: ein Slice (Begründung: die Seed-/Transfer-Lücke, nicht die Größe).
* **E11 — Guard für Plan-Artefakte (M5):** `fingerprintAlgorithm` wird bei Plan-Artefakten nicht gegen `ALGORITHM` geprüft; ein Bump würde sie nicht erkennbar entwerten (Wirkung ungeprüft). Ein Guard wäre eigenes Arbeitspaket. **Frage:** nur Rollback-Test (nichts über Plan-Artefakte behaupten) oder Guard bauen? Empfehlung: Guard als eigener Slice, falls der Bump kommt; im Plan hier nur Rollback testen.
* **E12 — Release und Versionierung:** das neue `reverse`-Format (`ref_type` an Domain-Spalten) ist ein sichtbarer Vertrag; bei Bump (a1/b) kommen ungültige Rollback-Artefakte und Partition-Overlays dazu. Optionen: Patch (1.11.2) oder Minor (1.12.0), jeweils mit Release-Sperre aus E10. Abwärtskompatibilität gespeicherter Artefakte ist **ausdrücklich** zu benennen: ohne Bump (a2/c) bleiben Rollback-Artefakte gültig; mit Bump scheitern alle alten an `…ALGORITHM_MISMATCH` und sind neu zu erzeugen; Plan-Artefakte werden nicht geprüft (E11). Empfehlung: Minor 1.12.0 bei Bump oder neuem Format (der `CHANGELOG`-Eintrag steht unter `[Unreleased]`), Patch nur bei (a2) ohne sichtbare Formatänderung — die `reverse`-Datei ändert sich aber stets, daher in der Praxis Minor.
* **E8-Seed (eigene Frage):** soll `data seed` eine Domain-Spalte über den Basistyp auflösen (Zahlen erzeugen) oder die Spalte nullen/abbrechen? Empfehlung: auflösen.
* **Reihenfolge (keine Frage):** P2a → P2b → P2c ist **verbindliche Commit-Reihenfolge**; P2c wird nicht nach dem Release von P2a ausgeliefert, sonst steht die Seed-/Transfer-Lücke im Release.

## Umfang von B′ (gewachsen)

Nicht mehr nur „Reader + einseitiger Vergleich“: dazu kommen (1) einseitige Projektion (M1), (2) Validierungsregeln (E7), (3) Fingerabdruck je Seite mit Bump und ADR 0067 (E3), (4) ein gemeinsamer Helfer und die Leser in Seed, Verifikation, Import, Codec, Parquet (E8),
(5) `domain_schema`-Prüfung und Grenze für Domain über Domain (E9), (6) Cross-Dialekt-Test. Realistisch mehr als ein Reader-Slice. **E10:** ein Slice (Phasen P2a–P2c) oder zwei? **Begründung der Empfehlung ‚ein Slice‘ ist nicht die Größe, sondern die Seed-/Transfer-Lücke:** zwei Slices lieferten nach Slice 1 einen Reader, dessen `reverse`-Dateien `data seed` brechen und die Transfer-Verifikation stumm aussparen; Slice 2 dürfte dann nie nach dem Release von Slice 1 liegen. Bei zwei Slices Release-Sperre auf Slice 2.

**Hinweis MCP:** der MCP-Adapter verdrahtet `SchemaMigrateRunner` und `SchemaRollbackRunner` **nicht** (am Code: kein Treffer in `adapters/driving/mcp/src/main`; der Migrate-Tool-Slice liegt in `next/`). MCP wirkt über den **Reader** (Reverse-Werkzeuge) und `SchemaCompareSemantics` (`schema_compare`, strikt, `SchemaComparator` ohne `TargetProjection`: `schema compare` bleibt nach E5 streng). Stichprobe in P3b.

## Abgrenzung

* Nicht: `ALTER DOMAIN`, `varchar`-/Array-Grenze der Domain selbst (`AlterCustomType`, `POSTGRES_RENDER_SKIP`) — bekannt, Handbuch §3.21, #52/#58/#59.
* Nicht: SQLite (kein Typ-Objekt; Vergleich über #56 gelöst), MySQL, MSSQL, Oracle (keine Domain als Spaltentyp).
* Nicht: Kollation/Default/`NOT NULL` der Domain (#59); die Spalte behält ihr `required` aus `attnotnull`.

## Arbeitspakete

* **Schritt 0 Spec in zwei Teilen (vor dem Code, nur Zielbild, keine Links auf Pläne/ADRs — `.d-check.yml` matrix; `spec/` darf Ungebautes beschreiben).** **0a** (jetzt möglich): Verweis und Reverse-Form (unten). **0b** (nach E7/E9): Zielbild Validierung der Domain-Spalte (DEFAULT/FK/Identity), Domain über Domain, Domain aus fremdem Schema; ohne diese Entscheidungen nicht formulierbar.
  * `spec/schema-reference.md` Abschnitt „Domain": „Verwendung in Spalten: `type: enum` + `ref_type: <domain>`" (heute nur beim Enum); je Dialekt, was daraus wird (PostgreSQL: Spalte vom Domain-Typ; MySQL, SQLite, MSSQL: Basistyp + `CHECK`).
  * `spec/ddl-generation-rules.md` (Domain-Abschnitt um Z. 1296ff.): Zielbild, zeitlos und unabhängig von Toleranz (E2) und `schema compare`-Strenge (E5): „`schema reverse` schreibt eine Spalte vom Domain-Typ als `type: enum` + `ref_type`; `schema migrate` plant für eine unveränderte Domain-Spalte nichts.“ Die Toleranz für Basistyp-Dateien ist Verhalten, kein Zielbild: sie steht im Handbuch (P5), nicht in der Spec. Keine Links auf Pläne oder ADRs.
  * `spec/neutral-model-spec.md` (§13.2/`ref_type` Zeile 221): Verweisziel ist `enum` **oder** `domain`; ggf. `spec/cli-spec.md` (`schema reverse`), falls dort die Spalten-Form genannt ist.
* **P0 Messung** (Tabellen oben, vollständig). DoD erfüllt: Tabelle und Befund im Slice, Aufbau beschrieben. Nachzumessen in P1: Identity an einer Domain-Spalte (E130, Generator), `data seed`/`import`/`transfer`/Parquet mit Domain-Spalte (E8).
* **P1a Messung auf `main` (vor jeder Entscheidung):** mit handgeschriebenen Dateien: Ist-Hashes je Seite (Datei mit/ohne Domain-Spalte, Altdatei, Datenbank mit Domain-Spalte); Rollback nach Migrate mit Altdatei (unter (b) entsteht nach Befund (6) gar kein Artefakt — messen); schreibt `--plan-only` bei Exit 8 ein Plan-Artefakt (`maybeWritePlanArtefact`); Suche nach Kurzschluss „gleicher Hash ⇒ kein Plan“
  (`DiffPlanner`, `SchemaMigrateRunner`); Lesestellen (Seed, Import, Transfer, Parquet, Codec) mit handgeschriebener Domain-`ref_type`-Datei; Identity an Domain-Spalte; Befund, ob SQLite-Domains denselben Altbefund (kontextfreier Rollback) haben — am Code belegt nein: der SQLite-Reader baut keine `Enum(refType)`-Spalte, er liest nur Wertevorrat-`CHECK`s (`SqliteSchemaReader` ≈326–335); die Datenbank-Seite einer Domain-Spalte ist dort Basistyp.
  Das Messskript (Issue-Ablauf plus `compare`/`generate`) wird **reproduzierbar unter `test/`** abgelegt (Arbeitspaket, nicht in die Doku verlinkt). DoD: Messtabelle im Slice; E3/E7/E8/E9 entscheidbar.
* **P1b nach P2a (Hash nachher):** Hash je Seite nachher, Wahl a/b/c bestätigt, **ADR 0067 geschrieben** (Zeile in `docs/adr/README.md`; `make doc-immutable` beachten) **vor** P2b/P2c. Alternativ analytisch vorab entscheiden, wenn P1a die Hashes eindeutig herleitet.
* **P2 Umsetzung** (je Option):
  * **A:** `PostgresNeutralTypeCanonicalizer.canonicalize(type, customTypes)` löst `Enum(refType)` auf eine Domain über deren Basistyp (`PostgresDomainBaseType`); `TypeCanonicalizerWiring.registryDomainAwareCanonicalizer`
    nicht mehr an `resolvesRefTypeVocabulary` hängen, sondern an eine eigene Fähigkeit (`DialectCapabilities`, Muster SQLite); KDoc „Fuer PostgreSQL waere ein Aufloesen falsch" berichtigen.
  * **B′ (entschieden):** `PostgresTableMetadataQueries.listColumns` um `domain_schema`, `domain_name`; `PostgresTypeMapping.ColumnInput`/`mapColumn` um die Domain; `PostgresSchemaStructureReaders` setzt den Verweis nur, wenn die Domain in `custom_types`
    steht (E4); `domain_name` hat Vorrang vor `udt_name` (Domain über Domain, Array-Domain; fehlt das Feld in einer Zeile, keine Domain); `DomainColumnTypes` in Gegenrichtung (Datenbank-Seite) nach dem Muster `TargetProjection.typeWithCustomTypes`.
  * **P2a Reader und Vergleich** (oben), **einseitige** Projektion nur der Datenbank-Seite (M1): neue Eigenschaft bzw. Richtung an `TargetProjection` (kein Verdrahten von `typeWithCustomTypes` allein), Anpassung in `TableComparator.enumVocabularies` (Zeilen 272–273) und `DomainColumnTypes`; eigene Capability statt `resolvesRefTypeVocabulary` (für PostgreSQL `false`).
    Die KDoc-Sätze in `TypeCanonicalizerWiring` (≈78, „Fuer PostgreSQL waere ein Aufloesen falsch“) und in `TargetProjection.typeWithCustomTypes` sind für B′ zu berichtigen: für die **Spalte** stimmt der Satz nicht.
  * **P2a-R Rollback-Verdrahtung (nur bei E3 a1/a2):** `SchemaRollbackRunner` bindet den Kanonisierer an das gelesene Ziel-Schema (`registrySchemaAwareCanonicalizer`) statt an `registryTypeCanonicalizer`; Test: Rollback-Rundlauf gegen Datenbank mit Domain-Spalte (Migrate mit `ref_type`-Datei und mit Altdatei, danach Rollback).
  * **P2b Validierung (E7):** Regeln lösen `Enum(refType)` einer Domain über `custom_types` auf den Basistyp auf (E009/E017/E130) — oder dokumentierte Grenze; Generator (`PostgresColumnConstraintHelper`, Identity) prüfen.
  * **P2c Gemeinsamer Helfer und Leser (E8), je Lesestelle ein Commit:** **P2c-0** gemeinsamer Helfer „Basistyp einer Domain-Spalte“ in `hexagon/core`; dann **P2c-1** Seed (`ColumnValueGenerator`, E8-Seed; **P1a-Befund:** mit der `ref_type`-Datei nullt `data seed` die Domain-Spalte still (Exit 0, alle NULL), mit der Altdatei scheitert er an `dd_check` — P2c-1 muss neben dem Basistyp auch die `CHECK`-Bedingung der Domain respektieren, nicht nur den Basistyp auflösen); **P2c-2** Transfer/Verifikation (`TransferVerifier.canonicalFamily`, `DataTransferRunner`); **P2c-3** Import (`ImportTypeCompatibility`); **P2c-4** Codec/Parquet/`CompareValueText` (`CanonicalValueCodec`, `ParquetGroupValueWriter`/`Reader`, `ChunkSchemaToParquetMessageType`). Jede Stelle löst auf oder bleibt bewusst beim Basistyp (je begründet).
  * **Verdrahtung, getrennt gezählt:** die **Vergleichs**-Projektion hat **eine** Stelle (`SchemaMigrateRunner.kt:643`); der **Fingerabdruck** hat die in E3 mit Datei:Zeile gelisteten Stellen — bei E3 (a) alle schemagebunden, nie eine zweite Variante pro Stelle.
  * DoD **je Phase** (P2a, P2b, P2c-n): `make docker-check MODULES=…` je betroffenem Modul, **einmal ohne `MODULES`** (geteilte Signatur: Modell, Port, Fingerabdruck), `make solid-suppression-gate`, kein `@Suppress`.
* **P3a Tests (Reader, Vergleich, Fingerabdruck, Validierung):** **P3b Tests (Leser, Cross-Dialekt, MCP-Stichprobe)** folgen nach P2c. **P3 Inhalt (beide):** Unit: Reader-Zeile mit `domain_name` (B) bzw. Kanonisierer/Vergleich (A); Vergleich pro Basistyp-Matrix (integer, text, varchar(n), decimal(p,s), uuid, boolean, biginteger); Enum-Kontrolle; Domain ohne Spalte;
  Datei gegen Datei (Basistypwechsel bleibt ein `AlterCustomType`). Integration PostgreSQL (Testcontainers): generate → anwenden → `migrate --plan-only` Exit 0 → `reverse` → `generate` (Domain-Spalte erhalten, B) → Folgeplan Exit 0;
  Altdatei (`type: integer`) Exit 0 (B′); Datei `ref_type: dd` gegen Datenbank-Spalte `integer` bleibt Exit 8 (M1); `reverse` → `validate` mit Domain-Spalte mit Spalten-`DEFAULT`, mit FK-Spalte vom Domain-Typ (E7); Cross-Dialekt: PostgreSQL-`reverse` mit Domain-Spalte → `generate` für MySQL, SQLite und MSSQL (Basistyp + `CHECK`; **E053** bei nicht abbildbarem Basistyp, **W100** bei `timestamptz`; E054 betrifft Composite, nicht Domain) in `test/cross-dialect-matrix`; Altartefakt (Rollback- und Plan-Artefakt gegen Datenbank mit Domain-Spalte) → nach Bump `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` (Rollback-Artefakt; für **Plan-Artefakte** wird heute nichts geprüft, siehe E11 — kein Test darüber); Domain über Domain (`ref_type` = die äußere Domain); Array-Domain; Domain aus fremdem Schema (Rückfall Basistyp); `data seed`, `data import`, `data transfer` mit Domain-Spalte; strikter `schema compare` zeigt Domain-Spalte gegen Basistyp-Spalte (E5). Fingerabdruck-Test (E3). DoD: Tests laufen wirklich (einmal absichtlich rot, `-PintegrationTests` beachten: `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"`).
* **P4 Ledger:** entfällt (E6: kein neuer Code). Falls sich das ändert: neue Codes: `ledger/warn-code-ledger-1.1.0.yaml` (W) bzw. `ledger/error-code-ledger-1.1.0.yaml` (E/R) mit `test_path`/`evidence_paths`, Zeile in `spec/ledger.md`;
  `CodeLedgerValidationTest` läuft mit. Sonst entfällt P4 (der Slice führt heute keinen Code ein).
* **P5 Doku (nach dem Code):**
  * Handbuch §3.21: Beispiel einer Domain-Spalte (`type: enum` + `ref_type`) beim Enum-Beispiel; ein Satz zum Rundlauf (`generate` → `migrate`, `reverse`) — zeitlos, ohne „bisher/neu/ab".
  * Anhang F.3 (Zeile `ref_type`): „Nur bei Typ `enum`: Verweis auf `custom_types`" um Domain erweitern; F.9 mit einem Verweis aus einer Spalte.
  * Toleranz (E2): ein zeitloser Satz in §3.21, dass eine Datei mit Basistyp an einer Domain-Spalte als unverändert gilt (kein Hinweis „neu ziehen“; E2 ist entschieden).
  * `CHANGELOG.md`: **ein** Eintrag unter **`[Unreleased]`** mit Befund und Fix (Betroffene: PostgreSQL mit Domain-Spalte, gemessen mit 1.11.1; Folge: Exit 8 bei `migrate`; neue `reverse`-Form; Fingerabdruck und Artefakt-Folgen nach E3; die Zielversion entscheidet E12). **In `docs/user/` keine Chronik**, kein Troubleshooting-Eintrag für den alten Zustand.
  * `make docs-check` vor dem Commit.

## Akzeptanzkriterien

Art je Kriterium: **Rot** (scheitert auf `main`; vor dem Fix einmal absichtlich rot sehen), **Wächter** (grün auf `main`, darf nie rot werden), **nach Umbau rot** (scheitert erst nach dem Reader-Umbau/der Folgearbeit), **Gate**. Je E3-Weg entfallen oder ändern sich Kriterien (Tabelle unten).

1. **Rot.** Datei `d: { type: enum, ref_type: dd }` mit `kind: domain` über jeden Basistyp (integer, text, varchar(n), decimal(p,s), uuid, boolean, biginteger): `generate` → anwenden → `schema migrate --plan-only` Exit 0 ohne Operation; `schema compare` Datei ↔ Datenbank `IDENTICAL`. Heute Exit 8 / `DIFFERENT`.
2. **Wächter.** Echte Änderungen planen weiter: Verweis auf eine andere Domain; Basistypwechsel der Domain; Datei `ref_type: dd` gegen Datenbank-Spalte `integer` (Exit 8, `AlterColumnType`). Datei `integer` gegen Datenbank-Spalte vom Typ `dd` plant nichts (E2). Alle grün auf `main`; ein beidseitiges Verdrahten ließe den ersten rot werden.
2b. **Nach Umbau rot.** Strikter `schema compare`: Datei `integer` gegen Datenbank-Spalte vom Typ `dd` zeigt `DIFFERENT` (E5). Auf `main` heute `IDENTICAL`, Exit 0 (der Reader liefert `integer`); erst nach dem Reader-Umbau sichtbar.
3. **Wächter.** Kontrollen: Enum-Spalte Exit 0; Domain ohne Spalte Exit 0; `varchar`-Domain selbst blockiert wie dokumentiert (`POSTGRES_RENDER_SKIP`).
4a. **Rot.** `schema reverse` einer Domain-Spalte schreibt `type: enum` + `ref_type`; `generate` aus dieser Datei erzeugt die Domain-Spalte; `migrate --plan-only` Exit 0 (heute `type: integer`, `"d" INTEGER`).
4a'. **Nach Umbau rot (an den Reader-Umbau gebunden).** Array-Domain und `NOT NULL`-/`DEFAULT`-Domain-Spalte schreiben `ref_type` und erzeugen die Domain-Spalte.
4b. **Wächter.** Eine ältere `reverse`-Datei (Basistyp) bleibt bei Exit 0; Domain aus fremdem Schema und Erweiterungs-Domain schreiben weiter den Basistyp (E4).
5a. **Rot.** Post-Compare: `migrate --execute` einer unabhängigen Änderung (neue Spalte) gegen eine Datei mit `ref_type: dd` ohne `POST_EXECUTE_DRIFT` (heute blockiert der Plan, Exit 8).
5b. **Wächter, gilt bei der entschiedenen Wahl (a2, Rückfall a1).** Dasselbe mit einer Altdatei (Basistyp): kein Drift im Post-Compare. **Bei (b) und (c) unerfüllbar und entfällt** (dort `POST_EXECUTE_DRIFT`, kein Rollback-Artefakt).
6. **Je Weg (E3).** Je Seite Hash vorher/nachher dokumentiert; **für jeden Weg (a1, a2, b, c)** gilt: nach Abzug des Tags `algorithm=` bleiben Hashes von Eingängen ohne Domain-Spalte gleich; bei (a2) zusätzlich Datenbank-Hashes bitgleich; bei (c) Datenbank-Hash mit Domain-Spalte als neuer Wert fixiert. Bump (a1/b): `v25`, ADR 0067, `MigrationFingerprintTest`, Rollback-Artefakt alter Version → `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`.
7. **Nach Umbau rot (Vorbedingung Reader-Umbau).** Validierung (E7): `reverse` → `validate` einer Domain-Spalte mit Spalten-`DEFAULT` und einer FK-Spalte vom Domain-Typ besteht (oder die Grenze ist dokumentiert und getestet). Mit handgeschriebener Datei heute E009/E017.
8. **Nach Umbau rot (Vorbedingung Reader-Umbau).** Leser (E8): je Lesestelle (Seed, Transfer-Verifikation, Import-Preflight, Codec, Parquet, `CompareValueText`) ein Test mit einer `integer`-Domain-Spalte: gleiches Ergebnis wie mit einer `integer`-Spalte (Seed erzeugt Zahlen; `data transfer` PostgreSQL → PostgreSQL und → MySQL verifiziert die Spalte; Import besteht den Preflight; Parquet bleibt numerisch). Ist-Lage in P1a.
9. **Gate.** Cross-Dialekt: PostgreSQL-`reverse` mit Domain-Spalte → `generate` für MySQL, SQLite, MSSQL ergibt Basistyp + `CHECK` an der Spalte (E053/W100 wie dokumentiert); `test/cross-dialect-matrix` grün.
10. **Gate.** Spec (Schritt 0a/0b) und Handbuch (P5) stimmen mit dem Verhalten überein; `docs/user/` beschreibt nur den Ist-Zustand.
11. **Gate.** `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`, `make solid-suppression-gate`, `make docker-check` je Modul und je Phase einmal ohne `MODULES`, Integrationslauf PostgreSQL mit `-PintegrationTests`.

| Weg | entfällt / ändert sich |
|---|---|
| a2 (entschieden, vorläufig), a1 (Rückfall) | Kriterium 5b gilt; Rollback-Rundlauf (P2a-R) und die zwei Blindheiten im Post-Compare sind zu testen; a2: Datenbank-Hashes bitgleich (6) |
| b (nicht gewählt) | 5b entfällt; kein Rollback-Artefakt bei Altdatei-Drift; keine Rollback-Umverdrahtung, keine Blindheit-Tests |
| c (nicht gewählt) | 5b entfällt; kein globaler Bump; Altartefakte für Datenbanken mit Domain-Spalte scheitern mit `TARGET_STATE_MISMATCH` |

## Verifikation

* Messskript wie im Issue gegen ein `make docker-build`-Image (nicht das veröffentlichte); Ergebnis je Fall in die P0-Tabelle.
* Der Testfall je Kriterium ist als Einzelzeile benennbar (Klasse, Methode) und im ImpPlan nachzutragen, sobald P3 beginnt.
* Done-ness wird gegen Lastenheft, Spec (`spec/neutral-model-spec.md`, `spec/schema-reference.md`, `spec/ddl-generation-rules.md`) und ADR begründet, nicht gegen diesen Plan.
