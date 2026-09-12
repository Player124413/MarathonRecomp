# DirectX 12 через vkd3d на Android — разбор и план

Задача: дать игроку в лаунчере выбор «DirectX 12 или Vulkan», положив vkd3d в комплект APK,
и получить меньше графических багов и более стабильный FPS.

Короткий вывод: **выбор в лаунчере, описание для пользователя, гейт по рантайму и место под
vkd3d в сборке — сделаны в этом изменении. Сам рендер DirectX 12 на Android сегодня не работает
ни в одной сборке этого APK**, и это не «недоделано по лени», а следствие трёх независимых
блокеров (см. «Почему это не работает из коробки»). Ожидание «будет стабильнее FPS» тоже
инвертировано: под DirectX 12 на Android всё равно тот же Vulkan-драйвер, просто ещё и слоем выше.

---

## Что уже есть в проекте

| Слой | Состояние |
|---|---|
| `plume` (библиотека рендера) | Vulkan — везде; D3D12 — только `WIN32`; Metal — Apple |
| `MARATHON_RECOMP_D3D12` | Windows-специфичный CMake-`option` (`MarathonRecomp/CMakeLists.txt`) |
| Бэкенд-логика игры (`gpu/video.cpp`) | ~40 мест с `g_backend == Backend::D3D12` уже написаны и готовы к D3D12 |
| Шейдеры | XenosRecomp кладёт в кеш и SPIR-V, и DXIL; `.dxil.h`-заголовки генеряются при включённом D3D12 |
| Конфиг | `Video.GraphicsAPI` (`Auto/Vulkan/D3D12/Metal`) — существовавший ключ, на Android не использовался |
| Android | D3D12-рендерер не компилируется; лаунчер писал только `VulkanDriver`/`RenderMode` |

## Что сделано этим изменением

1. **Лаунчер (Android):** в карточке «Graphics driver» появился пункт **Renderer (graphics API)**
   с двумя значениями — `Vulkan (native, recommended)` и `DirectX 12 via vkd3d (experimental)`,
   описание под ним (EN/RU), объясняющее, что DX12 на Android — это перевод обратно в Vulkan на
   том же драйвере, а не второй драйвер, и чего от него ждать.
2. **Реальная проверка рантайма:** `MarathonRecomp/os/android/vkd3d_android.cpp` ищет arm64
   .so-библиотеку vkd3d (в `nativeLibraryDir` APK и в папках `vkd3d_import/`), проверяет, что
   это ELF64/AArch64 shared object, грузит её через `dlopen` и ищет `D3D12CreateDevice` /
   `vkd3d_create_device`. Итог пишется в `Android/data/<pkg>/files/vkd3d_status.txt` одной
   машиночитаемой строкой (`ready|…`, `missing-runtime|…`, `bad-runtime|…`,
   `no-renderer-in-build`), и лаунчер показывает именно её, а не догадку по именам файлов.
3. **Кнопка «DirectX 12 folder (vkd3d)»** — создаёт папку, кладёт туда `readme.txt` с
   требованиями к файлу и открывает её в системном файловом менеджере (тот же UX, что у импорта
   Turnip-драйвера).
4. **Честный гейт:** `Video::CreateHostDevice` на Android спрашивает рантайм перед выбором
   бэкенда. Если D3D12-рендерер не собран или рантайма нет — пишется причина и игра стартует на
   Vulkan, а значение `GraphicsAPI` нормализуется, чтобы config.toml не врал.
5. **Конфиг для Android:** перечислитель `EGraphicsAPI::D3D12` и его строка «D3D12» объявляются и
   на Android, чтобы выбор сохранялся и читался, а не сбрасывался при парсинге toml.
6. **Разделение windows-специфики внутри D3D12-кода:** `MARATHON_RECOMP_D3D12_SEH`
   (`__try/__except` вокруг создания устройства) и `MARATHON_RECOMP_D3D12_DXC` (рантайм-линковка
   spec-constant-библиотек через dxcompiler) теперь отдельные макросы, включаемые только на
   Windows. Без этого любой будущий Android-билд D3D12-путём не компилируется вообще.
7. **CMake:** опция `MARATHON_RECOMP_D3D12_VKD3D` (OFF) + `MARATHON_RECOMP_VKD3D_ROOT`. Она
   включает DXIL-блобы и определения D3D12, и **намеренно валит configure** с точным списком того,
   чего не хватает, — чтобы невозможно было выложить APK, который обещает DirectX 12 и падает.
8. **CI:** `tools/ci/fetch_build_files.sh` теперь достаёт из zip-архива необязательные папки
   `drivers/` и `vkd3d/` (раньше проверка `ci-build-files/drivers/*.so` не срабатывала никогда,
   потому что архив распаковывается в `ci-build-files/extracted/`), а workflow кладёт
   `vkd3d/*.so` рядом с `libmain.so` в `jniLibs/arm64-v8a` — то есть в `nativeLibraryDir`, куда
   смотрит пробник.

## Почему это не работает «из коробки»

