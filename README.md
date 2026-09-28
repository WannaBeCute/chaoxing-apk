# 超星助手（Chaoxing Runner）

把开源项目 [Samueli924/chaoxing](https://github.com/Samueli924/chaoxing)（Python 命令行版刷课脚本）
打包成一个可以在 Android 上**长期后台运行**的 APP，并做成非开发者也能直接上手的界面。

> 上游项目遵循 GPL-3.0，本工程同样遵循 GPL-3.0，仅用于学习讨论，禁止用于盈利。

---

## 一、整体方案

| 层 | 技术 |
| --- | --- |
| Python 运行时 | [Chaquopy](https://chaquo.com/chaquopy) 17.0，Python 3.13，随 APK 打包 |
| 后台常驻 | `RunnerService` 前台服务（通知栏常驻 + 电池优化白名单引导） |
| 界面 | Jetpack Compose + Material 3（VS Code / Termux / Material Files 风格） |
| 脚本交互 | 文件协议（Python 写请求 → APP 弹窗 → APP 写应答） |
| 日志 | Python stdout/stderr 实时落盘，APP 轮询增量读取 |

Python 源码位于 `app/src/main/python/`（即上游 `main.py` + `api/`），由 Chaquopy 编译进 APK。

### 依赖说明（与上游 requirements.txt 的差异）

* `pyaes`：PyPI 只有 sdist，已把源码内置在 `app/src/main/python/pyaes/`；
* `openai`：依赖 `pydantic-core`（Rust 扩展，无 Android wheel），
  由 `app/src/main/python/openai.py` 提供**接口兼容的轻量实现**（httpx 直连 `/chat/completions`）；
* `ddddocr`（验证码 OCR）：依赖 onnxruntime，Android 无 wheel，未内置；
  上游 `api/captcha.py` 对缺失情况已有降级处理；
* `celery` / `flask`：只被上游 Web 版 `app.py` 使用，命令行运行不需要，未内置。

---

## 二、两种运行模式

| 模式 | 等价命令 | 行为 |
| --- | --- | --- |
| 直接运行 | `python main.py` | APP 弹出输入框收集手机号/密码，登录后弹出课程多选列表 |
| 按配置文件运行 | `python main.py -c config.ini` | 使用设置面板生成的 `config.ini`；课程 ID 为空时仍会弹课程列表 |

两种模式都会在真实工作目录（`/data/data/com.cxrunner.app/files/chaoxing`）下写入
`config.ini`、`cookies.txt`、`chaoxing.log`。

---

## 三、交互是怎么做到的

Chaquopy 里 `sys.stdin` 恒为 EOF，且终端不可见。`android_bootstrap.py` 做了三件事：

1. **接管 `builtins.input`**：把提示写成 `ui/request.json`，轮询等待 `ui/response.json`；
   * 提示含"手机号/密码" → 文本/密码输入框；
   * 提示含"课程" → 从最近日志里正则解析出课程列表，弹出**多选课程对话框**（可全选/清空，留空=全部课程）；
   * 提示含 `(Y/n)` → 是/否对话框；
   * 其余 → 普通文本输入框（手动答题模式也走这条路）。
2. **接管 `time.sleep` 与停止标记**：设置面板/通知栏点"停止"后写 `ui/stop.flag`，
   脚本在下一个 sleep 处抛出 `KeyboardInterrupt` 优雅退出。
3. **stdout/stderr 落盘**：`loguru`、`tqdm`、`print` 的输出统一写入 `run.log`，
   APP 日志窗口按字节偏移增量读取，支持自动刷新、字号缩放、导出到下载目录。

---

## 四、设置面板

结构按需求组织为五个区：账号区 / 课程区 / 行为区 / 题库区（折叠）/ 通知区（折叠）。

* **预览文本**：显示按 `config_template.ini` 骨架渲染出的 `config.ini`，可直接编辑保存
  （保存时会反向解析回界面的各个字段）；
* **恢复默认**：二次确认后恢复为模板默认值；
* 生成的 `config.ini` **严格以 `config_template.ini` 为骨架**，只替换键值，
  注释、空行、缩进、`=` 两侧空格全部原样保留（避免上游解析踩坑）。

### 主题

黑色 / 白色 / 跟随系统。

### 权限卡片

主页顶部一排方形卡片，横向可滑动，点击跳转到对应系统设置页：

* 通知权限（常驻通知，始终需要）
* 后台常驻 / 电池优化（始终需要）
* 网络访问（始终需要）
* Cookies 文件 —— **仅当**"使用 Cookie 登录"开启时出现
* 大模型配置 —— **仅当**题库 provider 勾选了 `AI` / `SiliconFlow` 时出现

卡片会显示"已获取 / 去开启"两种状态，点击后无论是否已获取都可以再次跳转系统设置。

---

## 五、构建

```bash
# 需要 JDK 17+（Android Studio 自带 jbr）与 Android SDK
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 注意：本仓库的 `app/build.gradle.kts` 里有一个 `patchChaquopyPipInstall` 补丁，
> 用于规避某些 Windows 环境下 `os.rmdir` 对非空目录同样返回成功、
> 导致 Chaquopy 清理依赖时级联删除整个 ABI 目录的问题；在正常环境下该补丁等价且无害。

另需构建机器上有 Python **3.13**（与 APP 内 Python 版本一致），
路径在 `app/build.gradle.kts` 的 `buildPython(...)` 中配置。

---

## 六、目录结构

```
app/src/main/
├── java/com/cxrunner/app/
│   ├── MainActivity.kt          单 Activity + Compose 路由
│   ├── RunnerApp.kt             初始化 Chaquopy / 释放 assets
│   ├── data/                    配置模型、设置持久化、运行状态、文件布局
│   ├── service/RunnerService.kt 前台服务：启动脚本、监听交互请求与结束状态
│   └── ui/                      主页（终端日志）与设置页及各组件
├── python/
│   ├── android_bootstrap.py     ★ 运行桥接层（日志/交互/停止）
│   ├── main.py, api/            上游脚本
│   ├── openai.py                openai 接口兼容层
│   └── pyaes/                   内置 pyaes 源码
├── assets/resource/             上游字形映射表（启动时释放到工作目录）
└── res/raw/config_template.ini  配置模板（生成 config.ini 的骨架）
```
