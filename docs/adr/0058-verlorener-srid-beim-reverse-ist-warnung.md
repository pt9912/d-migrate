---
status: accepted
date: 2026-09-17
decision-makers: pt9912
consulted: docs/planning/next/reader-treue-spatial-array-json.md, docs/adr/0001-mysql-routine-drop-create-non-atomic-warning.md, docs/adr/0002-unsafe-dependency-pair-warning-not-blocker.md, docs/adr/0016-spatialite-metadata-bootstrap.md, spec/type-mapping.md, spec/ddl-generation-rules.md, spec/cli-spec.md, spec/neutral-model-spec.md
informed: adapters/driven/driver-oracle (OracleSchemaReader), spec/type-mapping.md, spec/ddl-generation-rules.md, docs/user/anwenderhandbuch.md
---

# Ein beim Reverse verlorener SRID ist eine Warnung, kein Blocker

> **Status: accepted (2026-09-17).** Verliert `schema reverse` den SRID einer
> Geometriespalte, meldet es das als `WARNING`. Das gilt einheitlich für die
> neue Note `R370` (Oracle, quotiert kleingeschriebene Tabelle) und für ihre
> Schwester `R365`, die bisher `INFO` war. Weder der Reverse noch
> `schema generate`, `schema migrate` oder `data transfer` blocken deswegen.
> Ein späterer Block bräuchte eine Markierung im neutralen Modell und damit
> eine eigene Entscheidung.

## Kontext und Problemstellung

