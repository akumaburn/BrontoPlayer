package com.aemake.brontoplayer.playback

/** Custom session command actions and argument/extra keys shared by the service and client. */
object PlaybackCommands {
    const val SET_SKIP_SILENCE = "com.aemake.brontoplayer.action.SET_SKIP_SILENCE"
    const val SET_SLEEP_TIMER = "com.aemake.brontoplayer.action.SET_SLEEP_TIMER"
    const val CANCEL_SLEEP_TIMER = "com.aemake.brontoplayer.action.CANCEL_SLEEP_TIMER"

    // Notification / lock-screen transport buttons (rewind & fast-forward by the configured interval).
    const val REWIND = "com.aemake.brontoplayer.action.REWIND"
    const val FAST_FORWARD = "com.aemake.brontoplayer.action.FAST_FORWARD"

    // Custom command arguments
    const val ARG_SKIP_SILENCE = "arg_skip_silence"
    const val ARG_SLEEP_DURATION_MS = "arg_sleep_duration_ms"
    const val ARG_SLEEP_END_OF_CHAPTER = "arg_sleep_end_of_chapter"

    // Session extras (service -> controllers)
    const val EXTRA_SLEEP_ACTIVE = "extra_sleep_active"
    const val EXTRA_SLEEP_REMAINING_MS = "extra_sleep_remaining_ms"
    const val EXTRA_SLEEP_END_OF_CHAPTER = "extra_sleep_end_of_chapter"
    const val EXTRA_SKIP_SILENCE = "extra_skip_silence"
}
