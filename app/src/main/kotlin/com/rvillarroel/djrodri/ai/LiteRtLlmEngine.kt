package com.rvillarroel.djrodri.ai

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.rvillarroel.djrodri.data.ai.LlmEngine
import com.rvillarroel.djrodri.data.ai.LlmStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The local model, through LiteRT-LM.
 *
 * The model file is not shipped with the app: you copy it to the phone yourself ([modelsDir]). With no model the
 * status is [LlmStatus.NotInstalled] and everything keeps working on the rules interpreter.
 *
 * It loads on first use and can be unloaded to give the memory back, because a party matters more than an idle model.
 */
class LiteRtLlmEngine(private val context: Context) : LlmEngine {
    private val _status = MutableStateFlow<LlmStatus>(LlmStatus.NotInstalled)
    override val status: StateFlow<LlmStatus> = _status

    private val lock = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var loadedSystemInstruction: String? = null

    /** Where to copy the model: `adb push modelo.litertlm <this folder>`. */
    val modelsDir: File = File(context.getExternalFilesDir(null), "models")

    fun modelFile(): File? = modelsDir.takeIf { it.isDirectory }?.listFiles()
        ?.firstOrNull { it.isFile && it.extension in MODEL_EXTENSIONS }

    /** Called when the settings screen opens, so the status reflects a model copied a minute ago. */
    fun refreshStatus() {
        if (_status.value is LlmStatus.Ready || _status.value is LlmStatus.Loading) return
        _status.value = if (modelFile() == null) LlmStatus.NotInstalled else LlmStatus.Failed("Modelo sin cargar todavía")
    }

    override suspend fun json(systemInstruction: String, userMessage: String, jsonSchema: String, timeoutMs: Long): String? =
        withContext(Dispatchers.IO) {
            val conversation = lock.withLock { conversationFor(systemInstruction) } ?: return@withContext null
            // sendMessage blocks its thread; the timeout stops us waiting and cancelProcess stops the model.
            val answer = withTimeoutOrNull(timeoutMs) {
                CoroutineScope(Dispatchers.IO).async {
                    runCatching { conversation.sendMessage(userMessage, responseFormat = ResponseFormat.json(jsonSchema)) }.getOrNull()
                }.await()
            }
            if (answer == null) runCatching { conversation.cancelProcess() }
            answer?.contents?.contents.orEmpty()
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
                .takeIf { it.isNotBlank() }
        }

    private fun conversationFor(systemInstruction: String): Conversation? {
        conversation?.let { if (it.isAlive && loadedSystemInstruction == systemInstruction) return it }
        val model = modelFile() ?: run {
            _status.value = LlmStatus.NotInstalled
            return null
        }
        _status.value = LlmStatus.Loading
        val startedAt = SystemClock.elapsedRealtime()
        // Fastest first: the NPU build may not exist for this chip yet, and the GPU is a fine second.
        // The NPU backend needs to find Qualcomm's native libraries, which ship inside the APK.
        val backends = listOf(Backend.NPU(context.applicationInfo.nativeLibraryDir), Backend.GPU(), Backend.CPU())
        for (backend in backends) {
            val attempt = runCatching {
                closeEngine()
                val created = Engine(
                    EngineConfig(
                        modelPath = model.absolutePath,
                        backend = backend,
                        cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath,
                    ),
                )
                created.initialize()
                engine = created
                created.createConversation(
                    ConversationConfig(systemInstruction = Contents.of(systemInstruction), enableResponseFormat = true),
                )
            }.getOrNull()
            if (attempt != null) {
                conversation = attempt
                loadedSystemInstruction = systemInstruction
                _status.value = LlmStatus.Ready(model.name, backend.name, SystemClock.elapsedRealtime() - startedAt)
                return attempt
            }
        }
        closeEngine()
        _status.value = LlmStatus.Failed("El modelo ${model.name} no cargó en NPU, GPU ni CPU")
        return null
    }

    override fun unload() {
        closeEngine()
        refreshStatus()
    }

    private fun closeEngine() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        loadedSystemInstruction = null
    }

    private companion object {
        val MODEL_EXTENSIONS = setOf("litertlm", "task", "tflite")
    }
}
