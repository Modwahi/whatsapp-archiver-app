"""يولّد ملفات اختبار من أداة Python المرجعية (Modwahi/whatsapp-archiver) لتتحقق اختبارات Kotlin
أن ناتج التطبيق مطابق لها بايتاً ببايت.

الاستخدام:
    git clone https://github.com/Modwahi/whatsapp-archiver ../whatsapp-archiver
    python -m venv venv && venv/bin/pip install -e ../whatsapp-archiver
    TZ=Asia/Aden venv/bin/python tools/gen_fixtures.py ../whatsapp-archiver core/src/test/resources/fixtures

كل سيناريو = مجلد فيه خطوات step1, step2, ... وكل خطوة فيها:
    input/msgstore.db.crypt15   النسخة المشفرة (المفتاح في key.txt)
    input/wa/                   ملفات الوسائط في مجلد واتساب وقت هذه الخطوة
    step.json                   الوقت now، mtime للنسخة، خيارات التشغيل، والنتيجة المتوقعة (ok/None/...)
    expected/remote/            شجرة الدرايف بعد التشغيل (بما فيها _سجل التشغيل)
    expected/summary.txt        نص الملخص
"""

import json
import os
import shutil
import sqlite3
import sys
import time
from datetime import datetime
from pathlib import Path

os.environ["TZ"] = "Asia/Aden"
time.tzset()

ref = Path(sys.argv[1]).resolve()
out_root = Path(sys.argv[2]).resolve()
sys.path.insert(0, str(ref))
sys.path.insert(0, str(ref / "tests"))

import wa_archiver.runner as runner  # noqa: E402
from wa_archiver.config import ChatSpec  # noqa: E402
from wa_archiver.logutil import setup_logging  # noqa: E402
from wa_archiver.uploader import LocalUploader, UploadError  # noqa: E402
from wa_archiver.wadb import WaDb  # noqa: E402
from wa_archiver.crypto import encrypt_for_tests  # noqa: E402
import fakewa  # noqa: E402
from fakewa import GROUP, KEY, NOW, PRIVATE, build_env  # noqa: E402

CONTACTS = {"967771111111": "أحمد"}
_Orig = runner.Summary
_started = [None]
runner.Summary = lambda **kw: _Orig(started=_started[0], **kw)


class FailFor(LocalUploader):
    def __init__(self, base, bad):
        super().__init__(base)
        self.bad = bad

    def upload_dir(self, local_dir, rel_dir):
        if rel_dir == self.bad:
            raise UploadError("انقطع الاتصال")
        super().upload_dir(local_dir, rel_dir)


def cfg_json(cfg):
    return {
        "chats": [{"jid": c.jid, "name": c.name} for c in cfg.chats],
        "first_run_days": cfg.first_run_days,
        "max_media_mb": cfg.max_media_mb,
        "stale_backup_hours": cfg.stale_backup_hours,
        "me_name": cfg.me_name,
        "timezone": "Asia/Aden",
        "contacts": CONTACTS,
    }


