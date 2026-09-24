Embedded Node.js engine (nodejs-mobile libnode)
================================================

The embedded server ("Host on this device") runs Node.js through the official
nodejs-mobile Android library. The shared libraries are NOT committed to the
repo - download them once and drop them in place:

1. Download the latest "nodejs-mobile" core library release zip from:
   https://github.com/nodejs-mobile/nodejs-mobile/releases

2. From the zip, copy the `bin/` contents so you end up with:
     libnode/bin/armeabi-v7a/libnode.so
     libnode/bin/arm64-v8a/libnode.so
     libnode/bin/x86_64/libnode.so

3. Copy the `include/` folder from the zip so you end up with:
     libnode/include/node/node.h   (and the other headers)

That's it. CMake (app/CMakeLists.txt) auto-detects libnode.so per ABI: if the
files are present the real engine is linked; if not, the app still builds with a
stub so the rest of the app keeps working in "Connect to a server" mode.

Requirements to build with the engine: Android Studio with NDK r24+ and CMake.
Before building the APK also run `npm install` inside
app/src/main/assets/nodejs-project/ so express/ws are bundled.