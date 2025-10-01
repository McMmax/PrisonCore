package twizzy.tech.util

import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.instance.LightingChunk
import twizzy.tech.game.MineManager
import twizzy.tech.game.RegionManager
import net.hollowcube.polar.PolarWriter
import java.io.File
import java.util.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class InstanceMap(
    private val worlds: Worlds,
    private val regionManager: RegionManager,
    private val mineManager: MineManager
) {
    // Map of player UUID to their instance
    val playerInstances = mutableMapOf<UUID, Instance>()
    // Map of player UUID to their spawn position
    private val playerSpawns = mutableMapOf<UUID, Pos>()
    // Map of cell ID to their vault instance
    val cellVaultInstances = mutableMapOf<String, Instance>()

    /**
     * Creates a new instance for the player, copying the specified world (Polar, regions, mines)
     */
    suspend fun createInstance(player: Player, worldName: String): Instance? {
        // Get the source world directory
        val worldDir = File(worlds.worldsDir, worldName)
        if (!worldDir.exists()) return null

        // Load the world config
        val configFile = File(worldDir, "config.yml")
        if (!configFile.exists()) return null
        val config = twizzy.tech.util.YamlFactory.loadConfig(configFile)

        // Copy the polar file if it exists
        val polarPath = config["filepath"] as? String ?: ""
        val instance = net.minestom.server.MinecraftServer.getInstanceManager().createInstanceContainer(worlds.fullbrightKey)
        instance.time = 1000L
        instance.timeRate = 0
        instance.setChunkSupplier(::LightingChunk)
        if (polarPath.isNotEmpty() && File(polarPath).exists()) {
            instance.setChunkLoader(net.hollowcube.polar.PolarLoader(java.nio.file.Paths.get(polarPath)))
        } else {
            // Default generator
            instance.setGenerator { unit ->
                unit.modifier().fillHeight(0, 40, net.minestom.server.instance.block.Block.GRASS_BLOCK)
            }
        }

        // Copy regions
        val regionsFile = File(worldDir, "regions.yml")
        var regionsList: List<Map<String, Any>> = emptyList()
        val mineNames = mutableSetOf<String>()
        val minesFile = File(worldDir, "mines.yml")
        if (minesFile.exists()) {
            val minesData = YamlFactory.loadConfig(minesFile)
            val minesList = minesData["mines"] as? List<Map<String, Any>> ?: emptyList()
            for (mineData in minesList) {
                val name = mineData["name"] as String
                mineNames.add(name)
            }
        }
        if (regionsFile.exists()) {
            val regionsData = YamlFactory.loadConfig(regionsFile)
            regionsList = regionsData["regions"] as? List<Map<String, Any>> ?: emptyList()
            for (regionData in regionsList) {
                try {
                    val name = regionData["name"] as String
                    if (mineNames.contains(name)) continue // skip mine regions
                    val pos1Map = regionData["pos1"] as Map<*, *>
                    val pos2Map = regionData["pos2"] as Map<*, *>
                    val pos1 = net.minestom.server.coordinate.Pos(
                        (pos1Map["x"] as Number).toDouble(),
                        (pos1Map["y"] as Number).toDouble(),
                        (pos1Map["z"] as Number).toDouble()
                    )
                    val pos2 = net.minestom.server.coordinate.Pos(
                        (pos2Map["x"] as Number).toDouble(),
                        (pos2Map["y"] as Number).toDouble(),
                        (pos2Map["z"] as Number).toDouble()
                    )
                    val rawFlags = (regionData["flags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    val flags = rawFlags.map { it.lowercase() }.toMutableSet()
                    val id = if (regionData["id"] != null) UUID.fromString(regionData["id"] as String) else UUID.randomUUID()
                    val region = RegionManager.Region(id, player.uuid.toString(), pos1, pos2, flags)
                    regionManager.setRegion(worldName, region)
                } catch (_: Exception) {}
            }
        }

        // Copy mines
        if (minesFile.exists()) {
            val minesData = twizzy.tech.util.YamlFactory.loadConfig(minesFile)
            val minesList = minesData["mines"] as? List<Map<String, Any>> ?: emptyList()
            for (mineData in minesList) {
                try {
                    val name = mineData["name"] as String
                    val originalRegionId = mineData["regionId"] as? String
                    // Find the original region bounds by name (region name == mine name)
                    val regionData = regionsList.find { (it["name"] as? String) == name }
                    if (regionData == null) continue // skip if no region found
                    val pos1Map = regionData["pos1"] as Map<*, *>
                    val pos2Map = regionData["pos2"] as Map<*, *>
                    val pos1 = net.minestom.server.coordinate.Pos(
                        (pos1Map["x"] as Number).toDouble(),
                        (pos1Map["y"] as Number).toDouble(),
                        (pos1Map["z"] as Number).toDouble()
                    )
                    val pos2 = net.minestom.server.coordinate.Pos(
                        (pos2Map["x"] as Number).toDouble(),
                        (pos2Map["y"] as Number).toDouble(),
                        (pos2Map["z"] as Number).toDouble()
                    )

                    val rawFlags = (regionData["flags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    val flags = rawFlags.map { it.lowercase() }.toMutableSet()
                    val newRegionId = UUID.randomUUID()
                    val region = RegionManager.Region(newRegionId, player.uuid.toString(), pos1, pos2, flags)
                    regionManager.setRegion(player.uuid.toString(), region)
                    val blocksList = mineData["blocks"] as? List<Map<String, Any>> ?: emptyList()
                    val blocks = blocksList.map { MineManager.MineBlock(it["block"] as String) }.toMutableList()
                    val lastReset = System.currentTimeMillis()
                    val resetInterval = (mineData["resetInterval"] as? Number)?.toLong() ?: 900L
                    val mine = MineManager.Mine("${player.uuid}_mine", newRegionId, blocks, lastReset, resetInterval)
                    // Add to mineManager's map for this player's instance
                    mineManager.setMine(player.uuid.toString(), "${player.uuid}_mine", mine)
                    mineManager.resetMine(player.uuid.toString(), "${player.uuid}_mine")
                } catch (_: Exception) {}
            }
        }

        // Store the instance for the player
        playerInstances[player.uuid] = instance
        // Save the spawn position if available in config
        val spawn = config["spawnPoint"] as? Map<*, *>
        if (spawn != null) {
            val x = (spawn["x"] as? Number)?.toDouble() ?: 0.0
            val y = (spawn["y"] as? Number)?.toDouble() ?: 40.0
            val z = (spawn["z"] as? Number)?.toDouble() ?: 0.0
            val yaw = (spawn["yaw"] as? Number)?.toFloat() ?: 0.0f
            val pitch = (spawn["pitch"] as? Number)?.toFloat() ?: 0.0f
            playerSpawns[player.uuid] = Pos(x, y, z, yaw, pitch)
        }

        mineManager.resetMine(player.uuid.toString(), "${player.uuid}_mine")
        return instance
    }

    suspend fun createCellInstance(cellId: String) {
        // Create a new instance for the cell vault
        val instance = net.minestom.server.MinecraftServer.getInstanceManager().createInstanceContainer(worlds.fullbrightKey)
        instance.time = 1000L
        instance.timeRate = 0
        instance.setChunkSupplier(::LightingChunk)

        // Set up the polar file for the cell vault
        val polarFile = File(worlds.worldsDir, "cells/$cellId.polar")
        if (polarFile.exists()) {
            instance.setChunkLoader(net.hollowcube.polar.PolarLoader(polarFile.toPath()))
        } else {
            // Default generator for cell vault
            instance.setGenerator { unit ->
                unit.modifier().fillHeight(0, 40, net.minestom.server.instance.block.Block.STONE)
            }
        }

        // Store the instance for the cell vault
        cellVaultInstances[cellId] = instance
    }

    /**
     * Creates a cell vault instance for cells at level 10+
     */
    suspend fun createCellVault(cellId: String): Instance? {
        try {
            // Create vault directory structure
            val vaultDir = File(worlds.worldsDir, "vaults")
            if (!vaultDir.exists()) vaultDir.mkdirs()

            val cellVaultDir = File(vaultDir, cellId)
            if (!cellVaultDir.exists()) cellVaultDir.mkdirs()

            // Create instance
            val instance = net.minestom.server.MinecraftServer.getInstanceManager().createInstanceContainer(worlds.fullbrightKey)
            instance.time = 1000L
            instance.timeRate = 0
            instance.setChunkSupplier(::LightingChunk)

            // Check if cell already has a saved vault
            val existingPolarFile = File(cellVaultDir, "$cellId.polar")
            if (existingPolarFile.exists()) {
                // Load existing vault
                instance.setChunkLoader(net.hollowcube.polar.PolarLoader(existingPolarFile.toPath()))
            } else {
                // Copy the default cell.polar template
                val templatePolarFile = File("src/main/resources/cell.polar")
                if (templatePolarFile.exists()) {
                    Files.copy(templatePolarFile.toPath(), existingPolarFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    instance.setChunkLoader(net.hollowcube.polar.PolarLoader(existingPolarFile.toPath()))
                } else {
                    // Fallback to default generation if template doesn't exist
                    instance.setGenerator { unit ->
                        unit.modifier().fillHeight(0, 1, net.minestom.server.instance.block.Block.BEDROCK)
                        unit.modifier().fillHeight(1, 40, net.minestom.server.instance.block.Block.STONE)
                    }
                }
            }

            // Create config file for the vault
            val configFile = File(cellVaultDir, "config.yml")
            if (!configFile.exists()) {
                val defaultConfig = mapOf(
                    "filepath" to existingPolarFile.absolutePath,
                    "spawnPoint" to mapOf(
                        "x" to 0.0,
                        "y" to 1.0,
                        "z" to 0.0,
                        "yaw" to 0.0f,
                        "pitch" to 0.0f
                    )
                )
                YamlFactory.saveConfig(defaultConfig, configFile)
            }

            // Store the vault instance
            cellVaultInstances[cellId] = instance

            println("[InstanceMap] Created vault for cell $cellId")
            return instance
        } catch (e: Exception) {
            println("[InstanceMap] Failed to create cell vault for $cellId: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * Gets the vault instance for a cell
     */
    fun getCellVault(cellId: String): Instance? = cellVaultInstances[cellId]

    /**
     * Gets the vault spawn position for a cell (always 0, 1, 0 by default)
     */
    fun getCellVaultSpawn(cellId: String): Pos {
        val vaultDir = File(worlds.worldsDir, "vaults/$cellId")
        val configFile = File(vaultDir, "config.yml")

        return if (configFile.exists()) {
            try {
                val config = YamlFactory.loadConfig(configFile)
                val spawnPoint = config["spawnPoint"] as? Map<*, *>
                if (spawnPoint != null) {
                    val x = (spawnPoint["x"] as? Number)?.toDouble() ?: 0.0
                    val y = (spawnPoint["y"] as? Number)?.toDouble() ?: 1.0
                    val z = (spawnPoint["z"] as? Number)?.toDouble() ?: 0.0
                    val yaw = (spawnPoint["yaw"] as? Number)?.toFloat() ?: 0.0f
                    val pitch = (spawnPoint["pitch"] as? Number)?.toFloat() ?: 0.0f
                    Pos(x, y, z, yaw, pitch)
                } else {
                    Pos(0.0, 1.0, 0.0)
                }
            } catch (e: Exception) {
                Pos(0.0, 1.0, 0.0)
            }
        } else {
            Pos(0.0, 1.0, 0.0)
        }
    }

    /**
     * Saves all cell vaults to disk
     */
    suspend fun saveAllCellVaults() {
        for ((cellId, instance) in cellVaultInstances) {
            saveCellVault(cellId, instance)
        }
    }

    /**
     * Saves a specific cell vault to disk
     */
    private suspend fun saveCellVault(cellId: String, instance: Instance) {
        try {
            val vaultDir = File(worlds.worldsDir, "vaults/$cellId")
            if (!vaultDir.exists()) vaultDir.mkdirs()

            val polarFile = File(vaultDir, "$cellId.polar")
            val tempPolarFile = File(vaultDir, "$cellId.polar.tmp")

            // Save the instance to polar format using the correct API (based on Polar example)
            val polarLoader = net.hollowcube.polar.PolarLoader(net.hollowcube.polar.PolarWorld())
            (instance as net.minestom.server.instance.InstanceContainer).setChunkLoader(polarLoader)
            instance.saveInstance().join()

            // Write the polar world to a temporary file first
            val polarData = PolarWriter.write(polarLoader.world())

            // Write to temporary file first to prevent corruption
            java.nio.file.Files.write(
                tempPolarFile.toPath(),
                polarData,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            )

            // Only replace the original file if the write was successful
            if (tempPolarFile.exists() && tempPolarFile.length() > 0) {
                // Delete old file if it exists
                if (polarFile.exists()) {
                    polarFile.delete()
                }
                // Rename temp file to final file
                tempPolarFile.renameTo(polarFile)
                println("[InstanceMap] Saved vault for cell $cellId (${polarFile.length()} bytes)")
            } else {
                println("[InstanceMap] Failed to save vault for cell $cellId: Temporary file is empty or doesn't exist")
                // Clean up temp file if it exists
                if (tempPolarFile.exists()) {
                    tempPolarFile.delete()
                }
            }

        } catch (e: Exception) {
            println("[InstanceMap] Failed to save vault for cell $cellId: ${e.message}")
            e.printStackTrace()

            // Clean up temp file if it exists
            val tempPolarFile = File(worlds.worldsDir, "vaults/$cellId/$cellId.polar.tmp")
            if (tempPolarFile.exists()) {
                tempPolarFile.delete()
            }
        }
    }

    fun getInstance(player: Player): Instance? = playerInstances[player.uuid]

    fun getSpawn(player: Player): Pos? = playerSpawns[player.uuid]
}