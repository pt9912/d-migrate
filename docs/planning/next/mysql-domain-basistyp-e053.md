# MySQL: der Basistyp einer Domain wird roh ausgegeben, ohne Gegenstück entsteht still ungültiges DDL (E053 fehlt)

> **Status:** Draft mit Scope (2026-10-25). Befund aus dem Review zu
> [#58](https://github.com/pt9912/d-migrate/issues/58) (Länge/Array im PostgreSQL-Reader). Keine Regression von #58:
> mit `_int4` war das Verhalten dasselbe. **Vorbedingung:** keine; der Plan gilt mit und ohne #58 (Abschnitt
> „Wechselwirkung mit #58"). **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit.
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
| `timestamptz`, `timestamptz(3)` | `timestamptz(3)` | unbekannter Typ; `DateTime(timezone = true)` hat in `resolveBaseType` ein Gegenstück (MySQL-Typmapper: `DATETIME`/`TIMESTAMP`, W-Notiz zur Zeitzone — P0) |
| `timetz`, `bit`, `varbit(n)`, `bpchar`, `money`, `interval`, `inet`, `cidr`, `macaddr` | roh | kein Gegenstück → E053 (`bit(n)` hat in MySQL einen Typ gleichen Namens — P0, ob die Bedeutung trägt) |
| `uuid`, `jsonb`, `bytea`, `int4`, `int8`, `float8`, `bool` | roh | `uuid`/`jsonb`/`bytea`/`int4`/`int8`/`float8` sind **keine** MySQL-Typnamen, obwohl `resolveBaseType` sie kennt → Mapper statt Rohname |
| `numeric` ohne Klammer, `precision`/`scale` an der Domain | `numeric` | wird zu `DECIMAL(10,0)`: **still falsche Genauigkeit**, kein Fehler — der gefährlichste Fall |
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
  MySQL-Ausgaben steigt, die Ursache bleibt dieselbe. #58 dokumentiert die MySQL-Grenze ehrlich im Handbuch und nimmt die
  Spec-Aussage „Array … in MySQL als E053" auf; der Test `DomainBaseTypeCrossDialectTest` (Matrix) lässt MySQL beim
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
Stelle), (K4) Eingriff an Spec und Fingerabdruck, (K5) Rück-Reverse stabil (zweiter Lauf `operationsTotal` 0).

| Option | K1 | K2 | K3 | K4 | K5 |
| ------ | -- | -- | -- | -- | -- |
| **A — nur E053** für alles, was nicht roh trägt; sonst wie heute | ja | `numeric` bleibt still falsch | nein | keiner | offen |
| **B — `resolveBaseType` + MySQL-Typmapper; `null` → E053** (Spalte `TEXT`, wie SQLite) | ja | ja | ja | Spec §6.3 trägt es schon | zu messen |
| **C — B, plus gültige Abbildung dort, wo MySQL ein Gegenstück hat** (`timestamptz(n)` → `DATETIME(n)` mit W-Notiz, `bit(n)` → `BIT(n)` nur nach P0) | ja | nur nach P0 | ja | Spec-Zusatz je Abbildung | zu messen |

**Empfehlung: B**, C nur für die Typen, die P0 als bedeutungstreu belegt. `columnDomain` löst wie MSSQL über
`DomainTypes.resolveBaseType(baseType, precision, scale)` auf und rendert mit dem MySQL-Typmapper (`typeMapper.toSql`);
`null` ergibt `ManualActionRequired` E053 (`objectType = "domain"`, `objectName = "<tabelle>.<spalte>"`) und die Spalte
`TEXT`, im Wortlaut an SQLite/MSSQL angelehnt. Das behebt `numeric`/`uuid`/`jsonb`/`bytea` mit, weil der Mapper die gültige
MySQL-Form kennt. Ein Roh-Durchreichen entfällt ganz (K1, K2). Offen für P1: ob `timestamptz` ohne Zeitzonen-Gegenstück E053
oder Abbildung mit Warnung wird (Kriterium: wie behandelt `MysqlTypeMapper` `DateTime(timezone = true)` an einer gewöhnlichen
Spalte? Dieselbe Regel gilt für die Domain-Spalte).

