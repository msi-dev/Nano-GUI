cmake_minimum_required(VERSION 3.22.1)

project("nanogui-native")

add_library(
    nanogui-native
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    nanogui-native
    ${log-lib}
)
