package com.dafei.voicenotes.llm

import com.dafei.voicenotes.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String
)

class OpenAiCompatibleClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun polishTranscript(
        config: LlmConfig,
        transcript: String,
        onProgress: ((Float, LlmPhase) -> Unit)? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(transcript.isNotBlank()) { "识别稿为空" }
                fun report(progress: Float, phase: LlmPhase) {
                    onProgress?.invoke(progress, phase)
                }
                report(0.05f, LlmPhase.PREPARING)
                val base = config.baseUrl.trimEnd('/')
                val url = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
                report(0.12f, LlmPhase.CONNECTING)
                val body = JSONObject()
                    .put("model", config.model)
                    .put("temperature", 0.3)
                    .put(
                        "messages",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("role", "system")
                                    .put("content", config.systemPrompt)
                            )
                            .put(
                                JSONObject()
                                    .put("role", "user")
                                    .put("content", transcript)
                            )
                    )
                    .toString()
                val requestBuilder = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                if (config.apiKey.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
                }
                val request = requestBuilder.build()
                report(0.22f, LlmPhase.SENDING)
                report(0.35f, LlmPhase.WAITING)
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("LLM 请求失败 (${response.code}): $text")
                    }
                    report(0.92f, LlmPhase.PARSING)
                    val json = JSONObject(text)
                    json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                }
            }
        }

    suspend fun testConnection(config: LlmConfig): Result<String> =
        polishTranscript(config, "你好，请回复 OK。")
}

fun llmConfigFromPrefs(context: android.content.Context, profileId: String? = null): LlmConfig {
    val id = profileId ?: AppPrefs.getActiveLlmProfileId(context)
    val profile = AppPrefs.getLlmProfile(context, id)
        ?: AppPrefs.getActiveLlmProfile(context)
        ?: LlmProfile.createDefault(id = LlmProfile.DEFAULT_PROFILE_ID, name = "默认")
    return profile.toConfig()
}
