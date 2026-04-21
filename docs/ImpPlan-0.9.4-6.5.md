# Implementierungsplan: 0.9.4 - Arbeitspaket 6.5 `Doku- und Vertragsnachzug`

> **Milestone**: 0.9.4 - Beta: MySQL-Sequence Reverse-Engineering und Compare
> **Arbeitspaket**: 6.5 (`Phase E2: Doku- und Vertragsnachzug`)
> **Status**: Draft (2026-04-21)
> **Referenz**: `docs/implementation-plan-0.9.4.md` Abschnitt 6.5,
> Abschnitt 7 und Abschnitt 9;
> `docs/ImpPlan-0.9.4-6.1.md`;
> `docs/ImpPlan-0.9.4-6.2.md`;
> `docs/ImpPlan-0.9.4-6.3.md`;
> `docs/ImpPlan-0.9.4-6.4.md`;
> `docs/roadmap.md`;
> `docs/mysql-sequence-emulation-plan.md`;
> `docs/cli-spec.md`;
> `docs/guide.md`;
> `ledger/warn-code-ledger-0.9.4.yaml`.

---

## 1. Ziel

Arbeitspaket 6.5 zieht die in D1 bis D3 und E1 festgezogenen
Produkt-, CLI- und Warnvertraege in die dauerhafte Projektdokumentation
nach. E2 ist kein weiterer Fach- oder Codepfad, sondern der
Vertragsnachzug, der sicherstellt, dass Roadmap, Spezialplan,
CLI-Spezifikation, Guide und Warncode-Ledger denselben 0.9.4-Stand
beschreiben.

6.5 liefert vier konkrete Ergebnisse:

- `docs/roadmap.md` beschreibt 0.9.4 mit dem tatsaechlichen Scope fuer
  Reverse, Compare und `W116`
- `docs/mysql-sequence-emulation-plan.md` zieht Phase D/E vom offenen
  Plan in einen umgesetzungsnahen Vertragsstand
- `docs/cli-spec.md` dokumentiert `schema reverse`, `schema compare`
  und `W116` im 0.9.4-Vertrag korrekt
- `docs/guide.md` und `ledger/warn-code-ledger-0.9.4.yaml` stehen
  sichtbar und widerspruchsfrei zum Implementierungsstand

Nach 6.5 soll klar gelten:

- Nutzerdokumentation und interne Planungsdokumente widersprechen sich
  nicht mehr bei Scope, Sichtbarkeit und Status von `W116`
- `schema reverse` und `schema compare` sind fuer MySQL-Sequence-
  Emulation aus Nutzer- und CI-Sicht nachvollziehbar dokumentiert
- das Ledger ist die kanonische Aktivierungsliste, und die restliche
  Doku verweist konsistent darauf statt einen aelteren Reserve-Stand
  fortzuschreiben

---

## 2. Ausgangslage

Der 0.9.4-Doku-Stand ist aktuell ueber mehrere Dateien verteilt:

- `docs/roadmap.md` enthaelt den groben Milestone-Scope und verweist auf
  den Sequence-Plan
- `docs/mysql-sequence-emulation-plan.md` fuehrt Phase D/E noch als
  offen bzw. fuer 0.9.4 geplant
- `docs/cli-spec.md` dokumentiert CLI-Vertraege, fuehrt `W116` aber
  noch als reserviert
- `docs/guide.md` enthaelt generische Hinweise zu `schema reverse` und
  `schema compare`, aber noch keine 0.9.4-spezifischen MySQL-Sequence-
  Hinweise
- `ledger/warn-code-ledger-0.9.4.yaml` fuehrt `W116` bereits als
  `active`

Was nach D1 bis D3 und E1 fuer E2 noch fehlt:

- Status- und Vertragsabgleich zwischen Plan, Guide, CLI-Spezifikation
  und Ledger
- explizite Nutzererklaerung, wann `W116` erscheint und wann Compare
  trotz `W116` diff- bzw. exit-stabil bleibt
- konsistente Beschreibung des 0.9.4-Stands in Roadmap und
  Spezialdokument
