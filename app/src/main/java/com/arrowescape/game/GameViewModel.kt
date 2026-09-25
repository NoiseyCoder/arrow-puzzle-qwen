package com.arrowescape.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** High-level screen the host Activity renders. */
enum class Screen { HOME, GAME, LEVELS, SETTINGS }

/** Phase of the in-game flow. */
enum class GamePhase { LOADING, PLAYING, OUT_OF_HEARTS, WIN_SEQUENCE, NEXT_LEVEL_LOADING, NO_MORE_LEVELS }

/**
 * Single immutable snapshot of everything the UI draws. The Canvas reads [activeIds],
 * [crimsonIds], [hintId] and the effect seeds from here; per-frame animation values live in
 * BoardCanvas (driven by [clearEvents]/[blockedEvents]) so a tap never forces a recomposition
 * of the whole tree.
 */
data class GameState(
    val screen: Screen = Screen.HOME,
    val phase: GamePhase = GamePhase.LOADING,
    val levelNumber: Int = 1,
    val width: Int = 0,
    val height: Int = 0,
    val arrows: List<Arrow> = emptyList(),
    /** Ids of arrows still on the board. Removed instantly on a free tap (rapid tapping works). */
    val activeIds: Set<Int> = emptySet(),
    /** Cosmetic "known blocked" markers — crimson until cleared by Try again / new attempt. */
    val crimsonIds: Set<Int> = emptySet(),
    val hintId: Int? = null,
    val hearts: Int = 3,
    val totalArrows: Int = 0,
    val clearedCount: Int get() = totalArrows - activeIds.size,
    val progress: Float get() = if (totalArrows == 0) 0f else clearedCount.toFloat() / totalArrows,
    val hintsRemaining: Int = 3,
    val adAvailable: Boolean = false,
    val tutorialText: String? = null,
    val celebrationSkipsDotsWave: Boolean = false,
    val theme: GameTheme.ThemeName = GameTheme.ThemeName.NIGHT,
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val highestUnlocked: Int = 1,
    val starsByLevel: Map<Int, Int> = emptyMap(),
    val silhouettes: Set<String> = emptySet(),
    val streakDays: Int = 0,
    val dailyClaimedToday: Boolean = true,
)

/** One-shot side effects (toast/snack bar style), consumed via [GameViewModel.uiEvents]. */
sealed interface UiEvent {
    data object OutOfHearts : UiEvent
    data class ArrowCleared(val arrowId: Int, val headX: Float, val headY: Float, val dir: Direction, val pitchStep: Int) : UiEvent
    data class ArrowBlocked(val arrowId: Int, val blockerId: Int?) : UiEvent
    data object LevelWon : UiEvent
    data class HintPlaced(val arrowId: Int) : UiEvent
    data object NoHintsLeft : UiEvent
    data object AdUnavailable : UiEvent
    data object DailyRewardClaimed : UiEvent
    data class Toast(val text: String) : UiEvent
}

