# Gap-Analyse: `ddl-generation-rules.md` gegen Implementierungsstand

**Stand:** 2026-05-02  
**Status:** Planung / Scope-Klaerung

Dieses Dokument haelt Punkte fest, die in der DDL-Zielbild-Spezifikation
[`spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md)
beschrieben sind, aber nach aktuellem Implementierungsstand nicht oder nur als
spaeterer Scope getragen werden.

## Ergebnis

Diese Analyse identifiziert aktuell **Partial Indexes / Partial-UNIQUE**
als einzigen fachlich relevanten Gap ohne explizite Deferred-Entscheidung.
Die Einstufung sollte bei Aenderungen an `ddl-generation-rules.md` erneut
geprueft werden.

Zwei weitere Punkte sind bereits in der Spezifikation selbst oder in
Planungsdokumenten als spaeterer Scope markiert:

- `ddl.quote_identifiers: reserved_only`
- diff-basierter `schema migrate`-/ALTER-/SQLite-Rebuild-Pfad

## 1. Partial Indexes und Partial-UNIQUE

### Zielbild

[`ddl-generation-rules.md` §5.4](../../spec/ddl-generation-rules.md) beschreibt
Partial Indexes fuer PostgreSQL:

```sql
CREATE INDEX "idx_active_orders" ON "orders" ("status") WHERE "status" != 'cancelled';
```

[`lastenheft-d-migrate.md` §8.7](../../spec/lastenheft-d-migrate.md) nennt
Partial Indexes mit WHERE-Klauseln als PostgreSQL-spezifischen Testfall.

### Aktueller Implementierungsstand

Partial Indexes werden aktuell nicht modelliert.

- [`IndexDefinition`](../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/IndexDefinition.kt) enthaelt nur `name`, `columns`, `type`, `unique`.
- Die Schema-Referenz und das JSON-Schema dokumentieren kein `where`-, `predicate`- oder `filter`-Feld fuer Indizes.
- PostgreSQL erzeugt Index-DDL ohne `WHERE`-Klausel.
- MySQL und SQLite erzeugen ebenfalls nur normale Indizes oder behandeln nicht unterstuetzte Index-Typen mit `W102`.
- Reverse Engineering kann Partial-Index-Praedikate derzeit nicht informationsbewahrend rekonstruieren.

### Risiko

Partial-UNIQUE darf nicht still auf einen normalen Unique-Index reduziert
werden. Das waere semantisch strenger als die Quelle und kann valide Daten im
Zielsystem blockieren.

Beispiel:

```yaml
indices:
  - name: uq_active_email
    columns: [email]
    unique: true
    where: "deleted_at IS NULL"
```

Ein Fallback auf `CREATE UNIQUE INDEX ... (email)` waere falsch, weil dann auch
geloschte/inaktive Zeilen eindeutig sein muessten.

### Empfehlung

Ein additives Feld auf `IndexDefinition` einfuehren:

```kotlin
data class IndexDefinition(
    val name: String? = null,
    val columns: List<String>,
    val type: IndexType = IndexType.BTREE,
    val unique: Boolean = false,
    val where: String? = null,
)
```

YAML-Beispiel:

```yaml
indices:
  - name: uq_active_email
    columns: [email]
    unique: true
    where: "deleted_at IS NULL"
```

Semantik:

| Ziel | Nicht-unique Partial Index | Partial-UNIQUE |
|---|---|---|
| PostgreSQL | `CREATE INDEX ... WHERE ...` | `CREATE UNIQUE INDEX ... WHERE ...` |
| MySQL | `action_required` oder bewusst lossy `CREATE INDEX` + `W102` | `action_required`, kein voller Unique-Fallback |
| SQLite | optional native Partial Index-Unterstuetzung pruefen, sonst `action_required` | optional native Partial Unique Index-Unterstuetzung pruefen, sonst `action_required` |

Hinweis: SQLite unterstuetzt Partial Indexes in modernen Versionen. Wenn
SQLite-Zielversionen nicht explizit gemanagt werden, sollte der erste Schritt
konservativ bleiben und nicht still voraussetzen, dass jedes Zielsystem die
Klausel akzeptiert.

### Umsetzungsschnitt

Betroffene Bereiche:

- Core-Modell: `IndexDefinition.where`
- YAML/JSON Schema: Lesen, Schreiben, Validierung
- Schema-Diff: `where` als Index-Eigenschaft vergleichen
- PostgreSQL-Generator: `WHERE <predicate>` an Index-DDL anhaengen
- MySQL-/SQLite-Generatoren: klare `action_required`-/Warnstrategie
- Reverse Engineering: PostgreSQL `pg_indexes.indexdef` oder Katalogfelder fuer Praedikat auswerten
- Tests: Golden Master, Generator-Unit-Tests, Reverse-Tests fuer `unique + where`

Offene Entscheidung:

- Feldname `where` ist YAML-nah und SQL-verstaendlich.
- Feldname `predicate` ist modellneutraler, aber weniger direkt fuer Anwender.
- Empfehlung: `where`, weil die Spezifikation bereits von WHERE-Klauseln spricht.

## 2. `reserved_only` Identifier-Quoting

### Zielbild

[`ddl-generation-rules.md` §2.2](../../spec/ddl-generation-rules.md) beschreibt
eine spaetere Option:

```yaml
ddl:
  quote_identifiers: reserved_only
```

### Aktueller Stand

Nicht implementiert und in der Spezifikation bereits als spaeterer Milestone
markiert. Der aktuelle Vertrag ist defensives Always-Quote.

### Bewertung

Kein ungeplanter Gap. Die Spezifikation ist hier konsistent, weil sie den
aktuellen Stand explizit abgrenzt.

Empfehlung: Erst umsetzen, wenn ein konkreter Bedarf fuer unquoted DDL besteht.
Das Feature braucht reservierte-Wort-Listen pro Dialekt und DB-Version und ist
daher deutlich wartungsintensiver als der Nutzen im Normalfall.

## 3. Diff-basierter `schema migrate`-/ALTER-/SQLite-Rebuild-Pfad

### Zielbild

[`ddl-generation-rules.md` §3.6`-`§3.7](../../spec/ddl-generation-rules.md)
beschreibt ALTER-Statements und SQLite-Rebuilds fuer einen spaeteren
`schema migrate`-Pfad.

### Aktueller Stand

Nicht Teil des aktuellen CLI-Funktionsumfangs. Das ist in mehreren Dokumenten
explizit abgegrenzt:

- [`cli-spec.md` §7](../../spec/cli-spec.md)
- [`implementation-plan-0.5.0.md`](./implementation-plan-0.5.0.md)
- [`implementation-plan-0.7.0.md`](./implementation-plan-0.7.0.md)

### Bewertung

Kein ungeplanter Gap. Das ist bewusst deferred und sollte nicht im Rahmen von
Partial-Index-Support mit umgesetzt werden.

## 4. Bereits implementiert oder plausibel getragen

Folgende Punkte aus `ddl-generation-rules.md` wirken nach Stichprobe nicht wie
offene Zielbild-Gaps:

- normale Unique-Constraints und Unique-Indizes
- Custom Types fuer PostgreSQL: Enum, Composite, Domain
- Materialized Views fuer PostgreSQL
- W102 fuer nicht unterstuetzte Index-Typen in MySQL/SQLite
- Circular-FK-Nachzuegler via `ALTER TABLE ADD CONSTRAINT`
- Spatial-Profile und `action_required`-Blockierung
- MySQL Named-Sequence-Emulation ueber `--mysql-named-sequences helper_table`
- phasenbezogene DDL-Ordnung mit `DdlPhase` und `--split pre-post`

## Vorschlag fuer naechsten Umsetzungsscope

1. Partial-Index-Modell einfuehren (`where`).
2. PostgreSQL-Generator und PostgreSQL-Reverse sauber implementieren.
3. Partial-UNIQUE fuer nicht unterstuetzte Dialekte als `action_required` behandeln.
4. `ddl-generation-rules.md`, `neutral-model-spec.md`, `schema-reference.md` und `schema.json` synchronisieren.
5. Danach entscheiden, ob SQLite Partial Indexes als natives Ziel explizit unterstuetzt werden.
