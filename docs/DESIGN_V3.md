# YouJi V3 设计文档：页面精简、自动工作流、任务持久化

## 1. 背景与目标

### 1.1 前版遗留问题

V2 设计了"选照片 → 自动地理反查 → 本地生成 → VLM 润色 → 保存"的链路，但 UI 上仍保留了标题/日期/正文输入框和生成按钮，导致实际流程是**半自动**的——用户仍需手动触发每一步，体验割裂。

### 1.2 核心需求

| # | 需求 | 说明 |
|---|------|------|
| R1 | **页面精简** | 创建游记页仅保留：照片选择区 + 风格选择区 + 保存按钮。移除标题、日期、正文输入框和生成按钮 |
| R2 | **自动工作流** | 点击保存后，APP 自动执行完整工作流（地理反查 → 本地生成 → VLM 生成 → 入库），全程无需用户干预 |
| R3 | **进度管理** | 工作流各阶段实时显示进度，用户可随时查看当前执行到哪一步 |
| R4 | **任务持久化** | 工作流中间结果写入本地数据库，APP 被关闭后下次启动时能恢复未完成任务 |
| R5 | **风格选择** | 保留 V2 设计的纪实/美化两种内置风格 + 自定义风格管理 |

### 1.3 设计原则

- **Save = Start**：保存按钮即启动按钮，点击后自动完成所有环节
- **不丢数据**：任何阶段崩溃/退出，已完成的中间结果可恢复
- **用户可中断**：运行中的任务可取消，已完成的阶段不回滚
- **渐进增强**：无 VLM 配置时自动降级为纯本地生成，不阻塞主流程

---

## 2. 页面重构

### 2.1 创建游记页（Before → After）

**Before（V2 现状）：**
```
[标题输入框]
[日期范围选择]
[照片选择区（3列网格）]
[智能生成] [VLM生成]  ← 用户手动触发
[生成进度]
[正文输入框]  ← 300px 可编辑
[保存]
```

**After（V3）：**
```
[照片选择区（3列网格）]
[风格选择行：纪实(选中) 美化 +管理]
[保存并生成]  ← 唯一主按钮
```

### 2.2 工作流进度页（新增）

点击保存后进入全屏进度页，实时展示：

```
╔══════════════════════════════════╗
║  正在生成游记...                  ║
║                                  ║
║  ✅ 读取照片信息          已完成  ║
║  ✅ 解析地理位置 (3/5)    已完成  ║
║  ✅ 整理行程内容          已完成  ║
║  ⏳ AI 生成游记...        进行中  ║
║  ⬜ 保存游记              待执行  ║
║                                  ║
║  ┌────────────────────────────┐  ║
║  │ ████████████████░░░░  65%  │  ║
║  └────────────────────────────┘  ║
║                                  ║
║  [取消生成]                      ║
╚══════════════════════════════════╝
```

### 2.3 任务恢复对话框（新增）

APP 启动时检测到未完成任务时弹出：

```
╔══════════════════════════════════╗
║  ⚠️ 发现未完成的任务              ║
║                                  ║
║  您上次创建游记时退出了应用，      ║
║  任务尚未完成。是否继续？          ║
║                                  ║
║  任务：5张照片 · 纪实风格          ║
║  进度：地理反查已完成，VLM生成未完成║
║                                  ║
║  [放弃任务]  [继续生成]            ║
╚══════════════════════════════════╝
```

---

## 3. 工作流引擎设计

### 3.1 工作流阶段定义

```
阶段 1: 准备（Prepare）
  - 扫描照片 EXIF，提取 lat/long/takenAt
  - 写入 PhotoEntity 内存列表
  ↓
阶段 2: 地理反查（Geocode）
  - 批量 reverseGeocode，写入 PhotoEntity.locationName
  - 进度: 已反查/总数
  ↓
阶段 3: 本地生成（LocalGenerate）
  - LocalContentGenerator.generateContent(photos, style)
  - 输出: title, content, locationSummary, dateRange
  ↓
阶段 4: VLM 生成（VlmGenerate）
  - 构建结构化上下文 prompt
  - 调 VLM API 获取润色后正文
  - 若 VLM 未配置或失败，降级用本地生成结果
  ↓
阶段 5: 保存（Save）
  - TravelNoteEntity + PhotoEntity 入库
  - 标记任务完成
```

### 3.2 阶段状态机

```
  IDLE ──→ RUNNING ──→ COMPLETED
               │    ↓
               │  FAILED ──→ RETRY ──→ RUNNING
               │    ↓
               └──→ PAUSED（用户取消/APP被杀）
                        ↓
                     RESUMED（恢复时）
```

