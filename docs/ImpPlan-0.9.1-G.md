# Implementierungsplan: Phase G - Vorbereitung fuer 1.0.0-Publish dokumentieren

> **Milestone**: 0.9.1 - Library-Refactor und Integrationsschnitt
> **Phase**: G (Vorbereitung fuer 1.0.0-Publish dokumentieren)
> **Status**: Draft (2026-04-18)
> **Referenz**: `docs/implementation-plan-0.9.1.md` Abschnitt 1 bis 5,
> Abschnitt 6.7, Abschnitt 7, Abschnitt 8 und Abschnitt 9;
> `docs/releasing.md`;
> `docs/roadmap.md`;
> `docs/architecture.md`;
> `settings.gradle.kts`;
> `build.gradle.kts`;
> `hexagon/core/build.gradle.kts`;
> `hexagon/ports/build.gradle.kts`;
> `hexagon/application/build.gradle.kts`;
> `hexagon/profiling/build.gradle.kts`;
> `adapters/driven/driver-common/build.gradle.kts`;
> `adapters/driven/driver-postgresql/build.gradle.kts`;
> `adapters/driven/driver-mysql/build.gradle.kts`;
> `adapters/driven/driver-sqlite/build.gradle.kts`;
> `adapters/driven/formats/build.gradle.kts`;
> `adapters/driven/integrations/build.gradle.kts`;
> `adapters/driven/streaming/build.gradle.kts`;
> `adapters/driving/cli/build.gradle.kts`.

---

## 1. Ziel

Phase G dokumentiert die inhaltliche Vorstufe fuer einen spaeteren
`1.0.0`-Publish-Vertrag, ohne in `0.9.1` bereits Maven-Central-
Publishing, Signing oder Release-Automation einzubauen.

Der Teilplan beantwortet bewusst zuerst die Distributions- und
Vertragsfragen:

- welche heutigen Modulgruppen grundsaetzlich als spaetere
  Library-Artefakte taugen
- welche Artefakte fuer `1.0.0` als erste oeffentliche Maven-Central-
  Welle in Frage kommen
- welche Module bewusst intern oder CLI-zentriert bleiben sollen
- wie `docs/releasing.md` und `docs/roadmap.md` die Grenze zwischen
  `0.9.1`-Refactor und `1.0.0`-Publish konsistent beschreiben
- welche Entscheidungen bis `1.0.0` noch offen bleiben duerfen, ohne
  den vorbereitenden Dokumentationsstand zu verwaessern

Phase G liefert damit keinen Build- oder CI-Umbau, sondern eine
nachvollziehbare Publish-Landkarte fuer die nachgelagerte
`1.0.0`-Distribution.

Nach Phase G soll klar gelten:

- `0.9.1` stabilisiert Modulgrenzen und Integrationsflaechen, aber
  veroeffentlicht noch keinen oeffentlichen Library-Publish-Vertrag
- die spaetere `1.0.0`-Artefaktmenge ist fachlich vorstrukturiert und
  nach Modulgruppen eingeordnet
- Release Guide und Roadmap widersprechen dieser Trennung nicht
- interne Tool- und CLI-Module werden nicht versehentlich als
  oeffentliche Maven-Artefakte mitversprochen

---

## 2. Ausgangslage

Die aktuelle Release- und Modul-Lage ist klar, aber noch nicht auf einen
oeffentlichen Library-Publish zugeschnitten:

- `docs/releasing.md` beschreibt heute den Release-Ablauf fuer:
  - GitHub-Release-Assets
  - OCI-Image
  - Homebrew
- dass Maven Central noch **nicht** Teil dieses Vertrags ist, ist dort
  bereits explizit vermerkt
- `docs/roadmap.md` fuehrt `0.9.1` bewusst als
  "Library-Refactor und Integrationsschnitt"
  und `1.0.0` separat als Stable-Release mit
  Maven-Central-Portal-Publish-Workflow
- im Build existiert heute noch **kein** `maven-publish`-,
  Signing- oder Portal-Setup
- `settings.gradle.kts` zeigt die heutige Modulmenge:
  - Hexagon:
    - `hexagon:core`
    - `hexagon:ports`
    - `hexagon:application`
    - `hexagon:profiling`
  - driven Adapter:
    - `adapters:driven:driver-common`
    - `adapters:driven:driver-postgresql`
    - `adapters:driven:driver-mysql`
    - `adapters:driven:driver-sqlite`
    - `adapters:driven:formats`
    - `adapters:driven:integrations`
    - `adapters:driven:streaming`
  - driving Adapter:
    - `adapters:driving:cli`
  - Testmodule:
    - `test:integration-postgresql`
    - `test:integration-mysql`
