# `partition-mapping`-Overlay

> **Status:** Draft mit Scope (28.08.2026)
> **Ziel:** Eine dritte Overlay-Art, mit der ein Anwender Partitions-Identität
> beisteuert, die das Werkzeug nicht ableiten kann — Kindnamen, die ein Ziel
> nicht trägt, und LIST-Wertemengen, die als RANGE-Grenzen ausdrückbar sind.
> **Vorbedingungen:** keine offenen mehr — die Bindungsfrage ist mit
> [ADR 0050](../../adr/0050-overlay-bindung-uebergang-vs-darstellung.md)
> entschieden.
> **Aktivierung:** Beim ersten Implementierungs-Commit → `../in-progress/`.

Absorbiert die Vorabklärung `open/partition-mapping-overlay.md`.

## 1. Ausgangslage

Zwei Verluste derselben Gestalt: **die Identität ist bekannt, aber nicht
ableitbar** — sie liegt beim Anwender, nicht in der Datenbank.

- **Kindnamen.** PostgreSQL und MySQL benennen Partitionen, SQL Server
  nummeriert sie. Ein Reverse kann `p_2024` nicht zurückgeben; er vergibt
  `p1`, `p2`, … und meldet das mit `R346`
  ([`MssqlSchemaReader.kt`](../../../adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlSchemaReader.kt)).
- **`LIST` → `RANGE`.** SQL Server kennt nur RANGE; `list` und `hash` brechen
  mit `E055` ab
  ([`MssqlDdlGenerator.kt`](../../../adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlDdlGenerator.kt)).
  Eine LIST-Partitionierung ist genau dann als RANGE ausdrückbar, wenn die
  Wertemengen in Sortierreihenfolge zusammenhängend und überschneidungsfrei
  sind: `(1,2), (3,4)` wird zu den Grenzen `3, 5`. Bei `('DE','FR'),
  ('US','CA')` geht es nicht — die Mengen verschränken sich in jeder Ordnung.

Der LIST-Fall ist der wertvollere, weil die Zuordnung **verifizierbar** ist:
sortieren, auf Zusammenhang und Überschneidungsfreiheit prüfen, bei
Verschränkung mit benanntem Grund ablehnen. Eine Zuordnung, die falsches
Routing erzeugte, käme nicht durch. Das kann ein Namens-Mapping nicht leisten.

## 2. Warum ein Overlay und kein Konfigurationsschalter

[`MigrationOverlay`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/overlay/MigrationOverlay.kt)
führt bereits `using-expression` und `rename-mapping`. Beide lösen dieselbe
Lage. `partition-mapping` wäre die dritte Art derselben Sorte.

Der Unterschied zu einem Schalter ist der entscheidende: **ein Overlay stellt
Identität her, es lockert keine Gleichheit.** `schema compare` bliebe streng
und erführe nur, dass Partition 1 des Ziels dieselbe ist wie `p_2024` der
Quelle. Die Grenze aus
[ADR 0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md) bliebe
unangetastet. Ein Schalter, der entscheidet, *ob* zwei Partitionssätze gleich
sind, wäre das Gegenteil: dieselbe Migration wäre je nach Datei sauber oder
driftend, und der Fingerabdruck im Rollback-Artefakt hinge an einer
Einstellung statt am Schema.

## 3. Die Bindung — entschieden

