# shellcheck shell=bash
# Der Silent-Loss-Check der Compare-Matrix (smoke-compare-matrix.sh).
#
# Die Matrix zaehlt Vergleichsfunde. Ein **Verlust** faellt dabei nicht auf:
# geht eine Eigenschaft beim Lesen oder beim Erzeugen verloren, sind hinterher
# beide Seiten gleich verloren, und die Zelle meldet null Funde. Dieser Check
# fragt deshalb etwas anderes: **kommt an, was ankommen soll — und wo nicht,
# wird es gesagt?**
#
# Grundlage sind die **Anmerkungen** in den Seed-Dateien
# (fixtures/seeds/<dialekt>.sql). Jede Seed-Spalte traegt eine, und zwar
# ausserhalb der `CREATE`-Anweisung (SQLite speichert Kommentare im
# Tabellentext mit, und die Scanner des Readers kennen keine):
#
#   -- seed: <tabelle>.<spalte> | paket: <Paket> | quelle: <form> [| code: <Code|keinen>]
#   --   [generation: <gen>]
#   --   [ausdruck: <text>]
#   --   ziel <dialekt>: <form> | code: <Code|keinen> [| generation: <gen>] [| ausdruck: <text>]
#
# `quelle` ist die neutrale Form, die der Reverse **dieser** Quelle liefern
# muss; `ziel <d>` die Form, die der Reverse des Ziels danach liefert. `code`
# nennt den Code, den der zugehoerige Report fuer dieses Objekt traegt — oder
# ausdruecklich `keinen`. Die Anmerkungen beschreiben den **Zielzustand**; was
# davon heute fehlt, steht in der Liste bekannter Befunde unten.
#
# Die Formen sind eine kurze Schreibweise des neutralen Typs:
#
#   text, text(40), decimal(12,2), float, float(double), integer, biginteger,
#   identifier, identifier(auto), array(text), enum(ref:x), enum(a,b),
#   geometry, geometry(srid=4326), json, uuid, date, datetime, …
#
# dazu die Erzeugung (`generation:`) als `identity(<modus>)`,
# `identity(<modus>,serial)` oder `computed(stored|virtual)` und der rohe
# Berechnungsausdruck (`ausdruck:`).
#
# **Vier Klassen** (Verstoesse gehen zeilenweise nach stdout):
#
#   quelle    Der Reverse der Quelle liefert eine andere Form, Erzeugung oder
#             einen anderen Ausdruck als die Anmerkung — oder der
#             Reverse-Report traegt den angemerkten Code nicht.
#   reftype   Eine Spalte verweist auf einen `ref_type`, den `custom_types`
#             nicht fuehrt. Ein solcher Verweis ist nie richtig, auf keinem
#             Reverse.
#   verloren  Eine Spalte fehlt: im Reverse der Quelle gegenueber der
#             Anmerkung, oder im Reverse des Ziels gegenueber dem der Quelle,
#             ohne dass der Generate-Schritt sie in `skipped_objects` nennt.
#   ziel      Der Reverse des Ziels verfehlt die angemerkte Form — oder er
#             trifft sie, sie ist eine Degradierung, und der Generate-Report
#             der Zelle nennt dafuer keinen Code (M1). Eine Degradierung, deren
#             Anmerkung „keinen" sagt, ist selbst ein Verstoss.
#
# Ein Verstoss ist ein Fehlschlag, **ausser** er steht wortgleich in
# [SILENT_LOSS_KNOWN]. Die Liste ist Code, keine Erwartung: sie waechst nicht
# mit `--update-expectations`, und ein Eintrag, der im Lauf **nicht** auftritt,
# ist selbst ein Fehlschlag (der Befund ist weg, die Liste ist nachzuziehen).
#
# Erwartet: jq und awk. Jede Funktion schreibt nur ihr Ergebnis nach stdout;
# ihr Exit-Code ist zu pruefen — eine leere Ausgabe nach einem Fehler ist
# **kein** „nichts gefunden".

