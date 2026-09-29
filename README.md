# 超星刷课助手

将开源项目 [Samueli924/chaoxing](https://github.com/Samueli924/chaoxing)（Python 命令行版刷课脚本）
打包成一个可以在 Android 上**长期后台运行**的 APP，使得该原项目的刷课脚本让非开发者的普通人也能快速上手使用

> 上游项目遵循 GPL-3.0，本工程同样遵循 GPL-3.0（全文见 [`LICENSE`](LICENSE)），仅用于学习交流，禁止用于盈利。
>
> 本仓库为 APK 打包源码：<https://github.com/WannaBeCute/chaoxing-apk>
>
> 本项目由AI辅助生成

## 应用截图

<p align="center">
  <img src="https://github.com/user-attachments/assets/8a44982b-0cd3-4fe0-b22d-c0a0d09fbac3" width="300" />
  <img src="https://github.com/user-attachments/assets/de8e4f4a-2560-4260-b1ed-3670d1dbe70e" width="300" />
</p>


---

## 一、整体方案

| 层 | 技术 |
| --- | --- |
| Python 运行时 | [Chaquopy](https://chaquo.com/chaquopy) 17.0，Python 3.13，随 APK 打包 |
| 后台常驻 | `RunnerService` 前台服务（通知栏常驻 + 电池优化白名单引导） |
| 界面 | Jetpack Compose + Material 3（VS Code / Termux / Material Files 风格），全屏边到边 + 自动避让系统栏 |
| 脚本交互 | 文件协议（Python 写请求 → APP 弹窗 → APP 写应答） |
| 日志 | Python stdout/stderr 实时落盘，APP 轮询增量读取 |
| 帮助文档 | `assets/help/quickstart.md`，APP 内置极简 Markdown 渲染器直接渲染 |

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

> 首次安装时手机号与密码都是**空的**（不再预填模板占位符 `xxx`）；
> 留空运行时，脚本会在日志区弹出输入框让你临时输入。

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

### 关于脚本入口的执行方式（重要）

入口不使用 `runpy.run_path`。Chaquopy 为 assets 目录（`AssetFinder`）注册了自定义 import
路径钩子，它不要求路径是目录，于是 `runpy.run_path` 内部的 `pkgutil.get_importer(path)`
会把 `main.py` 这个**文件路径**当成可导入的包，走进 `import __main__` 分支并抛出：

```
ImportError: can't find '__main__' module in '.../app/main.py'
```

`android_bootstrap._exec_script()` 改为自己 `compile()` + `exec()`，语义等同
`python main.py`，且不依赖任何 importer 行为。

---

## 四、界面

### 主页

* 顶部右侧依次是 `?`（帮助）与「设置」；
* 一排横滑的**权限卡片**，点击跳转到对应系统设置页：
  * 通知权限（常驻通知，始终需要）
  * 后台常驻 / 电池优化（始终需要）
  * 网络访问（始终需要）
  * Cookies 文件 —— **仅当**"使用 Cookie 登录"开启时出现
  * 大模型配置 —— **仅当**题库 provider 勾选了 `AI` / `SiliconFlow` 时出现
* 运行模式分段控件 + 开始/停止按钮 + 状态条 + 终端风格日志窗口。

### 帮助页

`assets/help/quickstart.md` 由内置的 `MarkdownView`（纯 Compose，零第三方依赖）渲染，
支持标题、列表、引用、代码块、分隔线、图片、加粗/斜体/行内代码与可点击链接。
配图放在 `app/src/main/assets/help/` 下，用 `![说明](help/xxx.png)` 引用即可。

### 设置面板

结构按需求组织为：界面与运行 / 账号区 / 课程区 / 行为区 / 题库区（折叠）/ 通知区（折叠）/
配置文件参数 / **恢复出厂设置**。

* **预览文本**：显示按 `config_template.ini` 骨架渲染出的 `config.ini`，可直接编辑保存
  （保存时会反向解析回界面的各个字段）；
* **恢复默认**：二次确认后恢复为模板默认值；
* **恢复出厂设置**（最底部，危险操作）：二次确认后清空所有设置项、`run.log`、
  `chaoxing.log`、`config.ini`、`cookies.txt`、课程缓存与交互临时文件，回到首次安装状态；
* 生成的 `config.ini` **严格以 `config_template.ini` 为骨架**，只替换键值，
  注释、空行、缩进、`=` 两侧空格全部原样保留（避免上游解析踩坑）。

### 主题与系统栏

黑色 / 白色 / 跟随系统。Activity 通过 `enableEdgeToEdge()` 全屏显示，
页面根布局统一套一层 `WindowInsets.safeDrawing` 内边距，
因此内容会自动避开状态栏、刘海、导航栏与软键盘（Android 7 ~ 16 表现一致）；
状态栏/导航栏图标明暗随 App 主题切换。

---

## 五、构建

需要：

* JDK 17+（Android Studio 自带 jbr 即可）
* Android SDK（`compileSdk`/`targetSdk` 见 `app/build.gradle.kts`）
* 构建机上的 Python **3.13**（Chaquopy 用它安装依赖）

Python 解释器路径**不写死在仓库里**，按以下顺序查找：

1. `-PbuildPython=/path/to/python.exe` 或 `gradle.properties` 里的 `buildPython`
2. 环境变量 `CHAQUOPY_BUILD_PYTHON`
3. `local.properties` 里的 `buildPython`（该文件不纳入版本管理）
4. 兜底 `"3.13"`：让 Chaquopy 在 `PATH` 中查找同版本解释器

`local.properties` 示例：

```properties
sdk.dir=/path/to/Android/Sdk
buildPython=/path/to/python3.13/python.exe   # 可选
```

构建：

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 注意 1：本仓库的 `app/build.gradle.kts` 里有一个 `patchChaquopyPipInstall` 补丁，
> 用于规避某些 Windows 环境下 `os.rmdir` 对非空目录同样返回成功、
> 导致 Chaquopy 清理依赖时级联删除整个 ABI 目录的问题；在正常环境下该补丁等价且无害。
>
> 注意 2：Chaquopy 在配置阶段会启动外部 Python 进程，与 Gradle 配置缓存不兼容，
> `gradle.properties` 中已设置 `org.gradle.configuration-cache=false`。

---

## 六、目录结构

```
app/src/main/
├── java/com/cxrunner/app/
│   ├── MainActivity.kt          单 Activity + Compose 路由（主页 / 设置 / 帮助）
│   ├── RunnerApp.kt             初始化 Chaquopy / 释放 assets
│   ├── data/                    配置模型、设置持久化、运行状态、文件布局
│   ├── service/RunnerService.kt 前台服务：启动脚本、监听交互请求与结束状态
│   └── ui/                      主页、设置页、帮助页与各组件
│       └── components/Markdown.kt  ★ 极简 Markdown 渲染器
├── python/
│   ├── android_bootstrap.py     ★ 运行桥接层（日志/交互/停止/脚本执行）
│   ├── main.py, api/            上游脚本（未改动）
│   ├── openai.py                openai 接口兼容层
│   └── pyaes/                   内置 pyaes 源码
├── assets/
│   ├── resource/                上游字形映射表（启动时释放到工作目录）
│   └── help/quickstart.md       ★ 帮助页 Markdown（可放配图）
└── res/raw/config_template.ini  配置模板（生成 config.ini 的骨架）
```

---

## 七、许可证

本项目遵循 **GNU General Public License v3.0**（GPL-3.0），全文见仓库根目录的 [`LICENSE`](LICENSE)。

上游项目 [Samueli924/chaoxing](https://github.com/Samueli924/chaoxing) 同样以 GPL-3.0 发布，
本仓库作为其打包工程，属于 GPL-3.0 意义上的衍生作品，因此沿用同一许可证。

> 仅供学习交流，禁止用于盈利或商业用途。使用本工具产生的任何后果由使用者自行承担，
> 与原作者及打包者无关。
