# Implementierungsplan: Phase E - FK-/Topo-Sort-Utility extrahieren

> **Milestone**: 0.9.1 - Library-Refactor und Integrationsschnitt
> **Phase**: E (FK-/Topo-Sort-Utility extrahieren)
> **Status**: Done (2026-04-18)
> **Referenz**: `docs/implementation-plan-0.9.1.md` Abschnitt 1 bis 5,
> Abschnitt 6.5, Abschnitt 7, Abschnitt 8 und Abschnitt 9;
> `docs/d-browser-integration-coupling-assessment.md`;
> `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`;
> `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/AbstractDdlGeneratorTest.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/ImportDirectoryResolver.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportSchemaPreflightTest.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`;
> `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/DataTransferRunnerTest.kt`;
> `docs/architecture.md`.

---

## 1. Ziel

Phase E zieht die mehrfach vorhandene FK-/Topo-Sort-Logik in eine kleine,
wiederverwendbare Core-Utility zusammen, ohne die heute bewusst
unterschiedlichen Fehler- und Diagnosevertraege von DDL-, Import- und
Transfer-Pfad zu verwischen.

Der Teilplan beantwortet bewusst zuerst die Struktur- und
Vertragsfragen:

- welche gemeinsame Semantik in den drei bestehenden Implementierungen
  tatsaechlich identisch ist
- welche Unterschiede bewusst erhalten bleiben muessen
- wie eine kleine Core-Utility fuer Tabellenabhaengigkeiten und
  Zyklusdiagnostik aussehen soll
- wie DDL-, Directory-Import- und Transfer-Pfad auf denselben Helfer
  umgestellt werden, ohne ihre heute sichtbaren Nutzervertraege zu
  verlieren
- wie dabei Mehrspalten-FKs und verschiedene Referenzquellen
  (`column.references` versus `constraint.references`) sauber getragen
  werden

Phase E liefert damit keine neue Endnutzerfunktion, sondern eine
reduzierte Regressions- und Wartungsflaeche fuer FK-abhaengige
Tabellenordnung.

Nach Phase E soll klar gelten:

- es gibt genau eine kleine Core-Utility fuer FK-basierte
  Tabellenabhaengigkeiten
- DDL-, Directory-Import- und Transfer-Pfad duplizieren keine eigene
  Topo-Sort-Implementierung mehr
- Zyklusdiagnostik ist zentral modelliert, aber ihre Darstellung bleibt
  callsite-spezifisch
- die Utility ist auf Schemaebene wiederverwendbar, nicht als
  universeller Baum- oder Row-Graph-Walker

---

## 2. Ausgangslage

Im aktuellen Repo existieren drei relevante Implementierungen:

- `AbstractDdlGenerator.topologicalSort(...)`
- `ImportDirectoryResolver.topologicalSort(...)`
- `DataTransferRunner.topoSort(...)`

Diese drei Varianten teilen Grundsemantik, unterscheiden sich aber in
wichtigen Details:

- **DDL-Pfad** (`AbstractDdlGenerator`):
  - arbeitet auf `Map<String, TableDefinition>`
  - betrachtet aktuell nur `column.references`
  - ignoriert Selbstreferenzen
  - ignoriert Referenzen auf unbekannte Tabellen
  - liefert auch bei FK-Zyklen noch eine sortierte Reihenfolge plus
    `circularEdges`
  - der Generator verarbeitet Resttabellen weiter und delegiert die
    Diagnose an `handleCircularReferences(...)`
- **Directory-Import-Pfad** (`ImportDirectoryResolver`):
  - arbeitet auf einer selektierten Tabellenmenge fuer
    `ImportInput.Directory`
  - betrachtet sowohl `column.references` als auch
    `ConstraintType.FOREIGN_KEY`
  - mappt Referenzen vorab ueber `schemaToCandidate`, weil Dateinamen
    und Schema-Tabellennamen auseinanderfallen koennen
  - ignoriert Selbstreferenzen und Referenzen ausserhalb der selektierten
    Tabellenmenge
  - bricht bei Zyklus mit detaillierter Kantenliste ab
