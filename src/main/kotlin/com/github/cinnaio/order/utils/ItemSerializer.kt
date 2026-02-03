package com.github.cinnaio.order.utils

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

object ItemSerializer {

    /**
     * 将 ItemStack 序列化为 Base64 字符串
     * 这种方式可以完整保留 NBT, Enchants, CustomModelData, PDC 等所有 Bukkit 支持的属性
     */
    fun serialize(item: ItemStack): String {
        val yaml = YamlConfiguration()
        yaml.set("i", item)
        val stringData = yaml.saveToString()
        return Base64.getEncoder().encodeToString(stringData.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 将 Base64 字符串反序列化为 ItemStack
     */
    fun deserialize(base64: String): ItemStack {
        val stringData = String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)
        val yaml = YamlConfiguration()
        yaml.loadFromString(stringData)
        return yaml.getItemStack("i") ?: throw IllegalArgumentException("Invalid item data")
    }

    /**
     * 计算物品的唯一 Hash (基于序列化数据)
     * 同属性、同 NBT 的物品 Hash 一致
     */
    fun computeHash(serializedData: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(serializedData.toByteArray(StandardCharsets.UTF_8))
        return bytesToHex(hashBytes)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexString = StringBuilder()
        for (b in bytes) {
            val hex = Integer.toHexString(0xff and b.toInt())
            if (hex.length == 1) hexString.append('0')
            hexString.append(hex)
        }
        return hexString.toString()
    }
}
