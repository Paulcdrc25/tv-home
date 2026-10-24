#!/usr/bin/env python3
"""Connect the home screen's Spotify widget to your account (one time).

1. https://developer.spotify.com/dashboard -> Create app. Redirect URI: http://127.0.0.1:8888/callback
   API: Web API. Copy the Client ID and Client secret.
2. python3 tools/spotify-connect.py <client_id> <client_secret>
   Your browser opens; log in to Spotify and accept. The TV gets the token, nothing else leaves your Mac.
"""
import base64
import http.server
import json
import os
import secrets
import subprocess
import sys
import urllib.parse
import urllib.request
import webbrowser

REDIRECT = "http://127.0.0.1:8888/callback"
SCOPES = "user-read-recently-played user-read-currently-playing"
HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    cid, secret = sys.argv[1], sys.argv[2]
    state = secrets.token_urlsafe(16)
    got = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if q.get("state", [""])[0] == state and "code" in q:
                got["code"] = q["code"][0]
                msg = "Spotify connecté. Tu peux fermer cet onglet."
            else:
                got["error"] = q.get("error", ["bad state"])[0]
                msg = "Erreur : " + got["error"]
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(f"<h2 style='font-family:sans-serif'>{msg}</h2>".encode())

        def log_message(self, *a):
            pass

    url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode(
        {"client_id": cid, "response_type": "code", "redirect_uri": REDIRECT, "scope": SCOPES, "state": state})
    print("Opening Spotify login in your browser…\n" + url)
    webbrowser.open(url)
    server = http.server.HTTPServer(("127.0.0.1", 8888), Handler)
    while not got:
        server.handle_request()
    if "code" not in got:
        sys.exit("Spotify said: " + got["error"])

    req = urllib.request.Request(
        "https://accounts.spotify.com/api/token",
        data=urllib.parse.urlencode({"grant_type": "authorization_code", "code": got["code"],
                                     "redirect_uri": REDIRECT}).encode(),
        headers={"Authorization": "Basic " + base64.b64encode(f"{cid}:{secret}".encode()).decode(),
                 "Content-Type": "application/x-www-form-urlencoded"})
    tok = json.load(urllib.request.urlopen(req))
    refresh = tok["refresh_token"]

    # Hand the credentials to the launcher; it stores them in its private preferences.
    cmd = ("am start -n com.mihir.tvhome/.MainActivity"
           f" --es sp_id {cid} --es sp_secret {secret} --es sp_refresh {refresh}")
    py = os.path.join(HERE, "venv", "bin", "python")
    subprocess.run([py, "-I", os.path.join(HERE, "tv.py"), cmd], check=True, stdout=subprocess.DEVNULL)
    print("Done. The Spotify widget on the TV updates within a few seconds.")


if __name__ == "__main__":
    main()
