package com.brandonwallace.rocketlaunch.game

import kotlin.math.PI
import kotlin.math.ln

class StageSpec(
    val dryMass: Double,
    val fuelMass: Double,
    val thrust: Double,   // N
    val isp: Double,      // s
    val length: Float,    // m (visual)
    val radius: Float,    // m (visual)
)

/** Everything about the rocket that derives from the upgrade levels. */
class RocketSpec(levels: Map<UpgradeType, Int>) {
    private val lv = UpgradeType.entries.associateWith { (levels[it] ?: 0).coerceIn(0, it.maxLevel) }
    fun level(t: UpgradeType) = lv.getValue(t)

    companion object {
        val THRUST_KN = doubleArrayOf(16.0, 26.0, 42.0, 68.0, 110.0, 180.0, 300.0)
        val ISP = doubleArrayOf(200.0, 222.0, 246.0, 272.0, 298.0, 325.0, 352.0)
        val FUEL_KG = doubleArrayOf(90.0, 190.0, 400.0, 820.0, 1650.0, 3200.0, 6200.0)
        val CD = doubleArrayOf(0.85, 0.66, 0.52, 0.42, 0.34, 0.27)
        val HULL = doubleArrayOf(100.0, 160.0, 240.0, 340.0, 470.0, 650.0)
        val HEAT_TOL = doubleArrayOf(1.0, 2.0, 3.6, 6.5)
        val MAX_TILT_DEG = floatArrayOf(14f, 18f, 23f, 28f, 34f)
        val STEER_RESP = floatArrayOf(2.2f, 3.0f, 4.0f, 5.2f, 6.5f)
        val MAGNET_M = floatArrayOf(0f, 4f, 6.5f, 9f, 12f)
        val UPPER_FUEL_FRAC = doubleArrayOf(0.70, 0.46, 0.30)
        val UPPER_THRUST_FRAC = doubleArrayOf(0.85, 0.6, 0.4)
        const val RADIUS = 0.85f
        const val PAYLOAD_KG = 220.0
    }

    val thrust1 = THRUST_KN[level(UpgradeType.ENGINE)] * 1000.0
    val isp1 = ISP[level(UpgradeType.ENGINE)]
    val fuel1 = FUEL_KG[level(UpgradeType.FUEL)]

    val stages: List<StageSpec> = buildList {
        val len1 = 3.2f + 0.85f * level(UpgradeType.FUEL)
        add(StageSpec(dryMass(fuel1, thrust1, 230.0), fuel1, thrust1, isp1, len1, RADIUS))
        for (k in 0 until level(UpgradeType.STAGES)) {
            val f = fuel1 * UPPER_FUEL_FRAC[k]
            val t = thrust1 * UPPER_THRUST_FRAC[k]
            add(StageSpec(dryMass(f, t, 45.0, 0.06, 0.8), f, t, isp1 + 72.0, len1 * (0.75f - 0.12f * k), RADIUS * (0.96f - 0.06f * k)))
        }
    }

    val boosterCount: Int = when (level(UpgradeType.BOOSTERS)) { 0 -> 0; 3 -> 4; else -> 2 }
    val booster: StageSpec? = if (boosterCount == 0) null else {
        val big = level(UpgradeType.BOOSTERS) >= 2
        val f = fuel1 * (if (big) 0.42 else 0.26)
        val t = thrust1 * (if (big) 0.52 else 0.36)
        StageSpec(dryMass(f, t, 35.0), f, t, isp1 - 15.0, stages[0].length * (if (big) 0.85f else 0.68f), if (big) 0.46f else 0.38f)
    }

    val payloadMass = PAYLOAD_KG + 35.0 * level(UpgradeType.HULL) + 30.0 * level(UpgradeType.HEAT)
    val cd = CD[level(UpgradeType.AERO)]
    val area = PI * RADIUS * RADIUS + boosterCount * 0.28
    val maxHull = HULL[level(UpgradeType.HULL)]
    val heatTolerance = HEAT_TOL[level(UpgradeType.HEAT)]
    val maxTiltRad = Math.toRadians(MAX_TILT_DEG[level(UpgradeType.FINS)].toDouble())
    val steerResponse = STEER_RESP[level(UpgradeType.FINS)]
    val magnetRadius = MAGNET_M[level(UpgradeType.MAGNET)]

    private fun dryMass(fuel: Double, thrustN: Double, base: Double, frac: Double = 0.085, perKn: Double = 1.6) = base + frac * fuel + perKn * thrustN / 1000.0

    fun liftoffMass(): Double =
        payloadMass + stages.sumOf { it.dryMass + it.fuelMass } + boosterCount * ((booster?.dryMass ?: 0.0) + (booster?.fuelMass ?: 0.0))

    fun liftoffTwr(): Double =
        (thrust1 + boosterCount * (booster?.thrust ?: 0.0)) / (liftoffMass() * Physics.G0)

    /** Ideal (no gravity/drag) delta-v, boosters approximated as burning with stage 1. */
    fun idealDeltaV(): Double {
        var m = liftoffMass()
        var dv = 0.0
        val b = booster
        if (b != null) {
            val flowB = boosterCount * b.thrust / (b.isp * Physics.G0)
            val flow1 = stages[0].thrust / (stages[0].isp * Physics.G0)
            val tb = b.fuelMass / (b.thrust / (b.isp * Physics.G0))
            val burned = flowB * tb + flow1 * tb
            val ispEff = (boosterCount * b.thrust + stages[0].thrust) / ((flowB + flow1) * Physics.G0)
            dv += ispEff * Physics.G0 * ln(m / (m - burned))
            m -= burned + boosterCount * b.dryMass
            val left = stages[0].fuelMass - flow1 * tb
            dv += stages[0].isp * Physics.G0 * ln(m / (m - left))
            m -= left + stages[0].dryMass
            for (s in stages.drop(1)) { dv += s.isp * Physics.G0 * ln(m / (m - s.fuelMass)); m -= s.fuelMass + s.dryMass }
        } else {
            for (s in stages) { dv += s.isp * Physics.G0 * ln(m / (m - s.fuelMass)); m -= s.fuelMass + s.dryMass }
        }
        return dv
    }

    /** Short human description of what a tier gives. */
    fun describe(t: UpgradeType, level: Int): String = when (t) {
        UpgradeType.ENGINE -> "${THRUST_KN[level].toInt()} kN · Isp ${ISP[level].toInt()} s"
        UpgradeType.FUEL -> "${FUEL_KG[level].toInt()} kg fuel"
        UpgradeType.AERO -> "drag Cd ${"%.2f".format(CD[level])}"
        UpgradeType.HULL -> "${HULL[level].toInt()} hull HP"
        UpgradeType.STAGES -> "${level + 1} stage${if (level > 0) "s" else ""}"
        UpgradeType.BOOSTERS -> when (level) { 0 -> "none"; 1 -> "2 small"; 2 -> "2 large"; else -> "4 large" }
        UpgradeType.HEAT -> "×${HEAT_TOL[level]} heat tolerance"
        UpgradeType.FINS -> "${MAX_TILT_DEG[level].toInt()}° max tilt"
        UpgradeType.MAGNET -> if (level == 0) "none" else "${MAGNET_M[level].toInt()} m pull radius"
    }
}
