package com.mallowlink.app.data.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// EmbeddingManager
//
// Runs a small on-device embedding model (e.g. all-MiniLM-L6-v2 quantised to
// INT8 via ONNX Runtime).  The model file is bundled in assets/models/.
//
// Input  : tokenized text → int64 input_ids  [1, seqLen]
// Output : float32 embedding  [1, hiddenDim]
// ─────────────────────────────────────────────────────────────────────────────

private const val EMBEDDING_MODEL_ASSET = "models/embedding_model.onnx"
private const val TOKENIZER_VOCAB_ASSET = "models/vocab.txt"
private const val MAX_SEQ_LEN = 128
const val EMBEDDING_DIM = 384   // all-MiniLM-L6-v2

@Singleton
class EmbeddingManager @Inject constructor(
    private val context: Context,
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var tokenizer: SimpleWordPieceTokenizer? = null

    private var isReady = false

    /** Must be called before embed(). Loads model + tokenizer from assets. */
    suspend fun init() = withContext(Dispatchers.IO) {
        if (isReady) return@withContext
        try {
            val modelBytes = context.assets.open(EMBEDDING_MODEL_ASSET).readBytes()
            val opts = OrtSession.SessionOptions().apply {
                // Allow NNAPI delegate on supported devices
                addNnapi()
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(modelBytes, opts)

            val vocabLines = context.assets.open(TOKENIZER_VOCAB_ASSET)
                .bufferedReader()
                .readLines()
            tokenizer = SimpleWordPieceTokenizer(vocabLines)

            isReady = true
            Timber.i("EmbeddingManager ready (dim=$EMBEDDING_DIM)")
        } catch (e: Exception) {
            Timber.e(e, "EmbeddingManager init failed")
        }
    }

    /** Returns a normalised L2 float embedding for [text], or null on failure. */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        val sess = session ?: run { Timber.w("Session not ready"); return@withContext null }
        val tok = tokenizer ?: return@withContext null

        try {
            val tokens = tok.tokenize(text, MAX_SEQ_LEN)
            val seqLen = tokens.size.toLong()

            val inputIds = LongBuffer.allocate(tokens.size)
            val attentionMask = LongBuffer.allocate(tokens.size)
            tokens.forEach { id ->
                inputIds.put(id.toLong())
                attentionMask.put(1L)
            }
            inputIds.rewind(); attentionMask.rewind()

            val inputIdsTensor = OnnxTensor.createTensor(
                env, inputIds, longArrayOf(1, seqLen)
            )
            val maskTensor = OnnxTensor.createTensor(
                env, attentionMask, longArrayOf(1, seqLen)
            )

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to maskTensor,
            )

            val output = sess.run(inputs)
            // Mean-pool the last hidden state [1, seqLen, hiddenDim] → [hiddenDim]
            val tensor = output[0].value as Array<Array<FloatArray>>
            val hidden = tensor[0]  // [seqLen, hiddenDim]
            val pooled = FloatArray(EMBEDDING_DIM)
            for (token in hidden) {
                for (d in token.indices) pooled[d] += token[d]
            }
            val n = hidden.size.toFloat()
            for (d in pooled.indices) pooled[d] /= n

            // L2 normalise
            val norm = Math.sqrt(pooled.map { it.toDouble() * it }.sum()).toFloat()
                .coerceAtLeast(1e-9f)
            FloatArray(EMBEDDING_DIM) { pooled[it] / norm }
        } catch (e: Exception) {
            Timber.e(e, "embed() failed")
            null
        }
    }

    fun isReady() = isReady

    fun close() {
        session?.close()
        session = null
        isReady = false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Minimal WordPiece tokenizer (no external dep)
// A full BPE/SentencePiece tokenizer should be used in production;
// this is a lightweight stand-in that covers common English vocabulary.
// ─────────────────────────────────────────────────────────────────────────────

class SimpleWordPieceTokenizer(vocabLines: List<String>) {

    private val vocab: Map<String, Int> = buildMap {
        vocabLines.forEachIndexed { idx, token -> put(token.trim(), idx) }
    }

    private val clsId = vocab["[CLS]"] ?: 101
    private val sepId = vocab["[SEP]"] ?: 102
    private val unkId = vocab["[UNK]"] ?: 100
    private val padId = vocab["[PAD]"] ?: 0

    fun tokenize(text: String, maxLen: Int): IntArray {
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s.,!?'-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        val ids = mutableListOf(clsId)
        for (word in words) {
            if (ids.size >= maxLen - 1) break
            ids += wordPieceEncode(word)
        }
        ids.add(sepId)
        // Pad to maxLen
        while (ids.size < maxLen) ids.add(padId)
        return ids.take(maxLen).toIntArray()
    }

    private fun wordPieceEncode(word: String): List<Int> {
        if (vocab.containsKey(word)) return listOf(vocab[word]!!)
        val pieces = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var found = false
            for (end in word.length downTo start + 1) {
                val piece = if (start == 0) word.substring(start, end)
                else "##" + word.substring(start, end)
                val id = vocab[piece]
                if (id != null) {
                    pieces.add(id)
                    start = end
                    found = true
                    break
                }
            }
            if (!found) { pieces.add(unkId); break }
        }
        return pieces
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Float array ↔ ByteArray serialisation helpers
// ─────────────────────────────────────────────────────────────────────────────

fun FloatArray.toByteArray(): ByteArray {
    val buf = java.nio.ByteBuffer.allocate(size * 4)
    buf.asFloatBuffer().put(this)
    return buf.array()
}

fun ByteArray.toFloatArray(): FloatArray {
    val floatBuf = java.nio.ByteBuffer.wrap(this).asFloatBuffer()
    return FloatArray(floatBuf.remaining()) { floatBuf.get() }
}
