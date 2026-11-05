cmake_minimum_required(VERSION 3.22.1)

project(localaichat_llm C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- llama.cpp (git-submodule third_party/llama.cpp, тег зафиксирован) ---
# Только библиотека: без инструментов, тестов, сервера и сетевых зависимостей.
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)

# CPU-бэкенд собирается в нескольких вариантах (armv8.0 ... armv9 с dotprod/i8mm),
# во время работы ggml выбирает лучший для процессора телефона.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# Без OpenMP (свой пул потоков ggml) и без KleidiAI (он скачивается во время сборки).
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} llama-build)

# --- JNI-обёртка ---
add_library(llm_jni SHARED llm_jni.cpp)
target_link_libraries(llm_jni PRIVATE llama ggml android log)
# Страницы 16 КБ (Android 15+).
target_link_options(llm_jni PRIVATE "-Wl,-z,max-page-size=16384")
