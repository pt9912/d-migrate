# `partition-mapping`-Overlay

> **Status:** Draft mit Scope (28.08.2026)
> **Ziel:** Eine dritte Overlay-Art, mit der ein Anwender Partitions-Identität
> beisteuert, die das Werkzeug nicht ableiten kann — Kindnamen, die ein Ziel
> nicht trägt, und LIST-Wertemengen, die als RANGE-Grenzen ausdrückbar sind.
> **Vorbedingungen:** Entscheidung E1 aus Abschnitt 3 (Bindung des Dokuments)
> muss vor P1 fallen; sie bestimmt, ob ein ADR nötig wird.
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

## 3. Die Naht, die es noch nicht gibt

**Das ist die Entscheidung, die vor jeder Codezeile fällt.** Die
Vorabklärung nahm an, der CLI-Weg sei „wie bei `rename-mapping`". Er ist es
nicht, und der Unterschied ist strukturell:

- `MigrationOverlay` trägt `sourceFingerprint` **und** `targetFingerprint`.
  Das Dokument gilt für ein Schema**paar**, nicht für ein Schema.
- `--migration-overlay` gibt es ausschließlich an `schema migrate`
  ([`SchemaMigrateCommand.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateCommand.kt)).
  Weder `schema reverse` noch `schema generate` kennen Overlays.

Beide Partitionsfälle treten **außerhalb** dieser Naht auf: Kindnamen bei
`schema reverse` (eine Datenbank, kein Paar), LIST→RANGE bei `schema generate`
(eine Datei, kein Zielschema). Nur der LIST-Fall *innerhalb* eines
`schema migrate` passt heute schon.

### Entscheidung E1: Bindung des Dokuments

| Option | Bindung | Kosten | Risiko |
| ------ | ------- | ------ | ------ |
| **A** — Paarbindung bleibt Pflicht | Nur `schema migrate` bekommt `partition-mapping`; Reverse und Generate bleiben außen vor | Am wenigsten Arbeit, kein Formatbruch | Löst den Kindnamen-Fall **gar nicht** — der entsteht im Reverse |
| **B** — Einschema-Bindung als zweite Dokumentform | `targetFingerprint` entfällt für Overlays, die auf ein einzelnes Schema wirken | Formatänderung (`formatVersion`), Preflight muss beide Formen prüfen | Zwei Dokumentformen, die man verwechseln kann |
| **C** — Reverse-Overlay bindet an die gelesene Datenbank | `sourceFingerprint` = Fingerabdruck des gelesenen Schemas, `targetFingerprint` = derselbe | Kein Formatbruch, aber semantisch schief | Ein Feld trägt eine Bedeutung, die sein Name nicht hergibt |

**Empfehlung: B**, und zwar bewusst gegen die billigere Variante. C erkauft
sich die Vermeidung des Formatbruchs damit, dass `targetFingerprint` etwas
anderes bedeutet als sein Name sagt — genau die Art stiller Doppelbedeutung,
die später niemand mehr auflöst. A ist ehrlich, liefert aber nur die Hälfte;
falls E1 auf A fällt, entfällt P3 und der Kindnamen-Fall braucht ein eigenes
Ticket statt eines stillen Rests in diesem Plan.

B verlangt einen ADR: die Dokumentform ist ein Vertrag mit Artefakt-Wirkung
(`overlayHash`, `createdByVersion`), und eine zweite Form davon ist keine
Implementierungsentscheidung.

### Entscheidung E2: Wirkt das Overlay auf `schema generate`?

Der LIST-Fall bricht heute im Generate mit `E055` ab — dort gibt es kein
Zielschema und keinen Fingerabdruck, an den ein Dokument binden könnte.
Entweder bekommt Generate eine eigene, schwächer gebundene Form (dann ist
die Verifikation aus P2 die einzige Sicherung), oder der Fall bleibt auf
`schema migrate` beschränkt und Generate meldet weiter `E055`. **Vorschlag:
zunächst beschränken** — der Plan liefert dann keinen Weg für die reine
DDL-Erzeugung, und das gehört so gesagt, nicht stillschweigend gelassen.

## 4. Arbeitspakete

### P0 — Entscheidungen und ADR
- E1 und E2 entschieden und begründet.
- Bei E1 = B: ADR geschrieben und angenommen (Dokumentform, Migrationspfad
  für bestehende Overlays, Verhalten des Preflights bei der jeweils anderen
  Form).
- **Abnahme:** ADR-Status `accepted`; `make docs-check` grün.

### P1 — Overlay-Art und Eintragsform
- `MigrationOverlayKinds.PARTITION_MAPPING` und ein
  `PartitionMappingOverlayEntry` mit beiden Eintragsarten: Kind ↔ Bezeichner
  des Ziels sowie Wertemenge ↔ Grenze.
- Kanonische JSON-Serialisierung samt `overlayHash`, wie bei den bestehenden
  Arten.
- **Abnahme:** Round-Trip-Test (schreiben → lesen → identischer Hash); ein
  Overlay mit unbekannter `kind` wird weiterhin abgelehnt, nicht ignoriert.

### P2 — Verifikation des LIST-Falls
- Prüfung: Wertemengen sortieren, auf Zusammenhang und
  Überschneidungsfreiheit prüfen, Grenzen daraus ableiten und mit den
  angegebenen vergleichen.
- Ablehnung mit benanntem Grund (eigener Diagnosecode, Ledger-Eintrag in der
  Datei der nächsten Version).
- **Abnahme:** `(1,2), (3,4)` → Grenzen `3, 5` akzeptiert; `('DE','FR'),
  ('US','CA')` abgelehnt; eine Zuordnung, die eine Zeile in die falsche
  Partition routen würde, abgelehnt. Property-Test über zufällige
  Mengenpartitionen: akzeptiert genau dann, wenn zusammenhängend.

### P3 — Naht im Reverse (entfällt bei E1 = A)
- `schema reverse` nimmt ein Overlay entgegen und setzt die Kindnamen daraus,
  statt `p1`, `p2`, … zu vergeben.
- `R346` verstummt für die Kinder, die das Overlay benennt, und bleibt für
  die übrigen.
- **Abnahme:** Live gegen echtes SQL Server — partitionierte Tabelle lesen,
  einmal ohne und einmal mit Overlay, Namen in der Ausgabe belegt.

### P4 — Naht im Diff/Migrate
- Der Planer nutzt die Zuordnung für Identität; bei einer Zuordnung, die der
  Preflight ablehnt, entsteht ein Blocker, kein stiller Fallback.
- **Abnahme:** Ein `schema migrate --plan-only` mit gültigem Overlay erzeugt
  keine Drop/Create-Paare für Partitionen, die einander entsprechen; mit
  ungültigem Overlay bricht es mit benanntem Blocker ab.

### P5 — CLI und Doku
- Overlay-Weg an den betroffenen Befehlen (welche, entscheidet E1/E2).
- Handbuch **erst hier** — dort darf nur stehen, was wirkt.
- **Abnahme:** Aufgabenorientierter Abschnitt („Ihre Partitionsnamen gehen
  beim Reverse verloren → …"); Feldreferenz im Anhang; `make docs-check` grün.

## 5. Nicht-Scope

- **HASH.** Eine HASH-Partitionierung auf RANGE abzubilden verlangt, die
  Hash-Funktion des Quellsystems nachzubilden; das ist kein Mapping, sondern
  eine Emulation, und gehört zum Sub-Slice 7d
  ([`mssql-dialect-scoping.md`](../in-progress/mssql-dialect-scoping.md)).
- **Automatische Herleitung.** Wenn das Werkzeug die Zuordnung selbst raten
  könnte, bräuchte es kein Overlay. Ein Vorschlagsmodus („so könnte die
  Zuordnung aussehen") ist denkbar, aber nicht Teil dieses Plans.
- **Lockerung des Vergleichs.** Siehe Abschnitt 2.

## 6. Reichweite

Nicht MSSQL-spezifisch, auch wenn der Auslöser dort lag. Der Namensfall trifft
jedes Ziel, das Partitionen anders identifiziert als die Quelle; der LIST-Fall
jedes, das LIST nicht kennt. Der Plan schneidet die Arten deshalb neutral und
belegt sie zuerst an SQL Server, weil dort beide Fälle zugleich auftreten.
