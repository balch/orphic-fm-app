// EngineSlot: how the Android host keeps JNI reads safe while a route change rebuilds the engine.
// The fake-engine stress catches a destroy under a borrow in any build; the real-engine stress
// adds ASan's view of the same thing.
//
//   liborpheus_dsp/build-desktop/orpheus_dsp_test engine_slot
//   liborpheus_dsp/build-asan/orpheus_dsp_test engine_slot
#include "test_harness.h"
#include "orpheus_engine_slot.h"
#include <atomic>
#include <cstdlib>
#include <memory>
#include <thread>

static bool test_engine_slot_replace_waits_for_a_live_borrow() {
    printf("\n=== Test: replace() waits for a borrow already inside the old engine ===\n");
    OrpheusEngineSlot slot;
    OrpheusEngine* first = orpheus_engine_create(48000.0f);
    OrpheusEngine* second = orpheus_engine_create(48000.0f);
    slot.publish(first);

    std::atomic<bool> inside{false}, release{false}, replaced{false};
    std::thread borrower([&] {
        slot.with([&](OrpheusEngine* e) {
            inside.store(true);
            while (!release.load()) std::this_thread::yield();
            float out[64];
            orpheus_engine_get_scope(e, out, 64, 40.0f);  // still valid: replace() is waiting
        });
    });
    while (!inside.load()) std::this_thread::yield();
    std::thread replacer([&] {
        slot.replace(second);
        replaced.store(true);
    });

    // Once a new borrow sees the new engine the swap has happened, and replace() must keep
    // waiting on the first borrow for as long as it lasts (watched for 50 ms).
    OrpheusEngine* seen = nullptr;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (seen != second && std::chrono::steady_clock::now() < deadline) {
        seen = slot.with(static_cast<OrpheusEngine*>(nullptr), [](OrpheusEngine* e) { return e; });
        if (seen != second) std::this_thread::yield();
    }
    bool waited = seen == second;
    const auto watch_end = std::chrono::steady_clock::now() + std::chrono::milliseconds(50);
    while (waited && std::chrono::steady_clock::now() < watch_end) {
        waited = !replaced.load();
        std::this_thread::yield();
    }
    release.store(true);
    borrower.join();
    replacer.join();

    printf("  replace() still waiting while the borrow was open: %s\n", waited ? "yes" : "NO");
    printf("  a later borrow saw the new engine: %s\n", seen == second ? "yes" : "NO");
    printf("  replace() returned once the borrow ended: %s\n", replaced.load() ? "yes" : "NO");
    slot.replace(nullptr);
    const bool pass = waited && seen == second && replaced.load();
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

// A fake engine whose destroy only marks it dead and keeps its memory, so a borrow that outlives
// the destroy reads alive == false: a plain, defined failure in Release, no sanitizer needed.
struct FakeEngine {
    std::atomic<bool> alive{true};
};
static void retire_fake(FakeEngine* e) { e->alive.store(false, std::memory_order_seq_cst); }
using FakeSlot = EngineSlot<FakeEngine, retire_fake>;

static bool test_engine_slot_keeps_a_held_engine_alive() {
    printf("\n=== Test: a fake engine held by a borrow outlives its replacement ===\n");
    FakeEngine held, next;
    FakeSlot slot;
    slot.publish(&held);

    std::atomic<bool> inside{false}, release{false};
    std::thread borrower([&] {
        slot.with([&](FakeEngine*) {
            inside.store(true);
            while (!release.load()) std::this_thread::yield();
        });
    });
    while (!inside.load()) std::this_thread::yield();
    std::thread replacer([&] { slot.replace(&next); });

    // Once a new borrow sees the replacement the swap is done; the held engine must then stay
    // alive for as long as the first borrow lasts. 50 ms is ample for a destroy that didn't wait.
    FakeEngine* seen = nullptr;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (seen != &next && std::chrono::steady_clock::now() < deadline) {
        seen = slot.with(static_cast<FakeEngine*>(nullptr), [](FakeEngine* e) { return e; });
    }
    bool stayed_alive = seen == &next;
    const auto watch_end = std::chrono::steady_clock::now() + std::chrono::milliseconds(50);
    while (stayed_alive && std::chrono::steady_clock::now() < watch_end) {
        stayed_alive = held.alive.load();
        std::this_thread::yield();
    }
    release.store(true);
    borrower.join();
    replacer.join();

    printf("  swapped: %s; held engine alive while borrowed: %s; destroyed after: %s\n",
           seen == &next ? "yes" : "NO", stayed_alive ? "yes" : "NO", held.alive.load() ? "NO" : "yes");
    const bool pass = seen == &next && stayed_alive && !held.alive.load() && next.alive.load();
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_engine_slot_never_destroys_under_a_borrow() {
    printf("\n=== Test: 2000 swaps of a fake engine never land inside a borrow ===\n");
    constexpr int kSwaps = 2000;
    std::vector<std::unique_ptr<FakeEngine>> engines;
    for (int i = 0; i <= kSwaps; ++i) engines.push_back(std::make_unique<FakeEngine>());

    FakeSlot slot;
    slot.publish(engines[0].get());
    std::atomic<bool> stop{false};
    std::atomic<long> borrows{0}, dead{0};
    auto reader = [&] {
        while (!stop.load(std::memory_order_relaxed)) {
            slot.with([&](FakeEngine* e) {
                if (!e->alive.load()) dead.fetch_add(1);
                // Linger, so a destroy that doesn't wait lands mid-borrow.
                for (int spin = 0; spin < 512; ++spin) {
                    if (!e->alive.load(std::memory_order_relaxed)) break;
                }
                if (!e->alive.load()) dead.fetch_add(1);
            });
            borrows.fetch_add(1, std::memory_order_relaxed);
        }
    };
    std::thread a(reader), b(reader), c(reader);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(60);
    for (int i = 1; i <= kSwaps; ++i) {
        slot.replace(engines[i].get());
        // Paced, so the swaps are spread across many borrows rather than done in a burst.
        const auto until = std::chrono::steady_clock::now() + std::chrono::microseconds(20);
        while (std::chrono::steady_clock::now() < until) std::this_thread::yield();
        if (std::chrono::steady_clock::now() > deadline) {
            fprintf(stderr, "  FAIL: replace() stopped returning under constant reads (livelock)\n");
            std::_Exit(1);
        }
    }
    stop.store(true);
    a.join();
    b.join();
    c.join();

    int retired = 0;
    for (int i = 0; i < kSwaps; ++i) retired += engines[i]->alive.load() ? 0 : 1;
    printf("  %ld borrows, %ld saw a destroyed engine (limit 0), %d/%d old engines destroyed\n",
           borrows.load(), dead.load(), retired, kSwaps);
    const bool pass = dead.load() == 0 && borrows.load() > 0 && retired == kSwaps;
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

// The real engine under ASan: every borrow does real engine work, so a destroy under a live read
// is a heap use-after-free that aborts the run. In Release this only checks it completes.
static bool test_engine_slot_survives_rebuilds_under_reads() {
    printf("\n=== Test: 20 engine rebuilds while three threads read and write it ===\n");
    OrpheusEngineSlot slot;
    slot.publish(orpheus_engine_create(48000.0f));
    std::atomic<bool> stop{false};
    std::atomic<long> calls{0}, empty{0};

    auto reader = [&](int kind) {
        float buf[256];
        OrpheusMonitorData mon;
        while (!stop.load(std::memory_order_relaxed)) {
            const bool hit = slot.with(false, [&](OrpheusEngine* e) {
                switch (kind) {
                    case 0: orpheus_engine_get_scope(e, buf, 256, 40.0f); break;
                    case 1: orpheus_engine_get_spectrum(e, buf, 40); break;
                    default:
                        orpheus_engine_set_port(e, "org.balch.orpheus.plugins.reverb", "amount", 0.3f);
                        orpheus_engine_get_monitor(e, &mon);
                        break;
                }
                return true;
            });
            (hit ? calls : empty).fetch_add(1, std::memory_order_relaxed);
        }
    };
    std::thread a(reader, 0), b(reader, 1), c(reader, 2);

    const int rebuilds = 20;
    std::atomic<bool> rebuilt{false};
    double slowest_ms = 0.0;
    std::thread rebuilder([&] {
        for (int i = 0; i < rebuilds; ++i) {
            // Onboard speaker to Bluetooth and back, the way onErrorAfterClose does it, then a
            // straight swap to a prebuilt engine so reads are always inside the one retired.
            OrpheusEngine* next = orpheus_engine_create(i % 2 ? 48000.0f : 44100.0f);
            const auto t0 = std::chrono::steady_clock::now();
            if (i % 2) {
                slot.replace(nullptr);
                slot.publish(next);
            } else {
                slot.replace(next);
            }
            const double ms = std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - t0).count();
            slowest_ms = std::max(slowest_ms, ms);
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        rebuilt.store(true);
    });
    // Readers never pause, so a replace() that waits for all of them at once would never return.
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(60);
    while (!rebuilt.load()) {
        if (std::chrono::steady_clock::now() > deadline) {
            fprintf(stderr, "  FAIL: replace() never returned under constant reads (livelock)\n");
            std::_Exit(1);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    rebuilder.join();
    stop.store(true);
    a.join();
    b.join();
    c.join();
    slot.replace(nullptr);

    printf("  %d rebuilds under constant reads, slowest replace %.2f ms\n", rebuilds, slowest_ms);
    printf("  %ld engine calls, %ld calls between engines (no engine)\n", calls.load(), empty.load());
    const bool pass = calls.load() > 0;
    printf(pass ? "  PASS (a use-after-free here aborts under ASan)\n" : "  FAIL: no reads ran\n");
    return pass;
}

bool run_engine_slot_tests() {
    int p = 0, f = 0;
    auto tally = [&](bool ok) { if (ok) ++p; else ++f; };
    tally(test_engine_slot_replace_waits_for_a_live_borrow());
    tally(test_engine_slot_keeps_a_held_engine_alive());
    tally(test_engine_slot_never_destroys_under_a_borrow());
    tally(test_engine_slot_survives_rebuilds_under_reads());
    TEST_SUITE_RETURN(p, f);
}
