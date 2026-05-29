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

add_library(lcm_native SHARED lcm_urls.c)
target_link_libraries(lcm_native android log)

# Native Security Library
add_library(lmg_security SHARED security.c)
target_compile_options(lmg_security PRIVATE 
    -fvisibility=hidden
    -fomit-frame-pointer
    -fdata-sections
    -ffunction-sections
    -Oz # Optimize for size and security
)
target_link_libraries(lmg_security android log)

find_library(log-lib log)

target_link_libraries(icmkey ${log-lib})
