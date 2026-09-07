---
id: fulltext-config-fingerprint-lossy-dialects
title: "MySQL, SQLite und SQL Server verwerfen die Text-Search-Konfiguration, blenden sie im Fingerabdruck aber nicht aus"
status: open
---

# Volltext-Konfiguration: drei Dialekte projizieren nicht

## Befund

Slice 8 hat fuer Oracle die Faehigkeit `carriesFullTextConfiguration`
eingefuehrt (`DialectCapabilities`), und `capabilityIndexCanonicalizer`
blendet `IndexDefinition.textSearchConfig` dort im Fingerabdruck aus. Ohne
das meldete der Post-Compare nach jedem `migrate --execute` Drift — **und**
der naechste Lauf plante denselben Index erneut, weil auch
`TableComparator.projectIndex` das Feld fuehrt. Also kein einmaliger
Fehlalarm, sondern eine Migration, die nie fertig wird.

**Dieselbe Lage besteht bei MySQL, SQLite und SQL Server.** Keiner von
ihnen speichert die Konfiguration (PostgreSQL als Herkunftsdialekt tut es),
alle drei stehen aber weiterhin auf dem Default `true` — also „der Server
fuehrt das" —, obwohl sie es nicht tun.

## Warum es nicht im Oracle-Slice mitgemacht wurde

Dieselbe Erwaegung wie bei
[`partition-fingerprint-lossy-dialects.md`](partition-fingerprint-lossy-dialects.md)
und `namesIdentitySequences`: die Fingerabdruecke dieser Dialekte zu aendern
**entwertet bereits erzeugte Rollback-Artefakte**, die ihren
`postUpFingerprint` tragen. Fuer Oracle war der Pfad bis zu diesem Slice
ueberhaupt geblockt (`E057`), es gibt dort also nichts zu entwerten.

## Zu klaeren

1. Ob die Umstellung eine Anhebung von `MigrationFingerprint.ALGORITHM`
   braucht (steht nach Slice 7 auf `v10`).
2. Ob PostgreSQL die Konfiguration wirklich verlustfrei zurueckliest — das
   ist die Annahme hinter dem Default, aber nicht gemessen.
