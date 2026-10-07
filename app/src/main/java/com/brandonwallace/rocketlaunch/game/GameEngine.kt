package com.brandonwallace.rocketlaunch.game

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class Phase { MENU, HANGAR, COUNTDOWN, FLIGHT, RESULTS }
enum class Sfx { COIN, FUEL, REPAIR, HIT, EXPLODE, STAGE, BEEP, LIFTOFF, ACHIEVE, CLICK, WIN }

interface SaveStore { fun load(): String?; fun save(data: String) }

data class FlightResult(
    val maxAlt: Double, val topSpeed: Double, val distance: Double, val time: Double,
    val pickupCoins: Int, val altitudeCoins: Int, val achievementCoins: Int,
    val records: List<String>, val achievements: List<Achievement>, val reason: String,
    val moonReached: Boolean, val moonLanded: Boolean, val hits: Int,
) { val total: Int get() = pickupCoins + altitudeCoins + achievementCoins }

data class HudState(
    val phase: Phase = Phase.MENU,
    val paused: Boolean = false,
    val countdown: Int = 0,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,
    val vSpeed: Double = 0.0,
    val fuel: Float = 1f,
    val boosterFuel: Float = -1f,
    val stage: Int = 0,
    val stageCount: Int = 1,
    val coins: Int = 0,
    val hull: Float = 1f,
    val heat: Float = 0f,
    val zone: String = "",
    val warp: Float = 1f,
    val throttle: Float = 0f,
    val moonProgress: Float = 0f,
    val banner: String = "",
    val bannerId: Int = 0,
    val hits: Int = 0,
    val showHint: Boolean = false,
    val ending: Boolean = false,
    val mach: Float = 0f,
    val results: FlightResult? = null,
    /** Current orbital energy already carries the rocket to the Moon (vacuum, upward). */
    val moonBound: Boolean = false,
)

/** Visual-only events the renderer turns into particles. */
sealed class Fx {
    data class StageSep(val index: Int) : Fx()
    data object BoosterSep : Fx()
    data class Hit(val x: Float, val y: Float) : Fx()
    data class Collect(val x: Float, val y: Float, val kind: EntityKind) : Fx()
    data class Explode(val x: Float, val y: Float) : Fx()
    data object Liftoff : Fx()
}

/** A separated stage or booster tumbling away. group >= 0: stage index, group < 0: booster -(group+1). */
class SpentPart(val group: Int, var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val spin: Float) { var age = 0f }

class GameEngine(private val store: SaveStore) {
    val lock = Any()
    private val _save = MutableStateFlow(SaveData.parse(store.load()))
    val save: StateFlow<SaveData> = _save
    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud

    @Volatile var inputThrottle = false
    @Volatile var inputSteer = 0f
    var sfxListener: ((Sfx) -> Unit)? = null

    var phase = Phase.MENU; private set
    var paused = false; private set
    var spec = RocketSpec(_save.value.levels); private set
    var specVersion = 0; private set
    var flight: FlightState? = null; private set
    val world = World()
    val spent = ArrayList<SpentPart>()
    val fx = ArrayList<Fx>()

    // visual state read by the renderer
    var time = 0f; private set
    var countdown = 0f; private set
    var laneX = 0f; private set
    var laneVx = 0f; private set
    var tilt = 0f; private set
    var scroll = 0f; private set
    var warp = 1.0; private set
    var shake = 0f; private set
    var exploded = false; private set
    var ending = false; private set
    var holdingVisual = false; private set
    var engineGlow = 0f; private set

    private var autoBurn = 0f
    private var endTimer = 0f
    private var descendTimer = 0f
    private var flightCoins = 0
    private var coinPickups = 0
    private var hits = 0
    private var everHeld = false
    private var bannerText = ""
    private var bannerId = 0
    private var endReason = ""
    private var moonReached = false
    private var moonLanded = false
    private val newAch = ArrayList<Achievement>()
    private var zoneIdx = 0
    private var results: FlightResult? = null
    private var hudTimer = 0f
    private val flightEvents = ArrayList<FlightEvent>()
    private val worldEvents = ArrayList<WorldEvent>()

