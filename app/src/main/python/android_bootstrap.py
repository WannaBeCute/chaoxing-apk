# -*- coding: utf-8 -*-
"""
Android 运行桥接层 (Chaquopy)

作用：
1. 把脚本的 stdout / stderr 实时写入日志文件，供 APP 的日志窗口读取；
2. 接管 builtins.input，把命令行提问转换成「文件协议」交给 APP 弹窗，
   支持文本 / 密码 / 课程多选 / 是否确认 四种交互；
3. 监听停止标记文件，让用户可以随时中断正在运行的脚本；
4. 编译并执行 main.py（等价于 `python main.py ...`）。

文件协议（全部位于 UI 目录，由 APP 传入）：
    request.json   Python -> APP  {"id", "kind", "prompt", "courses": [...]}
    response.json  APP  -> Python {"id", "value": "..."}  (value 为 null 表示取消)
    stop.flag      APP  -> Python 存在即代表要求停止
    status.json    Python -> APP  {"state": "running|finished", "code": 0}
"""

import builtins
import collections
import importlib.util
import io
import json
import os
import re
import signal
import sys
import threading
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

STATE = {
    "workdir": None,
    "log_path": None,
    "ui_dir": None,
    "stop_flag": None,
    "req_path": None,
    "resp_path": None,
    "req_id": 0,
    "stopped": False,
    "writer": None,
}

COURSE_RE = re.compile(r"ID[:：]\s*(\d+)\s+班级ID[:：]\s*(\d+)\s+课程名[:：]\s*(.*)")


# --------------------------------------------------------------------------- #
# 日志输出
# --------------------------------------------------------------------------- #
class LogWriter(io.TextIOBase):
    """写入日志文件，同时保留最近若干行，用于解析课程列表。"""

    def __init__(self, path, ring_size=600):
        self._fp = open(path, "ab", buffering=0)
        self._lock = threading.Lock()
        self.ring = collections.deque(maxlen=ring_size)

    def write(self, s):
        if not s:
            return 0
        if isinstance(s, bytes):
            try:
                s = s.decode("utf-8", "replace")
            except Exception:
                s = str(s)
        with self._lock:
            try:
                self._fp.write(s.encode("utf-8", "replace"))
            except Exception:
                pass
            for line in s.splitlines():
                line = line.rstrip()
                if line:
                    self.ring.append(line)
        return len(s)

    def flush(self):
        pass

    def writable(self):
        return True

    def isatty(self):
        return False

    def seekable(self):
        return False

    def close(self):
        try:
            self._fp.close()
        except Exception:
            pass

    def recent_lines(self, n=400):
        with self._lock:
            return list(self.ring)[-n:]


def _log(text):
    writer = STATE.get("writer")
    if writer is not None:
        writer.write(text + "\n")
    else:
        sys.__stdout__.write(text + "\n")


