# Mobilix Launcher

Свободный лаунчер Minecraft: Java Edition для Android (arm64). Работает без сторонних лаунчеров: собственный запуск Java 21, LWJGL, MobileGlues (OpenGL поверх OpenGL ES).

Возможности: Vanilla, Fabric и Forge, каталог модов, ресурспаков и шейдеров (Modrinth), экранное управление с редактором раскладки, 3D-скин, настройки памяти и JVM, русский и английский язык.

Данные не собираются: [политика конфиденциальности](PRIVACY.md).

## Лицензия
Форки и модификации разрешены при условии заметного указания оригинала: «Основано на Mobilix Launcher for Minecraft, автор Kumolix» со ссылкой на этот репозиторий (в начале README форка и внутри самого приложения). Подробности в [LICENSE](LICENSE).

Forks and modifications are welcome as long as the original is clearly credited: “Based on Mobilix Launcher for Minecraft by Kumolix” with a link to this repository (at the top of the fork's README and inside the app). See [LICENSE](LICENSE).

## Установка
Готовые APK лежат в разделе Releases. Либо откройте вкладку Actions, выберите последнюю сборку и скачайте артефакт `Mobilix-launcher-v-<версия>`, распакуйте zip и установите APK.

## Сборка
Сборка идёт на GitHub Actions (NDK для нативных библиотек, затем Gradle `assembleRelease`). APK подписывается постоянным ключом из репозитория, поэтому новая версия ставится поверх старой.

Telegram: https://t.me/mobilix_launcher
