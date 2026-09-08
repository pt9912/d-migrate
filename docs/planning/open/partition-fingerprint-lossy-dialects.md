---
id: partition-fingerprint-lossy-dialects
title: "MySQL und SQL Server verlieren dieselben Partitionsangaben wie Oracle, ohne sie im Fingerabdruck auszublenden"
status: refuted
---

# Partitions-Fingerabdruck: MySQL und SQL Server projizieren nicht

> **Widerlegt — nicht umgesetzt.** Der Befund unten fragt, was der *Server*
> ablegt. Fuer den Vergleich zaehlt aber, was sein *Reverse* zurueckgibt, und
> beide Leser **rekonstruieren** die fehlenden Angaben:
>
> - `MysqlPartitionReader.reconstructNeutralBounds`: RANGE-`from` aus der
>   Kontiguitaet (`fromₙ = toₙ₋₁`, erstes `from = MINVALUE`), HASH
>   `modulus = n` und `remainder = Ordinalindex` aus `PARTITIONS n`.
> - `MssqlSchemaReader`: `from = if (i == 0) MinValue else bounds[i - 1]` —
>   dieselbe Rekonstruktion.
> - `TableComparator` normalisiert zusaetzlich selbst
>   (`PartitionBoundNormalizer.withDerivedLowerBounds`).
>
> `carriesPartitionLowerBounds` und `carriesPartitionHashModulus` bleiben
> deshalb fuer beide Dialekte auf `true`. Sie wegzuprojizieren wuerde einen
> **echten** Verlust verstecken: eine Luecke zwischen RANGE-Partitionen laesst
> sich in MySQL und SQL Server gar nicht abbilden, der Zielbestand ist dann
> ein anderer, und der Generate-Pfad warnt davor (`W112`).
>
> Die Quelle des Irrtums stand im Code: der KDoc von
> `carriesPartitionLowerBounds` behauptete, MySQLs Reverse koenne die untere
> Grenze „deshalb nicht zurueckmelden". Der Satz ist korrigiert.
>
> **Was bleibt**, ist ein anderer und groesserer Befund bei SQL Server:
> eine emulierte HASH-Partitionierung kommt als RANGE zurueck, mit anderem
> Schluessel und einer zusaetzlichen Spalte. Das kann keine Feld-Projektion
> heilen — eigenes Ticket:
> [`mssql-hash-emulation-not-round-trippable.md`](../done/mssql-hash-emulation-not-round-trippable.md).
>
> Die dritte Frage unten (Artefakt-Kompatibilitaet) ist ebenfalls beantwortet:
> der Rollback prueft `fingerprintAlgorithm` und meldet
> `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` benannt.

## Befund

Slice 7 hat fuer Oracle zwei Faehigkeiten eingefuehrt,
`carriesPartitionLowerBounds` und `carriesPartitionHashModulus`
(`DialectCapabilities`), und `capabilityPartitionCanonicalizer` blendet die
betroffenen Felder im Fingerabdruck aus. Ohne das meldete der Post-Compare
nach jedem `migrate --execute` Drift fuer eine Migration, die genau das
getan hat, was verlangt war.

**Dieselbe Lage besteht bei MySQL und SQL Server**, und dort ist sie nicht
geschlossen:

- **MySQL** kennt bei RANGE nur `VALUES LESS THAN` (der Generator meldet den
  Verlust der unteren Grenze selbst mit `W112`), und MySQL-HASH fuehrt weder
  Modulus noch Remainder.
- **SQL Server** bildet Partitionierung ueber Function + Scheme ab; aus n
  Partitionen werden n−1 Grenzwerte, und HASH wird ueber eine berechnete
  Spalte emuliert.

Beide stehen deshalb weiterhin auf dem Default `true` — also „der Server
fuehrt das" —, obwohl er es nicht tut.

## Warum es nicht im Oracle-Slice mitgemacht wurde

Die Fingerabdruecke von MySQL und SQL Server zu aendern **entwertet bereits
erzeugte Rollback-Artefakte**: ein Artefakt traegt den `postUpFingerprint`,
gegen den `schema rollback` den Ist-Zustand prueft. Aendert sich die
Projektion, passt der gespeicherte Abdruck nicht mehr.

Das ist dieselbe Erwaegung, aus der `namesIdentitySequences` fuer
PostgreSQL auf `true` blieb (siehe
[`pg-identity-sequence-name-fingerprint.md`](../done/pg-identity-sequence-name-fingerprint.md)) —
eine eigene Entscheidung mit eigener Migrationsfrage, kein Beifang eines
Dialekt-Rollouts.

## Zu klaeren

1. Ob die Umstellung eine Fingerprint-Versionsanhebung braucht (der
   Fingerabdruck ist versioniert — `MigrationFingerprint` fuehrt v4/v5/v7).
2. Ob alte Artefakte weiterhin gegen die alte Projektion geprueft werden
   sollen (Version im Artefakt entscheidet) oder ob ein Bruch akzeptabel ist.
3. Ob SQL Server ueberhaupt eine untere Grenze zurueckmelden kann — das ist
   nicht gemessen, sondern aus `spec/ddl-generation-rules.md` Abschnitt 9.4
   geschlossen.
