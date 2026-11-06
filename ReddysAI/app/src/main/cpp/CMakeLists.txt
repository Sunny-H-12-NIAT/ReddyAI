cmake_minimum_required(VERSION 3.22.1)

project("reddysai-llama" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# ---------------------------------------------------------------------------
# Prebuilt libllama.so from the WSL native build
# ---------------------------------------------------------------------------
# Drop the following into app/src/main/jniLibs/arm64-v8a/ before building:
#   libllama.so         — main llama.cpp library
#   libggml.so          — ggml compute backend
#   libggml-cpu.so      — CPU backend plugin
#   libllama-common.so  — common utilities (tokenisation, sampling, chat templates)
#
# The headers needed to compile llama_jni.cpp go into app/src/main/cpp/include/:
#   llama.h, common.h, sampling.h, chat.h, ggml.h, ggml-backend.h

set(JNI_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# Declare each prebuilt so CMake knows how to link against it
add_library(llama        SHARED IMPORTED)
add_library(ggml         SHARED IMPORTED)
add_library(llama-common SHARED IMPORTED)

set_target_properties(llama        PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libllama.so)
set_target_properties(ggml         PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libggml.so)
set_target_properties(llama-common PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libllama-common.so)

# ---------------------------------------------------------------------------
# Our JNI bridge shared library (the only thing Gradle compiles)
# ---------------------------------------------------------------------------
add_library(reddysai-llama SHARED
        llama_jni.cpp)

target_include_directories(reddysai-llama PRIVATE
        ${CMAKE_SOURCE_DIR}/include)   # llama.h, common.h, sampling.h, chat.h

target_link_libraries(reddysai-llama
        llama
        ggml
        llama-common
        android
        log)