### 1. vkd3d на Android не существует как готовая библиотека

Upstream vkd3d-proton прямо пишет: штатный способ использования — `d3d12.dll`/`d3d12core.dll`
в Wine (Proton) или на Windows; нативная (Linux) сборка — «mostly relevant for development
purposes», и она **не поставляет DXGI**: swapchain и `IDXGISwapChain` берутся из DXVK 2.1+,
у которого win32-специфичный WSI. Отдельно в релизах DXVK есть оговорка, что фиксы для
«unified memory setups, including the Qualcomm proprietary driver» **не означают** поддержки
Android или проприетарных мобильных драйверов.

Нужен форк vkd3d-proton (или Wine vkd3d 1.x) с:

* сборкой под NDK (`aarch64-linux-android29`, `VK_USE_PLATFORM_ANDROID_KHR`);
* собственным WSI: `vkCreateAndroidSurfaceKHR(ANativeWindow*)` вместо `HWND` и минимальным
  `IDXGIFactory2/4/5` + `IDXGISwapChain1`, потому что plume ходит именно в DXGI;
* отказом от виндовых вещей (`windows.h`, `ComPtr`, `swprintf_s`, `__declspec`, registry).

### 2. plume собирает D3D12-бэкенд только для Windows

`thirdparty/plume/CMakeLists.txt`: `Building with backends: Vulkan=1 Metal=${APPLE} D3D12=${WIN32}`,
а `plume_d3d12.cpp` создаёт окно через
`dxgiFactory->CreateSwapChainForHwnd(commandQueue->d3d, desc.renderWindow, ...)` и использует
`dxgi1_5.h`, `CreateDXGIFactory2`, `IDXGIFactory5`. То есть, кроме самой библиотеки-переводчика, нужно ещё и портировать этот файл (≈4300 строк) на ANativeWindow.

### 3. Требования vkd3d к драйверу выше, чем дают мобильные драйверы

Минимум vkd3d-proton: Vulkan 1.3, полный набор `VkPhysicalDeviceDescriptorIndexingFeatures`
(≥1 000 000 `UpdateAfterBind`), `VK_EXT_robustness2`, `VK_KHR_push_descriptor`,
`VK_KHR_dynamic_rendering`, `VK_EXT_extended_dynamic_state`. `VK_KHR_push_descriptor` на мобильных
— как раз то, чего нет или что сломано: например, в PS2-эмуляторе его сделали опциональным именно
потому, что «some Mali (e.g. Mali-G52) don't expose it at all», а на других Mali он падает в
`vkCmdPushDescriptorSetKHR`. Turnip ближе к требованиям, чем Mali/PowerVR, но «ближе» ≠ «есть»,
и это надо проверять на каждом Adreno-поколении, а не предполагать.

### 4. Про «стабильнее FPS» и «не будет багов»

На Windows D3D12 в этом порте — дефолт по той причине, что Xenos-шейдеры рекомпилируются в HLSL/DXIL
1-в-1, а Vulkan-путь на десктопе упирался в баги драйверов AMD/NVIDIA. На Android такой причины нет:
`DX12 → vkd3d → Vulkan → Turnip/Adreno` — это тот же самый драйвер плюс трансляция. Что это значит
практически:

* +CPU на каждый вызов (обновление дескрипторов, запись команд, барьеры), при том что порт на мобильных и так упирается в CPU;
* +память (vkd3d держит свои пулы дескрипторов и эмуляцию ресурс-стейтов);
* новые классы расхождений: flip-model present, `DiscardResource`, MSAA-resolve, depth bias
  (в `video.cpp` под это уже есть отдельные ветки `g_backend == Backend::D3D12` — они сработают и
  на Android, но проверить их будет не на чем, пока рантайма не существует);
* графические баги не «исчезают», а меняют набор: всё, что мы сейчас обходим в Vulkan-пути
  (TU_DEBUG, sample-count запросы, ETC2/BC, `textureCompressionBC`), останутся актуальны и под vkd3d,
  потому что драйвер тот же.

## План, если всё-таки делать

Оценка честная: это не «добавить .so в assets», а ~2–4 недели работы + железо для тестов, и с
вероятным исходом «равно или медленнее».

**Фаза 0 — сделана.** UI, гейт, пробник, config, CI, CMake-опция. Побочный полезный результат:
даже без DX12 у лаунчера есть честная диагностика «почему DirectX 12 нельзя».

**Фаза 1 — рантайм.** Собрать под NDK форк vkd3d-proton с Android WSI (или Wine vkd3d).
Критерий готовности: отдельное NDK-приложение (не игра) создаёт `ID3D12Device` и
`IDXGISwapChain` на `ANativeWindow`, рисует треугольник и present-ит на Adreno 732 и на Mali.
Риск: `VK_KHR_push_descriptor`/`robustness2` — если их нет, дальше идти некуда; тогда вариант —
форкать vkd3d с заменой push-дескрипторов на ordinary sets (это правки в vkd3d, не в нас).

