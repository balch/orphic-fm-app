# Builds a patched copy of the Rings DSP sources in the build dir (never touches EURORACK_DIR).
# The patch makes kSampleRate/a3 a runtime value; see docs/BUILD.md.
#
# Sets: PATCHED_RINGS_SRC (sources to compile), PATCHED_RINGS_INCLUDE (put first on include paths).

set(RINGS_PATCH_FILE "${CMAKE_CURRENT_SOURCE_DIR}/patches/rings-runtime-sample-rate.patch")
set(PATCHED_RINGS_INCLUDE "${CMAKE_CURRENT_BINARY_DIR}/patched_rings")
set(_rings_stamp "${PATCHED_RINGS_INCLUDE}/.stamp")

# Reconfigure when the patch changes.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${RINGS_PATCH_FILE}")

file(SHA256 "${RINGS_PATCH_FILE}" _rings_patch_hash)
set(_rings_want "${_rings_patch_hash} ${EURORACK_DIR}")
set(_rings_have "")
if(EXISTS "${_rings_stamp}")
    file(READ "${_rings_stamp}" _rings_have)
endif()

# Skip the copy when nothing changed, so a reconfigure doesn't touch mtimes and rebuild Rings.
if(NOT "${_rings_have}" STREQUAL "${_rings_want}")
    find_program(RINGS_PATCH_PROGRAM patch REQUIRED)
    file(REMOVE_RECURSE "${PATCHED_RINGS_INCLUDE}")
    file(MAKE_DIRECTORY "${PATCHED_RINGS_INCLUDE}/rings")
    file(COPY "${EURORACK_DIR}/rings/dsp" DESTINATION "${PATCHED_RINGS_INCLUDE}/rings")
    file(COPY "${EURORACK_DIR}/rings/resources.h" "${EURORACK_DIR}/rings/resources.cc"
         DESTINATION "${PATCHED_RINGS_INCLUDE}/rings")
    execute_process(
        COMMAND "${RINGS_PATCH_PROGRAM}" -p1 --batch --forward -i "${RINGS_PATCH_FILE}"
        WORKING_DIRECTORY "${PATCHED_RINGS_INCLUDE}"
        RESULT_VARIABLE _rings_patch_rc
        OUTPUT_VARIABLE _rings_patch_out
        ERROR_VARIABLE _rings_patch_out)
    if(NOT _rings_patch_rc EQUAL 0)
        file(REMOVE_RECURSE "${PATCHED_RINGS_INCLUDE}")
        message(FATAL_ERROR "Could not apply ${RINGS_PATCH_FILE} to ${EURORACK_DIR}/rings "
                            "(upstream changed?):\n${_rings_patch_out}")
    endif()
    file(WRITE "${_rings_stamp}" "${_rings_want}")
endif()

set(PATCHED_RINGS_SRC
    "${PATCHED_RINGS_INCLUDE}/rings/dsp/dsp.cc"
    "${PATCHED_RINGS_INCLUDE}/rings/dsp/fm_voice.cc"
    "${PATCHED_RINGS_INCLUDE}/rings/dsp/part.cc"
    "${PATCHED_RINGS_INCLUDE}/rings/dsp/resonator.cc"
    "${PATCHED_RINGS_INCLUDE}/rings/dsp/string.cc"
    "${PATCHED_RINGS_INCLUDE}/rings/dsp/string_synth_part.cc"
    "${PATCHED_RINGS_INCLUDE}/rings/resources.cc"
)
