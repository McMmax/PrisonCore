package twizzy.tech.game.cells

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import twizzy.tech.util.JaSync
import twizzy.tech.util.YamlFactory
import twizzy.tech.player.PlayerData
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import net.minestom.server.utils.UUIDUtils
import net.minestom.server.utils.mojang.MojangUtils
import java.time.Instant
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.math.BigDecimal

/**
 * Result types for cell operations
 */
enum class CellCreationResult {
    SUCCESS,
    ALREADY_IN_CELL,
    NAME_TAKEN,
    PLAYER_DATA_ERROR,
    INSUFFICIENT_RANK,
    ERROR
}

enum class CellDeletionResult {
    SUCCESS,
    CELL_NOT_FOUND,
    NOT_LEADER,
    ERROR
}

enum class CellJoinResult {
    SUCCESS,
    CELL_NOT_FOUND,
    ALREADY_IN_CELL,
    CELL_FULL,
    ERROR
}

enum class CellLeaveResult {
    SUCCESS,
    NOT_IN_CELL,
    CELL_NOT_FOUND,
    LEADER_CANNOT_LEAVE,
    ERROR
}

enum class InvitationResult {
    SUCCESS,
    CELL_NOT_FOUND,
    NOT_LEADER,
    ALREADY_IN_CELL,
    CELL_FULL,
    ALREADY_INVITED,
    NO_INVITATION,
    ERROR
}

enum class CellKickResult {
    SUCCESS,
    CELL_NOT_FOUND,
    NOT_LEADER,
    NOT_MEMBER,
    CANNOT_KICK_LEADER,
    INSUFFICIENT_PERMISSION,
    ERROR
}

enum class CellUpdateResult {
    SUCCESS,
    CELL_NOT_FOUND,
    NOT_LEADER,
    INVALID_INPUT,
    ERROR
}

enum class CellRoleResult {
    SUCCESS,
    CELL_NOT_FOUND,
    NOT_LEADER,
    NOT_MEMBER,
    CANNOT_PROMOTE_LEADER,
    CANNOT_DEMOTE_LEADER,
    ALREADY_AT_ROLE,
    INSUFFICIENT_PERMISSION,
    INVALID_ROLE,
    ERROR
}

enum class CellUpgradeResult {
    SUCCESS,
    CELL_NOT_FOUND,
    INSUFFICIENT_PERMISSION,
    INSUFFICIENT_VALUE,
    ALREADY_MAX_LEVEL,
    ERROR
}

/**
 * Represents different roles within a cell
 */
enum class CellRole(val displayName: String, val level: Int) {
    MEMBER("Member", 1),
    CAPTAIN("Captain", 2),
    CO_LEADER("Co-Leader", 3),
    LEADER("Leader", 4);

    fun canInvite(): Boolean = level >= CAPTAIN.level
    fun canKick(): Boolean = level >= CAPTAIN.level
    fun canAnnounce(): Boolean = level >= CO_LEADER.level
    fun canSetDescription(): Boolean = level >= LEADER.level
    fun canSetPublic(): Boolean = level >= LEADER.level
    fun canPromote(): Boolean = level >= CO_LEADER.level
    fun canDemote(): Boolean = level >= CO_LEADER.level
    fun canUpgrade(): Boolean = level >= CO_LEADER.level

    /**
     * Gets the maximum role level this role can promote/demote to
     */
    fun getMaxManageableRole(): CellRole {
        return when (this) {
            LEADER -> CO_LEADER
            CO_LEADER -> CAPTAIN
            else -> MEMBER
        }
    }

    companion object {
        fun fromString(role: String): CellRole? {
            return values().find { it.name.equals(role, ignoreCase = true) }
        }
    }
}

/**
 * Optimized Cell data class using Instant for timestamps and simplified invitation tracking
 */
