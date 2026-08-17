package cn.hllcloud.youji.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 图片处理工具
 *
 * 用于 VLM Vision 请求前的图片压缩和 base64 编码。
 *
 * 参考 OpenAI Vision API 规范：
 * - 图片长边缩放到 1024px（保持宽高比），超过 2048px 会被服务端自动降采样
 * - 转为 JPEG 格式（质量 80）减小传输体积
 * - base64 编码后构建 data URL：data:image/jpeg;base64,{data}
 * - detail:"low" 模式下固定消耗 85 tokens，适合游记场景
 *
 * 客户端预压缩可将单图 token 从 ~1500 降至 ~85（detail:low）或 ~425（1024px high），
 * 同时大幅减少网络传输量。
 */
object ImageUtil {

    /** VLM 图片最大长边（像素），参考 OpenAI high detail 2048px 上限取一半以平衡质量和成本 */
    private const val MAX_IMAGE_SIZE = 1024

    /** JPEG 压缩质量（0-100） */
    private const val JPEG_QUALITY = 80

    /**
     * 读取图片文件，缩放并压缩为 JPEG，返回 base64 编码字符串。
     *
     * 处理流程：
     * 1. 用 inJustDecodeBounds 读取原始尺寸（不分配内存）
     * 2. 计算 inSampleSize 进行粗缩放（2 的幂次方，快速降采样）
     * 3. 解码为 Bitmap
     * 4. 精确缩放到 MAX_IMAGE_SIZE（保持宽高比）
     * 5. 压缩为 JPEG 并 base64 编码
     *
     * @param filePath 图片文件路径
     * @return base64 编码的 JPEG 数据（不含 data URL 前缀），或 null（读取失败）
     */
    fun compressAndBase64Encode(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null

        // 1. 读取图片尺寸
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, boundsOptions)
        val srcW = boundsOptions.outWidth
        val srcH = boundsOptions.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        // 2. 计算 inSampleSize（2 的幂次方粗缩放，目标：长边不超过 MAX_IMAGE_SIZE * 2）
        var sampleSize = 1
        val maxDim = maxOf(srcW, srcH)
        while (maxDim / sampleSize > MAX_IMAGE_SIZE * 2) {
            sampleSize *= 2
        }

        // 3. 解码为 Bitmap（采样缩放）
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return null

        // 4. 精确缩放到 MAX_IMAGE_SIZE（保持宽高比）
        val scaledBitmap = if (maxOf(bitmap.width, bitmap.height) > MAX_IMAGE_SIZE) {
            val scale = MAX_IMAGE_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                if (it != bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }

        // 5. 压缩为 JPEG 并 base64 编码
        val outputStream = ByteArrayOutputStream()
        try {
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        } finally {
            scaledBitmap.recycle()
        }

        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 构建 data URL 格式的图片引用。
     * @return "data:image/jpeg;base64,{base64_data}"
     */
    fun buildDataUrl(base64Data: String): String {
        return "data:image/jpeg;base64,$base64Data"
    }
}
