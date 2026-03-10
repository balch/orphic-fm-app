#include "orpheus_resonator.h"
#include <cmath>
#include <algorithm>

// ── Lookup Tables ───────────────────────────────────────────────

const float kOrpheusLutStiffness[257] = {
    -6.250000000e-02f, -6.152343750e-02f, -6.054687500e-02f, -5.957031250e-02f,
    -5.859375000e-02f, -5.761718750e-02f, -5.664062500e-02f, -5.566406250e-02f,
    -5.468750000e-02f, -5.371093750e-02f, -5.273437500e-02f, -5.175781250e-02f,
    -5.078125000e-02f, -4.980468750e-02f, -4.882812500e-02f, -4.785156250e-02f,
    -4.687500000e-02f, -4.589843750e-02f, -4.492187500e-02f, -4.394531250e-02f,
    -4.296875000e-02f, -4.199218750e-02f, -4.101562500e-02f, -4.003906250e-02f,
    -3.906250000e-02f, -3.808593750e-02f, -3.710937500e-02f, -3.613281250e-02f,
    -3.515625000e-02f, -3.417968750e-02f, -3.320312500e-02f, -3.222656250e-02f,
    -3.125000000e-02f, -3.027343750e-02f, -2.929687500e-02f, -2.832031250e-02f,
    -2.734375000e-02f, -2.636718750e-02f, -2.539062500e-02f, -2.441406250e-02f,
    -2.343750000e-02f, -2.246093750e-02f, -2.148437500e-02f, -2.050781250e-02f,
    -1.953125000e-02f, -1.855468750e-02f, -1.757812500e-02f, -1.660156250e-02f,
    -1.562500000e-02f, -1.464843750e-02f, -1.367187500e-02f, -1.269531250e-02f,
    -1.171875000e-02f, -1.074218750e-02f, -9.765625000e-03f, -8.789062500e-03f,
    -7.812500000e-03f, -6.835937500e-03f, -5.859375000e-03f, -4.882812500e-03f,
    -3.906250000e-03f, -2.929687500e-03f, -1.953125000e-03f, -9.765625000e-04f,
     0.000000000e+00f,  0.000000000e+00f,  0.000000000e+00f,  0.000000000e+00f,
     0.000000000e+00f,  0.000000000e+00f,  0.000000000e+00f,  0.000000000e+00f,
     0.000000000e+00f,  0.000000000e+00f,  0.000000000e+00f,  0.000000000e+00f,
     0.000000000e+00f,  6.029410294e-05f,  3.672617230e-04f,  6.835957809e-04f,
     1.009582073e-03f,  1.345515115e-03f,  1.691698412e-03f,  2.048444725e-03f,
     2.416076364e-03f,  2.794925468e-03f,  3.185334315e-03f,  3.587655624e-03f,
     4.002252878e-03f,  4.429500650e-03f,  4.869784943e-03f,  5.323503537e-03f,
     5.791066350e-03f,  6.272895808e-03f,  6.769427226e-03f,  7.281109202e-03f,
     7.808404022e-03f,  8.351788076e-03f,  8.911752293e-03f,  9.488802580e-03f,
     1.008346028e-02f,  1.069626264e-02f,  1.132776331e-02f,  1.197853283e-02f,
     1.264915914e-02f,  1.334024813e-02f,  1.405242417e-02f,  1.478633069e-02f,
     1.554263074e-02f,  1.632200761e-02f,  1.712516545e-02f,  1.795282987e-02f,
     1.880574864e-02f,  1.968469234e-02f,  2.059045506e-02f,  2.152385512e-02f,
     2.248573583e-02f,  2.347696619e-02f,  2.449844176e-02f,  2.555108540e-02f,
     2.663584813e-02f,  2.775370999e-02f,  2.890568094e-02f,  3.009280173e-02f,
     3.131614488e-02f,  3.257681565e-02f,  3.387595299e-02f,  3.521473064e-02f,
     3.659435812e-02f,  3.801608189e-02f,  3.948118641e-02f,  4.099099536e-02f,
     4.254687278e-02f,  4.415022437e-02f,  4.580249868e-02f,  4.750518848e-02f,
     4.925983210e-02f,  5.106801479e-02f,  5.293137017e-02f,  5.485158172e-02f,
     5.683038428e-02f,  5.886956562e-02f,  6.097096806e-02f,  6.313649016e-02f,
     6.536808837e-02f,  6.766777886e-02f,  7.003763933e-02f,  7.247981084e-02f,
     7.499649981e-02f,  7.758997998e-02f,  8.026259446e-02f,  8.301675786e-02f,
     8.585495846e-02f,  8.877976048e-02f,  9.179380636e-02f,  9.489981918e-02f,
     9.810060511e-02f,  1.013990559e-01f,  1.047981517e-01f,  1.083009634e-01f,
     1.119106556e-01f,  1.156304895e-01f,  1.194638260e-01f,  1.234141283e-01f,
     1.274849653e-01f,  1.316800149e-01f,  1.360030671e-01f,  1.404580277e-01f,
     1.450489216e-01f,  1.497798965e-01f,  1.546552266e-01f,  1.596793166e-01f,
     1.648567056e-01f,  1.701920711e-01f,  1.756902336e-01f,  1.813561603e-01f,
     1.871949702e-01f,  1.932119385e-01f,  1.994125013e-01f,  2.058022605e-01f,
     2.123869891e-01f,  2.191726361e-01f,  2.261653322e-01f,  2.333713949e-01f,
     2.407973346e-01f,  2.484498605e-01f,  2.563358863e-01f,  2.644625367e-01f,
     2.728371538e-01f,  2.814673039e-01f,  2.903607839e-01f,  2.995256288e-01f,
     3.089701187e-01f,  3.187027863e-01f,  3.287324247e-01f,  3.390680953e-01f,
     3.497191360e-01f,  3.606951697e-01f,  3.720061128e-01f,  3.836621843e-01f,
     3.956739150e-01f,  4.080521572e-01f,  4.208080940e-01f,  4.339532500e-01f,
     4.474995013e-01f,  4.614590865e-01f,  4.758446177e-01f,  4.906690914e-01f,
     5.059459012e-01f,  5.216888491e-01f,  5.379121581e-01f,  5.546304856e-01f,
     5.718589358e-01f,  5.896130741e-01f,  6.079089407e-01f,  6.267630651e-01f,
     6.461924814e-01f,  6.662147434e-01f,  6.868479405e-01f,  7.081107139e-01f,
     7.300222738e-01f,  7.526024164e-01f,  7.758715422e-01f,  7.998506739e-01f,
     8.245614757e-01f,  8.500262730e-01f,  8.762680723e-01f,  9.033105820e-01f,
     9.311782340e-01f,  9.598962059e-01f,  9.894904431e-01f,  1.000000745e+00f,
     1.000037649e+00f,  1.000262504e+00f,  1.000964607e+00f,  1.002570034e+00f,
     1.005639154e+00f,  1.010861180e+00f,  1.019043988e+00f,  1.031097087e+00f,
     1.048005353e+00f,  1.070791059e+00f,  1.100461817e+00f,  1.137942574e+00f,
     1.183990632e+00f,  1.239094135e+00f,  1.303356514e+00f,  1.376372085e+00f,
     1.457101344e+00f,  1.543758274e+00f,  1.633725943e+00f,  1.723520185e+00f,
     1.808823654e+00f,  1.884612937e+00f,  1.945398753e+00f,  2.000000000e+00f,
     2.000000000e+00f
};

