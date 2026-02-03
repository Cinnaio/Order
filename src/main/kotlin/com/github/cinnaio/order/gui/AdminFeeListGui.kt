package com.github.cinnaio.order.gui

import com.github.cinnaio.order.Order
import com.github.cinnaio.order.model.FeeItem
import com.github.cinnaio.order.service.OrderService
import com.github.cinnaio.order.utils.ItemSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.math.BigDecimal

class AdminFeeListGui(
    player: Player,
    private val service: OrderService,
    private val page: Int = 1
) : GuiBase(
    player,
    54,
    JavaPlugin.getPlugin(Order::class.java).configManager.getMessage("gui-admin-fee-title", mapOf("page" to page.toString()), false)
) {

    private val slotMap = mutableMapOf<Int, FeeItem>()

    override fun render() {
        val plugin = JavaPlugin.getPlugin(Order::class.java)
        val config = plugin.configManager

        guiInventory.clear()

        val borderItem = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val borderMeta = borderItem.itemMeta
        borderMeta?.setDisplayName(" ")
        borderItem.itemMeta = borderMeta

        val borderSlots = listOf(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 17, 18, 26, 27, 35, 36, 44,
            45, 46, 47, 48, 50, 51, 52, 53
        )
        borderSlots.forEach { guiInventory.setItem(it, borderItem) }

        val loading = ItemStack(Material.CLOCK)
        val meta = loading.itemMeta
        meta?.setDisplayName(config.getMessage("gui-loading", withPrefix = false))
        loading.itemMeta = meta
        guiInventory.setItem(22, loading)

        service.getFeeItemsAsync().thenAccept { entries ->
            plugin.server.regionScheduler.execute(plugin, player.location) {
                if (!guiInventory.viewers.contains(player)) return@execute

                val innerSlots = listOf(
                    10, 11, 12, 13, 14, 15, 16,
                    19, 20, 21, 22, 23, 24, 25,
                    28, 29, 30, 31, 32, 33, 34,
                    37, 38, 39, 40, 41, 42, 43
                )

                innerSlots.forEach { guiInventory.setItem(it, null) }
                slotMap.clear()

                val itemsPerPage = 28
                val start = (page - 1) * itemsPerPage
                val pageEntries = entries.drop(start).take(itemsPerPage)

                pageEntries.forEachIndexed { idx, entry ->
                    val slot = innerSlots[idx]
                    val displayName = entry.displayName ?: entry.materialType
                    val item: ItemStack = if (!entry.serializedData.isNullOrEmpty()) {
                        try {
                            ItemSerializer.deserialize(entry.serializedData)
                        } catch (e: Exception) {
                            val mat = runCatching { Material.valueOf(entry.materialType) }.getOrNull() ?: Material.PAPER
                            ItemStack(mat)
                        }
                    } else if (entry.itemHash.isNotEmpty()) {
                        // Fallback if serializedData missing but hash present (shouldn't happen with new logic)
                        val mat = runCatching { Material.valueOf(entry.materialType) }.getOrNull() ?: Material.PAPER
                        ItemStack(mat)
                    } else {
                        val mat = runCatching { Material.valueOf(entry.materialType) }.getOrNull() ?: Material.PAPER
                        ItemStack(mat)
                    }
                    val im = item.itemMeta
                    im?.setDisplayName(displayName)
                    val lore = mutableListOf<String>()
                    lore.add(config.getMessage("gui-admin-fee-rate", mapOf("rate" to entry.feeRate.toString()), false))
                    val shortHash = entry.itemHash.take(8)
                    lore.add(config.getMessage("gui-admin-fee-hash", mapOf("hash" to shortHash), false))
                    im?.lore = lore
                    item.itemMeta = im
                    guiInventory.setItem(slot, item)
                    slotMap[slot] = entry
                }

                if (page > 1) {
                    val prev = ItemStack(Material.ARROW)
                    val prevMeta = prev.itemMeta
                    prevMeta?.setDisplayName(config.getMessage("gui-prev-page", withPrefix = false))
                    prev.itemMeta = prevMeta
                    guiInventory.setItem(45, prev)
                }

                if (entries.size > start + itemsPerPage) {
                    val next = ItemStack(Material.ARROW)
                    val nextMeta = next.itemMeta
                    nextMeta?.setDisplayName(config.getMessage("gui-next-page", withPrefix = false))
                    next.itemMeta = nextMeta
                    guiInventory.setItem(53, next)
                }

                val refresh = ItemStack(Material.NETHER_STAR)
                val refreshMeta = refresh.itemMeta
                refreshMeta?.setDisplayName(config.getMessage("gui-refresh", withPrefix = false))
                refresh.itemMeta = refreshMeta
                guiInventory.setItem(49, refresh)
            }
        }
    }

    override fun onClick(event: InventoryClickEvent) {
        event.isCancelled = true
        val slot = event.rawSlot
        if (slot == 45 && page > 1) {
            AdminFeeListGui(player, service, page - 1).open()
            return
        }
        if (slot == 53) {
            AdminFeeListGui(player, service, page + 1).open()
            return
        }
        if (slot == 49) {
            render()
            return
        }
    }
}