- Absicherung, dass additive JSON/YAML-Outputfelder aus E1 nicht in
  der Doku fehlen

Aktueller Scaffold-Gap vor E2:

- `docs/cli-spec.md` spricht bei `W116` noch von "reserviert", obwohl
  das 0.9.4-Ledger bereits `active` fuehrt
- `docs/mysql-sequence-emulation-plan.md` markiert Phase D/E noch als
  offen, statt den konkreten 0.9.4-Vertrag aus D1 bis E1 abzubilden
- `docs/guide.md` erklaert weder Sequence-Reverse noch Compare-
  Sichtbarkeit fuer degradierte Operanden konkret
- `docs/roadmap.md` beschreibt 0.9.4 auf Milestone-Ebene, aber noch
  nicht den nachgezogenen Vertragsstand aus den Teilplaenen

Konsequenz ohne 6.5:

- Nutzer lesen je nach Dokument unterschiedliche Aussagen zu `W116`,
  Reverse und Compare
- das Ledger behauptet einen aktiven Warncode, waehrend `cli-spec.md`
  noch den Reserve-Zustand transportiert
- die additive Compare-Ausgabe aus E1 bleibt fuer Nutzer und
  Integratoren unterdokumentiert

---

## 3. Scope fuer 6.5

### 3.1 In Scope

- E2 setzt D1 bis D3 und E1 als fachliche Quelle voraus
- Nachzug in:
  - `docs/roadmap.md`
  - `docs/mysql-sequence-emulation-plan.md`
  - `docs/cli-spec.md`
  - `docs/guide.md`
  - `ledger/warn-code-ledger-0.9.4.yaml`
- Status- und Vertragsabgleich fuer:
  - `W116`
  - `schema reverse`
  - `schema compare`
  - operandseitige Diagnose in Plain/JSON/YAML
- explizite Beschreibung, welche 0.9.4-Aenderungen nutzerrelevant sind
- Absicherung, dass Ledger und Doku denselben Aktivierungsstand und
  dieselben Codebeschreibungen tragen

### 3.2 Bewusst nicht Teil von 6.5

- neue Produktentscheidungen jenseits des bereits festgezogenen
  0.9.4-Vertrags
- fachliche Aenderungen an Reverse oder Compare
- neue Warncodes oder Ledger-Struktur jenseits des 0.9.4-Abgleichs
- Marketing-/Release-Notes ausserhalb der technischen Doku

Praezisierung:

6.5 loest "welche dauerhaft lesbaren Projektdokumente muessen nach dem
0.9.4-Vertrag aktualisiert werden?", nicht "welche neue Fachlogik wird
noch entwickelt?".

---

## 4. Leitentscheidungen

### 4.1 Ledger ist Statusquelle fuer Warncode-Aktivierung

Verbindliche Folge:

- wenn Ledger und Fliesstext widersprechen, muss E2 den Fliesstext an
  das Ledger angleichen
- `W116` wird in E2 nicht erneut diskutiert, sondern als 0.9.4-aktiver
  Warncode dokumentiert

### 4.2 CLI-Spezifikation beschreibt Nutzervertrag, nicht Planungsrest

Verbindliche Folge:

- `docs/cli-spec.md` darf fuer `schema reverse` und `schema compare`
  keine veralteten Reserve- oder "spaeter geplant"-Aussagen behalten,
  sobald D1 bis E1 den Vertrag festgezogen haben
- Reverse-/Compare-Verhalten wird dort aus Nutzersicht beschrieben:
  Sichtbarkeit, Exit-Codes, Outputformate, `W116`

### 4.3 Guide bleibt praxisnah, nicht vollstaendige Spezialplanung

Verbindliche Folge:

- `docs/guide.md` bekommt nur die nutzerrelevanten Hinweise:
  - wann MySQL-Sequences sauber reverse-bar sind
  - wann `W116` erscheint
  - was `schema compare` mit operandseitigem `W116` macht
- Detailvertraege bleiben im Spezialplan und in den Teilplaenen

### 4.4 Spezialplan und Masterplan muessen denselben Stand sprechen

