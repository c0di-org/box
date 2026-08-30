#!/usr/bin/env python3
"""Unpack AARs and merge their manifests into the app's, without Gradle.

An AAR is a zip holding a jar, a resource tree and a manifest fragment. Using
one means three things the Android Gradle Plugin would otherwise do:

  1. unpack it, and collect the jars for the compile classpath and for d8
  2. hand every library's res/ to aapt2 so its resources exist and R fields resolve
  3. merge every library's manifest into the app's

Step 3 is the one with teeth. Libraries declare components the app never
mentions - most importantly androidx.startup's InitializationProvider, which
several AndroidX libraries register meta-data on. If those are dropped the app
still builds and then misbehaves at runtime, so they are merged here rather than
ignored: providers are unioned by name and their meta-data combined, exactly as
AGP does.

    aar.py <manifest.json> <workdir> <app-manifest> --out <merged-manifest>

Prints JSON describing jars, resource directories and extra R packages.
"""

import json
import os
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "http://schemas.android.com/apk/res/android"
TOOLS = "http://schemas.android.com/tools"
ET.register_namespace("android", ANDROID)
ET.register_namespace("tools", TOOLS)
NAME = f"{{{ANDROID}}}name"
AUTHORITIES = f"{{{ANDROID}}}authorities"

# Application children worth carrying over from a library manifest.
COMPONENTS = ("activity", "activity-alias", "service", "receiver",
              "provider", "meta-data", "uses-library")
# Top-level elements worth carrying over.
TOPLEVEL = ("uses-permission", "uses-permission-sdk-23", "uses-feature",
            "permission", "queries")


def unpack(manifest, workdir):
    """Explode each AAR; return jars, resource dirs, manifests, assets."""
    os.makedirs(workdir, exist_ok=True)
    jars, resdirs, manifests, assets = [], [], [], []

    for art in manifest:
        path, kind = art["file"], art["type"]
        label = f"{art['group']}-{art['artifact']}-{art['version']}"

        if kind == "jar":
            jars.append(path)
            continue

        dest = os.path.join(workdir, label)
        if os.path.isdir(dest):
            shutil.rmtree(dest)
        with zipfile.ZipFile(path) as z:
            z.extractall(dest)

        classes = os.path.join(dest, "classes.jar")
        if os.path.exists(classes):
            jars.append(classes)
        # some AARs ship extra jars alongside
        libdir = os.path.join(dest, "libs")
        if os.path.isdir(libdir):
            jars += [os.path.join(libdir, f)
                     for f in sorted(os.listdir(libdir)) if f.endswith(".jar")]

        res = os.path.join(dest, "res")
        if os.path.isdir(res) and os.listdir(res):
            resdirs.append(res)

        am = os.path.join(dest, "AndroidManifest.xml")
        if os.path.exists(am):
            manifests.append(am)

        asset = os.path.join(dest, "assets")
        if os.path.isdir(asset) and os.listdir(asset):
            assets.append(asset)

    return jars, resdirs, manifests, assets


def package_of(manifest_path):
    try:
        return ET.parse(manifest_path).getroot().get("package")
    except ET.ParseError:
        return None


def _subst(elem, app_package):
    """Replace ${applicationId} anywhere in attribute values.

    androidx.startup writes android:authorities="${applicationId}.androidx-startup".
    Left unsubstituted the manifest is invalid and, worse, two apps built this
    way would collide on the same provider authority.
    """
    for k, v in list(elem.attrib.items()):
        # tools: attributes are merger directives (tools:node, tools:replace).
        # AGP consumes and strips them; aapt2 has no idea what they are, so
        # carrying them into the merged manifest can fail the link.
        if k.startswith(f"{{{TOOLS}}}"):
            del elem.attrib[k]
        elif "${applicationId}" in v:
            elem.set(k, v.replace("${applicationId}", app_package))
    for child in elem:
        _subst(child, app_package)


def merge_manifests(app_manifest, lib_manifests, out_path):
    tree = ET.parse(app_manifest)
    root = tree.getroot()
    app_package = root.get("package")
    application = root.find("application")
    if application is None:
        raise SystemExit("app manifest has no <application>")

    seen_top = set()
    for el in root:
        if el.tag in TOPLEVEL:
            seen_top.add((el.tag, el.get(NAME)))

    # existing components, and providers indexed for meta-data union
    seen_comp = {(el.tag, el.get(NAME)) for el in application}
    providers = {el.get(NAME): el for el in application if el.tag == "provider"}

    added = 0
    for lib in lib_manifests:
        try:
            lib_root = ET.parse(lib).getroot()
        except ET.ParseError:
            continue
        _subst(lib_root, app_package)

        for el in lib_root:
            if el.tag in TOPLEVEL:
                key = (el.tag, el.get(NAME))
                if key not in seen_top:
                    seen_top.add(key)
                    root.append(el)
                    added += 1

        lib_app = lib_root.find("application")
        if lib_app is None:
            continue
        for el in lib_app:
            if el.tag not in COMPONENTS:
                continue
            key = (el.tag, el.get(NAME))

            # several libraries register meta-data on the *same* provider;
            # union the children instead of dropping the later copies.
            if el.tag == "provider" and el.get(NAME) in providers:
                target = providers[el.get(NAME)]
                have = {(c.tag, c.get(NAME)) for c in target}
                for child in el:
                    if (child.tag, child.get(NAME)) not in have:
                        target.append(child)
                        added += 1
                continue

            if key in seen_comp:
                continue
            seen_comp.add(key)
            if el.tag == "provider":
                providers[el.get(NAME)] = el
            application.append(el)
            added += 1

    tree.write(out_path, encoding="utf-8", xml_declaration=True)
    return added


if __name__ == "__main__":
    args = sys.argv[1:]
    out = args[args.index("--out") + 1]
    man_json, workdir, app_manifest = args[0], args[1], args[2]

    with open(man_json) as f:
        artifacts = json.load(f)

    jars, resdirs, lib_manifests, assets = unpack(artifacts, workdir)
    packages = sorted({p for p in (package_of(m) for m in lib_manifests) if p})
    added = merge_manifests(app_manifest, lib_manifests, out)

    print(json.dumps({
        "jars": jars,
        "resdirs": resdirs,
        "assets": assets,
        "packages": packages,
        "merged_nodes": added,
    }, indent=2))