- nach Phase C bis F kommen voraussichtlich hinzu:
  - `hexagon:ports-common`, `hexagon:ports-read`,
    `hexagon:ports-write` (Phase-C-Stretch-Goal)
  - `adapters:driven:driver-postgresql-profiling`,
    `adapters:driven:driver-mysql-profiling`,
    `adapters:driven:driver-sqlite-profiling` (Phase D)
  - `test:consumer-read-probe` (Phase F)

Wichtige Ist-Feststellungen:

- die sichtbare Release-Distribution ist aktuell **CLI-zentriert**
  und nicht library-zentriert
- mehrere Modulgrenzen sind durch `0.9.1` Phase C bis F bewusst noch in
  Bewegung:
  - Read-/Write-Portschnitt
  - Profiling-Extraktion aus Treiberkernen
  - FK-/Topo-Sort-Reuse
  - read-only Integrationsschnitt fuer `source-d-migrate`
- damit waere ein frueher oeffentlicher Publish-Vertrag fuer einzelne
  Module derzeit unnoetig riskant
- gleichzeitig ist die Modulmenge bereits so gross, dass spaetestens in
  `1.0.0` klar dokumentiert werden muss:
  - welche Artefakte oeffentliche Bibliotheken werden sollen
  - welche Artefakte nur interne Toolbausteine bleiben

Konsequenz:

- Phase G muss keine Publishing-Mechanik bauen
- sie muss aber die **inhaltliche Publish-Selektion** vorbereiten, damit
  `1.0.0` nicht erst im Release-Milestone unsortiert ueber Modulnamen,
  Koordinaten und Oeffentlichkeitsgrad entscheidet

---

## 3. Scope fuer Phase G

### 3.1 In Scope

- heutige Gradle-Module in spaetere Distributionsgruppen einordnen
- fuer jede Gruppe dokumentieren, ob sie:
  - Kernbestand der ersten `1.0.0`-Publish-Welle ist
  - optionale Zusatzartefakte sein kann
  - bewusst intern bzw. nicht fuer Maven Central gedacht ist
- fuer `1.0.0` eine erste fachliche Artefaktliste definieren
- `docs/releasing.md` so schaerfen, dass der aktuelle Releaseprozess
  und der spaetere Library-Publish klar getrennt bleiben
- `docs/roadmap.md` so schaerfen, dass `0.9.1` nicht als
  "Publish-Milestone light" missverstanden wird
- bei Bedarf Architektur-/Port-Doku auf denselben Schnitt ausrichten

### 3.2 Bewusst nicht Teil von Phase G

- `maven-publish`-Konfiguration im Build
- GPG-/Signing-Setup
- Maven-Central-Portal-Workflow in GitHub Actions
- finale POM-Metadaten, SCM-Block, Lizenz- und Developer-Eintraege
- finaler Nachweis eines echten Portal-Publishs
- verbindliche Langfrist-Garantie fuer **jede** heutige Modulgrenze
- oeffentliche Zusage, dass alle aktuellen Gradle-Module spaeter auch
  als eigene Artefakte erscheinen

Praezisierung:

Phase G loest zuerst "welche Modulgruppen sollen spaeter oeffentlich
werden und wie halten wir das dokumentarisch sauber fest?", nicht
"wie schalten wir das Portal-Publishing technisch frei?".

---

## 4. Leitentscheidungen fuer Phase G

### 4.1 `0.9.1` dokumentiert Publish-Reife, fuehrt aber keinen Publish-Vertrag ein

Verbindliche Entscheidung:

- `0.9.1` endet mit einer dokumentierten Artefaktlandkarte
- `0.9.1` fuehrt **keine** Maven-Central-Automation und keine
  oeffentlichen Publish-Instruktionen fuer Einzelmodule ein
- `1.0.0` bleibt der erste Milestone fuer einen verbindlichen
  Distributionsvertrag

Folge:

- Release Guide und Roadmap duerfen fuer `0.9.1` nur vorbereitende,
  nicht operative Maven-Central-Aussagen treffen

### 4.2 Nicht jedes Gradle-Modul wird automatisch ein oeffentliches Artefakt

Verbindliche Entscheidung:

- die aktuelle Modulstruktur ist zunaechst eine interne
  Bau- und Kopplungsstruktur
- fuer `1.0.0` wird nur der Teil veroefentlicht, der einen klaren
  externen Konsumnutzen und einen stabilen Vertrag hat
- technische Existenz eines Moduls ist **kein** ausreichendes Argument
  fuer Maven Central

Folge:

