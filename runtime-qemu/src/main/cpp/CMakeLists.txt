cmake_minimum_required(VERSION 3.22.1)
project(qemu_launcher)

add_library(qemu-launcher SHARED qemu_launcher.cpp)
find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(qemu-launcher ${log-lib} ${dl-lib})
