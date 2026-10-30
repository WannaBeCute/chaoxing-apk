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
# 进度条帧识别
# --------------------------------------------------------------------------- #
# tqdm 用 "\r" 原地刷新进度条，bar_format 形如：
#     TS060A7682A1.mp4:  37%|████       | 08:12/22:05
# 每秒刷几次且**从不换行**，原样落盘就是一个视频刷出几千行
# "\rTS060...: 0%|...| 00:01/22:05\rTS060...: 0%|..." 的垃圾，
# 日志窗口又刷不动、也读不出内容。这里把它们从日志流里摘掉，
# 改为写入 ui/progress.json，由 APP 侧渲染成真正的进度条。
PROGRESS_RE = re.compile(
    r"^(?P<name>.*?):\s*(?P<pct>\d{1,3})%\|[^|]*\|\s*"
    r"(?P<cur>\d{1,2}:\d{2}(?::\d{2})?)\s*/\s*(?P<total>\d{1,2}:\d{2}(?::\d{2})?)"
)

# 行分隔（保留分隔符，便于判断是换行还是回车原地重画）
_LINE_SPLIT_RE = re.compile(r"(\r\n|\r|\n)")

# 光标控制序列（\x1b[A 上移、\x1b[?25l 隐藏光标……）由 tqdm 的 moveto/clear 发出，
# 落进日志里没有意义，只留下 ESC[A 噪声。SGR（m 结尾）要保留给日志配色。
_ANSI_CSI_RE = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")

# progress.json 的最小写入间隔（秒）：进度条刷新很密，节流一下避免频繁写盘
_PROGRESS_FLUSH_INTERVAL = 0.4

# 已收尾任务的「墓碑」记录保留时长（秒）：只用来挡住迟到的进度帧，
# 超过这个时间同名的条目一定来自新任务，直接放行即可。
_PROGRESS_DONE_TTL = 60.0


def _strip_cursor_codes(text):
    """去掉光标控制类 ANSI 序列，保留 SGR 颜色码（m 结尾）。"""
    return _ANSI_CSI_RE.sub(lambda m: m.group(0) if m.group(0).endswith("m") else "", text)


def _parse_timecode(tc):
    """`08:12` / `1:02:03` -> 秒数；解析失败返回 0。"""
    try:
        parts = [int(p) for p in str(tc).split(":")]
    except (TypeError, ValueError):
        return 0
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    if len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    return 0


