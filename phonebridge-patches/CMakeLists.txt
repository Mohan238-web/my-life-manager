cmake_minimum_required(VERSION 3.24)
project(PhoneBridgeReceiver LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
if(MSVC)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

find_package(libjpeg-turbo CONFIG REQUIRED)

add_executable(PhoneBridgeReceiver WIN32 Receiver.cpp JpegDecoder.cpp SharedBus.cpp)
target_compile_definitions(PhoneBridgeReceiver PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
target_link_libraries(PhoneBridgeReceiver PRIVATE ws2_32 ole32 shlwapi comctl32 libjpeg-turbo::turbojpeg-static)

add_executable(PhoneBridgeDecoderSelfTest DecoderSelfTest.cpp JpegDecoder.cpp)
target_link_libraries(PhoneBridgeDecoderSelfTest PRIVATE libjpeg-turbo::turbojpeg-static)
