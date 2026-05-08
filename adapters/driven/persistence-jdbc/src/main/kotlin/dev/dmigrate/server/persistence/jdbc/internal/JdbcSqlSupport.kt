package dev.dmigrate.server.persistence.jdbc.internal

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

/**
 * Kleine, adapter-private JDBC-Helfer fuer den persistence-jdbc-Adapter.
 * Spart pro SQL-Statement die manuelle Index-Buchhaltung der
 * `setX`-Aufrufe und macht die Stores im LF-012 / LN-011 / LN-017 / LN-027 SQL-Pattern lesbar.
 *
 * Bewusst minimal — kein DSL-Anspruch, keine ORM-Anleihen. Alles bleibt
 * im JDBC-Stil, nur die Bind-Schleife ist abstrahiert.
 */

internal fun PreparedStatement.bindAll(vararg params: Any?): PreparedStatement = apply {
    params.forEachIndexed { idx, value ->
        bind(idx + 1, value)
    }
}

internal fun PreparedStatement.bind(index: Int, value: Any?) {
    when (value) {
        null -> setNull(index, Types.NULL)
        is String -> setString(index, value)
        is Int -> setInt(index, value)
        is Long -> setLong(index, value)
        is Boolean -> setBoolean(index, value)
        is Instant -> setTimestamp(index, Timestamp.from(value))
        else -> setObject(index, value)
    }
}

internal fun ResultSet.getInstantOrNull(label: String): Instant? =
    getTimestamp(label)?.toInstant()

internal fun ResultSet.getInstant(label: String): Instant =
    getInstantOrNull(label)
        ?: error("expected non-null timestamp for column '$label'")

/**
 * `block` bekommt einen ResultSet exakt einmal — falls die Query keine
 * Zeile liefert, wird `null` zurueckgegeben. Vermeidet `if (rs.next())`
 * Boilerplate und schliesst Statement+ResultSet via `use {}`.
 */
internal fun <T : Any> Connection.querySingle(
    sql: String,
    vararg params: Any?,
    map: (ResultSet) -> T,
): T? = prepareStatement(sql).use { ps ->
    ps.bindAll(*params)
    ps.executeQuery().use { rs ->
        if (rs.next()) map(rs) else null
    }
}

internal fun Connection.executeUpdate(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { ps ->
        ps.bindAll(*params)
        ps.executeUpdate()
    }
