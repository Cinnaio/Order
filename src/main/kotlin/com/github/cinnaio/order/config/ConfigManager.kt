package com.github.cinnaio.order.config

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.regex.Pattern

class ConfigManager(private val plugin: JavaPlugin) {

    private lateinit var messagesConfig: YamlConfiguration
    private val messagesFile = File(plugin.dataFolder, "messages.yml")

    init {
        loadConfig()
        loadMessages()
    }

    fun loadConfig() {
        plugin.saveDefaultConfig()
        plugin.reloadConfig()
    }

    fun loadMessages() {
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false)
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile)
    }

    fun getMessage(key: String, placeholders: Map<String, String> = emptyMap(), withPrefix: Boolean = true): String {
        val rawMessage = messagesConfig.getString(key) ?: return "§cMissing message: $key"
        val prefix = messagesConfig.getString("prefix") ?: ""
        
        var message = rawMessage.replace("{prefix}", prefix)
        
        // Add prefix if not already there (optional, but good for consistency)
        if (withPrefix && !message.contains(prefix) && key != "prefix") {
             message = prefix + message
        }

        placeholders.forEach { (k, v) ->
            message = message.replace("{$k}", v)
        }
        
        return parseColors(message)
    }

    private fun parseColors(text: String): String {
        var processed = text.replace("§", "&")
        
        // Support Legacy Hex format (converted from §x§r§r§g§g§b§b -> &x&r&r&g&g&b&b)
        processed = processed.replace(Regex("&x&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])&([0-9a-fA-F])"), "<color:#$1$2$3$4$5$6>")
        
        // Support Hex format: &#RRGGBB
        processed = processed.replace(Regex("&#([0-9a-fA-F]{6})"), "<color:#$1>")
        // Support Hex format: {#RRGGBB}
        processed = processed.replace(Regex("\\{#([0-9a-fA-F]{6})\\}"), "<color:#$1>")

        // Support Legacy format: &c -> <red>, etc.
        // We replace them with MiniMessage tags to handle everything uniformly
        processed = processed
            .replace("&0", "<black>")
            .replace("&1", "<dark_blue>")
            .replace("&2", "<dark_green>")
            .replace("&3", "<dark_aqua>")
            .replace("&4", "<dark_red>")
            .replace("&5", "<dark_purple>")
            .replace("&6", "<gold>")
            .replace("&7", "<gray>")
            .replace("&8", "<dark_gray>")
            .replace("&9", "<blue>")
            .replace("&a", "<green>")
            .replace("&b", "<aqua>")
            .replace("&c", "<red>")
            .replace("&d", "<light_purple>")
            .replace("&e", "<yellow>")
            .replace("&f", "<white>")
            .replace("&k", "<obfuscated>")
            .replace("&l", "<bold>")
            .replace("&m", "<strikethrough>")
            .replace("&n", "<underlined>")
            .replace("&o", "<italic>")
            .replace("&r", "<reset>")

        // Deserialize with MiniMessage
        val component = MiniMessage.miniMessage().deserialize(processed)
        
        // Serialize back to Legacy Section (using §) for Bukkit compatibility
        // hexColors() enables support for BungeeCord hex format (e.g. §x§f§f...)
        return LegacyComponentSerializer.builder()
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build()
            .serialize(component)
    }
    
    // Helper to get raw string from config
    fun getString(path: String): String? {
        return plugin.config.getString(path)
    }
    
    fun getInt(path: String): Int {
        return plugin.config.getInt(path)
    }

    fun getDouble(path: String): Double {
        return plugin.config.getDouble(path)
    }

    fun getStringList(path: String): List<String> {
        return plugin.config.getStringList(path)
    }
    
    // Expose config and messagesConfig (or add save methods)
    // Actually, we can just expose the FileConfiguration object via getter if needed,
    // or provide methods. The calling code used `configManager.config.set(...)` which assumes `config` property exists.
    // But here `plugin` is private and `messagesConfig` is private.
    // The previous code in Order.kt accessed `configManager.config` which is NOT valid if I don't expose it.
    // Let's add a getter for the main config.
    val config: YamlConfiguration
        get() = plugin.config as YamlConfiguration
        
    val messagesConfigPublic: YamlConfiguration
        get() = messagesConfig

    fun saveConfig() {
        plugin.saveConfig()
    }
}
