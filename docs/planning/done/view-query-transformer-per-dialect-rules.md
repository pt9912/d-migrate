# `ViewQueryTransformer`: Dialekt-Regeln aus `driver-common` in die Adapter verschieben

> **Status:** erledigt (2026-09-09).
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

## Closure (2026-09-09)

Gebaut wie geschnitten, mit drei Abweichungen — jede billiger als der Plan:

- **Keine `DatabaseDriver`-Erweiterung, keine Registry.** P2 fürchtete einen
  Rippel durch alle fünf Treiber und jeden Test-Fake. Der war unnötig: jede
  der elf Aufrufstellen liegt **im eigenen Treibermodul** und kennt ihren
  Dialekt zur Übersetzungszeit. Sie konstruiert das Regelobjekt direkt
  (`ViewQueryTransformer(MysqlViewPortabilityRules)`), und das Interface
  bleibt eine reine Übergabe.
- **Zwei Marker bleiben in der Hülle**, und das ist kein Rest, sondern eine
  Unterscheidung, die der Plan nicht traf: MySQLs Backticks und T-SQLs
  Klammern sind Aussagen über die **Quelle**, nicht über das Ziel — sie
  gelten für jeden Dialekt außer ihrem Eigentümer. In fünf Regelobjekten
  stünde dieselbe Zeile viermal.
- **Die Sichtbarkeit musste sich öffnen.** `ViewQueryToken`, `ViewQueryRule`
  und die Regelklassen waren `internal` in `driver-common` — für die
  Regelobjekte in den anderen Modulen unsichtbar. Sie sind jetzt öffentlich:
  aus einem Modul-Internum wird ein geteiltes Vokabular, und genau das ist
  die Rolle, die dem Modul bleibt.

**Was die Aufteilung nebenbei aufgedeckt hat:** die generische Umschreib-
Infrastruktur (`ViewQueryRuleSupport`, Tokenizer, Klausel-Erkennung) war
**nirgends direkt geprüft** — sie lief nur nebenbei durch die Dialekt-Tests
mit. Nach dem Umzug fiel die Modulabdeckung von 90 % auf 79 %, und das war
kein Messfehler, sondern der sichtbar gewordene Zustand. Sie hat jetzt eigene
Zusicherungen (Argument-Zerlegung mit Schachtelung, `EXTRACT`/`SUBSTRING`-
Formen, Bau-Helfer, Klausel-Erkennung) — prüfbar ohne jeden Dialekt, was
zugleich der Beleg dafür ist, dass sie zu Recht geteilt liegt.

## Was offen bleibt

`RawSqlExpressionPortability` (im selben Modul, 2026-09-08 entstanden) trägt
dieselbe Gestalt: `::`/`~~` für Nicht-PostgreSQL, Backticks für Nicht-MySQL,
`||` nur für SQL Server — Dialektwissen in einem Modul, das keinen Dialekt
besitzt. Es wurde **gebaut, während dieses Ticket schon offen war**. Derselbe
Handgriff behebt es; eigener Schnitt, damit dieser hier eine reine
Verschiebung bleibt.

Dahinter steht die größere Frage, die der Eigner beim Durchsehen gestellt hat:
**ein Modul, das nach seiner Beziehung benannt ist („common", „shared"), hat
kein Aufnahmekriterium** — also landet dort alles, was zwei Module zufällig
teilen. `driver-common` ist heute gemessen mindestens fünf Dinge
(`connection/` 7 Dateien, `data/` 9, `metadata/` 6, `migration/` 3,
`profiling/` 1, dazu 20 im Wurzelverzeichnis), und 28 Module hängen daran.
Sie zu benennen statt ihre Beziehung wäre ein eigener Schnitt mit
entsprechender Reichweite.
