#!/usr/bin/env python3
"""Checks FlowForge's compiled bytecode against Android's official public API.

Why: FlowForge can be built against the Robolectric "android-all" framework jar (when Google's
SDK hosts are unreachable). That jar contains hidden/internal APIs too, so code could compile but
crash on a phone. This script disassembles our own classes (app + core, not third-party libraries)
with `javap`, collects every reference to android.* (the framework API, where hidden/internal APIs live),
and resolves each one (walking superclasses and interfaces) against AOSP's
core/api/current.txt:

  * API 36 (Android 16): anything missing is an ERROR (hidden or non-existent API).
  * API 33 (Android 13, minSdk): anything present in 36 but not in 33 is listed for review;
    each such call must be guarded by a Build.VERSION.SDK_INT check (or justified).

Usage: tools/api_check.py CLASS_DIR...
Exit status 1 if errors are found or an unreviewed newer-than-minSdk call appears.
Allowlists (one label per line, '#' comments): tools/api_module_allowlist.txt (public APIs that
live in mainline-module API files not loaded here) and tools/api_guarded_allowlist.txt
(newer-than-minSdk calls that were reviewed and are behind a Build.VERSION check).
"""
import hashlib
import os
import re
import subprocess
import sys
import urllib.request

API_FILES = {
    36: ("android16-release", "1f84aa28d5e59cd4b24087032bdd6880d87570dda64a8a6474817fb043b39b16"),
    33: ("android13-release", "b81f2c9481116901ccd986d973fa4d6e658f016e8d8d3224ee15fb25c3f9d4e6"),
}
URL = "https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/{branch}/core/api/current.txt"
# java.*/javax.* are covered by compiling with -Xjdk-release=1.8 (Java 8 API, fully provided by
# Android 13+); org.json and dalvik live in libcore's API file, which this check does not load.
CHECKED_PREFIXES = ("android/",)
PRIMITIVES = {"Z": "boolean", "B": "byte", "C": "char", "S": "short", "I": "int", "J": "long",
              "F": "float", "D": "double", "V": "void"}


def cache_dir():
    d = os.environ.get("FLOWFORGE_API_CACHE") or os.path.join(os.path.expanduser("~"), ".cache", "flowforge-api")
    os.makedirs(d, exist_ok=True)
    return d


def fetch(level):
    branch, sha = API_FILES[level]
    path = os.path.join(cache_dir(), f"current-{branch}.txt")
    if not os.path.exists(path):
        with urllib.request.urlopen(URL.format(branch=branch), timeout=60) as r:
            data = r.read()
        with open(path + ".tmp", "wb") as f:
            f.write(data)
        os.replace(path + ".tmp", path)
    data = open(path, "rb").read()
    if hashlib.sha256(data).hexdigest() != sha:
        sys.exit(f"Checksum mismatch for {path}; delete it and retry.")
    return data.decode("utf-8")


def strip_balanced(s, open_ch, close_ch):
    out, depth = [], 0
    for ch in s:
        if ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
        elif depth == 0:
            out.append(ch)
    return "".join(out)


ANNOTATION = re.compile(r"@[\w.]+")


def strip_annotations(s):
    # Remove "@Foo(...)" and "@Foo" tokens (arguments may contain parentheses and quotes).
    out, i = [], 0
    while i < len(s):
        if s[i] == "@" and i + 1 < len(s) and (s[i + 1].isalpha()):
            m = ANNOTATION.match(s, i)
            i = m.end()
            if i < len(s) and s[i] == "(":
                depth, in_str = 0, False
                while i < len(s):
                    c = s[i]
                    if c == '"':
                        in_str = not in_str
                    elif not in_str and c == "(":
                        depth += 1
                    elif not in_str and c == ")":
                        depth -= 1
                        if depth == 0:
                            i += 1
                            break
                    i += 1
            continue
        out.append(s[i])
        i += 1
    return " ".join("".join(out).split())


class ApiClass:
    def __init__(self, name):
        self.name = name          # dotted, nested with '.', e.g. android.view.View.OnClickListener
        self.supers = []          # dotted names as written
        self.methods = {}         # name -> list of param lists (None entries = type variable)
        self.fields = set()
        self.ctors = []


