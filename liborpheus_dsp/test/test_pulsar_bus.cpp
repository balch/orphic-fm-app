#include "test_harness.h"
#include "orpheus_unit_pulsar.h"
#include <cstdio>

static bool test_drum_engines_classified_as_drums() {
    printf("\n=== Test: Drum engines classified as drums ===\n");
    bool pass = true;
    pass &= (kEngineBusType[21] == PULSAR_BUS_DRUMS); // BassDrum
    pass &= (kEngineBusType[22] == PULSAR_BUS_DRUMS); // SnareDrum
    pass &= (kEngineBusType[23] == PULSAR_BUS_DRUMS); // HiHat
    pass &= (kEngineBusType[17] == PULSAR_BUS_DRUMS); // Noise
    pass &= (kEngineBusType[18] == PULSAR_BUS_DRUMS); // Particle
    pass &= (kEngineBusType[20] == PULSAR_BUS_DRUMS); // Modal
    printf("  Result: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_bass_engines_classified_as_bass() {
    printf("\n=== Test: Bass engines classified as bass ===\n");
    bool pass = (kEngineBusType[0] == PULSAR_BUS_BASS); // VirtualAnalogVCF
    printf("  Result: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_melodic_engines_classified_as_keys() {
    printf("\n=== Test: Melodic engines classified as keys ===\n");
    bool pass = true;
    pass &= (kEngineBusType[10] == PULSAR_BUS_KEYS); // FM
    pass &= (kEngineBusType[13] == PULSAR_BUS_KEYS); // Wavetable
    pass &= (kEngineBusType[14] == PULSAR_BUS_KEYS); // Chord
    pass &= (kEngineBusType[19] == PULSAR_BUS_KEYS); // String
    printf("  Result: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_all_engines_have_valid_bus() {
    printf("\n=== Test: All 24 engines have valid bus type ===\n");
    bool pass = true;
    for (int i = 0; i < 24; i++) {
        PulsarBusType bus = kEngineBusType[i];
        bool valid = (bus == PULSAR_BUS_KEYS || bus == PULSAR_BUS_DRUMS || bus == PULSAR_BUS_BASS);
        if (!valid) {
            printf("  FAIL: engine %d has invalid bus type %d\n", i, (int)bus);
            pass = false;
        }
    }
    printf("  Result: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool run_pulsar_bus_tests() {
    printf("\n========== PULSAR BUS TESTS ==========\n");
    bool all_pass = true;
    all_pass &= test_drum_engines_classified_as_drums();
    all_pass &= test_bass_engines_classified_as_bass();
    all_pass &= test_melodic_engines_classified_as_keys();
    all_pass &= test_all_engines_have_valid_bus();
    printf("\nPulsar bus tests: %s\n", all_pass ? "ALL PASSED" : "SOME FAILED");
    return all_pass;
}