data class Cell(
    val id: String,
    val name: String,
    val leaderId: UUID,
    val members: MutableMap<UUID, CellRole> = mutableMapOf(),
    val createdAt: Instant = Instant.now(),
    var description: String = "",
    var announcement: String = "",
    var isPublic: Boolean = false,
    var level: Int = 1,
    var balance: BigDecimal = BigDecimal.ZERO,
    val pendingInvitations: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
) {
    companion object {
        const val MAX_DESCRIPTION_LENGTH = 500
        const val MAX_ANNOUNCEMENT_LENGTH = 500
        val INVITATION_EXPIRY_DURATION: Duration = Duration.ofDays(3)
    }

    /**
     * Calculates the required balance for a specific cell level
     */
    fun getRequiredBalanceForLevel(targetLevel: Int, basePrice: Double, priceIncrease: Double): BigDecimal {
        if (targetLevel <= 1) return BigDecimal.ZERO

        val price = basePrice * Math.pow(1.0 + (priceIncrease / 100.0), (targetLevel - 2).toDouble())
        return BigDecimal.valueOf(price)
    }

    /**
     * Gets the required balance for the next level upgrade
     */
    fun getNextUpgradeRequiredBalance(basePrice: Double, priceIncrease: Double): BigDecimal? {
        return getRequiredBalanceForLevel(level + 1, basePrice, priceIncrease)
    }

    /**
     * Checks if the cell is full based on the max members limit from config
     */
    fun isFull(): Boolean {
        val config = twizzy.tech.game.Engine.gameConfig ?: emptyMap<String, Any>()
        val maxMembers = YamlFactory.getValue(config, "cells.member-limit", 25)
        return members.size >= maxMembers
    }

    /**
     * Checks if a player is a member of this cell
     */
    fun isMember(playerUuid: UUID): Boolean = members.containsKey(playerUuid) || leaderId == playerUuid

    /**
     * Checks if a player is the leader of this cell
     */
    fun isLeader(playerUuid: UUID): Boolean = leaderId == playerUuid

    /**
     * Gets a player's role in this cell
     */
    fun getPlayerRole(playerUuid: UUID): CellRole? {
        return if (leaderId == playerUuid) {
            CellRole.LEADER
        } else {
            members[playerUuid]
        }
    }

    /**
     * Checks if a player has permission to perform an action
     */
    fun hasPermission(playerUuid: UUID, permission: String): Boolean {
        val role = getPlayerRole(playerUuid) ?: return false
        return when (permission) {
            "invite" -> role.canInvite()
            "kick" -> role.canKick()
            "announce" -> role.canAnnounce()
            "description" -> role.canSetDescription()
            "public" -> role.canSetPublic()
            "promote" -> role.canPromote()
            "demote" -> role.canDemote()
            "upgrade" -> role.canUpgrade()
            else -> false
        }
    }

    /**
     * Gets all members including the leader
     */
    fun getAllMembers(): Set<UUID> = members.keys + leaderId

    /**
     * Gets members by role
     */
    fun getMembersByRole(role: CellRole): List<UUID> {
        return if (role == CellRole.LEADER) {
            listOf(leaderId)
        } else {
            members.filter { it.value == role }.map { it.key }
        }
    }

    /**
     * Checks if a player has a pending invitation to this cell
     */
    fun hasInvitation(playerId: UUID): Boolean = pendingInvitations.contains(playerId)

    /**
     * Adds an invitation to this cell
     */
    fun addInvitation(playerId: UUID): Boolean = pendingInvitations.add(playerId)

    /**
     * Removes an invitation from this cell
     */
    fun removeInvitation(playerId: UUID): Boolean = pendingInvitations.remove(playerId)
}

/**
 * Optimized CellManager with improved performance and memory usage
 */
class CellManager private constructor() {
    private val cells = ConcurrentHashMap<String, Cell>()
    private val playerToCellMap = ConcurrentHashMap<UUID, String>()
    private val jaSync = JaSync.getInstance()
    private val cellMutex = Mutex()

    // Background maintenance job for cleanup tasks
    private val maintenanceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var maintenanceJob: Job? = null

    companion object {
        private var instance: CellManager? = null

        fun getInstance(): CellManager = instance ?: synchronized(this) {
            instance ?: CellManager().also { instance = it }
        }
    }

