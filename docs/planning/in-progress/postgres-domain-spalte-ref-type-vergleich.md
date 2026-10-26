# PostgreSQL: eine Spalte vom Domain-Typ (`type: enum` + `ref_type`) plant bei jedem Lauf `AlterColumnType` und blockiert mit Exit 8 (Issue #68)

> **Status: Entschieden (Eigner, 2026-10-26): Option B′; P0-Messung vollständig, Umsetzung offen.** Es gibt noch keinen Implementierungs-Commit. Der Plan liegt auf Anweisung des Eigners
> schon in `in-progress/` (ADR 0004 sieht den Move erst mit dem ersten Implementierungs-Commit vor).
> **Vorbedingung:** E1–E6 entschieden (siehe „Entscheidung“); offen bleibt nur die Fingerabdruck-Prüfung in P1 (E3). **Reihenfolge:** **Schritt 0** Spec-Prosa (vor dem Code) → P1 → P2–P4 → P5 Doku.
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
* **E3 = Faltung in P1 prüfen:** ADR und `schema-fingerprint-v25` nur, wenn sich der Hash für **bestehende** Eingänge ändert (Datei ohne Domain-Spalte darf nicht wechseln; Datenbank mit Domain-Spalte gegen Altartefakt prüfen).
* **E4:** Domain außerhalb des gelesenen Schemas (nicht in `custom_types` des Ergebnisses) → Rückfall auf den Basistyp wie bisher.
* **E5:** strikter `schema compare` (Bundle `null`) zeigt den Unterschied Domain-Spalte gegen Basistyp-Spalte weiter; die Toleranz gilt nur im ziel-bewussten Vergleich (`schema migrate`/Fingerabdruck).
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
* **Messaufbau:** Grundlage ist das Skript aus Issue #68 (generate → `psql` → `migrate --plan-only` → `reverse` → `migrate`), ergänzt um `schema compare` und `generate` aus der `reverse`-Datei; die Datenbank der Zusatzmessungen wurde per `psql`-DDL angelegt. Das Skript liegt nicht im Repo; die Fälle sind mit dem Issue-Skript und den obigen Domain-Zeilen reproduzierbar.
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

## Entscheidungsfragen (entschieden, siehe „Entscheidung“)

* **E1 — Option:** A (Vergleich), B (Reader, mit B′ oder ohne Toleranz) oder C (nur dokumentieren)? Empfehlung: B′.
* **E2 — Altdateien (nur B):** Toleranz im Vergleich (B′: `reverse`-Dateien mit Basistyp bleiben bei Exit 0) oder harter Bruch mit Hinweis „`schema reverse` neu ziehen"? Empfehlung: Toleranz.
* **E3 — Fingerabdruck und ADR (konkretisiert, offen):** `SchemaMigrateRunner` (≈246–285) hasht **beide Seiten unabhängig** (`MigrationFingerprint.compute` je Seite mit `registrySchemaAwareCanonicalizer`); eine Toleranz des Vergleichs
  (B′) bildet sich im Hash **nicht** ab. Heute: Datei `ref_type: dd` → `enum(ref:dd)`, Datenbank-Spalte → `integer`; Altdatei → `integer`. P1 misst **je Seite vorher/nachher**: Datei mit und ohne Domain-Spalte, Datenbank mit Domain-Spalte,
  Altdatei (Basistyp) gegen Datenbank (Post-Compare nach `--execute` einer unabhängigen Änderung!). Zwei Wege, beide **ändern Hashes bestehender Eingänge** und führen nach der Regel von E3 zu `v25` + ADR 0067:
  (a) **beide Seiten falten** die Domain-Spalte auf den Basistyp (Muster SQLite): Post-Compare bleibt für Altdateien sauber (passt zu E2), der Datei-Hash von `ref_type: dd` ändert sich, und der Post-Compare ist blind für `dd1` → `dd2` gleichen Basistyps;
  (b) **kein Falten** (Hash trägt `enum(ref:dd)` auf der Datenbank-Seite): streng, aber eine Altdatei meldet nach `--execute` einer unabhängigen Änderung `POST_EXECUTE_DRIFT` — die Toleranz E2 hielte im Plan, nicht im Post-Compare.
  Der Bump ist hier kein zu vermeidendes Übel, sondern die ehrliche Folge (Präzedenz ADR 0066 Entscheidung 6: Altartefakt → `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`). Empfehlung: **(a) mit `v25` + ADR 0067**; Begründung in P1 festhalten.
  Mitzuziehen sind alle Stellen, die den Abdruck bilden: `SchemaMigrateRunner`, `SchemaRollbackRunner` (≈225–231), `PartitionOverlayHint.representationFingerprint` (Zeile 45), `SchemaMigrateExecutionStage`.
