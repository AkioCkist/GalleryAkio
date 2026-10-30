package com.example.galleryakio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import com.example.galleryakio.ui.GalleryNavHost
import com.example.galleryakio.ui.components.GlassOverlayHost
import com.example.galleryakio.ui.components.GridLayout
import com.example.galleryakio.ui.components.LocalGridLayout
import com.example.galleryakio.ui.GalleryViewModel
import com.example.galleryakio.ui.theme.CqMono
import com.example.galleryakio.ui.theme.GalleryTheme
import com.example.galleryakio.ui.theme.LocalCustomColors

class MainActivity : ComponentActivity() {
    private val viewModel: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Start the MediaStore query now, in parallel with inflating the first frame.
        if (hasMediaAccess(this)) viewModel.start()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            GalleryTheme(theme = settings.theme, darkMode = settings.darkMode) {
                val background = MaterialTheme.colorScheme.background
                DisposableEffect(settings.darkMode, background) {
                    val style = if (settings.darkMode) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    window.decorView.setBackgroundColor(background.toArgb())
                    onDispose { }
                }
                val gridLayout = remember(settings.gridStyle, settings.gridSpacing, settings.showDateHeaders, settings.showVideoDuration) {
                    GridLayout(
                        style = settings.gridStyle,
                        spacing = settings.gridSpacing.resolve(settings.gridStyle),
                        showDateHeaders = settings.showDateHeaders,
                        showVideoDuration = settings.showVideoDuration,
                        onColumnsChange = viewModel.settingsRepo::setGridColumns,
                    )
                }
                CompositionLocalProvider(LocalGridLayout provides gridLayout) {
                    GlassOverlayHost {
                        PermissionGate(onGranted = viewModel::start) {
                            GalleryNavHost(viewModel)
                        }
                    }
                }
            }
        }
    }
}

private val mediaPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun hasMediaAccess(context: Context): Boolean {
    val full = mediaPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    // Android 14 "selected photos only" still lets the gallery show what the user picked.
    val partial = Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    ) == PackageManager.PERMISSION_GRANTED
    return full || partial
}

@Composable
private fun PermissionGate(onGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun hasAccess() = hasMediaAccess(context)

    var granted by remember { mutableStateOf(hasAccess()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasAccess()
    }

    if (granted) {
        LaunchedEffect(Unit) { onGranted() }
        content()
    } else {
        PermissionScreen(onRequest = {
            val request = if (Build.VERSION.SDK_INT >= 34) mediaPermissions + Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            else mediaPermissions
            launcher.launch(request)
        })
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.secure),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text("GALLERYAKIO", fontFamily = CqMono, fontSize = 32.sp, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your photos, your memories, your privacy",
            textAlign = TextAlign.Center,
            color = LocalCustomColors.current.unselectedItem,
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onRequest,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("ALLOW ACCESS", fontFamily = CqMono, fontSize = 16.sp)
        }
    }
}
