# `migrate --execute` Post-Compare-Drift-False-Positive durch SQLite-Typ-Abflachung

> Status: **Vorabklärung** (Befund live belegt, Lösungsrichtung skizziert, kein Scope-Schnitt).
> Trigger: Live-Verifikation des Fulltext-Rebuild-Blocks
> ([`../done/sqlite-fulltext-rebuild-block.md`](../done/sqlite-fulltext-rebuild-block.md)),
> 2026-07-02 — die erste Testschema-Variante driftete wegen einer `smallint`-Spalte,
> nicht wegen des Fulltext-Index.
> Aktivierungsbedingung: sobald drift-freie frische `migrate --execute`-Läufe auf SQLite
> für realistische Schemata gebraucht werden (jedes Schema mit `boolean`/`datetime`
> trifft den Befund) oder der nächste RC-Zyklus die Exit-Semantik härtet.

## Befund (live belegt, 2026-07-02)

Ein **frisches** `migrate --execute` gegen ein leeres SQLite-Ziel endet mit **Exit 5**
(„Post-execute compare detected drift"), sobald das Soll-Schema einen Typ enthält, den
[`SqliteTypeMapper`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt)
auf eine SQLite-Storage-Klasse abflacht — obwohl der Apply sauber durchläuft
(`execution.completed = true`, `executionError = null`, keine Diagnostics).

Probe-Matrix (Ein-Spalten-Schemata, Runtime-Image, jeweils frisches File-Target):

| Spaltentyp | Exit | Abflachung |
| ---------- | ---- | ---------- |
| `text` (Kontrollfall) | 0 | `TEXT` → `Text`, verlustfrei |
| `smallint` | 5 | `INTEGER` → Reverse liest `integer` |
| `biginteger` | 5 | `INTEGER` → Reverse liest `integer` |
| `boolean` | 5 | `INTEGER` → Reverse liest `integer` |
| `datetime` | 5 | `TEXT` → Reverse liest `text` |
| `decimal(10,2)` | 5 | `REAL` → Reverse liest `float` |

Mechanik: Generate flacht den Neutraltyp ab (`smallint`/`biginteger`/`boolean` → `INTEGER`,
`datetime`/`date`/`time`/`uuid`/`json`/… → `TEXT`, `decimal` → `REAL`), der Reverse liest den
deklarierten Storage-Typ zurück, und der Post-Compare vergleicht Neutraltypen wörtlich →
Drift-False-Positive. Der Fehler ist **loud** (kein stiller Verlust), aber ein
Korrektheitsdefekt der Exit-Semantik: ein spec-valides Schema kann auf SQLite nie
drift-frei frisch migriert werden.

Nur SQLite ist belegt; MySQL/PG sind auf analoge Abflachungskanten (z. B. MySQL
`boolean`→`TINYINT(1)`) noch zu proben.

## Lösungsrichtung (Skizze)

Dialektbewusste Typ-Kanonisierung im Post-Compare: zwei Neutraltypen gelten als äquivalent,
wenn sie im **Ziel-Dialekt** auf denselben Typ abbilden — genau das Substrat, das der
M2-Preflight-Fix bereits als `StructuralTransferTypeCompatibility` in ports-common etabliert
hat (`normalize(toSql(X)) == normalize(toSql(Y))`). Präzedenz für Fingerprint-Kanonisierung:
[`../done/migrate-postcompare-identifier-pk-drift.md`](../done/migrate-postcompare-identifier-pk-drift.md)
(Fingerprint v3, impliziter `identifier`-PK). Vertragsänderung ⇒ Fingerprint-Version-Bump.

## Nicht-Scope

- Kein neues Typ-Mapping (die Abflachung selbst ist korrekt und gewollt, SQLite hat nur
  vier Storage-Klassen).
- Kein Reverse-„Raten" des Ursprungstyps aus Werten.
