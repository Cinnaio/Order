package com.github.cinnaio.order.config

import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.java.JavaPlugin

class EconomyManager(private val plugin: JavaPlugin) {

    private var economy: Economy? = null

    init {
        setupEconomy()
    }

    private fun setupEconomy(): Boolean {
        if (plugin.server.pluginManager.getPlugin("Vault") == null) {
            return false
        }
        val rsp: RegisteredServiceProvider<Economy>? = plugin.server.servicesManager.getRegistration(Economy::class.java)
        if (rsp == null) {
            return false
        }
        economy = rsp.provider
        return economy != null
    }

    fun has(playerUuid: java.util.UUID, amount: Double): Boolean {
        return economy?.has(plugin.server.getOfflinePlayer(playerUuid), amount) ?: true // 如果没有 Vault，默认通过（演示模式）
    }

    fun withdraw(playerUuid: java.util.UUID, amount: Double): Boolean {
        if (economy == null) return true
        return economy!!.withdrawPlayer(plugin.server.getOfflinePlayer(playerUuid), amount).transactionSuccess()
    }

    fun deposit(playerUuid: java.util.UUID, amount: Double): Boolean {
        if (economy == null) return true
        return economy!!.depositPlayer(plugin.server.getOfflinePlayer(playerUuid), amount).transactionSuccess()
    }

    fun format(amount: Double): String {
        return economy?.format(amount) ?: "$$amount"
    }
}
