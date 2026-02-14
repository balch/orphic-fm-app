// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles random/distributions.h.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

/** Generates samples from various kinds of random distributions.
 *  Uses pre-computed ICDF tables for the Beta distribution. */
object Distributions {
    private const val NUM_BIAS_VALUES = 5
    private const val NUM_RANGE_VALUES = 9
    private const val ICDF_TABLE_SIZE = 128f

    /** Generate a sample from a Beta distribution using pre-computed ICDF tables.
     *  Bilinear interpolation across 4 tables (bias x spread).
     *  For bias > 0.5, symmetry is used. */
    fun betaDistributionSample(uniform: Float, spread: Float, bias: Float): Float {
        var u = uniform
        var b = bias

        // Tables only stored for bias <= 0.5; use symmetry for > 0.5
        val flipResult = b > 0.5f
        if (flipResult) {
            u = 1f - u
            b = 1f - b
        }

        b *= (NUM_BIAS_VALUES - 1).toFloat() * 2f
        var s = spread * (NUM_RANGE_VALUES - 1).toFloat()

        val biasIntegral = b.toInt()
        val biasFractional = b - biasIntegral
        val spreadIntegral = s.toInt()
        val spreadFractional = s - spreadIntegral

        val cell = biasIntegral * (NUM_RANGE_VALUES + 1) + spreadIntegral

        // Lower 5% and upper 95% percentiles use higher-resolution tail tables
        var offset = 0
        if (u <= 0.05f) {
            offset = (ICDF_TABLE_SIZE.toInt() + 1)
            u *= 20f
        } else if (u >= 0.95f) {
            offset = 2 * (ICDF_TABLE_SIZE.toInt() + 1)
            u = (u - 0.95f) * 20f
        }

        val tables = DistributionTables.distributionsTable
        val x1y1 = DspUtil.interpolate(tables[cell], u, ICDF_TABLE_SIZE, offset)
        val x2y1 = DspUtil.interpolate(tables[cell + 1], u, ICDF_TABLE_SIZE, offset)
        val x1y2 = DspUtil.interpolate(tables[cell + NUM_RANGE_VALUES + 1], u, ICDF_TABLE_SIZE, offset)
        val x2y2 = DspUtil.interpolate(tables[cell + NUM_RANGE_VALUES + 2], u, ICDF_TABLE_SIZE, offset)

        val y1 = x1y1 + (x2y1 - x1y1) * spreadFractional
        val y2 = x1y2 + (x2y2 - x1y2) * spreadFractional
        var y = y1 + (y2 - y1) * biasFractional

        if (flipResult) {
            y = 1f - y
        }
        return y
    }

    /** Pre-computed Beta(3,3) with fatter tail, used for jitter distribution. */
    fun fastBetaDistributionSample(uniform: Float): Float {
        return DspUtil.interpolate(DistributionTables.fastBetaIcdf, uniform, ICDF_TABLE_SIZE)
    }
}