每个阶段独立状态：`PENDING / RUNNING / COMPLETED / FAILED`。整体任务状态为各阶段的聚合。

### 3.3 进度计算

```
总进度 = Σ(阶段权重 × 阶段进度)

阶段权重：
  Prepare:    0.05
  Geocode:    0.25  (反查进度 = 已完成反查数 / 总照片数)
  LocalGen:   0.10
  VlmGen:     0.45  (VLM API 无法细分，按 0 或 1)
  Save:       0.15
```

### 3.4 取消与重试

- **取消**：调用 `cancel()` 后，当前阶段完成后停止（不强制中断网络请求）。已完成阶段的中间结果保留。
- **重试**：失败阶段可单独重试，无需从头开始。
- **降级**：VLM 阶段失败自动降级为本地生成结果，标记 `isGeneratedByVlm=false`，不阻塞后续 Save 阶段。

---

## 4. 任务持久化

### 4.1 数据模型

#### WorkflowTask（新表）

```sql
CREATE TABLE workflow_tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  status TEXT NOT NULL,           -- 'pending' | 'running' | 'paused' | 'completed' | 'failed'
  current_phase TEXT NOT NULL,    -- 'prepare' | 'geocode' | 'local_gen' | 'vlm_gen' | 'save'
  progress REAL NOT NULL DEFAULT 0.0,  -- 0.0 ~ 1.0
  selected_style_id INTEGER,      -- 选中的风格 ID
  selected_style_name TEXT,       -- 冗余存储风格名
  input_photo_paths TEXT NOT NULL, -- JSON: ["path1", "path2", ...]
  created_note_id INTEGER,        -- 若已保存则关联游记 ID
  error_message TEXT,             -- 失败原因
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
)
```

#### WorkflowPhaseResult（新表，存储每阶段的中间结果）

```sql
CREATE TABLE workflow_phase_results (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER NOT NULL,
  phase TEXT NOT NULL,            -- 'prepare' | 'geocode' | 'local_gen' | 'vlm_gen' | 'save'
  status TEXT NOT NULL,           -- 'pending' | 'running' | 'completed' | 'failed'
  result_json TEXT,               -- 阶段产物 JSON（见下方说明）
  error_message TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(task_id, phase)
)
```

#### 各阶段 result_json 结构

| 阶段 | result_json 示例 | 说明 |
|------|-----------------|------|
| prepare | `{"photo_count": 5, "has_gps": 3}` | 照片统计 |
| geocode | `{"geocoded": 5, "failed": 0}` | 反查结果统计，PhotoEntity 的 locationName 已单独写入 |
| local_gen | `{"title": "郑州3日游", "content": "...", "locationSummary": "郑州", "startDate": 1755273600000, "endDate": 1755446400000}` | 本地生成产物 |
| vlm_gen | `{"content": "...", "success": true}` | VLM 生成产物 |
| save | `{"note_id": 42}` | 保存结果 |

#### TravelNoteEntity 扩展

```kotlin
data class TravelNoteEntity(
    // ... 原有字段 ...
    val writingStyleId: Long? = null,
    val writingStyleName: String? = null,
    val workflowTaskId: Long? = null,  // 关联的工作流任务 ID
)
```

### 4.2 任务恢复流程

```
APP 启动
  ↓
检查 workflow_tasks 表中 status ∈ ('pending', 'running', 'paused') 的记录
  ↓
找到未完成任务？
  ├── 否 → 正常进入主页
  └── 是 → 弹出恢复对话框
            ├── 用户选"放弃" → 标记任务为 'failed'，清理中间数据
            └── 用户选"继续" → 从 current_phase 恢复执行
                  ↓
              读取该阶段的 phase_result
                  ↓
              从断点继续执行（如 VLM 阶段未完成，则重新调 VLM）
```

### 4.3 中间数据缓存策略

- **PhotoEntity**：反查完成后立即入库（`locationName` 字段），不依赖内存
- **生成结果**：`local_gen` 和 `vlm_gen` 阶段的产物存入 `workflow_phase_results.result_json`
- **缓存清理**：任务完成后保留 7 天，自动清理；任务被放弃时立即清理
- **存储空间**：仅存储文本结果（几 KB），图片文件仍按原方式管理

### 4.4 防重复执行

每个阶段开始前检查 `workflow_phase_results` 表：