Verbindliche Folge:

- `docs/mysql-sequence-emulation-plan.md` und
  `docs/implementation-plan-0.9.4.md` duerfen nach E2 nicht mehr
  auseinanderlaufen bei:
  - Phase-D/E-Status
  - `W116`-Bedeutung
  - Compare-Rolle

### 4.5 Additive Output-Felder bleiben additiv dokumentiert

Verbindliche Folge:

- wenn E1 `sourceOperand`/`targetOperand` in JSON/YAML sichtbar macht,
  muss E2 diese Felder als additive Erweiterung dokumentieren
- bestehende Felder und ihre Semantik bleiben in der Doku unveraendert
  beschrieben

---

## 5. Zielarchitektur fuer 6.5

### 5.1 Dokumenttypen und Rollen

E2 behandelt die 0.9.4-Dokumente in klaren Rollen:

1. `roadmap.md`
   - beantwortet: Was gehoert zu 0.9.4 und wie ist der Status?
2. `mysql-sequence-emulation-plan.md`
   - beantwortet: Wie sieht der fachliche Gesamtvertrag fuer
     MySQL-Sequence-Emulation nach D/E aus?
3. `cli-spec.md`
   - beantwortet: Wie verhalten sich `schema reverse` und
     `schema compare` fuer Nutzer, Automationspfade und Outputformate?
4. `guide.md`
   - beantwortet: Welche praktische Erwartung hat ein Nutzer bei
     Reverse/Compare gegen MySQL?
5. `warn-code-ledger-0.9.4.yaml`
   - beantwortet: Ist `W116` aktiv, wie ist er klassifiziert und wo ist
     seine Evidenz?

### 5.2 Nachzuziehende Vertragskerne

E2 zieht mindestens diese 0.9.4-Vertraege nach:

- Reverse:
  - kanonische MySQL-Sequence-Emulation wird rekonstruiert
  - degradierte Faelle erzeugen `W116`
- Compare:
  - operandseitiges `W116` bleibt Diagnose, kein Diff
  - Exit-Codes folgen weiter nur Validation oder echtem Diff
  - Plain/JSON/YAML machen operandseitige Diagnose sichtbar
- Warncode:
  - `W116` ist aktiv
  - Bedeutung und Grenzen sind konsistent beschrieben

### 5.3 E2-Artefaktstrategie

Jeder E2-Schritt muss ein sichtbares, reviewbares Artefakt erzeugen:

- Roadmap-Nachzug als geaenderter 0.9.4-Statusblock
- Spezialplan-Nachzug als aktualisierte Phase-D/E-Status- und
  Vertragsabschnitte
- CLI-Spec-Nachzug als angepasste Warncode-, Reverse- und
  Compare-Abschnitte
- Guide-Nachzug als konkrete Nutzerhinweise
- Ledger-Nachzug als bestaetigter `W116`-Eintrag mit passender Evidenz

Empfohlene Review- und Commit-Strategie fuer E2:

- `E2a` und `E2b` bevorzugt zuerst, damit Status- und Fachvertrag
  stabilisiert sind
- `E2c` danach auf Basis des konsolidierten Vertragsstandes
- `E2d` zuletzt als nutzerorientierte Verdichtung des finalen
  CLI-/Vertragsstandes
- Commits duerfen pro Dokument oder als kleiner logisch
  zusammenhaengender Doku-Schnitt erfolgen; entscheidend ist, dass
  jeder Schritt als reviewbares Artefakt separat nachvollziehbar bleibt

### 5.4 Konsistenzregeln zwischen den Dokumenten

Fuer E2 gilt:

- Roadmap und Spezialplan duerfen sich beim 0.9.4-Status nicht
  widersprechen
- CLI-Spec und Guide duerfen sich beim Nutzervertrag nicht
  widersprechen
- CLI-Spec und Ledger duerfen sich beim Aktivierungsstatus von `W116`
  nicht widersprechen
- additive Compare-Ausgabe aus E1 muss in CLI-Spec und Guide gleich
  verstanden werden

---

