# `schema compare`: Toleranzprofil — offene Grenzfragen per Konfiguration steuern

> **Status:** Entwurf mit Scope (2026-09-17). Grundlage ist der Eigner-Auftrag vom
> 2026-09-17: Die Grenzfragen, die der aktive Compare-Slice offen lässt, sollen
> **nicht einmal für alle** entschieden werden. Stattdessen sollen sie über eine
> Erweiterung der Konfigurationsdatei steuerbar werden.
> **Ziel:** Wer zwei Reverses verschiedener Dialekte vergleicht, kann benannte
> Gleichsetzungen **ausdrücklich** zuschalten. Ohne Angabe bleibt
> `schema compare` so streng wie heute. Jedes Ergebnis nennt die Toleranzen, unter
> denen es entstanden ist.
> **Anforderung:** [`LF-015`](../../../spec/lastenheft-d-migrate.md#lf-015)
> (Schema-Vergleiche zwischen Umgebungen); die Oberflächen folgen
> [`LF-012`](../../../spec/lastenheft-d-migrate.md#lf-012) (CLI) und dem
> MCP-Vertrag.
> **Vorbedingung / Gate:** Es gibt drei, und keine davon liefert dieser Plan:
> 1. **Ein neuer ADR (architect).** Er legt das Profil als Vergleichslinie fest.
>    Für den Kandidaten K3 braucht er zusätzlich eine **Statusänderung** an
>    [`ADR 0055`](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md)
>    und [`ADR 0056`](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md)
>    (s. „Entscheidungsbedarf und Gate"). Die Spec zieht mit dem ADR nach.
> 2. **Die Eigner-Fragen E1 bis E7** (s. dort). Mindestens muss feststehen,
>    welche Kandidaten ins Profil kommen.
> 3. **Die Graduation des aktiven Slices**
>    [`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md).
>    Das Profil baut auf dessen Faltung, Pfad-Schema und `details`-Format auf
>    und setzt voraus, dass beide MCP-Oberflächen gleich vergleichen (s.
>    „Abhängigkeit").
> **Aktivierung:** Der Plan wandert mit dem ersten Implementierungs-Commit nach
> `../in-progress/`, frühestens wenn alle drei Vorbedingungen erfüllt sind.
> **Belegart:** Die heutige Wirkung jedes Kandidaten ist am Code (Stand
> `d14f7021b`) und am Konsumenten-Repro des aktiven Slices nachgelesen. Das
> Semantik-Risiko ist aus den Dialektregeln hergeleitet und für K2 und K4 **nicht**
> gegen einen Server gemessen (s. „Verifikation", Punkt 4).

## Warum ein Profil statt einer Entscheidung

Für die Kandidaten unten sind beide Antworten vertretbar. Ob ein PostgreSQL-`RESTRICT`
für ein bestimmtes Projekt etwas anderes bedeutet als eine weggelassene Aktion,
kann nur der Anwender beurteilen. [ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md)
begründet, warum `schema compare` hier nachgiebiger sein darf als
`schema migrate`: Bei `schema compare` kostet ein Fehlalarm einen Fund, bei
`schema migrate` kostet eine übersehene Änderung eine falsch stehende Datenbank.
Dieselbe Abwägung bleibt aber **innerhalb** von `schema compare` ungelöst, wenn
die Gleichsetzung nicht mehr nur die Schreibweise betrifft. Dort soll der
Anwender entscheiden.

Daraus folgt eine Pflicht: **Ein `IDENTICAL` unter Toleranz ist ein anderes
`IDENTICAL`.** Wer zwei Ergebnisse nebeneinanderlegt, muss sehen können, ob sie
unter denselben Toleranzen entstanden sind. Die Kennzeichnung ist deshalb
Vertragsbestandteil und keine Zutat (s. „Kennzeichnung im Ergebnis").

### Vier Mechanismen, die nicht verwechselt werden dürfen

| Mechanismus | Wirkung | Wer entscheidet | Normativer Ort |
| ----------- | ------- | --------------- | -------------- |
| Schreibweise-Faltung | in `schema compare` **immer** | der Vertrag | ADR 0056, [`spec/cli-spec.md`](../../../spec/cli-spec.md) (Abschnitt `schema compare`) |
| Herkunftsfelder (Identity-Sequenzname, serial-Flag, `sourceDialect`) | werden **nie** als Schemaeigenschaft verglichen | der Vertrag | aktiver Slice (P6, P10), `spec/cli-spec.md` |
| Präferenz | an der Wurzel (Reverse, Schreiben) — was im Modell **steht** | der Anwender | [ADR 0027](../../adr/0027-reverse-preferences-inhaerente-mehrdeutigkeit.md), [`spec/dialect-preference-mechanism.md`](../../../spec/dialect-preference-mechanism.md) |
| **Toleranz (neu)** | nur in der Vergleichsentscheidung, nur wenn zugeschaltet | der Anwender, je Lauf oder Projekt | neuer ADR |

Eine **Toleranz** setzt etwas gleich, das eine andere Aussage sein **kann** oder
dessen Grenze zu einer anderen Aussage sich lexikalisch nicht sicher ziehen
lässt. Der Anwender nimmt das bewusst in Kauf. Eine **Faltung** darf das nach
ADR 0056 nie tun. Eine **Präferenz** ändert, was der Reverse ins Modell
schreibt; der Vergleich bleibt davon unberührt. Die Präferenz-Spec schließt für
**inhärente Mehrdeutigkeiten** ausdrücklich „eine tolerante Vergleichs-Faltung"
aus. Wo ein Kandidat an diese Grenze stößt, steht es bei ihm (K2).

## Kandidaten

Alle vier Kandidaten stehen standardmäßig auf **aus**. Ohne Angabe ist das
Ergebnis also dasselbe wie heute. Die Schlüsselnamen sind Vorschläge (E7).

### K1 — `keyword_case`: Groß-/Kleinschreibung von Schlüsselwörtern

- **Beispiel:** `((shipped_at IS NULL) OR (shipped_at >= placed_at))`
  (PostgreSQL) gegen ``((`shipped_at` is null) or (`shipped_at` >= `placed_at`))``
  (MySQL). Weitere Fälle: der Sichten-Rumpf `SUM(total)` gegen `sum(total)`
  (PostgreSQL gegen SQL Server) und `email LIKE '%@%'` gegen `email like '%@%'`
  (PostgreSQL gegen SQLite, nachdem `~~` gefaltet ist).
- **Heute:** Fund. Im Repro des aktiven Slices betrifft das
  `ck_order_ship_after_place` in PG↔MySQL und MSSQL↔MySQL sowie
  `ck_customer_email_shape` in PG↔SQLite. ADR 0056 lässt die Frage ausdrücklich
  offen („lässt ihre Faltung weder zu, noch schließt er sie aus"), und
  `spec/cli-spec.md` führt sie unter „Nicht festgelegt".
- **Felder:** CHECK/EXCLUDE, Index-Prädikat und Sichten-Rumpf. Der Ausdruck
  eines Index-Schlüssels gehört **nicht** dazu; er bleibt nach ADR 0056
  wortgleich.
- **Semantik-Risiko:** Echte Schlüsselwörter unterscheiden in keinem der fünf
  Dialekte zwischen Groß- und Kleinschreibung. Das Risiko liegt an der **Grenze
  zum Bezeichner**, und die ist konstruktiv heikel:
  - Das Gerüst packt einfache quotierte Bezeichner aus (`RawSqlSkeleton.kt:206`,
    Muster `SIMPLE_IDENTIFIER`). Danach lässt sich `"SUM"` nicht mehr von einem
    unquotierten `SUM` unterscheiden. Eine Kleinschreibung auf dem Gerüst würde
    deshalb `"SUM"(x)` mit `sum(x)` gleichsetzen, in PostgreSQL sind das zwei
    verschiedene Funktionen. Die Toleranz darf nur Wörter falten, die im
    Quelltext **unquotiert** standen, und das Gerüst muss diese Information
    tragen.
  - `sum` und `count` sind Funktionsnamen, keine Schlüsselwörter. Eine allgemeine
    Faltung unquotierter Wörter wäre eine Faltung der Bezeichner-Schreibung, und
    die ist ausgeschlossen: Unter SQL Server mit case-sensitiver Collation und
    für MySQL-Tabellennamen unter Linux sind `Col` und `col` verschiedene
    Objekte. Nötig ist deshalb eine **geschlossene Wortliste**, nämlich
    `SqlKeywords` plus eine benannte Liste eingebauter Funktionen. Die Liste
    gehört zum Spec-Vertrag.
  - Ein Wort hinter `.` ist ein qualifizierter Name und wird nie gefaltet
    (`dbo.SUM(x)` gegen `dbo.sum(x)`).
  - Typnamen gehören nicht zur Liste. Die Cast-Regel aus P9 wertet Typnamen nur
    in Kleinschreibung, weil das Gerüst `"TEXT"` zu `TEXT` entpackt. Ob K1 mit
    dem Quotierungsmerker auch unquotierte Typnamen falten darf, entscheidet der
    ADR. Bis dahin bleibt `'NEW'::VARCHAR` gegen `'NEW'::varchar` ein Fund.
- **Default:** aus. K1 ist der einzige Kandidat, den der ADR auch als festen
  Teil der Schreibweise-Faltung festlegen könnte, denn ADR 0056 lässt beides
  offen. Der Eigner-Auftrag sieht das Profil vor (E1).
- **Gegenproben:** `"SUM"(x)` gegen `sum(x)`, `dbo.SUM(x)` gegen `dbo.sum(x)`,
  `Quantity > 0` gegen `quantity > 0`, `"AMT" > 0` gegen `amt > 0` (Oracle),
  `'IS NULL'` gegen `'is null'` (Literal) und ein Text mit Kommentar (Rückzug
  vor K1). Alle bleiben Funde.

### K2 — `identity_mode`: Modus einer Identity-Spalte über Dialekte

- **Beispiel:** PostgreSQL `generation: {type: identity, mode: by_default}` gegen
  das SQL-Server-Reverse derselben Spalte mit `mode: always`.
- **Heute:** Fund an jeder Identity-Spalte in PG↔MSSQL und MSSQL↔MySQL (Repro).
  Der Generator rendert `by_default` für SQL Server als `IDENTITY(1,1)` und
  meldet dabei `W140`. Der Reverse liest das als `always`
  (`MssqlTypeMapping.kt:72`; T-SQL erlaubt kein explizites INSERT ohne
  `SET IDENTITY_INSERT`). **Eigner-Entscheidung (2026-09-16): Ohne Toleranz bleibt
  das ein Fund.** Dazu kommt: MySQL und SQLite setzen beim Lesen keinen Modus
  (`MysqlTypeMapping.kt:42`, `SqliteTypeMapping.kt:58`, also der Default
  `by_default`). Eine PostgreSQL-Spalte mit `always` erscheint dort
  deshalb ebenfalls als `by_default`. Ob der Generator das meldet, ist hier nicht
  geprüft. Oracle liest den Modus treu (`OracleTypeMapping.kt:82`).
- **Semantik-Risiko:** Der Modus ist eine echte Eigenschaft. `always` weist
  explizite Werte ab (PostgreSQL: außer mit `OVERRIDING SYSTEM VALUE`),
  `by_default` nimmt sie an, und Import und Transfer verhalten sich
  entsprechend verschieden. Eine **globale** Toleranz verdeckt auch einen echten
  Moduswechsel PG↔PG. Die **fähigkeitsgebundene** Variante verdeckt ihn nicht: Sie faltet nur, wenn die Reverse-Markierung einer Seite einen Dialekt
  nennt, der einen der beiden Modi nicht kennt (E3).
- **Grenze:** K2 faltet nur `mode`. Der Sequenzname (P6), `legacy_serial_syntax`
  (P10) und `stored` gehören nicht dazu. Ob `int IDENTITY` als
  `identifier(auto)` oder als `integer` mit Identity gilt, ist eine Typfrage und
  kein Teil von K2.
- **Default:** aus (Eigner, 2026-09-16).
- **Konflikt mit ADR 0027:** Ist die MSSQL-Lesung eine **inhärente
  Reverse-Mehrdeutigkeit**, dann ist nach ADR 0027 und
  `spec/dialect-preference-mechanism.md` das richtige Werkzeug eine
  Reverse-Präferenz (etwa `reverse.mssql.identity_mode`) und keine
  Vergleichstoleranz. Spricht man ihr die Mehrdeutigkeit ab (T-SQL-IDENTITY
  ist eine dritte Semantik und keiner der beiden Modi), ist die Toleranz
  zulässig. Der ADR muss sich dann aber ausdrücklich von ADR 0027 abgrenzen (E2).
- **Gegenproben:** Sequenzname, Legacy-Flag und `stored` bleiben von K2
  unberührt. `identifier(auto)` gegen `biginteger` mit Identity bleibt ein
  Typ-Fund. Bei fähigkeitsgebundener Variante bleibt ein Moduswechsel PG↔PG und
  PG↔Oracle ein Fund.

### K3 — `value_list_form`: `= ANY(ARRAY[…])`, `IN (…)` und `OR`-Kette

- **Beispiel:** `status = ANY (ARRAY['NEW'::text, 'PAID'::text])` (PostgreSQL)
  gegen `status IN ('NEW','PAID')` (SQLite, MySQL) gegen
  `status='PAID' OR status='NEW'` (SQL Server), jeweils in einem CHECK und in
  einem Index-Prädikat (`ix_order_open`).
- **Heute:** Fund auf jedem Pfad. Im Repro betrifft das `ck_order_status` und
  `ix_order_open` in allen gemessenen Paarungen. Im zielbewussten Vergleich
  (`schema migrate`) setzt `EnumCheckProjection` dieselben Formen gleich
  (ADR 0055).
- **Semantik-Risiko:** Für eine Liste aus String-Literalen über **eine** Spalte
  beschreiben die drei Formen dieselbe Menge. Es bleibt trotzdem eine
  **Umschreibung** und keine Schreibweise, und die Typauflösung unterscheidet
  sich: Unter PostgreSQL löst `IN` die Liste gemeinsam auf, `ARRAY[…]` als
  Array. P9 hat gegen PostgreSQL gemessen, dass Casts an Elementen die Operation
  umtypen können (`citext`, `char(n)`, `bpchar`). `NULL` in der Liste, `NOT IN`
  gegen `<> ALL` und Zahlenlisten sind nicht Teil von K3.
- **Konstruktionsgrenze:** Es gibt nur **einen** Erkenner, nämlich
  `EnumCheckProjection` (ADR 0055: „Die Regel steht an einer Stelle"). Dessen
  Cast-Behandlung ist aber kontextfrei; laut KDoc castet er bei einer
  `varchar`-Spalte auch die Spalte. In `schema compare` gilt dagegen die
  Cast-Regel aus P9 (nur mit dem Spaltentyp dieser Seite). Die
  Wiederverwendung darf die kontextfreie Cast-Streichung, die P9 entfernt hat,
  nicht zurückbringen.
- **Default:** aus.
- **Linie:** Das ist der **harte** Fall (s. „Entscheidungsbedarf und Gate").
  ADR 0056 und ADR 0055 schließen die Gleichsetzung für `schema compare`
  kategorisch aus.
- **Gegenproben:** Andere Wertemengen, `NOT IN` gegen `<> ALL`, Zahlenlisten,
  ein Array aus einer Spalte, `::text`-Elemente an einer `citext`-Spalte und
  `::bpchar`-Elemente bleiben Funde. Der zielbewusste Vergleich bleibt
  unverändert.

### K4 — `restrict_as_no_action`: `RESTRICT` gegen implizite Aktion

- **Beispiel:** PostgreSQL `on_delete: restrict` gegen das SQL-Server-Reverse
  desselben Fremdschlüssels ohne Aktion. SQL Server kennt kein `RESTRICT`, der
  Generator schreibt `NO ACTION` (`MssqlDdlGenerator.kt:90`,
  [`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md):
  „semantisch identisch, da SQL Server keine aufschiebbaren Constraints hat").
  Oracle kennt es ebenfalls nicht (`OracleColumnConstraintHelper`).
- **Heute:** Fund. Die Reader behalten `RESTRICT` und falten nur `NO ACTION` auf
  `null` (`SchemaReaderUtils.toReferentialAction`; MySQL seit `e397aa132`,
  `MysqlMetadataQueries.kt:118`). Betroffen sind alle Paarungen eines Dialekts
  mit `RESTRICT` (PostgreSQL, MySQL, SQLite) mit SQL Server oder Oracle, am
  Constraint wie an der einspaltigen `references`. `spec/cli-spec.md` führt die
  Frage unter „Nicht festgelegt", der Umbrella-Slice unter „Bewusst nicht in
  diesem Slice".
- **Semantik-Risiko:** Unter PostgreSQL prüft `RESTRICT` sofort und lässt sich
  nicht aufschieben. `NO ACTION` prüft am Ende der Anweisung oder aufgeschoben
  (`DEFERRABLE`). Für aufgeschobene Constraints und für Anweisungen, die beide
  Seiten ändern, ist das ein echter Unterschied. Unter MySQL/InnoDB sind beide
  Synonyme, unter SQL Server gleichwertig. Eine **globale** Toleranz verdeckt
  also einen echten PG↔PG-Wechsel, die **fähigkeitsgebundene** Variante (E3)
  nicht.
- **Default:** aus.
- **Nebenbefunde für den ADR- und Spec-Schritt:** Die KDoc von
  `SchemaReaderUtils.toReferentialAction` sagt weiter, MySQL falte `RESTRICT` auf
  `null`. Seit `e397aa132` stimmt das nicht mehr. `spec/ddl-generation-rules.md`
  sagt, der MSSQL-Reverse lese `no_action` zurück; der Reader liefert `null`.
- **Gegenproben:** `cascade`, `set_null` und `set_default` gegen `null` bleiben
  Funde. Bei fähigkeitsgebundener Variante bleibt auch `restrict` gegen `null`
  PG↔PG ein Fund.

### Geprüft und nicht aufgenommen

| Frage | Heute | Warum kein Kandidat |
| ----- | ----- | ------------------- |
| **MySQL-Sichtformatierung**, der Rest nach K1: Schemaqualifikation, `AS`-Aliase, gliedernde Join-Klammern | Fund | Braucht Namensauflösung und Struktur, also einen Parser (ADR 0056, Option E; [`../open/check-ausdruck-analyse-per-parser.md`](../open/check-ausdruck-analyse-per-parser.md)). Den Anteil der Schlüsselwort-Schreibung deckt K1. |
| **Unbenannte Indizes über die kanonische Form** (`TableIndexComparator.kt:96`, `indexKey` trägt das rohe Prädikat) | entfernt + hinzugefügt | Keine Anwenderwahl: Die Schreibweise-Faltung ist schon Vertrag (ADR 0056). Dass der Zuordnungsschlüssel sie nicht nutzt, ist eine Lücke in der bestehenden Regel. Sie gehört als Korrektur in einen eigenen Eintrag — angelegt als [`compare-unbenannte-indizes-kanonische-zuordnung.md`](../open/compare-unbenannte-indizes-kanonische-zuordnung.md). |
| **`stored` einer berechneten Spalte** | Fund | Physische Eigenschaft. Das Argument „keine Wahl" braucht Dialekt und Serverversion einer Zielseite. P6 hat `stored` bewusst sichtbar gelassen. |
| **Typabflachung je Dialekt** (SQLite-Affinität, `identifier(auto)`) | Fund (Nullfall PG↔SQLite) | [ADR 0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md) (D3) hält `schema compare` für Typen streng. Die Round-Trip-Projektion braucht einen Dialekt je Seite, und der Typ ist das wichtigste Vergleichssignal. Nur als eigener Kandidat mit eigener Eigner-Frage denkbar. |
| **Typ einer berechneten Spalte unter SQL Server** (`decimal(23,2)`) | Fund | Thema von Reader und Generator, kein Vergleichsthema (aktiver Slice, „Offen"). |
| **Enum als benutzerdefinierter Typ gegen Inline-Enum** | Fund in `custom_types` | Darstellungsfrage (Gegenstand von ADR 0055). Sie ändert die Struktur der Funde und nicht die Gleichheit eines Textes. Hängt an der Entscheidung zu K3 und gehört nicht in die erste Fassung. |
| **`SCHEMA_NAME_CHANGED` im MCP-`schema_compare`** (keine Bereinigung der Reverse-Markierung) | Fund | Defekt, keine Toleranz (aktiver Slice, „Offen"). |
| **`W137`** (unentscheidbarer Berechnungsausdruck) | gleich plus Diagnose | Eigene Regel, nach ADR 0056 unberührt. |

## Was ausdrücklich kein Schalter wird

Das Profil **erweitert nur**: Jede Toleranz setzt **zusätzlich** gleich. Keine
Toleranz lockert eine Korrektheitsgrenze, und keine schaltet die
Schreibweise-Faltung ab. Diese Grenzen wurden gemessen. Ein Schalter an ihnen
würde gemessene Falsch-Gleichsetzungen wieder einschalten.

| Grenze | Ort (aktiver Slice) | Gemessenes Gegenbeispiel |
| ------ | ------------------- | ------------------------ |
| Ein Cast fällt nur am Vergleich und nur mit dem Spaltentyp | P9 (`ColumnCasts`, `CastRules`) | `qty / 2::numeric > 1` gegen `qty / 2 > 1`; `email = 'FOO'::text` bei `citext`; `code = 'a  '::text` bei `char(3)` |
| Rückzug bei Kommentar, Dollar-Quoting, Backslash, offener Quotierung, zweideutigem `[`, Oracles `q'…'` | P8, B (`RawSqlSkeleton.of`) | `a = 1 -- x⏎AND b = 2` gegen `a = 1 -- x AND b = 2` |
| Literalschutz | P8 | `'a  b'` gegen `'a b'` |
| Quotierte Schlüsselwörter bleiben geschützt | C (`SqlKeywords`) | `"user"` gegen `user` |
| Nur redundante Operanden-Klammern fallen | P3 (`OperandParens`) | `(a + b) * c`; `BETWEEN`-Ebene |
| Bezeichner-Schreibung | ADR 0056 | `"Quantity"` gegen `quantity` |
| Index-Schlüssel-Ausdruck wortgleich | ADR 0056 | — |
| **Herkunftsfelder:** Sequenzname (P6), serial-Flag (P10) | P6, P10 | Kein Toleranzfall, sondern eine Einordnung: Das Feld beschreibt die Buchhaltung des Servers. Ein Schalter „trotzdem vergleichen" würde die gemessenen Fehlalarme des Repros zurückholen. |
| `sourceDialect`, einseitiger `engine`, ausdrückliches `NO ACTION` | Umbrella-Slice AP1 bis AP3 | Lesekonventionen, keine Anwenderwahl |

Der Rückzug gilt **vor** jeder Toleranz: Ein Feld, dessen Text sich der Faltung
entzieht, wird auch unter K1 wortgleich verglichen.

## Vertrag über drei Oberflächen

### Konfigurationsdatei

```yaml
# ── Schema-Vergleich ───────────────────────────
compare:
  # Zusaetzliche Gleichsetzungen fuer `schema compare`. Leer = strikt (Default).
  # keyword_case | identity_mode | value_list_form | restrict_as_no_action
  tolerances: []
```

- Es entsteht ein **neuer Block** `compare:`. Die
  [Konfigurationsspezifikation](../../../spec/connection-config-spec.md) kennt
  heute keinen solchen Block (Abschnitt „Vollständiges Schema").
- **Default:** Die leere Liste bedeutet strikten Vergleich, also das heutige
  Verhalten.
- **Unbekannter Wert → Konfigurationsfehler (Exit 7).** Die Meldung nennt den
  Wert und die erlaubte Liste. Es gibt keinen stillen Rückfall, nach dem Muster
  von `write.oracle.empty_string` (`OracleEmptyStringResolver`) und
  `migrate.raw_sql_sandbox` (`MigrateConfigResolver`). Ist der Wert keine Liste
  oder ein Element keine Zeichenkette, gilt dasselbe. Für einen unbekannten
  **Schlüssel** unter `compare:` gilt die allgemeine Regel der
  Konfigurationsspezifikation (Abschnitt „Validierung"). Dass ein vertippter
  Schlüssel keine Wirkung hat, zeigt die Kennzeichnung im Ergebnis.
- Doppelte Einträge schaden nicht, und die Reihenfolge ist ohne Bedeutung. Das
  Ergebnis nennt die Toleranzen in fester Reihenfolge.
- Das Vokabular ist **dialektneutral**, analog ADR 0027 Punkt 2.
- Die Datei wird aufgelöst wie heute: `--config`, dann `D_MIGRATE_CONFIG`, dann
  `.d-migrate.yaml` im Arbeitsverzeichnis.
- Der Block **wirkt nur auf `schema compare` in der CLI**. `schema migrate` liest
  ihn nicht (gepinnt), und MCP liest ihn ebenfalls nicht (s. unten).
- Das Vorbild ist ADR 0056, Punkt 3.4 der übernommenen Entscheidungen: Der
  Sandkasten wird „per Konfigurationsdatei eingeschaltet, ist nicht
  voreingestellt". Dieselbe Form gilt hier. Anders als der Sandkasten hat das
  Profil aber ein CLI-Flag und einen MCP-Parameter, denn es ändert die
  **Aussage** des Ergebnisses und nicht nur dessen Herleitung.

### CLI

- `schema compare --tolerate <liste>`, kommagetrennt, zum Beispiel
  `--tolerate keyword_case,restrict_as_no_action`.
- **Vorrang:** Flag vor Konfiguration vor Default, wie in
  `spec/dialect-preference-mechanism.md` (Abschnitt „Auflösungs-Präzedenz").
- **Das Flag ersetzt die Liste aus der Datei, es wird nicht zusammengeführt.**
  Nur so lässt sich „für diesen Lauf strikt" ausdrücken:
  `--tolerate none` vergleicht strikt, auch wenn die Datei Toleranzen nennt.
  `none` zusammen mit anderen Werten ist ein Fehler.
- **Unbekannter Wert → Exit 2** (ungültige CLI-Argumente). Die Meldung nennt die
  erlaubte Liste.
- Exit 0 und 1 behalten ihre Bedeutung: `identical` unter Toleranz ergibt
  Exit 0.

### MCP (`schema_compare` und `schema_compare_start`)

- Beide Werkzeuge bekommen das optionale Eingabefeld `tolerances`: ein Array
  aus demselben Vokabular mit `uniqueItems`. Fehlt es, gilt `[]`, also strikt.
- **Die Server-Konfiguration setzt keine Toleranzen für MCP-Aufrufe** (Empfehlung,
  E4). Ein Agent sieht die Konfiguration des Servers nicht. Ein `identical` muss
  sich deshalb allein aus der Anfrage ergeben.
- Ein unbekannter Wert ergibt `VALIDATION_ERROR` auf dem Feld `tolerances`
  (Schema-Enum).
- **Idempotenz bei `schema_compare_start`:** Der Payload-Fingerabdruck wird über
  die ganzen Argumente gebildet (`SchemaCompareStartHandler.kt:51`,
  `JobStartHandlerSupport.toJsonValueObj`). Ein fehlendes Feld, ein leeres Array
  und eine andere Reihenfolge ergeben damit verschiedene Fingerabdrücke, und
  dieselbe fachliche Anfrage liefe in einen Idempotenz-Konflikt. Vor dem
  Fingerabdruck wird deshalb normalisiert (sortiert, ohne Dubletten, fehlend
  gleich `[]`), oder das Verhalten wird dokumentiert. T7 entscheidet, ein Test
  pinnt es.
- **`schema_compare_start` vergleicht heute wortgleich**
  (`McpCoreJobWorkerFactory.kt:142`, `SchemaComparator()`). Eine Toleranz auf
  diesem Stand ergäbe einen dritten Modus: tolerant, aber ohne
  Schreibweise-Faltung. Das Profil setzt deshalb voraus, dass beide Werkzeuge
  gleich vergleichen (s. „Abhängigkeit").
- **Vertragsänderung:** Die Schema-Goldens
  (`adapters/driving/mcp/src/test/resources/golden/phase-b-tool-schemas.json`,
  `McpToolSchemasGoldenTest`) ändern sich und werden über `make golden-update`
  neu erzeugt. Die Tool-Beschreibung nennt den Default. Ob das additive Feld die
  Vertragsversion berührt, legt [`spec/mcp-server.md`](../../../spec/mcp-server.md)
  fest.

### Kennzeichnung im Ergebnis

Grundsatz: „Nicht stumm" (`spec/dialect-preference-mechanism.md`, Abschnitt
„Prinzip"). Weicht ein Lauf vom Default ab, sagt das Ergebnis es.

| Oberfläche | Form (Vorschlag) | Ohne Toleranz |
| ---------- | ---------------- | ------------- |
| Textreport | Zeile `Tolerances: keyword_case, restrict_as_no_action` direkt nach der `Status:`-Zeile (`CompareRendererPlain.kt:17`) | **keine** Zeile. Die Ausgabe bleibt byte-gleich, und die gepinnte Baseline `examples/sample-db/expected/pagila-smoke.compare.txt` bleibt gültig. |
| JSON/YAML (`SchemaCompareDocument`) | Feld `tolerances` | E5: immer vorhanden (leer heißt strikt) oder nur wenn nicht leer, wie `diagnostics`. **Empfehlung: immer**, denn sonst ist „strikt" nicht von „Version ohne Profil" zu unterscheiden. |
| MCP `schema_compare` | Ausgabefeld `tolerances` (Ausgabe-Schema, Golden) | wie JSON |
| MCP `schema_compare_start` | Der Job veröffentlicht heute den rohen `SchemaDiff` als JSON (`McpCoreJobWorkerFactory.kt:318`). `SchemaDiff` ist Kernmodell und soll kein Oberflächenkonzept tragen. Die Kennzeichnung braucht deshalb einen Ort außerhalb, etwa einen Artefakt-Umschlag oder Job-Metadaten (T7). Ohne ihn ist das asynchrone Ergebnis nicht vergleichbar. | wie JSON |

**Empfohlen (E6):** Zu jeder Toleranz zusätzlich die Zahl der Gleichsetzungen,
die sie tatsächlich bewirkt hat. So wird „`IDENTICAL`, aber drei Funde
toleriert" sichtbar. Der Zähler entsteht neben dem `SchemaDiff`, nicht darin.

## Nicht wirksam auf `schema migrate`, Fingerabdruck und `CanonicalPayload`

ADR 0056 hält in der neu gefassten Entscheidung 1 fest: Planung und
Post-Compare von `schema migrate`, der Fingerabdruck und `CanonicalPayload`
normalisieren nicht. Das Profil folgt dieser Linie ohne Ausnahme:

- Toleranzen gibt es **nur** an den Baustellen des Compare-Comparators, also
  `SchemaCompareWiring.kt:63`, `McpRuntimeRegistries.kt:302` und
  `McpCoreJobWorkerFactory.kt:142`. `SchemaMigrateComparators` bekommt keinen
  Parameter.
- `schema migrate` liest `compare.tolerances` nicht. Eine Konfiguration mit allen
  vier Toleranzen erzeugt einen **byte-gleichen** Plan.
- Der Algorithmus des Fingerabdrucks ändert sich nicht. `CanonicalPayload`,
  Plan-Artefakte und die Operations-IDs von Overlays bleiben unberührt.
- **Gepinnt:** `SchemaMigrateComparatorsTest` (über den Befehl und am Objekt)
  läuft zusätzlich mit einer Konfiguration, die alle Toleranzen nennt. Der
  Fingerabdruck eines Schema-Paars ist mit und ohne diese Konfiguration gleich.
- ADR 0056 warnt: „Wer `schema compare` als Vorschau auf den Migrationsplan
  liest, liest es falsch." Unter Toleranz gilt das erst recht, und das
  Handbuch sagt es (T9).

## Entscheidungsbedarf und Gate

### Wem die Linien gehören

| Kandidat | Normative Aussage heute | Kategorisch? | Folge |
| -------- | ----------------------- | ------------ | ----- |
| K1 | ADR 0056, „Die Grenze": Schlüsselwort-Schreibung „nicht entschieden … weder zu, noch schließt er sie aus"; `spec/cli-spec.md`: „Nicht festgelegt" | nein | Der neue ADR **ergänzt**, ohne Statusänderung. Voraussetzung: K1 bleibt Schreibweise (geschlossene Liste, nur unquotiert, nie hinter `.`). |
| K2 | `spec/cli-spec.md`: „Der **Modus** der Identity-Spalte bleibt ein Unterschied"; ADR 0027 und `spec/dialect-preference-mechanism.md`: keine tolerante Vergleichsfaltung für inhärente Mehrdeutigkeiten | Spec: ja; die Spec ist Zielbild und zieht mit dem ADR nach. ADR 0027: nur, wenn die MSSQL-Lesung als inhärente Mehrdeutigkeit gilt. | Der ADR ordnet ein (E2). Gilt sie als Mehrdeutigkeit, ist eine Reverse-Präferenz das Werkzeug und K2 entfällt. Sonst ergänzt der ADR und grenzt sich ausdrücklich von ADR 0027 ab (dessen Text spricht von einer Post-Compare-Faltung mit Anhebung des Fingerabdrucks). |
| K3 | ADR 0056, „Die Grenze": „… für `schema compare` ausdrücklich nicht. Dabei bleibt es."; ADR 0055: „`schema compare` bleibt streng: dort ist die Darstellung selbst der Unterschied" | **ja, beide** | **Statusänderung:** `superseded by ADR-00NN` an ADR 0055 **und** ADR 0056. Der neue ADR übernimmt deren Entscheidungen, nach dem Muster, mit dem ADR 0056 den ADR 0053 übersteuert hat. Alternativ entfällt K3 (E1). |
| K4 | `spec/cli-spec.md`: „Nicht festgelegt"; kein ADR | nein | Der neue ADR **ergänzt**. |

**Die Lesart „der Default bleibt ja streng" trägt nicht.** Dass „`schema compare`
bleibt streng" nur den Default meine, ist genau die Umdeutung, die niemand
prüft. Die Statuszeile akzeptierter ADRs erlaubt nur `accepted` oder
`superseded by ADR-NNNN` (`.d-check.yml`, `vcs.head-allow`). Eine Form
„ergänzt durch" gibt es nicht. **Ein neuer ADR, der einem akzeptierten ADR
widerspricht und bloß danebengestellt wird, fällt keinem Gate auf:**
`make docs-check` und `make doc-immutable` bleiben grün, und der Widerspruch
steht im Repo (ADR 0056, Option B; aktiver Slice, P7). Ergänzen ist nur dort
zulässig, wo der ältere ADR die Frage ausdrücklich offen lässt (K1) oder gar
nicht besitzt (K2, K4).

### Empfehlung an den architect

Einen neuen ADR „Toleranzprofil für `schema compare`" schreiben, mit folgendem
Inhalt:

1. **Begriff:** Die Toleranz ist opt-in, Default strikt, das Ergebnis ist
   gekennzeichnet, es gibt drei Oberflächen, und das Profil erweitert nur.
2. **Die Kandidaten nach E1**, je mit Semantik-Risiko und gewählter Bindung (E3).
3. **Keine Wirkung** auf Migrate, Fingerabdruck und `CanonicalPayload`
   (Entscheidung 1 aus ADR 0056 übernehmen).
4. **Abgrenzung zu ADR 0027:** Toleranz ist nicht Präferenz.
5. **Falls K3 dazugehört:** ADR 0055 und ADR 0056 übersteuern und deren
   Entscheidungen der Sache nach vollständig übernehmen.

Mit dem ADR ziehen `spec/cli-spec.md`, `spec/connection-config-spec.md`,
`spec/mcp-server.md` und `spec/dialect-preference-mechanism.md` nach. Die Spec
verweist dabei **nicht** auf den ADR; Verweise von `spec` auf `adr` sind laut
`.d-check.yml` (Modul `matrix`) verboten.

**Gate-Hinweise:**

- `make doc-immutable RANGE=origin/main..HEAD` ist im Arbeits-Repo still grün
  ([`../open/doc-immutable-lokal-still-gruen.md`](../open/doc-immutable-lokal-still-gruen.md)).
  Bis das behoben ist, muss der Lauf in einem frischen
  `git clone --no-local` stattfinden. CI prüft ohnehin einen frischen Checkout.
- `consulted:` im neuen ADR ist historisch gemeint. Zieht dieser Plan nach
  `../in-progress/` um, bleibt der Pfad stehen (`CLAUDE.md`).

### Eigner-Fragen

| # | Frage | Empfehlung |
| - | ----- | ---------- |
| E1 | Welche Kandidaten kommen ins Profil? Vor allem: K3, der eine Statusänderung an zwei ADRs kostet. Und: K1 ins Profil oder fest in die Schreibweise-Faltung? | Profil mit K1, K2 und K4. K3 nur, wenn der Bedarf den Umbau von 0055 und 0056 trägt. |
| E2 | K2: Toleranz oder Reverse-Präferenz (`reverse.mssql.identity_mode`)? | Toleranz. T-SQL-IDENTITY entspricht keinem der beiden Modi; eine Präferenz würde dem Modell einen Modus zuschreiben, den der Server nicht hat. |
| E3 | K2 und K4 global oder **fähigkeitsgebunden** (nur wenn die Reverse-Markierung einer Seite einen Dialekt nennt, der den Unterschied nicht ausdrücken kann: für K2 SQL Server, MySQL, SQLite; für K4 SQL Server, Oracle)? | Fähigkeitsgebunden. So bleibt PG↔PG streng, und die Variante nutzt dieselbe Markierung wie P6 (`reverseSourceDialect`). Ein handgeschriebenes Schema trägt keine Markierung. |
| E4 | MCP: Gilt die Server-Konfiguration als Default für Aufrufe? | Nein, nur der Parameter zählt. |
| E5 | JSON/YAML/MCP: `tolerances` immer ausgeben oder nur, wenn nicht leer? | Immer. |
| E6 | Zahl der Gleichsetzungen je Toleranz im Ergebnis? | Ja. |
| E7 | Namen von Schlüssel, Werten und Flag (`compare.tolerances`, `--tolerate`, `none`) | wie vorgeschlagen |

## Abhängigkeit

- **Aktiver Slice**
  ([`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md)).
  Das Profil baut auf auf:
  - der Faltung (`RawSqlSkeleton`, `ExpressionSpelling`, `QuerySpelling`); K1
    hängt sich hinter den Rückzug;
  - P9 (Cast-Regel; K3);
  - P1 (Kurzform `CompareSignature`; ein Fund unter K4 bleibt lesbar);
  - P2 (Pfad-Schema) und E (`CompareValueText`, `details`-Format);
  - P6 (`comparisonGeneration`, `compareProjectionDialect`; K2 hängt an derselben
    Erzeugungsprojektion, und die fähigkeitsgebundene Variante nutzt dieselbe
    Markierung).

  Laut Eigner-Auftrag kommen hinzu: **P10** (serial-Flag als Herkunftsfeld) und
  **P11** (die zwei MCP-Oberflächen gleichgezogen). **Im committeten Stand
  `d14f7021b` gibt es P10 und P11 noch nicht.** Dort stehen die beiden Fragen
  unter „Offen" (`legacy_serial_syntax`; „Die zweite MCP-Oberfläche fehlt im
  Plan"). Der Plan wird erst nach der Graduation aktiviert. Kommt P11 nicht,
  muss dieser Plan zuerst `schema_compare_start` wie `schema_compare` vergleichen
  lassen. ADR 0056 lässt das offen, und es darf keine Nebenwirkung einer
  Toleranz sein.
- **Reader-Slice**, Posten C1/P6 (MySQL-Introducer,
  [`reader-treue-spatial-array-json.md`](reader-treue-spatial-array-json.md)).
  Ohne ihn enden die MySQL-Paarungen mit Exit 3. K1 lässt sich an echten
  MySQL-Reverses erst nach diesem Posten abnehmen; der Repro hat den Fix nur
  simuliert.
- **Geteilte Signatur:** Der Konstruktor von `SchemaComparator` ändert sich.
  Deshalb einmal `make docker-check` **ohne** `MODULES` fahren, denn
  `test/integration-mysql` ruft ihn direkt auf (`CLAUDE.md`).

## Arbeitspakete

**T0 — ADR und Spec (architect).** Das ist eine Vorbedingung und kein Paket
dieses Plans (s. „Entscheidungsbedarf und Gate").

### T1 — Das Profil im Hexagon

- **Modul:** `:hexagon:core`.
- **Umsetzung:**
  - Ein Wert `CompareTolerances` (Menge aus `CompareTolerance`) kommt als
    **ein** neuer Konstruktorparameter an `SchemaComparator` und wird an
    `TableComparator` und `RawTextFolding` weitergereicht.
  - Der Konstruktor trägt schon fünf Parameter. `LongParameterList` steht in
    `solid_rules` (`scripts/solid-suppression-gate.sh`); `@Suppress` ist
    ausgeschlossen, nötig ist echte Bündelung.
  - Die KDoc sagt: wirkt nur auf die Vergleichsentscheidung.
  - Den Zähler je Toleranz gibt es nur, wenn E6 ihn verlangt.
- **DoD:** Ein leeres Profil verhält sich wie heute. Alle bestehenden
  Compare-Tests bleiben unverändert grün. Ein Test zeigt am Repro-Schema, dass
  `SchemaComparator()` und ein leeres Profil denselben Diff liefern.

### T2 — K1 `keyword_case`

- **Modul:** `:hexagon:core`.
- **Umsetzung:**
  - Das Gerüst merkt sich, welche Wörter quotiert waren.
  - Es gibt eine geschlossene Wortliste an **einer** Stelle.
  - K1 faltet erst nach dem Rückzug, nie hinter `.`.
  - Betroffen sind CHECK, Index-Prädikat und Sichten-Rumpf, nicht der
    Index-Schlüssel-Ausdruck.
- **DoD:**
  - Die Repro-Paare sind unter K1 gleich.
  - Die Gegenproben aus K1 bleiben Funde.
  - Ohne K1 bleibt jedes Paar ein Fund.

### T3 — K2 `identity_mode`

- **Module:** `:hexagon:core` und `:hexagon:application` (neben dem Namensteil
  aus P6).
- **Umsetzung:** K2 faltet nur `mode`, in der Variante nach E3.
- **DoD:**
  - PG↔MSSQL meldet unter K2 keine Identity-Spalte mehr.
  - Die Gegenproben aus K2 bleiben Funde.
  - Ohne K2 bleibt es beim Fund (Eigner, 2026-09-16).

### T4 — K3 `value_list_form` (nur nach E1)

- **Modul:** `:hexagon:core`.
- **Umsetzung:** Der Erkenner kommt aus `EnumCheckProjection`, die Casts folgen
  P9 (`ColumnCasts`). Betroffen sind CHECK und Index-Prädikat.
- **DoD:**
  - Die drei Formen sind unter K3 gleich.
  - Die Gegenproben aus K3 bleiben Funde.
  - Der zielbewusste Vergleich (ADR 0055) ist nachweislich unverändert.

### T5 — K4 `restrict_as_no_action`

- **Modul:** `:hexagon:core`.
- **Umsetzung:**
  - Beim Bau von `ForeignKeySignature` (`TableComparator.kt:387`) faltet K4
    `restrict` auf `null`, für `on_delete` und `on_update`, am Constraint und an
    der einspaltigen `references`, in der Variante nach E3.
  - Dazu kommt die Korrektur der veralteten KDoc von
    `SchemaReaderUtils.toReferentialAction` im Modul
    `:adapters:driven:driver-common`.
- **DoD:**
  - PG↔MSSQL mit `restrict` ist unter K4 gleich.
  - Die Gegenproben aus K4 bleiben Funde.

### T6 — CLI

- **Module:** `:adapters:driving:cli` und `:hexagon:application`.
- **Umsetzung:**
  - Ein Resolver für den Block `compare:` nach dem Muster von
    `MigrateConfigResolver` und `OracleEmptyStringResolver`.
  - `--tolerate` an `SchemaCompareCommand`, Verdrahtung in
    `SchemaCompareWiring.kt:63`.
  - `SchemaCompareDocument.tolerances`, `SchemaCompareRunner`, die Zeile im
    Textreport sowie JSON und YAML.
- **DoD:**
  - Der Vorrang ist gepinnt: Flag vor Konfiguration vor Default, `none`,
    Zusammenführung ausgeschlossen.
  - Ein unbekannter Wert ergibt Exit 2 (Flag) bzw. Exit 7 (Datei).
  - `schema migrate` mit Profil-Konfiguration liefert denselben Plan.
  - Die Pagila-Baseline bleibt byte-gleich.
  - Die Kennzeichnung ist in allen drei Ausgabeformaten gepinnt.

### T7 — MCP

- **Modul:** `:adapters:driving:mcp`.
- **Umsetzung:**
  - Eingabefeld in `McpToolSchemas` (`schema_compare`, `schema_compare_start`),
    Auswertung in beiden Handlern.
  - Verdrahtung in `McpRuntimeRegistries.kt:302` und
    `McpCoreJobWorkerFactory.kt:142`.
  - Das Ausgabefeld `tolerances` sowie ein Ort für die Kennzeichnung des
    Job-Ergebnisses.
  - Normalisierung für den Idempotenz-Fingerabdruck.
  - Golden neu erzeugen mit `make golden-update`.
- **DoD:**
  - Beide Werkzeuge nehmen gültige Werte an und weisen ungültige ab.
  - Antwort und Job-Artefakt nennen die aktiven Toleranzen.
  - Gleiche Anfragen mit fehlendem Feld, `[]` oder anderer Reihenfolge
    verhalten sich wie festgelegt.
  - Der Golden-Diff ist nur additiv.
  - Die Server-Konfiguration hat nachweislich keine Wirkung (bei E4 „nein").

### T8 — Absicherung (quer)

- **Umsetzung:**
  - Sabotage je Toleranz und die Gegenproben-Matrix (s. „Verifikation").
  - Die Pins für Migrate und Fingerabdruck.
  - Ein Subprozess-Test in `test/e2e-cli` für `--tolerate` und für den Block
    `compare:`. Er belegt, dass Flag-Parser und Konfigurationspfad
    zusammenwirken.
- **DoD:** Jeder Sabotage-Lauf ist rot an der erwarteten Stelle, und die
  Rücknahme ist bestätigt.

### T9 — Doku (erst, wenn gebaut)

`docs/user/` beschreibt nur, was heute wirkt.

- **Umsetzung:**
  - **Anwenderhandbuch**, Abschnitt 3.4, aufgabenorientiert: „Sie vergleichen
    zwei Reverses verschiedener Dialekte, und die verbliebenen Funde sind für Sie
    keine Änderung → Toleranz zuschalten." Dazu, was die Kennzeichnung bedeutet
    und dass `schema compare` unter Toleranz keine Vorschau auf den
    Migrationsplan ist.
  - Anhang A.3 bekommt die Zeile `--tolerate`.
  - `docs/user/api-referenz.md` bekommt den Parameter der beiden Werkzeuge.
  - Die Schlüsselreferenz steht dort, wo die Handbücher Konfigurationsschlüssel
    führen; das wird beim Bau geprüft.
  - `CHANGELOG.md` unter `[Unreleased]`.
- **DoD:** Das Handbuch nennt nur gebaute Toleranzen; `make docs-check` ist grün.

## Akzeptanzkriterien

1. **Ohne Toleranz ändert sich nichts.** Das gilt ohne Konfiguration, ohne Flag
   und ohne MCP-Feld für `schema compare`, `schema_compare` und
   `schema_compare_start`. Der Textreport ist byte-gleich (Pagila-Baseline).
   JSON, YAML und MCP sind höchstens um das leere Kennzeichnungsfeld erweitert.
2. **Jede gebaute Toleranz setzt ihr Beispielpaar gleich und sonst nichts.** Je
   Toleranz steht eine Gegenprobe je Grenze (T2 bis T5) und je relevanter
   Dialekt-Paarung (Matrix unten).
3. **Keine Toleranz hebt eine Korrektheitsgrenze auf.** `ColumnCastFoldTest`,
   `SpellingFoldBoundaryTest`, `ExpressionCanonicalisationTest` und
   `ViewQueryCanonicalisationTest` laufen zusätzlich mit **vollem** Profil
   grün. Ausgenommen sind nur die einzeln benannten Zusicherungen, die genau die
   zugeschaltete Toleranz aufhebt (etwa „`= ANY` gegen `IN` bleibt Fund" unter
   K3).
4. **Migrate, Fingerabdruck und `CanonicalPayload` bleiben unverändert.** Das
   ist mit einer Konfiguration gepinnt, die alle Toleranzen nennt.
5. **Jedes Ergebnis nennt seine aktiven Toleranzen:** Textreport (nur wenn nicht
   leer), JSON/YAML, MCP synchron und MCP asynchron.
6. **Die Oberflächen verhalten sich wie festgelegt.** Ein unbekannter Wert in
   der Datei ergibt Exit 7, im Flag Exit 2. Das Flag ersetzt die Datei, `none`
   erzwingt strikt. MCP antwortet auf einen unbekannten Wert mit
   `VALIDATION_ERROR`. Die Server-Konfiguration wirkt nach E4.
7. **Der ADR ist `accepted`**; mit K3 tragen ADR 0055 und ADR 0056
   `superseded by`. Die Spec nennt Profil, Kandidaten, Defaults und
   Kennzeichnung. `make docs-check` ist grün, `make doc-immutable` im frischen
   Klon ebenfalls.
8. **Das Handbuch beschreibt das Profil aufgabenorientiert**, und zwar erst,
   wenn es wirkt.

## Verifikation

### 1. Module

| Paket | Modul | womit |
| ----- | ----- | ----- |
| T1 bis T5 | `:hexagon:core` (T3 auch `:hexagon:application`, T5-KDoc `:adapters:driven:driver-common`) | `make docker-check MODULES=":hexagon:core"` usw. |
| T6 | `:adapters:driving:cli`, `:hexagon:application` | `make docker-check` |
| T7 | `:adapters:driving:mcp` | `make docker-check`, `make golden-update` |
| T8 | `test/e2e-cli` | `make integration INTEGRATION_TASKS=":test:e2e-cli:test"` (setzt `-PintegrationTests`; ohne die Property überspringt Gradle die Tests und meldet trotzdem Erfolg) |
| geteilte Signatur | alle, auch `test/integration-*` | einmal `make docker-check` **ohne** `MODULES` |
| T0, T9 | `docs/adr/`, `spec/`, `docs/user/` | `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD` im frischen Klon |

Vor jedem Commit läuft `make solid-suppression-gate`. Die Ausgabe langer Läufe
geht in eine Datei und wird gegrept.

### 2. Sabotage je Toleranz

Für jede Toleranz gibt es vier Läufe; jeder muss an der erwarteten Stelle rot
werden:

- Die Faltung wird entfernt. Dann werden die Gleichheitstests rot.
- Die Faltung wird unabhängig vom Profil wirksam. Dann werden die
  Strikt-Default-Tests rot.
- Die Bindung nach E3 wird entfernt. Dann wird die PG↔PG-Gegenprobe rot.
- Die Kennzeichnung wird entfernt. Dann werden die Kennzeichnungstests rot.

Dazu kommen Läufe für einzelne Grenzen und Schalter:

- **K1:** Ohne Quotierungsmerker wird die Gegenprobe `"SUM"(x)` rot, ohne die
  Regel für `.` die Gegenprobe `dbo.SUM`.
- **Datei:** Wird die Auswertung nachsichtig, wird der Exit-7-Test rot.
- **Flag:** Wird das Flag mit der Datei zusammengeführt, wird der
  `none`-Test rot.

Nach jedem Lauf die Rücknahme verifizieren: Ausgabe lesen und die Tests danach
grün fahren. Einmal ist zu belegen, dass die neuen Specs überhaupt laufen, mit
einer absichtlich falschen Zusicherung.

### 3. Gegenproben je Toleranz und Dialekt-Paarung

Die Fixtures folgen echten Reverse-Ausgaben, also den Artefakten des
Konsumenten-Repros im aktiven Slice. „—" heißt: In dieser Paarung entsteht der
Unterschied nicht; die Gegenprobe zeigt, dass ohne Toleranz nichts gemeldet wird.

| Paarung | K1 | K2 | K3 | K4 |
| ------- | -- | -- | -- | -- |
| PG↔MSSQL | Sichten-Rumpf `SUM`/`sum` gleich | `by_default`/`always` gleich | `= ANY`/`OR`-Kette gleich | `restrict`/— gleich |
| PG↔MySQL (nach Reader-C1) | `IS NULL`/`is null` gleich | `always`/`by_default` gleich; Legacy-Flag bleibt P10 | `= ANY`/`IN` gleich | — (beide führen `RESTRICT`) |
| MSSQL↔MySQL | `IS NULL`/`is null` gleich | `always`/`by_default` gleich | `OR`-Kette/`IN` gleich | —/`restrict` gleich |
| PG↔SQLite | `LIKE`/`like` gleich | Nullfall: `identifier(auto)` ist ein Typ-Fund, kein K2 | `= ANY`/`IN` gleich | — |
| PG↔Oracle | `amt`/`"AMT"` **bleibt** Fund | Moduswechsel **bleibt** Fund (E3); beide lesen treu | `= ANY`/`IN` gleich | `restrict`/— gleich |
| PG↔PG (derselbe Dialekt) | `"SUM"(x)`/`sum(x)` **bleibt** Fund | `always`/`by_default` **bleibt** Fund (E3) | andere Wertemenge **bleibt** Fund | `restrict`/— **bleibt** Fund (E3) |

Jede Zeile gilt einmal mit und einmal ohne die Toleranz. Ohne sie ist jedes
„gleich" ein Fund.

### 4. Abnahme am Server

Der Konsumenten-Repro des aktiven Slices wird mit vollem Profil wiederholt,
über den Stack von `make mcp-e2e-up`, die Paarungen ausgeschrieben. Die
Semantik-Aussagen von K2 und K4 werden dabei gegen PostgreSQL nachgemessen:
`OVERRIDING SYSTEM VALUE` sowie `RESTRICT` gegen `NO ACTION` mit
`DEFERRABLE`. Die Messung steht im Plan, bevor der ADR die Risiken festschreibt.

### 5. Vertrags-Gates

`make docs-check`, `make doc-immutable` (frischer Klon) und
`make solid-suppression-gate`.

## Offen (nicht Teil dieses Plans)

- **Zuordnung unbenannter Indizes über die kanonische Form.** Das ist eine
  Korrektur und keine Toleranz (s. „Geprüft und nicht aufgenommen"). Der
  eigene Eintrag ist angelegt:
  [`compare-unbenannte-indizes-kanonische-zuordnung.md`](../open/compare-unbenannte-indizes-kanonische-zuordnung.md).
- **Typabflachung als Toleranz.** Nur mit eigener Eigner-Frage und im Bezug zu
  ADR 0026.
- **Ob der Generator eine `always`-Spalte für MySQL und SQLite meldet.** Das ist
  beim Schnitt nicht geprüft worden. Es berührt K2 nur als Beleg. Für MySQL
  ist der stille Verlust im Konsumenten-Repro des Compare-Slices gemessen; der
  Posten steht im [Reader-Slice](reader-treue-spatial-array-json.md) als D4
  (SQLite dort als offene Prüfung).
- **Die veraltete KDoc und Spec-Zeile zu `RESTRICT`/`no_action`** (K4,
  Nebenbefunde). Sie werden mit T5 bzw. dem Spec-Schritt korrigiert, nicht
  vorher.

## Herkunft

- Der aktive Slice
  [`compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md):
  die Abgrenzung (Schlüsselwort-Schreibung, `= ANY` gegen `IN`, `RESTRICT`,
  MySQL-Sichtformatierung) und „Offen" (Identity-Modus mit `W140`, zweite
  MCP-Oberfläche, unbenannte Indizes, `legacy_serial_syntax`).
- Der Umbrella-Slice
  [`compare-falsch-positive-cross-dialekt.md`](../in-progress/compare-falsch-positive-cross-dialekt.md):
  „`RESTRICT` gegen implizit" und „Der strikte Modus selbst" (eine
  Vertragsänderung von `schema compare`, die eine Eigner-Entscheidung braucht).
- [ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md),
  Punkt 3.4 der übernommenen Entscheidungen, als Vorbild für „per
  Konfigurationsdatei einschalten, nicht voreingestellt".
- Eigner-Entscheidung vom 2026-09-16 (Identity-Modus: ohne Toleranz ein Fund)
  und Eigner-Auftrag vom 2026-09-17 (steuerbar statt einmal für alle).
