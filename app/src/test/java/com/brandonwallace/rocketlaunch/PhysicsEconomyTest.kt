package com.brandonwallace.rocketlaunch

import com.brandonwallace.rocketlaunch.game.Achievement
import com.brandonwallace.rocketlaunch.game.Economy
import com.brandonwallace.rocketlaunch.game.Physics
import com.brandonwallace.rocketlaunch.game.RocketSpec
import com.brandonwallace.rocketlaunch.game.SaveData
import com.brandonwallace.rocketlaunch.game.UpgradeType
import com.brandonwallace.rocketlaunch.game.simulateAutopilot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsEconomyTest {
    private fun spec(vararg p: Pair<UpgradeType, Int>) = RocketSpec(mapOf(*p))
    private fun maxed() = RocketSpec(UpgradeType.entries.associateWith { it.maxLevel })

    @Test fun environment() {
        assertEquals(9.82, Physics.gravity(0.0), 0.01)
        assertEquals(Physics.gravity(0.0) / 4, Physics.gravity(Physics.R_EARTH), 0.01)
        assertEquals(1.225 / Math.E, Physics.density(8500.0), 1e-6)
        assertEquals(0.0, Physics.density(200_000.0), 0.0)
        assertEquals(1.0, Physics.timeWarp(0.0), 0.0)
        assertTrue(Physics.timeWarp(3_000.0) < 1.05)
        assertEquals(Physics.timeWarp(99_999.0), Physics.timeWarp(100_001.0), 0.01)
        assertTrue(Physics.timeWarp(10_000_000.0) > 100)
    }

    @Test fun baseRocketIsHumble() {
        val r = simulateAutopilot(spec())
        println("base apogee=${r.maxAlt} m top=${r.topSpeed} twr=${spec().liftoffTwr()}")
        assertTrue(r.maxAlt in 300.0..2500.0)
    }

    @Test fun upgradesHelp() {
        var prev = simulateAutopilot(spec()).maxAlt
        for (l in 1..UpgradeType.ENGINE.maxLevel) {
            val a = simulateAutopilot(spec(UpgradeType.ENGINE to l, UpgradeType.FUEL to l, UpgradeType.AERO to minOf(l, 5), UpgradeType.HEAT to l / 2)).maxAlt
            assertTrue("level $l: $a <= $prev", a > prev)
            prev = a
        }
        val noAero = simulateAutopilot(spec(UpgradeType.ENGINE to 2, UpgradeType.FUEL to 2)).maxAlt
        val aero = simulateAutopilot(spec(UpgradeType.ENGINE to 2, UpgradeType.FUEL to 2, UpgradeType.AERO to 3)).maxAlt
        assertTrue(aero > noAero)
    }

    @Test fun maxRocketReachesMoonQuickly() {
        val s = maxed()
        val r = simulateAutopilot(s)
        val atmT = run { var t = 0.0; t }; println("maxed: dv=${s.idealDeltaV()} twr=${s.liftoffTwr()} mass=${s.liftoffMass()} moon=${r.reachedMoon} destroyed=${r.destroyed} maxAlt=${r.maxAlt / 1000} km top=${r.topSpeed} realTime=${r.warpedRealTime}s")
        assertTrue(r.reachedMoon)
        assertTrue("trip takes ${r.warpedRealTime}s of real time", r.warpedRealTime < 240)
    }

    @Test fun saveRoundTrip() {
        val s = SaveData(coins = 1234, levels = mapOf(UpgradeType.ENGINE to 3, UpgradeType.MAGNET to 2),
            bestAltitude = 12345.6, flights = 7, achievements = setOf(Achievement.KM1, Achievement.KARMAN), tiltControl = true)
        assertEquals(s, SaveData.parse(s.serialize()))
        assertEquals(SaveData(), SaveData.parse(null))
        assertEquals(SaveData(), SaveData.parse("garbage;;=;x"))
    }

    @Test fun buyingAndCosts() {
        for (t in UpgradeType.entries) for (i in 1 until t.costs.size) assertTrue(t.costs[i] > t.costs[i - 1])
        val s = SaveData(coins = 100)
        val b = s.buy(UpgradeType.ENGINE)
        assertNotNull(b)
        assertEquals(100L - UpgradeType.ENGINE.costs[0], b!!.coins)
        assertEquals(1, b.level(UpgradeType.ENGINE))
        assertNull(SaveData(coins = 1).buy(UpgradeType.ENGINE))
        val maxed = SaveData(coins = 1_000_000, levels = mapOf(UpgradeType.FINS to UpgradeType.FINS.maxLevel))
        assertNull(maxed.buy(UpgradeType.FINS))
        assertTrue(Economy.altitudeCoins(100_000.0) > Economy.altitudeCoins(10_000.0))
    }

    /** Greedy player: fly autopilot, earn altitude coins (+ modest pickups), buy the cheapest useful upgrade. */
    @Test fun progressionCurve() {
        var save = SaveData()
        val useful = listOf(UpgradeType.ENGINE, UpgradeType.FUEL, UpgradeType.AERO, UpgradeType.STAGES, UpgradeType.BOOSTERS, UpgradeType.HEAT)
        var flights = 0
        var milestones = ""
        var reachedSpace = -1
        while (flights < 200) {
            flights++
            val r = simulateAutopilot(RocketSpec(save.levels))
            val pickups = (Economy.altitudeCoins(r.maxAlt) * 0.25).toInt()
            var earned = Economy.altitudeCoins(r.maxAlt) + pickups
            if (r.reachedMoon) { milestones += " MOON@$flights"; break }
            if (r.maxAlt > 100_000 && reachedSpace < 0) { reachedSpace = flights; earned += 600 }
            if (flights % 5 == 0 || flights < 6) milestones += "\n #$flights alt=${"%.1f".format(r.maxAlt / 1000)}km dv=${RocketSpec(save.levels).idealDeltaV().toInt()} heatDead=${r.destroyed} earned=$earned lv=${save.levels.values.sum()}"
            save = save.copy(coins = save.coins + earned)
            while (true) {
                val t = useful.filter { save.canBuy(it) }.minByOrNull { save.nextCost(it)!! } ?: break
                save = save.buy(t)!!
            }
        }
        println("progression:$milestones\nspace at flight $reachedSpace")
        assertTrue("moon took $flights flights", flights in 15..90)
    }
}

class HeatTest {
    private fun peak(spec: RocketSpec): Pair<Double, Double> {
        val s = com.brandonwallace.rocketlaunch.game.FlightState(spec)
        val ev = ArrayList<com.brandonwallace.rocketlaunch.game.FlightEvent>()
        var peak = 0.0
        while (s.alt < 200_000 && s.time < 1000 && s.hull > 0) { s.step(0.02, 1.0, ev); peak = maxOf(peak, s.heat) }
        return peak to s.hull
    }
    @Test fun heatShieldMatters() {
        val results = (0..3).map { h -> peak(RocketSpec(UpgradeType.entries.associateWith { if (it == UpgradeType.HEAT) h else it.maxLevel })) }
        results.forEachIndexed { h, (p, hull) -> println("heat lvl $h peak=${"%.2f".format(p)} hull=${hull.toInt()}") }
        assertTrue("no shield should overheat", results[0].first > 1.0)
        assertTrue("max shield should stay cool", results[3].first < 1.0)
    }
}
