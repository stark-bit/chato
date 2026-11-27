# External: adb needs Developer options > USB debugging on, and the phone's "Allow USB debugging?" prompt accepted

SDK_DIR  := $(shell grep sdk.dir local.properties 2>/dev/null | cut -d'=' -f2)
ADB      := $(or $(shell which adb 2>/dev/null),$(SDK_DIR)/platform-tools/adb)
GRADLEW  := ./gradlew
APK      := app/build/outputs/apk/debug/app-debug.apk
TEST_APK := app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
PACKAGE  := dev.starkbit.chato

# External: adb lists ready phones as "device", others as "offline" or "unauthorized"
DEVICE ?= $(shell $(ADB) devices 2>/dev/null | grep -v 'List' | grep 'device$$' | head -1 | awk '{print $$1}')

GREEN  := \033[0;32m
YELLOW := \033[0;33m
RED    := \033[0;31m
CYAN   := \033[0;36m
NC     := \033[0m

.DEFAULT_GOAL := help

.PHONY: help device build install uninstall clean run test check push-parakeet bench release

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

VERSION := $(shell grep -m1 versionName app/build.gradle | sed -E 's/.*"(.*)".*/\1/')

release: ## Build signed release APK (arm64) into dist/
	$(GRADLEW) assembleRelease
	mkdir -p dist
	cp app/build/outputs/apk/release/app-release.apk dist/chato-$(VERSION).apk
	@echo "$(GREEN)dist/chato-$(VERSION).apk$(NC)"

clean: ## Remove build artifacts
	@echo "$(CYAN)Cleaning...$(NC)"
	$(GRADLEW) clean
	@echo "$(GREEN)Clean.$(NC)"

test: ## Run unit tests (JVM, no device needed)
	$(GRADLEW) testDebugUnitTest

check: ## Package boundaries, comments, complexity, copy-paste, lint and unit tests, fastest first (no device needed)
	$(GRADLEW) checkstyle
	$(GRADLEW) pmd cpd
	$(GRADLEW) lintDebug
	$(GRADLEW) testDebugUnitTest

PARAKEET_DIR ?= /mnt/Wolf_1/_stacks/parakeet/data/models
BENCH_WAVS   ?= bench
FILES_DIR    := /sdcard/Android/data/$(PACKAGE)/files

push-parakeet: ## Copy Parakeet model files to the phone (PARAKEET_DIR=...)
ifeq ($(DEVICE),)
	$(error $(RED)No device found. Run 'make device' first.$(NC))
endif
	$(ADB) -s $(DEVICE) shell mkdir -p $(FILES_DIR)/parakeet
	$(ADB) -s $(DEVICE) push $(PARAKEET_DIR)/nemo128.onnx $(PARAKEET_DIR)/encoder-model.int8.onnx \
		$(PARAKEET_DIR)/decoder_joint-model.int8.onnx $(PARAKEET_DIR)/vocab.txt $(FILES_DIR)/parakeet/
	@echo "$(GREEN)Pushed. Pick 'Local (Parakeet, experimental)' in Settings.$(NC)"

bench: ## Benchmark Parakeet vs Whisper on the phone with WAVs from BENCH_WAVS=dir
ifeq ($(DEVICE),)
	$(error $(RED)No device found. Run 'make device' first.$(NC))
endif
	@# External: gradlew connectedAndroidTest uninstalls the app afterwards, wiping its data
	$(GRADLEW) assembleDebug assembleDebugAndroidTest
	$(ADB) -s $(DEVICE) install -r $(APK)
	$(ADB) -s $(DEVICE) install -r -t $(TEST_APK)
	$(ADB) -s $(DEVICE) shell mkdir -p $(FILES_DIR)/bench
	$(ADB) -s $(DEVICE) shell rm -f $(FILES_DIR)/bench/results.txt
	$(ADB) -s $(DEVICE) push $(BENCH_WAVS)/*.wav $(FILES_DIR)/bench/
	$(ADB) -s $(DEVICE) shell am instrument -w -e class com.whisperonnx.backend.LocalEngineBenchmark \
		$(PACKAGE).test/androidx.test.runner.AndroidJUnitRunner
	@echo ""
	@$(ADB) -s $(DEVICE) shell cat $(FILES_DIR)/bench/results.txt