- **Transfer-Pfad** (`DataTransferRunner`):
  - arbeitet auf einer `List<String>` der zu transferierenden Tabellen
  - betrachtet sowohl `column.references` als auch
    `constraint.references`
  - ignoriert Selbstreferenzen und Referenzen ausserhalb der
    ausgewaehlten Tabellenmenge
  - bricht bei Zyklus hart mit kompakter Meldung `FK cycle: ...` ab
  - sortiert initiale null-In-Degree-Knoten aktuell alphabetisch

Wichtige Ist-Feststellungen:

- es gibt noch **keine** extrahierte Core-Utility fuer
  Tabellenabhaengigkeiten
- `ImportDirectoryResolver` enthaelt bereits die fachlich reichste
  Extraktionsbasis, weil dort:
  - Spaltenreferenzen und FK-Constraints gemeinsam getragen werden
  - eine referenzseitige Namensprojektion moeglich ist
  - Zykluskanten strukturiert gesammelt werden
- `AbstractDdlGenerator` bildet derzeit eine schmalere Semantik ab, weil
  mehrspaltige/table-level FKs aus `constraints` dort noch nicht Teil
  des Sortierinputs sind

Konsequenz:

- ein naiver Extract aus dem DDL-Pfad waere fachlich zu schmal
- ein naiver Extract aus dem Import-Pfad darf die DDL-spezifische
  "weiterlaufen trotz Zyklus"-Semantik nicht ueberformen
- die gemeinsame Utility braucht deshalb:
  - zentrale Graph-/Sortierlogik
  - parametrisierbare Referenzprojektion
  - strukturierte Zyklusdiagnostik
  - callsite-spezifische Reaktion oberhalb der Utility

---

## 3. Scope fuer Phase E

### 3.1 In Scope

- Herausarbeiten einer gemeinsamen Core-Semantik fuer:
  - Tabellenreihenfolge aus FK-Abhaengigkeiten
  - Ignorieren von Selbstreferenzen
  - Ignorieren unbekannter bzw. nicht selektierter Referenzen
  - strukturierte Zykluskanten
- Einfuehrung einer kleinen Utility in `hexagon:core`
- Unterstuetzung fuer beide heute relevanten Referenzquellen:
  - `column.references`
  - `ConstraintType.FOREIGN_KEY` mit `constraint.references`
- Moeglichkeit, referenzierte Tabellennamen vor der Graphbildung auf eine
  selektierte Zielmenge zu projizieren
- Umstellung der drei heutigen Call-Sites:
  - `AbstractDdlGenerator`
  - `ImportDirectoryResolver`
  - `DataTransferRunner`
- Tests fuer die neue Utility und die drei umgestellten Verbraucher
- Kover-Coverage bleibt pro betroffenem Modul bei dessen bestehendem
  Schwellenwert:
  - `hexagon:core` und `adapters:driven:driver-common` weiter 90 %
  - `adapters:driving:cli` weiter 80 %
  - `hexagon:application` hat derzeit keine eigene modul-lokale
    `koverVerify`-Schwelle; Phase E fuehrt dort keinen neuen
    Coverage-Gate ein

### 3.2 Bewusst nicht Teil von Phase E

- Baumprojektion oder Datensatz-Graph-Traversal fuer externe Consumer
- generische Sicht-/Function-/Procedure-Dependency-Analyse
- Umbau des gesamten DDL-Vertrags
- neuer Import- oder Transfer-Nutzervertrag
- Port-/Modulschnitt aus Phase C/D

Praezisierung:

Phase E loest zuerst "wie vermeiden wir dreifache FK-/Topo-Sort-
Duplikation auf Schemaebene?", nicht "wie bauen wir einen universellen
Dependency-Graph fuer alle Objekttypen?".

---

## 4. Leitentscheidungen fuer Phase E

### 4.1 Die Utility lebt in `hexagon:core`

Verbindliche Entscheidung:

