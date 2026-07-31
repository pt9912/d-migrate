# Tracker: d-check-Modul `versions` gegen `version.md` verdrahten

> **Status:** Vorschlag (Draft) / Trigger Watch (2026-07-31)
> **Trigger:** [`version.md`](../../../version.md) existiert seit 2026-07-31 und trägt die
> Versions-Koordinaten samt wandernden Ankern. Damit wäre das d-check-Modul `versions`
> (`DC-FA-VER-001`) verdrahtbar: es prüft jedes Vorkommen eines `pin-pattern` gegen die in
> `current-from` deklarierte Version und meldet Abweichungen als `version-stale`.
> **Aktivierungsbedingung** (Move nach `../next/`): die Zuordnungsfrage unten entschieden.

## Warum es nicht sofort eingeschaltet ist

`current-from` ist geklärt: `version.md#aktuell` trägt **eine** aktuelle Version — die
zuletzt veröffentlichte. Offen ist, **welche Erwähnungen** dagegen gepinnt werden sollen.

Der Stolperstein: Nicht jede Versionsnennung im Repo *soll* die aktuelle Version sein.
Die READMEs nennen im Abschnitt „Was kann ich heute laufen lassen?" bewusst das **letzte
Stable** — das ist die Empfehlung an Nutzer, nicht die neueste Version. Ist die aktuelle
Version eine Vorabversion (heute `v1.0.0-RC2`), weichen die beiden zwangsläufig ab, und ein
zu breites `pin-pattern` meldet die README-Angabe als `version-stale`, obwohl sie richtig
ist.

## Zu entscheiden

1. **Was wird gepinnt?** Naheliegend nur Registry-Koordinaten
   (`ghcr.io/pt9912/d-migrate:X.Y.Z`, `pt9912/d-migrate:X.Y.Z`) — dort ist „neueste
   Version" immer die richtige Antwort. Die Fließtext-Angaben der READMEs bewusst **nicht**.
2. **Welche Dateien sind ausgenommen?** `exempt-paths` für historische Pins: `CHANGELOG.md`,
   `docs/planning/done/**`, ADR-Texte — dort gehören alte Versionen hin.
3. **Wie wird mit Beispielen umgegangen**, die absichtlich das Stable zeigen (Quick-Start,
   Docker-Hub-Overview)? Entweder ausnehmen oder auf ein Platzhalter-Muster umstellen.

## Verhältnis zum README-Paritäts-Gate

[`scripts/readme-parity-gate.sh`](../../../scripts/readme-parity-gate.sh) prüft unter
anderem, dass beide READMEs **dieselbe** aktuelle Version nennen. `versions` wäre die
stärkere Invariante: es prüft gegen **eine deklarierte Quelle** statt auf Einigkeit — zwei
Dokumente können sich einig und beide falsch sein.

Deckt `versions` diesen Teil ab, kann die dritte Prüfung des Skripts entfallen; die beiden
anderen (Versionsmengen und Anzahl der Milestone-Einträge im Status-Block) bleiben
Eigenbau, weil kein d-check-Modul Inhalt **zwischen zwei Dokumenten** vergleicht.
