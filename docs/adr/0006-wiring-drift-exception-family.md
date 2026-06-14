---
status: accepted
date: 2026-06-08
decision-makers: pt9912
consulted: S7-Multi-Angle-Review (Plan-Review-v2 Finding 4)
informed: kuenftige Konsumenten von SeekableDataChunkReaderFactory.unsupported(...);
  Wartungs-Authors von StreamingImporter / TableImporter
---

# Wiring-Drift-Exception-Familie: `IllegalStateException` als gemeinsamer Typ

## Kontext und Problemstellung

Mit der S7-Sub-Slice (`a0dc2c5b` S7a + `5ff17e6f` S7b) entstand
eine Mehrschicht-Sicherheitsarchitektur fuer Seekable-Inputs im
Streaming-Layer:

1. **`StreamingImporter.import`-Loop Pre-Stream-Check**
   (`adapters/driven/streaming/.../StreamingImporter.kt:88`): wenn
   ein `ResolvedTableInput.Seekable` im Loop landet, aber kein
   `seekableReaderFactory` verdrahtet ist, brechen wir fail-fast
   mit `error("Seekable input requires seekableReaderFactory; ...")` ab.
   `error(...)` wirft `IllegalStateException`.
2. **`TableImporter.prepareImport` Elvis-Resolver**
   (`adapters/driven/streaming/.../TableImporter.kt:135`): zweite
   Verteidigungslinie fuer direkte Test-Aufrufe am `TableImporter`,
   die den outer Pre-Stream-Check umgehen. Ebenfalls
   `error(...)`-Aufruf, also `IllegalStateException`.
3. **`SeekableDataChunkReaderFactory.unsupported(reason)`-Sentinel**
   (`hexagon/ports-read/.../SeekableDataChunkReaderFactory.kt:75`):
   Companion-Factory am Port, die im `create(...)`-Body wirft. Bis
   zum S7-Review-Fix R2 (#4) warf dieser Pfad
   `UnsupportedOperationException`.

Alle drei Pfade druecken dieselbe semantische Klasse aus:
**„Wiring-Drift"** — der Konsument hat eine Factory-/Hook-
Konstellation aufgebaut, in der ein Seekable-Pfad konstruierbar
ist, aber nicht ausgefuehrt werden darf. Das ist KEIN
„Feature/Format wird nie unterstuetzt"-Fall (dafuer waere
`UnsupportedOperationException` semantisch korrekt), sondern ein
Konsumenten-Setup-Fehler.

Der Multi-Angle-Review (Finding #4 / Plan-Review-v2) hat darauf
hingewiesen: ein zukuenftiger MCP-Hardening-Pfad, der explizit
`SeekableDataChunkReaderFactory.unsupported('MCP does not support
Parquet')` injiziert (statt heute `null`), wuerde im Fehlerfall
einen `UnsupportedOperationException` werfen — Konsumenten, die
die Wiring-Drift-Familie ueber `catch (IllegalStateException)`
behandeln, wuerden den Sentinel-Fall verfehlen.

## Entscheidung

**Alle drei Pfade werfen `IllegalStateException` (via
Kotlin-Standard-`error(...)`-Aufruf). `UnsupportedOperationException`
bleibt reserviert fuer die semantische Klasse „diese
Implementierung wird das nie unterstuetzen, unabhaengig vom
Konsumenten-Setup" (z.B. ein Reader, der ein Format-Konstrukt
fundamental nicht ausdruecken kann).**

## Pros und Cons

**Pros:**
- Konsumenten, die die Familie behandeln, brauchen genau einen
  Catch-Typ: `catch (e: IllegalStateException)`.
- Die Trennung zu `UnsupportedOperationException` (= „feature
  nicht implementierbar") bleibt scharf und semantisch wertvoll.
- Kein Verlust an Discoverability — der `reason`-String enthaelt
  immer noch die spezifische Begruendung; nur der Typ ist
  harmonisiert.

**Cons:**
- Ein Konsument, der vor dem S7-Review-Fix R2 auf
  `UnsupportedOperationException` setzte, muss seinen Catch
  umstellen. Es gibt heute KEINEN solchen Konsumenten — der
  Sentinel ist seit seiner Einfuehrung in S6-Batch 12 (F4) im Repo
  unwired und wird ausschliesslich von der Companion-Factory
  konstruiert.

## Konsequenzen

- `SeekableDataChunkReaderFactory.unsupported(...)`-Companion-Kdoc
  und der private Sentinel-Body verweisen ab S7-Review-R2 nur noch
  auf diese ADR statt der langen Begruendung im Code.
- Wenn in einem zukuenftigen Slice die Wiring-Drift-Familie um
  weitere Werfer ergaenzt wird (z.B. ein analoger Hook fuer Output-
  Seitige Seekable-Factories), gehoert der `error(...)`-Aufruf
  weiterhin zum Standard-Typ.
