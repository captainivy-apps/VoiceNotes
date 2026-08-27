# 语音记事

## 核心功能
用户语音讲述记事内容，通过asr模型进行语音识别转为文本。最终用大语言模型整理文本，消除口语化痕迹，祛除重复内容，纠正逻辑混乱的地方。将高质量文本保存为记事。


## 产品设计
### 概念
ASR：Automatic Speech Recognition，自动语音识别模型，将人类语音信号转换为文本
录音：用户语音输入的原始音频文件
识别稿：原始音频文件通过asr模型识别出的文本稿
润色稿：最终当作记事内容的文本稿
LLM：大语言模型

### 录制页【默认用户首页】
录制界面，有醒目大按钮开始录音。录制中显示录音时长、显示音频波形。
停止录音后，先询问用户是否立即开始语音识别。用户确认后，asr模型开始识别语音，得到“识别稿”，并展示给用户。用户也可选择稍后识别。
对于“识别稿”，用户有两个处理分支：
1. 可以选择使用LLM，对识别稿进行进一步加工，再保存为最终“润色稿”
2. 直接保存为“润色稿”。

### 记事列表页
展示用户记事列表，默认按时间倒叙排列，可点击list item进入记事详情页。
允许用户选中记事并删除记事，可以复选。

### 记事详情页
记事详情展示和编辑页
试听录音，查看识别稿和润色稿。识别稿和润色稿的文本框可以编辑。

提供三个操作按钮：0. 用asr模型处理语音为识别稿 1. 将识别稿保存为润色稿 2. 通过LLM加工识别稿成润色稿。
用户可以选择“分别”删除录音、识别稿和润色稿。也可以删除整个记事，这个操作将删除该记事的所有内容。由于删除操作不可逆，删除时需要用户确认。
用户可以选择下载“录音”，“识别稿”和“润色稿”到手机默认下载目录
底部提供“上一篇”和“下一篇”按钮，直接进入上一篇和下一篇记事详情页

### 设置界面
* 提供下拉框内置几个开源ASR模型选项，用户直接下载到本地
* 用户选择当前使用哪个ASR模型
* 填写LLM相关配置，用于处理识别稿文本，支持open ai兼容接口规范

## 技术规范
* 原生安卓app
* 使用开源语音识别大模型，用户可以下载到本地
* 录音文件，记事文本都保存在手机本地存储
* 开源ASR模型：https://github.com/openai/whisper/blob/main/model-card.md

## Android 工程

本目录包含完整的原生 Android 应用（Kotlin + Jetpack Compose）。

### 技术栈

| 模块 | 实现 |
|------|------|
| UI | Jetpack Compose + Material3 |
| 本地 ASR | whisper.cpp（JNI/NDK） |
| 本地存储 | Room + 应用私有目录 |
| LLM | OpenAI 兼容 `/v1/chat/completions` |
| 包名 | `com.dafei.voicenotes` |

### 环境要求

* Android Studio Ladybug 或更高版本
* JDK 17+
* Android SDK 34
* Android NDK（CMake 构建 whisper.cpp）
* 真机或模拟器（`arm64-v8a` / `armeabi-v7a`）

### 首次构建

```bash
cd llm/voice-dairy

# 若 whisper.cpp 目录不存在，需先克隆
git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git whisper.cpp

# 配置 Android SDK 路径（Android Studio 通常会自动生成）
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

chmod +x gradlew
./gradlew assembleDebug
```

Debug APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

Release 构建：

```bash
./gradlew assembleRelease
```

### 首次使用

1. 安装 APK 后，进入 **设置** 页
2. 下载至少一个 ASR 模型（建议先下载 **Base**，约 142 MB）
3. 选择当前使用的模型
4. 如需 LLM 润色，在 **LLM 配置** 页添加一组或多组配置（如 OpenAI、Ollama、DeepSeek），设好默认后保存；录制与详情页可临时切换。示例：
   * **云端 OpenAI**：Base URL `https://api.openai.com`，填写 API Key，Model `gpt-4o-mini`
   * **本地/局域网 LLM**（Ollama、LM Studio 等）：Base URL 填局域网地址，API Key 可留空
     * 真机访问电脑：`http://192.168.x.x:11434`（Ollama 示例）
     * 模拟器访问宿主机：`http://10.0.2.2:11434`
     * Model 填本地模型名，如 `qwen2.5:7b`
5. 返回 **录制** 页开始录音

### 内置 ASR 模型

| 模型 ID | 大小 | 说明 |
|---------|------|------|
| tiny | 75 MB | 最快，精度较低 |
| base | 142 MB | 推荐默认 |
| small | 466 MB | 更高精度 |
| medium | 1.5 GB | 高精度，推理较慢 |
| large-v3-turbo-q5_0 | 547 MB | 量化大模型 |

模型从 HuggingFace `ggerganov/whisper.cpp` 按需下载，保存在应用私有目录。

### 数据存储

* 录音：`files/recordings/*.wav`（16kHz mono PCM）
* 记事元数据与文本：Room 数据库 `voice_notes.db`
* ASR 模型：`files/models/ggml-*.bin`
* 导出文件：系统 **Download** 目录
