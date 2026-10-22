// The scan behind photo search (com.amar.vault.photos.PhotoScan): one score per stored picture,
// out[i] = scales[i] * dot(query, vectors[i]), where each picture's vector is `dim` signed bytes.
// Compiled at -O3 with fast math so the inner loop vectorises (see CMakeLists.txt).

#include <jni.h>
#include <cstddef>
#include <cstdint>

extern "C" {

JNIEXPORT jint JNICALL
Java_com_amar_vault_photos_PhotoScan_nativeCheck(JNIEnv*, jclass) {
    return 1;
}

JNIEXPORT void JNICALL
Java_com_amar_vault_photos_PhotoScan_nativeScores(
        JNIEnv* env, jclass,
        jfloatArray jQuery, jbyteArray jVectors, jfloatArray jScales,
        jint dim, jint count, jfloatArray jOut) {
    auto* query = static_cast<float*>(env->GetPrimitiveArrayCritical(jQuery, nullptr));
    auto* vectors = static_cast<int8_t*>(env->GetPrimitiveArrayCritical(jVectors, nullptr));
    auto* scales = static_cast<float*>(env->GetPrimitiveArrayCritical(jScales, nullptr));
    auto* out = static_cast<float*>(env->GetPrimitiveArrayCritical(jOut, nullptr));
    if (query != nullptr && vectors != nullptr && scales != nullptr && out != nullptr) {
        for (jint i = 0; i < count; ++i) {
            const int8_t* row = vectors + static_cast<size_t>(i) * static_cast<size_t>(dim);
            float acc = 0.0f;
            for (jint j = 0; j < dim; ++j) acc += query[j] * static_cast<float>(row[j]);
            out[i] = acc * scales[i];
        }
    }
    if (out != nullptr) env->ReleasePrimitiveArrayCritical(jOut, out, 0);
    if (scales != nullptr) env->ReleasePrimitiveArrayCritical(jScales, scales, JNI_ABORT);
    if (vectors != nullptr) env->ReleasePrimitiveArrayCritical(jVectors, vectors, JNI_ABORT);
    if (query != nullptr) env->ReleasePrimitiveArrayCritical(jQuery, query, JNI_ABORT);
}

}  // extern "C"
