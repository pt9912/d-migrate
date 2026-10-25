package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.sqlite.SqliteDriver
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class CliSchemaMigrateDomainMatrixP0Test : FunSpec({
    fun run(vararg args: String): Int {
        DatabaseDriverRegistry.clear()
        DatabaseDriverRegistry.register(SqliteDriver())
        return try { DMigrate().subcommands(SchemaCommand()).parse(args.toList()); 0 } catch (e: ProgramResult) { e.statusCode }
    }
    fun yaml(flagRequired: Boolean, types: String, column: String): String = """
schema_format: "1.0"
name: "M"
version: "1.0"
custom_types:
$types
tables:
  t:
    columns:
      id: { type: integer, required: true }
      flag: { type: integer, required: $flagRequired }
      n: { $column }
    primary_key: [id]
""".trimStart()
    fun domainType(c: String) =
        if (c == "decimal-10-2") "  d: { kind: domain, base_type: decimal, precision: 10, scale: 2 }"
        else "  d: { kind: domain, base_type: \"$c\" }"
    fun exec(db: Path, vararg s: String) = DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
        c.createStatement().use { st -> s.forEach { st.execute(it) } }
    }
    fun sqlStatements(sql: Path) = Files.readString(sql).lineSequence().filterNot { it.trimStart().startsWith("--") }
        .joinToString("\n").split(";").map { it.trim() }.filter { it.isNotEmpty() }

    test("P0 matrix") {
        val cases = listOf(
            "integer", "biginteger", "smallint", "boolean", "real", "float", "decimal-10-2", "decimal",
            "text", "varchar(32)", "char(5)", "uuid", "json", "xml", "binary", "date", "time", "datetime", "timestamptz",
        )
        val out = StringBuilder("\n")
        val enumT = "  d: { kind: enum, values: [a, b] }"
        val checkT = "  d: { kind: domain, base_type: text, check: \"VALUE IN ('a','b')\" }"
        val checkI = "  d: { kind: domain, base_type: integer, check: \"VALUE > 0\" }"
        val all = cases.map { it to domainType(it) } +
            listOf("enum-ref" to enumT, "domain-wertliste" to checkT, "domain-integer-check" to checkI)
        for ((base, types) in all) {
            val dir = Files.createTempDirectory("p0")
            val a = dir.resolve("a.yaml").also { Files.writeString(it, yaml(false, types, "type: enum, ref_type: d")) }
            val b = dir.resolve("b.yaml").also { Files.writeString(it, yaml(true, types, "type: enum, ref_type: d")) }
            val create = dir.resolve("create.sql")
            val gen = run("schema", "generate", "--source", "$a", "--target", "sqlite", "--output", "$create")
            if (gen != 0) { out.append("$base | generate exit $gen\n"); continue }
            val db = dir.resolve("db.sqlite")
            exec(db, *sqlStatements(create).toTypedArray())
            fun mig(src: Path, name: String, vararg more: String): Pair<Int, String> {
                val code = run("schema", "migrate", "--source", "$src", "--target", "db:sqlite://$db",
                    "--report", "${dir.resolve("$name.json")}", *more)
                val r = Files.readString(dir.resolve("$name.json"))
                val total = Regex("\"operationsTotal\":(\\d+)").find(r)?.groupValues?.get(1)
                val cast = if ("SQLITE_CAST_NOT_WHITELISTED" in r) "CAST_BLOCKED" else ""
                val ids = Regex("\"(?:operationId|id|type|kind)\"\\s*:\\s*\"([^\"]+)\"").findAll(r)
                    .map { it.groupValues[1] }.filter { !it.contains(":") && (it.contains("Alter") || it.contains("Custom")) }
                    .toSet()
                return code to "ops=$total $ids $cast"
            }
            val decl = Regex("(?m)^\\s*\"?n\"?\\s+([^,\\n]*)").find(Files.readString(create))?.groupValues?.get(1)
            out.append("$base | decl=$decl\n")
            val plan = mig(a, "plan")
            val ex = mig(b, "up", "--execute")
            val again = mig(b, "again", "--execute")
            out.append("$base | plan exit=${plan.first} ${plan.second}")
            out.append(" | rebuild exit=${ex.first} ${ex.second}")
            out.append(" | again exit=${again.first} ${again.second}\n")
        }
        error(out.toString())
    }
})
