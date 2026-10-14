#!/usr/bin/env python3
"""Tiny GitHub API mock for tests/test_p13.sh. Usage: mock_github.py <port-file> <log-dir>"""
import json, sys, os
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

LOG = sys.argv[2]
def repo(name, **kw):
    r = {"full_name": "alif/" + name, "name": name, "private": False, "fork": False, "archived": False,
         "default_branch": "main", "html_url": "https://github.com/alif/" + name, "permissions": {"push": True}}
    r.update(kw); return r
PRS = []
NISSUE = [0]
REPOS = {"site": repo("site"), "forked": repo("forked", fork=True), "old": repo("old", archived=True),
         "ro": repo("ro", permissions={"push": False}), "legacy": repo("legacy", default_branch="master")}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _send(self, code, body, headers=None):
        b = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(b)
    def _rec(self, extra=""):
        with open(os.path.join(LOG, "requests.log"), "a") as f:
            f.write("%s %s auth=%s %s\n" % (self.command, self.path, self.headers.get("Authorization", ""), extra))
    def do_GET(self):
        self._rec(); u = urlparse(self.path); p = u.path; q = parse_qs(u.query)
        if p == "/status/401": return self._send(401, {"message": "Bad credentials"})
        if p == "/status/403rl": return self._send(403, {"message": "rate limit exceeded"}, {"X-RateLimit-Remaining": "0"})
        if p == "/status/403": return self._send(403, {"message": "Resource not accessible by personal access token"})
        if p == "/status/500": return self._send(500, {"message": "boom"})
        if p == "/status/422": return self._send(422, {"message": "Validation Failed"})
        if p == "/user": return self._send(200, {"login": "alif", "id": 42, "name": "Alif"})
        if p == "/user/repos":
            pg = int(q.get("page", ["1"])[0])
            if pg == 1: return self._send(200, [repo("bulk%03d" % i) for i in range(100)])
            if pg == 2: return self._send(200, [REPOS["site"], REPOS["forked"]])
            return self._send(200, [])
        parts = p.strip("/").split("/")
        if len(parts) >= 3 and parts[0] == "repos" and parts[1] == "alif" and parts[2] in REPOS:
            if len(parts) == 3: return self._send(200, REPOS[parts[2]])
            if parts[3] == "branches": return self._send(200, [{"name": "dev"}, {"name": "main"}])
            if parts[3] == "pulls" and len(parts) == 4:
                head = q.get("head", [""])[0].split(":", 1)[-1]
                return self._send(200, [x for x in PRS if x["head"]["ref"] == head and x["state"] == "open"][:1])
        return self._send(404, {"message": "Not Found"})
    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0")); body = self.rfile.read(n).decode()
        self._rec("body=" + body)
        parts = self.path.strip("/").split("/")
        if parts[:3] == ["repos", "alif", "site"] and len(parts) == 4 and parts[3] in ("issues", "pulls"):
            d = json.loads(body)
            if not d.get("title"): return self._send(422, {"message": "Validation Failed"})
            NISSUE[0] += 1; n = NISSUE[0]
            o = {"number": n, "html_url": "https://github.com/alif/site/%s/%d" % ("pull" if parts[3] == "pulls" else "issues", n)}
            if parts[3] == "pulls":
                o.update({"state": "open", "head": {"ref": d["head"]}, "base": {"ref": d["base"]}}); PRS.append(o)
            return self._send(201, o)
        if self.path != "/user/repos": return self._send(404, {"message": "Not Found"})
        d = json.loads(body)
        if d["name"] == "exists": return self._send(422, {"message": "name already exists on this account"})
        return self._send(201, repo(d["name"], private=d["private"]))

    def do_PUT(self):
        n = int(self.headers.get("Content-Length", "0")); body = self.rfile.read(n).decode()
        self._rec("body=" + body)
        parts = self.path.strip("/").split("/")
        if len(parts) == 6 and parts[:3] == ["repos", "alif", "site"] and parts[3] == "pulls" and parts[5] == "merge":
            if json.loads(body).get("merge_method") == "rebase": return self._send(405, {"message": "Rebase merges are not allowed"})
            for x in PRS:
                if str(x["number"]) == parts[4]: x["state"] = "closed"; return self._send(200, {"merged": True, "sha": "a" * 40})
        return self._send(404, {"message": "Not Found"})
    def do_DELETE(self):
        self._rec()
        if self.path.startswith("/repos/alif/site/git/refs/heads/"):
            self.send_response(204); self.send_header("Content-Length", "0"); self.end_headers(); return
        return self._send(404, {"message": "Not Found"})

srv = HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(srv.server_port))
srv.serve_forever()
