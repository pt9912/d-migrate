---
id: raw-sql-text-drift
title: "Roher SQL-Text im Modell driftet gegen den Server — Sichten, CHECKs und Ausdrucks-Indizes gleichermassen"
status: open
---

# Roher SQL-Text driftet

## Befund

Das neutrale Modell traegt an vier Stellen rohen SQL-Text:

- `ViewDefinition.query`
- `ConstraintDefinition.expression` (CHECK)
- `IndexDefinition.where` (partieller Index)
- `IndexColumn.expression` (Ausdrucks-Index, seit Slice 6b)

Alle vier werden im Fingerabdruck und im Comparator **wortgleich**
verglichen. Die Server geben sie aber nicht wortgleich zurueck. Live
gemessen gegen PostgreSQL 16 (2026-09-07):

| Feld | geschrieben | zurueckgelesen |
| --- | --- | --- |
| View | `SELECT id, email FROM customers WHERE age > 21` | vierzeilig umformatiert, mit `;` am Ende |
| CHECK | `age >= 18` | `((age >= 18))` |
| Index-Ausdruck | `upper(nm)` | `upper(nm::text)` |

Und gegen Oracle 23:

| Feld | geschrieben | zurueckgelesen |
| --- | --- | --- |
| Index-Ausdruck | `("amt" + 2)` | `"amt"+2` |

Folge: nach `migrate --execute` meldet der Post-Compare Drift, und weil der
Comparator dasselbe Feld fuehrt, plant der **naechste** Lauf dieselbe
Aenderung erneut — eine Migration, die nicht konvergiert.

**Sichten und CHECKs haben das seit jeher**; Slice 6b hat es nur sichtbar
gemacht, weil dort zum ersten Mal jemand hingesehen hat.

## Warum die uebliche Antwort hier nicht traegt

Fuer Faehigkeits-Unterschiede kanonisiert das Repo, indem es **beide Seiten
durch dieselbe verlustbehaftete Projektion** schickt — Typen ueber `toSql`,
Indizes/Partitionen ueber `capability*Canonicalizer`. Das geht hier nicht:
der Text IST die Aussage. Ihn auszublenden hiesse, eine echte Aenderung nicht
mehr zu sehen.

## Loesungsrichtungen

### 1. Konservativer Normalisierer — **versucht und zurueckgenommen**

Die naheliegende Idee: nur Umformungen, die die Bedeutung nicht aendern
koennen — Leerraum-Folgen auf ein Zeichen, ein vollstaendig redundantes
aeusseres Klammernpaar, ein abschliessendes Semikolon.

**Sie traegt nicht**, und der Grund stand bereits im Repo. `RoutineBodyNormalizer`
haelt fuer dieselbe Textklasse fest, dass innerer Leerraum und Kommentare
bedeutungstragend bleiben, weil ein sicheres Zusammenziehen semantisches
Verstaendnis braeuchte. Genau daran scheitert der Ansatz:

- Ein `--`-Kommentar reicht bis zum Zeilenende. Den Zeilenumbruch zu einem
  Leerzeichen zu machen kommentiert **den Rest der Bedingung aus**:

  ```
  status = 'A'   -- nur aktive
  AND deleted = false
  ```

  wird zu `status = 'A' -- nur aktive AND deleted = false`.

- Das bleibt nicht im Vergleich. Der kanonisierte CHECK-Ausdruck wandert
  ueber `AddConstraint` bis in die **erzeugte DDL** — aus einer
  Vergleichs-Projektion wird eine Textverfaelschung.

- Umgekehrt verschluckt es einen echten Unterschied: `a --x\nAND b` und
  `a --x AND b` fielen auf dieselbe Form.

- PostgreSQLs Dollar-Quoting (`${'$'}${'$'}…${'$'}${'$'}`) ist ebenfalls eine Zeichenkette, die
  ein einfacher Quote-Scanner nicht sieht.

Ein Normalisierer muesste also Kommentare und Dollar-Quoting kennen — und
waere damit kein Zeichen-Scanner mehr, sondern der Anfang eines Parsers.
Genau davor stand die Ueberlegung schon einmal.

**Was daraus folgt:** wenn ueberhaupt normalisiert wird, dann nur auf einer
Form, die **niemals** in die DDL zurueckfliesst — also als reiner
Vergleichsschluessel, nicht als Objekt im Diff. Und selbst dann bleibt die
Frage, ob der Gewinn (Klammern, Leerraum) den Aufwand rechtfertigt, wo
Richtung 2 die Ursache trifft.

Ein zweiter Befund derselben Runde: die Index-Identitaet haengt laut
`TableComparator` an **drei** Projektionen (Comparator, `MigrationFingerprint`,
`CanonicalPayload`). Zwei davon zu kanonisieren und die dritte nicht bricht
den Vertrag — und `CanonicalPayload` traegt die Operations-IDs, deren
Aenderung bestehende Overlays entwertet. Wer hier ansetzt, muss alle drei
zugleich bewegen.

### 2. Der Server als Kanonisierer (der eigentliche Schnitt)

Die Beobachtung, die es tragbar macht: **ueberall, wo der Drift beisst, gibt
es einen Server.** Post-Compare, `migrate` gegen eine Datenbank,
`schema compare` gegen eine Datenbank — nur der Datei-gegen-Datei-Vergleich
hat keinen, und dort stehen beide Seiten in derselben Schreibweise, driften
also gar nicht.

Und weiter: der Post-Compare fragt „hat die Migration getan, was der Plan
sagte". Fuer ein Textfeld ist das bereits beantwortet, wenn der Server die
Anweisung angenommen hat. Er muesste also nicht gegen den **Dateitext**
vergleichen, sondern gegen das, **was er selbst nach dem Anwenden fuehrt** —
und das steht nach `migrate --execute` ohnehin schon da.

Das ist der Kern: nicht den Text normieren, sondern die richtige Seite
vergleichen.

### 3. Was dann noch bleibt, erklaeren lassen

Ein Rest bleibt (etwa ein Ziel, das der Anwender nur beschreibt und nie
anwendet). Dafuer gibt es den Praeferenz-Mechanismus
(`spec/dialect-preference-mechanism.md`): eine reine Textabweichung an einem
rohen SQL-Feld als Warnung statt als Aenderung behandeln — deklariert, nicht
geraten.

## Stand

Der Entwurf liegt als [ADR 0053](../../adr/0053-vergleich-rohen-sql-texts.md)
(`proposed`) vor: Server-Form gegen Server-Form, Herkunft fuer die Planung,
kein Parser. Gebaut wird davor nichts.

## Zu entscheiden

1. Ob Richtung 1 sofort gebaut wird (sie steht Richtung 2 nicht im Weg).
2. Ob Richtung 2 der Schnitt ist — das ist eine Aenderung an der **Bedeutung**
   des Post-Compare und braucht eine ADR.
3. Ob die Anhebung von `MigrationFingerprint.ALGORITHM` (steht auf `v10`)
   dazugehoert.

## Nicht betroffen

`schema generate` und `schema reverse` sind unberuehrt — beide erzeugen
gueltige Ergebnisse. Es geht ausschliesslich um den **wiederholten**
Vergleich gegen eine Datenbank.
