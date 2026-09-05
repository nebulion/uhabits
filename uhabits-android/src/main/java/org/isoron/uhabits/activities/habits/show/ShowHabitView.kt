/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.habits.show

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.tabs.SecondaryTabRowMMD
import com.mudita.mmd.components.tabs.TabMMD
import com.mudita.mmd.components.text.TextMMD
import org.isoron.uhabits.R
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitState
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardState
import org.isoron.uhabits.databinding.ShowHabitBinding

/**
 * The habit screen: a fixed summary over a tabbed detail.
 *
 * It used to be nine cards stacked in one `LazyColumnMMD`, which was wrong in
 * two ways that only showed up on the device. Nothing read at a glance -- the
 * numbers you actually check daily were the third card down -- and the
 * scrollbar visibly changed size as you dragged. That second one is not a bug
 * in this app: `LazyColumnMMD` sizes its thumb as `visibleCount /
 * totalItemsCount`, which is correct for a list of like-sized rows and
 * meaningless for nine cards ranging from a two-line subtitle to a full-height
 * chart. It was a list component doing a document's job.
 *
 * So the summary is hoisted out and pinned: four figures, always visible,
 * never scrolled past. Everything else is grouped behind `SecondaryTabRowMMD`
 * -- MMD's own documentation describes the secondary row as "used within a
 * content area to further separate related content", which is exactly this,
 * and its 3dp indicator is already the weight of every rule in the app.
 * Nothing was dropped; it was redistributed so that no single view has to be
 * tall enough to need scrolling.
 *
 * `TabsMMD` is also one of the few MMD components whose defaults touch none of
 * the six roles `eInkColorScheme` leaves `Color.Unspecified`, so unlike
 * `TextFieldMMD` and `TimeInputMMD` it needs no colour override to render a
 * fill at all. Its own default `divider` is a raw Material3 `HorizontalDivider`
 * though -- the same shortcut behind `TopAppBarMMD.showDivider` -- so it is
 * suppressed and the rule drawn explicitly.
 *
 * The card *contents* are still Views, hosted through `AndroidView`, because
 * the charts inside them draw through their own `onDraw` and have to be ported
 * onto the core `Canvas` interface first. Each panel therefore still uses
 * `LazyColumnMMD` for the cards it holds -- but with `isScrollbarVisible =
 * false`, since a panel holding one or two cards has nothing a thumb could
 * usefully describe. As each chart is ported, its panel becomes plain Compose
 * and the `AndroidView` goes.
 */
class ShowHabitView(context: Context) : FrameLayout(context) {
    private val binding = ShowHabitBinding.inflate(LayoutInflater.from(context))

    // Header inputs, held as Compose state so a refresh recomposes the bar
    // rather than re-setting the ComposeView's content.
    private var title by mutableStateOf("")
    private var canArchive by mutableStateOf(false)
    private var canUnarchive by mutableStateOf(false)
    /**
     * Keys of cards that are not currently shown.
     *
     * `SubtitleCardView`, `NotesCardView` and `BarCardView` hide themselves
     * with `visibility = GONE` when they have nothing to show, and overview
     * and target are mutually exclusive. A GONE view inside a lazy item is
     * still an item: it still counts towards `totalItemsCount`, which sizes
     * the scrollbar thumb and drives the paging, and it still gets a divider
     * drawn after it -- which is where the doubled rules came from. So the
     * views' own visibility is read back after `setState` and used to drop
     * them from the list entirely.
     */
    private var hiddenKeys by mutableStateOf(emptySet<String>())

    // The pinned summary. These are read straight off the state the cards are
    // already given, so nothing new is computed for them.
    private var statScore by mutableStateOf(0)
    private var statBest by mutableStateOf(0)
    private var statMonth by mutableStateOf(0)
    private var statTotal by mutableStateOf(0L)

    /** Which detail panel is showing. */
    private var tab by mutableStateOf(0)

    /**
     * The calendar's data, held for Compose rather than pushed into a View.
     * This is the one card that never needed the chart port to stop being a
     * View -- its state is already a plain list of squares.
     */
    private var history by mutableStateOf<HistoryCardState?>(null)

    private val composeView = ComposeView(context)

