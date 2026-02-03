package com.github.cinnaio.order

import com.github.cinnaio.order.config.ConfigManager
import com.github.cinnaio.order.config.EconomyManager
import com.github.cinnaio.order.database.DatabaseManager
import com.github.cinnaio.order.gui.GuiListener
import com.github.cinnaio.order.gui.MarketGui
import com.github.cinnaio.order.gui.AdminBanListGui
import com.github.cinnaio.order.gui.AdminFeeListGui
import com.github.cinnaio.order.service.OrderService
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.math.BigDecimal
import com.github.cinnaio.order.utils.ItemSerializer

class Order : JavaPlugin(), CommandExecutor, TabCompleter {

    lateinit var configManager: ConfigManager
    private lateinit var databaseManager: DatabaseManager
    private lateinit var economyManager: EconomyManager
    private lateinit var orderService: OrderService

    override fun onEnable() {
        // 0. 初始化配置
        configManager = ConfigManager(this)

        // 1. 初始化数据库
        databaseManager = DatabaseManager(dataFolder, configManager)
        databaseManager.init()
        
        // 1.5 初始化经济
        economyManager = EconomyManager(this)
        
        // 2. 初始化服务
        orderService = OrderService(this, databaseManager, economyManager, configManager)
        
        // 3. 注册监听器
        server.pluginManager.registerEvents(GuiListener(), this)
        
        // 4. 注册命令
        getCommand("market")?.apply {
            setExecutor(this@Order)
            tabCompleter = this@Order
        }
        
        logger.info("Order plugin enabled!")
    }

    override fun onDisable() {
        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }
        logger.info("Order plugin disabled!")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // Handle reload command (Allows Console)
        if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
            if (!sender.hasPermission("order.reload")) {
                sender.sendMessage(configManager.getMessage("no-permission"))
                return true
            }
            