# --------------------------------------------------------------------------- #
# 日志输出
# --------------------------------------------------------------------------- #
class LogWriter(io.TextIOBase):
    """写入日志文件，同时保留最近若干行，用于解析课程列表。

    日志流里混着两类内容，这里按终端语义分流：

    1. 普通文本（以 \\n 结尾）—— 原样落盘，进度条区域之外照常显示；
    2. tqdm 的进度条刷新帧（以 \\r 开头、不换行）—— **不落盘**，
       只解析成结构化进度写进 ui/progress.json。

    否则一个 22 分钟的视频会往日志里灌 1300+ 帧 "xx.mp4: 3%|...| 00:41/22:05"，
    日志窗口被刷屏、还会被 3000 行上限反复截断，看起来就是"一直在重复输出"。
    """

    def __init__(self, path, progress_path=None, ring_size=600):
        self._fp = open(path, "ab", buffering=0)
        self._lock = threading.Lock()
        self.ring = collections.deque(maxlen=ring_size)
        self.progress_path = progress_path
        # name -> {"pct", "cur", "total", "submitting", "ts"}
        self._progress = {}
        # 已收尾任务 -> True，表示还有一条迟到帧要吞掉（见 _is_retired）
        self._finished = {}
        # 本次结算刚放行了一个「收尾后重启」的任务首帧，需要立刻写盘
        self._pending_new_task = False
        # 拿不到日志锁时被丢弃的输出条数（见 write()）
        self._dropped = 0
        self._last_progress_flush = 0.0
        # 收到 \r 后，后续内容属于"原地重画"，不进日志
        self._pending_overwrite = False
        # 换行之前已积累的普通文本（半行日志，等下一批补全后再写）
        self._pending_text = ""
        # 重画期间攒下的内容，等下一次 \r / \n 结算
        self._overwrite_text = ""

    # ------------------------------------------------------------------ #
    # 写入
    # ------------------------------------------------------------------ #
    def write(self, s):
        if not s:
            return 0
        if isinstance(s, bytes):
            try:
                s = s.decode("utf-8", "replace")
            except Exception:
                s = str(s)

        # 限时拿锁：正常情况下锁内只有内存操作 + 一次小文件写入（微秒级），
        # 3 秒都拿不到说明有线程卡死在锁里（比如 fsync 撞上存储 I/O 停滞）。
        # 这时宁可丢掉这一条日志也不能让**所有**线程排在锁后面——那会让
        # 整个解释器看起来彻底冻结，连停止都停不下来（真机已踩过）。
        if not self._lock.acquire(timeout=3.0):
            self._dropped += 1
            if self._dropped % 50 == 1:
                try:
                    self._emit("[LogWriter] 日志锁超时，已丢弃 %d 条输出\n" % self._dropped)
                except Exception:
                    pass
            return len(s)
        try:
            for part in _LINE_SPLIT_RE.split(s):
                if part in ("\n", "\r\n"):
                    # 真正的换行：本行结束，落地待写内容 + 处理重画残留
                    self._commit_line()
                elif part == "\r":
                    # 回车：终端语义是"回到行首重画"，本行内容整体作废
                    self._pending_text = ""
                    self._resolve_overwrite()
                    self._pending_overwrite = True
                elif part:
                    if self._pending_overwrite:
                        # 重画期间的内容先攒着，等下一个 \r / \n 才能判定是
                        # 进度条还是普通文本——tqdm 一帧可能被 write() 切成多段
                        self._overwrite_text += part
                    else:
                        self._pending_text += part
        finally:
            self._lock.release()

        return len(s)

    def _commit_line(self):
        """一行结束：把积累的文本写出去。"""
        self._resolve_overwrite()
        self._pending_overwrite = False
        text = self._pending_text
        self._pending_text = ""
        if text:
            self._emit(text + "\n")

    def _resolve_overwrite(self):
        """结算"回车重画"期间攒下的内容。

        判定顺序很重要：tqdm 的帧与 loguru 的整行日志会**粘在同一次写入**里
        （帧没有结尾换行，紧接着就是带颜色的日志行），所以先用 PROGRESS_RE
        从头匹配，匹配到的前缀是进度条、剩余部分是普通日志，两者分开处理。
        """
        text = _strip_cursor_codes(self._overwrite_text).strip()
        self._overwrite_text = ""
        if not text:
            return

        m = PROGRESS_RE.match(text)
        if m:
            name = (m.group("name") or "").strip()
            if name:
                pct = int(m.group("pct"))
                cur_s = _parse_timecode(m.group("cur"))
                total_s = _parse_timecode(m.group("total"))
                # tqdm 的百分比会向下取整，22:04/22:05 可能只显示 99%，
                # 但此时视频其实已经播完、正在提交。所以「时长已到（含 1 秒取整误差）」
                # 也算提交中——否则最后一两秒会一直是 99%，看起来像卡住。
                submitting = pct >= 100 or (
                    total_s > 0 and cur_s >= total_s - 1
                )
                if not self._is_retired(name, submitting):
                    frame_ts = time.time()
                    self._progress[name] = {
                        "pct": 100 if submitting else pct,
                        "cur": m.group("cur"),
                        "total": m.group("total"),
                        # 100% 之后脚本还在等服务端判定通过，这段时间对外显示
                        # 「提交ing...」而不是干巴巴的 100%（见 Kotlin ProgressStore）
                        "submitting": submitting,
                        "ts": frame_ts,
                    }
                    if self._pending_new_task:
                        # 新任务的首帧，跳过 0.4s 节流立刻写盘并撤掉旧墓碑
                        self._pending_new_task = False
                        self._finished.pop(name, None)
                        self._last_progress_flush = frame_ts
                        self._write_progress_file()
                    else:
                        self._maybe_flush_progress()
            text = text[m.end():].strip()

        if text:
            self._emit(text + "\n")

    def _is_retired(self, name, submitting):
        """该任务的这一帧是否属于「已经收尾、不该再出现」的残留。

        tqdm 在 close() 时还会刷最后一帧，多线程下这帧可能晚于
        clear_progress 结算，于是已完成的视频又被写回面板。墓碑不是
        「这个名字永久屏蔽」：视频失败回退音频、章节重试都会用同一个
        任务名重新来过，永久屏蔽会让新任务一直不显示。
        """
        stamp = self._finished.get(name)
        if stamp is None:
            return False
        # 提交中的帧只可能来自旧进度条：任务收尾之后它已经被关掉了
        if submitting:
            return True
        # 帧记下的时间早于最近一次写盘 ⇒ 属于上一轮积压下来的数据
        if stamp > self._last_progress_flush:
            return True
        # 否则这是收尾之后才产生的帧，只可能是同名的新任务：
        # 撤掉墓碑并立刻写盘，不然 0.4s 节流内下一帧还会被继续吞掉。
        self._finished.pop(name, None)
        self._pending_new_task = True
        return False

    def _maybe_flush_progress(self):
        now = time.time()
        if now - self._last_progress_flush < _PROGRESS_FLUSH_INTERVAL:
            return
        self._last_progress_flush = now
        self._write_progress_file()

    def flush_progress(self):
        if not self._lock.acquire(timeout=3.0):
            return
        try:
            self._write_progress_file()
        finally:
            self._lock.release()

    def _write_progress_file(self):
        if not self.progress_path:
            return
        try:
            payload = {
                "time": time.time(),
                "items": [
                    {"name": name, "pct": v["pct"], "cur": v["cur"], "total": v["total"],
                     "submitting": v.get("submitting", v["pct"] >= 100), "ts": v["ts"]}
                    for name, v in self._progress.items()
                ],
            }
            _atomic_write_json(self.progress_path, payload, sync=False)
            # 面板内容已经落盘，之前积压的墓碑没必要再留着：
            # 再来的帧都是新任务，0.4s 一次的写盘也足够把旧残留盖掉。
            self._finished.clear()
        except Exception:
            pass

    def clear_progress(self, name=None):
        """任务收尾：从面板摘掉，并留下墓碑吃掉 tqdm 关闭时刷的那一帧。

        限时拿锁：停止时 worker 线程的收尾都会走到这里，如果锁被某个
        卡死的线程占着，无限等待就会把整条停止链路拖死。
        拿不到锁就直接放弃（面板上残留一条旧进度，比卡死好得多）。
        """
        if not self._lock.acquire(timeout=3.0):
            return
        try:
            if name is None:
                self._progress.clear()
                self._finished.clear()
            else:
                self._progress.pop(name, None)
            # 必须先落盘再登记墓碑：写盘成功会清掉墓碑
            self._write_progress_file()
            if name:
                self._finished[name] = True
                self._prune_finished()
        finally:
            self._lock.release()

    def _prune_finished(self):
        """墓碑只在两次写盘之间有意义：堆积过多时整体清空。"""
        if len(self._finished) > 256:
            # 正常一次运行最多几十个任务同时收尾；真上千了宁可漏一帧，
            # 也不能让这个字典无限增长（下一帧就会把残留盖回去）。
            self._finished.clear()

    def _emit(self, text):
        """把清理后的日志文本真正写入文件 + 环形缓冲。"""
        if not text:
            return
        try:
            self._fp.write(text.encode("utf-8", "replace"))
        except Exception:
            pass
        for line in text.splitlines():
            line = line.strip()
            if line:
                self.ring.append(line)

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
def _atomic_write_json(path, obj, sync=True):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
        f.flush()
        if sync:
            # fsync 保证断电/被杀时状态文件不丢，但一次要几毫秒到几十毫秒；
            # progress.json 每 0.4s 写一次且在日志锁内调用，fsync 撞上存储
            # I/O 停滞时会把所有等锁的线程拖死，所以进度文件不做 sync。
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


