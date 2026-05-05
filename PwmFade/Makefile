PACKAGE  := pwm_fade
TOP      := PwmFadeTop
SIM_MAIN := PwmFadeSim
SRC_DIR  := src
HW_DIR   := $(SRC_DIR)/hw
SIM_DIR  := $(SRC_DIR)/sim
GEN_DIR  := gen
SIM_DIR  := simWorkspace
PCF      := icebreaker.pcf

# Tools
SBT     := sbt
YOSYS   := yosys
NEXTPNR := nextpnr-ice40
ICEPACK := icepack
ICEPROG := iceprog

# Derived files
V       := $(GEN_DIR)/$(TOP).v
JSON    := $(GEN_DIR)/$(TOP).json
ASC     := $(GEN_DIR)/$(TOP).asc
BIN     := $(GEN_DIR)/$(TOP).bin

# Source partitioning: only HW sources should retrigger Verilog regen.
HW_SRCS  := $(shell find $(HW_DIR) -name '*.scala')
SIM_SRCS := $(shell find $(SIM_DIR) -name '*.scala')

# Default target
all: $(BIN)

# Phony shortcut for "just regenerate Verilog"
verilog: $(V)

# Ensure output directory exists
$(GEN_DIR):
	mkdir -p $(GEN_DIR)

# 1. Generate Verilog (Spinal -> Verilog). Depends only on HW_SRCS so
#    editing a testbench does NOT retrigger synthesis.
$(V): $(HW_SRCS)
	$(SBT) -batch "runMain $(PACKAGE).$(TOP)Verilog"

# 2. Synthesize (Verilog -> JSON)
$(JSON): $(V)
	$(YOSYS) -p "synth_ice40 -top $(TOP) -json $@" $<

# 3. Place & route (JSON -> ASC)
$(ASC): $(JSON) $(PCF)
	$(NEXTPNR) \
		--up5k \
		--package sg48  \
		--pcf $(PCF)    \
		--json $<       \
		--asc $@        \
		--freq 12

# 4. Pack (ASC -> BIN)
$(BIN): $(ASC)
	$(ICEPACK) $< $@

# Simulation targets
sim: sim-phase sim-shaper sim-top

sim-phase:
	$(SBT) -batch "runMain $(PACKAGE).PhaseGenSim"

sim-shaper:
	$(SBT) -batch "runMain $(PACKAGE).ShaperSim"

sim-top:
	$(SBT) -batch "runMain $(PACKAGE).$(SIM_MAIN)"

# Flash
flash: $(BIN)
	$(ICEPROG) $<

# Clean
clean:
	rm -rf $(GEN_DIR) $(SIM_DIR)

help:
	@echo "Targets:"
	@echo "  all         (default) build the bitstream  -> $(BIN)"
	@echo "  verilog     regenerate Verilog only        -> $(V)"
	@echo "  sim         run all sims (phase + shaper + top)"
	@echo "  sim-phase   run PhaseGenSim"
	@echo "  sim-shaper  run ShaperSim   (LUT correctness)"
	@echo "  sim-top     run PwmFadeSim  (end-to-end, generates waves)"
	@echo "  flash       program the iCEBreaker"
	@echo "  clean       remove $(GEN_DIR) and $(SIM_DIR)"

.PHONY: all verilog sim sim-phase sim-shaper sim-top flash clean help
