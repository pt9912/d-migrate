# Cross-Dialekt-`schema compare`: Falsch-Positive abbauen (Slice A + konservative Fälle)

> **Status:** **Done — graduiert (2026-09-17)**, Eigner-Entscheidung vom selben
> Tag. AP1–AP4 sind gebaut, sabotage-verifiziert und mit **1.7.0**
> ausgeliefert. Die zwei Eigner-Punkte, die dieser Plan offen führte, sind mit
> ADR 0056 und ADR 0057 entschieden und im Folge-Slice gebaut (s. Nachtrag).
> Die Closure mit Paket → Commit steht am Ende. Jeder offene Punkt steht mit
> seinem Ort unter „Restflächen" direkt unter diesem Kopf.
> **Angelegt** am 2026-09-14 direkt in `../in-progress/`, mit dem ersten
> Implementierungs-Commit (`685f4ebc9`).
> **Graduiert** am 2026-09-17 (Move nach `../done/`).
> **Nachtrag 2026-09-17 — die zwei Eigner-Punkte dieses Plans sind entschieden
> und gebaut.** Die rohen CHECK- und Sichten-Texte (dazu das Index-Prädikat)
> faltet `schema compare` in der Dialekt-Schreibweise
> ([ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md),
> übersteuert ADR 0053); `schema migrate` und der Fingerabdruck bleiben
> streng — das beantwortet die „Kernfrage für den Eigner" unten. Der „strikte
> Modus selbst" ist für Reverses geöffnet: die Herkunft einer Seite ist kein
> Unterschied, und CLI, `schema_compare` und `schema_compare_start`
> vergleichen gleich
> ([ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md)).
> Gebaut und am Konsumenten-Repro nachgemessen im Slice
> [`compare-projektion-und-normalisierung.md`](compare-projektion-und-normalisierung.md)
> (graduiert). **Offen bleibt** `RESTRICT` gegen implizit — als opt-in-Toleranz
> K4 im [Toleranzprofil](../next/compare-toleranzprofil.md); vom strukturellen
> Rest der MySQL-Sichten deckt K1 die Schlüsselwort-Schreibung, Schemaqualifikation
> und gliedernde Klammern bleiben ein Fund (Parser-Frage). Die Abschnitte unten
> sind Stand vor diesen Entscheidungen.
> **Ziel:** Die gemessene Falsch-Positiv-Quote von `schema compare` zwischen
> zwei zurückgelesenen Schemata senken — ohne die Zusage des strikten Modus
> aufzuweichen.
> **Herkunft:** Die „Nicht in Scope"-Liste aus
> [`schema-compare-computed-expression-undecidable-silent.md`](schema-compare-computed-expression-undecidable-silent.md).
> Gemeldet von einem Konsumentenprojekt, der zwei Reverses gegeneinander
> vergleicht und eine Quote von **38 % (PG↔MSSQL) / 38 % (PG↔MySQL) /
> 30 % (MSSQL↔MySQL)** gemessen hat. Jeder der sieben Punkte wurde im Code
> nachgemessen (Bericht 2026-09-14), keiner war erfunden; zwei brauchten eine
> Präzisierung.

## Restflächen (2026-09-17)

**Nur ein Punkt ist Bauschuld von AP1–AP4:** eine Spec-Zeile, die AP1 hätte
mitziehen müssen (zweite Zeile unten). Alles Übrige ist eine Eigner- oder
ADR-Frage, eine Grenze, die der Plan bewusst gezogen hat, oder ein Befund, der
beim Nachfahren für die Graduation auffiel. Jeder Punkt hat einen Ort. Die
Abschnitte „Bewusst nicht in diesem Slice" und „Offene Eigner-Entscheidung"
bleiben als Stand vom 2026-09-14 stehen. Was aus jedem ihrer Punkte geworden
ist, steht in der Closure.

