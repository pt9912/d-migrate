package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import java.io.ByteArrayOutputStream

internal object MysqlSequenceSqlCodec {
    fun quoteIdentifier(value: String): String =
        SqlIdentifiers.quoteIdentifier(value, DatabaseDialect.MYSQL)

    fun quoteStringLiteral(value: String): String =
        SqlIdentifiers.quoteStringLiteral(value.replace("\\", "\\\\"))

    fun markerValue(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            if (isMarkerSafeByte(unsigned)) {
                append(unsigned.toChar())
            } else {
                append('%')
                append(markerHex[unsigned ushr 4])
                append(markerHex[unsigned and 0x0f])
            }
        }
    }

    fun decodeMarkerValue(value: String): String {
        val output = StringBuilder()
        val bytes = ByteArrayOutputStream()

        fun flushBytes() {
            if (bytes.size() == 0) return
            output.append(bytes.toByteArray().toString(Charsets.UTF_8))
            bytes.reset()
        }

        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                val high = hexValue(value[index + 1])
                val low = hexValue(value[index + 2])
                if (high >= 0 && low >= 0) {
                    bytes.write((high shl 4) + low)
                    index += 3
                    continue
                }
            }
            flushBytes()
            output.append(value[index])
            index++
        }

        flushBytes()
        return output.toString()
    }

    fun unescapeStringLiteral(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '\'' && index + 1 < value.length && value[index + 1] == '\'' -> {
                    append('\'')
                    index += 2
                }
                char == '\\' && index + 1 < value.length -> {
                    append(unescapeBackslash(value[index + 1]))
                    index += 2
                }
                else -> {
                    append(char)
                    index++
                }
            }
        }
    }

    private fun isMarkerSafeByte(value: Int): Boolean =
        value in 'A'.code..'Z'.code ||
            value in 'a'.code..'z'.code ||
            value in '0'.code..'9'.code ||
            value == '_'.code ||
            value == '-'.code ||
            value == '.'.code

    private fun hexValue(value: Char): Int = when (value) {
        in '0'..'9' -> value - '0'
        in 'a'..'f' -> value - 'a' + 10
        in 'A'..'F' -> value - 'A' + 10
        else -> -1
    }

    private fun unescapeBackslash(value: Char): Char = when (value) {
        '0' -> '\u0000'
        '\'' -> '\''
        '"' -> '"'
        'b' -> '\b'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'Z' -> '\u001A'
        '\\' -> '\\'
        else -> value
    }

    private val markerHex = "0123456789ABCDEF".toCharArray()
}
