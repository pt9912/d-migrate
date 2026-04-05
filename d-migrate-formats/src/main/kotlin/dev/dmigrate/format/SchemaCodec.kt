package dev.dmigrate.format

import dev.dmigrate.core.model.SchemaDefinition
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlin.io.path.inputStream

interface SchemaCodec {
    fun read(input: InputStream): SchemaDefinition
    fun read(path: Path): SchemaDefinition = path.inputStream().use { read(it) }
    fun write(output: OutputStream, schema: SchemaDefinition)
}
