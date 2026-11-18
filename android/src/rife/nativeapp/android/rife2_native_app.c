/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */

/* Between the Java of Android and the site, which is a native library with a
   Java of its own: starts that Java once, and has the site answer a request
   on the thread that asks. It's linked into the site's library. Texts cross
   as bytes of UTF-8 that end with a zero, since the texts that JNI makes
   itself are written in a UTF-8 of its own. */
#include <jni.h>
#include <stdio.h>
#include <string.h>

typedef struct __graal_isolate_t graal_isolate_t;
typedef struct __graal_isolatethread_t graal_isolatethread_t;
int graal_create_isolate(void* params, graal_isolate_t** isolate, graal_isolatethread_t** thread);
int graal_attach_thread(graal_isolate_t* isolate, graal_isolatethread_t** thread);
int graal_detach_thread(graal_isolatethread_t* thread);

/* the functions of rife.nativeapp.EntryPoints */
int rife2_abi(graal_isolatethread_t* thread);
void rife2_property(graal_isolatethread_t* thread, const char* name, const char* value);
void rife2_handle(graal_isolatethread_t* thread, long long id, const char* url, const char* method, const char* headers,
                  const char* body, int length, void* context,
                  void (*committed)(void* context, int status, const char* headers),
                  void (*content)(void* context, const char* bytes, int length),
                  void (*ended)(void* context, int how, const char* failure));

enum { COMPLETED, FAILED, CANCELLED };

static graal_isolate_t* isolate;
/* what NativeSite.Answer is told with */
static jmethodID committed_, content_, ended_;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass answer = (*env)->FindClass(env, "rife/nativeapp/android/NativeSite$Answer");
    if (!answer) {
        return JNI_ERR;
    }
    committed_ = (*env)->GetMethodID(env, answer, "committed", "(I[B)V");
    content_ = (*env)->GetMethodID(env, answer, "content", "([B)V");
    ended_ = (*env)->GetMethodID(env, answer, "ended", "(I[B)V");
    return committed_ && content_ && ended_ ? JNI_VERSION_1_6 : JNI_ERR;
}

/* Who the site's answer to a request is told to, on the thread that asked,
   before rife2_handle returns, which is as long as this lasts: NativeSite
   has the site take answers whole, so that an event stream ends with its
   request and no other thread tells of it. */
struct told {
    JNIEnv* env;
    jobject answer;
};

/* Tells the answer something: a number, when there is one, and bytes of the
   site, which are only the site's during the call and so are copied. Once
   Java threw, for want of memory for instance, it's told nothing more: what
   it threw goes on to who asked when the request returns. */
static void tell(struct told* told, jmethodID what, const jint* number, const char* bytes, jsize length) {
    JNIEnv* env = told->env;
    if ((*env)->ExceptionCheck(env)) {
        return;
    }
    jbyteArray copied = NULL;
    if (bytes) {
        copied = (*env)->NewByteArray(env, length);
        if (!copied) {
            return;
        }
        (*env)->SetByteArrayRegion(env, copied, 0, length, (const jbyte*) bytes);
    }
    jvalue said[2];
    int count = 0;
    if (number) {
        said[count++].i = *number;
    }
    said[count].l = copied;
    (*env)->CallVoidMethodA(env, told->answer, what, said);
    /* a request tells of its content many times, and Java only lets go of these by itself when it returns */
    (*env)->DeleteLocalRef(env, copied);
}

static void committed(void* context, int status, const char* headers) {
    tell(context, committed_, &status, headers, (jsize) strlen(headers));
}

static void content(void* context, const char* bytes, int length) {
    tell(context, content_, NULL, bytes, length);
}

static void ended(void* context, int how, const char* why) {
    tell(context, ended_, &how, why, why ? (jsize) strlen(why) : 0);
}

/* The texts and the bytes that Java hands over, for as long as a call into
   the site lasts. One that's NULL ends them: there are no more, or Java had
   no memory for it and threw. */
struct handed {
    jbyteArray arrays[4];
    jbyte* bytes[4];
    int count;
};

static jboolean take(JNIEnv* env, struct handed* handed) {
    for (handed->count = 0; handed->count < 4 && handed->arrays[handed->count]; handed->count++) {
        handed->bytes[handed->count] = (*env)->GetByteArrayElements(env, handed->arrays[handed->count], NULL);
        if (!handed->bytes[handed->count]) {
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

static void give_back(JNIEnv* env, struct handed* handed) {
    for (int i = 0; i < handed->count; i++) {
        /* nothing was written to them */
        (*env)->ReleaseByteArrayElements(env, handed->arrays[i], handed->bytes[i], JNI_ABORT);
    }
}

/* Starts the Java of the site and tells it the properties, as names and
   values in turn. Answers why it didn't start, or NULL. */
JNIEXPORT jstring JNICALL Java_rife_nativeapp_android_NativeSite_start(JNIEnv* env, jclass class, jobjectArray properties) {
    graal_isolatethread_t* thread;
    int code = graal_create_isolate(NULL, &isolate, &thread);
    if (code != 0) {
        char why[64];
        snprintf(why, sizeof why, "The site's Java didn't start: error %d", code);
        return (*env)->NewStringUTF(env, why);
    }
    if (rife2_abi(thread) != 1) {
        graal_detach_thread(thread);
        return (*env)->NewStringUTF(env, "The native library has the functions of another version of RIFE2");
    }
    jsize count = (*env)->GetArrayLength(env, properties);
    for (jsize i = 0; i + 1 < count && !(*env)->ExceptionCheck(env); i += 2) {
        struct handed property = {{(*env)->GetObjectArrayElement(env, properties, i), (*env)->GetObjectArrayElement(env, properties, i + 1)}, {0}, 0};
        if (take(env, &property)) {
            rife2_property(thread, (char*) property.bytes[0], (char*) property.bytes[1]);
        }
        give_back(env, &property);
        (*env)->DeleteLocalRef(env, property.arrays[0]);
        (*env)->DeleteLocalRef(env, property.arrays[1]);
    }
    graal_detach_thread(thread);
    return NULL;
}

/* Has the site answer a request, with what was posted or NULL, and tells the
   answer of it. The number is one that no request had before. */
JNIEXPORT void JNICALL Java_rife_nativeapp_android_NativeSite_handle(JNIEnv* env, jclass class, jlong number,
                                                                     jbyteArray url, jbyteArray method, jbyteArray headers, jbyteArray body, jobject answer) {
    struct told told = {env, answer};
    /* Java only knows the threads that were attached to it, and several can be at a time */
    graal_isolatethread_t* thread;
    if (graal_attach_thread(isolate, &thread) != 0) {
        ended(&told, FAILED, "A request couldn't reach the site's Java");
        return;
    }
    struct handed asked = {{url, method, headers, body}, {0}, 0};
    if (take(env, &asked)) {
        rife2_handle(thread, number, (char*) asked.bytes[0], (char*) asked.bytes[1], (char*) asked.bytes[2],
                     (char*) asked.bytes[3], body ? (*env)->GetArrayLength(env, body) : 0, &told, committed, content, ended);
    }
    give_back(env, &asked);
    graal_detach_thread(thread);
}
