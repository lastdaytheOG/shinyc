#include "VectorEngine.h"
#include <mutex>
#include <stdexcept>
#include <algorithm>
#include <fstream>

// =============================================================================
// Constructor / Destructor
// =============================================================================

VectorEngine::VectorEngine() : dim_(0), initialized_(false) {}

VectorEngine::~VectorEngine() {
    // unique_ptrs handle cleanup
}

// =============================================================================
// init
// =============================================================================

void VectorEngine::init(int dim, int maxElements) {
    std::unique_lock<std::shared_mutex> lock(mutex_);

    if (dim <= 0 || maxElements <= 0) {
        throw std::invalid_argument("dim and maxElements must be positive");
    }

    dim_ = dim;
    space_ = std::make_unique<hnswlib::InnerProductSpace>(static_cast<size_t>(dim));
    index_ = std::make_unique<hnswlib::HierarchicalNSW<float>>(
        space_.get(),
        static_cast<size_t>(maxElements),
        static_cast<size_t>(M),
        static_cast<size_t>(EF_CONSTRUCTION)
    );
    index_->setEf(DEFAULT_EF_SEARCH);
    initialized_ = true;
}

// =============================================================================
// addVector
// =============================================================================

void VectorEngine::addVector(int id, const std::vector<float>& vector) {
    std::shared_lock<std::shared_mutex> lock(mutex_);

    if (!initialized_) {
        throw std::runtime_error("VectorEngine not initialized. Call init() first.");
    }

    if ((int)vector.size() != dim_) {
        throw std::invalid_argument(
            "Vector dimension mismatch: expected " + std::to_string(dim_) +
            " but got " + std::to_string(vector.size())
        );
    }

    // hnswlib::addPoint is internally thread-safe (has its own locks)
    // so we only need a shared lock here to protect against concurrent save/load
    index_->addPoint(vector.data(), static_cast<hnswlib::labeltype>(id));
}

// =============================================================================
// searchKnn
// =============================================================================

std::vector<std::pair<int, float>> VectorEngine::searchKnn(
    const std::vector<float>& query, int k)
{
    std::shared_lock<std::shared_mutex> lock(mutex_);

    if (!initialized_) {
        throw std::runtime_error("VectorEngine not initialized. Call init() first.");
    }

    if ((int)query.size() != dim_) {
        throw std::invalid_argument(
            "Query dimension mismatch: expected " + std::to_string(dim_) +
            " but got " + std::to_string(query.size())
        );
    }

    if (index_->getCurrentElementCount() == 0) {
        return {};
    }

    // Clamp k to available elements
    size_t actualK = std::min(static_cast<size_t>(k), index_->getCurrentElementCount());

    // hnswlib returns a max-heap of (distance, label)
    auto results = index_->searchKnn(query.data(), actualK);

    // Convert to sorted vector (ascending distance = most similar first)
    std::vector<std::pair<int, float>> sorted;
    sorted.reserve(results.size());
    while (!results.empty()) {
        auto [dist, label] = results.top();
        results.pop();
        sorted.emplace_back(static_cast<int>(label), dist);
    }

    // Sort ascending by distance (closest/most similar first)
    std::sort(sorted.begin(), sorted.end(),
        [](const auto& a, const auto& b) { return a.second < b.second; });

    return sorted;
}

// =============================================================================
// saveIndex — EXCLUSIVE LOCK (blocks reads and writes)
// =============================================================================

void VectorEngine::saveIndex(const std::string& path) {
    std::unique_lock<std::shared_mutex> lock(mutex_);

    if (!initialized_) {
        throw std::runtime_error("VectorEngine not initialized. Call init() first.");
    }

    index_->saveIndex(path);
}

// =============================================================================
// loadIndex — EXCLUSIVE LOCK
// =============================================================================

void VectorEngine::loadIndex(const std::string& path) {
    std::unique_lock<std::shared_mutex> lock(mutex_);

    if (!initialized_) {
        throw std::runtime_error("VectorEngine not initialized. Call init() first.");
    }

    std::ifstream check(path, std::ios::binary);
    if (!check.is_open()) {
        throw std::runtime_error("Index file not found: " + path);
    }
    check.close();

    index_->loadIndex(path, space_.get());
}

// =============================================================================
// Utility
// =============================================================================

size_t VectorEngine::getCount() const {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    if (!initialized_) return 0;
    return index_->getCurrentElementCount();
}

void VectorEngine::setSearchEf(int ef) {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    if (initialized_ && ef > 0) {
        index_->setEf(static_cast<size_t>(ef));
    }
}
