package com.maheshkatakam.melo.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class At(val endsAtMillis: Long) : SleepTimerState
    data object EndOfSong : SleepTimerState
}

/** Shared between the UI (which sets it) and the playback service (which enforces it). */
class SleepTimer {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    fun start(minutes: Int) {
        _state.value = SleepTimerState.At(System.currentTimeMillis() + minutes * 60_000L)
    }

    fun endOfSong() {
        _state.value = SleepTimerState.EndOfSong
    }

    fun cancel() {
        _state.value = SleepTimerState.Off
    }
}
