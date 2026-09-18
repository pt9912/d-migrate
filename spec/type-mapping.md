# Type-Mapping: Bekannte Lücken und Entscheidungen

> Dokumentation der String-/Typ-Mapping-Grenzen pro Dialekt.
> Stand: 2026-04-19

---

## 1. Mapping-Architektur

Zwei Richtungen:

- **Forward (Neutral → SQL)**: `TypeMapper.toSql(NeutralType)` — exhaustiv
  durch Kotlin sealed class, kein `else` möglich
- **Reverse (SQL → Neutral)**: `*TypeMapping.mapColumn(dataType, ...)` —
  `else`-Fallback nötig, weil Datenbanken beliebige Typ-Strings liefern

Dateien:

| Dialekt | Forward | Reverse |
|---------|---------|---------|
| PostgreSQL | `PostgresTypeMapper.kt` | `PostgresTypeMapping.kt` |
| MySQL | `MysqlTypeMapper.kt` | `MysqlTypeMapping.kt` |
| SQLite | `SqliteTypeMapper.kt` | `SqliteTypeMapping.kt` |
| MSSQL | `MssqlTypeMapper.kt` | `MssqlTypeMapping.kt` |

---

## 2. String-Typen: Längenerhaltung

### Neutrales Modell

```kotlin
NeutralType.Text(maxLength: Int? = null)  // VARCHAR(n) oder TEXT
NeutralType.Char(length: Int)             // CHAR(n)
```

`maxLength = null` bedeutet unbegrenzter Text (`TEXT`).

### Reverse-Mapping (DB → Neutral)

| DB-Typ | PostgreSQL | MySQL | SQLite | MSSQL |
|--------|-----------|-------|--------|-------|
| `VARCHAR(n)` | `Text(maxLength=n)` ✅ | `Text(maxLength=n)` ✅ | `Text(maxLength=n)` ✅ | `Text(maxLength=n)` ✅ |
| `CHAR(n)` | `Char(length=n)` ✅ | `Char(length=n)` ✅ | `Char(length=n)` ✅ | `Char(length=n)` ✅ |
| `TEXT` | `Text()` ✅ | `Text()` ✅ | `Text()` ✅ | `Text()` ✅ |
| `MEDIUMTEXT` | — | `Text()` ✅ | — | — |
| `LONGTEXT` | — | `Text()` ✅ | — | — |
| `TINYTEXT` | — | `Text()` ✅ | — | — |
| `NVARCHAR(n)` | — | — | — | `Text(maxLength=n)` ✅ (Zeichen, nicht Bytes) |
| `NVARCHAR(MAX)`, `NTEXT` | — | — | — | `Text()` ✅ |
| `NCHAR(n)` | — | — | — | `Char(length=n)` ✅ |

### Forward-Mapping (Neutral → SQL)

| Neutraler Typ | PostgreSQL | MySQL | SQLite | MSSQL |
|---------------|-----------|-------|--------|-------|
| `Text(maxLength=100)` | `VARCHAR(100)` | `VARCHAR(100)` | `VARCHAR(100)` | `NVARCHAR(100)` |
| `Text()` | `TEXT` | `TEXT` | `TEXT` | `NVARCHAR(MAX)` |
| `Char(length=36)` | `CHAR(36)` | `CHAR(36)` | `CHAR(36)` | `NCHAR(36)` |

Länge geht in keiner Richtung verloren — mit einer Ausnahme: MSSQL trägt in
`NVARCHAR(n)`/`NCHAR(n)` höchstens 4000 Zeichen; eine größere deklarierte
Länge wird als `NVARCHAR(MAX)` gerendert (Warnung W136), der Reverse liest
dann `Text()` ohne Länge.

---

## 3. PostgreSQL: Bekannte Lücken

### 3.1 Extension-Typen (citext, ltree, hstore, etc.)

PostgreSQL liefert für Extension-Typen:
- `data_type = "USER-DEFINED"`
- `udt_name = "citext"` (oder `"ltree"`, `"hstore"`, etc.)

**Aktuelles Verhalten**: `mapUserDefined()` erkennt nur `geometry`
(PostGIS). Alle anderen `USER-DEFINED`-Typen werden als
`Enum(refType = udtName)` gemappt — das ist **falsch** für
text-artige Extensions wie `citext`.

**Betroffene Typen**:

| Extension | udt_name | Korrektes Mapping | Aktuell |
|-----------|----------|-------------------|---------|
| citext | `citext` | `Text()` | `Enum(refType="citext")` ❌ |
| ltree | `ltree` | `Text()` | `Enum(refType="ltree")` ❌ |
| hstore | `hstore` | `Json` oder `Text()` | `Enum(refType="hstore")` ❌ |
| tsvector | `tsvector` | `Text()` + Note | `Enum(refType="tsvector")` ❌ |

**Empfehlung**: In `mapUserDefined()` eine Allowlist bekannter
Extension-Typen einführen:

```kotlin
fun mapUserDefined(udtName: String, ...): MappingResult = when (udtName) {
    "geometry" -> MappingResult(NeutralType.Geometry(), ...)
    "citext" -> MappingResult(NeutralType.Text(), infoNote("citext mapped to Text"))
    "ltree" -> MappingResult(NeutralType.Text(), infoNote("ltree mapped to Text"))
    "hstore" -> MappingResult(NeutralType.Json, infoNote("hstore mapped to Json"))
    "tsvector" -> MappingResult(NeutralType.Text(), actionNote("tsvector has no neutral equivalent"))
    else -> MappingResult(NeutralType.Enum(refType = udtName))
}
```

