package twizzy.tech.commands

import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Named
import revxrsal.commands.annotation.Optional
import twizzy.tech.game.cells.*
import twizzy.tech.player.PlayerData
import twizzy.tech.util.YamlFactory
import net.minestom.server.utils.mojang.MojangUtils
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.time.ZoneId
import java.math.BigDecimal

// Type alias to avoid naming conflict between command class and data class
typealias CellData = twizzy.tech.game.cells.Cell

@Command("cell", "c")
class Cell {

    private val cellManager = CellManager.getInstance()

    @Description("Show cell help information")
    suspend fun cellUsage(actor: Player) {
        val helpLines = YamlFactory.getCommandHelp("cell")
        helpLines.forEach { line ->
            actor.sendMessage(Component.text(line))
        }
    }

    @Subcommand("create")
    @Description("Create a new cell")
    suspend fun cellCreate(actor: Player, @Named("name") name: String) {
        // Validate cell name
        if (name.length < 3 || name.length > 16) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.create.invalid_name")))
            return
        }

        if (!name.matches(Regex("[a-zA-Z0-9_]+"))) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.create.invalid_characters")))
            return
        }

        val result = cellManager.createCell(name, actor.uuid)

        val message = when (result) {
            CellCreationResult.SUCCESS -> YamlFactory.getMessage("commands.cell.create.success", mapOf("name" to name))
            CellCreationResult.ALREADY_IN_CELL -> YamlFactory.getMessage("commands.cell.create.already_in_cell")
            CellCreationResult.NAME_TAKEN -> YamlFactory.getMessage("commands.cell.create.name_taken", mapOf("name" to name))
            CellCreationResult.INSUFFICIENT_RANK -> {
                val requiredRank = getRequiredLeaderRank()
                YamlFactory.getMessage("commands.cell.create.insufficient_rank", mapOf("required" to requiredRank.toString()))
            }
            CellCreationResult.PLAYER_DATA_ERROR -> YamlFactory.getMessage("commands.cell.create.data_error")
            CellCreationResult.ERROR -> YamlFactory.getMessage("commands.cell.create.error")
        }

        actor.sendMessage(Component.text(message))
    }

    @Subcommand("disband")
    @Description("Disband your cell")
    suspend fun cellDelete(actor: Player) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.delete.not_in_cell")))
            return
        }

        val result = cellManager.deleteCell(cell.id, actor.uuid)

        val message = when (result) {
            CellDeletionResult.SUCCESS -> YamlFactory.getMessage("commands.cell.delete.success", mapOf("name" to cell.name))
            CellDeletionResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.delete.not_found")
            CellDeletionResult.NOT_LEADER -> YamlFactory.getMessage("commands.cell.delete.not_leader")
            CellDeletionResult.ERROR -> YamlFactory.getMessage("commands.cell.delete.error")
        }

        actor.sendMessage(Component.text(message))
    }

    @Subcommand("join")
    @Description("Join a cell by name or accept an invitation")
    suspend fun cellJoin(actor: Player, @Named("name") name: String) {
        // Find cell by name
        val cell = cellManager.getAllCells().find { it.name.equals(name, ignoreCase = true) }
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.join.not_found", mapOf("name" to name))))
            return
        }

        // Check if player has an invitation to this cell
        val hasInvitation = cell.hasInvitation(actor.uuid)

        if (hasInvitation) {
            // Player has an invitation, accept it
            val result = cellManager.acceptInvitation(cell.id, actor.uuid)

            val message = when (result) {
                InvitationResult.SUCCESS -> YamlFactory.getMessage("commands.cell.join.success_invitation", mapOf("name" to cell.name))
                InvitationResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.join.not_found", mapOf("name" to name))
                InvitationResult.NO_INVITATION -> YamlFactory.getMessage("commands.cell.join.no_invitation", mapOf("name" to name))
                InvitationResult.ALREADY_IN_CELL -> YamlFactory.getMessage("commands.cell.join.already_in_cell")
                InvitationResult.CELL_FULL -> YamlFactory.getMessage("commands.cell.join.full")
                InvitationResult.ERROR -> YamlFactory.getMessage("commands.cell.join.error")
                else -> YamlFactory.getMessage("commands.cell.join.error")
            }

            actor.sendMessage(Component.text(message))
            return
        }

        // No invitation, try to join as public cell
        if (!cell.isPublic) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.join.private")))
            return
        }

        val result = cellManager.joinCell(cell.id, actor.uuid)

        val message = when (result) {
            CellJoinResult.SUCCESS -> YamlFactory.getMessage("commands.cell.join.success", mapOf("name" to cell.name))
            CellJoinResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.join.not_found", mapOf("name" to name))
            CellJoinResult.ALREADY_IN_CELL -> YamlFactory.getMessage("commands.cell.join.already_in_cell")
            CellJoinResult.CELL_FULL -> YamlFactory.getMessage("commands.cell.join.full")
            CellJoinResult.ERROR -> YamlFactory.getMessage("commands.cell.join.error")
        }

        actor.sendMessage(Component.text(message))
    }

    @Subcommand("leave")
    @Description("Leave your current cell")
    suspend fun cellLeave(actor: Player) {
        val result = cellManager.leaveCell(actor.uuid)

        val message = when (result) {
            CellLeaveResult.SUCCESS -> YamlFactory.getMessage("commands.cell.leave.success")
            CellLeaveResult.NOT_IN_CELL -> YamlFactory.getMessage("commands.cell.leave.not_in_cell")
            CellLeaveResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.leave.not_found")
            CellLeaveResult.LEADER_CANNOT_LEAVE -> YamlFactory.getMessage("commands.cell.leave.leader_cannot_leave")
            CellLeaveResult.ERROR -> YamlFactory.getMessage("commands.cell.leave.error")
        }

        actor.sendMessage(Component.text(message))
    }

    @Subcommand("info", "show")
    @Description("Show information about your cell or another cell")
    suspend fun cellInfo(actor: Player, @Optional @Named("name") cellName: String?) {
        val cell = if (cellName != null) {
            cellManager.getAllCells().find { it.name.equals(cellName, ignoreCase = true) }
        } else {
            cellManager.getPlayerCell(actor.uuid)
        }

        if (cell == null) {
            val message = if (cellName != null) {
                YamlFactory.getMessage("commands.cell.info.not_found", mapOf("name" to cellName))
            } else {
                YamlFactory.getMessage("commands.cell.info.not_in_cell")
            }
            actor.sendMessage(Component.text(message))
            return
        }

        // Get leader name
        val leaderName = try {
            MojangUtils.getUsername(cell.leaderId) ?: "Unknown"
        } catch (e: IOException) {
            "Unknown"
        }

        // Get online players for status checking
        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers.map { it.uuid }.toSet()

        // Check if leader is online and apply color
        val isLeaderOnline = onlinePlayers.contains(cell.leaderId)
        val coloredLeaderName = if (isLeaderOnline) "§a$leaderName" else "§7$leaderName"

        // Separate members by role with online status colors
        val coLeaders = mutableListOf<String>()
        val captains = mutableListOf<String>()
        val members = mutableListOf<String>()
        var onlineCount = 0

        // Count leader in online count if they're online
        if (isLeaderOnline) onlineCount++

        // Sort members by role and apply online status colors
        for ((memberId, role) in cell.members) {
            // Skip if this is somehow the leader (shouldn't happen but safety check)
            if (memberId == cell.leaderId) continue

            try {
                val memberName = MojangUtils.getUsername(memberId) ?: "Unknown"
                val isOnline = onlinePlayers.contains(memberId)
                if (isOnline) onlineCount++

                val color = if (isOnline) "§a" else "§7" // Green for online, gray for offline
                val coloredName = "$color$memberName"

                when (role) {
                    CellRole.CO_LEADER -> coLeaders.add(coloredName)
                    CellRole.CAPTAIN -> captains.add(coloredName)
                    CellRole.MEMBER -> members.add(coloredName)
                    CellRole.LEADER -> {} // This shouldn't happen but just in case
                }
            } catch (e: IOException) {
                val color = "§7" // Assume offline if we can't get username
                val coloredName = "${color}Unknown"

                when (role) {
                    CellRole.CO_LEADER -> coLeaders.add(coloredName)
                    CellRole.CAPTAIN -> captains.add(coloredName)
                    CellRole.MEMBER -> members.add(coloredName)
                    CellRole.LEADER -> {} // This shouldn't happen but just in case
                }
            }
        }

        // Format creation date - Fixed to work with Instant
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        val createdDate = dateFormat.format(Date.from(cell.createdAt))

        // Get upgrade configuration for balance display
        val upgradeConfig = CellManager.getInstance().getCellUpgradeConfig()
        val nextUpgradeBalance = cell.getNextUpgradeRequiredBalance(upgradeConfig.basePrice, upgradeConfig.priceIncrease)
        val upgradeInfo = if (nextUpgradeBalance != null) {
            "§7Next upgrade: §f${String.format("%,.0f", nextUpgradeBalance)} §7(Level ${cell.level + 1})"
        } else {
            "§7Can continue upgrading"
        }

        // Get the info format list and replace placeholders
        val infoFormat = YamlFactory.getMessageList("commands.cell.info.format")
        val replacements = mapOf(
            "name" to cell.name,
            "leader" to coloredLeaderName,
            "coleaders" to if (coLeaders.isNotEmpty()) coLeaders.joinToString(", ") else "None",
            "captains" to if (captains.isNotEmpty()) captains.joinToString(", ") else "None",
            "members" to if (members.isNotEmpty()) members.joinToString(", ") else "None",
            "online" to if (onlineCount > 0) "§a$onlineCount" else "§7$onlineCount", // Green if 1+, gray if 0
            "count" to (cell.members.size + 1).toString(), // +1 for the leader
            "date" to createdDate,
            "status" to if (cell.isPublic) "(Open)" else "",
            "description" to if (cell.description.isNotEmpty()) cell.description else "None",
            "announcement" to if (cell.announcement.isNotEmpty()) cell.announcement else "None",
            "level" to cell.level.toString(),
            "balance" to String.format("%,.0f", cell.balance),
            "upgrade_info" to upgradeInfo
        )

        // Send each line with placeholder replacements, but skip empty description/announcement lines
        infoFormat.forEach { line ->
            // Skip description line if description is empty
            if (line.contains("{description}") && cell.description.isEmpty()) {
                return@forEach
            }

            // Skip announcement line if announcement is empty
            if (line.contains("{announcement}") && cell.announcement.isEmpty()) {
                return@forEach
            }

            // Skip Co-Leaders line if there are no co-leaders
            if (line.contains("{coleaders}") && coLeaders.isEmpty()) {
                return@forEach
            }

            // Skip Captains line if there are no captains
            if (line.contains("{captains}") && captains.isEmpty()) {
                return@forEach
            }

            // Skip Members line if there are no members
            if (line.contains("{members}") && members.isEmpty()) {
                return@forEach
            }

            var processedLine = line
            replacements.forEach { (key, value) ->
                processedLine = processedLine.replace("{$key}", value)
            }
            actor.sendMessage(Component.text(processedLine))
        }
    }

    @Subcommand("list")
    @Description("List all cells with online members")
    suspend fun cellList(actor: Player) {
        val cellsWithOnlineMembers = cellManager.getCellsWithOnlineMembers()

        if (cellsWithOnlineMembers.isEmpty()) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.list.empty")))
            return
        }

        actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.list.header", mapOf("count" to cellsWithOnlineMembers.size.toString()))))

        for ((cell, onlineCount) in cellsWithOnlineMembers) {
            val leaderName = try {
                MojangUtils.getUsername(cell.leaderId) ?: "Unknown"
            } catch (e: IOException) {
                "Unknown"
            }

            val entry = YamlFactory.getMessage("commands.cell.list.entry", mapOf(
                "name" to cell.name,
                "leader" to leaderName,
                "online" to onlineCount.toString(),
                "members" to cell.members.size.toString()
            ))
            actor.sendMessage(Component.text(entry))
        }
    }

    @Subcommand("invite")
    @Description("Invite a player to your cell")
    suspend fun cellInvite(actor: Player, @Named("player") targetName: String) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.invite.not_in_cell")))
            return
        }

        // Use the new permission system
        if (!cell.hasPermission(actor.uuid, "invite")) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.invite.not_leader")))
            return
        }

        // Get target player UUID
        val targetUuid = try {
            MojangUtils.getUUID(targetName)
        } catch (e: IOException) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.invite.player_not_found", mapOf("player" to targetName))))
            return
        }

        // Send invitation using the new system
        val result = cellManager.sendInvitation(cell.id, actor.uuid, targetUuid)

        val message = when (result) {
            InvitationResult.SUCCESS -> YamlFactory.getMessage("commands.cell.invite.sent", mapOf("player" to targetName))
            InvitationResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.invite.cell_not_found")
            InvitationResult.NOT_LEADER -> YamlFactory.getMessage("commands.cell.invite.not_leader")
            InvitationResult.ALREADY_IN_CELL -> YamlFactory.getMessage("commands.cell.invite.already_in_cell", mapOf("player" to targetName))
            InvitationResult.CELL_FULL -> YamlFactory.getMessage("commands.cell.invite.cell_full")
            InvitationResult.ALREADY_INVITED -> YamlFactory.getMessage("commands.cell.invite.already_invited", mapOf("player" to targetName))
            InvitationResult.NO_INVITATION -> YamlFactory.getMessage("commands.cell.invite.no_invitation")
            InvitationResult.ERROR -> YamlFactory.getMessage("commands.cell.invite.error")
        }

        actor.sendMessage(Component.text(message))
    }

    @Subcommand("revoke")
    @Description("Revoke an invitation to your cell")
    suspend fun cellRevoke(actor: Player, @Named("player") targetName: String) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.revoke.not_in_cell")))
            return
        }

        // Use the new permission system
        if (!cell.hasPermission(actor.uuid, "invite")) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.revoke.not_leader")))
            return
        }

        // Get target player UUID
        val targetUuid = try {
            MojangUtils.getUUID(targetName)
        } catch (e: IOException) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.revoke.player_not_found", mapOf("player" to targetName))))
            return
        }

        val result = cellManager.revokeInvitation(cell.id, actor.uuid, targetUuid)

        val message = when (result) {
            InvitationResult.SUCCESS -> YamlFactory.getMessage("commands.cell.revoke.success", mapOf("player" to targetName))
            InvitationResult.NO_INVITATION -> YamlFactory.getMessage("commands.cell.revoke.no_invitation", mapOf("player" to targetName))
            InvitationResult.NOT_LEADER -> YamlFactory.getMessage("commands.cell.revoke.not_leader")
            InvitationResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.revoke.cell_not_found")
            else -> YamlFactory.getMessage("commands.cell.revoke.error")
        }

        actor.sendMessage(Component.text(message))
    }

    @Subcommand("invites")
    @Description("Show your pending invitations")
    suspend fun cellInvites(actor: Player) {
        // Get all cells that have invited this player
        val pendingInvites = mutableListOf<CellData>()
        cellManager.getAllCells().forEach { cell ->
            if (cell.hasInvitation(actor.uuid)) {
                pendingInvites.add(cell)
            }
        }

        // Get current cell for sent invitations
        val cell = cellManager.getPlayerCell(actor.uuid)
        val sentInvites = if (cell != null && cell.hasPermission(actor.uuid, "invite")) {
            cell.pendingInvitations.toList()
        } else {
            emptyList()
        }

        // Send header
        actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.invites.header")))

        // Show pending invitations (received)
        if (pendingInvites.isNotEmpty()) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.invites.pending_header", mapOf("count" to pendingInvites.size.toString()))))

            for (cellData in pendingInvites) {
                val leaderName = try {
                    MojangUtils.getUsername(cellData.leaderId) ?: "Unknown"
                } catch (e: IOException) {
                    "Unknown"
                }

                val inviteInfo = YamlFactory.getMessage("commands.cell.invites.pending_entry", mapOf(
                    "cell" to cellData.name,
                    "inviter" to leaderName
                ))
                actor.sendMessage(Component.text(inviteInfo))
            }
        } else {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.invites.no_pending")))
        }

        // Show sent invitations (only if they have invite permissions)
        if (sentInvites.isNotEmpty()) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.invites.sent_header", mapOf("count" to sentInvites.size.toString()))))

            for (inviteeId in sentInvites) {
                val inviteeName = try {
                    MojangUtils.getUsername(inviteeId) ?: "Unknown"
                } catch (e: IOException) {
                    "Unknown"
                }

                val inviteInfo = YamlFactory.getMessage("commands.cell.invites.sent_entry", mapOf(
                    "player" to inviteeName,
                    "cell" to cell!!.name
                ))
                actor.sendMessage(Component.text(inviteInfo))
            }
        } else if (cell != null && cell.hasPermission(actor.uuid, "invite")) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.invites.no_sent")))
        }

        // Send footer
        actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.invites.footer")))
    }

    @Subcommand("kick")
    @Description("Kick a player from your cell")
    suspend fun cellKick(actor: Player, @Named("player") targetName: String) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.kick.not_in_cell")))
            return
        }

        // Use the new permission system
        if (!cell.hasPermission(actor.uuid, "kick")) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.kick.insufficient_permission")))
            return
        }

        // Get target player UUID
        val targetUuid = try {
            MojangUtils.getUUID(targetName)
        } catch (e: IOException) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.kick.player_not_found", mapOf("player" to targetName))))
            return
        }

        // Kick the player
        val result = cellManager.kickPlayer(cell.id, actor.uuid, targetUuid)

        val message = when (result) {
            CellKickResult.SUCCESS -> YamlFactory.getMessage("commands.cell.kick.success", mapOf("player" to targetName))
            CellKickResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.kick.cell_not_found")
            CellKickResult.NOT_LEADER -> YamlFactory.getMessage("commands.cell.kick.not_leader")
            CellKickResult.NOT_MEMBER -> YamlFactory.getMessage("commands.cell.kick.not_member", mapOf("player" to targetName))
            CellKickResult.CANNOT_KICK_LEADER -> YamlFactory.getMessage("commands.cell.kick.cannot_kick_leader")
            CellKickResult.INSUFFICIENT_PERMISSION -> YamlFactory.getMessage("commands.cell.kick.insufficient_permission")
            CellKickResult.ERROR -> YamlFactory.getMessage("commands.cell.kick.error")
        }

        actor.sendMessage(Component.text(message))
    }

    @Subcommand("desc")
    @Description("Set your cell's description")
    suspend fun cellDescription(actor: Player, @Named("description") message: String) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.description.not_in_cell")))
            return
        }

        // Use the new permission system
        if (!cell.hasPermission(actor.uuid, "description")) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.description.not_leader")))
            return
        }

        // Check if user wants to remove the description
        val newDescription = if (message.equals("remove", ignoreCase = true)) {
            ""
        } else {
            message
        }

        // Set the description
        val result = cellManager.setDescription(cell.id, actor.uuid, newDescription)

        val responseMessage = when (result) {
            CellUpdateResult.SUCCESS -> {
                if (newDescription.isEmpty()) {
                    YamlFactory.getMessage("commands.cell.description.removed")
                } else {
                    YamlFactory.getMessage("commands.cell.description.success")
                }
            }
            CellUpdateResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.description.cell_not_found")
            CellUpdateResult.NOT_LEADER -> YamlFactory.getMessage("commands.cell.description.not_leader")
            CellUpdateResult.INVALID_INPUT -> YamlFactory.getMessage("commands.cell.description.invalid_input")
            CellUpdateResult.ERROR -> YamlFactory.getMessage("commands.cell.description.error")
        }

        actor.sendMessage(Component.text(responseMessage))
    }

    @Subcommand("announce")
    @Description("Make an announcement to your cell")
    suspend fun cellAnnounce(actor: Player, @Named("announcement") announcement: String) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.announce.not_in_cell")))
            return
        }

        // Use the new permission system
        if (!cell.hasPermission(actor.uuid, "announce")) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.announce.not_leader")))
            return
        }

        // Check if user wants to remove the announcement
        val newAnnouncement = if (announcement.equals("remove", ignoreCase = true)) {
            ""
        } else {
            announcement
        }

        // Set the announcement
        val result = cellManager.setAnnouncement(cell.id, actor.uuid, newAnnouncement)

        val responseMessage = when (result) {
            CellUpdateResult.SUCCESS -> {
                if (newAnnouncement.isEmpty()) {
                    YamlFactory.getMessage("commands.cell.announce.removed")
                } else {
                    YamlFactory.getMessage("commands.cell.announce.success")
                }
            }
            CellUpdateResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.announce.cell_not_found")
            CellUpdateResult.NOT_LEADER -> YamlFactory.getMessage("commands.cell.announce.not_leader")
            CellUpdateResult.INVALID_INPUT -> YamlFactory.getMessage("commands.cell.announce.invalid_input")
            CellUpdateResult.ERROR -> YamlFactory.getMessage("commands.cell.announce.error")
        }

        actor.sendMessage(Component.text(responseMessage))
    }

    @Subcommand("promote")
    @Description("Promote a player to the next higher role in your cell")
    suspend fun cellPromote(actor: Player, @Named("player") targetName: String) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.not_in_cell")))
            return
        }

        if (!cell.hasPermission(actor.uuid, "promote")) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.no_permission")))
            return
        }

        // Get target player UUID
        val targetUuid = try {
            MojangUtils.getUUID(targetName)
        } catch (e: IOException) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.player_not_found", mapOf("player" to targetName))))
            return
        }

        // Check if target is in the cell
        if (!cell.members.containsKey(targetUuid)) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.not_member", mapOf("player" to targetName))))
            return
        }

        val currentRole = cell.members[targetUuid]!!

        // Calculate next role up
        val nextRole = when (currentRole) {
            CellRole.MEMBER -> CellRole.CAPTAIN
            CellRole.CAPTAIN -> CellRole.CO_LEADER
            CellRole.CO_LEADER -> {
                actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.already_max_role", mapOf("player" to targetName))))
                return
            }
            CellRole.LEADER -> {
                actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.cannot_promote_leader")))
                return
            }
        }

        // Promote the player
        val result = cellManager.promotePlayer(cell.id, actor.uuid, targetUuid)

        val message = when (result) {
            CellRoleResult.SUCCESS -> YamlFactory.getMessage("commands.cell.role.promote_success", mapOf(
                "player" to targetName,
                "role" to nextRole.displayName
            ))
            CellRoleResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.role.cell_not_found")
            CellRoleResult.NOT_LEADER -> YamlFactory.getMessage("commands.cell.role.not_leader")
            CellRoleResult.NOT_MEMBER -> YamlFactory.getMessage("commands.cell.role.not_member", mapOf("player" to targetName))
            CellRoleResult.CANNOT_PROMOTE_LEADER -> YamlFactory.getMessage("commands.cell.role.cannot_promote_leader")
            CellRoleResult.CANNOT_DEMOTE_LEADER -> YamlFactory.getMessage("commands.cell.role.cannot_demote_leader")
            CellRoleResult.ALREADY_AT_ROLE -> YamlFactory.getMessage("commands.cell.role.already_max_role", mapOf("player" to targetName))
            CellRoleResult.INSUFFICIENT_PERMISSION -> YamlFactory.getMessage("commands.cell.role.insufficient_permission_promote", mapOf("role" to nextRole.displayName))
            CellRoleResult.INVALID_ROLE -> YamlFactory.getMessage("commands.cell.role.invalid_role_target")
            CellRoleResult.ERROR -> YamlFactory.getMessage("commands.cell.role.error")
        }

        actor.sendMessage(Component.text(message))

        // Notify the promoted player if online
        if (result == CellRoleResult.SUCCESS) {
            val promotedPlayer = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(targetUuid)
            if (promotedPlayer != null) {
                val notificationMessage = YamlFactory.getMessage("commands.cell.role.promote_notification", mapOf(
                    "role" to nextRole.displayName,
                    "cell" to cell.name,
                    "promoter" to actor.username
                ))
                promotedPlayer.sendMessage(Component.text(notificationMessage))
            }
        }
    }

    @Subcommand("demote")
    @Description("Demote a player to the next lower role in your cell")
    suspend fun cellDemote(actor: Player, @Named("player") targetName: String) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.not_in_cell")))
            return
        }

        if (!cell.hasPermission(actor.uuid, "demote")) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.no_permission")))
            return
        }

        // Get target player UUID
        val targetUuid = try {
            MojangUtils.getUUID(targetName)
        } catch (e: IOException) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.player_not_found", mapOf("player" to targetName))))
            return
        }

        // Check if target is in the cell
        if (!cell.members.containsKey(targetUuid)) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.not_member", mapOf("player" to targetName))))
            return
        }

        val currentRole = cell.members[targetUuid]!!

        // Calculate next role down
        val nextRole = when (currentRole) {
            CellRole.CO_LEADER -> CellRole.CAPTAIN
            CellRole.CAPTAIN -> CellRole.MEMBER
            CellRole.MEMBER -> {
                actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.already_lowest_role", mapOf("player" to targetName))))
                return
            }
            CellRole.LEADER -> {
                actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.role.cannot_demote_leader")))
                return
            }
        }

        // Demote the player
        val result = cellManager.demotePlayer(cell.id, actor.uuid, targetUuid)

        val message = when (result) {
            CellRoleResult.SUCCESS -> YamlFactory.getMessage("commands.cell.role.demote_success", mapOf(
                "player" to targetName,
                "role" to nextRole.displayName
            ))
            CellRoleResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.role.cell_not_found")
            CellRoleResult.NOT_LEADER -> YamlFactory.getMessage("commands.cell.role.not_leader")
            CellRoleResult.NOT_MEMBER -> YamlFactory.getMessage("commands.cell.role.not_member", mapOf("player" to targetName))
            CellRoleResult.CANNOT_PROMOTE_LEADER -> YamlFactory.getMessage("commands.cell.role.cannot_promote_leader")
            CellRoleResult.CANNOT_DEMOTE_LEADER -> YamlFactory.getMessage("commands.cell.role.cannot_demote_leader")
            CellRoleResult.ALREADY_AT_ROLE -> YamlFactory.getMessage("commands.cell.role.already_lowest_role", mapOf("player" to targetName))
            CellRoleResult.INSUFFICIENT_PERMISSION -> YamlFactory.getMessage("commands.cell.role.insufficient_permission_demote")
            CellRoleResult.INVALID_ROLE -> YamlFactory.getMessage("commands.cell.role.invalid_role_target")
            CellRoleResult.ERROR -> YamlFactory.getMessage("commands.cell.role.error")
        }

        actor.sendMessage(Component.text(message))

        // Notify the demoted player if online
        if (result == CellRoleResult.SUCCESS) {
            val demotedPlayer = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(targetUuid)
            if (demotedPlayer != null) {
                val notificationMessage = YamlFactory.getMessage("commands.cell.role.demote_notification", mapOf(
                    "role" to nextRole.displayName,
                    "cell" to cell.name,
                    "demoter" to actor.username
                ))
                demotedPlayer.sendMessage(Component.text(notificationMessage))
            }
        }
    }

    @Subcommand("upgrade")
    @Description("Upgrade your cell to the next level")
    suspend fun cellUpgrade(actor: Player) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.upgrade.not_in_cell")))
            return
        }

        // Check if player has permission to upgrade (Co-Leaders and Leaders)
        if (!cell.hasPermission(actor.uuid, "upgrade")) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.upgrade.insufficient_permission")))
            return
        }

        // Get upgrade configuration
        val upgradeConfig = cellManager.getCellUpgradeConfig()

        val requiredBalance = cell.getRequiredBalanceForLevel(cell.level + 1, upgradeConfig.basePrice, upgradeConfig.priceIncrease)

        // Check if cell has enough balance
        if (cell.balance < requiredBalance) {
            val message = YamlFactory.getMessage("commands.cell.upgrade.insufficient_balance", mapOf(
                "required" to twizzy.tech.util.CompactNotation.format(requiredBalance),
                "current" to twizzy.tech.util.CompactNotation.format(cell.balance),
                "needed" to twizzy.tech.util.CompactNotation.format(requiredBalance.subtract(cell.balance))
            ))
            actor.sendMessage(Component.text(message))
            return
        }

        // Perform the upgrade (this will now consume the cell balance)
        val result = cellManager.upgradeCell(cell.id, actor.uuid)

        val message = when (result) {
            CellUpgradeResult.SUCCESS -> {
                // Notify all online cell members of the upgrade
                val allMembers = cell.getAllMembers()
                val notification = YamlFactory.getMessage("commands.cell.upgrade.success_notification", mapOf(
                    "cell" to cell.name,
                    "level" to (cell.level).toString(),
                    "upgrader" to actor.username
                ))

                allMembers.forEach { memberId ->
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(memberId)?.let { member ->
                        member.sendMessage(Component.text(notification))
                    }
                }

                YamlFactory.getMessage("commands.cell.upgrade.success", mapOf(
                    "level" to cell.level.toString()
                ))
            }
            CellUpgradeResult.CELL_NOT_FOUND -> YamlFactory.getMessage("commands.cell.upgrade.cell_not_found")
            CellUpgradeResult.INSUFFICIENT_PERMISSION -> YamlFactory.getMessage("commands.cell.upgrade.insufficient_permission")
            CellUpgradeResult.INSUFFICIENT_VALUE -> YamlFactory.getMessage("commands.cell.upgrade.insufficient_balance_generic")
            CellUpgradeResult.ALREADY_MAX_LEVEL -> YamlFactory.getMessage("commands.cell.upgrade.max_level")
            CellUpgradeResult.ERROR -> YamlFactory.getMessage("commands.cell.upgrade.error")
        }

        actor.sendMessage(Component.text(message))
    }

    @Subcommand("balance")
    @Description("Add balance to your cell or check current balance")
    suspend fun cellBalance(actor: Player, @Optional @Named("amount") amount: String?) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.balance.not_in_cell")))
            return
        }

        if (amount == null) {
            // Just display current balance and upgrade info
            val upgradeConfig = cellManager.getCellUpgradeConfig()
            val nextUpgradeBalance = cell.getNextUpgradeRequiredBalance(upgradeConfig.basePrice, upgradeConfig.priceIncrease)

            actor.sendMessage(Component.text("§6§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"))
            actor.sendMessage(Component.text("§6§lCELL BALANCE - ${cell.name}"))
            actor.sendMessage(Component.text("§7"))
            actor.sendMessage(Component.text("§e📊 Current Level: §f${cell.level}"))
            actor.sendMessage(Component.text("§e💰 Current Balance: §f${String.format("%,.0f", cell.balance)}"))

            if (nextUpgradeBalance != null) {
                val remaining = nextUpgradeBalance.subtract(cell.balance)
                actor.sendMessage(Component.text("§e⬆ Next Level (${cell.level + 1}): §f${String.format("%,.0f", nextUpgradeBalance)}"))
                if (remaining > BigDecimal.ZERO) {
                    actor.sendMessage(Component.text("§e⏳ Remaining: §f${String.format("%,.0f", remaining)}"))
                } else {
                    actor.sendMessage(Component.text("§a✅ Ready to upgrade!"))
                }
            } else {
                actor.sendMessage(Component.text("§a🏆 Max level reached!"))
            }

            actor.sendMessage(Component.text("§6§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"))
            return
        }

        // Check if player is the leader
        if (!cell.isLeader(actor.uuid)) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.balance.not_leader")))
            return
        }

        // Parse amount
        val balanceToAdd = try {
            BigDecimal(amount)
        } catch (e: NumberFormatException) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.balance.invalid_amount")))
            return
        }

        if (balanceToAdd <= BigDecimal.ZERO) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.balance.positive_only")))
            return
        }

        // Add balance to cell
        val success = cellManager.addCellBalance(cell.id, balanceToAdd)

        if (success) {
            val message = YamlFactory.getMessage("commands.cell.balance.added", mapOf(
                "amount" to String.format("%,.0f", balanceToAdd),
                "total" to String.format("%,.0f", cell.balance.add(balanceToAdd))
            ))
            actor.sendMessage(Component.text(message))
        } else {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.balance.error")))
        }
    }

    @Subcommand("deposit")
    @Description("Deposit money from your balance into your cell's balance")
    suspend fun cellDeposit(actor: Player, @Named("amount") amount: String) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.deposit.not_in_cell")))
            return
        }

        // Allow any cell member to deposit (removed leader-only restriction)
        // Parse deposit amount - now supports compact notation
        val depositAmount = try {
            BigDecimal.valueOf(twizzy.tech.util.CompactNotation.parse(amount))
        } catch (e: NumberFormatException) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.deposit.invalid_amount")))
            return
        } catch (e: IllegalArgumentException) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.deposit.invalid_amount")))
            return
        }

        if (depositAmount <= BigDecimal.ZERO) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.deposit.positive_only")))
            return
        }

        // Get player's current balance
        val playerData = PlayerData.getFromCache(actor.uuid)
        if (playerData == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.deposit.data_error")))
            return
        }

        // Check if player has enough balance
        if (playerData.balance < depositAmount) {
            val message = YamlFactory.getMessage("commands.cell.deposit.insufficient_balance", mapOf(
                "required" to twizzy.tech.util.CompactNotation.format(depositAmount),
                "current" to twizzy.tech.util.CompactNotation.format(playerData.balance)
            ))
            actor.sendMessage(Component.text(message))
            return
        }

        // Perform the transaction
        val balanceSuccess = PlayerData.addBalance(actor.uuid, depositAmount.negate())
        if (!balanceSuccess) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.deposit.balance_error")))
            return
        }

        val cellBalanceSuccess = cellManager.addCellBalance(cell.id, depositAmount)
        if (!cellBalanceSuccess) {
            // Rollback the balance change if cell balance update failed
            PlayerData.addBalance(actor.uuid, depositAmount)
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.deposit.cell_error")))
            return
        }

        // Success message with compact notation formatting
        val message = YamlFactory.getMessage("commands.cell.deposit.success", mapOf(
            "amount" to twizzy.tech.util.CompactNotation.format(depositAmount),
            "cell_balance" to twizzy.tech.util.CompactNotation.format(cell.balance),
            "remaining_balance" to twizzy.tech.util.CompactNotation.format(playerData.balance)
        ))
        actor.sendMessage(Component.text(message))

        // Notify other online cell members
        val allMembers = cell.getAllMembers()
        val notification = YamlFactory.getMessage("commands.cell.deposit.notification", mapOf(
            "player" to actor.username,
            "amount" to twizzy.tech.util.CompactNotation.format(depositAmount),
            "cell" to cell.name
        ))

        allMembers.forEach { memberId ->
            if (memberId != actor.uuid) { // Don't notify the depositor
                MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(memberId)?.let { member ->
                    member.sendMessage(Component.text(notification))
                }
            }
        }
    }

    @Subcommand("go")
    @Description("Go to your cell's vault (requires level 10+)")
    suspend fun cellGo(actor: Player) {
        val cell = cellManager.getPlayerCell(actor.uuid)
        if (cell == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.go.not_in_cell")))
            return
        }

        // Check if cell is level 10 or higher
        if (cell.level < 10) {
            val message = YamlFactory.getMessage("commands.cell.go.insufficient_level", mapOf(
                "current" to cell.level.toString(),
                "required" to "10"
            ))
            actor.sendMessage(Component.text(message))
            return
        }

        // Get the instance map (we need to add this to the Engine or inject it)
        val instanceMap = twizzy.tech.game.Engine.instanceMap
        if (instanceMap == null) {
            actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.go.error")))
            return
        }

        // Get or create the cell vault
        var vaultInstance = instanceMap.getCellVault(cell.id)
        if (vaultInstance == null) {
            vaultInstance = instanceMap.createCellVault(cell.id)
            if (vaultInstance == null) {
                actor.sendMessage(Component.text(YamlFactory.getMessage("commands.cell.go.creation_failed")))
                return
            }
        }

        // Get the vault spawn position
        val spawnPos = instanceMap.getCellVaultSpawn(cell.id)

        // Teleport the player
        actor.setInstance(vaultInstance, spawnPos)

        val message = YamlFactory.getMessage("commands.cell.go.success", mapOf(
            "cell" to cell.name
        ))
        actor.sendMessage(Component.text(message))
    }

    private fun getRequiredLeaderRank(): Int {
        val config = twizzy.tech.game.Engine.gameConfig ?: return 5
        return YamlFactory.getValue(config, "cells.leader-rank", 5)
    }
}