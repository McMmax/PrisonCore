package twizzy.tech.game.pathfinding

import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.Instance
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A* 3D Pathfinding for entities that can fly.
 *
 * This pathfinder works on a 3D grid and is suitable for flying entities
 * that need to navigate complex environments with verticality.
 */
object AStar3D {

    /**
     * Checks if a block can be flown through by flying entities.
     * This includes air, water, lava, cobwebs, flowers, and other non-solid blocks.
     */
    private fun canFlyThrough(instance: Instance, x: Int, y: Int, z: Int): Boolean {
        val block = instance.getBlock(x, y, z)
        return !block.isSolid()
    }

    private fun canFlyThrough(instance: Instance, point: Point): Boolean {
        return canFlyThrough(instance, point.blockX(), point.blockY(), point.blockZ())
    }

    private data class Node(
        val point: Point,
        var g: Double,
        var h: Double,
        var parent: Node?
    ) {
        val f: Double get() = g + h
        override fun equals(other: Any?): Boolean = other is Node && this.point == other.point
        override fun hashCode(): Int = point.hashCode()
    }

    data class Result(val points: List<Point>, val success: Boolean)

    private val NEIGHBORS_3D = arrayOf(
        Vec(1.0, 0.0, 0.0), Vec(-1.0, 0.0, 0.0),
        Vec(0.0, 1.0, 0.0), Vec(0.0, -1.0, 0.0),
        Vec(0.0, 0.0, 1.0), Vec(0.0, 0.0, -1.0),
        Vec(1.0, 1.0, 0.0), Vec(-1.0, 1.0, 0.0), Vec(1.0, -1.0, 0.0), Vec(-1.0, -1.0, 0.0),
        Vec(1.0, 0.0, 1.0), Vec(-1.0, 0.0, 1.0), Vec(1.0, 0.0, -1.0), Vec(-1.0, 0.0, -1.0),
        Vec(0.0, 1.0, 1.0), Vec(0.0, -1.0, 1.0), Vec(0.0, 1.0, -1.0), Vec(0.0, -1.0, -1.0),
        Vec(1.0, 1.0, 1.0), Vec(-1.0, 1.0, 1.0), Vec(1.0, -1.0, 1.0), Vec(-1.0, -1.0, 1.0),
        Vec(1.0, 1.0, -1.0), Vec(-1.0, 1.0, -1.0), Vec(1.0, -1.0, -1.0), Vec(-1.0, -1.0, -1.0)
    )

    fun findPath(
        instance: Instance,
        start: Point,
        goal: Point,
        maxRange: Int = 30,
        timeoutMillis: Long = 10L
    ): Result {
        val startTime = System.nanoTime()
        fun timedOut(): Boolean = (System.nanoTime() - startTime) / 1_000_000L > timeoutMillis

        val startNode = Node(start, 0.0, heuristic(start, goal), null)
        val goalNode = Node(goal, 0.0, 0.0, null)

        val openSet = PriorityQueue(compareBy(Node::f))
        val allNodes = mutableMapOf<Point, Node>()

        openSet.add(startNode)
        allNodes[start] = startNode

        while (openSet.isNotEmpty()) {
            if (timedOut()) {
                return Result(emptyList(), false) // Timeout
            }

            val current = openSet.poll()

            if (current.point == goalNode.point) {
                return Result(reconstructPath(current), true)
            }

            for (neighborDir in NEIGHBORS_3D) {
                val neighborPoint = current.point.add(neighborDir)

                if (!isWalkable(instance, neighborPoint, start, maxRange)) {
                    continue
                }

                val tentativeG = current.g + current.point.distance(neighborPoint)
                val neighborNode = allNodes.getOrPut(neighborPoint) { Node(neighborPoint, Double.MAX_VALUE, heuristic(neighborPoint, goal), null) }

                if (tentativeG < neighborNode.g) {
                    neighborNode.parent = current
                    neighborNode.g = tentativeG
                    // h is already calculated
                    if (!openSet.contains(neighborNode)) {
                        openSet.add(neighborNode)
                    }
                }
            }
        }

        return Result(emptyList(), false) // No path found
    }

