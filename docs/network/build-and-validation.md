# Сборка и проверка Tor + qWDTT

Текущий выпуск: transports.9 (50), Android 10+ / min API 29. Cups/OpenFlux удалён. Нативные компоненты APK: Arti, obfs4/WebTunnel transport и qWDTT. ABI: arm64-v8a, armeabi-v7a, x86_64, x86.

Требуются JDK 21, Android SDK из gradle/libs.versions.toml, NDK 27.3.13750724, Go 1.27.3. Для пересборки Arti/PT также нужны зафиксированные Rust/cargo-ndk; версии — tools/network-native/versions.lock.json.

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk
# Если пересобираются все нативные компоненты:
tools/network-native/build-all.sh
# Комплект подготовки собственного Debian x86_64:
python3 tools/network-native/prepare-deployer.py --rebuild
./gradlew :app:assembleRelease :wear:assembleDebug testDebugUnitTest clientRewriteContractTest
./gradlew -PnetworkUiTests=true :app:testDebugUnitTest --tests '*NetworkWorkspaceButtonsTest' --tests '*NetworkSettingsColdStartTest' --tests '*NetworkWorkspaceVisualTest' --tests '*MessagesListFollowTest'
python3 -m unittest discover -s deploy/tests -v
python3 -m unittest discover -s tools/network-native/tests -v
tools/arti-build/verify-checksums.sh
# Общий lint имеет известные ошибки исходного репозитория:
./gradlew lintDebug
```

Release APK необходимо подписать собственным ключом и проверить apksigner, ZIP/native alignment и tools/network-native/verify-package.py для соответствующего ABI. Закрытые ключи и реальные реквизиты не включаются в исходники/экспорт. Для Pixel 6 Pro / 7 Pro используется arm64-v8a APK. Новая подпись форка не заменяет стоковое приложение: applicationId com.bitchat.transport устанавливается рядом со стоком.

Миграция после удаления Cups, результаты и ограничения — remove-cups.md. Серверная подготовка — server-setup.md и deploy/README.md. Предыдущие отчёты сетевого этапа являются историческими: наличие там Cups не означает его поддержку текущей сборкой.

Физическая приёмка Android 10/17, radio mesh и обмен со стоковым Bitchat остаются отдельными проверками. Не использовать реальные сообщения, аккаунты и геолокацию в тестах. В текущем этапе форматы Noise/Nostr/сообщений не менялись.
