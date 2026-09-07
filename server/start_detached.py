import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
NODE = r"C:\Users\blueice\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
env = os.environ.copy()
env["PHONEBRIDGE_PORT"] = "9503"
out = open(os.path.join(ROOT, "detached_server.out.log"), "ab", buffering=0)
err = open(os.path.join(ROOT, "detached_server.err.log"), "ab", buffering=0)
flags = subprocess.CREATE_NO_WINDOW | subprocess.DETACHED_PROCESS
subprocess.Popen([NODE, "index.js"], cwd=ROOT, env=env, stdout=out, stderr=err,
                 creationflags=flags, close_fds=True)
