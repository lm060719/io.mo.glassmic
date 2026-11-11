#pragma once
#include <cstdlib>
struct pollfd { int fd; short events; short revents; };
constexpr short POLLIN = 1;
inline int poll(pollfd*, unsigned long, int) { std::abort(); }
