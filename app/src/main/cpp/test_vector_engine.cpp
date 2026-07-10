#include <iostream>
#include <vector>
#include <cmath>
#include <cassert>
#include <random>
#include <thread>
#include <chrono>
#include <cstdio>
#include <algorithm>
#include "VectorEngine.h"

#define GREEN "\033[32m"
#define RED   "\033[31m"
#define YELLOW "\033[33m"
#define RESET "\033[0m"

static int passed = 0;
static int failed = 0;

void check(const std::string& name, bool cond) {
    if (cond) {
        std::cout << GREEN << "  ✓ " << name << RESET << "\n";
        passed++;
    } else {
        std::cout << RED << "  ✗ " << name << RESET << "\n";
        failed++;
    }
}

// Generate a random normalized vector
std::vector<float> randomNormalizedVec(int dim, std::mt19937& rng) {
    std::normal_distribution<float> dist(0.0f, 1.0f);
    std::vector<float> v(dim);
    float norm = 0;
    for (int i = 0; i < dim; i++) {
        v[i] = dist(rng);
        norm += v[i] * v[i];
    }
    norm = std::sqrt(norm);
    for (int i = 0; i < dim; i++) v[i] /= norm;
    return v;
}

// Cosine similarity between two normalized vectors
float cosineSim(const std::vector<float>& a, const std::vector<float>& b) {
    float dot = 0;
    for (size_t i = 0; i < a.size(); i++) dot += a[i] * b[i];
    return dot;
}

