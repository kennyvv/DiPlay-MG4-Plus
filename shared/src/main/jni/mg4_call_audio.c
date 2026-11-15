/*
 * MG4 stock CarPlay call audio path (test).
 *
 * The head unit's audio HAL cancels call echo (Nuance ECNR) only for audio on its "Phone Direct Out"
 * output (AUDIO_DEVICE_OUT_HDMI 0x400) while the "Carplay Phone input" mic is open. The car's
 * AudioPolicyManager sends a track there when it asks for AUDIO_OUTPUT_FLAG_DIRECT. The stock AllGo
 * library (libCarPlayPlatformAudio.so) does:
 *   AudioSystem::setDeviceConnectionState(0x400, AVAILABLE, "", "carplay_source=phone")
 *   new AudioTrack(AUDIO_STREAM_VOICE_CALL, rate, PCM_16_BIT, MONO, ..., AUDIO_OUTPUT_FLAG_DIRECT, ...)
 *   new AudioRecord(AUDIO_SOURCE_MIC, rate, PCM_16_BIT, IN_MONO, ..., input flags 0x200, ...)
 * Java cannot ask for a direct PCM output or vendor input flags, so this does the same natively.
 *
 * android::AudioTrack and android::AudioRecord are private C++ classes in libaudioclient.so. An app
 * on /data may not dlopen that library on Android 9 (linker namespaces), but it is already loaded in
 * every app process, so the symbols are looked up in the loaded copy: dl_iterate_phdr gives its path
 * and load bias, and .dynsym is read from the file. Only the MG4 Android 9 firmware uses this.
 */
#include <jni.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "DiPlay-StockCall"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* Android 9 system/media/audio/include/system/audio-base.h values. */
#define STREAM_VOICE_CALL 0
#define FORMAT_PCM_16_BIT 1
#define CHANNEL_OUT_MONO 0x1
#define CHANNEL_IN_MONO 0x10
#define OUTPUT_FLAG_DIRECT 0x1
#define DEVICE_OUT_HDMI 0x400          /* "Phone Direct Out" in audio_policy_configuration.xml */
#define TRANSFER_DEFAULT 0             /* no callback: becomes TRANSFER_SYNC */
#define OBJECT_BYTES 4096              /* larger than sizeof(AudioTrack) and sizeof(AudioRecord) */

typedef void (*ctor_fn)(void *self);
typedef void (*record_ctor_fn)(void *self, const void *op_package_name);
typedef void (*string16_ctor_fn)(void *self, const char *text);
typedef void (*string16_dtor_fn)(void *self);
typedef void (*ref_fn)(const void *self, const void *id);
typedef int (*track_set_fn)(void *self, int stream, uint32_t rate, int format, uint32_t mask,
                            size_t frame_count, int flags, void *cbf, void *user, int notification_frames,
                            const void *shared_buffer, bool thread_can_call_java, int session,
                            int transfer_type, const void *offload_info, uint32_t uid, int pid,
                            const void *attributes, bool do_not_reconnect, float max_speed,
                            int selected_device);
typedef int (*record_set_fn)(void *self, int source, uint32_t rate, int format, uint32_t mask,
                             size_t frame_count, void *cbf, void *user, uint32_t notification_frames,
                             bool thread_can_call_java, int session, int transfer_type, int flags,
                             uint32_t uid, int pid, const void *attributes, int selected_device);
typedef int (*start_fn)(void *self);
typedef int (*record_start_fn)(void *self, int event, int session);
typedef void (*stop_fn)(void *self);
typedef ssize_t (*io_fn)(void *self, void *buffer, size_t size, bool blocking);
typedef int (*get_handle_fn)(const void *self);
typedef int (*device_state_fn)(uint32_t device, int state, const char *address, const char *name);

