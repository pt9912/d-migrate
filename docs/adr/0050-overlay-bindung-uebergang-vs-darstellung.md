---
status: accepted
date: 2026-08-28
decision-makers: pt9912
consulted: docs/planning/next/partition-mapping-overlay.md
informed: hexagon/core (MigrationOverlay, MigrationOverlayValidator), hexagon/application (MigrationOverlayPreflight), adapters/driving/cli (schema migrate, schema reverse, schema generate)
---

# Overlay-Bindung: Übergang bindet an ein Paar, Darstellung an ein Schema

> **Status: accepted (2026-08-28).** `MigrationOverlay` trägt statt zweier
> fester Fingerabdruck-Felder eine **sealed Bindung**: `Transition` (Quelle +
> Ziel) für `using-expression` und `rename-mapping`, `Representation` (ein
> Schema) für `partition-mapping`. Format `migration-overlay.v2`; v1-Dokumente
> werden weiterhin als `Transition` gelesen.

## Kontext und Problemstellung

`MigrationOverlay` ist der Weg, auf dem ein Anwender Wissen beisteuert, das
das Werkzeug nicht ableiten kann. Das Dokument trägt heute
`sourceFingerprint` **und** `targetFingerprint` und gilt damit für ein
Schema*paar*; `MigrationOverlayValidator` lehnt es ab, sobald eine der beiden
Seiten nicht passt (`OVERLAY_STALE_SOURCE_FINGERPRINT` /
`OVERLAY_STALE_TARGET_FINGERPRINT`). Verdrahtet ist es ausschließlich an
`schema migrate` (`--migration-overlay`).

Beim Schneiden des `partition-mapping`-Overlays fiel auf, dass seine beiden
Fälle außerhalb dieser Naht entstehen: Partitions-Kindnamen bei
`schema reverse` (eine Datenbank, kein Paar), `LIST` → `RANGE` bei
`schema generate` (eine Datei, kein Zielschema).

Die naheliegende Lesart wäre gewesen: zwei Befehle, denen der Vertrag nicht
passt, also biegt man den Vertrag. Das wäre die falsche Reihenfolge. Die
Frage ist nicht, welche Form den Befehlen entgegenkommt, sondern **wovon der
Inhalt eines Overlays tatsächlich abhängt.**

## Entscheidungstreiber

Die Antwort fällt je Overlay-Art unterschiedlich aus, und das ist der Kern:

| Art | Aussage | Hängt ab von |
| --- | ------- | ------------ |
| `using-expression` | „Spalte `x` wird von Typ A zu Typ B mit diesem Ausdruck konvertiert" | **beiden** Zuständen |
| `rename-mapping` | „Das Objekt, das `kunde` hieß, heißt jetzt `customer`" | **beiden** Zuständen |
| `partition-mapping` (LIST) | „Diese Wertemengen entsprechen diesen Grenzen im Zieldialekt" | **einem** Schema (dem, das die LIST-Partitionierung deklariert) + dem Dialekt |
| `partition-mapping` (Namen) | „Partition 1 heißt `p_2024`" | **einem** Schema (dem gelesenen) |

Die ersten beiden beschreiben einen **Übergang** — eine Aussage über zwei
Zustände, die ohne beide sinnlos ist. `partition-mapping` beschreibt eine
**Darstellung**: wie ein Sachverhalt in einem Dialekt ausgedrückt wird. Der
IST-Zustand einer laufenden Datenbank ist dafür belanglos. Auch innerhalb
eines `schema migrate` ist er es: die LIST-Partitionierung steht im
SOLL-Schema, und wie sie zu RANGE-Grenzen wird, hängt nicht daran, was in der
Zieldatenbank gerade liegt.

Die Befehlsform hat das also nicht verursacht, sondern nur sichtbar gemacht.

## Betrachtete Optionen

- **Paarbindung bleibt Pflicht** — `partition-mapping` gäbe es nur an
  `schema migrate`.
- **Beide Felder auf denselben Wert setzen** — kein Formatbruch.
- **`targetFingerprint` nullable machen** — Bedeutung je nach `overlayKind`.
- **Sealed Bindung** — `Transition` vs. `Representation`. **Gewählt.**

## Entscheidungsergebnis

Gewählt: **sealed Bindung**.

```kotlin
sealed interface MigrationOverlayBinding {
    /** using-expression, rename-mapping: eine Aussage über zwei Zustände. */
    data class Transition(val sourceFingerprint: String, val targetFingerprint: String)
    /** partition-mapping: eine Aussage über die Darstellung eines Schemas. */
    data class Representation(val schemaFingerprint: String)
}
```

`dialect` bleibt Feld des Dokuments — beide Bindungsarten brauchen ihn. Die
verlangte Bindungsart ergibt sich aus `overlayKind`; ein Dokument mit der
falschen wird abgelehnt, nicht umgedeutet. `formatVersion` steigt auf
`migration-overlay.v2`; v1-Dokumente tragen beide Felder flach und werden
weiterhin als `Transition` gelesen, ohne dass bestehende Artefakte neu
geschrieben werden müssen.

### Warum „beide Felder gleich" nicht nur unschön, sondern falsch ist

Das war zunächst die billigste Variante, und sie scheitert messbar, nicht
ästhetisch. Der Validator vergleicht `sourceFingerprint` gegen den
**IST**-Zustand. Ein Darstellungs-Dokument, das den SOLL-Fingerabdruck in
beide Felder schriebe, käme durch diese Prüfung nur, wenn IST und SOLL gleich
wären — also genau dann, wenn es nichts zu migrieren gibt. Für jede echte
Migration wäre das Dokument unbrauchbar.

### Warum kein nullables Feld

Ein `targetFingerprint: String?`, dessen Bedeutung an einem Geschwisterfeld
hängt, ist dieselbe Bauart, die im Projekt schon einmal aufgelöst wurde: die
nullablen `mysql*`/`sqlite*`-Felder generischer Ports wichen dem sealed
`DdlDialectContext`. Hier gilt derselbe Grund — der Compiler soll die
Fallunterscheidung erzwingen, statt sie jedem Aufrufer zu überlassen.

## Konsequenzen

- **`schema generate` kann Overlays konsumieren.** Nicht als Zugeständnis,
  sondern weil die Bindung nie ein Paar brauchte. `E055` für `list` wird
  damit zu einem Abbruch, der durch ein Overlay auflösbar ist, statt zu einer
  Sackgasse.
- **Die Diagnose muss den Fingerabdruck nennen, an den zu binden ist.** Kein
  Befehl gibt heute den Fingerabdruck eines Schemas aus. Wer ein Overlay
  schreiben soll, braucht ihn; also trägt ihn die Meldung, die das Overlay
  anfordert (`R346`, `E055`). Die Fehlermeldung sagt, was zu schreiben ist.
- **Ein Reverse-Overlay bindet an den Zustand *vor* seiner Anwendung.**
  Partitions-Kindnamen stehen im Fingerabdruck, und die Kinder werden **nach
  Namen** sortiert — das Anwenden des Overlays ändert den Fingerabdruck also
  zwangsläufig. Ohne diese Festlegung entstünde ein Dokument, das nie
  validieren kann.
- **[ADR 0026](0026-fingerprint-kanonisierung-post-compare.md) bleibt
  unberührt.** Ein Overlay stellt Identität her; es lockert keine Gleichheit.
  `schema compare` bleibt streng.
- Bestehende Overlays bleiben gültig. Der Preflight muss beide Bindungsarten
  prüfen, und ein Dokument mit der für seine Art falschen Bindung ist ein
  Blocker, kein Hinweis.