- die neue FK-/Topo-Sort-Utility lebt im Core, voraussichtlich unter
  `dev.dmigrate.core.dependency`
- sie darf von Treibern, CLI-nahen Preflight-Pfaden und
  Application-Use-Cases ohne gegenseitige Schichtverletzung genutzt
  werden

Folge:

- die Utility wird nicht an einen einzelnen Adapterpfad angehaengt
- der Reuse-Gewinn wird repo-weit sichtbar

### 4.2 Die Utility liefert Analyse, nicht Nutzerreaktion

Verbindliche Entscheidung:

- die Utility liefert:
  - sortierte Tabellenreihenfolge
  - erkannte zyklische Kanten
  - ggf. weitere kleine Diagnosemetadaten
- sie entscheidet **nicht**, ob ein Zyklus ein harter Fehler oder ein
  "weiter mit Notes"-Fall ist

Folge:

- DDL darf weiterhin bei Zyklus fortsetzen
- Import/Transfer duerfen weiterhin preflight-seitig abbrechen
- die Semantik der Reaktion bleibt bei den Call-Sites

### 4.3 Constraint-FKs gehoeren zum fachlichen Endzustand dazu

Verbindliche Entscheidung:

- die extrahierte Utility muss neben `column.references` auch
  `ConstraintType.FOREIGN_KEY` unterstuetzen
- Phase E darf nicht den schmaleren DDL-Iststand als Endzustand
  verfestigen
- DDL kann beim Umschluss zunaechst bewusst eine Teilmenge nutzen, der
  Core-Helfer selbst muss aber fuer beide Referenzquellen ausgelegt
  sein

### 4.4 Referenzprojektion ist Teil des Vertrages

Verbindliche Entscheidung:

- die Utility muss eine optionale Projektion von Referenzzielen
  unterstuetzen, damit `ImportDirectoryResolver` Dateinamen auf
  Schema-Tabellen mappen kann
- Referenzen, die nach dieser Projektion ausserhalb der betrachteten
  Zielmenge liegen, werden ignoriert

Damit bleibt die Import-Semantik ohne Zusatzlogik ausserhalb der Utility
abbildbar.

### 4.5 Das Kantenmodell darf spaltennahe und tabellennahe Pfade zugleich tragen

Verbindliche Entscheidung:

- das zentrale Kantenmodell darf `fromColumn` und `toColumn` optional
  modellieren
- Directory-Import und DDL duerfen weiter spaltennahe Kanten mit
  konkreten Column-Namen liefern
- der Transfer-Pfad darf zunaechst bei tabellennaher Kantenauflosung
  bleiben, solange sein kompakter Nutzervertrag (`FK cycle: ...`)
  unveraendert bleibt
- falls spaeter auch der Transfer-Pfad detailliertere Kanten liefern
  soll, ist das eine additive Verbesserung, kein Phase-E-Muss

Folge:

- die Utility zwingt `DataTransferRunner` nicht in eine kuenstliche
  Scheingenauigkeit
- das gemeinsame Modell bleibt trotzdem reich genug fuer die heute
  detailreichste Call-Site (`ImportDirectoryResolver`)

### 4.6 Stabile Reihenfolge bleibt callsite-nah steuerbar

Verbindliche Entscheidung:

- die Utility darf eine Basisreihenfolge bzw. Originalreihenfolge als
  Eingangsordnung respektieren
- Phase E erzwingt keine repo-weite Alphabetisierung
- unterschiedliche Stabilitaetsbedarfe der Call-Sites bleiben zulaessig:
  - DDL: Schema-/Map-Reihenfolge
  - Directory-Import: Kandidaten-/Filterreihenfolge
  - Transfer: heute alphabetischer Null-In-Degree-Start, sofern bewusst
    beibehalten

### 4.7 Die Utility ist kein `d-browser`-Baumhelfer

Verbindliche Entscheidung:

- Phase E adressiert ausschliesslich Tabellenabhaengigkeiten auf
  Schemaebene
- sie ist kein Ersatz fuer Row-Graph-Walks, Rekursion ueber
  Datensatzbeziehungen oder UI-seitige Baumprojektionen

