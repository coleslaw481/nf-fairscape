# letters-chain on S3

The same three steps as [`letters-chain`](../letters-chain), but the published
outputs and the RO-Crate live in an S3 bucket. MinIO serves the S3 API from a
container, so this needs no cloud account and no credits.

## Run it

```bash
docker run -d --name nf-minio -p 9000:9000 \
    -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \
    quay.io/minio/minio:latest server /data

export AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin
aws --endpoint-url http://localhost:9000 s3 mb s3://nf-prov

make install          # from the repo root
cd examples/s3-minio && nextflow run main.nf
```

Then look at what landed:

```bash
aws --endpoint-url http://localhost:9000 s3 ls --recursive s3://nf-prov/results/
```

`ro-crate-metadata.json`, `provenance-graph.{json,html}`, `ro-crate-datasheet.html`,
`ro-crate-linkml.yaml` and `ai_ready_score.json` are all written as S3 objects, with
per-file md5s and sizes read back over the S3 API.

## What this actually covers

`checksums`, `contentSizes`, `expandDirectories` and `schemas` are all on, because
those are the options that read file bytes — the paths that have to cope with a
filesystem that isn't POSIX.

The **work dir stays local**. Nextflow's `local` executor rejects an `s3://` work
dir outright (*"Local executor requires the use of POSIX compatible file system"*);
a fully remote work dir needs a cloud executor (AWS Batch, Google Batch, k8s) or
Fusion. That is a Nextflow constraint, not a plugin one — everything the plugin
itself touches here is an S3 object.

## The bug this example was written for

`CrateArtifacts.writeCrate` rewrites the crate through a sibling `.tmp` and then
renames it. S3 has no atomic rename, and nf-amazon reports that by throwing
`IllegalArgumentException` rather than the `AtomicMoveNotSupportedException` the
NIO contract specifies — so a `catch` on the documented exception never fired.

All three enrichment steps (`applyInverses`, `applyInputsOutputs`,
`buildEvidenceGraph`) failed. Because crate errors are deliberately swallowed so a
broken crate can't fail a finished workflow, **the run still reported success**
while the crate silently lost `EVI#inputs`, `EVI#outputs`, `datasetUsedBy`,
`softwareUsedBy` and `localEvidenceGraph`, and left a stray
`ro-crate-metadata.json.tmp` in the bucket.

Worth remembering when testing new environments: a green Nextflow run proves
nothing about the crate. Diff its *contents* against a known-good POSIX run.
