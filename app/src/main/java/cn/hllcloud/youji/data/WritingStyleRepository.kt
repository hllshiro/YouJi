package cn.hllcloud.youji.data

import cn.hllcloud.youji.data.dao.WritingStyleDao
import cn.hllcloud.youji.data.entity.WritingStyleEntity
import kotlinx.coroutines.flow.Flow

/**
 * 写作风格 Repository。
 *
 * 封装 [WritingStyleDao]，对上层提供风格列表查询、按 id 查询、增删改接口。
 * 对应设计文档 V3 第 3.1 节 writing_styles 表 + 第 6 节风格系统。
 *
 * 内置"纪实""美化"两种风格由 [cn.hllcloud.youji.data.AppDatabase] 的
 * onCreate 回调注入，本 Repository 不再重复创建，仅读取。
 */
class WritingStyleRepository(
    private val writingStyleDao: WritingStyleDao
) {

    /**
     * 全量监听风格列表，按"内置优先 + 创建时间升序"返回。
     * UI 用于风格选择行和风格管理页。
     */
    fun getAll(): Flow<List<WritingStyleEntity>> = writingStyleDao.getAll()

    /**
     * 仅内置风格（纪实/美化）。
     */
    fun getBuiltin(): Flow<List<WritingStyleEntity>> = writingStyleDao.getBuiltin()

    /**
     * 按 id 查询，主要用于工作流引擎启动时取出选中的风格记录。
     */
    suspend fun getById(id: Long): WritingStyleEntity? = writingStyleDao.getById(id)

    /**
     * 创建自定义风格。内置风格不允许通过此方法插入（isBuiltin 固定为 0）。
     */
    suspend fun create(style: WritingStyleEntity): Long {
        val safe = style.copy(isBuiltin = 0)
        return writingStyleDao.insert(safe)
    }

    /**
     * 更新风格。内置风格的 promptGuideline 允许编辑，但 isBuiltin 字段保留原值。
     */
    suspend fun update(style: WritingStyleEntity) {
        writingStyleDao.update(style)
    }

    /**
     * 删除风格。内置风格不允许删除，调用方应自行拦截。
     */
    suspend fun delete(style: WritingStyleEntity) {
        if (style.isBuiltin == 1) {
            // 防御：内置风格不可删
            return
        }
        writingStyleDao.delete(style)
    }
}
