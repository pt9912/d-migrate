# Gap-Analyse: `ddl-generation-rules.md` gegen Implementierungsstand

**Stand:** 2026-05-02  
**Status:** Planung / Scope-Klaerung

Dieses Dokument haelt Punkte fest, die in der DDL-Zielbild-Spezifikation
[`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md)
beschrieben sind, aber nach aktuellem Implementierungsstand nicht oder nur als
spaeterer Scope getragen werden.

## Ergebnis

Diese Analyse identifiziert aktuell zwei relevante Gaps ohne explizite
Deferred-Entscheidung:

- **Partial Indexes / Partial-UNIQUE** als fachlicher DDL-Semantik-Gap
- **`schema generate`-Determinismus** als Reproduzierbarkeits-Gap

Die Einstufung sollte bei Aenderungen an `ddl-generation-rules.md` erneut
geprueft werden.

Zwei weitere Punkte sind bereits in der Spezifikation selbst oder in
Planungsdokumenten als spaeterer Scope markiert:

- `ddl.quote_identifiers: reserved_only`
- diff-basierter `schema migrate`-/ALTER-/SQLite-Rebuild-Pfad

## 1. Partial Indexes und Partial-UNIQUE

### Zielbild

[`ddl-generation-rules.md` §5.4](../../../spec/ddl-generation-rules.md) beschreibt
Partial Indexes fuer PostgreSQL:

```sql
CREATE INDEX "idx_active_orders" ON "orders" ("status") WHERE "status" != 'cancelled';
```

[`lastenheft-d-migrate.md` §8.7](../../../spec/lastenheft-d-migrate.md) nennt
Partial Indexes mit WHERE-Klauseln als PostgreSQL-spezifischen Testfall.

### Aktueller Implementierungsstand

Partial Indexes werden aktuell nicht modelliert.

- [`IndexDefinition`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/IndexDefinition.kt) enthaelt nur `name`, `columns`, `type`, `unique`.
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
| MySQL | `action_required` fuer Partial Indexes; kein stiller Predicate-Verlust | `action_required`; kein Unique-Fallback |
| SQLite | optional native Partial-Index-Unterstuetzung pruefen, sonst `action_required` | optional native Partial-UNIQUE-Unterstuetzung pruefen, sonst `action_required`; kein Unique-Fallback |

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

[`ddl-generation-rules.md` §2.2](../../../spec/ddl-generation-rules.md) beschreibt
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

[`ddl-generation-rules.md` §3.6`-`§3.7](../../../spec/ddl-generation-rules.md)
beschreibt ALTER-Statements und SQLite-Rebuilds fuer einen spaeteren
`schema migrate`-Pfad.

### Aktueller Stand

Nicht Teil des aktuellen CLI-Funktionsumfangs. Das ist in mehreren Dokumenten
explizit abgegrenzt:

- [`cli-spec.md` §7](../../../spec/cli-spec.md)
- [`implementation-plan-0.5.0.md`](../done/implementation-plan-0.5.0.md)
- [`implementation-plan-0.7.0.md`](../done/implementation-plan-0.7.0.md)

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

## 5. `schema generate`-Header-Timestamp und Determinismus

### Zielbild / Ist-Konflikt

[`ddl-generation-rules.md` §1.2](../../../spec/ddl-generation-rules.md) definiert
fuer generierte DDL einen Header mit Laufzeit-Timestamp:

```sql
-- Target: <dialect> | Generated: <ISO-8601-timestamp>
```

Fuer Tool-Exports ist der Determinismus bereits separat geregelt: Flyway,
Liquibase, Django und Knex duerfen den Laufzeit-Timestamp nicht in die
vergleichbaren Artefakte uebernehmen.

Plain `schema generate` nutzt aktuell jedoch weiterhin Laufzeitzeit:

```kotlin
java.time.Instant.now()
```

Damit ist `schema generate` bei identischer Eingabe nicht byte-deterministisch.

### Bewertung

Das ist ein echter Reproduzierbarkeits-Gap, aber kein funktionaler
DDL-Semantikfehler. Der erzeugte SQL-Inhalt ist stabil; nur der Header macht
die Ausgabe volatil.

### Braucht es einen neuen Schalter?

Empfehlung: **Ja, aber nicht als einziger Mechanismus.**

Ein kompletter Default-Wechsel waere riskant, weil der Header bewusst
Provenienz liefert und bestehende Nutzer oder Tests den Timestamp erwarten
koennen. Besser ist ein additiver deterministischer Modus.

Empfohlener CLI-Vertrag:

```bash
d-migrate schema generate --source schema.yaml --target postgresql --deterministic
```

Semantik von `--deterministic`:

- keine Laufzeitzeit in DDL, JSON-Ausgabe oder Sidecar-Report
- Header wird entweder ohne `Generated:` gerendert oder mit stabilem Wert
- gleiche Eingabe + gleiche Flags erzeugen byte-identische Ausgabe

Ergaenzend sollte ein Standard-Reproducible-Build-Mechanismus unterstuetzt
werden:

```bash
SOURCE_DATE_EPOCH=1775001600 d-migrate schema generate ...
```

Semantik und Praezedenz:

1. `--deterministic` setzt explizit die Output-Policy fuer DDL, JSON und Sidecar-Report.
2. Wenn `--deterministic` gesetzt ist und `SOURCE_DATE_EPOCH` ebenfalls gesetzt ist, liefert `SOURCE_DATE_EPOCH` nur den stabilen Zeitwert; die deterministische Output-Policy bleibt durch `--deterministic` bestimmt.
3. Wenn `--deterministic` nicht gesetzt ist, aber `SOURCE_DATE_EPOCH` gesetzt ist, bleibt die normale Header-Form erhalten, `Generated:` wird aber auf den daraus abgeleiteten UTC-Instant fixiert.
4. Wenn weder `--deterministic` noch `SOURCE_DATE_EPOCH` gesetzt ist, bleibt das heutige Provenienzverhalten mit Laufzeitzeit aktiv.
5. Tool-Exports duerfen ihre bestehende Timestamp-Normalisierung behalten; falls sie auf die gemeinsame Policy umgestellt werden, muss diese Praezedenz identisch gelten.

Offene Entscheidung:

| Option | Vorteil | Nachteil |
|---|---|---|
| `--deterministic` entfernt `Generated:` | maximal stabil, analog Tool-Export-Normalisierung | Header-Form weicht von §1.2 ab |
| `--deterministic` setzt festen Wert, z. B. Unix Epoch | Header-Form bleibt stabil | kuenstlicher Timestamp kann missverstanden werden |
| `--generated-at <instant>` | maximale Kontrolle, gut testbar | mehr CLI-Oberflaeche, weniger intuitiv |
| nur `SOURCE_DATE_EPOCH` | Standardkonform ohne neue CLI-Option | weniger sichtbar fuer Nutzer |

Empfehlung fuer ersten Scope:

1. `DdlGenerationOptions` um `generatedAt: Instant?` oder einen Clock-/Header-Policy-Wert erweitern.
2. CLI-Flag `--deterministic` einfuehren.
3. `SOURCE_DATE_EPOCH` als stabilen Zeitwert gemaess Praezedenzregel auswerten.
4. Sidecar-Reports ebenfalls ueber dieselbe Zeitquelle fuehren.
5. Tool-Exports koennen ihre bestehende Normalisierung behalten oder auf dieselbe Policy umstellen.
