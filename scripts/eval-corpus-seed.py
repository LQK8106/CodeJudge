#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
评测语料补齐脚本（任务 A 前置）：通过**现有业务接口**生成带真实判题结论的提交，
再调用 AI 点评接口产出 `ai_review.content` 正文。

为什么需要它：
  `logs/ai-ttft-*.json` 只存 reviewId 与 contentChars，没有点评正文；
  历史 `ai_review` 几乎全是 AC + 主动点评，verdict 覆盖不足 4 类。
  本脚本不改动业务代码，只按产品既有链路（POST /submissions → 轮询终态 →
  POST /ai/review）触发新样本，并把「补齐条数」如实落盘。

用法（仓库根目录）：
  py -3 scripts/eval-corpus-seed.py                     # 默认 40 条 4001：AC10 WA10 TLE8 CE6 RE6
  py -3 scripts/eval-corpus-seed.py --ac 4 --wa 4 --tle 2 --ce 2 --re 2
  py -3 scripts/eval-corpus-seed.py --multi             # 8 道题 × 4 条 = 32 条（更难的多样语料）
  py -3 scripts/eval-corpus-seed.py --balanced          # 9 道已发布题 × 5 类结论 = 45 条（题目均衡）
输出：logs/eval-corpus-seed-<时间戳>.json（每条样本的 submissionId / reviewId / verdict / 正文字数）
"""

import argparse
import json
import pathlib
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATEWAY = "http://localhost:9080"
PHONE = "13900000001"   # 演示学员一（sql/seed.sql）
PASSWORD = "123456"
PROBLEM_ID = 4001
LANGUAGE = "JAVA"
RUN_TS = time.strftime("%Y%m%d-%H%M%S")

# ---------------------------------------------------------------- 单题（4001 A+B）模板
# 该题用例含 2147483647×2 溢出，五类解法只差「一行实现」，verdict 全部由真实沙箱判题给出。

CODE_AC = """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long a = sc.nextLong(), b = sc.nextLong();
        System.out.println(a + b);
    }
}
// %NONCE%
"""

CODE_WA = """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int a = sc.nextInt(), b = sc.nextInt();
        System.out.println(a + b);
    }
}
// %NONCE%
"""

CODE_TLE = """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long a = sc.nextLong(), b = sc.nextLong();
        long s = 0;
        while (a > 0) { s++; }
        System.out.println(s);
    }
}
// %NONCE%
"""

CODE_CE = """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long a = sc.nextLong() long b = sc.nextLong();
        System.out.println(a + b)
    }
}
// %NONCE%
"""

CODE_RE = """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long a = sc.nextLong(), b = sc.nextLong();
        System.out.println(a / (b - b));
    }
}
// %NONCE%
"""

PLANS = {"AC": CODE_AC, "WA": CODE_WA, "TLE": CODE_TLE, "CE": CODE_CE, "RE": CODE_RE}

# ---------------------------------------------------------------- 多题计划（更难的多样语料）
# 每题给 AC 与 WA 各一份「只差一行」的实现；另按题目特点配 TLE / CE / RE 之一。
# 全部为 Java（可执行性规则引擎只编译 Java），判题结论仍由真实沙箱给出。

MULTI_CODES = {
    4002: {
        "AC": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long n = sc.nextLong();
        System.out.println(n * (n + 1) / 2);
    }
}
// %NONCE%
""",
        "WA": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        int sum = n * (n + 1) / 2;
        System.out.println(sum);
    }
}
// %NONCE%
""",
        "TLE": """import java.math.BigInteger;
