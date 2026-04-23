package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.metadata.JdbcOperations

internal sealed interface SequenceRowAssessment

internal data class ValidCandidate(
    val key: String,
    val sequence: SequenceDefinition,
) : SequenceRowAssessment

internal data class InvalidEvidence(
    val key: String,
    val reason: String,
) : SequenceRowAssessment

private fun java.math.BigInteger.toLongWithinRange(): Long? {
    val min = java.math.BigInteger.valueOf(Long.MIN_VALUE)
    val max = java.math.BigInteger.valueOf(Long.MAX_VALUE)
    return if (this in min..max) toLong() else null
}

private fun java.math.BigDecimal.toLongWithinRange(): Long? {
    val min = java.math.BigDecimal.valueOf(Long.MIN_VALUE)
    val max = java.math.BigDecimal.valueOf(Long.MAX_VALUE)
    return if (scale() == 0 && this >= min && this <= max) {
        toLong()
    } else {
        null
    }
}

private fun java.math.BigInteger.toIntWithinRange(): Int? {
    val min = java.math.BigInteger.valueOf(Int.MIN_VALUE.toLong())
    val max = java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong())
    return if (this in min..max) toInt() else null
}

private fun java.math.BigDecimal.toIntWithinRange(): Int? {
    val min = java.math.BigDecimal.valueOf(Int.MIN_VALUE.toLong())
    val max = java.math.BigDecimal.valueOf(Int.MAX_VALUE.toLong())
    return if (scale() == 0 && this >= min && this <= max) {
        toInt()
    } else {
        null
    }
}

private fun safeLong(value: Any?): Long? {
    if (value == null) return null
    return when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is java.math.BigInteger -> value.toLongWithinRange()
        is java.math.BigDecimal -> value.toLongWithinRange()
        else -> null
    }
}

private fun safeInt(value: Any?): Int? {
    if (value == null) return null
    return when (value) {
        is Int -> value
        is Short -> value.toInt()
        is Byte -> value.toInt()
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
        is java.math.BigInteger -> value.toIntWithinRange()
        is java.math.BigDecimal -> value.toIntWithinRange()
        else -> null
    }
}

private fun safeCycleInt(value: Any?): Int? {
    if (value == null) return null
    return when (value) {
        is Boolean -> if (value) 1 else 0
        is Int -> value
        is Short -> value.toInt()
        is Byte -> value.toInt()
        is Long -> if (value in 0L..1L) value.toInt() else value.toInt()
        else -> null
    }
}

internal fun mapSequenceRow(row: Map<String, Any?>): SequenceRowSnapshot {
    val rawMin = row["min_value"]
    val rawMax = row["max_value"]
    val rawCache = row["cache_size"]
    return SequenceRowSnapshot(
        name = row["name"] as? String,
        nextValue = safeLong(row["next_value"]),
        incrementBy = safeLong(row["increment_by"]),
        minValue = safeLong(rawMin),
        maxValue = safeLong(rawMax),
        cycleEnabledRaw = safeCycleInt(row["cycle_enabled"]),
        cacheSize = safeInt(rawCache),
        managedBy = row["managed_by"] as? String,
        formatVersion = row["format_version"] as? String,
        minValueOverflow = rawMin != null && safeLong(rawMin) == null,
        maxValueOverflow = rawMax != null && safeLong(rawMax) == null,
        cacheSizeOverflow = rawCache != null && safeInt(rawCache) == null,
    )
}

internal fun detectAmbiguousKeys(
    rows: List<SequenceRowSnapshot>,
): Pair<Set<String>, Set<String>> {
    fun isDMigrate(row: SequenceRowSnapshot) =
        row.managedBy?.trim() == "d-migrate" &&
            row.formatVersion?.trim() == "mysql-sequence-v1"

    val names = rows
        .filter { isDMigrate(it) && !it.name?.trim().isNullOrEmpty() }
        .map { it.name!!.trim() }
    val ambiguous = names.groupBy { it }.filter { it.value.size > 1 }.keys
    return ambiguous to names.filter { it !in ambiguous }.toSet()
}