const float kOrpheusLut4Decades[257] = {
     1.000000000e+00f,  1.036632928e+00f,  1.074607828e+00f,  1.113973860e+00f,
     1.154781985e+00f,  1.197085030e+00f,  1.240937761e+00f,  1.286396945e+00f,
     1.333521432e+00f,  1.382372227e+00f,  1.433012570e+00f,  1.485508017e+00f,
     1.539926526e+00f,  1.596338544e+00f,  1.654817100e+00f,  1.715437896e+00f,
     1.778279410e+00f,  1.843422992e+00f,  1.910952975e+00f,  1.980956779e+00f,
     2.053525026e+00f,  2.128751662e+00f,  2.206734069e+00f,  2.287573200e+00f,
     2.371373706e+00f,  2.458244069e+00f,  2.548296748e+00f,  2.641648320e+00f,
     2.738419634e+00f,  2.838735965e+00f,  2.942727176e+00f,  3.050527890e+00f,
     3.162277660e+00f,  3.278121151e+00f,  3.398208329e+00f,  3.522694651e+00f,
     3.651741273e+00f,  3.785515249e+00f,  3.924189758e+00f,  4.067944321e+00f,
     4.216965034e+00f,  4.371444813e+00f,  4.531583638e+00f,  4.697588817e+00f,
     4.869675252e+00f,  5.048065717e+00f,  5.232991147e+00f,  5.424690937e+00f,
     5.623413252e+00f,  5.829415347e+00f,  6.042963902e+00f,  6.264335367e+00f,
     6.493816316e+00f,  6.731703824e+00f,  6.978305849e+00f,  7.233941627e+00f,
     7.498942093e+00f,  7.773650302e+00f,  8.058421878e+00f,  8.353625470e+00f,
     8.659643234e+00f,  8.976871324e+00f,  9.305720409e+00f,  9.646616199e+00f,
     1.000000000e+01f,  1.036632928e+01f,  1.074607828e+01f,  1.113973860e+01f,
     1.154781985e+01f,  1.197085030e+01f,  1.240937761e+01f,  1.286396945e+01f,
     1.333521432e+01f,  1.382372227e+01f,  1.433012570e+01f,  1.485508017e+01f,
     1.539926526e+01f,  1.596338544e+01f,  1.654817100e+01f,  1.715437896e+01f,
     1.778279410e+01f,  1.843422992e+01f,  1.910952975e+01f,  1.980956779e+01f,
     2.053525026e+01f,  2.128751662e+01f,  2.206734069e+01f,  2.287573200e+01f,
     2.371373706e+01f,  2.458244069e+01f,  2.548296748e+01f,  2.641648320e+01f,
     2.738419634e+01f,  2.838735965e+01f,  2.942727176e+01f,  3.050527890e+01f,
     3.162277660e+01f,  3.278121151e+01f,  3.398208329e+01f,  3.522694651e+01f,
     3.651741273e+01f,  3.785515249e+01f,  3.924189758e+01f,  4.067944321e+01f,
     4.216965034e+01f,  4.371444813e+01f,  4.531583638e+01f,  4.697588817e+01f,
     4.869675252e+01f,  5.048065717e+01f,  5.232991147e+01f,  5.424690937e+01f,
     5.623413252e+01f,  5.829415347e+01f,  6.042963902e+01f,  6.264335367e+01f,
     6.493816316e+01f,  6.731703824e+01f,  6.978305849e+01f,  7.233941627e+01f,
     7.498942093e+01f,  7.773650302e+01f,  8.058421878e+01f,  8.353625470e+01f,
     8.659643234e+01f,  8.976871324e+01f,  9.305720409e+01f,  9.646616199e+01f,
     1.000000000e+02f,  1.036632928e+02f,  1.074607828e+02f,  1.113973860e+02f,
     1.154781985e+02f,  1.197085030e+02f,  1.240937761e+02f,  1.286396945e+02f,
     1.333521432e+02f,  1.382372227e+02f,  1.433012570e+02f,  1.485508017e+02f,
     1.539926526e+02f,  1.596338544e+02f,  1.654817100e+02f,  1.715437896e+02f,
     1.778279410e+02f,  1.843422992e+02f,  1.910952975e+02f,  1.980956779e+02f,
     2.053525026e+02f,  2.128751662e+02f,  2.206734069e+02f,  2.287573200e+02f,
     2.371373706e+02f,  2.458244069e+02f,  2.548296748e+02f,  2.641648320e+02f,
     2.738419634e+02f,  2.838735965e+02f,  2.942727176e+02f,  3.050527890e+02f,
     3.162277660e+02f,  3.278121151e+02f,  3.398208329e+02f,  3.522694651e+02f,
     3.651741273e+02f,  3.785515249e+02f,  3.924189758e+02f,  4.067944321e+02f,
     4.216965034e+02f,  4.371444813e+02f,  4.531583638e+02f,  4.697588817e+02f,
     4.869675252e+02f,  5.048065717e+02f,  5.232991147e+02f,  5.424690937e+02f,
     5.623413252e+02f,  5.829415347e+02f,  6.042963902e+02f,  6.264335367e+02f,
     6.493816316e+02f,  6.731703824e+02f,  6.978305849e+02f,  7.233941627e+02f,
     7.498942093e+02f,  7.773650302e+02f,  8.058421878e+02f,  8.353625470e+02f,
     8.659643234e+02f,  8.976871324e+02f,  9.305720409e+02f,  9.646616199e+02f,
     1.000000000e+03f,  1.036632928e+03f,  1.074607828e+03f,  1.113973860e+03f,
     1.154781985e+03f,  1.197085030e+03f,  1.240937761e+03f,  1.286396945e+03f,
     1.333521432e+03f,  1.382372227e+03f,  1.433012570e+03f,  1.485508017e+03f,
     1.539926526e+03f,  1.596338544e+03f,  1.654817100e+03f,  1.715437896e+03f,
     1.778279410e+03f,  1.843422992e+03f,  1.910952975e+03f,  1.980956779e+03f,
     2.053525026e+03f,  2.128751662e+03f,  2.206734069e+03f,  2.287573200e+03f,
     2.371373706e+03f,  2.458244069e+03f,  2.548296748e+03f,  2.641648320e+03f,
     2.738419634e+03f,  2.838735965e+03f,  2.942727176e+03f,  3.050527890e+03f,
     3.162277660e+03f,  3.278121151e+03f,  3.398208329e+03f,  3.522694651e+03f,
     3.651741273e+03f,  3.785515249e+03f,  3.924189758e+03f,  4.067944321e+03f,
     4.216965034e+03f,  4.371444813e+03f,  4.531583638e+03f,  4.697588817e+03f,
     4.869675252e+03f,  5.048065717e+03f,  5.232991147e+03f,  5.424690937e+03f,
     5.623413252e+03f,  5.829415347e+03f,  6.042963902e+03f,  6.264335367e+03f,
     6.493816316e+03f,  6.731703824e+03f,  6.978305849e+03f,  7.233941627e+03f,
     7.498942093e+03f,  7.773650302e+03f,  8.058421878e+03f,  8.353625470e+03f,
     8.659643234e+03f,  8.976871324e+03f,  9.305720409e+03f,  9.646616199e+03f,
     1.000000000e+04f
};

