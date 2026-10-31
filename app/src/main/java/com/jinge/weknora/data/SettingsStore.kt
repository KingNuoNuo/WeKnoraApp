package com.jinge.weknora.data

import android.content.Context

/**
 * 连接配置的持久化。
 *
 * 用 SharedPreferences 而非 DataStore：配置只有两三个值，ViewModel 里读一次就够，
 * 上 DataStore 反而多一层 Flow 和一个 coroutine 依赖。
 * API Key 属于凭据，已在 data_extraction_rules.xml 里排除出云备份。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BASE_URL, v.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_API_KEY, v.trim()).apply()

    /**
     * 可选的空间 ID（X-Tenant-ID）。
     * 空间级 API Key 不需要；平台级 API Key 必填，否则 409 TENANT_REQUIRED。
     */
    var tenantId: String
        get() = prefs.getString(KEY_TENANT_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TENANT_ID, v.trim()).apply()

    /** 上次选中的知识库；`__all__` 表示「全部知识库」 */
    var lastKbId: String
        get() = prefs.getString(KEY_LAST_KB, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_KB, v).apply()

    /** 上次选择的智能体 id（空串 = 知识库直答） */
    var agentId: String
        get() = prefs.getString(KEY_AGENT_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_AGENT_ID, v).apply()

    /**
     * 更新源地址（update.json）。
     *
     * 默认指向仓库的 raw 地址；改成自己的 NAS 也能用 —— 内网部署的人
     * 不该因为 GitHub 打不开就永远收不到更新提示。
     */
    var updateUrl: String
        get() = prefs.getString(KEY_UPDATE_URL, "").orEmpty().ifBlank { UpdateChecker.DEFAULT_URL }
        set(v) = prefs.edit().putString(KEY_UPDATE_URL, v.trim()).apply()

    /* ---------------- 账号登录 ---------------- */

    /** 当前登录账号（仅用于界面展示） */
    var email: String
        get() = prefs.getString(KEY_EMAIL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_EMAIL, v.trim()).apply()

    /**
     * 访问令牌（JWT）。
     *
     * 有它就用 `Authorization: Bearer <token>`，不再发 X-API-Key ——
     * 这是账号登录与 API Key 两种鉴权方式的唯一分界点。
     */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).apply()

    var refreshToken: String
        get() = prefs.getString(KEY_REFRESH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_REFRESH, v.trim()).apply()

    /**
     * 登录密码。只用于 access token 过期后自动重登。
     *
     * 存在 App 私有 SharedPreferences 里（已在 data_extraction_rules.xml
     * 排除出云备份），不出设备。不存密码就没法在 token 过期时静默续期，
     * 只能弹回登录页。
     */
    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && (token.isNotBlank() || apiKey.isNotBlank())

    fun config(): ConnectionConfig = ConnectionConfig(baseUrl, apiKey, tenantId, token)

    /** 退出登录：只清账号相关的，服务地址留着，方便直接重登 */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH)
            .remove(KEY_PASSWORD)
            .remove(KEY_LAST_KB)
            .apply()
    }

    companion object {
        /** lastKbId 的哨兵值：表示检索范围是「全部知识库」 */
        const val ALL_KB = "__all__"

        private const val PREF_NAME = "weknora_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TENANT_ID = "tenant_id"
        private const val KEY_LAST_KB = "last_kb_id"
        private const val KEY_AGENT_ID = "selected_agent_id"
        private const val KEY_UPDATE_URL = "update_url"
        private const val KEY_EMAIL = "account_email"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_REFRESH = "auth_refresh_token"
        private const val KEY_PASSWORD = "auth_password"
    }
}