def normalize_type(t, type_vars):
    t = strip_balanced(t.strip(), "<", ">").strip()
    t = t.replace("...", "[]")
    dims = t.count("[]")
    base = t.replace("[]", "").strip()
    if base in type_vars or (re.fullmatch(r"[A-Z]\w*", base) and "." not in base):
        return None if dims == 0 else "?" + "[]" * dims
    return base + "[]" * dims


def parse_api(text):
    classes = {}
    pkg = None
    cur = None
    class_vars = set()
    for raw in text.splitlines():
        if raw.startswith("package ") and raw.rstrip().endswith("{"):
            pkg = raw[len("package "):].rstrip(" {")
            continue
        if raw.startswith("  ") and not raw.startswith("    ") and raw.rstrip().endswith("{"):
            header = strip_annotations(raw.strip()[:-1])
            m = re.search(r"\b(class|interface|enum|@interface)\s+([\w.]+)", header)
            if not m:
                cur = None
                continue
            name = m.group(2)
            rest = header[m.end():]
            gm = re.match(r"\s*<(.*)>", rest)
            class_vars = set(re.findall(r"(?:^|,)\s*(\w+)", strip_balanced(gm.group(1), "<", ">"))) if gm else set()
            rest = strip_balanced(rest, "<", ">")
            supers = []
            # Signature format 2.0 separates supertypes with spaces (older files used commas).
            em = re.search(r"\bextends\s+(.+?)(?:\s+implements\s+|$)", rest)
            if em:
                supers += [x for x in re.split(r"[\s,]+", em.group(1)) if x]
            im = re.search(r"\bimplements\s+(.+)$", rest)
            if im:
                supers += [x for x in re.split(r"[\s,]+", im.group(1)) if x]
            cur = ApiClass(f"{pkg}.{name}")
            cur.supers = supers
            classes[cur.name] = cur
            continue
        if cur is None or not raw.startswith("    "):
            continue
        line = strip_annotations(raw.strip())
        if line.startswith("method "):
            body = line[len("method "):]
            body = re.sub(r"\s+throws\s+[^;]+;$", ";", body)
            m = re.match(r"(.*?)(\w+)\((.*)\);$", body)
            if not m:
                continue
            prefix, name, params = m.group(1), m.group(2), m.group(3)
            tm = re.search(r"<(.*)>", strip_balanced(prefix, "(", ")"))
            mvars = set(re.findall(r"(?:^|,)\s*(\w+)", strip_balanced(tm.group(1), "<", ">"))) if tm else set()
            plist = split_params(params)
            cur.methods.setdefault(name, []).append([normalize_type(p, mvars | class_vars) for p in plist])
        elif line.startswith("ctor "):
            m = re.match(r".*?\((.*)\)(?:\s+throws\s+[^;]+)?;$", line)
            if m:
                cur.ctors.append([normalize_type(p, class_vars) for p in split_params(m.group(1))])
        elif line.startswith("field ") or line.startswith("enum_constant "):
            m = re.match(r"[^=;]*?(\w+)\s*(=.*)?;", line)
            if m:
                cur.fields.add(m.group(1))
    return classes


def split_params(params):
    params = strip_balanced(params, "<", ">").strip()
    if not params:
        return []
    out = []
    for p in params.split(","):
        p = p.strip()
        # Signature format 2.0 has types only; drop a trailing parameter name if present.
        parts = p.split(" ")
        out.append(parts[0] if len(parts) > 1 and not parts[1].startswith("[") else p)
    return out


