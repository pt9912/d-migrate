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

---

## 2. String-Typen: Längenerhaltung

### Neutrales Modell

```kotlin
NeutralType.Text(maxLength: Int? = null)  // VARCHAR(n) oder TEXT
NeutralType.Char(length: Int)             // CHAR(n)
```

`maxLength = null` bedeutet unbegrenzter Text (`TEXT`).

### Reverse-Mapping (DB → Neutral)

| DB-Typ | PostgreSQL | MySQL | SQLite |
|--------|-----------|-------|--------|
| `VARCHAR(n)` | `Text(maxLength=n)` ✅ | `Text(maxLength=n)` ✅ | `Text(maxLength=n)` ✅ |
| `CHAR(n)` | `Char(length=n)` ✅ | `Char(length=n)` ✅ | `Char(length=n)` ✅ |
| `TEXT` | `Text()` ✅ | `Text()` ✅ | `Text()` ✅ |
| `MEDIUMTEXT` | — | `Text()` ✅ | — |
| `LONGTEXT` | — | `Text()` ✅ | — |
| `TINYTEXT` | — | `Text()` ✅ | — |

### Forward-Mapping (Neutral → SQL)

| Neutraler Typ | PostgreSQL | MySQL | SQLite |
|---------------|-----------|-------|--------|
| `Text(maxLength=100)` | `VARCHAR(100)` | `VARCHAR(100)` | `VARCHAR(100)` |
| `Text()` | `TEXT` | `TEXT` | `TEXT` |
| `Char(length=36)` | `CHAR(36)` | `CHAR(36)` | `CHAR(36)` |

Länge geht in keiner Richtung verloren.

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

---

## 6. Reverse-Mapping else-Fallback

Alle drei Reverse-Mapper haben einen `else`-Fallback:

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

## 7. Offene Verbesserungen

| # | Beschreibung | Priorität | Aufwand |
|---|-------------|-----------|---------|
| 1 | PG Extension-Typen Allowlist (`citext`, `ltree`, `hstore`) | P2 | S |
| 2 | PG Generated Columns erkennen | P3 | M |
| 3 | MySQL SET → strukturiertes Modell statt Text-Fallback | P3 | M |
| 4 | SQLite Type-Affinity-Warnung bei unbekannten Typen | P3 | S |
