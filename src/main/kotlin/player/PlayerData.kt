package twizzy.tech.player

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.minestom.server.inventory.PlayerInventory
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.utils.mojang.MojangUtils
import org.bson.Document
import twizzy.tech.util.MongoStream
import twizzy.tech.util.YamlFactory
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/**
 * Represents a player's persistent data and provides caching functionality.
 */
class PlayerData(
    val uuid: UUID,
    var balance: BigDecimal = BigDecimal.ZERO,
    var blocksMined: Int = 0,
    val inventory: MutableMap<String, InventoryItem> = mutableMapOf(),
    val blockpack: MutableMap<String, Int> = mutableMapOf(),
    val backpack: MutableMap<String, InventoryItem> = mutableMapOf(), // New backpack system
    var souls: BigDecimal = BigDecimal.ZERO, // New currency
    var tokens: BigDecimal = BigDecimal.ZERO, // New currency
    var rank: Int = 0,
    var kills: Int = 0,
    var deaths: Int = 0,
    var cellID: String? = null
) {
    /**
     * Represents an item in a player's inventory
     */
    data class InventoryItem(
        val id: String,
        var count: Int
    )


    /**
     * Converts this PlayerData instance to a MongoDB Document
     * Note: Inventory and blockpack are stored in YAML, not MongoDB
     */
    fun toDocument(): Document {
        return Document()
            .append("_id", uuid.toString())
            .append("balance", balance)
            .append("blocksMined", blocksMined)
            .append("souls", souls)
            .append("tokens", tokens)
            .append("rank", rank)
            .append("kills", kills)
            .append("deaths", deaths)
            .append("cellID", cellID)
    }

    companion object {
        private val playerCache = ConcurrentHashMap<UUID, PlayerData>()
        private const val PLAYERS_DIR = "players"
        private val mongo = MongoStream.getInstance()

        // File operation synchronization
        private val fileLocks = ConcurrentHashMap<UUID, Mutex>()

        private fun getFileLock(uuid: UUID): Mutex {
            return fileLocks.computeIfAbsent(uuid) { Mutex() }
        }

        /**
         * Gets player data from the cache, or loads from MongoDB if not present.
         * @param uuid Player UUID
         * @return PlayerData if found in cache or MongoDB, null if not found in either
         */
        fun getFromCache(uuid: UUID): PlayerData? {
            val cached = playerCache[uuid]
            if (cached != null) return cached
            // Try to load from MongoDB
            return try {
                val mongoStream = twizzy.tech.util.MongoStream.getInstance()
                val document = runBlocking { mongoStream.getPlayerData(uuid) }
                val playerData = fromDocument(document)
                saveToCache(playerData)
                playerData
            } catch (e: Exception) {
                println("Failed to load player data for UUID $uuid: ${e.message}")
                null
            }
        }

        /**
         * Finds player data from the cache or database without creating it if not present.
         * @param uuid Player UUID
         * @return PlayerData if found in cache or MongoDB, null if not found in either
         */
        suspend fun findFromCache(uuid: UUID): PlayerData? {
            val cached = playerCache[uuid]
            if (cached != null) return cached
            // Try to load from MongoDB, but don't create if not found
            return try {
                val mongoStream = twizzy.tech.util.MongoStream.getInstance()
                val document = mongoStream.findPlayerData(uuid) ?: return null
                val playerData = fromDocument(document)
                saveToCache(playerData)
                playerData
            } catch (e: Exception) {
                println("Failed to find player data for UUID $uuid: ${e.message}")
                null
            }
        }

        suspend fun findFromCache(username: String): PlayerData? {
            // Try to get UUID from username, return null if username is invalid
            val uuid = try {
                MojangUtils.getUUID(username)
            } catch (e: IOException) {
                println("Failed to get UUID for username '$username': ${e.message}")
                return null
            } catch (e: Exception) {
                println("Unexpected error getting UUID for username '$username': ${e.message}")
                return null
            }

            val cached = playerCache[uuid]
            if (cached != null) return cached
            // Try to load from MongoDB, but don't create if not found
            return try {
                val mongoStream = twizzy.tech.util.MongoStream.getInstance()
                val document = mongoStream.findPlayerData(uuid) ?: return null
                val playerData = fromDocument(document)
                saveToCache(playerData)
                playerData
            } catch (e: Exception) {
                println("Failed to find player data for UUID $uuid: ${e.message}")
                null
            }
        }

        /**
         * Saves player data to the cache
         * @param data PlayerData to save
         */
        fun saveToCache(data: PlayerData) {
            playerCache[data.uuid] = data
        }

        /**
         * Removes player data from the cache
         * @param uuid Player UUID
         * @return The removed PlayerData, or null if not present
         */
        fun removeFromCache(uuid: UUID): PlayerData? = playerCache.remove(uuid)

        /**
         * Creates PlayerData from a MongoDB Document
         * @param document MongoDB Document
         * @return PlayerData instance
         */
        fun fromDocument(document: Document): PlayerData {
            val uuid = UUID.fromString(document.getString("_id"))
            val balanceObj = document.get("balance")
            val balance = when (balanceObj) {
                is org.bson.types.Decimal128 -> balanceObj.bigDecimalValue()
                is Double -> BigDecimal.valueOf(balanceObj)
                is Int -> BigDecimal.valueOf(balanceObj.toLong())
                is Long -> BigDecimal.valueOf(balanceObj)
                is String -> balanceObj.toBigDecimalOrNull() ?: BigDecimal.ZERO
                else -> BigDecimal.ZERO
            }
            val blocksMined = document.getInteger("blocksMined") ?: 0
            val soulsObj = document.get("souls")
            val souls = when (soulsObj) {
                is org.bson.types.Decimal128 -> soulsObj.bigDecimalValue()
                is Double -> BigDecimal.valueOf(soulsObj)
                is Int -> BigDecimal.valueOf(soulsObj.toLong())
                is Long -> BigDecimal.valueOf(soulsObj)
                is String -> soulsObj.toBigDecimalOrNull() ?: BigDecimal.ZERO
                else -> BigDecimal.ZERO
            }
            val tokensObj = document.get("tokens")
            val tokens = when (tokensObj) {
                is org.bson.types.Decimal128 -> tokensObj.bigDecimalValue()
                is Double -> BigDecimal.valueOf(tokensObj)
                is Int -> BigDecimal.valueOf(tokensObj.toLong())
                is Long -> BigDecimal.valueOf(tokensObj)
                is String -> tokensObj.toBigDecimalOrNull() ?: BigDecimal.ZERO
                else -> BigDecimal.ZERO
            }

            val rank = document.getInteger("rank") ?: 0
            val kills = document.getInteger("kills") ?: 0
            val deaths = document.getInteger("deaths") ?: 0
            val cellID = document.getString("cellID")

            return PlayerData(
                uuid,
                balance,
                blocksMined,
                mutableMapOf(),
                mutableMapOf(),
                mutableMapOf(),
                souls,
                tokens,
                rank,
                kills,
                deaths,
                cellID
            )
        }

        /**
         * Sets a player's balance
         * @param uuid Player's UUID
         * @param amount The new balance amount
         * @return true if successful, false if player data not found
         */
        suspend fun setBalance(uuid: UUID, amount: BigDecimal): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.balance = amount
            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Adds to a player's balance
         * @param uuid Player's UUID
         * @param amount The amount to add (can be negative to subtract)
         * @return true if successful, false if player data not found
         */
        suspend fun addBalance(uuid: UUID, amount: BigDecimal): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.balance = playerData.balance.add(amount)
            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Increments the blocks mined count for a player
         * @param uuid Player's UUID
         * @param amount Amount of blocks to add to count (defaults to 1)
         * @return true if successful, false if player data not found
         */
        suspend fun incrementBlocksMined(uuid: UUID, amount: Int = 1): Boolean {
            val playerData = getFromCache(uuid) ?: return false

            val oldBlocksMined = playerData.blocksMined
            playerData.blocksMined += amount
            val newBlocksMined = playerData.blocksMined

            return true
        }

        /**
         * Gets a player's current balance
         * @param username Player's username
         * @return The player's balance, or null if player data not found or username is invalid
         */
        suspend fun getBalance(username: String): BigDecimal? {
            return findFromCache(username)?.balance
        }

        /**
         * Gets a player's blocks mined count
         * @param uuid Player's UUID
         * @return The player's blocks mined count, or null if player data not found
         */
        suspend fun getBlocksMined(uuid: UUID): Int? = getFromCache(uuid)?.blocksMined

        /**
         * Sets an item in a player's inventory
         * @param uuid Player's UUID
         * @param slot The inventory slot (as a string)
         * @param itemId The Minecraft item ID
         * @param count The item count
         * @return true if successful, false if player data not found
         */
        suspend fun setInventoryItem(uuid: UUID, slot: String, itemId: String, count: Int): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.inventory[slot] = InventoryItem(itemId, count)
            return true
        }

        /**
         * Removes an item from a player's inventory
         * @param uuid Player's UUID
         * @param slot The inventory slot
         * @return true if successful, false if player data not found
         */
        suspend fun removeInventoryItem(uuid: UUID, slot: String): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.inventory.remove(slot)
            return true
        }

        /**
         * Gets a player's entire inventory
         * @param uuid Player's UUID
         * @return Map of slot to InventoryItem, or null if player data not found
         */
        suspend fun getInventory(uuid: UUID): Map<String, InventoryItem>? = getFromCache(uuid)?.inventory

        /**
         * Gets an item from a player's inventory
         * @param uuid Player's UUID
         * @param slot The inventory slot
         * @return The InventoryItem, or null if not found
         */
        suspend fun getInventoryItem(uuid: UUID, slot: String): InventoryItem? =
            getFromCache(uuid)?.inventory?.get(slot)

        /**
         * Adds an item to the player's blockpack
         * @param uuid Player's UUID
         * @param itemId The Minecraft item ID
         * @param amount The amount to add
         * @return true if successful, false if player data not found
         */
        suspend fun addToBlockpack(uuid: UUID, itemId: String, amount: Int): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            val currentAmount = playerData.blockpack.getOrDefault(itemId, 0)
            playerData.blockpack[itemId] = currentAmount + amount
            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Removes an item from the player's blockpack
         * @param uuid Player's UUID
         * @param itemId The Minecraft item ID
         * @param amount The amount to remove
         * @return true if successful, false if player data not found or insufficient items
         */
        suspend fun removeFromBlockpack(uuid: UUID, itemId: String, amount: Int): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            val currentAmount = playerData.blockpack.getOrDefault(itemId, 0)

            if (currentAmount < amount) {
                return false
            }

            val newAmount = currentAmount - amount
            if (newAmount > 0) {
                playerData.blockpack[itemId] = newAmount
            } else {
                playerData.blockpack.remove(itemId)
            }

            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Gets a player's blockpack contents
         * @param uuid Player's UUID
         * @return Map of item ID to count, or null if player data not found
         */
        suspend fun getBlockpack(uuid: UUID): Map<String, Int>? = getFromCache(uuid)?.blockpack

        /**
         * Gets the count of a specific item in a player's blockpack
         * @param uuid Player's UUID
         * @param itemId The Minecraft item ID
         * @return The item count, or 0 if player data not found or item not in blockpack
         */
        suspend fun getBlockpackAmount(uuid: UUID, itemId: String): Int =
            getFromCache(uuid)?.blockpack?.getOrDefault(itemId, 0) ?: 0

        // === TOKENS MANAGEMENT ===

        /**
         * Sets a player's tokens
         * @param uuid Player's UUID
         * @param amount The new tokens amount
         * @return true if successful, false if player data not found
         */
        suspend fun setTokens(uuid: UUID, amount: BigDecimal): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.tokens = amount
            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Adds to a player's tokens
         * @param uuid Player's UUID
         * @param amount The amount to add (can be negative to subtract)
         * @return true if successful, false if player data not found
         */
        suspend fun addTokens(uuid: UUID, amount: BigDecimal): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.tokens = playerData.tokens.add(amount)
            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Gets a player's current tokens
         * @param uuid Player's UUID
         * @return The player's tokens, or null if player data not found
         */
        suspend fun getTokens(uuid: UUID): BigDecimal? = getFromCache(uuid)?.tokens

        // === SOULS MANAGEMENT ===

        /**
         * Sets a player's souls
         * @param uuid Player's UUID
         * @param amount The new souls amount
         * @return true if successful, false if player data not found
         */
        suspend fun setSouls(uuid: UUID, amount: BigDecimal): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.souls = amount
            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Adds to a player's souls
         * @param uuid Player's UUID
         * @param amount The amount to add (can be negative to subtract)
         * @return true if successful, false if player data not found
         */
        suspend fun addSouls(uuid: UUID, amount: BigDecimal): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.souls = playerData.souls.add(amount)
            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Gets a player's current souls
         * @param uuid Player's UUID
         * @return The player's souls, or null if player data not found
         */
        suspend fun getSouls(uuid: UUID): BigDecimal? = getFromCache(uuid)?.souls

        /**
         * Sells all items in a player's blockpack and adds the value to their balance
         * @param uuid Player's UUID
         * @return SellResult containing success status, total value, items sold, and error message if any
         */
        suspend fun sellBlockpack(uuid: UUID): SellResult {
            val playerData = getFromCache(uuid) ?: return SellResult(
                success = false,
                totalValue = BigDecimal.ZERO,
                itemsSold = 0,
                errorMessage = "Unable to load your player data."
            )

            val blockpack = playerData.blockpack

            if (blockpack.isEmpty()) {
                return SellResult(
                    success = false,
                    totalValue = BigDecimal.ZERO,
                    itemsSold = 0,
                    errorMessage = "Your blockpack is empty - nothing to sell!"
                )
            }

            // Calculate total sell value using the game engine
            val totalValue = twizzy.tech.gameEngine.calculateTotalSellValue(blockpack)

            if (totalValue <= BigDecimal.ZERO) {
                return SellResult(
                    success = false,
                    totalValue = BigDecimal.ZERO,
                    itemsSold = 0,
                    errorMessage = "No valuable items found in your blockpack."
                )
            }

            // Calculate total items being sold
            val totalItems = blockpack.values.sum()

            // Create a backup of the blockpack for rollback
            val blockpackBackup = blockpack.toMap()

            try {
                // Add to player's balance
                playerData.balance = playerData.balance.add(totalValue)

                // Clear the blockpack
                blockpack.clear()

                // Save the updated data
                val mongoStream = MongoStream.getInstance()
                mongoStream.savePlayerData(playerData)
                saveBlockpack(uuid)

                // Update scoreboard automatically (balance and blockpack count changed)
                twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)

                return SellResult(
                    success = true,
                    totalValue = totalValue,
                    itemsSold = totalItems,
                    newBalance = playerData.balance
                )

            } catch (e: Exception) {
                // Rollback the changes if saving fails
                playerData.balance = playerData.balance.subtract(totalValue)
                blockpack.clear()
                blockpack.putAll(blockpackBackup)

                return SellResult(
                    success = false,
                    totalValue = BigDecimal.ZERO,
                    itemsSold = 0,
                    errorMessage = "Failed to save your data after selling. Transaction cancelled.",
                    exception = e
                )
            }
        }

        /**
         * Result of a blockpack sell operation
         */
        data class SellResult(
            val success: Boolean,
            val totalValue: BigDecimal,
            val itemsSold: Int,
            val errorMessage: String? = null,
            val newBalance: BigDecimal? = null,
            val exception: Exception? = null
        )

        /**
         * Saves player inventory data to YAML file directly from a PlayerInventory
         * @param uuid Player UUID
         * @param inventory The player's inventory object
         * @return true if successful, false otherwise
         */
        suspend fun saveInventory(uuid: UUID, inventory: PlayerInventory): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            val playerFile = File("$PLAYERS_DIR/${uuid}.yml")

            // Ensure the players directory exists
            File(PLAYERS_DIR).mkdirs()

            try {
                // Load existing data if player file exists
                val yamlData = if (playerFile.exists()) {
                    YamlFactory.loadConfig(playerFile).toMutableMap()
                } else {
                    mutableMapOf()
                }

                // Convert inventory to YAML format
                val inventoryData = mutableMapOf<String, Any>()

                // Process main inventory slots
                for (i in 0 until inventory.size) {
                    val item = inventory.getItemStack(i)
                    if (!item.isAir) {
                        try {
                            // Get the NBT data for the item
                            val nbt = item.toItemNBT()

                            // Write the NBT data to a binary format
                            val outputStream = java.io.ByteArrayOutputStream()
                            BinaryTagIO.writer().write(nbt, outputStream)
                            val binaryData = outputStream.toByteArray()

                            // Encode the binary data to Base64 for safe storage
                            val base64Data = java.util.Base64.getEncoder().encodeToString(binaryData)

                            inventoryData[i.toString()] = base64Data
                        } catch (e: Exception) {
                            println("Error serializing item in slot $i: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }

                // Save to file
                yamlData["inventory"] = inventoryData
                YamlFactory.saveConfig(yamlData, playerFile)
                return true
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }

        /**
         * Loads player inventory from YAML file and creates a PlayerInventory object
         * @param uuid Player UUID
         * @return PlayerInventory with items loaded from YAML, or an empty inventory if loading fails
         */
        @Suppress("UNCHECKED_CAST")
        suspend fun loadInventory(uuid: UUID): PlayerInventory {
            // Create a new inventory
            val playerInventory = PlayerInventory()

            // Attempt to load player data from cache
            val playerData = getFromCache(uuid) ?: return playerInventory // Return empty inventory if no player data

            val playerFile = File("$PLAYERS_DIR/${uuid}.yml")
            if (!playerFile.exists()) {
                return playerInventory // Return empty inventory if no file exists
            }

            try {
                val yamlData = YamlFactory.loadConfig(playerFile)

                // Load inventory data
                val inventoryData = yamlData["inventory"] as? Map<String, Any>

                // Process inventory data from YAML
                inventoryData?.forEach { (slot, nbtString) ->
                    try {
                        val slotIndex = slot.toInt()

                        // Skip invalid slots
                        if (slotIndex < 0 || slotIndex >= playerInventory.size) {
                            return@forEach
                        }

                        // Parse the NBT string and deserialize to ItemStack
                        if (nbtString is String) {
                            try {
                                try {
                                    // Check if it's Base64 encoded (new format)
                                    val binaryData = try {
                                        java.util.Base64.getDecoder().decode(nbtString)
                                    } catch (e: IllegalArgumentException) {
                                        // If decoding fails, it might be the old string format
                                        null
                                    }

                                    val nbt = if (binaryData != null) {
                                        // Use the binary data directly
                                        BinaryTagIO.reader().read(java.io.ByteArrayInputStream(binaryData))
                                    } else {
                                        // Try the legacy string format as fallback
                                        BinaryTagIO.reader().read(nbtString.byteInputStream())
                                    }

                                    val itemStack = ItemStack.fromItemNBT(nbt as CompoundBinaryTag)

                                    // Set the item in the inventory
                                    playerInventory.setItemStack(slotIndex, itemStack)
                                } catch (e: IllegalArgumentException) {
                                    println("Format error in NBT data for slot $slot: ${e.message}")
                                } catch (e: Exception) {
                                    println("Error deserializing NBT for item in slot $slot: ${e.message}")
                                    e.printStackTrace() // Print full stack trace for debugging
                                }
                            } catch (e: Exception) {
                                println("Error processing NBT string for slot $slot: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                    } catch (e: NumberFormatException) {
                        // Skip invalid slot numbers
                        println("Invalid slot number in inventory data: $slot")
                    } catch (e: Exception) {
                        println("Error processing item in slot $slot: ${e.message}")
                    }
                }

                return playerInventory
            } catch (e: Exception) {
                e.printStackTrace()
                return playerInventory // Return empty inventory in case of error
            }
        }

        /**
         * Loads player blockpack data from YAML file
         * @param uuid Player UUID
         * @return true if successful, false otherwise
         */
        @Suppress("UNCHECKED_CAST")
        suspend fun loadBlockpack(uuid: UUID): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            val playerFile = File("$PLAYERS_DIR/${uuid}.yml")

            if (!playerFile.exists()) {
                // No data file yet, that's okay
                return true
            }

            try {
                val yamlData = YamlFactory.loadConfig(playerFile)

                // Load blockpack
                val blockpackData = yamlData["blockpack"] as? Map<String, Any>
                playerData.blockpack.clear() // Clear existing blockpack before loading
                blockpackData?.forEach { (itemId, countObj) ->
                    val count = when (countObj) {
                        is Int -> countObj
                        is Double -> countObj.toInt()
                        is String -> countObj.toIntOrNull() ?: 0
                        else -> 0
                    }
                    playerData.blockpack[itemId] = count
                }

                return true
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }

        /**
         * Saves player blockpack data to YAML file
         * @param uuid Player UUID
         * @return true if successful, false otherwise
         */
        suspend fun saveBlockpack(uuid: UUID): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            val playerFile = File("$PLAYERS_DIR/${uuid}.yml")

            // Ensure the players directory exists
            File(PLAYERS_DIR).mkdirs()

            try {
                // Load existing data if file exists
                val yamlData = if (playerFile.exists()) {
                    YamlFactory.loadConfig(playerFile).toMutableMap()
                } else {
                    mutableMapOf()
                }

                // Update blockpack data
                yamlData["blockpack"] = playerData.blockpack

                // Save to file
                YamlFactory.saveConfig(yamlData, playerFile)
                return true
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }

        /**
         * Retrieves all player data currently stored in the cache
         * Used primarily for server shutdown to save all data
         * @return List of all PlayerData objects in cache
         */
        fun getAllCachedData(): List<PlayerData> {
            return playerCache.values.toList()
        }

        enum class LeaderboardType { Balance, Souls, Tokens, Rank, Blocks }

        // Leaderboard cache and timestamps (moved out of nested companion object)
        private const val LEADERBOARD_SIZE = 10
        private const val CACHE_DURATION_MS = 10 * 60 * 1000L // 10 minutes
        private val leaderboardCache = mutableMapOf<LeaderboardType, List<Pair<UUID, Number>>>()
        private val leaderboardTimestamps = mutableMapOf<LeaderboardType, Long>()

        // --- Leaderboard config helpers ---
        private fun getLeaderboardConfig(): Map<String, Any> {
            val config = YamlFactory.loadConfig(File("game/config.yml"))
            return config["leaderboard"] as? Map<String, Any> ?: emptyMap()
        }

        private fun getLeaderboardRefreshIntervalMs(): Long {
            val intervalStr = getLeaderboardConfig()["refreshInterval"]?.toString() ?: "10m"
            val seconds = twizzy.tech.util.DurationParser.parse(intervalStr) ?: 600L
            return seconds * 1000L
        }

        fun getLeaderboardPerPage(): Int = (getLeaderboardConfig()["perPage"] as? Int) ?: 10
        fun getLeaderboardMaxPages(): Int = (getLeaderboardConfig()["maxPages"] as? Int) ?: 10

        /**
         * Refreshes the leaderboard cache and timestamp for the specified type.
         */
        private suspend fun refreshLeaderboard(type: LeaderboardType) {

            // Upload the current cached player data to MongoDB
            getAllCachedData().forEach { playerData ->
                mongo.savePlayerData(playerData)
            }

            val perPage = getLeaderboardPerPage()
            val maxPages = getLeaderboardMaxPages()
            val field = when (type) {
                LeaderboardType.Balance -> "balance"
                LeaderboardType.Souls -> "souls"
                LeaderboardType.Tokens -> "tokens"
                LeaderboardType.Rank -> "rank"
                LeaderboardType.Blocks -> "blocksMined"
            }
            val collection = mongo.getPlayersCollection()
            val leaderboard = mutableListOf<Pair<UUID, Number>>()
            try {
                collection.find()
                    .sort(Document(field, -1))
                    .limit(perPage * maxPages)
                    .asFlow()
                    .collect { doc ->
                        val id = doc.getString("_id")
                        val value = doc.get(field)
                        val uuid = try {
                            UUID.fromString(id)
                        } catch (_: Exception) {
                            null
                        }
                        if (uuid != null && value is Number) {
                            leaderboard.add(uuid to value)
                        }
                    }
            } catch (e: Exception) {
                println("[PrisonCore/Leaderboard] Failed to refresh leaderboard: " + e.message)
            }
            leaderboardCache[type] = leaderboard
            leaderboardTimestamps[type] = System.currentTimeMillis()
        }

        /**
         * Gets the leaderboard for the specified type, using cache if fresh.
         * @param type The leaderboard type
         * @param page The page number (1-based)
         * @return List of pairs (UUID, value) sorted descending for the page
         */
        suspend fun getLeaderboard(type: LeaderboardType, page: Int = 1): List<Pair<UUID, Number>> {
            val now = System.currentTimeMillis()
            val refreshMs = getLeaderboardRefreshIntervalMs()
            val perPage = getLeaderboardPerPage()
            val maxPages = getLeaderboardMaxPages()
            val lastRefresh = leaderboardTimestamps[type] ?: 0L
            if (now - lastRefresh >= refreshMs || leaderboardCache[type] == null) {
                refreshLeaderboard(type)
            }
            val cached = leaderboardCache[type] ?: emptyList()
            val safePage = page.coerceIn(1, maxPages)
            val from = (safePage - 1) * perPage
            val to = (from + perPage).coerceAtMost(cached.size)
            return if (from < to) cached.subList(from, to) else emptyList()
        }

        /**
         * Forces a refresh of the specified leaderboard type, ignoring the cache.
         */
        suspend fun forceRefreshLeaderboard(type: LeaderboardType) {
            refreshLeaderboard(type)
        }

        /**
         * Returns milliseconds until the next refresh for the given leaderboard type.
         * Returns 0 if the cache is expired or never set.
         */
        fun getNextLeaderboardRefresh(type: LeaderboardType): Long {
            val refreshMs = getLeaderboardRefreshIntervalMs()
            val lastRefresh = leaderboardTimestamps[type] ?: 0L
            val now = System.currentTimeMillis()
            val nextRefresh = (lastRefresh + refreshMs) - now
            return nextRefresh.coerceAtLeast(0)
        }

        // === KILLS AND DEATHS MANAGEMENT ===

        /**
         * Increments a player's kill count
         * @param uuid Player's UUID
         * @param amount Amount of kills to add (defaults to 1)
         * @return true if successful, false if player data not found
         */
        suspend fun addKills(uuid: UUID, amount: Int = 1): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.kills += amount
            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Increments a player's death count
         * @param uuid Player's UUID
         * @param amount Amount of deaths to add (defaults to 1)
         * @return true if successful, false if player data not found
         */
        suspend fun addDeaths(uuid: UUID, amount: Int = 1): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.deaths += amount
            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Gets a player's kill count
         * @param uuid Player's UUID
         * @return The player's kills, or null if player data not found
         */
        suspend fun getKills(uuid: UUID): Int? = getFromCache(uuid)?.kills

        /**
         * Gets a player's death count
         * @param uuid Player's UUID
         * @return The player's deaths, or null if player data not found
         */
        suspend fun getDeaths(uuid: UUID): Int? = getFromCache(uuid)?.deaths

        /**
         * Gets a player's kill/death ratio
         * @param uuid Player's UUID
         * @return The player's K/D ratio, or null if player data not found
         */
        suspend fun getKDRatio(uuid: UUID): Double? {
            val playerData = getFromCache(uuid) ?: return null
            return if (playerData.deaths == 0) {
                playerData.kills.toDouble()
            } else {
                playerData.kills.toDouble() / playerData.deaths.toDouble()
            }
        }

        // === CELL MANAGEMENT ===

        /**
         * Sets a player's cell ID
         * @param uuid Player's UUID
         * @param cellID The cell ID to set (null to remove from cell)
         * @return true if successful, false if player data not found
         */
        suspend fun setCellID(uuid: UUID, cellID: String?): Boolean {
            val playerData = getFromCache(uuid) ?: return false
            playerData.cellID = cellID
            // Update scoreboard automatically
            twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(uuid)
            return true
        }

        /**
         * Gets a player's cell ID
         * @param uuid Player's UUID
         * @return The player's cell ID, or null if player data not found or not in a cell
         */
        suspend fun getCellID(uuid: UUID): String? = getFromCache(uuid)?.cellID
        }
}























































































































