# MySQL: der Basistyp einer Domain wird roh ausgegeben, ohne Gegenstück entsteht still ungültiges DDL (E053 fehlt)

> **Status:** In Arbeit (aktiviert 2026-10-25). Befund aus dem Review zu
> [#58](https://github.com/pt9912/d-migrate/issues/58) (Länge/Array im PostgreSQL-Reader). Keine Regression von #58:
> mit `_int4` war das Verhalten dasselbe. **Vorbedingung:** keine; der Plan gilt mit und ohne #58 (Abschnitt
> „Wechselwirkung mit #58"). Aktiviert mit dem ersten Implementierungs-Commit.
> GitHub-Issue: [#62](https://github.com/pt9912/d-migrate/issues/62).
> Bezug: [`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md) §6.3 (Zielbild: „Ein Basistyp ohne
> neutrales Gegenstück wird nicht roh durchgereicht, sondern als `action_required` E053 ausgewiesen"), ADR 0061.

## Ziel

`schema generate` und `schema migrate` auf MySQL schreiben für eine Domain-Spalte nie einen Basistyp, den MySQL nicht
annimmt. Ein Basistyp mit Gegenstück wird auf dessen MySQL-Typ abgebildet; einer ohne Gegenstück erscheint als E053,
nicht als ungültiges DDL ohne Notiz.

## Problem (am Code belegt)

[`MysqlColumnConstraintHelper.columnDomain`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlColumnConstraintHelper.kt)
(Z. 133) setzt `customType.baseType ?: "TEXT"` **unverändert** in die Spalte. Die Funktion bekommt weder `precision`/`scale`
der Domain noch eine Notiz-Liste (`columnEnum` reicht `notes` nicht durch) und ruft
[`DomainTypes.resolveBaseType`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/metadata/DomainTypes.kt)
nicht auf. Die anderen Dialekte tun es:

| Dialekt | Stelle | Verhalten ohne Gegenstück |
| ------- | ------ | ------------------------- |
| SQLite | `SqliteRefTypeColumns.resolveDomain` → `DomainTypes.resolveBaseType` | E053 |
| MSSQL | `MssqlColumnConstraintHelper.domainColumn` → `MssqlColumnTypeResolver.resolveDomainBaseType` | E053, Spalte `NVARCHAR(MAX)` |
| Oracle | `OracleColumnConstraintHelper.enumColumn` | **jede** Domain `CLOB` mit E053 (Basistyp wird nicht aufgelöst) |
| MySQL | `columnDomain` | **roh**, keine Notiz |

Betroffen ist nicht nur das Array. Der Reader reicht PostgreSQL-Katalognamen durch (`DomainTypes` nennt sie: „die gängigen
PostgreSQL-Katalognamen, die der PG-Reverse roh durchreicht"); MySQL bekommt sie wörtlich. Nach dem Code zu erwarten, **in P0
zu messen** (nicht angenommen):

| Basistyp in der Datei | roh in MySQL | erwartet |
| --------------------- | ------------ | -------- |
| `integer[]`, `varchar[]`, `numeric[]` (Array; vorher `_int4`) | `INTEGER[]` | Syntaxfehler; kein Gegenstück → E053 |
| `timestamptz`, `timestamptz(3)` | `timestamptz(3)` | unbekannter Typ. `resolveBaseType` kennt `timestamptz` (`DateTime(timezone = true)`), verwirft aber `(3)` (`DateTime` trägt keine Nachkommastellen); `MysqlTypeMapper.simpleToSql` gibt jedes `DateTime` pauschal als `DATETIME` aus. Option B macht aus `timestamptz(3)` also still `DATETIME` (K2-Verstoß: Zeitzone und Nachkommastellen fallen weg) — Regel dazu unter „Optionen“ |
| `timetz`, `bit`, `varbit(n)`, `bpchar`, `money`, `interval`, `inet`, `cidr`, `macaddr` | roh | kein Gegenstück → E053 (`bit(n)` hat in MySQL einen Typ gleichen Namens — P0, ob die Bedeutung trägt) |
| `uuid`, `jsonb`, `bytea` | roh | keine MySQL-Typnamen, obwohl `resolveBaseType` sie kennt → Mapper statt Rohname (`uuid` → `CHAR(36)`, `jsonb` → `JSON`, `bytea` → `BLOB`) |
| `int4`, `int8`, `float8`, `bool` | roh | voraussichtlich gültig (MySQL kennt `INT4`, `INT8`, `FLOAT8` als Synonyme, `BOOL` als `TINYINT(1)`); **nicht behauptet**, Stufe-2-Messung. Der Mapper schreibt in jedem Fall `INT`/`BIGINT`/`DOUBLE`/`TINYINT(1)` |
| `numeric`/`decimal` **mit** `precision`/`scale` an der Domain | `numeric` | `precision` wird roh ignoriert: MySQL liest `DECIMAL(10,0)`, **still falsche Genauigkeit**; nach B `DECIMAL(p,s)` |
| `numeric`/`decimal` **ohne** `precision` und ohne Klammer | `numeric` | roh `DECIMAL(10,0)`; `resolveBaseType` liefert `null` → nach B E053 + `TEXT` (Typwechsel Zahl → Text). P1: E053 oder `DECIMAL(10,0)` |
| `character varying` ohne Länge | roh | MySQL verlangt eine Länge; `Text()` hat ein Gegenstück |

Folge: der Lauf erzeugt entweder einen Syntaxfehler erst beim Ausführen (`schema migrate --execute`) oder, bei `numeric`/`bit`,
ein DDL, das MySQL annimmt und anders deutet. Der Fehlschlag trifft den Anwender hinter dem Plan-Only-Lauf, den er für grün hielt.

## Wechselwirkung mit #58

[#58](https://github.com/pt9912/d-migrate/issues/58) (Branch `issue-58-laenge-array`, **nicht auf `main`**; ADR 0065,
`schema-fingerprint-v23`) führt Länge und Array als Neutralform im PostgreSQL-Reader ein: der Reader schreibt `varchar(50)`,
`timestamp(3)`, `integer[]` statt `text`/`_int4`. `DomainTypes` bekommt dort **keinen** Array-Zweig; `resolveBaseType` liefert für
ein Array weiter `null`. Der Plan ist davon unabhängig, die Wechselwirkung ist:

* **Ohne #58 (heute `main`):** Reader liefert `_int4`, `text` (für `varchar(50)`), `timestamp` (für `timestamp(3)`). Betroffen
  sind die Katalognamen und Arrays; `varchar(n)`/`timestamp(n)` kommen gar nicht erst als Parameter an.
* **Mit #58:** mehr Eingänge tragen Klammerformen und `[]` (`integer[]`, `timestamptz(3)`, `varbit(7)`); die Zahl falscher
  MySQL-Ausgaben steigt, die Ursache bleibt dieselbe. #58 dokumentiert die MySQL-Grenze im Handbuch (Branch `issue-58-laenge-array`,
  `docs/user/anwenderhandbuch.md`: „Prüfen Sie das erzeugte MySQL-Skript für solche Domains“) und nimmt in
  `spec/ddl-generation-rules.md` §6.3 (dort Z. 1289) den Satz auf, ein Array-Basistyp habe in MySQL, SQLite, MSSQL und Oracle kein
  Gegenstück und werde als E053 ausgewiesen. **Merge-Risiko:** dieser Satz ist für MySQL bis zu diesem Plan falsch; Handbuch und Spec
  widersprechen sich im #58-Stand. Als Zielbild zulässig, aber wer #58 zuerst mergt, liefert eine Zeile, die der Code nicht erfüllt; der Test `DomainBaseTypeCrossDialectTest` (Matrix) lässt MySQL beim
  Array-Fall **bewusst aus** („MySQL reicht den Basistyp roh durch"). Dieser Plan löst die Grenze auf.
* **Reihenfolge:** egal. Landet dieser Plan zuerst, ergänzt #58 beim Merge die MySQL-Zeile im Matrix-Test (Array → E053) und
  streicht die Grenze im Handbuch; landet #58 zuerst, streicht dieser Plan sie in P3. Die Handbuch-Stelle und die Spec-Ergänzung
  sind die Konfliktfläche beim Rebase — dort nachführen, nicht doppeln.
* **Kein Array-Zweig in `DomainTypes`:** die Array-Erkennung (Suffix `[]`) gehört in `resolveBaseType` oder davor, gemeinsam für
  alle Dialekte, nicht als MySQL-Sonderfall. Wer sie zuerst braucht, legt sie dort ab; der zweite übernimmt. Heute gibt
  `resolveBaseType` für `integer[]` ohnehin `null` (kein `when`-Treffer; `TYPE_WITH_PARAMS` greift nicht), SQLite/MSSQL
  weisen es also schon aus.

## Optionen

Kriterien: (K1) kein ungültiges DDL, (K2) keine stille Bedeutungsänderung, (K3) Parität mit SQLite/MSSQL (eine Auflösung, eine
Stelle), (K4) Eingriff an Spec (E053 steht in §6.3 schon) und, getrennt davon, am Fingerabdruck (keiner erwartet), (K5) Rück-Reverse stabil (zweiter Lauf `operationsTotal` 0).

| Option | K1 | K2 | K3 | K4 | K5 |
| ------ | -- | -- | -- | -- | -- |
| **A — nur E053** für alles, was nicht roh trägt; sonst wie heute | ja | `numeric` mit `precision` bleibt still falsch | nein | Spec: keiner (E053 steht in §6.3); Fingerabdruck: keiner | Rückweg unverändert zu heute |
| **B — `resolveBaseType` + MySQL-Typmapper; `null` → E053** (Spalte `TEXT`, wie SQLite) | ja | ja, bis auf `timestamptz(n)` (still `DATETIME`, Regel unten) | ja | Spec §6.3 trägt es schon | zu messen |
| **C — B, plus gültige Abbildung dort, wo MySQL ein Gegenstück hat** (`bit(n)` → `BIT(n)` nur nach P0; `(n)` bei Zeittypen erst, wenn `DateTime` Nachkommastellen trägt — Modelländerung, nicht hier) | ja | nur nach P0 | ja | Spec-Zusatz je Abbildung | zu messen |

**Empfehlung: B**, C nur für die Typen, die P0 als bedeutungstreu belegt. `columnDomain` löst wie MSSQL über
`DomainTypes.resolveBaseType(baseType, precision, scale)` auf und rendert mit dem MySQL-Typmapper (`typeMapper.toSql`);
`null` ergibt `ManualActionRequired` E053 (`objectType = "domain"`, `objectName = "<tabelle>.<spalte>"`) und die Spalte
`TEXT`, im Wortlaut an SQLite/MSSQL angelehnt. Das behebt `numeric`/`uuid`/`jsonb`/`bytea` mit, weil der Mapper die gültige
MySQL-Form kennt. Ein Roh-Durchreichen entfällt ganz (K1, K2). Zeitzone: die Warnung W100 sitzt in `MysqlDdlGenerator.generate` (Z. 123-131) und prüft `col.type is DateTime`; eine
Domain-Spalte (`Enum(refType)`) bekommt sie nie. **Festlegung für P2:** die Regel (Zeitzone → W100) wird eine geteilte
Funktion, die `generate` für gewöhnliche Spalten und `columnDomain` für den aufgelösten Basistyp aufrufen; eine Domain über
`timestamptz` bekommt W100 wie eine gewöhnliche Spalte. Die Klammer `(n)` verwirft `resolveBaseType`; ob das eine zusätzliche
Notiz trägt oder als Grenze benannt wird, entscheidet P1 — nicht stillschweigend.

## Phasen

* **P0 Messung.** Zwei Stufen, damit nicht alles gegen MySQL läuft:
  1. **Unit-Test ohne MySQL** für reine Mapper-Ausgaben: jede Zeile aus `resolveBaseTypeName` (`integer`, `int4`, `bigint`, `int8`,
     `smallint`, `int2`, `boolean`, `bool`, `real`, `float4`, `float`, `float8`, `double precision`, `numeric`, `text`,
     `character varying`, `varchar`, `char`, `decimal`, `uuid`, `json`, `jsonb`, `xml`, `bytea`, `date`, `time`, `time without time zone`, `timestamp`, `timestamptz`, `timestamp with time zone`) plus
     Arrays und die Typen ohne Gegenstück: heute erzeugtes DDL und Soll (B/C) festhalten.
  2. **Messung gegen echtes MySQL** (`test/integration-mysql`, `TestImages.MYSQL` = `mysql:9.7.2`) nur dort, wo die Annahme vom
     Typnamen abhängt: `bit`/`bit(n)`, `numeric` mit/ohne `precision`, `bool`, `int4`, `int8`, `float8`, `timestamptz(3)` — nimmt MySQL das DDL an, mit
     welchem `information_schema.columns.column_type`?
  **Ablageort:** Abschnitt „P0-Ergebnis“ in diesem Plan, Tabelle *Basistyp · erzeugtes DDL · MySQL-Ergebnis (nur Stufe 2) · Soll*;
  P0 endet, wenn jede Zeile gefüllt ist.
  **Matrix (beantwortet):** `MatrixSweepRunner` und `MatrixSweepTest` enthalten keine JDBC-/Testcontainers-Aufrufe; der Sweep
  führt DDL nicht aus. Die Matrix kann für MySQL nur das erzeugte DDL und die E053-Notizen pinnen, **nicht** die
  MySQL-Akzeptanz — die gehört in den Integrationstest. Auf `main` gibt es kein Domain-Fixture; im Branch `issue-58-laenge-array`
  kommt `DomainBaseTypeCrossDialectTest` mit `pg-reverse.yaml` dazu und nimmt MySQL für Arrays **aus**. Carve-outs (beantwortet): `test/cross-dialect-matrix/src/test/resources/fixtures/carve-outs.yaml`
  führt `dialect: mysql` nur bei CHECK und Materialized Views, keine Domain-Zeile; es gibt keinen Carve-out, der MySQL-Domains deckt.
* **P1 Entscheidung.** Option (B/C), E053-Wortlaut, Behandlung `timestamptz`/`bit(n)`, Array-Erkennung (siehe #58).
  **Tag/ADR-Bedarf:** erwartet **kein** Tag-Bump (der Fingerabdruck projiziert Datei und Reader-Ausgang, nicht das MySQL-DDL;
  `MysqlNeutralTypeCanonicalizer`/Vergleichspfad prüft P0 mit: löst der MySQL-Vergleich eine Domain-Spalte schon über
  `resolveBaseType` auf, ändert sich dort etwas?). Kriterium für ein Ja: ändert sich `MigrationFingerprint.project` für einen
  bestehenden Eingang. Ein ADR nur dann, wenn eine Abbildung (C) oder eine Vergleichsregel neu festgelegt wird. Nummern **zum
  Aktivierungszeitpunkt** nehmen, nicht jetzt: **reserviert, nicht auf `main`:** ADR 0064 (#55), 0065 (#58), 0066 (#59), 0067 (für #60/#61
  vorgesehen); Tags v22–v24 belegt, v25 reserviert (`main` trägt zum Planzeitpunkt `v21`, ADR bis 0063). Der nächste freie ist dann zu prüfen (`docs/adr/`, `grep
  schema-fingerprint-v`); accepted-ADRs bleiben unberührt.
* **P2 Umsetzung mit Tests.**
  * `columnDomain` mit `precision`/`scale` und `notes`; `columnEnum`/`columnClause` reichen `notes` durch.
  * Unit-Test `MysqlDdlGenerator` je Basistyp der P0-Matrix: Soll-DDL oder E053 mit Objektname; absichtlich falsche Zusicherung
    einmal rot gesehen.
  * Der `numeric`-Fall mit `precision`/`scale` an der Domain als eigener Test (Regression gegen die stille Genauigkeit), dazu der Fall ohne `precision` (P1-Entscheidung).
  * **Detekt:** `config/detekt/detekt.yml` setzt `LongParameterList.functionThreshold: 8` mit `ignoreDefaultParameters: true`; mit `notes`/`tableName`/`schema` hätte `columnDomain` 6 Parameter und löst die Regel **nicht** aus. Ein Wertobjekt für den Spaltenkontext ist Designwahl bei Bedarf, damit die Grenze 8 nicht reißt. Grundregel bleibt: kein `@Suppress`; `make solid-suppression-gate` vor dem Commit.
  * W100 für Domain über `timestamptz` (geteilte Regel mit `generate`) als Test.
  * MySQL-Integrationstest: das erzeugte DDL für jeden Basistyp **ausführen**; E053-Typen enthalten die Ersatzspalte `TEXT` und
    werden angenommen; zweiter Lauf `operationsTotal` 0 (soweit der Reader die Domain-Spalte zurückliest, P0).
  * **Matrix:** Fixture mit Domain je Basistyp (Reverse-Form wie `pg-reverse.yaml`) gegen MySQL, Soll-Ergebnis je Zelle; zuerst
    rot ohne die Änderung. (Rebase-Folge für #58: siehe „Reihenfolge“.)
* **P3 Doku.** `docs/user/anwenderhandbuch.md` (die Domain-Stelle zu Dialekten/MySQL; nur Ist-Zustand, ohne Chronik: keine
  „bis Version x"-Sätze, die #58-Warnung „prüfen Sie das erzeugte MySQL-Skript" entfällt/wird zur E053-Beschreibung),
  `CHANGELOG.md`, `spec/ddl-generation-rules.md` §6.3 **nur**, falls P1 eine Abbildung (C) festlegt oder der Satz „Für
  MySQL/SQLite: Als Basistyp + CHECK Constraint inline" einen Zusatz „über den neutralen Typ" braucht — die E053-Regel selbst
  steht schon da (die Spec ist hier das Zielbild, der Code holt auf). Handbuch-Aussagen zu E053 erst, wenn P2 liegt.

## P0-Ergebnis

Stufe 1: Unit-Test `MysqlDomainBaseTypeTest` (`adapters/driven/driver-mysql`), eine Zeile je `resolveBaseTypeName`-Eintrag
plus die Typen ohne Gegenstück; **vor** der Änderung rot (der Test zeigte die Rohausgabe: `int4`, `numeric`, `uuid` standen wörtlich in der Spalte). Stufe 2: Messung gegen `mysql:9.7.2` (`TestImages.MYSQL`), `CREATE TABLE t (c <Typ>)` je
Typname, gelesen aus `information_schema.columns.column_type`. „Heute“ ist der Stand vor der Änderung: `columnDomain` schreibt
`baseType` wörtlich und ignoriert `precision`/`scale` der Domain. „–“ = nicht gemessen (Stufe 2 nur, wo die Annahme vom
Typnamen abhängt); die Soll-Spalte ist durch den Integrationstest (DDL ausgeführt, `column_type` gelesen) belegt.

| Basistyp (Domain) | erzeugtes DDL heute | MySQL-Ergebnis (Stufe 2) | Soll (Option B) |
| ----------------- | ------------------- | ------------------------ | --------------- |
| `integer`, `int`, `int4` | wörtlich | `int4`: angenommen, `int` | `INT` |
| `bigint`, `biginteger`, `int8` | wörtlich | `int8`: angenommen, `bigint` | `BIGINT` |
| `smallint`, `int2` | wörtlich | – | `SMALLINT` |
| `boolean`, `bool` | wörtlich | `bool`: angenommen, `tinyint(1)` | `TINYINT(1)` |
| `real`, `float4` | wörtlich | – | `FLOAT` |
| `float`, `double precision`, `float8` | wörtlich | `float8`: angenommen, `double` | `DOUBLE` |
| `numeric`/`decimal` mit `precision`/`scale` | `numeric` (Genauigkeit der Domain fehlt) | `numeric`: angenommen, `decimal(10,0)`; `numeric(10,2)`: `decimal(10,2)` | `DECIMAL(p,s)` |
| `numeric`/`decimal` ohne `precision` | `numeric` | `decimal(10,0)` (Nachkommastellen fallen still weg) | E053 + `TEXT` (P1) |
| `text` | wörtlich | – | `TEXT` |
| `character varying` ohne Länge | wörtlich | Syntaxfehler | `TEXT` |
| `varchar`/`character varying` mit `precision` | wörtlich, Länge fehlt | `varchar` ohne Länge ist in MySQL ungültig (`character varying` gemessen: Syntaxfehler) | `VARCHAR(n)` |
| `varchar(20)` (Klammerform) | `varchar(20)` | – | `VARCHAR(20)` |
| `char` / `char` mit `precision` | wörtlich | – | `CHAR(1)` / `CHAR(n)` |
| `uuid` | `uuid` | – (kein MySQL-Typname) | `CHAR(36)` |
| `json` / `jsonb` | wörtlich | – | `JSON` |
| `xml` | `xml` | – | `TEXT` |
| `bytea` | `bytea` | – | `BLOB` |
| `date` / `time` | wörtlich | – | `DATE` / `TIME` |
| `time without time zone` | wörtlich | – | `TIME` |
| `timestamp`, `timestamp without time zone` | wörtlich | – | `DATETIME` |
| `timestamptz`, `timestamp with time zone` | wörtlich | Syntaxfehler | `DATETIME` + W100 |
| `timestamptz(3)` | `timestamptz(3)` | Syntaxfehler | `DATETIME` + W100; `(3)` fällt (Grenze, P1) |
| `bit` | `bit` | angenommen, `bit(1)` | E053 + `TEXT` (P1) |
| `bit(3)` | `bit(3)` | angenommen, `bit(3)` | E053 + `TEXT` (P1) |
| `varbit(7)`, `timetz`, `bpchar`, `money`, `interval`, `inet`, `cidr`, `macaddr` | wörtlich | `varbit(7)`, `timetz`: Syntaxfehler | E053 + `TEXT` |
| `integer[]`, `varchar[]`, `numeric[]`, `_int4` | wörtlich | `integer[]`: Syntaxfehler | E053 + `TEXT` |

Weitere Befunde der Messung:

* `bit`/`bit(3)` ist die einzige Stufe-2-Zeile, die MySQL **annimmt und anders deutet**, neben `numeric` (Genauigkeit
  der Domain ignoriert, ohne `precision` `decimal(10,0)`). `int4`, `int8`, `float8`, `bool` tragen: die Annahme „voraussichtlich
  gültig“ aus dem Problem-Abschnitt bestätigt sich; der Mapper schreibt trotzdem `INT`/`BIGINT`/`DOUBLE`/`TINYINT(1)`.
* `char` mit `precision` an der Domain und `varchar`/`character varying` mit `precision` verloren die Länge (zusätzlich zum
  Problem-Abschnitt).
* **Prämisse „`schema migrate`“ widerlegt:** `columnDomain` liegt nur am Generate-Pfad (`MysqlColumnConstraintHelper`). Der Diff-Renderer
  (`MysqlDiffSqlBuilders`/`MysqlDiffTableOps`) kennt keine Domain-Auflösung: er schreibt eine `ref_type`-Spalte über den Mapper
  als `TEXT` und warnt mit W134 – nie roh. Der Befund „roh, ungültiges DDL“ gilt daher nur für `schema generate`; die
  Domain-Auflösung im Migrate-Pfad (Typ der Domain statt `TEXT`) ist Folgearbeit, nicht Teil von #62.
* **Vergleichspfad:** `MysqlNeutralTypeCanonicalizer` reicht `Enum(refType)` unverändert durch (`type is Enum && refType != null -> type`);
  er löst keine Domain auf, und der Fingerabdruck projiziert Datei und Reader-Ausgang, nicht das MySQL-DDL. Diese Änderung berührt
  beides nicht. Dass eine Domain-Spalte gegen die gelesene DB-Spalte (`Integer` gegen `Enum(refType)`) als geändert gilt, war schon
  vorher so und liegt im Nicht-Scope (siehe unten). Ein zweiter Lauf `operationsTotal` 0 ist deshalb **nicht** gemessen und nicht Teil der Akzeptanz.
* **Matrix:** der Sweep (`MatrixSweepTest`) vergleicht Exit-Codes und führt kein DDL aus; die Zellen pinnen deshalb das erzeugte
  DDL in `MysqlDomainBaseTypeMatrixTest` (siehe P2).

## P1-Entscheidung

* **Option B**, mit C für keinen Typ. `columnDomain` löst über `DomainTypes.resolveBaseType` auf und rendert mit
  `typeMapper.toSql`; `null` ergibt E053 (`objectType = "domain"`, `objectName = "<tabelle>.<spalte>"`), die Spalte `TEXT`. Wortlaut
  wie MSSQL/SQLite: „Domain '<name>' base type '<typ>' has no MySQL mapping; column '<spalte>' was rendered as TEXT.“
* **`bit`/`bit(n)`: E053, keine Abbildung (C nicht gewählt).** MySQL nimmt `BIT(n)` an (gemessen), aber die Bedeutung
  ist nicht belegt: das neutrale Modell hat keinen Bit-Typ, eine Abbildung wäre eine neue Regel (Spec-Zusatz, Reverse- und Datenpfad
  ungeprüft), und der Rück-Reverse (K5) hätte keinen neutralen Zieltyp. Bleibt als Folgearbeit offen.
* **`numeric`/`decimal` ohne `precision`: E053 + `TEXT`.** PostgreSQL-`numeric` ohne Angabe ist beliebig genau; `DECIMAL(10,0)`
  schnitte die Nachkommastellen still ab (K2). Der Typwechsel Zahl → Text ist durch E053 sichtbar; eine erfundene Genauigkeit
  (`DECIMAL(65,30)`) wäre eine neue Regel.
* **`(n)` bei `timestamptz`/`timestamp`/`time`: Grenze, keine zusätzliche Notiz.** `resolveBaseType` verwirft die Klammer, weil `DateTime`/`Time`
  keine Nachkommastellen tragen; das gilt auf MySQL schon für jede gewöhnliche Zeitspalte (der Mapper schreibt `DATETIME`). Eine
  Notiz nur für Domain-Spalten wäre eine Sonderregel; die Grenze steht im Handbuch. W100 (Zeitzone) bleibt für `timestamptz`.
* **W100 als geteilte Regel:** `MysqlTimezoneDegradation.noteFor` ersetzt den Inline-Block in `MysqlDdlGenerator.generate`;
  `columnDomain` ruft sie für den aufgelösten Basistyp auf.
* **Kein Tag-Bump, kein ADR:** der Fingerabdruck ändert sich für keinen Eingang (Vergleichspfad siehe P0); keine Abbildung (C)
  und keine Vergleichsregel neu festgelegt. Kein Wertobjekt für den Spaltenkontext: `columnDomain` hat 6 Parameter (Schwelle 8).
* **Ausgang von `schema generate`:** E053 hängt wie bei SQLite/MSSQL an der Notiz (ACTION_REQUIRED), nicht an einem
  `SkippedObject`; die Spalte entsteht ja (als `TEXT`). Der Ausgangscode folgt dieser Regel unverändert (kein eigener CLI-Lauf gemessen; der Mechanismus ist der von SQLite/MSSQL).

## Nicht-Scope

Oracle (löst Domain-Basistypen gar nicht auf, `CLOB` + E053 für jede Domain — eigener, bereits ausgewiesener Stand); Reader-Seite
des PostgreSQL-Basistyps (#58); der Domain-`CHECK` auf MySQL; `DEFAULT`/`NOT NULL`/Kollation der Domain
(`domain-default-not-null-modell.md`); Vergleich einer MySQL-Domain-Spalte gegen die Datenbank (nur falls P0 dort eine
Abweichung zeigt, dann eigener Plan).

## Akzeptanz

* P0-Tabelle vollständig: jede Zeile hat das erzeugte DDL und das Soll; die Zeilen der Stufe 2 (`bit`, `numeric`, `bool`, `int4`, `int8`, `float8`, `timestamptz(3)`) zusätzlich die gemessene MySQL-Annahme (ja/nein) und den tatsächlichen Typ.
* Kein Basistyp der P0-Matrix erzeugt auf MySQL DDL, das MySQL ablehnt (Integrationstest führt es aus) oder mit anderer
  Bedeutung annimmt (`numeric` mit `precision`/`scale` ergibt `DECIMAL(p,s)`, nicht `DECIMAL(10,0)`; `numeric` ohne `precision` folgt der P1-Entscheidung: E053 + `TEXT` oder `DECIMAL(10,0)`, jeweils getestet; `timestamptz` trägt W100).
* Jeder Basistyp ohne Gegenstück (`integer[]`, `timetz`, `varbit(n)`, `bpchar`, …) erzeugt genau eine E053-Notiz
  (`objectType = "domain"`, `objectName = "<tabelle>.<spalte>"`), die Spalte ist `TEXT`, `schema generate` endet mit dem
  Action-Required-Ausgang wie für SQLite/MSSQL.
* Jede gemessene Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt; absichtlich falsche
  Zusicherung einmal rot gesehen.
* Matrix-Test (`test/cross-dialect-matrix`) deckt MySQL für Domains ab, rot vor der Änderung; Array bleibt auf MySQL kein
  Sonderfall mehr (setzt das Array-Fixture aus #58 voraus; sonst per Rebase-Nachführung, sobald #58 liegt).
* `MigrationFingerprint` unverändert (kein Tag-Bump), oder die Gegenentscheidung steht in P1 mit Tag und ADR.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module (`:adapters:driven:driver-mysql`,
  `:adapters:driven:driver-common`; einmal ohne `MODULES=`, falls eine geteilte Signatur wechselt),
  `make integration INTEGRATION_TASKS=":test:integration-mysql:test"`, `make solid-suppression-gate`, `make docs-check`,
  `make doc-immutable RANGE=origin/main..HEAD`.
