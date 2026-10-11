package com.gitdrip.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gitdrip.app.ui.Appearance
import com.gitdrip.app.ui.GitDripNav
import com.gitdrip.app.ui.GitDripTheme
import com.gitdrip.app.ui.LocalAnimations
import com.gitdrip.app.ui.LocalSetSolid
import com.gitdrip.app.ui.LocalSolidSurfaces

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // re-arm every enabled schedule whenever the app opens (OEM battery managers can drop pending alarms)
        this.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { com.gitdrip.app.data.Scheduler.armAll(applicationContext); com.gitdrip.app.data.Scheduler.syncAuto(applicationContext, dropOverdue = true) } catch (_: Exception) { } }
        setContent {
            var solid by remember { mutableStateOf(Appearance.solid(this)) }
            val animate = remember { Appearance.animationsOn(this) }
            GitDripTheme {
                CompositionLocalProvider(
                    LocalSolidSurfaces provides solid, LocalAnimations provides animate,
                    LocalSetSolid provides { on -> solid = on; Appearance.setSolid(this, on) },
                ) { GitDripNav() }
            }
        }
    }
}
