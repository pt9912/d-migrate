# Cross-Dialekt-`schema compare`: Falsch-Positive abbauen (Slice A + konservative Fälle)

> **Status:** Entwurf (2026-09-14)
> **Ziel:** Die gemessene Falsch-Positiv-Quote von `schema compare` zwischen
> zwei zurückgelesenen Schemata senken — ohne die Zusage des strikten Modus
> aufzuweichen.
> **Herkunft:** Die „Nicht in Scope"-Liste aus
> [`../done/schema-compare-computed-expression-undecidable-silent.md`](../done/schema-compare-computed-expression-undecidable-silent.md).
> Gemeldet von einem Konsumentenprojekt, der zwei Reverses gegeneinander
> vergleicht und eine Quote von **38 % (PG↔MSSQL) / 38 % (PG↔MySQL) /
> 30 % (MSSQL↔MySQL)** gemessen hat. Jeder der sieben Punkte wurde im Code
> nachgemessen (Bericht 2026-09-14), keiner war erfunden; zwei brauchten eine
> Präzisierung.

## Die Wurzel (gemessen)

Beide Compare-Pfade bauen den Comparator **strikt**:
`SchemaCompareWiring.kt:50` und `McpRuntimeRegistries.kt:123` rufen
`SchemaComparator()` ohne Argumente — also `targetProjection = null`,
`authorship = null`, `serverForm = null`. `TargetProjection.kt:19-21` nennt die
Absicht: *„strikter Vergleich: jeden Unterschied zeigen, auch einen, den das
Ziel nicht ausdrücken kann."*

Damit liegt die **gesamte Falt-Maschinerie still**, die für `schema migrate`
gebaut wurde: `RawTextFolding` (CHECK, View-Body, Index-Prädikat) und
`EnumCheckProjection` (Enum↔CHECK) greifen nur mit Projektion bzw. Herkunft.

Das ist **für „handgeschriebenes Soll gegen zurückgelesenes Ist" richtig** — dort
*will* man den Unterschied sehen. Der Konsument vergleicht aber **zwei
zurückgelesene** Schemata; dort sind viele Funde nur zwei Server-Schreibweisen
desselben Sachverhalts.

