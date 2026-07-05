# Reverse-Präferenzen: Auflösung inhärenter Reverse-Mehrdeutigkeiten

Beim Reverse-Engineering einer bestehenden Datenbank in das neutrale Modell kann
ein Ziel-DDL in Einzelfällen **mehrere gleichwertige** Neutralmodell-Repräsentationen
zulassen, ohne dass die Datenbank die Information trägt, die die Wahl entscheiden
würde — eine **inhärente Mehrdeutigkeit**. d-migrate löst solche Fälle nicht durch
eine Auto-Heuristik (die gegen einen Teil der Anwender rät) und nicht durch eine
tolerante Vergleichs-Faltung, sondern durch eine **deklarierte Anwender-Präferenz**.

## 1. Prinzip

- **Deklaration statt Heuristik.** Nur der Anwender kennt die Absicht; er erklärt sie.
- **Konservativer Default.** Ohne Deklaration gilt die spec-konforme
  Standard-Repräsentation — der Reverse-Output ist byte-identisch zum Stand ohne das
  Feature (keine Regression).
- **Wurzel, nicht Symptom.** Die Präferenz wirkt an der Reverse-Quelle (was ins
  neutrale Modell geschrieben wird), nicht im nachgelagerten Vergleich. Die
  Fingerprint-Berechnung bleibt unverändert.
- **Nicht stumm.** Weicht der Reverse per Präferenz vom Default ab, wird das mit
  einer INFO-Note im Report festgehalten (Audit-Trail).

## 2. Auflösungs-Präzedenz

Für jede Mehrdeutigkeit gilt dieselbe Reihenfolge — die höchste vorhandene Quelle
gewinnt:

1. **CLI-Flag** (pro Lauf)
2. **Konfigurationsdatei** `.d-migrate.yaml`, Abschnitt `reverse:` (pro Projekt)
3. **Konservativer Default**

Die Granularität ist global (pro Lauf/Projekt).

## 3. Oberfläche

- **Konfigurationsdatei:** ein `reverse:`-Block in `.d-migrate.yaml`. Das
  Anwender-Vokabular ist bewusst **dialekt-neutral** gehalten (z. B. Breite `32`/`64`
  statt interner Neutraltyp-Namen), damit der stabile Konfigurations-Vertrag nicht an
  Implementierungs-Interna koppelt.
- **CLI-Flag:** ein gleichbedeutendes Flag auf den reverse-lesenden Kommandos
  (`schema reverse`, `data transfer`), das die Konfiguration übersteuert.

Die konkreten Schlüssel und Flags stehen in
[`connection-config-spec.md`](connection-config-spec.md) und
[`cli-spec.md`](cli-spec.md).

## 4. Registry der Mehrdeutigkeiten

| Dialekt | Mehrdeutigkeit | Präferenz-Werte | Default | Detail |
| ------- | -------------- | --------------- | ------- | ------ |
| SQLite | `INTEGER PRIMARY KEY AUTOINCREMENT` (64-bit-Rowid) ist speicher-ununterscheidbar vom 32-bit-`identifier`-Vertrag und von 64-bit `biginteger` + `generation: identity` | Breite `32` (→ `identifier`) · `64` (→ `biginteger` + `identity`) | `32` | [`type-mapping.md`](type-mapping.md) |

Weitere inhärente Reverse-Mehrdeutigkeiten (nur dann, wenn ein Dialekt eine
Repräsentation nicht eindeutig rekonstruieren kann) tragen sich hier als zusätzliche
Zeile ein und verweisen auf die zuständige Detail-Spezifikation. Nur echte
Ununterscheidbarkeit gehört in diese Registry — verlustbehaftete, aber eindeutige
Abbildungen sind gewöhnliche Reverse-Notes, keine Präferenzen.
