package twizzy.tech.commands

import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Optional
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.minestom.annotation.CommandPermission
import twizzy.tech.util.Worlds
import twizzy.tech.util.YamlFactory

@Command("world")
@CommandPermission("admin.world")
class World(private val worlds: Worlds) {

    @Command("world")
    @Description("Manage worlds on the server")
    suspend fun worldHelp(sender: CommandSender) {
        val helpMessages = YamlFactory.getCommandHelp("world")
        helpMessages.forEach { sender.sendMessage(it) }
    }

    @Subcommand("create")
    suspend fun createWorld(sender: CommandSender, name: String) {
        // Validate world name
        if (!isValidWorldName(name)) {
            sender.sendMessage(YamlFactory.getMessage("commands.world.create.invalid_name"))
            return
        }

        // Check if world already exists
        if (worlds.worldExists(name)) {
            sender.sendMessage(YamlFactory.getMessage("commands.world.create.already_exists"))
            return
        }

        try {
            worlds.createWorld(name)
            sender.sendMessage(YamlFactory.getMessage("commands.world.create.success", mapOf("name" to name)))
        } catch (e: Exception) {
            sender.sendMessage(YamlFactory.getMessage("commands.world.create.failed", mapOf("error" to (e.message ?: "Unknown error"))))
        }
    }

    @Subcommand("delete")
    suspend fun deleteWorld(sender: CommandSender, name: String) {
        // Don't allow deleting the spawn world
        if (name.equals("spawn", ignoreCase = true)) {
            sender.sendMessage(YamlFactory.getMessage("commands.world.delete.cannot_delete_spawn"))
            return
        }

        // Check if world exists
        if (!worlds.worldExists(name)) {
            sender.sendMessage(YamlFactory.getMessage("commands.world.delete.not_exists", mapOf("name" to name)))
            return
        }

        try {
            worlds.deleteWorld(name)
            sender.sendMessage(YamlFactory.getMessage("commands.world.delete.success", mapOf("name" to name)))
        } catch (e: Exception) {
            sender.sendMessage(YamlFactory.getMessage("commands.world.delete.failed", mapOf("error" to (e.message ?: "Unknown error"))))
        }
    }

    @Subcommand("setspawn")
    suspend fun setWorldSpawn(player: Player) {
        val name = worlds.getWorldNameFromInstance(player.instance)

        if (name == null) {
            player.sendMessage(YamlFactory.getMessage("commands.world.setspawn.unknown_world"))
            return
        }

        try {
            val position = player.position
            worlds.setWorldSpawn(name, position)
            player.sendMessage(YamlFactory.getMessage("commands.world.setspawn.success", mapOf("name" to name)))
        } catch (e: Exception) {
            player.sendMessage(YamlFactory.getMessage("commands.world.setspawn.failed", mapOf("error" to (e.message ?: "Unknown error"))))
        }
    }

    @Subcommand("info")
    suspend fun worldInfo(player: Player, @Optional worldName: String?) {
        // Determine which world to show info for - either specified or current
        val targetWorldName: String
        val instance: Instance
        val worldInfo: Worlds.WorldInfo

        if (worldName != null) {
            // User specified a world name
            if (!worlds.worldExists(worldName)) {
                player.sendMessage(YamlFactory.getMessage("commands.world.info.not_exists", mapOf("name" to worldName)))
                return
            }

            targetWorldName = worldName
            // Get the instance from the world name
            instance = worlds.getWorld(worldName) ?: run {
                player.sendMessage(YamlFactory.getMessage("commands.world.info.failed_load", mapOf("name" to worldName)))
                return
            }

            // Get world info for regular worlds
            worldInfo = try {
                worlds.getWorldInfo(targetWorldName)
            } catch (e: Exception) {
                player.sendMessage(YamlFactory.getMessage("commands.world.info.failed", mapOf("error" to (e.message ?: "Unknown error"))))
                return
            }
        } else {
            // Use the player's current world
            instance = player.instance
            val currentWorldName = worlds.getWorldNameFromInstance(instance)

            if (currentWorldName == null) {
                player.sendMessage(YamlFactory.getMessage("commands.world.info.unknown_world"))
                return
            }

            targetWorldName = currentWorldName

            // Check if this is a cell vault instance
            val cellVaultInfo = worlds.getCellVaultInfo(instance)
            if (cellVaultInfo != null) {
                // This is a cell vault, use the cell vault info
                worldInfo = cellVaultInfo
            } else {
                // This is a regular world, use normal world info
                worldInfo = try {
                    worlds.getWorldInfo(targetWorldName)
                } catch (e: Exception) {
                    player.sendMessage(YamlFactory.getMessage("commands.world.info.failed", mapOf("error" to (e.message ?: "Unknown error"))))
                    return
                }
            }
        }

        // Display the world information
        player.sendMessage(YamlFactory.getMessage("commands.world.info.header"))
        player.sendMessage(YamlFactory.getMessage("commands.world.info.name", mapOf("name" to worldInfo.name)))
        player.sendMessage(YamlFactory.getMessage("commands.world.info.spawn", mapOf(
            "x" to worldInfo.spawnPoint.x,
            "y" to worldInfo.spawnPoint.y,
            "z" to worldInfo.spawnPoint.z
        )))
        player.sendMessage(YamlFactory.getMessage("commands.world.info.filepath", mapOf(
            "filepath" to if (worldInfo.filepath.isEmpty()) "None" else worldInfo.filepath
        )))
        player.sendMessage(YamlFactory.getMessage("commands.world.info.chunks", mapOf("count" to instance.chunks.size)))
        player.sendMessage(YamlFactory.getMessage("commands.world.info.players", mapOf("count" to instance.players.size)))
        player.sendMessage(YamlFactory.getMessage("commands.world.info.footer"))
    }

    @Subcommand("tp")
    suspend fun teleportToWorld(player: Player, name: String) {
        // Check if world exists
        if (!worlds.worldExists(name)) {
            player.sendMessage(YamlFactory.getMessage("commands.world.tp.not_exists", mapOf("name" to name)))
            return
        }

        try {
            worlds.teleportToWorld(player, name)
            player.sendMessage(YamlFactory.getMessage("commands.world.tp.success", mapOf("name" to name)))
        } catch (e: Exception) {
            player.sendMessage(YamlFactory.getMessage("commands.world.tp.failed", mapOf("error" to (e.message ?: "Unknown error"))))
        }
    }

    @Subcommand("list")
    suspend fun listWorlds(sender: CommandSender) {
        val worldList = worlds.listWorlds()

        if (worldList.isEmpty()) {
            sender.sendMessage(YamlFactory.getMessage("commands.world.list.empty"))
            return
        }

        sender.sendMessage(YamlFactory.getMessage("commands.world.list.header", mapOf("count" to worldList.size)))
        for (worldName in worldList) {
            sender.sendMessage(YamlFactory.getMessage("commands.world.list.entry", mapOf("name" to worldName)))
        }
    }

    private suspend fun isValidWorldName(name: String): Boolean {
        return name.matches(Regex("^[a-zA-Z0-9_]+$"))
    }
}