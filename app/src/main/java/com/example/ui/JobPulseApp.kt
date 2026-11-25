package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.auth.authStateFlow
import com.example.auth.signOut
import com.example.data.repository.JobPulseRepository
import com.example.data.repository.RadarPreferencesStore
import com.example.notifications.JobNotificationManager
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.FiltersScreen
import com.example.ui.screens.PulseFeedScreen
import com.example.ui.screens.RadarScreen
import com.example.ui.screens.SitesScreen
import com.example.viewmodel.JobPulseViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

private enum class AppDestination(
    val route: String,
    val labelRes: Int,
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    PULSE("pulse_feed", R.string.nav_feed, R.string.feed_title, Icons.Rounded.Bolt, Icons.Outlined.Bolt),
    SITES("monitored_sites", R.string.nav_sites, R.string.sites_title, Icons.Rounded.Language, Icons.Outlined.Language),
    FILTERS("custom_filters", R.string.nav_filters, R.string.filters_title, Icons.Rounded.Tune, Icons.Outlined.Tune),
    RADAR("radar_settings", R.string.nav_radar, R.string.radar_title, Icons.Rounded.Radar, Icons.Outlined.Radar)
}

@Composable
fun JobPulseApp(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = { /* authStateFlow updates automatically */ })
    } else {
        key(user.uid) {
            AuthenticatedJobPulseShell(
                currentUserId = user.uid,
                userDisplayName = user.displayName.orEmpty(),
                userEmail = user.email.orEmpty()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedJobPulseShell(
    currentUserId: String,
    userDisplayName: String,
    userEmail: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: JobPulseViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                JobPulseViewModel(
                    repository = JobPulseRepository(db),
                    prefsStore = RadarPreferencesStore(app.applicationContext),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val sourcesState by viewModel.sourcesState.collectAsStateWithLifecycle()
    val filtersState by viewModel.filtersState.collectAsStateWithLifecycle()
    val alertsState by viewModel.alertsState.collectAsStateWithLifecycle()
    val radarPrefs by viewModel.radarPrefs.collectAsStateWithLifecycle()
    val radarTelemetry by viewModel.radarTelemetry.collectAsStateWithLifecycle()
    val selectedFeedFilter by viewModel.selectedFeedFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    var currentDestination by remember { mutableStateOf(AppDestination.PULSE) }
    var showAddSiteDialog by remember { mutableStateOf(false) }
    var showAddFilterDialog by remember { mutableStateOf(false) }
    var hasNotifPermission by remember {
        mutableStateOf(JobNotificationManager.hasNotificationPermission(context))
    }
    var pendingStartRadarAfterPermission by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted || JobNotificationManager.hasNotificationPermission(context)
        if (pendingStartRadarAfterPermission) {
            pendingStartRadarAfterPermission = false
            viewModel.setLiveRadarEnabled(context, true)
        }
    }

    val requestNotificationsAndRun: (Boolean) -> Unit = { startRadar ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission) {
            pendingStartRadarAfterPermission = startRadar
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (startRadar) {
            viewModel.setLiveRadarEnabled(context, true)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    BackHandler(enabled = currentDestination != AppDestination.PULSE) {
        currentDestination = AppDestination.PULSE
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.navigationBarsPadding()
                )
            },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(currentDestination.titleRes),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        AppDestination.entries.forEach { dest ->
                            val selected = dest == currentDestination
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentDestination = dest },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                when (currentDestination) {
                    AppDestination.SITES -> {
                        ExtendedFloatingActionButton(
                            onClick = { showAddSiteDialog = true },
                            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            text = { Text(stringResource(R.string.sites_add_custom)) },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.testTag("fab_add_site")
                        )
                    }
                    AppDestination.FILTERS -> {
                        ExtendedFloatingActionButton(
                            onClick = { showAddFilterDialog = true },
                            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            text = { Text(stringResource(R.string.filters_add)) },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.testTag("fab_add_filter")
                        )
                    }
                    else -> Unit
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        AppDestination.entries.forEach { dest ->
                            val selected = dest == currentDestination
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentDestination = dest },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag("rail_${dest.route}")
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val contentModifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 760.dp)

                    when (currentDestination) {
                        AppDestination.PULSE -> PulseFeedScreen(
                            alertsState = alertsState,
                            sourcesState = sourcesState,
                            telemetry = radarTelemetry,
                            selectedFilter = selectedFeedFilter,
                            searchQuery = searchQuery,
                            onSelectFilter = viewModel::selectFeedFilter,
                            onSearchQueryChange = viewModel::updateSearchQuery,
                            onScanNow = { viewModel.scanAllEnabledNow(context) },
                            onToggleRadar = { enable ->
                                if (enable) requestNotificationsAndRun(true)
                                else viewModel.setLiveRadarEnabled(context, false)
                            },
                            onAddCoreFour = { viewModel.addCoreFourPresets(context) },
                            onNavigateToSites = { currentDestination = AppDestination.SITES },
                            onBookmarkClick = viewModel::toggleBookmark,
                            onAppliedClick = viewModel::toggleApplied,
                            onOpenAlert = viewModel::markAlertRead,
                            onDeleteAlert = viewModel::deleteAlert,
                            modifier = contentModifier
                        )

                        AppDestination.SITES -> SitesScreen(
                            sourcesState = sourcesState,
                            scanningNow = radarTelemetry.scanningNow,
                            showAddDialog = showAddSiteDialog,
                            onDismissAddDialog = { showAddSiteDialog = false },
                            onAddSource = { name, url, cat, interval, done ->
                                viewModel.addSource(name, url, cat, interval, done)
                            },
                            onUpdateSource = { id, name, url, cat, enabled, interval, done ->
                                viewModel.updateSource(id, name, url, cat, enabled, interval, done)
                            },
                            onToggleSource = viewModel::toggleSourceEnabled,
                            onDeleteSource = viewModel::deleteSource,
                            onScanSingleSource = { source -> viewModel.scanSingleSource(context, source) },
                            onAddPreset = { preset -> viewModel.addPreset(context, preset) },
                            onAddCoreFour = { viewModel.addCoreFourPresets(context) },
                            modifier = contentModifier
                        )

                        AppDestination.FILTERS -> FiltersScreen(
                            filtersState = filtersState,
                            sourcesState = sourcesState,
                            alertsState = alertsState,
                            showAddDialog = showAddFilterDialog,
                            onDismissAddDialog = { showAddFilterDialog = false },
                            onAddFilter = { name, mode, inc, exc, workMode, minRate, targets, push, done ->
                                viewModel.addFilter(name, mode, inc, exc, workMode, minRate, targets, push, done)
                            },
                            onUpdateFilter = { id, name, enabled, mode, inc, exc, workMode, minRate, targets, push, done ->
                                viewModel.updateFilter(id, name, enabled, mode, inc, exc, workMode, minRate, targets, push, done)
                            },
                            onToggleFilter = viewModel::toggleFilterEnabled,
                            onDeleteFilter = viewModel::deleteFilter,
                            modifier = contentModifier
                        )

                        AppDestination.RADAR -> RadarScreen(
                            radarPrefs = radarPrefs,
                            telemetry = radarTelemetry,
                            hasNotificationPermission = hasNotifPermission,
                            userDisplayName = userDisplayName,
                            userEmail = userEmail,
                            onToggleLiveRadar = { enable ->
                                if (enable) requestNotificationsAndRun(true)
                                else viewModel.setLiveRadarEnabled(context, false)
                            },
                            onSelectDefaultCadence = viewModel::setDefaultPollCadence,
                            onRequestNotificationPermission = { requestNotificationsAndRun(false) },
                            onSendTestAlert = { viewModel.sendTestPushAlert(context) },
                            onSignOut = {
                                signOut(
                                    context = context,
                                    credentialManager = credentialManager,
                                    scope = scope
                                )
                            },
                            modifier = contentModifier
                        )
                    }
                }
            }
        }
    }
}
