---
status: accepted
date: 2026-09-08
decision-makers: pt9912
consulted: docs/planning/open/raw-sql-text-drift.md, docs/adr/0049-abdeckende-und-clustered-indizes-im-neutralen-modell.md
informed: spec/ddl-generation-rules.md, docs/user/anwenderhandbuch.md
---

# Vergleich rohen SQL-Texts — Server-Form gegen Server-Form statt Normalisierung

> **Status: accepted (2026-09-08).** Rohen SQL-Text (Sichten-Rumpf,
> CHECK-Ausdruck, Index-Prädikat, Index-Ausdruck) **nicht** lokal
> normalisieren, sondern den Vergleich so führen, dass Autorentext und
> Katalogform nie gegeneinander stehen. Der Post-Compare vergleicht die
> beobachtete Form gegen die **nach dem Anwenden zurückgelesene**; die
> Planung beantwortet „hat der Autor etwas geändert?" aus der **Herkunft**,
> nicht aus einem Textvergleich mit dem Server. `schema compare` bleibt
> streng. Kein SQL-Parser.

## Kontext und Problemstellung

Das neutrale Modell trägt an vier Stellen SQL, das kein Baustein ist, sondern
Text:

- `ViewDefinition.query`
- `ConstraintDefinition.expression` (CHECK/EXCLUDE)
- `IndexDefinition.where`
- `IndexColumn.expression`

Comparator und Fingerabdruck vergleichen diesen Text wortgleich. Die Server
geben ihn aber nicht wortgleich zurück. Gemessen gegen PostgreSQL 16 und
Oracle 23:

| Feld | geschrieben | zurückgelesen |
| --- | --- | --- |
| CHECK | `age >= 18` | `((age >= 18))` |
| CHECK mit `--`-Kommentar | `status = 'A'   -- nur aktive`⏎`AND deleted = false` | `(((status = 'A'::text) AND (deleted = false)))` |
| Sicht | `SELECT id, email FROM customers WHERE age > 21` | mehrzeilig umbrochen, mit `;` |
| Index-Ausdruck (PG) | `(amt + 2)` | `(amt + 2::numeric)` |
| Index-Ausdruck (PG) | `upper(nm)` | `upper(nm)` — **unverändert** |
| Index-Ausdruck (Oracle) | `("amt" + 2)` | `"amt"+2` |

Zwei Zeilen dieser Tabelle sind schärfer als die erste Messung und tragen die
Entscheidung:

- **Der `--`-Kommentar ist beim Zurücklesen spurlos weg.** PostgreSQL gibt den
  Ausdruck aus seinem Parsebaum aus, nicht aus dem Eingabetext. Der Autorentext
  ist aus der Katalogform damit **grundsätzlich nicht rekonstruierbar** — kein
  Normalisierer, wie klug auch immer, kann diese Richtung je schließen.
- **`upper(nm)` driftet nicht, `upper(nm::text)` schon.** Der Cast entsteht nur,
  wenn die Spalte `varchar(n)` ist und nicht `text`. Ob ein Ausdruck driftet,
  hängt also am **Spaltentyp** — ein Ausdruck lässt sich nicht freistehend
  normalisieren.

Die Folge ist zweifach. Der Post-Compare meldet nach jedem
`migrate --execute` Drift. Und weil der Comparator dieselben Felder führt,
plant der **nächste** Lauf dieselbe Änderung erneut — eine Migration, die
nicht konvergiert.

Das betrifft Sichten und CHECK-Constraints seit jeher; sichtbar wurde es erst
mit den Index-Ausdrücken, weil dort zum ersten Mal jemand hingesehen hat.

## Warum lokale Normalisierung nicht in Frage kommt

Der naheliegende Weg — Leerraum zusammenziehen, redundante Klammern und ein
abschließendes Semikolon entfernen — wurde gebaut und wieder zurückgenommen.
Er ist nicht bedeutungserhaltend:

- Ein `--`-Kommentar reicht bis zum Zeilenende. Den Zeilenumbruch zu einem
  Leerzeichen zu machen kommentiert **den Rest der Bedingung aus**.
- Der kanonisierte CHECK-Ausdruck wandert über `AddConstraint` bis in die
  **erzeugte DDL**. Aus einer Vergleichs-Projektion würde eine
  Textverfälschung.
- PostgreSQLs Dollar-Quoting (`$$…$$`) ist ebenfalls eine Zeichenkette, die
  ein Quote-Scanner nicht als solche erkennt.

