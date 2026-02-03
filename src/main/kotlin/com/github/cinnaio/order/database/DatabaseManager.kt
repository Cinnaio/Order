package com.github.cinnaio.order.database

import com.github.cinnaio.order.config.ConfigManager
import com.github.cinnaio.order.model.MarketItem
import com.github.cinnaio.order.model.OrderStatus
import com.github.cinnaio.order.model.SellOrder
import com.github.cinnaio.order.model.Transaction
import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.UUID

import com.github.cinnaio.order.model.MarketOverviewItem

class DatabaseManager(private val dataFolder: File, private val config: ConfigManager) {

    private lateinit var connection: Connection

    fun init() {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        val type = config.getString("database.type") ?: "sqlite"
        val url: String

        if (type.equals("mysql", ignoreCase = true)) {
            val host = config.getString("database.host")
            val port = config.getInt("database.port")
            val dbName = config.getString("database.database")
            val user = config.getString("database.username")
            val password = config.getString("database.password")
            url = "jdbc:mysql://$host:$port/$dbName?useSSL=false&autoReconnect=true&characterEncoding=UTF-8"
            
            try {
                connection = DriverManager.getConnection(url, user, password)
            } catch (e: SQLException) {
                e.printStackTrace()
                throw RuntimeException("Failed to connect to MySQL database", e)
            }
        } else {
            // Default to SQLite
            val fileName = config.getString("database.file") ?: "order.db"
            val dbFile = File(dataFolder, fileName)
            url = "jdbc:sqlite:${dbFile.absolutePath}"
            
            try {
                // Ensure class loaded
                Class.forName("org.sqlite.JDBC")
                connection = DriverManager.getConnection(url)
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException("Failed to connect to SQLite database", e)
            }
        }

        try {
            createTables(type)
        } catch (e: SQLException) {
            e.printStackTrace()
            throw RuntimeException("Failed to create tables", e)
        }
    }

    private fun createTables(type: String) {
        val stmt = connection.createStatement()
        val isMySQL = type.equals("mysql", ignoreCase = true)
        val autoIncrement = if (isMySQL) "AUTO_INCREMENT" else "AUTOINCREMENT"
        
        // 1. Market Items
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS market_items (
                item_hash VARCHAR(64) PRIMARY KEY,
                serialized_data TEXT NOT NULL,
                display_name VARCHAR(255),
                material_type VARCHAR(64),
                custom_fee_rate DECIMAL(5, 4) DEFAULT NULL
            )
        """)

        // Add custom_fee_rate column if not exists (for existing DB)
        try {
            stmt.execute("ALTER TABLE market_items ADD COLUMN custom_fee_rate DECIMAL(5, 4) DEFAULT NULL")
        } catch (e: SQLException) {
            // Ignore if column already exists
        }

        // 2. Sell Orders
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS sell_orders (
                id INTEGER PRIMARY KEY $autoIncrement,
                seller_uuid VARCHAR(36) NOT NULL,
                seller_name VARCHAR(64) NOT NULL,
                item_hash VARCHAR(64) NOT NULL,
                price_per_unit DECIMAL(20, 2) NOT NULL,
                total_amount INTEGER NOT NULL,
                remaining_amount INTEGER NOT NULL,
                status VARCHAR(20) NOT NULL,
                created_at LONG NOT NULL,
                version INTEGER DEFAULT 0
            )
        """)

