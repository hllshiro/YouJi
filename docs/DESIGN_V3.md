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
- **强制配置**：VLM API 和地理编码服务为**必须项**，未配置完成不允许启动工作流；首次启动进入引导页强制完成两项配置后才可使用主功能

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
║  ✅ 读取照片信息            (1/5) ║
║  ✅ 解析地理位置            (2/5) ║
║  ✅ 整理行程内容            (3/5) ║
║  ⏳ AI 生成游记...          (4/5) ║
║  ⬜ 保存游记                (5/5) ║
║                                  ║
║  [暂停]                          ║
╚══════════════════════════════════╝
```

**进度展示规则**：
- 不使用百分比，不维护阶段权重
- 直接用 `(当前阶段序号 / 总阶段数)` 显示，如 `(3/5)` 表示正在执行第 3 阶段，共 5 个阶段
- 阶段前缀图标表示状态：✅ 已完成 / ⏳ 进行中 / ⬜ 待执行
- Geocode 阶段额外显示内部进度：`(2/5) 解析中 (3/8 张)`，左侧为阶段序号，右侧为反查进度

### 2.3 任务恢复机制（调整）

**不再启动时弹框打断用户**，改为"首页展示 + 用户主动恢复"：

#### 启动行为

APP 启动时执行 `markUnfinishedTasksAsPaused()`：
- 扫描 `workflow_tasks` 表中 `status ∈ ('pending', 'running')` 的任务
- 全部标记为 `paused`，并记录 `current_phase`（用于恢复时知道从哪个阶段继续）
- 静默完成，不弹任何对话框

#### 首页入口

主页顶部展示"待恢复任务"区块（仅当存在 paused 任务时显示）：

```
╔══════════════════════════════════╗
║  ⚠️ 3 个任务待恢复               ║
║  5张照片 · 纪实风格 (3/5 反查)   ║
║  8张照片 · 美化风格 (VLM 阶段)   ║
║  3张照片 · 自定义风格 (本地生成)  ║
║                                  ║
║  [恢复全部]  [管理]              ║
╚══════════════════════════════════╝
```

- 点击任务卡片 → 直接恢复该任务（任务状态改为 `running`，从 `current_phase` 续传）
- 点击"恢复全部" → 顺序恢复所有 paused 任务
- 点击"管理" → 进入任务管理列表，可单独恢复/暂停/放弃

#### 手动暂停（替代原"继续/忽略"对话框）

任务执行过程中支持用户主动暂停，不再使用"继续/忽略"二选一弹窗：

**触发方式**：进度页右下角「暂停」按钮 / 任务管理列表的「暂停」开关

**确认对话框**（暂停前强制弹出）：

```
╔══════════════════════════════════╗
║  确定暂停任务？                   ║
║                                  ║
║  暂停后任务会保留当前进度，        ║
║  您可以随时从主页恢复继续执行。    ║
║                                  ║
║  当前进度：地理反查 3/5           ║
║                                  ║
║  [取消]            [确定暂停]      ║
╚══════════════════════════════════╝
```

- **取消** → 关闭对话框，任务继续执行
- **确定暂停** → 任务状态改为 `paused`，等待当前阶段安全停止点后中断（不强制打断网络请求），返回主页

**恢复方式**：暂停后的任务与启动时标记为 paused 的任务同等对待，从主页"待恢复"区块点击恢复即可。

### 2.4 首次启动配置引导页（新增）

APP 首次启动或检测到 VLM / 地理编码未配置时强制进入：

```
╔══════════════════════════════════╗
║  欢迎使用 YouJi                  ║
║                                  ║
║  使用前需要完成以下配置：          ║
║                                  ║
║  1. VLM 大模型 API               ║
║     [未配置] →                    ║
║     baseUrl + apiKey + 模型选择   ║
║     [测试连接] (必须通过)          ║
║                                  ║
║  2. 地理编码服务                  ║
║     [未配置] →                    ║
║     高德 Key（可选）               ║
║     [测试] (必须通过)              ║
║                                  ║
║  [完成配置后开始使用]              ║
╚══════════════════════════════════╝
```

- 两项必须全部配置完成（VLM 测试通过 + 地理编码测试通过）才能进入主功能
- 「完成配置后开始使用」按钮在两项未通过前禁用
- 配置完成后写入 SharedPreferences 标记 `setup_completed=true`，后续启动跳过引导页
- 用户可在设置页随时修改这两项配置（修改后自动重置 `setup_completed` 要求重新测试）

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
  - VLM 调用失败时任务标记为 FAILED，由用户决定重试或放弃（不自动降级）
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
               └──→ PAUSED（用户取消/APP被杀/启动时统一标记）
                        ↓
                     RESUMED（用户主动恢复）
```