    /**
     * Initializes the cell manager and starts background maintenance
     */
    suspend fun initialize() {
        try {
            createTablesIfNotExist()
            loadAllCells()
            startMaintenanceJob()
            println("[CellManager] Initialization completed successfully. Loaded ${cells.size} cells.")
        } catch (e: Exception) {
            println("[CellManager] Failed to initialize: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Creates the optimized database schema
     */
    private suspend fun createTablesIfNotExist() {
        try {
            println("[CellManager] Creating/verifying optimized database schema...")

            jaSync.executeQuery("""
                CREATE TABLE IF NOT EXISTS cells (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    name VARCHAR(100) NOT NULL UNIQUE,
                    leader_id UUID NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    description TEXT NOT NULL DEFAULT '',
                    announcement TEXT NOT NULL DEFAULT '',
                    is_public BOOLEAN NOT NULL DEFAULT FALSE,
                    last_updated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    level INT NOT NULL DEFAULT 1,
                    balance NUMERIC(20,6) NOT NULL DEFAULT 0
                );
                
                CREATE TABLE IF NOT EXISTS cell_members (
                    cell_id UUID NOT NULL REFERENCES cells(id) ON DELETE CASCADE,
                    player_id UUID NOT NULL,
                    role VARCHAR(12) NOT NULL DEFAULT 'MEMBER' 
                        CHECK (role IN ('MEMBER','CAPTAIN','CO_LEADER','LEADER')),
                    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (cell_id, player_id)
                );
                
                CREATE TABLE IF NOT EXISTS cell_invitations (
                    cell_id UUID NOT NULL REFERENCES cells(id) ON DELETE CASCADE,
                    inviter_id UUID NOT NULL,
                    invitee_id UUID NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (cell_id, invitee_id)
                );
                
                -- Create optimized indexes
                CREATE INDEX IF NOT EXISTS idx_cells_leader_id ON cells(leader_id);
                CREATE INDEX IF NOT EXISTS idx_cells_name ON cells(name);
                CREATE INDEX IF NOT EXISTS idx_cells_public ON cells(is_public) WHERE is_public = true;
                CREATE INDEX IF NOT EXISTS idx_cell_members_player ON cell_members(player_id);
                CREATE INDEX IF NOT EXISTS idx_cell_invitations_invitee ON cell_invitations(invitee_id);
                CREATE INDEX IF NOT EXISTS idx_cell_invitations_expiry ON cell_invitations(created_at);
            """.trimIndent())

            println("[CellManager] Database schema verified successfully")
        } catch (e: Exception) {
            println("[CellManager] Failed to create database schema: ${e.message}")
            throw e
        }
    }

    /**
     * Optimized batch loading of all cells from database
     */
    private suspend fun loadAllCells() {
        try {
            // Load cells in a single query
            val cellsResult = jaSync.executeQuery("SELECT * FROM cells ORDER BY created_at")
            val cellMap = mutableMapOf<String, Cell>()

            cellsResult.rows.forEach { row ->
                val cellId = jaSync.getString(row, "id")!!
                val leaderId = jaSync.getUUID(row, "leader_id")!!
                val createdAt = jaSync.getInstant(row, "created_at") ?: Instant.now()

                val cell = Cell(
                    id = cellId,
                    name = jaSync.getString(row, "name")!!,
                    leaderId = leaderId,
                    createdAt = createdAt,
                    description = jaSync.getString(row, "description") ?: "",
                    announcement = jaSync.getString(row, "announcement") ?: "",
                    isPublic = jaSync.getBoolean(row, "is_public") ?: false,
                    level = jaSync.getInt(row, "level") ?: 1,
                    balance = jaSync.getBigDecimal(row, "balance") ?: BigDecimal.ZERO
                )

                cellMap[cellId] = cell
                playerToCellMap[leaderId] = cellId
            }

            // Batch load all members
            val membersResult = jaSync.executeQuery("""
                SELECT cm.cell_id, cm.player_id, cm.role 
                FROM cell_members cm
                JOIN cells c ON cm.cell_id = c.id
            """)

            membersResult.rows.forEach { row ->
                val cellId = jaSync.getString(row, "cell_id")!!
                val playerId = jaSync.getUUID(row, "player_id")!!
                val role = CellRole.fromString(jaSync.getString(row, "role")!!) ?: CellRole.MEMBER

                cellMap[cellId]?.let { cell ->
                    cell.members[playerId] = role
                    playerToCellMap[playerId] = cellId
                }
            }

            // Batch load non-expired invitations
            val expiryTime = Instant.now().minus(Cell.INVITATION_EXPIRY_DURATION)
            val invitationsResult = jaSync.executeQuery("""
                SELECT cell_id, invitee_id 
                FROM cell_invitations 
                WHERE created_at > '${expiryTime}'
            """)

            invitationsResult.rows.forEach { row ->
                val cellId = jaSync.getString(row, "cell_id")!!
                val inviteeId = jaSync.getUUID(row, "invitee_id")!!
                cellMap[cellId]?.pendingInvitations?.add(inviteeId)
            }

            // Clean up expired invitations in background
            CoroutineScope(Dispatchers.IO).launch {
                jaSync.executeQuery("DELETE FROM cell_invitations WHERE created_at <= '${expiryTime}'")
            }

            cells.putAll(cellMap)

        } catch (e: Exception) {
            println("[CellManager] Failed to load cells: ${e.message}")
            throw e
        }
    }

    /**
     * Starts the background maintenance job for periodic cleanup
     */
    private fun startMaintenanceJob() {
        maintenanceJob = maintenanceScope.launch {
            while (isActive) {
                try {
                    delay(TimeUnit.HOURS.toMillis(1)) // Run every hour
                    cleanupExpiredInvitations()
                } catch (e: Exception) {
                    println("[CellManager] Maintenance job error: ${e.message}")
                }
            }
        }
    }

    /**
     * Optimized cell creation with conflict handling
     */
    suspend fun createCell(name: String, leaderId: UUID): CellCreationResult = cellMutex.withLock {
        try {
            // Check if player already has a cell
            if (playerToCellMap.containsKey(leaderId)) {
                return CellCreationResult.ALREADY_IN_CELL
            }

            // Check rank requirement
            val playerData = PlayerData.findFromCache(leaderId)
            if (playerData == null) {
                return CellCreationResult.PLAYER_DATA_ERROR
            }

            val requiredRank = getRequiredLeaderRank()
            if (playerData.rank < requiredRank) {
                return CellCreationResult.INSUFFICIENT_RANK
            }

            // Use upsert with conflict handling
            val result = jaSync.executeUpsert("""
                INSERT INTO cells (name, leader_id) 
                VALUES (?, ?) 
                ON CONFLICT (name) DO NOTHING
                RETURNING id
            """, listOf(name, leaderId))

            if (result.rows.isEmpty()) {
                return CellCreationResult.NAME_TAKEN
            }

            val cellId = jaSync.getString(result.rows.first(), "id")!!
            val cell = Cell(cellId, name, leaderId)

            // Update memory maps
            cells[cellId] = cell
            playerToCellMap[leaderId] = cellId
            PlayerData.setCellID(leaderId, cellId)

            println("[CellManager] Created cell '$name' with ID $cellId")
            return CellCreationResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to create cell: ${e.message}")
            return CellCreationResult.ERROR
        }
    }

    /**
     * Optimized invitation sending with batch operations
     */
    suspend fun sendInvitation(cellId: String, inviterId: UUID, inviteeId: UUID): InvitationResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return InvitationResult.CELL_NOT_FOUND

            // Permission checks
            if (!cell.hasPermission(inviterId, "invite")) {
                return InvitationResult.NOT_LEADER
            }

            if (playerToCellMap.containsKey(inviteeId)) {
                return InvitationResult.ALREADY_IN_CELL
            }

            if (cell.isFull()) {
                return InvitationResult.CELL_FULL
            }

            if (cell.hasInvitation(inviteeId)) {
                return InvitationResult.ALREADY_INVITED
            }

            // Insert invitation with conflict handling
            val result = jaSync.executeUpsert("""
                INSERT INTO cell_invitations (cell_id, inviter_id, invitee_id)
                VALUES (?, ?, ?)
                ON CONFLICT (cell_id, invitee_id) DO NOTHING
            """, listOf(cellId, inviterId, inviteeId))

            if (result.rowsAffected.toInt() == 0) {
                return InvitationResult.ALREADY_INVITED
            }

            // Update memory
            cell.addInvitation(inviteeId)

            // Send notification if player is online
            MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(inviteeId)?.let { player ->
                sendInvitationNotification(player, cell, inviterId)
            }

            return InvitationResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to send invitation: ${e.message}")
            return InvitationResult.ERROR
        }
    }

    /**
     * Optimized invitation acceptance
     */
    suspend fun acceptInvitation(cellId: String, playerId: UUID): InvitationResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return InvitationResult.CELL_NOT_FOUND

            if (!cell.hasInvitation(playerId)) {
                return InvitationResult.NO_INVITATION
            }

            if (playerToCellMap.containsKey(playerId)) {
                // Clean up invitation since player is already in a cell
                removeInvitationFromDatabase(cellId, playerId)
                cell.removeInvitation(playerId)
                return InvitationResult.ALREADY_IN_CELL
            }

            if (cell.isFull()) {
                return InvitationResult.CELL_FULL
            }

            // Add member and remove invitation in single transaction
            jaSync.executeBatch(listOf(
                "INSERT INTO cell_members (cell_id, player_id, role) VALUES ('$cellId', '$playerId', 'MEMBER')",
                "DELETE FROM cell_invitations WHERE cell_id = '$cellId' AND invitee_id = '$playerId'"
            ))

            // Update memory
            cell.members[playerId] = CellRole.MEMBER
            playerToCellMap[playerId] = cellId
            cell.removeInvitation(playerId)
            PlayerData.setCellID(playerId, cellId)

            return InvitationResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to accept invitation: ${e.message}")
            return InvitationResult.ERROR
        }
    }

    /**
     * Optimized cleanup of expired invitations
     */
    suspend fun cleanupExpiredInvitations() {
        try {
            val expiryTime = Instant.now().minus(Cell.INVITATION_EXPIRY_DURATION)
            val deletedCount = jaSync.queryForCount("""
                WITH deleted AS (
                    DELETE FROM cell_invitations 
                    WHERE created_at <= '$expiryTime'
                    RETURNING cell_id, invitee_id
                )
                SELECT COUNT(*) FROM deleted
            """)

            // Get all current valid invitations from database to sync memory
            val validInvitationsResult = jaSync.executeQuery("""
                SELECT cell_id, invitee_id 
                FROM cell_invitations
            """)

            // Build a set of valid invitation pairs for quick lookup
            val validInvitations = mutableSetOf<String>()
            validInvitationsResult.rows.forEach { row ->
                val cellId = jaSync.getString(row, "cell_id")!!
                val inviteeId = jaSync.getUUID(row, "invitee_id")!!
                validInvitations.add("$cellId:$inviteeId")
            }

            // Update memory maps by removing invitations that don't exist in database
            cells.values.forEach { cell ->
                val toRemove = mutableListOf<UUID>()
                cell.pendingInvitations.forEach { inviteeId ->
                    val key = "${cell.id}:$inviteeId"
                    if (!validInvitations.contains(key)) {
                        toRemove.add(inviteeId)
                    }
                }
                toRemove.forEach { inviteeId ->
                    cell.pendingInvitations.remove(inviteeId)
                }
            }

            if (deletedCount > 0) {
                println("[CellManager] Cleaned up $deletedCount expired invitations")
            }
        } catch (e: Exception) {
            println("[CellManager] Failed to cleanup expired invitations: ${e.message}")
        }
    }

    /**
     * Deletes a cell
     */
    suspend fun deleteCell(cellId: String, requesterId: UUID): CellDeletionResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return CellDeletionResult.CELL_NOT_FOUND

            // Check if requester is the leader
            if (!cell.isLeader(requesterId)) {
                return CellDeletionResult.NOT_LEADER
            }

            // Reset cellID for all members in PlayerData
            cell.getAllMembers().forEach { memberId ->
                PlayerData.setCellID(memberId, null)
            }

            // Delete from database (cascade will handle members and invitations)
            jaSync.executePreparedStatement("DELETE FROM cells WHERE id = ?", listOf(cellId))

            // Remove from memory
            cell.getAllMembers().forEach { memberId ->
                playerToCellMap.remove(memberId)
            }
            cells.remove(cellId)

            println("[CellManager] Deleted cell '${cell.name}'")
            return CellDeletionResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to delete cell: ${e.message}")
            e.printStackTrace()
            return CellDeletionResult.ERROR
        }
    }

    /**
     * Adds a player to a cell
     */
    suspend fun joinCell(cellId: String, playerId: UUID): CellJoinResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return CellJoinResult.CELL_NOT_FOUND

            // Check if player is already in a cell
            if (playerToCellMap.containsKey(playerId)) {
                return CellJoinResult.ALREADY_IN_CELL
            }

            // Check if cell is full
            if (cell.isFull()) {
                return CellJoinResult.CELL_FULL
            }

            // Add player to cell in database
            jaSync.executePreparedStatement(
                "INSERT INTO cell_members (cell_id, player_id, role) VALUES (?, ?, 'MEMBER')",
                listOf(cellId, playerId, "MEMBER")
            )

            // Add to memory
            cell.members[playerId] = CellRole.MEMBER
            playerToCellMap[playerId] = cellId

            // Update player's cellID in PlayerData
            PlayerData.setCellID(playerId, cellId)

            println("[CellManager] Player $playerId joined cell '${cell.name}'")
            return CellJoinResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to join cell: ${e.message}")
            e.printStackTrace()
            return CellJoinResult.ERROR
        }
    }

    /**
     * Removes a player from their cell
     */
    suspend fun leaveCell(playerId: UUID): CellLeaveResult = cellMutex.withLock {
        try {
            val cellId = playerToCellMap[playerId] ?: return CellLeaveResult.NOT_IN_CELL
            val cell = cells[cellId] ?: return CellLeaveResult.CELL_NOT_FOUND

            // Check if player is the leader
            if (cell.isLeader(playerId)) {
                return CellLeaveResult.LEADER_CANNOT_LEAVE
            }

            // Remove from database
            jaSync.executePreparedStatement(
                "DELETE FROM cell_members WHERE cell_id = ? AND player_id = ?",
                listOf(cellId, playerId)
            )

            // Remove from memory
            cell.members.remove(playerId)
            playerToCellMap.remove(playerId)

            // Reset player's cellID in PlayerData
            PlayerData.setCellID(playerId, null)

            println("[CellManager] Player $playerId left cell '${cell.name}'")
            return CellLeaveResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to leave cell: ${e.message}")
            e.printStackTrace()
            return CellLeaveResult.ERROR
        }
    }

    /**
     * Declines an invitation to join a cell
     */
    suspend fun declineInvitation(cellId: String, playerId: UUID): InvitationResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return InvitationResult.CELL_NOT_FOUND

            // Check if player has an invitation
            if (!cell.hasInvitation(playerId)) {
                return InvitationResult.NO_INVITATION
            }

            // Remove invitation from database
            removeInvitationFromDatabase(cellId, playerId)

            // Remove from memory
            cell.removeInvitation(playerId)

            println("[CellManager] Player declined invitation to cell '${cell.name}'")
            return InvitationResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to decline invitation: ${e.message}")
            e.printStackTrace()
            return InvitationResult.ERROR
        }
    }

    /**
     * Revokes an invitation
     */
    suspend fun revokeInvitation(cellId: String, inviterId: UUID, inviteeId: UUID): InvitationResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return InvitationResult.CELL_NOT_FOUND

            // Check if requester is the cell leader or has invite permissions
            if (!cell.hasPermission(inviterId, "invite")) {
                return InvitationResult.NOT_LEADER
            }

            // Check if invitation exists
            if (!cell.hasInvitation(inviteeId)) {
                return InvitationResult.NO_INVITATION
            }

            // Remove invitation from database
            removeInvitationFromDatabase(cellId, inviteeId)

            // Remove from memory
            cell.removeInvitation(inviteeId)

            // Notify the invitee if online
            val inviteePlayer = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(inviteeId)
            if (inviteePlayer != null) {
                val inviterName = MojangUtils.getUsername(inviterId) ?: "Unknown"
                val message = "§cYour invitation to join cell '${cell.name}' has been revoked by $inviterName"
                inviteePlayer.sendMessage(Component.text(message))
            }

            println("[CellManager] Invitation to $inviteeId for cell '${cell.name}' was revoked")
            return InvitationResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to revoke invitation: ${e.message}")
            e.printStackTrace()
            return InvitationResult.ERROR
        }
    }

    /**
     * Helper method to remove invitation from database
     */
    private suspend fun removeInvitationFromDatabase(cellId: String, playerId: UUID) {
        jaSync.executePreparedStatement(
            "DELETE FROM cell_invitations WHERE cell_id = ? AND invitee_id = ?",
            listOf(cellId, playerId)
        )
    }

    /**
     * Kicks a player from a cell
     */
    suspend fun kickPlayer(cellId: String, kickerId: UUID, targetId: UUID): CellKickResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return CellKickResult.CELL_NOT_FOUND

            // Check if kicker has permission to kick
            if (!cell.hasPermission(kickerId, "kick")) {
                return CellKickResult.INSUFFICIENT_PERMISSION
            }

            // Check if target is a member
            if (!cell.isMember(targetId)) {
                return CellKickResult.NOT_MEMBER
            }

            // Cannot kick the leader
            if (cell.isLeader(targetId)) {
                return CellKickResult.CANNOT_KICK_LEADER
            }

            // Remove from database
            jaSync.executePreparedStatement(
                "DELETE FROM cell_members WHERE cell_id = ? AND player_id = ?",
                listOf(cellId, targetId)
            )

            // Remove from memory
            cell.members.remove(targetId)
            playerToCellMap.remove(targetId)

            // Reset player's cellID in PlayerData
            PlayerData.setCellID(targetId, null)

            println("[CellManager] Player $targetId was kicked from cell '${cell.name}' by $kickerId")
            return CellKickResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to kick player: ${e.message}")
            e.printStackTrace()
            return CellKickResult.ERROR
        }
    }

    /**
     * Sets a cell's description
     */
    suspend fun setDescription(cellId: String, requesterId: UUID, description: String): CellUpdateResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return CellUpdateResult.CELL_NOT_FOUND

            // Check if requester has permission
            if (!cell.hasPermission(requesterId, "description")) {
                return CellUpdateResult.NOT_LEADER
            }

            // Validate input length
            if (description.length > Cell.MAX_DESCRIPTION_LENGTH) {
                return CellUpdateResult.INVALID_INPUT
            }

            // Update in memory
            cell.description = description

            // Update in database
            jaSync.executePreparedStatement(
                "UPDATE cells SET description = ? WHERE id = ?",
                listOf(description, cellId)
            )

            println("[CellManager] Updated description for cell '${cell.name}'")
            return CellUpdateResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to set description: ${e.message}")
            e.printStackTrace()
            return CellUpdateResult.ERROR
        }
    }

    /**
     * Sets a cell's announcement
     */
    suspend fun setAnnouncement(cellId: String, requesterId: UUID, announcement: String): CellUpdateResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return CellUpdateResult.CELL_NOT_FOUND

            // Check if requester has permission
            if (!cell.hasPermission(requesterId, "announce")) {
                return CellUpdateResult.NOT_LEADER
            }

            // Validate input length
            if (announcement.length > Cell.MAX_ANNOUNCEMENT_LENGTH) {
                return CellUpdateResult.INVALID_INPUT
            }

            // Update in memory
            cell.announcement = announcement

            // Update in database
            jaSync.executePreparedStatement(
                "UPDATE cells SET announcement = ? WHERE id = ?",
                listOf(announcement, cellId)
            )

            println("[CellManager] Updated announcement for cell '${cell.name}'")
            return CellUpdateResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to set announcement: ${e.message}")
            e.printStackTrace()
            return CellUpdateResult.ERROR
        }
    }

    /**
     * Promotes a player in a cell
     */
    suspend fun promotePlayer(cellId: String, promoterId: UUID, targetId: UUID): CellRoleResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return CellRoleResult.CELL_NOT_FOUND

            // Check if promoter has permission
            if (!cell.hasPermission(promoterId, "promote")) {
                return CellRoleResult.INSUFFICIENT_PERMISSION
            }

            // Check if target is a member
            if (!cell.isMember(targetId)) {
                return CellRoleResult.NOT_MEMBER
            }

            // Cannot promote the leader
            if (cell.isLeader(targetId)) {
                return CellRoleResult.CANNOT_PROMOTE_LEADER
            }

            val currentRole = cell.getPlayerRole(targetId) ?: return CellRoleResult.NOT_MEMBER
            val promoterRole = cell.getPlayerRole(promoterId) ?: return CellRoleResult.INSUFFICIENT_PERMISSION

            // Calculate next role
            val nextRole = when (currentRole) {
                CellRole.MEMBER -> CellRole.CAPTAIN
                CellRole.CAPTAIN -> CellRole.CO_LEADER
                CellRole.CO_LEADER -> return CellRoleResult.ALREADY_AT_ROLE // Can't promote CO_LEADER further
                CellRole.LEADER -> return CellRoleResult.CANNOT_PROMOTE_LEADER
            }

            // Check if promoter can promote to this level
            if (nextRole.level >= promoterRole.level) {
                return CellRoleResult.INSUFFICIENT_PERMISSION
            }

            // Update in database
            jaSync.executePreparedStatement(
                "UPDATE cell_members SET role = ? WHERE cell_id = ? AND player_id = ?",
                listOf(nextRole.name, cellId, targetId)
            )

            // Update in memory
            cell.members[targetId] = nextRole

            println("[CellManager] Promoted player $targetId to ${nextRole.name} in cell '${cell.name}'")
            return CellRoleResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to promote player: ${e.message}")
            e.printStackTrace()
            return CellRoleResult.ERROR
        }
    }

    /**
     * Demotes a player in a cell
     */
    suspend fun demotePlayer(cellId: String, demoterId: UUID, targetId: UUID): CellRoleResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return CellRoleResult.CELL_NOT_FOUND

            // Check if demoter has permission
            if (!cell.hasPermission(demoterId, "demote")) {
                return CellRoleResult.INSUFFICIENT_PERMISSION
            }

            // Check if target is a member
            if (!cell.isMember(targetId)) {
                return CellRoleResult.NOT_MEMBER
            }

            // Cannot demote the leader
            if (cell.isLeader(targetId)) {
                return CellRoleResult.CANNOT_DEMOTE_LEADER
            }

            val currentRole = cell.getPlayerRole(targetId) ?: return CellRoleResult.NOT_MEMBER
            val demoterRole = cell.getPlayerRole(demoterId) ?: return CellRoleResult.INSUFFICIENT_PERMISSION

            // Calculate previous role
            val previousRole = when (currentRole) {
                CellRole.CO_LEADER -> CellRole.CAPTAIN
                CellRole.CAPTAIN -> CellRole.MEMBER
                CellRole.MEMBER -> return CellRoleResult.ALREADY_AT_ROLE // Can't demote MEMBER further
                CellRole.LEADER -> return CellRoleResult.CANNOT_DEMOTE_LEADER
            }

            // Check if demoter can demote this level
            if (currentRole.level >= demoterRole.level) {
                return CellRoleResult.INSUFFICIENT_PERMISSION
            }

            // Update in database
            jaSync.executePreparedStatement(
                "UPDATE cell_members SET role = ? WHERE cell_id = ? AND player_id = ?",
                listOf(previousRole.name, cellId, targetId)
            )

            // Update in memory
            cell.members[targetId] = previousRole

            println("[CellManager] Demoted player $targetId to ${previousRole.name} in cell '${cell.name}'")
            return CellRoleResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to demote player: ${e.message}")
            e.printStackTrace()
            return CellRoleResult.ERROR
        }
    }

    /**
     * Upgrades a cell to the next level
     */
    suspend fun upgradeCell(cellId: String, requesterId: UUID): CellUpgradeResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return CellUpgradeResult.CELL_NOT_FOUND

            // Check if requester has permission to upgrade (Co-Leaders and Leaders)
            if (!cell.hasPermission(requesterId, "upgrade")) {
                return CellUpgradeResult.INSUFFICIENT_PERMISSION
            }

            // Get upgrade configuration
            val upgradeConfig = getCellUpgradeConfig()

            // Check if cell has enough balance for upgrade
            val requiredBalance = cell.getRequiredBalanceForLevel(cell.level + 1, upgradeConfig.basePrice, upgradeConfig.priceIncrease)
            if (cell.balance < requiredBalance) {
                return CellUpgradeResult.INSUFFICIENT_VALUE
            }

            // Consume the required balance from the cell
            cell.balance = cell.balance.subtract(requiredBalance)

            // Upgrade the cell level
            cell.level += 1

            // Update both level and balance in database
            jaSync.executePreparedStatement(
                "UPDATE cells SET level = ?, balance = ? WHERE id = ?",
                listOf(cell.level, cell.balance, cellId)
            )

            println("[CellManager] Upgraded cell '${cell.name}' to level ${cell.level}, consumed ${requiredBalance} balance")
            return CellUpgradeResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to upgrade cell: ${e.message}")
            e.printStackTrace()
            return CellUpgradeResult.ERROR
        }
    }

    /**
     * Sets a cell's balance
     */
    suspend fun setCellBalance(cellId: String, requesterId: UUID, balance: BigDecimal): CellUpdateResult = cellMutex.withLock {
        try {
            val cell = cells[cellId] ?: return CellUpdateResult.CELL_NOT_FOUND

            // Check if requester is the leader
            if (!cell.isLeader(requesterId)) {
                return CellUpdateResult.NOT_LEADER
            }

            // Validate balance (cannot be negative)
            if (balance < BigDecimal.ZERO) {
                return CellUpdateResult.INVALID_INPUT
            }

            // Update in memory
            cell.balance = balance

            // Update in database
            jaSync.executePreparedStatement(
                "UPDATE cells SET balance = ? WHERE id = ?",
                listOf(balance, cellId)
            )

            println("[CellManager] Updated balance for cell '${cell.name}' to $balance")
            return CellUpdateResult.SUCCESS

        } catch (e: Exception) {
            println("[CellManager] Failed to set cell balance: ${e.message}")
            e.printStackTrace()
            return CellUpdateResult.ERROR
        }
    }

    /**
     * Adds balance to a cell
     */
    suspend fun addCellBalance(cellId: String, amount: BigDecimal): Boolean {
        try {
            val cell = cells[cellId] ?: return false

            // Update in memory
            cell.balance = cell.balance.add(amount)

            // Update in database
            jaSync.executePreparedStatement(
                "UPDATE cells SET balance = balance + ? WHERE id = ?",
                listOf(amount, cellId)
            )

            return true
        } catch (e: Exception) {
            println("[CellManager] Failed to add cell balance: ${e.message}")
            return false
        }
    }

    /**
     * Gets the cell upgrade configuration from game config
     */
    fun getCellUpgradeConfig(): CellUpgradeConfig {
        val config = twizzy.tech.game.Engine.gameConfig ?: emptyMap<String, Any>()
        return CellUpgradeConfig(
            basePrice = YamlFactory.getValue(config, "cells.level-upgrade.basePrice", 1000000.0),
            priceIncrease = YamlFactory.getValue(config, "cells.level-upgrade.priceIncrease", 10.0)
        )
    }

    /**
     * Data class for cell upgrade configuration
     */
    data class CellUpgradeConfig(
        val basePrice: Double,
        val priceIncrease: Double
    )

    // Getter methods
    fun getCell(cellId: String): Cell? = cells[cellId]

    fun getPlayerCell(playerId: UUID): Cell? {
        val cellId = playerToCellMap[playerId] ?: return null
        return cells[cellId]
    }

    fun getAllCells(): Collection<Cell> = cells.values

    fun getPublicCells(): List<Cell> = cells.values.filter { it.isPublic }

    fun getCellsWithOnlineMembers(): List<Pair<Cell, Int>> {
        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers.map { it.uuid }.toSet()
        return cells.values
            .mapNotNull { cell ->
                val onlineCount = cell.getAllMembers().count { onlinePlayers.contains(it) }
                if (onlineCount > 0) {
                    Pair(cell, onlineCount)
                } else {
                    null
                }
            }
            .sortedByDescending { it.second }
    }

    private fun getRequiredLeaderRank(): Int {
        val config = twizzy.tech.game.Engine.gameConfig ?: return 5
        return YamlFactory.getValue(config, "cells.leader-rank", 5)
    }

    /**
     * Shutdown method to clean up resources
     */
    fun shutdown() {
        maintenanceJob?.cancel()
        maintenanceScope.cancel()
        println("[CellManager] Shutdown completed")
    }

    /**
     * Optimized notification sending
     */
    private fun sendInvitationNotification(player: Player, cell: Cell, inviterId: UUID) {
        try {
            val inviterName = MojangUtils.getUsername(inviterId) ?: "Unknown"

            val acceptComponent = Component.text("§a[ACCEPT]")
                .hoverEvent(HoverEvent.showText(Component.text("§aClick to accept the invitation")))
                .clickEvent(ClickEvent.runCommand("/cell accept ${cell.id}"))

            val declineComponent = Component.text("§c[DECLINE]")
                .hoverEvent(HoverEvent.showText(Component.text("§cClick to decline the invitation")))
                .clickEvent(ClickEvent.runCommand("/cell decline ${cell.id}"))

            player.sendMessage(Component.text(""))
            player.sendMessage(Component.text("§6§l❯ CELL INVITATION ❮"))
            player.sendMessage(Component.text("§7You have been invited to join cell §f${cell.name}"))
            player.sendMessage(Component.text("§7by §f$inviterName"))
            player.sendMessage(Component.text(""))
            player.sendMessage(acceptComponent.append(Component.text("   ")).append(declineComponent))
            player.sendMessage(Component.text("§8This invitation expires in 3 days"))
            player.sendMessage(Component.text(""))
        } catch (e: Exception) {
            println("[CellManager] Failed to send invitation notification: ${e.message}")
        }
    }

    /**
     * Updates a cell's properties in the database
     */
    suspend fun saveCell(cell: Cell) {
        try {
            jaSync.executePreparedStatement(
                "UPDATE cells SET description = ?, announcement = ?, is_public = ? WHERE id = ?",
                listOf(cell.description, cell.announcement, cell.isPublic, cell.id)
            )
        } catch (e: Exception) {
            println("[CellManager] Failed to save cell: ${e.message}")
            e.printStackTrace()
        }
    }
}
