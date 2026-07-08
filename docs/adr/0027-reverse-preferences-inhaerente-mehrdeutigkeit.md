---
status: accepted
date: 2026-07-05
decision-makers: pt9912
consulted: docs/planning/done/reverse-preferences.md (Slice-Plan, drei Review-Runden), docs/planning/open/sqlite-reverse-identifier-64bit-narrowing.md (Ur-Ticket)
informed: spec/reverse-preference-mechanism.md, spec/type-mapping.md, spec/connection-config-spec.md, spec/cli-spec.md
---

# Reverse-Präferenzen: inhärente Reverse-Mehrdeutigkeiten per deklarierter Anwender-Präferenz auflösen — nicht per Heuristik oder Post-Compare-Fold

> **Status: accepted (2026-07-05).** Wenn ein Ziel-DDL beim Reverse-Engineering
> **mehrere gleichwertige** Neutralmodell-Repräsentationen zulässt (inhärente
> Mehrdeutigkeit), löst d-migrate das über eine **deklarierte Anwender-Präferenz**
> (Config-Datei + CLI-Flag, **konservativer Default**) auf — **nicht** über eine
> Auto-Heuristik und **nicht** über eine tolerante Post-Compare-Faltung mit
> Fingerprint-Bump. Erster Fall: die SQLite-AUTOINCREMENT-Breite
> (`identifier` 32-bit ↔ `biginteger` + `generation: identity` 64-bit).

## Kontext und Problemstellung

SQLites `INTEGER PRIMARY KEY AUTOINCREMENT` ist ein 64-bit-Rowid und ist
**speicher-ununterscheidbar** von zwei Neutralmodell-Formen: dem 32-bit-Vertrag
`identifier` (PG `SERIAL`, MySQL `INT AUTO_INCREMENT`) und dem 64-bit
`biginteger` + `generation: identity`. Der SQLite-Reverse **muss** eine der beiden
wählen — das Ziel-DDL trägt die Information nicht, die die Wahl entscheiden würde.
PG/MySQL haben diese Mehrdeutigkeit nicht (sie unterscheiden per Spaltenbreite
int4/int8); **nur SQLite** kollabiert beide Formen.

Heute wählt der Reverse fest `identifier` (+ INFO-Note R202). Für einen
SQLite→PG/MySQL-**Transfer** eines hochvolumigen 64-bit-Auto-Increment-PK verengt
das den Wertebereich still auf 32-bit (Overflow bei ~2,15 Mrd — externer
Consumer-Befund m-trace, siehe Ur-Ticket). Die Frage: **wie löst d-migrate solche
inhärenten Reverse-Mehrdeutigkeiten strukturell auf** — nicht nur diesen einen Fall.

## Entscheidungstreiber

- **Die Mehrdeutigkeit ist inhärent, nicht ableitbar.** Das Tool kann die Absicht
  nicht aus dem Ziel-DDL erschließen — nur der Anwender kennt sie.
- **Kein Fingerprint-Bump ohne Not.** Eine Post-Compare-Faltung `identifier ≡
  biginteger+identity` müsste über **zwei** getrennt projizierte Felder (`type` und
  `generation`) laufen — der gelandete Typ-Kanonisierer ist `(NeutralType) ->
  NeutralType` und kann `generation` architektonisch nicht anfassen. Das erzwänge
  einen `schema-fingerprint`-Bump (Artefakt-/Overlay-Invalidierung) plus eine
  invasive Sonderfaltung — und würde eine Drift-Klasse zurückholen, die der
  Kanonisierungs-Slice gerade beseitigt hat.
- **Null Regression.** Der bisherige, spec-konforme Stand muss der Default bleiben.
- **Wiederverwendbarkeit.** Weitere inhärente Reverse-Mehrdeutigkeiten sind
  absehbar; die Auflösung soll ein Muster sein, kein Einzelfall-Hack.

## Betrachtete Optionen

