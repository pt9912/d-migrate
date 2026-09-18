package dev.dmigrate.driver.mysql

/**
 * Welche **nackten** Woerter eines rohen neutralen Ausdrucks MySQL nicht als
 * Bezeichner liest.
 *
 * Das neutrale Modell schreibt einen rein kleingeschriebenen Spaltennamen ohne
 * Quotierung (`NeutralExpressionIdentifier`). Ist der Name in MySQL
 * **reserviert**, ist das dort kein Name mehr: aus `` `key` `` wird beim
 * Zurueckschreiben `key`, und `CHECK (key > 0)` scheitert am Server
 * (`ERROR 1064`, gemessen auf MySQL 9.7.2). Der Generator quotiert solche
 * Woerter deshalb wieder ([MysqlRawExpressionText]).
 *
 * **Die Liste ist gemessen, nicht abgeschrieben:**
 * `SELECT LOWER(WORD) FROM information_schema.KEYWORDS WHERE RESERVED = 1` —
 * auf MySQL 9.7.2 (264 Woerter) und 8.0.46 (262) gefahren, die Vereinigung
 * beider (266). Sie unterscheiden sich in sechs Woertern (`external`,
 * `library`, `qualify`, `tablesample` erst ab 9; `master_bind`,
 * `master_ssl_verify_server_cert` nur bis 8), und die Vereinigung ist die
 * sichere Wahl: ein Wort zu quotieren, das die Version nicht reserviert,
 * bleibt gueltig; es wegzulassen nicht. 8.0.16 ist die Untergrenze, ab der
 * MySQL `CHECK` ueberhaupt durchsetzt (`spec/lastenheft-d-migrate.md`).
 *
 * **Quotiert wird nur, was hier kein Bezeichner sein kann.** Die vier Felder,
 * um die es geht (CHECK, Berechnungsausdruck, Index-Praedikat,
 * Index-Ausdruck), tragen einen **skalaren** Ausdruck: keine Unterabfrage,
 * keine Aggregation, kein Fensterausdruck. Welches reservierte Wort dort
 * trotzdem Syntax ist, haengt an seiner **Stellung**, nicht am Wort allein:
 * `a mod b` ist ein Operator, `mod > 0` ein Spaltenname. Die Trennlinie ist
 * die Operandenstellung.
 *
 * - In **Operandenstellung** beginnt ein Operand: am Ausdrucksanfang, hinter
 *   `(`, hinter `,`, hinter einem Operatorzeichen und hinter einem Wort, auf
 *   das ein Operand folgt ([OPERAND_OPENERS] — `and`, `is`, `when` …). Dort
 *   ist [OPERATOR_SYNTAX] ein Bezeichner und wird quotiert.
 * - In **Operatorstellung** bleibt [OPERATOR_SYNTAX] nackt; `` `and` `` waere
 *   dort kein Operator mehr.
 *
 * Zwei weitere Stellungen entscheidet der Scanner strukturell: ein Wort
 * unmittelbar vor `(` ist ein Funktionsaufruf (`left(x,1)`, `char(65)`), und
 * der **Typname** eines `CAST`/`CONVERT` laeuft bis zur schliessenden Klammer
 * des Aufrufs (`MysqlRawExpressionText`).
 *
 * **Was bleibt:** [OPERAND_SYNTAX] — die Woerter, die auch am Anfang eines
 * Operanden Syntax sind. Sie bleiben in jeder Stellung nackt, und eine Spalte
 * dieses Namens scheitert weiter am Server, laut und mit dessen Meldung; bei
 * `null`, `true` und `false` sogar still (MySQL liest das Literal und nimmt
 * die Anweisung an). Die Grenze steht in
 * `docs/planning/open/nackte-reservierte-woerter-im-rohen-ausdruck.md`.
 */
internal object MysqlReservedWords {

    /**
     * Ob [word] in einem skalaren Ausdruck quotiert werden muss, damit MySQL
     * es als Bezeichner liest. [operandPosition] sagt, ob an dieser Stelle ein
     * Operand beginnt; die uebrigen Stellungen (vor `(`, im Typnamen) prueft
     * der Aufrufer strukturell.
     */
    fun mustQuoteAsIdentifier(word: String, operandPosition: Boolean): Boolean {
        val lower = word.lowercase()
        if (lower !in RESERVED || lower in OPERAND_SYNTAX) return false
        return operandPosition || lower !in OPERATOR_SYNTAX
    }

    /** Ob hinter dem nackt gebliebenen [word] ein Operand beginnt. */
    fun opensOperand(word: String): Boolean = word.lowercase() in OPERAND_OPENERS

    /**
     * Was auch **am Anfang eines Operanden** Syntax ist: die Praefixoperatoren
     * (`not x`, `binary x`, `interval 1 year_month`), der `CASE`-Ausdruck, die
     * Literale, `distinct` hinter der Klammer eines Aggregats und die
     * Werte-Funktionen ohne Klammern. Quotiert bricht jedes davon die
     * Anweisung (gemessen auf 9.7.2 und 8.0.46: `` `binary` note = 'x' ``,
     * `` a is `null` ``, `` d + `interval` 1 year_month ``, `` count(`distinct` a) ``
     * und `` `current_timestamp` `` je `ERROR 1064` bzw. `1054`).
     */
    private val OPERAND_SYNTAX = setOf(
        "not", "binary", "interval", "case", "distinct",
        "null", "true", "false",
        "current_date", "current_time", "current_timestamp", "current_user",
        "localtime", "localtimestamp", "utc_date", "utc_time", "utc_timestamp",
    )

