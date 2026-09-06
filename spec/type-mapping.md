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

| Typ | Ab PG-Version | Status |
|-----|---------------|--------|
| `jsonb` | 9.4 | ✅ Gemappt als `Json` |
| `uuid` | 8.3 (als Extension), nativ ab 13 | ✅ Gemappt als `Uuid` |
| `generated always as (...)` | 12 | ❌ Nicht erkannt |
| `multirange` | 14 | ❌ Nicht erkannt |

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

---

## 5. SQLite: Bekannte Lücken

### 5.1 Type-Affinity

SQLite hat kein striktes Typsystem — der gespeicherte Typ ist eine
"Affinity" die aus dem deklarierten Typ abgeleitet wird. Das Mapping
parst den deklarierten Typ-String (z.B. `VARCHAR(100)`) und extrahiert
Länge/Precision. Unbekannte Typen fallen auf `Text()`.

### 5.2 Fehlende Typen

| DDL-Typ | Aktuell | Korrekt |
|---------|---------|---------|
| `CLOB` | `Text()` ✅ | — |
| `BLOB` | `Binary` ✅ | — |
| `NUMERIC` ohne Precision | `Float()` | Akzeptabel |

### 5.3 AUTOINCREMENT-Breite (inhärente Reverse-Mehrdeutigkeit)

`INTEGER PRIMARY KEY AUTOINCREMENT` ist ein 64-bit-Rowid und **speicher-
ununterscheidbar** vom 32-bit-`identifier`-Vertrag (PG `SERIAL`, MySQL `INT
AUTO_INCREMENT`) und von 64-bit `biginteger` + `generation: identity`. SQLite
trägt die Information nicht, die die Wahl entscheiden würde — anders als PG/MySQL,
die per Spaltenbreite (int4/int8) unterscheiden. Der Reverse löst das über eine
**deklarierte Präferenz** (`dialect-preference-mechanism.md`):

| Breite | Reverse-Ergebnis | Note |
|--------|------------------|------|
| `32` (Default) | `identifier` (32-bit-Vertrag) | R202 (Verengungs-Hinweis, nennt den Flag) |
| `64` | `biginteger` + `generation: identity` (`legacySerialSyntax = true`, wie der MySQL-`BIGINT AUTO_INCREMENT`-Reverse → PG `BIGSERIAL`) | R204 (Bestätigung) |

Deklaration: CLI `--sqlite-autoincrement-width` bzw. Config
`reverse.sqlite.autoincrement_width`. Der Default lässt den Reverse-Output
unverändert (keine Regression), der Fingerprint bleibt unberührt.

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

**CHECK-Ausdrücke** kommen in **neutraler Syntax** ins Modell, nicht in
T-SQL-Oberflächensyntax: der Unicode-Literal-Präfix `N'…'` entfällt (er ist
Syntax, kein Wert) und Klammer-Quoting `[col]` wird zum unquotierten Namen bzw.
— wo der Name Quoting braucht — zum ANSI-Doppelquote `"col"`. Der Ausdruck wird
darüber hinaus nicht umgeschrieben. Ohne diese Normalisierung liest die
Validierung das `N` als Spaltenbezug (E012) und jedes andere Ziel scheitert am
T-SQL-Quoting.

### 6.3 Bekannte Lücken

- `hierarchyid`, `sql_variant`, `rowversion`/`timestamp` und CLR-UDTs fallen
  auf `Text()` + R301.
- Computed Columns werden als normale Spalten gelesen (R343).
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
  SQL Server führt sie am Wert statt an der Spalte — beim Schreiben gilt
  deshalb 0 (`geometry`) bzw. 4326 (`geography`). Abweichende Wert-SRIDs
  gehen dabei verloren.

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

**Datenpfad (`data export`/`import`/`transfer`)**: Oracle-JDBC liefert
`CLOB`/`BLOB`-Spalten über `getObject()` als live `java.sql.Clob`/
`java.sql.Blob`-Locator statt als materialisierten `String`/`ByteArray` —
anders als die anderen vier Dialekte. Der Reader materialisiert deshalb
sofort beim Lesen, während der Cursor noch auf der Zeile steht; ein Locator,
der die Chunk-Grenze überlebt, wäre gegen einen fremden Ziel-Treiber nicht
mehr sicher bindbar. `TIMESTAMP WITH TIME ZONE` liest als Standard-
`OffsetDateTime`.

### 7.3 Bekannte Lücken

- Routinen, Trigger und Packages werden nicht gelesen; vorhandene
  Objekte erscheinen als `skippedObjects` + R342-Notiz.
- Materialized Views werden als reguläre Views gelesen.
- `ALL_SEQUENCES` führt nur `LAST_NUMBER`, nicht den ursprünglichen
  `START WITH`-Wert (R345).
- Indizes über einem echten Ausdruck (`UPPER(nm)`) haben im neutralen
  Modell keine Entsprechung; sie werden ausgelassen und mit `R354`
  gemeldet. Bitmap-Indizes dagegen werden als eigener Typ gelesen
  (`INDEX_TYPE` enthält `BITMAP`), und ein absteigender Index — in Oracle
  intern ebenfalls function-based — wird auf seine Spalte zurückgefaltet.
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

---

## 9. Offene Verbesserungen

| # | Beschreibung | Priorität | Aufwand |
|---|-------------|-----------|---------|
| 1 | PG Extension-Typen Allowlist (`citext`, `ltree`, `hstore`) | P2 | S |
| 2 | PG Generated Columns erkennen | P3 | M |
| 3 | MySQL SET → strukturiertes Modell statt Text-Fallback | P3 | M |
| 4 | SQLite Type-Affinity-Warnung bei unbekannten Typen | P3 | S |
