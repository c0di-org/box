#!/usr/bin/env python3
"""Resolve Maven coordinates and download the artifacts, without Gradle.

This is the piece that separates "can build an app" from "can build a real app":
AndroidX and Material ship as AARs on Maven, and using them means walking a
transitive dependency graph, resolving versions, and unpacking archives that the
Android Gradle Plugin would normally handle.

    maven.py androidx.appcompat:appcompat:1.6.1 [more...] --out libs

Writes the resolved artifacts into --out and prints a JSON manifest describing
them, which build.sh consumes.

Deliberately not implemented: <scope>import</scope> BOMs, classifiers, snapshot
versions, and range versions. None appear in the AndroidX graphs this targets,
and guessing at them silently would be worse than refusing.
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

REPOS = [
    "https://dl.google.com/dl/android/maven2",   # AndroidX, Material, play-services
    "https://repo1.maven.org/maven2",            # everything else
]
M2 = "{http://maven.apache.org/POM/4.0.0}"
CACHE = os.environ.get("MAVEN_CACHE", "/workspace/android/.m2")


# --------------------------------------------------------------------------
# fetching
# --------------------------------------------------------------------------

def fetch(path):
    """Return bytes for a repo-relative path, trying each repo in order."""
    cached = os.path.join(CACHE, path)
    if os.path.exists(cached):
        with open(cached, "rb") as f:
            return f.read()

    last = None
    for repo in REPOS:
        url = f"{repo}/{path}"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "box-maven/1"})
            with urllib.request.urlopen(req, timeout=90) as r:
                data = r.read()
            os.makedirs(os.path.dirname(cached), exist_ok=True)
            with open(cached, "wb") as f:
                f.write(data)
            return data
        except urllib.error.HTTPError as e:
            if e.code != 404:
                last = e
        except Exception as e:                                  # noqa: BLE001
            last = e
    raise LookupError(f"not found in any repo: {path}" + (f" ({last})" if last else ""))


def coord_path(group, artifact, version, ext):
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.{ext}"


# --------------------------------------------------------------------------
# version comparison - highest wins, as Gradle does
# --------------------------------------------------------------------------

_PRE = {"alpha": 0, "beta": 1, "rc": 2, "snapshot": -1}


def normalize_version(v):
    """Maven version ranges. AndroidX pins hard versions as `[1.6.1]`, which is
    a range meaning 'exactly this'. Unwrap those; refuse open ranges rather than
    guess a version and silently build against the wrong thing."""
    if not v:
        return v
    v = v.strip()
    if v.startswith(("[", "(")):
        inner = v[1:-1]
        if "," not in inner and v.startswith("[") and v.endswith("]"):
            return inner.strip()
        raise ValueError(f"open version range not supported: {v}")
    return v


def version_key(v):
    """Sort key: (numeric parts..., stage, stage number). Releases beat pre-releases."""
    main = re.split(r"[-+]", v, 1)
    nums = [int(x) if x.isdigit() else 0 for x in main[0].split(".")]
    nums += [0] * (4 - len(nums))
    stage, stage_n = 3, 0                      # 3 == final release
    if len(main) > 1:
        tail = main[1].lower()
        for name, rank in _PRE.items():
            if tail.startswith(name):
                stage = rank
                digits = re.search(r"\d+", tail)
                stage_n = int(digits.group()) if digits else 0
                break
    return (*nums[:4], stage, stage_n)


# --------------------------------------------------------------------------
# POM parsing
# --------------------------------------------------------------------------

class Pom:
    def __init__(self, group, artifact, version):
        self.group, self.artifact, self.version = group, artifact, version
        self.packaging = "jar"
        self.props = {}
        self.managed = {}          # (g, a) -> version
        self.deps = []             # (g, a, version_or_None)
        self._parse()

    def _text(self, node, tag):
        el = node.find(M2 + tag)
        return el.text.strip() if el is not None and el.text else None

    def _parse(self):
        raw = fetch(coord_path(self.group, self.artifact, self.version, "pom"))
        root = ET.fromstring(raw)

        # inherit from parent first, so our own values override
        parent = root.find(M2 + "parent")
        if parent is not None:
            p = Pom(self._text(parent, "groupId"),
                    self._text(parent, "artifactId"),
                    self._text(parent, "version"))
            self.props.update(p.props)
            self.managed.update(p.managed)

        self.props.setdefault("project.version", self.version)
        self.props.setdefault("project.groupId", self.group)
        props = root.find(M2 + "properties")
        if props is not None:
            for el in props:
                self.props[el.tag.replace(M2, "")] = (el.text or "").strip()

        self.packaging = self._text(root, "packaging") or "jar"

        dm = root.find(f"{M2}dependencyManagement/{M2}dependencies")
        if dm is not None:
            for d in dm.findall(M2 + "dependency"):
                g = self.expand(self._text(d, "groupId"))
                a = self._text(d, "artifactId")
                try:
                    v = normalize_version(self.expand(self._text(d, "version")))
                except ValueError:
                    continue
                if g and a and v:
                    self.managed[(g, a)] = v

        deps = root.find(M2 + "dependencies")
        if deps is not None:
            for d in deps.findall(M2 + "dependency"):
                scope = self._text(d, "scope") or "compile"
                optional = (self._text(d, "optional") or "false").lower() == "true"
                if scope not in ("compile", "runtime") or optional:
                    continue
                g = self.expand(self._text(d, "groupId"))
                a = self._text(d, "artifactId")
                try:
                    v = normalize_version(self.expand(self._text(d, "version")))
                except ValueError:
                    continue
                if g and a:
                    self.deps.append((g, a, v))

    def expand(self, value):
        """Substitute ${...} placeholders, a few passes deep."""
        if not value:
            return value
        for _ in range(5):
            m = re.findall(r"\$\{([^}]+)\}", value)
            if not m:
                break
            for key in m:
                value = value.replace("${" + key + "}", self.props.get(key, ""))
        return value.strip()


# --------------------------------------------------------------------------
# resolution
# --------------------------------------------------------------------------

def resolve(coords, log=print):
    """Breadth-first walk of the dependency graph. Highest version wins."""
    chosen = {}                       # (g,a) -> version
    depth = {}                        # (g,a) -> distance from a root coordinate
    queue = []
    for c in coords:
        g, a, v = c.split(":")
        chosen[(g, a)] = v
        depth[(g, a)] = 0
        queue.append((g, a, v, 0))

    seen, poms = set(), {}
    while queue:
        g, a, v, d = queue.pop(0)
        if (g, a, v) in seen:
            continue
        seen.add((g, a, v))

        try:
            pom = Pom(g, a, v)
        except (LookupError, ET.ParseError) as e:
            log(f"  ! skipping {g}:{a}:{v} - {e}")
            continue
        poms[(g, a, v)] = pom

        for dg, da, dv in pom.deps:
            dv = dv or pom.managed.get((dg, da))
            if not dv:
                log(f"  ! no version for {dg}:{da} (needed by {a}) - skipped")
                continue
            # Shallowest wins: a direct dependency's resources must outrank
            # those of something it merely pulled in transitively.
            prev = depth.get((dg, da))
            if prev is None or d + 1 < prev:
                depth[(dg, da)] = d + 1

            cur = chosen.get((dg, da))
            if cur is None or version_key(dv) > version_key(cur):
                chosen[(dg, da)] = dv
                queue.append((dg, da, dv, d + 1))

    # keep only the winning version of each module
    final = []
    for (g, a), v in sorted(chosen.items()):
        pom = poms.get((g, a, v))
        if pom is None:
            try:
                pom = Pom(g, a, v)
            except Exception as e:                              # noqa: BLE001
                log(f"  ! dropping {g}:{a}:{v} - {e}")
                continue
        final.append((g, a, v, pom.packaging, depth.get((g, a), 99)))
    # deepest first, so callers can treat the order as lowest-priority-first
    final.sort(key=lambda t: (-t[4], t[0], t[1]))
    return final


def download(resolved, outdir, log=print):
    os.makedirs(outdir, exist_ok=True)
    manifest, total = [], 0
    for g, a, v, packaging, depth in resolved:
        ext = "aar" if packaging == "aar" else "jar"
        try:
            data = fetch(coord_path(g, a, v, ext))
        except LookupError:
            other = "jar" if ext == "aar" else "aar"
            try:
                data = fetch(coord_path(g, a, v, other))
                ext = other
            except LookupError:
                log(f"  ! no artifact for {g}:{a}:{v} - skipped")
                continue
        dest = os.path.join(outdir, f"{g}-{a}-{v}.{ext}")
        with open(dest, "wb") as f:
            f.write(data)
        total += len(data)
        manifest.append({"group": g, "artifact": a, "version": v,
                         "type": ext, "file": dest, "depth": depth})
    log(f"  {len(manifest)} artifacts, {total / 1e6:.1f} MB")
    return manifest


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    out = "libs"
    if "--out" in sys.argv:
        out = sys.argv[sys.argv.index("--out") + 1]
        args = [a for a in args if a != out]
    if not args:
        raise SystemExit(__doc__)

    err = lambda m: print(m, file=sys.stderr)                   # noqa: E731
    err(f"resolving {len(args)} coordinate(s)...")
    res = resolve(args, log=err)
    err(f"  graph: {len(res)} modules")
    man = download(res, out, log=err)
    print(json.dumps(man, indent=2))
