package nl.vdzon.pvdd.documents

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("pvdd.document-download")
data class DocumentDownloadProperties(
    var maxDocumentsPerMeeting: Int = 80,
    var maxDocumentBytes: Int = 20 * 1024 * 1024,
    var maxTotalBytes: Long = 100L * 1024 * 1024,
    var maxExtractedCharacters: Int = 2_000_000,
    var maxPdfPages: Int = 500,
) {
    @PostConstruct
    fun validate() {
        require(maxDocumentsPerMeeting in 1..250) { "Document count limit is unsafe." }
        require(maxDocumentBytes in 1024..50 * 1024 * 1024) { "Document size limit is unsafe." }
        require(maxTotalBytes in maxDocumentBytes.toLong()..500L * 1024 * 1024) { "Total download limit is unsafe." }
        require(maxExtractedCharacters in 1_000..5_000_000) { "Extraction limit is unsafe." }
        require(maxPdfPages in 1..1_000) { "PDF page limit is unsafe." }
    }
}
