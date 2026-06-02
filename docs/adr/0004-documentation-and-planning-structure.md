---
status: accepted
date: 2026-05-28
decision-makers: pt9912
consulted: c-hsm-doc ADR-0001 (Planning-Structure-ADR des Schwesterprojekts, 2026-05-26)
informed: künftige Plan-Doc-Autoren; Reviewer, die einordnen wo ein Plan-Doc in seinem Lebenszyklus steht
---

# Lebenszyklus des Planungsverzeichnisses (`open/` → `next/` → `in-progress/` → `done/`)

## Kontext und Problemstellung

`docs/planning/` war historisch in drei Ordnern (`open/`,
`in-progress/`, `done/`) organisiert — mit informeller, nirgends
dokumentierter Semantik. Bis 2026-05 hatte sich aus dieser
Informalität konkrete Drift angesammelt:

- `docs/planning/open/sqlite-sequence-emulation-plan.md` trug
  `Status: In Progress (2026-05-28)`, nachdem die Phasen B.0/B.1/B.2
  geliefert waren (Commits `48c7f01c` Phase B.0,
  `84ba7ab7` Phase B.1, `25f59f73` Phase B.2 Step 1, `09068f79`
  Phase B.2 Step 2; Phase A bestand ausschließlich aus § 11
  Pre-Code-Klärungen und hatte keinen eigenen Shipping-Commit).
  Trotz dieses Standes blieb die Datei in `open/`, weil der
  Ordnername "open" als "noch nicht abgeschlossen" gelesen wurde
  statt als "noch nicht angefangen".
- `docs/planning/open/refactoring-cli-testability.md` trug
  ähnlich `Status: Teilweise umgesetzt (McpServeCommand)` und saß
  trotzdem in `open/`.
- Die übrigen 12 `open/`-Einträge waren ein Mix aus `Draft`,
  `Entwurf`, `Vorschlag / Entscheidungsbasis` und reinen
  Referenz-Katalogen (`test-database-candidates.md`), ohne
  dokumentierte Trennung: welche von ihnen haben einen Scope, der
  als nächstes aktiviert werden kann, vs. welche sind reine
  Trigger-Watches?
- `docs/planning/in-progress/` enthielt nur zwei Dateien
  (`roadmap.md` und `diffresult-migration-plan-2.md`); das legte
  nahe, der Ordner sei für Top-Level-Aggregatoren reserviert statt
  für aktive Per-Feature-Slice-Pläne — aber kein Dokument sagte
  das aus.
- Per-Slice-Closures landen unter `docs/planning/done/` als
  `ImpPlan-<version>-<slice>.md`; auch dieses Muster war etabliert,
  aber undokumentiert.

