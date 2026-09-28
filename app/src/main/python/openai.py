# -*- coding: utf-8 -*-
"""
openai 兼容层（Android 专用轻量实现）

原因：官方 openai SDK 依赖 pydantic-core（Rust 扩展），Android 平台没有可用 wheel，
无法在 Chaquopy 中通过 pip 安装。本模块仅实现 chaoxing 项目实际用到的接口：

    OpenAI(base_url=..., api_key=..., http_client=httpx.Client(...))
    client.chat.completions.create(model=..., messages=[...], max_tokens=..., extra_body=...)
    completion.choices[0].message.content / .reasoning_content

行为与 OpenAI 官方 /v1/chat/completions 接口保持一致，使用 httpx 直接发起请求。
"""

import json as _json

try:
    import httpx
except ImportError:  # pragma: no cover
    httpx = None


class _Message:
    def __init__(self, content, reasoning_content=None):
        self.content = content
        self.reasoning_content = reasoning_content
        self.role = "assistant"

    def __repr__(self):
        return "ChoiceMessage(content=%r)" % (self.content,)


class _Choice:
    def __init__(self, message):
        self.message = message
        self.index = 0
        self.finish_reason = "stop"

    def __repr__(self):
        return "Choice(message=%r)" % (self.message,)


class _Completion:
    def __init__(self, choices, raw=None):
        self.choices = choices
        self.raw = raw or {}

    def __repr__(self):
        return "ChatCompletion(choices=%d)" % len(self.choices)


class _Completions:
    def __init__(self, client):
        self._client = client

    def create(self, model=None, messages=None, **kwargs):
        payload = {"model": model, "messages": messages or []}

        # 官方 SDK 支持的参数，直接透传
        for key in (
            "temperature", "top_p", "max_tokens", "max_completion_tokens",
            "stream", "stop", "presence_penalty", "frequency_penalty",
            "seed", "n", "response_format", "tools", "tool_choice", "user",
        ):
            if key in kwargs and kwargs[key] is not None:
                payload[key] = kwargs[key]

        # chaoxing 使用 extra_body 传递厂商私有字段（如关闭思考模式）
        extra_body = kwargs.pop("extra_body", None)
        if isinstance(extra_body, dict):
            payload.update(extra_body)

        data = self._client._post(payload)
        if data is None:
            raise RuntimeError("大模型未返回有效内容")

        choices = []
        raw_choices = data.get("choices") or []
        if not raw_choices:
            raise RuntimeError("大模型返回的 choices 为空")

        for item in raw_choices:
            msg = item.get("message") or {}
            choices.append(
                _Choice(
                    _Message(
                        content=msg.get("content"),
                        reasoning_content=msg.get("reasoning_content"),
                    )
                )
            )
        return _Completion(choices, data)


class _Chat:
    def __init__(self, client):
        self.completions = _Completions(client)


class OpenAI:
    """openai.OpenAI 的最小可用替代实现。"""

    def __init__(self, base_url=None, api_key=None, http_client=None, timeout=120.0, **kwargs):
        self.base_url = (base_url or "https://api.openai.com/v1").rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self._external_client = http_client
        self._own_client = None
        self.chat = _Chat(self)

    # ------------------------------------------------------------------ #
    def _client(self):
        if self._external_client is not None:
            return self._external_client
        if self._own_client is None:
            if httpx is None:
                raise RuntimeError("缺少 httpx 依赖，无法调用大模型接口")
            self._own_client = httpx.Client(timeout=self.timeout)
        return self._own_client

    def _endpoint(self):
        if self.base_url.endswith("/chat/completions"):
            return self.base_url
        return self.base_url + "/chat/completions"

    def _post(self, payload):
        headers = {
            "Content-Type": "application/json",
            "Authorization": "Bearer %s" % (self.api_key or ""),
        }
        client = self._client()
        resp = client.post(self._endpoint(), headers=headers, json=payload)
        text = resp.text if hasattr(resp, "text") else ""
        try:
            data = _json.loads(text)
        except Exception:
            raise RuntimeError("大模型返回非 JSON 内容 (HTTP %s)" % getattr(resp, "status_code", "?"))
        if getattr(resp, "status_code", 200) >= 400:
            raise RuntimeError("大模型请求失败 (HTTP %s): %s" % (resp.status_code, text[:300]))
        return data

    def close(self):
        if self._own_client is not None:
            try:
                self._own_client.close()
            except Exception:
                pass


class AzureOpenAI(OpenAI):
    """占位实现，项目未使用。"""
