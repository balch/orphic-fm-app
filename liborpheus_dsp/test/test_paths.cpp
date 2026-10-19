// The only test TU compiled with TEST_DATA_DIR; see test_data_path() in test_harness.h.
#include "test_harness.h"

std::string test_data_path(const char* relative) {
    return std::string(TEST_DATA_DIR) + relative;
}
