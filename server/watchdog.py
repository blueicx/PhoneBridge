import os
import socket
import subprocess
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
NODE = r"C:\Users\blueice\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
PHONE_PORT = 9503
BRIDGE_PORT = 9501
ADB_SERVER_PORTS = (5038, 5039)
SPAWN_COOLDOWN_SECONDS = 30
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
OUT = open(os.path.join(ROOT, "watchdog_server.out.log"), "ab", buffering=0)
ERR = open(os.path.join(ROOT, "watchdog_server.err.log"), "ab", buffering=0)


def alive() -> bool:
    try:
        with socket.create_connection(("127.0.0.1", BRIDGE_PORT), timeout=0.4):
            return True
    except OSError:
        return False


def adb(*args: str) -> str:
    result = subprocess.run(
        [ADB, *args],
        capture_output=True,
        text=True,
        timeout=2,
        check=False,
        creationflags=NO_WINDOW,
    )
    return result.stdout


def ensure_usb_reverse() -> tuple[bool, str]:
    for server_port in ADB_SERVER_PORTS:
        try:
            listing = adb("-P", str(server_port), "devices")
            online = [
                line.split("\t")[0]
                for line in listing.splitlines()
                if "\tdevice" in line
            ]
            if not online:
                continue

            serial = online[0]
            forwarded = adb(
                "-P", str(server_port), "reverse", "--list"
            )
            if f"tcp:{PHONE_PORT}:tcp:{BRIDGE_PORT}" in forwarded:
                return True, f"{serial}@{server_port}"

            adb(
                "-P", str(server_port),
                "reverse",
                f"tcp:{PHONE_PORT}",
                f"tcp:{BRIDGE_PORT}",
            )
            return True, f"{serial}@{server_port}"
        except (OSError, subprocess.TimeoutExpired) as error:
            ERR.write(f"adb {server_port}: {error}\n".encode())
    return False, ""


def main() -> None:
    last_link = None
    last_check = 0.0
    last_spawn = 0.0
    while True:
        try:
            now = time.monotonic()
            if now - last_spawn >= SPAWN_COOLDOWN_SECONDS and not alive():
                env = os.environ.copy()
                env["PHONEBRIDGE_PORT"] = str(BRIDGE_PORT)
                subprocess.Popen(
                    [NODE, "index.js"],
                    cwd=ROOT,
                env=env,
                stdout=OUT,
                stderr=ERR,
                creationflags=NO_WINDOW,
                )
                last_spawn = now
                time.sleep(2)

            if now - last_check >= 5:
                linked, detail = ensure_usb_reverse()
                status = (linked, detail)
                if status != last_link:
                    message = (
                        f"[success] USB bridge ready: {detail}\n"
                        if linked else
                        "[info] Waiting for phone ADB connection\n"
                    )
                    OUT.write(message.encode())
                    last_link = status
                last_check = now
        except Exception as error:
            ERR.write(f"watchdog: {error}\n".encode())
        time.sleep(2)


if __name__ == "__main__":
    main()
