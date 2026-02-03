package com.github.cinnaio.order.gui

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent

class GuiListener : Listener {

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder is GuiBase) {
            holder.onClick(event)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val holder = event.inventory.holder
        if (holder is GuiBase) {
            holder.onDrag(event)
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder
        if (holder is GuiBase) {
            holder.onClose(event)
        }
    }
    
    @EventHandler
    fun onOpen(event: InventoryOpenEvent) {
        val holder = event.inventory.holder
        if (holder is GuiBase) {
            holder.onOpen(event)
        }
    }
}
