import io

import pyzipper
import pytest

from quality import bazaar


def _aes_zip(path, name, data):
    with pyzipper.AESZipFile(path, "w", compression=pyzipper.ZIP_DEFLATED, encryption=pyzipper.WZ_AES) as z:
        z.setpassword(b"infected")
        z.writestr(name, data)


def _aes_zip_bytes(name, data):
    buf = io.BytesIO()
    with pyzipper.AESZipFile(buf, "w", compression=pyzipper.ZIP_DEFLATED, encryption=pyzipper.WZ_AES) as z:
        z.setpassword(b"infected")
        z.writestr(name, data)
    return buf.getvalue()


def test_zip이면_그대로_저장(tmp_path):
    body = _aes_zip_bytes("x.exe", b"MZ..")
    status = bazaar.fetch_sample("a" * 64, "KEY", tmp_path, post=lambda u, d, h: (200, "application/zip", body))
    assert status == "ok" and (tmp_path / ("a" * 64 + ".zip")).read_bytes() == body
    assert [p.name for p in tmp_path.iterdir()] == ["a" * 64 + ".zip"]


def test_요청에_키는_헤더로만(tmp_path):
    seen = {}

    def post(url, data, headers):
        seen.update(url=url, data=data, headers=headers)
        return (200, "application/json", b'{"query_status":"file_not_found"}')

    bazaar.fetch_sample("a" * 64, "KEY", tmp_path, post=post)
    assert seen["url"] == bazaar.API
    assert seen["headers"]["Auth-Key"] == "KEY" and "KEY" not in str(seen["data"])
    assert seen["data"] == {"query": "get_file", "sha256_hash": "a" * 64}


@pytest.mark.parametrize("status_text", ["file_not_found", "illegal_hash", "no_hash_provided"])
def test_JSON_오류는_missing(tmp_path, status_text):
    body = ('{"query_status":"%s"}' % status_text).encode()
    status = bazaar.fetch_sample("a" * 64, "KEY", tmp_path, post=lambda u, d, h: (200, "application/json", body))
    assert status == "missing" and list(tmp_path.iterdir()) == []


@pytest.mark.parametrize("body", [b'{"query_status":"weird"}', b"{}", b"not json"])
def test_알_수_없는_JSON은_error(tmp_path, body):
    status = bazaar.fetch_sample("a" * 64, "KEY", tmp_path, post=lambda u, d, h: (200, "application/json", body))
    assert status == "error" and list(tmp_path.iterdir()) == []


def test_HTTP_오류는_error(tmp_path):
    assert bazaar.fetch_sample("a" * 64, "KEY", tmp_path, post=lambda u, d, h: (500, "text/plain", b"x")) == "error"

    def boom(u, d, h):
        raise OSError("KEY leaked?")

    assert bazaar.fetch_sample("a" * 64, "KEY", tmp_path, post=boom) == "error"
    assert list(tmp_path.iterdir()) == []


def test_저장은_임시파일을_남기지_않음(tmp_path):
    body = _aes_zip_bytes("x.exe", b"MZ..")
    bazaar.fetch_sample("b" * 64, "KEY", tmp_path, post=lambda u, d, h: (200, "application/zip", body))
    assert not list(tmp_path.glob("*.tmp")) and not list(tmp_path.glob(".*"))


def test_zip_메모리에서_읽기(tmp_path):
    p = tmp_path / "s.zip"
    _aes_zip(p, "x.bin", b"abc")
    assert bazaar.read_zip_sample(p) == b"abc"


def test_zip_크기_초과는_풀지_않음(tmp_path):
    p = tmp_path / "s.zip"
    _aes_zip(p, "x.bin", b"a" * 100)
    assert bazaar.read_zip_sample(p, max_bytes=99) is None
    assert bazaar.read_zip_sample(p, max_bytes=100) == b"a" * 100


def test_빈_zip은_ZipUnreadable(tmp_path):
    p = tmp_path / "s.zip"
    with pyzipper.AESZipFile(p, "w"):
        pass
    with pytest.raises(bazaar.ZipUnreadable):
        bazaar.read_zip_sample(p)


def test_틀린_비밀번호는_ZipUnreadable(tmp_path):
    p = tmp_path / "s.zip"
    _aes_zip(p, "x.bin", b"abc")
    with pytest.raises(bazaar.ZipUnreadable):
        bazaar.read_zip_sample(p, password=b"wrong")


def test_깨진_바이트는_ZipUnreadable(tmp_path):
    p = tmp_path / "s.zip"
    p.write_bytes(b"not a zip at all")
    with pytest.raises(bazaar.ZipUnreadable):
        bazaar.read_zip_sample(p)


def test_헤더가_속여도_읽기는_상한까지만(tmp_path, monkeypatch):
    p = tmp_path / "s.zip"
    _aes_zip(p, "x.bin", b"abc")
    asked = []

    class FakeStream:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self, n=-1):
            asked.append(n)
            return b"x" * n

    monkeypatch.setattr(pyzipper.AESZipFile, "open", lambda self, *a, **k: FakeStream())
    assert bazaar.read_zip_sample(p, max_bytes=10) is None
    assert asked == [11]


def test_기본_post는_리다이렉트를_거부(tmp_path):
    import urllib.request

    handler = bazaar._NoRedirect()
    req = urllib.request.Request(bazaar.API, data=b"x", headers={"Auth-Key": "KEY"})
    assert handler.redirect_request(req, None, 302, "Found", {}, "https://evil.example/") is None
    assert any(isinstance(h, bazaar._NoRedirect) for h in bazaar._OPENER.handlers)
    assert not any(type(h) is urllib.request.HTTPRedirectHandler for h in bazaar._OPENER.handlers)
