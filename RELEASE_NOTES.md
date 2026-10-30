version: 1.8.37

**EN: What's new**
- Mipmap levels setting (Settings > Game): Auto / 0-4. On old GPUs (Adreno 6xx) mipmaps made the world run at ~1 FPS; Auto detects the GPU after the first launch and sets 0 once. Fixes very low FPS on Galaxy A52-class phones
- Virtual mouse: a "Mouse" button in the controls turns on a cursor you move with one finger (tap = left click), with LMB / RMB (hold) and scroll buttons; two fingers scroll lists and settings. Split stacks, move single items, scroll settings
- Sign in with Microsoft (experimental): device-code login for your own Minecraft account; works once Mojang approves the app registration
- More memory by default for 1.17+ versions
- Diagnostics: FPS, GPU info and per-frame timing (GPU vs game) are written to game.log, GC log to gc.log; when FPS is below 15 a render-thread profile is added to game.log

**RU: Что нового?**
- Настройка мипмапов (Настройки > Игра): Авто / 0–4. На старых видеочипах (Adreno 6xx) мипмапы роняли FPS в мире до ~1; «Авто» определяет видеочип после первого запуска и один раз ставит 0. Исправляет очень низкий FPS на телефонах уровня Galaxy A52
- Виртуальная мышь: кнопка «Мышь» в управлении включает курсор, который двигается одним пальцем (касание = левый клик), с кнопками ЛКМ / ПКМ (удержание) и прокрутки; два пальца прокручивают списки и настройки. Можно делить стаки, переносить по одному предмету, листать настройки
- Вход через Microsoft (экспериментально): вход по коду для своего аккаунта Minecraft; заработает после одобрения регистрации приложения Mojang
- Больше памяти по умолчанию для версий 1.17+
- Диагностика: FPS, данные о видеочипе и время кадра (GPU или игра) пишутся в game.log, журнал сборщика мусора в gc.log; при FPS ниже 15 в game.log добавляется профиль потока рендера
