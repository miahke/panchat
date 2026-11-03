# panchat

Android-мессенджер на базе [Bitchat Android](https://github.com/permissionlesstech/bitchat-android) с Tor-мостами и локальным qWDTT-прокси через VK TURN. Android 10 и новее; arm64-v8a, armeabi-v7a, x86_64 и x86.

## Что есть

- Собственные мосты obfs4 и WebTunnel: вставьте строки в настройках подключения.
- qWDTT WG и защищённый Rawv2, два транспортных потока, локальный SOCKS5 с паролем. Трафик идёт через VK TURN на ваш VPS, затем к релеям.
- Импорт qwdtt://config и panchan:// профилей, ручной ввод хэшей VK и встроенный вход VK.
- Мастер установки qWDTT на Debian x86_64 по SSH-паролю или ключу; панель выдачи, изменения, отключения и удаления подписок. Административный API остаётся на loopback и доступен через проверенный SSH-сеанс.
- Схема этапов и закреплённая статистика qWDTT, адаптированные из оригинального qWDTT.
- Выбор области на глобусе, роли пользователя/администратора, светлого/тёмного режима и пяти цветовых тем. Системное автозаполнение внутри приложения отключено.

Cups/OpenFlux исключён. Общее парольное шифрование хранилища и аппаратная привязка доступа не входят в этот выпуск. Сетевые секреты хранятся в Android Keystore без отдельного запроса пароля; данные приложения также зависят от защиты Android. Идентификатор пакета сохранён для обновления ранее установленных тестовых версий.

Форматы сообщений Bitchat не изменены ради туннелей. Стоковому собеседнику не нужен ваш VPS. Публичные каналы остаются публичными; транспорт не превращает их в личную переписку. Проверки совместимости и ограничения описаны в документации. Это независимый тестовый форк, не официальный выпуск Bitchat.

## Начало работы

1. Установите APK своего процессора из Releases; Pixel использует arm64-v8a.
2. Выберите область и роль. Пользователь может вставить Tor-мосты или ключ настройки от администратора.
3. Администратор открывает «Настройки мода» → «Мой сервер и подписки», сверяет SSH-отпечаток с панелью VPS и запускает установку. Нужны Debian x86_64, TUN и root либо sudo без пароля.
4. В панели подписок выдайте отдельный ключ каждому получателю. Ключ содержит параметры доступа; отправляйте его получателю защищённым способом. SSH-пароль в ключ не входит.

Исходники не содержат готовых адресов VPS, хэшей, подписок или реквизитов владельца. Свой VPS настраивается через мастер; вшивать адрес в код не требуется. Для компьютерного установщика экспортируйте комплект в приложении и запустите `python3 deploy/wizard.py`.

## Сборка и проверки

Нужны JDK 21, Android SDK и закреплённый NDK r27d. Java/Kotlin-зависимости закреплены в Gradle lockfiles; Go/Rust — в соответствующих lockfiles. Нативные библиотеки собираются из исходников; приватный ключ подписи не входит в репозиторий.

```sh
# Linux: собрать Arti в закреплённом контейнере (Docker)
tools/arti-build/rebuild-in-container.sh
# ANDROID_NDK_HOME должен указывать на NDK r27d
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  tools/network-native/build-go.sh "$abi" pt
  tools/network-native/build-go.sh "$abi" qwdtt
done
python3 tools/network-native/prepare-deployer.py --rebuild
./gradlew :app:testDebugUnitTest :wear:testDebugUnitTest :app:assembleRelease
python3 tools/network-native/verify-package.py app/build/outputs/apk/release/app-universal-release-unsigned.apk
```

Go должен удовлетворять версиям в go.mod. Для `prepare-deployer.py --rebuild` нужны Python 3.11+ и Go. Серверный комплект после сборки встроен в APK; исполнительный код после установки приложения не скачивается. APK release по умолчанию не подписан: подпишите его своим ключом и сохраняйте этот ключ для обновлений. Нельзя заменить upstream APK с другой подписью обычным обновлением.

Проверки UI с ресурсами запускаются отдельно:

```sh
./gradlew :app:testDebugUnitTest -PnetworkUiTests=true \
  --tests '*NetworkWorkspaceButtonsTest' --tests '*NetworkWorkspaceVisualTest' \
  --tests '*QwdttAdminScreenTest' --tests '*Panchat*Test'
python3 -m unittest discover -s deploy/tests
```

[Настройка сервера](docs/network/server-setup.md), [нативные компоненты](tools/network-native/README.md), [сборка Arti](tools/arti-build/README.md).

## Лицензия и происхождение

Код форка распространяется под **GNU GPL версии 3**: [LICENSE.md](LICENSE.md). Сохраняйте лицензии и уведомления компонентов. Соответствующие исходники и инструкции сборки должны сопровождать распространяемые бинарные выпуски.

Основа — Bitchat Android; исходная лицензия сохранена в [third_party/network/bitchat-original-LICENSE.md](third_party/network/bitchat-original-LICENSE.md). qWDTT и элементы его интерфейса заимствованы из [proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android); лицензии и остальные уведомления — в [third_party/network](third_party/network). Названия внутренних пакетов и протокольные идентификаторы сохраняются для совместимости.