class Api:
    def __init__(self, classes):
        self.classes = classes

    def resolve_name(self, dotted):
        dotted = strip_balanced(dotted, "<", ">").strip()
        return dotted if dotted in self.classes else None

    def lookup_class(self, binary):  # binary: android/view/View$OnClickListener
        return self.classes.get(binary.replace("/", ".").replace("$", "."))

    def hierarchy(self, cls):
        seen, queue = set(), [cls]
        while queue:
            c = queue.pop(0)
            if c is None or c.name in seen:
                continue
            seen.add(c.name)
            yield c
            for s in c.supers:
                queue.append(self.classes.get(self.resolve_name(s) or ""))
        obj = self.classes.get("java.lang.Object")
        if obj is not None and obj.name not in seen:
            yield obj

    def has_unresolved_super(self, cls):
        """True if a supertype is outside the loaded API (e.g. java.lang.CharSequence)."""
        for c in self.hierarchy(cls):
            for sname in c.supers:
                if self.resolve_name(sname) is None:
                    return True
        return False

    def has_method(self, binary, name, params):
        cls = self.lookup_class(binary)
        if cls is None:
            return None
        for c in self.hierarchy(cls):
            if name == "<init>":
                if c is not cls:
                    break
                cands = c.ctors
            else:
                cands = c.methods.get(name, [])
            for cand in cands:
                if len(cand) == len(params) and all(a is None or a == "?" or a.startswith("?") or a == b for a, b in zip(cand, params)):
                    return True
        # Implicit default constructor / enum helpers.
        if name in ("values", "valueOf") or (name == "<init>" and not params and not cls.ctors):
            return True
        # Possibly inherited from a java.* interface we don't load (e.g. CharSequence.length()).
        return "unverifiable" if self.has_unresolved_super(cls) else False

    def has_field(self, binary, name):
        cls = self.lookup_class(binary)
        if cls is None:
            return None
        return any(name in c.fields for c in self.hierarchy(cls))


def descriptor_params(desc):
    inner = desc[1:desc.index(")")]
    out, i = [], 0
    while i < len(inner):
        dims = 0
        while inner[i] == "[":
            dims += 1
            i += 1
        if inner[i] == "L":
            j = inner.index(";", i)
            t = inner[i + 1:j].replace("/", ".").replace("$", ".")
            i = j + 1
        else:
            t = PRIMITIVES[inner[i]]
            i += 1
        out.append(t + "[]" * dims)
    return out


REF = re.compile(r"//\s*(Method|InterfaceMethod|Field|class)\s+(.+)$")
HEADER = re.compile(r"^(?:[\w ]*?)(?:class|interface)\s+([\w.$]+)(?:<.*?>)?(?:\s+extends\s+([\w.$<>, ?]+?))?(?:\s+implements\s+([\w.$<>, ?]+?))?\s*\{")
MEMBER_NAME = re.compile(r"([\w$<>]+)\(")


def collect_refs(class_dirs):
    """Returns (refs, classinfo). refs: {(kind, owner, name, desc): {locations}} where owner may be
    one of our own classes; classinfo: {our class (slash form): (super, [interfaces], {members})}."""
    files = []
    for d in class_dirs:
        for root, _, names in os.walk(d):
            files += [os.path.join(root, n) for n in names if n.endswith(".class")]
    refs, classinfo = {}, {}
    for i in range(0, len(files), 150):
        out = subprocess.run(["javap", "-c", "-p"] + files[i:i + 150], capture_output=True, text=True).stdout
        cls, member = "?", "?"
        for line in out.splitlines():
            if line and not line.startswith(" "):
                m = HEADER.match(line)
                if m:
                    cls = m.group(1).replace(".", "/")
                    sup = strip_balanced(m.group(2) or "java.lang.Object", "<", ">").strip().replace(".", "/")
                    ifaces = [x.strip().replace(".", "/") for x in strip_balanced(m.group(3) or "", "<", ">").split(",") if x.strip()]
                    classinfo[cls] = (sup, ifaces, set())
                continue
            if line.startswith("  ") and not line.startswith("    ") and "(" in line and cls in classinfo:
                member = line.strip()
                mm = MEMBER_NAME.search(member)
                if mm:
                    classinfo[cls][2].add(mm.group(1).split(".")[-1])
            elif line.startswith("  ") and not line.startswith("    ") and line.rstrip().endswith(";") and cls in classinfo:
                # Field declaration: "  private final int foo;"
                classinfo[cls][2].add(line.rstrip(";").split()[-1])
            m = REF.search(line)
            if not m:
                continue
            kind, target = m.group(1), m.group(2).strip()
            if kind == "class":
                owner = target.strip('"').lstrip("[").lstrip("L").rstrip(";")
                key = ("class", owner, "", "")
            else:
                owner_name, _, desc = target.partition(":")
                owner, _, name = owner_name.rpartition(".")
                key = (kind, owner or cls, name.strip('"'), desc)
            refs.setdefault(key, set()).add(f"{cls.replace('/', '.')}  {member[:80]}")
    return refs, classinfo