* **E4 — Domain außerhalb des gelesenen Schemas (B):** Verweis mit Schemaqualifizierung in `ref_type` oder Basistyp-Rückfall mit Hinweis? Empfehlung: Rückfall auf Basistyp, solange der Reader die Domain nicht in `custom_types` führt.
* **E5 — Sichtbarkeit der blinden Stelle (A, B′):** Soll `schema compare` (strikt) den Unterschied „Domain-Spalte gegen Basistyp-Spalte" zeigen, auch wenn `schema migrate` ihn nicht plant? Empfehlung: ja (strikt bleibt streng).
* **E6 — Code oder Hinweis:** braucht der Rundlauf eine eigene Meldung (neuer `R`-/`W`-Code), etwa wenn der Reader eine Domain-Spalte auf den Basistyp zurückfallen lässt? Empfehlung: nein, solange der Rückfall selten ist; sonst Ledger-Pflicht.

## Neue Fragen aus dem Plan-Review (offen, mit Empfehlung)

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

## Umfang von B′ (gewachsen)

Nicht mehr nur „Reader + einseitiger Vergleich“: dazu kommen (1) einseitige Projektion (M1), (2) Validierungsregeln (E7), (3) Fingerabdruck je Seite mit Bump und ADR 0067 (E3), (4) ein gemeinsamer Helfer und die Leser in Seed, Verifikation, Import, Codec, Parquet (E8),
(5) `domain_schema`-Prüfung und Grenze für Domain über Domain (E9), (6) Cross-Dialekt-Test. Realistisch mehr als ein Reader-Slice; falls der Eigner es trennen will: **Slice 1** Reader + Vergleich + Fingerabdruck + Validierung, **Slice 2** Leser (E8) — dann bliebe Slice 1 mit der
Seed-/Transfer-Lücke ausgeliefert, was Slice 2 vor dem Release braucht. Das ist eine Eigner-Frage (**E10**), Empfehlung: ein Slice, Phasen P2a–P2c.

## Abgrenzung