Derselbe Befund steht bereits im Repo: `RoutineBodyNormalizer` hält für
dieselbe Textklasse fest, dass innerer Leerraum und Kommentare
bedeutungstragend bleiben, weil ein sicheres Zusammenziehen semantisches
Verständnis bräuchte.

Ein Normalisierer müsste also Kommentare, Dollar-Quoting und
Dialekt-Escapes kennen — und wäre damit ein Parser. Das Repo hat keinen:
`spec/jsqlparser-adapter.md` beschreibt einen Adapter, zu dem **kein Code
existiert**, und der vorhandene `ViewQueryTokenizer` kennt keinen
Kommentar-Token.

**Kein Parser zu brauchen ist deshalb kein Nebeneffekt dieser Entscheidung,
sondern ihr Hauptargument.**

## Entscheidung (Vorschlag)

1. **Rohes SQL wird nicht lokal normalisiert.** Kein Zeichen-Scanner, kein
   Parser, keine „konservative" Teilmenge.

2. **Der Post-Compare vergleicht Server-Form gegen Server-Form.** Für die
   vier Textfelder ist die Grundlinie nicht der Dateitext, sondern die Form,
   die der Server **unmittelbar nach dem Anwenden** führt. Sie wird ohnehin
   gelesen; der Vergleich wird dadurch nicht teurer, sondern billiger.

   Das verbessert die Drift-Erkennung sogar: eine Handänderung am Server
   fällt weiterhin auf (beobachtet-jetzt weicht von beobachtet-nach-Anwenden
   ab), während heute jeder Lauf Drift meldet und die echte Änderung darin
   untergeht.

3. **Die Planung entscheidet aus der Herkunft.** Die Frage lautet nicht
   „unterscheidet sich der Dateitext von der Katalogform?" — das tut er
   immer —, sondern „hat der **Autor** den Text seit dem letzten Anwenden
   geändert?". Das ist eine Aussage über zwei Autorentexte und braucht den
   Server nicht.

4. **`schema compare` bleibt streng.** Es beantwortet eine andere Frage als
   die Planung und darf einen Textunterschied weiterhin als Unterschied
   melden — dieselbe Grenze, die `TableComparator.targetCanonicalization`
   schon heute zieht (dort: der Migrate-Pfad unterdrückt, was das Ziel nicht
   ausdrücken kann, `schema compare` nicht).

## Betrachtete Optionen

### A — Lokale Normalisierung

Verworfen, siehe oben. Sie ist entweder unsicher oder ein Parser.

### B — Den Server fragen, was er speichern würde

Das Objekt in einem Wegwerf-Kontext anlegen, die Katalogform lesen,
verwerfen. In PostgreSQL sauber, weil DDL transaktional ist. **In Oracle
nicht** — dort committet DDL implizit, es bliebe Anlegen und Löschen mit
Zeitfenster und Rechtebedarf.

Zwei weitere Einwände: die Deparse-Form hängt am Objekttyp (ein
Sichten-Rumpf kommt anders zurück als ein Index-Ausdruck), man müsste also
durch dieselbe Objektart normalisieren, die man vergleicht. Und bei
`schema compare` gegen ein fremdes Zielsystem Objekte anzulegen ist eine
Nebenwirkung, die niemand erwartet.

Nicht grundsätzlich verworfen, aber von Option D abgelöst: ein eigenes
Sandkasten-Schema hat dieselbe Wirkung ohne die Nebenwirkung.

### C — Textfelder aus dem Vergleich nehmen

Verworfen. Der Text **ist** die Aussage von Sicht, CHECK und
Ausdrucks-Index; ihn auszublenden hieße, eine echte Änderung nicht mehr zu
sehen. Anders als bei Fähigkeits-Unterschieden (Bitmap-Zugriffsmethode,
untere Partitionsgrenze) gibt es hier nichts, das der Zielserver „nicht
ausdrücken kann" — er drückt es aus, nur anders geschrieben.

### D — Sandkasten-Schema: das Soll anwenden und zurücklesen

Statt Wegwerf-Objekte im Schema des Anwenders anzulegen (Option B), ein
eigenes Schema auf demselben Server: dort das Soll-Schema anwenden, die
Katalogform lesen, das Schema verwerfen. Beide Seiten des Vergleichs stehen
dann in Server-Form, ohne dass das Ziel berührt wird.

Das nimmt Option B ihren Haupteinwand — die Nebenwirkung auf dem Zielsystem
— und löst zugleich die Frage nach der richtigen Deparse-Form, weil dasselbe
Objekt derselben Art auf demselben Server entsteht.

