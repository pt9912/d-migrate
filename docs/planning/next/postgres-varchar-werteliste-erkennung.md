# PostgreSQL: Werteliste an einer `varchar`-Spalte wird nicht erkannt — `schema migrate` plant bei jedem Lauf DROP/ADD

- **Status**: Draft mit Scope (2026-11-24). Aktiv beim ersten Implementierungs-Commit.
- **Ziel**: `EnumCheckProjection` erkennt die Form, in der PostgreSQL einen `IN`-CHECK an einer
  `varchar`-Spalte zurückgibt. Danach plant `schema migrate --target db:` gegen eine Datenbank, die aus
  derselben Datei entstand, 0 Anweisungen, und `schema compare` meldet den gleichen Wertevorrat nicht
  mehr ([ADR 0067](../../adr/0067-schema-compare-wertelisten-check-gleicher-wertevorrat.md)).
- **Vorbedingungen**: Entscheidung, ob ein ADR nötig ist. Die Erkennung speist auch den
  Fingerabdruck ([ADR 0048](../../adr/0048-enum-wertevorrat-im-fingerprint.md),
  [ADR 0055](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md)). Eine neue erkannte Form
  ändert die Hashes zurückgelesener `varchar`-Schemata und braucht damit einen neuen Algorithmus-Tag
  (heute `schema-fingerprint-v24`).

## Befund (gemessen 2026-11-23, PostgreSQL 18)

Was PostgreSQL aus `IN ('OFF','ON')` macht, je Spaltentyp (`pg_get_expr(conbin, conrelid)`):

| Spalte | Rückform | erkannt? |
| --- | --- | --- |
| `text` | `(a = ANY (ARRAY['OFF'::text, 'ON'::text]))` | ja |
| `text`, ein Wert | `(e = 'ON'::text)` | ja |
| `varchar(20)`, `varchar` | `((b)::text = ANY ((ARRAY['OFF'::character varying, 'ON'::character varying])::text[]))` | **nein** |
| `varchar(20)`, ein Wert | `((f)::text = 'ON'::text)` | ja |
| `char(3)` | `(d = ANY (ARRAY['OFF'::bpchar, 'ON'::bpchar]))` | erkannt, aber `bpchar` vergleicht aufgefüllt |

Die Lücke ist der Cast **um das Array**, `(ARRAY[…])::text[]`. Die Erkennung entfernt Casts an den
Elementen und an der Spalte, aber nicht diesen.

**Folge in `schema migrate`** (gemessen mit dem Arbeitsstand `1.13.0-SNAPSHOT`, ZIP-Distribution):
Eine Datei mit `a_state: { type: text, max_length: 20 }` und `ck_a_state: a_state IN ('OFF', 'ON')` wird
mit `schema generate` angelegt. Danach plant `schema migrate --source <dieselbe Datei> --target db:…`
mit Exit 0:

```sql
ALTER TABLE "obj_device" DROP CONSTRAINT "ck_a_state";
ALTER TABLE "obj_device" ADD CONSTRAINT "ck_a_state" CHECK (a_state IN ('OFF', 'ON'));
```

Das wiederholt sich bei jedem Lauf, denn die Datenbank gibt die Liste danach wieder in derselben Form
zurück. Es ist dasselbe Symptom wie Issue #77, mit anderer Ursache. `schema compare` meldet dasselbe
Paar als `DIFFERENT`. Vermutlich besteht das schon seit es die Erkennung gibt; an veröffentlichten
Versionen ist es nicht nachgemessen.

## Scope-Skizze

- **P0 — Messen.** Dieselbe Reproduktion gegen das veröffentlichte `1.12.2`-Image (alt oder neu?), dazu
  `varchar` ohne Länge, eine Liste mit `''` im Wert und eine `varchar`-Domain. Prüfen, ob die Form unter
  MySQL, SQL Server, Oracle oder SQLite vorkommt (vermutlich nicht).
- **P1 — Erkennung.** `EnumCheckProjection.valuesOf` entfernt einen Cast auf ein Text-Array
  (`::text[]`, `::character varying[]`) um das `ARRAY[…]`. Bewusst eng: kein anderer Array-Typ, kein
  Typmodifikator. Die Cast-Regel aus ADR 0067 (nur `text` und `character varying` ohne Länge) muss
  `text[]` dann ebenfalls erlauben.
- **P2 — Fingerabdruck.** Neuer Algorithmus-Tag, falls P1 die kanonische Nutzlast ändert. Prüfen, ob
  ein gespeichertes Plan- oder Rücknahme-Artefakt mit `v24` dadurch abgelehnt wird, und das im
  CHANGELOG nennen.
- **P3 — Tests.** Ein Unit-Test der Erkennung mit der gemessenen Rückform. Ein Live-Test im Modul
  `test/integration-postgresql`: Anlegen aus der Datei, zweiter `schema migrate --execute` plant nichts,
  eine geänderte Liste ersetzt nur diesen CHECK. Dazu `schema compare` → `IDENTICAL`. Gegenprobe ohne
  P1.
- **P4 — Doku.** Die Grenze aus ADR 0067, aus `spec/cli-spec.md` und aus dem Anwenderhandbuch
  („an einer Spalte mit `max_length` … meldet `schema compare` den Unterschied weiter") entfernen;
  `spec/ddl-generation-rules.md` 3.6 nennt die erkannten Formen.

## Akzeptanzkriterien

1. Datei mit `varchar`-Spalte und Wertelisten-CHECK → `schema generate` → `schema migrate --target db:`
   plant 0 Anweisungen, auch beim zweiten Lauf.
2. `schema compare` Datei gegen diese Datenbank → `IDENTICAL`; ein anderer Wertevorrat bleibt
   `DIFFERENT`.
3. `char(n)` bleibt beim Textvergleich (aufgefüllter Vergleich, ADR 0067).
4. Die neue Erkennung ist durch eine Gegenprobe belegt; der Fingerabdruck-Tag ist gehoben, falls die
   Nutzlast sich ändert.
