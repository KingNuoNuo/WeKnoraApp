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

    /** 上次选中的知识库，启动时自动恢复，省得每次重选 */
    var lastKbId: String
        get() = prefs.getString(KEY_LAST_KB, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_KB, v).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    fun config(): ConnectionConfig = ConnectionConfig(baseUrl, apiKey, tenantId)

    companion object {
        private const val PREF_NAME = "weknora_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TENANT_ID = "tenant_id"
        private const val KEY_LAST_KB = "last_kb_id"
    }
}
