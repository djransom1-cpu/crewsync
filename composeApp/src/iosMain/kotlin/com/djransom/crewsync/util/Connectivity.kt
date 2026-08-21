package com.djransom.crewsync.util

import androidx.compose.runtime.*
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberConnectivityState(): State<Boolean> {
    val state = remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            val status = nw_path_get_status(path)
            state.value = (status == nw_path_status_satisfied)
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)

        onDispose {
            // Monitor is cleaned up by the system in KMP
        }
    }

    return state
}
