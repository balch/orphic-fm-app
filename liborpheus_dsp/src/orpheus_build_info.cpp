#include "orpheus_dsp.h"

#if !defined(_WIN32)
#include <dlfcn.h>
#endif

// Asks the loader which image holds this function, so the answer is the file it mapped.
const char* orpheus_library_path(void) {
#if defined(_WIN32)
    return "";
#else
    Dl_info info;
    if (dladdr(reinterpret_cast<void*>(&orpheus_library_path), &info) && info.dli_fname) {
        return info.dli_fname;
    }
    return "";
#endif
}