static struct {
    bool resolved;
    char error[256];
    ctor_fn track_ctor;
    track_set_fn track_set;
    start_fn track_start;
    stop_fn track_stop;
    io_fn track_write;
    get_handle_fn track_output;
    record_ctor_fn record_ctor;
    record_set_fn record_set;
    record_start_fn record_start;
    stop_fn record_stop;
    io_fn record_read;
    string16_ctor_fn string16_ctor;
    string16_dtor_fn string16_dtor;
    ref_fn inc_strong;
    ref_fn dec_strong;
    device_state_fn set_device_state;
} api;

struct loaded_lib {
    const char *suffix;
    char path[256];
    uintptr_t bias;
    bool found;
};

static int find_loaded(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    struct loaded_lib *lib = data;
    const char *name = info->dlpi_name;
    if (name == NULL) return 0;
    size_t name_len = strlen(name), suffix_len = strlen(lib->suffix);
    if (name_len < suffix_len || strcmp(name + name_len - suffix_len, lib->suffix) != 0) return 0;
    snprintf(lib->path, sizeof(lib->path), "%s", name);
    lib->bias = (uintptr_t) info->dlpi_addr;
    lib->found = true;
    return 1;
}

/* Resolves each name in names[] (NULL-terminated) to out[] from the loaded copy of a library. */
static bool resolve_from(const char *suffix, const char *const *names, void **out, size_t count) {
    struct loaded_lib lib = {.suffix = suffix};
    dl_iterate_phdr(find_loaded, &lib);
    if (!lib.found) {
        snprintf(api.error, sizeof(api.error), "%s not loaded in this process", suffix);
        return false;
    }
    int fd = open(lib.path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        snprintf(api.error, sizeof(api.error), "open %s: %s", lib.path, strerror(errno));
        return false;
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < (off_t) sizeof(ElfW(Ehdr))) {
        close(fd);
        snprintf(api.error, sizeof(api.error), "stat %s failed", lib.path);
        return false;
    }
    const uint8_t *file = mmap(NULL, (size_t) st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (file == MAP_FAILED) {
        snprintf(api.error, sizeof(api.error), "mmap %s failed", lib.path);
        return false;
    }
    const ElfW(Ehdr) *eh = (const ElfW(Ehdr) *) file;
    const ElfW(Shdr) *sections = (const ElfW(Shdr) *) (file + eh->e_shoff);
    size_t found = 0;
    for (int s = 0; s < eh->e_shnum; s++) {
        if (sections[s].sh_type != SHT_DYNSYM) continue;
        const ElfW(Sym) *syms = (const ElfW(Sym) *) (file + sections[s].sh_offset);
        size_t n = sections[s].sh_size / sizeof(ElfW(Sym));
        const char *strtab = (const char *) (file + sections[sections[s].sh_link].sh_offset);
        for (size_t i = 0; i < n; i++) {
            if (syms[i].st_value == 0 || syms[i].st_shndx == SHN_UNDEF) continue;
            const char *sym_name = strtab + syms[i].st_name;
            for (size_t k = 0; k < count; k++) {
                if (out[k] == NULL && strcmp(sym_name, names[k]) == 0) {
                    out[k] = (void *) (lib.bias + syms[i].st_value);
                    found++;
                }
            }
        }
    }
    munmap((void *) file, (size_t) st.st_size);
    for (size_t k = 0; k < count; k++) {
        if (out[k] == NULL) {
            snprintf(api.error, sizeof(api.error), "%s: missing %s", suffix, names[k]);
            return false;
        }
    }
    return found == count;
}

static bool resolve(void) {
    if (api.resolved) return true;
    static const char *const client[] = {
        "_ZN7android10AudioTrackC1Ev",
        "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_tPFviPvS4_ES4_iRKNS_2spINS_7IMemoryEEEb15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tjiPK18audio_attributes_tbfi",
        "_ZN7android10AudioTrack5startEv",
        "_ZN7android10AudioTrack4stopEv",
        "_ZN7android10AudioTrack5writeEPKvmb",
        "_ZNK7android10AudioTrack9getOutputEv",
        "_ZN7android11AudioRecordC1ERKNS_8String16E",
        "_ZN7android11AudioRecord3setE14audio_source_tj14audio_format_tjmPFviPvS3_ES3_jb15audio_session_tNS0_13transfer_typeE19audio_input_flags_tjiPK18audio_attributes_ti",
        "_ZN7android11AudioRecord5startENS_11AudioSystem12sync_event_tE15audio_session_t",
        "_ZN7android11AudioRecord4stopEv",
        "_ZN7android11AudioRecord4readEPvmb",
        "_ZN7android11AudioSystem24setDeviceConnectionStateEj24audio_policy_dev_state_tPKcS3_",
    };
    static const char *const utils[] = {
        "_ZN7android8String16C1EPKc",
        "_ZN7android8String16D1Ev",
        "_ZNK7android7RefBase9incStrongEPKv",
        "_ZNK7android7RefBase9decStrongEPKv",
    };
    void *c[sizeof(client) / sizeof(client[0])] = {0};
    void *u[sizeof(utils) / sizeof(utils[0])] = {0};
    if (!resolve_from("/libaudioclient.so", client, c, sizeof(c) / sizeof(c[0]))) return false;
    if (!resolve_from("/libutils.so", utils, u, sizeof(u) / sizeof(u[0]))) return false;
    api.track_ctor = (ctor_fn) c[0];
    api.track_set = (track_set_fn) c[1];
    api.track_start = (start_fn) c[2];
    api.track_stop = (stop_fn) c[3];
    api.track_write = (io_fn) c[4];
    api.track_output = (get_handle_fn) c[5];
    api.record_ctor = (record_ctor_fn) c[6];
    api.record_set = (record_set_fn) c[7];
    api.record_start = (record_start_fn) c[8];
    api.record_stop = (stop_fn) c[9];
    api.record_read = (io_fn) c[10];
    api.set_device_state = (device_state_fn) c[11];
    api.string16_ctor = (string16_ctor_fn) u[0];
    api.string16_dtor = (string16_dtor_fn) u[1];
    api.inc_strong = (ref_fn) u[2];
    api.dec_strong = (ref_fn) u[3];
    api.resolved = true;
    api.error[0] = 0;
    return true;
}

/* A RefBase-derived object: RefBase is the first base of both classes, at offset 0. */
static void *new_object(void) { return calloc(1, OBJECT_BYTES); }
static void release_object(void *object) {
    if (object != NULL) api.dec_strong(object, object); /* last strong ref deletes it */
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_media_StockCallAudio_nativeStatus(JNIEnv *env, jclass cls) {
    (void) cls;
    bool ok = resolve();
    return (*env)->NewStringUTF(env, ok ? "ok" : api.error);
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_StockCallAudio_nativeSetPhoneDevice(JNIEnv *env, jclass cls, jboolean on) {
    (void) env;
    (void) cls;
    if (!resolve()) return -1000;
    int result = api.set_device_state(DEVICE_OUT_HDMI, on ? 1 : 0, "", "carplay_source=phone");
    LOGI("setDeviceConnectionState(0x400, %d, carplay_source=phone) = %d", on ? 1 : 0, result);
    return result;
}

/* Returns a handle, or a negative status from AudioTrack::set / start. */
JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_StockCallAudio_nativeOpenOutput(JNIEnv *env, jclass cls, jint rate) {
    (void) env;
    (void) cls;
    if (!resolve()) return -1000;
    void *track = new_object();
    if (track == NULL) return -12;
    api.track_ctor(track);
    api.inc_strong(track, track);
    void *no_shared_buffer = NULL; /* sp<IMemory>() */
    int status = api.track_set(track, STREAM_VOICE_CALL, (uint32_t) rate, FORMAT_PCM_16_BIT,
                               CHANNEL_OUT_MONO, 0, OUTPUT_FLAG_DIRECT, NULL, NULL, 0, &no_shared_buffer,
                               false, 0, TRANSFER_DEFAULT, NULL, (uint32_t) -1, -1, NULL, false, 1.0f, 0);
    if (status != 0) {
        LOGW("AudioTrack::set(VOICE_CALL, %d Hz, DIRECT) = %d", rate, status);
        release_object(track);
        return status < 0 ? status : -status;
    }
    status = api.track_start(track);
    if (status != 0) {
        LOGW("AudioTrack::start = %d", status);
        release_object(track);
        return status < 0 ? status : -status;
    }
    LOGI("call output open rate=%d output=%d", rate, api.track_output(track));
    return (jlong) (intptr_t) track;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_StockCallAudio_nativeOutputHandle(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    if (handle <= 0 || !api.resolved) return -1;
    return api.track_output((void *) (intptr_t) handle);
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_StockCallAudio_nativeWrite(JNIEnv *env, jclass cls, jlong handle,
                                                            jbyteArray data, jint offset, jint length) {
    (void) cls;
    if (handle <= 0 || !api.resolved) return -1;
    jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (bytes == NULL) return -1;
    ssize_t written = api.track_write((void *) (intptr_t) handle, bytes + offset, (size_t) length, true);
    (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
    return (jint) written;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_StockCallAudio_nativeCloseOutput(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    if (handle <= 0 || !api.resolved) return;
    void *track = (void *) (intptr_t) handle;
    api.track_stop(track);
    release_object(track);
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_StockCallAudio_nativeOpenInput(JNIEnv *env, jclass cls, jint rate,
                                                                jstring package_name, jint source, jint flags) {
    (void) cls;
    if (!resolve()) return -1000;
    const char *package = (*env)->GetStringUTFChars(env, package_name, NULL);
    uint8_t name[64] = {0}; /* android::String16 is one pointer */
    api.string16_ctor(name, package != NULL ? package : "");
    if (package != NULL) (*env)->ReleaseStringUTFChars(env, package_name, package);
    void *record = new_object();
    if (record == NULL) {
        api.string16_dtor(name);
        return -12;
    }
    api.record_ctor(record, name);
    api.string16_dtor(name);
    api.inc_strong(record, record);
    int status = api.record_set(record, source, (uint32_t) rate, FORMAT_PCM_16_BIT, CHANNEL_IN_MONO, 0,
                                NULL, NULL, 0, false, 0, TRANSFER_DEFAULT, flags,
                                (uint32_t) -1, -1, NULL, 0);
    if (status != 0) {
        LOGW("AudioRecord::set(source %d, %d Hz, flags 0x%x) = %d", source, rate, flags, status);
        release_object(record);
        return status < 0 ? status : -status;
    }
    status = api.record_start(record, 0, 0);
    if (status != 0) {
        LOGW("AudioRecord::start = %d", status);
        release_object(record);
        return status < 0 ? status : -status;
    }
    LOGI("car input open source=%d rate=%d flags=0x%x", source, rate, flags);
    return (jlong) (intptr_t) record;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_StockCallAudio_nativeRead(JNIEnv *env, jclass cls, jlong handle,
                                                           jbyteArray data, jint offset, jint length) {
    (void) cls;
    if (handle <= 0 || !api.resolved) return -1;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return -1;
    ssize_t count = api.record_read((void *) (intptr_t) handle, bytes + offset, (size_t) length, true);
    (*env)->ReleaseByteArrayElements(env, data, bytes, 0);
    return (jint) count;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_StockCallAudio_nativeCloseInput(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    if (handle <= 0 || !api.resolved) return;
    void *record = (void *) (intptr_t) handle;
    api.record_stop(record);
    release_object(record);
}

/* Stops capture so a blocked read returns; the object stays until nativeCloseInput. */
JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_StockCallAudio_nativeStopInput(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    if (handle <= 0 || !api.resolved) return;
    api.record_stop((void *) (intptr_t) handle);
}