def _interrupt_script_thread():
    """把 KeyboardInterrupt 投递给「正在跑脚本的那个线程」。

    为什么不用 `os.kill(getpid(), SIGINT)`：
        Python 的信号处理函数只会在**解释器主线程**里执行，而 Chaquopy 调
        `start()` 的线程未必就是初始化解释器的那个线程，信号可能压根落不到
        跑脚本的线程上（真机表现就是点了停止、脚本毫无反应）。
    这里优先用 C 接口 `PyThreadState_SetAsyncExc` 直接给目标线程挂异步异常，
    不依赖信号；拿不到再退回原来的 SIGINT。
    """
    # 脚本已经进入收尾阶段就别再投递了：
    # 这时候投出去的异常只会打在收尾逻辑上，把「写状态」那一步打断。
    if not STATE.get("entry_running", False):
        return False
    target = STATE.get("script_thread") or threading.main_thread()
    if target is threading.current_thread():
        return False

    try:
        import ctypes

        tid = target.ident
        if tid:
            set_async = ctypes.pythonapi.PyThreadState_SetAsyncExc
            set_async.restype = ctypes.c_int
            n = set_async(ctypes.c_ulong(tid), ctypes.py_object(KeyboardInterrupt))
            if n == 1:
                return True
            if n > 1:
                # 一次影响了多个线程状态，立刻撤销，避免误伤
                set_async(ctypes.c_ulong(tid), None)
    except Exception:
        pass

    try:
        os.kill(os.getpid(), signal.SIGINT)
        return True
    except Exception:
        return False