// ── OrpheusModalResonator ───────────────────────────────────────

void OrpheusModalResonator::init() {
    for (int i = 0; i < kOrpheusMaxModes; i++) {
        filters[i].init();
    }
    frequency = 220.0f / 48000.0f;
    structure = 0.25f;
    brightness = 0.5f;
    damping = 0.3f;
    position = 0.5f;
    resolution = kOrpheusMaxModes;
    out_odd = 0.0f;
    out_even = 0.0f;
}

void OrpheusModalResonator::reset() {
    for (int i = 0; i < kOrpheusMaxModes; i++) {
        filters[i].reset();
    }
    out_odd = 0.0f;
    out_even = 0.0f;
}

void OrpheusModalResonator::process(float input) {
    // Compute stiffness from structure parameter
    float stiffness = lut_interpolate(kOrpheusLutStiffness, structure, 256);

    float harmonic = frequency;
    float stretch_factor = 1.0f;

    // Q factor from damping (500 * exponential mapping)
    float q = 500.0f * lut_interpolate(kOrpheusLut4Decades, damping, 256);

    // Safety damping: reduce Q at high brightness/structure
    float safety = brightness * 0.4f + structure * 0.3f;
    safety = std::max(0.0f, std::min(safety, 0.7f));
    q *= (1.0f - safety);

    // Brightness attenuation: (1 - structure)^8
    float ba = 1.0f - structure;
    ba = ba * ba; // ^2
    ba = ba * ba; // ^4
    ba = ba * ba; // ^8
    float brightness_adj = brightness * (1.0f - 0.2f * ba);
    float q_loss = brightness_adj * (2.0f - brightness_adj) * 0.85f + 0.15f;
    float q_loss_damping_rate = structure * (2.0f - structure) * 0.1f;

    float q_loss_current = q_loss;

    int max_modes = std::min(kOrpheusMaxModes, resolution);
    // Ensure even for odd/even splitting
    max_modes = max_modes - (max_modes & 1);

    int num_modes = 0;

    for (int i = 0; i < max_modes; i++) {
        float partial_freq = harmonic * stretch_factor;
        if (partial_freq > 0.49f) partial_freq = 0.49f;

        if (partial_freq < 0.49f) {
            num_modes = i + 1;
        }

        filters[i].set_fq(partial_freq, 1.0f + partial_freq * q);

        stretch_factor += stiffness;
        if (stiffness < 0.0f) {
            stretch_factor = std::max(stretch_factor * 0.93f, 0.01f);
        } else {
            stretch_factor *= 0.98f;
        }

        q_loss_current += q_loss_damping_rate * (1.0f - q_loss_current);
        harmonic += frequency;
        q *= q_loss_current;
    }

    // Process through filter bank with position-based amplitude modulation
    // Scale input inversely with active mode count to normalize output energy.
    // JSyn uses 24 modes with 0.125f; 64 modes produce ~2.67x more energy.
    float mode_scale = 24.0f / static_cast<float>(std::max(num_modes, 1));
    float scaled_input = input * 0.125f * mode_scale;

    float odd = 0.0f;
    float even = 0.0f;

    float pos_phase = position * kOrpheusPiF * 2.0f;
    float amp_phase = 0.0f;
    int nm = std::max(num_modes, 1);
    float amp_increment = pos_phase / static_cast<float>(nm);

    int i = 0;
    while (i < num_modes) {
        float amp1 = std::cos(amp_phase);
        amp_phase += amp_increment;
        float amp2 = std::cos(amp_phase);
        amp_phase += amp_increment;

        odd += amp1 * filters[i].process_bp(scaled_input);
        i++;
        if (i < num_modes) {
            even += amp2 * filters[i].process_bp(scaled_input);
            i++;
        }
    }

    out_odd = soft_clip(odd);
    out_even = soft_clip(even);
}

