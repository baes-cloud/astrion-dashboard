package com.custom.astrion.ui.ambient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.custom.astrion.ui.AstrionTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The rest face: a large H:mm clock in Antonio, centred over a dim pool of light,
 * the date below it in quiet text. Shown when nothing is playing and
 * `ambient.restClock` is on. Redraws on the minute, not the second.
 */
@Composable
fun RestClock(is24: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    val timeFmt = remember(is24) { SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }

    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(AstrionTheme.colors.surfacePrimary, AstrionTheme.colors.void)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                timeFmt.format(Date(now)),
                color = AstrionTheme.nightInk,
                fontFamily = AstrionTheme.readoutFont,
                fontWeight = FontWeight.Light,
                fontSize = AstrionTheme.size.restClock,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                dateFmt.format(Date(now)),
                color = AstrionTheme.nightInk2,
                fontFamily = AstrionTheme.bodyFont,
                fontSize = AstrionTheme.size.bodyLarge,
                maxLines = 1,
            )
        }
    }
}
