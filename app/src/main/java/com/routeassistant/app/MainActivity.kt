package com.routeassistant.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.routeassistant.app.ui.screens.OcrConfirmScreen
import com.routeassistant.app.ui.screens.ParcelListScreen
import com.routeassistant.app.ui.screens.RouteScreen
import com.routeassistant.app.ui.viewmodel.ParcelViewModel

object Routes {
    const val PARCELS = "parcels"
    const val OCR_CONFIRM = "ocr_confirm"
    const val ROUTE = "route"
}

class MainActivity : ComponentActivity() {
    private val viewModel: ParcelViewModel by viewModels { ParcelViewModel.Factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    AppNav(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun AppNav(viewModel: ParcelViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.PARCELS) {
        composable(Routes.PARCELS) {
            ParcelListScreen(
                viewModel = viewModel,
                onOpenOcrConfirm = { nav.navigate(Routes.OCR_CONFIRM) },
                onOpenRoute = { nav.navigate(Routes.ROUTE) }
            )
        }
        composable(Routes.OCR_CONFIRM) {
            OcrConfirmScreen(
                viewModel = viewModel,
                onDone = { nav.popBackStack() },
                onCancel = {
                    viewModel.clearOcrCandidates()
                    nav.popBackStack()
                }
            )
        }
        composable(Routes.ROUTE) {
            RouteScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() }
            )
        }
    }
}
