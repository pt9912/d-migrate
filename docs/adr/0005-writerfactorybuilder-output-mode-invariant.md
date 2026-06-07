---
status: accepted
date: 2026-06-08
decision-makers: pt9912
consulted: S7-Multi-Angle-Review (Plan-Review-v2 Finding 5)
informed: kuenftige Implementier:innen des Parquet-/Arrow-IPC-/ORC-Wirings;
  S8-Plan-Author (Checkpoint-Specifics-Slice)
---

# `writerFactoryBuilder`-Invariante: Output-Mode statt Output-Pfad

## Kontext und Problemstellung

Mit S7-0 (`34eea7ce`, Sub-Slice der Parquet-Cut-A-Umsetzung)
wandelte sich der `writerFactoryBuilder` von
`() -> DataChunkWriterFactory` zu
`(ExportOutput) -> DataChunkWriterFactory`. Der Grund war, das
CLI-Wiring konnte ohne den aufgeloesten `ExportOutput` nicht
zwischen Single-File-Modus (mit `d-migrate.manifest`-Footer-KV-
Provider) und Bundle-Modus (ohne Footer-KV; Manifest extern in
`manifest.yaml`) unterscheiden — die S4 §2.2-Invariante verlangt
genau diese Verzweigung. Konkretisiert wurde der Builder im
CLI-Modul (`adapters/driving/cli/.../DataExportWiring.kt:buildWriterFactoryForOutput`)
und im Application-Modul-Konstruktor
(`hexagon/application/.../DataExportRunner.kt:writerFactoryBuilder`).

Im Multi-Angle-Review der S7-Serie tauchte folgende Asymmetrie auf:

- `ExportPreflightValidator.kt:105` ruft den Builder mit dem
  vom User aufgeloesten `ExportOutput` (`output`).
- `DataExportRunner.executeStreaming(...)` (Z. ~246) konstruiert
  dagegen einen Staging-`executorOutput = ExportOutput.SingleFile(staging.staging)`,
  wenn Checkpoint-Staging aktiv ist, und uebergibt diesen Staging-
  Output an den eigentlichen Streaming-Exporter; nach Erfolg wird
  ein atomarer Move auf den User-Target-Pfad ausgefuehrt.

Konsequenz: die Factory wurde gegen den User-Target-Pfad gebaut,
der Executor schreibt aber zu einem anderen Pfad. Heute bleibt das
folgenlos, weil weder der Footer-KV-Provider noch der Bundle-
Closure-Hook den `.path`/`.directory`-Wert konsumieren — die
Verzweigung im Builder ist rein Output-Mode-abhaengig
(`Stdout` / `SingleFile` / `FilePerTable`).

Ein zukuenftiger Path-aware Wiring-Schritt (z.B. relative-path-
Metadata im Footer, sidecar-File-Pfade, target-volume-spezifische
Buffer-Sizing-Heuristik) wuerde den falschen Pfad einfangen und
silently Metadata oder Sizing-Entscheidungen am User-Target
festlegen — obwohl die tatsaechlichen Bytes ins Staging
geschrieben werden.

## Entscheidung

**Implementierungen des `writerFactoryBuilder` duerfen
AUSSCHLIESSLICH auf die `ExportOutput`-Sealed-Subklasse verzweigen
(`Stdout` / `SingleFile` / `FilePerTable`); sie duerfen NIEMALS
den `.path`-Wert (von `ExportOutput.SingleFile`) oder den
`.directory`-Wert (von `ExportOutput.FilePerTable`) lesen.**

Begruendung: die Output-Mode-Klassifizierung zwischen User-Target
und Staging ist nach Konstruktion identisch (Staging spiegelt den
gleichen Mode wie das Ziel; bei `SingleFile` ist Staging eine
einzelne Datei, bei `FilePerTable` ist Staging ein Verzeichnis).
Path-abhaengige Logik wuerde diese Invariante brechen.

## Wenn doch Path-aware Wiring benoetigt wird

Den Builder-Call ins Executor-Innere ziehen, sodass die Factory
mit dem TATSAECHLICHEN Output (Staging oder User-Target,
abhaengig vom Lauf) konstruiert wird. Konkret: `factory`-Feld in
`ExportPreparedContext` wird durch eine `factoryBuilder`-Lambda
ersetzt; `DataExportRunner.executeStreaming` ruft sie mit dem
gewaehlten `executorOutput`. Das ist ein nicht-trivialer Refactor —
NICHT die Invariante brechen, sondern den Refactor durchziehen.

## Pros und Cons

**Pros:**
- Verhindert silente Pfad-Drift zwischen Validator und Executor.
- Macht die Invariante explizit testbar (z.B. ein
  `LintRule`-aequivalenter Detekt-Check kann auf `ExportOutput.SingleFile.path`-
  Reads im Builder-Pfad warnen, sobald jemand sie einbaut).
- Minimal-invasiver Schritt jetzt, mit klarem Pfad fuer den
  zukuenftigen Refactor.

**Cons:**
- Konvention statt typsystem-erzwungener Garantie. Ein neuer
  Implementierer kann die Invariante brechen, wenn er den ADR
  nicht liest.
- Bei `Stdout` ist die Verzweigung defensiv (defensive Branch
  ohne Provider) — die produktive Stdout-Ablehnung passiert
  upstream in `DataExportRunner.validateRequest`. ADR-konformes
  Verhalten ist es trotzdem.

## Konsequenzen

- `DataExportRunner.writerFactoryBuilder`-Kdoc und
  `ExportPreflightValidator.writerFactoryBuilder`-Kdoc
  referenzieren ab S7-Review-R2 nur noch diese ADR statt der
  langen Invariant-Begruendung im Code.
- S8-Plan-Autor (Checkpoint-Specifics-Slice) sollte diese
  Invariante im Hinterkopf haben, wenn `executeStreaming` weiter
  veraendert wird. Falls dort eine Path-aware Konstellation noetig
  wird, ist die ADR-konforme Loesung der oben skizzierte
  Builder-Move ins Executor-Innere.
