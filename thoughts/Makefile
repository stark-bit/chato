# =============================================================================
# WhisperIME+ Build & Deploy Makefile
# =============================================================================
#
# FIRST-TIME SETUP (one-time per phone):
#   1. Enable Developer Options:
#        Settings → About Phone → tap "Build Number" 7 times fast
#   2. Enable USB Debugging:
#        Settings → System → Developer Options → USB Debugging → ON
#   3. Plug phone via USB, pull down notification shade, select "File Transfer"
#   4. Phone will show "Allow USB debugging?" RSA dialog → tap "Allow"
#   5. Run:  make device
#      Should show your phone listed. If it says "unauthorized", unlock phone
#      and accept the dialog. If nothing shows, run:  adb kill-server && make device
#
# USAGE:
#   make                Build + install (auto-detects single device)
#   make device         List all connected devices and current DEVICE
#   make build          Build debug APK only, no install
#   make install        Install APK on device (build first if needed)
#   make uninstall      Remove app from device
#   make clean          Wipe build artifacts
#   make help           Print this help
#
# With specific device (useful when multiple phones connected):
#   make DEVICE=3B6623010MJ00000 install
#   make DEVICE=3B6623010MJ00000 run
#
# =============================================================================

# ---- Paths ----------------------------------------------------------------
SDK_DIR  := $(shell grep sdk.dir local.properties 2>/dev/null | cut -d'=' -f2)
ADB      := $(or $(shell which adb 2>/dev/null),$(SDK_DIR)/platform-tools/adb)
GRADLEW  := ./gradlew
APK      := app/build/outputs/apk/debug/app-debug.apk
PACKAGE  := org.woheller69.whisperplus

# ---- Auto-detect device ----------------------------------------------------
# Picks first online device. Override with DEVICE=XXXX on command line.
# "grep device$$" matches lines ending in "device" (not "offline"/"unauthorized")
DEVICE ?= $(shell $(ADB) devices 2>/dev/null | grep -v 'List' | grep 'device$$' | head -1 | awk '{print $$1}')

# ---- Colors ----------------------------------------------------------------
GREEN  := \033[0;32m
YELLOW := \033[0;33m
RED    := \033[0;31m
CYAN   := \033[0;36m
NC     := \033[0m # No Color

# ---- Default target --------------------------------------------------------
.DEFAULT_GOAL := help

# ---- Targets ---------------------------------------------------------------
.PHONY: help device build install uninstall clean run

help: ## Show this help message
	@echo "$(CYAN)WhisperIME+ Makefile$(NC)"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		sort | \
		awk 'BEGIN {FS = ":.*?## "}; \
			{printf "  $(GREEN)%-14s$(NC) %s\n", $$1, $$2}'
	@echo ""
	@echo "Current DEVICE: $(or $(DEVICE),$(YELLOW)none detected$(NC))"

device: ## List connected devices + serial numbers
	@echo "$(CYAN)Connected devices:$(NC)"
	@$(ADB) devices
	@echo ""
	@if [ -z "$(DEVICE)" ]; then \
		echo "$(YELLOW)No device detected. Is USB debugging enabled? Phone unlocked?$(NC)"; \
		echo "Try: adb kill-server && make device"; \
	else \
		echo "$(GREEN)Using DEVICE: $(DEVICE)$(NC)"; \
	fi

build: ## Build debug APK (assembleDebug)
	@echo "$(CYAN)Building debug APK...$(NC)"
	$(GRADLEW) assembleDebug
	@echo "$(GREEN)Built: $(APK)$(NC)"

install: ## Install debug APK on device
ifeq ($(DEVICE),)
	$(error $(RED)No device found. Run 'make device' first.$(NC))
endif
	@echo "$(CYAN)Installing to $(DEVICE)...$(NC)"
	$(ADB) -s $(DEVICE) install -r $(APK)
	@echo "$(GREEN)Installed.$(NC)"

uninstall: ## Remove app from device
ifeq ($(DEVICE),)
	$(error $(RED)No device found. Run 'make device' first.$(NC))
endif
	@echo "$(CYAN)Uninstalling from $(DEVICE)...$(NC)"
	$(ADB) -s $(DEVICE) uninstall $(PACKAGE)
	@echo "$(GREEN)Uninstalled.$(NC)"

run: build install ## Build and install (full pipeline)

clean: ## Remove build artifacts
	@echo "$(CYAN)Cleaning...$(NC)"
	$(GRADLEW) clean
	@echo "$(GREEN)Clean.$(NC)"

# ---- Future: tests ---------------------------------------------------------
# test: ## Run unit tests (JVM)
# 	$(GRADLEW) test
#
# connected-test: ## Run instrumented tests on device
# ifeq ($(DEVICE),)
# 	$(error $(RED)No device found.$(NC))
# endif
# 	$(ADB) -s $(DEVICE) $(GRADLEW) connectedAndroidTest