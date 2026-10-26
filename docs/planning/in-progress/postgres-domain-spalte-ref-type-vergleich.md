# PostgreSQL: eine Spalte vom Domain-Typ (`type: enum` + `ref_type`) plant bei jedem Lauf `AlterColumnType` und blockiert mit Exit 8 (Issue #68)

> **Status: Entschieden (Eigner, 2026-10-26): Option B′; P0-Messung vollständig, Umsetzung offen.** Es gibt noch keinen Implementierungs-Commit. Der Plan liegt auf Anweisung des Eigners
> schon in `in-progress/` (ADR 0004 sieht den Move erst mit dem ersten Implementierungs-Commit vor).
> **Vorbedingung:** E1–E6 entschieden (siehe „Entscheidung“); offen bleibt nur die Fingerabdruck-Prüfung in P1 (E3). **Reihenfolge:** **Schritt 0** Spec-Prosa (vor dem Code) → P1 → P2–P4 → P5 Doku.
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
| Datei `type: integer` gegen Datenbank mit Domain-Spalte | Exit 0 (Domain-Spalte und Basistyp-Spalte gelten als gleich) | `schema migrate`: Exit 0 (Toleranz E2); strikter `schema compare`: Unterschied sichtbar (E5) |

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

* **Reproduziert** auf dem Stand von `main`: alle gemessenen Fälle wie im Issue (1, 2, 2v, 3b, 3d; 1b, 3a, 3c gleich nach dem Code, nicht einzeln wiederholt). Die Kontrollen verhalten sich wie im Issue.
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

* `data seed` (`ColumnValueGenerator.randomEnumValue`): eine `Enum` **ohne `values`** wirft `UnsupportedSeedTypeException`. Eine `reverse`-Datei mit `type: enum` + `ref_type` an einer Domain-Spalte erzeugte heute `integer` und seedet; nach B′ würde sie am Seed scheitern oder die Spalte nullen (`TableRowSeeder`). **Regressrisiko, gleiche Lücke wie bei einer handgeschriebenen Datei heute.** Abhilfe: der Seed löst `ref_type` auf eine Domain über deren Basistyp auf (eigenes Arbeitspaket P2c) — oder die Zeile wird bewusst als Grenze dokumentiert (Entscheidung für den Eigner, siehe Rückmeldung).
* `data import` (`ImportTypeCompatibility.isEnumCompatible`): wertet `refType` gegen den JDBC-`sqlTypeName` aus; für eine Domain-Spalte meldet der PostgreSQL-Treiber voraussichtlich den Basistyp (INTEGER) — dann „inkompatibel". Nicht gemessen; P3 misst Import einer Datei mit Domain-`ref_type` in die Domain-Spalte.
* `data transfer` liest beide Schemas **zur Laufzeit** über den Reader (`DataTransferRunner`, `schemaReader().read`): die Quell- und Ziel-Typen der Domain-Spalte werden nach B′ `Enum(refType)`; Verifikation (`TransferVerifier`) fasst `Enum` wie Text. Nicht gemessen; P3.
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
    2. **B′ — Toleranz für Altdateien:** der Vergleich löst die **Datenbank-Seite** über den Basistyp auf, wo die Datei-Seite die Spalte **nicht** als `ref_type` führt (Gegenstück zu A, gleicher Mechanismus
       `DomainColumnTypes`, in die andere Richtung). Altdatei bleibt bei Exit 0; Neudatei ist streng. Der Preis ist die blinde Stelle von A, begrenzt auf „Datei hat Basistyp, Datenbank hat Domain" (ein Wechsel Basistyp → Domain
       bleibt ungeplant; Domain → Basistyp ist sichtbar).
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
* **E3 — Fingerabdruck und ADR:** ändert sich der Abdruck des Ist-Zustands (B) oder der Datei-Seite (A)? Dann `schema-fingerprint-v25` mit neuem ADR (nächste freie Nummer 0067; ADR 0061 Entscheidung 3 und 4, 0065 und 0066 bleiben unberührt,
  accepted-ADRs ändert niemand) — oder der Abdruck faltet die Domain-Spalte auf den Basistyp (kein Tag-Bump, aber Hash der Datenbank bleibt gleich; das widerspricht dem Zielbild von B für den Vergleich nicht, weil der Abdruck
  ohnehin projiziert). Empfehlung: Faltung im Abdruck prüfen (P1), ADR nur wenn sich ein Hash für bestehende Eingaben ändert.
* **E4 — Domain außerhalb des gelesenen Schemas (B):** Verweis mit Schemaqualifizierung in `ref_type` oder Basistyp-Rückfall mit Hinweis? Empfehlung: Rückfall auf Basistyp, solange der Reader die Domain nicht in `custom_types` führt.
* **E5 — Sichtbarkeit der blinden Stelle (A, B′):** Soll `schema compare` (strikt) den Unterschied „Domain-Spalte gegen Basistyp-Spalte" zeigen, auch wenn `schema migrate` ihn nicht plant? Empfehlung: ja (strikt bleibt streng).
* **E6 — Code oder Hinweis:** braucht der Rundlauf eine eigene Meldung (neuer `R`-/`W`-Code), etwa wenn der Reader eine Domain-Spalte auf den Basistyp zurückfallen lässt? Empfehlung: nein, solange der Rückfall selten ist; sonst Ledger-Pflicht.