每个阶段独立状态：`PENDING / RUNNING / COMPLETED / FAILED`。整体任务状态为各阶段的聚合。

**任务状态取值**：`pending / running / paused / completed / failed`。`paused` 用于标识"被外部打断、等待用户决策"的中间态，与 `running` 区分以避免启动时被误判为"在执行"。

### 3.3 进度展示

**不使用百分比，不维护阶段权重**，直接用阶段序号表示进度：

```
当前阶段进度 = (current_phase_index + 1) / total_phases_count
显示格式: "(N/M)"，如 "(3/5)" 表示正在执行第 3 阶段，共 5 个阶段
```

各阶段在进度页的展示：
- PREPARE: `(1/5) 读取照片信息`
- GEOCODE: `(2/5) 解析地理位置 (已反查数/总照片数)` —— 内部还有反查粒度
- LOCAL_GEN: `(3/5) 整理行程内容`
- VLM_GEN: `(4/5) AI 生成游记`
- SAVE: `(5/5) 保存游记`

不计算聚合百分比，不维护跨阶段权重。Geocode 阶段的反查进度作为额外信息附加显示，不参与整体进度计算。

### 3.4 取消、暂停与重试

- **取消（放弃）**：调用 `abandon()` 后，任务标记为 `failed`，清理中间数据。与暂停不同，放弃后任务不可恢复。
- **暂停**：调用 `pause()` 后，当前阶段完成当前可中断点后停止（不强制中断网络请求）。已完成阶段的中间结果保留，任务状态改为 `paused`。暂停后可通过主页恢复继续执行。
- **重试**：失败阶段可单独重试，无需从头开始。
- **不降级**：VLM 阶段失败不自动降级到本地生成结果，任务标记为 FAILED 等待用户决策（重试或放弃）。

### 3.5 启动前置校验

`WorkflowEngine.start()` 调用前必须先通过配置校验：

```kotlin
fun canStartWorkflow(): WorkflowStartCheck {
    val vlmOk = vlmSettings.enabled &&
        vlmSettings.apiUrl.isNotBlank() &&
        vlmSettings.apiKey.isNotBlank() &&
        vlmSettings.modelName.isNotBlank()
    val geoOk = geocoderService.isAvailable()  // Geocoder.isPresent() 或 amapKey 非空
    return WorkflowStartCheck(
        canStart = vlmOk && geoOk,
        missingVlm = !vlmOk,
        missingGeo = !geoOk
    )
}
```

校验未通过时，UI 强制跳转到设置引导页，不允许用户进入创建游记流程。

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

**启动时不再弹框，改为主页展示 + 用户主动恢复**：

```
APP 启动
  ↓
执行 markUnfinishedTasksAsPaused():
  - 扫描 workflow_tasks 表中 status ∈ ('pending', 'running') 的记录
  - 全部更新为 'paused'（不弹任何 UI）
  - 保留 current_phase 字段不动
  ↓
进入主页
  ↓
检查是否存在 paused 任务
  ├── 否 → 不显示待恢复区块
  └── 是 → 主页顶部显示"待恢复任务"区块
            ↓
        用户操作:
          ├── 点击"恢复全部" → 顺序恢复所有 paused 任务（一个完成再启动下一个）
          ├── 点击单个任务卡片 → 直接恢复该任务（状态改 'running'，从 current_phase 续传）
          └── 点击"管理" → 进入任务管理页（可单独恢复/暂停/放弃）

任务执行中:
  ↓
用户点击进度页"暂停"按钮
  ↓
弹出确认对话框（显示当前进度）
  ├── 用户点"取消" → 关闭对话框，任务继续执行
  └── 用户点"确定暂停" → 任务状态改 'paused'，当前阶段到达安全停止点后中断，返回主页
```

