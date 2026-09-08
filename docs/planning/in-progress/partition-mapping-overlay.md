# `partition-mapping`-Overlay

> **Status:** In Arbeit seit 2026-09-08 (P0/P1 erledigt).
> **Ziel:** Eine dritte Overlay-Art, mit der ein Anwender Partitions-Identität
> beisteuert, die das Werkzeug nicht ableiten kann — Kindnamen, die ein Ziel
> nicht trägt, und LIST-Wertemengen, die als RANGE-Grenzen ausdrückbar sind.
> **Vorbedingungen:** keine offenen mehr — die Bindungsfrage ist mit
> [ADR 0050](../../adr/0050-overlay-bindung-uebergang-vs-darstellung.md)
> entschieden **und gebaut**: `MigrationOverlayBinding` mit
> `Transition`/`Representation`, `migration-overlay.v2`, v1-Dokumente
> unveraendert lesbar. Dieser Slice bringt nur noch die Overlay-**Art**
> `partition-mapping` mit ihrer Darstellungsbindung.
> **Stand:** P0-P6 geliefert; der Slice ist inhaltlich durch. Beide Faelle
> wirken auf allen drei Pfaden: `schema reverse` setzt die Kindnamen,
> `schema generate` macht aus LIST gueltiges RANGE, und `schema migrate`
> uebersetzt vor dem Vergleich, damit ein so erzeugtes Schema wieder
> migrierbar ist. P7 (Doku) ist mit den jeweiligen Paketen entstanden.

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

### P2 — Overlay-Art und Eintragsform ✅ geliefert (2026-09-08)
- `MigrationOverlayKinds.PARTITION_MAPPING` und ein
  `PartitionMappingOverlayEntry` mit beiden Eintragsarten: Kind ↔ Bezeichner
  des Ziels sowie Wertemenge ↔ Grenze.
- Kanonische JSON-Serialisierung samt `overlayHash`, wie bei den bestehenden
  Arten.
- **Abnahme:** Round-Trip-Test (schreiben → lesen → identischer Hash); ein
  Overlay mit unbekannter `kind` wird weiterhin abgelehnt, nicht ignoriert.

### P3 — Verifikation des LIST-Falls ✅ geliefert (2026-09-08)
- Prüfung: Wertemengen sortieren, auf Zusammenhang und
  Überschneidungsfreiheit prüfen, Grenzen daraus ableiten und mit den
  angegebenen vergleichen.
- Ablehnung mit benanntem Grund (eigener Diagnosecode, Ledger-Eintrag in der
  Datei der nächsten Version).
- **Abnahme:** `(1,2), (3,4)` → Grenzen `3, 5` akzeptiert; `('DE','FR'),
  ('US','CA')` abgelehnt; eine Zuordnung, die eine Zeile in die falsche
  Partition routen würde, abgelehnt. Property-Test über zufällige
  Mengenpartitionen: akzeptiert genau dann, wenn zusammenhängend.

### P4 — Die Diagnose nennt den Fingerabdruck ✅ geliefert (2026-09-08)
- `R346` trägt den Fingerabdruck des Schemas, an das ein Overlay zu binden
  wäre, samt der erwarteten Eintragsart.
- **`E055` bewusst noch nicht.** Der Sache nach gehört es dazu, aber der
  Generate-Pfad liest das Overlay erst mit P6 — ein Hinweis auf eine Datei,
  die kein Befehl entgegennimmt, wäre schlechter als keiner. Mit P6 fällt die
  Zurückhaltung.
- **Abnahme:** Der in der Meldung genannte Wert ist derselbe, den der
  Validator anschließend erwartet — belegt durch einen Test, der die Meldung
  parst und das daraus gebaute Overlay ohne weitere Angabe akzeptiert bekommt.

### P5 — Naht im Reverse ✅ geliefert (2026-09-08)
- `schema reverse` nimmt ein Overlay entgegen und setzt die Kindnamen daraus,
  statt `p1`, `p2`, … zu vergeben. Gebunden wird an den Zustand **vor** der
  Anwendung (ADR 0050).
- `R346` verstummt für die Kinder, die das Overlay benennt, und bleibt für
  die übrigen.
- Beim Bauen kam heraus, dass der Hinweis allein nicht reicht: der Validator
  verlangt `overlayHash`, und wer das Overlay von Hand schreibt, kann ihn nicht
  ausrechnen — die Ablehnung nannte den Wert nicht. Damit war die Datei nicht
  abzugeben, und der Hinweis aus P4 zeigte auf einen Weg, der am Schluss
  verschlossen war. `OVERLAY_HASH_MISSING`/`OVERLAY_HASH_MISMATCH` nennen den
  kanonischen Abdruck jetzt; er ist eine Inhaltssumme, kein Geheimnis.