def retarget(refs, classinfo):
    """Maps references whose owner is one of our classes to the framework ancestor that provides the
    member (e.g. DictationService.startForeground -> android.app.Service.startForeground). Members
    declared by our own classes are dropped; only android.* targets are kept."""
    out = {}
    for (kind, owner, name, desc), where in refs.items():
        if kind == "class" or owner not in classinfo:
            if owner.startswith(CHECKED_PREFIXES):
                out.setdefault((kind, owner, name, desc), set()).update(where)
            continue
        # Walk our own class chain; stop if one of our classes declares the member.
        target, seen, queue = None, set(), [owner]
        while queue:
            c = queue.pop(0)
            if c in seen:
                continue
            seen.add(c)
            if c in classinfo:
                sup, ifaces, members = classinfo[c]
                if name in members and name != "<init>":
                    target = None
                    queue = []
                    break
                queue += [sup] + ifaces
            elif c.startswith(CHECKED_PREFIXES):
                target = c
                break
        if target is not None and name != "<init>":
            out.setdefault((kind, target, name, desc), set()).update(where)
    return out


def label_of(kind, owner, name, desc):
    return f"{kind} {owner.replace('/', '.')}" + (f".{name}{desc}" if name else "")


def check(refs, api):
    """Returns (errors, unverifiable): references missing from the API, and references that may be
    inherited from java.* types that the framework API file does not describe."""
    errors, unverifiable = [], []
    for (kind, owner, name, desc), where in sorted(refs.items()):
        if kind == "class":
            ok = api.lookup_class(owner) is not None
        elif kind == "Field":
            ok = api.has_field(owner, name)
        else:
            ok = api.has_method(owner, name, descriptor_params(desc))
        label = label_of(kind, owner, name, desc)
        if ok == "unverifiable":
            unverifiable.append((label, where))
        elif ok is False or ok is None:
            errors.append((label, where))
    return errors, unverifiable


def read_list(path):
    if not path or not os.path.exists(path):
        return set()
    return {l.split("#")[0].strip() for l in open(path) if l.split("#")[0].strip()}


def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    module_apis = read_list(os.path.join(here, "api_module_allowlist.txt"))
    guarded = read_list(os.path.join(here, "api_guarded_allowlist.txt"))
    dirs = argv[1:]
    if not dirs:
        sys.exit(__doc__)
    raw_refs, classinfo = collect_refs(dirs)
    refs = retarget(raw_refs, classinfo)
    api36 = Api(parse_api(fetch(36)))
    api33 = Api(parse_api(fetch(33)))
    missing36, unverifiable = check(refs, api36)
    errors = [m for m in missing36 if m[0] not in module_apis]
    module_hits = [m for m in missing36 if m[0] in module_apis]
    missing36_labels = {m[0] for m in missing36}
    missing33 = [m for m in check(refs, api33)[0] if m[0] not in missing36_labels]
    print(f"Checked {len(refs)} distinct android.* references from {len(dirs)} class directories "
          f"against AOSP android16-release (API 36) and android13-release (API 33) public API.")
    print(f"\nERRORS (not public API in Android 16): {len(errors)}")
    for label, where in errors:
        print(f"  {label}\n      used in: {sorted(where)[0]}")
    print(f"\nPublic mainline-module APIs (allowlisted in tools/api_module_allowlist.txt): {len(module_hits)}")
    for label, _ in module_hits:
        print(f"  {label}")
    print(f"\nInherited from java.* types (not described by the framework API file): {len(unverifiable)}")
    for label, _ in unverifiable:
        print(f"  {label}")
    unreviewed = [m for m in missing33 if m[0] not in guarded]
    print(f"\nNewer than minSdk 33: {len(missing33)} ({len(missing33) - len(unreviewed)} reviewed as version-guarded)")
    for label, where in missing33:
        mark = "guarded" if label in guarded else "REVIEW"
        print(f"  [{mark}] {label}\n      used in: {sorted(where)[0]}")
    ok = not errors and not unreviewed
    print("\nRESULT: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
