import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PKG = "dev.starkbit.chato.debug"
PORT = 8765
SERVER = f"http://127.0.0.1:{PORT}"
TRANSCRIPT = "check one two"
IME = f"{PKG}/com.whisperonnx.WhisperInputMethodService"
CHECK_SCREEN = f"{PKG}/com.whisperonnx.devicecheck.DeviceCheckActivity"
PREFS = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="backend_type">openai</string>
    <string name="openai_endpoint">{SERVER}/v1/audio/transcriptions</string>
    <string name="openai_api_key">device-check</string>
    <string name="openai_model">fake</string>
    <string name="endpoint1_name">Check</string>
    <string name="endpoint1_url">{SERVER}/endpoint/1</string>
    <boolean name="imeModeAuto" value="false" />
    <boolean name="imeToolbarExpanded" value="true" />
    <int name="imeSendTarget" value="0" />
</map>
"""


def sdk_adb():
    with open(os.path.join(ROOT, "local.properties")) as f:
        sdk = re.search(r"sdk\.dir=(.*)", f.read()).group(1).strip()
    return os.path.join(sdk, "platform-tools", "adb")


ADB = [sdk_adb()] + (["-s", os.environ["DEVICE"]] if os.environ.get("DEVICE") else [])


def adb(*args, stdin=None, check=True):
    r = subprocess.run(ADB + list(args), input=stdin, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def shell(cmd, stdin=None, check=True):
    return adb("shell", cmd, stdin=stdin, check=check)


def server(path, data=None):
    req = urllib.request.Request(SERVER + path, data=data, method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=5) as r:
        return r.read().decode()


def events(prefix):
    return [e for e in json.loads(server("/log")) if e["path"].startswith(prefix)]


def nodes():
    raw = adb("exec-out", "uiautomator", "dump", "--windows", "/dev/tty", check=False)
    if "<hierarchy" not in raw:
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    found = []
    for xml in re.findall(r"<hierarchy.*?</hierarchy>", raw, re.S):
        found.extend(ET.fromstring(xml).iter("node"))
    return found


def node(rid):
    for n in nodes():
        if n.get("resource-id") == f"{PKG}:id/{rid}":
            return n
    return None


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def present(value):
    if isinstance(value, ET.Element):
        return True
    return value is not None and value is not False and value != "" and value != []


def wait(what, fn, timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        try:
            value = fn()
        except OSError:
            value = None
        if present(value):
            return value
        time.sleep(0.4)
    raise AssertionError(f"timed out waiting for {what}")


def tap(rid):
    x, y = center(wait(rid, lambda: node(rid)))
    shell(f"input tap {x} {y}")
    time.sleep(0.4)


def hold(rid, ms):
    x, y = center(wait(rid, lambda: node(rid)))
    shell(f"input swipe {x} {y} {x} {y} {ms}")


def drag(rid, dx_dp):
    n = wait(rid, lambda: node(rid))
    x, y = center(n)
    density = int(re.search(r"(\d+)", shell("wm density").split(":")[-1]).group(1)) / 160
    shell(f"input swipe {x} {y} {x + int(dx_dp * density)} {y} 600")
    time.sleep(0.4)


def field_text():
    n = node("check_field")
    if n is None:
        return None
    text = n.get("text", "")
    return "" if text == n.get("hint", "\0") or text == "Device check field" else text


def pref(name):
    xml = shell(f"run-as {PKG} cat shared_prefs/{PKG}_preferences.xml", check=False)
    m = re.search(rf'name="{re.escape(name)}"(?: value="([^"]*)" />|>([^<]*)<)', xml)
    return (m.group(1) or m.group(2)) if m else None


def strings():
    lang = shell("getprop persist.sys.locale", check=False).strip().replace("-", "-r", 1)
    found = {}
    for folder in ("values", f"values-{lang.split('-r')[0]}", f"values-{lang}"):
        path = os.path.join(ROOT, "app/src/main/res", folder, "strings.xml")
        if os.path.exists(path):
            found.update({e.get("name"): e.text for e in ET.parse(path).getroot().iter("string")})
    return found


def label(name, *args):
    text = STRINGS[name].replace("\\'", "'")
    for i, arg in enumerate(args, 1):
        text = re.sub(rf"%{i}\$[sd]", lambda m: str(arg), text)
        text = re.sub(r"%[sd]", lambda m: str(arg), text, count=1)
    return text


def keyboard_status():
    texts = [getattr(node(rid), "get", lambda k: None)("text") for rid in ("tv_status_inline", "tv_status")]
    return next((t for t in texts if t), None)


def fail(path, **how):
    server("/fail", json.dumps({"path": path, **how}).encode())


def wait_status(what, expected):
    seen = []

    def matches():
        status = keyboard_status()
        if status and status not in seen:
            seen.append(status)
        return status == expected

    try:
        wait(what, matches, 15)
    except AssertionError:
        raise AssertionError(f"expected status {expected!r}, saw {seen or 'none'}")


def quick_record_finished(since):
    log = shell(f"logcat -d -v raw -T '{since}' -s QuickRecord:D", check=False)
    return re.findall(r"^Finished: (.*)$", log, re.M)


def open_check_screen():
    shell(f"am start -n {CHECK_SCREEN} --activity-clear-task")
    wait("the device check screen", lambda: node("check_field"), 15)
    wait("the keyboard", lambda: node("btnRecord"), 15)
    wait("an empty field", lambda: field_text() == "")


def check_keys():
    open_check_screen()
    tap("btnBullet")
    tap("btnDot")
    tap("btnSpace")
    tap("btnDash")
    wait("'- . -' in the field", lambda: field_text() == "- . -")
    tap("btnDel")
    wait("delete to remove one character", lambda: field_text() == "- . ")
    tap("btnEnter")
    wait("enter to run the field's action", lambda: getattr(node("check_done_count"), "get", lambda k: None)("text") == "Enter actions: 1")


def check_cursor_drag():
    open_check_screen()
    shell("input text abcdef")
    wait("typed text", lambda: field_text() == "abcdef")
    drag("btnSpace", -40)
    tap("btnDot")
    text = wait("the dot", lambda: field_text() if field_text() and "." in field_text() else None)
    assert not text.endswith("."), f"dragging on space did not move the cursor: {text!r}"


def record(rid="btnRecord"):
    hold(rid, 2500)


def check_dictation_at_cursor():
    open_check_screen()
    server("/reset", b"")
    record()
    wait("the transcript in the field", lambda: TRANSCRIPT in (field_text() or ""), 15)
    calls = events("/v1/audio/transcriptions")
    assert len(calls) == 1, f"expected one transcription request, got {len(calls)}"
    assert calls[0]["auth"] == "Bearer device-check", "the API key was not sent"
    assert not events("/endpoint/"), "typing at the cursor also sent to an endpoint"


def check_send_target():
    open_check_screen()
    server("/reset", b"")
    tap("btnSendTarget")
    wait("send target 1 saved", lambda: pref("imeSendTarget") == "1")
    record()
    sent = wait("a POST to endpoint 1", lambda: events("/endpoint/1"), 15)
    assert TRANSCRIPT in sent[0]["body"], f"endpoint body has no transcript: {sent[0]['body']!r}"
    assert field_text() == "", "sending to an endpoint also typed into the field"
    tap("btnSendTarget")
    wait("send target back to the cursor", lambda: pref("imeSendTarget") == "0")


def check_toggles_are_saved():
    open_check_screen()
    for rid, key in (("btnModeAuto", "imeModeAuto"), ("btnToolbarToggle", "imeToolbarExpanded")):
        before = pref(key)
        tap(rid)
        wait(f"{key} to change", lambda: pref(key) != before)
        tap(rid)
        wait(f"{key} to change back", lambda: pref(key) == before)


def check_quick_record():
    shell("input keyevent KEYCODE_HOME")
    server("/reset", b"")
    start = f"am start -n {PKG}/com.whisperonnx.QuickRecordActivity --ei endpoint 1"
    shell(start)
    time.sleep(2.5)
    shell(start)
    sent = wait("quick record to POST to endpoint 1", lambda: events("/endpoint/1"), 15)
    assert TRANSCRIPT in sent[0]["body"], f"endpoint body has no transcript: {sent[0]['body']!r}"


def check_server_error():
    open_check_screen()
    server("/reset", b"")
    fail("/v1/audio/transcriptions", code=500, body="boom")
    record()
    wait_status("the server error in the keyboard",
                label("status_with_detail", label("status_server_rejected"), "500: boom"))
    assert field_text() == "", "a failed transcription typed into the field"


def check_network_error():
    open_check_screen()
    server("/reset", b"")
    fail("/v1/audio/transcriptions", drop=True)
    record()
    prefix = label("status_network_error")
    wait("the network error in the keyboard", lambda: (keyboard_status() or "").startswith(prefix), 15)
    assert field_text() == "", "a failed transcription typed into the field"


def with_send_target(fn):
    tap("btnSendTarget")
    wait("send target 1 saved", lambda: pref("imeSendTarget") == "1")
    try:
        fn()
    finally:
        tap("btnSendTarget")
        wait("send target back to the cursor", lambda: pref("imeSendTarget") == "0")


def check_endpoint_error():
    open_check_screen()
    server("/reset", b"")
    fail("/endpoint/1", code=503)
    expected = label("endpoint_failed", "Check", label("send_failed_http", 503))

    def send():
        record()
        wait_status("the endpoint error in the keyboard", expected)

    with_send_target(send)


def check_quick_record_error():
    shell("input keyevent KEYCODE_HOME")
    server("/reset", b"")
    fail("/endpoint/1", code=503)
    since = shell("date '+%m-%d %H:%M:%S.000'").strip()
    start = f"am start -n {PKG}/com.whisperonnx.QuickRecordActivity --ei endpoint 1"
    shell(start)
    time.sleep(2.5)
    shell(start)
    finished = wait("quick record to finish", lambda: quick_record_finished(since), 15)
    expected = label("endpoint_failed", "Check", label("send_failed_http", 503))
    assert finished == [expected], f"quick record showed {finished}, expected {expected!r}"


def dismiss_notices():
    found = nodes()
    ok = [n for n in found if n.get("resource-id") == "android:id/button1"]
    if ok:
        x, y = center(ok[0])
        shell(f"input tap {x} {y}")
        return None
    return any(n.get("resource-id") == f"{PKG}:id/tvResult" for n in found)


def check_test_screen():
    server("/reset", b"")
    shell(f"am start -n {PKG}/com.whisperonnx.MainActivity")
    wait("the test screen, after dismissing its first-run notices", dismiss_notices, 20)
    record()
    wait("the transcript on the test screen",
         lambda: TRANSCRIPT in (getattr(node("tvResult"), "get", lambda k: None)("text") or ""), 15)


def describe_screen():
    ours = [n for n in nodes() if n.get("package") in (PKG, "android")]
    shown = [f"{n.get('resource-id', '').split('/')[-1] or n.get('class', '').split('.')[-1]}"
             + (f"={n.get('text')!r}" if n.get("text") else "") for n in ours]
    return ", ".join(x for x in shown if x) or "nothing from the debug app"


STRINGS = {}

CHECKS = [check_keys, check_cursor_drag, check_dictation_at_cursor, check_send_target,
          check_toggles_are_saved, check_quick_record, check_test_screen, check_server_error,
          check_network_error, check_endpoint_error, check_quick_record_error]


def setup():
    if "device" not in adb("get-state", check=False):
        sys.exit("No device. Is USB debugging on and the phone unlocked?")
    window = shell("dumpsys window", check=False)
    if re.search(r"mKeyguardShowing=true|isKeyguardShowing=true|mShowingLockscreen=true", window):
        sys.exit("The phone is locked. Unlock it and run again.")
    apk = os.path.join(ROOT, "app/build/outputs/apk/debug/app-debug.apk")
    adb("install", "-r", "-g", apk)
    shell(f"am force-stop {PKG}")
    shell(f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/{PKG}_preferences.xml'", stdin=PREFS)
    adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    previous = shell("settings get secure default_input_method").strip()
    shell(f"ime enable {IME}")
    shell(f"ime set {IME}")
    shell("input keyevent KEYCODE_WAKEUP")
    STRINGS.update(strings())
    return previous


def teardown(previous_ime):
    if previous_ime and previous_ime != "null":
        shell(f"ime set {previous_ime}", check=False)
    shell(f"ime disable {IME}", check=False)
    adb("reverse", "--remove", f"tcp:{PORT}", check=False)
    shell(f"am force-stop {PKG}", check=False)
    shell("input keyevent KEYCODE_HOME", check=False)


def main():
    fake = subprocess.Popen([sys.executable, "-I", os.path.join(os.path.dirname(__file__), "fake_server.py"), str(PORT)])
    try:
        wait("the fake server", lambda: server("/log") is not None)
        previous = setup()
        try:
            failed = 0
            wanted = sys.argv[1:]
            for check in CHECKS:
                if wanted and check.__name__ not in wanted:
                    continue
                try:
                    check()
                    print(f"PASS {check.__name__}")
                except Exception as e:
                    failed += 1
                    print(f"FAIL {check.__name__}: {e}")
                    print("  on screen: " + describe_screen())
        finally:
            teardown(previous)
    finally:
        fake.terminate()
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
