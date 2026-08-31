package nl.vdzon.pvdd.documents

import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service

data class DocumentIngestionSummary(
    val documents: List<SourceDocument>,
    val fullyRead: Boolean,
)

@Service
class DocumentIngestionService(
    private val downloader: DocumentDownloader,
    private val extractor: DocumentExtractor,
    private val repository: DocumentRepository,
    private val properties: DocumentDownloadProperties,
    private val clock: Clock,
) {
    fun ingest(agendaItemId: UUID, references: List<DocumentReference>): DocumentIngestionSummary {
        val budget = DownloadBudget(properties)
        val documents = references.map { reference ->
            try {
                val document = extractor.extract(downloader.download(reference, budget), agendaItemId, clock.instant())
                repository.save(document)
                document
            } catch (failure: DocumentDownloadException) {
                SourceDocument(
                    id = UUID.randomUUID(),
                    agendaItemId = agendaItemId,
                    sourceId = reference.sourceId,
                    name = reference.name,
                    sourceUrl = reference.sourceUrl,
                    declaredMimeType = null,
                    detectedMimeType = null,
                    sha256 = null,
                    sizeBytes = null,
                    extractionStatus = if (failure.code == DocumentDownloadError.TOO_LARGE) {
                        ExtractionStatus.TOO_LARGE
                    } else {
                        ExtractionStatus.DOWNLOAD_FAILED
                    },
                    fetchedAt = null,
                    errorCode = failure.code.name,
                    sections = emptyList(),
                ).also(repository::save)
            }
        }
        return DocumentIngestionSummary(
            documents = documents,
            fullyRead = documents.all { it.extractionStatus == ExtractionStatus.EXTRACTED },
        )
    }
}
