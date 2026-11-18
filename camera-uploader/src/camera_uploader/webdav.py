"""Minimal WebDAV client for the homepage's per-user WebDAV (user `cameras`)."""

from __future__ import annotations

from pathlib import Path
from urllib.parse import quote

import httpx

CHUNK = 256 * 1024


class WebDavError(Exception):
    """A WebDAV request failed in a way retrying won't fix by itself."""


class AuthError(WebDavError):
    """401/403: wrong credentials. Back off hard: the server locks out repeated failures."""


class QuotaFullError(WebDavError):
    """507: the `cameras` user's quota is full."""


class TransientError(WebDavError):
    """Network trouble or a 5xx: try again later."""


class WebDavClient:
    def __init__(self, base_url: str, user: str, password: str,
                 transport: httpx.AsyncBaseTransport | None = None):
        self._base = base_url.rstrip("/") + "/"
        self._http = httpx.AsyncClient(
            auth=httpx.BasicAuth(user, password),
            timeout=httpx.Timeout(300.0, connect=30.0),
            transport=transport,
            follow_redirects=False,
        )

    def _url(self, path: str, collection: bool = False) -> str:
        segments = [quote(s, safe="") for s in path.strip("/").split("/") if s]
        return self._base + "/".join(segments) + ("/" if collection and segments else "")

    async def _send(self, method: str, url: str, ok: tuple[int, ...], **kwargs) -> httpx.Response:
        try:
            response = await self._http.request(method, url, **kwargs)
        except httpx.TransportError as exc:  # connect/read/write errors and timeouts
            raise TransientError(f"{method} {url}: {type(exc).__name__}: {exc}") from exc
        status = response.status_code
        if status in ok:
            return response
        if status in (401, 403):
            raise AuthError(f"{method} {url}: HTTP {status}")
        if status == 507:
            raise QuotaFullError(f"{method} {url}: HTTP 507 quota full")
        if status >= 500:
            raise TransientError(f"{method} {url}: HTTP {status}")
        raise WebDavError(f"{method} {url}: HTTP {status}")

    async def ensure_dirs(self, stem: str) -> None:
        parts = stem.strip("/").split("/")[:-1]
        for i in range(1, len(parts) + 1):
            await self._send("MKCOL", self._url("/".join(parts[:i]), collection=True), ok=(201, 405))

    async def put_file(self, path: str, src: Path, content_type: str) -> None:
        size = src.stat().st_size

        async def body():
            with src.open("rb") as f:
                while chunk := f.read(CHUNK):
                    yield chunk

        await self._send("PUT", self._url(path), ok=(201, 204), content=body(),
                         headers={"Content-Length": str(size), "Content-Type": content_type})

    async def put_bytes(self, path: str, data: bytes, content_type: str) -> None:
        await self._send("PUT", self._url(path), ok=(201, 204), content=data,
                         headers={"Content-Type": content_type})

    async def exists(self, path: str) -> bool:
        response = await self._send("PROPFIND", self._url(path), ok=(207, 404), headers={"Depth": "0"})
        return response.status_code == 207

    async def aclose(self) -> None:
        await self._http.aclose()
