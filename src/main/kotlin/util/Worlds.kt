package twizzy.tech.util

import net.hollowcube.polar.PolarLoader
import net.kyori.adventure.key.Key
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.LightingChunk
import net.minestom.server.instance.block.Block
import net.minestom.server.registry.DynamicRegistry
import net.minestom.server.registry.RegistryKey
import net.minestom.server.world.DimensionType
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

class Worlds(private var instanceMap: InstanceMap? = null) {
    // Method to set RegionManager after construction (to resolve circular dependency)
    fun setInstanceMap(instanceMap: InstanceMap) {
        this.instanceMap = instanceMap
    }


    val logger = LoggerFactory.getLogger(Worlds::class.java)
    val worldsDir = File("worlds").apply {
        if (!exists()) mkdir()
    }

    // Cache for loaded world instances
    val loadedWorlds = mutableMapOf<String, InstanceContainer>()

    val fullbright = DimensionType.builder().ambientLight(1.0f).respawnAnchorWorks(true).build()
    val fullbrightKey: RegistryKey<DimensionType?> =
        MinecraftServer.getDimensionTypeRegistry().register(Key.key("fullbright"), fullbright)

    /**
     * Gets the spawn world instance, creating it if it doesn't exist.
     * @return The spawn world instance
     */
    suspend fun getSpawnWorld(): InstanceContainer {
        return getWorld("Spawn") ?: createWorld("Spawn").also {
            loadedWorlds["Spawn"] = it
        }
    }

    /**
     * Checks if a world exists
     * @param name The name of the world to check
     * @return true if the world exists, false otherwise
     */
    suspend fun worldExists(name: String): Boolean {
        return File(worldsDir, name).exists()
    }

    /**
     * Lists all available worlds
     * @return A list of world names
     */
    suspend fun listWorlds(): List<String> {
        return worldsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted() ?: emptyList()
    }

    /**
     * Creates a new world with the specified name
     * @param name The name of the world to create
     * @return The instance container for the new world
     */
    suspend fun createWorld(name: String): InstanceContainer {
        // Create world directory
        val worldDir = File(worldsDir, name)
        if (!worldDir.exists()) {
            worldDir.mkdir()
        }

        // Create default config file
        val configFile = File(worldDir, "config.yml")
        if (!configFile.exists()) {
            val defaultConfig = mutableMapOf<String, Any>(
                "filepath" to "",
                "spawnPoint" to mapOf(
                    "x" to 0.0,
                    "y" to 40.0,
                    "z" to 0.0,
                    "yaw" to 0.0f,
                    "pitch" to 0.0f
                )
            )

            YamlFactory.saveConfig(defaultConfig, configFile)
            logger.info("Created new world configuration for $name")
        }

        // Create and configure the instance
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer(fullbrightKey)
        instance.time = 1000L // Set to daytime
        instance.timeRate = 0 // Freeze time
        instance.setChunkSupplier(::LightingChunk)

        // Set up default world generation
        instance.setGenerator { unit ->
            unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK)
        }

        // Save this instance in our cache
        loadedWorlds[name] = instance

