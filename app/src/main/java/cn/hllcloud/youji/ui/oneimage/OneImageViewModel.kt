package cn.hllcloud.youji.ui.oneimage

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import cn.hllcloud.youji.YouJiApplication
import cn.hllcloud.youji.data.TravelRepository
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.data.entity.TravelNoteEntity
import cn.hllcloud.youji.util.DateFormatUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 一图流生成ViewModel
 */
class OneImageViewModel(
    application: Application,
    private val repository: TravelRepository,
    private val noteId: Long
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(OneImageUiState())
    val uiState: StateFlow<OneImageUiState> = _uiState.asStateFlow()

    val note = repository.getTravelNoteById(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val photos = repository.getPhotosByNoteId(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 生成一图流图片
     */
    fun generateImage(onGenerated: (Uri) -> Unit) {
        val currentNote = note.value
        val currentPhotos = photos.value
        if (currentNote == null) return

        _uiState.value = _uiState.value.copy(isGenerating = true, error = null)

        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    generateOneImageBitmap(currentNote, currentPhotos)
                }
                val savedFile = withContext(Dispatchers.IO) {
                    saveBitmapToFile(bitmap)
                }
                val uri = FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.fileprovider",
                    savedFile
                )
                _uiState.value = _uiState.value.copy(isGenerating = false, generatedUri = uri)
                onGenerated(uri)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    error = e.message ?: "生成失败"
                )
            }
        }
    }

    private suspend fun generateOneImageBitmap(
        note: TravelNoteEntity,
        photos: List<PhotoEntity>
    ): Bitmap {
        // 画布尺寸 (宽: 1080px, 高度自适应)
        val canvasWidth = 1080
        val padding = 60
        val contentWidth = canvasWidth - padding * 2

        var currentY = 0

        // ---- 加载封面图 ----
        val coverPhoto = photos.firstOrNull()
        val coverBitmap: Bitmap? = coverPhoto?.let { loadBitmap(it.filePath) }

        // 封面图高度 (按比例缩放)
        val coverHeight = if (coverBitmap != null) {
            (contentWidth * (coverBitmap.height.toFloat() / coverBitmap.width)).toInt()
        } else {
            500
        }

        // ---- 计算文本区域 ----
        val titlePaint = TextPaint().apply {
            color = Color.parseColor("#201A18")
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val metaPaint = TextPaint().apply {
            color = Color.parseColor("#85736E")
            textSize = 32f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val contentPaint = TextPaint().apply {
            color = Color.parseColor("#53433F")
            textSize = 36f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val footerPaint = TextPaint().apply {
            color = Color.parseColor("#FF6B35")
            textSize = 28f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        // 标题
        val titleLayout = createStaticLayout(note.title, titlePaint, contentWidth)
        val titleHeight = titleLayout.height

        // 元信息（日期范围 + 位置）
        val dateRange = DateFormatUtil.formatDateRange(note.startDate, note.endDate)
        val metaText = buildString {
            if (dateRange.isNotBlank()) append(dateRange)
            if (!note.locationSummary.isNullOrBlank()) {
                if (isNotBlank()) append("  ·  ")
                append(note.locationSummary)
            }
        }
        val metaHeight = if (metaText.isNotBlank()) 60 else 0

        // 正文
        val contentText = note.content.replace("\r\n", "\n")
        val contentLayout = createStaticLayout(contentText, contentPaint, contentWidth)
        val contentHeight = contentLayout.height

        // 照片网格 (除封面外的照片)
        val otherPhotos = photos.drop(1).take(9) // 最多9张
        val gridHeight = if (otherPhotos.isNotEmpty()) {
            val cols = 3
            val rows = (otherPhotos.size + cols - 1) / cols
            val gap = 10
            val cellWidth = (contentWidth - gap * (cols - 1)) / cols
            val cellHeight = cellWidth
            rows * cellHeight + (rows - 1) * gap + 40 // top margin
        } else 0

        // 底部
        val footerHeight = 100
        val sectionGap = 40

        // 计算总高度
        val totalHeight = padding +
                coverHeight + sectionGap +
                titleHeight + sectionGap +
                metaHeight + sectionGap +
                contentHeight + sectionGap +
                gridHeight +
                footerHeight + padding

        // 创建画布
        val bitmap = Bitmap.createBitmap(canvasWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 背景
        canvas.drawColor(Color.parseColor("#FFFBFF"))

        currentY = padding

        // ---- 绘制封面 ----
        if (coverBitmap != null) {
            val scaledCover = Bitmap.createScaledBitmap(coverBitmap, contentWidth, coverHeight, true)
            canvas.drawBitmap(scaledCover, padding.toFloat(), currentY.toFloat(), null)
            currentY += coverHeight + sectionGap
        } else {
            // 纯色占位
            val placeholderPaint = Paint().apply { color = Color.parseColor("#FFDBCB") }
            canvas.drawRect(
                padding.toFloat(), currentY.toFloat(),
                (padding + contentWidth).toFloat(), (currentY + coverHeight).toFloat(),
                placeholderPaint
            )
            currentY += coverHeight + sectionGap
        }

        // ---- 绘制标题 ----
        canvas.save()
        canvas.translate(padding.toFloat(), currentY.toFloat())
        titleLayout.draw(canvas)
        canvas.restore()
        currentY += titleHeight + sectionGap

        // ---- 绘制元信息 ----
        if (metaText.isNotBlank()) {
            canvas.drawText(metaText, padding.toFloat(), (currentY + 40).toFloat(), metaPaint)
            currentY += metaHeight + sectionGap
        }

        // ---- 绘制正文 ----
        canvas.save()
        canvas.translate(padding.toFloat(), currentY.toFloat())
        contentLayout.draw(canvas)
        canvas.restore()
        currentY += contentHeight + sectionGap

        // ---- 绘制照片网格 ----
        if (otherPhotos.isNotEmpty()) {
            currentY += 20
            val cols = 3
            val gap = 10
            val cellWidth = (contentWidth - gap * (cols - 1)) / cols
            val cellHeight = cellWidth

            otherPhotos.forEachIndexed { index, photo ->
                val row = index / cols
                val col = index % cols
                val left = padding + col * (cellWidth + gap)
                val top = currentY + row * (cellHeight + gap)

                val imgBitmap = loadBitmap(photo.filePath)
                if (imgBitmap != null) {
                    val scaled = centerCrop(imgBitmap, cellWidth, cellHeight)
                    canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), null)
                }
            }

            val rows = (otherPhotos.size + cols - 1) / cols
            currentY += rows * cellHeight + (rows - 1) * gap
        }

        // ---- 绘制底部Logo/水印 ----
        val footerY = totalHeight - padding - 40
        canvas.drawText("— 游记 YouJi —", padding.toFloat(), footerY.toFloat(), footerPaint)

        return bitmap
    }

    private suspend fun loadBitmap(filePath: String): Bitmap? {
        val loader = ImageLoader(getApplication())
        val request = ImageRequest.Builder(getApplication())
            .data(File(filePath))
            .allowHardware(false)
            .build()
        return (loader.execute(request) as? SuccessResult)?.drawable?.let { drawable ->
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }
    }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale = Math.max(
            width.toFloat() / source.width,
            height.toFloat() / source.height
        )
        val scaledWidth = (source.width * scale).toInt()
        val scaledHeight = (source.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val x = (scaledWidth - width) / 2
        val y = (scaledHeight - height) / 2
        return Bitmap.createBitmap(scaled, x, y, width, height)
    }

    @Suppress("DEPRECATION")
    private fun createStaticLayout(
        text: String,
        paint: TextPaint,
        width: Int
    ): Layout {
        val lineSpacingExtra = 16f
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(lineSpacingExtra, 1f)
                .setIncludePad(true)
                .build()
        } else {
            StaticLayout(
                text, paint, width,
                Layout.Alignment.ALIGN_NORMAL,
                1f, lineSpacingExtra, true
            )
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap): File {
        val timeStamp = DateFormatUtil.formatFileName(System.currentTimeMillis())
        val storageDir = File(getApplication<Application>().filesDir, "photos").apply { mkdirs() }
        val file = File(storageDir, "one_image_${timeStamp}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    data class OneImageUiState(
        val isGenerating: Boolean = false,
        val generatedUri: Uri? = null,
        val error: String? = null
    )

    class Factory(
        private val application: Application,
        private val noteId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as YouJiApplication
            return OneImageViewModel(application, app.repository, noteId) as T
        }
    }
}
