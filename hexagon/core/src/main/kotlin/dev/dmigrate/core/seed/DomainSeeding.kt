package dev.dmigrate.core.seed

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DomainBaseTypes
import dev.dmigrate.core.model.NeutralType
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/**
 * Eine Domain-Spalte fuer `data seed`: die Spalte als ihr Basistyp ([DomainBaseTypes]) und der `CHECK` der Domain.
 *
 * Der Seed erzeugt Werte des Basistyps, die den `CHECK` der Domain erfuellen. Versteht er den `CHECK` nicht
 * ([DomainCheck.parse]), kann er keinen Wert garantieren, der ihn erfuellt, und meldet es mit
 * [SeedPreflightException] -- statt die Spalte still auf `NULL` zu setzen oder einen Wert zu schreiben, an dem die
 * Datenbank scheitert. `--rules` fuer die Spalte umgeht die Pruefung (der Autor kennt den `CHECK`).
 */
internal class DomainSeed(
    val domainName: String,
    val baseType: NeutralType?,
    private val checkText: String?,
) {
    private val check: DomainCheck? = checkText?.let(DomainCheck::parse)

    fun generate(
        table: String, column: String, generator: ColumnValueGenerator, random: Random,
    ): Any? {
        val type = baseType ?: throw SeedPreflightException(
            "Spalte '$table.$column' hat den Domain-Typ '$domainName', dessen Basistyp `data seed` nicht aufloesen " +
                "kann (Domain ueber einer Domain oder Basistyp ohne neutrales Gegenstueck); mit --rules einen Wert vorgeben.",
        )
        if (checkText == null) return generator.generate(type)
        val parsed = check ?: throw SeedPreflightException(
            "Spalte '$table.$column': `data seed` versteht den CHECK der Domain '$domainName' ($checkText) nicht " +
                "und kann keinen Wert garantieren, der ihn erfuellt; mit --rules einen Wert vorgeben.",
        )
        return DomainValues(random, generator).generate(type, parsed)
            ?: throw SeedPreflightException(
                "Spalte '$table.$column': `data seed` findet keinen Wert des Typs $type, der den CHECK der Domain " +
                    "'$domainName' ($checkText) erfuellt; mit --rules einen Wert vorgeben.",
            )
    }

    companion object {
        /** Die Domain-Spalten einer Tabelle, je Spaltenname; die Spalte selbst bleibt unveraendert. */
        fun of(
            columns: Map<String, ColumnDefinition>,
            customTypes: Map<String, CustomTypeDefinition>,
        ): Map<String, DomainSeed> = columns.mapNotNull { (name, column) ->
            val ref = (column.type as? NeutralType.Enum)?.refType ?: return@mapNotNull null
            val domain = customTypes[ref]?.takeIf { it.kind == CustomTypeKind.DOMAIN } ?: return@mapNotNull null
            val base = DomainBaseTypes.baseTypeOf(domain)
            name to DomainSeed(ref, base, domain.check)
        }.toMap()
    }
}

/** Werte eines Basistyps unter den Schranken und Listen eines [DomainCheck]; `null`, wenn keiner gefunden wird. */
private class DomainValues(private val random: Random, private val generator: ColumnValueGenerator) {

    fun generate(type: NeutralType, check: DomainCheck): Any? {
        check.allowedValues()?.let { return fromList(type, it, check) }
        return when (type) {
            is NeutralType.SmallInt, NeutralType.Integer, NeutralType.BigInteger -> integer(type, check)
            is NeutralType.Decimal -> decimal(type, check)
            is NeutralType.Float -> float(check)
            else -> sample(check) { generator.generate(type) }
        }
    }

    private fun fromList(type: NeutralType, values: List<String>, check: DomainCheck): Any? {
        val typed = values.map { toType(type, it) }.filter { check.accepts(it) }
        return typed.takeIf { it.isNotEmpty() }?.random(random)
    }

    private fun toType(type: NeutralType, literal: String): Any = when (type) {
        is NeutralType.SmallInt, NeutralType.Integer, NeutralType.BigInteger ->
            literal.toBigDecimalOrNull()?.toLong() ?: literal
        is NeutralType.Decimal -> literal.toBigDecimalOrNull() ?: literal
        is NeutralType.Float -> literal.toDoubleOrNull() ?: literal
        else -> literal
    }

    private inline fun sample(check: DomainCheck, next: () -> Any?): Any? {
        repeat(MAX_ATTEMPTS) {
            val candidate = next()
            if (check.accepts(candidate)) return candidate
        }
        return null
    }

    private fun integer(type: NeutralType, check: DomainCheck): Long? {
        val (typeMin, typeMax) = when (type) {
            is NeutralType.SmallInt -> SMALLINT_MIN to SMALLINT_MAX
            is NeutralType.BigInteger -> Long.MIN_VALUE to Long.MAX_VALUE
            else -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
        }
        val span = if (type is NeutralType.BigInteger) BIG_SPAN else SPAN
        val b = check.bounds()
        val lo = b.lowestAt(0)
        val hi = b.highestAt(0)
        // Eine offene Seite bekommt eine feste Spanne, damit die Werte klein bleiben; der Typbereich begrenzt beide.
        val from = maxOf(typeMin, lo?.toLong() ?: (hi?.toLong()?.minus(span) ?: -span))
        val to = minOf(typeMax, hi?.toLong() ?: (lo?.toLong()?.plus(span) ?: span))
        if (from > to) return null
        return sample(check) { random.nextLong(from, to + 1) } as Long?
    }

    private fun decimal(type: NeutralType.Decimal, check: DomainCheck): BigDecimal? {
        val scale = type.scale.coerceAtLeast(0)
        val step = BigDecimal.ONE.movePointLeft(scale)
        val limit = BigDecimal.TEN.pow((type.precision - scale).coerceIn(1, DECIMAL_DIGITS)).subtract(step)
        val b = check.bounds()
        val lo = b.lowestAt(scale)
        val hi = b.highestAt(scale)
        val from = lo ?: (hi?.let { (it - DECIMAL_SPAN).max(limit.negate()) } ?: DECIMAL_SPAN.negate().max(limit.negate()))
        val to = hi ?: (lo?.let { (it + DECIMAL_SPAN).min(limit) } ?: DECIMAL_SPAN.min(limit))
        if (from > to) return null
        return sample(check) {
            from.add(to.subtract(from).multiply(BigDecimal(random.nextDouble()))).setScale(scale, RoundingMode.HALF_UP)
                .max(from).min(to)
        } as BigDecimal?
    }

    private fun float(check: DomainCheck): Double? {
        val b = check.bounds()
        val lo = b.lower?.value?.toDouble()
        val hi = b.upper?.value?.toDouble()
        val from = lo ?: (hi?.let { it - FLOAT_SPAN } ?: -FLOAT_SPAN)
        val to = hi ?: (lo?.let { it + FLOAT_SPAN } ?: FLOAT_SPAN)
        if (from > to) return null
        return sample(check) { if (from == to) from else random.nextDouble(from, to) } as Double?
    }

    private companion object {
        const val MAX_ATTEMPTS = 200
        const val SPAN = 1_000_000L
        const val BIG_SPAN = 1_000_000_000_000L
        const val SMALLINT_MIN = -32_768L
        const val SMALLINT_MAX = 32_767L
        const val DECIMAL_DIGITS = 6
        val DECIMAL_SPAN: BigDecimal = BigDecimal(SPAN)
        const val FLOAT_SPAN = 1_000_000.0
    }
}
