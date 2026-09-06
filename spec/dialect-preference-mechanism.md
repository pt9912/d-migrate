# Dialekt-Präferenzen: Auflösung inhärenter Dialekt-Mehrdeutigkeiten

An zwei Stellen kann d-migrate eine Abbildung nicht aus sich heraus entscheiden,
weil die Information, die die Wahl bestimmt, nirgends steht — eine **inhärente
Mehrdeutigkeit**:

- **Beim Lesen (Reverse).** Ein Ziel-DDL lässt mehrere gleichwertige
  Neutralmodell-Repräsentationen zu, und die Datenbank trägt nicht, welche gemeint
  war.
- **Beim Schreiben.** Der Quellwert ist eindeutig, aber der Zieldialekt kann ihn
  nicht darstellen, und mehrere Ersatzdarstellungen sind gleich vertretbar.

d-migrate löst solche Fälle nicht durch eine Auto-Heuristik (die gegen einen Teil
der Anwender rät) und nicht durch eine tolerante Vergleichs-Faltung, sondern durch
eine **deklarierte Anwender-Präferenz**.

## 1. Prinzip

Zwischen den Dialekten gibt es Unterschiede, die kein Werkzeug wegdefinieren kann.
Wo eine Abbildung deshalb nicht eindeutig ist, **entscheidet der Anwender** — so
weit es geht, und nicht d-migrate stellvertretend für ihn. Diese Spezifikation
beschreibt, wie er das erklärt und wie d-migrate sich verhält, solange er es nicht
getan hat.

- **Deklaration statt Heuristik.** Nur der Anwender kennt die Absicht; er erklärt sie.
- **Konservativer Default.** Ohne Deklaration gilt die spec-konforme
  Standard-Repräsentation — der Reverse-Output ist byte-identisch zum Stand ohne das
  Feature (keine Regression).
- **Wurzel, nicht Symptom.** Die Präferenz wirkt dort, wo der Wert entsteht — beim
  Lesen an der Reverse-Quelle (was ins neutrale Modell geschrieben wird), beim
  Schreiben an der Schreibstelle. Nie im nachgelagerten Vergleich; die
  Fingerprint-Berechnung bleibt unverändert.
- **Nicht stumm.** Weicht ein Lauf per Präferenz vom Default ab, wird das mit
  einer INFO-Note im Report festgehalten (Audit-Trail).

## 2. Auflösungs-Präzedenz

Für jede Mehrdeutigkeit gilt dieselbe Reihenfolge — die höchste vorhandene Quelle
gewinnt:

1. **CLI-Flag** (pro Lauf)
2. **Konfigurationsdatei** `.d-migrate.yaml` — Abschnitt `reverse:` für
   Lese-Präferenzen, `write:` für Schreib-Präferenzen (pro Projekt)
3. **Konservativer Default**

Die Granularität ist global (pro Lauf/Projekt).

## 3. Oberfläche

- **Konfigurationsdatei:** ein `reverse:`- bzw. `write:`-Block in
  `.d-migrate.yaml`, darunter je Dialekt. Das Anwender-Vokabular ist bewusst
  **dialekt-neutral** gehalten (z. B. Breite `32`/`64` statt interner
  Neutraltyp-Namen), damit der stabile Konfigurations-Vertrag nicht an
  Implementierungs-Interna koppelt.
- **CLI-Flag:** ein gleichbedeutendes Flag auf den betroffenen Kommandos, das die
  Konfiguration übersteuert — für Lese-Präferenzen die reverse-lesenden
  (`schema reverse`, `data transfer`), für Schreib-Präferenzen die schreibenden
  (`data import`, `data transfer`).

Die konkreten Schlüssel und Flags stehen in
[`connection-config-spec.md`](connection-config-spec.md) und
[`cli-spec.md`](cli-spec.md).

## 4. Registry: Lese-Mehrdeutigkeiten (Reverse)

| Dialekt | Mehrdeutigkeit | Präferenz-Werte | Default | Detail |
| ------- | -------------- | --------------- | ------- | ------ |
| SQLite | `INTEGER PRIMARY KEY AUTOINCREMENT` (64-bit-Rowid) ist speicher-ununterscheidbar vom 32-bit-`identifier`-Vertrag und von 64-bit `biginteger` + `generation: identity` | Breite `32` (→ `identifier`) · `64` (→ `biginteger` + `identity`) | `32` | [`type-mapping.md`](type-mapping.md) |

Weitere inhärente Reverse-Mehrdeutigkeiten (nur dann, wenn ein Dialekt eine
Repräsentation nicht eindeutig rekonstruieren kann) tragen sich hier als zusätzliche
Zeile ein und verweisen auf die zuständige Detail-Spezifikation. Nur echte
Ununterscheidbarkeit gehört in diese Registry — verlustbehaftete, aber eindeutige
Abbildungen sind gewöhnliche Reverse-Notes, keine Präferenzen.

## 5. Registry: Schreib-Mehrdeutigkeiten

| Dialekt | Mehrdeutigkeit | Präferenz-Werte | Default | Detail |
| ------- | -------------- | --------------- | ------- | ------ |
| Oracle | Der leere String `''` ist in Oracle **identisch mit NULL**. Trifft ein leerer Quellwert auf eine `NOT NULL`-Spalte, ist er nicht schreibbar; welcher Ersatz gemeint ist, weiß nur der Anwender | `error` (Lauf bricht mit benannter Meldung ab) · `null` (als NULL schreiben, nur bei nullbarer Spalte) · beliebiger Literalwert (z. B. `" "`), der an die Stelle des leeren Strings tritt | `error` | [`type-mapping.md`](type-mapping.md) |

## 6. Was hier hineingehört

Die Registries wachsen mit jedem Dialekt-Unterschied, bei dem mehrere Antworten
gleich vertretbar sind. Der Prüfstein ist nicht „ist es unangenehm", sondern:

- Gibt es **mehr als eine** vertretbare Antwort? Dann gehört die Wahl dem
  Anwender.
- Gibt es genau eine (der Zieldialekt kann es schlicht nicht, und jede Ersatz-
  darstellung wäre eine stille Verfälschung)? Dann ist es ein **Blocker** oder eine
  **Transformations-Note**, keine Präferenz.

Ein Sonderfall verdient Aufmerksamkeit: wenn d-migrate heute etwas **still tut**
(eine Struktur weglässt, einen Wert umformt, eine Voreinstellung wählt), ohne dass
der Anwender es angeordnet hat, ist das ein Kandidat für diese Registry — auch
dann, wenn das aktuelle Verhalten vertretbar ist.

Auch hier gilt die Abgrenzung: nur was der Zieldialekt **nicht darstellen kann**
und wofür mehrere Ersatzdarstellungen gleich vertretbar sind, gehört in diese
Registry. Eine verlustbehaftete, aber eindeutige Abbildung (etwa eine Typ-Degradierung)
ist eine gewöhnliche Transformations-Note, keine Präferenz.

Der Default `error` ist die konservative Wahl im Sinne von Abschnitt 1: ohne
Deklaration ändert d-migrate **keine Daten** und schreibt nichts, was der Anwender
nicht angeordnet hat. Er muss dafür eine Meldung tragen, die die Ursache nennt —
eine rohe Treibermeldung (`ORA-01400`) genügt nicht.