**Priorität**: P2 — betrifft nur Reverse-Engineering von Datenbanken
mit Extensions. Dateibasierte Schemas sind nicht betroffen.

### 3.2 Interne PG-Typen (name, oid, regclass, etc.)

PostgreSQL-Systemkataloge verwenden interne Typen die in
`information_schema.columns` als `data_type` erscheinen können:

| data_type | Vorkommen | Korrektes Mapping |
|-----------|-----------|-------------------|
| `name` | Systemkataloge | `Text(maxLength=63)` |
| `oid` | Systemkataloge | `Integer` |
| `regclass` | Systemkataloge | `Text()` |

**Aktuell**: Fallen in `else` → `Text()` mit R301-Warning.
Das ist akzeptabel — Systemkataloge werden selten reversed.

### 3.3 Versionsspezifische Typen

| Typ | Ab PG-Version | Regel |
|-----|---------------|-------|
| `jsonb` | 9.4 | `json` |
| `json` | — | `json`, mit `R402`: das neutrale `json` rendert als `jsonb` zurück, und `jsonb` normalisiert den gespeicherten Text (Schlüsselreihenfolge, doppelte Schlüssel und bedeutungsloser Leerraum gehen verloren). `jsonb` selbst verliert nichts und meldet nichts |
| `uuid` | 8.3 (als Extension), nativ ab 13 | `uuid` |
| `generated always as (...)` | 12 | berechnete Spalte (`generation: computed`); die Speicherform kommt aus `pg_attribute.attgenerated`, siehe 6.3 für die dialektübergreifende Regel |
| `multirange` | 14 | kein neutraler Typ; `text` + `R301` (Abschnitt 8) |

### 3.4 Reverse-Entscheidungen

| PostgreSQL | Neutral | Regel |
|---|---|---|
| `numeric`/`decimal` **ohne** Präzision | `float` | `R404`: das neutrale Modell trägt keine ungebundene Dezimalzahl, und die Spalte rendert als `double precision` zurück — aus exakter wird binäre Arithmetik. Mit Präzision und Skala bleibt es `decimal(p,s)` und meldet nichts |
| ein Feld eines zusammengesetzten Typs | wie die gleichnamige Spaltenregel | dieselben Codes an derselben Stelle: `R404` für `numeric` ohne Präzision, `R301` für einen unbekannten Feldtyp |
| `<typ>[]` | `array` mit `element_type` | Die Elementart kommt aus dem Katalog-`udt_name`. Ein Element, für das es keinen neutralen Namen gibt (`date[]`, `inet[]`), liest `text` und meldet `R301` — derselbe `else`-Fallback wie an einer Spalte (Abschnitt 8). `json[]` trägt `R402` wie eine `json`-Spalte |

### 3.5 Forward-Entscheidungen

| Neutral | PostgreSQL | Regel |
|---|---|---|
| `array` | `<element>[]` | Jede Elementart, die der Reverse benennt, wird in ihrem Typ gerendert: `TEXT`, `INTEGER`, `BIGINT`, `BOOLEAN`, `UUID`, `DOUBLE PRECISION` (für `float`), `NUMERIC` (für `decimal`) und `JSONB` (für `json`). Die Schreibweise ist parameterlos — das Modell trägt am Array nur den Namen der Elementart, keine Präzision und keine Länge. Eine Elementart, die der Reverse nicht benennt, bleibt `TEXT[]` |

---

## 4. MySQL: Bekannte Lücken

### 4.1 SET-Typ

`SET('a','b','c')` wird als `Text()` mit R320 ACTION_REQUIRED gemappt.
Das ist bewusst — SET hat kein neutrales Äquivalent.

### 4.2 CHAR(36) → UUID Heuristik

MySQL hat keinen nativen UUID-Typ. `CHAR(36)` wird heuristisch als
`Uuid` gemappt (R310 Info-Note). Das kann false positives erzeugen
bei CHAR(36)-Spalten die keine UUIDs enthalten.

### 4.3 TINYINT(1) → Boolean Heuristik

`TINYINT(1)` wird als `BooleanType` gemappt. Andere TINYINT-Varianten
als `SmallInt`. Die Heuristik ist MySQL-Standard, aber nicht immer
korrekt.

### 4.4 `BIGINT AUTO_INCREMENT`: `serial` oder `identity` (inhärente Reverse-Mehrdeutigkeit)

`INT AUTO_INCREMENT` liest der Reverse als `identifier` (32-bit-Vertrag),
`BIGINT AUTO_INCREMENT` als `biginteger` + `generation: identity`. MySQL kennt
nur diese eine Autowert-Form; ob die Spalte als `SERIAL` oder als
SQL-Standard-IDENTITY gemeint war, trägt die Datenbank nicht. Der Reverse löst
das über eine **deklarierte Präferenz** (`dialect-preference-mechanism.md`):

| Präferenz | Reverse-Ergebnis | PostgreSQL erzeugt | Note |
|-----------|------------------|--------------------|------|
| `serial` (Default) | `generation: identity` mit `legacy_serial_syntax: true` | `BIGSERIAL` | — |
| `identity` | `generation: identity` ohne das Flag | `BIGINT GENERATED BY DEFAULT AS IDENTITY` | R205 (Bestätigung) |

