package twizzy.tech.game

import com.github.shynixn.mccoroutine.minestom.scope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block
import org.slf4j.LoggerFactory
import twizzy.tech.util.Worlds
import twizzy.tech.util.YamlFactory
import java.io.File
import java.util.*

class MineManager(private val worlds: Worlds, private val minecraftServer: MinecraftServer, private var regionManager: RegionManager) {
    private val logger = LoggerFactory.getLogger(MineManager::class.java)

    // Store mines by world name and then by mine name
    private val mines = mutableMapOf<String, MutableMap<String, Mine>>()

    // Track scheduled reset jobs for each mine
    private val mineResetJobs = mutableMapOf<String, MutableMap<String, kotlinx.coroutines.Job>>()


    /**
     * Data class representing a mine
     */
    data class Mine(
        var name: String,
        val regionId: UUID,
        var blocks: MutableList<MineBlock> = mutableListOf(),
        var lastReset: Long = System.currentTimeMillis(),
        var resetInterval: Long? = 900 // Default reset interval in seconds (15 minutes)
    )

    /**
     * Data class representing a block type in a mine
     */
    data class MineBlock(
        val block: String, // Block type (like "STONE", "COAL_ORE", etc.)
    )

    init {
        runBlocking { loadAllMines() }
    }

    /**
     * Start the mine creation process for a player
     */
    suspend fun startMineCreation(player: Player): Boolean {
        val worldName = worlds.getWorldNameFromInstance(player.instance)
        val mineName = "${worldName}_mine"

        // Check if a mine with this name already exists
        if (mines[worldName]?.containsKey(mineName) == true) {
            player.sendMessage(
                Component.text("A mine already exists in this world.")
                    .color(NamedTextColor.RED)
            )
            return false
        }

        // Start region claiming process
        val success = regionManager?.startRegionClaiming(player, mineName) ?: false

        if (success) {
            player.sendMessage(
                Component.text("Started mine creation. Please select the boundaries of your mine.")
                    .color(NamedTextColor.GREEN)
            )

            player.sendMessage(
                Component.text("Left-click to set position 1, right-click to set position 2.")
                    .color(NamedTextColor.YELLOW)
            )

            player.sendMessage(
                Component.text("Shift + left-click to confirm your selection.")
                    .color(NamedTextColor.YELLOW)
            )
        }

        return success
    }

    /**
     * Complete mine creation process after region has been created
     * This is called from the RegionManager after a claiming process is completed
     */
    suspend fun finalizeMineCreation(worldName: String, regionName: String, regionId: UUID): Mine? {
        // Create the mine object with a default stone block and default interval
        val mine = Mine(regionName, regionId, resetInterval = 900)
        mine.blocks.add(MineBlock("STONE"))

        // Set the vertical and break flag on the region
        regionManager?.toggleRegionFlag(worldName, regionId, "vertical")
        regionManager?.toggleRegionFlag(worldName, regionId, "break")

        // Store the mine
        if (worldName !in mines) {
            mines[worldName] = mutableMapOf()
        }
        mines[worldName]!![regionName] = mine

        // Save mines to disk
        saveMines(worldName)

        // Start reset timer for this mine
        scheduleMineReset(worldName, regionName, mine.resetInterval ?: 900)

        logger.info("Created mine '$regionName' in world '$worldName'")
        return mine
    }

    /**
     * Get a mine by name
     */
    suspend fun getMine(worldName: String?, mineName: String): Mine? {
        return mines[worldName]?.get(mineName)
    }

    /**
     * List all mines in a world
     */
    suspend fun listMines(worldName: String): List<Mine> {
        return mines[worldName]?.values?.toList() ?: emptyList()
    }

    /**
     * Delete a mine by name
     */
    suspend fun deleteMine(worldName: String?, mineName: String): Boolean {
        // Check if mine exists
        val mine = mines[worldName]?.get(mineName) ?: return false

        // Delete associated region
        regionManager.deleteRegion(worldName, mine.regionId)

        // Remove mine from cache
        mines[worldName]?.remove(mineName)

        // Save changes
        saveMines(worldName)

        logger.info("Deleted mine '$mineName' from world '$worldName'")
        return true
    }

