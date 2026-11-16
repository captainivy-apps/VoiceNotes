# 语音记事

一款本地优先的原生 Android 语音记事应用。用户说出记事内容，应用先保存原始录音，再通过本地运行的 whisper.cpp 模型生成「识别稿」，最后可选择使用大语言模型整理成通顺的「润色稿」。

- 包名：`com.dafei.voicenotes`
- 当前版本：`1.0`（versionCode `1`）
- 最低系统：Android 8.0（API 26）
- 目标系统：Android 14（API 34）
- 支持架构：`arm64-v8a`、`armeabi-v7a`

## 核心功能

1. **录音**：16 kHz 单声道 PCM WAV 录音，支持后台前台服务录音与通知栏停止。
2. **导入音频**：可从系统文件选择器导入 WAV、MP3、M4A 等常见格式，自动解码、混音为单声道并重采样到 16 kHz。
3. **本地语音识别**：使用 whisper.cpp 在设备本地离线识别，无需上传音频。
4. **LLM 润色**：通过 OpenAI 兼容接口整理识别稿，消除口语赘词、重复内容与逻辑混乱。
5. **记事管理**：列表浏览、多选删除、详情编辑、试听、下载、复制、上一篇/下一篇跳转。
6. **多 LLM 配置**：可保存多组配置（OpenAI、Ollama、DeepSeek、LM Studio 等），支持默认配置与页面内临时切换。

## 产品概念

| 概念 | 含义 |
|------|------|
| 录音 | 用户语音输入的原始音频文件，统一保存为 WAV |
| 识别稿 | 录音经 ASR 模型识别出的文本，可手动编辑 |
| 润色稿 | 最终作为记事内容的文本，可由识别稿直接复制或经 LLM 加工生成 |
| ASR | Automatic Speech Recognition，自动语音识别 |
| LLM | Large Language Model，大语言模型 |

处理流程：

```text
录音 / 导入音频
      ↓
创建草稿记事（Room）
      ↓
whisper.cpp 本地识别
      ↓
识别稿（可编辑）
      ↓
直接保存 ──────────→ 润色稿
      └─ LLM 润色 ──→ 润色稿
```

## 界面与功能

### 录制页（默认首页）

- 醒目大按钮开始/停止录音，录音中显示时长与实时波形。
- 录音通过 `RecordingService` 前台服务执行，通知栏提供「停止」操作。
- 停止录音后自动创建草稿记事，并弹窗询问是否立即进行语音识别：
  - **立即识别**：调用本地 ASR 模型生成识别稿。
  - **稍后**：仅保存录音，之后可在记事详情页重新发起识别。
- 支持从系统文件选择器导入音频文件。
- 识别完成后展示可编辑的识别稿，提供两个分支：
  - **直接保存为润色稿**：识别稿同时写入识别稿与润色稿字段。
  - **LLM 加工后保存**：先选择 LLM 配置，再流式生成润色稿后保存。
- 识别与润色过程中展示进度、阶段文案与耗时。
- 可选择「开始录制新的记事」重置当前流程。

### 记事列表页

- 按创建时间倒序展示所有记事。
- 每条显示：润色稿首行（无润色稿时回退到识别稿，均为空时显示「新录音」）、创建时间、整篇记事大小（录音 + 识别稿 + 润色稿）。
- 点击进入详情页。
- 支持多选模式批量删除，删除前二次确认。

### 记事详情页

页面包含三个标签页：

| 标签 | 内容与操作 |
|------|------------|
| 录音 | 试听/停止、波形与播放进度、删除录音、下载录音、重新 ASR 识别 |
| 识别稿 | 编辑（自动保存）、删除、下载、复制、直接当作润色稿、LLM 加工 |
| 润色稿 | 编辑（自动保存）、删除、下载、复制 |

其他能力：

- 波形按 30 秒窗口展示，播放时自动跟随并绘制播放头。
- 底部显示创建时间、更新时间（如有）、整篇记事大小。
- 底部提供「上一篇」「下一篇」按钮，按列表顺序直接跳转。
- 右上角可删除整篇记事（同时删除录音文件与数据库记录）。
- 删除录音、识别稿、润色稿或整篇记事均需二次确认。
- 编辑识别稿/润色稿时实时写回 Room 数据库。

### 设置页

设置页分为三个标签：

**录制**

- 内置 ASR 模型列表，支持下载、镜像下载、删除、单选切换当前模型。
- 显示模型大小、下载状态、下载时间与下载进度。
- 「录音时保持屏幕常亮」开关（开启前提示耗电影响）。
- 未下载的模型不可选；删除当前模型后自动回退到其他已下载模型或默认模型。

**LLM 配置**

- 支持添加、编辑、删除多组配置，并单选设为默认配置。
- 每组配置包含：配置名称、Base URL、API Key、Model Name、System Prompt。
- 支持「测试连接」，发送一条测试消息验证接口可用性。
- 至少保留一组配置。
- 录制页与详情页初始选中默认配置，也可临时切换到其他配置。