        // 3. Transactions
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS transactions (
                id INTEGER PRIMARY KEY $autoIncrement,
                buyer_uuid VARCHAR(36) NOT NULL,
                buyer_name VARCHAR(64) NOT NULL,
                seller_uuid VARCHAR(36) NOT NULL,
                seller_name VARCHAR(64) NOT NULL,
                order_id INTEGER,
                item_hash VARCHAR(64) NOT NULL,
                amount INTEGER NOT NULL,
                price_per_unit DECIMAL(20, 2) NOT NULL,
                total_price DECIMAL(20, 2) NOT NULL,
                traded_at LONG NOT NULL
            )
        """)
        
        // Indexes
        try {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_match ON sell_orders (item_hash, status, price_per_unit)")
        } catch (e: SQLException) {
            // Ignore
        }
        
        stmt.close()
    }

    fun close() {
        if (::connection.isInitialized && !connection.isClosed) {
            connection.close()
        }
    }

    // --- DAO Methods ---

    fun getCustomFeeRate(itemHash: String): BigDecimal? {
        val sql = "SELECT custom_fee_rate FROM market_items WHERE item_hash = ?"
        val ps = connection.prepareStatement(sql)
        ps.setString(1, itemHash)
        val rs = ps.executeQuery()
        var rate: BigDecimal? = null
        if (rs.next()) {
             rate = rs.getBigDecimal("custom_fee_rate")
        }
        rs.close()
        ps.close()
        return rate
    }

    fun setCustomFeeRate(itemHash: String, rate: BigDecimal?) {
        val sql = "UPDATE market_items SET custom_fee_rate = ? WHERE item_hash = ?"
        val ps = connection.prepareStatement(sql)
        ps.setBigDecimal(1, rate)
        ps.setString(2, itemHash)
        ps.executeUpdate()
        ps.close()
    }
    
    fun getItemsWithCustomFee(): List<com.github.cinnaio.order.model.FeeItem> {
        val sql = "SELECT item_hash, display_name, material_type, custom_fee_rate, serialized_data FROM market_items WHERE custom_fee_rate IS NOT NULL"
        val ps = connection.prepareStatement(sql)
        val rs = ps.executeQuery()
        val list = mutableListOf<com.github.cinnaio.order.model.FeeItem>()
        while (rs.next()) {
            val hash = rs.getString("item_hash")
            val name = rs.getString("display_name")
            val material = rs.getString("material_type")
            val rate = rs.getBigDecimal("custom_fee_rate")
            val serialized = rs.getString("serialized_data")
            list.add(com.github.cinnaio.order.model.FeeItem(hash, name, material, rate, serialized))
        }
        rs.close()
        ps.close()
        return list
    }

    fun saveMarketItem(item: MarketItem) {
        val sql = "INSERT OR IGNORE INTO market_items (item_hash, serialized_data, display_name, material_type) VALUES (?, ?, ?, ?)"
        val ps = connection.prepareStatement(sql)
        ps.setString(1, item.itemHash)
        ps.setString(2, item.serializedData)
        ps.setString(3, item.displayName)
        ps.setString(4, item.materialType)
        ps.executeUpdate()
        ps.close()
    }
    
    fun getSellOrdersByItem(itemHash: String): List<SellOrder> {
        val sql = "SELECT * FROM sell_orders WHERE item_hash = ? AND status = 'OPEN' ORDER BY price_per_unit ASC"
        val ps = connection.prepareStatement(sql)
        ps.setString(1, itemHash)
        val rs = ps.executeQuery()
        
        val orders = mutableListOf<SellOrder>()
        while (rs.next()) {
            orders.add(SellOrder(
                id = rs.getLong("id"),
                sellerUuid = UUID.fromString(rs.getString("seller_uuid")),
                sellerName = rs.getString("seller_name"),
                itemHash = rs.getString("item_hash"),
                pricePerUnit = rs.getBigDecimal("price_per_unit"),
                totalAmount = rs.getInt("total_amount"),
                remainingAmount = rs.getInt("remaining_amount"),
                status = OrderStatus.valueOf(rs.getString("status")),
                createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                version = rs.getInt("version")
            ))
        }
        
        rs.close()
        ps.close()
        return orders
    }

    fun getOrder(orderId: Long): SellOrder? {
        val sql = "SELECT * FROM sell_orders WHERE id = ?"
        val ps = connection.prepareStatement(sql)
        ps.setLong(1, orderId)
        val rs = ps.executeQuery()
        
        var order: SellOrder? = null
        if (rs.next()) {
            order = SellOrder(
                id = rs.getLong("id"),
                sellerUuid = UUID.fromString(rs.getString("seller_uuid")),
                sellerName = rs.getString("seller_name"),
                itemHash = rs.getString("item_hash"),
                pricePerUnit = rs.getBigDecimal("price_per_unit"),
                totalAmount = rs.getInt("total_amount"),
                remainingAmount = rs.getInt("remaining_amount"),
                status = OrderStatus.valueOf(rs.getString("status")),
                createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                version = rs.getInt("version")
            )
        }
        
        rs.close()
        ps.close()
        return order
    }

    fun getMarketOverview(limit: Int = 50, offset: Int = 0): List<MarketOverviewItem> {
        // 1. Get the items with basic stats (pagination applied here)
        val sql = """
            SELECT 
                mi.item_hash, 
                mi.serialized_data, 
                mi.display_name, 
                mi.material_type,
                MIN(so.price_per_unit) as min_price,
                SUM(so.remaining_amount) as total_stock
            FROM sell_orders so
            JOIN market_items mi ON so.item_hash = mi.item_hash
            WHERE so.status = 'OPEN'
            GROUP BY mi.item_hash
            ORDER BY total_stock DESC
            LIMIT ? OFFSET ?
        """
        val ps = connection.prepareStatement(sql)
        ps.setInt(1, limit)
        ps.setInt(2, offset)
        val rs = ps.executeQuery()
        
        val items = mutableListOf<MarketOverviewItem>()
        val itemHashes = mutableListOf<String>()
        
        while (rs.next()) {
            val hash = rs.getString("item_hash")
            items.add(MarketOverviewItem(
                itemHash = hash,
                serializedData = rs.getString("serialized_data"),
                displayName = rs.getString("display_name"),
                materialType = rs.getString("material_type"),
                minPrice = rs.getBigDecimal("min_price"),
                totalStock = rs.getInt("total_stock"),
                priceDistribution = emptyMap() // Fill later
            ))
            itemHashes.add(hash)
        }
        
        rs.close()
        ps.close()
        
        if (items.isEmpty()) {
            return items
        }
        
        // 2. Fetch price distribution for these items
        // Construct IN clause
        val placeholders = itemHashes.joinToString(",") { "?" }
        val distSql = """
            SELECT item_hash, price_per_unit, SUM(remaining_amount) as amount 
            FROM sell_orders 
            WHERE status = 'OPEN' AND item_hash IN ($placeholders)
            GROUP BY item_hash, price_per_unit
        """
        
        val psDist = connection.prepareStatement(distSql)
        itemHashes.forEachIndexed { index, hash ->
            psDist.setString(index + 1, hash)
        }
        
        val rsDist = psDist.executeQuery()
        val distMap = mutableMapOf<String, MutableMap<BigDecimal, Int>>()
        
        while (rsDist.next()) {
            val hash = rsDist.getString("item_hash")
            val price = rsDist.getBigDecimal("price_per_unit")
            val amount = rsDist.getInt("amount")
            
            distMap.computeIfAbsent(hash) { mutableMapOf() }[price] = amount
        }
        
        rsDist.close()
        psDist.close()
        
        // 3. Merge distribution into items
        return items.map { item ->
            val dist = distMap[item.itemHash] ?: emptyMap()
            // Sort by price ascending
            val sortedDist = dist.toSortedMap()
            item.copy(priceDistribution = sortedDist)
        }
    }
    
    fun getMarketItem(hash: String): MarketItem? {
        val sql = "SELECT * FROM market_items WHERE item_hash = ?"
        val ps = connection.prepareStatement(sql)
        ps.setString(1, hash)
        val rs = ps.executeQuery()
        
        return if (rs.next()) {
            MarketItem(
                rs.getString("item_hash"),
                rs.getString("serialized_data"),
                rs.getString("display_name"),
                rs.getString("material_type")
            )
        } else {
            null
        }.also {
            rs.close()
            ps.close()
        }
    }

    fun createSellOrder(order: SellOrder): Long {
        val sql = """
            INSERT INTO sell_orders 
            (seller_uuid, seller_name, item_hash, price_per_unit, total_amount, remaining_amount, status, created_at, version) 
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        val ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
        ps.setString(1, order.sellerUuid.toString())
        ps.setString(2, order.sellerName)
        ps.setString(3, order.itemHash)
        ps.setBigDecimal(4, order.pricePerUnit)
        ps.setInt(5, order.totalAmount)
        ps.setInt(6, order.remainingAmount)
        ps.setString(7, order.status.name)
        ps.setLong(8, order.createdAt.toEpochMilli())
        ps.setInt(9, order.version)
        