    /**
     * Set blocks for a mine
     */
    suspend fun setMineBlocks(worldName: String?, mineName: String, blockTypes: List<String>): Boolean {
        // Get the mine
        val mine = mines[worldName]?.get(mineName) ?: return false

        // Update blocks
        mine.blocks.clear()

        // If no valid blocks were provided, add stone as default
        if (blockTypes.isEmpty()) {
            mine.blocks.add(MineBlock("STONE"))
        } else {
            // Add the blocks
            blockTypes.forEach { blockType ->
                mine.blocks.add(MineBlock(blockType))
            }
        }

        // Save changes
        saveMines(worldName)

        return true
    }

    /**
     * Reset a specific mine
     */
    suspend fun resetMine(worldName: String?, mineName: String): Boolean {
        // Get mine
        val mine = mines[worldName]?.get(mineName) ?: return false

        // Get region
        val region = regionManager.getRegion(worldName, mine.regionId) ?: return false

        // Get instance
        val instance = worlds.getWorld(worldName) ?: return false

        // Get region bounds
        val (min, max) = region.getMinMaxPositions()

        // Fill the region with blocks from the mine's block list
        fillMineWithBlocks(instance, min, max, mine.blocks)

        // Update last reset timestamp
        mine.lastReset = System.currentTimeMillis()
        saveMines(worldName)

        return true
    }

