# letters-chain on Kubernetes

The same three steps as [`letters-chain`](../letters-chain), but every task runs
as a Kubernetes pod. [kind](https://kind.sigs.k8s.io) runs the cluster inside
Docker, so this needs no cloud account.

## The path trick

Nextflow's `k8s` executor runs the head process **outside** the cluster while the
tasks run **inside** it. The head is what writes the crate, so both have to agree
on where files live.

`kind-cluster.yaml` mounts *this directory* into the kind node at the same
absolute path it has on the host, and `k8s-storage.yaml` puts a `hostPath` PV over
it. So `results/` means the same thing to the head and to every pod. Everything in
`nextflow.config` hangs off `projectDir`, which means the absolute path appears in
exactly one place — the kind config — and nothing has to be edited to run this
somewhere else.

Without that, the head writes the crate to a host path the pods never populated,
or to a pod path the host cannot see.

## Run it

```bash
make install                       # from the repo root
cd examples/k8s-kind

kind create cluster --name nf-prov --config kind-cluster.yaml
kubectl apply -f k8s-storage.yaml

nextflow run main.nf
ls results/
```

Tear down with `kind delete cluster --name nf-prov`.

## Pods must not run as root

The volume is a host directory, so anything a pod writes lands in this repo with
the pod's ownership. Pods default to root, which leaves a `work/` tree you need
`sudo` to delete. `nextflow.config` therefore sets a `securityContext` from the
invoking user's own uid/gid:

```groovy
securityContext = [
    runAsUser : 'id -u'.execute().text.trim() as Integer,
    runAsGroup: 'id -g'.execute().text.trim() as Integer,
    fsGroup   : 'id -g'.execute().text.trim() as Integer
]
```

If you drop that, clean up with `docker exec nf-prov-control-plane rm -rf <path>`
— the node container is root and shares the mount.

## What it showed

Crate parity against a local POSIX run: same 15 entities, identical md5s,
`EVI#outputs` and `localEvidenceGraph` present, no stray `.tmp`.

One genuine difference, and it is the right one — the k8s crate carries a
`containerImage` property the POSIX run has no reason to:

```
MAKE_LIST      debian:stable-slim
REVERSE        debian:stable-slim
SPLIT_HALVES   debian:stable-slim
```

Because every k8s task must name a container, the plugin records the image on each
process Software entity with no extra configuration.

## What it does not cover

The work dir here is a shared POSIX volume reached through a PVC. A work dir on
**object storage** (`s3://`, `gs://`) is a different code path, and the `local`
executor refuses it outright — that needs AWS Batch, Google Batch, or Fusion. See
[`s3-minio`](../s3-minio) for the object-store side, which covers the crate and the
published outputs but still keeps the work dir local.
