package com.pcmdk369.superioa.skills

/** Optional capabilities from List.md. Intentionally NOT wired to any vendor: they report "not configured" instead of faking results. */
interface WebSearchProvider {
    suspend fun search(query: String): List<String>
}

interface OcrProvider {
    suspend fun readText(imageBytes: ByteArray): String
}

object NotConfiguredWebSearch : WebSearchProvider {
    override suspend fun search(query: String): List<String> =
        throw UnsupportedOperationException("Web search is not configured")
}

object NotConfiguredOcr : OcrProvider {
    override suspend fun readText(imageBytes: ByteArray): String =
        throw UnsupportedOperationException("OCR is not configured")
}
