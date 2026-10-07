package com.brandonwallace.rocketlaunch.game

/** Every upgrade line. [costs] has one entry per purchasable tier, so maxLevel = costs.size. */
enum class UpgradeType(val title: String, val icon: String, val blurb: String, val costs: IntArray) {
    ENGINE("Engine", "🔥", "More thrust, better efficiency", intArrayOf(40, 120, 320, 800, 1900, 4400)),
    FUEL("Fuel Tank", "⛽", "Bigger tanks, longer burns", intArrayOf(35, 100, 280, 700, 1700, 4000)),
    AERO("Nose Cone", "🔺", "Slices through the air", intArrayOf(60, 180, 500, 1300, 3200)),
    HULL("Hull Armor", "🛡️", "Shrug off impacts", intArrayOf(50, 160, 450, 1100, 2700)),
    STAGES("Extra Stage", "🧱", "Stack another stage on top", intArrayOf(650, 2800, 9000)),
    BOOSTERS("Boosters", "🚀", "Strap-on side boosters", intArrayOf(400, 1800, 6000)),
    HEAT("Heat Shield", "♨️", "Survive hypersonic heating", intArrayOf(300, 1300, 4200)),
    FINS("Steering Fins", "✈️", "Sharper, faster steering", intArrayOf(45, 150, 450, 1200)),
    MAGNET("Coin Magnet", "🧲", "Pulls in nearby pickups", intArrayOf(120, 400, 1200, 3200));

    val maxLevel: Int get() = costs.size
}

enum class Achievement(val title: String, val desc: String, val reward: Int) {
    FIRST_FLIGHT("Liftoff!", "Complete your first flight", 15),
    KM1("Kilometer Club", "Reach 1 km", 25),
    CLOUDS("Cloud Surfer", "Reach the clouds (2.5 km)", 50),
    SOUND_BARRIER("Sound Barrier", "Fly faster than 343 m/s", 120),
    STRATOSPHERE("Stratosphere", "Reach 12 km", 150),
    KARMAN("Hello, Space!", "Cross the Kármán line (100 km)", 600),
    ORBIT("Orbital Altitude", "Reach 400 km — ISS height", 1500),
    HYPERSONIC("Hypersonic", "Fly faster than 5 km/s", 2000),
    DEEP_SPACE("Deep Space", "Reach 20,000 km", 4000),
    MOON_REACHED("Lunar Arrival", "Reach the Moon", 10000),
    MOON_LANDING("The Eagle Has Landed", "Land on the Moon with fuel to spare", 25000),
    COLLECTOR("Collector", "Grab 50 coins in one flight", 300),
    TOUGH("Tough as Nails", "Survive 5 hazard hits in one flight", 400),
    STAGER("Staging Master", "Separate 3 stages in one flight", 800),
    MAXED("Fully Upgraded", "Max out any upgrade", 500);
}

/** Immutable save game. Serialized to a simple key=value string (no Android deps, unit-testable). */
data class SaveData(
    val coins: Long = 0,
    val levels: Map<UpgradeType, Int> = emptyMap(),
    val bestAltitude: Double = 0.0,
    val bestSpeed: Double = 0.0,
    val bestDistance: Double = 0.0,
    val flights: Int = 0,
    val achievements: Set<Achievement> = emptySet(),
    val totalCoinsEarned: Long = 0,
    val moonLandings: Int = 0,
    val soundOn: Boolean = true,
    val tiltControl: Boolean = false,
) {
    fun level(t: UpgradeType): Int = (levels[t] ?: 0).coerceIn(0, t.maxLevel)
    fun nextCost(t: UpgradeType): Int? = t.costs.getOrNull(level(t))
    fun canBuy(t: UpgradeType): Boolean = nextCost(t)?.let { coins >= it } ?: false

    /** Returns the new save after buying one tier, or null if not affordable / maxed. */
    fun buy(t: UpgradeType): SaveData? {
        val cost = nextCost(t) ?: return null
        if (coins < cost) return null
        return copy(coins = coins - cost, levels = levels + (t to level(t) + 1))
    }

    fun serialize(): String = buildString {
        append("v=1;coins=").append(coins)
        append(";bestAlt=").append(bestAltitude)
        append(";bestSpd=").append(bestSpeed)
        append(";bestDist=").append(bestDistance)
        append(";flights=").append(flights)
        append(";earned=").append(totalCoinsEarned)
        append(";moon=").append(moonLandings)
        append(";sound=").append(soundOn)
        append(";tilt=").append(tiltControl)
        for (t in UpgradeType.entries) append(";lvl.").append(t.name).append('=').append(level(t))
        append(";ach=").append(achievements.joinToString(",") { it.name })
    }

    companion object {
        fun parse(s: String?): SaveData {
            if (s.isNullOrBlank()) return SaveData()
            val m = HashMap<String, String>()
            for (part in s.split(';')) {
                val i = part.indexOf('=')
                if (i > 0) m[part.substring(0, i)] = part.substring(i + 1)
            }
            val levels = HashMap<UpgradeType, Int>()
            for (t in UpgradeType.entries) {
                val l = m["lvl.${t.name}"]?.toIntOrNull() ?: 0
                if (l > 0) levels[t] = l.coerceAtMost(t.maxLevel)
            }
            val ach = m["ach"].orEmpty().split(',').mapNotNull { n ->
                Achievement.entries.firstOrNull { it.name == n }
            }.toSet()
            return SaveData(
                coins = m["coins"]?.toLongOrNull() ?: 0,
                levels = levels,
                bestAltitude = m["bestAlt"]?.toDoubleOrNull() ?: 0.0,
                bestSpeed = m["bestSpd"]?.toDoubleOrNull() ?: 0.0,
                bestDistance = m["bestDist"]?.toDoubleOrNull() ?: 0.0,
                flights = m["flights"]?.toIntOrNull() ?: 0,
                achievements = ach,
                totalCoinsEarned = m["earned"]?.toLongOrNull() ?: 0,
                moonLandings = m["moon"]?.toIntOrNull() ?: 0,
                soundOn = m["sound"]?.toBooleanStrictOrNull() ?: true,
                tiltControl = m["tilt"]?.toBooleanStrictOrNull() ?: false,
            )
        }
    }
}

/** Coin rewards. Kept here so tests and the game share one formula. */
object Economy {
    fun altitudeCoins(maxAltM: Double): Int {
        val km = maxAltM / 1000.0
        if (km <= 0.0) return 0
        val low = 30.0 * Math.pow(km.coerceAtMost(100.0), 0.62) + 4.0 * km.coerceAtMost(25.0)
        // above the Karman line rewards grow logarithmically so deep-space flights don't trivialize the shop
        val high = if (km > 100.0) 380.0 * Math.log(km / 100.0) else 0.0
        return Math.round(low + high).toInt()
    }

    fun coinValueAt(altM: Double): Int = when {
        altM < 2_500 -> 1
        altM < 12_000 -> 2
        altM < 50_000 -> 3
        altM < 100_000 -> 5
        altM < 2_000_000 -> 8
        else -> 12
    }
}
