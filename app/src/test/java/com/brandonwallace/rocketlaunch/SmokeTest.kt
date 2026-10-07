package com.brandonwallace.rocketlaunch

import com.brandonwallace.rocketlaunch.audio.SoundEngine
import com.brandonwallace.rocketlaunch.game.GameEngine
import com.brandonwallace.rocketlaunch.game.Phase
import com.brandonwallace.rocketlaunch.game.SaveStore
import com.brandonwallace.rocketlaunch.game.UpgradeType
import com.brandonwallace.rocketlaunch.gl.GameRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Headless end-to-end smoke test: drives the real GameEngine + GameRenderer (GL calls are no-op stubs
 * in JVM unit tests) through countdown, flights, staging, results and the shop, to catch logic crashes
 * (index errors, buffer overflows, NaNs) without a device.
 */
class SmokeTest {
    private class MemStore : SaveStore { var data: String? = null; override fun load() = data; override fun save(data: String) { this.data = data } }

    val buckets = IntArray(4)
    private fun fly(engine: GameEngine, renderer: GameRenderer, steerSeed: Float, maxFrames: Int = 200_000, smartCoast: Boolean = false): Int {
        engine.openHangar(); renderer.onDrawFrame(null)
        engine.launch()
        var frames = 0
        buckets.fill(0)
        while (engine.phase != Phase.RESULTS && frames < maxFrames) {
            engine.inputThrottle = engine.phase == Phase.FLIGHT && !(smartCoast && engine.hud.value.moonBound)
            engine.inputSteer = sin(frames * 0.013f + steerSeed)
            if (frames % 700 == 350) { engine.setPaused(true); engine.update(1 / 60f); engine.setPaused(false) }
            engine.update(1 / 60f)
            renderer.onDrawFrame(null)
            val f = engine.flight
            if (f != null) assertTrue("NaN state", !f.alt.isNaN() && !f.vy.isNaN())
            if (f != null) { val b = (if (f.alt < 100_000) 0 else 2) + (if (f.thrustNow > 0) 0 else 1); buckets[b]++ }
            frames++
        }
        assertEquals("flight did not end", Phase.RESULTS, engine.phase)
        repeat(30) { engine.update(1 / 60f); renderer.onDrawFrame(null) }
        return frames
    }

    @Test fun fullCampaignHeadless() {
        val store = MemStore()
        val engine = GameEngine(store)
        val renderer = GameRenderer(engine, SoundEngine())
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, 1080, 2400)
        repeat(120) { engine.update(1 / 60f); renderer.onDrawFrame(null) } // menu
        var moon = false
        var flightsTaken = 0
        for (flight in 1..60) {
            val frames = fly(engine, renderer, flight.toFloat())
            val r = engine.save.value
            val res = engine.hud.value.results!!
            println("flight $flight: ${frames} frames, alt=${"%.1f".format(res.maxAlt / 1000)} km, coins=${res.total} (pick ${res.pickupCoins} alt ${res.altitudeCoins} ach ${res.achievementCoins}), reason=${res.reason}, moon=${res.moonReached}, bank=${r.coins} lowBurn/lowCoast/hiBurn/hiCoast=${buckets.toList()}")
            if (res.moonReached) { moon = true; flightsTaken = flight; break }
            // greedy shop: buy cheapest affordable upgrade repeatedly
            while (true) {
                val s = engine.save.value
                val t = UpgradeType.values().filter { s.canBuy(it) }.minByOrNull { s.nextCost(it)!! } ?: break
                assertTrue(engine.buy(t))
                renderer.onDrawFrame(null) // rebuild rocket model with new spec
            }
        }
        assertTrue("campaign should reach the Moon within 60 flights", moon)
        assertTrue("Moon came too easily ($flightsTaken flights)", flightsTaken >= 14)
        // reload save from store
        val engine2 = GameEngine(store)
        assertEquals(engine.save.value, engine2.save.value)
    }

    @Test fun maxedRocketAndStress() {
        val engine = GameEngine(MemStore())
        val renderer = GameRenderer(engine, SoundEngine())
        renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 720, 1600)
        engine.grantCoins(10_000_000)
        for (t in UpgradeType.values()) while (engine.buy(t)) renderer.onDrawFrame(null)
        val frames = fly(engine, renderer, 0.5f)
        val res = engine.hud.value.results!!
        println("maxed: $frames frames, moon=${res.moonReached} landed=${res.moonLanded} reason=${res.reason}")
        assertTrue(res.moonReached)
        // a pilot who heeds the HUD and coasts once Moon-bound should land
        fly(engine, renderer, 0.5f, smartCoast = true)
        val res2 = engine.hud.value.results!!
        println("maxed smart: moon=${res2.moonReached} landed=${res2.moonLanded} reason=${res2.reason}")
        assertTrue(res2.moonLanded)
        // abort path
        engine.openHangar(); engine.launch()
        repeat(400) { engine.inputThrottle = true; engine.update(1 / 60f); renderer.onDrawFrame(null) }
        engine.abortFlight()
        repeat(10) { engine.update(1 / 60f); renderer.onDrawFrame(null) }
        assertEquals(Phase.RESULTS, engine.phase)
    }
}
