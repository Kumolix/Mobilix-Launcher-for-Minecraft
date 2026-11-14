version: 1.9.4

**EN: What's new**
- Crash helper: after a crash the launcher finds suspect mods (crash report, log, hs_err) and offers automatic or manual fix (mods are renamed to .disabled and can be restored); installing Sodium/Iris suggests the Renderlix profile
- Renderlix: renderer profile in Settings (Standard / Sodium + Iris) - the launcher now makes MobileGlues read its config (OpenGL 4.5, compute shaders, shader error ignoring, shader cache)
- Catalog: page buttons (1 2 3 4 5 ‹ ›) under results; CurseForge results show the listed / not listed marker; source button centered and collapses with the tabs; the catalog source setting moved from Settings into the catalog
- Modpacks: new "Modpacks" tab in the catalog (CurseForge and Modrinth) and import of CurseForge pack .zip files; mods whose authors disabled launcher downloads are listed for manual download
- Memory: JVM metaspace/code cache are capped; loader installer heap = game heap + 512 MB (limited to 55% of RAM)
- Catalog: "version" and "loader" buttons next to Import; downloaded items are grouped by loader and show "Loader • Version" (e.g. Fabric • 1.12.2)
- Catalog: CurseForge as a second source (Source: Modrinth / CurseForge); mods, resource packs, shaders, datapacks; required dependencies are downloaded automatically
- Quilt and NeoForge: install from Versions (Quilt for 1.14+ via Quilt meta, NeoForge for 1.20.2+), catalog filter by loader, support in Modrinth packs (.mrpack) and builds
- Landscape layout: skin on the left, menu above Play and version buttons on the right (bottom menu); with the side menu it stays on the left
- OF button next to Forge versions: installs OptiFine as a mod into the Forge profile (mirror BMCLAPI)

**RU: Что нового**
- Память: ограничены метаспейс и кэш кода JVM; куча установщика загрузчика = память игры + 512 МБ (не более 55% ОЗУ)
- Модпаки: вкладка «Модпаки» в каталоге (CurseForge и Modrinth) и импорт .zip-сборок CurseForge; моды, которые автор запретил качать в лаунчерах, выводятся списком для ручной загрузки
- Renderlix: профиль рендера в настройках (Стандарт / Sodium + Iris): лаунчер теперь заставляет MobileGlues читать свой конфиг (OpenGL 4.5, compute-шейдеры, игнор ошибок шейдеров, кэш шейдеров)
- Помощник после вылета: лаунчер находит подозрительные моды (отчёт о вылете, лог, hs_err) и предлагает исправить автоматически или вручную (моды переименовываются в .disabled, их можно вернуть); при установке Sodium/Iris предлагается профиль Renderlix
- Каталог: кнопки страниц (1 2 3 4 5 ‹ ›) под результатами; у CurseForge тоже есть пометка «заявлен / не заявлен»; кнопка источника по центру и сворачивается со вкладками; выбор источника каталога перенесён из настроек в сам каталог
- Каталог: кнопки «версия» и «загрузчик» рядом с «Импорт»; скачанное сгруппировано по загрузчикам, под названием «Загрузчик • Версия» (например Fabric • 1.12.2)
- Каталог: CurseForge как второй источник (Источник: Modrinth / CurseForge); моды, ресурспаки, шейдеры, датапаки; обязательные зависимости скачиваются автоматически
- Quilt и NeoForge: установка из «Версий» (Quilt, NeoForge от 1.20.2), фильтр каталога по загрузчику, поддержка сборок Modrinth (.mrpack) и сборок Mobilix
- Горизонтальный вид: скин слева, справа меню над кнопками «Играть» и версии (нижнее меню); при боковом меню оно слева, а скин и кнопки справа
- Кнопка OF рядом с версиями Forge: ставит OptiFine модом в профиль Forge (зеркало BMCLAPI)