        return instance
    }

    /**
     * Gets a world instance by name
     * @param name The name of the world to get
     * @return The world instance, or null if it doesn't exist
     */
    suspend fun getWorld(name: String?): InstanceContainer? {
        // Check if the instance exists in InstanceMap
        instanceMap?.playerInstances?.entries?.find { it.key.toString() == name }?.let {
            return it.value as? InstanceContainer
        }

        // Check if we already have this world loaded
        loadedWorlds[name]?.let { return it }

        // Check if the world directory exists
        val worldDir = File(worldsDir, name)
        if (!worldDir.exists()) {
            return null
        }

        // Load the world configuration
        val configFile = File(worldDir, "config.yml")
        if (!configFile.exists()) {
            logger.error("World directory exists but has no config file: $name")
            return null
        }

        // Load configuration
        val worldConfig = YamlFactory.loadConfig(configFile)

        // Create the instance
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer(fullbrightKey)
        instance.time = 1000L
        instance.timeRate = 0
        instance.setChunkSupplier(::LightingChunk)

        // Set the spawn point
        val spawnPoint = getSpawnPointFromConfig(worldConfig)

        // If filepath is specified, load the world using PolarLoader
        val filepath = YamlFactory.getValue(worldConfig, "filepath", "")
        if (filepath.isNotEmpty()) {
            try {
                val path = Paths.get(filepath)
                if (Files.exists(path)) {
                    instance.setChunkLoader(PolarLoader(path))
                    logger.info("Loaded world from: $filepath")
                } else {
                    logger.error("World file not found: $filepath")
                    setupDefaultWorldGeneration(instance)
                }
            } catch (e: Exception) {
                logger.error("Failed to load world file: $filepath", e)
                setupDefaultWorldGeneration(instance)
            }
        } else {
            setupDefaultWorldGeneration(instance)
            logger.info("Using default world generation for: $name")
        }

        // Cache the world instance
        loadedWorlds[name as String] = instance

        return instance
    }

    /**
     * Deletes a world
     * @param name The name of the world to delete
     */
    suspend fun deleteWorld(name: String) {
        // Unload the world if it's loaded
        loadedWorlds.remove(name)?.let { instance ->
            MinecraftServer.getInstanceManager().unregisterInstance(instance)
        }

        // Delete the world files
        val worldDir = File(worldsDir, name)
        if (worldDir.exists() && worldDir.isDirectory) {
            worldDir.listFiles()?.forEach { it.delete() }
            worldDir.delete()
            logger.info("Deleted world: $name")
        }
    }

    /**
     * Sets the spawn point for a world
     * @param name The name of the world
     * @param pos The new spawn position
     */
    suspend fun setWorldSpawn(name: String, pos: Pos) {
        val worldDir = File(worldsDir, name)
        if (!worldDir.exists()) {
            throw IllegalArgumentException("World $name does not exist")
        }

        val configFile = File(worldDir, "config.yml")
        if (!configFile.exists()) {
            throw IllegalArgumentException("World $name has no config file")
        }

        // Load the current config
        val config = YamlFactory.loadConfig(configFile).toMutableMap()

        // Update the spawn point
        val spawnPoint = mutableMapOf<String, Any>(
            "x" to pos.x,
            "y" to pos.y,
            "z" to pos.z,
            "yaw" to pos.yaw,
            "pitch" to pos.pitch
        )

        YamlFactory.setValue(config, "spawnPoint", spawnPoint)
        YamlFactory.saveConfig(config, configFile)

        logger.info("Updated spawn point for world $name to: $pos")
    }

    /**
     * Teleports a player to a world
     * @param player The player to teleport
     * @param worldName The name of the destination world
     */
    suspend fun teleportToWorld(player: Player, worldName: String) {
        val instance = getWorld(worldName) ?: createWorld(worldName)

        // Get the world configuration
        val configFile = File(File(worldsDir, worldName), "config.yml")
        val config = YamlFactory.loadConfig(configFile)

        // Get the spawn point
        val spawnPoint = getSpawnPointFromConfig(config)

        // Teleport the player
        player.setInstance(instance, spawnPoint)
    }

    /**
     * Set up default world generation for an instance
     */
    private suspend fun setupDefaultWorldGeneration(instance: InstanceContainer) {
        instance.setGenerator { unit ->
            unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK)
        }
    }

    /**
     * Extract spawn point coordinates from the config
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun getSpawnPointFromConfig(config: Map<String, Any>): Pos {
        val spawnPointMap = YamlFactory.getValue(config, "spawnPoint", mapOf<String, Any>()) as Map<String, Any>

        val x = YamlFactory.getValue(spawnPointMap, "x", 0.0) as Double
        val y = YamlFactory.getValue(spawnPointMap, "y", 40.0) as Double
        val z = YamlFactory.getValue(spawnPointMap, "z", 0.0) as Double

        // Handle yaw and pitch - they might be Double values from YAML but we need Float
        val yawValue = YamlFactory.getValue(spawnPointMap, "yaw", 0.0)
        val pitchValue = YamlFactory.getValue(spawnPointMap, "pitch", 0.0)

        // Convert to Float properly
        val yaw = if (yawValue is Double) yawValue.toFloat() else 0.0f
        val pitch = if (pitchValue is Double) pitchValue.toFloat() else 0.0f

        return Pos(x, y, z, yaw, pitch)
    }

    /**
     * World information data class for the info command
     */
    data class WorldInfo(
        val name: String,
        val filepath: String,
        val spawnPoint: SpawnPoint
    )

    /**
     * Spawn point coordinates
     */
    data class SpawnPoint(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float
    )

    /**
     * Checks if a world is currently loaded
     * @param name The name of the world to check
     * @return true if the world is loaded, false otherwise
     */
    suspend fun isWorldLoaded(name: String): Boolean {
        return loadedWorlds.containsKey(name)
    }

    /**
     * Gets the name of a world from an instance
     * @param instance The instance to check
     * @return The name of the world, or null if not found
     */
    fun getWorldNameFromInstance(instance: Instance): String? {
        // Check if this is a cell vault instance first
        instanceMap?.cellVaultInstances?.entries?.find { it.value === instance }?.let { (cellId, _) ->
            // Get the cell name from the CellManager
            val cell = twizzy.tech.game.cells.CellManager.getInstance().getCell(cellId)
            return if (cell != null) {
                "${cell.name}'s Vault"
            } else {
                "Cell Vault ($cellId)"
            }
        }

        // Check InstanceMap's playerInstances (player UUID as world name)
        instanceMap?.playerInstances?.entries?.find { it.value === instance }?.let {
            return UUID.fromString(it.key.toString()).toString()
        }

        // First try direct instance reference comparison
        loadedWorlds.entries.find { it.value === instance }?.let {
            return it.key
        }

        // Next, try by UUID comparison (for player instances, e.g. player.uuid as key)
        loadedWorlds.entries.find { it.value.uniqueId == instance.uniqueId }?.let {
            return it.key
        }

        // If still not found, check if the instance UUID matches any key in loadedWorlds (for player UUIDs as world names)
        loadedWorlds.keys.find { key ->
            try {
                UUID.fromString(key) == instance.uniqueId
            } catch (_: Exception) {
                false
            }
        }?.let { uuidKey ->
            return uuidKey
        }

        // Fallback: search the worlds directory for any matching instances
        for (worldDir in worldsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            val worldName = worldDir.name
            MinecraftServer.getInstanceManager().instances
                .find { it.uniqueId == instance.uniqueId }
                ?.let {
                    if (it is InstanceContainer && !loadedWorlds.containsKey(worldName)) {
                        loadedWorlds[worldName] = it
                    }
                    return worldName
                }
        }
        return null
    }

    /**
     * Gets detailed information about a world
     * @param name The name of the world
     * @return WorldInfo containing details about the world
     */
    @Suppress("UNCHECKED_CAST")
    fun getWorldInfo(name: String): WorldInfo {

        val worldDir = File(worldsDir, name)
        if (!worldDir.exists()) {
            throw IllegalArgumentException("World $name does not exist")
        }

        val configFile = File(worldDir, "config.yml")
        if (!configFile.exists()) {
            throw IllegalArgumentException("World $name has no config file")
        }

        // Load configuration
        val worldConfig = YamlFactory.loadConfig(configFile)

        // Get filepath
        val filepath = YamlFactory.getValue(worldConfig, "filepath", "")

        // Get spawn point
        val spawnPointMap = YamlFactory.getValue(worldConfig, "spawnPoint", mapOf<String, Any>()) as Map<String, Any>

        val x = YamlFactory.getValue(spawnPointMap, "x", 0.0) as Double
        val y = YamlFactory.getValue(spawnPointMap, "y", 40.0) as Double
        val z = YamlFactory.getValue(spawnPointMap, "z", 0.0) as Double

        val yawValue = YamlFactory.getValue(spawnPointMap, "yaw", 0.0)
        val pitchValue = YamlFactory.getValue(spawnPointMap, "pitch", 0.0)

        // Convert to Float properly
        val yaw = if (yawValue is Double) yawValue.toFloat() else 0.0f
        val pitch = if (pitchValue is Double) pitchValue.toFloat() else 0.0f

        val spawnPoint = SpawnPoint(x, y, z, yaw, pitch)

        return WorldInfo(name, filepath, spawnPoint)
    }

    /**
     * Gets the spawn point position of the spawn world
     * @return The spawn position or a default position if the spawn world configuration can't be loaded
     */
    fun getSpawnPoint(): Pos {
        try {
            val worldInfo = getWorldInfo("Spawn")
            return Pos(
                worldInfo.spawnPoint.x,
                worldInfo.spawnPoint.y,
                worldInfo.spawnPoint.z,
                worldInfo.spawnPoint.yaw,
                worldInfo.spawnPoint.pitch
            )
        } catch (e: Exception) {
            logger.error("Failed to get spawn point from spawn world config, using default", e)
            return Pos(0.0, 42.0, 0.0)
        }
    }

    /**
     * Gets detailed information about a cell vault
     * @param instance The cell vault instance
     * @return WorldInfo containing details about the cell vault
     */
    fun getCellVaultInfo(instance: Instance): WorldInfo? {
        // Find the cell ID for this instance
        val cellId = instanceMap?.cellVaultInstances?.entries?.find { it.value === instance }?.key
            ?: return null

        // Get the cell from CellManager
        val cell = twizzy.tech.game.cells.CellManager.getInstance().getCell(cellId)
            ?: return null

        // Get the vault directory
        val vaultDir = File(worldsDir, "vaults/$cellId")
        val configFile = File(vaultDir, "config.yml")

        val spawnPoint = if (configFile.exists()) {
            try {
                val config = YamlFactory.loadConfig(configFile)
                val spawnPointMap = config["spawnPoint"] as? Map<*, *>
                if (spawnPointMap != null) {
                    val x = (spawnPointMap["x"] as? Number)?.toDouble() ?: 0.0
                    val y = (spawnPointMap["y"] as? Number)?.toDouble() ?: 1.0
                    val z = (spawnPointMap["z"] as? Number)?.toDouble() ?: 0.0
                    val yaw = (spawnPointMap["yaw"] as? Number)?.toFloat() ?: 0.0f
                    val pitch = (spawnPointMap["pitch"] as? Number)?.toFloat() ?: 0.0f
                    SpawnPoint(x, y, z, yaw, pitch)
                } else {
                    SpawnPoint(0.0, 1.0, 0.0, 0.0f, 0.0f)
                }
            } catch (e: Exception) {
                SpawnPoint(0.0, 1.0, 0.0, 0.0f, 0.0f)
            }
        } else {
            SpawnPoint(0.0, 1.0, 0.0, 0.0f, 0.0f)
        }

        // Get the polar file path
        val polarFile = File(vaultDir, "$cellId.polar")
        val filepath = if (polarFile.exists()) {
            polarFile.absolutePath
        } else {
            "Not yet saved"
        }

        return WorldInfo("${cell.name}'s Vault", filepath, spawnPoint)
    }
}