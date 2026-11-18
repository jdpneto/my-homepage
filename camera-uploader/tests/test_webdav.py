import httpx
import pytest

from camera_uploader.webdav import AuthError, QuotaFullError, TransientError, WebDavClient, WebDavError

BASE = "https://cloud.davidneto.eu/"


class Recorder:
    """httpx transport that records requests and answers from a status table."""

    def __init__(self, statuses=None, default=201, raise_exc=None):
        self.requests: list[httpx.Request] = []
        self.bodies: list[bytes] = []
        self.statuses = statuses or {}
        self.default = default
        self.raise_exc = raise_exc

    async def handle(self, request: httpx.Request) -> httpx.Response:
        body = b"".join([chunk async for chunk in request.stream])
        self.requests.append(request)
        self.bodies.append(body)
        if self.raise_exc:
            raise self.raise_exc
        return httpx.Response(self.statuses.get((request.method, request.url.raw_path.decode()), self.default))

    def client(self):
        return WebDavClient(BASE, "cameras", "pw", transport=httpx.MockTransport(self.handle))


async def test_ensure_dirs_mkcols_each_parent_in_order():
    rec = Recorder()
    await rec.client().ensure_dirs("Backyard/2026-11-16/19-22-58")
    assert [(r.method, r.url.raw_path.decode()) for r in rec.requests] == [
        ("MKCOL", "/Backyard/"), ("MKCOL", "/Backyard/2026-11-16/")]


async def test_ensure_dirs_tolerates_existing_collections():
    rec = Recorder(default=405)
    await rec.client().ensure_dirs("Backyard/2026-11-16/19-22-58")  # no exception


async def test_put_file_sends_content_length_not_chunked(tmp_path):
    src = tmp_path / "clip.mp4"
    src.write_bytes(b"x" * 300_000)
    rec = Recorder()
    await rec.client().put_file("Backyard/2026-11-16/19-22-58.mp4", src, "video/mp4")
    req = rec.requests[0]
    assert req.method == "PUT"
    assert req.headers["content-length"] == "300000"
    assert "transfer-encoding" not in req.headers
    assert req.headers["content-type"] == "video/mp4"
    assert rec.bodies[0] == b"x" * 300_000


async def test_put_bytes_204_is_success():
    rec = Recorder(default=204)
    await rec.client().put_bytes("Backyard/2026-11-16/19-22-58.json", b"{}", "application/json")
    assert rec.requests[0].headers["content-length"] == "2"


async def test_basic_auth_sent():
    rec = Recorder()
    await rec.client().put_bytes("a.json", b"{}", "application/json")
    assert rec.requests[0].headers["authorization"].startswith("Basic ")


@pytest.mark.parametrize("status,error", [(507, QuotaFullError), (401, AuthError), (403, AuthError),
                                          (502, TransientError), (500, TransientError), (409, WebDavError)])
async def test_status_mapping(status, error):
    rec = Recorder(default=status)
    with pytest.raises(error):
        await rec.client().put_bytes("a.json", b"{}", "application/json")


async def test_quota_full_is_not_transient():
    assert not issubclass(QuotaFullError, TransientError)


async def test_connect_error_is_transient():
    rec = Recorder(raise_exc=httpx.ConnectError("refused"))
    with pytest.raises(TransientError):
        await rec.client().put_bytes("a.json", b"{}", "application/json")


async def test_timeout_is_transient():
    rec = Recorder(raise_exc=httpx.ReadTimeout("slow"))
    with pytest.raises(TransientError):
        await rec.client().put_bytes("a.json", b"{}", "application/json")


async def test_non_ascii_and_spaces_are_percent_encoded():
    rec = Recorder()
    await rec.client().put_bytes("Cozinha é/2026-11-16/19-22-58.json", b"{}", "application/json")
    assert rec.requests[0].url.raw_path.decode() == "/Cozinha%20%C3%A9/2026-11-16/19-22-58.json"


async def test_get_text_returns_body_on_200_and_none_on_404():
    async def handle(request):
        if request.url.raw_path == b"/volume-marker.txt":
            return httpx.Response(200, text="camera clips live on the 15 GB volume - do not delete\n")
        return httpx.Response(404)
    client = WebDavClient(BASE, "cameras", "pw", transport=httpx.MockTransport(handle))
    assert (await client.get_text("volume-marker.txt")).startswith("camera clips live on the 15 GB volume")
    assert await client.get_text("nope.txt") is None


async def test_get_text_uses_get_not_propfind():
    # The real server answers 207 to PROPFIND on paths that do not exist.
    rec = Recorder(default=404)
    await rec.client().get_text("volume-marker.txt")
    assert rec.requests[0].method == "GET"


async def test_get_text_auth_failure_raises():
    rec = Recorder(default=401)
    with pytest.raises(AuthError):
        await rec.client().get_text("volume-marker.txt")


async def test_base_url_without_trailing_slash():
    rec = Recorder()
    client = WebDavClient("https://cloud.davidneto.eu", "cameras", "pw", transport=httpx.MockTransport(rec.handle))
    await client.put_bytes("a.json", b"{}", "application/json")
    assert str(rec.requests[0].url) == "https://cloud.davidneto.eu/a.json"