## Abgrenzung

* Nicht: `ALTER DOMAIN`, `varchar`-/Array-Grenze der Domain selbst (`AlterCustomType`, `POSTGRES_RENDER_SKIP`) — bekannt, Handbuch §3.21, #52/#58/#59.
* Nicht: SQLite (kein Typ-Objekt; Vergleich über #56 gelöst), MySQL, MSSQL, Oracle (keine Domain als Spaltentyp).
* Nicht: Kollation/Default/`NOT NULL` der Domain (#59); die Spalte behält ihr `required` aus `attnotnull`.

## Arbeitspakete

* **Schritt 0 Spec (vor dem Code, nur Zielbild, keine Links auf Pläne/ADRs — `.d-check.yml` matrix; `spec/` darf Ungebautes beschreiben):**
  * `spec/schema-reference.md` Abschnitt „Domain": „Verwendung in Spalten: `type: enum` + `ref_type: <domain>`" (heute nur beim Enum); je Dialekt, was daraus wird (PostgreSQL: Spalte vom Domain-Typ; MySQL, SQLite, MSSQL: Basistyp + `CHECK`).
  * `spec/ddl-generation-rules.md` (Domain-Abschnitt um Z. 1296ff.): Reverse und Vergleich der Domain-Spalte auf PostgreSQL — zeitlos („`schema reverse` schreibt eine Spalte vom Domain-Typ als `type: enum` + `ref_type`; `schema migrate`
    plant für eine unveränderte Domain-Spalte nichts").
  * `spec/neutral-model-spec.md` (§13.2/`ref_type` Zeile 221): Verweisziel ist `enum` **oder** `domain`; ggf. `spec/cli-spec.md` (`schema reverse`), falls dort die Spalten-Form genannt ist.
* **P0 Messung** (Tabelle oben; die offenen Messungen vor E1/E3 abschließen). DoD: Tabelle und Befund im Slice, Messskript reproduzierbar im Stand `main`.
* **P1 Folgenabschätzung (E1–E6 entschieden):** die Leser des Spaltentyps (Export/Transfer/Import/Profiling/Manifest) und die Cross-Dialekt-Matrix gegenprüfen; Fingerabdruck-Prüfung: derselbe Eingang,
  Hash vorher/nachher (Datei-Seite und Datenbank-Seite je Option). DoD: Entscheidung dokumentiert; ADR (E3) geschrieben, falls ein Hash sich ändert (`make doc-immutable` beachten: Frontmatter-Kern akzeptierter ADRs nicht anfassen).
* **P2 Umsetzung** (je Option):
  * **A:** `PostgresNeutralTypeCanonicalizer.canonicalize(type, customTypes)` löst `Enum(refType)` auf eine Domain über deren Basistyp (`PostgresDomainBaseType`); `TypeCanonicalizerWiring.registryDomainAwareCanonicalizer`
    nicht mehr an `resolvesRefTypeVocabulary` hängen, sondern an eine eigene Fähigkeit (`DialectCapabilities`, Muster SQLite); KDoc „Fuer PostgreSQL waere ein Aufloesen falsch" berichtigen.
  * **B′ (entschieden):** `PostgresTableMetadataQueries.listColumns` um `domain_schema`, `domain_name`; `PostgresTypeMapping.ColumnInput`/`mapColumn` um die Domain; `PostgresSchemaStructureReaders` setzt den Verweis nur, wenn die Domain in `custom_types`
    steht (E4); `domain_name` hat Vorrang vor `udt_name` (Domain über Domain, Array-Domain; fehlt das Feld in einer Zeile, keine Domain); `DomainColumnTypes` in Gegenrichtung (Datenbank-Seite) nach dem Muster `TargetProjection.typeWithCustomTypes`.
  * **P2c Leser:** `data seed` löst `ref_type` auf eine Domain über den Basistyp auf (oder bewusste Grenze, siehe offene Rückfrage); `data import`/`data transfer` nach Messung (P3).
  * Keine zweite Verdrahtung in `SchemaMigrateRunner`/`SchemaRollbackRunner`/`PartitionOverlayHint` (Kriterium wie in #56).
  * DoD: `make docker-check MODULES=…` je betroffenem Modul, **einmal ohne `MODULES`** (geteilte Signatur: Modell, Port, Fingerabdruck), `make solid-suppression-gate`, kein `@Suppress`.
* **P3 Tests:** Unit: Reader-Zeile mit `domain_name` (B) bzw. Kanonisierer/Vergleich (A); Vergleich pro Basistyp-Matrix (integer, text, varchar(n), decimal(p,s), uuid, boolean, biginteger); Enum-Kontrolle; Domain ohne Spalte;
  Datei gegen Datei (Basistypwechsel bleibt ein `AlterCustomType`). Integration PostgreSQL (Testcontainers): generate → anwenden → `migrate --plan-only` Exit 0 → `reverse` → `generate` (Domain-Spalte erhalten, B) → Folgeplan Exit 0;
  Altdatei (`type: integer`) Exit 0 (B′); Domain über Domain (`ref_type` = die äußere Domain); Array-Domain; Domain aus fremdem Schema (Rückfall Basistyp); `data seed`, `data import`, `data transfer` mit Domain-Spalte; strikter `schema compare` zeigt Domain-Spalte gegen Basistyp-Spalte (E5). Fingerabdruck-Test (E3). DoD: Tests laufen wirklich (einmal absichtlich rot, `-PintegrationTests` beachten: `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"`).
* **P4 Ledger:** entfällt (E6: kein neuer Code). Falls sich das ändert: neue Codes: `ledger/warn-code-ledger-1.1.0.yaml` (W) bzw. `ledger/error-code-ledger-1.1.0.yaml` (E/R) mit `test_path`/`evidence_paths`, Zeile in `spec/ledger.md`;
  `CodeLedgerValidationTest` läuft mit. Sonst entfällt P4 (der Slice führt heute keinen Code ein).
* **P5 Doku (nach dem Code):**
  * Handbuch §3.21: Beispiel einer Domain-Spalte (`type: enum` + `ref_type`) beim Enum-Beispiel; ein Satz zum Rundlauf (`generate` → `migrate`, `reverse`) — zeitlos, ohne „bisher/neu/ab".
  * Anhang F.3 (Zeile `ref_type`): „Nur bei Typ `enum`: Verweis auf `custom_types`" um Domain erweitern; F.9 mit einem Verweis aus einer Spalte.
  * Falls B ohne Toleranz (E2): Hinweis in §3.21 („`schema reverse` neu ziehen"), analog zum `varchar(n)`-Satz.
  * `CHANGELOG.md`: **ein** Eintrag mit Befund und Fix (Version 1.11.1, Betroffene: PostgreSQL mit Domain-Spalte; Folge: Exit 8 bei `migrate`; B: neue `reverse`-Form; Fingerabdruck). **In `docs/user/` keine Chronik**, kein Troubleshooting-Eintrag für den alten Zustand.
  * `make docs-check` vor dem Commit.

## Akzeptanzkriterien

1. Datei `d: { type: enum, ref_type: dd }` mit `kind: domain` über jeden gemessenen Basistyp (integer, text, varchar(n), decimal(p,s), uuid, boolean, biginteger): `generate` → anwenden → `schema migrate --plan-only` gegen die Datenbank ergibt Exit 0
   ohne Operation; `schema compare` Datei ↔ Datenbank ergibt `IDENTICAL`.
2. Eine **echte** Änderung an der Domain-Spalte (Verweis auf eine andere Domain; Basistypwechsel der Domain) plant weiter (Exit 8 mit `AlterCustomType` bzw. `AlterColumnType`), nicht still nichts.
3. Kontrollen unverändert: Enum-Spalte Exit 0; Domain ohne Spalte Exit 0; `varchar`-Domain selbst blockiert wie dokumentiert (`POSTGRES_RENDER_SKIP`).
4. **B′:** `schema reverse` einer Domain-Spalte schreibt `type: enum` + `ref_type`; `generate` aus dieser Datei erzeugt die Domain-Spalte; `migrate --plan-only` Exit 0. **B′:** eine ältere `reverse`-Datei (Basistyp an der Domain-Spalte) bleibt bei Exit 0.
5. Der Post-Compare nach `migrate --execute` einer unabhängigen Änderung in derselben Tabelle meldet keinen Drift an der Domain-Spalte.
6. Fingerabdruck (E3): Hash vorher/nachher je Eingang dokumentiert; kein unbeabsichtigter Wechsel für Eingänge ohne Domain-Spalte; bei Wechsel Tag, ADR und Test (`MigrationFingerprintTest`).
7. Cross-Dialekt (B): `test/cross-dialect-matrix` grün; Export/Transfer/Import/Profiling mit Domain-Spalte gemessen, kein Regress.
8. Spec (Schritt 0) und Handbuch (P5) stimmen mit dem Verhalten überein; `docs/user/` beschreibt nur den Ist-Zustand.
9. Gates: `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`, `make solid-suppression-gate`, `make docker-check` je Modul und einmal ohne `MODULES`, Integrationslauf PostgreSQL mit `-PintegrationTests`.

## Verifikation

* Messskript wie im Issue gegen ein `make docker-build`-Image (nicht das veröffentlichte); Ergebnis je Fall in die P0-Tabelle.
* Done-ness wird gegen Lastenheft, Spec (`spec/neutral-model-spec.md`, `spec/schema-reference.md`, `spec/ddl-generation-rules.md`) und ADR begründet, nicht gegen diesen Plan.
