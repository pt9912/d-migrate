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

## Nachgemessen (2026-09-08, PostgreSQL 16)

Die erste Messung war zu freundlich. Zwei Zeilen entscheiden die Sache:

| Feld | geschrieben | zurueckgelesen |
| --- | --- | --- |
| CHECK mit `--`-Kommentar | `status = 'A'   -- nur aktive`⏎`AND deleted = false` | `(((status = 'A'::text) AND (deleted = false)))` |
| Index-Ausdruck | `upper(nm)` | `upper(nm)` — **unveraendert** |
| Index-Ausdruck | `(amt + 2)` | `(amt + 2::numeric)` |

- **Der Kommentar ist spurlos weg.** PostgreSQL gibt den Ausdruck aus seinem
  Parsebaum aus, nicht aus dem Eingabetext. Der Autorentext ist aus der
  Katalogform damit **grundsaetzlich nicht rekonstruierbar** — Richtung 1 ist
  nicht nur unsicher, sie ist unmoeglich, und zwar in jeder denkbaren
  Ausbaustufe.
- **`upper(nm)` driftet gar nicht**, `upper(nm::text)` schon: der Cast entsteht
  nur bei `varchar(n)`. Ob ein Ausdruck driftet, haengt am **Spaltentyp** — er
  laesst sich nicht freistehend normalisieren, was Option D ihre Tabellen-
  Skelette abverlangt.

## Stand

[ADR 0053](../../adr/0053-vergleich-rohen-sql-texts.md) ist **accepted**; die
fuenf offenen Punkte sind dort entschieden:

1. Herkunft im **Migrations-Artefakt**, nicht in einer Ablage im Zielsystem.
2. **Ohne Herkunft wird konservativ geplant** (ausfuehren statt melden).
3. Der **Fingerabdruck wird angehoben**.
4. **Option D kommt als Zusatz, per Konfigurationsdatei einzuschalten** — nicht
   voreingestellt, weil er das Recht braucht, ein Schema anzulegen.
5. **`CanonicalPayload` geht mit** — Folge: bestehende Overlays werden
   entwertet.

## Vorbedingung, die beim Aufsetzen des Baus herauskam

Die Herkunft beschreibt **ein** Schema: „so stand der Text, als zuletzt
angewandt wurde". Der Overlay-Vertrag kann das heute nicht ausdruecken.
`MigrationOverlay` traegt `sourceFingerprint` **und** `targetFingerprint`
flach (`migration-overlay.v1`) und gilt damit fuer ein Schema*paar*; der
Validator lehnt ab, sobald eine der beiden Seiten nicht passt.

Genau diese Frage ist mit
[ADR 0050](../../adr/0050-overlay-bindung-uebergang-vs-darstellung.md)
entschieden — `Representation` fuer Overlays, die ein Schema beschreiben,
`migration-overlay.v2` — aber **noch nicht gebaut**: im Code gibt es weder
`Representation` noch `Transition`, und `MigrationOverlayKinds` fuehrt nur
`using-expression` und `rename-mapping`. Der Plan dafuer liegt in
[`done/partition-mapping-overlay.md`](../done/partition-mapping-overlay.md).

Ein Herkunfts-Overlay mit der v1-Form zu bauen hiesse, beide
Fingerabdruck-Felder auf dasselbe Schema zu setzen — eine Bindung
vorzutaeuschen, die es nicht gibt, und ADR 0050 im selben Zug zu
unterlaufen.

## Schnitt

1. ~~**Overlay v2** (ADR 0050)~~ — **gebaut.** Sealed Bindung
   `Transition`/`Representation`, `migration-overlay.v2`; ein
   Uebergangs-Dokument bleibt v1 und unveraendert lesbar. Die Vorbedingung
   steht damit; sie kam ohnehin auch `partition-mapping` zugute.
2. ~~**Herkunfts-Overlay** `raw-text-provenance`, `Representation`-gebunden~~ —
   **gebaut.** Je Objekt und Feld der zuletzt angewandte Autorentext und die
   daraufhin beobachtete Katalogform. Erzeugt von
   `migrate --execute --provenance-output`, und zwar **nur nach sauberem
   Post-Compare**: vorher steht nicht fest, dass das Paar zusammengehoert.
   Gelesen wird es ueber `--migration-overlay` — was heute nur heisst, dass es
   angenommen und geprueft wird; ausgewertet wird es erst mit Punkt 3.
3. **Vergleich aus der Herkunft** in allen **drei** Projektionen zugleich
   (Comparator, `MigrationFingerprint`, `CanonicalPayload`) — Punkt 5 oben.
   Fingerabdruck-Anhebung geht mit.
4. ~~**Post-Compare Server-Form gegen Server-Form** fuer die vier Felder.~~
   — **gebaut.** Die vier Textfelder fallen ueber `RawSqlTextProjection` aus
   dem Fingerabdruck des Post-Compare und werden getrennt geprueft: wie der
   Server das Feld **vor** dem Lauf fuehrte gegen wie **danach**, und nur an
   Objekten, die der Plan nicht angefasst hat. Damit meldet ein `--execute`
   nicht mehr bei jedem Lauf Drift, und eine Handaenderung am Server faellt
   dabei erstmals wirklich auf statt in der Dauer-Drift unterzugehen.

   Die Grundlinie kommt aus dem Lauf selbst (`prepared.targetNormalized`) —
   ohne Herkunfts-Overlay. Was damit **noch nicht** geht: eine Handaenderung
   zwischen zwei Laeufen zu erkennen, denn dafuer muesste die Katalogform den
   Lauf ueberdauern. Genau das leistet Punkt 2.
5. **Sandkasten** (Option D), per Konfigurationsdatei einzuschalten, greift
   dort, wo Herkunft fehlt.

## Nicht betroffen

`schema generate` und `schema reverse` sind unberuehrt — beide erzeugen
gueltige Ergebnisse. Es geht ausschliesslich um den **wiederholten**
Vergleich gegen eine Datenbank.