## 6. Konkrete Arbeitsschritte

### E2-0 Doku-Ist-Stand und Widersprueche verifizieren

- aktuelle Aussagen in Roadmap, Spezialplan, CLI-Spec, Guide und Ledger
  gegeneinander halten
- offene Widersprueche fuer:
  - `W116`
  - Reverse-/Compare-Vertrag
  - 0.9.4-Status
  explizit benennen

Done-Kriterien fuer E2-0:

- alle relevanten 0.9.4-Dokumentpfade sind erfasst
- Widersprueche und veraltete Aussagen sind als konkreter E2-
  Aenderungsschnitt festgehalten

### E2a Roadmap nachziehen

- `docs/roadmap.md` fuer den 0.9.4-Status aktualisieren
- Scope, Status und Verweisstruktur auf D/E mit dem aktuellen
  Umsetzungsstand abgleichen
- den sichtbaren Status mindestens auf `AP 6.1-6.4 Done` anheben,
  damit `roadmap.md` nicht auf einem aelteren Zwischenstand stehen
  bleibt
- dabei explizit entscheiden, wie die 0.9.4-AP-Nummerierung in der
  Roadmap sichtbar gemacht wird:
  - entweder durch eine eigene Statusspalte fuer die 0.9.4-Tabelle
  - oder durch einen klaren Statustext, der `AP 6.1-6.4` eindeutig auf
    0.9.4 bezieht

Done-Kriterien fuer E2a:

- `roadmap.md` beschreibt 0.9.4 nicht mehr nur grob, sondern konsistent
  zum Masterplan
- Status- und Verweisstand auf `mysql-sequence-emulation-plan.md`
  stimmt
- der Roadmap-Status steht nicht mehr bei nur `AP 6.1 Done`, wenn die
  Teilplaene fuer `6.2`, `6.3` und `6.4` bereits vorliegen

### E2b Spezialplan aktualisieren

- `docs/mysql-sequence-emulation-plan.md` Phase D/E auf den 0.9.4-
  Vertragsstand ziehen
- offene/erledigte Statusmarker bereinigen
- `W116` und Compare-Rolle mit D1 bis E1 synchronisieren

Done-Kriterien fuer E2b:

- Phase D/E ist nicht mehr als unbestimmter Rest offen beschrieben
- Spezialplan, Masterplan und Teilplaene sprechen denselben
  Fachvertrag

### E2c CLI-Spezifikation konkretisieren

- `docs/cli-spec.md` fuer `schema reverse`, `schema compare` und
  `W116` aktualisieren
- Warncode-Status von `W116` auf den aktiven 0.9.4-Stand bringen
- Reverse-/Compare-Vertrag fuer operandseitige Diagnose und Outputformate
  ergaenzen

Done-Kriterien fuer E2c:

- `W116` ist in `cli-spec.md` nicht mehr als reserviert beschrieben
- `schema reverse` und `schema compare` tragen den 0.9.4-Vertrag fuer
  MySQL-Sequences und operandseitige Diagnose sichtbar
- additive JSON/YAML-Operandfelder sind dokumentiert, ohne bestehende
  Feldsemantik umzubrechen

### E2d Guide erweitern

- `docs/guide.md` um praxisnahe Hinweise zu MySQL-Sequence-Reverse und
  Compare-Verhalten erweitern
- Nutzerwarnung und Erwartungsmanagement fuer `W116` einziehen
- mindestens ein konkreter Nutzerhinweis aufnehmen, z. B.:
  - bei intakter kanonischer MySQL-Sequence-Emulation faltet
    `schema reverse` die Supportobjekte wieder zu Sequences und
    `SequenceNextVal` zurueck
  - bei fehlenden oder nicht kanonischen Supportobjekten erscheint
    `W116`, waehrend `schema compare` diese Diagnose sichtbar macht,
    aber nicht allein zum Diff macht

Done-Kriterien fuer E2d:

- Guide erklaert fuer Nutzer knapp, wann Sequence-Reverse funktioniert
- Guide erklaert knapp, was `W116` fuer Reverse und Compare bedeutet

