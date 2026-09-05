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

package org.isoron.uhabits.activities.intro

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import org.isoron.uhabits.R

/**
 * The first-run introduction, built from Mudita Mindful Design components.
 *
 * This replaces `AppIntro2`. That library drew its own bar, its own indicator
 * and its own navigation buttons -- grey discs with white glyphs, the one
 * combination this panel renders worst -- and re-applied those backgrounds on
 * every page change, so stripping them meant reaching into the library's
 * resource IDs from three different lifecycle callbacks and hoping it had
 * finished inflating. Owning the deck outright removes both the dependency and
 * that hack.
 *
 * The deck **pages**: there is no swipe and no cross-fade. MuditaOS paginates
 * its own lists rather than scrolling them, and a cross-fade between slides is
 * a full-screen repaint per swipe, which on E Ink leaves a ghost of the slide
 * you just left.
 */

/** One slide: an icon, a heading and a line of explanation. */
private data class IntroSlide(
    val imageRes: Int,
    val titleRes: Int,
    val descriptionRes: Int,
)

private val SLIDES = listOf(
    IntroSlide(R.drawable.intro_icon_1, R.string.intro_title_1, R.string.intro_description_1),
    IntroSlide(R.drawable.intro_icon_2, R.string.intro_title_2, R.string.intro_description_2),
    IntroSlide(R.drawable.intro_icon_4, R.string.intro_title_4, R.string.intro_description_4),
)

@Composable
fun IntroScreenMMD(onFinished: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val slide = SLIDES[page]
    val isLast = page == SLIDES.lastIndex

    ThemeMMD {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // The three illustrations are not the same shape -- one is a
                // square disc, one is a wide strip -- so this fixes the band
                // they sit in and lets Fit decide each one's width, rather
                // than squaring them all and letterboxing the wide one.
                Image(
                    painter = painterResource(slide.imageRes),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                Spacer(modifier = Modifier.height(40.dp))
                TextMMD(
                    text = stringResource(slide.titleRes),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextMMD(
                    text = stringResource(slide.descriptionRes),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }

            PageIndicator(current = page, count = SLIDES.size)
            Spacer(modifier = Modifier.height(24.dp))

            // MuditaOS labels its navigation actions with words -- MeditationWindow
            // puts "options", "start" and "back" on the nav bar -- so these are
            // labels, not glyphs on discs.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = { if (page == 0) onFinished() else page -= 1 },
                ) {
                    TextMMD(
                        text = stringResource(
                            if (page == 0) R.string.intro_skip else R.string.intro_back
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                ButtonMMD(onClick = { if (isLast) onFinished() else page += 1 }) {
                    TextMMD(
                        text = stringResource(
                            if (isLast) R.string.done_label else R.string.intro_next
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Which slide this is, as filled and hollow dots.
 *
 * `eInkColorScheme` has no grey, so the unselected dot cannot simply be a
 * lighter fill: it is the same circle drawn as an outline. State is inversion,
 * the same rule the switch follows.
 */
@Composable
private fun PageIndicator(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) { index ->
            val ink = MaterialTheme.colorScheme.onBackground
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .then(
                        if (index == current) {
                            Modifier.background(ink, CircleShape)
                        } else {
                            Modifier.border(2.dp, ink, CircleShape)
                        }
                    ),
            )
        }
    }
}
