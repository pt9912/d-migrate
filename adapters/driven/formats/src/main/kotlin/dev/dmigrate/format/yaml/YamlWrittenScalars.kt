package dev.dmigrate.format.yaml

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonPointer
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.BigIntegerNode
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.DoubleNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.dmigrate.format.WrittenScalar

/**
 * Markiert in einem gelesenen YAML-Baum die Skalare, die der YAML-1.1-Resolver **verlustbehaftet**
 * typisiert hat: `OFF` → `false`, `010` → `8`, `1.10` → `1.1`, `1e3` → `1000.0`, `~` → `null`.
 *
 * Jackson liest den Baum wie bisher (samt der Pruefung auf doppelte Schluessel); ein zweiter Lauf
 * ueber die Tokens vergleicht danach je Skalar den **geschriebenen** Text (`JsonParser.getText()`,
 * den der YAML-Parser auch fuer aufgeloeste Skalare liefert) mit dem Text des aufgeloesten Knotens.
 * Weicht er ab, ersetzt eine Unterklasse den Knoten. Fuer ein typisiertes Feld (`required: yes`)
 * verhaelt sie sich wie der aufgeloeste Knoten; erst wer sie als Text liest, bekommt einen Fehler
 * ([dev.dmigrate.format.asSchemaText]). Ein `null` in einer Liste wird immer markiert: eine
 * Werteliste kennt keinen leeren Eintrag.
 *
 * Verlustfreie Skalare bleiben unberuehrt: `version: 1.0` liest sich als `"1.0"`, `values: [1, 2]`
 * als `"1"`, `"2"`.
 */
internal object YamlWrittenScalars {

    fun mark(root: JsonNode, parser: JsonParser): JsonNode {
        parser.use { p ->
            while (true) {
                val token = p.nextToken() ?: break
                if (!token.isScalarValue || token == JsonToken.VALUE_STRING) continue
                val pointer = p.parsingContext.pathAsPointer()
                replace(root, pointer, p.text)
            }
        }
        return root
    }

    private fun replace(root: JsonNode, pointer: JsonPointer, written: String) {
        // Ein Dokument, das nur aus einem Skalar besteht, hat keinen Elternknoten; dort gibt es auch kein
        // Textfeld, und der Parser meldet das Dokument selbst als ungueltig.
        val head = pointer.head() ?: return
        val parent = root.at(head)
        val resolved = root.at(pointer)
        if (resolved.isMissingNode) return
        val inArray = parent is ArrayNode
        if (resolved.asText() == written && !(inArray && resolved.isNull)) return
        val marked = wrap(resolved, written, dotted(root, pointer)) ?: return
        when (parent) {
            is ObjectNode -> parent.set<JsonNode>(pointer.last().matchingProperty, marked)
            is ArrayNode -> parent.set(pointer.last().matchingIndex, marked)
            else -> Unit
        }
    }

    private fun wrap(node: JsonNode, written: String, path: String): JsonNode? = when (node) {
        is BooleanNode -> WrittenBoolean(node.booleanValue(), written, path)
        is IntNode -> WrittenInt(node.intValue(), written, path)
        is LongNode -> WrittenLong(node.longValue(), written, path)
        is BigIntegerNode -> WrittenBigInteger(node.bigIntegerValue(), written, path)
        is DoubleNode -> WrittenDouble(node.doubleValue(), written, path)
        is NullNode -> WrittenNull(written, path)
        // Gleitkomma liest der Mapper als DoubleNode (kein USE_BIG_DECIMAL_FOR_FLOATS); andere Knoten
        // entstehen aus einem Skalar nicht.
        else -> null
    }

    /** `/tables/t/columns/a/values/0` → `tables.t.columns.a.values[0]`. */
    private fun dotted(root: JsonNode, pointer: JsonPointer): String {
        val out = StringBuilder()
        var node: JsonNode? = root
        var current: JsonPointer = pointer
        while (!current.matches()) {
            val segment = current.matchingProperty
            if (node is ArrayNode) {
                out.append('[').append(segment).append(']')
            } else {
                if (out.isNotEmpty()) out.append('.')
                out.append(segment)
            }
            node = node?.at(JsonPointer.compile("/" + segment.replace("~", "~0").replace("/", "~1")))
            current = current.tail()
        }
        return out.toString()
    }
}

private class WrittenBoolean(value: Boolean, override val written: String, override val path: String) :
    BooleanNode(value), WrittenScalar {
    override val kind: String get() = "Boolean"
}

private class WrittenInt(value: Int, override val written: String, override val path: String) :
    IntNode(value), WrittenScalar {
    override val kind: String get() = "Zahl"
}

private class WrittenLong(value: Long, override val written: String, override val path: String) :
    LongNode(value), WrittenScalar {
    override val kind: String get() = "Zahl"
}

private class WrittenBigInteger(value: java.math.BigInteger, override val written: String, override val path: String) :
    BigIntegerNode(value), WrittenScalar {
    override val kind: String get() = "Zahl"
}

private class WrittenDouble(value: Double, override val written: String, override val path: String) :
    DoubleNode(value), WrittenScalar {
    override val kind: String get() = "Zahl"
}

private class WrittenNull(override val written: String, override val path: String) : NullNode(), WrittenScalar {
    override val kind: String get() = "null"
}
