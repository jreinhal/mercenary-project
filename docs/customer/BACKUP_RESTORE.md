# Backup and Restore (MongoDB)

This guide covers backups for customer deployments.

## 1) Logical backups (recommended)
Use mongodump and mongorestore.

Backup:
```
mongodump --db mercenary --out D:\MongoDB\backups\mercenary_YYYYMMDD
```

Restore:
```
mongorestore --db mercenary D:\MongoDB\backups\mercenary_YYYYMMDD\mercenary
```

## 2) Verify service account (Windows)
MongoDB runs as a Windows service. Confirm the account:
```
Get-CimInstance Win32_Service -Filter "Name='MongoDB'" | Select-Object Name,StartName
```

Ensure the account has access to:
- D:\MongoDB\data
- D:\MongoDB\log

Grant access (example for NetworkService):
```
icacls D:\MongoDB\data /grant "NT AUTHORITY\NetworkService:(OI)(CI)F"
icacls D:\MongoDB\log /grant "NT AUTHORITY\NetworkService:(OI)(CI)F"
```

## 3) Offline backup (data directory)
Use only when MongoDB is stopped.

```
Stop-Service MongoDB
Copy-Item -Recurse D:\MongoDB\data D:\MongoDB\backups\data_snapshot_YYYYMMDD
Start-Service MongoDB
```

## 4) Recovery notes
- If authentication fails after restore, verify users in the admin database.
- If you do not know the admin password, restore from a known-good backup or reset the data directory.
