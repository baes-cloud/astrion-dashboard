package com.custom.astrion.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.CallOutcome
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tap → optimistic → pending → failure, for every control.
 *
 * 1. Optimistic: the control shows the state you asked for immediately
 *    ([rememberOptimistic]).
 * 2. Pending: if Home Assistant hasn't answered within 250 ms the control
 *    shows a spinner ([ActionHandle.busy]). Fast calls never flicker one.
 * 3. Failure: a refused or unsent call outlines the control in red for
 *    2.5 s ([ActionHandle.failed]), rolls the optimistic value back, and the
 *    reason appears in the feedback strip (HaClient.errors).
 */
@Stable
class ActionHandle internal constructor(
    private val client: HaClient,
    private val scope: CoroutineScope,
) {
    /** A call is in flight and has been for more than 250 ms. */
    var busy by mutableStateOf(false)
        private set

    /** The last call was refused or couldn't be sent (clears after 2.5 s). */
    var failed by mutableStateOf(false)
        private set

    /** Calls of this handle still waiting for HA (main thread only). */
    private var inFlight = 0

    /**
     * Fire [calls] together. [onFail] runs if any is refused or unsent —
     * use it to roll back an optimistic value.
     *
     * Started UNDISPATCHED, so the call leaves the device inside the tap
     * handler itself (no frame of latency, and rapid presses keep their
     * order). Earlier presses are never cancelled by later ones.
     */
    fun run(vararg calls: ServiceCall, onFail: (() -> Unit)? = null) {
        if (calls.isEmpty()) return
        val list = calls.toList()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            inFlight++
            failed = false
            val shower = launch {
                delay(250)
                if (inFlight > 0) busy = true
            }
            val outcomes = try {
                list.map { c -> async(start = CoroutineStart.UNDISPATCHED) { client.callAwait(c) } }.awaitAll()
            } finally {
                inFlight--
                shower.cancel()
                if (inFlight == 0) busy = false
            }
            val bad = outcomes.any { it == CallOutcome.FAILED || it == CallOutcome.NOT_SENT }
            if (bad) {
                onFail?.invoke()
                failed = true
                delay(2500)
                failed = false
            }
        }
    }
}

/** One [ActionHandle] per control. */
@Composable
fun rememberAction(ctx: CardContext): ActionHandle {
    val scope = rememberCoroutineScope()
    return remember(ctx.client) { ActionHandle(ctx.client, scope) }
}

/** Holder so a null / false target is distinguishable from "no target". */
class Held<T>(val value: T)

/**
 * An optimistic override of a value that Home Assistant owns. [show] returns
 * the requested value until HA reports it (or [timeoutMs] passes, or the
 * action fails and calls [clear]) — then the real value again.
 */
@Stable
class Optimistic<T> internal constructor() {
    internal var target by mutableStateOf<Held<T>?>(null)
    internal var stamp by mutableLongStateOf(0L)

    fun set(value: T) {
        target = Held(value)
        stamp = SystemClock.uptimeMillis()
    }

    fun clear() {
        target = null
    }

    val active: Boolean get() = target != null

    fun show(actual: T): T {
        val t = target
        return if (t != null) t.value else actual
    }
}

@Composable
fun <T> rememberOptimistic(actual: T, timeoutMs: Long = 6000L): Optimistic<T> {
    val o = remember { Optimistic<T>() }
    val t = o.target
    // HA caught up: drop the override.
    LaunchedEffect(actual, t) {
        if (t != null && t.value == actual) o.clear()
    }
    // HA never caught up (device offline, refused silently): give up.
    LaunchedEffect(o.stamp) {
        if (o.target != null) {
            delay(timeoutMs)
            o.clear()
        }
    }
    return o
}
