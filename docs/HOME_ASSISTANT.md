# Home Assistant companion pieces

The remotes do less work and feel more responsive when Home Assistant
handles some things itself. These are the automations, scripts and helpers
that go with this version of the app. They live in HA, not in the APK, so
they're recorded here to make them easy to recreate. Entity ids are the
author's; substitute your own.

All of them were created through HA's UI config API, so they're in
`automations.yaml` / `scripts.yaml` and editable in the HA UI.

## Sonos plays the TV when the TV is playing

Before this automation, every TV session needed a 1.5 s hold of SCENE. It
triggers on real playback, not TV power, so a TV that woke by mistake doesn't
take over the music.

It also requires the soundbar to be able to hear the TV. With the soundbar's
HDMI unplugged (to watch TV while music plays), the Sonos reports
`No input connected`, and nothing switches.

```yaml
alias: Club · Sonos plays the TV when the TV is playing
mode: single
triggers:
  - trigger: state
    entity_id: [media_player.the_club_tv, media_player.club_android_tv_10_0_0_248_club_tv]
    to: playing
    for: { seconds: 5 }
  - trigger: state
    entity_id: sensor.club_audio_input_format
conditions:
  - "{{ is_state('media_player.the_club_tv','playing') or is_state('media_player.club_android_tv_10_0_0_248_club_tv','playing') }}"
  - "{{ (states('sensor.club_audio_input_format') | lower) not in ['no input','no input connected','no audio','pcm 2.0 no audio','unavailable','unknown',''] }}"
  - "{{ state_attr('media_player.club','source') != 'TV' }}"
actions:
  - action: media_player.select_source
    target: { entity_id: media_player.club }
    data: { source: TV }
  - variables:
      others: "{{ (state_attr('media_player.club','group_members') or []) | reject('eq','media_player.club') | list }}"
  - if: "{{ others | count > 0 }}"
    then:
      - action: media_player.unjoin
        target: { entity_id: "{{ others }}" }
```

## Remote battery alerts

Each remote publishes its battery with `power.report_entity`, one sensor per
remote. `dock_fault` is true when the remote is plugged in but not charging,
or reports charging while its level falls (`dock_draining`), which is how
dirty or worn dock contacts show up.

```yaml
alias: Astrion remotes · battery alerts
mode: parallel
triggers:
  - trigger: template
    id: dock_fault
    value_template: "{{ state_attr('sensor.club_remote_113_battery','dock_fault') == true }}"
    for: { minutes: 3 }
    variables: { remote: sensor.club_remote_113_battery }
  # …one dock_fault trigger per remote…
  - trigger: numeric_state
    id: low
    entity_id: [sensor.club_remote_113_battery, sensor.club_remote_141_battery]
    below: 20
conditions:
  - "{{ trigger.id == 'dock_fault' or state_attr(trigger.entity_id,'docked') != true }}"
actions:
  - variables: { who: "{{ remote if trigger.id == 'dock_fault' else trigger.entity_id }}" }
  - action: notify.mobile_app_your_phone
    data:
      title: "{{ state_attr(who,'friendly_name') or who }}"
      message: >-
        {% if trigger.id == 'dock_fault' %}On its dock but not charging ({{ states(who) }}%). Reseat it or clean the contacts.
        {% else %}Off the dock at {{ states(who) }}%. Pop it on the dock.{% endif %}
```

## Stop a Plex session left paused

A paused Plex session on the Club TV's Cast entity reported its playhead
about four times a second, all day: some 680,000 state writes in 11 days,
each one sent to every remote with its screen on. The app now ignores
playhead-only updates from players that aren't playing. This automation
stops the updates at the source, and Plex keeps the resume point.

```yaml
alias: Club TV · stop a Plex session left paused
triggers:
  - trigger: state
    entity_id: media_player.the_club_tv
    to: paused
    for: { minutes: 5 }
conditions:
  - "{{ state_attr('media_player.the_club_tv','app_name') == 'Plex' }}"
actions:
  - action: media_player.media_stop
    target: { entity_id: media_player.the_club_tv }
```

## Reconnect a Sonos speaker HA lost

After a network blip, or a speaker getting a new DHCP address, HA's Sonos
integration can mark a speaker unavailable and never retry. The speaker
itself is fine, and the Sonos app and Music Assistant still see it. Reloading
the integration fixes it. A DHCP reservation per speaker stops it happening
in the first place.

```yaml
alias: Sonos · reconnect a speaker HA lost
mode: single
triggers:
  - trigger: state
    entity_id: [media_player.bathroom_sonos, media_player.bedroom_sonos, media_player.office_sonos, media_player.club]
    to: unavailable
    for: { minutes: 10 }
conditions:
  - "{{ this.attributes.last_triggered is none or (now() - this.attributes.last_triggered).total_seconds() > 3600 }}"
actions:
  - action: homeassistant.reload_config_entry
    target: { entity_id: "{{ trigger.entity_id }}" }
```

## Hold CURTAIN: all Club blinds one way

If any Club blind is open, this closes them all; otherwise it opens them all.

