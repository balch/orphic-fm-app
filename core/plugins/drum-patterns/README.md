# drum-patterns (GPL-3.0)

The MI Grids pattern ROM and the generator built on it. Grids is AVR firmware, which upstream
licenses GPL-3.0 — unlike the STM32F modules this project otherwise uses, which are MIT. That
single difference is where this repository's GPL-3.0 licence comes from.

The module is isolated so apps that ship commercially can omit it. Only `features:beats` may
depend on it. Adding an edge from anything else puts copyleft code into a binary that cannot
carry it, and the guard tests exist because that mistake is a one-line edit that compiles.
