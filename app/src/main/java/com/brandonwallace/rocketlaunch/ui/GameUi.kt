package com.brandonwallace.rocketlaunch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.brandonwallace.rocketlaunch.game.Achievement
import com.brandonwallace.rocketlaunch.game.FlightResult
import com.brandonwallace.rocketlaunch.game.GameEngine
import com.brandonwallace.rocketlaunch.game.HudState
import com.brandonwallace.rocketlaunch.game.Phase
import com.brandonwallace.rocketlaunch.game.Physics
import com.brandonwallace.rocketlaunch.game.RocketSpec
import com.brandonwallace.rocketlaunch.game.SaveData
import com.brandonwallace.rocketlaunch.game.SimResult
import com.brandonwallace.rocketlaunch.game.UpgradeType
import com.brandonwallace.rocketlaunch.game.formatAlt
import com.brandonwallace.rocketlaunch.game.formatSpeed
import com.brandonwallace.rocketlaunch.game.simulateAutopilot
import com.brandonwallace.rocketlaunch.gl.GameView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ln

val Orange = Color(0xFFFF8A1F)
val Gold = Color(0xFFFFD34D)
val Cyan = Color(0xFF4FD1FF)
val Panel = Color(0xE60A1130)
val PanelLight = Color(0xCC16204A)
val Good = Color(0xFF4ADE80)
val Bad = Color(0xFFFF4D4D)

private val scheme = darkColorScheme(primary = Orange, onPrimary = Color(0xFF2A1200), secondary = Cyan, surface = Color(0xFF0E1638), background = Color(0xFF050814))

@Composable
fun GameUi(engine: GameEngine, glView: GameView) {
    val hud by engine.hud.collectAsState()
    val save by engine.save.collectAsState()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(hud.hits) { if (hud.hits > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    BackHandler(enabled = hud.phase != Phase.MENU) {
        when (hud.phase) {
            Phase.HANGAR -> engine.openMenu()
            Phase.FLIGHT, Phase.COUNTDOWN -> engine.setPaused(!hud.paused)
            Phase.RESULTS -> engine.openHangar()
            else -> {}
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
            when (hud.phase) {
                Phase.MENU -> MenuScreen(engine, save)
                Phase.HANGAR -> HangarScreen(engine, save)
                Phase.COUNTDOWN -> CountdownOverlay(engine, hud)
                Phase.FLIGHT -> { FlightTouchLayer(engine, save.tiltControl); FlightHud(engine, hud) }
                Phase.RESULTS -> hud.results?.let { ResultsScreen(engine, it, save) }
            }
            if (hud.phase == Phase.FLIGHT || hud.phase == Phase.COUNTDOWN) Banner(hud)
            if (hud.paused) PauseOverlay(engine)
        }
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, color: Color = Orange, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF1A0A00)),
    ) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp) }
}

@Composable
fun CoinChip(coins: Long, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Panel).border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🪙", fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Text("%,d".format(coins), color = Gold, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
fun MoonProgress(progress: Float, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("🌍", fontSize = 16.sp)
        Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x55FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Brush.horizontalGradient(listOf(Cyan, Gold))))
        }
        Text("🌕", fontSize = 16.sp)
    }
}

fun moonFraction(alt: Double): Float = (ln(1.0 + alt / 1000.0) / ln(1.0 + Physics.MOON_ARRIVAL_ALT / 1000.0)).toFloat().coerceIn(0f, 1f)

// ---------------------------------------------------------------- menu

@Composable
fun MenuScreen(engine: GameEngine, save: SaveData) {
    var showAch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xAA050814), Color.Transparent, Color(0xCC050814)))).safeDrawingPadding().padding(22.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ROCKET", style = TextStyle(brush = Brush.verticalGradient(listOf(Color.White, Gold, Orange))), fontSize = 58.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            Text("ASCENT", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("Launch · Upgrade · Reach the Moon", color = Cyan, fontSize = 14.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { StatCell("Best altitude", formatAlt(save.bestAltitude), Modifier.weight(1f)); StatCell("Flights", "${save.flights}", Modifier.weight(1f)) }
                Row { StatCell("Top speed", formatSpeed(save.bestSpeed), Modifier.weight(1f)); StatCell("Moon landings", "${save.moonLandings}", Modifier.weight(1f)) }
                Spacer(Modifier.height(4.dp))
                MoonProgress(moonFraction(save.bestAltitude))
            }
            CoinChip(save.coins)
            BigButton(if (save.flights == 0) "START" else "PLAY", Modifier.fillMaxWidth()) { engine.openHangar() }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showAch = true }, modifier = Modifier.weight(1f)) { Text("🏆 ${save.achievements.size}/${Achievement.entries.size}") }
                OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.weight(1f)) { Text("⚙️ Settings") }
            }
        }
    }
    if (showAch) AchievementsDialog(save) { showAch = false }
    if (showSettings) SettingsDialog(engine, save) { showSettings = false }
}

