#!/usr/bin/env python3
"""Check the evidence graph against the run it claims to describe.

`validate_crate.py` answers "is this a valid EVI RO-Crate". `check-crate.py` answers
"does it have the properties a real nf-core crate needs". Neither one looks at what
actually ran -- a crate that describes a *different* workflow, perfectly, passes both.

This rebuilds the run's provenance from Nextflow's own artifacts, which the plugin does
not write and cannot influence after the fact, and diffs the two:

  pipeline_info/execution_trace_*.txt   the task list, with hashes
  work/<hash>/.command.sh               what each task ran
  work/<hash>/.command.run  nxf_stage() what each task consumed, by absolute path
  work/<hash>/  (leftover files)        what each task produced
  work/<hash>/.exitcode                 whether it succeeded

Four join keys make the diff exact, so nothing here is a heuristic:

  Computation.identifier  == the task hash          (which crate node is which task)
  Dataset.md5             == md5 of the bytes       (which crate node is which file)
  Dataset.contentUrl      -> a path on disk         (does the file exist, and match)
  Computation.command     == .command.sh            (did it run what the crate says)

Two things to know about the ground truth before trusting a failure:

  * This CANNOT see whether a published file is a real file or a symlink into work/.
    Both sit at a path under the crate root, so both get a crate-relative contentUrl and
    both resolve here. Only `publishDir mode: 'copy'` makes the bytes travel with the
    crate -- Nextflow's default is `symlink`. See "Precondition" in docs/FAIRSCAPE.md.

  * Under `-resume`, a CACHED task's `.command.sh` is the one the ORIGINAL session wrote,
    while the crate's `command` is re-rendered now. If an upstream channel collects in a
    nondeterministic order, the two disagree on argument order for the same file set --
    real, reported, and a property of the pipeline rather than of the crate. Re-run
    without `-resume` to tell that apart from a command the plugin got wrong.

An edge the crate asserts that the run does not support is a FAIL -- the crate is
describing something that did not happen. An edge the run has that the crate omits is
reported but not failed: unpublished intermediates are legitimately out of scope, and
the counts tell you how much of the run made it into the graph.

Usage:  verify-against-run.py <run dir> [...]     (dir holding results/ and work/)
Exit:   0 when every run passes, 1 otherwise.
"""
import glob
import hashlib
import json
import os
import re
import shlex
import sys
from collections import defaultdict

# A CACHED task is one that ran in an earlier session and whose outputs Nextflow restored;
# the plugin includes it deliberately (onTaskCached), so its provenance edges are real and
# a -resume run is still fully checkable. Only FAILED/ABORTED mean nothing was produced.
SUCCEEDED = ("COMPLETED", "CACHED")

# hashing the same 24 MB GTF once per task it is staged into is the whole runtime
_MD5 = {}


def md5(path):
    real = os.path.realpath(path)
    if real not in _MD5:
        digest = hashlib.md5()
        try:
            with open(real, "rb") as handle:
                for chunk in iter(lambda: handle.read(1 << 20), b""):
                    digest.update(chunk)
        except OSError:
            return None
        _MD5[real] = digest.hexdigest()
    return _MD5[real]


# ---------------------------------------------------------------- ground truth


def read_trace(run_dir):
    """One row per task from Nextflow's execution trace: hash prefix -> record."""
    traces = sorted(glob.glob(os.path.join(run_dir, "results/pipeline_info/execution_trace_*.txt")))
    if not traces:
        return {}
    with open(traces[-1]) as handle:
        header = handle.readline().rstrip("\n").split("\t")
        rows = [dict(zip(header, line.rstrip("\n").split("\t"))) for line in handle if line.strip()]
    return {row["hash"]: row for row in rows}


def stage_inputs(command_run):
    """Absolute source paths from the task's nxf_stage() block.

    This is the authoritative input list: Nextflow generated it from the resolved
    task inputs, and the shell literally executed it. Handles the symlink strategy
    (`ln -s`) and the copy one (`cp`), staged flat or into subdirectories.
    """
    try:
        with open(command_run) as handle:
            body = handle.read()
    except OSError:
        return []
    match = re.search(r"^nxf_stage\(\) \{\n(.*?)^\}", body, re.S | re.M)
    if not match:
        return []
    sources = []
    for line in match.group(1).splitlines():
        # staging into a subdirectory is one compound line:
        #   mkdir -p inputs && ln -s /abs/src inputs/name
        for clause in line.split("&&"):
            try:
                parts = shlex.split(clause.strip())
            except ValueError:
                continue
            if not parts:
                continue
            if parts[0] == "ln" and len(parts) >= 4 and parts[1] == "-s":
                sources.append(parts[2])
            elif parts[0] == "cp" and len(parts) >= 3:
                sources.append(parts[-2])
    return [s for s in sources if s.startswith("/")]


