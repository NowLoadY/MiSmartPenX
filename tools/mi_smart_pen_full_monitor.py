#!/usr/bin/env python3
"""
MiSmartPenX Tools
Designed and Developed by NowLoadY
"""
import subprocess
import re
import shutil
import sys
import signal
import time
import threading
from dataclasses import dataclass
from typing import Optional


ADB = shutil.which("adb") or "adb"

DIGITIZER_EVENT = "/dev/input/event4"     # NVTCapacitivePen：坐标、压感、倾角、笔尖
BUTTON_EVENT = "/dev/input/event10"       # Xiaomi Smart Pen：实体按键

PEN_X_MAX = 12799
PEN_Y_MAX = 20479
PRESSURE_MAX = 4095

SCREEN_W = 1600
SCREEN_H = 2560


@dataclass
class BatteryInfo:
    # 触控笔
    pen_soc: Optional[int] = None
    pen_soc_decimal: Optional[int] = None
    pen_soc_decimal_rate: Optional[int] = None
    pen_charge_state: Optional[int] = None

    # 平板/手机本体
    tablet_level: Optional[int] = None
    tablet_scale: Optional[int] = None
    tablet_status: Optional[int] = None
    tablet_plugged: Optional[int] = None
    tablet_voltage_mv: Optional[int] = None
    tablet_temperature_c10: Optional[int] = None


@dataclass
class PenState:
    x_raw: Optional[int] = None
    y_raw: Optional[int] = None
    x: Optional[float] = None
    y: Optional[float] = None
    x_norm: Optional[float] = None
    y_norm: Optional[float] = None

    pressure: int = 0
    pressure_norm: float = 0.0
    distance: Optional[int] = None
    tilt_x: Optional[int] = None
    tilt_y: Optional[int] = None

    touching: bool = False
    digi: bool = False

    stylus_btn1_raw: bool = False
    stylus_btn2_raw: bool = False

    key_pageup: bool = False
    key_pagedown: bool = False
    key_leftmeta: bool = False

    last_button_event: str = "-"
    battery: BatteryInfo = None


state = PenState()
state.battery = BatteryInfo()

lock = threading.Lock()
running = True
processes = []


def run_adb(args, timeout=10):
    try:
        result = subprocess.run(
            [ADB] + args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="ignore",
            timeout=timeout,
        )
        return result.stdout, result.stderr, result.returncode
    except FileNotFoundError:
        print("找不到 adb，请先安装：sudo apt install android-tools-adb")
        sys.exit(1)


def check_device():
    out, _, _ = run_adb(["devices"])
    if "\tdevice" not in out:
        print("没有检测到已授权 Android 设备。")
        print(out)
        sys.exit(1)


def parse_value(s: str):
    s = s.strip()

    if s.upper() == "DOWN":
        return 1

    if s.upper() == "UP":
        return 0

    try:
        return int(s, 16)
    except ValueError:
        pass

    try:
        return int(s)
    except ValueError:
        return None


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def update_mapped_values():
    if state.x_raw is not None:
        x = clamp(state.x_raw, 0, PEN_X_MAX)
        state.x_norm = x / PEN_X_MAX
        state.x = state.x_norm * (SCREEN_W - 1)

    if state.y_raw is not None:
        y = clamp(state.y_raw, 0, PEN_Y_MAX)
        state.y_norm = y / PEN_Y_MAX
        state.y = state.y_norm * (SCREEN_H - 1)

    state.pressure_norm = clamp(state.pressure / PRESSURE_MAX, 0.0, 1.0)


def strip_timestamp(line: str) -> str:
    return re.sub(r"^\[\s*\d+\.\d+\]\s*", "", line.strip())


def parse_getevent_payload(line: str):
    line = strip_timestamp(line)

    m = re.match(r"^/dev/input/event\d+:\s*(.*)$", line)
    if m:
        line = m.group(1)

    parts = line.split()
    if len(parts) < 3:
        return None

    ev_type = parts[0]
    code = parts[1]
    value_s = parts[2]
    value = parse_value(value_s)

    if value is None:
        return None

    return ev_type, code, value, value_s


