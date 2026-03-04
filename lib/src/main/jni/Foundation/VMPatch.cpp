//
// VirtualApp Native Project
//
#include <unistd.h>
#include <string.h>
#include <Jni/VAJni.h>
#include <Substrate/CydiaSubstrate.h>
#include "VMPatch.h"
#include "fake_dlfcn.h"

namespace FunctionDef {
    typedef void (*Function_DalvikBridgeFunc)(const void **, void *, const void *, void *);

    typedef jobject (*Function_openDexNativeFunc)(JNIEnv *, jclass, jstring, jstring, jint);

    typedef jobject (*Native_openDexNativeFunc_N)(JNIEnv *, jclass, jstring, jstring, jint, jobject,
                                                  jobject);


    typedef jint (*Function_cameraNativeSetupFunc_T1)(JNIEnv *, jobject, jobject, jint, jstring);

    typedef jint (*Function_cameraNativeSetupFunc_T2)(JNIEnv *, jobject, jobject, jint, jint,
                                                      jstring);

    typedef jint (*Function_cameraNativeSetupFunc_T3)(JNIEnv *, jobject, jobject, jint, jint,
                                                      jstring,
                                                      jboolean);

    typedef jint (*Function_cameraNativeSetupFunc_T4)(JNIEnv *, jobject, jobject, jint, jstring,
                                                      jboolean);

    typedef jint (*Function_getCallingUid)(JNIEnv *, jclass);

    typedef jint (*Function_audioRecordNativeCheckPermission)(JNIEnv *, jobject, jstring);
}

using namespace FunctionDef;


static struct {

    bool is_art;
    int native_offset;
    bool translation_layer;  // true when running on binary translation (e.g. Berberis on x86_64 emulator)
    char *host_packageName;
    jint api_level;
    jmethodID method_onGetCallingUid;
    jmethodID method_onOpenDexFileNative;

    void *art_work_around_app_jni_bugs;

    char *(*GetCstrFromString)(void *);

    void *(*GetStringFromCstr)(const char *);

    int (*native_getCallingUid)(int);

    int (*IPCThreadState_self)(void);

    Function_getCallingUid jni_orig_getCallingUid;
    Function_DalvikBridgeFunc orig_cameraNativeSetup_dvm;

    int cameraMethodType;
    union {
        Function_cameraNativeSetupFunc_T1 t1;
        Function_cameraNativeSetupFunc_T2 t2;
        Function_cameraNativeSetupFunc_T3 t3;
        Function_cameraNativeSetupFunc_T4 t4;
    } orig_native_cameraNativeSetupFunc;

    Function_DalvikBridgeFunc orig_openDexFile_dvm;
    union {
        Function_openDexNativeFunc beforeN;
        Native_openDexNativeFunc_N afterN;
    } orig_openDexNativeFunc_art;

    Function_audioRecordNativeCheckPermission orig_audioRecordNativeCheckPermission;

} patchEnv;


/**
 * Hook for Binder.getCallingUid().
 * On Android 14+, this method is @CriticalNative: ART does NOT pass JNIEnv* or jclass.
 * The env/clazz parameters may be garbage — never dereference them.
 * Always use Environment::ensureCurrentThreadIsAttached() for a real JNIEnv*.
 * When forwarding to the original, passing garbage env/clazz is safe because
 * the original also just calls IPCThreadState::self()->getCallingUid().
 */
static jint getCallingUid_hook(JNIEnv *env, jclass clazz) {
    jint uid;
    if (patchEnv.is_art) {
        if (patchEnv.jni_orig_getCallingUid != NULL) {
            // Forward params as-is; original ignores env/clazz on @CriticalNative
            uid = patchEnv.jni_orig_getCallingUid(env, clazz);
        } else if (patchEnv.IPCThreadState_self != NULL && patchEnv.native_getCallingUid != NULL) {
            // Fallback: call IPCThreadState directly (same as what Binder.getCallingUid does internally)
            uid = patchEnv.native_getCallingUid(patchEnv.IPCThreadState_self());
        } else {
            uid = getuid();
        }
    } else {
        uid = patchEnv.native_getCallingUid(patchEnv.IPCThreadState_self());
    }
    if (nativeEngineClass.get() != NULL && patchEnv.method_onGetCallingUid != NULL) {
        JNIEnv *real_env = Environment::ensureCurrentThreadIsAttached();
        uid = real_env->CallStaticIntMethod(
                nativeEngineClass.get(), patchEnv.method_onGetCallingUid, uid);
    }
    return uid;
}