class GameViewModel(
    private val levels: LevelRepository,
    private val progressStore: ProgressStore,
    private val adProvider: RewardedAdProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    /** Hearts lost during the current attempt (for star rating). */
    private var heartsLostThisAttempt = 0
    private var lastClearAtMs = 0L
    private var pitchStep = 0

    init {
        viewModelScope.launch {
            val p = progressStore.snapshot()
            _state.value = _state.value.copy(
                hintsRemaining = p.hints,
                theme = p.theme,
                soundEnabled = p.soundEnabled,
                hapticsEnabled = p.hapticsEnabled,
                highestUnlocked = p.highestUnlocked,
                starsByLevel = p.starsByLevel,
                silhouettes = p.completedSilhouettes,
                streakDays = p.streakDays,
                dailyClaimedToday = p.dailyClaimedDay == todayKey(),
            )
        }
    }

    private fun todayKey(): String {
        val c = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    // ------------------------------------------------------------ navigation

    fun goHome() { _state.value = _state.value.copy(screen = Screen.HOME, phase = GamePhase.PLAYING) }
    fun openLevels() { _state.value = _state.value.copy(screen = Screen.LEVELS) }
    fun openSettings() { _state.value = _state.value.copy(screen = Screen.SETTINGS) }

    /** Called by Home "Play" and after settings/back flows: enters the stored current level. */
    fun startCurrentLevel() {
        viewModelScope.launch {
            val p = progressStore.snapshot()
            loadLevel(p.currentLevel)
            _state.value = _state.value.copy(screen = Screen.GAME)
        }
    }

    fun playLevel(n: Int) {
        viewModelScope.launch {
            loadLevel(n)
            _state.value = _state.value.copy(screen = Screen.GAME)
        }
    }

    // ------------------------------------------------------------ level loading

    private suspend fun loadLevel(number: Int) {
        _state.value = _state.value.copy(phase = GamePhase.LOADING, levelNumber = number)
        when (val res = levels.load(number)) {
            is LevelLoadResult.Level -> {
                val lv = res.level
                heartsLostThisAttempt = 0
                _state.value = _state.value.copy(
                    phase = GamePhase.PLAYING,
                    levelNumber = lv.number,
                    width = lv.width,
                    height = lv.height,
                    arrows = lv.arrows,
                    activeIds = lv.arrows.mapTo(HashSet()) { it.id },
                    crimsonIds = emptySet(),
                    hintId = null,
                    hearts = 3,
                    totalArrows = lv.totalArrows,
                    tutorialText = lv.tutorialText,
                    celebrationSkipsDotsWave = Random(lv.number * 7919 + 13).nextBoolean(),
                )
            }
            is LevelLoadResult.NotShipped -> {
                _state.value = _state.value.copy(phase = GamePhase.NO_MORE_LEVELS, arrows = emptyList(), activeIds = emptySet(), totalArrows = 0)
            }
        }
    }

    // ------------------------------------------------------------ gameplay input

    /** Tap on an arrow id from BoardCanvas hit-testing. Logic resolves instantly. */
    fun onArrowTapped(arrowId: Int) {
        val s = _state.value
        if (s.phase != GamePhase.PLAYING || arrowId !in s.activeIds) return
        val level = Level(s.levelNumber, s.width, s.height, s.arrows)
        val arrow = s.arrows.first { it.id == arrowId }
        val free = GameEngine.isFree(level, arrowId, s.activeIds)
        if (free) {
            val now = System.currentTimeMillis()
            pitchStep = if (now - lastClearAtMs < 900) (pitchStep + 1).coerceAtMost(7) else 0
            lastClearAtMs = now
            val newActive = s.activeIds - arrowId
            val won = newActive.isEmpty()
            _state.value = s.copy(
                activeIds = newActive,
                crimsonIds = s.crimsonIds, // markers persist for the rest of this attempt
                hintId = if (s.hintId == arrowId) null else s.hintId,
                phase = if (won) GamePhase.WIN_SEQUENCE else GamePhase.PLAYING,
            )
            events.trySend(UiEvent.ArrowCleared(arrowId, arrow.head.x.toFloat(), arrow.head.y.toFloat(), arrow.headDirection, pitchStep))
            if (won) onLevelWon()
        } else {
            val occ = GameEngine.Occupancy.build(level, s.activeIds)
            val d = arrow.headDirection
            var x = arrow.head.x + d.dx; var y = arrow.head.y + d.dy
            var blockerId: Int? = null
            while (x in 0 until s.width && y in 0 until s.height) {
                val o = occ.ownerAt(x, y)
                if (o != -1 && o != arrowId) { blockerId = o; break }
                x += d.dx; y += d.dy
            }
            val hearts = s.hearts - 1
            heartsLostThisAttempt++
            _state.value = s.copy(
                hearts = hearts.coerceAtLeast(0),
                crimsonIds = s.crimsonIds + arrowId,
                phase = if (hearts <= 0) GamePhase.OUT_OF_HEARTS else GamePhase.PLAYING,
            )
            events.trySend(UiEvent.ArrowBlocked(arrowId, blockerId))
            if (hearts <= 0) events.trySend(UiEvent.OutOfHearts)
        }
    }

    private fun onLevelWon() {
        events.trySend(UiEvent.LevelWon)
        viewModelScope.launch {
            val s = _state.value
            progressStore.recordCompletion(s.levelNumber, heartsLostThisAttempt, silhouetteFor(s.levelNumber))
            val p = progressStore.snapshot()
            _state.value = _state.value.copy(
                hintsRemaining = p.hints,
                highestUnlocked = p.highestUnlocked,
                starsByLevel = p.starsByLevel,
                silhouettes = p.completedSilhouettes,
                streakDays = p.streakDays,
                dailyClaimedToday = p.dailyClaimedDay == todayKey(),
            )
        }
    }

    /** Player tapped to skip the celebration (BoardCanvas/GameScreen enforce the ~300 ms floor). */
    fun advanceAfterWin() {
        val s = _state.value
        if (s.phase != GamePhase.WIN_SEQUENCE) return
        viewModelScope.launch {
            _state.value = _state.value.copy(phase = GamePhase.NEXT_LEVEL_LOADING)
            delay(500) // brief "Level N+1" spinner moment with the dotted ring
            loadLevel(s.levelNumber + 1)
        }
    }

    // ------------------------------------------------------------ out-of-hearts modal

    /** Full reset of the current attempt: hearts, removed arrows, crimson markers, hint. Layout unchanged. */
    fun tryAgain() {
        val s = _state.value
        if (s.phase != GamePhase.OUT_OF_HEARTS) return
        heartsLostThisAttempt = 0
        _state.value = s.copy(
            phase = GamePhase.PLAYING,
            hearts = 3,
            activeIds = s.arrows.mapTo(HashSet()) { it.id },
            crimsonIds = emptySet(),
            hintId = null,
        )
    }

    /** Rewarded-ad rescue: refills hearts WITHOUT resetting the attempt (progress preserved). */
    fun saveMeWithAd() {
        val s = _state.value
        if (s.phase != GamePhase.OUT_OF_HEARTS) return
        viewModelScope.launch {
            when (adProvider.show()) {
                AdResult.SUCCESS -> _state.value = _state.value.copy(phase = GamePhase.PLAYING, hearts = 3)
                else -> events.trySend(UiEvent.AdUnavailable)
            }
        }
    }

    // ------------------------------------------------------------ hints

    fun requestHint() {
        val s = _state.value
        if (s.phase != GamePhase.PLAYING) return
        viewModelScope.launch {
            if (!progressStore.consumeHint()) {
                events.trySend(UiEvent.NoHintsLeft)
                return@launch
            }
            val level = Level(s.levelNumber, s.width, s.height, s.arrows)
            val free = GameEngine.findFreeArrow(level, s.activeIds, avoid = s.crimsonIds)
            val p = progressStore.snapshot()
            _state.value = _state.value.copy(hintsRemaining = p.hints, hintId = free?.id)
            if (free != null) events.trySend(UiEvent.HintPlaced(free.id))
        }
    }

    /** Optional "watch video for a hint" badge — stubbed, degrades gracefully. */
    fun hintFromAd() {
        viewModelScope.launch {
            when (adProvider.show()) {
                AdResult.SUCCESS -> {
                    progressStore.addHints(1)
                    val p = progressStore.snapshot()
                    _state.value = _state.value.copy(hintsRemaining = p.hints)
                    events.trySend(UiEvent.Toast("+1 hint"))
                }
                else -> events.trySend(UiEvent.AdUnavailable)
            }
        }
    }

    // ------------------------------------------------------------ settings & rewards

    fun setSound(enabled: Boolean) {
        viewModelScope.launch {
            progressStore.setSound(enabled)
            _state.value = _state.value.copy(soundEnabled = enabled)
        }
    }

    fun setHaptics(enabled: Boolean) {
        viewModelScope.launch {
            progressStore.setHaptics(enabled)
            _state.value = _state.value.copy(hapticsEnabled = enabled)
        }
    }

    fun setTheme(name: GameTheme.ThemeName) {
        viewModelScope.launch {
            progressStore.setTheme(name)
            val p = progressStore.snapshot()
            _state.value = _state.value.copy(theme = p.theme)
        }
    }

    fun claimDailyReward() {
        viewModelScope.launch {
            if (progressStore.claimDailyReward()) {
                val p = progressStore.snapshot()
                _state.value = _state.value.copy(
                    hintsRemaining = p.hints,
                    streakDays = p.streakDays,
                    dailyClaimedToday = true,
                )
                events.trySend(UiEvent.DailyRewardClaimed)
            } else {
                events.trySend(UiEvent.Toast("Already claimed today"))
            }
        }
    }

    companion object {
        const val MAX_HEARTS = 3
        /** Silhouette collected for the Gallery on milestone levels (every 25th). */
        fun silhouetteFor(levelNumber: Int): String? =
            if (levelNumber % 25 == 0) LevelShapes.shapeFor(levelNumber) else null
    }
}
