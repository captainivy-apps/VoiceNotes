package com.dafei.voicenotes.data

import android.content.Context
import android.content.SharedPreferences
import com.dafei.voicenotes.llm.LlmProfile

object AppPrefs {
    private const val NAME = "voice_notes_prefs"
    private const val KEY_SELECTED_ASR_MODEL = "selected_asr_model"
    private const val KEY_LLM_BASE_URL = "llm_base_url"
    private const val KEY_LLM_API_KEY = "llm_api_key"
    private const val KEY_LLM_MODEL = "llm_model"
    private const val KEY_LLM_SYSTEM_PROMPT = "llm_system_prompt"
    private const val KEY_LLM_PROFILES = "llm_profiles_json"
    private const val KEY_ACTIVE_LLM_PROFILE_ID = "active_llm_profile_id"
    private const val KEY_KEEP_SCREEN_ON_WHILE_RECORDING = "keep_screen_on_while_recording"

    const val DEFAULT_ASR_MODEL = "base"
    const val DEFAULT_LLM_BASE_URL = "https://api.openai.com"
    const val DEFAULT_LLM_MODEL = "gpt-4o-mini"
    const val DEFAULT_LLM_SYSTEM_PROMPT =
        "你是记事整理助手。将用户的口语识别稿整理为简洁、通顺的记事正文。" +
            "去除口语赘词和重复，修正逻辑顺序，保留原意与情感，不要添加未提及的内容。"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getSelectedAsrModel(context: Context): String =
        prefs(context).getString(KEY_SELECTED_ASR_MODEL, DEFAULT_ASR_MODEL) ?: DEFAULT_ASR_MODEL

    fun setSelectedAsrModel(context: Context, modelId: String) {
        prefs(context).edit().putString(KEY_SELECTED_ASR_MODEL, modelId).apply()
    }

    fun ensureLlmProfilesMigrated(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_LLM_PROFILES)) return
        val legacy = LlmProfile(
            id = LlmProfile.DEFAULT_PROFILE_ID,
            name = "默认",
            baseUrl = p.getString(KEY_LLM_BASE_URL, DEFAULT_LLM_BASE_URL) ?: DEFAULT_LLM_BASE_URL,
            apiKey = p.getString(KEY_LLM_API_KEY, "") ?: "",
            model = p.getString(KEY_LLM_MODEL, DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL,
            systemPrompt = p.getString(KEY_LLM_SYSTEM_PROMPT, DEFAULT_LLM_SYSTEM_PROMPT)
                ?: DEFAULT_LLM_SYSTEM_PROMPT
        )
        saveLlmProfiles(context, listOf(legacy))
        setActiveLlmProfileId(context, legacy.id)
    }

    fun getLlmProfiles(context: Context): List<LlmProfile> {
        ensureLlmProfilesMigrated(context)
        val json = prefs(context).getString(KEY_LLM_PROFILES, "") ?: ""
        return LlmProfile.listFromJson(json)
    }

    fun saveLlmProfiles(context: Context, profiles: List<LlmProfile>) {
        require(profiles.isNotEmpty()) { "至少保留一组 LLM 配置" }
        prefs(context).edit()
            .putString(KEY_LLM_PROFILES, LlmProfile.listToJson(profiles))
            .apply()
        val activeId = getActiveLlmProfileId(context)
        if (profiles.none { it.id == activeId }) {
            setActiveLlmProfileId(context, profiles.first().id)
        }
    }

    fun getActiveLlmProfileId(context: Context): String {
        ensureLlmProfilesMigrated(context)
        val profiles = getLlmProfiles(context)
        val stored = prefs(context).getString(KEY_ACTIVE_LLM_PROFILE_ID, null)
        if (stored != null && profiles.any { it.id == stored }) return stored
        val fallback = profiles.firstOrNull()?.id ?: LlmProfile.DEFAULT_PROFILE_ID
        setActiveLlmProfileId(context, fallback)
        return fallback
    }

    fun setActiveLlmProfileId(context: Context, profileId: String) {
        prefs(context).edit().putString(KEY_ACTIVE_LLM_PROFILE_ID, profileId).apply()
    }

    fun getLlmProfile(context: Context, profileId: String): LlmProfile? =
        getLlmProfiles(context).find { it.id == profileId }

    fun getActiveLlmProfile(context: Context): LlmProfile? =
        getLlmProfile(context, getActiveLlmProfileId(context))

    fun getKeepScreenOnWhileRecording(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON_WHILE_RECORDING, false)

    fun setKeepScreenOnWhileRecording(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON_WHILE_RECORDING, value).apply()
    }
}