    /** One card body, keyed so the lazy list can identify it across scrolls. */
    private class Card(val key: String, val view: View)

    private val cards: List<Card>

    init {
        cards = listOf(
            Card("subtitle", binding.subtitleCard),
            Card("notes", binding.notesCard),
            Card("overview", binding.overviewCard),
            Card("target", binding.targetCard),
            Card("score", binding.scoreCard),
            Card("bar", binding.barCard),
            Card("history", binding.historyCard),
            Card("streak", binding.streakCard),
            Card("frequency", binding.frequencyCard),
        )
        // The inflated LinearLayout was only ever a holder: the cards are laid
        // out by the lazy list now. Their own backgrounds go with it -- the
        // page is white paper, and `card_eink_background` was this fork's
        // hand-drawn approximation of a card surface.
        binding.linearLayout.removeAllViews()
        cards.forEach { it.view.background = null }
        addView(composeView)
    }

    fun setTopBar(
        actions: ShowHabitActions,
        /** Called once per page of scrolling, for the E Ink flash counter. */
        onScrolled: () -> Unit = {},
    ) {
        composeView.setContent {
            ThemeMMD {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    ShowHabitTopBarMMD(
                        title = title,
                        canArchive = canArchive,
                        canUnarchive = canUnarchive,
                        actions = actions,
                    )

                    StatBand()

                    val panels = panels()
                    val selected = tab.coerceIn(0, panels.lastIndex)

                    SecondaryTabRowMMD(
                        selectedTabIndex = selected,
                        // Suppressed: the row's own default divider is a raw
                        // Material3 HorizontalDivider at 2dp, the same
                        // shortcut that stops TopAppBarMMD's showDivider from
                        // drawing the 3dp rule. The real one is drawn below.
                        divider = {},
                    ) {
                        panels.forEachIndexed { index, panel ->
                            TabMMD(
                                selected = index == selected,
                                onClick = { tab = index },
                                text = {
                                    TextMMD(
                                        text = panel.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        textAlign = TextAlign.Center,
                                    )
                                },
                            )
                        }
                    }
                    HorizontalDividerMMD()

                    if (selected == CALENDAR_TAB) {
                        val state = history
                        if (state != null) {
                            HabitCalendarMMD(
                                state = state,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                        } else {
                            Box(Modifier.weight(1f))
                        }
                    } else {
                        Panel(
                            cards = panels[selected].cards,
                            onScrolled = onScrolled,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    /**
     * The four figures, pinned above the tabs.
     *
     * These are what someone opens this screen to read, so they are the one
     * thing that never scrolls and never hides behind a tab. `headlineLarge`
     * is MMD's largest role at 28sp; the captions are `labelSmall` at 14sp,
     * which is the floor and deliberately at it -- they are labels for the
     * numbers, not text anyone reads twice.
     */
    @Composable
    private fun StatBand() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            Stat(statScore.toString() + "%", stringOrEmpty(R.string.score))
            Stat(statBest.toString(), stringOrEmpty(R.string.best_streak_short))
            Stat(formatDelta(statMonth), stringOrEmpty(R.string.month))
            Stat(statTotal.toString(), stringOrEmpty(R.string.total))
        }
        HorizontalDividerMMD(thickness = 1.dp)
    }

    @Composable
    private fun RowScope.Stat(value: String, label: String) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TextMMD(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            TextMMD(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }

    /** A signed percentage, because a month delta of zero is worth seeing. */
    private fun formatDelta(value: Int) = if (value >= 0) "+$value" else "$value"

    /**
     * One tab's worth of cards.
     *
     * Cards that hid themselves are already gone from [hiddenKeys], so a panel
     * can end up empty -- Notes on a habit with no notes -- and an empty panel
     * simply shows nothing rather than an empty scroller.
     */
    private class PanelSpec(val title: String, val cards: List<View>)

    @Composable
    private fun panels(): List<PanelSpec> {
        fun cardsFor(vararg keys: String): List<View> =
            keys.filter { it !in hiddenKeys }
                .mapNotNull { key -> cards.firstOrNull { it.key == key }?.view }

        // Three tabs. Four was one too many for the width: the row divides
        // evenly and MMD's own tab padding takes 16dp off each side, leaving
        // 58dp for a label, and "Calendar" rendered as "Calend". Three give
        // 120dp each, which every label clears.
        //
        // The one that went was Notes, and not for space -- it held the
        // question, the frequency, the reminder and the description, every one
        // of which is a field on the edit screen, one tap away, where they can
        // also be changed. A tab that only repeats another page is not worth
        // its width.
        return listOf(
            PanelSpec(stringOrEmpty(R.string.calendar), emptyList()),
            PanelSpec(stringOrEmpty(R.string.score), cardsFor("score", "bar")),
            PanelSpec(
                stringOrEmpty(R.string.streaks),
                cardsFor("streak", "frequency", "target"),
            ),
        )
    }

    /**
     * A panel's cards.
     *
     * Still `LazyColumnMMD`, because these are still Views and one of them can
     * be taller than the panel -- but with no scrollbar. A thumb sized by
     * `visibleCount / totalItemsCount` says nothing useful about one or two
     * cards of wildly different heights, and watching it resize as you drag is
     * what made the old screen feel broken.
     */
    @Composable
    private fun Panel(
        cards: List<View>,
        onScrolled: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val listState = rememberLazyListState()

        LaunchedEffect(listState, cards) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .drop(1)
                .collect { onScrolled() }
        }

        LazyColumnMMD(
            modifier = modifier.fillMaxSize(),
            state = listState,
            isScrollbarVisible = false,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(cards, key = { _, c -> System.identityHashCode(c) }) { index, card ->
                AndroidView(
                    factory = { card.detached() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (index < cards.lastIndex) HorizontalDividerMMD(thickness = 1.dp)
            }
        }
    }

    /** A string, or empty if the context has gone. */
    private fun stringOrEmpty(resId: Int): String = context.getString(resId)

    /**
     * A lazy item that scrolls out of range is disposed and its `AndroidView`
     * detaches the child; scrolling back calls the factory again with the same
     * instance. Handing back a view that still has a parent throws, so make
     * sure it has none.
     */
    private fun View.detached(): View = apply { (parent as? ViewGroup)?.removeView(this) }

    fun setState(data: ShowHabitState) {
        title = data.title
        binding.subtitleCard.setState(data.subtitle)
        binding.overviewCard.setState(data.overview)
        binding.notesCard.setState(data.notes)
        binding.targetCard.setState(data.target)
        binding.streakCard.setState(data.streaks)
        binding.scoreCard.setState(data.scores)
        binding.frequencyCard.setState(data.frequency)
        binding.historyCard.setState(data.history)
        history = data.history
        binding.barCard.setState(data.bar)

        // A numerical habit has a target rather than an overview, and vice
        // versa. Set both ways round, because the same view instances are
        // reused when the habit is edited from numerical to boolean.
        binding.overviewCard.visibility = if (data.isNumerical) GONE else VISIBLE
        binding.targetCard.visibility = if (data.isNumerical) VISIBLE else GONE

        // The summary figures. Score is a fraction, and the month delta is a
        // fraction of a fraction -- both are percentages by the time anyone
        // reads them. "Best" is the longest streak rather than the current
        // one, because that is what the core actually computes.
        statScore = (data.overview.scoreToday * 100).toInt()
        statMonth = (data.overview.scoreMonthDiff * 100).toInt()
        statTotal = data.overview.totalCount
        statBest = data.streaks.bestStreaks.maxOfOrNull { it.length } ?: 0

        // Read back after every card has had its say, including the three that
        // hide themselves.
        hiddenKeys = cards.filter { it.view.visibility != VISIBLE }
            .map { it.key }
            .toSet()
    }

    fun setMenuState(canArchive: Boolean, canUnarchive: Boolean) {
        this.canArchive = canArchive
        this.canUnarchive = canUnarchive
    }

    fun setListener(presenter: ShowHabitPresenter) {
        binding.scoreCard.setListener(presenter.scoreCardPresenter)
        binding.historyCard.setListener(presenter.historyCardPresenter)
        binding.barCard.setListener(presenter.barCardPresenter)
    }
}

/** The Calendar tab, which is drawn rather than hosted. */
private const val CALENDAR_TAB = 0
