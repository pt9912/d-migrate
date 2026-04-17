# Implementierungsplan: Phase A - Sicherheits-Haertung

> **Milestone**: 0.9.1 - Library-Refactor und Integrationsschnitt
> **Phase**: A (Sicherheits-Haertung)
> **Status**: Draft (2026-04-17)
> **Referenz**: `docs/implementation-plan-0.9.1.md` Abschnitt 1 bis 4,
> Abschnitt 6.1, Abschnitt 7, Abschnitt 8 und Abschnitt 9;
> `docs/quality.md`; `docs/cli-spec.md`;
> `adapters/driven/driver-postgresql/.../PostgresSchemaIntrospectionAdapter.kt`;
> `adapters/driven/driver-postgresql/.../PostgresProfilingDataAdapter.kt`;
> `adapters/driven/driver-mysql/.../MysqlSchemaIntrospectionAdapter.kt`;
> `adapters/driven/driver-mysql/.../MysqlProfilingDataAdapter.kt`;
> `adapters/driven/driver-sqlite/.../SqliteSchemaIntrospectionAdapter.kt`;
> `adapters/driven/driver-sqlite/.../SqliteProfilingDataAdapter.kt`;
> `hexagon/application/.../DataExportHelpers.kt`;
> die drei DDL-Generatoren unter `adapters/driven/driver-*`.

---

## 1. Ziel

Phase A zieht die Sicherheits-Findings aus `docs/quality.md` vor die
strukturellen 0.9.1-Refactors.

Der Teilplan beantwortet bewusst zuerst die Sicherheits- und
Vertragsfragen:

- wie Identifier in Profiling- und Introspection-SQL kuenftig pro
  Dialekt gebaut werden
- wie Literalwerte in Metadaten-SQL aus Interpolation auf
  `PreparedStatement`-Binding umgestellt werden
- wie bewusst offene Raw-SQL-Grenzen (`--filter`,
  `constraint.expression`) als Trusted-Input-Vertrag sichtbar gemacht
  werden
- welche Security-Tests boesartige Namen, reservierte Woerter und
  Unicode-Kanten faelschungssicher abdecken muessen
- wie dabei die modulweise Kover-Grenze von mindestens 90 % erhalten
  bleibt

Phase A liefert damit keine neue Endnutzerfunktion, sondern eine
Sicherheits-Haertung der bereits vorhandenen SQL-nahen Adapter und
offenen Vertragsstellen.

Nach Phase A soll klar gelten:

- Profiling- und Introspection-Adapter interpolieren keine
  unquotierten Identifier oder Literale mehr in SQL-Strings
- Identifier-Quoting ist dialektkonsistent zentralisiert
- offene Raw-SQL-Einstiegspunkte sind vertraglich klar als Trusted
  Input markiert oder haben einen kleineren sicheren Ersatzpfad
- boesartige Namen koennen nicht mehr unbemerkt Escape-/Quoting-Luecken
  passieren

---

## 2. Ausgangslage

Laut `docs/quality.md` sind fuer 0.9.1 vor allem diese Punkte relevant:

- **Hoch**: Profiling-/Introspection-Adapter interpolieren
  `table`/`column`/teils `schema` direkt in SQL-Strings
- **Mittel**: `DataExportHelpers.resolveFilter` uebernimmt `--filter`
  ungeprueft als Raw-SQL-`WhereClause`
- **Mittel**: DDL-Generatoren setzen `constraint.expression` direkt in
  CHECK/EXCLUDE und aehnliche SQL-Fragmente ein
- **Mittel**: aehnliche Profiling-Logik lebt in mehreren Dialekten mit
  leicht unterschiedlichen Escape-Strategien

Konsequenz:

- Sicherheitsfixes duerfen nicht nur punktuell je Adapter erfolgen
- der Identifier-/Literal-Vertrag muss zentralisiert werden
- Trusted-Input-Grenzen muessen in CLI, KDoc und Spezifikation
  explizit werden

---

## 3. Scope fuer Phase A

### 3.1 In Scope

- zentrale Identifier-Quoting-Utility pro Dialekt
  (PostgreSQL/MySQL/SQLite) in gemeinsamem, wiederverwendbarem Zuschnitt
- Umstellung der Introspection- und Profiling-Adapter auf:
  - konsequentes Identifier-Quoting fuer Namen
  - `PreparedStatement`-Binding fuer Literals in Metadaten-SQL, wo der
    Dialekt dies fuer Metadatenabfragen zulaesst
  - fuer SQLite-`PRAGMA`-Pfade einen explizit sicheren
    String-Literal-/Identifier-Helper statt freier Interpolation
