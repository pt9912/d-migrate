---
id: preserve-window-serialized-opt-in
title: "Soll ein Preserve-Fenster ohne Atomizität eine Zustimmung verlangen?"
status: open
---

# Soll ein Preserve-Fenster ohne Atomizität eine Zustimmung verlangen?

## Die Frage

Oracles Preserve-Fenster ist `SERIALIZED`, nicht `ATOMIC`: niemand kommt
dazwischen, aber ein Fehlschlag lässt stehen, was bis dahin lief — jedes DDL
committet dort implizit, und sowohl die geschützten Operationen als auch der
Restore sind DDL. Das ist keine Lücke, die sich schließen lässt, sondern eine
Eigenschaft des Servers.

[`sequence-preserve-mssql-oracle.md`](sequence-preserve-mssql-oracle.md) macht
diese Zusage sichtbar — als **Warnung** im Report. Offen bleibt, ob das reicht.

## Warum das nicht schon dort entschieden wurde

Weil die Wahl zwischen Warnung und Blocker-mit-Opt-in von einer Messung
abhängt, die es noch nicht gab: **wie sich ein Abbruch im Oracle-Fenster
wirklich auswirkt.** Slice B jenes Tickets liefert sie. Vorher wäre die
Entscheidung eine Meinung über eine Beschreibung.

## Die Gabelung

1. **Es bleibt bei der Warnung.** Das Fenster funktioniert; es sichert nur
   weniger zu. Wer Oracle fährt, liest den Report.
2. **Blocker mit Opt-in.** Ein Preserve-Lauf gegen einen `SERIALIZED`-Dialekt
   blockt, bis der Betreiber die schwächere Zusage ausdrücklich annimmt. Dann
   braucht es eine Option, ihren Namen, ihren Platz in der Konfiguration und
   eine Antwort darauf, was ein bestehender Lauf beim nächsten Update erlebt.

## Die Aktivierungsbedingung

Der gemessene Fehlerfall aus Slice B — und, falls vorhanden, ein Betreiber, den
ein halb angewandtes Fenster wirklich getroffen hat. Ohne das eine ist die
Entscheidung unbegründet, ohne das andere ist sie verfrüht.

## Herkunft

Aus der Eignerentscheidung vom 2026-09-12 zu
[`sequence-preserve-mssql-oracle.md`](sequence-preserve-mssql-oracle.md):
Warnung jetzt, diese Frage gestaffelt danach.