    private fun isWalkable(instance: Instance, point: Point, start: Point, maxRange: Int): Boolean {
        // Check bounds
        if (abs(point.blockX() - start.blockX()) > maxRange ||
            abs(point.blockY() - start.blockY()) > maxRange ||
            abs(point.blockZ() - start.blockZ()) > maxRange) {
            return false
        }

        val chunk = instance.getChunkAt(point)
        if (chunk == null || !chunk.isLoaded) {
            return true
        }

        // For flying entities, check if the block itself is air
        val currentBlock = instance.getBlock(point)

        // CRITICAL FIX: For vertical movement in narrow tunnels, allow movement through 1x1 spaces
        // Check if this is primarily vertical movement (same x and z coordinates)
        val isVerticalMove = point.blockX() == start.blockX() && point.blockZ() == start.blockZ()

        if (isVerticalMove) {
            // For vertical movement, only need the target block to be flyable
            return canFlyThrough(instance, point)
        } else {
            // For horizontal movement, check both the target and the block above
            val blockAbove = instance.getBlock(point.add(0.0, 1.0, 0.0))
            return canFlyThrough(instance, point) && canFlyThrough(instance, point.add(0.0, 1.0, 0.0))
        }
    }

    private fun heuristic(a: Point, b: Point): Double {
        // Manhattan distance - faster to calculate than Euclidean and still admissible.
        return (kotlin.math.abs(a.x() - b.x()) + kotlin.math.abs(a.y() - b.y()) + kotlin.math.abs(a.z() - b.z()))
    }

    private fun reconstructPath(node: Node): List<Point> {
        val path = mutableListOf<Point>()
        var current: Node? = node
        while (current != null) {
            path.add(current.point)
            current = current.parent
        }
        path.reverse()

        // Special handling for vertical movement - don't over-simplify vertical paths
        if (path.size > 2) {
            val simplifiedPath = mutableListOf<Point>()
            simplifiedPath.add(path.first())

            for (i in 1 until path.size - 1) {
                val prev = path[i - 1]
                val current = path[i]
                val next = path[i + 1]

                // Check if this is primarily vertical movement
                val isVerticalMove = abs(current.y() - prev.y()) > 0 &&
                        current.x() == prev.x() && current.z() == prev.z() &&
                        current.x() == next.x() && current.z() == next.z()

                // Don't remove nodes in vertical passages - they're crucial for navigation
                if (isVerticalMove) {
                    simplifiedPath.add(current)
                } else {
                    // Remove node if it's on a straight line from the previous to the next (for horizontal movement)
                    val dx1 = current.x() - prev.x()
                    val dy1 = current.y() - prev.y()
                    val dz1 = current.z() - prev.z()
                    val dx2 = next.x() - current.x()
                    val dy2 = next.y() - current.y()
                    val dz2 = next.z() - current.z()
                    if (dx1 != dx2 || dy1 != dy2 || dz1 != dz2) {
                        simplifiedPath.add(current)
                    }
                }
            }
            simplifiedPath.add(path.last())
            return simplifiedPath
        }
        return path
    }

    /**
     * Smooths the path by removing unnecessary intermediate points.
     * It does this by checking for a clear line of sight between points.
     */
    fun smoothPath(instance: Instance, path: List<Point>): List<Point> {
        if (path.size < 3) return path

        val smoothedPath = mutableListOf<Point>()
        smoothedPath.add(path.first())

        var currentIndex = 0
        while (currentIndex < path.size - 1) {
            var nextIndex = currentIndex + 1
            var lastVisibleIndex = nextIndex

            // Special handling for vertical passages - don't over-smooth
            val isVerticalPassage = abs(path[currentIndex].y() - path.last().y()) > 3.0 &&
                    path[currentIndex].x() == path.last().x() &&
                    path[currentIndex].z() == path.last().z()

            if (isVerticalPassage) {
                // For vertical passages, keep more waypoints to ensure proper navigation
                nextIndex = min(currentIndex + 2, path.size - 1)
                lastVisibleIndex = nextIndex
            } else {
                while (nextIndex < path.size) {
                    if (LineOfSight.hasClearLineOfSight3D(instance, path[currentIndex], path[nextIndex])) {
                        lastVisibleIndex = nextIndex
                    }
                    nextIndex++
                }
            }

            if (lastVisibleIndex > currentIndex) {
                if (lastVisibleIndex < path.size) {
                    smoothedPath.add(path[lastVisibleIndex])
                }
                currentIndex = lastVisibleIndex
            } else {
                currentIndex++
                if (currentIndex < path.size) {
                    smoothedPath.add(path[currentIndex])
                }
            }
        }

        if (smoothedPath.last() != path.last()) {
            smoothedPath.add(path.last())
        }

        return smoothedPath
    }
}