// ── OrpheusResonatorString ──────────────────────────────────────

void OrpheusResonatorString::init(float sample_rate) {
    std::memset(delay_line, 0, sizeof(delay_line));
    write_pos = 0;
    damping_filter.init();
    dc_blocker_state = 0.0f;
    dc_blocker_coeff = 1.0f - 20.0f / sample_rate;

    frequency = 220.0f / sample_rate;
    brightness = 0.5f;
    damping = 0.3f;
    position = 0.5f;

    delay = 1.0f / frequency;
    clamped_position = 0.5f;
    out_main = 0.0f;
    out_aux = 0.0f;
}

void OrpheusResonatorString::reset() {
    std::memset(delay_line, 0, sizeof(delay_line));
    damping_filter.reset();
    dc_blocker_state = 0.0f;
    out_main = 0.0f;
    out_aux = 0.0f;
}

float OrpheusResonatorString::read_delay(float delay_samples) const {
    float read_pos_f = static_cast<float>(write_pos) - delay_samples;
    int int_read_pos = static_cast<int>(read_pos_f);
    float frac = read_pos_f - static_cast<float>(int_read_pos);

    int idx0 = ((int_read_pos % kOrpheusDelayLineSize) + kOrpheusDelayLineSize) % kOrpheusDelayLineSize;
    int idx1 = (idx0 + 1) % kOrpheusDelayLineSize;

    return delay_line[idx0] + frac * (delay_line[idx1] - delay_line[idx0]);
}