Damit bleibt der Scope mit dem `d-browser`-Assessment konsistent.

### 4.8 Phase E ist inkrementell pro Call-Site lieferbar

Verbindliche Entscheidung:

- die drei Umstellungen auf die neue Utility sind technisch
  voneinander entkoppelbar:
  - DDL-Pfad
  - Directory-Import-Pfad
  - Transfer-Pfad
- der Zielzustand verlangt am Ende alle drei umgestellten Call-Sites,
  aber Zwischenstaende duerfen buildfaehig und testbar sein
- kein Big-Bang-Umschluss ist erforderlich; bei unerwarteten
  Semantikdifferenzen kann nach einer sauber migrierten Call-Site
  pausiert werden

Folge:

- das Refactor-Risiko sinkt
- Unterschiede zwischen DDL-, Import- und Transfer-Pfad koennen
  schrittweise sichtbar und testbar gehalten werden

---

## 5. Konkrete Arbeitspakete

Abhaengigkeiten und Reihenfolge:

1. **5.1** zieht die gemeinsame Fachsemantik fest
2. **5.2** fuehrt die Core-Utility ein
3. **5.3** schliesst DDL-, Import- und Transfer-Pfad an
4. **5.4** zieht Tests und Doku nach

### 5.1 Gemeinsame Fachsemantik der drei Varianten herausarbeiten

- die drei heutigen Implementierungen explizit gegeneinander mappen:
  - Eingabetyp
  - beruecksichtigte Referenzquellen
  - Stabilitaetsverhalten
  - Zyklusreaktion
  - Fehler-/Diagnoseformat
- den kleinsten gemeinsamen Nenner festziehen:
  - gerichteter Tabellen-Dependency-Graph
  - Ignorieren von Selbstreferenzen
  - Ignorieren von Referenzen ausserhalb der betrachteten Tabellenmenge
  - strukturierte Kanten fuer erkannte Zyklen
- die bewussten Unterschiede als Callsite-Verhalten oberhalb des Helpers
  dokumentieren

Ergebnis:

Ein belastbarer Fachkern statt eines impliziten "sieht aehnlich aus".

### 5.2 Kleine Core-Utility fuer Tabellenabhaengigkeiten einfuehren

- eine kleine Utility in `hexagon:core` einfuehren, Arbeitsnamen z. B.:
  - `TableDependencyGraph`
  - `ForeignKeyTopoSort`
  - `TableDependencyOrderer`
  - Zielpackage: `dev.dmigrate.core.dependency`
- ein Result-Modell definieren, das mindestens traegt:
  - sortierte Tabellenreihenfolge
  - zyklische Kanten
- ein kleines Kantenmodell definieren, das aus Call-Sites formatierbar
  bleibt, z. B.:
  - `fromTable`
  - `fromColumn?`
  - `toTable`
  - `toColumn?`
- Referenzsammlung so schneiden, dass beide Quellarten moeglich sind:
  - Spaltenreferenzen
  - FK-Constraints mit Mehrspaltenbezug
- optionale Referenzprojektion fuer Import-Use-Cases vorsehen
- der Transfer-Pfad darf dieses Modell zunaechst mit tabellennahen
  Kanten ohne Spaltennamen befuellen; DDL/Import duerfen detaillierter
  sein

Pragmatische Zusatzregel:

- die Utility muss nicht sofort ein maximales DSL-artiges API bekommen
- wichtiger ist ein kleiner, klarer Vertrag, der die drei aktuellen
  Call-Sites sauber abdeckt

Ergebnis:

Es gibt genau einen fachlichen Ort fuer Tabellenabhaengigkeiten und
Zykluskanten.

### 5.3 DDL-, Directory-Import- und Transfer-Pfad auf die Utility umstellen

- `AbstractDdlGenerator.topologicalSort(...)` auf den neuen Helfer
  reduzieren oder komplett durch ihn ersetzen
- `ImportDirectoryResolver.topologicalSort(...)` auf den neuen Helfer
  umstellen und die bestehende Namensprojektion erhalten
