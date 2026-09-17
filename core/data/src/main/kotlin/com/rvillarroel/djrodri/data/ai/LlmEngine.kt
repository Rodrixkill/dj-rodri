package com.rvillarroel.djrodri.data.ai

import kotlinx.coroutines.flow.StateFlow

sealed interface LlmStatus {
    /** No model file on the phone yet. */
    data object NotInstalled : LlmStatus
    data object Loading : LlmStatus
    data class Ready(val modelName: String, val backend: String, val loadMs: Long) : LlmStatus
    data class Failed(val reason: String) : LlmStatus
}

/**
 * A local language model that answers with JSON and nothing else.
 *
 * It is deliberately narrow: the DJ only ever asks it to turn a sentence of yours into numbers. It never receives
 * song titles, artists or anything else that came from Spotify.
 */
interface LlmEngine {
    val status: StateFlow<LlmStatus>

    /** @return the model's JSON, or null if there is no model, it timed out, or it failed. */
    suspend fun json(systemInstruction: String, userMessage: String, jsonSchema: String, timeoutMs: Long): String?

    /** Frees the memory the model holds; it loads again on the next request. */
    fun unload()
}

/** Used when no model is installed: everything falls back to the rules interpreter. */
class NoLlmEngine(override val status: StateFlow<LlmStatus>) : LlmEngine {
    override suspend fun json(systemInstruction: String, userMessage: String, jsonSchema: String, timeoutMs: Long): String? = null
    override fun unload() = Unit
}
