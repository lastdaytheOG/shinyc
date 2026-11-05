// A picture as the photo model reads it (com.amar.vault.photos.PhotoPixels), straight from the
// bitmap's own memory: no copy of its pixels on the Java heap, and the loops that take most of the
// preparing run several times faster than they can in Kotlin.
//
// It gives the same bits as PhotoPixels.resized, float for float: the same filter tables are passed
// in, every value receives the same additions in the same order, and this file is compiled without
// fast math or fused multiply-add (see CMakeLists.txt). The lanes of a vector hold red, green and
// blue, not neighbouring terms of one sum, so vectorising here reorders nothing.
// PhotoPixelsDeviceTest compares the two bit by bit.

#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>

namespace {

typedef float Lanes __attribute__((vector_size(16)));
typedef uint32_t Lanes32 __attribute__((vector_size(16)));

inline int clip8(float v) {
    const int i = static_cast<int>(v + 0.5f);
    return i < 0 ? 0 : (i > 255 ? 255 : i);
}

struct FreeDeleter {
    void operator()(void* p) const { std::free(p); }
};

template <typename T>
std::unique_ptr<T[], FreeDeleter> aligned(size_t count) {
    void* p = nullptr;
    if (posix_memalign(&p, 16, count * sizeof(T)) != 0) return nullptr;
    return std::unique_ptr<T[], FreeDeleter>(static_cast<T*>(p));
}

// One axis's filter: output i takes count[i] weights from weights + offset[i], starting at source
// position start[i].
struct Taps {
    const jint* start;
    const jint* offset;
    const jint* count;
    const jfloat* weights;
};

// The horizontal pass: every source row becomes `size` RGB values, rounded to 8 bits. Each source
// pixel is widened to floats once, as a row is begun, rather than once for each of the output
// pixels whose filter covers it.
bool horizontal(const uint8_t* pixels, uint32_t stride, int w, int h, int size, const Taps& xs, uint8_t* mid) {
    auto row = aligned<Lanes>(static_cast<size_t>(w));
    if (!row) return false;
    for (int y = 0; y < h; ++y) {
        const uint8_t* source = pixels + static_cast<size_t>(y) * stride;
        Lanes* floats = row.get();
        for (int x = 0; x < w; ++x) {
            const uint8_t* p = source + static_cast<size_t>(x) * 4;
            floats[x] = Lanes{static_cast<float>(p[0]), static_cast<float>(p[1]), static_cast<float>(p[2]), 0.0f};
        }
        uint8_t* o = mid + static_cast<size_t>(y) * static_cast<size_t>(size) * 3;
        for (int x = 0; x < size; ++x) {
            const Lanes* from = floats + xs.start[x];
            const jfloat* wt = xs.weights + xs.offset[x];
            const int n = xs.count[x];
            Lanes sum = {0.0f, 0.0f, 0.0f, 0.0f};
            for (int k = 0; k < n; ++k) {
                const float weight = wt[k];
                sum += from[k] * Lanes{weight, weight, weight, weight};
            }
            o[0] = static_cast<uint8_t>(clip8(sum[0]));
            o[1] = static_cast<uint8_t>(clip8(sum[1]));
            o[2] = static_cast<uint8_t>(clip8(sum[2]));
            o += 3;
        }
    }
    return true;
}

// The vertical pass, a row of weights at a time so it walks memory in order. Each output value
// still receives its terms in the order the filter has them.
bool vertical(const uint8_t* mid, int size, const Taps& ys, jfloat* out) {
    const size_t plane = static_cast<size_t>(size) * static_cast<size_t>(size);
    auto sums = aligned<float>(static_cast<size_t>(size) * 3);
    if (!sums) return false;
    for (int y = 0; y < size; ++y) {
        float* acc = sums.get();
        std::memset(acc, 0, static_cast<size_t>(size) * 3 * sizeof(float));
        const jfloat* wt = ys.weights + ys.offset[y];
        const int n = ys.count[y];
        for (int k = 0; k < n; ++k) {
            const float weight = wt[k];
            const uint8_t* source = mid + static_cast<size_t>(ys.start[y] + k) * static_cast<size_t>(size) * 3;
            for (int i = 0; i < size * 3; ++i) acc[i] += weight * static_cast<float>(source[i]);
        }
        const size_t at = static_cast<size_t>(y) * static_cast<size_t>(size);
        for (int x = 0; x < size; ++x) {
            out[at + x] = static_cast<float>(clip8(acc[x * 3])) / 127.5f - 1.0f;
            out[plane + at + x] = static_cast<float>(clip8(acc[x * 3 + 1])) / 127.5f - 1.0f;
            out[2 * plane + at + x] = static_cast<float>(clip8(acc[x * 3 + 2])) / 127.5f - 1.0f;
        }
    }
    return true;
}

// Holds a Java array's elements for as long as it is in scope.
template <typename Array, typename Element>
class Held {
public:
    Held(JNIEnv* env, Array array) : env_(env), array_(array) {
        elements_ = static_cast<Element*>(env->GetPrimitiveArrayCritical(array, nullptr));
    }
    ~Held() {
        if (elements_ != nullptr) env_->ReleasePrimitiveArrayCritical(array_, elements_, keep_ ? 0 : JNI_ABORT);
    }
    Element* get() const { return elements_; }
    void keep() { keep_ = true; }

private:
    JNIEnv* env_;
    Array array_;
    Element* elements_;
    bool keep_ = false;
};

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_amar_vault_photos_PhotoResize_nativeResize(
        JNIEnv* env, jclass, jobject bitmap, jint size,
        jintArray xStart, jintArray xOffset, jintArray xCount, jfloatArray xWeights,
        jintArray yStart, jintArray yOffset, jintArray yCount, jfloatArray yWeights,
        jfloatArray out) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    const int w = static_cast<int>(info.width);
    const int h = static_cast<int>(info.height);
    auto mid = aligned<uint8_t>(static_cast<size_t>(h) * static_cast<size_t>(size) * 3);
    if (!mid) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    bool done = false;
    {
        // The arrays are held only for a moment's work on one picture, and the tables are small.
        Held<jintArray, jint> xs0(env, xStart), xs1(env, xOffset), xs2(env, xCount);
        Held<jfloatArray, jfloat> xs3(env, xWeights);
        if (xs0.get() && xs1.get() && xs2.get() && xs3.get()) {
            done = horizontal(static_cast<const uint8_t*>(pixels), info.stride, w, h, size,
                              Taps{xs0.get(), xs1.get(), xs2.get(), xs3.get()}, mid.get());
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if (!done) return JNI_FALSE;

    Held<jintArray, jint> ys0(env, yStart), ys1(env, yOffset), ys2(env, yCount);
    Held<jfloatArray, jfloat> ys3(env, yWeights), result(env, out);
    if (!(ys0.get() && ys1.get() && ys2.get() && ys3.get() && result.get())) return JNI_FALSE;
    if (!vertical(mid.get(), size, Taps{ys0.get(), ys1.get(), ys2.get(), ys3.get()}, result.get())) return JNI_FALSE;
    result.keep();
    return JNI_TRUE;
}
