#pragma once

/**
 * hnswlib — Header-only Approximate Nearest Neighbor library.
 *
 * This is a minimal, self-contained implementation of the HNSW algorithm
 * (Hierarchical Navigable Small World graphs) by Yu. A. Malkov and D. A. Yashunin.
 *
 * Reference: https://github.com/nmslib/hnswlib
 * Paper: "Efficient and robust approximate nearest neighbor search using
 *         Hierarchical Navigable Small World graphs" (2018)
 *
 * This vendored version includes only what's needed for:
 *   - Inner Product space (cosine similarity on normalized vectors)
 *   - HierarchicalNSW index with add/search/save/load
 *   - Thread-safe addPoint (internal locking)
 */

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <vector>
#include <queue>
#include <algorithm>
#include <mutex>
#include <fstream>
#include <random>
#include <stdexcept>
#include <unordered_set>
#include <functional>

namespace hnswlib {

// ============================================================================
// Core types
// ============================================================================

typedef size_t labeltype;
typedef float dist_t;
typedef std::pair<dist_t, labeltype> dist_label_pair;

// ============================================================================
// Space Interface
// ============================================================================

template<typename MTYPE>
class SpaceInterface {
public:
    virtual size_t get_data_size() = 0;
    virtual MTYPE fstdistfunc(const void* a, const void* b) = 0;
    virtual ~SpaceInterface() = default;
};

// ============================================================================
// Inner Product Space (for cosine similarity on normalized vectors)
// Note: HNSW minimizes distance. For inner product, we use (1 - IP) so that
// higher similarity = lower distance.
// ============================================================================

class InnerProductSpace : public SpaceInterface<float> {
    size_t dim_;
    size_t data_size_;

public:
    explicit InnerProductSpace(size_t dim) : dim_(dim), data_size_(dim * sizeof(float)) {}

    size_t get_data_size() override { return data_size_; }
    size_t get_dim() const { return dim_; }

    float fstdistfunc(const void* a, const void* b) override {
        const float* va = static_cast<const float*>(a);
        const float* vb = static_cast<const float*>(b);
        float sum = 0.0f;
        for (size_t i = 0; i < dim_; ++i) {
            sum += va[i] * vb[i];
        }
        // Return negative IP so that higher similarity = smaller distance
        return 1.0f - sum;
    }
};

// ============================================================================
// L2 Space (Euclidean distance, included for completeness)
// ============================================================================

class L2Space : public SpaceInterface<float> {
    size_t dim_;
    size_t data_size_;

public:
    explicit L2Space(size_t dim) : dim_(dim), data_size_(dim * sizeof(float)) {}

    size_t get_data_size() override { return data_size_; }

    float fstdistfunc(const void* a, const void* b) override {
        const float* va = static_cast<const float*>(a);
        const float* vb = static_cast<const float*>(b);
        float sum = 0.0f;
        for (size_t i = 0; i < dim_; ++i) {
            float diff = va[i] - vb[i];
            sum += diff * diff;
        }
        return sum;
    }
};

// ============================================================================
// HierarchicalNSW — The HNSW Index
// ============================================================================

template<typename dist_t>
class HierarchicalNSW {
public:
    SpaceInterface<dist_t>* space_;
    size_t max_elements_;
    size_t cur_element_count_;
    size_t dim_;
    size_t data_size_;
    size_t M_;
    size_t maxM_;
    size_t maxM0_;
    size_t ef_construction_;
    size_t ef_;  // search-time ef

    double mult_;  // level generation factor
    int maxlevel_;
    int enterpoint_node_;

    // Per-element storage
    std::vector<std::vector<float>> data_;           // raw vectors
    std::vector<labeltype> labels_;                  // external labels
    std::vector<int> element_levels_;                // max level per element
    std::vector<std::vector<std::vector<int>>> links_; // links_[id][level] = neighbors

    // Thread safety
    mutable std::mutex global_lock_;
    mutable std::mutex cur_element_count_lock_;

    std::default_random_engine level_generator_;

