package twizzy.tech.game.pathfinding

import net.minestom.server.coordinate.Point
import net.minestom.server.instance.Instance
import kotlin.math.abs
import kotlin.math.floor

object LineOfSight {

    /**
     * Checks if a block can be flown through by flying entities.
     * This includes air, water, lava, cobwebs, flowers, and other non-solid blocks.
     */
    private fun canFlyThrough(instance: Instance, x: Int, y: Int, z: Int): Boolean {
        val block = instance.getBlock(x, y, z)
        return !block.isSolid()
    }

    /**
     * A more robust 3D line-of-sight check.
     * It iterates along the dominant axis and calculates the other coordinates.
     */
    fun hasClearLineOfSight3D(instance: Instance, start: Point, end: Point): Boolean {
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

        // Dominant axis logic
        if (dx >= dy && dx >= dz) { // X dominant
            var p1 = 2 * dy - dx
            var p2 = 2 * dz - dx
            while (x != x2) {
                if (!canFlyThrough(instance, x, y, z)) return false
                if (p1 >= 0) {
                    y += sy
                    p1 -= 2 * dx
                }
                if (p2 >= 0) {
                    z += sz
                    p2 -= 2 * dx
                }
                p1 += 2 * dy
                p2 += 2 * dz
                x += sx
            }
        } else if (dy >= dx && dy >= dz) { // Y dominant
            var p1 = 2 * dx - dy
            var p2 = 2 * dz - dy
            while (y != y2) {
                if (!canFlyThrough(instance, x, y, z)) return false
                if (p1 >= 0) {
                    x += sx
                    p1 -= 2 * dy
                }
                if (p2 >= 0) {
                    z += sz
                    p2 -= 2 * dy
                }
                p1 += 2 * dx
                p2 += 2 * dz
                y += sy
            }
        } else { // Z dominant
            var p1 = 2 * dy - dz
            var p2 = 2 * dx - dz
            while (z != z2) {
                if (!canFlyThrough(instance, x, y, z)) return false
                if (p1 >= 0) {
                    y += sy
                    p1 -= 2 * dz
                }
                if (p2 >= 0) {
                    x += sx
                    p2 -= 2 * dz
                }
                p1 += 2 * dy
                p2 += 2 * dx
                z += sz
            }
        }
        // Final check at the destination
        return canFlyThrough(instance, x2, y2, z2)
    }
}
