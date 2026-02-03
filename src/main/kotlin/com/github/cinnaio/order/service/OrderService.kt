package com.github.cinnaio.order.service

import com.github.cinnaio.order.config.ConfigManager
import com.github.cinnaio.order.config.EconomyManager
import com.github.cinnaio.order.database.DatabaseManager
import com.github.cinnaio.order.model.MarketItem
import com.github.cinnaio.order.model.OrderStatus
import com.github.cinnaio.order.model.SellOrder
import com.github.cinnaio.order.model.Transaction
import com.github.cinnaio.order.utils.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

import com.github.cinnaio.order.model.MarketOverviewItem
import com.github.cinnaio.order.model.FeeItem

data class BuyResult(
    val success: Boolean,
    val amountBought: Int,
    val totalCost: BigDecimal,
    val message: String? = null
)

class OrderService(
    private val plugin: JavaPlugin,
    private val db: DatabaseManager,
    private val economy: EconomyManager,
    private val config: ConfigManager
) {

    data class AdminBanEntry(
        val isHash: Boolean,
        val itemHash: String? = null,
        val serializedData: String? = null,
        val displayName: String? = null,
        val materialType: String? = null
    )

    // 简单的内存锁，防止同一商品并发撮合导致超卖
    // 在多实例环境需依赖数据库锁
    private val itemLocks = ConcurrentHashMap<String, Any>()

    fun getSellOrdersAsync(itemHash: String): CompletableFuture<List<SellOrder>> {
        val future = CompletableFuture<List<SellOrder>>()
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                val orders = db.getSellOrdersByItem(itemHash)
                future.complete(orders)
            } catch (e: Exception) {
                e.printStackTrace()
                future.complete(emptyList())
            }
        }
        return future
    }

    fun getBannedEntriesAsync(): CompletableFuture<List<AdminBanEntry>> {
        val future = CompletableFuture<List<AdminBanEntry>>()
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                val list = config.getStringList("market.banned-items")
                val entries = mutableListOf<AdminBanEntry>()
                list.forEach { entry ->
                    val isHash = entry.matches(Regex("^[0-9a-fA-F]{64}$"))
                    if (isHash) {
                        val mi = db.getMarketItem(entry)
                        if (mi != null) {
                            entries.add(AdminBanEntry(
                                isHash = true,
                                itemHash = mi.itemHash,
                                serializedData = mi.serializedData,
                                displayName = mi.displayName,
                                materialType = mi.materialType
                            ))
                        } else {
                            entries.add(AdminBanEntry(
                                isHash = true,
                                itemHash = entry
                            ))
                        }
                    } else {
                        entries.add(AdminBanEntry(
                            isHash = false,
                            materialType = entry,
                            displayName = entry
                        ))
                    }
                }
                future.complete(entries)
            } catch (e: Exception) {
                e.printStackTrace()
                future.complete(emptyList())
            }
        }
        return future
    }

    fun getFeeItemsAsync(): CompletableFuture<List<FeeItem>> {
        val future = CompletableFuture<List<FeeItem>>()
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                val list = db.getItemsWithCustomFee()
                future.complete(list)
            } catch (e: Exception) {
                e.printStackTrace()
                future.complete(emptyList())
            }
        }
        return future
    }

    fun getCustomFeeRateAsync(itemHash: String): CompletableFuture<BigDecimal?> {
        val future = CompletableFuture<BigDecimal?>()
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                val rate = db.getCustomFeeRate(itemHash)
                future.complete(rate)
            } catch (e: Exception) {
                e.printStackTrace()
                future.complete(null)
            }
        }
        return future
    }

    fun setCustomFeeRateAsync(item: ItemStack, rate: BigDecimal?): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        
        // Normalize item for hash consistency
        val itemClone = item.clone()
        itemClone.amount = 1
        val serialized = ItemSerializer.serialize(itemClone)
        val hash = ItemSerializer.computeHash(serialized)
        
        val marketItem = MarketItem(
            itemHash = hash,
            serializedData = serialized,
            displayName = if (item.hasItemMeta() && item.itemMeta.hasDisplayName()) item.itemMeta.displayName else item.type.name,
            materialType = item.type.name
        )

        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                // Ensure item exists in DB
                db.saveMarketItem(marketItem)
                // Set fee
                db.setCustomFeeRate(hash, rate)
                future.complete(null)
            } catch (e: Exception) {
                e.printStackTrace()
                future.complete(null)
            }
        }
        return future
    }
    
    fun cancelOrderAsync(player: Player, orderId: Long): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                // 1. Get order
                val order = db.getOrder(orderId)
                if (order == null || order.sellerUuid != player.uniqueId || order.status != OrderStatus.OPEN) {
                    player.sendMessage(config.getMessage("cancel-fail-invalid"))
                    future.complete(false)
                    return@runNow
                }

                // 2. Calculate fee
                val remainingValue = order.pricePerUnit.multiply(BigDecimal(order.remainingAmount))
                val feeRate = config.getDouble("market.cancellation-fee")
                val fee = remainingValue.multiply(BigDecimal(feeRate))

                // 3. Main thread check balance
                player.scheduler.run(plugin, { _ ->
                    if (fee > BigDecimal.ZERO && !economy.has(player.uniqueId, fee.toDouble())) {
                        player.sendMessage(config.getMessage("cancel-fail-afford", mapOf("fee" to economy.format(fee.toDouble()))))
                        future.complete(false)
                        return@run
                    }

                    // 4. Deduct fee if any
                    if (fee > BigDecimal.ZERO) {
                        economy.withdraw(player.uniqueId, fee.toDouble())
                        player.sendMessage(config.getMessage("cancel-fee-deducted", mapOf("fee" to economy.format(fee.toDouble()))))
                    }

                    // 5. Async update DB
                    Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
                         try {
                             order.status = OrderStatus.CANCELLED
                             if (db.updateOrder(order)) {
                                 // 6. Return items (Main thread)
                                 val marketItem = db.getMarketItem(order.itemHash)
                                 player.scheduler.run(plugin, { _ ->
                                     if (marketItem != null) {
                                         val baseStack = ItemSerializer.deserialize(marketItem.serializedData)
                                         val maxStack = baseStack.maxStackSize
                                         var toGive = order.remainingAmount
                                         
                                         while (toGive > 0) {
                                             val amount = minOf(toGive, maxStack)
                                             val stack = baseStack.clone()
                                             stack.amount = amount
                                             val left = player.inventory.addItem(stack)
                                             left.values.forEach { player.world.dropItemNaturally(player.location, it) }
                                             toGive -= amount
                                         }
                                         player.sendMessage(config.getMessage("cancel-success"))
                                         future.complete(true)
                                     } else {
                                         future.complete(false) // Data error
                                     }
                                 }, null)
                             } else {
                                 // Failed to update (concurrent modification?)
                                 // Refund fee? (Simplification: Ignore for now, rare case)
                                 future.complete(false)
                             }
                         } catch (e: Exception) {
                             e.printStackTrace()
                             future.complete(false)
                         }
                    }
                }, null)
            } catch (e: Exception) {
                e.printStackTrace()
                future.complete(false)
            }
        }
        return future
    }

    fun getMarketOverviewAsync(page: Int): CompletableFuture<List<MarketOverviewItem>> {
        val future = CompletableFuture<List<MarketOverviewItem>>()
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                val limit = 45 // 5行 * 9列
                val offset = (page - 1) * limit
                val list = db.getMarketOverview(limit, offset)
                future.complete(list)
            } catch (e: Exception) {
                e.printStackTrace()
                future.complete(emptyList())
            }
        }
        return future
    }

    fun buyItemAsync(buyer: Player, itemHash: String, amountToBuy: Int): CompletableFuture<BuyResult> {
        val future = CompletableFuture<BuyResult>()
        
        if (amountToBuy <= 0) {
            future.complete(BuyResult(false, 0, BigDecimal.ZERO, config.getMessage("buy-invalid-amount", withPrefix = false)))
            return future
        }

        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                // 1. 获取所有可用订单（按价格升序），并过滤掉购买者自己的订单
                val allOrders = db.findBestMatches(itemHash)
                val orders = allOrders.filter { it.sellerUuid != buyer.uniqueId }

                if (orders.isEmpty()) {
                    future.complete(BuyResult(false, 0, BigDecimal.ZERO, config.getMessage("buy-no-stock", withPrefix = false)))
                    return@runNow
                }

                // 2. 切换到主线程检查余额并预扣款
                buyer.scheduler.run(plugin, { _ ->
                    val totalStock = orders.sumOf { it.remainingAmount }
                    if (totalStock == 0) {
                        future.complete(BuyResult(false, 0, BigDecimal.ZERO, config.getMessage("buy-no-stock", withPrefix = false)))
                        return@run
                    }
                    
                    var plannedCount = 0
                    var plannedCost = BigDecimal.ZERO
                    var tempNeeded = amountToBuy
                    
                    for (order in orders) {
                        if (tempNeeded <= 0) break
                        val count = minOf(order.remainingAmount, tempNeeded)
                        plannedCount += count
                        plannedCost = plannedCost.add(order.pricePerUnit.multiply(BigDecimal(count)))
                        tempNeeded -= count
                    }
                    
                    if (plannedCount == 0) {
                         future.complete(BuyResult(false, 0, BigDecimal.ZERO, config.getMessage("buy-no-stock", withPrefix = false)))
                         return@run
                    }

                    if (!economy.has(buyer.uniqueId, plannedCost.toDouble())) {
                        future.complete(BuyResult(false, 0, plannedCost, config.getMessage("buy-insufficient-funds", withPrefix = false)))
                        return@run
                    }
                    
                    // 预扣款
                    economy.withdraw(buyer.uniqueId, plannedCost.toDouble())
                    
                    // 3. 切换回异步线程执行数据库更新
                    Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
                        try {
                            var actualBought = 0
                            var actualCost = BigDecimal.ZERO
                            var remainingToBuy = amountToBuy
                            val transactions = mutableListOf<Transaction>()
                            
                            // Pre-fetch custom fee rate
                            val customRate = db.getCustomFeeRate(itemHash)
                            val feeRate = customRate?.toDouble() ?: config.getDouble("market.transaction-fee")

                            for (order in orders) {
                                if (remainingToBuy <= 0) break
                                
                                val count = minOf(order.remainingAmount, remainingToBuy)
                                val cost = order.pricePerUnit.multiply(BigDecimal(count))
                                
                                // 更新对象状态
                                order.remainingAmount -= count
                                if (order.remainingAmount == 0) {
                                    order.status = OrderStatus.DONE
                                }
                                
                                // 尝试更新数据库（乐观锁）
                                if (db.updateOrder(order)) {
                                    actualBought += count
                                    actualCost = actualCost.add(cost)
                                    remainingToBuy -= count
                                    
                                    transactions.add(Transaction(
                                        buyerUuid = buyer.uniqueId,
                                        buyerName = buyer.name,
                                        sellerUuid = order.sellerUuid,
                                        sellerName = order.sellerName,
                                        orderId = order.id,
                                        itemHash = itemHash,
                                        amount = count,
                                        pricePerUnit = order.pricePerUnit,
                                        totalPrice = cost,
                                        tradedAt = Instant.now()
                                    ))
                                }
                            }
                            
                            // 记录交易日志
                            transactions.forEach { db.logTransaction(it) }
                            
                            // 获取物品信息用于发货
                            val marketItem = db.getMarketItem(itemHash)
                            
                            // 4. 切换回主线程处理退款、打款和发货
                            buyer.scheduler.run(plugin, { _ ->
                                // 退还多扣的钱
                                val refund = plannedCost.subtract(actualCost)
                                if (refund > BigDecimal.ZERO) {
                                    economy.deposit(buyer.uniqueId, refund.toDouble())
                                }
                                
                                // 给卖家打款 (扣除手续费)
                                transactions.forEach { tx ->
                                    val gross = tx.totalPrice
                                    val fee = gross.multiply(BigDecimal(feeRate))
                                    val net = gross.subtract(fee)
                                    
                                    economy.deposit(tx.sellerUuid, net.toDouble())
                                    
                                    // 通知卖家 (在线)
                                    val seller = Bukkit.getPlayer(tx.sellerUuid)
                                    seller?.sendMessage(config.getMessage("seller-sold-notification", mapOf(
                                        "amount" to tx.amount.toString(), 
                                        "price" to economy.format(net.toDouble()),
                                        "fee" to economy.format(fee.toDouble())
                                    )))
                                }
                                
                                // 发货
                                if (actualBought > 0 && marketItem != null) {
                                    val baseStack = ItemSerializer.deserialize(marketItem.serializedData)
                                    val maxStack = baseStack.maxStackSize
                                    var toGive = actualBought
                                    
                                    while (toGive > 0) {
                                        val amount = minOf(toGive, maxStack)
                                        val stack = baseStack.clone()
                                        stack.amount = amount
                                        
                                        val left = buyer.inventory.addItem(stack)
                                        if (left.isNotEmpty()) {
                                            left.values.forEach { buyer.world.dropItemNaturally(buyer.location, it) }
                                            buyer.sendMessage(config.getMessage("buy-inventory-full"))
                                        }
                                        toGive -= amount
                                    }
                                    
                                    future.complete(BuyResult(true, actualBought, actualCost, config.getMessage("buy-success-msg", mapOf("amount" to actualBought.toString()), false)))
                                } else if (actualBought == 0) {
                                     future.complete(BuyResult(false, 0, BigDecimal.ZERO, config.getMessage("buy-fail-concurrent", withPrefix = false)))
                                } else {
                                     future.complete(BuyResult(true, actualBought, actualCost, config.getMessage("buy-fail-data", withPrefix = false)))
                                }
                            }, null)
                            
                        } catch (e: Exception) {
                            e.printStackTrace()
                            // 严重错误：回滚预扣款
                             buyer.scheduler.run(plugin, { _ ->
                                 economy.deposit(buyer.uniqueId, plannedCost.toDouble())
                                 future.complete(BuyResult(false, 0, BigDecimal.ZERO, "Error during processing: ${e.message}"))
                             }, null)
                        }
                    }
                }, null)
            } catch (e: Exception) {
                e.printStackTrace()
                future.complete(BuyResult(false, 0, BigDecimal.ZERO, "Error: ${e.message}"))
            }
        }
        
        return future
    }

    fun createSellOrderAsync(player: Player, item: ItemStack, price: BigDecimal, amount: Int): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        
        if (amount <= 0 || price <= BigDecimal.ZERO) {
            future.complete(false)
            return future
        }
        
        // 1. 序列化物品 (强制数量为1，确保同类物品堆叠)
        val itemClone = item.clone()
        itemClone.amount = 1
        val serialized = ItemSerializer.serialize(itemClone)
        val hash = ItemSerializer.computeHash(serialized)
        
        // 快照玩家信息
        val uuid = player.uniqueId
        val name = player.name
        
        // 异步执行数据库操作
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                // 2. 确保 MarketItem 存在
                if (db.getMarketItem(hash) == null) {
                    val marketItem = MarketItem(
                        itemHash = hash,
                        serializedData = serialized,
                        displayName = if (item.hasItemMeta() && item.itemMeta.hasDisplayName()) item.itemMeta.displayName else item.type.name,
                        materialType = item.type.name
                    )
                    db.saveMarketItem(marketItem)
                }
                
                // 3. 创建订单
                val order = SellOrder(
                    sellerUuid = uuid,
                    sellerName = name, // 记录快照
                    itemHash = hash,
                    pricePerUnit = price,
                    totalAmount = amount,
                    remainingAmount = amount,
                    status = OrderStatus.OPEN,
                    createdAt = Instant.now()
                )
                
                // 4. 存入数据库
                val success = db.createSellOrder(order) > 0
                future.complete(success)
                
            } catch (e: Exception) {
                e.printStackTrace()
                future.complete(false)
            }
        }
        
        return future
    }
}
