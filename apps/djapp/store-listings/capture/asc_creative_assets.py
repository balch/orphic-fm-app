#!/usr/bin/env python3
"""Upload, submit and place the App Store Header and Search Results assets.

Three stages, because Apple reviews an asset before it can be placed:
  upload  reserve each PNG in the app's asset library, send the bytes, wait for processing
  submit  put the uploaded images in one review submission and submit it
  place   after approval, attach each image to a version localization

Credentials come from the environment, never from this file:
  ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (the .p8). Needs pyjwt[crypto] and requests.
"""
import argparse, json, os, pathlib, sys, time
import jwt, requests

API = "https://api.appstoreconnect.apple.com"
CREATIVE = pathlib.Path(__file__).resolve().parent.parent / "app-store" / "creative"
# file -> placement type. Apple derives the spec from the pixel size, so the name is only ours.
ASSETS = {
    "header-3840x1646.png": "PRODUCT_PAGE_HEADER_ASSET",
    "search-results-3840x2560.png": "APP_STORE_SEARCH_RESULTS_ASSET",
}


def token():
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 900, "aud": "appstoreconnect-v1"},
        pathlib.Path(os.environ["ASC_KEY_PATH"]).read_text(),
        algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"]},
    )


def call(method, path, body=None, params=None):
    r = requests.request(method, API + path, json=body, params=params,
                         headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    try:
        return r.status_code, r.json()
    except ValueError:
        return r.status_code, r.text


def must(status, payload, ok):
    if status not in ok:
        sys.exit(f"HTTP {status}: {json.dumps(payload, indent=1)[:2000]}")
    return payload


def library_images(app):
    return must(*call("GET", f"/v1/appAssetLibraries/{app}/images", params={"limit": 200}), ok=(200,))["data"]


def ours(app):
    """Newest library image for each of our files, keyed by file name."""
    found = {}
    for im in sorted(library_images(app), key=lambda i: i["attributes"]["createdDate"]):
        if im["attributes"]["fileName"] in ASSETS:
            found[im["attributes"]["fileName"]] = im
    return found


def upload(app):
    for name in ASSETS:
        data = (CREATIVE / name).read_bytes()
        body = {"data": {"type": "appAssetLibraryImages",
                         "attributes": {"fileName": name, "fileSize": len(data), "category": "CREATIVE_ASSETS"},
                         "relationships": {"assetLibrary": {"data": {"type": "appAssetLibraries", "id": app}}}}}
        img = must(*call("POST", "/v1/appAssetLibraryImages", body), ok=(201,))["data"]
        for op in img["attributes"]["uploadOperations"]:
            headers = {h["name"]: h["value"] for h in op["requestHeaders"]}
            chunk = data[op["offset"]:op["offset"] + op["length"]]
            r = requests.request(op["method"], op["url"], headers=headers, data=chunk)
            if r.status_code >= 300:
                sys.exit(f"upload of {name} failed: {r.status_code} {r.text[:300]}")
        must(*call("PATCH", f"/v1/appAssetLibraryImages/{img['id']}",
                   {"data": {"type": "appAssetLibraryImages", "id": img["id"], "attributes": {"uploaded": True}}}),
             ok=(200,))
        for _ in range(40):  # processing took a few seconds to a couple of minutes when measured
            state = must(*call("GET", f"/v1/appAssetLibraryImages/{img['id']}"), ok=(200,))["data"]["attributes"]
            if state["state"] not in ("AWAITING_UPLOAD", "UPLOAD_COMPLETE"):
                break
            time.sleep(6)
        print(name, img["id"], state["state"], state.get("stateDetails"))


def submit(app):
    images = ours(app)
    missing = [n for n in ASSETS if n not in images]
    if missing:
        sys.exit(f"not uploaded yet: {missing}")
    sub = must(*call("POST", "/v1/reviewSubmissions",
                     {"data": {"type": "reviewSubmissions", "attributes": {"platform": "IOS"},
                               "relationships": {"app": {"data": {"type": "apps", "id": app}}}}}), ok=(201,))["data"]
    for name, im in images.items():
        must(*call("POST", "/v1/reviewSubmissionItems",
                   {"data": {"type": "reviewSubmissionItems", "relationships": {
                       "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": sub["id"]}},
                       "appAssetLibraryImage": {"data": {"type": "appAssetLibraryImages", "id": im["id"]}}}}}),
             ok=(201,))
        print("added", name)
    done = must(*call("PATCH", f"/v1/reviewSubmissions/{sub['id']}",
                      {"data": {"type": "reviewSubmissions", "id": sub["id"], "attributes": {"submitted": True}}}),
                ok=(200,))
    print("submission", sub["id"], done["data"]["attributes"]["state"])


def place(app, localization):
    for name, im in ours(app).items():
        if im["attributes"]["state"] != "APPROVED":
            sys.exit(f"{name} is {im['attributes']['state']}, not APPROVED")
        body = {"data": {"type": "appAssetLibraryPlacements",
                         "attributes": {"placementType": ASSETS[name], "placementGroup": "DEFAULT_PROFILE"},
                         "relationships": {
                             "image": {"data": {"type": "appAssetLibraryImages", "id": im["id"]}},
                             "appStoreVersionLocalization": {
                                 "data": {"type": "appStoreVersionLocalizations", "id": localization}}}}}
        placed = must(*call("POST", "/v1/appAssetLibraryPlacements", body), ok=(201,))
        print("placed", name, placed["data"]["attributes"]["state"])


def status(app):
    for name, im in ours(app).items():
        a = im["attributes"]
        print(name, im["id"], a["state"], a.get("stateDetails"), a.get("imageAsset", {}).get("width"))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=("upload", "submit", "place", "status"))
    ap.add_argument("--app", required=True, help="App Store Connect app id")
    ap.add_argument("--localization", help="appStoreVersionLocalization id, for place")
    a = ap.parse_args()
    if a.stage == "place":
        if not a.localization:
            sys.exit("place needs --localization")
        place(a.app, a.localization)
    else:
        {"upload": upload, "submit": submit, "status": status}[a.stage](a.app)