static jobject new_native_openDexNativeFunc(JNIEnv *env, jclass jclazz, jstring javaSourceName,
                                            jstring javaOutputName, jint options) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(2, stringClass, NULL);

    if (javaSourceName) {
        env->SetObjectArrayElement(array, 0, javaSourceName);
    }
    if (javaOutputName) {
        env->SetObjectArrayElement(array, 1, javaOutputName);
    }
    env->CallStaticVoidMethod(nativeEngineClass.get(), patchEnv.method_onOpenDexFileNative, array);

    jstring newSource = (jstring) env->GetObjectArrayElement(array, 0);
    jstring newOutput = (jstring) env->GetObjectArrayElement(array, 1);

    return patchEnv.orig_openDexNativeFunc_art.beforeN(env, jclazz, newSource, newOutput,
                                                       options);
}

static jobject new_native_openDexNativeFunc_N(JNIEnv *env, jclass jclazz, jstring javaSourceName,
                                              jstring javaOutputName, jint options, jobject loader,
                                              jobject elements) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(2, stringClass, NULL);

    if (javaSourceName) {
        env->SetObjectArrayElement(array, 0, javaSourceName);
    }
    if (javaOutputName) {
        env->SetObjectArrayElement(array, 1, javaOutputName);
    }
    env->CallStaticVoidMethod(nativeEngineClass.get(), patchEnv.method_onOpenDexFileNative, array);

    jstring newSource = (jstring) env->GetObjectArrayElement(array, 0);
    jstring newOutput = (jstring) env->GetObjectArrayElement(array, 1);

    return patchEnv.orig_openDexNativeFunc_art.afterN(env, jclazz, newSource, newOutput, options,
                                                      loader, elements);
}


static void
new_bridge_openDexNativeFunc(const void **args, void *pResult, const void *method, void *self) {

    JNIEnv *env = Environment::ensureCurrentThreadIsAttached();

    const char *source = args[0] == NULL ? NULL : patchEnv.GetCstrFromString((void *) args[0]);
    const char *output = args[1] == NULL ? NULL : patchEnv.GetCstrFromString((void *) args[1]);

    jstring orgSource = source == NULL ? NULL : env->NewStringUTF(source);
    jstring orgOutput = output == NULL ? NULL : env->NewStringUTF(output);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(2, stringClass, NULL);
    if (orgSource) {
        env->SetObjectArrayElement(array, 0, orgSource);
    }
    if (orgOutput) {
        env->SetObjectArrayElement(array, 1, orgOutput);
    }
    env->CallStaticVoidMethod(nativeEngineClass.get(), patchEnv.method_onOpenDexFileNative, array);

    jstring newSource = (jstring) env->GetObjectArrayElement(array, 0);
    jstring newOutput = (jstring) env->GetObjectArrayElement(array, 1);

    const char *_newSource = newSource == NULL ? NULL : env->GetStringUTFChars(newSource, NULL);
    const char *_newOutput = newOutput == NULL ? NULL : env->GetStringUTFChars(newOutput, NULL);

    args[0] = _newSource == NULL ? NULL : patchEnv.GetStringFromCstr(_newSource);
    args[1] = _newOutput == NULL ? NULL : patchEnv.GetStringFromCstr(_newOutput);

    if (source && orgSource) {
        env->ReleaseStringUTFChars(orgSource, source);
    }
    if (output && orgOutput) {
        env->ReleaseStringUTFChars(orgOutput, output);
    }

    patchEnv.orig_openDexFile_dvm(args, pResult, method, self);
}