Die Vorabklärung nahm an, der CLI-Weg sei „wie bei `rename-mapping`". Er ist
es nicht. `MigrationOverlay` trägt `sourceFingerprint` **und**
`targetFingerprint`, gilt also für ein Schema*paar*, und ist nur an
`schema migrate` verdrahtet
([`SchemaMigrateCommand.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateCommand.kt)).
Beide Partitionsfälle entstehen außerhalb: Kindnamen im Reverse, LIST→RANGE im
Generate.

Naheliegend wäre gewesen, den Vertrag den Befehlen anzupassen. Das ist die
falsche Reihenfolge. Maßgeblich ist, **wovon der Inhalt eines Overlays
abhängt** — und da fällt die Antwort je Art verschieden aus: `using-expression`
und `rename-mapping` sind Aussagen über *zwei* Zustände und ohne beide sinnlos.
`partition-mapping` ist eine Aussage darüber, wie ein Sachverhalt in einem
Dialekt *dargestellt* wird; der IST-Zustand einer laufenden Datenbank ist dafür
belanglos — auch innerhalb eines `schema migrate`, denn die LIST-Partitionierung
steht im SOLL-Schema.

[ADR 0050](../../adr/0050-overlay-bindung-uebergang-vs-darstellung.md) hält
daraus fest: eine sealed Bindung, `Transition` (Paar) für die bestehenden
Arten, `Representation` (ein Schema) für `partition-mapping`, Format
`migration-overlay.v2` mit v1-Dokumenten als `Transition`.

Damit ist auch die zweite Frage beantwortet: **`schema generate` konsumiert
das Overlay** — nicht als Zugeständnis, sondern weil die Bindung nie ein Paar
brauchte. `E055` für `list` wird zu einem auflösbaren Abbruch statt einer
Sackgasse.

Zwei Folgerungen, die der ADR benennt und die den Schnitt unten prägen: die
Diagnose muss den Fingerabdruck nennen, an den zu binden ist (kein Befehl gibt
ihn heute aus), und ein Reverse-Overlay bindet an den Zustand **vor** seiner
Anwendung, weil Kindnamen im Fingerabdruck stehen und die Kinder nach Namen
sortiert werden.

## 4. Arbeitspakete

### P0 — Bindung ✅ entschieden
[ADR 0050](../../adr/0050-overlay-bindung-uebergang-vs-darstellung.md),
`accepted`.

### P1 — Sealed Bindung im Format
- `MigrationOverlayBinding` mit `Transition`/`Representation`; `formatVersion`
  auf `migration-overlay.v2`; v1-Dokumente lesen sich flach als `Transition`.
- Validator und Preflight prüfen die je `overlayKind` verlangte Bindungsart;
  die falsche ist ein Blocker, kein Hinweis.
- **Abnahme:** bestehende v1-Overlays validieren unverändert (Regressionstest
  über die vorhandenen Fixtures); ein `partition-mapping` mit Paarbindung wird
  abgelehnt; ein `rename-mapping` mit Einschema-Bindung ebenso.

### P2 — Overlay-Art und Eintragsform
- `MigrationOverlayKinds.PARTITION_MAPPING` und ein
  `PartitionMappingOverlayEntry` mit beiden Eintragsarten: Kind ↔ Bezeichner
  des Ziels sowie Wertemenge ↔ Grenze.
- Kanonische JSON-Serialisierung samt `overlayHash`, wie bei den bestehenden
  Arten.
- **Abnahme:** Round-Trip-Test (schreiben → lesen → identischer Hash); ein
  Overlay mit unbekannter `kind` wird weiterhin abgelehnt, nicht ignoriert.

### P3 — Verifikation des LIST-Falls
- Prüfung: Wertemengen sortieren, auf Zusammenhang und
  Überschneidungsfreiheit prüfen, Grenzen daraus ableiten und mit den
  angegebenen vergleichen.
- Ablehnung mit benanntem Grund (eigener Diagnosecode, Ledger-Eintrag in der
  Datei der nächsten Version).
- **Abnahme:** `(1,2), (3,4)` → Grenzen `3, 5` akzeptiert; `('DE','FR'),
  ('US','CA')` abgelehnt; eine Zuordnung, die eine Zeile in die falsche
  Partition routen würde, abgelehnt. Property-Test über zufällige
  Mengenpartitionen: akzeptiert genau dann, wenn zusammenhängend.

### P4 — Die Diagnose nennt den Fingerabdruck
- `R346` und `E055` tragen den Fingerabdruck des Schemas, an das ein Overlay
  zu binden wäre, samt der erwarteten Eintragsart.
- **Abnahme:** Der in der Meldung genannte Wert ist derselbe, den der
  Validator anschließend erwartet — belegt durch einen Test, der die Meldung
  parst und das daraus gebaute Overlay ohne weitere Angabe akzeptiert bekommt.

### P5 — Naht im Reverse
- `schema reverse` nimmt ein Overlay entgegen und setzt die Kindnamen daraus,
  statt `p1`, `p2`, … zu vergeben. Gebunden wird an den Zustand **vor** der
  Anwendung (ADR 0050).
- `R346` verstummt für die Kinder, die das Overlay benennt, und bleibt für
  die übrigen.
- **Abnahme:** Live gegen echtes SQL Server — partitionierte Tabelle lesen,
  einmal ohne und einmal mit Overlay, Namen in der Ausgabe belegt.

### P6 — Naht im Diff/Migrate/Generate
- Der Planer nutzt die Zuordnung für Identität; bei einer Zuordnung, die der
  Preflight ablehnt, entsteht ein Blocker, kein stiller Fallback.
- **Abnahme:** Ein `schema migrate --plan-only` mit gültigem Overlay erzeugt
  keine Drop/Create-Paare für Partitionen, die einander entsprechen; mit
  ungültigem Overlay bricht es mit benanntem Blocker ab. `schema generate
  --target mssql` erzeugt für ein LIST-Schema mit Overlay gültiges RANGE-DDL
  statt `E055`.

### P7 — CLI und Doku
- Overlay-Weg an `schema reverse`, `schema generate` und `schema migrate`.
- Handbuch **erst hier** — dort darf nur stehen, was wirkt.
- **Abnahme:** Aufgabenorientierter Abschnitt („Ihre Partitionsnamen gehen
  beim Reverse verloren → …"); Feldreferenz im Anhang; `make docs-check` grün.

## 5. Nicht-Scope

- **HASH.** Eine HASH-Partitionierung auf RANGE abzubilden verlangt, die
  Hash-Funktion des Quellsystems nachzubilden; das ist kein Mapping, sondern
  eine Emulation, und gehört zum Sub-Slice 7d
  ([`mssql-dialect-scoping.md`](../done/mssql-dialect-scoping.md)).
- **Automatische Herleitung.** Wenn das Werkzeug die Zuordnung selbst raten
  könnte, bräuchte es kein Overlay. Ein Vorschlagsmodus („so könnte die
  Zuordnung aussehen") ist denkbar, aber nicht Teil dieses Plans.
- **Lockerung des Vergleichs.** Siehe Abschnitt 2.

## 6. Reichweite

Nicht MSSQL-spezifisch, auch wenn der Auslöser dort lag. Der Namensfall trifft
jedes Ziel, das Partitionen anders identifiziert als die Quelle; der LIST-Fall
jedes, das LIST nicht kennt. Der Plan schneidet die Arten deshalb neutral und
belegt sie zuerst an SQL Server, weil dort beide Fälle zugleich auftreten.
