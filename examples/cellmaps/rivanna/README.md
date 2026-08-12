# Cell Maps on UVA Rivanna (clarklab)

Everything lives under `/project/clarklab/nf/cellmap`:

```
env/            conda env (setup.sh)
src/            six cellmaps_* checkouts, pinned commits
data/           cached proteinatlas.xml.gz
work/           Nextflow work dir — all intermediates
results/        published outputs + RO-Crate
```

## 1. Copy up

Zip the directory above and upload the zipped `cellmaps-rivanna.tar.gz` to `/project/clarklab/nf/cellmap` (OOD Files,
no VPN: https://ood.hpc.virginia.edu). Then in a shell:

```bash
cd /project/clarklab/nf/cellmap
tar -xzf cellmaps-rivanna.tar.gz
mkdir -p ~/.nextflow/plugins
mv plugins/nf-fairscape-0.1.0 ~/.nextflow/plugins/
```

## 2. Build the env

On a compute node — it compiles wheels:

```bash
salloc -A clarklab -p standard -c 4 --mem=16G -t 2:00:00
bash /project/clarklab/nf/cellmap/cellmaps/rivanna/setup.sh
exit
```

## 3. Cache the atlas

Saves a 697 MB download per run:

```bash
mkdir -p /project/clarklab/nf/cellmap/data
curl -L -o /project/clarklab/nf/cellmap/data/proteinatlas.xml.gz \
    https://www.proteinatlas.org/download/proteinatlas.xml.gz
```

## 4. Run

```bash
cd /project/clarklab/nf/cellmap/cellmaps
sbatch rivanna/run.slurm smoke     # minutes, fake images
sbatch rivanna/run.slurm           # full
```

```bash
squeue -u $USER
tail -f cellmaps-nf-*.log
```

## 5. Check

- `grep -i 'prov\|fairscape' .nextflow.log` — crate failures are swallowed by
  design, so a green run can still hide a broken crate.
- `results/` has `ro-crate-metadata.json`, `provenance-graph.{json,html}`,
  `ro-crate-datasheet.html`, `ai_ready_score.json`, `ro-crate-linkml.yaml`.
- `contentUrl`s crate-relative, not absolute `/project/...`.
- Re-run: `-resume` should reuse every task and mint identical ARKs.

## Notes

- `-A clarklab` is in `run.slurm` and `rivanna.config`.
- Head job walltime (2 days) caps the whole pipeline.
- `nextflow.config` is never edited; `rivanna.config` layers on with `-c`.
