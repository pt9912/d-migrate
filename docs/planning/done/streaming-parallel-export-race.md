---
id: streaming-parallel-export-race
title: "StreamingExporter: paralleler FilePerTable-Export scheitert sporadisch mit ArrayIndexOutOfBounds"
status: resolved
---

# Paralleler FilePerTable-Export: sporadischer ArrayIndexOutOfBounds

> **Erledigt.** Der Wettlauf lag in der **Test-Attrappe**, nicht im
> `StreamingExporter`: `RecordingChunkWriterFactory` fuehrte ein gemeinsames
> `mutableListOf<String>()`, an das der parallele Pfad aus mehreren
> Writer-Threads gleichzeitig anhaengte. Zwei nebenlaeufige `add`-Aufrufe auf
> einer `ArrayList` schreiben in dieselbe Zelle und lassen `size` vorlaufen —
> das ergibt genau die beobachtete Meldung.
>
> Jeder Writer protokolliert jetzt in ein **eigenes** Log und haengt es beim
> `close()` unter Sperre an. Sequenziell ist die Reihenfolge unveraendert (ein
> Writer ist dort zu Ende, bevor der naechste anfaengt); parallel kann keiner
> mehr dem anderen dazwischenschreiben.
>
> **Der Produktionspfad war nicht betroffen** — nachgeprueft, nicht
> angenommen: `exportFilePerTableParallel` sammelt die Ergebnisse auf dem
> aufrufenden Thread **nach** `ParallelWorkExecutor.run`, der seinerseits die
> `Future`s in Einreichungsreihenfolge einsammelt. Die einzige Sammlung, die
> ein Worker wirklich anfasst, ist der `warningSink` der CLI-Verdrahtung —
> und der steht dort seit [`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007) auf
> `Collections.synchronizedList`.
>
> **Die Spezifikation prueft das jetzt selbst.** Sie faechert in acht
> Partitionskinder auf statt in zwei und zaehlt am Ende die Ereignisse:
> neun Writer, fuenf Eintraege je Writer. Mit der alten geteilten Liste faellt
> sie damit **jedes** Mal statt sporadisch — dreimal nachgefahren, dreimal rot.

## Befund

Im Voll-Lauf (2026-09-07) scheiterte

```
StreamingExporterTest > FilePerTable parallel fans out a partitioned parent
into one file per child (LN-008)
  java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0
```

Zweimal isoliert nachgefahren (`make docker-check
MODULES=":adapters:driven:streaming"`) lief er beide Male durch. Es war also
kein deterministischer Fehler, sondern ein **Wettlauf**.

Aufgefallen beim Slice zur Kanonisierung rohen SQL-Texts; der Test hat mit
dieser Aenderung nichts zu tun (anderes Modul, anderer Pfad).

## Warum es nicht als „flaky" abgehakt gehoerte

Der Name des Tests nennt den Pfad: **paralleler** Export, ein Kind je
Partition. Ein `ArrayIndexOutOfBoundsException` mit `length 0` deutet auf
eine geteilte, nicht synchronisierte Sammlung — die Frage war nur, auf
welcher Seite. Ein Exportlauf, der gelegentlich abbricht, waere fuer ein
Migrationswerkzeug ernster als ein wackliger Test; deshalb war die Antwort
nachzuweisen und nicht zu vermuten.

## Warum die Haeufigkeit taeuschte

Zwei Kinder plus eine Tabelle sind drei Einheiten bei `parallelism = 4` —
die Ueberschneidung ist kurz, und ein Einzel-Lauf trifft sie selten. Der
Voll-Lauf faehrt unter `org.gradle.parallel` mit anderer Maschinenlast und
traf sie. Acht Kinder machen daraus einen Fall, der ohne die Trennung
verlaesslich bricht.
