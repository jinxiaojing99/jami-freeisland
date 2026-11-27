#ifndef JAMI_MINGW_POLL_COMPAT_H
#define JAMI_MINGW_POLL_COMPAT_H
/* MinGW-w64 has WSAPoll() but no POSIX poll.h.
   winsock2.h only declares WSAPOLLFD / struct pollfd when
   _WIN32_WINNT >= 0x0600 (Vista). Raise it before including. */
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#if _WIN32_WINNT < 0x0600
#undef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#ifndef WINVER
#define WINVER 0x0600
#endif
#include <winsock2.h>
#include <ws2tcpip.h>

/* POSIX nfds_t is missing from MinGW-w64; FFmpeg needs it. */
#ifndef JAMI_MINGW_NFDS_T_DEFINED
#define JAMI_MINGW_NFDS_T_DEFINED
typedef ULONG nfds_t;
#endif
#ifndef POLLIN
#define POLLIN   0x0300
#endif
#ifndef POLLPRI
#define POLLPRI  0x0400
#endif
#ifndef POLLOUT
#define POLLOUT  0x0010
#endif
#ifndef POLLERR
#define POLLERR  0x0001
#endif
#ifndef POLLHUP
#define POLLHUP  0x0002
#endif
#ifndef POLLNVAL
#define POLLNVAL 0x0004
#endif
/* struct pollfd / WSAPOLLFD come from winsock2.h now. */
#define poll(fds, nfds, timeout) WSAPoll((WSAPOLLFD *)(fds), (ULONG)(nfds), (INT)(timeout))
#endif
