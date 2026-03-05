package com.mallowlink.app.data.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// LlmEngine
//
// Runs a small quantised generative model on-device via ONNX Runtime.
// The bundled model should be a ≤4B parameter model quantised to INT4/INT8,
// e.g. Phi-2 (2.7B), Qwen2-1.5B, or Gemma-2B exported to ONNX.
//
// Model expected I/O (GenAI ONNX format):
//   input_ids        : int64  [1, seqLen]
//   attention_mask   : int64  [1, seqLen]
//   → logits         : float32 [1, seqLen, vocabSize]
//
// In production, replace this with ONNX Runtime GenAI (Microsoft's streaming
// inference wrapper) which handles KV caching, beam search, and token streaming
// natively.  This implementation is a simplified greedy-decode demonstration.
// ─────────────────────────────────────────────────────────────────────────────

private const val LLM_MODEL_ASSET = "models/llm_model.onnx"
private const val LLM_VOCAB_ASSET = "models/llm_vocab.txt"
private const val MAX_NEW_TOKENS = 512
private const val MAX_CONTEXT_TOKENS = 2048

@Singleton
class LlmEngine @Inject constructor(
    private val context: Context,
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var vocab: List<String> = emptyList()
    private var tokenToId: Map<String, Int> = emptyMap()
    private var isReady = false

    suspend fun init() = withContext(Dispatchers.IO) {
        if (isReady) return@withContext
        try {
            val modelBytes = context.assets.open(LLM_MODEL_ASSET).readBytes()
            val opts = OrtSession.SessionOptions().apply {
                addNnapi()
                setIntraOpNumThreads(4)
                setInterOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(modelBytes, opts)
            vocab = context.assets.open(LLM_VOCAB_ASSET).bufferedReader().readLines()
            tokenToId = vocab.withIndex().associate { (i, v) -> v to i }
            isReady = true
            Timber.i("LlmEngine ready (vocabSize=${vocab.size})")
        } catch (e: Exception) {
            Timber.e(e, "LlmEngine init failed – model may not be bundled yet")
        }
    }

    fun isReady() = isReady

    /**
     * Generates a streaming answer.
     * Emits tokens one by one as they are decoded, then emits [DONE_TOKEN] when finished.
     */
    fun generate(prompt: String): Flow<String> = flow {
        val sess = session ?: run {
            emit("[Model not loaded. Please download a model in Settings.]")
            return@flow
        }

        try {
            val promptIds = encodePrompt(prompt).takeLast(MAX_CONTEXT_TOKENS).toMutableList()
            val sb = StringBuilder()
            var stepCount = 0

            while (stepCount < MAX_NEW_TOKENS) {
                val seqLen = promptIds.size.toLong()
                val inputIdsBuf = java.nio.LongBuffer.allocate(promptIds.size)
                val maskBuf = java.nio.LongBuffer.allocate(promptIds.size)
                promptIds.forEach { inputIdsBuf.put(it.toLong()); maskBuf.put(1L) }
                inputIdsBuf.rewind(); maskBuf.rewind()

                val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuf, longArrayOf(1, seqLen))
                val maskTensor = OnnxTensor.createTensor(env, maskBuf, longArrayOf(1, seqLen))

                val output = sess.run(mapOf("input_ids" to inputIdsTensor, "attention_mask" to maskTensor))
                val logits = output[0].value as Array<Array<FloatArray>>

                // Greedy decode: pick highest-logit token at last position
                val lastLogits = logits[0][promptIds.size - 1]
                val nextTokenId = lastLogits.indices.maxByOrNull { lastLogits[it] } ?: break

                val token = vocab.getOrNull(nextTokenId) ?: break
                if (token == EOS_TOKEN) break

                // Strip the WordPiece '##' prefix when detokenising
                val text = if (token.startsWith("##")) token.substring(2) else " $token"
                sb.append(text)
                emit(text)

                promptIds.add(nextTokenId)
                stepCount++
            }
        } catch (e: Exception) {
            Timber.e(e, "generate() failed")
            emit("\n[Generation error: ${e.message}]")
        }
    }.flowOn(Dispatchers.IO)

    private fun encodePrompt(prompt: String): List<Int> {
        // Very simple whitespace tokenisation; swap for a real BPE tokeniser.
        return prompt.split(Regex("\\s+")).map { w ->
            tokenToId[w.lowercase()] ?: tokenToId["[UNK]"] ?: 0
        }
    }

    fun close() {
        session?.close()
        session = null
        isReady = false
    }

    companion object {
        const val DONE_TOKEN = "[DONE]"
        private const val EOS_TOKEN = "[EOS]"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Prompt builder
// ─────────────────────────────────────────────────────────────────────────────

object PromptBuilder {

    /**
     * Builds an instruction-tuned prompt for RAG.
     *
     * @param query     The user's natural-language question.
     * @param context   Retrieved passages, each prefixed with [N] citation label.
     * @param history   Previous turns in the conversation (oldest first).
     */
    fun buildRagPrompt(
        query: String,
        context: String,
        history: List<Pair<String, String>> = emptyList(),
    ): String = buildString {
        appendLine(SYSTEM_PROMPT)
        appendLine()
        if (context.isNotBlank()) {
            appendLine("## Retrieved Sources")
            appendLine(context)
            appendLine()
        }
        if (history.isNotEmpty()) {
            appendLine("## Conversation History")
            for ((userMsg, assistantMsg) in history.takeLast(4)) {
                appendLine("User: $userMsg")
                appendLine("Assistant: $assistantMsg")
            }
            appendLine()
        }
        appendLine("## User Question")
        appendLine(query)
        appendLine()
        append("## Answer")
    }

    private const val SYSTEM_PROMPT = """You are MallowLink, a private, on-device AI assistant.
Answer ONLY using information from the retrieved sources above.
Cite sources with inline [N] labels that correspond to the sources list.
If the answer is not in the sources, say "I don't have that information in your documents."
Keep answers concise, accurate, and helpful."""
}