```yaml
alias: Club blinds · all one way          # script.club_blinds_all
sequence:
  - variables: { blinds: [cover.blinds, cover.club_sheer_blinds] }
  - variables:
      any_open: "{{ expand(blinds) | selectattr('state','in',['open','opening']) | list | count > 0 }}"
  - action: "{{ 'cover.close_cover' if any_open else 'cover.open_cover' }}"
    target: { entity_id: "{{ blinds }}" }
```

`longHotkeys`: `{ "key": "CURTAIN", "service": "script.turn_on", "entityId": "script.club_blinds_all" }`

## Alarm Stop starts the day

The alarm popup's Stop runs the usual stop script, then opens ABC iview on
the TV. The Sonos automation above takes care of the sound.

```yaml
alias: Work alarm · stop and start the day   # script.work_alarm_stop_and_start_day
sequence:
  - action: script.work_alarm_stop
  - action: androidtv.adb_command
    target: { entity_id: media_player.club_android_tv_10_0_0_248_club_tv }
    data:
      command: input keyevent KEYCODE_WAKEUP; sleep 1; cmd hdmi_control onetouchplay; monkey -p au.net.abc.iview -c android.intent.category.LEANBACK_LAUNCHER 1
```

`alarm.stop` in `dashboard.json`: `{ "service": "script.turn_on", "entity_id": "script.work_alarm_stop_and_start_day" }`

## Eye Comfort button (Samsung Serif)

The Samsung TV integration exposes no Eye Comfort setting, so the button
plays the key sequence a person would. The sequence was confirmed on a 2024
Serif:

1. MENU opens the settings panel, where the *EyeComfort Mode* chip is first
   and focused.
2. ENTER opens its settings, with the on/off toggle focused.
3. ENTER flips the toggle.
4. Three RETURNs close the menu. The first one is swallowed by the toggle
   page. Don't use EXIT: it also leaves the current input.

The TV greys out Eye Comfort on an input set as a PC, so the script and the
button both skip that input.

```yaml
alias: Serif TV · Eye Comfort toggle      # script.tv_eye_comfort_toggle
mode: single
sequence:
  - condition: template
    value_template: "{{ is_state('media_player.55_the_serif_3','on') and state_attr('media_player.55_the_serif_3','source') != 'hdmi2' }}"
  - action: remote.send_command
    target: { entity_id: remote.the_serif_qa55ls01dawxxy }
    data: { command: KEY_MENU }
  - delay: { milliseconds: 1800 }
  - action: remote.send_command
    target: { entity_id: remote.the_serif_qa55ls01dawxxy }
    data: { command: KEY_ENTER }
  - delay: { milliseconds: 1500 }
  - action: remote.send_command
    target: { entity_id: remote.the_serif_qa55ls01dawxxy }
    data: { command: KEY_ENTER }
  - delay: { milliseconds: 2000 }
  - action: remote.send_command
    target: { entity_id: remote.the_serif_qa55ls01dawxxy }
    data: { command: KEY_RETURN }
  - delay: { milliseconds: 1500 }
  - action: remote.send_command
    target: { entity_id: remote.the_serif_qa55ls01dawxxy }
    data: { command: KEY_RETURN }
  - delay: { milliseconds: 1500 }
  - action: remote.send_command
    target: { entity_id: remote.the_serif_qa55ls01dawxxy }
    data: { command: KEY_RETURN }
```

The IR popup button skips the PC input itself:

```json
{ "name": "Eye comfort", "service": "script.turn_on", "entity_id": "script.tv_eye_comfort_toggle",
  "unless": { "entity_id": "media_player.55_the_serif_3", "attribute": "source", "is": "hdmi2" },
  "unless_text": "Not on the PC input" }
```

The TV menu is a fixed layout of today's firmware. If Samsung reorders the
quick-settings chips, the sequence will toggle the wrong thing.

## Voice shortcuts

These are sentence triggers that HA handles locally, so they skip the LLM.
The assist pipeline needs `prefer_local_intents` on. Each conversation the
remote has is also written to `/sdcard/astrion/voice/log.txt`: what was
heard, the reply, and HA's response type.

| Say | Does |
|---|---|
| "tv mode", "watch tv", "sonos on the tv" | Sonos to TV audio, other speakers unjoined |
| "goodnight", "all lights off" | `script.long_lights` |
| "night mode" / "day mode" | `scene.night` / `script.day` |
| "blinds" | `script.club_blinds_all` |
| "blinds half" | sheers to 50% |
| "dim the lights" | Club lights that are on, to 30% |

## Sofa occupancy that survives sitting still

RMM's sofa zone comes from the LD2450, which loses a person who sits still.
Two counters recorded it: `counter.ld2412_sees_someone_rmm_lost` and
`counter.rmm_target_ld2412_cannot_see`. A template helper keeps the sofa
occupied while the LD2412 still sees someone after RMM has lost everyone. The
couch-light automations use the helper instead of RMM's sensor.

```jinja
{# binary_sensor.sofa_occupied_held (template helper, device class occupancy) #}
{% set rmm = is_state('binary_sensor.rmm_default_sofa_occupancy','on') %}
{% set lost = (states('sensor.rmm_default_master') | int(0)) == 0 %}
{% set still_there = is_state('binary_sensor.club_apollo_r_pro_1_ld2412_presence','on') %}
{{ rmm or (this.state == 'on' and lost and still_there) }}
```