**帮助**

- 内置应用说明、快速上手步骤、设置建议与常见问题。

## 技术架构

### 技术栈

| 模块 | 实现 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 导航 | Navigation Compose |
| 异步 | Kotlin Coroutines + Flow |
| 本地 ASR | whisper.cpp（JNI / NDK / CMake） |
| 本地存储 | Room 2.6.1 + SharedPreferences + 应用私有目录 |
| 网络 | OkHttp 4.12.0 |
| LLM 接口 | OpenAI 兼容 `/v1/chat/completions`（SSE 流式） |
| 构建 | Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24 / KSP |
| JDK | 17 |

### 工程结构

```text
VoiceNotes/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt          # 编译 whisper.cpp + ggml + JNI
│       │   └── jni.c                   # JNI 桥接与识别进度回调
│       ├── java/com/dafei/voicenotes/
│       │   ├── MainActivity.kt
│       │   ├── VoiceNotesApp.kt        # 应用级依赖容器
│       │   ├── asr/                    # 模型定义、下载、whisper 引擎
│       │   ├── audio/                  # 录音、WAV 读写、音频导入、前台服务
│       │   ├── data/                   # Repository、SharedPreferences、Room
│       │   ├── export/                 # 导出到 Download 目录
│       │   ├── llm/                    # LLM 配置与 OpenAI 兼容客户端
│       │   └── ui/                     # Compose 页面、ViewModel、通用组件
│       └── res/                        # 主题、字符串、图标、网络配置
├── whisper.cpp/                        # 本地克隆，已被 .gitignore 忽略
├── scripts/sync-asr-mirrors.sh         # ASR 模型镜像同步脚本
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

### ASR 实现

- 使用 whisper.cpp 的 C API，通过 JNI 暴露 `initContext`、`fullTranscribe`、`getTextSegment` 等方法。
- 根据设备 ABI 与 CPU 特性动态加载原生库：
  - `arm64-v8a` 且 CPU 支持 FP16：`libwhisper_v8fp16_va.so`
  - `armeabi-v7a` 且 CPU 支持 `vfpv4`：`libwhisper_vfpv4.so`
  - 其他情况：`libwhisper.so`
- 识别语言固定为中文（`zh`），并注入中文标点与断句的 initial prompt。
- 线程数取 CPU 核心数的一半，最低 2 线程。
- 长音频按约 5 分钟窗口切分，窗口间重叠约 1 秒，降低单次内存占用；结果按顺序拼接。
- 识别进度通过 JNI 回调传递到 UI，阶段包括：加载模型、读取音频、识别中。

### LLM 实现

- 使用 OpenAI 兼容的 `POST /v1/chat/completions`。
- Base URL 以 `/v1` 结尾时直接拼接 `/chat/completions`，否则自动补 `/v1/chat/completions`。
- 请求启用 `stream: true`，客户端逐行解析 SSE `data:` 事件并实时更新进度。
- 默认 `temperature = 0.3`；API Key 为空时不发送 `Authorization` 头，便于本地服务。
- 连接超时 30 秒，读/写不设超时，适合长文本生成。
- 默认系统提示词要求：去除口语赘词与重复、修正逻辑顺序、保留原意与情感、不添加未提及内容。
- 多组配置以 JSON 形式保存在 SharedPreferences 中。

### 音频实现

- 录音格式：16 kHz、单声道、PCM 16-bit WAV。
- 录音过程中每 10 秒重写 WAV 头并 `fsync`，异常退出最多损失约 10 秒音频。
- 录音由前台服务持有，应用退到后台仍可继续；通知栏可停止。
- 导入音频使用 `MediaExtractor` + `MediaCodec` 解码，自动下混为单声道，并通过流式线性插值重采样到 16 kHz。
- 详情页使用 `MediaPlayer` 播放，波形通过流式读取 WAV 峰值生成，不一次性加载完整浮点数组。

### 数据存储

| 数据 | 位置 |
|------|------|
| 录音 | 应用私有目录 `files/recordings/rec_<时间戳>.wav` |
| ASR 模型 | 应用私有目录 `files/models/ggml-*.bin` |
| 记事数据 | Room 数据库 `voice_notes.db`，表 `diary_entries` |
| 应用设置 | SharedPreferences `voice_notes_prefs` |
| 导出文件 | 系统 `Download` 目录（Android 10+ 通过 MediaStore） |

`DiaryEntry` 字段：`id`、`createdAt`、`updatedAt`、`audioPath`、`transcriptText`、`diaryText`、`durationMs`。

### 权限

| 权限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 录音 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | 前台录音服务 |
| `POST_NOTIFICATIONS` | Android 13+ 录音通知 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 下载模型、调用 LLM |
| `WRITE_EXTERNAL_STORAGE`（仅 API ≤ 28） | 旧系统导出文件 |

应用允许明文 HTTP 流量并信任用户证书，便于访问局域网 LLM 服务（如 `http://192.168.x.x:11434`）。

