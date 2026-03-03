package org.balch.orpheus.features.visualizations.viz.ants

/**
 * 3D Simplex noise — Stefan Gustavson's algorithm, ported to Kotlin.
 * Used by curl noise flow field for ant trail generation.
 */
internal object SimplexNoise {
    // Gradient vectors for 3D
    private val grad3 = intArrayOf(
        1,1,0, -1,1,0, 1,-1,0, -1,-1,0,
        1,0,1, -1,0,1, 1,0,-1, -1,0,-1,
        0,1,1, 0,-1,1, 0,1,-1, 0,-1,-1
    )

    // Permutation table (doubled to avoid wrapping)
    private val perm = IntArray(512)
    private val p = intArrayOf(
        151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,
        142,8,99,37,240,21,10,23,190,6,148,247,120,234,75,0,26,197,62,94,252,
        219,203,117,35,11,32,57,177,33,88,237,149,56,87,174,20,125,136,171,168,
        68,175,74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,122,60,211,
        133,230,220,105,92,41,55,46,245,40,244,102,143,54,65,25,63,161,1,216,
        80,73,209,76,132,187,208,89,18,169,200,196,135,130,116,188,159,86,164,
        100,109,198,173,186,3,64,52,217,226,250,124,123,5,202,38,147,118,126,
        255,82,85,212,207,206,59,227,47,16,58,17,182,189,28,42,223,183,170,213,
        119,248,152,2,44,154,163,70,221,153,101,155,167,43,172,9,129,22,39,253,
        19,98,108,110,79,113,224,232,178,185,112,104,218,246,97,228,251,34,242,
        193,238,210,144,12,191,179,162,241,81,51,145,235,249,14,239,107,49,192,
        214,31,181,199,106,157,184,84,204,176,115,121,50,45,127,4,150,254,138,
        236,205,93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180
    )

    init {
        for (i in 0 until 512) { perm[i] = p[i and 255] }
    }

    private fun dot(gi: Int, x: Float, y: Float, z: Float): Float {
        val idx = gi * 3
        return grad3[idx] * x + grad3[idx + 1] * y + grad3[idx + 2] * z
    }

    private fun fastFloor(x: Float): Int {
        val xi = x.toInt()
        return if (x < xi) xi - 1 else xi
    }

    /**
     * 3D simplex noise, returns value in approximately [-1, 1].
     */
    fun noise(xin: Float, yin: Float, zin: Float): Float {
        val F3 = (1f / 3f)
        val G3 = (1f / 6f)

        val s = (xin + yin + zin) * F3
        val i = fastFloor(xin + s)
        val j = fastFloor(yin + s)
        val k = fastFloor(zin + s)
        val t = (i + j + k) * G3
        val x0 = xin - (i - t)
        val y0 = yin - (j - t)
        val z0 = zin - (k - t)

        val i1: Int; val j1: Int; val k1: Int
        val i2: Int; val j2: Int; val k2: Int
        if (x0 >= y0) {
            if (y0 >= z0) { i1=1; j1=0; k1=0; i2=1; j2=1; k2=0 }
            else if (x0 >= z0) { i1=1; j1=0; k1=0; i2=1; j2=0; k2=1 }
            else { i1=0; j1=0; k1=1; i2=1; j2=0; k2=1 }
        } else {
            if (y0 < z0) { i1=0; j1=0; k1=1; i2=0; j2=1; k2=1 }
            else if (x0 < z0) { i1=0; j1=1; k1=0; i2=0; j2=1; k2=1 }
            else { i1=0; j1=1; k1=0; i2=1; j2=1; k2=0 }
        }

        val x1 = x0 - i1 + G3; val y1 = y0 - j1 + G3; val z1 = z0 - k1 + G3
        val x2 = x0 - i2 + 2f * G3; val y2 = y0 - j2 + 2f * G3; val z2 = z0 - k2 + 2f * G3
        val x3 = x0 - 1f + 3f * G3; val y3 = y0 - 1f + 3f * G3; val z3 = z0 - 1f + 3f * G3

        val ii = i and 255; val jj = j and 255; val kk = k and 255
        val gi0 = perm[ii + perm[jj + perm[kk]]] % 12
        val gi1 = perm[ii + i1 + perm[jj + j1 + perm[kk + k1]]] % 12
        val gi2 = perm[ii + i2 + perm[jj + j2 + perm[kk + k2]]] % 12
        val gi3 = perm[ii + 1 + perm[jj + 1 + perm[kk + 1]]] % 12

        var n0 = 0f; var n1 = 0f; var n2 = 0f; var n3 = 0f
        var t0 = 0.6f - x0*x0 - y0*y0 - z0*z0
        if (t0 > 0) { t0 *= t0; n0 = t0 * t0 * dot(gi0, x0, y0, z0) }
        var t1 = 0.6f - x1*x1 - y1*y1 - z1*z1
        if (t1 > 0) { t1 *= t1; n1 = t1 * t1 * dot(gi1, x1, y1, z1) }
        var t2 = 0.6f - x2*x2 - y2*y2 - z2*z2
        if (t2 > 0) { t2 *= t2; n2 = t2 * t2 * dot(gi2, x2, y2, z2) }
        var t3 = 0.6f - x3*x3 - y3*y3 - z3*z3
        if (t3 > 0) { t3 *= t3; n3 = t3 * t3 * dot(gi3, x3, y3, z3) }

        return 32f * (n0 + n1 + n2 + n3)
    }
}
