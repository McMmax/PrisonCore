package twizzy.tech.game.pets.controller

import com.github.shynixn.mccoroutine.minestom.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import twizzy.tech.serverInstance

/**
 * Single coroutine loop that updates all registered FlightControllers.
 * Reduces coroutine overhead and improves cache locality for hundreds of pets.
 */
object PetFlightScheduler {
    private val controllers = ConcurrentHashMap.newKeySet<FlightController>()
    private var loopJob: Job? = null

    private const val TICK_INTERVAL_MS = 50L // ~20 TPS

    // Global per-tick budget for expensive operations (e.g., A* pathfinding)
    private const val PATH_BUDGET_PER_TICK = 20
    @Volatile private var pathBudgetTokens: Int = PATH_BUDGET_PER_TICK

    fun tryConsumePathBudget(count: Int = 1): Boolean {
        synchronized(this) {
            return if (pathBudgetTokens >= count) {
                pathBudgetTokens -= count
                true
            } else {
                false
            }
        }
    }

    fun register(controller: FlightController) {
        controllers.add(controller)
        ensureLoop()
    }

    fun unregister(controller: FlightController) {
        controllers.remove(controller)
        // Optionally stop loop when empty
        if (controllers.isEmpty()) {
            loopJob?.cancel()
            loopJob = null
        }
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = serverInstance.launch {
            while (controllers.isNotEmpty()) {
                val startNs = System.nanoTime()
                // Reset budgets
                pathBudgetTokens = PATH_BUDGET_PER_TICK

                // Snapshot to avoid CME while iterating
                val snapshot = controllers.toList()
                for (c in snapshot) {
                    try {
                        c.tick()
                    } catch (_: Throwable) {
                        // Keep loop healthy; optionally log
                    }
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                val sleep = TICK_INTERVAL_MS - elapsedMs
                if (sleep > 0) delay(sleep) else yield()
            }
        }
    }
}
