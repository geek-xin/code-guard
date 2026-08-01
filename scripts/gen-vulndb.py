#!/usr/bin/env python3
"""从 OSV.dev 生成离线漏洞种子库 config/vulndb/codeguard-vulndb.json"""
import json, urllib.request, sys

OSV = "https://api.osv.dev/v1/query"

PACKAGES = {
    "npm": ["lodash", "minimist", "axios", "jsonwebtoken", "tar", "express", "ejs", "next",
            "ansi-regex", "glob-parent", "node-fetch", "qs", "path-parse", "shelljs", "vm2",
            "handlebars", "moment", "pug", "serialize-javascript", "semver", "undici", "request"],
    "Maven": ["org.apache.logging.log4j:log4j-core", "org.springframework:spring-core",
              "org.springframework:spring-webmvc", "org.yaml:snakeyaml", "org.apache.commons:commons-text",
              "com.alibaba:fastjson", "com.fasterxml.jackson.core:jackson-databind",
              "org.apache.tomcat.embed:tomcat-embed-core", "commons-fileupload:commons-fileupload",
              "org.apache.shiro:shiro-core", "org.apache.struts:struts2-core"],
    "PyPI": ["urllib3", "django", "requests", "jinja2", "pillow", "flask", "werkzeug",
             "sqlalchemy", "cryptography", "starlette", "fastapi", "setuptools", "pydantic"],
    "Go": ["golang.org/x/net", "golang.org/x/crypto", "golang.org/x/text", "github.com/gin-gonic/gin",
           "github.com/docker/docker", "github.com/ethereum/go-ethereum"],
    "RubyGems": ["rack", "rails", "nokogiri", "actionpack", "devise"],
    "Packagist": ["symfony/http-foundation", "laravel/framework", "guzzlehttp/guzzle", "phpunit/phpunit"],
}

SEV_MAP = {"CRITICAL": 4, "HIGH": 3, "MODERATE": 2, "MEDIUM": 2, "LOW": 1}
SEV_OUT = {"CRITICAL": "CRITICAL", "HIGH": "HIGH", "MODERATE": "MEDIUM", "MEDIUM": "MEDIUM", "LOW": "LOW"}

def query(ecosystem, name):
    body = json.dumps({"package": {"name": name, "ecosystem": ecosystem}}).encode()
    req = urllib.request.Request(OSV, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode())["vulns"]

def severity_of(v):
    s = (v.get("database_specific") or {}).get("severity")
    if s:
        return s.upper()
    for sev in v.get("severity", []):
        if sev.get("type") in ("CVSS_V3", "CVSS_V4"):
            try:
                score = float(sev.get("score", 0))
                return "CRITICAL" if score >= 9 else "HIGH" if score >= 7 else "MODERATE" if score >= 4 else "LOW"
            except ValueError:
                pass
    return "LOW"

def cvss_of(v):
    for sev in v.get("severity", []):
        if sev.get("type") in ("CVSS_V3", "CVSS_V4"):
            return sev.get("score", "")
    return ""

def ranges_of(a):
    out = []
    for r in a.get("ranges", []):
        parts = []
        first = True
        for ev in r.get("events", []):
            if "introduced" in ev and ev["introduced"] != "0":
                if not first: parts.append(", ")
                parts.append(">=" + ev["introduced"])
                first = False
            elif "introduced" in ev:
                first = True
            if "fixed" in ev:
                if not first: parts.append(", ")
                parts.append("< " + ev["fixed"])
                first = False
        if parts:
            out.append("".join(parts))
    return out

def convert(v):
    affected = []
    for a in v.get("affected", []):
        pkg = (a.get("package") or {}).get("name")
        eco = (a.get("package") or {}).get("ecosystem")
        if not pkg:
            continue
        ranges = ranges_of(a)
        versions = a.get("versions", []) or []
        if not ranges and not versions:
            continue
        affected.append({"ecosystem": eco, "packageName": pkg, "ranges": ranges, "versions": versions[:50]})
    if not affected:
        return None
    details = v.get("details") or ""
    return {
        "id": v.get("id"),
        "aliases": v.get("aliases", []),
        "summary": (v.get("summary") or "").strip(),
        "details": details[:1000],
        "severity": SEV_OUT.get(severity_of(v), "MEDIUM"),
        "cvss": cvss_of(v),
        "published": v.get("published"),
        "modified": v.get("modified"),
        "references": [r.get("url") for r in v.get("references", []) if r.get("url")][:6],
        "affected": affected,
    }

def main():
    out = []
    stats = {}
    for eco, names in PACKAGES.items():
        for name in names:
            try:
                vulns = query(eco, name)
            except Exception as e:
                print(f"  ! {eco}:{name} 查询失败: {e}", file=sys.stderr)
                continue
            ranked = sorted(vulns, key=lambda v: -SEV_MAP.get(severity_of(v), 0))
            keep = []
            for v in ranked:
                c = convert(v)
                if c and c["severity"] in ("CRITICAL", "HIGH", "MEDIUM"):
                    keep.append(c)
                if len(keep) >= 6:
                    break
            out.extend(keep)
            stats[eco] = stats.get(eco, 0) + len(keep)
            print(f"  {eco}:{name} -> {len(keep)} 条")
    out.sort(key=lambda v: -SEV_MAP.get(v["severity"], 0))
    with open("config/vulndb/codeguard-vulndb.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"\n共生成 {len(out)} 条漏洞记录")
    for eco, n in stats.items():
        print(f"  {eco}: {n}")

if __name__ == "__main__":
    main()