def task_outputs(work_dir, staged):
    """Files left in the work directory that the task itself created.

    Everything that is not a Nextflow control file and not something staged in.
    Directories are walked: GSEA and MultiQC both publish whole trees.
    """
    staged_names = {os.path.basename(s) for s in staged}
    produced = []
    for root, dirs, files in os.walk(work_dir):
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        for name in files:
            if name.startswith(".command") or name == ".exitcode":
                continue
            path = os.path.join(root, name)
            # a staged input is a symlink out of the tree; a task's own output is not
            if os.path.islink(path) or name in staged_names:
                continue
            produced.append(path)
    return produced


def read_run(run_dir):
    """Everything Nextflow itself recorded, keyed by full task hash."""
    trace = read_trace(run_dir)
    work = os.path.join(run_dir, "work")
    tasks = {}
    for prefix, row in trace.items():
        matches = glob.glob(os.path.join(work, prefix + "*"))
        if not matches:
            tasks[prefix] = {"trace": row, "work_dir": None}
            continue
        work_dir = matches[0]
        full_hash = "".join(os.path.relpath(work_dir, work).split(os.sep))
        staged = stage_inputs(os.path.join(work_dir, ".command.run"))
        command_sh = os.path.join(work_dir, ".command.sh")
        script = open(command_sh).read() if os.path.exists(command_sh) else ""
        exitcode = os.path.join(work_dir, ".exitcode")
        with open(os.path.join(work_dir, ".command.run")) as handle:
            run_body = handle.read()
        image = re.search(r"(?:docker|podman) run .*?\s(\S+)\s+/bin/(?:bash|sh)", run_body)
        tasks[full_hash] = {
            "trace": row,
            "work_dir": work_dir,
            "inputs": staged,
            "outputs": task_outputs(work_dir, staged),
            "script": script,
            "image": image.group(1) if image else None,
            "exitcode": open(exitcode).read().strip() if os.path.exists(exitcode) else None,
        }
    return tasks


# ---------------------------------------------------------------------- crate


def type_of(node):
    raw = node.get("@type")
    types = [str(t) for t in (raw if isinstance(raw, list) else [raw])]
    for candidate in ("ROCrate", "Container", "Schema", "Computation", "Software", "Dataset"):
        if any(t.rsplit("#", 1)[-1].rsplit(":", 1)[-1] == candidate for t in types):
            return candidate
    return types[-1] if types else ""


def as_list(value):
    return [] if value is None else (value if isinstance(value, list) else [value])


def refs(node, field):
    return [r["@id"] for r in as_list(node.get(field)) if isinstance(r, dict) and r.get("@id")]


def normalise(script):
    """A shell script as its non-blank lines, stripped -- indentation carries no meaning
    between the authored template and the rendered .command.sh."""
    body = re.sub(r"\A#!.*\n", "", script)
    return [line.strip() for line in body.splitlines() if line.strip()]


def contains_block(haystack, needle):
    """Is `needle` a contiguous run of lines inside `haystack`?"""
    if not needle:
        return True
    for start in range(len(haystack) - len(needle) + 1):
        if haystack[start:start + len(needle)] == needle:
            return True
    return False


def resolve(node, crate_dir, run_dir):
    """The file a Dataset points at, and how it said so.

    The two locators carry different promises, so a miss means different things:

      contentUrl, absolute (http, s3, …)  somewhere else; nothing to open, not a defect
      contentUrl, relative               MUST be at that path under the crate root --
                                         RO-Crate resolves it there, so a miss is a FAIL
      localPath                          was at that path under the run dir when it ran,
                                         with no claim it still is -- a miss is a note
    """
    url = node.get("contentUrl")
    if url and re.match(r"^[a-z][a-z0-9+.-]*://", str(url)):
        return None, "remote"
    if url:
        path = os.path.join(crate_dir, url)
        return (path, "crate") if os.path.exists(path) else (None, "broken")
    local = node.get("localPath")
    if local:
        path = os.path.join(run_dir, local)
        return (path, "local") if os.path.exists(path) else (None, "gone")
    return None, None


# --------------------------------------------------------------------- checks


