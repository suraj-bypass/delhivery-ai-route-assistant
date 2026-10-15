package com.routeassistant.app

import android.content.Intent
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
        handleSharedText(intent)
        setContent {
            MaterialTheme {
                Surface {
                    AppNav(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText(intent)
    }

    /**
     * User-authorized import via Android Share (ACTION_SEND, text/plain).
     * Same behaviour as Bulk Paste: saves + dedupes via bulkImport, then the
     * ViewModel auto-flow geocodes + optimises. Blank text is ignored silently.
     * The intent action is cleared after handling so rotation / recreation
     * does not re-import the same text.
     */
    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        intent.action = null
        intent.removeExtra(Intent.EXTRA_TEXT)
        viewModel.bulkImport(text)
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