| Punkt | Ort |
| --- | --- |
| `RESTRICT` gegen implizit. Seit `e397aa132` nur noch zwischen einem Dialekt, der `RESTRICT` führt (PostgreSQL, MySQL, SQLite), und SQL Server oder Oracle | [Toleranzprofil](../next/compare-toleranzprofil.md), Kandidat K4 |
| `spec/ddl-generation-rules.md` sagt, der MSSQL-Reverse lese `no_action` zurück; seit AP1 liefert er `null`. Die Zeile hätte AP1 mitziehen müssen | Toleranzprofil K4, „Nebenbefunde" (Korrektur mit dem Spec-Schritt) |
| KDoc von `SchemaReaderUtils.toReferentialAction`: sagt seit `e397aa132` Falsches über MySQL und verweist auf `docs/planning/next/` für diesen Plan, wo er nie lag | Toleranzprofil K4, „Nebenbefunde" (Korrektur mit T5; der Pfad ist bei der Graduation dort nachgetragen) |
| Schlüsselwort-Schreibung in CHECK und Sichtrumpf (`is null` gegen `IS NULL`) | Toleranzprofil, Kandidat K1 |
| Struktureller Rest der MySQL-Sichten: Schemaqualifikation, `AS`-Aliase, gliedernde Join-Klammern | Toleranzprofil, „Geprüft und nicht aufgenommen"; braucht einen Parser: [`../open/check-ausdruck-analyse-per-parser.md`](../open/check-ausdruck-analyse-per-parser.md) |
| Vollverständnis roher CHECK-Ausdrücke (Parser statt Schreibweise) | [`../open/check-ausdruck-analyse-per-parser.md`](../open/check-ausdruck-analyse-per-parser.md) |
| Enum als benutzerdefinierter Typ gegen Inline-Enum. AP4 hat nur die Benennung entschärft | Toleranzprofil, „Geprüft und nicht aufgenommen" (hängt an K3 und ADR 0055) |
| MSSQL-Sichten ohne `dependencies`, die zweite Hälfte von Punkt 5. Kein Fehlalarm, aber der Migrate-Pfad liest das Feld | [`../open/mssql-sicht-abhaengigkeiten-nicht-gelesen.md`](../open/mssql-sicht-abhaengigkeiten-nicht-gelesen.md) (bei der Graduation angelegt) |
| `sourceDialect` an Funktionen, Prozeduren und Triggern wird weiter verglichen; AP2 galt nur für Sichten | [`../open/compare-source-dialect-routinen-trigger.md`](../open/compare-source-dialect-routinen-trigger.md) (bei der Graduation angelegt) |

**Bewusst ohne eigenen Ort.** Das sind keine offenen Arbeiten:

- **Die Quote selbst.** 38/38/30 % hat der Konsument gemessen, mit seinem
  Schema und seiner Zählung, und nur er kann sie nachmessen. Der Stand im Repo
  ist am Konsumenten-Repro des Folge-Slices gemessen (Closure, „Nachmessung").
- **`ReverseMarkerNormalizer.isNormalized`** hat weiterhin keinen
  Produktivnutzer. Der Haken war für eine Lösung gedacht, die ADR 0057 nicht
  gewählt hat: `SchemaCompareSemantics` erkennt die Markierung vor dem Entfernen
  (`ReverseScopeCodec.isReverseGenerated`) und vergleicht beide Seiten gleich,
  egal woher sie stammen. Der Helfer ist getestet und schadet nicht. Sein KDoc
  nennt allerdings Nutzer, die es nicht gibt.
- **`CHANGELOG.md` (1.7.0)** nennt den alten Pfad unter `in-progress/`. Der
  Eintrag ist veröffentlichte Historie und liegt außerhalb des
  `codepaths`-Scopes. Er bleibt stehen.
- **`consulted:` in ADR 0056 und ADR 0057** nennt den alten Pfad. Das
  Frontmatter akzeptierter ADRs ist eingefroren. Der Verweis ist historisch
  gemeint und bleibt stehen.

**Und die `Datei:Zeile`-Anker im Text sind Entwurfsstand (2026-09-14/15).**
Wer einen Beleg nachfährt, sucht über den Symbolnamen. Die zwei
Verdrahtungsstellen aus „Die Wurzel" (`SchemaCompareWiring`,
`McpRuntimeRegistries`) bauen den Comparator heute nicht mehr selbst. Das
geschieht in `SchemaCompareSemantics` (P11 des Folge-Slices).

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
[`konsumentenbefunde-170-skipped-schemaref-views.md`](konsumentenbefunde-170-skipped-schemaref-views.md)
(P4) abgespalten und **dort gebaut** (die Wirkung ist in 1.7.1 ausgeliefert; die
Abdeckung ist dort noch offen). **Hier bleibt der strukturelle Rest:**
die gliedernden Klammern, die Kleinschreibung und die Schemaqualifikation aus
MySQLs `VIEW_DEFINITION`. Sie sind dieselbe Eigner-Frage wie die rohen
CHECK-Ausdruecke oben — eine Kanonisierung, die `(a join b)` und `a join b`
gleichsetzt, ist keine Schreibweise mehr, sondern Struktur — und gehoeren
damit in die Entscheidung, die dieser Plan ohnehin offen fuehrt. Die Messung
und die Codestellen stehen im abgespaltenen Slice, nicht hier, damit beide
nicht auseinanderlaufen.