static jint new_native_cameraNativeSetupFunc_T1(JNIEnv *env, jobject thiz, jobject camera_this,
                                                jint cameraId, jstring packageName) {

    jstring host = env->NewStringUTF(patchEnv.host_packageName);

    return patchEnv.orig_native_cameraNativeSetupFunc.t1(env, thiz, camera_this,
                                                         cameraId,
                                                         host);
}

static jint new_native_cameraNativeSetupFunc_T2(JNIEnv *env, jobject thiz, jobject camera_this,
                                                jint cameraId, jint halVersion,
                                                jstring packageName) {

    jstring host = env->NewStringUTF(patchEnv.host_packageName);

    return patchEnv.orig_native_cameraNativeSetupFunc.t2(env, thiz, camera_this, cameraId,
                                                         halVersion, host);
}

static jint new_native_cameraNativeSetupFunc_T3(JNIEnv *env, jobject thiz, jobject camera_this,
                                                jint cameraId, jint halVersion,
                                                jstring packageName, jboolean option) {

    jstring host = env->NewStringUTF(patchEnv.host_packageName);

    return patchEnv.orig_native_cameraNativeSetupFunc.t3(env, thiz, camera_this, cameraId,
                                                         halVersion, host, option);
}

static jint new_native_cameraNativeSetupFunc_T4(JNIEnv *env, jobject thiz, jobject camera_this,
                                                jint cameraId,
                                                jstring packageName, jboolean option) {

    jstring host = env->NewStringUTF(patchEnv.host_packageName);

    return patchEnv.orig_native_cameraNativeSetupFunc.t4(env, thiz, camera_this, cameraId, host,
                                                         option);
}


static jint
new_native_audioRecordNativeCheckPermission(JNIEnv *env, jobject thiz, jstring _packagename) {
    jstring host = env->NewStringUTF(patchEnv.host_packageName);
    return patchEnv.orig_audioRecordNativeCheckPermission(env, thiz, host);
}


static void
new_bridge_cameraNativeSetupFunc(const void **args, void *pResult, const void *method, void *self) {
    // args[0] = this
    switch (patchEnv.cameraMethodType) {
        case 1:
            args[4] = patchEnv.GetStringFromCstr(patchEnv.host_packageName);
            break;
        case 2:
            args[5] = patchEnv.GetStringFromCstr(patchEnv.host_packageName);
            break;
        case 3:
            args[5] = patchEnv.GetStringFromCstr(patchEnv.host_packageName);
            break;
        case 4:
            args[4] = patchEnv.GetStringFromCstr(patchEnv.host_packageName);
            break;
    }
    patchEnv.orig_cameraNativeSetup_dvm(args, pResult, method, self);
}

void mark() {
    // Do nothing
};

// Second probe function for snapshot-based offset measurement.
// Must have a different address from mark() so that re-registering
// nativeMark changes the JNI function pointer in the ArtMethod struct.
static void mark2() {
    volatile int dummy = 1;
    (void) dummy;
}

static size_t getArtMethodAddress(jobject javaMethod, jmethodID methodId) {
    if (patchEnv.api_level < 30) {
        return (size_t) methodId;
    } else {
        JNIEnv *env = Environment::current();
        jclass executableClass = env->FindClass("java/lang/reflect/Executable");
        jfieldID artMethod = env->GetFieldID(executableClass, "artMethod", "J");
        jlong addr = env->GetLongField(javaMethod, artMethod);
        return addr;
    }
}

