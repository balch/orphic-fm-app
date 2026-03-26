// Standalone runner for FM comparison tests only.
// Avoids depending on other test suites that may crash.
#include "test_harness.h"
#include <cstdio>

int main() {
    bool pass = run_fm_compare_tests();
    if (!pass) {
        fprintf(stderr, "\nFAILURE: FM compare tests failed!\n");
        return 1;
    }
    printf("\nSUCCESS: FM compare tests passed.\n");
    return 0;
}
