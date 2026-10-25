# PostgreSQL: Domain in Autorenform gegen Katalogform (Issue #52)

> **Status:** In Umsetzung (2026-10-25); Draft mit Scope nach drei Reviews. Aus Issue #52, abgegrenzt von
> #51 (`custom-type-fields-leer-gegen-null.md`, Nachtrag „Offen“): dort ging es um `fields`,
> `ordinal` und `description`; die Domain-Abweichung blieb offen. Ein Verweis dieses Plans
> auf den Vorgänger bleibt ein Dateiname; wandert der Vorgänger nach `done/`, ist der
> Nachtrag dort auf „erledigt durch #52“ nachzuführen.
> **Vorbedingung:** keine. Aktiviert mit dem Implementierungs-Commit.

## Ziel

Eine Domain, die Datei und PostgreSQL-Datenbank gleich ausdrücken, erzeugt keine
`AlterCustomType`-Operation, auch wenn die Datei sie anders schreibt als der Katalog.
`schema migrate` führt dann eine rein additive Änderung aus; eine **echte** Änderung der
Domain blockiert weiter (Exit 8, `AlterCustomType` ist für PostgreSQL nicht gerendert).

Das Ziel gilt **ohne** Herkunftsdatei und **ohne** Sandbox. Greift eine Richtung nur mit einem
dieser Opt-ins, ist das im Plan so zu benennen und im Handbuch als Grenze zu führen.

## Problem

Gemessen mit einem Integrationstest gegen PostgreSQL (`CREATE DOMAIN pos AS integer CHECK
(VALUE > 0)`; Soll in Autorenform `base_type: integer`, `check: "VALUE > 0"`, sonst unverändert):

| Feld | Reader (Katalog) | Datei (Autor) |
| --- | --- | --- |
| `baseType` | `integer` | `integer` |
| `precision` / `scale` | `32` / `0` | `null` / `null` |
| `check` | `(VALUE > 0)` | `VALUE > 0` |

[`SchemaComparator.compareCustomType`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt)
vergleicht `baseType`, `precision`, `scale` und `check` strikt (`valueChangeOrNull`), ohne Projektion
oder Folding. Die Messung steht im Nachtrag von `custom-type-fields-leer-gegen-null.md`.

### Ursachen

1. **`precision`/`scale` ohne Bedeutung.** Der Reader
   ([`PostgresTypeMetadataQueries.listDomainTypes`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMetadataQueries.kt))
   liest `information_schema.domains.numeric_precision/_scale` und übernimmt sie ungefiltert. Der
   Katalog füllt sie auch für `integer` (32/0), `bigint` (64/0), `smallint` (16/0) und die
   Gleitkommatypen (`real` 24, `double precision` 53; aus Kenntnis, zu messen). Eine Datei schreibt
   sie nur für `decimal(p, s)`. Der Renderer
   ([`PostgresTypeSequenceDdlSupport`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeSequenceDdlSupport.kt))
   nutzt sie nur für `decimal`/`numeric` und rohe Typnamen; für die übrigen ignoriert er sie.
2. **`check` in Katalogform.** `pg_get_constraintdef` liefert die vom Server umgeschriebene Form
   (`CHECK ((VALUE > 0))`); `normalizeDomainCheck`
   ([`PostgresSchemaStructureReaders`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt))
   entfernt `CHECK` und **eine** Klammerebene. Der Server schreibt weiter um: `BETWEEN` wird zu
   `(VALUE >= 1) AND (VALUE <= 10)`, `IN (…)` zu `= ANY (ARRAY[…::text])`, Literale bekommen Casts.
3. **`baseType` ist eine Neutralform, die Datei nicht.** `PostgresTypeMapping.mapParamType` liefert
   `biginteger`, `text` (auch für `varchar`/`bpchar`), `decimal`, `float` (auch `float4`/`float8`),
   `boolean`. Der Renderer akzeptiert viele Synonyme (`bigint`, `int8`, `int`, `numeric`, `bool`,
   `real`, …) und rohe Typstrings (`VARCHAR(254)`). Schreibt der Autor `bigint` oder `numeric`, liefert
   der Reader `biginteger` bzw. `decimal`: ein `AlterCustomType`, obwohl beide dasselbe meinen.
