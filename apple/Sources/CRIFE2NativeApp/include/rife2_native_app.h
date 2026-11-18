/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */

#ifndef RIFE2_NATIVE_APP_H
#define RIFE2_NATIVE_APP_H

// The functions of GraalVM that start the site's Java and attach threads to
// it, as native-image declares them in graal_isolate.h, which this stands in
// for so that an app doesn't need the one of its build.
#ifndef __GRAAL_ISOLATE_H
#define __GRAAL_ISOLATE_H

struct __graal_isolate_t;
typedef struct __graal_isolate_t graal_isolate_t;
struct __graal_isolatethread_t;
typedef struct __graal_isolatethread_t graal_isolatethread_t;
typedef unsigned long __graal_uword;

struct __graal_create_isolate_params_t {
    int version;
    __graal_uword reserved_address_space_size;
    const char* auxiliary_image_path;
    __graal_uword auxiliary_image_reserved_space_size;
    int argc;
    char** argv;
    int pkey;
    char ignore_unrecognized_args;
    char _reserved_4;
    char _reserved_5;
};
typedef struct __graal_create_isolate_params_t graal_create_isolate_params_t;

int graal_create_isolate(graal_create_isolate_params_t* params, graal_isolate_t** isolate, graal_isolatethread_t** thread);
int graal_attach_thread(graal_isolate_t* isolate, graal_isolatethread_t** thread);
int graal_detach_thread(graal_isolatethread_t* thread);
graal_isolatethread_t* graal_get_current_thread(graal_isolate_t* isolate);
#endif

// The functions of rife.nativeapp.EntryPoints. A text is UTF-8 that ends
// with a zero, and the three functions that a request is given are called
// on the thread that handles it, before rife2_handle returns unless the
// response is an event stream.
int rife2_abi(graal_isolatethread_t* thread);
void rife2_property(graal_isolatethread_t* thread, const char* name, const char* value);
void rife2_handle(graal_isolatethread_t* thread, long long id, const char* url, const char* method, const char* headers,
                  const char* body, int length, void* context,
                  void (*committed)(void* context, int status, const char* headers),
                  void (*content)(void* context, const char* bytes, int length),
                  void (*ended)(void* context, int how, const char* failure));
void rife2_cancel(graal_isolatethread_t* thread, long long id);

#endif
