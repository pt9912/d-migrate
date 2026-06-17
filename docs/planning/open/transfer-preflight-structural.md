# Transfer-Preflight strukturell aus der Dialekt-Typ-Abbildung ableiten

> **Status:** Vorabklärung (Draft mit Scope + offener Design-Entscheidung, 2026-06-17)
> **Trigger:** Re-Run-2-Befund **M2 (P1)**
> ([`../in-progress/pilot-validation-0.9.9-rerun2.md`](../in-progress/pilot-validation-0.9.9-rerun2.md)):
> Die Transfer-Preflight blockt weiterhin tool-eigene Cross-Dialect-Abbildungen
> (`Array(text)→Json` PG→MySQL, `Decimal→Float` →SQLite), obwohl I-01/N3 dieselbe
> Klasse fallweise bereits geöffnet haben. Hand-gepflegte Fall-Liste hinkt
> strukturell hinterher (Whack-a-Mole).
> **Aktivierungsbedingung:** Design-Entscheidung D-1 (Port-Form, s. u.) ist zu
> treffen, dann wandert der Eintrag nach `../next/` mit Phasenschnitt.

## Wurzel

`TransferTypeCompatibility.isCompatible(source, target)` ist eine Liste von
`if (source.type is X && target.type is Y) return true`. Jede neue tool-eigene
Abbildung muss einzeln nachgetragen werden (I-01: bool→Integer/Enum→Text/
timestamptz→DATETIME; N3: Enum→Enum/Temporal→Text; jetzt M2: Array→Json,
Decimal→Float; morgen das nächste).

**Strukturelle Regel (Zielbild):** Das Zielschema ist die *Tool-Ausgabe* für den
Zieldialekt — der Ziel-Spaltentyp Y ist die generatoreigene Abbildung. Quelle X
und Ziel Y sind transfer-kompatibel, **wenn der Ziel-Dialekt-Typ-Mapper X und Y
auf denselben (normalisierten) SQL-Typ abbildet**:

```
isCompatible(X, Y)  ==  normalize(targetMapper.toSql(X)) == normalize(targetMapper.toSql(Y))
```

Das deckt **alle** bisherigen Sonderfälle per Konstruktion ab:
`toSql(Array)==toSql(Json)=="JSON"` (MySQL), `toSql(Decimal)==toSql(Float)=="REAL"`
(SQLite), `toSql(Enum)==toSql(Text)=="TEXT"`, `toSql(DateTime(tz=true))==
toSql(DateTime(tz=false))=="DATETIME"` usw. — ohne jede Einzelregel.

## Offene Design-Entscheidung

**D-1 — Port-Form (application↔driver).** `DatabaseDriver` exponiert bewusst
**keinen** `TypeMapper` (`hexagon/ports/.../DatabaseDriver.kt`, Doku: „TypeMapper
is intentionally NOT exposed … internal detail of DdlGenerator"). Die Preflight
sitzt in `hexagon/application` (`TransferPreflightPlanner`) und braucht die
Ziel-Typ-Abbildung. Optionen:

- **(a) Treiber beantwortet Kompatibilität.** Neuer Port (z. B.
  `DatabaseDriver.transferCompatibility(): TransferCompatibility` oder eine
  Methode auf `DdlGenerator`), der die strukturelle Prüfung **im Treiber**
  ausführt (dort lebt der `TypeMapper`). Kapselung bleibt, kein TypeMapper-Leak.
  **Empfohlen.**
- **(b) Treiber exponiert die SQL-Typ-Auflösung.** `DdlGenerator.columnSqlType(
  type): String`; die Vergleichslogik bleibt in der application. Leakt die
  Mapping-Oberfläche schwächer, aber mehr Logik in der application.
- **(c) `TypeMapper` direkt exponieren** — widerspricht der bestehenden
  Design-Entscheidung (Z. 13-15), nur als Notlösung.

## Normalisierung

`toSql` trägt Länge/Präzision (`VARCHAR(50)`, `DECIMAL(10,2)`). Für die
Transfer-Preflight zählt die **Typ-Familie**, nicht die exakte Länge — Werte
fitten auf Wert-Ebene (Data-Writer). `normalize` muss daher `(…)` strippen.
**Offen:** `VARCHAR` vs `TEXT` (beide `Text`, aber verschiedene Basis-Namen) —
entweder über die neutrale Typfamilie statt des SQL-Strings vergleichen, oder
text-Varianten gezielt zusammenfassen. Im Slice klären.

## Scope

- Strukturelle `isCompatible` über D-1; die heutige Fall-Liste durch die
  Ableitung ersetzen (negative Guards entfallen, da strukturell abgedeckt).
- Identitäts-Fast-Path (`source.type == target.type`) bleibt.
- **Regressionsschutz:** alle heutigen Positiv-/Negativ-Fälle der
  `TransferTypeCompatibilityTest` müssen weiter gelten (I-01-/N3-Mappings
  kompatibel; `text→integer`, `integer→boolean` inkompatibel).
- Neue Fälle: `Array→Json`, `Decimal→Float`, `SmallInt/Integer→Float`,
  `Uuid→Text` (alle = tool-eigene Mappings) müssen kompatibel werden.

## Akzeptanz

- PG→MySQL (`film.special_features` Array→Json) und →SQLite (`product.price`
  Decimal→Float) passieren die Preflight (Re-Run-2-Repro M2).
- Keine Fall-für-Fall-Liste mehr; eine neue tool-eigene Abbildung braucht künftig
  **keinen** Preflight-Edit.
- `docker-check` grün; Live-Transfer-Stichprobe in einem Pilot-Re-Run.

## Nicht Teil dieses Slices

- **M1 (P2, separat):** generierte Funktionsnamen mit Signatur-Suffix
  (`"last_updated()"`) — eigener Quick-Fix (Namens-Normalisierung), unabhängig
  von der Preflight-Struktur.
