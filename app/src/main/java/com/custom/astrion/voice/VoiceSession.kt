package com.custom.astrion.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.custom.astrion.ha.HaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the voice overlay should be showing right now. */
enum class VoicePhase { IDLE, LISTENING, PROCESSING, SPEAKING, DONE, ERROR }

data class VoiceState(
    val phase: VoicePhase = VoicePhase.IDLE,
    /** Live transcript of what you said (filled in at stt-end). */
    val transcript: String = "",
    /** Assist's spoken reply text. */
    val reply: String = "",
    val error: String? = null,
    /** 0..1 mic level, drives the UI animation. */
    val level: Float = 0f,
    /**
     * Mic open and streaming to HA's wake word engine, waiting for the wake
     * word. The phase stays IDLE while armed, so no overlay is shown.
     */
    val armed: Boolean = false,
)

/**
 * Home Assistant Assist satellite, the same shape as an ESPHome voice
 * satellite but running on the remote itself.
 *
 * Flow (all over the existing authenticated HA websocket):
 *  1. `assist_pipeline/run` with start_stage=stt, end_stage=tts
 *  2. HA replies `run-start` carrying `stt_binary_handler_id`
 *  3. we stream raw 16 kHz mono PCM as binary frames prefixed with that id
 *  4. HA's VAD decides you stopped talking → `stt-end` with the transcript
 *  5. `intent-end` carries the assistant's text reply
 *  6. `tts-end` carries a URL we play back through MediaPlayer
 *
 * Wake word mode ([armWakeWord]) is the same run started one stage earlier
 * (start_stage=wake_word): audio streams to the pipeline's wake word engine
 * until it fires `wake_word-end`, then carries straight on into STT.
 *
 * Audio format is fixed by the Assist API: 16-bit little-endian PCM, mono,
 * 16 kHz — which is also what AudioRecord gives us natively here.
 */