- Sicherheits-Haertung der betroffenen Adapter:
  - `PostgresSchemaIntrospectionAdapter`
  - `PostgresProfilingDataAdapter`
  - `MysqlSchemaIntrospectionAdapter`
  - `MysqlProfilingDataAdapter`
  - `SqliteSchemaIntrospectionAdapter`
  - `SqliteProfilingDataAdapter`
- Kennzeichnung von `--filter` und `constraint.expression` als
  **Trusted Input**
- optionaler kleinerer sicherer Ersatzweg fuer Filter-Ausdruecke, wenn
  der Aufwand in Phase A vertretbar bleibt
- Security-Tests mit boesartigen Namen und problematischen Identifiern
- Sicherstellung, dass Kover pro betroffenem Modul >= 90 % bleibt

### 3.2 Bewusst nicht Teil von Phase A

- komplette DSL fuer beliebige SQL-Pradikate
- generelle Sanitization aller absichtlich offenen SQL-Fragmente
- groessere Port- oder Modul-Refactors aus Phase B bis F
- Aenderung des fachlichen DDL-Vertrags jenseits der Trusted-Input-
  Kennzeichnung

Praezisierung:

Phase A loest zuerst "wo ist die reale Injection-Flaeche und wie wird
sie geschlossen?", nicht "wie ersetzen wir jede flexible SQL-Kante
durch eine neue Produktsprache?".

---

## 4. Leitentscheidungen fuer Phase A

### 4.1 Identifier und Literale werden verschieden behandelt

Verbindliche Entscheidung:

- SQL-Identifier (`schema`, `table`, `column`, Constraint-/Index-Namen)
  werden nie als Literals behandelt
- Identifier laufen immer ueber dialektkonformes Quoting
- Literalwerte in Metadatenabfragen werden immer gebunden, nicht
  interpoliert
- wenn ein Dialekt fuer Metadatenabfragen kein `PreparedStatement`-
  faehiges Muster bietet, muss dafuer ein expliziter, getesteter
  Dialekt-Helper fuer sichere Literal- und Identifier-Einbettung
  verwendet werden

Folge:

- die Ziel-API der Utility muss beide Pfade sichtbar trennen
- Code, der weiter String-Interpolation fuer Literalwerte nutzt, gilt
  in Phase A als Bug
- SQLite-`PRAGMA`-Pfade brauchen im Plan einen gleichwertigen
  Sicherheitsvertrag statt einer stillen Ausnahme

### 4.2 Dialektkonsistenz ist wichtiger als lokale Hotfixes

Verbindliche Entscheidung:

- Phase A fuehrt keine lose Sammlung einzelner Escape-Fixes ein
- Quoting-/Binding-Regeln muessen zentral oder zumindest je Dialekt an
  einer wiederverwendbaren Stelle liegen
- vergleichbare Adapterpfade sollen danach denselben Sicherheitsvertrag
  sprechen

### 4.3 Raw-SQL-Grenzen bleiben vorerst erlaubt, aber sichtbar

Verbindliche Entscheidung:

- `--filter` und `constraint.expression` bleiben in 0.9.1 moeglich
- diese Pfade werden aber explizit als Trusted Input dokumentiert
- die CLI-Hilfe, Modell-/Generator-KDocs und die normativen
  Schema-/DDL-Dokumente duerfen an diesen Stellen keine falsche
  Sicherheitsillusion erzeugen

Nicht zulaessig ist:

- unmarkiertes Raw-SQL weiter als normalen, implizit sicheren
  Nutzereingabepfad zu praesentieren
- eine "stille" Sicherheitshaertung zu behaupten, waehrend Raw-SQL
  unveraendert offen bleibt

### 4.4 Boesartige Namen muessen testbar bleiben

Verbindliche Entscheidung:

- Tests muessen absichtlich problematische Identifier enthalten:
  - Anfuehrungszeichen
  - Semikolon-/Kommentar-Muster
  - reservierte Woerter
  - Unicode-Homoglyphs
- Phase A bewertet den Sicherheitsgewinn nicht ueber LOC, sondern ueber
  nachweisbar robuste SQL-Erzeugung

---

## 5. Konkrete Arbeitspakete

### 5.1 Identifier-Utility einfuehren

- API fuer dialektkonformes Quoting von Identifiern definieren
- Utility so schneiden, dass Adapter sie direkt verwenden koennen
- PostgreSQL-, MySQL- und SQLite-Regeln abbilden
- Doppelimplementierungen in bestehenden Adaptern entfernen

### 5.2 Introspection-/Profiling-Adapter haerten

- direkte `$name`-Interpolation von `table`/`column`/`schema`
  identifizieren und ersetzen