## 环境要求

- Android Studio Ladybug 或更高版本
- JDK 17
- Android SDK 34
- Android NDK 与 CMake（通过 SDK Manager 安装）
- 真机或 ARM64 模拟器

> 注意：应用只编译 `arm64-v8a` 与 `armeabi-v7a`，x86/x86_64 模拟器无法运行。

## 构建与运行

### 1. 准备 whisper.cpp

工程通过 CMake 直接编译根目录下的 `whisper.cpp` 源码，该目录已被 `.gitignore` 忽略，首次构建前需克隆：

```bash
git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git whisper.cpp
```

### 2. 配置 SDK 路径

Android Studio 通常会生成 `local.properties`，也可手动创建：

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

### 3. 构建 Debug APK

```bash
chmod +x gradlew
./gradlew assembleDebug
```

输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### 4. 构建 Release APK

```bash
./gradlew assembleRelease
```

输出路径：`app/build/outputs/apk/release/app-release.apk`

> 仓库中的 `app/release/` 目录存放的是历史构建产物，不代表当前源码的构建结果。

## 首次使用

1. 安装 APK，打开应用进入「设置 → 录制」。
2. 下载至少一个 ASR 模型，建议先下载 **Base**（约 142 MB）。
3. 在模型卡片上点击单选按钮，设为当前使用模型。
4. 如需 LLM 润色，进入「设置 → LLM 配置」添加配置并测试连接，再设为默认。
5. 回到「录制」页开始录音，或导入已有音频。
6. 录音结束后选择「立即识别」，得到识别稿后直接保存或使用 LLM 加工。

## 内置 ASR 模型

| 模型 ID | 显示名称 | 大小 | 说明 |
|---------|----------|------|------|
| `tiny` | Tiny | 75 MB | 最快，精度较低 |
| `base` | Base | 142 MB | 默认推荐 |
| `small` | Small | 466 MB | 更高精度 |
| `medium` | Medium | 1.5 GB | 高精度，推理较慢 |
| `large-v3-turbo-q5_0` | Large v3 Turbo Q5 | 547 MB | 量化大模型，速度与精度平衡 |

- 默认从 HuggingFace `ggerganov/whisper.cpp` 下载到应用私有目录。
- `base` 与 `large-v3-turbo-q5_0` 额外提供镜像下载地址，可在网络受限时使用「镜像下载」。
- 中文标点与断句质量与模型规模相关，建议使用 Small 及以上；完整排版可依赖 LLM 润色。

### 同步镜像（维护者）

`scripts/sync-asr-mirrors.sh` 用于将 `ggml-base.bin` 与 `ggml-large-v3-turbo-q5_0.bin` 从 HuggingFace 下载并上传到镜像服务器，随后校验远端文件大小：

```bash
./scripts/sync-asr-mirrors.sh
```

## LLM 配置示例

| 场景 | Base URL | API Key | Model |
|------|----------|---------|-------|
| OpenAI 云端 | `https://api.openai.com` | 必填 | `gpt-4o-mini` |
| Ollama（局域网） | `http://192.168.x.x:11434` | 可留空 | `qwen2.5:7b` |
| Ollama（模拟器宿主机） | `http://10.0.2.2:11434` | 可留空 | `qwen2.5:7b` |
| DeepSeek | `https://api.deepseek.com` | 必填 | `deepseek-chat` |
| LM Studio | `http://192.168.x.x:1234` | 可留空 | 本地模型名 |

说明：

- 配置保存前建议先点「测试连接」。
- 服务端需支持流式（SSE）返回。
- API Key 以明文保存在应用 SharedPreferences 中，请勿在共享设备上保存敏感密钥。

## 导出文件

导出文件写入系统 `Download` 目录，命名规则：

| 类型 | 文件名格式 |
|------|------------|
| 录音 | `voicenotes-record-yyyyMMdd-HHmmss.wav` |
| 识别稿 | `voicenotes-first-yyyyMMdd-HHmmss.txt` |
| 润色稿 | `voicenotes-final-yyyyMMdd-HHmmss.txt` |

Android 10 及以上使用 MediaStore 写入，无需存储权限；Android 9 及以下使用公共下载目录，需要在系统设置中授予存储权限（应用仅声明权限，未实现运行时申请）。

## 已知限制

- 仅支持 ARM 架构，不支持 x86/x86_64 模拟器。
- ASR 识别语言固定为中文。
- ASR 与 LLM 任务运行在 ViewModel 协程中，未实现独立后台任务；退出相关页面或应用进程被回收时任务可能中断。
- 录音为未压缩 WAV，体积约为 1.9 MB/分钟，长时间录音会占用较多空间。
- 记录数据未加密；应用允许系统备份（`allowBackup=true`）。
- 项目暂未配置自动化测试，功能验证以手动测试为主。

## 许可证

本项目使用 GNU General Public License v3.0，详见 [LICENSE](LICENSE)。whisper.cpp 遵循其上游许可证。