internal fun scanRoutineStates(
    session: JdbcOperations,
    database: String,
) = linkedMapOf(
    MysqlSequenceNaming.NEXTVAL_ROUTINE to MysqlMetadataQueries.lookupSupportRoutine(
        session,
        database,
        MysqlSequenceNaming.NEXTVAL_ROUTINE,
    ),
    MysqlSequenceNaming.SETVAL_ROUTINE to MysqlMetadataQueries.lookupSupportRoutine(
        session,
        database,
        MysqlSequenceNaming.SETVAL_ROUTINE,
    ),
)

internal fun assessSequenceRow(
    row: SequenceRowSnapshot,
    ambiguousKeys: Set<String>,
): SequenceRowAssessment? {
    val managedBy = row.managedBy?.trim() ?: return null
    val formatVersion = row.formatVersion?.trim() ?: return null
    if (managedBy != "d-migrate" || formatVersion != "mysql-sequence-v1") {
        return null
    }

    val name = row.name?.trim()
    if (name.isNullOrEmpty()) {
        return InvalidEvidence("(empty)", "sequence name is empty or null")
    }
    if (name in ambiguousKeys) return null

    return validateSequenceRow(name, row)
}

private fun validateSequenceRow(
    name: String,
    row: SequenceRowSnapshot,
): SequenceRowAssessment {
    val nextValue = row.nextValue
        ?: return InvalidEvidence(name, "next_value is not readable")
    val incrementBy = row.incrementBy
        ?: return InvalidEvidence(name, "increment_by is not readable")
    if (incrementBy == 0L) {
        return InvalidEvidence(name, "increment_by = 0 is invalid")
    }

    val cycleRaw = row.cycleEnabledRaw
    if (cycleRaw == null || (cycleRaw != 0 && cycleRaw != 1)) {
        return InvalidEvidence(
            name,
            "cycle_enabled value '$cycleRaw' is not a canonical boolean (0/1)",
        )
    }
    if (row.minValueOverflow) {
        return InvalidEvidence(name, "min_value exceeds Long range")
    }
    if (row.maxValueOverflow) {
        return InvalidEvidence(name, "max_value exceeds Long range")
    }
    if (row.cacheSizeOverflow) {
        return InvalidEvidence(name, "cache_size exceeds Int range")
    }

    val cacheSize = row.cacheSize
    if (cacheSize != null && cacheSize < 0) {
        return InvalidEvidence(name, "cache_size = $cacheSize is negative")
    }

    return ValidCandidate(
        key = name,
        sequence = SequenceDefinition(
            description = null,
            start = nextValue,
            increment = incrementBy,
            minValue = row.minValue,
            maxValue = row.maxValue,
            cycle = cycleRaw == 1,
            cache = cacheSize,
        ),
    )
}

internal fun buildSupportSequenceNotes(
    ambiguousKeys: Set<String>,
    invalids: List<InvalidEvidence>,
    sequenceKeys: Set<String>,
    routineStates: Map<String, SupportRoutineState>,
): List<SchemaReadNote> {
    val allNotes = mutableListOf<SchemaReadNote>()
    allNotes += ambiguousKeys.map { name ->
        SchemaReadNote(
            severity = SchemaReadSeverity.WARNING,
            code = "W116",
            objectName = name,
            message = "Sequence metadata reconstructed, but multiple rows claim key '$name'",
        )
    }
    allNotes += invalids.map { invalid ->
        SchemaReadNote(
            severity = SchemaReadSeverity.WARNING,
            code = "W116",
            objectName = invalid.key,
            message = "Sequence metadata reconstructed, but row is invalid: ${invalid.reason}",
        )
    }

    val routineCauses = mutableListOf<String>()
    for ((name, state) in routineStates) {
        when (state) {
            SupportRoutineState.MISSING ->
                routineCauses += "support routine '$name' is missing"
            SupportRoutineState.NOT_ACCESSIBLE ->
                routineCauses += "support routine '$name' is not accessible"
            SupportRoutineState.NON_CANONICAL ->
                routineCauses += "support routine '$name' is not canonical"
            SupportRoutineState.CONFIRMED -> Unit
        }
    }
    if (sequenceKeys.isNotEmpty() && routineCauses.isNotEmpty()) {
        for (seqKey in sequenceKeys) {
            allNotes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "W116",
                objectName = seqKey,
                message = "Sequence metadata reconstructed, but required support objects are missing or degraded",
                hint = routineCauses.joinToString("; "),
            )
        }
    }

    return allNotes
}
