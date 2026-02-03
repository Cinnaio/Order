package com.github.cinnaio.order.gui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

abstract class GuiBase(val player: Player, val size: Int, val title: String) : InventoryHolder {
    
    val guiInventory: Inventory = Bukkit.createInventory(this, size, title)

    abstract fun render()

    open fun open() {
        render()
        player.openInventory(guiInventory)
    }

    open fun onClick(event: InventoryClickEvent) {
        event.isCancelled = true // 默认取消点击，防止拿走 GUI 物品
    }

    open fun onDrag(event: InventoryDragEvent) {
        event.isCancelled = true
    }
    
    open fun onClose(event: InventoryCloseEvent) {}
    
    open fun onOpen(event: InventoryOpenEvent) {}

    override fun getInventory(): Inventory {
        return guiInventory
    }
}
