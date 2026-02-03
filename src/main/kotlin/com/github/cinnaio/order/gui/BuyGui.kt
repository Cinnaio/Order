package com.github.cinnaio.order.gui

import com.github.cinnaio.order.Order
import com.github.cinnaio.order.model.MarketOverviewItem
import com.github.cinnaio.order.service.BuyResult
import com.github.cinnaio.order.service.OrderService
import com.github.cinnaio.order.utils.ItemSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.math.BigDecimal

class BuyGui(
    player: Player,
    private val service: OrderService,
    private val marketItem: MarketOverviewItem,
    private val previousGui: GuiBase? = null
) : GuiBase(
    player, 
    54, 
    JavaPlugin.getPlugin(Order::class.java).configManager.getMessage("gui-buy-title", mapOf(), false)
) {

    private var quantity = 1

    override fun render() {
        guiInventory.clear()
        val plugin = JavaPlugin.getPlugin(Order::class.java)
        val config = plugin.configManager

        // 渲染背景 (灰色玻璃板)
        val borderItem = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val borderMeta = borderItem.itemMeta
        borderMeta?.setDisplayName(" ")
        borderItem.itemMeta = borderMeta

        val borderSlots = listOf(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 17, 18, 26, 27, 35, 36, 44,
            45, 46, 47, 48, 50, 51, 52, 53
        )
        
        borderSlots.forEach { slot ->
            guiInventory.setItem(slot, borderItem)
        }

        // 1. 展示商品信息 (Slot 13)
        val displayItem = ItemSerializer.deserialize(marketItem.serializedData)
        val meta = displayItem.itemMeta
        val lore = meta?.lore ?: mutableListOf()
        lore.add("")
        lore.add(config.getMessage("gui-buy-min-price", mapOf("price" to marketItem.minPrice.toString()), false))
        lore.add(config.getMessage("gui-buy-total-stock", mapOf("stock" to marketItem.totalStock.toString()), false))
        meta?.lore = lore
        displayItem.itemMeta = meta
        displayItem.amount = 1 // 仅展示用
        guiInventory.setItem(13, displayItem)

        // 2. 数量控制区 (Row 3, Slots 19-25)
        // -64
        guiInventory.setItem(19, createButton(
            Material.RED_STAINED_GLASS_PANE, 
            config.getMessage("gui-buy-decrease", mapOf("amount" to "64"), false), 
            listOf(config.getMessage("gui-buy-decrease-lore", mapOf("amount" to "64"), false))
        ))
        // -16
        guiInventory.setItem(20, createButton(
            Material.RED_STAINED_GLASS_PANE, 
            config.getMessage("gui-buy-decrease", mapOf("amount" to "16"), false), 
            listOf(config.getMessage("gui-buy-decrease-lore", mapOf("amount" to "16"), false))
        ))
        // -1
        guiInventory.setItem(21, createButton(
            Material.RED_STAINED_GLASS_PANE, 
            config.getMessage("gui-buy-decrease", mapOf("amount" to "1"), false), 
            listOf(config.getMessage("gui-buy-decrease-lore", mapOf("amount" to "1"), false))
        ))
        
        // 中间显示当前数量 (Slot 22)
        val estCost = marketItem.minPrice.multiply(BigDecimal(quantity))
        guiInventory.setItem(22, createButton(
            Material.PAPER, 
            config.getMessage("gui-buy-quantity-btn", mapOf("quantity" to quantity.toString()), false), 
            listOf(config.getMessage("gui-buy-est-cost", mapOf("cost" to estCost.toString()), false))
        ))

        // +1
        guiInventory.setItem(23, createButton(
            Material.LIME_STAINED_GLASS_PANE, 
            config.getMessage("gui-buy-increase", mapOf("amount" to "1"), false), 
            listOf(config.getMessage("gui-buy-increase-lore", mapOf("amount" to "1"), false))
        ))
        // +16
        guiInventory.setItem(24, createButton(
            Material.LIME_STAINED_GLASS_PANE, 
            config.getMessage("gui-buy-increase", mapOf("amount" to "16"), false), 
            listOf(config.getMessage("gui-buy-increase-lore", mapOf("amount" to "16"), false))
        ))
        // +64
        guiInventory.setItem(25, createButton(
            Material.LIME_STAINED_GLASS_PANE, 
            config.getMessage("gui-buy-increase", mapOf("amount" to "64"), false), 
            listOf(config.getMessage("gui-buy-increase-lore", mapOf("amount" to "64"), false))
        ))

        // 3. 购买按钮 (Slot 31)
        val confirmLore = listOf(
            "",
            config.getMessage("gui-buy-confirm-lore-quantity", mapOf("quantity" to quantity.toString()), false),
            config.getMessage("gui-buy-confirm-lore-cost", mapOf("cost" to estCost.toString()), false),
            "",
            config.getMessage("gui-buy-confirm-lore-action", mapOf(), false)
        )
        guiInventory.setItem(31, createButton(
            Material.EMERALD_BLOCK, 
            config.getMessage("gui-buy-confirm", mapOf(), false), 
            confirmLore
        ))
        
        // 全部数量 (Slot 30)
        guiInventory.setItem(30, createButton(
            Material.DIAMOND_BLOCK,
            config.getMessage("gui-buy-all", mapOf(), false),
            listOf(config.getMessage("gui-buy-all-lore", mapOf("stock" to marketItem.totalStock.toString()), false))
        ))

        // 4. 返回按钮 (Slot 49)
        guiInventory.setItem(49, createButton(
            Material.HONEY_BLOCK, 
            config.getMessage("gui-buy-back", mapOf(), false), 
            listOf(config.getMessage("gui-buy-back-lore", mapOf(), false))
        ))
    }

    private fun createButton(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        meta?.lore = lore
        item.itemMeta = meta
        return item
    }

    override fun onClick(event: InventoryClickEvent) {
        event.isCancelled = true
        val slot = event.rawSlot

        when (slot) {
            19 -> updateQuantity(-64)
            20 -> updateQuantity(-16)
            21 -> updateQuantity(-1)
            23 -> updateQuantity(1)
            24 -> updateQuantity(16)
            25 -> updateQuantity(64)
            30 -> { quantity = marketItem.totalStock; render() }
            31 -> confirmBuy()
            49 -> previousGui?.open() ?: player.closeInventory()
        }
    }

    private fun updateQuantity(delta: Int) {
        val newQuantity = quantity + delta
        if (newQuantity in 1..marketItem.totalStock) {
            quantity = newQuantity
            render() // 重新渲染更新显示
        } else if (newQuantity > marketItem.totalStock) {
            quantity = marketItem.totalStock
            render()
        } else if (newQuantity < 1) {
            quantity = 1
            render()
        }
    }

    private fun confirmBuy() {
        player.closeInventory()
        val plugin = JavaPlugin.getPlugin(Order::class.java)
        val config = plugin.configManager
        
        player.sendMessage(config.getMessage("buy-processing"))
        
        service.buyItemAsync(player, marketItem.itemHash, quantity).thenAccept { result ->
            player.scheduler.run(plugin, { _ -> 
                if (result.success) {
                    player.sendMessage(config.getMessage("buy-success-chat"))
                    player.sendMessage(config.getMessage("buy-bought-chat", mapOf("amount" to result.amountBought.toString())))
                    player.sendMessage(config.getMessage("buy-cost-chat", mapOf("cost" to result.totalCost.toString())))
                } else {
                    player.sendMessage(config.getMessage("buy-fail-chat", mapOf("message" to (result.message ?: "Unknown"))))
                }
            }, null)
        }
    }
}
