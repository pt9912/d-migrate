# Tracker: Autoritäts-Tabelle für `make`-Targets (Voraussetzung für d-check `targets`)

> **Status:** Vorschlag (Draft) / Trigger Watch (2026-07-31)
> **Trigger:** Das d-check-Modul `targets` (`DC-FA-TGT-001`) erzwingt maschinell „kein
> halluziniertes Gate / kein undokumentiertes Gate" — genau die Drift-Klasse, die am
> 2026-07-31 auftrat: `docker-oci-build` wurde umdefiniert, der `jib-image-tar`-Pfad
> entfernt und `readme-parity-gate` ergänzt, ohne dass irgendeine Prüfung die Doku
> dagegen gehalten hätte.
> **Aktivierungsbedingung** (Move nach `../next/`): Entscheidung, welches Dokument die
> **Autorität** trägt und welche Targets `exempt` sind. Danach ist das Einschalten in
> [`.d-check.yml`](../../../.d-check.yml) ein Dreizeiler.

## Warum es heute nicht eingeschaltet ist

`targets` erkennt eine Behauptung nur als `` `make X` `` **in einer Tabellenzeile**
(`|`-Präfix) — Prosa und Code-Blöcke zählen bewusst nicht, sonst gäbe es Fehlalarme aus
Fließtext. d-migrate dokumentiert seine Targets aber in einem **Code-Block** im README, und
ein Autoritäts-Dokument (d-checks eigenes Beispiel nutzt `AGENTS.md`) existiert hier nicht.

> **Quelle der Ausschließlichkeit: `DC-FA-TGT-001`** („Tabellen-Scoping (Erkennungs-Vertrag)")
> im d-check-**Lastenheft**, wörtlich: `make X` *„gilt **nur in Tabellenzeilen** (Zeilen mit
> `|`-Präfix) als Existenz-/Vollständigkeits-Behauptung"*. Im d-check-**Benutzerhandbuch**
> steht das Wort „nur" **nicht** — dort nur die positive Regel („jedes in einer
> Doku-Tabellenzeile behauptete `make X` ist eine Makefile-Regel"), aus der die
> Ausschließlichkeit nicht folgt. Wer die Abgrenzung im Handbuch sucht, findet sie nicht.

Die Messung dazu:

| | Anzahl |
| --- | --- |
| Echte Regeln in `Makefile` + `make/*.mk` (7 Dateien) | **83** |
| Davon im README-Code-Block genannt | **15** |

Einschalten ohne Vorarbeit hieße also **68 `gate-undocumented`-Befunde** und ein
blockiertes `make gates`. Das ist kein Konfigurationsschritt.

## Zu entscheiden

1. **Wer trägt die Autorität?** Ein neues AGENTS.md (d-checks Konvention), ein Abschnitt
   im README oder eine eigene Target-Übersicht unter docs/. Die Datei muss die Targets als
   `` `make X` `` in Tabellenzeilen führen.
2. **Welche Targets brauchen keine Deklaration?** `targets.exempt-targets` nimmt
   Regelnamen **exakt** (kein Glob). Kandidaten sind interne Hilfsregeln ohne
   Bedien-Charakter — die Liste ist Teil der Entscheidung, nicht Beiwerk.
3. **Welche Makefiles zählen?** `targets.makefiles` müsste alle sieben nennen
   (`Makefile` plus `make/{gate,bi-demo,sample-db,d-check,native,a-check}.mk`); die
   Regel-Erkennung ist eine Zeilen-Heuristik ohne `include`-Auflösung.

## Nutzen

Beide Richtungen sind wertvoll und heute ungeprüft:

- **`gate-phantom`** — die Doku nennt ein Target, das es nicht gibt. Genau das entstand
  heute beim Entfernen des Jib-Pfads (die README-Zeile zu `jib-image-tar` musste von Hand
  nachgezogen werden).
- **`gate-undocumented`** — eine Regel ohne Deklaration. Bei 83 zu 15 ist das der
  Normalfall, nicht die Ausnahme.
