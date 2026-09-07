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

### 1. Konservativer Normalisierer (klein, sicher, sofort)

Nur Umformungen, die die Bedeutung **nicht** aendern koennen:

- Leerraum-Folgen auf ein Zeichen, aussen getrimmt
- ein vollstaendig redundantes aeusseres Klammernpaar entfernen
- ein abschliessendes `;` entfernen

Das erledigt `((age >= 18))` gegen `age >= 18` und den Zeilenumbruch der
Sicht. Es erledigt **nicht** `upper(nm)` gegen `upper(nm::text)`. Eine
gemeinsame Quelle fuer alle vier Felder, kein Parser, Risiko praktisch null.

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