import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long n = sc.nextLong();
        BigInteger sum = BigInteger.ZERO;
        for (long i = 1; i <= n; i++) {
            sum = sum.add(BigInteger.valueOf(i));
        }
        System.out.println(sum);
    }
}
// %NONCE%
""",
    },
    4004: {
        "AC": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long max = Long.MIN_VALUE, min = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            long v = sc.nextLong();
            if (v > max) max = v;
            if (v < min) min = v;
        }
        System.out.println(max - min);
    }
}
// %NONCE%
""",
        "WA": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long max = 0, min = 0;
        for (int i = 0; i < n; i++) {
            long v = sc.nextLong();
            if (v > max) max = v;
            if (v < min) min = v;
        }
        System.out.println(max - min);
    }
}
// %NONCE%
""",
        "RE": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        int[] a = new int[n];
        for (int i = 0; i <= n; i++) {
            a[i] = sc.nextInt();
        }
        System.out.println(0);
    }
}
// %NONCE%
""",
    },
    4007: {
        "AC": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.next();
        StringBuilder sb = new StringBuilder(s);
        System.out.println(s.contentEquals(sb.reverse()) ? "YES" : "NO");
    }
}
// %NONCE%
""",
        "WA": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.next();
        System.out.println(s == new StringBuilder(s).reverse().toString() ? "YES" : "NO");
    }
}
// %NONCE%
""",
        "CE": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.next()
        System.out.println(s);
    }
}
// %NONCE%
""",
    },
    4008: {
        "AC": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), q = sc.nextInt();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = sc.nextInt();
        for (int k = 0; k < q; k++) {
            int x = sc.nextInt();
            int lo = 0, hi = n - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) / 2;
                if (a[mid] == x) { ans = mid; hi = mid - 1; }
                else if (a[mid] < x) lo = mid + 1;
                else hi = mid - 1;
            }
            System.out.println(ans);
        }
    }
}
// %NONCE%
""",
        "WA": """import java.util.Arrays;
import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), q = sc.nextInt();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = sc.nextInt();
        for (int k = 0; k < q; k++) {
            System.out.println(Arrays.binarySearch(a, sc.nextInt()));
        }
    }
}
// %NONCE%
""",
        "CE": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), q = sc.nextInt()
        System.out.println(n + q);
    }
}
// %NONCE%
""",
    },
    4009: {
        "AC": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long[] f = new long[n + 1];
        f[0] = 1;
        for (int i = 1; i <= n; i++) {
            f[i] = f[i - 1] + (i >= 2 ? f[i - 2] : 0);
        }
        System.out.println(f[n]);
    }
}
// %NONCE%
""",
        "WA": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        int[] f = new int[n + 1];
        f[0] = 0;
        f[1] = 1;
        for (int i = 2; i <= n; i++) f[i] = f[i - 1] + f[i - 2];
        System.out.println(f[n]);
    }
}
// %NONCE%
""",
        "TLE": """import java.util.Scanner;
