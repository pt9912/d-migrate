---
id: streaming-parallel-export-race
title: "StreamingExporter: paralleler FilePerTable-Export scheitert sporadisch mit ArrayIndexOutOfBounds"
status: open
---

# Paralleler FilePerTable-Export: sporadischer ArrayIndexOutOfBounds

## Befund

Im Voll-Lauf (2026-09-07) scheiterte

```
StreamingExporterTest > FilePerTable parallel fans out a partitioned parent
into one file per child (LN-008)
  java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0
```

Zweimal isoliert nachgefahren (`make docker-check
MODULES=":adapters:driven:streaming"`) lief er beide Male durch. Es ist also
kein deterministischer Fehler, sondern ein **Wettlauf**.

Aufgefallen beim Slice zur Kanonisierung rohen SQL-Texts; der Test hat mit
dieser Aenderung nichts zu tun (anderes Modul, anderer Pfad).

## Warum es nicht als „flaky" abgehakt gehoert

Der Name des Tests nennt den Pfad: **paralleler** Export, ein Kind je
Partition. Ein `ArrayIndexOutOfBoundsException` mit `length 0` deutet auf
eine geteilte, nicht synchronisierte Sammlung — und die laege dann im
Produktionscode (`StreamingExporter`), nicht im Test. Ein Exportlauf, der
gelegentlich abbricht, ist fuer ein Migrationswerkzeug ernster als ein
wackliger Test.

## Zu klaeren

1. Ob der Wettlauf im Test (geteilter Zustand zwischen den Faellen) oder im
   `StreamingExporter` selbst liegt.
2. Ob der Pfad unter `org.gradle.parallel` haeufiger trifft — der Voll-Lauf
   faehrt parallel, der Einzel-Lauf weniger.
3. Reproduktion: den Test in einer Schleife fahren
   (`--tests` + wiederholt), statt auf den naechsten Voll-Lauf zu warten.