void measureNativeOffset(bool isArt) {

    JNIEnv *env = Environment::current();
    jmethodID markMethod = nativeEngineClass->getStaticMethod<void(void)>("nativeMark").getId();

    jobject method = env->ToReflectedMethod(nativeEngineClass.get(), markMethod, JNI_TRUE);
    size_t startAddress = (size_t) getArtMethodAddress(method, markMethod);
    size_t targetAddress = (size_t) mark;
    if (isArt && patchEnv.art_work_around_app_jni_bugs) {
        targetAddress = (size_t) patchEnv.art_work_around_app_jni_bugs;
    }

    int offset = 0;
    bool found = false;
    bool found_via_direct = false;

    // Strategy 1: Direct scan for target address (original approach)
    while (offset < 200) {
        if (*((size_t *) (startAddress + offset)) == targetAddress) {
            found = true;
            found_via_direct = true;
            ALOGI("measureNativeOffset: found via direct scan at offset %d (target=%p)",
                  offset, (void *) targetAddress);
            break;
        }
        offset += 4;
    }

    // Strategy 2: If art_work_around_app_jni_bugs was used, retry with raw mark address
    if (!found && isArt && patchEnv.art_work_around_app_jni_bugs) {
        targetAddress = (size_t) mark;
        offset = 0;
        while (offset < 200) {
            if (*((size_t *) (startAddress + offset)) == targetAddress) {
                found = true;
                found_via_direct = true;
                ALOGI("measureNativeOffset: found via raw mark address at offset %d", offset);
                break;
            }
            offset += 4;
        }
    }

    // Strategy 3: Snapshot comparison — handles binary translation layers
    // (e.g. Berberis on x86_64 emulators running ARM64 native code).
    // We re-register nativeMark with a different native function and find
    // which pointer-sized slot in the ArtMethod struct changed.
    if (!found && isArt) {
        ALOGI("measureNativeOffset: direct scans failed, trying snapshot comparison...");
        const int SCAN_SIZE = 384;
        uint8_t snapshot1[384];
        uint8_t snapshot2[384];

        // Snapshot current state (nativeMark -> mark)
        memcpy(snapshot1, (void *) startAddress, SCAN_SIZE);

        // Re-register nativeMark with a different native function
        JNINativeMethod nm2 = {(char *) "nativeMark", (char *) "()V", (void *) mark2};
        env->RegisterNatives(nativeEngineClass.get(), &nm2, 1);

        // Snapshot new state (nativeMark -> mark2)
        memcpy(snapshot2, (void *) startAddress, SCAN_SIZE);

        // Find the changed pointer-sized slot.
        // Use pointer-aligned stepping to avoid false positives from
        // smaller metadata fields (method_index, hotness_count, etc.)
        // that may also change during RegisterNatives.
        int step = (int) sizeof(size_t);  // 8 on 64-bit, 4 on 32-bit
        for (int i = 0; i <= SCAN_SIZE - (int) sizeof(size_t); i += step) {
            size_t val1 = *(size_t *) (snapshot1 + i);
            size_t val2 = *(size_t *) (snapshot2 + i);
            if (val1 != val2 && val1 != 0 && val2 != 0) {
                offset = i;
                found = true;
                ALOGI("measureNativeOffset: found via snapshot diff at offset %d "
                      "(before=%p, after=%p)", offset, (void *) val1, (void *) val2);
                break;
            }
        }

        // Restore original registration
        JNINativeMethod nm_restore = {(char *) "nativeMark", (char *) "()V", (void *) mark};
        env->RegisterNatives(nativeEngineClass.get(), &nm_restore, 1);
    }

    if (found) {
        patchEnv.native_offset = offset;
        patchEnv.translation_layer = !found_via_direct;
        ALOGI("measureNativeOffset: final offset = %d (startAddr=%p, translation_layer=%d)",
              offset, (void *) startAddress, patchEnv.translation_layer);
        if (patchEnv.translation_layer) {
            ALOGI("measureNativeOffset: detected binary translation layer, "
                  "will use RegisterNatives for hooks");
        }
        if (!isArt) {
            patchEnv.native_offset += (sizeof(int) + sizeof(void *));
        }
    } else {
        ALOGE("measureNativeOffset: FAILED to determine offset after all strategies "
              "(searched from %p for %p)", (void *) startAddress, (void *) targetAddress);
    }
}


