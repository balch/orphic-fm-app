#include "orpheus_master_swell.h"
#include <cmath>
namespace orpheus {
static inline float ease(float a, float b, float x){ if(x<=0)return a; if(x>=1)return b; float c=0.5f-0.5f*std::cos(x*3.14159265f); return a+(b-a)*c; }
void MasterSwell::process(float* buf, size_t n) {
    int left = samples_left_.load(std::memory_order_acquire);
    if (left <= 0) return;
    int total = samples_total_.load(std::memory_order_relaxed);
    for (size_t i = 0; i < n; ++i) {
        if (left <= 0) break;
        float t = 1.0f - (float)left / (float)total;                 // 0 -> 1
        float g = (t < 0.5f) ? ease(start_, peak_, t * 2.0f)         // rise
                             : ease(peak_, 1.0f, (t - 0.5f) * 2.0f); // settle
        buf[i] *= g;
        --left;
    }
    samples_left_.store(left, std::memory_order_relaxed);
}
} // namespace orpheus
