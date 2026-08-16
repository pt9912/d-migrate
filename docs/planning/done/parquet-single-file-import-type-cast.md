# Tracker: Parquet-Bundle-Mitglied im Einzeldatei-Import bricht mit ClassCastException ab

> **BEHOBEN 2026-08-16** (`a01c4f07`, Weg 1). `buildSchemaFromFooter` leitet die
> Typen jetzt aus dem Footer ab statt aus einem `Text`-Platzhalter; der Wächter
> `verifyFooterMatchesSchema` vergleicht zusätzlich die Lese-Zugriffsform, sodass
> ein Schema-Drift als `BUNDLE_SCHEMA_PARQUET_MISMATCH` auffällt statt als roher
> Cast. Ende-zu-Ende über die gebaute CLI belegt: der brechende Aufruf liefert
> 500 Zeilen mit korrekten Prüfsummen, und alle vier übrigen Import-Formen
> bleiben grün.
>
> **Status:** Befund mit Repro und eingegrenzter Ursache (2026-08-16)
> **Trigger:** Beim funktionalen Nachweis der Hadoop-Ausschlüsse
> ([dependency-cve-exposure-shipped-artifact.md](../open/dependency-cve-exposure-shipped-artifact.md))
> als Nebenbefund aufgefallen. **Nicht** von jenem Eingriff verursacht — zwei
> Kontrollläufe gegen das unveränderte `pt9912/d-migrate:1.0.0` scheitern identisch.
> **Aktivierungsbedingung** (Move nach `../next/`): Entscheidung zwischen den Wegen
> unten — Typen aus dem Footer ableiten, aus dem Ziel-JDBC-Schema nachziehen, oder
> den Fall verständlich ablehnen.

## Symptom

```
data import --format parquet --source /w/out/t.parquet --table t

Error: Import failed: class org.apache.parquet.example.data.simple.IntegerValue
       cannot be cast to class org.apache.parquet.example.data.simple.BinaryValue
```

Kein Datensatz wird geschrieben; die Zieltabelle bleibt leer.

## Eingrenzung (2026-08-16, alles gemessen)

Der ursprüngliche Titel „Einzeldatei-Import bricht ab" war **zu breit**. Gemessen
gilt:

| Export | Import | Ergebnis |
| --- | --- | --- |
| Einzeldatei (`-o datei.parquet`) | dieselbe Datei | **läuft**, 500 Zeilen |
| Bundle (`--split-files`) | Mitglied daraus, einzeln | **ClassCastException**, 0 Zeilen |
| Bundle | ganzes Verzeichnis | **läuft**, 500 Zeilen |

- **Parquet-spezifisch.** Dieselbe Übung mit `--format csv` und `--format json`
  läuft in beiden Fällen durch (je 500 Zeilen). Es ist kein allgemeiner Defekt des
  Einzeldatei-Pfads.
- **Ziel-unabhängig.** Gegen SQLite **und** gegen PostgreSQL 17 identisch: Bundle-
  Mitglied bricht ab, Einzeldatei-Export läuft (je 500 Zeilen).

## Ursache

Die Kette ist vollständig belegt:

1. **Bundle- und Einzeldatei-Export legen das Schema an verschiedene Orte.** Der
   Einzeldatei-Export bettet ein Manifest als Footer-Key `d-migrate.manifest` ein
   (nachweisbar an der Dateigröße: 3719 statt 3283 Bytes für dieselben 500 Zeilen);
   `--split-files` schreibt stattdessen eine separate `manifest.yaml` und lässt den
   Footer-Key **weg**.
2. Ohne Footer-Key greift der Fallback
   [`ParquetSingleFilePreflight.buildSchemaFromFooter`](../../../adapters/driven/formats-parquet/src/main/kotlin/dev/dmigrate/format/parquet/ParquetSingleFilePreflight.kt).
   Er füllt **jede** Spalte mit `NeutralType.Text()`. Das ist kein Versehen, sondern
   ein bewusster Platzhalter — der Kommentar dort sagt es:

   > „Footer-Fallback ohne Manifest: keine NeutralType-Aufloesung — Cut A fuellt mit
   > Text als Marker. Der CLI-Resolver / Phase-2 darf das ueber das Target-
   > JDBC-Schema verbessern (AP11 §5.3, **kommt mit S6**)."