**设计要点**：
- **不打断用户**：启动时静默标记，不弹框
- **主动权交给用户**：用户在主页主动选择是否恢复，避免"刚打开就弹框"的侵入感
- **恢复无确认**：恢复是"无副作用"操作（从断点继续），不需要二次确认；只有暂停是"打断执行"才需要确认
- **批量操作**：首页"恢复全部"一键恢复多个任务，顺序执行避免并发冲突
- **断点续传**：恢复时读取 `current_phase` 和 `workflow_phase_results`，已完成阶段跳过

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

### 5.4 VLM 执行逻辑（不降级）

```kotlin
private suspend fun runVlmGen(taskId: Long, style: WritingStyle) {
    val vlmSettings = vlmSettingsRepository.settings.first()
    // 启动前已通过 canStartWorkflow() 校验，此处 vlmSettings 必然可用
    require(vlmSettings.enabled) { "VLM 未配置，不应到达此阶段" }

    val localResult = phaseResultDao.getByTaskAndPhase(taskId, "local_gen")
        ?.resultJson?.let { parseGeneratedContent(it) }
        ?: throw IllegalStateException("本地生成阶段未完成")

    val photos = photoDao.getByTask(taskId)
    val vlmResult = vlmClient.generateTravelContent(vlmSettings, photos, style)

    vlmResult.onSuccess { content ->
        val merged = localResult.copy(content = content)
        savePhaseResult(taskId, VLM_GEN, merged.toJson())
    }.onFailure { e ->
        // 失败时标记任务为 FAILED，等待用户重试或放弃
        updatePhaseStatus(taskId, VLM_GEN, FAILED, e.message)
        updateTaskStatus(taskId, "failed", e.message)
        throw e
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
- 每个阶段显示 `(N/M)` 序号 + 阶段名 + 状态图标，不显示百分比进度条
- Geocode 阶段附加显示反查细粒度：`(2/5) 解析中 (3/8 张)`
- 底部按钮区：
  - RUNNING 状态：显示「暂停」按钮（点击触发 `PauseConfirmDialog`）
  - PAUSED 状态：显示「恢复」按钮（直接续传，无需二次确认）
  - COMPLETED 状态：显示「完成」按钮（跳转详情页）
  - FAILED 状态：显示「重试」/「放弃」按钮
- 任务完成后自动关闭，跳转到游记详情页

### 6.3 游记详情页（扩展）

详情页在原有展示功能基础上，根据关联的 `workflowTaskId` 状态显示对应操作区：

```
游记详情页
├── 内容展示区（标题/正文/照片/日期等，原有功能）
└── 工作流状态区（仅在 travelNote.workflowTaskId != null 时显示）
    ├── 状态徽标（运行中 / 已暂停 / 已完成 / 失败）
    ├── 阶段进度文本（"(3/5) 整理行程内容" 形式，无百分比）
    └── 操作按钮（按状态切换）
        ├── RUNNING：[暂停]
        ├── PAUSED：[恢复]  [放弃]
        ├── COMPLETED：（无按钮，仅显示"AI生成完成"徽标）
        └── FAILED：[重试]  [放弃]
