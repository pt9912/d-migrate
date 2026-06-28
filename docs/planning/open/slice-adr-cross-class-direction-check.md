# Tracker: Cross-Class-Richtungsprüfung Vertrag › ADR › Slice (slice↔adr)

> **Status:** Tracker / wartet auf d-check-Feature (2026-06-28)
> **Trigger:** Bei der Gate-Härtung der Spec-Straten (`.d-check.yml`, `matrix`
> `order` + `direction: no-downward`) wurde die Grenze der v0.30.0-Neuerung
> sichtbar: `direction: no-downward` prüft die Richtung nur **klassenintern**
> (innerhalb *einer* geschichteten Klasse, z. B. den Spec-Straten
> Vertrag › Technik › Sicht). Die **klassenübergreifende** SDP-Kette
> Vertrag › ADR › Slice ist damit noch nicht mechanisch erzwingbar.
> **Aktivierungsbedingung:** Sobald eine d-check-Version mit passendem
> Cross-Class-Richtungs-Feature (slice↔adr) erscheint — dann hier verdrahten.

## Lücke

Das Regelwerk (`grundlagen-konventionen.md`, Zeile „Referenz-Richtung (SDP)")
definiert die Stabilitäts-Kette **Vertrag › ADR › Slice**: normative Referenzen
zeigen nur volatil→stabil. Heute mechanisiert die `.d-check.yml`:

- **Spec-Abwärts-Bann** (klassenübergreifend, hart): `matrix`-Regeln
  `spec→adr` / `spec→plan` `allow: false` — gegründet in konventionen.md
  (Zeile „Cross-Reference-Trigger": ein Spec→ADR-Rückzeiger existiert nicht im
  bindenden Text, auch nicht als Quellen-Spalte).
- **Spec-interne Straten** (klassenintern): `order` + `direction: no-downward`
  über `spec/**` (Vertrag › Technik › Sicht; `architecture.md` = Sicht).

**Nicht** mechanisiert: die Richtung an der **ADR↔Slice-Grenze**. Konkret die
Frage „darf eine ADR einen Slice referenzieren" — heute bewusst ungegatet, weil
ein blankes `matrix`-`adr→plan` `allow: false` nur legitime Provenienz flaggt
(Messung 2026-06-28: **19 `adr→plan`-Refs über 14 ADRs**, allesamt `consulted:`-
oder Slice-Kontext, der laut konventionen.md erlaubt ist: „Abwärts-/Seitwärts-
Verweise sind Kontext, keine Spezifikation").

## Was das Feature leisten müsste

Eine brauchbare Cross-Class-Prüfung muss **normativ vs. Kontext**
unterscheiden — sonst reproduziert sie genau die 19 Fehlalarme. Also nur
**bindende** Abwärts-/Seitwärts-Verweise flaggen, Provenienz-/`consulted:`-
Kontext durchlassen. Sobald d-check das anbietet:

1. Cross-Class-`order` Vertrag › ADR › Slice konfigurieren (die Klassen `spec`,
   `adr`, `plan` sind in der `matrix` schon definiert).
2. Den Normativ-Scope so setzen, dass die 19 Kontext-Refs grün bleiben.
3. Gegenprobe: ein **bindender** ADR→Slice-Verweis (Entscheidung *aus* dem Slice
   abgeleitet) muss flaggen; ein reiner `consulted:`-/Umsetzungs-Zeiger nicht.

## Referenzen

- `.d-check.yml` — aktuelle `matrix`-Config (Spec-Straten + Spec-Abwärts-Bann)
- [`../done/spec-adr-downref-hygiene.md`](../done/spec-adr-downref-hygiene.md) — wo die Cross-Class-Lücke zuerst notiert wurde
- [ADR 0004](../../adr/0004-documentation-and-planning-structure.md) — Doku-/Planning-Struktur, SDP-Quelle