3. **S6 wurde nie gebaut.** `phase2` reicht das Ergebnis unverändert durch
   („Heute (S4): nur Hash-Konsistenz-Check … CLI-Wiring (S6) erweitert ihn bei
   Bedarf"), `markS4FallbackUsed` ist eine **No-op-Funktion** (`Unit = Unit` mit
   `@Suppress`), und `manifestPresent` wird zwar bis in `ImportInput` durchgereicht,
   aber **nirgends gelesen** — die in der KDoc versprochene CLI-Warnung existiert
   nicht.
4. Der vorhandene Wächter greift nicht: `verifyFooterMatchesSchema` vergleicht
   ausschließlich **Spaltenanzahl und -namen**, nie die Typen. Ein Schema mit
   richtigen Namen und durchgehend falschen Typen passiert ihn unbeanstandet.
5. `ParquetGroupValueReader.readColumn` dispatcht auf den gelieferten `NeutralType`
   und ruft für `Text` `group.getString(...)` auf — auf einer INT32-Spalte. Das ist
   der Cast.

**Kurz:** Ein als temporär markierter Platzhalter ist produktiv geworden, weil der
Schritt, der ihn ersetzen sollte, nie kam. Der Fehler tritt dadurch als roher
Bibliotheks-Cast auf statt als Diagnose.

## Testlage — warum es ausgeliefert wurde

