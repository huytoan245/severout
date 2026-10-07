from pathlib import Path
import sys
r=Path(__file__).resolve().parent.parent
if len(sys.argv)>1: raise SystemExit("Historical reconstruction: checkout the historical commit in a separate directory; do not overwrite canonical tracked source.")
assert (r/'appsrc/child-app/src/main/java/com/family/child/LocationService.kt').is_file()
print("v232 canonical source is tracked in appsrc; no archive extraction or patches applied")
