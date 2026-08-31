package nl.vdzon.pvdd.documents

import java.net.URI
import java.time.Instant
import java.util.UUID

enum class ExtractionStatus {
    PENDING,
    EXTRACTED,
    OCR_REQUIRED,
    UNSUPPORTED,
    TOO_LARGE,
    DOWNLOAD_FAILED,
    INVALID_CONTENT,
}

data class SourceDocument(
    val id: UUID,
    val agendaItemId: UUID,
    val sourceId: String,
    val name: String,
    val sourceUrl: URI,
    val declaredMimeType: String?,
    val detectedMimeType: String?,
    val sha256: String?,
    val sizeBytes: Long?,
    val extractionStatus: ExtractionStatus,
    val fetchedAt: Instant?,
    val errorCode: String?,
    val sections: List<ExtractedSection>,
)

data class ExtractedSection(
    val sequence: Int,
    val pageNumber: Int?,
    val heading: String?,
    val text: String,
)