4. **Falsch-negativ: die Länge geht verloren.** Der Reader liest `character_maximum_length` nicht;
   `varchar(50)` wird `text`. Ändert jemand die Länge, sieht der Vergleich keinen Unterschied. Das
   widerspricht „eine echte Änderung blockiert weiter“; ob es in dieses Issue gehört, entscheidet P1.
5. **Reader-Bug, kein Autorenform-Thema: mehrere CHECKs je Domain.** `listDomainTypes` verbindet
   `pg_constraint` ohne Aggregation (zwei CHECKs ergeben zwei Zeilen), `result[name] = …` behält die
   letzte; ein CHECK geht still verloren. Eigener Befund, in P0 zu messen und gegebenenfalls als
   eigenes Issue abzutrennen.

**Modelllücke, keine Diff-Ursache:** `CustomTypeDefinition` kennt weder `DEFAULT` noch `NOT NULL` noch
Kollation einer Domain (der Reader selektiert `domain_default`, verwendet es aber nicht). Eine Änderung
daran ist im Diff unsichtbar. Das ist Grenze, nicht Scope.

## Richtung (nach P0 zu entscheiden, je Ursache)

**Ursache 1.**

* **A — Reader:** `precision`/`scale` nur für `numeric`/`decimal`. Klein und lokal. Ändert, was
  `schema reverse` schreibt (die Datei verliert `precision: 32`), also einen Reverse-Vertrag; die
  erzeugte DDL bleibt gleich. Ein Golden oder Test, der `precision: 32` an einer Domain erwartet, ist
  bei der Durchsicht nicht aufgefallen (`full-featured.yaml` nutzt `DECIMAL(10,2)`). **Übergang:** eine
  Datei aus einem früheren Reverse trägt `precision: 32` und gälte danach als geändert; dafür braucht es
  einen Test „alte Reverse-Datei gegen neuen Reader“ und eine Handbuchzeile.
* **B — Vergleich:** `precision`/`scale` nur bei numerischem `baseType` zählen. Wirkt in jedem
  Vergleich, auch Datei gegen Datei (jemand schreibt `integer` mit `precision: 32` bewusst), und braucht
  die Synonymtabelle (`numeric`/`decimal`/`NUMERIC(10,2)`). A und B sind nicht gleichwertig; A allein
  hilft nicht, wenn die Datei `precision: 32` selbst trägt. Auswahlregel: A, wenn nur der Reader ein
  Artefakt erzeugt; B zusätzlich, wenn Dateien es tragen dürfen.

**Ursache 3 (`baseType`):** Normalisierung beider Seiten über dieselbe Synonymtabelle wie der Renderer
(Comparator oder gemeinsame Funktion im Core). Entscheidet über die Behandlung von `varchar(n)` mit
(Ursache 4).

**Ursache 2 (`check`).**

* **C — Falten über die vorhandene Infrastruktur.** `RawTextFolding` samt `RawTextAuthorship`
  (Herkunft) und `RawTextServerForm` (Serverform) kennt Constraints, Index-Prädikate, Generierung und
  Sicht-Rümpfe, **nicht** `CustomTypeDefinition.check`. Ein neuer `objectType` (z. B. `custom_type`,
  Feld `check`) müsste an drei Stellen eingeführt werden (`RawTextProvenance.entriesOf`,
  Overlay-/Preflight-Validierung, `RawTextFolding`) und ändert damit ein persistiertes Overlay-Format
  — deutlich mehr als „Faltung ergänzen“. Die Quellen fehlen im Standardfall: die Herkunft setzt nur
  `OverlayRawTextAuthorship` aus einem früheren Lauf (sonst `null`, also konservativ „geändert“), die
  Serverform nur die Sandbox (`migrate.raw_sql_sandbox`, standardmäßig aus). Der **erste** Lauf gegen
  eine bestehende Datenbank bliebe ein `AlterCustomType`. `ConstraintDiffContract.canonicallyEqual`
  verlangt außerdem Spaltentypen für Casts; bei `VALUE` gibt es keine Spalte, der Domain-Basistyp müsste
  als Typkontext dienen.
