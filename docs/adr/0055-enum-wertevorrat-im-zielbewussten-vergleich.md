---
status: accepted
date: 2026-09-10
decision-makers: pt9912
consulted: docs/adr/0048-enum-wertevorrat-im-fingerprint.md, docs/planning/open/enum-inline-check-fidelity.md
informed: hexagon/core (TableComparator, MigrationFingerprint), spec/ddl-generation-rules.md, CHANGELOG.md
---

# Der Wertevorrat eines Enums zählt auch im zielbewussten Vergleich, nicht nur im Fingerprint

> **Status: accepted (2026-09-10).** Die formbasierte Erkennung eines
> Wertevorrat-CHECKs aus
> [ADR 0048](0048-enum-wertevorrat-im-fingerprint.md) gilt nicht mehr nur im
> Post-Execute-Fingerprint, sondern auch im **zielbewussten** Vergleich, aus
> dem der Migrationsplan entsteht. `schema compare` bleibt streng.

## Kontext und Problemstellung

ADR 0048 hat die Erkennung bewusst auf den Fingerprint begrenzt: „Die Toleranz
gilt **nur** im Fingerprint." Der Planer sah den zurückgelesenen CHECK also
weiterhin als eigenständigen Constraint, den das Soll-Schema nicht hat.

Was das bedeutet, ist gegen echtes SQL Server gemessen worden — mit einer
Tabelle, deren einzige Besonderheit eine Enum-Spalte ist:

| Lauf | vorher | |
| --- | --- | --- |
| erster `schema migrate --execute` | Exit 0, `NVARCHAR(5)` + `CHECK` steht | |
| **zweiter Lauf, unverändertes Soll** | plant `DropConstraint`, **führt ihn aus** — der CHECK ist weg — und meldet danach Drift (Exit 5) | |
| Soll mit **geänderter** Werteliste | löst den alten CHECK und legt keinen neuen an | |

Der zweite Lauf zerstört also genau die Werte-Durchsetzung, die der erste
angelegt hat, und der dritte Fall migriert eine echte Änderung gar nicht: der
Zieldialekt legt `enum` und Textspalte als denselben Typ ab, der Unterschied
steckt allein in den Werten, und die einzige Stelle, an der er sichtbar wäre,
war der Constraint — den das Soll nicht führt.

Die Grenze aus ADR 0048 war damit nicht konservativ, sondern der Schaden
selbst. Sie zu ziehen war schlüssig, solange nur der Fingerprint betrachtet
wurde: dort heilte sie die Drift-**Meldung**. Dass derselbe Unterschied eine
Zeile vorher zu einer geplanten **Operation** wird, blieb ungeprüft.

## Entscheidung

Die Erkennung aus ADR 0048 — formbasiert, zwei Schreibweisen, Eindeutigkeit vor
Toleranz, informationserhaltend — ist eine Eigenschaft des **Vergleichs**, nicht
einer einzelnen Projektion. Sie gilt deshalb an beiden Stellen, die den
Migrationslauf tragen:

- **Fingerprint** (unverändert gegenüber ADR 0048): der CHECK wandert in
  dieselbe Projektion wie der Wertevorrat des Spaltentyps.
- **Zielbewusster Vergleich** (neu): derselbe CHECK zählt zur Spalte statt zum
  Constraint-Block, und der Wertevorrat wird als **eigene Dimension** der
  Spalte verglichen.

Die eigene Dimension ist der Kern und nicht bloß Beiwerk: der Zieldialekt
faltet `enum` und Textspalte auf denselben deklarierten Typ, ein
Werte-Unterschied wäre über den Typvergleich also nicht mehr zu sehen. Er wird
als Spaltenänderung gemeldet, und der Neubau der Spalte schreibt den CHECK mit
— dasselbe Ergebnis, das ein Anwender von einer geänderten Werteliste erwartet.

Die Regel steht an **einer** Stelle im Code (`EnumCheckProjection`), aus der
beide Projektionen sie beziehen. Sie zweimal zu führen hiesse, sie zweimal
auseinanderlaufen zu lassen.

`schema compare` bleibt streng: dort ist die Darstellung selbst der
Unterschied, den ein Anwender sehen will.

## Konsequenzen

- Der Fingerprint-Algorithmus **springt nicht**. Die Projektion des
  Fingerprints ist unverändert; nur der Vergleich zieht nach. Gespeicherte
  Plan-Artefakte, `allowedPostUpFingerprints` und die Rollback-Drift-Prüfung
  sind nicht betroffen.
- Ein zweiter `schema migrate`-Lauf gegen ein konvergiertes Ziel plant null
  Operationen — die Konvergenz-Zusage gilt jetzt auch für Enum-Spalten.
- Eine geänderte Werteliste wird als Spaltenänderung geplant und angewendet.
- Dialekte ohne erkennbare Reverse-Form (siehe die Grenze in ADR 0048) sind
  unberührt: wo nichts erkannt wird, wird auch nichts gefaltet.

## Verworfene Alternativen

- **Die Faltung in `TargetProjection` legen.** Dort geht es um Angaben, die ein
  bestimmter Zieldialekt nicht zurückmelden kann. Die Regel ist aber formbasiert
  und dialektunabhängig; sie an Dialekt-Fähigkeiten zu hängen hiesse, dieselbe
  Aussage fünfmal zu pflegen.
- **Nur den Constraint falten, den Wertevorrat nicht vergleichen.** Behebt den
  zerstörenden zweiten Lauf, macht aber eine geänderte Werteliste unsichtbar —
  aus einem Schaden würde ein stiller.
- **Den Reverse den Enum rekonstruieren lassen** (Weg A aus
  [`enum-inline-check-fidelity.md`](../planning/open/enum-inline-check-fidelity.md)).
  Unverändert der breitere Eingriff, unverändert nicht vorweggenommen.