* Nicht: `ALTER DOMAIN`, `varchar`-/Array-Grenze der Domain selbst (`AlterCustomType`, `POSTGRES_RENDER_SKIP`) — bekannt, Handbuch §3.21, #52/#58/#59.
* Nicht: SQLite (kein Typ-Objekt; Vergleich über #56 gelöst), MySQL, MSSQL, Oracle (keine Domain als Spaltentyp).
* Nicht: Kollation/Default/`NOT NULL` der Domain (#59); die Spalte behält ihr `required` aus `attnotnull`.

## Arbeitspakete

* **Schritt 0 Spec (vor dem Code, nur Zielbild, keine Links auf Pläne/ADRs — `.d-check.yml` matrix; `spec/` darf Ungebautes beschreiben):**
  * `spec/schema-reference.md` Abschnitt „Domain": „Verwendung in Spalten: `type: enum` + `ref_type: <domain>`" (heute nur beim Enum); je Dialekt, was daraus wird (PostgreSQL: Spalte vom Domain-Typ; MySQL, SQLite, MSSQL: Basistyp + `CHECK`).
  * `spec/ddl-generation-rules.md` (Domain-Abschnitt um Z. 1296ff.): Zielbild, zeitlos und unabhängig von Toleranz (E2) und `schema compare`-Strenge (E5): „`schema reverse` schreibt eine Spalte vom Domain-Typ als `type: enum` + `ref_type`; `schema migrate` plant für eine unveränderte Domain-Spalte nichts.“ Die Toleranz für Basistyp-Dateien ist Verhalten, kein Zielbild: sie steht im Handbuch (P5), nicht in der Spec. Keine Links auf Pläne oder ADRs.
  * `spec/neutral-model-spec.md` (§13.2/`ref_type` Zeile 221): Verweisziel ist `enum` **oder** `domain`; ggf. `spec/cli-spec.md` (`schema reverse`), falls dort die Spalten-Form genannt ist.
* **P0 Messung** (Tabellen oben, vollständig). DoD erfüllt: Tabelle und Befund im Slice, Aufbau beschrieben. Nachzumessen in P1: Identity an einer Domain-Spalte (E130, Generator), `data seed`/`import`/`transfer`/Parquet mit Domain-Spalte (E8).
* **P1 Folgenabschätzung (E1–E6 entschieden; E3 Hashe je Seite, E7–E10 offen):** die Leser des Spaltentyps (Export/Transfer/Import/Profiling/Manifest) und die Cross-Dialekt-Matrix gegenprüfen; Fingerabdruck-Prüfung: derselbe Eingang,
  Hash vorher/nachher (Datei-Seite und Datenbank-Seite je Option). DoD: Entscheidung dokumentiert; ADR (E3) geschrieben, falls ein Hash sich ändert (`make doc-immutable` beachten: Frontmatter-Kern akzeptierter ADRs nicht anfassen).
* **P2 Umsetzung** (je Option):
  * **A:** `PostgresNeutralTypeCanonicalizer.canonicalize(type, customTypes)` löst `Enum(refType)` auf eine Domain über deren Basistyp (`PostgresDomainBaseType`); `TypeCanonicalizerWiring.registryDomainAwareCanonicalizer`
    nicht mehr an `resolvesRefTypeVocabulary` hängen, sondern an eine eigene Fähigkeit (`DialectCapabilities`, Muster SQLite); KDoc „Fuer PostgreSQL waere ein Aufloesen falsch" berichtigen.
  * **B′ (entschieden):** `PostgresTableMetadataQueries.listColumns` um `domain_schema`, `domain_name`; `PostgresTypeMapping.ColumnInput`/`mapColumn` um die Domain; `PostgresSchemaStructureReaders` setzt den Verweis nur, wenn die Domain in `custom_types`
    steht (E4); `domain_name` hat Vorrang vor `udt_name` (Domain über Domain, Array-Domain; fehlt das Feld in einer Zeile, keine Domain); `DomainColumnTypes` in Gegenrichtung (Datenbank-Seite) nach dem Muster `TargetProjection.typeWithCustomTypes`.
  * **P2a Reader und Vergleich** (oben), **einseitige** Projektion nur der Datenbank-Seite (M1). Einzige Änderungsstelle der Typ-Projektion: `SchemaMigrateRunner.kt:643` (`typeWithCustomTypes`); der Abdruck wird an den vier in E3 genannten Stellen gebildet.
  * **P2b Validierung (E7):** Regeln lösen `Enum(refType)` einer Domain über `custom_types` auf den Basistyp auf (E009/E017/E130) — oder dokumentierte Grenze; Generator (`PostgresColumnConstraintHelper`, Identity) prüfen.
  * **P2c Gemeinsamer Helfer und Leser (E8):** ein Helfer „Basistyp einer Domain-Spalte“ in `hexagon/core`; Seed, `TransferVerifier`, `ImportTypeCompatibility`, `CanonicalValueCodec`, Parquet-Writer/Reader, `CompareValueText` lösen auf oder bleiben bewusst beim Basistyp (je Stelle begründet).
  * Keine zweite Verdrahtung in `SchemaMigrateRunner`/`SchemaRollbackRunner`/`PartitionOverlayHint` (Kriterium wie in #56).
  * DoD: `make docker-check MODULES=…` je betroffenem Modul, **einmal ohne `MODULES`** (geteilte Signatur: Modell, Port, Fingerabdruck), `make solid-suppression-gate`, kein `@Suppress`.
* **P3 Tests:** Unit: Reader-Zeile mit `domain_name` (B) bzw. Kanonisierer/Vergleich (A); Vergleich pro Basistyp-Matrix (integer, text, varchar(n), decimal(p,s), uuid, boolean, biginteger); Enum-Kontrolle; Domain ohne Spalte;
  Datei gegen Datei (Basistypwechsel bleibt ein `AlterCustomType`). Integration PostgreSQL (Testcontainers): generate → anwenden → `migrate --plan-only` Exit 0 → `reverse` → `generate` (Domain-Spalte erhalten, B) → Folgeplan Exit 0;
  Altdatei (`type: integer`) Exit 0 (B′); Datei `ref_type: dd` gegen Datenbank-Spalte `integer` bleibt Exit 8 (M1); `reverse` → `validate` mit Domain-Spalte mit Spalten-`DEFAULT`, mit FK-Spalte vom Domain-Typ (E7); Cross-Dialekt: PostgreSQL-`reverse` mit Domain-Spalte → `generate` für MySQL, SQLite und MSSQL (Basistyp + `CHECK`; **E053** bei nicht abbildbarem Basistyp, **W100** bei `timestamptz`; E054 betrifft Composite, nicht Domain) in `test/cross-dialect-matrix`; Altartefakt (Rollback- und Plan-Artefakt gegen Datenbank mit Domain-Spalte) → `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`/Plan-Meldung nach Bump (E3); Domain über Domain (`ref_type` = die äußere Domain); Array-Domain; Domain aus fremdem Schema (Rückfall Basistyp); `data seed`, `data import`, `data transfer` mit Domain-Spalte; strikter `schema compare` zeigt Domain-Spalte gegen Basistyp-Spalte (E5). Fingerabdruck-Test (E3). DoD: Tests laufen wirklich (einmal absichtlich rot, `-PintegrationTests` beachten: `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"`).
* **P4 Ledger:** entfällt (E6: kein neuer Code). Falls sich das ändert: neue Codes: `ledger/warn-code-ledger-1.1.0.yaml` (W) bzw. `ledger/error-code-ledger-1.1.0.yaml` (E/R) mit `test_path`/`evidence_paths`, Zeile in `spec/ledger.md`;
  `CodeLedgerValidationTest` läuft mit. Sonst entfällt P4 (der Slice führt heute keinen Code ein).
* **P5 Doku (nach dem Code):**
  * Handbuch §3.21: Beispiel einer Domain-Spalte (`type: enum` + `ref_type`) beim Enum-Beispiel; ein Satz zum Rundlauf (`generate` → `migrate`, `reverse`) — zeitlos, ohne „bisher/neu/ab".
  * Anhang F.3 (Zeile `ref_type`): „Nur bei Typ `enum`: Verweis auf `custom_types`" um Domain erweitern; F.9 mit einem Verweis aus einer Spalte.
  * Falls B ohne Toleranz (E2): Hinweis in §3.21 („`schema reverse` neu ziehen"), analog zum `varchar(n)`-Satz.
  * `CHANGELOG.md`: **ein** Eintrag mit Befund und Fix (Version 1.11.1, Betroffene: PostgreSQL mit Domain-Spalte; Folge: Exit 8 bei `migrate`; B: neue `reverse`-Form; Fingerabdruck). **In `docs/user/` keine Chronik**, kein Troubleshooting-Eintrag für den alten Zustand.
  * `make docs-check` vor dem Commit.

## Akzeptanzkriterien

Zu jedem Kriterium der erwartete **Rot-Zustand auf `main`** (der Test muss vor dem Fix scheitern; einmal absichtlich rot belegen).

1. Datei `d: { type: enum, ref_type: dd }` mit `kind: domain` über jeden Basistyp (integer, text, varchar(n), decimal(p,s), uuid, boolean, biginteger): `generate` → anwenden → `schema migrate --plan-only` Exit 0 ohne Operation; `schema compare` Datei ↔ Datenbank `IDENTICAL`.
   *Rot auf `main`:* Exit 8 / `DIFFERENT` (P0-Tabelle, alle sieben Basistypen gemessen).
2. Echte Änderungen planen weiter: Verweis auf eine andere Domain, Basistypwechsel der Domain, **Datei `ref_type: dd` gegen Datenbank-Spalte `integer`** (Exit 8, `AlterColumnType`); Datei `integer` gegen Datenbank-Spalte vom Typ `dd` plant (Toleranz E2) nichts, strikter `schema compare` zeigt sie (E5).
   *Rot auf `main`:* der Fall `ref_type: dd` gegen `integer` ist heute Exit 8 (grün, bleibt); die Toleranz-Richtung ist heute Exit 0 (grün, bleibt) — beide Fälle sind **Regressionswächter**, nicht Rot-Fälle; ein symmetrisches Verdrahten ließe den ersten rot werden.
3. Kontrollen unverändert: Enum-Spalte Exit 0; Domain ohne Spalte Exit 0; `varchar`-Domain selbst blockiert wie dokumentiert (`POSTGRES_RENDER_SKIP`). *Wächter, grün auf `main`.*
4. `schema reverse` einer Domain-Spalte schreibt `type: enum` + `ref_type` (äußere Domain; Domain über Domain nach E9; Array-Domain; Rückfall auf Basistyp bei fremdem Schema/Erweiterung); `generate` aus dieser Datei erzeugt die Domain-Spalte; `migrate --plan-only` Exit 0; eine ältere `reverse`-Datei bleibt bei Exit 0.
   *Rot auf `main`:* `reverse` schreibt `type: integer`, `generate` erzeugt `"d" INTEGER`.
5. Post-Compare: `migrate --execute` einer unabhängigen Änderung (neue Spalte) in derselben Tabelle gegen eine Datei mit `ref_type: dd` **und** gegen eine Altdatei (Basistyp) meldet keinen `POST_EXECUTE_DRIFT` an der Domain-Spalte.
   *Rot auf `main`:* mit `ref_type: dd` blockiert schon der Plan (Exit 8); der Test ist vor dem Fix rot, mit der Altdatei grün (Wächter, hält die Toleranz im Post-Compare, E3).
6. Fingerabdruck (E3): Hash je Seite vorher/nachher dokumentiert (Datei mit/ohne Domain-Spalte, Datenbank mit Domain-Spalte, Altdatei); Eingänge **ohne** Domain-Spalte ändern sich nicht; bei Wechsel `v25`, ADR 0067, `MigrationFingerprintTest` und Altartefakt-Test (Rollback- und Plan-Artefakt) mit der erwarteten Meldung.
   *Rot auf `main`:* Hash der Datenbank-Seite einer Domain-Spalte ist `integer`; der Test fixiert den neuen Wert.
7. Validierung (E7): `reverse` → `validate` einer Domain-Spalte mit Spalten-`DEFAULT` und einer FK-Spalte vom Domain-Typ besteht (oder die Grenze ist dokumentiert und getestet). *Rot nach reinem Reader-Umbau:* E009/E017 (gemessen mit handgeschriebenen Dateien; `reverse` schreibt heute `integer`, daher grün — der Test wird erst mit dem Reader rot und ist die Wache gegen den Bruch).
8. Leser (E8): je Lesestelle (Seed, Transfer-Verifikation, Import-Preflight, Codec, Parquet, `CompareValueText`) ein Test mit einer `integer`-Domain-Spalte: gleiches Ergebnis wie mit einer `integer`-Spalte (Seed erzeugt Zahlen; `data transfer` PostgreSQL → PostgreSQL und → MySQL verifiziert die Spalte; Import in die Domain-Spalte besteht den Preflight; Parquet-Spalte bleibt numerisch). *Rot nach reinem Reader-Umbau* (nicht auf `main`, wo die `reverse`-Datei `integer` trägt); die Messung der Ist-Lage steht in P1.
9. Cross-Dialekt: PostgreSQL-`reverse` mit Domain-Spalte → `generate` für MySQL, SQLite, MSSQL ergibt Basistyp + `CHECK` an der Spalte (E053/W100 wie dokumentiert); `test/cross-dialect-matrix` grün.
10. Spec (Schritt 0) und Handbuch (P5) stimmen mit dem Verhalten überein; `docs/user/` beschreibt nur den Ist-Zustand.
11. Gates: `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`, `make solid-suppression-gate`, `make docker-check` je Modul und einmal ohne `MODULES`, Integrationslauf PostgreSQL mit `-PintegrationTests`.

## Verifikation

* Messskript wie im Issue gegen ein `make docker-build`-Image (nicht das veröffentlichte); Ergebnis je Fall in die P0-Tabelle.
* Der Testfall je Kriterium ist als Einzelzeile benennbar (Klasse, Methode) und im ImpPlan nachzutragen, sobald P3 beginnt.
* Done-ness wird gegen Lastenheft, Spec (`spec/neutral-model-spec.md`, `spec/schema-reference.md`, `spec/ddl-generation-rules.md`) und ADR begründet, nicht gegen diesen Plan.
