#pragma once

// Kotlin PlaitsEngineId ordinal to the C++ Plaits engine index (see kOrpheusOutGain[] in
// orpheus_voice.h). -1 when the ordinal is out of range.
// Kotlin: 0=BD, 1=SD, 2=HH, 3=FMDrum, 4=FM, 5=Noise, 6=Waveshaping,
//         7=VA, 8=Additive, 9=Grain, 10=String, 11=Modal, 12=Particle,
//         13=Swarm, 14=Chord, 15=Wavetable, 16=Speech,
//         17=VA_VCF, 18=PhaseDistortion, 19=SixOpFM, 20=WaveTerrain,
//         21=StringMachine, 22=Chiptune,
//         23=SixOpFM_2 (bank 2), 24=SixOpFM_3 (bank 3)
inline int kotlin_engine_ordinal_to_plaits(int ordinal) {
    static constexpr int kKotlinToEngine[] = {
        21, 22, 23, 10, // BD, SD, HH, FMDrum→FM(10) (no C++ FmDrum; Kotlin has custom impl)
        10, 17,  9,  8, // FM, Noise, Waveshaping, VA
        12, 11, 19, 20, // Additive, Grain, String, Modal
        18, 16, 14, 13, // Particle, Swarm, Chord, Wavetable
        15,             // Speech
        // V1.2 engines (ordinals 17-22)
         0,  1,  2,  5, // VA_VCF, PhaseDistortion, SixOpFM, WaveTerrain
         6,  7,         // StringMachine, Chiptune
        // SixOp additional banks (ordinals 23-24)
         3,  4          // SixOpFM_2 (bank 2), SixOpFM_3 (bank 3)
    };
    constexpr int kCount = static_cast<int>(sizeof(kKotlinToEngine) / sizeof(kKotlinToEngine[0]));
    return (ordinal >= 0 && ordinal < kCount) ? kKotlinToEngine[ordinal] : -1;
}