- Phase G dokumentiert bewusst auch **Nicht-Publish-Gruppen**

### 4.3 Die erste `1.0.0`-Welle bleibt klein und consumer-getrieben

Verbindliche Entscheidung:

- die erste oeffentliche Artefaktwelle konzentriert sich auf den
  Library-Kern fuer externe Consumer
- interne Runner-, CLI- und Testwelten gehoeren nicht in die erste
  oeffentliche Publish-Oberflaeche
- zusaetzliche Hilfs- oder Toolmodule werden nur dann mitpubliziert,
  wenn ihr externer Vertrag bis `1.0.0` wirklich stabil beschrieben ist

### 4.4 Profiling- und Reader-/Writer-Refactors beeinflussen die Publish-Liste direkt

Verbindliche Entscheidung:

- die Ergebnisse aus Phase C und D sind Vorbedingung fuer eine saubere
  `1.0.0`-Artefaktliste
- insbesondere gilt:
  - Read-/Write-Portsplit aus Phase C darf sich in der spaeteren
    Artefaktstruktur widerspiegeln
  - Profiling aus Phase D darf nicht mehr unbemerkt an jedem
    Treiberkern haengen
- die Einordnung von `hexagon:profiling` als optionales Zusatzartefakt
  setzt voraus, dass Phase D die direkte `implementation`-Abhaengigkeit
  der drei Treiberkerne auf `hexagon:profiling` aufgeloest hat;
  **ohne abgeschlossene Phase D** waere `hexagon:profiling` faktisch
  ein transitives Pflichtartefakt jedes Treiberkerns und damit kein
  optionales Zusatzartefakt

Folge:

- Phase G dokumentiert die Artefaktgruppen entlang des **beabsichtigten**
  0.9.1-Endzustands, nicht nur entlang der heute noch gemischten
  Zwischenlage
- die Artefaktklassifikation ist nur dann belastbar, wenn Phase D
  tatsaechlich abgeschlossen ist

### 4.5 `hexagon:application`, CLI und Testmodule bleiben ausserhalb des ersten Publish-Vertrags

Verbindliche Entscheidung:

- folgende Module gehoeren nicht zur ersten `1.0.0`-Maven-Central-Welle:
  - `hexagon:application`
  - `adapters:driving:cli`
  - `test:integration-postgresql`
  - `test:integration-mysql`
- `hexagon:application` bleibt die interne Runner-/Use-Case-Schicht
  zwischen Ports und CLI
- CLI und Testmodule bleiben Distributions- bzw. QA-Werkzeuge, keine
  oeffentlichen Library-Artefakte

### 4.6 Tool-Integrationen bleiben vorerst kein Kernbestand der oeffentlichen Library-Flaeche

Verbindliche Entscheidung:

- `adapters:driven:integrations` wird in Phase G nicht als
  Pflichtartefakt fuer die erste `1.0.0`-Maven-Central-Welle gesetzt
- die Flyway-/Liquibase-/Django-/Knex-Exporter bleiben zunaechst als
  toolnahe Erweiterungen dokumentiert, nicht als Kernvertrag fuer
  externe Read-/Write-Consumer

Folge:

- falls spaeter ein eigenstaendiger externer Konsumbedarf dafuer
  entsteht, kann dies nach `1.0.0` oder als bewusst separate
  Zusatzentscheidung erfolgen

---

## 5. Konkrete Arbeitspakete

### 5.1 Modul-Inventar auf Publish-Sicht abbilden

Zuerst wird das heutige Modul-Inventar in drei saubere Gruppen
einsortiert:

1. **Kernartefakte fuer die erste `1.0.0`-Publish-Welle**
2. **Optionale Zusatzartefakte mit moeglichem `1.0.0`-Fit**
3. **Bewusst interne oder nicht fuer Maven Central gedachte Module**

Der Inventarstand muss sich auf `settings.gradle.kts` und die
Build-Dependencies stuetzen, nicht auf rein abstrakte Architekturworte.

### 5.2 Erste `1.0.0`-Artefaktliste fachlich festziehen

Fuer den dokumentierten `1.0.0`-Zielstand gilt als erste oeffentliche
Artefaktliste:

- **Kernartefakte**
  - `hexagon:core`
  - read-orientierte und ggf. write-orientierte Port-Artefakte aus
    Phase C
    - bis zur tatsaechlichen Aufspaltung dokumentarisch noch unter
      `hexagon:ports` referenziert
  - `adapters:driven:driver-common`
  - `adapters:driven:driver-postgresql`
  - `adapters:driven:driver-mysql`
  - `adapters:driven:driver-sqlite`