| Fall | Abdeckung |
| --- | --- |
| Einzeldatei -> Einzeldatei | ja (`ParquetSingleFileRoundTripTest`, `DataParquetRoundTripE2EPostgresTest`) |
| Bundle -> Verzeichnis | ja (e2e „S7e Bundle-Roundtrip" gegen echtes PostgreSQL) |
| **Bundle-Mitglied -> einzeln** | **keine** |

Zwei Punkte wiegen schwerer als die bloße Lücke:

1. **Kein Test liest Zeilen durch ein `MANIFEST_FALLBACK`-Schema.** Die Suche liefert
   null Treffer. Der Fallback wird ausschließlich an der Preflight-Grenze geprüft —
   also genau vor der Stelle, an der er bricht.
2. **Der eine Test, der ihn anfasst, schreibt den Defekt fest.**
   [`ParquetSingleFileRoundTripTest`](../../../adapters/driven/formats-parquet/src/test/kotlin/dev/dmigrate/format/parquet/ParquetSingleFileRoundTripTest.kt)
   legt eine Datei mit `id` als `NeutralType.Integer` an und behauptet danach:

   ```kotlin
   phase1.schema.columns.all { it.neutralType is NeutralType.Text } shouldBe true
   ```

   Das ist grün, weil es den **Platzhalter** beschreibt statt des Verhaltens. Wer S6
   gebaut hätte, wäre über einen roten Test gestolpert, der die Krücke einfordert.

**Folge für den Fix:** Ein Repro-Test für den Bundle-Mitglied-Fall gehört dazu, und
der bestehende Fallback-Test ist umzuschreiben — von „alle Spalten sind Text" zu
„die Typen entsprechen dem Footer" (Weg 1) bzw. „der Aufruf wird verständlich
abgelehnt" (Weg 3).

## Wege

> **Gewählt und umgesetzt: Weg 1**, plus der Typvergleich im Wächter. Weg 2 (S6
> nachbauen) ist damit gegenstandslos und die entsprechende Zusage in der KDoc
> entfernt. Weg 3 ist in abgeschwächter Form enthalten: Ein Drift führt jetzt zu
> einer benannten Ausnahme mit Spaltennamen statt zu einer `ClassCastException` —
> eine eigene Vorab-Ablehnung im CLI braucht es dafür nicht mehr.

1. **Typen aus dem Footer ableiten.** Die Parquet-Datei trägt ihr physisches und
   logisches Schema selbst; `buildSchemaFromFooter` liest es bereits (für Namen und
   Nullability) und wirft die Typinformation weg. Das ist die eigentliche Auflösung
   und macht den Fall unabhängig von Manifest und Ziel.
2. **S6 nachbauen** — Typen im `phase2` aus dem Ziel-JDBC-Schema nachziehen. Der
   ursprünglich geplante Weg; schwächer als 1, weil er ein existierendes Ziel
   voraussetzt und bei Spaltenreihenfolge-Abweichung erneut still danebenliegt.
3. **Verständlich ablehnen.** Das Minimum: Wenn `manifestPresent == false` und die
   Typen nicht aufgelöst werden können, mit klarer Meldung abbrechen statt eine
   `ClassCastException` durchzureichen — samt Hinweis auf den Verzeichnis-Import.

Weg 1 ist die Auflösung, Weg 3 das Minimum, und **Weg 3 sollte in jedem Fall
kommen**: Auch mit Weg 1 bleibt der Wächter typblind, und der nächste Platzhalter
fiele wieder als roher Cast auf.

**Zusätzlich, unabhängig vom gewählten Weg:** `verifyFooterMatchesSchema` um einen
Typvergleich erweitern. Er ist die Stelle, die den Fehler hätte fangen sollen.

## Verwandt — dieselbe Bewegung könnte drei Dinge lösen

Ein Sprung auf **parquet-java 1.18.x** steht aus drei unabhängigen Gründen im Raum:

1. Der Hadoop-Klotz unter `formats-parquet` (Weg 3 in
   [dependency-cve-exposure-shipped-artifact.md](../open/dependency-cve-exposure-shipped-artifact.md)).
2. **Geshadetes Jackson 2.21.3 in `parquet-jackson`** — drei HIGH, die kein eigener
   Pin erreicht; als begründete Ausnahme in `.trivyignore.yaml` hinterlegt.
3. Dieser Defekt — allerdings **nur mittelbar**: Die Ursache liegt in eigenem Code
   (nicht aufgelöster Platzhalter), nicht in der Bibliothek. Ein Upgrade behebt ihn
   **nicht**.

## Umsetzung (2026-08-16, `a01c4f07`)

- **`ParquetMessageTypeToChunkSchema`** — Umkehrung von
  `ChunkSchemaToParquetMessageType`. Der Vertrag ist bewusst schwächer als
  „ursprünglichen Typ rekonstruieren": Die Vorwärtsrichtung ist **nicht injektiv**.
  Kriterium ist, dass der abgeleitete Typ in `readColumn` denselben Zugriff auslöst;
  bei Mehrdeutigkeit gewinnt die allgemeinste Variante.
- **Wächter erweitert** um einen Vergleich der Lese-Zugriffsform — bewusst *nicht*
  Typgleichheit, weil ein Manifest legitim den spezifischeren Typ tragen darf.
- **Tests**: drei neue in `ParquetFallbackSchemaReadTest` (Zeilen *durch* ein
  Fallback-Schema lesen, Typen stammen aus dem Footer, Drift wird abgewiesen). Der
  alte Test, der `all { it.neutralType is NeutralType.Text }` festschrieb, ist
  umgeschrieben.
- **Aufgeräumt**: `markS4FallbackUsed` (No-op) entfernt, drei KDoc-Stellen
  entdriftet, die den nie gebauten Ziel-Schema-Fallback weiter versprachen.

## Offen

- Ob der fehlende Footer-Key im Bundle-Export Absicht ist (das Manifest liegt ja
  daneben) oder eine Lücke, ist **nicht geklärt**. Schriebe der Bundle-Export den
  Key zusätzlich, wären seine Mitglieder ohne Fallback lesbar und trügen die
  spezifischeren Typen (`Identifier`, `Enum`, `Geometry`) mit. Berührt das
  Bundle-Format und ist deshalb nicht Teil dieser Behebung.
- **`manifestPresent` wird von keinem Produktivpfad gelesen** — nur von Tests. Mit
  Typen aus dem Footer ist das keine Korrektheitslücke mehr, aber eine Warnung
  „Schema abgeleitet, semantische Details fehlen" wäre weiterhin sinnvoll.