    /**
     * Was **nur in Operatorstellung** Syntax ist: die Infix-Operatoren und
     * -Praedikate, die Fortsetzungen von `CASE`, die Woerter, hinter denen ein
     * Name statt eines Operanden steht (`collate`, `using`, `as`,
     * `separator`), `default` (nur als `default(col)` moeglich, und das steht
     * vor `(`), `match` (nur als `match(…) against(…)`) und die Zeiteinheiten
     * hinter `INTERVAL`. In Operandenstellung ist jedes davon ein Spaltenname
     * — gemessen auf 9.7.2 und 8.0.46: `CHECK (mod > 0)` ist `ERROR 1064`,
     * ``CHECK (`mod` > 0)`` wird angenommen, und umgekehrt bricht
     * `` a > 0 `and` b > 0 `` die Anweisung.
     *
     * Die Zeiteinheiten sind nur die reservierten; `day`, `hour`, `month`,
     * `second` und `year` sind in MySQL nicht reserviert.
     */
    private val OPERATOR_SYNTAX = setOf(
        "and", "or", "xor", "is", "between", "when", "then", "else",
        "in", "like", "regexp", "rlike", "match", "div", "mod",
        "as", "collate", "using", "separator", "default",
        "day_hour", "day_microsecond", "day_minute", "day_second",
        "hour_microsecond", "hour_minute", "hour_second",
        "minute_microsecond", "minute_second", "second_microsecond", "year_month",
    )

    /**
     * Die Teilmenge von [OPERATOR_SYNTAX], hinter der ein **Operand** folgt.
     * Nicht dabei sind die Woerter, hinter denen ein Name oder ein Typ steht
     * (`collate utf8mb4_bin`, `using utf8mb4`, `as signed`, `separator ','`),
     * `match` und `default` (beide stehen vor `(`) — und vor allem `not`:
     * hinter ihm kann ebenso gut Syntax stehen (`a not like 'x'`,
     * `a not between 1 and 2`), die quotiert braeche.
     */
    private val OPERAND_OPENERS = setOf(
        "and", "or", "xor", "is", "between", "when", "then", "else",
        "in", "like", "regexp", "rlike", "div", "mod",
    )

    private val RESERVED = setOf(
        "accessible", "add", "all", "alter", "analyze", "and", "as", "asc", "asensitive", "before",
        "between", "bigint", "binary", "blob", "both", "by", "call", "cascade", "case", "change",
        "char", "character", "check", "collate", "column", "condition", "constraint", "continue",
        "convert", "create", "cross", "cube", "cume_dist", "current_date", "current_time",
        "current_timestamp", "current_user", "cursor", "database", "databases", "day_hour",
        "day_microsecond", "day_minute", "day_second", "dec", "decimal", "declare", "default",
        "delayed", "delete", "dense_rank", "desc", "describe", "deterministic", "distinct",
        "distinctrow", "div", "double", "drop", "dual", "each", "else", "elseif", "empty", "enclosed",
        "escaped", "except", "exists", "exit", "explain", "external", "false", "fetch", "first_value",
        "float", "float4", "float8", "for", "force", "foreign", "from", "fulltext", "function",
        "generated", "get", "grant", "group", "grouping", "groups", "having", "high_priority",
        "hour_microsecond", "hour_minute", "hour_second", "if", "ignore", "in", "index", "infile",
        "inner", "inout", "insensitive", "insert", "int", "int1", "int2", "int3", "int4", "int8",
        "integer", "intersect", "interval", "into", "io_after_gtids", "io_before_gtids", "is",
        "iterate", "join", "json_table", "key", "keys", "kill", "lag", "last_value", "lateral", "lead",
        "leading", "leave", "left", "library", "like", "limit", "linear", "lines", "load", "localtime",
        "localtimestamp", "lock", "long", "longblob", "longtext", "loop", "low_priority",
        "master_bind", "master_ssl_verify_server_cert", "match", "maxvalue", "mediumblob", "mediumint",
        "mediumtext", "middleint", "minute_microsecond", "minute_second", "mod", "modifies", "natural",
        "no_write_to_binlog", "not", "nth_value", "ntile", "null", "numeric", "of", "on", "optimize",
        "optimizer_costs", "option", "optionally", "or", "order", "out", "outer", "outfile", "over",
        "partition", "percent_rank", "precision", "primary", "procedure", "purge", "qualify", "range",
        "rank", "read", "read_write", "reads", "real", "recursive", "references", "regexp", "release",
        "rename", "repeat", "replace", "require", "resignal", "restrict", "return", "revoke", "right",
        "rlike", "row", "row_number", "rows", "schema", "schemas", "second_microsecond", "select",
        "sensitive", "separator", "set", "show", "signal", "smallint", "spatial", "specific", "sql",
        "sql_big_result", "sql_calc_found_rows", "sql_small_result", "sqlexception", "sqlstate",
        "sqlwarning", "ssl", "starting", "stored", "straight_join", "system", "table", "tablesample",
        "terminated", "then", "tinyblob", "tinyint", "tinytext", "to", "trailing", "trigger", "true",
        "undo", "union", "unique", "unlock", "unsigned", "update", "usage", "use", "using", "utc_date",
        "utc_time", "utc_timestamp", "values", "varbinary", "varchar", "varcharacter", "varying",
        "virtual", "when", "where", "while", "window", "with", "write", "xor", "year_month",
        "zerofill",    )
}
