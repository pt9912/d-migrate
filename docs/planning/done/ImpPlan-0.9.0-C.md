# Überplan: Phase C - Export-Checkpoint und Resume

> **Milestone**: 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI
> **Phase**: C (Export-Checkpoint und Resume)
> **Status**: Überplan (2026-04-16) — gliedert den Exportpfad in zwei
> konkrete Unterphasen. Der Umsetzungsstand wird in den Unterplänen
> geführt.
> **Unterpläne**:
> - [`ImpPlan-0.9.0-C1.md`](./ImpPlan-0.9.0-C1.md) — Export-Resume
>   tabellengranular (bestätigte Tabellen werden bei Wiederaufnahme
>   übersprungen; neuer Manifest-Lifecycle; Executor-Seam für
>   `operationId` aus Phase B)
> - [`ImpPlan-0.9.0-C2.md`](./ImpPlan-0.9.0-C2.md) — Mid-Table-Resume
>   via `--since-column`-Marker (feinere Granularität; DataReader-Port-
>   Erweiterung; Single-File-Fortsetzungsvertrag)
>
> **Referenz**: `docs/planning/implementation-plan-0.9.0.md` Abschnitt 3,
> Abschnitt 4.3 bis 4.7, Abschnitt 5.4, Abschnitt 6.3, Abschnitt 8.1 und
> 8.2; `docs/planning/ImpPlan-0.9.0-A.md`; `docs/planning/ImpPlan-0.9.0-B.md`;
> `spec/cli-spec.md` `data export`.

---

## 1. Ziel

Phase C macht den in Phase A sichtbaren und in Phase B infrastrukturell
vorbereiteten Resume-Vertrag für den Exportpfad produktiv.

Der Teilplan beantwortet bewusst die export-spezifischen Fragen:

- wie ein Exportlauf einen resume-fähigen Positionsmarker pro Tabelle führt
- wann während eines Exports ein Checkpoint als bestätigt gilt
- wie `DataExportRunner` und `StreamingExporter` mit vorhandenen
  Checkpoint-Manifests zusammenspielen
- welche Exportoptionen für einen Resume-Lauf identisch sein müssen
- wie die Quellidentität eines Exportlaufs gegen das Manifest validiert wird
- wie file-basierte Output-Ziele gegen einen Checkpoint validiert werden
- wie der Exportpfad nicht resume-fähige Fälle klar abweist

Phase C liefert damit keine allgemeine Checkpoint-Infrastruktur mehr, sondern
den ersten echten Datenpfad auf diesem Unterbau.

Nach vollständigem Abschluss von Phase C (C1 + C2) soll klar und testbar
gelten:

- file-basierte Exportläufe können ab einem bestätigten Checkpoint
  kontrolliert fortgesetzt werden
- der Exportpfad schreibt Checkpoints nur an fachlich sauberen Stellen
- semantisch inkompatible Resume-Versuche scheitern im Preflight
- bestehende Export-Statistiken und Fortschrittsmeldungen bleiben erhalten,
  bekommen aber Resume-/Operation-Kontext hinzu (Executor-Seam,
  `ProgressRenderer`-Anzeige und Starting/Resuming-Label sind in **C.1**
  verortet, nicht in Phase E)

---

## 2. Warum zwei Unterpläne?

Phase C deckt zwei fachlich unterschiedliche Granularitätsstufen ab.
Beide verwenden denselben Manifest- und Port-Vertrag aus Phase B, haben
aber sehr unterschiedliche Risikoprofile:

**C1 — Tabellengranular (niedrigeres Risiko, hoher Praxisnutzen):**
Wenn ein Exportlauf nach Stunden abbricht, waren typischerweise mehrere
Tabellen bereits vollständig geschrieben. Eine tabellengranulare
Wiederaufnahme skippt diese fertigen Tabellen und exportiert nur die
unvollständigen von vorn. Das erfordert keine Kooperation vom
`DataReader` und keinen deterministischen Marker innerhalb einer Tabelle.
C1 liefert damit 80% des realen Resume-Werts bei minimalem Eingriff in
den Treiber-Stack.