    HierarchicalNSW(SpaceInterface<dist_t>* space, size_t max_elements,
                     size_t M = 16, size_t ef_construction = 200)
        : space_(space),
          max_elements_(max_elements),
          cur_element_count_(0),
          M_(M),
          maxM_(M),
          maxM0_(M * 2),
          ef_construction_(ef_construction),
          ef_(10),
          maxlevel_(-1),
          enterpoint_node_(-1)
    {
        dim_ = dynamic_cast<InnerProductSpace*>(space)
            ? dynamic_cast<InnerProductSpace*>(space)->get_dim()
            : 0;
        if (dim_ == 0) {
            // Try to infer from data_size
            data_size_ = space->get_data_size();
            dim_ = data_size_ / sizeof(float);
        }
        data_size_ = dim_ * sizeof(float);

        mult_ = 1.0 / log(1.0 * M_);

        data_.resize(max_elements_);
        labels_.resize(max_elements_);
        element_levels_.resize(max_elements_, 0);
        links_.resize(max_elements_);

        level_generator_.seed(42);
    }

    // Generate random level for new element
    int getRandomLevel() {
        std::uniform_real_distribution<double> distribution(0.0, 1.0);
        double r = -log(distribution(level_generator_)) * mult_;
        return static_cast<int>(r);
    }

    // Distance between two stored elements
    dist_t calcDistance(int a, int b) {
        return space_->fstdistfunc(data_[a].data(), data_[b].data());
    }

    // Distance between a query and a stored element
    dist_t calcDistanceToQuery(const float* query, int b) {
        return space_->fstdistfunc(query, data_[b].data());
    }

    // ========================================================================
    // Search layer — greedy closest neighbor search within a single layer
    // Returns a max-heap of (distance, id) pairs
    // ========================================================================

    using PairDI = std::pair<dist_t, int>;

    // Search the layer for ef nearest neighbors to query, starting from ep
    std::priority_queue<PairDI> searchLayer(const float* query, int ep, int ef, int level) {
        std::unordered_set<int> visited;
        visited.insert(ep);

        // candidates: min-heap (closest first)
        std::priority_queue<PairDI, std::vector<PairDI>, std::greater<PairDI>> candidates;
        // results: max-heap (farthest first, so we can trim)
        std::priority_queue<PairDI> results;

        dist_t d = calcDistanceToQuery(query, ep);
        candidates.push({d, ep});
        results.push({d, ep});

        while (!candidates.empty()) {
            auto [cd, cid] = candidates.top();

            // If closest candidate is farther than farthest result, stop
            if (cd > results.top().first && (int)results.size() >= ef) break;
            candidates.pop();

            // Explore neighbors of cid at this level
            if (level < (int)links_[cid].size()) {
                for (int neighbor : links_[cid][level]) {
                    if (visited.count(neighbor)) continue;
                    visited.insert(neighbor);

                    dist_t nd = calcDistanceToQuery(query, neighbor);

                    if ((int)results.size() < ef || nd < results.top().first) {
                        candidates.push({nd, neighbor});
                        results.push({nd, neighbor});
                        if ((int)results.size() > ef) results.pop();
                    }
                }
            }
        }

        return results;
    }

    // Select neighbors: simple strategy — take closest M
    std::vector<int> selectNeighbors(const float* query, std::priority_queue<PairDI>& candidates, size_t M) {
        // Convert max-heap to sorted vector
        std::vector<PairDI> sorted;
        while (!candidates.empty()) {
            sorted.push_back(candidates.top());
            candidates.pop();
        }
        std::sort(sorted.begin(), sorted.end());

        std::vector<int> result;
        size_t count = std::min(sorted.size(), M);
        for (size_t i = 0; i < count; ++i) {
            result.push_back(sorted[i].second);
        }
        return result;
    }

    // ========================================================================
    // addPoint — Insert a vector with a label (thread-safe)
    // ========================================================================