### E2e Ledger und Evidenzpfade absichern

- `ledger/warn-code-ledger-0.9.4.yaml` gegen den finalen 0.9.4-Stand
  verifizieren
- bei Bedarf Evidenzpfade, Testpfade und Status mit D1 bis E1
  abgleichen

Done-Kriterien fuer E2e:

- Ledger-Status und Doku widersprechen sich bei `W116` nicht
- Evidenzpfade zeigen auf die tatsaechlich relevanten Produktions- und
  Testpfade

### E2f Doku-Verifikation abschliessen

- E2-Aenderungen als zusammenhaengenden 0.9.4-Dokustand gegenlesen
- pruefen, ob Nutzervertrag, Spezialplan und Ledger denselben
  Aktivierungs- und Sichtbarkeitsstand transportieren

Done-Kriterien fuer E2f:

- keine Restwidersprueche zwischen Roadmap, Spezialplan, CLI-Spec,
  Guide und Ledger
- alle 0.9.4-nutzerrelevanten Aussagen zu `W116`, Reverse und Compare
  sind konsistent

Abhaengigkeiten:

- `E2-0` vor `E2a` bis `E2e`
- `E2c` haengt fachlich von D1 bis D3 und E1 ab
- `E2e` vor `E2f`
- `E2a` bis `E2d` sind nach `E2-0` weitgehend parallelisierbar
- empfohlene Reihenfolge trotz Parallelisierbarkeit:
  - `E2b` vor `E2c`, damit die CLI-Spec den bereits konsolidierten
    Sequence-Vertrag referenziert
  - `E2c` vor `E2d`, damit der Guide nur den finalen Nutzervertrag
    zusammenfasst statt Zwischenstaende zu erklaeren
- `E2f` ist Abschluss-Gate fuer 6.5

---

## 7. Verifikation

Pflichtfaelle fuer 6.5:

1. `roadmap.md` beschreibt 0.9.4 konsistent zum Masterplan und verweist
   korrekt auf den Sequence-Plan.
2. `mysql-sequence-emulation-plan.md` fuehrt Phase D/E nicht mehr nur
   als offen, sondern als 0.9.4-Vertrag mit aktuellem Status.
3. `cli-spec.md` beschreibt `W116` als aktiven 0.9.4-Warncode, nicht
   mehr als reserviert.
4. `cli-spec.md` beschreibt `schema reverse` fuer MySQL-Sequences und
   `W116` konsistent zum Reverse-Vertrag aus D1 bis D3.
5. `cli-spec.md` beschreibt `schema compare` so, dass operandseitiges
   `W116` sichtbar bleibt, aber keinen eigenen Diff-/Exit-Typ bildet.
6. `cli-spec.md` dokumentiert additive `sourceOperand`/`targetOperand`-
   Felder fuer JSON/YAML ohne Bruch bestehender Feldsemantik.
7. `guide.md` erklaert nutzerverstaendlich, wann MySQL-Sequences sauber
   reverse-bar sind.
8. `guide.md` erklaert nutzerverstaendlich, wann `W116` erscheint und
   wie Compare damit umgeht.
9. `ledger/warn-code-ledger-0.9.4.yaml` und `cli-spec.md` stimmen bei
   Aktivierung und Bedeutung von `W116` ueberein.
10. Roadmap, Spezialplan, CLI-Spec und Guide widersprechen sich nicht
    bei Scope oder Sichtbarkeit von `W116`.

Maschinell gestuetzte Mindestchecks sind fuer E2 sinnvoll, auch wenn
die Hauptverifikation redaktionell bleibt:

- `rg -n "W116.*[Rr]eserviert|[Rr]eserviert.*W116" docs/cli-spec.md`
  liefert nach E2 keinen Treffer mehr
- `rg -n "W116" docs/guide.md` liefert nach E2 mindestens einen
  nutzerrelevanten Treffer
- ein einfacher Check darf absichern, dass
  `ledger/warn-code-ledger-0.9.4.yaml` und `docs/cli-spec.md` beide
  `W116` auf aktivem 0.9.4-Stand fuehren

