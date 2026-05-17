cmake_minimum_required(VERSION 3.22.1)
project("icmkey")

add_library(
    icmkey
    SHARED
    icmkey.c
)

find_library(log-lib log)

target_link_libraries(icmkey ${log-lib})
