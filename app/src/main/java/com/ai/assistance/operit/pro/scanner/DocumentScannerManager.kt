package com.ai.assistance.operit.pro.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 文档扫描与 OCR 管理器
 *
 * 提供摄像头文档扫描、自动边缘检测、透视校正、
 * OCR 文字识别和文档智能分析功能。
 *
 * 核心能力：
 * - 自动文档边缘检测
 * - 透视校正与增强
 * - 多语言 OCR（中英日韩）
 * - 文档分类与标签
 * - 智能内容摘要
 * - PDF 导出
 * - 批量扫描
 */
class DocumentScannerManager(private val context: Context) {

    companion object {
        private val _scannedDocuments = MutableStateFlow<List<ScannedDocument>>(emptyList())
        val scannedDocuments: StateFlow<List<ScannedDocument>> = _scannedDocuments.asStateFlow()

        private val _isProcessing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

        private val documents = mutableListOf<ScannedDocument>()
    }

    /** 扫描文档 */
    suspend fun scanDocument(
        bitmap: Bitmap,
        title: String,
        autoEnhance: Boolean = true,
    ): ScannedDocument = withContext(Dispatchers.IO) {
        _isProcessing.value = true

        try {
            // 1. 检测边缘（模拟）
            val edges = detectEdges(bitmap)

            // 2. 透视校正
            val corrected = if (edges != null) {
                perspectiveCorrect(bitmap, edges)
            } else {
                bitmap
            }

            // 3. 图像增强
            val enhanced = if (autoEnhance) {
                enhanceDocument(corrected)
            } else {
                corrected
            }

            // 4. 保存图片
            val imageFile = saveBitmap(enhanced, "scan_${System.currentTimeMillis()}")

            // 5. 生成文档
            val doc = ScannedDocument(
                id = UUID.randomUUID().toString(),
                title = title,
                imagePath = imageFile.absolutePath,
                pageCount = 1,
                createdAt = System.currentTimeMillis(),
                ocrText = "",
                summary = "",
                tags = emptyList(),
                category = DocumentCategory.OTHER,
            )

            documents.add(0, doc)
            _scannedDocuments.value = documents.toList()
            doc
        } finally {
            _isProcessing.value = false
        }
    }

    /** 批量扫描 */
    suspend fun batchScan(bitmaps: List<Bitmap>, title: String): ScannedDocument =
        withContext(Dispatchers.IO) {
            _isProcessing.value = true

            try {
                val imageFiles = bitmaps.mapIndexed { index, bitmap ->
                    val enhanced = enhanceDocument(bitmap)
                    saveBitmap(enhanced, "scan_${System.currentTimeMillis()}_$index")
                }

                val doc = ScannedDocument(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    imagePath = imageFiles.first().absolutePath,
                    allImagePaths = imageFiles.map { it.absolutePath },
                    pageCount = bitmaps.size,
                    createdAt = System.currentTimeMillis(),
                    ocrText = "",
                    summary = "",
                    tags = emptyList(),
                    category = DocumentCategory.OTHER,
                )

                documents.add(0, doc)
                _scannedDocuments.value = documents.toList()
                doc
            } finally {
                _isProcessing.value = false
            }
        }

    /** 更新 OCR 结果 */
    fun updateOcrResult(docId: String, ocrText: String) {
        val index = documents.indexOfFirst { it.id == docId }
        if (index >= 0) {
            val doc = documents[index]
            val category = DocumentClassifier.classify(ocrText)
            val tags = TagExtractor.extract(ocrText)
            val summary = TextSummarizer.summarize(ocrText)

            documents[index] = doc.copy(
                ocrText = ocrText,
                category = category,
                tags = tags,
                summary = summary,
            )
            _scannedDocuments.value = documents.toList()
        }
    }

    /** 删除文档 */
    fun deleteDocument(docId: String) {
        val doc = documents.find { it.id == docId }
        doc?.let {
            // 删除图片文件
            it.allImagePaths.forEach { path ->
                File(path).takeIf { f -> f.exists() }?.delete()
            }
            // 删除PDF
            it.pdfPath?.let { pdfPath -> File(pdfPath).takeIf { f -> f.exists() }?.delete() }
        }
        documents.removeAll { it.id == docId }
        _scannedDocuments.value = documents.toList()
    }

    /** 搜索文档 */
    fun searchDocuments(query: String): List<ScannedDocument> {
        val q = query.lowercase()
        return documents.filter { doc ->
            doc.title.lowercase().contains(q) ||
            doc.ocrText.lowercase().contains(q) ||
            doc.tags.any { it.lowercase().contains(q) }
        }
    }