## Phasen

* **P0 Messung.** Integrationsmessung gegen MySQL (`test/integration-mysql`, Image aus `TestImages`; Version nennen). Je Basistyp
  der Tabelle oben plus **jede Zeile aus `resolveBaseTypeName`** (`integer`, `int4`, `bigint`, `int8`, `smallint`, `int2`,
  `boolean`, `bool`, `real`, `float4`, `float`, `float8`, `double precision`, `numeric` mit/ohne `precision`, `text`,
  `character varying` mit/ohne Länge, `char`, `uuid`, `json`, `jsonb`, `xml`, `bytea`, `date`, `time`, `timestamp`,
  `timestamptz`) das **heute erzeugte** `CREATE TABLE`-DDL ausführen und festhalten: nimmt MySQL es an, und mit welchem
  tatsächlichen Typ (`information_schema.columns.column_type`)? Dazu das Soll nach Option B/C. Tabelle *Basistyp · erzeugtes DDL ·
  MySQL-Ergebnis · Soll* im Plan nachtragen; P0 endet, wenn jede Zeile gefüllt ist.
  **Matrix-Prüfung (Teil von P0):** `test/cross-dialect-matrix` auf MySQL-Domain-Fälle lesen. Bekannt auf `main`: kein
  Domain-Fixture (nur `ForeignRawExpressionRefusalTest`, `MatrixSweepTest`); im Branch `issue-58-laenge-array` kommt
  `DomainBaseTypeCrossDialectTest` mit `pg-reverse.yaml` dazu und nimmt MySQL für Arrays **aus**. Zu prüfen: ob
  `CarveOutRegistry` oder `MatrixSweepRunner` Domain-Fälle sehen und einen Carve-out führen, der MySQL deckt, und ob der Sweep
  MySQL-DDL gegen eine echte MySQL-Instanz ausführt (dann hätte er den Fehler gefunden — Lücke benennen).
  Ohne Matrix-Sicht ist das ein eigener Befund (Fixture in P2).