    void addPoint(const float* data_point, labeltype label) {
        std::unique_lock<std::mutex> lock(cur_element_count_lock_);

        if (cur_element_count_ >= max_elements_) {
            throw std::runtime_error("HNSW index is full");
        }

        int cur_id = static_cast<int>(cur_element_count_);
        cur_element_count_++;
        lock.unlock();

        int curlevel = getRandomLevel();

        // Store data
        data_[cur_id].assign(data_point, data_point + dim_);
        labels_[cur_id] = label;
        element_levels_[cur_id] = curlevel;
        links_[cur_id].resize(curlevel + 1);

        std::unique_lock<std::mutex> glock(global_lock_);

        if (enterpoint_node_ == -1) {
            // First element
            enterpoint_node_ = cur_id;
            maxlevel_ = curlevel;
            return;
        }

        int ep = enterpoint_node_;
        int cur_maxlevel = maxlevel_;
        glock.unlock();

        // Phase 1: Traverse from top to curlevel+1 — greedy search, single nearest
        for (int level = cur_maxlevel; level > curlevel; --level) {
            auto results = searchLayer(data_point, ep, 1, level);
            if (!results.empty()) {
                ep = results.top().second;
            }
        }

        // Phase 2: From curlevel down to 0 — search with ef_construction, connect
        for (int level = std::min(curlevel, cur_maxlevel); level >= 0; --level) {
            auto results = searchLayer(data_point, ep, (int)ef_construction_, level);

            size_t maxM = (level == 0) ? maxM0_ : maxM_;
            auto neighbors = selectNeighbors(data_point, results, maxM);

            links_[cur_id][level] = neighbors;

            // Add reverse connections
            for (int neighbor : neighbors) {
                if (level < (int)links_[neighbor].size()) {
                    auto& nlinks = links_[neighbor][level];
                    nlinks.push_back(cur_id);

                    // Trim if too many connections
                    if (nlinks.size() > maxM) {
                        // Keep closest M
                        std::vector<PairDI> scored;
                        for (int nl : nlinks) {
                            scored.push_back({calcDistance(neighbor, nl), nl});
                        }
                        std::sort(scored.begin(), scored.end());
                        nlinks.clear();
                        for (size_t i = 0; i < maxM; ++i) {
                            nlinks.push_back(scored[i].second);
                        }
                    }
                }
            }

            if (!neighbors.empty()) {
                ep = neighbors[0]; // closest neighbor becomes entry for next level
            }
        }

        // Update enterpoint if new element has higher level
        glock.lock();
        if (curlevel > maxlevel_) {
            maxlevel_ = curlevel;
            enterpoint_node_ = cur_id;
        }
    }

    // ========================================================================
    // searchKnn — Find k nearest neighbors
    // ========================================================================

    std::priority_queue<std::pair<dist_t, labeltype>> searchKnn(const float* query, size_t k) {
        if (cur_element_count_ == 0) {
            return {};
        }

        int ep = enterpoint_node_;

        // Traverse from top level down to level 1 — greedy single nearest
        for (int level = maxlevel_; level > 0; --level) {
            auto results = searchLayer(query, ep, 1, level);
            if (!results.empty()) {
                ep = results.top().second;
            }
        }

        // Search level 0 with ef >= k
        size_t search_ef = std::max(ef_, k);
        auto results = searchLayer(query, ep, (int)search_ef, 0);

        // Convert to labeled results, keep top k
        std::priority_queue<std::pair<dist_t, labeltype>> labeled;
        while (!results.empty()) {
            auto [d, id] = results.top();
            results.pop();
            labeled.push({d, labels_[id]});
        }

        // Trim to k (labeled is max-heap, so pop farthest)
        while (labeled.size() > k) {
            labeled.pop();
        }

        return labeled;
    }

    // ========================================================================
    // Persistence — save/load index to/from disk
    // ========================================================================