Akzeptanzkriterium fuer 6.5:

- der 0.9.4-Nutzer- und Vertragsstand zu MySQL-Sequence-Reverse,
  Compare und `W116` ist projektweit konsistent dokumentiert.

Gesamt-Gate fuer 6.5:

- 6.5 gilt erst dann als abgeschlossen, wenn `E2f` erledigt ist und
  alle Pflichtfaelle aus Abschnitt 7 bestanden haben

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `docs/roadmap.md`
  - Milestone-0.9.4-Status und sichtbaren AP-Fortschritt nachziehen
- `docs/mysql-sequence-emulation-plan.md`
  - Phase-D/E-Status und Gesamtvertrag auf den 0.9.4-Stand ziehen
- `docs/cli-spec.md`
  - `W116` von reserviert auf aktiv heben und Reverse-/Compare-Vertrag
    konkretisieren
- `docs/guide.md`
  - praxisnahe Hinweise zu MySQL-Sequence-Reverse, `W116` und Compare
    ergaenzen
- `ledger/warn-code-ledger-0.9.4.yaml`
  - Evidenz- und Statusabgleich gegen den finalen 0.9.4-Vertrag

Als fachliche Quellen relevant, aber voraussichtlich nicht direkt zu
aendern:

- `docs/implementation-plan-0.9.4.md`
- `docs/ImpPlan-0.9.4-6.1.md`
- `docs/ImpPlan-0.9.4-6.2.md`
- `docs/ImpPlan-0.9.4-6.3.md`
- `docs/ImpPlan-0.9.4-6.4.md`

Bewusst noch nicht direkt betroffen:

- Produktionscode fuer Reverse und Compare
- Testcode ausserhalb von Doku-/Ledger-Evidenzverweisen

---

## 9. Risiken und Abgrenzung

### 9.1 CLI-Spec und Ledger laufen auseinander

Risiko:

- `cli-spec.md` bleibt bei "W116 reserviert", waehrend das Ledger
  bereits `active` fuehrt

Gegenmassnahme:

- Ledger als Statusquelle behandeln
- `cli-spec.md` explizit auf aktiven 0.9.4-Stand ziehen

### 9.2 Spezialplan und Roadmap sprechen unterschiedlichen 0.9.4-Status

Risiko:

- Roadmap beschreibt 0.9.4 grob oder als in Arbeit, waehrend der
  Spezialplan D/E noch als offen stehenlaesst

Gegenmassnahme:

- beide Dokumente im selben E2-Schnitt aktualisieren
- Statusmarker explizit gegeneinander pruefen

### 9.3 Guide bleibt zu technisch oder zu leer

Risiko:

- der Guide uebernimmt entweder zu viele Spezialdetails oder erklaert
  `W116` und Compare gar nicht

Gegenmassnahme:

- Guide auf nutzerrelevante Hinweise begrenzen
- Detailvertraege in CLI-Spec und Spezialplan belassen

### 9.4 Additive Compare-Ausgabe bleibt unterdokumentiert

Risiko:

- E1 fuehrt additive `sourceOperand`-/`targetOperand`-Felder ein,
  aber die Doku erwaehnt sie nicht oder beschreibt sie als Bruch

Gegenmassnahme:

- additive Natur in `cli-spec.md` explizit nennen
- Guide nur auf Sichtbarkeit hinweisen, nicht die komplette
  Feldstruktur wiederholen

### 9.5 Doku-Nachzug bleibt hinter dem echten 0.9.4-Codepfad zurueck

Risiko:

- E2 dokumentiert einen Zwischenstand statt den finalen Vertrag aus D1
  bis E1

Gegenmassnahme:

- vor dem finalen E2-Schnitt gezielt pruefen, ob nach dem letzten
  relevanten Teilplan-Commit noch fachliche Aenderungen an
  Reverse/Compare eingeflossen sind, die den Vertragsstand verschieben
- E2 erst gegen diesen tatsaechlich letzten Fachstand abgleichen
- `E2f` als bewussten Konsistenz- und Gegenleseschritt behandeln
