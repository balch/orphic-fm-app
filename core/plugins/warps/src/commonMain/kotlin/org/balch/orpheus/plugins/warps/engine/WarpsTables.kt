package org.balch.orpheus.plugins.warps.engine

/**
 * Port of Mutable Instruments Warps lookup tables.
 */
object WarpsTables {
    fun interpolate(table: FloatArray, index: Float, size: Float): Float {
        val scaledIndex = index * size
        val intIndex = scaledIndex.toInt().coerceIn(0, table.size - 2)
        val frac = scaledIndex - intIndex
        return table[intIndex] + frac * (table[intIndex + 1] - table[intIndex])
    }

    // These tables will be populated from eurorack/warps/resources.cc
    // For now, I'll define placeholders or use mathematical approximations where possible
    // until I can extract the full data.
    
    val LUT_SIN = FloatArray(1281) // TODO: Populate from resources.cc
    val LUT_XFADE_IN = FloatArray(257) // TODO: Populate from resources.cc
    val LUT_XFADE_OUT = FloatArray(257) // TODO: Populate from resources.cc
    val LUT_BIPOLAR_FOLD = FloatArray(4097) // TODO: Populate from resources.cc
    
    // MIDI to Frequency tables - these are common across many MI modules
    val LUT_MIDI_TO_F_HIGH = FloatArray(256)
    val LUT_MIDI_TO_F_LOW = FloatArray(256)
}
