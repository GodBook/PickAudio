"""Seed and inspect upgrade fixtures on the task's Android emulator only."""
import json
import shlex
import subprocess
import sys
import time
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = "D:/dev/android-sdk/platform-tools/adb.exe"
SERIAL = "emulator-5554"
DB = "/data/user/0/com.pickaudio/databases/pickaudio.db"


def adb(*args):
    result = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()


def sql(statement):
    return adb("shell", "sqlite3 " + shlex.quote(DB) + " " + shlex.quote(statement))


def seed():
    assert sql("PRAGMA user_version;") == "1", "Seed only the known v1 test installation"
    artifact = ROOT / "app/build/ux-verification/upgrade.wav"
    artifact.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(artifact), "wb") as audio:
        audio.setparams((1, 2, 44100, 0, "NONE", "not compressed"))
        audio.writeframes(bytes(44100 * 2 * 20))
    adb("push", str(artifact), "/data/user/0/com.pickaudio/files/ux-upgrade.wav")
    uid = adb("shell", "stat -c %u /data/user/0/com.pickaudio")
    adb("shell", "mkdir -p /data/user/0/com.pickaudio/cache/downloads")
    adb("shell", "dd if=/dev/zero of=/data/user/0/com.pickaudio/cache/downloads/temp_ux_upgrade_download.part bs=1024 count=1")
    adb("shell", f"chown {uid}:{uid} /data/user/0/com.pickaudio/files/ux-upgrade.wav /data/user/0/com.pickaudio/cache/downloads /data/user/0/com.pickaudio/cache/downloads/temp_ux_upgrade_download.part")
    timestamp = "CAST(strftime('%s','now') AS INTEGER)*1000"
    sql(f"""
        BEGIN;
        INSERT INTO tracks(id,title,artist,album,durationMs,coverUri,trackNumber,createdAt) VALUES('ux_upgrade_track','覆盖升级保留验证','拾音验证','体验升级',20000,NULL,1,{timestamp});
        INSERT INTO local_assets(trackId,uri,sourceType,fileSize,mimeType,format,isAvailable,fileHash) VALUES('ux_upgrade_track','file:///data/user/0/com.pickaudio/files/ux-upgrade.wav','SAF_FILE',1764044,'audio/wav','wav',1,NULL);
        INSERT INTO playlists(id,name,coverUri,sortOrder,isSystem,createdAt,updatedAt) VALUES('ux_upgrade_playlist','升级前的歌单',NULL,1,0,{timestamp},{timestamp});
        INSERT INTO playlist_tracks(playlistId,trackId,sortOrder,addedAt) VALUES('ux_upgrade_playlist','ux_upgrade_track',0,{timestamp});
        INSERT INTO favorites(trackId,addedAt) VALUES('ux_upgrade_track',{timestamp});
        INSERT INTO queue_entries(trackId,queueOrder) VALUES('ux_upgrade_track',0);
        INSERT INTO playback_snapshot(id,currentTrackId,progressMs,playbackMode,shuffleOrderJson,shuffleHistoryJson,updatedAt) VALUES(1,'ux_upgrade_track',3500,'LIST_LOOP',NULL,NULL,{timestamp});
        INSERT INTO lyric_records(trackId,sourceType,content,offsetMs,updatedAt) VALUES('ux_upgrade_track','MANUAL_LRC','[00:00.00]升级后仍能保留歌单\n[00:05.00]歌词校准按歌曲保存\n[00:10.00]下载进度已保留\n[00:15.00]轻点任一行可以跳转',300,{timestamp});
        INSERT INTO download_tasks(id,trackId,title,artist,album,coverUri,platform,platformSongId,targetQuality,status,downloadedBytes,totalBytes,tempFilePath,targetUri,errorMessage,createdAt,updatedAt) VALUES('ux_upgrade_download','ux_upgrade_track','升级前暂停的任务','拾音验证','体验升级',NULL,'wy','verification_no_fetch','320k','PAUSED',1024,4096,'/data/user/0/com.pickaudio/cache/downloads/temp_ux_upgrade_download.part',NULL,NULL,{timestamp},{timestamp});
        COMMIT;
    """)
    print("Seeded v1 song, playlist, favorite, queue, lyrics, offset and partial download")


def verify():
    for _ in range(30):
        if sql("PRAGMA user_version;") == "2":
            break
        time.sleep(0.2)
    assert sql("PRAGMA user_version;") == "2"
    for table, condition in [
        ("tracks", "id='ux_upgrade_track'"), ("playlists", "id='ux_upgrade_playlist'"),
        ("playlist_tracks", "playlistId='ux_upgrade_playlist' AND trackId='ux_upgrade_track'"),
        ("favorites", "trackId='ux_upgrade_track'"), ("local_assets", "trackId='ux_upgrade_track'"),
        ("queue_entries", "trackId='ux_upgrade_track'"),
        ("lyric_records", "trackId='ux_upgrade_track' AND offsetMs=300"),
        ("download_tasks", "id='ux_upgrade_download' AND downloadedBytes=1024 AND status='PAUSED'"),
        ("platform_source_selection", "platform='wy' AND sourceId='builtin_aggregate'"),
    ]:
        assert sql(f"SELECT COUNT(*) FROM {table} WHERE {condition};") == "1", table
    partial = "/data/user/0/com.pickaudio/files/download_parts/temp_ux_upgrade_download.part"
    for _ in range(30):
        if adb("shell", f"if [ -f {partial} ]; then stat -c %s {partial}; fi") == "1024":
            break
        time.sleep(0.2)
    assert adb("shell", f"stat -c %s {partial}") == "1024"
    result = {"from": "1.1.5", "to": "1.2.0", "schema": 2, "data_preserved": True, "partial_bytes_preserved": 1024}
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    assert "sdk_gphone" in adb("shell", "getprop ro.product.model")
    assert adb("shell", "getprop ro.build.type") == "userdebug"
    {"seed": seed, "verify": verify}[sys.argv[1]]()
