#include "orpheus_wah_core.h"
#include <cstdio>
#include <cmath>
#include <cstdint>
using namespace orpheus;
static bool close(float a, float b, float e=1e-4f){ return std::fabs(a-b) < e; }

static bool test_wet_zero_is_identity() {
    printf("\n=== wah_core: wet==0 is identity ===\n");
    WahVoice v; v.Init(); WahParams p;
    bool ok = true;
    for (int i=0;i<256;i++){ float in = std::sin(i*0.3f); float out = v.process_sample(in,p,0.0f,48000.0f); v.advance(p,240.0); if(!close(out,in)){ ok=false; break; } }
    printf("  %s\n", ok?"PASS":"FAIL"); return ok;
}
static bool test_cutoff_in_band() {
    printf("\n=== wah_core: cutoff stays in band over a period ===\n");
    WahParams p; bool ok=true; float sr=48000.0f, hi=kWahMaxCutoffFrac*sr;
    for (float ph=0.0f; ph<1.0f; ph+=0.01f){ float hz=wah_cutoff_hz(p, wah_triangle_bipolar(ph), sr); if(hz<kWahMinCutoffHz-1e-3f||hz>hi+1e-3f){ok=false;break;} }
    printf("  %s\n", ok?"PASS":"FAIL"); return ok;
}
static bool test_depth_zero_holds_center() {
    printf("\n=== wah_core: depth==0 holds center_hz ===\n");
    WahParams p; p.depth=0.0f; bool ok=true;
    for (float ph=0.0f; ph<1.0f; ph+=0.05f){ if(!close(wah_cutoff_hz(p, wah_triangle_bipolar(ph), 48000.0f), p.center_hz, 1e-2f)){ok=false;break;} }
    printf("  %s\n", ok?"PASS":"FAIL"); return ok;
}
static bool test_period_matches_division() {
    printf("\n=== wah_core: LFO period matches beat division ===\n");
    WahParams p; p.rate_division=8.0f; double sps=240.0; // 240 samples/step
    double inc = wah_phase_increment(p, sps);
    double period = 1.0/inc; double expect = (16.0/8.0)*sps; // 2 steps
    bool ok = close((float)period,(float)expect,1.0f);
    printf("  period=%.1f expect=%.1f %s\n", period, expect, ok?"PASS":"FAIL"); return ok;
}
static bool test_high_q_stable() {
    printf("\n=== wah_core: high Q stays bounded on noise ===\n");
    WahVoice v; v.Init(); WahParams p; p.resonance_q=8.0f; uint32_t r=1; bool ok=true;
    for(int i=0;i<48000;i++){ r=r*1664525u+1013904223u; float in=((r>>9)*(1.0f/8388608.0f))-1.0f; float o=v.process_sample(in,p,1.0f,48000.0f); v.advance(p,240.0); if(!std::isfinite(o)||std::fabs(o)>20.0f){ok=false;break;} }
    printf("  %s\n", ok?"PASS":"FAIL"); return ok;
}
bool run_wah_core_tests(){ bool ok=true; ok&=test_wet_zero_is_identity(); ok&=test_cutoff_in_band(); ok&=test_depth_zero_holds_center(); ok&=test_period_matches_division(); ok&=test_high_q_stable(); return ok; }