    private val zones = listOf(
        2_500.0 to "☁️ Entering the clouds",
        12_000.0 to "Stratosphere",
        50_000.0 to "Mesosphere — the sky turns black",
        100_000.0 to "🌌 KÁRMÁN LINE — YOU'RE IN SPACE!",
        400_000.0 to "🛰️ Orbital altitude (ISS height)",
        2_000_000.0 to "High Earth orbit",
        20_000_000.0 to "Deep space",
        300_000_000.0 to "🌕 Approaching the Moon",
    )

    fun zoneName(alt: Double): String = when {
        alt < 2_500 -> "Lower atmosphere"
        alt < 12_000 -> "Cloud layer"
        alt < 50_000 -> "Stratosphere"
        alt < 100_000 -> "Mesosphere"
        alt < 2_000_000 -> "Low Earth orbit"
        alt < 36_000_000 -> "High orbit"
        else -> "Cislunar space"
    }

    // ---------------- UI commands (main thread) ----------------

    fun openHangar() = synchronized(lock) { phase = Phase.HANGAR; paused = false; resetVisuals(); sfx(Sfx.CLICK) }
    fun openMenu() = synchronized(lock) { phase = Phase.MENU; paused = false; resetVisuals() }

    fun launch() = synchronized(lock) {
        rebuildSpec()
        resetVisuals()
        phase = Phase.COUNTDOWN
        countdown = 3.6f
        paused = false
    }

    fun setPaused(p: Boolean) = synchronized(lock) { if (phase == Phase.FLIGHT || phase == Phase.COUNTDOWN) paused = p }

    fun abortFlight() = synchronized(lock) {
        if (phase == Phase.FLIGHT && !ending) { endReason = "Flight aborted"; finishFlight() }
        else if (phase == Phase.COUNTDOWN) { phase = Phase.HANGAR; resetVisuals() }
        paused = false
    }

    fun buy(t: UpgradeType): Boolean = synchronized(lock) {
        var s = _save.value.buy(t) ?: return false
        if (s.level(t) == t.maxLevel && Achievement.MAXED !in s.achievements) {
            s = s.copy(achievements = s.achievements + Achievement.MAXED, coins = s.coins + Achievement.MAXED.reward)
            showBanner("🏆 ${Achievement.MAXED.title} +${Achievement.MAXED.reward}")
            sfx(Sfx.ACHIEVE)
        } else sfx(Sfx.COIN)
        commit(s)
        rebuildSpec()
        true
    }

    fun setSound(on: Boolean) = synchronized(lock) { commit(_save.value.copy(soundOn = on)) }
    fun setTilt(on: Boolean) = synchronized(lock) { commit(_save.value.copy(tiltControl = on)) }
    fun resetProgress() = synchronized(lock) {
        val s = _save.value
        commit(SaveData(soundOn = s.soundOn, tiltControl = s.tiltControl))
        rebuildSpec(); phase = Phase.MENU
    }

    /** Debug/test helper. */
    fun grantCoins(n: Long) = synchronized(lock) { commit(_save.value.copy(coins = _save.value.coins + n)) }

    private fun commit(s: SaveData) { _save.value = s; store.save(s.serialize()) }
    private fun rebuildSpec() { spec = RocketSpec(_save.value.levels); specVersion++ }
    private fun sfx(s: Sfx) { if (_save.value.soundOn) sfxListener?.invoke(s) }
    private fun showBanner(t: String) { bannerText = t; bannerId++ }

    private fun resetVisuals() {
        flight = null; world.reset((System.nanoTime() and 0xffff).toInt()); spent.clear()
        laneX = 0f; laneVx = 0f; tilt = 0f; scroll = 0f; warp = 1.0; shake = 0f
        exploded = false; ending = false; holdingVisual = false; engineGlow = 0f
        results = null
    }

    // ---------------- simulation (GL thread) ----------------

    fun update(dtIn: Float) = synchronized(lock) {
        val dt = dtIn.coerceIn(0f, 0.05f)
        time += dt
        fx.clear()
        if (!paused) {
            when (phase) {
                Phase.COUNTDOWN -> updateCountdown(dt)
                Phase.FLIGHT -> updateFlight(dt)
                else -> { engineGlow = 0f; shake = max(0f, shake - dt * 2f) }
            }
        }
        hudTimer -= dt
        if (hudTimer <= 0f || paused) { hudTimer = 1f / 30f; publishHud() }
    }

