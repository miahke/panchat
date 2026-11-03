package com.bitchat.android.ui.network

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bitchat.android.net.route.*
import com.bitchat.android.net.vk.VkAuthActivity
import com.bitchat.android.onboarding.NetworkSetupPreferences
import com.bitchat.android.ui.GeohashPickerActivity
import com.bitchat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkSettingsActivity : ComponentActivity() {
    private val model: NetworkSettingsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NetworkSettingsViewModel(RuntimeNetworkSettingsOperations(applicationContext)) as T
        }
    }
    private val vkAuth=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){ result ->
        model.vkLoginReturned(result.resultCode==RESULT_OK)
    }
    private val regionPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val region = result.data?.getStringExtra(GeohashPickerActivity.EXTRA_RESULT_GEOHASH)
            if (result.resultCode == RESULT_OK && region != null)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        NetworkSetupPreferences.from(applicationContext).setRegion(region)
                    }
                    com.bitchat.android.geohash.LocationChannelManager.getInstance(
                            applicationContext
                        )
                        .selectManual(
                            com.bitchat.android.geohash.GeohashChannel(level(region.length), region)
                        )
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null)
            when (intent.getStringExtra("setup_action")) {
                "import" -> model.goImport()
                "tor" -> model.newProfile(NetworkRoute.TOR)
            }
        lifecycleScope.launch {
            val epoch = NetworkRuntime.wipeEpoch.value
            NetworkRuntime.wipeEpoch.first { it != epoch }
            model.clearTransient()
            finish()
        }
        setContent {
            BitchatTheme {
                val ui by model.state.collectAsState()
                val profiles by NetworkRuntime.profiles.collectAsState()
                val status by NetworkRuntime.status.collectAsState()
                val loaded by NetworkRuntime.loaded.collectAsState()
                val initError by NetworkRuntime.initializationError.collectAsState()
                val loggedIn =
                    if (loaded) NetworkRuntime.vkSession.loggedIn.collectAsState().value else false
                val events by NetworkDiagnostics.log.events.collectAsState()
                LaunchedEffect(loggedIn, ui.awaitingVkLogin) {
                    if (loggedIn && ui.awaitingVkLogin) model.vkLoginCompleted()
                }
                LaunchedEffect(
                    status.generation,
                    status.state,
                    status.route,
                    ui.page,
                    ui.pendingSubscription,
                    ui.operation,
                ) {
                    model.resumePendingSubscription(NetworkRuntime.status.value)
                }
                val back = {
                    if (model.state.value.page == NetworkPage.HOME) finish() else model.back()
                }
                BackHandler(onBack = back)
                NetworkSettingsWorkspace(
                    ui.copy(
                        profiles = profiles,
                        connection = status,
                        loaded = loaded,
                        initializationError = initError,
                        loggedIn = loggedIn,
                        torProgress =
                            events
                                .lastOrNull { it.route == NetworkRoute.TOR && it.progress != null }
                                ?.progress,
                    ),
                    NetworkSettingsActions(
                        back = back,
                        add = model::goImport,
                        preferences = model::openPreferences,
                        newProfile = model::newProfile,
                        edit = model::editProfile,
                        requestDelete = model::requestDelete,
                        confirmDelete = model::confirmDelete,
                        dismissDelete = model::dismissDelete,
                        connect = model::connectSaved,
                        disconnect = model::disconnect,
                        diagnostics = {
                            startActivity(
                                Intent(this, NetworkDiagnosticsActivity::class.java)
                                    .putExtra("route", it.name)
                            )
                        },
                        importText = model::updateImportText,
                        analyze = model::analyzeImport,
                        choose = model::chooseImport,
                        changeDraft = { next -> model.updateDraft { next } },
                        secret = model::updateSecret,
                        advanced = model::toggleAdvanced,
                        save = model::connectDraft,
                        login = {
                            if(!model.state.value.awaitingVkLogin){
                                model.awaitingVkLogin()
                                vkAuth.launch(Intent(this, VkAuthActivity::class.java))
                            }
                        },
                        logout = model::logout,
                        hash = model::generateHash,
                        cancel = model::cancelOperation,
                        cancelPending = model::cancelPendingSubscription,
                        discard = model::discardDraft,
                        keepEditing = model::keepEditing,
                        role = model::changeRole,
                        region = {
                            regionPicker.launch(
                                Intent(this, GeohashPickerActivity::class.java)
                                    .putExtra(GeohashPickerActivity.EXTRA_MANUAL_ONLY, true)
                            )
                        },
                        mesh = {
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    NetworkSetupPreferences.from(applicationContext)
                                        .setInternetOnly(false)
                                }
                                com.bitchat.android.service.InternetForegroundService.stop(
                                    applicationContext
                                )
                                com.bitchat.android.wifiaware.WifiAwareController.initialize(
                                    applicationContext,
                                    com.bitchat.android.ui.debug.DebugPreferenceManager
                                        .getWifiAwareEnabled(false),
                                )
                                startActivity(
                                    Intent(
                                            this@NetworkSettingsActivity,
                                            com.bitchat.android.MainActivity::class.java,
                                        )
                                        .addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        )
                                )
                            }
                        },
                        deployer = {
                            startActivity(Intent(this, ServerPreparationActivity::class.java))
                        },
                    ),
                )
            }
        }
    }

    private fun level(length: Int) =
        when (length) {
            in 1..2 -> com.bitchat.android.geohash.GeohashChannelLevel.REGION
            in 3..4 -> com.bitchat.android.geohash.GeohashChannelLevel.PROVINCE
            5 -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
            6 -> com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
            7 -> com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
            else -> com.bitchat.android.geohash.GeohashChannelLevel.BUILDING
        }
}
