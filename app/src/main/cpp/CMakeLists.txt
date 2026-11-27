cmake_minimum_required(VERSION 3.22.1)

project(byedpi_native LANGUAGES C)

set(BYEDPI_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/byedpi")
file(GLOB BYEDPI_SOURCES CONFIGURE_DEPENDS "${BYEDPI_SOURCE_DIR}/*.c")
list(REMOVE_ITEM BYEDPI_SOURCES "${BYEDPI_SOURCE_DIR}/win_service.c")

add_library(byedpi SHARED
    ${BYEDPI_SOURCES}
    byedpi-jni.c
    hev-jni.c
)

target_include_directories(byedpi PRIVATE "${BYEDPI_SOURCE_DIR}")
target_compile_features(byedpi PRIVATE c_std_99)
target_compile_options(byedpi PRIVATE
    -O2
    -Wall
    -Wextra
    -Wno-unused
    -Wno-unused-parameter
    -pedantic
)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)

target_link_options(byedpi PRIVATE
    "-Wl,--wrap=daemon"
    "-Wl,--wrap=socket"
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(byedpi PRIVATE android log dl)