## Closure

**Graduiert 2026-09-17**, Eigner-Entscheidung vom selben Tag. AP1–AP4 sind
gebaut, sabotage-verifiziert (s. „Umgesetzt") und mit **1.7.0** ausgeliefert.
Was dieser Plan unter „Bewusst nicht in diesem Slice" und als Eigner-Frage
führte, ist entweder entschieden und gebaut oder hat unter „Restflächen" einen
Ort. In diesem Plan bleibt nichts offen.

**Woran „fertig" gemessen ist:** am Vertrag, nicht an diesem Plan.

- [LF-015](../../../spec/lastenheft-d-migrate.md#lf-015) (Schema-Vergleiche
  zwischen Umgebungen): Zwei Reverses verschiedener Dialekte melden keinen
  Unterschied mehr, der nur eine Lesekonvention ist. Das betrifft ein
  implizites gegen ein vom Server ausgeschriebenes `NO ACTION`, die Herkunft
  einer Sicht (`sourceDialect`) und einen `engine` auf nur einer Seite. Der
  `custom_types`-Fund behauptet keinen Verlust mehr. Gepinnt ist das in
  `SchemaComparatorCrossDialectReverseTest` (Comparator) und in
  `CrossDialectReverseCompareE2ETest` (zwei echte Server, Reader → Compare).
  Beide enthalten die Gegenprobe, dass ein echter Unterschied ein Fund bleibt:
  der Identity-Modus, eine fehlende Constraint, `CASCADE` gegen eine
  weggelassene Aktion und ein von Hand geschriebenes `NO ACTION` gegen ein
  weggelassenes.
- [ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md)
  beantwortet die „Kernfrage für den Eigner" aus der Entscheidungsgrundlage:
  Die Dialekt-Schreibweise faltet nur `schema compare`, `schema migrate` und
  der Fingerabdruck bleiben streng. Die Faltungsmenge steht in
  `spec/cli-spec.md`.
- [ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md)
  öffnet den „strikten Modus selbst", aber anders als unter „Offene
  Eigner-Entscheidung" skizziert. Es gibt keine Erkennung „beide Seiten sind
  Reverses" mit eigener Faltung. Stattdessen vergleichen CLI, `schema_compare`
  und `schema_compare_start` nach einer Semantik, und die Herkunft einer Seite
  ist kein Unterschied.

**Paket → Commit**

| Paket | Inhalt | Commit | Release |
| --- | --- | --- | --- |
| AP1–AP4 und Plan | `NO ACTION` wird beim Lesen `null` (`SchemaReaderUtils.toReferentialAction`); `sourceDialect` an Sichten und ein einseitiger `engine` werden nicht verglichen; der `custom_types`-Fund sagt „not present as a custom type"; zwei Tests mit Krücken auf echte Unterschiede umgestellt | `685f4ebc9` | 1.7.0 |
| Absicherung | zwei Reverses als eigener Fall, mit Gegenproben (`SchemaComparatorCrossDialectReverseTest`) | `52cc161a9` | 1.7.0 |
| Absicherung | die Kette echte DB → Reader → Compare gegen PostgreSQL und SQL Server (`CrossDialectReverseCompareE2ETest`) | `0c80fbc12` | 1.7.0 |
| Entscheidungsgrundlage | Kandidat `ExpressionCanonicalisationCandidateTest` und Abschnitt im Plan | `146d2713d` | 1.7.0 |
| Nachträge | Abschnitt „Die View-Seite" · Verweise auf den Konsumentenbefunde-Slice · Nachtrag zu ADR 0056/0057 | `ef78036e6` · `5de1f7c6b`, `12119d887` · `d680287c4` | — |
| Graduation | zwei Orte in `open/` · Move mit Closure | `a091f96f2` · der Move-Commit | — |

**Wo die Punkte aus „Bewusst nicht in diesem Slice" gelandet sind**

| Punkt | Ausgang | Commit |
| --- | --- | --- |
| Rohe CHECK-Ausdrücke (Punkt 1) | Schreibweise-Faltung nur für `schema compare`, festgeschrieben in ADR 0056; seit P11 des Folge-Slices an einer Stelle (`SchemaCompareSemantics`) | `0bc3a323e` (1.7.0); ADR `c9737f909` (unveröffentlicht) |
| View-Body-Faltung (Punkt 4) | Quoting und Whitespace, eng gefasst (`ViewQueryCanonicalisationTest`) | `453dc5ff3` (1.7.0) |
| `RESTRICT` gegen implizit (Punkt 3) | Die Prämisse hielt für den Katalog nicht: MySQL 9.7.2 führt `RESTRICT` getrennt von `NO ACTION`. Der Reader behält es jetzt, und zwischen PostgreSQL und MySQL gibt es keinen Fund mehr. Übrig bleibt die Paarung mit SQL Server oder Oracle | `e397aa132` (1.7.0); Rest: Restflächen |
| MSSQL-Sicht ohne `columns` (Punkt 5, Datenlücke) | Spalten aus `sys.columns` | `1b7e5f517` (1.7.0) |
| Sichten, Vergleichsseite (Spalten nach Namen, abschließende Semikola) | Konsumentenbefunde-170, P4. Die Abdeckung, die „Die View-Seite" noch offen nennt, ist dort in `9c063f19` geschlossen | `8a246e92b` (1.7.1) |
| MSSQL-Sicht ohne `dependencies` (Punkt 5) | nicht gebaut | Restflächen |
| Der strikte Modus selbst | ADR 0057; eine Semantik in `SchemaCompareSemantics` (P11 des Folge-Slices) | `2601d1631`; ADR `f6bab1514` (beide unveröffentlicht) |

**Der Fahrplan „Was ein Schnitt braeuchte", Punkt für Punkt**

1. **Eigner-Entscheidung.** Die Antwort „nur `schema compare`" wurde zuerst
   gebaut: `0bc3a323e`, drei Stunden nach der Entscheidungsgrundlage, und in
   1.7.0 ausgeliefert. ADR 0053 sagte zu der Zeit noch „`schema compare` bleibt
   streng". Einen Eigner-Beleg vor dem Bau gibt es im Repo nicht. Festgehalten
   ist die Entscheidung am 2026-09-16 im Folge-Slice („voller Umfang"), und sie
   bestätigt den gebauten Stand.
2. **ADR.** ADR 0056 (`c9737f909`) übersteuert ADR 0053 per Statusänderung.
3. **Die fünf Tests** in `SchemaComparatorRawTextProvenanceTest` sind
   unverändert (letzte Änderung 2026-09-10) und laufen weiter. Die Faltung ist
   nur im Compare-Pfad eingeschaltet, die alte Linie gilt im Migrate-Pfad
   weiter, und diese Tests pinnen sie dort. Dass `schema migrate` streng
   bleibt, pinnt zusätzlich `SchemaMigrateComparatorsTest` (Folge-Slice).
4. **Das Spec-Update in `spec/ddl-generation-rules.md` für den Migrate-Pfad
   entfiel**, weil `schema migrate` streng bleibt. Die Faltungsmenge von
   `schema compare` steht in `spec/cli-spec.md` (`0bc3a323e`, `453dc5ff3`;
   vollständig mit P7 des Folge-Slices, `26ff678ed`).
5. **Der Kandidat-Test ist in den Produktivpfad gewandert.** `0bc3a323e`
   löscht `ExpressionCanonicalisationCandidateTest` und legt
   `ExpressionCanonicalisationTest` an. Der neue Test prüft
   `ConstraintDiffContract` statt einer lokalen Kopie. Die Grenzfälle aus der
   Entscheidungsgrundlage sind weiter enthalten, darunter gliedernde Klammern
   und operator-ähnliche Folgen im Literal (`note = 'a~~b'`). Erweitert wurde
   er in `8a246e92b` (1.7.1) und `13e397475` (P9 des Folge-Slices). Den
   Kandidaten gibt es nicht mehr. Der Kopfkommentar des Tests verweist seit der
   Graduation für die Begründung auf ADR 0056 statt auf diesen Plan.

**Nachmessung der Quote (38/38/30 %)**

- **Ausgangslage.** Die Zahlen stammen aus dem Wiederholungs-Audit des
  Konsumenten, `v1.5.1` gegen die Baseline `v1.2.0` (s. „Herkunft"). Sie geben
  den Anteil der Funde an, die der Konsument als falsch-positiv gezählt hat.
- **Die Zahl überschätzt.** Die Absicherung zu AP1–AP4 hat gezeigt, dass ein
  Teil der gezählten Fehlalarme echte Unterschiede waren. Im gemessenen Schema
  fehlten eine Constraint und eine berechnete Spalte auf der Gegenseite
  wirklich (Skip über `E057`/`E053`; `52cc161a9`, `0c80fbc12`).
- **Was der Folge-Slice gemessen hat.** Am Konsumenten-Repro (Schema des
  Konsumenten, über MCP) sank die Zahl der Funde von `schema_compare` zwischen
  1.7.1 und dem Stand bei dessen Graduation: PG↔MSSQL 23 → 17,
  PG↔MySQL 20 → 19, PG↔SQLite 36 → 35, MSSQL↔MySQL 19 → 18
  ([dort, Closure](compare-projektion-und-normalisierung.md#closure),
  „Abnahme"). Das sind Fundzahlen, keine Quoten. Die Ausgangslage 1.7.1
  enthält AP1–AP4 und die Faltung aus 1.7.0 bereits. Die Wirkung dieses Plans
  allein ist dort also nicht getrennt gemessen. Welche Funde bleiben und warum,
  ordnet dort die Tabelle „Konsumenten-Repro (Verifikation 3)" je Fund ein
  (nachgebautes Schema, CLI).
- **Die Quote selbst kann nur der Konsument messen.** Dafür braucht es sein
  Schema, seine Zählung und seine Einordnung, was als falsch-positiv gilt. Das
  Repo hat keine eigene Quote, und dieser Plan behauptet keine.

**Was über den Entwurf hinausging**

- **AP1** fand die Ursache in einem Widerspruch zwischen Code und KDoc: Laut
  KDoc sollte `toReferentialAction` `NO ACTION` auf `null` abbilden, der Code
  tat es nicht. Dabei fiel eine Krücke in
  `MssqlMigrateRoundTripIntegrationTest` weg.
- **AP3** hatte in der ersten Fassung einen Fehler, den ein eigener Test fand:
  `TableMetadata(engine = null)` ist nicht `null`.
- **`RESTRICT` war für MySQL keine Eigner-Frage.** Die Annahme, auf der die
  Frage beruhte, war messbar falsch (`e397aa132`). Übrig bleibt eine
  Fähigkeitsfrage gegenüber SQL Server und Oracle.
- **Die Absicherung hat die Quote relativiert** (s. „Nachmessung").

**Gates der Graduation:** `make docs-check` und `make doc-planning` mit je
0 Befunden, `make solid-suppression-gate` grün, `make doc-immutable` im frischen
`--no-local`-Klon über `00b64a4a6..HEAD` grün. Geändert sind nur Pläne und ein
Testkommentar, deshalb war kein Bau nötig. `00b64a4a6` war bei der Graduation
gepusht, die Graduations-Commits nicht.

**Was von diesem Plan lesenswert bleibt.** Der Fahrplan für die rohen Texte
legte eine Reihenfolge fest: Eigner, ADR, Spec, Tests. Gebaut wurde zuerst, und
der Code wurde ausgeliefert, während ein akzeptierter ADR das Gegenteil sagte.
Kein Gate hat das bemerkt, weil kein Gate den Inhalt eines ADRs mit dem Code
vergleicht. Der Folge-Slice fand den Widerspruch bei seiner Aktivierung und hat
ihn mit ADR 0056 geschlossen. Das Ergebnis stimmt mit der Entscheidung überein,
aber bis dahin stand der Widerspruch im Repo.