class Scenario:
    def __init__(self, name, tmp):
        self.dir = out_root / name
        shutil.rmtree(self.dir, ignore_errors=True)
        self.dir.mkdir(parents=True)
        self.env = build_env(tmp / name)
        self.n = 0

    def step(self, *, now=NOW, dry_run=False, scheduled=False, key=KEY, fail_upload=None, note=""):
        env = self.env
        self.n += 1
        sd = self.dir / f"step{self.n}"
        (sd / "input").mkdir(parents=True)
        if env.backup.exists():
            shutil.copy2(env.backup, sd / "input" / "msgstore.db.crypt15")
        shutil.copytree(env.wa_dir, sd / "input" / "wa", ignore=shutil.ignore_patterns("Databases"))
        mtime = env.backup.stat().st_mtime if env.backup.exists() else None
        state_existed = env.paths.state.exists()
        _started[0] = datetime.fromtimestamp(now)
        log = setup_logging(env.paths.logs)
        dry_dir = None
        if dry_run:
            dry_dir = sd / "_dry"
            up = LocalUploader(dry_dir / ".no-remote")
        elif fail_upload:
            up = FailFor(env.remote, fail_upload)
        else:
            up = LocalUploader(env.remote)
        s = runner.run(env.cfg, env.paths, key, up, log, contacts=CONTACTS, dry_run=dry_run,
                       scheduled=scheduled, dry_dir=dry_dir, now=now)
        exp = sd / "expected"
        exp.mkdir()
        if env.remote.exists():
            shutil.copytree(env.remote, exp / "remote")
        if dry_dir:
            shutil.move(str(dry_dir), str(exp / "dry"))
        if s is not None:
            (exp / "summary.txt").write_bytes(s.render().encode("utf-8"))
        st = json.loads(env.paths.state.read_text(encoding="utf-8")) if env.paths.state.exists() else None
        step = {
            "note": note, "state_before_exists": state_existed, "now": now, "backup_mtime": mtime, "dry_run": dry_run, "scheduled": scheduled,
            "key": key, "fail_upload": fail_upload, "config": cfg_json(env.cfg),
            "result": None if s is None else {
                "ok": s.ok, "errors": s.errors, "warnings": s.warnings,
                "chats": [{"jid": c.jid, "name": c.name, "messages": c.messages, "media": c.media,
                           "media_missing": c.media_missing, "media_too_large": c.media_too_large,
                           "recovered": c.recovered, "error": c.error} for c in s.chats],
            },
            "state": st,
        }
        (sd / "step.json").write_text(json.dumps(step, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return s


def main():
    tmp = out_root.parent / "_gen_tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    out_root.mkdir(parents=True, exist_ok=True)
    (out_root / "key.txt").write_text(KEY + "\n")

    # 1) التسلسل الكامل: أول تشغيل، تشغيل بلا جديد، ثم رسائل جديدة + وسيط نزل متأخراً
    sc = Scenario("full_sequence", tmp)
    sc.step(note="أول تشغيل")
    sc.step(note="تشغيل ثانٍ بلا جديد")
    f = sc.env.fake
    f.msg(GROUP, "2026-10-31T20:00:00", "طلب جديد", sender="967771111111@s.whatsapp.net")
    f.msg(GROUP, "2026-10-31T05:00:00", "وصلت متأخرة", sender="967771111111@s.whatsapp.net")
    f.msg(PRIVATE, "2026-11-02T08:00:00", "رسالة \"مقتبسة\" وفيها \\ وتاب\tهنا 😀")
    (sc.env.wa_dir / "Media/WhatsApp Images/IMG-20261031-WA0002.jpg").write_bytes(b"NOW-DOWNLOADED")
    sc.env.encrypt(mtime=NOW + 86400 - 7200)
    sc.step(now=NOW + 86400, note="رسائل جديدة ووسيط نزل")
    sc.step(now=NOW + 86400 + 3600, scheduled=True, note="مجدول بلا نسخة جديدة")

    # 2) فقدان state.json: دمج مع ما على الدرايف دون تكرار
    sc = Scenario("state_lost", tmp)
    sc.step(note="أول تشغيل")
    sc.env.paths.state.unlink()
    sc.step(note="بعد حذف الحالة")

    # 3) نسخة قديمة (تحذير) + وسيط أكبر من الحد
    sc = Scenario("stale_and_large", tmp)
    big = sc.env.wa_dir / "Media/WhatsApp Video/VID-1.mp4"
    big.parent.mkdir(parents=True)
    big.write_bytes(b"0" * (2 * 1024 * 1024))
    sc.env.fake.msg(GROUP, "2026-11-01T10:00:00", None, sender="967771111111@s.whatsapp.net", mtype=3,
                    media=("Media/WhatsApp Video/VID-1.mp4", "video/mp4", None))
    sc.env.encrypt(mtime=NOW - 40 * 3600)
    sc.step(note="نسخة عمرها 40 ساعة ووسيط 2MB والحد 1MB")

    # 4) تجربة dry-run
    sc = Scenario("dry_run", tmp)
    sc.step(dry_run=True, note="تجربة بدون رفع")

    # 5) فشل الرفع لمحادثة واحدة ثم إعادة
    sc = Scenario("upload_failure", tmp)
    sc.step(fail_upload="طلبات التحويل_ الشركة", note="فشل رفع المجموعة")
    sc.step(note="إعادة التشغيل")

    # 6) مفتاح خاطئ
    sc = Scenario("wrong_key", tmp)
    sc.step(key="b" * 64, note="مفتاح خاطئ")

    # 7) محادثة غير موجودة
    sc = Scenario("unknown_chat", tmp)
    sc.env.cfg.chats.append(ChatSpec("120363000000000000@g.us"))
    sc.step(note="محادثة غير موجودة")

    # 8) wadb: قائمة المحادثات ورسائل كل محادثة كما تقرؤها الأداة
    env = build_env(tmp / "wadb")
    db = WaDb(env.db_path)
    chats = db.list_chats()
    dump = {"chats": [c.__dict__ for c in chats], "messages": {}}
    for c in chats:
        dump["messages"][c.jid] = [m.__dict__ for m in db.messages(c, since_ms=0)]
    db.close()
    wd = out_root / "wadb"
    shutil.rmtree(wd, ignore_errors=True)
    wd.mkdir()
    shutil.copy2(env.backup, wd / "msgstore.db.crypt15")
    (wd / "expected.json").write_text(json.dumps(dump, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # 9) crypt15: ملف أكبر من دفعة (streaming)، ونسخة multi-file بلا md5
    cd = out_root / "crypto"
    shutil.rmtree(cd, ignore_errors=True)
    cd.mkdir()
    plain = cd / "plain.db"
    c = sqlite3.connect(plain)
    c.execute("CREATE TABLE t (x TEXT)")
    import random
    rnd = random.Random(7)
    c.executemany("INSERT INTO t VALUES (?)",
                  [("نص عربي " + rnd.randbytes(300).hex(),) for _ in range(6000)])
    c.commit()
    c.close()
    encrypt_for_tests(plain, cd / "big.crypt15", KEY)
    encrypt_for_tests(plain, cd / "multifile.crypt15", KEY, multifile=True)
    import hashlib
    (cd / "plain.sha256").write_text(hashlib.sha256(plain.read_bytes()).hexdigest() + f" {plain.stat().st_size}\n")
    plain.unlink()

    shutil.rmtree(tmp, ignore_errors=True)
    print("done:", out_root)


main()