```kotlin
suspend fun executePhase(taskId: Long, phase: Phase): PhaseResult {
    val existing = phaseResultDao.getByTaskAndPhase(taskId, phase.name)
    if (existing?.status == "completed") {
        return PhaseResult.Skip(existing.resultJson)  // 已完成，跳过
    }
    return doExecute(phase)
}
```

---

## 5. 工作流引擎实现

### 5.1 WorkflowEngine 接口

```kotlin
class WorkflowEngine(
    private val taskDao: WorkflowTaskDao,
    private val phaseResultDao: WorkflowPhaseResultDao,
    private val geocoderService: GeocoderService,
    private val localGenerator: LocalContentGenerator,
    private val vlmClient: VlmClient,
    private val repository: TravelRepository
) {
    suspend fun start(
        photoPaths: List<String>,
        style: WritingStyle,
        onProgress: (TaskProgress) -> Unit
    ): Long  // 返回 taskId

    suspend fun resume(taskId: Long, onProgress: (TaskProgress) -> Unit)

    suspend fun cancel(taskId: Long)

    suspend fun abandon(taskId: Long)
}

data class TaskProgress(
    val taskId: Long,
    val currentPhase: Phase,
    val phaseStatuses: Map<Phase, PhaseStatus>,
    val overallProgress: Float,
    val message: String?
)

enum class Phase { PREPARE, GEOCODE, LOCAL_GEN, VLM_GEN, SAVE }
enum class PhaseStatus { PENDING, RUNNING, COMPLETED, FAILED }
```

### 5.2 执行逻辑（伪代码）

```kotlin
suspend fun start(photoPaths, style, onProgress): Long {
    val taskId = createTask(photoPaths, style)
    val phases = orderedPhases()

    for (phase in phases) {
        if (isCancelled(taskId)) break

        updatePhaseStatus(taskId, phase, RUNNING)
        onProgress(buildProgress(taskId))

        try {
            val result = when (phase) {
                PREPARE -> runPrepare(photoPaths)
                GEOCODE -> runGeocode(taskId)
                LOCAL_GEN -> runLocalGen(taskId, style)
                VLM_GEN -> runVlmGen(taskId, style)
                SAVE -> runSave(taskId)
            }
            savePhaseResult(taskId, phase, result)
            updatePhaseStatus(taskId, phase, COMPLETED)
        } catch (e: Exception) {
            // VLM 阶段失败可降级
            if (phase == VLM_GEN) {
                savePhaseResult(taskId, phase, localFallbackResult)
                updatePhaseStatus(taskId, phase, COMPLETED)
            } else {
                updatePhaseStatus(taskId, phase, FAILED)
                updateTaskStatus(taskId, "failed")
                throw e
            }
        }
        onProgress(buildProgress(taskId))
    }

    updateTaskStatus(taskId, "completed")
    return taskId
}
```

### 5.3 Geocode 阶段并行优化

反查是 IO 密集操作，采用并发 + 节流：

```kotlin
private suspend fun runGeocode(taskId: Long) {
    val photos = photoDao.getByTask(taskId)
    val scope = CoroutineScope(Dispatchers.IO)

    photos.map { photo ->
        scope.async {
            val result = geocoderService.reverse(photo.latitude!!, photo.longitude!!)
            result.onSuccess { address ->
                photoDao.updateLocationName(photo.id, address.formatted)
            }
        }
    }.awaitAll()

    // 更新已反查的 PhotoEntity 到内存状态
    val completed = photoDao.getByTask(taskId)
    emitProgress(geocodedCount = completed.count { it.locationName != null })
}
```

**并发度**：3 个协程并行（兼顾速度与 Geocoder 限流）
**Nominatim 特判**：若回退到 Nominatim，强制串行（1.1s 间隔）

### 5.4 VLM 降级逻辑

```kotlin
private suspend fun runVlmGen(taskId: Long, style: WritingStyle) {
    val vlmSettings = vlmSettingsRepository.settings.first()
    val localResult = phaseResultDao.getByTaskAndPhase(taskId, "local_gen")
        ?.resultJson?.let { parseGeneratedContent(it) }

    if (!vlmSettings.enabled) {
        // VLM 未配置，直接用本地生成结果
        savePhaseResult(taskId, VLM_GEN, localResult.toJson())
        return
    }

    val photos = photoDao.getByTask(taskId)
    val vlmResult = vlmClient.generateTravelContent(vlmSettings, photos, style)

    vlmResult.onSuccess { content ->
        val merged = localResult.copy(content = content)
        savePhaseResult(taskId, VLM_GEN, merged.toJson())
    }.onFailure {
        // 降级：保留本地生成内容
        savePhaseResult(taskId, VLM_GEN, localResult.toJson())
        markTaskNote("VLM生成失败，使用本地生成结果")
    }
}
```

