PACKAGE  := uart
TOP      := UartTxDemo
SIM_MAIN := UartTxSim
SRC_DIR  := src
GEN_DIR  := gen
SIM_DIR  := simWorkspace
PCF      := icebreaker.pcf

# Tools
SBT     := sbt
YOSYS   := yosys
NEXTPNR := nextpnr-ice40
ICEPACK := icepack
ICEPROG := iceprog
GTKWAVE := gtkwave

# Derived files
V    := $(GEN_DIR)/$(TOP).v
JSON := $(GEN_DIR)/$(TOP).json
ASC  := $(GEN_DIR)/$(TOP).asc
BIN  := $(GEN_DIR)/$(TOP).bin

# Hardware sources only -- sim sources don't affect Verilog generation,
# so excluding them prevents needless rebuilds when iterating on tests.
HW_SRCS := $(shell find $(SRC_DIR)/hw -name '*.scala')

# Default target
.DEFAULT_GOAL := all

all: $(BIN) ## Build flashable bitstream (gen/<TOP>.bin)

# Order-only prerequisite: $(GEN_DIR) must exist, but its mtime
# must NOT trigger rebuilds (that's what the | does).
$(GEN_DIR):
	mkdir -p $(GEN_DIR)

# 1. Generate Verilog (Spinal -> Verilog)
$(V): $(HW_SRCS) | $(GEN_DIR)
	$(SBT) -batch "runMain $(PACKAGE).$(TOP)Verilog"

# 2. Synthesize (Verilog -> JSON)
$(JSON): $(V)
	$(YOSYS) -p "synth_ice40 -top $(TOP) -json $@" $<

# 3. Place & route (JSON -> ASC)
$(ASC): $(JSON) $(PCF)
	$(NEXTPNR) \
		--up5k          \
		--package sg48  \
		--pcf $(PCF)    \
		--json $< \
		--asc $@  \
		--freq 12

# 4. Pack (ASC -> BIN)
$(BIN): $(ASC)
	$(ICEPACK) $< $@

sim: sim-baud sim-shiftreg sim-fsm sim-top sim-rxsync ## Run all simulations

sim-baud: ## Run BaudGenerator simulation
	$(SBT) -batch "runMain $(PACKAGE).BaudGeneratorSim"

sim-shiftreg: ## Run TxShiftReg simulation
	$(SBT) -batch "runMain $(PACKAGE).TxShiftRegSim"

sim-fsm: ## Run TxFsm simulation
	$(SBT) -batch "runMain $(PACKAGE).TxFsmSim"

sim-top: ## Run UartTx (top-level) simulation
	$(SBT) -batch "runMain $(PACKAGE).$(SIM_MAIN)"

sim-rxsync: ## Run RxSync simulation
	$(SBT) -batch "runMain $(PACKAGE).RxSyncSim"

waves: ## Open the most recent simulation VCD in gtkwave
	@vcd=$$(find $(SIM_DIR) -name '*.vcd' -printf '%T@ %p\n' 2>/dev/null \
		| sort -nr | head -1 | cut -d' ' -f2-); \
	if [ -z "$$vcd" ]; then \
		echo "no .vcd found under $(SIM_DIR)/ -- run a sim first"; \
		exit 1; \
	fi; \
	echo "opening $$vcd"; \
	$(GTKWAVE) "$$vcd" &

flash: $(BIN) ## Flash bitstream to the iCEbreaker
	$(ICEPROG) $<

clean: ## Remove generated artefacts (gen/, simWorkspace/)
	rm -rf $(GEN_DIR) $(SIM_DIR)

distclean: clean ## clean + nuke sbt build state (target/, project/target/)
	rm -rf target project/target project/project

help: ## Show this help (every target with a ## description)
	@awk 'BEGIN { FS = ":.*## "; printf "Available targets:\n\n" } \
		/^[a-zA-Z0-9_.-]+:.*## / { printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2 }' \
		$(MAKEFILE_LIST)

.PHONY: all sim sim-baud sim-shiftreg sim-fsm sim-top sim-rxsync waves flash clean distclean help
