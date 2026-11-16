prerelease: 1.9.5.2
What's new
- New renderer "Zink + Turnip (Adreno)": Mesa Turnip Vulkan driver for Adreno GPUs, selectable in Settings → Game → Renderer (experimental, arm64, Adreno only)
- Fixed: Zink crash on launch (OSMesa context is now made current before its pixel store is set); MobileGlues + ANGLE now draws to the screen (EGL goes through MobileGlues into ANGLE)
- Experimental "New interface" (Settings → Appearance): rounded cards, animated background in theme colors, floating bottom bar, redesigned pages and dialogs, collapsible settings groups; works with custom themes
- The log of the previous game run is kept and shown right in the launcher (Latest / Previous / Copy), with the native crash reason; mod auto-update now runs on every launch and finds the conflicting mod by its id; mipmap buttons fit one row
- Code split into focused files (no behavior change)
- Settings > Special: "Mod downloads" Auto / Manual (manual lets you pick the exact file version)
- Settings > Special: "Hide system buttons in game" (the Layout editor opens by holding Esc / "II" in Bedrock for 5 s); on-screen keyboard now accepts input from keyboards that send digits and dots as key presses (typing a server IP); closing the game tab is no longer treated as a crash
- Settings > Special: "Bedrock controls" mode (tap = attack, hold = mine, Pocket Edition style layout with its own editable buttons)
- Renderers: Renderlix profiles (Auto / Standard / Sodium+Iris / Custom: OpenGL version, shader errors, compute shaders, shader cache, FSR 1)
- Settings > Game: renderer choice (Auto / MobileGlues / MobileGlues + ANGLE / gl4es); versions before 1.17 always use gl4es
- Minecraft 26.1+ (Java 25): a separate Java 25 runtime is installed automatically; rotating the screen no longer interrupts a download
- Experimental: 32-bit (arm) and x86 / x86_64 devices in the same APK (versions up to 1.16.5, gl4es)
- 1.17–1.18.2: LWJGL 3.3.3 is used (fixes launch crash after running other versions)
- Fabric: auto-update now rolls back a mod that breaks another installed one (e.g. Sodium 0.6.13 vs Iris 1.8.1); Renderlix settings removed (Sodium/Iris profile is applied automatically); renderer buttons wrap to a second row
- Graphics presets also lower simulation and entity distance (and chunk fade-in on 1.21+) on weak presets; the render thread gets a higher priority
- New renderer Zink (Mesa, OpenGL over Vulkan) for 1.17+ on arm64: Settings → Game → Renderer (experimental, a bit slower than MobileGlues on some devices, can help where the GLES driver is weak)
- Version list loads in parallel and is cached (shows vanilla immediately); skin preview keeps rotating; low-memory JVM profile for devices under 3.5 GB; LWJGL debug output disabled

Что нового?
- Новый рендерер «Zink + Turnip (Adreno)»: Vulkan-драйвер Mesa Turnip для видеочипов Adreno, выбор в Настройки → Игра → Рендерер (экспериментально, arm64, только Adreno)
- Исправлено: вылет Zink при запуске; MobileGlues + ANGLE теперь выводит картинку на экран
- Экспериментальный «Новый интерфейс» (Настройки → Оформление): скруглённые карточки, живой фон в цветах темы, плавающая нижняя панель, новые страницы и диалоги, настройки в сворачиваемых группах; работает со своими темами
- Лог прошлого запуска сохраняется и виден в самом лаунчере (Последний / Предыдущий / Копировать) вместе с причиной вылета; автообновление модов работает при каждом запуске и находит конфликтующий мод по id; кнопки мипмапов в одну строку
- Код разбит на отдельные файлы (поведение не менялось)
- Настройки → Специальное: «Скачивание модов» Авто / Вручную (вручную можно выбрать нужную версию файла)
- Настройки → Специальное: «Скрыть системные кнопки в игре» (раскладка открывается удержанием Esc / «II» в Bedrock 5 с); экранная клавиатура принимает ввод от клавиатур, которые присылают цифры и точки как нажатия клавиш (ввод IP сервера); закрытие вкладки игры больше не считается вылетом
- Настройки → Специальное: режим «Управление Bedrock» (касание — удар, удержание — добыча, раскладка как в Pocket Edition со своими настраиваемыми кнопками)
- Рендеры: профили Renderlix (Авто / Стандарт / Sodium+Iris / Свой: версия OpenGL, ошибки шейдеров, compute-шейдеры, кэш шейдеров, FSR 1)
- Настройки → Игра: выбор рендерера (Авто / MobileGlues / MobileGlues + ANGLE / gl4es); 1.13–1.16.5 теперь можно запускать на MobileGlues; может помочь на Mali и старых Adreno
- Экспериментально: 32-битные (arm) и x86 / x86_64 устройства в том же APK (версии до 1.16.5, gl4es)
- Minecraft 26.1+ (Java 25): отдельная Java 25 ставится сама; поворот экрана больше не прерывает скачивание
