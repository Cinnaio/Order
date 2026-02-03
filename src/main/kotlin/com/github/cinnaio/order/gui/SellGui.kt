package com.github.cinnaio.order.gui

import com.github.cinnaio.order.Order
import com.github.cinnaio.order.service.OrderService
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.math.BigDecimal

class SellGui(
    player: Player,
    private val service: OrderService
) : GuiBase(player, 27, JavaPlugin.getPlugin(Order::class.java).configManager.getMessage("gui-sell-title", withPrefix = false)) {

    private val SELL_SLOT = 13
    private val CONFIRM_SLOT = 22
    
    // 暂存当前正在上架的物品
    private var pendingItem: ItemStack? = null
    private var price: BigDecimal = BigDecimal.TEN // 默认价格
    
    init {
        render()
    }

    override fun render() {
        val plugin = JavaPlugin.getPlugin(Order::class.java)
        val config = plugin.configManager

        inventory.clear()
        
        // 渲染背景
        val glass = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val meta = glass.itemMeta
        meta.setDisplayName(" ")
        glass.itemMeta = meta
        
        for (i in 0 until size) {
            inventory.setItem(i, glass)
        }
        
        // 渲染上架槽
        val item = pendingItem
        if (item == null) {
            val slotItem = ItemStack(Material.STONE_BUTTON)
            val slotMeta = slotItem.itemMeta
            slotMeta.setDisplayName(config.getMessage("gui-sell-slot-empty", withPrefix = false))
            slotItem.itemMeta = slotMeta
            inventory.setItem(SELL_SLOT, slotItem)
        } else {
            // 显示正在上架的物品
            val displayItem = item.clone()
            val meta = displayItem.itemMeta
            val lore = meta.lore ?: mutableListOf()
            lore.add(config.getMessage("gui-sell-separator", withPrefix = false))
            lore.add(config.getMessage("gui-sell-price", mapOf("price" to price.toString()), false))
            lore.add(config.getMessage("gui-sell-action", withPrefix = false)) // 简化的调价方式
            meta.lore = lore
            displayItem.itemMeta = meta
            inventory.setItem(SELL_SLOT, displayItem)
            
            // 确认按钮
            val confirm = ItemStack(Material.LIME_WOOL)
            val cm = confirm.itemMeta
            cm.setDisplayName(config.getMessage("gui-sell-confirm", withPrefix = false))
            confirm.itemMeta = cm
            inventory.setItem(CONFIRM_SLOT, confirm)
        }
    }

    override fun onClick(event: InventoryClickEvent) {
        val plugin = JavaPlugin.getPlugin(Order::class.java)
        val config = plugin.configManager
        
        event.isCancelled = true // 默认全锁
        
        val slot = event.rawSlot
        
        // 处理上架槽逻辑
        if (slot == SELL_SLOT) {
            val cursor = event.cursor
            
            // 如果槽位为空，允许放入
            if (pendingItem == null) {
                if (cursor != null && cursor.type != Material.AIR) {
                    pendingItem = cursor.clone()
                    player.setItemOnCursor(ItemStack(Material.AIR)) // 清空光标
                    render()
                    player.sendMessage(config.getMessage("gui-sell-msg-put"))
                }
            } else {
                // 如果槽位有物品，处理点击调价或取回
                if (cursor == null || cursor.type == Material.AIR) {
                    // 点击调价
                    if (event.isLeftClick) {
                        price = price.add(BigDecimal.TEN)
                    } else if (event.isRightClick) {
                        price = price.subtract(BigDecimal.TEN).max(BigDecimal.ONE)
                    } else if (event.click.isShiftClick) {
                        // Shift点击取回
                        val itemToReturn = pendingItem
                        if (itemToReturn != null) {
                            player.inventory.addItem(itemToReturn)
                            pendingItem = null
                        }
                    }
                    render()
                }
            }
        }
        
        // 处理确认按钮
        val itemToSell = pendingItem
        if (slot == CONFIRM_SLOT && itemToSell != null) {
            player.closeInventory() // 先关闭 GUI
            player.sendMessage("§e正在处理订单...")
            
            service.createSellOrderAsync(player, itemToSell, price, itemToSell.amount).thenAccept { success ->
                // 回调可能在异步线程，需要调度回主线程发送消息（虽然 sendMessage 线程安全，但为了保险起见）
                // 在 Folia 中，sendMessage 是线程安全的。
                if (success) {
                    player.sendMessage("§a上架成功！")
                    pendingItem = null // 物品已由 Service 接管（所有权转移）
                } else {
                    player.sendMessage("§c上架失败，请重试。")
                    // 失败需要还给玩家，这里需要调度回 Region Thread
                     org.bukkit.plugin.java.JavaPlugin.getPlugin(com.github.cinnaio.order.Order::class.java).server.regionScheduler.execute(org.bukkit.plugin.java.JavaPlugin.getPlugin(com.github.cinnaio.order.Order::class.java), player.location) {
                          player.inventory.addItem(itemToSell)
                     }
                }
            }
            
            // 为了防止 onClose 误判退回物品，先清空 pendingItem?
            // 不，如果在异步完成前 pendingItem 仍存在，onClose 会退回。
            // 这是一个并发问题。
            // 策略：点击确认后，立即清空 pendingItem，但如果失败，在回调中加回去。
            pendingItem = null 
        }
        
        // 允许点击自己背包 (可选，如果允许从背包整理)
        if (event.clickedInventory == player.inventory) {
            event.isCancelled = false
        }
    }
    
    override fun onClose(event: org.bukkit.event.inventory.InventoryCloseEvent) {
        // 关闭时归还未上架的物品
        val itemToReturn = pendingItem
        if (itemToReturn != null) {
            player.inventory.addItem(itemToReturn)
            pendingItem = null
            player.sendMessage("§e未上架物品已退回背包。")
        }
    }
}