**C2 — Mid-Table-Resume (höheres Risiko, ergänzender Feinschliff):**
Für große Einzeltabellen reicht C1 nicht aus. Mid-Table-Resume setzt auf
einen fachlich bestätigten Marker (typischerweise den
`--since-column`-Wert des letzten committeten Chunks) und erweitert den
`DataReader`-Port um eine Marker-basierte Fortsetzung. Das Risiko aus
§5 R1 („Export-Marker ohne ausreichend deterministische Quelle") wird in
C2 explizit adressiert, nicht stillschweigend umgangen.

Die Trennung stellt sicher, dass C1 schnell landen und den
Resume-Nutzervertrag aus Phase A produktiv einlösen kann, während C2
seinen eigenen Reviewpfad bekommt und nicht unter Zeitdruck an den
DataReader-Port gedrückt wird.

---

## 3. Scope-Übersicht

### 3.1 Insgesamt in Scope (C1 + C2 zusammen)

- Resume-Preflight für `data export` auf Basis des Phase-B-Manifests
- Checkpoint-Initialisierung für neue Exportläufe
- resume-fähige Exportfortsetzung pro Tabelle **und** pro Marker-Position
  innerhalb einer Tabelle
- persistierte Export-Positionsmarker pro Tabelle
- Kompatibilitätsprüfungen für:
  - Quell-Fingerprint / effektive Source-Identität
  - Tabellenliste
  - Output-Ziel
  - Format / Encoding / CSV-Optionen
  - `--filter`
  - `--since-column` / `--since`
- klare Ablehnung nicht resume-fähiger Exportfälle:
  - stdout
  - unpassende Zielpfade
  - semantisch inkompatible Checkpoints
- Fortschritts- und Ergebnisanreicherung für Resume-/Operation-Kontext
- Aktivierung der Runtime (kein „accepted but ignored"-Zustand nach
  Phase C)

### 3.2 Aufteilung zwischen C1 und C2

| Baustein | C1 | C2 |
|---|---|---|
| Preflight + Kompatibilitätsprüfung | ✅ | unverändert |
| Manifest anlegen/fortschreiben/completen | ✅ (pro Tabelle) | ✅ (pro Chunk) |
| Tabellen mit Status `COMPLETED` überspringen | ✅ | — |
| Innerhalb einer Tabelle am Marker weiterlesen | — | ✅ |
| `DataReader`-Port-Erweiterung für Marker-Paging | — | ✅ |
| File-per-table stable mapping | ✅ | unverändert |
| Single-File-Fortsetzung (ein Tabellenziel) | ✅ (nur komplett neu, wenn unvollständig) | ✅ Staging+Atomic-Rename (Fresh-Track; Mid-Table-Rebuild bewusst auf spaeteren Release verschoben — siehe C2 §5.4) |
| Executor-Seam für `operationId` | ✅ (Phase-B-Nachzug) | unverändert |
| `ProgressRenderer` zeigt `operationId` + Starting/Resuming-Label | ✅ | unverändert |
| Runner-Warning „run from scratch" entfernen | ✅ | unverändert |

### 3.3 Bewusst nicht Teil von Phase C (weder C1 noch C2)

- importseitige Resume-Fortsetzung (→ Phase D)
- allgemeine Signalbehandlung bei SIGINT/SIGTERM
- Retry-Logik
- paralleler Export mehrerer Tabellen
- Resume für `data transfer`
- nachträgliche Archivierungs-/GC-Strategien für alte Checkpoints

---

## 4. Leitentscheidungen (gelten für C1 und C2)

### 4.1 Export-Resume bleibt file-basiert

Phase C übernimmt den in Phase A gesetzten 0.9.0-Zuschnitt:

- Resume ist nur für file-basierte Exportpfade gültig
- stdout-Export bleibt reguläre Basisfunktion, aber kein Resume-Ziel

Verbindliche Folge:

- `DataExportRunner` behält den CLI-Preflight gegen `--resume` ohne
  `--output`
- `StreamingExporter` braucht keinen halbunterstützten TTY-/Pipe-Resume-Pfad

### 4.2 Der Resume-Marker ist pro Tabelle und fachlich bestätigt

Phase C fixiert:

- Export-Resume arbeitet pro Tabelle mit einem serialisierbaren Status
  (C1) und optional einem Marker innerhalb der Tabelle (C2)
- ein Marker repräsentiert den letzten fachlich bestätigten Stand, nicht
  einen beliebigen Byte-Offset
- Wiederaufnahme startet am nächsten noch nicht bestätigten Bereich

Verbindliche Folge:

- ein Checkpoint wird erst fortgeschrieben, wenn ein Chunk inklusive
  Dateischreibpfad erfolgreich abgeschlossen ist
- Mid-Chunk- oder Mid-Writer-Zustände sind kein gültiger Resume-Stand

### 4.3 Export-Kompatibilität ist strenger als „gleiche Quelle"

Phase C fixiert für Resume-Preflight mindestens diese Gleichheitsklassen:

- Quell-Fingerprint bzw. effektiv aufgelöste Source-Identität des Laufs
- Tabellenmenge und Reihenfolge des Laufs
- Output-Modus:
  - Single-File
  - File-per-table
- Zielpfade/Dateinamen
- Ausgabeformat
- Encoding
- CSV-relevante Optionen:
  - Delimiter
  - BOM
  - Header
  - NULL-String
- `--filter`
- `--since-column`
- `--since`

Nicht zulässig ist:

- einen Checkpoint „best effort" mit geändertem Filter oder anderem
  Zielpfad wiederzuverwenden
- einen Checkpoint gegen eine andere aufgelöste Source-DB oder einen
  abweichenden Lauf-Fingerprint weiterzuverwenden
- Resume bei formell gleichem Format, aber abweichender Output-Topologie
  still durchlaufen zu lassen

### 4.4 Auto-Discovery und Resume dürfen sich nicht widersprechen

Der heutige Exportpfad erlaubt Auto-Discovery aller Tabellen. Für Resume ist
dieser Pfad nur dann sicher, wenn das Manifest die aufgelöste Tabellenmenge
stabil festhält.

Phase C fixiert:

- ein Resume-Lauf arbeitet gegen die im Checkpoint bestätigte effektive
  Tabellenliste
- spätere Unterschiede zwischen aktueller Auto-Discovery und Manifest sind
  ein semantischer Preflight-Fehler, kein stilles Nachziehen

Damit bleibt Resume deterministisch, auch wenn sich die Quelle seit dem
abgebrochenen Lauf verändert hat.

### 4.5 Resume und bestehende Output-Dateien brauchen einen expliziten Vertrag

Der aktuelle Exportpfad öffnet Ziel-Dateien mit `TRUNCATE_EXISTING`.
Resume braucht deshalb einen kontrollierten Dateivertrag.

Phase C fixiert:

- Resume darf bestehende Exportdateien nur weiterverwenden, wenn Manifest und
  Dateiziel kompatibel sind
- für File-per-table-Resume muss die Zuordnung `table -> output path`
  manifestgebunden stabil sein (C1)
- Single-File-Resume mit **einer** Tabelle ist in C1 nur als
  Neu-Schreiben des Dateiziels zulässig, wenn die Tabelle im Manifest
  nicht `COMPLETED` ist; andernfalls ist der Lauf bereits fertig
- Single-File-Resume über eine manifestgebundene, vom Checkpoint-Store
  kontrollierte Fortsetzungsdatei ist Ziel von C2

Begründung:

- der aktuelle Exportpfad öffnet Single-File-Ziele mit
  `TRUNCATE_EXISTING`
- JSON-/YAML-/CSV-Writer besitzen unterschiedliche Container- und
  Header-Semantik
- ein kontrollierter Fortsetzungspfad ist belastbarer als ein
  scheinbar einfacher In-Place-Append-Vertrag — und gehört deshalb in C2

### 4.6 Leere Tabellen und Teilfehler bleiben Teil des Resume-Vertrags

`StreamingExporter` garantiert heute für leere Tabellen einen echten
Writer-Lebenszyklus. Phase C darf diese Garantie nicht brechen.

Verbindliche Folge:

- auch leere Tabellen müssen einen nachvollziehbaren Resume-/Abschlussstatus
  im Manifest hinterlassen (`COMPLETED` ohne Rows)
- Tabellenfehler bleiben pro Tabelle sichtbar; ein fehlgeschlagener
  Exportlauf darf bereits bestätigte Tabellenergebnisse nicht entwerten

### 4.7 Phase C aktiviert die Runtime, nicht nur den Preflight

Die aktuelle Warnung im Runner, dass `--resume` zwar akzeptiert, aber noch
nicht aktiv ist, ist nach Phase C nicht mehr zulässig.

Verbindliche Folge:

- file-basiertes `--resume` führt nach C1 entweder zu echter
  tabellengranularer Wiederaufnahme oder zu einem klaren Preflight-/
  Manifestfehler
- ein „accepted but ignored" ist für den Exportpfad nach Abschluss von
  C1 nicht mehr Teil des Vertrags
- C2 verfeinert die Granularität ohne den Runtime-Vertrag zu brechen

---

## 5. Risiken und offene Punkte (übergreifend)

### 5.1 Export-Marker ohne ausreichend deterministische Quelle

Der größte fachliche Risikopunkt ist der eigentliche Export-Marker.
Ein OFFSET-basiertes Skippen ist bei gleichzeitigem Schreibverkehr nicht
deterministisch.

Mitigation:

- C1 umgeht das Risiko vollständig, weil Mid-Table-Resume nicht Teil der
  Unterphase ist — fertige Tabellen werden per Status geskippt, unfertige
  neu exportiert
- C2 bindet den Marker verbindlich an einen fachlichen Anker
  (`--since-column`); ohne solchen Anker gibt es in 0.9.0 kein
  Mid-Table-Resume

### 5.2 Single-File-Resume ist heikler als File-per-table

Bei einer Datei pro Tabelle ist die Zuordnung vergleichsweise klar. Ein
Single-File-Resume ist deutlich sensibler, weil Datencontainer, Writer-Header
und Dateikonsistenz sauber zusammenpassen müssen.

Mitigation:

- C1 lässt Single-File nur für genau eine Tabelle zu, und auch nur als
  "von vorn, wenn unvollständig" (§4.5)
- C2 modelliert den kontrollierten Fortsetzungspfad getrennt und
  format-spezifisch

### 5.3 Auto-Discovery gegen manifestierte Tabellenmenge

Resume gegen eine dynamisch neu erkannte Tabellenliste ist riskant. Phase C
muss das Manifest als kanonische Laufreferenz behandeln, sonst wird der
Exportpfad nondeterministisch.

Mitigation: §4.4 fixiert das schon in C1.

### 5.4 Teilweise geschriebene Output-Dateien

Auch mit atomarem Manifest-Schreiben bleibt die Frage kritisch, wie mit
bereits begonnenen, aber nicht sauber abgeschlossenen Output-Dateien
umgegangen wird.

Mitigation:

- C1: Dateien einer noch nicht `COMPLETED`en Tabelle werden als
  ungültig angesehen und überschrieben (`TRUNCATE_EXISTING` bleibt
  zulässig, solange das Manifest die Tabelle nicht als abgeschlossen
  markiert hat)
- C2: der kontrollierte Fortsetzungspfad ersetzt dieses Verhalten durch
  einen format-spezifischen Rebuild-/Append-Pfad

### 5.5 Resume-Warnpfad darf nicht versehentlich bestehen bleiben

Der aktuelle Repo-Stand akzeptiert `--resume` bereits sichtbar, führt den
Lauf aber noch von vorn aus. Wenn C1 diese Zwischenstufe nicht sauber
ablöst, bleibt ein irreführender Nutzervertrag bestehen.

Mitigation: C1 entfernt die Warning explizit und ersetzt sie durch den
echten Runtime- oder Fehlerpfad (§4.7).

---

## 6. Reihenfolge und Empfehlung

Empfohlen wird für 0.9.0:

1. **C1 zuerst** (tabellengranular). Liefert den größten Praxisnutzen
   mit dem geringsten Treiber-Risiko und schließt den Nutzervertrag aus
   Phase A vollständig.
2. **C2 danach** (mid-table). Baut auf dem in C1 etablierten Manifest-
   Lifecycle auf und fügt Marker-basierte Wiederaufnahme hinzu.

Der Masterplan `docs/planning/implementation-plan-0.9.0.md` §6.3 bleibt als
übergreifende Phase-C-Beschreibung stabil; die konkrete Umsetzung wird
in den Unterplänen `ImpPlan-0.9.0-C1.md` und `ImpPlan-0.9.0-C2.md`
verfolgt.
