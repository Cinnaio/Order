package com.github.cinnaio.order.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class OrderStatus {
    OPEN,
    PARTIAL,
    DONE,
    CANCELLED
}

data class MarketItem(
    val itemHash: String,
    val serializedData: String, // Base64
    val displayName: String?,
    val materialType: String
)

data class FeeItem(
    val itemHash: String,
    val displayName: String?,
    val materialType: String,
    val feeRate: BigDecimal,
    val serializedData: String? = null
)

data class SellOrder(
    val id: Long = 0,
    val sellerUuid: UUID,
    val sellerName: String, // 快照：下单时的玩家名
    val itemHash: String,
    val pricePerUnit: BigDecimal,
    val totalAmount: Int,
    var remainingAmount: Int,
    var status: OrderStatus,
    val createdAt: Instant,
    val version: Int = 0 // 乐观锁版本号
)

data class Transaction(
    val id: Long = 0,
    val buyerUuid: UUID,
    val buyerName: String, // 快照
    val sellerUuid: UUID,
    val sellerName: String, // 快照
    val orderId: Long,
    val itemHash: String,
    val amount: Int,
    val pricePerUnit: BigDecimal,
    val totalPrice: BigDecimal,
    val tradedAt: Instant
)
