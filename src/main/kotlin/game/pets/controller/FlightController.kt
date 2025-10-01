package twizzy.tech.game.pets.controller

import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.instance.Instance
import net.minestom.server.potion.PotionEffect
import twizzy.tech.game.pathfinding.AStar3D
import kotlin.math.*

class FlightController(
    private val entity: EntityCreature,
    private val owner: Player
) {
    private var orbitAngle: Double = Math.random() * (2 * Math.PI)
    private var currentMode = Mode.ORBIT

    // Movement parameters
    private val baseOrbitRadius = 2.0
    private val desiredHeightOffset = 1.0
    private val baseSpeed = 6.5
    private val maxSpeed = 10.0
    private val damping = 0.85
    private val steer = 1.0 - damping

    // Bobbing motion parameters
    private val bobAmplitude = 0.6
    private val bobSpeed = 0.4

    // Pathfinding and state
    private var path: List<Point>? = null
    private var pathIndex: Int = 0
    private var lastGoalPos: Point? = null
    private val losCooldownTicks = 20
    private var losCooldownCounter = 0
    private var lastEntityPos: Vec? = null
    private var stuckCounter = 0
    private var lastOwnerPos: Pos? = null
    private var pursueTargetPos: Vec? = null
    private var lastPathRecalculation = 0L
    private val recalculationCooldown = 500L
    private var teleportTimer = 0
    private val maxStuckTime = 100

    // Enhanced orbit
    private val minOrbitRadius = 4.0
    private val maxOrbitRadius = 7.0
    private var currentOrbitRadius = minOrbitRadius + Math.random() * (maxOrbitRadius - minOrbitRadius)
    private val orbitHeightVariation = 2.0
    private var targetOrbitRadius = currentOrbitRadius
    private var radiusChangeTimer = 0
    private val radiusChangeInterval = 40
    private var verticalOscillationPhase = Math.random() * (2 * Math.PI)
    private var horizontalWanderPhase = Math.random() * (2 * Math.PI)
    private var orbitSpeedMultiplier = 1.0

    // Ticking and caches
    private var bobPhase = Math.random() * (2 * Math.PI)
    private var tickCounter = 0
    private var speedCheckCounter = 0
    private var cachedSpeedAmplifier = -1
    private var losCheckCounter = 0
    private var cachedHasLOS = false
    private var orbitSpaceCheckCounter = 0
    private var cachedHasOrbitSpace = true

    private val SPEED_RECHECK_TICKS = 10
    private val LOS_RECHECK_TICKS = 5
    private val ORBIT_SPACE_RECHECK_TICKS = 15

    private enum class Mode { ORBIT, PURSUE, IDLE }
    private enum class SpaceType { TIGHT_VERTICAL, TIGHT_HORIZONTAL, OPEN }

    fun start() {
        // Register with centralized scheduler loop
        PetFlightScheduler.register(this)
    }

    fun stop() {
        PetFlightScheduler.unregister(this)
    }

    fun isPursuingOwner(): Boolean = currentMode == Mode.PURSUE

    /**
     * Called by PetFlightScheduler at ~20 TPS.
     */
    fun tick() {
        // Early termination checks
        val ownerInstance = owner.instance
        if (ownerInstance == null || !owner.isOnline) {
            entity.remove()
            stop()
            return
        }
        if (entity.instance != ownerInstance) {
            entity.setInstance(ownerInstance, owner.position)
        }

        val entityPos = entity.position
        val ownerPos = owner.position
        val distanceToOwner = entityPos.distance(ownerPos)

        // Hard leash/teleport to keep up and avoid excessive path searches
        if (distanceToOwner > 30.0) {
            val directionToEntity = entityPos.asVec().sub(ownerPos.asVec()).normalize()
            val teleportDistance = 8.0 + Math.random() * 4.0
            val teleportPos = ownerPos.asVec().add(directionToEntity.mul(teleportDistance))
            val adjustedTeleportPos = Pos(teleportPos.x(), ownerPos.y() + desiredHeightOffset, teleportPos.z())
            entity.teleport(adjustedTeleportPos)
            path = null; pathIndex = 0; lastGoalPos = null; pursueTargetPos = null
            return
        }

        // Cached reads and rate-limited calculations
        if (speedCheckCounter-- <= 0) {
            val speedEffect = owner.activeEffects.find { it.potion.effect() == PotionEffect.SPEED }
            cachedSpeedAmplifier = speedEffect?.potion?.amplifier() ?: -1
            speedCheckCounter = SPEED_RECHECK_TICKS
        }

        if (losCheckCounter-- <= 0) {
            cachedHasLOS = hasLineOfSight(entity, owner)
            losCheckCounter = LOS_RECHECK_TICKS
            // maintain legacy cooldown behavior
            losCooldownCounter = if (cachedHasLOS) losCooldownTicks else max(0, losCooldownCounter - 1)
        } else {
            // still decay cooldown
            losCooldownCounter = max(0, losCooldownCounter - 1)
        }

        if (orbitSpaceCheckCounter-- <= 0) {
            cachedHasOrbitSpace = hasSpaceToOrbit(owner)
            orbitSpaceCheckCounter = ORBIT_SPACE_RECHECK_TICKS
        }

        // Mode selection
        val newMode = when {
            !cachedHasOrbitSpace && distanceToOwner < 3.0 && cachedHasLOS -> Mode.IDLE
            cachedHasOrbitSpace -> {
                if (cachedHasLOS || losCooldownCounter > 0) {
                    Mode.ORBIT
                } else {
                    if (distanceToOwner > currentOrbitRadius * 1.5) Mode.PURSUE else Mode.ORBIT
                }
            }
            else -> Mode.PURSUE
        }
        currentMode = newMode

        // Vertical correction to hold target height near player
        val targetHeight = ownerPos.y() + desiredHeightOffset
        val currentHeight = entityPos.y()
        val heightDifference = targetHeight - currentHeight
        val verticalCorrection = heightDifference.coerceIn(-0.3, 0.3)

        // Mode handling
        when (currentMode) {
            Mode.ORBIT -> handleOrbitMode(entity, ownerPos, targetHeight, bobPhase)
            Mode.PURSUE -> handlePursueMode(entity, owner, ownerPos, targetHeight, cachedSpeedAmplifier)
            Mode.IDLE -> handleIdleMode(entity, ownerPos, targetHeight)
        }

        val finalVerticalCorrection = if (abs(heightDifference) > 3.0) {
            heightDifference.coerceIn(-1.5, 1.5)
        } else verticalCorrection

        val currentVel = entity.velocity
        if (abs(heightDifference) > 0.1) {
            entity.velocity = Vec(currentVel.x(), currentVel.y() + finalVerticalCorrection, currentVel.z())
        }

        // Advance phases and housekeeping
        bobPhase += 0.1
        checkAndPreventStuck(entity, owner, ownerPos, entityPos)
        lastOwnerPos = ownerPos

        // Auto-unregister if removed
        if (entity.isRemoved) stop()

        tickCounter++
    }

    private fun hasLineOfSight(entity: EntityCreature, owner: Player): Boolean {
        val instance = owner.instance ?: return false
        val entityPos = entity.position
        val ownerPos = owner.position
        return hasClearLineOfSightForFlying(instance, entityPos, ownerPos)
    }

    private fun hasClearLineOfSightForFlying(instance: Instance, start: Point, end: Point): Boolean {
        val scale = entity.getAttribute(Attribute.SCALE).baseValue.toFloat()
        val x1 = floor(start.x()).toInt()
        val y1 = floor(start.y()).toInt()
        val z1 = floor(start.z()).toInt()
        val x2 = floor(end.x()).toInt()
        val y2 = floor(end.y()).toInt()
        val z2 = floor(end.z()).toInt()

        val dx = abs(x2 - x1)
        val dy = abs(y2 - y1)
        val dz = abs(z2 - z1)

        val sx = if (x1 < x2) 1 else -1
        val sy = if (y1 < y2) 1 else -1
        val sz = if (z1 < z2) 1 else -1

        var x = x1
        var y = y1
        var z = z1

        if (dx >= dy && dx >= dz) {
            var p1 = 2 * dy - dx
            var p2 = 2 * dz - dx
            while (x != x2) {
                if (!canFlyThrough(instance, Vec(x.toDouble(), y.toDouble(), z.toDouble()), scale)) return false
                if (p1 >= 0) { y += sy; p1 -= 2 * dx }
                if (p2 >= 0) { z += sz; p2 -= 2 * dx }
                p1 += 2 * dy
                p2 += 2 * dz
                x += sx
            }
        } else if (dy >= dx && dy >= dz) {
            var p1 = 2 * dx - dy
            var p2 = 2 * dz - dy
            while (y != y2) {
                if (!canFlyThrough(instance, Vec(x.toDouble(), y.toDouble(), z.toDouble()), scale)) return false
                if (p1 >= 0) { x += sx; p1 -= 2 * dy }
                if (p2 >= 0) { z += sz; p2 -= 2 * dy }
                p1 += 2 * dx
                p2 += 2 * dz
                y += sy
            }
        } else {
            var p1 = 2 * dy - dz
            var p2 = 2 * dx - dz
            while (z != z2) {
                if (!canFlyThrough(instance, Vec(x.toDouble(), y.toDouble(), z.toDouble()), scale)) return false
                if (p1 >= 0) { y += sy; p1 -= 2 * dz }
                if (p2 >= 0) { x += sx; p2 -= 2 * dz }
                p1 += 2 * dy
                p2 += 2 * dx
                z += sz
            }
        }
        return canFlyThrough(instance, Vec(x2.toDouble(), y2.toDouble(), z2.toDouble()), scale)
    }

    private fun handleOrbitMode(
        entity: EntityCreature,
        ownerPos: Pos,
        targetHeight: Double,
        bobPhase: Double
    ) {
        val entityPos = entity.position
        val distanceToOwner = entityPos.distance(ownerPos)

        radiusChangeTimer++
        if (radiusChangeTimer >= radiusChangeInterval) {
            radiusChangeTimer = 0
            targetOrbitRadius = minOrbitRadius + Math.random() * (maxOrbitRadius - minOrbitRadius)
        }

        val radiusChangeSpeed = 0.02
        currentOrbitRadius += (targetOrbitRadius - currentOrbitRadius) * radiusChangeSpeed
        if (currentOrbitRadius < minOrbitRadius) currentOrbitRadius = minOrbitRadius
        if (currentOrbitRadius > maxOrbitRadius) currentOrbitRadius = maxOrbitRadius

        if (Math.random() < 0.002) {
            orbitSpeedMultiplier = 1.0 + Math.random() * 0.4
        }

        val idealOrbitDistance = currentOrbitRadius
        val distanceFromIdeal = distanceToOwner - idealOrbitDistance

        val distanceSpeedMultiplier = when {
            distanceFromIdeal > 2.0 -> 1.0 + ((distanceFromIdeal - 2.0) * 0.3).coerceAtMost(1.5)
            else -> 1.0
        }

        val playerMovementBoost = lastOwnerPos?.let { lastPos ->
            val playerMovement = ownerPos.distance(lastPos)
            if (playerMovement > 0.5) 1.0 + (playerMovement * 0.5) else 1.0
        } ?: 1.0

        orbitAngle = (orbitAngle + 0.1 * orbitSpeedMultiplier) % (2 * Math.PI)

        val wanderStrength = 0.1
        horizontalWanderPhase = (horizontalWanderPhase + 0.05) % (2 * Math.PI)
        val horizontalWanderX = cos(horizontalWanderPhase) * wanderStrength
        val horizontalWanderZ = sin(horizontalWanderPhase * 1.3) * wanderStrength

        verticalOscillationPhase = (verticalOscillationPhase + 0.1) % (2 * Math.PI)
        val verticalOscillation = sin(verticalOscillationPhase) * orbitHeightVariation

        val bobOffset = sin(orbitAngle * bobSpeed + bobPhase) * bobAmplitude
        val orbitHeight = targetHeight + verticalOscillation + bobOffset

        val centerX = ownerPos.x() + horizontalWanderX
        val centerZ = ownerPos.z() + horizontalWanderZ

        val targetX = centerX + cos(orbitAngle) * currentOrbitRadius
        val targetZ = centerZ + sin(orbitAngle) * currentOrbitRadius
        var targetPos = Vec(targetX, orbitHeight, targetZ)

        if (Math.random() < 0.005) {
            val randomDeviation = Vec(
                (Math.random() - 0.5) * 0.5,
                (Math.random() - 0.5) * 0.3,
                (Math.random() - 0.5) * 0.5
            )
            targetPos = targetPos.add(randomDeviation)
        }

        val baseOrbitSpeed = baseSpeed * 1.2
        val dynamicOrbitSpeed = baseOrbitSpeed * distanceSpeedMultiplier * playerMovementBoost
        val finalOrbitSpeed = min(dynamicOrbitSpeed, maxSpeed * 1.5)

        steerToTarget(entity, entityPos, targetPos, finalOrbitSpeed, maxSpeed, damping, steer)

        if (Math.random() < 0.002) {
            val currentVel = entity.velocity
            if (currentVel.length() > 0.1) {
                val slightTurn = Vec(
                    currentVel.x() + (Math.random() - 0.5) * 0.1,
                    currentVel.y() + (Math.random() - 0.5) * 0.05,
                    currentVel.z() + (Math.random() - 0.5) * 0.1
                ).normalize().mul(currentVel.length())
                entity.velocity = slightTurn
            }
        }
    }

    private fun handlePursueMode(
        entity: EntityCreature,
        owner: Player,
        ownerPos: Pos,
        targetHeight: Double,
        speedAmplifier: Int
    ) {
        val instance = owner.instance ?: return
        val entityPos = entity.position
        val hasCurrentLOS = cachedHasLOS

        var pursueSpeed = baseSpeed * 1.5
        var pursueMaxSpeed = maxSpeed * 1.3

        if (speedAmplifier > -1) {
            val speedBonus = 1.0 + (speedAmplifier + 1) * 0.5
            pursueSpeed *= speedBonus
            pursueMaxSpeed *= speedBonus
        }

        val targetPos: Vec = if (hasCurrentLOS && losCooldownCounter > 0) {
            path = null
            val ownerHasMoved = lastOwnerPos?.sameBlock(ownerPos) == false
            if (pursueTargetPos == null || ownerHasMoved) {
                val spaceType = getPlayerSpaceType(instance, ownerPos)
                val verticalDiff = ownerPos.y() - entityPos.y()
                when (spaceType) {
                    SpaceType.TIGHT_VERTICAL -> {
                        val airAbove = canFlyThrough(instance, ownerPos.x().toInt(), (ownerPos.y() + 1.5).toInt(), ownerPos.z().toInt())
                        val airBelow = canFlyThrough(instance, ownerPos.x().toInt(), (ownerPos.y() - 1.5).toInt(), ownerPos.z().toInt())
                        pursueTargetPos = if (verticalDiff < -0.5) {
                            val targetY = when {
                                airAbove -> ownerPos.y() + 1.5
                                canFlyThrough(instance, ownerPos.x().toInt(), (ownerPos.y() + 0.5).toInt(), ownerPos.z().toInt()) -> ownerPos.y() + 0.5
                                else -> ownerPos.y()
                            }
                            Vec(ownerPos.x(), targetY, ownerPos.z())
                        } else {
                            val targetY = when {
                                airBelow -> ownerPos.y() - 1.5
                                canFlyThrough(instance, ownerPos.x().toInt(), (ownerPos.y() - 0.5).toInt(), ownerPos.z().toInt()) -> ownerPos.y() - 0.5
                                else -> ownerPos.y()
                            }
                            Vec(ownerPos.x(), targetY, ownerPos.z())
                        }
                    }
                    SpaceType.TIGHT_HORIZONTAL -> {
                        val behindOffset = 1.5
                        val behindX = -sin(ownerPos.yaw() * (PI / 180.0)) * behindOffset
                        val behindZ = cos(ownerPos.yaw() * (PI / 180.0)) * behindOffset
                        pursueTargetPos = Vec(ownerPos.x() + behindX, ownerPos.y(), ownerPos.z() + behindZ)
                    }
                    SpaceType.OPEN -> {
                        val directionToPlayer = ownerPos.asVec().sub(entityPos.asVec()).normalize()
                        val targetPoint = ownerPos.asVec().sub(directionToPlayer.mul(baseOrbitRadius * 0.8))
                        pursueTargetPos = Vec(targetPoint.x(), ownerPos.y() + 1.5, targetPoint.z())
                    }
                }
            }
            pursueTargetPos!!
        } else {
            pursueTargetPos = null
            val goalPoint = Vec(ownerPos.blockX().toDouble(), ownerPos.blockY().toDouble() + desiredHeightOffset, ownerPos.blockZ().toDouble())
            val needsRecalculation = path == null ||
                pathIndex >= path!!.size ||
                lastGoalPos == null ||
                goalPoint.distance(lastGoalPos!!) > 3.0 ||
                (!hasCurrentLOS && pathIndex == 0)

            val currentTime = System.currentTimeMillis()
            if (needsRecalculation && (currentTime - lastPathRecalculation) > recalculationCooldown) {
                // Respect global pathfinding budget to avoid spikes
                if (PetFlightScheduler.tryConsumePathBudget()) {
                    lastPathRecalculation = currentTime
                    val startPoint = Vec(entityPos.blockX().toDouble(), entityPos.blockY().toDouble(), entityPos.blockZ().toDouble())
                    val pathResult = AStar3D.findPath(instance, startPoint, goalPoint, maxRange = 30, timeoutMillis = 10L)
                    if (pathResult.success && pathResult.points.isNotEmpty()) {
                        path = AStar3D.smoothPath(instance, pathResult.points)
                        pathIndex = 0
                        lastGoalPos = goalPoint
                    } else {
                        path = null
                        lastGoalPos = null
                    }
                }
            }

            if (path != null && pathIndex < path!!.size) {
                val waypointPoint = path!![pathIndex].add(0.5, 0.5, 0.5)
                val waypoint = Vec(waypointPoint.x(), waypointPoint.y(), waypointPoint.z())
                val verticalDifference = abs(waypoint.y() - entityPos.y())
                val horizontalDifference = sqrt((waypoint.x() - entityPos.x()).pow(2) + (waypoint.z() - entityPos.z()).pow(2))
                val isVerticalWaypoint = verticalDifference > 1.0 && horizontalDifference < 1.5
                val reachDistance = when {
                    waypoint.y() < entityPos.y() -> 2.0
                    isVerticalWaypoint -> 1.8
                    else -> 1.2
                }

                val targetWaypoint = if (isVerticalWaypoint && verticalDifference > 2.0) {
                    if (horizontalDifference < 0.8 && entityPos.distance(waypoint) < 3.0) {
                        pathIndex++
                        if (pathIndex < path!!.size) {
                            val nextWaypointPoint = path!![pathIndex].add(0.5, 0.5, 0.5)
                            Vec(nextWaypointPoint.x(), nextWaypointPoint.y(), nextWaypointPoint.z())
                        } else {
                            waypoint
                        }
                    } else {
                        waypoint
                    }
                } else {
                    waypoint
                }

                if (entityPos.distance(waypoint) < reachDistance) {
                    pathIndex++
                    if (pathIndex >= path!!.size && !hasCurrentLOS) {
                        path = null
                        pathIndex = 0
                        lastGoalPos = null
                        lastPathRecalculation = 0L
                    }
                }
                targetWaypoint
            } else {
                if ((System.currentTimeMillis() - lastPathRecalculation) > recalculationCooldown * 2) {
                    val alternativeGoals = listOf(
                        goalPoint,
                        goalPoint.add(1.0, 0.0, 0.0),
                        goalPoint.add(-1.0, 0.0, 0.0),
                        goalPoint.add(0.0, 0.0, 1.0),
                        goalPoint.add(0.0, 0.0, -1.0),
                        goalPoint.add(0.0, 1.0, 0.0)
                    )
                    val startPoint = Vec(entityPos.blockX().toDouble(), entityPos.blockY().toDouble(), entityPos.blockZ().toDouble())
                    for (altGoal in alternativeGoals) {
                        if (!PetFlightScheduler.tryConsumePathBudget()) break
                        val pathResult = AStar3D.findPath(instance, startPoint, altGoal, maxRange = 40, timeoutMillis = 15L)
                        if (pathResult.success && pathResult.points.isNotEmpty()) {
                            path = AStar3D.smoothPath(instance, pathResult.points)
                            pathIndex = 0
                            lastGoalPos = altGoal
                            lastPathRecalculation = System.currentTimeMillis()
                            break
                        }
                    }
                }
                Vec(ownerPos.x(), targetHeight, ownerPos.z())
            }
        }
        steerToTarget(entity, entityPos, targetPos, pursueSpeed, pursueMaxSpeed, damping, steer)
    }

    private fun handleIdleMode(
        entity: EntityCreature,
        ownerPos: Pos,
        targetHeight: Double
    ) {
        val entityPos = entity.position
        val instance = entity.instance ?: return
        val yawRad = Math.toRadians(ownerPos.yaw().toDouble())
        val playerX = ownerPos.x()
        val blockY = ownerPos.blockY()
        val playerZ = ownerPos.z()

        val behindOffsetX = sin(yawRad) * 1.5
        val behindOffsetZ = -cos(yawRad) * 1.5
        val leftYawRad = yawRad - PI / 2
        val rightYawRad = yawRad + PI / 2
        val leftOffsetX = sin(leftYawRad) * 1.5
        val leftOffsetZ = -cos(leftYawRad) * 1.5
        val rightOffsetX = sin(rightYawRad) * 1.5
        val rightOffsetZ = -cos(rightYawRad) * 1.5

        fun flyableScore(x: Double, y: Double, z: Double): Int {
            return (0..2).count { canFlyThrough(instance, x.toInt(), y.toInt() + it, z.toInt()) }
        }
        val behindScore = flyableScore(playerX + behindOffsetX, blockY.toDouble(), playerZ + behindOffsetZ)
        val leftScore = flyableScore(playerX + leftOffsetX, blockY.toDouble(), playerZ + leftOffsetZ)
        val rightScore = flyableScore(playerX + rightOffsetX, blockY.toDouble(), playerZ + rightOffsetZ)

        val (targetX, targetZ) = when {
            behindScore > 0 -> Pair(playerX + behindOffsetX, playerZ + behindOffsetZ)
            leftScore > 0 && rightScore == 0 -> Pair(playerX + leftOffsetX, playerZ + leftOffsetZ)
            rightScore > 0 && leftScore == 0 -> Pair(playerX + rightOffsetX, playerZ + rightOffsetZ)
            leftScore > 0 && rightScore > 0 -> {
                if (leftScore >= rightScore) Pair(playerX + leftOffsetX, playerZ + leftOffsetZ)
                else Pair(playerX + rightOffsetX, playerZ + rightOffsetZ)
            }
            leftScore > rightScore -> Pair(playerX + leftOffsetX, playerZ + leftOffsetZ)
            rightScore > leftScore -> Pair(playerX + rightOffsetX, playerZ + rightOffsetZ)
            else -> Pair(playerX + rightOffsetX, playerZ + rightOffsetZ)
        }
        val targetPos = Vec(targetX, targetHeight, targetZ)
        steerToTarget(entity, entityPos, targetPos, baseSpeed, maxSpeed, damping, steer)
    }

    private fun steerToTarget(entity: EntityCreature, entityPos: Pos, targetPos: Vec, baseSpeed: Double, maxSpeed: Double, damping: Double, steer: Double) {
        val toTarget = targetPos.sub(entityPos.x(), entityPos.y(), entityPos.z())
        val distance = toTarget.length()

        if (distance < 1e-4) {
            entity.velocity = Vec.ZERO
            return
        }

        val desiredDir = toTarget.normalize()
        val desiredVel = desiredDir.mul(baseSpeed)

        val horizontalDist = sqrt(toTarget.x() * toTarget.x() + toTarget.z() * toTarget.z())
        if (toTarget.y() < -1.0 && horizontalDist < 1.5) {
            val nudge = toTarget.withY(0.0).normalize().mul(0.5)
            entity.velocity = Vec(nudge.x(), -maxSpeed, nudge.z())
            return
        }

        val currentVel = entity.velocity
        var newVel = currentVel.mul(damping).add(desiredVel.mul(steer))

        val len = newVel.length()
        if (len > maxSpeed) newVel = newVel.mul(maxSpeed / len)

        entity.velocity = newVel

        if (newVel.length() > 0.05) {
            val lookAt = Pos(entityPos.x() + newVel.x(), entityPos.y() + newVel.y(), entityPos.z() + newVel.z())
            entity.lookAt(lookAt)
        }
    }

    private fun checkAndPreventStuck(entity: EntityCreature, owner: Player, ownerPos: Pos, entityPos: Pos) {
        if (currentMode == Mode.ORBIT && cachedHasOrbitSpace) {
            stuckCounter = 0
            teleportTimer = 0
            lastEntityPos = Vec(entityPos.x(), entityPos.y(), entityPos.z())
            return
        }
        if (currentMode == Mode.IDLE) {
            stuckCounter = 0
            teleportTimer = 0
            lastEntityPos = Vec(entityPos.x(), entityPos.y(), entityPos.z())
            return
        }
        val currentEntityPos = Vec(entityPos.x(), entityPos.y(), entityPos.z())
        val distanceToOwner = entityPos.distance(ownerPos)

        lastEntityPos?.let { lastPos ->
            val instance = owner.instance ?: return@let
            val tryingToMoveVertically = path?.let {
                pathIndex < it.size && abs(it[pathIndex].y() - entityPos.y()) > 0.5
            } ?: false
            val verticalMovement = abs(currentEntityPos.y() - lastPos.y())
            val horizontalMovement = sqrt((currentEntityPos.x() - lastPos.x()).pow(2) + (currentEntityPos.z() - lastPos.z()).pow(2))
            val isVerticallyStuck = tryingToMoveVertically && horizontalMovement < 0.1 && verticalMovement < 0.05 && entity.velocity.length() > 0.1
            val isHorizontallyStuck = !tryingToMoveVertically && currentEntityPos.distance(lastPos) < 0.1 && entity.velocity.length() > 0.1
            val isCornerClipping = entity.velocity.length() > 0.5 && currentEntityPos.distance(lastPos) < 0.2 && path != null && pathIndex < path!!.size
            val isPathfindingStuck = path == null && !cachedHasLOS && currentEntityPos.distance(lastPos) < 0.3 && distanceToOwner > 3.0

            if (isVerticallyStuck || isHorizontallyStuck || isCornerClipping || isPathfindingStuck) {
                stuckCounter++
                teleportTimer++

                if (stuckCounter > 0 && currentMode == Mode.PURSUE) {
                    val currentVel = entity.velocity
                    if (currentVel.length() > 0.1) {
                        val boostedVel = currentVel.mul(2.5)
                        entity.velocity = boostedVel
                    }
                }

                if (teleportTimer > maxStuckTime) {
                    entity.teleport(ownerPos.add(0.0, desiredHeightOffset, 0.0))
                    path = null
                    pathIndex = 0
                    stuckCounter = 0
                    teleportTimer = 0
                    return@let
                }

                val threshold = when {
                    isCornerClipping -> 1
                    isVerticallyStuck -> 2
                    isPathfindingStuck -> 2
                    else -> 3
                }

                if (stuckCounter > threshold) {
                    when {
                        isCornerClipping && path != null && pathIndex < path!!.size -> {
                            handleCornerClipping(entity, instance, entityPos, ownerPos)
                        }
                        isPathfindingStuck -> {
                            handlePathfindingStuck(entity, instance, entityPos, ownerPos)
                        }
                        else -> {
                            val goalPoint = Vec(ownerPos.blockX().toDouble(), ownerPos.blockY().toDouble() + desiredHeightOffset, ownerPos.blockZ().toDouble())
                            if (isVerticallyStuck) {
                                path = findVerticalAlternativePath(instance, entityPos, goalPoint)
                                pathIndex = 0
                                stuckCounter = 0
                            } else if (isHorizontallyStuck) {
                                path = findAlternativePath(instance, entityPos, goalPoint)
                                pathIndex = 0
                                stuckCounter = 0
                            }
                        }
                    }
                }
            } else {
                stuckCounter = 0
                teleportTimer = 0
            }
        }
        lastEntityPos = currentEntityPos
    }

    private fun handlePathfindingStuck(entity: EntityCreature, instance: Instance, entityPos: Pos, ownerPos: Pos) {
        val strategies = listOf(
            Vec(ownerPos.x() - entityPos.x(), 0.0, ownerPos.z() - entityPos.z()).normalize(),
            Vec(ownerPos.x() - entityPos.x(), 2.0, ownerPos.z() - entityPos.z()).normalize(),
            Vec(0.0, 1.0, 0.0),
            Vec(entityPos.x() - ownerPos.x(), 0.0, entityPos.z() - ownerPos.z()).normalize(),
        )
        var bestDirection: Vec? = null
        var maxClearDistance = 0.0

        for (direction in strategies) {
            var testDistance = 0.0
            val maxTest = 5.0
            val step = 0.5
            while (testDistance < maxTest) {
                val testPos = Vec(entityPos.x(), entityPos.y(), entityPos.z()).add(direction.mul(testDistance))
                if (!isPositionWalkable(instance, testPos)) {
                    break
                }
                testDistance += step
            }
            if (testDistance > maxClearDistance) {
                maxClearDistance = testDistance
                bestDirection = direction
            }
        }

        if (bestDirection != null && maxClearDistance > 1.0) {
            val escapeVelocity = bestDirection.mul(maxSpeed * 1.5)
            entity.velocity = escapeVelocity
            stuckCounter = 0
        } else {
            stuckCounter += 3
        }
    }

    private fun handleCornerClipping(entity: EntityCreature, instance: Instance, entityPos: Pos, ownerPos: Pos) {
        val currentWaypoint = if (path != null && pathIndex < path!!.size) {
            path!![pathIndex]
        } else {
            return
        }
        val waypointVec = Vec(currentWaypoint.x() + 0.5, currentWaypoint.y() + 0.5, currentWaypoint.z() + 0.5)
        waypointVec.sub(entityPos.x(), entityPos.y(), entityPos.z()).normalize()

        val offsets = listOf(
            Vec(0.5, 0.0, 0.0), Vec(-0.5, 0.0, 0.0), Vec(0.0, 0.0, 0.5), Vec(0.0, 0.0, -0.5),
            Vec(0.0, 0.5, 0.0), Vec(0.0, -0.5, 0.0),
            Vec(0.3, 0.3, 0.0), Vec(-0.3, 0.3, 0.0), Vec(0.0, 0.3, 0.3), Vec(0.0, 0.3, -0.3)
        )
        var bestOffset: Vec? = null
        var bestDistance = Double.MAX_VALUE

        for (offset in offsets) {
            val testPos = Vec(entityPos.x(), entityPos.y(), entityPos.z()).add(offset)
            if (isPositionWalkable(instance, testPos)) {
                val distanceToWaypoint = testPos.distance(waypointVec)
                if (distanceToWaypoint < bestDistance) {
                    bestDistance = distanceToWaypoint
                    bestOffset = offset
                }
            }
        }

        if (bestOffset != null) {
            val targetPos = Vec(entityPos.x(), entityPos.y(), entityPos.z()).add(bestOffset)
            val direction = targetPos.sub(entityPos.x(), entityPos.y(), entityPos.z()).normalize()
            val avoidanceVelocity = direction.mul(maxSpeed * 0.8)
            entity.velocity = avoidanceVelocity
            stuckCounter = 0
        } else {
            pathIndex++
            if (pathIndex >= path!!.size) {
                path = null
                pathIndex = 0
                val goalPoint = Vec(ownerPos.blockX().toDouble(), ownerPos.blockY().toDouble() + desiredHeightOffset, ownerPos.blockZ().toDouble())
                val startPoint = Vec(entityPos.blockX().toDouble(), entityPos.blockY().toDouble(), entityPos.blockZ().toDouble())
                val pathResult = AStar3D.findPath(instance, startPoint, goalPoint, maxRange = 30, timeoutMillis = 15L)
                if (pathResult.success && pathResult.points.isNotEmpty()) {
                    path = AStar3D.smoothPath(instance, pathResult.points)
                }
            }
            stuckCounter = 0
        }
    }

    private fun hasSpaceToOrbit(owner: Player): Boolean {
        val instance = owner.instance ?: return false
        val center = owner.position
        val y = center.blockY()
        var flyableBlocks = 0
        val checkRadius = 3
        for (heightOffset in -1..1) {
            for (x in -checkRadius..checkRadius) {
                for (z in -checkRadius..checkRadius) {
                    if (canFlyThrough(instance, center.blockX() + x, y + heightOffset, center.blockZ() + z)) {
                        flyableBlocks++
                    }
                }
            }
        }
        val totalCells = (checkRadius * 2 + 1) * (checkRadius * 2 + 1) * 3
        return flyableBlocks > totalCells * 0.4
    }

    private fun findVerticalAlternativePath(instance: Instance, start: Point, goal: Point): List<Point>? {
        if (goal.y() < start.y()) {
            var currentY = start.y()
            var clearPath = true
            while (currentY > goal.y() && clearPath) {
                val testPoint = Vec(start.x(), currentY, start.z())
                if (!isPositionWalkable(instance, testPoint)) {
                    clearPath = false
                }
                currentY -= 1.0
            }
            if (clearPath) {
                val verticalPath = mutableListOf<Point>()
                var y = start.y()
                while (y >= goal.y()) {
                    verticalPath.add(Vec(start.x(), y, start.z()))
                    y -= 1.0
                }
                return verticalPath
            }
        }
        return findAlternativePath(instance, start, goal)
    }

    private fun findAlternativePath(instance: Instance, start: Point, goal: Point): List<Point>? {
        if (goal.y() < start.y()) {
            val downwardStart = start.add(0.0, -1.0, 0.0)
            if (isPositionWalkable(instance, downwardStart)) {
                val pathResult = AStar3D.findPath(instance, downwardStart, goal, maxRange = 30, timeoutMillis = 20L)
                if (pathResult.success && pathResult.points.isNotEmpty()) {
                    return AStar3D.smoothPath(instance, pathResult.points)
                }
            }
        }
        val offsets = listOf(
            Vec(1.0, 0.0, 0.0), Vec(-1.0, 0.0, 0.0), Vec(0.0, 0.0, 1.0), Vec(0.0, 0.0, -1.0),
            Vec(1.0, 0.0, 1.0), Vec(-1.0, 0.0, 1.0), Vec(1.0, 0.0, -1.0), Vec(-1.0, 0.0, -1.0)
        )
        for (offset in offsets) {
            val alternativeStart = start.add(offset)
            if (isPositionWalkable(instance, alternativeStart)) {
                val pathResult = AStar3D.findPath(instance, alternativeStart, goal, maxRange = 30, timeoutMillis = 20L)
                if (pathResult.success && pathResult.points.isNotEmpty()) {
                    return AStar3D.smoothPath(instance, pathResult.points)
                }
            }
        }
        return null
    }

    private fun isPositionWalkable(instance: Instance, point: Point): Boolean {
        val chunk = instance.getChunkAt(point) ?: return false
        if (!chunk.isLoaded) return false
        val scale = entity.getAttribute(Attribute.SCALE).baseValue.toFloat()
        return canFlyThrough(instance, point, scale) && canFlyThrough(instance, point.add(0.0, 1.0, 0.0), scale)
    }

    private fun getPlayerSpaceType(instance: Instance, playerPos: Pos): SpaceType {
        val x = playerPos.blockX()
        val y = playerPos.blockY()
        val z = playerPos.blockZ()
        val flyableAbove = canFlyThrough(instance, x, y + 1, z)
        val flyableBelow = canFlyThrough(instance, x, y - 1, z)
        val flyableLeft = canFlyThrough(instance, x - 1, y, z)
        val flyableRight = canFlyThrough(instance, x + 1, y, z)
        val flyableFront = canFlyThrough(instance, x, y, z + 1)
        val flyableBack = canFlyThrough(instance, x, y, z - 1)

        val tightVertical = !flyableLeft && !flyableRight && !flyableFront && !flyableBack && (flyableAbove || flyableBelow)
        val tightHorizontal = (flyableFront || flyableBack) && !flyableLeft && !flyableRight && !flyableAbove && !flyableBelow

        return when {
            tightVertical -> SpaceType.TIGHT_VERTICAL
            tightHorizontal -> SpaceType.TIGHT_HORIZONTAL
            else -> SpaceType.OPEN
        }
    }

    private fun canFlyThrough(instance: Instance, x: Int, y: Int, z: Int): Boolean {
        val block = instance.getBlock(x, y, z)
        return !block.isSolid
    }

    private fun canFlyThrough(instance: Instance, pos: Point): Boolean {
        return canFlyThrough(instance, pos.blockX(), pos.blockY(), pos.blockZ())
    }

    private fun canFlyThrough(instance: Instance, pos: Point, scale: Float): Boolean {
        val width = 0.5f * scale
        val height = 0.9f * scale

        val halfWidth = width / 2.0
        val halfHeight = height / 2.0

        val minX = floor(pos.x() - halfWidth).toInt()
        val maxX = ceil(pos.x() + halfWidth).toInt()
        val minY = floor(pos.y()).toInt()
        val maxY = ceil(pos.y() + height).toInt()
        val minZ = floor(pos.z() - halfWidth).toInt()
        val maxZ = ceil(pos.z() + halfWidth).toInt()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    if (instance.getBlock(x, y, z).isSolid) {
                        return false
                    }
                }
            }
        }
        return true
    }
}
