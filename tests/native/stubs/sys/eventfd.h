#pragma once
#include <cstdlib>
constexpr int EFD_CLOEXEC = 1, EFD_NONBLOCK = 2;
inline int eventfd(unsigned int, int) { std::abort(); }
