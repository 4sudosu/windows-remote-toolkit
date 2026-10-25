Embedded Node.js engine (nodejs-mobile libnode)
================================================

The embedded server ("Run Server on all Network Interfaces") runs Node.js
through the official nodejs-mobile Android library. The shared libraries are
NOT committed to the repo - download once and drop in place:

1. Download the latest nodejs-mobile Android release zip from:
   https://github.com/nodejs-mobile/nodejs-mobile/releases
   (e.g. nodejs-mobile-v18.20.4-android.zip)

2. From the zip, copy into this folder:
     libnode/bin/armeabi-v7a/libnode.so
     libnode/bin/arm64-v8a/libnode.so
     libnode/bin/x86_64/libnode.so

3. Copy the zip's `include/` folder here:
     libnode/include/node/node.h   (and the other headers)

CMake (app/CMakeLists.txt) auto-detects libnode.so per ABI: without the
binaries the app still builds, but the embedded server stays dormant.
libc++_shared.so lives in app/src/main/jniLibs/<abi>/ and is packaged via CMake.
