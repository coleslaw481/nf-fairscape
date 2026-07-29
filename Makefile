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

# Diff the Groovy datasheet/evidence-graph port against fairscape-cli's output
# for a crate directory (needs fairscape-cli importable by python3)
CRATE ?= examples/letters-chain/results
parity:
	./tools/parity.sh $(CRATE)

# Publish the plugin
release:
	./gradlew releasePluginIfNotExists

.PHONY: assemble clean test install verify parity release
