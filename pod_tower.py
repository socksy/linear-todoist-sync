#!/usr/bin/env python3
"""Babashka pod exposing the entire Tower API SDK."""

import importlib
import inspect
import json
import os
import pkgutil
import sys
import typing

from bcoding import bencode, bdecode

import tower.tower_api_client.api.default as _default_api
from tower.tower_api_client import AuthenticatedClient


def _make_client():
    url = os.environ.get("TOWER_URL", "https://api.tower.dev").rstrip("/") + "/v1"
    return AuthenticatedClient(
        base_url=url,
        token=os.environ.get("BENS_TOWER_API_KEY") or os.environ.get("TOWER_API_KEY") or "",
        auth_header_name="X-API-Key",
        prefix="",
        verify_ssl=False,
        raise_on_unexpected_status=True,
    )


def _snake_keys(obj):
    if isinstance(obj, dict):
        return {k.replace("-", "_"): _snake_keys(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_snake_keys(x) for x in obj]
    return obj


def _kebab_keys(obj):
    if isinstance(obj, dict):
        return {k.replace("_", "-"): _kebab_keys(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_kebab_keys(x) for x in obj]
    return obj


def _convert_arg(value, annotation):
    if annotation is None or not isinstance(value, dict):
        return value
    origin = getattr(annotation, "__origin__", None)
    if origin is typing.Union:
        for arg in annotation.__args__:
            if arg is type(None):
                continue
            if hasattr(arg, "from_dict"):
                return arg.from_dict(value)
        return value
    if hasattr(annotation, "from_dict"):
        return annotation.from_dict(value)
    return value


def _serialize(result):
    if result is None:
        return None
    if hasattr(result, "to_dict"):
        return _kebab_keys(result.to_dict())
    return result


def _discover_api_fns():
    fns = {}
    for _, modname, _ in pkgutil.iter_modules(_default_api.__path__):
        mod = importlib.import_module(f"tower.tower_api_client.api.default.{modname}")
        if not hasattr(mod, "sync"):
            continue
        name = modname.replace("_", "-")
        hints = typing.get_type_hints(mod.sync)
        params = {}
        for pname in inspect.signature(mod.sync).parameters:
            if pname == "client":
                continue
            params[pname.rstrip("_")] = (pname, hints.get(pname))
        body_only = (list(params.keys()) == ["body"]
                     and hasattr(params["body"][1], "from_dict"))
        fns[name] = (mod.sync, params, body_only)
    return fns


def _encrypt_secret(public_key_pem, plaintext):
    import base64
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    public_key = serialization.load_pem_public_key(public_key_pem.encode())
    aes_key = os.urandom(32)
    iv = os.urandom(12)
    ciphertext = AESGCM(aes_key).encrypt(iv, plaintext.encode("utf-8"), None)
    encrypted_key = public_key.encrypt(
        aes_key,
        padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()),
                     algorithm=hashes.SHA256(), label=None))
    return base64.b64encode(encrypted_key + iv + ciphertext).decode("utf-8")


def _preview(value):
    return "X" * max(0, len(value) - 4) + value[-4:]


EXTRA_FNS = {
    "encrypt-secret": lambda args: _encrypt_secret(args[0], args[1]),
    "secret-preview": lambda args: _preview(args[0]),
}


def read():
    return dict(bdecode(sys.stdin.buffer))


def write(obj):
    sys.stdout.buffer.write(bencode(obj))
    sys.stdout.buffer.flush()


def main():
    client = _make_client()
    api_fns = _discover_api_fns()

    raw_vars = [{"name": n} for n in list(api_fns) + list(EXTRA_FNS)]

    wrapper_vars = []
    for name in api_fns:
        wrapper_vars.append({
            "name": name,
            "code": f"(defn {name} [& {{:as opts}}] (pod.tower.raw/{name} opts))"})
    for name in EXTRA_FNS:
        wrapper_vars.append({"name": name, "code": f"(def {name} pod.tower.raw/{name})"})

    while True:
        msg = read()
        op = msg.get("op", "")

        if op == "describe":
            write({
                "format": "json",
                "namespaces": [
                    {"name": "pod.tower.raw", "vars": raw_vars},
                    {"name": "pod.tower", "vars": wrapper_vars},
                ],
                "ops": {"shutdown": {}},
            })

        elif op == "invoke":
            var = msg["var"].split("/")[-1]
            id_ = msg["id"]
            args = json.loads(msg["args"])

            try:
                if var in EXTRA_FNS:
                    result = EXTRA_FNS[var](args)
                else:
                    sync_fn, params, body_only = api_fns[var]
                    opts = _snake_keys(args[0]) if args and isinstance(args[0], dict) else {}
                    if body_only:
                        kwargs = {"body": _convert_arg(opts, params["body"][1])}
                    else:
                        kwargs = {}
                        for key, (pname, ann) in params.items():
                            if key in opts:
                                kwargs[pname] = _convert_arg(opts[key], ann)
                    result = sync_fn(client=client, **kwargs)

                write({"value": json.dumps(_serialize(result)),
                       "id": id_, "status": ["done"]})
            except Exception as e:
                write({"ex-message": str(e),
                       "ex-data": json.dumps({"type": type(e).__name__}),
                       "id": id_, "status": ["done", "error"]})

        elif op == "shutdown":
            sys.exit(0)


if __name__ == "__main__":
    main()
