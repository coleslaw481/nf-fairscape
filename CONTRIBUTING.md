# nf-fairscape

Contributions are welcome. Fork [this repository](https://github.com/fairscape/nf-fairscape) and open a pull request to propose changes. Consider submitting an [issue](https://github.com/fairscape/nf-fairscape/issues/new) to discuss any proposed changes with the maintainers before submitting a pull request.

## Development

Build and install the plugin to your local Nextflow installation:

```bash
make install
```

Run with Nextflow as usual:

```bash
nextflow run nf-fairscape-test -plugins nf-fairscape@<version>
```

## Publishing

The plugin is published to the [Nextflow plugin registry](https://registry.nextflow.io).
Publishing rights are a one-time ownership claim on the plugin name, plus an API token
in `$HOME/.gradle/gradle.properties` as `npr.apiKey=<api-key>`. After that, each release
is:

1. Run the gates: `make test`, `make parity-test`, `make verify`.

2. Update the [version file](./VERSION).

3. Run `make release` to build and publish the plugin. Registry versions are
   immutable — `releasePluginIfNotExists` skips a version that already exists, so
   bump rather than re-cut.

4. Tag the commit and make a
   [GitHub release](https://github.com/fairscape/nf-fairscape/releases).