- **Optionale Zusatzartefakte, wenn ihr Vertrag bis `1.0.0`
  stabil beschrieben ist**
  - `hexagon:profiling` (Vorbedingung: Phase D muss die harte
    Kopplung der Treiberkerne an `hexagon:profiling` geloest haben;
    solange die Treiberkerne noch direkt an `hexagon:profiling`
    haengen, ist die Einordnung als "optional" technisch nicht
    haltbar)
  - `adapters:driven:driver-postgresql-profiling`,
    `adapters:driven:driver-mysql-profiling`,
    `adapters:driven:driver-sqlite-profiling` (Phase D)
  - `adapters:driven:formats`
  - `adapters:driven:streaming`

- **Nicht Teil der ersten oeffentlichen Welle**
  - `hexagon:application`
  - `adapters:driven:integrations`
  - `adapters:driving:cli`
  - `test:integration-postgresql`, `test:integration-mysql`
  - `test:consumer-read-probe` (Phase F)

Wichtige Praezisierung:

- Phase G zieht damit **keine** endgueltigen Maven-Koordinaten ein
- sie dokumentiert aber bewusst, welche Modulgruppen fuer `1.0.0`
  ueberhaupt als publizierbar gedacht sind

### 5.3 Artefaktgruppen sprachlich sauber benennen

Die Dokumentation soll fuer `1.0.0` mindestens folgende fachliche
Gruppen verwenden:

- **Foundation**
  - neutrales Modell und Kernlogik
- **Ports**
  - read-/write-orientierte Integrationsvertraege
- **Driver Runtime**
  - gemeinsame JDBC-Infrastruktur und Dialektadapter
- **Optional Extensions**
  - Profiling, Formate, Streaming
- **Internal Tooling**
  - Application-Runner, CLI, Tool-Exporter, Tests

Das Ziel ist nicht Marketing-Sprache, sondern ein konsistentes Raster,
das Release Guide, Roadmap und Architektur in derselben Weise benutzen
koennen.

### 5.4 `docs/releasing.md` auf zwei Ebenen schneiden

`docs/releasing.md` soll nach Phase G klar trennen zwischen:

- **aktuellem Releaseprozess**
  - GitHub Releases
  - OCI
  - Homebrew
- **spaeterem `1.0.0`-Library-Publish**
  - noch nicht operativ beschrieben
  - nur als bewusst nachgelagerter Schritt markiert
  - mit Verweis auf die vorbereitende Modul-/Artefaktklassifikation

Wichtig ist:

- das Dokument darf nicht so gelesen werden koennen, als waere Maven
  Central bereits Teil des `0.9.1`-Releaseablaufs

### 5.5 `docs/roadmap.md` entlang der Vertragsgrenze schaerfen

Die Roadmap soll nach Phase G explizit konsistent machen:

- `0.9.1`:
  - Refactor
  - Integrationsschnitt
  - Dokumentationsvorbereitung fuer spaeteren Publish
- `1.0.0`:
  - erster verbindlicher Maven-Central-Publish-Vertrag
  - technische Umsetzung des Portal-Publishs

Wenn noetig, wird in der `0.9.1`-Beschreibung ein kurzer Zusatz
aufgenommen, dass die Publish-Artefaktliste inhaltlich vorbereitet wird,
der operative Publish aber bewusst aussteht.

### 5.6 Architektur- und Port-Doku nur dort nachziehen, wo der Oeffentlichkeitsgrad relevant ist

`docs/architecture.md` und ggf. `docs/hexagonal-port.md` werden nur dort
angepasst, wo sie sonst den Eindruck erwecken koennten:

- jede sichtbare Schicht sei automatisch oeffentliche Library-API
- `DatabaseDriver`-nahe Infrastruktur, CLI-Runtime und Tool-Exporter
  seien gleichrangige Publish-Kandidaten

Die Anpassung soll knapp bleiben und den Unterschied zwischen
interner Modulstruktur und spaeterem oeffentlichem Artefaktvertrag
klarstellen.

---

## 6. Verifikation

Pflichtfaelle fuer Phase G:

- `docs/releasing.md` beschreibt weiterhin nur den aktuellen
  GitHub-Release-/OCI-/Homebrew-Prozess und verweist Maven Central
  sichtbar auf `1.0.0`
- `docs/roadmap.md` trennt `0.9.1` als Refactor-/Vorbereitungsstufe
  und `1.0.0` als Publish-Stufe ohne Widerspruch
- die dokumentierte Artefaktklassifikation deckt alle in
  `settings.gradle.kts` vorhandenen Modulgruppen ab