def _clear_pending_async_exc():
    """清掉挂在**当前线程**上、还没投递的异步异常。

    为什么需要：看门狗投递的是异步异常，CPython 只承诺「下次执行字节码时抛出」，
    落点不可控。若协作式停止（补丁后的 sleep / queue）先一步让脚本收尾，
    这个异常就会延迟到 `finally` 里才炸出来——真机表现就是
    「任务明明停了，APP 却卡在正在停止」（因为写状态那句被跳过）。
    """
    try:
        import ctypes

        tid = threading.current_thread().ident
        if tid:
            set_async = ctypes.pythonapi.PyThreadState_SetAsyncExc
            set_async.restype = ctypes.c_int
            # 第二个参数传 NULL 即「撤销待投递的异常」
            set_async(ctypes.c_ulong(tid), None)
    except Exception:
        pass


def _watchdog():
    """监听停止标记，一旦出现就中断正在跑脚本的线程。

    【关键】这里只能用 `_real_sleep`，不能用 `time.sleep`：
        `time.sleep` 在 start() 里已被换成 `_patched_sleep`，而 `_patched_sleep`
        一发现停止标记就抛 KeyboardInterrupt——那样异常会落在**看门狗自己的线程**里，
        看门狗当场死亡（日志里就是 `Exception in thread Thread-N (_watchdog)`），
        下面的投递逻辑一行都执行不到，脚本因此永远收不到中断、继续闷头刷课。
    """
    while True:
        if stop_requested():
            STATE["stopped"] = True
            _interrupt_script_thread()
            return
        _real_sleep(0.5)


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
# Android / Chaquopy 兼容补丁
# --------------------------------------------------------------------------- #
def _patch_multiprocessing():
    """把 multiprocessing 的「进程级同步原语」换成线程版实现。

    背景（真机实测）：
    ------------------
    Chaquopy 不提供 `_multiprocessing` 这个 C 扩展，所以 Android 上没有
    进程间信号量。Chaquopy 自己也知道，`java.android.initialize_multiprocessing()`
    会给 *context* 层的 ThreadingContext 装上 threading 版实现，但 **模块级** 的
    `multiprocessing.Lock / Event / SimpleQueue` 依旧是 SemLock 实现，
    一调用就抛：

        File "stdlib/multiprocessing/synchronize.py", line 57, in __init__
        OSError: No module named '_multiprocessing'

    典型受害者是 loguru：`logger.add(sink, enqueue=True)` 在
    `loguru/_handler.py:93-95` 会创建 `multiprocessing.SimpleQueue/Event/Lock`。
    上游 `api/logger.py` 正是这么写的，于是脚本在 import 阶段就崩了。

    本 APP 是单进程运行（Android 上也没有 fork），enqueue 本来就只是把写日志
    放到后台线程，所以用 threading / queue 的等价实现替换模块级属性，
    语义完全一致。替换的范围刻意与 Chaquopy 自己的 ThreadingContext 保持一致。
    """
    try:
        import multiprocessing
        import queue
        import threading
    except Exception:
        return False

    if getattr(multiprocessing, "_cxrunner_threaded", False):
        return True

    try:
        # loguru 只用到 put / get / close，queue.SimpleQueue 完全兼容
        multiprocessing.SimpleQueue = lambda *a, **kw: queue.SimpleQueue()
        multiprocessing.Event = threading.Event
        multiprocessing.Lock = threading.Lock
        multiprocessing.RLock = threading.RLock
        multiprocessing.Condition = threading.Condition
        multiprocessing.Semaphore = threading.Semaphore
        multiprocessing.BoundedSemaphore = threading.BoundedSemaphore
        multiprocessing.Barrier = threading.Barrier
        multiprocessing._cxrunner_threaded = True
        return True
    except Exception:
        return False