# --------------------------------------------------------------------------- #
# 原子文件读写
# --------------------------------------------------------------------------- #
def _atomic_write_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def _read_json(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def _remove(path):
    try:
        os.remove(path)
    except Exception:
        pass


def _write_status(state, code=0, message=""):
    path = os.path.join(STATE["ui_dir"], "status.json")
    _atomic_write_json(path, {
        "state": state,
        "code": code,
        "message": message,
        "time": time.strftime("%Y-%m-%d %H:%M:%S"),
    })


# --------------------------------------------------------------------------- #
# 交互协议
# --------------------------------------------------------------------------- #
def _parse_courses():
    """从最近的日志行中解析课程列表。"""
    courses = []
    seen = set()
    writer = STATE.get("writer")
    lines = writer.recent_lines(400) if writer else []
    for line in lines:
        m = COURSE_RE.search(line)
        if not m:
            continue
        course_id, clazz_id, title = m.group(1), m.group(2), m.group(3).strip()
        key = (course_id, clazz_id)
        if key in seen:
            continue
        seen.add(key)
        courses.append({
            "id": course_id,
            "clazzId": clazz_id,
            "title": title,
        })
    return courses


def _classify(prompt):
    """根据提示文本判断交互类型。"""
    text = prompt or ""
    if "密码" in text:
        return "password", None
    if "手机号" in text or "用户名" in text or "账号" in text:
        return "text", None
    if "课程" in text or "courseId" in text or "course" in text.lower():
        return "courses", _parse_courses()
    if re.search(r"\[?\(?Y/n\)?\]?", text) or "是否继续" in text or "确认" in text:
        return "confirm", None
    return "text", None


def request_input(kind, prompt, extra=None):
    """向 APP 请求一次输入，阻塞直到用户提交或取消。"""
    STATE["req_id"] += 1
    req_id = STATE["req_id"]
    payload = {
        "id": req_id,
        "kind": kind,
        "prompt": prompt or "",
        "courses": extra or [],
    }
    if kind == "courses" and extra:
        # 缓存课程列表，方便设置页离线选择
        _atomic_write_json(os.path.join(STATE["ui_dir"], "courses_cache.json"), extra)

    _remove(STATE["resp_path"])
    _atomic_write_json(STATE["req_path"], payload)

    while True:
        if STATE["stopped"] or os.path.exists(STATE["stop_flag"]):
            _remove(STATE["req_path"])
            raise KeyboardInterrupt("用户已停止运行")
        data = _read_json(STATE["resp_path"])
        if data is not None:
            try:
                if int(data.get("id", -1)) == req_id:
                    _remove(STATE["resp_path"])
                    _remove(STATE["req_path"])
                    value = data.get("value")
                    if value is None:
                        raise KeyboardInterrupt("用户取消了输入")
                    return str(value)
            except KeyboardInterrupt:
                raise
            except Exception:
                _remove(STATE["resp_path"])
                raise KeyboardInterrupt("用户输入解析失败")
        time.sleep(0.3)


def _patched_input(prompt=""):
    prompt = prompt or ""
    if prompt.strip():
        _log(prompt)
    kind, extra = _classify(prompt)
    return request_input(kind, prompt, extra)


# --------------------------------------------------------------------------- #
# 停止信号
# --------------------------------------------------------------------------- #
def stop_requested():
    return STATE["stopped"] or os.path.exists(STATE["stop_flag"])


_real_sleep = time.sleep


def _patched_sleep(secs):
    """分段睡眠，保证停止指令能被及时响应。"""
    try:
        secs = float(secs)
    except Exception:
        secs = 0.0
    if secs <= 0:
        return
    deadline = time.time() + secs
    while True:
        if stop_requested():
            raise KeyboardInterrupt("用户已停止运行")
        remain = deadline - time.time()
        if remain <= 0:
            return
        _real_sleep(min(remain, 0.4))


def _watchdog():
    """监听停止标记，向自身发送 SIGINT 以中断主线程。"""
    while True:
        if stop_requested():
            STATE["stopped"] = True
            try:
                os.kill(os.getpid(), signal.SIGINT)
            except Exception:
                pass
            return
        time.sleep(0.5)


# --------------------------------------------------------------------------- #
# 脚本执行
# --------------------------------------------------------------------------- #
#
# 为什么不用 runpy.run_path？
# --------------------------
# Chaquopy 为 assets 目录（AssetFinder）注册了自定义的 import 路径钩子，
# 它并不要求路径必须是「目录」。于是 CPython 的 runpy.run_path 内部执行
# `pkgutil.get_importer(path)` 时，会把 main.py 这个**文件路径**当成一个
# 可导入的包，从而走进 "把路径塞进 sys.path 再 import __main__" 的分支，
# 最终抛出：
#
#     ImportError: can't find '__main__' module in '.../app/main.py'
#
# 另外，Chaquopy 打进 APK 的是编译后的 .pyc（见 assets/chaquopy/app.imy），
# 运行时由 AssetFinder 虚拟提供，磁盘上**不一定**存在同名的 .py 文件。
# 因此这里按「能拿到什么就用什么」的顺序加载入口，语义始终等于 `python main.py`。
# --------------------------------------------------------------------------- #

ENTRY_MODULE = "main"


def _entry_globals(path):
    """构造等价于 `python main.py` 的模块全局字典。"""
    return {
        "__name__": "__main__",
        "__file__": path,
        "__cached__": None,
        "__doc__": None,
        "__loader__": None,
        "__package__": "",
        "__spec__": None,
        "__builtins__": builtins,
    }


def _code_from_file(path):
    """从磁盘文件读取代码：.py 走 compile，.pyc 走 marshal。"""
    with open(path, "rb") as fp:
        data = fp.read()
    if path.endswith(".pyc"):
        # 3.7+ 的 pyc 头部固定 16 字节（magic + flags + 时间戳/哈希 + 大小）
        import marshal
        return marshal.loads(data[16:])
    # 与 py_compile / runpy 保持一致：统一为 lf，避免 CRLF 影响行号
    data = data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
    return compile(data, path, "exec")


def _exec_script(path, run_name="__main__"):
    """直接执行磁盘上的脚本文件（等价于 `python <path>`）。"""
    code = _code_from_file(path)
    mod_globals = _entry_globals(path)
    mod_globals["__name__"] = run_name
    mod_globals["__package__"] = run_name.rpartition(".")[0]
    exec(code, mod_globals)
    return mod_globals


def _safe_listdir(path):
    try:
        return sorted(os.listdir(path))
    except Exception as exc:
        return ["<%s>" % exc]


def _run_entry():
    """执行入口脚本，返回实际使用的加载方式（写进日志方便排查）。

    顺序：
      1. 磁盘上真的存在 main.py / main.pyc  → 直接 compile / marshal 后 exec
         （最贴近 `python main.py`，也是 Chaquopy 解包后的常见形态）
      2. 否则交给 import 机制（AssetFinder 直接提供模块）
    """
    for candidate in ("main.py", "main.pyc"):
        path = os.path.join(HERE, candidate)
        if os.path.isfile(path):
            _exec_script(path, run_name="__main__")
            return "file:%s" % path
    return _run_entry_via_import()


def _run_entry_via_import():
    spec = None
    try:
        spec = importlib.util.find_spec(ENTRY_MODULE)
    except Exception:
        spec = None

    if spec is None or spec.loader is None:
        raise ImportError(
            "找不到入口模块 %r。HERE=%s 内容=%s"
            % (ENTRY_MODULE, HERE, _safe_listdir(HERE))
        )

    loader = spec.loader
    origin = getattr(spec, "origin", None) or os.path.join(HERE, "main.py")

    # 方式 A：把代码对象取出来，用 __main__ 身份执行（不依赖 loader 对名字的校验）
    get_code = getattr(loader, "get_code", None)
    if callable(get_code):
        for name in (ENTRY_MODULE, "__main__"):
            code = None
            try:
                code = get_code(name)
            except Exception:
                code = None
            if code is not None:
                exec(code, _entry_globals(origin))
                return "code:%s" % origin

    # 方式 B：让 loader 以 __main__ 的身份加载
    try:
        spec.name = "__main__"
        loader.name = "__main__"
        module = importlib.util.module_from_spec(spec)
        module.__package__ = ""
        module.__file__ = origin
        sys.modules["__main__"] = module
        loader.exec_module(module)
        if getattr(module, "__name__", None) == "__main__":
            return "import:%s" % origin
    except Exception:
        pass

    # 方式 C：按真实模块名加载，再显式调用入口函数 main()
    module = importlib.util.module_from_spec(spec)
    sys.modules[ENTRY_MODULE] = module
    loader.exec_module(module)
    entry_main = getattr(module, "main", None)
    if callable(entry_main):
        entry_main()
        return "call:%s" % origin
    raise ImportError("无法执行入口模块 %r（%s）" % (ENTRY_MODULE, origin))


# --------------------------------------------------------------------------- #
# 入口
# --------------------------------------------------------------------------- #
def start(params_json):
    """
    params_json: {
        "workdir":  可写工作目录（cookies.txt / chaoxing.log / config.ini 都放这里）
        "log":      日志文件路径
        "ui":       UI 交互目录
        "argv":     ["main.py", "-c", "/path/config.ini", ...]
    }
    返回进程退出码。
    """
    params = json.loads(params_json)
    workdir = params["workdir"]
    log_path = params["log"]
    ui_dir = params["ui"]
    argv = params.get("argv") or ["main.py"]

    os.makedirs(workdir, exist_ok=True)
    os.makedirs(ui_dir, exist_ok=True)

    STATE.update({
        "workdir": workdir,
        "log_path": log_path,
        "ui_dir": ui_dir,
        "stop_flag": os.path.join(ui_dir, "stop.flag"),
        "req_path": os.path.join(ui_dir, "request.json"),
        "resp_path": os.path.join(ui_dir, "response.json"),
        "req_id": 0,
        "stopped": False,
    })
    _remove(STATE["stop_flag"])
    _remove(STATE["req_path"])
    _remove(STATE["resp_path"])

    # 日志文件重新开始（保留历史日志无意义，APP 侧可导出）
    try:
        open(log_path, "wb").close()
    except Exception:
        pass

    writer = LogWriter(log_path)
    STATE["writer"] = writer
    sys.stdout = writer
    sys.stderr = writer

    builtins.input = _patched_input
    time.sleep = _patched_sleep
    threading.Thread(target=_watchdog, daemon=True).start()

    os.chdir(workdir)
    sys.argv = list(argv)

    _write_status("running")
    _log("=" * 52)
    _log("运行环境: Python %s @ Android" % sys.version.split()[0])
    _log("工作目录: %s" % workdir)
    _log("脚本目录: %s" % HERE)
    _log("启动命令: main.py %s" % " ".join(argv[1:]))
    _log("=" * 52)

    code = 0
    entry_desc = None
    try:
        entry_desc = _run_entry()
    except SystemExit as e:  # 脚本主动退出（ argparse -h 等）
        code = int(e.code) if isinstance(e.code, int) else (0 if e.code is None else 1)
    except KeyboardInterrupt:
        _log("已停止运行。")
        code = 130
    except BaseException:
        code = 1
        try:
            writer.write(traceback.format_exc() + "\n")
        except Exception:
            pass
    finally:
        STATE["stopped"] = True
        builtins.input = _real_input
        time.sleep = _real_sleep
        _remove(STATE["stop_flag"])
        _remove(STATE["req_path"])
        if entry_desc:
            _log("入口加载方式: %s" % entry_desc)
        _log("=" * 52)
        _log("运行结束，退出码: %d" % code)
        _log("=" * 52)
        _write_status("finished", code)
        try:
            writer.flush()
        except Exception:
            pass
    return code


_real_input = builtins.input
