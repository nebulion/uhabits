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

import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.FrequencyCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCardState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardPresenter
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

    /**
     * The streaks and the weekday grid, held for Compose rather than pushed
     * into a View.
     *
     * Both cards are drawn by [HabitStreaksMMD] and [HabitFrequencyMMD] now.
     * Their old `StreakChart` and `FrequencyChart` are still in the tree --
     * they are `android.view.View` subclasses with their own `onDraw`, and the
     * habits list's checkmark drawing still shares their halftone -- but
     * nothing on this screen hosts them any more.
     */
    private var streaks by mutableStateOf<StreakCardState?>(null)
    private var frequency by mutableStateOf<FrequencyCardState?>(null)
    private var target by mutableStateOf<TargetCardState?>(null)
    private var isNumerical by mutableStateOf(false)

    /**
     * Which span the score and bar charts are drawn over, and whether the bar
     * chart is showing a numerical habit.
     *
     * Held here because the picker for them is Compose now. It was an
     * `AppCompatSpinner` inside each card's own layout -- the most obviously
     * un-Mudita thing left on a screen that otherwise looks finished, and one
     * that dragged a grey `?android:attr/listDivider` and a shadowed
     * `ListPopupWindow` into its popup that no Compose theme could reach.
     */
    private var scoreSpan by mutableStateOf(0)
    private var barSpan by mutableStateOf(0)
    private var barIsNumerical by mutableStateOf(false)

    private var scorePresenter: ScoreCardPresenter? = null
    private var barPresenter: BarCardPresenter? = null

    private val composeView = ComposeView(context)

    /** One card body, keyed so the lazy list can identify it across scrolls. */
    private class Card(val key: String, val view: View)

    private val cards: List<Card>

    init {
        cards = listOf(
            Card("score", binding.scoreCard),
            Card("bar", binding.barCard),
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
                            leading = when (selected) {
                                STREAKS_TAB -> {
                                    {
                                        // Only a numerical habit has a target.
                                        // The overview card that used to be
                                        // its boolean counterpart is gone: the
                                        // pinned summary says all of it.
                                        if (isNumerical) {
                                            target?.let { HabitTargetMMD(state = it) }
                                        }
                                        streaks?.let { HabitStreaksMMD(state = it) }
                                    }
                                }
                                else -> null
                            },
                            trailing = when (selected) {
                                SCORE_TAB -> {
                                    { frequency?.let { HabitFrequencyMMD(state = it) } }
                                }
                                else -> null
                            },
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
    private class PanelSpec(val title: String, val cards: List<Card>)

    @Composable
    private fun panels(): List<PanelSpec> {
        fun cardsFor(vararg keys: String): List<Card> =
            keys.filter { it !in hiddenKeys }
                .mapNotNull { key -> cards.firstOrNull { it.key == key } }

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
        //
        // Frequency sits under Score, not Streaks. It is a question about
        // pattern over time -- which days does this actually happen on -- and
        // it belongs beside the score line and the bar chart rather than
        // beside a table of longest runs. Moving it also leaves Streaks as one
        // clean list.
        return listOf(
            PanelSpec(stringOrEmpty(R.string.calendar), emptyList()),
            PanelSpec(stringOrEmpty(R.string.score), cardsFor("score", "bar")),
            PanelSpec(stringOrEmpty(R.string.streaks), emptyList()),
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
        cards: List<Card>,
        onScrolled: () -> Unit,
        modifier: Modifier = Modifier,
        /** Compose content above the hosted cards, if this panel has any. */
        leading: (@Composable () -> Unit)? = null,
        /** Compose content below them. */
        trailing: (@Composable () -> Unit)? = null,
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
            if (leading != null) {
                item(key = "leading") { leading() }
            }
            itemsIndexed(cards, key = { _, c -> System.identityHashCode(c.view) }) { index, card ->
                // The span picker sits above its chart rather than inside the
                // card, because the card is still an Android View and this is
                // not.
                when (card.key) {
                    "score" -> SpanPicker(
                        options = SPAN_NAMES,
                        selected = scoreSpan,
                        onSelect = { scorePresenter?.onSpinnerPosition(it) },
                    )
                    "bar" -> SpanPicker(
                        // A boolean habit's bar chart cannot be drawn a day at
                        // a time -- there is nothing to average -- so its list
                        // starts at Week and its indices are shifted by one
                        // against the numerical one. That offset lived in two
                        // `android:entries` arrays before, where nothing said
                        // they were different.
                        options = if (barIsNumerical) SPAN_NAMES else SPAN_NAMES.drop(1),
                        selected = barSpan,
                        onSelect = {
                            if (barIsNumerical) {
                                barPresenter?.onNumericalSpinnerPosition(it)
                            } else {
                                barPresenter?.onBoolSpinnerPosition(it)
                            }
                        },
                    )
                }
                AndroidView(
                    factory = { card.view.detached() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (index < cards.lastIndex) HorizontalDividerMMD(thickness = 1.dp)
            }
            if (trailing != null) {
                item(key = "trailing") {
                    if (cards.isNotEmpty()) HorizontalDividerMMD(thickness = 1.dp)
                    trailing()
                }
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
        binding.scoreCard.setState(data.scores)
        target = data.target
        isNumerical = data.isNumerical
        history = data.history
        streaks = data.streaks
        frequency = data.frequency
        binding.barCard.setState(data.bar)
        scoreSpan = data.scores.spinnerPosition
        barIsNumerical = data.bar.isNumerical
        barSpan = if (data.bar.isNumerical) {
            data.bar.numericalSpinnerPosition
        } else {
            data.bar.boolSpinnerPosition
        }

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
        scorePresenter = presenter.scoreCardPresenter
        barPresenter = presenter.barCardPresenter
        binding.scoreCard.setListener(presenter.scoreCardPresenter)
        binding.barCard.setListener(presenter.barCardPresenter)
    }
}

/** The Calendar tab, which is drawn rather than hosted. */
private const val CALENDAR_TAB = 0

/**
 * The five spans a chart can be drawn over: day, week, month, quarter, year.
 *
 * Read from the same strings the two `android:entries` arrays used, so nothing
 * has to be translated twice.
 */
/** Index of each tab in [ShowHabitView.panels]. */
private const val SCORE_TAB = 1
private const val STREAKS_TAB = 2

private val SPAN_NAMES = listOf(
    R.string.day,
    R.string.week,
    R.string.month,
    R.string.quarter,
    R.string.year,
)

/**
 * Picks the span a chart is drawn over.
 *
 * **A sheet, not a tab row**, which is a departure from what this project
 * planned and worth saying why. Two to four always-visible options is a tab
 * row, and that is what the notes called for -- but there are five here, and
 * the arithmetic does not work: the row divides the width evenly and MMD takes
 * 16dp off each side of a tab, which leaves about 40dp of label at 480px. That
 * is enough for "Week" and not for "Quarter". Stacking a second tab row under
 * the Calendar/Score/Streaks one would also be two rows of tabs saying
 * different kinds of thing.
 *
 * So it follows the other rule instead, the one MuditaOS itself follows in
 * Sound and Bluetooth: **a choice is a sheet.** The row shows the span in
 * force with a chevron; tapping it opens `ModalBottomSheetMMD` holding
 * `RadioButtonMMD` rows in MMD's own shape -- an inert button (the row carries
 * the click), a 56dp row, and the label held off the button by 16dp. It is the
 * same pattern the frequency picker and the new first-day-of-week setting use,
 * so the app answers every one-of-N question the same way.
 */
@Composable
private fun SpanPicker(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val index = selected.coerceIn(0, options.lastIndex)
    // A control row, not a floating label: what it sets on the left, the value
    // it is set to on the right, and a hairline closing it. The value is
    // inverted because it is the one thing on this page you can act on, which
    // is the only way an all-black-on-white palette can say "this is a
    // control" without inventing a grey.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clickable { open = true }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextMMD(
            text = stringResource(R.string.averaged_by),
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.onBackground,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextMMD(
                text = stringResource(options[index]),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.background,
            )
            Icon(
                painter = painterResource(R.drawable.mmd_chevron_filled_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    HorizontalDividerMMD(thickness = 1.dp)
    if (open) {
        ModalBottomSheetMMD(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true),
            dragHandle = null,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                options.forEachIndexed { position, res ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = position == index,
                                onClick = {
                                    open = false
                                    onSelect(position)
                                },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButtonMMD(selected = position == index, onClick = null)
                        TextMMD(
                            text = stringResource(res),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
