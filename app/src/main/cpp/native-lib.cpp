#include <jni.h>
#include <string>
#include <cctype>

extern "C" JNIEXPORT jintArray JNICALL
Java_com_msi_nanogui_NativeEditorHelper_analyzeText(JNIEnv *env, jobject thiz, jstring text_obj) {
    if (!text_obj) {
        jintArray result = env->NewIntArray(3);
        jint fill[3] = {0, 0, 0};
        env->SetIntArrayRegion(result, 0, 3, fill);
        return result;
    }

    const char *text = env->GetStringUTFChars(text_obj, nullptr);
    if (!text) {
        jintArray result = env->NewIntArray(3);
        jint fill[3] = {0, 0, 0};
        env->SetIntArrayRegion(result, 0, 3, fill);
        return result;
    }

    std::string cpp_str(text);
    env->ReleaseStringUTFChars(text_obj, text);

    int lines = 1;
    int words = 0;
    int chars = cpp_str.length();

    if (chars == 0) {
        lines = 0;
    } else {
        bool in_word = false;
        for (char c : cpp_str) {
            if (c == '\n') {
                lines++;
            }
            if (std::isspace(static_cast<unsigned char>(c))) {
                in_word = false;
            } else {
                if (!in_word) {
                    words++;
                    in_word = true;
                }
            }
        }
    }

    jintArray result = env->NewIntArray(3);
    jint fill[3] = {lines, words, chars};
    env->SetIntArrayRegion(result, 0, 3, fill);
    return result;
}