* **D — Sandbox:** PostgreSQL legt die Autorenform in einem Wegwerf-Schema an und liest sie zurück
  (`PostgresRawTextSandbox` wendet das Soll über den Generator an, auch `CREATE DOMAIN`). Trägt
  technisch, gilt aber nur für Live-Ziele mit Opt-in, nicht für `file:`; das Ergebnis hängt damit vom
  Zieltyp ab, was [ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md)
  („eine Semantik, Herkunft kein Unterschied“) entgegensteht.
* **E — Lokale Kanonisierung (`canonicalizeRawExpressions`)** im Migrate-Pfad: das lehnt
  [ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md) (Entscheidung 1)
  ausdrücklich ab. Ein Folge-ADR wäre eine **Teilablösung** von 0056 (Statuszeile `superseded by`, Kern
  eingefroren), keine Anknüpfung; nächste freie Nummer 0061.
* **F — Grenze dokumentieren:** der `check` bleibt strikt, das Handbuch nennt die verbleibenden
  Ausdrücke. Der billigste Weg und der einzige ohne Vertragsänderung.

**ADR-Frage je Richtung:** A (Reverse-Vertrag) und D (Sandbox-Verhalten) berühren Verträge und sind auf
ADR-Bedarf zu prüfen, C/E sicher. Anknüpfungspunkte: 0056 und 0057 (0055 betrifft den Enum-Wertevorrat,
nicht rohe Ausdrücke).

**Compare-Seite:** `schema compare` faltet mit `canonicalizeRawExpressions=true` (0056/0057), aber
`compareCustomType` ruft `RawTextFolding` nie auf; derselbe Fehlalarm tritt dort auf. Bewusst
ausnehmen oder aufnehmen, in P1 festlegen.

