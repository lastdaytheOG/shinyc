#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "VectorEngine.h"

#define LOG_TAG "VectorEngineJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global engine instance
static VectorEngine* engine = nullptr;

// Stored path to storage directory (set during init)
static std::string storagePath;
static const char* INDEX_FILENAME = "hnsw_index.bin";

static std::string getIndexPath() {
    return storagePath + "/" + INDEX_FILENAME;
}

extern "C" {

// =============================================================================
// initEngine — Initialize the HNSW index and attempt to load from disk
// =============================================================================

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeVectorEngine_initEngine(
        JNIEnv *env, jobject thiz,
        jint dim, jint maxElements, jstring jStoragePath)
{
if (engine != nullptr) {
delete engine;
engine = nullptr;
}

const char* cPath = env->GetStringUTFChars(jStoragePath, nullptr);
storagePath = std::string(cPath);
env->ReleaseStringUTFChars(jStoragePath, cPath);

try {
engine = new VectorEngine();
engine->init(dim, maxElements);

std::string indexPath = getIndexPath();
try {
engine->loadIndex(indexPath);
LOGI("Loaded HNSW index from disk: %zu vectors", engine->getCount());
} catch (...) {
LOGI("No existing index found. Starting with empty index.");
}

} catch (const std::exception& e) {
LOGE("Failed to initialize VectorEngine: %s", e.what());
if (engine != nullptr) {
delete engine;
engine = nullptr;
}
jclass rtException = env->FindClass("java/lang/RuntimeException");
env->ThrowNew(rtException, e.what());
}
}

// =============================================================================
// addVector — Pass 1024-dim FloatArray from Kotlin to C++ without copy overhead
// =============================================================================

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeVectorEngine_addVector(
        JNIEnv *env, jobject thiz,
        jint numericId, jfloatArray jVector)
{
if (engine == nullptr) {
jclass rtException = env->FindClass("java/lang/RuntimeException");
env->ThrowNew(rtException, "VectorEngine not initialized");
return;
}

jint len      = env->GetArrayLength(jVector);
jfloat* elems = env->GetFloatArrayElements(jVector, nullptr);
if (elems == nullptr) {
jclass rtException = env->FindClass("java/lang/RuntimeException");
env->ThrowNew(rtException, "Failed to access FloatArray elements");
return;
}

try {
std::vector<float> vec(elems, elems + len);
engine->addVector(numericId, vec);
} catch (const std::exception& e) {
env->ReleaseFloatArrayElements(jVector, elems, JNI_ABORT);
jclass rtException = env->FindClass("java/lang/RuntimeException");
env->ThrowNew(rtException, e.what());
return;
}

// JNI_ABORT — release without copying back, we only read
env->ReleaseFloatArrayElements(jVector, elems, JNI_ABORT);
}

// =============================================================================
// search — Return IntArray of numeric IDs sorted by similarity
// =============================================================================

JNIEXPORT jintArray JNICALL
        Java_com_amar_vault_NativeVectorEngine_search(
        JNIEnv *env, jobject thiz,
jfloatArray jQueryVector, jint k)
{
if (engine == nullptr) return env->NewIntArray(0);

jint    queryLen  = env->GetArrayLength(jQueryVector);
jfloat* queryElems = env->GetFloatArrayElements(jQueryVector, nullptr);
if (queryElems == nullptr) return env->NewIntArray(0);

std::vector<std::pair<int, float>> results;
try {
std::vector<float> queryVec(queryElems, queryElems + queryLen);
results = engine->searchKnn(queryVec, k);
} catch (const std::exception& e) {
env->ReleaseFloatArrayElements(jQueryVector, queryElems, JNI_ABORT);
LOGE("Search failed: %s", e.what());
return env->NewIntArray(0);
}
env->ReleaseFloatArrayElements(jQueryVector, queryElems, JNI_ABORT);

jintArray jResults = env->NewIntArray(static_cast<jsize>(results.size()));
if (results.empty()) return jResults;

std::vector<jint> ids;
ids.reserve(results.size());
for (const auto& [id, dist] : results) {
ids.push_back(static_cast<jint>(id));
}
env->SetIntArrayRegion(jResults, 0, static_cast<jsize>(ids.size()), ids.data());
return jResults;
}

// =============================================================================
// searchWithScores — Return FloatArray of [id0, score0, id1, score1, ...]
// =============================================================================

JNIEXPORT jfloatArray JNICALL
        Java_com_amar_vault_NativeVectorEngine_searchWithScores(
        JNIEnv *env, jobject thiz,
jfloatArray jQueryVector, jint k)
{
if (engine == nullptr) return env->NewFloatArray(0);

jint    queryLen   = env->GetArrayLength(jQueryVector);
jfloat* queryElems = env->GetFloatArrayElements(jQueryVector, nullptr);
if (queryElems == nullptr) return env->NewFloatArray(0);

std::vector<std::pair<int, float>> results;
try {
std::vector<float> queryVec(queryElems, queryElems + queryLen);
results = engine->searchKnn(queryVec, k);
} catch (const std::exception& e) {
env->ReleaseFloatArrayElements(jQueryVector, queryElems, JNI_ABORT);
LOGE("SearchWithScores failed: %s", e.what());
return env->NewFloatArray(0);
}
env->ReleaseFloatArrayElements(jQueryVector, queryElems, JNI_ABORT);

// Interleaved format: [id0, score0, id1, score1, ...]
jfloatArray jResults = env->NewFloatArray(
        static_cast<jsize>(results.size() * 2)
);
if (results.empty()) return jResults;

std::vector<jfloat> flat;
flat.reserve(results.size() * 2);
for (const auto& [id, dist] : results) {
flat.push_back(static_cast<jfloat>(id));
// Convert InnerProduct distance to similarity score
// For normalized vectors: similarity = 1 - distance
flat.push_back(1.0f - dist);
}
env->SetFloatArrayRegion(
        jResults, 0, static_cast<jsize>(flat.size()), flat.data()
);
return jResults;
}

// =============================================================================
// saveToDisk — Flush HNSW graph to internal storage
// =============================================================================

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeVectorEngine_saveToDisk(
        JNIEnv *env, jobject thiz)
{
if (engine == nullptr) return;

try {
std::string indexPath = getIndexPath();
engine->saveIndex(indexPath);
LOGI("Saved HNSW index: %zu vectors", engine->getCount());
} catch (const std::exception& e) {
LOGE("Failed to save index: %s", e.what());
jclass rtException = env->FindClass("java/lang/RuntimeException");
env->ThrowNew(rtException, e.what());
}
}

// =============================================================================
// destroyEngine — Free all native memory
// =============================================================================

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeVectorEngine_destroyEngine(
        JNIEnv *env, jobject thiz)
{
if (engine != nullptr) {
delete engine;
engine = nullptr;
LOGI("VectorEngine destroyed");
}
}

// =============================================================================
// getCount — Current number of indexed vectors
// =============================================================================

JNIEXPORT jint JNICALL
        Java_com_amar_vault_NativeVectorEngine_getCount(
        JNIEnv *env, jobject thiz)
{
if (engine == nullptr) return 0;
return static_cast<jint>(engine->getCount());
}

} // extern "C"