Die Auflösung ist deshalb **zweigeteilt**: Was unstrittig falsch ist, wird
gelöst (dieser Plan). Was eine Vertragsänderung wäre, wird **nicht** hier
entschieden (siehe „Bewusst nicht in diesem Slice").

## Arbeitspaket 1 — ReferentialAction: implizit = explizit `NO ACTION`

**Befund (bestätigt, Punkt 2 der Meldung).** Drei Reader, drei Konventionen für
dasselbe Feld:

| Reader | `NO ACTION` | `RESTRICT` |
|---|---|---|
| PG (`PostgresTableMetadataQueries.kt:302-309`, `mapPgAction`) | `null` | `"RESTRICT"` |
| MySQL (`MysqlMetadataQueries.kt:110`) | `null` | `null` |
| MSSQL (`MssqlMetadataQueries.kt:191-192`, `actionDesc` Zeile 484) | `"NO ACTION"` | `"RESTRICT"` |

`actionDesc` macht nur `replace('_', ' ')`, aus `NO_ACTION` wird also der
String `"NO ACTION"` — nicht `null`. `ForeignKeySignature`
(`TableComparator.kt:349-352`) wird per Data-Class-Gleichheit verglichen
(`TableComparator.kt:455-459`), `"NO ACTION"` ≠ `null` → der FK gilt als
geändert.

**Umsetzung.** `"NO ACTION"` beim **Bauen** der Signatur auf `null` falten
(allenfalls case-/whitespace-tolerant), damit der bestehende `l != r`-Vergleich
unverändert bleibt. `NO ACTION` ist der SQL-Standard-Default — „explizit
hingeschrieben" und „weggelassen" sind derselbe Sachverhalt. Das ist keine
Semantik-Entscheidung, sondern das Einlösen einer Konvention.

**Akzeptanz.** Ein MSSQL-Reverse gegen ein PG-Reverse mit identischem FK
erzeugt **keinen** `constraintsChanged`-Eintrag, unabhängig davon, ob MSSQL
`NO ACTION` explizit führt. Der Test fällt, wenn man die Faltung entfernt.

## Arbeitspaket 2 — `sourceDialect` ist ein Herkunfts-Marker, keine Schema-Eigenschaft

**Befund (nicht gemeldet, beim Nachmessen gefunden).**
`SchemaComparator.kt:165` vergleicht `sourceDialect` auf Views; nichts nullt
ihn vorher. Ein PG-Reverse (`"postgresql"`) gegen ein MSSQL-Reverse (`"mssql"`)
meldet ihn als Änderung. Der 1.3.1-Fix betraf nur den **Post-Compare** von
`schema migrate` — der `schema compare`-Pfad wurde nicht mitgenommen.

**Umsetzung.** `sourceDialect` aus dem Vergleich nehmen. Er beschreibt, **woher**
ein Objekt gelesen wurde, nicht **was** es ist — genau die Klasse, die
`ReverseMarkerNormalizer` für `name`/`version` schon behandelt
(`ReverseMarkerNormalizer.kt:64-75`).

**Akzeptanz.** Zwei Reverses aus verschiedenen Dialekten erzeugen keinen
`VIEW_CHANGED`-Fund allein wegen `sourceDialect`.

## Arbeitspaket 3 — `engine` cross-dialekt nicht vergleichen

**Befund (bestätigt, Punkt 6).** `TableComparator.kt:101-102` vergleicht
`left.metadata == right.metadata` als Ganzes. MySQL setzt
`TableMetadata(engine = …)` (`MysqlSchemaReader.kt:233`), PG und MSSQL setzen
nichts → pro Tabelle ein Fund (vom Konsumenten als vier Info-Funde gemeldet).

**Umsetzung.** `engine` nur vergleichen, wenn **beide** Seiten einen tragen.
PG *kann* keinen Engine setzen — der Vergleich fragt dort etwas Unmögliches.
Innerhalb von MySQL bleibt der Vergleich scharf.

**Akzeptanz.** PG-Reverse gegen MySQL-Reverse meldet keine Engine-Änderung;
MySQL gegen MySQL mit unterschiedlichem Engine weiterhin schon.

## Arbeitspaket 4 — `custom_types … REMOVED` präziser benennen

**Befund (bestätigt, Punkt 7).** `SchemaComparator.kt:35-38` vergleicht die
`customTypes`-Maps schlüsselweise. PG führt den Enum als Typ, MySQL inline an
der Spalte → `customTypesRemoved`, obwohl der Wertevorrat nicht verloren ist.
Die ausgleichende `EnumCheckProjection` ist projektionsgebunden
(`TableComparator.kt:377-378`) und greift im strikten Modus nicht.

**Umsetzung (bewusst klein).** Die **Benennung** entschärfen: der Fund sagt,
dass der Typ auf der Gegenseite nicht als Custom-Type geführt wird — nicht,
dass er entfernt wurde. Die Äquivalenz Enum↔inline **nicht** herstellen; das
wäre dieselbe Designentscheidung wie unten und gehört nicht in diesen Slice.

**Akzeptanz.** Der Fund für einen nur-inline geführten Enum spricht von
„nicht als Custom-Type vorhanden" statt „entfernt".

## Bewusst nicht in diesem Slice

- **Rohe CHECK-Ausdrücke** (Punkt 1, größter Einzelposten: 4 von 8 FPs pro
  PG-Vergleich). Ohne Faltung bleiben `email like '%@%'` und
  `((email)::text ~~ '%@%'::text)` verschieden. Eine Operator-/Cast-
  Kanonisierung wäre der leichte Anfang — das **Vollverständnis** gehört zu
  [`../open/check-ausdruck-analyse-per-parser.md`](../open/check-ausdruck-analyse-per-parser.md)
  (JSQLParser-Port, Eigner-Entscheidung über eine neue Laufzeit-Abhängigkeit).
- **View-Body-Faltung** (Punkt 4). Braucht dieselbe Entscheidung.
- **`RESTRICT` gegen implizit** (Punkt 3). MySQL faltet `RESTRICT` und
  `NO ACTION` beide auf `null` — dort sind sie Synonyme, die Faltung ist also
  **korrekt**. Erst cross-dialekt entsteht daraus ein Verlust, und sauber
  auflösen lässt er sich nur, wenn man die Semantik-Frage entscheidet:
  Ist `RESTRICT` für den Vergleich dasselbe wie „weggelassen"? PG unterscheidet
  sie (PG faltet nur `NO ACTION` auf `null`). Solange das offen ist, wird
  **nichts** gleichgesetzt — lieber ein Fund zu viel als ein stiller Verlust.
- **MSSQL-View ohne `columns`** (Punkt 5, halb): verursacht wirklich einen FP
  (`SchemaComparator.kt:164` vergleicht `left.columns != right.columns`), ist
  aber eine **Datenlücke beim Lesen**, kein Vergleichsthema — eigener Fix im
  MSSQL-Adapter. **`dependencies`** verursacht gar keinen FP (`ViewDiff` hat
  kein solches Feld); ebenfalls reine Datenvollständigkeit.
- **Der strikte Modus selbst.** Ihn für „zwei Reverses" zu öffnen — die
  Faltung also auch dort zuzuschalten — ist eine **Vertragsänderung** von
  `schema compare` und braucht eine Eigner-Entscheidung samt
  `spec/cli-spec.md`-Anpassung. Siehe unten.

## Offene Eigner-Entscheidung (nicht Teil dieses Plans)

Der eigentliche Hebel wäre, `schema compare` erkennen zu lassen, dass **beide**
Seiten Reverses sind, und dann dieselbe Faltung wie `migrate` zu nutzen — mit
der etablierten Linie „unentscheidbar heißt: nichts melden, aber es sagen"
(`W137`, für Computed schon gebaut). Der Anker dafür **existiert bereits**:
`ReverseMarkerNormalizer.isNormalized()` (`ReverseMarkerNormalizer.kt:84-85`)
ist genau dafür kommentiert („Used by reports / metadata blocks that want to
flag *this side was reverse-generated*") — hat aber **keinen einzigen
Produktionsnutzer**. Der Haken ist vorbereitet und nie angeschlossen.

Dagegen steht die Zusage des strikten Modus. Ein Fund, den man nicht mehr
sieht, ist schwerer zu bemerken als einer zu viel — deshalb bleibt das eine
bewusste Entscheidung und fällt nicht als Nebeneffekt dieses Slices.

## Reihenfolge

1. Arbeitspaket 1 (ReferentialAction) — klar abgrenzbar, sofort nachmessbar,
   wirkt am stärksten bei MSSQL↔MySQL (dort steht die Quote mit 30 % am
   höchsten und die übrigen Punkte fehlen).
2. Arbeitspakete 2 und 3 (Herkunfts-/Engine-Felder) — klein, unabhängig.
3. Arbeitspaket 4 (Benennung) — kosmetisch, zuletzt.

Jedes Paket wird **sabotage-verifiziert** (Fix entfernen, Fehlschlag sehen,
zurücksetzen) und die Nachmessung dem Konsumenten angeboten.

## Umgesetzt (2026-09-14)

Arbeitspakete 1–3 sind gebaut und sabotage-verifiziert; Paket 4 ist als
Textpräzisierung mitgegangen.

**AP1 — ReferentialAction.** `SchemaReaderUtils.toReferentialAction` bildet
`"NO ACTION"` jetzt auf `null` ab statt auf `ReferentialAction.NO_ACTION`. Der
**KDoc dokumentierte diese Absicht bereits** („Returns null for NO ACTION or
unknown values") — der Code tat es nicht; das war die eigentliche Ursache. Alle
drei Reader gehen durch diese eine Funktion, der Fix wirkt also überall.
`ReferentialAction.NO_ACTION` bleibt als Wert bestehen: der Render-Pfad *setzt*
ihn (Cascade-Neutralisierung auf MSSQL), er wird nur nicht mehr aus einem Read
erwartet.

Gefunden dabei: `MssqlMigrateRoundTripIntegrationTest` trug eine Krücke — er
setzte `NO_ACTION` ins Soll, weil der Katalog es so meldete, und kommentierte
das als „sonst tauscht der Plan den Fremdschlüssel zusätzlich aus". Der
Kommentar ist jetzt umgekehrt: die Aktionen entfallen, der Vergleich sieht so
oder so kein geändertes Paar.

**AP2 — `sourceDialect`.** Wird nicht mehr verglichen (`SchemaComparator`,
`compareView`). Er beschreibt, *woher* ein Objekt gelesen wurde.

**AP3 — `engine`.** `metadataChangeOrNull` nimmt das Feld aus dem Vergleich,
wenn auch nur eine Seite keinen Engine trägt. **Ein eigener Test fand hier
einen Bug in der ersten Fassung:** `TableMetadata(engine = null)` ist nicht
`null` — beim echten Fall (PG führt gar kein Metadaten-Objekt, MySQL eines mit
Engine) hätte der Fix nichts geholfen. `null` und ein `TableMetadata` aus
lauter Defaults gelten jetzt als gleich.

Folge für `RenameProjectionReportTest`: er nutzte einen einseitigen Engine als
„strukturellen Mismatch", um den drop+create-Fallback zu provozieren. Das ist
nach AP3 keiner mehr — zu Recht, denn cross-dialekt daraus drop+create zu machen
wäre Datenverlust. Der Test nutzt jetzt zwei **verschiedene** Engines.

**AP4 — `custom_types`-Benennung.** Der MCP-Fund heißt jetzt „is not present as
a custom type on the other side" statt „was removed". Code und Pfad bleiben
(Wire-Contract), nur die Aussage wird ehrlich: ein Dialekt ohne benannte Typen
führt denselben Wertevorrat inline an der Spalte.

**Nicht angefasst** (wie geplant): die rohen CHECK-/View-Texte, `RESTRICT`
gegen implizit, die MSSQL-View-Datenlücke, und die Vertragsänderung am
strikten Modus. Alle vier stehen oben begründet.

**Nachmessung offen:** Die Wirkung auf die FP-Quote (Ausgangslage 38/38/30 %)
kann nur der Konsument messen — der Slice ändert die Zahl der Funde, nicht ihre
Darstellung.

## Entscheidungsgrundlage: die rohen CHECK-Ausdruecke (2026-09-15)

Der groesste verbleibende Posten — im gemessenen Vergleich 1 von 13 Funden
(`order_items_quantity_check`). Gebaut, **gemessen** und wieder
**zurueckgenommen**, weil er eine dokumentierte Linie kippt:

### Was der Kandidat leistet

`ExpressionCanonicalisationCandidateTest` haelt eine Kanonisierung als
**Kandidat** (nicht im Produktivpfad) und misst sie an den woertlichen Paaren
aus dem Konsumenten-Vergleich:

| Paar | Kanonisiert gleich? |
|---|---|
| `(quantity > 0)` / `quantity>(0)` | **ja** |
| `(email ~~ '%@%'::text)` / `email like '%@%'` | **ja** |

Und sie setzt **nicht** gleich, was sie nicht soll: verschiedene Literale,
verschiedene Operatoren, vertauschte Operanden, umgestellte Konjunktionen,
**gliedernde** Klammern (`(a + b) * c` bleibt von `a + b * c` verschieden) und
operator-aehnliche Folgen **im** Literal (`'a~~b'`).

### Warum sie trotzdem nicht drin ist

Sie bricht **fuenf Tests**, die die Gegenposition ausdruecklich festschreiben
(`SchemaComparatorRawTextProvenanceTest`):

- „ohne Herkunft ist die Schreibweise des Servers eine Aenderung — wie bisher"
- „ohne Auskunft wird konservativ geplant, nicht geraten"
- „eine andere Serverform ist sehr wohl eine Aenderung"
- „die Herkunft hat Vorrang — sie braucht den Server nicht"
- „ohne Serverform fuer dieses Feld bleibt es beim Textvergleich"

Das ist die Linie aus `RawTextFolding` („ohne Herkunft faltet nichts — es
bleibt beim Textvergleich, und der plant konservativ"), und sie ist **geteilt**:
dieselbe Faltung traegt `schema compare` **und** `schema migrate`.

### Die Kernfrage fuer den Eigner

**Gilt „Dialekt-Schreibweise ist keine Aenderung" auch fuer `schema migrate`
— oder nur fuer `schema compare`?**

Der Unterschied wiegt verschieden schwer:

- Bei `compare` kostet ein Fehlalarm einen Fund zu viel — laestig, harmlos.
- Bei `migrate` heisst „unentscheidbar → konservativ planen" das Gegenteil:
  eine **uebersehene** Aenderung laesst die Datenbank falsch stehen.

Wer zwei Serverformen gleichsetzt, die es nicht sind, verliert dort genau die
Absicherung, die bewusst gewaehlt wurde.

### Ein Befund aus dem Bauen, der in die Entscheidung gehoert

Die erste Fassung des Kandidaten hatte einen **Fehler, der echte Unterschiede
versteckt haette**: der Platzhalter fuer String-Literale war `" <n> "` — mit
Leerzeichen. Die Whitespace-Normalisierung zerstoerte ihn, die Literale wurden
nicht zurueckgelegt, und `note = 'a~~b'` wurde `note = 'a like b'` **gleich**.
Aufgefallen ist das nur, weil die Gegenprobe im Test stand.

**Das ist das Argument, das man beim Entscheiden mitwiegen sollte:** Eine
Kanonisierung, die zu viel gleichsetzt, ist gefaehrlicher als eine, die zu
wenig gleichsetzt — sie versteckt Unterschiede statt sie zu melden, und zwar in
beiden Pfaden. Ohne Test, der die **Grenze** mitpinnt, faellt das nicht auf.

### Was ein Schnitt braeuchte

1. Eigner-Entscheidung zur Kernfrage oben (bestimmt den ganzen Zuschnitt).
2. **ADR**, weil eine dokumentierte Linie wechselt.
3. Die **fuenf Tests** kodifizieren die alte Linie und muessen mit — sie sind
   der Grund, warum das auffaellt.
4. **Spec-Update** in `spec/ddl-generation-rules.md` fuer den Migrate-Pfad.
5. Der Kandidat-Test wandert in den Produktivpfad — oder entfaellt mit der
   Entscheidung.

## Die View-Seite ist breiter als oben notiert

Der Punkt „MSSQL-View ohne `columns`" steht oben als **Datenlücke** und damit
ausserhalb dieses Slices. Die Datenlücke ist mit 1.7.0 geschlossen (Spalten
aus `sys.columns`) — die **Vergleichsseite** aber blieb liegen, und damit die
zweite Hälfte jener Aussage. Sie ist im MCP-E2E-Harness reproduzierbar, auch
im Round-Trip **innerhalb** eines Dialekts, also ohne Dialektwechsel.

Die nicht-strukturellen Ursachen — die rohe Spalten-Wertung und das
abschliessende Semikolon eines zurückgelesenen Rumpfs — sind als
[`../next/konsumentenbefunde-170-skipped-schemaref-views.md`](../next/konsumentenbefunde-170-skipped-schemaref-views.md)
(P4) abgespalten und werden dort gebaut. **Hier bleibt der strukturelle Rest:**
die gliedernden Klammern, die Kleinschreibung und die Schemaqualifikation aus
MySQLs `VIEW_DEFINITION`. Sie sind dieselbe Eigner-Frage wie die rohen
CHECK-Ausdruecke oben — eine Kanonisierung, die `(a join b)` und `a join b`
gleichsetzt, ist keine Schreibweise mehr, sondern Struktur — und gehoeren
damit in die Entscheidung, die dieser Plan ohnehin offen fuehrt. Die Messung
und die Codestellen stehen im abgespaltenen Slice, nicht hier, damit beide
nicht auseinanderlaufen.

