package com.example.aiapkanalyzer

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class McpConfig(
    val transport: String = "http",
    val url: String = "",
    val command: List<String> = emptyList(),
    val enabled: Boolean = true
)

@Serializable
data class AgentConfig(
    val max_iterations: Int = 25,
    val temperature: Float = 0.2f
)

@Serializable
data class FloatingBallConfig(
    val sizeDp: Int = 60,
    val normalAlpha: Float = 1.0f,
    val hiddenAlpha: Float = 0.3f,
    val hiddenRatio: Float = 0.67f,
    val iconIndex: Int = 0,
    val customIconPath: String = ""
)

@Serializable
data class AppConfig(
    val llm: LlmConfig,
    val mcp: McpConfig,
    val agent: AgentConfig = AgentConfig(),
    val workDirUri: String = "",
    val floatingEnabled: Boolean = false,
    val floatingBall: FloatingBallConfig = FloatingBallConfig()
) {
    companion object {
        // 单例 Json：load/save 共用同一份配置；
        // prettyPrint=true 方便人工查看 filesDir/config.json；
        // encodeDefaults=true 把默认值字段也写出，保持与 assets/config.json 一致的显式风格。
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun load(context: Context): AppConfig {
            // 优先从应用私有目录读 config.json，不存在则从 assets 读默认配置
            val file = File(context.filesDir, "config.json")
            val text = if (file.exists()) file.readText()
            else context.assets.open("config.json").bufferedReader().use { it.readText() }
            return json.decodeFromString(serializer(), text)
        }

        /** 把配置序列化写入 filesDir/config.json（prettyPrint，方便人工查看）。 */
        fun save(context: Context, config: AppConfig) {
            val file = File(context.filesDir, "config.json")
            file.writeText(json.encodeToString(serializer(), config))
        }

        /** 便捷更新：读当前配置 → block 修改（copy） → save。返回更新后的配置。 */
        fun update(context: Context, block: AppConfig.() -> AppConfig): AppConfig {
            val current = load(context)
            val updated = current.block()
            save(context, updated)
            return updated
        }
    }
}
