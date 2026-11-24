---
status: accepted
date: 2026-11-24
decision-makers: pt9912
consulted: docs/adr/0048-enum-wertevorrat-im-fingerprint.md, docs/adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md, docs/adr/0067-schema-compare-wertelisten-check-gleicher-wertevorrat.md, docs/planning/next/postgres-varchar-werteliste-erkennung.md
informed: hexagon/core (EnumCheckProjection, MigrationFingerprint), spec/cli-spec.md, spec/ddl-generation-rules.md, docs/user/anwenderhandbuch.md, CHANGELOG.md
---

# Die Werteliste-Erkennung liest auch `(ARRAY[…])::text[]` — `schema-fingerprint-v25`

> **Status: accepted (2026-11-24).** `EnumCheckProjection` erkennt die Form, in der PostgreSQL einen `IN`-CHECK an
> einer `varchar`-Spalte zurückgibt: `((spalte)::text = ANY ((ARRAY['a'::character varying, …])::text[]))`. Damit
> gelten ADR 0048, ADR 0055 und ADR 0067 auch für `varchar`-Spalten. Der Algorithmus-Tag steigt auf `v25`.

## Kontext und Problemstellung

Die Erkennung ist bewusst eng und formbasiert (ADR 0055): Sie liest `IN`-Liste, `= ANY (ARRAY[…])`, die
einelementige `=`-Form und eine OR-Kette, jeweils mit Casts an Literal und Spalte. Gemessen an PostgreSQL 18 kommt
`b IN ('OFF','ON')` an einer `varchar`-Spalte aber so zurück:

```
((b)::text = ANY ((ARRAY['OFF'::character varying, 'ON'::character varying])::text[]))
```

Der Cast **um das Array** fehlte in der Erkennung. Die Folgen, gemessen:

- `schema migrate --target db:` plant gegen eine Datenbank, die aus derselben Datei entstand, bei jedem Lauf
  `DROP CONSTRAINT` und `ADD CONSTRAINT` (Exit 0, keine Diagnose).
- `schema compare` meldet den gleichen Wertevorrat als Änderung (die Grenze, die ADR 0067 nennt).
- Der Fingerabdruck führt den CHECK an einer `varchar`-Spalte als rohen Text statt als Wertevorrat der Spalte.

## Entscheidung

1. **Ein Cast auf ein Text-Array um `ARRAY[…]` wird übergangen**, genau wie ein Cast an Literal oder Spalte. Erkannt
   werden `::text[]`, `::character varying[]` und `::varchar[]`, jeweils ohne Typmodifikator. Ein anderer Array-Typ
   (`::integer[]`, `::bpchar[]`, `::citext[]`) bleibt unerkannt; dort ist der CHECK weiter roher Text.
2. **Sonst bleibt die Erkennung, wie sie ist**: dieselben vier Formen, dieselben Grenzen (nur String-Literale, eine
   Spalte, kein `NOT IN`, kein `AND`).
3. **Fingerabdruck.** Die Erkennung speist die kanonische Nutzlast (ADR 0048). Ein von PostgreSQL gelesenes Schema
   mit Werteliste an einer `varchar`-Spalte hasht damit anders als bisher. `MigrationFingerprint.ALGORITHM` steigt
   deshalb auf `schema-fingerprint-v25`. Ein Artefakt mit `v24` scheitert dann laut an der Algorithmus-Prüfung,
   nicht still an einem anderen Hash.
4. **Für `schema compare`** gilt die Cast-Regel aus ADR 0067 unverändert. Ein `::text[]` um das Array ist darin ein
   Cast auf `text` ohne Länge und damit zulässig. ADR 0067 bleibt eingefroren; dessen genannte Grenze entfällt mit
   dieser Entscheidung.

## Konsequenzen

- `schema migrate --target db:` konvergiert an einer `varchar`-Spalte mit Werteliste (0 Anweisungen beim zweiten
  Lauf). Eine geänderte Liste ersetzt den CHECK unter seinem Namen.
- `schema compare` meldet den gleichen Wertevorrat an einer `varchar`-Spalte nicht mehr. Die Compare-Matrix
  verliert in den Zellen mit PostgreSQL als Quelle den Fund `ck_cm_customer_segment`.
- Plan- und Rücknahme-Artefakte, die mit `v24` erzeugt wurden, sind mit dieser Version nicht mehr verwendbar. Sie
  sind neu zu erzeugen; das nennt der CHANGELOG.
- Der Wertevorrat einer `varchar`-Domain (`VALUE`) läuft über dieselbe Erkennung und profitiert mit.