```

**按钮行为**：
- **暂停** → 触发 `PauseConfirmDialog`，确认后任务改 `paused`
- **恢复** → 任务改 `running`，从 `current_phase` 续传，UI 切换到进度反馈模式
- **重试** → 失败阶段重新执行，其他已完成阶段跳过
- **放弃** → 任务标记 `failed`，清理中间数据，游记本体保留（若 Save 阶段已部分完成）

**设计要点**：
- 进度页和详情页共用同一套工作流状态 ViewModel，避免逻辑重复
- 详情页的"恢复"与首页"待恢复"区块的"恢复"完全一致，调用同一个 `WorkflowEngine.resume()`
- 任务 RUNNING 时详情页只读，不允许编辑正文（避免与 AI 生成结果冲突）
- 任务 PAUSED/COMPLETED 后允许用户编辑正文

### 6.4 任务恢复区块（首页）

详见 2.3 节，主页顶部展示 paused 任务列表，支持「恢复全部」、单个卡片直接恢复、「管理」入口。恢复按钮无二次确认（无副作用操作），暂停才需要二次确认。

---

## 7. 数据模型变更汇总

### 7.1 新增表

**workflow_tasks**：
```sql
CREATE TABLE workflow_tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  status TEXT NOT NULL DEFAULT 'pending',
  current_phase TEXT NOT NULL DEFAULT 'prepare',
  current_phase_index INTEGER NOT NULL DEFAULT 0,  -- 当前阶段序号（0-based），用于 (N/M) 展示
  total_phases INTEGER NOT NULL DEFAULT 5,          -- 总阶段数，默认 5
  geocode_done_count INTEGER NOT NULL DEFAULT 0,    -- Geocode 阶段已反查数（额外细粒度）
  geocode_total_count INTEGER NOT NULL DEFAULT 0,  -- Geocode 阶段总照片数
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
6. `canStartWorkflow()` 前置校验：VLM 已配置 + 地理编码可用，缺一项拒绝启动

### 阶段 B：页面精简

1. `CreateTravelScreen`：移除标题/日期/正文/生成按钮，新增风格选择行
2. `CreateTravelViewModel`：简化 `save()` 为 `startWorkflow()`，不再直接保存
3. `SaveAndGenerate` 按钮替代原 Save 按钮

### 阶段 C：进度页 + 恢复机制 + 首次配置引导

1. `WorkflowProgressScreen`：全屏进度对话框，含「暂停」按钮
2. `markUnfinishedTasksAsPaused()`：Application 启动时静默将 pending/running 任务标记为 paused
3. 首页"待恢复任务"区块：展示 paused 任务列表 + "恢复全部"按钮 + "管理"入口
4. `PauseConfirmDialog`：用户点击进度页暂停按钮时弹出，显示当前进度并要求二次确认
5. `WorkflowViewModel`：管理进度状态和 UI 交互（含暂停/恢复动作）
6. `SetupWizardScreen`：首次启动配置引导页，强制完成 VLM + 地理编码配置
7. 启动路由：`setup_completed=false` → 强制进入引导页；否则进入主页

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
| Prepare 阶段被杀 | 启动时标记为 paused；用户恢复时从 Prepare 重新开始（代价低） |
| Geocode 阶段被杀 | 启动时标记为 paused；用户恢复时检查已完成的反查结果，跳过已完成的，继续剩余的 |
| LocalGen 阶段被杀 | 启动时标记为 paused；用户恢复时重新执行（纯本地，秒级） |
| VlmGen 阶段被杀 | 启动时标记为 paused；用户恢复时重新调 VLM API（可能消耗 token） |
| Save 阶段被杀 | 启动时标记为 paused；用户恢复时检查游记是否已入库，已入库则跳过 |

**注意**：所有阶段被杀后都不会自动恢复，统一标记为 paused 等待用户在主页主动决策。

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
- **强制要求**：首次启动配置引导页要求地理编码测试必须通过，若 Geocoder 不可用，用户必须配置高德 Key 或确保有网络访问 Nominatim

### 10.6 VLM Token 成本

- N 张照片 × detail:"low" ≈ N × 85 tokens
- 结构化上下文 prompt ≈ 500-1000 tokens
- 单次 20 张照片 ≈ 3000-4000 tokens 输入，可控
- **强制要求**：VLM 测试不通过不允许进入主功能，首次配置时必须通过 API 连通性 + Vision 能力测试

---

## 11. 参考资料

- Room 持久化：https://developer.android.com/training/data-storage/room
- Android WorkManager（备选，如需后台任务）：https://developer.android.com/topic/libraries/architecture/workmanager
- Android Geocoder：`android.location.Geocoder`
- Nominatim API：https://nominatim.org/reverse-api/
- 高德 Web API：https://restapi.amap.com/v3/geocode/regeo
- Kotlin 协程：https://kotlinlang.org/docs/coroutines-overview.html