- **Option A — Auto-Heuristik.** Der Reverse rät (z. B. „AUTOINCREMENT ⇒ immer
  64-bit"). **Verworfen:** rät gegen die halbe Nutzerschaft, ändert den Default
  spürbar (Regression), und die geratene Form driftet im Post-Compare weiter.
- **Option B — Post-Compare-Fold + Fingerprint-Bump.** Der Reverse stellt fest auf
  `biginteger+identity` um, und ein struktureller Fold versteckt die entstehende
  Drift. **Verworfen:** `type`+`generation`-spannender Fold, `v_N`-Bump
  (Artefakt-Invalidierung), architektur-invasiv — hoher Preis für einen
  Einzelfall (siehe Entscheidungstreiber).
- **Option C — Deklarierte Präferenz (Config + CLI), konservativer Default.**
  Der Anwender erklärt die Absicht; der Reverse produziert je nach Präferenz ein
  anderes Schema. **Gewählt.** Der Fingerprint hasht unverändert, was der Reverse
  liefert — **kein Bump**; der Default hält den bestehenden Stand.

## Entscheidung

**Gewählt: Option C.** Inhärente Reverse-Mehrdeutigkeiten werden durch eine
**deklarierte Anwender-Präferenz** aufgelöst:

1. **Konservativer Default** = der bisherige, spec-konforme Stand (keine
   Regression). Für die SQLite-AUTOINCREMENT-Breite: 32-bit `identifier` + R202.
2. **Opt-in** über CLI-Flag **und** `.d-migrate.yaml` (Flag > Config > Default),
   Oberflächen-Vokabular **dialekt-neutral als Breite** (`32` | `64`), damit der
   stabile Config-Vertrag nicht an interne Neutraltyp-Namen koppelt.
3. **Kein Fingerprint-Bump, kein Fold.** Die Präferenz wirkt an der
   **Reverse-Wurzel** (was das Tool ins Neutralmodell schreibt), nicht im
   Vergleich; `schema-fingerprint`-Algorithmus unverändert.
4. **Wurzel, nicht Symptom.** Das Tool rät nicht mehr und muss die Rate-Folgen
   nicht verstecken.

Der generische Mechanismus + eine wachsende **Registry** der Mehrdeutigkeiten sind
im Zielbild-Spec `reverse-preference-mechanism.md` festgehalten; die konkrete
Oberfläche in `connection-config-spec.md`, `cli-spec.md` und `type-mapping.md`.

## Konsequenzen

- **Gut:** SQLite→PG/MySQL-Transfer eines 64-bit-Auto-Increment-PK bleibt 64-bit
  (`--sqlite-autoincrement-width 64`); der Default-Reverse bleibt bit-für-bit
  unverändert (Null-Regression), R202 bleibt und nennt jetzt den Flag
  (Auffindbarkeit). Opt-in ist **nicht stumm** (bestätigende INFO-Note statt
  Unterdrückung).
- **Konsistenz:** Im 64-bit-Modus rekonstruiert SQLite `biginteger` +
  `Identity(legacySerialSyntax = true)` — dasselbe, was der MySQL-Reverse eines
  `BIGINT AUTO_INCREMENT` liefert (beide sind Legacy-Auto-Increment, nicht
  SQL-Standard-IDENTITY) → SQLite→PG und MySQL→PG erzeugen dasselbe `BIGSERIAL`.
- **Preis:** Der Anwender muss die Absicht deklarieren, wo das Tool früher (falsch)
  riet. Das ist der bewusste Kern der Entscheidung.
- **Bewusst außerhalb:** Der SQLite-`migrate`-gegen-SQLite-Pfad (authored
  `biginteger+identity`) hat noch eine Render-Lücke **und** braucht
  Präferenz-Threading im Post-Compare-Re-Read — eigenes Ticket, nicht dieser
  Slice (der Transfer-Fall braucht kein SQLite-Generate).

## Bestätigung

- `make docs-check` grün; Docker-`check` grün (Unit-Tests Reverse beide Modi +
  Config-Resolver-Präzedenz).
- Live: SQLite→PG-Transfer mit `--sqlite-autoincrement-width 64` → PG-Ziel
  `BIGSERIAL` (64-bit); Default → `SERIAL` (unverändert).
- **Kein Fingerprint-Change** (`schema-fingerprint-v7` unverändert); der
  authored-`identifier`→SQLite-Post-Compare bleibt grün.

## Weitere Informationen

- Slice-Plan (Design D1–D7, AP0–AP5, drei Review-Runden):
  [`reverse-preferences.md`](../planning/done/reverse-preferences.md).
- Ur-Ticket (durch dieses Muster abgelöste „Option 2"):
  [`sqlite-reverse-identifier-64bit-narrowing.md`](../planning/open/sqlite-reverse-identifier-64bit-narrowing.md).
- Muster-Präzedenz (querschnittliche Prinzipien als ADR):
  [ADR-0015](0015-fulltext-tsvector-neutral-type.md),
  [ADR-0021](0021-column-ordinal-fidelity.md).