* **P1 Entscheidung.** Option (B/C), E053-Wortlaut, Behandlung `timestamptz`/`bit(n)`, Array-Erkennung (siehe #58).
  **Tag/ADR-Bedarf:** erwartet **kein** Tag-Bump (der Fingerabdruck projiziert Datei und Reader-Ausgang, nicht das MySQL-DDL;
  `MysqlNeutralTypeCanonicalizer`/Vergleichspfad prüft P0 mit: löst der MySQL-Vergleich eine Domain-Spalte schon über
  `resolveBaseType` auf, ändert sich dort etwas?). Kriterium für ein Ja: ändert sich `MigrationFingerprint.project` für einen
  bestehenden Eingang. Ein ADR nur dann, wenn eine Abbildung (C) oder eine Vergleichsregel neu festgelegt wird. Nummern **zum
  Aktivierungszeitpunkt** nehmen, nicht jetzt: vergeben sind ADR 0064–0067 und Tags bis `schema-fingerprint-v25` (`main` trägt
  zum Planzeitpunkt `v21`, ADR bis 0063; #58 hält v23/ADR 0065). Der nächste freie ist dann zu prüfen (`docs/adr/`, `grep
  schema-fingerprint-v`); accepted-ADRs bleiben unberührt.
* **P2 Umsetzung mit Tests.**
  * `columnDomain` mit `precision`/`scale` und `notes`; `columnEnum`/`columnClause` reichen `notes` durch.
  * Unit-Test `MysqlDdlGenerator` je Basistyp der P0-Matrix: Soll-DDL oder E053 mit Objektname; absichtlich falsche Zusicherung
    einmal rot gesehen.
  * Der `numeric`-Fall (`precision`/`scale` an der Domain) als eigener Test (Regression gegen die stille Genauigkeit).
  * MySQL-Integrationstest: das erzeugte DDL für jeden Basistyp **ausführen**; E053-Typen enthalten die Ersatzspalte `TEXT` und
    werden angenommen; zweiter Lauf `operationsTotal` 0 (soweit der Reader die Domain-Spalte zurückliest, P0).
  * **Matrix:** Fixture mit Domain je Basistyp (Reverse-Form wie `pg-reverse.yaml`) gegen MySQL, Soll-Ergebnis je Zelle; zuerst
    rot ohne die Änderung. Fällt #58 ins Rebase: die MySQL-Ausnahme im Array-Test streichen, der Fall wird E053 wie SQLite/MSSQL.
  * Detekt: Zusatzparameter nicht über `LongParameterList` unterdrücken — Kontext als Wertobjekt (`CLAUDE.md`).
* **P3 Doku.** `docs/user/anwenderhandbuch.md` (die Domain-Stelle zu Dialekten/MySQL; nur Ist-Zustand, ohne Chronik: keine
  „bis Version x"-Sätze, die #58-Warnung „prüfen Sie das erzeugte MySQL-Skript" entfällt/wird zur E053-Beschreibung),
  `CHANGELOG.md`, `spec/ddl-generation-rules.md` §6.3 **nur**, falls P1 eine Abbildung (C) festlegt oder der Satz „Für
  MySQL/SQLite: Als Basistyp + CHECK Constraint inline" einen Zusatz „über den neutralen Typ" braucht — die E053-Regel selbst
  steht schon da (die Spec ist hier das Zielbild, der Code holt auf). Handbuch-Aussagen zu E053 erst, wenn P2 liegt.

## Nicht-Scope

Oracle (löst Domain-Basistypen gar nicht auf, `CLOB` + E053 für jede Domain — eigener, bereits ausgewiesener Stand); Reader-Seite
des PostgreSQL-Basistyps (#58); der Domain-`CHECK` auf MySQL; `DEFAULT`/`NOT NULL`/Kollation der Domain
(`domain-default-not-null-modell.md`); Vergleich einer MySQL-Domain-Spalte gegen die Datenbank (nur falls P0 dort eine
Abweichung zeigt, dann eigener Plan).

## Akzeptanz

* P0-Tabelle vollständig: jede Zeile hat gemessene MySQL-Annahme (ja/nein) und tatsächlichen Typ.
* Kein Basistyp der P0-Matrix erzeugt auf MySQL DDL, das MySQL ablehnt (Integrationstest führt es aus) oder mit anderer
  Bedeutung annimmt (`numeric` mit `precision`/`scale` ergibt `DECIMAL(p,s)`, nicht `DECIMAL(10,0)`).
* Jeder Basistyp ohne Gegenstück (`integer[]`, `timetz`, `varbit(n)`, `bpchar`, …) erzeugt genau eine E053-Notiz
  (`objectType = "domain"`, `objectName = "<tabelle>.<spalte>"`), die Spalte ist `TEXT`, `schema generate` endet mit dem
  Action-Required-Ausgang wie für SQLite/MSSQL.
* Jede gemessene Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt; absichtlich falsche
  Zusicherung einmal rot gesehen.
* Matrix-Test (`test/cross-dialect-matrix`) deckt MySQL für Domains ab, rot vor der Änderung; Array bleibt auf MySQL kein
  Sonderfall mehr.
* `MigrationFingerprint` unverändert (kein Tag-Bump), oder die Gegenentscheidung steht in P1 mit Tag und ADR.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module (`:adapters:driven:driver-mysql`,
  `:adapters:driven:driver-common`; einmal ohne `MODULES=`, falls eine geteilte Signatur wechselt),
  `make integration INTEGRATION_TASKS=":test:integration-mysql:test"`, `make solid-suppression-gate`, `make docs-check`,
  `make doc-immutable RANGE=origin/main..HEAD`.