- `DataTransferRunner.topoSort(...)` auf den neuen Helfer umstellen
- die heutige Unterschiedslogik oberhalb der Utility beibehalten:
  - DDL: Resttabellen weiter verarbeiten, `circularEdges` an
    `handleCircularReferences(...)` geben
  - Directory-Import: bei Zyklus `ImportPreflightException` mit
    detaillierter Kantenliste
  - Transfer: bei Zyklus `TransferPreflightException` mit kompakter
    Fehlermeldung
- der Transfer-Pfad muss fuer Phase E nicht kuenstlich auf
  spaltennahe Fehlerdetails erweitert werden; sein kompakter
  Nutzervertrag darf bewusst bestehen bleiben
- dabei pruefen, ob DDL durch die neue Utility bewusst auch table-level
  FK-Constraints fuer die Sortierung nutzen soll; falls nicht, muss
  diese Einschraenkung explizit dokumentiert bleiben

Ergebnis:

Die Logik ist zentral, die Nutzerreaktion bleibt pfadspezifisch.

### 5.4 Tests, Konsistenzpruefung und Doku nachziehen

- neue Core-Tests fuer den extrahierten Helfer anlegen:
  - stabile Reihenfolge
  - Selbstreferenzen
  - unbekannte Referenzen
  - einfache Zyklen
  - Mehrspalten-FKs ueber Constraints
  - Referenzprojektion
- bestehende DDL-, Import- und Transfer-Tests auf den neuen Helfer
  nachziehen
- Doku aktualisieren:
  - `docs/implementation-plan-0.9.1.md`
  - `docs/architecture.md`
  - ggf. Integrations- oder Architekturtexte mit FK-/Topo-Sort-
    Beschreibung

Ergebnis:

Die Extraktion ist nicht nur intern, sondern auch test- und doku-
seitig konsistent.

---

## 6. Verifikation

Pflichtfaelle:

- Unit-Tests fuer die neue FK-/Topo-Sort-Utility in `hexagon:core`
- Regressionstests, dass `AbstractDdlGenerator`:
  - weiterhin stabile Tabellenreihenfolge liefert
  - Selbstreferenzen ignoriert
  - bei Zyklen `circularEdges` weitergibt statt hart abzubrechen
  - DDL-Constraint-FK-Entscheidung (siehe 5.3) explizit absichern:
    - falls DDL table-level `ConstraintType.FOREIGN_KEY` kuenftig
      mitnutzt: neuer Test, der zeigt, dass Constraint-FKs die
      Sortierung beeinflussen
    - falls DDL bewusst weiter nur `column.references` nutzt: neuer
      Nicht-Regressions-Test, der zeigt, dass table-level Constraints
      die DDL-Sortierung nicht veraendern, plus dokumentierter
      Kommentar im Code warum
- Regressionstests, dass `ImportDirectoryResolver`:
  - Spalten- und Constraint-FKs beruecksichtigt
  - Namensprojektion fuer Directory-Dateien korrekt anwendet
  - Zykluskanten detailreich meldet
- Regressionstests, dass `DataTransferRunner`:
  - FK-Zyklen weiter als Preflight-Fehler meldet
  - seinen bestehenden Nutzervertrag nicht still aendert
- Kover-Coverage bleibt pro betroffenem Modul bei dessen bestehendem
  Schwellenwert:
  - `hexagon:core` und `adapters:driven:driver-common` weiter 90 %
  - `adapters:driving:cli` weiter 80 %
  - `hexagon:application` weiterhin ohne neue modul-lokale
    `koverVerify`-Schwelle in Phase E

Erwuenschte Zusatzfaelle:

- explizite Vergleichstests fuer dieselbe Eingabestruktur ueber alle
  drei Call-Sites hinweg, damit gewollte Unterschiede und ungewollte
  Divergenzen sichtbar bleiben
- ein kleiner Goldentest fuer Mehrspalten-FK-Zyklen

---

## 7. Betroffene Codebasis

Direkt betroffen:

