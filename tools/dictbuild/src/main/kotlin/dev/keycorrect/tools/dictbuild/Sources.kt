package dev.keycorrect.tools.dictbuild

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Opens (optionally .gz / .bz2 compressed) input files as buffered streams / UTF-8 readers. */
object Sources {
    fun openStream(path: Path): InputStream {
        val raw = BufferedInputStream(Files.newInputStream(path), 1 shl 20)
        val name = path.fileName.toString()
        return when {
            name.endsWith(".gz") -> BufferedInputStream(GZIPInputStream(raw, 1 shl 16), 1 shl 20)
            name.endsWith(".bz2") ->
                BufferedInputStream(BZip2CompressorInputStream(raw, true), 1 shl 20)
            else -> raw
        }
    }

    fun openReader(path: Path): BufferedReader =
        BufferedReader(InputStreamReader(openStream(path), StandardCharsets.UTF_8), 1 shl 16)
}
