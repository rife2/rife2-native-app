/* Asks the site in a native library for addresses without an app around it,
   and prints the status, the size and a CRC-32 of each answer, which the JVM
   gives as well with rife.nativeapp.NativeApp. An argument -Dname=value sets
   a property of the site first. */
#include <stdio.h>
#include <string.h>
#include <zlib.h>
#include "graal_isolate.h"

int rife2_abi(graal_isolatethread_t* thread);
void rife2_property(graal_isolatethread_t* thread, const char* name, const char* value);
void rife2_handle(graal_isolatethread_t* thread, long long id, const char* url, const char* method, const char* headers,
                  const char* body, int length, void* context,
                  void (*committed)(void* context, int status, const char* headers),
                  void (*content)(void* context, const char* bytes, int length),
                  void (*ended)(void* context, int how, const char* failure));

static int status;
static long size;
static unsigned long checksum;
static char failure[1024];

static void committed(void* context, int answered, const char* headers) {
    status = answered;
}

static void content(void* context, const char* bytes, int length) {
    size += length;
    checksum = crc32(checksum, (const unsigned char*) bytes, length);
}

static void ended(void* context, int how, const char* why) {
    if (how != 0) {
        status = -how;
        /* the text is only the library's during the call */
        snprintf(failure, sizeof failure, "%s", why ? why : "");
    }
}

int main(int argc, char** argv) {
    graal_isolate_t* isolate;
    graal_isolatethread_t* thread;
    int code = graal_create_isolate(NULL, &isolate, &thread);
    if (code != 0) {
        printf("Java didn't start: error %d\n", code);
        return 1;
    }
    if (rife2_abi(thread) != 1) {
        printf("The library has the functions of another version\n");
        return 1;
    }
    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "-D", 2) == 0 && strchr(argv[i], '=')) {
            char name[1024];
            const char* equals = strchr(argv[i], '=');
            snprintf(name, sizeof name, "%.*s", (int) (equals - argv[i] - 2), argv[i] + 2);
            rife2_property(thread, name, equals + 1);
        }
    }
    int failed = 0;
    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "-D", 2) == 0) {
            continue;
        }
        status = 0;
        size = 0;
        checksum = crc32(0, NULL, 0);
        failure[0] = 0;
        rife2_handle(thread, i, argv[i], "GET", "", NULL, 0, NULL, committed, content, ended);
        printf("%s %d %ld %lu%s%s\n", argv[i], status, size, checksum, failure[0] ? " " : "", failure);
        failed |= status < 0;
    }
    return failed;
}
