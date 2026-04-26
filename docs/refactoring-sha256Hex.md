# Refactoring: SHA-256 / Hex-Encoding konsolidieren

Status: offen
Ursprung: Code-Review zu AP 6.3 (0.9.6 Phase A), siehe Reuse-Findung #4.
Hinweis: Datei wurde als `.md` angelegt — die anderen Plan-/Refactoring-Docs
in `docs/` nutzen ebenfalls Markdown.

## Problem

Die SHA-256-Berechnung mit Hex-Ausgabe wird im Repo an inzwischen elf
Stellen neu implementiert (zehn Quellcodefiles im Refactoring-Scope,
eine Stelle im Buildscript ausgenommen). Vier verschiedene Schreibweisen
(`joinToString { "%02x" }`, `joinToString separator=""`, Lookup-Table,
Buildscript-Variante) machen Refactorings, Performance-Tuning und Tests
unnötig fragmentiert. AP 6.4 und 6.5 (0.9.6 Phase A) haben jeweils eine
weitere Lookup-Table-Kopie hinzugefuegt — der Druck zur Konsolidierung
waechst.

### Aktuelle Fundstellen

| Datei | Zeile | Form |
| --- | --- | --- |
| `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ExportOptionsFingerprint.kt` | 48 | `"%02x".format(byte)` |
| `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ImportOptionsFingerprint.kt` | 49 | `"%02x".format(byte)` |
| `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt` | 42 | `joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)` (`HASH_LENGTH = 10`) |
| `hexagon/ports-common/src/testFixtures/kotlin/dev/dmigrate/server/ports/memory/InMemoryArtifactContentStore.kt` | 52 | `joinToString(separator="") { ... "%02x".format(byte) }` |
| `hexagon/ports-common/src/testFixtures/kotlin/dev/dmigrate/server/ports/memory/InMemoryUploadSegmentStore.kt` | 85 | gleiche Form wie ArtifactContent |
| `adapters/driven/storage-file/src/main/kotlin/dev/dmigrate/server/adapter/storage/file/StreamingHashWriter.kt` | 43–52 | Lookup-Table (schnellste Variante) |
| `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/fingerprint/PayloadFingerprintService.kt` | 28–41 | Lookup-Table-Kopie (0.9.6 AP 6.4) |
| `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/approval/ApprovalTokenFingerprint.kt` | 21–32 | Lookup-Table-Kopie (0.9.6 AP 6.5) |
| `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/perf/LargeJsonFixture.kt` | 74, 211 | `joinToString("") { "%02x".format(it) }` |
| `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/perf/LargeJsonFixtureTest.kt` | 152 | gleiche Form |
| `adapters/driving/cli/build.gradle.kts` | 38 | Buildscript-lokal — nicht im Refactoring-Scope |

Buildscript-Helper bleibt aussen vor, weil das Plugin-Klassen-Loading nicht den
selben Klassenpfad wie die Anwendung nutzt.

## Vorschlag

Eine zentrale Top-Level-Extension in `hexagon:core`:

```kotlin
// hexagon/core/src/main/kotlin/dev/dmigrate/core/util/HexEncoding.kt
package dev.dmigrate.core.util

private val HEX_CHARS = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(HEX_CHARS[(b.toInt() ushr 4) and 0xF])
        sb.append(HEX_CHARS[b.toInt() and 0xF])
    }
    return sb.toString()
}
```

Top-Level-Extension statt Member-Extension auf einem Object — sonst muss
der Aufrufer entweder `with(HexEncoding) { bytes.toHex() }` schreiben oder
mit `import dev.dmigrate.core.util.HexEncoding.toHex` riskieren, dass die
Extension-Resolution je nach Kotlin-Version anders aufgeloest wird. Top-
Level ist eindeutig.

`hexagon:core` ist bereits transitive Abhaengigkeit aller betroffenen
Module — keine Build-Graph-Aenderung noetig.

Die Lookup-Table-Variante ist messbar schneller als
`joinToString { "%02x".format(it) }` (`String.format` allokiert pro Byte
einen Formatter), und sie ist bereits in `StreamingHashWriter` als
de-facto Standard ausgewaehlt — wir folgen diesem.

