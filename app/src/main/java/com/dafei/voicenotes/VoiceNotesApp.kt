package com.dafei.voicenotes

import android.app.Application
import com.dafei.voicenotes.asr.ModelDownloader
import com.dafei.voicenotes.asr.WhisperEngine
import com.dafei.voicenotes.data.DiaryRepository
import com.dafei.voicenotes.data.local.DiaryDatabase
import com.dafei.voicenotes.llm.OpenAiCompatibleClient

class VoiceNotesApp : Application() {
    lateinit var repository: DiaryRepository
        private set
    lateinit var modelDownloader: ModelDownloader
        private set
    lateinit var whisperEngine: WhisperEngine
        private set
    lateinit var llmClient: OpenAiCompatibleClient
        private set

    override fun onCreate() {
        super.onCreate()
        val dao = DiaryDatabase.get(this).diaryDao()
        repository = DiaryRepository(this, dao)
        modelDownloader = ModelDownloader(this)
        whisperEngine = WhisperEngine(modelDownloader)
        llmClient = OpenAiCompatibleClient()
    }
}