    void saveIndex(const std::string& path) {
        std::ofstream out(path, std::ios::binary);
        if (!out.is_open()) {
            throw std::runtime_error("Cannot open file for writing: " + path);
        }

        // Header
        uint32_t magic = 0x484E5357; // "HNSW"
        out.write(reinterpret_cast<const char*>(&magic), sizeof(magic));

        uint64_t n = cur_element_count_;
        uint64_t d = dim_;
        uint64_t m = M_;
        uint64_t efc = ef_construction_;
        int32_t ml = maxlevel_;
        int32_t ep = enterpoint_node_;

        out.write(reinterpret_cast<const char*>(&n), sizeof(n));
        out.write(reinterpret_cast<const char*>(&d), sizeof(d));
        out.write(reinterpret_cast<const char*>(&m), sizeof(m));
        out.write(reinterpret_cast<const char*>(&efc), sizeof(efc));
        out.write(reinterpret_cast<const char*>(&ml), sizeof(ml));
        out.write(reinterpret_cast<const char*>(&ep), sizeof(ep));

        // Per-element data
        for (size_t i = 0; i < n; ++i) {
            // Label
            uint64_t label = labels_[i];
            out.write(reinterpret_cast<const char*>(&label), sizeof(label));

            // Level
            int32_t level = element_levels_[i];
            out.write(reinterpret_cast<const char*>(&level), sizeof(level));

            // Vector data
            out.write(reinterpret_cast<const char*>(data_[i].data()), d * sizeof(float));

            // Links per level
            for (int l = 0; l <= level; ++l) {
                uint32_t num_links = (l < (int)links_[i].size()) ? links_[i][l].size() : 0;
                out.write(reinterpret_cast<const char*>(&num_links), sizeof(num_links));
                if (num_links > 0 && l < (int)links_[i].size()) {
                    out.write(reinterpret_cast<const char*>(links_[i][l].data()),
                              num_links * sizeof(int));
                }
            }
        }

        out.close();
    }

    void loadIndex(const std::string& path, SpaceInterface<dist_t>* space) {
        std::ifstream in_file(path, std::ios::binary);
        if (!in_file.is_open()) {
            throw std::runtime_error("Cannot open file for reading: " + path);
        }

        uint32_t magic;
        in_file.read(reinterpret_cast<char*>(&magic), sizeof(magic));
        if (magic != 0x484E5357) {
            throw std::runtime_error("Invalid HNSW index file");
        }

        uint64_t n, d, m, efc;
        int32_t ml, ep;
        in_file.read(reinterpret_cast<char*>(&n), sizeof(n));
        in_file.read(reinterpret_cast<char*>(&d), sizeof(d));
        in_file.read(reinterpret_cast<char*>(&m), sizeof(m));
        in_file.read(reinterpret_cast<char*>(&efc), sizeof(efc));
        in_file.read(reinterpret_cast<char*>(&ml), sizeof(ml));
        in_file.read(reinterpret_cast<char*>(&ep), sizeof(ep));

        space_ = space;
        dim_ = d;
        data_size_ = d * sizeof(float);
        M_ = m;
        maxM_ = m;
        maxM0_ = m * 2;
        ef_construction_ = efc;
        mult_ = 1.0 / log(1.0 * M_);
        cur_element_count_ = n;
        maxlevel_ = ml;
        enterpoint_node_ = ep;

        // Ensure storage
        if (n > max_elements_) {
            max_elements_ = n + 10000;
            data_.resize(max_elements_);
            labels_.resize(max_elements_);
            element_levels_.resize(max_elements_, 0);
            links_.resize(max_elements_);
        }

        for (size_t i = 0; i < n; ++i) {
            uint64_t label;
            in_file.read(reinterpret_cast<char*>(&label), sizeof(label));
            labels_[i] = label;

            int32_t level;
            in_file.read(reinterpret_cast<char*>(&level), sizeof(level));
            element_levels_[i] = level;

            data_[i].resize(d);
            in_file.read(reinterpret_cast<char*>(data_[i].data()), d * sizeof(float));

            links_[i].resize(level + 1);
            for (int l = 0; l <= level; ++l) {
                uint32_t num_links;
                in_file.read(reinterpret_cast<char*>(&num_links), sizeof(num_links));
                links_[i][l].resize(num_links);
                if (num_links > 0) {
                    in_file.read(reinterpret_cast<char*>(links_[i][l].data()),
                                 num_links * sizeof(int));
                }
            }
        }

        in_file.close();
    }

    // ========================================================================
    // Utility
    // ========================================================================

    size_t getCurrentElementCount() const { return cur_element_count_; }
    size_t getMaxElements() const { return max_elements_; }

    void setEf(size_t ef) { ef_ = ef; }
};

} // namespace hnswlib
