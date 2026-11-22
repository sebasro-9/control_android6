#!/usr/bin/env bash
set -euo pipefail
FF_VER=n7.1.1
API=29
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
RAIZ="$(pwd)"
SRC=/tmp/ffmpeg-src
echo "NDK: $NDK"
[ -d "$SRC" ] || git clone --depth 1 -b "$FF_VER" https://github.com/FFmpeg/FFmpeg.git "$SRC"

COMUN=(
  --enable-pic --enable-static --disable-shared --disable-doc --disable-debug
  --disable-programs --enable-ffmpeg --disable-network --disable-autodetect
  --disable-everything
  --enable-protocol=file,pipe
  --enable-demuxer=avi,mov,matroska,mpegps,mpegts,mpegvideo,asf,flv,wav,mp3,mjpeg
  --enable-muxer=mp4,mov
  --enable-decoder=mjpeg,mjpegb,mpeg4,msmpeg4v1,msmpeg4v2,msmpeg4v3,h263,h263p,h264,hevc,mpeg1video,mpeg2video,wmv1,wmv2,wmv3,vc1,flv,dvvideo,rawvideo,cinepak,indeo3,indeo4,indeo5,msvideo1,huffyuv,ffvhuff,vp8,mp3,mp3float,mp2,mp2float,aac,ac3,eac3,wmav1,wmav2,vorbis,gsm_ms,adpcm_ima_wav,adpcm_ms,adpcm_ima_qt,adpcm_g726,adpcm_ima_dk3,adpcm_ima_dk4,adpcm_yamaha,adpcm_swf,pcm_s16le,pcm_s16be,pcm_u8,pcm_s24le,pcm_s32le,pcm_f32le,pcm_mulaw,pcm_alaw
  --enable-encoder=mpeg4,aac
  --enable-parser=mjpeg,mpeg4video,h263,h264,hevc,mpegvideo,mpegaudio,aac,ac3,vc1,vp8
  --enable-filter=scale,format,null,aresample,aformat,anull,setsar,setpts,asetpts,trim,atrim,hflip,vflip,transpose
  --extra-ldflags=-Wl,-z,max-page-size=16384
  --extra-ldexeflags=-pie
)

compilar() {
  local abi=$1 arch=$2 cpu=$3 cc=$4; shift 4
  cd "$SRC"
  make distclean >/dev/null 2>&1 || true
  ./configure --target-os=android --arch="$arch" --cpu="$cpu" --enable-cross-compile \
    --cc="$TC/$cc" --cxx="$TC/${cc}++" --ld="$TC/$cc" \
    --nm="$TC/llvm-nm" --ar="$TC/llvm-ar" --ranlib="$TC/llvm-ranlib" --strip="$TC/llvm-strip" \
    "${COMUN[@]}" "$@" || { tail -40 ffbuild/config.log; exit 1; }
  make -j"$(nproc)"
  mkdir -p "$RAIZ/ffmpeg-out/$abi"
  "$TC/llvm-strip" -o "$RAIZ/ffmpeg-out/$abi/libffmpeg.so" ffmpeg
  ls -la "$RAIZ/ffmpeg-out/$abi"
  cd "$RAIZ"
}

compilar arm64-v8a aarch64 armv8-a "aarch64-linux-android${API}-clang"
compilar armeabi-v7a arm armv7-a "armv7a-linux-androideabi${API}-clang" --disable-asm
