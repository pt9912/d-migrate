# Implementierungsplan: Phase A - Sicherheits-Haertung

> **Milestone**: 0.9.1 - Library-Refactor und Integrationsschnitt
> **Phase**: A (Sicherheits-Haertung)
> **Status**: In Review (2026-04-17)
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

Partielles Quoting existiert bereits an einzelnen Stellen:

- `SqliteDdlGenerator` hat eine eigene `quoteIdentifier()`-Methode
- `DataExportHelpers.quoteQualifiedIdentifier()` behandelt alle drei
  Dialekte, aber nur fuer den Export-Pfad
- `DataExportHelpers.resolveFilter` traegt bereits den Kommentar
  "Trust-Boundary ist die lokale Shell" — die Trusted-Input-Entscheidung
  ist also implizit getroffen, aber nicht formalisiert

Dieses bestehende Quoting ist inkonsistent und nicht zentralisiert.

Konsequenz:

- Sicherheitsfixes duerfen nicht nur punktuell je Adapter erfolgen
- der Identifier-/Literal-Vertrag muss zentralisiert werden;
  bestehende verstreute Quoting-Implementierungen muessen dabei
  konsolidiert werden
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
- Umstellung der DDL-Generatoren auf die neue zentrale
  Identifier-Utility, soweit sie heute eigenes Quoting verwenden
  (z. B. `SqliteDdlGenerator.quoteIdentifier()`), damit nach Phase A
  nicht zwei parallele Quoting-Welten existieren
- Sicherstellung, dass Kover pro betroffenem Modul >= 90 % bleibt

### 3.2 Bewusst nicht Teil von Phase A

- komplette DSL fuer beliebige SQL-Praedikate
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
  Sicherheitsvertrag statt einer stillen Ausnahme; betroffen sind
  insbesondere `PRAGMA table_info(<table>)` und
  `PRAGMA foreign_key_list(<table>)` in `SqliteSchemaIntrospectionAdapter`,
  die keinen `PreparedStatement`-Binding-Pfad bieten; der Helper muss
  dort Identifier per Whitelist (nur erlaubte Zeichen) oder per
  dialektkonformem Quoting absichern

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

Abhaengigkeiten und Reihenfolge:

1. **5.1** (Utility) muss zuerst umgesetzt werden — alle anderen
   Pakete haengen davon ab
2. **5.2** (Adapter-Haertung) und **5.3** (Raw-SQL-Vertrag) koennen
   parallel bearbeitet werden
3. **5.4** (Security-Tests) laeuft parallel zu 5.2 — Tests werden
   zusammen mit der jeweiligen Adapter-Umstellung geschrieben

### 5.1 Identifier-Utility einfuehren

- API fuer dialektkonformes Quoting von Identifiern definieren
- grobe Ziel-Signatur:
  - `fun quoteIdentifier(name: String): String` — einzelner Name
  - `fun quoteQualifiedIdentifier(schema: String?, table: String): String`
    — qualifizierter Name mit optionalem Schema-Prefix
  - pro Dialekt eine Implementierung; gemeinsames Interface oder
    sealed class fuer Typensicherheit
- Utility so schneiden, dass Adapter sie direkt verwenden koennen
- PostgreSQL-, MySQL- und SQLite-Regeln abbilden
- bestehende Quoting-Implementierungen konsolidieren:
  - `SqliteDdlGenerator.quoteIdentifier()` auf Utility umstellen
  - `DataExportHelpers.quoteQualifiedIdentifier()` auf Utility
    umstellen oder ersetzen
  - weitere verstreute Escape-Logik in Adaptern identifizieren und
    entfernen

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
- KDoc/Vertragsdoku fuer `DataExportHelpers.resolveFilter` nachziehen;
  der Code traegt bereits den Kommentar "Trust-Boundary ist die lokale
  Shell" — Phase A formalisiert diese implizite Entscheidung in KDoc
  und CLI-Hilfe, nicht als Neuentdeckung
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

Testinfrastruktur:

- die Identifier-Utility selbst wird als reine Unit-Tests abgedeckt
  (kein DB-Zugang noetig)
- Profiling-/Introspection-Adapter-Tests brauchen echte
  DB-Connections; die bestehende Testcontainers-Infrastruktur fuer
  PostgreSQL und MySQL sowie die In-Memory-SQLite-Anbindung sind dafuer
  zu nutzen
- boesartige Identifier muessen als wiederverwendbare Test-Fixtures
  bereitgestellt werden, damit alle drei Dialekte denselben Satz
  problematischer Namen durchlaufen

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
- `hexagon/application/.../cli/commands/DataExportHelpers.kt`
  (liegt im `cli.commands`-Paket, nicht im Application-Layer —
  ggf. fuer Phase B relevant)
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

## 9. Aufwandseinschaetzung

Grobe Einschaetzung pro Arbeitspaket (T-Shirt-Sizing):

- 5.1 (Identifier-Utility): S — definiertes API, drei Dialektregeln
- 5.2 (Adapter-Haertung): L — sechs Adapter, SQLite-PRAGMA-Sonderfall,
  DDL-Generator-Konsolidierung
- 5.3 (Raw-SQL-Vertrag): S — primaer Doku und KDoc-Anpassungen
- 5.4 (Security-Tests): M — Fixture-Erstellung, drei Dialekte,
  Integrationstests mit echten Datenbanken

---

## 10. Entscheidungsempfehlung

Phase A sollte wie in `docs/implementation-plan-0.9.1.md` vorgesehen
als erste 0.9.1-Phase umgesetzt werden.

Begruendung:

- sie schliesst reale Sicherheits-Findings aus `docs/quality.md`
- sie reduziert die Regressionsflaeche der spaeteren strukturellen
  Refactors
- sie schafft eine saubere Basis fuer Phase B, ohne bereits den grossen
  Orchestrierungs- und Modulumbau mitzuziehen
