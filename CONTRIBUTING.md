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

Full procedure, including the one-time registry claim and API token setup:
**[docs/PUBLISHING.md](./docs/PUBLISHING.md)**. Once that is done, each release is:

1. Run the gates: `make test`, `make parity-test`, `make verify`.

2. Update the [version file](./VERSION).

3. Update the [changelog](./CHANGELOG.md).

4. Run `make release` to build and publish the plugin. Registry versions are
   immutable — `releasePluginIfNotExists` skips a version that already exists, so
   bump rather than re-cut.

5. Tag the commit and make a
   [GitHub release](https://github.com/fairscape/nf-fairscape/releases).
