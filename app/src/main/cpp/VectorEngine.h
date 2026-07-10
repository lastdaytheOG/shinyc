#pragma once

#include <string>
#include <vector>
#include <utility>
#include <shared_mutex>
#include <memory>
#include "hnswlib.h"

/**
 * VectorEngine — Thread-safe wrapper around hnswlib's HierarchicalNSW.
 *
 * Designed for 768-dimensional BGE-M3 embeddings with Inner Product distance
 * (cosine similarity on normalized vectors).
 *
 * Thread safety model:
 *   - addVector(): acquires shared (read) lock — hnswlib's addPoint is internally thread-safe
 *   - searchKnn(): acquires shared (read) lock — concurrent reads are safe
 *   - saveIndex(): acquires exclusive (write) lock — blocks all other operations
 *   - loadIndex(): acquires exclusive (write) lock — blocks all other operations
 *
 * Parameters (hardcoded per spec):
 *   M = 16                (max connections per layer — balanced RAM/recall)
 *   ef_construction = 200 (build-time search depth — high recall)
 *   ef_search = 64        (query-time search depth — tunable at runtime)
 */
class VectorEngine {
public:
    VectorEngine();
    ~VectorEngine();

    /**
     * Initialize the HNSW index.
     * @param dim         Vector dimensionality (1024 for BGE-M3)
     * @param maxElements Maximum number of vectors the index can hold
     */
    void init(int dim, int maxElements);

    /**
     * Add a vector to the index.
     * @param id     Numeric ID (mapped to VaultItem UUID on the Kotlin side)
     * @param vector 1024-dimensional float vector (must be normalized for cosine sim)
     */
    void addVector(int id, const std::vector<float>& vector);

    /**
     * Search for k nearest neighbors.
     * @param query  1024-dimensional query vector (normalized)
     * @param k      Number of results to return
     * @return       Vector of (id, distance) pairs, sorted by distance ascending
     *               Lower distance = higher similarity for Inner Product
     */
    std::vector<std::pair<int, float>> searchKnn(const std::vector<float>& query, int k);

    /**
     * Save the index to disk. Acquires exclusive lock.
     * @param path  Absolute path to the output file
     */
    void saveIndex(const std::string& path);

    /**
     * Load the index from disk. Acquires exclusive lock.
     * @param path  Absolute path to the input file
     */
    void loadIndex(const std::string& path);

    /**
     * Get the current number of vectors in the index.
     */
    size_t getCount() const;

    /**
     * Set search-time ef parameter (higher = more accurate but slower).
     * Default is 64. Set to 128+ for maximum recall.
     */
    void setSearchEf(int ef);

private:
    static constexpr int M = 16;
    static constexpr int EF_CONSTRUCTION = 200;
    static constexpr int DEFAULT_EF_SEARCH = 64;

    std::unique_ptr<hnswlib::InnerProductSpace> space_;
    std::unique_ptr<hnswlib::HierarchicalNSW<float>> index_;
    int dim_;
    bool initialized_;

    mutable std::shared_mutex mutex_;
};
