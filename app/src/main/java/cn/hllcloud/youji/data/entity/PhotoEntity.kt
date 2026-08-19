package cn.hllcloud.youji.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 照片实体
 * 存储每张照片的元信息和本地路径
 */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val travelNoteId: Long? = null, // 关联的游记ID
    val filePath: String, // 本地文件路径
    val thumbnailPath: String? = null, // 缩略图路径
    val fileName: String,
    val takenAt: Long? = null, // 拍摄时间戳
    val latitude: Double? = null, // 纬度
    val longitude: Double? = null, // 经度
    val locationName: String? = null, // 位置名称（地理编码后）
    val exifMake: String? = null, // 相机品牌
    val exifModel: String? = null, // 相机型号
    val description: String? = null, // 照片描述
    val createdAt: Long = System.currentTimeMillis(),
    val workflowTaskId: Long? = null, // 关联的工作流任务ID（Prepare阶段写入，Save阶段清空）
)
