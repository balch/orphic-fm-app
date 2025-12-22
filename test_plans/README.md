# Songe-8 Test Plans

This directory contains comprehensive test plans for all major features of the Songe-8 synthesizer.

## Test Plan Index

1. **[Mod Delay Test Plan](mod_delay_test_plan.md)** - Dual delay system with LFO/self modulation
2. **[HyperLFO Test Plan](hyper_lfo_test_plan.md)** - Dual oscillator LFO with AND/OR logic
3. **[Voice & FM Synthesis Test Plan](voice_fm_test_plan.md)** - 8-voice FM synthesis system
4. **[Distortion & Dynamics Test Plan](distortion_test_plan.md)** - Drive, limiter, and mix controls
5. **[Global Controls Test Plan](global_controls_test_plan.md)** - Cross-modulation, feedback, vibrato, coupling
6. **[MIDI Integration Test Plan](midi_test_plan.md)** - MIDI mapping and learn mode
7. **[Preset System Test Plan](preset_test_plan.md)** - Preset management and persistence
8. **[Integration Test Plan](integration_test_plan.md)** - Full system testing scenarios

## Testing Philosophy

- **Start Simple:** Begin with basic functionality before testing complex interactions
- **Listen Actively:** Many bugs manifest as audio artifacts that meters won't catch
- **Edge Cases:** Always test minimum, maximum, and mid-range values
- **Real-World Usage:** Test realistic musical scenarios, not just technical extremes
- **Cross-Platform:** JVM and Android implementations may behave differently

## Quick Start

For initial testing, we recommend this order:
1. Voice & FM Synthesis (basic sound generation)
2. Distortion & Dynamics (output stage)
3. HyperLFO (modulation source)
4. Mod Delay (time-based effects)
5. Global Controls (advanced features)
6. MIDI Integration (if MIDI controller available)
7. Preset System (workflow testing)
8. Integration (full system scenarios)

## Bug Reporting

When reporting issues, please include:
- Platform (JVM/Android)
- Steps to reproduce
- Expected behavior
- Actual behavior
- Audio recordings if possible
- Parameter values at time of issue
