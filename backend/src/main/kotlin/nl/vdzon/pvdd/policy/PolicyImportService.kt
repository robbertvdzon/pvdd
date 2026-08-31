package nl.vdzon.pvdd.policy

import java.time.Clock
import java.util.UUID
import java.security.MessageDigest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Service

data class ImportedPolicy(val sourceSha256: String, val chunkCount: Int)

@Service
class PolicyImportService(
    private val source: PolicySourceGateway,
    private val properties: PolicySourceProperties,
    private val repository: PolicyStore,
    private val clock: Clock,
) {
    fun ensureImported(): ImportedPolicy {
        repository.latestSource()?.let { existing -> return ImportedPolicy(existing, repository.countByHash(existing)) }
        return import(source.fetch())
    }

    fun import(pdf: PolicyPdf): ImportedPolicy {
        if (!pdf.bytes.beginsWith(PDF_MAGIC) || !pdf.contentType.isNullOrPdf()) throw PolicySourceException("INVALID_PDF")
        val hash = sha256(pdf.bytes)
        if (repository.countByHash(hash) > 0) return ImportedPolicy(hash, repository.countByHash(hash))
        val fetchedAt = clock.instant()
        val chunks = parsePages(pdf.bytes).flatMap { (page, text) -> chunk(page, text) }
        if (chunks.isEmpty()) throw PolicySourceException("NO_POLICY_TEXT")
        chunks.forEach { parsed ->
            repository.insert(
                PolicyChunk(
                    id = UUID.randomUUID(),
                    sourceUrl = properties.url,
                    sourceSha256 = hash,
                    fetchedAt = fetchedAt,
                    pageNumber = parsed.page,
                    sequence = parsed.sequence,
                    heading = parsed.heading,
                    text = parsed.text,
                    themes = PolicyThemeClassifier.classify(parsed.text),
                ),
            )
        }
        return ImportedPolicy(hash, chunks.size)
    }

    private fun parsePages(bytes: ByteArray): List<Pair<Int, String>> = try {
        Loader.loadPDF(bytes).use { document ->
            if (document.isEncrypted || document.numberOfPages !in 1..100) throw PolicySourceException("INVALID_PDF")
            (1..document.numberOfPages).map { page ->
                page to PDFTextStripper().apply { startPage = page; endPage = page; sortByPosition = true }
                    .getText(document)
                    .replace("\u0000", "")
                    .trim()
            }
        }
    } catch (failure: PolicySourceException) {
        throw failure
    } catch (_: Exception) {
        throw PolicySourceException("INVALID_PDF")
    }

    private fun chunk(page: Int, pageText: String): List<ParsedPolicyChunk> {
        if (pageText.isBlank()) return emptyList()
        val lines = pageText.lines().map { it.trim().replace(WHITESPACE, " ") }.filter { it.isNotBlank() }
        val heading = lines.firstOrNull()?.takeIf { it.length <= 120 }
        val result = mutableListOf<ParsedPolicyChunk>()
        val current = StringBuilder()
        var sequence = 0
        lines.forEach { line ->
            if (current.isNotEmpty() && current.length + line.length + 1 > properties.chunkCharacters) {
                result += ParsedPolicyChunk(page, ++sequence, heading, current.toString())
                current.clear()
            }
            if (line.length > properties.chunkCharacters) {
                line.chunked(properties.chunkCharacters).forEach { part ->
                    if (current.isNotEmpty()) {
                        result += ParsedPolicyChunk(page, ++sequence, heading, current.toString())
                        current.clear()
                    }
                    result += ParsedPolicyChunk(page, ++sequence, heading, part)
                }
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        if (current.isNotEmpty()) result += ParsedPolicyChunk(page, ++sequence, heading, current.toString())
        return result
    }

    private fun ByteArray.beginsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun String?.isNullOrPdf(): Boolean = this == null || substringBefore(';').trim().equals("application/pdf", true)

    private data class ParsedPolicyChunk(val page: Int, val sequence: Int, val heading: String?, val text: String)

    companion object {
        private val PDF_MAGIC = "%PDF-".toByteArray()
        private val WHITESPACE = Regex("[\\t \\r\\x0B\\f]+")
    }
}