void OrpheusResonatorString::write_delay(float sample) {
    delay_line[write_pos] = sample;
    write_pos = (write_pos + 1) % kOrpheusDelayLineSize;
}

float OrpheusResonatorString::dc_block(float input) {
    float output = input - dc_blocker_state;
    dc_blocker_state = input * (1.0f - dc_blocker_coeff) + dc_blocker_state * dc_blocker_coeff;
    return output * dc_blocker_coeff;
}

void OrpheusResonatorString::process(float input, float sample_rate) {
    // Calculate delay in samples from frequency
    float delay_samples = 1.0f / frequency;
    delay_samples = std::max(4.0f, std::min(delay_samples, static_cast<float>(kOrpheusDelayLineSize - 4)));

    // Smooth delay parameter
    delay += 0.1f * (delay_samples - delay);

    // Calculate damping coefficient
    float lf_damping = damping * (2.0f - damping);
    float rt60 = 0.07f * semitones_to_ratio(lf_damping * 96.0f) * sample_rate;
    float rt60_base = std::max(-120.0f * delay / rt60, -127.0f);
    float damping_coeff = semitones_to_ratio(rt60_base);

    // Brightness affects filter cutoff
    float brightness_sq = brightness * brightness;

    // Configure the damping filter
    damping_filter.configure(damping_coeff, brightness_sq);

    // Calculate pick position for comb delay
    float pick_position = 0.5f - 0.98f * std::fabs(position - 0.5f);
    clamped_position += 0.1f * (pick_position - clamped_position);
    float comb_delay = delay * clamped_position;

    // Read from delay line (Karplus-Strong core)
    float s = read_delay(delay - 1.0f);

    // Add input excitation
    s += input;

    // Apply damping filter
    s = damping_filter.process(s);

    // DC block to prevent buildup
    s = dc_block(s);

    // Write back to delay line
    write_delay(s);

    out_main = soft_clip(s);
    out_aux = soft_clip(read_delay(comb_delay));
}

