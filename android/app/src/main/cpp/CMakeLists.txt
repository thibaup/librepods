cmake_minimum_required(VERSION 3.22.1)

project("l2c_fcr_hook")
set(CMAKE_CXX_STANDARD 23)

add_library(bluetooth_socket SHARED
        bluetooth_socket.cpp
)

target_compile_options(bluetooth_socket PRIVATE
        -O2
        -fvisibility=hidden
)

target_link_options(bluetooth_socket PRIVATE
        -Wl,--strip-all
        -Wl,--gc-sections
)

target_link_libraries(bluetooth_socket
        android
        log
)


set(XPOSED_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../xposed/cpp)

add_library(l2c_fcr_hook SHARED
        l2c_fcr_hook.cpp

        xz/xz_crc32.c
        xz/xz_crc64.c
        xz/xz_sha256.c
        xz/xz_dec_stream.c
        xz/xz_dec_lzma2.c
        xz/xz_dec_bcj.c
)

target_include_directories(l2c_fcr_hook PRIVATE
        xz
)

target_compile_definitions(l2c_fcr_hook PRIVATE
        XZ_DEC_X86
        XZ_DEC_ARM
        XZ_DEC_ARMTHUMB
        XZ_DEC_ARM64
        XZ_DEC_ANY_CHECK
        XZ_USE_CRC64
        XZ_USE_SHA256
        XZ_DEC_CONCATENATED
)

target_link_libraries(l2c_fcr_hook
        android
        log
)

# Phone-native Anisette bridge used by the Find My network implementation. Apple's
# libraries are downloaded from Apple's CDN at runtime, hash-checked, and loaded with
# dlopen; they are never linked into or redistributed with LibrePods.
add_library(opentagviewer SHARED
        adi.cpp
)

target_link_libraries(opentagviewer
        android
        log
)

# libstoreservicescore.so declares these two libraries as dependencies but imports no
# symbols from the large upstream implementations. Generate narrow stand-ins with the
# exact SONAMEs required by Android's dynamic linker.
function(add_apple_stub library)
    set(symbols "${CMAKE_CURRENT_SOURCE_DIR}/stubs/lib${library}.symbols")
    set(generated "${CMAKE_CURRENT_BINARY_DIR}/lib${library}_stub.c")
    set(expected "${CMAKE_CURRENT_SOURCE_DIR}/stubs/lib${library}.expected")

    set(inputs "${symbols}" "${CMAKE_CURRENT_SOURCE_DIR}/stubs/generate_stub.cmake")
    if (EXISTS "${expected}")
        list(APPEND inputs "${expected}")
    endif ()

    add_custom_command(
            OUTPUT "${generated}"
            COMMAND ${CMAKE_COMMAND}
            -DSYMBOLS=${symbols}
            -DEXPECTED=${expected}
            -DOUTPUT=${generated}
            -DLIBRARY=lib${library}.so
            -DSAFE=${library}
            -P "${CMAKE_CURRENT_SOURCE_DIR}/stubs/generate_stub.cmake"
            DEPENDS ${inputs}
            COMMENT "Generating lib${library}.so stub"
            VERBATIM)

    add_library(${library}_stub SHARED "${generated}")
    set_target_properties(${library}_stub PROPERTIES
            OUTPUT_NAME "${library}"
            PREFIX "lib")
    target_link_libraries(${library}_stub log)
endfunction()

add_apple_stub(CoreFoundation)
add_apple_stub(mediaplatform)