        ps.executeUpdate()
        val rs = ps.generatedKeys
        return if (rs.next()) {
            rs.getLong(1)
        } else {
            throw SQLException("Creating order failed, no ID obtained.")
        }.also {
            rs.close()
            ps.close()
        }
    }

    fun findBestMatches(itemHash: String): List<SellOrder> {
        // 价格升序，时间升序
        val sql = """
            SELECT * FROM sell_orders 
            WHERE item_hash = ? AND status = 'OPEN'
            ORDER BY price_per_unit ASC, created_at ASC
        """
        val ps = connection.prepareStatement(sql)
        ps.setString(1, itemHash)
        
        val rs = ps.executeQuery()
        val orders = mutableListOf<SellOrder>()
        
        while (rs.next()) {
            orders.add(mapResultSetToOrder(rs))
        }
        
        rs.close()
        ps.close()
        return orders
    }
    
    fun updateOrder(order: SellOrder): Boolean {
        // 乐观锁：检查 ID 和 Version
        val sql = """
            UPDATE sell_orders 
            SET remaining_amount = ?, status = ?, version = version + 1 
            WHERE id = ? AND version = ?
        """
        val ps = connection.prepareStatement(sql)
        ps.setInt(1, order.remainingAmount)
        ps.setString(2, order.status.name)
        ps.setLong(3, order.id)
        ps.setInt(4, order.version)
        
        val rows = ps.executeUpdate()
        ps.close()
        return rows > 0
    }
    
    fun logTransaction(tx: Transaction) {
        val sql = """
            INSERT INTO transactions 
            (buyer_uuid, buyer_name, seller_uuid, seller_name, order_id, item_hash, amount, price_per_unit, total_price, traded_at) 
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        val ps = connection.prepareStatement(sql)
        ps.setString(1, tx.buyerUuid.toString())
        ps.setString(2, tx.buyerName)
        ps.setString(3, tx.sellerUuid.toString())
        ps.setString(4, tx.sellerName)
        ps.setLong(5, tx.orderId)
        ps.setString(6, tx.itemHash)
        ps.setInt(7, tx.amount)
        ps.setBigDecimal(8, tx.pricePerUnit)
        ps.setBigDecimal(9, tx.totalPrice)
        ps.setLong(10, tx.tradedAt.toEpochMilli())
        
        ps.executeUpdate()
        ps.close()
    }
    
    // 获取玩家的卖单 (用于管理 GUI)
    fun getPlayerOrders(uuid: UUID): List<SellOrder> {
        val sql = "SELECT * FROM sell_orders WHERE seller_uuid = ? ORDER BY created_at DESC"
        val ps = connection.prepareStatement(sql)
        ps.setString(1, uuid.toString())
        val rs = ps.executeQuery()
        val orders = mutableListOf<SellOrder>()
        while (rs.next()) {
            orders.add(mapResultSetToOrder(rs))
        }
        rs.close()
        ps.close()
        return orders
    }

    private fun mapResultSetToOrder(rs: java.sql.ResultSet): SellOrder {
        return SellOrder(
            id = rs.getLong("id"),
            sellerUuid = UUID.fromString(rs.getString("seller_uuid")),
            sellerName = rs.getString("seller_name"),
            itemHash = rs.getString("item_hash"),
            pricePerUnit = rs.getBigDecimal("price_per_unit"),
            totalAmount = rs.getInt("total_amount"),
            remainingAmount = rs.getInt("remaining_amount"),
            status = OrderStatus.valueOf(rs.getString("status")),
            createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
            version = rs.getInt("version")
        )
    }
}
