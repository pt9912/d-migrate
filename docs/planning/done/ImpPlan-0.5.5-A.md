# Implementierungsplan: Phase A - Spezifikationsbereinigung und Ist-Abgleich

> **Milestone**: 0.5.5 - Erweitertes Typsystem
> **Phase**: A (Spezifikationsbereinigung und Ist-Abgleich)
> **Status**: Done (2026-04-13)
> **Referenz**: `docs/planning/implementation-plan-0.5.5.md` Abschnitt 1, Abschnitt 2,
> Abschnitt 3, Abschnitt 4, Abschnitt 5 Phase A, Abschnitt 7, Abschnitt 8,
> Abschnitt 9, Abschnitt 10; `docs/planning/change-request-spatial-types.md`

---

## 1. Ziel

Phase A zieht die freigegebene Spatial-Entscheidung aus dem Change Request in
die regulaeren Spezifikationsdokumente ueber und gleicht diese mit dem realen
Ist-Zustand von 0.5.5 ab.

Der Teilplan liefert bewusst noch keine Code-Umsetzung. Ergebnis von Phase A
ist eine belastbare Dokumentationsbasis, auf der die spaeteren Phasen B bis F
ohne Auslegungsluecken arbeiten koennen.

Konkret soll nach Phase A klar und konsistent dokumentiert sein:

- was `geometry` in 0.5.5 fachlich bedeutet
- welche Rolle `geometry_type` und `srid` haben
- dass `spatialProfile` zur Generator-Konfiguration und nicht ins Schema gehoert
- welche Faelle `schema validate` prueft und welche erst bei
  `schema generate` entstehen
- welche Spatial-Faelle explizit ausserhalb von 0.5.5 bleiben

---

## 2. Ausgangslage

Aktueller Stand der Dokumentation:

- `docs/planning/change-request-spatial-types.md` ist bereits als
  `Approved for Milestone 0.5.5` markiert und beschreibt das freigegebene
  Spatial-Phase-1-Modell fachlich detailliert.
- `spec/neutral-model-spec.md` dokumentiert bereits `uuid`, `json`, `xml`,
  `binary`, `enum` und `array`, aber noch keinen neutralen Typ `geometry` und
  keine Attribute `geometry_type` oder `srid`.
- `spec/ddl-generation-rules.md` beschreibt den allgemeinen
  `schema generate`-/Report-Vertrag, enthaelt aber noch keine produktive
  Spatial-Spezifikation fuer PostgreSQL/PostGIS, MySQL und SQLite/SpatiaLite.
- `spec/cli-spec.md` dokumentiert `schema generate` aktuell ohne
  `--spatial-profile` und ohne dialektspezifische Default-Profile.
- `spec/architecture.md` beschreibt `DdlGenerator` und
  `SchemaGenerateRunner`, aber noch keinen expliziten Generator-Options-Pfad
  fuer Spatial.

Konsequenz:

- Die fachliche Entscheidung liegt derzeit noch primaer im Change Request.
- Die regulaeren Dokumente sind fuer 0.5.5 noch nicht der vollstaendige
  produktive Vertrag.
- Ohne Phase A wuerden die spaeteren Implementierungsphasen gegen eine
  verteilte und teils implizite Spezifikation arbeiten.

---

## 3. Scope fuer Phase A

### 3.1 In Scope

- Konsolidierung der Spatial-Phase-1-Regeln in den regulaeren
  Spezifikationsdokumenten
- Klarstellung des Generator-Profils `spatialProfile` fuer `schema generate`
- Trennung von schema-zentrierter Validierung vs. generatorseitigen
  Transformations-Notes
- Dokumentation der 0.5.5-Grenzen fuer `geometry`, `geometry_type`, `srid`
- Dokumentation der Haertung von `uuid`, `json`, `binary` und `array`, soweit
  sie fuer den 0.5.5-Vertrag relevant ist
- ausdruecklicher Verweis darauf, dass der Change Request ab dann
  Entscheidungsbasis und nicht mehr Hauptspezifikation ist

### 3.2 Bewusst nicht Teil von Phase A

- Implementierung in `hexagon:core`, `hexagon:ports`, `hexagon:application`
  oder den DDL-Drivern
- CLI-Parsing oder Generator-Optionen im Code
- Tests, Golden Masters oder Fixtures
- Reverse-Engineering fuer Spatial
- `type: geography`, `z`, `m` oder Spatial-Indizes
- neue Dialekte oder Live-Erkennung von PostGIS bzw. SpatiaLite

---

## 4. Leitentscheidungen fuer Phase A

### 4.1 Regulaere Docs werden die produktive Quelle

Nach Phase A muessen `spec/neutral-model-spec.md`,
`spec/ddl-generation-rules.md`, `spec/cli-spec.md` und
`spec/architecture.md` gemeinsam den produktiven 0.5.5-Vertrag tragen.

Der Change Request bleibt erhalten, aber nur noch als:

- Entscheidungsgrundlage
- Herleitung der freigegebenen Phase-1-Grenzen
- Referenz fuer spaetere Erweiterungen

