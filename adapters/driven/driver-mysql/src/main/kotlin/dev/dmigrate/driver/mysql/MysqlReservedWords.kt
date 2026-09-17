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
 * keine Aggregation, kein Fensterausdruck. Die reservierten Woerter, die dort
 * trotzdem als **Syntax** vorkommen, stehen unten in [EXPRESSION_SYNTAX] und
 * bleiben nackt — `` `and` `` waere kein Operator mehr. Zwei weitere
 * Stellungen entscheidet der Scanner strukturell statt ueber eine Liste: ein
 * Wort unmittelbar vor `(` ist ein Funktionsaufruf (`left(x,1)`, `char(65)`),
 * und ein Wort unmittelbar hinter `AS` ist ein Typname (`cast(x as signed)`).
 *
 * **Was bleibt:** ein Wort, das beides sein kann — `binary`, `interval`,
 * `char`, `collate` sind Operator **und** moeglicher Spaltenname. Sie bleiben
 * nackt; eine Spalte dieses Namens in einem CHECK scheitert weiter am Server,
 * laut und mit der Meldung des Servers. Ohne Parser ist die Stellung nicht zu
 * entscheiden, und ein falsch gesetztes Quoting waere schlimmer als ein
 * fehlendes.
 */
internal object MysqlReservedWords {

    /**
     * Ob [word] in einem skalaren Ausdruck quotiert werden muss, damit MySQL
     * es als Bezeichner liest. Die Stellung (vor `(`, hinter `AS`) prueft der
     * Aufrufer.
     */
    fun mustQuoteAsIdentifier(word: String): Boolean {
        val lower = word.lowercase()
        return lower in RESERVED && lower !in EXPRESSION_SYNTAX
    }

    /**
     * Die Woerter, die ein **skalarer** MySQL-Ausdruck als Syntax traegt:
     * Operatoren und Praedikate, die Werte-Funktionen ohne Klammern, die
     * Zeiteinheiten hinter `INTERVAL` und die Wortbestandteile einer
     * Typangabe. Funktionsnamen fehlen absichtlich — sie stehen vor `(` und
     * werden dort erkannt.
     */
    private val EXPRESSION_SYNTAX = setOf(
        // Operatoren, Praedikate, Literale
        "and", "or", "xor", "not", "is", "null", "true", "false",
        "between", "case", "when", "then", "else",
        "in", "like", "regexp", "rlike", "match", "div", "mod",
        "binary", "collate", "interval", "distinct", "default", "as", "using", "separator",
        // Werte ohne Klammern
        "current_date", "current_time", "current_timestamp", "current_user",
        "localtime", "localtimestamp", "utc_date", "utc_time", "utc_timestamp",
        // Zeiteinheiten hinter INTERVAL (nur die reservierten; `day`, `hour`,
        // `month`, `second`, `year` sind in MySQL nicht reserviert)
        "day_hour", "day_microsecond", "day_minute", "day_second",
        "hour_microsecond", "hour_minute", "hour_second",
        "minute_microsecond", "minute_second", "second_microsecond", "year_month",
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
