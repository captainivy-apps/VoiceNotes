package com.dafei.voicenotes.asr

data class AsrModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeLabel: String,
    val downloadUrl: String,
    val mirrorDownloadUrl: String? = null
)

object AsrModels {
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    private const val MIRROR_BASE_URL = "https://www.galaxyrover.com/mirrors"

    val ALL = listOf(
        AsrModelInfo("tiny", "Tiny", "ggml-tiny.bin", "75 MB", "$BASE_URL/ggml-tiny.bin"),
        AsrModelInfo(
            "base",
            "Base",
            "ggml-base.bin",
            "142 MB",
            "$BASE_URL/ggml-base.bin",
            "$MIRROR_BASE_URL/ggml-base.bin"
        ),
        AsrModelInfo("small", "Small", "ggml-small.bin", "466 MB", "$BASE_URL/ggml-small.bin"),
        AsrModelInfo("medium", "Medium", "ggml-medium.bin", "1.5 GB", "$BASE_URL/ggml-medium.bin"),
        AsrModelInfo(
            "large-v3-turbo-q5_0",
            "Large v3 Turbo Q5",
            "ggml-large-v3-turbo-q5_0.bin",
            "547 MB",
            "$BASE_URL/ggml-large-v3-turbo-q5_0.bin",
            "$MIRROR_BASE_URL/ggml-large-v3-turbo-q5_0.bin"
        )
    )

    fun find(id: String): AsrModelInfo? = ALL.find { it.id == id }
}
