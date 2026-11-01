package com.jinge.weknora.ui

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jinge.weknora.data.Agent
import com.jinge.weknora.data.ApiException
import com.jinge.weknora.data.Artifact
import com.jinge.weknora.data.Attachment
import com.jinge.weknora.data.ChatMessage
import com.jinge.weknora.data.ChatHistoryParser
import com.jinge.weknora.data.movePreambleToThinking
import com.jinge.weknora.data.applyAnswer
import com.jinge.weknora.data.ConnectionConfig
import com.jinge.weknora.data.FolderInfo
import com.jinge.weknora.data.KbStats
import com.jinge.weknora.data.Knowledge
import com.jinge.weknora.data.KnowledgeBase
import com.jinge.weknora.data.ModelInfo
import com.jinge.weknora.data.ReferenceParser
import com.jinge.weknora.data.Session
import com.jinge.weknora.data.SettingsStore
import com.jinge.weknora.data.StreamFrame
import com.jinge.weknora.data.WeKnoraApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 文档列表分页大小。服务端未强制上限，100 是「够大又不至于一次太重」的取值 */
        private const val DOC_PAGE_SIZE = 100
        /** 翻页安全阀：5000 条封顶，防止 total 异常时无限循环 */
        private const val MAX_DOC_PAGES = 50
        /**
         * 统计专用的页大小，比列表页大得多：统计要的是「全量 + 分类计数」，
         * 每多一页就多一次往返。实测 page_size=1000 服务端照样返回，
         * 361 篇一次就拿全。
         */
        private const val STATS_PAGE_SIZE = 500
        /** 统计时的并发上限：知识库可能有几十个，一次性全发会把 NAS 打满 */
        private const val STATS_CONCURRENCY = 4
        /**
         * 产物页最多扫描多少个会话。
         * 产物是按会话取的（见 [loadArtifacts]），会话列表按时间倒序，
         * 所以取最近的一批就够了 —— 太老的对话不太可能还有人去找里面的文件。
         */
        private const val ARTIFACT_SESSION_LIMIT = 100
        /** 按会话拉产物时的并发上限，同统计 */
        private const val ARTIFACT_CONCURRENCY = 6
    }

    private val store = SettingsStore(app)
    private var api: WeKnoraApi? = null
    private var streamJob: Job? = null

    /** 文档分块里的图片句柄 */
    private val RESOURCE_HANDLE = Regex("""resource://[A-Za-z0-9_-]{6,}""")

    /** 会话懒创建，但并发调用会各建一个（多选附件踩过：一次建出 N 个会话） */
    private val sessionLock = Mutex()

    /* ---------------- 连接 ---------------- */
    var configured by mutableStateOf(store.isConfigured)
        private set
    var baseUrlInput by mutableStateOf(store.baseUrl)
    var apiKeyInput by mutableStateOf(store.apiKey)
    /** 可选：仅平台级 API Key 需要，空间级 Key 留空 */
    var tenantIdInput by mutableStateOf(store.tenantId)

    /* ---------------- 账号登录 ---------------- */
    var emailInput by mutableStateOf(store.email)
    var passwordInput by mutableStateOf("")
    /** 已登录账号（设置页展示用） */
    val accountEmail: String get() = store.email

    /* ---------------- 更新 ---------------- */
    /** 更新源（update.json）地址，设置页可改，默认指向仓库 raw 地址 */
    var updateUrlInput by mutableStateOf(store.updateUrl)
        private set

    fun saveUpdateUrl(url: String) {
        updateUrlInput = url.trim().ifBlank { com.jinge.weknora.data.UpdateChecker.DEFAULT_URL }
        store.updateUrl = updateUrlInput
    }

    /**
     * 图片/文件请求要带的鉴权头，和 API 请求保持一致。
     *
     * 账号登录用 Bearer token；老的 API Key 配置继续用 X-API-Key。
     * 知识库正文里的图是 `resource://<handle>`，要经服务端 `/files` 代理取字节，
     * 鉴权必须和接口一致。
     */
    fun authHeaders(): Map<String, String> = buildMap {
        if (store.token.isNotBlank()) {
            put("Authorization", "Bearer " + store.token)
        } else if (store.apiKey.isNotBlank()) {
            put("X-API-Key", store.apiKey)
            put("Authorization", "Bearer " + store.apiKey)
        }
        if (store.tenantId.isNotBlank()) put("X-Tenant-ID", store.tenantId)
    }

    /* ---------------- 通用反馈 ---------------- */
    var busy by mutableStateOf(false)
    var busyLabel by mutableStateOf("")
    var snackText by mutableStateOf<String?>(null)
    /** 上一次「测试并保存」的结果。null = 还没测过。用于给结果卡片上色 */
    var connectOk by mutableStateOf<Boolean?>(null)
    fun consumeSnack() { snackText = null }

    /* ---------------- 知识库 ---------------- */
    val knowledgeBases = mutableStateListOf<KnowledgeBase>()
    var currentKb by mutableStateOf<KnowledgeBase?>(null)

    /**
     * 检索范围是否是「全部知识库」。
     *
     * 默认就是全部：单人自建库通常按主题拆了好几个，问问题时并不想先挑库。
     * 选具体库时才置 false。文档页需要具体库，在全部模式下会提示去选库。
     */
    var allKbsSelected by mutableStateOf(store.lastKbId == SettingsStore.ALL_KB)

    /** 顶栏 / 空态显示用的库名 */
    val kbDisplayName: String
        get() = if (allKbsSelected) "全部知识库" else (currentKb?.name ?: "未选择知识库")

    /** 本轮检索要带的库 id 列表 */
    val chatKbIds: List<String>
        get() = if (allKbsSelected) knowledgeBases.map { it.id }
        else listOfNotNull(currentKb?.id)

    /** 顶栏/空态展示的文档总数：全部模式 = 各库 knowledge_count 之和 */
    val displayDocCount: Int
        get() = if (allKbsSelected) knowledgeBases.sumOf { it.knowledgeCount }
        else if (kbDocCount > 0) kbDocCount else docs.size

    /**
     * kbId → 文档统计快照（设置页每个库右侧那三列数字）。
     *
     * 单独放 Map 而不是塞进 [KnowledgeBase]：那是接口模型，加可变字段会污染
     * 序列化对象，也会让「同一份数据在别处被改了没通知」变成常态。
     */
    val kbStats = mutableStateMapOf<String, KbStats>()

    /** 还有任意一个库没统计完 —— 设置页拿它在区块标题右侧转圈 */
    val kbStatsLoading: Boolean get() = kbStats.values.any { it.loading }

    private var statsJob: Job? = null

    /* ---------------- 会话与对话 ---------------- */
    val sessions = mutableStateListOf<Session>()
    var currentSessionId by mutableStateOf<String?>(null)

    /* ---------------- 历史会话 ---------------- */
    /** 跨知识库的会话列表（历史面板用） */
    val historySessions = mutableStateListOf<Session>()
    var historyLoading by mutableStateOf(false)

    fun refreshHistory() {
        viewModelScope.launch {
            historyLoading = true
            try {
                val list = withContext(Dispatchers.IO) {
                    apiNow().listAllSessions(page = 1, pageSize = 50).items
                }
                historySessions.clear()
                historySessions.addAll(list)
            } catch (e: Exception) {
                Log.w("WeKnora", "refreshHistory failed", e)
            } finally {
                historyLoading = false
            }
        }
    }

    /* ---------------- 本轮的运行参数（智能体 / 模型 / 思考强度 / 附件） ---------------- */
    val agents = mutableStateListOf<Agent>()
    val models = mutableStateListOf<ModelInfo>()
    /** 空串 = 不用智能体，走 knowledge-chat */
    var selectedAgentId by mutableStateOf(store.agentId)
        private set

    /** 选智能体并记住，下次启动直接恢复 */
    fun selectAgent(id: String) {
        selectedAgentId = id
        store.agentId = id
    }
    /** 空串 = 沿用知识库/智能体默认模型 */
    var selectedModelId by mutableStateOf("")
    /** 空串 = 沿用智能体配置；否则 off/auto/minimal/low/medium/high/xhigh/max */
    var reasoningEffort by mutableStateOf("")
    /**
     * 本轮是否允许联网搜索。默认关 —— 开了会明显变慢（实测同一问题 43s → 75s），
     * 应该是「需要时才打开」，而不是默认打开。
     */
    var webSearchEnabled by mutableStateOf(false)
    val attachments = mutableStateListOf<Attachment>()
    /** 只给 UI 用的 KnowledgeQA 模型（Embedding/Rerank 等不该出现在聊天下拉里） */
    val chatModels: List<ModelInfo>
        get() = models.filter { it.type.equals("KnowledgeQA", true) }
    val messages = mutableStateListOf<ChatMessage>()
    var streaming by mutableStateOf(false)

    /* ---------------- 文档 ---------------- */
    val docs = mutableStateListOf<Knowledge>()
    val folders = mutableStateListOf<FolderInfo>()
    var docFilter by mutableStateOf("")
    var selectedDocs = mutableStateListOf<String>()
    var uploadProgress by mutableStateOf(false)

    /**
     * 当前目录筛选：`""` = 全部，`"/"` = 仅根目录，其余 = 具体目录路径。
     * 服务端语义实测确认（folder_path=/ 返回 root_document_count 条）。
     */
    var currentFolder by mutableStateOf("")
    /** 当前筛选条件下服务端报告的总条数（分页拉完后应与 docs.size 一致） */
    var docsTotal by mutableStateOf(0)
    var docsLoading by mutableStateOf(false)
    /** 根目录下直属文档数，来自 folders 接口 */
    var rootDocCount by mutableStateOf(0)
    /** 知识库文档总数（不分目录） */
    var kbDocCount by mutableStateOf(0)

    /* ---------------- 产物 ---------------- */

    /** 智能体在对话里生成的文件，新→旧 */
    val artifacts = mutableStateListOf<Artifact>()
    var artifactsLoading by mutableStateOf(false)
    /** 文件名/会话标题的模糊筛选（客户端侧过滤，见 [visibleArtifacts]） */
    var artifactFilter by mutableStateOf("")
    /** 类型筛选：空串 = 全部 */
    var artifactType by mutableStateOf("")

    /**
     * 是否已经拉过一轮。用来区分两种空态：
     * 「还没拉」显示转圈，「拉过但是空的」显示空态说明。
     * 两者都用 `artifacts.isEmpty()` 判断的话，进页面会先闪一下空态。
     */
    var artifactsLoaded by mutableStateOf(false)
        private set

    /**
     * 加载失败的原因，null = 没出错。
     *
     * 存在的意义是**别把「拉不到」伪装成「没有」**：
     * 只用一个空列表表示失败，界面会显示「还没有产物」，那是编造出来的结论。
     */
    var artifactsError by mutableStateOf<String?>(null)
        private set

    /**
     * 数据可能过期。对话结束（或失败/中断）后置位 ——
     * 智能体可能刚好生成了新产物。
     * 产物页据此决定「重新进来时要不要再拉一次」，
     * 否则每切一次 tab 都要扫上百个会话。
     */
    var artifactsStale by mutableStateOf(false)
        private set

    private var artifactsJob: Job? = null

    /** 已拉到的产物里出现过的类型，给类型筛选条用 */
    val artifactTypes: List<String>
        get() = artifacts.map { it.ext }.distinct().sorted()

    /**
     * 套上关键词 + 类型筛选后的列表。
     *
     * 关键词在**本地**过滤而不是交给接口：会话聚合这条路本来就已经把数据
     * 全拿到了，再为关键词去发一次请求纯属浪费（而且 `/artifacts` 的
     * keyword 只作用于产物库，对会话聚合那条路无效）。
     */
    val visibleArtifacts: List<Artifact>
        get() {
            val k = artifactFilter.trim().lowercase()
            val t = artifactType
            if (k.isEmpty() && t.isEmpty()) return artifacts
            return artifacts.filter { a ->
                (t.isEmpty() || a.ext == t) &&
                    (k.isEmpty() ||
                        a.displayName.lowercase().contains(k) ||
                        a.sessionTitle.lowercase().contains(k))
            }
        }

    private fun apiNow(): WeKnoraApi = api ?: throw ApiException("未配置连接")

    /* ============================================================
     * 连接
     * ============================================================ */

    init {
        if (configured) {
            api = WeKnoraApi(store.config())
            loadAll()
        }
    }

    fun saveAndTest() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            busyLabel = "登录中…"
            try {
                val url = baseUrlInput.trim().trimEnd('/')
                if (url.isBlank()) throw ApiException("请填写服务地址")
                val mail = emailInput.trim()
                if (mail.isBlank() || passwordInput.isBlank()) {
                    throw ApiException("请填写账号和密码")
                }
                store.baseUrl = url
                val candidate = WeKnoraApi(ConnectionConfig(url, "", ""))
                val r = withContext(Dispatchers.IO) { candidate.login(mail, passwordInput) }
                store.token = r.token
                store.refreshToken = r.refreshToken
                store.email = mail
                store.password = passwordInput
                store.tenantId = r.activeTenant?.id?.toString().orEmpty()
                tenantIdInput = store.tenantId
                api = WeKnoraApi(store.config())
                configured = true
                connectOk = true
                passwordInput = ""
                snackText = "登录成功" + (r.activeTenant?.name?.let { " · $it" } ?: "")
                loadAll()
            } catch (e: Exception) {
                connectOk = false
                snackText = "登录失败：" + e.message
                Log.w("WeKnora", "login failed", e)
            } finally {
                busy = false
            }
        }
    }

    /** 退出登录：清 token / 密码 / 会话缓存，回到登录页 */
    fun logout() {
        cancelStreaming()
        store.clearSession()
        api = null
        configured = false
        connectOk = null
        snackText = null
        emailInput = store.email
        passwordInput = ""
    }

    /**
     * 静默续期：token 过期时用存下来的账号密码重新登录一次。
     * 成功返回 true（调用方可重试原请求），失败返回 false。
     */
    private suspend fun reloginIfPossible(): Boolean {
        val url = store.baseUrl
        val mail = store.email
        val pwd = store.password
        if (url.isBlank() || mail.isBlank() || pwd.isBlank()) return false
        return try {
            val r = withContext(Dispatchers.IO) {
                WeKnoraApi(ConnectionConfig(url, "", "")).login(mail, pwd)
            }
            store.token = r.token
            store.refreshToken = r.refreshToken
            store.tenantId = r.activeTenant?.id?.toString().orEmpty()
            tenantIdInput = store.tenantId
            api = WeKnoraApi(store.config())
            true
        } catch (e: Exception) {
            Log.w("WeKnora", "relogin failed", e)
            false
        }
    }

    fun editConfig() {
        configured = false
        cancelStreaming()
    }

    private fun loadAll() {
        loadKnowledgeBases()
        loadAgents()
        loadModels()
    }

    /** 智能体列表：API Key 缺 read_agents/chat 能力时会 403，静默降级即可 */
    fun loadAgents() {
        viewModelScope.launch {
            try {
                val l = withContext(Dispatchers.IO) { apiNow().listAgents() }
                agents.clear()
                agents.addAll(l)
                // 记住的智能体可能已被删除：清掉，别让下拉一直显示一个不存在的选项
                if (selectedAgentId.isNotBlank() && l.none { it.id == selectedAgentId }) {
                    selectAgent("")
                }
            } catch (e: Exception) {
                Log.w("WeKnora", "loadAgents failed（可能 API Key 无 read_agents 能力）", e)
            }
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            try {
                val l = withContext(Dispatchers.IO) { apiNow().listModels() }
                models.clear()
                models.addAll(l)
            } catch (e: Exception) {
                Log.w("WeKnora", "loadModels failed", e)
            }
        }
    }

    /* ============================================================
     * 知识库
     * ============================================================ */

    fun loadKnowledgeBases() {
        viewModelScope.launch {
            try {
                val list = try {
                    withContext(Dispatchers.IO) { apiNow().listKnowledgeBases() }
                } catch (e: ApiException) {
                    // token 过期（401）：用存下来的账号密码静默续一次，再重试
                    if (e.code == 401 && reloginIfPossible()) {
                        withContext(Dispatchers.IO) { apiNow().listKnowledgeBases() }
                    } else {
                        throw e
                    }
                }
                knowledgeBases.clear()
                knowledgeBases.addAll(list)
                loadKbStats()
                val remembered = store.lastKbId
                when {
                    // 「全部知识库」是默认值：没记住具体库就按全部来
                    remembered.isBlank() || remembered == SettingsStore.ALL_KB -> selectAllKb()
                    else -> {
                        val target = list.firstOrNull { it.id == remembered }
                        if (target != null) selectKb(target) else selectAllKb()
                    }
                }
            } catch (e: Exception) {
                snackText = "加载知识库失败：" + e.message
            }
        }
    }

    /* ============================================================
     * 知识库文档统计（设置页）
     * ============================================================ */

    /**
     * 统计每个知识库的「总数 / 排队 / 处理中」。
     *
     * 为什么不用服务端现成的数字：知识库列表里确实带了 knowledge_count（总数，
     * 实测与文档接口 total 一致，可信）和一个 processing_count（**不可信** ——
     * 实测有 123 篇 finalizing 时它仍是 0）。而「排队」根本没有对应字段。
     * 所以总数直接用列表里的，排队/处理中按文档状态本地分类数出来 ——
     * 顺带保证跟文档页那套 pill 文案、颜色完全一致，不会出现两个页面对不上。
     *
     * 一次统计要按库各拉一遍全量文档，属于重活，所以：
     *   - 只在知识库列表加载完、以及上传/删除/重建解析之后触发，不做定时轮询；
     *   - 限流 [STATS_CONCURRENCY] 并发；
     *   - 重复调用（含设置页那个刷新按钮）会取消上一轮，防止旧结果盖掉新结果。
     */
    fun loadKbStats() {
        val kbs = knowledgeBases.toList()
        if (kbs.isEmpty() || api == null) return
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            // 先占位：总数是知识库列表里现成的，先显示出来，不闪一片空白
            kbs.forEach { kb ->
                kbStats[kb.id] = KbStats(total = kb.knowledgeCount, loading = true)
            }
            val gate = Semaphore(STATS_CONCURRENCY)
            coroutineScope {
                kbs.forEach { kb ->
                    launch {
                        kbStats[kb.id] = try {
                            gate.withPermit {
                                withContext(Dispatchers.IO) { countKb(kb) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("WeKnora", "统计知识库失败：" + kb.name, e)
                            // 明细整个拉不到：总数还能给（列表里现成的），
                            // 完成/处理中留空显示破折号，别编一个 0 出来
                            KbStats(total = kb.knowledgeCount)
                        }
                    }
                }
            }
        }
    }

    /** 拉一个库的全量文档并分类计数 */
    private suspend fun countKb(kb: KnowledgeBase): KbStats {
        val seen = HashSet<String>()
        val acc = ArrayList<Knowledge>()
        var reported = 0
        var page = 1
        while (page <= MAX_DOC_PAGES) {
            val p = apiNow().listKnowledge(
                kbId = kb.id,
                page = page,
                pageSize = STATS_PAGE_SIZE
            )
            // 翻页期间若有增删，同一条可能落在两页，按 id 去重
            p.items.forEach { if (seen.add(it.id)) acc.add(it) }
            reported = p.total
            if (p.items.isEmpty() || acc.size >= reported || p.items.size < STATS_PAGE_SIZE) break
            page++
        }
        return KbStats(
            total = when {
                kb.knowledgeCount > 0 -> kb.knowledgeCount
                reported > 0 -> reported
                else -> acc.size
            },
            queued = acc.count { it.isQueued },
            processing = acc.count { it.isRunning }
        )
    }

    fun selectKb(kb: KnowledgeBase) {
        if (!allKbsSelected && kb.id == currentKb?.id) return
        // 必须先掐断正在跑的流：否则清空 messages 之后到达的 SSE 帧
        // 会按旧下标写回，直接 IndexOutOfBounds 崩进程
        cancelStreaming()
        allKbsSelected = false
        currentKb = kb
        store.lastKbId = kb.id
        currentSessionId = null
        messages.clear()
        selectedDocs.clear()
        attachments.clear()
        // 换库必须重置目录筛选，否则会带着上一个库的目录路径去查
        currentFolder = ""
        docsTotal = 0
        loadSessions()
        loadDocs()
        loadFolders()
    }

    /**
     * 切到「全部知识库」。
     *
     * 对话会把所有库 id 一起发给服务端；文档页需要具体库，
     * 这一页在全部模式下只显示提示，不拉列表。
     */
    fun selectAllKb() {
        if (allKbsSelected) return
        cancelStreaming()
        allKbsSelected = true
        currentKb = null
        store.lastKbId = SettingsStore.ALL_KB
        currentSessionId = null
        messages.clear()
        selectedDocs.clear()
        attachments.clear()
        currentFolder = ""
        docsTotal = 0
        docs.clear()
        folders.clear()
        sessions.clear()
    }

    fun createKb(name: String, desc: String = "") {
        if (name.isBlank()) return
        viewModelScope.launch {
            busy = true
            busyLabel = "创建中…"
            try {
                val kb = withContext(Dispatchers.IO) { apiNow().createKnowledgeBase(name, desc) }
                knowledgeBases.add(kb)
                // 新库在统计表里还没有条目，不刷的话三列会一直空着
                loadKbStats()
                selectKb(kb)
                snackText = "已创建「${kb.name}」"
            } catch (e: Exception) {
                snackText = "创建失败：" + e.message
            } finally {
                busy = false
            }
        }
    }

    /* ============================================================
     * 会话
     * ============================================================ */

    fun loadSessions() {
        val kb = currentKb ?: return
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { apiNow().listSessions(kb.id) }
                sessions.clear()
                sessions.addAll(list)
            } catch (e: Exception) {
                Log.w("WeKnora", "loadSessions", e)
            }
        }
    }

    /**
     * 打开一条历史会话：把服务端存的消息拉回来，按 role 还原成气泡。
     * references 是一个 JSON 字符串，解析后照样能显示来源与图片。
     */
    fun openSession(s: Session) {
        cancelStreaming()
        currentSessionId = s.id
        messages.clear()
        selectedDocs.clear()
        attachments.clear()
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { apiNow().loadMessages(s.id, 60) }
                Log.d("WeKnora", "history loaded: ${list.size} messages")
                messages.addAll(list.mapNotNull(ChatHistoryParser::parse))
                // 历史消息也补一次文档插图（模型当时可能没把图写进正文）
                messages.lastOrNull { it.role == ChatMessage.Role.ASSISTANT && it.references.isNotEmpty() }
                    ?.let { loadDocImages(it.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("WeKnora", "openSession failed", e)
                snackText = "加载会话失败：" + e.message
            }
        }
    }

    fun newSession() {
        // 清空本地并把 currentSessionId 置空，下次提问会自动建新会话
        cancelStreaming()
        currentSessionId = null
        messages.clear()
        attachments.clear()
        snackText = "已开启新会话"
    }

    /**
     * 会话是懒创建的：首次提问或首次挂附件时才真正建。
     *
     * 用锁串起来 —— 多选附件时每个文件一个协程，全都看到
     * `currentSessionId == null`，会各建一个会话，附件散落在 N 个会话里，
     * 提问时只能带走其中一个。
     */
    private suspend fun ensureSession(kbId: String, title: String): String {
        currentSessionId?.let { return it }
        return sessionLock.withLock {
            // 双检：等锁期间可能已经被别的协程建好了
            currentSessionId?.let { return@withLock it }
            val s = withContext(Dispatchers.IO) { apiNow().createSession(kbId, title) }
            currentSessionId = s.id
            sessions.add(0, s)
            s.id
        }
    }

    /**
     * 挂附件到当前会话。必须先有 sessionId —— 附件是会话级资源
     * （POST /sessions/{sid}/attachments），所以这里会顺带把会话建出来。
     */
    fun attachFile(uri: Uri) = attachFiles(listOf(uri))

    /**
     * 批量挂附件。
     *
     * 刻意**串行**：并发时每个协程都会走一遍 ensureSession（虽有锁兜底，
     * 但 busy / busyLabel 会互相覆盖，失败提示也会被后面那条顶掉），
     * 而且会话本身就是串行资源。「一次选择多个文件」在 UI 上是一件事，
     * 就当一个操作做完、只弹一条汇总提示。
     */
    fun attachFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // 附件是挂在会话上的，必须有具体库来建会话；全部模式下用第一个库兜底
        val kb = currentKb ?: knowledgeBases.firstOrNull() ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            busy = true
            val okNames = mutableListOf<String>()
            val failures = mutableListOf<String>()
            try {
                uris.forEachIndexed { i, uri ->
                    busyLabel =
                        if (uris.size > 1) "上传附件 ${i + 1}/${uris.size}…" else "上传附件…"
                    try {
                        val att = withContext(Dispatchers.IO) { attachOne(kb.id, resolver, uri) }
                        attachments.add(att)
                        okNames += att.fileName
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val label = runCatching {
                            withContext(Dispatchers.IO) { queryDisplayName(resolver, uri) }
                        }.getOrNull() ?: "文件"
                        failures += "$label：${e.message}"
                    }
                }
            } finally {
                busy = false
            }
            snackText = buildString {
                if (okNames.isNotEmpty()) {
                    append("已附加 ${okNames.size} 个：${okNames.joinToString("、")}（服务端解析中）")
                }
                if (failures.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("失败 ${failures.size} 个：").append(failures.joinToString("；"))
                }
            }.ifBlank { "没有可附加的文件" }
        }
    }

    /** 单个附件的完整读取 + 上传。会话已在 [ensureSession] 里建好 */
    private suspend fun attachOne(
        kbId: String,
        resolver: android.content.ContentResolver,
        uri: Uri
    ): Attachment {
        val sid = ensureSession(kbId, "附件会话")
        val name = withContext(Dispatchers.IO) { queryDisplayName(resolver, uri) }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val size = withContext(Dispatchers.IO) {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }
        return withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { stream ->
                apiNow().uploadAttachment(sid, name, mime, stream, size)
            } ?: throw ApiException("无法读取所选文件")
        }
    }

    fun removeAttachment(att: Attachment) {
        attachments.remove(att)
        val sid = currentSessionId ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { apiNow().deleteAttachment(sid, att.id) }
            } catch (e: Exception) {
                Log.w("WeKnora", "deleteAttachment", e)
            }
        }
    }

    /* ============================================================
     * 流式问答
     * ============================================================ */

    fun send(text: String) {
        val kbIds = chatKbIds
        val sessionKbId = currentKb?.id ?: kbIds.firstOrNull() ?: return
        val query = text.trim()
        if (query.isBlank() || streaming || kbIds.isEmpty()) return

        // 本轮参数在流启动那一刻快照下来：流式过程中改下拉不会影响已发出的请求
        val agentId = selectedAgentId
        val modelId = selectedModelId
        val effort = reasoningEffort
        val webSearch = webSearchEnabled
        val attachIds = attachments.map { it.id }
        val attachNames = attachments.map { it.fileName }

        viewModelScope.launch {
            val finalSid = try {
                ensureSession(sessionKbId, query.take(20))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackText = "发送失败：" + e.message
                return@launch
            }

            // 消息 id 用 UUID：时间戳在同一毫秒内发两条会撞 LazyColumn 的 key
            val uid = "u" + UUID.randomUUID()
            val aid = "a" + UUID.randomUUID()
            messages.add(ChatMessage(id = uid, role = ChatMessage.Role.USER, content = query,
                attachments = attachNames))
            messages.add(ChatMessage(id = aid, role = ChatMessage.Role.ASSISTANT,
                content = "", streaming = true, webSearch = webSearch))
            streaming = true

            val startTime = System.currentTimeMillis()
            // 智能推理模式下消息的 knowledge_references 是空的，图片只能从
            // tool_result / references 帧里捡 —— 那是服务端真实返回的数据，
            // 比模型正文里自己写的句柄可信
            val frameImages = LinkedHashSet<String>()
            streamJob = launch {
                try {
                    apiNow().streamChat(
                        sessionId = finalSid,
                        query = query,
                        kbIds = kbIds,
                        agentId = agentId,
                        modelId = modelId,
                        reasoningEffort = effort,
                        attachmentIds = attachIds,
                        webSearch = webSearch
                    ).collect { frame ->
                        if (frame.responseType == StreamFrame.TYPE_TOOL_RESULT ||
                            frame.responseType == StreamFrame.TYPE_REFERENCES
                        ) {
                            val blob = frame.content + " " + (frame.data?.toString() ?: "")
                            if (blob.contains("resource://")) {
                                RESOURCE_HANDLE.findAll(blob).forEach { frameImages += it.value }
                                updateMessage(aid) { it.copy(docImages = frameImages.take(6).toList()) }
                            }
                        }
                        when (frame.responseType) {
                            StreamFrame.TYPE_THINKING ->
                                updateMessage(aid) { it.copy(thinking = it.thinking + frame.content) }

                            StreamFrame.TYPE_ANSWER ->
                                updateMessage(aid) { it.applyAnswer(frame) }

                            StreamFrame.TYPE_REFERENCES -> {
                                // 检索来源：既给「参考了哪几篇」，也是知识库图片的主要来源
                                val refs = ReferenceParser.parse(frame.knowledgeReferences ?: frame.data)
                                if (refs.isNotEmpty()) {
                                    updateMessage(aid) { it.copy(references = refs) }
                                }
                            }

                            StreamFrame.TYPE_TOOL_CALL -> {
                                // agent 模式会穿插工具调用，名字取出来做 chip 展示
                                val name = toolName(frame)
                                if (name != "final_answer") {
                                    updateMessage(aid) { cur ->
                                        val next = cur.movePreambleToThinking()
                                        if (name.isBlank() || name in next.tools) next
                                        else next.copy(tools = next.tools + name)
                                    }
                                }
                            }

                            StreamFrame.TYPE_ERROR ->
                                updateMessage(aid) {
                                    it.copy(
                                        streaming = false,
                                        error = frame.content.ifBlank { "服务端返回错误" }
                                    )
                                }

                            StreamFrame.TYPE_COMPLETE ->
                                updateMessage(aid) { cur ->
                                    cur.copy(
                                        content = extractFinal(frame).ifBlank { cur.content },
                                        streaming = false
                                    )
                                }
                        }
                    }
                    // 部分实现不发 complete 帧，兜底收尾
                    updateMessage(aid) { if (it.streaming) it.copy(streaming = false) else it }
                    if (frameImages.isNotEmpty()) {
                        updateMessage(aid) { it.copy(docImages = frameImages.take(6).toList()) }
                        Log.d("WeKnora", "docImages from frames: ${frameImages.size}")
                    } else {
                        // 帧里没图时，再按引用的文档补一次
                        runCatching { loadDocImages(aid) }
                    }
                } catch (e: CancellationException) {
                    // 用户点「停止生成」/ 切库 / 新会话导致的中断，不是错误：
                    // 必须原样抛出，不能当成失败往气泡里塞红字，否则
                    // 点一下停止就冒出「生成中断：JobCancellationException」。
                    throw e
                } catch (e: Exception) {
                    updateMessage(aid) {
                        it.copy(streaming = false, error = "生成中断：" + e.message)
                    }
                } finally {
                    streaming = false
                    // 附件随本轮请求一起发走了，用完即清，避免下一条追问重复带上
                    attachments.clear()
                    // 这一轮可能生成了新产物，标记产物页下次进入时要重拉。
                    // 不在这里直接 loadArtifacts()：那要扫上百个会话，
                    // 而用户大概率压根不会去看产物页 —— 把成本推迟到真正需要时
                    artifactsStale = true
                    Log.d("WeKnora", "done in ${System.currentTimeMillis() - startTime}ms")
                }
            }
        }
    }

    /**
     * 从被引用的文档里补插图。
     *
     * 模型经常：「只写了文字，没把图写进答案」或「写出来的句柄是编的」。
     * 但文档分块里 `resource://` 图是真实存在的，所以回答落地后按
     * knowledge_id 拉一次分块，取引用位置附近、最多 6 张。
     */
    private suspend fun loadDocImages(aid: String) {
        val msg = messages.firstOrNull { it.id == aid } ?: return
        if (msg.docImages.isNotEmpty()) return
        val ref = msg.references.firstOrNull { it.knowledgeId.isNotBlank() } ?: return
        val chunks = withContext(Dispatchers.IO) { apiNow().listChunks(ref.knowledgeId) }
        val found = chunks.mapNotNull { o ->
            val idx = (o["chunk_index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
            val content = (o["content"] as? JsonPrimitive)?.content ?: ""
            val hs = RESOURCE_HANDLE.findAll(content).map { it.value }.toList()
            if (hs.isEmpty()) null else idx to hs
        }
        val target = ref.chunkIndex
        val ordered = if (target >= 0) {
            found.sortedBy { kotlin.math.abs(it.first - target) }
        } else {
            found.sortedBy { it.first }
        }
        val images = ordered.flatMap { it.second }.distinct().take(6)
        if (images.isNotEmpty()) {
            updateMessage(aid) { it.copy(docImages = images) }
            Log.d("WeKnora", "docImages: ${images.size} from ${ref.knowledgeId.take(8)}")
        }
    }

    /**
     * 按 id 更新消息。
     *
     * 之前的写法是进入流之前记一个下标 `idx`，之后所有帧都 `messages[idx] = …`。
     * 但流式过程中用户可以切库 / 新会话，那些入口都会 `messages.clear()`，
     * 下一条帧一到就是 IndexOutOfBounds 崩进程（catch 里同样是 [idx]，二次抛出）。
     * 按 id 找、找不到就丢弃这一帧，才是安全的。
     */
    private fun updateMessage(id: String, block: (ChatMessage) -> ChatMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = block(messages[i])
    }

    /**
     * 中止当前流式回答。切库、新会话、切历史会话、停止生成都必须走这里 ——
     * 只清空 messages 而不取消流，就是上面那个崩溃的根因。
     */
    private fun cancelStreaming() {
        streamJob?.cancel()
        streamJob = null
        streaming = false
        val i = messages.indexOfLast { it.streaming }
        if (i >= 0) messages[i] = messages[i].copy(streaming = false)
    }

    /** tool_call 帧里工具名在 data.name / data.tool_name，实在没有就用 content */
    private fun toolName(frame: StreamFrame): String {
        val obj = frame.data as? JsonObject
        listOf("name", "tool_name", "function").forEach { k ->
            (obj?.get(k) as? JsonPrimitive)?.content?.let { if (it.isNotBlank()) return it }
        }
        return frame.content.trim().take(32)
    }

    /**
     * complete 帧里 data.final_content 是完整答案，优先使用服务端的最终内容
     * （可能包含流式正文里尚未补全的图片地址）。
     * 必须按 JsonPrimitive 取值：直接 toString() 会把 \n 变成字面的 \\n、
     * 引号带转义符一起塞进正文。
     */
    private fun extractFinal(frame: StreamFrame): String {
        val obj = frame.data as? JsonObject ?: return ""
        return (obj["final_content"] as? JsonPrimitive)?.content ?: ""
    }

    /** 用户点「停止生成」。协程一取消，API 层的哨兵就会掐断 HTTP 连接 */
    fun stopStreaming() = cancelStreaming()

    /* ============================================================
     * 文档
     * ============================================================ */

    /**
     * 拉文档。**必须分页拉到底**：
     *
     * 之前的写法是 `page_size=100` 只取一页，于是「文档超过 100 个就只能看到前 100」。
     * 服务端并没有 100 条硬上限（实测 page_size=500 可一次返回 361 条），
     * 但仍然按页翻更稳妥 —— 将来哪怕加上限也不会少数据。
     *
     * 停止条件三者任一：本页为空 / 累计已达 total / 本页不满一页。
     * 另加 MAX_PAGES 安全阀，防止 total 异常时无限翻页。
     */
    fun loadDocs() {
        val kb = currentKb ?: return
        val keyword = docFilter.trim()
        val folder = currentFolder
        viewModelScope.launch {
            docsLoading = true
            try {
                val acc = ArrayList<Knowledge>()
                val seen = HashSet<String>()
                var total = 0
                var page = 1
                while (page <= MAX_DOC_PAGES) {
                    val p = withContext(Dispatchers.IO) {
                        apiNow().listKnowledge(
                            kbId = kb.id,
                            page = page,
                            pageSize = DOC_PAGE_SIZE,
                            keyword = keyword,
                            folderPath = folder
                        )
                    }
                    // 按 id 去重：翻页期间若有文档增删，会出现同一条落在两页
                    p.items.forEach { if (seen.add(it.id)) acc.add(it) }
                    total = p.total
                    if (p.items.isEmpty() || acc.size >= total || p.items.size < DOC_PAGE_SIZE) break
                    page++
                }
                docs.clear()
                docs.addAll(acc)
                docsTotal = if (total > 0) total else acc.size
                if (acc.size < docsTotal) {
                    Log.w("WeKnora", "文档分页未拉满：${acc.size}/${docsTotal}")
                }
            } catch (e: Exception) {
                snackText = "加载文档失败：" + e.message
            } finally {
                docsLoading = false
            }
        }
    }

    fun loadFolders() {
        val kb = currentKb ?: return
        viewModelScope.launch {
            try {
                val env = withContext(Dispatchers.IO) { apiNow().listFolders(kb.id) }
                folders.clear()
                folders.addAll(env.folders)
                rootDocCount = env.rootDocumentCount
                kbDocCount = env.totalDocumentCount
            } catch (e: Exception) {
                Log.w("WeKnora", "loadFolders", e)
            }
        }
    }

    /** 切换目录筛选；传 "" 表示全部 */
    fun selectFolder(path: String) {
        if (path == currentFolder) return
        currentFolder = path
        selectedDocs.clear()
        loadDocs()
    }

    /** 把已勾选的文档移动到目标目录（`""` = 根目录） */
    fun moveSelectedToFolder(target: String) {
        val ids = selectedDocs.toList()
        if (ids.isEmpty()) {
            snackText = "请先勾选文档"
            return
        }
        runDocAction("移动", ids) { a, kb, list ->
            a.changeFolder(kb, list, target)
        }
        // 移动后目录统计会变，目录树要跟着刷新
        loadFolders()
    }

    fun toggleSelectDoc(id: String) {
        if (selectedDocs.contains(id)) selectedDocs.remove(id) else selectedDocs.add(id)
    }

    fun clearSelection() = selectedDocs.clear()

    /**
     * 上传。uri 来自系统文件选择器，通过 contentResolver 拿 InputStream，
     * 全程流式，不把文件读进内存。
     */
    fun upload(uri: Uri) = uploadAll(listOf(uri))

    /**
     * 批量上传到知识库。
     *
     * 与附件同理：多选时串行跑完再刷一次列表。原先每选 N 个文件就并行起 N 个
     * 协程，各自在结束时调用 [loadDocs]（而 loadDocs 是全量翻页），
     * 等于传 5 个文件要翻 5 遍全库。
     */
    fun uploadAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val kb = currentKb ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            uploadProgress = true
            busy = true
            val okNames = mutableListOf<String>()
            val failures = mutableListOf<String>()
            try {
                uris.forEachIndexed { i, uri ->
                    busyLabel =
                        if (uris.size > 1) "上传 ${i + 1}/${uris.size}…" else "上传中…"
                    try {
                        val name = withContext(Dispatchers.IO) { queryDisplayName(resolver, uri) }
                        val mime = resolver.getType(uri) ?: "application/octet-stream"
                        val size = withContext(Dispatchers.IO) {
                            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                        }
                        withContext(Dispatchers.IO) {
                            resolver.openInputStream(uri)?.use { stream ->
                                apiNow().upload(kb.id, name, mime, stream, size)
                            } ?: throw ApiException("无法读取所选文件")
                        }
                        okNames += name
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val label = runCatching {
                            withContext(Dispatchers.IO) { queryDisplayName(resolver, uri) }
                        }.getOrNull() ?: "文件"
                        failures += "$label：${e.message}"
                    }
                }
            } finally {
                uploadProgress = false
                busy = false
            }
            snackText = buildString {
                if (okNames.isNotEmpty()) append("已上传 ${okNames.size} 个：${okNames.joinToString("、")}")
                if (failures.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("失败 ${failures.size} 个：").append(failures.joinToString("；"))
                }
            }.ifBlank { "没有可上传的文件" }
            loadDocs()
            // 新增文档会改变统计，顺手刷一次（设置页的三个数字）
            loadKbStats()
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String {
        resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                val n = c.getString(idx)
                if (!n.isNullOrBlank()) return n
            }
        }
        return uri.lastPathSegment ?: "upload.bin"
    }

    fun reparseSelected() {
        val ids = selectedDocs.toList()
        runDocAction("重建解析", ids) { a, kb, list ->
            try {
                a.batchReparse(kb, list)
            } catch (e: Exception) {
                // 批量接口不可用时退化为逐条（与桌面上传器一致的行为）
                list.forEach { a.reparse(it) }
            }
        }
    }

    fun cancelSelected() {
        // 取消解析只有单条接口，没有批量版
        runDocAction("取消解析", selectedDocs.toList()) { a, _, list ->
            list.forEach { a.cancelParse(it) }
        }
    }

    fun deleteSelected() {
        val ids = selectedDocs.toList()
        runDocAction("删除", ids) { a, kb, list ->
            try {
                a.batchDelete(kb, list)
            } catch (e: Exception) {
                list.forEach { a.deleteKnowledge(it) }
            }
        }
    }

    private fun runDocAction(
        label: String,
        ids: List<String>,
        block: suspend (WeKnoraApi, String, List<String>) -> Unit
    ) {
        if (ids.isEmpty()) {
            snackText = "请先勾选文档"
            return
        }
        val kbId = currentKb?.id ?: run {
            snackText = "未选择知识库"
            return
        }
        viewModelScope.launch {
            busy = true
            busyLabel = "$label 中…"
            try {
                withContext(Dispatchers.IO) { block(apiNow(), kbId, ids) }
                snackText = "$label 完成：${ids.size} 项"
            } catch (e: Exception) {
                snackText = "$label 失败：" + e.message
            } finally {
                selectedDocs.clear()
                busy = false
            }
            // 解析状态服务端不会立刻翻转，晚一点刷才能看到 pending/processing
            delay(1200)
            loadDocs()
            // 删除 / 取消解析 / 重建解析都会改变「完成 / 处理中」的分布
            loadKbStats()
        }
    }

    /**
     * 下载原始文件。交给系统 DownloadManager：
     * 自带进度通知、大文件不用自己管 IO、也不用申请存储权限（API 29+）。
     */
    fun openDocument(context: Context, doc: Knowledge) {
        val format = doc.fileType.trimStart('.').lowercase().ifBlank { doc.title.substringAfterLast('.', "").lowercase() }
        if (format !in setOf("pdf", "docx")) {
            snackText = if (format == "doc") "旧版 .doc 请先转换为 .docx 后阅读；仍可下载原文" else "内置阅读支持 PDF 和 DOCX；其他格式请下载原文"
            return
        }
        val api = api ?: return
        val headers = android.os.Bundle().apply { api.authHeaders().forEach { (key, value) -> putString(key, value) } }
        context.startActivity(android.content.Intent(context, com.jinge.weknora.DocumentReaderActivity::class.java)
            .putExtra("url", api.downloadUrl(doc.id)).putExtra("title", doc.title)
            .putExtra("format", format).putExtra("headers", headers))
    }

    fun downloadDoc(context: Context, doc: Knowledge) {
        val api = api ?: return
        try {
            val request = DownloadManager.Request(Uri.parse(api.downloadUrl(doc.id)))
                .setTitle(doc.title)
                .setDescription("来自 WeKnora 知识库")
                .apply { api.authHeaders().forEach { (k, v) -> addRequestHeader(k, v) } }
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, doc.title)
            // allowScanningByMediaScanner() 返回 Unit 且不参与链式，单独调用
            request.allowScanningByMediaScanner()
            val dm = context.getSystemService(DownloadManager::class.java)
            dm.enqueue(request)
            snackText = "已开始下载：${doc.title}"
        } catch (e: Exception) {
            snackText = "下载失败：" + e.message
        }
    }

    /* ============================================================
     * 产物
     * ============================================================ */

    /** 产物页进入时调用：没拉过或数据已过期才真的去拉 */
    fun ensureArtifacts() {
        if (!artifactsLoaded || artifactsStale) loadArtifacts()
    }

    /**
     * 拉产物列表。
     *
     * **取数路径是「按会话聚合」，不是官方的产物库接口。**
     * 官方 `GET /api/v1/artifacts` 明确排除 API Key 会话（App 全程用 API Key），
     * 实测确实恒为 `total=0`；而 `GET /sessions/{sid}/artifacts`
     * 对 API Key 会话返回 200，是唯一真正能取到产物的路。
     * 所以这里是「列全部会话 → 并发问每个会话要产物 → 合并」。
     *
     * 代价是 N 次请求，所以：
     *   - 只扫最近 [ARTIFACT_SESSION_LIMIT] 个会话（列表按时间倒序）；
     *   - 限流 [ARTIFACT_CONCURRENCY] 并发，别把 NAS 打满；
     *   - 重复调用取消上一轮，防止旧结果盖掉新结果。
     * 绝大多数会话没有产物，响应只是一个空数组，很快。
     */
    fun loadArtifacts() {
        if (api == null) return
        artifactsJob?.cancel()
        artifactsJob = viewModelScope.launch {
            artifactsLoading = true
            artifactsError = null
            try {
                // ① 官方产物库。对 API Key 主体恒为空（见 WeKnoraApi.listArtifacts 的
                //    注释），仍然取一次是为了「换成 JWT 登录后自动有数据」，
                //    代价只是一个返回空数组的请求。
                val fromLibrary = try {
                    withContext(Dispatchers.IO) {
                        apiNow().listArtifacts(pageSize = ARTIFACT_SESSION_LIMIT).items
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("WeKnora", "读产物库失败（API Key 会话本就不在其中）", e)
                    emptyList()
                }

                // ② 会话聚合 —— App 里真正有数据的那条路
                val sessions = withContext(Dispatchers.IO) {
                    apiNow().listAllSessions(page = 1, pageSize = ARTIFACT_SESSION_LIMIT).items
                }
                val gate = Semaphore(ARTIFACT_CONCURRENCY)
                // 用 async + awaitAll 收集，而不是往共享列表里写 ——
                // 共享列表要加锁，而这里天然就是「各收各的、最后合并」，没有竞争
                val all = coroutineScope {
                    sessions.map { s ->
                        async {
                            val list = try {
                                gate.withPermit {
                                    withContext(Dispatchers.IO) { apiNow().listSessionArtifacts(s.id) }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // 单个会话读不到（已删除 / 无权限）不该拖垮整页
                                Log.w("WeKnora", "读会话产物失败：" + s.title, e)
                                emptyList()
                            }
                            // 会话维度接口不带 session_title，这里补上会话标题，
                            // 否则列表里看不出文件是哪个对话生成的
                            list.map { a ->
                                if (a.sessionTitle.isBlank()) a.copy(sessionTitle = s.title) else a
                            }
                        }
                    }.awaitAll().flatten()
                }
                // 两个来源按 (会话, 消息, 下标) 去重 —— 同一产物完全可能两边都有
                // 新 → 旧。created_at 是 RFC3339，同格式下字典序即时间序，
                // 直接用字符串比较，不必引日期库
                val sorted = (fromLibrary + all)
                    .distinctBy { it.key }
                    .sortedByDescending { it.createdAt }
                artifacts.clear()
                artifacts.addAll(sorted)
                artifactsLoaded = true
                artifactsStale = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 不写 snackText：整页都拉不到时，一条一闪而过的提示撑不住，
                // 界面会退化成「还没有产物」——那是编出来的结论。存下来交给页面展示。
                artifactsError = e.message ?: "未知错误"
            } finally {
                artifactsLoading = false
            }
        }
    }

    /**
     * 下载产物。同文档一样交给系统 DownloadManager ——
     * 自带进度通知、大文件不用自己管 IO、API 29+ 也不用存储权限。
     *
     * 落地到 `Downloads/WeKnora/` 子目录：产物是「某轮对话的产出」，
     * 跟知识库文档混在同一个目录里不好找。
     */
    fun downloadArtifact(context: Context, a: Artifact) {
        val api = api ?: return
        val name = a.displayName.ifBlank { "artifact_${a.index}" }
        try {
            val request = DownloadManager.Request(Uri.parse(api.artifactDownloadUrl(a)))
                .setTitle(name)
                .setDescription("来自 WeKnora 对话产物")
                .apply { api.authHeaders().forEach { (k, v) -> addRequestHeader(k, v) } }
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "WeKnora/$name")
            request.allowScanningByMediaScanner()
            val dm = context.getSystemService(DownloadManager::class.java)
            dm.enqueue(request)
            snackText = "已开始下载：$name"
        } catch (e: Exception) {
            snackText = "下载失败：" + e.message
        }
    }

    /**
     * 删除产物。**服务端会回收对象存储里的字节，不可恢复** ——
     * 调用方（产物页）必须先弹确认框再来这里。
     *
     * 删完只从本地列表摘掉，不重新拉全量：官方说明产物在消息里的**位置会被保留**
     * （index 就是下载地址，后面的文件不会前移）。重拉虽然也对，但会让整页跳一下。
     */
    fun deleteArtifact(a: Artifact) {
        viewModelScope.launch {
            busy = true
            busyLabel = "删除产物…"
            try {
                withContext(Dispatchers.IO) { apiNow().deleteArtifact(a) }
                artifacts.remove(a)
                snackText = "已删除：${a.displayName}"
            } catch (e: Exception) {
                snackText = "删除失败：" + e.message
            } finally {
                busy = false
            }
        }
    }

    fun config(): ConnectionConfig? = if (configured) store.config() else null
}
