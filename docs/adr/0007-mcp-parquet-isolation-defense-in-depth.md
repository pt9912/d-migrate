---
status: accepted
date: 2026-06-08
decision-makers: pt9912
consulted: S6-Multi-Angle-Review (Finding B1); S7-Multi-Angle-Review (Finding #3)
informed: MCP-Maintainers; kuenftige Author:innen einer MCP-Parquet-Erweiterung
---

# MCP-Parquet-Isolation: vier Verteidigungslinien

## Kontext und Problemstellung

Der MCP-Import-Pfad (`adapters/driving/mcp/…/McpDataImportJobWorker`)
exponiert heute kein Parquet. Die Begruendung ist organisatorisch:
das CLI-Wiring fuer Parquet (S6/S7) hat eine andere Resolver-,
Hook- und Composite-Writer-Verdrahtung als MCP, und der vollstaendige
MCP-Parquet-Vertrag (Bundle-Upload-Quellen, Hook-Wiring,
Permission-Modell) ist nicht definiert. Bis ein dedizierter
MCP-Parquet-Milestone das aendert, soll **jeder** MCP-Aufruf mit
`format=parquet` deterministisch fail-fast werden.

Die naheliegende Loesung „eine zentrale Validierung am
Worker-Eingang" ist nicht robust gegen Wiring-Drift: solange
StreamingImporter-Hooks und -Factories konfigurierbar bleiben,
kann eine Aenderung an einer Schicht die Validierung an einer
anderen Schicht umgehen. Der S6-Review (Finding B1) und der
S7-Review (Finding #3) haben deshalb eine
**Defense-in-Depth-Architektur** etabliert, in der vier
unabhaengige Schichten den Parquet-Pfad gegen MCP isolieren.

## Entscheidung

**Vier Verteidigungslinien — jede einzelne ist heute ausreichend,
das Zusammenwirken haelt die Isolation auch bei lokal ungeplanten
Refactors:**

1. **Fruehe Worker-seitige Ablehnung**
   (`McpDataImportJobWorker.execute`): wenn der MCP-Request
   `format=parquet` traegt, schlaegt der Job sofort mit
   `MCP_DATA_IMPORT_UNSUPPORTED_FORMAT` fehl. Eingefuehrt mit
   S6-Review Batch B1 (`a906ae36`). Verhindert, dass die
   Spool-/Resolver-/Streaming-Pipeline ueberhaupt anlaeuft.

2. **Mid-Layer NoOp-Default-Hook**: MCP verdrahtet im
   `DataImportRunner`-Konstruktor (`DataRunnerWorkers.buildRunner`)
   den Default `ImportInputResolutionHook.NoOp`. Der NoOp-Hook
   transformiert `ImportInput.SingleFile` / `Directory` NICHT zu
   `ResolvedSingleFile` / `ResolvedBundle`. Selbst wenn die fruehe
   Worker-Ablehnung umgangen wuerde, produzierte MCP nie Seekable-
   Inputs.

3. **Spaete Pre-Stream-Pruefung im StreamingImporter**
   (`StreamingImporter.import`-Loop, S7b): falls dennoch ein
   `ResolvedTableInput.Seekable` im Loop landet und keine
   `seekableReaderFactory` verdrahtet ist (MCP setzt `null`-Default
   seit S6-Batch F4), `error(...)`-Abort mit klarer Wiring-Drift-
   Meldung. Siehe
   `docs/adr/0006-wiring-drift-exception-family.md`.

4. **Fall-back-Elvis im TableImporter**
   (`TableImporter.prepareImport`, S7a): zweite Verteidigungslinie
   gegen direkte `TableImporter`-Test-Aufrufe, die den outer
   `StreamingImporter`-Loop umgehen. Selbe Exception-Familie.

## Pros und Cons

**Pros:**
- Keine einzelne Aenderung kann den MCP-Parquet-Isolation-
  Vertrag still brechen.
- Jede Schicht hat eine klar getrennte Verantwortung (Worker /
  Resolver / Importer-Loop / Test-Resilienz), was lokale
  Wartungs-Aenderungen risikolos macht.
- Wenn der MCP-Parquet-Milestone irgendwann ansteht, lassen sich
  die Schichten einzeln aufheben (NoOp-Hook → echter Parquet-Hook;
  null-Factory → echte Factory; Worker-Reject aufheben) ohne den
  Rest umzubauen.

**Cons:**
- Drei der vier Linien sind heute redundant zu Linie 1 — das ist
  Absicht (Defense in Depth), aber liest sich fuer einen
  Erstleser des MCP-Pfads wie Overengineering, solange das
  Big-Picture nicht klar ist.
- Wenn jemand zukuenftig nur Linie 1 entfernt (z.B. um Parquet
  zu „enabeln") ohne die anderen Schichten zu verdrahten,
  produzieren die nachgelagerten Linien suboptimale Fehlermeldungen
  (Wiring-Drift-IllegalStateException statt sauberer User-Message).
  Mitigation: MCP-Parquet-Aktivierung erfordert explizites
  Konsultieren dieses ADRs.

## Konsequenzen

- `DataRunnerWorkers.kt`-Comment zum `StreamingImporter`-Bau
  verweist ab S7-Review-Fix R2 (#3) nur noch auf diese ADR statt
  der vier-Schichten-Erklaerung im Code.
- Ein zukuenftiger MCP-Parquet-Milestone wird im Plan-Doc
  explizit auf diese ADR Bezug nehmen und beschreiben, welche
  Schichten in welcher Reihenfolge aufgehoben werden.
