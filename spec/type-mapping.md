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
**deklarierte Präferenz** (`reverse-preference-mechanism.md`):

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

---

## 7. Reverse-Mapping else-Fallback

Alle vier Reverse-Mapper haben einen `else`-Fallback:

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

## 8. Offene Verbesserungen

| # | Beschreibung | Priorität | Aufwand |
|---|-------------|-----------|---------|
| 1 | PG Extension-Typen Allowlist (`citext`, `ltree`, `hstore`) | P2 | S |
| 2 | PG Generated Columns erkennen | P3 | M |
| 3 | MySQL SET → strukturiertes Modell statt Text-Fallback | P3 | M |
| 4 | SQLite Type-Affinity-Warnung bei unbekannten Typen | P3 | S |
