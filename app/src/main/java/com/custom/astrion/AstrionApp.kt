package com.custom.astrion

import android.app.Application
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.impl.BubbleLightCard
import com.custom.astrion.cards.impl.ButtonGridCard
import com.custom.astrion.cards.impl.ClimateCard
import com.custom.astrion.cards.impl.CalendarLineCard
import com.custom.astrion.cards.impl.ClockWeatherCard
import com.custom.astrion.cards.impl.CoverCard
import com.custom.astrion.cards.impl.FanCard
import com.custom.astrion.cards.impl.LightCard
import com.custom.astrion.cards.impl.LightGroupCard
import com.custom.astrion.cards.impl.LightZonesCard
import com.custom.astrion.cards.impl.LockCard
import com.custom.astrion.cards.impl.MediaPlayerCard
import com.custom.astrion.cards.impl.MonitorCard
import com.custom.astrion.cards.impl.NextUpCard
import com.custom.astrion.cards.impl.NowPlayingLineCard
import com.custom.astrion.cards.impl.PictureElementsCard
import com.custom.astrion.cards.impl.ClockHeaderCard
import com.custom.astrion.cards.impl.MediaShelvesCard
import com.custom.astrion.cards.impl.PlexCard
import com.custom.astrion.cards.impl.SectionCard
import com.custom.astrion.cards.impl.RowCard
import com.custom.astrion.cards.impl.SceneGridCard
import com.custom.astrion.cards.impl.SourceSelectCard
import com.custom.astrion.cards.impl.SpeakerGroupCard
import com.custom.astrion.cards.impl.StackCard
import com.custom.astrion.cards.impl.SwipeStackCard
import com.custom.astrion.cards.impl.SwitchCard
import com.custom.astrion.cards.impl.TvRemoteCard
import com.custom.astrion.cards.impl.VacuumCard

/**
 * App entry point. Register all card types here once at startup.
 *
 * To add a brand-new native card type:
 *   1. Create a class implementing CardRenderer (see cards/impl/ for examples).
 *   2. Add one line below.
 *   3. Reference it in DashboardConfig with its `type` string.
 *
 * That's the whole extension model — no re-patching anyone's APK, no fixed
 * taxonomy of 11 types.
 */
class AstrionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CardRegistry.register(
            LightCard(),
            LightGroupCard(),
            LightZonesCard(),
            SceneGridCard(),
            BubbleLightCard(),
            TvRemoteCard(),
            MediaPlayerCard(),
            ClimateCard(),
            CoverCard(),
            LockCard(),
            FanCard(),
            SwitchCard(),
            ClockWeatherCard(),
            PictureElementsCard(),
            RowCard(),
            SwipeStackCard(),
            StackCard(),
            MonitorCard(),
            ButtonGridCard(),
            PlexCard(),
            MediaShelvesCard(),
            SectionCard(),
            ClockHeaderCard(),
            CalendarLineCard(),
            NowPlayingLineCard(),
            NextUpCard(),
            SpeakerGroupCard(),
            SourceSelectCard(),
            VacuumCard(),
            // ← register your own card types here
        )
    }
}