    private fun updateCountdown(dt: Float) {
        val before = ceil(countdown).toInt()
        countdown -= dt
        val after = ceil(countdown).toInt()
        if (after != before && after in 1..3) sfx(Sfx.BEEP)
        engineGlow = if (countdown < 1.1f) ((1.1f - countdown) / 1.1f).coerceIn(0f, 1f) * 0.6f else 0f
        shake = engineGlow * 0.5f
        if (countdown <= 0f) beginFlight()
    }

    private fun beginFlight() {
        phase = Phase.FLIGHT
        flight = FlightState(spec)
        autoBurn = 1.4f
        endTimer = 0f; descendTimer = 0f; flightCoins = 0; coinPickups = 0; hits = 0; everHeld = false
        endReason = ""; moonReached = false; moonLanded = false; newAch.clear(); zoneIdx = 0
        sfx(Sfx.LIFTOFF)
        showBanner("🚀 LIFTOFF!")
    }

    private fun updateFlight(dt: Float) {
        val f = flight ?: return
        if (inputThrottle) everHeld = true
        autoBurn -= dt
        val holding = !ending && !exploded && (inputThrottle || autoBurn > 0f)
        holdingVisual = holding && f.hasThrustAvailable()

        // steering in real time
        val target = (inputSteer.coerceIn(-1f, 1f) * spec.maxTiltRad).toFloat()
        tilt += (target - tilt) * min(1f, dt * spec.steerResponse)
        f.tilt = tilt.toDouble()
        laneVx = sin(tilt) * 75f
        laneX += laneVx * dt
        if (laneX > World.LANE) { laneX = World.LANE; laneVx = 0f }
        if (laneX < -World.LANE) { laneX = -World.LANE; laneVx = 0f }

        // physics with time warp and sub-stepping
        if (!moonReached && !exploded) {
            warp = Physics.timeWarp(f.alt)
            val simDt = dt * warp
            val maxStep = if (f.alt < 160_000) 0.02 else min(30.0, 0.02 * warp)
            val n = ceil(simDt / maxStep).toInt().coerceIn(1, 300)
            val h = simDt / n
            repeat(n) {
                f.step(h, if (holding) 1.0 else 0.0, flightEvents)
                if (f.alt >= Physics.MOON_ARRIVAL_ALT) return@repeat
            }
        }
        for (e in flightEvents) handleFlightEvent(e, f)
        flightEvents.clear()

        // visual scroll speed: true speed when slow, compressed when fast
        val vEff = abs(f.vy) * warp
        val comp = if (vEff < 80) vEff else min(260.0, 80 + 9 * sqrt(vEff - 80))
        scroll = (if (f.vy >= 0) comp else -comp).toFloat()
        engineGlow = (f.throttle * (if (f.thrustNow > 0) 1.0 else 0.0)).toFloat()

        val rocketLen = remainingLength()
        val radius = if (f.boostersAttached) 1.9f else 1.1f
        world.update(dt, scroll, f.alt, laneX, rocketLen, radius, spec.magnetRadius, !ending && !exploded && f.liftedOff, worldEvents)
        for (e in worldEvents) handleWorldEvent(e, f)
        worldEvents.clear()

        // spent stages / boosters fall away
        val rel = (f.accel.toFloat() + 9.8f).coerceIn(8f, 40f)
        for (p in spent) { p.age += dt; p.vy -= rel * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.spin * dt }
        spent.removeAll { it.age > 7f }

        // camera shake
        val atm = Physics.density(f.alt) / 1.225
        val base = (engineGlow * (0.06 + 0.35 * atm * (if (f.alt < 400) 1.0 else 0.35)) + min(0.5, f.dynPressure / 60_000.0)).toFloat()
        shake = max(base, shake - dt * 2.2f)

        checkProgress(f)
        checkEnd(f, holding, dt)
    }

    private fun remainingLength(): Float {
        val f = flight ?: return spec.stages.sumOf { it.length.toDouble() }.toFloat() + 3f
        var l = 3f
        for (i in f.stage until spec.stages.size) l += spec.stages[i].length
        return l
    }

