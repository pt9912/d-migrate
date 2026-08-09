---
name: markdown-mq
description: Strukturelle Suchen und Abfragen in Markdown-Dateien mit dem CLI-Tool `mq` (jq-artige Syntax, Node-basiert) statt grep. TRIGGER wenn die Frage an eine .md-Datei strukturell ist — "nur in Überschriften", "nicht in Code-Blöcken", "alle Links/Tabellen/offenen Tasks", "gib mir den Abschnitt X komplett", "in welchem Abschnitt steht Y", Konsistenz-/Notationsprüfungen über viele Dateien. SKIP bei reiner Textsuche ("wo steht das Wort X") — dafür ist grep schneller und überall verfügbar.
---

# Markdown strukturell abfragen mit `mq`

`mq` parst Markdown in einen Node-Baum und filtert ihn mit jq-artiger Syntax. Damit
beantwortet es Fragen, die grep prinzipiell nicht beantworten kann, weil grep
zeilenbasiert arbeitet und die Dokumentstruktur nicht kennt.

## Entscheidungsregel

| Frage | Werkzeug |
|---|---|
| „Wo steht das Wort X?" | **grep** — schneller (~9x), überall installiert |
| „Steht X in Prosa oder nur in einem Code-Beispiel?" | **mq** |
| „Nur Überschriften / Links / Tabellen / offene Tasks" | **mq** |
| „Gib mir den Abschnitt *X* komplett, inkl. Unterabschnitten" | **mq** |
| „Ist die Schreibweise über alle Dateien konsistent?" | **mq** |

## Preflight

```bash
command -v mq || echo "mq nicht installiert — auf grep zurückfallen"
```

Ohne `mq` nicht scheitern, sondern grep verwenden und die strukturelle Einschränkung
benennen. `mq` ist kein Standard-Tool; auf einer fremden Maschine fehlt es oft.

## Query-Kochbuch

Alle Queries sind gegen echte Dateien verifiziert.

```bash
# Überschriften
mq '.h' datei.md                    # alle
mq '.h2' datei.md                   # nur H2

# Treffer AUSSERHALB von Code-Blöcken (grep kann das nicht)
mq -F grep 'select(not(.code)) | select(contains("foo"))' datei.md

# Treffer NUR in Code-Blöcken
mq -F grep '.code | select(contains("foo"))' datei.md

# Nur reiner Text — ohne Inline-Code, ohne Code-Blöcke
mq -F grep '.text | select(contains("foo"))' datei.md

# Abschnitt komplett, inkl. aller Unterabschnitte
mq -A 'import "section" | section::section(self, "Titel", true) | section::collect(self)' datei.md

# Genau einen Abschnitt herausgreifen
mq -A 'import "section" | section::all_nodes(section::nth(section::section(self, "Titel", true), 0))' datei.md

# In welchen Abschnitten kommt ein Begriff vor?
mq -A 'import "section" | section::titles(section::filter_sections(self, fn(s): section::all_nodes(s) | to_text() | contains("foo");))' datei.md

# Mehrere Dateien: einfach anhängen
mq -F grep '.h' a.md b.md c.md
```

Nützliche Selektoren: `.h`/`.h1`…`.h6`, `.code`, `.code_inline`, `.text`, `.list`,
`.task`/`.todo`/`.done`, `.link`, `.table`, `.blockquote`, `.image`.
`mq help` listet alle, `mq help <name>` die Details — **immer nachschlagen statt
Syntax raten**.

## Output-Formate

| Flag | Ausgabe |
|---|---|
| `-F grep` | `datei:zeile:inhalt` — grep-kompatibel |
| *(default)* | nur der Inhalt, keine Zeilennummern |
| `-F text` | Rohtext ohne Markdown-Escaping |
| `-F json` | Node-Typ + `position.start/end` mit Zeile **und Spalte** — einziges Format mit dem *Ende* eines Nodes |