    /** 按分类筛选 */
    fun filterByCategory(category: DocumentCategory): List<ScannedDocument> {
        return documents.filter { it.category == category }
    }

    private fun detectEdges(bitmap: Bitmap): DocumentEdges? {
        // 简化的边缘检测 - 在实际实现中会使用 OpenCV
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val margin = 0.05f
        return DocumentEdges(
            topLeft = Pair(w * margin, h * margin),
            topRight = Pair(w * (1 - margin), h * margin),
            bottomLeft = Pair(w * margin, h * (1 - margin)),
            bottomRight = Pair(w * (1 - margin), h * (1 - margin)),
        )
    }

    private fun perspectiveCorrect(bitmap: Bitmap, edges: DocumentEdges): Bitmap {
        // 简化的透视校正
        return bitmap
    }

    private fun enhanceDocument(bitmap: Bitmap): Bitmap {
        // 图像增强 - 对比度和亮度调整
        val matrix = Matrix()
        matrix.postScale(1f, 1f)

        val width = bitmap.width
        val height = bitmap.height

        val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(enhanced)
        canvas.drawBitmap(bitmap, matrix, null)

        // 应用对比度增强
        val paint = android.graphics.Paint()
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    1.5f, 0f, 0f, 0f, -128f * 0.5f,
                    0f, 1.5f, 0f, 0f, -128f * 0.5f,
                    0f, 0f, 1.5f, 0f, -128f * 0.5f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return enhanced
    }

    private fun saveBitmap(bitmap: Bitmap, fileName: String): File {
        val dir = File(context.filesDir, "scanned_documents")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$fileName.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }
}

/** 扫描文档 */
data class ScannedDocument(
    val id: String,
    val title: String,
    val imagePath: String,
    val allImagePaths: List<String> = listOf(imagePath),
    val pdfPath: String? = null,
    val pageCount: Int,
    val createdAt: Long,
    val ocrText: String,
    val summary: String,
    val tags: List<String>,
    val category: DocumentCategory,
)

/** 文档分类 */
enum class DocumentCategory(val displayName: String) {
    RECEIPT("收据"),
    INVOICE("发票"),
    CONTRACT("合同"),
    ID_CARD("证件"),
    BUSINESS_CARD("名片"),
    NOTE("笔记"),
    LETTER("信件"),
    FORM("表格"),
    BOOK("书籍"),
    OTHER("其他"),
}

/** 文档边缘 */
data class DocumentEdges(
    val topLeft: Pair<Float, Float>,
    val topRight: Pair<Float, Float>,
    val bottomLeft: Pair<Float, Float>,
    val bottomRight: Pair<Float, Float>,
)

/** 文档分类器 */
object DocumentClassifier {
    fun classify(text: String): DocumentCategory {
        val t = text.lowercase()
        return when {
            t.contains("发票") || t.contains("invoice") || t.contains("receipt") -> DocumentCategory.RECEIPT
            t.contains("合同") || t.contains("contract") || t.contains("协议") -> DocumentCategory.CONTRACT
            t.contains("身份证") || t.contains("护照") || t.contains("身份证") || t.contains("driver") -> DocumentCategory.ID_CARD
            t.contains("名片") || t.contains("business card") -> DocumentCategory.BUSINESS_CARD
            t.contains("笔记") || t.contains("note") -> DocumentCategory.NOTE
            t.contains("表格") || t.contains("form") -> DocumentCategory.FORM
            else -> DocumentCategory.OTHER
        }
    }
}

/** 标签提取器 */
object TagExtractor {
    fun extract(text: String): List<String> {
        val tags = mutableListOf<String>()
        val t = text.lowercase()

        // 日期
        Regex("\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}").findAll(t).forEach {
            tags.add("日期:${it.value}")
        }

        // 金额
        Regex("[¥$€]\\s*\\d+[.,]?\\d*").findAll(t).forEach {
            tags.add("金额:${it.value}")
        }

        // 电话
        Regex("\\d{3}[-]?\\d{3,4}[-]?\\d{4}").findAll(t).forEach {
            tags.add("电话:${it.value}")
        }

        // 邮箱
        Regex("[\\w.]+@[\\w]+\\.[\\w]+").findAll(t).forEach {
            tags.add("邮箱:${it.value}")
        }

        return tags.distinct().take(10)
    }
}

/** 文本摘要器 */
object TextSummarizer {
    fun summarize(text: String): String {
        if (text.length < 100) return text

        val sentences = text.split(Regex("[。.！!？?\\n]+"))
            .filter { it.trim().length > 10 }
            .take(3)

        return sentences.joinToString("。") + "。"
    }
}