    private fun handleFlightEvent(e: FlightEvent, f: FlightState) {
        when (e) {
            is FlightEvent.StageSeparated -> {
                spent.add(SpentPart(e.index, laneX, -spec.stages[e.index].length, laneVx * 0.3f, -2f, tilt, (if (e.index % 2 == 0) 1 else -1) * 0.5f))
                fx.add(Fx.StageSep(e.index)); sfx(Sfx.STAGE); shake = max(shake, 0.6f)
                showBanner("Stage ${e.index + 1} separation")
            }
            FlightEvent.BoostersSeparated -> {
                for (b in 0 until spec.boosterCount) {
                    val side = if (b % 2 == 0) -1f else 1f
                    spent.add(SpentPart(-(b + 1), laneX, 0f, laneVx * 0.3f + side * 4f, -1f, tilt, side * 0.7f))
                }
                fx.add(Fx.BoosterSep); sfx(Sfx.STAGE); shake = max(shake, 0.5f)
                showBanner("Booster separation")
            }
            FlightEvent.Liftoff -> fx.add(Fx.Liftoff)
        }
    }

    private fun handleWorldEvent(e: WorldEvent, f: FlightState) {
        when (e) {
            is WorldEvent.Collected -> {
                fx.add(Fx.Collect(e.x, e.y, e.kind))
                when (e.kind) {
                    EntityKind.COIN -> { flightCoins += e.value; coinPickups++; sfx(Sfx.COIN) }
                    EntityKind.FUEL -> { f.addFuel(0.25); sfx(Sfx.FUEL); showBanner("⛽ +25% fuel") }
                    EntityKind.REPAIR -> { f.hull = min(spec.maxHull, f.hull + spec.maxHull * 0.3); sfx(Sfx.REPAIR); showBanner("🔧 Hull repaired") }
                    else -> {}
                }
            }
            is WorldEvent.Hit -> {
                f.hull -= e.damage
                hits++
                fx.add(Fx.Hit(e.x, e.y))
                shake = 1.2f
                sfx(Sfx.HIT)
                f.vy *= 0.97
            }
        }
    }

    private fun unlock(a: Achievement) {
        if (a in _save.value.achievements || a in newAch) return
        newAch.add(a)
        showBanner("🏆 ${a.title}")
        sfx(Sfx.ACHIEVE)
    }

    private fun checkProgress(f: FlightState) {
        while (zoneIdx < zones.size && f.alt >= zones[zoneIdx].first) { showBanner(zones[zoneIdx].second); zoneIdx++ }
        val a = f.maxAlt; val v = f.topSpeed
        if (a >= 1_000) unlock(Achievement.KM1)
        if (a >= 2_500) unlock(Achievement.CLOUDS)
        if (v >= 343) unlock(Achievement.SOUND_BARRIER)
        if (a >= 12_000) unlock(Achievement.STRATOSPHERE)
        if (a >= Physics.KARMAN) unlock(Achievement.KARMAN)
        if (a >= 400_000) unlock(Achievement.ORBIT)
        if (v >= 5_000) unlock(Achievement.HYPERSONIC)
        if (a >= 20_000_000) unlock(Achievement.DEEP_SPACE)
        if (coinPickups >= 50) unlock(Achievement.COLLECTOR)
        if (f.stagesSeparated >= 3) unlock(Achievement.STAGER)
    }

    private fun checkEnd(f: FlightState, holding: Boolean, dt: Float) {
        if (ending) {
            endTimer -= dt
            if (endTimer <= 0f) finishFlight()
            return
        }
        when {
            f.hull <= 0 -> {
                exploded = true; ending = true; endTimer = 2.4f; endReason = "Rocket destroyed"
                fx.add(Fx.Explode(laneX, remainingLength() * 0.5f)); sfx(Sfx.EXPLODE); shake = 2f
                showBanner("💥 Hull failure!")
            }
            f.alt >= Physics.MOON_ARRIVAL_ALT -> {
                moonReached = true
                moonLanded = f.stage < spec.stages.size - 1 || f.remainingFuel() >= 0.10 * spec.stages.last().fuelMass
                unlock(Achievement.MOON_REACHED)
                if (moonLanded) unlock(Achievement.MOON_LANDING)
                ending = true; endTimer = 4f
                endReason = if (moonLanded) "Landed on the Moon!" else "Crash-landed on the Moon — arrive with 10% fuel or a spare stage to land"
                showBanner(if (moonLanded) "🌕 THE EAGLE HAS LANDED" else "🌕 Lunar impact! (save fuel to land)")
                sfx(Sfx.WIN)
            }
            f.liftedOff && f.alt <= 0.0 && f.vy <= 0 -> { ending = true; endTimer = 0.8f; endReason = "Crashed back to Earth" }
            else -> {
                val canBurn = holding && f.hasThrustAvailable()
                if (f.liftedOff && f.vy < 0 && !canBurn) descendTimer += dt else descendTimer = 0f
                if (descendTimer > 2.2f) {
                    ending = true; endTimer = 1.4f
                    endReason = if (f.hasThrustAvailable()) "Engines cut — apogee reached" else "Out of fuel — apogee reached"
                    showBanner("Apogee: ${formatAlt(f.maxAlt)}")
                }
            }
        }
    }