---

## 6. UI 变更详情

### 6.1 创建游记页（精简后）

```
Column {
    // 照片选择区
    Card {
        Row { [拍照] [图库] }
        if (photos.isNotEmpty()) {
            photos.chunked(3).forEach { row -> Row { ... } }
        }
    }

    // 风格选择区
    Card {
        Text("选择风格")
        FlowRow {
            builtinStyles.forEach { style -> FilterChip(style.name) }
            AssistChip("+ 管理")
        }
    }

    // 保存按钮
    Button("保存并生成")  ← 替代原有的"智能生成"+"VLM生成"+"保存"
}
```

**移除的组件**：
- 标题输入框（`OutlinedTextField` for title）
- 日期范围选择卡
- 正文输入框（`OutlinedTextField` for content）
- 智能生成 / VLM 生成按钮
- 生成进度条（移到独立进度页）
- VLM 提示 Chip

**保留的组件**：
- 照片选择区（拍照 + 图库 + 3列网格）
- 风格选择行
- 保存按钮（改为"保存并生成"）

### 6.2 工作流进度页（新页面）

- 全屏对话框（不可关闭），顶部显示阶段列表
- 中间进度条
- 底部取消按钮（RUNNING 状态可点，COMPLETED 后变为"完成"）
- 任务完成后自动关闭，跳转到游记详情页

### 6.3 任务恢复对话框

- 启动时检测未完成任务
- 显示任务摘要（照片数、风格、进度）
- 两个按钮："放弃任务" / "继续生成"

---

## 7. 数据模型变更汇总

### 7.1 新增表

**workflow_tasks**：
```sql
CREATE TABLE workflow_tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  status TEXT NOT NULL DEFAULT 'pending',
  current_phase TEXT NOT NULL DEFAULT 'prepare',
  progress REAL NOT NULL DEFAULT 0.0,
  selected_style_id INTEGER,
  selected_style_name TEXT,
  input_photo_paths TEXT NOT NULL,
  created_note_id INTEGER,
  error_message TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
)
```

**workflow_phase_results**：
```sql
CREATE TABLE workflow_phase_results (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER NOT NULL,
  phase TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  result_json TEXT,
  error_message TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(task_id, phase)
)
```

**writing_styles**（V2 设计，保留）：
```sql
CREATE TABLE writing_styles (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  prompt_guideline TEXT NOT NULL,
  opening_tone TEXT,
  closing_tone TEXT,
  is_builtin INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
)
```

### 7.2 扩展表

**TravelNoteEntity** 新增字段：
```kotlin
val writingStyleId: Long? = null,
val writingStyleName: String? = null,
val workflowTaskId: Long? = null,
```

**PhotoEntity** 使用已有字段：
```kotlin
// latitude / longitude / locationName 已存在，V3 真正写入
val latitude: Double? = null,
val longitude: Double? = null,
val locationName: String? = null,
```

### 7.3 SharedPreferences

**GeocodingSettings**（V2 设计，保留）：
```kotlin
data class GeocodingSettings(
    val amapKey: String = ""
)
```

---

## 8. 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer                           │
│  CreateTravelScreen │ WorkflowProgressScreen │ RecoveryDialog │
└──────────┬──────────────────┬───────────────────────────┘
           │ save()            │ start()/resume()
           ▼                  ▼
┌─────────────────────────────────────────────────────────┐
│                 ViewModel Layer                         │
│  CreateTravelViewModel    WorkflowViewModel              │
└──────────┬──────────────────┬───────────────────────────┘
           │                  │
           ▼                  ▼
┌─────────────────────────────────────────────────────────┐
│              Workflow Engine (新)                       │
│                                                         │
│  ┌────────┐  ┌────────┐  ┌────────┐  ┌──────┐  ┌─────┐ │
│  │Prepare │→│Geocode │→│LocalGen│→│VlmGen│→│Save │ │
│  └────────┘  └────────┘  └────────┘  └──────┘  └─────┘ │
│       │          │          │          │          │      │
│       ▼          ▼          ▼          ▼          ▼      │
│  ┌─────────────────────────────────────────────────┐    │
│  │         PhaseResult 持久化（Room）               │    │
│  └─────────────────────────────────────────────────┘    │
└──────────┬──────────────────┬───────────────────────────┘
           │                  │
           ▼                  ▼