# Die Dialekte, die eine Anmerkung als Ziel nennen darf.
SILENT_LOSS_DIALECTS=" postgresql mysql mssql sqlite oracle "

# --- Anmerkungen ------------------------------------------------------

# Die Anmerkungen aller Seed-Dateien als JSON-Array. Ein Formatfehler, ein
# unbekannter Zieldialekt oder eine Seed-Datei ohne Anmerkung scheitern laut.
# $1=Seed-Verzeichnis
seed_annotations() {
    local dir="$1"
    if [ ! -d "$dir" ] || [ -z "$(find "$dir" -maxdepth 1 -name '*.sql' -print -quit)" ]; then
        printf '[]\n'
        return 0
    fi
    local raw
    raw="$(awk -v dialects="$SILENT_LOSS_DIALECTS" '
      function die(msg) {
          printf("Anmerkung: %s (%s:%d)\n", msg, FILENAME, FNR) > "/dev/stderr"
          failed = 1
          exit 3
      }
      function clean(value) {
          gsub(/^[ \t]+|[ \t]+$/, "", value)
          # Ein Backslash bliebe in der JSON-Ausgabe zweideutig; ein
          # Anfuehrungszeichen kommt in einem neutralen Bezeichner vor und wird
          # beim Schreiben escapet (esc).
          if (value ~ /\\/) die("Wert mit Backslash: " value)
          return value
      }
      function esc(value) {
          gsub(/"/, "\\\"", value)
          return value
      }
      # `a: b | c: d` -> die Werte in der Reihenfolge; fields[key] = value
      function split_fields(rest,   parts, i, n, kv, key) {
          delete fields
          n = split(rest, parts, /[ \t]*\|[ \t]*/)
          for (i = 1; i <= n; i++) {
              if (parts[i] !~ /:/) die("Feld ohne Doppelpunkt: " parts[i])
              kv = parts[i]
              key = substr(kv, 1, index(kv, ":") - 1)
              fields[clean(key)] = clean(substr(kv, index(kv, ":") + 1))
          }
      }
      function flush(   t) {
          if (table == "") return
          if (targets == "") die("Anmerkung ohne eine einzige Zielzeile: " table "." column)
          printf("{\"dialect\":\"%s\",\"table\":\"%s\",\"column\":\"%s\",\"package\":\"%s\",", dialect, table, column, package)
          printf("\"form\":\"%s\",\"generation\":\"%s\",\"expression\":\"%s\",\"code\":\"%s\",", esc(form), esc(generation), esc(expression), code)
          printf("\"targets\":{%s}}\n", targets)
          table = ""; targets = ""
      }
      FNR == 1 {
          flush()
          if (file_entries == 0 && seen_file != "") die("Seed-Datei ohne Anmerkung: " seen_file)
          seen_file = FILENAME
          file_entries = 0
          dialect = FILENAME
          sub(/.*\//, "", dialect)
          sub(/\.sql$/, "", dialect)
          if (index(dialects, " " dialect " ") == 0) die("unbekannter Dialekt im Dateinamen: " dialect)
      }
      /^--[ \t]*seed:/ {
          flush()
          rest = $0
          sub(/^--[ \t]*seed:[ \t]*/, "", rest)
          # Vor dem ersten `|` steht <tabelle>.<spalte>, dahinter die Felder.
          spec = rest
          sub(/[ \t]*\|.*$/, "", spec)
          spec = clean(spec)
          if (spec !~ /^[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*$/) die("seed-Zeile ohne <tabelle>.<spalte>: " spec)
          if (rest !~ /\|/) die("seed-Zeile ohne paket und quelle: " spec)
          split_fields(substr(rest, index(rest, "|") + 1))
          if (!("paket" in fields) || !("quelle" in fields)) die("seed-Zeile ohne paket oder quelle")
          table = substr(spec, 1, index(spec, ".") - 1)
          column = substr(spec, index(spec, ".") + 1)
          package = fields["paket"]
          form = fields["quelle"]
          code = ("code" in fields) ? fields["code"] : "keinen"
          generation = "-"
          expression = "-"
          file_entries++
          next
      }
      /^--[ \t]+generation:/ {
          if (table == "") die("generation-Zeile ohne seed-Zeile")
          rest = $0; sub(/^--[ \t]+generation:[ \t]*/, "", rest)
          generation = clean(rest)
          next
      }
      /^--[ \t]+ausdruck:/ {
          if (table == "") die("ausdruck-Zeile ohne seed-Zeile")
          rest = $0; sub(/^--[ \t]+ausdruck:[ \t]*/, "", rest)
          expression = clean(rest)
          next
      }
      /^--[ \t]+ziel[ \t]/ {
          if (table == "") die("ziel-Zeile ohne seed-Zeile")
          rest = $0
          sub(/^--[ \t]+ziel[ \t]+/, "", rest)
          if (rest !~ /:/) die("ziel-Zeile ohne Doppelpunkt")
          target = clean(substr(rest, 1, index(rest, ":") - 1))
          if (index(dialects, " " target " ") == 0) die("unbekannter Zieldialekt: " target)
          if (target == dialect) die("ziel-Zeile auf den eigenen Dialekt: " target)
          # Hinter dem Dialekt steht die Form, dahinter die Felder.
          after = substr(rest, index(rest, ":") + 1)
          if (after !~ /\|/) die("ziel-Zeile ohne code (Code oder `keinen`)")
          value = clean(substr(after, 1, index(after, "|") - 1))
          if (value == "") die("ziel-Zeile ohne Form")
          split_fields(substr(after, index(after, "|") + 1))
          if (!("code" in fields)) die("ziel-Zeile ohne code (Code oder `keinen`)")
          if (targets != "") targets = targets ","
          targets = targets sprintf("\"%s\":{\"form\":\"%s\",\"code\":\"%s\",\"generation\":\"%s\",\"expression\":\"%s\"}", \
              target, esc(value), fields["code"], esc(("generation" in fields) ? fields["generation"] : "-"), \
              esc(("ausdruck" in fields) ? fields["ausdruck"] : "-"))
          next
      }
      END {
          if (failed) exit 3
          flush()
          if (file_entries == 0 && seen_file != "") die("Seed-Datei ohne Anmerkung: " seen_file)
      }
    ' "$dir"/*.sql)" || return 3
    jq -s '.' <<< "$raw"
}

# --- Formen aus einem Reverse ----------------------------------------

# Die Kurzschreibweise eines neutralen Typs, seiner Erzeugung und seines
# Berechnungsausdrucks — dieselbe, in der die Anmerkungen geschrieben sind.
# shellcheck disable=SC2016 # jq-Programm, keine Shell-Expansion
SILENT_LOSS_FORM_JQ='
  def form_of:
    (.type // "?") as $t
    | if $t == "text" then "text" + (if .max_length then "(\(.max_length))" else "" end)
      elif $t == "decimal" then "decimal" + (if .precision then "(\(.precision),\(.scale // 0))" else "" end)
      elif $t == "float" then "float" + (if .precision then "(\(.precision))" else "" end)
      elif $t == "array" then "array(\(.element_type // "?"))"
      elif $t == "enum" then
        (if .ref_type then "enum(ref:\(.ref_type))"
         elif .values then "enum(\(.values | join(",")))"
         else "enum" end)
      elif $t == "identifier" then "identifier" + (if .auto_increment then "(auto)" else "" end)
      elif $t == "geometry" then "geometry" + (if .srid then "(srid=\(.srid))" else "" end)
      else $t end;
  def generation_of:
    (.generation // null) as $g
    | if $g == null then "-"
      elif $g.type == "identity" then
        "identity(" + ($g.mode // "by_default") + (if $g.legacy_serial_syntax then ",serial" else "" end) + ")"
      elif $g.type == "computed" then "computed(" + (if $g.stored then "stored" else "virtual" end) + ")"
      else ($g.type // "?") end;
  def expression_of: (.generation.expression // "-");
  def columns_of:
    if (.tables | type) != "object" then error("Reverse ohne `tables`") else . end
    | [ .tables | to_entries[] | .key as $t
        | (.value.columns // {} | to_entries[]
           | {key: "\($t).\(.key)",
              value: {form: (.value | form_of), generation: (.value | generation_of),
                      expression: (.value | expression_of), ref: (.value.ref_type // null)}}) ]
    | from_entries;
'

# Die Spalten eines Reverse als {"tabelle.spalte": {form, generation, expression, ref}}.
# $1=Reverse (JSON)
reverse_columns() {
    jq "$SILENT_LOSS_FORM_JQ"' columns_of' "$1"
}

# --- Reports (YAML) ---------------------------------------------------

# Ein Reverse- oder Generate-Report als JSON:
#   {"notes":[{"code","object"}], "skipped":[{"name","code"}]}
# Selbstpruefung: die Zahlen aus `summary` muessen zu den Listen passen —
# sonst hat der Report eine Form, die dieser Leser nicht versteht, und ein
# stiller Verlust saehe aus wie „kein Code noetig". $1=Report (YAML)
report_json() {
    local file="$1"
    [ -f "$file" ] || { printf 'Report fehlt: %s\n' "$file" >&2; return 3; }
    local raw
    raw="$(awk -v file="$file" '
      function die(msg) { printf("Report %s: %s (Zeile %d)\n", file, msg, FNR) > "/dev/stderr"; exit 3 }
      function esc(v) { gsub(/\\/, "\\\\", v); gsub(/"/, "\\\"", v); return v }
      function unquote(v) {
          gsub(/^[ \t]+|[ \t]+$/, "", v)
          if (v ~ /^".*"$/) v = substr(v, 2, length(v) - 2)
          return v
      }
      # Der Wert des Feldes [name] in [line] — oder NOFIELD, wenn die Zeile
      # dieses Feld nicht **traegt**. Der Anker ist Pflicht: `code:` steht auch
      # mitten in einer Meldung (`message: "... code: W200 ..."`), und ein
      # unverankertes Muster mit `substr($0, index($0, ":") + 1)` nahm dann den
      # Text ab dem ERSTEN Doppelpunkt als Code — pinnbar falsch.
      function field(line, name) {
          if (match(line, "^[ \t]*(-[ \t]+)?" name ":") == 0) return NOFIELD
          return unquote(substr(line, RSTART + RLENGTH))
      }
      BEGIN { NOFIELD = "\001kein-feld\001" }
      /^summary:/ { section = "summary"; seen_summary = 1; next }
      /^notes:/ { section = "notes"; next }
      /^skipped_objects:/ { section = "skipped"; next }
      /^[a-z_]+:/ { section = "other"; next }
      section == "summary" {
          v = field($0, "notes"); if (v != NOFIELD) { want_notes = v + 0; next }
          v = field($0, "skipped_objects"); if (v != NOFIELD) { want_skipped = v + 0; next }
          next
      }
      section == "notes" && /^[ \t]*-[ \t]/ { n_notes++; note_code[n_notes] = ""; note_object[n_notes] = "" }
      section == "notes" && n_notes > 0 {
          v = field($0, "code"); if (v != NOFIELD) note_code[n_notes] = v
          v = field($0, "object"); if (v != NOFIELD) note_object[n_notes] = v
      }
      section == "skipped" && /^[ \t]*-[ \t]/ { n_skipped++; skip_name[n_skipped] = ""; skip_code[n_skipped] = "" }
      section == "skipped" && n_skipped > 0 {
          v = field($0, "name"); if (v != NOFIELD) skip_name[n_skipped] = v
          v = field($0, "code"); if (v != NOFIELD) skip_code[n_skipped] = v
      }
      END {
          # Ein Report ohne `summary` ist keine Form, die dieser Leser kennt:
          # `want_notes`/`want_skipped` blieben 0, und leere Listen liefen
          # durch, als waere nichts zu melden gewesen.
          if (!seen_summary) die("ohne summary")
          if (n_notes != want_notes) die(sprintf("summary nennt %d notes, gelesen wurden %d", want_notes, n_notes))
          if (n_skipped != want_skipped) die(sprintf("summary nennt %d skipped_objects, gelesen wurden %d", want_skipped, n_skipped))
          printf("{\"notes\":[")
          for (i = 1; i <= n_notes; i++) {
              if (note_code[i] == "") die("Note ohne code")
              printf("%s{\"code\":\"%s\",\"object\":\"%s\"}", (i > 1 ? "," : ""), esc(note_code[i]), esc(note_object[i]))
          }
          printf("],\"skipped\":[")
          for (i = 1; i <= n_skipped; i++) {
              printf("%s{\"name\":\"%s\",\"code\":\"%s\"}", (i > 1 ? "," : ""), esc(skip_name[i]), esc(skip_code[i]))
          }
          printf("]}\n")
      }
    ' "$file")" || return 3
    jq -c '.' <<< "$raw"
}

# Die Codes eines Reports mit Anzahl, in der Form der uebrigen Code-Listen
# (`W140:1 R205:2`). $1=Report-JSON
report_codes() {
    jq -r '.notes | map(.code) | group_by(.) | map("\(.[0]):\(length)") | join(" ")' <<< "$1"
}

# --- Die Klassen ------------------------------------------------------

# Klasse `quelle` (Form, Erzeugung, Ausdruck) und der Teil von `verloren`, der
# die Quelle betrifft. Den Code-Teil prueft [silent_loss_source_codes].
# $1=Anmerkungen (JSON) $2=Dialekt $3=Spalten des Reverse (JSON)
silent_loss_source() {
    jq -r --argjson cols "$3" --arg d "$2" '
      map(select(.dialect == $d))
      | map(
          (.table + "." + .column) as $key
          | ($cols[$key] // null) as $actual
          | if $actual == null then
              ["verloren \($d): \($key): die Anmerkung nennt eine Spalte, die der Reverse nicht hat"]
            else
              [ (if $actual.form != .form then
                   "quelle \($d): \($key): Form erwartet \u0027\(.form)\u0027, gemessen \u0027\($actual.form)\u0027"
                 else empty end),
                (if .generation != "-" and $actual.generation != .generation then
                   "quelle \($d): \($key): Erzeugung erwartet \u0027\(.generation)\u0027, gemessen \u0027\($actual.generation)\u0027"
                 else empty end),
                (if .expression != "-" and $actual.expression != .expression then
                   "quelle \($d): \($key): Ausdruck erwartet \u0027\(.expression)\u0027, gemessen \u0027\($actual.expression)\u0027"
                 else empty end) ]
            end)
      | flatten | .[]' <<< "$1"
}

# Der Code-Teil der Klasse `quelle`: die Anmerkung nennt einen Code, den der
# Reverse-Report fuer dieses Objekt nicht traegt.
# $1=Anmerkungen $2=Dialekt $3=Report-JSON
silent_loss_source_codes() {
    jq -r --argjson report "$3" --arg d "$2" '
      map(select(.dialect == $d and .code != "keinen"))
      | map(. as $a
            | ($a.table + "." + $a.column) as $key
            | if ([$report.notes[] | select(.code == $a.code and .object == $key)] | length) == 0 then
                "quelle \($d): \($key): der Reverse-Report nennt \($a.code) nicht"
              else empty end)
      | .[]' <<< "$1"
}

# Die Gegenrichtung der Anmerkungen: eine Seed-Spalte (Tabellenpraefix `sl_`)
# **ohne** Anmerkung. Sonst waere ein Seed, den niemand angemerkt hat, im Check
# unsichtbar — „nichts gefunden" statt „nichts geprueft".
# $1=Anmerkungen $2=Dialekt $3=Spalten des Reverse (JSON)
silent_loss_unannotated() {
    jq -r --argjson cols "$3" --arg d "$2" '
      ([.[] | select(.dialect == $d) | "\(.table).\(.column)"]) as $annotated
      | [ ($cols | keys[]) as $key
          | select($key | startswith("sl_"))
          | select(($annotated | index($key)) == null)
          | "anmerkung \($d): \($key): Seed-Spalte ohne Anmerkung" ]
      | .[]' <<< "$1"
}

# Klasse `reftype`, auf jedem Reverse. $1=Reverse (JSON) $2=Bezeichnung
silent_loss_ref_types() {
    jq -r --arg label "$2" '
      (.custom_types // {} | keys) as $known
      | [ .tables // {} | to_entries[] | .key as $t
          | (.value.columns // {} | to_entries[]
             | select(.value.ref_type != null and ((.value.ref_type | IN($known[])) | not))
             | "reftype \($label): \($t).\(.key): ref_type \u0027\(.value.ref_type)\u0027 ohne Eintrag in custom_types") ]
      | .[]' "$1"
}

# Klassen `ziel` und der Ziel-Teil von `verloren`.
# $1=Anmerkungen $2=Quelle $3=Ziel $4=Quellspalten $5=Zielspalten $6=Report-JSON der Zelle
silent_loss_target() {
    jq -r --argjson src "$4" --argjson tgt "$5" --argjson report "$6" --arg s "$2" --arg t "$3" '
      ([$report.skipped[] | .name]) as $skipped
      | ([ ($src | to_entries[]) as $entry
           | select(($tgt[$entry.key] // null) == null)
           | ($entry.key | split(".") | .[1]) as $column
           | select((($skipped | index($entry.key)) == null) and (($skipped | index($column)) == null))
           | "verloren \($s)->\($t): \($entry.key): im Reverse des Ziels nicht vorhanden und nicht in skipped_objects" ])
        + ([ .[] | select(.dialect == $s) | . as $a
             | ($a.table + "." + $a.column) as $key
             | ($a.targets[$t] // null) as $want
             | select($want != null)
             | ($tgt[$key] // null) as $actual
             | if $actual == null then empty
               else
                 [ (if $actual.form != $want.form then
                      "ziel \($s)->\($t): \($key): Form erwartet \u0027\($want.form)\u0027, gemessen \u0027\($actual.form)\u0027"
                    else empty end),
                   (if $want.generation != "-" and $actual.generation != $want.generation then
                      "ziel \($s)->\($t): \($key): Erzeugung erwartet \u0027\($want.generation)\u0027, gemessen \u0027\($actual.generation)\u0027"
                    else empty end),
                   (if $want.expression != "-" and $actual.expression != $want.expression then
                      "ziel \($s)->\($t): \($key): Ausdruck erwartet \u0027\($want.expression)\u0027, gemessen \u0027\($actual.expression)\u0027"
                    else empty end),
                   # M1: eine Degradierung ohne Code im Generate-Report.
                   ((($a.form != $want.form) or ($a.generation != "-" and $a.generation != $want.generation)) as $degraded
                    | if $degraded | not then empty
                      elif $want.code == "keinen" then
                        "ziel \($s)->\($t): \($key): Degradierung \($a.form)/\($a.generation) -> \($want.form)/\($want.generation) ohne Code (Anmerkung sagt keinen)"
                      elif ([$report.notes[]
                             | select(.code == $want.code
                                      and (.object == $key or .object == $a.column))] | length) == 0 then
                        # Der Code muss **diesem Objekt** gelten: ein Code
                        # irgendwo im Report sagt ueber diese Spalte nichts.
                        "ziel \($s)->\($t): \($key): Degradierung ohne \($want.code) im Generate-Report"
                      else empty end) ]
               end ] | flatten)
      | .[]' <<< "$1"
}

# --- Die Liste bekannter Befunde --------------------------------------

# Verstoesse, die heute auftreten **duerfen**, weil ein benanntes Paket sie
# aufloest. Wortgleich der Verstoss, hinter dem `|` das Paket. Die Liste ist
# **Code**: `--update-expectations` erweitert sie nicht, und ein Eintrag, der
# im Lauf nicht auftritt, ist ein Fehlschlag — der Befund ist weg, die Liste
# gehoert nachgezogen. Das Paket streicht seinen Eintrag in dem Commit, der den
# Fix bringt.
SILENT_LOSS_KNOWN=(
    "quelle postgresql: sl_pg_identity_int.id: Form erwartet 'integer', gemessen 'identifier(auto)'|S1 (Plan 2)"
    "quelle postgresql: sl_pg_identity_int.id: Erzeugung erwartet 'identity(always)', gemessen '-'|S1 (Plan 2)"
    "ziel postgresql->mysql: sl_pg_identity_int.id: Degradierung ohne W163 im Generate-Report|S1 und P10 (Plan 2)"
    "ziel postgresql->sqlite: sl_pg_identity_int.id: Degradierung ohne W163 im Generate-Report|S1 und P10 (Plan 2)"
    "ziel postgresql->mssql: sl_pg_identity_int.id: Degradierung integer/identity(always) -> identifier(auto)/- ohne Code (Anmerkung sagt keinen)|Befund open/mssql-integer-identity-pk-verliert-den-modus.md — S1 hat den PostgreSQL-Fall behoben, die Zielseite auf SQL Server ist eine Typfrage"
    "ziel postgresql->sqlite: sl_pg_json.payload_json: Degradierung json/- -> text/- ohne Code (Anmerkung sagt keinen)|Befund open/sqlite-generate-verschweigt-typmarke-und-laenge.md"
    "ziel postgresql->sqlite: sl_pg_json.payload_jsonb: Degradierung json/- -> text/- ohne Code (Anmerkung sagt keinen)|Befund open/sqlite-generate-verschweigt-typmarke-und-laenge.md"
    "ziel mysql->sqlite: sl_my_expr.note: Degradierung text(40)/- -> text/- ohne Code (Anmerkung sagt keinen)|Befund open/sqlite-generate-verschweigt-typmarke-und-laenge.md"
    "ziel mysql->sqlite: sl_my_expr.stufe: Degradierung text(10)/- -> text/- ohne Code (Anmerkung sagt keinen)|Befund open/sqlite-generate-verschweigt-typmarke-und-laenge.md"
    "ziel mysql->mssql: sl_my_expr.stufe: Degradierung text(10)/- -> text(5)/- ohne Code (Anmerkung sagt keinen)|D1 (Plan 4) — SQL Server leitet den Typ einer berechneten Spalte ab"
    "ziel mssql->sqlite: sl_ms_calc.Summe: Degradierung ohne W200 im Generate-Report|Befund open/sqlite-generate-verschweigt-typmarke-und-laenge.md — W200 trifft die berechnete Spalte nicht"
)

# Das Paket zu einem bekannten Befund — oder Rueckgabe 1, wenn er keiner ist.
# $1=Verstoss
silent_loss_known_package() {
    local entry
    for entry in "${SILENT_LOSS_KNOWN[@]:-}"; do
        [ -n "$entry" ] || continue
        if [ "${entry%%|*}" = "$1" ]; then
            printf '%s\n' "${entry#*|}"
            return 0
        fi
    done
    return 1
}

# Alle bekannten Befunde, je Zeile einer (ohne das Paket).
silent_loss_known_list() {
    local entry
    for entry in "${SILENT_LOSS_KNOWN[@]:-}"; do
        [ -n "$entry" ] || continue
        printf '%s\n' "${entry%%|*}"
    done
}
