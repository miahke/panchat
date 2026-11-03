# Storage integration inventory

Static source scan, not device/user data. These sites are candidates requiring a manual storage/lifecycle classification before claiming password-bound application storage. Presence of a path is not proof that private data is written there. Existing automatic Keystore protection does not replace the password-bound vault.

| Source | Lines at inventory time |
|---|---|
| `app/src/main/java/com/bitchat/android/crypto/EncryptionService.kt` | 65,514 |
| `app/src/main/java/com/bitchat/android/features/file/FileUtils.kt` | 41,96,202,203,204,245,256,288,298,301,303,310,312,313,318,319,322,324,331,361,400 |
| `app/src/main/java/com/bitchat/android/features/media/ImageUtils.kt` | 33,58 |
| `app/src/main/java/com/bitchat/android/features/voice/LiveVoiceManager.kt` | 477 |
| `app/src/main/java/com/bitchat/android/features/voice/LiveVoicePreferences.kt` | 12,17 |
| `app/src/main/java/com/bitchat/android/features/voice/VoiceRecorder.kt` | 40,54 |
| `app/src/main/java/com/bitchat/android/geohash/GeohashBookmarksStore.kt` | 46 |
| `app/src/main/java/com/bitchat/android/hotspot/HotspotManager.kt` | 94 |
| `app/src/main/java/com/bitchat/android/identity/SecureIdentityStateManager.kt` | 63 |
| `app/src/main/java/com/bitchat/android/net/TorPreferenceManager.kt` | 15,22,29 |
| `app/src/main/java/com/bitchat/android/net/route/NetworkRuntime.kt` | 48,50,112,115 |
| `app/src/main/java/com/bitchat/android/net/route/NetworkSecretStore.kt` | 51 |
| `app/src/main/java/com/bitchat/android/nostr/GeohashAliasRegistry.kt` | 19 |
| `app/src/main/java/com/bitchat/android/nostr/GeohashConversationRegistry.kt` | 20 |
| `app/src/main/java/com/bitchat/android/nostr/PoWPreferenceManager.kt` | 43 |
| `app/src/main/java/com/bitchat/android/nostr/RelayDirectory.kt` | 126,129,154 |
| `app/src/main/java/com/bitchat/android/onboarding/BackgroundLocationPreferenceManager.kt` | 13,18 |
| `app/src/main/java/com/bitchat/android/onboarding/BatteryOptimizationPreferenceManager.kt` | 18,24,30 |
| `app/src/main/java/com/bitchat/android/onboarding/PermissionManager.kt` | 26 |
| `app/src/main/java/com/bitchat/android/service/MeshServicePreferences.kt` | 14 |
| `app/src/main/java/com/bitchat/android/service/PeerAvailabilityNotifier.kt` | 43 |
| `app/src/main/java/com/bitchat/android/services/ConversationRepository.kt` | 7,384,1518 |
| `app/src/main/java/com/bitchat/android/services/MessageRetentionService.kt` | 36,37 |
| `app/src/main/java/com/bitchat/android/spam/SpamFilterPreferences.kt` | 45 |
| `app/src/main/java/com/bitchat/android/ui/DataManager.kt` | 19 |
| `app/src/main/java/com/bitchat/android/ui/debug/DebugPreferenceManager.kt` | 31 |
| `app/src/main/java/com/bitchat/android/ui/media/FileViewerDialog.kt` | 138 |
| `app/src/main/java/com/bitchat/android/ui/media/ImagePickerButton.kt` | 68 |
| `app/src/main/java/com/bitchat/android/ui/theme/ChatUiMode.kt` | 39,45 |
| `app/src/main/java/com/bitchat/android/ui/theme/ThemePreference.kt` | 32,38 |
| `app/src/main/java/com/bitchat/android/util/ApkRateLimitStore.kt` | 15 |
| `app/src/main/java/com/bitchat/android/util/GitHubReleaseClient.kt` | 82 |
| `app/src/main/java/com/bitchat/android/util/UniversalApkManager.kt` | 54,55,57,58,99,139,165,235,275,291,705,759,760,914,949 |
| `app/src/main/java/info/guardianproject/arti/ArtiProxy.kt` | 127 |

Entry points: BitchatApplication initializes identity, conversation history, network runtime and mesh before an unlock gate. MainActivity, NetworkSettingsActivity, VkAuthActivity, HotspotActivity, MeshForegroundService, BootCompletedReceiver, ConversationNotificationReceiver and any WorkManager/download entry point must respect the cold-start/UI distinction.

User chose background receipt: UI hiding retains already-open keys and transports. Cold process startup must never access the protected repositories or start protected work without password unlock. Notification actions and deep links must not unlock UI. Explicit full lock revokes keys and transport access.