// ── OrpheusResonator (top-level combiner) ───────────────────────

void OrpheusResonator::init(float sr) {
    sample_rate = sr;
    modal.init();
    string.init(sr);
    out_l = 0.0f;
    out_r = 0.0f;
}

void OrpheusResonator::reset() {
    modal.reset();
    string.reset();
    out_l = 0.0f;
    out_r = 0.0f;
}

void OrpheusResonator::strum(float freq_hz, float sr) {
    sample_rate = sr;
    float normalized = freq_hz / sr;
    normalized = std::max(0.00001f, std::min(normalized, 0.49f));
    modal.frequency = normalized;
    string.frequency = normalized;
}

void OrpheusResonator::process(float input, int mode) {
    switch (mode) {
        case 0: // Modal
            modal.process(input);
            out_l = modal.out_odd;
            out_r = modal.out_even;
            break;
        case 1: // Sympathetic
            modal.process(input);
            string.process(modal.out_odd, sample_rate);
            out_l = string.out_main;
            out_r = modal.out_even;
            break;
        case 2: // String
            string.process(input, sample_rate);
            out_l = string.out_main;
            out_r = string.out_aux;
            break;
        default:
            modal.process(input);
            out_l = modal.out_odd;
            out_r = modal.out_even;
            break;
    }
}
