# Tracker: `config show` — vollständiger Multi-Source-Provenienz-Merge

> **Status:** Vorabklärung / Trigger Watch (2026-07-19)
> **Trigger:** `config show` Phase 1 (geliefert `03b76488`, Plan
> [`config-cli-management-surface.md`](../done/config-cli-management-surface.md)) zeigt nur die
> **effektiv aufgelöste Konfigurationsdatei** (Pfad nach `CLI > ENV > Default`) als Section-Baum
> plus die **Namen** aktiver `D_MIGRATE_*`-Runtime-Overrides. `spec/cli-spec.md` §6.7 wurde dafür
> vom früheren Wortlaut „gemerged aus allen Quellen" auf genau diesen Ist-Vertrag präzisiert.
> Ein **vollständiger** Merge aller Quellen mit Provenienz je Feld ist damit bewusst **nicht**
> geliefert und lebte danach nur noch als Satz im graduierten `done/`-Plan — dieser Tracker holt
> ihn zurück in `open/`, damit die Restfläche echt getrackt ist (ADR 0004).
> **Aktivierungsbedingung** (Move nach `../next/`): ein konkreter Bedarf für Feld-Provenienz
> **plus** ein Design der Merge-/Provenienz-Schicht **plus** eine vorgelagerte `cli-spec.md`
> §6.7-Präzisierung auf den Voll-Merge-Vertrag.

## Was Phase 1 heute **nicht** tut

`config show` rendert aktuell ausschließlich den **Inhalt der einen effektiv gewählten
Konfigurationsdatei**. Nicht abgedeckt:

- **Keine Default-Überlagerung** — implizite Standardwerte (die ohne Datei-Eintrag gelten)
  erscheinen nicht; nur explizit in der Datei Stehendes wird gezeigt.
- **ENV-Overrides nur als Namen** — die `D_MIGRATE_*`-Provenienzzeile listet Variablennamen,
  spielt deren Werte aber **nicht** in den Baum ein (auch nicht maskiert), und zeigt nicht,
  welches Feld dadurch überschrieben würde.
- **CLI-Flags gar nicht** — pro-Kommando-Flags, die eine Einstellung zur Laufzeit überstimmen,
  sind kein Teil der Ausgabe.
- **`${VAR}` bleibt literal** — Platzhalter werden bewusst nicht aufgelöst (kein versehentliches
  Secret-Leak); der real wirksame Wert ist also nicht sichtbar.
- **Keine Provenienz je Feld** — es gibt keine Annotation „dieser Wert kommt aus Datei / ENV /
  Flag / Default".

## Zielbild eines Voll-Merges

Eine Ausgabe, die pro Feld den **effektiv wirksamen** Wert (Secret-maskiert) **und** seine Herkunft
zeigt — die Präzedenz Inline-URL bzw. Flag → ENV → Datei-`${VAR}` → Datei-Literal → Default
aufgelöst und sichtbar gemacht. Das ist die ursprüngliche cli-spec-Intention „gemerged aus allen
Quellen".

## Warum zurückgestellt (nicht Phase 1)

- Braucht eine **Konfig-Modell-/Merge-Schicht mit Provenienz-Tracking** — deutlich größer als der
  reine, seiteneffektfreie `ConfigShowRenderer` (der nur eine bereits geparste Map maskiert-rendert).
  Offene Design-Frage: lebt diese Schicht im CLI-Adapter oder in einem neutralen Config-Modell im
  Hexagon (heute existiert **kein** solches merge-fähiges Modell — die Config wird verstreut je
  Kommando gelesen).
- **Kein Lastenheft-Eintrag erzwingt** den Voll-Merge heute; der operative Kern-Bedarf („Was steht
  wirklich in meiner Config, ohne Secrets, welche Datei ist aktiv, welche Overrides sind gesetzt")
  ist von Phase 1 gedeckt.

## Vorbedingungen vor `../next/`

1. **Konkreter Bedarf** für Feld-Provenienz (User-Requirement oder Support-/Debug-Motivation).
2. **Design der Merge-/Provenienz-Schicht** inkl. Ort (CLI-Adapter vs. neutrales Config-Modell) und
   wie sie sich mit der bestehenden pro-Kommando-Config-Auflösung verträgt (keine zweite Quelle der
   Wahrheit).
3. **Spec-Präzisierung** von `cli-spec.md` §6.7 auf den Voll-Merge-Vertrag — heute beschreibt §6.7
   bewusst nur die effektive-Datei-Sicht. Richtung: dieser Tracker verweist auf die Spec (Zielbild),
   **nicht** umgekehrt.

## Herkunft

Abgespalten aus [`config-cli-management-surface.md`](../done/config-cli-management-surface.md)
(§3.1 / offene Frage §7.1) bei dessen Graduierung nach `done/` am 2026-07-19 — dort war die
Restfläche nur als Nicht-Ziel vermerkt, ohne eigenen Tracker.