public class Main {
    static long fib(int n) {
        if (n <= 1) return 1;
        return fib(n - 1) + fib(n - 2);
    }
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.println(fib(sc.nextInt()));
    }
}
// %NONCE%
""",
    },
    4010: {
        "AC": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int m = sc.nextInt();
        int[] d = {100, 50, 20, 10, 5, 1};
        int cnt = 0;
        for (int x : d) { cnt += m / x; m %= x; }
        System.out.println(cnt);
    }
}
// %NONCE%
""",
        "WA": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int m = sc.nextInt();
        int[] d = {100, 50, 10, 5, 1};
        int cnt = 0;
        for (int x : d) { cnt += m / x; m %= x; }
        System.out.println(cnt);
    }
}
// %NONCE%
""",
        "RE": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int m = sc.nextInt();
        int[] dp = new int[m];
        dp[m] = 0;
        System.out.println(dp[0]);
    }
}
// %NONCE%
""",
    },
    4011: {
        "AC": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), target = sc.nextInt();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = sc.nextInt();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (a[i] + a[j] == target) { System.out.println(i + " " + j); return; }
            }
        }
    }
}
// %NONCE%
""",
        "WA": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), target = sc.nextInt();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = sc.nextInt();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (a[i] + a[j] == target) { System.out.println((i + 1) + " " + (j + 1)); return; }
            }
        }
    }
}
// %NONCE%
""",
        "CE": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), target = sc.nextInt()
        System.out.println(n + target);
    }
}
// %NONCE%
""",
    },
    4012: {
        "AC": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        int[][] a = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) a[i][j] = sc.nextInt();
        }
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < n; j++) {
                if (j > 0) sb.append(' ');
                sb.append(a[n - 1 - j][i]);
            }
            System.out.println(sb);
        }
    }
}
// %NONCE%
""",
        "WA": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        int[][] a = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) a[i][j] = sc.nextInt();
        }
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < n; j++) {
                if (j > 0) sb.append(' ');
                sb.append(a[j][i]);
            }
            System.out.println(sb);
        }
    }
}
// %NONCE%
""",
        "RE": """import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        int[][] a = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) a[i][j] = sc.nextInt();
        }
        for (int j = 0; j < n; j++) {
            System.out.println(a[n][j]);
        }
    }
}
// %NONCE%
""",
    },
}

# ---------------------------------------------------------------- 均衡计划（题目分布）
# 目的：让每个 verdict 层内的候选样本分散到多道题，避免单一题目占满抽样。
# 题目池只取 status=1（已发布）且有测试用例的题；4001 用单题模板，其余用 MULTI_CODES 的
# AC/WA 题解，TLE/CE/RE 用与题面无关的通用失败代码（失败类型由真实沙箱判定，不由本脚本指定）。
BALANCED_PROBLEMS = (4001, 4002, 4004, 4007, 4008, 4009, 4010, 4011, 4012)

GENERIC_CODES = {
    "TLE": """public class Main {
    public static void main(String[] args) {
        long x = 0;
        while (true) { x++; }
    }
}
// %NONCE%
""",
    "CE": """public class Main {
    public static void main(String[] args) {
        int a = 1
        System.out.println(a);
    }
}
// %NONCE%
""",
    "RE": """public class Main {
    public static void main(String[] args) {
        int z = 0;
        System.out.println(1 / z);
    }
}
// %NONCE%
""",
}


def balanced_code(pid, verdict):
    if pid == PROBLEM_ID:
        return PLANS[verdict]
    if verdict in ("AC", "WA"):
        return MULTI_CODES[pid][verdict]
    return GENERIC_CODES[verdict]


# 8 题 × 4 条：AC 2 + WA 1 + 特色失败 1（TLE/CE/RE 轮转）
MULTI_PLAN = (
    [(4002, "AC"), (4002, "AC"), (4002, "WA"), (4002, "TLE")]
    + [(4004, "AC"), (4004, "AC"), (4004, "WA"), (4004, "RE")]
    + [(4007, "AC"), (4007, "AC"), (4007, "WA"), (4007, "CE")]
    + [(4008, "AC"), (4008, "AC"), (4008, "WA"), (4008, "CE")]
    + [(4009, "AC"), (4009, "AC"), (4009, "WA"), (4009, "TLE")]
    + [(4010, "AC"), (4010, "AC"), (4010, "WA"), (4010, "RE")]
    + [(4011, "AC"), (4011, "AC"), (4011, "WA"), (4011, "CE")]
    + [(4012, "AC"), (4012, "AC"), (4012, "WA"), (4012, "RE")]
)

_login_lock = threading.Lock()


def login(phone=PHONE, password=PASSWORD, tries=12):
    """登录拿 JWT：网关登录限流 2 req/s 突发 5，失败指数退避。"""
    last = ""
    for i in range(tries):
        try:
            r = requests.post(f"{GATEWAY}/accounts/login",
                              json={"cellPhone": phone, "password": password}, timeout=15)
            d = r.json()
            token = (d.get("data") or {}).get("accessToken")
            if token:
                return token
            last = f"{r.status_code} {d.get('msg')}"
        except Exception as e:  # noqa: BLE001
            last = f"{type(e).__name__}: {e}"
        time.sleep(min(0.6 * (i + 1), 3.0))
    raise SystemExit(f"登录失败：{last}")


def submit(token, problem_id, code):
    r = requests.post(f"{GATEWAY}/submissions",
                      headers={"Authorization": f"Bearer {token}"},
                      json={"problemId": problem_id, "contestId": 0,
                            "language": LANGUAGE, "code": code}, timeout=20)
    d = r.json()
    data = d.get("data") or {}
    if d.get("code") == 200 and data.get("id"):
        return str(data["id"]), None
    return None, f"HTTP {d.get('code')} {d.get('msg')}"


def wait_terminal(token, sid, timeout_s=180):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            d = requests.get(f"{GATEWAY}/submissions/{sid}",
                             headers={"Authorization": f"Bearer {token}"}, timeout=10).json()
            data = d.get("data") or {}
            if data.get("status") not in ("PENDING", "JUDGING", None):
                return data.get("verdict")
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.6)
    return None


def review(token, sid, review_type):
    """非流式点评（POST /ai/review），阻塞到生成完成；返回 (reviewId, 正文字数, model, degraded)。"""
    t0 = time.time()
    r = requests.post(f"{GATEWAY}/ai/review",
                      headers={"Authorization": f"Bearer {token}"},
                      json={"submissionId": sid, "reviewType": review_type}, timeout=300)
    d = r.json()
    if d.get("code") != 200:
        return None, 0, None, None, f"HTTP {d.get('code')} {d.get('msg')}", round(time.time() - t0, 1)
    data = d.get("data") or {}
    return (data.get("id"), len(data.get("content") or ""), data.get("model"),
            data.get("degraded"), None, round(time.time() - t0, 1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--multi", action="store_true",
                    help="使用多题目语料计划（8 题 × 4 条 = 32 条）")
    ap.add_argument("--balanced", action="store_true",
                    help="题目均衡语料：9 道已发布题 × AC/WA/TLE/CE/RE 各 1 条 = 45 条")
    ap.add_argument("--ac", type=int, default=10)
    ap.add_argument("--wa", type=int, default=10)
    ap.add_argument("--tle", type=int, default=8)
    ap.add_argument("--ce", type=int, default=6)
    ap.add_argument("--re", type=int, default=6)
    ap.add_argument("--gap", type=float, default=2.4, help="提交间隔秒（限流 30/min/人）")
    ap.add_argument("--workers", type=int, default=3, help="点评并发（非流式，独立提交互不共享会话）")
    ap.add_argument("--out-dir", default=None, help="明细输出目录（默认 logs/，可指向可写目录）")
    args = ap.parse_args()

    if args.balanced:
        plan = [(pid, v) for pid in BALANCED_PROBLEMS for v in ("AC", "WA", "TLE", "CE", "RE")]
    elif args.multi:
        plan = list(MULTI_PLAN)
    else:
        plan = ([(PROBLEM_ID, "AC")] * args.ac + [(PROBLEM_ID, "WA")] * args.wa
                + [(PROBLEM_ID, "TLE")] * args.tle + [(PROBLEM_ID, "CE")] * args.ce
                + [(PROBLEM_ID, "RE")] * args.re)
    print(f"计划补齐 {len(plan)} 条：" + " ".join(
        f"{pid}/{v}" for pid, v in plan[:8]) + (" ……" if len(plan) > 8 else ""))

    token = login()
    print("登录成功")

    # 阶段一：串行提交 + 等终态（避免撞限流；TLE 单条要跑多个用例）
    submitted = []
    last = 0.0
    for i, (pid, v) in enumerate(plan):
        wait = args.gap - (time.time() - last)
        if wait > 0:
            time.sleep(wait)
        nonce = f"eval-{RUN_TS}-{pid}-{v}-{i}"
        if args.balanced:
            code = balanced_code(pid, v).replace("%NONCE%", nonce)
        elif args.multi:
            code = MULTI_CODES[pid][v].replace("%NONCE%", nonce)
        else:
            code = PLANS[v].replace("%NONCE%", nonce)
        sid, err = submit(token, pid, code)
        last = time.time()
        if not sid:
            print(f"  [{i + 1}/{len(plan)}] {pid}/{v} 提交失败：{err}")
            submitted.append({"targetVerdict": v, "problemId": pid,
                              "submissionId": None, "submitErr": err})
            continue
        verdict = wait_terminal(token, sid)
        print(f"  [{i + 1}/{len(plan)}] 题={pid} 目标={v} 实际={verdict} sid={sid}")
        submitted.append({"targetVerdict": v, "problemId": pid, "submissionId": sid,
                          "verdict": verdict, "reviewType": 2 if verdict == "AC" else 1})

    # 阶段二：并发点评（每条提交独立会话）
    def do_review(item):
        if not item.get("submissionId") or item.get("verdict") in (None, "SE"):
            item.setdefault("reviewErr", "提交未产出有效判题结论，跳过点评")
            return item
        # 点评可能被 LLM 侧限流（TooManyRequests）打断，失败即退避重试，最多 3 次
        for attempt in range(3):
            rid, chars, model, degraded, rerr, secs = review(token, item["submissionId"], item["reviewType"])
            item.update({"reviewId": rid, "contentChars": chars, "model": model,
                         "degraded": degraded, "reviewErr": rerr, "reviewSeconds": secs,
                         "reviewAttempts": attempt + 1})
            if rid and chars:
                break
            time.sleep(5 * (attempt + 1))
        return item

    print(f"\n开始并发点评（workers={args.workers}）……")
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        results = list(ex.map(do_review, submitted))

    ok = [r for r in results if r.get("reviewId") and r.get("contentChars", 0) > 0]
    empty = [r for r in results if r.get("reviewId") and r.get("contentChars", 0) == 0]
    failed = [r for r in results if not r.get("reviewId")]
    dist = {}
    for r in results:
        dist[r.get("verdict") or "NONE"] = dist.get(r.get("verdict") or "NONE", 0) + 1
    print(f"点评成功（正文非空）{len(ok)}/{len(results)}；空正文 {len(empty)}；失败 {len(failed)}")
    print("判题结论分布：" + json.dumps(dist, ensure_ascii=False))

    out_dir = pathlib.Path(args.out_dir) if args.out_dir else ROOT / "logs"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"eval-corpus-seed-{RUN_TS}.json"
    out.write_text(json.dumps({
        "meta": {"ts": RUN_TS, "gateway": GATEWAY,
                 "mode": "balanced" if args.balanced else ("multi" if args.multi else "single"),
                 "problemIds": sorted({pid for pid, _ in plan}),
                 "language": LANGUAGE, "account": PHONE,
                 "pipeline": "POST /submissions → 轮询终态 → POST /ai/review（AC=type2，非AC=type1）",
                 "note": "本脚本只走产品既有接口，不改业务代码；补齐条数=实际产出正文非空的条数"},
        "summary": {"planned": len(plan), "contentOk": len(ok), "empty": len(empty),
                    "failed": len(failed), "verdictDist": dist},
        "samples": results,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"明细已写入 {out}")


if __name__ == "__main__":
    main()