Deklaration: CLI `--mysql-autoincrement-syntax` bzw. Config
`reverse.mysql.autoincrement_syntax`. Der Default lässt den Reverse-Output
unverändert; MySQL selbst erzeugt aus beiden Formen dieselbe Spalte, und der
Fingerprint bleibt unberührt. `INT AUTO_INCREMENT` trägt kein Flag, die
Präferenz wirkt dort nicht.

### 4.5 Roher Ausdruckstext kommt in neutraler Syntax

CHECK-Ausdruck, Berechnungsausdruck einer Spalte und der Ausdrucks-Schlüssel
eines Index kommen in **neutraler Syntax** ins Modell, nicht in
MySQL-Oberflächensyntax. Der Server gibt sie aus seinem Parsebaum zurück
(`information_schema.CHECK_CONSTRAINTS.CHECK_CLAUSE`,
`COLUMNS.GENERATION_EXPRESSION`, `STATISTICS.EXPRESSION`) und legt dabei drei
Dialekt-Anhänge darüber, die im neutralen Modell nichts zu suchen haben:

| Anhang | Serverform | Neutral |
| --- | --- | --- |
| Zeichensatz-Introducer eines Literals | `_latin1'%@%'` | `'%@%'` |
| Backslash-Escape in einem Literal | `'it\'s'` | `'it''s'` |
| Backtick-Quoting eines Bezeichners | `` `Qty` `` | `"Qty"` (kleingeschrieben: nackt) |

Der Introducer ist der Zeichensatz der Sitzung, die den Ausdruck anlegte —
kein Wert. Ohne ihn zu entfernen liest die Validierung ihn als Spaltenbezug
und weist jedes zurückgelesene MySQL-Schema ab (`E012` am CHECK, `E136` am
Berechnungsausdruck); mit Backticks wäre der Ausdruck außerdem auf jedem
anderen Ziel unportabel (`E053`). Die Regel entspricht Abschnitt 6.2 für SQL
Server; wie dort wird der Ausdruck darüber hinaus **nicht** umgeschrieben.

`information_schema` liefert diese Texte ein zweites Mal escapet, als wären sie
selbst ein String-Literal (aus `'a\b'` wird `\'a\\b\'`). Diese Ebene zieht
der Reverse ab, bevor er die drei Anhänge entfernt; ein Text, der die Form
nicht trägt, gilt als bereits ausgepackt. Ein Steuerzeichen-Escape (`\n`,
`\t`, `\0`) behält seinen Wert: im neutralen Literal steht das Zeichen selbst,
wie es die übrigen vier Dialekte schreiben.

**Eine Quotierung, nicht zwei.** Diese drei Felder führen Bezeichner
ausschließlich in Backticks und Zeichenketten ausschließlich in `'…'` —
unabhängig vom `sql_mode` der Sitzung, die den Ausdruck anlegte, und
unabhängig von dem der Sitzung, die ihn liest. Auch unter `ANSI_QUOTES`
(enthalten in `ANSI`) bleibt es dabei, obwohl derselbe Server dann
Tabellen- und Spaltennamen außerhalb des Ausdrucks mit `"` schreibt. Ein
`"…"`-Lauf in einem dieser Felder ist deshalb keine Zeichenkette; der Reverse
übernimmt ihn wortgleich als neutralen Bezeichner, statt ihn zu einer
Konstanten zu machen.

