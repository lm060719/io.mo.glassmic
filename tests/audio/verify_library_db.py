"""Host SQLite regression checks. Run after assembleDebug; no Android device/emulator.

Applies the actual migration SQL to a v1 fixture and compares the resulting schema
with Room's generated v2 creation SQL, including defaults, indices and foreign keys.
"""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
DB_SOURCE = ROOT / "app/src/main/java/io/mo/glassmic/data/db/GlassDatabase.kt"
DAO_SOURCE = ROOT / "app/src/main/java/io/mo/glassmic/data/db/AudioDao.kt"
GENERATED = ROOT / "app/build/generated/ksp/debug/java/io/mo/glassmic/data/db/GlassDatabase_Impl.java"
V1 = """
CREATE TABLE audio_groups (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL,
    emoji TEXT NOT NULL, sortOrder INTEGER NOT NULL, playbackPolicyOverride TEXT);
CREATE TABLE audio_clips (id TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL,
    displayName TEXT NOT NULL, fileName TEXT NOT NULL, relativePath TEXT NOT NULL,
    mimeType TEXT NOT NULL, durationMs INTEGER NOT NULL, sizeBytes INTEGER NOT NULL,
    sampleRate INTEGER NOT NULL, channels INTEGER NOT NULL, createdAt INTEGER NOT NULL,
    sortOrder INTEGER NOT NULL, FOREIGN KEY(groupId) REFERENCES audio_groups(id) ON DELETE CASCADE);
CREATE INDEX index_audio_clips_groupId ON audio_clips(groupId);
INSERT INTO audio_groups VALUES ('g', 'My imports', 'music', 9, 'LOOP');
INSERT INTO audio_clips VALUES ('c', 'g', 'My renamed clip', 'sample.wav', 'g/c.wav',
    'audio/wav', 5000, 480000, 48000, 1, 123456, 2);
"""


def queries():
    text = DAO_SOURCE.read_text(encoding="utf-8")
    return dict((name, sql) for sql, name in re.findall(
        r'@Query\("([^"\n]+)"\)\s+(?:suspend )?fun (\w+)', text))


class LibraryDatabaseRegression(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.executescript(V1)
        self.legacy_group = self.db.execute("SELECT * FROM audio_groups").fetchone()
        self.legacy_clip = self.db.execute("SELECT * FROM audio_clips").fetchone()
        migration = re.findall(r'db.execSQL\("(ALTER TABLE [^"]+)"\)', DB_SOURCE.read_text(encoding="utf-8"))
        self.assertEqual(len(migration), 5, "v1-to-v2 migration must be present")
        for sql in migration:
            self.db.execute(sql)

    def tearDown(self):
        self.db.close()

    def test_preserves_imports_names_order_policy_and_paths(self):
        group = self.db.execute("SELECT * FROM audio_groups").fetchone()
        clip = self.db.execute("SELECT * FROM audio_clips").fetchone()
        self.assertEqual(group[:5], self.legacy_group)
        self.assertEqual(clip[:12], self.legacy_clip)
        self.assertEqual(group[5:], (None, 0))
        self.assertEqual(clip[12:], (None, 0, 0))

    def test_matches_room_generated_schema(self):
        fresh = sqlite3.connect(":memory:")
        self.addCleanup(fresh.close)
        ddl = re.findall(r'db.execSQL\("(CREATE (?:TABLE|INDEX)[^"]+)"\)', GENERATED.read_text(encoding="utf-8"))
        self.assertTrue(ddl, "Run assembleDebug first to generate Room schema")
        for sql in ddl:
            fresh.execute(sql)
        for table in ("audio_groups", "audio_clips"):
            for pragma in ("table_info", "foreign_key_list", "index_list"):
                self.assertEqual(self.db.execute(f"PRAGMA {pragma}({table})").fetchall(),
                                 fresh.execute(f"PRAGMA {pragma}({table})").fetchall())

    def test_remove_restore_and_all_observers(self):
        sql = queries()
        self.db.execute("UPDATE audio_clips SET sourceUri = 'content://folder/file', relativePath = '' WHERE id = 'c'")
        self.db.execute(sql["hideLinkedClip"], {"id": "c"})
        self.assertEqual(self.db.execute(sql["observeAllClips"]).fetchall(), [])
        self.assertEqual(self.db.execute(sql["observeClipsInGroup"], {"groupId": "g"}).fetchall(), [])
        self.assertEqual(len(self.db.execute(sql["clipsInGroup"], {"groupId": "g"}).fetchall()), 1)
        self.db.execute(sql["restoreLinkedClips"], {"groupId": "g"})
        self.assertEqual(len(self.db.execute(sql["observeAllClips"]).fetchall()), 1)

    def test_imports_cannot_be_hidden_and_unlink_cascades(self):
        sql = queries()
        self.db.execute(sql["hideLinkedClip"], {"id": "c"})
        self.assertEqual(len(self.db.execute(sql["observeAllClips"]).fetchall()), 1)
        self.db.execute(sql["deleteGroup"], {"id": "g"})
        self.assertEqual(self.db.execute(sql["clipsInGroup"], {"groupId": "g"}).fetchall(), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
