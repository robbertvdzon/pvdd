package nl.vdzon.pvdd.policy

import org.springframework.stereotype.Component

object PolicyThemeClassifier {
    private val rules = linkedMapOf(
        PolicyTheme.ANIMALS_AND_NATURE to setOf("dier", "dieren", "natuur", "ecosysteem", "leefgebied"),
        PolicyTheme.BIODIVERSITY to setOf("biodiversiteit", "soorten", "natuurverbinding", "ecologisch"),
        PolicyTheme.CLIMATE_AND_RESOURCES to setOf("klimaat", "energie", "grondstoffen", "planeet", "uitstoot"),
        PolicyTheme.HEALTHY_ENVIRONMENT to setOf("gezondheid", "luchtkwaliteit", "bodem", "waterkwaliteit", "vervuiling"),
        PolicyTheme.ECOLOGY_OVER_SHORT_TERM_ECONOMY to setOf("economie", "economisch", "groei", "ecologie", "grootvervuiler"),
        PolicyTheme.CIRCULAR_BUILDING to setOf("circulair", "natuurinclusief", "energiepositief", "bouwmaterialen"),
        PolicyTheme.HOUSING_AND_AFFORDABILITY to setOf("woning", "woningen", "wonen", "betaalbaar", "binnenstedelijk", "bebouwing"),
        PolicyTheme.WALKING_CYCLING_PUBLIC_TRANSPORT to setOf("voetganger", "fiets", "fietser", "openbaar vervoer", "trein", "bus"),
        PolicyTheme.ROADS_AND_AVIATION to setOf("weg", "wegen", "snelweg", "luchtvaart", "schiphol", "vliegen"),
        PolicyTheme.TRANSPARENCY_PRIVACY_PARTICIPATION to setOf("transparant", "transparantie", "privacy", "inwoners", "participatie"),
        PolicyTheme.FAIR_DISTRIBUTION_AND_FUTURE_GENERATIONS to setOf("kwetsbaar", "rechtvaardig", "bestaanszekerheid", "toekomstige generaties"),
    )

    fun classify(text: String): Set<PolicyTheme> {
        val normalized = text.lowercase()
        return rules.filterValues { terms -> terms.any(normalized::contains) }.keys
    }
}

data class PolicySelection(
    val sourceSha256: String,
    val chunks: List<PolicyChunk>,
)

class MissingPolicySourceException : RuntimeException("PRIMARY_POLICY_SOURCE_MISSING")

@Component
class PolicySelector(private val repository: PolicyStore) {
    fun select(context: String, maxChunks: Int = 12): PolicySelection {
        require(maxChunks in 2..20)
        val hash = repository.latestSource() ?: throw MissingPolicySourceException()
        val chunks = repository.findByHash(hash)
        if (chunks.isEmpty() || chunks.any { it.sourceSha256 != hash || it.sourceUrl.toString().isBlank() }) {
            throw MissingPolicySourceException()
        }
        val queryThemes = PolicyThemeClassifier.classify(context)
        val queryTerms = terms(context)
        val core = chunks.filter { isCore(it.text) }.ifEmpty { listOf(chunks.first()) }.take(2)
        val relevant = chunks
            .asSequence()
            .filterNot { it in core }
            .map { chunk -> chunk to score(chunk, queryThemes, queryTerms) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<PolicyChunk, Int>> { it.second }.thenBy { it.first.pageNumber }.thenBy { it.first.sequence })
            .map { it.first }
            .take(maxChunks - core.size)
            .toList()
        return PolicySelection(hash, (core + relevant).distinctBy { it.id }.take(maxChunks))
    }

    private fun score(chunk: PolicyChunk, themes: Set<PolicyTheme>, queryTerms: Set<String>): Int {
        val themeScore = chunk.themes.intersect(themes).size * 20
        val chunkTerms = terms(chunk.text)
        return themeScore + chunkTerms.intersect(queryTerms).size
    }

    private fun isCore(text: String): Boolean {
        val normalized = text.lowercase()
        return CORE_PHRASES.any(normalized::contains)
    }

    private fun terms(text: String): Set<String> = WORD.findAll(text.lowercase())
        .map { it.value }
        .filter { it.length >= 4 && it !in STOP_WORDS }
        .toSet()

    companion object {
        private val CORE_PHRASES = setOf(
            "draagkracht van de planeet",
            "dieren, natuur, milieu",
            "mens en dier in harmonie",
            "toekomstige generaties",
        )
        private val WORD = Regex("[a-zà-ÿ0-9]+")
        private val STOP_WORDS = setOf("deze", "voor", "naar", "wordt", "door", "over", "meer", "maar", "zijn", "niet", "heeft")
    }
}