Nicht-Ziel: `AlterCustomType` für PostgreSQL rendern (`ALTER DOMAIN`); Composite und Enum (erledigt mit
#51); Domain als Spaltentyp-Wechsel (anderes Diff-Niveau, Spaltentyp).

## Phasen

* **P0 Messung.** Das Soll entsteht aus einem **handgebauten** `CustomTypeDefinition` in Autorenform
  (der Datei-Parser liegt nicht auf dem Klassenpfad des Integrationsmoduls; die Form entspricht
  `parseCustomTypes`); **mindestens ein Fall** schickt zusätzlich eine echte YAML-Datei durch den
  Parser (Test im CLI-Modul oder Parser-Roundtrip), damit die handgebaute Form nicht nur das spiegelt,
  was der Test-Autor denkt. Feste Fallliste, je Zeile (Autorenform, Reader-Ausgabe, Ergebnis
  gleich/`AlterCustomType`):
  `integer`, `bigint` (auch als `int8`), `smallint` mit einfachem `CHECK`; `real`/`double precision`;
  `decimal(10,2)` und `numeric`; `text`, `varchar(50)`, `bpchar(5)`; `CHECK` mit `BETWEEN`, `IN (…)`,
  Literal mit Cast, `AND`/`OR`; **zwei** CHECKs (Ursache 5); Domain über Domain; Array-Domain;
  Domain in einem anderen Schema als `public`. **P0 endet**, wenn jede Zeile gemessen ist; weitere
  Ausdrucksformen gehen in die dokumentierte Grenze.
* **P1 Entscheidung:** Richtung je Ursache nach der Messtabelle; Auswahlregel A/B, C–F; ADR-Frage;
  Compare-Seite; Übergang für Bestandsdateien; Aufwandsschätzung für einen neuen `objectType`
  (nur C). Entscheiden: der Autor dieses Issues zusammen mit dem Reviewer; ohne Einigung gilt F
  (Grenze) für `check` und A für `precision`/`scale`.
* **P2 Umsetzung** nach P1; Unit-Tests (Reader bzw. Comparator), Integrationstest grün, zweiter
  Lauf `operationsTotal=0`, Rückweg `schema rollback`.
* **P3 Doku:** Anwenderhandbuch §3.21 (Absatz zur Domain-Grenze, `anwenderhandbuch.md` um Zeile
  2590) nachführen oder streichen — je nach P1 bleibt die Grenze für `check` teilweise stehen und nennt
  dann genau die verbleibenden Ausdrücke; `CHANGELOG.md`; `spec/ddl-generation-rules.md` §6.3
  (Domain-Typen) und `spec/schema-reference.md` (Abschnitt „Domain“, `precision`) prüfen; bei A zusätzlich
  die Reverse-Dokumentation; im Plan `custom-type-fields-leer-gegen-null.md` den Nachtrag
  „Offen“ auf „erledigt durch #52“ stellen.

## Nicht-Scope

* `AlterCustomType` auf PostgreSQL rendern (`ALTER DOMAIN`).
* Andere Dialekte: nur PostgreSQL liest Typ-Objekte (zu belegen: MySQL, MSSQL, Oracle und SQLite
  setzen kein `customTypes` im Reader).
* Rohe Ausdrücke außerhalb von `CustomTypeDefinition.check`.
* `DEFAULT`/`NOT NULL`/Kollation einer Domain (Modelllücke, siehe oben).

## Akzeptanz

* Jede in P0 als Fehler gemessene Zeile hat einen Test, der **ohne** die Änderung an der erwarteten
  Stelle fehlschlägt (`AlterCustomType`, Exit 8, Diff auf das benannte Feld) und mit ihr grün wird.
  Die neuen Tests werden einmal mit absichtlich falscher Zusicherung rot gesehen.
* Echte Änderungen blockieren weiter: anderer `CHECK` (`> 0` gegen `>= 0`), `decimal(10,2)` →
  `decimal(12,2)`, `integer` → `bigint`; bei einer Faltung (C/D/E) zusätzlich ein Negativfall, in dem
  ein ähnlich aussehender Ausdruck etwas anderes bedeutet.
* Zweiter Lauf `operationsTotal=0`, kein `POST_EXECUTE_DRIFT`; Rückweg über `schema rollback` Exit 0.
* Bei Richtung A: ein Test „alte Reverse-Datei (`precision: 32`) gegen neuen Reader“ und ein
  `schema reverse`-Test für die geänderte Ausgabe.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne
  `MODULES=`, `make integration INTEGRATION_TASKS=":test:integration-postgresql:test"`,
  `make solid-suppression-gate`, `make docs-check`; bei einem ADR `make doc-immutable
  RANGE=origin/main..HEAD`.

## Nachtrag P0/P1/P2 (2026-10-25)

**P0 (Messung, ungefixt, 20 Fälle; Soll in Autorenform, Reader-Ausgabe, Diff-Felder):**

| Fall | Reader | Diff vorher |
| --- | --- | --- |
| `integer`/`bigint`/`smallint` mit `CHECK (VALUE > 0)` | `…/32/0` bzw. `64/0`, `16/0`; `(VALUE > 0)` | precision, scale, check (bei `bigint` auch baseType) |
| `real`, `double precision` | `float/24/null`, `float/53/null`; `(VALUE > (0)::double precision)` | precision, check |
| `decimal(10,2)` / `numeric` | `decimal/10/2`; `(VALUE >= (0)::numeric)` | check |
| `text` | `(length(VALUE) > 0)` | check |
| `varchar(50)` | `text`; `(length((VALUE)::text) > 0)` | check (als `varchar` auch baseType) |
| `BETWEEN` / `IN` / `AND` / `OR` | `((VALUE >= 1) AND (VALUE <= 10))` / `= ANY (ARRAY['a'::text, …])` | check |
| **zwei** CHECKs | nur der letzte (`(VALUE < 10)`) | check — **Reader-Bug** |
| Domain über Domain | `b` | gleich |
| Array-Domain | `_int4` | baseType |

**P1 (Entscheidung):** Ursache 1 → **Richtung A** (Reader: `precision`/`scale` nur für `decimal`;
kein Vergleich in B). Ursache 3 → Neutralform des Basistyps im Vergleich **und** im Fingerabdruck
(`CustomTypeBaseType`). Ursache 2 → **Reader-Normalform** statt Falten im Vergleich
(`PostgresDomainCheckForm`): überflüssige äußere Klammern und der Cast an Zahl-/Textliteralen
entfallen — das ist keine Normalisierung im Migrate-Vergleich und berührt ADR 0056 nicht (ADR 0061, siehe unten); `BETWEEN`/`IN`/Cast an `VALUE`/`AND`-Klammern bleiben dokumentierte Grenze (**F**), C/D/E sind
verworfen (fehlende Herkunft/Serverform im Standardfall, ADR 0056/0057). Compare-Seite: unverändert,
`compareCustomType` ruft `RawTextFolding` weiter nicht auf; der Fehlalarm des Checks entfällt dort mit
der Reader-Normalform.

**P2:** Fingerabdruck **`schema-fingerprint-v19`** (der Basistyp einer Domain geht in Neutralform ein;
die Reader-Änderung ändert `precision`/`scale`): ein Rollback-Artefakt einer früheren Version ist neu zu
erzeugen. Integrationstest (`PostgresCustomTypeFileFormMigrateIntegrationTest`, Domain-Teil): zehn Fälle
in Autorenform sind gleich (zweiter Lauf `operationsTotal=0`), drei Grenzen blockieren weiter, drei echte
Änderungen (anderer `CHECK`, andere Genauigkeit, anderer Basistyp) blockieren weiter. Ohne die Änderung
rot (P0-Tabelle, und `POST_EXECUTE_DRIFT` am Fingerabdruck für `bigint`/`real`/`numeric`/`varchar`).

**Offen / abgetrennt:** Mehrfach-CHECK (Reader behält den letzten) und die verlorene `varchar`-Länge sind
eigene Befunde; Array-Domain (`_int4` gegen `integer[]`) ist Grenze; ein Test „alte Reverse-Datei mit
`precision: 32` gegen neuen Reader“ fehlt (so eine Datei gilt nach der Änderung als geändert, bis die
Zeile entfällt). Der YAML-Parser-Roundtrip ist durch `SchemaNodeParserTest` (Form der Datei-Domain)
abgedeckt, nicht durch einen Integrationsfall mit echter Datei.

**ADR.** ADR 0056 verbietet die lokale Normalisierung roher SQL-Texte im **Vergleich**
von `schema migrate` (Entscheidung 1), weil dafür ein Parser nötig wäre, der Kommentare, Quoting
und Literale beherrscht. `PostgresDomainCheckForm` ist kein Vergleichsschritt, sondern die
**Reverse-Normalform des Readers**: derselbe Platz, an dem `normalizeDomainCheck` schon `CHECK` und
eine Klammerebene entfernte. Sie bleibt absichtlich klein (Standardliterale, kein Cast mit
Typmodifikator oder Array-Klammer, nichts bei Rechenoperatoren, keine negativen Zahlen) und
vergleicht nichts. Sichtbar ändert sich der `schema reverse`-Vertrag: der Domain-`CHECK` steht in
Autorenform, `precision`/`scale` entfallen bei Nicht-`decimal` — beides in Handbuch und CHANGELOG
genannt, `spec/schema-reference.md` sagt „`precision`/`scale` nur bei decimal“ schon so. Der
Fingerabdruck-Bump auf v19 braucht für sich keinen ADR (v16/v17 hatten keinen). Die Entscheidung steht in
[ADR 0061](../../adr/0061-domain-in-autorenform-reader-normalform-und-neutralform-des-basistyps.md).

**Nach dem Review (3 Durchgänge, behoben):** `numeric(10,2)` und `varchar(n)` verschwinden nicht
mehr stillschweigend (Klammerparameter bleiben, `precision`/`scale` aus der Klammer, Länge nur bei
beidseitiger Angabe); die Synonymtabelle kennt `jsonb`/`bytea`; die Cast-Entfernung lässt
Typmodifikatoren, Array-Casts, Rechenoperatoren und negative Zahlen in Ruhe (Bug am
`character varying(10)` gefunden und behoben); der Vergleich ignoriert `precision`/`scale` bei
Nicht-`decimal` (alte Reverse-Dateien); Tests: `knownLimits` prüfen `AlterCustomType`, mehr Messzeilen
(`double precision`, `bpchar`, Domain über Domain, `OR`, Array-Domain), Rückweg für Domains.
**Folge-Slices:** Mehrfach-CHECK in [`postgres-domain-mehrfach-check.md`](postgres-domain-mehrfach-check.md),
`varchar`-Länge und Array-Domain in [`postgres-domain-laenge-array.md`](../next/postgres-domain-laenge-array.md),
`DEFAULT`/`NOT NULL`/Kollation in [`domain-default-not-null-modell.md`](../next/domain-default-not-null-modell.md).
Der `schema reverse`-Test (Ausgabe ohne `precision: 32`, `YamlSchemaWriterTest`) und der Parser-Roundtrip
mit `decimal` (`SchemaNodeParserTest`) sind ergänzt.