    private fun finishFlight() {
        val f = flight ?: return
        val s0 = _save.value
        if (s0.flights == 0) unlock(Achievement.FIRST_FLIGHT)
        if (hits >= 5 && f.hull > 0) unlock(Achievement.TOUGH)
        val altCoins = Economy.altitudeCoins(f.maxAlt)
        val achCoins = newAch.sumOf { it.reward }
        val records = ArrayList<String>()
        if (f.maxAlt > s0.bestAltitude) records.add("Altitude")
        if (f.topSpeed > s0.bestSpeed) records.add("Speed")
        if (f.distance > s0.bestDistance) records.add("Distance")
        val r = FlightResult(
            f.maxAlt, f.topSpeed, f.distance, f.time, flightCoins, altCoins, achCoins, records, newAch.toList(),
            endReason.ifEmpty { "Flight over" }, moonReached, moonLanded, hits,
        )
        val total = r.total.toLong()
        commit(
            s0.copy(
                coins = s0.coins + total,
                bestAltitude = max(s0.bestAltitude, f.maxAlt),
                bestSpeed = max(s0.bestSpeed, f.topSpeed),
                bestDistance = max(s0.bestDistance, f.distance),
                flights = s0.flights + 1,
                achievements = s0.achievements + newAch,
                totalCoinsEarned = s0.totalCoinsEarned + total,
                moonLandings = s0.moonLandings + (if (moonLanded) 1 else 0),
            )
        )
        results = r
        phase = Phase.RESULTS
        ending = false
        holdingVisual = false
        engineGlow = 0f
        publishHud()
    }

    private fun publishHud() {
        val f = flight
        val inFlight = phase == Phase.FLIGHT && f != null
        _hud.value = HudState(
            phase = phase,
            paused = paused,
            countdown = ceil(countdown).toInt().coerceAtLeast(0),
            altitude = f?.alt ?: 0.0,
            speed = abs(f?.vy ?: 0.0),
            vSpeed = f?.vy ?: 0.0,
            fuel = (f?.currentFuelFraction() ?: 1.0).toFloat(),
            boosterFuel = if (f != null && f.boostersAttached) (f.boosterFuel / (spec.booster?.fuelMass ?: 1.0)).toFloat() else -1f,
            stage = f?.stage ?: 0,
            stageCount = spec.stages.size,
            coins = flightCoins,
            hull = ((f?.hull ?: spec.maxHull) / spec.maxHull).toFloat().coerceIn(0f, 1f),
            heat = (f?.heat ?: 0.0).toFloat(),
            zone = zoneName(f?.alt ?: 0.0),
            warp = warp.toFloat(),
            throttle = (f?.throttle ?: 0.0).toFloat(),
            moonProgress = if (f == null) 0f else (ln(1.0 + f.alt / 1000.0) / ln(1.0 + Physics.MOON_ARRIVAL_ALT / 1000.0)).toFloat().coerceIn(0f, 1f),
            banner = bannerText,
            bannerId = bannerId,
            hits = hits,
            showHint = inFlight && !everHeld && (f?.time ?: 0.0) < 12.0,
            ending = ending,
            mach = if (f == null) 0f else (abs(f.vy) / Physics.speedOfSound(f.alt)).toFloat(),
            results = results,
            moonBound = inFlight && f != null && f.alt > 160_000 && f.vy > 0 &&
                0.5 * f.vy * f.vy - Physics.MU / (Physics.R_EARTH + f.alt) >= -Physics.MU / (Physics.R_EARTH + Physics.MOON_ARRIVAL_ALT),
        )
    }
}

fun formatAlt(m: Double): String = when {
    m < 1_000 -> "${m.toInt()} m"
    m < 100_000 -> "%.2f km".format(m / 1000)
    else -> "%,d km".format((m / 1000).toLong())
}

fun formatSpeed(v: Double): String = if (v < 1000) "${v.toInt()} m/s" else "%.2f km/s".format(v / 1000)