## Fallen — alle reproduziert, alle kosten sonst Zeit

**Zwei Fallen liefern ein falsches Ergebnis, ohne zu scheitern.** Bei unerwartetem
Ergebnis zuerst diese beiden prüfen:

1. **Vorangestelltes `.[]` lässt `select` still auf leer laufen.** Kein Fehler, kein
   Exit-Code, nur null Treffer. `select(…)` ohne `.[]` schreiben. Bei „nichts
   gefunden" immer gegen eine Datei gegenprüfen, in der der Treffer sicher steht.

2. **Das Pattern von `section::section` ist ein Substring-Match über *alle*
   Abschnitte.** `"Kapitel"` trifft `# Kapitel 1` *und* `# Kapitel 2`, und
   `collect` klebt beide zusammen — sieht aus wie ein einzelner Abschnitt.
   Spezifisch matchen oder mit `nth(…, 0)` auswählen. Zur Kontrolle:
   `len(section::section(self, "Titel", true))`.

Die übrigen:

3. **`section::*` braucht alle Nodes auf einmal** → `-A` ist Pflicht (sonst Warnung
   auf stderr, die bei `2>/dev/null` unsichtbar wird — stderr nicht unterdrücken).
4. **`and`/`or` sind Funktionen, nicht infix** — infix ist `||`. `select(a or b)`
   ist ein Syntaxfehler.
5. **Der 3. Parameter von `section::section(nodes, pattern, depth)` ist ein
   Boolean**, keine Heading-Ebene (eine Zahl wirkt nur zufällig, weil truthy).
   `true` = inkl. Unterabschnitte, bis zur nächsten Überschrift gleicher oder
   höherer Ebene; `false` = nur Überschrift + eigener Body.
6. **`collect` erwartet ein Array von Sections, `nth` liefert eine einzelne.**
   `collect(nth(…))` bricht mit `Invalid types for "foreach"` ab — stattdessen
   `all_nodes(nth(…))` oder `collect([nth(…)])`.
7. **`-F grep` serialisiert Markdown neu und escaped dabei** (`foo\_bar` statt
   `foo_bar`), ist also **nicht** byte-identisch mit der Datei. Betroffen sind
   Text-Nodes; Inline-Code bleibt unescaped, kommt aber mit Backticks zurück.
   Vor einem exakten Edit `-F text` nehmen oder die Stelle nochmal mit grep holen.
8. **Prädikat-Syntax ist `fn(s): …;`** — mit Doppelpunkt und abschließendem
   Semikolon, z. B. `filter_sections(self, fn(s): true;)`.

## Treffer zählen

**mq zählt Nodes, grep zählt Zeilen — die Zahlen sind nie direkt vergleichbar.**
Ein dreizeiliger Absatz mit zwei Vorkommen ist für grep 2, für mq 1.

Und weil `-F grep` bei mehrzeiligen Nodes (Code-Blöcke, Tabellen, mehrzeilige
Absätze) nur die *erste* Zeile präfixiert und den Rest unpräfixiert durchlaufen
lässt, zählt ein naives `| wc -l` massiv zu hoch. Korrekt:

```bash
mq -F grep '…' *.md | grep -cE '^[^:]+\.md:[0-9]+:'
```

Aus demselben Grund verrutscht nachgelagertes `cut -d: -f2`. Zum maschinellen
Weiterverarbeiten `-F json` nehmen und `position` auslesen.

## Beispiel: Konsistenzprüfung

Prüfen, ob ein ID-Schema durchgängig in Backticks geschrieben wird:

```bash
FILES=$(grep -rl --include='*.md' 'ARC-' .)
mq -F grep '.text | select(contains("ARC-"))' $FILES    # leer = 100 % konsistent
```

Trifft `.text` nichts, steht der Begriff nirgends als reiner Fließtext — also
überall in Inline-Code oder Code-Blöcken. Solche Aussagen sind mit grep nicht
zu bekommen.