┌─────────────────────────────────────────────────────────┐
│                  Data Layer                             │
│  TravelRepository │ WorkflowTaskDao │ PhaseResultDao    │
│  GeocoderService │ VlmClient │ LocalContentGenerator    │
└─────────────────────────────────────────────────────────┘
```

---

## 9. 实现计划

### 阶段 A：数据层 + 工作流引擎（核心）

1. Room 数据库升级：新增 `workflow_tasks`、`workflow_phase_results`、`writing_styles` 三张表
2. `TravelNoteEntity` 迁移：新增 `writingStyleId`、`writingStyleName`、`workflowTaskId` 三列
3. DAO：`WorkflowTaskDao`、`WorkflowPhaseResultDao`、`WritingStyleDao`
4. `GeocoderService` 实现：`SystemGeocoderService` + `NominatimGeocoderService` + `CompositeGeocoderService`
5. `WorkflowEngine` 核心逻辑：5 个阶段的执行、持久化、恢复、取消

### 阶段 B：页面精简

1. `CreateTravelScreen`：移除标题/日期/正文/生成按钮，新增风格选择行
2. `CreateTravelViewModel`：简化 `save()` 为 `startWorkflow()`，不再直接保存
3. `SaveAndGenerate` 按钮替代原 Save 按钮

### 阶段 C：进度页 + 恢复机制

1. `WorkflowProgressScreen`：全屏进度对话框
2. `RecoveryDialog`：启动时检测未完成任务并询问
3. `Application.onCreate()`：恢复逻辑入口
4. `WorkflowViewModel`：管理进度状态和 UI 交互

### 阶段 D：风格系统

1. `WritingStyle` 实体 + DAO + Repository
2. 内置风格注入（纪实/美化）
3. 风格管理页（新增/编辑/删除）
4. `LocalContentGenerator` + `VlmClient` 接入 style 参数

### 阶段 E：收尾

1. `AmapGeocoderService` 可选实现
2. 设置页加"地图服务"区块
3. 清理废弃代码（旧的 generateLocalContent / generateVlmContent 入口）
4. 端到端测试

---

## 10. 风险与开放问题

### 10.1 APP 被杀后的恢复边界

| 场景 | 恢复行为 |
|------|---------|
| Prepare 阶段被杀 | 从 Prepare 重新开始（代价低） |
| Geocode 阶段被杀 | 检查已完成的反查结果，跳过已完成的，继续剩余的 |
| LocalGen 阶段被杀 | 重新执行（纯本地，秒级） |
| VlmGen 阶段被杀 | 重新调 VLM API（可能消耗 token） |
| Save 阶段被杀 | 检查游记是否已入库，已入库则跳过 |

### 10.2 内存与线程

- WorkflowEngine 使用单线程协程调度（`Executors.newSingleThreadExecutor().asCoroutineDispatcher()`），避免并发写入冲突
- Geocode 阶段使用独立的 IO 调度器，并发度限制为 3
- 整个工作流在 ViewModel 的 `viewModelScope` 中启动，配置变更（如屏幕旋转）不会中断

### 10.3 用户体验

- "保存并生成"点击后立即进入进度页，用户无法返回（避免误操作）
- 生成完成后自动跳转到游记详情页，展示最终结果
- 生成失败时在进度页显示错误原因，允许用户重试或放弃
- 放弃任务后回到创建游记页，已选照片保留

### 10.4 存储空间

- 工作流任务表和阶段结果表仅存储文本数据，单任务 < 5KB
- 定期清理：已完成 > 7 天、已放弃 > 3 天的任务自动清理
- 图片文件仍按原方式管理（应用内部存储），不随任务表清理

### 10.5 Geocoder 兼容性（延续 V2 分析）

- 国内大部分手机可用，但不稳定
- 回退链路：Geocoder(系统) → Nominatim(免 key) → 仅坐标
- 已知可用：小米 MIUI、华为 EMUI、OPPO ColorOS、vivo OriginOS

### 10.6 VLM Token 成本

- N 张照片 × detail:"low" ≈ N × 85 tokens
- 结构化上下文 prompt ≈ 500-1000 tokens
- 单次 20 张照片 ≈ 3000-4000 tokens 输入，可控

---

## 11. 参考资料

- Room 持久化：https://developer.android.com/training/data-storage/room
- Android WorkManager（备选，如需后台任务）：https://developer.android.com/topic/libraries/architecture/workmanager
- Android Geocoder：`android.location.Geocoder`
- Nominatim API：https://nominatim.org/reverse-api/
- 高德 Web API：https://restapi.amap.com/v3/geocode/regeo
- Kotlin 协程：https://kotlinlang.org/docs/coroutines-overview.html
