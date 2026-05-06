PACKAGE := uart_tx
TOP     := UartTx
SIM_MAIN := UartTxSim
SRC_DIR := src
GEN_DIR := gen
SIM_DIR := simWorkspace
PCF     := icebreaker.pcf

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

# Find all scala sources
SCALA_SRCS := $(shell find $(SRC_DIR) -name '*.scala')

# Default target
all: $(BIN)

# Ensure output directory exists
$(GEN_DIR):
	mkdir -p $(GEN_DIR)

# 1. Generate Verilog (Spinal → Verilog)
$(V): $(SCALA_SRCS)
	$(SBT) -batch "runMain $(PACKAGE).$(TOP)Verilog"

# 2. Synthesize (Verilog → JSON)
$(JSON): $(V)
	$(YOSYS) -p "synth_ice40 -top $(TOP) -json $@" $<

# 3. Place & route (JSON → ASC)
$(ASC): $(JSON) $(PCF)
	$(NEXTPNR) \
		--up5k \
		--package sg48  \
		--pcf $(PCF)    \
		--json $<       \
		--asc $@        \
		--freq 12       \

# 4. Pack (ASC → BIN)
$(BIN): $(ASC)
	$(ICEPACK) $< $@

sim: sim-baud

sim-baud:
	$(SBT) -batch "runMain $(PACKAGE).BaudGeneratorSim"

sim-top:
	$(SBT) -batch "runMain $(PACKAGE).$(SIM_MAIN)"

# Flash
flash: $(BIN)
	$(ICEPROG) $<

# Clean
clean:
	rm -rf $(GEN_DIR) $(SIM_DIR)

.PHONY: all flash clean
