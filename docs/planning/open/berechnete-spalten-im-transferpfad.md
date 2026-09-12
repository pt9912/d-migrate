---
id: berechnete-spalten-im-transferpfad
title: "Eine berechnete Zielspalte macht `data transfer` unbenutzbar"
status: open
---

# Eine berechnete Zielspalte macht `data transfer` unbenutzbar

## Befund

Seit dem Schreibpfad-Stueck von
[`generated-column-expression-dropped.md`](../done/generated-column-expression-dropped.md)
lehnt der Import auf allen fuenf Zielen benannt ab, wenn der Chunk eine
berechnete Spalte traegt. Der Ausweg, den die Meldung nennt — „nehmen Sie die
Spalte aus Export/Transfer heraus" — ist fuer einen **direkten** `data transfer`
keiner: es gibt keinen Spaltenfilter. Weder `data transfer` noch `data import`
oder `data export` kennen eine Spaltenauswahl (geprueft: kein `--columns`, kein
`excludeColumns`/`columnFilter` im ganzen Baum).

Der Lesepfad liefert die berechnete Spalte wie jede andere mit — sie **hat**
einen Wert. Damit endet jeder Transfer einer Tabelle mit berechneter Spalte in
der Ablehnung. Das ist keine Verschlechterung (vorher fiel dort ein roher
Treiberfehler), aber eine Luecke, die jetzt sichtbar benannt ist.

Heutiger Weg drumherum: `data export` in eine Datei, die Spalte dort entfernen,
`data import`. Das ist bei grossen Datenmengen kein Weg.

## Die Frage, die der Schnitt klaeren muss

**Ablehnen oder auslassen?** Der Wert einer berechneten Spalte ist
**abgeleitet** — er steht in den Quelldaten, aber er traegt keine Information,
die das Ziel nicht selbst herstellt. Sie aus der `INSERT`-Spaltenliste
auszulassen ist deshalb verlustfrei, und genau das tut d-migrate an einer Stelle
bereits: der SQLite-Tabellen-Neubau laesst die berechnete Spalte aus dem
`INSERT INTO neu (…) SELECT … FROM alt` heraus, sonst scheiterte der Neubau.

Dagegen steht, dass stilles Auslassen einer uebergebenen Spalte genau die
Gestalt hat, die dieses Werkzeug sonst vermeidet. Die Auflösung ist
wahrscheinlich: **auslassen und es sagen** (ein `W`-Code je Tabelle, nicht je
Zeile), statt abzulehnen — mit der Ablehnung nur dort, wo die Werte
*nicht* uebereinstimmen wuerden. Letzteres ist ohne Nachrechnen des Ausdrucks
nicht entscheidbar, also kaeme es auf „auslassen + melden" hinaus.

Zu klaeren ist ausserdem, ob die Entscheidung am Import haengt (der die
Zielspalten kennt) oder am Transfer-Preflight (der beide Seiten kennt und
frueher melden koennte).

## Herkunft

Fiel beim Bau des Schreibpfad-Stuecks auf (2026-09-12), beim Schreiben des
Hinweises im Anwenderhandbuch: der dort genannte Ausweg existiert fuer den
Transferpfad nicht.