def handle_digitizer_line(line: str):
    parsed = parse_getevent_payload(line)
    if parsed is None:
        return

    ev_type, code, value, _ = parsed

    with lock:
        if ev_type == "EV_ABS":
            if code == "ABS_X":
                state.x_raw = value
            elif code == "ABS_Y":
                state.y_raw = value
            elif code == "ABS_PRESSURE":
                state.pressure = value
            elif code == "ABS_DISTANCE":
                state.distance = value
            elif code == "ABS_TILT_X":
                state.tilt_x = value
            elif code == "ABS_TILT_Y":
                state.tilt_y = value

            update_mapped_values()

        elif ev_type == "EV_KEY":
            if code == "BTN_TOUCH":
                state.touching = bool(value)
            elif code == "BTN_DIGI":
                state.digi = bool(value)
            elif code == "BTN_STYLUS":
                state.stylus_btn1_raw = bool(value)
            elif code == "BTN_STYLUS2":
                state.stylus_btn2_raw = bool(value)


def handle_button_line(line: str):
    parsed = parse_getevent_payload(line)
    if parsed is None:
        return

    ev_type, code, value, _ = parsed

    if ev_type != "EV_KEY":
        return

    pressed = bool(value)

    with lock:
        if code == "KEY_PAGEUP":
            state.key_pageup = pressed
            state.last_button_event = f"PAGEUP:{value}"

        elif code == "KEY_PAGEDOWN":
            state.key_pagedown = pressed
            state.last_button_event = f"PAGEDOWN:{value}"

        elif code == "KEY_LEFTMETA":
            state.key_leftmeta = pressed
            state.last_button_event = f"META:{value}"

        elif code in ["KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT"]:
            state.last_button_event = f"{code}:{value}"


def adb_getevent_worker(device: str, handler):
    global running

    cmd = [ADB, "shell", "getevent", "-lt", device]

    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="ignore",
        bufsize=1,
    )

    processes.append(proc)

    while running:
        line = proc.stdout.readline()
        if not line:
            time.sleep(0.01)
            continue

        handler(line)


def extract_int(text: str, key: str):
    m = re.search(rf"{re.escape(key)}\s*=\s*(-?\d+)", text)
    if not m:
        return None
    return int(m.group(1))


def parse_battery_from_activity_broadcasts(text: str) -> BatteryInfo:
    info = BatteryInfo()

    # 触控笔反向充电 sticky broadcast
    info.pen_soc = extract_int(text, "miui.intent.extra.REVERSE_PEN_SOC")
    info.pen_charge_state = extract_int(
        text,
        "miui.intent.extra.ACTION_PEN_REVERSE_CHARGE_STATE",
    )

    # 小米系统电量小数 sticky broadcast
    info.pen_soc_decimal = extract_int(text, "miui.intent.extra.soc_decimal")
    info.pen_soc_decimal_rate = extract_int(text, "miui.intent.extra.soc_decimal_rate")

    # 系统本体电池 BATTERY_CHANGED
    info.tablet_level = extract_int(text, "level")
    info.tablet_scale = extract_int(text, "scale")
    info.tablet_status = extract_int(text, "status")
    info.tablet_plugged = extract_int(text, "plugged")
    info.tablet_voltage_mv = extract_int(text, "voltage")
    info.tablet_temperature_c10 = extract_int(text, "temperature")

    return info


def update_battery_info(new_info: BatteryInfo):
    b = state.battery

    if new_info.pen_soc is not None and 0 <= new_info.pen_soc <= 100:
        b.pen_soc = new_info.pen_soc

    if new_info.pen_soc_decimal is not None:
        b.pen_soc_decimal = new_info.pen_soc_decimal

    if new_info.pen_soc_decimal_rate is not None:
        b.pen_soc_decimal_rate = new_info.pen_soc_decimal_rate

    if new_info.pen_charge_state is not None:
        b.pen_charge_state = new_info.pen_charge_state

    if new_info.tablet_level is not None:
        b.tablet_level = new_info.tablet_level

    if new_info.tablet_scale is not None:
        b.tablet_scale = new_info.tablet_scale

    if new_info.tablet_status is not None:
        b.tablet_status = new_info.tablet_status

    if new_info.tablet_plugged is not None:
        b.tablet_plugged = new_info.tablet_plugged

    if new_info.tablet_voltage_mv is not None:
        b.tablet_voltage_mv = new_info.tablet_voltage_mv

    if new_info.tablet_temperature_c10 is not None:
        b.tablet_temperature_c10 = new_info.tablet_temperature_c10