- kein Buildskript fuehrt in Phase G bereits `maven-publish`,
  Signing oder Portal-CI ein

Zusatznutzen:

- Architektur-/Port-Doku benutzt dieselben Begriffe fuer
  Kernartefakte, optionale Erweiterungen und internes Tooling
- es bleibt nachvollziehbar, warum bestimmte Module trotz technischer
  Existenz nicht Teil der ersten `1.0.0`-Maven-Central-Welle sind

Kein Pflichtziel von Phase G:

- echter Publish auf Maven Central
- CI-validierter Sign-/Portal-Test

---

## 7. Betroffene Codebasis

Sicher betroffen:

- `docs/releasing.md`
- `docs/roadmap.md`
- `docs/implementation-plan-0.9.1.md`
- ggf. `docs/architecture.md`
- ggf. `docs/hexagonal-port.md`
- `settings.gradle.kts`
- `build.gradle.kts`
- Modul-Builds als Referenz fuer die Publish-Klassifikation:
  - `hexagon/core/build.gradle.kts`
  - `hexagon/ports/build.gradle.kts`
  - `hexagon/application/build.gradle.kts`
  - `hexagon/profiling/build.gradle.kts`
  - `adapters/driven/driver-common/build.gradle.kts`
  - `adapters/driven/driver-postgresql/build.gradle.kts`
  - `adapters/driven/driver-mysql/build.gradle.kts`
  - `adapters/driven/driver-sqlite/build.gradle.kts`
  - `adapters/driven/formats/build.gradle.kts`
  - `adapters/driven/integrations/build.gradle.kts`
  - `adapters/driven/streaming/build.gradle.kts`
  - `adapters/driving/cli/build.gradle.kts`

Voraussichtlich **nicht** direkt betroffen:

- produktiver Anwendungscode
- bestehende Release-Workflows fuer GitHub Releases und Homebrew
- Testmodule ausser als bewusst nicht zu publizierende Referenz

---

## 8. Risiken und offene Punkte

### 8.1 Phase-C-/D-Ergebnisse koennen die finale Artefaktmenge noch verschieben

Wenn Read-/Write-Ports oder Profiling-Zuschnitte anders ausfallen als
heute geplant, muss die in Phase G dokumentierte Artefaktliste
nachgezogen werden.

Folge:

- Phase G soll bewusst mit "Kernartefakt" versus
  "optionales Zusatzartefakt" arbeiten, statt zu frueh jede
  spaetere Modul-ID als unverrueckbar darzustellen

### 8.2 `formats` und `streaming` sind sinnvolle, aber noch nicht gleich harte Publish-Kandidaten

Beide Module haben echten Reuse-Wert, tragen aber mehr externe
Bibliotheken und teils toolnahe Semantik als der Foundation-/Port-Kern.

Folge:

- Phase G soll sie als plausible Zusatzartefakte einordnen, nicht als
  zwingenden Bestandteil der minimalen ersten Welle

### 8.3 Tool-Exporter koennen spaeter eine eigene Publish-Entscheidung brauchen

`adapters:driven:integrations` ist nicht wertlos, aber fachlich naeher an
Tooling und CLI-Workflows als an einer schlanken Kern-Library.

Folge:

- Phase G soll dieses Modul bewusst aus dem ersten Standardvertrag
  heraushalten, statt eine halbstabile Oeffentlichkeit zu suggerieren

### 8.4 Dokumentation darf keine Scheinsicherheit ueber finale Koordinaten erzeugen

Der Teilplan identifiziert publizierbare Gruppen, aber keine finalen
Maven-Koordinaten, POM-Metadaten oder Release-Mechanik.

Folge:

- Formulierungen muessen deutlich zwischen:
  - "spaeter publikationsfaehig"
  - "Teil der ersten `1.0.0`-Welle"
  - "bereits technisch fuer Publishing verdrahtet"
  unterscheiden

---

## 9. Entscheidungsempfehlung

Phase G soll als reine Dokumentations- und Klassifikationsphase
umgesetzt werden.

Empfohlener Endzustand:

- `0.9.1` dokumentiert klar, welche Modulgruppen spaeter oeffentlich
  werden koennen
- die minimale erste `1.0.0`-Publish-Welle konzentriert sich auf
  Foundation, Ports und Dialekt-Runtime
- Profiling, Formate und Streaming bleiben als optionale Zusatzartefakte
  dokumentiert
- Application-, CLI-, Integrations- und Testmodule bleiben ausserhalb
  des ersten Maven-Central-Vertrags
- Release Guide und Roadmap halten die Grenze zwischen
  Refactor-Vorbereitung und echtem Publish-Vertrag sichtbar ein
