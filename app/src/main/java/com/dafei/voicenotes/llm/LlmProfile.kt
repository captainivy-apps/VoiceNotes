package com.dafei.voicenotes.llm

import com.dafei.voicenotes.data.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LlmProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String
) {
    fun toConfig(): LlmConfig = LlmConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        systemPrompt = systemPrompt
    )

    fun summary(): String = "$name · $model"

    companion object {
        const val DEFAULT_PROFILE_ID = "default"

        fun createDefault(
            id: String = UUID.randomUUID().toString(),
            name: String = "新配置"
        ): LlmProfile = LlmProfile(
            id = id,
            name = name,
            baseUrl = AppPrefs.DEFAULT_LLM_BASE_URL,
            apiKey = "",
            model = AppPrefs.DEFAULT_LLM_MODEL,
            systemPrompt = AppPrefs.DEFAULT_LLM_SYSTEM_PROMPT
        )

        fun fromJson(obj: JSONObject): LlmProfile = LlmProfile(
            id = obj.getString("id"),
            name = obj.optString("name", "未命名"),
            baseUrl = obj.optString("baseUrl", AppPrefs.DEFAULT_LLM_BASE_URL),
            apiKey = obj.optString("apiKey", ""),
            model = obj.optString("model", AppPrefs.DEFAULT_LLM_MODEL),
            systemPrompt = obj.optString("systemPrompt", AppPrefs.DEFAULT_LLM_SYSTEM_PROMPT)
        )

        fun listFromJson(json: String): List<LlmProfile> {
            if (json.isBlank()) return emptyList()
            val arr = JSONArray(json)
            return buildList {
                for (i in 0 until arr.length()) {
                    add(fromJson(arr.getJSONObject(i)))
                }
            }
        }

        fun listToJson(profiles: List<LlmProfile>): String {
            val arr = JSONArray()
            profiles.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("systemPrompt", systemPrompt)
}