inline void replaceGetCallingUid(jboolean isArt) {
    auto binderClass = findClassLocal("android/os/Binder");
    if (isArt) {
        JNIEnv *env = Environment::current();
        jmethodID methodId = binderClass->getStaticMethod<jint(void)>("getCallingUid").getId();

        // On API 30+, jmethodID may not be the real ArtMethod address;
        // use Executable.artMethod field via reflection instead.
        size_t mtd_getCallingUid;
        if (patchEnv.api_level >= 30) {
            jobject reflectedMethod = env->ToReflectedMethod(binderClass.get(), methodId, JNI_TRUE);
            mtd_getCallingUid = getArtMethodAddress(reflectedMethod, methodId);
        } else {
            mtd_getCallingUid = (size_t) methodId;
        }

        int nativeFuncOffset = patchEnv.native_offset;

        if (patchEnv.translation_layer) {
            // Translation layer (Berberis): can't hook native methods at all.
            // RegisterNatives from ARM64-translated code doesn't create proper x86_64
            // trampolines — ART stores raw ARM64 addresses and crashes (SEGV_ACCERR).
            // Skip the hook; getCallingUid will use ART's original implementation.
            // Virtual UID mapping is degraded but the app won't crash.
            ALOGI("replaceGetCallingUid: skipping on translation layer");
            return;
        }

        if (nativeFuncOffset > 0 && mtd_getCallingUid != 0) {
            // Real device: direct ArtMethod swap
            void **jniFuncPtr = (void **) (mtd_getCallingUid + nativeFuncOffset);
            patchEnv.jni_orig_getCallingUid = (Function_getCallingUid) (*jniFuncPtr);
            if (patchEnv.jni_orig_getCallingUid != NULL) {
                *jniFuncPtr = (void *) getCallingUid_hook;
                ALOGI("replaceGetCallingUid: ArtMethod swap OK (offset=%d, orig=%p)",
                      nativeFuncOffset, patchEnv.jni_orig_getCallingUid);
                return;
            }
        }
        // Fallback: ArtMethod pointer swap failed, use registerNatives instead.
        // getCallingUid_hook will use IPCThreadState as the original implementation.
        ALOGI("replaceGetCallingUid: ArtMethod swap failed (offset=%d, addr=%p), using registerNatives",
              nativeFuncOffset, (void *) mtd_getCallingUid);
        patchEnv.jni_orig_getCallingUid = NULL;
        JNINativeMethod nm = {(char *) "getCallingUid", (char *) "()I", (void *) getCallingUid_hook};
        env->RegisterNatives(binderClass.get(), &nm, 1);
    } else {
        JNIEnv *env = Environment::current();
        JNINativeMethod nm = {(char *) "getCallingUid", (char *) "()I", (void *) getCallingUid_hook};
        env->RegisterNatives(binderClass.get(), &nm, 1);
    }
}

