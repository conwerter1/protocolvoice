# Сборка native llama.cpp для Android

## Назначение

Файл `libllama-android.so` — нативная библиотека inference QVikhr 1.5B GGUF на Android.
Без неё PRO tier недоступен (Default tier работает без неё).

## Что нужно

1. **Android NDK r26+** (~3 GB)
   - Установить через Android Studio → SDK Manager → SDK Tools → NDK (Side by side)
   - Или скачать с https://developer.android.com/ndk/downloads и распаковать в
     `~/Android/Sdk/ndk/26.x.xxxxxx/`

2. **CMake 3.22+** (~50 MB)
   - Также через SDK Manager → CMake

3. **llama.cpp source**
   ```sh
   git clone https://github.com/ggml-org/llama.cpp.git C:\Work_Claude\llama.cpp
   ```

## Шаги сборки

### Вариант A: Использовать готовое демо из llama.cpp

`llama.cpp/examples/llama.android` — это готовое Android-приложение от команды ggml,
с уже написанным минимальным JNI и Kotlin wrapper'ом. Можно скопировать его модули
в наш проект.

```sh
# 1. Скопировать нативную часть
cp -r C:\Work_Claude\llama.cpp\examples\llama.android\llama\src\main\cpp \
      C:\Work_Claude\Output\ProtocolVoice_GitHub_ready\app\src\main\cpp

# 2. Скопировать llama.cpp source (нужны для компиляции)
cp -r C:\Work_Claude\llama.cpp\ggml \
      C:\Work_Claude\Output\ProtocolVoice_GitHub_ready\app\src\main\cpp\llama.cpp\ggml
cp -r C:\Work_Claude\llama.cpp\src \
      C:\Work_Claude\Output\ProtocolVoice_GitHub_ready\app\src\main\cpp\llama.cpp\src
```

### CMakeLists.txt

В `app/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("llama-android")

# Заглушим лишние таргеты llama.cpp
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "")

# Подключим саму llama.cpp как subdirectory
add_subdirectory(llama.cpp)

# Наша JNI-обёртка
add_library(llama-android SHARED llama-android.cpp)
target_link_libraries(llama-android llama android log)
```

### llama-android.cpp (минимальный JNI)

Файл `app/src/main/cpp/llama-android.cpp` — прямой порт из
`llama.cpp/examples/llama.android/llama/src/main/cpp/llama-android.cpp`.

Содержит 3 экспортируемые функции:
- `Java_app_protocolvoice_summary_pro_tier_LlamaCppBridge_nativeLoad`
- `Java_app_protocolvoice_summary_pro_tier_LlamaCppBridge_nativeChat`
- `Java_app_protocolvoice_summary_pro_tier_LlamaCppBridge_nativeFree`

⚠️ Имена JNI-функций должны точно соответствовать пакету Kotlin. Если переименуете
пакет — переименуйте и здесь.

### build.gradle.kts (app module)

Добавить:

```kotlin
android {
    defaultConfig {
        ndk {
            // Только ARM64 — на 32-bit устройствах PRO tier не работает
            abiFilters += setOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=ON",
                    "-DLLAMA_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",  // OpenMP может конфликтовать на Android
                )
                cppFlags += listOf("-O3", "-fexceptions")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

## Проверка

1. `./gradlew assembleDebug` — должно пройти без ошибок
2. APK будет содержать `lib/arm64-v8a/libllama-android.so` (~3-5 MB)
3. На устройстве `LlamaCppBridge.isNativeLibraryAvailable()` начнёт возвращать `true`

## Размер APK

| Конфигурация | Дополнительный размер |
|---|---|
| llama.cpp + ggml ARM64 only | ~3-5 MB native binary |
| QVikhr GGUF | НЕ включается в APK (1.0 GB) — скачивается отдельно |

## Частые проблемы

1. **NDK не находит OpenMP** — отключить `GGML_OPENMP=OFF`
2. **Большой размер binary** — собирать только под `arm64-v8a` (как в примере выше).
   Не включать `armeabi-v7a` — на 32-bit моделях достаточно RAM, но 1.5B GGUF
   с 1 GB файлом всё равно не загрузится в 4 GB RAM комфортно
3. **Missing libomp.so** — установить NDK с правильным sysroot

## Тестирование без устройства

Если нет устройства/ADB — можно собрать вариант под Linux x86_64 и тестировать локально:

```sh
cd C:\Work_Claude\llama.cpp
mkdir build && cd build
cmake ..
make -j8
./bin/llama-cli -m C:\Work_Claude\Temp\qvikhr_models\QVikhr-2.5-1.5B-Instruct-r.Q5_K_M.gguf -p "Hello"
```

Этот же бинарник работает на PC, поэтому можно валидировать логику промптов
без Android.
