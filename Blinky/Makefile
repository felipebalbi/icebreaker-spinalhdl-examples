TOP     = Blinky
RTL_DIR = rtl
PCF     = icebreaker.pcf

# Tools
SBT     = sbt
YOSYS   = yosys
NEXTPNR = nextpnr-ice40
ICEPACK = icepack
ICEPROG = iceprog

all: $(TOP).bin

# 1. Generate Verilog into rtl/
$(RTL_DIR)/$(TOP).v:
	$(SBT) -batch "runMain $(TOP)Verilog"

# 2. Synthesize to JSON
$(TOP).json: $(RTL_DIR)/$(TOP).v
	$(YOSYS) -p "synth_ice40 -top $(TOP) -json $@" $<

# 3. Place & route
$(TOP).asc: $(TOP).json $(PCF)
	$(NEXTPNR) \
		--up5k \
		--package sg48 \
		--pcf $(PCF) \
		--json $< \
		--asc $@

# 4. Pack bitstream
$(TOP).bin: $(TOP).asc
	$(ICEPACK) $< $@

# Flash to board
flash: $(TOP).bin
	$(ICEPROG) $<

# Cleanup
clean:
	rm -f $(RTL_DIR)/*.v *.json *.asc *.bin

.PHONY: all flash clean