@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), color = Color(0xFF8FA0D8), fontSize = 10.sp, letterSpacing = 1.sp)
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AchievementsDialog(save: SaveData, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Achievements") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Achievement.entries.forEach { a ->
                    val got = a in save.achievements
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (got) "🏆" else "🔒", fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.title, fontWeight = FontWeight.Bold, color = if (got) Gold else Color.White.copy(alpha = 0.7f))
                            Text(a.desc, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                        }
                        Text("+${a.reward}", color = Gold.copy(alpha = if (got) 1f else 0.5f), fontSize = 12.sp)
                    }
                }
            }
        }
    )
}

@Composable
fun SettingsDialog(engine: GameEngine, save: SaveData, onDismiss: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sound", Modifier.weight(1f)); Switch(checked = save.soundOn, onCheckedChange = { engine.setSound(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Tilt to steer"); Text("Tilt the phone instead of dragging", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f)) }
                    Switch(checked = save.tiltControl, onCheckedChange = { engine.setTilt(it) })
                }
                if (!confirmReset) OutlinedButton(onClick = { confirmReset = true }) { Text("Reset progress…", color = Bad) }
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Erase all progress?", Modifier.weight(1f), color = Bad)
                    TextButton(onClick = { engine.resetProgress(); confirmReset = false; onDismiss() }) { Text("Erase", color = Bad) }
                }
            }
        }
    )
}

// ---------------------------------------------------------------- hangar / shop

@Composable
fun HangarScreen(engine: GameEngine, save: SaveData) {
    val spec = remember(save.levels) { RocketSpec(save.levels) }
    val prediction by produceState<SimResult?>(initialValue = null, save.levels) {
        value = withContext(Dispatchers.Default) { simulateAutopilot(RocketSpec(save.levels)) }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { engine.openMenu() }) { Text("‹ Menu", color = Color.White, fontSize = 16.sp) }
            Spacer(Modifier.weight(1f))
            Text("HANGAR", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            Spacer(Modifier.weight(1f))
            CoinChip(save.coins)
        }
        Spacer(Modifier.weight(0.40f))
        Column(
            Modifier.weight(0.60f).fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelLight).padding(10.dp)) {
                StatCell("Δv", "%,d m/s".format(spec.idealDeltaV().toInt()), Modifier.weight(1f))
                StatCell("TWR", "%.2f".format(spec.liftoffTwr()), Modifier.weight(0.7f))
                StatCell("Stages", "${spec.stages.size}${if (spec.boosterCount > 0) "+${spec.boosterCount}B" else ""}", Modifier.weight(0.8f))
                val p = prediction
                StatCell("Autopilot", when { p == null -> "…"; p.reachedMoon -> "🌕 Moon!"; else -> formatAlt(p.maxAlt) }, Modifier.weight(1.1f))
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(UpgradeType.entries.toList()) { t -> UpgradeCard(engine, save, spec, t) }
            }
            Spacer(Modifier.height(8.dp))
            BigButton("🚀  LAUNCH", Modifier.fillMaxWidth()) { engine.launch() }
        }
    }
}

