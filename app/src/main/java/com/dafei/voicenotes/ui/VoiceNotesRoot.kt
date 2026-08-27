package com.dafei.voicenotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dafei.voicenotes.VoiceNotesApp
import com.dafei.voicenotes.ui.detail.DiaryDetailScreen
import com.dafei.voicenotes.ui.list.DiaryListScreen
import com.dafei.voicenotes.ui.record.RecordScreen
import com.dafei.voicenotes.ui.settings.SettingsScreen
import com.dafei.voicenotes.ui.theme.VoiceNotesTheme

private object Routes {
    const val Main = "main"
    const val Detail = "detail/{id}"
    fun detail(id: Long) = "detail/$id"
}

@Composable
fun VoiceNotesRoot(app: VoiceNotesApp) {
    VoiceNotesTheme {
        val navController = rememberNavController()
        var mainTab by remember { mutableIntStateOf(1) }
        NavHost(
            navController = navController,
            startDestination = Routes.Main,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Routes.Main) {
                MainShell(
                    app = app,
                    tab = mainTab,
                    onTabChange = { mainTab = it },
                    onOpenDetail = { id ->
                        mainTab = 0
                        navController.navigate(Routes.detail(id))
                    }
                )
            }
            composable(
                route = Routes.Detail,
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                DiaryDetailScreen(
                    app = app,
                    entryId = id,
                    onBack = {
                        mainTab = 0
                        navController.popBackStack()
                    },
                    onNavigateTo = { newId ->
                        navController.navigate(Routes.detail(newId)) {
                            popUpTo(entry.destination.id) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MainShell(
    app: VoiceNotesApp,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { onTabChange(0) },
                    icon = { Icon(Icons.Default.Book, contentDescription = null) },
                    label = { Text("记事") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { onTabChange(1) },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                    label = { Text("录制") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { onTabChange(2) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> DiaryListScreen(app = app, onOpenDetail = onOpenDetail)
                1 -> RecordScreen(app = app, onSaved = onOpenDetail)
                2 -> SettingsScreen(app = app)
            }
        }
    }
}
