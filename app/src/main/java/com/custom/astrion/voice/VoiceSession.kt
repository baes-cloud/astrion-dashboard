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

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Begin a voice interaction. No-ops if one is already running. */
    fun start(pipelineId: String? = null) {
        if (_state.value.phase != VoicePhase.IDLE && _state.value.phase != VoicePhase.DONE &&
            _state.value.phase != VoicePhase.ERROR
        ) return

        if (!hasPermission) {
            _state.value = VoiceState(phase = VoicePhase.ERROR, error = "Microphone permission not granted")
            return
        }

        stopPlayback()
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
                // If TTS produced nothing there's nothing left to wait for.
                if (_state.value.phase != VoicePhase.SPEAKING) finish()
                subscriptionId?.let { client.endSubscription(it) }
                subscriptionId = null
            }

            "error" -> {
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
                val startedAt = System.currentTimeMillis()
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
                    _state.value = _state.value.copy(level = rmsLevel(buf, read))

                    if (System.currentTimeMillis() - startedAt > MAX_LISTEN_MS) {
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

    /** Rough RMS of a 16-bit LE buffer, normalised to 0..1 for the UI. */
    private fun rmsLevel(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < len) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += (s * s).toDouble()
            n++
            i += 2
        }
        if (n == 0) return 0f
        val rms = Math.sqrt(sum / n) / 32768.0
        // Speech sits low in the linear range; scale so it reads well visually.
        return (rms * 4.0).coerceIn(0.0, 1.0).toFloat()
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
        streaming = false
        releaseRecorder()
        _state.value = _state.value.copy(phase = VoicePhase.DONE, level = 0f)
        // Let the result sit on screen briefly, then reset.
        scope.launch {
            delay(2_500)
            if (_state.value.phase == VoicePhase.DONE) _state.value = VoiceState()
        }
    }

    private fun fail(message: String) {
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
