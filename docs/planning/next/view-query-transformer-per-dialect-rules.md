# `ViewQueryTransformer`: Dialekt-Regeln aus `driver-common` in die Adapter verschieben

> **Status:** Draft mit Scope (2026-09-05).
> **Trigger:** Beim Oracle-Slice-2-Bau (`schema generate`, ADR 0052) fiel
> auf: `ViewQueryTransformer` (Portabilitäts-Check + Funktions-Umschreibung
> für View-Bodies) lebt als eine Klasse in `driver-common` und enthält für
> jeden Dialekt eigene `when (targetDialect)`-Zweige (Marker-Erkennung,
> Regelsätze, bekannte Funktionen) — ein Muster, das schon vor MSSQL/Oracle
> bestand (MySQL/SQLite/PostgreSQL) und mit dem Oracle-Zweig nur fortgesetzt,
> nicht neu eingeführt wurde. Eigner-Frage, ob das architektonisch so bleiben
> soll; Antwort: eigener Slice statt Mitziehen in Oracle Slice 2.

## Ist-Zustand

`adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/ViewQueryTransformer.kt`
ist eine einzelne Klasse, konstruiert mit `targetDialect: DatabaseDialect`,
die jeder `DdlGenerator` direkt aufruft (`ViewQueryTransformer(DatabaseDialect.X)`).
Sie bündelt pro Dialekt:

- **Marker-Erkennung** (`assessPortability`): harte Syntax-Inkompatibilitäten
  (Backtick-Quoting, `::`, `||`, `LIMIT`, T-SQL-Klammer-Quoting, bare
  `ORDER BY`) — je Ziel-Dialekt eigene `when`-Zweige bzw. ausgelagerte
  Funktionen (`mssqlMarkers`, `oracleMarkers`).
- **Umschreibe-Regeln** (`getRules()`): `mysqlRules`/`sqliteRules`/
  `postgresRules` (echte Funktions-Übersetzung, z. B. `DATE_TRUNC` →
  `DATE_FORMAT`), MSSQL/Oracle bewusst `emptyList()` (kein
  Übersetzer, Nicht-Portabilität fällt stattdessen `assessPortability` auf).
- **Bekannte Funktionen** (`knownFunctions()`): je Ziel-Dialekt eine
  Menge, die `detectUnknownFunctions` für die generische
  Cross-Dialekt-Warnung (W111) nutzt.

Die generische Tokenizer-/Regel-Infrastruktur (`ViewQueryTokenizer`,
`ViewQueryToken(Type)`, `ViewQueryRule` und seine Implementierungen
`FuncReplaceRule`/`ExtractReplaceRule`/`SubstringReplaceRule`/
`WordReplaceRule`, `ViewQueryRuleSupport`, `ViewQueryTokenSupport`) ist
echte Dialekt-unabhängige Infrastruktur und bliebe unverändert in
`driver-common`.

`a-check` (Architektur-Gate) beanstandet den Ist-Zustand nicht — es prüft
Hexagon-Schicht-Grenzen (core/ports/adapters), nicht "kein Dialektwissen in
einer geteilten Utility-Klasse". Das Muster ist also strukturell erlaubt,
aber ein Single-Responsibility-/Open-Closed-Zielkonflikt: jeder neue
Dialekt (wie gerade Oracle) erweitert eine Klasse in einem Modul, das
selbst keinen der fünf Dialekte "besitzt".

## Ziel

Dialekt-spezifisches Wissen (Marker-Listen, Regelsätze, bekannte
Funktionen) wandert in die jeweiligen Treibermodule
(`driver-postgresql`/`driver-mysql`/`driver-sqlite`/`driver-mssql`/
`driver-oracle`); `driver-common` behält nur die generische
Tokenizer-/Regel-Infrastruktur plus die dialekt-neutrale Orchestrierung
(`assessPortability`/`transform` als Hüllen, die auf ein injiziertes
Regel-Objekt delegieren).

## Scope-Skizze

1. **P0 — Port-Interface entwerfen.** Ein Interface in `driver-common`
   (Name offen, z. B. `ViewPortabilityRules`) mit den drei Zuständigkeiten:
   `markers(tokens, crossDialect): List<String>`, `rules(): List<ViewQueryRule>`,
   `knownFunctions(): Set<String>`. `ViewQueryTransformer` wird zur reinen
   Orchestrierungs-Hülle, die ein `ViewPortabilityRules`-Objekt injiziert
   bekommt statt selbst `when (targetDialect)` zu verzweigen.
2. **P1 — Fünf Implementierungen.** Je ein
   `<Dialekt>ViewPortabilityRules`-Objekt in den fünf Treibermodulen,
   befüllt mit dem heute in `ViewQueryTransformer` liegenden Inhalt
   (mechanische Verschiebung, keine Verhaltensänderung).
3. **P2 — Auflösung verdrahten.** `DatabaseDriver`-Interface um eine neue
   Methode ergänzen (z. B. `viewPortabilityRules(): ViewPortabilityRules`)
   — das rippelt in alle fünf `*Driver`-Klassen **und** jeden
   Fake/Mock, der `DatabaseDriver` in Tests implementiert. Jeder
   `DdlGenerator.generateView(s)` wechselt von
   `ViewQueryTransformer(DatabaseDialect.X)` auf die Registry-Auflösung
   (`DatabaseDriverRegistry.get(dialect).viewPortabilityRules()` oder
   äquivalent injiziert).
4. **P3 — Tests aufteilen.** `ViewQueryTransformerTest.kt` (~300 Zeilen,
   ein File in `driver-common`) auf die generische Tokenizer-/Regel-Basis
   dort belassen, die dialekt-spezifischen Fälle in je eine
   `<Dialekt>ViewPortabilityRulesTest.kt` im jeweiligen Treibermodul
   verschieben.
5. **P4 — Vollregression.** DDL-Goldens **aller** fünf Dialekte erneut
   prüfen (nicht nur Oracle) — das Risiko liegt in einer stillen
   Verhaltensänderung für MySQL/SQLite/PostgreSQL/MSSQL beim Verschieben,
   nicht in Oracle selbst.

## Akzeptanzkriterien

- `driver-common` enthält keinen `when (targetDialect)`-Zweig mehr in
  `ViewQueryTransformer` (nur noch generische Tokenizer-/Regel-Bausteine).
- Jedes Treibermodul trägt sein eigenes `ViewPortabilityRules` samt Tests.
- Alle bestehenden DDL-Goldens (fünf Dialekte) bleiben unverändert
  (reine Verschiebung, keine Verhaltensänderung).
- `make docker-check` (Vollbau, kein `MODULES=`) und `make a-check` grün.

## Vorbedingungen / Nicht-Scope

- Keine inhaltliche Änderung der Marker-/Regel-Logik selbst — reine
  Struktur-Verschiebung.
- Kein Auslöser-Zwang: aktiv erst, wenn ein sechster Dialekt oder ein
  weiterer Änderungsbedarf an den bestehenden Regeln den Aufwand
  rechtfertigt.