Der Rückweg gehört zum Generator: er setzt `"…"` wieder in Backticks,
verdoppelt den Backslash und quotiert ein nacktes Wort, das MySQL reserviert
([`ddl-generation-rules.md`](ddl-generation-rules.md), „Roher Ausdruckstext").

---

## 5. SQLite: Bekannte Lücken

### 5.1 Type-Affinity

SQLite hat kein striktes Typsystem — der gespeicherte Typ ist eine
"Affinity" die aus dem deklarierten Typ abgeleitet wird. Das Mapping
parst den deklarierten Typ-String (z.B. `VARCHAR(100)`) und extrahiert
Länge/Precision. Unbekannte Typen fallen auf `Text()`.

### 5.2 Fehlende Typen

| DDL-Typ | Neutral | Regel |
|---------|---------|-------|
| `CLOB` | `text` | — |
| `BLOB` | `binary` | — |
| `NUMERIC`/`DECIMAL` ohne Präzision | `float` | `R221`: das neutrale Modell trägt keine ungebundene Dezimalzahl, und die Spalte rendert als `REAL` zurück. Der Schwesterfall ist beim Erzeugen laut (`W200`, `decimal(p,s)` → `REAL`); mit Präzision und Skala bleibt es `decimal(p,s)` und meldet nichts |

### 5.2a Constraint-Namen: aus dem DDL-Text, sonst gebildet

SQLite führt Constraint-Namen **nicht im Katalog**: `PRAGMA foreign_key_list`
nummeriert die Fremdschlüssel einer Tabelle nur durch, und der Autoindex einer
UNIQUE-Klausel heißt `sqlite_autoindex_<tabelle>_<n>`. Der Name steht
ausschließlich im abgelegten `CREATE TABLE`-Text (`sqlite_master.sql`), und von
dort liest ihn der Reverse — für beide Formen, die SQLite kennt: die Klausel
auf Tabellenebene (`CONSTRAINT <name> FOREIGN KEY (…) REFERENCES …`) und die
an der Spalte (`<spalte> … CONSTRAINT <name> REFERENCES …`).

Wo kein Name steht, bildet der Reverse einen — nach dem Muster, das der
Generator für aufgeschobene Constraints benutzt:

| Constraint | Gebildeter Name |
| --- | --- |
| Fremdschlüssel | `fk_<tabelle>_<spalte>[_<spalte>…]` |
| mehrspaltige UNIQUE-Klausel | `uq_<tabelle>_<spalte>[_<spalte>…]` |

Der gebildete Name gilt **schemaweit**, nicht je Tabelle: PostgreSQL und
SQL Server verlangen Constraint-Namen schemaweit eindeutig, und zwei Tabellen
mit je einer unbenannten Klausel ergäben sonst zweimal denselben. Ein Name aus
dem DDL-Text gewinnt immer; ein gebildeter weicht ihm mit einem Zähler aus
(`fk_t_a_2`), auch wenn der echte Name erst in einer später gelesenen Tabelle
steht. Gekürzt wird auf **63 Zeichen** — die kleinste Bezeichnergrenze der
fünf Ziele (PostgreSQL); so kommt der Name überall unverändert an. Die Vergabe
ist deterministisch: zwei Reverses derselben Datenbank liefern dieselben Namen.
Die Zusage gilt **je Schema**: ändert sich der gelesene Bestand, kann sich ein
Zähler verschieben.

**Zwei echte Namen bleiben zwei echte Namen.** SQLite lässt denselben
Constraint-Namen in zwei Tabellen zu; der Reverse übernimmt beide, wie sie
dastehen, und benennt nicht um. Ein Ziel, das Constraint-Namen schemaweit
eindeutig verlangt, lehnt die erzeugte DDL dann ab — mit seiner eigenen
Meldung. Eindeutig gemacht wird nur, was der Reverse selbst bildet.

**Ein Fremdschlüssel ohne Spaltenliste** (`REFERENCES t` statt
`REFERENCES t(id)`) meint den Primärschlüssel der Zieltabelle; der Reverse
liest ihn von dort. Hat die Zieltabelle keinen, ist die Klausel in SQLite selbst
unbrauchbar (`foreign key mismatch`), und der Lauf endet mit einer Meldung, die
beide Tabellen nennt.

**Kommentare im Tabellentext** gehören zum Text: SQLite speichert ihn
wortgetreu. Die Scanner des Reverse überspringen Zeilen- und Blockkommentare —
sonst läse ein `CHECK` oder ein `AUTOINCREMENT` aus einem Kommentar mit, und
ein Apostroph darin verschöbe die Abgrenzung der Literale.

### 5.3 AUTOINCREMENT-Breite (inhärente Reverse-Mehrdeutigkeit)

`INTEGER PRIMARY KEY AUTOINCREMENT` ist ein 64-bit-Rowid und **speicher-
ununterscheidbar** vom 32-bit-`identifier`-Vertrag (PG `SERIAL`, MySQL `INT
AUTO_INCREMENT`) und von 64-bit `biginteger` + `generation: identity`. SQLite
trägt die Information nicht, die die Wahl entscheiden würde — anders als PG/MySQL,
die per Spaltenbreite (int4/int8) unterscheiden. Der Reverse löst das über eine
**deklarierte Präferenz** (`dialect-preference-mechanism.md`):

| Breite | Reverse-Ergebnis | Note |
|--------|------------------|------|
| `32` (Default) | `identifier` (32-bit-Vertrag) | R202 (Verengungs-Hinweis; rät zur Breite `64` an der Stelle der Deklaration — das Flag, wenn es gesetzt war, sonst der Konfigurationsschlüssel) |
| `64` | `biginteger` + `generation: identity` (`legacySerialSyntax = true`, wie der MySQL-`BIGINT AUTO_INCREMENT`-Reverse → PG `BIGSERIAL`) | R204 (Bestätigung) |

Deklaration: CLI `--sqlite-autoincrement-width` bzw. Config
`reverse.sqlite.autoincrement_width`. Der Default lässt den Reverse-Output
unverändert (keine Regression), der Fingerprint bleibt unberührt.

Unter der Breite `64` stellt sich dieselbe Frage wie bei MySQL (4.4): ob die
Spalte als `SERIAL` oder als IDENTITY gemeint war. Die Präferenz
`--sqlite-autoincrement-syntax` bzw. `reverse.sqlite.autoincrement_syntax`
(`serial` als Default, `identity` ohne `legacySerialSyntax`, bestätigt mit
R205) wirkt nur dort; unter der Breite `32` entsteht keine Identity-Spalte.

---

## 6. MSSQL (SQL Server): Entscheidungen und bekannte Lücken

### 6.1 Forward-Entscheidungen

| Neutraler Typ | T-SQL | Hinweis |
|---|---|---|
| `identifier` (`auto_increment`) | `INT IDENTITY(1,1) NOT NULL` | Seed/Increment immer `(1,1)`; der Reverse meldet abweichende Werte als R340 |
| `biginteger`/`integer`/`smallint` + `generation: identity` | `BIGINT`/`INT`/`SMALLINT IDENTITY(1,1) NOT NULL` | `BY DEFAULT` ist in T-SQL nicht abbildbar (W140: `SET IDENTITY_INSERT`) |
| `text(n)`, `char(n)`, `email` | `NVARCHAR(n)`, `NCHAR(n)`, `NVARCHAR(254)` | Unicode-sicher; > 4000 → `NVARCHAR(MAX)` + W136 |
| `boolean` | `BIT` | Defaults `true`/`false` → `1`/`0` |
| `float` single/double | `REAL`/`FLOAT` | |
| `decimal(p,s)` | `DECIMAL(p,s)` | p > 38 wird auf 38 gekappt (W139) |
| `datetime` / `datetime(timezone)` | `DATETIME2` / `DATETIMEOFFSET` | `current_timestamp` → `CURRENT_TIMESTAMP` bzw. `SYSDATETIMEOFFSET()` (offset-tragend) |
| `date`, `time` | `DATE`, `TIME` | `current_date`/`current_time` → `CAST(GETDATE() AS DATE/TIME)` |
| `uuid` | `UNIQUEIDENTIFIER` | `gen_uuid` → `NEWID()` |
| `json`, `array` | `NVARCHAR(MAX)` | kein nativer Typ (W137); Reverse liest `text` |
| `xml` | `XML` | |
| `binary` | `VARBINARY(MAX)` | |
| `enum` (Werte) | `NVARCHAR(<längster Wert>)` + benannter `CHECK (… IN (…))` | kein Enum-Typ; begrenzte Breite hält die Spalte schlüssel-/indexfähig |
| `fulltext` | `NVARCHAR(MAX)` | W132 |
| `geometry` (Profil `native`) | `geography` bei geodätischem SRID (EPSG-Geographic-Block 4000–4999, z. B. 4326), sonst `geometry` | Subtyp/SRID sind Werteigenschaften (W120); `geography` + 4326 = SQL-Server-Default, keine Warnung |
| `sequence_nextval` | `DEFAULT NEXT VALUE FOR [seq]` | native Sequenzen |

String-Literale in Defaults werden als Unicode-Literal `N'…'` gerendert.

### 6.2 Reverse-Entscheidungen

| T-SQL | Neutral | Hinweis |
|---|---|---|
| `int IDENTITY` | `identifier` (`auto_increment`) | |
| `bigint`/`smallint`/`tinyint`/`decimal` IDENTITY | Basistyp + `generation: identity` (`ALWAYS`) | T-SQL kennt keinen `BY DEFAULT`-Modus |
| `bit` | `boolean` | |
| `money`/`smallmoney` | `decimal(19,4)`/`decimal(10,4)` | |
| `datetime`, `datetime2`, `smalldatetime` | `datetime` | Präzisionsunterschiede werden nicht modelliert |
| `datetimeoffset` | `datetime(timezone)` | Default `sysdatetimeoffset()` → `current_timestamp` (wie `getdate()`) |
| `nvarchar`/`nchar` | Länge in **Zeichen** (`max_length`/2) | `varchar`/`char` in Bytes |
| `geometry` | `geometry` (generisch, ohne SRID) | Subtyp/SRID nicht lesbar (Werteigenschaft) |
| `geography` | `geometry` mit `srid: 4326` | SQL-Server-Default-SRID als Annahme (R345); hält den Round-Trip zur Generate-Regel stabil |
| `xml` | `xml` | |
| `sysname` | `text(128)` | |

**Defaults**: die vier neutralen Funktions-Defaults werden aus ihrer T-SQL-Form
zurückgewonnen — `getdate()`/`sysdatetime()`/`sysdatetimeoffset()` →
`current_timestamp`, `CONVERT([date],getdate())` bzw. `CAST(GETDATE() AS DATE)`
→ `current_date`, dieselbe Form mit `[time]` → `current_time`, `newid()` →
`gen_uuid`. SQL Server speichert nicht die geschriebene, sondern seine eigene
Form (aus `CAST(GETDATE() AS DATE)` wird im Katalog `CONVERT([date],getdate())`),
deshalb sind beide Schreibweisen abgedeckt. Ein nicht erkannter Funktions-Default
bleibt als Text stehen und wird beim Round-Trip über das neutrale Format zum
String-Literal — das neutrale Modell kennt nur diese vier als Funktion.

**Roher Ausdruckstext** kommt in **neutraler Syntax** ins Modell, nicht in
T-SQL-Oberflächensyntax — für den **CHECK-Ausdruck**
(`sys.check_constraints.definition`), den **Berechnungsausdruck einer
Spalte** (`sys.computed_columns.definition`) und das **Prädikat eines
gefilterten Index** (`sys.indexes.filter_definition`) nach derselben Regel: der
Unicode-Literal-Präfix `N'…'` entfällt (er ist Syntax, kein Wert),
Klammer-Quoting `[col]` wird zum unquotierten Namen bzw. — wo der Name Quoting
braucht — zum ANSI-Doppelquote `"col"`, und die äußere Klammer, die der Server
um jeden gespeicherten Ausdruck legt, fällt weg (der Generator setzt die
Klammern, die sein Dialekt braucht). Der Ausdruck wird darüber hinaus nicht
umgeschrieben. Ohne diese Normalisierung liest die Validierung das `N` als
Spaltenbezug (E012) und jedes andere Ziel scheitert am T-SQL-Quoting —
gemessen: PostgreSQL mit `syntax error at or near "["`, MySQL mit
`ERROR 1064`.

Die **Erkennung der Hash-Partitions-Emulation** liest denselben Katalogtext
weiter in Serverform: sie hängt an der Abfrage, nicht am Modell.

### 6.3 Bekannte Lücken

- `hierarchyid`, `sql_variant`, `rowversion`/`timestamp` und CLR-UDTs fallen
  auf `Text()` + R301.
- Computed Columns kommen als berechnete Spalten zurueck
  (`generation.type: computed`), in allen fuenf Dialekten und mit ihrer
  Speicherform. Der Ausdruck ist die **Serverform**, nicht der Autorentext — bis
  auf die Dialekt-Anhaenge, die der Reverse entfernt (Abschnitt 6.2 fuer SQL
  Server, 4.5 fuer MySQL) —, und wird deshalb nur unter den Bedingungen aus
  [`schema-reference.md`](schema-reference.md) verglichen.
  Gemeldet wird nur noch, was der Server nicht hergibt:
  `R343`, wenn die Spalte ohne ihren Ausdruck kommt;
  `R367`, wenn SQLite die Spalte in `PRAGMA table_info` ausblendet **und** der
  abgelegte `CREATE TABLE`-Text den Ausdruck nicht hergibt — dann fehlt die
  Spalte im Modell, nicht nur ihre Berechnung;
  `R369`, wenn Oracle die Einordnung materialisiert/Default nicht entscheidbar
  macht — ohne `DBMS_METADATA.GET_DDL` steht eine materialisierte Spalte im
  Katalog wie eine gewoehnliche mit `DEFAULT`.
- Collations werden nicht modelliert (Scoping-Entscheidung).

### 6.4 Spatial: `geometry` vs. `geography`

SQL Server hat zwei Spatial-Typen: **`geometry`** (planares Koordinatensystem)
und **`geography`** (geodätisch, Ellipsoid — Längen-/Breitengrad). Das
neutrale Modell kennt nur `geometry` mit optionalem `srid`; die Wahl des
T-SQL-Typs fällt deshalb über den SRID:

| Neutral (`type: geometry`) | T-SQL | Begründung |
|---|---|---|
| `srid` im EPSG-Geographic-Block **4000–4999** (z. B. **4326** WGS 84, 4258 ETRS89, 4269 NAD83) | `geography` | geodätisches Referenzsystem; SQL Server rechnet Distanzen/Flächen auf dem Ellipsoid |
| `srid` außerhalb (projiziert, z. B. 3857 Web Mercator, 25832 UTM 32N) | `geometry` | planares System |
| kein `srid` | `geometry` | ohne Referenzsystem gibt es keine geodätische Interpretation |

Konsequenzen:

- Subtyp (`geometry_type`) und SRID sind in SQL Server Eigenschaften des
  Werts, nicht der Spalte. Ein Subtyp oder ein SRID abseits des
  `geography`-Defaults 4326 wird nicht spaltenseitig erzwungen → W120;
  `geography` mit SRID 4326 und generischem Subtyp bleibt ohne Warnung.
- Reverse: `geography` → `geometry` mit `srid: 4326` (SQL-Server-Default,
  Hinweis R345), `geometry` → `geometry` ohne SRID. Damit ist der Round-Trip
  `srid: 4326 → geography → srid: 4326` stabil; andere geodätische SRIDs
  (z. B. 4258) kommen als 4326 zurück — sichtbar über R345.
- Räumliche Indizes: auf `geography` wird `CREATE SPATIAL INDEX` gerendert,
  auf planarem `geometry` nicht (E057, BOUNDING_BOX nötig) — Details in
  `ddl-generation-rules.md`, Abschnitt Spatial (MSSQL).
- Die Schwelle ist eine Konstante (`MssqlTypeMapper.GEODETIC_SRID_RANGE`);
  ESRI-Geographic-Codes (104xxx) gelten derzeit als planar.
- **Datenpfad (`data export`/`import`/`transfer`)**: Werte werden als WKB
  gelesen (`.STAsBinary()`) und als `geometry::STGeomFromWKB(?, srid)` bzw.
  `geography::STGeomFromWKB(?, srid)` geschrieben. WKB trägt keine SRID, und
  SQL Server führt sie am Wert statt an der Spalte — es gibt also keine
  Spaltenmetadaten, aus denen sie käme. `data transfer` liest sie deshalb aus
  den **Werten** der Quelle (`.STSrid`, ein `DISTINCT` je Geometriespalte)
  und bindet sie am Ziel. Führt eine Spalte Werte in mehr als einem
  Bezugssystem, bricht der Transfer im Preflight ab: das Ziel trägt eine SRID
  je Spalte, jede Wahl verschöbe einen Teil der Werte unsichtbar.
  Der Spalten-Default (0 für `geometry`, 4326 für `geography`) gilt nur noch,
  wo die Quelle gar keine Angabe hat — bei `data import` aus einer Datei, die
  kein Quellschema mitführt.

---

## 7. Oracle: Entscheidungen und bekannte Lücken

### 7.1 Forward-Entscheidungen

| Neutraler Typ | Oracle-DDL | Hinweis |
|---|---|---|
| `identifier` (`auto_increment`) | `NUMBER(9) GENERATED ALWAYS AS IDENTITY` | Symmetrisch zum Reverse (`precision <= 9 -> integer`) |
| `integer`/`smallint`/`biginteger` + `generation: identity` | `NUMBER(9)`/`NUMBER(4)`/`NUMBER(18) GENERATED ALWAYS/BY DEFAULT AS IDENTITY` | Oracle kennt (anders als MSSQL) `BY DEFAULT` nativ |
| `text(n)`, `char(n)` | `VARCHAR2(n)`, `CHAR(n)` | > 4000 bzw. > 2000 Byte → `CLOB` (W145) |
| `boolean` | `NUMBER(1)` | Oracles 0/1-Konvention; Defaults `true`/`false` → `1`/`0` |
| `float` single/double | `BINARY_FLOAT`/`BINARY_DOUBLE` | |
| `decimal(p,s)` | `NUMBER(p,s)` | p > 38 wird auf 38 gekappt (W148) |
| `datetime` / `datetime(timezone)` | `DATE` / `TIMESTAMP WITH TIME ZONE` | `current_timestamp` → `SYSDATE` bzw. `SYSTIMESTAMP` (zonentragend) |
| `date` | `DATE` | Oracle `DATE` trägt immer eine Uhrzeit (W147, INFO) |
| `time` | `VARCHAR2(8)` (`HH24:MI:SS`-Text) | kein nativer Zeit-Typ ohne Datum (W146) |
| `uuid` | `VARCHAR2(36)` | `gen_uuid` → `RAWTOHEX(SYS_GUID())` (W150: 32 Hex-Zeichen ohne Bindestriche, INFO) |
| `json` | `JSON` | nativer Oracle-21c+-Typ, kein Text-Fallback |
| `xml` | `XMLTYPE` | nativer Typ |
| `array` | `JSON` | kein nativer Array-Typ (W149); Werte als JSON-Array |
| `binary` | `BLOB` | |
| `enum` (Werte) | `VARCHAR2(<längster Wert>)` + benannter `CHECK (… IN (…))` | kein Enum-Typ; `refType` auf eine `DOMAIN` faltet auf `CLOB` + E053 (Basistyp-Auflösung noch nicht gebaut) |
| `fulltext` | `CLOB` | W132 (geteilter Cross-Dialekt-Pool) |
| `sequence_nextval` | `DEFAULT <seq>.NEXTVAL` | native Sequenzen |
| `geometry` (Profil `native`) | `SDO_GEOMETRY` | ein Typ für alle Subtypen; Subtyp und SRID sind Werteigenschaften (W120), siehe [DDL-Regeln §16.10](ddl-generation-rules.md) |

String-Literale in Defaults werden mit `''`-Escaping als `'…'` gerendert
(kein `N'…'`-Präfix wie bei MSSQL — Oracle kennt ihn nicht). `ON DELETE`
kennt nur `CASCADE`/`SET NULL`; `RESTRICT`/`NO_ACTION` entsprechen dem
Oracle-Default (keine Klausel) und werden ohne Notiz weggelassen,
`SET_DEFAULT` hat kein Äquivalent und wird verworfen (W153).

### 7.2 Reverse-Entscheidungen

| Oracle | Neutral | Hinweis |
|---|---|---|
| `NUMBER` GENERATED ALWAYS/BY DEFAULT AS IDENTITY | Basistyp (`smallint`/`integer`/`biginteger`/`decimal`) + `generation: identity` | `ALL_TAB_IDENTITY_COLS.SEQUENCE_NAME` liefert den echten Sequenznamen (höhere Fidelity als MSSQLs unbenannte IDENTITY) |
| `NUMBER(1)` (nicht identity) | `boolean` | Oracles 0/1-Konvention, analog MySQL `tinyint(1)` |
| `NUMBER` (kein Precision/Scale) | `decimal(38,10)` | konservativ: eine ungebundene NUMBER kann Ganz- oder Bruchzahlen tragen |
| `VARCHAR2`/`NVARCHAR2` | Länge in Zeichen | keine Byte/Unicode-Aufspaltung wie bei MSSQL |
| `DATE` | `datetime(timezone=false)` | trägt eine Uhrzeitkomponente |
| `TIMESTAMP [WITH [LOCAL] TIME ZONE]` | `datetime` bzw. `datetime(timezone=true)` | |
| `JSON` | `json` | nativer Oracle-21c+-Typ |
| `XMLTYPE` | `xml` | nativer Typ |
| `sysdate`/`systimestamp` (Default) | `current_timestamp` | kanonisiert für Cross-Dialekt-Portabilität (lowercase, wie bei MySQL/MSSQL) |
| `TRUNC(SYSDATE)` (Default) | `current_date` | |
| `TO_CHAR(SYSDATE, 'HH24:MI:SS')` (Default) | `current_time` | |
| `RAWTOHEX(SYS_GUID())` (Default) | `gen_uuid` | |
| `<seq>.NEXTVAL` (Default) | `sequence_nextval` | |
| `NUMBER` (kein Precision/Scale), nicht identity | `decimal(38,10)` + `R371` | konservativ, und der Verlust wird benannt: mehr als zehn Nachkomma- und mehr als 28 Vorkommastellen gehen auf dem Rückweg verloren. Eine `NUMBER`-Identity ohne Präzision liest `biginteger` und meldet nichts |
| `SDO_GEOMETRY` | `geometry` ohne Subtyp | SRID aus `ALL_SDO_GEOM_METADATA`, sofern eine Zeile mit exakt passendem Tabellen- und Spaltennamen existiert; sonst ohne SRID (R365, wenn die Sicht nicht lesbar ist) |

**Datenpfad (`data export`/`import`/`transfer`)**: Oracle-JDBC liefert
`CLOB`/`BLOB`-Spalten über `getObject()` als live `java.sql.Clob`/
`java.sql.Blob`-Locator statt als materialisierten `String`/`ByteArray` —
anders als die anderen vier Dialekte. Der Reader materialisiert deshalb
sofort beim Lesen, während der Cursor noch auf der Zeile steht; ein Locator,
der die Chunk-Grenze überlebt, wäre gegen einen fremden Ziel-Treiber nicht
mehr sicher bindbar. `TIMESTAMP WITH TIME ZONE` liest als Standard-
`OffsetDateTime`.

### 7.3 Bekannte Lücken

- **PL/SQL-Packages** werden nicht gelesen; vorhandene Packages erscheinen
  als `skippedObjects` + R342-Notiz. Funktionen, Prozeduren und Trigger
  dagegen schon.
- `ALL_SEQUENCES` führt nur `LAST_NUMBER`, nicht den ursprünglichen
  `START WITH`-Wert (R345).
- **Volltext- und räumliche Indizes** werden gelesen: Oracle führt beide als
  `INDEX_TYPE = DOMAIN` und unterscheidet sie am Indextyp-Eigner
  (`CTXSYS.CONTEXT` bzw. `MDSYS.SPATIAL_INDEX_V2` und der Vorgänger
  `MDSYS.SPATIAL_INDEX`); `ALL_IND_COLUMNS` nennt die echte Quellspalte. Ein
  Domain-Index einer **anderen** Indexart (benutzereigen) wird ausgelassen und
  mit `R357` gemeldet — ihn als B-Tree zu lesen ergäbe im Ziel einen Index,
  der etwas anderes tut.
- Indizes über einem echten Ausdruck (`UPPER(nm)`) kommen als
  Ausdrucks-Schlüssel zurück (`ALL_IND_EXPRESSIONS`), nicht als die
  unsichtbare Systemspalte. Bitmap-Indizes werden als eigener Typ gelesen
  (`INDEX_TYPE` enthält `BITMAP`), und ein absteigender Index — in Oracle
  intern ebenfalls function-based — wird auf seine Spalte zurückgefaltet.
- **INTERVAL-Partitionierung** (`PARTITION BY RANGE … INTERVAL (…)`) hat im
  neutralen Modell keine Entsprechung: gelesen werden nur die heute
  vorhandenen Partitionen, gemeldet mit `R355`. Eine wieder erzeugte Tabelle
  legt keine neuen Partitionen mehr selbsttätig an.
- **Composite-Partitionierung** (`SUBPARTITION BY …`) wird nur auf der
  obersten Ebene gelesen; die Subpartitionen fallen weg → `R356`.
- **HASH-Partitionen** tragen weder Modulus noch Remainder — Oracle führt sie
  nicht (`ALL_TAB_PARTITIONS.HIGH_VALUE` ist dort `NULL`). Der
  Fingerabdruck blendet beide Felder für Oracle aus, sonst meldete jeder
  Round-Trip Drift.
- `CHAR(1)` faltet **nicht** auf `boolean` (anders als `NUMBER(1)`): kein
  ebenso enges Signal, ein Einzelzeichen trägt oft einen echten
  Status-/Kategorie-Code.
- `NUMBER(p,0)` faltet unabhängig von seiner Herkunft auf
  `smallint`/`integer`/`biginteger` (wie bei `identifier`/Identity) —
  Oracles eigene Konvention, eine gebundene Ganzzahl-`NUMBER` ohne Skala
  als Integer-Typ zu lesen. Ein generiertes `decimal(p,0)` kommt beim
  Rückweg deshalb nicht als `decimal`, sondern als Integer-Typ zurück.

---

## 8. Reverse-Mapping else-Fallback

Alle fünf Reverse-Mapper haben einen `else`-Fallback:

```kotlin
else -> MappingResult(
    NeutralType.Text(),
    SchemaReadNote(WARNING, "R301", ..., "Unknown type '$dt' mapped to text"),
)
```

Das ist **bewusst und fachlich nötig** — Datenbanken können beliebige
Typ-Strings liefern (Extensions, benutzerdefinierte Typen, neue
Versionsfeatures). Der Fallback erzeugt immer eine diagnostische
Warning-Note damit der Nutzer die Zuordnung reviewen kann.

Die Regel gilt **nicht nur an der Spalte**. Sie gilt an jeder Stelle, an der
ein Typname auf einen neutralen Typ abgebildet wird und ein Rest übrig bleibt:
für die **Elementart eines Arrays** und für den **Feldtyp eines
zusammengesetzten Typs** genauso. Wo der Fallback greift, steht `text` im
Modell, und der Bericht nennt die Stelle.

---

## 9. Offene Verbesserungen

| # | Beschreibung | Priorität | Aufwand |
|---|-------------|-----------|---------|
| 1 | PG Extension-Typen Allowlist (`citext`, `ltree`, `hstore`) | P2 | S |
| 2 | PG Generated Columns erkennen | P3 | M |
| 3 | MySQL SET → strukturiertes Modell statt Text-Fallback | P3 | M |
| 4 | SQLite Type-Affinity-Warnung bei unbekannten Typen | P3 | S |