    /**
     * Fill a region with blocks from the mine's block list
     */
    private suspend fun fillMineWithBlocks(
        instance: net.minestom.server.instance.Instance,
        min: Pos,
        max: Pos,
        blocks: List<MineBlock>
    ) {
        val minX = min.blockX()
        val minY = min.blockY()
        val minZ = min.blockZ()
        val maxX = max.blockX()
        val maxY = max.blockY()
        val maxZ = max.blockZ()

        val random = Random()
        val blockList = if (blocks.isEmpty()) listOf(MineBlock("STONE")) else blocks

        // Use coroutines to process layers in parallel for better performance
        kotlinx.coroutines.coroutineScope {
            for (y in minY..maxY) {
                launch {
                    for (x in minX..maxX) {
                        for (z in minZ..maxZ) {
                            val selectedBlock = blockList[random.nextInt(blockList.size)]
                            val blockMaterial = Block.fromKey(selectedBlock.block)
                            if (blockMaterial != null) {
                                instance.setBlock(x, y, z, blockMaterial)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Get the file where mines are stored for a world
     */
    private suspend fun getMinesFile(worldName: String?): File {
        val worldDir = File(worlds.worldsDir, worldName)
        if (!worldDir.exists()) {
            worldDir.mkdir()
        }
        return File(worldDir, "mines.yml")
    }

    /**
     * Save all mines for a world to storage
     */
    private suspend fun saveMines(worldName: String?) {
        // Check if worldName is a UUID
        if (runCatching { UUID.fromString(worldName) }.isSuccess) {
            logger.debug("Skipping save for world $worldName as it is a UUID")
            return
        }

        val worldMines = mines[worldName] ?: return
        val minesFile = getMinesFile(worldName)

        // Convert mines to a map for YAML storage
        val minesData = mutableMapOf<String, Any>()
        val minesList = mutableListOf<Map<String, Any>>()

        worldMines.values.forEach { mine ->
            val mineMap = mutableMapOf<String, Any>(
                "name" to mine.name,
                "regionId" to mine.regionId.toString(),
                "lastReset" to mine.lastReset,
                "resetInterval" to (mine.resetInterval ?: 900L)
            )

            // Convert blocks to list of maps
            val blocksList = mine.blocks.map { block ->
                mapOf("block" to block.block)
            }

            mineMap["blocks"] = blocksList
            minesList.add(mineMap)
        }

        minesData["mines"] = minesList

        // Save to YAML
        YamlFactory.saveConfig(minesData, minesFile)
        logger.debug("Saved ${worldMines.size} mines for world $worldName")
    }

    /**
     * Load all mines from storage
     */
    private suspend fun loadAllMines() {
        // Clear existing mines
        mines.clear()

        // For each world directory
        worlds.worldsDir.listFiles()?.filter { it.isDirectory }?.forEach { worldDir ->
            val worldName = worldDir.name
            val minesFile = File(worldDir, "mines.yml")

            // Skip if the mines file doesn't exist
            if (!minesFile.exists()) {
                return@forEach
            }

            try {
                // Load YAML
                val minesData = YamlFactory.loadConfig(minesFile)

                // Initialize the mines map for this world
                mines[worldName] = mutableMapOf()

                // Get the list of mines
                @Suppress("UNCHECKED_CAST")
                val minesList = minesData.getOrDefault("mines", emptyList<Map<String, Any>>()) as List<Map<String, Any>>

                // Parse each mine
                minesList.forEach { mineData ->
                    try {
                        val name = mineData["name"] as String
                        val regionId = UUID.fromString(mineData["regionId"] as String)
                        val lastReset = (mineData["lastReset"] as? Number)?.toLong() ?: System.currentTimeMillis()
                        val resetInterval = (mineData["resetInterval"] as? Number)?.toLong() ?: 900L

                        // Parse blocks
                        val blocks = mutableListOf<MineBlock>()
                        @Suppress("UNCHECKED_CAST")
                        val blocksList = mineData.getOrDefault("blocks", emptyList<Map<String, Any>>()) as List<Map<String, Any>>

                        blocksList.forEach { blockData ->
                            val blockType = blockData["block"] as String
                            blocks.add(MineBlock(blockType))
                        }

                        // Create mine object
                        val mine = Mine(name, regionId, blocks, lastReset, resetInterval)

                        // Add to our map
                        mines[worldName]!![name] = mine

                        resetMine(worldName, name)
                        scheduleMineReset(worldName, name, resetInterval)

                    } catch (e: Exception) {
                        logger.error("Failed to load mine from mines.yml: ${e.message}")
                    }
                }
                logger.info("Loaded ${mines[worldName]?.size ?: 0} mines from world '$worldName'")

            } catch (e: Exception) {
                logger.error("Failed to load mines from file ${minesFile.name}", e)
            }
        }

        logger.info("Loaded mines for ${mines.size} worlds")
    }

    suspend fun setMineInterval(worldName: String?, mineName: String, intervalSeconds: Long): Boolean {
        val mine = mines[worldName]?.get(mineName) ?: return false
        mine.resetInterval = intervalSeconds
        saveMines(worldName)
        scheduleMineReset(worldName, mineName, intervalSeconds)
        return true
    }

    suspend fun scheduleMineReset(worldName: String?, mineName: String, intervalSeconds: Long) {
        val worldJobs = mineResetJobs.getOrPut(worldName as String) { mutableMapOf() }
        worldJobs[mineName]?.cancel()
        val job = minecraftServer.scope.launch {
            while (true) {
                kotlinx.coroutines.delay(intervalSeconds * 1000)
                val mine = mines[worldName]?.get(mineName) ?: continue
                val region = regionManager?.getRegion(worldName, mine.regionId) ?: continue
                val instance = worlds.getWorld(worldName) ?: continue
                val totalVolume = region.getVolume()
                val nonAirBlocks = region.getNonAirBlockCount(instance)
                val percentMined = 1.0 - (nonAirBlocks.toDouble() / totalVolume.toDouble())
                if (percentMined >= 0.3) {
                    resetMine(worldName, mineName)
                }
            }
        }
        worldJobs[mineName] = job
    }

    /**
     * Sets a mine for a specific world (in-memory only, not persisted)
     */
    suspend fun setMine(worldName: String, mineName: String, mine: Mine) {
        if (mines[worldName] == null) {
            mines[worldName] = mutableMapOf()
        }

        mines[worldName]!![mineName] = mine


        resetMine(worldName, mineName)
        scheduleMineReset(worldName, mineName, mine.resetInterval ?: 900L)
    }

    private fun Double.format(digits: Int) = "% .${digits}f".format(this)
}