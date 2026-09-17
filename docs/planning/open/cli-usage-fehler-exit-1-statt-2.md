# CLI: Usage-Fehler enden mit Exit 1 statt 2

> **Status:** Befund (2026-09-17), vorbestehend und global.
> **Trigger:** Review Runde 5 des Compare-Slices
> [`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md)
> (M-3; „Offen"). Beim Prüfen der neuen Lese-Flags gemessen; der Slice hat nur
> seine eigenen Sätze korrigiert und den Befund nicht gebaut.
> **Aktivierungsbedingung:** Ein `next/`-Plan, sobald ein Anwender oder
> Orchestrator auf Exit 2 prüft — oder vorher, weil Spec und
> Troubleshooting-Leitfaden heute etwas zusagen, das nicht gilt.

## Befund

[`spec/cli-spec.md`](../../../spec/cli-spec.md) (Exit-Codes) weist
`2 USAGE_ERROR` „ungültigen Argumenten oder Flags" zu, mit dem Beispiel
„fehlender Pflicht-Parameter"; der
[Troubleshooting-Leitfaden](../../user/troubleshooting-leitfaden.md) sagt
dasselbe.

Gemessen am Image des sechsten Compare-Bauabschnitts:

| Aufruf | Exit |
| --- | --- |
| `schema reverse … --mysql-autoincrement-syntax identiy` | **1** |
| `schema reverse … --sqlite-autoincrement-width 16` | **1** |

Die Ursache liegt vor jedem Befehl:
[`Main.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt)
ruft `buildRootCommand().main(args)`, und Clikt beendet einen eigenen
Usage-Fehler (ungültiger Auswahlwert, vermutlich auch unbekannte Option und
fehlender Pflicht-Parameter) mit seinem Default-Code 1. Die Exit-2-Fälle, die
die Befehle selbst prüfen (`--lang`, unzulässige Flag-Kombinationen …), sind
davon nicht betroffen.

## Zu klären und zu tun

1. **Messen, welche Fälle betroffen sind:** ungültiger Auswahlwert (gemessen),
   unbekannte Option, fehlender Pflicht-Parameter, fehlender Wert einer Option,
   unbekannter Befehl, `--help`/`--version` (die dürfen nicht zu 2 werden).
2. **Eine Stelle:** Clikts Usage-Fehler in `Main.kt` auf Exit 2 abbilden (etwa
   über den Status-Code der Ausnahme bzw. eine eigene Behandlung von
   `PrintMessage`/`UsageError`), nicht je Befehl.
3. **Vertragswechsel benennen:** ein Skript, das heute auf 1 prüft, sieht
   danach 2 — CHANGELOG.
4. Ein Test je Klasse aus Punkt 1 durch den echten Einstiegspunkt.

Der Slice hat die neuen Sätze bereits richtig gestellt: die Lese-Flags nennt
`spec/dialect-preference-mechanism.md` einen Usage-Fehler der
Exit-Code-Tabelle (ohne Zahl), die KDoc von
`ReverseAutoIncrementSyntaxResolver` verweist auf die Tabelle.
