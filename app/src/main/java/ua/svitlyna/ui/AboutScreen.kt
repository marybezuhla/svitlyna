package ua.svitlyna.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.svitlyna.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    padding: PaddingValues = PaddingValues(0.dp),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showLicences by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // No browser on the phone: nothing happens instead of a crash.
        runCatching { context.startActivity(intent) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(R.string.section_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, androidx.compose.ui.res.stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        item {
            // App Header & Interactive Easter Egg Trigger with actual App Logo
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFF113F6B)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        // The icon's circle is 72 of its 108 units: drawn at 1.5× so it fills the circle.
                        modifier = Modifier.requiredSize(138.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    androidx.compose.ui.res.stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    androidx.compose.ui.res.stringResource(
                        R.string.version_format,
                        ua.svitlyna.BuildConfig.VERSION_NAME,
                        ua.svitlyna.BuildConfig.VERSION_CODE
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    androidx.compose.ui.res.stringResource(R.string.app_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // The author's letter and how to support Ukraine, as in the author's other apps
        item { AuthorLetter() }
        item { SupportUkraine(open = { openUrl(it) }, modifier = Modifier.padding(top = 8.dp)) }

        item {
            Text(
                androidx.compose.ui.res.stringResource(R.string.about_developer_project),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp)
            )
        }

        item {
            OutlinedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AboutLinkRow(
                        icon = Icons.Outlined.Code,
                        title = androidx.compose.ui.res.stringResource(R.string.about_source_label),
                        subtitle = "github.com/marybezuhla/svitlyna"
                    ) { openUrl("https://github.com/marybezuhla/svitlyna") }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    AboutLinkRow(
                        icon = Icons.Outlined.VerifiedUser,
                        title = androidx.compose.ui.res.stringResource(R.string.about_license_label),
                        subtitle = androidx.compose.ui.res.stringResource(R.string.sv_licence_line)
                    ) { showLicences = true }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // The interface comes from Iris Gallery: credited here, as its licence asks.
                    AboutLinkRow(
                        icon = Icons.Outlined.Psychology,
                        title = androidx.compose.ui.res.stringResource(R.string.sv_based_on_iris),
                        subtitle = "Iris Gallery · Mohamad Oday · Apache 2.0"
                    ) { openUrl("https://github.com/MohamadOday/iris-gallery") }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    AboutLinkRow(
                        icon = Icons.Outlined.Language,
                        title = androidx.compose.ui.res.stringResource(R.string.sv_certificate),
                        subtitle = "53027b1a87085eb9e40a74820ff2aa067dd3350a0e3010a8f6c559e04a628630"
                    ) {
                        // Copies the fingerprint, to compare with the one on the project page.
                        runCatching {
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("SHA-256", "53027b1a87085eb9e40a74820ff2aa067dd3350a0e3010a8f6c559e04a628630"))
                        }
                    }
                }
            }
        }

        item {
            Text(
                androidx.compose.ui.res.stringResource(R.string.about_general_info),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp)
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppInfoItem(
                        androidx.compose.ui.res.stringResource(R.string.about_arch_title),
                        androidx.compose.ui.res.stringResource(R.string.about_arch_desc)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    AppInfoItem(
                        androidx.compose.ui.res.stringResource(R.string.about_ui_title),
                        androidx.compose.ui.res.stringResource(R.string.about_ui_desc)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    AppInfoItem(
                        androidx.compose.ui.res.stringResource(R.string.about_img_engine_title),
                        androidx.compose.ui.res.stringResource(R.string.about_img_engine_desc)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    AppInfoItem(
                        androidx.compose.ui.res.stringResource(R.string.about_vid_engine_title),
                        androidx.compose.ui.res.stringResource(R.string.about_vid_engine_desc)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    AppInfoItem(
                        androidx.compose.ui.res.stringResource(R.string.about_features_title),
                        androidx.compose.ui.res.stringResource(R.string.about_features_desc)
                    )
                }
            }
        }
    }
    }

    if (showLicences) {
        // Svitlyna: the notices and full licence texts, inside the app (no internet needed).
        val text = remember {
            val names = runCatching { context.assets.list("licences")?.sorted() }.getOrNull().orEmpty()
            // The notice first, then GPL-3.0, then each library's licence.
            val ordered = listOf("NOTICE.md", "LICENSE").filter { it in names } + names.filter { it != "NOTICE.md" && it != "LICENSE" }
            ordered.joinToString("\n\n────────\n\n") { name ->
                runCatching { context.assets.open("licences/$name").bufferedReader().use { it.readText() } }.getOrDefault("")
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLicences = false },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showLicences = false }) { Text(androidx.compose.ui.res.stringResource(R.string.action_close)) } },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.sv_licences)) },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                )
            },
        )
    }
}

@Composable
private fun AppInfoItem(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
