# Build the plugin
assemble:
	./gradlew assemble

clean:
	rm -rf .nextflow*
	rm -rf work
	rm -rf build
	./gradlew clean

# Run plugin unit tests
test:
	./gradlew test

# Install the plugin into local nextflow plugins dir
install:
	./gradlew install

# End-to-end: install, run the example pipeline, validate the emitted crate
# against the fairscape_models schema (set FAIRSCAPE_MODELS to the repo path
# if fairscape_models is not installed in your python environment).
# $(CURDIR)-anchored: the recipe cd's into nf-fairscape-test first, so a
# relative path would resolve from there and silently fall back to whatever
# fairscape_models pip happens to have installed.
FAIRSCAPE_MODELS ?= $(CURDIR)/../../../fairscape_models
verify: install
	cd nf-fairscape-test && rm -rf results work .nextflow* \
		&& nextflow run . -plugins nf-fairscape@$$(cat ../VERSION) \
		&& PYTHONPATH=$(FAIRSCAPE_MODELS) python3 validate_crate.py results/ro-crate-metadata.json

# The parity suite: every artifact the port derives, diffed against the one
# fairscape-cli derives from the same frozen crate. Needs fairscape-cli importable
# by python3 (override the invocation with FAIRSCAPE_CLI=...). FAIRSCAPE_PARITY_REQUIRED
# turns "the CLI is missing" from a skip into a failure, which is what CI wants --
# `make test` alone lets the suite skip so it stays runnable without Python.
parity-test:
	FAIRSCAPE_PARITY_REQUIRED=1 ./gradlew test --tests 'nextflow.prov.parity.*'

# Regenerate the frozen fixture crates the parity suite runs against (needs
# nextflow and an installed plugin). Only after the RENDERER's output changes.
fixtures: install
	./tools/make-parity-fixtures.sh

# Interactive form of the same comparison, pointed at ANY crate directory rather
# than the fixtures -- use it on real pipeline output, it prints the diffs.
# Defaults to a fixture so it runs on a fresh checkout; example output is
# git-ignored, so `examples/letters-chain/results` only exists after you run it.
CRATE ?= src/test/resources/parity/letters-chain
parity:
	./tools/parity.sh $(CRATE)

# Publish the plugin
release:
	./gradlew releasePluginIfNotExists

.PHONY: assemble clean test install verify parity parity-test fixtures release