int main() {
    const int DIM = 768;
    std::mt19937 rng(42);

    std::cout << "\n=== VectorEngine (HNSW) Unit Tests ===\n\n";

    // =========================================================================
    std::cout << "--- Basic Add & Search ---\n";
    {
        VectorEngine engine;
        engine.init(DIM, 1000);

        auto v1 = randomNormalizedVec(DIM, rng);
        auto v2 = randomNormalizedVec(DIM, rng);
        auto v3 = randomNormalizedVec(DIM, rng);

        engine.addVector(100, v1);
        engine.addVector(200, v2);
        engine.addVector(300, v3);

        check("getCount == 3", engine.getCount() == 3);

        // Search with v1 as query — should find itself as closest
        auto results = engine.searchKnn(v1, 3);
        check("search returns results", !results.empty());
        check("closest to v1 is ID 100", results[0].first == 100);
        check("distance to self is ~0", results[0].second < 0.01f);
    }

    // =========================================================================
    std::cout << "\n--- Cosine Similarity Correctness ---\n";
    {
        VectorEngine engine;
        engine.init(DIM, 1000);

        // Create a base vector and a near-duplicate (small perturbation)
        auto base = randomNormalizedVec(DIM, rng);
        auto similar = base; // copy
        // Perturb slightly
        std::normal_distribution<float> noise(0.0f, 0.01f);
        for (int i = 0; i < DIM; i++) similar[i] += noise(rng);
        // Re-normalize
        float norm = 0;
        for (float x : similar) norm += x * x;
        norm = std::sqrt(norm);
        for (float& x : similar) x /= norm;

        auto distant = randomNormalizedVec(DIM, rng);

        engine.addVector(1, base);
        engine.addVector(2, similar);
        engine.addVector(3, distant);

        auto results = engine.searchKnn(base, 3);
        check("base vector finds itself first", results[0].first == 1);
        check("similar vector is second closest", results[1].first == 2);
        check("similar is closer than distant",
              results[1].second < results[2].second);

        float sim = cosineSim(base, similar);
        check("cosine similarity of near-duplicate > 0.95", sim > 0.95f);
    }

    // =========================================================================
    std::cout << "\n--- Save & Load Persistence ---\n";
    {
        const std::string testPath = "/tmp/test_hnsw_index.bin";

        // Build and save
        {
            VectorEngine engine;
            engine.init(DIM, 1000);

            std::vector<std::vector<float>> vecs;
            for (int i = 0; i < 50; i++) {
                auto v = randomNormalizedVec(DIM, rng);
                vecs.push_back(v);
                engine.addVector(i, v);
            }

            engine.saveIndex(testPath);
            check("saveIndex doesn't crash", true);
        }

        // Load into a new engine
        {
            VectorEngine engine2;
            engine2.init(DIM, 1000);
            engine2.loadIndex(testPath);

            check("loadIndex restores count", engine2.getCount() == 50);

            // Search should still work
            auto query = randomNormalizedVec(DIM, rng);
            auto results = engine2.searchKnn(query, 5);
            check("search works after load", results.size() == 5);
        }

        std::remove(testPath.c_str());
    }

    // =========================================================================
    std::cout << "\n--- K Clamping ---\n";
    {
        VectorEngine engine;
        engine.init(DIM, 1000);

        engine.addVector(1, randomNormalizedVec(DIM, rng));
        engine.addVector(2, randomNormalizedVec(DIM, rng));
        engine.addVector(3, randomNormalizedVec(DIM, rng));

        // Ask for k=100 but only 3 vectors exist
        auto results = engine.searchKnn(randomNormalizedVec(DIM, rng), 100);
        check("k clamped to available vectors (3)", results.size() == 3);
    }

    // =========================================================================
    std::cout << "\n--- Empty Index ---\n";
    {
        VectorEngine engine;
        engine.init(DIM, 1000);

        auto results = engine.searchKnn(randomNormalizedVec(DIM, rng), 5);
        check("search on empty index returns empty", results.empty());
        check("getCount on empty index == 0", engine.getCount() == 0);
    }

    // =========================================================================
    std::cout << "\n--- Dimension Mismatch ---\n";
    {
        VectorEngine engine;
        engine.init(DIM, 1000);

        // Try adding a wrong-dimension vector
        bool threw = false;
        try {
            std::vector<float> wrongDim(128, 1.0f);
            engine.addVector(1, wrongDim);
        } catch (const std::exception& e) {
            threw = true;
        }
        check("addVector with wrong dim throws", threw);

        // Try searching with wrong dim
        threw = false;
        try {
            engine.addVector(1, randomNormalizedVec(DIM, rng));
            std::vector<float> wrongQuery(256, 1.0f);
            engine.searchKnn(wrongQuery, 5);
        } catch (const std::exception& e) {
            threw = true;
        }
        check("searchKnn with wrong dim throws", threw);
    }

    // =========================================================================
    std::cout << "\n--- Thread Safety (Concurrent Add + Search) ---\n";
    {
        VectorEngine engine;
        engine.init(DIM, 5000);

        bool threadError = false;

        // Writer thread
        std::thread writer([&]() {
            std::mt19937 wrng(123);
            for (int i = 0; i < 500; i++) {
                try {
                    engine.addVector(i, randomNormalizedVec(DIM, wrng));
                } catch (...) {
                    threadError = true;
                }
            }
        });

        // Reader threads
        std::vector<std::thread> readers;
        for (int t = 0; t < 4; t++) {
            readers.emplace_back([&]() {
                std::mt19937 rrng(456 + t);
                for (int i = 0; i < 100; i++) {
                    try {
                        engine.searchKnn(randomNormalizedVec(DIM, rrng), 5);
                    } catch (...) {
                        threadError = true;
                    }
                }
            });
        }

        writer.join();
        for (auto& r : readers) r.join();

        check("concurrent add+search doesn't crash", !threadError);
        check("all 500 vectors indexed", engine.getCount() == 500);
    }

    // =========================================================================
    std::cout << "\n--- Recall Quality (1000 vectors, brute-force comparison) ---\n";
    {
        VectorEngine engine;
        engine.init(DIM, 2000);
        engine.setSearchEf(64);

        const int N = 1000;
        const int K = 10;
        std::vector<std::vector<float>> allVecs;

        for (int i = 0; i < N; i++) {
            auto v = randomNormalizedVec(DIM, rng);
            allVecs.push_back(v);
            engine.addVector(i, v);
        }

        // Pick 20 random queries and measure recall
        int totalRecall = 0;
        int totalQueries = 20;

        for (int q = 0; q < totalQueries; q++) {
            auto query = randomNormalizedVec(DIM, rng);

            // Brute force ground truth
            std::vector<std::pair<float, int>> bruteForce;
            for (int i = 0; i < N; i++) {
                float dist = 1.0f - cosineSim(query, allVecs[i]);
                bruteForce.push_back({dist, i});
            }
            std::sort(bruteForce.begin(), bruteForce.end());

            std::unordered_set<int> groundTruth;
            for (int i = 0; i < K; i++) groundTruth.insert(bruteForce[i].second);

            // HNSW search
            auto results = engine.searchKnn(query, K);

            int hits = 0;
            for (auto& [id, dist] : results) {
                if (groundTruth.count(id)) hits++;
            }
            totalRecall += hits;
        }

        float avgRecall = static_cast<float>(totalRecall) / (totalQueries * K);
        std::cout << YELLOW << "    Average recall@" << K << " = "
                  << (avgRecall * 100) << "%" << RESET << "\n";
        check("recall@10 >= 80%", avgRecall >= 0.80f);
    }

    // =========================================================================
    std::cout << "\n--- Latency Benchmark (1000 vectors, 768 dim) ---\n";
    {
        VectorEngine engine;
        engine.init(DIM, 2000);

        for (int i = 0; i < 1000; i++) {
            engine.addVector(i, randomNormalizedVec(DIM, rng));
        }

        // Warm up
        for (int i = 0; i < 10; i++) {
            engine.searchKnn(randomNormalizedVec(DIM, rng), 10);
        }

        // Benchmark
        int iterations = 100;
        auto start = std::chrono::high_resolution_clock::now();
        for (int i = 0; i < iterations; i++) {
            engine.searchKnn(randomNormalizedVec(DIM, rng), 10);
        }
        auto end = std::chrono::high_resolution_clock::now();

        double totalMs = std::chrono::duration<double, std::milli>(end - start).count();
        double avgMs = totalMs / iterations;

        std::cout << YELLOW << "    Average search latency: "
                  << avgMs << " ms" << RESET << "\n";
        check("search latency < 50ms (1K vectors, x86 sandbox)", avgMs < 50.0);
    }

    // =========================================================================
    std::cout << "\n========================================\n";
    std::cout << "Results: " << GREEN << passed << " passed" << RESET
              << ", " << (failed > 0 ? RED : GREEN) << failed << " failed" << RESET << "\n";
    std::cout << "========================================\n\n";

    return failed > 0 ? 1 : 0;
}