- neue Utility unter
  `hexagon/core/src/main/kotlin/dev/dmigrate/core/dependency/...`
- neue Tests unter
  `hexagon/core/src/test/kotlin/dev/dmigrate/core/dependency/...`
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`
- `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/AbstractDdlGeneratorTest.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/ImportDirectoryResolver.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportSchemaPreflightTest.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/DataTransferRunnerTest.kt`

Wahrscheinlich mit betroffen:

- `build.gradle.kts` (Root-Kover-Aggregation mit expliziter Projektliste)
- `docs/implementation-plan-0.9.1.md`
- `docs/architecture.md`
- evtl. weitere Tests oder Helfer rund um Import-Preflight

---

## 8. Risiken und offene Punkte

### 8.1 Ein zu schmaler Extract aus dem DDL-Pfad wuerde Constraint-FKs verlieren

`AbstractDdlGenerator.topologicalSort(...)` betrachtet heute nur
`column.references`. Ein Copy-Move dieser Logik in den Core wuerde die
reichere Semantik von Import- und Transfer-Pfad regressieren.

Mitigation:

- die Utility von Anfang an fuer Spalten- und Constraint-FKs auslegen
- DDL-Iststand nicht unkritisch als Endzustand verfestigen

### 8.2 Ein zu "smarter" Universalhelfer wuerde die Call-Sites verkomplizieren

Wenn die Utility Analyse, Fehlerreaktion und Stringformatierung zugleich
uebernehmen will, wird sie schnell schwer lesbar und schlecht
wiederverwendbar.

Mitigation:

- Analyse im Core, Reaktion in der Call-Site halten
- Result- und Kantenmodell klein halten

### 8.3 Stabile Reihenfolge kann unbeabsichtigt kippen

Die drei heutigen Implementierungen haben nicht exakt dieselbe
Tie-Breaking-Strategie. Ein Zentralhelfer kann dadurch scheinbar kleine,
aber sichtbare Reihenfolgeaenderungen ausloesen.

Mitigation:

- Eingangsordnung bzw. Tie-Breaking bewusst modellieren
- bisherige Reihenfolge in den Verbraucher-Tests explizit absichern

### 8.4 Detaillierte Zyklusdiagnostik kann beim Refactor versehentlich verarmen

Gerade der Directory-Import meldet heute konkrete Kanten wie
`a.b_id -> b.id`. Diese Details duerfen durch Zentralisierung nicht auf
eine grobe "FK cycle"-Meldung schrumpfen.

Mitigation:

- Kantenmodell mit `fromTable`/`toTable` und optionalen
  `fromColumn`/`toColumn` beibehalten
- Fehlermeldungsdetails in den bestehenden Tests halten

### 8.5 Scope-Druck Richtung `d-browser`-Baumprojektion vermeiden

Die Extraktion ist fuer Tabellenabhaengigkeiten auf Schemaebene
gedacht. Daraus einen generischen Datensatz-Graph-Helfer machen zu
wollen, wuerde den Scope unnötig aufblaehen.

Mitigation:

- Utility explizit als Tabellen-/Schema-Helfer benennen
- andere Graphbedarfe als Folgearbeit behandeln

---

## 9. Entscheidungsempfehlung

Phase E sollte in 0.9.1 umgesetzt werden, weil sie eine klar benennbare
Mehrfachduplikation in einen kleinen, repo-weit nuetzlichen
Core-Helfer ueberfuehrt.

Empfohlener Zuschnitt:

1. gemeinsame Fachsemantik der drei heutigen Varianten explizit
   festziehen
2. kleine Core-Utility fuer Tabellenabhaengigkeiten und Zykluskanten
   einfuehren
3. DDL-, Directory-Import- und Transfer-Pfad darauf umstellen
4. die jeweiligen Fehler- und Diagnosevertraege oberhalb der Utility
   bewusst beibehalten

Damit liefert Phase E einen wiederverwendbaren FK-/Topo-Sort-Vertrag,
ohne ihn zu einem ueberfrachteten Universal-Graphen auszubauen.