Optional: zusaetzlich `fun sha256Hex(bytes: ByteArray): String` und
`fun sha256Hex(text: String): String` als Convenience, da viele Call-Sites
`MessageDigest.getInstance("SHA-256").digest(...)` plus Hex direkt
hintereinander rufen. Kann als zweiter Schritt nach der Encoder-
Konsolidierung folgen.

## Migration

Pro Datei:

1. `import dev.dmigrate.core.util.toHex` ergaenzen.
2. Lokale `sha256Hex`/`toHex`-Helper loeschen.
3. Aufrufstelle: `digest.toHex()` statt `digest.joinToString(...)`.
4. `MessageDigest.getInstance("SHA-256")` an Ort und Stelle lassen oder
   ueber den optionalen `sha256Hex(...)`-Helper zusammenfassen, wenn beide
   Schritte direkt aneinander stehen.

Reihenfolge:

1. **Helper anlegen** in `hexagon/core/src/main/.../util/HexEncoding.kt`.
2. **Produktivcode umstellen**:
   - `ExportOptionsFingerprint`, `ImportOptionsFingerprint`
     (`hexagon/application`)
   - `MysqlSequenceNaming` (`driver-mysql`) — `take(10)` bleibt erhalten
   - `StreamingHashWriter` (`storage-file`) — bestehende `toHex`-Extension
     loeschen, da sie nun aus `hexagon:core` kommt
3. **Downstream-Imports anpassen** (kein Duplikat, nur Import-Update):
   - `Sidecar.rehash` (`storage-file`) ruft heute `digest.digest().toHex()`
     gegen die `internal fun ByteArray.toHex` aus `StreamingHashWriter.kt`
     auf — Import wechselt mit, sonst kein Eingriff.
4. **Test-Fixtures**:
   - `InMemoryArtifactContentStore`, `InMemoryUploadSegmentStore`
     (`hexagon/ports-common` testFixtures)
   - `LargeJsonFixture`, `LargeJsonFixtureTest` (`adapters/driven/formats`
     Tests)
5. **Docker-Build** + `koverVerify`.

## Risiken

- `MysqlSequenceNaming` schneidet das Hex-Ergebnis auf `HASH_LENGTH = 10`.
  Migration darf das `take(HASH_LENGTH)` nicht entfernen — sonst aendert
  sich der erzeugte Triggername.
- `LargeJsonFixture.stampHex()` ist Stamp-Funktion fuer den Perf-Fixture-
  Cache. Falls sich der zurueckgegebene Hex-String unbeabsichtigt aendert
  (z.B. Buchstabengrosse, Padding), wird die Fixture neu generiert.
  Lookup-Table-Variante ist byte-fuer-byte identisch zu `"%02x".format` —
  Risiko ist theoretisch, aber `stampHex().shouldBe(...)`-Tests sollten
  bestaetigt werden.
- Modul-Boundary-Checks: `hexagon:core` darf keine neuen transitive
  JVM-Dependencies einfuehren. Lookup-Table braucht nichts ausser stdlib.
- Detekt-Baselines: das Loeschen lokaler `sha256Hex`-Funktionen kann eine
  Baseline-Datei invalidieren. Pro betroffenes Modul ggf. Baseline neu
  erzeugen (`gradle :<module>:detektBaseline`).

## Nicht-Ziele

- Ablöse von `MessageDigest`-Verwendung durch eine eigene Crypto-Wrapper-API.
  SHA-256 bleibt JDK-Standard, das hier ist reine Encoder-Refactor.
- Aenderungen an `:adapters:driving:cli:build.gradle.kts` (Buildscript ist
  separater Klassenpfad).

## Aufwand

Acht Files plus neuer Helper im `hexagon:core`-Modul, zentral ueber Suche
`%02x` plus `HEX_CHARS`. Keine Behavior-Aenderung, kover-Coverage bleibt
durch bestehende Tests gedeckt. Realistisch ein bis zwei Stunden inklusive
Docker-Build, eventueller Detekt-Baseline-Updates und Verifikation der
Perf-Fixture-Stamps.
