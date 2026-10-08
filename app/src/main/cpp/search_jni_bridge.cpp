#include <jni.h>
#include <string>
#include <vector>
#include "SearchEngine.h"
#include "WordCutter.h"

// Each NativeSearchEngine object on the Kotlin side owns one engine and holds its address.
// The app has one for its whole life; a test can have one of its own.
//
// Text and queries arrive as they are and are cut into words here (WordCutter), by the table
// the Kotlin side installs when the library is loaded.

namespace {

WordCutter cutter;

SearchEngine* engineAt(jlong handle) { return reinterpret_cast<SearchEngine*>(handle); }

/** The words of a Java string and, when [more] is given, of that one after it. */
std::u16string wordsOf(JNIEnv* env, jstring string, jstring more = nullptr) {
    const jsize length = env->GetStringLength(string);
    const jsize moreLength = more != nullptr ? env->GetStringLength(more) : 0;
    // A space between the two, so that no word runs from one into the other.
    std::u16string text(static_cast<size_t>(length) + 1 + static_cast<size_t>(moreLength), u' ');
    if (length > 0) env->GetStringRegion(string, 0, length, reinterpret_cast<jchar*>(&text[0]));
    if (moreLength > 0) {
        env->GetStringRegion(more, 0, moreLength, reinterpret_cast<jchar*>(&text[length + 1]));
    }
    cutter.cut(text);
    return text;
}

/** An item id. It only has to come back out as it went in, which this form does. */
std::string idOf(JNIEnv* env, jstring string) {
    const char* chars = env->GetStringUTFChars(string, nullptr);
    std::string id(chars);
    env->ReleaseStringUTFChars(string, chars);
    return id;
}

}  // namespace

extern "C" {

/** Once, before anything is added: KeywordWords.table. */
JNIEXPORT void JNICALL
Java_com_amar_vault_NativeSearchEngine_nativeInstallWords(JNIEnv* env, jclass, jcharArray table) {
    if (env->GetArrayLength(table) != static_cast<jsize>(WordCutter::TABLE_SIZE)) return;
    jchar* entries = env->GetCharArrayElements(table, nullptr);
    cutter.install(reinterpret_cast<const char16_t*>(entries));
    env->ReleaseCharArrayElements(table, entries, JNI_ABORT);
}

/** The words of [text] as the engine is given them, a space between them: for tests. */
JNIEXPORT jstring JNICALL
Java_com_amar_vault_NativeSearchEngine_nativeWords(JNIEnv* env, jclass, jstring text) {
    const std::u16string words = wordsOf(env, text);
    return env->NewString(reinterpret_cast<const jchar*>(words.data()), static_cast<jsize>(words.size()));
}

JNIEXPORT jlong JNICALL
Java_com_amar_vault_NativeSearchEngine_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new SearchEngine());
}

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeSearchEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete engineAt(handle);
}

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeSearchEngine_nativeAdd(JNIEnv* env, jobject, jlong handle,
                                                 jstring docId, jstring text, jstring more) {
    engineAt(handle)->addDocument(idOf(env, docId), wordsOf(env, text, more));
}

JNIEXPORT jboolean JNICALL
Java_com_amar_vault_NativeSearchEngine_nativeRemove(JNIEnv* env, jobject, jlong handle, jstring docId) {
    return engineAt(handle)->removeDocument(idOf(env, docId)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_amar_vault_NativeSearchEngine_nativeSearch(JNIEnv* env, jobject, jlong handle,
                                                    jstring query, jint limit) {
    const std::vector<std::string> ids = engineAt(handle)->search(
            wordsOf(env, query), limit > 0 ? static_cast<size_t>(limit) : 0);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(ids.size()), stringClass, nullptr);
    for (size_t i = 0; i < ids.size(); i++) {
        jstring id = env->NewStringUTF(ids[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), id);
        env->DeleteLocalRef(id);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeSearchEngine_nativeClear(JNIEnv*, jobject, jlong handle) {
    engineAt(handle)->clear();
}

/** items, words, entries, removed items not yet cleaned out. */
JNIEXPORT jlongArray JNICALL
Java_com_amar_vault_NativeSearchEngine_nativeStats(JNIEnv* env, jobject, jlong handle) {
    const SearchEngine::Stats stats = engineAt(handle)->stats();
    const jlong values[4] = {
            static_cast<jlong>(stats.items), static_cast<jlong>(stats.words),
            static_cast<jlong>(stats.entries), static_cast<jlong>(stats.removedKept),
    };
    jlongArray result = env->NewLongArray(4);
    env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}

}  // extern "C"