- Literals in Metadaten-SQL auf gebundene Parameter umstellen
- fuer SQLite-`PRAGMA`-Abfragen einen zentralen, getesteten
  Escape-/Literal-Helper verwenden, wo Binding technisch nicht als
  gleichwertiger Pfad verfuegbar ist
- Namen ausschliesslich ueber die neue Identifier-Utility erzeugen
- bestehende Adaptertests auf boesartige Namen erweitern

### 5.3 Raw-SQL-Vertrag sichtbar machen

- `--filter` in CLI-Hilfe als Trusted Input markieren
- KDoc/Vertragsdoku fuer `DataExportHelpers.resolveFilter` nachziehen
- `constraint.expression` im Modell-/Generatorvertrag als Trusted Input
  markieren
- `constraint.expression` in `docs/neutral-model-spec.md` als bewusst
  offene Trusted-Input-Grenze sichtbar machen
- DDL-seitige Verwendung von `constraint.expression` in
  `docs/ddl-generation-rules.md` mit demselben Trusted-Input-Vertrag
  spiegeln
- optional pruefen, ob ein kleiner sicherer Filter-Ersatzpfad fuer
  haeufige Faelle in Phase A schon vertretbar ist

### 5.4 Security-Testbasis schliessen

- Tests mit boesartigen Tabellen-/Spaltennamen fuer Profiling-Pfade
- Tests mit reservierten Woertern und Unicode-Kanten
- Tests fuer DDL-/Constraint-Pfade dort, wo Quoting und Trusted-Input-
  Kennzeichnung betroffen sind
- Kover-Verification der betroffenen Module mit Mindestgrenze 90 %

---

## 6. Verifikation

Phase A ist erst abgeschlossen, wenn folgende Punkte gruen sind:

- betroffene Adaptertests fuer PostgreSQL, MySQL und SQLite
- gezielte Security-Tests mit boesartigen Identifiern
- CLI-/Helper-Tests fuer den sichtbaren Trusted-Input-Vertrag
- Kover-Checks der betroffenen Module

Mindestergebnis:

- die Injection-Flaeche aus `docs/quality.md` ist fuer die betroffenen
  Adapter geschlossen
- Raw-SQL-Oberflaechen sind explizit als Trusted Input sichtbar
- die Sicherheits-Haertung ist durch Tests und Coverage nachweisbar

---

## 7. Betroffene Codebasis

Mit hoher Wahrscheinlichkeit betroffen:

- `adapters/driven/driver-postgresql/...`
- `adapters/driven/driver-mysql/...`
- `adapters/driven/driver-sqlite/...`
- `hexagon/application/.../DataExportHelpers.kt`
- `hexagon/core/.../ConstraintDefinition.kt`
- DDL-Generatoren in `adapters/driven/driver-*`
- `docs/cli-spec.md`
- `docs/neutral-model-spec.md`
- `docs/ddl-generation-rules.md`
- ggf. gemeinsame Dialekt-Utility unter `adapters/driven/driver-common/...`
- ggf. weitere KDoc-nahe Vertragsstellen im Port-/Application-Bereich

Die genaue Paketlage darf waehrend der Umsetzung pragmatisch angepasst
werden; entscheidend ist der einheitliche Sicherheitsvertrag.

---

## 8. Risiken und offene Punkte

### 8.1 Trusted-Input-Markierung kann als "nur Doku-Fix" missverstanden werden

Gegenmassnahme:

- klar trennen zwischen echter Adapter-Haertung und bewusst weiter
  offener Raw-SQL-Kante
- Review und Doku duerfen diese beiden Themen nicht vermischen

### 8.2 Dialektunterschiede koennen zu scheinbar kleinen Sonderfaellen fuehren

Gegenmassnahme:

- zentrale Utility mit expliziten Dialektregeln
- boesartige Testfaelle fuer alle drei Dialekte, nicht nur fuer einen

### 8.3 Coverage-Ziel kann bei refactorstarker Utility-Einfuehrung absinken

Gegenmassnahme:

- neue Utility sofort mit eigenen Tests absichern
- Kover nicht erst am Ende, sondern waehrend Phase A laufen lassen

---

## 9. Entscheidungsempfehlung

Phase A sollte unveraendert als erste 0.9.1-Phase umgesetzt werden.

Begruendung:

- sie schliesst reale Sicherheits-Findings aus `docs/quality.md`
- sie reduziert die Regressionsflaeche der spaeteren strukturellen
  Refactors
- sie schafft eine saubere Basis fuer Phase B, ohne bereits den grossen
  Orchestrierungs- und Modulumbau mitzuziehen
