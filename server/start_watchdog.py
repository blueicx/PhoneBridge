import os
import subprocess

ROOT = os.path.dirname(os.path.abspath(__file__))
PYTHON = r"C:\Users\blueice\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
flags = subprocess.CREATE_NO_WINDOW | subprocess.DETACHED_PROCESS
subprocess.Popen([PYTHON, os.path.join(ROOT, "watchdog.py")], cwd=ROOT,
                 creationflags=flags, close_fds=True)