Das Schwesterprojekt c-hsm-doc stand vor derselben Frage und hat
die Konvention in seiner
[`ADR-0001`](https://github.com/pt9912/c-hsm-doc/blob/main/docs/plan/adr/0001-documentation-and-planning-structure.md)
§2.4 festgehalten: ein 4-stufiger Lebenszyklus mit Pflicht-README
pro Ordner. Diese ADR übernimmt das Modell für d-migrate, mit
d-migrate-spezifischen Carve-outs zum Namensschema.

## Entscheidungstreiber

- Der Ordner eines Plan-Docs muss auf einen Blick eindeutig
  signalisieren, in welchem Status die Arbeit steht. "Open" als
  Doppelbedeutung ("nicht abgeschlossen" vs. "nicht angefangen")
  ist genau die Art überladenes Signal, das schleichend verfault.
- Langlebige Umbrella-Pläne (z. B.
  `sqlite-sequence-emulation-plan.md`, der die Phasen A bis E
  überspannt) brauchen einen Ordner, der sowohl "Planung läuft
  weiter" als auch "erste Sub-Slices geliefert" toleriert.
- Querverweise zwischen Plänen, Code-KDoc, CHANGELOG, ADRs und der
  Roadmap müssen stabil bleiben; die Ordnerstruktur darf nicht
  jedes Mal Plan-Renames erzwingen, wenn sich der Status ein
  Stückchen weiterbewegt.
- Die Konvention muss mit dem etablierten
  `ImpPlan-<version>-<slice>.md`-Namensschema für Per-Slice-Closures
  unter `done/` koexistieren — dieses Muster ist bereits aus über
  150 Closure-Dateien dokumentiert und darf nicht rückwirkend
  umgebaut werden.

## Betrachtete Optionen

### Option A — Drei Stufen `open/` → `in-progress/` → `done/` (Status quo)

Aktuelle Ordner beibehalten, die informelle Konvention nachträglich
dokumentieren.

- Pro: null Datei-Moves, keine Pfad-Updates.
- Pro: konsistent mit den 152 Querverweisen aus `done/ImpPlan-*`,
  die schon so existieren.
- Contra: "open" bleibt überladen — sowohl "Trigger-Watch ohne
  Scope" als auch "Scope-skizziert-aber-nicht-aktiviert" landen im
  selben Ordner.
- Contra: Pläne, die teilweise geliefert sind, aber noch Phasen
  vor sich haben, passen in keinen der drei sauber. Genau so ist
  `sqlite-sequence-emulation-plan.md` in `open/` hängengeblieben.

### Option B — Vier Stufen `open/` → `next/` → `in-progress/` → `done/` (c-hsm-doc-Modell)

Einen neuen Ordner `docs/planning/next/` zwischen `open/` und
`in-progress/` einziehen. Pläne mit skizziertem Scope, aber ohne
aktive Slice-Arbeit, leben dort; Pläne ohne Scope bleiben in
`open/`; Pläne mit aktiver Slice-Arbeit wandern nach
`in-progress/`.

- Pro: jeder Ordner trägt eindeutig eine einzige Bedeutung, auf
  einen Blick lesbar.
- Pro: der teilweise-geliefert-Fall ist nicht mehr unklar —
  `sqlite-sequence-emulation-plan.md` in `in-progress/` liest sich
  als "aktive Slice-Arbeit", ohne zu suggerieren alles sei schon
  fertig.
- Pro: im Schwesterprojekt c-hsm-doc seit 2026-05-26 erprobt.
- Contra: einmalige Migration von 12 bestehenden `open/`-Einträgen;
  eine neue ADR und vier READMEs zu schreiben.
- Contra: Querverweise in CHANGELOG, ADRs, Done-Plänen und
  Code-KDoc brauchen einen einmaligen Bulk-Sweep.

### Option C — Nur Status-Konvention, keine Ordner-Moves

Alles in `open/` belassen, dafür einen maschinell prüfbaren
`Status:`-Header vorschreiben, an dem nachgelagerte Tools andocken
können.

- Pro: keine Datei-Moves.
- Contra: Ordner werden zur Dekoration; Leser müssen jede Datei
  öffnen, um den Status zu sehen. Genau die Ordner-als-Signal-
  Eigenschaft, die diese ADR explizit motiviert, wäre damit aufgegeben.

## Entscheidung

Gewählt: **Option B** — Übernahme des 4-stufigen c-hsm-doc-Lebenszyklus.

```text
docs/planning/open/         — Trigger-Watches, offene Folgearbeiten ohne Scope
docs/planning/next/         — Pläne mit skizziertem Scope, noch nicht aktiv
docs/planning/in-progress/  — Roadmap-Aggregatoren + aktive Per-Feature-Umbrella-Pläne
docs/planning/done/         — gelieferte Per-Slice-Closures (ImpPlan-*) + abgeschlossene Umbrellas
docs/archive/               — explizit verworfene oder vollständig überholte Pläne
```

### Konvention pro Ordner

Jeder Ordner trägt eine `README.md`, die seine Konvention nennt.
Zusammengefasst:

- **`open/`** — Einträge beschreiben einen Trigger / eine
  Beobachtung / eine offene Folgearbeit ohne ausgearbeiteten
  Slice-Scope. Sie bleiben hier, bis sie entweder aktiviert
  werden (Move nach `next/`) oder verworfen werden (Move nach
  `docs/archive/`).
- **`next/`** — Einträge tragen einen skizzierten Scope (Ziel,
  grobe Arbeitspakete, Akzeptanzkriterien), aber noch keine
  aktiven Implementierungs-Commits. Sie bleiben hier, bis die
  Slice-Arbeit startet (Move nach `in-progress/`) oder verworfen
  wird.
- **`in-progress/`** — zwei Dokumenttypen leben hier:
  1. Top-Level-Aggregatoren mit sprechenden Namen (`roadmap.md`,
     `diffresult-migration-plan-2.md`). Sie wandern nicht.
  2. Per-Feature-Umbrella-Pläne, deren erste Phase geliefert ist
     oder deren Slice-Arbeit aktiv läuft. Sie wandern nach `done/`,
     sobald **alle** Phasen geliefert sind.
- **`done/`** — zwei Typen:
  1. `ImpPlan-<version>-<slice>.md`-Per-Slice-Closures
     (etabliertes d-migrate-Muster, 150+ Dateien).
  2. Umbrella-Pläne, deren sämtliche Phasen geliefert sind;
     diese tragen am Ende eine `## Closure`-Sektion.

### Namenskonventionen

- ADRs: `NNNN-kurz-titel.md` (vierstellige Nummer, MADR-Format) —
  unverändert gegenüber der bestehenden Konvention.
- Per-Slice-Closures: `ImpPlan-<version>-<slice>.md`
  (z. B. `ImpPlan-0.9.7-cross-dialect-sequencing.md`) — unverändert.
- Umbrella-Pläne und Roadmap-Aggregatoren: sprechende
  lowercase-kebab-Namen ohne numerischen Prefix (z. B.
  `roadmap.md`, `sqlite-sequence-emulation-plan.md`,
  `diffresult-migration-plan-2.md`).

Hinweis: c-hsm-doc nutzt einen `NNN-kurz-titel.md`-Prefix
(dreistellig, fortlaufend) für **alle** Plan-Einträge. d-migrate
übernimmt diesen Teil bewusst **nicht** — das etablierte
`ImpPlan-<version>-<slice>.md`-Schema trägt die Versionsinformation
mit, die ein 3-stelliger Prefix verlieren würde, und ein Umbenennen
von 150+ bestehenden `done/`-Dateien wäre Churn ohne Gewinn.

### Lebenszyklus-Übergänge

Ein Plan wechselt zwischen Ordnern per `git mv`, begleitet von:
- Einem `> Status: …`-Header-Update innerhalb der Datei, das den
  Übergang dokumentiert.
- Einem Sweep über alle Querverweise (CHANGELOG, ADRs, Done-Pläne,
  Code-KDoc, Roadmap), damit sie auf den neuen Pfad zeigen.
  Eingefrorene historische Records (geschlossene ADRs, Done-Pläne)
  werden mit nachgezogen — ein gebrochener Pfad ist schlechter als
  ein aktualisierter eingefrorener Record.

Ein Plan wandert nach `docs/archive/` nur, wenn er explizit
verworfen oder vollständig überholt ist. Vollständig gelieferte
Pläne wandern nach `done/`, nicht nach `archive/`.

**Sonderbahn für Per-Slice-Closures:** Die
`ImpPlan-<version>-<slice>.md`-Dateien laufen den Standard-Pfad
`next/ → in-progress/ → done/` üblicherweise **nicht** durch. Sie
werden direkt unter `done/` final platziert — eine eventuelle
Skizzen- oder Draft-Phase findet im zugehörigen Umbrella-Plan
(in `in-progress/`) statt, nicht in `next/` oder
`in-progress/` als eigenständige Datei. Die ImpPlan-Datei ist das
schriftliche DoD-Belegstück, das nach Abschluss neben dem
Umbrella platziert wird.

## Konsequenzen

- Der 2026-05-28-Sweep besteht aus zwei aufeinander folgenden
  Commits auf `develop` (Bezeichnung "Vorsweep" / "ADR-Commit",
  um nicht mit dem `Phase A/B…`-Vokabular des
  SQLite-Sequence-Emulation-Plans aus dem Kontext-Abschnitt zu
  kollidieren):
  1. **Vorsweep** `457a54d9` verschiebt
     `sqlite-sequence-emulation-plan.md` und
     `refactoring-cli-testability.md` von `open/` nach
     `in-progress/` und zieht 17 Querverweise nach.
  2. **ADR-Commit** `d8bc4046` führt `next/` ein, verschiebt
     9 weitere Pläne dorthin, schreibt die vier READMEs, fixt
     eine Reihe historisch falscher
     `planning/in-progress/ImpPlan-*`- und
     `planning/open/ImpPlan-*`-Pfade in Code-KDoc und
     Done-Plänen und landet diese ADR.
- Verweise auf
  `docs/planning/open/sqlite-sequence-emulation-plan.md` aus ADR-0003
  wurden in Commit `457a54d9` auf den neuen `in-progress/`-Pfad
  umgesetzt. Das ist ein bewusstes Carve-out gegenüber der
  "ADRs sind nach Accepted immutable"-Regel: nur die
  Pfad-Referenz wandert mit, der Entscheidungsinhalt bleibt
  unberührt.
- Die 12 bisherigen `open/`-Einträge teilen sich nach der
  Phase-B-Migration wie folgt auf:
  - **Bleiben in `open/`** (kein ausgearbeiteter Scope):
    `beispiel-stored-procedure-migration.md` (worked Example),
    `d-browser-integration-coupling-assessment.md` (Coupling-
    Vorabklärung), `test-database-candidates.md`
    (Referenz-Katalog, streng genommen gar kein Plan-Doc).
  - **Wandern nach `next/`** (Scope skizziert, nicht aktiv):
    `bi-demo-compose.md`, `migrations-ef-core-10.md`,
    `object-storage-artifact-store.md`,
    `orchestrator-examples.md`,
    `parquet-export-import-evaluation.md`,
    `persistence-jdbc-mig.md`,
    `profiling-data-quality-export.md`,
    `telemetry-observability-port.md`, `trino.md`.

## Bestätigung

Der Ordner eines Plan-Docs ist jetzt ein eindeutiges Single-Signal
seines Status. Die Konvention wäre mechanisch prüfbar (z. B. könnte
ein CI-Check sicherstellen, dass keine Datei mit
`Status: In Progress` in `open/` oder `next/` liegt). Ein solcher
Check ist nicht Bestandteil dieser ADR; falls die Drift erneut
auftritt, kann er separat im jeweiligen Folge-Slice eingezogen
werden.

## Weitere Informationen

- Die Vorbild-ADR aus dem Schwesterprojekt:
  [c-hsm-doc ADR-0001](https://github.com/pt9912/c-hsm-doc/blob/main/docs/plan/adr/0001-documentation-and-planning-structure.md)
  §2.4 (4-stufiger Lebenszyklus), §2.2 (Dateinamen).
- `docs/planning/{open,next,in-progress,done}/README.md` tragen
  die operative Konvention pro Ordner.
- Sprachhinweis: ADRs in d-migrate sind auf Deutsch zu schreiben.
  ADRs 0001-0003 wurden initial englisch verfasst und im
  Folge-Commit zu dieser ADR ins Deutsche übersetzt — die
  ADR-Immutability-Regel ("Accepted = immutable") gilt für den
  Entscheidungsinhalt, nicht für sprachliche Form. YAML-Frontmatter-
  Felder (`status: accepted`, `date: …`) bleiben englisch — das ist
  MADR-Spezifikation und nicht durch diese ADR überschreibbar.
