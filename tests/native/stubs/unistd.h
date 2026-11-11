#pragma once
#include <cstdint>
#include <cstdlib>
using ssize_t = intptr_t;
using useconds_t = unsigned int;
inline ssize_t read(int, void*, size_t) { std::abort(); }
inline ssize_t write(int, const void*, size_t) { std::abort(); }
inline int close(int) { std::abort(); }
inline int usleep(unsigned int) { std::abort(); }
