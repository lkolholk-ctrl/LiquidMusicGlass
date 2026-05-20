cmake_minimum_required(VERSION 3.22.1)
project("icmkey")

add_library(
    icmkey
    SHARED
    icmkey.c
)

# Hiding all symbols except JNI_OnLoad and optimizing for security
target_compile_options(icmkey PRIVATE 
    -fvisibility=hidden
    -fomit-frame-pointer
    -fdata-sections
    -ffunction-sections
    -Oz # Optimize for size and obfuscation
)

find_library(log-lib log)

target_link_libraries(icmkey ${log-lib})
