cmake_minimum_required(VERSION 3.22)
project(RendererProxy C ASM)

add_library(RendererProxy SHARED proxy.c stubs.S)
target_link_libraries(RendererProxy log dl)
set_target_properties(RendererProxy PROPERTIES OUTPUT_NAME RendererProxy)
target_compile_options(RendererProxy PRIVATE -fvisibility=hidden -O2)