@Composable
fun UpgradeCard(engine: GameEngine, save: SaveData, spec: RocketSpec, t: UpgradeType) {
    val lvl = save.level(t)
    val cost = save.nextCost(t)
    val affordable = save.canBuy(t)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PanelLight)
            .border(1.dp, if (affordable) Orange.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(16.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(t.icon, fontSize = 28.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.Center)
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(t.maxLevel) { i -> Box(Modifier.size(width = 10.dp, height = 6.dp).clip(RoundedCornerShape(2.dp)).background(if (i < lvl) Orange else Color(0x44FFFFFF))) }
                }
            }
            Text(t.blurb, color = Color(0xFF8FA0D8), fontSize = 11.sp)
            Text(
                if (cost == null) spec.describe(t, lvl) else "${spec.describe(t, lvl)}  →  ${spec.describe(t, lvl + 1)}",
                color = Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace
            )
        }
        if (cost == null) Text("MAX", color = Gold, fontWeight = FontWeight.Black, modifier = Modifier.padding(end = 8.dp))
        else Button(
            onClick = { engine.buy(t) }, enabled = affordable, shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF2A1A00)),
        ) { Text("🪙 %,d".format(cost), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

// ---------------------------------------------------------------- countdown + flight

@Composable
fun CountdownOverlay(engine: GameEngine, hud: HudState) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        PauseButton(engine, Modifier.align(Alignment.TopEnd).padding(12.dp))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (hud.countdown > 0) "${hud.countdown}" else "IGNITION", color = Color.White, fontSize = if (hud.countdown > 0) 110.sp else 48.sp, fontWeight = FontWeight.Black)
            Text("T-minus", color = Cyan, letterSpacing = 4.sp)
        }
        Text(
            "HOLD anywhere to burn · DRAG left/right to steer", color = Color.White, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp)
        )
    }
}

@Composable
fun FlightTouchLayer(engine: GameEngine, tilt: Boolean) {
    Box(
        Modifier.fillMaxSize().pointerInput(tilt) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                engine.inputThrottle = true
                val startX = down.position.x
                do {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!tilt) engine.inputSteer = ((ch.position.x - startX) / (size.width * 0.22f)).coerceIn(-1f, 1f)
                    ch.consume()
                } while (ch.pressed)
                engine.inputThrottle = false
                if (!tilt) engine.inputSteer = 0f
            }
        }
    )
}

@Composable
fun PauseButton(engine: GameEngine, modifier: Modifier = Modifier) {
    Box(modifier.size(46.dp).clip(CircleShape).background(Panel).clickable { engine.setPaused(true) }, contentAlignment = Alignment.Center) {
        Text("II", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}

@Composable
fun VBar(label: String, value: Float, color: Color, modifier: Modifier = Modifier, flash: Boolean = false) {
    val pulse by rememberInfiniteTransition(label = "bar").animateFloat(0.4f, 1f, infiniteRepeatable(tween(300), RepeatMode.Reverse), label = "p")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(16.dp).height(150.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x66000000)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(8.dp))) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f))
                    .background(color.copy(alpha = if (flash) pulse else 1f))
            )
        }
        Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun FlightHud(engine: GameEngine, hud: HudState) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Column(Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(16.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(formatAlt(hud.altitude), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (hud.vSpeed < 0) "↓ " else "↑ ") + formatSpeed(hud.speed), color = Cyan, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                if (hud.mach >= 0.8f && hud.altitude < 60_000) Text("  M%.1f".format(hud.mach), color = Gold, fontSize = 13.sp)
            }
            Text(hud.zone, color = Color(0xFF8FA0D8), fontSize = 11.sp)
            if (hud.warp > 1.5f) Text("⏩ time warp ×%,d".format(hud.warp.toInt()), color = Gold, fontSize = 11.sp)
        }
        Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
            CoinChip(hud.coins.toLong())
            Spacer(Modifier.width(8.dp))
            PauseButton(engine)
        }
        // left gauges
        Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VBar("FUEL", hud.fuel, if (hud.fuel > 0.25f) Orange else Bad, flash = hud.fuel in 0.001f..0.15f)
            if (hud.boosterFuel >= 0f) VBar("BOOST", hud.boosterFuel, Gold)
        }
        // right gauges
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VBar("HEAT", (hud.heat / 1.3f), if (hud.heat > 1f) Bad else Color(0xFFFF6A3D), flash = hud.heat > 1f)
            VBar("HULL", hud.hull, if (hud.hull > 0.35f) Good else Bad, flash = hud.hull < 0.25f)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (hud.moonBound && !hud.ending) {
                Text(if (hud.throttle > 0.05f) "🌕 On course for the Moon — release to save landing fuel" else "🌕 Coasting to the Moon",
                    color = Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(10.dp))
            }
            if (hud.showHint) {
                val a by rememberInfiniteTransition(label = "hint").animateFloat(0.5f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
                Text("HOLD to burn · DRAG to steer", color = Color.White.copy(alpha = a), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 14.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp))
            }
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hud.throttle > 0.05f) Text("🔥 ", fontSize = 14.sp)
                for (i in 0 until hud.stageCount) {
                    val active = i == hud.stage
                    val spentStage = i < hud.stage
                    Box(
                        Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(8.dp))
                            .background(when { active -> Orange; spentStage -> Color(0x33FFFFFF); else -> Color(0x66FFFFFF) })
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) { Text("S${i + 1}", color = if (active) Color(0xFF1A0A00) else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
                Spacer(Modifier.width(10.dp))
                MoonProgress(hud.moonProgress, Modifier.width(150.dp))
            }
        }
    }
}

