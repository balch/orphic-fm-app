#pragma once
// A host's current engine, for threads other than the audio thread. Each call borrows it, and
// replacing it destroys the old engine only once no borrow can still reach it.
#include "orpheus_dsp.h"
#include <atomic>
#include <chrono>
#include <mutex>
#include <thread>

// Generic over the engine and how it is destroyed, so a test can retire a fake that records it.
template <typename Engine, void (*Destroy)(Engine*)>
class EngineSlot {
public:
    // Runs f(engine) with the current engine held; returns fallback when there is none.
    template <typename R, typename F>
    R with(R fallback, F&& f) {
        Borrow b(*this);
        Engine* e = engine_.load(std::memory_order_seq_cst);
        return e ? f(e) : fallback;
    }

    template <typename F>
    void with(F&& f) {
        Borrow b(*this);
        if (Engine* e = engine_.load(std::memory_order_seq_cst)) f(e);
    }

    // Installs an engine where there was none to destroy.
    void publish(Engine* next) { engine_.store(next, std::memory_order_seq_cst); }

    // Installs next, then destroys the old engine once every borrow that could hold it has ended.
    // Borrows count on one of two sides and the swap sends new ones to the other, so the side
    // being drained only falls: busy readers delay the destroy, they can't block it forever.
    // Never call from the audio thread or from inside with().
    void replace(Engine* next) {
        std::lock_guard<std::mutex> one_at_a_time(replace_lock_);
        Engine* old = engine_.exchange(next, std::memory_order_seq_cst);
        const unsigned drained = epoch_.fetch_add(1, std::memory_order_seq_cst) & 1u;
        // Always drained, even with no old engine: the next replace relies on this side being empty.
        while (readers_[drained].load(std::memory_order_seq_cst) != 0) {
            std::this_thread::sleep_for(std::chrono::microseconds(50));
        }
        if (old) Destroy(old);
    }

    // The audio thread reads it bare: a host destroys an engine only after its stream stopped.
    Engine* audio_thread_engine() const { return engine_.load(std::memory_order_acquire); }

private:
    // Counts on the side of the current epoch. If a swap flipped the epoch while it counted, it
    // moves to the new side, so each count sits on the side of an epoch it began and ended in.
    // All seq_cst: a count must be visible to replace() before the engine load that follows it.
    struct Borrow {
        explicit Borrow(EngineSlot& s) : s_(s) {
            for (;;) {
                const unsigned e = s_.epoch_.load(std::memory_order_seq_cst);
                side_ = e & 1u;
                s_.readers_[side_].fetch_add(1, std::memory_order_seq_cst);
                if (s_.epoch_.load(std::memory_order_seq_cst) == e) break;
                s_.readers_[side_].fetch_sub(1, std::memory_order_seq_cst);
            }
        }
        ~Borrow() { s_.readers_[side_].fetch_sub(1, std::memory_order_seq_cst); }
        Borrow(const Borrow&) = delete;
        Borrow& operator=(const Borrow&) = delete;
        EngineSlot& s_;
        unsigned side_ = 0;
    };

    std::atomic<Engine*> engine_{nullptr};
    std::atomic<unsigned> epoch_{0};
    std::atomic<int> readers_[2] = {};
    std::mutex replace_lock_;
};

using OrpheusEngineSlot = EngineSlot<OrpheusEngine, orpheus_engine_destroy>;