inline void
replaceOpenDexFileMethod(jobject javaMethod, jboolean isArt, int apiLevel) {
    if (!javaMethod) {
        ALOGW("replaceOpenDexFileMethod: javaMethod is null, skipping");
        return;
    }

    JNIEnv *env = Environment::current();
    jmethodID openDexNative = env->FromReflectedMethod(javaMethod);
    int nativeFuncOffset = patchEnv.native_offset;

    if (patchEnv.translation_layer) {
        // Translation layer (Berberis): skip openDexFile hook entirely.
        // Can't call x86_64 original function pointer from ARM64 translated code.
        // IO redirect (NativeEngine.enableIORedirect) already handles file path rewriting;
        // this hook only controls odex output placement, which is non-essential.
        ALOGI("replaceOpenDexFileMethod: skipping on translation layer (IO redirect handles paths)");
        return;
    }

    if (nativeFuncOffset == 0 && isArt) {
        ALOGW("replaceOpenDexFileMethod: native_offset is 0, skipping hook "
              "(dex path override disabled for this session)");
        return;
    }

    if (!isArt) {
        // Dalvik path (legacy)
        size_t mtd_openDexNative = (size_t) openDexNative;
        void **jniFuncPtr = (void **) (mtd_openDexNative + nativeFuncOffset);
        patchEnv.orig_openDexFile_dvm = (Function_DalvikBridgeFunc) (*jniFuncPtr);
        *jniFuncPtr = (void *) new_bridge_openDexNativeFunc;
        ALOGI("replaceOpenDexFileMethod: Dalvik hook installed");
        return;
    }

    // ART path
    size_t mtd_openDexNative = getArtMethodAddress(javaMethod, openDexNative);
    void **jniFuncPtr = (void **) (mtd_openDexNative + nativeFuncOffset);

    // Save the original native function pointer from ArtMethod
    if (apiLevel < 24) {
        patchEnv.orig_openDexNativeFunc_art.beforeN = (Function_openDexNativeFunc) (*jniFuncPtr);
    } else {
        patchEnv.orig_openDexNativeFunc_art.afterN = (Native_openDexNativeFunc_N) (*jniFuncPtr);
    }

    if (patchEnv.translation_layer) {
        // Translation layer (Berberis): use RegisterNatives instead of direct ArtMethod patching.
        // Direct patching writes ARM64 pointers into x86_64 ART structures, causing SEGV_ACCERR.
        jclass dexFileClass = env->FindClass("dalvik/system/DexFile");
        if (dexFileClass != NULL) {
            bool registered = false;
            if (apiLevel < 24) {
                JNINativeMethod nm = {
                    (char *) "openDexFileNative",
                    (char *) "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Object;",
                    (void *) new_native_openDexNativeFunc
                };
                registered = (env->RegisterNatives(dexFileClass, &nm, 1) == 0);
                if (!registered) env->ExceptionClear();
            } else {
                JNINativeMethod nm = {
                    (char *) "openDexFileNative",
                    (char *) "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/ClassLoader;"
                              "[Ldalvik/system/DexPathList$Element;)Ljava/lang/Object;",
                    (void *) new_native_openDexNativeFunc_N
                };
                registered = (env->RegisterNatives(dexFileClass, &nm, 1) == 0);
                if (!registered) env->ExceptionClear();
            }
            ALOGI("replaceOpenDexFileMethod: RegisterNatives %s (translation_layer, orig=%p)",
                  registered ? "OK" : "FAILED", (void *) *jniFuncPtr);
        }
    } else {
        // Real device: direct ArtMethod patching
        if (apiLevel < 24) {
            *jniFuncPtr = (void *) new_native_openDexNativeFunc;
        } else {
            *jniFuncPtr = (void *) new_native_openDexNativeFunc_N;
        }
        ALOGI("replaceOpenDexFileMethod: ArtMethod swap OK (offset=%d, artMethod=%p)",
              nativeFuncOffset, (void *) mtd_openDexNative);
    }
}


inline void
replaceCameraNativeSetupMethod(jobject javaMethod, jboolean isArt, int apiLevel) {

    if (!javaMethod) {
        return;
    }

    int nativeFuncOffset = patchEnv.native_offset;
    if ((nativeFuncOffset == 0 && isArt) || patchEnv.translation_layer) {
        if (patchEnv.translation_layer) {
            ALOGW("replaceCameraNativeSetupMethod: skipping on translation layer");
        } else {
            ALOGW("replaceCameraNativeSetupMethod: native_offset is 0, skipping hook");
        }
        return;
    }

    jmethodID cameraNativeSetup = Environment::current()->FromReflectedMethod(javaMethod);
    size_t mtd_cameraNativeSetup = getArtMethodAddress(javaMethod, cameraNativeSetup);
    void **jniFuncPtr = (void **) (mtd_cameraNativeSetup + nativeFuncOffset);

    if (!isArt) {
        patchEnv.orig_cameraNativeSetup_dvm = (Function_DalvikBridgeFunc) (*jniFuncPtr);
        *jniFuncPtr = (void *) new_bridge_cameraNativeSetupFunc;
    } else {
        switch (patchEnv.cameraMethodType) {
            case 1:
                patchEnv.orig_native_cameraNativeSetupFunc.t1 = (Function_cameraNativeSetupFunc_T1) (*jniFuncPtr);
                *jniFuncPtr = (void *) new_native_cameraNativeSetupFunc_T1;
                break;
            case 2:
                patchEnv.orig_native_cameraNativeSetupFunc.t2 = (Function_cameraNativeSetupFunc_T2) (*jniFuncPtr);
                *jniFuncPtr = (void *) new_native_cameraNativeSetupFunc_T2;
                break;
            case 3:
                patchEnv.orig_native_cameraNativeSetupFunc.t3 = (Function_cameraNativeSetupFunc_T3) (*jniFuncPtr);
                *jniFuncPtr = (void *) new_native_cameraNativeSetupFunc_T3;
                break;
            case 4:
                patchEnv.orig_native_cameraNativeSetupFunc.t4 = (Function_cameraNativeSetupFunc_T4) (*jniFuncPtr);
                *jniFuncPtr = (void *) new_native_cameraNativeSetupFunc_T4;
                break;
        }
    }

}


