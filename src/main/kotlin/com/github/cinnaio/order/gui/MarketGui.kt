package com.github.cinnaio.order.gui

import com.github.cinnaio.order.Order
import com.github.cinnaio.order.model.MarketOverviewItem
import com.github.cinnaio.order.service.OrderService
import com.github.cinnaio.order.utils.ItemSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

class MarketGui(
    player: Player,
    private val service: OrderService,
    private val page: Int = 1
) : GuiBase(
    player, 
    54, 
    JavaPlugin.getPlugin(Order::class.java).configManager.getMessage("gui-market-title", mapOf("page" to page.toString()), false)
) {

    private val items = ConcurrentHashMap<Int, MarketOverviewItem>()

    override fun render() {
        val plugin = JavaPlugin.getPlugin(Order::class.java)
        val config = plugin.configManager
        
        guiInventory.clear()
        
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
        
        // 渲染加载中
        val loading = ItemStack(Material.CLOCK)
        val meta = loading.itemMeta
        meta?.setDisplayName(config.getMessage("gui-loading", withPrefix = false))
        loading.itemMeta = meta
        guiInventory.setItem(22, loading)

        // 异步加载数据
        service.getMarketOverviewAsync(page).thenAccept { list ->
            // 回调到 Region 线程渲染 GUI
            plugin.server.regionScheduler.execute(plugin, player.location) {
                if (guiInventory.viewers.contains(player)) {
                    // 清空中间区域 (保留边框)
                    val innerSlots = listOf(
                        10, 11, 12, 13, 14, 15, 16,
                        19, 20, 21, 22, 23, 24, 25,
                        28, 29, 30, 31, 32, 33, 34,
                        37, 38, 39, 40, 41, 42, 43
                    )
                    innerSlots.forEach { guiInventory.setItem(it, null) }
                    
                    items.clear()
                    
                    // 计算每页容量 (4行 * 7列 = 28个)
                    val itemsPerPage = 28
                    
                    list.forEachIndexed { index, marketItem ->
                        if (index < itemsPerPage) {
                            val displayItem = ItemSerializer.deserialize(marketItem.serializedData)
                            val itemMeta = displayItem.itemMeta
                            val lore = itemMeta?.lore ?: mutableListOf()
                            
                            lore.add("")
                            lore.add(config.getMessage("gui-item-price", mapOf("price" to marketItem.minPrice.toString()), false))
                            lore.add(config.getMessage("gui-item-stock", mapOf("stock" to marketItem.totalStock.toString()), false))
                            
                            // Check if player has stock
                            service.getSellOrdersAsync(marketItem.itemHash).thenAccept { orders ->
                                val playerOrders = orders.filter { it.sellerUuid == player.uniqueId }
                                if (playerOrders.isNotEmpty()) {
                                     // Update lore in main thread (Region)
                                     plugin.server.regionScheduler.execute(plugin, player.location) {
                                         if (guiInventory.viewers.contains(player)) {
                                             val currentItem = guiInventory.getItem(innerSlots[index])
                                             if (currentItem != null) {
                                                 val meta = currentItem.itemMeta
                                                 val currentLore = meta?.lore ?: mutableListOf()
                                                 
                                                 val feeRate = config.getDouble("market.cancellation-fee") * 100
                                                 val feeStr = String.format("%.1f%%", feeRate)
                                                 
                                                 currentLore.add("")
                                                 currentLore.add(config.getMessage("cancel-lore", mapOf("fee" to feeStr), false))
                                                 meta?.lore = currentLore
                                                 currentItem.itemMeta = meta
                                             }
                                         }
                                     }
                                }
                            }

                            lore.add("")
                            
                            // 价格分布进度条
                            if (marketItem.priceDistribution.isNotEmpty()) {
                                val total = marketItem.totalStock.toDouble()
                                val symbol = config.getMessage("gui-stock-bar-symbol", withPrefix = false)
                                val filledColor = config.getMessage("gui-stock-bar-color-filled", withPrefix = false)
                                val emptyColor = config.getMessage("gui-stock-bar-color-empty", withPrefix = false)
                                val maxBars = 10
                                
                                // 取前5个价格显示
                                marketItem.priceDistribution.entries.take(5).forEach { (price, amount) ->
                                    val percent = amount / total
                                    var filledCount = (percent * maxBars).toInt()
                                    if (filledCount == 0 && amount > 0) filledCount = 1 // 至少显示1格
                                    val emptyCount = (maxBars - filledCount).coerceAtLeast(0)
                                    
                                    val bar = StringBuilder()
                                    bar.append(filledColor)
                                    repeat(filledCount) { bar.append(symbol) }
                                    bar.append(emptyColor)
                                    repeat(emptyCount) { bar.append(symbol) }
                                    
                                    lore.add(config.getMessage("gui-stock-entry", mapOf(
                                        "price" to price.toString(),
                                        "bar" to bar.toString(),
                                        "amount" to amount.toString()
                                    ), false))
                                }
                                if (marketItem.priceDistribution.size > 5) {
                                    lore.add("§7...")
                                }
                                lore.add("")
                            }
                            
                            lore.add(config.getMessage("gui-item-click", withPrefix = false))
                            
                            itemMeta?.lore = lore
                            displayItem.itemMeta = itemMeta
                            
                            // 映射 index 到 innerSlots
                            val slot = innerSlots[index]
                            guiInventory.setItem(slot, displayItem)
                            items[slot] = marketItem
                        }
                    }
                    
                    // 翻页按钮 (放在边框上)
                    if (page > 1) {
                        val prev = ItemStack(Material.ARROW)
                        val prevMeta = prev.itemMeta
                        prevMeta?.setDisplayName(config.getMessage("gui-prev-page", withPrefix = false))
                        prev.itemMeta = prevMeta
                        guiInventory.setItem(45, prev)
                    }
                    
                    if (list.size >= itemsPerPage) {
                         val next = ItemStack(Material.ARROW)
                         val nextMeta = next.itemMeta
                         nextMeta?.setDisplayName(config.getMessage("gui-next-page", withPrefix = false))
                         next.itemMeta = nextMeta
                         guiInventory.setItem(53, next)
                    }

                    // 刷新按钮
                    val refresh = ItemStack(Material.NETHER_STAR)
                    val refreshMeta = refresh.itemMeta
                    refreshMeta?.setDisplayName(config.getMessage("gui-refresh", withPrefix = false))
                    refresh.itemMeta = refreshMeta
                    guiInventory.setItem(49, refresh)
                }
            }
        }
    }

    override fun onClick(event: InventoryClickEvent) {
        event.isCancelled = true
        val slot = event.rawSlot
        
        // 翻页逻辑
        if (slot == 45 && page > 1) {
            MarketGui(player, service, page - 1).open()
            return
        }
        
        if (slot == 53) {
            MarketGui(player, service, page + 1).open()
            return
        }
        
        if (slot == 49) {
            render() // 刷新
            return
        }
        
        // 商品点击逻辑 (现在 items key 是 slot，直接取即可)
        val marketItem = items[slot]
        if (marketItem != null) {
            val plugin = JavaPlugin.getPlugin(Order::class.java) // Capture plugin instance
            
            // Right click to cancel
            if (event.isRightClick) {
                // Fetch orders again to find one to cancel (simplified: cancel the oldest one or show a GUI list?)
                // User requirement: "右键取消上架" (Singular? Or all? "取消我的上架")
                // Let's assume cancel ONE order (LIFO/FIFO?) or show a list.
                // Given "market" usually aggregates, maybe better to cancel the oldest order or show a "Manage Listings" GUI.
                // For simplicity and matching typical plugins: Cancel ONE order (FIFO) per click or open a specific "My Listings" GUI.
                // User text says "取消我的上架", implying action.
                // Let's implement: Find ONE open order from this player for this item and cancel it.
                
                service.getSellOrdersAsync(marketItem.itemHash).thenAccept { orders ->
                    val playerOrder = orders.firstOrNull { it.sellerUuid == player.uniqueId }
                    if (playerOrder != null) {
                        service.cancelOrderAsync(player, playerOrder.id).thenAccept { success ->
                            if (success) {
                                // Refresh GUI
                                plugin.server.regionScheduler.execute(plugin, player.location) {
                                    render()
                                }
                            }
                        }
                    } else {
                         // No orders to cancel (Lore might be outdated or race condition)
                    }
                }
            } else {
                BuyGui(player, service, marketItem, this).open()
            }
        }
    }
}