- **Abnahme:** Live gegen echtes SQL Server — partitionierte Tabelle lesen,
  einmal ohne und einmal mit Overlay, Namen in der Ausgabe belegt. Der
  Handbuch-Weg (ohne Abdruck schreiben, Wert aus der Ablehnung übernehmen,
  erneut aufrufen) einmal ganz durchlaufen.

### P6a — Naht im Generate ✅ geliefert (2026-09-08)
- `schema generate --target mssql --migration-overlay` erzeugt für ein
  LIST-Schema gültiges RANGE-DDL statt `E055`; ohne Overlay nennt `W157` den
  Abdruck, an den eines zu binden wäre.
- **Übersetzt wird das Schema, nicht das Statement.** Das war die
  Schnittentscheidung des Pakets: eine Übersetzung im Renderer hätte dieselbe
  Rechnung an drei Stellen gebraucht (Generate, Diff, Post-Compare) und einen
  Vergleich hinterlassen, der weiter LIST gegen RANGE hält. So sieht jede
  Projektion dieselbe Form, und `DialectCapabilities.supportsListPartitioning`
  entscheidet, wo sie überhaupt greift.
- **Die Weitung steht im Modell.** Aus n Wertemengen werden n Grenzen und n+1
  Partitionen; die zusätzliche oberhalb der letzten Grenze existiert nach dem
  Anlegen, also führt das übersetzte Schema sie mit — sonst beschriebe es eine
  Partition weniger, als da ist, und der nächste Vergleich fände eine
  Abweichung, die niemand gemacht hat. `W156` sagt es.
- **Abnahme:** Live gegen SQL Server 2022 — erzeugtes DDL ausgeführt, dann den
  Server selbst gefragt (`$PARTITION`), wohin die Werte routen: die Mengen
  landen in ihren Partitionen, die Grenzwerte `3`/`7` auf der rechten Seite
  (`RANGE RIGHT`), und `9` in der Auffang-Partition. Sabotage-geprüft über die
  Grenzrichtung und über eine weggelassene Grenze.

### P6b — Naht im Diff/Migrate ✅ geliefert (2026-09-08)
- Dieselbe Übersetzung, nur früher: das SOLL-Schema wird **vor** dem Vergleich
  übersetzt. Der Abdruck, an den das Overlay bindet, ist der des
  **unübersetzten** Schemas (ADR 0050); der Plan hält fest, was danach auf dem
  Ziel steht — deshalb werden die Abdrücke nach einer Übersetzung neu gerechnet
  und nicht weitergereicht.
- Eine Zuordnung, die nicht trägt, geht als Befund durch denselben Kanal wie
  ein unlesbares Overlay (`MigrationOverlayLoadFailure`), nicht über einen
  eigenen Ausgang. Zwei Arten, dasselbe zu melden, wären eine zu viel.
- **Der erwartete Schaden war ein anderer als der gemessene.** Dieses Paket
  ging von Drop/Create-Paaren aus. Gemessen gegen echtes SQL Server: der Planer
  emittiert für eine Strategieänderung **gar keine Operation**, sondern die
  Warnung `PARTITIONING_CHANGE_NOT_APPLIED` — und die bleibt bei jedem Lauf
  stehen und rät, eine Tabelle von Hand neu zu bauen, die in Wahrheit genau
  richtig ist. Der Schaden ist damit kein wiederholter Umbau, sondern ein
  dauerhaft falscher Rat.
- **Abnahme:** Live gegen SQL Server 2022, Tabelle angelegt wie der
  Generate-Pfad sie schreibt. Mit Overlay: 0 Operationen, keine Warnung. Ohne:
  0 Operationen, Warnung. Sabotage-geprüft, indem die Naht stillgelegt wurde —
  die Warnung kam zurück.

### P7 — CLI und Doku ✅ geliefert (2026-09-08)
- Overlay-Weg an `schema reverse`, `schema generate` und `schema migrate`.
- Handbuch **erst hier** — dort darf nur stehen, was wirkt. Das hat den Schnitt
  verschoben statt ihn zu verzögern: die Doku entstand mit dem jeweiligen
  Paket, weil sie erst dort wahr wurde (Reverse mit P5, Generate mit P6a).
- **Abnahme:** Aufgabenorientierte Abschnitte („Ihre Partitionsnamen gehen beim
  Auslesen verloren", „Ihre LIST-Partitionierung kennt das Ziel nicht", samt
  dem Hinweis, dieselbe Datei beim Migrieren mitzugeben); Feldreferenz im
  Anhang; `make docs-check` grün.

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