void
replaceAudioRecordNativeCheckPermission(jobject javaMethod, jboolean isArt, int api) {
    if (!javaMethod || !isArt) {
        return;
    }

    int nativeFuncOffset = patchEnv.native_offset;
    if (nativeFuncOffset == 0 || patchEnv.translation_layer) {
        if (patchEnv.translation_layer) {
            ALOGW("replaceAudioRecordNativeCheckPermission: skipping on translation layer");
        } else {
            ALOGW("replaceAudioRecordNativeCheckPermission: native_offset is 0, skipping hook");
        }
        return;
    }

    jmethodID methodStruct = Environment::current()->FromReflectedMethod(javaMethod);
    size_t mtd_methodStruct = getArtMethodAddress(javaMethod, methodStruct);
    void **funPtr = (void **) (mtd_methodStruct + nativeFuncOffset);
    patchEnv.orig_audioRecordNativeCheckPermission = (Function_audioRecordNativeCheckPermission) (*funPtr);
    *funPtr = (void *) new_native_audioRecordNativeCheckPermission;
}


/**
 * Only called once.
 * @param javaMethod Method from Java
 * @param isArt Dalvik or Art
 * @param apiLevel Api level from Java
 */
void hookAndroidVM(JArrayClass<jobject> javaMethods,
                   jstring packageName, jboolean isArt, jint apiLevel,
                   jint cameraMethodType) {

    JNIEnv *env = Environment::current();

    JNINativeMethod methods[] = {
            NATIVE_METHOD((void *) mark, "nativeMark", "()V"),
    };
    if (env->RegisterNatives(nativeEngineClass.get(), methods, 1) < 0) {
        return;
    }
    patchEnv.is_art = isArt;
    patchEnv.cameraMethodType = cameraMethodType;
    patchEnv.host_packageName = (char *) env->GetStringUTFChars(packageName,
                                                                NULL);
    patchEnv.api_level = apiLevel;
    void *soInfo = getDvmOrArtSOHandle();
    patchEnv.method_onGetCallingUid = nativeEngineClass->getStaticMethod<jint(jint)>(
            "onGetCallingUid").getId();
    patchEnv.method_onOpenDexFileNative = env->GetStaticMethodID(nativeEngineClass.get(),
                                                                 "onOpenDexFileNative",
                                                                 "([Ljava/lang/String;)V");

    // Always resolve IPCThreadState functions (primary for Dalvik, fallback for ART on API 34+)
    patchEnv.IPCThreadState_self = (int (*)(void)) dlsym(RTLD_DEFAULT,
                                                         "_ZN7android14IPCThreadState4selfEv");
    patchEnv.native_getCallingUid = (int (*)(int)) dlsym(RTLD_DEFAULT,
                                                         "_ZNK7android14IPCThreadState13getCallingUidEv");
    if (patchEnv.IPCThreadState_self == NULL) {
        patchEnv.IPCThreadState_self = (int (*)(void)) dlsym(RTLD_DEFAULT,
                                                             "_ZN7android14IPCThreadState13getCallingUidEv");
    }
    ALOGI("hookAndroidVM: IPCThreadState_self=%p, native_getCallingUid=%p",
          patchEnv.IPCThreadState_self, patchEnv.native_getCallingUid);

    if (isArt) {
        patchEnv.art_work_around_app_jni_bugs = dlsym(soInfo, "art_work_around_app_jni_bugs");
    } else {
        // workaround for dlsym returns null when system has libhoudini
        void *h = dlopen("/system/lib/libandroid_runtime.so", RTLD_LAZY);
        if (h != NULL) {
            dlclose(h);
        }

        patchEnv.GetCstrFromString = (char *(*)(void *)) dlsym(soInfo,
                                                               "_Z23dvmCreateCstrFromStringPK12StringObject");
        if (!patchEnv.GetCstrFromString) {
            patchEnv.GetCstrFromString = (char *(*)(void *)) dlsym(soInfo,
                                                                   "dvmCreateCstrFromString");
        }
        patchEnv.GetStringFromCstr = (void *(*)(const char *)) dlsym(soInfo,
                                                                     "_Z23dvmCreateStringFromCstrPKc");
        if (!patchEnv.GetStringFromCstr) {
            patchEnv.GetStringFromCstr = (void *(*)(const char *)) dlsym(soInfo,
                                                                         "dvmCreateStringFromCstr");
        }
    }
    measureNativeOffset(isArt);
    replaceGetCallingUid(isArt);

    replaceOpenDexFileMethod(javaMethods.getElement(OPEN_DEX).get(), isArt,
                             apiLevel);
    replaceCameraNativeSetupMethod(javaMethods.getElement(CAMERA_SETUP).get(),
                                   isArt, apiLevel);
    replaceAudioRecordNativeCheckPermission(javaMethods.getElement(
            AUDIO_NATIVE_CHECK_PERMISSION).get(),
                                            isArt, apiLevel);
}

