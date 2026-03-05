package com.mallowlink.app.data.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer
import timber.log.Timber
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ─────────────────────────────────────────────────────────────────────────────
// Result type
// ─────────────────────────────────────────────────────────────────────────────

data class ParsedPage(
    val pageIndex: Int,
    val text: String,
    val bitmap: Bitmap? = null,
)

data class ParseResult(
    val pages: List<ParsedPage>,
    val metadata: Map<String, String> = emptyMap(),
)

// ─────────────────────────────────────────────────────────────────────────────
// Main dispatcher
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class DocumentParser @Inject constructor(
    private val context: Context,
) {
    private val ocrRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val markdownParser = Parser.builder().build()
    private val markdownRenderer = TextContentRenderer.builder().build()

    suspend fun parse(uri: Uri, mimeType: String): ParseResult = withContext(Dispatchers.IO) {
        Timber.d("Parsing $uri (mime=$mimeType)")
        try {
            when {
                mimeType == "application/pdf"                              -> parsePdf(uri)
                mimeType.startsWith("image/")                             -> parseImage(uri)
                mimeType == "text/plain"                                  -> parsePlainText(uri)
                mimeType == "text/markdown" || uri.lastPathSegment
                    ?.endsWith(".md") == true                             -> parseMarkdown(uri)
                mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                                                          -> parseDocx(uri)
                else                                                      -> parsePlainText(uri)
            }
        } catch (e: Exception) {
            Timber.e(e, "Parse failed for $uri")
            ParseResult(pages = emptyList())
        }
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    private suspend fun parsePdf(uri: Uri): ParseResult = withContext(Dispatchers.IO) {
        val pages = mutableListOf<ParsedPage>()
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val scale = minOf(2.0f, 2048f / maxOf(page.width, page.height))
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).toInt(),
                            (page.height * scale).toInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = recognizeText(bmp)
                        pages.add(ParsedPage(i, text, bmp))
                    }
                }
            }
        }
        ParseResult(pages = pages)
    }

    // ── Image / Screenshot ───────────────────────────────────────────────────

    private suspend fun parseImage(uri: Uri): ParseResult = withContext(Dispatchers.IO) {
        val image = InputImage.fromFilePath(context, uri)
        val text = recognizeTextFromInputImage(image)
        ParseResult(pages = listOf(ParsedPage(0, text)))
    }

    // ── Plain Text ───────────────────────────────────────────────────────────

    private suspend fun parsePlainText(uri: Uri): ParseResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readText() } ?: ""
        // Split by double newline as "pages"
        val pages = text.split("\n\n\n").mapIndexed { i, chunk ->
            ParsedPage(i, chunk.trim())
        }.filter { it.text.isNotBlank() }
        ParseResult(pages = if (pages.isEmpty()) listOf(ParsedPage(0, text)) else pages)
    }

    // ── Markdown ─────────────────────────────────────────────────────────────

    private suspend fun parseMarkdown(uri: Uri): ParseResult = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readText() } ?: ""
        val doc = markdownParser.parse(raw)
        val plainText = markdownRenderer.render(doc)
        ParseResult(pages = listOf(ParsedPage(0, plainText)))
    }

    // ── DOCX ─────────────────────────────────────────────────────────────────

    private suspend fun parseDocx(uri: Uri): ParseResult = withContext(Dispatchers.IO) {
        val pages = mutableListOf<ParsedPage>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            try {
                val text = extractDocxText(stream)
                // Split into ~2000-char chunks as logical "pages"
                text.chunked(2000).forEachIndexed { i, chunk ->
                    pages.add(ParsedPage(i, chunk))
                }
            } catch (e: Exception) {
                Timber.e(e, "DOCX parse failed")
            }
        }
        ParseResult(pages = pages)
    }

    /**
     * Minimal DOCX text extraction using Apache POI.
     * We load POI lazily to avoid its footprint on startup.
     */
    private fun extractDocxText(stream: InputStream): String {
        // Use reflection to avoid hard compile-time dep issues on Android
        return try {
            val xwpfClass = Class.forName("org.apache.poi.xwpf.usermodel.XWPFDocument")
            val doc = xwpfClass.getConstructor(InputStream::class.java).newInstance(stream)
            val sb = StringBuilder()
            @Suppress("UNCHECKED_CAST")
            val paragraphs = xwpfClass.getMethod("getParagraphs").invoke(doc) as List<Any>
            for (para in paragraphs) {
                val text = para.javaClass.getMethod("getText").invoke(para) as String
                if (text.isNotBlank()) sb.appendLine(text)
            }
            sb.toString()
        } catch (e: ClassNotFoundException) {
            Timber.w("Apache POI not available; falling back to raw bytes")
            stream.readText()
        }
    }

    // ── OCR helpers ──────────────────────────────────────────────────────────

    private suspend fun recognizeText(bitmap: Bitmap): String =
        recognizeTextFromInputImage(InputImage.fromBitmap(bitmap, 0))

    private suspend fun recognizeTextFromInputImage(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            ocrRecognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { e ->
                    Timber.w(e, "OCR failed")
                    cont.resume("")
                }
        }
}

/** Splits a large string into overlapping chunks suitable for embedding. */
fun String.toChunks(
    maxTokens: Int = 300,        // approximate tokens (1 token ≈ 4 chars)
    overlapTokens: Int = 50,
): List<String> {
    val chunkSize = maxTokens * 4
    val overlapSize = overlapTokens * 4
    if (length <= chunkSize) return listOf(this)

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < length) {
        val end = minOf(start + chunkSize, length)
        chunks.add(substring(start, end))
        start += chunkSize - overlapSize
        if (start >= length) break
    }
    return chunks
}

private fun InputStream.readText(): String = bufferedReader().use { it.readText() }
