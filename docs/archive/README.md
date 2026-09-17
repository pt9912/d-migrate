# Verworfene und überholte Pläne

Ablage für Plan-Dokumente, die **explizit verworfen** oder vollständig
überholt wurden — im Unterschied zu `docs/planning/done/` und
`docs/planning/done-archive/`, die *abgeschlossene* (gelieferte) Arbeit
tragen. Ein Plan wandert hierher nur, wenn er bewusst aufgegeben oder durch
andere Pläne vollständig ersetzt wurde, nicht wenn er geliefert ist.

Lebenszyklus und Abgrenzung:
[`ADR 0004`](../adr/0004-documentation-and-planning-structure.md)
(Planungs-Lebenszyklus) und
[`ADR 0010`](../adr/0010-done-archive-und-gate-scan-ausschluss.md)
(Done-Archiv vs. verworfen). Das Verzeichnis ist vom d-check-Scan
ausgenommen (`.d-check.yml`); ein Umzug hierher rechnet die relativen Links
der Datei trotzdem um, damit sie lesbar bleiben.

## Bestand

| Datei | Grund | Nachfolger |
| ----- | ----- | ---------- |
| [`design.md`](design.md) | retiriert: veralteter Ist/Soll-Überblick, überholt durch `spec/architecture.md` ([ADR 0024](../adr/0024-ist-zustand-dokumentation.md)) | [`design-md-retire.md`](../planning/done/design-md-retire.md) |
| [`reader-treue-spatial-array-json.md`](reader-treue-spatial-array-json.md) | durch Schnitt überholt (2026-09-17): in vier Pläne unter einem Umbrella geteilt | [`reader-treue.md`](../planning/next/reader-treue.md) |
