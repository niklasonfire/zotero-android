package org.zotero.android.screens.dashboard

import android.net.Uri
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.accompanist.navigation.animation.rememberAnimatedNavController
import org.zotero.android.architecture.EventBusConstants.FileWasSelected.CallPoint
import org.zotero.android.architecture.ui.CustomLayoutSize
import org.zotero.android.uicomponents.systemui.SolidStatusBar
import java.io.File

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DashboardScreen(
    onBack: () -> Unit,
    onPickFile: (callPoint: CallPoint) -> Unit,
    onOpenFile: (file: File, mimeType: String) -> Unit,
    onShowPdf: (file: File) -> Unit,
    onOpenWebpage: (uri: Uri) -> Unit,
    viewModel: DashboardViewModel,
) {
    LaunchedEffect(key1 = viewModel) {
        viewModel.init()
    }

    SolidStatusBar()

    val rightPaneNavController = rememberAnimatedNavController()
    val rightPaneDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val rightPaneNavigation = remember(rightPaneNavController) {
        Navigation(rightPaneNavController, rightPaneDispatcher)
    }

    val layoutType = CustomLayoutSize.calculateLayoutType()
    if (layoutType.isTablet()) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(0.3f)) {
                LeftPaneNavigation(navigateToAllItems = rightPaneNavigation::toAllItems)
            }
            Box(modifier = Modifier.weight(0.7f)) {
                FullScreenOrRightPaneNavigation(
                    onPickFile = onPickFile,
                    onOpenFile = onOpenFile,
                    onShowPdf = onShowPdf,
                    onOpenWebpage = onOpenWebpage,
                    navController = rightPaneNavController,
                    navigation = rightPaneNavigation
                )
            }

        }
    } else {
        FullScreenOrRightPaneNavigation(
            navController = rightPaneNavController,
            navigation = rightPaneNavigation,
            onPickFile = onPickFile,
            onOpenWebpage = onOpenWebpage,
            onOpenFile = onOpenFile,
            onShowPdf = onShowPdf
        )
    }
}


