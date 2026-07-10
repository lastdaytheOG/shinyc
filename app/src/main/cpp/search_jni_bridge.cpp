#include <jni.h>
#include <string>
#include <vector>
#include "SearchEngine.h"

// Global engine pointer (per spec — single instance, managed by Kotlin lifecycle).
SearchEngine* engine = nullptr;

extern "C" {

// =============================================================================
// Lifecycle
// =============================================================================

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeSearchEngine_initEngine(JNIEnv *env, jobject thiz) {
    if (engine == nullptr) {
        engine = new SearchEngine();
    }
}

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeSearchEngine_destroyEngine(JNIEnv *env, jobject thiz) {
    if (engine != nullptr) {
        delete engine;
        engine = nullptr;
    }
}

// =============================================================================
// Data Feeding
// =============================================================================

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeSearchEngine_addDocument(JNIEnv *env, jobject thiz,
                                                          jstring docId, jstring text) {
    if (engine == nullptr) return;

    const char *c_docId = env->GetStringUTFChars(docId, nullptr);
    const char *c_text  = env->GetStringUTFChars(text, nullptr);

    engine->addDocument(std::string(c_docId), std::string(c_text));

    env->ReleaseStringUTFChars(docId, c_docId);
    env->ReleaseStringUTFChars(text, c_text);
}

// =============================================================================
// Querying
// =============================================================================

JNIEXPORT jobjectArray JNICALL
Java_com_amar_vault_NativeSearchEngine_search(JNIEnv *env, jobject thiz, jstring query) {
    jclass stringClass = env->FindClass("java/lang/String");

    if (engine == nullptr) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    const char *c_query = env->GetStringUTFChars(query, nullptr);
    std::vector<std::string> results = engine->search(std::string(c_query));
    env->ReleaseStringUTFChars(query, c_query);

    // Convert C++ vector<string> → Java String[]
    jobjectArray javaResults = env->NewObjectArray(
            static_cast<jsize>(results.size()), stringClass, nullptr);

    for (size_t i = 0; i < results.size(); i++) {
        jstring javaString = env->NewStringUTF(results[i].c_str());
        env->SetObjectArrayElement(javaResults, static_cast<jsize>(i), javaString);
        env->DeleteLocalRef(javaString);
    }

    return javaResults;
}

// =============================================================================
// Clear
// =============================================================================

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeSearchEngine_clear(JNIEnv *env, jobject thiz) {
    if (engine != nullptr) {
        engine->clear();
    }
}

} // extern "C"