@Composable
fun BoxScope.BannerHost(content: @Composable () -> Unit) { content() }

@Composable
fun Banner(hud: HudState) {
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(hud.bannerId) {
        if (hud.bannerId == 0 || hud.banner.isEmpty()) return@LaunchedEffect
        text = hud.banner; visible = true
        delay(2300)
        visible = false
    }
    Box(Modifier.fillMaxSize().padding(top = 150.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.7f), exit = fadeOut()) {
            Text(
                text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xDD1B2A6B), Color(0xDD3B1B6B)))).border(1.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
fun PauseOverlay(engine: GameEngine) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(Modifier.width(280.dp).clip(RoundedCornerShape(24.dp)).background(Panel).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PAUSED", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            BigButton("RESUME", Modifier.fillMaxWidth()) { engine.setPaused(false) }
            OutlinedButton(onClick = { engine.abortFlight() }, modifier = Modifier.fillMaxWidth()) { Text("Abort flight", color = Bad) }
        }
    }
}

// ---------------------------------------------------------------- results

@Composable
fun ResultsScreen(engine: GameEngine, r: FlightResult, save: SaveData) {
    Box(Modifier.fillMaxSize().background(Color(0x88000000)).safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(true, enter = fadeIn() + slideInVertically { it / 3 }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Panel).border(1.dp, Gold.copy(alpha = 0.4f), RoundedCornerShape(26.dp))
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(when { r.moonLanded -> "🌕"; r.moonReached -> "🌑"; r.reason.contains("destroyed") -> "💥"; else -> "🚀" }, fontSize = 52.sp)
                Text(r.reason, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                if (r.moonLanded) Text("You made it! Rocket Ascent is beaten — keep flying for records and coins.", color = Gold, textAlign = TextAlign.Center, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                ResultRow("Max altitude", formatAlt(r.maxAlt), "Altitude" in r.records)
                ResultRow("Top speed", formatSpeed(r.topSpeed), "Speed" in r.records)
                ResultRow("Distance flown", formatAlt(r.distance), "Distance" in r.records)
                ResultRow("Flight time", "%.0f s (sim)".format(r.time), false)
                ResultRow("Hazards hit", "${r.hits}", false)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                ResultRow("Coins collected", "+${r.pickupCoins}", false, Gold)
                ResultRow("Altitude bonus", "+${r.altitudeCoins}", false, Gold)
                if (r.achievementCoins > 0) ResultRow("Achievements", "+${r.achievementCoins}", false, Gold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("TOTAL", color = Color.White, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Text("🪙 +%,d".format(r.total), color = Gold, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
                r.achievements.forEach { a ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x332B6DE0)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🏆", fontSize = 20.sp); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { Text(a.title, color = Gold, fontWeight = FontWeight.Bold); Text(a.desc, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp) }
                        Text("+${a.reward}", color = Gold)
                    }
                }
                Text("Balance: 🪙 %,d".format(save.coins), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("UPGRADE", Modifier.weight(1f), color = Cyan) { engine.openHangar() }
                    BigButton("LAUNCH", Modifier.weight(1f)) { engine.launch() }
                }
            }
        }
    }
}

@Composable
fun ResultRow(label: String, value: String, record: Boolean, color: Color = Color.White) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFB8C4EE), fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (record) Text("NEW RECORD ", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x44FFD34D)).padding(horizontal = 6.dp, vertical = 2.dp))
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}