def battery_worker():
    while running:
        out, _, code = run_adb(
            ["shell", "dumpsys", "activity", "broadcasts"],
            timeout=12,
        )

        if code == 0 and out:
            new_info = parse_battery_from_activity_broadcasts(out)
            with lock:
                update_battery_info(new_info)

        time.sleep(2)


def fmt_float(v: Optional[float], width=7, digits=1):
    if v is None:
        return "?".rjust(width)
    return f"{v:{width}.{digits}f}"


def fmt_int(v: Optional[int]):
    if v is None:
        return "?"
    return str(v)


def fmt_pen_soc():
    b = state.battery

    if b.pen_soc is None:
        return "?"

    if b.pen_soc_decimal is not None and b.pen_soc_decimal_rate:
        return f"{b.pen_soc}.{b.pen_soc_decimal}%"

    return f"{b.pen_soc}%"


def fmt_tablet_battery():
    b = state.battery

    if b.tablet_level is None:
        return "?"

    if b.tablet_scale and b.tablet_scale != 100:
        return f"{b.tablet_level}/{b.tablet_scale}"

    return f"{b.tablet_level}%"


def fmt_tablet_temp():
    t = state.battery.tablet_temperature_c10
    if t is None:
        return "?"
    return f"{t / 10:.1f}C"


def current_button():
    if state.key_pageup:
        return "PAGEUP"
    if state.key_pagedown:
        return "PAGEDOWN"
    if state.key_leftmeta:
        return "META"
    return "-"


def current_mode():
    if state.touching:
        return "touch"
    if state.digi:
        return "hover"
    return "idle"


def render_loop():
    while running:
        with lock:
            line = (
                "\r"
                f"mode={current_mode():<5} "
                f"x={fmt_float(state.x)} "
                f"y={fmt_float(state.y)} "
                f"raw=({fmt_int(state.x_raw)},{fmt_int(state.y_raw)}) "
                f"p={state.pressure:>4} "
                f"dist={fmt_int(state.distance):>3} "
                f"tilt=({fmt_int(state.tilt_x)},{fmt_int(state.tilt_y)}) "
                f"btn={current_button():<8} "
                f"last_btn={state.last_button_event:<12} "
                f"pen_soc={fmt_pen_soc():<6} "
                f"pen_charge={fmt_int(state.battery.pen_charge_state):<3} "
                f"tablet={fmt_tablet_battery():<5} "
                f"plugged={fmt_int(state.battery.tablet_plugged):<2} "
                f"volt={fmt_int(state.battery.tablet_voltage_mv):<4}mV "
                f"temp={fmt_tablet_temp():<6}"
                + " " * 30
            )

        print(line, end="", flush=True)
        time.sleep(0.03)


def show_header():
    print("小米触控笔监听器")
    print("=" * 100)
    print(f"笔尖/坐标设备：{DIGITIZER_EVENT}")
    print(f"物理按键设备：{BUTTON_EVENT}")
    print()
    print(
        "字段：mode | x/y | raw | p | dist | tilt | btn | last_btn | "
        "pen_soc | pen_charge | tablet | plugged | volt | temp"
    )
    print("Ctrl+C 退出。")
    print("=" * 100)
    print()


def stop(sig=None, frame=None):
    global running
    running = False

    for p in processes:
        try:
            p.terminate()
        except Exception:
            pass


def main():
    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    check_device()
    show_header()

    threads = [
        threading.Thread(
            target=adb_getevent_worker,
            args=(DIGITIZER_EVENT, handle_digitizer_line),
            daemon=True,
        ),
        threading.Thread(
            target=adb_getevent_worker,
            args=(BUTTON_EVENT, handle_button_line),
            daemon=True,
        ),
        threading.Thread(
            target=battery_worker,
            daemon=True,
        ),
        threading.Thread(
            target=render_loop,
            daemon=True,
        ),
    ]

    for t in threads:
        t.start()

    try:
        while running:
            time.sleep(0.2)
    finally:
        stop()
        print("\n已退出。")


if __name__ == "__main__":
    main()