            try {
                configManager.loadConfig()
                configManager.loadMessages()
                if (::databaseManager.isInitialized) {
                    databaseManager.close()
                }
                databaseManager.init()
                sender.sendMessage(configManager.getMessage("reload-success"))
            } catch (e: Exception) {
                sender.sendMessage(configManager.getMessage("reload-fail", mapOf("error" to (e.message ?: "Unknown error"))))
                e.printStackTrace()
            }
            return true
        }

        // Help
        if (args.isNotEmpty() && args[0].equals("help", ignoreCase = true)) {
            val version = description.version
            sender.sendMessage(" ")
            sender.sendMessage(configManager.getMessage("help-title", mapOf("version" to version), false))
            sender.sendMessage(" ")
            // /market
            sender.sendMessage(configManager.getMessage("help-open", withPrefix = false))
            sender.sendMessage(configManager.getMessage("help-open-detail", withPrefix = false))
            // /market sell
            sender.sendMessage(configManager.getMessage("help-sell", withPrefix = false))
            sender.sendMessage(configManager.getMessage("help-sell-detail", withPrefix = false))
            // /market admin fee
            sender.sendMessage(configManager.getMessage("help-admin-fee", withPrefix = false))
            sender.sendMessage(configManager.getMessage("help-admin-fee-detail", withPrefix = false))
            // /market admin fee list
            sender.sendMessage(configManager.getMessage("help-admin-fee-list", withPrefix = false))
            sender.sendMessage(configManager.getMessage("help-admin-fee-list-detail", withPrefix = false))
            // /market admin ban
            sender.sendMessage(configManager.getMessage("help-admin-ban", withPrefix = false))
            sender.sendMessage(configManager.getMessage("help-admin-ban-detail", withPrefix = false))
            // /market admin ban list
            sender.sendMessage(configManager.getMessage("help-admin-ban-list", withPrefix = false))
            sender.sendMessage(configManager.getMessage("help-admin-ban-list-detail", withPrefix = false))
            // /market reload
            sender.sendMessage(configManager.getMessage("help-reload", withPrefix = false))
            sender.sendMessage(configManager.getMessage("help-reload-detail", withPrefix = false))
            return true
        }

        // Handle Admin Commands
        if (args.isNotEmpty() && args[0].equals("admin", ignoreCase = true)) {
            if (!sender.hasPermission("order.admin")) {
                sender.sendMessage(configManager.getMessage("no-permission"))
                return true
            }
            
            if (args.size < 2) {
                sender.sendMessage("Usage: /market admin <fee|ban> ...")
                return true
            }

            // /market admin fee <rate> (Held item)
            if (args[1].equals("fee", ignoreCase = true)) {
                if (args.size >= 3 && args[2].equals("list", ignoreCase = true)) {
                    if (sender is Player) {
                        AdminFeeListGui(sender, orderService).open()
                    } else {
                        val list = databaseManager.getItemsWithCustomFee()
                        sender.sendMessage(configManager.getMessage("admin-fee-list-title"))
                        if (list.isEmpty()) {
                            sender.sendMessage(configManager.getMessage("admin-fee-list-empty"))
                        } else {
                            list.forEach {
                                val name = it.displayName ?: it.materialType
                                val rate = it.feeRate.toString()
                                val shortHash = it.itemHash.substring(0, 8)
                                sender.sendMessage(configManager.getMessage("admin-fee-list-entry", mapOf(
                                    "name" to name,
                                    "material" to it.materialType,
                                    "hash" to shortHash,
                                    "rate" to rate
                                )))
                            }
                        }
                    }
                    return true
                }
                if (sender !is Player) {
                    sender.sendMessage(configManager.getMessage("only-player"))
                    return true
                }
                
                val item = sender.inventory.itemInMainHand
                if (item.type == Material.AIR) {
                    sender.sendMessage("§cPlease hold an item.")
                    return true
                }

                if (args.size < 3) {
                     // Reset fee (set to null)
                     orderService.setCustomFeeRateAsync(item, null).thenRun {
                         sender.sendMessage(configManager.getMessage("admin-fee-reset"))
                     }
                     return true
                }

                val rateStr = args[2]
                try {
                    val rate = BigDecimal(rateStr)
                    if (rate < BigDecimal.ZERO || rate > BigDecimal.ONE) {
                        sender.sendMessage("§cRate must be between 0.0 and 1.0")
                        return true
                    }
                    
                    // Now we pass the item directly, OrderService handles normalization and DB insertion
                    orderService.setCustomFeeRateAsync(item, rate).thenRun {
                        sender.sendMessage(configManager.getMessage("admin-fee-set", mapOf("rate" to rate.toString())))
                    }
                } catch (e: NumberFormatException) {
                    sender.sendMessage("§cInvalid number.")
                }
                return true
            }
            
            // /market admin ban (Held item)
            if (args[1].equals("ban", ignoreCase = true)) {
                if (args.size >= 3 && args[2].equals("list", ignoreCase = true)) {
                    if (sender is Player) {
                        AdminBanListGui(sender, orderService).open()
                    } else {
                        val bannedList = configManager.getStringList("market.banned-items")
                        sender.sendMessage(configManager.getMessage("admin-ban-list-title"))
                        if (bannedList.isEmpty()) {
                            sender.sendMessage(configManager.getMessage("admin-ban-list-empty"))
                        } else {
                            bannedList.forEach { entry ->
                                val isHash = entry.matches(Regex("^[0-9a-fA-F]{64}$"))
                                if (isHash) {
                                    val shortHash = entry.substring(0, 8)
                                    sender.sendMessage(configManager.getMessage("admin-ban-list-entry-hash", mapOf(
                                        "hash" to shortHash
                                    )))
                                } else {
                                    sender.sendMessage(configManager.getMessage("admin-ban-list-entry-material", mapOf(
                                        "material" to entry
                                    )))
                                }
                            }
                        }
                    }
                    return true
                }
                 if (sender !is Player) {
                    sender.sendMessage(configManager.getMessage("only-player"))
                    return true
                }
                
                val item = sender.inventory.itemInMainHand
                if (item.type == Material.AIR) {
                    sender.sendMessage("§cPlease hold an item.")
                    return true
                }
                
                val matName = item.type.name
                
                // Calculate Hash for strict ban
                val itemClone = item.clone()
                itemClone.amount = 1
                val serialized = ItemSerializer.serialize(itemClone)
                val hash = ItemSerializer.computeHash(serialized)
                
                val bannedList = configManager.getStringList("market.banned-items").toMutableList()
                
                // Toggle ban (check both Material name and Hash)
                // We prioritize Hash if it's there.
                
                if (bannedList.contains(hash)) {
                    bannedList.remove(hash)
                    configManager.config.set("market.banned-items", bannedList)
                    configManager.saveConfig()
                    sender.sendMessage(configManager.getMessage("admin-ban-success", mapOf("item" to "Hash: ${hash.substring(0, 8)}... (Unbanned)")))
                } else {
                    // Save MarketItem to DB so we can display it in GUI
                    val marketItem = com.github.cinnaio.order.model.MarketItem(
                        itemHash = hash,
                        serializedData = serialized,
                        displayName = if (item.hasItemMeta() && item.itemMeta.hasDisplayName()) item.itemMeta.displayName else item.type.name,
                        materialType = item.type.name
                    )
                    databaseManager.saveMarketItem(marketItem)
                    
                    bannedList.add(hash)
                    configManager.config.set("market.banned-items", bannedList)
                    configManager.saveConfig()
                    sender.sendMessage(configManager.getMessage("admin-ban-success", mapOf("item" to "Hash: ${hash.substring(0, 8)}... (Banned)")))
                }
                return true
            }
        }

        if (sender !is Player) {
            sender.sendMessage(configManager.getMessage("only-player"))
            return true
        }

        if (args.isEmpty()) {
            val gui = MarketGui(sender, orderService)
            gui.open()
            return true
        }

        // Handle Sell Command: /market sell <price>
        if (args[0].equals("sell", ignoreCase = true)) {
            if (args.size < 2) {
                sender.sendMessage(configManager.getMessage("invalid-args", mapOf("usage" to configManager.getMessage("sell-usage"))))
                return true
            }
            
            // Check item in hand
            val item = sender.inventory.itemInMainHand
            if (item.type == Material.AIR) {
                sender.sendMessage(configManager.getMessage("sell-no-item"))
                return true
            }
            
            // Check if banned
            val bannedList = configManager.getStringList("market.banned-items")
            
            // Check Material Name
            if (bannedList.contains(item.type.name)) {
                sender.sendMessage(configManager.getMessage("gui-sell-banned"))
                return true
            }
            
            // Check Item Hash (Strict)
            val itemCheck = item.clone()
            itemCheck.amount = 1
            val serializedCheck = ItemSerializer.serialize(itemCheck)
            val hashCheck = ItemSerializer.computeHash(serializedCheck)
            
            if (bannedList.contains(hashCheck)) {
                 sender.sendMessage(configManager.getMessage("gui-sell-banned"))
                 return true
            }

            // Parse price
            val priceInput = args[1]
            val pricePerUnit: BigDecimal
            try {
                pricePerUnit = BigDecimal(priceInput)
                if (pricePerUnit <= BigDecimal.ZERO) {
                    throw NumberFormatException("Price must be > 0")
                }
            } catch (e: NumberFormatException) {
                sender.sendMessage(configManager.getMessage("sell-invalid-price"))
                return true
            }
            
            if (args.size >= 3 && args[2].equals("all", ignoreCase = true)) {
                val inv = sender.inventory
                val contents = inv.contents
                val heldClone = item.clone()
                heldClone.amount = 1
                val heldSerialized = ItemSerializer.serialize(heldClone)
                val heldHash = ItemSerializer.computeHash(heldSerialized)
                
                if (bannedList.contains(heldHash)) {
                    sender.sendMessage(configManager.getMessage("gui-sell-banned"))
                    return true
                }
                
                val indices = mutableListOf<Int>()
                contents.forEachIndexed { index, stack ->
                    if (stack != null && stack.type != Material.AIR) {
                        val clone = stack.clone()
                        clone.amount = 1
                        val h = ItemSerializer.computeHash(ItemSerializer.serialize(clone))
                        if (h == heldHash) {
                            indices.add(index)
                        }
                    }
                }
                
                if (indices.isEmpty()) {
                    sender.sendMessage(configManager.getMessage("sell-no-item"))
                    return true
                }
                
                var total = 0
                indices.forEach { idx ->
                    val s = contents[idx]
                    if (s != null && s.type != Material.AIR) {
                        total += s.amount
                        inv.setItem(idx, null)
                    }
                }
                
                if (total <= 0) {
                    sender.sendMessage(configManager.getMessage("sell-no-item"))
                    return true
                }
                
                val rep = heldClone
                orderService.createSellOrderAsync(sender, rep, pricePerUnit, total).thenAccept { success ->
                    server.regionScheduler.execute(this, sender.location) {
                        if (success) {
                            val totalPrice = pricePerUnit.multiply(BigDecimal(total))
                            sender.sendMessage(configManager.getMessage("sell-success", mapOf(
                                "amount" to total.toString(),
                                "item" to (if (rep.hasItemMeta() && rep.itemMeta.hasDisplayName()) rep.itemMeta.displayName else rep.type.name),
                                "price" to pricePerUnit.toString(),
                                "total" to totalPrice.toString()
                            )))
                        } else {
                            var remain = total
                            val base = rep
                            while (remain > 0) {
                                val give = minOf(remain, base.maxStackSize)
                                val back = base.clone()
                                back.amount = give
                                sender.inventory.addItem(back)
                                remain -= give
                            }
                            sender.sendMessage(configManager.getMessage("sell-fail"))
                        }
                    }
                }
                return true
            }
            
            val amount = item.amount
            val itemToSell = item.clone()
            
            // Clear item from hand
            sender.inventory.setItemInMainHand(null)
            
            // Async create order
            orderService.createSellOrderAsync(sender, itemToSell, pricePerUnit, amount).thenAccept { success ->
                 server.regionScheduler.execute(this, sender.location) {
                     if (success) {
                         val totalPrice = pricePerUnit.multiply(BigDecimal(amount))
                         sender.sendMessage(configManager.getMessage("sell-success", mapOf(
                             "amount" to amount.toString(),
                             "item" to (if (itemToSell.hasItemMeta() && itemToSell.itemMeta.hasDisplayName()) itemToSell.itemMeta.displayName else itemToSell.type.name),
                             "price" to pricePerUnit.toString(),
                             "total" to totalPrice.toString()
                         )))
                     } else {
                         // Return item if failed
                         sender.inventory.addItem(itemToSell)
                         sender.sendMessage(configManager.getMessage("sell-fail"))
                     }
                 }
            }
            return true
        }
        
        sender.sendMessage(configManager.getMessage("unknown-command"))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val subCommands = mutableListOf("sell", "reload", "admin", "help")
            return subCommands.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        
        if (args.size == 2) {
            if (args[0].equals("sell", ignoreCase = true)) {
                 return listOf("<price>")
            }
            if (args[0].equals("admin", ignoreCase = true)) {
                 return listOf("fee", "ban")
            }
        }
        
        if (args.size == 3) {
            if (args[0].equals("sell", ignoreCase = true)) {
                return listOf("all")
            }
        }
        
        if (args.size == 3 && args[0].equals("admin", ignoreCase = true)) {
            if (args[1].equals("fee", ignoreCase = true) || args[1].equals("ban", ignoreCase = true)) {
                return listOf("list")
            }
        }
        
        return emptyList()
    }
}
