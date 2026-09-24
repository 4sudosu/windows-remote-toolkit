#include <jni.h>
#include <string>
#include <cstdlib>

#ifdef NODE_EMBEDDED
#include "node.h"
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_runtimebroker_app_NodeServerService_startNodeWithArguments(
        JNIEnv *env,
        jobject /* thiz */,
        jobjectArray arguments) {
#ifdef NODE_EMBEDDED
    jsize argument_count = env->GetArrayLength(arguments);

    int c_arguments_size = 0;
    for (int i = 0; i < argument_count; i++) {
        c_arguments_size += strlen(env->GetStringUTFChars((jstring)env->GetObjectArrayElement(arguments, i), 0));
        c_arguments_size++; // for '\0'
    }

    char *args_buffer = (char *)calloc(c_arguments_size, sizeof(char));
    char *argv[argument_count];
    char *current_args_position = args_buffer;

    for (int i = 0; i < argument_count; i++) {
        const char *current_argument = env->GetStringUTFChars((jstring)env->GetObjectArrayElement(arguments, i), 0);
        strncpy(current_args_position, current_argument, strlen(current_argument));
        argv[i] = current_args_position;
        current_args_position += strlen(current_args_position) + 1;
    }

    int node_result = node::Start(argument_count, argv);
    free(args_buffer);
    return jint(node_result);
#else
    return 0;
#endif
}