class VoiceSession(
    private val context: Context,
    private val client: HaClient,
) {
    companion object {
        private const val TAG = "VoiceSession"
        private const val SAMPLE_RATE = 16_000
        /** ~32 ms of audio per frame; small enough for responsive VAD. */
        private const val CHUNK_SAMPLES = 512
        /** Hard stop so a stuck pipeline can never record forever. */
        private const val MAX_LISTEN_MS = 30_000L
        /**
         * How long one armed run waits for the wake word before HA ends it with
         * `wake-word-timeout` (its default is only 3 s). The caller re-arms.
         */
        private const val WAKE_TIMEOUT_S = 3_600

        // End-of-speech detection for wake word runs. HA's own VAD ends the
        // STT stage almost immediately when it follows a wake word (the run
        // then fails with stt-stream-failed), so those runs use no_vad and
        // we end the audio ourselves.
        /** Speech = louder than the room by this factor... */
        private const val SPEECH_FACTOR = 2.5
        /** ...and never quieter than this (normalised RMS, about -44 dBFS). */
        private const val MIN_SPEECH_RMS = 0.006
        /** Ignore the wake word's tail and the chime right after detection. */
        private const val WAKE_SETTLE_MS = 300L
        /** Quiet this long after speaking = you've finished. */
        private const val END_SILENCE_MS = 900L
        /** Nothing said this long after the wake word = give up. */
        private const val NO_SPEECH_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recordJob: Job? = null
    private var subscriptionId: Int? = null
    private var recorder: AudioRecord? = null
    private var player: MediaPlayer? = null

    @Volatile private var handlerId: Int? = null
    @Volatile private var streaming = false
    /** When the current command started (push-to-talk, or wake word fired). */
    @Volatile private var listenStartedAt = 0L

    // Local end-of-speech state (wake word runs only, see SPEECH_FACTOR).
    @Volatile private var endpointing = false
    private var noiseFloor = 0.0
    private var speechSeen = false
    private var lastLoudAt = 0L

    /** Last time an armed run failed for a reason other than its own timeout. */
    @Volatile var lastWakeFailureAt = 0L
        private set

    /** Called (on an IO thread) when the wake word is heard. */
    var onWakeWord: (() -> Unit)? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Begin a voice interaction. No-ops if one is already running. */
    fun start(pipelineId: String? = null) {
        // Push-to-talk wins over a waiting wake word run.
        if (_state.value.armed) cancel()
        if (_state.value.phase != VoicePhase.IDLE && _state.value.phase != VoicePhase.DONE &&
            _state.value.phase != VoicePhase.ERROR
        ) return

        if (!hasPermission) {
            _state.value = VoiceState(phase = VoicePhase.ERROR, error = "Microphone permission not granted")
            return
        }

        stopPlayback()
        endpointing = false // push-to-talk keeps HA's VAD
        _state.value = VoiceState(phase = VoicePhase.LISTENING)

        val id = client.startSubscription(build = {
            put("type", "assist_pipeline/run")
            put("start_stage", "stt")
            put("end_stage", "tts")
            pipelineId?.let { put("pipeline", it) }
            put("input", buildJsonObject { put("sample_rate", SAMPLE_RATE) })
        }, onEvent = ::onPipelineEvent)

        if (id == null) {
            _state.value = VoiceState(phase = VoicePhase.ERROR, error = "Not connected to Home Assistant")
            return
        }
        subscriptionId = id
        listenStartedAt = System.currentTimeMillis()
    }

    /**
     * Open the mic and wait for the pipeline's wake word (the pipeline must
     * have a wake word engine set). Silent until it fires; failures just
     * disarm and stamp [lastWakeFailureAt] so the caller can back off.
     */
    fun armWakeWord(pipelineId: String? = null) {
        if (_state.value.phase != VoicePhase.IDLE || _state.value.armed || !hasPermission) return

        _state.value = VoiceState(armed = true)
        endpointing = false
        noiseFloor = 0.0
        val id = client.startSubscription(build = {
            put("type", "assist_pipeline/run")
            put("start_stage", "wake_word")
            put("end_stage", "tts")
            // Whole-run cap (HA default 300 s): must outlast the wake timeout
            // plus a spoken command, or HA kills the run with code "timeout".
            put("timeout", WAKE_TIMEOUT_S + 120)
            pipelineId?.let { put("pipeline", it) }
            put("input", buildJsonObject {
                put("sample_rate", SAMPLE_RATE)
                put("timeout", WAKE_TIMEOUT_S)
                put("no_vad", true)
            })
        }, onEvent = ::onPipelineEvent)

        if (id == null) {
            disarmQuietly(failed = true)
            return
        }
        subscriptionId = id
    }

    /** Stop waiting for the wake word. No-op unless armed. */
    fun disarm() {
        if (_state.value.armed) cancel()
    }

    /** Stop sending audio but let the pipeline finish thinking/replying. */
    fun stopListening() {
        if (!streaming) return
        streaming = false
        // A frame containing only the handler byte tells HA "end of audio".
        handlerId?.let { client.sendAudioChunk(it, ByteArray(0), 0) }
        if (_state.value.phase == VoicePhase.LISTENING) {
            _state.value = _state.value.copy(phase = VoicePhase.PROCESSING, level = 0f)
        }
    }

    /** Tear everything down (overlay dismissed / mic pressed again). */
    fun cancel() {
        endpointing = false
        // An armed run would otherwise sit in HA until its hour-long timeout;
        // end-of-audio makes HA abort it now.
        if (_state.value.armed && streaming) handlerId?.let { client.sendAudioChunk(it, ByteArray(0), 0) }
        streaming = false
        recordJob?.cancel()
        recordJob = null
        releaseRecorder()
        stopPlayback()
        subscriptionId?.let { client.endSubscription(it) }
        subscriptionId = null
        handlerId = null
        _state.value = VoiceState(phase = VoicePhase.IDLE)
    }

    // ---- pipeline events ----------------------------------------------------

    private fun onPipelineEvent(event: JsonObject) {
        when (event["type"]?.jsonPrimitive?.content) {
            "run-start" -> {
                val hid = event["data"]?.jsonObject
                    ?.get("runner_data")?.jsonObject
                    ?.get("stt_binary_handler_id")?.jsonPrimitive?.int
                if (hid == null) {
                    fail("HA did not return an audio handler id")
                } else {
                    handlerId = hid
                    startRecording(hid)
                }
            }

            "wake_word-end" -> {
                val heard = event["data"]?.jsonObject?.get("wake_word_output") as? JsonObject
                if (_state.value.armed && !heard.isNullOrEmpty()) {
                    listenStartedAt = System.currentTimeMillis()
                    speechSeen = false
                    lastLoudAt = listenStartedAt
                    endpointing = true
                    _state.value = VoiceState(phase = VoicePhase.LISTENING)
                    onWakeWord?.invoke()
                }
            }

            "stt-end" -> {
                val text = event["data"]?.jsonObject
                    ?.get("stt_output")?.jsonObject
                    ?.get("text")?.jsonPrimitive?.content.orEmpty()
                streaming = false
                releaseRecorder()
                _state.value = _state.value.copy(
                    phase = VoicePhase.PROCESSING,
                    transcript = text,
                    level = 0f,
                )
            }

            "intent-end" -> {
                val speech = event["data"]?.jsonObject
                    ?.get("intent_output")?.jsonObject
                    ?.get("response")?.jsonObject
                    ?.get("speech")?.jsonObject
                    ?.get("plain")?.jsonObject
                    ?.get("speech")?.jsonPrimitive?.content.orEmpty()
                _state.value = _state.value.copy(reply = speech)
            }

            "tts-end" -> {
                val url = event["data"]?.jsonObject
                    ?.get("tts_output")?.jsonObject
                    ?.get("url")?.jsonPrimitive?.content
                if (url != null) playReply(url) else finish()
            }

            "run-end" -> {
                if (_state.value.armed) {
                    disarmQuietly(failed = false)
                    return
                }
                // If TTS produced nothing there's nothing left to wait for.
                if (_state.value.phase != VoicePhase.SPEAKING) finish()
                subscriptionId?.let { client.endSubscription(it) }
                subscriptionId = null
            }

            "error" -> {
                if (_state.value.armed) {
                    val code = event["data"]?.jsonObject?.get("code")?.jsonPrimitive?.content
                    val expected = code == "wake-word-timeout" || code == "timeout"
                    if (!expected) Log.w(TAG, "wake word run ended: $code")
                    disarmQuietly(failed = !expected)
                    return
                }
                val msg = event["data"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    ?: "Assist pipeline error"
                fail(msg)
            }
        }
    }

    // ---- mic capture --------------------------------------------------------

    private fun startRecording(hid: Int) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            fail("Microphone unavailable (bad buffer size)")
            return
        }
        // Give ourselves generous headroom so a slow SoC can't drop samples.
        val bufSize = maxOf(minBuf * 4, CHUNK_SAMPLES * 2 * 8)

        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (e: Exception) {
            fail("Could not open microphone: ${e.message}")
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fail("Microphone failed to initialise")
            return
        }

        recorder = rec
        streaming = true
        recordJob = scope.launch {
            val buf = ByteArray(CHUNK_SAMPLES * 2)
            try {
                rec.startRecording()
                while (streaming) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read <= 0) {
                        if (read < 0) Log.w(TAG, "AudioRecord.read error $read")
                        continue
                    }
                    if (!client.sendAudioChunk(hid, buf, read)) {
                        fail("Lost connection while streaming audio")
                        return@launch
                    }
                    val rms = rawRms(buf, read)
                    // While armed nobody is watching the level; just learn
                    // how loud the room is for end-of-speech detection.
                    if (_state.value.armed) {
                        noiseFloor = if (noiseFloor == 0.0) rms else noiseFloor * 0.98 + rms * 0.02
                        continue
                    }
                    // Speech sits low in the linear range; scale so it reads well visually.
                    _state.value = _state.value.copy(level = (rms * 4.0).coerceIn(0.0, 1.0).toFloat())

                    if (endpointing && speechEnded(rms)) {
                        stopListening()
                        break
                    }

                    if (System.currentTimeMillis() - listenStartedAt > MAX_LISTEN_MS) {
                        stopListening()
                        break
                    }
                }
            } catch (e: Exception) {
                if (streaming) fail("Recording failed: ${e.message}")
            } finally {
                releaseRecorder()
            }
        }
    }

    /** After the wake word: has the user spoken and then gone quiet? */
    private fun speechEnded(rms: Double): Boolean {
        val now = System.currentTimeMillis()
        if (now - listenStartedAt < WAKE_SETTLE_MS) return false
        if (rms > maxOf(noiseFloor * SPEECH_FACTOR, MIN_SPEECH_RMS)) {
            speechSeen = true
            lastLoudAt = now
        }
        val quietFor = now - lastLoudAt
        return if (speechSeen) quietFor > END_SILENCE_MS else quietFor > NO_SPEECH_MS
    }

    /** RMS of a 16-bit LE buffer, normalised to 0..1 of full scale. */
    private fun rawRms(buf: ByteArray, len: Int): Double {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < len) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += (s * s).toDouble()
            n++
            i += 2
        }
        return if (n == 0) 0.0 else Math.sqrt(sum / n) / 32768.0
    }

    private fun releaseRecorder() {
        recorder?.let {
            runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            runCatching { it.release() }
        }
        recorder = null
    }

    // ---- reply playback -----------------------------------------------------

    private fun playReply(url: String) {
        _state.value = _state.value.copy(phase = VoicePhase.SPEAKING)
        stopPlayback()
        try {
            val mp = MediaPlayer()
            player = mp
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setDataSource(
                context,
                android.net.Uri.parse(client.authedUrl(url)),
                mapOf("Authorization" to "Bearer ${client.bearerToken()}"),
            )
            mp.setOnCompletionListener { finish() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "TTS playback error $what/$extra")
                finish(); true
            }
            mp.prepareAsync()
            mp.setOnPreparedListener { it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "TTS playback failed", e)
            finish()
        }
    }

    private fun stopPlayback() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    // ---- terminal states ----------------------------------------------------

    private fun finish() {
        endpointing = false
        streaming = false
        releaseRecorder()
        _state.value = _state.value.copy(phase = VoicePhase.DONE, level = 0f)
        // Let the result sit on screen briefly, then reset.
        scope.launch {
            delay(2_500)
            if (_state.value.phase == VoicePhase.DONE) _state.value = VoiceState()
        }
    }

    /** Tear down an armed run without showing anything. */
    private fun disarmQuietly(failed: Boolean) {
        endpointing = false
        if (failed) lastWakeFailureAt = System.currentTimeMillis()
        streaming = false
        releaseRecorder()
        subscriptionId?.let { client.endSubscription(it) }
        subscriptionId = null
        handlerId = null
        _state.value = VoiceState()
    }

    private fun fail(message: String) {
        endpointing = false
        if (_state.value.armed) {
            Log.w(TAG, "wake word failure: $message")
            disarmQuietly(failed = true)
            return
        }
        Log.w(TAG, "voice failure: $message")
        streaming = false
        releaseRecorder()
        subscriptionId?.let { client.endSubscription(it) }
        subscriptionId = null
        _state.value = _state.value.copy(phase = VoicePhase.ERROR, error = message, level = 0f)
        scope.launch {
            delay(3_500)
            if (_state.value.phase == VoicePhase.ERROR) _state.value = VoiceState()
        }
    }
}
