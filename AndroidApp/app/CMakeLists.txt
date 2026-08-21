cmake_minimum_required(VERSION 3.22.1)
project("runtimebroker")

find_library(log-lib log)

set(LIBNODE_DIR ${CMAKE_SOURCE_DIR}/libnode/bin/${ANDROID_ABI})

add_library(native-lib SHARED src/main/cpp/native-lib.cpp)

if(EXISTS ${LIBNODE_DIR}/libnode.so)
    message(STATUS "nodejs-mobile: linking libnode.so for ${ANDROID_ABI}")
    add_library(libnode SHARED IMPORTED)
    set_target_properties(libnode PROPERTIES IMPORTED_LOCATION ${LIBNODE_DIR}/libnode.so)
    target_compile_definitions(native-lib PRIVATE NODE_EMBEDDED=1)
    target_link_libraries(native-lib libnode ${log-lib})
else()
    message(WARNING "nodejs-mobile: libnode.so not found in ${LIBNODE_DIR}; "
                    "embedded server will be a stub until the libnode binaries are added")
    target_link_libraries(native-lib ${log-lib})
endif()

include_directories(${CMAKE_SOURCE_DIR}/libnode/include/node/)