def _patch_queue_join():
    """让 queue.Queue.join() 在收到停止指令后立刻返回。

    为什么需要（真机实测「日志里已经打了 KeyboardInterrupt，APP 却一直停在
    「正在停止…」）：
        上游 `JobProcessor.run()` 用 `self.task_queue.join()` 等所有章节跑完，
        而 `worker_thread` 被 `@log_error` 包着——收到 SIGINT 后它抛出异常、
        被装饰器记录进日志（正是你看到的那条 KeyboardInterrupt），线程就此结束。
        队列里剩下的章节没人再取，`unfinished_tasks` 永远归不了零，
        主线程就死等在 join() 上：脚本不退出、`status.json` 不会写成 finished，
        APP 只能一直停在「正在停止…」。

    这里只替换 join 的等待实现（逻辑与 CPython 原版一致，只是加了停止检查和
    0.2s 超时），**不改上游任何一行代码**。
    """
    try:
        import queue as _queue

        if getattr(_queue.Queue.join, "_cxrunner_stop_aware", False):
            return False

        def join(self, *args, **kwargs):
            with self.all_tasks_done:
                while self.unfinished_tasks:
                    if stop_requested():
                        break
                    self.all_tasks_done.wait(timeout=0.2)

        join._cxrunner_stop_aware = True
        _queue.Queue.join = join
        return True
    except Exception:
        return False


def _patch_queue_get():
    """让 queue.Queue.get() 在收到停止指令后立刻抛 KeyboardInterrupt。

    为什么需要（真机实测：点了停止，日志又继续刷了 35 秒、700 多个任务点）：
        `JobProcessor` 的工作线程是 daemon，循环里阻塞在 `task_queue.get()` 上取任务。
        Chaquopy 的解释器是长期存活的，`start()` 返回并不会带走这些 daemon 线程——
        主线程虽然已经收尾，它们仍会把队列里剩下的章节一个不落刷完。
        让 get() 也感知停止标记，工作线程就会在下一轮取任务时立刻退出。

    只替换等待实现，正常运行时语义与原版一致（只是内部改成 0.2s 轮询），
    **不改上游任何一行代码**。
    """
    try:
        import queue as _queue

        if getattr(_queue.Queue.get, "_cxrunner_stop_aware", False):
            return False
        real_get = _queue.Queue.get
        empty_exc = _queue.Empty

        def get(self, block=True, timeout=None):
            if stop_requested():
                raise KeyboardInterrupt("用户已停止运行")
            if not block:
                return real_get(self, block=False)
            if timeout is None:
                # 无限等待：分段等，每段回来先看一眼停止标记
                while True:
                    if stop_requested():
                        raise KeyboardInterrupt("用户已停止运行")
                    try:
                        return real_get(self, True, 0.2)
                    except empty_exc:
                        continue
            # 有限等待：同样分段，但严格遵守调用方给的 deadline
            deadline = time.time() + timeout
            while True:
                if stop_requested():
                    raise KeyboardInterrupt("用户已停止运行")
                remain = deadline - time.time()
                try:
                    return real_get(self, True, min(max(remain, 0.0), 0.2))
                except empty_exc:
                    if time.time() >= deadline:
                        raise

        get._cxrunner_stop_aware = True
        _queue.Queue.get = get
        return True
    except Exception:
        return False


def _task_threads():
    """找出上游脚本自己起的那些工作线程（JobProcessor 的 worker / retry）。

    判定方式：线程 target 是**绑定方法**，且其所属类定义在入口脚本里
    （模块名为 `__main__` 或 `main`）。这样能把 loguru 那类常驻后台线程排除掉，
    否则下面的等待每次都要白白等满超时。
    """
    me = threading.current_thread()
    found = []
    for t in threading.enumerate():
        if t is me or t is threading.main_thread():
            continue
        if getattr(t, "_cxrunner_internal", False):
            continue
        try:
            if not t.is_alive():
                continue
            owner = getattr(getattr(t, "_target", None), "__self__", None)
            module = getattr(type(owner), "__module__", "") if owner is not None else ""
        except Exception:
            continue
        if module in ("__main__", "main"):
            found.append(t)
    return found


def _wait_for_worker_threads(timeout=5.0):
    """等上游的 daemon 工作线程真正退出，最多等 timeout 秒。

    不等的话，APP 这边已经切回「已停止」，日志却还在刷剩下的章节。
    等满了也不再拖：宁可让残留线程在下一轮 get() 时自己退出，也不能卡住界面。
    """
    deadline = time.time() + timeout
    while True:
        others = _task_threads()
        if not others:
            return True
        if time.time() >= deadline:
            return False
        for t in others:
            t.join(timeout=0.2)