### 4.2 Spatial-Profil bleibt ausserhalb des Schemas

Phase A dokumentiert verbindlich:

- `type: geometry` ist Teil des neutralen Modells
- `spatialProfile` ist Teil der Generator-Konfiguration
- Profil-Defaults werden pro Zieldialekt dokumentiert, nicht in YAML-Feldern

### 4.3 Validierung und Generierung werden sauber getrennt

Die Spezifikation muss ausdruecklich zwischen zwei Ebenen unterscheiden:

- `schema validate` prueft das neutrale Schema selbst
- `schema generate` bewertet die Generierbarkeit im gewaehlten Zielprofil

Verbindliche Einordnung in Phase A:

- `E120` und `E121` sind Schema-/Modellregeln
- `E052` und `W120` sind Generator-/Report-Regeln

### 4.4 0.5.5 dokumentiert Spatial Phase 1, nicht mehr

Die Dokumentation darf keine halbfertige Vorab-Freigabe fuer spaetere
Spatial-Funktionalitaet suggerieren.

Explizit ausserhalb des dokumentierten 0.5.5-Vertrags bleiben:

- `type: geography`
- `z`
- `m`
- Reverse-Engineering von Spatial-Spalten
- automatische Extension-Erkennung oder -Installation

### 4.5 Array-Haertung bleibt Teil des Gesamtvertrags

Phase A beschreibt nicht nur Spatial neu, sondern schaerft auch die Doku fuer
bereits vorhandene erweiterte Typen.

Insbesondere muss klar bleiben:

- `geometry` wird Basistyp des neutralen Modells
- `geometry` wird dadurch in 0.5.5 nicht automatisch zulaessiger
  `array.element_type`
- Basistyp-Allowlist und Array-Element-Allowlist sind getrennte Vertraege

---

## 5. Arbeitspakete

### A.1 `spec/neutral-model-spec.md`

Einpflegen von:

- neuem Basistyp `geometry`
- Attributen `geometry_type` und `srid`
- erlaubten `geometry_type`-Werten samt Default `geometry`
- YAML-Beispielen fuer Phase 1
- klarer Abgrenzung zu `geography`, `z` und `m`
- Hinweis, dass `geometry` nicht Teil der 0.5.5-Array-Element-Allowlist ist

### A.2 `spec/ddl-generation-rules.md`

Konkretisierung von:

- Spatial-Mapping fuer PostgreSQL/PostGIS, MySQL und SQLite/SpatiaLite
- Profilverhalten `postgis`, `native`, `spatialite`, `none`
- `action_required` mit `E052` fuer nicht generierbare Spatial-Faelle
- `W120` fuer MySQL-SRID-Best-Effort
- Tabellenblockierung statt partieller Tabellen-DDL bei PostgreSQL/SQLite
- Verzicht auf automatisches `CREATE EXTENSION` fuer PostGIS
- Rollback-Pflichten fuer Spatial-Zusatzstatements

### A.3 `spec/cli-spec.md`

Erweiterung von `schema generate` um:

- Flag `--spatial-profile`
- erlaubte Profilwerte je Dialekt
- dokumentierte Defaults:
  - PostgreSQL -> `postgis`
  - MySQL -> `native`
  - SQLite -> `none`
- Fehlerfall fuer unzulaessige Dialekt/Profil-Kombinationen
- Sichtbarkeit von `E052` und `W120` im bestehenden Ausgabe- und Report-Vertrag

### A.4 `spec/architecture.md`

Ergaenzung um:

- expliziten Generator-Options-Pfad zwischen CLI, Application und Driver-Port
- Abgrenzung zwischen Schema-Modell, Validierung und Generierung
- Verortung der Typsystem-Erweiterung fuer 0.5.5 als Voraussetzung fuer 0.6.0

### A.5 `docs/planning/change-request-spatial-types.md`

Keine inhaltliche Produktivpflege mehr, sondern nur noch:

- konsistente Referenz auf die freigegebene Entscheidung
- explizite Kennzeichnung als Entscheidungsbasis statt Hauptspezifikation
- Vermeidung widerspruechlicher Detailregeln gegenueber den regulaeren Docs

Ziel von Phase A:

- Vor Beginn der Code-Phasen ist dokumentiert, was 0.5.5 liefert.
- Spatial-Scope, Generator-Profil und Fallback-Verhalten sind ohne
  Interpretationsspielraum beschrieben.

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `spec/neutral-model-spec.md`
- `spec/ddl-generation-rules.md`
- `spec/cli-spec.md`
- `spec/architecture.md`
- `docs/planning/change-request-spatial-types.md`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/planning/implementation-plan-0.5.5.md`
- `docs/planning/roadmap.md`

---

## 7. Akzeptanzkriterien

- [ ] `spec/neutral-model-spec.md` beschreibt `geometry`,
      `geometry_type` und `srid` explizit und konsistent mit dem freigegebenen
      CR-Phase-1-Scope.
- [ ] `spec/ddl-generation-rules.md` dokumentiert Spatial-Regeln fuer
      PostgreSQL/PostGIS, MySQL und SQLite/SpatiaLite inklusive `E052` und
      `W120`.
- [ ] `spec/cli-spec.md` beschreibt `--spatial-profile`, die zulaessigen Werte
      je Dialekt und die dokumentierten Defaults.
- [ ] `spec/architecture.md` beschreibt den neuen Generator-Options-Pfad
      nachvollziehbar und ohne das Profil ins Schema zu ziehen.
- [ ] Die regulaeren Docs unterscheiden explizit zwischen:
      `E120`/`E121` als Modell-/Validierungsregeln und `E052`/`W120` als
      Generator-/Report-Regeln.
- [ ] PostgreSQL- und SQLite-Faelle mit Profil `none` sind als
      Tabellenblockierung mit `action_required` beschrieben; partielle
      Tabellen-DDL ohne Spatial-Spalte wird nicht als zulaessiger 0.5.5-Pfad
      dokumentiert.
- [ ] MySQL-SRID-Best-Effort und der zugehoerige Warning-Pfad `W120` sind
      dokumentiert.
- [ ] Der Dokumentationsvertrag fuer `schema generate` beschreibt explizit den
      Spatial-Bezug von `--generate-rollback`, JSON-Output und Sidecar-Report.
- [ ] `geometry` ist in den regulaeren Docs nicht stillschweigend als
      zulaessiger `array.element_type` dargestellt.
- [ ] `type: geography`, `z`, `m`, Reverse-Engineering und Spatial-Indizes sind
      in allen relevanten Docs sichtbar ausserhalb von 0.5.5 gehalten.
- [ ] Der Change Request ist nach Phase A nicht mehr die einzige Stelle, an der
      der produktive Spatial-Vertrag beschrieben ist.
- [ ] `docs/planning/change-request-spatial-types.md` ist als Entscheidungsbasis
      eingeordnet und widerspricht den regulaeren Spezifikationsdokumenten
      nicht.

---

## 8. Verifikation

Phase A wird dokumentationsseitig verifiziert, nicht ueber Code- oder
Integrationstests.

Mindestumfang:

1. Querlesen der vier regulaeren Spezifikationsdokumente auf konsistente
   Begriffe, Defaults und Fehlercodes.
2. Abgleich gegen `docs/planning/change-request-spatial-types.md`, damit die regulaeren
   Docs den freigegebenen Phase-1-Scope korrekt uebernehmen.
3. Explizite Gegenpruefung auf Widersprueche bei:
   - `geometry_type`-Allowlist
   - `srid`-Regel
   - Dialekt-Defaults fuer `spatialProfile`
   - Trennung von `schema validate` vs. `schema generate`
   - Tabellenblockierung vs. partielle DDL
   - Spatial-Verhalten in `--generate-rollback`, JSON-Output und Sidecar-Report
4. Abschluss-Review, dass Spatial-Phase-2-Begriffe nicht versehentlich als
   0.5.5-Ist-Zustand beschrieben werden.

Praktische Review-Hilfe:

- gezielte Volltextsuche nach `geometry`, `spatial`, `srid`,
  `geometry_type`, `spatial-profile`, `E052`, `W120`,
  `generate-rollback`, `output-format json`, `report`
- Vergleich der `schema generate`-Beschreibung in `spec/cli-spec.md` mit den
  Regeln in `spec/ddl-generation-rules.md`

---

## 9. Risiken und offene Punkte

### R1 - Regulaere Docs laufen dem Code voraus oder hinterher

Phase A schafft den Sollvertrag vor der Implementierung. Ohne nachfolgenden
disziplinierten Abgleich in den Code-Phasen besteht das Risiko, dass Docs und
Code wieder auseinanderlaufen.

### R2 - Change Request und Produktivdoku duplizieren einander

Wenn Details nach Phase A parallel in CR und regulaeren Docs weitergepflegt
werden, entsteht erneut ein Zwei-Quellen-Problem.

### R3 - Spatial wird ueber den freigegebenen Scope hinaus beschrieben

Schon reine Begriffsverwendung zu `geography`, `z` oder `m` kann den Eindruck
einer Teilunterstuetzung erzeugen. Die Dokumentation muss diese Punkte klar als
spaeter markieren.

### R4 - Array-Haertung bleibt zu vage

Wenn die regulaeren Docs zwar `geometry` erwaehnen, aber die getrennten
Allowlists fuer Basistypen und Array-Elementtypen nicht klar benennen, bleibt
der 0.5.5-Vertrag an einer zentralen Stelle weiter implizit.

---

## 10. Abschlussdefinition

Phase A ist abgeschlossen, wenn die regulaeren Spezifikationsdokumente den
freigegebenen 0.5.5-Spatial-Vertrag konsistent und ohne Rueckgriff auf
Interpretation aus dem Change Request beschreiben.

Danach ist fuer die Code-Phasen klar:

- welches Modell gebaut werden soll
- welche Generator-Profile existieren
- welche Fehler- und Warning-Codes wo entstehen
- und welche Spatial-Faelle ausdruecklich nicht Teil von 0.5.5 sind
