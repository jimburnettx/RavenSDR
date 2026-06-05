cmake_minimum_required(VERSION 3.22)
project(rtlsdr_android C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Reproducible builds: strip machine-specific absolute paths from all debug
# info so two builds on different machines produce identical .so files.
add_compile_options(
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}/=
    -ffile-prefix-map=${CMAKE_BINARY_DIR}/=
)
if(DEFINED CMAKE_ANDROID_NDK)
    add_compile_options(-ffile-prefix-map=${CMAKE_ANDROID_NDK}/=ndk/)
endif()

# ────────────────────────────────────────────────────────────────────────────
# Paths
# ────────────────────────────────────────────────────────────────────────────
set(LIBUSB_ROOT  ${CMAKE_CURRENT_SOURCE_DIR}/libusb)
set(LIBUSB_SRC   ${LIBUSB_ROOT}/libusb)
set(RTLSDR_ROOT  ${CMAKE_CURRENT_SOURCE_DIR}/librtlsdr)
set(KISSFFT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/kissfft)

# ────────────────────────────────────────────────────────────────────────────
# libusb — static, Android Linux backend, no udev / no netlink
# ────────────────────────────────────────────────────────────────────────────
set(LIBUSB_SOURCES
    ${LIBUSB_SRC}/core.c
    ${LIBUSB_SRC}/descriptor.c
    ${LIBUSB_SRC}/hotplug.c
    ${LIBUSB_SRC}/io.c
    ${LIBUSB_SRC}/sync.c
    ${LIBUSB_SRC}/strerror.c
    ${LIBUSB_SRC}/os/linux_usbfs.c
    ${LIBUSB_SRC}/os/events_posix.c
    ${LIBUSB_SRC}/os/threads_posix.c
)

add_library(usb-static STATIC ${LIBUSB_SOURCES})

# The android/config.h must be found as <config.h>; add it first in the path
target_include_directories(usb-static PUBLIC
    ${LIBUSB_ROOT}/android    # config.h lives here
    ${LIBUSB_SRC}             # libusb.h, libusbi.h
    ${LIBUSB_SRC}/os          # linux_usbfs.h, events_posix.h, threads_posix.h
)

target_compile_definitions(usb-static PRIVATE
    HAVE_STRUCT_TIMESPEC=1
    # -DLIBUSB_TARGET_OS=Android / -DENABLE_UDEV=OFF equivalents.
    #
    # NOTE: Do NOT define HAVE_LIBUDEV at all — even HAVE_LIBUDEV=0 would
    # satisfy "#if defined(HAVE_LIBUDEV)" and pull in udev symbols that do
    # not exist on Android.  Leaving it undefined lets the NDK's built-in
    # __ANDROID__ macro make the hotplug helpers into safe no-ops.
    #
    OS_LINUX=1              # use the Linux/usbfs backend
    PLATFORM_POSIX=1        # POSIX threading and events
    LIBUSB_TARGET_OS=Android
)

target_compile_options(usb-static PRIVATE
    -Wno-unused-parameter
    -Wno-sign-compare
)

# ────────────────────────────────────────────────────────────────────────────
# librtlsdr — static
# ────────────────────────────────────────────────────────────────────────────
set(RTLSDR_SOURCES
    ${RTLSDR_ROOT}/src/librtlsdr.c
    ${RTLSDR_ROOT}/src/tuner_e4k.c
    ${RTLSDR_ROOT}/src/tuner_fc0012.c
    ${RTLSDR_ROOT}/src/tuner_fc0013.c
    ${RTLSDR_ROOT}/src/tuner_fc2580.c
    ${RTLSDR_ROOT}/src/tuner_r82xx.c
)

add_library(rtlsdr-static STATIC ${RTLSDR_SOURCES})

target_include_directories(rtlsdr-static PUBLIC
    ${RTLSDR_ROOT}/include
    ${RTLSDR_ROOT}/src
    ${LIBUSB_SRC}
    ${LIBUSB_ROOT}/android
)

target_compile_definitions(rtlsdr-static PRIVATE
    # Tell librtlsdr not to detach the kernel driver — not applicable on Android
    # and it would fail / crash if attempted
)

target_compile_options(rtlsdr-static PRIVATE
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-implicit-function-declaration
)

target_link_libraries(rtlsdr-static PUBLIC usb-static log)

# ────────────────────────────────────────────────────────────────────────────
# kissfft — compiled as object library to embed directly into the .so
# ────────────────────────────────────────────────────────────────────────────
set(KISSFFT_SOURCES
    ${KISSFFT_ROOT}/kiss_fft.c
)

add_library(kissfft-obj OBJECT ${KISSFFT_SOURCES})
target_include_directories(kissfft-obj PUBLIC ${KISSFFT_ROOT})
target_compile_options(kissfft-obj PRIVATE -Wno-unused-parameter)

# ────────────────────────────────────────────────────────────────────────────
# rtlsdr_android.so — the JNI shared library
# ────────────────────────────────────────────────────────────────────────────
add_library(rtlsdr_android SHARED
    rtlsdr_jni.cpp
    $<TARGET_OBJECTS:kissfft-obj>
)

target_include_directories(rtlsdr_android PRIVATE
    ${RTLSDR_ROOT}/include
    ${RTLSDR_ROOT}/src
    ${LIBUSB_SRC}
    ${LIBUSB_ROOT}/android
    ${KISSFFT_ROOT}
)

target_link_libraries(rtlsdr_android
    rtlsdr-static
    usb-static
    log
    android
)

target_compile_options(rtlsdr_android PRIVATE
    -Wno-unused-parameter
    -fvisibility=hidden
)
