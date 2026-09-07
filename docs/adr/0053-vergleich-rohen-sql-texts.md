---
status: proposed
date: 2026-09-07
decision-makers: pt9912
consulted: docs/planning/open/raw-sql-text-drift.md, docs/adr/0049-abdeckende-und-clustered-indizes-im-neutralen-modell.md
informed: spec/ddl-generation-rules.md, docs/user/anwenderhandbuch.md
---

# Vergleich rohen SQL-Texts — Server-Form gegen Server-Form statt Normalisierung

> **Status: proposed.** Vorschlag: rohen SQL-Text (Sichten-Rumpf,
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
| Sicht | `SELECT id, email FROM customers WHERE age > 21` | mehrzeilig umbrochen, mit `;` |
| Index-Ausdruck (PG) | `upper(nm)` | `upper(nm::text)` |
| Index-Ausdruck (Oracle) | `("amt" + 2)` | `"amt"+2` |

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

Nicht grundsätzlich verworfen, aber nachrangig: er wäre erst nötig, wenn ein
Datei-gegen-Datenbank-Vergleich **ohne vorherige Anwendung** den Textabgleich
wirklich braucht.

### C — Textfelder aus dem Vergleich nehmen

Verworfen. Der Text **ist** die Aussage von Sicht, CHECK und
Ausdrucks-Index; ihn auszublenden hieße, eine echte Änderung nicht mehr zu
sehen. Anders als bei Fähigkeits-Unterschieden (Bitmap-Zugriffsmethode,
untere Partitionsgrenze) gibt es hier nichts, das der Zielserver „nicht
ausdrücken kann" — er drückt es aus, nur anders geschrieben.

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

## Vor der Umsetzung zu entscheiden

1. **Wo die Herkunft liegt.** Migrations-Artefakt, oder eine eigene Ablage?
   Das Artefakt kann verloren gehen; eine Ablage im Zielsystem wäre
   dauerhafter, aber ein neues Objekt.
2. **Verhalten ohne Herkunft.** Konservativ planen (also die Änderung
   ausführen, was harmlos, aber unnötig ist), oder melden und nicht planen?
3. **Ob der Fingerabdruck-Algorithmus angehoben werden muss.** Er steht auf
   `v10`; ändert sich die Projektion der Textfelder, ändern sich alle
   Abdrücke von Schemata mit Sicht oder CHECK.
4. **Ob `CanonicalPayload` mitgeht.** Die Index-Identität hängt an drei
   Projektionen (Comparator, Fingerabdruck, `CanonicalPayload`); zwei zu
   ändern und die dritte nicht bricht den dort dokumentierten Vertrag — und
   die dritte trägt die Operations-IDs, deren Änderung bestehende Overlays
   entwertet.