**Der nicht offensichtliche Teil:** die Deparse-Form hängt an den
**Spaltentypen**. `upper(nm)` wird genau deshalb zu `upper(nm::text)`, weil
`nm` den Typ `text` hat. Ein Ausdruck lässt sich also nicht freistehend
normalisieren; der Sandkasten braucht die Tabellenskelette, auf die der
Ausdruck sich bezieht. Aus „einen Ausdruck normalisieren" wird damit „das
Soll-Schema (oder den Teil davon, der rohen SQL-Text trägt, samt der
Tabellen, auf die er verweist) anwenden".

**Dafür:**

- Kein Parser, keine Nebenwirkung auf dem Zielsystem, dieselbe Server-Version
  und damit dieselbe Deparse-Form.
- Die erzeugte DDL wird nebenbei **geprüft**, bevor das Zielsystem sie sieht.
- Funktioniert auch für den Vergleich Datei gegen Datenbank ohne vorherige
  Anwendung — genau die Lücke, die Richtung 2b offenlässt.

**Dagegen / zu tragen:**

- Braucht Rechte, ein Schema anzulegen. Auf vielen Zielsystemen hat der
  Migrations-Nutzer die nicht.
- Kostet eine Anwendung je Lauf. Begrenzbar auf die Objekte mit rohem
  SQL-Text plus ihre Tabellen, und über den Texthash zwischenspeicherbar,
  aber nicht umsonst.
- Aufräumen ist Pflicht, und in Oracle committet DDL implizit — ein
  abgebrochener Lauf hinterlässt das Schema. Es braucht einen erkennbaren
  Namen und einen Aufräumpfad.

## Konsequenzen

**Dafür:**

- Der Migrate-Lauf konvergiert; die zweite Ausführung plant nichts mehr.
- Kein Parser, keine Wartung einer SQL-Grammatik.
- Keine Nebenwirkung auf dem Zielsystem.
- Der Post-Compare wird billiger, nicht teurer.

**Dagegen / zu tragen:**

- „Drift" bedeutet für Textfelder etwas anderes als bisher — nicht mehr
  „weicht von der Datei ab", sondern „weicht von dem ab, was wir angewandt
  haben". Das gehört ins Handbuch.
- Es braucht einen Ort für die Herkunft. Das Migrations-Artefakt ist der
  naheliegende, weil es bereits Fingerabdrücke trägt.
- Ein Lauf **ohne** Herkunft (erstes Anwenden, verlorenes Artefakt) hat keine
  Grundlinie.

## Die fünf offenen Punkte, entschieden

1. **Die Herkunft liegt im Migrations-Artefakt**, nicht in einer Ablage im
   Zielsystem. Eine Ablage wäre dauerhafter, wäre aber ein von d-migrate
   angelegtes Objekt auf fremdem Server — eine Zusage, die dieser ADR nicht
   geben will. Der Preis steht in Punkt 2.
2. **Ohne Herkunft wird konservativ geplant.** Der erste Lauf gegen eine
   bestehende Datenbank und ein Lauf mit verlorenem Artefakt führen die
   Änderung aus, auch wenn sie vielleicht unnötig ist. Bei Sichten ist das
   ein `CREATE OR REPLACE`, bei CHECK-Constraints ein Drop+Add — kurzzeitig
   ohne Prüfung, aber ohne Datenverlust. Ab dem zweiten Lauf konvergiert er.
   Die Alternative (melden statt planen) ließe eine echte Autoränderung beim
   ersten Lauf liegen, und das fiele nur auf, wer die Warnung liest.
3. **Der Fingerabdruck wird angehoben.** Ändert sich die Projektion der
   Textfelder, ändern sich alle Abdrücke von Schemata mit Sicht oder CHECK.
4. **Option D kommt als Zusatz, per Konfigurationsdatei einzuschalten.** Nicht
   voreingestellt: der Sandkasten braucht das Recht, ein Schema anzulegen, und
   in Oracle committet DDL implizit — ein abgebrochener Lauf hinterlässt es.
   Wer diese Kosten tragen will und die Lücke schließen möchte (Vergleich ohne
   vorheriges Anwenden), schaltet ihn ein; er greift dann dort, wo Herkunft
   fehlt, statt Punkt 2.
5. **`CanonicalPayload` geht mit.** Die Index-Identität hängt an drei
   Projektionen (Comparator, Fingerabdruck, `CanonicalPayload`); zwei zu
   ändern und die dritte nicht bricht den dort dokumentierten Vertrag.
   **Folge: bestehende Overlays werden entwertet** — ihre Operations-IDs
   ändern sich und müssen neu erzeugt werden. Das ist der Preis dafür, den
   Vertrag ganz statt halb zu bewegen.
