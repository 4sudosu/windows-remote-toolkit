This folder is bundled into the APK as the embedded Node.js server.

Before building the APK, install the JS dependencies so they get packaged:

    cd "E:\AI\Runtime Broker\AndroidApp\app\src\main\assets\nodejs-project"
    npm install

The app runs this server on-device through the nodejs-mobile libnode.so library
(via the JNI bridge in app/src/main/cpp/native-lib.cpp). The NodeServerService
foreground service copies this folder into the app's files dir, writes
server-config.json (host/port/admin password chosen in Settings), and starts
Node on a background thread pointing at server.js in that copied folder.

For the embedded server to actually run, also drop the nodejs-mobile libnode.so
binaries into app/libnode/bin/<abi>/ (see app/libnode/README.txt).

Only run npm install on a machine with Node.js; it creates node_modules/ here,
which Gradle packages into the APK.