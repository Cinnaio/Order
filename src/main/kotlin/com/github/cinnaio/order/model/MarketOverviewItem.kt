package com.github.cinnaio.order.model

import java.math.BigDecimal

data class MarketOverviewItem(
    val itemHash: String,
    val serializedData: String,
    val displayName: String?,
    val materialType: String,
    val minPrice: BigDecimal,
    val totalStock: Int,
    val priceDistribution: Map<BigDecimal, Int> = emptyMap()
)
