package com.tvremote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvremote.ui.DevicesScreen
import com.tvremote.ui.RemoteScreen
import com.tvremote.ui.RemoteViewModel
import com.tvremote.ui.TvRemoteTheme

class MainActivity : ComponentActivity() {

    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TvRemoteTheme {
                val current by vm.current.collectAsStateWithLifecycle()
                val device = current
                if (device == null) {
                    DevicesScreen(vm)
                } else {
                    BackHandler { vm.closeRemote() }
                    RemoteScreen(vm, device)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppForeground()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        vm.handleHardwareKey(event) || super.dispatchKeyEvent(event)
}