bool processNothing(void* thiz, void* new_methods){ return true; }
bool (*orig_ProcessProfilingInfo)(void*, void*);

bool compileNothing(void* thiz, void* thread, void* method, bool osr) { return false; }
bool (*orig_CompileNothing)(void* thiz, void* thread, void* method, bool osr);

void (*org_notifyJitActivity)(void *);
void notifyNothing(void *thiz) {
    return;
}

void disableJit(int apiLevel) {
#ifdef __arm__
    void *libart = fake_dlopen("/system/lib/libart.so", RTLD_NOW);
    if (libart) {
        // disable profile.
        void *processProfilingInfo = NULL;
        const char *processProfileInfoFunc =
                apiLevel < 26 ? "_ZN3art12ProfileSaver20ProcessProfilingInfoEPt" :
                "_ZN3art12ProfileSaver20ProcessProfilingInfoEbPt";
        processProfilingInfo = fake_dlsym(libart, processProfileInfoFunc);
        ALOGE("processProfileingInfo: %p", processProfilingInfo);
        if (processProfilingInfo) {
            MSHookFunction(processProfilingInfo, (void*)processNothing, (void**)&orig_ProcessProfilingInfo);
        }

        // disable jit
        void *compileMethod = NULL;
        compileMethod = fake_dlsym(libart,
                                   "_ZN3art3jit3Jit13CompileMethodEPNS_9ArtMethodEPNS_6ThreadEb");
        ALOGE("compileMethod: %p", compileMethod);
        if (compileMethod) {
            MSHookFunction(compileMethod, (void*) compileNothing, (void**) &orig_CompileNothing);
        }

        void *notifyJitActivity = fake_dlsym(libart, "_ZN3art12ProfileSaver17NotifyJitActivityEv");
        if (notifyJitActivity) {
            MSHookFunction(notifyJitActivity, (void *) notifyNothing,
                          (void **) &org_notifyJitActivity);
        }
    }
#endif
}

void *getDvmOrArtSOHandle() {
    char so_name[25] = {0};
    __system_property_get("persist.sys.dalvik.vm.lib.2", so_name);
    if (strlen(so_name) == 0) {
        __system_property_get("persist.sys.dalvik.vm.lib", so_name);
    }
    void *soInfo = dlopen(so_name, 0);
    if (!soInfo) {
        soInfo = RTLD_DEFAULT;
    }
    return soInfo;
}
