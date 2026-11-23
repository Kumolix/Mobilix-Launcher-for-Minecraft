version: 1.9.7.4
What's new
- Mod dependencies now respect the exact version a mod asks for (for example Iris and its Sodium): a wrong installed version is replaced instead of being skipped
- Fixed: Minecraft 26.3 crashed on start with "Failed to locate library: libjemalloc.so": LWJGL now uses the system memory allocator
- Fixed: old Minecraft (1.12.2 and other legacy versions) showed the main menu shifted to the top right when the render resolution was below 100%

RU: Что нового
- Зависимости модов учитывают точную версию, которую просит мод (например, Iris и его Sodium): неподходящая установленная версия заменяется, а не пропускается
- Исправлено: Minecraft 26.3 вылетал при запуске с «Failed to locate library: libjemalloc.so»: LWJGL теперь использует системный аллокатор памяти
- Исправлено: в старых версиях Minecraft (1.12.2 и других) главное меню было сдвинуто вправо вверх, если разрешение рендера ниже 100%