def verify(run_dir):
    crate_dir = os.path.join(run_dir, "results")
    crate_path = os.path.join(crate_dir, "ro-crate-metadata.json")
    problems, notes = [], []
    if not os.path.exists(crate_path):
        return [f"no crate at {crate_path}"], []

    graph = json.load(open(crate_path))["@graph"]
    index = {e["@id"]: e for e in graph if "@id" in e}
    datasets = {i: e for i, e in index.items() if type_of(e) == "Dataset"}
    computations = {i: e for i, e in index.items() if type_of(e) == "Computation"}
    tasks = read_run(run_dir)
    if not tasks:
        return ["no execution trace under results/pipeline_info -- nothing to check against"], []

    # ---- 1. every Dataset points at a file that exists, with the bytes it claims
    unresolved, bad_md5, bad_size, roots = [], [], [], defaultdict(int)
    dataset_file = {}
    for node_id, node in datasets.items():
        path, root = resolve(node, crate_dir, run_dir)
        roots[root] += 1
        if path is None:
            # only a relative contentUrl promised the file would be there
            if root == "broken":
                unresolved.append(node.get("contentUrl"))
            continue
        dataset_file[node_id] = path
        # MultiQC and lastdb publish whole directories; the crate sizes those by summing
        # what is inside, which is the only figure that means anything for a directory
        if os.path.isdir(path):
            actual = sum(os.path.getsize(os.path.join(r, f))
                         for r, _, fs in os.walk(path) for f in fs)
        else:
            actual = os.path.getsize(path)
            if node.get("md5") and md5(path) != node["md5"]:
                bad_md5.append(node.get("name"))
        if node.get("contentSize") and str(actual) != str(node["contentSize"]):
            bad_size.append(f"{node.get('name')} (says {node['contentSize']}, is {actual})")
    notes.append(f"{len(datasets)} Datasets: {roots['crate']} in the crate, "
                 f"{roots['local']} via localPath, {roots['remote']} remote"
                 + (f", {roots['gone']} localPath since deleted" if roots["gone"] else ""))
    if unresolved:
        problems.append(f"{len(unresolved)} Dataset(s) claim a crate-relative contentUrl for a "
                        f"file that is not in the crate, e.g. {unresolved[0]!r}")
    if bad_md5:
        problems.append(f"{len(bad_md5)} Dataset(s) declare an md5 the file does not have: "
                        f"{', '.join(bad_md5[:3])}")
    if bad_size:
        problems.append(f"{len(bad_size)} Dataset(s) declare the wrong contentSize: "
                        f"{', '.join(bad_size[:3])}")

    # md5 is the content-addressed join: a file published to results/ under a new path
    # is still the same bytes the task wrote in work/
    dataset_md5 = {i: (n.get("md5") or md5(dataset_file[i]) if i in dataset_file else n.get("md5"))
                   for i, n in datasets.items()}
    by_md5 = defaultdict(set)
    for node_id, digest in dataset_md5.items():
        if digest:
            by_md5[digest].add(node_id)

    # ---- 2. task coverage: the crate's Computations are exactly the run's tasks
    by_identifier = {c.get("identifier"): c for c in computations.values() if c.get("identifier")}
    root_comp = next((c for c in computations.values() if not c.get("isPartOf")), None)
    missing = [h for h in tasks if h not in by_identifier]
    extra = [c.get("name") for i, c in computations.items()
             if c is not root_comp and c.get("identifier") not in tasks]
    succeeded = [h for h, t in tasks.items() if t["trace"].get("status") in SUCCEEDED]
    notes.append(f"{len(tasks)} tasks in the trace, {len(computations) - 1} task Computations "
                 f"+ 1 run Computation")
    if missing:
        named = ", ".join(tasks[h]["trace"]["name"].split(":")[-1] for h in missing[:3])
        problems.append(f"{len(missing)} task(s) ran and are not in the crate: {named}")
    if extra:
        problems.append(f"{len(extra)} Computation(s) match no task that ran: {extra[:3]}")

    # ---- 3. per task: command, container, and the edges
    bad_command, bad_image, fabricated_in, fabricated_out = [], [], [], []
    covered_in = covered_out = total_in = total_out = 0
    broken_chain = []
    for full_hash, task in tasks.items():
        comp = by_identifier.get(full_hash)
        if comp is None or task["work_dir"] is None:
            continue
        short = task["trace"]["name"].split(":")[-1]

        if task["script"] and comp.get("command"):
            # The crate holds the process script as authored in the module, keeping its
            # indentation; .command.sh is the rendered form, dedented, with a shebang in
            # front and Nextflow's env-capture epilogue behind. Same commands either way,
            # so compare the stripped lines and require them contiguous and in order.
            ran, claimed = normalise(task["script"]), normalise(comp["command"])
            if not contains_block(ran, claimed):
                differing = next((c for c in claimed if c not in ran), "")
                bad_command.append(f"{short} ({differing[:70]}…)" if differing else short)
        if task["image"] and comp.get("containerImage") and task["image"] != comp["containerImage"]:
            bad_image.append(f"{short}: crate says {comp['containerImage']}, ran {task['image']}")

        # inputs -- what the shell staged, by content
        gt_in = {md5(p) for p in task["inputs"] if os.path.exists(p)}
        gt_in.discard(None)
        crate_in = {dataset_md5.get(r) for r in refs(comp, "usedDataset")} - {None}
        total_in += len(gt_in)
        covered_in += len(gt_in & crate_in)
        for orphan in crate_in - gt_in:
            name = next((datasets[i].get("name") for i in by_md5[orphan]), orphan)
            fabricated_in.append(f"{short} <- {name}")

        # outputs -- what the task left behind, by content
        gt_out = {md5(p) for p in task["outputs"]} - {None}
        crate_out = {dataset_md5.get(r) for r in refs(comp, "generated")} - {None}
        total_out += len(gt_out)
        covered_out += len(gt_out & crate_out)
        for orphan in crate_out - gt_out:
            name = next((datasets[i].get("name") for i in by_md5[orphan]), orphan)
            fabricated_out.append(f"{short} -> {name}")

        # derivation chain -- an input staged out of another task's work directory
        # must be attributed, in the crate, to that task
        for source in task["inputs"]:
            producer = re.search(r"/work/([0-9a-f]{2})/([0-9a-f]{30,})(?:/|$)", source)
            if not producer:
                continue
            producer_hash = producer.group(1) + producer.group(2)
            if producer_hash not in by_identifier:
                continue
            digest = md5(source)
            nodes = by_md5.get(digest, set())
            if not nodes:
                continue  # the crate does not carry this intermediate at all
            producer_id = by_identifier[producer_hash]["@id"]
            if not any(producer_id in refs(datasets[n], "generatedBy") for n in nodes):
                upstream = tasks[producer_hash]["trace"]["name"].split(":")[-1]
                broken_chain.append(f"{short} used {os.path.basename(source)} from {upstream}, "
                                    f"crate does not say {upstream} generated it")

    notes.append(f"edges: {covered_in}/{total_in} staged inputs and {covered_out}/{total_out} "
                 f"produced files are in the graph "
                 f"({total_in - covered_in} + {total_out - covered_out} unpublished intermediates "
                 f"not carried)")
    if bad_command:
        problems.append(f"{len(bad_command)} Computation(s) carry a command the task did not run: "
                        f"{', '.join(bad_command[:3])}")
    if bad_image:
        problems.append(f"{len(bad_image)} container mismatch(es): {bad_image[0]}")
    if fabricated_in:
        problems.append(f"{len(fabricated_in)} usedDataset edge(s) the run does not support: "
                        f"{'; '.join(fabricated_in[:3])}")
    if fabricated_out:
        problems.append(f"{len(fabricated_out)} generated edge(s) the run does not support: "
                        f"{'; '.join(fabricated_out[:3])}")
    if broken_chain:
        problems.append(f"{len(broken_chain)} derivation(s) the graph breaks: {broken_chain[0]}")

    # ---- 4. failed tasks must not be presented as having produced anything
    for full_hash, task in tasks.items():
        if task["trace"].get("status") in SUCCEEDED:
            continue
        comp = by_identifier.get(full_hash)
        if comp is not None and refs(comp, "generated"):
            problems.append(f"task {task['trace']['name']} is {task['trace']['status']} but the "
                            f"crate says it generated {len(refs(comp, 'generated'))} file(s)")
    cached = sum(1 for t in tasks.values() if t["trace"].get("status") == "CACHED")
    notes.append(f"{len(succeeded)}/{len(tasks)} tasks succeeded"
                 + (f" ({cached} restored from cache by -resume)" if cached else ""))

    return problems, notes


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    failed = False
    for run_dir in sys.argv[1:]:
        print(f"{run_dir}:")
        problems, notes = verify(run_dir.rstrip("/"))
        for note in notes:
            print(f"  {note}")
        for problem in problems:
            print(f"  FAIL: {problem}")
        if problems:
            failed = True
        else:
            print("  OK: every task, command, container and edge is backed by the run")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
