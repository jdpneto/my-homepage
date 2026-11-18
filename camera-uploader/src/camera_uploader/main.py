"""Entry point: the forever loop the LaunchDaemon runs, plus `--once` for manual checks."""

from __future__ import annotations

import argparse
import asyncio
import logging
import shutil
import sys
from datetime import datetime
from logging.handlers import RotatingFileHandler
from pathlib import Path

from . import __version__
from .config import Config, ConfigError, load_config
from .cycle import run_cycle
from .hub import HubAuthError, HubError, ReolinkHub
from .state import StateStore
from .webdav import AuthError, QuotaFullError, TransientError, WebDavClient, WebDavError

log = logging.getLogger("camera_uploader")

DEFAULT_CONFIG = Path("/usr/local/etc/camera-uploader/config.toml")
CLOCK_SKEW_WARN = 300  # seconds


async def _cycle_now(hub, clock) -> datetime:
    """Use the hub's clock: clip timestamps are hub-local, so the search window must be too."""
    hub_now = await hub.now()
    skew = abs((hub_now - clock()).total_seconds())
    if skew > CLOCK_SKEW_WARN:
        log.warning("hub clock differs from this Mac's clock by %.0f s - using the hub clock "
                    "(check the hub's time zone / DST setting)", skew)
    return hub_now


def backoff_seconds(error: Exception, consecutive: int) -> float:
    if isinstance(error, (AuthError, HubAuthError)):
        return 900  # both the homepage and the hub lock accounts out after repeated bad logins
    if isinstance(error, QuotaFullError):
        return 600
    if isinstance(error, (HubError, TransientError)):
        return min(30 * 2 ** (max(consecutive, 1) - 1), 300)
    return 300


def prepare_tmp(tmp_dir: Path) -> None:
    """Start with an empty temp dir: leftovers from a crash or power cut never pile up."""
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)
    tmp_dir.mkdir(parents=True, exist_ok=True)


def setup_logging(log_path: Path) -> None:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s")
    file_handler = RotatingFileHandler(log_path, maxBytes=10_000_000, backupCount=5)
    file_handler.setFormatter(fmt)
    console = logging.StreamHandler(sys.stderr)
    console.setFormatter(fmt)
    # Interactive (--once by hand): everything. Under launchd: errors only - launchd's
    # stderr file is never rotated; the rotating file has the rest.
    console.setLevel(logging.INFO if sys.stderr.isatty() else logging.ERROR)
    root = logging.getLogger()
    root.handlers[:] = [file_handler, console]
    root.setLevel(logging.INFO)
    logging.getLogger("reolink_aio").setLevel(logging.WARNING)
    # Logs a WARNING every 0.5 s while the hub is unreachable; our own HubError line covers it.
    logging.getLogger("reolink_aio.baichuan.udp_protocol").setLevel(logging.ERROR)
    logging.getLogger("httpx").setLevel(logging.WARNING)


async def _close_hub(hub) -> None:
    if hub is not None:
        try:
            await hub.close()
        except Exception as exc:
            log.debug("hub close failed: %s", exc)


async def run_forever(cfg: Config, hub_factory, dav_factory, sleep=asyncio.sleep, clock=datetime.now) -> None:
    prepare_tmp(cfg.tmp_dir)
    store = StateStore(cfg.state_path)
    dav = dav_factory(cfg)
    hub = None
    consecutive = 0
    log.info("camera-uploader %s starting: %r", __version__, cfg)
    try:
        while True:
            try:
                if hub is None:
                    hub = hub_factory(cfg)
                    await hub.connect()
                result = await run_cycle(hub, dav, store, cfg, await _cycle_now(hub, clock))
                if result.uploaded or result.failed:
                    log.info("cycle: %d uploaded, %d pending, %d failed, %d already done",
                             result.uploaded, result.pending, result.failed, result.skipped)
                consecutive = 0
                delay = cfg.poll_seconds
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                consecutive += 1
                delay = backoff_seconds(exc, consecutive)
                known = isinstance(exc, (HubError, WebDavError))
                if isinstance(exc, HubError) or not known:
                    # Hub trouble or something unexpected: start a fresh hub session next time.
                    await _close_hub(hub)
                    hub = None
                level = logging.WARNING if isinstance(exc, (HubError, TransientError)) else logging.ERROR
                log.log(level, "%s: %s - retrying in %ds (failure #%d)", type(exc).__name__, exc, delay, consecutive,
                        exc_info=not known)
            await sleep(delay)
    finally:
        await _close_hub(hub)
        await dav.aclose()
        store.close()


async def run_once(cfg: Config, hub_factory, dav_factory, clock=datetime.now) -> int:
    prepare_tmp(cfg.tmp_dir)
    store = StateStore(cfg.state_path)
    dav = dav_factory(cfg)
    hub = hub_factory(cfg)
    try:
        await hub.connect()
        result = await run_cycle(hub, dav, store, cfg, await _cycle_now(hub, clock))
        log.info("once: %d uploaded, %d pending, %d failed, %d already done",
                 result.uploaded, result.pending, result.failed, result.skipped)
        return 0
    except Exception as exc:
        log.error("once failed: %s: %s", type(exc).__name__, exc)
        return 1
    finally:
        await _close_hub(hub)
        await dav.aclose()
        store.close()


async def serve(cfg: Config, hub_factory, dav_factory, sleep=asyncio.sleep, clock=datetime.now) -> None:
    """run_forever, stopped cleanly (hub logged out, state closed) on SIGTERM/SIGINT from launchd."""
    import signal

    loop = asyncio.get_running_loop()
    task = asyncio.ensure_future(run_forever(cfg, hub_factory, dav_factory, sleep=sleep, clock=clock))
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, task.cancel)
    try:
        await task
    except asyncio.CancelledError:
        log.info("stopped by signal")
    finally:
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.remove_signal_handler(sig)


def _dav_factory(cfg: Config) -> WebDavClient:
    return WebDavClient(cfg.webdav_url, cfg.webdav_user, cfg.webdav_password)


def cli(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="camera-uploader", description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--once", action="store_true", help="run one cycle and exit (non-zero on error)")
    args = parser.parse_args(argv)
    try:
        cfg = load_config(args.config)
    except ConfigError as exc:
        print(f"camera-uploader: {exc}", file=sys.stderr)
        return 2
    setup_logging(cfg.log_path)
    if args.once:
        return asyncio.run(run_once(cfg, ReolinkHub, _dav_factory))
    asyncio.run(serve(cfg, ReolinkHub, _dav_factory))
    return 0


if __name__ == "__main__":
    sys.exit(cli())