Oracle führt den SRID einer `SDO_GEOMETRY`-Spalte nicht an der Spalte, sondern
in einer Zeile von `USER_SDO_GEOM_METADATA`. Der Reverse liest ihn aus
`ALL_SDO_GEOM_METADATA` und verlangt dabei einen exakt passenden Tabellen- und
Spaltennamen ([`spec/type-mapping.md`](../../spec/type-mapping.md), Oracle
„Reverse-Entscheidungen").

Oracle schreibt Tabellen- und Spaltennamen in dieser Zeile bedingungslos groß.
d-migrate quotiert Bezeichner wortgetreu und legt deshalb kleingeschriebene
Tabellen an. Zu einer solchen Tabelle kann es keine Zeile geben, die sie
beschreibt. Eine Zeile mit dem großgeschriebenen Namen benennt eine andere
Tabelle ([`spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md),
Abschnitt 16.10). Dasselbe gilt für einen quotiert kleingeschriebenen
Spaltennamen. Ein toleranter Abgleich ohne Rücksicht auf die Schreibweise ist
geprüft und verworfen: Er würde die Zeile einer fremden Tabelle auswerten, und
dieselbe Abfrage speist auch den Datenpfad.

Der Reverse liest eine solche Spalte deshalb als `geometry` ohne SRID. Heute
geschieht das **ohne jede Meldung**. Gemeldet wird nur der andere Fall: Ist
die Metadatensicht gar nicht lesbar, entsteht `R365`, und zwar als `INFO`. Die
Klartext-Ausgabe von `schema reverse` zeigt `INFO` nur mit `--verbose`.

Die Folge des Verlusts zeigt sich erst auf dem Ziel. SQL Server wählt ohne
geodätischen SRID das planare `geometry` statt `geography`. Einen räumlichen
Index darauf rendert d-migrate nicht, weil dafür
`BOUNDING_BOX`-Parameter nötig sind, die das Modell nicht trägt. Der Index
entfällt mit `E057`.

Für die neue Meldung ist `R370` reserviert. Offen war, welches Gewicht sie hat.
Das kostet mehr als ein Fehlalarm und weniger als ein Datenverlust.

Die Frage dieses ADR lautet: **Ist ein beim Reverse verlorener SRID ein Fund
oder ein Block — und wenn ein Fund, mit welcher Severity?**

## Entscheidungstreiber

- Kein Verlust bleibt still. Der Anwender muss ihn sehen, bevor er aus dem
  Artefakt ein Ziel baut, und zwar ohne `--verbose`.
- d-migrate legt genau die Tabellen an, die den Verlust auslösen. Jeder Reverse
  eines von d-migrate angelegten Oracle-Schemas mit Geometriespalte trifft
  diesen Fall.
- Die Spalte selbst ist vollständig lesbar, ebenso ihre Werte. Es fehlt nur die
  Angabe des Bezugssystems.
- Eine Geometrie ohne SRID ist im neutralen Modell zulässig. `srid` ist
  optional ([`spec/neutral-model-spec.md`](../../spec/neutral-model-spec.md)).
- Der Ausgang eines Laufs hängt nach
  [`spec/cli-spec.md`](../../spec/cli-spec.md) nicht an der Stufe einer Notiz,
  sondern daran, ob ein Objekt fehlt. `warning` bedeutet dort: Das Ergebnis
  trägt, was die Eingabe verlangt, aber etwas daran verdient einen Blick.
- Zwei Notizen, die denselben Verlust aus verschiedenen Gründen melden, sollen
  dasselbe Gewicht haben.
- Präzedenz: [ADR 0001](0001-mysql-routine-drop-create-non-atomic-warning.md)
  und [ADR 0002](0002-unsafe-dependency-pair-warning-not-blocker.md) lassen
  operativ relevante Befunde auf `WARNING`, wenn ein Block einen gewollten Pfad
  unerreichbar machte. ADR 0001 nennt `INFO` für ein solches Risiko zu leise.

## Betrachtete Optionen

### A — Fund (gewählt)

Der Reverse meldet den Verlust als Note und liest die Spalte ohne SRID weiter.
Die Severity ist `WARNING`.

`INFO` wäre ebenfalls ein Fund, aber zu leise: Die Klartext-Ausgabe blendet
`INFO` ohne `--verbose` aus, und der Verlust kostet auf SQL Server einen Index.
`R365` hatte bisher genau dieses Gewicht. Er wird deshalb angehoben, statt
`R370` an ihm auszurichten.

### B — Block beim Reverse

`R370` und `R365` würden `action_required`. Nach der Regel in
`spec/cli-spec.md` ändert eine Notiz allein den Ausgang nicht. Ein Block hieße
also, die Spalte oder die Tabelle als übersprungenes Objekt auszulassen, oder
den Lauf abzubrechen. Verworfen:

- Der Reverse ließe eine lesbare Spalte samt Werten weg, um das Fehlen einer
  einzigen Angabe zu melden. Das ist der größere Verlust.
- Jeder Reverse eines von d-migrate angelegten Oracle-Schemas mit
  Geometriespalte endete mit Exit `8`.
- An der Quelle lässt sich der Zustand für eine quotiert kleingeschriebene
  Tabelle nicht beheben. Die einzige Abhilfe wäre, die Tabelle neu anzulegen.
- Der Block erreicht die nachgelagerten Kommandos nicht. Sie lesen die
  Schemadatei, und die trägt die Note nicht (siehe C).

### C — Block mit Markierung im Modell (später möglich)

Das neutrale Modell bekäme eine Markierung, die „SRID beim Reverse verloren"
von „kein SRID deklariert" unterscheidet. `schema generate`, `schema migrate`
und `data transfer` könnten daran blocken. Heute nicht gewählt:

- Es wäre eine Modellerweiterung mit Folgen für
  `spec/neutral-model-spec.md` und das JSON-Schema. Möglicherweise wäre auch
  der Fingerabdruck betroffen.
- Der Anwender müsste die Markierung von Hand entfernen oder den SRID
  deklarieren, bevor ein Ziel entsteht. Das verlangt mehr, als der Schaden
  heute rechtfertigt.

Die Option bleibt offen. Ohne Markierung ist ein Block in den nachgelagerten
Kommandos nicht zielgenau möglich (siehe Entscheidung, Punkt 4).

## Entscheidung

Gewählt ist **Option A**.

### 1. Die Severity ist `WARNING`, einheitlich

Eine Reverse-Note, die den verlorenen SRID einer Geometriespalte meldet, hat
die Severity `WARNING`. Heute betrifft das zwei Kennungen:

| Kennung | Fall | Severity |
| --- | --- | --- |
| `R370` (neu) | Die Metadatensicht ist lesbar, aber die Tabelle ist quotiert kleingeschrieben angelegt. Eine Zeile, die sie beschreibt, kann es nicht geben. | `WARNING` |
| `R365` | Die Metadatensicht ist nicht lesbar, etwa weil Oracle Spatial oder das Leserecht fehlt. | `WARNING` (bisher `INFO`) |

Die beiden Fälle schließen sich aus. Ist die Sicht nicht lesbar, lässt sich
über ihre Zeilen nichts sagen, und zur selben Spalte entsteht kein
zusätzliches `R370`.

`R365` entsteht heute je Tabelle, deren Metadaten nicht lesbar sind, auch an
einer Tabelle ohne Geometriespalte. Dort geht kein SRID verloren, und eine
solche Meldung ist **keine** `WARNING` im Sinn dieser Entscheidung. Ob sie an
solchen Tabellen entfällt oder als `INFO` stehen bleibt, lässt dieser ADR
offen.

Ob der Reader auch eine Spalte meldet, deren Tabelle eine Zeile tragen könnte,
aber keine trägt (unquotiert angelegt, Zeile nie registriert), entscheidet
dieser ADR nicht. Meldet er sie, gilt dieselbe Severity.

### 2. Kein Block

- `schema reverse` liest die Spalte als `geometry` ohne SRID und nimmt sie ins
  Schema auf. Die Note ändert den Ausgang nicht.
- `schema generate`, `schema migrate` und `data transfer` blocken nicht wegen
  eines fehlenden SRID. Sie werten `R370` und `R365` nicht aus und führen dafür
  keinen eigenen Code.

### 3. Die benannte Folge

Die Folge, die die Note benennt, ist der fehlende räumliche Index. Auf SQL
Server wird eine Spalte ohne geodätischen SRID zu planarem `geometry`, und ein
räumlicher Index darauf entfällt mit `E057`. Diese Regel besteht unabhängig
von dieser Entscheidung und bleibt unverändert, einschließlich ihrer Wirkung
auf den Ausgang von `schema generate`.

### 4. Ein späterer Block braucht eine Markierung im Modell

`R370` steht nur im Reverse-Report. Die Schemadatei trägt eine Geometrie ohne
SRID, und die ist nicht von einer handgeschriebenen Geometrie ohne SRID zu
unterscheiden. Ein Block in `schema generate`, `schema migrate` oder
`data transfer` träfe ohne Markierung jede handgeschriebene Geometrie ohne
SRID. Ein solcher Block setzt deshalb Option C voraus und braucht einen
eigenen ADR, der diesen Punkt übersteuert.

### 5. Der Ausweg für den Anwender

Die Note nennt Grund und Ausweg. Der Grund: Oracle kann zu einer quotiert
kleingeschriebenen Tabelle keine Metadatenzeile führen. Wird der SRID
gebraucht, gibt es zwei Wege:

- Der Anwender **deklariert den SRID in der Schemadatei**. Das neutrale Modell
  trägt ihn dann, und jedes Ziel, das ihn an der Spalte führt, bekommt ihn.
- Oder er **legt die Tabelle unquotiert an** (großgeschrieben) und registriert
  ihre Metadatenzeile. Dann liest der Reverse den SRID.

Der Ausweg ist **nicht**, die Metadatenzeile für die quotiert kleingeschriebene
Tabelle von Hand einzufügen. Oracle schreibt ihren Namen groß, und die Zeile
beschreibt dann eine andere Tabelle.

### 6. Wo die Regel steht

Die Regel ist Teil des Spatial-Vertrags und gehört in die Spezifikation, an
zwei Stellen:

- [`spec/type-mapping.md`](../../spec/type-mapping.md), Oracle
  „Reverse-Entscheidungen", Zeile `SDO_GEOMETRY`;
- [`spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md),
  Abschnitt 16.10, Reverse-Regel.

Beide Stellen bekommen die Begründung aus dem Kontext, damit die Grenze nicht
als Fehler gelesen wird. Die Spezifikation zieht mit dem Paket nach, das
`R370` baut, nicht vorher. Sie verweist dabei nicht auf diesen ADR.

## Bestätigung

Das Paket, das `R370` baut, erfüllt diese Entscheidung, wenn Folgendes gilt:

- **Die Severity ist gepinnt.** Ein Test des Oracle-Readers liest eine quotiert
  kleingeschriebene Tabelle mit Geometriespalte. Die Metadatensicht ist lesbar,
  eine passende Zeile fehlt. Er prüft:
  - Es entsteht genau ein `R370` mit Severity `WARNING`.
  - Die Spalte ist `geometry` ohne SRID und bleibt im Schema.
  - Die Note nennt Grund und Ausweg und empfiehlt nicht, die Metadatenzeile
    von Hand einzufügen.
- **`R365` ist `WARNING`.** Die bestehende Zusicherung auf `R365` pinnt
  `WARNING` statt `INFO`, und zwar an einer Tabelle mit Geometriespalte.
- **Gegenproben:**
  - Eine unquotiert angelegte Tabelle mit registrierter Zeile liest den SRID
    und erzeugt weder `R370` noch `R365`.
  - Der `R365`-Fall (Sicht nicht lesbar) erzeugt kein zusätzliches `R370`.
  - Eine Tabelle ohne Geometriespalte erzeugt keine `WARNING` zum SRID.
- **Live-Abnahme.** Gegen eine echte Oracle-Instanz entsteht für eine
  quotiert kleingeschriebene Tabelle mit Geometriespalte `R370`, und
  `schema reverse` endet mit Exit `0`.
- **Kein Block.** Kein Pfad in `schema generate`, `schema migrate` oder
  `data transfer` wertet `R370` oder `R365` aus. Die bestehenden Zusicherungen
  zu `E057` bleiben unverändert.
- **Sabotage.** Jeder dieser Eingriffe macht einen der Tests rot:
  - `R370` oder `R365` zurück auf `INFO`;
  - `R370` auch für die unquotierte Tabelle mit registrierter Zeile;
  - `R370` zusätzlich im `R365`-Fall.
- **Spezifikation.** Beide Stellen aus Punkt 6 tragen Regel und Begründung,
  und `make docs-check` bleibt ohne Befund.

## Konsequenzen

**Dafür:**

- Der Verlust ist sichtbar, auch ohne `--verbose`, und zählt im
  Reverse-Report unter `summary.warnings`. Dieselbe Form legen die Lese-Jobs
  des MCP-Servers ab.
- Der Reverse bleibt vollständig: Spalte und Werte kommen mit, nur der SRID
  fehlt.
- Ein Reverse der von d-migrate selbst angelegten Oracle-Schemata endet
  weiterhin mit Exit `0`.
- `R365` und `R370` haben dasselbe Gewicht für denselben Verlust.
- Das neutrale Modell bleibt unverändert.

**Dagegen / zu tragen:**

- Wer `WARNING` überliest, merkt den Verlust erst am Ziel: auf SQL Server am
  fehlenden Index (`E057`).
- Auch der Datenpfad trägt die Folge. `data transfer` setzt den SRID aus dem
  Quellschema ein, wo das Ziel keinen führt. Führen ihn weder Quelle noch Ziel,
  kommen die Werte ohne Bezugssystem an. Dieser Vertrag steht in
  `spec/ddl-generation-rules.md` (Abschnitt 16.10, Datenpfad) und bleibt
  unverändert. Der Ausweg aus Punkt 5 greift dort nur, wo das Ziel den SRID
  an der Spalte führt.
- Werkzeuge, die auf `WARNING` eskalieren, sehen `R365` und `R370` neu. Wer
  `R365` bisher als `INFO` gefiltert hat, sieht ihn jetzt.
- Ein Anwender, der den Block will, bekommt ihn nicht. Dafür braucht es
  Option C und einen neuen ADR.

## Verhältnis zu benachbarten ADRs

- **ADR 0001 und ADR 0002** sind das Muster: Severity-Entscheidungen, die
  einen operativ relevanten Befund auf `WARNING` lassen, statt einen Pfad zu
  sperren. Dieser ADR folgt ihnen und ändert keinen der beiden.
- **[ADR 0016](0016-spatialite-metadata-bootstrap.md)** regelt den
  SpatiaLite-Bootstrap auf dem Migrate-Pfad. Er wird nicht berührt: Dieser ADR
  betrifft den Oracle-Reverse, nicht SpatiaLite und keinen Schreibpfad.
- Kein akzeptierter ADR wird übersteuert.

## Weitere Informationen

- Der normative Spatial-Vertrag steht in
  [`spec/type-mapping.md`](../../spec/type-mapping.md) (Oracle
  „Reverse-Entscheidungen", SQL Server „Spatial: `geometry` vs. `geography`")
  und in
  [`spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md)
  (Abschnitte 16.9 für SQL Server, 16.10 für Oracle und 16.8 für die
  Spatial-Codes).
- Die Stufen der Notizen und ihre Wirkung auf den Ausgang regelt
  [`spec/cli-spec.md`](../../spec/cli-spec.md) im Abschnitt zu den
  Exit-Code-Regeln.