**Фаза 2 — plume.** Флаг `PLUME_D3D12_ANDROID_ENABLED`, swapchain через
`SDL_AndroidGetNativeWindow()` → `vkCreateAndroidSurfaceKHR`, `RenderWindow` как `ANativeWindow*`,
DXGI-шим (у `desc.renderWindow` и `RenderFormat` в plume виндовые типы), D3D12MA собирается как
есть (он портативный). Критерий: тот же треугольник через plume.

**Фаза 3 — игра.** Включить DXIL-блобы (это уже делает опция; попутно проверить, что
XenosRecomp действительно кладёт DXIL в `shader_cache.cpp` при сборке для Android — `shader_cache.h`
объявляет оба набора всегда), заменить runtime-DXC для
spec-constants на прекомпиляцию вариантов на этапе сборки (XenosRecomp), прогнать стейт-машину
`video.cpp` (`g_discardCommandList`, triple buffering=3, MSAA-resolve, `dynamicDepthBias`), PSO
caching. Критерий: игра грузит меню и один этап без валидационных ошибок.

**Фаза 4 — Android-специфика.** Frame pacing (flip model vs SurfaceFlinger vsync), работа с
libadrenotools (vkd3d должен увидеть подменённый ICD — проверить, что `volk`/`GetInstanceProcAddr`
обходятся корректно), память/бюджеты, ресайз при фоновом режиме (`Video::OnAndroidResume`).

**Фаза 5 — QA и выкатка.** Экспериментальный флаг, логирование выбранного бэкенда в `log.txt`
(уже есть `Backend` в логах опций), матрица устройств (Adreno 710/725/732/750, пара Mali),
сравнение FPS/просадок с Vulkan на одном и том же билде, и — обязательно — кнопка «вернуть
Vulkan» в том же лаунчере, чтобы плохой билд не ловил пользователя в петлю.

## Как проверить то, что уже сделано (без устройства нельзя, но на устройстве — легко)

1. Лаунчер → Graphics driver → Renderer = Vulkan. Ожидание: описание про прямой путь к драйверу,
   статус «No DirectX 12 runtime installed…».
2. Renderer = DirectX 12 → «Launch game». Ожидание: диалог «DirectX 12 is not available» с кнопками
   «Use Vulkan» / «Start anyway».
3. Положить любой non-vkd3d `.so` с именем `libvkd3d_test.so` в `vkd3d_import/`, запустить.
   Ожидание: в `log.txt` строка `DirectX 12 runtime is unusable: … (file is not an arm64 shared
   object)` или `… (loaded, but exports no D3D12 entry point …)`, а `vkd3d_status.txt` →
   `bad-runtime|…`; лаунчер показывает «DirectX 12 runtime was rejected».
4. Проверить round-trip конфига: после запуска в `config.toml` в `[Video]` стоит
   `GraphicsAPI = "Vulkan"` (иначе был бы сброшен на `Auto`).

## Что проверено локально (без телефона)

`MarathonRecomp/os/android/vkd3d_android.cpp` прогнан на хосте через подставные
SDL/JNI/logger-заглушки: код собирается в C++20 с `-Wall -Wextra` как с
`MARATHON_RECOMP_D3D12`, так и без него, и корректно отрабатывают все ветки
поиска — папка пуста (`missing-runtime|…`), посторонний `.so` игнорируется,
`libvkd3d_custom_3.0.so` не-ARM архива (`bad-runtime|… (file is not an arm64
shared object)`), правдоподобный кандидат без пробы (`present-not-loaded|…`),
кандидат, не прошедший `dlopen`/`dlsym` (`bad-runtime|…`), и успешная проба
(`ready|…`). Для проверки ветки `ready` в scratch-копии файла временно
разрешался ещё и `EM_X86_64` — в репозитории этого изменения нет.

Сборка самого APK, `javac` и NDK в песочнице недоступны, поэтому Java-часть и
CMake проверены только чтением: XML обоих `strings.xml` валиден, все
`R.string.*`/`R.array.*` из `LauncherActivity` существуют и в EN, и в RU
(кроме двух предсуществующих пропусков `error_storage_create`/`error_storage_write`
в `values-ru`), `CMakePresets.json` остаётся валидным JSON, `bash -n` на
`tools/ci/fetch_build_files.sh` чист. Реальное поведение на устройстве —
обязательная часть проверки, см. чек-лист выше.

## Источники по утверждениям

* vkd3d-proton README (назначение, нативная сборка, отсутствие DXGI, требования к драйверу,
  `package-release.sh --native`): <https://github.com/HansKristian-Work/vkd3d-proton>
* DXVK releases — оговорка про Android/proprietary mobile drivers и unified memory:
  <https://github.com/doitsujin/dxvk/releases>
* Пример, почему `VK_KHR_push_descriptor` нельзя считать обязательным на мобильных:
  <https://git.eden-emu.dev/moonpower/ARMSX2/commit/09109bb935bc46fddb03e3691fcc0a6d5db05998>
