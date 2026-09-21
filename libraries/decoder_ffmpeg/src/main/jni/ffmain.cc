#include <jni.h>

#include <cstdio>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/defs.h"
#include "libavcodec/version.h"
#include "libavutil/avutil.h"
#include "libavutil/version.h"
#include "libswresample/swresample.h"
#include "libswresample/version.h"
}

#include "ffcommon.h"
static bool hasExpectedVersion(const char *library, unsigned runtimeVersion,
                               unsigned headerVersion) {
  if (runtimeVersion == headerVersion) {
    return true;
  }
  LOGE("%s version mismatch: headers=%d.%d.%d runtime=%d.%d.%d.", library,
       AV_VERSION_MAJOR(headerVersion), AV_VERSION_MINOR(headerVersion),
       AV_VERSION_MICRO(headerVersion), AV_VERSION_MAJOR(runtimeVersion),
       AV_VERSION_MINOR(runtimeVersion), AV_VERSION_MICRO(runtimeVersion));
  return false;
}

jint JNI_OnLoad(JavaVM *vm, void *) {
  JNIEnv *env;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }
  if (!hasExpectedVersion("libavcodec", avcodec_version(),
                          LIBAVCODEC_VERSION_INT) ||
      !hasExpectedVersion("libavutil", avutil_version(),
                          LIBAVUTIL_VERSION_INT) ||
      !hasExpectedVersion("libswresample", swresample_version(),
                          LIBSWRESAMPLE_VERSION_INT)) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_ffmpegGetVersion(JNIEnv *env,
                                                                   jclass) {
  unsigned version = avcodec_version();
  char versionString[32];
  snprintf(versionString, sizeof(versionString), "Lavc%d.%d.%d",
           AV_VERSION_MAJOR(version), AV_VERSION_MINOR(version),
           AV_VERSION_MICRO(version));
  return env->NewStringUTF(versionString);
}

extern "C" JNIEXPORT jint JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_ffmpegGetInputBufferPaddingSize(
    JNIEnv *, jclass) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_ffmpegHasDecoder(
    JNIEnv *env, jclass, jstring codec_name) {
  return getCodecByName(env, codec_name) != nullptr;
}