# --------------------------------------------------------------------------- #
# 入口
# --------------------------------------------------------------------------- #
def start(params_json):
    """APP 调用的入口（RunnerService 里 `callAttr("start", paramsJson)`）。

    外面这层是必须的：**无论里面发生什么，状态都要写成 finished**。
    停止是用「异步异常」投递的，它只在线程下次执行字节码时抛出，落点并不可控——
    真机出现过：任务确实停了，但异常在**收尾阶段**才炸出来，
    `finally` 里写状态的那句被跳过，APP 就一直卡在「正在停止…」。
    所以这里兜底：内部哪怕漏出异常，也先把状态落盘、再返回一个退出码。
    """
    try:
        return _start_impl(params_json)
    except BaseException:
        try:
            _write_status("finished", 130)
        except Exception:
            pass
        return 130


def _start_impl(params_json):
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

    # Android 没有 _multiprocessing，先给 multiprocessing 打上线程版兼容补丁，
    # 否则 loguru 的 enqueue=True 会在 import 阶段直接抛 OSError。
    mp_patched = _patch_multiprocessing()

    # 停止时不能死等在 queue.join() 上，否则脚本不退出、APP 一直停在「正在停止…」
    join_patched = _patch_queue_join()

    # 停止后要让上游的 daemon 工作线程也能退出：Chaquopy 的解释器不随 start() 返回
    # 销毁，光让主线程收尾的话，它们仍会把队列里剩下的章节全部刷完
    get_patched = _patch_queue_get()

    STATE.update({
        "workdir": workdir,
        "log_path": log_path,
        "ui_dir": ui_dir,
        "stop_flag": os.path.join(ui_dir, "stop.flag"),
        "req_path": os.path.join(ui_dir, "request.json"),
        "resp_path": os.path.join(ui_dir, "response.json"),
        "req_id": 0,
        "stopped": False,
        # 真正执行脚本的线程：停止时要把 KeyboardInterrupt 投递给它
        "script_thread": threading.current_thread(),
        # 脚本是否还在跑（进入收尾后置 False，看门狗就停止投递）
        "entry_running": False,
    })
    _remove(STATE["stop_flag"])
    _remove(STATE["req_path"])
    _remove(STATE["resp_path"])

    # 日志文件重新开始（保留历史日志无意义，APP 侧可导出）
    try:
        open(log_path, "wb").close()
    except Exception:
        pass

    writer = LogWriter(log_path, progress_path=os.path.join(ui_dir, "progress.json"))
    STATE["writer"] = writer
    sys.stdout = writer
    sys.stderr = writer

    builtins.input = _patched_input
    time.sleep = _patched_sleep
    watchdog = threading.Thread(target=_watchdog, daemon=True)
    watchdog._cxrunner_internal = True  # 收尾等待时要跳过它
    watchdog.start()

    os.chdir(workdir)
    sys.argv = list(argv)

    _write_status("running")
    _log("=" * 52)
    _log("运行环境: Python %s @ Android" % sys.version.split()[0])
    _log("工作目录: %s" % workdir)
    _log("脚本目录: %s" % HERE)
    _log("multiprocessing 兼容补丁: %s" % ("已应用" if mp_patched else "未应用"))
    _log("停止等待(queue.join) 补丁: %s" % ("已应用" if join_patched else "未应用"))
    _log("停止取任务(queue.get) 补丁: %s" % ("已应用" if get_patched else "未应用"))
    _log("启动命令: main.py %s" % " ".join(argv[1:]))
    _log("=" * 52)

    code = 0
    entry_desc = None
    STATE["entry_running"] = True
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
        # 先摘掉「脚本还在跑」这个开关：之后看门狗即便又发现停止标记，
        # 也不该把异步异常挂到正在收尾的线程上。
        STATE["entry_running"] = False
        # 撤销可能已经挂上、但还没来得及投递的异步异常
        _clear_pending_async_exc()
        STATE["stopped"] = True
        _remove(STATE["stop_flag"])
        _remove(STATE["req_path"])
        # 解释器不会因为 start() 返回而销毁，先等上游的 daemon 工作线程收尾，
        # 否则 APP 这边已经切回「已停止」，日志却还在刷剩下的章节。
        # 这里自己吃掉异常：收尾环节被打断也不能影响下面写状态。
        try:
            _wait_for_worker_threads()
        except BaseException:
            pass
        builtins.input = _real_input
        time.sleep = _real_sleep
        if entry_desc:
            _log("入口加载方式: %s" % entry_desc)
        _log("=" * 52)
        try:
            writer.clear_progress()
        except Exception:
            pass
        _log("运行结束，退出码: %d" % code)
        _log("=" * 52)
        _write_status("finished", code)
        try:
            writer.flush()
        except Exception:
            pass
    return code


_real_input = builtins.input
